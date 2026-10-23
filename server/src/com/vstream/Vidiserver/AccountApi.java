package com.vstream.Vidiserver;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * Sign-in, registration and admin endpoints, plus the admin web page.
 *   POST /api/login /api/register /api/logout /api/password, GET /api/me
 *   /api/admin/...  (admin only)    GET /admin  (the admin page)
 * Sessions travel as "Authorization: Bearer <token>". Requests and responses never contain
 * password hashes. Without TLS, passwords are readable on the network: use test passwords only.
 */
public final class AccountApi {

    private static final int MAX_BODY = 8 * 1024;
    private static final Pattern CHANNEL_ID = Pattern.compile("CHANNEL-\\d{1,9}");

    private final UserStore users;
    private final SessionStore sessions;
    private final Limiter limiter;
    private final LiveChannelRegistry registry;
    private final Path adminPage;
    private final Consumer<String> kick;
    private final Predicate<String> liveCheck;
    private volatile ClaimStore claims = null;

    public AccountApi(UserStore users, SessionStore sessions, Limiter limiter,
                      LiveChannelRegistry registry, Path adminPage,
                      Consumer<String> kick, Predicate<String> liveCheck) {
        this.users = users;
        this.sessions = sessions;
        this.limiter = limiter;
        this.registry = registry;
        this.adminPage = adminPage;
        this.kick = kick;
        this.liveCheck = liveCheck;
    }

    /** Enables the channel pool endpoints. */
    private volatile ModerationService moderation;

    public void setModeration(ModerationService m) {
        this.moderation = m;
    }

    public void setClaims(ClaimStore store) {
        this.claims = store;
    }

    /** Email addresses and codes. Without it (null), accounts are created without an email, as before. */
    private volatile EmailService email;
    private volatile boolean requireEmail = true;

    public void setEmail(EmailService service, boolean requireForNewAccounts) {
        this.email = service;
        this.requireEmail = requireForNewAccounts;
    }

    /** "Also stream to" for the Vstream app (registered next to the account API). */
    private volatile Restreamer restreamer;

    private volatile TvApi tv;

    public void setTv(TvApi t) {
        this.tv = t;
    }

    private volatile VideoApi videos;
    private volatile AdminUserView userView;

    public void setVideos(VideoApi v) {
        this.videos = v;
    }

    public void setUserView(AdminUserView v) {
        this.userView = v;
    }

    private final java.util.List<java.util.function.Consumer<HttpServer>> modules = new java.util.concurrent.CopyOnWriteArrayList<java.util.function.Consumer<HttpServer>>();

    /** Something else that adds its own pages or API to the server (Alma, the SuperAgent, downloads...). */
    public void addModule(java.util.function.Consumer<HttpServer> m) {
        modules.add(m);
    }

    public void setRestreamer(Restreamer r) {
        this.restreamer = r;
    }

