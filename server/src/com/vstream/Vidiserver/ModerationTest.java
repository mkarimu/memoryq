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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Tests reports, suspension, reinstating, deleting accounts, group suspensions and the Banned list. */
public class ModerationTest {

    private static int failures = 0;
    private static final long HOUR = 3600000L;
    private static final List<String> released = Collections.synchronizedList(new ArrayList<String>());
    private static final List<String> kicks = Collections.synchronizedList(new ArrayList<String>());
    private static final Map<String, String> tok = new HashMap<String, String>();
    private static final AtomicLong t = new AtomicLong(1000000000000L);

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("vstream-moderation");
        Path page = dir.resolve("admin.html");
        Files.write(page, "<html>x</html>".getBytes(StandardCharsets.UTF_8));
        LiveChannelRegistry reg = new LiveChannelRegistry();
        UserStore us = new UserStore(dir.resolve("users.txt"), reg, kicks::add);
        us.init(null);
        for (String n : new String[]{"alice", "bob", "carol", "dave", "erin", "zed", "wes"}) {
            us.createUser(n, "password123", UserStore.Role.CHATTER);
        }
        us.createUser("gus", "password123", UserStore.Role.GUEST);
        us.createUser("gus2", "password123", UserStore.Role.GUEST);
        UserStore.Info bcast = null;
        try {
            bcast = us.createUser("bcast", "password123", UserStore.Role.BROADCASTER);
        } catch (Exception e) {
            bcast = null;
        }
        Path chatFile = dir.resolve("chat.txt");
        ChatStore cs = new ChatStore(chatFile, t::get);
        cs.init();
        SessionStore ss = new SessionStore();
        for (String n : new String[]{"alice", "bob", "carol", "dave", "erin", "zed", "wes", "gus", "gus2"}) {
            tok.put(n, ss.create(n, n.startsWith("gus") ? UserStore.Role.GUEST : UserStore.Role.CHATTER));
        }
        tok.put("admin", ss.create("admin", UserStore.Role.ADMIN));
        Path modFile = dir.resolve("moderation.txt");
        ModerationService mod = new ModerationService(modFile, t::get, cs, us, ss,
                (name, why) -> released.add(name + ":" + why));
        mod.init();
        ChatApi chatApi = new ChatApi(cs, us, ss, reg, id -> false);
        chatApi.setModeration(mod);
        AccountApi accountApi = new AccountApi(us, ss, new Limiter(), reg, page, kicks::add, id -> false);
        accountApi.setModeration(mod);
        ChannelListHttpServer server = new ChannelListHttpServer(reg, 0);
        server.setAccountApi(accountApi);
        server.setChatApi(chatApi);
        server.start();
        int port = server.getPort();
        final String G = "g-family-chat";
        final String OPEN = "g-open-club";
        final String admin = tok.get("admin");

        System.out.println();
        System.out.println("-- Setting up --");
        check("alice makes a private group", http(port, "POST", "/api/chat/groups/create", form("title", "Family Chat", "private", "true"), tok.get("alice"))[0].equals("200"));
        check("bob makes a public one", http(port, "POST", "/api/chat/groups/create", form("title", "Open Club"), tok.get("bob"))[0].equals("200"));
        String multi = newCode(port, G, 20);
        for (String n : new String[]{"bob", "carol", "dave", "zed"}) {
            check(n + " joins the private group", http(port, "POST", "/api/chat/groups/join", form("code", multi), tok.get(n))[0].equals("200"));
        }
        check("zed makes a private group of his own", http(port, "POST", "/api/chat/groups/create", form("title", "Zed Club", "private", "true"), tok.get("zed"))[0].equals("200"));

