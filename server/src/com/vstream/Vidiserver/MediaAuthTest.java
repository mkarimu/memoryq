package com.vstream.Vidiserver;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.HttpURLConnection;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Stage 6a test. Pretends to be MediaMTX asking for authorization,
 * and checks the parsing of MediaMTX API responses. No MediaMTX needed.
 */
public class MediaAuthTest {

    private static int failures = 0;

    private static class Fake {
        final Socket socket;
        final BufferedReader in;
        final PrintWriter out;
        final String channelId;
        final String token;

        Fake(int port, String broadcasterId) throws Exception {
            socket = new Socket("127.0.0.1", port);
            socket.setSoTimeout(5000);
            in = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            out = new PrintWriter(socket.getOutputStream(), true);
            out.println("BROADCAST " + broadcasterId);
            String reply = in.readLine();
            String[] p = reply.split(" ");
            if (p.length < 2 || !p[0].equals("OK")) {
                throw new IllegalStateException("unexpected reply: " + reply);
            }
            channelId = p[1];
            token = p.length > 2 ? p[2] : "";
            System.out.println(broadcasterId + " received: OK " + channelId + " <token hidden>");
            checkTrue("reply has OK, channel ID and a 48-character hex token",
                    p.length == 3 && token.matches("[0-9a-f]{48}"));
        }

        void close() throws Exception {
            socket.close();
        }
    }

