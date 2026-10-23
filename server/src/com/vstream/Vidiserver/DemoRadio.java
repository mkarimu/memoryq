package com.vstream.Vidiserver;

import com.sun.net.httpserver.Filter;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * "Try the Studio": one radio station that any visitor may run, to get a taste of having their own station before
 * buying one. Its login (user and password) is shown on the Studio's sign-in page and on the home page. Everybody
 * who tries it shares the same station. Every 2 hours (at even hours) it goes back to the starting point the admin
 * saved: songs, playlists, stories and settings as they were; songs visitors added are deleted; everyone trying it is
 * signed out.
 * The trial login can only use the Studio of its own station (and listen to the radio). It cannot change its
 * password or email, buy anything, open DJ logins or the live DJ password, go live with video, chat or post.
 *
 *   GET  /api/demo                 for the sign-in page: {on, user, password, stationName, nextReset, now}
 *   /api/admin/demo/status | setup (station, user, password, listed) | settings (password, listed) | save-start | reset | stop
 */
public final class DemoRadio {

    /** How often the trial station goes back to its starting point. */
    public static final long PERIOD_MS = Long.getLong("vstream.demoResetMinutes", 120L) * 60000L;
    /** Room for visitors' own songs on top of the starting point. */
    public static final long EXTRA_BYTES = Long.getLong("vstream.demoExtraMb", 200L) * 1024L * 1024L;

    private final RadioService radio;
    private final UserStore users;
    private final SessionStore sessions;
    private final LongSupplier clock;
    private final Path settingsFile;
    private final Path startDir;

    // read without the lock by every request (the gate), so a reset does not hold up the site
    private volatile boolean on;
    private volatile String user = "";
    private String password = "";
    private volatile long station;
    private volatile boolean listed;
    private long savedAt;
    private long lastReset;
    private long savedBytes;
    private String lastResult = "";
    private ScheduledExecutorService timer;

    public DemoRadio(RadioService radio, UserStore users, SessionStore sessions, LongSupplier clock, Path settingsFile, Path startDir) {
        this.radio = radio;
        this.users = users;
        this.sessions = sessions;
        this.clock = clock;
        this.settingsFile = settingsFile;
        this.startDir = startDir;
    }

    // ---- Settings file ----

    public synchronized void init() {
        if (!Files.isRegularFile(settingsFile)) {
            return;
        }
        try {
            for (String line : Files.readAllLines(settingsFile, StandardCharsets.UTF_8)) {
                int t = line.indexOf('\t');
                if (t < 0) {
                    continue;
                }
                String k = line.substring(0, t), v = line.substring(t + 1);
                try {
                    switch (k) {
                        case "on": on = "1".equals(v); break;
                        case "user": user = v; break;
                        case "password": password = v; break;
                        case "station": station = Long.parseLong(v); break;
                        case "listed": listed = "1".equals(v); break;
                        case "savedAt": savedAt = Long.parseLong(v); break;
                        case "lastReset": lastReset = Long.parseLong(v); break;
                        default: break;
                    }
                } catch (NumberFormatException e) {
                    // skip
                }
            }
        } catch (IOException e) {
            System.out.println("[try-it] could not read " + settingsFile + ": " + e.getMessage());
        }
        savedBytes = RadioStore.exportedBytes(startDir.resolve("station"));
    }

