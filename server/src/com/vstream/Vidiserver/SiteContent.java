package com.vstream.Vidiserver;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The website's words, edited in place by the super-admin (like a page builder): every block marked
 * data-edit="page.key" in home.html, terms.html, privacy.html and vstream-legal.html can be changed on the page itself
 * (the pencil), and the change shows at once. Changes are kept in site-content.txt and put into the page when it is
 * served, so visitors and search engines see them. Only simple, safe formatting is kept (bold, italic, links, lists).
 *   GET  /site-editor.js                                  the editor (it only shows up for the super-admin)
 *   POST /api/admin/content/save   key, html             -> {html} (cleaned)
 *   POST /api/admin/content/reset  key, page             -> {html} (the original from the page file)
 *   POST /api/admin/view-as        who=user              -> {token, username} the Memoryq trial member, to see the site as a user
 */
public final class SiteContent {

    private static final Set<String> TAGS = new HashSet<String>(Arrays.asList(
            "b", "strong", "i", "em", "u", "br", "a", "span", "small", "ul", "ol", "li", "p", "h2", "h3", "h4", "div", "sup", "sub"));
    private static final Set<String> DROP_WITH_CONTENT = new HashSet<String>(Arrays.asList(
            "script", "style", "iframe", "object", "embed", "svg", "math", "template", "noscript", "form", "textarea", "select", "button", "input"));
    static final int MAX_HTML = 20000;
    public static final String TRIAL_USER = "memoryq_trial";

    private final Path file;
    private final Path pagesDir;
    private final SessionStore sessions;
    private final UserStore users;
    private final Map<String, String> overrides = new LinkedHashMap<String, String>();

    public SiteContent(Path file, Path pagesDir, SessionStore sessions, UserStore users) {
        this.file = file;
        this.pagesDir = pagesDir;
        this.sessions = sessions;
        this.users = users;
    }

