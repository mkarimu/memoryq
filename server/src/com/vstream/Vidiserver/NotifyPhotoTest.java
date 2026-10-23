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
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Tests notifications (comments, replies, likes, friend adds, blocks, test people reacting) and profile photos. */
public class NotifyPhotoTest {

    private static int failures = 0;
    private static final AtomicLong t = new AtomicLong(1760000000000L);
    private static int port;

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("vstream-notify");
        LiveChannelRegistry reg = new LiveChannelRegistry();
        UserStore us = new UserStore(dir.resolve("users.txt"), reg, id -> { });
        us.init(null);
        for (String n : new String[]{"alice", "bob", "carol"}) {
            us.createUser(n, "password123", UserStore.Role.CHATTER);
        }
        SessionStore ss = new SessionStore();
        String alice = ss.create("alice", UserStore.Role.CHATTER);
        String bob = ss.create("bob", UserStore.Role.CHATTER);
        String carol = ss.create("carol", UserStore.Role.CHATTER);
        String admin = ss.create("admin", UserStore.Role.ADMIN);
        ChatStore cs = new ChatStore(dir.resolve("chat.txt"), t::get);
        cs.init();
        Path photos = dir.resolve("profile-photos");
        ProfileStore profiles = new ProfileStore(dir.resolve("profiles.txt"), photos);
        profiles.init();
        WallStore wall = new WallStore(dir.resolve("wall.txt"), dir.resolve("wall"));
        wall.init();
        CallService calls = new CallService(dir.resolve("calls.txt"), t::get, cs, us, false);
        calls.init();
        NotificationStore notes = new NotificationStore(dir.resolve("notifications.txt"));
        notes.init();
        WallApi wallApi = new WallApi(wall, profiles, us, ss, cs, new Limiter(t::get), t::get, new Random(5));
        wallApi.setBlocks(calls);
        wallApi.setNotifications(notes);
        ProfilerService profiler = new ProfilerService(profiles, us, t::get, new Random(9), 3, 10, 3600000L);
        ProfileApi profileApi = new ProfileApi(profiles, profiler, us, ss, new Limiter(t::get), name -> {
            us.deleteAccount(name);
        }, t::get);
        profileApi.setNotifications(notes, calls);
        ChannelListHttpServer server = new ChannelListHttpServer(reg, 0);
        server.setWallApi(wallApi);
        server.setProfileApi(profileApi);
        server.setNotifyApi(new NotifyApi(notes, profiles, us, ss));
        server.start();
        port = server.getPort();

        System.out.println();
        System.out.println("-- Notifications --");
        check("the list needs a sign-in", http("GET", "/api/notify/list", null, null)[0].equals("401"));
        check("alice starts with none", http("GET", "/api/notify/count", null, alice)[1].contains("\"unread\":0"));
        String post = val(http("POST", "/api/wall/post", form("text", "Hello world"), alice)[1], "id");
        String c1 = cid(http("POST", "/api/wall/comment", form("post", post, "text", "Welcome Alice!"), bob)[1]);
        String[] r = http("GET", "/api/notify/list", null, alice);
        check("bob's comment notifies alice", r[1].contains("\"unread\":1") && r[1].contains("\"type\":\"comment\"")
                && r[1].contains("\"actor\":\"bob\"") && r[1].contains("\"post\":" + post) && r[1].contains("Welcome Alice!"));
        cid(http("POST", "/api/wall/comment", form("post", post, "parent", c1, "text", "Hi both"), carol)[1]);
        check("carol's reply notifies bob (reply) and alice (comment)",
                http("GET", "/api/notify/list", null, bob)[1].contains("\"type\":\"reply\"")
                && http("GET", "/api/notify/count", null, alice)[1].contains("\"unread\":2"));
        http("POST", "/api/wall/comment", form("post", post, "parent", c1, "text", "Thanks Bob"), alice);
        check("alice answering bob notifies only bob, not herself", http("GET", "/api/notify/count", null, alice)[1].contains("\"unread\":2")
                && count(http("GET", "/api/notify/list", null, bob)[1], "\"type\":\"reply\"") == 2);
        http("POST", "/api/wall/like", form("id", post, "like", "true"), bob);
        http("POST", "/api/wall/like", form("id", post, "like", "false"), bob);
        http("POST", "/api/wall/like", form("id", post, "like", "true"), bob);
        r = http("GET", "/api/notify/list", null, alice);
        check("a like notifies once, even after unlike and like again", count(r[1], "\"type\":\"like\"") == 1);
        http("POST", "/api/profiles/friends/add", form("u", "alice"), bob);
        http("POST", "/api/profiles/friends/remove", form("u", "alice"), bob);
        http("POST", "/api/profiles/friends/add", form("u", "alice"), bob);
        r = http("GET", "/api/notify/list", null, alice);
        check("a friend add notifies (once)", count(r[1], "\"type\":\"friend\"") == 1);
        check("bob's list is his own", !http("GET", "/api/notify/list", null, bob)[1].contains("\"type\":\"friend\""));

