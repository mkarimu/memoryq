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

/** Tests the news slider (random people + an ad card) and the ad server (paid ads first, Google fills gaps). */
public class AggregatorTest {

    private static int failures = 0;
    private static final AtomicLong t = new AtomicLong(1760000000000L);
    private static int port;

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("vstream-ads");
        LiveChannelRegistry reg = new LiveChannelRegistry();
        UserStore us = new UserStore(dir.resolve("users.txt"), reg, id -> { });
        us.init(null);
        for (String n : new String[]{"alice", "bob", "carol", "dave", "erin", "frank", "gina", "hal"}) {
            us.createUser(n, "password123", UserStore.Role.CHATTER);
        }
        SessionStore ss = new SessionStore();
        String alice = ss.create("alice", UserStore.Role.CHATTER);
        String admin = ss.create("admin", UserStore.Role.ADMIN);
        ProfileStore profiles = new ProfileStore(dir.resolve("profiles.txt"));
        profiles.init();
        profiles.edit("bob", "Bob", "Stone", "DJ Bob", "Accra", "Ghana", "", null, t.get());
        Path adsFile = dir.resolve("ads.txt");
        Path adsDir = dir.resolve("ads");
        AdStore ads = new AdStore(adsFile, adsDir);
        ads.init();
        FeedApi api = new FeedApi(ads, profiles, us, ss, new Limiter(t::get), t::get, new Random(3));
        ChannelListHttpServer server = new ChannelListHttpServer(reg, 0);
        server.setFeedApi(api);
        server.start();
        port = server.getPort();

        System.out.println();
        System.out.println("-- The slider --");
        String[] r = http("GET", "/api/feed/slider", null, alice);
        check("it is on by default, after every 20 stories", r[0].equals("200") && r[1].contains("\"enabled\":true")
                && r[1].contains("\"every\":20"));
        check("it shows 4 people", count(r[1], "\"username\"") == 4);
        check("never yourself or the admin", !r[1].contains("\"username\":\"alice\"") && !r[1].contains("\"username\":\"admin\""));
        Set<String> seen = new HashSet<String>();
        boolean sawBob = false;
        for (int i = 0; i < 15; i++) {
            String b = http("GET", "/api/feed/slider", null, alice)[1];
            Matcher m = Pattern.compile("\"username\":\"([a-z]+)\"").matcher(b);
            while (m.find()) {
                seen.add(m.group(1));
            }
            sawBob |= b.contains("\"stageName\":\"DJ Bob\"") && b.contains("\"country\":\"Ghana\"");
        }
        check("the 4 people are picked at random each time", seen.size() >= 6);
        check("people come with their stage name and country", sawBob);
        r = http("GET", "/api/feed/slider", null, null);
        check("someone not signed in sees no people", r[1].contains("\"people\":[]"));
        check("with no paid ads, the ad card is a Google test ad",
                r[1].contains("\"type\":\"google\"") && r[1].contains(AdStore.GOOGLE_TEST_UNIT));

