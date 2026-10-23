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

/** The team's accounts never get Google ads (so nobody taps our own ads); everyone else does. */
public final class AdFreeTest {

    private static int failures = 0;

    public static void main(String[] args) throws Exception {
        System.out.println("-- Who is ad-free --");
        String list = AdStore.DEFAULT_AD_FREE;
        checkTrue("the team's names", AdStore.adFree(list, "ndere", UserStore.Role.CHATTER) && AdStore.adFree(list, "Dadi", UserStore.Role.CHATTER)
                && AdStore.adFree(list, "jaba", UserStore.Role.BROADCASTER));
        checkTrue("every name with gad in it (Gad's aliases)", AdStore.adFree(list, "gad", UserStore.Role.CHATTER)
                && AdStore.adFree(list, "gadotepa26", UserStore.Role.CHATTER) && AdStore.adFree(list, "GadSmooth", UserStore.Role.CHATTER)
                && AdStore.adFree(list, "bigGad", UserStore.Role.CHATTER));
        checkTrue("admin accounts always", AdStore.adFree("", "boss", UserStore.Role.ADMIN));
        checkTrue("other members get ads", !AdStore.adFree(list, "amina", UserStore.Role.CHATTER) && !AdStore.adFree(list, "jabari", UserStore.Role.CHATTER)
                && !AdStore.adFree(list, "dadi2", UserStore.Role.CHATTER));

        System.out.println("-- Kept, and the slider --");
        Path dir = Files.createTempDirectory("memoryq-adfree");
        Files.write(dir.resolve("ads.txt"), "# old file\nS\t1\t20\t1\t1\tca-app-pub-3940256099942544/6300978111\n".getBytes(StandardCharsets.UTF_8));
        AdStore ads = new AdStore(dir.resolve("ads.txt"), dir.resolve("ads"));
        ads.init();
        checkTrue("an old settings file gets the team's list", AdStore.DEFAULT_AD_FREE.equals(ads.settings().adFree));
        UserStore us = new UserStore(dir.resolve("users.txt"), new LiveChannelRegistry(), c -> { });
        us.init(dir.resolve("none.txt"));
        us.createUser("gadotepa26", "password123", UserStore.Role.CHATTER);
        us.createUser("amina", "password123", UserStore.Role.CHATTER);
        SessionStore sessions = new SessionStore();
        String gad = sessions.create("gadotepa26", UserStore.Role.CHATTER);
        String amina = sessions.create("amina", UserStore.Role.CHATTER);
        String admin = sessions.create("admin", UserStore.Role.ADMIN);
        FeedApi feed = new FeedApi(ads, null, us, sessions, new Limiter(), System::currentTimeMillis, new Random(1));
        HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        feed.register(http);
        http.start();
        int port = http.getAddress().getPort();
        try {
            checkTrue("amina's slider has the Google ad", call(port, "GET", "/api/feed/slider", null, amina).contains("\"type\":\"google\""));
            checkTrue("gadotepa26's slider has none", call(port, "GET", "/api/feed/slider", null, gad).contains("\"ad\":null"));
            call(port, "POST", "/api/admin/ads/settings", "adFree=ndere%2C+*gad*%2C+amina", admin);
            checkTrue("the admin changes the list: amina is ad-free now", call(port, "GET", "/api/feed/slider", null, amina).contains("\"ad\":null"));
            AdStore again = new AdStore(dir.resolve("ads.txt"), dir.resolve("ads"));
            again.init();
            checkTrue("kept across a restart", again.settings().adFree.equals("ndere, *gad*, amina"));
        } finally {
            http.stop(0);
        }
        System.out.println(failures == 0 ? "ALL CHECKS PASSED" : failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }

    private static String call(int port, String method, String path, String body, String token) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:" + port + path).openConnection();
        c.setRequestMethod(method);
        c.setRequestProperty("Connection", "close");
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
        return new String(b.toByteArray(), StandardCharsets.UTF_8);
    }

    private static void checkTrue(String name, boolean ok) {
        System.out.println((ok ? "  ok    " : "  FAIL  ") + name);
        if (!ok) {
            failures++;
        }
    }
}