        System.out.println();
        System.out.println("-- Reporting a message --");
        long m1 = say(port, "carol", G, "c1");
        long m2 = say(port, "bob", G, "b1");
        long m3 = say(port, "carol", G, "c2");
        long m4 = say(port, "dave", G, "d1");
        long m5 = say(port, "carol", G, "c3");
        long m6 = say(port, "carol", G, "c4");
        say(port, "bob", G, "later message");
        String[] r = report(port, "bob", "message", G, m6, "", "harassment", "keeps insulting me");
        check("bob reports carol's message", r[0].equals("200") && r[1].contains("\"ok\":true"));
        long rep1 = (long) number(r[1], "\"id\":(\\d+)");
        String list = http(port, "GET", "/api/admin/chat/reports?status=open", null, admin)[1];
        String obj = reportObj(list, rep1);
        check("the super-admin sees who, about whom, why and where",
                obj.contains("\"reporter\":\"bob\"") && obj.contains("\"target\":\"carol\"") && obj.contains("\"reason\":\"harassment\"")
                        && obj.contains("\"note\":\"keeps insulting me\"") && obj.contains("\"private\":true")
                        && obj.contains("\"roomTitle\":\"Family Chat\"") && obj.contains("\"owner\":\"alice\"") && obj.contains("\"status\":\"open\""));
        check("it holds the message and the 4 before it, oldest first",
                in(obj, "b1") < in(obj, "c2") && in(obj, "c2") < in(obj, "d1") && in(obj, "d1") < in(obj, "c3") && in(obj, "c3") < in(obj, "c4")
                        && in(obj, "b1") > 0);
        check("but not older or newer messages", in(obj, "c1") < 0 && in(obj, "later message") < 0);
        http(port, "POST", "/api/chat/edit", form("room", G, "id", String.valueOf(m6), "text", "edited afterwards"), tok.get("carol"));
        obj = reportObj(http(port, "GET", "/api/admin/chat/reports?status=open", null, admin)[1], rep1);
        check("later edits do not change the snapshot", in(obj, "c4") > 0 && in(obj, "edited afterwards") < 0);
        check("the super-admin still cannot browse the group", http(port, "GET", "/api/chat/messages?room=" + G, null, admin)[0].equals("404"));

        System.out.println();
        System.out.println("-- Who can report, and limits --");
        check("an outsider cannot report a private-group message", report(port, "erin", "message", G, m2, "", "spam", "")[0].equals("404"));
        check("an outsider cannot report a user in it", report(port, "erin", "user", G, 0, "carol", "spam", "")[0].equals("404"));
        check("reporting needs a sign-in", http(port, "POST", "/api/chat/report", form("type", "message", "room", G, "id", String.valueOf(m2), "reason", "spam"), null)[0].equals("401"));
        check("an unknown reason", report(port, "bob", "message", G, m2, "", "boring", "")[0].equals("400"));
        check("no reason", report(port, "bob", "message", G, m2, "", "", "")[0].equals("400"));
        check("an unknown type", report(port, "bob", "banana", G, m2, "", "spam", "")[0].equals("400"));
        check("you cannot report yourself", report(port, "carol", "message", G, m1, "", "spam", "")[0].equals("400"));
        long adminMsg = say(port, "admin", "g-general", "hello from the admin");
        check("the super-admin cannot be reported", report(port, "alice", "message", "g-general", adminMsg, "", "abuse", "")[0].equals("400"));
        check("a message that does not exist", report(port, "bob", "message", G, 999999, "", "spam", "")[0].equals("404"));
        long oops = say(port, "carol", G, "oops");
        http(port, "POST", "/api/chat/delete", form("room", G, "id", String.valueOf(oops)), tok.get("carol"));
        check("a deleted message", report(port, "bob", "message", G, oops, "", "spam", "")[0].equals("404"));
        r = report(port, "bob", "message", G, m6, "", "harassment", "again");
        check("the same report twice in a day", r[0].equals("429") && r[1].contains("already reported"));
        t.addAndGet(25 * HOUR);
        check("a day later it is allowed again", report(port, "bob", "message", G, m6, "", "harassment", "still happening")[0].equals("200"));
        r = report(port, "bob", "user", G, 0, "carol", "spam", "");
        check("bob reports the person", r[0].equals("200"));
        obj = reportObj(http(port, "GET", "/api/admin/chat/reports?status=open", null, admin)[1], number(r[1], "\"id\":(\\d+)"));
        check("a person report shows that person's last messages in that group only",
                obj.contains("\"type\":\"user\"") && obj.contains("\"messageId\":0") && in(obj, "c2") > 0 && in(obj, "c3") > 0 && in(obj, "b1") < 0 && in(obj, "d1") < 0);
        r = report(port, "dave", "group", G, 0, "", "other", "");
        obj = reportObj(http(port, "GET", "/api/admin/chat/reports?status=open", null, admin)[1], number(r[1], "\"id\":(\\d+)"));
        check("a group report points at the group admin", r[0].equals("200") && obj.contains("\"type\":\"group\"") && obj.contains("\"target\":\"alice\""));
        check("an outsider cannot report the group", report(port, "erin", "group", G, 0, "", "other", "")[0].equals("404"));
        check("a public group can be reported by anyone", report(port, "erin", "group", OPEN, 0, "", "spam", "")[0].equals("200"));
        List<Long> openIds = new ArrayList<Long>();
        for (int i = 1; i <= 12; i++) {
            openIds.add(say(port, "carol", OPEN, "open message " + i));
        }
        int okCount = 0;
        int limited = 0;
        for (int i = 0; i < 12; i++) {
            String code = report(port, "wes", "message", OPEN, openIds.get(i), "", "spam", "")[0];
            if (code.equals("200")) {
                okCount++;
            } else if (code.equals("429")) {
                limited++;
            }
        }
        check("ten reports an hour are allowed", okCount == 10);
        check("then they are slowed down", limited == 2);
        t.addAndGet(61 * 60000L);
        check("an hour later reporting works again", report(port, "wes", "message", OPEN, openIds.get(10), "", "spam", "")[0].equals("200"));

