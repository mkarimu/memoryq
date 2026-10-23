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

/** (unused) Tests notifications (comments, replies, likes, friend adds, blocks, test people reacting) and profile photos. */
/** Tests card colours: choosing one, the automatic colour, colours too light for white writing, saving. */
public class CardColorTest {

    private static int failures = 0;
    private static final AtomicLong t = new AtomicLong(1760000000000L);
    private static int port;
    /** The colours offered in the app and on the web. */
    static final String[] PALETTE = {"#c62828", "#ad1457", "#6a1b9a", "#4527a0", "#283593", "#1565c0", "#0277bd",
            "#00838f", "#00695c", "#2e7d32", "#558b2f", "#8d6e00", "#e65100", "#bf360c", "#5d4037", "#455a64",
            "#37474f", "#212121"};

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("vstream-color");
        LiveChannelRegistry reg = new LiveChannelRegistry();
        UserStore us = new UserStore(dir.resolve("users.txt"), reg, id -> { });
        us.init(null);
        for (String n : new String[]{"alice", "bob"}) {
            us.createUser(n, "password123", UserStore.Role.CHATTER);
        }
        SessionStore ss = new SessionStore();
        String alice = ss.create("alice", UserStore.Role.CHATTER);
        String bob = ss.create("bob", UserStore.Role.CHATTER);
        Path pfile = dir.resolve("profiles.txt");
        ProfileStore profiles = new ProfileStore(pfile, dir.resolve("profile-photos"));
        profiles.init();
        ProfilerService profiler = new ProfilerService(profiles, us, t::get, new Random(9), 3, 10, 3600000L);
        ProfileApi profileApi = new ProfileApi(profiles, profiler, us, ss, new Limiter(t::get), name -> {
            us.deleteAccount(name);
        }, t::get);
        ChannelListHttpServer server = new ChannelListHttpServer(reg, 0);
        server.setProfileApi(profileApi);
        server.start();
        port = server.getPort();

        System.out.println();
        System.out.println("-- Card colours --");
        boolean all = true;
        for (String c : PALETTE) {
            all &= c.equals(ProfileStore.checkColor(c));
        }
        check("every offered colour is dark enough for white writing", all);
        check("white and pale yellow are refused", ProfileStore.checkColor("#ffffff") == null
                && ProfileStore.checkColor("#fff59d") == null);
        check("bad text is refused", ProfileStore.checkColor("red") == null && ProfileStore.checkColor("#12345") == null
                && ProfileStore.checkColor(null) == null);
        String auto = ProfileStore.autoColor("Alice");
        check("the automatic colour is the same for any capitals", auto.equals(ProfileStore.autoColor("alice"))
                && auto.matches("#[0-9a-f]{6}") && ProfileStore.checkColor(auto) != null);

        String[] r = http("GET", "/api/profiles/me", null, alice);
        check("alice starts with the automatic colour", r[1].contains("\"color\":\"" + auto + "\"")
                && r[1].contains("\"colorSet\":false"));
        check("choosing a colour needs a sign-in", http("POST", "/api/profiles/color", form("color", "#1565c0"), null)[0].equals("401"));
        r = http("POST", "/api/profiles/color", form("color", "#1565C0"), alice);
        check("alice picks blue", r[0].equals("200") && r[1].contains("\"color\":\"#1565c0\"")
                && r[1].contains("\"colorSet\":true"));
        r = http("GET", "/api/profiles/people", null, bob);
        check("bob sees alice's card in blue", r[1].contains("\"username\":\"alice\"") && r[1].contains("\"color\":\"#1565c0\""));
        r = http("GET", "/api/profiles/view?u=alice", null, bob);
        check("and on her profile", r[1].contains("\"color\":\"#1565c0\""));
        r = http("POST", "/api/profiles/color", form("color", "#ffffff"), alice);
        check("white is refused with a reason", r[0].equals("400") && r[1].contains("too light"));
        check("a refused colour changes nothing", http("GET", "/api/profiles/me", null, alice)[1].contains("\"color\":\"#1565c0\""));
        check("rubbish is refused", http("POST", "/api/profiles/color", form("color", "blue;x"), alice)[0].equals("400"));

        ProfileStore again = new ProfileStore(pfile, dir.resolve("profile-photos"));
        again.init();
        check("the colour is saved", "#1565c0".equals(again.cardColor("alice"))
                && ProfileStore.autoColor("nobody").equals(again.cardColor("nobody")));
        String old = new String(Files.readAllBytes(pfile), StandardCharsets.UTF_8);
        check("the file line still ends with end", old.contains("\t#1565c0\tend\n"));
        // a file from before card colours (no colour column) still loads
        Files.write(pfile, old.replace("\t#1565c0\tend\n", "\tend\n").getBytes(StandardCharsets.UTF_8));
        ProfileStore older = new ProfileStore(pfile, dir.resolve("profile-photos"));
        older.init();
        check("an older file loads with the automatic colour", auto.equals(older.cardColor("alice"))
                && older.get("alice") != null);

        r = http("POST", "/api/profiles/color", form("color", "auto"), alice);
        check("back to automatic", r[0].equals("200") && r[1].contains("\"color\":\"" + auto + "\"")
                && r[1].contains("\"colorSet\":false"));
        r = http("POST", "/api/profiles/color", form("color", "#2e7d32"), bob);
        check("bob picks green (only his card changes)", r[1].contains("#2e7d32")
                && http("GET", "/api/profiles/me", null, alice)[1].contains("\"color\":\"" + auto + "\""));

        server.stop();
        System.out.println(failures == 0 ? "ALL CARD COLOUR TESTS PASSED" : failures + " CARD COLOUR TEST(S) FAILED");
        if (failures > 0) {
            System.exit(1);
        }
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