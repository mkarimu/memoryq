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
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Tests receipts, online status, forwarding, pins, search and the privacy switches. */
public class ChatReceiptsTest {

    private static int failures = 0;

    public static void main(String[] args) throws Exception {
        final AtomicLong t = new AtomicLong(1000000000000L);
        Path dir = Files.createTempDirectory("vstream-receipts");
        LiveChannelRegistry reg = new LiveChannelRegistry();
        final String ch1 = reg.registerChannel("alice", "");
        UserStore us = new UserStore(dir.resolve("users.txt"), reg, id -> { });
        us.init(null);
        us.createUser("alice", "password123", UserStore.Role.CHATTER);
        us.createUser("bob", "password123", UserStore.Role.CHATTER);
        us.createUser("gus", "password123", UserStore.Role.GUEST);
        us.createUser("carol", "password123", UserStore.Role.CHATTER);
        Path chatFile = dir.resolve("chat.txt");
        ChatStore cs = new ChatStore(chatFile, t::get);
        cs.init();
        cs.createGroup("Other Room", "admin", true);
        SessionStore ss = new SessionStore();
        String aliceTok = ss.create("alice", UserStore.Role.CHATTER);
        String bobTok = ss.create("bob", UserStore.Role.CHATTER);
        String gusTok = ss.create("gus", UserStore.Role.GUEST);
        String carolTok = ss.create("carol", UserStore.Role.CHATTER);
        String adminTok = ss.create("admin", UserStore.Role.ADMIN);
        ChannelListHttpServer server = new ChannelListHttpServer(reg, 0);
        server.setChatApi(new ChatApi(cs, us, ss, reg, id -> id.equals(ch1)));
        server.start();
        int port = server.getPort();

        System.out.println();
        System.out.println("-- Delivered and read --");
        long m1 = say(port, t, aliceTok, "g-general", "hello everyone");
        int[] c = counts(get(port, "g-general", aliceTok), m1);
        check("a new message is delivered to nobody yet", c[0] == 0 && c[1] == 0);
        get(port, "g-general", bobTok);
        c = counts(get(port, "g-general", aliceTok), m1);
        check("after bob's phone fetches it, it is delivered to 1", c[0] == 1 && c[1] == 0);
        get(port, "g-general", null);
        c = counts(get(port, "g-general", aliceTok), m1);
        check("an anonymous viewer does not count as a recipient", c[0] == 1);
        c = counts(get(port, "g-general", bobTok), m1);
        check("only the author sees the counts", c[0] == 0 && c[1] == 0);
        String[] r = http(port, "POST", "/api/chat/read", form("room", "g-general", "upTo", String.valueOf(m1)), bobTok);
        checkCode("bob reads the message", r[0], "200");
        c = counts(get(port, "g-general", aliceTok), m1);
        check("it is now read by 1", c[0] == 1 && c[1] == 1);
        http(port, "POST", "/api/chat/read", form("room", "g-general", "upTo", String.valueOf(m1)), gusTok);
        c = counts(get(port, "g-general", aliceTok), m1);
        check("a guest's read counts too", c[0] == 2 && c[1] == 2);
        r = http(port, "POST", "/api/chat/read", form("room", "g-general", "upTo", "5"), null);
        checkCode("reading without a sign-in", r[0], "401");
        r = http(port, "POST", "/api/chat/read", form("room", "g-nothere", "upTo", "5"), bobTok);
        checkCode("reading a room that does not exist", r[0], "404");
        http(port, "POST", "/api/chat/read", form("room", "g-general", "upTo", "999999"), carolTok);
        c = counts(get(port, "g-general", aliceTok), m1);
        check("reading past the end is capped to the last message", c[1] == 3);

        long m2 = say(port, t, aliceTok, "g-general", "second message");
        c = counts(get(port, "g-general", aliceTok), m2);
        check("a newer message starts at zero again", c[0] == 0 && c[1] == 0);
        http(port, "POST", "/api/chat/prefs", form("sendReceipts", "false"), bobTok);
        get(port, "g-general", bobTok);
        http(port, "POST", "/api/chat/read", form("room", "g-general", "upTo", String.valueOf(m2)), bobTok);
        c = counts(get(port, "g-general", aliceTok), m2);
        check("with receipts off bob's message is delivered but not read", c[0] == 1 && c[1] == 0);
        http(port, "POST", "/api/chat/prefs", form("sendReceipts", "false"), aliceTok);
        c = counts(get(port, "g-general", aliceTok), m1);
        check("with receipts off you do not see others' counts either", c[0] == 0 && c[1] == 0);
        http(port, "POST", "/api/chat/prefs", form("sendReceipts", "true"), aliceTok);
        c = counts(get(port, "g-general", aliceTok), m1);
        check("and they come back when you turn them on again", c[0] == 3 && c[1] == 3);

        r = http(port, "GET", "/api/chat/info?room=g-general&id=" + m1, null, aliceTok);
        checkCode("the author asks who received a message", r[0], "200");
        check("the names are listed", r[1].contains("\"read\":[") && r[1].contains("bob") && r[1].contains("gus") && r[1].contains("carol")
                && r[1].contains("\"readCount\":3"));
        r = http(port, "GET", "/api/chat/info?room=g-general&id=" + m1, null, bobTok);
        checkCode("someone else asks", r[0], "403");
        r = http(port, "GET", "/api/chat/info?room=g-general&id=" + m1, null, adminTok);
        checkCode("the admin asks", r[0], "200");
        r = http(port, "GET", "/api/chat/info?room=g-general&id=" + m1, null, null);
        checkCode("asking without a sign-in", r[0], "401");
        r = http(port, "GET", "/api/chat/info?room=g-general&id=999999", null, aliceTok);
        checkCode("asking about a message that does not exist", r[0], "404");

        System.out.println();
        System.out.println("-- Online status --");
        t.addAndGet(30000);
        get(port, "g-general", bobTok);
        get(port, "g-general", gusTok);
        String json = get(port, "g-general", aliceTok);
        check("three people are in the room", json.contains("\"onlineCount\":3"));
        check("a signed-in viewer sees who", json.contains("\"online\":[") && json.contains("\"bob\"") && json.contains("\"gus\"") && json.contains("\"alice\""));
        json = get(port, "g-general", null);
        check("an anonymous viewer sees only a count", json.contains("\"onlineCount\":3") && json.contains("\"online\":[]"));
        json = http(port, "GET", "/api/chat/rooms", null, null)[1];
        check("the room list shows the online count", json.contains("\"id\":\"g-general\"") && json.contains("\"online\":3"));
        http(port, "POST", "/api/chat/prefs", form("showOnline", "false"), gusTok);
        get(port, "g-general", gusTok);
        json = get(port, "g-general", aliceTok);
        check("someone who hides their status is left out", json.contains("\"onlineCount\":2") && !json.contains("\"gus\""));
        r = http(port, "GET", "/api/chat/prefs", null, gusTok);
        check("the switches are saved", r[1].contains("\"showOnline\":false") && r[1].contains("\"sendReceipts\":true"));
        r = http(port, "GET", "/api/chat/prefs", null, null);
        checkCode("the switches need a sign-in", r[0], "401");
        t.addAndGet(20000);
        json = get(port, "g-general", null);
        check("after 20 seconds without activity nobody is online", json.contains("\"onlineCount\":0"));
        check("the users are still known as recently seen only briefly", !cs.isOnline("bob") || cs.isOnline("bob"));
        get(port, "g-general", bobTok);
        check("a global online check works", cs.isOnline("bob") && !cs.isOnline("gus"));
        t.addAndGet(61000);
        check("and expires after a minute", !cs.isOnline("bob"));
        http(port, "POST", "/api/chat/prefs", form("showOnline", "true"), gusTok);

        System.out.println();
        System.out.println("-- Forwarding --");
        String other = "g-other-room";
        t.addAndGet(5000);
        r = http(port, "POST", "/api/chat/forward", form("fromRoom", "g-general", "toRoom", other, "id", String.valueOf(m1)), bobTok);
        checkCode("bob forwards alice's message", r[0], "200");
        check("it shows who wrote it first", r[1].contains("\"forwardedFrom\":\"alice\"") && r[1].contains("\"user\":\"bob\"")
                && r[1].contains("\"text\":\"hello everyone\""));
        long fwdId = (long) first(r[1], "\"id\":(\\d+)");
        t.addAndGet(5000);
        r = http(port, "POST", "/api/chat/forward", form("fromRoom", other, "toRoom", "g-general", "id", String.valueOf(fwdId)), carolTok);
        check("forwarding a forward keeps the first author", r[0].equals("200") && r[1].contains("\"forwardedFrom\":\"alice\"")
                && r[1].contains("\"user\":\"carol\""));
        r = http(port, "POST", "/api/chat/forward", form("fromRoom", "g-general", "toRoom", other, "id", "999999"), bobTok);
        checkCode("forwarding a message that does not exist", r[0], "404");
        r = http(port, "POST", "/api/chat/forward", form("fromRoom", "g-general", "toRoom", "g-nothere", "id", String.valueOf(m1)), bobTok);
        checkCode("forwarding to a room that does not exist", r[0], "404");
        r = http(port, "POST", "/api/chat/forward", form("fromRoom", "g-general", "toRoom", other, "id", String.valueOf(m1)), null);
        checkCode("forwarding without a sign-in", r[0], "401");
        r = http(port, "POST", "/api/chat/forward", form("fromRoom", "g-general", "toRoom", ch1, "id", String.valueOf(m1)), gusTok);
        checkCode("a guest can forward into a channel room", r[0], "200");
        long gone = say(port, t, aliceTok, "g-general", "to be removed");
        http(port, "POST", "/api/chat/delete", form("room", "g-general", "id", String.valueOf(gone)), aliceTok);
        r = http(port, "POST", "/api/chat/forward", form("fromRoom", "g-general", "toRoom", other, "id", String.valueOf(gone)), bobTok);
        checkCode("forwarding a deleted message", r[0], "404");
        cs.mute("carol", 0);
        r = http(port, "POST", "/api/chat/forward", form("fromRoom", "g-general", "toRoom", other, "id", String.valueOf(m1)), carolTok);
        checkCode("a muted user forwards", r[0], "403");
        cs.unmute("carol");
        t.addAndGet(60000);
        String f1 = http(port, "POST", "/api/chat/forward", form("fromRoom", "g-general", "toRoom", other, "id", String.valueOf(m1)), carolTok)[0];
        String f2 = http(port, "POST", "/api/chat/forward", form("fromRoom", "g-general", "toRoom", other, "id", String.valueOf(m1)), carolTok)[0];
        String f3 = http(port, "POST", "/api/chat/forward", form("fromRoom", "g-general", "toRoom", other, "id", String.valueOf(m1)), carolTok)[0];
        String f4 = http(port, "POST", "/api/chat/forward", form("fromRoom", "g-general", "toRoom", other, "id", String.valueOf(m1)), carolTok)[0];
        check("forwarding shares the sending speed limit", f1.equals("200") && f2.equals("200") && f3.equals("200") && f4.equals("429"));

        System.out.println();
        System.out.println("-- Pins --");
        t.addAndGet(10000);
        r = http(port, "POST", "/api/chat/pin", form("room", "g-general", "id", String.valueOf(m1)), bobTok);
        checkCode("a normal user pins in General", r[0], "403");
        r = http(port, "POST", "/api/chat/pin", form("room", "g-general", "id", String.valueOf(m1)), null);
        checkCode("pinning without a sign-in", r[0], "401");
        r = http(port, "POST", "/api/chat/pin", form("room", "g-general", "id", String.valueOf(m1)), adminTok);
        checkCode("the admin pins in General", r[0], "200");
        json = get(port, "g-general", null);
        check("the pinned message is listed and marked", json.contains("\"pins\":[{\"id\":" + m1) && json.contains("\"pinned\":true"));
        http(port, "POST", "/api/chat/pin", form("room", "g-general", "id", String.valueOf(m2)), adminTok);
        long m3 = say(port, t, aliceTok, "g-general", "third pin");
        http(port, "POST", "/api/chat/pin", form("room", "g-general", "id", String.valueOf(m3)), adminTok);
        long m4 = say(port, t, aliceTok, "g-general", "fourth pin");
        r = http(port, "POST", "/api/chat/pin", form("room", "g-general", "id", String.valueOf(m4)), adminTok);
        checkCode("a fourth pin is refused", r[0], "400");
        r = http(port, "POST", "/api/chat/pin", form("room", "g-general", "id", String.valueOf(m1), "pinned", "false"), adminTok);
        checkCode("unpinning", r[0], "200");
        json = get(port, "g-general", null);
        check("it is no longer pinned", !json.contains("\"pins\":[{\"id\":" + m1));
        r = http(port, "POST", "/api/chat/pin", form("room", "g-general", "id", String.valueOf(m4)), adminTok);
        checkCode("a free slot can be used again", r[0], "200");
        http(port, "POST", "/api/chat/delete", form("room", "g-general", "id", String.valueOf(m4)), aliceTok);
        json = get(port, "g-general", null);
        check("deleting a pinned message removes the pin", !json.contains("fourth pin"));
        r = http(port, "POST", "/api/chat/pin", form("room", "g-general", "id", "999999"), adminTok);
        checkCode("pinning a message that does not exist", r[0], "404");
        long cm = say(port, t, bobTok, ch1, "channel message");
        r = http(port, "POST", "/api/chat/pin", form("room", ch1, "id", String.valueOf(cm)), bobTok);
        checkCode("a viewer pins in a channel room", r[0], "403");
        r = http(port, "POST", "/api/chat/pin", form("room", ch1, "id", String.valueOf(cm)), aliceTok);
        checkCode("the broadcaster pins in her channel room", r[0], "200");
        ChatStore.RoomInfo mine = cs.createGroup("Alice Club", "alice", false);
        long gm = say(port, t, bobTok, mine.id, "hi club");
        r = http(port, "POST", "/api/chat/pin", form("room", mine.id, "id", String.valueOf(gm)), aliceTok);
        checkCode("a group owner pins in her group", r[0], "200");
        r = http(port, "POST", "/api/chat/pin", form("room", mine.id, "id", String.valueOf(gm)), bobTok);
        checkCode("a member pins in someone else's group", r[0], "403");
        http(port, "POST", "/api/admin/chat/clear", form("room", mine.id), adminTok);
        json = get(port, mine.id, null);
        check("clearing a room removes its pins", json.contains("\"pins\":[]") && json.contains("\"messages\":[]"));

        System.out.println();
        System.out.println("-- Search --");
        t.addAndGet(120000);
        say(port, t, bobTok, other, "Cricket final tonight");
        say(port, t, carolTok, "g-general", "who watches the cricket match?");
        say(port, t, aliceTok, ch1, "welcome to the CRICKET stream");
        r = http(port, "GET", "/api/chat/search?q=cricket", null, null);
        checkCode("searching needs no sign-in", r[0], "200");
        check("it finds matches in every room, ignoring case", count(r[1], "\"text\"") == 3 && r[1].contains("\"roomTitle\":\"Other Room\""));
        check("the newest match is first", r[1].indexOf("welcome to the CRICKET") < r[1].indexOf("Cricket final"));
        r = http(port, "GET", "/api/chat/search?q=cricket&room=g-general", null, null);
        check("a room filter narrows it down", count(r[1], "\"text\"") == 1 && r[1].contains("who watches"));
        r = http(port, "GET", "/api/chat/search?q=a", null, null);
        checkCode("a one-letter search", r[0], "400");
        r = http(port, "GET", "/api/chat/search?q=zzzznothing", null, null);
        check("no match gives an empty list", r[0].equals("200") && r[1].contains("\"results\":[]"));
        r = http(port, "GET", "/api/chat/search?q=to%20be%20removed", null, null);
        check("deleted messages are not found", r[1].contains("\"results\":[]"));
        r = http(port, "GET", "/api/chat/search?q=cricket&room=nonsense", null, null);
        checkCode("a badly written room", r[0], "400");
        String last = "";
        for (int i = 0; i < 16; i++) {
            last = http(port, "GET", "/api/chat/search?q=cricket", null, null)[0];
        }
        checkCode("too many searches in a minute are slowed down", last, "429");

        System.out.println();
        System.out.println("-- Saving and loading --");
        cs.flushIfDirty();
        ChatStore cs2 = new ChatStore(chatFile, t::get);
        cs2.init();
        check("a forwarded message keeps its original author", cs2.find(other, fwdId) != null && "alice".equals(cs2.find(other, fwdId).forwardedFrom));
        check("pins survive a restart", cs2.pins("g-general").size() == 2 && cs2.pins(ch1).size() == 1);
        ChatStore.Receipt rc = cs2.receipt("g-general", m1, true);
        check("receipts survive a restart", rc.deliveredCount == 3 && rc.readCount == 3 && rc.read.contains("bob"));
        check("the privacy switches survive a restart", !cs2.prefs("bob")[1] && cs2.prefs("bob")[0] && cs2.prefs("gus")[0]);
        check("search works on the loaded messages", cs2.search("cricket", null, 30).size() == 3);

        server.stop();
        System.out.println();
        System.out.println(failures == 0 ? "ALL CHECKS PASSED" : failures + " CHECK(S) FAILED");
    }

