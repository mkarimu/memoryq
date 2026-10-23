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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;
import java.util.zip.GZIPOutputStream;

/**
 * World Radio (memoryq.com/world): radio stations from all over the world on a spinning globe.
 * The dataset: stations from Radio Browser (radio-browser.info, an open, community-made list - refreshed every day)
 * plus the stations Memoryq members add (stream address, name, genre and where it is - the place is found on the map).
 *   GET  /api/world/land | countries | dots | search?q=&cc=&near=lat,lon | ask?q= (Alma) | station?id= | listen?id=
 *   GET  /api/world/geocode?q=  /reverse?lat=&lon=      (members: find a place for a new station)
 *   POST /api/world/add  name, url, home, tags, lang, lat, lon, city, country, cc     /play id     /report id, reason
 *   /api/admin/world/status | remove | restore | import    (super-admin)
 * http:// streams play through the server (browsers block them on an https page); only stations in the list, never
 * addresses inside a network.
 */
public final class WorldRadio {

    public interface Fetcher {
        String get(String url) throws IOException;
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
    }

    static final int ADDS_PER_DAY = 5;
    static final int MAX_RELAYS = 40;
    static final String UA = "Memoryq/1.0 (+https://memoryq.com; support@memoryq.com)";

    private final Path file;
    private final UserStore users;
    private final SessionStore sessions;
    private final LongSupplier clock;
    private final Map<Long, Station> stations = new LinkedHashMap<Long, Station>();
    private final Map<String, Long> byUuid = new HashMap<String, Long>();
    private final Map<String, String[]> geoCache = new HashMap<String, String[]>();
    private final AtomicInteger relays = new AtomicInteger();
    private long nextId = 1;
    private boolean dirty;
    private long lastImport;
    private String lastImportNote = "not yet";
    private long lastGeo;
    private volatile Fetcher fetcher = WorldRadio::httpGet;
    private volatile RadioWriter.Ai ai;
    private volatile boolean allowPrivate;
    private ScheduledExecutorService timer;
    private static final Map<String, String[]> COUNTRY_BY_NAME = new HashMap<String, String[]>();
    private static final Map<String, String[]> COUNTRY_BY_CODE = new HashMap<String, String[]>();

    static {
        for (String row : WorldMap.COUNTRIES) {
            String[] f = row.split("\\|");
            COUNTRY_BY_NAME.put(f[0].toLowerCase(Locale.ROOT), f);
            COUNTRY_BY_CODE.put(f[1].toUpperCase(Locale.ROOT), f);
        }
        String[][] alias = {{"usa", "US"}, {"america", "US"}, {"united states of america", "US"}, {"uk", "GB"}, {"britain", "GB"},
            {"england", "GB"}, {"great britain", "GB"}, {"dr congo", "CD"}, {"drc", "CD"}, {"ivory coast", "CI"}, {"holland", "NL"},
            {"south korea", "KR"}, {"korea", "KR"}, {"russia", "RU"}, {"uae", "AE"}, {"czech republic", "CZ"}};
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
    }

    public void setFetcher(Fetcher f) {
        this.fetcher = f;
    }

    public void setAi(RadioWriter.Ai a) {
        this.ai = a;
    }

    /** Tests only: allow stream addresses on this computer. */
    void allowPrivateAddresses(boolean on) {
        this.allowPrivate = on;
    }

    // ---- the file ----

    private static String c(String s, int max) {
        if (s == null) {
            return "";
        }
        String t = s.replaceAll("[\\t\\r\\n]+", " ").trim();
        return t.length() > max ? t.substring(0, max) : t;
    }

