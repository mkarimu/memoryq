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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Studio Master: who may use it, the phone feeds that are live, the relay (picture copied, sound to Opus) on a private
 * path, guests with invite links, who may send or watch the master/ paths, the programme going to a live channel.
 */
public final class StudioMasterTest {

    private static int failures = 0;
    private static int port;

    public static void main(String[] args) {
        try {
            run();
        } catch (Throwable t) {
            t.printStackTrace();
            System.out.println("FAILED: " + t);
            System.exit(1);
        }
    }

    static String[] call(String method, String path, String body, String token) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:" + port + path).openConnection();
        c.setRequestMethod(method);
        c.setRequestProperty("Connection", "close");
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
        byte[] buf = new byte[8192];
        int n;
        while (in != null && (n = in.read(buf)) > 0) {
            b.write(buf, 0, n);
        }
        return new String[] {String.valueOf(code), new String(b.toByteArray(), StandardCharsets.UTF_8)};
    }

    static String jstr(String json, String key) {
        int i = json.indexOf("\"" + key + "\":\"");
        return i < 0 ? "" : json.substring(i + key.length() + 4, json.indexOf('"', i + key.length() + 4));
    }

    static String q(String url, String k) {
        return MediaAuthHttpServer.queryParam(url.substring(url.indexOf('?') + 1), k);
    }

    private static void run() throws Exception {
        Path dir = Files.createTempDirectory("memoryq-master");
        long[] now = {1792900000000L};
        UserStore users = new UserStore(dir.resolve("users.txt"), new LiveChannelRegistry(), x -> { });
        users.init(dir.resolve("none.txt"));
        users.createUser("mc_kali", "password123", UserStore.Role.CHATTER);
        users.createUser("phone1", "password123", UserStore.Role.CHATTER);
        LiveChannelRegistry reg = new LiveChannelRegistry();
        ClaimStore claims = new ClaimStore(dir.resolve("claims.txt"), reg, x -> { }, 48 * 3600000L, 12 * 3600000L, () -> now[0]);
        claims.init(101, 4);
        ClaimStore.Claim phone = claims.claim("phone1", "CHANNEL-101");
        reg.getChannel("CHANNEL-101").setTitle("Wedding in Mwanza");
        Set<String> live = new HashSet<String>();
        live.add("CHANNEL-101");
        SessionStore sessions = new SessionStore();
        String admin = sessions.create("admin", UserStore.Role.ADMIN);
        String kali = sessions.create("mc_kali", UserStore.Role.CHATTER);
        String[] ff = {null};
        StudioMaster sm = new StudioMaster(dir.resolve("studio-master.txt"), sessions, users, claims, reg, live::contains, () -> now[0]).ffmpeg(() -> ff[0]);
        sm.init();
        HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        http.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        sm.register(http);
        http.start();
        port = http.getAddress().getPort();
        try {
            System.out.println("-- Who may use it --");
            checkTrue("signed out: refused", call("GET", "/api/master/state", null, null)[0].equals("401"));
            checkTrue("a member without access: refused", call("GET", "/api/master/state", null, kali)[0].equals("403"));
            checkTrue("only the super-admin gives access", call("POST", "/api/admin/master", "add=mc_kali", kali)[0].equals("403"));
            checkTrue("not to a name nobody has", call("POST", "/api/admin/master", "add=nobody", admin)[0].equals("404"));
            String[] r = call("POST", "/api/admin/master", "add=MC_Kali", admin);
            checkTrue("the super-admin gives mc_kali access", r[0].equals("200") && r[1].contains("\"mc_kali\""));
            r = call("GET", "/api/master/state", null, kali);
            checkTrue("mc_kali sees the phones that are live (with their title)", r[0].equals("200") && r[1].contains("\"channel\":\"CHANNEL-101\"")
                    && r[1].contains("\"holder\":\"phone1\"") && r[1].contains("\"title\":\"Wedding in Mwanza\"") && r[1].contains("\"slot\":5"));
            String show = jstr(r[1], "show");
            StudioMaster again = new StudioMaster(dir.resolve("studio-master.txt"), sessions, users, claims, reg, live::contains, () -> now[0]);
            again.init();
            checkTrue("access is kept across a restart", again.mayUse(sessions.get(kali)));

            System.out.println("-- A phone feed in a slot --");
            checkTrue("no FFmpeg: said clearly", call("POST", "/api/master/relay", "slot=1&channel=CHANNEL-101", kali)[1].contains("FFmpeg"));
            checkTrue("a feed that is not live is refused", call("POST", "/api/master/relay", "slot=1&channel=CHANNEL-102", kali)[0].equals("409"));
            checkTrue("slots 1 to 5 only", call("POST", "/api/master/relay", "slot=6&channel=CHANNEL-101", kali)[0].equals("400"));
            StudioMaster.Show s = null;
            for (int k = 0; k < 1; k++) {
                java.lang.reflect.Field f = StudioMaster.class.getDeclaredField("shows");
                f.setAccessible(true);
                s = (StudioMaster.Show) ((java.util.Map<?, ?>) f.get(sm)).get("mc_kali");
            }
            List<String> cmd = sm.relayCommand(s, 1, "CHANNEL-101");
            String line = String.join(" ", cmd);
            checkTrue("the relay copies the picture and turns the sound into Opus, to a private path with its own key",
                    line.contains("-i rtmp://127.0.0.1:1935/live/CHANNEL-101") && line.contains("-c:v copy") && line.contains("-c:a libopus")
                    && line.contains("rtsp://127.0.0.1:8554/master/" + show + "/s1?token=" + s.pubToken) && line.contains("-rtsp_transport tcp"));
            boolean unix = !System.getProperty("os.name", "").toLowerCase().startsWith("windows") && new java.io.File("/bin/sleep").exists();
            if (unix) {
                ff[0] = "/bin/sleep"; // stands in for FFmpeg here: a process that keeps running
                r = call("POST", "/api/master/relay", "slot=1&channel=CHANNEL-101", kali);
                checkTrue("cued in slot 1: the console reads it from the private path", r[0].equals("200") && r[1].contains("\"feed\":\"CHANNEL-101\"")
                        && r[1].contains("master/" + show + "/s1/whep?token=" + s.readToken));
                now[0] += StudioMaster.IDLE_MS + 1000;
                sm.idleCheck();
                checkTrue("a console that stopped checking in has its feeds stopped", s.relays[1] == null);
                call("POST", "/api/master/ping", "", kali);
            }

            System.out.println("-- Who may send to and watch master/ paths --");
            String p1 = "master/" + show + "/s1";
            checkTrue("the console reads with its key", sm.deny("read", p1, s.readToken, "203.0.113.5") == null);
            checkTrue("a wrong key cannot watch", sm.deny("read", p1, "nope", "203.0.113.5") != null);
            checkTrue("only the server's own relay sends phone feeds", sm.deny("publish", p1, s.pubToken, "127.0.0.1") == null
                    && sm.deny("publish", p1, s.pubToken, "203.0.113.5") != null);
            checkTrue("an unknown show is refused", sm.deny("read", "master/0123456789abcdef/s1", s.readToken, "127.0.0.1") != null);

            System.out.println("-- A guest with a link --");
            r = call("POST", "/api/master/invite", "slot=2&name=" + java.net.URLEncoder.encode("Dr Amina", "UTF-8"), kali);
            String link = jstr(r[1], "link");
            String code = link.substring(link.indexOf("c=") + 2);
            checkTrue("an invite link for slot 2", r[0].equals("200") && link.contains("/master/join?c=") && code.length() == 24);
            r = call("GET", "/api/master/guest?c=" + code, null, null);
            String whip = jstr(r[1], "whipUrl");
            checkTrue("the guest's page knows where to send (no sign-in needed)", r[0].equals("200") && r[1].contains("\"name\":\"Dr Amina\"")
                    && r[1].contains("\"host\":\"mc_kali\"") && whip.contains("master/" + show + "/g2/whip?token="));
            String gtok = q(whip, "token");
            checkTrue("the guest may send to their slot only", sm.deny("publish", "master/" + show + "/g2", gtok, "198.51.100.4") == null
                    && sm.deny("publish", "master/" + show + "/g3", gtok, "198.51.100.4") != null);
            call("POST", "/api/master/uninvite", "slot=2", kali);
            checkTrue("ended: the link and its key stop", sm.deny("publish", "master/" + show + "/g2", gtok, "198.51.100.4") != null
                    && call("GET", "/api/master/guest?c=" + code, null, null)[0].equals("404"));
            r = call("POST", "/api/master/invite", "slot=3&name=Juma", kali);
            String code2 = jstr(r[1], "link").substring(jstr(r[1], "link").indexOf("c=") + 2);
            now[0] += 13 * 3600000L;
            checkTrue("an invite link lasts 12 hours", call("GET", "/api/master/guest?c=" + code2, null, null)[0].equals("410"));

            System.out.println("-- The programme --");
            r = call("POST", "/api/master/live", "title=" + java.net.URLEncoder.encode("Mwanza Live Debate", "UTF-8"), kali);
            String w = jstr(r[1], "whipUrl");
            ClaimStore.Claim mine = claims.claimOf("mc_kali");
            checkTrue("GO LIVE: a live channel for mc_kali (not the phone's), sent with its key and title", r[0].equals("200") && mine != null
                    && !mine.channelId.equals(phone.channelId) && w.contains("live/" + mine.channelId + "/whip?token=" + mine.token) && w.contains("title=Mwanza%20Live%20Debate"));
            r = call("POST", "/api/master/live", "title=again", kali);
            checkTrue("going live again uses the same channel", jstr(r[1], "channel").equals(mine.channelId));
            r = call("POST", "/api/master/end", "", kali);
            checkTrue("End clears the slots", r[0].equals("200") && !r[1].contains("\"guest\""));
            Restreamer rs = new Restreamer(dir.resolve("restream.txt"), sessions, users, claims);
            Restreamer.Target t = new Restreamer.Target();
            t.platform = "youtube";
            t.url = "rtmp://a.rtmp.youtube.com/live2";
            t.key = "abcd-1234";
            String rcmd = String.join(" ", rs.command(mine.channelId, t));
            checkTrue("copies to YouTube & co. turn the programme's Opus sound into AAC (the picture untouched)", rcmd.contains("-c:v copy") && rcmd.contains("-c:a aac"));
        } finally {
            http.stop(0);
        }
        System.out.println(failures == 0 ? "ALL CHECKS PASSED" : failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }

    private static void checkTrue(String name, boolean ok) {
        System.out.println((ok ? "  ok    " : "  FAIL  ") + name);
        if (!ok) {
            failures++;
        }
    }
}