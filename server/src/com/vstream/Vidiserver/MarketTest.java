package com.vstream.Vidiserver;

import com.sun.net.httpserver.HttpServer;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.imageio.ImageIO;

/** The members' marketplace: sell, approval, browse and search, photos, messages, reports, limits. */
public final class MarketTest {

    private static int failures = 0;

    public static void main(String[] args) {
        System.setProperty("java.awt.headless", "true");
        try {
            run();
        } catch (Throwable t) {
            t.printStackTrace();
            System.out.println("FAILED: " + t);
            System.exit(1);
        }
    }

    private static void run() throws Exception {
        Path dir = Files.createTempDirectory("memoryq-market");
        long[] now = {1792700000000L};
        UserStore us = new UserStore(dir.resolve("users.txt"), new LiveChannelRegistry(), c -> { });
        us.init(dir.resolve("none.txt"));
        us.createUser("amina", "password123", UserStore.Role.CHATTER);
        us.createUser("nelson", "password123", UserStore.Role.CHATTER);
        us.createUser("tester_111111", "password123", UserStore.Role.CHATTER);
        SessionStore sessions = new SessionStore();
        String admin = sessions.create("admin", UserStore.Role.ADMIN);
        String amina = sessions.create("amina", UserStore.Role.CHATTER);
        String nelson = sessions.create("nelson", UserStore.Role.CHATTER);
        String tester = sessions.create("tester_111111", UserStore.Role.CHATTER);
        us.createUser("someone", "password123", UserStore.Role.CHATTER);
        String other = sessions.create("someone", UserStore.Role.CHATTER);
        MarketStore store = new MarketStore(dir.resolve("market.txt"), dir.resolve("photos"));
        store.init();
        NotificationStore notes = new NotificationStore(dir.resolve("notes.txt"));
        notes.init();
        List<String> mails = Collections.synchronizedList(new ArrayList<String>());
        MarketApi api = new MarketApi(store, us, sessions, () -> now[0]).notifications(notes)
                .mail((to, s, t) -> mails.add(to + " | " + s + " | " + t), u -> u.equals("amina") ? "amina@example.com" : null, "boss@memoryq.com", "memoryq.com");
        HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        http.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        api.register(http);
        http.start();
        int port = http.getAddress().getPort();
        try {
            System.out.println("-- Selling --");
            String[] r = call(port, "POST", "/api/market/save", "title=Samsung+A54&desc=Like+new%2C+128+GB%2C+with+charger&price=250&currency=USD&category=Phones+%26+tablets&condition=like+new&location=Nairobi", null);
            checkTrue("signed in only", r[0].equals("401"));
            r = call(port, "POST", "/api/market/save", "title=Phone&desc=A+phone+for+sale&price=1&category=Electronics", tester);
            checkTrue("anonymous testers cannot sell", r[0].equals("403"));
            r = call(port, "POST", "/api/market/save", "title=Samsung+A54&desc=Like+new%2C+128+GB%2C+with+charger&price=1%2C250.50&currency=KES&category=Phones+%26+tablets&condition=like+new&location=Nairobi", amina);
            long id = num(r[1], "id");
            checkTrue("amina lists a phone: waiting for approval (" + r[1].substring(0, Math.min(120, r[1].length())) + ")", r[0].equals("200")
                    && r[1].contains("\"status\":\"pending\"") && r[1].contains("\"priceCents\":125050") && r[1].contains("\"currency\":\"KES\""));
            checkTrue("not in the market yet", !call(port, "GET", "/api/market/list", null, null)[1].contains("Samsung"));
            checkTrue("others cannot open it", call(port, "GET", "/api/market/item?id=" + id, null, nelson)[0].equals("404"));
            checkTrue("amina can", call(port, "GET", "/api/market/item?id=" + id, null, amina)[0].equals("200"));
            Thread.sleep(200);
            checkTrue("the admin gets an e-mail", has(mails, "boss@memoryq.com | Marketplace: 1 listing(s) waiting"));
            checkTrue("bad input refused", call(port, "POST", "/api/market/save", "title=x&desc=short&category=Electronics", amina)[0].equals("400")
                    && call(port, "POST", "/api/market/save", "title=Good+title&desc=A+good+description&category=Nope", amina)[0].equals("400")
                    && call(port, "POST", "/api/market/save", "title=Good+title&desc=A+good+description&price=abc&category=Other", amina)[0].equals("400"));

            System.out.println("-- Photos --");
            r = callBytes(port, "/api/market/photo/add?id=" + id, picture(3000, 2000), amina);
            checkTrue("a photo is added", r[0].equals("200") && r[1].contains("/api/market/photo/"));
            String photoUrl = first(r[1], "\"(/api/market/photo/[0-9a-f]{20}\\.jpg)\"");
            byte[] img = getBytes(port, photoUrl);
            BufferedImage back = ImageIO.read(new ByteArrayInputStream(img));
            checkTrue("made smaller (1600 wide) and saved as a clean JPEG", back != null && back.getWidth() == 1600 && img[0] == (byte) 0xFF && img[1] == (byte) 0xD8);
            checkTrue("not a picture: refused", callBytes(port, "/api/market/photo/add?id=" + id, "hello there, not a photo".getBytes(StandardCharsets.UTF_8), amina)[0].equals("400"));
            checkTrue("not someone else's listing", callBytes(port, "/api/market/photo/add?id=" + id, picture(200, 200), nelson)[0].equals("404"));

            System.out.println("-- Approval --");
            checkTrue("only the admin approves", call(port, "POST", "/api/admin/market/approve", "id=" + id, amina)[0].equals("403"));
            r = call(port, "GET", "/api/admin/market/list?status=pending", null, admin);
            checkTrue("the admin sees it waiting", r[1].contains("\"pending\":1") && r[1].contains("Samsung A54"));
            r = call(port, "POST", "/api/admin/market/approve", "id=" + id, admin);
            checkTrue("approved", r[0].equals("200") && r[1].contains("\"status\":\"active\""));
            checkTrue("amina is told", notes.list("amina", 10).size() == 1 && notes.list("amina", 10).get(0).text.contains("now in the Marketplace"));
            r = call(port, "GET", "/api/market/list", null, null);
            checkTrue("in the market for everyone", r[1].contains("Samsung A54") && r[1].contains("\"total\":1") && !r[1].contains("\"desc\""));
            checkTrue("search finds it", call(port, "GET", "/api/market/list?q=samsung+nairobi", null, null)[1].contains("Samsung")
                    && !call(port, "GET", "/api/market/list?q=bicycle", null, null)[1].contains("Samsung"));
            checkTrue("category and price filters", call(port, "GET", "/api/market/list?cat=Phones+%26+tablets&min=1000&max=2000", null, null)[1].contains("Samsung")
                    && !call(port, "GET", "/api/market/list?cat=Vehicles", null, null)[1].contains("Samsung")
                    && !call(port, "GET", "/api/market/list?max=100", null, null)[1].contains("Samsung"));
            r = call(port, "GET", "/api/market/item?id=" + id, null, null);
            checkTrue("anyone opens it, with the description", r[0].equals("200") && r[1].contains("Like new, 128 GB") && r[1].contains("\"mine\":false"));
            checkTrue("meta counts the category", call(port, "GET", "/api/market/meta", null, null)[1].contains("{\"name\":\"Phones & tablets\",\"count\":1}"));

            System.out.println("-- Changes --");
            r = call(port, "POST", "/api/market/save", "id=" + id + "&title=Samsung+A54&desc=Like+new%2C+128+GB%2C+with+charger&price=1100&currency=KES&category=Phones+%26+tablets&condition=like+new&location=Nairobi", amina);
            checkTrue("a new price: stays in the market", r[1].contains("\"status\":\"active\"") && r[1].contains("\"priceCents\":110000"));
            r = call(port, "POST", "/api/market/save", "id=" + id + "&title=Samsung+A54+5G&desc=Like+new%2C+128+GB%2C+with+charger&price=1100&currency=KES&category=Phones+%26+tablets", amina);
            checkTrue("a new title: waits for approval again", r[1].contains("\"status\":\"pending\"") && !call(port, "GET", "/api/market/list", null, null)[1].contains("Samsung"));
            call(port, "POST", "/api/admin/market/approve", "id=" + id, admin);
            checkTrue("nelson cannot change it", call(port, "POST", "/api/market/save", "id=" + id + "&title=Mine+now&desc=I+took+it+over+haha&category=Other", nelson)[0].equals("404"));

            System.out.println("-- Messages --");
            checkTrue("not to your own listing", call(port, "POST", "/api/market/ask", "id=" + id + "&text=hi", amina)[0].equals("400"));
            r = call(port, "POST", "/api/market/ask", "id=" + id + "&text=Is+it+still+available%3F", nelson);
            checkTrue("nelson asks", r[0].equals("200") && r[1].contains("Is it still available?"));
            Thread.sleep(200);
            checkTrue("amina is told in Memoryq and by e-mail", notes.unread("amina") >= 1 && has(mails, "amina@example.com | Marketplace: nelson about Samsung A54 5G"));
            r = call(port, "GET", "/api/market/threads", null, amina);
            checkTrue("amina sees the conversation, 1 unread", r[1].contains("\"with\":\"nelson\"") && r[1].contains("\"unread\":1") && r[1].contains("\"selling\":true"));
            r = call(port, "GET", "/api/market/thread?id=" + id + "&buyer=nelson", null, amina);
            checkTrue("she opens it: read", r[0].equals("200") && call(port, "GET", "/api/market/threads", null, amina)[1].contains("\"unread\":0"));
            r = call(port, "POST", "/api/market/reply", "id=" + id + "&buyer=nelson&text=Yes%2C+come+see+it+today", amina);
            checkTrue("she replies", r[0].equals("200") && r[1].contains("come see it today"));
            checkTrue("nelson has 1 unread", call(port, "GET", "/api/market/threads", null, nelson)[1].contains("\"unread\":1"));
            checkTrue("others cannot read it", call(port, "GET", "/api/market/thread?id=" + id + "&buyer=nelson", null, tester)[0].equals("403")
                    && call(port, "POST", "/api/market/reply", "id=" + id + "&buyer=nelson&text=hey", other)[0].equals("404")
                    && call(port, "GET", "/api/market/thread?id=" + id + "&buyer=nelson", null, other)[0].equals("404"));

            System.out.println("-- Reports and the admin --");
            r = call(port, "POST", "/api/market/report", "id=" + id + "&reason=Looks+stolen", nelson);
            checkTrue("nelson reports it", r[0].equals("200"));
            r = call(port, "GET", "/api/admin/market/reports", null, admin);
            checkTrue("the admin sees the report", r[1].contains("Looks stolen") && r[1].contains("Samsung"));
            r = call(port, "POST", "/api/admin/market/remove", "id=" + id + "&reason=Reported+as+stolen", admin);
            checkTrue("taken down: gone from the market, report closed", r[1].contains("\"status\":\"removed\"")
                    && !call(port, "GET", "/api/market/list", null, null)[1].contains("Samsung")
                    && call(port, "GET", "/api/admin/market/reports", null, admin)[1].equals("{\"reports\":[]}"));
            checkTrue("amina cannot put it back herself", call(port, "POST", "/api/market/status", "id=" + id + "&status=active", amina)[0].equals("403"));
            call(port, "POST", "/api/admin/market/restore", "id=" + id, admin);

            System.out.println("-- Sold, paused, deleted --");
            r = call(port, "POST", "/api/market/status", "id=" + id + "&status=sold", amina);
            checkTrue("sold: shown as sold, not in the list", r[1].contains("\"status\":\"sold\"") && !call(port, "GET", "/api/market/list", null, null)[1].contains("Samsung")
                    && call(port, "GET", "/api/market/item?id=" + id, null, null)[0].equals("200"));
            store.flushIfDirty();
            MarketStore again = new MarketStore(dir.resolve("market.txt"), dir.resolve("photos"));
            again.init();
            checkTrue("kept across a restart", again.get(id) != null && again.get(id).title.equals("Samsung A54 5G") && again.get(id).photos.size() == 1
                    && again.thread(id, "nelson").size() == 2);
            r = call(port, "POST", "/api/market/status", "id=" + id + "&status=deleted", amina);
            checkTrue("deleted: the photos go too", r[0].equals("200") && !Files.list(dir.resolve("photos")).findAny().isPresent()
                    && call(port, "GET", "/api/market/item?id=" + id, null, null)[0].equals("404"));

            System.out.println("-- Auto-approve and limits --");
            call(port, "POST", "/api/admin/market/settings", "autoApprove=1", admin);
            r = call(port, "POST", "/api/market/save", "title=Guitar&desc=Acoustic+guitar%2C+good+strings&price=80&category=Music+%26+instruments", nelson);
            checkTrue("with auto-approve on: in the market at once", r[1].contains("\"status\":\"active\""));
            us.setDisabled("nelson", true);
            checkTrue("a suspended seller's listings are hidden", !call(port, "GET", "/api/market/list", null, null)[1].contains("Guitar"));
            us.setDisabled("nelson", false);
            int ok = 0;
            for (int i = 0; i < 12; i++) {
                if (call(port, "POST", "/api/market/save", "title=Item+" + i + "&desc=Something+to+sell+" + i + "&category=Other", amina)[0].equals("200")) {
                    ok++;
                }
            }
            checkTrue("10 new listings a day at most (" + ok + ")", ok == 9); // amina already listed one today
        } finally {
            http.stop(0);
        }
        System.out.println(failures == 0 ? "ALL CHECKS PASSED" : failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }

    private static byte[] picture(int w, int h) throws Exception {
        BufferedImage b = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = b.createGraphics();
        g.setColor(Color.ORANGE);
        g.fillRect(0, 0, w, h);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(b, "png", out);
        return out.toByteArray();
    }

    private static long num(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\":(\\d+)").matcher(json);
        return m.find() ? Long.parseLong(m.group(1)) : -1;
    }

