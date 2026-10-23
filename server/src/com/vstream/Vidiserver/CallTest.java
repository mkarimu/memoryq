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
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Tests the calls add-on: the switch, who may call whom, ringing, adding people, media logins, limits and saving. */
public class CallTest {

    private static int failures = 0;
    private static final AtomicLong t = new AtomicLong(1000000000000L);
    private static final Map<String, String> tokens = new HashMap<String, String>();
    private static int port;
    private static int authPort;

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("vstream-calls");
        LiveChannelRegistry reg = new LiveChannelRegistry();
        String liveChannel = reg.registerChannel("preset", "");
        UserStore us = new UserStore(dir.resolve("users.txt"), reg, id -> { });
        us.init(null);
        String[] normal = {"alice", "bob", "carol", "dave", "erin", "zed", "rate01", "rate02",
                "user01", "user02", "user03", "user04", "user05", "user06", "user07", "user08", "user09", "user10", "user11"};
        for (String n : normal) {
            us.createUser(n, "password123", UserStore.Role.CHATTER);
        }
        us.createUser("gus", "password123", UserStore.Role.GUEST);
        SessionStore ss = new SessionStore();
        for (String n : normal) {
            tokens.put(n, ss.create(n, UserStore.Role.CHATTER));
        }
        tokens.put("gus", ss.create("gus", UserStore.Role.GUEST));
        tokens.put("admin", ss.create("admin", UserStore.Role.ADMIN));
        Path chatFile = dir.resolve("chat.txt");
        ChatStore cs = new ChatStore(chatFile, t::get);
        cs.init();
        Path callsFile = dir.resolve("calls.txt");
        CallService calls = new CallService(callsFile, t::get, cs, us, false);
        calls.init();
        ChatApi chatApi = new ChatApi(cs, us, ss, reg, id -> false);
        CallApi callApi = new CallApi(calls, cs, us, ss);
        MediaAuthHttpServer auth = new MediaAuthHttpServer(reg, 0);
        auth.setCallAuth(calls::authorize);
        auth.start();
        authPort = auth.getPort();
        ChannelListHttpServer server = new ChannelListHttpServer(reg, 0);
        server.setChatApi(chatApi);
        server.setCallApi(callApi);
        server.start();
        port = server.getPort();
        final String G = "g-family-chat";
        final String W = "g-work-crew";

        System.out.println();
        System.out.println("-- Setting up groups --");
        check("alice makes a private group", http("POST", "/api/chat/groups/create", form("title", "Family Chat", "private", "true"), tokens.get("alice"))[0].equals("200"));
        String code = val(http("POST", "/api/chat/groups/invite/create", form("room", G, "uses", "50", "days", "7"), tokens.get("alice"))[1], "code");
        String[] members = {"bob", "carol", "dave", "zed", "gus", "rate01", "rate02", "user01", "user02", "user03", "user04",
                "user05", "user06", "user07", "user08", "user09", "user10", "user11"};
        boolean allJoined = true;
        for (String n : members) {
            allJoined &= http("POST", "/api/chat/groups/join", form("code", code), tokens.get(n))[0].equals("200");
        }
        check("everyone joins it", allJoined);
        check("carol makes another private group with erin in it", http("POST", "/api/chat/groups/create", form("title", "Work Crew", "private", "true"), tokens.get("carol"))[0].equals("200"));
        String wcode = val(http("POST", "/api/chat/groups/invite/create", form("room", W, "uses", "5", "days", "7"), tokens.get("carol"))[1], "code");
        check("erin joins that one", http("POST", "/api/chat/groups/join", form("code", wcode), tokens.get("erin"))[0].equals("200"));
        check("bob makes a public group", http("POST", "/api/chat/groups/create", form("title", "Open Club"), tokens.get("bob"))[0].equals("200"));

        System.out.println();
        System.out.println("-- The add-on is off by default --");
        String[] r = http("GET", "/api/calls/features", null, null);
        check("the feature list says calls are off", r[0].equals("200") && r[1].contains("\"calls\":false"));
        check("starting a call says the add-on is off", http("POST", "/api/calls/start", form("to", "bob"), tokens.get("alice"))[0].equals("404"));
        check("polling too", http("GET", "/api/calls/poll", null, tokens.get("alice"))[0].equals("404"));
        check("call paths are refused by the media login", authCode("read", "call/0123456789abcdef/alice", "x") == 401);
        check("a normal user cannot turn calls on", http("POST", "/api/admin/calls/enable", form("enabled", "true"), tokens.get("bob"))[0].equals("403"));
        check("and neither can a visitor", http("POST", "/api/admin/calls/enable", form("enabled", "true"), null)[0].equals("401"));
        check("the super-admin turns calls on", http("POST", "/api/admin/calls/enable", form("enabled", "true"), tokens.get("admin"))[0].equals("200"));
        check("the feature list now says on", http("GET", "/api/calls/features", null, null)[1].contains("\"calls\":true"));

