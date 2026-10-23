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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Tests chat rooms, groups, replies, deleting, muting, guests, saving and the HTTP API. */
public class ChatTest {

    private static final long HOUR = 3600000L;
    private static int failures = 0;
    private static final List<String> kicks = Collections.synchronizedList(new ArrayList<String>());

    public static void main(String[] args) throws Exception {
        AtomicLong t = new AtomicLong(1000000000000L);
        Path dir = Files.createTempDirectory("vstream-chat");
        Path page = dir.resolve("admin.html");
        Files.write(page, "<html>x</html>".getBytes(StandardCharsets.UTF_8));

        LiveChannelRegistry reg = new LiveChannelRegistry();
        String ch1 = reg.registerChannel("preset-1", "");
        String ch2 = reg.registerChannel("preset-2", "");
        reg.getChannel(ch1).setTitle("Friday show");
        UserStore us = new UserStore(dir.resolve("users.txt"), reg, kicks::add);
        String adminPw = us.init(null);
        us.createUser("alice", "password123", UserStore.Role.CHATTER);
        us.createUser("gus", "password123", UserStore.Role.GUEST);
        us.createUser("bob", "password123", UserStore.Role.CHATTER);

        ChatStore cs = new ChatStore(dir.resolve("chat.txt"), t::get);
        cs.init();
        Set<String> live = ConcurrentHashMap.newKeySet();
        live.add(ch1);
        SessionStore ss = new SessionStore();
        ChatApi chatApi = new ChatApi(cs, us, ss, reg, live::contains);
        AccountApi accountApi = new AccountApi(us, ss, new Limiter(), reg, page, kicks::add, id -> false);
        ClaimStore claims = new ClaimStore(dir.resolve("claims.txt"), reg, kicks::add, 48 * HOUR, 12 * HOUR, t::get);
        claims.init(101, 3);
        accountApi.setClaims(claims);
        ChannelListHttpServer server = new ChannelListHttpServer(reg, 0);
        server.setAccountApi(accountApi);
        server.setChatApi(chatApi);
        server.start();
        int port = server.getPort();

        String adminTok = login(port, "admin", adminPw);
        String aliceTok = login(port, "alice", "password123");
        String gusTok = login(port, "gus", "password123");
        String bobTok = login(port, "bob", "password123");

        System.out.println();
        System.out.println("-- Reading is public --");
        String[] r = http(port, "GET", "/api/chat/rooms", null, null);
        check("the room list needs no sign-in", r[0], "200");
        checkTrue("General is listed", r[1].contains("\"id\":\"g-general\""));
        checkTrue("the live channel has a room, titled by its title",
                r[1].contains("\"id\":\"CHANNEL-001\"") && r[1].contains("\"title\":\"Friday show\""));
        checkTrue("a channel that is not live has no room in the list", !r[1].contains("\"id\":\"CHANNEL-002\""));
        r = http(port, "GET", "/api/chat/messages?room=g-general", null, null);
        check("reading General without signing in", r[0], "200");
        checkTrue("it starts empty", r[1].contains("\"messages\":[]"));
        r = http(port, "GET", "/api/chat/messages?room=CHANNEL-002", null, null);
        check("reading a channel room that is not live", r[0], "200");
        r = http(port, "GET", "/api/chat/messages?room=CHANNEL-099", null, null);
        check("a channel that does not exist", r[0], "404");
        r = http(port, "GET", "/api/chat/messages?room=g-nothere", null, null);
        check("a group that does not exist", r[0], "404");
        r = http(port, "GET", "/api/chat/messages?room=nonsense", null, null);
        check("a badly written room name", r[0], "400");
        r = http(port, "POST", "/api/chat/rooms", "", null);
        check("POST to the room list", r[0], "405");

        System.out.println();
        System.out.println("-- Posting --");
        r = http(port, "POST", "/api/chat/post", form("room", "g-general", "text", "hello"), null);
        check("posting needs a sign-in", r[0], "401");
        r = http(port, "POST", "/api/chat/post", form("room", "g-general", "text", "hello world"), aliceTok);
        check("a registered user posts", r[0], "200");
        long id1 = firstNumber(r[1], "\"id\":(\\d+)");
        r = http(port, "POST", "/api/chat/post", form("room", "g-general", "text", "hi from a guest"), gusTok);
        check("a guest posts", r[0], "200");
        checkTrue("the guest is labelled", r[1].contains("\"role\":\"GUEST\""));
        r = http(port, "GET", "/api/chat/messages?room=g-general", null, null);
        List<Long> ids = messageIds(r[1]);
        checkTrue("both messages are listed, oldest first", ids.size() == 2 && ids.get(0) == id1 && ids.get(1) > id1);
        r = http(port, "POST", "/api/chat/post", "room=g-general&text=a%09b%0Ac", aliceTok);
        check("a message with a tab and a new line", r[0], "200");
        checkTrue("they become spaces", r[1].contains("\"text\":\"a b c\""));
        r = http(port, "POST", "/api/chat/post", form("room", "g-general", "text", "third"), aliceTok);
        check("a third quick message", r[0], "200");
        r = http(port, "POST", "/api/chat/post", form("room", "g-general", "text", "fourth"), aliceTok);
        check("a fourth message inside 3 seconds", r[0], "429");
        t.addAndGet(4000);
        r = http(port, "POST", "/api/chat/post", form("room", "g-general", "text", "fourth"), aliceTok);
        check("the same message 4 seconds later", r[0], "200");
        r = http(port, "POST", "/api/chat/post", form("room", "g-general", "text", ""), bobTok);
        check("an empty message", r[0], "400");
        r = http(port, "POST", "/api/chat/post", form("room", "g-general", "text", "   "), bobTok);
        check("a message of only spaces", r[0], "400");
        r = http(port, "POST", "/api/chat/post", form("room", "g-general", "text", repeat('x', 501)), bobTok);
        check("a message of 501 characters", r[0], "400");
        r = http(port, "POST", "/api/chat/post", form("room", "g-nothere", "text", "hi"), bobTok);
        check("posting to a group that does not exist", r[0], "404");
        r = http(port, "POST", "/api/chat/post", form("room", "CHANNEL-099", "text", "hi"), bobTok);
        check("posting to a channel that does not exist", r[0], "404");

        System.out.println();
        System.out.println("-- Replies --");
        r = http(port, "POST", "/api/chat/post", form("room", "g-general", "text", "welcome", "reply", String.valueOf(id1)), bobTok);
        check("a reply", r[0], "200");
        checkTrue("it quotes the first message",
                r[1].contains("\"reply\":{\"id\":" + id1 + ",\"user\":\"alice\",\"text\":\"hello world\",\"deleted\":false}"));
        long welcomeId = firstNumber(r[1], "\"id\":(\\d+)");
        r = http(port, "POST", "/api/chat/post", form("room", "g-general", "text", "to nothing", "reply", "999999"), bobTok);
        checkTrue("a reply to a message that does not exist quotes nothing", r[1].contains("\"reply\":null"));
        r = http(port, "POST", "/api/chat/post", form("room", ch1, "text", "from the channel", "reply", String.valueOf(id1)), bobTok);
        check("a post in a channel room", r[0], "200");
        checkTrue("a reply to a message from another room quotes nothing", r[1].contains("\"reply\":null"));

        System.out.println();
        System.out.println("-- Deleting --");
        r = http(port, "POST", "/api/chat/delete", form("room", "g-general", "id", String.valueOf(id1)), bobTok);
        check("deleting someone else's message", r[0], "403");
        r = http(port, "POST", "/api/chat/delete", form("room", "g-general", "id", String.valueOf(id1)), null);
        check("deleting without a sign-in", r[0], "401");
        r = http(port, "POST", "/api/chat/delete", form("room", "g-general", "id", String.valueOf(id1)), aliceTok);
        check("deleting your own message", r[0], "200");
        r = http(port, "POST", "/api/chat/delete", form("room", "g-general", "id", String.valueOf(id1)), aliceTok);
        check("deleting it again", r[0], "404");
        r = http(port, "GET", "/api/chat/messages?room=g-general", null, null);
        checkTrue("the message stays as a deleted placeholder without its text",
                r[1].contains("\"deleted\":true") && !r[1].contains("hello world\""));
        checkTrue("a reply to it shows it as removed",
                r[1].contains("\"reply\":{\"id\":" + id1 + ",\"user\":\"alice\",\"text\":\"\",\"deleted\":true}"));
        long lastId = firstNumber(r[1], "\"lastId\":(\\d+)");
        r = http(port, "POST", "/api/chat/delete", form("room", "g-general", "id", String.valueOf(welcomeId)), adminTok);
        check("the admin deletes anyone's message", r[0], "200");

        System.out.println();
        System.out.println("-- Reading new messages only --");
        t.addAndGet(10000);
        r = http(port, "POST", "/api/chat/post", form("room", "g-general", "text", "newer"), aliceTok);
        long newerId = firstNumber(r[1], "\"id\":(\\d+)");
        checkTrue("the new message has a higher id than the last one seen", newerId > lastId);
        r = http(port, "GET", "/api/chat/messages?room=g-general&after=" + lastId, null, null);
        checkTrue("only the newer message comes back", count(r[1], "\"time\":") == 1 && r[1].contains("newer"));
        checkTrue("lastId matches the new message", firstNumber(r[1], "\"lastId\":(\\d+)") == newerId);
        r = http(port, "GET", "/api/chat/messages?room=g-general&limit=2", null, null);
        checkTrue("a limit returns only the latest messages", count(r[1], "\"time\":") == 2 && r[1].contains("newer"));

        System.out.println();
        System.out.println("-- The excerpt and the most active room --");
        t.addAndGet(10000);
        r = http(port, "POST", "/api/chat/post", form("room", "g-general", "text", repeat('z', 200)), gusTok);
        check("a 200-character message", r[0], "200");
        r = http(port, "GET", "/api/chat/rooms", null, null);
        Matcher ex = Pattern.compile("\"id\":\"g-general\"[^}]*?\"lastText\":\"([^\"]*)\"").matcher(r[1]);
        checkTrue("the excerpt is 80 characters ending with an ellipsis",
                ex.find() && ex.group(1).length() == 80 && ex.group(1).endsWith("\u2026"));
        r = http(port, "GET", "/api/chat/top", null, null);
        check("the top room needs no sign-in", r[0], "200");
        checkTrue("the most active room is General", r[1].contains("\"id\":\"g-general\""));

        System.out.println();
        System.out.println("-- Groups --");
        t.addAndGet(10000);
        r = http(port, "POST", "/api/chat/groups/create", form("title", "Cricket Fans"), gusTok);
        check("a guest creates a group", r[0], "403");
        r = http(port, "POST", "/api/chat/groups/create", form("title", "ab"), aliceTok);
        check("a 2-letter group name", r[0], "400");
        r = http(port, "POST", "/api/chat/groups/create", form("title", repeat('q', 31)), aliceTok);
        check("a 31-letter group name", r[0], "400");
        r = http(port, "POST", "/api/chat/groups/create", form("title", "Cricket Fans"), aliceTok);
        check("a registered user creates a group", r[0], "200");
        checkTrue("the group gets a name-based id", r[1].contains("\"id\":\"g-cricket-fans\""));
        r = http(port, "POST", "/api/chat/groups/create", form("title", "Cricket Fans"), aliceTok);
        checkTrue("the same title gets a different id", r[1].contains("\"id\":\"g-cricket-fans-2\""));
        r = http(port, "POST", "/api/chat/groups/create", form("title", "Movie Night"), aliceTok);
        check("a third group", r[0], "200");
        r = http(port, "POST", "/api/chat/groups/create", form("title", "Book Club"), aliceTok);
        check("a fourth group in a day", r[0], "429");
        r = http(port, "POST", "/api/chat/groups/create", form("title", "Staff Room"), adminTok);
        check("the admin is not limited", r[0], "200");
        r = http(port, "POST", "/api/chat/post", form("room", "g-cricket-fans", "text", "hi group"), bobTok);
        check("posting in a group", r[0], "200");
        long groupMsg = firstNumber(r[1], "\"id\":(\\d+)");
        t.addAndGet(10000);
        r = http(port, "POST", "/api/chat/post", form("room", "g-cricket-fans", "text", "another one"), bobTok);
        long groupMsg2 = firstNumber(r[1], "\"id\":(\\d+)");
        r = http(port, "POST", "/api/chat/delete", form("room", "g-cricket-fans", "id", String.valueOf(groupMsg)), gusTok);
        check("a stranger deletes a message in the group", r[0], "403");
        r = http(port, "POST", "/api/chat/delete", form("room", "g-cricket-fans", "id", String.valueOf(groupMsg)), aliceTok);
        check("the group creator deletes a message in her group", r[0], "200");
        r = http(port, "POST", "/api/chat/groups/delete", form("room", "g-cricket-fans-2"), bobTok);
        check("someone else deletes the group", r[0], "403");
        r = http(port, "POST", "/api/chat/groups/delete", form("room", "g-general"), aliceTok);
        check("a user deletes General", r[0], "403");
        r = http(port, "POST", "/api/chat/groups/delete", form("room", "g-cricket-fans-2"), aliceTok);
        check("the creator deletes her group", r[0], "200");
        r = http(port, "GET", "/api/chat/messages?room=g-cricket-fans-2", null, null);
        check("a deleted group is gone", r[0], "404");
        r = http(port, "POST", "/api/chat/groups/delete", form("room", "g-movie-night"), adminTok);
        check("the admin deletes any group", r[0], "200");
        r = http(port, "POST", "/api/chat/groups/delete", form("room", ch1), aliceTok);
        check("deleting a channel room as a group", r[0], "400");

        System.out.println();
        System.out.println("-- Muting --");
        r = http(port, "POST", "/api/admin/chat/mute", form("username", "bob", "hours", "1"), aliceTok);
        check("a user mutes someone", r[0], "403");
        r = http(port, "POST", "/api/admin/chat/mute", form("username", "bob", "hours", "1"), null);
        check("muting without a sign-in", r[0], "401");
        r = http(port, "POST", "/api/admin/chat/mute", form("username", "bob", "hours", "1"), adminTok);
        check("the admin mutes bob for an hour", r[0], "200");
        r = http(port, "POST", "/api/chat/post", form("room", "g-general", "text", "can you hear me"), bobTok);
        check("bob posts while muted", r[0], "403");
        r = http(port, "GET", "/api/admin/chat/muted", null, adminTok);
        checkTrue("the muted list names bob with the time left", r[1].contains("\"username\":\"bob\"") && r[1].contains("\"hoursLeft\":1"));
        t.addAndGet(61 * 60000L);
        r = http(port, "POST", "/api/chat/post", form("room", "g-general", "text", "back again"), bobTok);
        check("bob posts after the hour is over", r[0], "200");
        r = http(port, "POST", "/api/admin/chat/mute", form("username", "bob", "hours", "0"), adminTok);
        check("muting until unmuted", r[0], "200");
        r = http(port, "GET", "/api/admin/chat/muted", null, adminTok);
        checkTrue("that shows no end time", r[1].contains("\"hoursLeft\":null"));
        t.addAndGet(1000 * HOUR);
        r = http(port, "POST", "/api/chat/post", form("room", "g-general", "text", "still muted"), bobTok);
        check("still muted 1000 hours later", r[0], "403");
        r = http(port, "POST", "/api/admin/chat/unmute", form("username", "bob"), adminTok);
        check("unmuting", r[0], "200");
        r = http(port, "POST", "/api/admin/chat/unmute", form("username", "bob"), adminTok);
        check("unmuting again", r[0], "404");
        r = http(port, "POST", "/api/chat/post", form("room", "g-general", "text", "free at last"), bobTok);
        check("bob posts after the unmute", r[0], "200");
        r = http(port, "POST", "/api/admin/chat/mute", form("username", "admin", "hours", "1"), adminTok);
        check("muting the admin", r[0], "400");
        r = http(port, "POST", "/api/admin/chat/mute", form("username", "nobody", "hours", "1"), adminTok);
        check("muting someone who does not exist", r[0], "404");
        cs.mute("zed", 0);
        r = http(port, "POST", "/api/admin/chat/mute", form("username", "gus", "hours", "0"), adminTok);
        check("muting a guest", r[0], "200");
        http(port, "POST", "/api/admin/chat/unmute", form("username", "gus"), adminTok);

        System.out.println();
        System.out.println("-- The top room after a day of silence --");
        t.addAndGet(25 * HOUR);
        r = http(port, "GET", "/api/chat/top", null, null);
        checkTrue("no messages in the last 24 hours means no top room", r[1].contains("\"room\":null"));

        System.out.println();
        System.out.println("-- Clearing a room --");
        r = http(port, "POST", "/api/admin/chat/clear", form("room", "g-general"), aliceTok);
        check("a user clears a room", r[0], "403");
        r = http(port, "POST", "/api/admin/chat/clear", form("room", "nonsense"), adminTok);
        check("clearing a badly written room", r[0], "400");
        r = http(port, "POST", "/api/admin/chat/clear", form("room", "g-general"), adminTok);
        check("the admin clears General", r[0], "200");
        r = http(port, "GET", "/api/chat/messages?room=g-general", null, null);
        checkTrue("General is empty", r[1].contains("\"messages\":[]"));

        System.out.println();
        System.out.println("-- Guests and registered users --");
        r = http(port, "POST", "/api/claim", form("channel", "CHANNEL-101"), gusTok);
        check("a guest picks a channel", r[0], "403");
        checkTrue("the answer says why", r[1].contains("guest"));
        r = http(port, "POST", "/api/claim", form("channel", "CHANNEL-101"), aliceTok);
        check("a registered user picks a channel", r[0], "200");
        r = http(port, "POST", "/api/register", form("username", "newguest", "password", "password123", "type", "guest"), null);
        check("signing up as a guest", r[0], "200");
        checkTrue("that makes a guest", r[1].contains("\"role\":\"GUEST\""));
        r = http(port, "POST", "/api/register", form("username", "newchat", "password", "password123"), null);
        checkTrue("signing up without a type makes a registered user", r[1].contains("\"role\":\"CHATTER\""));
        r = http(port, "POST", "/api/admin/users/create", form("username", "admguest", "password", "password123", "role", "GUEST"), adminTok);
        check("the admin creates a guest", r[0], "200");
        checkTrue("that is a guest account", r[1].contains("\"role\":\"GUEST\""));

        System.out.println();
        System.out.println("-- Saving and loading --");
        cs.flushIfDirty();
        ChatStore cs2 = new ChatStore(dir.resolve("chat.txt"), t::get);
        cs2.init();
        checkTrue("a group survives a restart", cs2.hasGroup("g-cricket-fans") && cs2.hasGroup("g-staff-room"));
        checkTrue("a deleted group stays deleted", !cs2.hasGroup("g-movie-night") && !cs2.hasGroup("g-cricket-fans-2"));
        checkTrue("messages in a channel room survive", cs2.messages(ch1, 0, 50).size() >= 1);
        checkTrue("a deleted message stays deleted after a restart",
                cs2.find("g-cricket-fans", groupMsg) != null && cs2.find("g-cricket-fans", groupMsg).deleted);
        checkTrue("a normal message in a group survives",
                cs2.find("g-cricket-fans", groupMsg2) != null && "another one".equals(cs2.find("g-cricket-fans", groupMsg2).text));
        checkTrue("a mute survives", cs2.isMuted("zed"));
        long before = cs.lastId("g-cricket-fans");
        ChatStore.Message next = cs2.post("g-staff-room", "admin", "after the restart", 0);
        checkTrue("new messages get ids above the old ones", next.id > before && next.id > newerId);
        checkTrue("the cleared room is still empty", cs2.messages("g-general", 0, 50).isEmpty());

        System.out.println();
        System.out.println("-- Idle guests --");
        long now = System.currentTimeMillis();
        checkTrue("guests active within 30 days stay", us.purgeIdleGuests(now + 10 * 24 * HOUR, 30 * 24 * HOUR).isEmpty());
        List<String> gone = us.purgeIdleGuests(now + 31 * 24 * HOUR, 30 * 24 * HOUR);
        checkTrue("guests idle for 31 days are removed", gone.contains("gus") && gone.contains("newguest") && gone.contains("admguest"));
        checkTrue("registered users and the admin stay",
                us.info("alice") != null && us.info("bob") != null && us.info("newchat") != null && us.info("admin") != null);
        checkTrue("a removed guest can no longer sign in", us.authenticate("gus", "password123") == null);

        server.stop();
        System.out.println();
        System.out.println(failures == 0 ? "ALL CHECKS PASSED" : failures + " CHECK(S) FAILED");
    }

    private static String login(int port, String user, String pass) throws Exception {
        return value(http(port, "POST", "/api/login", form("username", user, "password", pass), null)[1], "token");
    }

    private static String repeat(char c, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            sb.append(c);
        }
        return sb.toString();
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

    private static long firstNumber(String json, String pattern) {
        Matcher m = Pattern.compile(pattern).matcher(json);
        return m.find() ? Long.parseLong(m.group(1)) : -1;
    }

    private static List<Long> messageIds(String json) {
        List<Long> ids = new ArrayList<Long>();
        Matcher m = Pattern.compile("\\{\"id\":(\\d+),\"user\":\"[^\"]*\",\"role\"").matcher(json);
        while (m.find()) {
            ids.add(Long.parseLong(m.group(1)));
        }
        return ids;
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