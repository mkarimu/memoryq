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
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Random;

/**
 * The playlist page: repeat (once / every N minutes, hours, days, weeks, months), interrupt yes/no, remote DJ may take
 * over, weight 0-10, AI per playlist and per station, Studio playlists; and downloads with descriptions.
 */
public final class PlaylistStudioTest {

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
        Path dir = Files.createTempDirectory("memoryq-pl3");
        RadioStore store = new RadioStore(dir.resolve("radio.txt"), dir.resolve("music"));
        store.init();
        long[] now = {ZonedDateTime.of(2026, 10, 22, 10, 30, 0, 0, ZoneOffset.UTC).toInstant().toEpochMilli()};
        RadioStore.Station st = store.createStation("Rules FM", "", "nelson", RadioStore.STATUS_ACTIVE, now[0]);
        long sid = st.id;
        RadioStore.Track a = store.addTrack(sid, "a.mp3", "mp3", new byte[3000], "Song A", "", "", 1000, now[0]);
        RadioStore.Track b = store.addTrack(sid, "b.mp3", "mp3", new byte[3000], "Song B", "", "", 1000, now[0]);
        RadioStore.Track c = store.addTrack(sid, "c.mp3", "mp3", new byte[3000], "Song C", "", "", 1000, now[0]);

        System.out.println("-- Repeat rules --");
        RadioStore.Playlist p = new RadioStore.Playlist();
        p.when = "every:3:day:2026-10-20";
        checkTrue("every 3 days from Oct 20: 20, 23, 26 - not 21, not before", p.dayMatches(LocalDate.of(2026, 10, 20))
                && p.dayMatches(LocalDate.of(2026, 10, 23)) && p.dayMatches(LocalDate.of(2026, 10, 26))
                && !p.dayMatches(LocalDate.of(2026, 10, 21)) && !p.dayMatches(LocalDate.of(2026, 10, 17)));
        p.when = "every:2:week:2026-10-20";
        checkTrue("every 2 weeks: the same weekday, every other week", p.dayMatches(LocalDate.of(2026, 11, 3))
                && !p.dayMatches(LocalDate.of(2026, 10, 27)) && !p.dayMatches(LocalDate.of(2026, 11, 4)));
        p.when = "every:1:month:2026-01-31";
        checkTrue("every month on the 31st: the last day in shorter months", p.dayMatches(LocalDate.of(2026, 2, 28))
                && p.dayMatches(LocalDate.of(2026, 3, 31)) && !p.dayMatches(LocalDate.of(2026, 3, 30)));
        checkTrue("its words", p.whenText().equals("every month from 2026-01-31"));
        p.when = "every:2:month:2026-01-15";
        checkTrue("every 2 months", p.dayMatches(LocalDate.of(2026, 3, 15)) && !p.dayMatches(LocalDate.of(2026, 2, 15)));

        System.out.println("-- Saving the new settings --");
        RadioStore.Playlist show = new RadioStore.Playlist();
        show.station = sid;
        show.name = "Morning Show";
        show.type = RadioStore.Playlist.SCHEDULED;
        show.start = 600;
        show.end = 720;
        show.when = "every:1:day:2026-10-01";
        show.interrupt = "no";
        show.djOverride = false;
        show.ai = false;
        show.studio = true;
        show.weight = 0;
        show.tracks.add(a.id);
        show = store.savePlaylist(show, now[0]);
        RadioStore.Playlist bad = new RadioStore.Playlist();
        bad.station = sid;
        bad.name = "Bad";
        bad.type = RadioStore.Playlist.SCHEDULED;
        bad.start = 60;
        bad.end = 120;
        bad.when = "every:0:day:2026-10-01";
        checkTrue("a broken rule is refused", refused(() -> store.savePlaylist(bad, now[0])));
        RadioStore.Playlist hourly = new RadioStore.Playlist();
        hourly.station = sid;
        hourly.name = "Hourly IDs";
        hourly.type = RadioStore.Playlist.INTERVAL;
        hourly.unit = "minutes";
        hourly.every = 180;
        hourly.tracks.add(c.id);
        hourly = store.savePlaylist(hourly, now[0]);
        checkTrue("every 3 hours = every 180 minutes", hourly.every == 180 && "minutes".equals(hourly.unit));
        store.flushIfDirty();
        RadioStore again = new RadioStore(dir.resolve("radio.txt"), dir.resolve("music"));
        again.init();
        RadioStore.Playlist back = again.playlist(show.id);
        checkTrue("kept: interrupt no, DJ may not take over, AI off, studio on, weight 0, the rule",
                "no".equals(back.interrupt) && !back.djOverride && !back.ai && back.studio && back.weight == 0
                        && "every:1:day:2026-10-01".equals(back.when));
        checkTrue("older playlists: interruptible, DJs welcome, AI on, not in the studio", "yes".equals(again.playlist(hourly.id).interrupt)
                && again.playlist(hourly.id).djOverride && again.playlist(hourly.id).ai && !again.playlist(hourly.id).studio);
        checkTrue("the station's AI is on by default and kept", again.station(sid).aiOn);
        store.setAiOn(sid, false);
        store.flushIfDirty();
        RadioStore third = new RadioStore(dir.resolve("radio.txt"), dir.resolve("music"));
        third.init();
        checkTrue("switched off by the admin and kept", !third.station(sid).aiOn);
        store.setAiOn(sid, true);

