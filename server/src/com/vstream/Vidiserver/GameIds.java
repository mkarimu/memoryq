package com.vstream.Vidiserver;

import java.io.IOException;
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
 * Game IDs: one ID per member per game, given by the super-admin (or a moderator with the right) in Profiler -> All users
 * -> Edit -> Games. Every ID is kept here on memoryq.com first, so giving one never fails because a game server is down
 * or not updated yet: a game that keeps its own players (the Vistr word game) gets the ID as soon as it can be reached,
 * and the admin page shows "waiting for the game" until then.
 *
 * File (gameids.txt): username TAB game TAB id TAB state TAB note TAB at TAB by TAB paused(1/0)
 *   state: ok | waiting (not in the game yet; note says why)
 *
 * One person, one player: a member who already has a player in a game keeps it (their points are on it). Giving an ID
 * with an empty box returns the one they have; a different number replaces it only when the admin confirmed (change).
 * When the game cannot be reached, no number is made up for someone the game may already know: the admin is asked to
 * try again. The game is the truth for its own players: refreshFromGame() brings its numbers here.
 */
public final class GameIds {

    /** The games: key, name, digits min, digits max, needs an email, public when nothing was set ("1"). */
    static final String[][] GAMES = {
        {"vistr", "Vistr word game", "2", "4", "email", "1"},
        {"missing", "Spot What's Missing", "2", "6", "", "0"},
    };

    /** A game that keeps its own players (the Vistr word game). */
    public interface Sync {
        /** Sends an ID (code empty: the person's own, or a new one); change: a different number may replace theirs. */
        String send(String actor, String username, String code, boolean change) throws Exception;

        /** The member's Player ID in the game ("" when none), changing nothing. */
        default String peek(String actor, String username) throws Exception {
            return "";
        }

        default void pause(String actor, String id, boolean on) throws Exception {
        }

        /** Deletes the player in the game (its scores go). */
        default void drop(String actor, String id) throws Exception {
        }

        /** Every player in the game: {Player ID, name, email, member (username or ""), paused 1/0, points, rating, last seen, games}. */
        default List<String[]> roster(String actor) throws Exception {
            return null;
        }

        /** Games per player per day between two dates (YYYY-MM-DD): Player ID -> (date -> games); null when the game keeps no daily record. */
        default java.util.Map<String, java.util.Map<String, Integer>> report(String actor, String from, String to) throws Exception {
            return null;
        }

        /** Like report(), plus the time zone the days were cut in and each game that was kept singly; null when the game keeps no such record. */
        default Detail reportDetail(String actor, String from, String to) throws Exception {
            return null;
        }
    }

    /** What a game keeps about games per day: days (Player ID -> date -> games), daily (-> date -> {games, points, last time}), plays (-> {time, level, points, mode, platform, date}). */
    public static final class Detail {
        public String tz = "UTC";
        public String logFrom = "";
        public String today = "";
        public final java.util.Map<String, java.util.Map<String, Integer>> days = new java.util.LinkedHashMap<String, java.util.Map<String, Integer>>();
        public final java.util.Map<String, java.util.Map<String, long[]>> daily = new java.util.LinkedHashMap<String, java.util.Map<String, long[]>>();
        public final java.util.Map<String, List<String[]>> plays = new java.util.LinkedHashMap<String, List<String[]>>();
    }

    /** Thrown when the ID cannot be given (taken, wrong form, no email): nothing is saved. */
    static final class Refused extends Exception {
        final int status;
        /** 409: the Player ID the person already has, and whose (a member) it is. */
        String existing = "";
        String owner = "";