    public static void main(String[] args) throws Exception {
        LiveChannelRegistry registry = new LiveChannelRegistry();
        BroadcastRegistrationServer broadcastServer = new BroadcastRegistrationServer(registry, 0);
        broadcastServer.start();
        MediaAuthHttpServer authServer = new MediaAuthHttpServer(registry, 0);
        authServer.start();
        int ap = authServer.getPort();

        System.out.println();
        System.out.println("-- Before any broadcaster registers --");
        check("publish live/CHANNEL-001, no token, not registered",
                auth(ap, "publish", "live/CHANNEL-001", "", "127.0.0.1"), 401);
        check("publish live/CHANNEL-001, some token, not registered",
                auth(ap, "publish", "live/CHANNEL-001", "token=abc", "127.0.0.1"), 401);

        System.out.println();
        System.out.println("-- Broadcaster A registers --");
        Fake a = new Fake(broadcastServer.getPort(), "user001");
        String tokA = "token=" + a.token;

        check("publish with the right token", auth(ap, "publish", "live/CHANNEL-001", tokA, "127.0.0.1"), 200);
        check("publish with the right token among other params",
                auth(ap, "publish", "live/CHANNEL-001", "user=x&" + tokA, "127.0.0.1"), 200);
        check("publish with no query at all", auth(ap, "publish", "live/CHANNEL-001", "", "127.0.0.1"), 401);
        check("publish with an empty token", auth(ap, "publish", "live/CHANNEL-001", "token=", "127.0.0.1"), 401);
        check("publish with a wrong token", auth(ap, "publish", "live/CHANNEL-001", "token=wrong", "127.0.0.1"), 401);
        check("publish with the token plus extra characters",
                auth(ap, "publish", "live/CHANNEL-001", tokA + "x", "127.0.0.1"), 401);
        check("publish with a differently named parameter",
                auth(ap, "publish", "live/CHANNEL-001", "key=" + a.token, "127.0.0.1"), 401);
        check("publish live/CHANNEL-099 (never registered) with A's token",
                auth(ap, "publish", "live/CHANNEL-099", tokA, "127.0.0.1"), 401);
        check("publish other/CHANNEL-001 (wrong prefix)",
                auth(ap, "publish", "other/CHANNEL-001", tokA, "127.0.0.1"), 401);
        check("publish ../live/CHANNEL-001 (odd path)",
                auth(ap, "publish", "../live/CHANNEL-001", tokA, "127.0.0.1"), 401);
        check("read live/CHANNEL-001 without a token (viewers)",
                auth(ap, "read", "live/CHANNEL-001", "", "127.0.0.1"), 200);
        check("read live/CHANNEL-099", auth(ap, "read", "live/CHANNEL-099", "", "127.0.0.1"), 401);
        check("playback action not allowed",
                auth(ap, "playback", "live/CHANNEL-001", tokA, "127.0.0.1"), 401);

        System.out.println();
        System.out.println("-- MediaMTX API calls --");
        check("api from 127.0.0.1", auth(ap, "api", "", "", "127.0.0.1"), 200);
        check("api from ::1", auth(ap, "api", "", "", "::1"), 200);
        check("api from a remote address", auth(ap, "api", "", "", "8.8.8.8"), 401);

        System.out.println();
        System.out.println("-- Broadcaster B registers --");
        Fake b = new Fake(broadcastServer.getPort(), "user002");
        String tokB = "token=" + b.token;
        checkTrue("A and B have different tokens", !a.token.equals(b.token));
        check("B publishes with B's token", auth(ap, "publish", "live/CHANNEL-002", tokB, "127.0.0.1"), 200);
        check("B's path with A's token", auth(ap, "publish", "live/CHANNEL-002", tokA, "127.0.0.1"), 401);
        check("A's path with B's token", auth(ap, "publish", "live/CHANNEL-001", tokB, "127.0.0.1"), 401);

        System.out.println();
        System.out.println("-- The token is not exposed --");
        String json = ChannelListHttpServer.toJson(registry.getAllChannels());
        checkTrue("channel list JSON lists the channels", json.contains("CHANNEL-001") && json.contains("CHANNEL-002"));
        checkTrue("channel list JSON does not contain any token",
                !json.contains(a.token) && !json.contains(b.token));
        checkTrue("channel toString does not contain the token",
                !registry.getChannel("CHANNEL-001").toString().contains(a.token));

        System.out.println();
        System.out.println("-- Broadcaster A disconnects --");
        a.close();
        long deadline = System.currentTimeMillis() + 3000;
        while (registry.channelExists("CHANNEL-001") && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        check("A's old token after disconnect", auth(ap, "publish", "live/CHANNEL-001", tokA, "127.0.0.1"), 401);
        check("B still allowed", auth(ap, "publish", "live/CHANNEL-002", tokB, "127.0.0.1"), 200);

        System.out.println();
        System.out.println("-- Re-registration gives a new ID and token --");
        Fake c = new Fake(broadcastServer.getPort(), "user001");
        checkTrue("new channel ID is not reused", !c.channelId.equals("CHANNEL-001"));
        checkTrue("new token differs from A's old token", !c.token.equals(a.token));
        check("old token on the new channel", auth(ap, "publish", "live/" + c.channelId, tokA, "127.0.0.1"), 401);
        check("new token on the new channel",
                auth(ap, "publish", "live/" + c.channelId, "token=" + c.token, "127.0.0.1"), 200);

        System.out.println();
        System.out.println("-- Parsing helpers --");
        checkTrue("queryParam finds token among others",
                "abc".equals(MediaAuthHttpServer.queryParam("user=x&token=abc&z=1", "token")));
        checkTrue("queryParam returns null when absent",
                MediaAuthHttpServer.queryParam("user=x", "token") == null);
        checkTrue("queryParam handles an empty query",
                MediaAuthHttpServer.queryParam("", "token") == null);

        System.out.println();
        System.out.println("-- Parsing MediaMTX API responses --");
        String pathsJson = "{\"pageCount\":1,\"itemCount\":3,\"items\":["
                + "{\"name\":\"live/CHANNEL-001\",\"confName\":\"all_others\","
                + "\"source\":{\"type\":\"rtmpConn\",\"id\":\"abc\"},\"ready\":true,"
                + "\"readyTime\":\"2026-10-06T23:09:06Z\",\"tracks\":[\"H264\"],\"readers\":[]},"
                + "{\"name\":\"live/CHANNEL-002\",\"confName\":\"all_others\",\"source\":null,"
                + "\"ready\":false,\"readyTime\":null,\"tracks\":[],\"readers\":[]},"
                + "{\"name\":\"live/CHANNEL-003\",\"source\":{\"type\":\"rtmpConn\",\"id\":\"def\"},"
                + "\"ready\":true}]}";
        Set<String> expectedReady = new HashSet<String>(
                Arrays.asList("live/CHANNEL-001", "live/CHANNEL-003"));
        checkTrue("parseReadyPaths finds only ready paths",
                MediaMtxClient.parseReadyPaths(pathsJson).equals(expectedReady));

        String connsJson = "{\"pageCount\":1,\"itemCount\":3,\"items\":["
                + "{\"id\":\"uuid-1\",\"created\":\"x\",\"remoteAddr\":\"[::1]:62842\","
                + "\"state\":\"publish\",\"path\":\"live/CHANNEL-001\",\"query\":\"\"},"
                + "{\"id\":\"uuid-2\",\"created\":\"x\",\"remoteAddr\":\"[::1]:62850\","
                + "\"state\":\"read\",\"path\":\"live/CHANNEL-001\",\"query\":\"\"},"
                + "{\"id\":\"uuid-3\",\"created\":\"x\",\"remoteAddr\":\"[::1]:62860\","
                + "\"state\":\"publish\",\"path\":\"live/CHANNEL-002\",\"query\":\"\"}]}";
        checkTrue("parsePublisherIds finds only the publisher of that path",
                MediaMtxClient.parsePublisherIds(connsJson, "live/CHANNEL-001")
                        .equals(Arrays.asList("uuid-1")));

        b.close();
        c.close();
        authServer.stop();
        broadcastServer.stop();

        System.out.println();
        System.out.println(failures == 0 ? "ALL CHECKS PASSED" : failures + " CHECK(S) FAILED");
    }

    private static int auth(int port, String action, String path, String query, String ip) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:" + port + "/auth").openConnection();
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json");
        String body = "{\"user\":\"\",\"password\":\"\",\"token\":\"\",\"ip\":\"" + ip + "\",\"action\":\""
                + action + "\",\"path\":\"" + path + "\",\"protocol\":\"rtmp\",\"id\":\"x\",\"query\":\""
                + query + "\"}";
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