        System.out.println();
        System.out.println("-- The media login for live channels is unchanged --");
        String liveToken = reg.getChannel(liveChannel).getPublishToken();
        check("reading a live channel is allowed", authRaw("read", "live/" + liveChannel, "", "10.0.0.5") == 200);
        check("publishing with the right token is allowed", authRaw("publish", "live/" + liveChannel, "token=" + liveToken, "10.0.0.5") == 200);
        check("publishing with a wrong token is refused", authRaw("publish", "live/" + liveChannel, "token=wrong", "10.0.0.5") == 401);
        check("the API is allowed from this computer", authRaw("api", "", "", "127.0.0.1") == 200);
        check("and refused from elsewhere", authRaw("api", "", "", "10.0.0.5") == 401);
        check("some other path is refused", authRaw("read", "other/thing", "", "10.0.0.5") == 401);

        System.out.println();
        System.out.println("-- Who may start a call --");
        check("a visitor cannot", http("POST", "/api/calls/start", form("to", "bob"), null)[0].equals("401"));
        check("nobody to call", http("POST", "/api/calls/start", form("video", "true"), tokens.get("alice"))[0].equals("400"));
        r = http("POST", "/api/calls/start", form("to", "bob,carol"), tokens.get("alice"));
        check("two people cannot be rung at the start", r[0].equals("400") && r[1].contains("one person"));
        r = http("POST", "/api/calls/start", form("to", "bob", "group", "true"), tokens.get("alice"));
        check("a group call cannot be started directly", r[0].equals("400") && r[1].contains("add people"));
        check("you cannot call yourself", http("POST", "/api/calls/start", form("to", "alice"), tokens.get("alice"))[0].equals("400"));
        check("a user who does not exist", http("POST", "/api/calls/start", form("to", "nobody"), tokens.get("alice"))[0].equals("404"));
        check("the super-admin cannot be called", http("POST", "/api/calls/start", form("to", "admin"), tokens.get("alice"))[0].equals("400"));
        r = http("POST", "/api/calls/start", form("to", "erin"), tokens.get("alice"));
        check("anyone can be called, even without a shared group", r[0].equals("200") && r[1].contains("\"roomTitle\":\"\""));
        check("(alice hangs up)", http("POST", "/api/calls/leave", form("id", id(r[1])), tokens.get("alice"))[0].equals("200"));
        r = http("POST", "/api/calls/start", form("to", "zed", "room", W), tokens.get("erin"));
        check("a private group's name is not shown to someone outside it", r[0].equals("200") && r[1].contains("\"roomTitle\":\"\""));
        check("(erin hangs up)", http("POST", "/api/calls/leave", form("id", id(r[1])), tokens.get("erin"))[0].equals("200"));
        r = http("POST", "/api/calls/start", form("to", "carol", "room", W), tokens.get("erin"));
        check("a call started from a shared group shows that group", r[0].equals("200") && r[1].contains("\"roomTitle\":\"Work Crew\""));
        check("(erin hangs up)", http("POST", "/api/calls/leave", form("id", id(r[1])), tokens.get("erin"))[0].equals("200"));
        r = http("POST", "/api/calls/start", form("to", "bob"), tokens.get("gus"));
        check("a guest cannot start calls", r[0].equals("403") && r[1].contains("guest"));
        cs.mute("zed", 0);
        check("a muted user cannot", http("POST", "/api/calls/start", form("to", "bob"), tokens.get("zed"))[0].equals("403"));
        cs.unmute("zed");
        us.setDisabled("zed", true);
        check("a suspended user cannot", http("POST", "/api/calls/start", form("to", "bob"), tokens.get("zed"))[0].equals("401"));
        us.setDisabled("zed", false);

