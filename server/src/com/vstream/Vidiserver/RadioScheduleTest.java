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
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Playlists: rotation by weight, no repeats, in order, jingles every N songs, scheduled shows, the API. */
public final class RadioScheduleTest {

    private static int failures = 0;

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("memoryq-schedule");
        long[] now = {1768201200000L}; // Mon 2026-01-12 07:00 UTC
        RadioStore store = new RadioStore(dir.resolve("radio.txt"), dir.resolve("music"));
        store.init();
        RadioStore.Station st = store.createStation("Hits", "", "owner", RadioStore.STATUS_ACTIVE, now[0]);
        long sid = st.id;
        long[] t = new long[12];
        for (int i = 1; i <= 11; i++) {
            t[i] = store.addTrack(sid, "song" + i + ".mp3", "mp3", new byte[2000], "Song " + i, "Artist " + i, "",
                    180000, now[0]).id;
        }
        RadioScheduler sch0 = null;
        RadioScheduler sch = new RadioScheduler(store, () -> now[0], new Random(7));

        System.out.println("-- Without playlists --");
        checkTrue("no playlists: the engine shuffles everything (null)", sch.next(sid, 0) == null);

        System.out.println("-- Rotation by weight --");
        store.setNoRepeat(sid, 0);
        long a = save(store, sid, "Afrobeats", "general", 9, false, ids(t, 1, 2, 3, 4, 5));
        long b = save(store, sid, "Oldies", "general", 1, false, ids(t, 6, 7, 8));
        int fromA = 0;
        for (int i = 0; i < 2000; i++) {
            long id = sch.next(sid, 0).id;
            if (id == t[1] || id == t[2] || id == t[3] || id == t[4] || id == t[5]) {
                fromA++;
            }
        }
        checkTrue("weight 9 vs 1 plays about 90% from the first (" + fromA / 20 + "%)", fromA > 1700 && fromA < 1900);
        checkTrue("songs outside the playlists are not played", !played(sch, sid, 300).contains(t[9]));

        System.out.println("-- No repeats --");
        store.setNoRepeat(sid, 60);
        disable(store, b);
        sch = new RadioScheduler(store, () -> now[0], new Random(8));
        Set<Long> seen = new HashSet<Long>();
        long last = 0;
        boolean dupe = false;
        for (int i = 0; i < 5; i++) {
            long id = sch.next(sid, last).id;
            dupe |= !seen.add(id);
            last = id;
            now[0] += 60000;
        }
        checkTrue("5 songs, 5 different (60-minute no-repeat)", !dupe && seen.size() == 5);
        long sixth = sch.next(sid, last).id;
        checkTrue("then the one played longest ago (not the current one)", sixth != last && seen.contains(sixth));

        System.out.println("-- In order --");
        long ordered = save(store, sid, "Story", "general", 5, true, ids(t, 6, 7, 8));
        disable(store, a);
        List<Long> seq = new ArrayList<Long>();
        long cur = 0;
        for (int i = 0; i < 4; i++) {
            cur = sch.next(sid, cur).id;
            seq.add(cur);
        }
        checkTrue("6, 7, 8, then 6 again", seq.equals(Arrays.asList(t[6], t[7], t[8], t[6])));
        check2("the playlist name is known", sch.currentPlaylist(sid), "Story");

        System.out.println("-- Jingles every N songs --");
        long jingle = save(store, sid, "Station IDs", "interval", 5, false, ids(t, 9));
        RadioStore.Playlist jp = store.playlist(jingle);
        jp.every = 3;
        store.savePlaylist(jp, now[0]);
        List<Long> pattern = new ArrayList<Long>();
        for (int i = 0; i < 8; i++) {
            cur = sch.next(sid, cur).id;
            pattern.add(cur);
        }
        int jingles = 0;
        for (long id : pattern) {
            jingles += id == t[9] ? 1 : 0;
        }
        checkTrue("1 jingle after every 3 songs (8 picks: 2 jingles at 4th and 8th) " + pattern,
                jingles == 2 && pattern.get(3) == t[9] && pattern.get(7) == t[9]);

