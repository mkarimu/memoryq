package com.vstream.Vidiserver;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Videos for everyone: YouTube links and uploaded files, with comments.
 *
 * Who can do what:
 *  - Everyone (no sign-in): watch public videos and read comments; Memoryq TV plays the featured ones.
 *  - Signed-in members: add videos (upload a file or paste a YouTube link), change the title and description of
 *    their own, pause / show and delete their own, comment, edit and delete their own comments, and delete any
 *    comment on their own videos.
 *  - The super-admin: all of that for every video, plus choose which videos play on Memoryq TV (and their order);
 *    the super-admin's own videos go on TV automatically. A video the super-admin paused stays paused for its owner.
 *  Guest accounts and suspended accounts cannot add, change or comment.
 *
 *   GET  /api/videos?scope=tv|all|mine|user|admin&u=&q=    lists
 *   GET  /api/videos/one?id=          GET /api/videos/comments?id=
 *   GET  /api/videos/file/ID.mp4     (seekable)       GET /api/videos/thumb/ID.jpg
 *   POST /api/videos/youtube   url, title, description, tv (admin: false = not on TV)
 *   POST /api/videos/upload?name=&title=&description=&tv=   (the file is the request body)
 *   POST /api/videos/edit id, title, description    /pause id, paused=true|false    /delete id
 *   POST /api/videos/feature id, on      /move id, dir=up|down     (super-admin)
 *   POST /api/videos/view id
 *   POST /api/videos/comment id, text    /comment/edit id, text    /comment/delete id
 */
public final class VideoApi {

    private static final Pattern FILE_PATH = Pattern.compile("/api/videos/file/(\\d{1,12})\\.mp4");
    private static final Pattern THUMB_PATH = Pattern.compile("/api/videos/thumb/(\\d{1,12})\\.jpg");
    private static final Pattern RANGE = Pattern.compile("bytes=(\\d*)-(\\d*)");
    static final List<String> TYPES = Collections.unmodifiableList(java.util.Arrays.asList(
            "mp4", "m4v", "mov", "webm", "mkv", "avi", "3gp", "mpeg", "mpg", "ts", "wmv", "flv"));
    private static final long MB = 1024L * 1024L;
    private static final long DAY = 24L * 3600000L;

