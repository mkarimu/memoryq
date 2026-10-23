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
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Tests the channel pool: claiming, releasing, time to live, notices, restarts and the HTTP API. */
public class ClaimsTest {

    private interface Thrower {
        void run() throws Exception;
    }

    private static final long HOUR = 3600000L;
    private static int failures = 0;
    private static final List<String> kicks = Collections.synchronizedList(new ArrayList<String>());

    public static void main(String[] args) throws Exception {
        System.out.println();
        System.out.println("-- The pool --");
        AtomicLong clock = new AtomicLong(1000000000L);
        Path dir = Files.createTempDirectory("vstream-claims");
        Path file = dir.resolve("claims.txt");
        LiveChannelRegistry reg = new LiveChannelRegistry();
        ClaimStore cs = new ClaimStore(file, reg, kicks::add, 48 * HOUR, 12 * HOUR, clock::get);
        cs.init(101, 5);
        checkTrue("the pool has 5 channels", cs.pool().size() == 5);
        boolean allRegistered = true;
        for (ClaimStore.PoolEntry p : cs.pool()) {
            allRegistered &= reg.getChannel(p.channelId) != null && p.holder == null;
        }
        checkTrue("every pool channel is registered and free", allRegistered);
        checkTrue("publishing to a free channel with a guessed key is refused",
                deny(reg, "CHANNEL-101", LiveChannelRegistry.newToken()) != null);
        checkTrue("publishing to a free channel without a key is refused",
                MediaAuthHttpServer.denyReason(reg, "publish", "live/CHANNEL-101", "", "127.0.0.1") != null);

        System.out.println();
        System.out.println("-- Claiming --");
        ClaimStore.Claim a = cs.claim("alice", "CHANNEL-102");
        String keyA = a.streamKey();
        String tokA = keyA.substring(keyA.indexOf('=') + 1);
        checkTrue("the key has the channel and a 48-character token", keyA.matches("CHANNEL-102\\?token=[0-9a-f]{48}"));
        checkTrue("the registry holds that token", tokA.equals(reg.getChannel("CHANNEL-102").getPublishToken()));
        checkTrue("the channel names its holder", "alice".equals(reg.getChannel("CHANNEL-102").getBroadcasterId()));
        checkTrue("publishing with the key is allowed", deny(reg, "CHANNEL-102", tokA) == null);
        checkTrue("alice's claim is found (any letter case)", cs.claimOf("ALICE") != null);
        expectClaimError("a second claim by the same user", () -> cs.claim("alice", "CHANNEL-103"));
        expectClaimError("taking a channel somebody holds", () -> cs.claim("bob", "CHANNEL-102"));
        expectClaimError("a channel outside the pool", () -> cs.claim("bob", "CHANNEL-001"));
        expectClaimError("a pool-looking channel that does not exist", () -> cs.claim("bob", "CHANNEL-106"));

        System.out.println();
        System.out.println("-- Releasing --");
        kicks.clear();
        cs.release("alice");
        checkTrue("releasing kicks the publisher", kicks.contains("CHANNEL-102"));
        checkTrue("the old key stops working", deny(reg, "CHANNEL-102", tokA) != null);
        checkTrue("the channel is free again", holderOf(cs, "CHANNEL-102") == null);
        checkTrue("a voluntary release leaves no notice", cs.noticeFor("alice") == null);
        expectClaimError("releasing with no channel", () -> cs.release("alice"));
        ClaimStore.Claim b = cs.claim("bob", "CHANNEL-102");
        checkTrue("the next holder gets a different key", !b.streamKey().equals(keyA));
        cs.release("bob");

        System.out.println();
        System.out.println("-- Time to live (48 hours) --");
        cs.claim("bob", "CHANNEL-103");
        clock.addAndGet(47 * HOUR);
        cs.tick(id -> false);
        checkTrue("a channel unused for 47 hours is kept", holderOf(cs, "CHANNEL-103") != null);
        kicks.clear();
        clock.addAndGet(2 * HOUR);
        cs.tick(id -> false);
        checkTrue("a channel unused for 49 hours is released", holderOf(cs, "CHANNEL-103") == null);
        checkTrue("the release kicks the publisher", kicks.contains("CHANNEL-103"));
        checkTrue("the holder gets a notice naming the channel",
                cs.noticeFor("bob") != null && cs.noticeFor("bob").contains("CHANNEL-103"));
        cs.ackNotice("bob");
        checkTrue("the notice goes away when acknowledged", cs.noticeFor("bob") == null);

        cs.claim("carol", "CHANNEL-104");
        clock.addAndGet(40 * HOUR);
        cs.tick(id -> true);
        clock.addAndGet(40 * HOUR);
        cs.tick(id -> true);
        clock.addAndGet(10 * HOUR);
        cs.tick(id -> false);
        checkTrue("a channel that was live recently is kept", holderOf(cs, "CHANNEL-104") != null);
        clock.addAndGet(40 * HOUR);
        cs.tick(id -> false);
        checkTrue("it is released 48 hours after the last live moment", holderOf(cs, "CHANNEL-104") == null);

        cs.claim("dave", "CHANNEL-105");
        clock.addAndGet(47 * HOUR);
        cs.touch("CHANNEL-105");
        cs.touch("CHANNEL-101");
        clock.addAndGet(47 * HOUR);
        cs.tick(id -> false);
        checkTrue("publishing restarts the clock", holderOf(cs, "CHANNEL-105") != null);
        long gap = -1;
        for (ClaimStore.PoolEntry p : cs.pool()) {
            if (p.channelId.equals("CHANNEL-105")) {
                gap = p.expiresAt - p.lastUsed;
            }
        }
        checkTrue("the expiry time is 48 hours after the last use", gap == 48 * HOUR);

        System.out.println();
        System.out.println("-- Admin and account actions --");
        cs.claim("erin", "CHANNEL-101");
        cs.releaseByUser("erin", "Your account\nwas disabled\tby the admin.");
        checkTrue("releasing by user frees the channel", holderOf(cs, "CHANNEL-101") == null);
        checkTrue("the user is told why", cs.noticeFor("erin") != null);
        cs.claim("frank", "CHANNEL-101");
        checkTrue("the admin can take a claimed channel back", cs.releaseChannel("CHANNEL-101", "Taken back by the admin."));
        checkTrue("taking back a free channel reports false", !cs.releaseChannel("CHANNEL-101", "x"));

        System.out.println();
        System.out.println("-- Restart keeps everything --");
        cs.tick(id -> false);
        LiveChannelRegistry reg2 = new LiveChannelRegistry();
        ClaimStore cs2 = new ClaimStore(file, reg2, kicks::add, 48 * HOUR, 12 * HOUR, clock::get);
        cs2.init(101, 5);
        ClaimStore.Claim d2 = cs2.claimOf("dave");
        checkTrue("a claim survives a restart", d2 != null && d2.channelId.equals("CHANNEL-105"));
        checkTrue("with the same key", d2 != null && d2.streamKey().equals(cs.claimOf("dave").streamKey()));
        checkTrue("and the registry holds that key again",
                d2 != null && d2.streamKey().endsWith(reg2.getChannel("CHANNEL-105").getPublishToken()));
        checkTrue("a notice survives a restart", cs2.noticeFor("carol") != null);
        String erinNotice = cs2.noticeFor("erin");
        checkTrue("tabs and new lines in a notice are cleaned up",
                erinNotice != null && !erinNotice.contains("\n") && !erinNotice.contains("\t"));
        LiveChannelRegistry reg3 = new LiveChannelRegistry();
        ClaimStore cs3 = new ClaimStore(file, reg3, kicks::add, 48 * HOUR, 12 * HOUR, clock::get);
        cs3.init(101, 4);
        checkTrue("a claim on a channel that left the pool is dropped", cs3.claimOf("dave") == null);
        checkTrue("and its holder is told", cs3.noticeFor("dave") != null);

        System.out.println();
        System.out.println("-- HTTP API --");
        Path dir5 = Files.createTempDirectory("vstream-claims-http");
        Path page = dir5.resolve("admin.html");
        Files.write(page, "<html>x</html>".getBytes(StandardCharsets.UTF_8));
        AtomicLong clock5 = new AtomicLong(5000000000L);
        LiveChannelRegistry reg5 = new LiveChannelRegistry();
        UserStore us5 = new UserStore(dir5.resolve("users.txt"), reg5, kicks::add);
        String pw5 = us5.init(null);
        us5.createUser("alice", "password123", UserStore.Role.CHATTER);
        us5.createUser("bob", "password123", UserStore.Role.CHATTER);
        ClaimStore cs5 = new ClaimStore(dir5.resolve("claims.txt"), reg5, kicks::add, 48 * HOUR, 12 * HOUR, clock5::get);
        cs5.init(101, 5);
        AccountApi api = new AccountApi(us5, new SessionStore(), new Limiter(), reg5, page, kicks::add, id -> false);
        api.setClaims(cs5);
        ChannelListHttpServer server = new ChannelListHttpServer(reg5, 0);
        server.setAccountApi(api);
        server.start();
        int port = server.getPort();

        String adminTok = value(http(port, "POST", "/api/login", form("username", "admin", "password", pw5), null)[1], "token");
        String aliceTok = value(http(port, "POST", "/api/login", form("username", "alice", "password", "password123"), null)[1], "token");
        String bobTok = value(http(port, "POST", "/api/login", form("username", "bob", "password", "password123"), null)[1], "token");

        String[] r = http(port, "GET", "/api/pool", null, null);
        check("the pool list needs a sign-in", r[0], "401");
        r = http(port, "GET", "/api/pool", null, aliceTok);
        check("the pool list for a signed-in user", r[0], "200");
        checkTrue("all 5 channels are free", count(r[1], "\"status\":\"FREE\"") == 5);
        checkTrue("the pool list never shows keys or tokens", !r[1].contains("token") && !r[1].contains("streamKey"));
        r = http(port, "POST", "/api/claim", form("channel", "CHANNEL-102"), null);
        check("claiming needs a sign-in", r[0], "401");
        r = http(port, "POST", "/api/claim", form("channel", "CHANNEL-102"), adminTok);
        check("the admin cannot claim", r[0], "403");
        r = http(port, "POST", "/api/claim", form("channel", "foo"), aliceTok);
        check("claiming a bad channel name", r[0], "400");
        r = http(port, "POST", "/api/claim", form("channel", "CHANNEL-102"), aliceTok);
        check("alice claims CHANNEL-102", r[0], "200");
        String keyA1 = value(r[1], "streamKey");
        checkTrue("the answer has the key", keyA1 != null && keyA1.matches("CHANNEL-102\\?token=[0-9a-f]{48}"));
        checkTrue("48 hours left and no warning", r[1].contains("\"hoursLeft\":48") && r[1].contains("\"warning\":false"));
        r = http(port, "POST", "/api/claim", form("channel", "CHANNEL-103"), aliceTok);
        check("a second claim by alice", r[0], "409");
        r = http(port, "POST", "/api/claim", form("channel", "CHANNEL-102"), bobTok);
        check("bob cannot take alice's channel", r[0], "409");

        r = http(port, "GET", "/api/pool", null, bobTok);
        checkTrue("bob sees it as taken, without alice's name or key",
                r[1].contains("\"status\":\"TAKEN\"") && !r[1].contains("alice") && !r[1].contains(keyA1.substring(keyA1.indexOf('=') + 1))
                        && !r[1].contains("hoursLeft"));
        r = http(port, "GET", "/api/pool", null, aliceTok);
        checkTrue("alice sees it as hers, with the time left",
                r[1].contains("\"status\":\"YOURS\"") && r[1].contains("\"hoursLeft\":48"));

        r = http(port, "GET", "/api/me", null, aliceTok);
        checkTrue("/api/me shows alice's claim", r[1].contains("\"claim\":{") && keyA1.equals(value(r[1], "streamKey")));
        r = http(port, "GET", "/api/me", null, bobTok);
        checkTrue("/api/me shows no claim for bob", !r[1].contains("\"claim\""));
        r = http(port, "POST", "/api/login", form("username", "alice", "password", "password123"), null);
        checkTrue("signing in on another device returns the same channel and key", keyA1.equals(value(r[1], "streamKey")));

        System.out.println();
        System.out.println("-- Expiry, warning and notices over HTTP --");
        clock5.addAndGet(49 * HOUR);
        cs5.tick(id -> false);
        r = http(port, "GET", "/api/me", null, aliceTok);
        checkTrue("after 49 idle hours the claim is gone and a notice is shown",
                !r[1].contains("\"claim\"") && r[1].contains("\"notice\""));
        r = http(port, "POST", "/api/ack", "", aliceTok);
        check("acknowledging the notice", r[0], "200");
        r = http(port, "GET", "/api/me", null, aliceTok);
        checkTrue("the notice is gone afterwards", !r[1].contains("\"notice\""));
        r = http(port, "POST", "/api/claim", form("channel", "CHANNEL-103"), aliceTok);
        check("alice claims another channel", r[0], "200");
        clock5.addAndGet(40 * HOUR);
        r = http(port, "GET", "/api/me", null, aliceTok);
        checkTrue("8 hours left raises the warning", r[1].contains("\"hoursLeft\":8") && r[1].contains("\"warning\":true"));
        kicks.clear();
        r = http(port, "POST", "/api/release", "", aliceTok);
        check("alice releases her channel", r[0], "200");
        checkTrue("the release kicks the publisher", kicks.contains("CHANNEL-103"));
        r = http(port, "POST", "/api/release", "", aliceTok);
        check("releasing with no channel", r[0], "409");
        r = http(port, "GET", "/api/me", null, aliceTok);
        checkTrue("alice has no claim afterwards", !r[1].contains("\"claim\""));

        System.out.println();
        System.out.println("-- Admin pool --");
        http(port, "POST", "/api/claim", form("channel", "CHANNEL-104"), bobTok);
        r = http(port, "GET", "/api/admin/pool", null, aliceTok);
        check("the admin pool as a chatter", r[0], "403");
        r = http(port, "GET", "/api/admin/pool", null, adminTok);
        check("the admin pool as the admin", r[0], "200");
        checkTrue("it names the holder and the idle time", r[1].contains("\"holder\":\"bob\"") && r[1].contains("\"idleHours\":0"));
        checkTrue("it never shows keys", !r[1].contains("token") && !r[1].contains("streamKey"));
        r = http(port, "POST", "/api/admin/pool/release", form("channel", "CHANNEL-104"), adminTok);
        check("the admin takes a channel back", r[0], "200");
        r = http(port, "POST", "/api/admin/pool/release", form("channel", "CHANNEL-104"), adminTok);
        check("taking back a free channel", r[0], "404");
        r = http(port, "POST", "/api/admin/pool/release", form("channel", "foo"), adminTok);
        check("taking back a bad channel name", r[0], "400");
        r = http(port, "POST", "/api/admin/pool/release", form("channel", "CHANNEL-104"), aliceTok);
        check("a chatter cannot take channels back", r[0], "403");

        http(port, "POST", "/api/claim", form("channel", "CHANNEL-105"), bobTok);
        r = http(port, "POST", "/api/admin/users/disable", form("username", "bob", "disabled", "true"), adminTok);
        check("the admin disables bob", r[0], "200");
        r = http(port, "GET", "/api/admin/pool", null, adminTok);
        checkTrue("disabling bob frees his channel", !r[1].contains("\"holder\":\"bob\""));
        http(port, "POST", "/api/admin/users/disable", form("username", "bob", "disabled", "false"), adminTok);
        r = http(port, "POST", "/api/login", form("username", "bob", "password", "password123"), null);
        checkTrue("bob finds out why when he signs in again", r[1].contains("\"notice\"") && r[1].contains("disabled"));

        server.stop();
        System.out.println();
        System.out.println(failures == 0 ? "ALL CHECKS PASSED" : failures + " CHECK(S) FAILED");
    }

    private static String deny(LiveChannelRegistry reg, String channel, String token) {
        return MediaAuthHttpServer.denyReason(reg, "publish", "live/" + channel, "token=" + token, "127.0.0.1");
    }

    private static String holderOf(ClaimStore cs, String channel) {
        for (ClaimStore.PoolEntry p : cs.pool()) {
            if (p.channelId.equals(channel)) {
                return p.holder;
            }
        }
        return null;
    }

    private static int count(String text, String part) {
        int n = 0;
        int i = 0;
        while ((i = text.indexOf(part, i)) >= 0) {
            n++;
            i += part.length();
        }
        return n;
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

    private static String value(String json, String name) {
        Matcher m = Pattern.compile("\"" + name + "\":\"([^\"]*)\"").matcher(json);
        return m.find() ? m.group(1) : null;
    }

    private static void expectClaimError(String name, Thrower t) {
        try {
            t.run();
            failures++;
            System.out.println("FAIL  " + name + " was accepted");
        } catch (ClaimStore.ClaimException e) {
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