        System.out.println();
        System.out.println("-- A call that is answered --");
        newHour();
        r = http("POST", "/api/calls/start", form("to", "bob", "video", "true"), tokens.get("alice"));
        check("alice calls bob", r[0].equals("200"));
        String c1 = id(r[1]);
        String pubA = val(r[1], "pubToken");
        String readA = val(r[1], "readToken");
        check("the call carries the group it goes through", r[1].contains("\"roomTitle\":\"Family Chat\"") && r[1].contains("\"video\":true") && r[1].contains("\"group\":false"));
        check("alice is in it and bob is ringing", partState(r[1], "alice").equals("JOINED") && partState(r[1], "bob").equals("INVITED"));
        check("alice gets her own media login", pubA != null && pubA.length() == 48 && readA != null && r[1].contains("/call/" + c1 + "/alice/whip?token=" + pubA));
        String bobPoll = http("GET", "/api/calls/poll", null, tokens.get("bob"))[1];
        check("bob sees it ringing, from alice", bobPoll.contains("\"initiator\":\"alice\"") && bobPoll.contains(c1) && bobPoll.contains("\"myState\":\"INVITED\"") && bobPoll.contains("\"invitedBy\":\"alice\""));
        check("carol sees nothing", http("GET", "/api/calls/poll", null, tokens.get("carol"))[1].contains("\"incoming\":[]"));
        check("alice sees it as her current call", http("GET", "/api/calls/poll", null, tokens.get("alice"))[1].contains("\"current\":{\"id\":\"" + c1 + "\""));
        check("alice may publish with her token", authCode("publish", "call/" + c1 + "/alice", pubA) == 200);
        check("a wrong token is refused", authCode("publish", "call/" + c1 + "/alice", "deadbeef") == 401);
        check("a missing token is refused", authCode("publish", "call/" + c1 + "/alice", null) == 401);
        check("bob cannot publish while he is only ringing", authCode("publish", "call/" + c1 + "/bob", pubA) == 401);
        check("nobody can read bob's stream before he joins", authCode("read", "call/" + c1 + "/bob", readA) == 401);
        check("an unknown call is refused", authCode("publish", "call/0000000000000000/alice", pubA) == 401);
        check("a longer path is not a call path", authCode("publish", "call/" + c1 + "/alice/extra", pubA) == 401);
        check("carol cannot see the call", http("GET", "/api/calls/state?id=" + c1, null, tokens.get("carol"))[0].equals("404"));
        check("carol cannot accept it", http("POST", "/api/calls/accept", form("id", c1), tokens.get("carol"))[0].equals("404"));
        r = http("POST", "/api/calls/accept", form("id", c1), tokens.get("bob"));
        check("bob accepts", r[0].equals("200") && r[1].contains("\"myState\":\"JOINED\""));
        String pubB = val(r[1], "pubToken");
        String readB = val(r[1], "readToken");
        check("bob gets his own tokens, not alice's", pubB != null && !pubB.equals(pubA) && !r[1].contains(pubA) && !r[1].contains(readA));
        check("bob is told where to read alice's stream", r[1].contains("/call/" + c1 + "/alice/whep?token=" + readB));
        String aliceState = http("GET", "/api/calls/state?id=" + c1, null, tokens.get("alice"))[1];
        check("alice sees bob has joined, and where to read his stream", partState(aliceState, "bob").equals("JOINED") && aliceState.contains("/call/" + c1 + "/bob/whep?token=" + readA)
                && !aliceState.contains(pubB) && !aliceState.contains(readB));
        check("a call between two people is not a group call", aliceState.contains("\"group\":false"));
        check("bob may publish his own stream", authCode("publish", "call/" + c1 + "/bob", pubB) == 200);
        check("bob cannot publish to alice's path", authCode("publish", "call/" + c1 + "/alice", pubB) == 401);
        check("bob may read alice's stream", authCode("read", "call/" + c1 + "/alice", readB) == 200);
        check("alice may read bob's stream", authCode("read", "call/" + c1 + "/bob", readA) == 200);
        check("a made-up read token is refused", authCode("read", "call/" + c1 + "/alice", "feedface") == 401);
        check("a publish token cannot be used to read", authCode("read", "call/" + c1 + "/alice", pubB) == 401);
        check("nobody can read a stream of someone who is not in the call", authCode("read", "call/" + c1 + "/carol", readB) == 401);
        check("bob cannot accept twice", http("POST", "/api/calls/accept", form("id", c1), tokens.get("bob"))[0].equals("404"));
        check("alice cannot start another call", http("POST", "/api/calls/start", form("to", "carol"), tokens.get("alice"))[0].equals("409"));
        check("there is no way to join a call by its id any more", http("POST", "/api/calls/join", form("id", c1), tokens.get("carol"))[0].equals("404"));
        check("someone not in the call cannot end it", http("POST", "/api/calls/end", form("id", c1), tokens.get("carol"))[0].equals("404"));
        t.addAndGet(5000);
        check("bob leaves", http("POST", "/api/calls/leave", form("id", c1), tokens.get("bob"))[0].equals("200"));
        aliceState = http("GET", "/api/calls/state?id=" + c1, null, tokens.get("alice"))[1];
        check("the call ends for everyone", aliceState.contains("\"ended\":true") && aliceState.contains("\"endReason\":\"ended\""));
        check("the media logins stop working", authCode("publish", "call/" + c1 + "/alice", pubA) == 401 && authCode("read", "call/" + c1 + "/alice", readB) == 401);
        check("accepting an ended call", http("POST", "/api/calls/accept", form("id", c1), tokens.get("bob"))[0].equals("404"));

        System.out.println();
        System.out.println("-- A call that is declined --");
        newHour();
        String c2 = id(http("POST", "/api/calls/start", form("to", "bob", "video", "false"), tokens.get("alice"))[1]);
        check("bob declines", http("POST", "/api/calls/decline", form("id", c2), tokens.get("bob"))[0].equals("200"));
        String st = http("GET", "/api/calls/state?id=" + c2, null, tokens.get("alice"))[1];
        check("the call ends as declined", st.contains("\"ended\":true") && st.contains("\"endReason\":\"declined\"") && st.contains("\"video\":false"));

        System.out.println();
        System.out.println("-- A call that nobody answers --");
        newHour();
        String c3 = id(http("POST", "/api/calls/start", form("to", "bob"), tokens.get("alice"))[1]);
        advance(46000, "alice");
        check("bob no longer sees it ringing", http("GET", "/api/calls/poll", null, tokens.get("bob"))[1].contains("\"incoming\":[]"));
        st = http("GET", "/api/calls/state?id=" + c3, null, tokens.get("alice"))[1];
        check("it ends as no answer", st.contains("\"ended\":true") && st.contains("\"endReason\":\"no answer\""));
        check("bob cannot accept it any more", http("POST", "/api/calls/accept", form("id", c3), tokens.get("bob"))[0].equals("404"));
        r = http("GET", "/api/calls/poll", null, tokens.get("bob"));
        check("bob has one missed call", r[1].contains("\"missed\":1"));
        check("alice does not", http("GET", "/api/calls/poll", null, tokens.get("alice"))[1].contains("\"missed\":0"));

