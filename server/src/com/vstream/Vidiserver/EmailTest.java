package com.vstream.Vidiserver;

import com.sun.net.httpserver.HttpServer;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Email verification (with a pretend mail server) and the test people ON/OFF switch. */
public final class EmailTest {

    private static int failures = 0;
    private static final List<String> mails = Collections.synchronizedList(new ArrayList<String>());

    public static void main(String[] args) throws Exception {
        ServerSocket smtp = new ServerSocket(0);
        Thread t = new Thread(() -> fakeSmtp(smtp));
        t.setDaemon(true);
        t.start();
        Mailer mailer = new Mailer("127.0.0.1", smtp.getLocalPort(), "no-reply@memoryq.com", "Memoryq", "support@memoryq.com", false);

        System.out.println("-- Mailer --");
        checkTrue("good address", Mailer.validAddress("jo.smith+radio@gmail.com"));
        checkTrue("address with a line break is refused", !Mailer.validAddress("a@b.com\r\nBcc: x@y.com"));
        checkTrue("address without a dot is refused", !Mailer.validAddress("a@localhost"));
        checkTrue("mask", EmailService.mask("mkarimum@gmail.com").equals("mk******@gmail.com"));
        checkTrue("mail is handed over", mailer.send("jo@example.com", "Hello \u00e9", "Line one\n.line two\n") == null);
        String m = lastMail();
        checkTrue("headers", m.contains("From: Memoryq <no-reply@memoryq.com>") && m.contains("To: <jo@example.com>")
                && m.contains("Subject: =?UTF-8?B?") && m.contains("Reply-To: <support@memoryq.com>"));
        checkTrue("body survives", body(m).contains(".line two"));
        Mailer dead = new Mailer("127.0.0.1", 1, "no-reply@memoryq.com", "Memoryq", "", false);
        checkTrue("no mail server gives an error, not a crash", dead.send("jo@example.com", "x", "y") != null);

        System.out.println("-- Sign-up with a code --");
        Path dir = Files.createTempDirectory("memoryq-email");
        UserStore users = new UserStore(dir.resolve("users.txt"), new LiveChannelRegistry(), c -> { });
        users.init(dir.resolve("none.txt"));
        long[] now = {1000000L};
        EmailService es = new EmailService(dir.resolve("emails.txt"), users, mailer, () -> now[0]);
        es.init();
        SessionStore sessions = new SessionStore();
        AccountApi api = new AccountApi(users, sessions, new Limiter(), new LiveChannelRegistry(),
                dir.resolve("admin.html"), c -> { }, c -> false);
        api.setEmail(es, true);
        HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        api.register(http);
        http.start();
        int port = http.getAddress().getPort();

        String[] r = post(port, "/api/register", "username=newbie&password=password123&type=chatter");
        check("an account without an email is refused", r[0], "400");
        r = post(port, "/api/register", "username=newbie&password=password123&email=bad-address");
        check("a bad address is refused", r[0], "400");
        r = post(port, "/api/register", "username=newbie&password=short&email=newbie%40example.com");
        check("a short password is refused before any email", r[0], "400");
        int before = mails.size();
        r = post(port, "/api/register", "username=newbie&password=password123&email=Newbie%40Example.com");
        check("sign-up waits for the code", r[0], "200");
        checkTrue("answer says verify", r[1].contains("\"verify\":true") && r[1].contains("ne****@example.com"));
        checkTrue("one email was sent", mails.size() == before + 1);
        checkTrue("the account does not exist yet", users.info("newbie") == null);
        String code = code(lastMail());
        checkTrue("the email has a 6-digit code", code.matches("\\d{6}"));
        r = post(port, "/api/register/resend", "username=newbie");
        check("resending at once is slowed down", r[0], "429");
        r = post(port, "/api/register/verify", "username=newbie&code=000000" .replace("000000", code.equals("000000") ? "111111" : "000000"));
        check("a wrong code is refused", r[0], "400");
        r = post(port, "/api/register", "username=newbie&password=password123&email=other%40example.com");
        check("someone else cannot take the username while it waits", r[0], "409");
        r = post(port, "/api/register/verify", "username=newbie&code=" + code);
        check("the right code creates the account", r[0], "200");
        checkTrue("and signs in", r[1].contains("\"token\""));
        checkTrue("the account exists", users.info("newbie") != null);
        checkTrue("the address is saved as verified", es.get("newbie") != null && es.get("newbie").verified
                && es.get("newbie").email.equals("newbie@example.com"));
        String tok = value(r[1], "token");
        r = post(port, "/api/register", "username=second&password=password123&email=newbie%40example.com");
        check("one address, one account", r[0], "400");
        r = post(port, "/api/register", "username=guesty&password=password123&type=guest");
        check("guests still need no email", r[0], "200");

        System.out.println("-- Expiry --");
        r = post(port, "/api/register", "username=slowpoke&password=password123&email=slow%40example.com");
        check("sign-up started", r[0], "200");
        String slowCode = code(lastMail());
        now[0] += EmailService.CODE_MS + 1000;
        r = post(port, "/api/register/verify", "username=slowpoke&code=" + slowCode);
        check("an old code has expired", r[0], "404");

        System.out.println("-- Existing accounts add an email --");
        users.createUser("oldtimer", "password123", UserStore.Role.CHATTER);
        String oldTok = sessions.create("oldtimer", UserStore.Role.CHATTER);
        r = get(port, "/api/email", oldTok);
        checkTrue("no email yet", r[0].equals("200") && r[1].contains("\"verified\":false") && r[1].contains("\"needed\":true"));
        r = postAuth(port, "/api/email/start", "email=newbie%40example.com", oldTok);
        check("cannot use someone else's address", r[0], "400");
        r = postAuth(port, "/api/email/start", "email=old%40example.com", oldTok);
        checkTrue("code sent", r[0].equals("200") && r[1].contains("ol*@example.com"));
        r = postAuth(port, "/api/email/confirm", "code=" + code(lastMail()), oldTok);
        checkTrue("confirmed", r[0].equals("200") && r[1].contains("\"verified\":true") && r[1].contains("old@example.com"));
        r = get(port, "/api/email", tok);
        checkTrue("the new account shows its address", r[1].contains("newbie@example.com"));

        System.out.println("-- Saved and loaded --");
        es.flushIfDirty();
        EmailService es2 = new EmailService(dir.resolve("emails.txt"), users, mailer, () -> now[0]);
        es2.init();
        checkTrue("addresses survive a restart", es2.get("oldtimer") != null && es2.get("oldtimer").verified
                && es2.verifiedCount() == 2);
        http.stop(0);

        System.out.println("-- Test people ON/OFF --");
        ProfileStore profiles = new ProfileStore(dir.resolve("profiles.txt"), dir.resolve("photos"));
        profiles.init();
        ProfilerService profiler = new ProfilerService(profiles, users, () -> now[0], new Random(7), 4, 10, 3600000L);
        profiler.tick();
        int n = profiles.testCount();
        checkTrue("the profiler made test people", n == 4);
        String someone = profiles.testPeople().get(0).username;
        String pw = profiles.testPeople().get(0).testPassword;
        checkTrue("a test person can sign in", users.authenticate(someone, pw) != null);
        List<String> signedOut = new ArrayList<String>();
        int changed = profiler.setTestPeopleOn(false, signedOut::add);
        checkTrue("OFF disables every test account", changed == n && users.info(someone).disabled);
        checkTrue("OFF signs them out", signedOut.size() == n);
        checkTrue("OFF: they cannot sign in", users.authenticate(someone, pw) == null);
        checkTrue("OFF: nothing is deleted", profiles.testCount() == n && users.info(someone) != null);
        now[0] += 10 * 3600000L;
        checkTrue("OFF: no new people are made", profiler.tick() == 0 && profiles.testCount() == n);
        checkTrue("OFF: no one can be added by hand", profiler.createBatch(1) == 0);
        profiles.flushIfDirty();
        ProfileStore reload = new ProfileStore(dir.resolve("profiles.txt"), dir.resolve("photos"));
        reload.init();
        checkTrue("OFF is remembered after a restart", reload.testSuspended() && !reload.profilerOn());
        changed = profiler.setTestPeopleOn(true, null);
        checkTrue("ON brings them back", changed == n && !users.info(someone).disabled
                && users.authenticate(someone, pw) != null);
        checkTrue("ON: the profiler carries on", profiles.profilerOn() && !profiles.testSuspended());

        System.out.println();
        if (failures == 0) {
            System.out.println("ALL CHECKS PASSED");
        } else {
            System.out.println(failures + " CHECK(S) FAILED");
            System.exit(1);
        }
        System.exit(0);
    }

