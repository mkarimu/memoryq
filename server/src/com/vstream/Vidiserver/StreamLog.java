package com.vstream.Vidiserver;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Predicate;

/**
 * Every video stream on Memoryq / Vstream: who, which channel, the title, when it started and ended. Kept for good
 * (it is what loyalty is counted from). The end is found by checking every 20 seconds whether the channel is still
 * live; a stream that drops and comes back within 2 minutes is the same stream.
 * File (stream-log.txt), one line per stream:
 *   id, user, channel, title, start, end (0 = live now), lastSeen, videoId (kept as a video, 0 = not), removed (1/0), "end"
 */
public final class StreamLog {

    /** A stream that drops and comes back within this time is the same stream. */
    static final long RECONNECT_MS = 120000L;
    /** A channel that has not been seen live for this long has ended. */
    static final long GONE_MS = 60000L;

    public static final class Session {
        public long id;
        public String user = "";
        public String channel = "";
        public String title = "";
        public long start;
        public long end;
        public long lastSeen;
        public long videoId;
        public boolean removed;

        public boolean live() {
            return end == 0;
        }

        /** How long it went (until now while it is live). */
        public long durationMs(long now) {
            return Math.max(0, (end == 0 ? Math.max(now, lastSeen) : end) - start);
        }

        Session copy() {
            Session s = new Session();
            s.id = id;
            s.user = user;
            s.channel = channel;
            s.title = title;
            s.start = start;
            s.end = end;
            s.lastSeen = lastSeen;
            s.videoId = videoId;
            s.removed = removed;
            return s;
        }
    }

    /** One streamer's totals and loyalty. */
    public static final class Totals {
        public String user = "";
        public int streams;
        public long totalMs;
        public long longestMs;
        public long firstStart;
        public long lastStart;
        public boolean liveNow;
        public long points;
        public String level = "";
        public String nextLevel = "";
        public long pointsToNext;

        public long averageMs() {
            return streams == 0 ? 0 : totalMs / streams;
        }
    }

    /**
     * Loyalty: a point for every minute streamed, and 10 for every stream of at least 5 minutes. Removed streams do
     * not count. Levels by points.
     */
    static final String[] LEVELS = {"New", "Bronze", "Silver", "Gold", "Platinum"};
    static final long[] LEVEL_POINTS = {0, 100, 500, 2000, 6000};

    private final Path file;
    private final LongSupplier clock;
    private final Map<Long, Session> sessions = new LinkedHashMap<Long, Session>();
    private long nextId = 1;
    private volatile Consumer<Session> onNew;

    public StreamLog(Path file, LongSupplier clock) {
        this.file = file;
        this.clock = clock;
    }

    /** Called (on the caller's thread) when a new stream starts - not for reconnects. */
    public void setOnNew(Consumer<Session> listener) {
        this.onNew = listener;
    }

