package com.vstream.Vidiserver;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * Moderators: members the super-admin trusts with parts of the admin panel, right by right (Profiler -> Moderators).
 * A moderator signs in at memoryq.com/admin and sees only what was ticked; everything a moderator changes is logged.
 *
 *   Users   add users, modify users they added, remove users they added, modify any user, edit emails
 *   Games   game by game (the super-admin ticks each game): its players (players.vistr, players.missing) and its own
 *           admin (games.vistr: messages and reviews; games.missing: pictures). A game that is not public yet
 *           (GameIds.isPublic) is open only to the super-admin and the moderators ticked for it.
 *   GET  /api/admin/games                   every game: public or not, its moderators, players     (super-admin)
 *   POST /api/admin/games/save              game, public=1|0
 *   GET  /api/games                         the games and whether each is open to the caller (anyone; CORS for the games)
 *   Streams see streams and recordings; end a live stream; keep or take down recordings and streams
 *   Market  approve / reject / remove listings          Posts  hide wall posts and news stories
 *
 *   GET  /api/admin/moderators              the rights, the moderators, the log           (super-admin)
 *   POST /api/admin/moderators/save         username, rights=users.add,users.modifyOwn,...
 *   POST /api/admin/moderators/remove       username
 *   GET  /api/mod/me                        a moderator's own rights (the admin page asks this)
 *   GET  /api/mod/users?q=                  members to manage (own ones marked)
 *   POST /api/mod/users/create              username, password, email, gameId
 *   POST /api/mod/users/edit                u + profile fields, email, password
 *   POST /api/mod/users/remove              u          (only users this moderator added; the super-admin: anyone)
 *   POST /api/mod/users/gameid              u, game, code [, change=1] [, remove=1]   (an ID in one game: GameIds.GAMES; empty
 *                                           code: the one they have, else a new one; a different number only with change=1)
 *   GET  /api/mod/suggest?q=                members like what is typed (username, name, email): pick the existing person
 *   GET  /api/mod/game?game=vistr           a game's players (the games ticked for the moderator: players.vistr, ...)
 *   POST /api/mod/game/pause                game, u (or code: a game player who is not a member; super-admin), on=1|0
 *   POST /api/mod/game/remove               game, u (or code), [account=1: their Memoryq account too, if you added it]
 * Moderators act on the players they added (to the game, or the account); the super-admin on everyone.
 * The super-admin can do all of it. Moderators never change the super-admin or other moderators.
 */
public final class Moderators {

    /** key, area, what it allows. */
    static final String[][] RIGHTS = {
        {"users.add", "Users", "Add users"},
        {"users.modifyOwn", "Users", "Modify users they added"},
        {"users.removeOwn", "Users", "Remove users they added"},
        {"users.modifyAny", "Users", "Modify any user"},
        {"users.email", "Users", "Edit users' emails"},
        {"players.vistr", "Games", "Players: add, edit, pause and remove the ones they added (and give Player IDs)"},
        {"games.vistr", "Games", "Messages and reviews"},
        {"visitors.vistr", "Games", "Lite and guest visitors: who is using Vistr Lite (read only)"},
        {"reports.vistr", "Games", "Performance report: games per day and week for the players they added (table, CSV and PDF)"},
        {"players.missing", "Games", "Players: add, edit, pause and remove the ones they added (and give IDs)"},
        {"games.missing", "Games", "Pictures"},
        {"streams.view", "Streams", "See streams and recordings"},
        {"streams.end", "Streams", "End a live stream"},
        {"streams.recordings", "Streams", "Keep or take down recordings and streams"},
        {"market.approve", "Marketplace & posts", "Approve, reject and remove listings"},
        {"posts.hide", "Marketplace & posts", "Hide wall posts and news stories"},
    };
    static final Set<String> KEYS = new LinkedHashSet<String>();

    static {
        for (String[] r : RIGHTS) {
            KEYS.add(r[0]);
        }
    }

    /** What the admin needs to manage one user: profile edits, game IDs, deleting an account. */
    public interface Members {
        String profileJson(UserStore.Info i);

        /** Applies the form (profile fields, email when emailOk, password); returns the profile JSON. */
        String edit(String actor, UserStore.Info i, String form, boolean emailOk) throws Exception;

        /** A Vistr Player ID for the user (code empty: the game picks one); returns the Player ID. */
        String gameId(String actor, String username, String code) throws Exception;

        String gameIdOf(String username);

        void delete(String actor, String username) throws Exception;

        String emailOf(String username);

        void setEmail(String username, String email) throws Exception;

        /** First and last name from the profile ("" when none). */
        default String nameOf(String username) {
            return "";
        }
    }

    private static volatile Moderators current;

    private final Path file;
    private final Path logFile;
    private final UserStore users;
    private final SessionStore sessions;
    private final LongSupplier clock;
    private volatile Members members;
    private final Map<String, Set<String>> mods = new LinkedHashMap<String, Set<String>>(); // lower-case username -> rights
    private final Map<String, String[]> modInfo = new LinkedHashMap<String, String[]>(); // -> {by, at}
    private final Map<String, String[]> added = new LinkedHashMap<String, String[]>(); // lower-case member -> {moderator, at}

    public Moderators(Path file, UserStore users, SessionStore sessions, LongSupplier clock) {
        this.file = file;
        this.logFile = file.resolveSibling("moderators-log.txt");
        this.users = users;
        this.sessions = sessions;
        this.clock = clock;
    }

    public Moderators members(Members m) {
        this.members = m;
        return this;
    }

    private static String lc(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
    }

