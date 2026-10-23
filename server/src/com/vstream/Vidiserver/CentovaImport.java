package com.vstream.Vidiserver;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Moves Centova Cast stations to Memoryq (Admin - Radio - Import).
 * 1. The admin makes an import code. 2. On the Centova server, centova-export.sh (served at /radio/centova-export.sh)
 * reads the Centova database and sends the accounts, playlists, song list and song files here with that code.
 * 3. The admin checks the preview and imports: every account becomes a station with its own login, songs and playlists,
 * on a new live DJ port, with the account's DJ password, on a free trial.
 *   GET  /api/radio-import/hello|have?track=N             (code)   POST table?name=X | file?track=N&ext=E | done
 *   GET  /api/admin/radio-import/status                   (admin)  POST code | run | clear
 */
public final class CentovaImport {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final long CODE_MS = 48L * 3600 * 1000;
    private static final long MAX_TABLE = 40L * 1024 * 1024;
    private static final long MAX_FILE = 400L * 1024 * 1024;
    /** Imported songs may be longer than uploads (DJ mixes, shows): up to 300 MB each. */
    static final long IMPORT_SONG_BYTES = 300L * 1024 * 1024;
    private static final String[] TABLES = {"accounts", "sources", "tracks", "playlists", "playlist_tracks", "missing", "history"};
    /** Centova's weekday names, in Memoryq's day order (Monday first). */
    private static final String[] WEEK = {"mon", "tue", "wed", "thu", "fri", "sat", "sun"};

    private final RadioService radio;
    private final UserStore users;
    private final SessionStore sessions;
    private final LongSupplier clock;
    private final Path dir;
    private volatile Mailer mailer;
    private volatile EmailService emails;
    private volatile String liveHost = "memoryq.com";

    private String code = "";
    private long codeUntil = 0;
    private volatile boolean running = false;
    private volatile String progress = "";
    private volatile long exportedAt = 0;

    public CentovaImport(RadioService radio, UserStore users, SessionStore sessions, LongSupplier clock, Path dir) {
        this.radio = radio;
        this.users = users;
        this.sessions = sessions;
        this.clock = clock;
        this.dir = dir;
        try {
            Path c = dir.resolve("code.txt");
            if (Files.isRegularFile(c)) {
                String[] f = new String(Files.readAllBytes(c), StandardCharsets.UTF_8).trim().split("\t");
                if (f.length == 2) {
                    code = f[0];
                    codeUntil = Long.parseLong(f[1]);
                }
            }
            Path d = dir.resolve("done.txt");
            if (Files.isRegularFile(d)) {
                exportedAt = Long.parseLong(new String(Files.readAllBytes(d), StandardCharsets.UTF_8).trim().split("\t")[0]);
            }
        } catch (IOException | RuntimeException e) {
            // start without a code
        }
    }

    public void setMailer(Mailer m) {
        this.mailer = m;
    }

    public void setEmails(EmailService e) {
        this.emails = e;
    }

    public void setLiveHost(String h) {
        if (h != null && !h.trim().isEmpty()) {
            liveHost = h.trim();
        }
    }