        System.out.println();
        System.out.println("-- History --");
        r = http("GET", "/api/calls/history", null, tokens.get("alice"));
        int iNo = r[1].indexOf("\"outcome\":\"no answer\"");
        int iDec = r[1].indexOf("\"outcome\":\"declined\"");
        int iAns = r[1].indexOf("\"outcome\":\"answered\"");
        check("alice's history lists her three calls, newest first", iNo > 0 && iDec > iNo && iAns > iDec);
        check("they are outgoing, with the other person", r[1].contains("\"outgoing\":true") && r[1].contains("\"peer\":\"bob\""));
        check("the answered call has a duration", durationOf(r[1], c1) >= 5);
        r = http("GET", "/api/calls/history", null, tokens.get("bob"));
        check("bob's history shows them as incoming, with his outcomes",
                r[1].contains("\"outgoing\":false") && r[1].contains("\"outcome\":\"missed\"") && r[1].contains("\"outcome\":\"declined\"") && r[1].contains("\"peer\":\"alice\""));
        check("carol's history only has erin's call", count(http("GET", "/api/calls/history", null, tokens.get("carol"))[1], "\"id\":\"") == 1);
        check("history needs a sign-in", http("GET", "/api/calls/history", null, null)[0].equals("401"));
        check("bob marks his missed calls as seen", http("POST", "/api/calls/history/seen", "", tokens.get("bob"))[0].equals("200")
                && http("GET", "/api/calls/poll", null, tokens.get("bob"))[1].contains("\"missed\":0"));

        System.out.println();
        System.out.println("-- Do not disturb, blocking and busy --");
        newHour();
        t.addAndGet(1000);
        check("bob turns on do not disturb", http("POST", "/api/calls/prefs", form("dnd", "true"), tokens.get("bob"))[1].contains("\"dnd\":true"));
        r = http("POST", "/api/calls/start", form("to", "bob"), tokens.get("alice"));
        check("alice is told he is not available", r[0].equals("409") && r[1].contains("not available"));
        check("bob sees a missed call from it", http("GET", "/api/calls/poll", null, tokens.get("bob"))[1].contains("\"missed\":1")
                && http("GET", "/api/calls/history?limit=1", null, tokens.get("bob"))[1].contains("\"outcome\":\"dnd\""));
        check("bob does not hear a ring", http("GET", "/api/calls/poll", null, tokens.get("bob"))[1].contains("\"incoming\":[]"));
        http("POST", "/api/calls/prefs", form("dnd", "false"), tokens.get("bob"));
        newHour();
        check("carol blocks alice", http("POST", "/api/calls/block", form("username", "alice"), tokens.get("carol"))[0].equals("200"));
        int carolHistory = count(http("GET", "/api/calls/history", null, tokens.get("carol"))[1], "\"id\":\"");
        r = http("POST", "/api/calls/start", form("to", "carol"), tokens.get("alice"));
        check("alice gets the same answer", r[0].equals("409") && r[1].contains("not available"));
        check("a blocked call leaves nothing in carol's history", count(http("GET", "/api/calls/history", null, tokens.get("carol"))[1], "\"id\":\"") == carolHistory);
        check("carol's block list shows alice", http("GET", "/api/calls/blocked", null, tokens.get("carol"))[1].contains("\"alice\""));
        check("blocking yourself", http("POST", "/api/calls/block", form("username", "carol"), tokens.get("carol"))[0].equals("400"));
        check("blocking someone who does not exist", http("POST", "/api/calls/block", form("username", "nobody"), tokens.get("carol"))[0].equals("404"));
        check("carol unblocks alice", http("POST", "/api/calls/unblock", form("username", "alice"), tokens.get("carol"))[0].equals("200"));
        check("unblocking again", http("POST", "/api/calls/unblock", form("username", "alice"), tokens.get("carol"))[0].equals("404"));
        check("alice blocks dave", http("POST", "/api/calls/block", form("username", "dave"), tokens.get("alice"))[0].equals("200"));
        r = http("POST", "/api/calls/start", form("to", "dave"), tokens.get("alice"));
        check("she cannot call him until she unblocks", r[0].equals("409") && r[1].contains("you have blocked"));
        http("POST", "/api/calls/unblock", form("username", "dave"), tokens.get("alice"));
        newHour();
        String c4 = id(http("POST", "/api/calls/start", form("to", "bob"), tokens.get("alice"))[1]);
        http("POST", "/api/calls/accept", form("id", c4), tokens.get("bob"));
        r = http("POST", "/api/calls/start", form("to", "bob"), tokens.get("carol"));
        check("calling someone who is on a call", r[0].equals("409") && r[1].contains("busy"));
        check("calling while you are on a call", http("POST", "/api/calls/start", form("to", "dave"), tokens.get("alice"))[0].equals("409"));
        check("the starter ends it for everyone", http("POST", "/api/calls/end", form("id", c4), tokens.get("alice"))[0].equals("200")
                && http("GET", "/api/calls/state?id=" + c4, null, tokens.get("bob"))[1].contains("\"ended\":true"));
        newHour();
        String c5 = id(http("POST", "/api/calls/start", form("to", "bob"), tokens.get("alice"))[1]);
        http("POST", "/api/calls/accept", form("id", c5), tokens.get("bob"));
        check("someone who did not start it cannot end it for everyone", http("POST", "/api/calls/end", form("id", c5), tokens.get("bob"))[0].equals("403"));
        t.addAndGet(2000);
        http("POST", "/api/calls/leave", form("id", c5), tokens.get("alice"));