        System.out.println();
        System.out.println("-- Who can see the reports --");
        check("anonymous", http(port, "GET", "/api/admin/chat/reports", null, null)[0].equals("401"));
        check("a normal user", http(port, "GET", "/api/admin/chat/reports", null, tok.get("bob"))[0].equals("403"));
        check("actions need the super-admin too", http(port, "POST", "/api/admin/chat/reports/dismiss", form("id", String.valueOf(rep1)), tok.get("alice"))[0].equals("403"));
        check("and so does the log", http(port, "GET", "/api/admin/chat/audit", null, tok.get("bob"))[0].equals("403"));

        System.out.println();
        System.out.println("-- Dismiss, warn and delete a message --");
        long x1 = say(port, "carol", G, "report me 1");
        long rDismiss = (long) number(report(port, "dave", "message", G, x1, "", "spam", "")[1], "\"id\":(\\d+)");
        check("the super-admin dismisses a report", http(port, "POST", "/api/admin/chat/reports/dismiss", form("id", String.valueOf(rDismiss), "note", "not a problem"), admin)[0].equals("200"));
        check("it moves to the dismissed list", reportObj(http(port, "GET", "/api/admin/chat/reports?status=dismissed", null, admin)[1], rDismiss).contains("\"status\":\"dismissed\"")
                && reportObj(http(port, "GET", "/api/admin/chat/reports?status=open", null, admin)[1], rDismiss).isEmpty());
        check("a report that does not exist", http(port, "POST", "/api/admin/chat/reports/dismiss", form("id", "9999"), admin)[0].equals("404"));

        long x2 = say(port, "carol", G, "report me 2");
        long rWarn = (long) number(report(port, "bob", "message", G, x2, "", "abuse", "")[1], "\"id\":(\\d+)");
        check("the super-admin warns the group admin", http(port, "POST", "/api/admin/chat/reports/warn", form("id", String.valueOf(rWarn), "text", "Please keep your group friendly"), admin)[0].equals("200"));
        check("the group admin gets it privately", http(port, "GET", "/api/chat/adminmail", null, tok.get("alice"))[1].contains("Please keep your group friendly"));
        check("it is not in the group", !http(port, "GET", "/api/chat/messages?room=" + G, null, tok.get("alice"))[1].contains("Please keep your group friendly"));
        check("the report is handled", reportObj(http(port, "GET", "/api/admin/chat/reports?status=handled", null, admin)[1], rWarn).contains("warned the group admin alice"));
        long gm = say(port, "carol", "g-general", "a message in general");
        long rGen = (long) number(report(port, "alice", "message", "g-general", gm, "", "spam", "")[1], "\"id\":(\\d+)");
        check("a room with no group admin cannot be warned", http(port, "POST", "/api/admin/chat/reports/warn", form("id", String.valueOf(rGen), "text", "hello"), admin)[0].equals("400"));
        check("an empty warning", http(port, "POST", "/api/admin/chat/reports/warn", form("id", String.valueOf(rWarn), "text", ""), admin)[0].equals("400"));