    public void register(HttpServer server) {
        Restreamer rs = restreamer;
        if (rs != null) {
            rs.register(server);
        }
        TvApi t = tv;
        if (t != null) {
            t.register(server);
        }
        VideoApi va = videos;
        if (va != null) {
            va.register(server);
        }
        AdminUserView uv = userView;
        if (uv != null) {
            uv.register(server);
        }
        for (java.util.function.Consumer<HttpServer> m : modules) {
            m.accept(server);
        }
        server.createContext("/api/", new HttpHandler() {
            @Override
            public void handle(HttpExchange ex) throws IOException {
                handleApi(ex);
            }
        });
        server.createContext("/admin", new HttpHandler() {
            @Override
            public void handle(HttpExchange ex) throws IOException {
                handleAdminPage(ex);
            }
        });
        server.createContext("/me", new HttpHandler() {
            @Override
            public void handle(HttpExchange ex) throws IOException {
                handleUserPage(ex);
            }
        });
        server.createContext("/terms", new HttpHandler() {
            @Override
            public void handle(HttpExchange ex) throws IOException {
                servePage(ex, "/terms", sibling("terms.html"));
            }
        });
        server.createContext("/studio", new HttpHandler() {
            @Override
            public void handle(HttpExchange ex) throws IOException {
                servePage(ex, "/studio", sibling("studio.html"));
            }
        });
        server.createContext("/djpro", new HttpHandler() {
            @Override
            public void handle(HttpExchange ex) throws IOException {
                servePage(ex, "/djpro", sibling("djpro.html"));
            }
        });
        server.createContext("/tv", new HttpHandler() {
            @Override
            public void handle(HttpExchange ex) throws IOException {
                if (isVideoHost(ex)) {
                    serveTv(ex, "/tv");
                } else if (videoHostSeen) {
                    // the TV wall lives at the video site now (mkarimu.net)
                    try {
                        ex.getResponseHeaders().set("Location", "https://" + VIDEO_HOST + "/");
                        ex.sendResponseHeaders(302, -1);
                    } finally {
                        ex.close();
                    }
                } else {
                    servePage(ex, "/tv", sibling("tv.html"));
                }
            }
        });
        // the members' marketplace (buy and sell)
        server.createContext("/market", new HttpHandler() {
            @Override
            public void handle(HttpExchange ex) throws IOException {
                servePage(ex, "/market", sibling("market.html"));
            }
        });
        server.createContext("/vstream-legal", new HttpHandler() {
            @Override
            public void handle(HttpExchange ex) throws IOException {
                servePage(ex, "/vstream-legal", sibling("vstream-legal.html"));
            }
        });
        // AdMob checks this file on the app's website (the Play Store listing's website: memoryq.com)
        server.createContext("/app-ads.txt", new HttpHandler() {
            @Override
            public void handle(HttpExchange ex) throws IOException {
                try {
                    byte[] b = (System.getProperty("vstream.appAds", "google.com, pub-2298775225198657, DIRECT, f08c47fec0942fa0")
                            + "\n").getBytes(StandardCharsets.UTF_8);
                    ex.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
                    ex.sendResponseHeaders(200, b.length);
                    ex.getResponseBody().write(b);
                } finally {
                    ex.close();
                }
            }
        });
        server.createContext("/privacy", new HttpHandler() {
            @Override
            public void handle(HttpExchange ex) throws IOException {
                servePage(ex, "/privacy", sibling("privacy.html"));
            }
        });
    }

    // ---- Routing ----

    private void handleApi(HttpExchange ex) throws IOException {
        try {
            String path = ex.getRequestURI().getPath();
            String method = ex.getRequestMethod();
            if (path.startsWith("/api/admin/")) {
                admin(ex, path, method);
                return;
            }
            switch (path) {
                case "/api/login":
                    if (requireMethod(ex, method, "POST")) {
                        login(ex);
                    }
                    break;
                case "/api/register":
                    if (requireMethod(ex, method, "POST")) {
                        registerChatter(ex);
                    }
                    break;
                case "/api/register/verify":
                    if (requireMethod(ex, method, "POST")) {
                        finishRegister(ex);
                    }
                    break;
                case "/api/register/resend":
                    if (requireMethod(ex, method, "POST")) {
                        resendRegister(ex);
                    }
                    break;
                case "/api/email":
                    if (requireMethod(ex, method, "GET")) {
                        emailStatus(ex);
                    }
                    break;
                case "/api/email/start":
                    if (requireMethod(ex, method, "POST")) {
                        emailStart(ex);
                    }
                    break;
                case "/api/email/confirm":
                    if (requireMethod(ex, method, "POST")) {
                        emailConfirm(ex);
                    }
                    break;
                case "/api/logout":
                    if (requireMethod(ex, method, "POST")) {
                        sessions.remove(bearerToken(ex));
                        send(ex, 200, "{\"ok\":true}");
                    }
                    break;
                case "/api/password":
                    if (requireMethod(ex, method, "POST")) {
                        changePassword(ex);
                    }
                    break;
                case "/api/pool":
                    if (requireMethod(ex, method, "GET")) {
                        poolList(ex);
                    }
                    break;
                case "/api/claim":
                    if (requireMethod(ex, method, "POST")) {
                        claimChannel(ex);
                    }
                    break;
                case "/api/release":
                    if (requireMethod(ex, method, "POST")) {
                        releaseChannel(ex);
                    }
                    break;
                case "/api/ack":
                    if (requireMethod(ex, method, "POST")) {
                        ackNotice(ex);
                    }
                    break;
                case "/api/me":
                    if (requireMethod(ex, method, "GET")) {
                        me(ex);
                    }
                    break;
                default:
                    send(ex, 404, error("not found"));
            }
        } catch (UserStore.UserException e) {
            send(ex, 400, error(e.getMessage()));
        } catch (Exception e) {
            send(ex, 500, error("server error"));
        } finally {
            ex.close();
        }
    }

