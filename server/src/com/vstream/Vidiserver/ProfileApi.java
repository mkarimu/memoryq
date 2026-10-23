package com.vstream.Vidiserver;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Profiles and the profiler.
 *   GET  /api/profiles/avatar?u=  /picture?u=        drawn pictures (PNG, no sign-in needed)
 *   GET  /api/profiles/me  /view?u=  /people?q=       (signed in)
 *   POST /api/profiles/me                             edit your profile
 *   POST /api/profiles/friends/add  /friends/remove   u=
 *   POST /api/profiles/deletion/request  /deletion/cancel
 *   POST /api/profiles/photo  image=(data URL)   /photo/remove        your own profile photo
 *   /api/admin/profiler/photos | removephoto   (super-admin: recent photos, take one down)
 *   /api/admin/profiler/status | enable | generate | requests | approve | decline   (super-admin only)
 */
public final class ProfileApi {

    /** Deletes an account everywhere (the moderation service does this in the real server). */
    public interface Deleter {
        void delete(String username) throws Exception;
    }

    private static final int MAX_BODY = 8 * 1024;
    private static final long HOUR = 3600000L;

    private final ProfileStore store;
    private final ProfilerService profiler;
    private final UserStore users;
    private final SessionStore sessions;
    private final Limiter limiter;
    private final Deleter deleter;
    private final java.util.function.LongSupplier clock;
    private volatile NotificationStore notes;
    private volatile CallService blocks;

    /** Friend adds are announced as notifications, unless the person has blocked whoever added them. */
    public void setNotifications(NotificationStore n, CallService calls) {
        this.notes = n;
        this.blocks = calls;
    }

    public ProfileApi(ProfileStore store, ProfilerService profiler, UserStore users, SessionStore sessions,
                      Limiter limiter, Deleter deleter, java.util.function.LongSupplier clock) {
        this.store = store;
        this.profiler = profiler;
        this.users = users;
        this.sessions = sessions;
        this.limiter = limiter;
        this.deleter = deleter;
        this.clock = clock;
    }

