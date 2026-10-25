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

/** The SuperAgent (SERVERASSETS: change log, live status, warnings), Alma the assistant, and the download page. */
public final class AgentTest {

    private static int failures = 0;
    private static int port;

    public static void main(String[] args) {
        try {
            run();
        } catch (Throwable t) {
            t.printStackTrace();
            failures++;
        }
        System.out.println();
        if (failures == 0) {
            System.out.println("ALL CHECKS PASSED");
            System.exit(0);
        }
        System.out.println(failures + " CHECK(S) FAILED");
        System.exit(1);
    }

    private static void run() throws Exception {
        Path app = Files.createTempDirectory("memoryq-agent");
        Path code = app.resolve("src/com/vstream/Vidiserver");
        Files.createDirectories(code);
        Files.write(code.resolve("RadioThing.java"), ("package x;\n/**\n * Plays the radio for everyone. More details here.\n */\npublic final class RadioThing {}\n").getBytes(StandardCharsets.UTF_8));
        Files.write(app.resolve("tv.html"), "<html>tv</html>".getBytes(StandardCharsets.UTF_8));
        Files.write(app.resolve("admin.html"), "<html>admin</html>".getBytes(StandardCharsets.UTF_8));
        long[] now = {1768212000000L};
        SessionStore sessions = new SessionStore();
        Path file = app.resolve("SERVERASSETS.md");
        SuperAgent agent = new SuperAgent(app, file, app.resolve("snap.txt"), sessions, () -> now[0]);
        int[] live = {2};
        String[] warn = {null};
        agent.fact("Accounts", () -> "12 (1 broadcaster)").counter("Live channels", () -> live[0]).check(() -> warn[0]);

        System.out.println("-- SERVERASSETS --");
        String e = agent.checkChanges();
        checkTrue("the first check starts the log", e != null && e.contains("started keeping this log") && e.contains("Watching 3"));
        agent.writeStatus();
        String text = read(file);
        checkTrue("the file starts with the people-written part (assets, NEXT...)", text.startsWith("# Memoryq - SERVERASSETS") && text.contains("XXX NEXT XXX"));
        checkTrue("the live status lists facts and counters", text.contains("Accounts: 12 (1 broadcaster)") && text.contains("Live channels: 2 now, 2 at most today"));
        live[0] = 5;
        agent.writeStatus();
        live[0] = 1;
        agent.writeStatus();
        checkTrue("today's peak is kept", read(file).contains("Live channels: 1 now, 5 at most today"));
        checkTrue("no change, no log line", agent.checkChanges() == null);
        Files.write(code.resolve("RadioThing.java"), ("package x;\n/**\n * Plays the radio for everyone, now with jingles. More.\n */\npublic final class RadioThing { int x; }\n").getBytes(StandardCharsets.UTF_8));
        Files.write(code.resolve("VideoThing.java"), ("package x;\n/** Videos for everyone: uploads and links. */\npublic final class VideoThing {}\n").getBytes(StandardCharsets.UTF_8));
        Files.write(code.resolve("VideoThingTest.java"), "class VideoThingTest {}".getBytes(StandardCharsets.UTF_8));
        Files.delete(app.resolve("admin.html"));
        now[0] += 3600000L;
        e = agent.checkChanges();
        checkTrue("a deploy is written in plain words", e != null && e.contains("Deploy: 2 new, 1 changed, 1 removed")
                && e.contains("Changed: RadioThing - Plays the radio for everyone, now with jingles.")
                && e.contains("New: VideoThing - Videos for everyone") && e.contains("1 automatic test") && e.contains("Removed: admin"));
        warn[0] = "Disk is nearly full";
        agent.writeStatus();
        agent.writeStatus();
        text = read(file);
        checkTrue("a new warning is shown and logged once", text.contains("### Warnings\n\n- Disk is nearly full")
                && text.split("WARNING: Disk is nearly full", -1).length == 2);
        checkTrue("the newest log entry comes first", text.indexOf("WARNING: Disk") < text.indexOf("Deploy: 2 new") && text.indexOf("Deploy: 2 new") < text.indexOf("started keeping"));
        agent.saveManual("# My own notes\n\n- Studio Master is next\n");
        text = read(file);
        checkTrue("the admin edits the people-written part; the log stays", text.startsWith("# My own notes") && text.contains("Deploy: 2 new"));

        String vs = SuperAgent.vistrLine(MiniJson.object("{\"enabled\":true,\"playersOnline\":2,\"gamesPlaying\":{\"duo\":1,\"solo\":0},"
                + "\"bots\":2,\"botsPlaying\":1,\"botsWaitingForTheirPlayer\":1,\"botsCreated\":2,\"botsRemoved\":0,\"botGames\":3}"));
        checkTrue("the word game's status reads well", vs.equals("2 players online, 1 two-player and 0 solo games; bots: 2 (1 playing, 1 waiting for their player; 3 bot games since the game server started)"));
        checkTrue("and says when the game does not answer", SuperAgent.vistrLine(null).contains("no answer"));
        check("game", Alma.match("where can I play the word game?").id, "game");
        System.out.println("-- Alma: built-in answers --");
        check("sign up", Alma.match("How do I sign up?").id, "signup");
        check("apk", Alma.match("where can i download the APK for android").id, "apk");
        check("radio", Alma.match("how to find a radio station").id, "radio");
        check("upload", Alma.match("How do I upload videos?").id, "upload");
        check("go live", Alma.match("I want to go live from my phone").id, "golive");
        checkTrue("nonsense gets no built-in answer", Alma.match("purple elephants dance") == null);

        System.out.println("-- Alma over HTTP --");
        Path dl = app.resolve("downloads");
        DownloadApi downloads = new DownloadApi(dl, sessions, () -> now[0]);
        Alma alma = new Alma(sessions, app.resolve("alma-log.txt"), () -> now[0]);
        alma.setLiveFacts(() -> "- Apps ready to download: " + downloads.available() + ".\n");
        HttpServer srv = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        alma.register(srv);
        agent.register(srv);
        downloads.register(srv);
        srv.start();
        port = srv.getAddress().getPort();
        // a stand-in for OpenAI
        String[] lastInstructions = {""};
        int[] aiStatus = {200};
        HttpServer ai = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        ai.createContext("/v1/responses", ex -> {
            String body = new String(readAll(ex.getRequestBody()), StandardCharsets.UTF_8);
            lastInstructions[0] = body;
            byte[] out = ("{\"output\":[{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"Sure! Go to https://memoryq.com/me and tap \\\"Create account\\\".\"}]}]}")
                    .getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(aiStatus[0], out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        ai.start();
        String[] r = post("/api/alma/ask", "q", "how do i sign up");
        checkTrue("without OpenAI she answers from the built-in answers", r[0].equals("200") && r[1].contains("memoryq.com/me") && r[1].contains("\"source\":\"faq\""));
        r = post("/api/alma/ask", "q", "purple elephants");
        checkTrue("and says what she can help with when unsure", r[1].contains("\"source\":\"fallback\"") && r[1].contains("support@memoryq.com"));
        alma.setOpenAi("test-key", "gpt-5-nano", "http://127.0.0.1:" + ai.getAddress().getPort() + "/v1/responses");
        r = post("/api/alma/ask", "q", "hola, como me registro?", "ctx", "Visitor: hi\nAlma: Hi!");
        checkTrue("with OpenAI she answers in her own words", r[1].contains("\"source\":\"ai\"") && r[1].contains("Create account"));
        checkTrue("OpenAI gets the facts, the live facts and the visitor's text marked as such",
                lastInstructions[0].contains("You are Alma") && lastInstructions[0].contains("memoryq.com/download")
                        && lastInstructions[0].contains("Apps ready to download: none uploaded yet") && lastInstructions[0].contains("VISITOR\\nhola, como me registro?\\nVISITOR"));
        aiStatus[0] = 500;
        r = post("/api/alma/ask", "q", "where do i download the app");
        checkTrue("if OpenAI fails she still answers", r[1].contains("\"source\":\"faq\"") && r[1].contains("/download"));
        for (int i = 0; i < 16; i++) {
            post("/api/alma/ask", "q", "hello " + i);
        }
        r = post("/api/alma/ask", "q", "one more");
        checkTrue("too many questions too fast are slowed down", r[0].equals("429"));
        r = get("/alma.js", null);
        checkTrue("the chat bubble script is served", r[0].equals("200") && r[1].contains("Ask Alma") && r[1].contains("/api/alma/ask"));
        String admin = sessions.create("admin", UserStore.Role.ADMIN);
        String bob = sessions.create("bob", UserStore.Role.CHATTER);
        check("the question log is for the super-admin", get("/api/admin/alma/log", bob)[0], "403");
        r = get("/api/admin/alma/log", admin);
        checkTrue("the admin sees what people asked and how it was answered", r[1].contains("purple elephants") && r[1].contains("\"source\":\"fallback\"")
                && r[1].contains("\"source\":\"ai\"") && r[1].contains("\"today\":"));

        System.out.println("-- Downloads --");
        checkTrue("the page says Coming soon until an app is uploaded", get("/download", null)[1].contains("Coming soon"));
        check("not for members", http("POST", "/api/admin/downloads/upload?app=vstream", new byte[20000], bob)[0], "403");
        check("only real APK files", http("POST", "/api/admin/downloads/upload?app=vstream", new byte[20000], admin)[0], "400");
        byte[] apk = new byte[20000];
        apk[0] = 'P';
        apk[1] = 'K';
        r = http("POST", "/api/admin/downloads/upload?app=vstream&note=" + URLEncoder.encode("1.1 - live menu", "UTF-8"), apk, admin);
        checkTrue("the admin uploads the Vstream APK", r[0].equals("200") && r[1].contains("\"have\":true"));
        r = get("/download", null);
        checkTrue("the page offers it: version, what's new", r[1].contains("/download/vstream.apk") && r[1].contains("Vstream 1.1")
                && r[1].contains("version: 1.1") && r[1].contains("What's new:</b> live menu") && r[1].contains("downloads: 0"));
        checkTrue("and the file downloads", get("/download/vstream.apk", null)[1].length() == 20000);
        checkTrue("Alma knows it is ready", downloads.available().equals("Vstream 1.1"));
        checkTrue("the download is counted", get("/download", null)[1].contains("downloads: 1"));
        byte[] apk2 = new byte[30000];
        apk2[0] = 'P';
        apk2[1] = 'K';
        apk2[5] = 7;
        r = http("POST", "/api/admin/downloads/upload?app=vstream&version=1.2.0&released=2026-10-20&note=" + URLEncoder.encode("Faster start", "UTF-8"), apk2, admin);
        checkTrue("a new version (version, release date)", r[0].equals("200") && r[1].contains("\"version\":\"1.2.0\"") && r[1].contains("\"downloads\":1")
                && r[1].contains("\"thisVersion\":0"));
        r = get("/download", null);
        checkTrue("the page: the new version, its date, all downloads; the old one in the version history",
                r[1].contains("Vstream 1.2.0") && r[1].contains("October 20, 2026") && r[1].contains("downloads: 1")
                && r[1].contains("Version history") && r[1].contains("<td>1.1</td>") && r[1].contains("/download/old/vstream-1.1-"));
        String old = r[1].substring(r[1].indexOf("/download/old/"), r[1].indexOf('"', r[1].indexOf("/download/old/")));
        checkTrue("the old version still downloads", get(old, null)[1].length() == 20000);
        checkTrue("and is counted too", get("/download", null)[1].contains("downloads: 2"));
        checkTrue("the SHA-256 is shown", get("/download", null)[1].contains(DownloadApi.sha256(dl.resolve("vstream.apk"))));

        System.out.println("-- SERVERASSETS in the admin page --");
        check("not for members", get("/api/admin/agent", bob)[0], "403");
        r = get("/api/admin/agent", admin);
        checkTrue("the admin reads it", r[0].equals("200") && r[1].contains("Studio Master is next") && r[1].contains("Disk is nearly full"));
        r = post2("/api/admin/agent/save", admin, "manual", "# Memoryq assets\n\n- Word game bots next\n");
        checkTrue("and edits it", r[0].equals("200") && read(file).startsWith("# Memoryq assets"));
        r = post2("/api/admin/agent/refresh", admin);
        checkTrue("check now", r[0].equals("200") && r[1].contains("No code or page changed"));
        r = get("/api/admin/agent/file", admin);
        checkTrue("download the file", r[0].equals("200") && r[1].startsWith("# Memoryq assets"));
        srv.stop(0);
        ai.stop(0);
    }

    private static String read(Path p) throws Exception {
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }

    private static String[] get(String path, String tok) throws Exception {
        return http("GET", path, null, tok);
    }

    private static String[] post(String path, String... kv) throws Exception {
        return post2(path, null, kv);
    }

    private static String[] post2(String path, String tok, String... kv) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            sb.append(i > 0 ? "&" : "").append(URLEncoder.encode(kv[i], "UTF-8")).append('=').append(URLEncoder.encode(kv[i + 1], "UTF-8"));
        }
        return http("POST", path, sb.toString().getBytes(StandardCharsets.UTF_8), tok);
    }

    private static String[] http(String method, String path, byte[] body, String tok) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:" + port + path).openConnection();
        c.setRequestMethod(method);
        c.setReadTimeout(15000);
        if (tok != null) {
            c.setRequestProperty("Authorization", "Bearer " + tok);
        }
        if (body != null) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            c.getOutputStream().write(body);
        } else if (method.equals("POST")) {
            c.setDoOutput(true);
            c.getOutputStream().close();
        }
        int code = c.getResponseCode();
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        byte[] b = in == null ? new byte[0] : readAll(in);
        return new String[] {String.valueOf(code), new String(b, StandardCharsets.ISO_8859_1)};
    }

    private static byte[] readAll(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
        return out.toByteArray();
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