package com.vstream.Vidiserver;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.TreeMap;
import java.util.function.LongSupplier;

/**
 * Daily numbers for every station (UTC days): listening time, the most listeners at once, different listeners,
 * songs played, likes, requests, "get this song" taps and minutes with a live DJ. Kept for 400 days in
 * radio-stats.txt. Listeners are told apart by a scrambled form of their address that changes every day,
 * so no addresses are stored.
 */
public final class RadioStats {

    public static final class Day {
        public final String date;
        public long listenSeconds;
        public int peak;
        public int uniques;
        public int plays;
        public int likes;
        public int requests;
        public int taps;
        public long liveSeconds;

        Day(String date) {
            this.date = date;
        }

        Day copy() {
            Day d = new Day(date);
            d.listenSeconds = listenSeconds;
            d.peak = peak;
            d.uniques = uniques;
            d.plays = plays;
            d.likes = likes;
            d.requests = requests;
            d.taps = taps;
            d.liveSeconds = liveSeconds;
            return d;
        }
    }

    private static final int KEEP_DAYS = 400;

    private final Path file;
    private final LongSupplier clock;
    private final Map<Long, TreeMap<String, Day>> days = new HashMap<Long, TreeMap<String, Day>>();
    /** Today's listener fingerprints per station (memory only). */
    private final Map<Long, Set<String>> seenToday = new HashMap<Long, Set<String>>();
    private String seenDate = "";
    private long lastSample = 0;
    private boolean dirty = false;

    public RadioStats(Path file, LongSupplier clock) {
        this.file = file;
        this.clock = clock;
    }

    public static String dateOf(long millis) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd");
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date(millis));
    }

    private Day today(long station) {
        String d = dateOf(clock.getAsLong());
        TreeMap<String, Day> m = days.get(station);
        if (m == null) {
            m = new TreeMap<String, Day>();
            days.put(station, m);
        }
        Day day = m.get(d);
        if (day == null) {
            day = new Day(d);
            m.put(d, day);
            while (m.size() > KEEP_DAYS) {
                m.remove(m.firstKey());
            }
        }
        dirty = true;
        return day;
    }

    /** Called every so often with the number of listeners (and whether a live DJ is on) for each station. */
    public synchronized void sample(Map<Long, Integer> listeners, Set<Long> live) {
        long now = clock.getAsLong();
        long seconds = lastSample == 0 ? 0 : Math.min(300, Math.max(0, (now - lastSample) / 1000));
        lastSample = now;
        for (Map.Entry<Long, Integer> e : listeners.entrySet()) {
            int n = e.getValue();
            if (n <= 0 && !live.contains(e.getKey())) {
                continue;
            }
            Day d = today(e.getKey());
            d.listenSeconds += n * seconds;
            d.peak = Math.max(d.peak, n);
            if (live.contains(e.getKey())) {
                d.liveSeconds += seconds;
            }
        }
    }

    /** Someone started listening; counts once per address per station per day. */
    public synchronized void listener(long station, String address) {
        String date = dateOf(clock.getAsLong());
        if (!date.equals(seenDate)) {
            seenToday.clear();
            seenDate = date;
        }
        Set<String> seen = seenToday.get(station);
        if (seen == null) {
            seen = new HashSet<String>();
            seenToday.put(station, seen);
        }
        if (seen.add(fingerprint(date, address))) {
            today(station).uniques++;
        }
    }

    public synchronized void played(long station) {
        today(station).plays++;
    }

    public synchronized void liked(long station) {
        today(station).likes++;
    }

    public synchronized void requested(long station) {
        today(station).requests++;
    }

    public synchronized void tapped(long station) {
        today(station).taps++;
    }

    /** The last n days for a station, oldest first, with empty days filled in. */
    public synchronized List<Day> lastDays(long station, int n) {
        List<Day> out = new ArrayList<Day>();
        TreeMap<String, Day> m = days.get(station);
        long now = clock.getAsLong();
        for (int i = n - 1; i >= 0; i--) {
            String d = dateOf(now - i * 86400000L);
            Day day = m == null ? null : m.get(d);
            out.add(day == null ? new Day(d) : day.copy());
        }
        return out;
    }

    public synchronized void forget(long station) {
        if (days.remove(station) != null) {
            dirty = true;
        }
    }

    private static String fingerprint(String date, String address) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] h = md.digest(("memoryq-radio|" + date + "|" + address).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                sb.append(String.format("%02x", h[i]));
            }
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(address.hashCode());
        }
    }

    // ---- Storage ----

    public synchronized void init() throws IOException {
        days.clear();
        if (!Files.exists(file)) {
            return;
        }
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            String[] f = line.split("\t", -1);
            try {
                if ("D".equals(f[0]) && f.length >= 11) {
                    long station = Long.parseLong(f[1]);
                    Day d = new Day(f[2]);
                    d.listenSeconds = Long.parseLong(f[3]);
                    d.peak = Integer.parseInt(f[4]);
                    d.uniques = Integer.parseInt(f[5]);
                    d.plays = Integer.parseInt(f[6]);
                    d.likes = Integer.parseInt(f[7]);
                    d.requests = Integer.parseInt(f[8]);
                    d.taps = Integer.parseInt(f[9]);
                    d.liveSeconds = Long.parseLong(f[10]);
                    TreeMap<String, Day> m = days.get(station);
                    if (m == null) {
                        m = new TreeMap<String, Day>();
                        days.put(station, m);
                    }
                    m.put(d.date, d);
                }
            } catch (RuntimeException e) {
                // skip a damaged line
            }
        }
    }

    public synchronized void flushIfDirty() {
        if (!dirty) {
            return;
        }
        StringBuilder sb = new StringBuilder("# Memoryq radio statistics per station per day (UTC): station, date, "
                + "listening seconds, peak, different listeners, plays, likes, requests, taps, live seconds\n");
        for (Map.Entry<Long, TreeMap<String, Day>> e : days.entrySet()) {
            for (Day d : e.getValue().values()) {
                sb.append("D\t").append(e.getKey()).append('\t').append(d.date).append('\t').append(d.listenSeconds)
                        .append('\t').append(d.peak).append('\t').append(d.uniques).append('\t').append(d.plays)
                        .append('\t').append(d.likes).append('\t').append(d.requests).append('\t').append(d.taps)
                        .append('\t').append(d.liveSeconds).append('\n');
            }
        }
        try {
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.write(tmp, sb.toString().getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            dirty = false;
        } catch (IOException e) {
            System.out.println("[radio] could not save statistics: " + e.getMessage());
        }
    }
}