    private void login(HttpExchange ex) throws Exception {
        String body = readBody(ex);
        String username = MediaAuthHttpServer.queryParam(body, "username");
        String password = MediaAuthHttpServer.queryParam(body, "password");
        if (username == null || password == null) {
            send(ex, 400, error("username and password are required"));
            return;
        }
        String ip = clientIp(ex);
        String userKey = ip + "|" + username.toLowerCase(Locale.ROOT);
        if (limiter.count(userKey, 60000) >= 5 || limiter.count(ip, 60000) >= 20) {
            send(ex, 429, error("too many attempts, try again in a minute"));
            return;
        }
        ModerationService mod = moderation;
        String suspended = mod == null ? null : mod.suspensionMessage(username);
        if (suspended != null) {
            limiter.record(userKey);
            limiter.record(ip);
            send(ex, 403, error(suspended));
            return;
        }
        UserStore.Info info = users.authenticate(username, password);
        if (info == null) {
            limiter.record(userKey);
            limiter.record(ip);
            send(ex, 401, error("wrong username or password"));
            return;
        }
        limiter.clear(userKey);
        send(ex, 200, loginJson(sessions.create(info.username, info.role), info));
    }

    private void registerChatter(HttpExchange ex) throws Exception {
        String body = readBody(ex);
        String username = MediaAuthHttpServer.queryParam(body, "username");
        String password = MediaAuthHttpServer.queryParam(body, "password");
        String regKey = "reg|" + clientIp(ex);
        if (limiter.count(regKey, 3600000) >= 10) {
            send(ex, 429, error("too many sign-ups from this address, try again later"));
            return;
        }
        String type = MediaAuthHttpServer.queryParam(body, "type");
        UserStore.Role newRole = "guest".equalsIgnoreCase(type) ? UserStore.Role.GUEST : UserStore.Role.CHATTER;
        EmailService es = email;
        if (newRole == UserStore.Role.CHATTER && es != null) {
            String address = MediaAuthHttpServer.queryParam(body, "email");
            if (address == null || address.trim().isEmpty()) {
                if (requireEmail) {
                    send(ex, 400, error("an email address is needed to create an account - please update the app"));
                    return;
                }
            } else {
                // The account is created only after the code from the email comes back (/api/register/verify).
                limiter.record(regKey);
                try {
                    String masked = es.startSignup(username, password, address);
                    send(ex, 200, "{\"verify\":true,\"username\":" + quote(username.trim()) + ",\"email\":" + quote(masked)
                            + ",\"minutes\":" + (EmailService.CODE_MS / 60000) + "}");
                } catch (EmailService.EmailException e) {
                    send(ex, e.status, error(e.getMessage()));
                }
                return;
            }
        }
        UserStore.Info info = users.createUser(username, password, newRole);
        limiter.record(regKey);
        send(ex, 200, loginJson(sessions.create(info.username, info.role), info));
    }

    private void finishRegister(HttpExchange ex) throws Exception {
        EmailService es = email;
        if (es == null) {
            send(ex, 404, error("not found"));
            return;
        }
        String body = readBody(ex);
        String username = MediaAuthHttpServer.queryParam(body, "username");
        String ip = clientIp(ex);
        if (limiter.count("code|" + ip, 600000) >= 30) {
            send(ex, 429, error("too many attempts, try again in a few minutes"));
            return;
        }
        limiter.record("code|" + ip);
        try {
            UserStore.Info info = es.finishSignup(username, MediaAuthHttpServer.queryParam(body, "code"));
            System.out.println("[accounts] new account " + info.username + " (email confirmed)");
            send(ex, 200, loginJson(sessions.create(info.username, info.role), info));
        } catch (EmailService.EmailException e) {
            send(ex, e.status, error(e.getMessage()));
        }
    }

    private void resendRegister(HttpExchange ex) throws Exception {
        EmailService es = email;
        if (es == null) {
            send(ex, 404, error("not found"));
            return;
        }
        String body = readBody(ex);
        try {
            String masked = es.resendSignup(MediaAuthHttpServer.queryParam(body, "username"));
            send(ex, 200, "{\"ok\":true,\"email\":" + quote(masked) + "}");
        } catch (EmailService.EmailException e) {
            send(ex, e.status, error(e.getMessage()));
        }
    }

