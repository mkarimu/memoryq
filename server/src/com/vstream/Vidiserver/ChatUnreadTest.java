package com.vstream.Vidiserver;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;

/** Tests the unread counts used by the app's chat badges. */
public class ChatUnreadTest {

    private static int failures = 0;

    public static void main(String[] args) throws Exception {
        AtomicLong t = new AtomicLong(1000000000000L);
        Path dir = Files.createTempDirectory("vstream-unread");
        LiveChannelRegistry reg = new LiveChannelRegistry();
        UserStore us = new UserStore(dir.resolve("users.txt"), reg, id -> { });
        us.init(null);
        us.createUser("alice", "password123", UserStore.Role.CHATTER);
        us.createUser("bob", "password123", UserStore.Role.CHATTER);
        ChatStore cs = new ChatStore(dir.resolve("chat.txt"), t::get);
        cs.init();
        SessionStore ss = new SessionStore();
        String aliceTok = ss.create("alice", UserStore.Role.CHATTER);
        String bobTok = ss.create("bob", UserStore.Role.CHATTER);
        ChannelListHttpServer server = new ChannelListHttpServer(reg, 0);
        server.setChatApi(new ChatApi(cs, us, ss, reg, id -> false));
        server.start();
        int port = server.getPort();

        cs.post("g-general", "alice", "one", 0);
        t.addAndGet(4000);
        long a2 = cs.post("g-general", "alice", "two", 0).id;
        t.addAndGet(4000);
        long b1 = cs.post("g-general", "bob", "three", 0).id;
        t.addAndGet(4000);
        long a3 = cs.post("g-general", "alice", "four", 0).id;
        t.addAndGet(4000);

        System.out.println();
        System.out.println("-- Unread counts --");
        String r = get(port, "g-general:0", null);
        checkTrue("without signing in, every message counts", r.contains("\"g-general\":4"));
        r = get(port, "g-general:0", bobTok);
        checkTrue("bob's own message does not count", r.contains("\"g-general\":3"));
        r = get(port, "g-general:" + a2, aliceTok);
        checkTrue("alice sees only bob's newer message", r.contains("\"g-general\":1"));
        r = get(port, "g-general:" + a3, aliceTok);
        checkTrue("nothing after the newest message", r.contains("\"g-general\":0"));
        r = get(port, "g-general:0,CHANNEL-001:0", null);
        checkTrue("two rooms at once, and a channel room with no messages is 0",
                r.contains("\"g-general\":4") && r.contains("\"CHANNEL-001\":0"));
        r = get(port, "bad:0,g-general:0", null);
        checkTrue("a badly written room is skipped", !r.contains("\"bad\"") && r.contains("\"g-general\":4"));
        r = get(port, "g-nothere:0", null);
        checkTrue("a room that does not exist is left out (it looks the same as a private group)", !r.contains("g-nothere"));
        String none = rawGet(port, "/api/chat/unread");
        checkTrue("no rooms asked for gives an empty answer", none.contains("\"unread\":{}"));
        cs.delete("g-general", a2, "alice", false);
        r = get(port, "g-general:0", bobTok);
        checkTrue("a deleted message stops counting", r.contains("\"g-general\":2"));

        for (int i = 0; i < 105; i++) {
            t.addAndGet(4000);
            cs.post("g-general", "carol", "message " + i, 0);
        }
        r = get(port, "g-general:0", aliceTok);
        checkTrue("a count is capped at 99", r.contains("\"g-general\":99"));

        server.stop();
        System.out.println();
        System.out.println(failures == 0 ? "ALL CHECKS PASSED" : failures + " CHECK(S) FAILED");
    }

    private static String get(int port, String rooms, String bearer) throws Exception {
        return rawGet(port, "/api/chat/unread?rooms=" + URLEncoder.encode(rooms, "UTF-8"), bearer);
    }

    private static String rawGet(int port, String path) throws Exception {
        return rawGet(port, path, null);
    }

    private static String rawGet(int port, String path, String bearer) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:" + port + path).openConnection();
        if (bearer != null) {
            c.setRequestProperty("Authorization", "Bearer " + bearer);
        }
        int code = c.getResponseCode();
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        StringBuilder sb = new StringBuilder();
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        String line;
        while ((line = reader.readLine()) != null) {
            sb.append(line);
        }
        reader.close();
        c.disconnect();
        return sb.toString();
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