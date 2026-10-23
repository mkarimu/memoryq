package com.vstream.Vidiserver;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

/**
 * The aggregator: the slider shown between news stories (4 random people and an ad card) and the ad server.
 *   GET  /api/feed/slider                 people (only for signed-in users) and one ad: paid first, else Google
 *   GET  /api/feed/ad/image?id=           a paid ad's picture
 *   POST /api/feed/ad/view   id=          counts a view (once per phone and ad every 10 minutes)
 *   POST /api/feed/ad/click  id=          counts a tap and returns the link
 *   /api/admin/ads/list | create | active | delete | settings   (super-admin only)
 */
public final class FeedApi {

    private static final int MAX_BODY = 8 * 1024;
    private static final int MAX_UPLOAD = 2 * 1024 * 1024;
    private static final int PEOPLE = 4;
    private static final Pattern GOOGLE_UNIT = Pattern.compile("ca-app-pub-[0-9]{10,20}/[0-9]{6,12}");

    private final AdStore ads;
    private final ProfileStore profiles;
    private final UserStore users;
    private final SessionStore sessions;
    private final Limiter limiter;
    private final LongSupplier clock;
    private final Random random;

    public FeedApi(AdStore ads, ProfileStore profiles, UserStore users, SessionStore sessions, Limiter limiter,
                   LongSupplier clock, Random random) {
        this.ads = ads;
        this.profiles = profiles;
        this.users = users;
        this.sessions = sessions;
        this.limiter = limiter;
        this.clock = clock;
        this.random = random;
    }

    public void register(HttpServer server) {
        server.createContext("/api/feed/", new HttpHandler() {
            @Override
            public void handle(HttpExchange ex) throws IOException {
                handleFeed(ex);
            }
        });
        server.createContext("/api/admin/ads/", new HttpHandler() {
            @Override
            public void handle(HttpExchange ex) throws IOException {
                handleAdmin(ex);
            }
        });
    }

    private static final class Bad extends Exception {
        private static final long serialVersionUID = 1L;
        final int status;

