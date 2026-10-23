package com.vstream.Vidiserver;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

/**
 * "Also stream to" (Vstream app - Advanced): when someone goes live on their Memoryq channel, the server sends the same
 * stream on to YouTube, Facebook, Twitch, X, TikTok or any RTMP server they set up - the phone uploads only once.
 * Each copy is an FFmpeg process that reads the channel from the local media server and copies it, untouched.
 *   GET  /api/restream                      the account's destinations (keys are never sent back) and what is running
 *   POST /api/restream/save                 platform, on, url, key (empty key = keep the saved one)
 *   POST /api/restream/remove               platform
 * Destinations are kept in restream.txt (user, platform, on, server, key).
 */
public final class Restreamer {

    /** platform id, label, default server address ("" = the user copies it from the platform). */
    static final String[][] PLATFORMS = {
        {"youtube", "YouTube", "rtmp://a.rtmp.youtube.com/live2"},
        {"facebook", "Facebook", "rtmps://live-api-s.facebook.com:443/rtmp/"},
        {"twitch", "Twitch", "rtmp://live.twitch.tv/app"},
        {"x", "X (Twitter)", ""},
        {"tiktok", "TikTok", ""},
        {"custom", "Other RTMP server", ""},
    };
    private static final long RETRY_WINDOW_MS = 20000;
    private static final int MAX_RETRIES = 3;

    static final class Target {
        String platform;
        boolean on;
        String url = "";
        String key = "";
    }

    /** One copy being sent right now. */
    private static final class Relay {
        String user;
        String channel;
        String platform;
        volatile Process process;
        volatile String state = "starting";
        volatile boolean stopped;
        long started;
        int tries;
    }

    private final Path file;
    private final SessionStore sessions;
    private final UserStore users;
    private final ClaimStore claims;
    private volatile Supplier<String> ffmpeg = () -> null;
    private volatile String mediaInput = "rtmp://127.0.0.1:" + StreamUrls.RTMP_PORT + "/live/";
    private final Map<String, Map<String, Target>> targets = new HashMap<String, Map<String, Target>>();
    private final Map<String, Relay> relays = new HashMap<String, Relay>();

    public Restreamer(Path file, SessionStore sessions, UserStore users, ClaimStore claims) {
        this.file = file;
        this.sessions = sessions;
        this.users = users;
        this.claims = claims;
    }

    public void setFfmpeg(Supplier<String> path) {
        this.ffmpeg = path;
    }

    /** Where FFmpeg reads a channel from (the local media server). */
    void setMediaInput(String base) {
        this.mediaInput = base;
    }

