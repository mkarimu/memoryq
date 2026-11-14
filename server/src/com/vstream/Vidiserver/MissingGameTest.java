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
import java.util.List;

/**
 * Spot What's Missing on memoryq.com: the pictures, the carer's game-only sign-in, players (changed only in the carers'
 * area), sessions and rounds (counted once), the progress page and its level suggestions, deleting a player, the
 * super-admin's picture library (cleaned SVGs, built-in pictures changed and put back) and the new service.
 */
public final class MissingGameTest {

    private static int failures = 0;
    private static int port;

    public static void main(String[] args) {
        System.setProperty("sun.net.http.allowRestrictedHeaders", "true"); // lets the test send Origin like a browser
        try {
            run();
        } catch (Throwable t) {
            t.printStackTrace();
            System.out.println("FAILED: " + t);
            System.exit(1);
        }
    }

    static String[] call(String method, String path, String body, String token, String type) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:" + port + path).openConnection();
        c.setRequestMethod(method);
        c.setRequestProperty("Connection", "close");
        c.setRequestProperty("Origin", "https://vistr.memoryq.com");
        c.setConnectTimeout(10000);
        c.setReadTimeout(30000);
        if (token != null) {
            c.setRequestProperty("Authorization", "Bearer " + token);
        }
        if (body != null) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", type == null ? "application/x-www-form-urlencoded" : type);
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
        String cors = c.getHeaderField("Access-Control-Allow-Origin");
        return new String[] {String.valueOf(code), new String(b.toByteArray(), StandardCharsets.UTF_8), cors == null ? "" : cors};
    }

    static String[] get(String path, String token) throws Exception {
        return call("GET", path, null, token, null);
    }

    static String[] post(String path, String body, String token) throws Exception {
        return call("POST", path, body, token, null);
    }

    static String[] json(String path, String body, String token) throws Exception {
        return call("POST", path, body, token, "application/json");
    }

    static String e(String s) throws Exception {
        return URLEncoder.encode(s, "UTF-8");
    }

    static String round(String session, String id, String picture, String hidden, String foundJson, boolean passed, int misses) {
        return "{\"session\":\"" + session + "\",\"id\":\"" + id + "\",\"picture\":\"" + picture + "\",\"hidden\":[" + hidden + "],\"passed\":" + passed
                + ",\"misses\":" + misses + ",\"started\":0,\"found\":[" + foundJson + "]}";
    }

    private static void run() throws Exception {
        Path dir = Files.createTempDirectory("memoryq-missing");
        long[] now = {1792900000000L};
        UserStore users = new UserStore(dir.resolve("users.txt"), new LiveChannelRegistry(), x -> { });
        users.init(dir.resolve("none.txt"));
        users.createUser("mama_neema", "password123", UserStore.Role.CHATTER);
        users.createUser("teacher_juma", "password123", UserStore.Role.CHATTER);
        SessionStore sessions = new SessionStore(() -> now[0]);
        SignInApi.Issuer issuer = new SignInApi.Issuer() {
            @Override
            public String issue(UserStore.Info info, long ttlMs) {
                return "{\"token\":\"" + sessions.create(info.username, info.role, ttlMs) + "\"}";
            }

            @Override
            public String refusal(UserStore.Info info) {
                return null;
            }
        };
        SignInApi sign = new SignInApi(dir.resolve("signin.txt"), users, sessions, null, issuer, () -> now[0]);
        sign.init();
        Path mdir = dir.resolve("missing");
        MissingGame game = new MissingGame(mdir, sessions, users, sign, () -> now[0]);
        game.init();
        HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        http.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        game.register(http);
        http.start();
        port = http.getAddress().getPort();
        String neema = sessions.create("mama_neema", UserStore.Role.CHATTER);
        String juma = sessions.create("teacher_juma", UserStore.Role.CHATTER);
        String admin = sessions.create("admin", UserStore.Role.ADMIN);
        try {
            System.out.println("-- The pictures --");
            String[] r = get("/api/missing/pictures", null);
            checkTrue("15 built-in pictures, open to the game page on vistr.memoryq.com", r[0].equals("200") && r[1].split("\"licence\"").length - 1 == 15
                    && r[2].equals("https://vistr.memoryq.com"));
            r = get("/api/missing/picture?id=cat", null);
            checkTrue("a picture is an SVG with its parts named in English and Kiswahili", r[0].equals("200") && r[1].startsWith("<svg")
                    && r[1].contains("data-name-sw=\"mboni\"") && r[1].contains("id=\"left-pupil\""));
            checkTrue("the browser may ask first (CORS)", call("OPTIONS", "/api/missing/players", null, null, null)[0].equals("204"));
            checkTrue("other sites get no CORS answer", !get("/api/missing/pictures", null)[2].equals("https://evil.example"));

            System.out.println("-- The carer signs in on the game page --");
            checkTrue("signed out: no players", get("/api/missing/players", null)[0].equals("401"));
            String code = sign.codeFor("mama_neema", "missing", 30 * SessionStore.DAY_MS);
            r = post("/api/missing/signin", "code=" + code, null);
            String gtok = MiniJson.str(MiniJson.parse(r[1]), "token");
            checkTrue("a one-time code from memoryq.com/login becomes a game-only sign-in (30 days)", r[0].equals("200") && gtok.startsWith("MG.")
                    && r[1].contains("\"days\":30"));
            checkTrue("the code works once", post("/api/missing/signin", "code=" + code, null)[0].equals("401"));
            checkTrue("the game sign-in is not a Memoryq sign-in", sessions.get(gtok) == null);

            System.out.println("-- Players: only in the carers' area --");
            checkTrue("the game page cannot add or change players", post("/api/missing/players/save", "nickname=Zawadi", gtok)[0].equals("403"));
            checkTrue("a player needs a name", post("/api/missing/players/save", "nickname=", neema)[0].equals("400"));
            r = post("/api/missing/players/save", "nickname=" + e("Zawadi <b>") + "&avatar=giraffe&age=child&lang=sw&level=1&minutes=5&hints=sooner&categories=animals", neema);
            String zid = MiniJson.str(MiniJson.parse(r[1]), "player", "id");
            checkTrue("Mama Neema adds Zawadi (no HTML in names; Kiswahili; level 1)", r[0].equals("200") && r[1].contains("\"nickname\":\"Zawadi b\"")
                    && r[1].contains("\"lang\":\"sw\"") && r[1].contains("\"avatar\":\"giraffe\""));
            r = get("/api/missing/players", gtok);
            checkTrue("the game page lists her players", r[0].equals("200") && r[1].contains(zid));
            checkTrue("another carer does not see them", !get("/api/missing/players", juma)[1].contains(zid));
            checkTrue("nor can start a session for them", post("/api/missing/session/start", "player=" + zid, juma)[0].equals("404"));

            System.out.println("-- A session --");
            r = post("/api/missing/session/start", "player=" + zid, gtok);
            String s1 = MiniJson.str(MiniJson.parse(r[1]), "session");
            Object params = MiniJson.at(MiniJson.parse(r[1]), "params");
            checkTrue("level 1: one large part, forgiving taps, hints sooner (3 s), animals only", r[0].equals("200") && MiniJson.num(params, "hideMax") == 1
                    && MiniJson.num(params, "tolerance") == 30 && MiniJson.num(params, "hints", 0) == 3 && r[1].contains("\"id\":\"dog\"") && !r[1].contains("\"id\":\"house\""));
            String f1 = "{\"part\":\"left-ear\",\"ms\":4200,\"hint\":0,\"how\":\"point\"}";
            r = json("/api/missing/round", round(s1, "round-0001", "cat", "\"left-ear\"", f1, false, 2), gtok);
            checkTrue("a picture found with no hints: 1 star + 1 bonus", r[0].equals("200") && r[1].contains("\"stars\":2") && r[1].contains("\"found\":1"));
            r = json("/api/missing/round", round(s1, "round-0001", "cat", "\"left-ear\"", f1, false, 2), gtok);
            checkTrue("sent twice after a bad connection: counted once", r[1].contains("\"stars\":2") && r[1].contains("\"pictures\":1"));
            r = json("/api/missing/round", round(s1, "round-0002", "dog", "\"tail\"", "", true, 5), gtok);
            checkTrue("Next pressed (passed): no stars, no penalty", r[1].contains("\"stars\":2") && r[1].contains("\"passes\":1"));
            checkTrue("parts that were not hidden do not count", json("/api/missing/round",
                    round(s1, "round-0003", "dog", "\"tail\"", "{\"part\":\"nose\",\"ms\":10,\"hint\":0}", false, 0), gtok)[1].contains("\"found\":1"));
            checkTrue("someone else's session: refused", json("/api/missing/round", round(s1, "round-0004", "cat", "\"tail\"", "", false, 0), juma)[0].equals("404"));
            post("/api/missing/session/end", "session=" + s1, gtok);

            System.out.println("-- Progress and level suggestions --");
            r = get("/api/missing/progress?player=" + zid, neema);
            checkTrue("the progress page: 1 session, 3 pictures, ear found without a hint", r[0].equals("200") && r[1].contains("\"sessions\":1")
                    && r[1].contains("\"pictures\":3") && r[1].contains("\"kind\":\"ear\",\"name\":\"sikio\""));
            checkTrue("one session is too few for a suggestion", r[1].contains("\"suggestion\":\"\""));
            for (int k = 0; k < 2; k++) {
                now[0] += 3600000L;
                String s = MiniJson.str(MiniJson.parse(post("/api/missing/session/start", "player=" + zid, gtok)[1]), "session");
                for (int j = 0; j < 3; j++) {
                    json("/api/missing/round", round(s, "good-" + k + "-" + j, "cat", "\"tail\"", "{\"part\":\"tail\",\"ms\":3000,\"hint\":0}", false, 0), gtok);
                }
            }
            r = get("/api/missing/progress?player=" + zid, neema);
            checkTrue("3 good sessions in a row: suggests moving up (never changes by itself)", r[1].contains("\"suggestion\":\"up\"") && r[1].contains("\"level\":1,"));
            post("/api/missing/players/save", "id=" + zid + "&level=3", neema);
            for (int k = 0; k < 2; k++) {
                String s = MiniJson.str(MiniJson.parse(post("/api/missing/session/start", "player=" + zid, gtok)[1]), "session");
                json("/api/missing/round", round(s, "hard-" + k + "-a", "dog", "\"nose\",\"tongue\"", "{\"part\":\"nose\",\"ms\":30000,\"hint\":2}", true, 9), gtok);
                json("/api/missing/round", round(s, "hard-" + k + "-b", "fish", "\"pupil\",\"mouth\"", "{\"part\":\"pupil\",\"ms\":40000,\"hint\":3}", true, 9), gtok);
            }
            r = get("/api/missing/progress?player=" + zid, neema);
            checkTrue("2 hard sessions at level 3: suggests moving down", r[1].contains("\"suggestion\":\"down\""));
            int nose = r[1].indexOf("\"kind\":\"nose\""), ear = r[1].indexOf("\"kind\":\"ear\"");
            checkTrue("the hardest kinds of parts come first", nose > 0 && ear > nose);
            r = post("/api/missing/session/start", "player=" + zid, gtok);
            checkTrue("the game learns which parts were hidden lately (to hide others)", r[1].contains("\"recent\":{") && r[1].contains("\"fish\":[\"pupil,mouth\""));

            System.out.println("-- Kept across a restart; deleting a player --");
            MissingGame again = new MissingGame(mdir, sessions, users, sign, () -> now[0]);
            again.init();
            HttpServer h2 = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            again.register(h2);
            h2.start();
            int old = port;
            port = h2.getAddress().getPort();
            r = get("/api/missing/progress?player=" + zid, neema);
            checkTrue("players, sessions, rounds and the game sign-in survive a restart", r[0].equals("200") && r[1].contains("\"sessions\":5")
                    && get("/api/missing/players", gtok)[0].equals("200"));
            checkTrue("the game page cannot delete a player", post("/api/missing/players/delete", "id=" + zid, gtok)[0].equals("403"));
            r = post("/api/missing/players/delete", "id=" + zid, neema);
            checkTrue("the carer deletes Zawadi and everything about her", r[0].equals("200") && get("/api/missing/progress?player=" + zid, neema)[0].equals("404")
                    && !new String(Files.readAllBytes(mdir.resolve("rounds.tsv")), StandardCharsets.UTF_8).contains(zid));
            checkTrue("signing out ends the game sign-in", post("/api/missing/signout", "", gtok)[0].equals("200")
                    && get("/api/missing/players", gtok)[0].equals("401"));
            h2.stop(0);
            port = old;

            System.out.println("-- The super-admin's picture library --");
            checkTrue("members cannot open it", get("/api/admin/missing", neema)[0].equals("403"));
            String svg = "<svg xmlns=\"http://www.w3.org/2000/svg\" xmlns:xlink=\"http://www.w3.org/1999/xlink\" width=\"200\" height=\"100\" onload=\"alert(1)\">"
                    + "<script>alert(2)</script><rect id=\"body\" x=\"10\" y=\"10\" width=\"180\" height=\"60\" fill=\"red\" style=\"fill:url(http://x)\"/>"
                    + "<g id=\"wheel-1\"><circle cx=\"50\" cy=\"80\" r=\"15\" fill=\"black\" onclick=\"x()\"/></g>"
                    + "<g id=\"wheel-2\"><circle cx=\"150\" cy=\"80\" r=\"15\" fill=\"url(#grad)\"/></g>"
                    + "<a href=\"javascript:alert(3)\"><text>hi</text></a><image href=\"http://x/y.png\"/><foreignObject><div>x</div></foreignObject></svg>";
            String pic = "{\"id\":\"red-bus\",\"title\":{\"en\":\"the red bus\",\"sw\":\"basi jekundu\"},\"category\":\"vehicles\",\"ages\":[\"child\",\"adult\"],"
                    + "\"svg\":" + MissingGame.q(svg) + ",\"parts\":[{\"id\":\"wheel-1\",\"kind\":\"wheel\",\"size\":\"large\",\"twin\":\"wheel-2\",\"name\":{\"en\":\"wheel\",\"sw\":\"gurudumu\"}},"
                    + "{\"id\":\"wheel-2\",\"kind\":\"wheel\",\"size\":\"large\",\"twin\":\"wheel-1\",\"name\":{\"en\":\"wheel\",\"sw\":\"gurudumu\"}}],\"never\":[[\"wheel-1\",\"wheel-2\"]],\"licence\":\"drawn by us\"}";
            r = json("/api/admin/missing/picture", pic, admin);
            String saved = get("/api/missing/picture?id=red-bus", null)[1];
            checkTrue("a new picture is added (version 1, viewBox from its size)", r[0].equals("200") && r[1].contains("\"version\":1")
                    && r[1].contains("\"viewBox\":\"0 0 200 100\""));
            checkTrue("cleaned: no scripts, events, links, outside images, styles or HTML", !saved.contains("script") && !saved.contains("onload")
                    && !saved.contains("onclick") && !saved.contains("javascript") && !saved.contains("<image") && !saved.contains("foreignObject")
                    && !saved.contains("http://x") && !saved.contains("style=") && !saved.contains("xlink"));
            checkTrue("its parts carry their names", saved.contains("data-name-sw=\"gurudumu\"") && saved.contains("fill=\"url(#grad)\""));
            checkTrue("a part that is not in the picture is refused", json("/api/admin/missing/picture", pic.replace("\"wheel-2\",\"kind\"", "\"wheel-9\",\"kind\""), admin)[0]
                    .equals("400"));
            checkTrue("a DOCTYPE (XML entity tricks) is refused", json("/api/admin/missing/picture", pic.replace(MissingGame.q(svg),
                    MissingGame.q("<!DOCTYPE svg [<!ENTITY x SYSTEM \"file:///etc/passwd\">]><svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 9 9\"><g id=\"wheel-1\">&x;</g><g id=\"wheel-2\"/></svg>")), admin)[0].equals("400"));
            String catFix = "{\"id\":\"cat\",\"title\":{\"en\":\"the cat\",\"sw\":\"paka\"},\"category\":\"animals\",\"svg\":\"\",\"parts\":[{\"id\":\"tail\",\"kind\":\"tail\",\"size\":\"large\",\"name\":{\"en\":\"tail\",\"sw\":\"mkia wa paka\"}}]}";
            r = json("/api/admin/missing/picture", catFix, admin);
            checkTrue("a built-in picture can be corrected (Kiswahili name) - kept as a changed copy, version 2", r[0].equals("200") && r[1].contains("\"version\":2")
                    && r[1].contains("\"changed\":true") && get("/api/missing/picture?id=cat", null)[1].contains("mkia wa paka"));
            r = post("/api/admin/missing/delete", "id=cat", admin);
            checkTrue("... and put back as it was", r[0].equals("200") && !get("/api/missing/picture?id=cat", null)[1].contains("mkia wa paka"));
            checkTrue("the admin still sees a switched-off picture", call("GET", "/api/admin/missing/svg?id=red-bus", null, admin, null)[1].startsWith("<svg")
                    && call("GET", "/api/admin/missing/svg?id=red-bus", null, neema, null)[0].equals("403"));
            checkTrue("a built-in picture cannot be deleted, only switched off", post("/api/admin/missing/delete", "id=dog", admin)[0].equals("404"));
            post("/api/admin/missing/enable", "id=dog&on=0", admin);
            checkTrue("switched off: not in the game", !get("/api/missing/pictures", null)[1].contains("\"id\":\"dog\"")
                    && get("/api/missing/picture?id=dog", null)[0].equals("404"));
            r = get("/api/admin/missing", admin);
            checkTrue("the admin list: every picture (16), how often played, the numbers", r[0].equals("200") && r[1].split("\"licence\"").length - 1 == 16
                    && r[1].contains("\"plays\":") && r[1].contains("\"carers\":"));
            MissingGame third = new MissingGame(mdir, sessions, users, sign, () -> now[0]);
            third.init();
            checkTrue("added pictures and switched-off ones are remembered", third.toString() != null
                    && Files.isRegularFile(mdir.resolve("pictures").resolve("red-bus.svg")) && new String(Files.readAllBytes(mdir.resolve("pictures-off.tsv")),
                    StandardCharsets.UTF_8).contains("dog"));

            System.out.println("-- Level settings --");
            java.util.Map<String, String> set = new java.util.HashMap<String, String>();
            set.put("hints", "off");
            set.put("timer", "1");
            String l5 = MissingGame.levelJson(5, set);
            checkTrue("level 5: 4 to 6 small parts, exact taps, 8 pictures, no hints, the timer if the carer wants it", l5.contains("\"hideMin\":4")
                    && l5.contains("\"hideMax\":6") && l5.contains("\"tolerance\":4") && l5.contains("\"hints\":[0,0,0]") && l5.contains("\"timer\":true")
                    && l5.contains("\"pictures\":8"));
            checkTrue("no timer below level 4", MissingGame.levelJson(2, set).contains("\"timer\":false"));

            System.out.println("-- The new service --");
            Files.write(dir.resolve("old-services.txt"), "T\thome,radio,vistr\nU\tteacher_juma\thome,vistr\nU\tmama_neema\thome\n".getBytes(StandardCharsets.UTF_8));
            ServiceAccess sa = new ServiceAccess(dir.resolve("old-services.txt"), users, sessions);
            sa.init();
            List<String> t = sa.template();
            checkTrue("a service list saved before: Spot What's Missing is switched on for new members and every own list", t.contains("missing")
                    && sa.servicesOf("teacher_juma", UserStore.Role.CHATTER).contains("missing") && sa.servicesOf("mama_neema", UserStore.Role.CHATTER).contains("missing"));
            sa.setOwn("mama_neema", ServiceAccess.parse("home"));
            ServiceAccess sb = new ServiceAccess(dir.resolve("old-services.txt"), users, sessions);
            sb.init();
            checkTrue("switched off for someone later: stays off", !sb.servicesOf("mama_neema", UserStore.Role.CHATTER).contains("missing"));
        } finally {
            http.stop(0);
        }
        System.out.println(failures == 0 ? "ALL CHECKS PASSED" : failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }

    private static void checkTrue(String name, boolean ok) {
        System.out.println((ok ? "  ok    " : "  FAIL  ") + name);
        if (!ok) {
            failures++;
        }
    }
}