        System.out.println("-- Scheduled shows --");
        store.setTimezone(sid, "Africa/Nairobi"); // UTC+3
        long morning = saveShow(store, sid, "Morning Drive", "1111100", 6 * 60, 10 * 60, ids(t, 10));
        now[0] = 1768201200000L; // Mon 07:00 UTC = 10:00 Nairobi - just ended
        checkTrue("10:00 Nairobi: the show has ended", sch.showOnNow(sid) == null);
        now[0] = 1768194000000L; // Mon 05:00 UTC = 08:00 Nairobi
        checkTrue("08:00 Nairobi on a Monday: Morning Drive is on", sch.showOnNow(sid) != null
                && sch.showOnNow(sid).id == morning);
        boolean allShow = true;
        for (int i = 0; i < 5; i++) {
            long id = sch.next(sid, cur).id;
            allShow &= id == t[10] || id == t[9];
            cur = id;
        }
        checkTrue("during the show its songs play (and jingles still come)", allShow);
        check2("listeners can see the show", sch.currentShow(sid), "Morning Drive");
        now[0] += 5L * 86400000; // Saturday 08:00 Nairobi
        checkTrue("not on Saturday", sch.showOnNow(sid) == null);
        long night = saveShow(store, sid, "Friday Night", "0000100", 22 * 60, 2 * 60, ids(t, 11));
        now[0] = 1768602600000L; // Fri 2026-01-16 22:30 UTC = Sat 01:30 Nairobi
        checkTrue("past midnight: Friday Night still on at 01:30 Saturday", sch.showOnNow(sid) != null
                && sch.showOnNow(sid).id == night);
        now[0] += 3600000L; // 02:30
        checkTrue("and off at 02:30", sch.showOnNow(sid) == null);
        now[0] = 1768586400000L; // Fri 18:00 UTC = 21:00 Nairobi
        checkTrue("not yet at 21:00", sch.showOnNow(sid) == null);
        check2("between shows the rotation plays again", sch.next(sid, 0) == null ? "" : "ok", "ok");

        System.out.println("-- Checks and saving --");
        RadioStore.Playlist bad = new RadioStore.Playlist();
        bad.station = sid;
        bad.type = "scheduled";
        bad.name = "No days";
        bad.days = "0000000";
        checkTrue("a show without days is refused", refused(store, bad));
        bad.days = "1000000";
        bad.start = 600;
        bad.end = 600;
        checkTrue("a show without a length is refused", refused(store, bad));
        bad.type = "interval";
        bad.every = 0;
        checkTrue("every 0 songs is refused", refused(store, bad));
        bad.type = "general";
        bad.name = " ";
        checkTrue("a nameless playlist is refused", refused(store, bad));
        RadioStore.Station other = store.createStation("Other", "", "x", RadioStore.STATUS_ACTIVE, now[0]);
        long foreign = store.addTrack(other.id, "f.mp3", "mp3", new byte[2000], "Foreign", "", "", 1000, now[0]).id;
        RadioStore.Playlist mixed = store.playlist(a);
        mixed.tracks.add(foreign);
        checkTrue("another station's songs are left out", !store.savePlaylist(mixed, now[0]).tracks.contains(foreign));
        store.deleteTrack(t[1]);
        checkTrue("a deleted song leaves its playlists", !store.playlist(a).tracks.contains(t[1]));
        store.addToPlaylist(a, t[11]);
        checkTrue("new uploads can go straight into a playlist", store.playlist(a).tracks.contains(t[11]));
        store.flushIfDirty();
        RadioStore again = new RadioStore(dir.resolve("radio.txt"), dir.resolve("music"));
        again.init();
        RadioStore.Playlist rn = again.playlist(night);
        checkTrue("playlists survive a restart", again.playlists(sid).size() == 6 && rn.start == 1320 && rn.end == 120
                && rn.days.equals("0000100") && again.playlist(ordered).sequential && again.playlist(jingle).every == 3
                && again.station(sid).noRepeatMin == 60 && again.playlist(a).tracks.contains(t[11]));