        System.out.println();
        System.out.println("-- Cancelling before anyone answers --");
        newHour();
        String c6 = id(http("POST", "/api/calls/start", form("to", "bob"), tokens.get("alice"))[1]);
        check("alice hangs up while it rings", http("POST", "/api/calls/leave", form("id", c6), tokens.get("alice"))[0].equals("200"));
        check("the call is cancelled", http("GET", "/api/calls/state?id=" + c6, null, tokens.get("alice"))[1].contains("\"endReason\":\"cancelled\""));
        check("bob has a missed call", http("GET", "/api/calls/poll", null, tokens.get("bob"))[1].contains("\"incoming\":[]")
                && http("GET", "/api/calls/history?limit=1", null, tokens.get("bob"))[1].contains("\"outcome\":\"missed\""));
        check("leaving a call you are not in", http("POST", "/api/calls/leave", form("id", c6), tokens.get("alice"))[0].equals("404"));

        System.out.println();
        System.out.println("-- Adding people: a call turns into a group call --");
        newHour();
        r = http("POST", "/api/calls/start", form("to", "bob", "video", "true"), tokens.get("alice"));
        String cA = id(r[1]);
        http("POST", "/api/calls/accept", form("id", cA), tokens.get("bob"));
        r = add("alice", cA, "carol");
        check("alice adds carol", r[0].equals("200") && r[1].contains("\"added\":[\"carol\"]") && r[1].contains("\"skipped\":[]"));
        String sa = state("alice", cA);
        check("carol is ringing, added by alice", sa.contains("{\"name\":\"carol\",\"state\":\"INVITED\",\"by\":\"alice\"}"));
        check("with three people it is a group call for everyone", sa.contains("\"group\":true") && state("bob", cA).contains("\"group\":true"));
        String carolPoll = http("GET", "/api/calls/poll", null, tokens.get("carol"))[1];
        check("carol sees who is in the call and who added her", carolPoll.contains(cA) && carolPoll.contains("\"invitedBy\":\"alice\"")
                && partState(carolPoll, "alice").equals("JOINED") && partState(carolPoll, "bob").equals("JOINED") && carolPoll.contains("\"group\":true"));
        check("carol cannot read anyone's stream yet", authCode("read", "call/" + cA + "/alice", "x") == 401);
        check("carol declines", http("POST", "/api/calls/decline", form("id", cA), tokens.get("carol"))[0].equals("200"));
        sa = state("alice", cA);
        check("the call goes on and is one-to-one again", sa.contains("\"ended\":false") && sa.contains("\"group\":false") && partState(sa, "carol").equals("DECLINED"));
        r = add("alice", cA, "carol");
        check("alice can ring carol again", r[0].equals("200"));
        r = http("POST", "/api/calls/accept", form("id", cA), tokens.get("carol"));
        check("carol accepts and is told about two streams", r[0].equals("200") && count(r[1], "\"whepUrl\"") == 2);
        check("the call is a group call", state("alice", cA).contains("\"group\":true"));
        String readBob = val(state("bob", cA), "readToken");
        check("bob can read carol's stream with his own token", authCode("read", "call/" + cA + "/carol", readBob) == 200);
        check("alice now sees carol's stream too", state("alice", cA).contains("/call/" + cA + "/carol/whep?token="));
        r = add("bob", cA, "zed");
        check("bob adds zed", r[0].equals("200") && state("alice", cA).contains("{\"name\":\"zed\",\"state\":\"INVITED\",\"by\":\"bob\"}"));

        check("someone who is only ringing cannot add people", add("zed", cA, "user01")[0].equals("403"));
        check("someone outside the call cannot add (and cannot see it)", add("dave", cA, "user01")[0].equals("404"));
        check("adding nobody", add("alice", cA, "")[0].equals("400"));
        check("adding yourself", add("alice", cA, "alice")[0].equals("400"));
        r = add("alice", cA, "bob");
        check("someone already in the call", r[0].equals("409") && r[1].contains("already in the call"));
        check("someone already ringing", add("alice", cA, "zed")[0].equals("409"));
        r = add("alice", cA, "erin");
        check("someone who shares no group with the adder can be added", r[0].equals("200") && r[1].contains("\"added\":[\"erin\"]"));
        check("(erin declines)", http("POST", "/api/calls/decline", form("id", cA), tokens.get("erin"))[0].equals("200"));
        check("a user who does not exist", add("alice", cA, "nobody")[0].equals("404"));
        check("the super-admin", add("alice", cA, "admin")[0].equals("400"));
        check("adding needs a sign-in", http("POST", "/api/calls/add", form("id", cA, "to", "user01"), null)[0].equals("401"));
        check("a guest in the call could add, a guest outside cannot", add("gus", cA, "user01")[0].equals("404"));

