package com.vstream.Vidiserver;

import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

/** Station statistics (sessions, songs, countries, players, peaks), logs, Immediate / every-N-minutes playlists,
 *  the admin's own limits for a station, and "log in to this account". */
public final class RadioReportTest {

    private static int failures = 0;

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("memoryq-report");
        long[] now = {1768201200000L}; // Mon 2026-01-12 07:00 UTC

        System.out.println("-- Countries --");
        Path csv = dir.resolve("geo.csv");
        Files.write(csv, ("1.0.0.0,1.0.0.255,AU\n41.80.0.0,41.90.255.255,KE\n102.0.0.0,102.255.255.255,NG\n"
                + "2c0f:fe38::,2c0f:fe38:ffff:ffff:ffff:ffff:ffff:ffff,KE\n").getBytes(StandardCharsets.UTF_8));
        GeoIp geo = new GeoIp(csv);
        geo.load();
        check("a Kenyan address", geo.country("41.81.2.3"), "KE");
        check("an IPv6 address", geo.country("2c0f:fe38::1"), "KE");
        check("a home network", geo.country("192.168.1.20"), "LAN");
        check("not in the list", geo.country("8.8.8.8"), "");
        check("names are never looked up", geo.country("example.com"), "");
        check("country names", GeoIp.name("NG"), "Nigeria");

        System.out.println("-- Players --");
        check("VLC", RadioListenLog.player("VLC/3.0.20 LibVLC/3.0.20")[0], "VLC");
        check("the Android app", RadioListenLog.player("ExoPlayerLib/2.19.1")[0], "Memoryq app");
        String[] chrome = RadioListenLog.player("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0 Safari/537.36");
        checkTrue("Chrome on Windows", "Chrome".equals(chrome[0]) && "Windows".equals(chrome[1]));
        check("owners see part of the address", RadioListenLog.mask("41.81.2.3", false), "41.81.2.x");