        System.out.println("-- Weight 0 --");
        RadioStore.Playlist rot = new RadioStore.Playlist();
        rot.station = sid;
        rot.name = "Main";
        rot.weight = 5;
        rot.tracks.add(b.id);
        store.savePlaylist(rot, now[0]);
        RadioStore.Playlist zero = new RadioStore.Playlist();
        zero.station = sid;
        zero.name = "Not in rotation";
        zero.weight = 0;
        zero.tracks.add(c.id);
        zero = store.savePlaylist(zero, now[0]);
        now[0] = ZonedDateTime.of(2026, 10, 22, 8, 0, 0, 0, ZoneOffset.UTC).toInstant().toEpochMilli(); // no show on
        RadioScheduler sch = new RadioScheduler(store, () -> now[0], new Random(1));
        boolean zeroPicked = false;
        for (int i = 0; i < 40; i++) {
            RadioStore.Track t = sch.next(sid, 0);
            zeroPicked |= t != null && "Not in rotation".equals(sch.currentPlaylist(sid));
        }
        checkTrue("a weight-0 playlist is never in the rotation", !zeroPicked);

        System.out.println("-- Interrupt --");
        RadioStore.Playlist late = new RadioStore.Playlist();
        late.station = sid;
        late.name = "Late Show";
        late.type = RadioStore.Playlist.SCHEDULED;
        late.start = 660;
        late.end = 780;
        late.tracks.add(b.id);
        late = store.savePlaylist(late, now[0]);
        now[0] = ZonedDateTime.of(2026, 10, 22, 10, 30, 0, 0, ZoneOffset.UTC).toInstant().toEpochMilli();
        sch.next(sid, 0);
        checkTrue("10:30 the Morning Show plays", "Morning Show".equals(sch.currentShow(sid)));
        now[0] = ZonedDateTime.of(2026, 10, 22, 11, 15, 0, 0, ZoneOffset.UTC).toInstant().toEpochMilli();
        checkTrue("11:15 the Late Show's time comes, but the Morning Show may not be interrupted", "Morning Show".equals(sch.showOnNow(sid).name));
        now[0] = ZonedDateTime.of(2026, 10, 22, 12, 5, 0, 0, ZoneOffset.UTC).toInstant().toEpochMilli();
        checkTrue("after its end the Late Show is on", "Late Show".equals(sch.showOnNow(sid).name));
        RadioStore.Playlist ms = store.playlist(show.id);
        ms.interrupt = "yes";
        store.savePlaylist(ms, now[0]);
        now[0] = ZonedDateTime.of(2026, 10, 22, 10, 30, 0, 0, ZoneOffset.UTC).toInstant().toEpochMilli();
        sch.next(sid, 0);
        now[0] = ZonedDateTime.of(2026, 10, 22, 11, 15, 0, 0, ZoneOffset.UTC).toInstant().toEpochMilli();
        checkTrue("interrupt YES: the Late Show takes over at 11:00", "Late Show".equals(sch.showOnNow(sid).name));

        System.out.println("-- AI and DJs --");
        RadioService radio = new RadioService(store, new AudioTools(dir.resolve("tools"), dir.resolve("tmp")), null, null, null, null,
                () -> now[0], new Random(3));
        now[0] = ZonedDateTime.of(2026, 10, 22, 10, 30, 0, 0, ZoneOffset.UTC).toInstant().toEpochMilli();
        radio.scheduler().next(sid, 0);
        checkTrue("the Morning Show has AI off: Alma is quiet", !radio.aiAllowed(sid, store.station(sid)));
        now[0] = ZonedDateTime.of(2026, 10, 22, 8, 0, 0, 0, ZoneOffset.UTC).toInstant().toEpochMilli();
        radio.scheduler().next(sid, 0);
        checkTrue("the rotation has AI on: Alma talks", radio.aiAllowed(sid, store.station(sid)));
        store.setAiOn(sid, false);
        checkTrue("the station's AI off: quiet everywhere", !radio.aiAllowed(sid, store.station(sid)));
        store.setAiOn(sid, true);
        RadioStore.Dj dj = new RadioStore.Dj();
        dj.station = sid;
        dj.name = "Kali";
        dj.login = "kali";
        dj.password = "djpassword1";
        dj = store.saveDj(dj, 5, now[0]);
        checkTrue("outside the show a DJ may go live", radio.djRefusal(sid, dj.id) == null);
        now[0] = ZonedDateTime.of(2026, 10, 22, 10, 30, 0, 0, ZoneOffset.UTC).toInstant().toEpochMilli();
        String why = radio.djRefusal(sid, dj.id);
        checkTrue("during a show DJs may not take over: refused (" + why + ")", why != null && why.contains("Morning Show"));

