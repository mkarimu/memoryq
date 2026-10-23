package com.vstream.Vidiserver;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.LongSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The radio on the web.
 *   GET  /radio/ID.mp3                          the station's live sound (any player: app, browser, radio apps)
 *   GET  /api/radio/stations                    the stations with what is playing now
 *   GET  /api/radio/now?station=ID              one station: now playing, up next, what the DJ said
 *   GET  /radio/embed/ID                        a small player to put on any website (iframe)
 *   GET  /radio/ID.m3u                          a playlist file for radio apps
 *   /api/studio/request | pay | paid | trial | billing/portal ...   station owners (sign-up, payment, running it)
 *   /api/admin/radio/status | station/create | station/save | station/delete | tracks | upload | track/save |
 *   track/delete | skip | talk | detect        (super-admin only)
 */
public final class RadioApi {

    private static final int MAX_BODY = 16 * 1024;
    private static final Pattern STREAM = Pattern.compile("^/radio/(\\d{1,9})\\.mp3$");
    private static final Pattern EMBED = Pattern.compile("^/radio/embed/(\\d{1,9})/?$");
    private static final Pattern M3U = Pattern.compile("^/radio/(\\d{1,9})\\.m3u$");

    private final RadioService radio;
    private final UserStore users;
    private final SessionStore sessions;
    private final LongSupplier clock;
    private final Path tmpDir;

    public RadioApi(RadioService radio, UserStore users, SessionStore sessions, LongSupplier clock, Path tmpDir) {
        this.radio = radio;
        this.users = users;
        this.sessions = sessions;
        this.clock = clock;
        this.tmpDir = tmpDir;
        this.djPro = new DjProApi(radio, users, sessions, clock);
        this.importer = new CentovaImport(radio, users, sessions, clock,
                java.nio.file.Paths.get(System.getProperty("vstream.radioImportDir", "radio-import")));
        this.oldLinks = new OldLinks(this, importer, radio, users, sessions,
                java.nio.file.Paths.get(System.getProperty("vstream.radioImportDir", "radio-import")).resolve("old-links.tsv"),
                System.getProperty("vstream.oldLinksIp", "184.75.212.211"));
    }

    /** Old Centova / Azuracast listening addresses that now play Memoryq stations. */
    private final OldLinks oldLinks;

    public OldLinks oldLinks() {
        return oldLinks;
    }

    /** Moving Centova Cast stations to Memoryq (Admin - Radio - Import). */
    private final CentovaImport importer;

    public CentovaImport importer() {
        return importer;
    }

    private final DjProApi djPro;

    /** Live DJ Pro (the browser DJ console). */
    public DjProApi djPro() {
        return djPro;
    }

    /** The channel pool, so the Live DJ Pro camera can go out as a Live Channel. */
    public void setClaims(ClaimStore c) {
        djPro.setClaims(c);
    }

    public void register(HttpServer server) {
        djPro.register(server);
        importer.register(server);
        oldLinks.register(server);
        server.createContext("/radio/", new HttpHandler() {
            @Override
            public void handle(HttpExchange ex) throws IOException {
                handleStream(ex);
            }
        });
        server.createContext("/api/radio/", new HttpHandler() {
            @Override
            public void handle(HttpExchange ex) throws IOException {
                handlePublic(ex);
            }
        });
        server.createContext("/api/admin/radio/", new HttpHandler() {
            @Override
            public void handle(HttpExchange ex) throws IOException {
                handleAdmin(ex);
            }
        });
        server.createContext("/api/studio/", new HttpHandler() {
            @Override
            public void handle(HttpExchange ex) throws IOException {
                handleStudio(ex);
            }
        });
    }

    private static final class Bad extends Exception {
        private static final long serialVersionUID = 1L;
        final int status;

        Bad(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    // ---- Listening ----

    private void handleStream(HttpExchange ex) throws IOException {
        try {
            String rpath = ex.getRequestURI().getPath();
            Matcher em = EMBED.matcher(rpath);
            if (em.matches()) {
                embedPage(ex, Long.parseLong(em.group(1)));
                return;
            }
            Matcher pm = M3U.matcher(rpath);
            if (pm.matches()) {
                RadioStore.Station ps = radio.store().station(Long.parseLong(pm.group(1)));
                if (ps == null) {
                    send(ex, 404, error("no such station"));
                    return;
                }
                byte[] pl = ("#EXTM3U\n#EXTINF:-1," + ps.name + "\nhttps://" + liveHost + "/radio/" + ps.id + ".mp3\n")
                        .getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().set("Content-Type", "audio/x-mpegurl");
                ex.getResponseHeaders().set("Content-Disposition", "inline; filename=\"station-" + ps.id + ".m3u\"");
                ex.sendResponseHeaders(200, pl.length);
                ex.getResponseBody().write(pl);
                return;
            }
            Matcher m = STREAM.matcher(rpath);
            if (!m.matches()) {
                send(ex, 404, error("not found"));
                return;
            }
            if (!"GET".equalsIgnoreCase(ex.getRequestMethod()) && !"HEAD".equalsIgnoreCase(ex.getRequestMethod())) {
                send(ex, 405, error("method not allowed"));
                return;
            }
            serveStation(ex, Long.parseLong(m.group(1)));
        } finally {
            ex.close();
        }
    }

    /**
     * Plays a station's live sound to this listener (also used for old addresses: Centova ports, Azuracast links).
     * Does not close the exchange.
     */
    void serveStation(HttpExchange ex, long id) throws IOException {
        {
            if (!"GET".equalsIgnoreCase(ex.getRequestMethod()) && !"HEAD".equalsIgnoreCase(ex.getRequestMethod())) {
                send(ex, 405, error("method not allowed"));
                return;
            }
            RadioStore.Station st = radio.store().station(id);
            RadioEngine.Broadcast cast = radio.broadcast(id);
            if (st == null || cast == null) {
                send(ex, 503, error(st == null ? "no such station" : "this station is off air"));
                return;
            }
            RadioBilling.Plan plan = planOf(st);
            if (plan != null && plan.transferGb > 0 && monthBytes(st) >= plan.transferGb * GB) {
                radio.logEvent(id, "limit", "A listener was turned away: this month's listening data is used up");
                send(ex, 503, error(st.name + " has used its listening data for this month"));
                return;
            }
            if (plan != null && plan.listeners > 0 && radio.listeners(id) >= plan.listeners) {
                radio.logEvent(id, "limit", "A listener was turned away: the station is full (" + plan.listeners + " listeners)");
                send(ex, 503, error(st.name + " is full right now (" + plan.listeners + " listeners) - please try again soon"));
                return;
            }
            ex.getResponseHeaders().set("Content-Type", "audio/mpeg");
            ex.getResponseHeaders().set("Cache-Control", "no-cache, no-store");
            ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
            ex.getResponseHeaders().set("icy-name", st.name);
            ex.getResponseHeaders().set("icy-description", st.description);
            if ("HEAD".equalsIgnoreCase(ex.getRequestMethod())) {
                ex.sendResponseHeaders(200, -1);
                return;
            }
            ex.sendResponseHeaders(200, 0);
            if (radio.stats() != null) {
                radio.stats().listener(id, clientAddress(ex));
            }
            final java.util.concurrent.atomic.AtomicLong counter = sentCounter(id);
            RadioListenLog ll = radio.listenLog();
            final RadioListenLog.Open session = ll == null ? null : ll.open(id, clientAddress(ex), ex.getRequestHeaders().getFirst("User-Agent"));
            final java.util.concurrent.atomic.AtomicLong mine = session == null ? new java.util.concurrent.atomic.AtomicLong() : session.bytes;
            final OutputStream body = ex.getResponseBody();
            OutputStream os = new java.io.FilterOutputStream(body) {
                @Override
                public void write(byte[] b, int off, int len) throws IOException {
                    body.write(b, off, len);
                    counter.addAndGet(len);
                    mine.addAndGet(len);
                }

                @Override
                public void write(int b) throws IOException {
                    body.write(b);
                    counter.incrementAndGet();
                    mine.incrementAndGet();
                }
            };
            try {
                cast.serve(os);
            } catch (IOException e) {
                // the listener went away
            } finally {
                if (ll != null) {
                    ll.close(session);
                }
            }
        }
    }

    // ---- Public information ----

    private void handlePublic(HttpExchange ex) throws IOException {
        try {
            String path = ex.getRequestURI().getPath();
            String query = ex.getRequestURI().getRawQuery();
            boolean post = "POST".equalsIgnoreCase(ex.getRequestMethod());
            if (!post && !"GET".equalsIgnoreCase(ex.getRequestMethod())) {
                send(ex, 405, error("method not allowed"));
                return;
            }
            UserStore.Info viewer = signedIn(ex);
            RadioStore store = radio.store();
            switch (path) {
                case "/api/radio/songs": {
                    RadioStore.Station s = store.station(id(MediaAuthHttpServer.queryParam(query, "station")));
                    if (s == null || !s.onAir()) {
                        throw new Bad(404, "no such station");
                    }
                    StringBuilder sb = new StringBuilder("{\"songs\":[");
                    List<RadioStore.Track> list = store.tracks(s.id);
                    list.sort((a, b) -> a.label().compareToIgnoreCase(b.label()));
                    for (int i = 0; i < list.size(); i++) {
                        RadioStore.Track t = list.get(i);
                        if (i > 0) {
                            sb.append(',');
                        }
                        sb.append("{\"id\":").append(t.id).append(",\"title\":").append(q(t.title))
                                .append(",\"artist\":").append(q(t.artist)).append(",\"likes\":").append(t.likes)
                                .append(",\"hasLink\":").append(!t.link.isEmpty()).append('}');
                    }
                    send(ex, 200, sb.append("]}").toString());
                    break;
                }
                case "/api/radio/like": {
                    requirePost(post);
                    if (viewer == null) {
                        throw new Bad(401, "sign in to like songs");
                    }
                    String body = readBody(ex, MAX_BODY);
                    long track = id(MediaAuthHttpServer.queryParam(body, "track"));
                    boolean like = !"false".equals(MediaAuthHttpServer.queryParam(body, "like"));
                    boolean had = store.likes(track, viewer.username);
                    int n = store.like(track, viewer.username, like);
                    store.flushIfDirty();
                    RadioStore.Track lt = store.track(track);
                    if (like && !had && lt != null && radio.stats() != null) {
                        radio.stats().liked(lt.station);
                    }
                    send(ex, 200, "{\"likes\":" + n + ",\"liked\":" + store.likes(track, viewer.username) + "}");
                    break;
                }
                case "/api/radio/tap": {
                    requirePost(post);
                    long tapped = id(MediaAuthHttpServer.queryParam(readBody(ex, MAX_BODY), "track"));
                    String link = store.tap(tapped);
                    RadioStore.Track tt = store.track(tapped);
                    if (tt != null && !link.isEmpty() && radio.stats() != null) {
                        radio.stats().tapped(tt.station);
                    }
                    send(ex, 200, "{\"link\":" + q(link) + "}");
                    break;
                }
                case "/api/radio/request": {
                    requirePost(post);
                    if (viewer == null) {
                        throw new Bad(401, "sign in to request songs");
                    }
                    String body = readBody(ex, MAX_BODY);
                    long station = id(MediaAuthHttpServer.queryParam(body, "station"));
                    long track = id(MediaAuthHttpServer.queryParam(body, "track"));
                    int place = radio.request(station, track, viewer.username, radio.displayName(viewer.username));
                    store.flushIfDirty();
                    if (radio.stats() != null) {
                        radio.stats().requested(station);
                    }
                    RadioEngine eng = radio.engine(station);
                    if (eng != null) {
                        eng.requestArrived();
                        if (eng.upNextRequestedBy().isEmpty()) {
                            place = Math.max(1, place);
                        } else {
                            place = place + 1;
                        }
                    }
                    send(ex, 200, "{\"place\":" + place + "}");
                    break;
                }
                case "/api/radio/stations": {
                    StringBuilder sb = new StringBuilder("{\"stations\":[");
                    boolean first = true;
                    DemoRadio dm = demo;
                    for (RadioStore.Station s : radio.store().stations()) {
                        if (!s.onAir() || (dm != null && dm.hiddenFromList(s.id))) {
                            continue;
                        }
                        if (!first) {
                            sb.append(',');
                        }
                        first = false;
                        sb.append(stationJson(s, false, viewer));
                    }
                    send(ex, 200, sb.append("]}").toString());
                    break;
                }
                case "/api/radio/now": {
                    RadioStore.Station s = radio.store().station(id(MediaAuthHttpServer.queryParam(query, "station")));
                    if (s == null || !s.onAir()) {
                        throw new Bad(404, "no such station");
                    }
                    send(ex, 200, stationJson(s, true, viewer));
                    break;
                }
                default:
                    send(ex, 404, error("not found"));
            }
        } catch (Bad e) {
            send(ex, e.status, error(e.getMessage()));
        } catch (IllegalArgumentException e) {
            send(ex, 400, error(e.getMessage()));
        } catch (Exception e) {
            send(ex, 500, error("server error"));
        } finally {
            ex.close();
        }
    }

    private String stationJson(RadioStore.Station s, boolean withLog) {
        return stationJson(s, withLog, null);
    }

    private String stationJson(RadioStore.Station s, boolean withLog, UserStore.Info viewer) {
        RadioEngine e = radio.engine(s.id);
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"id\":").append(s.id);
        sb.append(",\"name\":").append(q(s.name));
        sb.append(",\"description\":").append(q(s.description));
        sb.append(",\"djName\":").append(q(s.djName));
        sb.append(",\"live\":").append(e != null && e.running());
        sb.append(",\"liveDj\":").append(e != null && e.liveOnAir());
        if (e != null && e.liveOnAir()) {
            // who is live, and the Live Channel showing the set (Live DJ Pro with video)
            String who = djPro.liveName(s.id);
            if (who == null) {
                LiveInput door = radio.liveDoor(s.id);
                who = door == null ? "" : door.djName();
            }
            sb.append(",\"liveName\":").append(q(who));
            String video = djPro.liveVideo(s.id);
            sb.append(",\"video\":").append(video == null ? "null" : q(video));
        }
        sb.append(",\"listeners\":").append(radio.listeners(s.id));
        sb.append(",\"songs\":").append(radio.store().trackCount(s.id));
        sb.append(",\"stream\":").append(q("/radio/" + s.id + ".mp3"));
        sb.append(",\"show\":").append(q(radio.scheduler().currentShow(s.id)));
        RadioEngine.Event song = e == null ? null : e.nowSong();
        if (song != null) {
            RadioStore.Track t = radio.store().track(song.trackId);
            sb.append(",\"now\":{\"title\":").append(q(t == null ? song.text : t.title))
                    .append(",\"artist\":").append(q(t == null ? "" : t.artist))
                    .append(",\"label\":").append(q(song.text))
                    .append(",\"startedAt\":").append(song.time)
                    .append(",\"durationMs\":").append(song.durationMs)
                    .append(",\"trackId\":").append(song.trackId)
                    .append(",\"hasLink\":").append(t != null && !t.link.isEmpty())
                    .append(",\"likes\":").append(t == null ? 0 : t.likes)
                    .append(",\"liked\":").append(t != null && viewer != null && radio.store().likes(t.id, viewer.username))
                    .append('}');
        } else {
            sb.append(",\"now\":null");
        }
        RadioEngine.Event talk = e == null ? null : e.nowTalk();
        sb.append(",\"talk\":").append(talk == null ? "null"
                : "{\"kind\":" + q(talk.kind) + ",\"text\":" + q(talk.text) + ",\"time\":" + talk.time + "}");
        RadioStore.Track next = e == null ? null : e.upNext();
        sb.append(",\"next\":").append(next == null ? "null" : q(next.label()));
        String by = e == null ? "" : e.upNextRequestedBy();
        sb.append(",\"nextRequestedBy\":").append(q(next == null ? "" : by));
        List<RadioWriter.Request> waiting = radio.requests(s.id);
        sb.append(",\"requests\":[");
        for (int i = 0; i < waiting.size(); i++) {
            RadioStore.Track rt = radio.store().track(waiting.get(i).trackId);
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"song\":").append(q(rt == null ? "" : rt.label())).append(",\"by\":").append(q(waiting.get(i).name))
                    .append('}');
        }
        sb.append(']');
        if (withLog) {
            sb.append(",\"recent\":[");
            List<RadioEngine.Event> events = e == null ? java.util.Collections.<RadioEngine.Event>emptyList() : e.recent(20);
            for (int i = 0; i < events.size(); i++) {
                RadioEngine.Event ev = events.get(i);
                if (i > 0) {
                    sb.append(',');
                }
                sb.append("{\"kind\":").append(q(ev.kind)).append(",\"text\":").append(q(ev.text))
                        .append(",\"time\":").append(ev.time).append('}');
            }
            sb.append(']');
        }
        return sb.append('}').toString();
    }