    private void save() {
        String s = "# The Studio's try-it station (the login is public - shown on the sign-in page)\n"
                + "on\t" + (on ? "1" : "0") + "\nuser\t" + user + "\npassword\t" + password + "\nstation\t" + station
                + "\nlisted\t" + (listed ? "1" : "0") + "\nsavedAt\t" + savedAt + "\nlastReset\t" + lastReset + "\n";
        try {
            Path part = settingsFile.resolveSibling(settingsFile.getFileName() + ".part");
            Files.write(part, s.getBytes(StandardCharsets.UTF_8));
            Files.move(part, settingsFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            System.out.println("[try-it] could not save the settings: " + e.getMessage());
        }
    }

    // ---- Questions from the rest of the server ----

    public boolean isOn() {
        return on;
    }

    /** The shared trial login (only while the trial is on). */
    public boolean isDemoUser(String username) {
        String u = user;
        return on && username != null && !u.isEmpty() && u.equalsIgnoreCase(username);
    }

    public boolean isDemoStation(long id) {
        return on && id == station;
    }

    /** Left out of the public list of stations (unless the admin wants it in). */
    public boolean hiddenFromList(long id) {
        return on && id == station && !listed;
    }

    /** How many more bytes visitors may upload to this station (Long.MAX_VALUE for other stations). */
    public synchronized long uploadRoom(long id) {
        if (!isDemoStation(id)) {
            return Long.MAX_VALUE;
        }
        return Math.max(0, savedBytes + EXTRA_BYTES - radio.store().storageBytes(id));
    }

    public synchronized long nextReset() {
        return (lastReset / PERIOD_MS + 1) * PERIOD_MS;
    }

    // ---- What the admin does ----

    /** Starts (or moves) the trial on a station with this login; the station as it is now is the starting point. */
    public synchronized String setup(long stationId, String username, String pass, boolean showInList) throws IOException {
        return setup(stationId, username, pass, showInList, null);
    }

    /** The same, and renames the station first (name "" or null = keep its name). */
    public synchronized String setup(long stationId, String username, String pass, boolean showInList, String name) throws IOException {
        RadioStore store = radio.store();
        RadioStore.Station s = store.station(stationId);
        if (s == null) {
            throw new IllegalArgumentException("choose a station");
        }
        String u = username == null ? "" : username.trim();
        try {
            UserStore.checkNew(u, pass);
        } catch (UserStore.UserException e) {
            throw new IllegalArgumentException(e.getMessage());
        }
        UserStore.Info existing = users.info(u);
        boolean ownsIt = !s.owner.isEmpty() && s.owner.equalsIgnoreCase(u);
        boolean wasTrial = !user.isEmpty() && user.equalsIgnoreCase(u);
        if (existing != null && existing.role == UserStore.Role.ADMIN) {
            throw new IllegalArgumentException(u + " is an admin account - choose another login (e.g. trial)");
        }
        if (existing != null && !ownsIt && !wasTrial) {
            throw new IllegalArgumentException("there is already an account called " + u
                    + " - choose another login (e.g. trial), or the account that runs this station");
        }
        if (existing != null) {
            for (RadioStore.Station o : store.ownedBy(u)) {
                if (o.id != stationId) {
                    throw new IllegalArgumentException(u + " also runs " + o.name + " - choose a login that runs only this station");
                }
            }
        }
        if (!s.owner.isEmpty() && !ownsIt && !(on && s.owner.equalsIgnoreCase(user))) {
            throw new IllegalArgumentException(s.name + " belongs to the account " + s.owner
                    + " - use " + s.owner + " as the trial login, or choose a Memoryq station");
        }
        if (name != null && !name.trim().isEmpty() && !name.trim().equals(s.name)) {
            s.name = name.trim();
            store.updateStation(s); // checks the name is free
        }
        try {
            if (existing == null) {
                users.createUser(u, pass, UserStore.Role.CHATTER);
            } else {
                users.setPassword(u, pass);
                users.setDisabled(u, false);
            }
        } catch (UserStore.UserException e) {
            throw new IllegalArgumentException(e.getMessage());
        }
        if (on && station != stationId && !user.isEmpty()) {
            sessions.removeUser(user); // the old trial station stays as it is now
        }
        sessions.removeUser(u);
        store.setAdminFields(stationId, u, RadioStore.STATUS_ACTIVE, 0);
        store.setBilling(stationId, RadioStore.BILL_PAID, 0, "");
        store.flushIfDirty();
        on = true;
        user = u;
        password = pass;
        station = stationId;
        listed = showInList;
        saveStartLocked();
        lastReset = clock.getAsLong();
        lastResult = "started";
        save();
        radio.refresh();
        System.out.println("[try-it] the trial studio is on: " + s.name + ", login " + u);
        return "The trial is on: " + s.name + ", login " + u;
    }

    /** The public password and whether the station shows in the public list. */
    public synchronized void settings(String pass, Boolean showInList) {
        if (!on) {
            throw new IllegalArgumentException("start the trial first");
        }
        if (pass != null && !pass.isEmpty() && !pass.equals(password)) {
            try {
                users.setPassword(user, pass);
            } catch (UserStore.UserException e) {
                throw new IllegalArgumentException(e.getMessage());
            }
            password = pass;
            sessions.removeUser(user);
        }
        if (showInList != null) {
            listed = showInList;
        }
        save();
    }

    /** The station as it is now becomes the starting point. */
    public synchronized void saveStart() throws IOException {
        if (!on) {
            throw new IllegalArgumentException("start the trial first");
        }
        saveStartLocked();
        save();
    }

    private void saveStartLocked() throws IOException {
        Path fresh = startDir.resolve("station.new");
        deleteTree(fresh);
        Files.createDirectories(fresh);
        long bytes = radio.store().exportStation(station, fresh);
        RadioStories st = radio.stories();
        if (st != null) {
            st.exportStation(station, fresh.resolve("stories"));
        }
        Path cur = startDir.resolve("station");
        Path old = startDir.resolve("station.old");
        deleteTree(old);
        if (Files.exists(cur)) {
            Files.move(cur, old);
        }
        Files.move(fresh, cur);
        deleteTree(old);
        savedBytes = bytes;
        savedAt = clock.getAsLong();
    }

    /** Back to the starting point now (everyone trying it is signed out). */
    public synchronized String reset() throws IOException {
        if (!on) {
            throw new IllegalArgumentException("the trial is off");
        }
        Path dir = startDir.resolve("station");
        sessions.removeUser(user);
        String what = radio.store().restoreStation(station, dir);
        RadioStories st = radio.stories();
        if (st != null && Files.isDirectory(dir.resolve("stories"))) {
            st.restoreStation(station, dir.resolve("stories"));
        }
        try {
            if (users.info(user) == null) {
                users.createUser(user, password, UserStore.Role.CHATTER);
            } else {
                users.setPassword(user, password);
                users.setDisabled(user, false);
            }
        } catch (UserStore.UserException e) {
            System.out.println("[try-it] the trial login could not be set: " + e.getMessage());
        }
        RadioStore.Station s = radio.store().station(station);
        if (s != null && !s.owner.equalsIgnoreCase(user)) {
            radio.store().setAdminFields(station, user, null, 0);
            radio.store().flushIfDirty();
        }
        radio.refresh();
        lastReset = clock.getAsLong();
        lastResult = what;
        save();
        System.out.println("[try-it] back to the starting point: " + what);
        return what;
    }

    /** Switches the trial off: the login stops working; the station stays as it is. */
    public synchronized void stop() {
        if (!on) {
            return;
        }
        sessions.removeUser(user);
        try {
            users.setDisabled(user, true);
        } catch (UserStore.UserException e) {
            // no such account any more
        }
        on = false;
        save();
        System.out.println("[try-it] the trial studio is off");
    }

    // ---- The clock ----

    public synchronized void start() {
        if (timer != null) {
            return;
        }
        timer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "try-it");
            t.setDaemon(true);
            return t;
        });
        timer.scheduleWithFixedDelay(this::tick, 20, 30, TimeUnit.SECONDS);
    }

    /** Resets when the time has come. */
    public void tick() {
        try {
            synchronized (this) {
                if (on && clock.getAsLong() >= nextReset()) {
                    reset();
                }
            }
        } catch (IOException | RuntimeException e) {
            System.out.println("[try-it] reset failed: " + e);
            synchronized (this) {
                lastReset = clock.getAsLong(); // try again next time, not every 30 seconds
                lastResult = "failed: " + e.getMessage();
                save();
            }
        }
    }

    // ---- Web ----

    public void register(HttpServer server) {
        server.createContext("/api/demo", ex -> {
            try {
                send(ex, 200, publicJson());
            } finally {
                ex.close();
            }
        });
        server.createContext("/api/admin/demo/", this::handleAdmin);
    }

    public synchronized String publicJson() {
        if (!on) {
            return "{\"on\":false}";
        }
        RadioStore.Station s = radio.store().station(station);
        return "{\"on\":true,\"user\":" + q(user) + ",\"password\":" + q(password) + ",\"station\":" + station
                + ",\"stationName\":" + q(s == null ? "" : s.name) + ",\"nextReset\":" + nextReset() + ",\"now\":" + clock.getAsLong()
                + ",\"periodMin\":" + (PERIOD_MS / 60000) + ",\"extraMb\":" + (EXTRA_BYTES / 1048576) + "}";
    }

    synchronized String adminJson() {
        StringBuilder sb = new StringBuilder("{\"on\":").append(on).append(",\"user\":").append(q(user))
                .append(",\"password\":").append(q(password)).append(",\"station\":").append(station)
                .append(",\"listed\":").append(listed).append(",\"savedAt\":").append(savedAt)
                .append(",\"savedBytes\":").append(savedBytes).append(",\"lastReset\":").append(lastReset)
                .append(",\"lastResult\":").append(q(lastResult)).append(",\"nextReset\":").append(on ? nextReset() : 0)
                .append(",\"periodMin\":").append(PERIOD_MS / 60000).append(",\"extraMb\":").append(EXTRA_BYTES / 1048576)
                .append(",\"stations\":[");
        int i = 0;
        for (RadioStore.Station s : radio.store().stations()) {
            sb.append(i++ > 0 ? "," : "").append("{\"id\":").append(s.id).append(",\"name\":").append(q(s.name))
                    .append(",\"owner\":").append(q(s.owner)).append('}');
        }
        return sb.append("]}").toString();
    }

    private void handleAdmin(HttpExchange ex) throws IOException {
        try {
            String auth = ex.getRequestHeaders().getFirst("Authorization");
            String token = auth != null && auth.startsWith("Bearer ") ? auth.substring(7).trim() : "";
            SessionStore.Session se = token.isEmpty() ? null : sessions.get(token);
            UserStore.Info me = se == null ? null : users.info(se.username);
            if (me == null || me.role != UserStore.Role.ADMIN) {
                send(ex, 403, "{\"error\":\"admins only\"}");
                return;
            }
            String op = ex.getRequestURI().getPath().substring("/api/admin/demo/".length());
            boolean post = "POST".equalsIgnoreCase(ex.getRequestMethod());
            if (!"status".equals(op) && !post) {
                send(ex, 405, "{\"error\":\"use POST\"}");
                return;
            }
            String body = post ? new String(AudioTools.readAll(ex.getRequestBody()), StandardCharsets.UTF_8) : "";
            try {
                String note = "";
                switch (op) {
                    case "status":
                        break;
                    case "setup": {
                        long id;
                        try {
                            id = Long.parseLong(String.valueOf(MediaAuthHttpServer.queryParam(body, "station")).trim());
                        } catch (NumberFormatException e) {
                            throw new IllegalArgumentException("choose a station");
                        }
                        note = setup(id, MediaAuthHttpServer.queryParam(body, "user"), MediaAuthHttpServer.queryParam(body, "password"),
                                "1".equals(MediaAuthHttpServer.queryParam(body, "listed")), MediaAuthHttpServer.queryParam(body, "name"));
                        break;
                    }
                    case "settings": {
                        String l = MediaAuthHttpServer.queryParam(body, "listed");
                        settings(MediaAuthHttpServer.queryParam(body, "password"), l == null ? null : "1".equals(l));
                        note = "Saved";
                        break;
                    }
                    case "save-start":
                        saveStart();
                        note = "The station as it is now is the new starting point";
                        break;
                    case "reset":
                        note = "Back to the starting point: " + reset();
                        break;
                    case "stop":
                        stop();
                        note = "The trial is off - its login no longer works";
                        break;
                    default:
                        send(ex, 404, "{\"error\":\"not found\"}");
                        return;
                }
                String j = adminJson();
                send(ex, 200, j.substring(0, j.length() - 1) + ",\"note\":" + q(note) + "}");
            } catch (IllegalArgumentException e) {
                send(ex, 400, "{\"error\":" + q(e.getMessage()) + "}");
            }
        } finally {
            ex.close();
        }
    }

    // ---- The gate: the shared login can only use its Studio ----

    /** API addresses the trial login may use (everything else answers 403). Pages and the radio streams are open. */
    static final String[] ALLOWED = {"/api/studio/", "/api/radio/", "/api/demo", "/api/alma/", "/api/news", "/api/me", "/api/logout", "/api/login"};
    /** Studio operations the trial login may not use: buying, DJ logins and the live DJ password, a second station. */
    static final java.util.Set<String> BLOCKED_STUDIO = new java.util.HashSet<String>(java.util.Arrays.asList(
            "request", "pay", "paid", "trial", "billing/portal", "billing", "plan/change", "dj/save", "dj/delete", "live/password",
            "station/create", "station/delete", "station/admin", "station/login-as"));

    /** Why the trial login may not use this address, or null when it may. */
    static String refusal(String path) {
        if (path == null || !path.startsWith("/api/")) {
            return null;
        }
        if (path.startsWith("/api/studio/")) {
            String op = path.substring("/api/studio/".length());
            return BLOCKED_STUDIO.contains(op) ? "Not in the trial - create your own station to use this." : null;
        }
        for (String a : ALLOWED) {
            if (path.equals(a) || (a.endsWith("/") && path.startsWith(a)) || path.startsWith(a + "/")) {
                return null;
            }
        }
        return "This is the shared trial login - it can only use the radio Studio. Create your own free account to use the rest of Memoryq.";
    }

    private final Filter gate = new Filter() {
        @Override
        public void doFilter(HttpExchange ex, Chain chain) throws IOException {
            String why = null;
            if (isOn()) {
                String auth = ex.getRequestHeaders().getFirst("Authorization");
                String token = auth != null && auth.startsWith("Bearer ") ? auth.substring(7).trim() : null;
                if (token == null) {
                    String t = MediaAuthHttpServer.queryParam(ex.getRequestURI().getRawQuery(), "token");
                    token = t == null ? null : t.trim();
                }
                SessionStore.Session s = token == null || token.isEmpty() ? null : sessions.get(token);
                if (s != null && isDemoUser(s.username)) {
                    why = refusal(ex.getRequestURI().getPath());
                }
            }
            if (why == null) {
                chain.doFilter(ex);
                return;
            }
            try {
                send(ex, 403, "{\"error\":" + q(why) + ",\"trial\":true}");
            } finally {
                ex.close();
            }
        }

        @Override
        public String description() {
            return "try-it login gate";
        }
    };

    /** The server with the gate in front of every address registered on it. */
    public HttpServer wrap(HttpServer real) {
        return new Gated(real, gate);
    }

    private static final class Gated extends HttpServer {
        private final HttpServer real;
        private final Filter filter;

        Gated(HttpServer real, Filter filter) {
            this.real = real;
            this.filter = filter;
        }

        @Override
        public void bind(InetSocketAddress addr, int backlog) throws IOException {
            real.bind(addr, backlog);
        }

        @Override
        public void start() {
            real.start();
        }

        @Override
        public void setExecutor(Executor executor) {
            real.setExecutor(executor);
        }

        @Override
        public Executor getExecutor() {
            return real.getExecutor();
        }

        @Override
        public void stop(int delay) {
            real.stop(delay);
        }

        @Override
        public HttpContext createContext(String path, HttpHandler handler) {
            HttpContext c = real.createContext(path, handler);
            c.getFilters().add(filter);
            return c;
        }

        @Override
        public HttpContext createContext(String path) {
            HttpContext c = real.createContext(path);
            c.getFilters().add(filter);
            return c;
        }

        @Override
        public void removeContext(String path) {
            real.removeContext(path);
        }

        @Override
        public void removeContext(HttpContext context) {
            real.removeContext(context);
        }

        @Override
        public InetSocketAddress getAddress() {
            return real.getAddress();
        }
    }

    // ---- Helpers ----

    private static void deleteTree(Path p) throws IOException {
        if (!Files.exists(p)) {
            return;
        }
        List<Path> all = new ArrayList<Path>();
        try (java.util.stream.Stream<Path> w = Files.walk(p)) {
            w.forEach(all::add);
        }
        for (int i = all.size() - 1; i >= 0; i--) {
            Files.deleteIfExists(all.get(i));
        }
    }

    private static String q(String s) {
        return ProfileApi.quote(s == null ? "" : s);
    }

    private static void send(HttpExchange ex, int status, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(status, b.length);
        ex.getResponseBody().write(b);
    }
}