        Refused(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    static final class Entry {
        String user;
        String game;
        String id;
        String state;
        String note;
        long at;
        String by;
        boolean paused;
    }

    private static volatile GameIds current;

    private final Path file;
    private final LongSupplier clock;
    private final SecureRandom rnd = new SecureRandom();
    private final Map<String, Entry> map = new LinkedHashMap<String, Entry>(); // "user\tgame" -> entry
    private final Map<String, Sync> syncs = new LinkedHashMap<String, Sync>();
    /** Public access, set by the super-admin: game -> {"1" | "0", by, at} (games-open.txt). */
    private final Map<String, String[]> access = new LinkedHashMap<String, String[]>();
    private volatile java.util.function.Function<String, String> emailOf = u -> null;
    private volatile java.util.function.Function<String, String> knownVistr = u -> "";

    public GameIds(Path file, LongSupplier clock) {
        this.file = file;
        this.clock = clock;
    }

    static GameIds current() {
        return current;
    }

    public GameIds sync(String game, Sync s) {
        syncs.put(game, s);
        return this;
    }

    public GameIds emails(java.util.function.Function<String, String> f) {
        emailOf = f;
        return this;
    }

    /** Vistr Player IDs memoryq.com already knows from bringing the Vistr players in. */
    public GameIds knownVistr(java.util.function.Function<String, String> f) {
        knownVistr = f;
        return this;
    }

    private static String lc(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
    }

    private static String clean(String s) {
        return s == null ? "" : s.replaceAll("[\\t\\r\\n]", " ").trim();
    }

    static String[] game(String key) {
        for (String[] g : GAMES) {
            if (g[0].equals(key)) {
                return g;
            }
        }
        return null;
    }

    public synchronized GameIds init() {
        if (Files.isRegularFile(file)) {
            try {
                for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    String[] f = line.split("\t", -1);
                    if (f.length < 7 || game(f[1]) == null || f[2].isEmpty()) {
                        continue;
                    }
                    Entry e = new Entry();
                    e.user = f[0];
                    e.game = f[1];
                    e.id = f[2];
                    e.state = f[3];
                    e.note = f[4];
                    e.at = f[5].matches("\\d+") ? Long.parseLong(f[5]) : 0L;
                    e.by = f[6];
                    e.paused = f.length > 7 && f[7].equals("1");
                    map.put(lc(e.user) + "\t" + e.game, e);
                }
            } catch (IOException e) {
                System.out.println("[gameids] could not read " + file + ": " + e.getMessage());
            }
        }
        Path af = file.resolveSibling("games-open.txt");
        if (Files.isRegularFile(af)) {
            try {
                for (String line : Files.readAllLines(af, StandardCharsets.UTF_8)) {
                    String[] f = line.split("\t", -1);
                    if (f.length >= 4 && game(f[0]) != null) {
                        access.put(f[0], new String[] {f[1].equals("1") ? "1" : "0", f[2], f[3]});
                    }
                }
            } catch (IOException e) {
                System.out.println("[gameids] could not read " + af + ": " + e.getMessage());
            }
        }
        current = this;
        return this;
    }

    // ---- public access: a game is open to everyone only after the super-admin switches it on ----

    /** Is the game open to everyone? (No store yet: yes - nothing is shut by accident.) */
    public static boolean isPublic(String gameKey) {
        GameIds g = current;
        return g == null || g.publicNow(gameKey);
    }

    public synchronized boolean publicNow(String gameKey) {
        String[] a = access.get(gameKey);
        String[] g = game(gameKey);
        return a != null ? a[0].equals("1") : g != null && g[5].equals("1");
    }

    /** {by, at} of the last public-access change ("" and "0" when never changed). */
    public synchronized String[] publicChange(String gameKey) {
        String[] a = access.get(gameKey);
        return a == null ? new String[] {"", "0"} : new String[] {a[1], a[2]};
    }

