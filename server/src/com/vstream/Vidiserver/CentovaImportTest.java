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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Moving Centova Cast stations to Memoryq: the export arrives with a code, the admin imports, the stations work. */
public final class CentovaImportTest {

    private static int failures = 0;

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("memoryq-import");
        System.setProperty("vstream.radioImportDir", dir.resolve("import").toString());
        UserStore us = new UserStore(dir.resolve("users.txt"), new LiveChannelRegistry(), c -> { });
        us.init(dir.resolve("none.txt"));
        us.createUser("dcradio", "password123", UserStore.Role.CHATTER);
        us.createUser("someone", "password123", UserStore.Role.CHATTER);
        SessionStore sessions = new SessionStore();
        String admin = sessions.create("admin", UserStore.Role.ADMIN);
        String someone = sessions.create("someone", UserStore.Role.CHATTER);
        long[] now = {1768212000000L};
        RadioStore store = new RadioStore(dir.resolve("radio.txt"), dir.resolve("music"));
        store.init();
        AudioTools tools = new AudioTools(java.nio.file.Paths.get(System.getProperty("vstream.toolsDir", dir.resolve("tools").toString())),
                dir.resolve("tmp"));
        tools.detect();
        RadioService radio = new RadioService(store, tools, null, null, null, null, () -> now[0], new Random(2));
        RadioApi api = new RadioApi(radio, us, sessions, () -> now[0], dir.resolve("tmp"));
        EmailService emails = new EmailService(dir.resolve("emails.txt"), us, null, () -> now[0]);
        emails.init();
        emails.adminSet("someone", "some@example.com");
        api.setEmails(emails);
        HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        http.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        api.register(http);
        http.start();
        int port = http.getAddress().getPort();
        String[] r;

        System.out.println("-- The import code --");
        check("not for station owners", post(port, "/api/admin/radio-import/code", "", someone)[0], "403");
        r = post(port, "/api/admin/radio-import/code", "", admin);
        String code = str(r[1], "code");
        checkTrue("the admin makes a code and gets the command for the Centova server", r[0].equals("200") && code.length() >= 16
                && str(r[1], "command").contains("/radio/centova-export.sh") && str(r[1], "command").contains("mq-export.sh " + code));
        check("a wrong code is refused", get(port, "/api/radio-import/hello?code=nope", null)[0], "403");
        check("the right code works", get(port, "/api/radio-import/hello?code=" + code, null)[0], "200");
        byte[] script = getBytes(port, "/radio/centova-export.sh", null);
        checkTrue("the export script is served", script != null && new String(script, "UTF-8").startsWith("#!/bin/bash")
                && new String(script, "UTF-8").contains("api/radio-import"));

