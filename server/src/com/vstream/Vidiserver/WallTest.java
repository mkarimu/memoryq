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
import java.util.Base64;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Tests walls: posting, who can see what, pictures, likes, reports, the news feed picks and test people's posts. */
public class WallTest {

    private static int failures = 0;
    private static final AtomicLong t = new AtomicLong(1760000000000L);
    private static int port;

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("vstream-wall");
        LiveChannelRegistry reg = new LiveChannelRegistry();
        UserStore us = new UserStore(dir.resolve("users.txt"), reg, id -> { });
        us.init(null);
        for (String n : new String[]{"alice", "bob", "carol", "dave"}) {
            us.createUser(n, "password123", UserStore.Role.CHATTER);
        }
        SessionStore ss = new SessionStore();
        String alice = ss.create("alice", UserStore.Role.CHATTER);
        String bob = ss.create("bob", UserStore.Role.CHATTER);
        String carol = ss.create("carol", UserStore.Role.CHATTER);
        String dave = ss.create("dave", UserStore.Role.CHATTER);
        String admin = ss.create("admin", UserStore.Role.ADMIN);
        ChatStore cs = new ChatStore(dir.resolve("chat.txt"), t::get);
        cs.init();
        ProfileStore profiles = new ProfileStore(dir.resolve("profiles.txt"));
        profiles.init();
        profiles.edit("alice", "Alice", "Mensah", "DJ Al", "Accra", "Ghana", "", null, t.get());
        profiles.addFriend("alice", "bob", t.get());
        Path wallFile = dir.resolve("wall.txt");
        Path wallDir = dir.resolve("wall");
        WallStore wall = new WallStore(wallFile, wallDir);
        wall.init();
        WallApi api = new WallApi(wall, profiles, us, ss, cs, new Limiter(t::get), t::get, new Random(5));
        CallService calls = new CallService(dir.resolve("calls.txt"), t::get, cs, us, false);
        calls.init();
        api.setBlocks(calls);
        ChannelListHttpServer server = new ChannelListHttpServer(reg, 0);
        server.setWallApi(api);
        server.start();
        port = server.getPort();

        System.out.println();
        System.out.println("-- Posting --");
        check("posting needs a sign-in", http("POST", "/api/wall/post", form("text", "hi"), null)[0].equals("401"));
        check("an empty post is refused", http("POST", "/api/wall/post", form("text", "   "), alice)[0].equals("400"));
        String[] r = http("POST", "/api/wall/post", form("text", "Hello from Accra!\n\n\n\nSecond line\u0007", "visibility", "everyone"), alice);
        check("alice posts to everyone", r[0].equals("200") && r[1].contains("\"author\":\"alice\"") && r[1].contains("\"friendsOnly\":false")
                && r[1].contains("\"stageName\":\"DJ Al\""));
        check("line breaks are kept (at most two), odd characters removed", r[1].contains("\"text\":\"Hello from Accra!\\n\\nSecond line\""));
        String pub = val(r[1], "id");
        String png = "data:image/png;base64," + Base64.getEncoder().encodeToString(ProfileArt.scene(1, "Ghana", "Accra"));
        r = http("POST", "/api/wall/post", form("text", "Just for my friends", "visibility", "friends", "image", png), alice);
        check("alice posts a picture to friends only", r[0].equals("200") && r[1].contains("\"friendsOnly\":true")
                && r[1].contains("\"imageUrl\":\"/api/wall/image?id="));
        String priv = val(r[1], "id");
        String privImage = r[1].replaceAll("(?s).*\"imageUrl\":\"([^\"]+)\".*", "$1").replace("\\u0026", "&");
        check("a picture alone is fine", http("POST", "/api/wall/post", form("image", png), bob)[0].equals("200"));
        r = http("POST", "/api/wall/post", form("text", "x", "image", "data:image/png;base64,"
                + Base64.getEncoder().encodeToString("not a picture".getBytes(StandardCharsets.UTF_8))), alice);
        check("a picture must be PNG or JPEG", r[0].equals("400"));
        StringBuilder longText = new StringBuilder();
        for (int i = 0; i < 1200; i++) {
            longText.append('y');
        }
        r = http("POST", "/api/wall/post", form("text", longText.toString()), carol);
        check("long posts are shortened to 1000 characters", r[0].equals("200") && r[1].contains("\"text\":\"" + longText.substring(0, 1000) + "\""));
        check("the super-admin has no wall", http("POST", "/api/wall/post", form("text", "x"), admin)[0].equals("403"));
        cs.mute("dave", 0);
        check("a muted user cannot post", http("POST", "/api/wall/post", form("text", "x"), dave)[0].equals("403"));
        cs.unmute("dave");