    public synchronized void init() {
        stations.clear();
        byUuid.clear();
        if (file == null || !Files.isRegularFile(file)) {
            return;
        }
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String[] f = line.split("\t", -1);
                if (f.length < 21 || !f[0].matches("\\d+")) {
                    if (f.length >= 3 && "#import".equals(f[0])) {
                        lastImport = Long.parseLong(f[1]);
                        lastImportNote = f[2];
                    }
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
                    stations.put(s.id, s);
                    if (!s.uuid.isEmpty()) {
                        byUuid.put(s.uuid, s.id);
                    }
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
        sb.append("#import\t").append(lastImport).append('\t').append(c(lastImportNote, 200)).append('\n');
        for (Station s : stations.values()) {
            sb.append(s.id).append('\t').append(s.uuid).append('\t').append(s.name).append('\t').append(s.url).append('\t').append(s.home)
                    .append('\t').append(s.tags).append('\t').append(s.country).append('\t').append(s.cc).append('\t').append(s.city)
                    .append('\t').append(s.lang).append('\t').append(s.lat).append('\t').append(s.lon).append('\t').append(s.codec)
                    .append('\t').append(s.bitrate).append('\t').append(s.votes).append('\t').append(s.source).append('\t').append(s.addedBy)
                    .append('\t').append(s.status).append('\t').append(s.created).append('\t').append(s.plays).append('\t').append(s.reports)
                    .append('\n');
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

    public synchronized int count(String source) {
        int n = 0;
        for (Station s : stations.values()) {
            if ("active".equals(s.status) && (source == null || s.source.equals(source))) {
                n++;
            }
        }
        return n;
    }

    // ---- the open dataset (Radio Browser) ----

    /** Fetches the most listened-to stations that have a place on the map, and adds or updates them. */
    public int importRadioBrowser() {
        String[] mirrors = {"https://de1.api.radio-browser.info", "https://fi1.api.radio-browser.info",
            "https://nl1.api.radio-browser.info", "https://at1.api.radio-browser.info"};
        String body = null;
        String err = "";
        for (String m : mirrors) {
            try {
                body = fetcher.get(m + "/json/stations/search?has_geo_info=true&hidebroken=true&is_https=&order=clickcount&reverse=true&limit="
                        + Integer.getInteger("vstream.worldStations", 5000));
                break;
            } catch (IOException e) {
                err = e.getMessage();
            }
        }
        if (body == null) {
            synchronized (this) {
                lastImport = clock.getAsLong();
                lastImportNote = "could not reach Radio Browser: " + err;
                dirty = true;
            }
            flushIfDirty();
            return -1;
        }
        Object root;
        try {
            root = MiniJson.parse(body);
        } catch (RuntimeException e) {
            return -1;
        }
        if (!(root instanceof List)) {
            return -1;
        }
        int n = 0;
        long now = clock.getAsLong();
        synchronized (this) {
            for (Object o : (List<?>) root) {
                String uuid = MiniJson.str(o, "stationuuid");
                String url = MiniJson.str(o, "url_resolved");
                if (url.isEmpty()) {
                    url = MiniJson.str(o, "url");
                }
                if (uuid.isEmpty() || url.isEmpty() || MiniJson.num(o, "hls") == 1 || url.toLowerCase(Locale.ROOT).contains(".m3u8")) {
                    continue;
                }
                double lat, lon;
                try {
                    lat = Double.parseDouble(MiniJson.str(o, "geo_lat"));
                    lon = Double.parseDouble(MiniJson.str(o, "geo_long"));
                } catch (RuntimeException e) {
                    continue;
                }
                if (Math.abs(lat) > 90 || Math.abs(lon) > 180 || (lat == 0 && lon == 0)) {
                    continue;
                }
                Long id = byUuid.get(uuid);
                Station s = id == null ? null : stations.get(id);
                if (s == null) {
                    s = new Station();
                    s.id = nextId++;
                    s.uuid = uuid;
                    s.created = now;
                    s.source = "rb";
                    stations.put(s.id, s);
                    byUuid.put(uuid, s.id);
                }
                s.name = c(MiniJson.str(o, "name"), 80);
                s.url = c(url, 500);
                s.home = c(MiniJson.str(o, "homepage"), 300);
                s.tags = c(MiniJson.str(o, "tags"), 160);
                s.country = c(MiniJson.str(o, "country"), 60);
                s.cc = c(MiniJson.str(o, "countrycode"), 2).toUpperCase(Locale.ROOT);
                s.city = c(MiniJson.str(o, "state"), 60);
                s.lang = c(MiniJson.str(o, "language"), 60);
                s.lat = Math.round(lat * 10000) / 10000.0;
                s.lon = Math.round(lon * 10000) / 10000.0;
                s.codec = c(MiniJson.str(o, "codec"), 12);
                s.bitrate = (int) MiniJson.num(o, "bitrate");
                s.votes = MiniJson.num(o, "votes") + MiniJson.num(o, "clickcount");
                n++;
            }
            lastImport = now;
            lastImportNote = n + " stations from Radio Browser";
            dirty = true;
        }
        flushIfDirty();
        System.out.println("[world] " + n + " stations from Radio Browser");
        return n;
    }

    /** The dataset is refreshed every day (the first time a minute after the server starts). */
    public synchronized void start() {
        if (timer != null) {
            return;
        }
        timer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "world-radio");
            t.setDaemon(true);
            return t;
        });
        long wait = lastImport == 0 || clock.getAsLong() - lastImport > 86400000L ? 60 : (86400000L - (clock.getAsLong() - lastImport)) / 1000;
        timer.scheduleWithFixedDelay(() -> {
            try {
                importRadioBrowser();
            } catch (RuntimeException e) {
                System.out.println("[world] " + e);
            }
        }, Math.max(60, wait), 86400, TimeUnit.SECONDS);
        timer.scheduleWithFixedDelay(this::flushIfDirty, 2, 2, TimeUnit.MINUTES);
    }