    /** The signed-in account (not admin or guest) for the email endpoints, or null after sending an error. */
    private UserStore.Info emailUser(HttpExchange ex) throws Exception {
        if (email == null) {
            send(ex, 404, error("not found"));
            return null;
        }
        SessionStore.Session s = session(ex);
        UserStore.Info info = s == null ? null : users.info(s.username);
        if (info == null || info.disabled) {
            send(ex, 401, error("sign in required"));
            return null;
        }
        return info;
    }

    private String emailJson(UserStore.Info info) {
        EmailService es = email;
        EmailService.Entry e = es.get(info.username);
        boolean needed = info.role == UserStore.Role.CHATTER || info.role == UserStore.Role.BROADCASTER;
        return "{\"email\":" + quote(e == null ? "" : e.email) + ",\"verified\":" + (e != null && e.verified)
                + ",\"pending\":" + quote(es.pendingChange(info.username)) + ",\"needed\":" + needed + "}";
    }

    private void emailStatus(HttpExchange ex) throws Exception {
        UserStore.Info info = emailUser(ex);
        if (info != null) {
            send(ex, 200, emailJson(info));
        }
    }

    private void emailStart(HttpExchange ex) throws Exception {
        UserStore.Info info = emailUser(ex);
        if (info == null) {
            return;
        }
        if (info.role == UserStore.Role.GUEST) {
            send(ex, 400, error("guest accounts do not have an email address - create a registered account"));
            return;
        }
        String key = "mail|" + info.username.toLowerCase(Locale.ROOT);
        if (limiter.count(key, 3600000) >= 8) {
            send(ex, 429, error("too many codes - try again in an hour"));
            return;
        }
        limiter.record(key);
        try {
            email.startChange(info.username, MediaAuthHttpServer.queryParam(readBody(ex), "email"));
            send(ex, 200, emailJson(info));
        } catch (EmailService.EmailException e) {
            send(ex, e.status, error(e.getMessage()));
        }
    }

    private void emailConfirm(HttpExchange ex) throws Exception {
        UserStore.Info info = emailUser(ex);
        if (info == null) {
            return;
        }
        try {
            email.confirmChange(info.username, MediaAuthHttpServer.queryParam(readBody(ex), "code"));
            send(ex, 200, emailJson(info));
        } catch (EmailService.EmailException e) {
            send(ex, e.status, error(e.getMessage()));
        }
    }

    private void me(HttpExchange ex) throws Exception {
        SessionStore.Session s = session(ex);
        UserStore.Info info = s == null ? null : users.info(s.username);
        if (info == null || info.disabled) {
            send(ex, 401, error("sign in required"));
            return;
        }
        send(ex, 200, loginJson(null, info));
    }

    private void changePassword(HttpExchange ex) throws Exception {
        SessionStore.Session s = session(ex);
        if (s == null) {
            send(ex, 401, error("sign in required"));
            return;
        }
        String body = readBody(ex);
        String oldPassword = MediaAuthHttpServer.queryParam(body, "old");
        String newPassword = MediaAuthHttpServer.queryParam(body, "new");
        if (oldPassword == null || newPassword == null) {
            send(ex, 400, error("old and new passwords are required"));
            return;
        }
        if (users.authenticate(s.username, oldPassword) == null) {
            send(ex, 401, error("the old password is wrong"));
            return;
        }
        users.setPassword(s.username, newPassword);
        send(ex, 200, "{\"ok\":true}");
    }

    // ---- Admin ----