    // ---- Pretend mail server: records every message ----

    private static void fakeSmtp(ServerSocket server) {
        while (true) {
            try (Socket s = server.accept()) {
                BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.US_ASCII));
                OutputStream out = s.getOutputStream();
                say(out, "220 fake ESMTP");
                String line;
                StringBuilder data = null;
                while ((line = in.readLine()) != null) {
                    if (data != null) {
                        if (line.equals(".")) {
                            mails.add(data.toString());
                            data = null;
                            say(out, "250 queued");
                        } else {
                            data.append(line).append('\n');
                        }
                        continue;
                    }
                    if (line.startsWith("EHLO")) {
                        say(out, "250-fake\r\n250 8BITMIME");
                    } else if (line.equals("DATA")) {
                        data = new StringBuilder();
                        say(out, "354 go");
                    } else if (line.equals("QUIT")) {
                        say(out, "221 bye");
                        break;
                    } else {
                        say(out, "250 ok");
                    }
                }
            } catch (Exception e) {
                if (server.isClosed()) {
                    return;
                }
            }
        }
    }

    private static void say(OutputStream out, String s) throws Exception {
        out.write((s + "\r\n").getBytes(StandardCharsets.US_ASCII));
        out.flush();
    }

    private static String lastMail() {
        return mails.isEmpty() ? "" : mails.get(mails.size() - 1);
    }

    private static String body(String mail) {
        int i = mail.indexOf("\n\n");
        return new String(Base64.getMimeDecoder().decode(mail.substring(i + 2)), StandardCharsets.UTF_8);
    }

    private static String code(String mail) {
        Matcher m = Pattern.compile("\\n    (\\d{6})\\n").matcher(body(mail).replace("\r\n", "\n"));
        return m.find() ? m.group(1) : "";
    }

    // ---- HTTP ----

    private static String[] post(int port, String path, String body) throws Exception {
        return http(port, "POST", path, body, null);
    }

    private static String[] postAuth(int port, String path, String body, String token) throws Exception {
        return http(port, "POST", path, body, token);
    }

    private static String[] get(int port, String path, String token) throws Exception {
        return http(port, "GET", path, null, token);
    }

    private static String[] http(int port, String method, String path, String body, String token) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:" + port + path).openConnection();
        c.setRequestMethod(method);
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
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                b.write(buf, 0, n);
            }
        }
        return new String[]{String.valueOf(code), new String(b.toByteArray(), StandardCharsets.UTF_8)};
    }

    private static String value(String json, String name) {
        Matcher m = Pattern.compile("\"" + name + "\":\"([^\"]*)\"").matcher(json);
        return m.find() ? m.group(1) : "";
    }

    static String enc(String s) throws Exception {
        return URLEncoder.encode(s, "UTF-8");
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