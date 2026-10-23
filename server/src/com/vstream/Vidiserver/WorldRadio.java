package com.vstream.Vidiserver;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;
import java.util.zip.GZIPOutputStream;

/**
 * World Radio (memoryq.com/world): radio stations from all over the world on a spinning globe.
 * The dataset: the station finder bot (WorldBot: Radio Browser, Wikidata, the Icecast directory, lists such as the TCRA
 * licence list - every stream is tested before it goes on the globe), stations Memoryq members add, and the super-admin's
 * own edits (Admin -> Global Radio).
 *   GET  /api/world/land | countries | browse | dots | search?q=&cc=&near=lat,lon | ask?q= (Alma) | station?id= | listen?id=
 *   GET  /api/world/geocode?q=  /reverse?lat=&lon=      (members: find a place for a new station)
 *   POST /api/world/add  name, url, home, tags, lang, lat, lon, city, country, cc     /play id     /report id
 *   /api/admin/world/status | list | countries | station | save | test | remove | restore | delete | bot | run | find | settings
 * Status: active (on the globe), broken (silent 3 nights running - hidden, comes back by itself when it plays again),
 * removed (taken off by the super-admin - never brought back by the bot), noplace (plays, but where it is is not known yet).
 * http:// streams play through the server (browsers block them on an https page); only stations in the list, never
 * addresses inside a network.
 */
public final class WorldRadio {

    public interface Fetcher {
        String get(String url) throws IOException;
    }

    /** Tests a stream address: {the address that plays, codec}; throws when no sound comes. */
    public interface Tester {
        String[] test(String url) throws IOException;
    }

    static final class Station {
        long id;
        String uuid = "";
        String name = "";
        String url = "";
        String home = "";
        String tags = "";
        String country = "";
        String cc = "";
        String city = "";
        String lang = "";
        double lat;
        double lon;
        String codec = "";
        int bitrate;
        long votes;
        String source = "rb";
        String addedBy = "";
        String status = "active";
        long created;
        long plays;
        int reports;
        long lastCheck;
        long lastOk;
        int fails;
        boolean edited;
        boolean exact = true;
        String note = "";
    }

    /** A station the bot found somewhere. */
    static final class Candidate {
        String key = "";
        String name = "";
        String url = "";
        String home = "";
        String tags = "";
        String country = "";
        String cc = "";
        String city = "";
        String lang = "";
        double lat = Double.NaN;
        double lon = Double.NaN;
        boolean exact = true;
        String codec = "";
        int bitrate;
        long votes;
        String source = "";
    }

    static final int ADDS_PER_DAY = 5;
    static final int MAX_RELAYS = 40;
    static final String UA = "MemoryqWorldRadio/1.0 (+https://memoryq.com; support@memoryq.com)";
    static final String[] STATUSES = {"active", "broken", "removed", "noplace"};

    private final Path file;
    private final UserStore users;
    private final SessionStore sessions;
    private final LongSupplier clock;
    private final Map<Long, Station> stations = new LinkedHashMap<Long, Station>();
    private final Map<String, Long> byUuid = new HashMap<String, Long>();
    private final Map<String, Long> byUrl = new HashMap<String, Long>();
    private final Map<String, String[]> geoCache = new HashMap<String, String[]>();
    private final AtomicInteger relays = new AtomicInteger();
    final Map<String, String> settings = new LinkedHashMap<String, String>();
    final Map<String, String[]> jobs = new LinkedHashMap<String, String[]>(); // name -> {last run, note}
    private long nextId = 1;
    private boolean dirty;
    private long lastGeo;
    private volatile Fetcher fetcher = WorldRadio::httpGet;
    private volatile boolean realNet = true;
    private volatile Tester tester = this::check;
    private volatile RadioWriter.Ai ai;
    private volatile boolean allowPrivate;
    private final java.util.Set<String> ownHosts = new java.util.concurrent.CopyOnWriteArraySet<String>();
    private volatile WorldBot bot;
    static final Map<String, String[]> COUNTRY_BY_NAME = new HashMap<String, String[]>();
    static final Map<String, String[]> COUNTRY_BY_CODE = new HashMap<String, String[]>();

    static {
        for (String row : WorldMap.COUNTRIES) {
            String[] f = row.split("\\|");
            COUNTRY_BY_NAME.put(f[0].toLowerCase(Locale.ROOT), f);
            COUNTRY_BY_CODE.put(f[1].toUpperCase(Locale.ROOT), f);
        }
        String[][] alias = {{"usa", "US"}, {"america", "US"}, {"united states of america", "US"}, {"uk", "GB"}, {"britain", "GB"},
            {"england", "GB"}, {"great britain", "GB"}, {"dr congo", "CD"}, {"drc", "CD"}, {"ivory coast", "CI"}, {"holland", "NL"},
            {"south korea", "KR"}, {"korea", "KR"}, {"russia", "RU"}, {"uae", "AE"}, {"czech republic", "CZ"},
            {"the united states of america", "US"}, {"the united kingdom of great britain and northern ireland", "GB"},
            {"the netherlands", "NL"}, {"the russian federation", "RU"}, {"tanzania, united republic of", "TZ"},
            {"united republic of tanzania", "TZ"}};
        for (String[] a : alias) {
            String[] row = COUNTRY_BY_CODE.get(a[1]);
            if (row != null) {
                COUNTRY_BY_NAME.put(a[0], row);
            }
        }
    }

    public WorldRadio(Path file, UserStore users, SessionStore sessions, LongSupplier clock) {
        this.file = file;
        this.users = users;
        this.sessions = sessions;
        this.clock = clock;
        settings.put("rbLimit", "5000");
        settings.put("wikidata", "on");
        settings.put("icecast", "on");
        settings.put("checker", "on");
        settings.put("repair", "on");
    }

    public void setFetcher(Fetcher f) {
        this.fetcher = f;
        this.realNet = false;
    }

    Fetcher fetcher() {
        return fetcher;
    }

    public void setTester(Tester t) {
        this.tester = t;
    }

    Tester tester() {
        return tester;
    }

    public void setAi(RadioWriter.Ai a) {
        this.ai = a;
    }

    public void setBot(WorldBot b) {
        this.bot = b;
    }

    long now() {
        return clock.getAsLong();
    }

    /** Memoryq's own names (memoryq.com, mkarimu.net): its own radio streams may be added even when the server sees them inside. */
    public void setOwnHosts(String... hosts) {
        for (String h : hosts) {
            if (h != null && !h.trim().isEmpty()) {
                ownHosts.add(h.trim().toLowerCase(Locale.ROOT).replaceFirst(":\\d+$", ""));
            }
        }
    }

    /** Tests only: allow stream addresses on this computer. */
    void allowPrivateAddresses(boolean on) {
        this.allowPrivate = on;
    }

    // ---- the file ----

    static String c(String s, int max) {
        if (s == null) {
            return "";
        }
        String t = s.replaceAll("[\\t\\r\\n]+", " ").trim();
        return t.length() > max ? t.substring(0, max) : t;
    }

    private static String urlKey(String url) {
        return url.toLowerCase(Locale.ROOT).replaceFirst("^https?://", "").replaceAll("/+$", "");
    }

    private void index(Station s) {
        if (!s.uuid.isEmpty()) {
            byUuid.put(s.uuid, s.id);
        }
        if (!s.url.isEmpty()) {
            byUrl.put(urlKey(s.url), s.id);
        }
    }

