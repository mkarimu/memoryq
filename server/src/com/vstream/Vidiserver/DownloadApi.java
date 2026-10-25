package com.vstream.Vidiserver;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.LongSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The download page: https://memoryq.com/download - a classic download table (like java.com's): for each app its
 * version, release date, file size, number of downloads, install instructions and SHA-256, and below it the version
 * history (the last 3 older files can still be downloaded).
 *
 *   GET  /download                 the page          GET /download/ID.EXT   (e.g. /download/vstream.apk)   GET /download/old/FILE
 *   GET  /api/admin/downloads      POST /api/admin/downloads/upload?app=ID|new&name=&desc=&note=&version=&released=YYYY-MM-DD&file=NAME.EXT
 *   POST /api/admin/downloads/info   app=, name=, desc=, note=, version=, released=        POST /api/admin/downloads/delete  app=  (super-admin)
 * Files: downloads/ID.EXT; downloads/items.tsv (ID, name, description, type, what's new, version, released, sha-256);
 * downloads/stats.tsv (ID, all downloads, downloads of this version); downloads/history.tsv and downloads/old/ (older versions).
 */
public final class DownloadApi {

    static final Map<String, String[]> APPS = new LinkedHashMap<String, String[]>();
    static {
        APPS.put("memoryq", new String[] {"Memoryq", "News, chats, friends, radio, live channels and calls."});
        APPS.put("vstream", new String[] {"Vstream", "Go live from your phone: a free Memoryq channel in one tap, your own RTMP server, "
                + "and copies to YouTube, Facebook, Twitch, X and TikTok."});
    }
    static final List<String> TYPES = java.util.Arrays.asList("apk", "zip", "pdf", "exe");
    static final int MAX_ITEMS = 30;
    static final int KEEP_OLD_FILES = 3;
    private static final long MAX = 300L * 1024 * 1024;
    private static final Pattern VERSION = Pattern.compile("(?i)^\\s*(?:version\\s*|v)?(\\d+(?:\\.\\d+){1,3}(?:[-+][\\w.]+)?)\\b");

    static final class Item {
        String id;
        String name;
        String desc;
        String ext = "apk";
        String note = "";
        String version = "";
        long released;
        String sha = "";
        boolean builtin;
    }

    static final class Old {
        String id;
        String version = "";
        long released;
        long size;
        String file = "";
        long downloads;
        String sha = "";
    }

    private final Path dir;
    private final SessionStore sessions;
    private final LongSupplier clock;
    private final Map<String, long[]> stats = new LinkedHashMap<String, long[]>(); // id -> {all, this version}
    private boolean statsRead;

    public DownloadApi(Path dir, SessionStore sessions, LongSupplier clock) {
        this.dir = dir;
        this.sessions = sessions;
        this.clock = clock;
    }

    public void register(HttpServer server) {
        server.createContext("/download", this::handlePublic);
        server.createContext("/api/admin/downloads", this::handleAdmin);
    }

    // ---- The list ----

    synchronized List<Item> items() {
        Map<String, Item> map = new LinkedHashMap<String, Item>();
        for (Map.Entry<String, String[]> e : APPS.entrySet()) {
            Item it = new Item();
            it.id = e.getKey();
            it.name = e.getValue()[0];
            it.desc = e.getValue()[1];
            it.builtin = true;
            it.note = legacyNote(it.id);
            map.put(it.id, it);
        }
        Path f = dir.resolve("items.tsv");
        if (Files.isRegularFile(f)) {
            try {
                for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                    String[] v = line.split("\t", -1);
                    if (v.length < 5 || !v[0].matches("[a-z0-9-]{2,30}") || !TYPES.contains(v[3])) {
                        continue;
                    }
                    Item it = map.get(v[0]);
                    if (it == null) {
                        it = new Item();
                        it.id = v[0];
                        map.put(it.id, it);
                    }
                    if (!v[1].isEmpty()) {
                        it.name = v[1];
                    }
                    if (!v[2].isEmpty() || !it.builtin) {
                        it.desc = v[2];
                    }
                    it.ext = it.builtin ? "apk" : v[3];
                    it.note = v[4];
                    if (v.length >= 8) {
                        it.version = v[5];
                        it.released = parseLong(v[6]);
                        it.sha = v[7];
                    }
                }
            } catch (IOException e) {
                System.out.println("[downloads] could not read the list: " + e.getMessage());
            }
        }
        for (Item it : map.values()) {
            splitNote(it);
        }
        return new ArrayList<Item>(map.values());
    }

    /** An old-style note ("Version 1.2 - released 24 Oct 2026", "1.1 - live menu") gives the version; the rest is what's new. */
    static void splitNote(Item it) {
        if (!it.version.isEmpty() || it.note.isEmpty()) {
            return;
        }
        Matcher m = VERSION.matcher(it.note);
        if (m.find()) {
            it.version = m.group(1);
            String rest = it.note.substring(m.end()).replaceFirst("^\\s*[-:,]?\\s*", "").replaceFirst("(?i)^released\\s+[^-]*$", "").trim();
            it.note = rest;
        }
    }

    private static long parseLong(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private void saveItems(List<Item> list) throws IOException {
        Files.createDirectories(dir);
        StringBuilder sb = new StringBuilder();
        for (Item it : list) {
            sb.append(it.id).append('\t').append(clean(it.name, 40)).append('\t').append(clean(it.desc, 300)).append('\t')
                    .append(it.ext).append('\t').append(clean(it.note, 120)).append('\t').append(clean(it.version, 30)).append('\t')
                    .append(it.released).append('\t').append(it.sha).append('\n');
        }
        write(dir.resolve("items.tsv"), sb.toString());
    }

    private void write(Path p, String text) throws IOException {
        Files.createDirectories(dir);
        Path part = p.resolveSibling(p.getFileName() + ".part");
        Files.write(part, text.getBytes(StandardCharsets.UTF_8));
        Files.move(part, p, StandardCopyOption.REPLACE_EXISTING);
    }

    private static String clean(String s, int max) {
        String t = s == null ? "" : s.replaceAll("[\\t\\r\\n]+", " ").trim();
        return t.length() > max ? t.substring(0, max).trim() : t;
    }

    Item item(String id) {
        for (Item it : items()) {
            if (it.id.equals(id)) {
                return it;
            }
        }
        return null;
    }

    Path fileOf(Item it) {
        return dir.resolve(it.id + "." + it.ext);
    }

    /** For the built-in apps, kept for old uploads: downloads/APP.apk. */
    Path apk(String app) {
        return dir.resolve(app + ".apk");
    }

    /** The version note uploaded the old way (downloads/APP.txt). */
    private String legacyNote(String app) {
        try {
            Path n = dir.resolve(app + ".txt");
            return Files.isRegularFile(n) ? new String(Files.readAllBytes(n), StandardCharsets.UTF_8).trim() : "";
        } catch (IOException e) {
            return "";
        }
    }

    String note(String app) {
        Item it = item(app);
        return it == null ? "" : (it.version.isEmpty() ? it.note : "Version " + it.version + (it.note.isEmpty() ? "" : " - " + it.note));
    }

    /** What can be downloaded right now (for Alma). */
    public String available() {
        StringBuilder sb = new StringBuilder();
        for (Item it : items()) {
            if (Files.isRegularFile(fileOf(it))) {
                sb.append(sb.length() > 0 ? ", " : "").append(it.name).append(it.version.isEmpty() ? "" : " " + it.version);
            }
        }
        return sb.length() == 0 ? "none uploaded yet" : sb.toString();
    }

    // ---- Download counts and the version history ----

    private synchronized long[] stat(String id) {
        if (!statsRead) {
            statsRead = true;
            try {
                Path f = dir.resolve("stats.tsv");
                if (Files.isRegularFile(f)) {
                    for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                        String[] v = line.split("\t", -1);
                        if (v.length >= 3) {
                            stats.put(v[0], new long[] {parseLong(v[1]), parseLong(v[2])});
                        }
                    }
                }
            } catch (IOException e) {
                System.out.println("[downloads] could not read the counts: " + e.getMessage());
            }
        }
        return stats.computeIfAbsent(id, k -> new long[2]);
    }

    private synchronized void saveStats() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, long[]> e : stats.entrySet()) {
            sb.append(e.getKey()).append('\t').append(e.getValue()[0]).append('\t').append(e.getValue()[1]).append('\n');
        }
        try {
            write(dir.resolve("stats.tsv"), sb.toString());
        } catch (IOException e) {
            System.out.println("[downloads] could not save the counts: " + e.getMessage());
        }
    }

    synchronized void counted(String id) {
        long[] s = stat(id);
        s[0]++;
        s[1]++;
        saveStats();
    }

    synchronized long downloads(String id) {
        return stat(id)[0];
    }

    synchronized List<Old> history() {
        List<Old> out = new ArrayList<Old>();
        Path f = dir.resolve("history.tsv");
        if (Files.isRegularFile(f)) {
            try {
                for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                    String[] v = line.split("\t", -1);
                    if (v.length < 7) {
                        continue;
                    }
                    Old o = new Old();
                    o.id = v[0];
                    o.version = v[1];
                    o.released = parseLong(v[2]);
                    o.size = parseLong(v[3]);
                    o.file = v[4];
                    o.downloads = parseLong(v[5]);
                    o.sha = v[6];
                    out.add(o);
                }
            } catch (IOException e) {
                System.out.println("[downloads] could not read the history: " + e.getMessage());
            }
        }
        return out;
    }

    private synchronized void saveHistory(List<Old> list) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (Old o : list) {
            sb.append(o.id).append('\t').append(clean(o.version, 30)).append('\t').append(o.released).append('\t').append(o.size).append('\t')
                    .append(o.file).append('\t').append(o.downloads).append('\t').append(o.sha).append('\n');
        }
        write(dir.resolve("history.tsv"), sb.toString());
    }

    /** The file being replaced goes to the history (its file kept for the last 3 versions). */
    private void archive(Item it) throws IOException {
        Path cur = fileOf(it);
        if (!Files.isRegularFile(cur)) {
            return;
        }
        List<Old> hist = history();
        Old o = new Old();
        o.id = it.id;
        o.version = it.version;
        o.released = it.released > 0 ? it.released : Files.getLastModifiedTime(cur).toMillis();
        o.size = Files.size(cur);
        o.sha = it.sha.isEmpty() ? sha256(cur) : it.sha;
        long[] s = stat(it.id);
        o.downloads = s[1];
        s[1] = 0;
        saveStats();
        Path oldDir = dir.resolve("old");
        Files.createDirectories(oldDir);
        String tag = (o.version.isEmpty() ? "" : o.version.replaceAll("[^A-Za-z0-9.-]", "") + "-") + clock.getAsLong();
        o.file = it.id + "-" + tag + "." + it.ext;
        Files.move(cur, oldDir.resolve(o.file), StandardCopyOption.REPLACE_EXISTING);
        hist.add(o);
        int kept = 0;
        for (int k = hist.size() - 1; k >= 0; k--) {
            Old h = hist.get(k);
            if (!h.id.equals(it.id) || h.file.isEmpty()) {
                continue;
            }
            if (++kept > KEEP_OLD_FILES) {
                Files.deleteIfExists(oldDir.resolve(h.file));
                h.file = "";
            }
        }
        saveHistory(hist);
    }

    static String sha256(Path f) {
        try (InputStream in = Files.newInputStream(f)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] b = new byte[1 << 16];
            int n;
            while ((n = in.read(b)) > 0) {
                md.update(b, 0, n);
            }
            StringBuilder sb = new StringBuilder();
            for (byte x : md.digest()) {
                sb.append(String.format("%02x", x));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static String typeOf(String ext) {
        switch (ext) {
            case "apk":
                return "application/vnd.android.package-archive";
            case "zip":
                return "application/zip";
            case "pdf":
                return "application/pdf";
            default:
                return "application/octet-stream";
        }
    }

    // ---- The page ----

    private void sendFile(HttpExchange ex, Path f, String name, String ext, Runnable count) throws IOException {
        long size = Files.size(f);
        ex.getResponseHeaders().set("Content-Type", typeOf(ext));
        ex.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"" + name + "\"");
        ex.getResponseHeaders().set("Cache-Control", "no-cache");
        if ("HEAD".equalsIgnoreCase(ex.getRequestMethod())) {
            ex.getResponseHeaders().set("Content-Length", String.valueOf(size));
            ex.sendResponseHeaders(200, -1);
            return;
        }
        String range = ex.getRequestHeaders().getFirst("Range");
        if (range == null || range.matches("bytes=0-.*")) { // a resumed download is not counted twice
            count.run();
        }
        ex.sendResponseHeaders(200, size);
        try (OutputStream os = ex.getResponseBody()) {
            Files.copy(f, os);
        } catch (IOException e) {
            // the download was cancelled
        }
    }

    private void handlePublic(HttpExchange ex) throws IOException {
        try {
            String path = ex.getRequestURI().getPath();
            if (path.equals("/download") || path.equals("/download/")) {
                SuperAgent.send(ex, 200, "text/html; charset=utf-8", AdSense.inject(page(), "/download"));
                return;
            }
            for (Item it : items()) {
                if (path.equals("/download/" + it.id + "." + it.ext)) {
                    Path f = fileOf(it);
                    if (!Files.isRegularFile(f)) {
                        break;
                    }
                    String name = it.id + (it.version.isEmpty() ? "" : "-" + it.version.replaceAll("[^A-Za-z0-9.-]", "")) + "." + it.ext;
                    sendFile(ex, f, name, it.ext, () -> counted(it.id));
                    return;
                }
            }
            if (path.startsWith("/download/old/")) {
                String file = path.substring("/download/old/".length());
                if (file.matches("[a-z0-9-]+-[A-Za-z0-9.-]+\\.(apk|zip|pdf|exe)")) {
                    synchronized (this) {
                        List<Old> hist = history();
                        for (Old o : hist) {
                            if (o.file.equals(file) && Files.isRegularFile(dir.resolve("old").resolve(file))) {
                                Path f = dir.resolve("old").resolve(file);
                                sendFile(ex, f, file.replaceFirst("-\\d{10,}(?=\\.)", ""), file.substring(file.lastIndexOf('.') + 1), () -> {
                                    o.downloads++;
                                    stat(o.id)[0]++;
                                    saveStats();
                                    try {
                                        saveHistory(hist);
                                    } catch (IOException e) {
                                        // not counted
                                    }
                                });
                                return;
                            }
                        }
                    }
                }
            }
            SuperAgent.send(ex, 404, "text/plain; charset=utf-8", "Not found - see https://memoryq.com/download");
        } finally {
            ex.close();
        }
    }

    private static String platform(String ext) {
        switch (ext) {
            case "apk":
                return "Android";
            case "exe":
                return "Windows";
            case "pdf":
                return "Guides (PDF)";
            default:
                return "Other downloads";
        }
    }

    private static String size(long bytes) {
        return bytes >= 1048576 ? String.format(Locale.ENGLISH, "%.2f MB", bytes / 1048576.0) : Math.max(1, bytes / 1024) + " KB";
    }

    private static String date(long t) {
        return new java.text.SimpleDateFormat("MMMM d, yyyy", Locale.ENGLISH).format(new java.util.Date(t));
    }

    private static String num(long n) {
        return String.format(Locale.ENGLISH, "%,d", n);
    }

    private static String instructions(String ext) {
        switch (ext) {
            case "apk":
                return "<ol><li>Tap the download link on your Android phone or tablet.</li><li>Open the downloaded file (Downloads, or the notification).</li>"
                        + "<li>If Android asks, allow your browser to install apps (Settings &rarr; Install unknown apps), then tap <b>Install</b>.</li>"
                        + "<li>Updating: install the new file over the old one - your sign-in and settings stay.</li></ol>";
            case "exe":
                return "<ol><li>Download the file and open it.</li><li>If Windows SmartScreen asks, choose <b>More info &rarr; Run anyway</b>.</li>"
                        + "<li>Follow the installer.</li></ol>";
            case "zip":
                return "<ol><li>Download the file.</li><li>Right-click it and choose <b>Extract All</b>.</li></ol>";
            default:
                return "<ol><li>Download the file and open it.</li></ol>";
        }
    }

    private static String sideNote(String ext) {
        switch (ext) {
            case "apk":
                return "Android 7.0 or newer. After installing, sign in with your Memoryq account - the same one as on memoryq.com.";
            case "exe":
                return "Windows 10 or 11 (64-bit).";
            default:
                return "Check the file with its SHA-256 below if you want to be sure it is the original.";
        }
    }

    String page() throws IOException {
        List<Item> all = items();
        List<Old> hist = history();
        Map<String, List<Item>> groups = new LinkedHashMap<String, List<Item>>();
        for (Item it : all) {
            if (!Files.isRegularFile(fileOf(it)) && !it.builtin) {
                continue; // an added download without its file yet is not shown
            }
            groups.computeIfAbsent(it.ext, k -> new ArrayList<Item>()).add(it);
        }
        StringBuilder body = new StringBuilder();
        for (Map.Entry<String, List<Item>> g : groups.entrySet()) {
            String ext = g.getKey();
            List<Item> items = g.getValue();
            body.append("<section><div class=\"bar\"><span class=\"os\">").append(platform(ext)).append("</span>")
                    .append("<a href=\"#help-").append(ext).append("\" class=\"which\">&#9432; Which download should I choose?</a></div>")
                    .append("<table class=\"dl\">");
            for (int k = 0; k < items.size(); k++) {
                Item it = items.get(k);
                Path f = fileOf(it);
                boolean have = Files.isRegularFile(f);
                body.append("<tr><td class=\"ico\">").append(have ? "<a href=\"/download/" + it.id + "." + it.ext + "\" aria-label=\"Download " + esc(it.name)
                        + "\"><span class=\"arrow\">&#x2B07;</span></a>" : "<span class=\"arrow off\">&#x2B07;</span>").append("</td><td class=\"what\">");
                if (have) {
                    long released = it.released > 0 ? it.released : Files.getLastModifiedTime(f).toMillis();
                    body.append("<a class=\"name\" href=\"/download/").append(it.id).append('.').append(it.ext).append("\">").append(esc(it.name))
                            .append(it.version.isEmpty() ? "" : " " + esc(it.version)).append("</a>")
                            .append("<div class=\"desc\">").append(esc(it.desc)).append("</div>")
                            .append("<div class=\"meta\">filesize: ").append(size(Files.size(f)))
                            .append(" &nbsp;&middot;&nbsp; version: ").append(it.version.isEmpty() ? "-" : esc(it.version))
                            .append(" &nbsp;&middot;&nbsp; released: ").append(date(released))
                            .append(" &nbsp;&middot;&nbsp; downloads: ").append(num(downloads(it.id))).append("</div>");
                    if (!it.note.isEmpty()) {
                        body.append("<div class=\"new\"><b>What's new:</b> ").append(esc(it.note)).append("</div>");
                    }
                } else {
                    body.append("<span class=\"name off\">").append(esc(it.name)).append("</span><div class=\"desc\">").append(esc(it.desc))
                            .append("</div><div class=\"meta\">Coming soon</div>");
                }
                body.append("</td><td class=\"inst\"><a href=\"#help-").append(ext).append("\">Instructions</a></td>");
                if (k == 0) {
                    body.append("<td class=\"side\" rowspan=\"").append(items.size()).append("\">").append(sideNote(ext)).append("</td>");
                }
                body.append("</tr>");
            }
            body.append("<tr><td colspan=\"4\" class=\"foot\" id=\"help-").append(ext).append("\"><b>Installing</b>").append(instructions(ext));
            StringBuilder shas = new StringBuilder();
            for (Item it : items) {
                if (Files.isRegularFile(fileOf(it))) {
                    String sha = it.sha.isEmpty() ? sha256(fileOf(it)) : it.sha;
                    shas.append("<div><span>").append(esc(it.name)).append(it.version.isEmpty() ? "" : " " + esc(it.version)).append(":</span> <code>")
                            .append(sha).append("</code></div>");
                }
            }
            if (shas.length() > 0) {
                body.append("<details class=\"sha\"><summary>SHA-256 checksums &raquo;</summary>").append(shas).append("</details>");
            }
            body.append("</td></tr></table></section>");
        }
        // the version history
        StringBuilder hrows = new StringBuilder();
        List<Old> sorted = new ArrayList<Old>(hist);
        sorted.sort((a, b) -> Long.compare(b.released, a.released));
        for (Old o : sorted) {
            Item it = null;
            for (Item x : all) {
                if (x.id.equals(o.id)) {
                    it = x;
                }
            }
            if (it == null) {
                continue;
            }
            boolean file = !o.file.isEmpty() && Files.isRegularFile(dir.resolve("old").resolve(o.file));
            hrows.append("<tr><td>").append(esc(it.name)).append("</td><td>").append(o.version.isEmpty() ? "-" : esc(o.version)).append("</td><td>")
                    .append(date(o.released)).append("</td><td class=\"r\">").append(size(o.size)).append("</td><td class=\"r\">").append(num(o.downloads))
                    .append("</td><td>").append(file ? "<a href=\"/download/old/" + o.file + "\">Download</a>" : "<span class=\"muted\">no longer available</span>")
                    .append("</td></tr>");
        }
        String history = hrows.length() == 0 ? "" : "<section><div class=\"bar\"><span class=\"os\">Version history</span>"
                + "<span class=\"which muted\">Older versions - use the newest one above unless you need an older one.</span></div>"
                + "<div class=\"scroll\"><table class=\"hist\"><tr><th>App</th><th>Version</th><th>Released</th><th class=\"r\">File size</th><th class=\"r\">Downloads</th><th></th></tr>"
                + hrows + "</table></div></section>";
        return "<!DOCTYPE html><html lang=\"en\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
                + "<title>Downloads - Memoryq</title><meta name=\"description\" content=\"Download the Memoryq apps for Android.\"><style>"
                + ":root{--bg:#f4f4f4;--panel:#fff;--ink:#1a1a1a;--muted:#5c5c5c;--line:#b9b9b9;--soft:#e6e6e6;--link:#0b6e99;--red:#d0021b;--head:#16202a}"
                + "@media (prefers-color-scheme:dark){:root{--bg:#121417;--panel:#1b1f24;--ink:#e8e8e8;--muted:#a0a0a0;--line:#3a4048;--soft:#262b31;--link:#5fb4dc;--red:#ff4d5e}}"
                + "*{box-sizing:border-box}body{margin:0;font-family:'Segoe UI',system-ui,-apple-system,Roboto,Helvetica,Arial,sans-serif;background:var(--bg);color:var(--ink);line-height:1.45}"
                + "a{color:var(--link);text-decoration:none}a:hover{text-decoration:underline}"
                + "header{background:var(--head);border-bottom:4px solid var(--red)}header div{max-width:980px;margin:0 auto;padding:14px 18px;display:flex;align-items:center;gap:22px}"
                + "header .logo{color:#fff;font-weight:800;font-size:22px;letter-spacing:.3px}header nav a{color:#c9d4dd;font-size:14px;margin-right:16px}"
                + "main{max-width:980px;margin:26px auto 60px;padding:0 18px}h1{font-weight:400;font-size:30px;margin:0 0 4px}.lead{color:var(--muted);margin:0 0 22px;font-size:16px}"
                + "section{margin:0 0 26px}.bar{background:var(--soft);border:1px solid var(--line);border-bottom:0;padding:10px 14px;display:flex;align-items:center;gap:18px;flex-wrap:wrap}"
                + ".bar .os{font-size:19px;font-weight:600}.bar .which{font-size:15px}"
                + "table{width:100%;border-collapse:collapse;background:var(--panel)}td,th{border:1px solid var(--line);padding:12px 14px;vertical-align:middle;text-align:left}"
                + "th{background:var(--soft);font-weight:600;font-size:14px}"
                + "td.ico{width:52px;text-align:center}.arrow{display:inline-flex;width:22px;height:22px;border-radius:50%;background:var(--red);color:#fff;font-size:13px;"
                + "align-items:center;justify-content:center;font-weight:700}.arrow.off{background:var(--line)}"
                + ".name{font-size:20px}.name.off{color:var(--muted)}.desc{color:var(--muted);font-size:14px;margin:2px 0 4px}.meta{font-size:14px}"
                + ".new{font-size:14px;margin-top:4px}td.inst{width:150px;font-size:18px}td.side{width:250px;font-size:17px;line-height:1.4}"
                + "td.foot{font-size:15px;padding:14px 18px}td.foot ol{margin:6px 0 4px;padding-left:22px}td.foot li{margin:2px 0}"
                + ".sha summary{cursor:pointer;color:var(--link);margin-top:6px}.sha div{font-size:13px;margin:4px 0;word-break:break-all}.sha span{color:var(--muted)}"
                + "code{font-family:Consolas,Menlo,monospace;font-size:12px}"
                + "table.hist td,table.hist th{padding:8px 12px;font-size:14px}.r{text-align:right}.muted{color:var(--muted)}"
                + ".scroll{overflow-x:auto}.scroll table{min-width:520px}.legal{color:var(--muted);font-size:13px;border-top:1px solid var(--line);padding-top:14px}"
                + "@media (max-width:720px){td.side{display:none}td.inst{width:auto;font-size:15px}.name{font-size:17px}td,th{padding:10px}}"
                + "</style></head><body><header><div><a class=\"logo\" href=\"/\">Memoryq</a><nav><a href=\"/\">Home</a><a href=\"/me\">Web app</a>"
                + "<a href=\"/tv\">Memoryq TV</a><a href=\"/radio\">Radio</a></nav></div></header><main>"
                + "<h1>Download Memoryq</h1><p class=\"lead\">The apps for your phone. On a computer, use <a href=\"/me\">the web app</a> - nothing to install.</p>"
                + body + history
                + "<p class=\"legal\">By installing you accept the <a href=\"/terms\">Terms</a> and the <a href=\"/privacy\">Privacy Policy</a>"
                + " (Vstream: <a href=\"/vstream-legal\">Vstream terms</a>). Only download Memoryq apps from memoryq.com or an official app store.</p>"
                + "</main><script src=\"/alma.js\" defer></script></body></html>";
    }

    // ---- The admin ----

    private static String slug(String name) {
        String s = name == null ? "" : name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        return s.length() > 24 ? s.substring(0, 24).replaceAll("-+$", "") : s;
    }

    private static boolean looksRight(String ext, byte[] head, long total) {
        if (total < 1000) {
            return false;
        }
        switch (ext) {
            case "apk":
            case "zip":
                return head[0] == 'P' && head[1] == 'K';
            case "pdf":
                return head[0] == '%' && head[1] == 'P';
            case "exe":
                return head[0] == 'M' && head[1] == 'Z';
            default:
                return false;
        }
    }

    /** "2026-10-24" (or milliseconds) -> milliseconds; null when not given or not a date. */
    static Long releasedParam(String s) {
        if (s == null || s.trim().isEmpty()) {
            return null;
        }
        String t = s.trim();
        if (t.matches("\\d{11,14}")) {
            return Long.parseLong(t);
        }
        try {
            return java.time.LocalDate.parse(t).atTime(12, 0).toInstant(java.time.ZoneOffset.UTC).toEpochMilli();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static void applyText(Item it, String note, String version, String released) {
        if (version != null) {
            it.version = clean(version, 30).replaceFirst("(?i)^(version\\s*|v)(?=\\d)", "");
        }
        if (note != null) {
            it.note = clean(note, 120);
            if (version == null) {
                it.version = "";
                splitNote(it);
            }
        }
        Long r = releasedParam(released);
        if (r != null) {
            it.released = r;
        }
    }

    private void handleAdmin(HttpExchange ex) throws IOException {
        try {
            if (!SuperAgent.isAdmin(ex, sessions)) {
                SuperAgent.send(ex, 403, "application/json", "{\"error\":\"super-admin only\"}");
                return;
            }
            String path = ex.getRequestURI().getPath();
            String query = ex.getRequestURI().getRawQuery();
            boolean post = "POST".equalsIgnoreCase(ex.getRequestMethod());
            synchronized (this) {
                if (path.equals("/api/admin/downloads/upload") && post) {
                    String err = upload(ex, query);
                    if (err != null) {
                        SuperAgent.send(ex, 400, "application/json", "{\"error\":" + VideoApi.jq(err) + "}");
                        return;
                    }
                } else if (path.equals("/api/admin/downloads/info") && post) {
                    String body = SuperAgent.readBody(ex, 4096);
                    List<Item> list = items();
                    String app = String.valueOf(MediaAuthHttpServer.queryParam(body, "app"));
                    for (Item it : list) {
                        if (it.id.equals(app)) {
                            String n = MediaAuthHttpServer.queryParam(body, "name");
                            String d = MediaAuthHttpServer.queryParam(body, "desc");
                            if (n != null && !n.trim().isEmpty()) {
                                it.name = clean(n, 40);
                            }
                            if (d != null) {
                                it.desc = clean(d, 300);
                            }
                            applyText(it, MediaAuthHttpServer.queryParam(body, "note"), MediaAuthHttpServer.queryParam(body, "version"),
                                    MediaAuthHttpServer.queryParam(body, "released"));
                        }
                    }
                    saveItems(list);
                } else if (path.equals("/api/admin/downloads/delete") && post) {
                    String app = String.valueOf(MediaAuthHttpServer.queryParam(SuperAgent.readBody(ex, 1024), "app"));
                    List<Item> list = items();
                    for (Item it : new ArrayList<Item>(list)) {
                        if (it.id.equals(app)) {
                            Files.deleteIfExists(fileOf(it));
                            Files.deleteIfExists(dir.resolve(it.id + ".txt"));
                            if (it.builtin) {
                                it.note = "";
                                it.version = "";
                                it.released = 0;
                                it.sha = "";
                            } else {
                                list.remove(it);
                            }
                        }
                    }
                    saveItems(list);
                }
            }
            StringBuilder sb = new StringBuilder("{\"types\":[\"apk\",\"zip\",\"pdf\",\"exe\"],\"apps\":[");
            int i = 0;
            for (Item it : items()) {
                Path f = fileOf(it);
                boolean have = Files.isRegularFile(f);
                sb.append(i++ > 0 ? "," : "").append("{\"app\":").append(VideoApi.jq(it.id)).append(",\"name\":").append(VideoApi.jq(it.name))
                        .append(",\"desc\":").append(VideoApi.jq(it.desc)).append(",\"ext\":").append(VideoApi.jq(it.ext))
                        .append(",\"builtin\":").append(it.builtin)
                        .append(",\"have\":").append(have).append(",\"sizeMb\":").append(have ? Files.size(f) / 1048576 : 0)
                        .append(",\"updated\":").append(have ? Files.getLastModifiedTime(f).toMillis() : 0)
                        .append(",\"note\":").append(VideoApi.jq(it.note)).append(",\"version\":").append(VideoApi.jq(it.version))
                        .append(",\"released\":").append(it.released).append(",\"downloads\":").append(downloads(it.id))
                        .append(",\"thisVersion\":").append(stat(it.id)[1]).append('}');
            }
            SuperAgent.send(ex, 200, "application/json", sb.append("]}").toString());
        } finally {
            ex.close();
        }
    }

    /** Saves an uploaded file: a built-in app, an added download, or a new one (app=new, its name and description). */
    private String upload(HttpExchange ex, String query) throws IOException {
        String app = String.valueOf(MediaAuthHttpServer.queryParam(query, "app"));
        String fileName = String.valueOf(MediaAuthHttpServer.queryParam(query, "file"));
        List<Item> list = items();
        Item it = null;
        for (Item x : list) {
            if (x.id.equals(app)) {
                it = x;
            }
        }
        if (it == null && !"new".equals(app)) {
            return "which download? (memoryq, vstream, or new)";
        }
        if (it == null) {
            String name = clean(MediaAuthHttpServer.queryParam(query, "name"), 40);
            if (name.isEmpty()) {
                return "give the download a name";
            }
            if (list.size() >= MAX_ITEMS) {
                return MAX_ITEMS + " downloads at most";
            }
            String base = slug(name);
            if (base.length() < 2) {
                base = "file";
            }
            String id = base;
            for (int n = 2; item(id) != null || APPS.containsKey(id); n++) {
                id = base + "-" + n;
            }
            int dot = fileName.lastIndexOf('.');
            String ext = dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
            if (!TYPES.contains(ext)) {
                return "the file must be one of: .apk, .zip, .pdf, .exe";
            }
            it = new Item();
            it.id = id;
            it.name = name;
            it.desc = clean(MediaAuthHttpServer.queryParam(query, "desc"), 300);
            it.ext = ext;
            list.add(it);
        } else {
            String d = MediaAuthHttpServer.queryParam(query, "desc");
            if (d != null) {
                it.desc = clean(d, 300);
            }
        }
        Files.createDirectories(dir);
        Path tmp = dir.resolve(it.id + "." + it.ext + ".part");
        long total = 0;
        byte[] head = new byte[2];
        try (InputStream in = ex.getRequestBody(); OutputStream out = Files.newOutputStream(tmp)) {
            byte[] b = new byte[1 << 16];
            int n;
            while ((n = in.read(b)) != -1) {
                if (total == 0 && n >= 2) {
                    head[0] = b[0];
                    head[1] = b[1];
                }
                total += n;
                if (total > MAX) {
                    break;
                }
                out.write(b, 0, n);
            }
        }
        if (total > MAX || !looksRight(it.ext, head, total) || ("apk".equals(it.ext) && total < 10000)) {
            Files.deleteIfExists(tmp);
            return total > MAX ? "the file is too big (300 MB at most)"
                    : "apk".equals(it.ext) ? "that is not an Android app file (.apk)" : "that file is not a real ." + it.ext + " file";
        }
        String sha = sha256(tmp);
        if (!sha.isEmpty() && sha.equals(it.sha) && Files.isRegularFile(fileOf(it))) {
            Files.deleteIfExists(tmp); // the same file again: only the text changes
        } else {
            archive(it);
            Files.move(tmp, fileOf(it), StandardCopyOption.REPLACE_EXISTING);
            it.sha = sha;
            it.released = clock.getAsLong();
            it.version = "";
            it.note = "";
        }
        applyText(it, MediaAuthHttpServer.queryParam(query, "note"), MediaAuthHttpServer.queryParam(query, "version"), MediaAuthHttpServer.queryParam(query, "released"));
        Files.deleteIfExists(dir.resolve(it.id + ".txt"));
        saveItems(list);
        System.out.println("[downloads] " + it.name + (it.version.isEmpty() ? "" : " " + it.version) + " uploaded (" + total / 1024 + " KB)");
        return null;
    }

    private static String esc(String s) {
        return (s == null ? "" : s).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}