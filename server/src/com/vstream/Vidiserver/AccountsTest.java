package com.vstream.Vidiserver;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Tests accounts, sessions, key handling and the HTTP API. No MediaMTX or phone needed. */
public class AccountsTest {

    private interface Thrower {
        void run() throws Exception;
    }

    private static int failures = 0;
    private static final List<String> kicks = Collections.synchronizedList(new ArrayList<String>());

    public static void main(String[] args) throws Exception {
        System.out.println();
        System.out.println("-- First start and passwords --");
        Path dir = Files.createTempDirectory("vstream-accounts");
        Path usersFile = dir.resolve("users.txt");
        Path legacy = dir.resolve("streamkeys.txt");
        LiveChannelRegistry reg1 = new LiveChannelRegistry();
        UserStore us1 = new UserStore(usersFile, reg1, kicks::add);
        String adminPw = us1.init(legacy);
        checkTrue("first start makes an admin password", adminPw != null && adminPw.length() >= 12);
        checkTrue("admin can sign in", us1.authenticate("admin", adminPw) != null);
        checkTrue("the username is not case sensitive", us1.authenticate("ADMIN", adminPw) != null);
        checkTrue("a wrong password is refused", us1.authenticate("admin", adminPw + "x") == null);
        checkTrue("an unknown user is refused", us1.authenticate("nobody", "whatever1") == null);
        String fileText = new String(Files.readAllBytes(usersFile), StandardCharsets.UTF_8);
        checkTrue("the password is not stored in the file", !fileText.contains(adminPw));

        System.out.println();
        System.out.println("-- Creating accounts --");
        us1.createUser("alice", "password123", UserStore.Role.BROADCASTER);
        String keyA = us1.getStreamKey("alice");
        checkTrue("a broadcaster gets CHANNEL-001 and a 48-character key",
                keyA != null && keyA.matches("CHANNEL-001\\?token=[0-9a-f]{48}"));
        String tokA = keyA.substring(keyA.indexOf('=') + 1);
        checkTrue("the channel is in the registry with that key",
                reg1.getChannel("CHANNEL-001") != null
                        && tokA.equals(reg1.getChannel("CHANNEL-001").getPublishToken()));
        checkTrue("MediaMTX would allow publishing with the key",
                MediaAuthHttpServer.denyReason(reg1, "publish", "live/CHANNEL-001", "token=" + tokA, "127.0.0.1") == null);
        us1.createUser("carol", "password123", UserStore.Role.CHATTER);
        checkTrue("a chatter has no channel and no key",
                us1.info("carol").channelId == null && us1.getStreamKey("carol") == null);
        expectError("a 2-letter username", () -> us1.createUser("ab", "password123", UserStore.Role.CHATTER));
        expectError("a username with spaces", () -> us1.createUser("bad name", "password123", UserStore.Role.CHATTER));
        expectError("a short password", () -> us1.createUser("dave", "short", UserStore.Role.CHATTER));
        expectError("a taken username in other letters", () -> us1.createUser("ALICE", "password123", UserStore.Role.CHATTER));
        expectError("creating another admin", () -> us1.createUser("root", "password123", UserStore.Role.ADMIN));

        System.out.println();
        System.out.println("-- Disable, enable and rotate --");
        kicks.clear();
        us1.setDisabled("alice", true);
        checkTrue("a disabled user cannot sign in", us1.authenticate("alice", "password123") == null);
        checkTrue("disabling removes the channel", reg1.getChannel("CHANNEL-001") == null);
        checkTrue("disabling kicks the publisher", kicks.contains("CHANNEL-001"));
        checkTrue("publishing is refused while disabled",
                MediaAuthHttpServer.denyReason(reg1, "publish", "live/CHANNEL-001", "token=" + tokA, "127.0.0.1") != null);
        us1.setDisabled("alice", false);
        checkTrue("enabling brings back the same key",
                keyA.equals(us1.getStreamKey("alice")) && reg1.getChannel("CHANNEL-001") != null
                        && tokA.equals(reg1.getChannel("CHANNEL-001").getPublishToken()));
        expectError("disabling the admin", () -> us1.setDisabled("admin", true));
        kicks.clear();
        String keyA2 = us1.rotateKey("alice");
        String tokA2 = keyA2.substring(keyA2.indexOf('=') + 1);
        checkTrue("rotating gives a different key on the same channel",
                !keyA2.equals(keyA) && keyA2.startsWith("CHANNEL-001?token="));
        checkTrue("the old key stops working",
                MediaAuthHttpServer.denyReason(reg1, "publish", "live/CHANNEL-001", "token=" + tokA, "127.0.0.1") != null);
        checkTrue("the new key works",
                MediaAuthHttpServer.denyReason(reg1, "publish", "live/CHANNEL-001", "token=" + tokA2, "127.0.0.1") == null);
        checkTrue("rotating kicks the current publisher", kicks.contains("CHANNEL-001"));
        expectError("rotating a chatter's key", () -> us1.rotateKey("carol"));

        System.out.println();
        System.out.println("-- Restart keeps everything --");
        LiveChannelRegistry reg2 = new LiveChannelRegistry();
        UserStore us2 = new UserStore(usersFile, reg2, kicks::add);
        checkTrue("a restart makes no new admin password", us2.init(legacy) == null);
        checkTrue("the key survives a restart", keyA2.equals(us2.getStreamKey("alice")));
        checkTrue("the channel is registered again with the same key",
                reg2.getChannel("CHANNEL-001") != null && tokA2.equals(reg2.getChannel("CHANNEL-001").getPublishToken()));
        checkTrue("the password survives a restart", us2.authenticate("alice", "password123") != null);
        us2.createUser("bob", "password123", UserStore.Role.BROADCASTER);
        checkTrue("the next broadcaster gets the next channel", us2.getStreamKey("bob").startsWith("CHANNEL-002?"));

        System.out.println();
        System.out.println("-- Importing the old stream keys --");
        Path dir3 = Files.createTempDirectory("vstream-accounts-legacy");
        List<String> tokens = new ArrayList<String>();
        for (int i = 0; i < 5; i++) {
            tokens.add(LiveChannelRegistry.newToken());
        }
        Files.write(dir3.resolve("streamkeys.txt"), String.join("\n", tokens).getBytes(StandardCharsets.UTF_8));
        LiveChannelRegistry reg3 = new LiveChannelRegistry();
        UserStore us3 = new UserStore(dir3.resolve("users.txt"), reg3, kicks::add);
        checkTrue("the import still makes an admin password", us3.init(dir3.resolve("streamkeys.txt")) != null);
        boolean same = true;
        for (int i = 1; i <= 5; i++) {
            same &= us3.getStreamKey("broadcaster" + i).equals("CHANNEL-00" + i + "?token=" + tokens.get(i - 1));
        }
        checkTrue("the five old keys keep their channels", same);
        checkTrue("imported accounts have no password yet",
                !us3.info("broadcaster1").hasPassword && us3.authenticate("broadcaster1", "password123") == null);
        us3.setPassword("broadcaster1", "newpassword1");
        checkTrue("a password can be set later", us3.authenticate("broadcaster1", "newpassword1") != null);
        us3.createUser("frank", "password123", UserStore.Role.BROADCASTER);
        checkTrue("a new broadcaster after the import gets CHANNEL-006", us3.getStreamKey("frank").startsWith("CHANNEL-006?"));

        System.out.println();
        System.out.println("-- HTTP API --");
        Path dir4 = Files.createTempDirectory("vstream-accounts-http");
        Path page = dir4.resolve("admin.html");
        Files.write(page, "<html>admin-page-test</html>".getBytes(StandardCharsets.UTF_8));
        LiveChannelRegistry reg4 = new LiveChannelRegistry();
        UserStore us4 = new UserStore(dir4.resolve("users.txt"), reg4, kicks::add);
        String pw4 = us4.init(null);
        ChannelListHttpServer server = new ChannelListHttpServer(reg4, 0);
        server.setAccountApi(new AccountApi(us4, new SessionStore(), new Limiter(), reg4, page,
                kicks::add, id -> true));
        server.start();
        int port = server.getPort();

        String[] r = http(port, "GET", "/api/login", null, null);
        check("GET /api/login is not allowed", r[0], "405");
        r = http(port, "POST", "/api/login", form("username", "admin", "password", "wrong-password"), null);
        check("wrong password", r[0], "401");
        r = http(port, "POST", "/api/login", form("username", "admin"), null);
        check("missing password", r[0], "400");
        r = http(port, "POST", "/api/login", form("username", "admin", "password", pw4), null);
        check("admin sign-in", r[0], "200");
        checkTrue("the answer names the admin role", r[1].contains("\"role\":\"ADMIN\""));
        String adminTok = token(r[1]);
        checkTrue("a session token is returned", adminTok != null);

        for (int i = 0; i < 5; i++) {
            r = http(port, "POST", "/api/login", form("username", "mallory", "password", "guess-" + i), null);
        }
        r = http(port, "POST", "/api/login", form("username", "mallory", "password", "guess-6"), null);
        check("five wrong guesses lock the name for a minute", r[0], "429");
        r = http(port, "POST", "/api/login", form("username", "admin", "password", pw4), null);
        check("the lock does not affect other names", r[0], "200");

        r = http(port, "POST", "/api/register", form("username", "carol2", "password", "password123"), null);
        check("a chatter can register", r[0], "200");
        checkTrue("a new chatter has the chatter role", r[1].contains("\"role\":\"CHATTER\""));
        String chatTok = token(r[1]);
        r = http(port, "POST", "/api/register", form("username", "CAROL2", "password", "password123"), null);
        check("registering a taken name", r[0], "400");
        r = http(port, "POST", "/api/register", form("username", "x", "password", "password123"), null);
        check("registering a bad name", r[0], "400");
        r = http(port, "POST", "/api/register", form("username", "okname", "password", "short"), null);
        check("registering with a short password", r[0], "400");

        r = http(port, "GET", "/api/me", null, chatTok);
        check("/api/me with a session", r[0], "200");
        checkTrue("/api/me names the user", r[1].contains("\"username\":\"carol2\""));
        r = http(port, "GET", "/api/me", null, null);
        check("/api/me without a session", r[0], "401");
        r = http(port, "GET", "/api/me", null, "not-a-real-token");
        check("/api/me with a made-up session", r[0], "401");

        r = http(port, "GET", "/api/admin/users", null, null);
        check("admin list without a session", r[0], "401");
        r = http(port, "GET", "/api/admin/users", null, chatTok);
        check("admin list as a chatter", r[0], "403");
        r = http(port, "GET", "/api/admin/users", null, adminTok);
        check("admin list as the admin", r[0], "200");
        checkTrue("the list shows the users", r[1].contains("\"admin\"") && r[1].contains("\"carol2\""));
        checkTrue("the list has no keys, tokens, hashes or passwords",
                !r[1].contains("streamKey") && !r[1].contains("token") && !r[1].toLowerCase().contains("hash")
                        && !r[1].toLowerCase().contains("salt") && !r[1].contains(pw4));

        r = http(port, "POST", "/api/admin/users/create", form("username", "dave", "password", "password123", "role", "BROADCASTER"), adminTok);
        check("admin creates a broadcaster", r[0], "200");
        checkTrue("the new broadcaster has a channel", r[1].contains("\"channelId\":\"CHANNEL-001\""));
        r = http(port, "POST", "/api/admin/users/key", form("username", "dave"), adminTok);
        check("admin reads a key", r[0], "200");
        String key1 = value(r[1], "streamKey");
        checkTrue("the key looks right", key1 != null && key1.matches("CHANNEL-001\\?token=[0-9a-f]{48}"));
        r = http(port, "POST", "/api/admin/users/key", form("username", "carol2"), adminTok);
        check("a chatter has no key", r[0], "404");
        kicks.clear();
        r = http(port, "POST", "/api/admin/users/rotate", form("username", "dave"), adminTok);
        check("admin rotates a key", r[0], "200");
        checkTrue("the rotated key is different", !key1.equals(value(r[1], "streamKey")));
        checkTrue("rotating kicks the publisher", kicks.contains("CHANNEL-001"));
        kicks.clear();
        r = http(port, "POST", "/api/admin/kick", form("channel", "CHANNEL-001"), adminTok);
        check("admin kicks a channel", r[0], "200");
        checkTrue("the kick was passed on", kicks.contains("CHANNEL-001"));
        r = http(port, "POST", "/api/admin/kick", form("channel", "foo"), adminTok);
        check("kicking a bad channel name", r[0], "400");

        r = http(port, "POST", "/api/admin/users/disable", form("username", "carol2", "disabled", "true"), adminTok);
        check("admin disables a user", r[0], "200");
        r = http(port, "GET", "/api/me", null, chatTok);
        check("a disabled user's session stops working", r[0], "401");
        r = http(port, "POST", "/api/login", form("username", "carol2", "password", "password123"), null);
        check("a disabled user cannot sign in", r[0], "401");
        r = http(port, "POST", "/api/admin/users/disable", form("username", "carol2", "disabled", "false"), adminTok);
        check("admin enables the user again", r[0], "200");
        r = http(port, "POST", "/api/admin/users/password", form("username", "carol2", "password", "brandnew123"), adminTok);
        check("admin sets a password", r[0], "200");
        r = http(port, "POST", "/api/login", form("username", "carol2", "password", "brandnew123"), null);
        check("the new password works", r[0], "200");
        String chatTok2 = token(r[1]);

        r = http(port, "POST", "/api/password", form("old", "wrong-one", "new", "another-pass1"), chatTok2);
        check("changing a password with the wrong old one", r[0], "401");
        r = http(port, "POST", "/api/password", form("old", "brandnew123", "new", "another-pass1"), chatTok2);
        check("changing a password", r[0], "200");
        r = http(port, "POST", "/api/login", form("username", "carol2", "password", "another-pass1"), null);
        check("signing in with the changed password", r[0], "200");

        r = http(port, "POST", "/api/logout", "", chatTok2);
        check("sign out", r[0], "200");
        r = http(port, "GET", "/api/me", null, chatTok2);
        check("a signed-out session stops working", r[0], "401");

        r = http(port, "GET", "/admin", null, null);
        check("the admin page is served", r[0], "200");
        checkTrue("the admin page content arrives", r[1].contains("admin-page-test"));
        r = http(port, "POST", "/admin", "", null);
        check("POST /admin is not allowed", r[0], "405");
        r = http(port, "GET", "/me", null, null);
        check("without user.html the user page says so", r[0], "404");
        Files.write(dir4.resolve("user.html"), "<html>user-page-test</html>".getBytes(StandardCharsets.UTF_8));
        r = http(port, "GET", "/me", null, null);
        check("the user dashboard is served", r[0], "200");
        checkTrue("the user dashboard content arrives", r[1].contains("user-page-test"));
        r = http(port, "GET", "/", null, null);
        checkTrue("the bare address opens the user dashboard", r[1].contains("user-page-test"));
        r = http(port, "GET", "/media", null, null);
        check("other addresses are not the dashboard", r[0], "404");
        r = http(port, "GET", "/channels", null, null);
        check("the channel list still works", r[0], "200");

        server.stop();
        System.out.println();
        System.out.println(failures == 0 ? "ALL CHECKS PASSED" : failures + " CHECK(S) FAILED");
        if (failures > 0) {
            System.exit(1);
        }
    }