    private static String first(String s, String re) {
        Matcher m = Pattern.compile(re).matcher(s);
        return m.find() ? m.group(1) : "";
    }

    private static boolean has(List<String> list, String part) {
        synchronized (list) {
            for (String s : list) {
                if (s.contains(part)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static byte[] getBytes(int port, String path) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:" + port + path).openConnection();
        c.setRequestProperty("Connection", "close");
        return read(c.getInputStream());
    }

    private static byte[] read(InputStream in) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        if (in != null) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) {
                b.write(buf, 0, n);
            }
        }
        return b.toByteArray();
    }

    private static String[] callBytes(int port, String path, byte[] body, String token) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:" + port + path).openConnection();
        c.setRequestMethod("POST");
        c.setRequestProperty("Connection", "close");
        c.setConnectTimeout(10000);
        c.setReadTimeout(30000);
        if (token != null) {
            c.setRequestProperty("Authorization", "Bearer " + token);
        }
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/octet-stream");
        c.getOutputStream().write(body);
        int code = c.getResponseCode();
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        return new String[]{String.valueOf(code), new String(read(in), StandardCharsets.UTF_8)};
    }

    private static String[] call(int port, String method, String path, String body, String token) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:" + port + path).openConnection();
        c.setRequestMethod(method);
        c.setRequestProperty("Connection", "close"); // a fresh connection each time (Windows resets reused ones)
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
        return new String[]{String.valueOf(code), new String(read(in), StandardCharsets.UTF_8)};
    }

    private static void checkTrue(String name, boolean ok) {
        System.out.println((ok ? "  ok    " : "  FAIL  ") + name);
        if (!ok) {
            failures++;
        }
    }
}