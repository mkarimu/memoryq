package com.vstream.Vidiserver;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Profiler -> Vistr players: every Vistr player gets a Memoryq account (one account everywhere).
 *   - a player already linked to a Memoryq account: kept as it is ("linked")
 *   - the name is free on Memoryq: an account with that name ("new")
 *   - the name is taken by someone else: an account called MQ + the name (MQmkarimu), waiting for the super-admin
 *     to choose ("waiting") - rename it, or keep it
 * The Vistr player is linked to the account, so "Sign in with Memoryq" in the game opens the same player.
 * New accounts get a random password (the player keeps signing in to Vistr with the Player ID); the super-admin can set
 * a password, an email and every part of the profile (Admin -> Users -> Account details -> Edit everything).
 *   GET  /api/admin/vistr/players                  the list
 *   POST /api/admin/vistr/import                   fetch the players from the game and bring the new ones in
 *   POST /api/admin/vistr/rename   code, username  a better Memoryq username (accounts made here only)
 *   POST /api/admin/vistr/keep     code            keep the MQ name
 *   POST /api/admin/vistr/player   code, newCode, name     change the player in the game (name, Player ID)
 *   POST /api/admin/vistr/email    code            the player's email becomes the account's email
 *   GET  /api/admin/member?u=     POST /api/admin/member/edit   the whole profile of any member, for the super-admin
 */
public final class VistrImport {

    public interface Web {
        /** method, address, JSON body (null for GET) -> {status, body}. */
        String[] call(String method, String url, String json) throws IOException;
    }

    static final class Row {
        String code = "";
        String name = "";
        String email = "";
        String mq = "";
        String status = "";
        String note = "";
        long at;
        boolean made; // the account was made by the import (it may be renamed)
    }

    private final Path file;
    private final UserStore users;
    private final SessionStore sessions;
    private final ProfileStore profiles;
    private final EmailService emails;
    private final ServiceAccess services;
    private final SignInApi signIn;
    private final LongSupplier clock;
    private final Map<String, Row> rows = new LinkedHashMap<String, Row>(); // Vistr Player ID -> row
    private String vistrUrl = System.getProperty("vstream.vistrUrl", "https://vistr.memoryq.com");
    private volatile Web web = VistrImport::http;
    private String adminKey = "";
    private long adminKeyAt;
    private long lastImport;
    private String lastNote = "not yet";
    private static final SecureRandom RANDOM = new SecureRandom();

    public VistrImport(Path file, UserStore users, SessionStore sessions, ProfileStore profiles, EmailService emails, ServiceAccess services,
                       SignInApi signIn, LongSupplier clock) {
        this.file = file;
        this.users = users;
        this.sessions = sessions;
        this.profiles = profiles;
        this.emails = emails;
        this.services = services;
        this.signIn = signIn;
        this.clock = clock;
    }

    void setWeb(Web w, String url) {
        this.web = w;
        this.vistrUrl = url;
    }

    // ---- the file ----

    private static String c(String s, int max) {
        String t = s == null ? "" : s.replaceAll("[\\t\\r\\n]+", " ").trim();
        return t.length() > max ? t.substring(0, max) : t;
    }

