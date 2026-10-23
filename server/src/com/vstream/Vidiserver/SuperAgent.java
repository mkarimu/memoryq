package com.vstream.Vidiserver;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The SuperAgent: works under the hood on memoryq.com.
 *  - Keeps SERVERASSETS.md, the development log: the part people write (assets, accomplished, NEXT) is kept as it
 *    is; below it the agent writes a live status (refreshed every 5 minutes) and a change log - after every deploy it
 *    compares the server's code and pages with the last time, and writes what changed in plain words.
 *  - Watches server-wide activity (live channels, viewers, uploads, Alma questions...) with today's peaks, and
 *    warns about problems (little disk space, FFmpeg missing).
 *  - Game bots for lone word-game players will be added here once the word game is part of the server.
 *
 *   GET  /api/admin/agent            the file, the people-written part, warnings     (super-admin)
 *   GET  /api/admin/agent/file       SERVERASSETS.md as a download
 *   POST /api/admin/agent/refresh    check for changes and refresh the status now
 *   POST /api/admin/agent/save       manual=  (the people-written part)
 */
public final class SuperAgent {

    static final String MARKER = "<!-- SUPERAGENT: everything below this line is written by the SuperAgent -->";
    private static final String LOG_HEAD = "## Change log (written by the SuperAgent)";
    private static final int MAX_LOG_ENTRIES = 400;
    private static final long STATUS_MS = 5 * 60000L;
    private static final long CHANGES_MS = 60 * 60000L;
    private static final long LOW_DISK = 2L * 1024 * 1024 * 1024;

    private final Path appDir;
    private final Path file;
    private final Path snapshot;
    private final SessionStore sessions;
    private final LongSupplier clock;
    private final long startedAt;
    private final Map<String, Supplier<String>> facts = new LinkedHashMap<String, Supplier<String>>();
    private final Map<String, IntSupplier> counters = new LinkedHashMap<String, IntSupplier>();
    private final Map<String, int[]> peaks = new LinkedHashMap<String, int[]>(); // name -> {today's peak, day}
    private final List<Supplier<String>> checks = new ArrayList<Supplier<String>>();
    private volatile List<String> warnings = new ArrayList<String>();
    private volatile long lastStatus;

    public SuperAgent(Path appDir, Path file, Path snapshot, SessionStore sessions, LongSupplier clock) {
        this.appDir = appDir;
        this.file = file;
        this.snapshot = snapshot;
        this.sessions = sessions;
        this.clock = clock;
        this.startedAt = clock.getAsLong();
    }

    /** A line in the live status, e.g. "Accounts" -> "120 (3 suspended)". */
    public synchronized SuperAgent fact(String name, Supplier<String> value) {
        facts.put(name, value);
        return this;
    }

    /** A number watched over the day (now and today's peak), e.g. live channels, viewers. */
    public synchronized SuperAgent counter(String name, IntSupplier value) {
        counters.put(name, value);
        return this;
    }

    /** A check that returns a warning, or null when all is well. */
    public synchronized SuperAgent check(Supplier<String> warning) {
        checks.add(warning);
        return this;
    }

    public List<String> warnings() {
        return warnings;
    }

    // ---- Running ----

