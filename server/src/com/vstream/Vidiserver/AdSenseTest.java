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

/** Google AdSense: the code in the head of the public pages (not admin / Studio / DJ Pro / sign-in), ads.txt, the admin settings. */
public final class AdSenseTest {

    private static int failures = 0;

    public static void main(String[] args) {
        try {
            run();
        } catch (Throwable t) {
            t.printStackTrace();
            System.out.println("FAILED: " + t);
            System.exit(1);
        }
    }

    private static void run() throws Exception {
        Path dir = Files.createTempDirectory("memoryq-adsense");
        SessionStore sessions = new SessionStore();
        String admin = sessions.create("admin", UserStore.Role.ADMIN);
        String member = sessions.create("amina", UserStore.Role.CHATTER);
        AdSense a = new AdSense(dir.resolve("adsense.txt"), sessions).init();
        String page = "<!doctype html><html><head><title>x</title></head><body>hi</body></html>";
        String withAds = AdSense.inject(page, "/");
        checkTrue("the code goes into the head of the home page", withAds.contains("<script async src=\"https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js?client=ca-pub-2298775225198657\"")
                && withAds.contains("crossorigin=\"anonymous\"></script>\n</head>") && withAds.indexOf("googlesyndication") < withAds.indexOf("<body>"));
        checkTrue("only once", AdSense.inject(withAds, "/").equals(withAds));
        checkTrue("not in the admin panel, Studio, DJ Pro or sign-in", AdSense.inject(page, "/admin").equals(page) && AdSense.inject(page, "/studio").equals(page)
                && AdSense.inject(page, "/djpro").equals(page) && AdSense.inject(page, "/login").equals(page));
        HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        a.register(http);
        http.start();
        int port = http.getAddress().getPort();
        try {
            String[] r = call(port, "GET", "/ads.txt", null, null);
            checkTrue("ads.txt", r[0].equals("200") && r[1].equals("google.com, pub-2298775225198657, DIRECT, f08c47fec0942fa0\n"));
            checkTrue("members cannot change it", call(port, "POST", "/api/admin/adsense", "on=0", member)[0].equals("403"));
            checkTrue("a wrong publisher id is refused", call(port, "POST", "/api/admin/adsense", "client=ca-pub-12", admin)[0].equals("400"));
            r = call(port, "POST", "/api/admin/adsense", "on=1&off=" + java.net.URLEncoder.encode("/studio,/login,/terms", "UTF-8"), admin);
            checkTrue("the admin picks the pages (admin always stays without ads)", r[0].equals("200") && r[1].contains("{\"route\":\"/terms\",\"ads\":false}")
                    && r[1].contains("{\"route\":\"/djpro\",\"ads\":true}") && r[1].contains("{\"route\":\"/admin\",\"ads\":false}"));
            checkTrue("Terms has no ads now", AdSense.inject(page, "/terms").equals(page));
            call(port, "POST", "/api/admin/adsense", "on=0", admin);
            checkTrue("switched off: no code anywhere", AdSense.inject(page, "/").equals(page));
            AdSense again = new AdSense(dir.resolve("adsense.txt"), sessions).init();
            checkTrue("kept across a restart", AdSense.inject(page, "/").equals(page));
            call(port, "POST", "/api/admin/adsense", "on=1", admin);
        } finally {
            http.stop(0);
        }
        System.out.println(failures == 0 ? "ALL CHECKS PASSED" : failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }

    private static String[] call(int port, String method, String path, String body, String token) throws Exception {
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
        if (in != null) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) {
                b.write(buf, 0, n);
            }
        }
        return new String[]{String.valueOf(code), new String(b.toByteArray(), StandardCharsets.UTF_8)};
    }

    private static void checkTrue(String name, boolean ok) {
        System.out.println((ok ? "  ok    " : "  FAIL  ") + name);
        if (!ok) {
            failures++;
        }
    }
}