        http("POST", "/api/calls/block", form("username", "bob"), tokens.get("dave"));
        r = add("alice", cA, "dave");
        check("someone who blocked a person in the call is not added, and no reason is given",
                r[0].equals("409") && r[1].contains("not available") && !r[1].contains("blocked"));
        check("dave was not rung", partState(state("alice", cA), "dave").equals(""));
        http("POST", "/api/calls/unblock", form("username", "bob"), tokens.get("dave"));
        http("POST", "/api/calls/block", form("username", "user01"), tokens.get("alice"));
        r = add("bob", cA, "user01");
        check("someone a person in the call has blocked cannot be added by another person", r[0].equals("409") && r[1].contains("not available"));
        r = add("alice", cA, "user01");
        check("the person who blocked them is told so", r[0].equals("409") && r[1].contains("you have blocked"));
        http("POST", "/api/calls/unblock", form("username", "user01"), tokens.get("alice"));

        http("POST", "/api/calls/prefs", form("dnd", "true"), tokens.get("user02"));
        r = add("alice", cA, "user02");
        check("someone on do not disturb is not rung", r[0].equals("409") && r[1].contains("not available"));
        check("they see a missed call", http("GET", "/api/calls/history?limit=1", null, tokens.get("user02"))[1].contains("\"outcome\":\"dnd\""));
        http("POST", "/api/calls/prefs", form("dnd", "false"), tokens.get("user02"));
        String c7a = id(http("POST", "/api/calls/start", form("to", "user04"), tokens.get("user03"))[1]);
        http("POST", "/api/calls/accept", form("id", c7a), tokens.get("user04"));
        r = add("alice", cA, "user03");
        check("someone on another call is busy", r[0].equals("409") && r[1].contains("busy"));
        http("POST", "/api/calls/leave", form("id", c7a), tokens.get("user03"));

        r = http("GET", "/api/admin/calls/active", null, tokens.get("admin"));
        check("the super-admin sees it as a group call, without tokens", r[1].contains("\"id\":\"" + cA + "\"") && r[1].contains("\"group\":true") && r[1].contains("\"joined\":3")
                && !r[1].contains("pubToken"));

        System.out.println();
        System.out.println("-- Limits on the size of a call --");
        r = add("alice", cA, "user05,user06");
        check("two more people can be added at once (a video call has room for six)", r[0].equals("200") && r[1].contains("\"added\":[\"user05\",\"user06\"]"));
        r = add("alice", cA, "user07");
        check("a seventh person on a video call is refused", r[0].equals("400") && r[1].contains("at most 6"));
        advance(46000, "alice", "bob", "carol");
        sa = state("alice", cA);
        check("people who did not answer are shown as missed, and the call goes on", partState(sa, "zed").equals("MISSED") && partState(sa, "user05").equals("MISSED") && sa.contains("\"ended\":false"));
        check("three people are still a group call", sa.contains("\"group\":true"));
        check("only the starter ends it for everyone", http("POST", "/api/calls/end", form("id", cA), tokens.get("bob"))[0].equals("403"));
        check("carol leaves", http("POST", "/api/calls/leave", form("id", cA), tokens.get("carol"))[0].equals("200"));
        sa = state("alice", cA);
        check("the call goes on as one-to-one again", sa.contains("\"ended\":false") && sa.contains("\"group\":false"));
        check("bob leaves", http("POST", "/api/calls/leave", form("id", cA), tokens.get("bob"))[0].equals("200"));
        check("the call is over when only one person is left", state("alice", cA).contains("\"ended\":true"));
        check("adding to an ended call", add("alice", cA, "user07")[0].equals("404"));
        r = http("GET", "/api/calls/history?limit=1", null, tokens.get("alice"));
        check("a call that had three or more people is saved as a group call", r[1].contains("\"group\":true") && r[1].contains("\"peer\":\"\"")
                && r[1].contains("\"carol\"") && r[1].contains("\"zed\"") && r[1].contains("\"outcome\":\"answered\""));
        r = http("GET", "/api/calls/history?limit=1", null, tokens.get("zed"));
        check("zed's history shows a missed group call", r[1].contains("\"group\":true") && r[1].contains("\"outcome\":\"missed\""));

        newHour();
        r = http("POST", "/api/calls/start", form("to", "user10", "video", "false"), tokens.get("alice"));
        String cV = id(r[1]);
        http("POST", "/api/calls/accept", form("id", cV), tokens.get("user10"));
        r = add("alice", cV, "user01,user02,user03,user04,user05,user06");
        check("a voice call has room for eight", r[0].equals("200") && count(r[1], "\"user0") >= 6);
        r = add("alice", cV, "user07");
        check("a ninth person on a voice call is refused", r[0].equals("400") && r[1].contains("at most 8"));
        check("the starter ends it", http("POST", "/api/calls/end", form("id", cV), tokens.get("alice"))[0].equals("200"));

        newHour();
        r = http("POST", "/api/calls/start", form("to", "user08", "video", "false"), tokens.get("alice"));
        String cP = id(r[1]);
        http("POST", "/api/calls/accept", form("id", cP), tokens.get("user08"));
        int rang = 0;
        for (int i = 0; i < 3; i++) {
            r = add("alice", cP, "user09");
            if (r[0].equals("200")) {
                rang++;
                http("POST", "/api/calls/decline", form("id", cP), tokens.get("user09"));
            }
        }
        check("the same person can be rung three times in ten minutes", rang == 3);
        r = add("alice", cP, "user09");
        check("a fourth time is slowed down", r[0].equals("429") && r[1].contains("several times"));
        http("POST", "/api/calls/end", form("id", cP), tokens.get("alice"));