        System.out.println();
        System.out.println("-- Blocked people do not notify --");
        int before = unread(alice);
        http("POST", "/api/wall/block", form("u", "carol"), alice);
        http("POST", "/api/wall/like", form("id", post, "like", "true"), carol);
        http("POST", "/api/profiles/friends/add", form("u", "alice"), carol);
        check("carol's like and friend add do not reach alice", unread(alice) == before);

        System.out.println();
        System.out.println("-- Reading --");
        String firstId = val(http("GET", "/api/notify/list", null, alice)[1].replaceAll("^\\{\"unread\":\\d+,\"items\":\\[", ""), "id");
        r = http("POST", "/api/notify/read", form("id", firstId), alice);
        check("marking one as read", r[1].contains("\"unread\":" + (before - 1)));
        check("marking all as read", http("POST", "/api/notify/read", "", alice)[1].contains("\"unread\":0")
                && http("GET", "/api/notify/list", null, alice)[1].contains("\"read\":true"));
        check("a notification opens its post", http("GET", "/api/wall/one?id=" + post, null, bob)[1].contains("\"text\":\"Hello world\""));
        String priv = val(http("POST", "/api/wall/post", form("text", "secret", "visibility", "friends"), bob)[1], "id");
        check("a hidden post cannot be opened", http("GET", "/api/wall/one?id=" + priv, null, carol)[0].equals("404"));

        System.out.println();
        System.out.println("-- Test people react --");
        profiler.tick();
        WallService ws = new WallService(wall, profiles, us, t::get, new Random(11), 3600000L);
        ws.setNotifications(notes);
        ws.tick();
        String fresh = val(http("POST", "/api/wall/post", form("text", "Anyone up for football?"), alice)[1], "id");
        http("POST", "/api/notify/read", "", alice);
        ws.tick();
        check("not straight away", unread(alice) == 0);
        t.addAndGet(3 * 60000L);
        ws.tick();
        r = http("GET", "/api/notify/list", null, alice);
        check("a few minutes later a test person likes and comments", unread(alice) == 2 && r[1].contains("\"type\":\"like\"")
                && r[1].contains("\"type\":\"comment\"") && wall.commentCount(Long.parseLong(fresh)) == 1);
        ws.tick();
        t.addAndGet(60000L);
        ws.tick();
        check("only once per post", wall.commentCount(Long.parseLong(fresh)) == 1);
        us.deleteAccount("bob");
        ws.tick();
        check("notifications from a deleted account disappear", !http("GET", "/api/notify/list", null, alice)[1].contains("\"actor\":\"bob\""));

