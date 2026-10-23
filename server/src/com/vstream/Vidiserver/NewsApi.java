package com.vstream.Vidiserver;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * News endpoints.
 *   GET  /api/news?seed=..&offset=..&limit=..&topics=a,b     public: stories in a seeded random order
 *   /api/admin/news/status|items|sources/add|sources/remove|sources/toggle|pause|refresh|items/hide   (admin only)
 */
public final class NewsApi {

    private static final int MAX_BODY = 4 * 1024;

    private final NewsService news;
    private final UserStore users;
    private final SessionStore sessions;

    public NewsApi(NewsService news, UserStore users, SessionStore sessions) {
        this.news = news;
        this.users = users;
        this.sessions = sessions;
    }

    /** Items shown first, the moment they happen (who is live now on Memoryq), as JSON objects. */
    private volatile java.util.function.Supplier<java.util.List<String>> pinned;

    public void setPinned(java.util.function.Supplier<java.util.List<String>> p) {
        this.pinned = p;
    }

    public void register(HttpServer server) {
        server.createContext("/api/news", new HttpHandler() {
            @Override
            public void handle(HttpExchange ex) throws IOException {
                handlePublic(ex);
            }
        });
        server.createContext("/api/admin/news/", new HttpHandler() {
            @Override
            public void handle(HttpExchange ex) throws IOException {
                handleAdmin(ex);
            }
        });
    }

    // ---- Public ----