        System.out.println();
        System.out.println("-- Who sees what --");
        r = http("GET", "/api/wall/user?u=alice", null, bob);
        check("bob (alice's friend) sees both her posts, newest first", r[0].equals("200") && r[1].indexOf("\"id\":" + priv) >= 0
                && r[1].indexOf("\"id\":" + priv) < r[1].indexOf("\"id\":" + pub));
        r = http("GET", "/api/wall/user?u=alice", null, carol);
        check("carol (not a friend) sees only the public one", r[1].contains("\"id\":" + pub + ",") && !r[1].contains("\"id\":" + priv + ","));
        check("alice sees her own posts, marked as hers", http("GET", "/api/wall/user?u=alice", null, alice)[1].contains("\"mine\":true"));
        check("carol cannot like the friends-only post", http("POST", "/api/wall/like", form("id", priv), carol)[0].equals("404"));
        check("the picture works with its key", isPic(getBytes(privImage)));
        check("without the key the picture is refused", getBytes("/api/wall/image?id=" + priv) == null
                && getBytes("/api/wall/image?id=" + priv + "&k=0000") == null);
        check("walls need a sign-in", http("GET", "/api/wall/user?u=alice", null, null)[0].equals("401"));
        check("nobody's wall", http("GET", "/api/wall/user?u=nobody", null, bob)[0].equals("404"));
        check("the admin sees everything", http("GET", "/api/wall/user?u=alice", null, admin)[1].contains("\"id\":" + priv + ","));

        System.out.println();
        System.out.println("-- Likes --");
        r = http("POST", "/api/wall/like", form("id", pub, "like", "true"), bob);
        check("bob likes alice's post", r[0].equals("200") && r[1].contains("\"likes\":1") && r[1].contains("\"liked\":true"));
        http("POST", "/api/wall/like", form("id", pub, "like", "true"), bob);
        check("liking twice counts once", http("POST", "/api/wall/like", form("id", pub, "like", "true"), carol)[1].contains("\"likes\":2"));
        check("unliking", http("POST", "/api/wall/like", form("id", pub, "like", "false"), carol)[1].contains("\"likes\":1"));
        check("bob sees he liked it", http("GET", "/api/wall/user?u=alice", null, bob)[1].matches("(?s).*\"id\":" + pub + ",.*?\"liked\":true.*"));

        System.out.println();
        System.out.println("-- The news feed --");
        Set<String> seen = new HashSet<String>();
        boolean neverPrivate = true;
        boolean neverOwn = true;
        for (int i = 0; i < 30; i++) {
            String b = http("GET", "/api/wall/random", null, carol)[1];
            Matcher m = Pattern.compile("\"post\":\\{\"id\":(\\d+)").matcher(b);
            if (m.find()) {
                seen.add(m.group(1));
            }
            neverPrivate &= !b.contains("\"id\":" + priv + ",");
            neverOwn &= !b.contains("\"author\":\"carol\"");
        }
        check("the feed picks public posts at random", seen.contains(pub) && seen.size() >= 2);
        check("never a friends-only post", neverPrivate);
        check("never your own post", neverOwn);
        r = http("GET", "/api/wall/random?skip=" + String.join(",", seen), null, carol);
        check("posts already shown can be skipped", r[1].contains("\"post\":null"));
        check("it says how often to show a post (every 5 stories)", r[1].contains("\"every\":5"));