    public synchronized void init() {
        sessions.clear();
        nextId = 1;
        if (file == null || !Files.isRegularFile(file)) {
            return;
        }
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String[] f = line.split("\t", -1);
                if (f.length < 9 || !f[0].matches("\\d+")) {
                    continue;
                }
                try {
                    Session s = new Session();
                    s.id = Long.parseLong(f[0]);
                    s.user = f[1];
                    s.channel = f[2];
                    s.title = f[3];
                    s.start = Long.parseLong(f[4]);
                    s.end = Long.parseLong(f[5]);
                    s.lastSeen = Long.parseLong(f[6]);
                    s.videoId = Long.parseLong(f[7]);
                    s.removed = "1".equals(f[8]);
                    sessions.put(s.id, s);
                    nextId = Math.max(nextId, s.id + 1);
                } catch (RuntimeException e) {
                    // a damaged line
                }
            }
        } catch (IOException e) {
            System.out.println("[streams] could not read " + file + ": " + e.getMessage());
        }
    }

    private static String clean(String s) {
        return s == null ? "" : s.replaceAll("[\\t\\r\\n]+", " ").trim();
    }

    /** A stream started (or came back) on a channel. Returns the stream. */
    public Session started(String channel, String user, String title) {
        Session fresh = null;
        Session result;
        synchronized (this) {
            long now = clock.getAsLong();
            String c = clean(channel), u = clean(user), t = clean(title);
            Session last = null;
            for (Session s : sessions.values()) {
                if (s.channel.equals(c) && (last == null || s.start >= last.start)) {
                    last = s;
                }
            }
            if (last != null && last.user.equalsIgnoreCase(u) && !last.removed
                    && (last.end == 0 || now - last.end < RECONNECT_MS)) {
                last.end = 0;
                last.lastSeen = now;
                if (!t.isEmpty()) {
                    last.title = t;
                }
                save();
                return last.copy();
            }
            if (last != null && last.end == 0) {
                last.end = Math.max(last.start, now); // the channel changed hands
            }
            Session s = new Session();
            s.id = nextId++;
            s.user = u;
            s.channel = c;
            s.title = t;
            s.start = now;
            s.lastSeen = now;
            sessions.put(s.id, s);
            save();
            fresh = s.copy();
            result = fresh;
        }
        Consumer<Session> l = onNew;
        if (l != null) {
            try {
                l.accept(fresh);
            } catch (RuntimeException e) {
                System.out.println("[streams] new-stream listener: " + e);
            }
        }
        return result;
    }

    /** Checks the streams that are on: still live (seen now) or gone (ended when last seen). */
    public void tick(Predicate<String> isLive) {
        List<Session> open = new ArrayList<Session>();
        synchronized (this) {
            for (Session s : sessions.values()) {
                if (s.end == 0) {
                    open.add(s.copy());
                }
            }
        }
        if (open.isEmpty()) {
            return;
        }
        Map<String, Boolean> live = new LinkedHashMap<String, Boolean>();
        for (Session s : open) {
            if (!live.containsKey(s.channel)) {
                boolean on;
                try {
                    on = isLive.test(s.channel);
                } catch (RuntimeException e) {
                    on = true; // cannot tell: do not end it
                }
                live.put(s.channel, on);
            }
        }
        synchronized (this) {
            long now = clock.getAsLong();
            boolean changed = false;
            for (Session o : open) {
                Session s = sessions.get(o.id);
                if (s == null || s.end != 0) {
                    continue;
                }
                if (Boolean.TRUE.equals(live.get(s.channel))) {
                    s.lastSeen = now;
                    changed = true;
                } else if (now - s.lastSeen >= GONE_MS) {
                    s.end = Math.max(s.start, s.lastSeen);
                    changed = true;
                }
            }
            if (changed) {
                save();
            }
        }
    }

    public synchronized Session get(long id) {
        Session s = sessions.get(id);
        return s == null ? null : s.copy();
    }

    /** All streams, newest first (removed ones too when wanted). */
    public synchronized List<Session> list(String user, boolean withRemoved, int max) {
        List<Session> out = new ArrayList<Session>();
        List<Session> all = new ArrayList<Session>(sessions.values());
        for (int i = all.size() - 1; i >= 0 && out.size() < max; i--) {
            Session s = all.get(i);
            if ((user == null || s.user.equalsIgnoreCase(user)) && (withRemoved || !s.removed)) {
                out.add(s.copy());
            }
        }
        return out;
    }

    /** The streams live right now (not removed), newest first. */
    public synchronized List<Session> liveNow() {
        List<Session> out = new ArrayList<Session>();
        List<Session> all = new ArrayList<Session>(sessions.values());
        for (int i = all.size() - 1; i >= 0; i--) {
            Session s = all.get(i);
            if (s.end == 0 && !s.removed && !s.user.isEmpty()) {
                out.add(s.copy());
            }
        }
        return out;
    }

    /** The stream that was on this channel at that moment, or null. */
    public synchronized Session at(String channel, long when) {
        Session best = null;
        for (Session s : sessions.values()) {
            if (s.channel.equals(channel) && s.start <= when + 90000L && (s.end == 0 || s.end >= when - 90000L)
                    && (best == null || s.start > best.start)) {
                best = s;
            }
        }
        return best == null ? null : best.copy();
    }

    public synchronized void setVideo(long id, long videoId) {
        Session s = sessions.get(id);
        if (s != null) {
            s.videoId = videoId;
            save();
        }
    }

    public synchronized void setRemoved(long id, boolean removed) {
        Session s = sessions.get(id);
        if (s != null) {
            s.removed = removed;
            save();
        }
    }

    /** An anonymous tester registered: their streams move to the new account. */
    public synchronized int rename(String from, String to) {
        int n = 0;
        for (Session s : sessions.values()) {
            if (s.user.equalsIgnoreCase(from)) {
                s.user = clean(to);
                n++;
            }
        }
        if (n > 0) {
            save();
        }
        return n;
    }

    // ---- Totals and loyalty ----

    public synchronized Totals totals(String user) {
        long now = clock.getAsLong();
        Totals t = new Totals();
        t.user = user;
        for (Session s : sessions.values()) {
            if (!s.user.equalsIgnoreCase(user) || s.removed) {
                continue;
            }
            add(t, s, now);
        }
        level(t);
        return t;
    }

    /** Everyone who has streamed, most points first. */
    public synchronized List<Totals> allTotals() {
        long now = clock.getAsLong();
        Map<String, Totals> by = new LinkedHashMap<String, Totals>();
        for (Session s : sessions.values()) {
            if (s.user.isEmpty() || s.removed) {
                continue;
            }
            String k = s.user.toLowerCase(java.util.Locale.ROOT);
            Totals t = by.get(k);
            if (t == null) {
                t = new Totals();
                t.user = s.user;
                by.put(k, t);
            }
            add(t, s, now);
        }
        List<Totals> out = new ArrayList<Totals>(by.values());
        for (Totals t : out) {
            level(t);
        }
        out.sort((a, b) -> a.points != b.points ? Long.compare(b.points, a.points) : Long.compare(b.lastStart, a.lastStart));
        return out;
    }

    private static void add(Totals t, Session s, long now) {
        long d = s.durationMs(now);
        t.streams++;
        t.totalMs += d;
        t.longestMs = Math.max(t.longestMs, d);
        t.firstStart = t.firstStart == 0 ? s.start : Math.min(t.firstStart, s.start);
        t.lastStart = Math.max(t.lastStart, s.start);
        t.liveNow |= s.end == 0;
        t.points += d / 60000L + (d >= 5L * 60000L ? 10 : 0);
    }

    static void level(Totals t) {
        int i = 0;
        while (i + 1 < LEVELS.length && t.points >= LEVEL_POINTS[i + 1]) {
            i++;
        }
        t.level = LEVELS[i];
        t.nextLevel = i + 1 < LEVELS.length ? LEVELS[i + 1] : "";
        t.pointsToNext = i + 1 < LEVELS.length ? LEVEL_POINTS[i + 1] - t.points : 0;
    }

    private void save() {
        if (file == null) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (Session s : sessions.values()) {
            sb.append(s.id).append('\t').append(s.user).append('\t').append(s.channel).append('\t').append(s.title).append('\t')
                    .append(s.start).append('\t').append(s.end).append('\t').append(s.lastSeen).append('\t').append(s.videoId)
                    .append('\t').append(s.removed ? "1" : "0").append("\tend\n");
        }
        try {
            Path part = file.resolveSibling(file.getFileName() + ".part");
            Files.write(part, sb.toString().getBytes(StandardCharsets.UTF_8));
            Files.move(part, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            System.out.println("[streams] could not save the stream log: " + e.getMessage());
        }
    }
}