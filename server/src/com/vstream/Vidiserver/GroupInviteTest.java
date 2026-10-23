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

/** Tests private groups: access control, invite codes, invitations by name, members and admin rules. */
public class GroupInviteTest {

    private static int failures = 0;
    private static final long HOUR = 3600000L;

    public static void main(String[] args) throws Exception {
        final AtomicLong t = new AtomicLong(1000000000000L);
        Path dir = Files.createTempDirectory("vstream-groups");
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

        System.out.println();
        System.out.println("-- Creating groups --");
        String[] r = http(port, "POST", "/api/chat/groups/create", form("title", "Family Chat", "private", "true"), aliceTok);
        check("alice creates a private group", r[0].equals("200") && r[1].contains("\"id\":\"" + G + "\"")
                && r[1].contains("\"private\":true") && r[1].contains("\"members\":1"));
        r = http(port, "POST", "/api/chat/groups/create", form("title", "Open Club"), bobTok);
        check("a group made the old way is public", r[0].equals("200") && r[1].contains("\"private\":false"));
        r = http(port, "POST", "/api/chat/groups/create", form("title", "Guest Group", "private", "true"), gusTok);
        check("a guest cannot create a group", r[0].equals("403"));

        System.out.println();
        System.out.println("-- Only members can see a private group --");
        long secret = say(port, t, aliceTok, G, "secret plan for Sunday");
        long publicMsg = say(port, t, bobTok, "g-open-club", "hello open club");
        check("anonymous viewers get nothing", http(port, "GET", "/api/chat/messages?room=" + G, null, null)[0].equals("404"));
        check("a signed-in outsider gets nothing", http(port, "GET", "/api/chat/messages?room=" + G, null, bobTok)[0].equals("404"));
        r = http(port, "GET", "/api/chat/messages?room=" + G, null, aliceTok);
        check("a member reads it", r[0].equals("200") && r[1].contains("secret plan") && r[1].contains("\"private\":true"));
        String sid = String.valueOf(secret);
        check("an outsider cannot post", http(port, "POST", "/api/chat/post", form("room", G, "text", "hi"), bobTok)[0].equals("404"));
        check("an outsider cannot react", http(port, "POST", "/api/chat/react", form("room", G, "id", sid, "emoji", "\uD83D\uDC4D"), bobTok)[0].equals("404"));
        check("an outsider cannot type", http(port, "POST", "/api/chat/typing", form("room", G), bobTok)[0].equals("404"));
        check("an outsider cannot mark it read", http(port, "POST", "/api/chat/read", form("room", G, "upTo", sid), bobTok)[0].equals("404"));
        check("an outsider cannot pin", http(port, "POST", "/api/chat/pin", form("room", G, "id", sid), bobTok)[0].equals("404"));
        check("an outsider cannot edit or delete", http(port, "POST", "/api/chat/edit", form("room", G, "id", sid, "text", "x"), bobTok)[0].equals("404")
                && http(port, "POST", "/api/chat/delete", form("room", G, "id", sid), bobTok)[0].equals("404"));
        check("an outsider cannot ask for message info", http(port, "GET", "/api/chat/info?room=" + G + "&id=" + sid, null, bobTok)[0].equals("404"));
        check("an outsider cannot forward out of it", http(port, "POST", "/api/chat/forward", form("fromRoom", G, "toRoom", "g-open-club", "id", sid), bobTok)[0].equals("404"));
        check("an outsider cannot forward into it", http(port, "POST", "/api/chat/forward", form("fromRoom", "g-open-club", "toRoom", G, "id", String.valueOf(publicMsg)), bobTok)[0].equals("404"));
        t.addAndGet(4000);
        check("a member can forward a public message into it", http(port, "POST", "/api/chat/forward", form("fromRoom", "g-open-club", "toRoom", G, "id", String.valueOf(publicMsg)), aliceTok)[0].equals("200"));

        r = http(port, "GET", "/api/chat/search?q=secret", null, bobTok);
        check("search does not find it for an outsider", r[1].contains("\"results\":[]"));
        r = http(port, "GET", "/api/chat/search?q=secret", null, null);
        check("nor for a viewer", r[1].contains("\"results\":[]"));
        r = http(port, "GET", "/api/chat/search?q=secret", null, aliceTok);
        check("a member finds it", r[1].contains("secret plan") && r[1].contains("\"roomTitle\":\"Family Chat\""));
        r = http(port, "GET", "/api/chat/search?q=secret&room=" + G, null, bobTok);
        check("searching inside it as an outsider", r[0].equals("404"));

        r = http(port, "GET", "/api/chat/rooms", null, aliceTok);
        check("the member's room list shows it", r[1].contains("\"id\":\"" + G + "\""));
        r = http(port, "GET", "/api/chat/rooms", null, bobTok);
        check("an outsider's list does not", !r[1].contains(G) && r[1].contains("g-open-club"));
        r = http(port, "GET", "/api/chat/rooms", null, null);
        check("neither does a viewer's", !r[1].contains(G));
        say(port, t, aliceTok, G, "more secret chatter one");
        say(port, t, aliceTok, G, "more secret chatter two");
        say(port, t, aliceTok, "g-general", "one public message");
        r = http(port, "GET", "/api/chat/top", null, null);
        check("the busiest-room excerpt on Home never uses a private group", !r[1].contains(G) && !r[1].contains("secret"));
        r = http(port, "GET", "/api/chat/unread?rooms=" + G + ":0,g-general:0", null, bobTok);
        check("unread counts skip a private group for an outsider", !r[1].contains(G));
        r = http(port, "GET", "/api/chat/unread?rooms=" + G + ":0", null, aliceTok);
        check("and count it for a member", r[1].contains("\"" + G + "\":"));
        check("a public group still works for everyone", http(port, "POST", "/api/chat/post", form("room", "g-open-club", "text", "still open"), carolTok)[0].equals("200"));

        System.out.println();
        System.out.println("-- Invite codes --");
        r = http(port, "POST", "/api/chat/groups/invite/create", form("room", G), bobTok);
        check("an outsider cannot make an invite", r[0].equals("404"));
        r = http(port, "POST", "/api/chat/groups/invite/create", form("room", "g-open-club"), bobTok);
        check("a public group has no invites", r[0].equals("400"));
        r = http(port, "POST", "/api/chat/groups/invite/create", form("room", G), aliceTok);
        check("the owner makes an invite", r[0].equals("200"));
        String code = value(r[1], "code");
        check("the code looks like XXXXX-XXXXX", code != null && code.matches("[2-9A-HJKMNP-Z]{5}-[2-9A-HJKMNP-Z]{5}"));
        String raw = code.replace("-", "");
        check("it comes with a link and a deep link", r[1].contains("/join/" + raw) && r[1].contains("vstream://join?code=" + raw)
                && r[1].contains("\"maxUses\":1"));
        long expires = (long) number(r[1], "\"expires\":(\\d+)");
        check("it expires in about 7 days", Math.abs(expires - (t.get() + 7 * 24 * HOUR)) < 5000);
        r = http(port, "GET", "/api/chat/invite?code=" + code, null, null);
        check("anyone with the code can see the group name, owner and size, and nothing else",
                r[0].equals("200") && r[1].contains("\"title\":\"Family Chat\"") && r[1].contains("\"owner\":\"alice\"")
                        && r[1].contains("\"members\":1") && !r[1].contains("secret"));
        check("a joining attempt without a sign-in", http(port, "POST", "/api/chat/groups/join", form("code", code), null)[0].equals("401"));
        r = http(port, "POST", "/api/chat/groups/join", form("code", raw.toLowerCase()), bobTok);
        check("bob joins, even with lower case and no dash", r[0].equals("200") && r[1].contains("\"room\":\"" + G + "\""));
        check("bob can now read the group", http(port, "GET", "/api/chat/messages?room=" + G, null, bobTok)[1].contains("secret plan"));
        check("a single-use code is then used up", http(port, "POST", "/api/chat/groups/join", form("code", code), carolTok)[0].equals("404"));
        check("the preview agrees", http(port, "GET", "/api/chat/invite?code=" + code, null, null)[0].equals("404"));

        r = http(port, "POST", "/api/chat/groups/invite/create", form("room", G, "uses", "2", "days", "3"), aliceTok);
        String multi = value(r[1], "code");
        check("a multi-use code joins two people", http(port, "POST", "/api/chat/groups/join", form("code", multi), carolTok)[0].equals("200")
                && http(port, "POST", "/api/chat/groups/join", form("code", multi), daveTok)[0].equals("200"));
        check("and not a third", http(port, "POST", "/api/chat/groups/join", form("code", multi), erinTok)[0].equals("404"));
        r = http(port, "POST", "/api/chat/groups/invite/create", form("room", G, "days", "1", "uses", "5"), aliceTok);
        String shortLived = value(r[1], "code");
        r = http(port, "POST", "/api/chat/groups/invite/create", form("room", G, "uses", "5"), aliceTok);
        String toRevoke = value(r[1], "code");
        r = http(port, "GET", "/api/chat/groups/invites?room=" + G, null, aliceTok);
        check("the owner sees the active invites", r[0].equals("200") && r[1].contains(shortLived) && r[1].contains(toRevoke) && !r[1].contains(multi));
        check("a member cannot list invites", http(port, "GET", "/api/chat/groups/invites?room=" + G, null, bobTok)[0].equals("403"));
        check("a member cannot revoke one", http(port, "POST", "/api/chat/groups/invite/revoke", form("room", G, "code", toRevoke), bobTok)[0].equals("403"));
        check("the owner revokes one", http(port, "POST", "/api/chat/groups/invite/revoke", form("room", G, "code", toRevoke), aliceTok)[0].equals("200"));
        check("a revoked code does not work", http(port, "POST", "/api/chat/groups/join", form("code", toRevoke), gusTok)[0].equals("404"));
        t.addAndGet(25 * HOUR);
        check("an expired code does not work", http(port, "POST", "/api/chat/groups/join", form("code", shortLived), gusTok)[0].equals("404"));
        check("a code that does not exist", http(port, "POST", "/api/chat/groups/join", form("code", "AAAAA-BBBBB"), gusTok)[0].equals("404"));

        System.out.println();
        System.out.println("-- The invite web page --");
        r = http(port, "POST", "/api/chat/groups/invite/create", form("room", G, "uses", "5"), aliceTok);
        String pageCode = value(r[1], "code").replace("-", "");
        r = http(port, "GET", "/join/" + pageCode, null, null);
        check("the page shows the group and an Open in VStream button",
                r[0].equals("200") && r[2].contains("text/html") && r[1].contains("Family Chat") && r[1].contains("vstream://join?code=" + pageCode));
        r = http(port, "GET", "/join/NOTREALCODE", null, null);
        check("a bad code gets a polite page", r[0].equals("404") && r[1].contains("no longer valid"));
        t.addAndGet(4000);
        r = http(port, "POST", "/api/chat/groups/create", form("title", "<b>Hi</b> there", "private", "true"), aliceTok);
        String xssRoom = value(r[1], "id");
        r = http(port, "POST", "/api/chat/groups/invite/create", form("room", xssRoom), aliceTok);
        String xssCode = value(r[1], "code").replace("-", "");
        r = http(port, "GET", "/join/" + xssCode, null, null);
        check("a group name with HTML is shown as text, not as markup", r[1].contains("&lt;b&gt;Hi&lt;/b&gt;") && !r[1].contains("<b>Hi</b>"));
        check("the page forbids scripts", r[3].contains("default-src 'none'"));

        System.out.println();
        System.out.println("-- Invitations by name --");
        r = http(port, "POST", "/api/chat/groups/invite/user", form("room", G, "username", "gus"), bobTok);
        check("a member cannot invite people", r[0].equals("403"));
        check("inviting a user that does not exist", http(port, "POST", "/api/chat/groups/invite/user", form("room", G, "username", "nobody"), aliceTok)[0].equals("404"));
        check("inviting someone who is already in", http(port, "POST", "/api/chat/groups/invite/user", form("room", G, "username", "bob"), aliceTok)[0].equals("400"));
        check("the owner invites gus", http(port, "POST", "/api/chat/groups/invite/user", form("room", G, "username", "gus"), aliceTok)[0].equals("200"));
        check("inviting twice does no harm", http(port, "POST", "/api/chat/groups/invite/user", form("room", G, "username", "gus"), aliceTok)[0].equals("200"));
        r = http(port, "GET", "/api/chat/invitations", null, gusTok);
        check("gus sees the invitation", r[1].contains("\"room\":\"" + G + "\"") && r[1].contains("\"from\":\"alice\"") && r[1].contains("Family Chat") && count(r[1], "\"room\"") == 1);
        check("invitations need a sign-in", http(port, "GET", "/api/chat/invitations", null, null)[0].equals("401"));
        check("gus cannot read yet", http(port, "GET", "/api/chat/messages?room=" + G, null, gusTok)[0].equals("404"));
        check("gus declines", http(port, "POST", "/api/chat/invitations/decline", form("room", G), gusTok)[0].equals("200"));
        check("the list is empty", http(port, "GET", "/api/chat/invitations", null, gusTok)[1].contains("\"invitations\":[]"));
        check("declining again", http(port, "POST", "/api/chat/invitations/decline", form("room", G), gusTok)[0].equals("404"));
        http(port, "POST", "/api/chat/groups/invite/user", form("room", G, "username", "gus"), aliceTok);
        check("accepting an invitation you do not have", http(port, "POST", "/api/chat/invitations/accept", form("room", G), erinTok)[0].equals("404"));
        r = http(port, "POST", "/api/chat/invitations/accept", form("room", G), gusTok);
        check("gus accepts", r[0].equals("200") && r[1].contains("\"room\":\"" + G + "\""));
        check("a guest can read and post in a private group", http(port, "GET", "/api/chat/messages?room=" + G, null, gusTok)[0].equals("200")
                && say(port, t, gusTok, G, "hello from the guest") > 0);
        check("the invitation is gone", http(port, "GET", "/api/chat/invitations", null, gusTok)[1].contains("\"invitations\":[]"));

        System.out.println();
        System.out.println("-- Members, removing and leaving --");
        r = http(port, "GET", "/api/chat/groups/members?room=" + G, null, bobTok);
        check("a member sees the member list, owner first", r[0].equals("200") && r[1].contains("\"owner\":\"alice\"")
                && r[1].indexOf("\"name\":\"alice\"") < r[1].indexOf("\"name\":\"bob\"") && r[1].contains("\"name\":\"gus\"")
                && r[1].contains("\"role\":\"GUEST\"") && count(r[1], "\"name\"") == 5);
        check("an outsider cannot", http(port, "GET", "/api/chat/groups/members?room=" + G, null, erinTok)[0].equals("404"));
        check("a public group has no member list", http(port, "GET", "/api/chat/groups/members?room=g-open-club", null, bobTok)[0].equals("400"));
        check("a member cannot remove someone", http(port, "POST", "/api/chat/groups/remove", form("room", G, "username", "carol"), bobTok)[0].equals("403"));
        check("the owner cannot be removed", http(port, "POST", "/api/chat/groups/remove", form("room", G, "username", "alice"), aliceTok)[0].equals("400"));
        check("removing someone who is not in", http(port, "POST", "/api/chat/groups/remove", form("room", G, "username", "zed"), aliceTok)[0].equals("404"));
        check("the owner removes carol", http(port, "POST", "/api/chat/groups/remove", form("room", G, "username", "carol"), aliceTok)[0].equals("200"));
        check("carol is locked out", http(port, "GET", "/api/chat/messages?room=" + G, null, carolTok)[0].equals("404"));
        check("dave leaves", http(port, "POST", "/api/chat/groups/leave", form("room", G), daveTok)[0].equals("200"));
        check("dave is locked out", http(port, "GET", "/api/chat/messages?room=" + G, null, daveTok)[0].equals("404"));
        check("the owner cannot leave", http(port, "POST", "/api/chat/groups/leave", form("room", G), aliceTok)[0].equals("400"));
        check("an outsider cannot leave", http(port, "POST", "/api/chat/groups/leave", form("room", G), erinTok)[0].equals("404"));

        System.out.println();
        System.out.println("-- Who can call whom (for the call add-on) --");
        check("alice and bob share a private group", cs.sharesPrivateGroup("alice", "bob"));
        check("bob and gus share one", cs.sharesPrivateGroup("bob", "gus"));
        check("alice and carol no longer do", !cs.sharesPrivateGroup("alice", "carol"));
        check("being in the same public group does not count", !cs.sharesPrivateGroup("alice", "erin"));
        check("it does not matter how the name is written", cs.sharesPrivateGroup("ALICE", "Bob"));

        System.out.println();
        System.out.println("-- The admin account and deleting --");
        check("the admin cannot read a private group", http(port, "GET", "/api/chat/messages?room=" + G, null, adminTok)[0].equals("404"));
        r = http(port, "GET", "/api/admin/chat/groups", null, adminTok);
        check("the admin sees a list of groups without any messages",
                r[0].equals("200") && r[1].contains("\"id\":\"" + G + "\"") && r[1].contains("\"private\":true") && !r[1].contains("secret"));
        check("a normal user cannot use the admin list", http(port, "GET", "/api/admin/chat/groups", null, aliceTok)[0].equals("403"));
        r = http(port, "POST", "/api/chat/groups/create", form("title", "Temp Group", "private", "true"), bobTok);
        String temp = value(r[1], "id");
        r = http(port, "POST", "/api/chat/groups/invite/create", form("room", temp, "uses", "3"), bobTok);
        String tempCode = value(r[1], "code");
        http(port, "POST", "/api/chat/groups/invite/user", form("room", temp, "username", "erin"), bobTok);
        check("an outsider cannot delete it", http(port, "POST", "/api/chat/groups/delete", form("room", temp), carolTok)[0].equals("404"));
        check("the owner deletes it", http(port, "POST", "/api/chat/groups/delete", form("room", temp), bobTok)[0].equals("200"));
        check("its invite code stops working", http(port, "POST", "/api/chat/groups/join", form("code", tempCode), daveTok)[0].equals("404"));
        check("its pending invitation disappears", http(port, "GET", "/api/chat/invitations", null, erinTok)[1].contains("\"invitations\":[]"));
        r = http(port, "POST", "/api/chat/groups/create", form("title", "Admin Target", "private", "true"), daveTok);
        String target = value(r[1], "id");
        check("the admin can delete any private group", http(port, "POST", "/api/chat/groups/delete", form("room", target), adminTok)[0].equals("200"));

        System.out.println();
        System.out.println("-- Guessing codes --");
        int wrong = 0;
        boolean limited = false;
        for (int i = 0; i < 14; i++) {
            String status = http(port, "POST", "/api/chat/groups/join", form("code", "ZZZZZ-ZZZ" + (i % 9 + 1) + "Z"), erinTok)[0];
            if (status.equals("404")) {
                wrong++;
            } else if (status.equals("429")) {
                limited = true;
            }
        }
        check("wrong guesses are slowed down after a few tries", limited && wrong >= 5);
        String ipLimited = "";
        for (int i = 0; i < 40 && !ipLimited.equals("429"); i++) {
            ipLimited = http(port, "GET", "/api/chat/invite?code=AAAAA-BBBBB", null, null)[0];
        }
        check("so are previews of codes", ipLimited.equals("429"));

        System.out.println();
        System.out.println("-- Saving and loading --");
        r = http(port, "POST", "/api/chat/groups/invite/create", form("room", G, "uses", "5"), aliceTok);
        String saved = value(r[1], "code");
        http(port, "POST", "/api/chat/groups/invite/user", form("room", G, "username", "dave"), aliceTok);
        cs.flushIfDirty();
        ChatStore cs2 = new ChatStore(chatFile, t::get);
        cs2.init();
        check("the private flag, members and owner survive a restart", cs2.isPrivate(G) && cs2.canAccess(G, "bob") && !cs2.canAccess(G, "carol")
                && cs2.members(G).get(0).owner && "alice".equals(cs2.members(G).get(0).name));
        check("a valid invite works after a restart", cs2.previewInvite(saved) != null && "Family Chat".equals(cs2.previewInvite(saved).title));
        check("a pending invitation survives", cs2.invitationsFor("dave").size() == 1);
        check("the public group is still public", !cs2.isPrivate("g-open-club") && cs2.canAccess("g-open-club", null));
        check("the member counts survive", cs2.info(G, G).members == cs2.members(G).size());
        String joined = cs2.joinByCode(saved, "erin");
        check("joining after a restart works and uses the code", G.equals(joined) && cs2.canAccess(G, "erin")
                && cs2.listInvites(G, "alice").size() >= 1);

        System.out.println();
        System.out.println("-- Removing an account --");
        cs2.removeUserEverywhere("gus");
        check("a removed account leaves every group", !cs2.canAccess(G, "gus") && !cs2.sharesPrivateGroup("bob", "gus"));
        cs2.removeUserEverywhere("dave");
        check("and loses its pending invitations", cs2.invitationsFor("dave").isEmpty());
        check("the owner is never removed that way", cs2.canAccess(G, "alice"));

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

    private static String value(String json, String name) {
        Matcher m = Pattern.compile("\"" + name + "\":\"([^\"]*)\"").matcher(json);
        return m.find() ? m.group(1) : null;
    }

    private static double number(String json, String pattern) {
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
        String type = c.getHeaderField("Content-Type");
        String csp = c.getHeaderField("Content-Security-Policy");
        c.disconnect();
        return new String[]{String.valueOf(code), sb.toString(), type == null ? "" : type, csp == null ? "" : csp};
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