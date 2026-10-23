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

/** "Try the Studio": the shared trial login, what it may do, and the station going back to its starting point. */
public final class DemoRadioTest {

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
        Path dir = Files.createTempDirectory("memoryq-demo");
        UserStore us = new UserStore(dir.resolve("users.txt"), new LiveChannelRegistry(), c -> { });
        us.init(dir.resolve("none.txt"));
        us.createUser("nelson", "password123", UserStore.Role.CHATTER);
        SessionStore sessions = new SessionStore();
        String admin = sessions.create("admin", UserStore.Role.ADMIN);
        String nelson = sessions.create("nelson", UserStore.Role.CHATTER);

        RadioStore store = new RadioStore(dir.resolve("radio.txt"), dir.resolve("music"));
        store.init();
        AudioTools tools = new AudioTools(dir.resolve("tools"), dir.resolve("tmp"));
        long[] now = {1_800_000_000_000L + 1000};
        RadioService radio = new RadioService(store, tools, null, null, null, null, () -> now[0], new Random(3));
        RadioStories stories = new RadioStories(dir.resolve("stories"));
        stories.init();
        radio.setStories(stories);
        RadioApi api = new RadioApi(radio, us, sessions, () -> now[0], dir.resolve("tmp"));

        RadioStore.Station nel = store.createStation("Nelson FM", "", "nelson", RadioStore.STATUS_ACTIVE, now[0]);
        RadioStore.Station test = store.createStation("Memoryq Test Radio", "Try me", "", RadioStore.STATUS_ACTIVE, now[0]);
        long id = test.id;
        RadioStore.Track a = store.addTrack(id, "a.mp3", "mp3", bytes(3000, 1), "Song A", "Artist", "", 1000, now[0]);
        RadioStore.Track b = store.addTrack(id, "b.mp3", "mp3", bytes(4000, 2), "Song B", "Artist", "", 1000, now[0]);
        RadioStore.Track other = store.addTrack(nel.id, "n.mp3", "mp3", bytes(2000, 3), "Nelson's", "", "", 1000, now[0]);
        RadioStore.Playlist pl = new RadioStore.Playlist();
        pl.station = id;
        pl.name = "Hits";
        pl.tracks.add(a.id);
        pl.tracks.add(b.id);
        pl = store.savePlaylist(pl, now[0]);
        stories.add(id, "The Lake", "", "Once upon a time there was a lake.\n\nThe end.", now[0]);

        DemoRadio demo = new DemoRadio(radio, us, sessions, () -> now[0], dir.resolve("demo-radio.txt"), dir.resolve("demo-radio"));
        demo.init();
        api.setDemo(demo);

        System.out.println("-- The admin starts the trial --");
        checkTrue("an existing account is not taken over", refused(() -> demo.setup(id, "nelson", "tryradio1", false)));
        checkTrue("the admin account is never the trial login", refused(() -> demo.setup(id, "admin", "tryradio1", false)));
        checkTrue("someone's station is not taken over", refused(() -> demo.setup(nel.id, "trial", "tryradio1", false)));
        checkTrue("a short password is refused", refused(() -> demo.setup(id, "trial", "short", false)));
        checkTrue("a name another station has is refused", refused(() -> demo.setup(id, "trial", "tryradio1", false, "Nelson FM")));
        demo.setup(id, "trial", "tryradio1", false, "Try Radio");
        checkTrue("the station gets its trial name", store.station(id).name.equals("Try Radio"));
        checkTrue("the trial is on, the login works", demo.isOn() && us.authenticate("trial", "tryradio1") != null);
        checkTrue("the station is run by the trial login and on air", "trial".equals(store.station(id).owner) && store.station(id).onAir());
        checkTrue("the next fresh start is at the next even hour", demo.nextReset() > now[0] && demo.nextReset() % DemoRadio.PERIOD_MS == 0);