        System.out.println();
        System.out.println("-- People who drop off, and accounts that are suspended --");
        newHour();
        String c7 = id(http("POST", "/api/calls/start", form("to", "bob"), tokens.get("alice"))[1]);
        http("POST", "/api/calls/accept", form("id", c7), tokens.get("bob"));
        advance(31000, "bob");
        check("when alice stops checking in, the call ends for bob", http("GET", "/api/calls/state?id=" + c7, null, tokens.get("bob"))[1].contains("\"ended\":true"));
        newHour();
        String c8 = id(http("POST", "/api/calls/start", form("to", "bob"), tokens.get("alice"))[1]);
        http("POST", "/api/calls/accept", form("id", c8), tokens.get("bob"));
        us.setDisabled("bob", true);
        calls.tick();
        check("suspending an account takes it out of its call", http("GET", "/api/calls/state?id=" + c8, null, tokens.get("alice"))[1].contains("\"ended\":true"));
        us.setDisabled("bob", false);
        newHour();
        String c9 = id(http("POST", "/api/calls/start", form("to", "bob"), tokens.get("alice"))[1]);
        String c9pub = val(http("GET", "/api/calls/state?id=" + c9, null, tokens.get("alice"))[1], "pubToken");
        r = http("GET", "/api/admin/calls/active", null, tokens.get("admin"));
        check("the super-admin sees live calls without any tokens", r[1].contains("\"id\":\"" + c9 + "\"") && r[1].contains("\"initiator\":\"alice\"")
                && r[1].contains("\"joined\":1") && !r[1].contains("pubToken") && !r[1].contains(c9pub));
        check("a normal user cannot see them", http("GET", "/api/admin/calls/active", null, tokens.get("bob"))[0].equals("403"));
        check("the super-admin ends the call", http("POST", "/api/admin/calls/end", form("id", c9), tokens.get("admin"))[0].equals("200"));
        st = http("GET", "/api/calls/state?id=" + c9, null, tokens.get("alice"))[1];
        check("alice sees it was ended by the administrator", st.contains("\"ended\":true") && st.contains("ended by the administrator"));
        check("its media login stops working", authCode("publish", "call/" + c9 + "/alice", c9pub) == 401);
        check("ending it again", http("POST", "/api/admin/calls/end", form("id", c9), tokens.get("admin"))[0].equals("404"));
        check("a normal user cannot end calls for others", http("POST", "/api/admin/calls/end", form("id", c9), tokens.get("bob"))[0].equals("403"));
        r = http("GET", "/api/admin/calls/status", null, tokens.get("admin"));
        check("the status page shows the switch and counts today's calls", r[0].equals("200") && r[1].contains("\"enabled\":true")
                && r[1].contains("\"callsToday\":") && !r[1].contains("\"callsToday\":0}"));

        System.out.println();
        System.out.println("-- Saving --");
        http("POST", "/api/calls/prefs", form("dnd", "true"), tokens.get("user11"));
        http("POST", "/api/calls/block", form("username", "zed"), tokens.get("user11"));
        calls.tick();
        check("the calls file is written", Files.exists(callsFile));
        String saved = new String(Files.readAllBytes(callsFile), StandardCharsets.UTF_8);
        check("the file holds no media tokens", !saved.contains(pubA) && !saved.contains(readB) && !saved.contains(c9pub));
        CallService again = new CallService(callsFile, t::get, cs, us, false);
        again.init();
        check("the switch is remembered", again.enabled());
        check("do not disturb is remembered", again.isDnd("user11") && !again.isDnd("bob"));
        check("blocks are remembered", again.blockedBy("user11").contains("zed"));
        int histNow = calls.history("alice", 1000).size();
        check("history is remembered", histNow > 5 && again.history("alice", 1000).size() == histNow);
        check("group calls are still group calls after a restart", again.history("zed", 1000).size() > 0 && again.history("zed", 1000).get(0).group);
        check("seen missed calls stay seen", again.poll("bob").missed == calls.poll("bob").missed);

        System.out.println();
        System.out.println("-- Turning the add-on off --");
        newHour();
        String c10 = id(http("POST", "/api/calls/start", form("to", "bob"), tokens.get("alice"))[1]);
        http("POST", "/api/calls/accept", form("id", c10), tokens.get("bob"));
        String pub10 = val(state("alice", c10), "pubToken");
        check("the call's media login works while it is on", authCode("publish", "call/" + c10 + "/alice", pub10) == 200);
        r = http("POST", "/api/admin/calls/enable", form("enabled", "false"), tokens.get("admin"));
        check("the super-admin turns calls off", r[0].equals("200") && r[1].contains("\"enabled\":false"));
        check("the feature list says off", http("GET", "/api/calls/features", null, null)[1].contains("\"calls\":false"));
        check("the call's media login stops at once", authCode("publish", "call/" + c10 + "/alice", pub10) == 401);
        check("the app is told calls are off", http("GET", "/api/calls/poll", null, tokens.get("bob"))[0].equals("404"));
        check("live channels still work", authRaw("read", "live/" + liveChannel, "", "10.0.0.5") == 200);
        http("POST", "/api/admin/calls/enable", form("enabled", "true"), tokens.get("admin"));
        st = state("alice", c10);
        check("turned back on, the old call stays ended", st.contains("\"ended\":true") && st.contains("calls were turned off"));
        check("and new calls can be made", http("POST", "/api/calls/start", form("to", "carol"), tokens.get("alice"))[0].equals("200"));

