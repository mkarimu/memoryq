package com.vstream.Vidiserver;

import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.math.BigInteger;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;

/** One account everywhere: remember me (30 days, survives restarts), Google sign-in, handing the sign-in to Vistr / the app, services. */
public final class SignInTest {

    private static int failures = 0;
    static final String CID = "1234567890-abc123.apps.googleusercontent.com";

    public static void main(String[] args) {
        try {
            run();
        } catch (Throwable t) {
            t.printStackTrace();
            System.out.println("FAILED: " + t);
            System.exit(1);
        }
    }

    static String b64(byte[] b) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    static String b64(BigInteger n) {
        byte[] b = n.toByteArray();
        if (b.length > 1 && b[0] == 0) {
            b = java.util.Arrays.copyOfRange(b, 1, b.length);
        }
        return b64(b);
    }

    static String jwt(KeyPair kp, String kid, String aud, String sub, String email, boolean verified, long exp) throws Exception {
        String head = b64(("{\"alg\":\"RS256\",\"kid\":\"" + kid + "\",\"typ\":\"JWT\"}").getBytes(StandardCharsets.UTF_8));
        String body = b64(("{\"iss\":\"https://accounts.google.com\",\"aud\":\"" + aud + "\",\"sub\":\"" + sub + "\",\"email\":\"" + email
                + "\",\"email_verified\":" + verified + ",\"name\":\"Neema Juma\",\"exp\":" + exp + "}").getBytes(StandardCharsets.UTF_8));
        Signature s = Signature.getInstance("SHA256withRSA");
        s.initSign(kp.getPrivate());
        s.update((head + "." + body).getBytes(StandardCharsets.US_ASCII));
        return head + "." + body + "." + b64(s.sign());
    }

