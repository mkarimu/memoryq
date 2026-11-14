package com.vstream.Vidiserver;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Studio Master (memoryq.com/master): a video mixer for live events, in the browser. Five slots, each a Vstream phone
 * feed, a guest who joins with a link, the Master-DJ's own camera, or a video. The browser mixes (cut, crossfades,
 * debate side by side) and sends one programme to a Memoryq live channel (Live Channels, Mkarimu STV, recording, the
 * user's "Also stream to" copies) and, if chosen, its sound to a radio station (Live DJ Pro's on-air path).
 *
 * A phone feed's sound is AAC, which browsers cannot play over WebRTC, so for each cued phone feed FFmpeg copies the
 * picture and turns the sound into Opus on a private path master/SHOW/sN that only this console can read.
 * Guests send straight from their browser to master/SHOW/gN.
 *
 *   GET  /master              the console        GET /master/join?c=CODE   a guest's page
 *   GET  /api/master/state    POST /api/master/relay | unrelay | invite | uninvite | live | ping | end
 *   GET  /api/master/guest?c=CODE                (a guest: where to send the camera)
 *   GET|POST /api/admin/master                   who may use Studio Master (the super-admin always can)
 */
public final class StudioMaster {

    static final int SLOTS = 5;
    static final Pattern MASTER_PATH = Pattern.compile("master/([0-9a-f]{16})/([sg][1-5])");
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int WEBRTC_PORT = Integer.getInteger("vstream.webrtcPort", 8889);
    private static final int RTSP_PORT = Integer.getInteger("vstream.rtspPort", 8554);
    static final long IDLE_MS = Long.getLong("vstream.masterIdleMs", 120000L);

    static final class Guest {
        String name = "";
        String code = "";
        String token = "";
        long created;
    }

    static final class Relay {
        String channel = "";
        volatile Process process;
        volatile String state = "starting";
        long started;
    }

    static final class Show {
        String id = "";
        String owner = "";
        String pubToken = "";
        String readToken = "";
        long lastPing;
        final Guest[] guests = new Guest[SLOTS + 1];
        final Relay[] relays = new Relay[SLOTS + 1];
    }

    private final Path file;
    private final SessionStore sessions;
    private final UserStore users;
    private final ClaimStore claims;
    private final LiveChannelRegistry registry;
    private final Predicate<String> isLive;
    private final LongSupplier clock;
    private final Set<String> allowed = new LinkedHashSet<String>();
    private final Map<String, Show> shows = new LinkedHashMap<String, Show>();
    private volatile Supplier<String> ffmpeg = () -> null;
    private volatile String mediaInput = "rtmp://127.0.0.1:" + StreamUrls.RTMP_PORT + "/live/";
    private volatile String rtspOut = "rtsp://127.0.0.1:" + RTSP_PORT + "/master/";

    public StudioMaster(Path file, SessionStore sessions, UserStore users, ClaimStore claims, LiveChannelRegistry registry,
                        Predicate<String> isLive, LongSupplier clock) {
        this.file = file;
        this.sessions = sessions;
        this.users = users;
        this.claims = claims;
        this.registry = registry;
        this.isLive = isLive;
        this.clock = clock;
    }

    public StudioMaster ffmpeg(Supplier<String> path) {
        this.ffmpeg = path;
        return this;
    }

    /** For tests: where phone feeds are read from and where the relays send. */
    void setMedia(String input, String out) {
        this.mediaInput = input;
        this.rtspOut = out;
    }