        long x3 = say(port, "carol", G, "bad words here");
        long rDel = (long) number(report(port, "bob", "message", G, x3, "", "abuse", "")[1], "\"id\":(\\d+)");
        check("the super-admin deletes the reported message", http(port, "POST", "/api/admin/chat/reports/deletemessage", form("id", String.valueOf(rDel)), admin)[0].equals("200"));
        String view = http(port, "GET", "/api/chat/messages?room=" + G, null, tok.get("alice"))[1];
        check("members see it as deleted", !view.contains("bad words here") && view.contains("\"deleted\":true"));
        check("deleting it again", http(port, "POST", "/api/admin/chat/reports/deletemessage", form("id", String.valueOf(rDel)), admin)[0].equals("404"));
        long rUser = (long) number(report(port, "alice", "user", G, 0, "carol", "spam", "")[1], "\"id\":(\\d+)");
        t.addAndGet(25 * HOUR);
        long rUser2 = (long) number(report(port, "dave", "user", G, 0, "carol", "spam", "")[1], "\"id\":(\\d+)");
        check("a person report has no message to delete", http(port, "POST", "/api/admin/chat/reports/deletemessage", form("id", String.valueOf(rUser2)), admin)[0].equals("400"));

        System.out.println();
        System.out.println("-- Suspending an account --");
        released.clear();
        String carolToken = tok.get("carol");
        check("carol's session works before", http(port, "GET", "/api/chat/prefs", null, carolToken)[0].equals("200"));
        check("a normal user cannot suspend", http(port, "POST", "/api/admin/chat/accounts/suspend", form("username", "carol", "hours", "2"), tok.get("bob"))[0].equals("403"));
        check("the super-admin suspends carol from the report", http(port, "POST", "/api/admin/chat/reports/suspend",
                form("id", String.valueOf(rUser), "hours", "2", "notice", "Please read the rules.", "reason", "private reason"), admin)[0].equals("200"));
        check("her claimed channel was released", released.toString().contains("carol:"));
        check("her old session no longer works", http(port, "GET", "/api/chat/prefs", null, carolToken)[0].equals("401"));
        check("the account is disabled", us.info("carol").disabled);
        r = http(port, "POST", "/api/login", form("username", "carol", "password", "password123"), null);
        check("signing in shows the suspension, how long and the message", r[0].equals("403") && r[1].contains("suspended") && r[1].contains("2 hours") && r[1].contains("Please read the rules."));
        check("the private note is never shown", !r[1].contains("private reason"));
        check("the wrong password shows the same notice", http(port, "POST", "/api/login", form("username", "carol", "password", "wrong-password"), null)[0].equals("403"));
        check("other accounts still get the normal error", http(port, "POST", "/api/login", form("username", "bob", "password", "wrong-password"), null)[0].equals("401"));
        String sus = http(port, "GET", "/api/admin/chat/suspensions", null, admin)[1];
        check("the super-admin sees the suspension with the private note", sus.contains("\"username\":\"carol\"") && sus.contains("\"hoursLeft\":2")
                && sus.contains("\"notice\":\"Please read the rules.\"") && sus.contains("\"reason\":\"private reason\""));
        check("the report is handled", reportObj(http(port, "GET", "/api/admin/chat/reports?status=handled", null, admin)[1], rUser).contains("suspended carol"));
        check("nothing is lifted too early", mod.tick() == 0 && us.info("carol").disabled);
        t.addAndGet(2 * HOUR + 60000);
        check("when the time is up it lifts itself", mod.tick() >= 1 && !us.info("carol").disabled);
        r = http(port, "POST", "/api/login", form("username", "carol", "password", "password123"), null);
        check("carol can sign in again", r[0].equals("200") && r[1].contains("\"token\""));
        check("the suspension list is empty", http(port, "GET", "/api/admin/chat/suspensions", null, admin)[1].contains("\"suspensions\":[]"));
        check("an indefinite suspension", http(port, "POST", "/api/admin/chat/accounts/suspend", form("username", "carol", "hours", "0", "notice", "", "reason", ""), admin)[0].equals("200"));
        check("its message says until reinstated", http(port, "POST", "/api/login", form("username", "carol", "password", "password123"), null)[1].contains("until the administrator reinstates it"));
        t.addAndGet(1000 * HOUR);
        mod.tick();
        check("it is not lifted by time", us.info("carol").disabled);
        check("the super-admin reinstates her", http(port, "POST", "/api/admin/chat/accounts/lift", form("username", "carol"), admin)[0].equals("200") && !us.info("carol").disabled);
        check("reinstating twice", http(port, "POST", "/api/admin/chat/accounts/lift", form("username", "carol"), admin)[0].equals("404"));
        us.setDisabled("dave", true);
        check("an account the admin had already disabled", http(port, "POST", "/api/admin/chat/accounts/suspend", form("username", "dave", "hours", "1"), admin)[0].equals("200"));
        t.addAndGet(HOUR + 60000);
        mod.tick();
        check("stays disabled when the suspension ends", us.info("dave").disabled);
        us.setDisabled("dave", false);
        tok.put("dave", ss.create("dave", UserStore.Role.CHATTER));
        check("the admin account cannot be suspended", http(port, "POST", "/api/admin/chat/accounts/suspend", form("username", "admin", "hours", "1"), admin)[0].equals("400"));
        check("an unknown account", http(port, "POST", "/api/admin/chat/accounts/suspend", form("username", "nobody", "hours", "1"), admin)[0].equals("404"));
        if (bcast != null && bcast.channelId != null) {
            String chan = bcast.channelId;
            check("the broadcaster's channel exists before", reg.getChannel(chan) != null);
            kicks.clear();
            check("suspending a broadcaster", http(port, "POST", "/api/admin/chat/accounts/suspend", form("username", "bcast", "hours", "1"), admin)[0].equals("200"));
            check("removes the channel and kicks the publisher", reg.getChannel(chan) == null && kicks.contains(chan));
            check("reinstating brings the channel back", http(port, "POST", "/api/admin/chat/accounts/lift", form("username", "bcast"), admin)[0].equals("200") && reg.getChannel(chan) != null);
        } else {
            System.out.println("SKIP  broadcaster checks (the test could not create a broadcaster account)");
        }

