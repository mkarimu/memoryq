package com.vstream.Vidiserver;

import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Plans and limits (listeners, stream quality, storage, monthly data, DJs) and remote DJs (own logins, days and
 * hours, plan limits, show history).
 */
public final class RadioPlansTest {

    private static int failures = 0;
    private static final long GB = 1024L * 1024 * 1024;

    public static void main(String[] args) throws Exception {
        System.setProperty("vstream.livePortMin", "18510");
        System.setProperty("vstream.livePortMax", "18519");
        Path dir = Files.createTempDirectory("memoryq-plans");

        System.out.println("-- From one price to plans --");
        Files.write(dir.resolve("billing.txt"), "K\tcurrency\tusd\nK\tmonth\t1999\nK\tyear\t19900\nK\ttrialDays\t0\nK\tstripeKey\t\n"
                .getBytes(StandardCharsets.UTF_8));
        RadioBilling billing = new RadioBilling(dir.resolve("billing.txt"));
        billing.init();
        RadioBilling.Plan std = billing.plan(1);
        checkTrue("the old price became the Standard plan", std != null && std.name.equals("Standard") && std.monthCents == 1999
                && std.yearCents == 19900 && std.djs == 3 && std.listeners == 0);

        UserStore us = new UserStore(dir.resolve("users.txt"), new LiveChannelRegistry(), c -> { });
        us.init(dir.resolve("none.txt"));
        us.createUser("zawadi", "password123", UserStore.Role.CHATTER);
        us.createUser("other", "password123", UserStore.Role.CHATTER);
        SessionStore sessions = new SessionStore();
        String admin = sessions.create("admin", UserStore.Role.ADMIN);
        String zawadi = sessions.create("zawadi", UserStore.Role.CHATTER);
        long[] now = {1767960000000L}; // Fri 2026-01-09 12:00 UTC
        RadioStore store = new RadioStore(dir.resolve("radio.txt"), dir.resolve("music"));
        store.init();
        AudioTools tools = new AudioTools(java.nio.file.Paths.get(System.getProperty("vstream.toolsDir", dir.resolve("tools").toString())),
                dir.resolve("tmp"));
        tools.detect();
        RadioService radio = new RadioService(store, tools, null, null, null, null, () -> now[0], new Random(4));
        RadioApi api = new RadioApi(radio, us, sessions, () -> now[0], dir.resolve("tmp"));
        api.setLiveHost("radio.example");
        api.setBilling(billing);
        HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        api.register(http);
        http.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        http.start();
        int port = http.getAddress().getPort();

        System.out.println("-- The admin makes plans --");
        String[] r = post(port, "/api/admin/radio/plan/save", "name=Starter&month=9.99&year=99&listeners=1&kbps=64&storageMb=1"
                + "&transferGb=1&djs=1&djPro=false&signup=true", admin);
        check("Starter", r[0], "200");
        int starter = Integer.parseInt(num(r[1], "id"));
        r = post(port, "/api/admin/radio/plan/save", "name=Pro&month=29.99&listeners=0&kbps=192&djs=3&signup=true", admin);
        int pro = Integer.parseInt(num(r[1], "id"));
        r = post(port, "/api/admin/radio/plan/save", "name=Club+deal&month=5&kbps=128&djs=3&signup=false", admin);
        int hidden = Integer.parseInt(num(r[1], "id"));
        checkTrue("Pro and a hidden plan", pro > 0 && hidden > 0);
        check("an odd stream quality is refused", post(port, "/api/admin/radio/plan/save", "name=X&kbps=100", admin)[0], "400");
        check("more than 3 DJs is refused", post(port, "/api/admin/radio/plan/save", "name=X&djs=4", admin)[0], "400");
        check("owners cannot make plans", post(port, "/api/studio/plan/save", "name=Free&month=0", zawadi)[0], "403");
        r = get(port, "/api/studio/status", zawadi);
        checkTrue("sign-up shows the plans on offer (not the hidden one)", r[1].contains("\"name\":\"Starter\"")
                && r[1].contains("\"name\":\"Pro\"") && !r[1].contains("Club deal"));
        RadioBilling again = new RadioBilling(dir.resolve("billing.txt"));
        again.init();
        checkTrue("plans are saved", again.plans().size() == 4 && again.plan(starter).kbps == 64 && !again.plan(starter).djPro
                && again.plan(hidden).signup == false);

        System.out.println("-- Signing up on a plan --");
        r = post(port, "/api/studio/request", "name=Zawadi+FM&plan=" + hidden, zawadi);
        check("a hidden plan cannot be picked at sign-up", r[0], "400");
        r = post(port, "/api/studio/request", "name=Zawadi+FM&plan=" + starter, zawadi);
        long id = Long.parseLong(num(r[1], "id"));
        checkTrue("on Starter", store.station(id).planId == starter);
        r = post(port, "/api/admin/radio/station/admin", "id=" + id + "&status=active&billing=paid", admin);
        checkTrue("activated", r[0].equals("200") && store.station(id).onAir());
        checkTrue("stream quality follows the plan (64 kbps)", radio.kbpsFor(id) == 64);
        r = get(port, "/api/studio/status", zawadi);
        checkTrue("the owner sees the plan, usage and DJ room", r[1].contains("\"plan\":{\"id\":" + starter)
                && r[1].contains("\"usage\":{") && r[1].contains("\"maxDjs\":1") && r[1].contains("\"timezone\":\"UTC\""));
        r = post(port, "/api/studio/plan/change", "station=" + id + "&plan=" + pro, zawadi);
        check("a paid station cannot switch plan by itself", r[0], "409");

        System.out.println("-- Remote DJs --");
        r = post(port, "/api/studio/dj/save", "station=" + id + "&name=DJ+Kali&login=kali&password=KaliLive2026", zawadi);
        check("first DJ", r[0], "200");
        long kali = Long.parseLong(num(r[1], "id"));
        r = post(port, "/api/studio/dj/save", "station=" + id + "&name=DJ+Two&login=two&password=TwoLive2026", zawadi);
        checkTrue("Starter allows 1 DJ", r[0].equals("400") && r[1].contains("allows 1 remote DJ"));
        post(port, "/api/admin/radio/station/admin", "id=" + id + "&plan=" + pro, admin);
        r = post(port, "/api/studio/dj/save", "station=" + id + "&name=DJ+Two&login=two&password=TwoLive2026&days=1000000&from=10&to=12", zawadi);
        check("on Pro a second DJ (Mondays 10-12)", r[0], "200");
        long two = Long.parseLong(num(r[1], "id"));
        r = post(port, "/api/studio/dj/save", "station=" + id + "&name=Club+Night&login=club&password=ClubLive2026", zawadi);
        long club = Long.parseLong(num(r[1], "id"));
        r = post(port, "/api/studio/dj/save", "station=" + id + "&name=Four&login=four&password=FourLive2026", zawadi);
        checkTrue("never more than 3", r[0].equals("400") && r[1].contains("3 remote DJs"));
        check("logins are unique", post(port, "/api/studio/dj/save", "station=" + id + "&id=" + club
                + "&name=Club&login=KALI&password=ClubLive2026", zawadi)[0], "400");
        check("\"source\" is reserved", post(port, "/api/studio/dj/save", "station=" + id + "&id=" + club
                + "&name=Club&login=source&password=ClubLive2026", zawadi)[0], "400");
        check("weak DJ password refused", post(port, "/api/studio/dj/save", "station=" + id + "&id=" + club
                + "&name=Club&login=club&password=short", zawadi)[0], "400");

        LiveInput.Station door = radio.liveCallbacks();
        LiveInput.Login l = door.login(id, "kali", "KaliLive2026", "BUTT");
        checkTrue("a DJ logs in with their own login", l != null && l.refused == null && l.name.equals("DJ Kali") && l.djId == kali);
        checkTrue("login is not case-sensitive", door.login(id, "KALI", "KaliLive2026", "") != null);
        checkTrue("wrong password: no", door.login(id, "kali", "nope12345", "") == null);
        checkTrue("another station's DJ: no", door.login(id + 1, "kali", "KaliLive2026", "") != null
                && door.login(id + 1, "kali", "KaliLive2026", "").refused != null);
        String pass = store.station(id).livePass;
        l = door.login(id, "source", pass, "Studio Mix");
        checkTrue("the station's own login still works", l != null && l.refused == null && l.djId == 0 && l.name.equals("Studio Mix"));
        l = door.login(id, "two", "TwoLive2026", "");
        checkTrue("outside their hours (Friday noon, they have Mondays 10-12)", l != null && l.refused != null
                && l.refused.contains("Outside your DJ hours") && l.refused.contains("Mon 10:00-12:00"));
        now[0] = 1768219200000L; // Mon 2026-01-12 12:00 UTC
        checkTrue("12:00 is already past their 10-12 slot", door.login(id, "two", "TwoLive2026", "").refused != null);
        post(port, "/api/studio/station/save", "id=" + id + "&timezone=Africa%2FNairobi", zawadi); // UTC+3: 15:00 local
        now[0] = 1768208400000L; // Mon 09:00 UTC = 12:00 Nairobi
        checkTrue("time zones count (09:00 UTC is 12:00 in Nairobi - outside)", door.login(id, "two", "TwoLive2026", "").refused != null);
        now[0] = 1768201200000L; // Mon 07:00 UTC = 10:00 Nairobi
        checkTrue("10:00 Nairobi on a Monday - in", door.login(id, "two", "TwoLive2026", "").refused == null);
        check("a wrong time zone is refused", post(port, "/api/studio/station/save", "id=" + id + "&timezone=Mars%2FBase", zawadi)[0], "400");
        post(port, "/api/studio/dj/save", "station=" + id + "&id=" + club + "&name=Club+Night&login=club&password=ClubLive2026&enabled=false", zawadi);
        checkTrue("a switched-off DJ is turned away", door.login(id, "club", "ClubLive2026", "").refused.contains("switched off"));
        post(port, "/api/admin/radio/station/admin", "id=" + id + "&plan=" + starter, admin);
        l = door.login(id, "two", "TwoLive2026", "");
        checkTrue("after a downgrade to 1 DJ, only the first DJ may go live", l.refused != null && l.refused.contains("plan")
                && door.login(id, "kali", "KaliLive2026", "").refused == null);
        door.showEnded(id, kali, "DJ Kali", now[0] - 3600000, now[0]);
        door.showEnded(id, kali, "DJ Kali", now[0] - 10000, now[0]);
        r = get(port, "/api/studio/status", zawadi);
        checkTrue("the show is in the history (not the 10-second one)", count(r[1], "\"dj\":\"DJ Kali\"") == 1
                && store.dj(kali).lastLive == now[0] && r[1].contains("\"login\":\"kali\""));
        check("delete a DJ", post(port, "/api/studio/dj/delete", "station=" + id + "&id=" + club, zawadi)[0], "200");
        check("someone else's DJ list is closed", post(port, "/api/studio/dj/save", "station=" + id + "&name=X&login=xxx&password=XxxLive2026",
                sessions.create("other", UserStore.Role.CHATTER))[0], "403");

        System.out.println("-- Storage, listeners, data --");
        if (tools.ready()) {
            byte[] small = tone(tools, 6);
            r = upload(port, id, "small.mp3", small, zawadi);
            check("a song within the 1 MB storage", r[0], "200");
            byte[] big = tone(tools, 70);
            r = upload(port, id, "big.mp3", big, zawadi);
            checkTrue("storage full is refused with the usage", r[0].equals("413") && r[1].contains("storage is full")
                    && r[1].contains("of 1 MB"));
            radio.refresh();
            RadioEngine.Broadcast cast = waitFor(() -> radio.broadcast(id), 15000);
            checkTrue("the station is on air", cast != null);
            HttpURLConnection one = (HttpURLConnection) new URL("http://127.0.0.1:" + port + "/radio/" + id + ".mp3").openConnection();
            InputStream in1 = one.getInputStream();
            byte[] buf = new byte[4096];
            int got = in1.read(buf);
            waitFor(() -> radio.listeners(id) >= 1 ? Boolean.TRUE : null, 5000);
            r = get(port, "/radio/" + id + ".mp3", null);
            checkTrue("the second listener is told the station is full (Starter: 1 listener)", got > 0 && r[0].equals("503")
                    && r[1].contains("full"));
            for (int i = 0; i < 20; i++) {
                in1.read(buf);
            }
            one.disconnect();
            api.usageTick();
            checkTrue("listening data is counted", store.station(id).monthBytes > 1000 && store.station(id).monthKey.equals("2026-01"));
        } else {
            System.out.println("  (FFmpeg not found - streaming part skipped)");
        }
        store.addTransfer(id, (long) (0.85 * GB), "2026-01");
        api.usageTick();
        checkTrue("80% of the monthly data: warned", store.station(id).xferWarned.equals("2026-01:80"));
        store.addTransfer(id, (long) (0.2 * GB), "2026-01");
        api.usageTick();
        checkTrue("100%: noted", store.station(id).xferWarned.equals("2026-01:100"));
        r = get(port, "/radio/" + id + ".mp3", null);
        checkTrue("new listeners cannot tune in this month", !tools.ready() || (r[0].equals("503") && r[1].contains("listening data")));
        now[0] = 1769904000000L; // 2026-02-01
        api.usageTick();
        r = get(port, "/api/studio/status", zawadi);
        long febBytes = Long.parseLong(num(r[1], "monthBytes"));
        checkTrue("a new month starts again from (almost) zero (" + febBytes + " bytes)", febBytes < 50L * 1024 * 1024);

        System.out.println("-- Saved --");
        store.flushIfDirty();
        RadioStore reload = new RadioStore(dir.resolve("radio.txt"), dir.resolve("music"));
        reload.init();
        RadioStore.Station rs = reload.station(id);
        checkTrue("plan, time zone, data and DJs survive a restart", rs.planId == starter && rs.timezone.equals("Africa/Nairobi")
                && (rs.monthBytes > GB || rs.monthKey.equals("2026-02")) && rs.xferWarned.equals("2026-01:100") && reload.djs(id).size() == 2
                && reload.djLogin(id, "two", "TwoLive2026") != null && reload.shows(id).size() == 1
                && reload.dj(two).days.equals("1000000") && reload.dj(two).from == 10);
        check("a plan in use cannot be deleted", post(port, "/api/admin/radio/plan/delete", "id=" + starter, admin)[0], "409");
        check("an unused plan can", post(port, "/api/admin/radio/plan/delete", "id=" + hidden, admin)[0], "200");

        System.out.println("-- Paying for a plan --");
        AtomicReference<String> lastCheckout = new AtomicReference<String>("");
        HttpServer stripe = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stripe.createContext("/v1/", ex -> {
            lastCheckout.set(URLDecoder.decode(new String(readAll(ex.getRequestBody()), StandardCharsets.UTF_8), "UTF-8"));
            byte[] b = "{\"id\":\"cs_test_1\",\"url\":\"https://pay.example/x\"}".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        stripe.start();
        billing.setBaseUrl("http://127.0.0.1:" + stripe.getAddress().getPort());
        post(port, "/api/admin/radio/billing/save", "stripeKey=sk_test_12345abcdefXYZ", admin);
        post(port, "/api/admin/radio/station/admin", "id=" + id + "&billing=unpaid", admin);
        r = post(port, "/api/studio/pay", "station=" + id + "&planId=" + pro + "&period=month", zawadi);
        checkTrue("checkout for Pro, monthly", r[0].equals("200") && lastCheckout.get().contains("unit_amount]=2999")
                && lastCheckout.get().contains("metadata[plan]=" + pro) && lastCheckout.get().contains("Memoryq radio Pro: Zawadi FM"));
        r = post(port, "/api/studio/pay", "station=" + id + "&planId=" + pro + "&period=year", zawadi);
        checkTrue("Pro has no yearly price", r[0].equals("502") && r[1].contains("no yearly price"));
        r = post(port, "/api/studio/plan/change", "station=" + id + "&plan=" + pro, zawadi);
        checkTrue("an unpaid station may switch plan itself", r[0].equals("200") && store.station(id).planId == pro);

        stripe.stop(0);
        radio.stopAll();
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

    private interface Probe<T> {
        T get();
    }

    private static <T> T waitFor(Probe<T> p, long ms) throws InterruptedException {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            T v = p.get();
            if (v != null) {
                return v;
            }
            Thread.sleep(100);
        }
        return p.get();
    }

    private static byte[] tone(AudioTools tools, int seconds) throws Exception {
        Process pr = new ProcessBuilder(tools.ffmpegPath(), "-v", "error", "-f", "lavfi", "-i",
                "sine=frequency=440:duration=" + seconds, "-ac", "2", "-ar", "44100", "-c:a", "libmp3lame", "-b:a", "128k",
                "-f", "mp3", "pipe:1").start();
        byte[] b = readAll(pr.getInputStream());
        pr.waitFor();
        return b;
    }

    private static String[] upload(int port, long station, String name, byte[] data, String token) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:" + port + "/api/studio/upload?station=" + station
                + "&name=" + name).openConnection();
        c.setRequestMethod("POST");
        c.setRequestProperty("Authorization", "Bearer " + token);
        c.setDoOutput(true);
        c.getOutputStream().write(data);
        int code = c.getResponseCode();
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        return new String[]{String.valueOf(code), in == null ? "" : new String(readAll(in), StandardCharsets.UTF_8)};
    }

    private static int count(String s, String sub) {
        int n = 0;
        int i = 0;
        while ((i = s.indexOf(sub, i)) >= 0) {
            n++;
            i += sub.length();
        }
        return n;
    }

    private static byte[] readAll(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            b.write(buf, 0, n);
        }
        return b.toByteArray();
    }

    private static String num(String json, String name) {
        Matcher m = Pattern.compile("\"" + name + "\":(\\d+)").matcher(json);
        return m.find() ? m.group(1) : "0";
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
        c.setReadTimeout(20000);
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
        if (code == 200 && path.endsWith(".mp3")) {
            c.disconnect();
            return new String[]{"200", ""};
        }
        byte[] b = in == null ? new byte[0] : readAll(in);
        return new String[]{String.valueOf(code), new String(b, StandardCharsets.UTF_8)};
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