    private void admin(HttpExchange ex, String path, String method) throws Exception {
        SessionStore.Session s = session(ex);
        if (s == null) {
            send(ex, 401, error("sign in required"));
            return;
        }
        UserStore.Info me = users.info(s.username);
        if (me == null || me.disabled || me.role != UserStore.Role.ADMIN) {
            send(ex, 403, error("admin only"));
            return;
        }
        switch (path) {
            case "/api/admin/users":
                if (requireMethod(ex, method, "GET")) {
                    send(ex, 200, usersJson());
                }
                break;
            case "/api/admin/users/create": {
                if (!requireMethod(ex, method, "POST")) {
                    break;
                }
                String body = readBody(ex);
                String roleText = MediaAuthHttpServer.queryParam(body, "role");
                if ("GUEST".equals(roleText)) {
                    UserStore.Info guest = users.createUser(
                            MediaAuthHttpServer.queryParam(body, "username"),
                            MediaAuthHttpServer.queryParam(body, "password"), UserStore.Role.GUEST);
                    send(ex, 200, loginJson(null, guest));
                    break;
                }
                UserStore.Role role = "BROADCASTER".equals(roleText)
                        ? UserStore.Role.BROADCASTER : UserStore.Role.CHATTER;
                UserStore.Info created = users.createUser(
                        MediaAuthHttpServer.queryParam(body, "username"),
                        MediaAuthHttpServer.queryParam(body, "password"), role);
                send(ex, 200, loginJson(null, created));
                break;
            }
            case "/api/admin/users/password": {
                if (!requireMethod(ex, method, "POST")) {
                    break;
                }
                String body = readBody(ex);
                String name = MediaAuthHttpServer.queryParam(body, "username");
                users.setPassword(name, MediaAuthHttpServer.queryParam(body, "password"));
                sessions.removeUser(name);
                send(ex, 200, "{\"ok\":true}");
                break;
            }
            case "/api/admin/users/disable": {
                if (!requireMethod(ex, method, "POST")) {
                    break;
                }
                String body = readBody(ex);
                String name = MediaAuthHttpServer.queryParam(body, "username");
                boolean disable = !"false".equals(MediaAuthHttpServer.queryParam(body, "disabled"));
                users.setDisabled(name, disable);
                if (disable && claims != null) {
                    claims.releaseByUser(name, "Your account was disabled by the admin.");
                }
                if (disable) {
                    sessions.removeUser(name);
                }
                send(ex, 200, "{\"ok\":true}");
                break;
            }
            case "/api/admin/users/rotate": {
                if (!requireMethod(ex, method, "POST")) {
                    break;
                }
                String name = MediaAuthHttpServer.queryParam(readBody(ex), "username");
                send(ex, 200, "{\"streamKey\":" + quote(users.rotateKey(name)) + "}");
                break;
            }
            case "/api/admin/users/key": {
                if (!requireMethod(ex, method, "POST")) {
                    break;
                }
                String name = MediaAuthHttpServer.queryParam(readBody(ex), "username");
                String key = users.getStreamKey(name);
                if (key == null) {
                    send(ex, 404, error("that account has no stream key"));
                } else {
                    send(ex, 200, "{\"streamKey\":" + quote(key) + "}");
                }
                break;
            }
            case "/api/admin/pool":
                if (requireMethod(ex, method, "GET")) {
                    send(ex, 200, adminPoolJson());
                }
                break;
            case "/api/admin/pool/release": {
                if (!requireMethod(ex, method, "POST")) {
                    break;
                }
                String poolChannel = MediaAuthHttpServer.queryParam(readBody(ex), "channel");
                ClaimStore store = claims;
                if (store == null || poolChannel == null || !CHANNEL_ID.matcher(poolChannel).matches()) {
                    send(ex, 400, error("bad channel"));
                } else if (store.releaseChannel(poolChannel,
                        "Your channel " + poolChannel + " was released by the admin.")) {
                    send(ex, 200, "{\"ok\":true}");
                } else {
                    send(ex, 404, error("that channel is not claimed"));
                }
                break;
            }
            case "/api/admin/kick": {
                if (!requireMethod(ex, method, "POST")) {
                    break;
                }
                String channel = MediaAuthHttpServer.queryParam(readBody(ex), "channel");
                if (channel == null || !CHANNEL_ID.matcher(channel).matches()) {
                    send(ex, 400, error("bad channel"));
                } else {
                    kick.accept(channel);
                    send(ex, 200, "{\"ok\":true}");
                }
                break;
            }
            default:
                send(ex, 404, error("not found"));
        }
    }

    // ---- Channel pool ----

    private static long hoursLeft(long expiresAt, long now) {
        long ms = expiresAt - now;
        return ms <= 0 ? 0 : (ms + 3599999L) / 3600000L;
    }

    private UserStore.Info signedIn(HttpExchange ex) {
        SessionStore.Session s = session(ex);
        UserStore.Info info = s == null ? null : users.info(s.username);
        return (info == null || info.disabled) ? null : info;
    }