        System.out.println("-- Listening sessions --");
        RadioStore store = new RadioStore(dir.resolve("radio.txt"), dir.resolve("music"));
        store.init();
        RadioStore.Station st = store.createStation("Stats FM", "", "owner1", RadioStore.STATUS_ACTIVE, now[0]);
        long sid = st.id;
        RadioListenLog log = new RadioListenLog(dir.resolve("logs"), () -> now[0], geo);
        log.init();
        // 07:00 two listeners from Kenya (one with VLC) and one from Nigeria
        RadioListenLog.Open a = log.open(sid, "41.81.2.3", "VLC/3.0.20 LibVLC/3.0.20");
        RadioListenLog.Open b = log.open(sid, "41.82.9.9", "ExoPlayerLib/2.19.1");
        now[0] += 60000;
        RadioListenLog.Open c = log.open(sid, "102.1.1.1", "Mozilla/5.0 (Windows NT 10.0) Chrome/129.0");
        log.played(sid, 7, "Lagos Nights", "Afro Star", 200000, 3, "autodj");
        RadioListenLog.Open probe = log.open(sid, "8.8.8.8", "curl/8");
        now[0] += 1000;
        log.close(probe); // a 1-second check of the link is not a listener
        now[0] += 29 * 60000;
        a.bytes.set(30_000_000);
        log.close(a);                   // 30 minutes
        log.played(sid, 8, "Sunrise", "DJ Neo", 180000, 2, "autodj");
        log.event(sid, "live", "DJ Kali went on air - AutoDJ waits");
        now[0] += 30 * 60000;
        b.bytes.set(60_000_000);
        log.close(b);                   // 60 minutes
        c.bytes.set(10_000_000);
        log.close(c);                   // 59 minutes
        log.played(sid, 7, "Lagos Nights", "Afro Star", 200000, 0, "autodj");
        long from = 1768201200000L - 3600000, to = now[0] + 3600000;
        Map<String, Object> rep = MiniJson.object(log.reportJson(st, from, to, false));
        Map<?, ?> ov = (Map<?, ?>) rep.get("overview");
        check("3 sessions (the 1-second check does not count)", n(ov.get("sessions")), "3");
        check("3 different listeners", n(ov.get("uniqueListeners")), "3");
        check("2 countries", n(ov.get("uniqueCountries")), "2");
        check("listener minutes 30 + 60 + 59", n(ov.get("listenerMinutes")), "149");
        check("peak: 3 at once", n(ov.get("peakListeners")), "3");
        check("the song on at the peak", String.valueOf(ov.get("peakTrack")), "Afro Star - Lagos Nights");
        check("songs played", n(ov.get("tracks")), "3");
        check("different songs", n(ov.get("uniqueTracks")), "2");
        check("data sent", n(ov.get("bytes")), "100000000");
        List<?> countries = (List<?>) rep.get("countries");
        Map<?, ?> top = (Map<?, ?>) countries.get(0);
        checkTrue("Kenya first, 2 sessions", "KE".equals(top.get("cc")) && "2".equals(n(top.get("sessions"))) && "Kenya".equals(top.get("name")));
        List<?> agents = (List<?>) rep.get("agents");
        checkTrue("players are counted", agents.size() == 3);
        Map<?, ?> topTrack = (Map<?, ?>) ((List<?>) rep.get("tracksList")).get(0);
        checkTrue("Lagos Nights played twice", "Lagos Nights".equals(topTrack.get("title")) && "2".equals(n(topTrack.get("plays"))));
        Map<?, ?> s0 = (Map<?, ?>) ((List<?>) rep.get("sessionsList")).get(0);
        checkTrue("owners do not see whole addresses", String.valueOf(s0.get("ip")).endsWith(".x"));
        checkTrue("the admin does", MiniJson.object(log.reportJson(st, from, to, true)).toString().contains("41.81.2.3"));
        List<?> chart = (List<?>) rep.get("chart");
        checkTrue("an hourly chart for a short period", Boolean.TRUE.equals(rep.get("hourly")) && chart.size() >= 3);
        Map<String, Object> week = MiniJson.object(log.reportJson(st, now[0] - 7L * 86400000, now[0], false));
        checkTrue("days for a longer period", Boolean.FALSE.equals(week.get("hourly")) && ((List<?>) week.get("chart")).size() >= 7);
        String countriesCsv = log.csv(st, from, to, "countries", false);
        checkTrue("countries as CSV", countriesCsv.startsWith("country,code,sessions") && countriesCsv.contains("\"Kenya\",\"KE\",2"));
        checkTrue("sessions as CSV", log.csv(st, from, to, "sessions", false).split("\n").length == 4);
        String logs = log.logsJson(sid, from, to, "");
        checkTrue("logs show the DJ and the songs", logs.contains("DJ Kali went on air") && logs.contains("Afro Star - Lagos Nights"));
        RadioListenLog.Open d = log.open(sid, "41.85.0.1", "VLC/3");
        checkTrue("listening now", log.listenersJson(sid, false).contains("\"country\":\"Kenya\"") && log.current(sid).size() == 1);
        RadioListenLog again = new RadioListenLog(dir.resolve("logs"), () -> now[0], geo);
        checkTrue("the record is kept on disk", MiniJson.object(again.reportJson(st, from, to, false)).get("overview").toString().contains("sessions=3"));
        log.close(d);

        System.out.println("-- Immediate and every-N-minutes playlists --");
        long[] t = new long[6];
        for (int i = 1; i <= 5; i++) {
            t[i] = store.addTrack(sid, "s" + i + ".mp3", "mp3", new byte[2000], "Song " + i, "A", "", 180000, now[0]).id;
        }
        store.setNoRepeat(sid, 0);
        RadioScheduler sch = new RadioScheduler(store, () -> now[0], new Random(3));
        playlist(store, sid, "Rotation", RadioStore.Playlist.GENERAL, 0, "songs", list(t[1], t[2]));
        long imm = playlist(store, sid, "Breaking news", RadioStore.Playlist.IMMEDIATE, 0, "songs", list(t[4], t[5]));
        checkTrue("an Immediate playlist plays straight away, in order", sch.next(sid, 0).id == t[4] && sch.next(sid, 0).id == t[5]);
        checkTrue("then it switches itself off", !store.playlist(imm).enabled);
        checkTrue("and the rotation carries on", sch.next(sid, 0).id != t[4]);
        long ids = playlist(store, sid, "Station ID", RadioStore.Playlist.INTERVAL, 30, "minutes", list(t[3]));
        checkTrue("every 30 minutes: not at once", sch.next(sid, 0).id != t[3]);
        now[0] += 10 * 60000;
        checkTrue("not after 10 minutes", sch.next(sid, 0).id != t[3]);
        now[0] += 21 * 60000;
        checkTrue("after 31 minutes, yes", sch.next(sid, 0).id == t[3]);
        checkTrue("then not again straight away", sch.next(sid, 0).id != t[3]);
        RadioStore reread = new RadioStore(dir.resolve("radio.txt"), dir.resolve("music"));
        store.flushIfDirty();
        reread.init();
        checkTrue("minutes are saved", "minutes".equals(reread.playlist(ids).unit) && reread.playlist(ids).every == 30);