    public void setPublic(String actor, String gameKey, boolean on) {
        StringBuilder sb = new StringBuilder();
        synchronized (this) {
            access.put(gameKey, new String[] {on ? "1" : "0", actor, String.valueOf(clock.getAsLong())});
            for (Map.Entry<String, String[]> e : access.entrySet()) {
                sb.append(e.getKey()).append('\t').append(e.getValue()[0]).append('\t').append(clean(e.getValue()[1])).append('\t').append(e.getValue()[2]).append('\n');
            }
            try {
                Path af = file.resolveSibling("games-open.txt");
                Path tmp = af.resolveSibling("games-open.txt.tmp");
                Files.write(tmp, sb.toString().getBytes(StandardCharsets.UTF_8));
                Files.move(tmp, af, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                System.out.println("[gameids] could not save public access: " + e.getMessage());
            }
        }
        System.out.println("[gameids] " + actor + (on ? " opened " : " closed ") + gameKey + (on ? " to everyone" : " (super-admin and its moderators only)"));
    }

    private void save() {
        StringBuilder sb = new StringBuilder();
        for (Entry e : map.values()) {
            sb.append(clean(e.user)).append('\t').append(e.game).append('\t').append(clean(e.id)).append('\t').append(e.state).append('\t')
                    .append(clean(e.note)).append('\t').append(e.at).append('\t').append(clean(e.by)).append('\t')
                    .append(e.paused ? "1" : "0").append('\n');
        }
        try {
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.write(tmp, sb.toString().getBytes(StandardCharsets.UTF_8));
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            System.out.println("[gameids] could not save: " + e.getMessage());
        }
    }

    /** The member's ID in one game ("" when none). */
    public String idOf(String user, String game) {
        Entry e;
        synchronized (this) {
            e = map.get(lc(user) + "\t" + game);
        }
        if (e != null) {
            return e.id;
        }
        if (game.equals("vistr")) {
            String k = knownVistr.apply(user);
            return k == null ? "" : k;
        }
        return "";
    }

    /** All games with the member's ID and its state, as JSON: [{"game","name","id","state","note","digits","needsEmail"}]. */
    public String json(String user) {
        StringBuilder sb = new StringBuilder("[");
        for (int j = 0; j < GAMES.length; j++) {
            String[] g = GAMES[j];
            Entry e;
            synchronized (this) {
                e = map.get(lc(user) + "\t" + g[0]);
            }
            String id = e != null ? e.id : idOf(user, g[0]);
            String state = e != null ? e.state : (id.isEmpty() ? "" : "ok");
            String note = e != null ? e.note : "";
            sb.append(j > 0 ? "," : "").append("{\"game\":").append(q(g[0])).append(",\"name\":").append(q(g[1])).append(",\"id\":").append(q(id))
                    .append(",\"state\":").append(q(state)).append(",\"note\":").append(q(note)).append(",\"digits\":").append(q(g[2] + "-" + g[3]))
                    .append(",\"needsEmail\":").append(!g[4].isEmpty()).append(",\"paused\":").append(e != null && e.paused)
                    .append(",\"by\":").append(q(e == null ? "" : e.by)).append('}');
        }
        return sb.append(']').toString();
    }

    private static String q(String s) {
        return VideoApi.jq(s == null ? "" : s);
    }

    private synchronized boolean taken(String game, String id, String user) {
        for (Entry e : map.values()) {
            if (e.game.equals(game) && e.id.equals(id) && !lc(e.user).equals(lc(user))) {
                return true;
            }
        }
        return false;
    }

    private synchronized String freeId(String game, int digits) {
        int lo = 1;
        for (int k = 1; k < digits; k++) {
            lo *= 10;
        }
        int span = lo * 9;
        for (int t = 0; t < 500; t++) {
            String id = String.valueOf(lo + rnd.nextInt(span));
            if (!taken(game, id, "")) {
                return id;
            }
        }
        throw new IllegalStateException("no free ID left");
    }

    /**
     * Gives (or changes) a member's ID in a game. code empty: one is picked. Returns the entry as saved; throws Refused when
     * it cannot be given (nothing changes then).
     */
    public Entry set(String actor, String user, String gameKey, String code) throws Refused {
        return set(actor, user, gameKey, code, false);
    }

    /** The entry for a member in a game, or null. */
    public synchronized Entry entry(String user, String game) {
        return map.get(lc(user) + "\t" + game);
    }

    public Entry set(String actor, String user, String gameKey, String code, boolean change) throws Refused {
        String[] g = game(gameKey);
        if (g == null) {
            throw new Refused(400, "no such game");
        }
        int min = Integer.parseInt(g[2]), max = Integer.parseInt(g[3]);
        String c = code == null ? "" : code.replaceAll("\\s", "");
        if (!c.isEmpty() && !c.matches("\\d{" + min + "," + max + "}")) {
            throw new Refused(400, g[1] + ": an ID is " + min + " to " + max + " digits");
        }
        if (!g[4].isEmpty()) {
            String em = emailOf.apply(user);
            if (em == null || em.isEmpty()) {
                throw new Refused(400, g[1] + " needs an email for " + user + " - type it in the Email box and save first");
            }
        }
        if (!c.isEmpty() && taken(gameKey, c, user)) {
            throw new Refused(409, g[1] + ": ID " + c + " belongs to someone else");
        }
        Entry had = entry(user, gameKey);
        if (had != null && had.state.equals("ok") && (c.isEmpty() || c.equals(had.id))) {
            return had; // they have it already: the same ID, nothing changes
        }
        if (had != null && !c.isEmpty() && !change) {
            Refused r = new Refused(409, user + " already has " + g[1] + " ID " + had.id + (gameKey.equals("vistr") ? " - their points are on it." : "."));
            r.existing = had.id;
            r.owner = user;
            throw r;
        }
        String state = "ok", note = "";
        Sync s = syncs.get(gameKey);
        if (s != null) {
            try {
                String got = s.send(actor, user, c, change);
                if (got != null && !got.isEmpty()) {
                    c = got;
                }
            } catch (Exception x) {
                int st = x instanceof VistrImport.Bad ? ((VistrImport.Bad) x).status : 0;
                if (st == 409 || st == 400 || st == 404) {
                    Refused r = new Refused(st, g[1] + ": " + x.getMessage().replaceFirst("^Vistr: ", ""));
                    if (x instanceof VistrImport.Bad) {
                        r.existing = ((VistrImport.Bad) x).existing;
                        r.owner = ((VistrImport.Bad) x).owner;
                    }
                    throw r;
                }
                if (c.isEmpty()) {
                    // never a made-up number: the game may know this person already (their points would be lost)
                    throw new Refused(503, g[1] + " cannot be reached right now, so it cannot be checked whether " + user
                            + " already has a Player ID there. Nothing was changed - try again in a minute.");
                }
                // a number the admin typed: kept here, sent when the game can be reached (it keeps theirs if they have one)
                state = "waiting";
                note = x.getMessage() == null ? x.toString() : x.getMessage();
            }
        } else if (c.isEmpty()) {
            c = freeId(gameKey, Math.max(min, Math.min(max, 4)));
        }
        Entry e = new Entry();
        e.user = user;
        e.game = gameKey;
        e.id = c;
        e.state = state;
        e.note = note;
        e.at = clock.getAsLong();
        e.by = had != null && !had.by.isEmpty() ? had.by : actor; // who added the player stays (moderators act on their own)
        e.paused = had != null && had.paused;
        synchronized (this) {
            map.put(lc(user) + "\t" + gameKey, e);
            save();
        }
        System.out.println("[gameids] " + actor + " gave " + user + " " + g[1] + " ID " + c + (state.equals("ok") ? "" : " (waiting for the game: " + note + ")"));
        return e;
    }

    /** Why the member's ID in a game is not in the game yet ("" when it is). */
    public synchronized String waitingNote(String user, String game) {
        Entry e = map.get(lc(user) + "\t" + game);
        return e == null || !e.state.equals("waiting") ? "" : (e.note.isEmpty() ? "waiting for the game" : e.note);
    }

    /** Takes a member's ID in a game away (here; a game that keeps its own players keeps its player). */
    public synchronized boolean remove(String actor, String user, String gameKey) {
        Entry e = map.remove(lc(user) + "\t" + gameKey);
        if (e != null) {
            save();
            System.out.println("[gameids] " + actor + " took away " + user + "'s " + gameKey + " ID " + e.id);
        }
        return e != null;
    }

    /** Sends the IDs still waiting to their games (every few minutes, and when the admin page opens a member). */
    public int retry() {
        List<Entry> todo = new ArrayList<Entry>();
        synchronized (this) {
            for (Entry e : map.values()) {
                if (e.state.equals("waiting") && syncs.containsKey(e.game)) {
                    todo.add(e);
                }
            }
        }
        int done = 0;
        for (Entry e : todo) {
            try {
                String got = syncs.get(e.game).send(e.by, e.user, e.id, false);
                synchronized (this) {
                    e.state = "ok";
                    e.note = "";
                    if (got != null && !got.isEmpty()) {
                        e.id = got;
                    }
                    save();
                }
                done++;
            } catch (VistrImport.Bad x) {
                synchronized (this) {
                    if (x.status == 409 && !x.existing.isEmpty() && (x.owner.isEmpty() || lc(x.owner).equals(lc(e.user)))) {
                        e.id = x.existing; // they had a Player ID already: they keep it (and their points)
                        e.state = "ok";
                        e.note = "kept the Player ID they already had";
                        done++;
                    } else {
                        e.note = x.getMessage() == null ? x.toString() : x.getMessage();
                    }
                    save();
                }
            } catch (Exception x) {
                synchronized (this) {
                    e.note = x.getMessage() == null ? x.toString() : x.getMessage();
                    save();
                }
            }
        }
        return done;
    }

    /** Asks the game what it has for this member (a game that keeps its own players) and keeps it here. */
    public Entry lookUp(String actor, String user, String gameKey) {
        Sync s = syncs.get(gameKey);
        Entry had = entry(user, gameKey);
        if (s == null || had != null && !had.state.equals("ok")) {
            return had;
        }
        try {
            String got = s.peek(actor, user);
            if (got != null && !got.isEmpty() && (had == null || !got.equals(had.id))) {
                return adopt(user, gameKey, got, had == null ? "the game" : had.by, had != null && had.paused, "found in the game");
            }
        } catch (Exception x) {
            // not reachable: what is here stays
        }
        return had;
    }

    private synchronized Entry adopt(String user, String gameKey, String id, String by, boolean paused, String note) {
        Entry e = new Entry();
        e.user = user;
        e.game = gameKey;
        e.id = id;
        e.state = "ok";
        e.note = note;
        e.at = clock.getAsLong();
        e.by = by;
        e.paused = paused;
        map.put(lc(user) + "\t" + gameKey, e);
        save();
        return e;
    }

    /**
     * The game's numbers for the members it knows replace what is here (the game is the truth for its own players).
     * Returns the game's whole roster (see Sync.roster), or null when the game cannot be reached.
     */
    public java.util.Map<String, java.util.Map<String, Integer>> playsPerDay(String actor, String gameKey, String from, String to) throws Exception {
        Sync s = syncs.get(gameKey);
        return s == null ? null : s.report(actor, from, to);
    }

    /** The same with each game's details, or null when the game gives none (the caller then uses playsPerDay). */
    public Detail playsDetail(String actor, String gameKey, String from, String to) throws Exception {
        Sync s = syncs.get(gameKey);
        return s == null ? null : s.reportDetail(actor, from, to);
    }

    /** Does this game keep a record of games per day (the performance report)? */
    public boolean hasDailyRecord(String gameKey) {
        return syncs.get(gameKey) != null;
    }

    public List<String[]> refreshFromGame(String actor, String gameKey) {
        Sync s = syncs.get(gameKey);
        if (s == null) {
            return null;
        }
        List<String[]> l;
        try {
            l = s.roster(actor);
        } catch (Exception x) {
            return null;
        }
        if (l == null) {
            return null;
        }
        for (String[] r : l) {
            if (r[3].isEmpty()) {
                continue;
            }
            Entry had = entry(r[3], gameKey);
            boolean p = "1".equals(r[4]);
            if (had == null || had.state.equals("ok") && (!had.id.equals(r[0]) || had.paused != p)) {
                adopt(had == null ? r[3] : had.user, gameKey, r[0], had == null ? "the game" : had.by, p, had == null ? "found in the game" : had.note);
            }
        }
        return l;
    }

    /** Does this game keep its own players (on its own server)? */
    public boolean keepsOwnPlayers(String gameKey) {
        return syncs.containsKey(gameKey);
    }

    /** Every member with an ID in a game. */
    public synchronized List<Entry> players(String gameKey) {
        List<Entry> out = new ArrayList<Entry>();
        for (Entry e : map.values()) {
            if (e.game.equals(gameKey)) {
                out.add(e);
            }
        }
        return out;
    }

    public synchronized boolean paused(String user, String gameKey) {
        Entry e = map.get(lc(user) + "\t" + gameKey);
        return e != null && e.paused;
    }

    /** Pauses a member in a game (they cannot play it) or lets them play again; the game is told first. */
    public void setPaused(String actor, String user, String gameKey, boolean on) throws Refused {
        Entry e = entry(user, gameKey);
        if (e == null) {
            throw new Refused(404, user + " has no ID in this game");
        }
        Sync s = syncs.get(gameKey);
        if (s != null && e.state.equals("ok")) {
            try {
                s.pause(actor, e.id, on);
            } catch (Exception x) {
                throw new Refused(503, "the game could not be told (" + x.getMessage() + ") - nothing was changed, try again");
            }
        }
        synchronized (this) {
            e.paused = on;
            save();
        }
        System.out.println("[gameids] " + actor + (on ? " paused " : " let ") + user + (on ? " in " : " play again in ") + gameKey);
    }

    /** Takes a member out of a game: a game that keeps its own players deletes the player (their scores go). */
    public void drop(String actor, String user, String gameKey) throws Refused {
        Entry e = entry(user, gameKey);
        if (e == null) {
            return;
        }
        Sync s = syncs.get(gameKey);
        if (s != null && e.state.equals("ok")) {
            try {
                s.drop(actor, e.id);
            } catch (Exception x) {
                throw new Refused(503, "the game could not be told (" + x.getMessage() + ") - nothing was changed, try again");
            }
        }
        remove(actor, user, gameKey);
    }

    /** Pauses or deletes a game player who is not a member (the super-admin): straight in the game. */
    public void dropOrPauseCode(String actor, String gameKey, String code, boolean pause, boolean on) throws Refused {
        Sync s = syncs.get(gameKey);
        if (s == null) {
            throw new Refused(400, "this game keeps no players of its own");
        }
        try {
            if (pause) {
                s.pause(actor, code, on);
            } else {
                s.drop(actor, code);
            }
        } catch (Exception x) {
            throw new Refused(503, "the game could not be told (" + x.getMessage() + ") - try again");
        }
        System.out.println("[gameids] " + actor + (pause ? (on ? " paused" : " let play") : " deleted") + " " + gameKey + " player " + code);
    }

    public void start() {
        Thread t = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(10 * 60000L);
                    retry();
                    for (String g : new ArrayList<String>(syncs.keySet())) {
                        refreshFromGame("system", g);
                    }
                } catch (InterruptedException e) {
                    return;
                } catch (RuntimeException e) {
                    System.out.println("[gameids] " + e);
                }
            }
        }, "game-ids");
        t.setDaemon(true);
        t.start();
    }
}