        System.out.println("-- The export arrives --");
        String accounts = "1\tadmin\t\t\t\t\t0\t0\t0\t0\tenabled\t\t\t0\t\n"
                + "2\tmkarimu\tMkarimu Radio\tMkarimu Media Inc\thttp://mkarimu.net/\tmk@example.com\t10000\t512\t0\t0\tenabled\tAfrica/Nairobi\tGospel\t8005\tIceCast\n"
                + "3\tdcradio\tDominion Community Radio\t\t\t\t1000\t256\t0\t0\tdisabled\t\t\t8003\tIceCast"
                + "\t1\t10\t5\t90\t0\t\t\t\t0\t0\t0\t\t0\t44100\t2\n"
                + "4\tsomeone\tSomeone FM\t\t\tSome@Example.com\t100\t128\t0\t0\tsuspended\t\t\t8007\tIceCast"
                + "\t0\t10\t5\t90\t0\tlogo.png\tradio.someone.com\tUTF-8\t0\t0\t0\tshoutcast\t3\t44100\t2\n";
        check("accounts", postText(port, "/api/radio-import/table?code=" + code + "&name=accounts", accounts)[0], "200");
        check("an unknown table is refused", postText(port, "/api/radio-import/table?code=" + code + "&name=users", "x")[0], "400");
        postText(port, "/api/radio-import/table?code=" + code + "&name=sources", "mkarimu\tSourcePass2026\ndcradio\tshort\n");
        postText(port, "/api/radio-import/table?code=" + code + "&name=tracks",
                "11\t2\t/Gospel/Song One.mp3\tSong One\tSinger A\tAlbum\t5\t1000\n"
                + "12\t2\t/Gospel/Song Two.mp3\tSong Two\tSinger B\t\t5\t1000\n"
                + "13\t2\t/IDs/Station ID.mp3\tStation ID\t\t\t5\t1000\n"
                + "21\t3\t/lost.mp3\tLost Song\t\t\t5\t1000\n");
        postText(port, "/api/radio-import/table?code=" + code + "&name=playlists",
                "100\t2\tHeavy Rotation\tgeneral\t\tnever\t\tsongs\t0\t3\tenabled\tsequential\t0\t00:00:00\t00:00:00\n"
                + "101\t2\tAfter-Shows\tinterval\t\tnever\t\tminutes\t180\t0\tenabled\trandom\t0\t00:00:00\t00:00:00\tplayall\tsequential\tdate\t0\t0\n"
                + "106\t2\tStation IDs\tinterval\t\tnever\t\tsongs\t4\t0\tenabled\trandom\t0\t00:00:00\t00:00:00\tonerandom\tsequential\tdate\t0\t0\n"
                + "107\t2\tMorning Calm\tgeneral\t\tnever\t\tsongs\t0\t1\tenabled\trandom\t0\t06:00:00\t09:00:00\tplayall\tsequential\tdate\t0\t0\n"
                + "108\t2\tMonthly Mix\tscheduled\t2024-01-15 10:00:00\tmonthly\t\tsongs\t0\t0\tenabled\trandom\t60\t00:00:00\t00:00:00\tplayall\trandom\tdate\t0\t0\n"
                + "109\t2\tPayday\tscheduled\t2024-01-31 17:00:00\tmonthly\t\tsongs\t0\t0\tenabled\trandom\t60\t00:00:00\t00:00:00\tplayall\tonerandom\tlast\t0\t0\n"
                + "110\t2\tNew Year\tscheduled\t2024-12-31 23:00:00\tyearly\t\tsongs\t0\t0\tenabled\trandom\t120\t00:00:00\t00:00:00\tplayall\tsequential\tdate\t0\t0\n"
                + "102\t2\tGen-Z Revo\tscheduled\t2024-01-01 08:00:00\tdaily\t\tsongs\t0\t0\tenabled\trandom\t0\t00:00:00\t00:00:00\n"
                + "103\t2\tWeekly Top 10\tscheduled\t2023-09-12 18:00:00\tweekly\tfri\tsongs\t0\t0\tdisabled\trandom\t120\t00:00:00\t00:00:00\n"
                + "104\t2\tNightcrawler\tnow\t\tnever\t\tsongs\t0\t0\tdisabled\trandom\t0\t00:00:00\t00:00:00\n"
                + "105\t2\tRequests\trequest\t\tnever\t\tsongs\t0\t0\tenabled\trandom\t0\t00:00:00\t00:00:00\n");
        postText(port, "/api/radio-import/table?code=" + code + "&name=playlist_tracks",
                "100\t11\n100\t12\n101\t13\n101\t12\n102\t11\n102\t12\n103\t12\n104\t11\n106\t13\n107\t11\n108\t12\n109\t11\n110\t12\n");
        postText(port, "/api/radio-import/table?code=" + code + "&name=missing", "21\tdcradio\t/lost.mp3\n");
        // days since 1970: 20000 = 2024-10-04, 20031 = 2024-11-04; day 5 (1970) is a broken date and is left out
        postText(port, "/api/radio-import/table?code=" + code + "&name=history",
                "2\t20000\t40\t600\t2048\t300\n2\t20001\t55\t1200\t4096\t310\n2\t20031\t20\t60\t1024\t50\n2\t5\t1\t1\t1\t1\n4\t20000\t3\t30\t10\t20\n");
        byte[] song = tools.ready() ? sound(tools, "mp3", 5) : new byte[3000];
        for (long t : new long[] {11, 12, 13}) {
            check("song " + t + " arrives", postBytes(port, "/api/radio-import/file?code=" + code + "&track=" + t + "&ext=mp3", song, null)[0], "200");
        }
        check("only sound files", postBytes(port, "/api/radio-import/file?code=" + code + "&track=14&ext=php", song, null)[0], "400");
        check("the script can see what already arrived", get(port, "/api/radio-import/have?code=" + code + "&track=12", null)[1], String.valueOf(song.length));
        check("and what has not", get(port, "/api/radio-import/have?code=" + code + "&track=21", null)[1], "0");
        post(port, "/api/radio-import/done?code=" + code + "&sent=3&skipped=0&missing=1", "", null);