        System.out.println();
        System.out.println("-- Deleting an account --");
        long z1 = say(port, "zed", G, "zed was here");
        check("a normal user cannot delete accounts", http(port, "POST", "/api/admin/chat/accounts/delete", form("username", "wes"), tok.get("bob"))[0].equals("403"));
        r = http(port, "POST", "/api/admin/chat/accounts/delete", form("username", "zed"), admin);
        check("the super-admin deletes zed, and his group goes too", r[0].equals("200") && r[1].contains("\"groupsDeleted\":1"));
        check("the account is gone", us.info("zed") == null && http(port, "POST", "/api/login", form("username", "zed", "password", "password123"), null)[0].equals("401"));
        check("his group is gone", http(port, "GET", "/api/chat/messages?room=g-zed-club", null, tok.get("alice"))[0].equals("404"));
        String members = http(port, "GET", "/api/chat/groups/members?room=" + G, null, tok.get("alice"))[1];
        check("he is out of the member list", !members.contains("\"name\":\"zed\""));
        view = http(port, "GET", "/api/chat/messages?room=" + G, null, tok.get("alice"))[1];
        check("his old messages stay", view.contains("zed was here"));
        check("his old session is dead", http(port, "GET", "/api/chat/prefs", null, tok.get("zed"))[0].equals("401"));
        check("deleting the admin", http(port, "POST", "/api/admin/chat/accounts/delete", form("username", "admin"), admin)[0].equals("400"));
        check("deleting someone who does not exist", http(port, "POST", "/api/admin/chat/accounts/delete", form("username", "nobody"), admin)[0].equals("404"));
        if (bcast != null) {
            check("a broadcaster cannot be deleted", http(port, "POST", "/api/admin/chat/accounts/delete", form("username", "bcast"), admin)[0].equals("400"));
        }
        check("a guest can be deleted", http(port, "POST", "/api/admin/chat/accounts/delete", form("username", "gus2"), admin)[0].equals("200") && us.info("gus2") == null);
        long w1 = say(port, "wes", OPEN, "wes says something");
        long rWes = (long) number(report(port, "dave", "message", OPEN, w1, "", "illegal", "")[1], "\"id\":(\\d+)");
        check("an account can be deleted from a report", http(port, "POST", "/api/admin/chat/reports/deleteaccount", form("id", String.valueOf(rWes)), admin)[0].equals("200") && us.info("wes") == null);
        obj = reportObj(http(port, "GET", "/api/admin/chat/reports?status=handled", null, admin)[1], rWes);
        check("the report remembers that the account was deleted", obj.contains("\"targetExists\":false") && obj.contains("deleted the account wes"));