        System.out.println("-- Studio API --");
        UserStore us = new UserStore(dir.resolve("users.txt"), new LiveChannelRegistry(), c -> { });
        us.init(dir.resolve("none.txt"));
        us.createUser("owner", "password123", UserStore.Role.CHATTER);
        us.createUser("xuser", "password123", UserStore.Role.CHATTER);
        SessionStore sessions = new SessionStore();
        String owner = sessions.create("owner", UserStore.Role.CHATTER);
        String x = sessions.create("xuser", UserStore.Role.CHATTER);
        RadioService radio = new RadioService(store, new AudioTools(dir.resolve("tools"), dir.resolve("tmp")), null, null, null,
                null, () -> now[0], new Random(1));
        RadioApi api = new RadioApi(radio, us, sessions, () -> now[0], dir.resolve("tmp"));
        HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        api.register(http);
        http.start();
        int port = http.getAddress().getPort();
        String[] r = post(port, "/api/studio/playlist/save", "station=" + sid + "&name=Weekend+Brunch&type=scheduled&days=0000011"
                + "&start=600&end=780&tracks=" + t[2] + "," + t[3] + "," + foreign, owner);
        checkTrue("the owner makes a show", r[0].equals("200") && r[1].contains("\"songs\":2"));
        long brunch = Long.parseLong(num(r[1], "id"));
        r = get(port, "/api/studio/playlists?station=" + sid, owner);
        checkTrue("and sees all playlists with their rules", r[0].equals("200") && r[1].contains("Weekend Brunch")
                && r[1].contains("\"days\":\"0000011\"") && r[1].contains("\"noRepeatMin\":60") && r[1].contains("\"tz\":\"Africa/Nairobi\""));
        check("someone else cannot", get(port, "/api/studio/playlists?station=" + sid, x)[0], "403");
        check("a bad playlist gives a reason", post(port, "/api/studio/playlist/save", "station=" + sid + "&name=J&type=interval&every=99", owner)[0], "400");
        r = post(port, "/api/studio/station/save", "id=" + sid + "&noRepeatMin=120", owner);
        checkTrue("no-repeat minutes are set", r[0].equals("200") && store.station(sid).noRepeatMin == 120);
        check("delete", post(port, "/api/studio/playlist/delete", "station=" + sid + "&id=" + brunch, owner)[0], "200");
        checkTrue("gone", store.playlist(brunch) == null);
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

    private static List<Long> ids(long[] t, int... which) {
        List<Long> out = new ArrayList<Long>();
        for (int w : which) {
            out.add(t[w]);
        }
        return out;
    }

    private static long save(RadioStore store, long sid, String name, String type, int weight, boolean seq, List<Long> tracks) {
        RadioStore.Playlist p = new RadioStore.Playlist();
        p.station = sid;
        p.name = name;
        p.type = type;
        p.weight = weight;
        p.sequential = seq;
        p.tracks = tracks;
        return store.savePlaylist(p, 0).id;
    }

    private static long saveShow(RadioStore store, long sid, String name, String days, int start, int end, List<Long> tracks) {
        RadioStore.Playlist p = new RadioStore.Playlist();
        p.station = sid;
        p.name = name;
        p.type = RadioStore.Playlist.SCHEDULED;
        p.days = days;
        p.start = start;
        p.end = end;
        p.tracks = tracks;
        return store.savePlaylist(p, 0).id;
    }

    private static void disable(RadioStore store, long id) {
        RadioStore.Playlist p = store.playlist(id);
        p.enabled = false;
        store.savePlaylist(p, 0);
    }

    private static boolean refused(RadioStore store, RadioStore.Playlist p) {
        try {
            store.savePlaylist(p, 0);
            return false;
        } catch (IllegalArgumentException e) {
            return true;
        }
    }

    private static Set<Long> played(RadioScheduler sch, long sid, int n) {
        Set<Long> s = new HashSet<Long>();
        for (int i = 0; i < n; i++) {
            RadioStore.Track t = sch.next(sid, 0);
            if (t != null) {
                s.add(t.id);
            }
        }
        return s;
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

    private static void check2(String name, String actual, String expected) {
        checkTrue(name + " (got " + actual + ")", expected.equals(actual));
    }

    private static void check(String name, String actual, String expected) {
        check2(name, actual, expected);
    }

    private static void checkTrue(String name, boolean ok) {
        System.out.println((ok ? "  ok    " : "  FAIL  ") + name);
        if (!ok) {
            failures++;
        }
    }
}