    public void register(HttpServer server) {
        server.createContext("/api/radio-import/", this::handleCode);
        server.createContext("/api/admin/radio-import/", this::handleAdmin);
        server.createContext("/radio/centova-export.sh", ex -> {
            try {
                byte[] b = CentovaExportScript.TEXT.getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
                ex.getResponseHeaders().set("Cache-Control", "no-store");
                ex.sendResponseHeaders(200, b.length);
                ex.getResponseBody().write(b);
            } finally {
                ex.close();
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

    // ---- The export script talks to these (with the import code) ----

    private void handleCode(HttpExchange ex) throws IOException {
        try {
            String path = ex.getRequestURI().getPath().substring("/api/radio-import/".length());
            String query = ex.getRequestURI().getRawQuery();
            String given = MediaAuthHttpServer.queryParam(query, "code");
            synchronized (this) {
                if (code.isEmpty() || given == null || !java.security.MessageDigest.isEqual(code.getBytes(StandardCharsets.UTF_8),
                        given.trim().getBytes(StandardCharsets.UTF_8)) || clock.getAsLong() > codeUntil) {
                    throw new Bad(403, "wrong or old import code - make a new one in Memoryq Admin - Radio - Import");
                }
            }
            if (running) {
                throw new Bad(409, "an import is running right now - try again when it is finished");
            }
            switch (path) {
                case "hello":
                    send(ex, 200, "{\"ok\":true}");
                    break;
                case "redirects": {
                    byte[] b = redirects().getBytes(StandardCharsets.UTF_8);
                    ex.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
                    ex.sendResponseHeaders(200, b.length);
                    ex.getResponseBody().write(b);
                    break;
                }
                case "table": {
                    String name = String.valueOf(MediaAuthHttpServer.queryParam(query, "name"));
                    if (!java.util.Arrays.asList(TABLES).contains(name)) {
                        throw new Bad(400, "unknown table");
                    }
                    Files.createDirectories(dir);
                    String only = MediaAuthHttpServer.queryParam(query, "only");
                    if (only == null) {
                        save(ex.getRequestBody(), dir.resolve(name + ".tsv"), MAX_TABLE);
                    } else {
                        // only some accounts were sent again: their rows are replaced, the other accounts' rows stay
                        Path fresh = dir.resolve(name + ".tsv.new");
                        save(ex.getRequestBody(), fresh, MAX_TABLE);
                        merge(name, fresh, csv(only), csv(MediaAuthHttpServer.queryParam(query, "users")));
                    }
                    send(ex, 200, "{\"ok\":true}");
                    break;
                }
                case "staged": {
                    // for the export script: which accounts were sent before / are stations already
                    byte[] b = stagedText().getBytes(StandardCharsets.UTF_8);
                    ex.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
                    ex.sendResponseHeaders(200, b.length);
                    ex.getResponseBody().write(b);
                    break;
                }
                case "have": {
                    Path f = staged(id(MediaAuthHttpServer.queryParam(query, "track")));
                    byte[] b = String.valueOf(f == null ? 0 : Files.size(f)).getBytes(StandardCharsets.UTF_8);
                    ex.getResponseHeaders().set("Content-Type", "text/plain");
                    ex.sendResponseHeaders(200, b.length);
                    ex.getResponseBody().write(b);
                    break;
                }
                case "file": {
                    long track = id(MediaAuthHttpServer.queryParam(query, "track"));
                    String ext = String.valueOf(MediaAuthHttpServer.queryParam(query, "ext")).toLowerCase(Locale.ROOT);
                    if (!RadioStore.AUDIO_TYPES.contains(ext)) {
                        drain(ex.getRequestBody());
                        throw new Bad(400, "not a sound file: ." + ext);
                    }
                    Path media = dir.resolve("media");
                    Files.createDirectories(media);
                    Path old = staged(track);
                    if (old != null) {
                        Files.deleteIfExists(old);
                    }
                    save(ex.getRequestBody(), media.resolve(track + "." + ext), MAX_FILE);
                    send(ex, 200, "{\"ok\":true}");
                    break;
                }
                case "done": {
                    exportedAt = clock.getAsLong();
                    String counts = "sent " + MediaAuthHttpServer.queryParam(query, "sent") + ", already there "
                            + MediaAuthHttpServer.queryParam(query, "skipped") + ", not found " + MediaAuthHttpServer.queryParam(query, "missing");
                    Files.write(dir.resolve("done.txt"), (exportedAt + "\t" + counts).getBytes(StandardCharsets.UTF_8));
                    System.out.println("[import] Centova export received: " + counts);
                    send(ex, 200, "{\"ok\":true}");
                    break;
                }
                default:
                    throw new Bad(404, "not found");
            }
        } catch (Bad e) {
            send(ex, e.status, "{\"error\":" + q(e.getMessage()) + "}");
        } catch (Exception e) {
            send(ex, 500, "{\"error\":" + q("server error: " + e.getMessage()) + "}");
        } finally {
            ex.close();
        }
    }

    private static java.util.Set<String> csv(String v) {
        java.util.Set<String> out = new java.util.HashSet<String>();
        for (String x : (v == null ? "" : v).split(",")) {
            if (!x.trim().isEmpty()) {
                out.add(x.trim());
            }
        }
        return out;
    }

    /** Replaces the rows of the given accounts in a staged table with the new rows (the rest stay). */
    private void merge(String name, Path fresh, java.util.Set<String> ids, java.util.Set<String> usernames) throws IOException {
        Path f = dir.resolve(name + ".tsv");
        Map<String, String> listOwner = new HashMap<String, String>();
        if ("playlist_tracks".equals(name) && Files.isRegularFile(dir.resolve("playlists.tsv"))) {
            for (String line : new String(Files.readAllBytes(dir.resolve("playlists.tsv")), StandardCharsets.UTF_8).split("\n")) {
                String[] v = line.split("\t", -1);
                if (v.length > 1) {
                    listOwner.put(v[0], v[1]);
                }
            }
        }
        StringBuilder sb = new StringBuilder();
        if (Files.isRegularFile(f)) {
            for (String line : new String(Files.readAllBytes(f), StandardCharsets.UTF_8).split("\n")) {
                if (line.isEmpty()) {
                    continue;
                }
                String[] v = line.split("\t", -1);
                boolean theirs;
                switch (name) {
                    case "accounts":
                    case "history":
                        theirs = ids.contains(v[0]);
                        break;
                    case "sources":
                        theirs = usernames.contains(v[0]);
                        break;
                    case "missing":
                        theirs = v.length > 1 && usernames.contains(v[1]);
                        break;
                    case "playlist_tracks": {
                        String owner = listOwner.get(v[0]);
                        theirs = owner == null || ids.contains(owner);
                        break;
                    }
                    default: // tracks, playlists
                        theirs = v.length > 1 && ids.contains(v[1]);
                }
                if (!theirs) {
                    sb.append(line).append('\n');
                }
            }
        }
        sb.append(new String(Files.readAllBytes(fresh), StandardCharsets.UTF_8));
        Files.write(f, sb.toString().getBytes(StandardCharsets.UTF_8));
        Files.deleteIfExists(fresh);
    }

    /** One line per staged account: username, songs waiting here, the station it became (0) and that station's name. */
    private String stagedText() throws IOException {
        Data d = load();
        Map<String, Ledger> imported = ledger();
        StringBuilder sb = new StringBuilder();
        for (Map<String, String> a : d.accounts) {
            long aid = num(a.get("id"));
            int files = 0;
            for (Map<String, String> t : d.tracks) {
                if (num(t.get("accountid")) == aid && staged(num(t.get("id"))) != null) {
                    files++;
                }
            }
            Ledger l = imported.get(a.get("username"));
            RadioStore.Station st = l == null ? null : radio.store().station(l.station);
            sb.append(a.get("username")).append('\t').append(files).append('\t').append(st == null ? 0 : st.id).append('\t')
                    .append(st == null ? "" : st.name.replace('\t', ' ')).append('\n');
        }
        for (Map.Entry<String, Ledger> e : imported.entrySet()) {
            boolean listed = false;
            for (Map<String, String> a : d.accounts) {
                listed |= a.get("username").equals(e.getKey());
            }
            RadioStore.Station st = radio.store().station(e.getValue().station);
            if (!listed && st != null) {
                sb.append(e.getKey()).append("\t0\t").append(st.id).append('\t').append(st.name.replace('\t', ' ')).append('\n');
            }
        }
        return sb.toString();
    }

    private static void save(InputStream in, Path to, long max) throws IOException, Bad {
        Path part = to.resolveSibling(to.getFileName() + ".part");
        long total = 0;
        try (OutputStream out = Files.newOutputStream(part)) {
            byte[] b = new byte[65536];
            int n;
            while ((n = in.read(b)) > 0) {
                total += n;
                if (total > max) {
                    throw new Bad(413, "too big");
                }
                out.write(b, 0, n);
            }
        } catch (Bad | IOException e) {
            Files.deleteIfExists(part);
            throw e;
        }
        Files.move(part, to, StandardCopyOption.REPLACE_EXISTING);
    }

    private static void drain(InputStream in) throws IOException {
        byte[] b = new byte[65536];
        long total = 0;
        int n;
        while ((n = in.read(b)) > 0 && total < MAX_FILE) {
            total += n;
        }
    }

    /** The staged file of a Centova song, or null. */
    private Path staged(long track) throws IOException {
        Path media = dir.resolve("media");
        if (!Files.isDirectory(media)) {
            return null;
        }
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(media, track + ".*")) {
            for (Path p : ds) {
                if (!p.getFileName().toString().endsWith(".part")) {
                    return p;
                }
            }
        }
        return null;
    }

    // ---- The admin ----

    private void handleAdmin(HttpExchange ex) throws IOException {
        try {
            String auth = ex.getRequestHeaders().getFirst("Authorization");
            String token = auth != null && auth.startsWith("Bearer ") ? auth.substring(7).trim() : "";
            SessionStore.Session s = token.isEmpty() ? null : sessions.get(token);
            UserStore.Info me = s == null ? null : users.info(s.username);
            if (me == null || me.role != UserStore.Role.ADMIN) {
                throw new Bad(403, "admins only");
            }
            String path = ex.getRequestURI().getPath().substring("/api/admin/radio-import/".length());
            boolean post = "POST".equalsIgnoreCase(ex.getRequestMethod());
            switch (path) {
                case "status":
                    send(ex, 200, statusJson(ex));
                    break;
                case "code": {
                    requirePost(post);
                    synchronized (this) {
                        byte[] b = new byte[15];
                        RANDOM.nextBytes(b);
                        code = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(b).replace('-', 'x').replace('_', 'y');
                        codeUntil = clock.getAsLong() + CODE_MS;
                        Files.createDirectories(dir);
                        Files.write(dir.resolve("code.txt"), (code + "\t" + codeUntil).getBytes(StandardCharsets.UTF_8));
                    }
                    send(ex, 200, statusJson(ex));
                    break;
                }
                case "run": {
                    requirePost(post);
                    String body = readBody(ex, 64 * 1024);
                    start(body);
                    send(ex, 200, "{\"ok\":true}");
                    break;
                }
                case "clear": {
                    requirePost(post);
                    if (running) {
                        throw new Bad(409, "an import is running");
                    }
                    deleteTree(dir.resolve("media"));
                    for (String t : TABLES) {
                        Files.deleteIfExists(dir.resolve(t + ".tsv"));
                    }
                    Files.deleteIfExists(dir.resolve("done.txt"));
                    exportedAt = 0;
                    send(ex, 200, "{\"ok\":true}");
                    break;
                }
                default:
                    throw new Bad(404, "not found");
            }
        } catch (Bad e) {
            send(ex, e.status, "{\"error\":" + q(e.getMessage()) + "}");
        } catch (IllegalArgumentException e) {
            send(ex, 400, "{\"error\":" + q(e.getMessage()) + "}");
        } catch (Exception e) {
            send(ex, 500, "{\"error\":" + q("server error: " + e.getMessage()) + "}");
        } finally {
            ex.close();
        }
    }

    /** What arrived from Centova, and what each account would become. */
    String statusJson(HttpExchange ex) throws IOException {
        String host = ex == null ? "memoryq.com" : String.valueOf(ex.getRequestHeaders().getFirst("Host"));
        if (!host.matches("[A-Za-z0-9.\\-]+(:[0-9]{1,5})?")) {
            host = "memoryq.com";
        }
        String site = (host.startsWith("localhost") || host.startsWith("127.") ? "http://" : "https://") + host;
        StringBuilder sb = new StringBuilder("{");
        synchronized (this) {
            boolean valid = !code.isEmpty() && clock.getAsLong() < codeUntil;
            sb.append("\"code\":").append(q(valid ? code : "")).append(",\"codeUntil\":").append(valid ? codeUntil : 0);
            sb.append(",\"command\":").append(q(valid ? "cd /usr/local/centovacast && curl -fsSL " + site + "/radio/centova-export.sh -o mq-export.sh && sudo bash mq-export.sh "
                    + code + (site.startsWith("https://memoryq.com") ? "" : " " + site) : ""));
        }
        sb.append(",\"exportedAt\":").append(exportedAt);
        String counts = "";
        if (Files.isRegularFile(dir.resolve("done.txt"))) {
            String[] f = new String(Files.readAllBytes(dir.resolve("done.txt")), StandardCharsets.UTF_8).split("\t");
            counts = f.length > 1 ? f[1].trim() : "";
        }
        sb.append(",\"counts\":").append(q(counts));
        sb.append(",\"running\":").append(running).append(",\"progress\":").append(q(progress));
        sb.append(",\"accounts\":[");
        Data d = load();
        RadioStore store = radio.store();
        Map<String, Ledger> imported = ledger();
        boolean first = true;
        for (Map<String, String> a : d.accounts) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            long aid = num(a.get("id"));
            String user = a.get("username");
            String wanted = loginFor(user);
            String login = safeLogin(wanted, a.get("email"));
            String loginNote = login.equals(wanted) ? "" : "the Memoryq username " + wanted + " belongs to someone else - this station gets " + login;
            UserStore.Info have = login.isEmpty() ? null : users.info(login);
            int songs = 0, files = 0;
            long bytes = 0;
            for (Map<String, String> t : d.tracks) {
                if (num(t.get("accountid")) == aid) {
                    songs++;
                    Path f = staged(num(t.get("id")));
                    if (f != null) {
                        files++;
                        bytes += Files.size(f);
                    }
                }
            }
            String name = nameFor(a);
            boolean taken = false;
            for (RadioStore.Station s : store.stations()) {
                taken |= s.name.equalsIgnoreCase(name);
            }
            Ledger led = imported.get(user);
            RadioStore.Station was = led == null ? null : store.station(led.station);
            if (was != null) {
                sb.append("{\"imported\":{\"station\":").append(was.id).append(",\"name\":").append(q(was.name)).append(",\"owner\":")
                        .append(q(was.owner)).append(",\"at\":").append(led.at).append(",\"songs\":").append(store.tracks(was.id).size())
                        .append(",\"playlists\":").append(store.playlists(was.id).size()).append("},");
            } else {
                sb.append("{\"imported\":null,").append("\"importedGone\":").append(led != null).append(',');
            }
            sb.append("\"id\":").append(aid).append(",\"username\":").append(q(user)).append(",\"title\":").append(q(a.get("title")))
                    .append(",\"name\":").append(q(name)).append(",\"nameTaken\":").append(taken)
                    .append(",\"login\":").append(q(login)).append(",\"loginExists\":").append(have != null)
                    .append(",\"loginNote\":").append(q(loginNote)).append(",\"centovaStatus\":").append(q(a.get("status")))
                    .append(",\"warnings\":").append(warningsJson(a))
                    .append(",\"loginIsAdmin\":").append(have != null && have.role == UserStore.Role.ADMIN)
                    .append(",\"email\":").append(q(a.get("email"))).append(",\"website\":").append(q(a.get("url")))
                    .append(",\"oldPort\":").append(num(a.get("port"))).append(",\"listeners\":").append(num(a.get("maxclients")))
                    .append(",\"kbps\":").append(num(a.get("maxbitrate"))).append(",\"stream\":").append(q(a.get("servertype")))
                    .append(",\"songs\":").append(songs).append(",\"files\":").append(files).append(",\"bytes\":").append(bytes)
                    .append(",\"djPass\":").append(!validPass(d.sources.get(user)).isEmpty())
                    .append(",\"playlists\":[");
            boolean pf = true;
            for (Map<String, String> p : d.playlists) {
                if (num(p.get("accountid")) != aid) {
                    continue;
                }
                int n = 0;
                for (long[] pt : d.playlistTracks) {
                    n += pt[0] == num(p.get("id")) ? 1 : 0;
                }
                sb.append(pf ? "" : ",").append("{\"title\":").append(q(p.get("title"))).append(",\"type\":").append(q(p.get("type")))
                        .append(",\"enabled\":").append("enabled".equals(p.get("status"))).append(",\"songs\":").append(n)
                        .append(",\"rule\":").append(q(rule(p))).append('}');
                pf = false;
            }
            sb.append("]}");
        }
        sb.append("],\"missing\":").append(d.missing).append(",\"result\":");
        Path res = dir.resolve("result.json");
        sb.append(Files.isRegularFile(res) ? new String(Files.readAllBytes(res), StandardCharsets.UTF_8) : "null");
        return sb.append('}').toString();
    }

    /**
     * The login for an account: the Centova username, unless a different person already has it on Memoryq
     * (an existing account is used only when its e-mail is the Centova account's e-mail).
     */
    String safeLogin(String wanted, String centovaEmail) {
        if (wanted.isEmpty() || users.info(wanted) == null || sameOwner(wanted, centovaEmail)) {
            return wanted;
        }
        String base = wanted.length() > 18 ? wanted.substring(0, 18) : wanted;
        for (String v : new String[] {base + "radio", base + "fm"}) {
            if (users.info(v) == null || sameOwner(v, centovaEmail)) {
                return v;
            }
        }
        for (int i = 2; i < 100; i++) {
            if (users.info(base + i) == null) {
                return base + i;
            }
        }
        return wanted;
    }

    private boolean sameOwner(String login, String centovaEmail) {
        EmailService es = emails;
        EmailService.Entry e = es == null ? null : es.get(login);
        String c = centovaEmail == null ? "" : centovaEmail.trim();
        return e != null && !c.isEmpty() && e.email.equalsIgnoreCase(c);
    }

    /** What works differently on Memoryq for this account - shown before the import and kept with the result. */
    static List<String> warnings(Map<String, String> a) {
        List<String> w = new ArrayList<String>();
        long kbps = num(a.get("maxbitrate"));
        if (kbps > 320) {
            w.add("quality " + kbps + " kbps becomes 320 kbps (the most Memoryq streams)");
        }
        String host = a.get("hostname") == null ? "" : a.get("hostname").trim();
        if (!host.isEmpty() && !host.matches("[0-9.]+")) {
            w.add("listeners use the address " + host + ":" + num(a.get("port")) + " - send them the new link, or use the old-link redirect after moving");
        }
        if ("1".equals(a.get("reseller"))) {
            w.add("this is a Centova reseller account - Memoryq has no resellers: its own stations are imported one by one");
        }
        if (a.get("logofile") != null && !a.get("logofile").trim().isEmpty()) {
            w.add("its logo is not copied - upload it again");
        }
        if (num(a.get("mountlimit")) > 1) {
            w.add("Centova allowed " + num(a.get("mountlimit")) + " mount points - Memoryq has one stream per station");
        }
        String src = a.get("sourcetype") == null ? "" : a.get("sourcetype").trim().toLowerCase(Locale.ROOT);
        if (src.contains("shout")) {
            w.add("the live DJ software must use the Icecast server type on Memoryq (Centova used " + a.get("sourcetype") + ")");
        }
        if ("0".equals(a.get("allowrequests"))) {
            w.add("song requests were off in Centova - on Memoryq signed-in listeners can request songs");
        }
        return w;
    }

    private static String warningsJson(Map<String, String> a) {
        StringBuilder sb = new StringBuilder("[");
        List<String> w = warnings(a);
        for (int i = 0; i < w.size(); i++) {
            sb.append(i > 0 ? "," : "").append(q(w.get(i)));
        }
        return sb.append(']').toString();
    }

    // ---- Listening history (Centova's hourly statistics, added up per day by the export script) ----

    /** Saves the account's days; returns how many. Days outside 2005..tomorrow are left out (unreadable dates). */
    int saveHistory(Data d, long accountId, long station) throws IOException {
        long minDay = 12784; // 2005-01-01
        long maxDay = clock.getAsLong() / 86400000L + 1;
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (Map<String, String> h : d.history) {
            long day = num(h.get("day"));
            if (num(h.get("accountid")) != accountId || day < minDay || day > maxDay) {
                continue;
            }
            sb.append(day).append('\t').append(num(h.get("peak"))).append('\t').append(num(h.get("minutes"))).append('\t')
                    .append(num(h.get("kb"))).append('\t').append(num(h.get("songs"))).append('\n');
            n++;
        }
        if (n > 0) {
            Files.createDirectories(dir);
            Files.write(dir.resolve("history-" + station + ".tsv"), sb.toString().getBytes(StandardCharsets.UTF_8));
        }
        return n;
    }

    /** A station's Centova history added up per month (newest first), or null when it has none. */
    public String historyJson(long station) {
        Path f = dir.resolve("history-" + station + ".tsv");
        if (!Files.isRegularFile(f)) {
            return null;
        }
        java.util.TreeMap<String, long[]> months = new java.util.TreeMap<String, long[]>(java.util.Collections.reverseOrder());
        try {
            for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                String[] v = line.split("\t");
                if (v.length < 5) {
                    continue;
                }
                String m = java.time.LocalDate.ofEpochDay(Long.parseLong(v[0])).toString().substring(0, 7);
                long[] t = months.computeIfAbsent(m, k -> new long[5]);
                t[0] = Math.max(t[0], Long.parseLong(v[1]));
                t[1] += Long.parseLong(v[2]);
                t[2] += Long.parseLong(v[3]);
                t[3] += Long.parseLong(v[4]);
                t[4]++;
            }
        } catch (IOException | RuntimeException e) {
            return null;
        }
        StringBuilder sb = new StringBuilder("[");
        int i = 0;
        for (Map.Entry<String, long[]> e : months.entrySet()) {
            long[] t = e.getValue();
            sb.append(i++ > 0 ? "," : "").append("{\"month\":").append(q(e.getKey())).append(",\"peak\":").append(t[0])
                    .append(",\"hours\":").append(Math.round(t[1] / 6.0) / 10.0).append(",\"mb\":").append(t[2] / 1024)
                    .append(",\"songs\":").append(t[3]).append(",\"days\":").append(t[4]).append('}');
        }
        return sb.append(']').toString();
    }

    /** "old port, new listening address" for every imported station (the old-link redirect on the Centova server). */
    String redirects() throws IOException {
        Map<String, Ledger> imported = ledger();
        if (imported.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Map<String, String> a : table("accounts", "id", "username", "title", "organization", "url", "email", "maxclients", "maxbitrate",
                "transferlimit", "diskquota", "status", "timezone", "genre", "port", "servertype")) {
            String user = a.get("username");
            Ledger l = imported.get(user);
            long port = num(a.get("port"));
            if (l == null || port <= 0 || radio.store().station(l.station) == null) {
                continue;
            }
            String was = a.get("status").matches("enabled|disabled|suspended") ? a.get("status") : "enabled";
            sb.append(port).append('\t').append("https://").append(liveHost).append("/radio/").append(l.station).append(".mp3")
                    .append('\t').append(user.replaceAll("[^A-Za-z0-9_.-]", "")).append('\t').append(was).append('\n');
        }
        return sb.toString();
    }

    private static String loginFor(String centovaUser) {
        String u = centovaUser == null ? "" : centovaUser.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "");
        if (u.length() > 24) {
            u = u.substring(0, 24);
        }
        return u.length() < 3 ? (u + "radio").substring(0, Math.min(24, u.length() + 5)) : u;
    }

