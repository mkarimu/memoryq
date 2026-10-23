package com.vstream.Vidiserver;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * Ad breaks and Centova-style playlists: an interval playlist that plays all its songs in order, breaks that wait
 * for shows, requests that wait for a break, timed rotation, shows that play their list once, monthly / yearly /
 * one-off shows, and saving and loading the new settings.
 */
public final class RadioBreakTest {

    private static int failures = 0;

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("memoryq-breaks");
        long[] now = {1768201200000L}; // Mon 2026-01-12 07:00 UTC
        RadioStore store = new RadioStore(dir.resolve("radio.txt"), dir.resolve("music"));
        store.init();
        long sid = store.createStation("Hits", "", "owner", RadioStore.STATUS_ACTIVE, now[0]).id;
        store.setTimezone(sid, "UTC");
        store.setNoRepeat(sid, 0);
        long[] t = new long[20];
        for (int i = 1; i < 20; i++) {
            t[i] = store.addTrack(sid, "s" + i + ".mp3", "mp3", new byte[2000], "Song " + i, "", "", 180000, now[0]).id;
        }
        RadioStore.Playlist music = pl(sid, "Music", RadioStore.Playlist.GENERAL, ids(t, 1, 2, 3, 4, 5));
        store.savePlaylist(music, 0);
        RadioStore.Playlist ads = pl(sid, "Ads", RadioStore.Playlist.INTERVAL, ids(t, 10, 11, 12));
        ads.every = 3;
        ads.style = "all";
        ads.sequential = true;
        long adsId = store.savePlaylist(ads, 0).id;
        RadioScheduler sch = new RadioScheduler(store, () -> now[0], new Random(3));

        System.out.println("-- An ad break: every 3 songs, all the ads in order --");
        List<Long> seq = new ArrayList<Long>();
        for (int i = 0; i < 12; i++) {
            seq.add(sch.next(sid, 0).id);
        }
        int firstAd = seq.indexOf(t[10]);
        checkTrue("3 songs, then the 3 ads back to back, in order", firstAd == 3 && seq.get(4) == t[11] && seq.get(5) == t[12]);
        checkTrue("then music again, and the next break after 3 more songs", isMusic(seq.get(6), t) && isMusic(seq.get(8), t)
                && seq.get(9) == t[10] && seq.get(10) == t[11] && seq.get(11) == t[12]);

        System.out.println("-- Requests wait for the break --");
        RadioScheduler s2 = new RadioScheduler(store, () -> now[0], new Random(4));
        for (int i = 0; i < 3; i++) {
            s2.next(sid, 0);
        }
        checkTrue("after 3 songs the break is due - a request waits", s2.breakDue(sid));
        checkTrue("the first ad was chosen and counts as a break song", s2.next(sid, 0).id == t[10] && s2.lastPickWasBreak(sid));
        checkTrue("still in the break", s2.breakDue(sid));
        s2.next(sid, 0);
        s2.next(sid, 0);
        checkTrue("the break is over: requests can play", !s2.breakDue(sid));
        checkTrue("a music song is not a break song", !isMusic(0, t) && music(s2.next(sid, 0), t) && !s2.lastPickWasBreak(sid));
        s2.countSong(sid);
        s2.countSong(sid);
        checkTrue("requested songs count for the 'every 3 songs'", s2.breakDue(sid));

        System.out.println("-- One song per interval (the old way) --");
        RadioStore.Playlist one = store.playlist(adsId);
        one.style = "";
        store.savePlaylist(one, 0);
        RadioScheduler s3 = new RadioScheduler(store, () -> now[0], new Random(5));
        List<Long> q = new ArrayList<Long>();
        for (int i = 0; i < 8; i++) {
            q.add(s3.next(sid, 0).id);
        }
        checkTrue("one ad after every 3 songs", !music(q.get(3), t) && music(q.get(4), t) && music(q.get(6), t) && !music(q.get(7), t));
        one.style = "all";
        store.savePlaylist(one, 0);