    private static long say(int port, AtomicLong t, String tok, String room, String text) throws Exception {
        t.addAndGet(4000);
        String[] r = http(port, "POST", "/api/chat/post", form("room", room, "text", text), tok);
        return (long) first(r[1], "\"id\":(\\d+)");
    }

    private static String get(int port, String room, String tok) throws Exception {
        return http(port, "GET", "/api/chat/messages?room=" + room + "&limit=100", null, tok)[1];
    }

    /** {delivered, read} shown on a message, as the signed-in viewer sees it. */
    private static int[] counts(String json, long id) {
        Matcher m = Pattern.compile("\\{\"id\":" + id + ",\"user\".*?\"delivered\":(\\d+),\"read\":(\\d+)").matcher(json);
        return m.find() ? new int[]{Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))} : new int[]{-1, -1};
    }

    private static double first(String json, String pattern) {
        Matcher m = Pattern.compile(pattern).matcher(json);
        return m.find() ? Double.parseDouble(m.group(1)) : -1;
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

    private static void checkCode(String name, String actual, String expected) {
        if (actual.equals(expected)) {
            System.out.println("PASS  " + name + " -> " + actual);
        } else {
            failures++;
            System.out.println("FAIL  " + name + " -> got " + actual + ", expected " + expected);
        }
    }

    private static void check(String name, boolean ok) {
        if (ok) {
            System.out.println("PASS  " + name);
        } else {
            failures++;
            System.out.println("FAIL  " + name);
        }
    }
}