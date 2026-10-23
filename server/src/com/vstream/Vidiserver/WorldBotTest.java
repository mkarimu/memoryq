package com.vstream.Vidiserver;

import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

/** The station finder bot (Wikidata, Icecast, lists, the nightly check and repairs) and Admin -> Global Radio. */
public final class WorldBotTest {

    private static int failures = 0;

    public static void main(String[] args) {
        try {
            run();
        } catch (Throwable t) {
            t.printStackTrace();
            System.out.println("FAILED: " + t);
            System.exit(1);
        }
    }

    private static String bind(String s, String label, String stream, String coord, String cc, String city, String cityCoord, String genre) {
        StringBuilder b = new StringBuilder("{\"s\":{\"type\":\"uri\",\"value\":\"http://www.wikidata.org/entity/" + s + "\"},\"sLabel\":{\"value\":\"" + label
                + "\"},\"stream\":{\"value\":\"" + stream + "\"}");
        if (coord != null) {
            b.append(",\"coord\":{\"value\":\"Point(" + coord + ")\"}");
        }
        if (cc != null) {
            b.append(",\"cc\":{\"value\":\"" + cc + "\"}");
        }
        if (city != null) {
            b.append(",\"cityLabel\":{\"value\":\"" + city + "\"}");
        }
        if (cityCoord != null) {
            b.append(",\"cityCoord\":{\"value\":\"Point(" + cityCoord + ")\"}");
        }
        if (genre != null) {
            b.append(",\"genreLabel\":{\"value\":\"" + genre + "\"}");
        }
        return b.append('}').toString();
    }

