package com.vstream.Vidiserver;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Live events recorded to the video disk. The live server (MediaMTX) records every live channel to
 * recordings/live/CHANNEL-N/DATE_TIME.mp4 (in parts of up to an hour) and removes them after 30 days. Here the admin
 * sees them, watches them, keeps one as a video (it moves into the videos, owned by the channel's broadcaster) or
 * deletes it. When the video disk is more than 80% full the oldest recordings go first, so the videos never run
 * out of room.
 *   GET  /api/admin/recordings/list    file?path=P&token=T (to watch)    POST keep (path, title) | delete (path)
 */
public final class Recordings {

    static final long KEEP_MS = 30L * 86400000L;
    static final int FULL = 80;
    private static final Pattern NAME = Pattern.compile("(\\d{4}-\\d{2}-\\d{2})_(\\d{2})-(\\d{2})-(\\d{2}).*\\.mp4");

    private final Path dir;
    private final VideoApi videos;
    private final UserStore users;
    private final SessionStore sessions;
    private final LongSupplier clock;
    private ScheduledExecutorService timer;

    public static final class Rec {
        public String path = "";
        public String channel = "";
        public long started;
        public long modified;
        public long bytes;
        public boolean recording;
    }

    public Recordings(Path dir, VideoApi videos, UserStore users, SessionStore sessions, LongSupplier clock) {
        this.dir = dir;
        this.videos = videos;
        this.users = users;
        this.sessions = sessions;
        this.clock = clock;
    }

    public void register(HttpServer server) {
        server.createContext("/api/admin/recordings/", this::handle);
        server.createContext("/api/streams/", this::handlePublic);
    }

    /** Who streamed what (the pool's channels change hands). */
    private volatile StreamHistory history;

    public void setHistory(StreamHistory h) {
        this.history = h;
    }

    /** Every stream with its start and end (for linking kept videos and hiding removed streams). */
    private volatile StreamLog log;

    public void setLog(StreamLog l) {
        this.log = l;
    }

    /** The recordings made during a stream (a stream that reconnected can have several), oldest first. */
    public List<Rec> recordingsOf(StreamLog.Session s, long now) {
        List<Rec> out = new java.util.ArrayList<Rec>();
        long to = s.end == 0 ? now : s.end + 60000L;
        for (Rec r : list()) {
            if (r.channel.equals(s.channel) && r.started >= s.start - 90000L && r.started <= to) {
                out.add(r);
            }
        }
        out.sort((a, b) -> Long.compare(a.started, b.started));
        return out;
    }

    /** A recording too small to be worth watching again (a stream that failed to start). */
    static final long MIN_BYTES = 300L * 1024;
    static final int MAX_RECENT = 60;

    /** Finished recordings for "Recent streams", newest first, with who streamed them and the title. */
    public String recentJson(String me, boolean admin) {
        StreamHistory h = history;
        StringBuilder sb = new StringBuilder("{\"keepDays\":30,\"streams\":[");
        int n = 0;
        for (Rec r : list()) {
            if (r.recording || r.bytes < MIN_BYTES) {
                continue;
            }
            StreamLog lg = log;
            StreamLog.Session ls = lg == null ? null : lg.at(r.channel, r.started);
            if (ls != null && ls.removed) {
                continue; // taken down
            }
            StreamHistory.Entry e = h == null ? null : h.at(r.channel, r.started);
            String user = e == null ? ownerOf(r.channel) : e.user;
            if ("admin".equals(user) && e == null) {
                user = "";
            }
            long secs = Math.max(0, (r.modified - r.started) / 1000);
            boolean mine = me != null && !user.isEmpty() && user.equalsIgnoreCase(me);
            sb.append(n++ > 0 ? "," : "").append("{\"path\":").append(ProfileApi.quote(r.path))
                    .append(",\"channel\":").append(ProfileApi.quote(r.channel))
                    .append(",\"user\":").append(ProfileApi.quote(user))
                    .append(",\"title\":").append(ProfileApi.quote(e == null ? "" : e.title))
                    .append(",\"started\":").append(r.started).append(",\"seconds\":").append(secs)
                    .append(",\"bytes\":").append(r.bytes)
                    .append(",\"mine\":").append(mine || admin)
                    .append(",\"url\":").append(ProfileApi.quote("/api/streams/file?path=" + urlEncode(r.path))).append('}');
            if (n >= MAX_RECENT) {
                break;
            }
        }
        return sb.append("]}").toString();
    }

    private static String urlEncode(String s) {
        try {
            return java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20");
        } catch (java.io.UnsupportedEncodingException e) {
            return s;
        }
    }

    /** May this person take the recording down? The one who streamed it, or the admin. */
    boolean mayDelete(String rel, UserStore.Info me) {
        if (me == null) {
            return false;
        }
        if (me.role == UserStore.Role.ADMIN) {
            return true;
        }
        for (Rec r : list()) {
            if (r.path.equals(rel)) {
                StreamHistory h = history;
                StreamHistory.Entry e = h == null ? null : h.at(r.channel, r.started);
                String user = e == null ? ownerOf(r.channel) : e.user;
                return user.equalsIgnoreCase(me.username);
            }
        }
        return false;
    }

    /**
     * Everyone: GET /api/streams/recent (signed in: "mine" marks your own) and GET /api/streams/file?path=P (watch, any
     * player). The streamer or the admin: POST /api/streams/delete (path).
     */
    private void handlePublic(HttpExchange ex) throws IOException {
        try {
            String op = ex.getRequestURI().getPath().substring("/api/streams/".length());
            String query = ex.getRequestURI().getRawQuery();
            String auth = ex.getRequestHeaders().getFirst("Authorization");
            String token = auth != null && auth.startsWith("Bearer ") ? auth.substring(7).trim() : "";
            SessionStore.Session s = token.isEmpty() ? null : sessions.get(token);
            UserStore.Info me = s == null ? null : users.info(s.username);
            switch (op) {
                case "recent":
                    json(ex, 200, recentJson(me == null ? null : me.username, me != null && me.role == UserStore.Role.ADMIN));
                    return;
                case "file": {
                    Path f = fileOf(MediaAuthHttpServer.queryParam(query, "path"));
                    if (f == null) {
                        json(ex, 404, "{\"error\":\"this recording is no longer there\"}");
                        return;
                    }
                    VideoApi.sendFile(ex, f, "video/mp4", "public, max-age=3600");
                    return;
                }
                case "delete": {
                    if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
                        json(ex, 405, "{\"error\":\"use POST\"}");
                        return;
                    }
                    if (me == null) {
                        json(ex, 401, "{\"error\":\"sign in required\"}");
                        return;
                    }
                    String body = new String(AudioTools.readAll(ex.getRequestBody()), StandardCharsets.UTF_8);
                    String rel = MediaAuthHttpServer.queryParam(body, "path");
                    if (!mayDelete(rel, me)) {
                        json(ex, 403, "{\"error\":\"only the person who streamed it can remove it\"}");
                        return;
                    }
                    if (delete(rel)) {
                        System.out.println("[recordings] " + me.username + " removed " + rel);
                        json(ex, 200, recentJson(me.username, me.role == UserStore.Role.ADMIN));
                    } else {
                        json(ex, 404, "{\"error\":\"this recording is no longer there\"}");
                    }
                    return;
                }
                default:
                    json(ex, 404, "{\"error\":\"not found\"}");
            }
        } finally {
            ex.close();
        }
    }

    /** Looks after the space every 10 minutes. */
    public synchronized void start() {
        if (timer != null || dir == null) {
            return;
        }
        timer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "recordings");
            t.setDaemon(true);
            return t;
        });
        timer.scheduleWithFixedDelay(() -> {
            try {
                tidy();
            } catch (RuntimeException e) {
                System.out.println("[recordings] " + e);
            }
        }, 2, 10, TimeUnit.MINUTES);
    }

    // ---- The list ----

    public List<Rec> list() {
        final List<Rec> out = new ArrayList<Rec>();
        if (dir == null || !Files.isDirectory(dir)) {
            return out;
        }
        final long now = clock.getAsLong();
        try {
            Files.walkFileTree(dir, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path f, BasicFileAttributes a) {
                    String n = f.getFileName().toString();
                    if (!a.isRegularFile() || !n.endsWith(".mp4")) {
                        return FileVisitResult.CONTINUE;
                    }
                    Rec r = new Rec();
                    r.path = dir.relativize(f).toString().replace('\\', '/');
                    Path parent = f.getParent();
                    r.channel = parent == null || parent.equals(dir) ? "" : parent.getFileName().toString();
                    r.modified = a.lastModifiedTime().toMillis();
                    r.bytes = a.size();
                    r.recording = now - r.modified < 60000L;
                    Matcher m = NAME.matcher(n);
                    if (m.matches()) {
                        try {
                            r.started = java.time.LocalDateTime.parse(m.group(1) + "T" + m.group(2) + ":" + m.group(3) + ":" + m.group(4))
                                    .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
                        } catch (RuntimeException e) {
                            r.started = r.modified;
                        }
                    } else {
                        r.started = r.modified;
                    }
                    out.add(r);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            System.out.println("[recordings] could not list: " + e.getMessage());
        }
        out.sort(Comparator.comparingLong((Rec r) -> r.started).reversed());
        return out;
    }

    /** A recording's file, only inside the recordings folder. */
    Path fileOf(String rel) {
        if (dir == null || rel == null || rel.isEmpty() || rel.contains("..") || rel.startsWith("/") || rel.contains("\\") || !rel.endsWith(".mp4")) {
            return null;
        }
        Path f = dir.resolve(rel).normalize();
        return f.startsWith(dir.normalize()) && Files.isRegularFile(f) ? f : null;
    }

    /** Old ones (30 days) go; when the disk is over 80% full, the oldest go until it is under 75%. Returns how many went. */
    public int tidy() {
        int gone = 0;
        long now = clock.getAsLong();
        List<Rec> all = list();
        for (Rec r : all) {
            if (!r.recording && now - r.modified > KEEP_MS && delete(r.path)) {
                gone++;
            }
        }
        if (MediaDisks.usedPercent(dir) > FULL) {
            List<Rec> old = list();
            old.sort(Comparator.comparingLong((Rec r) -> r.started));
            for (Rec r : old) {
                if (MediaDisks.usedPercent(dir) < FULL - 5) {
                    break;
                }
                if (!r.recording && delete(r.path)) {
                    gone++;
                }
            }
            if (gone > 0) {
                System.out.println("[recordings] the video disk was over " + FULL + "% full - " + gone + " old recording(s) removed");
            }
        }
        return gone;
    }

    public boolean delete(String rel) {
        Path f = fileOf(rel);
        if (f == null) {
            return false;
        }
        try {
            Files.delete(f);
            Path parent = f.getParent();
            boolean empty;
            try (java.util.stream.Stream<Path> s = Files.list(parent)) {
                empty = !s.findAny().isPresent();
            }
            if (empty && !parent.equals(dir)) {
                Files.deleteIfExists(parent);
            }
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** Who streamed this recording (from the stream history), else the channel's broadcaster, else "admin". */
    String whoStreamed(Rec r) {
        StreamHistory h = history;
        StreamHistory.Entry e = h == null ? null : h.at(r.channel, r.started);
        return e != null && !e.user.isEmpty() ? e.user : ownerOf(r.channel);
    }

    String titleOf(Rec r) {
        StreamHistory h = history;
        StreamHistory.Entry e = h == null ? null : h.at(r.channel, r.started);
        return e == null ? "" : e.title;
    }

    /** The broadcaster of a channel (CHANNEL-N), or "admin". */
    String ownerOf(String channel) {
        for (UserStore.Info i : users.listInfos()) {
            if (channel.equals(i.channelId)) {
                return i.username;
            }
        }
        return "admin";
    }

    /** Keeps a finished recording as a video. */
    public VideoStore.Video keep(String rel, String title) throws IOException {
        return keep(rel, title, false);
    }

    /** Keeps a finished recording as a video; onTv = it also plays on Mkarimu TV once it is ready. */
    public VideoStore.Video keep(String rel, String title, boolean onTv) throws IOException {
        Path f = fileOf(rel);
        if (f == null) {
            throw new IllegalArgumentException("no such recording");
        }
        Rec r = null;
        for (Rec x : list()) {
            if (x.path.equals(rel)) {
                r = x;
            }
        }
        if (r == null || r.recording) {
            throw new IllegalArgumentException("this recording is still going on - keep it when the event has ended");
        }
        String t = title == null || title.trim().isEmpty()
                ? "Live: " + r.channel + " " + java.time.Instant.ofEpochMilli(r.started).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
                : title.trim();
        VideoStore.Video v = videos.importFile(whoStreamed(r), f, t, "Recorded live on " + BackupJob.when(r.started) + ".", onTv);
        StreamLog lg = log;
        StreamLog.Session ls = lg == null ? null : lg.at(r.channel, r.started);
        if (ls != null && v != null) {
            lg.setVideo(ls.id, v.id);
        }
        return v;
    }

    // ---- The admin ----

    private void handle(HttpExchange ex) throws IOException {
        try {
            String op = ex.getRequestURI().getPath().substring("/api/admin/recordings/".length());
            String query = ex.getRequestURI().getRawQuery();
            String auth = ex.getRequestHeaders().getFirst("Authorization");
            String token = auth != null && auth.startsWith("Bearer ") ? auth.substring(7).trim() : "";
            if (token.isEmpty() && "file".equals(op)) {
                String t = MediaAuthHttpServer.queryParam(query, "token"); // the <video> player cannot send headers
                token = t == null ? "" : t.trim();
            }
            SessionStore.Session s = token.isEmpty() ? null : sessions.get(token);
            UserStore.Info me = s == null ? null : users.info(s.username);
            if (me == null || me.role != UserStore.Role.ADMIN) {
                json(ex, 403, "{\"error\":\"admins only\"}");
                return;
            }
            boolean post = "POST".equalsIgnoreCase(ex.getRequestMethod());
            String body = post ? new String(AudioTools.readAll(ex.getRequestBody()), StandardCharsets.UTF_8) : "";
            switch (op) {
                case "list":
                    json(ex, 200, listJson());
                    return;
                case "file": {
                    Path f = fileOf(MediaAuthHttpServer.queryParam(query, "path"));
                    if (f == null) {
                        json(ex, 404, "{\"error\":\"no such recording\"}");
                        return;
                    }
                    VideoApi.sendFile(ex, f, "video/mp4", "no-store");
                    return;
                }
                case "keep":
                case "delete": {
                    if (!post) {
                        json(ex, 405, "{\"error\":\"use POST\"}");
                        return;
                    }
                    String rel = MediaAuthHttpServer.queryParam(body, "path");
                    try {
                        if ("keep".equals(op)) {
                            VideoStore.Video v = keep(rel, MediaAuthHttpServer.queryParam(body, "title"),
                                    "1".equals(MediaAuthHttpServer.queryParam(body, "tv")));
                            json(ex, 200, "{\"video\":" + v.id + "}");
                        } else if (delete(rel)) {
                            json(ex, 200, listJson());
                        } else {
                            json(ex, 404, "{\"error\":\"no such recording\"}");
                        }
                    } catch (IllegalArgumentException e) {
                        json(ex, 400, "{\"error\":" + ProfileApi.quote(e.getMessage()) + "}");
                    }
                    return;
                }
                default:
                    json(ex, 404, "{\"error\":\"not found\"}");
            }
        } finally {
            ex.close();
        }
    }

    String listJson() {
        StringBuilder sb = new StringBuilder("{\"on\":").append(dir != null).append(",\"disk\":")
                .append(ProfileApi.quote(dir == null ? "" : MediaDisks.spaceLine(dir))).append(",\"keepDays\":30,\"recordings\":[");
        int i = 0;
        for (Rec r : list()) {
            sb.append(i++ > 0 ? "," : "").append("{\"path\":").append(ProfileApi.quote(r.path)).append(",\"channel\":").append(ProfileApi.quote(r.channel))
                    .append(",\"owner\":").append(ProfileApi.quote(whoStreamed(r)))
                    .append(",\"title\":").append(ProfileApi.quote(titleOf(r))).append(",\"started\":").append(r.started)
                    .append(",\"modified\":").append(r.modified).append(",\"bytes\":").append(r.bytes).append(",\"recording\":").append(r.recording).append('}');
        }
        return sb.append("]}").toString();
    }

    private static void json(HttpExchange ex, int status, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(status, b.length);
        ex.getResponseBody().write(b);
    }
}