    private String claimJson(ClaimStore store, ClaimStore.Claim c) {
        long now = store.now();
        long expires = c.lastUsed + store.ttlMs();
        return "{\"channelId\":" + quote(c.channelId)
                + ",\"streamKey\":" + quote(c.streamKey())
                + ",\"hoursLeft\":" + hoursLeft(expires, now)
                + ",\"warning\":" + (expires - now <= store.warnMs()) + "}";
    }

    private void appendClaim(StringBuilder sb, String username) {
        ClaimStore store = claims;
        if (store == null) {
            return;
        }
        ClaimStore.Claim c = store.claimOf(username);
        if (c != null) {
            sb.append(",\"claim\":").append(claimJson(store, c));
        }
        String notice = store.noticeFor(username);
        if (notice != null) {
            sb.append(",\"notice\":").append(quote(notice));
        }
    }

    private void poolList(HttpExchange ex) throws IOException {
        UserStore.Info me = signedIn(ex);
        if (me == null) {
            send(ex, 401, error("sign in required"));
            return;
        }
        ClaimStore store = claims;
        if (store == null) {
            send(ex, 404, error("the channel pool is not enabled"));
            return;
        }
        long now = store.now();
        StringBuilder sb = new StringBuilder("{\"ttlHours\":").append(store.ttlMs() / 3600000L)
                .append(",\"pool\":[");
        boolean first = true;
        for (ClaimStore.PoolEntry p : store.pool()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            String status = p.holder == null ? "FREE"
                    : (p.holder.equalsIgnoreCase(me.username) ? "YOURS" : "TAKEN");
            sb.append("{\"channelId\":").append(quote(p.channelId));
            sb.append(",\"status\":").append(quote(status));
            if ("YOURS".equals(status)) {
                sb.append(",\"hoursLeft\":").append(hoursLeft(p.expiresAt, now));
            }
            sb.append('}');
        }
        send(ex, 200, sb.append("]}").toString());
    }

    private void claimChannel(HttpExchange ex) throws IOException {
        UserStore.Info me = signedIn(ex);
        if (me == null) {
            send(ex, 401, error("sign in required"));
            return;
        }
        ClaimStore store = claims;
        if (store == null) {
            send(ex, 404, error("the channel pool is not enabled"));
            return;
        }
        if (me.role == UserStore.Role.GUEST) {
            send(ex, 403, error("guest accounts can chat but not broadcast - create a registered account"));
            return;
        }
        if (me.role == UserStore.Role.ADMIN) {
            send(ex, 403, error("the admin account cannot hold a channel"));
            return;
        }
        String channel = MediaAuthHttpServer.queryParam(readBody(ex), "channel");
        if ("auto".equals(channel)) {
            // the Vstream app: the channel this account already holds, or any free one
            ClaimStore.Claim have = store.claimOf(me.username);
            if (have != null) {
                send(ex, 200, claimJson(store, have));
                return;
            }
            for (ClaimStore.PoolEntry p : store.pool()) {
                if (p.holder == null) {
                    try {
                        send(ex, 200, claimJson(store, store.claim(me.username, p.channelId)));
                        return;
                    } catch (ClaimStore.ClaimException e) {
                        // taken a moment ago - try the next one
                    }
                }
            }
            send(ex, 409, error("all channels are in use right now - please try again in a few minutes"));
            return;
        }
        if (channel == null || !CHANNEL_ID.matcher(channel).matches()) {
            send(ex, 400, error("bad channel"));
            return;
        }
        try {
            send(ex, 200, claimJson(store, store.claim(me.username, channel)));
        } catch (ClaimStore.ClaimException e) {
            send(ex, 409, error(e.getMessage()));
        }
    }

    private void releaseChannel(HttpExchange ex) throws IOException {
        UserStore.Info me = signedIn(ex);
        if (me == null) {
            send(ex, 401, error("sign in required"));
            return;
        }
        ClaimStore store = claims;
        if (store == null) {
            send(ex, 404, error("the channel pool is not enabled"));
            return;
        }
        try {
            store.release(me.username);
            send(ex, 200, "{\"ok\":true}");
        } catch (ClaimStore.ClaimException e) {
            send(ex, 409, error(e.getMessage()));
        }
    }

    private void ackNotice(HttpExchange ex) throws IOException {
        UserStore.Info me = signedIn(ex);
        if (me == null) {
            send(ex, 401, error("sign in required"));
            return;
        }
        ClaimStore store = claims;
        if (store != null) {
            store.ackNotice(me.username);
        }
        send(ex, 200, "{\"ok\":true}");
    }

