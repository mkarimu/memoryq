package com.vstream.Vidiserver;

import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Station owners, approval, live DJ ports and passwords, a real live DJ connection, and daily statistics. */
public final class StudioTest {

    private static int failures = 0;

    public static void main(String[] args) throws Exception {
        System.setProperty("vstream.livePortMin", "18310");
        System.setProperty("vstream.livePortMax", "18319");
        Path dir = Files.createTempDirectory("memoryq-studio");

        System.out.println("-- Stations get live DJ ports --");
        Files.write(dir.resolve("old.txt"), ("S\t1\tOld Hits\tGood music\tDJ Memoryq\t\t1\t3\t1\t1\t1\t100\tpromo\tend\n")
                .getBytes(StandardCharsets.UTF_8));
        RadioStore old = new RadioStore(dir.resolve("old.txt"), dir.resolve("oldmusic"));
        old.init();
        RadioStore.Station o = old.station(1);
        checkTrue("an older station gets a port and a password", o.livePort == 18310 && o.livePass.length() == 12);
        checkTrue("an older station stays active and the platform's own", o.onAir() && o.owner.isEmpty());
        old.flushIfDirty();
        RadioStore old2 = new RadioStore(dir.resolve("old.txt"), dir.resolve("oldmusic"));
        old2.init();
        checkTrue("port and password are saved", old2.station(1).livePort == 18310
                && old2.station(1).livePass.equals(o.livePass) && old2.station(1).promo.equals("promo"));

        Path users = dir.resolve("users.txt");
        UserStore us = new UserStore(users, new LiveChannelRegistry(), c -> { });
        us.init(dir.resolve("none.txt"));
        us.createUser("nelson", "password123", UserStore.Role.CHATTER);
        us.createUser("other", "password123", UserStore.Role.CHATTER);
        SessionStore sessions = new SessionStore();
        String admin = sessions.create("admin", UserStore.Role.ADMIN);
        String nelson = sessions.create("nelson", UserStore.Role.CHATTER);
        String other = sessions.create("other", UserStore.Role.CHATTER);

        RadioStore store = new RadioStore(dir.resolve("radio.txt"), dir.resolve("music"));
        store.init();
        AudioTools tools = new AudioTools(java.nio.file.Paths.get(System.getProperty("vstream.toolsDir",
                dir.resolve("tools").toString())), dir.resolve("tmp"));
        tools.detect();
        long[] now = {System.currentTimeMillis()};
        RadioService radio = new RadioService(store, tools, null, null, null, null, () -> now[0], new Random(3));
        RadioStats stats = new RadioStats(dir.resolve("radio-stats.txt"), () -> now[0]);
        stats.init();
        radio.setStats(stats);
        radio.setLiveEnabled(true);
        RadioApi api = new RadioApi(radio, us, sessions, () -> now[0], dir.resolve("tmp"));
        api.setLiveHost("radio.example");
        HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        api.register(http);
        http.start();
        int port = http.getAddress().getPort();

        System.out.println("-- Asking for a station --");
        String[] r = post(port, "/api/studio/request", "name=Nelson+FM&description=All+my+songs", nelson);
        check("an owner asks for a station", r[0], "200");
        long id = Long.parseLong(num(r[1], "id"));
        checkTrue("it waits for the admin", r[1].contains("\"pending\"") && !store.station(id).onAir());
        r = post(port, "/api/studio/request", "name=Second+FM&description=x", nelson);
        check("one request at a time", r[0], "409");
        r = get(port, "/api/studio/status", nelson);
        checkTrue("the owner sees only their own station", r[1].contains("Nelson FM") && !r[1].contains("Old Hits")
                && r[1].contains("\"livePort\":1831") && r[1].contains("radio.example"));
        r = get(port, "/api/studio/status", other);
        checkTrue("someone else sees no stations", r[0].equals("200") && r[1].contains("\"stations\":[]"));
        r = post(port, "/api/studio/station/save", "id=" + id + "&name=Stolen", other);
        check("someone else cannot change it", r[0], "403");
        r = post(port, "/api/studio/station/admin", "id=" + id + "&status=active", nelson);
        check("an owner cannot approve their own station", r[0], "403");
        r = post(port, "/api/admin/radio/status", "", nelson);
        check("an owner cannot use the admin address", r[0], "403");
        r = get(port, "/api/radio/stations", null);
        checkTrue("a waiting station is not listed for listeners", !r[1].contains("Nelson FM"));

        System.out.println("-- The admin approves and sets the port --");
        r = post(port, "/api/admin/radio/station/admin", "id=" + id + "&status=active&billing=paid&livePort=18318", admin);
        check("approved", r[0], "200");
        checkTrue("active on port 18318", store.station(id).onAir() && store.station(id).livePort == 18318);
        r = post(port, "/api/admin/radio/station/admin", "id=" + id + "&livePort=9999", admin);
        check("a port outside the range is refused", r[0], "400");
        r = post(port, "/api/studio/station/save", "id=" + id + "&name=Nelson+Radio&description=Hits+all+day&djName=DJ+Nel"
                + "&talkEvery=0&newsOn=false&shoutOn=false&introsOn=false", nelson);
        check("the owner renames and sets a slogan", r[0], "200");
        checkTrue("saved", store.station(id).name.equals("Nelson Radio") && store.station(id).description.equals("Hits all day"));
        String oldPass = store.station(id).livePass;
        r = post(port, "/api/studio/live/password", "station=" + id, nelson);
        checkTrue("a new live password", r[0].equals("200") && !store.station(id).livePass.equals(oldPass));
        String pass = store.station(id).livePass;

        System.out.println("-- A live DJ connects --");
        radio.refresh();
        if (tools.ready()) {
            checkTrue("the live door is open", radio.liveDoor(id) != null && radio.liveDoor(id).port() == 18318);
            checkTrue("a wrong password is refused", sourceReply(18318, "wrong").startsWith("HTTP/1.0 401"));
            byte[] mp3 = tone(tools, 8);
            Socket dj = new Socket("127.0.0.1", 18318);
            OutputStream out = dj.getOutputStream();
            out.write(("SOURCE /live ICE/1.0\r\nAuthorization: Basic "
                    + Base64.getEncoder().encodeToString(("source:" + pass).getBytes(StandardCharsets.UTF_8))
                    + "\r\nContent-Type: audio/mpeg\r\nice-name: Friday Night Show\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            out.flush();
            String reply = readLine(dj.getInputStream());
            check("the right password is let in", reply, "HTTP/1.0 200 OK");
            for (int i = 0; i < mp3.length; i += 4096) {
                out.write(mp3, i, Math.min(4096, mp3.length - i));
                out.flush();
                Thread.sleep(40);
            }
            RadioEngine e = waitFor(() -> radio.engine(id) != null && radio.engine(id).liveOnAir() ? radio.engine(id) : null, 8000);
            checkTrue("the station is on air with the live DJ", e != null && e.liveOnAir());
            checkTrue("a second DJ is turned away", sourceReply(18318, pass).startsWith("HTTP/1.0 403"));
            r = get(port, "/api/radio/stations", null);
            checkTrue("listeners see LIVE", r[1].contains("\"liveDj\":true") && r[1].contains("LIVE: Friday Night Show"));
            r = get(port, "/api/studio/status", nelson);
            checkTrue("the owner sees the DJ", r[1].contains("\"liveOn\":true") && r[1].contains("Friday Night Show"));
            r = post(port, "/api/studio/skip", "station=" + id, nelson);
            check("skip is refused while live", r[0], "409");
            dj.close();
            waitFor(() -> {
                RadioEngine x = radio.engine(id);
                return x == null || !x.liveOnAir() ? Boolean.TRUE : null;
            }, 20000);
            checkTrue("after the DJ leaves, the station is no longer live",
                    radio.engine(id) == null || !radio.engine(id).liveOnAir());
        } else {
            System.out.println("  (FFmpeg not found - live sound part skipped)");
        }

        System.out.println("-- Suspending --");
        r = post(port, "/api/admin/radio/station/admin", "id=" + id + "&status=suspended", admin);
        check("the admin suspends", r[0], "200");
        radio.refresh();
        checkTrue("off air, live door closed", !store.station(id).onAir() && radio.liveDoor(id) == null);
        radio.stopAll();
        r = post(port, "/api/studio/station/save", "id=" + id + "&name=Back", nelson);
        check("a suspended owner cannot change it", r[0], "403");
        r = post(port, "/api/admin/radio/station/admin", "id=" + id + "&status=active", admin);
        check("and back on", r[0], "200");

        System.out.println("-- Statistics --");
        stats.listener(id, "1.2.3.4");
        stats.listener(id, "1.2.3.4");
        stats.listener(id, "5.6.7.8");
        stats.played(id);
        stats.liked(id);
        stats.requested(id);
        stats.tapped(id);
        Map<Long, Integer> counts = new HashMap<Long, Integer>();
        counts.put(id, 3);
        stats.sample(counts, new HashSet<Long>());
        now[0] += 60000;
        stats.sample(counts, new HashSet<Long>());
        r = get(port, "/api/studio/stats?station=" + id + "&days=7", nelson);
        checkTrue("the owner gets statistics", r[0].equals("200") && r[1].contains("\"uniques\":2")
                && r[1].contains("\"peak\":3") && r[1].contains("\"hours\":0.05") && r[1].contains("\"likes\":1"));
        List<RadioStats.Day> days = stats.lastDays(id, 7);
        checkTrue("seven days, today last", days.size() == 7 && days.get(6).listenSeconds == 180);
        r = get(port, "/api/studio/stats?station=" + id, other);
        check("someone else cannot see them", r[0], "403");
        stats.flushIfDirty();
        RadioStats again = new RadioStats(dir.resolve("radio-stats.txt"), () -> now[0]);
        again.init();
        checkTrue("statistics survive a restart", again.lastDays(id, 1).get(0).uniques == 2);

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

    /** An MP3 tone made with FFmpeg. */
    private static byte[] tone(AudioTools tools, int seconds) throws Exception {
        Process pr = new ProcessBuilder(tools.ffmpegPath(), "-v", "error", "-f", "lavfi", "-i",
                "sine=frequency=440:duration=" + seconds, "-ac", "2", "-ar", "44100", "-c:a", "libmp3lame", "-b:a", "128k",
                "-f", "mp3", "pipe:1").start();
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        InputStream in = pr.getInputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            b.write(buf, 0, n);
        }
        pr.waitFor();
        return b.toByteArray();
    }

    private static String sourceReply(int port, String password) throws Exception {
        try (Socket s = new Socket("127.0.0.1", port)) {
            s.setSoTimeout(5000);
            s.getOutputStream().write(("SOURCE /live ICE/1.0\r\nAuthorization: Basic "
                    + Base64.getEncoder().encodeToString(("source:" + password).getBytes(StandardCharsets.UTF_8))
                    + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            s.getOutputStream().flush();
            return readLine(s.getInputStream());
        }
    }

    private static String readLine(InputStream in) throws Exception {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) >= 0 && c != '\n') {
            if (c != '\r') {
                sb.append((char) c);
            }
        }
        return sb.toString();
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
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        if (in != null) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                b.write(buf, 0, n);
            }
        }
        return new String[]{String.valueOf(code), new String(b.toByteArray(), StandardCharsets.UTF_8)};
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