        HttpServer real = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        HttpServer http = demo.wrap(real);
        api.register(http);
        demo.register(http);
        http.createContext("/api/password", ex -> {
            AudioTools.readAll(ex.getRequestBody());
            ex.sendResponseHeaders(200, -1);
            ex.close();
        });
        http.createContext("/api/wall/", ex -> {
            AudioTools.readAll(ex.getRequestBody());
            ex.sendResponseHeaders(200, -1);
            ex.close();
        });
        real.start();
        int port = real.getAddress().getPort();

        System.out.println("-- Visitors see the login --");
        String[] r = get(port, "/api/demo", null);
        checkTrue("the sign-in page gets user and password", r[0].equals("200") && r[1].contains("\"user\":\"trial\"")
                && r[1].contains("\"password\":\"tryradio1\"") && r[1].contains("Try Radio"));
        String trial = sessions.create("trial", UserStore.Role.CHATTER);

        System.out.println("-- What the trial login may do --");
        r = get(port, "/api/studio/status", trial);
        checkTrue("it opens the Studio of the trial station", r[0].equals("200") && r[1].contains("Try Radio")
                && r[1].contains("\"trialLogin\":true") && !r[1].contains("Nelson FM"));
        checkTrue("the live DJ password is not shown", r[1].contains("\"livePass\":\"\""));
        r = post(port, "/api/password", "old=tryradio1&password=hijacked1", trial);
        check("it cannot change the password", r[0], "403");
        r = post(port, "/api/wall/post", "text=hi", trial);
        check("it cannot post on walls", r[0], "403");
        r = post(port, "/api/password", "x=1", nelson);
        check("other accounts are not affected", r[0], "200");
        r = post(port, "/api/studio/live/password", "station=" + id, trial);
        check("no new live DJ password", r[0], "403");
        r = post(port, "/api/studio/request", "name=Mine", trial);
        check("no second station", r[0], "403");
        r = post(port, "/api/studio/pay", "station=" + id, trial);
        check("no buying", r[0], "403");
        r = post(port, "/api/studio/dj/save", "station=" + id + "&name=x&login=x&password=xxxxxxxx", trial);
        check("no DJ logins", r[0], "403");
        r = get(port, "/api/admin/demo/status", trial);
        check("not the admin settings", r[0], "403");
        r = post(port, "/api/studio/station/save", "id=" + id + "&name=Rude+Name&promo=Hello+from+a+visitor&website=https://spam.example", trial);
        check("it changes the settings", r[0], "200");
        checkTrue("but not the name or the website", store.station(id).name.equals("Try Radio")
                && store.station(id).website.isEmpty() && store.station(id).promo.equals("Hello from a visitor"));
        r = get(port, "/api/radio/stations", null);
        checkTrue("the trial station is not in the public list", r[0].equals("200") && !r[1].contains("Try Radio"));
        demo.settings(null, true);
        r = get(port, "/api/radio/stations", null);
        checkTrue("unless the admin wants it there", r[1].contains("Try Radio"));

        System.out.println("-- Visitors change things --");
        RadioStore.Track added = store.addTrack(id, "c.mp3", "mp3", bytes(5000, 4), "Visitor song", "", "", 1000, now[0]);
        store.deleteTrack(a.id);
        store.editTrack(b.id, "Renamed", "Someone");
        store.replaceTrackFile(b.id, "mp3", bytes(4500, 5), 900);
        RadioStore.Playlist extra = new RadioStore.Playlist();
        extra.station = id;
        extra.name = "Visitor list";
        extra = store.savePlaylist(extra, now[0]);
        store.deletePlaylist(id, pl.id);
        stories.add(id, "Visitor book", "", "Some text here.", now[0]);
        checkTrue("room for visitors' songs is limited", demo.uploadRoom(id) < DemoRadio.EXTRA_BYTES && demo.uploadRoom(nel.id) == Long.MAX_VALUE);
        Path addedFile = store.pathOf(added);

