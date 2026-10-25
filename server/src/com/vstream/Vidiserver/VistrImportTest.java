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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

/** Profiler -> Vistr players: every game player gets a Memoryq account (MQ + the name when it is taken); the super-admin edits any profile. */
public final class VistrImportTest {

    private static int failures = 0;

    /** A pretend Vistr game: players by Player ID; it checks the admin code with Memoryq like the real one. */
    static final class FakeVistr implements VistrImport.Web {
        final Map<String, String[]> players = new LinkedHashMap<String, String[]>(); // code -> {name, email, mq}
        final List<String> calls = new ArrayList<String>();
        int memoryqPort;

        public String[] call(String method, String url, String json) throws java.io.IOException {
            calls.add(method + " " + url.replaceAll("\\?key=.*", ""));
            if (url.endsWith("/api/auth/memoryq")) {
                String code = String.valueOf(MiniJson.str(MiniJson.parse(json), "code"));
                String[] r;
                try {
                    r = post(memoryqPort, "/api/auth/exchange", "code=" + code);
                } catch (Exception e) {
                    throw new java.io.IOException(e);
                }
                if (!r[0].equals("200") || !r[1].contains("\"role\":\"ADMIN\"")) {
                    return new String[]{"403", "{\"error\":\"Only the Memoryq super-admin can open the game admin page.\"}"};
                }
                return new String[]{"200", "{\"adminKey\":\"adm_test\"}"};
            }
            if (!url.contains("key=adm_test")) {
                return new String[]{"403", "{\"error\":\"Wrong admin key.\"}"};
            }
            if (url.contains("/api/admin/players")) {
                StringBuilder sb = new StringBuilder("{\"players\":[");
                int i = 0;
                for (Map.Entry<String, String[]> e : players.entrySet()) {
                    sb.append(i++ > 0 ? "," : "").append("{\"playerCode\":\"").append(e.getKey()).append("\",\"name\":\"").append(e.getValue()[0])
                            .append("\",\"email\":\"").append(e.getValue()[1]).append("\",\"mq\":\"").append(e.getValue()[2]).append("\"}");
                }
                return new String[]{"200", sb.append("]}").toString()};
            }
            if (url.contains("/api/admin/player")) {
                Object b = MiniJson.parse(json);
                String code = MiniJson.str(b, "code");
                String[] p = players.get(code);
                if (p == null) {
                    return new String[]{"404", "{\"error\":\"No such player.\"}"};
                }
                String nc = MiniJson.str(b, "newCode");
                if (!nc.isEmpty()) {
                    if (players.containsKey(nc)) {
                        return new String[]{"409", "{\"error\":\"Player ID " + nc + " belongs to another player.\"}"};
                    }
                    players.remove(code);
                    players.put(nc, p);
                    code = nc;
                }
                if (MiniJson.at(b, "name") != null) {
                    p[0] = MiniJson.str(b, "name");
                }
                if (MiniJson.at(b, "mq") != null) {
                    p[2] = MiniJson.str(b, "mq");
                }
                return new String[]{"200", "{\"player\":{\"code\":\"" + code + "\",\"name\":\"" + p[0] + "\",\"email\":\"" + p[1] + "\",\"mq\":\"" + p[2] + "\"}}"};
            }
            return new String[]{"404", "{}"};
        }
    }

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
        long[] now = {1792700000000L};
        Path dir = Files.createTempDirectory("memoryq-vistrimport");
        UserStore us = new UserStore(dir.resolve("users.txt"), new LiveChannelRegistry(), c -> { });
        String adminPw = us.init(dir.resolve("none.txt"));
        us.createUser("mkarimu", "password123", UserStore.Role.CHATTER);
        us.createUser("neema", "password123", UserStore.Role.CHATTER);
        us.createUser("baraka", "password123", UserStore.Role.CHATTER);
        SessionStore sessions = new SessionStore(() -> now[0]);
        EmailService emails = new EmailService(dir.resolve("emails.txt"), us, null, () -> now[0]);
        emails.adminSet("neema", "neema@example.com");
        ProfileStore profiles = new ProfileStore(dir.resolve("profiles.txt"));
        profiles.init();
        AccountApi acc = new AccountApi(us, sessions, new Limiter(), new LiveChannelRegistry(), dir.resolve("admin.html"), x -> { }, x -> false);
        ServiceAccess services = new ServiceAccess(dir.resolve("services.txt"), us, sessions);
        SignInApi sign = new SignInApi(dir.resolve("signin.txt"), us, sessions, emails, acc.issuer(), () -> now[0]);
        VistrImport vi = new VistrImport(dir.resolve("vistr-import.txt"), us, sessions, profiles, emails, services, sign, () -> now[0]);
        FakeVistr game = new FakeVistr();
        game.players.put("10", new String[]{"mkarimu", "m@vistr.example", ""});
        game.players.put("11", new String[]{"Juma Ali", "juma@example.com", ""});
        game.players.put("12", new String[]{"", "neema@example.com", ""});
        game.players.put("13", new String[]{"Baraka", "b2@example.com", "baraka"});
        game.players.put("14", new String[]{"", "x@users.memoryq.com", ""});
        vi.setWeb(game, "https://vistr.test");
        HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        http.setExecutor(Executors.newCachedThreadPool());
        acc.register(http);
        sign.register(http);
        vi.register(http);
        http.start();
        int port = http.getAddress().getPort();
        game.memoryqPort = port;
        try {
            String admin = SignInTest.tok(post(port, "/api/login", "username=admin&password=" + URLEncoder.encode(adminPw, "UTF-8"))[1]);
            String neema = SignInTest.tok(post(port, "/api/login", "username=neema&password=password123")[1]);

            System.out.println("-- Bringing the Vistr players in --");
            checkTrue("members cannot", call(port, "/api/admin/vistr/import", "", neema)[0].equals("403"));
            String[] r = call(port, "/api/admin/vistr/import", "", admin);
            checkTrue("the import (" + r[1] + ")", r[0].equals("200") && r[1].contains("2 new accounts, 1 waiting") && r[1].contains("2 linked"));
            checkTrue("the game was asked as the super-admin, through memoryq.com", game.calls.get(0).equals("POST https://vistr.test/api/auth/memoryq"));
            checkTrue("a free name: an account with it", us.info("Juma_Ali") != null && profiles.get("Juma_Ali").firstName.equals("Juma")
                    && profiles.get("Juma_Ali").stageName.equals("Juma Ali"));
            checkTrue("a taken name: MQmkarimu, waiting for the super-admin", us.info("MQmkarimu") != null && us.info("mkarimu") != null);
            checkTrue("the same email as a member: linked to that member, no new account", us.info("player12") == null && game.players.get("12")[2].equals("neema"));
            checkTrue("already signed in with Memoryq: linked", game.players.get("13")[2].equals("baraka"));
            checkTrue("no name and no email: player + the Player ID", us.info("player14") != null);
            checkTrue("the game knows each player's account now", game.players.get("10")[2].equals("MQmkarimu") && game.players.get("11")[2].equals("Juma_Ali"));
            r = get(port, "/api/admin/vistr/players", admin);
            checkTrue("the list: waiting ones first, with the reason", r[1].indexOf("\"status\":\"waiting\"") < r[1].indexOf("\"status\":\"new\"")
                    && r[1].contains("is already a Memoryq member") && r[1].contains("\"waiting\":1"));
            int before = us.listInfos().size();
            checkTrue("running it again brings nobody twice", call(port, "/api/admin/vistr/import", "", admin)[1].contains("already brought in") && us.listInfos().size() == before);

            System.out.println("-- The super-admin decides --");
            checkTrue("a name that is taken is refused", call(port, "/api/admin/vistr/rename", "code=10&username=neema", admin)[0].equals("400"));
            r = call(port, "/api/admin/vistr/rename", "code=10&username=mkarimu_tz", admin);
            checkTrue("renamed (" + r[1] + ")", r[0].equals("200") && us.info("mkarimu_tz") != null && us.info("MQmkarimu") == null
                    && game.players.get("10")[2].equals("mkarimu_tz") && r[1].contains("\"status\":\"named\""));
            checkTrue("its profile came along", profiles.get("mkarimu_tz") != null && profiles.get("mkarimu_tz").stageName.equals("mkarimu"));
            checkTrue("members who were here before keep their names", call(port, "/api/admin/vistr/rename", "code=13&username=bb_new", admin)[0].equals("400"));
            r = call(port, "/api/admin/vistr/player", "code=11&newCode=21&name=" + URLEncoder.encode("Juma A.", "UTF-8"), admin);
            checkTrue("the player's name and Player ID in the game", r[0].equals("200") && game.players.containsKey("21") && game.players.get("21")[0].equals("Juma A.")
                    && r[1].contains("\"code\":\"21\""));
            checkTrue("a Player ID that is taken is refused", call(port, "/api/admin/vistr/player", "code=21&newCode=12", admin)[0].equals("409"));
            r = call(port, "/api/admin/vistr/email", "code=21", admin);
            checkTrue("the player's email becomes the account's", r[0].equals("200") && emails.get("Juma_Ali").email.equals("juma@example.com"));
            checkTrue("keep the name", call(port, "/api/admin/vistr/keep", "code=14", admin)[1].contains("kept player14"));

            System.out.println("-- The whole profile, for the super-admin --");
            r = get(port, "/api/admin/member?u=Juma_Ali", admin);
            checkTrue("everything about a member", r[0].equals("200") && r[1].contains("\"renamable\":true") && r[1].contains("\"vistrCode\":\"21\""));
            r = call(port, "/api/admin/member/edit", "u=neema&firstName=Neema&lastName=Juma&stageName=" + URLEncoder.encode("DJ Neema", "UTF-8")
                    + "&city=Arusha&country=Tanzania&education=UDSM&hobbies=" + URLEncoder.encode("music, radio", "UTF-8") + "&color=%232f6fed&email=neema2@example.com&password=newpassword9", admin);
            checkTrue("names, place, education, hobbies, colour, email, password (" + r[1] + ")", r[0].equals("200") && profiles.get("neema").stageName.equals("DJ Neema")
                    && profiles.get("neema").hobbies.size() == 2 && emails.get("neema").email.equals("neema2@example.com")
                    && us.authenticate("neema", "newpassword9") != null && profiles.get("neema").color.equals("#2f6fed"));
            checkTrue("a username change only for accounts from Vistr", call(port, "/api/admin/member/edit", "u=neema&username=neema_x", admin)[0].equals("400"));
            r = call(port, "/api/admin/member/edit", "u=player14&username=Zawadi", admin);
            checkTrue("... which works for them", r[0].equals("200") && us.info("Zawadi") != null && game.players.get("14")[2].equals("Zawadi"));
            checkTrue("members cannot edit others", call(port, "/api/admin/member/edit", "u=baraka&firstName=x", neema)[0].equals("403"));
            VistrImport again = new VistrImport(dir.resolve("vistr-import.txt"), us, sessions, profiles, emails, services, sign, () -> now[0]);
            again.init();
            again.setWeb(game, "https://vistr.test");
            checkTrue("kept across a restart", again.importAll("admin").contains("5 already brought in"));
        } finally {
            http.stop(0);
        }
        System.out.println(failures == 0 ? "ALL CHECKS PASSED" : failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }

    static String[] post(int port, String path, String body) throws Exception {
        return call(port, path, body, null);
    }

    private static String[] get(int port, String path, String token) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:" + port + path).openConnection();
        c.setRequestProperty("Connection", "close");
        if (token != null) {
            c.setRequestProperty("Authorization", "Bearer " + token);
        }
        int code = c.getResponseCode();
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        return new String[]{String.valueOf(code), new String(read(in), StandardCharsets.UTF_8)};
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
        c.setRequestProperty("Connection", "close");
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
        return new String[]{String.valueOf(code), new String(read(in), StandardCharsets.UTF_8)};
    }

    private static void checkTrue(String name, boolean ok) {
        System.out.println((ok ? "  ok    " : "  FAIL  ") + name);
        if (!ok) {
            failures++;
        }
    }
}