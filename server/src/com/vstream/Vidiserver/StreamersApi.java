package com.vstream.Vidiserver;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Predicate;

/**
 * Video streamers: every stream with its length, each streamer's totals and loyalty, and what to do with a stream.
 *
 * Super-admin:
 *   GET  /api/admin/streamers/list                        everyone who streamed: streams, time, longest, points, level
 *   GET  /api/admin/streamers/sessions?user=U             their streams (no user: the latest of everyone), with the
 *                                                         recordings and the kept video (on TV or not)
 *   POST /api/admin/streamers/keep    session, tv=1|0     keep the stream's recording as a video (and put it on TV)
 *   POST /api/admin/streamers/tv      session, on=1|0     the kept video on / off Mkarimu TV - back and forth
 *   POST /api/admin/streamers/delete  session             take it down: stop it if live, delete recordings and video
 *   POST /api/admin/streamers/restore session             count it again (the files are gone)
 *   GET/POST /api/admin/streamers/alerts  on=1|0          e-mail the admin when someone goes live
 * Members (their own):
 *   GET  /api/me/streams                                  my streams, totals, loyalty, anonymous?, alerts
 *   POST /api/me/streams/delete  session                  take my own stream down
 *   POST /api/me/streams/alerts  on=1|0                   e-mail me when someone goes live on Memoryq
 * Anonymous testers (tester_123456) keep their streams by registering:
 *   POST /api/vstream/claim  username, password, email  -> a code by e-mail
 *   POST /api/vstream/claim/verify  username, code      -> the new account (streams, recordings, videos move over)
 *   POST /api/vstream/claim/resend  username
 * Also: a new stream e-mails the admin and the members who asked, and is first in the news feed while it is live.
 */
public final class StreamersApi {

    /** One e-mail about the same streamer at most this often. */
    static final long ALERT_GAP_MS = 30L * 60000L;

    public interface Sender {
        void send(String to, String subject, String text);
    }