    // ---- searching ----

    static String[] countryIn(String text) {
        String t = " " + text.toLowerCase(Locale.ROOT).replaceAll("[^a-z ]", " ").replaceAll("\\s+", " ") + " ";
        String[] best = null;
        int bestLen = 0;
        for (Map.Entry<String, String[]> e : COUNTRY_BY_NAME.entrySet()) {
            if (e.getKey().length() > bestLen && t.contains(" " + e.getKey() + " ")) {
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
            out.sort((a, b) -> Long.compare(b.votes + b.plays * 50 + ("rb".equals(b.source) ? 0 : 500), a.votes + a.plays * 50 + ("rb".equals(a.source) ? 0 : 500)));
        }
        return out.size() > limit ? new ArrayList<Station>(out.subList(0, limit)) : out;
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
                text = (" " + text.toLowerCase(Locale.ROOT) + " ").replace(" " + found[0].toLowerCase(Locale.ROOT) + " ", " ").trim();
                for (Map.Entry<String, String[]> e : COUNTRY_BY_NAME.entrySet()) {
                    if (e.getValue()[1].equals(cc)) {
                        text = (" " + text + " ").replace(" " + e.getKey() + " ", " ").trim();
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

    // ---- places ----

    /** A place for a name ("Mwanza, Tanzania"): OpenStreetMap's search, else the country's middle. {lat, lon, city, country, cc} */
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
                r = new String[]{row[2], row[3], "", row[0], row[1]};
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
            r = new String[]{String.valueOf(lat), String.valueOf(lon), "", "", ""};
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
            c(city, 60), c(MiniJson.str(addr, "country"), 60), c(MiniJson.str(addr, "country_code"), 2).toUpperCase(Locale.ROOT)};
    }

    /** OpenStreetMap asks for at most one request a second. */
    private String politeGet(String url) throws IOException {
        synchronized (this) {
            long wait = 1100 - (System.currentTimeMillis() - lastGeo);
            if (wait > 0) {
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
            c.setReadTimeout(60000);
            c.setRequestProperty("User-Agent", UA);
            c.setRequestProperty("Accept", "application/json");
            int code = c.getResponseCode();
            if (code != 200) {
                throw new IOException("HTTP " + code);
            }
            return new String(AudioTools.readAll(c.getInputStream()), StandardCharsets.UTF_8);
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
        if (allowPrivate) {
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
                    return openStream(l, depth + 1);
                }
            }
            throw new IOException("the playlist has no stream address");
        }
        return c;
    }

    private static byte[] readUpTo(InputStream in, int max) throws IOException {
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
            if (!(type.startsWith("audio/") || type.contains("ogg") || type.contains("aac") || type.contains("mpeg") || icy)) {
                throw new IOException("that address is not an audio stream (it sends " + type + ")");
            }
            byte[] b = readUpTo(c.getInputStream(), 4096);
            if (b.length < 1024) {
                throw new IOException("no sound came from that stream");
            }
            String codec = type.contains("aac") ? "AAC" : type.contains("ogg") ? "OGG" : "MP3";
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

    private static String p(String q, String k) {
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
                case "dots": {
                    StringBuilder sb = new StringBuilder();
                    int n = 0;
                    synchronized (this) {
                        for (Station s : stations.values()) {
                            if ("active".equals(s.status)) {
                                sb.append(n++ > 0 ? ";" : "").append(s.id).append(',').append(s.lat).append(',').append(s.lon).append(',')
                                        .append("rb".equals(s.source) ? 0 : 1);
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
                    Station s;
                    synchronized (this) {
                        s = stations.get((long) num(p(query, "id"), 1, Long.MAX_VALUE));
                    }
                    if (s == null || !"active".equals(s.status)) {
                        throw new Bad(404, "this station is no longer on the map");
                    }
                    send(ex, 200, json(s), "no-store");
                    return;
                }
                case "listen":
                    listen(ex, query);
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
                    Station s = stations.get((long) num(p(form, "id"), 1, Long.MAX_VALUE));
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
                        Station s = stations.get((long) num(p(form, "id"), 1, Long.MAX_VALUE));
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
        long now = clock.getAsLong();
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
                if ("active".equals(s.status) && (s.url.equalsIgnoreCase(url) || (s.name.equalsIgnoreCase(name) && dist(lat, lon, s.lat, s.lon) < 30))) {
                    throw new Bad(409, s.name + " is on the map already");
                }
            }
        }
        String[] ok;
        try {
            ok = check(url);
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
        synchronized (this) {
            s.id = nextId++;
            stations.put(s.id, s);
            dirty = true;
        }
        flushIfDirty();
        System.out.println("[world] " + me.username + " added " + s.name + " (" + s.city + ", " + s.country + ")");
        return json(s);
    }

    /** http:// streams through the server - only stations on the map, a limited number at a time. */
    private void listen(HttpExchange ex, String query) throws IOException, Bad {
        Station s;
        synchronized (this) {
            s = stations.get((long) num(p(query, "id"), 1, Long.MAX_VALUE));
        }
        if (s == null || !"active".equals(s.status)) {
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

    // ---- the super-admin ----

    private void handleAdmin(HttpExchange ex) throws IOException {
        try {
            if (!SuperAgent.isAdmin(ex, sessions)) {
                throw new Bad(403, "super-admin only");
            }
            String op = ex.getRequestURI().getPath().substring("/api/admin/world/".length());
            boolean post = "POST".equalsIgnoreCase(ex.getRequestMethod());
            if (op.equals("status")) {
                List<Station> mine = new ArrayList<Station>();
                synchronized (this) {
                    for (Station s : stations.values()) {
                        if (!"rb".equals(s.source) || s.reports > 0 || !"active".equals(s.status)) {
                            mine.add(s);
                        }
                    }
                }
                Collections.reverse(mine);
                if (mine.size() > 300) {
                    mine = mine.subList(0, 300);
                }
                String body;
                synchronized (this) {
                    body = "{\"total\":" + count(null) + ",\"members\":" + count("member") + ",\"lastImport\":" + lastImport
                            + ",\"importNote\":" + q(lastImportNote) + ",\"relaysNow\":" + relays.get() + ",\"stations\":" + listJson(mine) + "}";
                }
                send(ex, 200, body, "no-store");
                return;
            }
            if (!post) {
                throw new Bad(405, "use POST");
            }
            if (op.equals("import")) {
                int n = importRadioBrowser();
                send(ex, n < 0 ? 502 : 200, n < 0 ? "{\"error\":" + q(lastImportNote) + "}" : "{\"imported\":" + n + "}", "no-store");
                return;
            }
            String form = SuperAgent.readBody(ex, 4096);
            Station s;
            synchronized (this) {
                s = stations.get((long) num(p(form, "id"), 1, Long.MAX_VALUE));
                if (s == null) {
                    throw new Bad(404, "no such station");
                }
                if (op.equals("remove")) {
                    s.status = "removed";
                } else if (op.equals("restore")) {
                    s.status = "active";
                    s.reports = 0;
                } else {
                    throw new Bad(404, "not found");
                }
                dirty = true;
            }
            flushIfDirty();
            send(ex, 200, json(s), "no-store");
        } catch (Bad e) {
            send(ex, e.status, "{\"error\":" + q(e.getMessage()) + "}", "no-store");
        } finally {
            ex.close();
        }
    }

    // ---- JSON ----

    private static String q(String s) {
        return ProfileApi.quote(s == null ? "" : s);
    }

    private String json(Station s) {
        boolean https = s.url.toLowerCase(Locale.ROOT).startsWith("https://");
        return "{\"id\":" + s.id + ",\"name\":" + q(s.name) + ",\"play\":" + q(https ? s.url : "/api/world/listen?id=" + s.id)
                + ",\"home\":" + q(s.home) + ",\"tags\":" + q(s.tags) + ",\"city\":" + q(s.city) + ",\"country\":" + q(s.country)
                + ",\"cc\":" + q(s.cc) + ",\"lang\":" + q(s.lang) + ",\"lat\":" + s.lat + ",\"lon\":" + s.lon + ",\"codec\":" + q(s.codec)
                + ",\"bitrate\":" + s.bitrate + ",\"source\":" + q(s.source) + ",\"addedBy\":" + q(s.addedBy) + ",\"status\":" + q(s.status)
                + ",\"plays\":" + s.plays + ",\"reports\":" + s.reports + ",\"created\":" + s.created + "}";
    }

    private String listJson(List<Station> list) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < list.size(); i++) {
            sb.append(i > 0 ? "," : "").append(json(list.get(i)));
        }
        return sb.append(']').toString();
    }

    private static String placeJson(String[] g) {
        return "{\"lat\":" + g[0] + ",\"lon\":" + g[1] + ",\"city\":" + q(g[2]) + ",\"country\":" + q(g[3]) + ",\"cc\":" + q(g[4]) + "}";
    }

    private static void send(HttpExchange ex, int status, String body, String cache) throws IOException {
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