        System.out.println();
        System.out.println("-- Profile photos --");
        byte[] scene = ProfileArt.scene(4, "Ghana", "");
        r = http("POST", "/api/profiles/photo", form("image", "data:image/png;base64," + Base64.getEncoder().encodeToString(scene)), alice);
        check("alice uploads a photo", r[0].equals("200") && r[1].contains("\"hasPhoto\":true"));
        byte[] av = getBytes("/api/profiles/avatar?u=alice");
        java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(av));
        check("her avatar is now her photo, cropped square (JPEG)", (av[0] & 0xFF) == 0xFF && (av[1] & 0xFF) == 0xD8
                && img.getWidth() == img.getHeight() && img.getWidth() == 500);
        check("the profile picture is the photo too", java.util.Arrays.equals(av, getBytes("/api/profiles/picture?u=alice")));
        check("others see that she has a photo", http("GET", "/api/profiles/view?u=alice", null, carol)[1].contains("\"hasPhoto\":true"));
        r = http("POST", "/api/profiles/photo", form("image", "data:image/png;base64,"
                + Base64.getEncoder().encodeToString("not a photo".getBytes(StandardCharsets.UTF_8))), alice);
        check("something that is not a photo is refused", r[0].equals("400"));
        check("the admin sees recent photos", http("GET", "/api/admin/profiler/photos", null, admin)[1].contains("\"username\":\"alice\""));
        check("a normal user cannot", http("GET", "/api/admin/profiler/photos", null, carol)[0].equals("403"));
        check("the admin can take a photo down", http("POST", "/api/admin/profiler/removephoto", form("u", "alice"), admin)[0].equals("200")
                && profiles.get("alice").photo.isEmpty());
        byte[] drawn = getBytes("/api/profiles/avatar?u=alice");
        check("then the drawn avatar comes back", drawn != null && (drawn[0] & 0xFF) == 0x89);
        http("POST", "/api/profiles/photo", form("image", "data:image/png;base64," + Base64.getEncoder().encodeToString(scene)), alice);
        check("alice removes her own photo", http("POST", "/api/profiles/photo/remove", "", alice)[1].contains("\"hasPhoto\":false"));
        http("POST", "/api/profiles/photo", form("image", "data:image/png;base64," + Base64.getEncoder().encodeToString(scene)), alice);
        long files = Files.list(photos).count();
        check("old photo files are cleaned up (one left)", files == 1);
        profiles.flushIfDirty();
        ProfileStore again = new ProfileStore(dir.resolve("profiles.txt"), photos);
        again.init();
        check("the photo is remembered", again.photo("alice") != null);
        again.remove("alice");
        check("deleting a profile deletes its photo", Files.list(photos).count() == 0);
        notes.flushIfDirty();
        NotificationStore notesAgain = new NotificationStore(dir.resolve("notifications.txt"));
        notesAgain.init();
        check("notifications are remembered", notesAgain.list("alice", 100).size() == notes.list("alice", 100).size()
                && notesAgain.list("alice", 100).size() > 0);

        server.stop();
        System.out.println();
        if (failures == 0) {
            System.out.println("All notification and photo tests passed.");
        } else {
            System.out.println(failures + " notification/photo test(s) FAILED.");
        }
        System.exit(failures == 0 ? 0 : 1);
    }

    private static int unread(String token) throws Exception {
        Matcher m = Pattern.compile("\"unread\":(\\d+)").matcher(http("GET", "/api/notify/count", null, token)[1]);
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }

    private static String cid(String json) {
        return val(json.substring(json.indexOf("\"comment\"")), "id");
    }

    private static void check(String name, boolean ok) {
        if (ok) {
            System.out.println("  ok    " + name);
        } else {
            failures++;
            System.out.println("  FAIL  " + name);
        }
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

    private static String val(String json, String key) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(key) + "\":\"?([^\",}]*)").matcher(json);
        return m.find() ? m.group(1) : null;
    }

    private static byte[] getBytes(String path) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:" + port + path).openConnection();
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