        System.out.println();
        System.out.println("-- Paid ads --");
        String png = "data:image/png;base64," + Base64.getEncoder().encodeToString(
                ProfileArt.avatar("x", "Ad", "Pic", 1));
        check("a normal user cannot add ads", http("POST", "/api/admin/ads/create", form("advertiser", "A"), alice)[0].equals("403"));
        check("nobody signed in cannot either", http("GET", "/api/admin/ads/list", null, null)[0].equals("401"));
        check("an ad needs an advertiser and headline", http("POST", "/api/admin/ads/create",
                form("advertiser", "", "headline", "", "link", "https://x.test", "image", png), admin)[0].equals("400"));
        r = http("POST", "/api/admin/ads/create", form("advertiser", "Shop", "headline", "Sale", "link", "javascript:alert(1)", "image", png), admin);
        check("the link must be a web address", r[0].equals("400") && r[1].contains("https://"));
        r = http("POST", "/api/admin/ads/create", form("advertiser", "Shop", "headline", "Sale", "link", "https://shop.test",
                "image", "data:text/plain;base64," + Base64.getEncoder().encodeToString("hello there".getBytes(StandardCharsets.UTF_8))), admin);
        check("the picture must be a PNG or JPEG", r[0].equals("400") && r[1].contains("PNG or JPEG"));
        check("a picture is required", http("POST", "/api/admin/ads/create",
                form("advertiser", "Shop", "headline", "Sale", "link", "https://shop.test"), admin)[0].equals("400"));
        check("dates are checked", http("POST", "/api/admin/ads/create", form("advertiser", "Shop", "headline", "Sale",
                "link", "https://shop.test", "image", png, "start", "next week"), admin)[0].equals("400"));
        check("the end must be after the start", http("POST", "/api/admin/ads/create", form("advertiser", "Shop", "headline", "Sale",
                "link", "https://shop.test", "image", png, "start", "2030-05-10", "end", "2030-05-01"), admin)[0].equals("400"));
        byte[] big = new byte[AdStore.MAX_IMAGE + 10];
        big[0] = (byte) 0xFF;
        big[1] = (byte) 0xD8;
        big[2] = (byte) 0xFF;
        r = http("POST", "/api/admin/ads/create", form("advertiser", "Shop", "headline", "Sale", "link", "https://shop.test",
                "image", "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(big)), admin);
        check("pictures over 1 MB are refused", r[0].equals("400") || r[0].equals("413"));
        r = http("POST", "/api/admin/ads/create", form("advertiser", "Mama's Kitchen", "headline", "Lunch for 5 cedis",
                "text", "Fresh jollof every day", "link", "https://kitchen.test/menu", "image", png), admin);
        check("the admin adds a paid ad", r[0].equals("200") && r[1].contains("\"headline\":\"Lunch for 5 cedis\"")
                && r[1].contains("\"running\":true"));
        String adId = val(r[1], "id");
        r = http("GET", "/api/feed/slider", null, alice);
        check("now the slider carries the paid ad instead of Google", r[1].contains("\"type\":\"paid\"")
                && r[1].contains("\"advertiser\":\"Mama's Kitchen\"") && r[1].contains("\"imageUrl\":\"/api/feed/ad/image?id=" + adId + "\""));
        check("the ad picture is served", isPng(getBytes("/api/feed/ad/image?id=" + adId)));
        check("an unknown ad has no picture", getBytes("/api/feed/ad/image?id=999") == null);

        System.out.println();
        System.out.println("-- Counting views and taps --");
        check("a view is counted", http("POST", "/api/feed/ad/view", form("id", adId), alice)[0].equals("200"));
        http("POST", "/api/feed/ad/view", form("id", adId), alice);
        http("POST", "/api/feed/ad/view", form("id", adId), null);
        check("the same phone is counted once every 10 minutes", ads.get(Long.parseLong(adId)).views == 1);
        t.addAndGet(11 * 60000L);
        http("POST", "/api/feed/ad/view", form("id", adId), alice);
        check("and again after 10 minutes", ads.get(Long.parseLong(adId)).views == 2);
        r = http("POST", "/api/feed/ad/click", form("id", adId), alice);
        check("a tap is counted and returns the link", r[0].equals("200") && r[1].contains("https://kitchen.test/menu")
                && ads.get(Long.parseLong(adId)).clicks == 1);
        http("POST", "/api/feed/ad/click", form("id", adId), alice);
        check("double taps count once", ads.get(Long.parseLong(adId)).clicks == 1);
        check("an unknown ad cannot be counted", http("POST", "/api/feed/ad/view", form("id", "999"), alice)[0].equals("404"));
        r = http("GET", "/api/admin/ads/list", null, admin);
        check("the admin sees the views and taps", r[1].contains("\"views\":2") && r[1].contains("\"clicks\":1"));

        System.out.println();
        System.out.println("-- Starting, stopping and scheduling ads --");
        http("POST", "/api/admin/ads/active", form("id", adId, "active", "false"), admin);
        check("a paused ad is not shown; Google fills the gap", http("GET", "/api/feed/slider", null, alice)[1].contains("\"type\":\"google\""));
        http("POST", "/api/admin/ads/active", form("id", adId, "active", "true"), admin);
        r = http("POST", "/api/admin/ads/create", form("advertiser", "Future Co", "headline", "Coming soon", "link",
                "https://future.test", "image", png, "start", "2099-01-01"), admin);
        check("an ad with a future start date is not running yet", r[1].contains("\"headline\":\"Coming soon\"")
                && r[1].matches("(?s).*Coming soon.*?\"running\":false.*"));
        boolean onlyKitchen = true;
        for (int i = 0; i < 10; i++) {
            onlyKitchen &= !http("GET", "/api/feed/slider", null, alice)[1].contains("Future Co");
        }
        check("so it is never picked", onlyKitchen);
        String futureId = val(r[1].substring(r[1].indexOf("Future Co") - 40), "id");
        check("the admin deletes it", http("POST", "/api/admin/ads/delete", form("id", futureId), admin)[0].equals("200")
                && !http("GET", "/api/admin/ads/list", null, admin)[1].contains("Future Co"));
        check("deleting again", http("POST", "/api/admin/ads/delete", form("id", futureId), admin)[0].equals("404"));

        System.out.println();
        System.out.println("-- Slider settings --");
        check("every must be 5 to 100", http("POST", "/api/admin/ads/settings", form("every", "2"), admin)[0].equals("400"));
        check("a wrong Google ad unit is refused", http("POST", "/api/admin/ads/settings", form("googleUnit", "my-ad"), admin)[0].equals("400"));
        r = http("POST", "/api/admin/ads/settings", form("every", "10", "googleUnit", "ca-app-pub-1234567890123456/1234567890"), admin);
        check("the admin sets every 10 stories and a real ad unit", r[0].equals("200") && r[1].contains("\"every\":10")
                && r[1].contains("\"googleTest\":false"));
        http("POST", "/api/admin/ads/active", form("id", adId, "active", "false"), admin);
        r = http("GET", "/api/feed/slider", null, alice);
        check("phones get the new settings", r[1].contains("\"every\":10") && r[1].contains("ca-app-pub-1234567890123456/1234567890"));
        http("POST", "/api/admin/ads/settings", form("googleOn", "false"), admin);
        check("with Google off and no paid ad, there is no ad card", http("GET", "/api/feed/slider", null, alice)[1].contains("\"ad\":null"));
        http("POST", "/api/admin/ads/active", form("id", adId, "active", "true"), admin);
        http("POST", "/api/admin/ads/settings", form("adCardOn", "false"), admin);
        r = http("GET", "/api/feed/slider", null, alice);
        check("with the ad card off, only people are shown", r[1].contains("\"ad\":null") && count(r[1], "\"username\"") == 4);
        http("POST", "/api/admin/ads/settings", form("sliderOn", "false"), admin);
        r = http("GET", "/api/feed/slider", null, alice);
        check("with the slider off, nothing is shown", r[1].contains("\"enabled\":false") && r[1].contains("\"people\":[]")
                && r[1].contains("\"ad\":null"));
        http("POST", "/api/admin/ads/settings", form("sliderOn", "true", "adCardOn", "true", "googleOn", "true", "googleUnit", ""), admin);
        check("an empty ad unit goes back to Google's test ads", http("GET", "/api/admin/ads/list", null, admin)[1].contains("\"googleTest\":true"));

        System.out.println();
        System.out.println("-- Saving --");
        ads.flushIfDirty();
        AdStore again = new AdStore(adsFile, adsDir);
        again.init();
        AdStore.Ad k = again.get(Long.parseLong(adId));
        check("ads and their counts are remembered", k != null && k.headline.equals("Lunch for 5 cedis") && k.views == 2 && k.clicks == 1
                && k.advertiser.equals("Mama's Kitchen"));
        check("the picture is kept on disk", again.image(k.id) != null && isPng(again.image(k.id)));
        check("settings are remembered", again.settings().every == 10 && again.settings().sliderOn && again.settings().googleOn);
        check("a deleted ad stays deleted, with its picture removed", again.get(Long.parseLong(futureId)) == null
                && !Files.exists(adsDir.resolve("ad-" + futureId + ".png")));

        server.stop();
        System.out.println();
        if (failures == 0) {
            System.out.println("All aggregator tests passed.");
        } else {
            System.out.println(failures + " aggregator test(s) FAILED.");
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

    private static boolean isPng(byte[] b) {
        return b != null && b.length > 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G';
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
            try {
                os.write((body == null ? "" : body).getBytes(StandardCharsets.UTF_8));
                os.close();
            } catch (java.io.IOException e) {
                // the server may stop reading a body that is too large
            }
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