        System.out.println();
        System.out.println("-- Reports and deleting --");
        check("you cannot report your own post", http("POST", "/api/wall/report", form("id", pub), alice)[0].equals("400"));
        check("carol reports alice's post", http("POST", "/api/wall/report", form("id", pub, "reason", "spam"), carol)[0].equals("200"));
        r = http("GET", "/api/admin/wall/status", null, admin);
        check("the admin sees the report and the reason", r[0].equals("200") && r[1].contains("\"reports\":1") && r[1].contains("carol: spam"));
        check("a normal user cannot see reports", http("GET", "/api/admin/wall/status", null, bob)[0].equals("403"));
        check("the admin dismisses it", http("POST", "/api/admin/wall/dismiss", form("id", pub), admin)[1].contains("\"reported\":[]"));
        check("bob cannot delete alice's post", http("POST", "/api/wall/delete", form("id", pub), bob)[0].equals("403"));
        check("alice deletes her friends-only post", http("POST", "/api/wall/delete", form("id", priv), alice)[0].equals("200")
                && wall.get(Long.parseLong(priv)) == null);
        check("its picture is gone", getBytes(privImage) == null);
        http("POST", "/api/wall/report", form("id", pub, "reason", "rude"), dave);
        check("the admin removes a reported post", http("POST", "/api/admin/wall/remove", form("id", pub), admin)[0].equals("200")
                && wall.get(Long.parseLong(pub)) == null);

        System.out.println();
        System.out.println("-- Settings and limits --");
        check("feed spacing must be 2 to 50 (or 0)", http("POST", "/api/admin/wall/settings", form("feedEvery", "1"), admin)[0].equals("400"));
        http("POST", "/api/admin/wall/settings", form("feedEvery", "0"), admin);
        check("0 turns posts in the news off", http("GET", "/api/wall/random", null, carol)[1].contains("\"post\":null"));
        http("POST", "/api/admin/wall/settings", form("feedEvery", "8"), admin);
        check("the admin sets every 8 stories", http("GET", "/api/wall/random", null, carol)[1].contains("\"every\":8"));
        int ok = 0;
        for (int i = 0; i < 25; i++) {
            if (http("POST", "/api/wall/post", form("text", "post " + i), dave)[0].equals("200")) {
                ok++;
            }
        }
        check("20 posts a day at most", ok == 20);
        t.addAndGet(25L * 3600000L);
        check("and more the next day", http("POST", "/api/wall/post", form("text", "new day"), dave)[0].equals("200"));

        System.out.println();
        System.out.println("-- Comments --");
        String bp = String.valueOf(wall.byAuthor("bob", 0, 1).get(0).id);
        check("a post starts with no comments", http("GET", "/api/wall/user?u=bob", null, carol)[1].contains("\"comments\":0")
                && http("GET", "/api/wall/user?u=bob", null, carol)[1].contains("\"canComment\":true"));
        r = http("POST", "/api/wall/comment", form("post", bp, "text", "Lovely picture!"), carol);
        check("carol comments on bob's post", r[0].equals("200") && r[1].contains("\"parent\":0") && r[1].contains("\"count\":1"));
        String c1 = val(r[1].substring(r[1].indexOf("\"comment\"")), "id");
        r = http("POST", "/api/wall/comment", form("post", bp, "parent", c1, "text", "Thanks Carol"), bob);
        check("bob replies to carol's comment", r[0].equals("200") && r[1].contains("\"parent\":" + c1) && r[1].contains("\"count\":2"));
        String c2 = val(r[1].substring(r[1].indexOf("\"comment\"")), "id");
        r = http("POST", "/api/wall/comment", form("post", bp, "parent", c2, "text", "Agreed, great shot"), dave);
        check("dave replies to the reply (a tree, any depth)", r[0].equals("200") && r[1].contains("\"parent\":" + c2));
        String c3 = val(r[1].substring(r[1].indexOf("\"comment\"")), "id");
        r = http("GET", "/api/wall/comments?post=" + bp, null, alice);
        check("everyone sees the comment tree with reply counts", r[0].equals("200") && r[1].contains("\"count\":3")
                && r[1].matches("(?s).*\"id\":" + c1 + ",\"parent\":0,.*?\"replies\":1.*")
                && r[1].matches("(?s).*\"id\":" + c2 + ",\"parent\":" + c1 + ",.*?\"replies\":1.*"));
        check("the post shows 3 comments", http("GET", "/api/wall/user?u=bob", null, carol)[1].contains("\"comments\":3"));
        check("an empty comment is refused", http("POST", "/api/wall/comment", form("post", bp, "text", "  "), carol)[0].equals("400"));
        StringBuilder lc = new StringBuilder();
        for (int i = 0; i < 600; i++) {
            lc.append('z');
        }
        r = http("POST", "/api/wall/comment", form("post", bp, "text", lc.toString()), alice);
        check("long comments are shortened to 500 characters", r[0].equals("200") && r[1].contains("\"text\":\"" + lc.substring(0, 500) + "\""));
        String c4 = val(r[1].substring(r[1].indexOf("\"comment\"")), "id");
        String alicePost = val(http("POST", "/api/wall/post", form("text", "Friends only again", "visibility", "friends"), alice)[1], "id");
        check("a reply must belong to the same post", http("POST", "/api/wall/comment", form("post", alicePost, "parent", c1, "text", "x"), bob)[0].equals("404"));
        check("someone who cannot see a post cannot comment on it",
                http("POST", "/api/wall/comment", form("post", alicePost, "text", "hi"), carol)[0].equals("404")
                && http("GET", "/api/wall/comments?post=" + alicePost, null, carol)[0].equals("404"));

