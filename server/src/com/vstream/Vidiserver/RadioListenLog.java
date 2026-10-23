package com.vstream.Vidiserver;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * The detailed listening record behind the station statistics (Studio - Statistics):
 * every listening session (when, how long, from where, with which player, how much data), every song played
 * (with how many people heard it), and station events (live DJs, starts and stops, full station) for the logs.
 * Kept as month files in radio-logs/: listens-2026-10.tsv, plays-2026-10.tsv, events-2026-10.tsv (13 months).
 */
public final class RadioListenLog {

    private final Path dir;
    private final LongSupplier clock;
    private final GeoIp geo;

    /** A listener connected right now. */
    public static final class Open {
        public final long id;
        public final long station;
        public final long start;
        public final String ip;
        public final String cc;
        public final String player;
        public final String platform;
        public final String agent;
        public final AtomicLong bytes = new AtomicLong();

        Open(long id, long station, long start, String ip, String cc, String player, String platform, String agent) {
            this.id = id;
            this.station = station;
            this.start = start;
            this.ip = ip;
            this.cc = cc;
            this.player = player;
            this.platform = platform;
            this.agent = agent;
        }
    }

    /** A listening session (finished, or still going with end = now). */
    public static final class Session {
        long station;
        long start;
        long end;
        String ip;
        String cc;
        String player;
        String platform;
        long bytes;
        String agent;
    }

    public static final class Play {
        long station;
        long time;
        long trackId;
        String title;
        String artist;
        long durationMs;
        int listeners;
        String source;
    }

    public static final class Event {
        long station;
        long time;
        String kind;
        String text;
    }

    private final Map<Long, Open> open = new ConcurrentHashMap<Long, Open>();
    private final AtomicLong nextId = new AtomicLong(1);

    private static final class Cached {
        long size = -1;
        long modified = -1;
        List<?> rows = Collections.emptyList();
    }

    private final Map<String, Cached> cache = new HashMap<String, Cached>();

    public RadioListenLog(Path dir, LongSupplier clock, GeoIp geo) {
        this.dir = dir;
        this.clock = clock;
        this.geo = geo;
    }