    private static String nameFor(Map<String, String> a) {
        String t = RadioStore.clean(a.get("title"), 40);
        return t.isEmpty() ? RadioStore.clean(a.get("username"), 40) : t;
    }

    private static String validPass(String p) {
        return p != null && p.matches("[A-Za-z0-9]{8,40}") ? p : "";
    }

    /** A Centova playlist rule in words (for the preview). */
    private static String rule(Map<String, String> p) {
        String type = p.get("type");
        if ("general".equals(type)) {
            String from = hhmm(p.get("general_starttime")), to = hhmm(p.get("general_endtime"));
            return "rotation, weight " + num(p.get("general_weight")) + ("sequential".equals(p.get("general_order")) ? ", in order" : "")
                    + (from.equals(to) ? "" : ", " + from + "-" + to + " only");
        }
        if ("interval".equals(type)) {
            return ("onerandom".equals(p.get("interval_style")) ? "one song every " : "all its songs (a break) every ") + num(p.get("interval_length")) + " "
                    + ("minutes".equals(p.get("interval_type")) ? "minutes" : "songs");
        }
        if ("scheduled".equals(type)) {
            return "scheduled " + p.get("scheduled_repeat") + " from " + p.get("scheduled_datetime")
                    + (p.get("scheduled_weekdays").isEmpty() ? "" : " (" + p.get("scheduled_weekdays") + ")");
        }
        if ("now".equals(type)) {
            return "immediate";
        }
        return type + " (not used by Memoryq)";
    }