        System.out.println();
        System.out.println("-- Blocking --");
        check("bob blocks carol", http("POST", "/api/wall/block", form("u", "carol"), bob)[0].equals("200")
                && http("GET", "/api/wall/blocked", null, bob)[1].contains("\"carol\""));
        r = http("POST", "/api/wall/comment", form("post", bp, "text", "Hello again"), carol);
        check("carol can no longer comment on bob's posts", r[0].equals("403"));
        check("she is told she cannot comment", http("GET", "/api/wall/user?u=bob", null, carol)[1].contains("\"canComment\":false")
                && http("GET", "/api/wall/comments?post=" + bp, null, carol)[1].contains("\"canComment\":false"));
        check("others still can", http("GET", "/api/wall/user?u=bob", null, alice)[1].contains("\"canComment\":true"));
        String alicePub = val(http("POST", "/api/wall/post", form("text", "Public question: favourite food?"), alice)[1], "id");
        String bc = val(http("POST", "/api/wall/comment", form("post", alicePub, "text", "Jollof"), bob)[1].replaceAll(".*\"comment\":", ""), "id");
        check("carol cannot answer bob's comments either", http("POST", "/api/wall/comment",
                form("post", alicePub, "parent", bc, "text", "No!"), carol)[0].equals("403"));
        check("but can still comment on alice's post", http("POST", "/api/wall/comment", form("post", alicePub, "text", "Fufu"), carol)[0].equals("200"));
        check("blocking yourself", http("POST", "/api/wall/block", form("u", "bob"), bob)[0].equals("400"));
        check("bob unblocks carol", http("POST", "/api/wall/unblock", form("u", "carol"), bob)[0].equals("200")
                && http("POST", "/api/wall/comment", form("post", bp, "text", "Back again"), carol)[0].equals("200"));

        System.out.println();
        System.out.println("-- Deleting comments --");
        check("dave cannot delete carol's comment", http("POST", "/api/wall/comment/delete", form("id", c1), dave)[0].equals("403"));
        r = http("POST", "/api/wall/comment/delete", form("id", c1), carol);
        check("carol deletes her comment", r[0].equals("200"));
        r = http("GET", "/api/wall/comments?post=" + bp, null, alice);
        check("its replies stay, under a deleted marker", r[1].matches("(?s).*\"id\":" + c1 + ",\"parent\":0,\"author\":\"\",.*?\"deleted\":true.*")
                && r[1].contains("\"id\":" + c2 + ",") && r[1].contains("\"id\":" + c3 + ","));
        check("the count drops", r[1].contains("\"count\":4"));
        check("bob (the post's author) can delete anyone's comment on it", http("POST", "/api/wall/comment/delete", form("id", c3), bob)[0].equals("200")
                && http("POST", "/api/wall/comment/delete", form("id", c2), bob)[0].equals("200"));
        r = http("GET", "/api/wall/comments?post=" + bp, null, alice);
        check("a deleted comment with no replies left is tidied away", !r[1].contains("\"id\":" + c1 + ",") && !r[1].contains("\"id\":" + c2 + ","));
        check("the admin can delete any comment", http("POST", "/api/wall/comment/delete", form("id", c4), admin)[0].equals("200"));
        check("deleting again", http("POST", "/api/wall/comment/delete", form("id", c4), admin)[0].equals("404"));
        http("POST", "/api/wall/comment", form("post", alicePost, "text", "nice"), bob);
        http("POST", "/api/wall/delete", form("id", alicePost), alice);
        check("deleting a post removes its comments", wall.comments(Long.parseLong(alicePost)).isEmpty());