        System.out.println();
        System.out.println("-- Suspending and banning inside a group --");
        String c1 = newCode(port, G, 20);
        check("erin joins", http(port, "POST", "/api/chat/groups/join", form("code", c1), tok.get("erin"))[0].equals("200"));
        check("a member cannot suspend", http(port, "POST", "/api/chat/groups/suspend", form("room", G, "username", "dave", "hours", "2"), tok.get("bob"))[0].equals("403"));
        check("an outsider cannot", http(port, "POST", "/api/chat/groups/suspend", form("room", G, "username", "dave", "hours", "2"), tok.get("gus"))[0].equals("404"));
        check("the group admin cannot suspend herself", http(port, "POST", "/api/chat/groups/suspend", form("room", G, "username", "alice", "hours", "2"), tok.get("alice"))[0].equals("400"));
        check("nor someone who is not in the group", http(port, "POST", "/api/chat/groups/suspend", form("room", G, "username", "gus", "hours", "2"), tok.get("alice"))[0].equals("404"));
        check("a public group has no suspensions (use mute)", http(port, "POST", "/api/chat/groups/suspend", form("room", OPEN, "username", "carol", "hours", "2"), tok.get("bob"))[0].equals("400"));
        check("the group admin suspends dave for 2 hours", http(port, "POST", "/api/chat/groups/suspend", form("room", G, "username", "dave", "hours", "2"), tok.get("alice"))[0].equals("200"));
        check("dave is locked out", http(port, "GET", "/api/chat/messages?room=" + G, null, tok.get("dave"))[0].equals("404"));
        String c2 = newCode(port, G, 20);
        r = http(port, "POST", "/api/chat/groups/join", form("code", c2), tok.get("dave"));
        check("he cannot come back with a code", r[0].equals("403") && r[1].contains("removed"));
        check("the group admin removes erin", http(port, "POST", "/api/chat/groups/remove", form("room", G, "username", "erin"), tok.get("alice"))[0].equals("200"));
        String banned = http(port, "GET", "/api/chat/groups/banned?room=" + G, null, tok.get("alice"))[1];
        check("the Banned list shows dave as suspended with the time left", banned.contains("\"username\":\"dave\",\"kind\":\"suspended\",\"hoursLeft\":2"));
        check("and erin as removed", banned.contains("\"username\":\"erin\",\"kind\":\"removed\",\"hoursLeft\":null"));
        check("a member cannot see the list", http(port, "GET", "/api/chat/groups/banned?room=" + G, null, tok.get("bob"))[0].equals("403"));
        check("the super-admin cannot either", http(port, "GET", "/api/chat/groups/banned?room=" + G, null, admin)[0].equals("404"));
        check("the group admin reinstates erin", http(port, "POST", "/api/chat/groups/reinstate", form("room", G, "username", "erin"), tok.get("alice"))[0].equals("200"));
        check("erin is back without a new invite", http(port, "GET", "/api/chat/messages?room=" + G, null, tok.get("erin"))[0].equals("200"));
        check("reinstating again", http(port, "POST", "/api/chat/groups/reinstate", form("room", G, "username", "erin"), tok.get("alice"))[0].equals("404"));
        check("a member cannot reinstate", http(port, "POST", "/api/chat/groups/reinstate", form("room", G, "username", "dave"), tok.get("bob"))[0].equals("403"));
        t.addAndGet(2 * HOUR + 60000);
        check("when the 2 hours are up dave is let back in", mod.tick() >= 1 && http(port, "GET", "/api/chat/messages?room=" + G, null, tok.get("dave"))[0].equals("200"));
        check("and leaves the Banned list", !http(port, "GET", "/api/chat/groups/banned?room=" + G, null, tok.get("alice"))[1].contains("dave"));
        check("an indefinite group suspension", http(port, "POST", "/api/chat/groups/suspend", form("room", G, "username", "bob", "hours", "0"), tok.get("alice"))[0].equals("200"));
        t.addAndGet(1000 * HOUR);
        mod.tick();
        check("it is not lifted by time", http(port, "GET", "/api/chat/messages?room=" + G, null, tok.get("bob"))[0].equals("404")
                && http(port, "GET", "/api/chat/groups/banned?room=" + G, null, tok.get("alice"))[1].contains("\"username\":\"bob\",\"kind\":\"suspended\",\"hoursLeft\":null"));
        check("the group admin reinstates bob", http(port, "POST", "/api/chat/groups/reinstate", form("room", G, "username", "bob"), tok.get("alice"))[0].equals("200")
                && http(port, "GET", "/api/chat/messages?room=" + G, null, tok.get("bob"))[0].equals("200"));
        check("reinstating someone whose account was deleted", http(port, "POST", "/api/chat/groups/reinstate", form("room", G, "username", "zed"), tok.get("alice"))[0].equals("404"));