    // ---- Station management: the admin (everything) and station owners (their own stations) ----

    /** Operations a station owner may use on their own stations (the admin may use all). */
    private static final java.util.Set<String> OWNER_OPS = new java.util.HashSet<String>(java.util.Arrays.asList(
            "status", "station/save", "tracks", "upload", "track/save", "track/delete", "testvoice", "skip", "talk",
            "live/password", "live/kick", "stats", "request", "pay", "paid", "trial", "billing/portal", "dj/save", "dj/delete", "plan/change",
            "playlists", "playlist/save", "playlist/delete", "playlist/enable", "stories", "story/add", "story/update", "story/delete", "report", "report.csv", "listeners", "logs", "restart"));
    /** Station staff: a co-admin runs the whole Studio except paying / plans; a presenter does the programme. */
    private static final java.util.Set<String> COADMIN_NOT = new java.util.HashSet<String>(java.util.Arrays.asList(
            "request", "pay", "paid", "trial", "billing/portal", "plan/change"));
    private static final java.util.Set<String> PRESENTER_OPS = new java.util.HashSet<String>(java.util.Arrays.asList(
            "status", "tracks", "upload", "track/save", "testvoice", "skip", "talk", "stats", "listeners",
            "playlists", "playlist/save", "playlist/enable", "stories", "story/add", "story/update"));
    /** The Studio operation being handled (for the staff checks in manageable). */
    private final ThreadLocal<String> currentOp = new ThreadLocal<String>();
    private static final int MAX_OWNED = Integer.getInteger("vstream.maxStationsPerOwner", 3);

    private volatile String liveHost = "memoryq.com";
    private volatile Mailer mailer;
    private volatile java.util.function.Function<String, String> emailOf;
    private volatile String adminEmail = "";

    private volatile RadioBilling billing;
    /** The Studio's try-it station (shared public login), or null. */
    private volatile DemoRadio demo;

    public void setDemo(DemoRadio d) {
        this.demo = d;
    }

    private boolean trialLogin(UserStore.Info me) {
        DemoRadio d = demo;
        return d != null && me != null && me.role != UserStore.Role.ADMIN && d.isDemoUser(me.username);
    }
    private volatile EmailService emails;

    /** Prices, the free trial and Stripe. */
    public void setBilling(RadioBilling b) {
        this.billing = b;
        radio.setPlans(b == null ? null : b::plan);
        djPro.setBilling(b);
    }

    private RadioBilling.Plan planOf(RadioStore.Station s) {
        RadioBilling b = billing;
        return RadioBilling.Plan.effective(s == null || b == null || s.planId == 0 ? null : b.plan(s.planId), s);
    }

    private static final long MB = 1024L * 1024;
    private static final long GB = 1024L * MB;

    private static String mb(long bytes) {
        return bytes >= GB ? String.format(java.util.Locale.ROOT, "%.1f GB", bytes / (double) GB)
                : String.format(java.util.Locale.ROOT, "%.0f MB", bytes / (double) MB);
    }

    /** Listening data not yet added to the station's month total (added every minute). */
    private final java.util.concurrent.ConcurrentHashMap<Long, java.util.concurrent.atomic.AtomicLong> sent =
            new java.util.concurrent.ConcurrentHashMap<Long, java.util.concurrent.atomic.AtomicLong>();

    private java.util.concurrent.atomic.AtomicLong sentCounter(long id) {
        return sent.computeIfAbsent(id, k -> new java.util.concurrent.atomic.AtomicLong());
    }

    /** This month's data so far (saved total + not yet saved). */
    private long monthBytes(RadioStore.Station s) {
        java.util.concurrent.atomic.AtomicLong c = sent.get(s.id);
        long pending = c == null ? 0 : c.get();
        return (month().equals(s.monthKey) ? s.monthBytes : 0) + pending;
    }

    private String month() {
        return java.time.Instant.ofEpochMilli(clock.getAsLong()).atZone(java.time.ZoneOffset.UTC).toString().substring(0, 7);
    }

    /** Every minute: adds listening data to each station's month, and warns at 80% / says when it is used up. */
    void usageTick() {
        RadioStore store = radio.store();
        String m = month();
        boolean changed = false;
        for (java.util.Map.Entry<Long, java.util.concurrent.atomic.AtomicLong> e : sent.entrySet()) {
            long n = e.getValue().getAndSet(0);
            RadioStore.Station s = store.station(e.getKey());
            if (s == null) {
                continue;
            }
            long total = store.addTransfer(s.id, n, m);
            changed |= n > 0;
            RadioBilling.Plan plan = planOf(s);
            if (plan == null || plan.transferGb <= 0) {
                continue;
            }
            long limit = plan.transferGb * GB;
            if (total >= limit && !s.xferWarned.equals(m + ":100")) {
                store.setXferWarned(s.id, m + ":100");
                changed = true;
                mailOwner(s.owner, s.name + " has used its listening data for " + m, s.name + " has used all "
                        + plan.transferGb + " GB of listening data in its " + plan.name + " plan this month, so new listeners "
                        + "cannot tune in until the 1st of next month. Move to a bigger plan in the studio (Billing) to "
                        + "continue straight away.");
            } else if (total >= limit * 8 / 10 && total < limit && !s.xferWarned.startsWith(m + ":")) {
                store.setXferWarned(s.id, m + ":80");
                changed = true;
                mailOwner(s.owner, s.name + ": 80% of this month's listening data used", s.name + " has used "
                        + mb(total) + " of its " + plan.transferGb + " GB this month. At 100% new listeners cannot tune in "
                        + "until next month - a bigger plan (studio - Billing) avoids that.");
            }
        }
        if (changed) {
            store.flushIfDirty();
        }
    }

    /** Lets the admin give a new station account its email address. */
    public void setEmails(EmailService e) {
        this.emails = e;
        importer.setEmails(e);
    }

    /** The address DJs connect to (shown in the station portal). */
    public void setLiveHost(String host) {
        if (host != null && !host.trim().isEmpty()) {
            this.liveHost = host.trim();
            importer.setLiveHost(host);
        }
    }

    /** Lets the radio email the admin about station requests and owners about their station's status. */
    public void setMail(Mailer m, java.util.function.Function<String, String> emailOfUser, String adminAddress) {
        this.mailer = m;
        importer.setMailer(m);
        this.emailOf = emailOfUser;
        this.adminEmail = adminAddress == null ? "" : adminAddress;
    }

    private void mail(String to, String subject, String text) {
        final Mailer m = mailer;
        if (m == null || to == null || to.isEmpty()) {
            return;
        }
        Thread t = new Thread(() -> m.send(to, subject, text), "radio-mail");
        t.setDaemon(true);
        t.start();
    }

    private void mailOwner(String owner, String subject, String text) {
        java.util.function.Function<String, String> f = emailOf;
        if (f != null && owner != null && !owner.isEmpty()) {
            mail(f.apply(owner), subject, "Hi " + owner + ",\n\n" + text + "\n\nManage your station: https://"
                    + liveHost + "/studio\n\nMemoryq\n");
        }
    }

    private void handleAdmin(HttpExchange ex) throws IOException {
        handleManage(ex, "/api/admin/radio/");
    }

    private void handleStudio(HttpExchange ex) throws IOException {
        handleManage(ex, "/api/studio/");
    }

