package com.vstream.Vidiserver;

import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

/** World Radio: the open station list, Alma's search, members adding a station (checked, placed on the map), listening, the admin. */
public final class WorldRadioTest {

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

    private static String rb(String uuid, String name, String tags, String country, String cc, String state, double lat, double lon, int clicks, int hls) {
        return "{\"stationuuid\":\"" + uuid + "\",\"name\":\"" + name + "\",\"url\":\"http://old.example/" + uuid + "\",\"url_resolved\":\"https://s.example/"
                + uuid + ".mp3\",\"homepage\":\"https://" + uuid + ".example\",\"tags\":\"" + tags + "\",\"country\":\"" + country + "\",\"countrycode\":\""
                + cc + "\",\"state\":\"" + state + "\",\"language\":\"swahili\",\"geo_lat\":" + lat + ",\"geo_long\":" + lon + ",\"codec\":\"MP3\",\"bitrate\":128,"
                + "\"votes\":10,\"clickcount\":" + clicks + ",\"hls\":" + hls + "}";
    }

    private static void run() throws Exception {
        System.out.println("-- The map --");
        checkTrue("the land is on the map (" + WorldMap.LAND_POINTS + " dots)", WorldMap.LAND_POINTS > 10000 && WorldMap.land().length() > 80000);
        checkTrue("countries are known by name", WorldRadio.countryIn("gospel radio in tanzania")[1].equals("TZ")
                && WorldRadio.countryIn("stations in the USA")[1].equals("US") && WorldRadio.countryIn("jazz") == null);

        Path dir = Files.createTempDirectory("memoryq-world");
        UserStore us = new UserStore(dir.resolve("users.txt"), new LiveChannelRegistry(), c -> { });
        us.init(dir.resolve("none.txt"));
        us.createUser("amina", "password123", UserStore.Role.CHATTER);
        SessionStore sessions = new SessionStore();
        String admin = sessions.create("admin", UserStore.Role.ADMIN);
        String amina = sessions.create("amina", UserStore.Role.CHATTER);
        long[] now = {1792700000000L};
        WorldRadio w = new WorldRadio(dir.resolve("world-radio.txt"), us, sessions, () -> now[0]);
        w.init();
        AtomicInteger geoCalls = new AtomicInteger();
        String rbJson = "[" + rb("a1", "Radio Mwangaza", "gospel,christian", "Tanzania", "TZ", "Arusha", -3.37, 36.68, 500, 0) + ","
                + rb("a2", "Clouds FM", "bongo flava,pop", "Tanzania", "TZ", "Dar es Salaam", -6.8, 39.28, 9000, 0) + ","
                + rb("a3", "Capital FM", "pop,news", "Kenya", "KE", "Nairobi", -1.29, 36.82, 3000, 0) + ","
                + rb("a4", "Jazz Paris", "jazz", "France", "FR", "Paris", 48.85, 2.35, 4000, 0) + ","
                + rb("a5", "HLS only", "pop", "France", "FR", "Lyon", 45.76, 4.84, 100, 1) + "]";
        w.setFetcher(url -> {
            if (url.contains("radio-browser.info")) {
                return rbJson;
            }
            if (url.contains("nominatim") && url.contains("/search")) {
                geoCalls.incrementAndGet();
                return url.contains("Mwanza") ? "[{\"lat\":\"-2.5164\",\"lon\":\"32.9175\",\"address\":{\"city\":\"Mwanza\",\"country\":\"Tanzania\",\"country_code\":\"tz\"}}]" : "[]";
            }
            if (url.contains("nominatim") && url.contains("/reverse")) {
                return "{\"address\":{\"town\":\"Moshi\",\"country\":\"Tanzania\",\"country_code\":\"tz\"}}";
            }
            throw new IOException("offline");
        });

        System.out.println("-- The open station list (Radio Browser) --");
        checkTrue("stations with a place are taken; HLS-only ones are left out", w.importRadioBrowser() == 4 && w.count(null) == 4);
        checkTrue("a second refresh updates, never doubles", w.importRadioBrowser() == 4 && w.count(null) == 4);

        System.out.println("-- Searching --");
        checkTrue("by words", w.search("gospel", "", null, 10).size() == 1 && w.search("gospel", "", null, 10).get(0).name.equals("Radio Mwangaza"));
        checkTrue("by country, most listened-to first", w.search("", "TZ", null, 10).get(0).name.equals("Clouds FM"));
        checkTrue("nearest first", w.search("", "", new double[]{-1.3, 36.8}, 10).get(0).name.equals("Capital FM"));
        String a = w.ask("gospel stations in Tanzania");
        checkTrue("Alma's search without the AI: words + country, and where to turn the globe (" + a + ")", a.contains("Radio Mwangaza")
                && !a.contains("Clouds FM") && a.contains("\"cc\":\"TZ\"") && a.contains("\"focus\":["));
        a = w.ask("Kenya");
        checkTrue("just a country", a.contains("Capital FM") && a.contains("\"cc\":\"KE\""));
        a = w.ask("reggae in Tanzania");
        checkTrue("nothing matches: the country's best stations instead", a.contains("Clouds FM") && a.contains("Nothing matched"));
        w.setAi(new RadioWriter.Ai() {
            public boolean ready() {
                return true;
            }

            public String write(String instructions, String input) {
                return "Sure! {\"words\":\"jazz\",\"country\":\"FR\",\"reply\":\"Here is some smooth jazz from France.\"}";
            }
        });
        a = w.ask("something smooth and french please");
        checkTrue("with the AI: Alma understands the request (" + a + ")", a.contains("Jazz Paris") && a.contains("smooth jazz from France"));
        w.setAi(null);

        // a pretend radio station on this computer
        byte[] sound = new byte[64 * 1024];
        HttpServer radio = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        radio.createContext("/live.mp3", ex -> {
            ex.getResponseHeaders().set("Content-Type", "audio/mpeg");
            ex.getResponseHeaders().set("icy-name", "Test FM");
            ex.sendResponseHeaders(200, sound.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(sound);
            } catch (IOException e) {
                // the listener left
            }
        });
        radio.createContext("/page", ex -> {
            byte[] b = "<html>not radio</html>".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "text/html");
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        radio.start();
        int rport = radio.getAddress().getPort();
        radio.createContext("/listen.pls", ex -> {
            byte[] b = ("[playlist]\nFile1=http://127.0.0.1:" + rport + "/live.mp3\n").getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "audio/x-scpls");
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });

        HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        w.register(http);
        http.start();
        int port = http.getAddress().getPort();
        try {
            System.out.println("-- Members add a station --");
            String form = "name=" + enc("Radio Kwetu") + "&url=" + enc("http://127.0.0.1:" + rport + "/listen.pls") + "&tags=" + enc("Taarab, Bongo")
                    + "&lang=swahili&home=" + enc("javascript:alert(1)") + "&lat=-2.5164&lon=32.9175&city=Mwanza&country=Tanzania&cc=tz";
            checkTrue("signed-out visitors cannot add", call(port, "/api/world/add", form, null)[0].equals("401"));
            String[] r = call(port, "/api/world/add", form, amina);
            checkTrue("addresses inside a network are refused (" + r[1] + ")", r[0].equals("400") && r[1].contains("not on the internet"));
            w.setOwnHosts("127.0.0.1");
            r = call(port, "/api/world/add", form.replace("listen.pls", "page"), amina);
            checkTrue("Memoryq's own address (memoryq.com) is allowed even when the server sees it inside (" + r[1] + ")", r[0].equals("400") && r[1].contains("not an audio stream"));
            w.allowPrivateAddresses(true);
            r = call(port, "/api/world/add", form.replace("listen.pls", "page"), amina);
            checkTrue("a web page is not a radio stream (" + r[1] + ")", r[0].equals("400") && r[1].contains("not an audio stream"));
            r = call(port, "/api/world/add", form, amina);
            checkTrue("a working stream (through its playlist) goes on the map (" + r[1] + ")", r[0].equals("200") && r[1].contains("\"source\":\"member\"")
                    && r[1].contains("\"addedBy\":\"amina\""));
            checkTrue("its place, cleaned details, no bad website", r[1].contains("\"city\":\"Mwanza\"") && r[1].contains("\"cc\":\"TZ\"")
                    && r[1].contains("\"tags\":\"taarab, bongo\"") && r[1].contains("\"home\":\"\""));
            long kwetu = Long.parseLong(r[1].replaceAll("^\\{\"id\":(\\d+),.*", "$1"));
            checkTrue("an http stream plays through Memoryq", r[1].contains("\"play\":\"/api/world/listen?id=" + kwetu + "\""));
            checkTrue("the same station twice is refused", call(port, "/api/world/add", form, amina)[0].equals("409"));
            checkTrue("it is on the globe", get(port, "/api/world/dots")[1].contains(kwetu + ",-2.5164,32.9175,1"));
            for (int i = 0; i < 4; i++) {
                call(port, "/api/world/add", form.replace("Radio+Kwetu", "Kwetu+" + i).replace("lat=-2.5164", "lat=" + (10 + i)), amina);
            }
            r = call(port, "/api/world/add", form.replace("Radio+Kwetu", "Kwetu+9").replace("lat=-2.5164", "lat=40"), amina);
            checkTrue("five new stations a day at most (" + r[1] + ")", r[0].equals("429"));

            System.out.println("-- Finding the place --");
            r = get(port, "/api/world/geocode?q=Mwanza%2C+Tanzania", amina);
            checkTrue("a town is found (" + r[1] + ")", r[0].equals("200") && r[1].contains("\"lat\":-2.5164") && r[1].contains("\"cc\":\"TZ\""));
            get(port, "/api/world/geocode?q=Mwanza%2C+Tanzania", amina);
            checkTrue("asked once, then remembered", geoCalls.get() == 1);
            r = get(port, "/api/world/geocode?q=Somewhere+in+Kenya", amina);
            checkTrue("an unknown town: the country's middle", r[0].equals("200") && r[1].contains("\"cc\":\"KE\""));
            r = get(port, "/api/world/reverse?lat=-3.35&lon=37.34", amina);
            checkTrue("the phone's location gives the town", r[0].equals("200") && r[1].contains("Moshi"));
            checkTrue("members only", get(port, "/api/world/geocode?q=x", null)[0].equals("401"));

            System.out.println("-- Listening --");
            HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:" + port + "/api/world/listen?id=" + kwetu).openConnection();
            byte[] got = read(c.getInputStream());
            checkTrue("the stream comes through (" + got.length + " bytes, " + c.getContentType() + ")", got.length == sound.length && "audio/mpeg".equals(c.getContentType()));
            checkTrue("a play is counted", call(port, "/api/world/play", "id=" + kwetu, null)[0].equals("200"));
            checkTrue("only stations on the map", get(port, "/api/world/listen?id=99999")[0].equals("404"));

            System.out.println("-- The super-admin --");
            checkTrue("members cannot remove", call(port, "/api/admin/world/remove", "id=" + kwetu, amina)[0].equals("403"));
            call(port, "/api/world/report", "id=" + kwetu, amina);
            r = get(port, "/api/admin/world/status", admin);
            checkTrue("the list of members' stations and reports", r[0].equals("200") && r[1].contains("Radio Kwetu") && r[1].contains("\"reports\":1"));
            checkTrue("removed", call(port, "/api/admin/world/remove", "id=" + kwetu, admin)[0].equals("200")
                    && !get(port, "/api/world/dots")[1].replace("\"", ";").contains(";" + kwetu + ",") && get(port, "/api/world/station?id=" + kwetu)[0].equals("404"));
            checkTrue("and back", call(port, "/api/admin/world/restore", "id=" + kwetu, admin)[0].equals("200") && get(port, "/api/world/station?id=" + kwetu)[0].equals("200"));
            w.flushIfDirty();
            WorldRadio again = new WorldRadio(dir.resolve("world-radio.txt"), us, sessions, () -> now[0]);
            again.init();
            checkTrue("kept across a restart (" + again.count(null) + ")", again.count(null) == w.count(null) && again.count("member") == w.count("member")
                    && again.search("kwetu", "", null, 10).get(0).plays == 1);
            r = get(port, "/api/world/land");
            checkTrue("the land for the globe", r[0].equals("200") && r[1].contains("\"points\":" + WorldMap.LAND_POINTS));
        } finally {
            http.stop(0);
            radio.stop(0);
        }
        System.out.println(failures == 0 ? "ALL CHECKS PASSED" : failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }

    private static String enc(String s) throws Exception {
        return URLEncoder.encode(s, "UTF-8");
    }

    private static String[] get(int port, String path) throws Exception {
        return get(port, path, null);
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