        System.out.println("-- The preview --");
        r = get(port, "/api/admin/radio-import/status", admin);
        checkTrue("two stream accounts (not the Centova admin row)", r[0].equals("200") && r[1].contains("\"username\":\"mkarimu\"")
                && r[1].contains("\"username\":\"dcradio\"") && !r[1].contains("\"username\":\"admin\""));
        checkTrue("songs and files are counted", r[1].contains("\"songs\":3,\"files\":3") && r[1].contains("\"songs\":1,\"files\":0"));
        checkTrue("someone else's username is not taken over", r[1].contains("\"login\":\"dcradioradio\",\"loginExists\":false")
                && r[1].contains("belongs to someone else"));
        checkTrue("the same person (same e-mail) keeps their login", r[1].contains("\"login\":\"someone\",\"loginExists\":true,\"loginNote\":\"\""));
        checkTrue("the Centova status is shown", r[1].contains("\"centovaStatus\":\"suspended\"") && r[1].contains("\"centovaStatus\":\"disabled\""));
        checkTrue("differences are listed", r[1].contains("becomes 320 kbps") && r[1].contains("radio.someone.com") && r[1].contains("logo is not copied")
                && r[1].contains("3 mount points") && r[1].contains("Icecast server type"));
        checkTrue("the playlists are listed", r[1].contains("Heavy Rotation") && r[1].contains("all its songs (a break) every 180 minutes")
                && r[1].contains("one song every 4 songs") && r[1].contains("06:00-09:00 only"));
        check("not for owners", get(port, "/api/admin/radio-import/status", someone)[0], "403");

