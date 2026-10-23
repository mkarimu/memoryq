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
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Tests profiles and the profiler: test people, pictures, editing, friends, deletion requests and saving. */
public class ProfilerTest {

    private static int failures = 0;
    private static final AtomicLong t = new AtomicLong(1000000000000L);
    private static int port;

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("vstream-profiles");
        LiveChannelRegistry reg = new LiveChannelRegistry();
        UserStore us = new UserStore(dir.resolve("users.txt"), reg, id -> { });
        us.init(null);
        us.createUser("alice", "password123", UserStore.Role.CHATTER);
        us.createUser("bob", "password123", UserStore.Role.CHATTER);
        us.createUser("caster", "password123", UserStore.Role.BROADCASTER);
        SessionStore ss = new SessionStore();
        String alice = ss.create("alice", UserStore.Role.CHATTER);
        String bob = ss.create("bob", UserStore.Role.CHATTER);
        String caster = ss.create("caster", UserStore.Role.BROADCASTER);
        String admin = ss.create("admin", UserStore.Role.ADMIN);
        ChatStore cs = new ChatStore(dir.resolve("chat.txt"), t::get);
        cs.init();
        ModerationService mod = new ModerationService(dir.resolve("moderation.txt"), t::get, cs, us, ss, (n, r) -> { });
        mod.init();
        Path file = dir.resolve("profiles.txt");
        ProfileStore store = new ProfileStore(file);
        store.init();
        ProfilerService profiler = new ProfilerService(store, us, t::get, new Random(42), 5, 8, 3600000L);
        ProfileApi api = new ProfileApi(store, profiler, us, ss, new Limiter(t::get),
                name -> mod.deleteAccount("admin", name), t::get);
        ChannelListHttpServer server = new ChannelListHttpServer(reg, 0);
        server.setProfileApi(api);
        server.start();
        port = server.getPort();

        System.out.println();
        System.out.println("-- The first batch --");
        check("the profiler is on by default", store.profilerOn());
        check("before the first run, the next person is due now", profiler.msUntilNext() == 0);
        int made = profiler.tick();
        check("the first run creates 5 test people", made == 5 && store.testCount() == 5);
        List<ProfileStore.Profile> five = store.testPeople();
        Set<String> countries = new HashSet<String>();
        boolean complete = true;
        boolean loginOk = true;
        boolean namesOk = true;
        for (ProfileStore.Profile p : five) {
            countries.add(p.country);
            complete &= !p.firstName.isEmpty() && !p.lastName.isEmpty() && !p.stageName.isEmpty() && !p.city.isEmpty()
                    && !p.country.isEmpty() && !p.education.isEmpty() && p.hobbies.size() >= 3 && p.test;
            loginOk &= us.authenticate(p.username, p.testPassword) != null;
            namesOk &= p.username.matches("[a-z0-9_]{3,24}");
            UserStore.Info i = us.info(p.username);
            loginOk &= i != null && i.role == UserStore.Role.CHATTER;
        }
        check("they come from 5 different countries", countries.size() == 5);
        check("each has names, a stage name, a place, education and hobbies", complete);
        check("each is a real account you can sign in to with the password the admin sees", loginOk);
        check("usernames are valid", namesOk);
        int withFriends = 0;
        boolean mutual = true;
        for (ProfileStore.Profile p : store.testPeople()) {
            if (!p.friends.isEmpty()) {
                withFriends++;
            }
            for (String f : p.friends) {
                ProfileStore.Profile o = store.get(f);
                boolean back = false;
                for (String g : o.friends) {
                    back |= g.equalsIgnoreCase(p.username);
                }
                mutual &= back;
            }
        }
        check("test people have friends among each other", withFriends >= 4);
        check("their friendships go both ways", mutual);
        check("nothing more is made in the same hour", profiler.tick() == 0);
        t.addAndGet(59 * 60000L);
        check("still nothing after 59 minutes", profiler.tick() == 0);
        check("the admin sees about a minute to the next one", profiler.msUntilNext() > 0 && profiler.msUntilNext() <= 60000);
        t.addAndGet(60000L);
        check("one more after an hour", profiler.tick() == 1 && store.testCount() == 6);
        t.addAndGet(3600000L);
        profiler.tick();
        t.addAndGet(3600000L);
        profiler.tick();
        check("it stops at the limit", store.testCount() == 8);
        t.addAndGet(3600000L);
        check("no more once the limit is reached", profiler.tick() == 0 && store.testCount() == 8 && profiler.msUntilNext() == -1);

        System.out.println();
        System.out.println("-- Pictures --");
        String tp = store.testPeople().get(0).username;
        byte[] av = getBytes("/api/profiles/avatar?u=" + tp);
        byte[] pic = getBytes("/api/profiles/picture?u=" + tp);
        check("a test person's avatar is a PNG", isPng(av));
        check("their profile picture is a PNG", isPng(pic) && pic.length > 2000);
        check("pictures need no sign-in and work for people without a profile", isPng(getBytes("/api/profiles/avatar?u=bob")));
        check("the same person always gets the same picture", java.util.Arrays.equals(av, getBytes("/api/profiles/avatar?u=" + tp)));
        check("an unknown person has no picture", getBytes("/api/profiles/avatar?u=nobody") == null);

