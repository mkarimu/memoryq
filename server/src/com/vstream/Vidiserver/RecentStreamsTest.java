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
import java.nio.file.attribute.FileTime;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/** Vstream: recorded streams in "Recent streams" (who, title, watch, remove your own) and the testers' quick start. */
public final class RecentStreamsTest {

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

    private static void run() throws Exception {
        Path dir = Files.createTempDirectory("memoryq-recent");
        UserStore us = new UserStore(dir.resolve("users.txt"), new LiveChannelRegistry(), c -> { });
        us.init(dir.resolve("none.txt"));
        us.createUser("nelson", "password123", UserStore.Role.CHATTER);
        us.createUser("amina", "password123", UserStore.Role.CHATTER);
        SessionStore sessions = new SessionStore();
        String admin = sessions.create("admin", UserStore.Role.ADMIN);
        String nelson = sessions.create("nelson", UserStore.Role.CHATTER);
        String amina = sessions.create("amina", UserStore.Role.CHATTER);

        System.out.println("-- Who streamed what --");
        LocalDateTime start1 = LocalDateTime.now().minusMinutes(50).withNano(0);
        LocalDateTime start2 = LocalDateTime.now().minusMinutes(20).withNano(0);
        long ms1 = start1.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        long ms2 = start2.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        long[] now = {ms1 - 3000};
        StreamHistory h = new StreamHistory(dir.resolve("stream-history.txt"), () -> now[0]);
        h.init();
        h.started("CHANNEL-101", "nelson", "Morning show");
        now[0] += 30000;
        h.started("CHANNEL-101", "nelson", "Morning show");   // a reconnect: the same stream
        now[0] = ms2 - 5000;
        h.started("CHANNEL-101", "amina", "Evening talk");   // the pool gave the channel to someone else
        checkTrue("the first recording belongs to nelson", "nelson".equals(h.at("CHANNEL-101", ms1).user)
                && "Morning show".equals(h.at("CHANNEL-101", ms1).title));
        checkTrue("the later one to amina", "amina".equals(h.at("CHANNEL-101", ms2).user));
        StreamHistory again = new StreamHistory(dir.resolve("stream-history.txt"), () -> now[0]);
        again.init();
        checkTrue("kept across a restart", "amina".equals(again.at("CHANNEL-101", ms2).user) && again.at("CHANNEL-999", ms2) == null);

        System.out.println("-- Recent streams --");
        Path rec = dir.resolve("recordings");
        DateTimeFormatter f = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
        Path a = write(rec.resolve("live/CHANNEL-101/" + start1.format(f) + "-000000.mp4"), 400 * 1024, 30);
        Path b = write(rec.resolve("live/CHANNEL-101/" + start2.format(f) + "-000000.mp4"), 500 * 1024, 5);
        write(rec.resolve("live/CHANNEL-102/" + start2.format(f) + "-000000.mp4"), 10 * 1024, 5);          // failed start: too small
        write(rec.resolve("live/CHANNEL-103/" + LocalDateTime.now().format(f) + "-000000.mp4"), 900 * 1024, 0); // still going
        Recordings r = new Recordings(rec, null, us, sessions, System::currentTimeMillis);
        r.setHistory(again);
        HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        r.register(http);
        http.start();
        int port = http.getAddress().getPort();
        String[] res = call(port, "GET", "/api/streams/recent", null, null);
        checkTrue("everyone can list them", res[0].equals("200"));
        checkTrue("finished ones only, newest first, with who and the title",
                res[1].indexOf("Evening talk") > 0 && res[1].indexOf("Morning show") > res[1].indexOf("Evening talk")
                        && res[1].contains("\"user\":\"amina\"") && !res[1].contains("CHANNEL-102") && !res[1].contains("CHANNEL-103"));
        checkTrue("not yours when signed out", !res[1].contains("\"mine\":true"));
        res = call(port, "GET", "/api/streams/recent", null, nelson);
        checkTrue("nelson's own one is marked", res[1].contains("\"mine\":true") && res[1].indexOf("\"mine\":true") > res[1].indexOf("Morning show"));
        String pathA = "live/CHANNEL-101/" + a.getFileName();
        res = call(port, "GET", "/api/streams/file?path=" + pathA.replace("/", "%2F"), null, null);
        checkTrue("anyone can watch it (" + res[1].length() + " bytes)", res[0].equals("200") && res[1].length() > 300000);
        String[] part = range(port, "/api/streams/file?path=" + pathA, "bytes=0-99");
        checkTrue("players can jump in it (ranges)", part[0].equals("206") && part[1].length() == 100);
        checkTrue("nothing outside the recordings", call(port, "GET", "/api/streams/file?path=..%2Fusers.txt", null, null)[0].equals("404"));

        System.out.println("-- Removing --");
        res = call(port, "POST", "/api/streams/delete", "path=" + pathA, amina);
        checkTrue("someone else cannot remove it", res[0].equals("403") && Files.exists(a));
        res = call(port, "POST", "/api/streams/delete", "path=" + pathA, null);
        checkTrue("not signed in: no", res[0].equals("401"));
        res = call(port, "POST", "/api/streams/delete", "path=" + pathA, nelson);
        checkTrue("the one who streamed it can", res[0].equals("200") && !Files.exists(a) && !res[1].contains("Morning show"));
        res = call(port, "POST", "/api/streams/delete", "path=live/CHANNEL-101/" + b.getFileName(), admin);
        checkTrue("the admin can", res[0].equals("200") && !Files.exists(b));
        http.stop(0);

        System.out.println("-- Testers go live without an account --");
        long[] t = {System.currentTimeMillis()};
        QuickStart q = new QuickStart(us, sessions, () -> t[0], dir.resolve("vstream-quick.txt"));
        q.init();
        HttpServer h2 = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        q.register(h2);
        h2.start();
        int p2 = h2.getAddress().getPort();
        checkTrue("off at first", call(p2, "GET", "/api/vstream/quick", null, null)[1].contains("\"on\":false")
                && call(p2, "POST", "/api/vstream/quick", "", null)[0].equals("403"));
        checkTrue("only the admin switches it on", call(p2, "POST", "/api/admin/vstream-quick", "on=1&days=14", nelson)[0].equals("403"));
        res = call(p2, "POST", "/api/admin/vstream-quick", "on=1&days=14", admin);
        checkTrue("switched on for 14 days", res[0].equals("200") && q.isOn());
        res = call(p2, "POST", "/api/vstream/quick", "", null);
        String name = num(res[1], "username");
        checkTrue("a tester login, signed in (" + name + ")", res[0].equals("200") && name.startsWith("tester_") && res[1].contains("\"token\"")
                && us.info(name) != null && us.info(name).role == UserStore.Role.CHATTER);
        checkTrue("its password works for next time", us.authenticate(name, num(res[1], "password")) != null);
        call(p2, "POST", "/api/vstream/quick", "", null);
        call(p2, "POST", "/api/vstream/quick", "", null);
        checkTrue("3 an hour from one address", call(p2, "POST", "/api/vstream/quick", "", null)[0].equals("403"));
        QuickStart q2 = new QuickStart(us, sessions, () -> t[0], dir.resolve("vstream-quick.txt"));
        q2.init();
        checkTrue("still on after a restart", q2.isOn());
        t[0] += 15L * 86400000L;
        checkTrue("off by itself after the end date", !q2.isOn() && call(p2, "GET", "/api/vstream/quick", null, null)[1].contains("\"on\":false"));
        h2.stop(0);

        System.out.println(failures == 0 ? "ALL CHECKS PASSED" : failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }

    private static Path write(Path p, int bytes, int minutesAgo) throws Exception {
        Files.createDirectories(p.getParent());
        Files.write(p, new byte[bytes]);
        Files.setLastModifiedTime(p, FileTime.fromMillis(System.currentTimeMillis() - minutesAgo * 60000L));
        return p;
    }

    private static String num(String json, String key) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"" + key + "\":\"?([^\",}]*)").matcher(json);
        return m.find() ? m.group(1) : "";
    }

    private static String[] range(int port, String path, String range) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:" + port + path).openConnection();
        c.setRequestProperty("Range", range);
        int code = c.getResponseCode();
        return new String[]{String.valueOf(code), new String(read(c.getInputStream()), StandardCharsets.ISO_8859_1)};
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

    private static String[] call(int port, String method, String path, String body, String token) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:" + port + path).openConnection();
        c.setRequestMethod(method);
        c.setRequestProperty("Connection", "close"); // a fresh connection each time (Windows resets reused ones)
        c.setConnectTimeout(10000);
        c.setReadTimeout(30000);
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
        return new String[]{String.valueOf(code), new String(read(in), StandardCharsets.ISO_8859_1)};
    }

    private static void checkTrue(String name, boolean ok) {
        System.out.println((ok ? "  ok    " : "  FAIL  ") + name);
        if (!ok) {
            failures++;
        }
    }
}