    public synchronized void init() {
        if (file == null || !Files.isRegularFile(file)) {
            return;
        }
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String[] f = line.split("\t", -1);
                if (f.length >= 3 && f[0].equals("#")) {
                    lastImport = Long.parseLong(f[1]);
                    lastNote = f[2];
                } else if (f.length >= 8) {
                    Row r = new Row();
                    r.code = f[0];
                    r.name = f[1];
                    r.email = f[2];
                    r.mq = f[3];
                    r.status = f[4];
                    r.note = f[5];
                    r.at = Long.parseLong(f[6]);
                    r.made = "1".equals(f[7]);
                    rows.put(r.code, r);
                }
            }
        } catch (Exception e) {
            System.out.println("[vistr-import] could not read " + file + ": " + e.getMessage());
        }
    }

    private synchronized void save() {
        if (file == null) {
            return;
        }
        StringBuilder sb = new StringBuilder("#\t").append(lastImport).append('\t').append(c(lastNote, 300)).append('\n');
        for (Row r : rows.values()) {
            sb.append(r.code).append('\t').append(c(r.name, 60)).append('\t').append(c(r.email, 200)).append('\t').append(r.mq).append('\t')
                    .append(r.status).append('\t').append(c(r.note, 200)).append('\t').append(r.at).append('\t').append(r.made ? 1 : 0).append('\n');
        }
        try {
            Path part = file.resolveSibling(file.getFileName() + ".part");
            Files.write(part, sb.toString().getBytes(StandardCharsets.UTF_8));
            Files.move(part, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            System.out.println("[vistr-import] could not save: " + e.getMessage());
        }
    }

    // ---- talking to the game ----

    static String[] http(String method, String url, String json) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setRequestMethod(method);
            c.setConnectTimeout(10000);
            c.setReadTimeout(30000);
            if (json != null) {
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json");
                try (OutputStream os = c.getOutputStream()) {
                    os.write(json.getBytes(StandardCharsets.UTF_8));
                }
            }
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            return new String[]{String.valueOf(code), in == null ? "" : new String(AudioTools.readAll(in), StandardCharsets.UTF_8)};
        } finally {
            c.disconnect();
        }
    }

    static String js(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (char ch : (s == null ? "" : s).toCharArray()) {
            if (ch == '"' || ch == '\\') {
                sb.append('\\').append(ch);
            } else if (ch < 32) {
                sb.append(String.format("\\u%04x", (int) ch));
            } else {
                sb.append(ch);
            }
        }
        return sb.append('"').toString();
    }

    /** The game's admin access, as the super-admin (the game checks it with memoryq.com). */
    private String key(String adminUser) throws IOException {
        synchronized (this) {
            if (!adminKey.isEmpty() && clock.getAsLong() - adminKeyAt < 12 * 3600000L) {
                return adminKey;
            }
        }
        String code = signIn.codeFor(adminUser, "vistr-admin", SessionStore.DAY_MS);
        String[] r = web.call("POST", vistrUrl + "/api/auth/memoryq", "{\"code\":" + js(code) + ",\"admin\":true}");
        Object o;
        try {
            o = MiniJson.parse(r[1]);
        } catch (RuntimeException e) {
            o = null;
        }
        String k = MiniJson.str(o, "adminKey");
        if (!r[0].equals("200") || k.isEmpty()) {
            String err = MiniJson.str(o, "error");
            throw new IOException(r[0].equals("404") ? "the game does not know Memoryq sign-in yet - run \"sudo bash setup-server.sh update\" on the server first"
                    : "the game did not let us in" + (err.isEmpty() ? " (" + r[0] + ")" : ": " + err));
        }
        synchronized (this) {
            adminKey = k;
            adminKeyAt = clock.getAsLong();
        }
        return k;
    }

    private String[] vistr(String adminUser, String method, String path, String json) throws IOException {
        String k = key(adminUser);
        String[] r = web.call(method, vistrUrl + path + (path.contains("?") ? "&" : "?") + "key=" + java.net.URLEncoder.encode(k, "UTF-8"), json);
        if (r[0].equals("403")) { // the key ended: once more with a new one
            synchronized (this) {
                adminKey = "";
            }
            k = key(adminUser);
            r = web.call(method, vistrUrl + path + (path.contains("?") ? "&" : "?") + "key=" + java.net.URLEncoder.encode(k, "UTF-8"), json);
        }
        return r;
    }

    // ---- bringing the players in ----

    /** A Memoryq username from a player's name (or email, or Player ID). */
    static String baseName(String name, String email, String code) {
        String b = name == null ? "" : name.trim().replaceAll("\\s+", "_").replaceAll("[^A-Za-z0-9_]", "");
        if (b.length() < 3 && email != null && email.contains("@") && !email.endsWith("@users.memoryq.com")) {
            b = email.substring(0, email.indexOf('@')).replaceAll("[^A-Za-z0-9_]", "_").replaceAll("_+", "_").replaceAll("^_|_$", "");
        }
        if (b.length() < 3) {
            b = "player" + code;
        }
        return b.length() > 22 ? b.substring(0, 22) : b;
    }

    private String freeName(String want) {
        String n = want.length() > 24 ? want.substring(0, 24) : want;
        for (int i = 2; users.info(n) != null; i++) {
            String tail = String.valueOf(i);
            n = (want.length() + tail.length() > 24 ? want.substring(0, 24 - tail.length()) : want) + tail;
        }
        return n;
    }

    private UserStore.Info make(String username, String playerName) throws UserStore.UserException {
        byte[] b = new byte[18];
        RANDOM.nextBytes(b);
        UserStore.Info i = users.createUser(username, java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(b), UserStore.Role.CHATTER);
        if (profiles != null && playerName != null && !playerName.trim().isEmpty()) {
            String[] parts = playerName.trim().split("\\s+", 2);
            profiles.edit(i.username, parts[0], parts.length > 1 ? parts[1] : "", playerName.trim(), null, null, null, null, clock.getAsLong());
        }
        return i;
    }

    /** Fetches the game's players and brings the new ones in. Returns a short report. */
    public String importAll(String adminUser) throws IOException {
        String[] r = vistr(adminUser, "GET", "/api/admin/players", null);
        if (!r[0].equals("200")) {
            throw new IOException("the game answered " + r[0]);
        }
        Object list = MiniJson.at(MiniJson.parse(r[1]), "players");
        if (!(list instanceof List)) {
            throw new IOException("the game sent no player list");
        }
        int fresh = 0, waiting = 0, linked = 0, known = 0;
        for (Object p : (List<?>) list) {
            String code = MiniJson.str(p, "playerCode"); // the game's list says playerCode
            if (code.isEmpty()) {
                code = MiniJson.str(p, "code");
            }
            if (!code.matches("\\d{1,6}")) {
                continue;
            }
            String name = MiniJson.str(p, "name"), email = MiniJson.str(p, "email").toLowerCase(Locale.ROOT), mq = MiniJson.str(p, "mq");
            Row row;
            synchronized (this) {
                row = rows.get(code);
            }
            if (row != null && users.info(row.mq) != null) {
                known++;
                synchronized (this) {
                    row.name = name;
                    row.email = email;
                }
                continue;
            }
            row = new Row();
            row.code = code;
            row.name = name;
            row.email = email;
            row.at = clock.getAsLong();
            try {
                if (!mq.isEmpty() && users.info(mq) != null) {
                    row.mq = users.info(mq).username;
                    row.status = "linked";
                    row.note = "already one account (signed in to Vistr with Memoryq)";
                    linked++;
                } else {
                    String base = baseName(name, email, code);
                    UserStore.Info same = users.info(base);
                    String owner = emails == null || email.isEmpty() ? null : emails.ownerOf(email);
                    if (owner != null && users.info(owner) != null && users.info(owner).role != UserStore.Role.ADMIN) {
                        row.mq = users.info(owner).username;
                        row.status = "linked";
                        row.note = "same email as this member";
                        linked++;
                    } else if (same == null && UserStore.validUsername(base)) {
                        row.mq = make(base, name).username;
                        row.status = "new";
                        row.made = true;
                        fresh++;
                    } else {
                        row.mq = make(freeName("MQ" + base), name).username;
                        row.status = "waiting";
                        row.made = true;
                        row.note = (same != null ? "\"" + same.username + "\" is already a Memoryq member" : "\"" + base + "\" cannot be a username")
                                + " - choose a name, or keep " + row.mq;
                        waiting++;
                    }
                }
                String[] l = vistr(adminUser, "POST", "/api/admin/player", "{\"code\":" + js(code) + ",\"mq\":" + js(row.mq) + "}");
                if (!l[0].equals("200")) {
                    row.note = (row.note.isEmpty() ? "" : row.note + "; ") + "the game did not save the link (" + l[0] + ")";
                }
            } catch (UserStore.UserException e) {
                row.status = "error";
                row.note = e.getMessage();
            }
            synchronized (this) {
                rows.put(code, row);
            }
        }
        String note = ((List<?>) list).size() + " players: " + fresh + " new accounts, " + waiting + " waiting for a name (MQ...), " + linked
                + " linked to existing members, " + known + " already brought in";
        synchronized (this) {
            lastImport = clock.getAsLong();
            lastNote = note;
        }
        save();
        if (profiles != null) {
            profiles.flushIfDirty();
        }
        System.out.println("[vistr-import] " + note);
        return note;
    }

    /** Moves an account made here to a better username (profile, email, services, Google and the game's link follow). */
    String rename(String adminUser, String code, String to) throws Exception {
        Row row;
        synchronized (this) {
            row = rows.get(code);
        }
        if (row == null) {
            throw new Bad(404, "no such player");
        }
        if (!row.made) {
            throw new Bad(400, "this account was not made from Vistr - it keeps its name");
        }
        String from = row.mq;
        UserStore.Info i = users.rename(from, to);
        if (profiles != null) {
            profiles.rename(from, i.username);
            profiles.flushIfDirty();
        }
        if (emails != null) {
            emails.rename(from, i.username);
        }
        if (services != null) {
            services.rename(from, i.username);
        }
        if (signIn != null) {
            signIn.rename(from, i.username);
        }
        sessions.removeUser(from);
        synchronized (this) {
            row.mq = i.username;
            row.status = "named";
            row.note = "renamed from " + from;
        }
        String[] l = vistr(adminUser, "POST", "/api/admin/player", "{\"code\":" + js(code) + ",\"mq\":" + js(i.username) + "}");
        if (!l[0].equals("200")) {
            row.note += "; the game did not save the new link (" + l[0] + ")";
        }
        save();
        return rowJson(row);
    }

    // ---- HTTP ----

    static final class Bad extends Exception {
        final int status;

        Bad(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    public void register(HttpServer server) {
        server.createContext("/api/admin/vistr/", this::handle);
        server.createContext("/api/admin/member", this::handleProfile);
    }

    private static String q(String s) {
        return ProfileApi.quote(s == null ? "" : s);
    }

    private static String p(String form, String k) {
        String v = MediaAuthHttpServer.queryParam(form, k);
        return v == null ? "" : v.trim();
    }

    private String admin(HttpExchange ex) {
        String a = ex.getRequestHeaders().getFirst("Authorization");
        SessionStore.Session s = sessions.get(a != null && a.startsWith("Bearer ") ? a.substring(7).trim() : "");
        return s != null && s.role == UserStore.Role.ADMIN ? s.username : null;
    }

    private synchronized String rowJson(Row r) {
        UserStore.Info i = users.info(r.mq);
        EmailService.Entry e = emails == null || i == null ? null : emails.get(i.username);
        return "{\"code\":" + q(r.code) + ",\"name\":" + q(r.name) + ",\"email\":" + q(r.email) + ",\"mq\":" + q(r.mq) + ",\"exists\":" + (i != null)
                + ",\"status\":" + q(r.status) + ",\"note\":" + q(r.note) + ",\"made\":" + r.made + ",\"at\":" + r.at
                + ",\"mqEmail\":" + q(e == null ? "" : e.email) + "}";
    }

    private void handle(HttpExchange ex) throws IOException {
        int status = 200;
        String out;
        try {
            String who = admin(ex);
            if (who == null) {
                throw new Bad(403, "super-admin only");
            }
            String op = ex.getRequestURI().getPath().substring("/api/admin/vistr/".length());
            if (op.equals("players")) {
                StringBuilder sb = new StringBuilder();
                int w = 0;
                synchronized (this) {
                    sb.append("{\"vistr\":").append(q(vistrUrl)).append(",\"lastImport\":").append(lastImport).append(",\"note\":").append(q(lastNote)).append(",\"rows\":[");
                    int n = 0;
                    List<Row> list = new ArrayList<Row>(rows.values());
                    list.sort((a, b) -> ("waiting".equals(b.status) ? 1 : 0) - ("waiting".equals(a.status) ? 1 : 0));
                    for (Row r : list) {
                        sb.append(n++ > 0 ? "," : "").append(rowJson(r));
                        if ("waiting".equals(r.status)) {
                            w++;
                        }
                    }
                }
                out = sb.append("],\"waiting\":").append(w).append('}').toString();
            } else {
                if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
                    throw new Bad(405, "use POST");
                }
                String form = SuperAgent.readBody(ex, 8192);
                String code = p(form, "code");
                Row row;
                synchronized (this) {
                    row = rows.get(code);
                }
                switch (op) {
                    case "import":
                        try {
                            out = "{\"note\":" + q(importAll(who)) + "}";
                        } catch (IOException e) {
                            synchronized (this) {
                                lastNote = "could not reach the game: " + e.getMessage();
                            }
                            throw new Bad(502, "Vistr: " + e.getMessage());
                        }
                        break;
                    case "rename":
                        try {
                            out = rename(who, code, p(form, "username"));
                        } catch (UserStore.UserException e) {
                            throw new Bad(400, e.getMessage());
                        }
                        break;
                    case "keep":
                        if (row == null) {
                            throw new Bad(404, "no such player");
                        }
                        synchronized (this) {
                            row.status = "named";
                            row.note = "the super-admin kept " + row.mq;
                        }
                        save();
                        out = rowJson(row);
                        break;
                    case "player": {
                        if (row == null) {
                            throw new Bad(404, "no such player");
                        }
                        String newCode = p(form, "newCode").replaceAll("\\D", "");
                        String name = MediaAuthHttpServer.queryParam(form, "name");
                        StringBuilder j = new StringBuilder("{\"code\":").append(js(code));
                        if (!newCode.isEmpty() && !newCode.equals(code)) {
                            j.append(",\"newCode\":").append(js(newCode));
                        }
                        if (name != null) {
                            j.append(",\"name\":").append(js(name.trim()));
                        }
                        String[] r = vistr(who, "POST", "/api/admin/player", j.append('}').toString());
                        if (!r[0].equals("200")) {
                            throw new Bad(Integer.parseInt(r[0]) == 409 ? 409 : 502, "Vistr: " + MiniJson.str(MiniJson.parse(r[1]), "error"));
                        }
                        Object pl = MiniJson.at(MiniJson.parse(r[1]), "player");
                        synchronized (this) {
                            rows.remove(code);
                            row.code = MiniJson.str(pl, "code");
                            row.name = MiniJson.str(pl, "name");
                            rows.put(row.code, row);
                        }
                        save();
                        out = rowJson(row);
                        break;
                    }
                    case "email": {
                        if (row == null || users.info(row.mq) == null) {
                            throw new Bad(404, "no such player");
                        }
                        if (row.email.isEmpty() || row.email.endsWith("@users.memoryq.com")) {
                            throw new Bad(400, "this player has no email address");
                        }
                        try {
                            emails.adminSet(row.mq, row.email);
                        } catch (EmailService.EmailException e) {
                            throw new Bad(400, e.getMessage());
                        }
                        out = rowJson(row);
                        break;
                    }
                    default:
                        throw new Bad(404, "not found");
                }
            }
        } catch (Bad e) {
            status = e.status;
            out = "{\"error\":" + q(e.getMessage()) + "}";
        } catch (Exception e) {
            status = 500;
            out = "{\"error\":" + q("server error: " + e.getMessage()) + "}";
        }
        send(ex, status, out);
    }

    // ---- the whole profile, for the super-admin ----

    private String profileJson(UserStore.Info i) {
        ProfileStore.Profile pr = profiles == null ? null : profiles.get(i.username);
        EmailService.Entry e = emails == null ? null : emails.get(i.username);
        Row from = null;
        synchronized (this) {
            for (Row r : rows.values()) {
                if (r.mq.equalsIgnoreCase(i.username)) {
                    from = r;
                }
            }
        }
        StringBuilder sb = new StringBuilder("{\"username\":").append(q(i.username)).append(",\"role\":").append(q(i.role.name()))
                .append(",\"disabled\":").append(i.disabled).append(",\"email\":").append(q(e == null ? "" : e.email))
                .append(",\"emailVerified\":").append(e != null && e.verified)
                .append(",\"renamable\":").append(from != null && from.made).append(",\"vistrCode\":").append(q(from == null ? "" : from.code));
        if (pr != null) {
            sb.append(",\"firstName\":").append(q(pr.firstName)).append(",\"lastName\":").append(q(pr.lastName)).append(",\"stageName\":").append(q(pr.stageName))
                    .append(",\"city\":").append(q(pr.city)).append(",\"country\":").append(q(pr.country)).append(",\"education\":").append(q(pr.education))
                    .append(",\"hobbies\":").append(q(String.join(", ", pr.hobbies))).append(",\"color\":").append(q(pr.color));
        }
        return sb.append('}').toString();
    }

    private void handleProfile(HttpExchange ex) throws IOException {
        int status = 200;
        String out;
        try {
            String who = admin(ex);
            if (who == null) {
                throw new Bad(403, "super-admin only");
            }
            String path = ex.getRequestURI().getPath();
            if (path.equals("/api/admin/member")) {
                UserStore.Info i = users.info(p(ex.getRequestURI().getRawQuery(), "u"));
                if (i == null) {
                    throw new Bad(404, "no such member");
                }
                out = profileJson(i);
            } else if (path.equals("/api/admin/member/edit") && "POST".equalsIgnoreCase(ex.getRequestMethod())) {
                String form = SuperAgent.readBody(ex, 16384);
                UserStore.Info i = users.info(p(form, "u"));
                if (i == null) {
                    throw new Bad(404, "no such member");
                }
                List<String> changed = new ArrayList<String>();
                String newName = p(form, "username");
                if (!newName.isEmpty() && !newName.equals(i.username)) {
                    String code = null;
                    synchronized (this) {
                        for (Row r : rows.values()) {
                            if (r.made && r.mq.equalsIgnoreCase(i.username)) {
                                code = r.code;
                            }
                        }
                    }
                    if (code == null) {
                        throw new Bad(400, "only accounts brought in from Vistr can get a new username (others have chats, posts and friends under their name)");
                    }
                    try {
                        rename(who, code, newName);
                    } catch (UserStore.UserException e) {
                        throw new Bad(400, e.getMessage());
                    }
                    i = users.info(newName);
                    changed.add("username");
                }
                if (profiles != null) {
                    List<String> hobbies = null;
                    String hb = MediaAuthHttpServer.queryParam(form, "hobbies");
                    if (hb != null) {
                        hobbies = new ArrayList<String>();
                        for (String h : hb.split(",")) {
                            if (!h.trim().isEmpty()) {
                                hobbies.add(h.trim());
                            }
                        }
                    }
                    profiles.edit(i.username, MediaAuthHttpServer.queryParam(form, "firstName"), MediaAuthHttpServer.queryParam(form, "lastName"),
                            MediaAuthHttpServer.queryParam(form, "stageName"), MediaAuthHttpServer.queryParam(form, "city"),
                            MediaAuthHttpServer.queryParam(form, "country"), MediaAuthHttpServer.queryParam(form, "education"), hobbies, clock.getAsLong());
                    String color = MediaAuthHttpServer.queryParam(form, "color");
                    if (color != null) {
                        String okc = color.trim().isEmpty() ? "" : ProfileStore.checkColor(color.trim());
                        if (okc == null) {
                            throw new Bad(400, "the colour must be a darker colour like #2f6fed (white writing goes on it)");
                        }
                        profiles.setColor(i.username, okc, clock.getAsLong());
                    }
                    profiles.flushIfDirty();
                    changed.add("profile");
                }
                String email = p(form, "email");
                EmailService.Entry e = emails == null ? null : emails.get(i.username);
                if (emails != null && !email.isEmpty() && (e == null || !e.email.equalsIgnoreCase(email))) {
                    try {
                        emails.adminSet(i.username, email);
                    } catch (EmailService.EmailException x) {
                        throw new Bad(400, x.getMessage());
                    }
                    changed.add("email");
                }
                String pw = MediaAuthHttpServer.queryParam(form, "password");
                if (pw != null && !pw.isEmpty()) {
                    try {
                        users.setPassword(i.username, pw);
                    } catch (UserStore.UserException x) {
                        throw new Bad(400, x.getMessage());
                    }
                    changed.add("password");
                }
                System.out.println("[admin] " + who + " edited " + i.username + ": " + changed);
                out = profileJson(i);
            } else {
                throw new Bad(404, "not found");
            }
        } catch (Bad e) {
            status = e.status;
            out = "{\"error\":" + q(e.getMessage()) + "}";
        } catch (Exception e) {
            status = 500;
            out = "{\"error\":" + q("server error: " + e.getMessage()) + "}";
        }
        send(ex, status, out);
    }

    private static void send(HttpExchange ex, int status, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(status, b.length);
        ex.getResponseBody().write(b);
        ex.close();
    }
}