    // ---- The data that came from Centova ----

    static final class Data {
        final List<Map<String, String>> accounts = new ArrayList<Map<String, String>>();
        final List<Map<String, String>> tracks = new ArrayList<Map<String, String>>();
        final List<Map<String, String>> playlists = new ArrayList<Map<String, String>>();
        final List<long[]> playlistTracks = new ArrayList<long[]>();
        final Map<String, String> sources = new HashMap<String, String>();
        final List<Map<String, String>> history = new ArrayList<Map<String, String>>();
        int missing;
    }

    Data load() throws IOException {
        Data d = new Data();
        List<Map<String, String>> acc = table("accounts", "id", "username", "title", "organization", "url", "email", "maxclients", "maxbitrate",
                "transferlimit", "diskquota", "status", "timezone", "genre", "port", "servertype",
                // added later: older exports do not have these, so they are empty then
                "allowrequests", "crossfade", "recenttracks", "songlengthlimit", "cachedtransfer", "logofile", "hostname", "charset",
                "usesource", "allowproxy", "reseller", "sourcetype", "mountlimit", "samplerate", "channels");
        Map<Long, Integer> trackCount = new HashMap<Long, Integer>();
        d.tracks.addAll(table("tracks", "id", "accountid", "pathname", "title", "artist", "album", "length", "filesize"));
        for (Map<String, String> t : d.tracks) {
            trackCount.merge(num(t.get("accountid")), 1, Integer::sum);
        }
        for (Map<String, String> a : acc) {
            // stream accounts only (the Centova admin / reseller rows have no stream and no songs)
            if (!a.get("servertype").isEmpty() || trackCount.containsKey(num(a.get("id")))) {
                d.accounts.add(a);
            }
        }
        d.playlists.addAll(table("playlists", "id", "accountid", "title", "type", "scheduled_datetime", "scheduled_repeat",
                "scheduled_weekdays", "interval_type", "interval_length", "general_weight", "status", "general_order",
                "scheduled_duration", "general_starttime", "general_endtime",
                // added later (older exports: empty)
                "interval_style", "scheduled_style", "scheduled_monthdays", "scheduled_interruptible", "track_interruptible"));
        for (Map<String, String> r : table("playlist_tracks", "playlistid", "trackid")) {
            d.playlistTracks.add(new long[] {num(r.get("playlistid")), num(r.get("trackid"))});
        }
        for (Map<String, String> r : table("sources", "username", "password")) {
            d.sources.put(r.get("username"), r.get("password"));
        }
        d.missing = table("missing", "track").size();
        d.history.addAll(table("history", "accountid", "day", "peak", "minutes", "kb", "songs"));
        return d;
    }

    /** One table as mysql --batch wrote it (tabs between fields, \t \n \\ escaped, NULL for nothing). */
    private List<Map<String, String>> table(String name, String... cols) throws IOException {
        List<Map<String, String>> out = new ArrayList<Map<String, String>>();
        Path f = dir.resolve(name + ".tsv");
        if (!Files.isRegularFile(f)) {
            return out;
        }
        for (String line : new String(Files.readAllBytes(f), StandardCharsets.UTF_8).split("\n")) {
            if (line.isEmpty()) {
                continue;
            }
            String[] v = line.split("\t", -1);
            Map<String, String> m = new LinkedHashMap<String, String>();
            for (int i = 0; i < cols.length; i++) {
                String x = i < v.length ? unescape(v[i]) : "";
                m.put(cols[i], "NULL".equals(x) ? "" : x);
            }
            out.add(m);
        }
        return out;
    }