        System.out.println("-- Breaks and shows --");
        RadioStore.Playlist show = pl(sid, "Morning Show", RadioStore.Playlist.SCHEDULED, ids(t, 15, 16, 17));
        show.days = "1111111";
        show.start = 7 * 60;
        show.end = 9 * 60;
        show.sequential = true;
        long showId = store.savePlaylist(show, 0).id;
        RadioStore.Playlist ad2 = store.playlist(adsId);
        ad2.inShows = false;
        store.savePlaylist(ad2, 0);
        RadioScheduler s4 = new RadioScheduler(store, () -> now[0], new Random(6));
        List<Long> sh = new ArrayList<Long>();
        for (int i = 0; i < 9; i++) {
            sh.add(s4.next(sid, 0).id);
        }
        checkTrue("a break set to skip shows does not play in the show", !sh.contains(t[10]) && sh.contains(t[15]));
        ad2.inShows = true;
        store.savePlaylist(ad2, 0);
        RadioScheduler s5 = new RadioScheduler(store, () -> now[0], new Random(6));
        List<Long> sh2 = new ArrayList<Long>();
        for (int i = 0; i < 9; i++) {
            sh2.add(s5.next(sid, 0).id);
        }
        checkTrue("a break allowed in shows plays in the show (Memoryq's own playlists)", sh2.contains(t[10]));

        System.out.println("-- A show that plays its list once --");
        RadioStore.Playlist once = store.playlist(showId);
        once.style = "once";
        store.savePlaylist(once, 0);
        ad2.enabled = false;
        store.savePlaylist(ad2, 0);
        RadioScheduler s6 = new RadioScheduler(store, () -> now[0], new Random(6));
        List<Long> on = new ArrayList<Long>();
        for (int i = 0; i < 6; i++) {
            on.add(s6.next(sid, 0).id);
        }
        checkTrue("the show's 3 songs in order, once", on.subList(0, 3).equals(Arrays.asList(t[15], t[16], t[17])));
        checkTrue("then the rotation is back although the show's hours go on", music(on.get(3), t) && music(on.get(5), t)
                && "".equals(s6.currentShow(sid)));
        now[0] += 86400000L; // the next day: the show runs again
        checkTrue("the next day the show starts again", s6.next(sid, 0).id == t[15]);
        RadioStore.Playlist pick1 = store.playlist(showId);
        pick1.style = "one";
        store.savePlaylist(pick1, 0);
        RadioScheduler s7 = new RadioScheduler(store, () -> now[0], new Random(9));
        long only = s7.next(sid, 0).id;
        checkTrue("'one song': one of the show's songs, then rotation", (only == t[15] || only == t[16] || only == t[17]) && music(s7.next(sid, 0), t));

        System.out.println("-- Shows on dates --");
        RadioStore.Playlist m = store.playlist(showId);
        m.style = "";
        m.when = "month:13";
        store.savePlaylist(m, 0);
        java.time.ZoneId utc = java.time.ZoneOffset.UTC;
        java.time.ZonedDateTime d13 = java.time.ZonedDateTime.of(2026, 1, 13, 8, 0, 0, 0, utc);
        checkTrue("monthly on the 13th", store.playlist(showId).onAt(d13) && !store.playlist(showId).onAt(d13.plusDays(1)));
        m.when = "month:last";
        store.savePlaylist(m, 0);
        checkTrue("last day of the month (also in February)", store.playlist(showId).onAt(java.time.ZonedDateTime.of(2026, 2, 28, 8, 0, 0, 0, utc))
                && !store.playlist(showId).onAt(java.time.ZonedDateTime.of(2026, 2, 27, 8, 0, 0, 0, utc)));
        m.when = "month:31";
        store.savePlaylist(m, 0);
        checkTrue("day 31 plays on the 30th in 30-day months", store.playlist(showId).onAt(java.time.ZonedDateTime.of(2026, 4, 30, 8, 0, 0, 0, utc)));
        m.when = "year:12-25";
        store.savePlaylist(m, 0);
        checkTrue("yearly on 25 December", store.playlist(showId).onAt(java.time.ZonedDateTime.of(2026, 12, 25, 8, 0, 0, 0, utc))
                && !store.playlist(showId).onAt(java.time.ZonedDateTime.of(2026, 12, 24, 8, 0, 0, 0, utc)));
        m.when = "once:2026-03-01";
        m.start = 23 * 60;
        m.end = 60;
        store.savePlaylist(m, 0);
        checkTrue("a one-off that goes past midnight", store.playlist(showId).onAt(java.time.ZonedDateTime.of(2026, 3, 2, 0, 30, 0, 0, utc))
                && !store.playlist(showId).onAt(java.time.ZonedDateTime.of(2026, 3, 3, 0, 30, 0, 0, utc)));
        m.when = "every:sunday";
        checkTrue("an unknown date rule is refused", refused(store, m));
        m.when = "";
        m.start = 7 * 60;
        m.end = 9 * 60;
        m.enabled = false;
        store.savePlaylist(m, 0);

