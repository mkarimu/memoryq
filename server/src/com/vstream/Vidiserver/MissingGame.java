package com.vstream.Vidiserver;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.w3c.dom.Attr;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Spot What's Missing (Vistr game 2) - everything is kept here on memoryq.com: the carers' players, their settings,
 * every session and picture played, the progress reports and the picture library. The game itself runs on
 * vistr.memoryq.com/missing/ and talks to this API (cross-origin) with a game-only sign-in.
 *
 *   GET  /api/missing/pictures                     the pictures that are switched on (manifests)
 *   GET  /api/missing/picture?id=cat               one picture (SVG, cleaned)
 *   POST /api/missing/signin       code            a one-time code from memoryq.com/login -> a game-only sign-in
 *   POST /api/missing/signout
 *   GET  /api/missing/players                      the carer's players (Memoryq sign-in or game sign-in)
 *   POST /api/missing/players/save   (Memoryq sign-in only: the carers' area on memoryq.com/me)
 *   POST /api/missing/players/delete id            the player and everything about them
 *   POST /api/missing/session/start  player        -> session id, settings, level settings, pictures, recently hidden
 *   POST /api/missing/round          JSON          one picture played (idempotent: the round id is made by the game)
 *   POST /api/missing/session/end    session
 *   GET  /api/missing/progress?player=             the carer's progress page and printable report
 *   GET  /api/admin/missing                        super-admin: pictures (with use counts) and numbers
 *   POST /api/admin/missing/picture   JSON         add or change a picture (SVG + parts); a built-in one is kept as a copy
 *   POST /api/admin/missing/enable    id, on       switch a picture on or off
 *   POST /api/admin/missing/delete    id           delete an added picture / put a built-in one back as it was
 *   GET  /api/admin/missing/svg?id=                a picture for the admin's preview (also when switched off)
 * Children never sign in: the carer does (Memoryq account), and players are only a nickname and an avatar.
 */
public final class MissingGame {

    static final int MAX_PLAYERS = 40;
    static final String[] SIZES = {"large", "medium", "small"};
    static final String[] AVATARS = {"lion", "elephant", "giraffe", "turtle", "fish", "butterfly", "flower", "star", "car", "rocket", "sun", "heart"};

    /** Per level: how many parts are hidden (min, max), which sizes, tap tolerance (viewBox units of a 300-wide picture),
     * hint delays (s), pictures per session. */
    static final int[][] HIDE = {{1, 1}, {1, 2}, {2, 3}, {3, 4}, {4, 6}};
    static final String[][] LEVEL_SIZES = {{"large"}, {"large"}, {"large", "medium"}, {"medium", "small"}, {"small", "medium"}};
    static final int[] TOLERANCE = {30, 22, 14, 8, 4};
    static final int[][] HINTS = {{5, 10, 15}, {8, 15, 25}, {12, 25, 40}, {20, 40, 60}, {30, 60, 90}};
    static final int[] PICTURES = {3, 4, 5, 6, 8};

    static final class Pic {
        String id;
        String svg;
        String manifest; // JSON
        boolean builtin;
        boolean changed; // a built-in picture with the super-admin's changes
        boolean enabled = true;
        int version = 1;
        String category = "";
        String title = "";
        int parts;
    }

    static final class Player {
        String id;
        String owner; // lower-case Memoryq username
        String nick = "";
        String avatar = "star";
        String age = "child";
        String lang = "en";
        int level = 1;
        Map<String, String> set = new LinkedHashMap<String, String>();
        long created;
        long lastPlayed;
    }

    static final class Sess {
        String id;
        String player;
        String owner;
        long started;
        long ended;
        int level;
        int stars;
        int found;
        int noHint;
        int hinted;
        int misses;
        int passes;
        int pictures;
        long findMs;
    }

    static final class Round {
        String id;
        String session;
        String player;
        String picture;
        String hidden = "";
        long started;
        long ended;
        boolean passed;
        int misses;
        int stars;
        String found = ""; // part:kind:size:ms:hint:how;...
    }

    private final Path dir;
    private final SessionStore sessions;
    private final UserStore users;
    private final SignInApi signIn;
    private final LongSupplier clock;
    private final Map<String, Pic> pics = new LinkedHashMap<String, Pic>();
    private final Map<String, Player> players = new LinkedHashMap<String, Player>();
    private final Map<String, Sess> sess = new LinkedHashMap<String, Sess>();
    private final List<Round> rounds = new ArrayList<Round>();
    private final Set<String> roundIds = new HashSet<String>();
    private final Map<String, String[]> tokens = new HashMap<String, String[]>(); // fingerprint -> {owner, expires}
    private final Set<String> disabled = new HashSet<String>();
    private static final SecureRandom RANDOM = new SecureRandom();
    static volatile String extraOrigin = System.getProperty("vstream.missingOrigin"); // tests

    public MissingGame(Path dir, SessionStore sessions, UserStore users, SignInApi signIn, LongSupplier clock) {
        this.dir = dir;
        this.sessions = sessions;
        this.users = users;
        this.signIn = signIn;
        this.clock = clock;
    }

    // ================================================================ files

    private static String c(String s, int max) {
        String t = s == null ? "" : s.replaceAll("[\\t\\r\\n]+", " ").trim();
        return t.length() > max ? t.substring(0, max) : t;
    }

    private List<String[]> rows(String name, int min) {
        List<String[]> out = new ArrayList<String[]>();
        Path p = dir.resolve(name);
        if (!Files.isRegularFile(p)) {
            return out;
        }
        try {
            for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
                String[] f = line.split("\t", -1);
                if (f.length >= min && !line.startsWith("#")) {
                    out.add(f);
                }
            }
        } catch (IOException e) {
            System.out.println("[missing] could not read " + p + ": " + e.getMessage());
        }
        return out;
    }

    private void write(String name, String text) {
        try {
            Files.createDirectories(dir);
            Path p = dir.resolve(name);
            Path part = dir.resolve(name + ".part");
            Files.write(part, text.getBytes(StandardCharsets.UTF_8));
            Files.move(part, p, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            System.out.println("[missing] could not save " + name + ": " + e.getMessage());
        }
    }

    private void append(String name, String line) {
        try {
            Files.createDirectories(dir);
            Files.write(dir.resolve(name), (line + "\n").getBytes(StandardCharsets.UTF_8), java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException e) {
            System.out.println("[missing] could not save " + name + ": " + e.getMessage());
        }
    }

    private static long l(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private static int i(String s) {
        return (int) l(s);
    }

    public synchronized void init() {
        for (String[] b : MissingPictures.ALL) {
            Pic p = new Pic();
            p.id = b[0];
            p.svg = b[1];
            p.manifest = b[2];
            p.builtin = true;
            fill(p);
            pics.put(p.id, p);
        }
        Path up = dir.resolve("pictures");
        if (Files.isDirectory(up)) {
            try (java.util.stream.Stream<Path> s = Files.list(up)) {
                List<Path> list = new ArrayList<Path>();
                s.forEach(list::add);
                Collections.sort(list);
                for (Path f : list) {
                    String n = f.getFileName().toString();
                    if (!n.endsWith(".json")) {
                        continue;
                    }
                    String id = n.substring(0, n.length() - 5);
                    Path svg = up.resolve(id + ".svg");
                    if (!id.matches("[a-z0-9-]{2,40}") || !Files.isRegularFile(svg)) {
                        continue;
                    }
                    Pic p = new Pic();
                    p.id = id;
                    p.svg = new String(Files.readAllBytes(svg), StandardCharsets.UTF_8);
                    p.manifest = new String(Files.readAllBytes(f), StandardCharsets.UTF_8);
                    Pic old = pics.get(id);
                    p.builtin = old != null && old.builtin;
                    p.changed = p.builtin;
                    fill(p);
                    pics.put(id, p);
                }
            } catch (IOException e) {
                System.out.println("[missing] could not read the added pictures: " + e.getMessage());
            }
        }
        for (String[] f : rows("pictures-off.tsv", 1)) {
            disabled.add(f[0]);
        }
        for (Pic p : pics.values()) {
            p.enabled = !disabled.contains(p.id);
        }
        for (String[] f : rows("players.tsv", 11)) {
            Player p = new Player();
            p.id = f[0];
            p.owner = f[1];
            p.nick = f[2];
            p.avatar = f[3];
            p.age = f[4];
            p.lang = f[5];
            p.level = Math.max(1, Math.min(5, i(f[6])));
            for (String kv : f[7].split(",")) {
                int eq = kv.indexOf('=');
                if (eq > 0) {
                    p.set.put(kv.substring(0, eq), kv.substring(eq + 1));
                }
            }
            p.created = l(f[8]);
            p.lastPlayed = l(f[9]);
            players.put(p.id, p);
        }
        for (String[] f : rows("sessions.tsv", 15)) {
            Sess s = new Sess();
            s.id = f[0];
            s.player = f[1];
            s.owner = f[2];
            s.started = l(f[3]);
            s.ended = l(f[4]);
            s.level = i(f[5]);
            s.stars = i(f[6]);
            s.found = i(f[7]);
            s.noHint = i(f[8]);
            s.hinted = i(f[9]);
            s.misses = i(f[10]);
            s.passes = i(f[11]);
            s.pictures = i(f[12]);
            s.findMs = l(f[13]);
            if (players.containsKey(s.player)) {
                sess.put(s.id, s);
            }
        }
        for (String[] f : rows("rounds.tsv", 11)) {
            Round r = new Round();
            r.id = f[0];
            r.session = f[1];
            r.player = f[2];
            r.picture = f[3];
            r.hidden = f[4];
            r.started = l(f[5]);
            r.ended = l(f[6]);
            r.passed = "1".equals(f[7]);
            r.misses = i(f[8]);
            r.stars = i(f[9]);
            r.found = f[10];
            if (players.containsKey(r.player) && roundIds.add(r.id)) {
                rounds.add(r);
            }
        }
        long now = clock.getAsLong();
        for (String[] f : rows("tokens.tsv", 3)) {
            if (l(f[2]) > now) {
                tokens.put(f[0], new String[] {f[1], f[2]});
            }
        }
        System.out.println("[missing] Spot What's Missing: " + pics.size() + " pictures, " + players.size() + " players, " + sess.size() + " sessions");
    }

    /** Category, title and part count from the manifest. */
    private static void fill(Pic p) {
        Object m = MiniJson.parse(p.manifest);
        p.category = MiniJson.str(m, "category");
        p.title = MiniJson.str(m, "title", "en");
        p.version = (int) Math.max(1, MiniJson.num(m, "version"));
        Object parts = MiniJson.at(m, "parts");
        p.parts = parts instanceof List ? ((List<?>) parts).size() : 0;
    }

    private void savePlayers() {
        StringBuilder sb = new StringBuilder("# id\towner\tnickname\tavatar\tage\tlang\tlevel\tsettings\tcreated\tlastPlayed\t-\n");
        for (Player p : players.values()) {
            StringBuilder set = new StringBuilder();
            for (Map.Entry<String, String> e : p.set.entrySet()) {
                set.append(set.length() > 0 ? "," : "").append(e.getKey()).append('=').append(e.getValue());
            }
            sb.append(p.id).append('\t').append(p.owner).append('\t').append(c(p.nick, 24)).append('\t').append(p.avatar).append('\t').append(p.age)
                    .append('\t').append(p.lang).append('\t').append(p.level).append('\t').append(set).append('\t').append(p.created).append('\t')
                    .append(p.lastPlayed).append("\t-\n");
        }
        write("players.tsv", sb.toString());
    }

    private static String sessLine(Sess s) {
        return s.id + "\t" + s.player + "\t" + s.owner + "\t" + s.started + "\t" + s.ended + "\t" + s.level + "\t" + s.stars + "\t" + s.found + "\t"
                + s.noHint + "\t" + s.hinted + "\t" + s.misses + "\t" + s.passes + "\t" + s.pictures + "\t" + s.findMs + "\t-";
    }

    private void saveSessions() {
        StringBuilder sb = new StringBuilder();
        for (Sess s : sess.values()) {
            sb.append(sessLine(s)).append('\n');
        }
        write("sessions.tsv", sb.toString());
    }

    private static String roundLine(Round r) {
        return r.id + "\t" + r.session + "\t" + r.player + "\t" + r.picture + "\t" + r.hidden + "\t" + r.started + "\t" + r.ended + "\t" + (r.passed ? 1 : 0)
                + "\t" + r.misses + "\t" + r.stars + "\t" + r.found;
    }

    private void saveRounds() {
        StringBuilder sb = new StringBuilder();
        for (Round r : rounds) {
            sb.append(roundLine(r)).append('\n');
        }
        write("rounds.tsv", sb.toString());
    }

    private void saveTokens() {
        StringBuilder sb = new StringBuilder();
        long now = clock.getAsLong();
        for (Map.Entry<String, String[]> e : tokens.entrySet()) {
            if (l(e.getValue()[1]) > now) {
                sb.append(e.getKey()).append('\t').append(e.getValue()[0]).append('\t').append(e.getValue()[1]).append('\n');
            }
        }
        write("tokens.tsv", sb.toString());
    }

    private void saveDisabled() {
        StringBuilder sb = new StringBuilder();
        for (String d : disabled) {
            sb.append(d).append('\n');
        }
        write("pictures-off.tsv", sb.toString());
    }

    private static String newId(int bytes) {
        byte[] b = new byte[bytes];
        RANDOM.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    // ================================================================ JSON

    static String q(String s) {
        return VideoApi.jq(s == null ? "" : s);
    }

    private static String playerJson(Player p) {
        StringBuilder set = new StringBuilder("{");
        int n = 0;
        for (Map.Entry<String, String> e : p.set.entrySet()) {
            set.append(n++ > 0 ? "," : "").append(q(e.getKey())).append(':').append(q(e.getValue()));
        }
        set.append('}');
        return "{\"id\":" + q(p.id) + ",\"nickname\":" + q(p.nick) + ",\"avatar\":" + q(p.avatar) + ",\"age\":" + q(p.age) + ",\"lang\":" + q(p.lang)
                + ",\"level\":" + p.level + ",\"settings\":" + set + ",\"created\":" + p.created + ",\"lastPlayed\":" + p.lastPlayed + "}";
    }

    /** What the game needs to know about a level (with the carer's hint setting). */
    static String levelJson(int level, Map<String, String> set) {
        int k = Math.max(1, Math.min(5, level)) - 1;
        String hints = set.containsKey("hints") ? set.get("hints") : "on";
        StringBuilder h = new StringBuilder("[");
        for (int j = 0; j < 3; j++) {
            int s = HINTS[k][j];
            if (hints.equals("sooner")) {
                s = Math.max(3, s / 2);
            } else if (hints.equals("later")) {
                s = s * 2;
            }
            h.append(j > 0 ? "," : "").append(hints.equals("off") ? 0 : s);
        }
        StringBuilder sizes = new StringBuilder("[");
        for (int j = 0; j < LEVEL_SIZES[k].length; j++) {
            sizes.append(j > 0 ? "," : "").append(q(LEVEL_SIZES[k][j]));
        }
        int pictures = PICTURES[k];
        try {
            if (set.containsKey("pictures") && Integer.parseInt(set.get("pictures")) > 0) {
                pictures = Math.min(20, Integer.parseInt(set.get("pictures")));
            }
        } catch (NumberFormatException ignored) {
            // the level's number
        }
        return "{\"level\":" + (k + 1) + ",\"hideMin\":" + HIDE[k][0] + ",\"hideMax\":" + HIDE[k][1] + ",\"sizes\":" + sizes + "],\"tolerance\":" + TOLERANCE[k]
                + ",\"hints\":" + h + "],\"pictures\":" + pictures + ",\"timer\":" + (k >= 3 && "1".equals(set.get("timer"))) + "}";
    }

    private static String picJson(Pic p) {
        String m = p.manifest.trim();
        return m.substring(0, m.length() - 1) + ",\"enabled\":" + p.enabled + ",\"changed\":" + p.changed + "}";
    }

    // ================================================================ who is asking

    private static String bearer(HttpExchange ex) {
        String a = ex.getRequestHeaders().getFirst("Authorization");
        return a != null && a.startsWith("Bearer ") ? a.substring(7).trim() : "";
    }

    /** The carer: {owner (lower case), "mq" | "game"}; null when not signed in. */
    private String[] carer(HttpExchange ex) {
        String t = bearer(ex);
        if (t.isEmpty()) {
            return null;
        }
        SessionStore.Session s = sessions.get(t);
        if (s != null) {
            UserStore.Info i = users.info(s.username);
            return i == null || i.disabled ? null : new String[] {i.username.toLowerCase(Locale.ROOT), "mq"};
        }
        synchronized (this) {
            String[] g = tokens.get(SessionStore.fingerprint(t));
            if (g == null || l(g[1]) < clock.getAsLong()) {
                return null;
            }
            UserStore.Info i = users.info(g[0]);
            return i == null || i.disabled ? null : new String[] {g[0], "game"};
        }
    }

    static final class Bad extends Exception {
        final int status;

        Bad(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    private static String p(String form, String k) {
        String v = MediaAuthHttpServer.queryParam(form, k);
        return v == null ? "" : v.trim();
    }

    static boolean allowedOrigin(String o) {
        return o != null && (o.equals("https://vistr.memoryq.com") || o.equals("https://memoryq.com") || o.equals("https://www.memoryq.com")
                || o.startsWith("http://localhost") || o.startsWith("https://localhost") || o.equals("capacitor://localhost")
                || extraOrigin != null && o.equals(extraOrigin));
    }

    private static void send(HttpExchange ex, int status, String type, byte[] b) throws IOException {
        String origin = ex.getRequestHeaders().getFirst("Origin");
        if (allowedOrigin(origin)) {
            ex.getResponseHeaders().set("Access-Control-Allow-Origin", origin);
            ex.getResponseHeaders().set("Vary", "Origin");
            ex.getResponseHeaders().set("Access-Control-Allow-Headers", "Authorization, Content-Type");
            ex.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
            ex.getResponseHeaders().set("Access-Control-Max-Age", "600");
        }
        ex.getResponseHeaders().set("Content-Type", type);
        ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        if (!ex.getResponseHeaders().containsKey("Cache-Control")) {
            ex.getResponseHeaders().set("Cache-Control", "no-store");
        }
        if (status == 204) {
            ex.sendResponseHeaders(204, -1);
        } else {
            ex.sendResponseHeaders(status, b.length);
            ex.getResponseBody().write(b);
        }
        ex.close();
    }

    public void register(HttpServer server) {
        server.createContext("/api/missing/", this::handle);
        server.createContext("/api/admin/missing", this::handleAdmin);
    }

    // ================================================================ the game and the carers' area

    private void handle(HttpExchange ex) throws IOException {
        int status = 200;
        String out;
        try {
            if ("OPTIONS".equalsIgnoreCase(ex.getRequestMethod())) {
                send(ex, 204, "text/plain", new byte[0]);
                return;
            }
            String op = ex.getRequestURI().getPath().substring("/api/missing/".length());
            String query = ex.getRequestURI().getRawQuery();
            boolean post = "POST".equalsIgnoreCase(ex.getRequestMethod());
            String body = post ? SuperAgent.readBody(ex, 65536) : "";
            switch (op) {
                case "pictures":
                    out = picturesJson(false);
                    break;
                case "picture": {
                    Pic pc;
                    synchronized (this) {
                        pc = pics.get(p(query == null ? "" : query, "id"));
                    }
                    if (pc == null || !pc.enabled) {
                        throw new Bad(404, "no such picture");
                    }
                    ex.getResponseHeaders().set("Cache-Control", "public, max-age=300");
                    send(ex, 200, "image/svg+xml; charset=utf-8", pc.svg.getBytes(StandardCharsets.UTF_8));
                    return;
                }
                case "signin": {
                    if (!post) {
                        throw new Bad(405, "use POST");
                    }
                    String[] t = signIn == null ? null : signIn.take(p(body, "code"));
                    if (t == null) {
                        throw new Bad(401, "that sign-in has expired - sign in again");
                    }
                    String token = "MG." + newId(24);
                    long ttl = Math.max(3600000L, l(t[1]));
                    synchronized (this) {
                        tokens.put(SessionStore.fingerprint(token), new String[] {t[0].toLowerCase(Locale.ROOT), String.valueOf(clock.getAsLong() + ttl)});
                        saveTokens();
                    }
                    out = "{\"token\":" + q(token) + ",\"username\":" + q(t[0]) + ",\"days\":" + Math.max(1, ttl / SessionStore.DAY_MS) + "}";
                    break;
                }
                default:
                    out = signedIn(ex, op, post, query == null ? "" : query, body);
            }
        } catch (Bad e) {
            status = e.status;
            out = "{\"error\":" + q(e.getMessage()) + "}";
        } catch (RuntimeException e) {
            status = 400;
            out = "{\"error\":" + q("that did not look right: " + e.getMessage()) + "}";
        }
        send(ex, status, "application/json; charset=utf-8", out.getBytes(StandardCharsets.UTF_8));
    }

    private String signedIn(HttpExchange ex, String op, boolean post, String query, String body) throws Bad {
        String[] who = carer(ex);
        if (who == null) {
            throw new Bad(401, "sign in with your Memoryq account");
        }
        String owner = who[0];
        boolean mq = who[1].equals("mq");
        if (!op.equals("signout") && !Moderators.gameOpenFor(users.info(owner), "missing")) {
            throw new Bad(403, "Spot What's Missing is not open yet - ask the Memoryq admin.");
        }
        if (!op.equals("players") && !op.equals("progress") && !post) {
            throw new Bad(405, "use POST");
        }
        synchronized (this) {
            switch (op) {
                case "signout":
                    tokens.remove(SessionStore.fingerprint(bearer(ex)));
                    saveTokens();
                    return "{\"ok\":true}";
                case "players": {
                    StringBuilder sb = new StringBuilder("{\"players\":[");
                    int n = 0;
                    for (Player p : players.values()) {
                        if (p.owner.equals(owner)) {
                            sb.append(n++ > 0 ? "," : "").append(playerJson(p));
                        }
                    }
                    String gid = GameIds.current() == null ? "" : GameIds.current().idOf(owner, "missing");
                    return sb.append("],\"carer\":").append(q(owner)).append(",\"gameId\":").append(q(gid)).append(",\"avatars\":").append(arr(AVATARS)).append('}').toString();
                }
                case "players/save":
                    if (!mq) {
                        throw new Bad(403, "players are changed in the carers' area on memoryq.com");
                    }
                    return "{\"player\":" + playerJson(savePlayer(owner, body)) + "}";
                case "players/delete": {
                    if (!mq) {
                        throw new Bad(403, "players are changed in the carers' area on memoryq.com");
                    }
                    Player pl = mine(owner, p(body, "id"));
                    players.remove(pl.id);
                    sess.values().removeIf(s -> s.player.equals(pl.id));
                    rounds.removeIf(r -> r.player.equals(pl.id));
                    savePlayers();
                    saveSessions();
                    saveRounds();
                    return "{\"deleted\":" + q(pl.id) + "}";
                }
                case "session/start":
                    return start(mine(owner, p(body, "player")));
                case "round":
                    return round(owner, body);
                case "session/end": {
                    Sess s = sess.get(p(body, "session"));
                    if (s == null || !s.owner.equals(owner)) {
                        throw new Bad(404, "no such session");
                    }
                    s.ended = Math.max(s.ended, clock.getAsLong());
                    saveSessions();
                    return sessionJson(s);
                }
                case "progress":
                    return progress(mine(owner, p(query, "player")));
                default:
                    throw new Bad(404, "not found");
            }
        }
    }

    private static String arr(String[] a) {
        StringBuilder sb = new StringBuilder("[");
        for (int j = 0; j < a.length; j++) {
            sb.append(j > 0 ? "," : "").append(q(a[j]));
        }
        return sb.append(']').toString();
    }

    private Player mine(String owner, String id) throws Bad {
        Player p = players.get(id);
        if (p == null || !p.owner.equals(owner)) {
            throw new Bad(404, "no such player");
        }
        return p;
    }

    private static String pick(String v, String[] allowed, String dflt) {
        for (String a : allowed) {
            if (a.equals(v)) {
                return v;
            }
        }
        return dflt;
    }

    private Player savePlayer(String owner, String body) throws Bad {
        String id = p(body, "id");
        Player pl;
        if (id.isEmpty()) {
            int n = 0;
            for (Player x : players.values()) {
                if (x.owner.equals(owner)) {
                    n++;
                }
            }
            if (n >= MAX_PLAYERS) {
                throw new Bad(409, "you have " + MAX_PLAYERS + " players already");
            }
            pl = new Player();
            pl.id = newId(9);
            pl.owner = owner;
            pl.created = clock.getAsLong();
            pl.set.put("minutes", "5");
            pl.set.put("voice", "1");
            pl.set.put("hints", "on");
        } else {
            pl = mine(owner, id);
        }
        String nick = c(p(body, "nickname"), 24).replaceAll("[<>\"&]", "");
        if (nick.isEmpty() && pl.nick.isEmpty()) {
            throw new Bad(400, "give the player a first name or nickname");
        }
        if (!nick.isEmpty()) {
            pl.nick = nick;
        }
        if (!p(body, "avatar").isEmpty()) {
            pl.avatar = pick(p(body, "avatar"), AVATARS, pl.avatar);
        }
        if (!p(body, "age").isEmpty()) {
            pl.age = pick(p(body, "age"), new String[] {"child", "teen", "adult"}, pl.age);
        }
        if (!p(body, "lang").isEmpty()) {
            pl.lang = pick(p(body, "lang"), new String[] {"en", "sw"}, pl.lang);
        }
        if (!p(body, "level").isEmpty()) {
            pl.level = Math.max(1, Math.min(5, i(p(body, "level"))));
        }
        if (!p(body, "minutes").isEmpty()) {
            pl.set.put("minutes", String.valueOf(Math.max(0, Math.min(20, i(p(body, "minutes"))))));
        }
        if (!p(body, "pictures").isEmpty()) {
            pl.set.put("pictures", String.valueOf(Math.max(0, Math.min(20, i(p(body, "pictures"))))));
        }
        for (String b : new String[] {"voice", "timer", "typing", "scan"}) {
            if (!p(body, b).isEmpty()) {
                pl.set.put(b, "1".equals(p(body, b)) ? "1" : "0");
            }
        }
        if (!p(body, "hints").isEmpty()) {
            pl.set.put("hints", pick(p(body, "hints"), new String[] {"on", "sooner", "later", "off"}, "on"));
        }
        if (!p(body, "scanSpeed").isEmpty()) {
            pl.set.put("scanSpeed", String.valueOf(Math.max(800, Math.min(5000, i(p(body, "scanSpeed"))))));
        }
        String cats = p(body, "categories");
        if (!p(body, "categoriesSet").isEmpty() || !cats.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (String cat : cats.split("[\\s,;]+")) {
                if (cat.matches("[a-z]{2,20}") && sb.indexOf(cat) < 0) {
                    sb.append(sb.length() > 0 ? ";" : "").append(cat);
                }
            }
            pl.set.put("categories", sb.toString());
        }
        players.put(pl.id, pl);
        savePlayers();
        return pl;
    }

    private String picturesJson(boolean all) {
        StringBuilder sb = new StringBuilder("{\"pictures\":[");
        int n = 0;
        synchronized (this) {
            for (Pic pc : pics.values()) {
                if (all || pc.enabled) {
                    sb.append(n++ > 0 ? "," : "").append(all ? picJson(pc) : pc.manifest.trim());
                }
            }
        }
        return sb.append("]}").toString();
    }

    private String start(Player pl) {
        Sess s = new Sess();
        s.id = newId(9);
        s.player = pl.id;
        s.owner = pl.owner;
        s.started = clock.getAsLong();
        s.ended = s.started;
        s.level = pl.level;
        sess.put(s.id, s);
        pl.lastPlayed = s.started;
        saveSessions();
        savePlayers();
        // the parts this player had hidden in each picture lately (the game avoids hiding the same ones again)
        Map<String, List<String>> recent = new LinkedHashMap<String, List<String>>();
        for (int j = rounds.size() - 1; j >= 0 && recent.size() < 60; j--) {
            Round r = rounds.get(j);
            if (r.player.equals(pl.id)) {
                List<String> l = recent.get(r.picture);
                if (l == null) {
                    l = new ArrayList<String>();
                    recent.put(r.picture, l);
                }
                if (l.size() < 2) {
                    l.add(r.hidden);
                }
            }
        }
        StringBuilder rj = new StringBuilder("{");
        int n = 0;
        for (Map.Entry<String, List<String>> e : recent.entrySet()) {
            rj.append(n++ > 0 ? "," : "").append(q(e.getKey())).append(":[");
            for (int j = 0; j < e.getValue().size(); j++) {
                rj.append(j > 0 ? "," : "").append(q(e.getValue().get(j)));
            }
            rj.append(']');
        }
        rj.append('}');
        String cats = pl.set.containsKey("categories") ? pl.set.get("categories") : "";
        List<String> catList = cats.isEmpty() ? Collections.<String>emptyList() : Arrays.asList(cats.split(";"));
        StringBuilder pj = new StringBuilder("[");
        n = 0;
        for (Pic pc : pics.values()) {
            if (!pc.enabled || !catList.isEmpty() && !catList.contains(pc.category)) {
                continue;
            }
            Object ages = MiniJson.at(MiniJson.parse(pc.manifest), "ages");
            if (ages instanceof List && !((List<?>) ages).isEmpty() && !((List<?>) ages).contains(pl.age)) {
                continue;
            }
            pj.append(n++ > 0 ? "," : "").append(pc.manifest.trim());
        }
        if (n == 0) { // the chosen kinds have no pictures (switched off): all pictures rather than none
            for (Pic pc : pics.values()) {
                if (pc.enabled) {
                    pj.append(n++ > 0 ? "," : "").append(pc.manifest.trim());
                }
            }
        }
        pj.append(']');
        return "{\"session\":" + q(s.id) + ",\"player\":" + playerJson(pl) + ",\"params\":" + levelJson(pl.level, pl.set) + ",\"pictures\":" + pj
                + ",\"recent\":" + rj + "}";
    }

    private String round(String owner, String body) throws Bad {
        Object o;
        try {
            o = MiniJson.parse(body);
        } catch (RuntimeException e) {
            throw new Bad(400, "send the round as JSON");
        }
        Sess s = sess.get(MiniJson.str(o, "session"));
        if (s == null || !s.owner.equals(owner)) {
            throw new Bad(404, "no such session");
        }
        String id = MiniJson.str(o, "id");
        if (!id.matches("[A-Za-z0-9_-]{6,40}")) {
            throw new Bad(400, "a round needs an id");
        }
        if (roundIds.contains(id)) {
            return sessionJson(s); // sent again after a bad connection: counted once
        }
        Pic pc = pics.get(MiniJson.str(o, "picture"));
        if (pc == null) {
            throw new Bad(404, "no such picture");
        }
        Map<String, String[]> known = new HashMap<String, String[]>(); // part id -> {kind, size}
        Object parts = MiniJson.at(MiniJson.parse(pc.manifest), "parts");
        if (parts instanceof List) {
            for (Object x : (List<?>) parts) {
                known.put(MiniJson.str(x, "id"), new String[] {MiniJson.str(x, "kind"), MiniJson.str(x, "size")});
            }
        }
        Round r = new Round();
        r.id = id;
        r.session = s.id;
        r.player = s.player;
        r.picture = pc.id;
        StringBuilder hidden = new StringBuilder();
        Object h = MiniJson.at(o, "hidden");
        Set<String> hiddenSet = new HashSet<String>();
        if (h instanceof List) {
            for (Object x : (List<?>) h) {
                String pid = String.valueOf(x);
                if (known.containsKey(pid) && hiddenSet.add(pid) && hiddenSet.size() <= 8) {
                    hidden.append(hidden.length() > 0 ? "," : "").append(pid);
                }
            }
        }
        if (hiddenSet.isEmpty()) {
            throw new Bad(400, "no hidden parts");
        }
        r.hidden = hidden.toString();
        long now = clock.getAsLong();
        r.started = Math.min(now, Math.max(s.started - 60000L, MiniJson.num(o, "started")));
        r.ended = now;
        r.passed = Boolean.TRUE.equals(MiniJson.at(o, "passed"));
        r.misses = (int) Math.max(0, Math.min(999, MiniJson.num(o, "misses")));
        StringBuilder f = new StringBuilder();
        Set<String> seen = new HashSet<String>();
        int found = 0, noHint = 0, hinted = 0;
        long ms = 0;
        Object fl = MiniJson.at(o, "found");
        if (fl instanceof List) {
            for (Object x : (List<?>) fl) {
                String pid = MiniJson.str(x, "part");
                if (!hiddenSet.contains(pid) || !seen.add(pid)) {
                    continue;
                }
                long t = Math.max(0, Math.min(3600000L, MiniJson.num(x, "ms")));
                int hint = (int) Math.max(0, Math.min(3, MiniJson.num(x, "hint")));
                String how = pick(MiniJson.str(x, "how"), new String[] {"point", "key", "switch", "type"}, "point");
                String[] ks = known.get(pid);
                f.append(f.length() > 0 ? ";" : "").append(pid).append(':').append(ks[0]).append(':').append(ks[1]).append(':').append(t).append(':')
                        .append(hint).append(':').append(how);
                found++;
                ms += t;
                if (hint == 0) {
                    noHint++;
                } else {
                    hinted++;
                }
            }
        }
        r.found = f.toString();
        r.stars = found + (!r.passed && hinted == 0 && found == hiddenSet.size() ? 1 : 0);
        rounds.add(r);
        roundIds.add(r.id);
        append("rounds.tsv", roundLine(r));
        s.pictures++;
        s.found += found;
        s.noHint += noHint;
        s.hinted += hinted;
        s.misses += r.misses;
        s.passes += r.passed ? 1 : 0;
        s.findMs += ms;
        s.stars += r.stars;
        s.ended = now;
        saveSessions();
        return sessionJson(s);
    }

    private static String sessionJson(Sess s) {
        return "{\"session\":" + q(s.id) + ",\"stars\":" + s.stars + ",\"found\":" + s.found + ",\"noHint\":" + s.noHint + ",\"pictures\":" + s.pictures
                + ",\"passes\":" + s.passes + "}";
    }

    // ================================================================ progress

    /** Up after 3 sessions in a row at this level with 80%+ found without a hint and at most 1 pass; down after 2 with
     * 60%+ found after a hint or 2+ passes. Only suggested to the carer, never changed by itself. */
    static String suggestion(List<Sess> mine, int level) {
        List<Sess> at = new ArrayList<Sess>();
        for (int j = mine.size() - 1; j >= 0 && at.size() < 3; j--) {
            Sess s = mine.get(j);
            if (s.level != level) {
                break;
            }
            if (s.pictures > 0) {
                at.add(s);
            }
        }
        if (at.size() >= 2) {
            boolean down = true;
            for (int j = 0; j < 2; j++) {
                Sess s = at.get(j);
                int all = s.found;
                if (!(all > 0 && s.hinted * 100 >= all * 60 || s.passes >= 2)) {
                    down = false;
                }
            }
            if (down && level > 1) {
                return "down";
            }
        }
        if (at.size() >= 3) {
            boolean up = true;
            for (Sess s : at) {
                if (!(s.found > 0 && s.noHint * 100 >= s.found * 80 && s.passes <= 1)) {
                    up = false;
                }
            }
            if (up && level < 5) {
                return "up";
            }
        }
        return "";
    }

    private String progress(Player pl) {
        List<Sess> mine = new ArrayList<Sess>();
        for (Sess s : sess.values()) {
            if (s.player.equals(pl.id) && s.pictures > 0) {
                mine.add(s);
            }
        }
        Collections.sort(mine, (a, b) -> Long.compare(a.started, b.started));
        long minutes = 0;
        int pictures = 0, found = 0, noHint = 0, hinted = 0, passes = 0, stars = 0;
        StringBuilder sj = new StringBuilder("[");
        int n = 0;
        StringBuilder lv = new StringBuilder("[");
        int lastLevel = -1, ln = 0;
        for (Sess s : mine) {
            minutes += Math.max(0, s.ended - s.started);
            pictures += s.pictures;
            found += s.found;
            noHint += s.noHint;
            hinted += s.hinted;
            passes += s.passes;
            stars += s.stars;
            if (s.level != lastLevel) {
                lv.append(ln++ > 0 ? "," : "").append("{\"at\":").append(s.started).append(",\"level\":").append(s.level).append('}');
                lastLevel = s.level;
            }
        }
        for (int j = Math.max(0, mine.size() - 60); j < mine.size(); j++) {
            Sess s = mine.get(j);
            sj.append(n++ > 0 ? "," : "").append("{\"at\":").append(s.started).append(",\"ms\":").append(Math.max(0, s.ended - s.started)).append(",\"level\":")
                    .append(s.level).append(",\"pictures\":").append(s.pictures).append(",\"found\":").append(s.found).append(",\"noHint\":").append(s.noHint)
                    .append(",\"hinted\":").append(s.hinted).append(",\"passes\":").append(s.passes).append(",\"misses\":").append(s.misses)
                    .append(",\"meanMs\":").append(s.found > 0 ? s.findMs / s.found : 0).append(",\"stars\":").append(s.stars).append('}');
        }
        sj.append(']');
        lv.append(']');
        // by kind of part and by size
        Map<String, long[]> kinds = new LinkedHashMap<String, long[]>(); // found, noHint, ms
        Map<String, long[]> sizes = new LinkedHashMap<String, long[]>();
        Map<String, String> kindName = new HashMap<String, String>();
        for (String z : SIZES) {
            sizes.put(z, new long[3]);
        }
        for (Round r : rounds) {
            if (!r.player.equals(pl.id) || r.found.isEmpty()) {
                continue;
            }
            for (String e : r.found.split(";")) {
                String[] f = e.split(":");
                if (f.length < 6) {
                    continue;
                }
                long[] k = kinds.get(f[1]);
                if (k == null) {
                    k = new long[3];
                    kinds.put(f[1], k);
                }
                long[] z = sizes.get(f[2]);
                for (long[] a : z == null ? new long[][] {k} : new long[][] {k, z}) {
                    a[0]++;
                    a[1] += "0".equals(f[4]) ? 1 : 0;
                    a[2] += l(f[3]);
                }
                if (!kindName.containsKey(f[1])) {
                    kindName.put(f[1], partName(r.picture, f[0], pl.lang));
                }
            }
        }
        List<Map.Entry<String, long[]>> kl = new ArrayList<Map.Entry<String, long[]>>(kinds.entrySet());
        Collections.sort(kl, (a, b) -> Double.compare(rate(a.getValue()), rate(b.getValue())));
        StringBuilder kj = new StringBuilder("[");
        n = 0;
        for (Map.Entry<String, long[]> e : kl) {
            long[] v = e.getValue();
            kj.append(n++ > 0 ? "," : "").append("{\"kind\":").append(q(e.getKey())).append(",\"name\":").append(q(kindName.get(e.getKey())))
                    .append(",\"found\":").append(v[0]).append(",\"noHint\":").append(v[1]).append(",\"meanMs\":").append(v[0] > 0 ? v[2] / v[0] : 0).append('}');
        }
        kj.append(']');
        StringBuilder zj = new StringBuilder("{");
        n = 0;
        for (Map.Entry<String, long[]> e : sizes.entrySet()) {
            long[] v = e.getValue();
            zj.append(n++ > 0 ? "," : "").append(q(e.getKey())).append(":{\"found\":").append(v[0]).append(",\"noHint\":").append(v[1])
                    .append(",\"meanMs\":").append(v[0] > 0 ? v[2] / v[0] : 0).append('}');
        }
        zj.append('}');
        return "{\"player\":" + playerJson(pl) + ",\"totals\":{\"sessions\":" + mine.size() + ",\"ms\":" + minutes + ",\"pictures\":" + pictures + ",\"found\":"
                + found + ",\"noHint\":" + noHint + ",\"hinted\":" + hinted + ",\"passes\":" + passes + ",\"stars\":" + stars + "},\"sessions\":" + sj
                + ",\"kinds\":" + kj + ",\"sizes\":" + zj + ",\"levels\":" + lv + ",\"suggestion\":" + q(suggestion(mine, pl.level)) + "}";
    }

    private static double rate(long[] v) {
        return v[0] == 0 ? 1 : (double) v[1] / v[0];
    }

    private String partName(String picture, String part, String lang) {
        Pic pc = pics.get(picture);
        if (pc == null) {
            return part;
        }
        Object parts = MiniJson.at(MiniJson.parse(pc.manifest), "parts");
        if (parts instanceof List) {
            for (Object x : (List<?>) parts) {
                if (part.equals(MiniJson.str(x, "id"))) {
                    String nm = MiniJson.str(x, "name", lang);
                    return nm.isEmpty() ? MiniJson.str(x, "name", "en") : nm;
                }
            }
        }
        return part;
    }

    // ================================================================ admin

    private void handleAdmin(HttpExchange ex) throws IOException {
        int status = 200;
        String out;
        try {
            if (Moderators.allowed(ex, sessions, users, "games.missing") == null) {   // the super-admin, or a moderator with this right
                throw new Bad(403, "super-admin only");
            }
            String op = ex.getRequestURI().getPath().substring("/api/admin/missing".length()).replaceFirst("^/", "");
            boolean post = "POST".equalsIgnoreCase(ex.getRequestMethod());
            String body = post ? SuperAgent.readBody(ex, 600000) : "";
            if (op.equals("svg")) { // the super-admin's preview, also of pictures that are switched off
                Pic pc;
                synchronized (this) {
                    pc = pics.get(p(ex.getRequestURI().getRawQuery() == null ? "" : ex.getRequestURI().getRawQuery(), "id"));
                }
                if (pc == null) {
                    throw new Bad(404, "no such picture");
                }
                send(ex, 200, "image/svg+xml; charset=utf-8", pc.svg.getBytes(StandardCharsets.UTF_8));
                return;
            }
            if (!op.isEmpty() && !post) {
                throw new Bad(405, "use POST");
            }
            synchronized (this) {
                switch (op) {
                    case "":
                        out = adminJson();
                        break;
                    case "picture":
                        out = "{\"picture\":" + picJson(savePicture(body)) + "}";
                        break;
                    case "enable": {
                        Pic pc = pics.get(p(body, "id"));
                        if (pc == null) {
                            throw new Bad(404, "no such picture");
                        }
                        pc.enabled = "1".equals(p(body, "on"));
                        if (pc.enabled) {
                            disabled.remove(pc.id);
                        } else {
                            disabled.add(pc.id);
                        }
                        saveDisabled();
                        out = "{\"picture\":" + picJson(pc) + "}";
                        break;
                    }
                    case "delete": {
                        Pic pc = pics.get(p(body, "id"));
                        if (pc == null || pc.builtin && !pc.changed) {
                            throw new Bad(404, pc == null ? "no such picture" : "a built-in picture can only be switched off");
                        }
                        try {
                            Files.deleteIfExists(dir.resolve("pictures").resolve(pc.id + ".svg"));
                            Files.deleteIfExists(dir.resolve("pictures").resolve(pc.id + ".json"));
                        } catch (IOException e) {
                            throw new Bad(500, "could not delete: " + e.getMessage());
                        }
                        pics.remove(pc.id);
                        for (String[] b : MissingPictures.ALL) {
                            if (b[0].equals(pc.id)) { // the built-in one comes back as it was
                                Pic orig = new Pic();
                                orig.id = b[0];
                                orig.svg = b[1];
                                orig.manifest = b[2];
                                orig.builtin = true;
                                orig.enabled = pc.enabled;
                                fill(orig);
                                pics.put(orig.id, orig);
                            }
                        }
                        out = "{\"deleted\":" + q(pc.id) + "}";
                        break;
                    }
                    default:
                        throw new Bad(404, "not found");
                }
            }
        } catch (Bad e) {
            status = e.status;
            out = "{\"error\":" + q(e.getMessage()) + "}";
        } catch (RuntimeException e) {
            status = 400;
            out = "{\"error\":" + q("that did not look right: " + e.getMessage()) + "}";
        }
        send(ex, status, "application/json; charset=utf-8", out.getBytes(StandardCharsets.UTF_8));
    }

    private String adminJson() {
        Map<String, Integer> uses = new HashMap<String, Integer>();
        long week = clock.getAsLong() - 7 * SessionStore.DAY_MS;
        int r7 = 0, found = 0;
        for (Round r : rounds) {
            uses.put(r.picture, (uses.containsKey(r.picture) ? uses.get(r.picture) : 0) + 1);
            if (r.ended >= week) {
                r7++;
            }
            found += r.found.isEmpty() ? 0 : r.found.split(";").length;
        }
        Set<String> carers = new HashSet<String>();
        for (Player p : players.values()) {
            carers.add(p.owner);
        }
        int s7 = 0;
        for (Sess s : sess.values()) {
            if (s.started >= week && s.pictures > 0) {
                s7++;
            }
        }
        StringBuilder sb = new StringBuilder("{\"pictures\":[");
        int n = 0;
        for (Pic pc : pics.values()) {
            String j = picJson(pc);
            int plays = uses.containsKey(pc.id) ? uses.get(pc.id).intValue() : 0;
            sb.append(n++ > 0 ? "," : "").append(j, 0, j.length() - 1).append(",\"plays\":").append(plays).append('}');
        }
        return sb.append("],\"numbers\":{\"carers\":").append(carers.size()).append(",\"players\":").append(players.size()).append(",\"sessions\":")
                .append(sess.size()).append(",\"sessions7\":").append(s7).append(",\"pictures\":").append(rounds.size()).append(",\"pictures7\":").append(r7)
                .append(",\"found\":").append(found).append("}}").toString();
    }

    // ---- adding or changing a picture ----

    private static final Set<String> ELEMENTS = new HashSet<String>(Arrays.asList("svg", "g", "path", "circle", "ellipse", "rect", "line", "polyline",
            "polygon", "text", "tspan", "defs", "linearGradient", "radialGradient", "stop", "title", "desc"));
    private static final Set<String> ATTRS = new HashSet<String>(Arrays.asList("id", "d", "cx", "cy", "r", "rx", "ry", "x", "y", "x1", "y1", "x2", "y2", "dx",
            "dy", "width", "height", "points", "fill", "stroke", "stroke-width", "stroke-linecap", "stroke-linejoin", "stroke-dasharray", "stroke-miterlimit",
            "opacity", "fill-opacity", "stroke-opacity", "fill-rule", "clip-rule", "transform", "viewBox", "font-family", "font-size", "font-weight",
            "font-style", "text-anchor", "dominant-baseline", "offset", "stop-color", "stop-opacity", "gradientUnits", "gradientTransform", "fx", "fy",
            "preserveAspectRatio", "xmlns", "version"));

    /** The SVG with only drawing elements and attributes: no scripts, no links, no outside files, no styles. */
    static String cleanSvg(String svg) throws Bad {
        Document doc;
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setExpandEntityReferences(false);
            f.setXIncludeAware(false);
            DocumentBuilder b = f.newDocumentBuilder();
            b.setErrorHandler(null);
            doc = b.parse(new ByteArrayInputStream(svg.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new Bad(400, "that is not an SVG file we can read (" + e.getMessage() + ")");
        }
        Element root = doc.getDocumentElement();
        if (!"svg".equals(root.getLocalName())) {
            throw new Bad(400, "the file is not an SVG picture");
        }
        clean(root);
        String vb = root.getAttribute("viewBox");
        if (!vb.matches("\\s*-?[0-9.]+[\\s,]+-?[0-9.]+[\\s,]+[0-9.]+[\\s,]+[0-9.]+\\s*")) {
            String w = root.getAttribute("width").replaceAll("[^0-9.]", ""), h = root.getAttribute("height").replaceAll("[^0-9.]", "");
            if (w.isEmpty() || h.isEmpty()) {
                throw new Bad(400, "the SVG needs a viewBox (or a width and height)");
            }
            root.setAttribute("viewBox", "0 0 " + w + " " + h);
        }
        root.removeAttribute("width");
        root.removeAttribute("height");
        try {
            Transformer t = TransformerFactory.newInstance().newTransformer();
            t.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
            StringWriter w = new StringWriter();
            t.transform(new DOMSource(doc), new StreamResult(w));
            return w.toString();
        } catch (Exception e) {
            throw new Bad(500, "could not write the picture: " + e.getMessage());
        }
    }

    private static boolean safeValue(String v) {
        String s = v.toLowerCase(Locale.ROOT).replaceAll("\\s", "");
        if (s.contains("javascript:") || s.contains("data:") || s.contains("expression(") || s.contains("@import")) {
            return false;
        }
        int u = s.indexOf("url(");
        while (u >= 0) {
            if (!s.startsWith("url(#", u)) {
                return false;
            }
            u = s.indexOf("url(", u + 4);
        }
        return true;
    }

    private static void clean(Element el) {
        NamedNodeMap at = el.getAttributes();
        List<Attr> drop = new ArrayList<Attr>();
        for (int j = 0; j < at.getLength(); j++) {
            Attr a = (Attr) at.item(j);
            String name = a.getName();
            boolean data = name.matches("data-[a-z0-9-]{1,30}");
            boolean ns = name.startsWith("xmlns");
            if (ns && !name.equals("xmlns")) {
                drop.add(a);
            } else if (!(ATTRS.contains(name) || data) || !safeValue(a.getValue()) || name.equals("xmlns") && !a.getValue().equals("http://www.w3.org/2000/svg")) {
                drop.add(a);
            }
        }
        for (Attr a : drop) {
            el.removeAttributeNode(a);
        }
        NodeList kids = el.getChildNodes();
        List<Node> remove = new ArrayList<Node>();
        for (int j = 0; j < kids.getLength(); j++) {
            Node k = kids.item(j);
            if (k.getNodeType() == Node.ELEMENT_NODE) {
                String n = k.getLocalName() == null ? k.getNodeName() : k.getLocalName();
                if (!ELEMENTS.contains(n) || !"http://www.w3.org/2000/svg".equals(k.getNamespaceURI())) {
                    remove.add(k);
                } else {
                    clean((Element) k);
                }
            } else if (k.getNodeType() == Node.TEXT_NODE) {
                String n = el.getLocalName();
                if (!("text".equals(n) || "tspan".equals(n) || "title".equals(n) || "desc".equals(n)) && !k.getNodeValue().trim().isEmpty()) {
                    remove.add(k);
                }
            } else {
                remove.add(k); // comments, processing instructions, CDATA
            }
        }
        for (Node k : remove) {
            el.removeChild(k);
        }
    }

    private static Element byId(Element el, String id) {
        if (id.equals(el.getAttribute("id"))) {
            return el;
        }
        NodeList kids = el.getChildNodes();
        for (int j = 0; j < kids.getLength(); j++) {
            if (kids.item(j).getNodeType() == Node.ELEMENT_NODE) {
                Element f = byId((Element) kids.item(j), id);
                if (f != null) {
                    return f;
                }
            }
        }
        return null;
    }

    private static String clip(String s, int max) {
        String t = s == null ? "" : s.replaceAll("[\\p{Cntrl}<>\"]", " ").replaceAll("\\s+", " ").trim();
        return t.length() > max ? t.substring(0, max) : t;
    }

    /** {id, title{en,sw}, category, ages[], svg, parts[{id, kind, size, twin, name{en,sw}}], never[[a,b]]} */
    private Pic savePicture(String body) throws Bad {
        Object o;
        try {
            o = MiniJson.parse(body);
        } catch (RuntimeException e) {
            throw new Bad(400, "send the picture as JSON");
        }
        String id = MiniJson.str(o, "id").trim().toLowerCase(Locale.ROOT);
        if (!id.matches("[a-z0-9][a-z0-9-]{1,39}")) {
            throw new Bad(400, "the picture's name (id): 2 to 40 letters, numbers or dashes, e.g. red-bus");
        }
        String svg = MiniJson.str(o, "svg");
        if (svg.length() > 400000) {
            throw new Bad(413, "the picture is too big (400 KB at most)");
        }
        Pic old = pics.get(id);
        if (svg.isEmpty()) {
            if (old == null) {
                throw new Bad(400, "choose an SVG file");
            }
            svg = old.svg;
        }
        String clean = cleanSvg(svg);
        Document doc;
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            doc = f.newDocumentBuilder().parse(new ByteArrayInputStream(clean.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new Bad(400, "could not read the picture again: " + e.getMessage());
        }
        Element root = doc.getDocumentElement();
        root.setAttribute("data-picture", id);
        Object parts = MiniJson.at(o, "parts");
        if (!(parts instanceof List) || ((List<?>) parts).isEmpty()) {
            throw new Bad(400, "tick at least one part that can go missing");
        }
        StringBuilder pj = new StringBuilder("[");
        Set<String> ids = new HashSet<String>();
        int n = 0;
        for (Object x : (List<?>) parts) {
            String pid = MiniJson.str(x, "id");
            Element el = pid.isEmpty() ? null : byId(root, pid);
            if (el == null) {
                throw new Bad(400, "the picture has no part called \"" + clip(pid, 40) + "\"");
            }
            if (el == root || !ids.add(pid)) {
                throw new Bad(400, "\"" + clip(pid, 40) + "\" cannot be a part");
            }
            String kind = clip(MiniJson.str(x, "kind"), 30).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9 -]", "");
            String size = pick(MiniJson.str(x, "size"), SIZES, "medium");
            String en = clip(MiniJson.str(x, "name", "en"), 40), sw = clip(MiniJson.str(x, "name", "sw"), 40);
            if (en.isEmpty()) {
                throw new Bad(400, "give \"" + clip(pid, 40) + "\" an English name");
            }
            if (kind.isEmpty()) {
                kind = en.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9 -]", "");
            }
            String twin = clip(MiniJson.str(x, "twin"), 40);
            el.setAttribute("data-part", kind);
            el.setAttribute("data-name-en", en);
            el.setAttribute("data-name-sw", sw);
            el.setAttribute("data-size", size);
            if (twin.isEmpty()) {
                el.removeAttribute("data-twin");
            } else {
                el.setAttribute("data-twin", twin);
            }
            pj.append(n++ > 0 ? "," : "").append("{\"id\":").append(q(pid)).append(",\"kind\":").append(q(kind)).append(",\"size\":").append(q(size))
                    .append(",\"twin\":").append(q(twin)).append(",\"name\":{\"en\":").append(q(en)).append(",\"sw\":").append(q(sw)).append("}}");
        }
        pj.append(']');
        StringBuilder nj = new StringBuilder("[");
        Object never = MiniJson.at(o, "never");
        n = 0;
        if (never instanceof List) {
            for (Object x : (List<?>) never) {
                if (x instanceof List && ((List<?>) x).size() == 2 && ids.contains(String.valueOf(((List<?>) x).get(0)))
                        && ids.contains(String.valueOf(((List<?>) x).get(1)))) {
                    nj.append(n++ > 0 ? "," : "").append('[').append(q(String.valueOf(((List<?>) x).get(0)))).append(',')
                            .append(q(String.valueOf(((List<?>) x).get(1)))).append(']');
                }
            }
        }
        nj.append(']');
        StringBuilder aj = new StringBuilder("[");
        Object ages = MiniJson.at(o, "ages");
        n = 0;
        if (ages instanceof List) {
            for (Object x : (List<?>) ages) {
                String a = pick(String.valueOf(x), new String[] {"child", "teen", "adult"}, "");
                if (!a.isEmpty()) {
                    aj.append(n++ > 0 ? "," : "").append(q(a));
                }
            }
        }
        aj.append(']');
        String category = clip(MiniJson.str(o, "category"), 20).toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
        String ten = clip(MiniJson.str(o, "title", "en"), 40), tsw = clip(MiniJson.str(o, "title", "sw"), 40);
        if (ten.isEmpty()) {
            throw new Bad(400, "give the picture a name, e.g. the red bus");
        }
        String svgOut;
        try {
            Transformer t = TransformerFactory.newInstance().newTransformer();
            t.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
            StringWriter w = new StringWriter();
            t.transform(new DOMSource(doc), new StreamResult(w));
            svgOut = w.toString();
        } catch (Exception e) {
            throw new Bad(500, "could not write the picture: " + e.getMessage());
        }
        int version = old == null ? 1 : old.version + 1;
        String manifest = "{\"id\":" + q(id) + ",\"version\":" + version + ",\"category\":" + q(category.isEmpty() ? "other" : category) + ",\"ages\":" + aj
                + ",\"title\":{\"en\":" + q(ten) + ",\"sw\":" + q(tsw) + "},\"viewBox\":" + q(root.getAttribute("viewBox")) + ",\"parts\":" + pj
                + ",\"never_together\":" + nj + ",\"licence\":" + q(clip(MiniJson.str(o, "licence"), 120)) + ",\"builtin\":" + (old != null && old.builtin) + "}";
        try {
            Path up = dir.resolve("pictures");
            Files.createDirectories(up);
            Files.write(up.resolve(id + ".svg.part"), svgOut.getBytes(StandardCharsets.UTF_8));
            Files.move(up.resolve(id + ".svg.part"), up.resolve(id + ".svg"), StandardCopyOption.REPLACE_EXISTING);
            Files.write(up.resolve(id + ".json.part"), manifest.getBytes(StandardCharsets.UTF_8));
            Files.move(up.resolve(id + ".json.part"), up.resolve(id + ".json"), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new Bad(500, "could not save the picture: " + e.getMessage());
        }
        Pic pc = new Pic();
        pc.id = id;
        pc.svg = svgOut;
        pc.manifest = manifest;
        pc.builtin = old != null && old.builtin;
        pc.changed = pc.builtin;
        pc.enabled = old == null || old.enabled;
        fill(pc);
        pics.put(id, pc);
        return pc;
    }
}