    static String unescape(String s) {
        if (s.indexOf('\\') < 0) {
            return s.replace("\r", "");
        }
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                b.append(n == 't' ? '\t' : n == 'n' ? '\n' : n == '0' ? ' ' : n);
            } else if (c != '\r') {
                b.append(c);
            }
        }
        return b.toString();
    }

    // ---- The import ----

    private synchronized void start(String body) throws Bad, IOException {
        if (running) {
            throw new Bad(409, "an import is running already");
        }
        Data d = load();
        if (d.accounts.isEmpty()) {
            throw new Bad(409, "nothing has arrived from Centova yet - run the export on the Centova server first");
        }
        String pick = MediaAuthHttpServer.queryParam(body, "accounts");
        List<Long> chosen = new ArrayList<Long>();
        for (String x : (pick == null ? "" : pick).split(",")) {
            if (x.trim().matches("\\d{1,9}")) {
                chosen.add(Long.parseLong(x.trim()));
            }
        }
        if (chosen.isEmpty()) {
            throw new Bad(400, "tick at least one account");
        }
        int trial = 7;
        String td = MediaAuthHttpServer.queryParam(body, "trialDays");
        if (td != null && td.matches("\\d{1,3}")) {
            trial = Math.max(1, Math.min(365, Integer.parseInt(td)));
        }
        Map<Long, String[]> overrides = new HashMap<Long, String[]>();
        for (long id : chosen) {
            overrides.put(id, new String[] {MediaAuthHttpServer.queryParam(body, "name" + id), MediaAuthHttpServer.queryParam(body, "login" + id)});
        }
        // accounts that are stations already: the admin must say what happens (nothing is overwritten by default)
        Map<String, Ledger> imported = ledger();
        Map<Long, String> modes = new HashMap<Long, String>();
        Map<Long, List<String>> parts = new HashMap<Long, List<String>>();
        List<String> problems = new ArrayList<String>();
        for (Map<String, String> a : d.accounts) {
            long id = num(a.get("id"));
            if (!chosen.contains(id)) {
                continue;
            }
            Ledger led = imported.get(a.get("username"));
            RadioStore.Station was = led == null ? null : radio.store().station(led.station);
            String m = MediaAuthHttpServer.queryParam(body, "mode" + id);
            m = m == null ? "" : m.trim();
            if (was == null) {
                modes.put(id, "new");
                continue;
            }
            if (!m.matches("skip|overwrite|new")) {
                problems.add(a.get("username") + " is already on Memoryq as station " + was.id + " (" + was.name + ")");
                continue;
            }
            modes.put(id, m);
            if ("overwrite".equals(m)) {
                List<String> ps = new ArrayList<String>();
                String given = MediaAuthHttpServer.queryParam(body, "parts" + id);
                for (String x : (given == null ? "" : given).split(",")) {
                    if (ALL_PARTS.contains(x.trim()) && !ps.contains(x.trim())) {
                        ps.add(x.trim());
                    }
                }
                if (ps.isEmpty()) {
                    problems.add(a.get("username") + ": tick what to overwrite (songs, playlists, settings, history)");
                }
                parts.put(id, ps);
            }
        }
        if (!problems.isEmpty()) {
            throw new Bad(409, String.join("; ", problems) + " - choose Skip, Overwrite or New copy for it");
        }
        final boolean email = !"false".equals(MediaAuthHttpServer.queryParam(body, "email"));
        final int trialDays = trial;
        running = true;
        progress = "starting...";
        Thread t = new Thread(() -> {
            try {
                run(d, chosen, overrides, trialDays, email, modes, parts);
            } catch (Exception e) {
                progress = "stopped: " + e.getMessage();
                System.out.println("[import] stopped: " + e);
            } finally {
                running = false;
            }
        }, "centova-import");
        t.setDaemon(true);
        t.start();
    }

    /** One finished account, for the result list. */
    private static final class Done {
        String centova;
        long station;
        String name = "";
        String login = "";
        String password = "";
        boolean newAccount;
        int port;
        String livePass = "";
        int songs;
        int playlists;
        List<String> notes = new ArrayList<String>();
        String error = "";
        /** new, overwrite or skip. */
        String mode = "new";
    }

    /** One account imported earlier: its station, when, and the playlists the import made. */
    static final class Ledger {
        long station;
        long at;
        List<Long> playlists = new ArrayList<Long>();
        /** The account's old Centova port (listeners' old links: http://old-server:PORT/...), 0 = unknown. */
        int port;
    }

    /** Centova username -> what was imported (also from older imports' result list). */
    synchronized Map<String, Ledger> ledger() throws IOException {
        Map<String, Ledger> out = new HashMap<String, Ledger>();
        Path f = dir.resolve("imported.tsv");
        if (Files.isRegularFile(f)) {
            for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                String[] v = line.split("\t", -1);
                if (v.length >= 4 && v[1].matches("\\d+")) {
                    Ledger l = new Ledger();
                    l.station = Long.parseLong(v[1]);
                    l.at = num(v[2]);
                    for (String x : v[3].split(",")) {
                        if (x.matches("\\d+")) {
                            l.playlists.add(Long.parseLong(x));
                        }
                    }
                    if (v.length >= 5 && v[4].matches("\\d{1,5}")) {
                        l.port = Integer.parseInt(v[4]);
                    }
                    out.put(v[0], l);
                }
            }
            return out;
        }
        Path res = dir.resolve("result.json");
        if (Files.isRegularFile(res)) {
            try {
                Object root = MiniJson.parse(new String(Files.readAllBytes(res), StandardCharsets.UTF_8));
                Object list = MiniJson.at(root, "stations");
                long at = MiniJson.num(root, "at");
                if (list instanceof List) {
                    for (Object o : (List<?>) list) {
                        if (MiniJson.num(o, "station") > 0 && MiniJson.str(o, "error").isEmpty()) {
                            Ledger l = new Ledger();
                            l.station = MiniJson.num(o, "station");
                            l.at = at;
                            out.put(MiniJson.str(o, "centova"), l);
                        }
                    }
                }
            } catch (RuntimeException e) {
                // an unreadable old result: nothing known
            }
        }
        return out;
    }

    /**
     * The imported accounts for the old links: Centova username -> {station, old port}. The port comes from the
     * import list, or from the copied Centova accounts for imports made before ports were kept.
     */
    public synchronized Map<String, long[]> importedStations() throws IOException {
        Map<String, Long> ports = new HashMap<String, Long>();
        for (Map<String, String> a : table("accounts", "id", "username", "title", "organization", "url", "email", "maxclients", "maxbitrate",
                "transferlimit", "diskquota", "status", "timezone", "genre", "port", "servertype")) {
            ports.put(a.get("username"), num(a.get("port")));
        }
        Map<String, long[]> out = new LinkedHashMap<String, long[]>();
        for (Map.Entry<String, Ledger> e : ledger().entrySet()) {
            long port = e.getValue().port > 0 ? e.getValue().port : ports.getOrDefault(e.getKey(), 0L);
            out.put(e.getKey(), new long[] {e.getValue().station, port});
        }
        return out;
    }

    /** Notes an imported account and its song map (for a later overwrite). */
    synchronized void remember(String user, long station, List<Long> playlists, Map<Long, Long> songs, int port) throws IOException {
        Map<String, Ledger> all = ledger();
        Ledger l = new Ledger();
        l.station = station;
        l.at = clock.getAsLong();
        l.playlists.addAll(playlists);
        l.port = port;
        all.put(user, l);
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Ledger> e : all.entrySet()) {
            StringBuilder ps = new StringBuilder();
            for (Long x : e.getValue().playlists) {
                ps.append(ps.length() > 0 ? "," : "").append(x);
            }
            sb.append(e.getKey().replaceAll("[\\t\\n]", "")).append('\t').append(e.getValue().station).append('\t').append(e.getValue().at)
                    .append('\t').append(ps).append('\t').append(e.getValue().port).append('\n');
        }
        Files.createDirectories(dir);
        Files.write(dir.resolve("imported.tsv"), sb.toString().getBytes(StandardCharsets.UTF_8));
        StringBuilder m = new StringBuilder();
        for (Map.Entry<Long, Long> e : songs.entrySet()) {
            m.append(e.getKey()).append('\t').append(e.getValue()).append('\n');
        }
        Files.write(dir.resolve("songs-" + station + ".tsv"), m.toString().getBytes(StandardCharsets.UTF_8));
    }

    private Map<Long, Long> songMap(long station) throws IOException {
        Map<Long, Long> m = new HashMap<Long, Long>();
        Path f = dir.resolve("songs-" + station + ".tsv");
        if (Files.isRegularFile(f)) {
            for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                String[] v = line.split("\t");
                if (v.length == 2 && v[0].matches("\\d+") && v[1].matches("\\d+")) {
                    m.put(Long.parseLong(v[0]), Long.parseLong(v[1]));
                }
            }
        }
        return m;
    }

    /**
     * Copies Centova again into a station imported before - only the parts the admin ticked. The login, its
     * password, the live DJ port and password, the plan and the station's name are never changed.
     */
    private void updateAccount(Data d, Map<String, String> a, RadioStore.Station s, Ledger led, List<String> parts, Done r, int ai, int of)
            throws Exception {
        RadioStore store = radio.store();
        progress = "account " + ai + " of " + of + ": " + s.name + " (overwriting " + String.join(", ", parts) + ")";
        r.station = s.id;
        r.name = s.name;
        r.login = s.owner;
        r.port = s.livePort;
        r.livePass = s.livePass;
        r.notes.add("overwritten: " + String.join(", ", parts) + " (login, passwords, live DJ port and plan unchanged)");
        if (parts.contains("settings")) {
            String mail = a.get("email").trim();
            try {
                store.setContact(s.id, a.get("organization"), Mailer.validAddress(mail.toLowerCase(Locale.ROOT)) ? mail : s.email);
            } catch (IllegalArgumentException e) {
                r.notes.add("contact details not copied");
            }
            String tz = a.get("timezone").trim();
            if (!tz.isEmpty()) {
                try {
                    store.setTimezone(s.id, tz);
                } catch (IllegalArgumentException e) {
                    r.notes.add("time zone " + tz + " not copied");
                }
            }
            int listeners = (int) Math.min(100000, num(a.get("maxclients")));
            int kbps = nearestKbps((int) num(a.get("maxbitrate")));
            int storageMb = (int) Math.min(10000000, num(a.get("diskquota")));
            long xferMb = num(a.get("transferlimit"));
            int transferGb = xferMb <= 0 ? 0 : (int) Math.min(1000000, (xferMb + 1023) / 1024);
            store.setLimits(s.id, listeners > 0 ? listeners : -1, kbps, storageMb > 0 ? storageMb : -1, transferGb > 0 ? transferGb : -1, 0);
        }
        Map<Long, Long> ids = songMap(s.id);
        List<Long> made = new ArrayList<Long>();
        copyContent(d, a, s, r, ai, of, parts, ids, led.playlists, made);
        remember(a.get("username"), s.id, made, ids, (int) num(a.get("port")));
        radio.refresh();
        System.out.println("[import] " + r.centova + " -> station " + s.id + " overwritten: " + String.join(", ", parts));
    }

    void run(Data d, List<Long> chosen, Map<Long, String[]> overrides, int trialDays, boolean email, Map<Long, String> modes,
             Map<Long, List<String>> parts) throws IOException {
        Map<String, Ledger> imported = ledger();
        RadioStore store = radio.store();
        List<Done> done = new ArrayList<Done>();
        int ai = 0;
        for (Map<String, String> a : d.accounts) {
            long aid = num(a.get("id"));
            if (!chosen.contains(aid)) {
                continue;
            }
            ai++;
            Done r = new Done();
            r.centova = a.get("username");
            done.add(r);
            String mode = modes.getOrDefault(aid, "new");
            r.mode = mode;
            try {
                Ledger led = imported.get(r.centova);
                RadioStore.Station was = led == null ? null : store.station(led.station);
                if ("skip".equals(mode) && was != null) {
                    r.station = was.id;
                    r.name = was.name;
                    r.login = was.owner;
                    r.port = was.livePort;
                    r.livePass = was.livePass;
                    r.notes.add("left as it is (already on Memoryq) - nothing was overwritten");
                } else if ("overwrite".equals(mode) && was != null) {
                    updateAccount(d, a, was, led, parts.get(aid), r, ai, chosen.size());
                } else {
                    importAccount(d, a, overrides.get(aid), trialDays, email, r, ai, chosen.size());
                    if (was != null) {
                        r.notes.add("a new copy - station " + was.id + " (" + was.name + ") from the earlier import is still there");
                    }
                }
            } catch (Exception e) {
                r.error = e.getMessage() == null ? e.toString() : e.getMessage();
                System.out.println("[import] " + r.centova + ": " + r.error);
            }
        }
        store.flushIfDirty();
        radio.refresh();
        StringBuilder sb = new StringBuilder("{\"at\":").append(clock.getAsLong()).append(",\"stations\":[");
        for (int i = 0; i < done.size(); i++) {
            Done r = done.get(i);
            sb.append(i > 0 ? "," : "").append("{\"centova\":").append(q(r.centova)).append(",\"station\":").append(r.station)
                    .append(",\"name\":").append(q(r.name)).append(",\"login\":").append(q(r.login)).append(",\"password\":").append(q(r.password))
                    .append(",\"newAccount\":").append(r.newAccount).append(",\"port\":").append(r.port).append(",\"livePass\":").append(q(r.livePass))
                    .append(",\"songs\":").append(r.songs).append(",\"playlists\":").append(r.playlists).append(",\"error\":").append(q(r.error))
                    .append(",\"mode\":").append(q(r.mode))
                    .append(",\"notes\":[");
            for (int j = 0; j < r.notes.size(); j++) {
                sb.append(j > 0 ? "," : "").append(q(r.notes.get(j)));
            }
            sb.append("]}");
        }
        Files.write(dir.resolve("result.json"), sb.append("]}").toString().getBytes(StandardCharsets.UTF_8));
        progress = "finished: " + done.size() + " account(s)";
        System.out.println("[import] Centova import finished: " + done.size() + " account(s)");
    }

    private void importAccount(Data d, Map<String, String> a, String[] ov, int trialDays, boolean email, Done r, int ai, int of)
            throws Exception {
        RadioStore store = radio.store();
        long aid = num(a.get("id"));
        long now = clock.getAsLong();
        String name = ov != null && ov[0] != null && !ov[0].trim().isEmpty() ? ov[0].trim() : nameFor(a);
        boolean typed = ov != null && ov[1] != null && !ov[1].trim().isEmpty();
        String login = typed ? ov[1].trim().toLowerCase(Locale.ROOT) : safeLogin(loginFor(a.get("username")), a.get("email"));
        if (!typed && !login.equals(loginFor(a.get("username")))) {
            r.notes.add("the Memoryq username " + loginFor(a.get("username")) + " belongs to someone else, so this station's login is " + login);
        }
        progress = "account " + ai + " of " + of + ": " + name;
        UserStore.Info have = users.info(login);
        if (have != null && have.role == UserStore.Role.ADMIN) {
            throw new IllegalArgumentException("the login " + login + " is an admin account - give this station another login user");
        }
        String password = "";
        if (have == null) {
            password = UserStore.randomPassword(10);
            UserStore.checkNew(login, password);
        }
        // the station: a new live DJ port, the account's own DJ password, on a free trial
        RadioStore.Station spec = new RadioStore.Station();
        spec.name = name;
        spec.description = a.get("genre");
        try {
            spec.website = RadioStore.checkWebsite(a.get("url"));
        } catch (IllegalArgumentException e) {
            r.notes.add("website not copied (" + a.get("url") + ")");
        }
        String dj = validPass(d.sources.get(a.get("username")));
        spec.livePass = dj;
        spec.billing = RadioStore.BILL_TRIAL;
        spec.paidUntil = now + trialDays * 86400000L;
        String centovaStatus = a.get("status");
        RadioStore.Station s = store.createStation(spec, login,
                "suspended".equals(centovaStatus) ? RadioStore.STATUS_SUSPENDED : RadioStore.STATUS_ACTIVE, now);
        if ("suspended".equals(centovaStatus)) {
            r.notes.add("was suspended in Centova - kept suspended (unsuspend it in Radio - Stations)");
        }
        if ("disabled".equals(centovaStatus)) {
            RadioStore.Station off = store.station(s.id);
            off.enabled = false;
            store.updateStation(off);
            r.notes.add("was switched off in Centova - kept off");
        }
        r.station = s.id;
        r.name = s.name;
        r.login = login;
        r.port = s.livePort;
        r.livePass = s.livePass;
        if (dj.isEmpty()) {
            r.notes.add("new DJ password (the Centova one could not be read or used)");
        }
        if (have == null) {
            try {
                users.createUser(login, password, UserStore.Role.CHATTER);
            } catch (UserStore.UserException e) {
                store.deleteStation(s.id);
                throw new IllegalArgumentException("login " + login + ": " + e.getMessage());
            }
            r.password = password;
            r.newAccount = true;
        } else {
            r.notes.add("added to the existing account " + login + " (its password is unchanged)");
        }
        String mail = a.get("email").trim();
        try {
            store.setContact(s.id, a.get("organization"), Mailer.validAddress(mail.toLowerCase(Locale.ROOT)) ? mail : "");
        } catch (IllegalArgumentException e) {
            r.notes.add("contact details not copied");
        }
        EmailService es = emails;
        if (r.newAccount && es != null && Mailer.validAddress(mail.toLowerCase(Locale.ROOT))) {
            try {
                es.adminSet(login, mail);
            } catch (EmailService.EmailException e) {
                r.notes.add("e-mail not saved: " + e.getMessage());
            }
        }
        String tz = a.get("timezone").trim();
        try {
            store.setTimezone(s.id, tz.isEmpty() ? "America/New_York" : tz);
        } catch (IllegalArgumentException e) {
            store.setTimezone(s.id, "America/New_York");
        }
        // Centova's limits become the station's own limits (0 = no limit)
        int listeners = (int) Math.min(100000, num(a.get("maxclients")));
        int kbps = nearestKbps((int) num(a.get("maxbitrate")));
        int storageMb = (int) Math.min(10000000, num(a.get("diskquota")));
        long xferMb = num(a.get("transferlimit"));
        int transferGb = xferMb <= 0 ? 0 : (int) Math.min(1000000, (xferMb + 1023) / 1024);
        store.setLimits(s.id, listeners > 0 ? listeners : -1, kbps, storageMb > 0 ? storageMb : -1, transferGb > 0 ? transferGb : -1, 0);
        if (num(a.get("maxbitrate")) > 320) {
            r.notes.add("quality " + num(a.get("maxbitrate")) + " kbps lowered to 320 kbps (the most Memoryq streams)");
        }
        store.flushIfDirty();

        Map<Long, Long> ids = new HashMap<Long, Long>();
        List<Long> made = new ArrayList<Long>();
        copyContent(d, a, s, r, ai, of, ALL_PARTS, ids, new ArrayList<Long>(), made);
        remember(a.get("username"), s.id, made, ids, (int) num(a.get("port")));
        store.flushIfDirty();
        radio.refresh();
        System.out.println("[import] " + r.centova + " -> station " + s.id + " " + s.name + " (" + r.songs + " songs, " + r.playlists
                + " playlists, port " + s.livePort + ")");
        if (email && r.newAccount) {
            welcome(s, login, password, mail, trialDays);
        }
    }

    /** Everything a station can get from Centova again. */
    static final List<String> ALL_PARTS = java.util.Arrays.asList("songs", "playlists", "settings", "history");

    /**
     * Songs, playlists and history of one Centova account into a station. ids: Centova song -> Memoryq song (filled
     * in); keptLists: the playlists an earlier import made; made: the station's Centova playlists afterwards.
     */
    private void copyContent(Data d, Map<String, String> a, RadioStore.Station s, Done r, int ai, int of, List<String> parts,
                             Map<Long, Long> ids, List<Long> keptLists, List<Long> made) throws Exception {
        // the songs
        RadioStore store = radio.store();
        long aid = num(a.get("id"));
        long now = clock.getAsLong();
        boolean doSongs = parts.contains("songs"), doLists = parts.contains("playlists"), doHistory = parts.contains("history");
        // the songs this station already has from Centova (an earlier import): Centova song -> Memoryq song
        Map<String, Long> byTitle = new HashMap<String, Long>();
        for (RadioStore.Track t : store.tracks(s.id)) {
            byTitle.put((t.title + "\t" + t.artist).toLowerCase(Locale.ROOT), t.id);
        }
        boolean noMap = ids.isEmpty();
        int i = 0, total = 0, missing = 0, replaced = 0, same = 0;
        for (Map<String, String> t : d.tracks) {
            total += num(t.get("accountid")) == aid ? 1 : 0;
        }
        for (Map<String, String> t : d.tracks) {
            if (num(t.get("accountid")) != aid) {
                continue;
            }
            i++;
            progress = "account " + ai + " of " + of + ": " + s.name + " - song " + i + " of " + total;
            long cid = num(t.get("id"));
            Long had = ids.get(cid);
            if (had != null && store.track(had) == null) {
                had = null; // deleted on Memoryq since
            }
            if (had == null && noMap && !t.get("title").isEmpty()) {
                had = byTitle.get((t.get("title") + "\t" + t.get("artist")).toLowerCase(Locale.ROOT)); // imported before the map was kept
            }
            if (had != null) {
                ids.put(cid, had);
            }
            if (!doSongs) {
                continue;
            }
            Path f = staged(cid);
            if (f == null) {
                missing++;
                continue;
            }
            if (Files.size(f) > IMPORT_SONG_BYTES) {
                r.notes.add("too big to add (over 300 MB): " + t.get("pathname"));
                continue;
            }
            String file = f.getFileName().toString();
            String ext = file.substring(file.lastIndexOf('.') + 1);
            long ms = num(t.get("length")) * 1000L;
            String title = t.get("title"), artist = t.get("artist"), album = t.get("album");
            if (radio.tools().ready()) {
                try {
                    AudioTools.Tags tags = radio.tools().probe(f);
                    if (tags.durationMs > 0) {
                        ms = tags.durationMs;
                    }
                    if (title.isEmpty()) {
                        title = tags.title;
                        artist = artist.isEmpty() ? tags.artist : artist;
                    }
                    album = album.isEmpty() ? tags.album : album;
                } catch (IOException e) {
                    r.notes.add("could not be read, not added: " + t.get("pathname"));
                    continue;
                }
            }
            if (had != null) {
                // already on Memoryq: the file is replaced only when Centova has a different one
                RadioStore.Track old = store.track(had);
                Path oldFile = old == null ? null : store.pathOf(old);
                if (oldFile != null && Files.isRegularFile(oldFile) && Files.size(oldFile) == Files.size(f)) {
                    same++;
                } else {
                    try {
                        if (store.replaceTrackFile(had, ext, Files.readAllBytes(f), ms) != null) {
                            replaced++;
                        }
                    } catch (IOException e) {
                        r.notes.add("could not replace " + t.get("pathname") + ": " + e.getMessage());
                    }
                }
                continue;
            }
            String base = t.get("pathname").replaceAll(".*/", "");
            RadioStore.Track nt = store.addTrack(s.id, base.isEmpty() ? file : base, ext, Files.readAllBytes(f), title, artist, album, ms, now);
            ids.put(cid, nt.id);
            r.songs++;
        }
        if (replaced > 0 || same > 0) {
            r.notes.add("songs already on Memoryq: " + replaced + " replaced with the Centova file, " + same + " unchanged");
        }
        if (missing > 0) {
            r.notes.add(missing + " song(s) were not sent from Centova (file not found there)");
        }

        // the playlists (Centova weights can be any number: they are scaled to 1-10, keeping their proportions)
        if (!doLists) {
            made.addAll(keptLists);
        }
        long maxWeight = 0;
        for (Map<String, String> p : d.playlists) {
            if (num(p.get("accountid")) == aid && "general".equals(p.get("type"))) {
                maxWeight = Math.max(maxWeight, num(p.get("general_weight")));
            }
        }
        boolean breaks = false;
        if (doLists) {
            // the playlists that came from Centova before are replaced; the owner's own playlists stay
            java.util.Set<String> names = new java.util.HashSet<String>();
            for (Map<String, String> p : d.playlists) {
                if (num(p.get("accountid")) == aid) {
                    names.add(p.get("title").trim().toLowerCase(Locale.ROOT));
                }
            }
            int gone = 0;
            for (RadioStore.Playlist old : store.playlists(s.id)) {
                if (keptLists.contains(old.id) || keptLists.isEmpty() && names.contains(old.name.trim().toLowerCase(Locale.ROOT))) {
                    gone += store.deletePlaylist(s.id, old.id) ? 1 : 0;
                }
            }
            if (gone > 0) {
                r.notes.add(gone + " playlist(s) from the earlier import replaced");
            }
        }
        for (Map<String, String> p : d.playlists) {
            if (!doLists || num(p.get("accountid")) != aid) {
                continue;
            }
            RadioStore.Playlist np = new RadioStore.Playlist();
            np.station = s.id;
            np.name = p.get("title");
            np.enabled = "enabled".equals(p.get("status"));
            for (long[] pt : d.playlistTracks) {
                Long nid = pt[0] == num(p.get("id")) ? ids.get(pt[1]) : null;
                if (nid != null) {
                    np.tracks.add(nid);
                }
            }
            String type = p.get("type");
            if ("general".equals(type)) {
                np.type = RadioStore.Playlist.GENERAL;
                long w = num(p.get("general_weight"));
                np.weight = (int) (w <= 0 ? 1 : maxWeight > 10 ? Math.max(1, Math.round(w * 10.0 / maxWeight)) : w);
                np.sequential = "sequential".equals(p.get("general_order"));
                String from = hhmm(p.get("general_starttime")), to = hhmm(p.get("general_endtime"));
                if (!from.equals(to)) {
                    np.timed = true;
                    np.start = minutesOf(from);
                    np.end = minutesOf(to) == 0 ? 1440 : minutesOf(to);
                }
            } else if ("interval".equals(type)) {
                np.type = RadioStore.Playlist.INTERVAL;
                np.unit = "minutes".equals(p.get("interval_type")) ? "minutes" : "songs";
                long every = num(p.get("interval_length"));
                np.every = (int) Math.max(1, Math.min("minutes".equals(np.unit) ? 1440 : 50, every <= 0 ? 4 : every));
                // Centova's default is "play all": the whole list, in order, at each interval (ad breaks)
                boolean one = "onerandom".equals(p.get("interval_style"));
                np.style = one ? "" : "all";
                np.sequential = !one;
                np.inShows = false; // Centova plays scheduled shows without interval playlists
                breaks |= !one && np.enabled;
            } else if ("now".equals(type)) {
                np.type = RadioStore.Playlist.IMMEDIATE;
                np.enabled = false;
            } else if ("scheduled".equals(type)) {
                np.type = RadioStore.Playlist.SCHEDULED;
                scheduled(p, np, d, ids, r);
            } else {
                r.notes.add("playlist \"" + p.get("title") + "\" (" + type + ") is not used by Memoryq - left out");
                continue;
            }
            try {
                made.add(store.savePlaylist(np, now).id);
                r.playlists++;
            } catch (IllegalArgumentException e) {
                r.notes.add("playlist \"" + p.get("title") + "\" not copied: " + e.getMessage());
            }
        }
        if (breaks) {
            r.notes.add("ad breaks / interval playlists play all their songs in order each time, and wait while a scheduled show is on - as in Centova");
        }
        // the listening history (one line per day), for the Studio's Historical report
        int days = doHistory ? saveHistory(d, aid, s.id) : 0;
        if (days > 0) {
            r.notes.add((days == 1 ? "1 day" : days + " days") + " of listening history copied (Studio - Statistics - Historical)");
        }
        for (String w : parts.contains("settings") ? warnings(a) : new ArrayList<String>()) {
            if (!w.startsWith("quality ")) { // the quality note is already there
                r.notes.add(w);
            }
        }
        store.flushIfDirty();
    }

    /** A Centova scheduled playlist becomes a show: its days and start time, its length (or the length of its songs). */
    private static void scheduled(Map<String, String> p, RadioStore.Playlist np, Data d, Map<Long, Long> ids, Done r) {
        LocalDateTime at;
        try {
            at = LocalDateTime.parse(p.get("scheduled_datetime").trim(), DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        } catch (RuntimeException e) {
            at = LocalDateTime.of(2024, 1, 1, 8, 0);
        }
        np.start = at.getHour() * 60 + at.getMinute();
        long minutes = num(p.get("scheduled_duration"));
        if (minutes <= 0) {
            long secs = 0;
            for (long[] pt : d.playlistTracks) {
                if (pt[0] == num(p.get("id"))) {
                    for (Map<String, String> t : d.tracks) {
                        if (num(t.get("id")) == pt[1]) {
                            secs += num(t.get("length"));
                        }
                    }
                }
            }
            minutes = Math.max(30, (secs + 59) / 60);
        }
        minutes = Math.min(1439, minutes);
        int end = (int) ((np.start + minutes) % 1440);
        np.end = end == np.start ? (np.start + 30) % 1440 : end;
        if (np.end == 0) {
            np.end = 1440;
        }
        // Centova plays a show's list once (all in order, all shuffled, or one random song), then the rotation is back
        String sstyle = p.get("scheduled_style");
        np.style = "onerandom".equals(sstyle) ? "one" : "once";
        np.sequential = !"random".equals(sstyle);
        String repeat = p.get("scheduled_repeat");
        int dow = at.getDayOfWeek().getValue() - 1; // Monday = 0
        char[] days = "0000000".toCharArray();
        if ("daily".equals(repeat)) {
            days = "1111111".toCharArray();
        } else {
            String wd = p.get("scheduled_weekdays").toLowerCase(Locale.ROOT);
            for (int i = 0; i < 7; i++) {
                if (wd.contains(WEEK[i])) {
                    days[i] = '1';
                }
            }
            if (new String(days).indexOf('1') < 0) {
                days[dow] = '1';
            }
            String md = p.get("scheduled_monthdays");
            if ("monthly".equals(repeat)) {
                np.when = "month:" + ("first".equals(md) || "last".equals(md) ? md : String.valueOf(at.getDayOfMonth()));
            } else if ("yearly".equals(repeat)) {
                np.when = "year:" + at.toLocalDate().toString().substring(5);
            } else if (!"weekly".equals(repeat)) {
                np.when = "once:" + at.toLocalDate();
                if (at.isBefore(LocalDateTime.now())) {
                    np.enabled = false;
                    r.notes.add("show \"" + p.get("title") + "\" was a one-off on " + at.toLocalDate() + " that has already played - copied, switched off");
                }
            }
        }
        np.days = new String(days);
    }

    /** "08:30:00" -> "08:30" ("00:00" when empty). */
    private static String hhmm(String t) {
        String v = t == null ? "" : t.trim();
        return v.matches("\\d{1,2}:\\d{2}(:\\d{2})?") ? String.format(Locale.ROOT, "%02d:%s", Integer.parseInt(v.split(":")[0]), v.split(":")[1]) : "00:00";
    }

    private static int minutesOf(String hhmm) {
        String[] f = hhmm.split(":");
        return Math.min(1439, Integer.parseInt(f[0]) * 60 + Integer.parseInt(f[1]));
    }

    private static int nearestKbps(int k) {
        if (k <= 0) {
            return 0;
        }
        int[] ok = {32, 48, 64, 96, 128, 160, 192, 256, 320};
        int best = 0;
        for (int v : ok) {
            if (v <= k) {
                best = v;
            }
        }
        return best == 0 ? 32 : best;
    }

    private void welcome(RadioStore.Station s, String login, String password, String to, int trialDays) {
        Mailer m = mailer;
        if (m == null || to == null || !Mailer.validAddress(to.toLowerCase(Locale.ROOT))) {
            return;
        }
        String text = "Hello,\n\nYour station " + s.name + " has moved from the old Centova panel to Memoryq, with its songs and playlists.\n\n"
                + "Your studio:     https://memoryq.com/studio\n"
                + "Login user:      " + login + "\n"
                + "Password:        " + password + "   (please change it after you sign in)\n\n"
                + "Listen link:     https://memoryq.com/radio/" + s.id + ".mp3\n"
                + "Website player:  https://memoryq.com/radio/embed/" + s.id + "\n\n"
                + "Live DJs (BUTT, Mixxx, VirtualDJ) - only the address and port are new, the password stays the same:\n"
                + "  Server " + liveHost + "   Port " + s.livePort + "   Mount /live   User source\n"
                + "Or mix in the browser with Live DJ Pro: https://memoryq.com/djpro\n\n"
                + "You have a free trial for " + trialDays + " days. Choose a plan in the studio (Billing) to stay on air after that.\n\n"
                + "Memoryq\n";
        Thread t = new Thread(() -> m.send(to, "Your radio station " + s.name + " is now on Memoryq", text), "import-mail");
        t.setDaemon(true);
        t.start();
    }

    // ---- Helpers ----

    private static long num(String s) {
        try {
            return s == null || s.trim().isEmpty() ? 0 : Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static long id(String s) throws Bad {
        if (s == null || !s.matches("\\d{1,12}")) {
            throw new Bad(400, "bad number");
        }
        return Long.parseLong(s);
    }

    private static void requirePost(boolean post) throws Bad {
        if (!post) {
            throw new Bad(405, "use POST");
        }
    }

    private static String readBody(HttpExchange ex, int max) throws IOException, Bad {
        InputStream in = ex.getRequestBody();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] b = new byte[8192];
        int n;
        while ((n = in.read(b)) > 0) {
            out.write(b, 0, n);
            if (out.size() > max) {
                throw new Bad(413, "too big");
            }
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private static void deleteTree(Path p) throws IOException {
        if (!Files.exists(p)) {
            return;
        }
        if (Files.isDirectory(p)) {
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(p)) {
                for (Path c : ds) {
                    deleteTree(c);
                }
            }
        }
        Files.deleteIfExists(p);
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