    private final StreamLog log;
    private final UserStore users;
    private final SessionStore sessions;
    private final LongSupplier clock;
    private volatile Recordings recordings;
    private volatile VideoStore videos;
    private volatile StreamHistory history;
    private volatile EmailService emails;
    private volatile Sender sender;
    private volatile Function<String, String> emailOf;
    private volatile String adminEmail = "";
    private volatile String host = "memoryq.com";
    private volatile Consumer<String> kick;
    private volatile Path alertsFile;
    private final Set<String> alertUsers = new LinkedHashSet<String>();
    private boolean adminAlerts = true;
    private final Map<String, Long> lastAlert = new HashMap<String, Long>();
    /** Registration in progress: new username -> the tester account it replaces. */
    private final Map<String, String> claims = new HashMap<String, String>();
    private final ExecutorService mailPool = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "stream-alerts");
        t.setDaemon(true);
        return t;
    });
    private ScheduledExecutorService timer;

    public StreamersApi(StreamLog log, UserStore users, SessionStore sessions, LongSupplier clock) {
        this.log = log;
        this.users = users;
        this.sessions = sessions;
        this.clock = clock;
        log.setOnNew(this::newStream);
    }

    public StreamersApi recordings(Recordings r) {
        this.recordings = r;
        return this;
    }

    public StreamersApi videos(VideoStore v) {
        this.videos = v;
        return this;
    }

    public StreamersApi history(StreamHistory h) {
        this.history = h;
        return this;
    }

    public StreamersApi emails(EmailService e) {
        this.emails = e;
        return this;
    }

    public StreamersApi kick(Consumer<String> k) {
        this.kick = k;
        return this;
    }

    public StreamersApi mail(Sender s, Function<String, String> emailOfUser, String admin, String publicHost) {
        this.sender = s;
        this.emailOf = emailOfUser;
        this.adminEmail = admin == null ? "" : admin;
        if (publicHost != null && !publicHost.isEmpty()) {
            this.host = publicHost;
        }
        return this;
    }

    /** Who asked for an e-mail when someone goes live (one username per line; "#admin off" switches the admin's off). */
    public synchronized StreamersApi alertsFile(Path f) {
        this.alertsFile = f;
        alertUsers.clear();
        if (f != null && Files.isRegularFile(f)) {
            try {
                for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                    String l = line.trim();
                    if (l.equals("#admin off")) {
                        adminAlerts = false;
                    } else if (!l.isEmpty() && !l.startsWith("#")) {
                        alertUsers.add(l.toLowerCase(Locale.ROOT));
                    }
                }
            } catch (IOException e) {
                System.out.println("[streams] could not read " + f + ": " + e.getMessage());
            }
        }
        return this;
    }

    private void saveAlerts() {
        Path f = alertsFile;
        if (f == null) {
            return;
        }
        StringBuilder sb = new StringBuilder("# members who get an e-mail when someone goes live\n");
        if (!adminAlerts) {
            sb.append("#admin off\n");
        }
        for (String u : alertUsers) {
            sb.append(u).append('\n');
        }
        try {
            Files.write(f, sb.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            System.out.println("[streams] could not save " + f + ": " + e.getMessage());
        }
    }

    /** Checks every 20 seconds which streams are still on. */
    public synchronized void start(Predicate<String> isLive) {
        if (timer != null) {
            return;
        }
        timer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "stream-log");
            t.setDaemon(true);
            return t;
        });
        timer.scheduleWithFixedDelay(() -> {
            try {
                log.tick(isLive);
            } catch (RuntimeException e) {
                System.out.println("[streams] " + e);
            }
        }, 20, 20, TimeUnit.SECONDS);
    }

    public static boolean anonymous(UserStore.Info i, String username) {
        return (username != null && username.toLowerCase(Locale.ROOT).startsWith("tester_"))
                || (i != null && i.role == UserStore.Role.GUEST);
    }

    // ---- A new stream: e-mails and the news feed ----

    private void newStream(StreamLog.Session s) {
        if (s.user.isEmpty()) {
            return;
        }
        Sender snd = sender;
        if (snd == null) {
            return;
        }
        List<String[]> out = new ArrayList<String[]>();
        String who = s.user;
        String title = s.title.isEmpty() ? "a live stream" : "\"" + s.title + "\"";
        String watch = "https://" + host + "/tv#" + s.channel;
        boolean anon = anonymous(users.info(s.user), s.user);
        synchronized (this) {
            long now = clock.getAsLong();
            Long last = lastAlert.get(who.toLowerCase(Locale.ROOT));
            if (last != null && now - last < ALERT_GAP_MS) {
                return;
            }
            lastAlert.put(who.toLowerCase(Locale.ROOT), now);
            if (adminAlerts && !adminEmail.isEmpty()) {
                out.add(new String[]{adminEmail, "Live now on Memoryq: " + who + " - " + (s.title.isEmpty() ? s.channel : s.title),
                        who + (anon ? " (anonymous tester account)" : "") + " started " + title + " on " + s.channel + " at "
                                + BackupJob.when(s.start) + ".\n\nWatch: " + watch
                                + "\nKeep it, put it on Mkarimu TV or take it down: https://" + host + "/admin (Videos -> Streamers)\n"});
            }
            Function<String, String> f = emailOf;
            for (String u : alertUsers) {
                if (u.equalsIgnoreCase(who) || f == null) {
                    continue;
                }
                String to = f.apply(u);
                if (to != null && !to.isEmpty()) {
                    out.add(new String[]{to, who + " is live on Memoryq",
                            "Hi " + u + ",\n\n" + who + " just went live with " + title + ".\n\nWatch: " + watch
                                    + "\n\nYou get this e-mail because you asked to hear when someone goes live. Switch it off: https://"
                                    + host + "/me#live/mystreams\n"});
                }
            }
        }
        for (String[] m : out) {
            mailPool.execute(() -> {
                try {
                    snd.send(m[0], m[1], m[2]);
                } catch (RuntimeException e) {
                    System.out.println("[streams] could not e-mail " + m[0] + ": " + e);
                }
            });
        }
    }

    /** The news feed's first items: who is live right now (shown the moment a stream starts). */
    public List<String> liveNews() {
        List<String> out = new ArrayList<String>();
        long now = clock.getAsLong();
        for (StreamLog.Session s : log.liveNow()) {
            long mins = Math.max(0, (now - s.start) / 60000L);
            out.add("{\"id\":" + (-s.id) + ",\"live\":true,\"headline\":" + q("LIVE NOW: " + s.user + (s.title.isEmpty() ? " is streaming" : " - " + s.title))
                    + ",\"summary\":" + q(s.user + " went live " + (mins < 1 ? "just now" : mins + " min ago") + " on Memoryq. Tap to watch.")
                    + ",\"topic\":\"live\",\"source\":\"Memoryq Live\",\"link\":" + q("https://" + host + "/tv#" + s.channel)
                    + ",\"channel\":" + q(s.channel) + ",\"published\":" + s.start + ",\"created\":" + s.start + ",\"ai\":false}");
        }
        return out;
    }

    // ---- HTTP ----

    public void register(HttpServer server) {
        server.createContext("/api/admin/streamers/", this::handleAdmin);
        server.createContext("/api/me/streams", this::handleMine);
        server.createContext("/api/vstream/claim", this::handleClaim);
    }

    private static final class Bad extends Exception {
        final int status;

        Bad(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    private UserStore.Info me(HttpExchange ex) {
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        String token = auth != null && auth.startsWith("Bearer ") ? auth.substring(7).trim() : "";
        SessionStore.Session s = token.isEmpty() ? null : sessions.get(token);
        return s == null ? null : users.info(s.username);
    }

    private static String body(HttpExchange ex) throws IOException {
        return new String(AudioTools.readAll(ex.getRequestBody()), StandardCharsets.UTF_8);
    }

    private static String p(String form, String key) {
        String v = MediaAuthHttpServer.queryParam(form, key);
        return v == null ? "" : v.trim();
    }

    private static long num(String v) throws Bad {
        try {
            return Long.parseLong(v.trim());
        } catch (RuntimeException e) {
            throw new Bad(400, "which stream?");
        }
    }

    private void handleAdmin(HttpExchange ex) throws IOException {
        try {
            UserStore.Info me = me(ex);
            if (me == null || me.role != UserStore.Role.ADMIN) {
                throw new Bad(403, "admins only");
            }
            String op = ex.getRequestURI().getPath().substring("/api/admin/streamers/".length());
            String query = ex.getRequestURI().getRawQuery();
            boolean post = "POST".equalsIgnoreCase(ex.getRequestMethod());
            String form = post ? body(ex) : "";
            switch (op) {
                case "list":
                    json(ex, 200, listJson());
                    return;
                case "sessions": {
                    String user = p(query, "user");
                    json(ex, 200, sessionsJson(log.list(user.isEmpty() ? null : user, true, user.isEmpty() ? 200 : 500), true));
                    return;
                }
                case "alerts": {
                    synchronized (this) {
                        if (post) {
                            adminAlerts = "1".equals(p(form, "on"));
                            saveAlerts();
                        }
                        json(ex, 200, "{\"on\":" + adminAlerts + ",\"to\":" + q(adminEmail) + ",\"members\":" + alertUsers.size() + "}");
                    }
                    return;
                }
                default:
                    break;
            }
            if (!post) {
                throw new Bad(405, "use POST");
            }
            StreamLog.Session s = log.get(num(p(form, "session")));
            if (s == null) {
                throw new Bad(404, "no such stream");
            }
            switch (op) {
                case "keep": {
                    long vid = keep(s, "1".equals(p(form, "tv")), p(form, "title"));
                    System.out.println("[streams] admin " + me.username + " kept stream " + s.id + " of " + s.user + " as video " + vid);
                    break;
                }
                case "tv": {
                    boolean on = "1".equals(p(form, "on"));
                    VideoStore vs = videos;
                    if (s.videoId == 0 || vs == null || vs.get(s.videoId) == null) {
                        throw new Bad(409, "keep the stream as a video first");
                    }
                    vs.setFeatured(s.videoId, on);
                    System.out.println("[streams] admin " + me.username + (on ? " put " : " took ") + "video " + s.videoId
                            + (on ? " on" : " off") + " Mkarimu TV");
                    break;
                }
                case "delete":
                    takeDown(s, me.username);
                    break;
                case "restore":
                    log.setRemoved(s.id, false);
                    break;
                default:
                    throw new Bad(404, "not found");
            }
            json(ex, 200, sessionsJson(java.util.Collections.singletonList(log.get(s.id)), true));
        } catch (Bad e) {
            json(ex, e.status, "{\"error\":" + q(e.getMessage()) + "}");
        } catch (IllegalArgumentException e) {
            json(ex, 400, "{\"error\":" + q(e.getMessage()) + "}");
        } catch (Exception e) {
            json(ex, 500, "{\"error\":" + q("server error: " + e.getMessage()) + "}");
        } finally {
            ex.close();
        }
    }

    /** Keeps the stream's longest recording as a video. */
    private long keep(StreamLog.Session s, boolean onTv, String title) throws Exception {
        VideoStore vs = videos;
        if (s.videoId != 0 && vs != null && vs.get(s.videoId) != null) {
            vs.setFeatured(s.videoId, onTv);
            return s.videoId;
        }
        Recordings rec = recordings;
        if (rec == null) {
            throw new Bad(409, "recordings are not set up on this server");
        }
        if (s.live()) {
            throw new Bad(409, "the stream is still going on - keep it when it has ended");
        }
        Recordings.Rec best = null;
        for (Recordings.Rec r : rec.recordingsOf(s, clock.getAsLong())) {
            if (!r.recording && (best == null || r.bytes > best.bytes)) {
                best = r;
            }
        }
        if (best == null) {
            throw new Bad(404, "this stream has no recording any more (they are kept 30 days)");
        }
        String t = title.isEmpty() ? (s.title.isEmpty() ? "Live: " + s.user : s.title) : title;
        VideoStore.Video v = rec.keep(best.path, t, onTv);
        log.setVideo(s.id, v.id);
        return v.id;
    }

    /** Stops it if live, deletes its recordings and kept video, and stops counting it. */
    private void takeDown(StreamLog.Session s, String by) {
        Consumer<String> k = kick;
        if (s.live() && k != null) {
            k.accept(s.channel);
        }
        int files = 0;
        Recordings rec = recordings;
        if (rec != null) {
            for (Recordings.Rec r : rec.recordingsOf(s, clock.getAsLong())) {
                if (rec.delete(r.path)) {
                    files++;
                }
            }
        }
        VideoStore vs = videos;
        if (s.videoId != 0 && vs != null) {
            vs.delete(s.videoId);
        }
        log.setRemoved(s.id, true);
        System.out.println("[streams] " + by + " took down stream " + s.id + " of " + s.user + " (" + files + " recording(s)"
                + (s.videoId != 0 ? ", video " + s.videoId : "") + ")");
    }

    private void handleMine(HttpExchange ex) throws IOException {
        try {
            UserStore.Info me = me(ex);
            if (me == null) {
                throw new Bad(401, "sign in first");
            }
            String path = ex.getRequestURI().getPath();
            boolean post = "POST".equalsIgnoreCase(ex.getRequestMethod());
            if (path.equals("/api/me/streams") || path.equals("/api/me/streams/")) {
                json(ex, 200, mineJson(me));
                return;
            }
            if (!post) {
                throw new Bad(405, "use POST");
            }
            String form = body(ex);
            if (path.equals("/api/me/streams/alerts")) {
                synchronized (this) {
                    if ("1".equals(p(form, "on"))) {
                        alertUsers.add(me.username.toLowerCase(Locale.ROOT));
                    } else {
                        alertUsers.remove(me.username.toLowerCase(Locale.ROOT));
                    }
                    saveAlerts();
                }
                json(ex, 200, mineJson(me));
                return;
            }
            if (path.equals("/api/me/streams/delete")) {
                StreamLog.Session s = log.get(num(p(form, "session")));
                if (s == null || !s.user.equalsIgnoreCase(me.username) || s.removed) {
                    throw new Bad(404, "no such stream");
                }
                takeDown(s, me.username);
                json(ex, 200, mineJson(me));
                return;
            }
            throw new Bad(404, "not found");
        } catch (Bad e) {
            json(ex, e.status, "{\"error\":" + q(e.getMessage()) + "}");
        } catch (Exception e) {
            json(ex, 500, "{\"error\":" + q("server error: " + e.getMessage()) + "}");
        } finally {
            ex.close();
        }
    }

    private void handleClaim(HttpExchange ex) throws IOException {
        try {
            if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
                throw new Bad(405, "use POST");
            }
            UserStore.Info me = me(ex);
            if (me == null) {
                throw new Bad(401, "sign in first");
            }
            if (!anonymous(me, me.username)) {
                throw new Bad(409, "you already have your own account");
            }
            EmailService es = emails;
            if (es == null) {
                throw new Bad(503, "registration is not available right now");
            }
            String path = ex.getRequestURI().getPath();
            String form = body(ex);
            String name = p(form, "username");
            try {
                if (path.equals("/api/vstream/claim")) {
                    String masked = es.startSignup(name, MediaAuthHttpServer.queryParam(form, "password"), p(form, "email"));
                    synchronized (this) {
                        claims.put(name.toLowerCase(Locale.ROOT), me.username);
                    }
                    json(ex, 200, "{\"verify\":true,\"username\":" + q(name) + ",\"email\":" + q(masked) + "}");
                    return;
                }
                String tester;
                synchronized (this) {
                    tester = claims.get(name.toLowerCase(Locale.ROOT));
                }
                if (tester == null || !tester.equalsIgnoreCase(me.username)) {
                    throw new Bad(404, "start again: choose your username, password and e-mail");
                }
                if (path.equals("/api/vstream/claim/resend")) {
                    json(ex, 200, "{\"email\":" + q(es.resendSignup(name)) + "}");
                    return;
                }
                if (!path.equals("/api/vstream/claim/verify")) {
                    throw new Bad(404, "not found");
                }
                UserStore.Info made = es.finishSignup(name, p(form, "code"));
                synchronized (this) {
                    claims.remove(name.toLowerCase(Locale.ROOT));
                }
                int moved = moveOver(me.username, made.username);
                String token = sessions.create(made.username, made.role);
                System.out.println("[streams] tester " + me.username + " registered as " + made.username + " (" + moved + " stream(s) moved)");
                json(ex, 200, "{\"token\":" + q(token) + ",\"username\":" + q(made.username) + ",\"role\":" + q(made.role.name())
                        + ",\"streamsMoved\":" + moved + "}");
            } catch (EmailService.EmailException e) {
                throw new Bad(e.status, e.getMessage());
            }
        } catch (Bad e) {
            json(ex, e.status, "{\"error\":" + q(e.getMessage()) + "}");
        } catch (Exception e) {
            json(ex, 500, "{\"error\":" + q("server error: " + e.getMessage()) + "}");
        } finally {
            ex.close();
        }
    }

    /** The tester's streams, recordings and videos now belong to the new account; the tester login is closed. */
    int moveOver(String tester, String to) {
        int n = log.rename(tester, to);
        StreamHistory h = history;
        if (h != null) {
            h.rename(tester, to);
        }
        VideoStore vs = videos;
        if (vs != null) {
            vs.reassign(tester, to);
        }
        sessions.removeUser(tester);
        try {
            users.deleteAccount(tester);
        } catch (UserStore.UserException e) {
            System.out.println("[streams] could not close " + tester + ": " + e.getMessage());
        }
        return n;
    }

    // ---- JSON ----

    private String totalsJson(StreamLog.Totals t) {
        return "{\"user\":" + q(t.user) + ",\"streams\":" + t.streams + ",\"totalMs\":" + t.totalMs + ",\"longestMs\":" + t.longestMs
                + ",\"averageMs\":" + t.averageMs() + ",\"firstStart\":" + t.firstStart + ",\"lastStart\":" + t.lastStart
                + ",\"liveNow\":" + t.liveNow + ",\"points\":" + t.points + ",\"level\":" + q(t.level)
                + ",\"nextLevel\":" + q(t.nextLevel) + ",\"pointsToNext\":" + t.pointsToNext + "}";
    }

    String listJson() {
        StringBuilder sb = new StringBuilder("{\"levels\":[");
        for (int i = 0; i < StreamLog.LEVELS.length; i++) {
            sb.append(i > 0 ? "," : "").append("{\"name\":").append(q(StreamLog.LEVELS[i])).append(",\"points\":").append(StreamLog.LEVEL_POINTS[i]).append('}');
        }
        sb.append("],\"streamers\":[");
        int i = 0;
        for (StreamLog.Totals t : log.allTotals()) {
            UserStore.Info info = users.info(t.user);
            String tj = totalsJson(t);
            sb.append(i++ > 0 ? "," : "").append(tj, 0, tj.length() - 1)
                    .append(",\"anonymous\":").append(anonymous(info, t.user))
                    .append(",\"account\":").append(info != null).append(",\"disabled\":").append(info != null && info.disabled).append('}');
        }
        return sb.append("]}").toString();
    }

    String sessionsJson(List<StreamLog.Session> list, boolean admin) {
        long now = clock.getAsLong();
        Recordings rec = recordings;
        VideoStore vs = videos;
        StringBuilder sb = new StringBuilder("{\"sessions\":[");
        int i = 0;
        for (StreamLog.Session s : list) {
            if (s == null) {
                continue;
            }
            sb.append(i++ > 0 ? "," : "").append("{\"id\":").append(s.id).append(",\"user\":").append(q(s.user))
                    .append(",\"channel\":").append(q(s.channel)).append(",\"title\":").append(q(s.title))
                    .append(",\"start\":").append(s.start).append(",\"end\":").append(s.end)
                    .append(",\"durationMs\":").append(s.durationMs(now)).append(",\"live\":").append(s.live())
                    .append(",\"removed\":").append(s.removed).append(",\"recordings\":[");
            int j = 0;
            if (rec != null && !s.removed) {
                for (Recordings.Rec r : rec.recordingsOf(s, now)) {
                    sb.append(j++ > 0 ? "," : "").append("{\"path\":").append(q(r.path)).append(",\"bytes\":").append(r.bytes)
                            .append(",\"recording\":").append(r.recording)
                            .append(",\"url\":").append(q("/api/streams/file?path=" + enc(r.path))).append('}');
                }
            }
            sb.append(']');
            VideoStore.Video v = s.videoId == 0 || vs == null ? null : vs.get(s.videoId);
            if (v != null) {
                sb.append(",\"video\":{\"id\":").append(v.id).append(",\"title\":").append(q(v.title)).append(",\"status\":").append(q(v.status))
                        .append(",\"onTv\":").append(v.featured).append(",\"paused\":").append(q(v.paused)).append('}');
            } else {
                sb.append(",\"video\":null");
            }
            sb.append('}');
        }
        return sb.append("]}").toString();
    }

    String mineJson(UserStore.Info me) {
        StreamLog.Totals t = log.totals(me.username);
        boolean alerts;
        synchronized (this) {
            alerts = alertUsers.contains(me.username.toLowerCase(Locale.ROOT));
        }
        String s = sessionsJson(log.list(me.username, false, 200), false);
        return "{\"user\":" + q(me.username) + ",\"anonymous\":" + anonymous(me, me.username) + ",\"alerts\":" + alerts
                + ",\"totals\":" + totalsJson(t) + "," + s.substring(1);
    }

    private static String enc(String s) {
        try {
            return java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20");
        } catch (java.io.UnsupportedEncodingException e) {
            return s;
        }
    }

    private static String q(String s) {
        return ProfileApi.quote(s == null ? "" : s);
    }

    private static void json(HttpExchange ex, int status, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(status, b.length);
        ex.getResponseBody().write(b);
    }
}