package com.vstream.Vidiserver;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Locale;
import java.util.function.LongFunction;

/**
 * The live DJ door of one station: listens on the station's own port for an Icecast source connection
 * (BUTT, Mixxx, RadioDJ, Rocket Broadcaster...: server type Icecast, any mount such as /live). The station's own
 * login is user "source" with the station's stream password; each remote DJ uses their own login and password. The sound (MP3, AAC or Ogg) is decoded by FFmpeg and handed to the station's
 * engine, which fades the playlist out and the DJ in, and goes back to the playlist when the DJ disconnects.
 * One DJ at a time per station.
 */
public final class LiveInput {

    /** Who may come in. */
    public static final class Login {
        /** Shown as "LIVE: name" (the DJ's name, or what the software sends). */
        public final String name;
        /** The remote DJ (0 = the station's own login). */
        public final long djId;
        /** Set when the login is right but the DJ may not go live now (e.g. outside their hours). */
        public final String refused;

        public Login(String name, long djId, String refused) {
            this.name = name;
            this.djId = djId;
            this.refused = refused;
        }
    }

    /** What the door needs from the radio. */
    public interface Station {
        /** The station's current live password, or null if the station no longer exists / is not allowed live. */
        String password(long stationId);

        /**
         * Checks a login (user + password from the DJ software). Null = wrong login. The default only knows the
         * station's own password (any user name).
         */
        default Login login(long stationId, String user, String pass, String softwareName) {
            String want = password(stationId);
            if (want == null || want.isEmpty()) {
                return new Login("", 0, "Live DJs are not allowed on this station right now.");
            }
            return java.security.MessageDigest.isEqual(want.getBytes(StandardCharsets.UTF_8),
                    (pass == null ? "" : pass).getBytes(StandardCharsets.UTF_8)) ? new Login(softwareName, 0, null) : null;
        }

        /** A show ended (for the station's history). */
        default void showEnded(long stationId, long djId, String name, long start, long end) {
        }

        /** Starts FFmpeg decoding whatever is written to it into raw 44.1 kHz stereo. */
        Process decoder() throws IOException;

        /** A DJ connected: the engine should play this source. */
        void connected(long stationId, LiveSource source, String djName);

        /** The DJ left. */
        void disconnected(long stationId, LiveSource source);
    }

    private final long stationId;
    private final int port;
    private final Station station;
    private ServerSocket server;
    private Thread acceptor;
    private volatile LiveSource active;
    private volatile Socket activeSocket;
    private volatile String dj = "";
    private volatile long djId;
    private volatile long since;
    private volatile String lastProblem = "";

    public LiveInput(long stationId, int port, Station station) {
        this.stationId = stationId;
        this.port = port;
        this.station = station;
    }

    public int port() {
        return port;
    }

    public boolean connected() {
        LiveSource s = active;
        return s != null && !s.finished();
    }

    public String djName() {
        return dj;
    }

    public long since() {
        return since;
    }

    public String lastProblem() {
        return lastProblem;
    }

