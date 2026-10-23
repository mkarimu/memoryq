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

/** Live DJ Pro: who may use it, the music for the decks, going on air with the browser's sound, the camera channel. */
public final class DjProTest {

    private static int failures = 0;

    public static void main(String[] args) throws Exception {
        System.setProperty("vstream.djproSilenceMs", "4000");
        System.setProperty("vstream.djproAssumeVideo", "true"); // no video server in the test: treat a requested channel as live
        Path dir = Files.createTempDirectory("memoryq-djpro");
        UserStore us = new UserStore(dir.resolve("users.txt"), new LiveChannelRegistry(), c -> { });
        us.init(dir.resolve("none.txt"));
        us.createUser("owner1", "password123", UserStore.Role.CHATTER);
        us.createUser("stranger", "password123", UserStore.Role.CHATTER);
        SessionStore sessions = new SessionStore();
        String owner = sessions.create("owner1", UserStore.Role.CHATTER);
        String stranger = sessions.create("stranger", UserStore.Role.CHATTER);
        String boss = sessions.create("admin", UserStore.Role.ADMIN);
        long[] now = {1768212000000L}; // Mon 2026-01-12 10:00 UTC
        RadioStore store = new RadioStore(dir.resolve("radio.txt"), dir.resolve("music"));
        store.init();
        AudioTools tools = new AudioTools(java.nio.file.Paths.get(System.getProperty("vstream.toolsDir", dir.resolve("tools").toString())),
                dir.resolve("tmp"));
        tools.detect();
        RadioService radio = new RadioService(store, tools, null, null, null, null, () -> now[0], new Random(2));
        RadioApi api = new RadioApi(radio, us, sessions, () -> now[0], dir.resolve("tmp"));
        RadioBilling billing = new RadioBilling(dir.resolve("billing.txt"));
        billing.init();
        api.setBilling(billing);
        LiveChannelRegistry reg = new LiveChannelRegistry();
        ClaimStore claims = new ClaimStore(dir.resolve("claims.txt"), reg, c -> { }, 48L * 3600000, 3600000L, () -> now[0]);
        claims.init(101, 3);
        api.setClaims(claims);
        api.djPro().setSettingsFile(dir.resolve("djpro.txt"));
        HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        http.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        api.register(http);
        http.start();
        int port = http.getAddress().getPort();

        RadioStore.Station st = store.createStation("Pro FM", "Mixes", "owner1", RadioStore.STATUS_ACTIVE, now[0]);
        long sid = st.id;
        byte[] song = tools.ready() ? sound(tools, "mp3", 5) : new byte[3000];
        long track = store.addTrack(sid, "Artist - Song.mp3", "mp3", song, "Song", "Artist", "", 5000, now[0]).id;
        RadioStore.Dj d = new RadioStore.Dj();
        d.station = sid;
        d.name = "DJ Remote";
        d.login = "remote";
        d.password = "RemoteLive2026";
        d = store.saveDj(d, 3, now[0]);
        RadioStore.Dj late = new RadioStore.Dj();
        late.station = sid;
        late.name = "Night Owl";
        late.login = "owl";
        late.password = "OwlLive20261";
        late.from = 22;
        late.to = 24;
        late.studio = true;
        store.saveDj(late, 3, now[0]);

        System.out.println("-- Signing in --");
        String[] r = post(port, "/api/djpro/login", "station=" + sid + "&login=remote&password=wrong", null);
        check("a wrong DJ password", r[0], "401");
        r = post(port, "/api/djpro/login", "station=" + sid + "&login=REMOTE&password=RemoteLive2026", null);
        checkTrue("without the station admin's OK a DJ cannot open the live studio", r[0].equals("403") && r[1].contains("live studio"));
        d.studio = true;
        d = store.saveDj(d, 3, now[0]);
        r = post(port, "/api/djpro/login", "station=" + sid + "&login=REMOTE&password=RemoteLive2026", null);
        checkTrue("a remote DJ signs in", r[0].equals("200") && r[1].contains("djp_") && r[1].contains("DJ Remote"));
        String dj = str(r[1], "token");
        r = get(port, "/api/djpro/state?station=" + sid, dj);
        checkTrue("the DJ sees the station", r[0].equals("200") && r[1].contains("\"name\":\"Pro FM\"") && r[1].contains("\"dj\":true")
                && r[1].contains("\"session\":null"));
        d.studio = false;
        d = store.saveDj(d, 3, now[0]);
        check("when the admin takes the access away, the DJ is out at once", get(port, "/api/djpro/state?station=" + sid, dj)[0], "401");
        d.studio = true;
        d = store.saveDj(d, 3, now[0]);
        check("not another station", get(port, "/api/djpro/state?station=" + (sid + 5), dj)[0], "404");
        r = get(port, "/api/djpro/state?station=" + sid, owner);
        checkTrue("the owner too, with their stations", r[0].equals("200") && r[1].contains("\"stations\":[{\"id\":" + sid));
        check("a stranger cannot", get(port, "/api/djpro/state?station=" + sid, stranger)[0], "403");
        check("nobody signed in cannot", get(port, "/api/djpro/state?station=" + sid, null)[0], "401");
        check("a made-up DJ token cannot", get(port, "/api/djpro/state?station=" + sid, "djp_123")[0], "401");

        System.out.println("-- Music for the decks --");
        r = get(port, "/api/djpro/tracks?station=" + sid, dj);
        checkTrue("the library", r[0].equals("200") && r[1].contains("\"title\":\"Song\"") && r[1].contains("\"artist\":\"Artist\""));
        byte[] file = getBytes(port, "/api/djpro/track?station=" + sid + "&id=" + track, dj);
        checkTrue("a song's sound file", file != null && file.length == song.length);
        checkTrue("not for strangers", getBytes(port, "/api/djpro/track?station=" + sid + "&id=" + track, stranger) == null);

        System.out.println("-- Plans --");
        RadioBilling.Plan noPro = new RadioBilling.Plan();
        noPro.name = "Basic";
        noPro.kbps = 128;
        noPro.djPro = false;
        int basic = billing.savePlan(noPro).id;
        store.setPlan(sid, basic);
        r = get(port, "/api/djpro/state?station=" + sid, owner);
        checkTrue("a plan without Live DJ Pro says so", r[0].equals("403") && r[1].contains("not in Pro FM's plan"));
        check("and DJs cannot sign in to it", post(port, "/api/djpro/login", "station=" + sid + "&login=remote&password=RemoteLive2026", null)[0], "403");
        store.setPlan(sid, 0);

        System.out.println("-- On air --");
        if (tools.ready()) {
            r = post(port, "/api/djpro/login", "station=" + sid + "&login=owl&password=OwlLive20261", null);
            String owl = str(r[1], "token");
            r = post(port, "/api/djpro/onair", "station=" + sid, owl);
            checkTrue("a DJ outside their hours cannot go on air", r[0].equals("403") && r[1].contains("Outside your DJ hours"));
            radio.refresh();
            r = post(port, "/api/djpro/onair", "station=" + sid, dj);
            checkTrue("the DJ goes on air", r[0].equals("200") && r[1].contains("\"session\""));
            String session = str(r[1], "session");
            checkTrue("the station knows someone is live", radio.liveBusy(sid) && radio.studioBusy(sid));
            r = post(port, "/api/djpro/onair", "station=" + sid, owner);
            checkTrue("the owner cannot take over while the DJ is live", r[0].equals("409") && r[1].contains("DJ Remote is live"));
            LiveInput.Login door = radio.liveCallbacks().login(sid, "source", store.station(sid).livePass, "BUTT");
            checkTrue("DJ software is turned away meanwhile", door != null && door.refused != null && door.refused.contains("Live DJ Pro"));
            byte[] mix = sound(tools, "webm", 6);
            int chunk = mix.length / 12 + 1;
            for (int i = 0; i < mix.length; i += chunk) {
                byte[] part = java.util.Arrays.copyOfRange(mix, i, Math.min(mix.length, i + chunk));
                r = postBytes(port, "/api/djpro/audio?session=" + session, part, dj);
                if (!r[0].equals("200")) {
                    break;
                }
                Thread.sleep(450);
            }
            check("the mix arrives piece by piece", r[0], "200");
            r = post(port, "/api/djpro/camera", "station=" + sid, dj);
            String vch = str(r[1], "channelId");
            r = get(port, "/api/radio/stations", null);
            checkTrue("the radio list shows who is live and the video channel",
                    r[1].contains("\"liveName\":\"DJ Remote\"") && r[1].contains("\"video\":\"" + vch + "\""));
            String extra = api.channelExtra(vch);
            checkTrue("Live Channels shows the radio station with the video", extra != null && extra.contains("\"name\":\"Pro FM\"")
                    && extra.contains("/radio/" + sid + ".mp3"));
            RadioEngine e = radio.engine(sid);
            checkTrue("the station plays the live mix (AutoDJ waits)", e != null && e.liveOnAir());
            r = get(port, "/api/djpro/state?station=" + sid, dj);
            checkTrue("the DJ sees their session", r[1].contains("\"session\":{\"id\":\"" + session + "\""));
            r = get(port, "/api/djpro/state?station=" + sid, owner);
            checkTrue("the owner sees who is live", r[1].contains("\"busy\":\"DJ Remote\""));
            check("someone else cannot send sound into it", postBytes(port, "/api/djpro/audio?session=" + session, new byte[10], owner)[0], "410");
            now[0] += 120000;
            check("off air", post(port, "/api/djpro/offair", "session=" + session, dj)[0], "200");
            checkTrue("the station is free again", !radio.studioBusy(sid));
            checkTrue("off air: the channel is no longer linked to the radio", api.channelExtra(vch) == null);
            checkTrue("the show is in the history", store.shows(sid).size() == 1 && store.shows(sid).get(0).dj.contains("DJ Remote"));
            check("the old session is gone", postBytes(port, "/api/djpro/audio?session=" + session, new byte[10], dj)[0], "410");

            r = post(port, "/api/djpro/onair", "station=" + sid + "&name=The+Boss", owner);
            checkTrue("the owner goes on air with a name", r[0].equals("200") && r[1].contains("The Boss"));
            Thread.sleep(6000);
            api.djPro().watch();
            checkTrue("no sound for a while (closed browser): the session ends by itself", !radio.studioBusy(sid));
            radio.stopAll();
        } else {
            System.out.println("  (FFmpeg not found - on-air part skipped)");
        }

        System.out.println("-- Settings and Dropbox --");
        r = get(port, "/api/djpro/settings", owner);
        checkTrue("anyone signed in can read whether Dropbox is on", r[0].equals("200") && r[1].contains("\"dropboxKey\":\"\""));
        check("only admins set the Dropbox key", post(port, "/api/djpro/settings", "dropboxKey=abc123def456", owner)[0], "403");
        check("a bad key is refused", post(port, "/api/djpro/settings", "dropboxKey=no+spaces!", boss)[0], "400");
        r = post(port, "/api/djpro/settings", "dropboxKey=abc123def456", boss);
        checkTrue("the admin sets the key", r[0].equals("200") && new String(Files.readAllBytes(dir.resolve("djpro.txt")), "UTF-8").contains("abc123def456"));
        checkTrue("the console gets the key", get(port, "/api/djpro/state?station=" + sid, owner)[1].contains("\"dropboxKey\":\"abc123def456\""));
        DjProApi again = new DjProApi(radio, us, sessions, () -> now[0]);
        again.setSettingsFile(dir.resolve("djpro.txt"));
        check("the key is kept after a restart", again.dropboxKey(), "abc123def456");
        check("the song pass-through only fetches Dropbox links",
                get(port, "/api/djpro/fetch?url=" + java.net.URLEncoder.encode("https://example.com/a.mp3", "UTF-8"), owner)[0], "400");
        check("and not for strangers to the console", get(port, "/api/djpro/fetch?url=x", null)[0], "401");

        System.out.println("-- Camera --");
        r = post(port, "/api/djpro/camera", "station=" + sid, dj);
        checkTrue("the camera gets a Live Channel held by the owner", r[0].equals("200") && r[1].contains("/live/CHANNEL-10")
                && r[1].contains("/whip?token=") && r[1].contains("title=DJ%20Remote%20on%20Pro%20FM") && claims.claimOf("owner1") != null);
        String ch = str(r[1], "channelId");
        checkTrue("the channel is ready for publishing", reg.getChannel(ch) != null && reg.getChannel(ch).getPublishToken() != null);
        r = post(port, "/api/djpro/camera", "station=" + sid + "&title=Friday+Mix", owner);
        checkTrue("the same channel again, with a title", r[1].contains("\"channelId\":\"" + ch + "\"") && r[1].contains("title=Friday%20Mix"));
        check("not for strangers", post(port, "/api/djpro/camera", "station=" + sid, stranger)[0], "403");

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