        Bad(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    // ---- Phones ----

    private void handleFeed(HttpExchange ex) throws IOException {
        try {
            String path = ex.getRequestURI().getPath();
            String method = ex.getRequestMethod();
            switch (path) {
                case "/api/feed/slider":
                    if (requireMethod(ex, method, "GET")) {
                        slider(ex);
                    }
                    break;
                case "/api/feed/ad/image":
                    if (requireMethod(ex, method, "GET")) {
                        adImage(ex, id(MediaAuthHttpServer.queryParam(ex.getRequestURI().getRawQuery(), "id")));
                    }
                    break;
                case "/api/feed/ad/view":
                    if (requireMethod(ex, method, "POST")) {
                        long id = id(MediaAuthHttpServer.queryParam(readBody(ex, MAX_BODY), "id"));
                        String k = "av|" + ip(ex) + "|" + id;
                        if (ads.get(id) == null) {
                            throw new Bad(404, "no such ad");
                        }
                        if (limiter.count(k, 10 * 60000L) == 0) {
                            limiter.record(k);
                            ads.countView(id);
                        }
                        send(ex, 200, "{\"ok\":true}");
                    }
                    break;
                case "/api/feed/ad/click":
                    if (requireMethod(ex, method, "POST")) {
                        long id = id(MediaAuthHttpServer.queryParam(readBody(ex, MAX_BODY), "id"));
                        AdStore.Ad a = ads.get(id);
                        if (a == null) {
                            throw new Bad(404, "no such ad");
                        }
                        String k = "ac|" + ip(ex) + "|" + id;
                        if (limiter.count(k, 60000L) == 0) {
                            limiter.record(k);
                            ads.countClick(id);
                        }
                        send(ex, 200, "{\"link\":" + ProfileApi.quote(a.link) + "}");
                    }
                    break;
                default:
                    send(ex, 404, error("not found"));
            }
        } catch (Bad e) {
            send(ex, e.status, error(e.getMessage()));
        } catch (Exception e) {
            send(ex, 500, error("server error"));
        } finally {
            ex.close();
        }
    }

    private void slider(HttpExchange ex) throws IOException {
        AdStore.Settings s = ads.settings();
        StringBuilder sb = new StringBuilder("{\"enabled\":").append(s.sliderOn).append(",\"every\":").append(s.every);
        sb.append(",\"people\":[");
        UserStore.Info me = signedIn(ex);
        if (s.sliderOn && me != null) {
            List<UserStore.Info> pool = new ArrayList<UserStore.Info>();
            for (UserStore.Info i : users.listInfos()) {
                if (!i.disabled && i.role != UserStore.Role.ADMIN && !i.username.equalsIgnoreCase(me.username)) {
                    pool.add(i);
                }
            }
            Collections.shuffle(pool, random);
            for (int n = 0; n < Math.min(PEOPLE, pool.size()); n++) {
                ProfileStore.Profile p = profiles == null ? null : profiles.get(pool.get(n).username);
                if (n > 0) {
                    sb.append(',');
                }
                sb.append("{\"username\":").append(ProfileApi.quote(pool.get(n).username))
                        .append(",\"stageName\":").append(ProfileApi.quote(p == null ? "" : p.stageName))
                        .append(",\"firstName\":").append(ProfileApi.quote(p == null ? "" : p.firstName))
                        .append(",\"lastName\":").append(ProfileApi.quote(p == null ? "" : p.lastName))
                        .append(",\"country\":").append(ProfileApi.quote(p == null ? "" : p.country))
                        .append(",\"test\":").append(p != null && p.test).append('}');
            }
        }
        sb.append("],\"ad\":");
        AdStore.Ad paid = s.sliderOn && s.adCardOn ? ads.pick(clock.getAsLong(), random) : null;
        if (paid != null) {
            sb.append("{\"type\":\"paid\",\"id\":").append(paid.id)
                    .append(",\"advertiser\":").append(ProfileApi.quote(paid.advertiser))
                    .append(",\"headline\":").append(ProfileApi.quote(paid.headline))
                    .append(",\"text\":").append(ProfileApi.quote(paid.text))
                    .append(",\"imageUrl\":").append(ProfileApi.quote("/api/feed/ad/image?id=" + paid.id)).append('}');
        } else if (s.sliderOn && s.adCardOn && s.googleOn && !s.googleUnit.isEmpty()) {
            sb.append("{\"type\":\"google\",\"unitId\":").append(ProfileApi.quote(s.googleUnit)).append('}');
        } else {
            sb.append("null");
        }
        send(ex, 200, sb.append('}').toString());
    }

    private void adImage(HttpExchange ex, long id) throws IOException {
        AdStore.Ad a = ads.get(id);
        byte[] img = a == null ? null : ads.image(id);
        if (img == null) {
            send(ex, 404, error("no such picture"));
            return;
        }
        ex.getResponseHeaders().set("Content-Type", a.image.endsWith(".png") ? "image/png" : "image/jpeg");
        ex.getResponseHeaders().set("Cache-Control", "max-age=600");
        ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        ex.sendResponseHeaders(200, img.length);
        OutputStream os = ex.getResponseBody();
        os.write(img);
        os.close();
    }

    // ---- Super-admin ----

    private void handleAdmin(HttpExchange ex) throws IOException {
        try {
            UserStore.Info me = signedIn(ex);
            if (me == null) {
                send(ex, 401, error("sign in required"));
                return;
            }
            if (me.role != UserStore.Role.ADMIN) {
                send(ex, 403, error("admin only"));
                return;
            }
            String path = ex.getRequestURI().getPath();
            String method = ex.getRequestMethod();
            switch (path) {
                case "/api/admin/ads/list":
                    if (requireMethod(ex, method, "GET")) {
                        send(ex, 200, listJson());
                    }
                    break;
                case "/api/admin/ads/create":
                    if (requireMethod(ex, method, "POST")) {
                        create(ex);
                    }
                    break;
                case "/api/admin/ads/active": {
                    if (!requireMethod(ex, method, "POST")) {
                        break;
                    }
                    String body = readBody(ex, MAX_BODY);
                    if (!ads.setActive(id(MediaAuthHttpServer.queryParam(body, "id")),
                            "true".equals(MediaAuthHttpServer.queryParam(body, "active")))) {
                        throw new Bad(404, "no such ad");
                    }
                    ads.flushIfDirty();
                    send(ex, 200, listJson());
                    break;
                }
                case "/api/admin/ads/delete":
                    if (requireMethod(ex, method, "POST")) {
                        if (!ads.delete(id(MediaAuthHttpServer.queryParam(readBody(ex, MAX_BODY), "id")))) {
                            throw new Bad(404, "no such ad");
                        }
                        ads.flushIfDirty();
                        send(ex, 200, listJson());
                    }
                    break;
                case "/api/admin/ads/settings":
                    if (requireMethod(ex, method, "POST")) {
                        settings(readBody(ex, MAX_BODY));
                        send(ex, 200, listJson());
                    }
                    break;
                default:
                    send(ex, 404, error("not found"));
            }
        } catch (Bad e) {
            send(ex, e.status, error(e.getMessage()));
        } catch (Exception e) {
            send(ex, 500, error("server error"));
        } finally {
            ex.close();
        }
    }

    private void settings(String body) throws Bad {
        AdStore.Settings s = ads.settings();
        String v = MediaAuthHttpServer.queryParam(body, "sliderOn");
        if (v != null) {
            s.sliderOn = "true".equals(v);
        }
        v = MediaAuthHttpServer.queryParam(body, "adCardOn");
        if (v != null) {
            s.adCardOn = "true".equals(v);
        }
        v = MediaAuthHttpServer.queryParam(body, "googleOn");
        if (v != null) {
            s.googleOn = "true".equals(v);
        }
        v = MediaAuthHttpServer.queryParam(body, "every");
        if (v != null) {
            try {
                int n = Integer.parseInt(v.trim());
                if (n < 5 || n > 100) {
                    throw new Bad(400, "the slider can come every 5 to 100 stories");
                }
                s.every = n;
            } catch (NumberFormatException e) {
                throw new Bad(400, "every must be a number");
            }
        }
        v = MediaAuthHttpServer.queryParam(body, "googleUnit");
        if (v != null) {
            String u = v.trim();
            if (u.isEmpty()) {
                u = AdStore.GOOGLE_TEST_UNIT;
            }
            if (!GOOGLE_UNIT.matcher(u).matches()) {
                throw new Bad(400, "that does not look like an AdMob ad unit ID (ca-app-pub-.../...)");
            }
            s.googleUnit = u;
        }
        ads.setSettings(s);
        ads.flushIfDirty();
    }

    private void create(HttpExchange ex) throws Exception {
        String body = readBody(ex, MAX_UPLOAD);
        String advertiser = ProfileStore.clean(MediaAuthHttpServer.queryParam(body, "advertiser"), 60);
        String headline = ProfileStore.clean(MediaAuthHttpServer.queryParam(body, "headline"), 80);
        String text = ProfileStore.clean(MediaAuthHttpServer.queryParam(body, "text"), 200);
        String link = ProfileStore.clean(MediaAuthHttpServer.queryParam(body, "link"), 500);
        if (advertiser.isEmpty() || headline.isEmpty()) {
            throw new Bad(400, "an ad needs an advertiser and a headline");
        }
        String l = link.toLowerCase(java.util.Locale.ROOT);
        if (!(l.startsWith("https://") || l.startsWith("http://")) || link.contains(" ")) {
            throw new Bad(400, "the link must start with https:// or http://");
        }
        long start = day(MediaAuthHttpServer.queryParam(body, "start"), false);
        long end = day(MediaAuthHttpServer.queryParam(body, "end"), true);
        if (start != 0 && end != 0 && end <= start) {
            throw new Bad(400, "the end date must be after the start date");
        }
        String data = MediaAuthHttpServer.queryParam(body, "image");
        if (data == null || data.isEmpty()) {
            throw new Bad(400, "choose a picture for the ad");
        }
        int comma = data.indexOf(',');
        byte[] img;
        try {
            img = Base64.getDecoder().decode(comma >= 0 ? data.substring(comma + 1) : data);
        } catch (IllegalArgumentException e) {
            throw new Bad(400, "the picture could not be read");
        }
        if (img.length > AdStore.MAX_IMAGE) {
            throw new Bad(400, "the picture is too big (1 MB at most)");
        }
        String ext;
        if (img.length > 8 && (img[0] & 0xFF) == 0x89 && img[1] == 'P' && img[2] == 'N' && img[3] == 'G') {
            ext = "png";
        } else if (img.length > 3 && (img[0] & 0xFF) == 0xFF && (img[1] & 0xFF) == 0xD8 && (img[2] & 0xFF) == 0xFF) {
            ext = "jpg";
        } else {
            throw new Bad(400, "the picture must be a PNG or JPEG");
        }
        ads.create(advertiser, headline, text, link, start, end, img, ext, clock.getAsLong());
        send(ex, 200, listJson());
    }

    /** "2026-10-12" as the start (or, with endOfDay, the end) of that day; empty means no date. */
    private static long day(String s, boolean endOfDay) throws Bad {
        if (s == null || s.trim().isEmpty()) {
            return 0;
        }
        try {
            LocalDate d = LocalDate.parse(s.trim());
            if (endOfDay) {
                d = d.plusDays(1);
            }
            return d.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
        } catch (DateTimeParseException e) {
            throw new Bad(400, "dates must look like 2026-10-31");
        }
    }

    private String listJson() {
        AdStore.Settings s = ads.settings();
        long now = clock.getAsLong();
        StringBuilder sb = new StringBuilder("{\"settings\":{\"sliderOn\":").append(s.sliderOn)
                .append(",\"every\":").append(s.every).append(",\"adCardOn\":").append(s.adCardOn)
                .append(",\"googleOn\":").append(s.googleOn).append(",\"googleUnit\":").append(ProfileApi.quote(s.googleUnit))
                .append(",\"googleTest\":").append(AdStore.GOOGLE_TEST_UNIT.equals(s.googleUnit)).append("},\"ads\":[");
        boolean first = true;
        for (AdStore.Ad a : ads.all()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append("{\"id\":").append(a.id).append(",\"advertiser\":").append(ProfileApi.quote(a.advertiser))
                    .append(",\"headline\":").append(ProfileApi.quote(a.headline))
                    .append(",\"text\":").append(ProfileApi.quote(a.text))
                    .append(",\"link\":").append(ProfileApi.quote(a.link))
                    .append(",\"start\":").append(a.start).append(",\"end\":").append(a.end)
                    .append(",\"active\":").append(a.active).append(",\"running\":").append(a.runningAt(now))
                    .append(",\"views\":").append(a.views).append(",\"clicks\":").append(a.clicks)
                    .append(",\"created\":").append(a.created).append('}');
        }
        return sb.append("]}").toString();
    }

    // ---- Helpers ----

    private static long id(String s) throws Bad {
        try {
            return Long.parseLong(s == null ? "" : s.trim());
        } catch (NumberFormatException e) {
            throw new Bad(400, "bad ad id");
        }
    }

    private static String ip(HttpExchange ex) {
        return ex.getRemoteAddress() == null ? "?" : ex.getRemoteAddress().getAddress().getHostAddress();
    }

    private UserStore.Info signedIn(HttpExchange ex) {
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        String token = auth != null && auth.startsWith("Bearer ") ? auth.substring(7).trim() : null;
        SessionStore.Session s = sessions.get(token);
        UserStore.Info info = s == null ? null : users.info(s.username);
        return (info == null || info.disabled) ? null : info;
    }

    private boolean requireMethod(HttpExchange ex, String actual, String wanted) throws IOException {
        if (actual.equalsIgnoreCase(wanted)) {
            return true;
        }
        ex.getResponseHeaders().set("Allow", wanted);
        send(ex, 405, error("method not allowed"));
        return false;
    }

    private static String readBody(HttpExchange ex, int max) throws IOException, Bad {
        InputStream in = ex.getRequestBody();
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] tmp = new byte[8192];
        int total = 0;
        int n;
        while ((n = in.read(tmp)) != -1) {
            total += n;
            if (total > max) {
                throw new Bad(413, "too much data sent (pictures can be 1 MB at most)");
            }
            buf.write(tmp, 0, n);
        }
        return new String(buf.toByteArray(), StandardCharsets.UTF_8);
    }

    private static String error(String message) {
        return "{\"error\":" + ProfileApi.quote(message) + "}";
    }

    private static void send(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        ex.sendResponseHeaders(status, bytes.length);
        OutputStream os = ex.getResponseBody();
        os.write(bytes);
        os.close();
    }
}