    private static void run() throws Exception {
        System.out.println("-- Reading the open lists --");
        String sparql = "{\"results\":{\"bindings\":[" + bind("Q1", "Radio Tanzania Dar", "https://wd.example/rtd.mp3", "39.28 -6.8", "TZ", "Dar es Salaam", null, "news") + ","
                + bind("Q1", "Radio Tanzania Dar", "https://wd.example/rtd.mp3", "39.28 -6.8", "TZ", "Dar es Salaam", null, "talk") + ","
                + bind("Q2", "Radio Mbeya", "https://wd.example/mbeya.mp3", null, "TZ", "Mbeya", "33.45 -8.9", null) + ","
                + bind("Q3", "Some Country Radio", "https://wd.example/country.mp3", null, "KE", null, null, null) + ","
                + bind("Q4", "Q4", "https://wd.example/nolabel.mp3", null, null, null, null, null) + ","
                + bind("Q5", "Video TV", "https://wd.example/live.m3u8", null, "KE", null, null, null) + ","
                + bind("Q6", "Silent FM", "https://wd.example/silent.mp3", "36.8 -1.3", "KE", "Nairobi", null, null) + "]}}";
        java.util.List<WorldRadio.Candidate> wd = WorldBot.parseWikidata(sparql);
        checkTrue("Wikidata: one station per item, genres joined, HLS and nameless left out (" + wd.size() + ")", wd.size() == 4
                && wd.get(0).tags.equals("news,talk") && wd.get(0).lat == -6.8 && wd.get(0).lon == 39.28);
        checkTrue("the town's place when the station has none", wd.get(1).lat == -8.9 && wd.get(1).exact);
        checkTrue("only the country known: in the country, marked approximate", !wd.get(2).exact && Math.abs(wd.get(2).lat - 0.5) < 2);
        String yp = "<directory><entry><server_name>Bongo &amp; Co Radio</server_name><listen_url>http://bongo.co.tz:8000/live</listen_url>"
                + "<server_type>audio/mpeg</server_type><bitrate>128</bitrate><genre>Bongo Taarab</genre></entry>"
                + "<entry><server_name>Unspecified name</server_name><listen_url>http://x.example:8000/a</listen_url><server_type>audio/mpeg</server_type></entry>"
                + "<entry><server_name>Berlin Jazz</server_name><listen_url>http://jazz.example.de/stream</listen_url><server_type>application/ogg</server_type><bitrate>96</bitrate><genre>jazz</genre></entry>"
                + "<entry><server_name>Nowhere Stream</server_name><listen_url>http://nowhere.example.com/s</listen_url><server_type>audio/mpeg</server_type></entry>"
                + "<entry><server_name>A Video</server_name><listen_url>http://v.example.fr/v</listen_url><server_type>video/webm</server_type></entry></directory>";
        java.util.List<WorldRadio.Candidate> ic = WorldBot.parseIcecast(yp);
        checkTrue("Icecast: real names only, no video (" + ic.size() + ")", ic.size() == 3 && ic.get(0).name.equals("Bongo & Co Radio") && ic.get(0).tags.equals("bongo,taarab"));
        String page = "<audio src=\"https://stream.zeno.fm/abc123\"></audio><a href=\"https://www.facebook.com/radio\">fb</a>"
                + "<script>var s={\"url\":\"http:\\/\\/s2.example.net:8000\\/live.mp3\"};</script><img src=\"https://x.example/logo.png\">";
        checkTrue("stream links on a web page (players, escaped scripts)", WorldBot.streamLinks(page).contains("https://stream.zeno.fm/abc123")
                && WorldBot.streamLinks(page).contains("http://s2.example.net:8000/live.mp3") && WorldBot.streamLinks(page).size() == 2);

        Path dir = Files.createTempDirectory("memoryq-worldbot");
        UserStore us = new UserStore(dir.resolve("users.txt"), new LiveChannelRegistry(), c -> { });
        us.init(dir.resolve("none.txt"));
        us.createUser("amina", "password123", UserStore.Role.CHATTER);
        SessionStore sessions = new SessionStore();
        String admin = sessions.create("admin", UserStore.Role.ADMIN);
        String amina = sessions.create("amina", UserStore.Role.CHATTER);
        long[] now = {1792700000000L};
        WorldRadio w = new WorldRadio(dir.resolve("world-radio.txt"), us, sessions, () -> now[0]);
        w.init();
        Set<String> silent = new HashSet<String>();
        silent.add("https://wd.example/silent.mp3");
        silent.add("http://nowhere.example.com/s");
        silent.add("https://old.example/broken.mp3");
        w.setTester(url -> {
            if (silent.contains(url) || url.contains("silent") || url.contains("dead")) {
                throw new IOException("no sound");
            }
            return new String[]{url.endsWith(".pls") ? url.replace(".pls", ".mp3") : url, "MP3"};
        });
        String rbByName = "[{\"stationuuid\":\"rb-uh\",\"name\":\"Uhuru FM\",\"url_resolved\":\"https://rb.example/uhuru.mp3\",\"countrycode\":\"TZ\",\"geo_lat\":null,\"geo_long\":null,\"hls\":0}]";
        w.setFetcher(url -> {
            if (url.contains("query.wikidata.org")) {
                return sparql;
            }
            if (url.contains("dir.xiph.org")) {
                return yp;
            }
            if (url.contains("radio-browser.info") && url.contains("byname/Uhuru")) {
                return rbByName;
            }
            if (url.contains("radio-browser.info")) {
                return "[]";
            }
            if (url.contains("wbsearchentities") && url.contains("Pambazuka")) {
                return "{\"search\":[{\"id\":\"Q77\",\"label\":\"Pambazuka FM\"}]}";
            }
            if (url.contains("wbsearchentities")) {
                return "{\"search\":[]}";
            }
            if (url.contains("EntityData/Q77")) {
                return "{\"entities\":{\"Q77\":{\"claims\":{\"P963\":[{\"mainsnak\":{\"datavalue\":{\"value\":\"https://pz.example/live.pls\"}}}],"
                        + "\"P625\":[{\"mainsnak\":{\"datavalue\":{\"value\":{\"latitude\":-3.4,\"longitude\":37.3}}}}]}}}}";
            }
            if (url.contains("nominatim") && url.contains("Morogoro")) {
                return "[{\"lat\":\"-6.82\",\"lon\":\"37.66\",\"address\":{\"city\":\"Morogoro\",\"country\":\"Tanzania\",\"country_code\":\"tz\"}}]";
            }
            if (url.contains("nominatim")) {
                return "[]";
            }
            if (url.equals("https://fixme.example/")) {
                return "<a href=\"https://fixed.example/new.mp3\">Listen live</a>";
            }
            if (url.equals("https://gone.example/")) {
                throw new IOException("HTTP 404");
            }
            if (url.contains("web.archive.org/cdx")) {
                return "[[\"timestamp\",\"original\"],[\"20190101000000\",\"https://gone.example/\"],[\"20230505120000\",\"https://gone.example/\"]]";
            }
            if (url.contains("web.archive.org/web/20230505120000id_/")) {
                return "<audio src=\"https://archived.example/radio.aac\"></audio>";
            }
            throw new IOException("offline " + url);
        });
        WorldBot bot = new WorldBot(w, ip -> "DE");
        bot.politeGapMs = 0;
        bot.threads = 3;

        System.out.println("-- The finder adds only streams that play --");
        bot.runNow("wikidata", null, null);
        checkTrue("Wikidata: 3 play and go on the globe, the silent one does not (" + w.count(null) + ")", w.count(null) == 3 && w.count("wikidata") == 3);
        checkTrue("grouped by country", w.search("", "TZ", null, 10).size() == 2 && w.search("", "KE", null, 10).size() == 1);
        bot.runNow("wikidata", null, null);
        checkTrue("running again changes nothing", w.count(null) == 3);
        bot.runNow("icecast", null, null);
        checkTrue("Icecast: the country from the address (.tz) or the server's place (DB-IP) (" + w.count("icecast") + ")",
                w.count("icecast") == 2 && w.search("bongo", "TZ", null, 5).size() == 1 && w.search("jazz", "DE", null, 5).size() == 1);

        System.out.println("-- A list of names (like the TCRA licence list) --");
        bot.runNow("list", "1. Uhuru FM - Morogoro\n2. Pambazuka FM\n3. Radio Tanzania Dar\n4. Nobody Knows FM", "TZ");
        WorldRadio.Station uh = w.findByName("Uhuru FM", "TZ");
        checkTrue("found in Radio Browser, its stream played, placed at its town (" + (uh == null ? "-" : uh.city + " " + uh.lat) + ")",
                uh != null && "active".equals(uh.status) && uh.city.equals("Morogoro") && uh.lat == -6.82);
        WorldRadio.Station pz = w.findByName("Pambazuka FM", "TZ");
        checkTrue("found in Wikidata (its playlist resolved)", pz != null && pz.url.equals("https://pz.example/live.mp3") && pz.lat == -3.4);
        String st = bot.statusJson();
        checkTrue("what was not found is listed for the super-admin (" + st.length() + ")", st.contains("\"notFound\":[\"Nobody Knows FM\"]") && st.contains("already on the list: Radio Tanzania Dar"));

        System.out.println("-- The nightly check --");
        WorldRadio.Candidate k = new WorldRadio.Candidate();
        k.name = "Fix Me FM";
        k.url = "https://old.example/broken.mp3";
        k.home = "https://fixme.example/";
        k.cc = "TZ";
        k.lat = -5;
        k.lon = 35;
        k.source = "admin";
        w.upsert(k, null);
        WorldRadio.Candidate k2 = new WorldRadio.Candidate();
        k2.name = "Gone FM";
        k2.url = "https://dead.example/gone.mp3";
        k2.home = "https://gone.example/";
        k2.cc = "KE";
        k2.lat = -1;
        k2.lon = 37;
        k2.source = "admin";
        w.upsert(k2, null);
        int before = w.count(null);
        for (int night = 1; night <= 3; night++) {
            now[0] += WorldBot.DAY;
            bot.runNow("check", null, null);
        }
        WorldRadio.Station fix = w.findByName("Fix Me FM", "TZ"), gone = w.findByName("Gone FM", "KE");
        checkTrue("silent 3 nights running, then repaired from its website (" + fix.status + ", " + fix.url + ")", "active".equals(fix.status)
                && fix.url.equals("https://fixed.example/new.mp3") && fix.note.contains("website"));
        checkTrue("website down: repaired from the Wayback Machine's newest copy (" + gone.url + ")", "active".equals(gone.status)
                && gone.url.equals("https://archived.example/radio.aac") && gone.note.contains("Wayback"));
        checkTrue("the others keep playing", w.count(null) == before);
        silent.add("https://wd.example/rtd.mp3");
        for (int night = 1; night <= 3; night++) {
            now[0] += WorldBot.DAY;
            bot.runNow("check", null, null);
        }
        WorldRadio.Station rtd = w.findByName("Radio Tanzania Dar", "TZ");
        checkTrue("no repair possible: hidden from the globe", "broken".equals(rtd.status) && w.count(null) == before - 1);
        silent.remove("https://wd.example/rtd.mp3");
        now[0] += WorldBot.DAY;
        bot.runNow("check", null, null);
        checkTrue("and back by itself when it plays again", "active".equals(w.findByName("Radio Tanzania Dar", "TZ").status));
        checkTrue("what is due next", bot.due() == null || bot.due().equals("rb") || bot.due().equals("icecast"));

        System.out.println("-- Admin -> Global Radio --");
        HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        w.register(http);
        http.start();
        int port = http.getAddress().getPort();
        try {
            checkTrue("members cannot see the admin list", get(port, "/api/admin/world/list", amina)[0].equals("403"));
            String[] r = get(port, "/api/admin/world/list?cc=TZ", admin);
            checkTrue("all stations of a country, with the real stream address (" + r[1].length() + ")", r[0].equals("200") && r[1].contains("Uhuru FM")
                    && r[1].contains("\"url\":\"https://rb.example/uhuru.mp3\"") && !r[1].contains("Berlin"));
            r = get(port, "/api/admin/world/countries", admin);
            checkTrue("countries with counts", r[0].equals("200") && r[1].contains("\"cc\":\"TZ\",\"name\":\"Tanzania\",\"active\":"));
            long id = uh.id;
            r = call(port, "/api/admin/world/save", "id=" + id + "&name=" + enc("Uhuru FM 95.5") + "&url=" + enc(uh.url) + "&tags=news&lang=swahili&home=&city=Morogoro&cc=TZ&lat=-6.8&lon=37.7&status=active", admin);
            checkTrue("the super-admin edits a station (" + r[1] + ")", r[0].equals("200") && r[1].contains("Uhuru FM 95.5") && r[1].contains("\"edited\":true") && r[1].contains("\"lon\":37.7"));
            r = call(port, "/api/admin/world/save", "id=" + id + "&name=x2&url=" + enc("https://dead.example/x.mp3") + "&cc=TZ&lat=-6.8&lon=37.7", admin);
            checkTrue("a new stream address that does not play is refused", r[0].equals("400") && r[1].contains("did not play"));
            r = call(port, "/api/admin/world/save", "name=" + enc("Admin Radio") + "&url=" + enc("https://admin.example/a.mp3") + "&cc=KE&lat=-1.2&lon=36.8", admin);
            checkTrue("and adds one", r[0].equals("200") && r[1].contains("\"source\":\"admin\"") && w.findByName("Admin Radio", "KE") != null);
            r = call(port, "/api/admin/world/save", "name=" + enc("Copy") + "&url=" + enc("https://admin.example/a.mp3") + "&cc=KE&lat=-1.2&lon=36.8", admin);
            checkTrue("the same stream twice is refused", r[0].equals("409"));
            bot.runNow("list", "Uhuru FM - Morogoro", "TZ");
            checkTrue("the bot never overwrites the super-admin's edit", w.byId(id).name.equals("Uhuru FM 95.5"));
            r = call(port, "/api/admin/world/test", "url=" + enc("https://ok.example/x.pls"), admin);
            checkTrue("test a stream", r[1].contains("\"ok\":true") && r[1].contains("x.mp3"));
            long wdId = w.findByName("Radio Mbeya", "TZ").id;
            checkTrue("stations from the open lists are taken off, not deleted", call(port, "/api/admin/world/delete", "id=" + wdId, admin)[0].equals("400"));
            checkTrue("taken off", call(port, "/api/admin/world/remove", "id=" + wdId, admin)[0].equals("200") && "removed".equals(w.byId(wdId).status));
            bot.runNow("wikidata", null, null);
            checkTrue("and the bot does not bring it back", "removed".equals(w.byId(wdId).status));
            long adm = w.findByName("Admin Radio", "KE").id;
            checkTrue("own stations can be deleted", call(port, "/api/admin/world/delete", "id=" + adm, admin)[0].equals("200") && w.byId(adm) == null);
            r = call(port, "/api/admin/world/settings", "rbLimit=12000&icecast=off", admin);
            checkTrue("settings", r[0].equals("200") && r[1].contains("\"rbLimit\":\"12000\"") && r[1].contains("\"icecast\":\"off\"") && !bot.enabled("icecast"));
            r = get(port, "/api/admin/world/bot", admin);
            checkTrue("the bot's page: jobs, log, not found", r[0].equals("200") && r[1].contains("\"job\":\"wikidata\"") && r[1].contains("\"log\":["));
            r = call(port, "/api/admin/world/find", "names=" + enc("Pambazuka FM") + "&cc=TZ", admin);
            checkTrue("a list search starts in the background", r[0].equals("200"));
            for (int i = 0; i < 100 && !bot.running().isEmpty(); i++) {
                Thread.sleep(50);
            }
            checkTrue("members cannot listen through the admin player", get(port, "/api/admin/world/listen?id=" + wdId + "&token=" + amina, null)[0].equals("403"));
            r = get(port, "/api/world/browse", null);
            checkTrue("visitors browse by country", r[0].equals("200") && r[1].contains("[\"TZ\",\"Tanzania\","));
            w.flushIfDirty();
            WorldRadio again = new WorldRadio(dir.resolve("world-radio.txt"), us, sessions, () -> now[0]);
            again.init();
            checkTrue("kept across a restart: edits, checks, settings (" + again.count(null) + "/" + w.count(null) + ")", again.count(null) == w.count(null)
                    && again.byId(id).edited && again.byId(id).name.equals("Uhuru FM 95.5") && "removed".equals(again.byId(wdId).status)
                    && again.setting("rbLimit").equals("12000") && again.jobLast("check") > 0
                    && again.findByName("Gone FM", "KE").note.contains("Wayback"));
        } finally {
            http.stop(0);
        }
        System.out.println(failures == 0 ? "ALL CHECKS PASSED" : failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }

    private static String enc(String s) throws Exception {
        return URLEncoder.encode(s, "UTF-8");
    }

    private static String[] get(int port, String path, String token) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:" + port + path).openConnection();
        c.setRequestProperty("Connection", "close");
        if (token != null) {
            c.setRequestProperty("Authorization", "Bearer " + token);
        }
        int code = c.getResponseCode();
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        return new String[]{String.valueOf(code), new String(read(in), StandardCharsets.UTF_8)};
    }

    private static byte[] read(InputStream in) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        if (in != null) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) {
                b.write(buf, 0, n);
            }
        }
        return b.toByteArray();
    }

    private static String[] call(int port, String path, String body, String token) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:" + port + path).openConnection();
        c.setRequestMethod("POST");
        c.setRequestProperty("Connection", "close");
        c.setConnectTimeout(10000);
        c.setReadTimeout(30000);
        if (token != null) {
            c.setRequestProperty("Authorization", "Bearer " + token);
        }
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        c.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
        int code = c.getResponseCode();
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        return new String[]{String.valueOf(code), new String(read(in), StandardCharsets.UTF_8)};
    }

    private static void checkTrue(String name, boolean ok) {
        System.out.println((ok ? "  ok    " : "  FAIL  ") + name);
        if (!ok) {
            failures++;
        }
    }
}