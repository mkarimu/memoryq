package com.vstream.Vidiserver;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The Vstream app's server side: a free channel picked for you, "also stream to" destinations, the legal page. */
public final class VstreamServerTest {

    private static int failures = 0;

    public static void main(String[] args) throws Exception {
        System.setProperty("vstream.restreamDelayMs", "50");
        Path dir = Files.createTempDirectory("memoryq-vstream");
        Path page = dir.resolve("admin.html");
        Files.write(page, "<html>admin</html>".getBytes(StandardCharsets.UTF_8));
        Files.write(dir.resolve("vstream-legal.html"), "<html><h2 id=\"terms\">Terms of Use</h2></html>".getBytes(StandardCharsets.UTF_8));
        LiveChannelRegistry reg = new LiveChannelRegistry();
        java.util.List<String> kicks = new java.util.ArrayList<String>();
        UserStore us = new UserStore(dir.resolve("users.txt"), reg, kicks::add);
        us.init(null);
        for (String u : new String[] {"carol", "dave", "erin", "fay"}) {
            us.createUser(u, "password123", UserStore.Role.CHATTER);
        }
        long[] now = {1768212000000L};
        ClaimStore cs = new ClaimStore(dir.resolve("claims.txt"), reg, kicks::add, 48L * 3600000, 12L * 3600000, () -> now[0]);
        cs.init(101, 3);
        SessionStore sessions = new SessionStore();
        AccountApi api = new AccountApi(us, sessions, new Limiter(), reg, page, kicks::add, id -> false);
        api.setClaims(cs);
        Restreamer rs = new Restreamer(dir.resolve("restream.txt"), sessions, us, cs);
        rs.init();
        api.setRestreamer(rs);
        ChannelListHttpServer server = new ChannelListHttpServer(reg, 0);
        server.setAccountApi(api);
        server.start();
        int port = server.getPort();
        String carol = sessions.create("carol", UserStore.Role.CHATTER);
        String dave = sessions.create("dave", UserStore.Role.CHATTER);
        String erin = sessions.create("erin", UserStore.Role.CHATTER);
        String fay = sessions.create("fay", UserStore.Role.CHATTER);
        String guest = sessions.create("guesty", UserStore.Role.GUEST);
        String[] r;

        System.out.println("-- A free channel, picked for you --");
        check("needs a sign-in", http(port, "POST", "/api/claim", form("channel", "auto"), null)[0], "401");
        r = http(port, "POST", "/api/claim", form("channel", "auto"), carol);
        checkTrue("carol gets the first free channel with a key", r[0].equals("200") && r[1].contains("\"channelId\":\"CHANNEL-101\"")
                && value(r[1], "streamKey").startsWith("CHANNEL-101?token="));
        r = http(port, "POST", "/api/claim", form("channel", "auto"), carol);
        checkTrue("asking again gives the same channel", r[1].contains("\"channelId\":\"CHANNEL-101\""));
        r = http(port, "POST", "/api/claim", form("channel", "auto"), dave);
        checkTrue("dave gets the next one", r[1].contains("\"channelId\":\"CHANNEL-102\""));
        http(port, "POST", "/api/claim", form("channel", "auto"), erin);
        r = http(port, "POST", "/api/claim", form("channel", "auto"), fay);
        checkTrue("when all are taken, a clear message", r[0].equals("409") && r[1].contains("all channels are in use"));

        System.out.println("-- Also stream to --");
        check("needs a sign-in", http(port, "GET", "/api/restream", null, null)[0], "401");
        check("not for guest accounts", http(port, "GET", "/api/restream", null, guest)[0], "403");
        r = http(port, "GET", "/api/restream", null, carol);
        checkTrue("six destinations to choose from", r[0].equals("200") && r[1].contains("\"youtube\"") && r[1].contains("\"facebook\"")
                && r[1].contains("\"twitch\"") && r[1].contains("\"x\"") && r[1].contains("\"tiktok\"") && r[1].contains("\"custom\""));
        r = http(port, "POST", "/api/restream/save", form("platform", "youtube", "on", "true", "url", "", "key", ""), carol);
        checkTrue("a key is needed", r[0].equals("400") && r[1].contains("stream key from YouTube"));
        r = http(port, "POST", "/api/restream/save", form("platform", "youtube", "on", "true", "url", "", "key", "abcd-1234-efgh"), carol);
        checkTrue("YouTube saved with its usual server", r[0].equals("200") && r[1].contains("rtmp://a.rtmp.youtube.com/live2"));
        checkTrue("the key is never sent back, only its end", r[1].contains("\"keyHint\":\"...efgh\"") && !r[1].contains("abcd-1234"));
        r = http(port, "POST", "/api/restream/save", form("platform", "youtube", "on", "true", "url", "", "key", ""), carol);
        check("saving again without the key keeps it", r[0], "200");
        check("a private network address is refused",
                http(port, "POST", "/api/restream/save", form("platform", "custom", "url", "rtmp://192.168.1.5/live", "key", "k"), carol)[0], "400");
        check("this server is refused", http(port, "POST", "/api/restream/save", form("platform", "custom", "url", "rtmp://localhost/live", "key", "k"), carol)[0], "400");
        check("only rtmp:// and rtmps://", http(port, "POST", "/api/restream/save", form("platform", "custom", "url", "http://example.com/x", "key", "k"), carol)[0], "400");
        check("an unknown platform", http(port, "POST", "/api/restream/save", form("platform", "myspace", "key", "k"), carol)[0], "400");
        r = http(port, "POST", "/api/restream/save", form("platform", "facebook", "on", "false", "key", "FB-1234567890"), carol);
        checkTrue("Facebook uses rtmps", r[0].equals("200") && r[1].contains("rtmps://live-api-s.facebook.com:443/rtmp/"));
        r = http(port, "POST", "/api/restream/remove", form("platform", "facebook"), carol);
        checkTrue("and can be removed", r[0].equals("200") && !r[1].contains("FB-") && r[1].contains("\"platform\":\"facebook\",\"label\":\"Facebook\",\"defaultUrl\":\"rtmps://live-api-s.facebook.com:443/rtmp/\",\"saved\":false"));
        checkTrue("dave does not see carol's destinations", !http(port, "GET", "/api/restream", null, dave)[1].contains("\"saved\":true"));
        Restreamer.Target t = new Restreamer.Target();
        t.platform = "youtube";
        t.url = "rtmp://a.rtmp.youtube.com/live2";
        t.key = "abcd-1234-efgh";
        List<String> cmd = rs.command("CHANNEL-101", t);
        checkTrue("FFmpeg copies the channel untouched to the platform", cmd.contains("copy") && cmd.contains("rtmp://127.0.0.1:1935/live/CHANNEL-101")
                && cmd.get(cmd.size() - 1).equals("rtmp://a.rtmp.youtube.com/live2/abcd-1234-efgh"));
        check("a key in an error message is hidden", Restreamer.mask("error at rtmp://x/abcd-1234-efgh", "abcd-1234-efgh"), "error at rtmp://x/****");
        rs.onPublish("CHANNEL-101");
        Thread.sleep(400);
        checkTrue("going live starts the copies (here: no FFmpeg, said plainly)", http(port, "GET", "/api/restream", null, carol)[1]
                .contains("\"state\":\"failed: FFmpeg is not installed on the server\""));
        Restreamer again = new Restreamer(dir.resolve("restream.txt"), sessions, us, cs);
        again.init();
        checkTrue("destinations are kept after a restart", again.json("carol").contains("\"keyHint\":\"...efgh\""));
        http(port, "POST", "/api/restream/save", form("platform", "twitch", "on", "true", "key", "live_123456789"), dave);
        us.deleteAccount("dave");
        Restreamer later = new Restreamer(dir.resolve("restream.txt"), sessions, us, cs);
        later.init();
        checkTrue("a deleted account's keys are forgotten", !new String(Files.readAllBytes(dir.resolve("restream.txt")), StandardCharsets.UTF_8).contains("live_123")
                && later.json("carol").contains("...efgh"));

        System.out.println("-- The legal page --");
        r = http(port, "GET", "/vstream-legal", null, null);
        checkTrue("served at /vstream-legal", r[0].equals("200") && r[1].contains("Terms of Use"));

        server.stop();
        System.out.println();
        if (failures == 0) {
            System.out.println("ALL CHECKS PASSED");
        } else {
            System.out.println(failures + " CHECK(S) FAILED");
            System.exit(1);
        }
        System.exit(0);
    }

    private static String form(String... kv) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            sb.append(i > 0 ? "&" : "").append(URLEncoder.encode(kv[i], "UTF-8")).append('=').append(URLEncoder.encode(kv[i + 1], "UTF-8"));
        }
        return sb.toString();
    }

    private static String value(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\":\"([^\"]*)\"").matcher(json);
        return m.find() ? m.group(1) : "";
    }

    private static String[] http(int port, String method, String path, String body, String token) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:" + port + path).openConnection();
        c.setRequestMethod(method);
        c.setReadTimeout(10000);
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
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                b.write(buf, 0, n);
            }
        }
        return new String[] {String.valueOf(code), new String(b.toByteArray(), StandardCharsets.UTF_8)};
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