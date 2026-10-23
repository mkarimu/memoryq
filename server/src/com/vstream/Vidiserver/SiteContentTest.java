package com.vstream.Vidiserver;

import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** The super-admin edits the website's words in place; "view as a user" opens the site as the trial member. */
public final class SiteContentTest {

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
        System.out.println("-- Only safe formatting is kept --");
        checkTrue("bold, italic, links stay", SiteContent.clean("Hi <b>there</b> <i>you</i> <a href=\"https://x.com/a\">link</a>")
                .equals("Hi <b>there</b> <i>you</i> <a href=\"https://x.com/a\" rel=\"noopener\" target=\"_blank\">link</a>"));
        checkTrue("scripts and their content go", SiteContent.clean("a<script>alert(1)</script>b").equals("ab"));
        checkTrue("event handlers and styles go", SiteContent.clean("<p onclick=\"x()\" style=\"color:red\">t</p>").equals("<p>t</p>"));
        checkTrue("javascript: links are dropped", SiteContent.clean("<a href=\"javascript:alert(1)\">x</a>").equals("<a>x</a>"));
        checkTrue("unknown tags go, their words stay", SiteContent.clean("<img src=x onerror=y><font>word</font>").equals("word"));
        checkTrue("inside pages: /links stay", SiteContent.clean("<a href=\"/market\">Market</a>").equals("<a href=\"/market\">Market</a>"));

        System.out.println("-- Finding a block --");
        String html = "<html><body><h1 data-edit=\"home.title\">Your <em>people</em></h1><div data-edit=\"home.box\"><div>in</div> out</div><p>x</p></body></html>";
        int[] r = SiteContent.block(html, "home.title");
        checkTrue("a heading", r != null && html.substring(r[0], r[1]).equals("Your <em>people</em>"));
        r = SiteContent.block(html, "home.box");
        checkTrue("a block with the same tag inside", r != null && html.substring(r[0], r[1]).equals("<div>in</div> out"));

        System.out.println("-- Editing in place --");
        Path dir = Files.createTempDirectory("memoryq-site");
        Files.write(dir.resolve("home.html"), html.getBytes(StandardCharsets.UTF_8));
        Files.write(dir.resolve("admin.html"), "<html>admin</html>".getBytes(StandardCharsets.UTF_8));
        Files.write(dir.resolve("site-editor.js"), "// editor".getBytes(StandardCharsets.UTF_8));
        UserStore us = new UserStore(dir.resolve("users.txt"), new LiveChannelRegistry(), c -> { });
        us.init(dir.resolve("none.txt"));
        us.createUser("amina", "password123", UserStore.Role.CHATTER);
        SessionStore sessions = new SessionStore();
        String admin = sessions.create("admin", UserStore.Role.ADMIN);
        String amina = sessions.create("amina", UserStore.Role.CHATTER);
        SiteContent sc = new SiteContent(dir.resolve("site-content.txt"), dir, sessions, us);
        sc.init();
        AccountApi acc = new AccountApi(us, sessions, new Limiter(), new LiveChannelRegistry(), dir.resolve("admin.html"), x -> { }, x -> false);
        acc.setSiteContent(sc);
        HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        acc.register(http);
        sc.register(http);
        http.createContext("/", ex -> {
            if (!acc.serveHome(ex)) {
                ex.sendResponseHeaders(404, -1);
                ex.close();
            }
        });
        http.start();
        int port = http.getAddress().getPort();
        try {
            checkTrue("members cannot change the site", call(port, "/api/admin/content/save", "key=home.title&html=Hacked", amina)[0].equals("403"));
            checkTrue("nor signed-out visitors", call(port, "/api/admin/content/save", "key=home.title&html=Hacked", null)[0].equals("403"));
            String[] res = call(port, "/api/admin/content/save", "key=home.title&html=" + URLEncoder.encode("Your <b>friends</b><script>x</script>", "UTF-8"), admin);
            checkTrue("the super-admin saves: cleaned and sent back for the page (" + res[1] + ")", res[0].equals("200") && res[1].contains("Your <b>friends</b>") && !res[1].contains("script"));
            String page = get(port, "/");
            checkTrue("visitors see the change at once", page.contains("<h1 data-edit=\"home.title\">Your <b>friends</b></h1>") && page.contains("<p>x</p>"));
            checkTrue("a part that is not on the page is refused", call(port, "/api/admin/content/save", "key=home.nothing&html=x", admin)[0].equals("404"));
            checkTrue("only the website's own pages", call(port, "/api/admin/content/save", "key=admin.x&html=x", admin)[0].equals("400"));
            SiteContent again = new SiteContent(dir.resolve("site-content.txt"), dir, sessions, us);
            again.init();
            checkTrue("kept across a restart", again.count() == 1 && again.apply(html).contains("Your <b>friends</b>"));
            res = call(port, "/api/admin/content/reset", "key=home.title", admin);
            checkTrue("back to the original", res[0].equals("200") && res[1].contains("Your <em>people</em>") && get(port, "/").contains("Your <em>people</em>"));
            checkTrue("the editor script is served", get(port, "/site-editor.js").contains("data-edit"));

            System.out.println("-- View as a user --");
            checkTrue("members cannot", call(port, "/api/admin/view-as", "who=user", amina)[0].equals("403"));
            res = call(port, "/api/admin/view-as", "who=user", admin);
            checkTrue("the super-admin gets a sign-in for the trial member", res[0].equals("200") && res[1].contains("\"username\":\"memoryq_trial\"")
                    && us.info("memoryq_trial") != null && us.info("memoryq_trial").role == UserStore.Role.CHATTER);
            String tok = res[1].replaceAll(".*\"token\":\"([0-9a-f]+)\".*", "$1");
            checkTrue("it is an ordinary member's sign-in", sessions.get(tok) != null && sessions.get(tok).role == UserStore.Role.CHATTER);
            checkTrue("the same trial account next time", call(port, "/api/admin/view-as", "who=user", admin)[0].equals("200") && us.listInfos().stream().filter(i -> i.username.startsWith("memoryq_trial")).count() == 1);
        } finally {
            http.stop(0);
        }
        System.out.println(failures == 0 ? "ALL CHECKS PASSED" : failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }

    private static String get(int port, String path) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:" + port + path).openConnection();
        c.setRequestProperty("Connection", "close");
        c.setRequestProperty("Host", "memoryq.com");
        return new String(read(c.getInputStream()), StandardCharsets.UTF_8);
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

    private static String[] call(int port, String path, String body, String token) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:" + port + path).openConnection();
        c.setRequestMethod("POST");
        c.setRequestProperty("Connection", "close"); // a fresh connection each time (Windows resets reused ones)
        c.setConnectTimeout(10000);
        c.setReadTimeout(30000);
        if (token != null) {
            c.setRequestProperty("Authorization", "Bearer " + token);
        }
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        c.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
        int code = c.getResponseCode();
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        String text = new String(read(in), StandardCharsets.UTF_8).replace("\\u003c", "<").replace("\\u003e", ">"); // JSON writes < and > as escapes
        return new String[]{String.valueOf(code), text};
    }

    private static void checkTrue(String name, boolean ok) {
        System.out.println((ok ? "  ok    " : "  FAIL  ") + name);
        if (!ok) {
            failures++;
        }
    }
}