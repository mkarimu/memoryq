package com.vstream.Vidiserver;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Sends auth requests the way MediaMTX (Go) writes them: with & as \u0026. */
public class AuthEscapeTest {

    private static int failures = 0;

    public static void main(String[] args) throws Exception {
        LiveChannelRegistry registry = new LiveChannelRegistry();
        String id = registry.registerChannel("preset-1", "");
        LiveChannel ch = registry.getChannel(id);
        String tok = ch.getPublishToken();
        MediaAuthHttpServer auth = new MediaAuthHttpServer(registry, 0);
        auth.start();
        int ap = auth.getPort();

        System.out.println();
        System.out.println("-- JSON escapes --");
        checkTrue("\\u0026 becomes &",
                "token=abc&title=Hi".equals(MediaAuthHttpServer.field("{\"query\":\"token=abc\\u0026title=Hi\"}", "query")));
        checkTrue("\\/ and \\\\ are decoded",
                "a/b\\c".equals(MediaAuthHttpServer.field("{\"path\":\"a\\/b\\\\c\"}", "path")));
        checkTrue("plain text is unchanged",
                "live/CHANNEL-001".equals(MediaAuthHttpServer.field("{\"path\":\"live/CHANNEL-001\"}", "path")));
        checkTrue("a broken \\u escape does not crash",
                MediaAuthHttpServer.field("{\"query\":\"x\\u00\"}", "query") != null);

        System.out.println();
        System.out.println("-- The query as Go writes it --");
        check("token and title, & escaped as \\u0026",
                call(ap, "token=" + tok + "\\u0026title=Friday+show"), 200);
        checkTrue("title arrives", "Friday show".equals(ch.getTitle()));
        check("title first, then the token",
                call(ap, "title=Late+night\\u0026token=" + tok), 200);
        checkTrue("title arrives when it comes first", "Late night".equals(ch.getTitle()));
        check("token only, no escape needed", call(ap, "token=" + tok), 200);
        checkTrue("title cleared", "".equals(ch.getTitle()));
        check("wrong token with an escaped title",
                call(ap, "token=wrong\\u0026title=Hacked"), 401);
        checkTrue("a refused publish leaves the title alone", "".equals(ch.getTitle()));

        auth.stop();
        System.out.println();
        System.out.println(failures == 0 ? "ALL CHECKS PASSED" : failures + " CHECK(S) FAILED");
    }

    private static int call(int port, String escapedQuery) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:" + port + "/auth").openConnection();
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json");
        String body = "{\"user\":\"\",\"password\":\"\",\"token\":\"\",\"ip\":\"127.0.0.1\","
                + "\"action\":\"publish\",\"path\":\"live/CHANNEL-001\",\"protocol\":\"rtmp\","
                + "\"id\":\"x\",\"query\":\"" + escapedQuery + "\"}";
        OutputStream os = c.getOutputStream();
        os.write(body.getBytes(StandardCharsets.UTF_8));
        os.close();
        int code = c.getResponseCode();
        c.disconnect();
        return code;
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