    public synchronized void init() throws IOException {
        if (Files.isRegularFile(file)) {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String u = line.trim().toLowerCase(Locale.ROOT);
                if (!u.isEmpty() && !u.startsWith("#")) {
                    allowed.add(u);
                }
            }
        }
    }

    private synchronized void save() throws IOException {
        StringBuilder sb = new StringBuilder("# Studio Master: members who may use it (the super-admin always can)\n");
        for (String u : allowed) {
            sb.append(u).append('\n');
        }
        Path part = file.resolveSibling(file.getFileName() + ".part");
        Files.write(part, sb.toString().getBytes(StandardCharsets.UTF_8));
        Files.move(part, file, StandardCopyOption.REPLACE_EXISTING);
    }

    private static String hex(int bytes) {
        byte[] b = new byte[bytes];
        RANDOM.nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) {
            sb.append(String.format("%02x", x));
        }
        return sb.toString();
    }

    private static boolean same(String a, String b) {
        return a != null && b != null && !a.isEmpty() && MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    private static boolean loopback(String ip) {
        return ip != null && (ip.equals("127.0.0.1") || ip.equals("::1") || ip.equals("0:0:0:0:0:0:0:1") || ip.startsWith("127."));
    }

    // ---- the media server asks: may this publish / read a master/ path? ----

    /** Null = allowed; otherwise why not. */
    public synchronized String deny(String action, String path, String token, String ip) {
        String p = path == null ? "" : (path.startsWith("/") ? path.substring(1) : path);
        Matcher m = MASTER_PATH.matcher(p);
        if (!m.matches()) {
            return "not a Studio Master path";
        }
        Show s = null;
        for (Show x : shows.values()) {
            if (x.id.equals(m.group(1))) {
                s = x;
            }
        }
        if (s == null) {
            return "no such show";
        }
        int slot = m.group(2).charAt(1) - '0';
        if ("read".equals(action)) {
            return same(token, s.readToken) ? null : "wrong token";
        }
        if (!"publish".equals(action)) {
            return "action not allowed";
        }
        if (m.group(2).charAt(0) == 's') {
            return loopback(ip) && same(token, s.pubToken) ? null : "only the server's own relay sends here";
        }
        Guest g = s.guests[slot];
        return g != null && same(token, g.token) ? null : "this guest link was replaced or ended";
    }

    // ---- who may use it ----

    synchronized boolean mayUse(SessionStore.Session s) {
        if (s == null) {
            return false;
        }
        if (s.role == UserStore.Role.ADMIN) {
            return true;
        }
        UserStore.Info info = users == null ? null : users.info(s.username);
        return info != null && !info.disabled && allowed.contains(s.username.toLowerCase(Locale.ROOT));
    }

    private Show show(String owner) {
        synchronized (this) {
            String k = owner.toLowerCase(Locale.ROOT);
            Show s = shows.get(k);
            if (s == null) {
                s = new Show();
                s.id = hex(8);
                s.owner = owner;
                s.pubToken = hex(16);
                s.readToken = hex(16);
                shows.put(k, s);
            }
            s.lastPing = clock.getAsLong();
            return s;
        }
    }

    // ---- relays: a phone feed's picture copied, its sound turned into Opus ----

    List<String> relayCommand(Show s, int slot, String channel) {
        List<String> c = new ArrayList<String>();
        String ff = ffmpeg.get();
        c.add(ff == null ? "ffmpeg" : ff);
        for (String a : new String[] {"-hide_banner", "-loglevel", "error", "-rw_timeout", "15000000", "-i", mediaInput + channel,
            "-map", "0:v:0?", "-map", "0:a:0?", "-c:v", "copy", "-c:a", "libopus", "-b:a", "128k", "-ar", "48000", "-ac", "2",
            "-f", "rtsp", "-rtsp_transport", "tcp"}) {
            c.add(a);
        }
        c.add(rtspOut + s.id + "/s" + slot + "?token=" + s.pubToken);
        return c;
    }

    private void startRelay(Show s, int slot, String channel) throws Bad {
        if (ffmpeg.get() == null) {
            throw new Bad(409, "FFmpeg is not installed on the server");
        }
        stopRelay(s, slot);
        Relay r = new Relay();
        r.channel = channel;
        r.started = clock.getAsLong();
        synchronized (this) {
            s.relays[slot] = r;
        }
        try {
            ProcessBuilder pb = new ProcessBuilder(relayCommand(s, slot, channel));
            pb.redirectErrorStream(true);
            Process p = pb.start();
            r.process = p;
            r.state = "live";
            Thread t = new Thread(() -> {
                StringBuilder last = new StringBuilder();
                try (InputStream in = p.getInputStream()) {
                    byte[] b = new byte[4096];
                    int n;
                    while ((n = in.read(b)) > 0) {
                        last.append(new String(b, 0, n, StandardCharsets.UTF_8));
                        if (last.length() > 2000) {
                            last.delete(0, last.length() - 1000);
                        }
                    }
                } catch (IOException e) {
                    // ended
                }
                if (r.process == p) {
                    String msg = last.toString().trim();
                    r.state = "ended" + (msg.isEmpty() ? "" : ": " + (msg.length() > 160 ? msg.substring(msg.length() - 160) : msg));
                }
            }, "master-relay");
            t.setDaemon(true);
            t.start();
            System.out.println("[master] " + s.owner + " cued " + channel + " in slot " + slot);
        } catch (IOException e) {
            r.state = "failed: " + e.getMessage();
            throw new Bad(500, "the feed could not be started: " + e.getMessage());
        }
    }

    private void stopRelay(Show s, int slot) {
        Relay r;
        synchronized (this) {
            r = s.relays[slot];
            s.relays[slot] = null;
        }
        if (r != null && r.process != null) {
            Process p = r.process;
            r.process = null;
            p.destroy();
        }
    }

    /** Every 30 s: a console that stopped checking in for 2 minutes has its relays stopped. */
    public void start() {
        java.util.concurrent.ScheduledExecutorService ses = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "master-idle");
            t.setDaemon(true);
            return t;
        });
        ses.scheduleWithFixedDelay(this::idleCheck, 30, 30, java.util.concurrent.TimeUnit.SECONDS);
    }

    void idleCheck() {
        List<Show> idle = new ArrayList<Show>();
        synchronized (this) {
            for (Show s : shows.values()) {
                if (clock.getAsLong() - s.lastPing > IDLE_MS) {
                    idle.add(s);
                }
            }
        }
        for (Show s : idle) {
            for (int k = 1; k <= SLOTS; k++) {
                if (s.relays[k] != null) {
                    stopRelay(s, k);
                }
            }
        }
    }

    // ---- HTTP ----

    public void register(HttpServer server) {
        server.createContext("/api/master/", this::api);
        server.createContext("/api/admin/master", this::admin);
    }

    static final class Bad extends Exception {
        private static final long serialVersionUID = 1L;
        final int status;

        Bad(int status, String m) {
            super(m);
            this.status = status;
        }
    }

    private static String q(String s) {
        return VideoApi.jq(s == null ? "" : s);
    }

    private static String p(String form, String k) {
        String v = MediaAuthHttpServer.queryParam(form, k);
        return v == null ? "" : v.trim();
    }

    private static String body(HttpExchange ex) throws IOException {
        InputStream in = ex.getRequestBody();
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0 && b.size() < 16384) {
            b.write(buf, 0, n);
        }
        return new String(b.toByteArray(), StandardCharsets.UTF_8);
    }

    private static void send(HttpExchange ex, int status, String json) throws IOException {
        byte[] b = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(status, b.length);
        ex.getResponseBody().write(b);
        ex.close();
    }

    private SessionStore.Session me(HttpExchange ex) {
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        String token = auth != null && auth.startsWith("Bearer ") ? auth.substring(7).trim() : "";
        return token.isEmpty() ? null : sessions.get(token);
    }

    /** https://host/webrtc/ behind Caddy; http://host:8889/ when talking to the server directly. */
    static String webrtcBase(HttpExchange ex) {
        String proto = ex.getRequestHeaders().getFirst("X-Forwarded-Proto");
        String host = ex.getRequestHeaders().getFirst("Host");
        if (host == null || !host.matches("[A-Za-z0-9.\\-]+(:[0-9]{1,5})?")) {
            host = "localhost";
        }
        return "https".equalsIgnoreCase(proto == null ? "" : proto.trim()) ? "https://" + host + "/webrtc/"
                : "http://" + host.replaceAll(":[0-9]+$", "") + ":" + WEBRTC_PORT + "/";
    }

    static String siteBase(HttpExchange ex) {
        String proto = ex.getRequestHeaders().getFirst("X-Forwarded-Proto");
        String host = ex.getRequestHeaders().getFirst("Host");
        if (host == null || !host.matches("[A-Za-z0-9.\\-]+(:[0-9]{1,5})?")) {
            host = "localhost";
        }
        return ("https".equalsIgnoreCase(proto == null ? "" : proto.trim()) ? "https://" : "http://") + host;
    }

    private String feeds(String me) {
        StringBuilder sb = new StringBuilder("[");
        int k = 0;
        for (ClaimStore.PoolEntry e : claims.pool()) {
            if (e.holder == null || !isLive.test(e.channelId)) {
                continue;
            }
            LiveChannel ch = registry == null ? null : registry.getChannel(e.channelId);
            String title = ch == null || ch.getTitle() == null ? "" : ch.getTitle();
            sb.append(k++ > 0 ? "," : "").append("{\"channel\":").append(q(e.channelId)).append(",\"holder\":").append(q(e.holder))
                    .append(",\"title\":").append(q(title)).append(",\"mine\":").append(e.holder.equalsIgnoreCase(me)).append('}');
        }
        return sb.append(']').toString();
    }

    private String state(HttpExchange ex, Show s) {
        String w = webrtcBase(ex);
        StringBuilder sb = new StringBuilder("{\"allowed\":true,\"show\":").append(q(s.id)).append(",\"owner\":").append(q(s.owner))
                .append(",\"feeds\":").append(feeds(s.owner)).append(",\"slots\":[");
        for (int k = 1; k <= SLOTS; k++) {
            Relay r = s.relays[k];
            Guest g = s.guests[k];
            sb.append(k > 1 ? "," : "").append("{\"slot\":").append(k);
            if (r != null) {
                sb.append(",\"feed\":").append(q(r.channel)).append(",\"feedState\":").append(q(r.state)).append(",\"whep\":")
                        .append(q(w + "master/" + s.id + "/s" + k + "/whep?token=" + s.readToken));
            }
            if (g != null) {
                sb.append(",\"guest\":").append(q(g.name)).append(",\"link\":").append(q(siteBase(ex) + "/master/join?c=" + g.code))
                        .append(",\"guestWhep\":").append(q(w + "master/" + s.id + "/g" + k + "/whep?token=" + s.readToken));
            }
            sb.append('}');
        }
        ClaimStore.Claim c = claims.claimOf(s.owner);
        sb.append("],\"channel\":").append(q(c == null ? "" : c.channelId)).append('}');
        return sb.toString();
    }

    private void api(HttpExchange ex) throws IOException {
        int status = 200;
        String out;
        try {
            String op = ex.getRequestURI().getPath().substring("/api/master/".length());
            boolean post = "POST".equalsIgnoreCase(ex.getRequestMethod());
            if (op.equals("guest")) {
                out = guest(ex, p(ex.getRequestURI().getRawQuery(), "c"));
            } else {
                SessionStore.Session me = me(ex);
                if (me == null) {
                    throw new Bad(401, "sign in with your Memoryq account");
                }
                if (!mayUse(me)) {
                    throw new Bad(403, "Studio Master is not switched on for your account - ask Memoryq");
                }
                Show s = show(me.username);
                String f = post ? body(ex) : ex.getRequestURI().getRawQuery();
                int slot = 0;
                if (!p(f, "slot").isEmpty()) {
                    try {
                        slot = Integer.parseInt(p(f, "slot"));
                    } catch (NumberFormatException e) {
                        slot = 0;
                    }
                    if (slot < 1 || slot > SLOTS) {
                        throw new Bad(400, "slots 1 to " + SLOTS);
                    }
                }
                if (post && !op.equals("ping") && !op.equals("state") && slot == 0 && (op.equals("relay") || op.equals("unrelay") || op.equals("invite") || op.equals("uninvite"))) {
                    throw new Bad(400, "which slot?");
                }
                switch (op) {
                    case "state":
                    case "ping":
                        break;
                    case "relay": {
                        String ch = p(f, "channel");
                        if (!ch.matches("CHANNEL-\\d{1,6}") || !isLive.test(ch)) {
                            throw new Bad(409, "that phone feed is not live right now");
                        }
                        boolean known = false;
                        for (ClaimStore.PoolEntry e : claims.pool()) {
                            known |= e.channelId.equals(ch) && e.holder != null;
                        }
                        if (!known) {
                            throw new Bad(404, "no such live feed");
                        }
                        startRelay(s, slot, ch);
                        break;
                    }
                    case "unrelay":
                        stopRelay(s, slot);
                        break;
                    case "invite": {
                        Guest g = new Guest();
                        g.name = RadioStore.clean(p(f, "name"), 40);
                        if (g.name.isEmpty()) {
                            g.name = "Guest " + slot;
                        }
                        g.code = hex(12);
                        g.token = hex(16);
                        g.created = clock.getAsLong();
                        synchronized (this) {
                            s.guests[slot] = g;
                        }
                        break;
                    }
                    case "uninvite":
                        synchronized (this) {
                            s.guests[slot] = null;
                        }
                        break;
                    case "live":
                        out = live(ex, me.username, p(f, "title"));
                        send(ex, 200, out);
                        return;
                    case "end":
                        for (int k = 1; k <= SLOTS; k++) {
                            stopRelay(s, k);
                        }
                        synchronized (this) {
                            for (int k = 1; k <= SLOTS; k++) {
                                s.guests[k] = null;
                            }
                        }
                        break;
                    default:
                        throw new Bad(404, "not found");
                }
                out = state(ex, s);
            }
        } catch (Bad e) {
            status = e.status;
            out = "{\"error\":" + q(e.getMessage()) + "}";
        } catch (Exception e) {
            status = 500;
            out = "{\"error\":" + q("server error: " + e.getMessage()) + "}";
        }
        send(ex, status, out);
    }

    /** The programme goes to the Master-DJ's live channel (claimed from the pool like a phone would). */
    private String live(HttpExchange ex, String user, String title) throws Bad {
        if (claims == null) {
            throw new Bad(409, "live channels are not running");
        }
        ClaimStore.Claim claim = claims.claimOf(user);
        if (claim == null) {
            for (ClaimStore.PoolEntry p : claims.pool()) {
                if (p.holder == null) {
                    try {
                        claim = claims.claim(user, p.channelId);
                        break;
                    } catch (ClaimStore.ClaimException e) {
                        // taken a moment ago - the next one
                    }
                }
            }
        }
        if (claim == null) {
            throw new Bad(409, "all live channels are in use right now - try again in a while");
        }
        String t = RadioStore.clean(title, 80);
        if (t.isEmpty()) {
            t = "Live event with " + user;
        }
        try {
            return "{\"whipUrl\":" + q(webrtcBase(ex) + "live/" + claim.channelId + "/whip?token=" + claim.token + "&title="
                    + URLEncoder.encode(t, "UTF-8").replace("+", "%20")) + ",\"channel\":" + q(claim.channelId) + ",\"title\":" + q(t) + "}";
        } catch (java.io.UnsupportedEncodingException e) {
            throw new Bad(500, "encoding");
        }
    }

    /** A guest's link: where the guest's browser sends camera and microphone. */
    private String guest(HttpExchange ex, String code) throws Bad {
        if (!code.matches("[0-9a-f]{24}")) {
            throw new Bad(404, "this link is not right - ask for a new one");
        }
        synchronized (this) {
            for (Show s : shows.values()) {
                for (int k = 1; k <= SLOTS; k++) {
                    Guest g = s.guests[k];
                    if (g != null && same(g.code, code)) {
                        if (clock.getAsLong() - g.created > 12 * 3600000L) {
                            throw new Bad(410, "this link has expired - ask for a new one");
                        }
                        return "{\"name\":" + q(g.name) + ",\"host\":" + q(s.owner) + ",\"slot\":" + k + ",\"whipUrl\":"
                                + q(webrtcBase(ex) + "master/" + s.id + "/g" + k + "/whip?token=" + g.token) + "}";
                    }
                }
            }
        }
        throw new Bad(404, "this link has ended - ask the host for a new one");
    }

    private void admin(HttpExchange ex) throws IOException {
        try {
            if (!SuperAgent.isAdmin(ex, sessions)) {
                send(ex, 403, "{\"error\":\"super-admin only\"}");
                return;
            }
            if ("POST".equalsIgnoreCase(ex.getRequestMethod())) {
                String f = body(ex);
                String add = p(f, "add").toLowerCase(Locale.ROOT), rem = p(f, "remove").toLowerCase(Locale.ROOT);
                if (!add.isEmpty()) {
                    if (users != null && users.info(add) == null) {
                        send(ex, 404, "{\"error\":\"no member with that username\"}");
                        return;
                    }
                    synchronized (this) {
                        allowed.add(add);
                    }
                }
                if (!rem.isEmpty()) {
                    synchronized (this) {
                        allowed.remove(rem);
                    }
                }
                save();
            }
            StringBuilder sb = new StringBuilder("{\"allowed\":[");
            synchronized (this) {
                int k = 0;
                for (String u : allowed) {
                    sb.append(k++ > 0 ? "," : "").append(q(u));
                }
            }
            send(ex, 200, sb.append("]}").toString());
        } catch (Exception e) {
            send(ex, 500, "{\"error\":" + q(e.getMessage()) + "}");
        }
    }
}