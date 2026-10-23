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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * The download page: https://memoryq.com/download, with the files the super-admin uploads - the Memoryq and Vstream
 * apps, and any other downloadable (another APK, a Windows program, a ZIP, a PDF guide), each with a short description.
 *
 *   GET  /download                 the page          GET /download/ID.EXT   (e.g. /download/vstream.apk)
 *   GET  /api/admin/downloads      POST /api/admin/downloads/upload?app=ID|new&name=&desc=&note=&file=NAME.EXT (the file is the body)
 *   POST /api/admin/downloads/info   app=, name=, desc=, note=        POST /api/admin/downloads/delete  app=      (super-admin)
 * Files: downloads/ID.EXT, the list in downloads/items.tsv (ID, name, description, file type, version note).
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
    private static final long MAX = 300L * 1024 * 1024;

    static final class Item {
        String id;
        String name;
        String desc;
        String ext = "apk";
        String note = "";
        boolean builtin;
    }

    private final Path dir;
    private final SessionStore sessions;
    private final LongSupplier clock;

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
                }
            } catch (IOException e) {
                System.out.println("[downloads] could not read the list: " + e.getMessage());
            }
        }
        return new ArrayList<Item>(map.values());
    }

    private void saveItems(List<Item> list) throws IOException {
        Files.createDirectories(dir);
        StringBuilder sb = new StringBuilder();
        for (Item it : list) {
            sb.append(it.id).append('\t').append(clean(it.name, 40)).append('\t').append(clean(it.desc, 300)).append('\t')
                    .append(it.ext).append('\t').append(clean(it.note, 80)).append('\n');
        }
        Path part = dir.resolve("items.tsv.part");
        Files.write(part, sb.toString().getBytes(StandardCharsets.UTF_8));
        Files.move(part, dir.resolve("items.tsv"), StandardCopyOption.REPLACE_EXISTING);
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
        return it == null ? "" : it.note;
    }

    /** What can be downloaded right now (for Alma). */
    public String available() {
        StringBuilder sb = new StringBuilder();
        for (Item it : items()) {
            if (Files.isRegularFile(fileOf(it))) {
                sb.append(sb.length() > 0 ? ", " : "").append(it.name);
            }
        }
        return sb.length() == 0 ? "none uploaded yet" : sb.toString();
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

    private void handlePublic(HttpExchange ex) throws IOException {
        try {
            String path = ex.getRequestURI().getPath();
            if (path.equals("/download") || path.equals("/download/")) {
                SuperAgent.send(ex, 200, "text/html; charset=utf-8", page());
                return;
            }
            for (Item it : items()) {
                if (path.equals("/download/" + it.id + "." + it.ext)) {
                    Path f = fileOf(it);
                    if (!Files.isRegularFile(f)) {
                        break;
                    }
                    long size = Files.size(f);
                    ex.getResponseHeaders().set("Content-Type", typeOf(it.ext));
                    ex.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"" + it.id + "." + it.ext + "\"");
                    ex.getResponseHeaders().set("Cache-Control", "no-cache");
                    ex.sendResponseHeaders(200, size);
                    try (OutputStream os = ex.getResponseBody()) {
                        Files.copy(f, os);
                    } catch (IOException e) {
                        // the download was cancelled
                    }
                    return;
                }
            }
            SuperAgent.send(ex, 404, "text/plain; charset=utf-8", "Not found - see https://memoryq.com/download");
        } finally {
            ex.close();
        }
    }

    private static String button(String ext) {
        switch (ext) {
            case "apk":
                return "Download for Android";
            case "exe":
                return "Download for Windows";
            case "pdf":
                return "Download the PDF";
            default:
                return "Download";
        }
    }

    String page() throws IOException {
        StringBuilder cards = new StringBuilder();
        for (Item it : items()) {
            Path f = fileOf(it);
            boolean have = Files.isRegularFile(f);
            if (!have && !it.builtin) {
                continue; // an added download without its file yet is not shown
            }
            cards.append("<div class=\"card\"><h2>").append(esc(it.name)).append("</h2><p>").append(esc(it.desc)).append("</p>");
            if (have) {
                long mb = Files.size(f) / 1048576;
                cards.append("<a class=\"btn\" href=\"/download/").append(it.id).append('.').append(it.ext).append("\">").append(button(it.ext)).append("</a>")
                        .append("<div class=\"small\">").append(mb > 0 ? mb + " MB" : Math.max(1, Files.size(f) / 1024) + " KB").append(" &middot; updated ")
                        .append(new java.text.SimpleDateFormat("MMMM d, yyyy", Locale.ENGLISH).format(new java.util.Date(Files.getLastModifiedTime(f).toMillis())))
                        .append(it.note.isEmpty() ? "" : " &middot; " + esc(it.note)).append("</div>");
            } else {
                cards.append("<div class=\"soon\">Coming soon</div>");
            }
            cards.append("</div>");
        }
        return "<!DOCTYPE html><html lang=\"en\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
                + "<title>Downloads - Memoryq</title><style>"
                + ":root{--bg:#f2f4f7;--panel:#fff;--ink:#1b1f23;--muted:#5f6e7d;--line:#e2e7ed;--accent:#2f6fed}"
                + "@media (prefers-color-scheme:dark){:root{--bg:#0f1419;--panel:#182029;--ink:#e6ebf0;--muted:#8b9aa8;--line:#2a3542;--accent:#5b8cff}}"
                + "*{box-sizing:border-box}body{margin:0;font-family:system-ui,-apple-system,'Segoe UI',Roboto,sans-serif;background:var(--bg);color:var(--ink);line-height:1.6}"
                + "header{background:#16202a}header a{display:block;max-width:820px;margin:0 auto;padding:16px 20px;color:#fff;font-weight:800;font-size:20px;text-decoration:none}"
                + "main{max-width:820px;margin:28px auto 60px;padding:0 20px}h1{margin:0 0 6px}.lead{color:var(--muted);margin:0 0 22px}"
                + ".grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(280px,1fr));gap:16px}"
                + ".card{background:var(--panel);border:1px solid var(--line);border-radius:16px;padding:22px}.card h2{margin:0 0 6px}.card p{color:var(--muted);margin:0 0 16px}"
                + ".btn{display:inline-block;background:var(--accent);color:#fff;text-decoration:none;font-weight:700;padding:11px 18px;border-radius:12px}"
                + ".small{color:var(--muted);font-size:13px;margin-top:8px}.soon{color:var(--muted);font-weight:700}"
                + ".how{margin-top:26px;background:var(--panel);border:1px solid var(--line);border-radius:16px;padding:18px 22px}.how li{margin:4px 0}"
                + "</style></head><body><header><a href=\"/\">Memoryq</a></header><main><h1>Download the apps</h1>"
                + "<p class=\"lead\">For Android phones and tablets. On a computer, use <a href=\"/me\">the web app</a> and <a href=\"/tv\">Memoryq TV</a>.</p>"
                + "<div class=\"grid\">" + cards + "</div>"
                + "<div class=\"how\"><b>Installing an Android app</b><ol><li>Tap Download on your phone.</li><li>Open the downloaded file.</li>"
                + "<li>If Android asks, allow installing apps from your browser, then tap Install.</li></ol>"
                + "<span class=\"small\">By installing you accept the <a href=\"/terms\">Terms</a> and <a href=\"/privacy\">Privacy Policy</a>"
                + " (Vstream: <a href=\"/vstream-legal\">Vstream terms</a>).</span></div></main><script src=\"/alma.js\" defer></script></body></html>";
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
                            String v = MediaAuthHttpServer.queryParam(body, "note");
                            if (n != null && !n.trim().isEmpty()) {
                                it.name = clean(n, 40);
                            }
                            if (d != null) {
                                it.desc = clean(d, 300);
                            }
                            if (v != null) {
                                it.note = clean(v, 80);
                            }
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
                        .append(",\"note\":").append(VideoApi.jq(it.note)).append('}');
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
        Files.move(tmp, fileOf(it), StandardCopyOption.REPLACE_EXISTING);
        String note = MediaAuthHttpServer.queryParam(query, "note");
        it.note = note == null ? "" : clean(note, 80);
        Files.deleteIfExists(dir.resolve(it.id + ".txt"));
        saveItems(list);
        System.out.println("[downloads] " + it.name + " uploaded (" + total / 1024 + " KB)");
        return null;
    }

    private static String esc(String s) {
        return (s == null ? "" : s).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}