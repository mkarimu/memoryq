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

/** Tests the group admin / super-admin split: labels, renaming, muting, bans, the admin list and private mail. */
public class GroupAdminTest {

    private static int failures = 0;
    private static final long HOUR = 3600000L;

    public static void main(String[] args) throws Exception {
        final AtomicLong t = new AtomicLong(1000000000000L);
        Path dir = Files.createTempDirectory("vstream-groupadmin");
        LiveChannelRegistry reg = new LiveChannelRegistry();
        UserStore us = new UserStore(dir.resolve("users.txt"), reg, id -> { });
        us.init(null);
        for (String n : new String[]{"alice", "bob", "carol", "dave", "erin"}) {
            us.createUser(n, "password123", UserStore.Role.CHATTER);
        }
        us.createUser("gus", "password123", UserStore.Role.GUEST);
        Path chatFile = dir.resolve("chat.txt");
        ChatStore cs = new ChatStore(chatFile, t::get);
        cs.init();
        SessionStore ss = new SessionStore();
        String aliceTok = ss.create("alice", UserStore.Role.CHATTER);
        String bobTok = ss.create("bob", UserStore.Role.CHATTER);
        String carolTok = ss.create("carol", UserStore.Role.CHATTER);
        String daveTok = ss.create("dave", UserStore.Role.CHATTER);
        String erinTok = ss.create("erin", UserStore.Role.CHATTER);
        String gusTok = ss.create("gus", UserStore.Role.GUEST);
        String adminTok = ss.create("admin", UserStore.Role.ADMIN);
        ChannelListHttpServer server = new ChannelListHttpServer(reg, 0);
        server.setChatApi(new ChatApi(cs, us, ss, reg, id -> false));
        server.start();
        int port = server.getPort();
        final String G = "g-family-chat";
        final String OPEN = "g-open-club";

        http(port, "POST", "/api/chat/groups/create", form("title", "Family Chat", "private", "true"), aliceTok);
        http(port, "POST", "/api/chat/groups/create", form("title", "Open Club"), bobTok);
        String[] r = http(port, "POST", "/api/chat/groups/invite/create", form("room", G, "uses", "20"), aliceTok);
        String multi = value(r[1], "code");
        for (String tok : new String[]{bobTok, carolTok, gusTok}) {
            http(port, "POST", "/api/chat/groups/join", form("code", multi), tok);
        }

        System.out.println();
        System.out.println("-- Group admin labels --");
        long ownerMsg = say(port, t, aliceTok, G, "message from the group admin");
        long memberMsg = say(port, t, bobTok, G, "message from a member");
        String json = http(port, "GET", "/api/chat/messages?room=" + G, null, bobTok)[1];
        check("the group admin's message is labelled", flag(json, ownerMsg).equals("true"));
        check("a member's message is not", flag(json, memberMsg).equals("false"));
        long adminMsg = say(port, t, adminTok, "g-general", "welcome everyone");
        long userMsg = say(port, t, aliceTok, "g-general", "hello general");
        json = http(port, "GET", "/api/chat/messages?room=g-general", null, null)[1];
        check("the super-admin is not labelled as a group admin", flag(json, adminMsg).equals("false"));
        check("nor is a normal user in a group someone else owns", flag(json, userMsg).equals("false"));
        r = http(port, "GET", "/api/chat/groups/members?room=" + G, null, bobTok);
        check("the member list labels the group admin and nobody else",
                r[1].contains("\"name\":\"alice\",\"owner\":true,\"label\":\"Group admin\"")
                        && r[1].contains("\"name\":\"bob\",\"owner\":false,\"label\":\"\""));

        System.out.println();
        System.out.println("-- Renaming --");
        r = http(port, "POST", "/api/chat/groups/rename", form("room", G, "title", "Family Hub"), aliceTok);
        check("the group admin renames the group", r[0].equals("200") && r[1].contains("\"title\":\"Family Hub\""));
        json = http(port, "GET", "/api/chat/messages?room=" + G, null, aliceTok)[1];
        check("the id stays the same and the title changes", json.contains("\"room\":\"" + G + "\"") && json.contains("\"title\":\"Family Hub\""));
        check("the room list shows the new name", http(port, "GET", "/api/chat/rooms", null, aliceTok)[1].contains("\"title\":\"Family Hub\""));
        check("a member cannot rename", http(port, "POST", "/api/chat/groups/rename", form("room", G, "title", "Bobs Hub"), bobTok)[0].equals("403"));
        check("an outsider cannot rename (and cannot tell it exists)", http(port, "POST", "/api/chat/groups/rename", form("room", G, "title", "Erins Hub"), erinTok)[0].equals("404"));
        check("renaming needs a sign-in", http(port, "POST", "/api/chat/groups/rename", form("room", G, "title", "Nobody Hub"), null)[0].equals("401"));
        check("a name that is too short", http(port, "POST", "/api/chat/groups/rename", form("room", G, "title", "ab"), aliceTok)[0].equals("400"));
        check("a name that is too long", http(port, "POST", "/api/chat/groups/rename", form("room", G, "title", repeat('x', 31)), aliceTok)[0].equals("400"));
        check("a group that does not exist", http(port, "POST", "/api/chat/groups/rename", form("room", "g-nothere", "title", "Whatever"), aliceTok)[0].equals("404"));
        check("a normal user cannot rename General", http(port, "POST", "/api/chat/groups/rename", form("room", "g-general", "title", "My General"), aliceTok)[0].equals("403"));
        check("the owner of a public group renames it", http(port, "POST", "/api/chat/groups/rename", form("room", OPEN, "title", "Open Club Two"), bobTok)[0].equals("200"));
        check("someone else cannot rename a public group", http(port, "POST", "/api/chat/groups/rename", form("room", OPEN, "title", "Carols Club"), carolTok)[0].equals("403"));
        check("the super-admin can rename any group, even a private one",
                http(port, "POST", "/api/chat/groups/rename", form("room", G, "title", "Renamed By Admin"), adminTok)[0].equals("200"));
        check("but still cannot read it", http(port, "GET", "/api/chat/messages?room=" + G, null, adminTok)[0].equals("404"));
        int ok = 0;
        for (int i = 2; i <= 10; i++) {
            if (http(port, "POST", "/api/chat/groups/rename", form("room", G, "title", "Hub " + i), aliceTok)[0].equals("200")) {
                ok++;
            }
        }
        check("ten renames a day are allowed", ok == 9);
        check("the eleventh is slowed down", http(port, "POST", "/api/chat/groups/rename", form("room", G, "title", "Hub 11"), aliceTok)[0].equals("429"));
        check("the super-admin is not limited and restores the name",
                http(port, "POST", "/api/chat/groups/rename", form("room", G, "title", "Family Chat"), adminTok)[0].equals("200"));

        System.out.println();
        System.out.println("-- Muting inside a group --");
        long carolMsg = say(port, t, carolTok, G, "carol before the mute");
        long publicMsg = say(port, t, bobTok, OPEN, "a public hello");
        check("a member cannot mute", http(port, "POST", "/api/chat/groups/mute", form("room", G, "username", "carol", "hours", "1"), bobTok)[0].equals("403"));
        check("an outsider cannot mute", http(port, "POST", "/api/chat/groups/mute", form("room", G, "username", "carol", "hours", "1"), erinTok)[0].equals("404"));
        check("muting needs a sign-in", http(port, "POST", "/api/chat/groups/mute", form("room", G, "username", "carol"), null)[0].equals("401"));
        check("the group admin mutes carol for an hour", http(port, "POST", "/api/chat/groups/mute", form("room", G, "username", "carol", "hours", "1"), aliceTok)[0].equals("200"));
        r = http(port, "POST", "/api/chat/post", form("room", G, "text", "can you hear me"), carolTok);
        check("carol cannot post in that group", r[0].equals("403") && r[1].contains("muted in this group"));
        check("nor edit", http(port, "POST", "/api/chat/edit", form("room", G, "id", String.valueOf(carolMsg), "text", "edited"), carolTok)[0].equals("403"));
        check("nor react", http(port, "POST", "/api/chat/react", form("room", G, "id", String.valueOf(ownerMsg), "emoji", "\uD83D\uDC4D"), carolTok)[0].equals("403"));
        check("nor forward into it", http(port, "POST", "/api/chat/forward", form("fromRoom", OPEN, "toRoom", G, "id", String.valueOf(publicMsg)), carolTok)[0].equals("403"));
        http(port, "POST", "/api/chat/typing", form("room", G), carolTok);
        check("her typing is not shown", http(port, "GET", "/api/chat/messages?room=" + G, null, aliceTok)[1].contains("\"typing\":[]"));
        check("she can still read the group", http(port, "GET", "/api/chat/messages?room=" + G, null, carolTok)[0].equals("200"));
        check("and post anywhere else", say(port, t, carolTok, OPEN, "carol in the open club") > 0);
        r = http(port, "GET", "/api/chat/groups/muted?room=" + G, null, aliceTok);
        check("the group admin sees who is muted", r[0].equals("200") && r[1].contains("\"username\":\"carol\"") && r[1].contains("\"hoursLeft\":1"));
        check("a member cannot see the list", http(port, "GET", "/api/chat/groups/muted?room=" + G, null, bobTok)[0].equals("403"));
        check("the super-admin cannot see inside a private group", http(port, "GET", "/api/chat/groups/muted?room=" + G, null, adminTok)[0].equals("404"));
        t.addAndGet(61 * 60000L);
        check("after the hour carol can post again", say(port, t, carolTok, G, "carol is back") > 0);
        check("an indefinite mute", http(port, "POST", "/api/chat/groups/mute", form("room", G, "username", "carol", "hours", "0"), aliceTok)[0].equals("200"));
        t.addAndGet(1000 * HOUR);
        check("still in force 1000 hours later", http(port, "POST", "/api/chat/post", form("room", G, "text", "still muted"), carolTok)[0].equals("403"));
        check("the group admin unmutes", http(port, "POST", "/api/chat/groups/unmute", form("room", G, "username", "carol"), aliceTok)[0].equals("200"));
        check("unmuting again", http(port, "POST", "/api/chat/groups/unmute", form("room", G, "username", "carol"), aliceTok)[0].equals("404"));
        check("carol can post again", say(port, t, carolTok, G, "free at last") > 0);
        check("the group admin cannot mute herself", http(port, "POST", "/api/chat/groups/mute", form("room", G, "username", "alice"), aliceTok)[0].equals("400"));
        check("nor the super-admin", http(port, "POST", "/api/chat/groups/mute", form("room", G, "username", "admin"), aliceTok)[0].equals("400"));
        check("nor someone who is not in the group", http(port, "POST", "/api/chat/groups/mute", form("room", G, "username", "erin"), aliceTok)[0].equals("404"));
        check("nor someone who does not exist", http(port, "POST", "/api/chat/groups/mute", form("room", G, "username", "nobody"), aliceTok)[0].equals("404"));
        check("a group admin cannot mute in a group she does not run", http(port, "POST", "/api/chat/groups/mute", form("room", "g-general", "username", "bob"), aliceTok)[0].equals("403"));
        check("in a public group the owner mutes any user", http(port, "POST", "/api/chat/groups/mute", form("room", OPEN, "username", "erin", "hours", "2"), bobTok)[0].equals("200"));
        check("that user cannot post there", http(port, "POST", "/api/chat/post", form("room", OPEN, "text", "hi"), erinTok)[0].equals("403"));
        check("but can post elsewhere", say(port, t, erinTok, "g-general", "erin in general") > 0);
        check("another member cannot mute in a public group", http(port, "POST", "/api/chat/groups/mute", form("room", OPEN, "username", "erin"), carolTok)[0].equals("403"));
        check("the super-admin does not use group mute (global mute is separate)", http(port, "POST", "/api/chat/groups/mute", form("room", OPEN, "username", "dave"), adminTok)[0].equals("403"));

        System.out.println();
        System.out.println("-- Removed people cannot slip back in --");
        String fresh = value(http(port, "POST", "/api/chat/groups/invite/create", form("room", G, "uses", "20"), aliceTok)[1], "code");
        check("dave joins with the code", http(port, "POST", "/api/chat/groups/join", form("code", fresh), daveTok)[0].equals("200"));
        check("the group admin removes dave", http(port, "POST", "/api/chat/groups/remove", form("room", G, "username", "dave"), aliceTok)[0].equals("200"));
        check("dave is locked out", http(port, "GET", "/api/chat/messages?room=" + G, null, daveTok)[0].equals("404"));
        r = http(port, "POST", "/api/chat/groups/join", form("code", fresh), daveTok);
        check("dave cannot come back with the same code", r[0].equals("403") && r[1].contains("removed"));
        check("the group admin invites dave again by name", http(port, "POST", "/api/chat/groups/invite/user", form("room", G, "username", "dave"), aliceTok)[0].equals("200"));
        check("dave accepts", http(port, "POST", "/api/chat/invitations/accept", form("room", G), daveTok)[0].equals("200"));
        check("dave is back in", http(port, "GET", "/api/chat/messages?room=" + G, null, daveTok)[0].equals("200"));
        check("dave leaves", http(port, "POST", "/api/chat/groups/leave", form("room", G), daveTok)[0].equals("200"));
        check("leaving on your own does not ban you", http(port, "POST", "/api/chat/groups/join", form("code", fresh), daveTok)[0].equals("200"));

        System.out.println();
        System.out.println("-- The super-admin sees group admins, not groups --");
        http(port, "GET", "/api/chat/messages?room=" + G, null, aliceTok);
        r = http(port, "GET", "/api/admin/chat/groupadmins", null, adminTok);
        check("the super-admin gets the list", r[0].equals("200"));
        check("alice is listed with the label and her group", r[1].contains("\"name\":\"alice\",\"label\":\"Group admin\"")
                && r[1].contains("\"title\":\"Family Chat\"") && r[1].contains("\"private\":true"));
        check("bob is listed with his public group", r[1].contains("\"name\":\"bob\",\"label\":\"Group admin\"")
                && r[1].contains("\"title\":\"Open Club Two\"") && r[1].contains("\"private\":false"));
        check("people who run no group are not listed", !r[1].contains("\"name\":\"carol\"") && !r[1].contains("\"name\":\"erin\""));
        check("the super-admin is not in the list of group admins", !r[1].contains("\"name\":\"admin\""));
        check("there are no message texts and no member names in it", !r[1].contains("message from") && !r[1].contains("carol") && !r[1].contains("dave"));
        check("online status is included", r[1].contains("\"online\":true"));
        check("a normal user cannot see it", http(port, "GET", "/api/admin/chat/groupadmins", null, aliceTok)[0].equals("403"));
        check("nor an anonymous one", http(port, "GET", "/api/admin/chat/groupadmins", null, null)[0].equals("401"));
        check("the super-admin cannot read a private group", http(port, "GET", "/api/chat/messages?room=" + G, null, adminTok)[0].equals("404"));
        check("nor post in it", http(port, "POST", "/api/chat/post", form("room", G, "text", "hi"), adminTok)[0].equals("404"));
        check("nor remove its members", http(port, "POST", "/api/chat/groups/remove", form("room", G, "username", "bob"), adminTok)[0].equals("404"));
        check("nor search it", http(port, "GET", "/api/chat/search?q=family", null, adminTok)[1].contains("\"results\":[]")
                || !http(port, "GET", "/api/chat/search?q=message", null, adminTok)[1].contains("message from the group admin"));

        System.out.println();
        System.out.println("-- Private messages from the super-admin --");
        check("a normal user cannot use the admin send", http(port, "POST", "/api/admin/chat/mail/send", form("username", "alice", "text", "hi"), bobTok)[0].equals("403"));
        check("it needs a sign-in", http(port, "POST", "/api/admin/chat/mail/send", form("username", "alice", "text", "hi"), null)[0].equals("401"));
        check("a person who runs no group cannot be messaged", http(port, "POST", "/api/admin/chat/mail/send", form("username", "carol", "text", "hi"), adminTok)[0].equals("400"));
        check("nor a user who does not exist", http(port, "POST", "/api/admin/chat/mail/send", form("username", "nobody", "text", "hi"), adminTok)[0].equals("404"));
        check("nor the super-admin", http(port, "POST", "/api/admin/chat/mail/send", form("username", "admin", "text", "hi"), adminTok)[0].equals("400"));
        check("an empty message", http(port, "POST", "/api/admin/chat/mail/send", form("username", "alice", "text", "  "), adminTok)[0].equals("400"));
        check("a message over 500 characters", http(port, "POST", "/api/admin/chat/mail/send", form("username", "alice", "text", repeat('x', 501)), adminTok)[0].equals("400"));
        check("the super-admin writes to alice", http(port, "POST", "/api/admin/chat/mail/send", form("username", "alice", "text", "Please keep the group friendly"), adminTok)[0].equals("200"));
        r = http(port, "GET", "/api/admin/chat/groupadmins", null, adminTok);
        r = http(port, "GET", "/api/chat/adminmail", null, aliceTok);
        check("alice sees it, from the super-admin, unread", r[0].equals("200") && r[1].contains("\"unread\":1") && r[1].contains("\"fromAdmin\":true")
                && r[1].contains("Please keep the group friendly"));
        check("bob has nothing", http(port, "GET", "/api/chat/adminmail", null, bobTok)[1].contains("\"messages\":[]"));
        check("it is not visible without a sign-in", http(port, "GET", "/api/chat/adminmail", null, null)[0].equals("401"));
        r = http(port, "GET", "/api/admin/chat/mail/thread?username=alice", null, adminTok);
        check("before she reads it, the super-admin sees it as not seen", r[1].contains("\"read\":false"));
        http(port, "POST", "/api/chat/adminmail/read", "", aliceTok);
        check("alice's unread count is now zero", http(port, "GET", "/api/chat/adminmail", null, aliceTok)[1].contains("\"unread\":0"));
        check("and the super-admin sees it as seen", http(port, "GET", "/api/admin/chat/mail/thread?username=alice", null, adminTok)[1].contains("\"read\":true"));
        check("a user without a thread cannot send one", http(port, "POST", "/api/chat/adminmail/send", form("text", "hello?"), bobTok)[0].equals("403"));
        check("alice replies", http(port, "POST", "/api/chat/adminmail/send", form("text", "Understood, thank you"), aliceTok)[0].equals("200"));
        check("the super-admin sees one new reply in the list", http(port, "GET", "/api/admin/chat/groupadmins", null, adminTok)[1].contains("\"unreadMail\":1"));
        r = http(port, "GET", "/api/admin/chat/mail/thread?username=alice", null, adminTok);
        check("the thread has both messages, the reply from alice", r[1].contains("Understood, thank you") && r[1].contains("\"fromAdmin\":false"));
        check("after opening the thread the reply counts as read", http(port, "GET", "/api/admin/chat/groupadmins", null, adminTok)[1].contains("\"unreadMail\":0"));
        check("an empty reply", http(port, "POST", "/api/chat/adminmail/send", form("text", ""), aliceTok)[0].equals("400"));
        int sent = 1;
        int limited = 0;
        for (int i = 0; i < 12; i++) {
            String code = http(port, "POST", "/api/chat/adminmail/send", form("text", "reply " + i), aliceTok)[0];
            if (code.equals("200")) {
                sent++;
            } else if (code.equals("429")) {
                limited++;
            }
        }
        check("replies are limited to 10 a minute", sent == 10 && limited == 3);
        check("the super-admin cannot use the group admin's inbox", http(port, "GET", "/api/chat/adminmail", null, adminTok)[0].equals("400"));
        check("the mail is never in the group", !http(port, "GET", "/api/chat/messages?room=" + G, null, aliceTok)[1].contains("Please keep the group friendly"));
        check("and never found by search", http(port, "GET", "/api/chat/search?q=friendly", null, aliceTok)[1].contains("\"results\":[]"));

        System.out.println();
        System.out.println("-- Saving and loading --");
        check("the group admin removes gus", http(port, "POST", "/api/chat/groups/remove", form("room", G, "username", "gus"), aliceTok)[0].equals("200"));
        check("and mutes carol until unmuted", http(port, "POST", "/api/chat/groups/mute", form("room", G, "username", "carol", "hours", "0"), aliceTok)[0].equals("200"));
        cs.flushIfDirty();
        ChatStore cs2 = new ChatStore(chatFile, t::get);
        cs2.init();
        check("the group name survives", "Family Chat".equals(cs2.info(G, G).title) && "Open Club Two".equals(cs2.info(OPEN, OPEN).title));
        int status = 0;
        try {
            cs2.joinByCode(fresh, "gus");
        } catch (ChatStore.ChatException e) {
            status = e.status;
        }
        check("a removed person stays removed after a restart", status == 403);
        check("the group mute survives", cs2.groupMutedList(G, "alice").containsKey("carol"));
        check("the private messages survive", cs2.mailThread("alice", false).size() >= 3);
        check("group admin status survives", cs2.isGroupAdmin("alice") && cs2.isGroupAdmin("bob") && !cs2.isGroupAdmin("carol"));
        cs2.removeUserEverywhere("alice");
        check("a removed account loses its private messages", cs2.mailThread("alice", true).isEmpty());

        System.out.println();
        System.out.println("-- No group, no longer a group admin --");
        check("bob deletes his only group", http(port, "POST", "/api/chat/groups/delete", form("room", OPEN), bobTok)[0].equals("200"));
        check("the super-admin can no longer message him", http(port, "POST", "/api/admin/chat/mail/send", form("username", "bob", "text", "hi"), adminTok)[0].equals("400"));
        check("he disappears from the list of group admins", !http(port, "GET", "/api/admin/chat/groupadmins", null, adminTok)[1].contains("\"name\":\"bob\""));

        server.stop();
        System.out.println();
        System.out.println(failures == 0 ? "ALL CHECKS PASSED" : failures + " CHECK(S) FAILED");
    }

    private static long say(int port, AtomicLong t, String tok, String room, String text) throws Exception {
        t.addAndGet(4000);
        String[] r = http(port, "POST", "/api/chat/post", form("room", room, "text", text), tok);
        Matcher m = Pattern.compile("\"id\":(\\d+)").matcher(r[1]);
        return m.find() ? Long.parseLong(m.group(1)) : -1;
    }

    /** The groupAdmin flag shown on a message. */
    private static String flag(String json, long id) {
        Matcher m = Pattern.compile("\\{\"id\":" + id + ",\"user\".*?\"groupAdmin\":(true|false)").matcher(json);
        return m.find() ? m.group(1) : "missing";
    }

    private static String value(String json, String name) {
        Matcher m = Pattern.compile("\"" + name + "\":\"([^\"]*)\"").matcher(json);
        return m.find() ? m.group(1) : null;
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

    private static void check(String name, boolean ok) {
        if (ok) {
            System.out.println("PASS  " + name);
        } else {
            failures++;
            System.out.println("FAIL  " + name);
        }
    }
}