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
import java.util.Random;

/**
 * Station staff: only the super-admin attaches a signed-up member to a station as co-admin or presenter; the member
 * then opens that station at /studio (and Live DJ Pro) with their own login, limited to their role.
 */
public final class StaffTest {

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
        Path dir = Files.createTempDirectory("memoryq-staff");
        long[] now = {1768212000000L};
        RadioStore store = new RadioStore(dir.resolve("radio.txt"), dir.resolve("music"));
        store.init();
        RadioStore.Station st = store.createStation("Gospel FM", "", "owner1", RadioStore.STATUS_ACTIVE, now[0]);
        RadioStore.Station other = store.createStation("Other FM", "", "someone", RadioStore.STATUS_ACTIVE, now[0]);
        long sid = st.id;
        long song = store.addTrack(sid, "a.mp3", "mp3", new byte[3000], "Song A", "", "", 1000, now[0]).id;
        UserStore us = new UserStore(dir.resolve("users.txt"), new LiveChannelRegistry(), c -> { });
        us.init(dir.resolve("none.txt"));
        us.createUser("owner1", "password123", UserStore.Role.CHATTER);
        us.createUser("gadotepa26", "password123", UserStore.Role.CHATTER);
        us.createUser("presen", "password123", UserStore.Role.CHATTER);
        SessionStore sessions = new SessionStore();
        String admin = sessions.create("admin", UserStore.Role.ADMIN);
        String owner = sessions.create("owner1", UserStore.Role.CHATTER);
        String otepa = sessions.create("gadotepa26", UserStore.Role.CHATTER);
        String pres = sessions.create("presen", UserStore.Role.CHATTER);
        RadioService radio = new RadioService(store, new AudioTools(dir.resolve("tools"), dir.resolve("tmp")), null, null, null, null,
                () -> now[0], new Random(1));
        RadioApi api = new RadioApi(radio, us, sessions, () -> now[0], dir.resolve("tmp"));
        HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        http.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        api.register(http);
        http.start();
        int port = http.getAddress().getPort();
        try {
            System.out.println("-- Before: a member sees no station --");
            String[] r = call(port, "GET", "/api/studio/status", null, otepa);
            checkTrue("gadotepa26 signs in to the Studio but has no station", r[0].equals("200") && !r[1].contains("Gospel FM"));
            checkTrue("and cannot open it", call(port, "GET", "/api/studio/tracks?station=" + sid, null, otepa)[0].equals("403"));

            System.out.println("-- Only the super-admin gives roles --");
            checkTrue("the owner cannot", call(port, "POST", "/api/admin/radio/staff/save", "station=" + sid + "&user=gadotepa26&role=coadmin", owner)[0].equals("403"));
            checkTrue("not through the Studio either", call(port, "POST", "/api/studio/staff/save", "station=" + sid + "&user=gadotepa26&role=coadmin", owner)[0].equals("403"));
            checkTrue("a member cannot give themselves one", call(port, "POST", "/api/admin/radio/staff/save", "station=" + sid + "&user=gadotepa26&role=coadmin", otepa)[0].equals("403"));
            r = call(port, "POST", "/api/admin/radio/staff/save", "station=" + sid + "&user=nobody&role=coadmin", admin);
            checkTrue("only members who signed up (" + r[1] + ")", r[0].equals("404") && r[1].contains("sign up"));
            checkTrue("not the owner", call(port, "POST", "/api/admin/radio/staff/save", "station=" + sid + "&user=owner1&role=coadmin", admin)[0].equals("400"));
            checkTrue("only coadmin / presenter", call(port, "POST", "/api/admin/radio/staff/save", "station=" + sid + "&user=presen&role=boss", admin)[0].equals("400"));
            r = call(port, "POST", "/api/admin/radio/staff/save", "station=" + sid + "&user=GadOtepa26&role=coadmin", admin);
            checkTrue("the admin makes gadotepa26 co-admin", r[0].equals("200") && r[1].contains("\"user\":\"gadotepa26\"") && r[1].contains("\"role\":\"coadmin\""));
            r = call(port, "POST", "/api/admin/radio/staff/save", "station=" + sid + "&user=presen&role=presenter", admin);
            checkTrue("and presen presenter", r[0].equals("200") && r[1].contains("\"role\":\"presenter\""));
            r = call(port, "GET", "/api/admin/radio/staff?station=" + sid, null, admin);
            checkTrue("the admin lists the station's staff", r[0].equals("200") && r[1].contains("gadotepa26") && r[1].contains("presen"));

            System.out.println("-- The co-admin --");
            r = call(port, "GET", "/api/studio/status", null, otepa);
            checkTrue("Gospel FM is in gadotepa26's Studio, as co-admin, not Other FM", r[1].contains("Gospel FM") && r[1].contains("\"myRole\":\"coadmin\"")
                    && !r[1].contains("Other FM"));
            checkTrue("sees the music", call(port, "GET", "/api/studio/tracks?station=" + sid, null, otepa)[0].equals("200"));
            checkTrue("changes settings", call(port, "POST", "/api/studio/station/save", "id=" + sid + "&talkEvery=4", otepa)[0].equals("200"));
            r = call(port, "POST", "/api/studio/dj/save", "station=" + sid + "&id=0&name=Night&login=nightdj&password=NightLive2026&days=1111111&from=0&to=24&studio=true", otepa);
            checkTrue("adds DJ logins (" + r[1] + ")", r[0].equals("200"));
            checkTrue("but not payment or plans", call(port, "POST", "/api/studio/pay", "station=" + sid + "&plan=1", otepa)[0].equals("403")
                    && call(port, "POST", "/api/studio/plan/change", "station=" + sid + "&plan=1", otepa)[0].equals("403"));
            checkTrue("and nothing on other stations", call(port, "GET", "/api/studio/tracks?station=" + other.id, null, otepa)[0].equals("403"));

            System.out.println("-- The presenter --");
            r = call(port, "GET", "/api/studio/status", null, pres);
            checkTrue("presenter: the station, without the DJ logins or the live password", r[1].contains("\"myRole\":\"presenter\"")
                    && r[1].contains("\"djs\":[]") && r[1].contains("\"livePass\":\"\""));
            checkTrue("works on the music and playlists", call(port, "GET", "/api/studio/tracks?station=" + sid, null, pres)[0].equals("200")
                    && call(port, "POST", "/api/studio/playlist/save", "station=" + sid + "&id=0&name=Sunday+Praise&type=general&weight=5&tracks=" + song, pres)[0].equals("200"));
            checkTrue("not the settings, DJs or deleting songs", call(port, "POST", "/api/studio/station/save", "id=" + sid + "&talkEvery=2", pres)[0].equals("403")
                    && call(port, "POST", "/api/studio/dj/save", "station=" + sid + "&id=0&name=X&login=xdj&password=XLive20261", pres)[0].equals("403")
                    && call(port, "POST", "/api/studio/track/delete", "id=" + song, pres)[0].equals("403"));

            System.out.println("-- Live DJ Pro --");
            r = call(port, "GET", "/api/djpro/state?station=" + sid, null, otepa);
            checkTrue("staff open the station in Live DJ Pro with their own login (" + r[0] + ")", r[0].equals("200") && r[1].contains("Gospel FM"));
            checkTrue("not other stations", call(port, "GET", "/api/djpro/state?station=" + other.id, null, pres)[0].equals("403"));

            System.out.println("-- Kept, changed, removed --");
            store.flushIfDirty();
            RadioStore again = new RadioStore(dir.resolve("radio.txt"), dir.resolve("music"));
            again.init();
            checkTrue("kept across a restart", "coadmin".equals(again.staffRole(sid, "gadotepa26")) && "presenter".equals(again.staffRole(sid, "presen")));
            call(port, "POST", "/api/admin/radio/staff/save", "station=" + sid + "&user=gadotepa26&role=presenter", admin);
            checkTrue("the admin changes the role: settings closed at once", call(port, "POST", "/api/studio/station/save", "id=" + sid + "&talkEvery=4", otepa)[0].equals("403"));
            r = call(port, "POST", "/api/admin/radio/staff/delete", "station=" + sid + "&user=gadotepa26", admin);
            checkTrue("the admin removes gadotepa26", r[0].equals("200") && !r[1].contains("gadotepa26"));
            checkTrue("the station is gone from their Studio at once", !call(port, "GET", "/api/studio/status", null, otepa)[1].contains("Gospel FM")
                    && call(port, "GET", "/api/studio/tracks?station=" + sid, null, otepa)[0].equals("403"));
            store.deleteStation(sid);
            checkTrue("deleting the station removes its staff", store.staffOf(0).isEmpty());
        } finally {
            http.stop(0);
        }
        System.out.println(failures == 0 ? "ALL CHECKS PASSED" : failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
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
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        if (in != null) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) {
                b.write(buf, 0, n);
            }
        }
        return new String[]{String.valueOf(code), new String(b.toByteArray(), StandardCharsets.ISO_8859_1)};
    }

    private static void checkTrue(String name, boolean ok) {
        System.out.println((ok ? "  ok    " : "  FAIL  ") + name);
        if (!ok) {
            failures++;
        }
    }
}