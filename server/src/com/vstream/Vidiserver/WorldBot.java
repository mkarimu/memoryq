package com.vstream.Vidiserver;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The World Radio station finder - Memoryq's own bot. One job at a time, in the background:
 *   rb        every day   Radio Browser (radio-browser.info): the most listened-to stations that have a place
 *   wikidata  every day   Wikidata (open data behind Wikipedia): stations with a "streaming media URL" (P963)
 *   icecast   every week  the Icecast directory (dir.xiph.org/yp.xml); the country comes from the address (.tz, .de)
 *                         or from where the server is (DB-IP), the dot goes in that country (marked "approximate")
 *   check     every night every station is played for a moment; silent 3 nights running = hidden (back when it plays);
 *                         broken ones are repaired: a new stream address is looked for on the station's website, then
 *                         on its old copies in the Wayback Machine (web.archive.org)
 *   list      on request  a list of station names (e.g. the TCRA licence list): each one is looked for in Radio Browser
 *                         and Wikidata
 * A station found on Wikidata, the Icecast directory or a list goes on the globe only after its stream played.
 */
public final class WorldBot {

    static final long DAY = 86400000L;
    static final String[] JOBS = {"rb", "wikidata", "check", "icecast"};

    private final WorldRadio wr;
    private final Function<String, String> ipCountry;
    private final Deque<String> log = new ArrayDeque<String>();
    private final Map<Long, Long> repaired = new HashMap<Long, Long>();
    private final List<String> notFound = new ArrayList<String>();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "world-bot");
        t.setDaemon(true);
        return t;
    });
    private ScheduledExecutorService timer;
    private volatile String running = "";
    private volatile boolean stopping;
    private final AtomicInteger done = new AtomicInteger();
    private volatile int total;
    int threads = 8;
    long politeGapMs = 1100;

    public WorldBot(WorldRadio wr, Function<String, String> ipCountry) {
        this.wr = wr;
        this.ipCountry = ipCountry;
        wr.setBot(this);
    }

    String running() {
        return running;
    }

    void stop() {
        stopping = true;
    }

    private void note(String line) {
        synchronized (log) {
            log.addLast(new java.text.SimpleDateFormat("MM-dd HH:mm").format(new java.util.Date(wr.now())) + "  " + line);
            while (log.size() > 300) {
                log.removeFirst();
            }
        }
        System.out.println("[world-bot] " + line);
    }

    // ---- running jobs ----

    /** Starts a job in the background; false when another one is running. */
    synchronized boolean start(String job, String names, String cc) {
        if (!running.isEmpty() || !(job.equals("list") || java.util.Arrays.asList(JOBS).contains(job))) {
            return false;
        }
        running = job;
        worker.submit(() -> {
            try {
                runNow(job, names, cc);
            } catch (RuntimeException e) {
                note(job + " stopped: " + e);
            }
        });
        return true;
    }

    /** Runs a job here and now (the tests call this). */
    void runNow(String job, String names, String cc) {
        running = job;
        stopping = false;
        done.set(0);
        total = 0;
        try {
            switch (job) {
                case "rb": {
                    note("Radio Browser: fetching the list...");
                    int n = wr.importRadioBrowser();
                    note(n < 0 ? "Radio Browser: could not be reached - will try again" : "Radio Browser: " + n + " stations up to date");
                    break;
                }
                case "wikidata":
                    wikidata();
                    break;
                case "icecast":
                    icecast();
                    break;
                case "check":
                    checkAll();
                    break;
                case "list":
                    list(names == null ? "" : names, cc == null ? "" : cc);
                    break;
                default:
                    break;
            }
        } finally {
            running = "";
            wr.flushIfDirty();
        }
    }

    /** Every 10 minutes: the next job that is due. */
    public synchronized void startSchedule() {
        if (timer != null) {
            return;
        }
        timer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "world-bot-timer");
            t.setDaemon(true);
            return t;
        });
        timer.scheduleWithFixedDelay(() -> {
            try {
                String due = due();
                if (due != null) {
                    start(due, null, null);
                }
            } catch (RuntimeException e) {
                System.out.println("[world-bot] " + e);
            }
        }, 60, 600, TimeUnit.SECONDS);
        timer.scheduleWithFixedDelay(wr::flushIfDirty, 2, 2, TimeUnit.MINUTES);
        timer.scheduleWithFixedDelay(wr::syncMemoryq, 20, 120, TimeUnit.SECONDS); // Memoryq's own stations
    }

    static long period(String job) {
        return job.equals("icecast") ? 7 * DAY : DAY - 30 * 60000L;
    }

    boolean enabled(String job) {
        return job.equals("rb") || !"off".equals(wr.setting(job.equals("check") ? "checker" : job));
    }

    String due() {
        if (!running.isEmpty()) {
            return null;
        }
        for (String j : JOBS) {
            if (enabled(j) && wr.now() - wr.jobLast(j) >= period(j)) {
                return j;
            }
        }
        return null;
    }

    // ---- testing many streams at once ----

    interface Each {
        void run(WorldRadio.Candidate k) throws Exception;
    }

    private void parallel(List<WorldRadio.Candidate> list, Each each) {
        total = list.size();
        done.set(0);
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, threads), r -> {
            Thread t = new Thread(r, "world-bot-test");
            t.setDaemon(true);
            return t;
        });
        for (WorldRadio.Candidate k : list) {
            pool.submit(() -> {
                if (stopping) {
                    return;
                }
                try {
                    each.run(k);
                } catch (Exception e) {
                    // counted by the caller
                } finally {
                    done.incrementAndGet();
                }
            });
        }
        pool.shutdown();
        try {
            pool.awaitTermination(6, TimeUnit.HOURS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Tests the new ones and puts those that play on the globe; returns {added, no sound, already known, no place}. */
    private int[] testAndAdd(List<WorldRadio.Candidate> found, int max) {
        List<WorldRadio.Candidate> fresh = new ArrayList<WorldRadio.Candidate>();
        int known = 0;
        Set<String> seen = new LinkedHashSet<String>();
        for (WorldRadio.Candidate k : found) {
            if (k.url.isEmpty() || !seen.add(k.url.toLowerCase(Locale.ROOT))) {
                continue;
            }
            if ((!k.key.isEmpty() && wr.knownKey(k.key)) || wr.knownUrl(k.url)) {
                known++;
                continue;
            }
            if (fresh.size() < max) {
                fresh.add(k);
            }
        }
        AtomicInteger added = new AtomicInteger(), silent = new AtomicInteger(), noplace = new AtomicInteger();
        parallel(fresh, k -> {
            String[] r;
            try {
                r = wr.tester().test(k.url);
            } catch (IOException e) {
                silent.incrementAndGet();
                return;
            }
            String res = wr.upsert(k, r);
            if (res.equals("added")) {
                added.incrementAndGet();
            } else if (res.equals("noplace")) {
                noplace.incrementAndGet();
            }
        });
        return new int[]{added.get(), silent.get(), known, noplace.get()};
    }

    // ---- Wikidata ----

    static final String SPARQL = "SELECT ?s ?sLabel ?stream ?home ?coord ?cityLabel ?cityCoord ?cc ?genreLabel WHERE {"
            + " ?s wdt:P963 ?stream ."
            + " OPTIONAL { ?s wdt:P856 ?home } OPTIONAL { ?s wdt:P625 ?coord }"
            + " OPTIONAL { ?s wdt:P131 ?city . OPTIONAL { ?city wdt:P625 ?cityCoord } }"
            + " OPTIONAL { ?s wdt:P17 ?c . ?c wdt:P297 ?cc } OPTIONAL { ?s wdt:P136 ?genre }"
            + " SERVICE wikibase:label { bd:serviceParam wikibase:language \"en,fr,es,de,pt,sw,it,nl\". } } LIMIT ";

    static double[] point(String wkt) {
        Matcher m = Pattern.compile("Point\\(\\s*(-?[0-9.]+)\\s+(-?[0-9.]+)\\s*\\)").matcher(wkt == null ? "" : wkt);
        return m.find() ? new double[]{Double.parseDouble(m.group(2)), Double.parseDouble(m.group(1))} : null;
    }

    static List<WorldRadio.Candidate> parseWikidata(String body) {
        Object rows = MiniJson.at(MiniJson.parse(body), "results", "bindings");
        Map<String, WorldRadio.Candidate> by = new LinkedHashMap<String, WorldRadio.Candidate>();
        if (!(rows instanceof List)) {
            return new ArrayList<WorldRadio.Candidate>();
        }
        for (Object r : (List<?>) rows) {
            String s = MiniJson.str(r, "s", "value");
            String id = s.substring(s.lastIndexOf('/') + 1);
            String stream = MiniJson.str(r, "stream", "value");
            String low = stream.toLowerCase(Locale.ROOT);
            if (id.isEmpty() || !low.matches("https?://.+") || low.contains(".m3u8") || low.contains("youtube.") || low.contains("youtu.be")
                    || low.contains("facebook.") || low.contains("twitch.")) {
                continue;
            }
            WorldRadio.Candidate k = by.get(id);
            if (k == null) {
                k = new WorldRadio.Candidate();
                k.key = "wd:" + id;
                k.source = "wikidata";
                k.name = MiniJson.str(r, "sLabel", "value");
                k.url = stream;
                by.put(id, k);
            }
            if (k.home.isEmpty()) {
                k.home = MiniJson.str(r, "home", "value");
            }
            if (k.cc.isEmpty()) {
                k.cc = MiniJson.str(r, "cc", "value").toUpperCase(Locale.ROOT);
            }
            if (k.city.isEmpty()) {
                String city = MiniJson.str(r, "cityLabel", "value");
                k.city = city.matches("Q\\d+") ? "" : city;
            }
            double[] pt = point(MiniJson.str(r, "coord", "value"));
            if (pt != null) {
                k.lat = pt[0];
                k.lon = pt[1];
                k.exact = true;
            } else if (Double.isNaN(k.lat)) {
                pt = point(MiniJson.str(r, "cityCoord", "value"));
                if (pt != null) {
                    k.lat = pt[0];
                    k.lon = pt[1];
                }
            }
            String g = MiniJson.str(r, "genreLabel", "value");
            if (!g.isEmpty() && !g.matches("Q\\d+") && k.tags.split(",").length < 3 && !k.tags.contains(g.toLowerCase(Locale.ROOT))) {
                k.tags = (k.tags.isEmpty() ? "" : k.tags + ",") + g.toLowerCase(Locale.ROOT);
            }
        }
        List<WorldRadio.Candidate> out = new ArrayList<WorldRadio.Candidate>();
        for (WorldRadio.Candidate k : by.values()) {
            if (k.name.isEmpty() || k.name.matches("Q\\d+")) {
                continue;
            }
            if (Double.isNaN(k.lat)) {
                placeInCountry(k);
            }
            out.add(k);
        }
        return out;
    }

    /** No town known: the middle of the country, a little apart for each station (marked approximate). */
    static void placeInCountry(WorldRadio.Candidate k) {
        String[] row = WorldRadio.COUNTRY_BY_CODE.get(k.cc);
        if (row == null) {
            return;
        }
        int h = Math.abs((k.url + k.name).hashCode());
        k.lat = Double.parseDouble(row[2]) + ((h % 1000) / 1000.0 - 0.5) * 2.0;
        k.lon = Double.parseDouble(row[3]) + (((h / 1000) % 1000) / 1000.0 - 0.5) * 2.6;
        k.exact = false;
        if (k.country.isEmpty()) {
            k.country = row[0];
        }
    }

    private void wikidata() {
        note("Wikidata: asking for radio stations with a stream address...");
        String body = null;
        for (int limit : new int[]{40000, 12000}) {
            try {
                body = wr.fetcher().get("https://query.wikidata.org/sparql?format=json&query=" + URLEncoder.encode(SPARQL + limit, "UTF-8"));
                break;
            } catch (IOException e) {
                note("Wikidata: " + e.getMessage() + (limit > 12000 ? " - trying a smaller question" : ""));
            }
        }
        if (body == null) {
            wr.jobDone("wikidata", "could not reach Wikidata");
            return;
        }
        List<WorldRadio.Candidate> found;
        try {
            found = parseWikidata(body);
        } catch (RuntimeException e) {
            wr.jobDone("wikidata", "Wikidata sent something unreadable");
            return;
        }
        note("Wikidata: " + found.size() + " stations with a stream - testing the new ones...");
        int[] r = testAndAdd(found, intSetting("wdMax", 4000));
        String msg = found.size() + " found, " + r[0] + " new on the globe, " + r[3] + " without a place, " + r[1] + " did not play, " + r[2] + " known";
        note("Wikidata: " + msg);
        wr.jobDone("wikidata", msg);
    }

    private int intSetting(String k, int def) {
        try {
            return Integer.parseInt(wr.setting(k));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    // ---- the Icecast directory ----

    static final Set<String> GENERIC_TLD = new java.util.HashSet<String>(java.util.Arrays.asList(
            "fm", "am", "tv", "io", "me", "co", "cc", "ws", "to", "ly", "ai", "gg", "la", "nu", "ag", "sc", "vc", "eu", "su", "mu", "st", "is", "it"));

    String countryOfHost(String url) {
        try {
            String host = new URL(url).getHost().toLowerCase(Locale.ROOT);
            String tld = host.substring(host.lastIndexOf('.') + 1);
            if (tld.equals("uk")) {
                tld = "gb";
            }
            if (tld.matches("[a-z]{2}") && !GENERIC_TLD.contains(tld) && WorldRadio.COUNTRY_BY_CODE.containsKey(tld.toUpperCase(Locale.ROOT))) {
                return tld.toUpperCase(Locale.ROOT);
            }
            if (ipCountry != null) {
                String cc = ipCountry.apply(InetAddress.getByName(host).getHostAddress());
                if (cc != null && cc.matches("[A-Z]{2}") && WorldRadio.COUNTRY_BY_CODE.containsKey(cc)) {
                    return cc;
                }
            }
        } catch (Exception e) {
            // not known
        }
        return "";
    }

    static String tag(String xml, String name) {
        Matcher m = Pattern.compile("<" + name + ">(.*?)</" + name + ">", Pattern.DOTALL).matcher(xml);
        return m.find() ? unxml(m.group(1)).trim() : "";
    }

    static String unxml(String s) {
        return s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&#39;", "'").replace("&amp;", "&");
    }

    static List<WorldRadio.Candidate> parseIcecast(String xml) {
        List<WorldRadio.Candidate> out = new ArrayList<WorldRadio.Candidate>();
        Matcher m = Pattern.compile("<entry>(.*?)</entry>", Pattern.DOTALL).matcher(xml);
        while (m.find()) {
            String e = m.group(1);
            WorldRadio.Candidate k = new WorldRadio.Candidate();
            k.name = tag(e, "server_name");
            k.url = tag(e, "listen_url");
            k.tags = tag(e, "genre").replace(' ', ',').replaceAll(",+", ",").toLowerCase(Locale.ROOT);
            String type = tag(e, "server_type").toLowerCase(Locale.ROOT);
            try {
                k.bitrate = Integer.parseInt(tag(e, "bitrate"));
            } catch (NumberFormatException x) {
                k.bitrate = 0;
            }
            k.source = "icecast";
            k.key = "ic:" + k.url.toLowerCase(Locale.ROOT);
            if (k.name.length() < 3 || k.name.matches("(?i).*(unspecified|no name|test stream|^stream$|^untitled).*") || !k.url.matches("https?://.+")
                    || type.startsWith("video") || (k.bitrate > 0 && k.bitrate < 24)) {
                continue;
            }
            if (k.tags.length() > 80) {
                k.tags = k.tags.substring(0, 80);
            }
            out.add(k);
        }
        return out;
    }

    private void icecast() {
        note("Icecast directory: fetching...");
        String xml;
        try {
            xml = wr.fetcher().get("https://dir.xiph.org/yp.xml");
        } catch (IOException e) {
            wr.jobDone("icecast", "could not reach dir.xiph.org: " + e.getMessage());
            note("Icecast directory: could not be reached");
            return;
        }
        List<WorldRadio.Candidate> all = parseIcecast(xml);
        List<WorldRadio.Candidate> placed = new ArrayList<WorldRadio.Candidate>();
        int nowhere = 0;
        for (WorldRadio.Candidate k : all) {
            if (stopping) {
                break;
            }
            if (wr.knownKey(k.key) || wr.knownUrl(k.url)) {
                placed.add(k); // counted as known
                continue;
            }
            k.cc = countryOfHost(k.url);
            if (k.cc.isEmpty()) {
                nowhere++;
                continue;
            }
            placeInCountry(k);
            placed.add(k);
        }
        note("Icecast directory: " + all.size() + " streams, " + nowhere + " with no country - testing the new ones...");
        int[] r = testAndAdd(placed, intSetting("icMax", 3000));
        String msg = all.size() + " in the directory, " + r[0] + " new on the globe, " + r[1] + " did not play, " + r[2] + " known, " + nowhere + " country unknown";
        note("Icecast directory: " + msg);
        wr.jobDone("icecast", msg);
    }

    // ---- the nightly check, and repairs ----

    private void checkAll() {
        List<WorldRadio.Station> todo = new ArrayList<WorldRadio.Station>();
        long now = wr.now();
        for (WorldRadio.Station s : wr.snapshot()) {
            if (("active".equals(s.status) || "broken".equals(s.status)) && now - s.lastCheck > 20 * 3600000L) {
                todo.add(s);
            }
        }
        note("Checking " + todo.size() + " streams...");
        List<WorldRadio.Candidate> list = new ArrayList<WorldRadio.Candidate>();
        Map<String, Long> ids = new HashMap<String, Long>();
        for (WorldRadio.Station s : todo) {
            WorldRadio.Candidate k = new WorldRadio.Candidate();
            k.url = s.url;
            k.key = String.valueOf(s.id);
            ids.put(k.key, s.id);
            list.add(k);
        }
        AtomicInteger ok = new AtomicInteger(), bad = new AtomicInteger();
        parallel(list, k -> {
            long id = ids.get(k.key);
            try {
                String[] r = wr.tester().test(k.url);
                wr.checked(id, r[0], r[1], null);
                ok.incrementAndGet();
            } catch (IOException e) {
                wr.checked(id, null, null, e.getMessage());
                bad.incrementAndGet();
            }
        });
        int fixed = 0, tried = 0;
        if (!"off".equals(wr.setting("repair")) && !stopping) {
            for (WorldRadio.Station s : wr.snapshot()) {
                if (stopping || tried >= intSetting("repairMax", 200)) {
                    break;
                }
                Long last = repaired.get(s.id);
                if (!"broken".equals(s.status) || s.home.isEmpty() || (last != null && now - last < 7 * DAY)) {
                    continue;
                }
                repaired.put(s.id, now);
                tried++;
                if (repair(s)) {
                    fixed++;
                }
            }
        }
        String msg = ok.get() + " playing, " + bad.get() + " silent; repairs: " + fixed + " of " + tried + " broken stations fixed";
        note("Check: " + msg);
        wr.jobDone("check", msg);
    }

    private static final Pattern LINK = Pattern.compile("https?://[^\\s\"'<>()\\\\\\[\\]{}]+", Pattern.CASE_INSENSITIVE);

    /** Stream-looking addresses in a web page (players, "listen live" links, playlists). */
    static List<String> streamLinks(String html) {
        Set<String> out = new LinkedHashSet<String>();
        Matcher m = LINK.matcher(html.replace("\\/", "/").replace("&amp;", "&"));
        while (m.find() && out.size() < 12) {
            String u = m.group().replaceAll("[.,;]+$", "");
            String l = u.toLowerCase(Locale.ROOT);
            if (l.contains(".m3u8") || l.matches(".*\\.(jpg|jpeg|png|gif|svg|webp|css|js|html?|php|asp|ico|woff2?|xml|json)(\\?.*)?$")
                    || l.matches("https?://[^/]*(youtube|facebook|twitter|instagram|google|gstatic|wordpress\\.org|w3\\.org|schema\\.org|whatsapp|tiktok)\\..*")) {
                continue;
            }
            if (l.matches(".*\\.(mp3|aac|aacp|ogg|opus|pls|m3u)(\\?.*)?$") || l.matches("https?://[^/]+:\\d{2,5}(/.*)?") || l.contains("/stream")
                    || l.contains("/live") || l.contains("/;") || l.contains("icecast") || l.contains("shoutcast") || l.contains("zeno.fm")
                    || l.contains("streamtheworld") || l.contains("radio.co") || l.contains("radioking") || l.contains("listen")) {
                out.add(u);
            }
        }
        return new ArrayList<String>(out);
    }

    private boolean tryLinks(WorldRadio.Station s, List<String> links, String how) {
        for (String u : links) {
            if (u.equalsIgnoreCase(s.url)) {
                continue;
            }
            try {
                String[] r = wr.tester().test(u);
                if (wr.knownUrl(r[0]) && !r[0].equalsIgnoreCase(s.url)) {
                    continue; // another station's stream
                }
                wr.checked(s.id, r[0], r[1], how);
                note("Repaired " + s.name + " (#" + s.id + "): " + how);
                return true;
            } catch (IOException e) {
                // the next one
            }
        }
        return false;
    }

    private void politePause() {
        if (politeGapMs > 0) {
            try {
                Thread.sleep(politeGapMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** A broken station: a new stream address from its website today, else from the Wayback Machine's copies. */
    boolean repair(WorldRadio.Station s) {
        try {
            if (tryLinks(s, streamLinks(wr.fetcher().get(s.home)), "a new stream address was found on its website")) {
                return true;
            }
        } catch (IOException e) {
            // the website is down too: the Wayback Machine
        }
        try {
            politePause();
            String cdx = wr.fetcher().get("https://web.archive.org/cdx/search/cdx?output=json&fl=timestamp,original&filter=statuscode:200&limit=-3&url="
                    + URLEncoder.encode(s.home, "UTF-8"));
            Object rows = MiniJson.parse(cdx);
            if (!(rows instanceof List)) {
                return false;
            }
            List<?> l = (List<?>) rows;
            for (int i = l.size() - 1; i >= 1 && i >= l.size() - 2; i--) { // the newest copies first (row 0 is the header)
                String ts = MiniJson.str(l.get(i), 0), orig = MiniJson.str(l.get(i), 1);
                if (!ts.matches("\\d{8,14}")) {
                    continue;
                }
                politePause();
                String html = wr.fetcher().get("https://web.archive.org/web/" + ts + "id_/" + orig);
                if (tryLinks(s, streamLinks(html), "repaired from the Wayback Machine (its website on " + ts.substring(0, 4) + "-" + ts.substring(4, 6) + "-" + ts.substring(6, 8) + ")")) {
                    return true;
                }
            }
        } catch (Exception e) {
            // not this time
        }
        return false;
    }

    // ---- a list of names (e.g. the TCRA licence list) ----

    private void list(String names, String cc) {
        List<String[]> lines = new ArrayList<String[]>();
        for (String raw : names.split("\\r?\\n")) {
            String l = raw.replaceAll("^\\s*\\d+[.)]?\\s+", "").trim();
            if (l.length() < 3 || l.length() > 160) {
                continue;
            }
            String[] parts = l.split("\\s+-\\s+|\\t|\\s*,\\s*|\\s*\\|\\s*", 2);
            lines.add(new String[]{parts[0].trim(), parts.length > 1 ? parts[1].trim() : ""});
        }
        String[] crow = WorldRadio.COUNTRY_BY_CODE.get(cc);
        total = lines.size();
        done.set(0);
        synchronized (notFound) {
            notFound.clear();
        }
        int added = 0, known = 0, missing = 0;
        note("List: looking for " + lines.size() + " stations" + (crow == null ? "" : " in " + crow[0]) + "...");
        for (String[] ln : lines) {
            if (stopping) {
                break;
            }
            done.incrementAndGet();
            String name = ln[0], region = ln[1];
            WorldRadio.Station have = wr.findByName(name, cc);
            if (have != null) {
                known++;
                note("  already on the list: " + name + " (#" + have.id + ", " + have.status + ")");
                continue;
            }
            String result = findOne(name, region, cc, crow);
            if (result.startsWith("added")) {
                added++;
            } else {
                missing++;
                synchronized (notFound) {
                    notFound.add(name + (region.isEmpty() ? "" : " - " + region));
                }
            }
            note("  " + name + ": " + result);
        }
        String msg = lines.size() + " names: " + added + " added, " + known + " already there, " + missing + " not found (add those by hand)";
        note("List: " + msg);
        wr.jobDone("list", msg);
    }

    private String findOne(String name, String region, String cc, String[] crow) {
        List<WorldRadio.Candidate> cands = new ArrayList<WorldRadio.Candidate>();
        String want = WorldRadio.norm(name);
        try {
            Object o = MiniJson.parse(wr.rbGet("/json/stations/byname/" + URLEncoder.encode(name, "UTF-8").replace("+", "%20") + "?hidebroken=true&limit=10"));
            if (o instanceof List) {
                for (Object r : (List<?>) o) {
                    WorldRadio.Candidate k = WorldRadio.fromRb(r);
                    if (!k.url.isEmpty() && (cc.isEmpty() || cc.equalsIgnoreCase(k.cc)) && similar(want, WorldRadio.norm(k.name))) {
                        k.source = "list";
                        cands.add(k);
                    }
                }
            }
        } catch (Exception e) {
            // then Wikidata
        }
        try {
            politePause();
            Object o = MiniJson.parse(wr.fetcher().get("https://www.wikidata.org/w/api.php?action=wbsearchentities&format=json&language=en&type=item&limit=3&search="
                    + URLEncoder.encode(name, "UTF-8")));
            Object hits = MiniJson.at(o, "search");
            if (hits instanceof List) {
                for (Object h : (List<?>) hits) {
                    String id = MiniJson.str(h, "id");
                    if (!id.matches("Q\\d+") || !similar(want, WorldRadio.norm(MiniJson.str(h, "label")))) {
                        continue;
                    }
                    politePause();
                    Object ent = MiniJson.at(MiniJson.parse(wr.fetcher().get("https://www.wikidata.org/wiki/Special:EntityData/" + id + ".json")), "entities", id);
                    String stream = MiniJson.str(ent, "claims", "P963", 0, "mainsnak", "datavalue", "value");
                    if (stream.isEmpty()) {
                        continue;
                    }
                    WorldRadio.Candidate k = new WorldRadio.Candidate();
                    k.key = "wd:" + id;
                    k.source = "list";
                    k.name = MiniJson.str(h, "label");
                    k.url = stream;
                    k.home = MiniJson.str(ent, "claims", "P856", 0, "mainsnak", "datavalue", "value");
                    Object lat = MiniJson.at(ent, "claims", "P625", 0, "mainsnak", "datavalue", "value", "latitude");
                    if (lat instanceof Number) {
                        k.lat = ((Number) lat).doubleValue();
                        k.lon = ((Number) MiniJson.at(ent, "claims", "P625", 0, "mainsnak", "datavalue", "value", "longitude")).doubleValue();
                    }
                    cands.add(k);
                }
            }
        } catch (Exception e) {
            // nothing more
        }
        if (cands.isEmpty()) {
            return "not found in Radio Browser or Wikidata";
        }
        for (WorldRadio.Candidate k : cands) {
            if (wr.knownUrl(k.url) || (!k.key.isEmpty() && wr.knownKey(k.key))) {
                return "already on the list (same stream)";
            }
            String[] r;
            try {
                r = wr.tester().test(k.url);
            } catch (IOException e) {
                continue;
            }
            k.name = name; // the name as the list writes it
            if (!cc.isEmpty()) {
                k.cc = cc;
                k.country = crow == null ? k.country : crow[0];
            }
            if (Double.isNaN(k.lat) || !region.isEmpty() && k.city.isEmpty()) {
                String[] g = region.isEmpty() ? null : wr.geocode(region + (crow == null ? "" : ", " + crow[0]));
                if (g != null) {
                    k.lat = Double.parseDouble(g[0]);
                    k.lon = Double.parseDouble(g[1]);
                    k.city = g[2].isEmpty() ? region : g[2];
                    k.exact = !"0".equals(g[5]);
                } else if (Double.isNaN(k.lat)) {
                    placeInCountry(k);
                    k.city = region;
                }
            }
            String res = wr.upsert(k, r);
            return res.equals("added") ? "added - its stream plays" + (k.exact ? "" : " (place approximate - set it in All stations)") : res;
        }
        return "found, but none of its stream addresses played";
    }

    static boolean similar(String a, String b) {
        return !a.isEmpty() && !b.isEmpty() && (a.equals(b) || a.contains(b) || b.contains(a));
    }

    // ---- for the admin page ----

    String statusJson() {
        StringBuilder sb = new StringBuilder("{\"running\":").append(WorldRadio.q(running)).append(",\"done\":").append(done.get())
                .append(",\"total\":").append(total).append(",\"jobs\":[");
        String[] all = {"rb", "wikidata", "icecast", "check", "list"};
        for (int i = 0; i < all.length; i++) {
            String j = all[i];
            String[] last = wr.jobs.get(j);
            long when = wr.jobLast(j);
            sb.append(i > 0 ? "," : "").append("{\"job\":").append(WorldRadio.q(j)).append(",\"last\":").append(when).append(",\"note\":")
                    .append(WorldRadio.q(last == null ? "not run yet" : last[1])).append(",\"enabled\":").append(j.equals("list") || enabled(j))
                    .append(",\"next\":").append(j.equals("list") ? 0 : when + period(j)).append('}');
        }
        sb.append("],\"log\":[");
        synchronized (log) {
            int i = 0;
            for (String l : log) {
                sb.append(i++ > 0 ? "," : "").append(WorldRadio.q(l));
            }
        }
        sb.append("],\"notFound\":[");
        synchronized (notFound) {
            for (int i = 0; i < notFound.size(); i++) {
                sb.append(i > 0 ? "," : "").append(WorldRadio.q(notFound.get(i)));
            }
        }
        return sb.append("]}").toString();
    }
}