    private void handleManage(HttpExchange ex, String prefix) throws IOException {
        try {
            UserStore.Info me = signedIn(ex);
            if (me == null) {
                throw new Bad(401, "sign in required");
            }
            boolean admin = me.role == UserStore.Role.ADMIN;
            String path = ex.getRequestURI().getPath();
            String op = path.substring(prefix.length());
            if (!admin && (prefix.startsWith("/api/admin/") || !OWNER_OPS.contains(op))) {
                throw new Bad(403, "admin only");
            }
            if (!admin && me.role == UserStore.Role.GUEST) {
                throw new Bad(403, "create a registered account to run a radio station");
            }
            if (trialLogin(me) && DemoRadio.BLOCKED_STUDIO.contains(op)) {
                throw new Bad(403, "Not in the trial - create your own station to use this.");
            }
            String query = ex.getRequestURI().getRawQuery();
            boolean post = "POST".equalsIgnoreCase(ex.getRequestMethod());
            RadioStore store = radio.store();
            currentOp.set(op);
            switch (op) {
                case "staff": {
                    long sid = 0;
                    String sp = MediaAuthHttpServer.queryParam(query, "station");
                    if (sp != null && !sp.isEmpty()) {
                        sid = id(sp);
                    }
                    send(ex, 200, staffJson(store.staffOf(sid)));
                    break;
                }
                case "staff/save": {
                    requirePost(post);
                    String body = readBody(ex, MAX_BODY);
                    RadioStore.Station s = store.station(id(MediaAuthHttpServer.queryParam(body, "station")));
                    if (s == null) {
                        throw new Bad(404, "no such station");
                    }
                    String user = trim(MediaAuthHttpServer.queryParam(body, "user"));
                    String role = trim(MediaAuthHttpServer.queryParam(body, "role"));
                    if (role.isEmpty()) {
                        role = RadioStore.ROLE_PRESENTER;
                    }
                    if (!RadioStore.validRole(role)) {
                        throw new Bad(400, "the role is coadmin or presenter");
                    }
                    UserStore.Info u = user.isEmpty() ? null : users.info(user);
                    if (u == null) {
                        throw new Bad(404, "there is no member called " + user + " - they sign up on memoryq.com first");
                    }
                    if (u.role == UserStore.Role.ADMIN) {
                        throw new Bad(400, u.username + " is a Memoryq admin - admins can open every station already");
                    }
                    if (u.role == UserStore.Role.GUEST) {
                        throw new Bad(400, u.username + " is a guest account - they need a registered account");
                    }
                    if (u.disabled) {
                        throw new Bad(400, u.username + "'s account is switched off");
                    }
                    if (u.username.equalsIgnoreCase(s.owner)) {
                        throw new Bad(400, u.username + " owns " + s.name + " already");
                    }
                    DemoRadio d = demo;
                    if (d != null && d.isDemoUser(u.username)) {
                        throw new Bad(400, "that is the public trial login");
                    }
                    boolean isNew = store.staffRole(s.id, u.username) == null;
                    store.setStaff(s.id, u.username, role, clock.getAsLong());
                    store.flushIfDirty();
                    String label = RadioStore.ROLE_COADMIN.equals(role) ? "co-admin" : "presenter";
                    radio.logEvent(s.id, "admin", u.username + " is now " + label + " of the station (given by the Memoryq admin)");
                    System.out.println("[radio] admin " + me.username + " made " + u.username + " " + label + " of " + s.name);
                    if (isNew) {
                        mailOwner(u.username, "You can now work on " + s.name, "Memoryq has made you " + label + " of the radio station "
                                + s.name + ".\nSign in at https://" + liveHost + "/studio with your own Memoryq username and password"
                                + " and pick " + s.name + ".");
                    }
                    send(ex, 200, staffJson(store.staffOf(s.id)));
                    break;
                }
                case "staff/delete": {
                    requirePost(post);
                    String body = readBody(ex, MAX_BODY);
                    long sid = id(MediaAuthHttpServer.queryParam(body, "station"));
                    String user = trim(MediaAuthHttpServer.queryParam(body, "user"));
                    if (!store.removeStaff(sid, user)) {
                        throw new Bad(404, user + " is not on that station's staff");
                    }
                    store.flushIfDirty();
                    radio.logEvent(sid, "admin", user + " was taken off the station's staff by the Memoryq admin");
                    send(ex, 200, staffJson(store.staffOf(sid)));
                    break;
                }
                case "status":
                    send(ex, 200, statusJson(me, admin));
                    break;
                case "detect":
                    requirePost(post);
                    radio.tools().detect();
                    radio.refresh();
                    send(ex, 200, statusJson(me, true));
                    break;
                case "station/create": {
                    requirePost(post);
                    String body = readBody(ex, MAX_BODY);
                    String owner = trim(MediaAuthHttpServer.queryParam(body, "loginUser"));
                    if (owner.isEmpty()) {
                        owner = trim(MediaAuthHttpServer.queryParam(body, "owner"));
                    }
                    String loginPass = MediaAuthHttpServer.queryParam(body, "loginPassword");
                    String email = trim(MediaAuthHttpServer.queryParam(body, "email"));
                    UserStore.Info existing = owner.isEmpty() ? null : users.info(owner);
                    boolean newAccount = !owner.isEmpty() && existing == null;
                    if (existing != null && existing.role == UserStore.Role.ADMIN) {
                        throw new Bad(400, "choose another login user - " + owner + " is an admin account");
                    }
                    if (newAccount) {
                        if (loginPass == null || loginPass.isEmpty()) {
                            throw new Bad(400, "give the login user a password (there is no account called " + owner + " yet)");
                        }
                        try {
                            UserStore.checkNew(owner, loginPass);
                        } catch (UserStore.UserException e) {
                            throw new Bad(400, "login user: " + e.getMessage());
                        }
                    }
                    if (!email.isEmpty() && !Mailer.validAddress(email.toLowerCase(java.util.Locale.ROOT))) {
                        throw new Bad(400, "enter a valid email address (or leave it empty)");
                    }
                    String bill = trim(MediaAuthHttpServer.queryParam(body, "billing"));
                    if (bill.isEmpty()) {
                        bill = RadioStore.BILL_PAID;
                    }
                    if (!RadioStore.validBilling(bill)) {
                        throw new Bad(400, "payment must be paid, trial or unpaid");
                    }
                    RadioStore.Station spec = new RadioStore.Station();
                    spec.name = MediaAuthHttpServer.queryParam(body, "name");
                    spec.description = MediaAuthHttpServer.queryParam(body, "description");
                    spec.website = MediaAuthHttpServer.queryParam(body, "website");
                    spec.livePort = portParam(MediaAuthHttpServer.queryParam(body, "livePort"));
                    spec.livePass = MediaAuthHttpServer.queryParam(body, "livePass");
                    spec.billing = bill;
                    spec.paidUntil = dateParam(MediaAuthHttpServer.queryParam(body, "until"));
                    String status = RadioStore.BILL_UNPAID.equals(bill) ? RadioStore.STATUS_PENDING : RadioStore.STATUS_ACTIVE;
                    int createPlan = intParam(MediaAuthHttpServer.queryParam(body, "plan"));
                    if (createPlan != 0 && (billing == null || billing.plan(createPlan) == null)) {
                        throw new Bad(400, "no such plan");
                    }
                    RadioStore.Station s = store.createStation(spec, owner, status, clock.getAsLong());
                    if (createPlan != 0) {
                        s = store.setPlan(s.id, createPlan);
                        store.flushIfDirty();
                    }
                    if (newAccount) {
                        try {
                            users.createUser(owner, loginPass, UserStore.Role.CHATTER);
                        } catch (UserStore.UserException e) {
                            store.deleteStation(s.id);
                            store.flushIfDirty();
                            throw new Bad(400, "login user: " + e.getMessage());
                        }
                    }
                    String note = "";
                    EmailService es = emails;
                    if (!owner.isEmpty() && !email.isEmpty() && es != null) {
                        try {
                            es.adminSet(owner, email);
                        } catch (EmailService.EmailException e) {
                            note = "the email was not saved: " + e.getMessage();
                        }
                    }
                    radio.refresh();
                    System.out.println("[radio] admin created station " + s.name + (owner.isEmpty() ? "" : " for " + owner)
                            + (newAccount ? " (new account)" : ""));
                    send(ex, 200, "{\"id\":" + s.id + ",\"owner\":" + q(owner) + ",\"newAccount\":" + newAccount
                            + ",\"livePort\":" + s.livePort + ",\"livePass\":" + q(s.livePass) + ",\"note\":" + q(note) + "}");
                    break;
                }
                case "request": {
                    requirePost(post);
                    String body = readBody(ex, MAX_BODY);
                    List<RadioStore.Station> mine = store.ownedBy(me.username);
                    if (!admin && mine.size() >= MAX_OWNED) {
                        throw new Bad(409, "you already have " + mine.size() + " stations - the most for one account");
                    }
                    for (RadioStore.Station o : mine) {
                        if (!admin && RadioStore.BILL_UNPAID.equals(o.billing) && RadioStore.STATUS_PENDING.equals(o.status)) {
                            throw new Bad(409, "finish setting up " + o.name + " first (payment)");
                        }
                    }
                    RadioStore.Station spec = new RadioStore.Station();
                    spec.name = MediaAuthHttpServer.queryParam(body, "name");
                    spec.description = MediaAuthHttpServer.queryParam(body, "description");
                    spec.website = MediaAuthHttpServer.queryParam(body, "website");
                    spec.livePort = portParam(MediaAuthHttpServer.queryParam(body, "livePort"));
                    spec.livePass = MediaAuthHttpServer.queryParam(body, "livePass");
                    spec.billing = RadioStore.BILL_UNPAID;
                    int wantPlan = intParam(MediaAuthHttpServer.queryParam(body, "plan"));
                    RadioBilling.Plan chosen = null;
                    if (wantPlan > 0) {
                        chosen = billing == null ? null : billing.plan(wantPlan);
                        if (chosen == null || (!chosen.signup && !admin)) {
                            throw new Bad(400, "that plan is not available");
                        }
                    } else if (billing != null) {
                        chosen = billing.defaultPlan();
                    }
                    RadioStore.Station s = store.createStation(spec, me.username, RadioStore.STATUS_PENDING, clock.getAsLong());
                    if (chosen != null) {
                        s = store.setPlan(s.id, chosen.id);
                        store.flushIfDirty();
                    }
                    System.out.println("[radio] " + me.username + " signed up for a station: " + s.name);
                    mail(adminEmail, "New radio station sign-up: " + s.name, me.username + " signed up for a radio station.\n\n"
                            + "Name: " + s.name + "\nSlogan: " + s.description + "\nWebsite: " + s.website
                            + "\nPlan: " + (chosen == null ? "-" : chosen.name)
                            + "\nLive port: " + s.livePort + "\n\nIt goes on air when it is paid for (or when you activate it in "
                            + "https://" + liveHost + "/admin - Radio - Stations).\n");
                    send(ex, 200, "{\"id\":" + s.id + ",\"status\":" + q(s.status) + ",\"billing\":" + q(s.billing) + "}");
                    break;
                }
                case "pay": {
                    requirePost(post);
                    String body = readBody(ex, MAX_BODY);
                    RadioStore.Station s = manageable(me, admin, id(MediaAuthHttpServer.queryParam(body, "station")));
                    RadioBilling b = billing;
                    if (b == null || !b.stripeReady()) {
                        throw new Bad(409, "online payment is not set up yet - Memoryq will contact you, or email " + adminEmail);
                    }
                    String period = MediaAuthHttpServer.queryParam(body, "period");
                    if (period == null) {
                        period = MediaAuthHttpServer.queryParam(body, "plan"); // older pages sent plan=month|year
                    }
                    boolean yearly = "year".equals(period);
                    int planId = intParam(MediaAuthHttpServer.queryParam(body, "planId"));
                    RadioBilling.Plan plan = b.plan(planId > 0 ? planId : s.planId);
                    if (plan == null) {
                        plan = b.defaultPlan();
                    }
                    if (plan == null || (!plan.signup && plan.id != s.planId && !admin)) {
                        throw new Bad(400, "choose a plan first");
                    }
                    if (!s.stripeSub.isEmpty() && RadioStore.BILL_PAID.equals(s.billing)) {
                        throw new Bad(409, "this station already has a card subscription - change or cancel it with "
                                + "\"Change card, see receipts or cancel\" first");
                    }
                    String base = "https://" + liveHost + "/studio";
                    java.util.function.Function<String, String> f = emailOf;
                    try {
                        java.util.Map<String, Object> cs = b.checkout(s.id, s.name, f == null ? null : f.apply(s.owner),
                                s.stripeCustomer, plan, yearly, base + "?paid={CHECKOUT_SESSION_ID}&station=" + s.id,
                                base + "?station=" + s.id + "&cancelled=1");
                        send(ex, 200, "{\"url\":" + q(MiniJson.str(cs, "url")) + "}");
                    } catch (RadioBilling.StripeException e) {
                        throw new Bad(502, "payment page: " + e.getMessage());
                    }
                    break;
                }
                case "paid": {
                    requirePost(post);
                    String body = readBody(ex, MAX_BODY);
                    RadioStore.Station s = manageable(me, admin, id(MediaAuthHttpServer.queryParam(body, "station")));
                    RadioBilling b = billing;
                    if (b == null) {
                        throw new Bad(409, "online payment is not set up");
                    }
                    java.util.Map<String, Object> cs;
                    try {
                        cs = b.checkoutSession(MediaAuthHttpServer.queryParam(body, "session"));
                    } catch (RadioBilling.StripeException e) {
                        throw new Bad(502, e.getMessage());
                    }
                    if (!String.valueOf(s.id).equals(MiniJson.str(cs, "metadata", "station"))) {
                        throw new Bad(400, "that payment is for another station");
                    }
                    String ps = MiniJson.str(cs, "payment_status");
                    if (!"complete".equals(MiniJson.str(cs, "status")) || !("paid".equals(ps) || "no_payment_required".equals(ps))) {
                        throw new Bad(402, "the payment is not finished yet - if you paid, wait a minute and reload this page");
                    }
                    Object sub = MiniJson.at(cs, "subscription");
                    String subId = sub instanceof java.util.Map ? MiniJson.str(sub, "id") : MiniJson.str(cs, "subscription");
                    long until = sub instanceof java.util.Map ? RadioBilling.periodEnd(sub) : 0;
                    String interval = sub instanceof java.util.Map
                            ? MiniJson.str(sub, "items", "data", 0, "price", "recurring", "interval") : "";
                    if (until <= 0) {
                        until = clock.getAsLong() + ("year".equals(interval) ? 365L : 31L) * 24 * 3600 * 1000;
                    }
                    boolean fresh = !subId.equals(s.stripeSub) || RadioStore.BILL_UNPAID.equals(s.billing);
                    RadioStore.Station after = store.setStripe(s.id, MiniJson.str(cs, "customer"), subId, until, interval);
                    int paidPlan = intParam(MiniJson.str(cs, "metadata", "plan"));
                    if (paidPlan > 0 && b.plan(paidPlan) != null) {
                        after = store.setPlan(s.id, paidPlan);
                    }
                    store.flushIfDirty();
                    radio.refresh();
                    if (fresh) {
                        System.out.println("[billing] " + s.name + " paid online until " + new java.util.Date(until));
                        mailOwner(s.owner, "Payment received - " + s.name + " is on air",
                                "Thank you! Your payment for " + s.name + " came through. The station is paid until "
                                        + day(until) + " and renews by itself (you can change or cancel it in the studio).");
                        mail(adminEmail, "Radio payment: " + s.name, s.owner + " paid for " + s.name + " (" + interval
                                + "), paid until " + day(until) + ".\n");
                    }
                    send(ex, 200, "{\"ok\":true,\"paidUntil\":" + after.paidUntil + "}");
                    break;
                }
                case "trial": {
                    requirePost(post);
                    RadioStore.Station s = manageable(me, admin, id(MediaAuthHttpServer.queryParam(readBody(ex, MAX_BODY), "station")));
                    RadioBilling b = billing;
                    int days = b == null ? 0 : b.trialDays();
                    if (days <= 0) {
                        throw new Bad(409, "there is no free trial at the moment");
                    }
                    if (s.trialUsed || !RadioStore.BILL_UNPAID.equals(s.billing)) {
                        throw new Bad(409, "this station has already had its free trial");
                    }
                    long until = clock.getAsLong() + days * 24L * 3600 * 1000;
                    store.setBilling(s.id, RadioStore.BILL_TRIAL, until, "");
                    if (RadioStore.STATUS_PENDING.equals(s.status)) {
                        store.setAdminFields(s.id, null, RadioStore.STATUS_ACTIVE, 0);
                    }
                    store.flushIfDirty();
                    radio.refresh();
                    mail(adminEmail, "Radio trial started: " + s.name, s.owner + " started a " + days + "-day free trial of "
                            + s.name + " (ends " + day(until) + ").\n");
                    send(ex, 200, "{\"ok\":true,\"paidUntil\":" + until + "}");
                    break;
                }
                case "billing/portal": {
                    requirePost(post);
                    RadioStore.Station s = manageable(me, admin, id(MediaAuthHttpServer.queryParam(readBody(ex, MAX_BODY), "station")));
                    RadioBilling b = billing;
                    if (b == null || s.stripeCustomer.isEmpty()) {
                        throw new Bad(409, "this station is not paid online - there is nothing to manage");
                    }
                    try {
                        send(ex, 200, "{\"url\":" + q(b.portal(s.stripeCustomer, "https://" + liveHost + "/studio?station=" + s.id)) + "}");
                    } catch (RadioBilling.StripeException e) {
                        throw new Bad(502, e.getMessage());
                    }
                    break;
                }
                case "billing": {
                    send(ex, 200, billingJson(true));
                    break;
                }
                case "playlists": {
                    RadioStore.Station s = manageable(me, admin, id(MediaAuthHttpServer.queryParam(query, "station")));
                    RadioStore.Playlist onNow = radio.scheduler().showOnNow(s.id);
                    StringBuilder sb = new StringBuilder("{\"station\":").append(s.id)
                            .append(",\"noRepeatMin\":").append(s.noRepeatMin)
                            .append(",\"tz\":").append(q(s.timezone))
                            .append(",\"showNow\":").append(q(onNow == null ? "" : onNow.name))
                            .append(",\"playingNow\":").append(q(radio.scheduler().currentPlaylist(s.id)))
                            .append(",\"aiOn\":").append(s.aiOn)
                            .append(",\"playlists\":[");
                    List<RadioStore.Playlist> pls = store.playlists(s.id);
                    for (int i = 0; i < pls.size(); i++) {
                        RadioStore.Playlist p = pls.get(i);
                        if (i > 0) {
                            sb.append(',');
                        }
                        sb.append("{\"id\":").append(p.id).append(",\"name\":").append(q(p.name)).append(",\"type\":").append(q(p.type))
                                .append(",\"enabled\":").append(p.enabled).append(",\"weight\":").append(p.weight)
                                .append(",\"sequential\":").append(p.sequential).append(",\"days\":").append(q(p.days))
                                .append(",\"start\":").append(p.start).append(",\"end\":").append(p.end)
                                .append(",\"every\":").append(p.every).append(",\"unit\":").append(q(p.unit)).append(",\"tracks\":").append(p.tracks.toString().replace(" ", ""))
                                .append(",\"style\":").append(q(p.style)).append(",\"timed\":").append(p.timed)
                                .append(",\"inShows\":").append(p.inShows).append(",\"when\":").append(q(p.when))
                                .append(",\"whenText\":").append(q(p.whenText()))
                                .append(",\"interrupt\":").append(q(p.interrupt)).append(",\"djOverride\":").append(p.djOverride)
                                .append(",\"ai\":").append(p.ai).append(",\"studio\":").append(p.studio)
                                .append('}');
                    }
                    send(ex, 200, sb.append("]}").toString());
                    break;
                }
                case "playlist/save": {
                    requirePost(post);
                    String body = readBody(ex, 256 * 1024);
                    RadioStore.Station s = manageable(me, admin, id(MediaAuthHttpServer.queryParam(body, "station")));
                    RadioStore.Playlist p = new RadioStore.Playlist();
                    p.id = intParam(MediaAuthHttpServer.queryParam(body, "id"));
                    p.station = s.id;
                    p.name = MediaAuthHttpServer.queryParam(body, "name");
                    p.type = val(body, "type", RadioStore.Playlist.GENERAL);
                    p.enabled = bool(body, "enabled", true);
                    p.weight = intParam(val(body, "weight", "5"));
                    p.sequential = bool(body, "sequential", false);
                    p.days = val(body, "days", "1111111");
                    p.start = intParam(val(body, "start", "0"));
                    p.end = intParam(val(body, "end", "60"));
                    p.every = intParam(val(body, "every", "4"));
                    p.unit = val(body, "unit", "songs");
                    // newer settings: kept as they are when the page does not send them
                    RadioStore.Playlist was = p.id == 0 ? null : store.playlist(p.id);
                    p.style = val(body, "style", was == null ? "" : was.style);
                    p.timed = bool(body, "timed", was != null && was.timed);
                    p.inShows = bool(body, "inShows", was == null || was.inShows);
                    p.when = val(body, "when", was == null ? "" : was.when);
                    p.interrupt = val(body, "interrupt", was == null ? "yes" : was.interrupt);
                    p.djOverride = bool(body, "djOverride", was == null || was.djOverride);
                    p.ai = bool(body, "ai", was == null || was.ai);
                    p.studio = bool(body, "studio", was != null && was.studio);
                    String list = val(body, "tracks", "");
                    for (String t : list.split(",")) {
                        if (!t.trim().isEmpty()) {
                            p.tracks.add(Long.valueOf(intParam(t)));
                        }
                    }
                    RadioStore.Playlist saved = store.savePlaylist(p, clock.getAsLong());
                    store.flushIfDirty();
                    send(ex, 200, "{\"id\":" + saved.id + ",\"songs\":" + saved.tracks.size() + "}");
                    break;
                }
                case "playlist/enable": {
                    requirePost(post);
                    String body = readBody(ex, MAX_BODY);
                    RadioStore.Station s = manageable(me, admin, id(MediaAuthHttpServer.queryParam(body, "station")));
                    long pid = id(MediaAuthHttpServer.queryParam(body, "id"));
                    RadioStore.Playlist pl = store.playlist(pid);
                    if (pl == null || pl.station != s.id) {
                        throw new Bad(404, "no such playlist");
                    }
                    store.setPlaylistEnabled(pid, bool(body, "on", true));
                    store.flushIfDirty();
                    send(ex, 200, "{\"ok\":true}");
                    break;
                }
                case "playlist/delete": {
                    requirePost(post);
                    String body = readBody(ex, MAX_BODY);
                    RadioStore.Station s = manageable(me, admin, id(MediaAuthHttpServer.queryParam(body, "station")));
                    if (!store.deletePlaylist(s.id, id(MediaAuthHttpServer.queryParam(body, "id")))) {
                        throw new Bad(404, "no such playlist");
                    }
                    store.flushIfDirty();
                    send(ex, 200, "{\"ok\":true}");
                    break;
                }
                case "stories": {
                    RadioStore.Station s = manageable(me, admin, id(MediaAuthHttpServer.queryParam(query, "station")));
                    RadioStories sto = radio.stories();
                    if (sto == null) {
                        throw new Bad(503, "stories are not running on this server");
                    }
                    StringBuilder sb = new StringBuilder("{\"storyEvery\":").append(s.storyEvery).append(",\"books\":[");
                    List<RadioStories.Book> bs = sto.books(s.id);
                    RadioStories.Part nx = sto.next(s.id);
                    for (int i = 0; i < bs.size(); i++) {
                        RadioStories.Book b = bs.get(i);
                        String peek = sto.partText(b.id, Math.min(b.pos, b.parts - 1));
                        sb.append(i > 0 ? "," : "").append("{\"id\":").append(b.id).append(",\"title\":").append(q(b.title))
                                .append(",\"author\":").append(q(b.author)).append(",\"enabled\":").append(b.enabled)
                                .append(",\"pos\":").append(b.pos).append(",\"parts\":").append(b.parts).append(",\"order\":").append(b.order)
                                .append(",\"reading\":").append(nx != null && nx.book == b.id)
                                .append(",\"next\":").append(q(peek.length() > 400 ? peek.substring(0, 400) + "..." : peek)).append('}');
                    }
                    send(ex, 200, sb.append("]}").toString());
                    break;
                }
                case "story/add": {
                    requirePost(post);
                    String body = readBody(ex, 8 * 1024 * 1024);
                    RadioStore.Station s = manageable(me, admin, id(MediaAuthHttpServer.queryParam(body, "station")));
                    RadioStories sto = radio.stories();
                    if (sto == null) {
                        throw new Bad(503, "stories are not running on this server");
                    }
                    RadioStories.Book b = sto.add(s.id, MediaAuthHttpServer.queryParam(body, "title"), MediaAuthHttpServer.queryParam(body, "author"),
                            MediaAuthHttpServer.queryParam(body, "text"), clock.getAsLong());
                    send(ex, 200, "{\"id\":" + b.id + ",\"parts\":" + b.parts + "}");
                    break;
                }
                case "story/update":
                case "story/delete": {
                    requirePost(post);
                    String body = readBody(ex, MAX_BODY);
                    RadioStore.Station s = manageable(me, admin, id(MediaAuthHttpServer.queryParam(body, "station")));
                    RadioStories sto = radio.stories();
                    long bid = id(MediaAuthHttpServer.queryParam(body, "id"));
                    RadioStories.Book b = sto == null ? null : sto.book(bid);
                    if (b == null || b.station != s.id) {
                        throw new Bad(404, "no such book");
                    }
                    if ("story/delete".equals(op)) {
                        sto.delete(s.id, bid);
                    } else {
                        String on = MediaAuthHttpServer.queryParam(body, "enabled");
                        String pos = MediaAuthHttpServer.queryParam(body, "pos");
                        String ord = MediaAuthHttpServer.queryParam(body, "order");
                        sto.update(bid, MediaAuthHttpServer.queryParam(body, "title"), MediaAuthHttpServer.queryParam(body, "author"),
                                on == null ? null : Boolean.valueOf(bool(body, "enabled", true)),
                                pos == null ? -1 : Math.max(0, intParam(pos)), ord == null ? -1 : Math.max(0, intParam(ord)));
                    }
                    send(ex, 200, "{\"ok\":true}");
                    break;
                }
                case "dj/save": {
                    requirePost(post);
                    String body = readBody(ex, MAX_BODY);
                    RadioStore.Station s = manageable(me, admin, id(MediaAuthHttpServer.queryParam(body, "station")));
                    RadioStore.Dj d = new RadioStore.Dj();
                    d.id = intParam(MediaAuthHttpServer.queryParam(body, "id"));
                    d.station = s.id;
                    d.name = MediaAuthHttpServer.queryParam(body, "name");
                    d.login = MediaAuthHttpServer.queryParam(body, "login");
                    d.password = MediaAuthHttpServer.queryParam(body, "password");
                    d.enabled = bool(body, "enabled", true);
                    d.days = val(body, "days", "1111111");
                    d.from = intParam(val(body, "from", "0"));
                    d.to = intParam(val(body, "to", "24"));
                    RadioStore.Dj was = d.id == 0 ? null : store.dj(d.id);
                    d.studio = bool(body, "studio", was != null && was.studio);
                    RadioStore.Dj saved = store.saveDj(d, Math.min(RadioBilling.MAX_DJS, radio.maxDjs(s.id)), clock.getAsLong());
                    store.flushIfDirty();
                    send(ex, 200, "{\"id\":" + saved.id + "}");
                    break;
                }
                case "dj/delete": {
                    requirePost(post);
                    String body = readBody(ex, MAX_BODY);
                    RadioStore.Station s = manageable(me, admin, id(MediaAuthHttpServer.queryParam(body, "station")));
                    if (!store.deleteDj(s.id, id(MediaAuthHttpServer.queryParam(body, "id")))) {
                        throw new Bad(404, "no such DJ");
                    }
                    store.flushIfDirty();
                    send(ex, 200, "{\"ok\":true}");
                    break;
                }
                case "plan/change": {
                    requirePost(post);
                    String body = readBody(ex, MAX_BODY);
                    RadioStore.Station s = manageable(me, admin, id(MediaAuthHttpServer.queryParam(body, "station")));
                    RadioBilling.Plan p = billing == null ? null : billing.plan(intParam(MediaAuthHttpServer.queryParam(body, "plan")));
                    if (p == null || (!p.signup && !admin)) {
                        throw new Bad(400, "that plan is not available");
                    }
                    if (!admin && !RadioStore.BILL_UNPAID.equals(s.billing)) {
                        throw new Bad(409, "to change the plan of a paid station, pay for the new plan (Billing) or ask Memoryq");
                    }
                    store.setPlan(s.id, p.id);
                    store.flushIfDirty();
                    radio.refresh();
                    send(ex, 200, "{\"ok\":true}");
                    break;
                }
                case "plan/save": {
                    requirePost(post);
                    String body = readBody(ex, MAX_BODY);
                    RadioBilling b = billing;
                    if (b == null) {
                        throw new Bad(409, "billing is not available");
                    }
                    RadioBilling.Plan p = new RadioBilling.Plan();
                    p.id = intParam(MediaAuthHttpServer.queryParam(body, "id"));
                    p.name = MediaAuthHttpServer.queryParam(body, "name");
                    p.monthCents = RadioBilling.cents(val(body, "month", ""));
                    p.yearCents = RadioBilling.cents(val(body, "year", ""));
                    p.listeners = intParam(val(body, "listeners", "0"));
                    p.kbps = intParam(val(body, "kbps", "128"));
                    p.storageMb = intParam(val(body, "storageMb", "0"));
                    p.transferGb = intParam(val(body, "transferGb", "0"));
                    p.djs = intParam(val(body, "djs", "3"));
                    p.djPro = bool(body, "djPro", true);
                    p.signup = bool(body, "signup", true);
                    RadioBilling.Plan saved = b.savePlan(p);
                    radio.refresh();
                    send(ex, 200, "{\"id\":" + saved.id + "}");
                    break;
                }
                case "plan/delete": {
                    requirePost(post);
                    int pid = intParam(MediaAuthHttpServer.queryParam(readBody(ex, MAX_BODY), "id"));
                    for (RadioStore.Station o : store.stations()) {
                        if (o.planId == pid) {
                            throw new Bad(409, o.name + " is on this plan - move it to another plan first");
                        }
                    }
                    if (billing == null || !billing.deletePlan(pid)) {
                        throw new Bad(404, "no such plan");
                    }
                    send(ex, 200, "{\"ok\":true}");
                    break;
                }
                case "billing/save": {
                    requirePost(post);
                    String body = readBody(ex, MAX_BODY);
                    RadioBilling b = billing;
                    if (b == null) {
                        throw new Bad(409, "billing is not available");
                    }
                    b.save(MediaAuthHttpServer.queryParam(body, "currency"), MediaAuthHttpServer.queryParam(body, "month"),
                            MediaAuthHttpServer.queryParam(body, "year"), MediaAuthHttpServer.queryParam(body, "trialDays"),
                            MediaAuthHttpServer.queryParam(body, "stripeKey"));
                    send(ex, 200, billingJson(true));
                    break;
                }
                case "station/admin": {
                    requirePost(post);
                    String body = readBody(ex, MAX_BODY);
                    long id = id(MediaAuthHttpServer.queryParam(body, "id"));
                    RadioStore.Station before = store.station(id);
                    if (before == null) {
                        throw new Bad(404, "no such station");
                    }
                    String owner = MediaAuthHttpServer.queryParam(body, "owner");
                    if (owner != null && !owner.trim().isEmpty() && users.info(owner.trim()) == null) {
                        throw new Bad(404, "there is no account called " + owner.trim());
                    }
                    String portText = MediaAuthHttpServer.queryParam(body, "livePort");
                    int port = 0;
                    if (portText != null && !portText.trim().isEmpty()) {
                        try {
                            port = Integer.parseInt(portText.trim());
                        } catch (NumberFormatException e) {
                            throw new Bad(400, "the live port must be a number");
                        }
                    }
                    String bill = MediaAuthHttpServer.queryParam(body, "billing");
                    String untilText = MediaAuthHttpServer.queryParam(body, "until");
                    if (bill != null && !bill.trim().isEmpty() && !RadioStore.validBilling(bill.trim())) {
                        throw new Bad(400, "payment must be paid, trial or unpaid");
                    }
                    long until = untilText == null ? -1 : dateParam(untilText);
                    String web = MediaAuthHttpServer.queryParam(body, "website");
                    String livePass = MediaAuthHttpServer.queryParam(body, "livePass");
                    if (web != null) {
                        RadioStore.checkWebsite(web);
                    }
                    if (livePass != null && !livePass.trim().isEmpty()) {
                        RadioStore.checkLivePass(livePass);
                    }
                    RadioStore.Station s = store.setAdminFields(id, owner == null ? null : owner.trim(),
                            MediaAuthHttpServer.queryParam(body, "status"), port);
                    if (bill != null && !bill.trim().isEmpty()) {
                        s = store.setBilling(id, bill.trim(), until >= 0 ? until : before.paidUntil, before.plan);
                    } else if (until >= 0) {
                        s = store.setBilling(id, before.billing, until, before.plan);
                    }
                    if (web != null) {
                        s = store.setWebsite(id, web);
                    }
                    if (livePass != null && !livePass.trim().isEmpty()) {
                        s = store.setLivePassword(id, livePass);
                    }
                    String aiText = MediaAuthHttpServer.queryParam(body, "aiOn");
                    if (aiText != null && !aiText.trim().isEmpty()) {
                        s = store.setAiOn(id, "1".equals(aiText.trim()) || "true".equals(aiText.trim()));
                    }
                    String lim = MediaAuthHttpServer.queryParam(body, "limits");
                    if (lim != null && !lim.trim().isEmpty()) {
                        String[] l = lim.split(",");
                        if (l.length != 5) {
                            throw new Bad(400, "limits: listeners,kbps,storageMb,transferGb,djs");
                        }
                        s = store.setLimits(id, intParam(l[0]), intParam(l[1]), intParam(l[2]), intParam(l[3]), intParam(l[4]));
                    }
                    String company = MediaAuthHttpServer.queryParam(body, "company");
                    String email = MediaAuthHttpServer.queryParam(body, "email");
                    if (company != null || email != null) {
                        s = store.setContact(id, company == null ? s.company : company, email == null ? s.email : email);
                    }
                    String ownerPass = MediaAuthHttpServer.queryParam(body, "ownerPassword");
                    if (ownerPass != null && !ownerPass.isEmpty()) {
                        if (s.owner.isEmpty()) {
                            throw new Bad(409, "this station has no owner account");
                        }
                        try {
                            users.setPassword(s.owner, ownerPass);
                        } catch (UserStore.UserException e) {
                            throw new Bad(400, e.getMessage());
                        }
                    }
                    String planText = MediaAuthHttpServer.queryParam(body, "plan");
                    if (planText != null && !planText.trim().isEmpty()) {
                        int pid = intParam(planText);
                        if (pid != 0 && (billing == null || billing.plan(pid) == null)) {
                            throw new Bad(400, "no such plan");
                        }
                        s = store.setPlan(id, pid);
                    }
                    store.flushIfDirty();
                    radio.refresh();
                    if (!before.status.equals(s.status)) {
                        if (RadioStore.STATUS_ACTIVE.equals(s.status)) {
                            mailOwner(s.owner, "Your radio station " + s.name + " is active",
                                    "Good news: " + s.name + " is active" + (RadioStore.BILL_TRIAL.equals(s.billing)
                                            ? " (free trial until " + day(s.paidUntil) + ")" : "")
                                            + ". Add your music and it goes on air by itself.");
                        } else if (RadioStore.STATUS_SUSPENDED.equals(s.status)) {
                            mailOwner(s.owner, "Your radio station " + s.name + " is paused",
                                    s.name + " has been taken off air by Memoryq. Reply to this email if you have questions.");
                        }
                    }
                    send(ex, 200, "{\"ok\":true}");
                    break;
                }
                case "station/save": {
                    requirePost(post);
                    String body = readBody(ex, MAX_BODY);
                    RadioStore.Station s = manageable(me, admin, id(MediaAuthHttpServer.queryParam(body, "id")));
                    boolean trial = trialLogin(me);
                    if (!trial) {
                        s.name = val(body, "name", s.name); // the trial station keeps its name (it may be in the public list)
                    }
                    s.description = val(body, "description", s.description);
                    s.djName = val(body, "djName", s.djName);
                    s.voice = val(body, "voice", s.voice);
                    s.enabled = bool(body, "enabled", s.enabled);
                    s.newsOn = bool(body, "newsOn", s.newsOn);
                    s.shoutOn = bool(body, "shoutOn", s.shoutOn);
                    s.introsOn = bool(body, "introsOn", s.introsOn);
                    s.sayArtist = bool(body, "sayArtist", s.sayArtist);
                    String se = MediaAuthHttpServer.queryParam(body, "storyEvery");
                    if (se != null && se.trim().matches("\\d{1,2}")) {
                        s.storyEvery = Integer.parseInt(se.trim());
                    }
                    String me2 = MediaAuthHttpServer.queryParam(body, "msgEvery");
                    if (me2 != null && me2.trim().matches("\\d{1,2}")) {
                        s.msgEvery = Integer.parseInt(me2.trim());
                    }
                    String ie = MediaAuthHttpServer.queryParam(body, "idEvery");
                    if (ie != null && ie.trim().matches("\\d{1,2}")) {
                        s.idEvery = Integer.parseInt(ie.trim());
                    }
                    s.promo = val(body, "promo", s.promo);
                    String every = MediaAuthHttpServer.queryParam(body, "talkEvery");
                    if (every != null) {
                        try {
                            s.talkEvery = Integer.parseInt(every.trim());
                        } catch (NumberFormatException e) {
                            throw new Bad(400, "talk every: give a number of songs");
                        }
                    }
                    store.updateStation(s);
                    String web = trial ? null : MediaAuthHttpServer.queryParam(body, "website");
                    if (web != null) {
                        store.setWebsite(s.id, web);
                    }
                    String noRep = MediaAuthHttpServer.queryParam(body, "noRepeatMin");
                    if (noRep != null && !noRep.trim().isEmpty()) {
                        store.setNoRepeat(s.id, intParam(noRep));
                    }
                    String tz = MediaAuthHttpServer.queryParam(body, "timezone");
                    if (tz != null && !tz.trim().isEmpty()) {
                        store.setTimezone(s.id, tz);
                    }
                    String company = trial ? null : MediaAuthHttpServer.queryParam(body, "company");
                    String email = trial ? null : MediaAuthHttpServer.queryParam(body, "email");
                    if (company != null || email != null) {
                        RadioStore.Station cur = store.station(s.id);
                        store.setContact(s.id, company == null ? cur.company : company, email == null ? cur.email : email);
                    }
                    store.flushIfDirty();
                    radio.refresh();
                    send(ex, 200, "{\"ok\":true}");
                    break;
                }
                case "station/delete": {
                    requirePost(post);
                    long id = id(MediaAuthHttpServer.queryParam(readBody(ex, MAX_BODY), "id"));
                    if (!store.deleteStation(id)) {
                        throw new Bad(404, "no such station");
                    }
                    store.flushIfDirty();
                    RadioStats st = radio.stats();
                    if (st != null) {
                        st.forget(id);
                    }
                    radio.refresh();
                    send(ex, 200, "{\"ok\":true}");
                    break;
                }
                case "tracks": {
                    long id = manageable(me, admin, id(MediaAuthHttpServer.queryParam(query, "station"))).id;
                    StringBuilder sb = new StringBuilder("{\"tracks\":[");
                    List<RadioStore.Track> list = store.tracks(id);
                    for (int i = list.size() - 1; i >= 0; i--) {
                        RadioStore.Track t = list.get(i);
                        if (i < list.size() - 1) {
                            sb.append(',');
                        }
                        sb.append("{\"id\":").append(t.id).append(",\"title\":").append(q(t.title))
                                .append(",\"artist\":").append(q(t.artist)).append(",\"album\":").append(q(t.album))
                                .append(",\"durationMs\":").append(t.durationMs).append(",\"plays\":").append(t.plays)
                                .append(",\"added\":").append(t.added).append(",\"lastPlayed\":").append(t.lastPlayed)
                                .append(",\"link\":").append(q(t.link)).append(",\"likes\":").append(t.likes)
                                .append(",\"taps\":").append(t.taps).append(",\"requests\":").append(t.requests)
                                .append('}');
                    }
                    send(ex, 200, sb.append("]}").toString());
                    break;
                }
                case "upload":
                    requirePost(post);
                    manageable(me, admin, id(MediaAuthHttpServer.queryParam(query, "station")));
                    upload(ex, query);
                    break;
                case "track/save": {
                    requirePost(post);
                    String body = readBody(ex, MAX_BODY);
                    long trackId = id(MediaAuthHttpServer.queryParam(body, "id"));
                    manageableTrack(me, admin, trackId);
                    store.editTrack(trackId, MediaAuthHttpServer.queryParam(body, "title"),
                            MediaAuthHttpServer.queryParam(body, "artist"), MediaAuthHttpServer.queryParam(body, "link"));
                    store.flushIfDirty();
                    send(ex, 200, "{\"ok\":true}");
                    break;
                }
                case "track/delete": {
                    requirePost(post);
                    long trackId = id(MediaAuthHttpServer.queryParam(readBody(ex, MAX_BODY), "id"));
                    manageableTrack(me, admin, trackId);
                    if (!store.deleteTrack(trackId)) {
                        throw new Bad(404, "no such song");
                    }
                    store.flushIfDirty();
                    radio.refresh();
                    send(ex, 200, "{\"ok\":true}");
                    break;
                }
                case "testvoice": {
                    requirePost(post);
                    RadioStore.Station st = manageable(me, admin,
                            id(MediaAuthHttpServer.queryParam(readBody(ex, MAX_BODY), "station")));
                    short[] pcm = radio.testVoice(st);
                    if (pcm == null) {
                        send(ex, 200, "{\"ok\":false,\"engine\":" + q(radio.tools().voiceEngine()) + ",\"error\":"
                                + q(radio.tools().lastVoiceError()) + "}");
                    } else {
                        send(ex, 200, "{\"ok\":true,\"engine\":" + q(radio.tools().voiceEngine()) + ",\"wav\":"
                                + q("data:audio/wav;base64," + java.util.Base64.getEncoder().encodeToString(wav(pcm))) + "}");
                    }
                    break;
                }
                case "skip":
                case "talk": {
                    requirePost(post);
                    long id = manageable(me, admin, id(MediaAuthHttpServer.queryParam(readBody(ex, MAX_BODY), "station"))).id;
                    RadioEngine e = radio.engine(id);
                    if (e == null) {
                        throw new Bad(409, "this station is not on air");
                    }
                    if (e.liveOnAir()) {
                        throw new Bad(409, "a live DJ is on air right now");
                    }
                    if (op.equals("skip")) {
                        e.skip();
                    } else {
                        if (!store.station(id).aiOn) {
                            throw new Bad(409, "the AI presenter is switched off for this station (Memoryq admin)");
                        }
                        e.talkImmediately();
                    }
                    send(ex, 200, "{\"ok\":true}");
                    break;
                }
                case "live/password": {
                    requirePost(post);
                    String lpBody = readBody(ex, MAX_BODY);
                    long id = manageable(me, admin, id(MediaAuthHttpServer.queryParam(lpBody, "station"))).id;
                    String wanted = MediaAuthHttpServer.queryParam(lpBody, "pass");
                    String pass = wanted == null || wanted.trim().isEmpty() ? store.resetLivePassword(id)
                            : store.setLivePassword(id, wanted).livePass;
                    store.flushIfDirty();
                    send(ex, 200, "{\"livePass\":" + q(pass) + "}");
                    break;
                }
                case "live/kick": {
                    requirePost(post);
                    long id = manageable(me, admin, id(MediaAuthHttpServer.queryParam(readBody(ex, MAX_BODY), "station"))).id;
                    LiveInput door = radio.liveDoor(id);
                    if (door != null) {
                        door.kick();
                    }
                    send(ex, 200, "{\"ok\":true}");
                    break;
                }
                case "report": {
                    RadioStore.Station s = manageable(me, admin, id(MediaAuthHttpServer.queryParam(query, "station")));
                    long[] r = range(query);
                    RadioListenLog ll = radio.listenLog();
                    if (ll == null) {
                        throw new Bad(503, "statistics are not running");
                    }
                    String rep = ll.reportJson(s, r[0], r[1], admin);
                    String old = importer.historyJson(s.id);
                    if (old != null && rep.endsWith("}")) {
                        // the station's listening before it moved here (from Centova Cast), per month
                        rep = rep.substring(0, rep.length() - 1) + ",\"centova\":" + old + "}";
                    }
                    send(ex, 200, rep);
                    break;
                }
                case "report.csv": {
                    RadioStore.Station s = manageable(me, admin, id(MediaAuthHttpServer.queryParam(query, "station")));
                    long[] r = range(query);
                    RadioListenLog ll = radio.listenLog();
                    if (ll == null) {
                        throw new Bad(503, "statistics are not running");
                    }
                    String kind = String.valueOf(MediaAuthHttpServer.queryParam(query, "kind"));
                    if (!kind.matches("sessions|plays|countries|agents|tracks|history")) {
                        throw new Bad(400, "unknown table");
                    }
                    byte[] csv = ll.csv(s, r[0], r[1], kind, admin).getBytes(StandardCharsets.UTF_8);
                    String fname = (s.name.replaceAll("[^A-Za-z0-9]+", "-") + "-" + kind + "-"
                            + java.time.Instant.ofEpochMilli(r[0]).toString().substring(0, 10) + ".csv").replaceAll("^-+", "");
                    ex.getResponseHeaders().set("Content-Type", "text/csv; charset=utf-8");
                    ex.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"" + fname + "\"");
                    ex.getResponseHeaders().set("Cache-Control", "no-store");
                    ex.sendResponseHeaders(200, csv.length);
                    ex.getResponseBody().write(csv);
                    break;
                }
                case "listeners": {
                    RadioStore.Station s = manageable(me, admin, id(MediaAuthHttpServer.queryParam(query, "station")));
                    RadioListenLog ll = radio.listenLog();
                    send(ex, 200, ll == null ? "{\"listeners\":[]}" : ll.listenersJson(s.id, admin));
                    break;
                }
                case "logs": {
                    RadioStore.Station s = manageable(me, admin, id(MediaAuthHttpServer.queryParam(query, "station")));
                    long[] r = range(query);
                    RadioListenLog ll = radio.listenLog();
                    send(ex, 200, ll == null ? "{\"rows\":[]}" : ll.logsJson(s.id, r[0], r[1], MediaAuthHttpServer.queryParam(query, "kind")));
                    break;
                }
                case "restart": {
                    requirePost(post);
                    RadioStore.Station s = manageable(me, admin, id(MediaAuthHttpServer.queryParam(readBody(ex, MAX_BODY), "station")));
                    if (!s.onAir()) {
                        throw new Bad(409, s.name + " is switched off - start it first");
                    }
                    radio.restart(s.id);
                    send(ex, 200, "{\"ok\":true}");
                    break;
                }
                case "station/login-as": {
                    requirePost(post);
                    RadioStore.Station s = manageable(me, admin, id(MediaAuthHttpServer.queryParam(readBody(ex, MAX_BODY), "id")));
                    UserStore.Info o = s.owner.isEmpty() ? null : users.info(s.owner);
                    if (o == null) {
                        throw new Bad(409, s.name + " has no owner account (it is run by Memoryq) - manage it here in Admin");
                    }
                    if (o.role == UserStore.Role.ADMIN) {
                        throw new Bad(409, "that is an admin account");
                    }
                    String tok = sessions.create(o.username, o.role);
                    System.out.println("[radio] admin " + me.username + " opened the Studio as " + o.username);
                    radio.logEvent(s.id, "admin", "The Memoryq admin opened the Studio for this account");
                    send(ex, 200, "{\"token\":" + q(tok) + ",\"owner\":" + q(o.username) + "}");
                    break;
                }
                case "stats": {
                    RadioStore.Station s = manageable(me, admin, id(MediaAuthHttpServer.queryParam(query, "station")));
                    int days = 30;
                    try {
                        days = Math.max(7, Math.min(365, Integer.parseInt(MediaAuthHttpServer.queryParam(query, "days"))));
                    } catch (RuntimeException e) {
                        days = 30;
                    }
                    send(ex, 200, statsJson(s, days));
                    break;
                }
                default:
                    send(ex, 404, error("not found"));
            }
        } catch (Bad e) {
            send(ex, e.status, error(e.getMessage()));
        } catch (IllegalArgumentException e) {
            send(ex, 400, error(e.getMessage()));
        } catch (Exception e) {
            send(ex, 500, error("server error: " + e.getMessage()));
        } finally {
            currentOp.remove();
            ex.close();
        }
    }

    private String staffJson(List<RadioStore.Staff> list) {
        StringBuilder sb = new StringBuilder("{\"staff\":[");
        for (int i = 0; i < list.size(); i++) {
            RadioStore.Staff f = list.get(i);
            RadioStore.Station s = radio.store().station(f.station);
            UserStore.Info u = users.info(f.user);
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"station\":").append(f.station).append(",\"stationName\":").append(q(s == null ? "" : s.name))
                    .append(",\"user\":").append(q(f.user)).append(",\"role\":").append(q(f.role))
                    .append(",\"added\":").append(f.added).append(",\"accountOk\":").append(u != null && !u.disabled).append('}');
        }
        return sb.append("]}").toString();
    }

    /** "owner", "coadmin", "presenter" or "admin" - what this person is on the station. */
    private String roleOn(UserStore.Info me, boolean admin, RadioStore.Station s) {
        if (admin) {
            return "admin";
        }
        if (!s.owner.isEmpty() && s.owner.equalsIgnoreCase(me.username)) {
            return "owner";
        }
        String r = radio.store().staffRole(s.id, me.username);
        return r == null ? "" : r;
    }

    /** The station, if this person may manage it (the admin: any; an owner: their own, unless suspended). */
    /** from / to (milliseconds) from the query; the last 48 hours when not given. */
    private long[] range(String query) throws Bad {
        long now = clock.getAsLong();
        long to = now, from = now - 48L * 3600000;
        try {
            String f = MediaAuthHttpServer.queryParam(query, "from");
            String t = MediaAuthHttpServer.queryParam(query, "to");
            if (t != null && !t.isEmpty()) {
                to = Long.parseLong(t);
            }
            if (f != null && !f.isEmpty()) {
                from = Long.parseLong(f);
            }
        } catch (NumberFormatException e) {
            throw new Bad(400, "from / to must be times");
        }
        if (to - from > 400L * 86400000) {
            from = to - 400L * 86400000;
        }
        return new long[] {from, Math.min(to, now)};
    }

    private RadioStore.Station manageable(UserStore.Info me, boolean admin, long id) throws Bad {
        RadioStore.Station s = radio.store().station(id);
        if (s == null) {
            throw new Bad(404, "no such station");
        }
        if (admin) {
            return s;
        }
        if (s.owner.isEmpty() || !s.owner.equalsIgnoreCase(me.username)) {
            String role = radio.store().staffRole(s.id, me.username);
            if (role == null) {
                throw new Bad(403, "this is not your station");
            }
            String op = currentOp.get();
            op = op == null ? "" : op;
            if (RadioStore.ROLE_COADMIN.equals(role) ? COADMIN_NOT.contains(op) : !PRESENTER_OPS.contains(op)) {
                throw new Bad(403, "your role on " + s.name + " (" + (RadioStore.ROLE_COADMIN.equals(role) ? "co-admin" : "presenter")
                        + ") does not include this - ask the station owner");
            }
        }
        if (RadioStore.STATUS_SUSPENDED.equals(s.status)) {
            throw new Bad(403, s.name + " is paused by Memoryq - contact support");
        }
        return s;
    }

    private void manageableTrack(UserStore.Info me, boolean admin, long trackId) throws Bad {
        RadioStore.Track t = radio.store().track(trackId);
        if (t == null) {
            throw new Bad(404, "no such song");
        }
        manageable(me, admin, t.station);
    }

    private String statsJson(RadioStore.Station s, int n) {
        RadioStats st = radio.stats();
        List<RadioStats.Day> days = st == null ? java.util.Collections.<RadioStats.Day>emptyList() : st.lastDays(s.id, n);
        StringBuilder sb = new StringBuilder("{\"station\":").append(s.id).append(",\"listenersNow\":")
                .append(radio.listeners(s.id)).append(",\"days\":[");
        long secs = 0;
        long live = 0;
        int peak = 0;
        int uniques = 0;
        int plays = 0;
        int likes = 0;
        int requests = 0;
        int taps = 0;
        for (int i = 0; i < days.size(); i++) {
            RadioStats.Day d = days.get(i);
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"date\":").append(q(d.date)).append(",\"hours\":")
                    .append(Math.round(d.listenSeconds / 36.0) / 100.0).append(",\"peak\":").append(d.peak)
                    .append(",\"uniques\":").append(d.uniques).append(",\"plays\":").append(d.plays)
                    .append(",\"likes\":").append(d.likes).append(",\"requests\":").append(d.requests)
                    .append(",\"taps\":").append(d.taps).append(",\"liveMinutes\":").append(d.liveSeconds / 60).append('}');
            secs += d.listenSeconds;
            live += d.liveSeconds;
            peak = Math.max(peak, d.peak);
            uniques += d.uniques;
            plays += d.plays;
            likes += d.likes;
            requests += d.requests;
            taps += d.taps;
        }
        sb.append("],\"totals\":{\"hours\":").append(Math.round(secs / 36.0) / 100.0).append(",\"peak\":").append(peak)
                .append(",\"uniques\":").append(uniques).append(",\"plays\":").append(plays).append(",\"likes\":")
                .append(likes).append(",\"requests\":").append(requests).append(",\"taps\":").append(taps)
                .append(",\"liveMinutes\":").append(live / 60).append("},\"top\":[");
        List<RadioStore.Track> tracks = radio.store().tracks(s.id);
        tracks.sort((a, b) -> Long.compare(b.plays * 2 + b.likes * 5L + b.requests * 5L + b.taps * 3L,
                a.plays * 2 + a.likes * 5L + a.requests * 5L + a.taps * 3L));
        for (int i = 0; i < Math.min(10, tracks.size()); i++) {
            RadioStore.Track t = tracks.get(i);
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"title\":").append(q(t.title)).append(",\"artist\":").append(q(t.artist))
                    .append(",\"plays\":").append(t.plays).append(",\"likes\":").append(t.likes)
                    .append(",\"requests\":").append(t.requests).append(",\"taps\":").append(t.taps).append('}');
        }
        return sb.append("]}").toString();
    }

    private String statusJson(UserStore.Info me, boolean admin) {
        AudioTools tools = radio.tools();
        StringBuilder sb = new StringBuilder("{\"ready\":").append(tools.ready());
        sb.append(",\"admin\":").append(admin);
        sb.append(",\"note\":").append(q(tools.note()));
        sb.append(",\"voiceEngine\":").append(q(tools.voiceEngine()));
        sb.append(",\"ai\":").append(radio.aiReady());
        sb.append(",\"newsAvailable\":").append(radio.newsAvailable());
        sb.append(",\"shoutAvailable\":").append(radio.shoutAvailable());
        sb.append(",\"voiceError\":").append(q(tools.lastVoiceError()));
        sb.append(",\"liveHost\":").append(q(liveHost));
        sb.append(",\"livePortMin\":").append(RadioStore.LIVE_PORT_MIN).append(",\"livePortMax\":").append(RadioStore.LIVE_PORT_MAX);
        sb.append(",\"maxOwned\":").append(MAX_OWNED);
        boolean trialMe = !admin && trialLogin(me);
        sb.append(",\"trialLogin\":").append(trialMe);
        sb.append(",\"freePorts\":").append(radio.store().freePorts().toString().replace(" ", ""));
        sb.append(",\"billing\":").append(billingJson(false));
        sb.append(",\"now\":").append(clock.getAsLong());
        sb.append(",\"voices\":[");
        List<String> v = tools.voiceNames();
        for (int i = 0; i < v.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(q(v.get(i)));
        }
        sb.append("],\"stations\":[");
        List<RadioStore.Station> list = admin ? radio.store().stations() : radio.store().manageableBy(me.username);
        for (int i = 0; i < list.size(); i++) {
            RadioStore.Station s = list.get(i);
            String myRole = roleOn(me, admin, s);
            boolean presenter = RadioStore.ROLE_PRESENTER.equals(myRole);
            if (i > 0) {
                sb.append(',');
            }
            String info = stationJson(s, true);
            sb.append(info, 0, info.length() - 1);
            LiveInput door = radio.liveDoor(s.id);
            RadioEngine e = radio.engine(s.id);
            sb.append(",\"enabled\":").append(s.enabled).append(",\"voice\":").append(q(s.voice))
                    .append(",\"talkEvery\":").append(s.talkEvery).append(",\"newsOn\":").append(s.newsOn)
                    .append(",\"shoutOn\":").append(s.shoutOn).append(",\"introsOn\":").append(s.introsOn)
                    .append(",\"sayArtist\":").append(s.sayArtist).append(",\"storyEvery\":").append(s.storyEvery)
                    .append(",\"aiOn\":").append(s.aiOn).append(",\"msgEvery\":").append(s.msgEvery).append(",\"myRole\":").append(q(myRole))
                    .append(",\"songMessages\":").append(radio.songMessagesReady())
                    .append(",\"idEvery\":").append(s.idEvery)
                    .append(",\"promo\":").append(q(s.promo)).append(",\"owner\":").append(q(s.owner))
                    .append(",\"status\":").append(q(s.status)).append(",\"onAir\":").append(s.onAir())
                    .append(",\"livePort\":").append(s.livePort)
                    .append(",\"livePass\":").append(q((RadioStore.STATUS_SUSPENDED.equals(s.status) && !admin) || trialMe || presenter ? "" : s.livePass))
                    .append(",\"liveOn\":").append(e != null && e.liveOnAir())
                    .append(",\"liveDoorOpen\":").append(door != null && door.lastProblem().indexOf("could not be opened") < 0)
                    .append(",\"liveDj\":").append(q(door == null ? "" : door.djName()))
                    .append(",\"liveProblem\":").append(q(door == null ? "" : door.lastProblem()))
                    .append(",\"website\":").append(q(s.website))
                    .append(",\"company\":").append(q(s.company)).append(",\"email\":").append(q(s.email))
                    .append(",\"limits\":[").append(s.limListeners).append(',').append(s.limKbps).append(',').append(s.limStorageMb).append(',')
                    .append(s.limTransferGb).append(',').append(s.limDjs).append(']')
                    .append(",\"kbpsNow\":").append(radio.kbpsFor(s.id))
                    .append(",\"billing\":").append(q(s.billing)).append(",\"paidUntil\":").append(s.paidUntil)
                    .append(",\"plan\":").append(q(s.plan)).append(",\"paidOnline\":").append(!s.stripeCustomer.isEmpty())
                    .append(",\"trialUsed\":").append(s.trialUsed)
                    .append(",\"planId\":").append(s.planId)
                    .append(",\"plan\":").append(planJson(planOf(s)))
                    .append(",\"timezone\":").append(q(s.timezone))
                    .append(",\"noRepeatMin\":").append(s.noRepeatMin)
                    .append(",\"usage\":{\"listeners\":").append(radio.listeners(s.id))
                    .append(",\"storageBytes\":").append(radio.store().storageBytes(s.id))
                    .append(",\"monthBytes\":").append(monthBytes(s))
                    .append(",\"djs\":").append(radio.store().djs(s.id).size()).append('}')
                    .append(",\"maxDjs\":").append(Math.min(RadioBilling.MAX_DJS, radio.maxDjs(s.id)))
                    .append(",\"djs\":").append(trialMe || presenter ? "[]" : djsJson(s.id))
                    .append(",\"shows\":").append(showsJson(s.id))
                    .append(",\"created\":").append(s.created).append('}');
        }
        return sb.append("]}").toString();
    }

    private void upload(HttpExchange ex, String query) throws Exception {
        RadioStore store = radio.store();
        long station = id(MediaAuthHttpServer.queryParam(query, "station"));
        if (store.station(station) == null) {
            throw new Bad(404, "no such station");
        }
        String name = MediaAuthHttpServer.queryParam(query, "name");
        String ext = RadioStore.audioType(name);
        if (ext == null) {
            throw new Bad(400, "only sound files can be added: " + String.join(", ", RadioStore.AUDIO_TYPES));
        }
        if (!radio.tools().ready()) {
            throw new Bad(409, "FFmpeg is not installed on the server - run the radio install script");
        }
        InputStream in = ex.getRequestBody();
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] b = new byte[65536];
        long total = 0;
        int n;
        while ((n = in.read(b)) != -1) {
            total += n;
            if (total > RadioStore.MAX_SONG_BYTES) {
                throw new Bad(413, "songs can be 60 MB at most");
            }
            buf.write(b, 0, n);
        }
        byte[] data = buf.toByteArray();
        if (data.length < 1000) {
            throw new Bad(400, "the file is empty");
        }
        DemoRadio dm = demo;
        if (dm != null && data.length > dm.uploadRoom(station)) {
            throw new Bad(413, "the trial station has room for about " + (DemoRadio.EXTRA_BYTES / MB)
                    + " MB of your own songs - delete one you added, or wait for the next fresh start");
        }
        RadioBilling.Plan plan = planOf(store.station(station));
        if (plan != null && plan.storageMb > 0) {
            long used = store.storageBytes(station);
            long limit = plan.storageMb * MB;
            if (used + data.length > limit) {
                throw new Bad(413, "storage is full (" + mb(used) + " of " + mb(limit) + " used) - delete some songs or move to a bigger plan");
            }
        }
        Files.createDirectories(tmpDir);
        Path tmp = Files.createTempFile(tmpDir, "upload-", "." + ext);
        AudioTools.Tags tags;
        try {
            Files.write(tmp, data);
            try {
                tags = radio.tools().probe(tmp);
            } catch (IOException e) {
                throw new Bad(400, name + ": " + e.getMessage());
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
        RadioStore.Track t = store.addTrack(station, name, ext, data, tags.title, tags.artist, tags.album,
                tags.durationMs, clock.getAsLong());
        String into = MediaAuthHttpServer.queryParam(query, "playlist");
        if (into != null && into.matches("\\d{1,9}")) {
            RadioStore.Playlist pl = store.playlist(Long.parseLong(into));
            if (pl != null && pl.station == station) {
                store.addToPlaylist(pl.id, t.id);
                store.flushIfDirty();
            }
        }
        radio.refresh();
        send(ex, 200, "{\"id\":" + t.id + ",\"title\":" + q(t.title) + ",\"artist\":" + q(t.artist)
                + ",\"durationMs\":" + t.durationMs + "}");
    }

    // ---- Helpers ----

    /** A WAV file (44.1 kHz stereo 16-bit) holding the samples. */
    static byte[] wav(short[] pcm) {
        int data = pcm.length * 2;
        java.nio.ByteBuffer b = java.nio.ByteBuffer.allocate(44 + data).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(36 + data).put("WAVE".getBytes(StandardCharsets.US_ASCII));
        b.put("fmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16).putShort((short) 1).putShort((short) 2)
                .putInt(AudioTools.RATE).putInt(AudioTools.RATE * 4).putShort((short) 4).putShort((short) 16);
        b.put("data".getBytes(StandardCharsets.US_ASCII)).putInt(data);
        for (short v : pcm) {
            b.putShort(v);
        }
        return b.array();
    }

    /** The listener's address (the real one when the request came through the HTTPS front door, Caddy). */
    private static String clientAddress(HttpExchange ex) {
        String f = ex.getRequestHeaders().getFirst("X-Forwarded-For");
        String direct = ex.getRemoteAddress() == null ? "" : ex.getRemoteAddress().getAddress().getHostAddress();
        if (f != null && !f.trim().isEmpty() && ("127.0.0.1".equals(direct) || "0:0:0:0:0:0:0:1".equals(direct))) {
            return f.split(",")[0].trim();
        }
        return direct;
    }

    private static void requirePost(boolean post) throws Bad {
        if (!post) {
            throw new Bad(405, "use POST");
        }
    }

    private static String val(String body, String key, String old) {
        String v = MediaAuthHttpServer.queryParam(body, key);
        return v == null ? old : v;
    }

    private static boolean bool(String body, String key, boolean old) {
        String v = MediaAuthHttpServer.queryParam(body, key);
        return v == null ? old : "true".equalsIgnoreCase(v.trim()) || "1".equals(v.trim()) || "on".equalsIgnoreCase(v.trim());
    }

    private static long id(String s) throws Bad {
        try {
            return Long.parseLong(s == null ? "" : s.trim());
        } catch (NumberFormatException e) {
            throw new Bad(400, "bad id");
        }
    }

    private static String q(String s) {
        return ProfileApi.quote(s == null ? "" : s);
    }

    private UserStore.Info signedIn(HttpExchange ex) {
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        String token = auth != null && auth.startsWith("Bearer ") ? auth.substring(7).trim() : null;
        SessionStore.Session s = sessions.get(token);
        UserStore.Info info = s == null ? null : users.info(s.username);
        return (info == null || info.disabled) ? null : info;
    }

    private static String readBody(HttpExchange ex, int max) throws IOException, Bad {
        InputStream in = ex.getRequestBody();
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] tmp = new byte[8192];
        int total = 0;
        int n;
        while ((n = in.read(tmp)) != -1) {
            total += n;
            if (total > max) {
                throw new Bad(413, "too much data sent");
            }
            buf.write(tmp, 0, n);
        }
        return new String(buf.toByteArray(), StandardCharsets.UTF_8);
    }

    /** Prices and what can be paid how. withAdmin adds the Stripe key hint. */
    private String billingJson(boolean withAdmin) {
        RadioBilling b = billing;
        if (b == null) {
            return "{\"online\":false,\"trialDays\":0,\"currency\":\"usd\",\"month\":\"0.00\",\"year\":\"0.00\",\"plans\":[]}";
        }
        StringBuilder sb = new StringBuilder("{\"online\":").append(b.stripeReady())
                .append(",\"trialDays\":").append(b.trialDays())
                .append(",\"currency\":").append(q(b.currency()))
                .append(",\"month\":").append(q(RadioBilling.money(b.monthCents())))
                .append(",\"year\":").append(q(RadioBilling.money(b.yearCents())))
                .append(",\"testMode\":").append(b.testMode())
                .append(",\"plans\":").append(plansJson(b, withAdmin));
        if (withAdmin) {
            sb.append(",\"keyHint\":").append(q(b.keyHint()));
        }
        return sb.append('}').toString();
    }

    private String djsJson(long station) {
        StringBuilder sb = new StringBuilder("[");
        for (RadioStore.Dj d : radio.store().djs(station)) {
            if (sb.length() > 1) {
                sb.append(',');
            }
            sb.append("{\"id\":").append(d.id).append(",\"name\":").append(q(d.name)).append(",\"login\":").append(q(d.login))
                    .append(",\"password\":").append(q(d.password)).append(",\"enabled\":").append(d.enabled)
                    .append(",\"days\":").append(q(d.days)).append(",\"from\":").append(d.from).append(",\"to\":").append(d.to)
                    .append(",\"lastLive\":").append(d.lastLive).append(",\"studio\":").append(d.studio).append('}');
        }
        return sb.append(']').toString();
    }

    private String showsJson(long station) {
        StringBuilder sb = new StringBuilder("[");
        List<RadioStore.Show> list = radio.store().shows(station);
        for (int i = 0; i < Math.min(15, list.size()); i++) {
            RadioStore.Show sh = list.get(i);
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"dj\":").append(q(sh.dj)).append(",\"start\":").append(sh.start).append(",\"end\":").append(sh.end).append('}');
        }
        return sb.append(']').toString();
    }

    /** For the Live Channels list: the radio station a Live DJ Pro channel belongs to (null = none). */
    public String channelExtra(String channelId) {
        long id = djPro.stationOfVideo(channelId);
        RadioStore.Station s = id == 0 ? null : radio.store().station(id);
        if (s == null) {
            return null;
        }
        return ",\"radio\":{\"id\":" + s.id + ",\"name\":" + q(s.name) + ",\"stream\":" + q("/radio/" + s.id + ".mp3") + "}";
    }

    private static String planJson(RadioBilling.Plan p) {
        if (p == null) {
            return "null";
        }
        return "{\"id\":" + p.id + ",\"name\":" + q(p.name) + ",\"month\":" + q(RadioBilling.money(p.monthCents))
                + ",\"year\":" + q(RadioBilling.money(p.yearCents)) + ",\"listeners\":" + p.listeners + ",\"kbps\":" + p.kbps
                + ",\"storageMb\":" + p.storageMb + ",\"transferGb\":" + p.transferGb + ",\"djs\":" + p.djs
                + ",\"djPro\":" + p.djPro + ",\"signup\":" + p.signup + "}";
    }

    private static String plansJson(RadioBilling b, boolean all) {
        StringBuilder sb = new StringBuilder("[");
        for (RadioBilling.Plan p : b.plans()) {
            if (!all && !p.signup) {
                continue;
            }
            if (sb.length() > 1) {
                sb.append(',');
            }
            sb.append("{\"id\":").append(p.id).append(",\"name\":").append(q(p.name))
                    .append(",\"month\":").append(q(RadioBilling.money(p.monthCents)))
                    .append(",\"year\":").append(q(RadioBilling.money(p.yearCents)))
                    .append(",\"listeners\":").append(p.listeners).append(",\"kbps\":").append(p.kbps)
                    .append(",\"storageMb\":").append(p.storageMb).append(",\"transferGb\":").append(p.transferGb)
                    .append(",\"djs\":").append(p.djs).append(",\"djPro\":").append(p.djPro)
                    .append(",\"signup\":").append(p.signup).append('}');
        }
        return sb.append(']').toString();
    }

    private static int intParam(String text) throws Bad {
        if (text == null || text.trim().isEmpty()) {
            return 0;
        }
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            throw new Bad(400, "expected a whole number, got: " + text.trim());
        }
    }

    private static int portParam(String text) throws Bad {
        if (text == null || text.trim().isEmpty()) {
            return 0;
        }
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            throw new Bad(400, "the port must be a number");
        }
    }

    /** "2026-12-31" -> the end of that day (UTC) in ms; "" -> 0 (no end). */
    private static long dateParam(String text) throws Bad {
        String t = text == null ? "" : text.trim();
        if (t.isEmpty()) {
            return 0;
        }
        try {
            return java.time.LocalDate.parse(t).plusDays(1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli() - 1;
        } catch (RuntimeException e) {
            throw new Bad(400, "dates look like 2026-12-31");
        }
    }

    private static String day(long ms) {
        return ms <= 0 ? "(no end date)" : java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneOffset.UTC).toLocalDate().toString();
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }

    // ---- Billing: renewals, reminders, running out ----

    private final java.util.concurrent.atomic.AtomicBoolean billingStarted = new java.util.concurrent.atomic.AtomicBoolean();

    /** Checks payments every 30 minutes (the first check a minute after the server starts). */
    public void startBillingTimer() {
        if (!billingStarted.compareAndSet(false, true)) {
            return;
        }
        java.util.concurrent.ScheduledExecutorService t = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
            Thread th = new Thread(r, "radio-billing");
            th.setDaemon(true);
            return th;
        });
        t.scheduleWithFixedDelay(() -> {
            try {
                billingTick();
            } catch (RuntimeException e) {
                System.out.println("[billing] check failed: " + e);
            }
        }, 1, 30, java.util.concurrent.TimeUnit.MINUTES);
        t.scheduleWithFixedDelay(() -> {
            try {
                usageTick();
            } catch (RuntimeException e) {
                System.out.println("[billing] usage check failed: " + e);
            }
        }, 1, 1, java.util.concurrent.TimeUnit.MINUTES);
    }

    /**
     * Online subscriptions near their end are checked with Stripe (renewed ones get their new end date); owners get a
     * reminder 3 days before a trial or a hand-set paid time ends; stations whose time ran out go off air.
     */
    void billingTick() {
        long now = clock.getAsLong();
        RadioStore store = radio.store();
        RadioBilling b = billing;
        boolean changed = false;
        for (RadioStore.Station s : store.stations()) {
            if (RadioStore.BILL_UNPAID.equals(s.billing) || s.paidUntil <= 0) {
                continue;
            }
            boolean renewing = false;
            if (!s.stripeSub.isEmpty() && b != null && b.hasKey() && now > s.paidUntil - 12L * 3600 * 1000) {
                try {
                    java.util.Map<String, Object> sub = b.subscription(s.stripeSub);
                    long end = RadioBilling.periodEnd(sub);
                    if (RadioBilling.subscriptionGood(sub)) {
                        renewing = true;
                        if (end > s.paidUntil) {
                            store.setStripe(s.id, s.stripeCustomer, s.stripeSub, end, s.plan);
                            s.paidUntil = end;
                            changed = true;
                            System.out.println("[billing] " + s.name + " renewed until " + day(end));
                        }
                    }
                } catch (RadioBilling.StripeException e) {
                    System.out.println("[billing] could not check " + s.name + ": " + e.getMessage());
                    renewing = !e.answered; // do not switch off just because Stripe could not be reached
                }
            }
            boolean trial = RadioStore.BILL_TRIAL.equals(s.billing);
            long offAt = trial ? s.paidUntil : s.paidUntil + RadioBilling.GRACE_MS;
            if (now >= offAt && !renewing) {
                store.setBilling(s.id, RadioStore.BILL_UNPAID, s.paidUntil, s.plan);
                changed = true;
                System.out.println("[billing] " + s.name + " ran out (" + s.billing + ") - off air until paid");
                mailOwner(s.owner, s.name + " is off air - payment needed", (trial ? "The free trial of " : "The paid time of ")
                        + s.name + " has ended, so the station is off air. Your music and settings are kept: pay in the "
                        + "studio and it is back on air straight away.");
                mail(adminEmail, "Radio station off air: " + s.name, s.name + " (" + s.owner + ") ran out of "
                        + (trial ? "its trial" : "paid time") + " and is off air.\n");
            } else if (!renewing && s.stripeSub.isEmpty() && now >= s.paidUntil - 3L * 24 * 3600 * 1000
                    && s.reminded != s.paidUntil && now < s.paidUntil) {
                store.setReminded(s.id, s.paidUntil);
                changed = true;
                mailOwner(s.owner, s.name + ": " + (trial ? "your free trial" : "your paid time") + " ends on " + day(s.paidUntil),
                        (trial ? "The free trial of " : "The paid time of ") + s.name + " ends on " + day(s.paidUntil)
                                + ". To keep it on air, pay in the studio (Account tab).");
            }
        }
        if (changed) {
            store.flushIfDirty();
            radio.refresh();
        }
    }

    // ---- The player for other websites ----

    private void embedPage(HttpExchange ex, long id) throws IOException {
        RadioStore.Station s = radio.store().station(id);
        if (s == null) {
            send(ex, 404, error("no such station"));
            return;
        }
        String theme = MediaAuthHttpServer.queryParam(ex.getRequestURI().getRawQuery(), "theme");
        String t = "dark".equals(theme) ? "dark" : ("light".equals(theme) ? "light" : "auto");
        String html = EMBED_HTML.replace("%ID%", String.valueOf(s.id)).replace("%THEME%", t)
                .replace("%NAME%", html(s.name)).replace("%SLOGAN%", html(s.description))
                .replace("%WEB%", html(s.website));
        byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-cache");
        ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        ex.sendResponseHeaders(200, bytes.length);
        ex.getResponseBody().write(bytes);
    }

    private static String html(String s) {
        return (s == null ? "" : s).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    private static final String EMBED_HTML = String.join("\n",
            "<!DOCTYPE html><html lang=\"en\" data-theme=\"%THEME%\"><head><meta charset=\"utf-8\">",
            "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"><title>%NAME%</title>",
            "<style>",
            ":root{--bg:#fff;--ink:#1b1f23;--mut:#5f6e7d;--line:#e2e7ed;--acc:#2f6fed}",
            "@media (prefers-color-scheme:dark){:root:not([data-theme=light]){--bg:#16202a;--ink:#e6ebf0;--mut:#9fb0c0;--line:#2a3542;--acc:#5b8cff}}",
            ":root[data-theme=dark]{--bg:#16202a;--ink:#e6ebf0;--mut:#9fb0c0;--line:#2a3542;--acc:#5b8cff}",
            "*{box-sizing:border-box}html,body{margin:0;height:100%}",
            "body{font-family:system-ui,-apple-system,'Segoe UI',Roboto,sans-serif;background:var(--bg);color:var(--ink);",
            "border:1px solid var(--line);border-radius:12px;display:flex;align-items:center;gap:14px;padding:12px 14px;overflow:hidden}",
            "button{flex:none;width:56px;height:56px;border-radius:50%;border:0;background:var(--acc);color:#fff;font-size:22px;cursor:pointer}",
            "button:disabled{opacity:.45;cursor:default}",
            ".g{flex:1;min-width:0}.n{font-weight:750;font-size:16px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}",
            ".s,.p{font-size:13px;color:var(--mut);white-space:nowrap;overflow:hidden;text-overflow:ellipsis}",
            ".p{color:var(--ink);margin-top:3px}.dot{display:inline-block;width:8px;height:8px;border-radius:50%;background:#e5484d;margin-right:6px;vertical-align:1px}",
            "a{color:var(--mut);font-size:11px;text-decoration:none;flex:none;align-self:flex-end}a:hover{text-decoration:underline}",
            "</style></head><body>",
            "<button id=\"b\" aria-label=\"Play %NAME%\">&#9654;</button>",
            "<div class=\"g\"><div class=\"n\">%NAME%</div><div class=\"s\">%SLOGAN%</div><div class=\"p\" id=\"p\">&nbsp;</div></div>",
            "<a href=\"https://memoryq.com\" target=\"_blank\" rel=\"noopener\">Memoryq</a>",
            "<audio id=\"a\" preload=\"none\"></audio>",
            "<script>",
            "var id=%ID%,b=document.getElementById('b'),a=document.getElementById('a'),p=document.getElementById('p'),on=false,air=true;",
            "function stop(){a.pause();a.removeAttribute('src');a.load();on=false;b.innerHTML='&#9654;';b.setAttribute('aria-label','Play');}",
            "b.onclick=function(){if(on){stop();return;}a.src='/radio/'+id+'.mp3?t='+Date.now();a.play().catch(function(){stop();});on=true;b.innerHTML='&#10074;&#10074;';b.setAttribute('aria-label','Pause');};",
            "a.onerror=function(){if(on){stop();p.textContent='Could not play - try again';}};",
            "function now(){fetch('/api/radio/now?station='+id).then(function(r){return r.ok?r.json():null;}).then(function(j){",
            "if(!j){air=false;b.disabled=true;p.textContent='Off air';return;}air=true;b.disabled=false;",
            "var t=j.liveDj?'Live show':(j.now?(j.now.label||j.now.title):'');p.innerHTML='';",
            "if(t){var d=document.createElement('span');d.className='dot';p.appendChild(d);p.appendChild(document.createTextNode(t));}else{p.innerHTML='&nbsp;';}",
            "}).catch(function(){});}",
            "now();setInterval(function(){if(!document.hidden){now();}},15000);",
            "</script></body></html>");

    private static String error(String message) {
        return "{\"error\":" + ProfileApi.quote(message) + "}";
    }

    private static void send(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        ex.sendResponseHeaders(status, bytes.length);
        OutputStream os = ex.getResponseBody();
        os.write(bytes);
        os.close();
    }
}