    public synchronized void init() {
        stations.clear();
        byUuid.clear();
        byUrl.clear();
        if (file == null || !Files.isRegularFile(file)) {
            return;
        }
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String[] f = line.split("\t", -1);
                if (f.length >= 3 && "#set".equals(f[0])) {
                    settings.put(f[1], f[2]);
                    continue;
                }
                if (f.length >= 4 && "#job".equals(f[0])) {
                    jobs.put(f[1], new String[]{f[2], f[3]});
                    continue;
                }
                if (f.length >= 3 && "#import".equals(f[0])) { // the first version's line
                    jobs.put("rb", new String[]{f[1], f[2]});
                    continue;
                }
                if (f.length < 21 || !f[0].matches("\\d+")) {
                    continue;
                }
                try {
                    Station s = new Station();
                    s.id = Long.parseLong(f[0]);
                    s.uuid = f[1];
                    s.name = f[2];
                    s.url = f[3];
                    s.home = f[4];
                    s.tags = f[5];
                    s.country = f[6];
                    s.cc = f[7];
                    s.city = f[8];
                    s.lang = f[9];
                    s.lat = Double.parseDouble(f[10]);
                    s.lon = Double.parseDouble(f[11]);
                    s.codec = f[12];
                    s.bitrate = Integer.parseInt(f[13]);
                    s.votes = Long.parseLong(f[14]);
                    s.source = f[15];
                    s.addedBy = f[16];
                    s.status = f[17];
                    s.created = Long.parseLong(f[18]);
                    s.plays = Long.parseLong(f[19]);
                    s.reports = Integer.parseInt(f[20]);
                    if (f.length >= 27) {
                        s.lastCheck = Long.parseLong(f[21]);
                        s.lastOk = Long.parseLong(f[22]);
                        s.fails = Integer.parseInt(f[23]);
                        s.edited = "1".equals(f[24]);
                        s.exact = !"0".equals(f[25]);
                        s.note = f[26];
                    }
                    stations.put(s.id, s);
                    index(s);
                    nextId = Math.max(nextId, s.id + 1);
                } catch (RuntimeException e) {
                    // a damaged line
                }
            }
        } catch (IOException e) {
            System.out.println("[world] could not read " + file + ": " + e.getMessage());
        }
    }

    public synchronized void flushIfDirty() {
        if (!dirty || file == null) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : settings.entrySet()) {
            sb.append("#set\t").append(e.getKey()).append('\t').append(c(e.getValue(), 200)).append('\n');
        }
        for (Map.Entry<String, String[]> e : jobs.entrySet()) {
            sb.append("#job\t").append(e.getKey()).append('\t').append(e.getValue()[0]).append('\t').append(c(e.getValue()[1], 300)).append('\n');
        }
        for (Station s : stations.values()) {
            sb.append(s.id).append('\t').append(s.uuid).append('\t').append(s.name).append('\t').append(s.url).append('\t').append(s.home)
                    .append('\t').append(s.tags).append('\t').append(s.country).append('\t').append(s.cc).append('\t').append(s.city)
                    .append('\t').append(s.lang).append('\t').append(s.lat).append('\t').append(s.lon).append('\t').append(s.codec)
                    .append('\t').append(s.bitrate).append('\t').append(s.votes).append('\t').append(s.source).append('\t').append(s.addedBy)
                    .append('\t').append(s.status).append('\t').append(s.created).append('\t').append(s.plays).append('\t').append(s.reports)
                    .append('\t').append(s.lastCheck).append('\t').append(s.lastOk).append('\t').append(s.fails).append('\t').append(s.edited ? 1 : 0)
                    .append('\t').append(s.exact ? 1 : 0).append('\t').append(c(s.note, 200)).append('\n');
        }
        try {
            Path part = file.resolveSibling(file.getFileName() + ".part");
            Files.write(part, sb.toString().getBytes(StandardCharsets.UTF_8));
            Files.move(part, file, StandardCopyOption.REPLACE_EXISTING);
            dirty = false;
        } catch (IOException e) {
            System.out.println("[world] could not save: " + e.getMessage());
        }
    }

    synchronized void touch() {
        dirty = true;
    }

    synchronized String setting(String k) {
        String v = settings.get(k);
        return v == null ? "" : v;
    }

    synchronized void jobDone(String name, String note) {
        jobs.put(name, new String[]{String.valueOf(now()), c(note, 300)});
        dirty = true;
    }

    synchronized long jobLast(String name) {
        String[] j = jobs.get(name);
        try {
            return j == null ? 0 : Long.parseLong(j[0]);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public synchronized int count(String source) {
        int n = 0;
        for (Station s : stations.values()) {
            if ("active".equals(s.status) && (source == null || s.source.equals(source))) {
                n++;
            }
        }
        return n;
    }

    synchronized Station byId(long id) {
        return stations.get(id);
    }

    synchronized List<Station> snapshot() {
        return new ArrayList<Station>(stations.values());
    }

    synchronized boolean knownUrl(String url) {
        return byUrl.containsKey(urlKey(url));
    }

    synchronized boolean knownKey(String key) {
        return byUuid.containsKey(key);
    }

    synchronized Station findByName(String name, String cc) {
        String n = norm(name);
        for (Station s : stations.values()) {
            if (norm(s.name).equals(n) && (cc == null || cc.isEmpty() || cc.equalsIgnoreCase(s.cc))) {
                return s;
            }
        }
        return null;
    }

    static String norm(String name) {
        return name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").replaceAll("\\b(radio|fm|am|the)\\b", " ").replaceAll("\\s+", " ").trim();
    }

    /** The bot's checker reports what it heard. */
    synchronized void checked(long id, String okUrl, String codec, String note) {
        Station s = stations.get(id);
        if (s == null) {
            return;
        }
        long now = now();
        s.lastCheck = now;
        if (okUrl != null) {
            s.lastOk = now;
            s.fails = 0;
            if (!okUrl.equals(s.url)) {
                byUrl.remove(urlKey(s.url));
                s.url = c(okUrl, 500);
                byUrl.put(urlKey(s.url), s.id);
            }
            if (codec != null && !codec.isEmpty()) {
                s.codec = codec;
            }
            if ("broken".equals(s.status)) {
                s.status = "active";
            }
            if (note != null) {
                s.note = note;
            }
        } else {
            s.fails++;
            if (s.fails >= 3 && "active".equals(s.status)) {
                s.status = "broken";
                s.note = "silent " + s.fails + " checks running" + (note == null ? "" : ": " + note);
            }
        }
        dirty = true;
    }

    /**
     * Adds what the bot found, or updates it (never what the super-admin edited or removed).
     * tested = the stream address that played (null when the list itself vouches for it, as Radio Browser's does).
     */
    synchronized String upsert(Candidate k, String[] tested) {
        Long id = !k.key.isEmpty() ? byUuid.get(k.key) : null;
        if (id == null && !k.url.isEmpty()) {
            id = byUrl.get(urlKey(k.url));
        }
        if (id == null && tested != null) {
            id = byUrl.get(urlKey(tested[0]));
        }
        Station s = id == null ? null : stations.get(id);
        long now = now();
        boolean fresh = s == null;
        if (fresh) {
            s = new Station();
            s.id = nextId++;
            s.created = now;
            s.source = k.source;
            s.uuid = k.key;
            stations.put(s.id, s);
        } else if ("removed".equals(s.status)) {
            return "removed";
        }
        s.votes = Math.max(s.votes, k.votes);
        if (k.bitrate > 0) {
            s.bitrate = k.bitrate;
        }
        if (tested != null) {
            s.lastCheck = now;
            s.lastOk = now;
            s.fails = 0;
        }
        if (!s.edited || fresh) {
            byUrl.remove(urlKey(s.url));
            s.name = c(k.name, 80);
            s.url = c(tested != null ? tested[0] : k.url, 500);
            if (!k.home.isEmpty() || fresh) {
                s.home = c(k.home, 300).matches("(?i)https?://\\S+") ? c(k.home, 300) : "";
            }
            if (!k.tags.isEmpty() || fresh) {
                s.tags = c(k.tags, 160).toLowerCase(Locale.ROOT);
            }
            if (!k.lang.isEmpty() || fresh) {
                s.lang = c(k.lang, 60).toLowerCase(Locale.ROOT);
            }
            s.codec = tested != null ? tested[1] : c(k.codec, 12);
            String cc = c(k.cc, 2).toUpperCase(Locale.ROOT);
            if (!cc.isEmpty() || fresh) {
                s.cc = cc;
                String[] row = COUNTRY_BY_CODE.get(cc);
                s.country = !k.country.isEmpty() ? c(k.country, 60) : row != null ? row[0] : "";
            }
            if (!k.city.isEmpty() || fresh) {
                s.city = c(k.city, 60);
            }
            if (!Double.isNaN(k.lat) && Math.abs(k.lat) <= 90 && Math.abs(k.lon) <= 180) {
                s.lat = Math.round(k.lat * 10000) / 10000.0;
                s.lon = Math.round(k.lon * 10000) / 10000.0;
                s.exact = k.exact;
                if ("noplace".equals(s.status)) {
                    s.status = "active";
                }
            } else if (fresh) {
                s.status = "noplace";
            }
            index(s);
        }
        dirty = true;
        return fresh ? ("noplace".equals(s.status) ? "noplace" : "added") : "updated";
    }

    // ---- the open dataset (Radio Browser) ----

    static final String[] RB_MIRRORS = {"https://de1.api.radio-browser.info", "https://fi1.api.radio-browser.info",
        "https://nl1.api.radio-browser.info", "https://at1.api.radio-browser.info"};

    String rbGet(String path) throws IOException {
        IOException last = null;
        for (String m : RB_MIRRORS) {
            try {
                return fetcher.get(m + path);
            } catch (IOException e) {
                last = e;
            }
        }
        throw last == null ? new IOException("no mirror") : last;
    }

    static Candidate fromRb(Object o) {
        Candidate k = new Candidate();
        k.key = MiniJson.str(o, "stationuuid");
        k.url = MiniJson.str(o, "url_resolved");
        if (k.url.isEmpty()) {
            k.url = MiniJson.str(o, "url");
        }
        k.name = MiniJson.str(o, "name");
        k.home = MiniJson.str(o, "homepage");
        k.tags = MiniJson.str(o, "tags");
        k.country = MiniJson.str(o, "country");
        k.cc = MiniJson.str(o, "countrycode");
        k.city = MiniJson.str(o, "state");
        k.lang = MiniJson.str(o, "language");
        k.codec = MiniJson.str(o, "codec");
        k.bitrate = (int) MiniJson.num(o, "bitrate");
        k.votes = MiniJson.num(o, "votes") + MiniJson.num(o, "clickcount");
        k.source = "rb";
        try {
            k.lat = Double.parseDouble(MiniJson.str(o, "geo_lat"));
            k.lon = Double.parseDouble(MiniJson.str(o, "geo_long"));
            if (k.lat == 0 && k.lon == 0) {
                k.lat = Double.NaN;
            }
        } catch (RuntimeException e) {
            k.lat = Double.NaN;
        }
        if (MiniJson.num(o, "hls") == 1 || k.url.toLowerCase(Locale.ROOT).contains(".m3u8")) {
            k.url = "";
        }
        return k;
    }

    /** Fetches the most listened-to stations that have a place on the map, and adds or updates them. */
    public int importRadioBrowser() {
        int limit = 5000;
        try {
            limit = Math.max(100, Math.min(60000, Integer.parseInt(setting("rbLimit"))));
        } catch (NumberFormatException e) {
            // the default
        }
        String body;
        try {
            body = rbGet("/json/stations/search?has_geo_info=true&hidebroken=true&order=clickcount&reverse=true&limit=" + limit);
        } catch (IOException e) {
            jobDone("rb", "could not reach Radio Browser: " + e.getMessage());
            flushIfDirty();
            return -1;
        }
        Object root;
        try {
            root = MiniJson.parse(body);
        } catch (RuntimeException e) {
            jobDone("rb", "Radio Browser sent something unreadable");
            return -1;
        }
        if (!(root instanceof List)) {
            return -1;
        }
        int n = 0, added = 0;
        for (Object o : (List<?>) root) {
            Candidate k = fromRb(o);
            if (k.key.isEmpty() || k.url.isEmpty() || Double.isNaN(k.lat)) {
                continue;
            }
            String r = upsert(k, null);
            if (!"removed".equals(r)) {
                n++;
            }
            if ("added".equals(r)) {
                added++;
            }
        }
        jobDone("rb", n + " stations from Radio Browser (" + added + " new)");
        flushIfDirty();
        System.out.println("[world] " + n + " stations from Radio Browser");
        return n;
    }

    // ---- searching ----

    static String[] countryIn(String text) {
        String t = " " + text.toLowerCase(Locale.ROOT).replaceAll("[^a-z ]", " ").replaceAll("\\s+", " ") + " ";
        String[] best = null;
        int bestLen = 0;
        for (Map.Entry<String, String[]> e : COUNTRY_BY_NAME.entrySet()) {
            if (e.getKey().length() > bestLen && t.contains(" " + e.getKey().replaceAll("[^a-z ]", " ").replaceAll("\\s+", " ") + " ")) {
                best = e.getValue();
                bestLen = e.getKey().length();
            }
        }
        return best == null ? null : new String[]{best[0], best[1], best[2], best[3], String.valueOf(bestLen)};
    }

    static List<String> words(String text) {
        List<String> ws = new ArrayList<String>();
        for (String w : (text == null ? "" : text).toLowerCase(Locale.ROOT).split("[\\s,]+")) {
            if (w.length() >= 2 && !w.matches("radio|radios|station|stations|fm|in|from|the|of|me|near|music|channel|play|find|some|a|an|and|for|with|please|show|any|i|want|listen|to|on|at")) {
                ws.add(w);
            }
        }
        return ws;
    }

    /** Stations matching all the words (name, genre, place, language), in a country, or nearest to a point. */
    synchronized List<Station> search(String words, String cc, double[] near, int limit) {
        List<String> ws = words(words);
        List<Station> out = new ArrayList<Station>();
        for (Station s : stations.values()) {
            if (!"active".equals(s.status) || (cc != null && !cc.isEmpty() && !cc.equalsIgnoreCase(s.cc))) {
                continue;
            }
            String hay = (s.name + " " + s.tags + " " + s.city + " " + s.country + " " + s.lang).toLowerCase(Locale.ROOT);
            boolean ok = true;
            for (String w : ws) {
                if (!hay.contains(w)) {
                    ok = false;
                    break;
                }
            }
            if (ok) {
                out.add(s);
            }
        }
        if (near != null) {
            final double la = near[0], lo = near[1];
            out.sort((a, b) -> Double.compare(dist(la, lo, a.lat, a.lon), dist(la, lo, b.lat, b.lon)));
        } else {
            out.sort((a, b) -> Long.compare(rank(b), rank(a)));
        }
        return out.size() > limit ? new ArrayList<Station>(out.subList(0, limit)) : out;
    }

    private static long rank(Station s) {
        return s.votes + s.plays * 50 + ("member".equals(s.source) || "admin".equals(s.source) ? 500 : 0);
    }

    static double dist(double la1, double lo1, double la2, double lo2) {
        double p = Math.PI / 180;
        double a = 0.5 - Math.cos((la2 - la1) * p) / 2 + Math.cos(la1 * p) * Math.cos(la2 * p) * (1 - Math.cos((lo2 - lo1) * p)) / 2;
        return 12742 * Math.asin(Math.sqrt(a));
    }

    /** Alma turns a request ("gospel stations in Nairobi") into a search; without the AI, the words and country are used. */
    String ask(String q) {
        String text = q == null ? "" : q.trim();
        String cc = "";
        String reply = "";
        RadioWriter.Ai a = ai;
        if (a != null && a.ready() && !text.isEmpty()) {
            try {
                String out = a.write("You help listeners find radio stations on Memoryq World Radio. Turn the request into a search and answer "
                        + "ONLY with JSON: {\"words\":\"1-3 words to match in the station name, genre or city (no country)\","
                        + "\"country\":\"the ISO 3166 two-letter country code, or empty\",\"reply\":\"one short friendly sentence from Alma\"}", text);
                String json = out == null ? "" : out.substring(Math.max(0, out.indexOf('{')), out.lastIndexOf('}') + 1);
                Object o = MiniJson.parse(json);
                text = MiniJson.str(o, "words");
                cc = MiniJson.str(o, "country").toUpperCase(Locale.ROOT);
                reply = c(MiniJson.str(o, "reply"), 200);
                if (!cc.matches("[A-Z]{2}")) {
                    cc = "";
                }
            } catch (Exception e) {
                text = q.trim();
                cc = "";
            }
        }
        if (cc.isEmpty()) {
            String[] found = countryIn(text);
            if (found != null) {
                cc = found[1];
                for (Map.Entry<String, String[]> e : COUNTRY_BY_NAME.entrySet()) {
                    if (e.getValue()[1].equals(cc)) {
                        text = (" " + text.toLowerCase(Locale.ROOT) + " ").replace(" " + e.getKey() + " ", " ").trim();
                    }
                }
            }
        }
        text = String.join(" ", words(text));
        List<Station> list = search(text, cc, null, 60);
        if (list.isEmpty() && !text.isEmpty() && !cc.isEmpty()) {
            list = search("", cc, null, 60); // nothing with those words there: the country's best stations
            reply = (reply.isEmpty() ? "" : reply + " ") + "Nothing matched \"" + text + "\" there, so here are the most listened-to stations.";
        }
        if (reply.isEmpty()) {
            String[] row = COUNTRY_BY_CODE.get(cc);
            reply = list.isEmpty() ? "I could not find a station for that - try other words or a country name."
                    : "I found " + list.size() + (list.size() == 60 ? "+" : "") + " station" + (list.size() == 1 ? "" : "s")
                    + (text.isEmpty() ? "" : " for \"" + text + "\"") + (row == null ? "" : " in " + row[0]) + ".";
        }
        String[] row = COUNTRY_BY_CODE.get(cc);
        StringBuilder sb = new StringBuilder("{\"reply\":").append(q(reply)).append(",\"words\":").append(q(text)).append(",\"cc\":").append(q(cc));
        if (row != null) {
            sb.append(",\"focus\":[").append(row[2]).append(',').append(row[3]).append(']');
        }
        return sb.append(",\"stations\":").append(listJson(list)).append('}').toString();
    }

    /** Stations per country (only those on the globe): [[cc, name, count, lat, lon], ...] biggest first. */
    synchronized String browseJson() {
        Map<String, int[]> n = new HashMap<String, int[]>();
        for (Station s : stations.values()) {
            if ("active".equals(s.status) && COUNTRY_BY_CODE.containsKey(s.cc)) {
                n.computeIfAbsent(s.cc, k -> new int[1])[0]++;
            }
        }
        List<Map.Entry<String, int[]>> l = new ArrayList<Map.Entry<String, int[]>>(n.entrySet());
        l.sort((a, b) -> b.getValue()[0] - a.getValue()[0]);
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < l.size(); i++) {
            String[] row = COUNTRY_BY_CODE.get(l.get(i).getKey());
            sb.append(i > 0 ? "," : "").append('[').append(q(row[1])).append(',').append(q(row[0])).append(',').append(l.get(i).getValue()[0])
                    .append(',').append(row[2]).append(',').append(row[3]).append(']');
        }
        return sb.append(']').toString();
    }

    // ---- places ----

    /** A place for a name ("Mwanza, Tanzania"): OpenStreetMap's search, else the country's middle. {lat, lon, city, country, cc, exact} */
    String[] geocode(String q) {
        String key = "q:" + q.toLowerCase(Locale.ROOT).trim();
        synchronized (geoCache) {
            if (geoCache.containsKey(key)) {
                return geoCache.get(key);
            }
        }
        String[] r = null;
        try {
            String body = politeGet("https://nominatim.openstreetmap.org/search?format=json&limit=1&addressdetails=1&accept-language=en&q="
                    + URLEncoder.encode(q, "UTF-8"));
            Object o = MiniJson.parse(body);
            if (o instanceof List && !((List<?>) o).isEmpty()) {
                Object p = ((List<?>) o).get(0);
                r = place(Double.parseDouble(MiniJson.str(p, "lat")), Double.parseDouble(MiniJson.str(p, "lon")), MiniJson.at(p, "address"));
            }
        } catch (Exception e) {
            r = null;
        }
        if (r == null) {
            String[] row = countryIn(q);
            if (row != null) {
                r = new String[]{row[2], row[3], "", row[0], row[1], "0"};
            }
        }
        synchronized (geoCache) {
            geoCache.put(key, r);
        }
        return r;
    }

    /** The town and country at a point (the phone's location). */
    String[] reverse(double lat, double lon) {
        String key = "r:" + Math.round(lat * 100) + "," + Math.round(lon * 100);
        synchronized (geoCache) {
            if (geoCache.containsKey(key)) {
                return geoCache.get(key);
            }
        }
        String[] r;
        try {
            String body = politeGet("https://nominatim.openstreetmap.org/reverse?format=json&zoom=10&accept-language=en&lat=" + lat + "&lon=" + lon);
            r = place(lat, lon, MiniJson.at(MiniJson.parse(body), "address"));
        } catch (Exception e) {
            r = new String[]{String.valueOf(lat), String.valueOf(lon), "", "", "", "1"};
        }
        synchronized (geoCache) {
            geoCache.put(key, r);
        }
        return r;
    }

    private static String[] place(double lat, double lon, Object addr) {
        String city = "";
        for (String k : new String[]{"city", "town", "village", "municipality", "county", "state"}) {
            String v = MiniJson.str(addr, k);
            if (!v.isEmpty()) {
                city = v;
                break;
            }
        }
        return new String[]{String.valueOf(Math.round(lat * 10000) / 10000.0), String.valueOf(Math.round(lon * 10000) / 10000.0),
            c(city, 60), c(MiniJson.str(addr, "country"), 60), c(MiniJson.str(addr, "country_code"), 2).toUpperCase(Locale.ROOT), "1"};
    }

    /** OpenStreetMap asks for at most one request a second. */
    private String politeGet(String url) throws IOException {
        synchronized (geoCache) {
            long wait = 1100 - (System.currentTimeMillis() - lastGeo);
            if (wait > 0 && realNet) {
                try {
                    Thread.sleep(wait);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            lastGeo = System.currentTimeMillis();
        }
        return fetcher.get(url);
    }

    static String httpGet(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(10000);
            c.setReadTimeout(90000);
            c.setRequestProperty("User-Agent", UA);
            c.setRequestProperty("Accept", url.contains("/sparql") ? "application/sparql-results+json"
                    : "application/json, text/html;q=0.9, text/xml;q=0.8, */*;q=0.5");
            int code = c.getResponseCode();
            if (code != 200) {
                throw new IOException("HTTP " + code);
            }
            return new String(readUpTo(c.getInputStream(), 60 * 1024 * 1024), StandardCharsets.UTF_8);
        } finally {
            c.disconnect();
        }
    }

    // ---- streams ----

    private void checkHost(URL u) throws IOException {
        String p = u.getProtocol().toLowerCase(Locale.ROOT);
        if (!p.equals("http") && !p.equals("https")) {
            throw new IOException("the stream address must start with http:// or https://");
        }
        if (allowPrivate || ownHosts.contains(u.getHost().toLowerCase(Locale.ROOT))) {
            return;
        }
        for (InetAddress a : InetAddress.getAllByName(u.getHost())) {
            if (a.isLoopbackAddress() || a.isSiteLocalAddress() || a.isLinkLocalAddress() || a.isAnyLocalAddress() || a.isMulticastAddress()
                    || a.getHostAddress().startsWith("100.64.") || a.getHostAddress().toLowerCase(Locale.ROOT).startsWith("fc")
                    || a.getHostAddress().toLowerCase(Locale.ROOT).startsWith("fd")) {
                throw new IOException("that address is not on the internet");
            }
        }
    }

    /** Opens a stream, following redirects and playlists (.m3u, .pls); checks every address. */
    HttpURLConnection openStream(String url, int depth) throws IOException {
        if (depth > 5) {
            throw new IOException("too many redirects");
        }
        URL u = new URL(url);
        checkHost(u);
        HttpURLConnection c = (HttpURLConnection) u.openConnection();
        c.setInstanceFollowRedirects(false);
        c.setConnectTimeout(8000);
        c.setReadTimeout(15000);
        c.setRequestProperty("User-Agent", UA);
        c.setRequestProperty("Icy-MetaData", "0");
        int code = c.getResponseCode();
        if (code >= 300 && code < 400) {
            String loc = c.getHeaderField("Location");
            c.disconnect();
            if (loc == null) {
                throw new IOException("a redirect without an address");
            }
            return openStream(new URL(u, loc).toString(), depth + 1);
        }
        if (code != 200) {
            c.disconnect();
            throw new IOException("the stream answered " + code);
        }
        String type = String.valueOf(c.getContentType()).toLowerCase(Locale.ROOT);
        String path = u.getPath().toLowerCase(Locale.ROOT);
        if (type.contains("mpegurl") || type.contains("scpls") || path.endsWith(".m3u") || path.endsWith(".pls")) {
            byte[] b = readUpTo(c.getInputStream(), 65536);
            c.disconnect();
            for (String line : new String(b, StandardCharsets.UTF_8).split("\\r?\\n")) {
                String l = line.trim();
                int eq = l.indexOf('=');
                if (l.toLowerCase(Locale.ROOT).startsWith("file") && eq > 0) {
                    l = l.substring(eq + 1).trim();
                }
                if (l.startsWith("http://") || l.startsWith("https://")) {
                    if (l.toLowerCase(Locale.ROOT).contains(".m3u8")) {
                        throw new IOException("HLS streams are not supported");
                    }
                    return openStream(l, depth + 1);
                }
            }
            throw new IOException("the playlist has no stream address");
        }
        return c;
    }

    static byte[] readUpTo(InputStream in, int max) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while (b.size() < max && (n = in.read(buf, 0, Math.min(buf.length, max - b.size()))) > 0) {
            b.write(buf, 0, n);
        }
        return b.toByteArray();
    }

    /** Checks that sound comes out of a stream address; returns the final address and its type. */
    String[] check(String url) throws IOException {
        HttpURLConnection c = openStream(url, 0);
        try {
            String type = String.valueOf(c.getContentType()).toLowerCase(Locale.ROOT);
            boolean icy = c.getHeaderField("icy-name") != null || c.getHeaderField("icy-br") != null;
            if (!(type.startsWith("audio/") || type.contains("ogg") || type.contains("aac") || type.contains("mpeg") || icy)
                    || type.contains("mpegurl") || type.startsWith("video/")) {
                throw new IOException("that address is not an audio stream (it sends " + type + ")");
            }
            byte[] b = readUpTo(c.getInputStream(), 4096);
            if (b.length < 1024) {
                throw new IOException("no sound came from that stream");
            }
            String codec = type.contains("aac") ? "AAC" : type.contains("ogg") || type.contains("opus") ? "OGG" : "MP3";
            return new String[]{c.getURL().toString(), codec};
        } finally {
            c.disconnect();
        }
    }

    // ---- HTTP ----

    public void register(HttpServer server) {
        server.createContext("/api/world/", this::handle);
        server.createContext("/api/admin/world/", this::handleAdmin);
    }

    static final class Bad extends Exception {
        final int status;

        Bad(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    private UserStore.Info me(HttpExchange ex) {
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        String token = auth != null && auth.startsWith("Bearer ") ? auth.substring(7).trim() : "";
        SessionStore.Session s = token.isEmpty() ? null : sessions.get(token);
        UserStore.Info i = s == null ? null : users.info(s.username);
        return i == null || i.disabled ? null : i;
    }

    static String p(String q, String k) {
        String v = MediaAuthHttpServer.queryParam(q, k);
        return v == null ? "" : v.trim();
    }

    private static double num(String v, double min, double max) throws Bad {
        try {
            double d = Double.parseDouble(v);
            if (d < min || d > max || Double.isNaN(d)) {
                throw new Bad(400, "the place is not on the map");
            }
            return d;
        } catch (NumberFormatException e) {
            throw new Bad(400, "the place is not on the map");
        }
    }

    private static long idOf(String v) throws Bad {
        try {
            return Long.parseLong(v);
        } catch (NumberFormatException e) {
            throw new Bad(400, "which station?");
        }
    }

    private void handle(HttpExchange ex) throws IOException {
        try {
            String op = ex.getRequestURI().getPath().substring("/api/world/".length());
            String query = ex.getRequestURI().getRawQuery();
            boolean post = "POST".equalsIgnoreCase(ex.getRequestMethod());
            switch (op) {
                case "land":
                    send(ex, 200, "{\"points\":" + WorldMap.LAND_POINTS + ",\"data\":" + q(WorldMap.land()) + "}", "public, max-age=86400");
                    return;
                case "countries": {
                    StringBuilder sb = new StringBuilder("[");
                    for (int i = 0; i < WorldMap.COUNTRIES.length; i++) {
                        String[] f = WorldMap.COUNTRIES[i].split("\\|");
                        sb.append(i > 0 ? "," : "").append('[').append(q(f[0])).append(',').append(q(f[1])).append(',').append(f[2]).append(',').append(f[3]).append(']');
                    }
                    send(ex, 200, sb.append(']').toString(), "public, max-age=86400");
                    return;
                }
                case "browse":
                    send(ex, 200, "{\"countries\":" + browseJson() + "}", "public, max-age=300");
                    return;
                case "dots": {
                    StringBuilder sb = new StringBuilder();
                    int n = 0;
                    synchronized (this) {
                        for (Station s : stations.values()) {
                            if ("active".equals(s.status)) {
                                sb.append(n++ > 0 ? ";" : "").append(s.id).append(',').append(s.lat).append(',').append(s.lon).append(',')
                                        .append("member".equals(s.source) || "admin".equals(s.source) ? 1 : 0);
                            }
                        }
                    }
                    send(ex, 200, "{\"n\":" + n + ",\"dots\":" + q(sb.toString()) + "}", "public, max-age=300");
                    return;
                }
                case "search": {
                    double[] near = null;
                    String nr = p(query, "near");
                    if (nr.matches("-?[0-9.]+,-?[0-9.]+")) {
                        near = new double[]{Double.parseDouble(nr.split(",")[0]), Double.parseDouble(nr.split(",")[1])};
                    }
                    send(ex, 200, "{\"stations\":" + listJson(search(p(query, "q"), p(query, "cc"), near, 60)) + "}", "no-store");
                    return;
                }
                case "ask":
                    send(ex, 200, ask(p(query, "q")), "no-store");
                    return;
                case "station": {
                    Station s = byId(idOf(p(query, "id")));
                    if (s == null || !"active".equals(s.status)) {
                        throw new Bad(404, "this station is no longer on the map");
                    }
                    send(ex, 200, json(s), "no-store");
                    return;
                }
                case "listen":
                    listen(ex, query, false);
                    return;
                default:
                    break;
            }
            if (!post && !op.equals("geocode") && !op.equals("reverse")) {
                throw new Bad(404, "not found");
            }
            String form = post ? SuperAgent.readBody(ex, 8192) : query;
            if (op.equals("play")) {
                synchronized (this) {
                    Station s = stations.get(idOf(p(form, "id")));
                    if (s != null) {
                        s.plays++;
                        dirty = true;
                    }
                }
                send(ex, 200, "{\"ok\":true}", "no-store");
                return;
            }
            UserStore.Info me = me(ex);
            if (me == null || me.role == UserStore.Role.GUEST) {
                throw new Bad(401, "sign in to Memoryq first");
            }
            switch (op) {
                case "geocode": {
                    String[] g = geocode(p(form, "q"));
                    if (g == null) {
                        throw new Bad(404, "that place was not found - try \"town, country\"");
                    }
                    send(ex, 200, placeJson(g), "no-store");
                    return;
                }
                case "reverse":
                    send(ex, 200, placeJson(reverse(num(p(form, "lat"), -90, 90), num(p(form, "lon"), -180, 180))), "no-store");
                    return;
                case "report": {
                    synchronized (this) {
                        Station s = stations.get(idOf(p(form, "id")));
                        if (s != null) {
                            s.reports++;
                            dirty = true;
                        }
                    }
                    send(ex, 200, "{\"ok\":true}", "no-store");
                    return;
                }
                case "add":
                    send(ex, 200, add(me, form), "no-store");
                    return;
                default:
                    throw new Bad(404, "not found");
            }
        } catch (Bad e) {
            send(ex, e.status, "{\"error\":" + q(e.getMessage()) + "}", "no-store");
        } catch (Exception e) {
            send(ex, 500, "{\"error\":" + q("server error: " + e.getMessage()) + "}", "no-store");
        } finally {
            ex.close();
        }
    }

    private String add(UserStore.Info me, String form) throws Bad {
        long now = now();
        synchronized (this) {
            int today = 0;
            for (Station s : stations.values()) {
                if (me.username.equalsIgnoreCase(s.addedBy) && now - s.created < 86400000L) {
                    today++;
                }
            }
            if (today >= ADDS_PER_DAY && me.role != UserStore.Role.ADMIN) {
                throw new Bad(429, ADDS_PER_DAY + " new stations a day at most - try again tomorrow");
            }
        }
        String name = c(p(form, "name"), 80);
        String url = c(p(form, "url"), 500);
        if (name.length() < 2) {
            throw new Bad(400, "give the station its name");
        }
        double lat = num(p(form, "lat"), -90, 90);
        double lon = num(p(form, "lon"), -180, 180);
        synchronized (this) {
            for (Station s : stations.values()) {
                if (!"removed".equals(s.status) && (s.url.equalsIgnoreCase(url) || (s.name.equalsIgnoreCase(name) && dist(lat, lon, s.lat, s.lon) < 30))) {
                    throw new Bad(409, s.name + " is on the map already");
                }
            }
        }
        String[] ok;
        try {
            ok = tester.test(url);
        } catch (IOException e) {
            throw new Bad(400, "the stream did not play: " + e.getMessage());
        }
        Station s = new Station();
        s.name = name;
        s.url = c(ok[0], 500);
        s.codec = ok[1];
        s.home = c(p(form, "home"), 300);
        if (!s.home.isEmpty() && !s.home.matches("(?i)https?://\\S+")) {
            s.home = "";
        }
        s.tags = c(p(form, "tags"), 160).toLowerCase(Locale.ROOT);
        s.lang = c(p(form, "lang"), 60).toLowerCase(Locale.ROOT);
        s.city = c(p(form, "city"), 60);
        s.country = c(p(form, "country"), 60);
        s.cc = c(p(form, "cc"), 2).toUpperCase(Locale.ROOT);
        if (s.country.isEmpty() && COUNTRY_BY_CODE.containsKey(s.cc)) {
            s.country = COUNTRY_BY_CODE.get(s.cc)[0];
        }
        s.lat = Math.round(lat * 10000) / 10000.0;
        s.lon = Math.round(lon * 10000) / 10000.0;
        s.source = "member";
        s.addedBy = me.username;
        s.created = now;
        s.lastCheck = now;
        s.lastOk = now;
        synchronized (this) {
            s.id = nextId++;
            stations.put(s.id, s);
            index(s);
            dirty = true;
        }
        flushIfDirty();
        System.out.println("[world] " + me.username + " added " + s.name + " (" + s.city + ", " + s.country + ")");
        return json(s);
    }

    /** http:// streams through the server - only stations on the map (any station for the super-admin), a few at a time. */
    private void listen(HttpExchange ex, String query, boolean admin) throws IOException, Bad {
        Station s = byId(idOf(p(query, "id")));
        if (s == null || (!admin && !"active".equals(s.status))) {
            throw new Bad(404, "this station is no longer on the map");
        }
        if (relays.incrementAndGet() > MAX_RELAYS) {
            relays.decrementAndGet();
            throw new Bad(503, "too many people are listening through Memoryq right now - try again soon");
        }
        HttpURLConnection c = null;
        try {
            c = openStream(s.url, 0);
            String type = c.getContentType();
            ex.getResponseHeaders().set("Content-Type", type == null || type.isEmpty() ? "audio/mpeg" : type);
            ex.getResponseHeaders().set("Cache-Control", "no-store");
            ex.sendResponseHeaders(200, 0);
            OutputStream os = ex.getResponseBody();
            InputStream in = c.getInputStream();
            byte[] buf = new byte[16384];
            long until = System.currentTimeMillis() + 4L * 3600000L;
            int n;
            while ((n = in.read(buf)) > 0 && System.currentTimeMillis() < until) {
                os.write(buf, 0, n);
                os.flush();
            }
        } catch (IOException e) {
            // the listener left, or the station stopped
        } finally {
            relays.decrementAndGet();
            if (c != null) {
                c.disconnect();
            }
        }
    }

    // ---- the super-admin: Admin -> Global Radio ----

    private void handleAdmin(HttpExchange ex) throws IOException {
        try {
            String op = ex.getRequestURI().getPath().substring("/api/admin/world/".length());
            String query = ex.getRequestURI().getRawQuery();
            if (op.equals("listen")) { // the admin's player (an <audio> cannot send the sign-in): any status, http or https
                String tok = p(query, "token");
                SessionStore.Session ss = tok.isEmpty() ? null : sessions.get(tok);
                if (ss == null || ss.role != UserStore.Role.ADMIN) {
                    throw new Bad(403, "super-admin only");
                }
                listen(ex, query, true);
                return;
            }
            if (!SuperAgent.isAdmin(ex, sessions)) {
                throw new Bad(403, "super-admin only");
            }
            boolean post = "POST".equalsIgnoreCase(ex.getRequestMethod());
            switch (op) {
                case "status": {
                    List<Station> mine = new ArrayList<Station>();
                    synchronized (this) {
                        for (Station s : stations.values()) {
                            if ("member".equals(s.source) || s.reports > 0) {
                                mine.add(s);
                            }
                        }
                    }
                    Collections.reverse(mine);
                    if (mine.size() > 300) {
                        mine = mine.subList(0, 300);
                    }
                    send(ex, 200, "{" + totals() + ",\"stations\":" + adminList(mine) + "}", "no-store");
                    return;
                }
                case "list":
                    send(ex, 200, adminSearch(query), "no-store");
                    return;
                case "countries":
                    send(ex, 200, "{\"countries\":" + adminCountries() + "}", "no-store");
                    return;
                case "station": {
                    Station s = byId(idOf(p(query, "id")));
                    if (s == null) {
                        throw new Bad(404, "no such station");
                    }
                    send(ex, 200, adminJson(s), "no-store");
                    return;
                }
                case "bot": {
                    WorldBot b = bot;
                    send(ex, 200, "{" + totals() + ",\"settings\":" + settingsJson() + ",\"bot\":" + (b == null ? "null" : b.statusJson()) + "}", "no-store");
                    return;
                }
                default:
                    break;
            }
            if (!post) {
                throw new Bad(405, "use POST");
            }
            String form = SuperAgent.readBody(ex, 200000);
            switch (op) {
                case "import": // the first version's button
                case "run": {
                    WorldBot b = bot;
                    String job = op.equals("import") ? "rb" : p(form, "job");
                    if (b == null) {
                        throw new Bad(503, "the station finder is not running");
                    }
                    if (!b.start(job, null, null)) {
                        throw new Bad(409, "the station finder is busy (" + b.running() + ") - try again when it has finished");
                    }
                    send(ex, 200, "{\"started\":" + q(job) + "}", "no-store");
                    return;
                }
                case "find": {
                    WorldBot b = bot;
                    String names = p(form, "names");
                    if (b == null) {
                        throw new Bad(503, "the station finder is not running");
                    }
                    if (names.trim().isEmpty()) {
                        throw new Bad(400, "paste the station names, one per line");
                    }
                    if (!b.start("list", names, p(form, "cc").toUpperCase(Locale.ROOT))) {
                        throw new Bad(409, "the station finder is busy (" + b.running() + ") - try again when it has finished");
                    }
                    send(ex, 200, "{\"started\":\"list\"}", "no-store");
                    return;
                }
                case "stop": {
                    WorldBot b = bot;
                    if (b != null) {
                        b.stop();
                    }
                    send(ex, 200, "{\"ok\":true}", "no-store");
                    return;
                }
                case "settings": {
                    synchronized (this) {
                        String lim = p(form, "rbLimit");
                        if (lim.matches("\\d{3,5}")) {
                            settings.put("rbLimit", String.valueOf(Math.max(100, Math.min(60000, Integer.parseInt(lim)))));
                        }
                        for (String k : new String[]{"wikidata", "icecast", "checker", "repair"}) {
                            String v = p(form, k);
                            if (v.equals("on") || v.equals("off")) {
                                settings.put(k, v);
                            }
                        }
                        dirty = true;
                    }
                    flushIfDirty();
                    send(ex, 200, "{\"settings\":" + settingsJson() + "}", "no-store");
                    return;
                }
                case "test": {
                    try {
                        String[] r = tester.test(p(form, "url"));
                        send(ex, 200, "{\"ok\":true,\"url\":" + q(r[0]) + ",\"codec\":" + q(r[1]) + "}", "no-store");
                    } catch (IOException e) {
                        send(ex, 200, "{\"ok\":false,\"error\":" + q(e.getMessage()) + "}", "no-store");
                    }
                    return;
                }
                case "save":
                    send(ex, 200, save(form), "no-store");
                    return;
                default:
                    break;
            }
            long id = idOf(p(form, "id"));
            String out;
            synchronized (this) {
                Station s = stations.get(id);
                if (s == null) {
                    throw new Bad(404, "no such station");
                }
                if (op.equals("remove")) {
                    s.status = "removed";
                    s.note = "taken off by the super-admin";
                } else if (op.equals("restore")) {
                    s.status = s.lat == 0 && s.lon == 0 ? "noplace" : "active";
                    s.reports = 0;
                    s.fails = 0;
                    s.note = "";
                } else if (op.equals("dismiss")) {
                    s.reports = 0;
                } else if (op.equals("delete")) {
                    if ("rb".equals(s.source) || "wikidata".equals(s.source) || "icecast".equals(s.source)) {
                        throw new Bad(400, "stations from the open lists are taken off, not deleted (or the finder would bring them back)");
                    }
                    stations.remove(id);
                    byUuid.remove(s.uuid);
                    byUrl.remove(urlKey(s.url));
                } else {
                    throw new Bad(404, "not found");
                }
                dirty = true;
                out = op.equals("delete") ? "{\"deleted\":" + id + "}" : adminJson(s);
            }
            flushIfDirty();
            send(ex, 200, out, "no-store");
        } catch (Bad e) {
            send(ex, e.status, "{\"error\":" + q(e.getMessage()) + "}", "no-store");
        } catch (Exception e) {
            send(ex, 500, "{\"error\":" + q("server error: " + e.getMessage()) + "}", "no-store");
        } finally {
            ex.close();
        }
    }

    /** The super-admin edits (or adds) a station. A new stream address is tested first unless "force" is ticked. */
    private String save(String form) throws Bad {
        String sid = p(form, "id");
        Station old = sid.isEmpty() ? null : byId(idOf(sid));
        if (!sid.isEmpty() && old == null) {
            throw new Bad(404, "no such station");
        }
        String name = c(p(form, "name"), 80);
        String url = c(p(form, "url"), 500);
        if (name.length() < 2) {
            throw new Bad(400, "the station needs a name");
        }
        if (!url.matches("(?i)https?://\\S+")) {
            throw new Bad(400, "the stream address must start with http:// or https://");
        }
        String latS = p(form, "lat"), lonS = p(form, "lon");
        boolean hasPlace = !latS.isEmpty() && !lonS.isEmpty();
        double lat = hasPlace ? num(latS, -90, 90) : 0, lon = hasPlace ? num(lonS, -180, 180) : 0;
        String codec = old == null ? "" : old.codec;
        String finalUrl = url;
        boolean urlChanged = old == null || !old.url.equals(url);
        if (urlChanged && !"1".equals(p(form, "force"))) {
            try {
                String[] r = tester.test(url);
                finalUrl = r[0];
                codec = r[1];
            } catch (IOException e) {
                throw new Bad(400, "the stream did not play: " + e.getMessage() + " (tick \"save without testing\" to keep it anyway)");
            }
        }
        String status = p(form, "status");
        Station saved;
        synchronized (this) {
            Long other = byUrl.get(urlKey(finalUrl));
            if (other != null && (old == null || other != old.id) && stations.containsKey(other)) {
                throw new Bad(409, "that stream is already on the list: " + stations.get(other).name + " (#" + other + ")");
            }
            Station s = old;
            if (s == null) {
                s = new Station();
                s.id = nextId++;
                s.source = "admin";
                s.addedBy = "admin";
                s.created = now();
                stations.put(s.id, s);
            } else {
                byUrl.remove(urlKey(s.url));
            }
            s.name = name;
            s.url = finalUrl;
            s.codec = codec;
            s.home = c(p(form, "home"), 300).matches("(?i)https?://\\S+") ? c(p(form, "home"), 300) : "";
            s.tags = c(p(form, "tags"), 160).toLowerCase(Locale.ROOT);
            s.lang = c(p(form, "lang"), 60).toLowerCase(Locale.ROOT);
            s.city = c(p(form, "city"), 60);
            s.cc = c(p(form, "cc"), 2).toUpperCase(Locale.ROOT);
            s.country = c(p(form, "country"), 60);
            if (s.country.isEmpty() && COUNTRY_BY_CODE.containsKey(s.cc)) {
                s.country = COUNTRY_BY_CODE.get(s.cc)[0];
            }
            if (hasPlace) {
                s.lat = Math.round(lat * 10000) / 10000.0;
                s.lon = Math.round(lon * 10000) / 10000.0;
                s.exact = true;
            }
            if (urlChanged) {
                s.fails = 0;
                s.lastCheck = now();
                s.lastOk = "1".equals(p(form, "force")) ? s.lastOk : now();
            }
            s.edited = true;
            if (java.util.Arrays.asList(STATUSES).contains(status)) {
                s.status = status;
            } else if (old == null) {
                s.status = "active";
            }
            if ("active".equals(s.status) && !hasPlace && s.lat == 0 && s.lon == 0) {
                s.status = "noplace";
            }
            s.note = "edited by the super-admin";
            index(s);
            dirty = true;
            saved = s;
        }
        flushIfDirty();
        return adminJson(saved);
    }

    private synchronized String totals() {
        int[] n = new int[STATUSES.length];
        int members = 0;
        for (Station s : stations.values()) {
            for (int i = 0; i < STATUSES.length; i++) {
                if (STATUSES[i].equals(s.status)) {
                    n[i]++;
                }
            }
            if ("member".equals(s.source) && "active".equals(s.status)) {
                members++;
            }
        }
        String[] rb = jobs.get("rb");
        return "\"total\":" + n[0] + ",\"broken\":" + n[1] + ",\"removed\":" + n[2] + ",\"noplace\":" + n[3] + ",\"members\":" + members
                + ",\"lastImport\":" + (rb == null ? "0" : rb[0]) + ",\"importNote\":" + q(rb == null ? "not yet" : rb[1]) + ",\"relaysNow\":" + relays.get();
    }

    private synchronized String settingsJson() {
        StringBuilder sb = new StringBuilder("{");
        int i = 0;
        for (Map.Entry<String, String> e : settings.entrySet()) {
            sb.append(i++ > 0 ? "," : "").append(q(e.getKey())).append(':').append(q(e.getValue()));
        }
        return sb.append('}').toString();
    }

    /** Admin -> Global Radio -> All stations: ?q=&cc=&status=&source=&sort=name|plays|new|checked&page= (50 a page). */
    private synchronized String adminSearch(String query) {
        String status = p(query, "status"), source = p(query, "source"), cc = p(query, "cc").toUpperCase(Locale.ROOT), sort = p(query, "sort");
        List<String> ws = new ArrayList<String>();
        for (String w : p(query, "q").toLowerCase(Locale.ROOT).split("\\s+")) {
            if (!w.isEmpty()) {
                ws.add(w);
            }
        }
        List<Station> out = new ArrayList<Station>();
        for (Station s : stations.values()) {
            if ((!status.isEmpty() && !status.equals("all") && !status.equals(s.status)) || (status.isEmpty() && "removed".equals(s.status))) {
                continue;
            }
            if ((!source.isEmpty() && !source.equals(s.source)) || (!cc.isEmpty() && !(cc.equals("--") ? s.cc.isEmpty() : cc.equals(s.cc)))) {
                continue;
            }
            if (p(query, "reported").equals("1") && s.reports == 0) {
                continue;
            }
            String hay = (s.id + " " + s.name + " " + s.tags + " " + s.city + " " + s.country + " " + s.lang + " " + s.url + " " + s.addedBy).toLowerCase(Locale.ROOT);
            boolean ok = true;
            for (String w : ws) {
                if (!hay.contains(w)) {
                    ok = false;
                    break;
                }
            }
            if (ok) {
                out.add(s);
            }
        }
        if (sort.equals("plays")) {
            out.sort((a, b) -> Long.compare(b.plays, a.plays));
        } else if (sort.equals("new")) {
            out.sort((a, b) -> Long.compare(b.created, a.created));
        } else if (sort.equals("popular")) {
            out.sort((a, b) -> Long.compare(rank(b), rank(a)));
        } else {
            out.sort((a, b) -> a.name.compareToIgnoreCase(b.name));
        }
        int page = 0;
        try {
            page = Math.max(0, Integer.parseInt(p(query, "page")));
        } catch (NumberFormatException e) {
            page = 0;
        }
        int from = Math.min(out.size(), page * 50);
        return "{\"count\":" + out.size() + ",\"page\":" + page + ",\"pages\":" + ((out.size() + 49) / 50) + ",\"stations\":"
                + adminList(out.subList(from, Math.min(out.size(), from + 50))) + "}";
    }

    /** Every country with its stations: [{cc, name, active, broken, noplace, removed, members}], biggest first. */
    private synchronized String adminCountries() {
        Map<String, int[]> n = new TreeMap<String, int[]>();
        for (Station s : stations.values()) {
            int[] c = n.computeIfAbsent(s.cc.isEmpty() ? "--" : s.cc, k -> new int[5]);
            for (int i = 0; i < STATUSES.length; i++) {
                if (STATUSES[i].equals(s.status)) {
                    c[i]++;
                }
            }
            if ("member".equals(s.source)) {
                c[4]++;
            }
        }
        List<Map.Entry<String, int[]>> l = new ArrayList<Map.Entry<String, int[]>>(n.entrySet());
        l.sort((a, b) -> (b.getValue()[0] + b.getValue()[1] + b.getValue()[3]) - (a.getValue()[0] + a.getValue()[1] + a.getValue()[3]));
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < l.size(); i++) {
            String k = l.get(i).getKey();
            int[] c = l.get(i).getValue();
            String[] row = COUNTRY_BY_CODE.get(k);
            String name = k.equals("--") ? "Country not known" : row != null ? row[0] : k;
            sb.append(i > 0 ? "," : "").append("{\"cc\":").append(q(k)).append(",\"name\":").append(q(name)).append(",\"active\":").append(c[0])
                    .append(",\"broken\":").append(c[1]).append(",\"removed\":").append(c[2]).append(",\"noplace\":").append(c[3])
                    .append(",\"members\":").append(c[4]);
            if (row != null) {
                sb.append(",\"lat\":").append(row[2]).append(",\"lon\":").append(row[3]);
            }
            sb.append('}');
        }
        return sb.append(']').toString();
    }

    // ---- JSON ----

    static String q(String s) {
        return ProfileApi.quote(s == null ? "" : s);
    }

    private String json(Station s) {
        String low = s.url.toLowerCase(Locale.ROOT);
        boolean https = low.startsWith("https://") && !low.matches(".*\\.(pls|m3u)(\\?.*)?$");
        return "{\"id\":" + s.id + ",\"name\":" + q(s.name) + ",\"play\":" + q(https ? s.url : "/api/world/listen?id=" + s.id)
                + ",\"home\":" + q(s.home) + ",\"tags\":" + q(s.tags) + ",\"city\":" + q(s.city) + ",\"country\":" + q(s.country)
                + ",\"cc\":" + q(s.cc) + ",\"lang\":" + q(s.lang) + ",\"lat\":" + s.lat + ",\"lon\":" + s.lon + ",\"codec\":" + q(s.codec)
                + ",\"bitrate\":" + s.bitrate + ",\"source\":" + q(s.source) + ",\"addedBy\":" + q(s.addedBy) + ",\"status\":" + q(s.status)
                + ",\"plays\":" + s.plays + ",\"reports\":" + s.reports + ",\"created\":" + s.created + "}";
    }

    /** The super-admin sees everything, including the real stream address and the checker's notes. */
    private String adminJson(Station s) {
        String j = json(s);
        return j.substring(0, j.length() - 1) + ",\"url\":" + q(s.url) + ",\"lastCheck\":" + s.lastCheck + ",\"lastOk\":" + s.lastOk
                + ",\"fails\":" + s.fails + ",\"edited\":" + s.edited + ",\"exact\":" + s.exact + ",\"note\":" + q(s.note) + ",\"votes\":" + s.votes + "}";
    }

    private String listJson(List<Station> list) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < list.size(); i++) {
            sb.append(i > 0 ? "," : "").append(json(list.get(i)));
        }
        return sb.append(']').toString();
    }

    private String adminList(List<Station> list) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < list.size(); i++) {
            sb.append(i > 0 ? "," : "").append(adminJson(list.get(i)));
        }
        return sb.append(']').toString();
    }

    private static String placeJson(String[] g) {
        return "{\"lat\":" + g[0] + ",\"lon\":" + g[1] + ",\"city\":" + q(g[2]) + ",\"country\":" + q(g[3]) + ",\"cc\":" + q(g[4]) + "}";
    }

    static void send(HttpExchange ex, int status, String body, String cache) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        String ae = ex.getRequestHeaders().getFirst("Accept-Encoding");
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", cache);
        if (ae != null && ae.contains("gzip") && b.length > 2048) {
            ByteArrayOutputStream z = new ByteArrayOutputStream();
            try (GZIPOutputStream g = new GZIPOutputStream(z)) {
                g.write(b);
            }
            b = z.toByteArray();
            ex.getResponseHeaders().set("Content-Encoding", "gzip");
        }
        ex.sendResponseHeaders(status, b.length);
        ex.getResponseBody().write(b);
    }
}