    public void init() throws IOException {
        Files.createDirectories(dir);
        // keep 13 months
        String oldest = monthKey(clock.getAsLong() - 400L * 86400000);
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*.tsv")) {
            for (Path p : ds) {
                String n = p.getFileName().toString();
                int dash = n.indexOf('-');
                if (dash > 0 && n.length() >= dash + 8 && n.substring(dash + 1, dash + 8).compareTo(oldest) < 0) {
                    Files.deleteIfExists(p);
                }
            }
        }
    }

    public GeoIp geo() {
        return geo;
    }

    // ---- Recording ----

    /** Someone started listening; returns the session to pass to {@link #close}. */
    public Open open(long station, String ip, String userAgent) {
        String[] pp = player(userAgent);
        String cc = geo == null ? "" : geo.country(ip);
        Open o = new Open(nextId.getAndIncrement(), station, clock.getAsLong(), ip == null ? "" : ip, cc, pp[0], pp[1],
                clip(userAgent, 200));
        open.put(o.id, o);
        return o;
    }

    /** The listener left. Very short visits (a player checking the link) are not counted. */
    public void close(Open o) {
        if (o == null || open.remove(o.id) == null) {
            return;
        }
        long end = clock.getAsLong();
        if (end - o.start < 3000 && o.bytes.get() < 32768) {
            return;
        }
        append("listens", o.start, o.station + "\t" + o.start + "\t" + end + "\t" + o.ip + "\t" + o.cc + "\t" + o.player + "\t"
                + o.platform + "\t" + o.bytes.get() + "\t" + o.agent + "\n");
    }

    /** Writes everyone still listening (the server is stopping). */
    public void closeAll() {
        for (Open o : new ArrayList<Open>(open.values())) {
            close(o);
        }
    }

    public void played(long station, long trackId, String title, String artist, long durationMs, int listeners, String source) {
        long now = clock.getAsLong();
        append("plays", now, station + "\t" + now + "\t" + trackId + "\t" + clip(title, 120) + "\t" + clip(artist, 120) + "\t"
                + durationMs + "\t" + listeners + "\t" + clip(source, 20) + "\n");
    }

    public void event(long station, String kind, String text) {
        long now = clock.getAsLong();
        append("events", now, station + "\t" + now + "\t" + clip(kind, 20) + "\t" + clip(text, 300) + "\n");
    }

    public List<Open> current(long station) {
        List<Open> out = new ArrayList<Open>();
        for (Open o : open.values()) {
            if (o.station == station) {
                out.add(o);
            }
        }
        out.sort((a, b) -> Long.compare(a.start, b.start));
        return out;
    }

    private synchronized void append(String kind, long time, String line) {
        try {
            Files.write(dir.resolve(kind + "-" + monthKey(time) + ".tsv"), line.getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE);
        } catch (IOException e) {
            System.out.println("[radio-log] could not write: " + e.getMessage());
        }
    }

    private static String clip(String s, int max) {
        if (s == null) {
            return "";
        }
        String t = s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').trim();
        return t.length() > max ? t.substring(0, max) : t;
    }

    static String monthKey(long ms) {
        ZonedDateTime z = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC);
        return String.format(Locale.ROOT, "%04d-%02d", z.getYear(), z.getMonthValue());
    }

    /** "VLC", "Windows" from a User-Agent. */
    static String[] player(String ua) {
        String u = ua == null ? "" : ua;
        String l = u.toLowerCase(Locale.ROOT);
        String platform = l.contains("android") ? "Android" : (l.contains("iphone") || l.contains("ipad") || l.contains("ios") ? "iPhone / iPad"
                : (l.contains("windows") ? "Windows" : (l.contains("mac os") || l.contains("macintosh") || l.contains("darwin") ? "Mac"
                : (l.contains("cros") ? "Chromebook" : (l.contains("linux") ? "Linux" : (l.contains("roku") ? "Roku" : ""))))));
        String player;
        if (l.contains("memoryq")) {
            player = "Memoryq app";
        } else if (l.contains("exoplayer") || l.contains("okhttp") || l.contains("dalvik")) {
            player = "Memoryq app";
        } else if (l.contains("vlc") || l.contains("libvlc")) {
            player = "VLC";
        } else if (l.contains("winamp")) {
            player = "Winamp";
        } else if (l.contains("foobar")) {
            player = "foobar2000";
        } else if (l.contains("nsplayer") || l.contains("windows-media-player") || l.contains("wmplayer")) {
            player = "Windows Media Player";
        } else if (l.contains("itunes") || l.contains("applecoremedia") || l.contains("appletv")) {
            player = "Apple Music / iTunes";
        } else if (l.contains("tunein")) {
            player = "TuneIn";
        } else if (l.contains("sonos")) {
            player = "Sonos";
        } else if (l.contains("alexa") || l.contains("echo")) {
            player = "Alexa";
        } else if (l.contains("lavf") || l.contains("ffmpeg")) {
            player = "FFmpeg";
        } else if (l.contains("mpv") || l.contains("mplayer")) {
            player = "mpv / MPlayer";
        } else if (l.contains("edg/")) {
            player = "Edge";
        } else if (l.contains("opr/") || l.contains("opera")) {
            player = "Opera";
        } else if (l.contains("samsungbrowser")) {
            player = "Samsung Internet";
        } else if (l.contains("firefox")) {
            player = "Firefox";
        } else if (l.contains("chrome") || l.contains("crios")) {
            player = "Chrome";
        } else if (l.contains("safari")) {
            player = "Safari";
        } else if (u.trim().isEmpty()) {
            player = "Unknown";
        } else {
            String first = u.trim().split("[ /;(]")[0];
            player = first.isEmpty() ? "Other" : clip(first, 30);
        }
        return new String[] {player, platform.isEmpty() ? "Other" : platform};
    }

    // ---- Reading the month files ----

    @SuppressWarnings("unchecked")
    private synchronized <T> List<T> rows(String kind, String month) {
        Path p = dir.resolve(kind + "-" + month + ".tsv");
        String key = p.toString();
        Cached c = cache.get(key);
        try {
            if (!Files.isRegularFile(p)) {
                return Collections.emptyList();
            }
            long size = Files.size(p), mod = Files.getLastModifiedTime(p).toMillis();
            if (c != null && c.size == size && c.modified == mod) {
                return (List<T>) c.rows;
            }
            List<Object> out = new ArrayList<Object>();
            for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
                String[] f = line.split("\t", -1);
                try {
                    if ("listens".equals(kind) && f.length >= 9) {
                        Session s = new Session();
                        s.station = Long.parseLong(f[0]);
                        s.start = Long.parseLong(f[1]);
                        s.end = Long.parseLong(f[2]);
                        s.ip = f[3];
                        s.cc = f[4];
                        s.player = f[5];
                        s.platform = f[6];
                        s.bytes = Long.parseLong(f[7]);
                        s.agent = f[8];
                        out.add(s);
                    } else if ("plays".equals(kind) && f.length >= 8) {
                        Play pl = new Play();
                        pl.station = Long.parseLong(f[0]);
                        pl.time = Long.parseLong(f[1]);
                        pl.trackId = Long.parseLong(f[2]);
                        pl.title = f[3];
                        pl.artist = f[4];
                        pl.durationMs = Long.parseLong(f[5]);
                        pl.listeners = Integer.parseInt(f[6]);
                        pl.source = f[7];
                        out.add(pl);
                    } else if ("events".equals(kind) && f.length >= 4) {
                        Event e = new Event();
                        e.station = Long.parseLong(f[0]);
                        e.time = Long.parseLong(f[1]);
                        e.kind = f[2];
                        e.text = f[3];
                        out.add(e);
                    }
                } catch (RuntimeException e) {
                    // a damaged line
                }
            }
            c = new Cached();
            c.size = size;
            c.modified = mod;
            c.rows = out;
            cache.put(key, c);
            if (cache.size() > 60) {
                cache.clear();
                cache.put(key, c);
            }
            return (List<T>) out;
        } catch (IOException e) {
            return Collections.emptyList();
        }
    }

    private static List<String> months(long from, long to) {
        List<String> out = new ArrayList<String>();
        LocalDate d = Instant.ofEpochMilli(from).atZone(ZoneOffset.UTC).toLocalDate().withDayOfMonth(1);
        LocalDate end = Instant.ofEpochMilli(to).atZone(ZoneOffset.UTC).toLocalDate();
        while (!d.isAfter(end) && out.size() < 40) {
            out.add(String.format(Locale.ROOT, "%04d-%02d", d.getYear(), d.getMonthValue()));
            d = d.plusMonths(1);
        }
        return out;
    }

    /** Sessions of a station that overlap [from, to); people listening now are included (ending now). */
    List<Session> sessions(long station, long from, long to) {
        List<Session> out = new ArrayList<Session>();
        // a session is filed under the month it started; look one month back for long ones
        for (String m : months(from - 31L * 86400000, to)) {
            for (Object o : this.<Object>rows("listens", m)) {
                Session s = (Session) o;
                if (s.station == station && s.start < to && s.end > from) {
                    out.add(s);
                }
            }
        }
        long now = clock.getAsLong();
        for (Open o : current(station)) {
            if (o.start < to && now > from) {
                Session s = new Session();
                s.station = station;
                s.start = o.start;
                s.end = now;
                s.ip = o.ip;
                s.cc = o.cc;
                s.player = o.player;
                s.platform = o.platform;
                s.bytes = o.bytes.get();
                s.agent = o.agent;
                out.add(s);
            }
        }
        return out;
    }

    List<Play> plays(long station, long from, long to) {
        List<Play> out = new ArrayList<Play>();
        for (String m : months(from, to)) {
            for (Object o : this.<Object>rows("plays", m)) {
                Play p = (Play) o;
                if (p.station == station && p.time >= from && p.time <= to) {
                    out.add(p);
                }
            }
        }
        return out;
    }

    List<Event> events(long station, long from, long to) {
        List<Event> out = new ArrayList<Event>();
        for (String m : months(from, to)) {
            for (Object o : this.<Object>rows("events", m)) {
                Event e = (Event) o;
                if (e.station == station && e.time >= from && e.time <= to) {
                    out.add(e);
                }
            }
        }
        return out;
    }

    /** When the record for a station starts (0 = nothing yet). */
    long since(long station) {
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*.tsv")) {
            String first = null;
            for (Path p : ds) {
                String n = p.getFileName().toString();
                int dash = n.indexOf('-');
                if (dash > 0 && n.length() >= dash + 8) {
                    String m = n.substring(dash + 1, dash + 8);
                    if (first == null || m.compareTo(first) < 0) {
                        first = m;
                    }
                }
            }
            if (first == null) {
                return 0;
            }
            long best = Long.MAX_VALUE;
            for (String kind : new String[] {"listens", "plays"}) {
                for (Object o : this.<Object>rows(kind, first)) {
                    long st = o instanceof Session ? ((Session) o).station : ((Play) o).station;
                    long t = o instanceof Session ? ((Session) o).start : ((Play) o).time;
                    if (st == station && t < best) {
                        best = t;
                    }
                }
            }
            return best == Long.MAX_VALUE ? 0 : best;
        } catch (IOException e) {
            return 0;
        }
    }

    // ---- The report ----

    /** Hides the end of an address for station owners (the admin sees all of it). */
    static String mask(String ip, boolean full) {
        if (full || ip == null) {
            return ip == null ? "" : ip;
        }
        if (ip.indexOf(':') >= 0) {
            String[] g = ip.split(":");
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < Math.min(3, g.length); i++) {
                sb.append(g[i]).append(':');
            }
            return sb.append(":x").toString();
        }
        int dot = ip.lastIndexOf('.');
        return dot > 0 ? ip.substring(0, dot) + ".x" : ip;
    }

    private static final class Bucket {
        long start;
        long end;
        int peak;
        double integral;
        int sessions;
        Set<String> ips = new HashSet<String>();
        long sessionMs;
        long bytes;
        int plays;
    }

    /** Everything the Statistics pages show for one station and period, as JSON. */
    public String reportJson(RadioStore.Station st, long from, long to, boolean fullIp) {
        long now = clock.getAsLong();
        to = Math.min(to, now);
        if (to <= from) {
            from = to - 3600000;
        }
        ZoneId zone;
        try {
            zone = ZoneId.of(st.timezone);
        } catch (RuntimeException e) {
            zone = ZoneOffset.UTC;
        }
        List<Session> ss = sessions(st.id, from, to);
        List<Play> ps = plays(st.id, from, to);
        ps.sort((a, b) -> Long.compare(a.time, b.time));

        // buckets: hours for up to 3 days, otherwise days (in the station's time zone)
        boolean hourly = to - from <= 3L * 86400000 + 1;
        List<Bucket> buckets = new ArrayList<Bucket>();
        ZonedDateTime z = Instant.ofEpochMilli(from).atZone(zone);
        z = hourly ? z.withMinute(0).withSecond(0).withNano(0) : z.toLocalDate().atStartOfDay(zone);
        while (z.toInstant().toEpochMilli() < to && buckets.size() < 800) {
            Bucket b = new Bucket();
            b.start = z.toInstant().toEpochMilli();
            z = hourly ? z.plusHours(1) : z.plusDays(1);
            b.end = z.toInstant().toEpochMilli();
            buckets.add(b);
        }

        // how many were listening at each moment
        List<long[]> edges = new ArrayList<long[]>();
        for (Session s : ss) {
            edges.add(new long[] {Math.max(s.start, from), 1});
            edges.add(new long[] {Math.min(s.end, to), -1});
        }
        edges.sort((a, b) -> a[0] != b[0] ? Long.compare(a[0], b[0]) : Long.compare(a[1], b[1]));
        int level = 0, peak = 0;
        long peakAt = 0;
        double listenMs = 0;
        int bi = 0;
        for (int i = 0; i < edges.size(); i++) {
            long t = edges.get(i)[0];
            level += (int) edges.get(i)[1];
            if (level > peak) {
                peak = level;
                peakAt = t;
            }
            long next = i + 1 < edges.size() ? edges.get(i + 1)[0] : t;
            if (next > t && level > 0) {
                listenMs += (double) level * (next - t);
                while (bi < buckets.size() && buckets.get(bi).end <= t) {
                    bi++;
                }
                for (int k = bi; k < buckets.size() && buckets.get(k).start < next; k++) {
                    Bucket b = buckets.get(k);
                    long a0 = Math.max(t, b.start), a1 = Math.min(next, b.end);
                    if (a1 > a0) {
                        b.integral += (double) level * (a1 - a0);
                        b.peak = Math.max(b.peak, level);
                    }
                }
            }
        }

        // sessions that started in the period
        int started = 0;
        long startedMs = 0, bytes = 0;
        Set<String> ips = new HashSet<String>(), ccs = new HashSet<String>();
        Map<String, long[]> agents = new LinkedHashMap<String, long[]>();
        Map<String, long[]> countries = new HashMap<String, long[]>();
        Map<String, Set<String>> countryIps = new HashMap<String, Set<String>>();
        int[] lengths = new int[7];
        List<Session> list = new ArrayList<Session>();
        for (Session s : ss) {
            long overlap = Math.min(s.end, to) - Math.max(s.start, from);
            String ag = s.player + "\t" + s.platform;
            long[] a = agents.computeIfAbsent(ag, k -> new long[2]);
            a[1] += overlap;
            String cc = s.cc == null ? "" : s.cc;
            long[] c = countries.computeIfAbsent(cc, k -> new long[2]);
            c[1] += overlap;
            if (s.start >= from) {
                started++;
                long len = s.end - s.start;
                startedMs += len;
                bytes += s.bytes;
                ips.add(s.ip);
                ccs.add(cc);
                a[0]++;
                c[0]++;
                countryIps.computeIfAbsent(cc, k -> new HashSet<String>()).add(s.ip);
                lengths[len < 60000 ? 0 : len < 300000 ? 1 : len < 900000 ? 2 : len < 1800000 ? 3 : len < 3600000 ? 4 : len < 7200000 ? 5 : 6]++;
                list.add(s);
                for (Bucket b : buckets) {
                    if (s.start >= b.start && s.start < b.end) {
                        b.sessions++;
                        b.ips.add(s.ip);
                        b.sessionMs += len;
                        b.bytes += s.bytes;
                        break;
                    }
                }
            }
        }
        for (Play p : ps) {
            for (Bucket b : buckets) {
                if (p.time >= b.start && p.time < b.end) {
                    b.plays++;
                    break;
                }
            }
        }
        String peakTrack = "";
        if (peakAt > 0) {
            for (Play p : ps) {
                if (p.time <= peakAt) {
                    peakTrack = (p.artist.isEmpty() ? "" : p.artist + " - ") + p.title;
                }
            }
        }
        Set<String> uniqueTracks = new HashSet<String>();
        long playMs = 0;
        Map<String, long[]> tracks = new LinkedHashMap<String, long[]>(); // plays, listener sum, peak, last
        Map<String, String[]> trackNames = new HashMap<String, String[]>();
        for (Play p : ps) {
            String key = p.trackId > 0 ? "#" + p.trackId : (p.artist + "\t" + p.title).toLowerCase(Locale.ROOT);
            uniqueTracks.add(key);
            playMs += p.durationMs;
            long[] t = tracks.computeIfAbsent(key, k -> new long[4]);
            t[0]++;
            t[1] += p.listeners;
            t[2] = Math.max(t[2], p.listeners);
            t[3] = Math.max(t[3], p.time);
            trackNames.put(key, new String[] {p.title, p.artist});
        }
        double hours = (to - from) / 3600000.0;

        StringBuilder sb = new StringBuilder("{\"from\":").append(from).append(",\"to\":").append(to)
                .append(",\"tz\":").append(q(zone.getId())).append(",\"hourly\":").append(hourly)
                .append(",\"since\":").append(since(st.id))
                .append(",\"geo\":").append(geo != null && geo.ready()).append(",\"credit\":").append(q(GeoIp.CREDIT));
        sb.append(",\"overview\":{")
                .append("\"listenerMinutes\":").append(Math.round(listenMs / 60000))
                .append(",\"tlh\":").append(String.format(Locale.ROOT, "%.2f", listenMs / 3600000))
                .append(",\"avgSessionMin\":").append(String.format(Locale.ROOT, "%.1f", started == 0 ? 0 : startedMs / 60000.0 / started))
                .append(",\"sessions\":").append(started)
                .append(",\"uniqueListeners\":").append(ips.size())
                .append(",\"uniqueCountries\":").append(ccs.size())
                .append(",\"bytes\":").append(bytes)
                .append(",\"bytesPerSession\":").append(started == 0 ? 0 : bytes / started)
                .append(",\"tracks\":").append(ps.size())
                .append(",\"uniqueTracks\":").append(uniqueTracks.size())
                .append(",\"tracksPerHour\":").append(String.format(Locale.ROOT, "%.2f", hours <= 0 ? 0 : ps.size() / hours))
                .append(",\"avgTrackSec\":").append(ps.isEmpty() ? 0 : playMs / 1000 / ps.size())
                .append(",\"peakListeners\":").append(peak)
                .append(",\"peakTime\":").append(peakAt)
                .append(",\"peakTrack\":").append(q(peakTrack))
                .append('}');
        sb.append(",\"chart\":[");
        for (int i = 0; i < buckets.size(); i++) {
            Bucket b = buckets.get(i);
            if (i > 0) {
                sb.append(',');
            }
            long span = Math.max(1, Math.min(b.end, to) - b.start);
            sb.append("{\"t\":").append(b.start).append(",\"peak\":").append(b.peak)
                    .append(",\"avg\":").append(String.format(Locale.ROOT, "%.2f", b.integral / span))
                    .append(",\"sessions\":").append(b.sessions).append(",\"unique\":").append(b.ips.size())
                    .append(",\"tlh\":").append(String.format(Locale.ROOT, "%.2f", b.integral / 3600000))
                    .append(",\"avgMin\":").append(String.format(Locale.ROOT, "%.1f", b.sessions == 0 ? 0 : b.sessionMs / 60000.0 / b.sessions))
                    .append(",\"bytes\":").append(b.bytes).append(",\"plays\":").append(b.plays).append('}');
        }
        sb.append(']');
        String[] lengthNames = {"Under 1 minute", "1 - 5 minutes", "5 - 15 minutes", "15 - 30 minutes", "30 - 60 minutes", "1 - 2 hours", "Over 2 hours"};
        sb.append(",\"lengths\":[");
        for (int i = 0; i < lengths.length; i++) {
            sb.append(i > 0 ? "," : "").append("{\"label\":").append(q(lengthNames[i])).append(",\"n\":").append(lengths[i]).append('}');
        }
        sb.append(']');
        list.sort((a, b) -> Long.compare(b.start, a.start));
        sb.append(",\"sessionsList\":[");
        for (int i = 0; i < Math.min(500, list.size()); i++) {
            Session s = list.get(i);
            sb.append(i > 0 ? "," : "").append("{\"start\":").append(s.start).append(",\"end\":").append(s.end)
                    .append(",\"ip\":").append(q(mask(s.ip, fullIp))).append(",\"cc\":").append(q(s.cc))
                    .append(",\"country\":").append(q(GeoIp.name(s.cc))).append(",\"player\":").append(q(s.player))
                    .append(",\"platform\":").append(q(s.platform)).append(",\"bytes\":").append(s.bytes)
                    .append(",\"agent\":").append(q(s.agent)).append('}');
        }
        sb.append("],\"sessionsTotal\":").append(list.size());
        List<Map.Entry<String, long[]>> ag = new ArrayList<Map.Entry<String, long[]>>(agents.entrySet());
        ag.sort((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0]) != 0 ? Long.compare(b.getValue()[0], a.getValue()[0])
                : Long.compare(b.getValue()[1], a.getValue()[1]));
        sb.append(",\"agents\":[");
        for (int i = 0; i < ag.size(); i++) {
            String[] k = ag.get(i).getKey().split("\t", 2);
            sb.append(i > 0 ? "," : "").append("{\"player\":").append(q(k[0])).append(",\"platform\":").append(q(k.length > 1 ? k[1] : ""))
                    .append(",\"sessions\":").append(ag.get(i).getValue()[0]).append(",\"minutes\":").append(ag.get(i).getValue()[1] / 60000)
                    .append('}');
        }
        sb.append(']');
        List<Map.Entry<String, long[]>> cs = new ArrayList<Map.Entry<String, long[]>>(countries.entrySet());
        cs.sort((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0]) != 0 ? Long.compare(b.getValue()[0], a.getValue()[0])
                : Long.compare(b.getValue()[1], a.getValue()[1]));
        sb.append(",\"countries\":[");
        for (int i = 0; i < cs.size(); i++) {
            String cc = cs.get(i).getKey();
            Set<String> u = countryIps.get(cc);
            sb.append(i > 0 ? "," : "").append("{\"cc\":").append(q(cc)).append(",\"name\":").append(q(GeoIp.name(cc)))
                    .append(",\"sessions\":").append(cs.get(i).getValue()[0]).append(",\"unique\":").append(u == null ? 0 : u.size())
                    .append(",\"minutes\":").append(cs.get(i).getValue()[1] / 60000).append('}');
        }
        sb.append(']');
        List<Map.Entry<String, long[]>> ts = new ArrayList<Map.Entry<String, long[]>>(tracks.entrySet());
        ts.sort((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0]) != 0 ? Long.compare(b.getValue()[0], a.getValue()[0])
                : Long.compare(b.getValue()[1], a.getValue()[1]));
        sb.append(",\"tracksList\":[");
        for (int i = 0; i < Math.min(500, ts.size()); i++) {
            long[] v = ts.get(i).getValue();
            String[] nm = trackNames.get(ts.get(i).getKey());
            sb.append(i > 0 ? "," : "").append("{\"title\":").append(q(nm[0])).append(",\"artist\":").append(q(nm[1]))
                    .append(",\"plays\":").append(v[0]).append(",\"avgListeners\":")
                    .append(String.format(Locale.ROOT, "%.1f", v[0] == 0 ? 0 : v[1] / (double) v[0]))
                    .append(",\"peakListeners\":").append(v[2]).append(",\"last\":").append(v[3]).append('}');
        }
        sb.append(']');
        sb.append(",\"playsList\":[");
        for (int i = ps.size() - 1, n = 0; i >= 0 && n < 1000; i--, n++) {
            Play p = ps.get(i);
            sb.append(n > 0 ? "," : "").append("{\"time\":").append(p.time).append(",\"title\":").append(q(p.title))
                    .append(",\"artist\":").append(q(p.artist)).append(",\"listeners\":").append(p.listeners)
                    .append(",\"source\":").append(q(p.source)).append(",\"durationMs\":").append(p.durationMs).append('}');
        }
        sb.append(']');
        return sb.append('}').toString();
    }

    /** People listening now (Statistics - Listeners). */
    public String listenersJson(long station, boolean fullIp) {
        long now = clock.getAsLong();
        StringBuilder sb = new StringBuilder("{\"now\":").append(now).append(",\"listeners\":[");
        List<Open> list = current(station);
        for (int i = 0; i < list.size(); i++) {
            Open o = list.get(i);
            sb.append(i > 0 ? "," : "").append("{\"start\":").append(o.start).append(",\"ip\":").append(q(mask(o.ip, fullIp)))
                    .append(",\"cc\":").append(q(o.cc)).append(",\"country\":").append(q(GeoIp.name(o.cc)))
                    .append(",\"player\":").append(q(o.player)).append(",\"platform\":").append(q(o.platform))
                    .append(",\"bytes\":").append(o.bytes.get()).append(",\"agent\":").append(q(o.agent)).append('}');
        }
        return sb.append("]}").toString();
    }

    /** Station events and songs, newest first (Statistics - Logs). */
    public String logsJson(long station, long from, long to, String kind) {
        List<Object[]> rows = new ArrayList<Object[]>();
        if (kind == null || kind.isEmpty() || "events".equals(kind)) {
            for (Event e : events(station, from, to)) {
                rows.add(new Object[] {e.time, e.kind, e.text});
            }
        }
        if (kind == null || kind.isEmpty() || "songs".equals(kind)) {
            for (Play p : plays(station, from, to)) {
                rows.add(new Object[] {p.time, "song", (p.artist.isEmpty() ? "" : p.artist + " - ") + p.title + "  (" + p.listeners
                        + " listening" + ("autodj".equals(p.source) ? "" : ", " + p.source) + ")"});
            }
        }
        rows.sort((a, b) -> Long.compare((Long) b[0], (Long) a[0]));
        StringBuilder sb = new StringBuilder("{\"rows\":[");
        for (int i = 0; i < Math.min(1000, rows.size()); i++) {
            Object[] r = rows.get(i);
            sb.append(i > 0 ? "," : "").append("{\"time\":").append(r[0]).append(",\"kind\":").append(q((String) r[1]))
                    .append(",\"text\":").append(q((String) r[2])).append('}');
        }
        return sb.append("],\"total\":").append(rows.size()).append('}').toString();
    }

    /** One of the report tables as CSV (Statistics - Downloads). */
    public String csv(RadioStore.Station st, long from, long to, String kind, boolean fullIp) {
        StringBuilder sb = new StringBuilder();
        if ("sessions".equals(kind)) {
            sb.append("start,end,minutes,address,country,player,platform,bytes,user_agent\n");
            List<Session> ss = sessions(st.id, from, to);
            ss.sort((a, b) -> Long.compare(a.start, b.start));
            for (Session s : ss) {
                if (s.start < from) {
                    continue;
                }
                sb.append(iso(s.start)).append(',').append(iso(s.end)).append(',')
                        .append(String.format(Locale.ROOT, "%.1f", (s.end - s.start) / 60000.0)).append(',').append(c(mask(s.ip, fullIp))).append(',')
                        .append(c(GeoIp.name(s.cc))).append(',').append(c(s.player)).append(',').append(c(s.platform)).append(',')
                        .append(s.bytes).append(',').append(c(s.agent)).append('\n');
            }
        } else if ("plays".equals(kind)) {
            sb.append("time,artist,title,listeners,source,seconds\n");
            List<Play> ps = plays(st.id, from, to);
            ps.sort((a, b) -> Long.compare(a.time, b.time));
            for (Play p : ps) {
                sb.append(iso(p.time)).append(',').append(c(p.artist)).append(',').append(c(p.title)).append(',').append(p.listeners)
                        .append(',').append(c(p.source)).append(',').append(p.durationMs / 1000).append('\n');
            }
        } else {
            // the summary tables come from the report itself
            Map<String, Object> rep = MiniJson.object(reportJson(st, from, to, fullIp));
            if ("countries".equals(kind)) {
                sb.append("country,code,sessions,unique_listeners,listening_minutes\n");
                for (Object o : (List<?>) rep.get("countries")) {
                    Map<?, ?> m = (Map<?, ?>) o;
                    sb.append(c(String.valueOf(m.get("name")))).append(',').append(c(String.valueOf(m.get("cc")))).append(',')
                            .append(num(m.get("sessions"))).append(',').append(num(m.get("unique"))).append(',').append(num(m.get("minutes"))).append('\n');
                }
            } else if ("agents".equals(kind)) {
                sb.append("player,platform,sessions,listening_minutes\n");
                for (Object o : (List<?>) rep.get("agents")) {
                    Map<?, ?> m = (Map<?, ?>) o;
                    sb.append(c(String.valueOf(m.get("player")))).append(',').append(c(String.valueOf(m.get("platform")))).append(',')
                            .append(num(m.get("sessions"))).append(',').append(num(m.get("minutes"))).append('\n');
                }
            } else if ("tracks".equals(kind)) {
                sb.append("artist,title,plays,average_listeners,peak_listeners,last_played\n");
                for (Object o : (List<?>) rep.get("tracksList")) {
                    Map<?, ?> m = (Map<?, ?>) o;
                    sb.append(c(String.valueOf(m.get("artist")))).append(',').append(c(String.valueOf(m.get("title")))).append(',')
                            .append(num(m.get("plays"))).append(',').append(m.get("avgListeners")).append(',').append(num(m.get("peakListeners")))
                            .append(',').append(iso(((Number) m.get("last")).longValue())).append('\n');
                }
            } else {
                sb.append(Boolean.TRUE.equals(rep.get("hourly")) ? "hour" : "day")
                        .append(",sessions,unique_listeners,listening_hours,peak_listeners,average_listeners,average_session_minutes,bytes,songs\n");
                for (Object o : (List<?>) rep.get("chart")) {
                    Map<?, ?> m = (Map<?, ?>) o;
                    sb.append(iso(((Number) m.get("t")).longValue())).append(',').append(num(m.get("sessions"))).append(',')
                            .append(num(m.get("unique"))).append(',').append(m.get("tlh")).append(',').append(num(m.get("peak"))).append(',')
                            .append(m.get("avg")).append(',').append(m.get("avgMin")).append(',').append(num(m.get("bytes"))).append(',')
                            .append(num(m.get("plays"))).append('\n');
                }
            }
        }
        return sb.toString();
    }

    private static String num(Object o) {
        return o instanceof Number ? String.valueOf(((Number) o).longValue()) : String.valueOf(o);
    }

    private static String iso(long ms) {
        return Instant.ofEpochMilli(ms).toString();
    }

    private static String c(String s) {
        String v = s == null ? "" : s;
        if (v.startsWith("=") || v.startsWith("+") || v.startsWith("-") || v.startsWith("@")) {
            v = "'" + v; // never a formula in a spreadsheet
        }
        return "\"" + v.replace("\"", "\"\"") + "\"";
    }

    static String q(String s) {
        if (s == null) {
            return "\"\"";
        }
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                case '<': sb.append("\\u003c"); break;
                default:
                    if (ch < 0x20 || ch > 0x7e) {
                        sb.append(String.format("\\u%04x", (int) ch));
                    } else {
                        sb.append(ch);
                    }
            }
        }
        return sb.append('"').toString();
    }
}