    public synchronized void init() {
        overrides.clear();
        if (file == null || !Files.isRegularFile(file)) {
            return;
        }
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                int t = line.indexOf('\t');
                if (t > 0) {
                    overrides.put(line.substring(0, t), line.substring(t + 1).replace("\\n", "\n").replace("\\\\", "\\"));
                }
            }
        } catch (IOException e) {
            System.out.println("[site] could not read " + file + ": " + e.getMessage());
        }
    }

    private void save() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : overrides.entrySet()) {
            sb.append(e.getKey()).append('\t').append(e.getValue().replace("\\", "\\\\").replace("\r", "").replace("\n", "\\n")).append('\n');
        }
        try {
            Path part = file.resolveSibling(file.getFileName() + ".part");
            Files.write(part, sb.toString().getBytes(StandardCharsets.UTF_8));
            Files.move(part, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            System.out.println("[site] could not save: " + e.getMessage());
        }
    }

    public synchronized int count() {
        return overrides.size();
    }

    // ---- putting the changes into a page ----

    /** The page with the super-admin's changes in the marked blocks. */
    public String apply(String html) {
        Map<String, String> copy;
        synchronized (this) {
            if (overrides.isEmpty()) {
                return html;
            }
            copy = new LinkedHashMap<String, String>(overrides);
        }
        String out = html;
        for (Map.Entry<String, String> e : copy.entrySet()) {
            int[] r = block(out, e.getKey());
            if (r != null) {
                out = out.substring(0, r[0]) + e.getValue() + out.substring(r[1]);
            }
        }
        return out;
    }

    /** Where the inside of the block data-edit="key" starts and ends, or null. */
    static int[] block(String html, String key) {
        int at = html.indexOf("data-edit=\"" + key + "\"");
        if (at < 0) {
            return null;
        }
        int open = html.lastIndexOf('<', at);
        int gt = html.indexOf('>', at);
        if (open < 0 || gt < 0) {
            return null;
        }
        Matcher tm = Pattern.compile("<([a-zA-Z0-9]+)").matcher(html);
        if (!tm.find(open)) {
            return null;
        }
        String tag = tm.group(1).toLowerCase(Locale.ROOT);
        Pattern p = Pattern.compile("<(/?)" + tag + "\\b[^>]*>", Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(html);
        int depth = 1;
        int from = gt + 1;
        while (m.find(from)) {
            if (m.group(1).isEmpty()) {
                if (!m.group().endsWith("/>")) {
                    depth++;
                }
            } else {
                depth--;
                if (depth == 0) {
                    return new int[]{gt + 1, m.start()};
                }
            }
            from = m.end();
        }
        return null;
    }

    /** The block's words as they are in the page file (before any change). */
    String original(String page, String key) throws IOException {
        Path f = pageFile(page);
        if (f == null || !Files.isRegularFile(f)) {
            return null;
        }
        String html = new String(Files.readAllBytes(f), StandardCharsets.UTF_8);
        int[] r = block(html, key);
        return r == null ? null : html.substring(r[0], r[1]);
    }

    private Path pageFile(String page) {
        switch (page) {
            case "home":
                return pagesDir.resolve("home.html");
            case "terms":
                return pagesDir.resolve("terms.html");
            case "privacy":
                return pagesDir.resolve("privacy.html");
            case "vstream-legal":
                return pagesDir.resolve("vstream-legal.html");
            default:
                return null;
        }
    }

    // ---- keeping only safe formatting ----

    /** Keeps simple formatting (bold, italic, underline, links, lists, line breaks); everything else goes. */
    public static String clean(String html) {
        if (html == null) {
            return "";
        }
        String s = html.replaceAll("(?s)<!--.*?-->", "");
        for (String t : DROP_WITH_CONTENT) {
            s = s.replaceAll("(?is)<" + t + "\\b.*?</" + t + "\\s*>", "").replaceAll("(?is)<" + t + "\\b[^>]*>", "");
        }
        StringBuilder out = new StringBuilder();
        Matcher m = Pattern.compile("<(/?)([a-zA-Z][a-zA-Z0-9]*)([^>]*)>").matcher(s);
        int last = 0;
        while (m.find()) {
            out.append(text(s.substring(last, m.start())));
            last = m.end();
            String tag = m.group(2).toLowerCase(Locale.ROOT);
            if (!TAGS.contains(tag)) {
                continue;
            }
            boolean close = !m.group(1).isEmpty();
            if (close) {
                if (!tag.equals("br")) {
                    out.append("</").append(tag).append('>');
                }
                continue;
            }
            if (tag.equals("a")) {
                Matcher h = Pattern.compile("(?i)href\\s*=\\s*(\"([^\"]*)\"|'([^']*)')").matcher(m.group(3));
                String href = h.find() ? (h.group(2) != null ? h.group(2) : h.group(3)) : "";
                href = href.trim();
                String lh = href.toLowerCase(Locale.ROOT);
                boolean ok = lh.startsWith("https://") || lh.startsWith("http://") || lh.startsWith("mailto:") || lh.startsWith("tel:")
                        || (lh.startsWith("/") && !lh.startsWith("//")) || lh.startsWith("#");
                if (ok && !href.contains("\"") && !href.contains("<")) {
                    boolean outside = lh.startsWith("http");
                    out.append("<a href=\"").append(href).append('"').append(outside ? " rel=\"noopener\" target=\"_blank\"" : "").append('>');
                } else {
                    out.append("<a>");
                }
            } else {
                out.append('<').append(tag).append('>');
            }
        }
        out.append(text(s.substring(last)));
        String r = out.toString().trim();
        return r.length() > MAX_HTML ? r.substring(0, MAX_HTML) : r;
    }

    /** Text between tags: a stray < or > becomes harmless. */
    private static String text(String t) {
        return t.replace("<", "&lt;").replace(">", "&gt;");
    }

    // ---- HTTP ----

    public void register(HttpServer server) {
        server.createContext("/site-editor.js", ex -> {
            try {
                byte[] b = SiteEditorJs.JS.getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().set("Content-Type", "application/javascript; charset=utf-8");
                ex.getResponseHeaders().set("Cache-Control", "no-cache");
                ex.sendResponseHeaders(200, b.length);
                ex.getResponseBody().write(b);
            } finally {
                ex.close();
            }
        });
        server.createContext("/api/admin/content/", this::handle);
        server.createContext("/api/admin/view-as", this::viewAs);
    }

    private boolean admin(HttpExchange ex) {
        return SuperAgent.isAdmin(ex, sessions);
    }

    private void handle(HttpExchange ex) throws IOException {
        try {
            if (!admin(ex)) {
                json(ex, 403, "{\"error\":\"super-admin only\"}");
                return;
            }
            if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
                json(ex, 405, "{\"error\":\"use POST\"}");
                return;
            }
            String op = ex.getRequestURI().getPath().substring("/api/admin/content/".length());
            String body = SuperAgent.readBody(ex, 64 * 1024);
            String key = String.valueOf(MediaAuthHttpServer.queryParam(body, "key")).trim();
            if (!key.matches("[a-z0-9-]+\\.[a-z0-9.-]{1,80}")) {
                json(ex, 400, "{\"error\":\"which part of the page?\"}");
                return;
            }
            String page = key.substring(0, key.indexOf('.'));
            if (pageFile(page) == null) {
                json(ex, 400, "{\"error\":\"this page cannot be edited\"}");
                return;
            }
            if ("save".equals(op)) {
                String html = clean(MediaAuthHttpServer.queryParam(body, "html"));
                if (original(page, key) == null) {
                    json(ex, 404, "{\"error\":\"that part is not on the page any more\"}");
                    return;
                }
                synchronized (this) {
                    overrides.put(key, html);
                    save();
                }
                System.out.println("[site] the super-admin changed " + key);
                json(ex, 200, "{\"html\":" + ProfileApi.quote(html) + "}");
            } else if ("reset".equals(op)) {
                String orig = original(page, key);
                synchronized (this) {
                    overrides.remove(key);
                    save();
                }
                json(ex, 200, "{\"html\":" + ProfileApi.quote(orig == null ? "" : orig) + "}");
            } else {
                json(ex, 404, "{\"error\":\"not found\"}");
            }
        } finally {
            ex.close();
        }
    }

    /** The super-admin sees the site as an ordinary member: the Memoryq trial account (made the first time). */
    private void viewAs(HttpExchange ex) throws IOException {
        try {
            if (!admin(ex) || !"POST".equalsIgnoreCase(ex.getRequestMethod())) {
                json(ex, 403, "{\"error\":\"super-admin only\"}");
                return;
            }
            if (users.info(TRIAL_USER) == null) {
                try {
                    users.createUser(TRIAL_USER, UserStore.randomPassword(24), UserStore.Role.CHATTER);
                } catch (UserStore.UserException e) {
                    json(ex, 500, "{\"error\":" + ProfileApi.quote("could not make the trial account: " + e.getMessage()) + "}");
                    return;
                }
            }
            UserStore.Info i = users.info(TRIAL_USER);
            String tok = sessions.create(i.username, i.role);
            System.out.println("[site] the super-admin is viewing the site as " + i.username);
            json(ex, 200, "{\"token\":" + ProfileApi.quote(tok) + ",\"username\":" + ProfileApi.quote(i.username) + "}");
        } finally {
            ex.close();
        }
    }

    private static void json(HttpExchange ex, int status, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(status, b.length);
        ex.getResponseBody().write(b);
    }
}