        System.out.println("-- Timed rotation --");
        RadioStore.Playlist calm = pl(sid, "Calm mornings", RadioStore.Playlist.GENERAL, ids(t, 6, 7));
        calm.timed = true;
        calm.start = 6 * 60;
        calm.end = 8 * 60;
        calm.weight = 10;
        long calmId = store.savePlaylist(calm, 0).id;
        now[0] = 1768201200000L; // 07:00
        RadioScheduler s8 = new RadioScheduler(store, () -> now[0], new Random(1));
        int calmNow = 0;
        for (int i = 0; i < 200; i++) {
            long id = s8.next(sid, 0).id;
            calmNow += id == t[6] || id == t[7] ? 1 : 0;
        }
        now[0] += 5 * 3600000L; // 12:00
        int calmNoon = 0;
        for (int i = 0; i < 200; i++) {
            long id = s8.next(sid, 0).id;
            calmNoon += id == t[6] || id == t[7] ? 1 : 0;
        }
        checkTrue("in its hours it plays (" + calmNow + "/200), outside it does not (" + calmNoon + ")", calmNow > 60 && calmNoon == 0);
        RadioStore.Playlist mu = store.playlist(music.id == 0 ? findId(store, sid, "Music") : music.id);
        mu.timed = true;
        mu.start = 0;
        mu.end = 60;
        store.savePlaylist(mu, 0);
        int calmAll = 0;
        for (int i = 0; i < 200; i++) {
            long id = s8.next(sid, 0).id;
            calmAll += id == t[6] || id == t[7] ? 1 : 0;
        }
        checkTrue("when every rotation playlist is timed, the times are ignored (as in Centova)", calmAll > 60);
        RadioStore.Playlist bad = store.playlist(calmId);
        bad.end = bad.start;
        checkTrue("timed rotation needs two different times", refused(store, bad));

        System.out.println("-- Saved and loaded --");
        store.flushIfDirty();
        RadioStore again = new RadioStore(dir.resolve("radio.txt"), dir.resolve("music"));
        again.init();
        RadioStore.Playlist ra = again.playlist(adsId), rc = again.playlist(calmId), rs = again.playlist(showId);
        checkTrue("the break settings are kept", "all".equals(ra.style) && ra.inShows && !ra.enabled);
        checkTrue("timed rotation is kept", rc.timed && rc.start == 360 && rc.end == 480);
        checkTrue("the show style is kept", "one".equals(rs.style) || "".equals(rs.style));
        RadioStore.Playlist bogus = again.playlist(calmId);
        bogus.style = "all"; // not for rotation playlists
        again.savePlaylist(bogus, 0);
        checkTrue("a style that does not fit the type is dropped", "".equals(again.playlist(calmId).style));

        System.out.println();
        if (failures == 0) {
            System.out.println("ALL CHECKS PASSED");
        } else {
            System.out.println(failures + " CHECK(S) FAILED");
            System.exit(1);
        }
        System.exit(0);
    }

    private static long findId(RadioStore store, long sid, String name) {
        for (RadioStore.Playlist p : store.playlists(sid)) {
            if (p.name.equals(name)) {
                return p.id;
            }
        }
        return 0;
    }

    private static boolean isMusic(long id, long[] t) {
        for (int i = 1; i <= 5; i++) {
            if (t[i] == id) {
                return true;
            }
        }
        return false;
    }

    private static boolean music(RadioStore.Track tr, long[] t) {
        return tr != null && (isMusic(tr.id, t) || tr.id == t[6] || tr.id == t[7]);
    }

    private static boolean music(long id, long[] t) {
        return isMusic(id, t) || id == t[6] || id == t[7];
    }

    private static RadioStore.Playlist pl(long sid, String name, String type, List<Long> tracks) {
        RadioStore.Playlist p = new RadioStore.Playlist();
        p.station = sid;
        p.name = name;
        p.type = type;
        p.tracks = tracks;
        return p;
    }

    private static List<Long> ids(long[] t, int... which) {
        List<Long> out = new ArrayList<Long>();
        for (int w : which) {
            out.add(t[w]);
        }
        return out;
    }

    private static boolean refused(RadioStore store, RadioStore.Playlist p) {
        try {
            store.savePlaylist(p, 0);
            return false;
        } catch (IllegalArgumentException e) {
            return true;
        }
    }

    private static void checkTrue(String name, boolean ok) {
        System.out.println((ok ? "  ok    " : "  FAIL  ") + name);
        if (!ok) {
            failures++;
        }
    }
}