    private void handlePublic(HttpExchange ex) throws IOException {
        try {
            String path = ex.getRequestURI().getPath();
            if (path.equals("/api/news.rss")) {
                sendRss(ex);
                return;
            }
            if (!path.equals("/api/news") && !path.equals("/api/news/")) {
                send(ex, 404, error("not found"));
                return;
            }
            if (!ex.getRequestMethod().equalsIgnoreCase("GET")) {
                ex.getResponseHeaders().set("Allow", "GET");
                send(ex, 405, error("method not allowed"));
                return;
            }
            String query = ex.getRequestURI().getRawQuery();
            long seed = parseLong(MediaAuthHttpServer.queryParam(query, "seed"), 0, Long.MIN_VALUE, Long.MAX_VALUE);
            int offset = (int) parseLong(MediaAuthHttpServer.queryParam(query, "offset"), 0, 0, 100000);
            int limit = (int) parseLong(MediaAuthHttpServer.queryParam(query, "limit"), 20, 1, 50);
            Set<String> topics = new LinkedHashSet<String>();
            String t = MediaAuthHttpServer.queryParam(query, "topics");
            if (t != null) {
                for (String part : t.split(",")) {
                    String p = part.trim().toLowerCase(Locale.ROOT);
                    if (NewsService.TOPICS.contains(p)) {
                        topics.add(p);
                    }
                }
            }
            java.util.List<String> pins = java.util.Collections.emptyList();
            java.util.function.Supplier<java.util.List<String>> ps = pinned;
            if (ps != null) {
                try {
                    pins = ps.get();
                } catch (RuntimeException e) {
                    pins = java.util.Collections.emptyList();
                }
            }
            // the live items come first; the stories' offsets move along by as many
            int np = pins.size();
            java.util.List<String> head = new java.util.ArrayList<String>();
            for (int i = offset; i < np && head.size() < limit; i++) {
                head.add(pins.get(i));
            }
            int storyOffset = Math.max(0, offset - np);
            int storyLimit = limit - head.size();
            NewsService.Page page = storyLimit > 0 ? news.visibleItems(topics, seed, storyOffset, storyLimit)
                    : news.visibleItems(topics, seed, 0, 1);
            StringBuilder sb = new StringBuilder("{\"total\":").append(page.total + np);
            sb.append(",\"seed\":").append(seed).append(",\"offset\":").append(offset).append(",\"live\":").append(np)
                    .append(",\"items\":[");
            boolean first = true;
            for (String pin : head) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                sb.append(pin);
            }
            if (storyLimit <= 0) {
                page = new NewsService.Page(page.total, java.util.Collections.<NewsService.Item>emptyList());
            }
            for (NewsService.Item it : page.items) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                sb.append(itemJson(it, false));
            }
            send(ex, 200, sb.append("]}").toString());
        } catch (Exception e) {
            send(ex, 500, error("server error"));
        } finally {
            ex.close();
        }
    }

    /** GET /api/news.rss: the engine's output as an RSS 2.0 feed. Every story links to the original. */
    private void sendRss(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equalsIgnoreCase("GET")) {
            ex.getResponseHeaders().set("Allow", "GET");
            send(ex, 405, error("method not allowed"));
            return;
        }
        String host = ex.getRequestHeaders().getFirst("Host");
        if (host == null || !host.matches("[A-Za-z0-9.:\\-\\[\\]]{1,100}")) {
            host = "localhost";
        }
        String self = "http://" + host + "/api/news.rss";
        java.util.List<NewsService.Item> list = news.latestVisible(50);
        java.time.format.DateTimeFormatter fmt = java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME;
        StringBuilder sb = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<rss version=\"2.0\"><channel>");
        sb.append("<title>Memoryq News</title><link>").append(xml(self)).append("</link>");
        sb.append("<description>Headlines and short summaries from our news sources. Every story links to the original.</description>");
        sb.append("<language>en</language>");
        for (NewsService.Item it : list) {
            long when = it.published > 0 ? it.published : it.created;
            String desc = it.summary.isEmpty() ? "Source: " + it.source : it.summary + " (Source: " + it.source + ")";
            sb.append("<item><title>").append(xml(it.headline)).append("</title>");
            sb.append("<link>").append(xml(it.link)).append("</link>");
            sb.append("<description>").append(xml(desc)).append("</description>");
            sb.append("<category>").append(xml(it.topic)).append("</category>");
            sb.append("<guid isPermaLink=\"false\">vstream-").append(it.id).append("</guid>");
            sb.append("<pubDate>").append(fmt.format(java.time.Instant.ofEpochMilli(when).atOffset(java.time.ZoneOffset.UTC)))
                    .append("</pubDate></item>");
        }
        sb.append("</channel></rss>");
        byte[] bytes = sb.toString().getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/rss+xml; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        ex.sendResponseHeaders(200, bytes.length);
        OutputStream os = ex.getResponseBody();
        os.write(bytes);
        os.close();
    }

    private static String xml(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '&': sb.append("&amp;"); break;
                case '<': sb.append("&lt;"); break;
                case '>': sb.append("&gt;"); break;
                case '"': sb.append("&quot;"); break;
                case '\'': sb.append("&apos;"); break;
                default:
                    if (ch >= 0x20 || ch == '\t' || ch == '\n') {
                        sb.append(ch);
                    }
            }
        }
        return sb.toString();
    }

    private static String itemJson(NewsService.Item it, boolean admin) {
        StringBuilder sb = new StringBuilder("{\"id\":").append(it.id);
        sb.append(",\"headline\":").append(quote(it.headline));
        sb.append(",\"summary\":").append(quote(it.summary));
        sb.append(",\"topic\":").append(quote(it.topic));
        sb.append(",\"source\":").append(quote(it.source));
        sb.append(",\"link\":").append(quote(it.link));
        sb.append(",\"published\":").append(it.published);
        sb.append(",\"created\":").append(it.created);
        sb.append(",\"ai\":").append(it.ai);
        if (admin) {
            sb.append(",\"sourceId\":").append(it.sourceId);
            sb.append(",\"hidden\":").append(it.hidden);
        }
        return sb.append('}').toString();
    }

    // ---- Admin ----

    private void handleAdmin(HttpExchange ex) throws IOException {
        try {
            SessionStore.Session s = sessions.get(bearerToken(ex));
            UserStore.Info me = s == null ? null : users.info(s.username);
            if (me == null || me.disabled) {
                send(ex, 401, error("sign in required"));
                return;
            }
            if (me.role != UserStore.Role.ADMIN) {
                send(ex, 403, error("admin only"));
                return;
            }
            String path = ex.getRequestURI().getPath();
            String method = ex.getRequestMethod();
            switch (path) {
                case "/api/admin/news/status":
                    if (requireMethod(ex, method, "GET")) {
                        send(ex, 200, statusJson());
                    }
                    break;
                case "/api/admin/news/items": {
                    if (!requireMethod(ex, method, "GET")) {
                        break;
                    }
                    int limit = (int) parseLong(MediaAuthHttpServer.queryParam(
                            ex.getRequestURI().getRawQuery(), "limit"), 40, 1, 200);
                    StringBuilder sb = new StringBuilder("{\"items\":[");
                    boolean first = true;
                    for (NewsService.Item it : news.allItems(limit)) {
                        if (!first) {
                            sb.append(',');
                        }
                        first = false;
                        sb.append(itemJson(it, true));
                    }
                    send(ex, 200, sb.append("]}").toString());
                    break;
                }
                case "/api/admin/news/sources/add": {
                    if (!requireMethod(ex, method, "POST")) {
                        break;
                    }
                    String body = readBody(ex);
                    NewsService.Source src = news.addSource(MediaAuthHttpServer.queryParam(body, "name"),
                            MediaAuthHttpServer.queryParam(body, "url"));
                    send(ex, 200, "{\"id\":" + src.id + ",\"name\":" + quote(src.name) + "}");
                    break;
                }
                case "/api/admin/news/sources/remove": {
                    if (!requireMethod(ex, method, "POST")) {
                        break;
                    }
                    news.removeSource(parseLong(MediaAuthHttpServer.queryParam(readBody(ex), "id"), -1, -1, Long.MAX_VALUE));
                    send(ex, 200, "{\"ok\":true}");
                    break;
                }
                case "/api/admin/news/sources/toggle": {
                    if (!requireMethod(ex, method, "POST")) {
                        break;
                    }
                    String body = readBody(ex);
                    news.setEnabled(parseLong(MediaAuthHttpServer.queryParam(body, "id"), -1, -1, Long.MAX_VALUE),
                            !"false".equals(MediaAuthHttpServer.queryParam(body, "enabled")));
                    send(ex, 200, "{\"ok\":true}");
                    break;
                }
                case "/api/admin/news/pause": {
                    if (!requireMethod(ex, method, "POST")) {
                        break;
                    }
                    news.setPaused("true".equals(MediaAuthHttpServer.queryParam(readBody(ex), "paused")));
                    send(ex, 200, "{\"ok\":true}");
                    break;
                }
                case "/api/admin/news/items/hide": {
                    if (!requireMethod(ex, method, "POST")) {
                        break;
                    }
                    String body = readBody(ex);
                    news.setHidden(parseLong(MediaAuthHttpServer.queryParam(body, "id"), -1, -1, Long.MAX_VALUE),
                            !"false".equals(MediaAuthHttpServer.queryParam(body, "hidden")));
                    send(ex, 200, "{\"ok\":true}");
                    break;
                }
                case "/api/admin/news/refresh": {
                    if (!requireMethod(ex, method, "POST")) {
                        break;
                    }
                    String blocker = news.refreshBlocker();
                    if (blocker != null) {
                        send(ex, 200, "{\"started\":false,\"reason\":" + quote(blocker) + "}");
                        break;
                    }
                    Thread t = new Thread(new Runnable() {
                        @Override
                        public void run() {
                            news.refreshAll();
                        }
                    }, "news-refresh");
                    t.setDaemon(true);
                    t.start();
                    send(ex, 200, "{\"started\":true}");
                    break;
                }
                default:
                    send(ex, 404, error("not found"));
            }
        } catch (NewsService.NewsException e) {
            send(ex, e.status, error(e.getMessage()));
        } catch (Exception e) {
            send(ex, 500, error("server error"));
        } finally {
            ex.close();
        }
    }

    private String statusJson() {
        NewsService.Status st = news.status();
        StringBuilder sb = new StringBuilder("{\"paused\":").append(st.paused);
        sb.append(",\"keyFound\":").append(st.keyFound);
        sb.append(",\"model\":").append(quote(st.model == null ? "" : st.model));
        sb.append(",\"usageCalls\":").append(st.usageCalls);
        sb.append(",\"usageIn\":").append(st.usageIn);
        sb.append(",\"usageOut\":").append(st.usageOut);
        sb.append(",\"dailyCap\":").append(st.dailyCap);
        sb.append(",\"lastRun\":").append(st.lastRun);
        sb.append(",\"lastRunError\":").append(quote(st.lastRunError));
        sb.append(",\"itemCount\":").append(st.itemCount);
        sb.append(",\"engineNote\":").append(quote(st.engineNote == null ? "" : st.engineNote));
        sb.append(",\"sources\":[");
        boolean first = true;
        for (NewsService.Source s : st.sources) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append("{\"id\":").append(s.id);
            sb.append(",\"name\":").append(quote(s.name));
            sb.append(",\"url\":").append(quote(s.url));
            sb.append(",\"enabled\":").append(s.enabled);
            sb.append(",\"lastFetch\":").append(s.lastFetch);
            sb.append(",\"lastError\":").append(quote(s.lastError));
            sb.append(",\"items\":").append(s.items).append('}');
        }
        return sb.append("]}").toString();
    }

    // ---- Helpers ----

    private static String bearerToken(HttpExchange ex) {
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        if (auth == null || !auth.startsWith("Bearer ")) {
            return null;
        }
        return auth.substring(7).trim();
    }

    private static long parseLong(String text, long fallback, long min, long max) {
        if (text == null) {
            return fallback;
        }
        try {
            long v = Long.parseLong(text.trim());
            return Math.max(min, Math.min(max, v));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private boolean requireMethod(HttpExchange ex, String actual, String wanted) throws IOException {
        if (actual.equalsIgnoreCase(wanted)) {
            return true;
        }
        ex.getResponseHeaders().set("Allow", wanted);
        send(ex, 405, error("method not allowed"));
        return false;
    }

    private static String readBody(HttpExchange ex) throws IOException {
        InputStream in = ex.getRequestBody();
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] tmp = new byte[1024];
        int total = 0;
        int n;
        while ((n = in.read(tmp)) != -1) {
            total += n;
            if (total > MAX_BODY) {
                throw new IOException("request body too large");
            }
            buf.write(tmp, 0, n);
        }
        return new String(buf.toByteArray(), StandardCharsets.UTF_8);
    }

    private static String error(String message) {
        return "{\"error\":" + quote(message) + "}";
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

    private static String quote(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n");  break;
                case '\r': sb.append("\\r");  break;
                case '\t': sb.append("\\t");  break;
                default:
                    if (ch < 0x20) {
                        sb.append(String.format("\\u%04x", (int) ch));
                    } else {
                        sb.append(ch);
                    }
            }
        }
        return sb.append('"').toString();
    }
}