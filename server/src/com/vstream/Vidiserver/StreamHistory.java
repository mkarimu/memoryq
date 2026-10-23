package com.vstream.Vidiserver;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;

/**
 * Who went live on which channel, when, and with what title - so a recording (live/CHANNEL-N/DATE.mp4) can be shown
 * as "Title - by user". Channels from the pool change hands, so this is the only place that remembers who it was.
 * File: one line per start of a stream: time, channel, user, title. Kept 40 days (recordings are kept 30).
 */
public final class StreamHistory {

    static final long KEEP_MS = 40L * 86400000L;

    public static final class Entry {
        public long at;
        public String channel = "";
        public String user = "";
        public String title = "";
    }

    private final Path file;
    private final LongSupplier clock;
    private final List<Entry> entries = new ArrayList<Entry>();

    public StreamHistory(Path file, LongSupplier clock) {
        this.file = file;
        this.clock = clock;
    }

    public synchronized void init() {
        entries.clear();
        if (file == null || !Files.isRegularFile(file)) {
            return;
        }
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String[] f = line.split("\t", -1);
                if (f.length < 4 || !f[0].matches("\\d+")) {
                    continue;
                }
                Entry e = new Entry();
                e.at = Long.parseLong(f[0]);
                e.channel = f[1];
                e.user = f[2];
                e.title = f[3];
                entries.add(e);
            }
        } catch (IOException e) {
            System.out.println("[streams] could not read " + file + ": " + e.getMessage());
        }
    }

    private static String clean(String s) {
        return s == null ? "" : s.replaceAll("[\\t\\r\\n]+", " ").trim();
    }

    /** A stream started (or reconnected) on a channel. Reconnects within 2 minutes by the same user are one stream. */
    public synchronized void started(String channel, String user, String title) {
        long now = clock.getAsLong();
        String c = clean(channel), u = clean(user), t = clean(title);
        for (int i = entries.size() - 1; i >= 0; i--) {
            Entry e = entries.get(i);
            if (e.channel.equals(c)) {
                if (e.user.equals(u) && now - e.at < 120000L) {
                    e.title = t.isEmpty() ? e.title : t;
                    save();
                    return;
                }
                break;
            }
        }
        Entry e = new Entry();
        e.at = now;
        e.channel = c;
        e.user = u;
        e.title = t;
        entries.add(e);
        entries.removeIf(x -> now - x.at > KEEP_MS);
        save();
    }

    /** A tester registered: their streams carry the new username. */
    public synchronized void rename(String from, String to) {
        boolean changed = false;
        for (Entry e : entries) {
            if (e.user.equalsIgnoreCase(from)) {
                e.user = clean(to);
                changed = true;
            }
        }
        if (changed) {
            save();
        }
    }

    /** The stream that was on this channel at that moment (the last start before it), or null. */
    public synchronized Entry at(String channel, long when) {
        Entry best = null;
        for (Entry e : entries) {
            if (e.channel.equals(channel) && e.at <= when + 90000L && (best == null || e.at > best.at)) {
                best = e;
            }
        }
        if (best == null) {
            return null;
        }
        Entry copy = new Entry();
        copy.at = best.at;
        copy.channel = best.channel;
        copy.user = best.user;
        copy.title = best.title;
        return copy;
    }

    private void save() {
        if (file == null) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (Entry e : entries) {
            sb.append(e.at).append('\t').append(e.channel).append('\t').append(e.user).append('\t').append(e.title).append('\n');
        }
        try {
            Path part = file.resolveSibling(file.getFileName() + ".part");
            Files.write(part, sb.toString().getBytes(StandardCharsets.UTF_8));
            Files.move(part, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            System.out.println("[streams] could not save: " + e.getMessage());
        }
    }
}