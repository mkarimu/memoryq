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

/** Tests reactions, editing and the typing indicator. */
public class ChatFeaturesTest {

    private static final String UP = "\uD83D\uDC4D";
    private static final String HEART = "\u2764\uFE0F";
    private static int failures = 0;

    public static void main(String[] args) throws Exception {
        AtomicLong t = new AtomicLong(1000000000000L);
        Path dir = Files.createTempDirectory("vstream-features");
        LiveChannelRegistry reg = new LiveChannelRegistry();
        UserStore us = new UserStore(dir.resolve("users.txt"), reg, id -> { });
        us.init(null);
        us.createUser("alice", "password123", UserStore.Role.CHATTER);
        us.createUser("bob", "password123", UserStore.Role.CHATTER);
        us.createUser("gus", "password123", UserStore.Role.GUEST);
        Path chatFile = dir.resolve("chat.txt");
        ChatStore cs = new ChatStore(chatFile, t::get);
        cs.init();
        SessionStore ss = new SessionStore();
        String aliceTok = ss.create("alice", UserStore.Role.CHATTER);
        String bobTok = ss.create("bob", UserStore.Role.CHATTER);
        String gusTok = ss.create("gus", UserStore.Role.GUEST);
        ChannelListHttpServer server = new ChannelListHttpServer(reg, 0);
        server.setChatApi(new ChatApi(cs, us, ss, reg, id -> false));
        server.start();
        int port = server.getPort();

        System.out.println();
        System.out.println("-- Reactions --");
        long m1 = say(port, t, aliceTok, "hello");
        String[] r = http(port, "POST", "/api/chat/react", form("room", "g-general", "id", String.valueOf(m1), "emoji", UP), bobTok);
        check("bob reacts", r[0], "200");
        checkTrue("the answer says it was added, and it is bob's",
                r[1].contains("\"added\":true") && r[1].contains("\"emoji\":\"" + UP + "\",\"count\":1,\"mine\":true"));
        r = http(port, "GET", "/api/chat/messages?room=g-general", null, null);
        checkTrue("a viewer sees the reaction but it is not theirs",
                r[1].contains("\"emoji\":\"" + UP + "\",\"count\":1,\"mine\":false"));
        r = http(port, "GET", "/api/chat/messages?room=g-general", null, bobTok);
        checkTrue("bob sees it as his own", r[1].contains("\"mine\":true"));
        http(port, "POST", "/api/chat/react", form("room", "g-general", "id", String.valueOf(m1), "emoji", UP), gusTok);
        r = http(port, "GET", "/api/chat/messages?room=g-general", null, null);
        checkTrue("a second person makes the count 2", r[1].contains("\"count\":2"));
        r = http(port, "POST", "/api/chat/react", form("room", "g-general", "id", String.valueOf(m1), "emoji", UP), bobTok);
        checkTrue("reacting again takes the reaction back", r[1].contains("\"added\":false"));
        r = http(port, "GET", "/api/chat/messages?room=g-general", null, null);
        checkTrue("the count is 1 again", r[1].contains("\"emoji\":\"" + UP + "\",\"count\":1"));
        http(port, "POST", "/api/chat/react", form("room", "g-general", "id", String.valueOf(m1), "emoji", HEART), bobTok);
        r = http(port, "GET", "/api/chat/messages?room=g-general", null, null);
        checkTrue("two different reactions can sit on one message",
                r[1].contains("\"emoji\":\"" + UP + "\"") && r[1].contains("\"emoji\":\"" + HEART + "\""));
        r = http(port, "POST", "/api/chat/react", form("room", "g-general", "id", String.valueOf(m1), "emoji", "x"), bobTok);
        check("an emoji that is not on the list", r[0], "400");
        r = http(port, "POST", "/api/chat/react", form("room", "g-general", "id", String.valueOf(m1), "emoji", UP), null);
        check("reacting without a sign-in", r[0], "401");
        r = http(port, "POST", "/api/chat/react", form("room", "g-general", "id", "999999", "emoji", UP), bobTok);
        check("reacting to a message that does not exist", r[0], "404");
        long m2 = say(port, t, alice(aliceTok), "to be deleted");
        http(port, "POST", "/api/chat/react", form("room", "g-general", "id", String.valueOf(m2), "emoji", UP), bobTok);
        r = http(port, "POST", "/api/chat/delete", form("room", "g-general", "id", String.valueOf(m2)), aliceTok);
        check("deleting a message that has reactions", r[0], "200");
        r = http(port, "POST", "/api/chat/react", form("room", "g-general", "id", String.valueOf(m2), "emoji", UP), bobTok);
        check("reacting to a deleted message", r[0], "404");
        cs.mute("bob", 0);
        r = http(port, "POST", "/api/chat/react", form("room", "g-general", "id", String.valueOf(m1), "emoji", UP), bobTok);
        check("a muted user reacts", r[0], "403");
        cs.unmute("bob");

        System.out.println();
        System.out.println("-- Editing --");
        long e1 = say(port, t, aliceTok, "typo here");
        r = http(port, "POST", "/api/chat/edit", form("room", "g-general", "id", String.valueOf(e1), "text", "fixed here"), aliceTok);
        check("the author edits", r[0], "200");
        checkTrue("the text changed and the message is marked as edited",
                r[1].contains("\"text\":\"fixed here\"") && r[1].contains("\"edited\":true"));
        r = http(port, "POST", "/api/chat/edit", form("room", "g-general", "id", String.valueOf(e1), "text", "hacked"), bobTok);
        check("someone else edits it", r[0], "403");
        r = http(port, "POST", "/api/chat/edit", form("room", "g-general", "id", String.valueOf(e1), "text", "   "), aliceTok);
        check("editing to nothing", r[0], "400");
        r = http(port, "POST", "/api/chat/edit", form("room", "g-general", "id", String.valueOf(e1), "text", repeat('x', 501)), aliceTok);
        check("editing to 501 characters", r[0], "400");
        r = http(port, "POST", "/api/chat/edit", form("room", "g-general", "id", String.valueOf(e1), "text", "again"), null);
        check("editing without a sign-in", r[0], "401");
        r = http(port, "POST", "/api/chat/edit", form("room", "g-general", "id", "999999", "text", "x"), aliceTok);
        check("editing a message that does not exist", r[0], "404");
        r = http(port, "POST", "/api/chat/edit", form("room", "g-general", "id", String.valueOf(m2), "text", "revived"), aliceTok);
        check("editing a deleted message", r[0], "404");
        long same = say(port, t, aliceTok, "same text");
        r = http(port, "POST", "/api/chat/edit", form("room", "g-general", "id", String.valueOf(same), "text", "same text"), aliceTok);
        checkTrue("saving the same text does not mark it as edited", r[0].equals("200") && r[1].contains("\"edited\":false"));
        t.addAndGet(4000);
        r = http(port, "POST", "/api/chat/post", form("room", "g-general", "text", "replying", "reply", String.valueOf(e1)), bobTok);
        checkTrue("a reply quotes the edited text", r[1].contains("\"text\":\"fixed here\",\"deleted\":false}"));
        cs.mute("alice", 0);
        r = http(port, "POST", "/api/chat/edit", form("room", "g-general", "id", String.valueOf(e1), "text", "muted edit"), aliceTok);
        check("a muted user edits", r[0], "403");
        cs.unmute("alice");
        long late = say(port, t, aliceTok, "too late soon");
        t.addAndGet(16 * 60000L);
        r = http(port, "POST", "/api/chat/edit", form("room", "g-general", "id", String.valueOf(late), "text", "too late"), aliceTok);
        check("editing after 16 minutes", r[0], "403");
        checkTrue("the answer mentions the 15 minutes", r[1].contains("15 minutes"));

        System.out.println();
        System.out.println("-- Typing --");
        r = http(port, "POST", "/api/chat/typing", form("room", "g-general"), null);
        check("typing without a sign-in", r[0], "401");
        r = http(port, "POST", "/api/chat/typing", form("room", "g-nothere"), aliceTok);
        check("typing in a room that does not exist", r[0], "404");
        r = http(port, "POST", "/api/chat/typing", form("room", "g-general"), aliceTok);
        check("alice starts typing", r[0], "200");
        r = http(port, "GET", "/api/chat/messages?room=g-general", null, null);
        checkTrue("a viewer sees alice typing", r[1].contains("\"typing\":[\"alice\"]"));
        r = http(port, "GET", "/api/chat/messages?room=g-general", null, bobTok);
        checkTrue("bob sees alice typing", r[1].contains("\"typing\":[\"alice\"]"));
        r = http(port, "GET", "/api/chat/messages?room=g-general", null, aliceTok);
        checkTrue("alice does not see herself", r[1].contains("\"typing\":[]"));
        http(port, "POST", "/api/chat/typing", form("room", "g-general"), bobTok);
        r = http(port, "GET", "/api/chat/messages?room=g-general", null, null);
        checkTrue("both names are listed", r[1].contains("\"typing\":[\"alice\",\"bob\"]"));
        t.addAndGet(6000);
        r = http(port, "GET", "/api/chat/messages?room=g-general", null, null);
        checkTrue("typing expires after 5 seconds", r[1].contains("\"typing\":[]"));
        http(port, "POST", "/api/chat/typing", form("room", "g-general"), aliceTok);
        say(port, t, aliceTok, "done typing");
        r = http(port, "GET", "/api/chat/messages?room=g-general", null, null);
        checkTrue("sending a message clears the typing line", r[1].contains("\"typing\":[]"));
        cs.mute("bob", 0);
        http(port, "POST", "/api/chat/typing", form("room", "g-general"), bobTok);
        r = http(port, "GET", "/api/chat/messages?room=g-general", null, null);
        checkTrue("a muted user does not show as typing", r[1].contains("\"typing\":[]"));
        cs.unmute("bob");

        System.out.println();
        System.out.println("-- Saving and loading --");
        cs.flushIfDirty();
        ChatStore cs2 = new ChatStore(chatFile, t::get);
        cs2.init();
        ChatStore.Message back = cs2.find("g-general", m1);
        checkTrue("reactions survive a restart",
                back != null && back.reactions.containsKey(HEART) && back.reactions.get(HEART).contains("bob")
                        && back.reactions.containsKey(UP) && back.reactions.get(UP).contains("gus"));
        checkTrue("an edited message stays edited", cs2.find("g-general", e1).edited
                && "fixed here".equals(cs2.find("g-general", e1).text));
        checkTrue("a deleted message has no reactions", cs2.find("g-general", m2).deleted
                && cs2.find("g-general", m2).reactions.isEmpty());
        checkTrue("the reaction list has 6 emoji", ChatStore.REACTIONS.size() == 6);

        server.stop();
        System.out.println();
        System.out.println(failures == 0 ? "ALL CHECKS PASSED" : failures + " CHECK(S) FAILED");
    }

    private static String alice(String tok) {
        return tok;
    }

    private static long say(int port, AtomicLong t, String tok, String text) throws Exception {
        t.addAndGet(4000);
        String[] r = http(port, "POST", "/api/chat/post", form("room", "g-general", "text", text), tok);
        Matcher m = Pattern.compile("\"id\":(\\d+)").matcher(r[1]);
        return m.find() ? Long.parseLong(m.group(1)) : -1;
    }

    private static String repeat(char c, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            sb.append(c);
        }
        return sb.toString();
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