        System.out.println("-- The Studio page --");
        UserStore us = new UserStore(dir.resolve("users.txt"), new LiveChannelRegistry(), x -> { });
        us.init(dir.resolve("none.txt"));
        us.createUser("nelson", "password123", UserStore.Role.CHATTER);
        SessionStore sessions = new SessionStore();
        String admin = sessions.create("admin", UserStore.Role.ADMIN);
        String owner = sessions.create("nelson", UserStore.Role.CHATTER);
        RadioApi api = new RadioApi(radio, us, sessions, () -> now[0], dir.resolve("tmp"));
        HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        api.register(http);
        DownloadApi downloads = new DownloadApi(dir.resolve("downloads"), sessions, () -> now[0]);
        downloads.register(http);
        http.start();
        int port = http.getAddress().getPort();
        String[] r = call(port, "POST", "/api/studio/playlist/save", "station=" + sid + "&id=0&name=Every+2+weeks&type=scheduled&start=1200&end=1260"
                + "&when=every%3A2%3Aweek%3A2026-10-22&days=1111111&tracks=" + a.id + "&interrupt=no&djOverride=false&ai=false&studio=true&weight=0", owner);
        checkTrue("the page saves a new playlist with every setting (" + r[1] + ")", r[0].equals("200"));
        r = call(port, "GET", "/api/studio/playlists?station=" + sid, null, owner);
        checkTrue("and lists them back", r[1].contains("\"name\":\"Every 2 weeks\"") && r[1].contains("\"interrupt\":\"no\"")
                && r[1].contains("\"studio\":true") && r[1].contains("every 2 weeks from 2026-10-22") && r[1].contains("\"aiOn\":true"));
        r = call(port, "POST", "/api/studio/station/admin", "id=" + sid + "&aiOn=0", owner);
        checkTrue("an owner cannot switch the station's AI", r[0].equals("403"));
        r = call(port, "POST", "/api/admin/radio/station/admin", "id=" + sid + "&aiOn=0", admin);
        checkTrue("the admin can (Stations list, AI column)", r[0].equals("200") && !store.station(sid).aiOn);
        r = call(port, "GET", "/api/studio/status", null, owner);
        checkTrue("the Studio sees it", r[1].contains("\"aiOn\":false"));

        System.out.println("-- Downloads --");
        byte[] pdf = new byte[5000];
        pdf[0] = '%';
        pdf[1] = 'P';
        r = call(port, "POST", "/api/admin/downloads/upload?app=new&file=guide.pdf&name=DJ+Guide&desc=How+to+go+live+with+BUTT", pdf, owner);
        checkTrue("members cannot add downloads", r[0].equals("403"));
        r = call(port, "POST", "/api/admin/downloads/upload?app=new&file=guide.pdf&name=DJ+Guide&desc=How+to+go+live+with+BUTT", new byte[5000], admin);
        checkTrue("a fake file is refused", r[0].equals("400"));
        r = call(port, "POST", "/api/admin/downloads/upload?app=new&file=guide.pdf&name=DJ+Guide&desc=How+to+go+live+with+BUTT&note=v2", pdf, admin);
        checkTrue("the super-admin adds a download with a description", r[0].equals("200") && r[1].contains("\"app\":\"dj-guide\"")
                && r[1].contains("How to go live with BUTT"));
        r = call(port, "GET", "/download", null, null);
        checkTrue("the page shows it with its description", r[1].contains("DJ Guide") && r[1].contains("How to go live with BUTT")
                && r[1].contains("/download/dj-guide.pdf") && r[1].contains("Guides (PDF)"));
        r = call(port, "GET", "/download/dj-guide.pdf", null, null);
        checkTrue("and it downloads", r[0].equals("200") && r[1].length() == 5000);
        r = call(port, "POST", "/api/admin/downloads/info", "app=vstream&desc=" + "Go+live+in+one+tap", admin);
        checkTrue("the apps' descriptions can be changed", r[1].contains("Go live in one tap"));
        r = call(port, "POST", "/api/admin/downloads/delete", "app=dj-guide", admin);
        checkTrue("an added download can be removed", !r[1].contains("dj-guide") && !Files.exists(dir.resolve("downloads/dj-guide.pdf")));
        http.stop(0);

        System.out.println(failures == 0 ? "ALL CHECKS PASSED" : failures + " FAILED");
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

    private static String[] call(int port, String method, String path, Object body, String token) throws Exception {
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
            c.getOutputStream().write(body instanceof byte[] ? (byte[]) body : body.toString().getBytes(StandardCharsets.UTF_8));
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