    private final VideoStore store;
    private final UserStore users;
    private final SessionStore sessions;
    private final LongSupplier clock;
    private volatile Supplier<String> ffmpeg = () -> null;
    private volatile Supplier<String> ffprobe = () -> null;
    private volatile Function<String, String> youtubeTitle = VideoApi::fetchYoutubeTitle;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "video-prepare");
        t.setDaemon(true);
        return t;
    });
    private final Map<String, Long> recentViews = new ConcurrentHashMap<String, Long>();

    final long maxUploadBytes = Long.getLong("vstream.videoMaxMb", 1024L) * MB;
    final long userStorageBytes = Long.getLong("vstream.videoUserMb", 2048L) * MB;
    final int uploadsPerDay = Integer.getInteger("vstream.videosPerDay", 20);
    final int commentsPerHour = 30;

    public VideoApi(VideoStore store, UserStore users, SessionStore sessions, LongSupplier clock) {
        this.store = store;
        this.users = users;
        this.sessions = sessions;
        this.clock = clock;
    }

    public void setTools(Supplier<String> ffmpegPath, Supplier<String> ffprobePath) {
        this.ffmpeg = ffmpegPath == null ? () -> null : ffmpegPath;
        this.ffprobe = ffprobePath == null ? () -> null : ffprobePath;
    }

    /** For tests: where YouTube titles come from. */
    void setYoutubeTitles(Function<String, String> f) {
        this.youtubeTitle = f;
    }

    public VideoStore store() {
        return store;
    }

    /** Hourly: removes the videos and comments of deleted accounts. */
    public void startCleanup() {
        Thread t = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(3600000L);
                    int n = store.purge(u -> users.info(u) != null);
                    if (n > 0) {
                        System.out.println("[videos] removed " + n + " video(s) of deleted accounts");
                    }
                } catch (InterruptedException e) {
                    return;
                } catch (RuntimeException e) {
                    System.out.println("[videos] cleanup: " + e);
                }
            }
        }, "video-cleanup");
        t.setDaemon(true);
        t.start();
    }

    public void register(HttpServer server) {
        server.createContext("/api/videos", this::handle);
    }

    // ---- Who is asking ----

    private static final class Me {
        final String name;
        final boolean admin;

        Me(String name, boolean admin) {
            this.name = name;
            this.admin = admin;
        }
    }

    private Me me(HttpExchange ex) {
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        String token = auth != null && auth.startsWith("Bearer ") ? auth.substring(7).trim() : "";
        SessionStore.Session s = token.isEmpty() ? null : sessions.get(token);
        if (s == null) {
            return null;
        }
        if (s.role == UserStore.Role.ADMIN) {
            return new Me(s.username, true);
        }
        UserStore.Info info = users.info(s.username);
        if (info == null || info.disabled) {
            return null;
        }
        return new Me(info.username, false);
    }

    private Me member(HttpExchange ex) throws Bad {
        Me m = me(ex);
        if (m == null) {
            throw new Bad(401, "sign in to do that");
        }
        if (!m.admin) {
            UserStore.Info info = users.info(m.name);
            if (info == null || info.role == UserStore.Role.GUEST) {
                throw new Bad(403, "guest accounts cannot add videos or comments - create a free account");
            }
        }
        return m;
    }

    private static final class Bad extends Exception {
        final int status;

        Bad(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    /** Public and its owner's account is in good standing (a suspended person's videos are hidden). */
    boolean shown(VideoStore.Video v) {
        if (!v.isPublic()) {
            return false;
        }
        if ("admin".equalsIgnoreCase(v.owner)) {
            return true;
        }
        UserStore.Info info = users.info(v.owner);
        return info != null && !info.disabled;
    }

    private static boolean owns(Me m, VideoStore.Video v) {
        return m != null && (m.admin || v.owner.equalsIgnoreCase(m.name));
    }

    // ---- Requests ----

    private void handle(HttpExchange ex) throws IOException {
        try {
            String path = ex.getRequestURI().getPath();
            String method = ex.getRequestMethod().toUpperCase(Locale.ROOT);
            Matcher fm = FILE_PATH.matcher(path);
            if (fm.matches()) {
                serveFile(ex, Long.parseLong(fm.group(1)));
                return;
            }
            Matcher tm = THUMB_PATH.matcher(path);
            if (tm.matches()) {
                serveThumb(ex, Long.parseLong(tm.group(1)));
                return;
            }
            String query = ex.getRequestURI().getRawQuery();
            if (method.equals("GET")) {
                switch (path) {
                    case "/api/videos":
                    case "/api/videos/":
                        list(ex, query);
                        return;
                    case "/api/videos/one": {
                        VideoStore.Video v = visible(ex, id(q(query, "id")));
                        send(ex, 200, json(v, me(ex)));
                        return;
                    }
                    case "/api/videos/comments":
                        comments(ex, id(q(query, "id")));
                        return;
                    default:
                        throw new Bad(404, "not found");
                }
            }
            if (!method.equals("POST")) {
                throw new Bad(405, "use GET or POST");
            }
            if (path.equals("/api/videos/upload")) {
                upload(ex, query);
                return;
            }
            String body = readBody(ex, 16 * 1024);
            switch (path) {
                case "/api/videos/youtube":
                    youtube(ex, body);
                    return;
                case "/api/videos/edit": {
                    Me m = member(ex);
                    VideoStore.Video v = mine(m, id(q(body, "id")));
                    send(ex, 200, json(store.edit(v.id, q(body, "title"), q(body, "description"), clock.getAsLong()), m));
                    return;
                }
                case "/api/videos/pause": {
                    Me m = member(ex);
                    VideoStore.Video v = mine(m, id(q(body, "id")));
                    boolean pause = "true".equals(q(body, "paused"));
                    if (!pause && "admin".equals(v.paused) && !m.admin) {
                        throw new Bad(403, "this video was paused by Memoryq - contact support@memoryq.com");
                    }
                    String by = pause ? (m.admin && !v.owner.equalsIgnoreCase(m.name) ? "admin" : "owner") : null;
                    send(ex, 200, json(store.setPaused(v.id, by, clock.getAsLong()), m));
                    return;
                }
                case "/api/videos/delete": {
                    Me m = member(ex);
                    VideoStore.Video v = mine(m, id(q(body, "id")));
                    store.delete(v.id);
                    send(ex, 200, "{\"ok\":true}");
                    return;
                }
                case "/api/videos/feature": {
                    Me m = admin(ex);
                    VideoStore.Video v = existing(id(q(body, "id")));
                    send(ex, 200, json(store.setFeatured(v.id, !"false".equals(q(body, "on"))), m));
                    return;
                }
                case "/api/videos/move": {
                    admin(ex);
                    VideoStore.Video v = existing(id(q(body, "id")));
                    store.move(v.id, "up".equals(q(body, "dir")) ? -1 : 1);
                    send(ex, 200, "{\"ok\":true}");
                    return;
                }
                case "/api/videos/view": {
                    VideoStore.Video v = existing(id(q(body, "id")));
                    Me m = me(ex);
                    String who = (m != null ? m.name : String.valueOf(ex.getRemoteAddress().getAddress())) + "|" + v.id;
                    long now = clock.getAsLong();
                    Long last = recentViews.get(who);
                    if (last == null || now - last > 30 * 60000L) {
                        recentViews.put(who, now);
                        if (recentViews.size() > 50000) {
                            recentViews.clear();
                        }
                        store.addView(v.id);
                    }
                    send(ex, 200, "{\"ok\":true}");
                    return;
                }
                case "/api/videos/comment": {
                    Me m = member(ex);
                    VideoStore.Video v = visible(ex, id(q(body, "id")));
                    String text = VideoStore.clean(q(body, "text"), VideoStore.MAX_COMMENT);
                    if (text.isEmpty()) {
                        throw new Bad(400, "write something first");
                    }
                    if (!m.admin && store.commentsSince(m.name, clock.getAsLong() - 3600000L) >= commentsPerHour) {
                        throw new Bad(429, "that is a lot of comments - try again in a while");
                    }
                    store.addComment(v.id, m.name, text, clock.getAsLong());
                    comments(ex, v.id);
                    return;
                }
                case "/api/videos/comment/edit": {
                    Me m = member(ex);
                    VideoStore.Comment c = store.comment(id(q(body, "id")));
                    if (c == null) {
                        throw new Bad(404, "that comment is gone");
                    }
                    if (!c.author.equalsIgnoreCase(m.name)) {
                        throw new Bad(403, "you can only change your own comments");
                    }
                    String text = VideoStore.clean(q(body, "text"), VideoStore.MAX_COMMENT);
                    if (text.isEmpty()) {
                        throw new Bad(400, "write something first");
                    }
                    store.editComment(c.id, text);
                    comments(ex, c.video);
                    return;
                }
                case "/api/videos/comment/delete": {
                    Me m = member(ex);
                    VideoStore.Comment c = store.comment(id(q(body, "id")));
                    if (c == null) {
                        throw new Bad(404, "that comment is gone");
                    }
                    VideoStore.Video v = store.get(c.video);
                    boolean ok = m.admin || c.author.equalsIgnoreCase(m.name) || (v != null && v.owner.equalsIgnoreCase(m.name));
                    if (!ok) {
                        throw new Bad(403, "you can delete your own comments, and comments on your own videos");
                    }
                    store.deleteComment(c.id);
                    comments(ex, c.video);
                    return;
                }
                default:
                    throw new Bad(404, "not found");
            }
        } catch (Bad e) {
            if (e.status == 413 || e.status == 400) {
                drain(ex);
            }
            send(ex, e.status, "{\"error\":" + jq(e.getMessage()) + "}");
        } catch (RuntimeException e) {
            send(ex, 500, "{\"error\":" + jq("server error: " + e) + "}");
        } finally {
            ex.close();
        }
    }

    private Me admin(HttpExchange ex) throws Bad {
        Me m = me(ex);
        if (m == null || !m.admin) {
            throw new Bad(403, "only the super-admin can do that");
        }
        return m;
    }

    private VideoStore.Video existing(long id) throws Bad {
        VideoStore.Video v = store.get(id);
        if (v == null) {
            throw new Bad(404, "that video is gone");
        }
        return v;
    }

    /** A video the person asking may see: public ones, plus their own (and all for the admin). */
    private VideoStore.Video visible(HttpExchange ex, long id) throws Bad {
        VideoStore.Video v = existing(id);
        if (!shown(v) && !owns(me(ex), v)) {
            throw new Bad(404, "that video is not available");
        }
        return v;
    }

    private VideoStore.Video mine(Me m, long id) throws Bad {
        VideoStore.Video v = existing(id);
        if (!owns(m, v)) {
            throw new Bad(403, "that is not your video");
        }
        return v;
    }

    private void list(HttpExchange ex, String query) throws IOException, Bad {
        String scope = String.valueOf(q(query, "scope"));
        String search = VideoStore.lower(q(query, "q")).trim();
        Me m = me(ex);
        List<VideoStore.Video> out;
        switch (scope) {
            case "tv":
                out = store.tv();
                out.removeIf(v -> !shown(v));
                break;
            case "mine":
                if (m == null) {
                    throw new Bad(401, "sign in to see your videos");
                }
                out = store.byOwner(m.name);
                break;
            case "user": {
                String u = String.valueOf(q(query, "u"));
                boolean all = m != null && (m.admin || m.name.equalsIgnoreCase(u));
                out = store.list(v -> v.owner.equalsIgnoreCase(u) && (all || shown(v)));
                break;
            }
            case "admin":
                admin(ex);
                out = store.list(v -> true);
                break;
            default:
                out = store.list(this::shown);
        }
        StringBuilder sb = new StringBuilder("{\"videos\":[");
        int n = 0;
        for (VideoStore.Video v : out) {
            if (!search.isEmpty() && !(VideoStore.lower(v.title + " " + v.description + " " + v.owner)).contains(search)) {
                continue;
            }
            if (n >= 300) {
                break;
            }
            sb.append(n++ > 0 ? "," : "").append(json(v, m));
        }
        sb.append("]");
        if (m != null && "mine".equals(scope)) {
            sb.append(",\"usedMb\":").append(store.bytesOf(m.name) / MB).append(",\"limitMb\":").append(m.admin ? 0 : userStorageBytes / MB)
                    .append(",\"maxUploadMb\":").append(maxUploadBytes / MB);
        }
        send(ex, 200, sb.append('}').toString());
    }

    private void comments(HttpExchange ex, long id) throws IOException, Bad {
        VideoStore.Video v = visible(ex, id);
        Me m = me(ex);
        StringBuilder sb = new StringBuilder("{\"video\":" + v.id + ",\"comments\":[");
        int n = 0;
        for (VideoStore.Comment c : store.comments(v.id)) {
            boolean canEdit = m != null && c.author.equalsIgnoreCase(m.name);
            boolean canDelete = m != null && (m.admin || canEdit || v.owner.equalsIgnoreCase(m.name));
            sb.append(n++ > 0 ? "," : "").append("{\"id\":").append(c.id).append(",\"author\":").append(jq(c.author))
                    .append(",\"text\":").append(jq(c.text)).append(",\"created\":").append(c.created)
                    .append(",\"edited\":").append(c.edited).append(",\"canEdit\":").append(canEdit)
                    .append(",\"canDelete\":").append(canDelete).append('}');
        }
        send(ex, 200, sb.append("]}").toString());
    }

    String json(VideoStore.Video v, Me m) {
        boolean yt = VideoStore.YOUTUBE.equals(v.kind);
        StringBuilder sb = new StringBuilder();
        sb.append("{\"id\":").append(v.id).append(",\"owner\":").append(jq(v.owner)).append(",\"kind\":").append(jq(v.kind))
                .append(",\"title\":").append(jq(v.title)).append(",\"description\":").append(jq(v.description))
                .append(",\"status\":").append(jq(v.status)).append(",\"paused\":").append(jq(v.paused))
                .append(",\"featured\":").append(v.featured).append(",\"pos\":").append(v.pos)
                .append(",\"created\":").append(v.created).append(",\"durationMs\":").append(v.durationMs)
                .append(",\"views\":").append(v.views).append(",\"comments\":").append(store.commentCount(v.id));
        if (yt) {
            sb.append(",\"youtubeId\":").append(jq(v.ref)).append(",\"thumb\":").append(jq("https://i.ytimg.com/vi/" + v.ref + "/hqdefault.jpg"));
        } else {
            boolean ready = VideoStore.READY.equals(v.status);
            sb.append(",\"src\":").append(ready ? jq("/api/videos/file/" + v.id + ".mp4?v=" + v.updated) : "null")
                    .append(",\"thumb\":").append(ready && Files.isRegularFile(store.thumbOf(v.id)) ? jq("/api/videos/thumb/" + v.id + ".jpg?v=" + v.updated) : "null")
                    .append(",\"sizeMb\":").append(Math.round(v.bytes * 10.0 / MB) / 10.0);
        }
        if (!v.error.isEmpty() && owns(m, v)) {
            sb.append(",\"error\":").append(jq(v.error));
        }
        sb.append(",\"canEdit\":").append(owns(m, v)).append(",\"canFeature\":").append(m != null && m.admin);
        return sb.append('}').toString();
    }

    // ---- Adding videos ----

    private void youtube(HttpExchange ex, String body) throws IOException, Bad {
        Me m = member(ex);
        String yt = VideoStore.youtubeId(q(body, "url"));
        if (yt == null) {
            throw new Bad(400, "paste a YouTube link, like https://www.youtube.com/watch?v=... or https://youtu.be/...");
        }
        checkQuota(m, 0);
        String title = VideoStore.clean(q(body, "title"), VideoStore.MAX_TITLE);
        if (title.isEmpty()) {
            String t = null;
            try {
                t = youtubeTitle.apply(yt);
            } catch (RuntimeException e) {
                // no title from YouTube
            }
            title = t == null || t.trim().isEmpty() ? "YouTube video" : t;
        }
        boolean tv = m.admin && !"false".equals(q(body, "tv"));
        VideoStore.Video v = store.add(m.name, VideoStore.YOUTUBE, yt, title, q(body, "description"), tv, VideoStore.READY, clock.getAsLong());
        send(ex, 200, json(v, m));
    }

    private void checkQuota(Me m, long extraBytes) throws Bad {
        if (m.admin) {
            return;
        }
        if (store.countSince(m.name, clock.getAsLong() - DAY) >= uploadsPerDay) {
            throw new Bad(429, "you have added " + uploadsPerDay + " videos today - that is the daily limit");
        }
        long used = store.bytesOf(m.name);
        if (extraBytes > 0 && used + extraBytes > userStorageBytes) {
            throw new Bad(413, "your video space is full (" + used / MB + " of " + userStorageBytes / MB + " MB) - delete some videos first");
        }
    }

    private void upload(HttpExchange ex, String query) throws IOException, Bad {
        Me m = member(ex);
        String name = String.valueOf(q(query, "name"));
        int dot = name.lastIndexOf('.');
        String ext = dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (!TYPES.contains(ext)) {
            throw new Bad(400, "only video files can be uploaded: " + String.join(", ", TYPES));
        }
        long declared = -1;
        try {
            declared = Long.parseLong(String.valueOf(ex.getRequestHeaders().getFirst("Content-Length")));
        } catch (NumberFormatException e) {
            // chunked upload
        }
        long max = m.admin ? Math.max(maxUploadBytes, 8L * 1024 * MB) : maxUploadBytes;
        if (declared > max) {
            throw new Bad(413, "videos can be " + max / MB + " MB at most");
        }
        checkQuota(m, Math.max(declared, 1));
        if (ffmpeg.get() == null && !ext.equals("mp4")) {
            throw new Bad(409, "the server cannot convert videos yet (FFmpeg is missing) - upload an .mp4 file");
        }
        String title = VideoStore.clean(q(query, "title"), VideoStore.MAX_TITLE);
        if (title.isEmpty()) {
            title = VideoStore.clean(dot > 0 ? name.substring(0, dot) : name, VideoStore.MAX_TITLE).replace('_', ' ');
        }
        Path tmp = Files.createTempFile(store.dir().resolve("tmp"), "up-", "." + ext);
        long total = 0;
        try (InputStream in = ex.getRequestBody(); OutputStream out = Files.newOutputStream(tmp)) {
            byte[] b = new byte[1 << 16];
            int n;
            while ((n = in.read(b)) != -1) {
                total += n;
                if (total > max) {
                    throw new Bad(413, "videos can be " + max / MB + " MB at most");
                }
                out.write(b, 0, n);
            }
        } catch (Bad | IOException e) {
            Files.deleteIfExists(tmp);
            throw e;
        }
        if (total < 1000) {
            Files.deleteIfExists(tmp);
            throw new Bad(400, "the file is empty");
        }
        boolean tv = m.admin && !"false".equals(q(query, "tv"));
        VideoStore.Video v = store.add(m.name, VideoStore.FILE, "", title, q(query, "description"), tv, VideoStore.PROCESSING, clock.getAsLong());
        final long size = total;
        worker.execute(() -> prepare(v.id, tmp, ext, size));
        send(ex, 200, json(store.get(v.id), m));
    }

    /**
     * Makes an upload playable in every browser: an H.264 MP4 is only re-packed (fast); anything else is converted
     * to H.264 / AAC, at most 1280 pixels wide. Then a picture is taken for the thumbnail.
     */
    void prepare(long id, Path in, String ext, long size) {
        VideoStore.Video v = store.get(id);
        if (v == null) {
            quietDelete(in);
            return;
        }
        Path out = store.fileOf(v);
        Path part = out.resolveSibling(id + ".part.mp4");
        try {
            String ff = ffmpeg.get();
            if (ff == null) {
                Files.move(in, out, StandardCopyOption.REPLACE_EXISTING);
                store.processed(id, VideoStore.READY, 0, Files.size(out), "");
                return;
            }
            String[] info = probe(in);
            if (info[0].isEmpty()) {
                throw new IOException("this file has no video picture that can be played");
            }
            boolean copy = info[0].equals("h264") && (info[1].isEmpty() || info[1].equals("aac") || info[1].equals("mp3"))
                    && (ext.equals("mp4") || ext.equals("m4v") || ext.equals("mov"));
            List<String> cmd = new ArrayList<String>();
            Collections.addAll(cmd, ff, "-hide_banner", "-loglevel", "error", "-nostdin", "-y", "-i", in.toAbsolutePath().toString());
            if (copy) {
                Collections.addAll(cmd, "-c", "copy");
            } else {
                Collections.addAll(cmd, "-vf", "scale='trunc(min(1280,iw)/2)*2':-2", "-c:v", "libx264", "-preset", "veryfast",
                        "-crf", "23", "-pix_fmt", "yuv420p", "-c:a", "aac", "-b:a", "128k", "-ac", "2");
            }
            Collections.addAll(cmd, "-movflags", "+faststart", part.toAbsolutePath().toString());
            String err = run(cmd, copy ? 600 : 7200);
            if (err != null || !Files.isRegularFile(part) || Files.size(part) < 1000) {
                throw new IOException("the video could not be converted" + (err == null || err.isEmpty() ? "" : ": " + err));
            }
            Files.move(part, out, StandardCopyOption.REPLACE_EXISTING);
            long ms = Math.round(Double.parseDouble(info[2].isEmpty() ? "0" : info[2]) * 1000);
            double at = Math.min(3.0, ms / 3000.0);
            List<String> th = new ArrayList<String>();
            Collections.addAll(th, ff, "-hide_banner", "-loglevel", "error", "-nostdin", "-y", "-ss", String.format(Locale.ROOT, "%.2f", at),
                    "-i", out.toAbsolutePath().toString(), "-frames:v", "1", "-vf", "scale=480:-2", "-q:v", "5",
                    store.thumbOf(id).toAbsolutePath().toString());
            run(th, 60);
            store.processed(id, VideoStore.READY, ms, Files.size(out), "");
        } catch (Exception e) {
            store.processed(id, VideoStore.FAILED, 0, 0, e.getMessage() == null ? e.toString() : e.getMessage());
            quietDelete(out);
        } finally {
            quietDelete(in);
            quietDelete(part);
        }
    }

    /**
     * Adds a video file from the server itself (a kept live recording): it is moved into the videos folder and
     * prepared like an upload. Returns the new video (still being prepared).
     */
    public VideoStore.Video importFile(String owner, Path file, String title, String description) throws IOException {
        return importFile(owner, file, title, description, false);
    }

    /** The same; onTv = it plays on Memoryq / Mkarimu TV as soon as it is prepared. */
    public VideoStore.Video importFile(String owner, Path file, String title, String description, boolean onTv) throws IOException {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String ext = dot > 0 ? name.substring(dot + 1).toLowerCase(Locale.ROOT) : "mp4";
        Files.createDirectories(store.dir().resolve("tmp"));
        Path tmp = store.dir().resolve("tmp").resolve("rec-" + System.nanoTime() + "." + ext);
        try {
            Files.move(file, tmp); // the same disk: instant
        } catch (IOException e) {
            Files.copy(file, tmp, StandardCopyOption.REPLACE_EXISTING);
            Files.delete(file);
        }
        final long size = Files.size(tmp);
        VideoStore.Video v = store.add(owner, VideoStore.FILE, "", VideoStore.clean(title, VideoStore.MAX_TITLE), description, onTv,
                VideoStore.PROCESSING, clock.getAsLong());
        worker.execute(() -> prepare(v.id, tmp, ext, size));
        return v;
    }

    /** {video codec, audio codec, seconds} ("" when missing). */
    private String[] probe(Path file) throws IOException {
        String fp = ffprobe.get();
        String[] r = {"", "", ""};
        if (fp == null) {
            r[0] = "unknown";
            return r;
        }
        Process p = new ProcessBuilder(fp, "-v", "error", "-show_entries", "stream=codec_type,codec_name:format=duration",
                "-of", "default=noprint_wrappers=1", file.toAbsolutePath().toString()).redirectErrorStream(true).start();
        String out = new String(readAll(p.getInputStream()), StandardCharsets.UTF_8);
        try {
            p.waitFor(60, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        String codec = "";
        for (String line : out.split("\\r?\\n")) {
            String l = line.trim();
            if (l.startsWith("codec_name=")) {
                codec = l.substring(11);
            } else if (l.equals("codec_type=video") && r[0].isEmpty() && !codec.matches("mjpeg|png|bmp|gif")) {
                r[0] = codec;
            } else if (l.equals("codec_type=audio") && r[1].isEmpty()) {
                r[1] = codec;
            } else if (l.startsWith("duration=") && !l.contains("N/A")) {
                r[2] = l.substring(9);
            }
        }
        return r;
    }

    /** Runs a command; null when it worked, otherwise the last of what it said. */
    private static String run(List<String> cmd, int seconds) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        ByteArrayOutputStream log = new ByteArrayOutputStream();
        Thread reader = new Thread(() -> {
            try (InputStream in = p.getInputStream()) {
                byte[] b = new byte[4096];
                int n;
                while ((n = in.read(b)) > 0) {
                    if (log.size() < 20000) {
                        log.write(b, 0, n);
                    }
                }
            } catch (IOException ignored) {
                // finished
            }
        });
        reader.start();
        if (!p.waitFor(seconds, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            return "it took too long";
        }
        reader.join(2000);
        if (p.exitValue() == 0) {
            return null;
        }
        String s = new String(log.toByteArray(), StandardCharsets.UTF_8).trim();
        return s.length() > 300 ? s.substring(s.length() - 300) : s;
    }

    private static void quietDelete(Path p) {
        try {
            Files.deleteIfExists(p);
        } catch (IOException ignored) {
            // harmless
        }
    }

    static String fetchYoutubeTitle(String id) {
        HttpURLConnection c = null;
        try {
            String u = "https://www.youtube.com/oembed?format=json&url=" + URLEncoder.encode("https://www.youtube.com/watch?v=" + id, "UTF-8");
            c = (HttpURLConnection) new URL(u).openConnection();
            c.setConnectTimeout(4000);
            c.setReadTimeout(4000);
            if (c.getResponseCode() != 200) {
                return null;
            }
            String body = new String(readAll(c.getInputStream()), StandardCharsets.UTF_8);
            Object o = MiniJson.parse(body);
            if (o instanceof Map) {
                Object t = ((Map<?, ?>) o).get("title");
                return t == null ? null : t.toString();
            }
            return null;
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) {
                c.disconnect();
            }
        }
    }

    // ---- Serving files ----

    private void serveThumb(HttpExchange ex, long id) throws IOException {
        VideoStore.Video v = store.get(id);
        Path p = store.thumbOf(id);
        if (v == null || !shown(v) && !owns(me(ex), v) || !Files.isRegularFile(p)) {
            send(ex, 404, "{\"error\":\"no picture\"}");
            return;
        }
        byte[] b = Files.readAllBytes(p);
        ex.getResponseHeaders().set("Content-Type", "image/jpeg");
        ex.getResponseHeaders().set("Cache-Control", "public, max-age=86400");
        ex.sendResponseHeaders(200, b.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(b);
        }
    }

    /** The video file, with byte ranges so players can seek and phones can stream it. */
    private void serveFile(HttpExchange ex, long id) throws IOException {
        VideoStore.Video v = store.get(id);
        Path p = v == null ? null : store.fileOf(v);
        if (v == null || !shown(v) || !Files.isRegularFile(p)) {
            send(ex, 404, "{\"error\":\"that video is not available\"}");
            return;
        }
        sendFile(ex, p, "video/mp4", "public, max-age=3600");
    }

    /** Sends a file with byte ranges (players seek with them). Does not close the exchange. */
    static void sendFile(HttpExchange ex, Path p, String type, String cache) throws IOException {
        long size = Files.size(p);
        long start = 0;
        long end = size - 1;
        int status = 200;
        String range = ex.getRequestHeaders().getFirst("Range");
        if (range != null) {
            Matcher m = RANGE.matcher(range.trim());
            if (m.matches() && !(m.group(1).isEmpty() && m.group(2).isEmpty())) {
                if (m.group(1).isEmpty()) {
                    start = Math.max(0, size - Long.parseLong(m.group(2)));
                } else {
                    start = Long.parseLong(m.group(1));
                    if (!m.group(2).isEmpty()) {
                        end = Math.min(end, Long.parseLong(m.group(2)));
                    }
                }
                if (start > end || start >= size) {
                    ex.getResponseHeaders().set("Content-Range", "bytes */" + size);
                    ex.sendResponseHeaders(416, -1);
                    return;
                }
                status = 206;
                ex.getResponseHeaders().set("Content-Range", "bytes " + start + "-" + end + "/" + size);
            }
        }
        long len = end - start + 1;
        ex.getResponseHeaders().set("Content-Type", type);
        ex.getResponseHeaders().set("Accept-Ranges", "bytes");
        ex.getResponseHeaders().set("Cache-Control", cache);
        if ("HEAD".equalsIgnoreCase(ex.getRequestMethod())) {
            ex.getResponseHeaders().set("Content-Length", String.valueOf(len));
            ex.sendResponseHeaders(status, -1);
            return;
        }
        ex.sendResponseHeaders(status, len);
        try (RandomAccessFile raf = new RandomAccessFile(p.toFile(), "r"); OutputStream os = ex.getResponseBody()) {
            raf.seek(start);
            byte[] b = new byte[1 << 16];
            long left = len;
            while (left > 0) {
                int n = raf.read(b, 0, (int) Math.min(b.length, left));
                if (n < 0) {
                    break;
                }
                os.write(b, 0, n);
                left -= n;
            }
        } catch (IOException e) {
            // the viewer stopped or seeked; normal for video
        }
    }

    // ---- Helpers ----

    private static long id(String s) throws Bad {
        if (s == null || !s.matches("\\d{1,12}")) {
            throw new Bad(400, "which video?");
        }
        return Long.parseLong(s);
    }

    private static String q(String body, String key) {
        return MediaAuthHttpServer.queryParam(body, key);
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] b = new byte[8192];
        int n;
        while ((n = in.read(b)) > 0) {
            out.write(b, 0, n);
        }
        return out.toByteArray();
    }

    private static String readBody(HttpExchange ex, int max) throws IOException, Bad {
        InputStream in = ex.getRequestBody();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] b = new byte[4096];
        int n;
        while ((n = in.read(b)) > 0) {
            out.write(b, 0, n);
            if (out.size() > max) {
                throw new Bad(413, "too much text");
            }
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private static void drain(HttpExchange ex) {
        try (InputStream in = ex.getRequestBody()) {
            byte[] b = new byte[1 << 16];
            long total = 0;
            int n;
            while ((n = in.read(b)) > 0 && total < 64L * MB) {
                total += n;
            }
        } catch (IOException ignored) {
            // the sender gave up
        }
    }

    static String jq(String s) {
        if (s == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                case '<': sb.append("\\u003c"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.append('"').toString();
    }

    private static void send(HttpExchange ex, int status, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(status, b.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(b);
        }
    }
}