    /** Opens the port. Returns an error message, or null when listening. */
    public synchronized String start() {
        try {
            server = new ServerSocket();
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress(port));
        } catch (IOException e) {
            lastProblem = "port " + port + " could not be opened: " + e.getMessage();
            return lastProblem;
        }
        acceptor = new Thread(this::acceptLoop, "live-" + stationId);
        acceptor.setDaemon(true);
        acceptor.start();
        return null;
    }

    public synchronized void stop() {
        try {
            if (server != null) {
                server.close();
            }
        } catch (IOException e) {
            // closing
        }
        kick();
    }

    /** Disconnects the live DJ (if any). */
    public void kick() {
        Socket s = activeSocket;
        if (s != null) {
            try {
                s.close();
            } catch (IOException e) {
                // closing
            }
        }
    }

    private void acceptLoop() {
        while (!server.isClosed()) {
            try {
                final Socket s = server.accept();
                Thread t = new Thread(() -> handle(s), "live-dj-" + stationId);
                t.setDaemon(true);
                t.start();
            } catch (IOException e) {
                if (server.isClosed()) {
                    return;
                }
            }
        }
    }

    private void handle(Socket s) {
        try {
            s.setSoTimeout(10000);
            InputStream in = s.getInputStream();
            OutputStream out = s.getOutputStream();
            String head = readHead(in);
            if (head == null) {
                s.close();
                return;
            }
            String[] lines = head.split("\r?\n");
            String[] first = lines[0].split(" ");
            String method = first[0].toUpperCase(Locale.ROOT);
            if (!method.equals("SOURCE") && !method.equals("PUT")) {
                reply(out, "HTTP/1.0 405 Method Not Allowed", "This is a Memoryq live DJ port. Connect with Icecast as the server type.");
                s.close();
                return;
            }
            String auth = null;
            String name = "";
            boolean expectContinue = false;
            for (int i = 1; i < lines.length; i++) {
                int c = lines[i].indexOf(':');
                if (c <= 0) {
                    continue;
                }
                String k = lines[i].substring(0, c).trim().toLowerCase(Locale.ROOT);
                String v = lines[i].substring(c + 1).trim();
                if (k.equals("authorization")) {
                    auth = v;
                } else if (k.equals("ice-name") || k.equals("icy-name")) {
                    name = v.length() > 60 ? v.substring(0, 60) : v;
                } else if (k.equals("expect") && v.equalsIgnoreCase("100-continue")) {
                    expectContinue = true;
                }
            }
            String[] cred = credentials(auth);
            Login who = cred == null ? null : station.login(stationId, cred[0], cred[1], name);
            if (who == null) {
                lastProblem = "someone tried to connect with a wrong login or password";
                reply(out, "HTTP/1.0 401 Unauthorized", "Wrong login or password.");
                s.close();
                return;
            }
            if (who.refused != null) {
                lastProblem = (who.name.isEmpty() ? "a DJ" : who.name) + " was turned away: " + who.refused;
                reply(out, "HTTP/1.0 403 Forbidden", who.refused);
                s.close();
                return;
            }
            name = who.name;
            synchronized (this) {
                if (connected()) {
                    reply(out, "HTTP/1.0 403 Forbidden", "Another DJ is live on this station right now.");
                    s.close();
                    return;
                }
                if (expectContinue) {
                    out.write("HTTP/1.1 100 Continue\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                }
                out.write("HTTP/1.0 200 OK\r\nServer: Memoryq live\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                out.flush();
                Process ff;
                try {
                    ff = station.decoder();
                } catch (IOException e) {
                    lastProblem = "FFmpeg could not start: " + e.getMessage();
                    s.close();
                    return;
                }
                LiveSource src = new LiveSource(ff);
                active = src;
                activeSocket = s;
                dj = name;
                djId = who.djId;
                since = System.currentTimeMillis();
                lastProblem = "";
                station.connected(stationId, src, name);
            }
            pump(s, in, active);
        } catch (IOException e) {
            // the DJ went away
        } finally {
            LiveSource src = active;
            if (src != null && activeSocket == s) {
                src.endInput();
                active = null;
                activeSocket = null;
                station.disconnected(stationId, src);
                station.showEnded(stationId, djId, dj, since, System.currentTimeMillis());
            }
            try {
                s.close();
            } catch (IOException e) {
                // closing
            }
        }
    }

    /** Copies the DJ's sound into FFmpeg until the DJ stops (or sends nothing for 15 seconds). */
    private static void pump(Socket s, InputStream in, LiveSource src) throws IOException {
        s.setSoTimeout(15000);
        byte[] b = new byte[16384];
        OutputStream toFfmpeg = src.input();
        while (true) {
            int n;
            try {
                n = in.read(b);
            } catch (SocketTimeoutException e) {
                return;
            }
            if (n < 0) {
                return;
            }
            try {
                toFfmpeg.write(b, 0, n);
                toFfmpeg.flush();
            } catch (IOException e) {
                return;
            }
        }
    }

    /** {user, password} from a Basic Authorization header, or null. */
    static String[] credentials(String auth) {
        if (auth == null || !auth.regionMatches(true, 0, "Basic ", 0, 6)) {
            return null;
        }
        String decoded;
        try {
            decoded = new String(Base64.getDecoder().decode(auth.substring(6).trim()), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return null;
        }
        int c = decoded.indexOf(':');
        return c < 0 ? new String[]{"", decoded} : new String[]{decoded.substring(0, c), decoded.substring(c + 1)};
    }

    static boolean passwordOk(String auth, String want) {
        if (auth == null || !auth.regionMatches(true, 0, "Basic ", 0, 6)) {
            return false;
        }
        String decoded;
        try {
            decoded = new String(Base64.getDecoder().decode(auth.substring(6).trim()), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return false;
        }
        int c = decoded.indexOf(':');
        String pass = c < 0 ? decoded : decoded.substring(c + 1);
        return MessageDigest.isEqual(pass.getBytes(StandardCharsets.UTF_8), want.getBytes(StandardCharsets.UTF_8));
    }

    /** The request line and headers, up to the empty line. Null if the connection ends first. */
    private static String readHead(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int prev3 = -1;
        int prev2 = -1;
        int prev1 = -1;
        while (sb.length() < 8192) {
            int c = in.read();
            if (c < 0) {
                return null;
            }
            sb.append((char) c);
            if ((prev1 == '\n' && c == '\n') || (prev3 == '\r' && prev2 == '\n' && prev1 == '\r' && c == '\n')) {
                return sb.toString().trim();
            }
            prev3 = prev2;
            prev2 = prev1;
            prev1 = c;
        }
        return null;
    }

    private static void reply(OutputStream out, String status, String text) throws IOException {
        out.write((status + "\r\nContent-Type: text/plain\r\n\r\n" + text + "\r\n").getBytes(StandardCharsets.US_ASCII));
        out.flush();
    }

    // ---- The DJ's sound for the engine ----

    /**
     * The live sound as an engine source. It never makes the engine wait: when the DJ's sound is late it gives
     * silence, it starts after a little buffer (to ride out network bumps), and it drops sound if it falls far
     * behind. It ends when the DJ has gone and everything heard.
     */
    public static final class LiveSource implements RadioEngine.Source {
        private static final int RATE = AudioTools.RATE;
        private static final int START_FRAMES = RATE * 3 / 2;
        private static final int MAX_FRAMES = RATE * 6;

        private final Process ffmpeg;
        private final short[] ring = new short[(MAX_FRAMES + RATE) * 2];
        private int readPos;
        private int count;
        private boolean started;
        private volatile boolean inputDone;
        private volatile boolean decoderDone;
        private volatile boolean closed;

        LiveSource(Process ffmpeg) {
            this.ffmpeg = ffmpeg;
            Thread t = new Thread(this::readDecoder, "live-decode");
            t.setDaemon(true);
            t.start();
        }

        /** For tests: a source fed directly with raw samples instead of FFmpeg. */
        public static LiveSource forTest(Process fake) {
            return new LiveSource(fake);
        }

        OutputStream input() {
            return ffmpeg.getOutputStream();
        }

        void endInput() {
            inputDone = true;
            try {
                ffmpeg.getOutputStream().close();
            } catch (IOException e) {
                // closing
            }
        }

        boolean finished() {
            synchronized (ring) {
                return closed || (decoderDone && count == 0);
            }
        }

        private void readDecoder() {
            byte[] raw = new byte[8192];
            InputStream in = ffmpeg.getInputStream();
            int odd = -1;
            try {
                int n;
                while (!closed && (n = in.read(raw)) > 0) {
                    synchronized (ring) {
                        int i = 0;
                        if (odd >= 0 && n > 0) {
                            push((short) ((odd & 0xff) | (raw[0] << 8)));
                            odd = -1;
                            i = 1;
                        }
                        for (; i + 1 < n; i += 2) {
                            push((short) ((raw[i] & 0xff) | (raw[i + 1] << 8)));
                        }
                        if (i < n) {
                            odd = raw[i] & 0xff;
                        }
                        // too far behind (the DJ's clock runs fast): drop the oldest sound
                        while (count > MAX_FRAMES * 2) {
                            readPos = (readPos + 2) % ring.length;
                            count -= 2;
                        }
                    }
                }
            } catch (IOException e) {
                // decoder closed
            } finally {
                decoderDone = true;
            }
        }

        private void push(short v) {
            if (count >= ring.length) {
                readPos = (readPos + 1) % ring.length;
                count--;
            }
            ring[(readPos + count) % ring.length] = v;
            count++;
        }

        @Override
        public int read(short[] buf, int frames) {
            synchronized (ring) {
                if (closed || (decoderDone && count == 0)) {
                    return -1;
                }
                if (!started) {
                    if (count >= START_FRAMES * 2 || decoderDone) {
                        started = true;
                    } else {
                        java.util.Arrays.fill(buf, 0, frames * 2, (short) 0);
                        return frames;
                    }
                }
                int have = Math.min(frames * 2, count - (count % 2));
                for (int i = 0; i < have; i++) {
                    buf[i] = ring[readPos];
                    readPos = (readPos + 1) % ring.length;
                }
                count -= have;
                if (have < frames * 2) {
                    java.util.Arrays.fill(buf, have, frames * 2, (short) 0);
                    if (have == 0 && !decoderDone) {
                        started = false; // the network hiccupped: build up a little buffer again
                    }
                }
                return frames;
            }
        }

        @Override
        public void close() {
            closed = true;
            ffmpeg.destroy();
        }
    }
}