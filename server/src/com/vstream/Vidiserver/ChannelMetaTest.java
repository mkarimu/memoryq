package com.vstream.Vidiserver;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;

/** Tests channel titles and viewer counts. No MediaMTX or phone needed. */
public class ChannelMetaTest {

    private static int failures = 0;

    public static void main(String[] args) throws Exception {
        LiveChannelRegistry registry = new LiveChannelRegistry();
        String id1 = registry.registerChannel("preset-1", "");
        String id2 = registry.registerChannel("preset-2", "");
        LiveChannel ch1 = registry.getChannel(id1);
        LiveChannel ch2 = registry.getChannel(id2);
        String tok = ch1.getPublishToken();

        MediaAuthHttpServer auth = new MediaAuthHttpServer(registry, 0);
        auth.start();
        ChannelListHttpServer list = new ChannelListHttpServer(registry, 0);
        list.start();
        int ap = auth.getPort();
        int lp = list.getPort();

        System.out.println();
        System.out.println("-- Titles through the publish query --");
        check("publish with a title", authCall(ap, "publish", "live/" + id1, "token=" + tok + "&title=My%20show"), 200);
        checkTrue("title stored", "My show".equals(ch1.getTitle()));
        check("publish with + as space", authCall(ap, "publish", "live/" + id1, "token=" + tok + "&title=Late+night"), 200);
        checkTrue("plus becomes a space", "Late night".equals(ch1.getTitle()));
        check("publish with no title", authCall(ap, "publish", "live/" + id1, "token=" + tok), 200);
        checkTrue("no title clears the old one", "".equals(ch1.getTitle()));
        StringBuilder longTitle = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            longTitle.append('a');
        }
        check("publish with a 100-character title", authCall(ap, "publish", "live/" + id1, "token=" + tok + "&title=" + longTitle), 200);
        checkTrue("title is cut to 40 characters", ch1.getTitle().length() == 40);
        check("publish with control characters", authCall(ap, "publish", "live/" + id1, "token=" + tok + "&title=a%0Ab%0Dc"), 200);
        checkTrue("control characters are removed", "abc".equals(ch1.getTitle()));
        check("publish to set a title to keep", authCall(ap, "publish", "live/" + id1, "token=" + tok + "&title=Keep"), 200);
        check("wrong token with a new title", authCall(ap, "publish", "live/" + id1, "token=wrong&title=Hacked"), 401);
        checkTrue("a refused publish does not change the title", "Keep".equals(ch1.getTitle()));
        check("unregistered channel with a title", authCall(ap, "publish", "live/CHANNEL-099", "token=" + tok + "&title=X"), 401);
        check("read does not change the title", authCall(ap, "read", "live/" + id1, "title=Read", ""), 200);
        checkTrue("title unchanged after read", "Keep".equals(ch1.getTitle()));

        System.out.println();
        System.out.println("-- Viewer counting --");
        AtomicLong now = new AtomicLong(1000);
        ViewerTracker vt = new ViewerTracker(now::get);
        vt.ping("CHANNEL-001", "aaaaaaaa");
        vt.ping("CHANNEL-001", "bbbbbbbb");
        checkTrue("two viewers counted", vt.count("CHANNEL-001") == 2);
        vt.ping("CHANNEL-001", "aaaaaaaa");
        checkTrue("the same viewer is counted once", vt.count("CHANNEL-001") == 2);
        checkTrue("another channel has none", vt.count("CHANNEL-002") == 0);
        now.addAndGet(11000);
        checkTrue("viewers expire after 10 seconds", vt.count("CHANNEL-001") == 0);
        vt.ping("CHANNEL-001", "aaaaaaaa");
        checkTrue("a returning viewer counts again", vt.count("CHANNEL-001") == 1);

        System.out.println();
        System.out.println("-- /watch and /channels over HTTP --");
        check("watch ping 1", post(lp, "/watch?channel=" + id1 + "&viewer=viewer-0001"), 204);
        check("watch ping 2", post(lp, "/watch?channel=" + id1 + "&viewer=viewer-0002"), 204);
        check("same viewer again", post(lp, "/watch?channel=" + id1 + "&viewer=viewer-0002"), 204);
        check("bad viewer id", post(lp, "/watch?channel=" + id1 + "&viewer=x"), 400);
        check("bad channel id", post(lp, "/watch?channel=foo&viewer=viewer-0003"), 400);
        check("missing parameters", post(lp, "/watch"), 400);
        check("unknown channel", post(lp, "/watch?channel=CHANNEL-099&viewer=viewer-0003"), 404);
        check("GET /watch is not allowed", postOrGet(lp, "/watch", "GET"), 405);
        ch1.setTitle("Friday show");
        ch2.setTitle("say \"hi\"");
        String json = get(lp, "/channels");
        checkTrue("list shows the title", json.contains("\"title\":\"Friday show\""));
        checkTrue("list shows 2 viewers on the first channel", json.contains("\"viewers\":2"));
        checkTrue("list shows 0 viewers on the second channel", json.contains("\"viewers\":0"));
        checkTrue("quotes in a title are escaped", json.contains("say \\\"hi\\\""));
        checkTrue("the list never contains a token", !json.contains(tok) && !json.contains(ch2.getPublishToken()));
        check("GET /nothing", postOrGet(lp, "/nothing", "GET"), 404);
        check("POST /channels", postOrGet(lp, "/channels", "POST"), 405);

        auth.stop();
        list.stop();
        System.out.println();
        System.out.println(failures == 0 ? "ALL CHECKS PASSED" : failures + " CHECK(S) FAILED");
    }

    private static int authCall(int port, String action, String path, String query) throws Exception {
        return authCall(port, action, path, query, "127.0.0.1");
    }

    private static int authCall(int port, String action, String path, String query, String ip) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:" + port + "/auth").openConnection();
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json");
        String body = "{\"user\":\"\",\"password\":\"\",\"token\":\"\",\"ip\":\"" + (ip.isEmpty() ? "127.0.0.1" : ip)
                + "\",\"action\":\"" + action + "\",\"path\":\"" + path
                + "\",\"protocol\":\"rtmp\",\"id\":\"x\",\"query\":\"" + query + "\"}";
        OutputStream os = c.getOutputStream();
        os.write(body.getBytes(StandardCharsets.UTF_8));
        os.close();
        int code = c.getResponseCode();
        c.disconnect();
        return code;
    }

    private static int post(int port, String pathAndQuery) throws Exception {
        return postOrGet(port, pathAndQuery, "POST");
    }

    private static int postOrGet(int port, String pathAndQuery, String method) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:" + port + pathAndQuery).openConnection();
        c.setRequestMethod(method);
        int code = c.getResponseCode();
        c.disconnect();
        return code;
    }

    private static String get(int port, String path) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:" + port + path).openConnection();
        BufferedReader reader = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            sb.append(line);
        }
        reader.close();
        c.disconnect();
        return sb.toString();
    }

    private static void check(String name, int actual, int expected) {
        if (actual == expected) {
            System.out.println("PASS  " + name + " -> " + actual);
        } else {
            failures++;
            System.out.println("FAIL  " + name + " -> got " + actual + ", expected " + expected);
        }
    }

    private static void checkTrue(String name, boolean ok) {
        if (ok) {
            System.out.println("PASS  " + name);
        } else {
            failures++;
            System.out.println("FAIL  " + name);
        }
    }
}