    public void register(HttpServer server) {
        server.createContext("/api/profiles/", new HttpHandler() {
            @Override
            public void handle(HttpExchange ex) throws IOException {
                handlePeople(ex);
            }
        });
        server.createContext("/api/admin/profiler/", new HttpHandler() {
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

    // ---- People ----

    private void handlePeople(HttpExchange ex) throws IOException {
        try {
            String path = ex.getRequestURI().getPath();
            String method = ex.getRequestMethod();
            String query = ex.getRequestURI().getRawQuery();
            if (path.equals("/api/profiles/avatar") || path.equals("/api/profiles/picture")) {
                if (requireMethod(ex, method, "GET")) {
                    image(ex, path.endsWith("avatar"), MediaAuthHttpServer.queryParam(query, "u"));
                }
                return;
            }
            UserStore.Info me = signedIn(ex);
            if (me == null) {
                send(ex, 401, error("sign in required"));
                return;
            }
            switch (path) {
                case "/api/profiles/me":
                    if (method.equalsIgnoreCase("GET")) {
                        send(ex, 200, profileJson(me.username, me));
                    } else if (method.equalsIgnoreCase("POST")) {
                        edit(ex, me);
                    } else {
                        ex.getResponseHeaders().set("Allow", "GET, POST");
                        send(ex, 405, error("method not allowed"));
                    }
                    break;
                case "/api/profiles/view":
                    if (requireMethod(ex, method, "GET")) {
                        UserStore.Info who = existing(MediaAuthHttpServer.queryParam(query, "u"));
                        send(ex, 200, profileJson(who.username, me));
                    }
                    break;
                case "/api/profiles/people":
                    if (requireMethod(ex, method, "GET")) {
                        people(ex, me, MediaAuthHttpServer.queryParam(query, "q"));
                    }
                    break;
                case "/api/profiles/friends/add":
                case "/api/profiles/friends/remove":
                    if (requireMethod(ex, method, "POST")) {
                        friend(ex, me, path.endsWith("add"));
                    }
                    break;
                case "/api/profiles/deletion/request":
                    if (requireMethod(ex, method, "POST")) {
                        requestDeletion(ex, me);
                    }
                    break;
                case "/api/profiles/photo":
                    if (requireMethod(ex, method, "POST")) {
                        uploadPhoto(ex, me);
                    }
                    break;
                case "/api/profiles/photo/remove":
                    if (requireMethod(ex, method, "POST")) {
                        store.clearPhoto(me.username);
                        store.flushIfDirty();
                        send(ex, 200, profileJson(me.username, me));
                    }
                    break;
                case "/api/profiles/color":
                    if (requireMethod(ex, method, "POST")) {
                        setColor(ex, me);
                    }
                    break;
                case "/api/profiles/deletion/cancel":
                    if (requireMethod(ex, method, "POST")) {
                        store.cancelDeletion(me.username);
                        store.flushIfDirty();
                        send(ex, 200, profileJson(me.username, me));
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

    /** An existing, enabled, non-admin account, or a 404. */
    private UserStore.Info existing(String name) throws Bad {
        UserStore.Info i = name == null ? null : users.info(name.trim());
        if (i == null || i.disabled || i.role == UserStore.Role.ADMIN) {
            throw new Bad(404, "no such person");
        }
        return i;
    }

    private void image(HttpExchange ex, boolean avatar, String name) throws IOException {
        UserStore.Info i = name == null ? null : users.info(name.trim());
        if (i == null) {
            send(ex, 404, error("no such person"));
            return;
        }
        ProfileStore.Profile p = store.get(i.username);
        byte[] own = p == null || p.photo.isEmpty() ? null : store.photo(i.username);
        if (own != null) {
            ex.getResponseHeaders().set("Content-Type", "image/jpeg");
            ex.getResponseHeaders().set("Cache-Control", "max-age=300");
            ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
            ex.sendResponseHeaders(200, own.length);
            OutputStream os = ex.getResponseBody();
            os.write(own);
            os.close();
            return;
        }
        String first = p == null ? "" : p.firstName;
        String last = p == null ? "" : p.lastName;
        String country = p == null ? "" : p.country;
        long seed = p == null ? i.username.toLowerCase(Locale.ROOT).hashCode() * 2654435761L : p.seed;
        byte[] png = avatar ? ProfileArt.avatar(i.username, first, last, seed)
                : ProfileArt.picture(i.username, first, last, country, seed);
        ex.getResponseHeaders().set("Content-Type", "image/png");
        ex.getResponseHeaders().set("Cache-Control", "max-age=300");
        ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        ex.sendResponseHeaders(200, png.length);
        OutputStream os = ex.getResponseBody();
        os.write(png);
        os.close();
    }

    private void edit(HttpExchange ex, UserStore.Info me) throws Exception {
        String body = readBody(ex);
        String k = "pe|" + me.username.toLowerCase(Locale.ROOT);
        if (limiter.count(k, HOUR) >= 30) {
            throw new Bad(429, "you changed your profile many times - please wait a while");
        }
        limiter.record(k);
        String hobbies = MediaAuthHttpServer.queryParam(body, "hobbies");
        store.edit(me.username, MediaAuthHttpServer.queryParam(body, "firstName"),
                MediaAuthHttpServer.queryParam(body, "lastName"), MediaAuthHttpServer.queryParam(body, "stageName"),
                MediaAuthHttpServer.queryParam(body, "city"), MediaAuthHttpServer.queryParam(body, "country"),
                MediaAuthHttpServer.queryParam(body, "education"),
                hobbies == null ? null : Arrays.asList(hobbies.split(",")), clock.getAsLong());
        store.flushIfDirty();
        send(ex, 200, profileJson(me.username, me));
    }

    /** Card colour: color=#rrggbb, or color= (empty) / auto for the automatic colour. */
    private void setColor(HttpExchange ex, UserStore.Info me) throws Exception {
        String c = MediaAuthHttpServer.queryParam(readBody(ex), "color");
        c = c == null ? "" : c.trim();
        String k = "pc|" + me.username.toLowerCase(Locale.ROOT);
        if (limiter.count(k, HOUR) >= 60) {
            throw new Bad(429, "you changed your card colour many times - please wait a while");
        }
        String ok = "";
        if (!c.isEmpty() && !"auto".equalsIgnoreCase(c)) {
            ok = ProfileStore.checkColor(c);
            if (ok == null) {
                throw new Bad(400, "that colour is too light for white writing - please pick a darker one");
            }
        }
        limiter.record(k);
        store.setColor(me.username, ok, clock.getAsLong());
        store.flushIfDirty();
        send(ex, 200, profileJson(me.username, me));
    }

    private void friend(HttpExchange ex, UserStore.Info me, boolean add) throws Exception {
        String name = MediaAuthHttpServer.queryParam(readBody(ex), "u");
        if (add) {
            UserStore.Info who = existing(name);
            if (who.username.equalsIgnoreCase(me.username)) {
                throw new Bad(400, "you cannot add yourself as a friend");
            }
            String k = "pf|" + me.username.toLowerCase(Locale.ROOT);
            if (limiter.count(k, HOUR) >= 60) {
                throw new Bad(429, "you added many friends - please wait a while");
            }
            boolean added;
            try {
                added = store.addFriend(me.username, who.username, clock.getAsLong());
            } catch (IllegalStateException e) {
                throw new Bad(400, e.getMessage());
            }
            NotificationStore n = notes;
            CallService b = blocks;
            if (added && n != null && (b == null || !b.hasBlocked(who.username, me.username))) {
                n.add(who.username, NotificationStore.FRIEND, me.username, 0, 0, "", clock.getAsLong());
                n.flushIfDirty();
            }
            limiter.record(k);
            store.flushIfDirty();
            send(ex, 200, profileJson(who.username, me));
        } else {
            if (name == null || !store.removeFriend(me.username, name.trim())) {
                throw new Bad(404, "that person is not on your friends list");
            }
            store.flushIfDirty();
            send(ex, 200, "{\"ok\":true}");
        }
    }

    private void requestDeletion(HttpExchange ex, UserStore.Info me) throws Exception {
        if (me.role == UserStore.Role.BROADCASTER) {
            throw new Bad(400, "broadcaster accounts cannot be deleted from the app - contact the admin");
        }
        String reason = MediaAuthHttpServer.queryParam(readBody(ex), "reason");
        String k = "pd|" + me.username.toLowerCase(Locale.ROOT);
        if (limiter.count(k, HOUR * 24) >= 5) {
            throw new Bad(429, "you sent several requests today - the admin will look at them");
        }
        limiter.record(k);
        store.requestDeletion(me.username, reason, clock.getAsLong());
        store.flushIfDirty();
        send(ex, 200, profileJson(me.username, me));
    }

    private void people(HttpExchange ex, UserStore.Info me, String q) throws IOException {
        String find = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder("{\"people\":[");
        boolean first = true;
        int n = 0;
        for (UserStore.Info i : users.listInfos()) {
            if (n >= 200) {
                break;
            }
            if (i.disabled || i.role == UserStore.Role.ADMIN || i.username.equalsIgnoreCase(me.username)) {
                continue;
            }
            ProfileStore.Profile p = store.get(i.username);
            String text = (i.username + " " + (p == null ? "" : p.firstName + " " + p.lastName + " " + p.stageName
                    + " " + p.city + " " + p.country)).toLowerCase(Locale.ROOT);
            if (!find.isEmpty() && !text.contains(find)) {
                continue;
            }
            if (!first) {
                sb.append(',');
            }
            first = false;
            n++;
            sb.append(shortJson(i.username, p));
        }
        send(ex, 200, sb.append("]}").toString());
    }

    // ---- JSON ----

    private static String shortJson(String username, ProfileStore.Profile p) {
        return "{\"username\":" + quote(username)
                + ",\"stageName\":" + quote(p == null ? "" : p.stageName)
                + ",\"firstName\":" + quote(p == null ? "" : p.firstName)
                + ",\"lastName\":" + quote(p == null ? "" : p.lastName)
                + ",\"country\":" + quote(p == null ? "" : p.country)
                + ",\"test\":" + (p != null && p.test)
                + ",\"color\":" + quote(p != null && !p.color.isEmpty() ? p.color : ProfileStore.autoColor(username))
                + ",\"colorSet\":" + (p != null && !p.color.isEmpty()) + "}";
    }

    private static String list(List<String> l) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < l.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(quote(l.get(i)));
        }
        return sb.append(']').toString();
    }

    private String profileJson(String username, UserStore.Info viewer) {
        ProfileStore.Profile p = store.get(username);
        boolean mine = viewer.username.equalsIgnoreCase(username);
        StringBuilder sb = new StringBuilder("{\"username\":").append(quote(username));
        sb.append(",\"me\":").append(mine);
        sb.append(",\"firstName\":").append(quote(p == null ? "" : p.firstName));
        sb.append(",\"lastName\":").append(quote(p == null ? "" : p.lastName));
        sb.append(",\"stageName\":").append(quote(p == null ? "" : p.stageName));
        sb.append(",\"city\":").append(quote(p == null ? "" : p.city));
        sb.append(",\"country\":").append(quote(p == null ? "" : p.country));
        sb.append(",\"education\":").append(quote(p == null ? "" : p.education));
        sb.append(",\"hobbies\":").append(list(p == null ? new ArrayList<String>() : p.hobbies));
        sb.append(",\"test\":").append(p != null && p.test);
        sb.append(",\"hasPhoto\":").append(p != null && !p.photo.isEmpty());
        sb.append(",\"color\":").append(quote(p != null && !p.color.isEmpty() ? p.color : ProfileStore.autoColor(username)));
        sb.append(",\"colorSet\":").append(p != null && !p.color.isEmpty());
        sb.append(",\"since\":").append(p == null ? 0 : p.created);
        sb.append(",\"friends\":[");
        boolean first = true;
        if (p != null) {
            for (String f : p.friends) {
                UserStore.Info fi = users.info(f);
                if (fi == null || fi.disabled) {
                    continue;
                }
                if (!first) {
                    sb.append(',');
                }
                first = false;
                sb.append(shortJson(fi.username, store.get(fi.username)));
            }
        }
        sb.append(']');
        if (!mine) {
            ProfileStore.Profile mineP = store.get(viewer.username);
            boolean isFriend = false;
            if (mineP != null) {
                for (String f : mineP.friends) {
                    isFriend |= f.equalsIgnoreCase(username);
                }
            }
            sb.append(",\"isFriend\":").append(isFriend);
        } else {
            sb.append(",\"deletion\":{\"status\":").append(quote(p == null ? "" : p.deletion))
                    .append(",\"at\":").append(p == null ? 0 : p.deletionAt)
                    .append(",\"reason\":").append(quote(p == null ? "" : p.deletionReason))
                    .append(",\"note\":").append(quote(p == null ? "" : p.deletionNote)).append('}');
        }
        return sb.append('}').toString();
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
                case "/api/admin/profiler/status":
                    if (requireMethod(ex, method, "GET")) {
                        status(ex);
                    }
                    break;
                case "/api/admin/profiler/enable":
                    if (requireMethod(ex, method, "POST")) {
                        store.setProfilerOn("true".equals(MediaAuthHttpServer.queryParam(readBody(ex), "enabled")));
                        store.flushIfDirty();
                        status(ex);
                    }
                    break;
                case "/api/admin/profiler/testpeople":
                    if (requireMethod(ex, method, "POST")) {
                        boolean on = "true".equals(MediaAuthHttpServer.queryParam(readBody(ex), "on"));
                        profiler.setTestPeopleOn(on, sessions::removeUser);
                        status(ex);
                    }
                    break;
                case "/api/admin/profiler/generate":
                    if (requireMethod(ex, method, "POST")) {
                        if (store.testSuspended()) {
                            throw new Bad(409, "test people are switched OFF - switch them ON first");
                        }
                        int made = profiler.createBatch(1);
                        if (made == 0) {
                            throw new Bad(409, "no one was added - the limit of " + profiler.max() + " test people is reached");
                        }
                        status(ex);
                    }
                    break;
                case "/api/admin/profiler/photos":
                    if (requireMethod(ex, method, "GET")) {
                        StringBuilder sb = new StringBuilder("{\"photos\":[");
                        boolean first = true;
                        int n = 0;
                        for (ProfileStore.Profile p : store.withPhotos()) {
                            if (n++ >= 60) {
                                break;
                            }
                            if (!first) {
                                sb.append(',');
                            }
                            first = false;
                            sb.append("{\"username\":").append(quote(p.username)).append(",\"stageName\":")
                                    .append(quote(p.stageName)).append(",\"at\":").append(p.photoAt)
                                    .append(",\"test\":").append(p.test).append('}');
                        }
                        send(ex, 200, sb.append("]}").toString());
                    }
                    break;
                case "/api/admin/profiler/removephoto":
                    if (requireMethod(ex, method, "POST")) {
                        String u = MediaAuthHttpServer.queryParam(readBody(ex), "u");
                        if (u == null || !store.clearPhoto(u.trim())) {
                            throw new Bad(404, "that person has no photo");
                        }
                        store.flushIfDirty();
                        send(ex, 200, "{\"ok\":true}");
                    }
                    break;
                case "/api/admin/profiler/requests":
                    if (requireMethod(ex, method, "GET")) {
                        StringBuilder sb = new StringBuilder("{\"requests\":[");
                        boolean first = true;
                        for (ProfileStore.Profile p : store.deletionRequests()) {
                            if (!first) {
                                sb.append(',');
                            }
                            first = false;
                            sb.append("{\"username\":").append(quote(p.username)).append(",\"stageName\":")
                                    .append(quote(p.stageName)).append(",\"at\":").append(p.deletionAt)
                                    .append(",\"reason\":").append(quote(p.deletionReason))
                                    .append(",\"test\":").append(p.test).append('}');
                        }
                        send(ex, 200, sb.append("]}").toString());
                    }
                    break;
                case "/api/admin/profiler/approve":
                    if (requireMethod(ex, method, "POST")) {
                        approve(ex, MediaAuthHttpServer.queryParam(readBody(ex), "u"));
                    }
                    break;
                case "/api/admin/profiler/decline":
                    if (requireMethod(ex, method, "POST")) {
                        String body = readBody(ex);
                        String u = MediaAuthHttpServer.queryParam(body, "u");
                        if (u == null || !store.declineDeletion(u, MediaAuthHttpServer.queryParam(body, "note"))) {
                            throw new Bad(404, "no deletion request from that person");
                        }
                        store.flushIfDirty();
                        send(ex, 200, "{\"ok\":true}");
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

    private void approve(HttpExchange ex, String u) throws Exception {
        ProfileStore.Profile p = u == null ? null : store.get(u);
        if (p == null || !"pending".equals(p.deletion)) {
            throw new Bad(404, "no deletion request from that person");
        }
        try {
            deleter.delete(p.username);
        } catch (Exception e) {
            String m = e.getMessage();
            throw new Bad(400, m == null ? "could not delete the account" : m);
        }
        store.remove(p.username);
        store.flushIfDirty();
        send(ex, 200, "{\"ok\":true}");
    }

    private void status(HttpExchange ex) throws IOException {
        long next = profiler.msUntilNext();
        StringBuilder sb = new StringBuilder("{\"enabled\":").append(store.profilerOn());
        sb.append(",\"testPeopleOn\":").append(!store.testSuspended());
        sb.append(",\"count\":").append(store.testCount()).append(",\"max\":").append(profiler.max());
        sb.append(",\"everyMinutes\":").append(profiler.everyMs() / 60000);
        sb.append(",\"nextInMinutes\":").append(next < 0 ? -1 : (next + 59999) / 60000);
        sb.append(",\"pendingDeletions\":").append(store.deletionRequests().size());
        sb.append(",\"people\":[");
        boolean first = true;
        for (ProfileStore.Profile p : store.testPeople()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append("{\"username\":").append(quote(p.username)).append(",\"password\":").append(quote(p.testPassword))
                    .append(",\"name\":").append(quote(p.firstName + " " + p.lastName))
                    .append(",\"stageName\":").append(quote(p.stageName))
                    .append(",\"place\":").append(quote(p.city + ", " + p.country))
                    .append(",\"friends\":").append(p.friends.size())
                    .append(",\"created\":").append(p.created).append('}');
        }
        send(ex, 200, sb.append("]}").toString());
    }

    // ---- Helpers ----

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

    /** Checks an uploaded picture, crops it to a square, shrinks it to 640 x 640 and saves it as a JPEG. */
    private void uploadPhoto(HttpExchange ex, UserStore.Info me) throws Exception {
        if (me.role == UserStore.Role.ADMIN) {
            throw new Bad(403, "the super-admin account has no profile");
        }
        String k = "pp|" + me.username.toLowerCase(Locale.ROOT);
        if (limiter.count(k, HOUR) >= 10) {
            throw new Bad(429, "you changed your photo many times - please wait a while");
        }
        String data = MediaAuthHttpServer.queryParam(readBody(ex, 4 * 1024 * 1024), "image");
        if (data == null || data.isEmpty()) {
            throw new Bad(400, "choose a photo");
        }
        int comma = data.indexOf(',');
        byte[] raw;
        try {
            raw = java.util.Base64.getDecoder().decode(comma >= 0 ? data.substring(comma + 1) : data);
        } catch (IllegalArgumentException e) {
            throw new Bad(400, "the photo could not be read");
        }
        if (raw.length > 3 * 1024 * 1024) {
            throw new Bad(400, "the photo is too big (3 MB at most)");
        }
        java.awt.image.BufferedImage src;
        try {
            src = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(raw));
        } catch (Exception e) {
            src = null;
        }
        if (src == null || src.getWidth() < 32 || src.getHeight() < 32) {
            throw new Bad(400, "that is not a photo the server can read (use a JPEG or PNG)");
        }
        int side = Math.min(src.getWidth(), src.getHeight());
        int x = (src.getWidth() - side) / 2;
        int y = (src.getHeight() - side) / 2;
        int out = Math.min(640, side);
        java.awt.image.BufferedImage sq = new java.awt.image.BufferedImage(out, out, java.awt.image.BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = sq.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(java.awt.RenderingHints.KEY_RENDERING, java.awt.RenderingHints.VALUE_RENDER_QUALITY);
        g.setColor(java.awt.Color.WHITE);
        g.fillRect(0, 0, out, out);
        g.drawImage(src, 0, 0, out, out, x, y, x + side, y + side, null);
        g.dispose();
        java.io.ByteArrayOutputStream jpeg = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageWriter w = javax.imageio.ImageIO.getImageWritersByFormatName("jpeg").next();
        javax.imageio.ImageWriteParam param = w.getDefaultWriteParam();
        param.setCompressionMode(javax.imageio.ImageWriteParam.MODE_EXPLICIT);
        param.setCompressionQuality(0.88f);
        try (javax.imageio.stream.ImageOutputStream ios = javax.imageio.ImageIO.createImageOutputStream(jpeg)) {
            w.setOutput(ios);
            w.write(null, new javax.imageio.IIOImage(sq, null, null), param);
        } finally {
            w.dispose();
        }
        limiter.record(k);
        store.setPhoto(me.username, jpeg.toByteArray(), clock.getAsLong());
        store.flushIfDirty();
        send(ex, 200, profileJson(me.username, me));
    }

    private static String readBody(HttpExchange ex) throws IOException {
        return readBody(ex, MAX_BODY);
    }

    private static String readBody(HttpExchange ex, int max) throws IOException {
        InputStream in = ex.getRequestBody();
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] tmp = new byte[1024];
        int total = 0;
        int n;
        while ((n = in.read(tmp)) != -1) {
            total += n;
            if (total > max) {
                throw new IOException("request body too large");
            }
            buf.write(tmp, 0, n);
        }
        return new String(buf.toByteArray(), StandardCharsets.UTF_8);
    }

    private static String error(String message) {
        return "{\"error\":" + quote(message) + "}";
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

    static String quote(String s) {
        if (s == null) {
            s = "";
        }
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n");  break;
                case '\r': sb.append("\\r");  break;
                case '\t': sb.append("\\t");  break;
                case '<':  sb.append("\\u003c"); break;
                case '>':  sb.append("\\u003e"); break;
                default:
                    if (ch < 0x20) {
                        sb.append(String.format("\\u%04x", (int) ch));
                    } else {
                        sb.append(ch);
                    }
            }
        }
        return sb.append('"').toString();
    }
}