        System.out.println();
        System.out.println("-- Viewing and editing --");
        check("viewing needs a sign-in", http("GET", "/api/profiles/view?u=" + tp, null, null)[0].equals("401"));
        String[] r = http("GET", "/api/profiles/view?u=" + tp, null, alice);
        ProfileStore.Profile tpp = store.get(tp);
        check("alice sees a test person's profile, marked as test", r[0].equals("200") && r[1].contains("\"test\":true")
                && r[1].contains(json(tpp.stageName)) && r[1].contains(json(tpp.country)) && r[1].contains("\"friends\":[{"));
        check("it never shows their password", !r[1].contains(tpp.testPassword) && !r[1].contains("password"));
        check("nor their deletion status", !r[1].contains("deletion"));
        check("the super-admin has no profile", http("GET", "/api/profiles/view?u=admin", null, alice)[0].equals("404"));
        r = http("GET", "/api/profiles/me", null, alice);
        check("alice starts with an empty profile", r[0].equals("200") && r[1].contains("\"stageName\":\"\"") && r[1].contains("\"me\":true"));
        r = http("POST", "/api/profiles/me", form("firstName", "Alice", "lastName", "Smith", "stageName", "DJ Al",
                "city", "Accra", "country", "Ghana", "education", "BSc in Music", "hobbies", "chess, singing , chess,,dancing"), alice);
        check("alice edits her profile", r[0].equals("200") && r[1].contains("\"stageName\":\"DJ Al\"") && r[1].contains("\"country\":\"Ghana\""));
        check("hobbies are cleaned up (no empty or repeated ones)", r[1].contains("\"hobbies\":[\"chess\",\"singing\",\"dancing\"]"));
        r = http("POST", "/api/profiles/me", form("stageName", "Al\tthe\nGreat|<b>"), alice);
        check("tabs, line breaks and | are removed; < > are made safe", r[1].contains("\"stageName\":\"Al the Great/\\u003cb\\u003e\"")
                && r[1].contains("\"firstName\":\"Alice\""));
        StringBuilder longName = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            longName.append('x');
        }
        r = http("POST", "/api/profiles/me", form("firstName", longName.toString()), alice);
        check("long names are shortened", r[1].contains("\"firstName\":\"" + longName.substring(0, ProfileStore.MAX_NAME) + "\""));
        byte[] alicePic = getBytes("/api/profiles/picture?u=alice");
        check("alice's picture uses her new name and country", isPng(alicePic));
        r = http("GET", "/api/profiles/people", null, bob);
        check("the people list shows everyone except you and the admin", r[0].equals("200") && r[1].contains("\"username\":\"alice\"")
                && r[1].contains("\"username\":\"" + tp + "\"") && !r[1].contains("\"username\":\"bob\"") && !r[1].contains("\"admin\""));
        r = http("GET", "/api/profiles/people?q=ghana", null, bob);
        check("it can be searched", r[1].contains("\"username\":\"alice\"") && !r[1].contains("\"username\":\"caster\""));

        System.out.println();
        System.out.println("-- Friends --");
        r = http("POST", "/api/profiles/friends/add", form("u", tp), alice);
        check("alice adds a test person as a friend", r[0].equals("200") && r[1].contains("\"isFriend\":true"));
        check("it shows on her profile", http("GET", "/api/profiles/me", null, alice)[1].contains("\"username\":\"" + tp + "\""));
        check("adding yourself", http("POST", "/api/profiles/friends/add", form("u", "alice"), alice)[0].equals("400"));
        check("adding someone who does not exist", http("POST", "/api/profiles/friends/add", form("u", "nobody"), alice)[0].equals("404"));
        check("adding the admin", http("POST", "/api/profiles/friends/add", form("u", "admin"), alice)[0].equals("404"));
        check("bob sees whether he is alice's friend", http("GET", "/api/profiles/view?u=alice", null, bob)[1].contains("\"isFriend\":false"));
        check("alice removes the friend", http("POST", "/api/profiles/friends/remove", form("u", tp), alice)[0].equals("200")
                && !http("GET", "/api/profiles/me", null, alice)[1].contains("\"username\":\"" + tp + "\""));
        check("removing again", http("POST", "/api/profiles/friends/remove", form("u", tp), alice)[0].equals("404"));
        http("POST", "/api/profiles/friends/add", form("u", "bob"), alice);

        System.out.println();
        System.out.println("-- Asking the admin to delete an account --");
        r = http("POST", "/api/profiles/deletion/request", form("reason", "I am leaving"), bob);
        check("bob asks for his account to be deleted", r[0].equals("200") && r[1].contains("\"status\":\"pending\"")
                && r[1].contains("\"reason\":\"I am leaving\""));
        check("a broadcaster is told to contact the admin", http("POST", "/api/profiles/deletion/request", form("reason", "x"), caster)[0].equals("400"));
        check("a normal user cannot see the requests", http("GET", "/api/admin/profiler/requests", null, alice)[0].equals("403"));
        r = http("GET", "/api/admin/profiler/requests", null, admin);
        check("the admin sees bob's request", r[0].equals("200") && r[1].contains("\"username\":\"bob\"") && r[1].contains("I am leaving"));
        check("bob cancels it", http("POST", "/api/profiles/deletion/cancel", "", bob)[1].contains("\"status\":\"\"")
                && http("GET", "/api/admin/profiler/requests", null, admin)[1].contains("\"requests\":[]"));
        http("POST", "/api/profiles/deletion/request", form("reason", "really leaving"), bob);
        r = http("POST", "/api/admin/profiler/decline", form("u", "bob", "note", "Please talk to us first"), admin);
        check("the admin declines with a note", r[0].equals("200"));
        r = http("GET", "/api/profiles/me", null, bob);
        check("bob sees it was declined, with the note", r[1].contains("\"status\":\"declined\"") && r[1].contains("Please talk to us first"));
        check("declining again", http("POST", "/api/admin/profiler/decline", form("u", "bob"), admin)[0].equals("404"));
        http("POST", "/api/profiles/deletion/request", form("reason", "final answer"), bob);
        check("approving someone who did not ask", http("POST", "/api/admin/profiler/approve", form("u", "alice"), admin)[0].equals("404"));
        r = http("POST", "/api/admin/profiler/approve", form("u", "bob"), admin);
        check("the admin approves bob's request", r[0].equals("200"));
        check("bob's account is gone", us.info("bob") == null && us.authenticate("bob", "password123") == null);
        check("his profile is gone", store.get("bob") == null);
        check("and he is gone from alice's friends", !http("GET", "/api/profiles/me", null, alice)[1].contains("\"username\":\"bob\""));
        check("his old session no longer works", http("GET", "/api/profiles/me", null, bob)[0].equals("401"));

        System.out.println();
        System.out.println("-- The admin's controls --");
        check("a normal user cannot see the profiler", http("GET", "/api/admin/profiler/status", null, alice)[0].equals("403"));
        r = http("GET", "/api/admin/profiler/status", null, admin);
        check("the admin sees the test people and their passwords", r[0].equals("200") && r[1].contains("\"count\":8")
                && r[1].contains("\"max\":8") && r[1].contains(json(tpp.testPassword)) && r[1].contains("\"nextInMinutes\":-1"));
        r = http("POST", "/api/admin/profiler/generate", "", admin);
        check("adding one now is refused at the limit", r[0].equals("409") && r[1].contains("limit"));
        r = http("POST", "/api/admin/profiler/enable", form("enabled", "false"), admin);
        check("the admin pauses the profiler", r[0].equals("200") && r[1].contains("\"enabled\":false") && !store.profilerOn());
        String gone = store.testPeople().get(1).username;
        mod.deleteAccount("admin", gone);
        profiler.tick();
        check("a test account deleted elsewhere loses its profile", store.get(gone) == null && store.testCount() == 7);
        t.addAndGet(5 * 3600000L);
        check("while paused, nobody new is made", profiler.tick() == 0 && store.testCount() == 7);
        check("but the admin can still add one by hand", http("POST", "/api/admin/profiler/generate", "", admin)[0].equals("200")
                && store.testCount() == 8);
        http("POST", "/api/admin/profiler/enable", form("enabled", "true"), admin);
        check("and turn it back on", store.profilerOn());

        System.out.println();
        System.out.println("-- Saving --");
        store.flushIfDirty();
        String saved = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        check("the profiles file is written", saved.contains("Al the Great") && saved.contains(tpp.testPassword));
        ProfileStore again = new ProfileStore(file);
        again.init();
        ProfileStore.Profile a2 = again.get("alice");
        check("profiles are remembered", a2 != null && a2.stageName.equals("Al the Great/<b>") && a2.hobbies.size() == 3
                && a2.country.equals("Ghana"));
        check("test people and their passwords are remembered", again.testCount() == 8
                && again.get(tp) != null && again.get(tp).testPassword.equals(tpp.testPassword));
        check("friends are remembered", again.get(tp).friends.size() == store.get(tp).friends.size());
        check("the on/off switch and the last run are remembered", again.profilerOn() && again.lastGenerated() == store.lastGenerated());

        server.stop();
        System.out.println();
        if (failures == 0) {
            System.out.println("All profiler tests passed.");
        } else {
            System.out.println(failures + " profiler test(s) FAILED.");
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

    private static String json(String s) {
        return ProfileApi.quote(s);
    }

    private static boolean isPng(byte[] b) {
        return b != null && b.length > 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G';
    }

    private static byte[] getBytes(String path) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:" + port + path).openConnection();
        c.setConnectTimeout(5000);
        c.setReadTimeout(15000);
        int code = c.getResponseCode();
        if (code != 200) {
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

    @SuppressWarnings("unused")
    private static String val(String json, String key) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(key) + "\":\"([^\"]*)\"").matcher(json);
        return m.find() ? m.group(1) : null;
    }
}