        System.out.println("-- Back to the starting point --");
        now[0] = demo.nextReset() - 1000;
        demo.tick();
        checkTrue("nothing happens before the time", store.track(added.id) != null);
        now[0] = demo.nextReset() + 1000;
        demo.tick();
        checkTrue("visitors' songs are gone, with their files", store.track(added.id) == null && !Files.exists(addedFile));
        RadioStore.Track ba = store.track(a.id), bb = store.track(b.id);
        checkTrue("a deleted song is back with its file", ba != null && ba.title.equals("Song A") && Files.size(store.pathOf(ba)) == 3000);
        checkTrue("an edited song is as it was", bb != null && bb.title.equals("Song B") && Files.size(store.pathOf(bb)) == 4000);
        checkTrue("other stations are untouched", store.track(other.id) != null && store.station(nel.id).owner.equals("nelson"));
        boolean hits = false, visitorList = false;
        for (RadioStore.Playlist p : store.playlists(id)) {
            hits |= p.name.equals("Hits") && p.tracks.size() == 2;
            visitorList |= p.name.equals("Visitor list");
        }
        checkTrue("playlists are as they were", hits && !visitorList);
        checkTrue("stories are as they were", stories.books(id).size() == 1 && stories.books(id).get(0).title.equals("The Lake"));
        checkTrue("settings are as they were", store.station(id).promo.isEmpty() && store.station(id).description.equals("Try me"));
        checkTrue("everyone trying it was signed out", sessions.get(trial) == null && sessions.get(nelson) != null);
        checkTrue("the login still works", us.authenticate("trial", "tryradio1") != null);
        long files;
        try (java.util.stream.Stream<Path> l = Files.list(store.pathOf(ba).getParent())) {
            files = l.count();
        }
        checkTrue("no leftover files (" + files + ")", files == 2);

        System.out.println("-- A restart --");
        DemoRadio again = new DemoRadio(radio, us, sessions, () -> now[0], dir.resolve("demo-radio.txt"), dir.resolve("demo-radio"));
        again.init();
        checkTrue("the trial is still on after a restart", again.isOn() && again.isDemoUser("TRIAL") && again.isDemoStation(id)
                && again.nextReset() == demo.nextReset());
        RadioStore reread = new RadioStore(dir.resolve("radio.txt"), dir.resolve("music"));
        reread.init();
        checkTrue("the restored station was saved", reread.track(a.id) != null && reread.track(added.id) == null);

        System.out.println("-- The admin --");
        r = post(port, "/api/admin/demo/settings", "password=newpass99&listed=0", admin);
        checkTrue("a new public password", r[0].equals("200") && us.authenticate("trial", "newpass99") != null);
        r = post(port, "/api/admin/demo/reset", "", admin);
        checkTrue("fresh start now", r[0].equals("200") && r[1].contains("Back to the starting point"));
        r = post(port, "/api/admin/demo/stop", "", admin);
        checkTrue("switched off: the login no longer works", r[0].equals("200") && !demo.isOn() && us.authenticate("trial", "newpass99") == null);
        r = get(port, "/api/demo", null);
        checkTrue("the sign-in page shows no trial", r[1].contains("\"on\":false"));
        String t2 = sessions.create("trial", UserStore.Role.CHATTER);
        r = post(port, "/api/wall/post", "text=hi", t2);
        check("when off, the gate lets everything through (the account is disabled anyway)", r[0], "200");

        real.stop(0);
        System.out.println(failures == 0 ? "ALL PASSED" : failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }

    interface Action {
        void run() throws Exception;
    }

    private static boolean refused(Action a) {
        try {
            a.run();
            return false;
        } catch (IllegalArgumentException e) {
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static byte[] bytes(int n, int seed) {
        byte[] b = new byte[n];
        new Random(seed).nextBytes(b);
        return b;
    }

    private static String[] get(int port, String path, String token) throws Exception {
        return call(port, "GET", path, null, token);
    }

    private static String[] post(int port, String path, String body, String token) throws Exception {
        return call(port, "POST", path, body, token);
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