        System.out.println("-- Import --");
        check("tick something first", post(port, "/api/admin/radio-import/run", "accounts=", admin)[0], "400");
        check("no redirect list before the import", get(port, "/api/radio-import/redirects?code=" + code, null)[1], "");
        r = post(port, "/api/admin/radio-import/run", "accounts=2,3,4&trialDays=7&name3=Dominion+FM&email=false", admin);
        check("the import starts", r[0], "200");
        for (int i = 0; i < 200 && get(port, "/api/admin/radio-import/status", admin)[1].contains("\"running\":true"); i++) {
            Thread.sleep(100);
        }
        r = get(port, "/api/admin/radio-import/status", admin);
        checkTrue("it finished", r[1].contains("\"progress\":\"finished: 3 account(s)\""));
        RadioStore.Station mk = null, dc = null, so = null;
        for (RadioStore.Station s : store.stations()) {
            if (s.name.equals("Mkarimu Radio")) {
                mk = s;
            }
            if (s.name.equals("Dominion FM")) {
                dc = s;
            }
            if (s.name.equals("Someone FM")) {
                so = s;
            }
        }
        checkTrue("both stations exist (one renamed in the preview)", mk != null && dc != null);
        if (mk != null && dc != null) {
            checkTrue("owned by the Centova logins (or a free one)", mk.owner.equals("mkarimu") && dc.owner.equals("dcradioradio")
                    && so != null && so.owner.equals("someone"));
            checkTrue("a suspended Centova account stays suspended", so != null && RadioStore.STATUS_SUSPENDED.equals(so.status));
            checkTrue("a switched-off Centova account stays off", !store.station(dc.id).enabled && store.station(mk.id).enabled);
            String hist = api.importer().historyJson(mk.id);
            checkTrue("the listening history arrives, per month", hist != null && hist.contains("{\"month\":\"2024-11\",\"peak\":20,\"hours\":1.0,\"mb\":1,\"songs\":50,\"days\":1}")
                    && hist.contains("{\"month\":\"2024-10\",\"peak\":55,\"hours\":30.0,\"mb\":6,\"songs\":610,\"days\":2}")
                    && !hist.contains("1970") && hist.indexOf("2024-11") < hist.indexOf("2024-10"));
            checkTrue("a station without history has none", api.importer().historyJson(dc.id) == null);
            String red = get(port, "/api/radio-import/redirects?code=" + code, null)[1];
            checkTrue("the old ports and the new links for the redirect", red.contains("8005\thttps://memoryq.com/radio/" + mk.id + ".mp3\tmkarimu")
                    && red.contains("8003\thttps://memoryq.com/radio/" + dc.id + ".mp3\tdcradio") && red.contains("8007\t"));
            check("the redirect list needs the code", get(port, "/api/radio-import/redirects?code=nope", null)[0], "403");
            checkTrue("a new login with a password that works", us.authenticate("mkarimu", str(r[1], "password")) != null);
            checkTrue("the existing login keeps its password", us.authenticate("dcradio", "password123") != null);
            checkTrue("on a one-week trial", RadioStore.BILL_TRIAL.equals(mk.billing) && mk.paidUntil == now[0] + 7 * 86400000L
                    && RadioStore.STATUS_ACTIVE.equals(mk.status));
            checkTrue("new ports, in Memoryq's range", mk.livePort >= 8010 && dc.livePort >= 8010 && mk.livePort != dc.livePort);
            check("DJs keep their Centova password", mk.livePass, "SourcePass2026");
            checkTrue("a short Centova password gets a new one", !dc.livePass.equals("short") && dc.livePass.length() >= 8);
            checkTrue("contact details, website, time zone", mk.company.equals("Mkarimu Media Inc") && mk.email.equals("mk@example.com")
                    && mk.website.startsWith("http://mkarimu.net") && mk.timezone.equals("Africa/Nairobi") && dc.timezone.equals("America/New_York"));
            checkTrue("Centova's limits (512 kbps becomes 320)", mk.limListeners == 10000 && mk.limKbps == 320 && dc.limListeners == 1000 && dc.limKbps == 256);
            java.util.List<RadioStore.Track> tr = store.tracks(mk.id);
            checkTrue("the songs, with Centova's titles", tr.size() == 3 && tr.stream().anyMatch(t -> t.title.equals("Song One") && t.artist.equals("Singer A")));
            checkTrue("the missing song is reported", store.tracks(dc.id).isEmpty() && r[1].contains("1 song(s) were not sent"));
            java.util.Map<String, RadioStore.Playlist> pl = new java.util.HashMap<String, RadioStore.Playlist>();
            for (RadioStore.Playlist p : store.playlists(mk.id)) {
                pl.put(p.name, p);
            }
            checkTrue("ten playlists (the request list is left out)", pl.size() == 10 && !pl.containsKey("Requests"));
            RadioStore.Playlist ids = pl.get("Station IDs"), calm = pl.get("Morning Calm"), mm = pl.get("Monthly Mix"), pd = pl.get("Payday"), ny = pl.get("New Year");
            checkTrue("Centova 'play all' interval = an ad break: all its songs in order, not during shows",
                    pl.get("After-Shows") != null && "all".equals(pl.get("After-Shows").style) && pl.get("After-Shows").sequential
                    && !pl.get("After-Shows").inShows && pl.get("After-Shows").tracks.size() == 2);
            checkTrue("'one random' interval = one song each time", ids != null && "".equals(ids.style) && !ids.sequential && ids.every == 4);
            checkTrue("rotation with an activation period = timed rotation", calm != null && calm.timed && calm.start == 360 && calm.end == 540);
            checkTrue("monthly show on the 15th, shuffled, played once, 60 minutes", mm != null && "month:15".equals(mm.when) && "once".equals(mm.style)
                    && !mm.sequential && mm.start == 600 && mm.end == 660 && mm.enabled);
            checkTrue("monthly on the last day, one random song", pd != null && "month:last".equals(pd.when) && "one".equals(pd.style));
            checkTrue("yearly on 31 December past midnight", ny != null && "year:12-31".equals(ny.when) && ny.start == 1380 && ny.end == 60 && ny.enabled);
            RadioStore.Playlist hr = pl.get("Heavy Rotation"), as = pl.get("After-Shows"), gz = pl.get("Gen-Z Revo"),
                    wk = pl.get("Weekly Top 10"), nc = pl.get("Nightcrawler");
            checkTrue("rotation: weight and order", hr != null && hr.weight == 3 && hr.sequential && hr.tracks.size() == 2 && hr.enabled);
            checkTrue("interval every 180 minutes", as != null && "minutes".equals(as.unit) && as.every == 180 && as.tracks.size() == 2);
            checkTrue("daily show at 08:00", gz != null && gz.days.equals("1111111") && gz.start == 480 && gz.end > 480);
            checkTrue("weekly show on Friday 18:00 for 120 minutes, still off", wk != null && wk.days.equals("0000100") && wk.start == 1080
                    && wk.end == 1200 && !wk.enabled && "once".equals(wk.style));
            checkTrue("immediate, switched off", nc != null && RadioStore.Playlist.IMMEDIATE.equals(nc.type) && !nc.enabled);
        }
        System.out.println("-- Running the export and the import again: nothing is overwritten without a choice --");
        if (mk != null) {
            String st = get(port, "/api/radio-import/staged?code=" + code, null)[1];
            checkTrue("the script sees what is on Memoryq already", st.contains("mkarimu\t3\t" + mk.id + "\tMkarimu Radio") && st.contains("dcradio\t0\t"));
            r = get(port, "/api/admin/radio-import/status", admin);
            checkTrue("the preview shows the stations that exist", r[1].contains("{\"imported\":{\"station\":" + mk.id + ",\"name\":\"Mkarimu Radio\""));
            // the owner made a playlist of their own meanwhile
            RadioStore.Playlist own = new RadioStore.Playlist();
            own.station = mk.id;
            own.name = "My own list";
            own.tracks = new java.util.ArrayList<Long>(java.util.Arrays.asList(store.tracks(mk.id).get(0).id));
            store.savePlaylist(own, now[0]);
            int stations = store.stations().size();
            r = post(port, "/api/admin/radio-import/run", "accounts=2&trialDays=7&email=false", admin);
            checkTrue("importing it again without a choice is refused", r[0].equals("409") && r[1].contains("already on Memoryq as station " + mk.id));
            r = post(port, "/api/admin/radio-import/run", "accounts=2&mode2=overwrite&email=false", admin);
            checkTrue("overwrite needs the parts ticked", r[0].equals("409") && r[1].contains("tick what to overwrite"));
            waitDone(port, admin, post(port, "/api/admin/radio-import/run", "accounts=2&mode2=skip&email=false", admin));
            checkTrue("skip: nothing new, nothing changed", store.stations().size() == stations && store.tracks(mk.id).size() == 3
                    && store.playlists(mk.id).size() == 11);
            // Centova changed: song 11 has a new file, song 14 is new; only mkarimu is sent again
            byte[] newer = java.util.Arrays.copyOf(song, song.length + 777);
            postText(port, "/api/radio-import/table?code=" + code + "&name=tracks&only=2&users=mkarimu",
                    "11\t2\t/Gospel/Song One.mp3\tSong One\tSinger A\tAlbum\t5\t1000\n"
                    + "12\t2\t/Gospel/Song Two.mp3\tSong Two\tSinger B\t\t5\t1000\n"
                    + "13\t2\t/IDs/Station ID.mp3\tStation ID\t\t\t5\t1000\n"
                    + "14\t2\t/Gospel/Song Three.mp3\tSong Three\tSinger C\t\t5\t1000\n");
            checkTrue("the other accounts' staged rows stay", new String(Files.readAllBytes(dir.resolve("import").resolve("tracks.tsv")), "UTF-8")
                    .contains("21\t3\t/lost.mp3"));
            postBytes(port, "/api/radio-import/file?code=" + code + "&track=11&ext=mp3", newer, null);
            postBytes(port, "/api/radio-import/file?code=" + code + "&track=14&ext=mp3", song, null);
            long one = 0;
            for (RadioStore.Track tk : store.tracks(mk.id)) {
                one = tk.title.equals("Song One") ? tk.id : one;
            }
            waitDone(port, admin, post(port, "/api/admin/radio-import/run", "accounts=2&mode2=overwrite&parts2=songs,playlists&email=false", admin));
            java.util.List<RadioStore.Track> after = store.tracks(mk.id);
            if (after.size() != 4) {
                String res = get(port, "/api/admin/radio-import/status", admin)[1];
                int at = res.indexOf("\"result\"");
                System.out.println("        result: " + (at < 0 ? res : res.substring(at, Math.min(res.length(), at + 900))));
            }
            RadioStore.Track oneAfter = store.track(one);
            checkTrue("overwrite: the new song is added, no doubles", after.size() == 4 && store.stations().size() == stations);
            checkTrue("the changed song keeps its place but gets the new file", oneAfter != null
                    && Files.size(store.pathOf(oneAfter)) == newer.length);
            checkTrue("Centova's playlists replaced, the owner's own kept", store.playlists(mk.id).size() == 11
                    && store.playlists(mk.id).stream().anyMatch(x -> x.name.equals("My own list")));
            RadioStore.Station mk2 = store.station(mk.id);
            checkTrue("login, DJ port and password not changed", mk2.owner.equals("mkarimu") && mk2.livePort == mk.livePort
                    && mk2.livePass.equals("SourcePass2026"));
            r = get(port, "/api/admin/radio-import/status", admin);
            checkTrue("the result says what was overwritten", r[1].contains("\"mode\":\"overwrite\"") && r[1].contains("overwritten: songs, playlists"));
            waitDone(port, admin, post(port, "/api/admin/radio-import/run", "accounts=2&mode2=new&name2=Mkarimu+Radio+Two&email=false", admin));
            checkTrue("new copy: a second station, the first one stays", store.stations().size() == stations + 1 && store.station(mk.id) != null);
            String red = get(port, "/api/radio-import/redirects?code=" + code, null)[1];
            checkTrue("old links go to the newest import, the others stay", red.contains("8003\t") && !red.contains("/radio/" + mk.id + ".mp3"));
        }
        check("the code still works for a second run of the script", get(port, "/api/radio-import/hello?code=" + code, null)[0], "200");
        check("clearing the staged files", post(port, "/api/admin/radio-import/clear", "", admin)[0], "200");
        checkTrue("they are gone", get(port, "/api/radio-import/have?code=" + code + "&track=11", null)[1].equals("0"));

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