    public void start() {
        Thread t = new Thread(() -> {
            long lastChanges = 0;
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    long now = clock.getAsLong();
                    if (now - lastChanges >= CHANGES_MS) {
                        checkChanges();
                        lastChanges = now;
                    }
                    writeStatus();
                    Thread.sleep(STATUS_MS);
                } catch (InterruptedException e) {
                    return;
                } catch (Exception e) {
                    System.out.println("[superagent] " + e);
                    try {
                        Thread.sleep(STATUS_MS);
                    } catch (InterruptedException ie) {
                        return;
                    }
                }
            }
        }, "superagent");
        t.setDaemon(true);
        t.start();
    }

    // ---- The file ----

    /** The text people keep (everything above the marker); the starting text when there is no file yet. */
    synchronized String manual() throws IOException {
        if (!Files.isRegularFile(file)) {
            return SuperAgentSeed.TEXT.trim() + "\n";
        }
        String s = read(file);
        int i = s.indexOf(MARKER);
        return (i < 0 ? s : s.substring(0, i)).replaceAll("\\s+$", "") + "\n";
    }

    /** The change log entries already in the file (newest first), each starting with "- ". */
    synchronized List<String> logEntries() throws IOException {
        List<String> out = new ArrayList<String>();
        if (!Files.isRegularFile(file)) {
            return out;
        }
        String s = read(file);
        int i = s.indexOf(LOG_HEAD);
        if (i < 0) {
            return out;
        }
        StringBuilder cur = null;
        for (String line : s.substring(i + LOG_HEAD.length()).split("\\r?\\n")) {
            if (line.startsWith("- ")) {
                if (cur != null) {
                    out.add(cur.toString());
                }
                cur = new StringBuilder(line);
            } else if (cur != null && line.startsWith("  ")) {
                cur.append('\n').append(line);
            }
        }
        if (cur != null) {
            out.add(cur.toString());
        }
        return out;
    }

    public synchronized void saveManual(String text) throws IOException {
        String t = text == null ? "" : text.replace("\r", "");
        t = t.replace(MARKER, "").replace(LOG_HEAD, "## Change log");
        compose(t.replaceAll("\\s+$", "") + "\n", logEntries());
    }

    private synchronized void addLog(String entry) throws IOException {
        List<String> log = logEntries();
        log.add(0, entry);
        while (log.size() > MAX_LOG_ENTRIES) {
            log.remove(log.size() - 1);
        }
        compose(manual(), log);
    }

    private void compose(String manual, List<String> log) throws IOException {
        StringBuilder sb = new StringBuilder(manual);
        sb.append('\n').append(MARKER).append("\n\n");
        sb.append("## Live status (updated by the SuperAgent ").append(stamp(clock.getAsLong())).append(")\n\n");
        for (String line : statusLines()) {
            sb.append("- ").append(line).append('\n');
        }
        List<String> w = warnings;
        sb.append("\n### Warnings\n\n");
        if (w.isEmpty()) {
            sb.append("- none\n");
        }
        for (String x : w) {
            sb.append("- ").append(x).append('\n');
        }
        sb.append('\n').append(LOG_HEAD).append("\n\n");
        for (String e : log) {
            sb.append(e).append('\n');
        }
        write(file, sb.toString());
    }

    // ---- Live status ----

    private List<String> statusLines() {
        List<String> out = new ArrayList<String>();
        long now = clock.getAsLong();
        out.add("Server up " + duration(now - startedAt) + " (since " + stamp(startedAt) + ")");
        Map<String, Supplier<String>> f;
        Map<String, IntSupplier> c;
        synchronized (this) {
            f = new LinkedHashMap<String, Supplier<String>>(facts);
            c = new LinkedHashMap<String, IntSupplier>(counters);
        }
        for (Map.Entry<String, Supplier<String>> e : f.entrySet()) {
            out.add(e.getKey() + ": " + safe(e.getValue()));
        }
        int day = (int) (now / 86400000L);
        for (Map.Entry<String, IntSupplier> e : c.entrySet()) {
            int v;
            try {
                v = e.getValue().getAsInt();
            } catch (RuntimeException ex) {
                v = -1;
            }
            int[] p = peaks.computeIfAbsent(e.getKey(), k -> new int[] {0, day});
            if (p[1] != day) {
                p[0] = 0;
                p[1] = day;
            }
            p[0] = Math.max(p[0], v);
            out.add(e.getKey() + ": " + (v < 0 ? "?" : String.valueOf(v)) + " now, " + p[0] + " at most today");
        }
        Runtime rt = Runtime.getRuntime();
        out.add("Memory: " + (rt.totalMemory() - rt.freeMemory()) / 1048576 + " MB used of " + rt.maxMemory() / 1048576 + " MB");
        double load = ManagementFactory.getOperatingSystemMXBean().getSystemLoadAverage();
        if (load >= 0) {
            out.add(String.format(Locale.ROOT, "Processor load: %.2f (%d cores)", load, rt.availableProcessors()));
        }
        File root = appDir.toAbsolutePath().toFile();
        out.add("Disk: " + root.getUsableSpace() / 1073741824L + " GB free of " + root.getTotalSpace() / 1073741824L + " GB");
        return out;
    }

    private static String safe(Supplier<String> s) {
        try {
            String v = s.get();
            return v == null ? "-" : v;
        } catch (RuntimeException e) {
            return "(not available: " + e.getClass().getSimpleName() + ")";
        }
    }

    private List<String> runChecks() {
        List<String> out = new ArrayList<String>();
        File root = appDir.toAbsolutePath().toFile();
        if (root.getUsableSpace() < LOW_DISK && root.getTotalSpace() > 0) {
            out.add("Disk space is low: " + root.getUsableSpace() / 1048576 + " MB free - delete old videos, songs or logs");
        }
        List<Supplier<String>> cs;
        synchronized (this) {
            cs = new ArrayList<Supplier<String>>(checks);
        }
        for (Supplier<String> c : cs) {
            try {
                String w = c.get();
                if (w != null && !w.isEmpty()) {
                    out.add(w);
                }
            } catch (RuntimeException e) {
                // a check that cannot run is not a warning
            }
        }
        return out;
    }

    /** Refreshes the live status and the warnings (and logs warnings that are new). */
    public synchronized void writeStatus() throws IOException {
        List<String> old = warnings;
        List<String> now = runChecks();
        warnings = now;
        lastStatus = clock.getAsLong();
        List<String> log = logEntries();
        for (String w : now) {
            if (!old.contains(w)) {
                log.add(0, "- " + stamp(lastStatus) + " - WARNING: " + w);
            }
        }
        compose(manual(), log);
    }

    // ---- What changed since the last deploy ----

    /** Compares the server's code and pages with the last check; writes what changed into the change log. */
    public synchronized String checkChanges() throws IOException {
        Map<String, String> nowFiles = scan();
        Map<String, String> before = new TreeMap<String, String>();
        boolean first = !Files.isRegularFile(snapshot);
        if (!first) {
            for (String line : Files.readAllLines(snapshot, StandardCharsets.UTF_8)) {
                int t = line.indexOf('\t');
                if (t > 0) {
                    before.put(line.substring(0, t), line.substring(t + 1));
                }
            }
        }
        List<String> added = new ArrayList<String>();
        List<String> changed = new ArrayList<String>();
        List<String> removed = new ArrayList<String>();
        for (Map.Entry<String, String> e : nowFiles.entrySet()) {
            String old = before.get(e.getKey());
            if (old == null) {
                added.add(e.getKey());
            } else if (!old.equals(e.getValue())) {
                changed.add(e.getKey());
            }
        }
        for (String k : before.keySet()) {
            if (!nowFiles.containsKey(k)) {
                removed.add(k);
            }
        }
        StringBuilder snap = new StringBuilder();
        for (Map.Entry<String, String> e : nowFiles.entrySet()) {
            snap.append(e.getKey()).append('\t').append(e.getValue()).append('\n');
        }
        write(snapshot, snap.toString());
        String entry;
        if (first) {
            entry = "- " + stamp(clock.getAsLong()) + " - SuperAgent started keeping this log. Watching " + nowFiles.size()
                    + " code files and pages.";
        } else if (added.isEmpty() && changed.isEmpty() && removed.isEmpty()) {
            return null;
        } else {
            StringBuilder sb = new StringBuilder("- " + stamp(clock.getAsLong()) + " - Deploy: " + added.size() + " new, "
                    + changed.size() + " changed, " + removed.size() + " removed");
            describe(sb, "New", added);
            describe(sb, "Changed", changed);
            if (!removed.isEmpty()) {
                sb.append("\n  - Removed: ").append(String.join(", ", shortNames(removed)));
            }
            entry = sb.toString();
        }
        addLog(entry);
        return entry;
    }

    private void describe(StringBuilder sb, String what, List<String> files) {
        int tests = 0;
        for (String f : files) {
            String n = shortName(f);
            if (n.endsWith("Test")) {
                tests++;
                continue;
            }
            String d = description(f);
            sb.append("\n  - ").append(what).append(": ").append(n).append(d.isEmpty() ? "" : " - " + d);
        }
        if (tests > 0) {
            sb.append("\n  - ").append(what).append(": ").append(tests).append(" automatic test").append(tests == 1 ? "" : "s");
        }
    }

    private static final Map<String, String> PAGES = new LinkedHashMap<String, String>();
    static {
        PAGES.put("admin", "the admin page (/admin)");
        PAGES.put("user", "the web app (/me)");
        PAGES.put("tv", "Memoryq TV (/tv)");
        PAGES.put("studio", "the Studio (/studio)");
        PAGES.put("djpro", "DJ Pro (/djpro)");
        PAGES.put("home", "the homepage");
        PAGES.put("terms", "the terms page");
        PAGES.put("privacy", "the privacy page");
        PAGES.put("vstream-legal", "the Vstream legal page");
    }

    /** One readable line about a file: the first sentence of a class's description, or what a page is. */
    String description(String rel) {
        String n = shortName(rel);
        if (rel.endsWith(".html")) {
            String p = PAGES.get(n);
            return p == null ? "a web page" : p;
        }
        try {
            String src = read(appDir.resolve(rel));
            Matcher m = Pattern.compile("/\\*\\*(.*?)\\*/\\s*(?:@\\w+\\s*)*public\\s+(?:final\\s+|abstract\\s+)*(?:class|interface|enum)\\s+"
                    + Pattern.quote(n) + "\\b", Pattern.DOTALL).matcher(src);
            if (!m.find()) {
                return "";
            }
            String doc = m.group(1).replaceAll("(?m)^\\s*\\*\\s?", " ").replaceAll("\\s+", " ").trim();
            doc = doc.replaceAll("\\{@\\w+\\s+([^}]*)}", "$1");
            int dot = doc.indexOf(". ");
            int colon = doc.indexOf(": ");
            int cut = dot < 0 ? doc.length() : dot + 1;
            if (colon > 0 && colon < cut) {
                cut = colon;
            }
            String first = doc.substring(0, cut).trim();
            return first.length() > 160 ? first.substring(0, 157) + "..." : first;
        } catch (IOException e) {
            return "";
        }
    }

    private Map<String, String> scan() throws IOException {
        Map<String, String> out = new TreeMap<String, String>();
        Path src = appDir.resolve("src");
        if (Files.isDirectory(src)) {
            try (Stream<Path> s = Files.walk(src)) {
                for (Path p : (Iterable<Path>) s.filter(x -> x.toString().endsWith(".java"))::iterator) {
                    out.put(appDir.relativize(p).toString().replace('\\', '/'), hash(p));
                }
            }
        }
        try (Stream<Path> s = Files.list(appDir)) {
            for (Path p : (Iterable<Path>) s.filter(x -> x.getFileName().toString().endsWith(".html") && Files.isRegularFile(x))::iterator) {
                String n = p.getFileName().toString();
                if (n.contains(".before") || n.contains(".bak")) {
                    continue;
                }
                out.put(n, hash(p));
            }
        }
        return out;
    }

    private static String hash(Path p) throws IOException {
        try {
            byte[] text = new String(Files.readAllBytes(p), StandardCharsets.UTF_8).replace("\r", "").getBytes(StandardCharsets.UTF_8);
            byte[] d = MessageDigest.getInstance("SHA-1").digest(text);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                sb.append(String.format("%02x", d[i]));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    private static String shortName(String rel) {
        String n = rel.substring(rel.lastIndexOf('/') + 1);
        int dot = n.lastIndexOf('.');
        return dot > 0 ? n.substring(0, dot) : n;
    }

    private static List<String> shortNames(List<String> rels) {
        List<String> out = new ArrayList<String>();
        for (String r : rels) {
            out.add(shortName(r));
        }
        return out;
    }

    // ---- Other servers on this machine (the games) ----

    /** A small JSON status from another server, or null when it does not answer. Asked at most once a minute. */
    public static Supplier<Map<String, Object>> remoteStatus(String url) {
        final Object[] cache = {0L, null};
        return () -> {
            synchronized (cache) {
                long now = System.currentTimeMillis();
                if (now - (Long) cache[0] > 60000L) {
                    cache[0] = now;
                    cache[1] = fetchJson(url, 5000);
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> m = (Map<String, Object>) cache[1];
                return m;
            }
        };
    }

    static Map<String, Object> fetchJson(String url, int timeoutMs) {
        java.net.HttpURLConnection c = null;
        try {
            c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            c.setConnectTimeout(timeoutMs);
            c.setReadTimeout(timeoutMs);
            if (c.getResponseCode() != 200) {
                return null;
            }
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] b = new byte[8192];
                int n;
                while ((n = in.read(b)) > 0 && out.size() < 65536) {
                    out.write(b, 0, n);
                }
                return MiniJson.object(new String(out.toByteArray(), StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) {
                c.disconnect();
            }
        }
    }

    /** One line about the Vistr word game from its /api/agent/status answer. */
    static String vistrLine(Map<String, Object> m) {
        if (m == null) {
            return "no answer from the game server";
        }
        return MiniJson.num(m, "playersOnline") + " players online, " + MiniJson.num(m, "gamesPlaying", "duo") + " two-player and "
                + MiniJson.num(m, "gamesPlaying", "solo") + " solo games; bots: " + MiniJson.num(m, "bots") + " ("
                + MiniJson.num(m, "botsPlaying") + " playing, " + MiniJson.num(m, "botsWaitingForTheirPlayer") + " waiting for their player; "
                + MiniJson.num(m, "botGames") + " bot games since the game server started)"
                + ("false".equals(MiniJson.str(m, "enabled")) ? " - bots are switched OFF" : "");
    }

    // ---- Admin ----

    public void register(HttpServer server) {
        server.createContext("/api/admin/agent", this::handle);
    }

    private void handle(HttpExchange ex) throws IOException {
        try {
            if (!isAdmin(ex, sessions)) {
                send(ex, 403, "application/json", "{\"error\":\"super-admin only\"}");
                return;
            }
            String path = ex.getRequestURI().getPath();
            boolean post = "POST".equalsIgnoreCase(ex.getRequestMethod());
            if (path.equals("/api/admin/agent/file")) {
                if (!Files.isRegularFile(file)) {
                    writeStatus();
                }
                ex.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"SERVERASSETS.md\"");
                send(ex, 200, "text/markdown; charset=utf-8", read(file));
                return;
            }
            if (post && path.equals("/api/admin/agent/refresh")) {
                String c = checkChanges();
                writeStatus();
                send(ex, 200, "application/json", json(c == null ? "No code or page changed since the last check." : "Logged: " + c.substring(2)));
                return;
            }
            if (post && path.equals("/api/admin/agent/save")) {
                String body = readBody(ex, 200 * 1024);
                String manual = MediaAuthHttpServer.queryParam(body, "manual");
                if (manual == null || manual.trim().isEmpty()) {
                    send(ex, 400, "application/json", "{\"error\":\"the text is empty\"}");
                    return;
                }
                saveManual(manual);
                send(ex, 200, "application/json", json("Saved."));
                return;
            }
            if (path.equals("/api/admin/agent") || path.equals("/api/admin/agent/")) {
                send(ex, 200, "application/json", json(null));
                return;
            }
            send(ex, 404, "application/json", "{\"error\":\"not found\"}");
        } catch (RuntimeException e) {
            send(ex, 500, "application/json", "{\"error\":" + VideoApi.jq("server error: " + e) + "}");
        } finally {
            ex.close();
        }
    }

    private String json(String message) throws IOException {
        if (!Files.isRegularFile(file)) {
            writeStatus();
        }
        StringBuilder sb = new StringBuilder("{\"text\":").append(VideoApi.jq(read(file)))
                .append(",\"manual\":").append(VideoApi.jq(manual()))
                .append(",\"updated\":").append(lastStatus).append(",\"warnings\":[");
        List<String> w = warnings;
        for (int i = 0; i < w.size(); i++) {
            sb.append(i > 0 ? "," : "").append(VideoApi.jq(w.get(i)));
        }
        sb.append(']');
        if (message != null) {
            sb.append(",\"message\":").append(VideoApi.jq(message));
        }
        return sb.append('}').toString();
    }

    static boolean isAdmin(HttpExchange ex, SessionStore sessions) {
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        String token = auth != null && auth.startsWith("Bearer ") ? auth.substring(7).trim() : "";
        SessionStore.Session s = token.isEmpty() ? null : sessions.get(token);
        return s != null && s.role == UserStore.Role.ADMIN;
    }

    // ---- Helpers ----

    static String stamp(long t) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm z", Locale.ROOT).format(new Date(t));
    }

    static String duration(long ms) {
        long m = ms / 60000;
        if (m < 60) {
            return m + " min";
        }
        long h = m / 60;
        return h < 48 ? h + " h " + m % 60 + " min" : h / 24 + " days " + h % 24 + " h";
    }

    private static String read(Path p) throws IOException {
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }

    private static void write(Path target, String text) throws IOException {
        Path abs = target.toAbsolutePath();
        if (abs.getParent() != null) {
            Files.createDirectories(abs.getParent());
        }
        Path tmp = abs.resolveSibling(abs.getFileName() + ".tmp");
        Files.write(tmp, text.getBytes(StandardCharsets.UTF_8));
        Files.move(tmp, abs, StandardCopyOption.REPLACE_EXISTING);
    }

    static String readBody(HttpExchange ex, int max) throws IOException {
        InputStream in = ex.getRequestBody();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] b = new byte[8192];
        int n;
        while ((n = in.read(b)) > 0) {
            out.write(b, 0, n);
            if (out.size() > max) {
                throw new IOException("too much text");
            }
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    static void send(HttpExchange ex, int status, String type, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", type);
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(status, b.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(b);
        }
    }
}