        System.out.println();
        if (failures == 0) {
            System.out.println("All call tests passed.");
        } else {
            System.out.println(failures + " call test(s) FAILED.");
        }
        System.exit(failures == 0 ? 0 : 1);
    }

    // ---- Helpers ----

    private static void check(String name, boolean ok) {
        if (ok) {
            System.out.println("  ok    " + name);
        } else {
            failures++;
            System.out.println("  FAIL  " + name);
        }
    }

    /** Moves the clock past every hourly limit. */
    private static void newHour() {
        t.addAndGet(3601000L);
    }

    /** Moves the clock forward in small steps; the named people keep checking in, as the app does. */
    private static void advance(long ms, String... keepAlive) throws Exception {
        long left = ms;
        while (left > 0) {
            long step = Math.min(5000L, left);
            t.addAndGet(step);
            left -= step;
            for (String u : keepAlive) {
                http("GET", "/api/calls/poll", null, tokens.get(u));
            }
        }
    }

    private static String state(String user, String id) throws Exception {
        return http("GET", "/api/calls/state?id=" + id, null, tokens.get(user))[1];
    }

    private static String[] add(String user, String id, String to) throws Exception {
        return http("POST", "/api/calls/add", form("id", id, "to", to), tokens.get(user));
    }

    private static String[] http(String method, String path, String body, String token) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:" + port + path).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(5000);
        c.setReadTimeout(15000);
        if (token != null) {
            c.setRequestProperty("Authorization", "Bearer " + token);
        }
        if (body != null || "POST".equals(method)) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            OutputStream os = c.getOutputStream();
            os.write((body == null ? "" : body).getBytes(StandardCharsets.UTF_8));
            os.close();
        }
        int code = c.getResponseCode();
        String text = readAll(code >= 400 ? c.getErrorStream() : c.getInputStream());
        c.disconnect();
        return new String[]{String.valueOf(code), text};
    }

    /** Asks the media login the way MediaMTX does (a JSON POST); returns its status code. */
    private static int authRaw(String action, String path, String query, String ip) throws Exception {
        String json = "{\"user\":\"\",\"password\":\"\",\"token\":\"\",\"ip\":\"" + ip + "\",\"action\":\"" + action
                + "\",\"path\":\"" + path + "\",\"protocol\":\"webrtc\",\"id\":null,\"query\":\"" + query + "\"}";
        HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:" + authPort + "/auth").openConnection();
        c.setRequestMethod("POST");
        c.setConnectTimeout(5000);
        c.setReadTimeout(15000);
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json");
        OutputStream os = c.getOutputStream();
        os.write(json.getBytes(StandardCharsets.UTF_8));
        os.close();
        int code = c.getResponseCode();
        readAll(code >= 400 ? c.getErrorStream() : c.getInputStream());
        c.disconnect();
        return code;
    }

    private static int authCode(String action, String path, String token) throws Exception {
        return authRaw(action, path, token == null ? "" : "token=" + token, "10.0.0.5");
    }

    private static String readAll(InputStream in) throws Exception {
        if (in == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        char[] buf = new char[2048];
        int n;
        while ((n = br.read(buf)) != -1) {
            sb.append(buf, 0, n);
        }
        br.close();
        return sb.toString();
    }

    /** A form body. Commas are left as they are so lists like "bob,carol" arrive as one value either way. */
    private static String form(String... kv) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            if (sb.length() > 0) {
                sb.append('&');
            }
            sb.append(URLEncoder.encode(kv[i], "UTF-8")).append('=')
                    .append(URLEncoder.encode(kv[i + 1], "UTF-8").replace("%2C", ","));
        }
        return sb.toString();
    }

    private static String val(String json, String key) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(key) + "\":\"([^\"]*)\"").matcher(json);
        return m.find() ? m.group(1) : null;
    }

    private static String id(String json) {
        return val(json, "id");
    }

    private static String partState(String json, String name) {
        Matcher m = Pattern.compile("\\{\"name\":\"" + Pattern.quote(name) + "\",\"state\":\"([A-Z]+)\"").matcher(json);
        return m.find() ? m.group(1) : "";
    }

    private static long durationOf(String json, String callId) {
        int at = json.indexOf("\"id\":\"" + callId + "\"");
        if (at < 0) {
            return -1;
        }
        Matcher m = Pattern.compile("\"duration\":(\\d+)").matcher(json);
        return m.find(at) ? Long.parseLong(m.group(1)) : -1;
    }

    private static int count(String s, String sub) {
        int n = 0;
        int i = 0;
        while ((i = s.indexOf(sub, i)) >= 0) {
            n++;
            i += sub.length();
        }
        return n;
    }
}