        System.out.println();
        System.out.println("-- The moderation log --");
        String log = http(port, "GET", "/api/admin/chat/audit?limit=100", null, admin)[1];
        check("suspensions, reinstatements and deletions are logged", log.contains("\"action\":\"suspend\"") && log.contains("\"action\":\"reinstate\"")
                && log.contains("\"action\":\"delete account\"") && log.contains("\"action\":\"delete message\"") && log.contains("\"action\":\"warn group admin\""));
        check("automatic lifts are marked as the system", log.contains("\"actor\":\"system\""));
        check("the actor is recorded", log.contains("\"actor\":\"admin\""));

        System.out.println();
        System.out.println("-- Saving and loading --");
        check("the super-admin suspends gus indefinitely", http(port, "POST", "/api/admin/chat/accounts/suspend", form("username", "gus", "hours", "0", "notice", "See you later", "reason", "x"), admin)[0].equals("200"));
        check("the group admin suspends erin for 5 hours", http(port, "POST", "/api/chat/groups/suspend", form("room", G, "username", "erin", "hours", "5"), tok.get("alice"))[0].equals("200"));
        cs.flushIfDirty();
        int reportCount = mod.list("all", 5000).size();
        ChatStore cs2 = new ChatStore(chatFile, t::get);
        cs2.init();
        ModerationService mod2 = new ModerationService(modFile, t::get, cs2, us, ss, (n, why) -> { });
        mod2.init();
        check("every report survives a restart", mod2.list("all", 5000).size() == reportCount && reportCount > 10);
        ModerationService.Report first = mod2.list("handled", 5000).get(mod2.list("handled", 5000).size() - 1);
        check("with its snapshot and what was done", !first.context.isEmpty() && !first.actions.isEmpty());
        check("the account suspension survives", mod2.suspensions().size() == 1 && "gus".equals(mod2.suspensions().get(0).username)
                && mod2.suspensionMessage("gus") != null && mod2.suspensionMessage("gus").contains("See you later"));
        check("the log survives", mod2.audit(10).size() == 10);
        boolean erinBanned = false;
        for (ChatStore.BanInfo b : cs2.bannedList(G, "alice")) {
            erinBanned |= b.key.equals("erin") && b.suspended && b.until != 0;
        }
        check("the group suspension survives", erinBanned);

        server.stop();
        System.out.println();
        System.out.println(failures == 0 ? "ALL CHECKS PASSED" : failures + " CHECK(S) FAILED");
    }

    private static String newCode(int port, String room, int uses) throws Exception {
        String[] r = http(port, "POST", "/api/chat/groups/invite/create", form("room", room, "uses", String.valueOf(uses), "days", "7"), tok.get("alice"));
        return value(r[1], "code");
    }

    private static long say(int port, String user, String room, String text) throws Exception {
        t.addAndGet(4000);
        String[] r = http(port, "POST", "/api/chat/post", form("room", room, "text", text), tok.get(user));
        return (long) number(r[1], "\"id\":(\\d+)");
    }

    private static String[] report(int port, String user, String type, String room, long id, String who, String reason, String note) throws Exception {
        return http(port, "POST", "/api/chat/report",
                form("type", type, "room", room, "id", String.valueOf(id), "username", who, "reason", reason, "note", note), tok.get(user));
    }

    /** The JSON of one report from a list, or "" if it is not there. */
    private static String reportObj(String json, double id) {
        Matcher m = Pattern.compile("\\{\"id\":(\\d+),\"time\":\\d+,\"reporter\"").matcher(json);
        int start = -1;
        int end = json.length();
        while (m.find()) {
            if (start >= 0) {
                end = m.start();
                break;
            }
            if (Long.parseLong(m.group(1)) == (long) id) {
                start = m.start();
            }
        }
        return start < 0 ? "" : json.substring(start, end);
    }

    /** Where a context line with exactly this text starts in the report, or -1. */
    private static int in(String obj, String text) {
        return obj.indexOf("\"text\":\"" + text + "\"");
    }

    private static String value(String json, String name) {
        Matcher m = Pattern.compile("\"" + name + "\":\"([^\"]*)\"").matcher(json);
        return m.find() ? m.group(1) : null;
    }

    private static double number(String json, String pattern) {
        Matcher m = Pattern.compile(pattern).matcher(json);
        return m.find() ? Double.parseDouble(m.group(1)) : -1;
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