        System.out.println("-- The admin's own limits and contact details --");
        RadioBilling billing = new RadioBilling(dir.resolve("billing.txt"));
        billing.init();
        checkTrue("no own limits: the plan as it is", RadioBilling.Plan.effective(null, store.station(sid)) == null);
        store.setLimits(sid, 250, 192, 5000, 40, 2);
        store.setContact(sid, "Stats Media Ltd", "boss@stats.example");
        RadioBilling.Plan eff = RadioBilling.Plan.effective(null, store.station(sid));
        checkTrue("own limits apply", eff.listeners == 250 && eff.kbps == 192 && eff.storageMb == 5000 && eff.transferGb == 40 && eff.djs == 2);
        store.setLimits(sid, -1, 0, 0, 0, -1);
        eff = RadioBilling.Plan.effective(null, store.station(sid));
        checkTrue("-1 = no limit (and no remote DJs)", eff.listeners == 0 && eff.djs == 0);
        checkTrue("an odd bitrate is refused", refusedLimit(store, sid));
        store.flushIfDirty();
        RadioStore r2 = new RadioStore(dir.resolve("radio.txt"), dir.resolve("music"));
        r2.init();
        checkTrue("limits and contact are saved", r2.station(sid).limListeners == -1 && r2.station(sid).limDjs == -1
                && "Stats Media Ltd".equals(r2.station(sid).company) && "boss@stats.example".equals(r2.station(sid).email));

        System.out.println("-- The API --");
        UserStore us = new UserStore(dir.resolve("users.txt"), new LiveChannelRegistry(), x -> { });
        us.init(dir.resolve("none.txt"));
        us.createUser("owner1", "password123", UserStore.Role.CHATTER);
        us.createUser("other", "password123", UserStore.Role.CHATTER);
        SessionStore sessions = new SessionStore();
        String owner = sessions.create("owner1", UserStore.Role.CHATTER);
        String other = sessions.create("other", UserStore.Role.CHATTER);
        String admin = sessions.create("admin", UserStore.Role.ADMIN);
        AudioTools tools = new AudioTools(dir.resolve("tools"), dir.resolve("tmp"));
        RadioService radio = new RadioService(store, tools, null, null, null, null, () -> now[0], new Random(3));
        radio.setListenLog(log);
        RadioApi api = new RadioApi(radio, us, sessions, () -> now[0], dir.resolve("tmp"));
        api.setBilling(billing);
        HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        http.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        api.register(http);
        http.start();
        int port = http.getAddress().getPort();
        String q = "?station=" + sid + "&from=" + from + "&to=" + to;
        String[] r = get(port, "/api/studio/report" + q, owner);
        checkTrue("the owner gets the report", r[0].equals("200") && r[1].contains("\"sessions\":3"));
        check("others do not", get(port, "/api/studio/report" + q, other)[0], "403");
        r = get(port, "/api/studio/report.csv" + q + "&kind=tracks", owner);
        checkTrue("a CSV download", r[0].equals("200") && r[1].startsWith("artist,title,plays"));
        check("only known tables", get(port, "/api/studio/report.csv" + q + "&kind=../x", owner)[0], "400");
        checkTrue("logs", get(port, "/api/studio/logs" + q, owner)[1].contains("DJ Kali"));
        checkTrue("listeners now", get(port, "/api/studio/listeners?station=" + sid, owner)[0].equals("200"));
        r = post(port, "/api/studio/playlist/enable", "station=" + sid + "&id=" + imm + "&on=true", owner);
        checkTrue("switch a playlist on from the list", r[0].equals("200") && store.playlist(imm).enabled);
        r = post(port, "/api/admin/radio/station/admin", "id=" + sid + "&limits=100,128,0,0,1&company=New+Co&email=a%40b.example", admin);
        checkTrue("the admin sets limits and contact", r[0].equals("200") && store.station(sid).limListeners == 100
                && "New Co".equals(store.station(sid).company));
        check("owners cannot set limits", post(port, "/api/admin/radio/station/admin", "id=" + sid + "&limits=0,0,0,0,0", owner)[0], "403");
        r = post(port, "/api/admin/radio/station/admin", "id=" + sid + "&ownerPassword=NewPass2026x", admin);
        checkTrue("the admin sets the owner's password", r[0].equals("200") && us.authenticate("owner1", "NewPass2026x") != null);
        r = post(port, "/api/admin/radio/station/login-as", "id=" + sid, admin);
        String as = str(r[1], "token");
        checkTrue("log in to this account", r[0].equals("200") && as != null && get(port, "/api/studio/status", as)[1].contains("Stats FM"));
        check("only the admin may", post(port, "/api/admin/radio/station/login-as", "id=" + sid, owner)[0], "403");
        r = get(port, "/api/studio/status", owner);
        checkTrue("the owner sees their limits", r[1].contains("\"limits\":[100,128,0,0,1]") && r[1].contains("\"listeners\":100"));
        http.stop(0);

