package com.vstream.Vidiserver;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Memoryq TV: live channels ordered by prominence, re-ordered as viewers change, pictures, and the /tv page. */
public final class TvApiTest {

    private static int failures = 0;

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("memoryq-tv");
        Path page = dir.resolve("admin.html");
        Files.write(page, "<html>admin</html>".getBytes(StandardCharsets.UTF_8));
        Files.write(dir.resolve("tv.html"), "<html><title>Memoryq TV</title></html>".getBytes(StandardCharsets.UTF_8));
        long[] now = {1768212000000L};
        List<LiveChannel> live = new ArrayList<LiveChannel>();
        LiveChannel a = new LiveChannel("CHANNEL-101", "ana", "rtmp://x/live/CHANNEL-101");
        a.setTitle("Morning talk");
        LiveChannel b = new LiveChannel("CHANNEL-102", "bob", "rtmp://x/live/CHANNEL-102");
        b.setTitle("Street \"food\" tour");
        live.add(a);
        live.add(b);
        Map<String, Integer> viewers = new HashMap<String, Integer>();
        viewers.put("CHANNEL-101", 2);
        viewers.put("CHANNEL-102", 6);
        TvApi tv = new TvApi(() -> live, id -> viewers.getOrDefault(id, 0),
                id -> id.equals("CHANNEL-102") ? ",\"radio\":{\"id\":4,\"name\":\"Nelson FM\"}" : null, () -> now[0]);

        System.out.println("-- Ordering --");
        tv.sample();
        String j = tv.json();
        checkTrue("the channel with more viewers comes first", j.indexOf("CHANNEL-102") < j.indexOf("CHANNEL-101"));
        checkTrue("titles are made safe for JSON", j.contains("Street \\\"food\\\" tour"));
        checkTrue("radio DJ details are included", j.contains("\"radio\":{\"id\":4"));
        checkTrue("no rating yet", j.contains("\"rating\":null"));
        checkTrue("no picture yet", j.contains("\"thumb\":null"));
        now[0] += 6 * 60000L;
        tv.sample();
        viewers.put("CHANNEL-101", 12);
        now[0] += 10000;
        tv.sample();
        j = tv.json();
        checkTrue("when viewers change, the order changes", j.indexOf("CHANNEL-101") < j.indexOf("CHANNEL-102"));
        checkTrue("growth over 5 minutes is shown", j.contains("\"rising\":10"));
        checkTrue("the most viewers so far is kept", j.contains("\"peak\":12"));
        checkTrue("score: watching now counts most", TvApi.score(10, 10, 0, 3600000, null) > TvApi.score(2, 30, 0, 3600000, null));
        checkTrue("score: new streams get a boost", TvApi.score(0, 0, 0, 60000, null) > TvApi.score(0, 0, 0, 30 * 60000, null));
        checkTrue("score: ratings will count", TvApi.score(1, 1, 0, 0, 5.0) > TvApi.score(1, 1, 0, 0, null));
        List<String> cmd = TvApi.grabCommand("ffmpeg", "CHANNEL-101");
        checkTrue("pictures are taken from the channel on this server", cmd.contains("rtmp://127.0.0.1:1935/live/CHANNEL-101")
                && cmd.contains("1") && cmd.get(cmd.size() - 1).equals("pipe:1"));

        System.out.println("-- Over HTTP --");
        LiveChannelRegistry reg = new LiveChannelRegistry();
        UserStore us = new UserStore(dir.resolve("users.txt"), reg, k -> { });
        us.init(null);
        AccountApi api = new AccountApi(us, new SessionStore(), new Limiter(), reg, page, k -> { }, id -> false);
        api.setTv(tv);
        ChannelListHttpServer server = new ChannelListHttpServer(reg, 0);
        server.setAccountApi(api);
        server.start();
        int port = server.getPort();
        String[] r = http(port, "/api/tv");
        checkTrue("GET /api/tv lists the channels, best first", r[0].equals("200") && r[1].indexOf("CHANNEL-101") < r[1].indexOf("CHANNEL-102"));
        check("no picture: 404", http(port, "/api/tv/thumb/CHANNEL-101")[0], "404");
        byte[] jpg = new byte[1200];
        jpg[0] = (byte) 0xFF;
        jpg[1] = (byte) 0xD8;
        tv.setThumb("CHANNEL-101", jpg, now[0]);
        r = http(port, "/api/tv/thumb/CHANNEL-101");
        checkTrue("the picture is served as a JPEG", r[0].equals("200") && r[2].equals("image/jpeg") && r[1].length() > 0);
        checkTrue("and listed with the channel", http(port, "/api/tv")[1].contains("\"thumb\":\"/api/tv/thumb/CHANNEL-101?t="));
        check("odd channel names are refused", http(port, "/api/tv/thumb/..%2Fusers.txt")[0], "404");
        r = http(port, "/tv");
        checkTrue("the page is at /tv", r[0].equals("200") && r[1].contains("Memoryq TV"));
        live.remove(b);
        tv.sample();
        checkTrue("a channel that ends leaves the list", !http(port, "/api/tv")[1].contains("CHANNEL-102"));
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

    private static String[] http(int port, String path) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:" + port + path).openConnection();
        c.setReadTimeout(10000);
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
        String type = c.getContentType();
        return new String[] {String.valueOf(code), new String(b.toByteArray(), StandardCharsets.ISO_8859_1), type == null ? "" : type};
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