    public synchronized Moderators init() {
        if (Files.isRegularFile(file)) {
            try {
                for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    String[] f = line.split("\t", -1);
                    if (f.length >= 5 && f[0].equals("M")) {
                        mods.put(lc(f[1]), parse(f[2]));
                        modInfo.put(lc(f[1]), new String[] {f[3], f[4]});
                    } else if (f.length >= 4 && f[0].equals("A")) {
                        added.put(lc(f[1]), new String[] {f[2], f[3]});
                    }
                }
            } catch (IOException e) {
                System.out.println("[moderators] could not read " + file + ": " + e.getMessage());
            }
        }
        current = this;
        return this;
    }

    static Set<String> parse(String keys) {
        Set<String> out = new LinkedHashSet<String>();
        for (String k : (keys == null ? "" : keys).split("[\\s,]+")) {
            if (k.equals("users.gameId")) {
                k = "players.vistr"; // "assign game IDs" was for the Vistr word game: now one tick per game
            }
            if (KEYS.contains(k)) {
                out.add(k);
            }
        }
        Set<String> ordered = new LinkedHashSet<String>();
        for (String k : KEYS) {
            if (out.contains(k)) {
                ordered.add(k);
            }
        }
        return ordered;
    }

    private synchronized void save() {
        StringBuilder sb = new StringBuilder("# M username rights by at / A member addedBy at\n");
        for (Map.Entry<String, Set<String>> e : mods.entrySet()) {
            String[] in = modInfo.containsKey(e.getKey()) ? modInfo.get(e.getKey()) : new String[] {"", "0"};
            sb.append("M\t").append(e.getKey()).append('\t').append(String.join(",", e.getValue())).append('\t').append(in[0]).append('\t').append(in[1]).append('\n');
        }
        for (Map.Entry<String, String[]> e : added.entrySet()) {
            sb.append("A\t").append(e.getKey()).append('\t').append(e.getValue()[0]).append('\t').append(e.getValue()[1]).append('\n');
        }
        try {
            Path part = file.resolveSibling(file.getFileName() + ".part");
            Files.write(part, sb.toString().getBytes(StandardCharsets.UTF_8));
            Files.move(part, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            System.out.println("[moderators] could not save: " + e.getMessage());
        }
    }

    // ---- dummy "bot" user accounts (not the game bots that play behind the scenes in Vistr) ----
    /** Why this account looks like a dummy bot user, or null when it does not. */
    static String botReason(String username, String email) {
        String u = lc(username);
        String e = lc(email);
        if (e.endsWith("@bots.memoryq.com")) {
            return "e-mail @bots.memoryq.com";
        }
        if (u.endsWith(".bot") || u.endsWith("_bot") || u.endsWith("-bot")) {
            return "username ends in \"bot\"";
        }
        if (u.matches("bot\\d+") || u.matches(".*_bot\\d+")) {
            return "username looks like bot + number";
        }
        if (u.startsWith("bot_") || u.startsWith("bot-") || u.startsWith("bot.")) {
            return "username starts with \"bot\"";
        }
        if (e.matches("bot[-_.]?\\d*@.*")) {
            return "e-mail starts with \"bot\"";
        }
        return null;
    }

    private List<String[]> botUsers() {
        List<String[]> out = new ArrayList<String[]>();
        Members m = members;
        for (UserStore.Info i : users.listInfos()) {
            if (i.role != UserStore.Role.CHATTER && i.role != UserStore.Role.GUEST || isModerator(i.username)) {
                continue; // never the super-admin, a broadcaster or a moderator
            }
            String em = m == null ? null : m.emailOf(i.username);
            String why = botReason(i.username, em);
            if (why != null) {
                out.add(new String[] {i.username, em == null ? "" : em, why, i.role == UserStore.Role.GUEST ? "guest" : "member"});
            }
        }
        return out;
    }

    private String botUsersJson() {
        StringBuilder sb = new StringBuilder("{\"users\":[");
        int n = 0;
        for (String[] b : botUsers()) {
            sb.append(n++ > 0 ? "," : "").append("{\"username\":").append(q(b[0])).append(",\"email\":").append(q(b[1])).append(",\"why\":").append(q(b[2])).append(",\"kind\":").append(q(b[3])).append('}');
        }
        return sb.append("],\"count\":").append(n).append('}').toString();
    }

    private String removeBotUsers(UserStore.Info me, String names, boolean all) throws Bad {
        Members m = members;
        if (m == null) {
            throw new Bad(503, "not ready");
        }
        Set<String> want = new LinkedHashSet<String>();
        if (!all) {
            for (String x : names.split(",")) {
                if (!x.trim().isEmpty()) {
                    want.add(lc(x));
                }
            }
            if (want.isEmpty()) {
                throw new Bad(400, "tick the bot users to remove, or remove all of them");
            }
        }
        int removed = 0;
        StringBuilder skipped = new StringBuilder();
        int ns = 0;
        for (String[] b : botUsers()) { // only accounts that still look like bots are ever removed, whatever was asked for
            if (!all && !want.contains(lc(b[0]))) {
                continue;
            }
            try {
                m.delete(me.username, b[0]);
                synchronized (this) {
                    added.remove(lc(b[0]));
                    save();
                }
                log(me.username, "removed bot user", b[0], b[2]);
                removed++;
            } catch (Exception e) {
                skipped.append(ns++ > 0 ? "," : "").append(q(b[0] + ": " + e.getMessage()));
            }
        }
        return "{\"removed\":" + removed + ",\"failed\":[" + skipped + "]}";
    }

    // ---- what a paid day is worth, per game (set by the super-admin): {games a day, amount in cents, currency} ----
    private final Map<String, String[]> pay = new LinkedHashMap<String, String[]>();
    private boolean payLoaded;

    private synchronized String[] payOf(String game) {
        Path pf = file.resolveSibling("performance-pay.txt");
        if (!payLoaded) {
            payLoaded = true;
            try {
                if (Files.isRegularFile(pf)) {
                    for (String line : Files.readAllLines(pf, StandardCharsets.UTF_8)) {
                        String[] f = line.split("\t");
                        if (f.length >= 4 && f[1].matches("\\d{1,4}") && f[2].matches("\\d{1,12}")) {
                            pay.put(f[0], new String[] {f[1], f[2], f[3]});
                        }
                    }
                }
            } catch (IOException e) {
                System.out.println("[moderators] could not read the pay settings: " + e.getMessage());
            }
        }
        String[] v = pay.get(game);
        return v != null ? v : new String[] {"6", "2000", "USD"};
    }

    private synchronized void paySave(String game, int threshold, long cents, String currency) {
        payOf(game);
        pay.put(game, new String[] {String.valueOf(threshold), String.valueOf(cents), currency});
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String[]> e : pay.entrySet()) {
            sb.append(e.getKey()).append('\t').append(e.getValue()[0]).append('\t').append(e.getValue()[1]).append('\t').append(e.getValue()[2]).append('\n');
        }
        try {
            Path pf = file.resolveSibling("performance-pay.txt");
            Path part = file.resolveSibling("performance-pay.txt.part");
            Files.write(part, sb.toString().getBytes(StandardCharsets.UTF_8));
            Files.move(part, pf, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            System.out.println("[moderators] could not save the pay settings: " + e.getMessage());
        }
    }

    void log(String who, String action, String target, String detail) {
        String line = clock.getAsLong() + "\t" + who + "\t" + clean(action) + "\t" + clean(target) + "\t" + clean(detail) + "\n";
        try {
            Files.write(logFile, line.getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            System.out.println("[moderators] could not write the log: " + e.getMessage());
        }
    }

    private static String clean(String s) {
        String t = s == null ? "" : s.replaceAll("[\\t\\r\\n]+", " ").trim();
        return t.length() > 200 ? t.substring(0, 200) : t;
    }

    public synchronized boolean has(String username, String right) {
        Set<String> r = mods.get(lc(username));
        return r != null && r.contains(right);
    }

    public synchronized Set<String> rightsOf(String username) {
        Set<String> r = mods.get(lc(username));
        return r == null ? Collections.<String>emptySet() : new LinkedHashSet<String>(r);
    }

    public synchronized boolean isModerator(String username) {
        Set<String> r = mods.get(lc(username));
        return r != null && !r.isEmpty();
    }

    synchronized String addedBy(String member) {
        String[] a = added.get(lc(member));
        return a == null ? "" : a[0];
    }

    // ================================================================ the gate the other parts of the server use

    /**
     * May this request do what needs `right`? The super-admin always may; a moderator who has the right may (a change is
     * logged). Returns the username, or null.
     */
    public static String allowed(HttpExchange ex, SessionStore sessions, UserStore users, String right) {
        String a = ex.getRequestHeaders().getFirst("Authorization");
        SessionStore.Session s = a != null && a.startsWith("Bearer ") ? sessions.get(a.substring(7).trim()) : null;
        if (s == null) {
            return null;
        }
        UserStore.Info i = users.info(s.username);
        if (i == null || i.disabled) {
            return null;
        }
        if (i.role == UserStore.Role.ADMIN) {
            return i.username;
        }
        Moderators m = current;
        if (m == null || right == null || !m.has(i.username, right)) {
            return null;
        }
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
            m.log(i.username, ex.getRequestURI().getPath(), "", right);
        }
        return i.username;
    }

    /** For handlers that already know who is asking. */
    public static boolean may(UserStore.Info i, String right) {
        if (i == null || i.disabled) {
            return false;
        }
        if (i.role == UserStore.Role.ADMIN) {
            return true;
        }
        Moderators m = current;
        return m != null && right != null && m.has(i.username, right);
    }

    static void note(UserStore.Info i, HttpExchange ex, String right) {
        Moderators m = current;
        if (m != null && i != null && i.role != UserStore.Role.ADMIN && !"GET".equalsIgnoreCase(ex.getRequestMethod())) {
            m.log(i.username, ex.getRequestURI().getPath(), "", right);
        }
    }

    // ================================================================ HTTP

    static final class Bad extends Exception {
        final int status;
        /** More JSON fields for the answer (",\"existing\":\"1234\""). */
        String extra = "";

        Bad(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    public void register(HttpServer server) {
        server.createContext("/api/admin/moderators", this::handleAdmin);
        server.createContext("/api/mod/", this::handleMod);
        server.createContext("/api/admin/games", this::handleGames);
        server.createContext("/api/games", this::handlePublicGames);
    }

    /** The games and whether each is open to the caller (signed in or not). The games' own pages ask this too. */
    private void handlePublicGames(HttpExchange ex) throws IOException {
        ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        ex.getResponseHeaders().set("Access-Control-Allow-Headers", "Authorization");
        if ("OPTIONS".equalsIgnoreCase(ex.getRequestMethod())) {
            ex.sendResponseHeaders(204, -1);
            ex.close();
            return;
        }
        if (!ex.getRequestURI().getPath().equals("/api/games")) {
            send(ex, 404, "{\"error\":\"not found\"}");
            return;
        }
        UserStore.Info me = caller(ex);
        StringBuilder sb = new StringBuilder("{\"games\":[");
        for (int j = 0; j < GameIds.GAMES.length; j++) {
            String g = GameIds.GAMES[j][0];
            sb.append(j > 0 ? "," : "").append("{\"key\":").append(q(g)).append(",\"name\":").append(q(GameIds.GAMES[j][1])).append(",\"public\":")
                    .append(GameIds.isPublic(g)).append(",\"open\":").append(GameIds.isPublic(g) || me != null && gameOpenFor(me, g)).append('}');
        }
        send(ex, 200, sb.append("]}").toString());
    }

    /** The super-admin's games overview: every game (also the ones not public yet), its moderators and players. */
    private void handleGames(HttpExchange ex) throws IOException {
        int status = 200;
        String out;
        try {
            UserStore.Info me = caller(ex);
            if (me == null || me.role != UserStore.Role.ADMIN) {
                throw new Bad(403, "super-admin only");
            }
            String op = ex.getRequestURI().getPath().substring("/api/admin/games".length()).replaceFirst("^/", "");
            boolean post = "POST".equalsIgnoreCase(ex.getRequestMethod());
            GameIds ids = GameIds.current();
            if (op.equals("save")) {
                if (!post) {
                    throw new Bad(405, "use POST");
                }
                String form = SuperAgent.readBody(ex, 4096);
                String g = p(form, "game");
                if (GameIds.game(g) == null) {
                    throw new Bad(400, "no such game");
                }
                if (ids == null) {
                    throw new Bad(503, "not ready");
                }
                boolean on = "1".equals(p(form, "public"));
                ids.setPublic(me.username, g, on);
                log(me.username, on ? "game public" : "game not public", GameIds.game(g)[1], "");
            } else if (!op.isEmpty()) {
                throw new Bad(404, "not found");
            }
            StringBuilder sb = new StringBuilder("{\"games\":[");
            for (int j = 0; j < GameIds.GAMES.length; j++) {
                String g = GameIds.GAMES[j][0];
                String[] ch = ids == null ? new String[] {"", "0"} : ids.publicChange(g);
                StringBuilder mods = new StringBuilder("[");
                int n = 0;
                synchronized (this) {
                    for (Map.Entry<String, Set<String>> e : this.mods.entrySet()) {
                        if (e.getValue().contains("players." + g) || e.getValue().contains("games." + g)) {
                            UserStore.Info i = users.info(e.getKey());
                            mods.append(n++ > 0 ? "," : "").append(q(i == null ? e.getKey() : i.username));
                        }
                    }
                }
                int players = ids == null ? 0 : ids.players(g).size();
                sb.append(j > 0 ? "," : "").append("{\"key\":").append(q(g)).append(",\"name\":").append(q(GameIds.GAMES[j][1])).append(",\"public\":")
                        .append(GameIds.isPublic(g)).append(",\"by\":").append(q(ch[0])).append(",\"at\":").append(ch[1].matches("\\d+") ? ch[1] : "0")
                        .append(",\"moderators\":").append(mods.append(']')).append(",\"players\":").append(players).append('}');
            }
            out = sb.append("]}").toString();
        } catch (Bad e) {
            status = e.status;
            out = "{\"error\":" + q(e.getMessage()) + "}";
        }
        send(ex, status, out);
    }

    private static String q(String s) {
        return VideoApi.jq(s == null ? "" : s);
    }

    private static String p(String form, String k) {
        String v = MediaAuthHttpServer.queryParam(form, k);
        return v == null ? "" : v.trim();
    }

    private UserStore.Info caller(HttpExchange ex) {
        String a = ex.getRequestHeaders().getFirst("Authorization");
        SessionStore.Session s = a != null && a.startsWith("Bearer ") ? sessions.get(a.substring(7).trim()) : null;
        UserStore.Info i = s == null ? null : users.info(s.username);
        return i == null || i.disabled ? null : i;
    }

    private static void send(HttpExchange ex, int status, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(status, b.length);
        ex.getResponseBody().write(b);
        ex.close();
    }

    /** The game a right belongs to ("" for the others): players.vistr, games.vistr -> vistr. */
    static String gameOf(String right) {
        int dot = right.indexOf('.');
        String g = dot < 0 ? "" : right.substring(dot + 1);
        return (right.startsWith("players.") || right.startsWith("games.") || right.startsWith("reports.") || right.startsWith("visitors.")) && GameIds.game(g) != null ? g : "";
    }

    private static String catalogue() {
        StringBuilder sb = new StringBuilder("[");
        for (int j = 0; j < RIGHTS.length; j++) {
            sb.append(j > 0 ? "," : "").append("{\"key\":").append(q(RIGHTS[j][0])).append(",\"area\":").append(q(RIGHTS[j][1])).append(",\"label\":")
                    .append(q(RIGHTS[j][2])).append(",\"game\":").append(q(gameOf(RIGHTS[j][0]))).append('}');
        }
        return sb.append(']').toString();
    }

    /** Is this game open to this member: public, or the super-admin, or a moderator ticked for it. */
    public static boolean gameOpenFor(UserStore.Info i, String gameKey) {
        if (i == null || i.disabled) {
            return false;
        }
        if (GameIds.isPublic(gameKey) || i.role == UserStore.Role.ADMIN) {
            return true;
        }
        Moderators m = current;
        return m != null && (m.has(i.username, "players." + gameKey) || m.has(i.username, "games." + gameKey));
    }

    private static String arr(Set<String> s) {
        StringBuilder sb = new StringBuilder("[");
        int n = 0;
        for (String k : s) {
            sb.append(n++ > 0 ? "," : "").append(q(k));
        }
        return sb.append(']').toString();
    }

    private void handleAdmin(HttpExchange ex) throws IOException {
        int status = 200;
        String out;
        try {
            UserStore.Info me = caller(ex);
            if (me == null || me.role != UserStore.Role.ADMIN) {
                throw new Bad(403, "super-admin only");
            }
            String op = ex.getRequestURI().getPath().substring("/api/admin/moderators".length()).replaceFirst("^/", "");
            boolean post = "POST".equalsIgnoreCase(ex.getRequestMethod());
            String form = post ? SuperAgent.readBody(ex, 8192) : "";
            if (!op.isEmpty() && !post) {
                throw new Bad(405, "use POST");
            }
            switch (op) {
                case "":
                    out = adminJson();
                    break;
                case "save": {
                    UserStore.Info m = users.info(p(form, "username"));
                    if (m == null) {
                        throw new Bad(404, "there is no member called \"" + p(form, "username") + "\"");
                    }
                    if (m.role == UserStore.Role.ADMIN) {
                        throw new Bad(400, "the super-admin has every right already");
                    }
                    if (m.role == UserStore.Role.GUEST || m.disabled) {
                        throw new Bad(400, "a guest or switched-off account cannot be a moderator");
                    }
                    Set<String> r = parse(p(form, "rights"));
                    synchronized (this) {
                        if (r.isEmpty()) {
                            mods.remove(lc(m.username));
                            modInfo.remove(lc(m.username));
                        } else {
                            if (!mods.containsKey(lc(m.username))) {
                                modInfo.put(lc(m.username), new String[] {me.username, String.valueOf(clock.getAsLong())});
                            }
                            mods.put(lc(m.username), r);
                        }
                        save();
                    }
                    log(me.username, "rights", m.username, r.isEmpty() ? "no rights (not a moderator)" : String.join(",", r));
                    out = adminJson();
                    break;
                }
                case "pay": {
                    String game = p(form, "game");
                    if (GameIds.game(game) == null) {
                        throw new Bad(400, "no such game");
                    }
                    String th = p(form, "threshold").trim();
                    String am = p(form, "amount").trim().replace(',', '.');
                    String cur = p(form, "currency").trim();
                    if (!th.matches("\\d{1,3}") || Integer.parseInt(th) < 1) {
                        throw new Bad(400, "games a day must be a whole number from 1 to 999");
                    }
                    if (!am.matches("\\d{1,9}(\\.\\d{1,2})?")) {
                        throw new Bad(400, "the amount must be a number such as 20 or 12.50");
                    }
                    if (!cur.matches("[A-Za-z$\u20ac\u00a3]{1,6}")) {
                        throw new Bad(400, "the currency must be 1 to 6 letters or a symbol, such as USD or KES");
                    }
                    String[] parts = am.split("\\.");
                    long cents = Long.parseLong(parts[0]) * 100 + (parts.length > 1 ? Long.parseLong((parts[1] + "0").substring(0, 2)) : 0);
                    paySave(game, Integer.parseInt(th), cents, cur);
                    log(me.username, "pay", game, th + " games a day = " + am + " " + cur);
                    out = "{\"threshold\":" + Integer.parseInt(th) + ",\"cents\":" + cents + ",\"currency\":" + q(cur) + "}";
                    break;
                }
                case "botusers": {
                    out = botUsersJson();
                    break;
                }
                case "botusers/remove": {
                    out = removeBotUsers(me, p(form, "names"), "1".equals(p(form, "all")));
                    break;
                }
                case "remove": {
                    String u = lc(p(form, "username"));
                    synchronized (this) {
                        mods.remove(u);
                        modInfo.remove(u);
                        save();
                    }
                    log(me.username, "removed moderator", u, "");
                    out = adminJson();
                    break;
                }
                default:
                    throw new Bad(404, "not found");
            }
        } catch (Bad e) {
            status = e.status;
            out = "{\"error\":" + q(e.getMessage()) + "}";
        }
        send(ex, status, out);
    }

    private String adminJson() {
        StringBuilder sb = new StringBuilder("{\"rights\":").append(catalogue()).append(",\"moderators\":[");
        Map<String, Integer> counts = new LinkedHashMap<String, Integer>();
        synchronized (this) {
            for (String[] a : added.values()) {
                counts.put(lc(a[0]), (counts.containsKey(lc(a[0])) ? counts.get(lc(a[0])) : 0) + 1);
            }
            int n = 0;
            for (Map.Entry<String, Set<String>> e : mods.entrySet()) {
                UserStore.Info i = users.info(e.getKey());
                String[] in = modInfo.containsKey(e.getKey()) ? modInfo.get(e.getKey()) : new String[] {"", "0"};
                int addedCount = counts.containsKey(e.getKey()) ? counts.get(e.getKey()).intValue() : 0;
                sb.append(n++ > 0 ? "," : "").append("{\"username\":").append(q(i == null ? e.getKey() : i.username)).append(",\"exists\":").append(i != null)
                        .append(",\"rights\":").append(arr(e.getValue())).append(",\"since\":").append(in[1]).append(",\"added\":")
                        .append(addedCount).append('}');
            }
        }
        sb.append("],\"log\":[");
        List<String> lines = new ArrayList<String>();
        try {
            if (Files.isRegularFile(logFile)) {
                lines = Files.readAllLines(logFile, StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            lines = new ArrayList<String>();
        }
        int n = 0;
        for (int j = lines.size() - 1; j >= 0 && n < 200; j--) {
            String[] f = lines.get(j).split("\t", -1);
            if (f.length < 5) {
                continue;
            }
            sb.append(n++ > 0 ? "," : "").append("{\"at\":").append(f[0].matches("\\d+") ? f[0] : "0").append(",\"who\":").append(q(f[1])).append(",\"action\":")
                    .append(q(f[2])).append(",\"target\":").append(q(f[3])).append(",\"detail\":").append(q(f[4])).append('}');
        }
        return sb.append("]}").toString();
    }

    // ---- the moderators' own part ----

    private boolean mayModify(UserStore.Info me, UserStore.Info target) {
        if (me.role == UserStore.Role.ADMIN) {
            return true;
        }
        if (target.role == UserStore.Role.ADMIN || isModerator(target.username)) {
            return false; // never the super-admin or another moderator
        }
        return has(me.username, "users.modifyAny") || (has(me.username, "users.modifyOwn") || gameMod(me)) && lc(addedBy(target.username)).equals(lc(me.username));
    }

    /** Looks after the players of at least one game (players.vistr, ...): may edit the accounts they made. */
    private boolean gameMod(UserStore.Info me) {
        for (String r : rightsOf(me.username)) {
            if (r.startsWith("players.")) {
                return true;
            }
        }
        return false;
    }

    private boolean own(UserStore.Info me, UserStore.Info target) {
        return lc(addedBy(target.username)).equals(lc(me.username));
    }

    private boolean anyUsersRight(UserStore.Info me) {
        if (me.role == UserStore.Role.ADMIN) {
            return true;
        }
        for (String r : rightsOf(me.username)) {
            if (r.startsWith("users.") || r.startsWith("players.") || r.startsWith("reports.")) {
                return true;
            }
        }
        return false;
    }

    /** May this person give IDs in (and look after the players of) a game? */
    private boolean mayGame(UserStore.Info me, String game) {
        return me.role == UserStore.Role.ADMIN || has(me.username, "players." + game);
    }

    private boolean anyGame(UserStore.Info me) {
        for (String[] g : GameIds.GAMES) {
            if (mayGame(me, g[0])) {
                return true;
            }
        }
        return false;
    }

    /** Is this member one of "my" players in the game: I gave them the ID, or I made their account. */
    private boolean myPlayer(UserStore.Info me, String member, String game) {
        if (me.role == UserStore.Role.ADMIN) {
            return true;
        }
        GameIds g = GameIds.current();
        GameIds.Entry e = g == null ? null : g.entry(member, game);
        return e != null && lc(e.by).equals(lc(me.username)) || lc(addedBy(member)).equals(lc(me.username));
    }

    private static String mask(String email) {
        if (email == null || email.isEmpty()) {
            return "";
        }
        int at = email.indexOf('@');
        return at <= 1 ? "***" + email.substring(Math.max(0, at)) : email.charAt(0) + "***" + email.substring(at);
    }

    /** The member who has this email already (another account), or null. */
    private UserStore.Info emailOwner(Members m, String email, String except) {
        if (email == null || email.trim().isEmpty()) {
            return null;
        }
        for (UserStore.Info i : users.listInfos()) {
            String e = m.emailOf(i.username);
            if (e != null && e.equalsIgnoreCase(email.trim()) && !i.username.equalsIgnoreCase(except)) {
                return i;
            }
        }
        return null;
    }

    private static Bad refused(GameIds.Refused e) {
        Bad b = new Bad(e.status == 409 || e.status == 503 || e.status == 404 ? e.status : 400, e.getMessage());
        b.extra = ",\"existing\":" + q(e.existing) + ",\"owner\":" + q(e.owner);
        return b;
    }

    /** A game ID through the per-game store (kept on memoryq.com even when the game cannot be reached yet). */
    private static String giveId(Members m, String actor, String user, String game, String code) throws Exception {
        return giveId(m, actor, user, game, code, false);
    }

    private static String giveId(Members m, String actor, String user, String game, String code, boolean change) throws Exception {
        GameIds g = GameIds.current();
        if (g == null) {
            return m.gameId(actor, user, code);
        }
        return g.set(actor, user, game, code, change).id;
    }

    private String rowJson(UserStore.Info me, UserStore.Info i) {
        boolean mine = own(me, i);
        boolean admin = me.role == UserStore.Role.ADMIN;
        boolean modify = mayModify(me, i);
        boolean seeEmail = admin || mine || has(me.username, "users.email") || has(me.username, "users.modifyAny");
        Members m = members;
        String email = m == null || !seeEmail ? "" : m.emailOf(i.username);
        return "{\"username\":" + q(i.username) + ",\"role\":" + q(i.role.name()) + ",\"disabled\":" + i.disabled + ",\"addedBy\":" + q(addedBy(i.username))
                + ",\"mine\":" + mine + ",\"email\":" + q(email == null ? "" : email) + ",\"hasEmail\":" + (m != null && m.emailOf(i.username) != null)
                + ",\"gameId\":" + q(GameIds.current() != null ? GameIds.current().idOf(i.username, "vistr") : m == null ? "" : m.gameIdOf(i.username)) + ",\"canModify\":" + modify
                + ",\"canEmail\":" + (modify && (admin || mine || has(me.username, "users.email")) || !admin && has(me.username, "users.email") && i.role != UserStore.Role.ADMIN && !isModerator(i.username))
                + ",\"canRemove\":" + (i.role != UserStore.Role.ADMIN && i.role != UserStore.Role.BROADCASTER && (admin || mine && has(me.username, "users.removeOwn")) && !isModerator(i.username))
                + ",\"canGameId\":" + (anyGame(me) && i.role != UserStore.Role.GUEST) + ",\"moderator\":" + isModerator(i.username)
                + ",\"games\":" + (GameIds.current() == null ? "[]" : GameIds.current().json(i.username)) + ",\"gamesMay\":" + gamesMay(me) + "}";
    }

    private String gamesMay(UserStore.Info me) {
        StringBuilder sb = new StringBuilder("[");
        int n = 0;
        for (String[] g : GameIds.GAMES) {
            if (mayGame(me, g[0])) {
                sb.append(n++ > 0 ? "," : "").append(q(g[0]));
            }
        }
        return sb.append(']').toString();
    }

    private void handleMod(HttpExchange ex) throws IOException {
        int status = 200;
        String out;
        try {
            UserStore.Info me = caller(ex);
            if (me == null) {
                throw new Bad(401, "sign in required");
            }
            String op = ex.getRequestURI().getPath().substring("/api/mod/".length());
            boolean post = "POST".equalsIgnoreCase(ex.getRequestMethod());
            String form = post ? SuperAgent.readBody(ex, 16384) : "";
            String query = ex.getRequestURI().getRawQuery() == null ? "" : ex.getRequestURI().getRawQuery();
            if (op.equals("me")) {
                Set<String> r = me.role == UserStore.Role.ADMIN ? KEYS : rightsOf(me.username);
                out = "{\"username\":" + q(me.username) + ",\"superAdmin\":" + (me.role == UserStore.Role.ADMIN) + ",\"rights\":" + arr(r) + ",\"catalogue\":" + catalogue() + "}";
                send(ex, 200, out);
                return;
            }
            if (!anyUsersRight(me)) {
                throw new Bad(403, "you have no rights over users");
            }
            Members m = members;
            if (m == null) {
                throw new Bad(503, "not ready");
            }
            switch (op) {
                case "users": {
                    String q = lc(p(query, "q"));
                    boolean all = me.role == UserStore.Role.ADMIN || has(me.username, "users.modifyAny") || has(me.username, "users.email");
                    String qq = q;
                    boolean admin = me.role == UserStore.Role.ADMIN;
                    List<UserStore.Info> list = new ArrayList<UserStore.Info>();
                    for (UserStore.Info i : users.listInfos()) {
                        if (!all && !own(me, i)) {
                            continue;
                        }
                        if (me.role != UserStore.Role.ADMIN && i.role == UserStore.Role.ADMIN) {
                            continue;
                        }
                        if (!qq.isEmpty() && !lc(i.username).contains(qq) && !lc(m.nameOf(i.username)).contains(qq)
                                && !(admin && lc(m.emailOf(i.username)).contains(qq))) {
                            continue;
                        }
                        list.add(i);
                    }
                    list.sort((a, b) -> Boolean.compare(own(me, b), own(me, a)) != 0 ? Boolean.compare(own(me, b), own(me, a)) : a.username.compareToIgnoreCase(b.username));
                    StringBuilder sb = new StringBuilder("{\"users\":[");
                    int n = 0;
                    for (UserStore.Info i : list) {
                        if (n >= 200) {
                            break;
                        }
                        sb.append(n++ > 0 ? "," : "").append(rowJson(me, i));
                    }
                    out = sb.append("],\"total\":").append(list.size()).append(",\"canAdd\":").append(me.role == UserStore.Role.ADMIN || has(me.username, "users.add")).append('}').toString();
                    break;
                }
                case "users/one": {
                    UserStore.Info i = users.info(p(query, "u"));
                    if (i == null || me.role != UserStore.Role.ADMIN && i.role == UserStore.Role.ADMIN) {
                        throw new Bad(404, "no such user");
                    }
                    if (GameIds.current() != null && mayGame(me, "vistr")) {
                        GameIds.current().lookUp(me.username, i.username, "vistr"); // the Player ID they already have, if any
                    }
                    String row = rowJson(me, i), prof = m.profileJson(i);
                    out = "{\"row\":" + row + ",\"profile\":" + (mayModify(me, i) ? prof : "{}") + "}";
                    break;
                }
                case "users/create": {
                    if (!post) {
                        throw new Bad(405, "use POST");
                    }
                    String gk = p(form, "game").equals("1") ? "vistr" : p(form, "game");
                    boolean forGame = !gk.isEmpty() && GameIds.game(gk) != null && mayGame(me, gk);
                    if (me.role != UserStore.Role.ADMIN && !has(me.username, "users.add") && !forGame) {
                        throw new Bad(403, "you may not add users");
                    }
                    String email = p(form, "email");
                    UserStore.Info same = emailOwner(m, email, "");
                    if (same != null && !"1".equals(p(form, "sure"))) {
                        Bad b = new Bad(409, email + " belongs to " + same.username + " already. If it is the same person, add " + same.username
                                + " (pick them from the list) - one account per person.");
                        b.extra = ",\"existingUser\":" + q(same.username);
                        throw b;
                    }
                    UserStore.Info i;
                    try {
                        i = users.createUser(p(form, "username"), MediaAuthHttpServer.queryParam(form, "password"), UserStore.Role.CHATTER);
                    } catch (UserStore.UserException e) {
                        throw new Bad(400, e.getMessage());
                    }
                    synchronized (this) {
                        added.put(lc(i.username), new String[] {me.username, String.valueOf(clock.getAsLong())});
                        save();
                    }
                    log(me.username, "added user", i.username, "");
                    String note = "";
                    if (!email.isEmpty()) {
                        try {
                            m.setEmail(i.username, email);
                        } catch (Exception e) {
                            note = "account made, but the email was not set: " + e.getMessage();
                        }
                    }
                    String gid = p(form, "gameId");
                    if (note.isEmpty() && (!gid.isEmpty() || !gk.isEmpty()) && GameIds.game(gk.isEmpty() ? "vistr" : gk) != null && mayGame(me, gk.isEmpty() ? "vistr" : gk)) {
                        try {
                            String code = giveId(m, me.username, i.username, gk.isEmpty() ? "vistr" : gk, gid);
                            log(me.username, "game ID", i.username, (gk.isEmpty() ? "vistr" : gk) + " " + code);
                        } catch (Exception e) {
                            note = "account made, but no game ID: " + e.getMessage();
                        }
                    }
                    out = "{\"row\":" + rowJson(me, i) + ",\"note\":" + q(note) + "}";
                    break;
                }
                case "users/edit": {
                    if (!post) {
                        throw new Bad(405, "use POST");
                    }
                    UserStore.Info i = users.info(p(form, "u"));
                    if (i == null) {
                        throw new Bad(404, "no such user");
                    }
                    boolean modify = mayModify(me, i);
                    boolean emailOnly = !modify && me.role != UserStore.Role.ADMIN && has(me.username, "users.email") && i.role != UserStore.Role.ADMIN && !isModerator(i.username);
                    if (!modify && !emailOnly) {
                        throw new Bad(403, own(me, i) ? "you may not modify users" : "you may only modify users you added");
                    }
                    boolean emailOk = me.role == UserStore.Role.ADMIN || has(me.username, "users.email") || own(me, i) && (has(me.username, "users.modifyOwn") || gameMod(me));
                    if (emailOnly) {
                        String email = p(form, "email");
                        if (!email.isEmpty()) {
                            try {
                                m.setEmail(i.username, email);
                            } catch (Exception e) {
                                throw new Bad(400, e.getMessage());
                            }
                        }
                        log(me.username, "email", i.username, "");
                        out = "{\"row\":" + rowJson(me, i) + "}";
                        break;
                    }
                    String pf;
                    try {
                        pf = m.edit(me.username, i, form, emailOk);
                    } catch (Bad b) {
                        throw b;
                    } catch (Exception e) {
                        throw new Bad(400, e.getMessage());
                    }
                    log(me.username, "modified user", i.username, "");
                    out = "{\"row\":" + rowJson(me, users.info(i.username)) + ",\"profile\":" + pf + "}";
                    break;
                }
                case "users/remove": {
                    if (!post) {
                        throw new Bad(405, "use POST");
                    }
                    UserStore.Info i = users.info(p(form, "u"));
                    if (i == null) {
                        throw new Bad(404, "no such user");
                    }
                    if (me.role != UserStore.Role.ADMIN && !(own(me, i) && has(me.username, "users.removeOwn"))) {
                        throw new Bad(403, own(me, i) ? "you may not remove users" : "you may only remove users you added");
                    }
                    if (isModerator(i.username)) {
                        throw new Bad(400, "take away the moderator's rights first");
                    }
                    try {
                        m.delete(me.username, i.username);
                    } catch (Exception e) {
                        throw new Bad(400, e.getMessage());
                    }
                    synchronized (this) {
                        added.remove(lc(i.username));
                        save();
                    }
                    log(me.username, "removed user", i.username, "");
                    out = "{\"removed\":" + q(i.username) + "}";
                    break;
                }
                case "users/gameid": {
                    if (!post) {
                        throw new Bad(405, "use POST");
                    }
                    UserStore.Info i = users.info(p(form, "u"));
                    if (i == null || me.role != UserStore.Role.ADMIN && i.role == UserStore.Role.ADMIN) {
                        throw new Bad(404, "no such user");
                    }
                    String gameKey = p(form, "game").isEmpty() ? "vistr" : p(form, "game");
                    if (GameIds.game(gameKey) == null) {
                        throw new Bad(400, "no such game");
                    }
                    if (!mayGame(me, gameKey)) {
                        throw new Bad(403, "you may not give IDs in " + GameIds.game(gameKey)[1]);
                    }
                    GameIds.Entry had = GameIds.current() == null ? null : GameIds.current().entry(i.username, gameKey);
                    if (had != null && me.role != UserStore.Role.ADMIN && !myPlayer(me, i.username, gameKey)
                            && ("1".equals(p(form, "remove")) || !p(form, "code").replaceAll("\\D", "").isEmpty() && !p(form, "code").replaceAll("\\D", "").equals(had.id))) {
                        throw new Bad(403, i.username + " was added to this game by " + had.by + " - you may change only the players you added");
                    }
                    if ("1".equals(p(form, "remove"))) {
                        if (GameIds.current() != null) {
                            GameIds.current().remove(me.username, i.username, gameKey);
                        }
                        log(me.username, "game ID taken away", i.username, gameKey);
                        out = "{\"row\":" + rowJson(me, i) + ",\"gameId\":\"\"}";
                        break;
                    }
                    String code;
                    String warn = "";
                    try {
                        code = giveId(m, me.username, i.username, gameKey, p(form, "code").replaceAll("\\D", ""), "1".equals(p(form, "change")));
                        if (GameIds.current() != null) {
                            warn = GameIds.current().waitingNote(i.username, gameKey);
                        }
                    } catch (GameIds.Refused e) {
                        throw refused(e);
                    } catch (Exception e) {
                        throw new Bad(400, e.getMessage());
                    }
                    log(me.username, "game ID", i.username, gameKey + " " + code);
                    out = "{\"row\":" + rowJson(me, i) + ",\"gameId\":" + q(code) + ",\"game\":" + q(gameKey) + ",\"waiting\":" + q(warn) + "}";
                    break;
                }
                case "suggest": {
                    out = suggest(me, m, lc(p(query, "q")));
                    break;
                }
                case "game": {
                    out = gameJson(me, m, p(query, "game"));
                    break;
                }
                case "report": {
                    report(ex, me, m, query);
                    return;
                }
                case "game/pause":
                case "game/remove": {
                    if (!post) {
                        throw new Bad(405, "use POST");
                    }
                    out = gameAction(me, m, op.equals("game/pause"), form);
                    break;
                }
                default:
                    throw new Bad(404, "not found");
            }
        } catch (Bad e) {
            status = e.status;
            out = "{\"error\":" + q(e.getMessage()) + e.extra + "}";
        } catch (RuntimeException e) {
            status = 500;
            out = "{\"error\":" + q("server error: " + e.getMessage()) + "}";
        }
        send(ex, status, out);
    }

    // ---- suggestions: the person who is already here ----

    private String suggest(UserStore.Info me, Members m, String q) {
        StringBuilder sb = new StringBuilder("{\"users\":[");
        if (q.length() < 2) {
            return sb.append("]}").toString();
        }
        boolean admin = me.role == UserStore.Role.ADMIN;
        boolean seeAll = admin || has(me.username, "users.email") || has(me.username, "users.modifyAny");
        List<String[]> hits = new ArrayList<String[]>(); // rank, username, name, email
        for (UserStore.Info i : users.listInfos()) {
            if (!admin && i.role == UserStore.Role.ADMIN || i.role == UserStore.Role.GUEST) {
                continue;
            }
            String u = lc(i.username), name = m.nameOf(i.username), em = m.emailOf(i.username);
            String ln = lc(name), le = lc(em);
            int rank = u.equals(q) ? 0 : u.startsWith(q) ? 1 : u.contains(q) ? 2 : ln.contains(q) ? 3 : le.startsWith(q) || le.equals(q) ? 4 : le.contains(q) ? 5 : -1;
            if (rank < 0) {
                continue;
            }
            hits.add(new String[] {String.valueOf(rank), i.username, name == null ? "" : name, em == null ? "" : (seeAll || own(me, i) ? em : mask(em))});
        }
        hits.sort((a, b) -> a[0].equals(b[0]) ? a[1].compareToIgnoreCase(b[1]) : a[0].compareTo(b[0]));
        int n = 0;
        for (String[] h : hits) {
            if (n >= 8) {
                break;
            }
            StringBuilder games = new StringBuilder("{");
            int k = 0;
            GameIds g = GameIds.current();
            for (String[] gm : GameIds.GAMES) {
                String id = g == null ? "" : g.idOf(h[1], gm[0]);
                if (!id.isEmpty()) {
                    games.append(k++ > 0 ? "," : "").append(q(gm[0])).append(':').append(q(id));
                }
            }
            sb.append(n++ > 0 ? "," : "").append("{\"username\":").append(q(h[1])).append(",\"name\":").append(q(h[2])).append(",\"email\":").append(q(h[3]))
                    .append(",\"games\":").append(games.append('}')).append('}');
        }
        return sb.append("]}").toString();
    }

    // ---- the performance report: games per player per day and week (for paying moderators) ----

    private final Map<String, Object[]> liveCache = new LinkedHashMap<String, Object[]>();

    /** The super-admin, or a moderator the super-admin switched the report on for: only the players they added. */
    @SuppressWarnings("unchecked")
    private void report(HttpExchange ex, UserStore.Info me, Members m, String query) throws Bad, IOException {
        String game = p(query, "game");
        String[] g = GameIds.game(game);
        if (g == null) {
            throw new Bad(400, "no such game");
        }
        boolean admin = me.role == UserStore.Role.ADMIN;
        if (!admin && !has(me.username, "reports." + game)) {
            throw new Bad(403, "the performance report is not switched on for you - ask the super-admin");
        }
        GameIds ids = GameIds.current();
        if (ids == null || !ids.hasDailyRecord(game)) {
            throw new Bad(400, g[1] + " does not keep a daily record");
        }
        String to = p(query, "to").isEmpty() ? java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString() : p(query, "to");
        String from = p(query, "from");
        try {
            if (from.isEmpty()) {
                from = java.time.LocalDate.parse(to).minusDays(27).toString();
            }
            PerformanceReport.dates(from, to);
        } catch (RuntimeException e) {
            throw new Bad(400, e.getMessage() == null || e.getMessage().startsWith("Text") ? "dates must look like 2026-10-31" : e.getMessage());
        }
        String today = java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString();
        String lo = from.compareTo(today) < 0 ? from : today; // the games of today are always fetched too
        String hi = to.compareTo(today) > 0 ? to : today;
        String cacheKey = game + "|" + lo + "|" + hi;
        boolean live = "1".equals(p(query, "live")); // the page asking every few seconds: a few seconds old is fine
        java.util.Map<String, java.util.Map<String, Integer>> plays = null;
        List<String[]> roster = null;
        boolean fresh = false;
        if (live) {
            synchronized (liveCache) {
                Object[] c = liveCache.get(cacheKey);
                if (c != null && System.currentTimeMillis() - ((Long) c[0]).longValue() < 5000) {
                    plays = (java.util.Map<String, java.util.Map<String, Integer>>) c[1];
                    roster = (List<String[]>) c[2];
                    fresh = true;
                }
            }
        }
        if (!fresh) {
            try {
                plays = ids.playsPerDay(me.username, game, lo, hi);
            } catch (Exception e) {
                throw new Bad(502, g[1] + " could not be reached: " + e.getMessage());
            }
            if (plays == null) {
                throw new Bad(502, g[1] + " could not be reached right now - try again in a minute");
            }
            roster = ids.refreshFromGame(me.username, game);
            if (live) {
                synchronized (liveCache) {
                    if (liveCache.size() > 40) {
                        liveCache.clear();
                    }
                    liveCache.put(cacheKey, new Object[] {Long.valueOf(System.currentTimeMillis()), plays, roster});
                }
            }
        }
        Map<String, String[]> byCode = new LinkedHashMap<String, String[]>();
        if (roster != null) {
            for (String[] r : roster) {
                byCode.put(r[0], r);
            }
        }
        String now = java.time.LocalDateTime.now(java.time.ZoneOffset.UTC).withNano(0).toString().replace('T', ' ');
        PerformanceReport rep = new PerformanceReport(g[1], admin ? "all players" : "the players added by " + me.username, from, to, now);
        rep.today = today;
        String[] pv = payOf(game);
        rep.payThreshold = Integer.parseInt(pv[0]);
        rep.payCents = Long.parseLong(pv[1]);
        rep.payCurrency = pv[2];
        List<PerformanceReport.Row> rows = new ArrayList<PerformanceReport.Row>();
        java.util.Set<String> seen = new java.util.HashSet<String>();
        for (GameIds.Entry e : ids.players(game)) {
            UserStore.Info i = users.info(e.user);
            if (i == null || i.role == UserStore.Role.ADMIN || !admin && !myPlayer(me, e.user, game)) {
                continue;
            }
            String[] r = byCode.get(e.id);
            String em = m.emailOf(e.user);
            if ((em == null || em.isEmpty()) && r != null) {
                em = r[2];
            }
            String seenAs = r != null && !r[1].isEmpty() ? r[1] : e.user;
            String by = e.by == null || e.by.isEmpty() ? addedBy(e.user) : e.by;
            PerformanceReport.Row row = new PerformanceReport.Row(em, seenAs, e.id, by == null || by.isEmpty() ? "super-admin" : by);
            java.util.Map<String, Integer> d = plays.get(e.id);
            if (d != null) {
                row.days.putAll(d);
            }
            seen.add(e.id);
            rows.add(row);
        }
        if (admin) { // players of the game who are not Memoryq members (from the Android app)
            for (String[] r : byCode.values()) {
                if (r[3].isEmpty() && !seen.contains(r[0]) && !r[2].toLowerCase(Locale.ROOT).endsWith("@bots.memoryq.com")) { // the game's own bots are not players
                    PerformanceReport.Row row = new PerformanceReport.Row(r[2], r[1].isEmpty() ? "(no name)" : r[1], r[0], "(not a Memoryq member)");
                    java.util.Map<String, Integer> d = plays.get(r[0]);
                    if (d != null) {
                        row.days.putAll(d);
                    }
                    rows.add(row);
                }
            }
        }
        rows.sort((a, b) -> a.moderator.equalsIgnoreCase(b.moderator) ? a.username.compareToIgnoreCase(b.username) : a.moderator.compareToIgnoreCase(b.moderator));
        rep.rows.addAll(rows);
        String format = p(query, "format").toLowerCase();
        String base = game + "-performance-" + from + "_to_" + to;
        if (format.equals("csv") || format.equals("pdf")) {
            log(me.username, "report", game, from + ".." + to + " " + format);
        }
        if (format.equals("csv")) {
            sendFile(ex, "text/csv; charset=utf-8", base + ".csv", rep.csv().getBytes(StandardCharsets.UTF_8));
        } else if (format.equals("pdf")) {
            sendFile(ex, "application/pdf", base + ".pdf", rep.pdf());
        } else {
            send(ex, 200, rep.json());
        }
    }

    private static void sendFile(HttpExchange ex, String type, String name, byte[] b) throws IOException {
        ex.getResponseHeaders().set("Content-Type", type);
        ex.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"" + name.replaceAll("[^A-Za-z0-9._-]", "_") + "\"");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        ex.sendResponseHeaders(200, b.length);
        ex.getResponseBody().write(b);
        ex.close();
    }

    // ---- a game's players (the moderator's game page) ----

    private String gameJson(UserStore.Info me, Members m, String game) throws Bad {
        String[] g = GameIds.game(game);
        if (g == null) {
            throw new Bad(400, "no such game");
        }
        if (!mayGame(me, game)) {
            throw new Bad(403, "you do not look after " + g[1]);
        }
        GameIds ids = GameIds.current();
        boolean admin = me.role == UserStore.Role.ADMIN;
        List<String[]> roster = ids == null ? null : ids.refreshFromGame(me.username, game); // the game's own numbers first
        Map<String, String[]> stats = new LinkedHashMap<String, String[]>();
        StringBuilder others = new StringBuilder("[");
        int no = 0;
        if (roster != null) {
            for (String[] r : roster) {
                if (!r[3].isEmpty()) {
                    stats.put(lc(r[3]), r);
                } else if (admin) { // players who are not Memoryq members (from the Android app): the super-admin sees them
                    others.append(no++ > 0 ? "," : "").append("{\"id\":").append(q(r[0])).append(",\"name\":").append(q(r[1])).append(",\"email\":").append(q(r[2]))
                            .append(",\"paused\":").append("1".equals(r[4])).append(",\"points\":").append(q(r[5])).append(",\"lastSeen\":").append(q(r[7]))
                            .append(",\"games\":").append(q(r[8])).append('}');
                }
            }
        }
        StringBuilder sb = new StringBuilder("{\"game\":").append(q(game)).append(",\"name\":").append(q(g[1])).append(",\"needsEmail\":").append(!g[4].isEmpty())
                .append(",\"reachable\":").append(roster != null || ids == null || !ids.keepsOwnPlayers(game)).append(",\"admin\":").append(admin)
                .append(",\"canNew\":true,\"players\":[");
        int n = 0;
        List<GameIds.Entry> list = ids == null ? new ArrayList<GameIds.Entry>() : ids.players(game);
        list.sort((a, b) -> {
            boolean ma = myPlayer(me, a.user, game), mb = myPlayer(me, b.user, game);
            return ma != mb ? (ma ? -1 : 1) : a.user.compareToIgnoreCase(b.user);
        });
        for (GameIds.Entry e : list) {
            UserStore.Info i = users.info(e.user);
            if (i == null || !admin && i.role == UserStore.Role.ADMIN) {
                continue;
            }
            boolean mine = myPlayer(me, i.username, game);
            String em = m.emailOf(i.username);
            String[] st = stats.get(lc(i.username));
            sb.append(n++ > 0 ? "," : "").append("{\"username\":").append(q(i.username)).append(",\"name\":").append(q(m.nameOf(i.username)))
                    .append(",\"email\":").append(q(em == null ? "" : mine || admin ? em : mask(em))).append(",\"id\":").append(q(e.id))
                    .append(",\"state\":").append(q(e.state)).append(",\"note\":").append(q(e.note)).append(",\"paused\":").append(e.paused)
                    .append(",\"by\":").append(q(e.by)).append(",\"accountBy\":").append(q(addedBy(i.username))).append(",\"mine\":").append(mine)
                    .append(",\"madeByMe\":").append(admin || own(me, i)).append(",\"points\":").append(q(st == null ? "" : st[5]))
                    .append(",\"lastSeen\":").append(q(st == null ? "" : st[7])).append(",\"games\":").append(q(st == null ? "" : st[8])).append('}');
        }
        return sb.append("],\"others\":").append(others.append(']')).append('}').toString();
    }

    private String gameAction(UserStore.Info me, Members m, boolean pause, String form) throws Bad {
        String game = p(form, "game");
        String[] g = GameIds.game(game);
        if (g == null) {
            throw new Bad(400, "no such game");
        }
        if (!mayGame(me, game)) {
            throw new Bad(403, "you do not look after " + g[1]);
        }
        GameIds ids = GameIds.current();
        if (ids == null) {
            throw new Bad(503, "not ready");
        }
        boolean on = "1".equals(p(form, "on"));
        String code = p(form, "code").replaceAll("\\D", "");
        if (p(form, "u").isEmpty() && !code.isEmpty()) { // a game player who is not a member: the super-admin only
            if (me.role != UserStore.Role.ADMIN) {
                throw new Bad(403, "only the super-admin acts on players who are not Memoryq members");
            }
            try {
                ids.dropOrPauseCode(me.username, game, code, pause, on);
            } catch (GameIds.Refused e) {
                throw refused(e);
            }
            log(me.username, pause ? (on ? "paused player" : "let player play") : "deleted player", "#" + code, game);
            return "{\"ok\":true}";
        }
        UserStore.Info i = users.info(p(form, "u"));
        if (i == null || me.role != UserStore.Role.ADMIN && i.role == UserStore.Role.ADMIN) {
            throw new Bad(404, "no such user");
        }
        if (!myPlayer(me, i.username, game)) {
            throw new Bad(403, "you may change only the players you added");
        }
        try {
            if (pause) {
                ids.setPaused(me.username, i.username, game, on);
                log(me.username, on ? "paused player" : "let player play", i.username, game);
                return "{\"ok\":true,\"paused\":" + on + "}";
            }
            ids.drop(me.username, i.username, game);
        } catch (GameIds.Refused e) {
            throw refused(e);
        }
        log(me.username, "removed from game", i.username, game);
        boolean account = false;
        String note = "";
        if ("1".equals(p(form, "account"))) {
            if (me.role != UserStore.Role.ADMIN && !own(me, i)) {
                note = "the Memoryq account stays (someone else made it)";
            } else if (isModerator(i.username) || i.role == UserStore.Role.ADMIN || i.role == UserStore.Role.BROADCASTER) {
                note = "the Memoryq account stays (a moderator, admin or broadcaster)";
            } else {
                try {
                    m.delete(me.username, i.username);
                    account = true;
                } catch (Exception e) {
                    note = "the account was not deleted: " + e.getMessage();
                }
            }
        }
        if (account) {
            synchronized (this) {
                added.remove(lc(i.username));
                save();
            }
            log(me.username, "removed user", i.username, "with the game");
        }
        return "{\"ok\":true,\"account\":" + account + ",\"note\":" + q(note) + "}";
    }
}