    private static String[] http(int port, String method, String path, String body, String bearer) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:" + port + path).openConnection();
        c.setRequestMethod(method);
        if (bearer != null) {
            c.setRequestProperty("Authorization", "Bearer " + bearer);
        }
        if (body != null) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            OutputStream os = c.getOutputStream();
            os.write(body.getBytes(StandardCharsets.UTF_8));
            os.close();
        }
        int code = c.getResponseCode();
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        StringBuilder sb = new StringBuilder();
        if (in != null) {
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            reader.close();
        }
        c.disconnect();
        return new String[]{String.valueOf(code), sb.toString()};
    }

    private static String form(String... pairs) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            if (sb.length() > 0) {
                sb.append('&');
            }
            sb.append(URLEncoder.encode(pairs[i], "UTF-8")).append('=').append(URLEncoder.encode(pairs[i + 1], "UTF-8"));
        }
        return sb.toString();
    }

    private static String token(String json) {
        return value(json, "token");
    }

    private static String value(String json, String name) {
        Matcher m = Pattern.compile("\"" + name + "\":\"([^\"]*)\"").matcher(json);
        return m.find() ? m.group(1) : null;
    }

    private static void expectError(String name, Thrower t) {
        try {
            t.run();
            failures++;
            System.out.println("FAIL  " + name + " was accepted");
        } catch (UserStore.UserException e) {
            System.out.println("PASS  " + name + " is refused");
        } catch (Exception e) {
            failures++;
            System.out.println("FAIL  " + name + " -> unexpected " + e);
        }
    }

    private static void check(String name, String actual, String expected) {
        if (actual.equals(expected)) {
            System.out.println("PASS  " + name + " -> " + actual);
        } else {
            failures++;
            System.out.println("FAIL  " + name + " -> got " + actual + ", expected " + expected);
        }
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