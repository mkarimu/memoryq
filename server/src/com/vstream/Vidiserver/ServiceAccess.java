package com.vstream.Vidiserver;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Which Memoryq services each member sees (the services menu at the top of memoryq.com/me and the app's menu).
 * The super-admin ticks a template (what new members get) and can tick a member's own list in Admin -> Profiler -> Services.
 * Members without their own list follow the template. Admin accounts always see everything; Notifications and Profile
 * are always there.
 *   GET  /api/admin/services?q=&page=     the catalogue, the template and the members (50 a page)
 *   POST /api/admin/services/template     keys=home,radio,...
 *   POST /api/admin/services/user         username, keys=...   (or reset=1: back to the template)
 *   POST /api/admin/services/everyone     everyone back to the template
 */
public final class ServiceAccess {

    /** key, name, where it opens. */
    static final String[][] CATALOG = {
        {"home", "News feed", "/me#home"},
        {"wall", "My wall", "/me#wall"},
        {"people", "People", "/me#people"},
        {"chat", "Chats", "/me#chat"},
        {"calls", "Calls", "/me#calls"},
        {"radio", "Radio", "/me#radio"},
        {"world", "World Radio", "/world"},
        {"videos", "Videos", "/me#videos"},
        {"tv", "Memoryq TV", "/tv"},
        {"live", "On air (go live)", "/me#live"},
        {"market", "Marketplace", "/market"},
        {"vistr", "Vistr word game", "https://vistr.memoryq.com/"},
    };

    static final Set<String> KEYS = new LinkedHashSet<String>();

    static {
        for (String[] c : CATALOG) {
            KEYS.add(c[0]);
        }
    }

    private final Path file;
    private final UserStore users;
    private final SessionStore sessions;
    private List<String> template = new ArrayList<String>(KEYS);
    private final Map<String, List<String>> own = new LinkedHashMap<String, List<String>>(); // lower-case username -> keys

    public ServiceAccess(Path file, UserStore users, SessionStore sessions) {
        this.file = file;
        this.users = users;
        this.sessions = sessions;
    }

    static List<String> parse(String keys) {
        List<String> out = new ArrayList<String>();
        for (String k : (keys == null ? "" : keys).toLowerCase(Locale.ROOT).split("[\\s,]+")) {
            if (KEYS.contains(k) && !out.contains(k)) {
                out.add(k);
            }
        }
        List<String> ordered = new ArrayList<String>(); // catalogue order
        for (String k : KEYS) {
            if (out.contains(k)) {
                ordered.add(k);
            }
        }
        return ordered;
    }

    public synchronized void init() {
        if (file == null || !Files.isRegularFile(file)) {
            return;
        }
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String[] f = line.split("\t", -1);
                if (f.length >= 2 && f[0].equals("T")) {
                    template = parse(f[1]);
                } else if (f.length >= 3 && f[0].equals("U")) {
                    own.put(f[1].toLowerCase(Locale.ROOT), parse(f[2]));
                }
            }
        } catch (IOException e) {
            System.out.println("[services] could not read " + file + ": " + e.getMessage());
        }
    }

    private synchronized void save() {
        if (file == null) {
            return;
        }
        StringBuilder sb = new StringBuilder("T\t").append(String.join(",", template)).append('\n');
        for (Map.Entry<String, List<String>> e : own.entrySet()) {
            sb.append("U\t").append(e.getKey()).append('\t').append(String.join(",", e.getValue())).append('\n');
        }
        try {
            Path part = file.resolveSibling(file.getFileName() + ".part");
            Files.write(part, sb.toString().getBytes(StandardCharsets.UTF_8));
            Files.move(part, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            System.out.println("[services] could not save: " + e.getMessage());
        }
    }

    /** What this member sees. */
    public synchronized List<String> servicesOf(String username, UserStore.Role role) {
        if (role == UserStore.Role.ADMIN) {
            return new ArrayList<String>(KEYS);
        }
        List<String> o = username == null ? null : own.get(username.toLowerCase(Locale.ROOT));
        return new ArrayList<String>(o != null ? o : template);
    }

    public synchronized List<String> template() {
        return new ArrayList<String>(template);
    }

    public synchronized void setTemplate(List<String> keys) {
        template = new ArrayList<String>(keys);
        save();
    }

    public synchronized void setOwn(String username, List<String> keys) {
        if (keys == null) {
            own.remove(username.toLowerCase(Locale.ROOT));
        } else {
            own.put(username.toLowerCase(Locale.ROOT), new ArrayList<String>(keys));
        }
        save();
    }

    public synchronized boolean hasOwn(String username) {
        return own.containsKey(username.toLowerCase(Locale.ROOT));
    }

    public synchronized void everyone() {
        own.clear();
        save();
    }

    /** For the JSON of a sign-in: ["home","radio",...]. */
    public String json(String username, UserStore.Role role) {
        StringBuilder sb = new StringBuilder("[");
        List<String> l = servicesOf(username, role);
        for (int i = 0; i < l.size(); i++) {
            sb.append(i > 0 ? "," : "").append('"').append(l.get(i)).append('"');
        }
        return sb.append(']').toString();
    }

    // ---- HTTP ----

    public void register(HttpServer server) {
        server.createContext("/api/admin/services", this::handle);
    }

    private static String q(String s) {
        return ProfileApi.quote(s == null ? "" : s);
    }

    private static String list(List<String> l) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < l.size(); i++) {
            sb.append(i > 0 ? "," : "").append(q(l.get(i)));
        }
        return sb.append(']').toString();
    }

    private void handle(HttpExchange ex) throws IOException {
        int status = 200;
        String body;
        try {
            if (!SuperAgent.isAdmin(ex, sessions)) {
                status = 403;
                body = "{\"error\":\"super-admin only\"}";
            } else {
                String path = ex.getRequestURI().getPath();
                String op = path.length() > "/api/admin/services".length() ? path.substring("/api/admin/services/".length()) : "";
                if (op.isEmpty()) {
                    body = page(ex.getRequestURI().getRawQuery());
                } else if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
                    status = 405;
                    body = "{\"error\":\"use POST\"}";
                } else {
                    String form = SuperAgent.readBody(ex, 8192);
                    String keys = MediaAuthHttpServer.queryParam(form, "keys");
                    if (op.equals("template")) {
                        setTemplate(parse(keys));
                        body = "{\"template\":" + list(template()) + "}";
                    } else if (op.equals("everyone")) {
                        everyone();
                        body = "{\"ok\":true}";
                    } else if (op.equals("user")) {
                        String u = MediaAuthHttpServer.queryParam(form, "username");
                        UserStore.Info i = u == null ? null : users.info(u.trim());
                        if (i == null) {
                            status = 404;
                            body = "{\"error\":\"no such member\"}";
                        } else {
                            setOwn(i.username, "1".equals(MediaAuthHttpServer.queryParam(form, "reset")) ? null : parse(keys));
                            body = "{\"username\":" + q(i.username) + ",\"own\":" + hasOwn(i.username) + ",\"services\":" + list(servicesOf(i.username, i.role)) + "}";
                        }
                    } else {
                        status = 404;
                        body = "{\"error\":\"not found\"}";
                    }
                }
            }
        } catch (Exception e) {
            status = 500;
            body = "{\"error\":" + q("server error: " + e.getMessage()) + "}";
        }
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(status, b.length);
        ex.getResponseBody().write(b);
        ex.close();
    }

    private String page(String query) {
        String qq = MediaAuthHttpServer.queryParam(query, "q");
        String needle = qq == null ? "" : qq.trim().toLowerCase(Locale.ROOT);
        int page = 0;
        try {
            page = Math.max(0, Integer.parseInt(String.valueOf(MediaAuthHttpServer.queryParam(query, "page"))));
        } catch (NumberFormatException e) {
            page = 0;
        }
        List<UserStore.Info> all = new ArrayList<UserStore.Info>();
        for (UserStore.Info i : users.listInfos()) {
            if (i.role != UserStore.Role.GUEST && (needle.isEmpty() || i.username.toLowerCase(Locale.ROOT).contains(needle))) {
                all.add(i);
            }
        }
        all.sort((a, b) -> a.username.compareToIgnoreCase(b.username));
        StringBuilder sb = new StringBuilder("{\"catalog\":[");
        for (int i = 0; i < CATALOG.length; i++) {
            sb.append(i > 0 ? "," : "").append("{\"key\":").append(q(CATALOG[i][0])).append(",\"name\":").append(q(CATALOG[i][1]))
                    .append(",\"href\":").append(q(CATALOG[i][2])).append('}');
        }
        sb.append("],\"template\":").append(list(template())).append(",\"count\":").append(all.size()).append(",\"page\":").append(page)
                .append(",\"pages\":").append((all.size() + 49) / 50).append(",\"users\":[");
        int from = Math.min(all.size(), page * 50);
        List<UserStore.Info> slice = all.subList(from, Math.min(all.size(), from + 50));
        for (int i = 0; i < slice.size(); i++) {
            UserStore.Info u = slice.get(i);
            sb.append(i > 0 ? "," : "").append("{\"username\":").append(q(u.username)).append(",\"role\":").append(q(u.role.name()))
                    .append(",\"own\":").append(hasOwn(u.username)).append(",\"services\":").append(list(servicesOf(u.username, u.role))).append('}');
        }
        return sb.append("]}").toString();
    }

    static List<String> all() {
        return new ArrayList<String>(Arrays.asList(KEYS.toArray(new String[0])));
    }
}