    private static void waitDone(int port, String admin, String[] started) throws Exception {
        if (!"200".equals(started[0])) {
            System.out.println("        (not started: " + started[1] + ")");
            return;
        }
        for (int i = 0; i < 200 && get(port, "/api/admin/radio-import/status", admin)[1].contains("\"running\":true"); i++) {
            Thread.sleep(100);
        }
    }

    private static String[] postText(int port, String path, String text) throws Exception {
        return http(port, "POST", path, text.getBytes(StandardCharsets.UTF_8), null);
    }

    /** A test tone as MP3, or as WebM/Opus like the browser sends (MP3 if Opus is not available). */
    private static byte[] sound(AudioTools tools, String kind, int seconds) throws Exception {
        String[] fmt = "webm".equals(kind) ? new String[]{"-c:a", "libopus", "-b:a", "96k", "-f", "webm"}
                : new String[]{"-c:a", "libmp3lame", "-b:a", "128k", "-f", "mp3"};
        java.util.List<String> cmd = new java.util.ArrayList<String>(java.util.Arrays.asList(tools.ffmpegPath(), "-v", "error", "-f", "lavfi", "-i",
                "sine=frequency=330:duration=" + seconds, "-ac", "2", "-ar", "48000"));
        cmd.addAll(java.util.Arrays.asList(fmt));
        cmd.add("pipe:1");
        Process p = new ProcessBuilder(cmd).start();
        byte[] b = readAll(p.getInputStream());
        p.waitFor();
        if (b.length < 1000 && "webm".equals(kind)) {
            return sound(tools, "mp3", seconds);
        }
        return b;
    }