        System.out.println();
        System.out.println("-- Test people post --");
        ProfilerService profiler = new ProfilerService(profiles, us, t::get, new Random(9), 3, 10, 3600000L);
        profiler.tick();
        WallService ws = new WallService(wall, profiles, us, t::get, new Random(11), 3600000L);
        int first = ws.tick();
        boolean each = true;
        for (ProfileStore.Profile p : profiles.testPeople()) {
            each &= wall.testCountBy(p.username) == 1;
        }
        check("each test person posts once at the start", first == 3 && each);
        check("nothing more in the same hour", ws.tick() == 0);
        t.addAndGet(3600000L);
        check("one more after an hour", ws.tick() == 1);
        String tp = profiles.testPeople().get(0).username;
        r = http("GET", "/api/wall/user?u=" + tp, null, carol);
        check("test posts are marked as test", r[1].contains("\"test\":true"));
        http("POST", "/api/admin/wall/settings", form("testPostsOn", "false"), admin);
        t.addAndGet(5 * 3600000L);
        check("the admin can stop test posts", ws.tick() == 0);
        us.deleteAccount("dave");
        ws.tick();
        check("posts of deleted accounts are removed", wall.byAuthor("dave", 0, 100).isEmpty());

        System.out.println();
        System.out.println("-- Saving --");
        wall.flushIfDirty();
        WallStore again = new WallStore(wallFile, wallDir);
        again.init();
        check("posts are remembered", again.size() == wall.size() && again.size() > 3);
        WallStore.Post bobs = again.byAuthor("bob", 0, 1).get(0);
        check("pictures stay with their posts", bobs.image.length() > 0 && isPic(again.image(bobs.id, bobs.imageKey())));
        check("settings are remembered", again.settings().feedEvery == 8 && !again.settings().testPostsOn);
        WallStore.Post c = again.byAuthor("carol", 0, 1).get(0);
        check("long text survives saving", c.text.length() == 1000);
        check("comments are remembered", again.comments(Long.parseLong(bp)).size() == wall.comments(Long.parseLong(bp)).size()
                && again.comments(Long.parseLong(bp)).size() >= 1
                && again.commentCount(Long.parseLong(alicePub)) == wall.commentCount(Long.parseLong(alicePub)));

        server.stop();
        System.out.println();
        if (failures == 0) {
            System.out.println("All wall tests passed.");
        } else {
            System.out.println(failures + " wall test(s) FAILED.");
        }
        System.exit(failures == 0 ? 0 : 1);
    }

    private static void check(String name, boolean ok) {
        if (ok) {
            System.out.println("  ok    " + name);
        } else {
            failures++;
            System.out.println("  FAIL  " + name);
        }
    }

    private static String val(String json, String key) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(key) + "\":\"?([^\",}]*)").matcher(json);
        return m.find() ? m.group(1) : null;
    }

    private static boolean isPic(byte[] b) {
        return b != null && b.length > 8 && (((b[0] & 0xFF) == 0x89 && b[1] == 'P') || ((b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8));
    }

    private static byte[] getBytes(String path) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:" + port + path).openConnection();
        c.setConnectTimeout(5000);
        c.setReadTimeout(15000);
        if (c.getResponseCode() != 200) {
            c.disconnect();
            return null;
        }
        InputStream in = c.getInputStream();
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) != -1) {
            out.write(buf, 0, n);
        }
        in.close();
        c.disconnect();
        return out.toByteArray();
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

    private static String form(String... kv) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            if (sb.length() > 0) {
                sb.append('&');
            }
            sb.append(URLEncoder.encode(kv[i], "UTF-8")).append('=').append(URLEncoder.encode(kv[i + 1], "UTF-8"));
        }
        return sb.toString();
    }
}