    private static void run() throws Exception {
        long[] now = {1792700000000L};
        Path dir = Files.createTempDirectory("memoryq-signin");

        System.out.println("-- Remember me --");
        checkTrue("ticked = 30 days, not ticked = 1 day, older apps (no answer) = 7 days", SessionStore.ttlFor("1") == 30 * SessionStore.DAY_MS
                && SessionStore.ttlFor("0") == SessionStore.DAY_MS && SessionStore.ttlFor(null) == 7 * SessionStore.DAY_MS);
        SessionStore s1 = new SessionStore(() -> now[0]);
        s1.persistTo(dir.resolve("sessions.txt"));
        String long1 = s1.create("amina", UserStore.Role.CHATTER, SessionStore.REMEMBER_MS);
        String short1 = s1.create("amina", UserStore.Role.CHATTER, SessionStore.SHORT_MS);
        s1.flushIfDirty();
        String file = new String(Files.readAllBytes(dir.resolve("sessions.txt")), StandardCharsets.UTF_8);
        checkTrue("the file keeps fingerprints, never the sign-ins", !file.contains(long1) && !file.contains(short1) && file.contains("amina"));
        SessionStore s2 = new SessionStore(() -> now[0]);
        s2.persistTo(dir.resolve("sessions.txt"));
        checkTrue("sign-ins survive a restart (an update)", s2.get(long1) != null && s2.get(short1) != null);
        now[0] += 2 * SessionStore.DAY_MS;
        checkTrue("after 2 days: the short one has ended, the remembered one has not", s2.get(short1) == null && s2.get(long1) != null);
        now[0] += 29 * SessionStore.DAY_MS;
        checkTrue("after 31 days the remembered one has ended too", s2.get(long1) == null);

        UserStore us = new UserStore(dir.resolve("users.txt"), new LiveChannelRegistry(), c -> { });
        String adminPw = us.init(dir.resolve("none.txt"));
        us.createUser("amina", "password123", UserStore.Role.CHATTER);
        us.createUser("baraka", "password123", UserStore.Role.CHATTER);
        SessionStore sessions = new SessionStore(() -> now[0]);
        EmailService emails = new EmailService(dir.resolve("emails.txt"), us, null, () -> now[0]);
        emails.adminSet("baraka", "baraka@example.com");
        emails.adminSet("admin", "boss@example.com");
        AccountApi acc = new AccountApi(us, sessions, new Limiter(), new LiveChannelRegistry(), dir.resolve("admin.html"), x -> { }, x -> false);
        ServiceAccess services = new ServiceAccess(dir.resolve("services.txt"), us, sessions);
        services.init();
        acc.setServices(services::json);
        SignInApi sign = new SignInApi(dir.resolve("signin.txt"), us, sessions, emails, acc.issuer(), () -> now[0]);
        sign.init();
        acc.setGoogleOf(sign::googleOf);
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        KeyPair kp = g.generateKeyPair(), other = g.generateKeyPair();
        RSAPublicKey pub = (RSAPublicKey) kp.getPublic();
        String jwks = "{\"keys\":[{\"kid\":\"k1\",\"kty\":\"RSA\",\"alg\":\"RS256\",\"n\":\"" + b64(pub.getModulus()) + "\",\"e\":\"" + b64(pub.getPublicExponent()) + "\"}]}";
        sign.setFetcher(url -> jwks);
        HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        acc.register(http);
        sign.register(http);
        services.register(http);
        http.start();
        int port = http.getAddress().getPort();
        long exp = now[0] / 1000 + 3600;
        try {
            String[] r = call(port, "/api/login", "username=amina&password=password123&remember=1", null);
            checkTrue("password sign-in, remembered: 30 days (" + r[1] + ")", r[0].equals("200") && r[1].contains("\"days\":30"));
            r = call(port, "/api/login", "username=amina&password=password123&remember=0", null);
            checkTrue("not remembered: 1 day", r[1].contains("\"days\":1"));
            r = call(port, "/api/login", "username=amina&password=password123", null);
            checkTrue("older apps: 7 days as before", r[1].contains("\"days\":7"));
            String amina = tok(r[1]);

            System.out.println("-- Sign in with Google --");
            checkTrue("off until the client ID is set", get(port, "/api/auth/config", null)[1].contains("\"google\":\"\"")
                    && call(port, "/api/auth/google", "credential=" + jwt(kp, "k1", CID, "g1", "neema@gmail.com", true, exp), null)[0].equals("401"));
            sign.setClientId(CID);
            checkTrue("the client ID is given to the pages", get(port, "/api/auth/config", null)[1].contains(CID));
            r = call(port, "/api/auth/google", "remember=1&credential=" + jwt(kp, "k1", CID, "g1", "neema@gmail.com", true, exp), null);
            checkTrue("a new Google user gets a Memoryq account (" + r[1] + ")", r[0].equals("200") && r[1].contains("\"username\":\"Neema_Juma\"")
                    && r[1].contains("\"days\":30") && emails.ownerOf("neema@gmail.com").equals("Neema_Juma"));
            r = call(port, "/api/auth/google", "credential=" + jwt(kp, "k1", CID, "g1", "neema@gmail.com", true, exp), null);
            checkTrue("next time: the same account", r[1].contains("\"username\":\"Neema_Juma\"") && us.listInfos().stream().filter(i -> i.username.startsWith("Neema")).count() == 1);
            r = call(port, "/api/auth/google", "credential=" + jwt(kp, "k1", CID, "g2", "baraka@example.com", true, exp), null);
            checkTrue("a member's verified email: their own account", r[1].contains("\"username\":\"baraka\""));
            checkTrue("a different website's sign-in is refused", call(port, "/api/auth/google", "credential=" + jwt(kp, "k1", "999-other.apps.googleusercontent.com", "g3", "x@gmail.com", true, exp), null)[1].contains("another website"));
            checkTrue("a forged one is refused", call(port, "/api/auth/google", "credential=" + jwt(other, "k1", CID, "g3", "x@gmail.com", true, exp), null)[1].contains("not genuine"));
            checkTrue("an email Google has not verified is refused", call(port, "/api/auth/google", "credential=" + jwt(kp, "k1", CID, "g3", "x@gmail.com", false, exp), null)[0].equals("401"));
            checkTrue("an old one is refused", call(port, "/api/auth/google", "credential=" + jwt(kp, "k1", CID, "g3", "x@gmail.com", true, exp - 7200), null)[1].contains("too old"));
            r = call(port, "/api/auth/google", "credential=" + jwt(kp, "k1", CID, "g9", "boss@example.com", true, exp), null);
            checkTrue("the super-admin is never linked by email alone (" + r[1] + ")", r[0].equals("403"));
            String admin = tok(call(port, "/api/login", "username=admin&password=" + enc(adminPw), null)[1]);
            r = call(port, "/api/auth/google", "link=1&credential=" + jwt(kp, "k1", CID, "g9", "boss@example.com", true, exp), admin);
            checkTrue("... but can link Google while signed in", r[0].equals("200") && r[1].contains("\"role\":\"ADMIN\""));
            r = call(port, "/api/auth/google", "credential=" + jwt(kp, "k1", CID, "g9", "boss@example.com", true, exp), null);
            checkTrue("then signs in with Google", r[0].equals("200") && r[1].contains("\"username\":\"admin\"") && r[1].contains("\"google\":"));
            checkTrue("one Google account per Memoryq account", call(port, "/api/auth/google", "link=1&credential=" + jwt(kp, "k1", CID, "g10", "boss2@example.com", true, exp), admin)[0].equals("409"));
            SignInApi again = new SignInApi(dir.resolve("signin.txt"), us, sessions, emails, acc.issuer(), () -> now[0]);
            again.init();
            checkTrue("links and the client ID are kept", again.clientId().equals(CID) && "boss@example.com".equals(again.googleOf("admin")));

            System.out.println("-- Handing the sign-in to Vistr and the app --");
            checkTrue("only when signed in", call(port, "/api/auth/handoff", "app=vistr&return=" + enc("https://vistr.memoryq.com/"), null)[0].equals("401"));
            checkTrue("never to other websites", call(port, "/api/auth/handoff", "app=vistr&return=" + enc("https://evil.example/"), amina)[0].equals("400"));
            r = call(port, "/api/auth/handoff", "app=vistr&remember=1&return=" + enc("https://vistr.memoryq.com/#old"), amina);
            String code = r[1].replaceAll(".*\"code\":\"([^\"]+)\".*", "$1");
            checkTrue("a one-time code in the address (" + r[1] + ")", r[0].equals("200") && r[1].contains("\"url\":\"https://vistr.memoryq.com/#mq=" + code + "\""));
            r = call(port, "/api/auth/exchange", "code=" + code, null);
            checkTrue("the game swaps it for its own 30-day sign-in", r[0].equals("200") && r[1].contains("\"username\":\"amina\"") && r[1].contains("\"days\":30")
                    && r[1].contains("\"app\":\"vistr\"") && sessions.get(tok(r[1])) != null);
            checkTrue("a code works once", call(port, "/api/auth/exchange", "code=" + code, null)[0].equals("401"));
            r = call(port, "/api/auth/handoff", "app=memoryq-app&remember=0&return=" + enc("memoryq-app://signin"), amina);
            code = r[1].replaceAll(".*\"code\":\"([^\"]+)\".*", "$1");
            now[0] += 3 * 60000;
            checkTrue("and for 2 minutes only", call(port, "/api/auth/exchange", "code=" + code, null)[0].equals("401"));

            System.out.println("-- Services per member --");
            r = get(port, "/api/me", amina);
            checkTrue("members see everything to start with", r[1].contains("\"services\":[\"home\",\"wall\"") && r[1].contains("\"vistr\""));
            checkTrue("members cannot change services", call(port, "/api/admin/services/template", "keys=home", amina)[0].equals("403"));
            r = call(port, "/api/admin/services/template", "keys=home,radio,world,market", admin);
            checkTrue("the template", r[0].equals("200") && get(port, "/api/me", amina)[1].contains("\"services\":[\"home\",\"radio\",\"world\",\"market\"]"));
            r = call(port, "/api/admin/services/user", "username=amina&keys=home,chat,vistr,nonsense", admin);
            checkTrue("one member's own ticks (unknown ones ignored)", r[0].equals("200") && get(port, "/api/me", amina)[1].contains("\"services\":[\"home\",\"chat\",\"vistr\"]"));
            checkTrue("the others keep the template", call(port, "/api/login", "username=baraka&password=password123", null)[1].contains("\"services\":[\"home\",\"radio\",\"world\",\"market\"]"));
            checkTrue("the super-admin always sees everything", get(port, "/api/me", admin)[1].contains("\"vistr\"") && get(port, "/api/me", admin)[1].contains("\"live\""));
            r = get(port, "/api/admin/services?q=ami", admin);
            checkTrue("the admin list", r[0].equals("200") && r[1].contains("\"username\":\"amina\",\"role\":\"CHATTER\",\"own\":true") && r[1].contains("\"catalog\":["));
            call(port, "/api/admin/services/user", "username=amina&reset=1", admin);
            checkTrue("back to the template", get(port, "/api/me", amina)[1].contains("\"services\":[\"home\",\"radio\",\"world\",\"market\"]"));
            ServiceAccess s3 = new ServiceAccess(dir.resolve("services.txt"), us, sessions);
            s3.init();
            checkTrue("kept across a restart", s3.template().toString().equals("[home, radio, world, market]"));
        } finally {
            http.stop(0);
        }
        System.out.println(failures == 0 ? "ALL CHECKS PASSED" : failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }

    static String tok(String json) {
        return json.replaceAll(".*\"token\":\"([0-9a-f]+)\".*", "$1");
    }

    private static String enc(String s) throws Exception {
        return URLEncoder.encode(s, "UTF-8");
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