        System.out.println();
        if (failures == 0) {
            System.out.println("ALL CHECKS PASSED");
        } else {
            System.out.println(failures + " CHECK(S) FAILED");
            System.exit(1);
        }
        System.exit(0);
    }

    private static boolean refusedLimit(RadioStore store, long sid) {
        try {
            store.setLimits(sid, 0, 100, 0, 0, 0);
            return false;
        } catch (IllegalArgumentException e) {
            return true;
        }
    }

    private static List<Long> list(long... ids) {
        List<Long> l = new ArrayList<Long>();
        for (long id : ids) {
            l.add(id);
        }
        return l;
    }

    private static long playlist(RadioStore store, long sid, String name, String type, int every, String unit, List<Long> tracks) {
        RadioStore.Playlist p = new RadioStore.Playlist();
        p.station = sid;
        p.name = name;
        p.type = type;
        p.every = every == 0 ? 4 : every;
        p.unit = unit;
        p.tracks = tracks;
        return store.savePlaylist(p, 0).id;
    }

    private static String n(Object o) {
        return o instanceof Number ? String.valueOf(((Number) o).longValue()) : String.valueOf(o);
    }

    private static String str(String json, String key) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"" + key + "\":\"([^\"]*)\"").matcher(json);
        return m.find() ? m.group(1) : null;
    }

    private static byte[] readAll(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] b = new byte[8192];
        int n;
        while ((n = in.read(b)) > 0) {
            out.write(b, 0, n);
        }
        return out.toByteArray();
    }

    private static String[] get(int port, String path, String token) throws Exception {
        return http(port, "GET", path, null, token);
    }

    private static String[] post(int port, String path, String body, String token) throws Exception {
        return http(port, "POST", path, body, token);
    }

    private static String[] http(int port, String method, String path, String body, String token) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:" + port + path).openConnection();
        c.setRequestMethod(method);
        if (token != null) {
            c.setRequestProperty("Authorization", "Bearer " + token);
        }
        if (body != null) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            c.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
        }
        int code = c.getResponseCode();
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        byte[] b = in == null ? new byte[0] : readAll(in);
        return new String[] {String.valueOf(code), new String(b, StandardCharsets.UTF_8)};
    }

    private static void check(String name, String actual, String expected) {
        checkTrue(name + " (got " + actual + ")", expected.equals(actual));
    }

    private static void checkTrue(String name, boolean ok) {
        System.out.println((ok ? "  ok    " : "  FAIL  ") + name);
        if (!ok) {
            failures++;
        }
    }
}