    private static String str(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\":\"([^\"]*)\"").matcher(json);
        return m.find() ? m.group(1) : "";
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

    private static byte[] getBytes(int port, String path, String token) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:" + port + path).openConnection();
        if (token != null) {
            c.setRequestProperty("Authorization", "Bearer " + token);
        }
        if (c.getResponseCode() != 200) {
            return null;
        }
        return readAll(c.getInputStream());
    }

    private static String[] get(int port, String path, String token) throws Exception {
        return http(port, "GET", path, null, token);
    }

    private static String[] post(int port, String path, String body, String token) throws Exception {
        return http(port, "POST", path, body == null ? null : body.getBytes(StandardCharsets.UTF_8), token);
    }

    private static String[] postBytes(int port, String path, byte[] body, String token) throws Exception {
        return http(port, "POST", path, body, token);
    }

    private static String[] http(int port, String method, String path, byte[] body, String token) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:" + port + path).openConnection();
        c.setRequestMethod(method);
        c.setReadTimeout(20000);
        if (token != null) {
            c.setRequestProperty("Authorization", "Bearer " + token);
        }
        if (body != null) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            c.getOutputStream().write(body);
        } else if ("POST".equals(method)) {
            c.setDoOutput(true);
            c.getOutputStream().write(new byte[0]);
        }
        int code = c.getResponseCode();
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
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