    private String adminPoolJson() {
        ClaimStore store = claims;
        if (store == null) {
            return "{\"ttlHours\":0,\"pool\":[]}";
        }
        long now = store.now();
        StringBuilder sb = new StringBuilder("{\"ttlHours\":").append(store.ttlMs() / 3600000L)
                .append(",\"pool\":[");
        boolean first = true;
        for (ClaimStore.PoolEntry p : store.pool()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append("{\"channelId\":").append(quote(p.channelId));
            sb.append(",\"holder\":").append(p.holder == null ? "null" : quote(p.holder));
            sb.append(",\"live\":").append(liveCheck.test(p.channelId));
            if (p.holder != null) {
                sb.append(",\"idleHours\":").append(Math.max(0L, (now - p.lastUsed) / 3600000L));
                sb.append(",\"hoursLeft\":").append(hoursLeft(p.expiresAt, now));
            }
            sb.append('}');
        }
        return sb.append("]}").toString();
    }

    private String usersJson() {
        List<UserStore.Info> list = users.listInfos();
        StringBuilder sb = new StringBuilder("{\"users\":[");
        boolean first = true;
        for (UserStore.Info u : list) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            boolean live = u.channelId != null && !u.disabled && liveCheck.test(u.channelId);
            sb.append("{\"username\":").append(quote(u.username));
            sb.append(",\"role\":").append(quote(u.role.name()));
            sb.append(",\"channelId\":").append(u.channelId == null ? "null" : quote(u.channelId));
            sb.append(",\"disabled\":").append(u.disabled);
            sb.append(",\"hasPassword\":").append(u.hasPassword);
            sb.append(",\"live\":").append(live);
            EmailService.Entry em = email == null ? null : email.get(u.username);
            sb.append(",\"email\":").append(quote(em == null ? "" : em.email));
            sb.append(",\"emailVerified\":").append(em != null && em.verified);
            sb.append('}');
        }
        return sb.append("]}").toString();
    }

    private void handleAdminPage(HttpExchange ex) throws IOException {
        servePage(ex, "/admin", adminPage);
    }

    /** The web dashboard for everyone (user.html, next to admin.html): chat, wall, people and more from a browser. */
    private void handleUserPage(HttpExchange ex) throws IOException {
        Path parent = adminPage.toAbsolutePath().getParent();
        servePage(ex, "/me", parent == null ? Paths.get("user.html") : parent.resolve("user.html"));
    }

    /** A page that sits next to admin.html (user.html, home.html, terms.html, privacy.html). */
    private Path sibling(String name) {
        Path parent = adminPage.toAbsolutePath().getParent();
        return parent == null ? Paths.get(name) : parent.resolve(name);
    }

    /**
     * The public homepage at the site address (home.html next to admin.html). Returns false when there is no
     * home.html, so the caller can send people to the web dashboard instead.
     */
    // ---- The video site (mkarimu.net): the same server, with the TV wall as its home page ----

    /** The video site's name (vstream.videoHost, default mkarimu.net). */
    static final String VIDEO_HOST = System.getProperty("vstream.videoHost", "mkarimu.net").trim().toLowerCase(java.util.Locale.ROOT);
    /** Set once a visitor arrives through the video site's name (its DNS points here): memoryq.com/tv then sends people there. */
    private volatile boolean videoHostSeen;

    boolean isVideoHost(HttpExchange ex) {
        String h = String.valueOf(ex.getRequestHeaders().getFirst("Host")).toLowerCase(java.util.Locale.ROOT).replaceAll(":\\d+$", "");
        boolean yes = !VIDEO_HOST.isEmpty() && (h.equals(VIDEO_HOST) || h.equals("www." + VIDEO_HOST));
        if (yes) {
            videoHostSeen = true;
        }
        return yes;
    }

    public boolean videoHostSeen() {
        return videoHostSeen;
    }

    /** The TV wall as "Mkarimu TV"; signing in, comments and going live stay on Memoryq. */
    private void serveTv(HttpExchange ex, String route) throws IOException {
        Path page = sibling("tv.html");
        try {
            String path = ex.getRequestURI().getPath();
            if (!path.equals(route) && !path.equals(route + "/") || !Files.isRegularFile(page)) {
                send(ex, 404, error("not found"));
                return;
            }
            String main = System.getProperty("vstream.mediaHost", "memoryq.com");
            String html = new String(Files.readAllBytes(page), StandardCharsets.UTF_8)
                    .replace("Memoryq TV", "Mkarimu TV")
                    .replace("Memoryq <small>TV</small>", "Mkarimu <small>TV</small>")
                    .replace("live on Memoryq right now", "live on Mkarimu TV right now")
                    .replace("href=\"/me#", "href=\"https://" + main + "/me#")
                    .replace("href=\"/vstream-legal\"", "href=\"https://" + main + "/vstream-legal\"");
            byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            ex.getResponseHeaders().set("Cache-Control", "no-store");
            ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
            ex.getResponseHeaders().set("X-Frame-Options", "SAMEORIGIN");
            ex.getResponseHeaders().set("Referrer-Policy", "no-referrer");
            ex.sendResponseHeaders(200, bytes.length);
            ex.getResponseBody().write(bytes);
        } finally {
            ex.close();
        }
    }

    public boolean serveHome(HttpExchange ex) throws IOException {
        if (isVideoHost(ex)) {
            serveTv(ex, "/");
            return true;
        }
        Path home = sibling("home.html");
        if (!Files.isRegularFile(home)) {
            return false;
        }
        servePage(ex, "/", home);
        return true;
    }

    private void servePage(HttpExchange ex, String route, Path page) throws IOException {
        try {
            String path = ex.getRequestURI().getPath();
            if (!path.equals(route) && !path.equals(route + "/")) {
                send(ex, 404, error("not found"));
                return;
            }
            if (!ex.getRequestMethod().equalsIgnoreCase("GET")) {
                ex.getResponseHeaders().set("Allow", "GET");
                send(ex, 405, error("method not allowed"));
                return;
            }
            if (!Files.isRegularFile(page)) {
                send(ex, 404, error(page.getFileName() + " was not found next to the server"));
                return;
            }
            byte[] bytes = Files.readAllBytes(page);
            ex.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            ex.getResponseHeaders().set("Cache-Control", "no-store");
            ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
            ex.getResponseHeaders().set("X-Frame-Options", "SAMEORIGIN");
            ex.getResponseHeaders().set("Referrer-Policy", "no-referrer");
            ex.sendResponseHeaders(200, bytes.length);
            OutputStream os = ex.getResponseBody();
            os.write(bytes);
            os.close();
        } finally {
            ex.close();
        }
    }

    // ---- Helpers ----

    private String loginJson(String token, UserStore.Info info) {
        StringBuilder sb = new StringBuilder("{");
        if (token != null) {
            sb.append("\"token\":").append(quote(token)).append(',');
        }
        sb.append("\"username\":").append(quote(info.username));
        sb.append(",\"role\":").append(quote(info.role.name()));
        if (info.role == UserStore.Role.BROADCASTER && info.channelId != null) {
            sb.append(",\"channelId\":").append(quote(info.channelId));
            String key = users.getStreamKey(info.username);
            if (key != null) {
                sb.append(",\"streamKey\":").append(quote(key));
            }
        }
        appendClaim(sb, info.username);
        return sb.append('}').toString();
    }

    private SessionStore.Session session(HttpExchange ex) {
        return sessions.get(bearerToken(ex));
    }

    private static String bearerToken(HttpExchange ex) {
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        if (auth == null || !auth.startsWith("Bearer ")) {
            return null;
        }
        return auth.substring(7).trim();
    }

    private static String clientIp(HttpExchange ex) {
        return ex.getRemoteAddress().getAddress().getHostAddress();
    }

    private boolean requireMethod(HttpExchange ex, String actual, String wanted) throws IOException {
        if (actual.equalsIgnoreCase(wanted)) {
            return true;
        }
        ex.getResponseHeaders().set("Allow", wanted);
        send(ex, 405, error("method not allowed"));
        return false;
    }

    private static String readBody(HttpExchange ex) throws IOException {
        InputStream in = ex.getRequestBody();
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] tmp = new byte[1024];
        int total = 0;
        int n;
        while ((n = in.read(tmp)) != -1) {
            total += n;
            if (total > MAX_BODY) {
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

    private static String quote(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n");  break;
                case '\r': sb.append("\\r");  break;
                case '\t': sb.append("\\t");  break;
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