    public synchronized void init() throws IOException {
        if (!Files.isRegularFile(file)) {
            return;
        }
        boolean dropped = false;
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String[] f = line.split("\t", -1);
            if (f.length < 5 || label(f[1]) == null) {
                continue;
            }
            if (users.info(f[0]) == null) {
                dropped = true; // the account was deleted: forget its keys
                continue;
            }
            Target t = new Target();
            t.platform = f[1];
            t.on = "1".equals(f[2]);
            t.url = f[3];
            t.key = f[4];
            targets.computeIfAbsent(f[0], k -> new LinkedHashMap<String, Target>()).put(t.platform, t);
        }
        if (dropped) {
            save();
        }
    }

    private void save() throws IOException {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Map<String, Target>> e : targets.entrySet()) {
            for (Target t : e.getValue().values()) {
                sb.append(e.getKey()).append('\t').append(t.platform).append('\t').append(t.on ? "1" : "0").append('\t')
                        .append(t.url).append('\t').append(t.key).append('\n');
            }
        }
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.write(tmp, sb.toString().getBytes(StandardCharsets.UTF_8));
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
    }

    static String label(String platform) {
        for (String[] p : PLATFORMS) {
            if (p[0].equals(platform)) {
                return p[1];
            }
        }
        return null;
    }

    static String defaultUrl(String platform) {
        for (String[] p : PLATFORMS) {
            if (p[0].equals(platform)) {
                return p[2];
            }
        }
        return "";
    }

    /** rtmp:// or rtmps:// to a public internet address (never this server or a private network). */
    static String checkUrl(String url) {
        String u = url == null ? "" : url.trim();
        if (u.isEmpty()) {
            throw new IllegalArgumentException("enter the server address (it starts with rtmp:// or rtmps://)");
        }
        if (u.length() > 300 || !u.matches("(?i)rtmps?://[^\\s\"'<>\\\\]+")) {
            throw new IllegalArgumentException("the server address must start with rtmp:// or rtmps://");
        }
        String host;
        try {
            host = new URI(u).getHost();
        } catch (Exception e) {
            host = null;
        }
        if (host == null || host.isEmpty()) {
            throw new IllegalArgumentException("that server address cannot be read");
        }
        if (privateHost(host)) {
            throw new IllegalArgumentException("that server address is not on the public internet");
        }
        return u;
    }

    static boolean privateHost(String host) {
        String h = host.toLowerCase(Locale.ROOT).replace("[", "").replace("]", "");
        if (h.equals("localhost") || h.endsWith(".localhost") || h.endsWith(".local") || h.endsWith(".internal")) {
            return true;
        }
        if (h.matches("[0-9.]+") || h.contains(":")) {
            try {
                InetAddress a = InetAddress.getByName(h);
                return a.isLoopbackAddress() || a.isSiteLocalAddress() || a.isLinkLocalAddress() || a.isAnyLocalAddress()
                        || a.isMulticastAddress() || h.startsWith("100.64.") || h.startsWith("fc") || h.startsWith("fd");
            } catch (Exception e) {
                return true;
            }
        }
        return false;
    }

    static String checkKey(String key) {
        String k = key == null ? "" : key.trim();
        if (k.length() > 400 || !k.matches("[\\x21-\\x7e]*")) {
            throw new IllegalArgumentException("the stream key has characters that cannot be used");
        }
        return k;
    }

    /** The address FFmpeg sends to: the server address and the key. */
    static String destination(Target t) {
        String u = t.url.trim();
        if (t.key.isEmpty()) {
            return u;
        }
        return u + (u.endsWith("/") ? "" : "/") + t.key;
    }

    /** The FFmpeg command for one copy (the key is in it - never print it). */
    List<String> command(String channel, Target t) {
        List<String> c = new ArrayList<String>();
        String ff = ffmpeg.get();
        c.add(ff == null ? "ffmpeg" : ff);
        c.add("-hide_banner");
        c.add("-loglevel");
        c.add("error");
        c.add("-rw_timeout");
        c.add("15000000");
        c.add("-i");
        c.add(mediaInput + channel);
        c.add("-c");
        c.add("copy");
        c.add("-f");
        c.add("flv");
        c.add(destination(t));
        return c;
    }

    // ---- Going live ----

    /** Called when a channel starts publishing: starts the copies its holder switched on. */
    public void onPublish(String channel) {
        String user = null;
        for (ClaimStore.PoolEntry p : claims.pool()) {
            if (p.channelId.equals(channel)) {
                user = p.holder;
            }
        }
        UserStore.Info who = user == null ? null : users.info(user);
        if (who == null || who.disabled) {
            return;
        }
        final String holder = user;
        List<Target> on = new ArrayList<Target>();
        synchronized (this) {
            Map<String, Target> mine = targets.get(holder.toLowerCase(Locale.ROOT));
            if (mine != null) {
                for (Target t : mine.values()) {
                    if (t.on && !t.url.isEmpty()) {
                        on.add(t);
                    }
                }
            }
        }
        if (on.isEmpty()) {
            return;
        }
        Thread th = new Thread(() -> {
            try {
                Thread.sleep(Long.getLong("vstream.restreamDelayMs", 3000L)); // let the stream settle first
            } catch (InterruptedException e) {
                return;
            }
            for (Target t : on) {
                start(holder, channel, t);
            }
        }, "restream-start");
        th.setDaemon(true);
        th.start();
    }

    private void start(String user, String channel, Target t) {
        String id = user.toLowerCase(Locale.ROOT) + "|" + t.platform;
        Relay r;
        synchronized (this) {
            Relay old = relays.get(id);
            if (old != null && old.process != null && old.process.isAlive()) {
                return; // already sending
            }
            r = new Relay();
            r.user = user;
            r.channel = channel;
            r.platform = t.platform;
            relays.put(id, r);
        }
        run(r, t);
    }

    private void run(Relay r, Target t) {
        if (ffmpeg.get() == null) {
            r.state = "failed: FFmpeg is not installed on the server";
            return;
        }
        try {
            ProcessBuilder pb = new ProcessBuilder(command(r.channel, t));
            pb.redirectErrorStream(true);
            r.process = pb.start();
            r.started = System.currentTimeMillis();
            r.state = "live";
            System.out.println("[restream] " + r.user + " " + r.channel + " -> " + label(t.platform));
        } catch (IOException e) {
            r.state = "failed: " + e.getMessage();
            return;
        }
        final Process p = r.process;
        Thread watch = new Thread(() -> {
            String last = "";
            try (BufferedReader in = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = in.readLine()) != null) {
                    if (!line.trim().isEmpty()) {
                        last = line.trim();
                    }
                }
            } catch (IOException e) {
                // the process ended
            }
            try {
                p.waitFor();
            } catch (InterruptedException e) {
                return;
            }
            if (r.stopped) {
                r.state = "stopped";
                return;
            }
            String why = mask(last, t.key);
            boolean quick = System.currentTimeMillis() - r.started < RETRY_WINDOW_MS;
            if (quick && r.tries < MAX_RETRIES && MediaMtxClient.isChannelLive(r.channel)) {
                r.tries++;
                r.state = "retrying (" + (why.isEmpty() ? "connection lost" : why) + ")";
                try {
                    Thread.sleep(4000L * r.tries);
                } catch (InterruptedException e) {
                    return;
                }
                if (!r.stopped) {
                    run(r, t);
                }
                return;
            }
            r.state = quick ? "failed: " + (why.isEmpty() ? "the platform did not accept the stream (check the server and key)" : why) : "ended";
            System.out.println("[restream] " + r.user + " -> " + label(t.platform) + ": " + r.state);
        }, "restream-watch");
        watch.setDaemon(true);
        watch.start();
    }

    /** Hides the stream key if FFmpeg repeats it in a message. */
    static String mask(String s, String key) {
        String out = s == null ? "" : s;
        if (key != null && key.length() >= 4) {
            out = out.replace(key, "****");
        }
        return out.length() > 200 ? out.substring(0, 200) : out;
    }

    private synchronized void stop(String user, String platform) {
        Relay r = relays.remove(user.toLowerCase(Locale.ROOT) + "|" + platform);
        if (r != null) {
            r.stopped = true;
            if (r.process != null) {
                r.process.destroy();
            }
        }
    }

    // ---- The app's API ----

    public void register(HttpServer server) {
        server.createContext("/api/restream", this::handle);
    }

    private void handle(HttpExchange ex) throws IOException {
        try {
            String auth = ex.getRequestHeaders().getFirst("Authorization");
            String token = auth != null && auth.startsWith("Bearer ") ? auth.substring(7).trim() : "";
            SessionStore.Session s = token.isEmpty() ? null : sessions.get(token);
            if (s != null && (s.role == UserStore.Role.GUEST || s.role == UserStore.Role.ADMIN)) {
                send(ex, 403, "{\"error\":\"this account cannot stream - use a registered account\"}");
                return;
            }
            UserStore.Info me = s == null ? null : users.info(s.username);
            if (me == null || me.disabled) {
                send(ex, 401, "{\"error\":\"sign in required\"}");
                return;
            }
            if (me.role == UserStore.Role.GUEST || me.role == UserStore.Role.ADMIN) {
                send(ex, 403, "{\"error\":\"this account cannot stream - use a registered account\"}");
                return;
            }
            String path = ex.getRequestURI().getPath();
            boolean post = "POST".equalsIgnoreCase(ex.getRequestMethod());
            String user = me.username.toLowerCase(Locale.ROOT);
            if (path.equals("/api/restream") || path.equals("/api/restream/")) {
                send(ex, 200, json(user));
                return;
            }
            if (!post) {
                send(ex, 405, "{\"error\":\"use POST\"}");
                return;
            }
            String body = readBody(ex);
            String platform = String.valueOf(MediaAuthHttpServer.queryParam(body, "platform"));
            if (label(platform) == null) {
                send(ex, 400, "{\"error\":\"unknown platform\"}");
                return;
            }
            if (path.equals("/api/restream/remove")) {
                synchronized (this) {
                    Map<String, Target> mine = targets.get(user);
                    if (mine != null) {
                        mine.remove(platform);
                    }
                    save();
                }
                stop(user, platform);
                send(ex, 200, json(user));
                return;
            }
            if (!path.equals("/api/restream/save")) {
                send(ex, 404, "{\"error\":\"not found\"}");
                return;
            }
            boolean on = !"false".equals(MediaAuthHttpServer.queryParam(body, "on"));
            String url = MediaAuthHttpServer.queryParam(body, "url");
            String key = MediaAuthHttpServer.queryParam(body, "key");
            synchronized (this) {
                Map<String, Target> mine = targets.computeIfAbsent(user, k -> new LinkedHashMap<String, Target>());
                Target t = mine.get(platform);
                Target n = new Target();
                n.platform = platform;
                n.url = checkUrl(url == null || url.trim().isEmpty() ? defaultUrl(platform) : url);
                n.key = key == null || key.trim().isEmpty() ? (t == null ? "" : t.key) : checkKey(key);
                if (n.key.isEmpty() && !"custom".equals(platform)) {
                    throw new IllegalArgumentException("enter the stream key from " + label(platform));
                }
                n.on = on;
                if (mine.size() >= 6 && t == null) {
                    throw new IllegalArgumentException("6 destinations at most");
                }
                mine.put(platform, n);
                save();
            }
            if (!on) {
                stop(user, platform);
            }
            send(ex, 200, json(user));
        } catch (IllegalArgumentException e) {
            send(ex, 400, "{\"error\":" + q(e.getMessage()) + "}");
        } catch (Exception e) {
            send(ex, 500, "{\"error\":" + q("server error: " + e.getMessage()) + "}");
        } finally {
            ex.close();
        }
    }

    /** The account's destinations: platform, label, server, whether a key is saved (the last 4 characters), on, state. */
    synchronized String json(String user) {
        StringBuilder sb = new StringBuilder("{\"platforms\":[");
        Map<String, Target> mine = targets.get(user);
        for (int i = 0; i < PLATFORMS.length; i++) {
            String[] p = PLATFORMS[i];
            Target t = mine == null ? null : mine.get(p[0]);
            Relay r = relays.get(user + "|" + p[0]);
            sb.append(i > 0 ? "," : "").append("{\"platform\":").append(q(p[0])).append(",\"label\":").append(q(p[1]))
                    .append(",\"defaultUrl\":").append(q(p[2])).append(",\"saved\":").append(t != null)
                    .append(",\"on\":").append(t != null && t.on).append(",\"url\":").append(q(t == null ? "" : t.url))
                    .append(",\"keyHint\":").append(q(t == null || t.key.length() < 4 ? "" : "..." + t.key.substring(t.key.length() - 4)))
                    .append(",\"state\":").append(q(r == null ? "" : r.state)).append('}');
        }
        return sb.append("]}").toString();
    }

    // ---- Helpers ----

    private static String readBody(HttpExchange ex) throws IOException {
        InputStream in = ex.getRequestBody();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] b = new byte[4096];
        int n;
        while ((n = in.read(b)) > 0 && out.size() < 16384) {
            out.write(b, 0, n);
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private static String q(String s) {
        if (s == null) {
            return "\"\"";
        }
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\') {
                sb.append('\\').append(c);
            } else if (c < 0x20 || c > 0x7e) {
                sb.append(String.format("\\u%04x", (int) c));
            } else {
                sb.append(c);
            }
        }
        return sb.append('"').toString();
    }

    private static void send(HttpExchange ex, int status, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(status, b.length);
        ex.getResponseBody().write(b);
    }
}