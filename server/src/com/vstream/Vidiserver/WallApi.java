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
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.function.LongSupplier;

/**
 * Walls.
 *   GET  /api/wall/user?u=&before=     someone's posts that you may see (newest first, 20 at a time)
 *   GET  /api/wall/random?skip=1,2     a random "everyone" post for the news feed, and how often to show one
 *   GET  /api/wall/image?id=&k=        a post's picture (k is the secret key given with the post)
 *   POST /api/wall/post                text, visibility (everyone | friends), image (data URL, optional)
 *   POST /api/wall/delete  id=         your own post (the admin can delete any)
 *   POST /api/wall/like    id=, like=true|false
 *   POST /api/wall/report  id=, reason=
 *   GET  /api/wall/one?id=             one post (for opening a notification)
 *   GET  /api/wall/comments?post=      a post's comments (a tree: each has the id of the comment it answers)
 *   POST /api/wall/comment  post=, parent= (0 = the post), text=     (not if the post's author blocked you)
 *   POST /api/wall/comment/delete  id=   your comment, any comment on your post, or (admin) any comment
 *   GET  /api/wall/blocked   POST /api/wall/block u=   POST /api/wall/unblock u=   (one block list for calls and comments)
 *   /api/admin/wall/status | settings | remove | dismiss    (super-admin only)
 */
public final class WallApi {

    private static final int MAX_BODY = 8 * 1024;
    private static final int MAX_UPLOAD = 3 * 1024 * 1024;
    private static final int PAGE = 20;
    private static final int POSTS_PER_DAY = 20;
    private static final long DAY = 24L * 3600000L;

    private final WallStore wall;
    private final ProfileStore profiles;
    private final UserStore users;
    private final SessionStore sessions;
    private final ChatStore chat;
    private final Limiter limiter;
    private final LongSupplier clock;
    private final Random random;
    private volatile CallService blocks;
    private volatile NotificationStore notes;

    public void setNotifications(NotificationStore n) {
        this.notes = n;
    }

    /** Tells user about something actor did, unless user has blocked actor. */
    private void notify(String user, String type, String actor, long post, long comment, String text) {
        NotificationStore n = notes;
        if (n != null && !blocked(user, actor)) {
            n.add(user, type, actor, post, comment, text, clock.getAsLong());
            n.flushIfDirty();
        }
    }

    /** Blocks come from the calls add-on's block list: someone you blocked can neither call you nor comment. */
    public void setBlocks(CallService calls) {
        this.blocks = calls;
    }

    boolean blocked(String blocker, String target) {
        CallService b = blocks;
        return b != null && b.hasBlocked(blocker, target);
    }

    public WallApi(WallStore wall, ProfileStore profiles, UserStore users, SessionStore sessions, ChatStore chat,
                   Limiter limiter, LongSupplier clock, Random random) {
        this.wall = wall;
        this.profiles = profiles;
        this.users = users;
        this.sessions = sessions;
        this.chat = chat;
        this.limiter = limiter;
        this.clock = clock;
        this.random = random;
    }

    public void register(HttpServer server) {
        server.createContext("/api/wall/", new HttpHandler() {
            @Override
            public void handle(HttpExchange ex) throws IOException {
                handlePeople(ex);
            }
        });
        server.createContext("/api/admin/wall/", new HttpHandler() {
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

    /** May viewer see this post? Its author, the admin, everyone for "everyone" posts, the author's friends otherwise. */
    boolean canSee(WallStore.Post p, UserStore.Info viewer) {
        if (viewer == null) {
            return false;
        }
        if (viewer.role == UserStore.Role.ADMIN || p.author.equalsIgnoreCase(viewer.username) || !p.friendsOnly) {
            return true;
        }
        ProfileStore.Profile a = profiles.get(p.author);
        if (a != null) {
            for (String f : a.friends) {
                if (f.equalsIgnoreCase(viewer.username)) {
                    return true;
                }
            }
        }
        return false;
    }

    // ---- People ----

    private void handlePeople(HttpExchange ex) throws IOException {
        try {
            String path = ex.getRequestURI().getPath();
            String method = ex.getRequestMethod();
            String query = ex.getRequestURI().getRawQuery();
            if (path.equals("/api/wall/image")) {
                if (requireMethod(ex, method, "GET")) {
                    image(ex, id(MediaAuthHttpServer.queryParam(query, "id")), MediaAuthHttpServer.queryParam(query, "k"));
                }
                return;
            }
            UserStore.Info me = signedIn(ex);
            if (me == null) {
                send(ex, 401, error("sign in required"));
                return;
            }
            switch (path) {
                case "/api/wall/user":
                    if (requireMethod(ex, method, "GET")) {
                        userWall(ex, me, MediaAuthHttpServer.queryParam(query, "u"), MediaAuthHttpServer.queryParam(query, "before"));
                    }
                    break;
                case "/api/wall/random":
                    if (requireMethod(ex, method, "GET")) {
                        randomPost(ex, me, MediaAuthHttpServer.queryParam(query, "skip"));
                    }
                    break;
                case "/api/wall/post":
                    if (requireMethod(ex, method, "POST")) {
                        post(ex, me);
                    }
                    break;
                case "/api/wall/delete":
                    if (requireMethod(ex, method, "POST")) {
                        WallStore.Post p = existing(MediaAuthHttpServer.queryParam(readBody(ex, MAX_BODY), "id"), me);
                        if (!p.author.equalsIgnoreCase(me.username) && me.role != UserStore.Role.ADMIN) {
                            throw new Bad(403, "you can only delete your own posts");
                        }
                        wall.delete(p.id);
                        wall.flushIfDirty();
                        send(ex, 200, "{\"ok\":true}");
                    }
                    break;
                case "/api/wall/like": {
                    if (!requireMethod(ex, method, "POST")) {
                        break;
                    }
                    String body = readBody(ex, MAX_BODY);
                    WallStore.Post p = existing(MediaAuthHttpServer.queryParam(body, "id"), me);
                    boolean like = !"false".equals(MediaAuthHttpServer.queryParam(body, "like"));
                    int n = wall.setLike(p.id, me.username, like);
                    if (like) {
                        notify(p.author, NotificationStore.LIKE, me.username, p.id, 0, p.text);
                    }
                    send(ex, 200, "{\"likes\":" + n + ",\"liked\":" + like + "}");
                    break;
                }
                case "/api/wall/report": {
                    if (!requireMethod(ex, method, "POST")) {
                        break;
                    }
                    String body = readBody(ex, MAX_BODY);
                    WallStore.Post p = existing(MediaAuthHttpServer.queryParam(body, "id"), me);
                    if (p.author.equalsIgnoreCase(me.username)) {
                        throw new Bad(400, "you cannot report your own post");
                    }
                    String k = "wr|" + me.username.toLowerCase(Locale.ROOT);
                    if (limiter.count(k, DAY) >= 20) {
                        throw new Bad(429, "you sent many reports today - the admin will look at them");
                    }
                    if (wall.report(p.id, me.username, MediaAuthHttpServer.queryParam(body, "reason"))) {
                        limiter.record(k);
                    }
                    wall.flushIfDirty();
                    send(ex, 200, "{\"ok\":true}");
                    break;
                }
                case "/api/wall/one":
                    if (requireMethod(ex, method, "GET")) {
                        WallStore.Post p = existing(MediaAuthHttpServer.queryParam(query, "id"), me);
                        send(ex, 200, "{\"post\":" + postJson(p, me) + "}");
                    }
                    break;
                case "/api/wall/comments":
                    if (requireMethod(ex, method, "GET")) {
                        commentsJson(ex, me, existing(MediaAuthHttpServer.queryParam(query, "post"), me));
                    }
                    break;
                case "/api/wall/comment":
                    if (requireMethod(ex, method, "POST")) {
                        addComment(ex, me, readBody(ex, MAX_BODY));
                    }
                    break;
                case "/api/wall/comment/delete": {
                    if (!requireMethod(ex, method, "POST")) {
                        break;
                    }
                    WallStore.Comment c = wall.comment(id(MediaAuthHttpServer.queryParam(readBody(ex, MAX_BODY), "id")));
                    WallStore.Post p = c == null ? null : wall.get(c.post);
                    if (c == null || c.deleted || p == null || !canSee(p, me)) {
                        throw new Bad(404, "no such comment");
                    }
                    if (!c.author.equalsIgnoreCase(me.username) && !p.author.equalsIgnoreCase(me.username)
                            && me.role != UserStore.Role.ADMIN) {
                        throw new Bad(403, "you can delete your own comments, and any comment on your posts");
                    }
                    wall.deleteComment(c.id);
                    wall.flushIfDirty();
                    send(ex, 200, "{\"ok\":true,\"comments\":" + wall.commentCount(p.id) + "}");
                    break;
                }
                case "/api/wall/blocked":
                    if (requireMethod(ex, method, "GET")) {
                        CallService b = blocks;
                        StringBuilder sb = new StringBuilder("{\"blocked\":[");
                        if (b != null) {
                            List<String> list = b.blockedBy(me.username);
                            for (int i = 0; i < list.size(); i++) {
                                if (i > 0) {
                                    sb.append(',');
                                }
                                sb.append(ProfileApi.quote(list.get(i)));
                            }
                        }
                        send(ex, 200, sb.append("]}").toString());
                    }
                    break;
                case "/api/wall/block":
                case "/api/wall/unblock":
                    if (requireMethod(ex, method, "POST")) {
                        CallService b = blocks;
                        if (b == null) {
                            throw new Bad(404, "blocking is not available");
                        }
                        String u = MediaAuthHttpServer.queryParam(readBody(ex, MAX_BODY), "u");
                        try {
                            if (path.endsWith("/block")) {
                                b.block(me.username, u);
                            } else {
                                b.unblock(me.username, u);
                            }
                        } catch (CallService.CallException e) {
                            throw new Bad(e.status, e.getMessage());
                        }
                        b.flushIfDirty();
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

    /** Whether me may comment on this post: not the admin, not blocked by its author, not muted. */
    private boolean mayComment(WallStore.Post p, UserStore.Info me) {
        return me.role != UserStore.Role.ADMIN && !blocked(p.author, me.username)
                && (chat == null || !chat.isMuted(me.username));
    }

    private void commentsJson(HttpExchange ex, UserStore.Info me, WallStore.Post p) throws IOException {
        List<WallStore.Comment> list = wall.comments(p.id);
        java.util.Map<Long, Integer> replies = new java.util.HashMap<Long, Integer>();
        for (WallStore.Comment c : list) {
            if (c.parent != 0 && !c.deleted) {
                replies.merge(c.parent, 1, Integer::sum);
            }
        }
        StringBuilder sb = new StringBuilder("{\"post\":").append(p.id)
                .append(",\"canComment\":").append(mayComment(p, me))
                .append(",\"count\":").append(wall.commentCount(p.id)).append(",\"comments\":[");
        boolean first = true;
        for (WallStore.Comment c : list) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append(commentJson(c, p, me, replies.getOrDefault(c.id, 0)));
        }
        send(ex, 200, sb.append("]}").toString());
    }

    private String commentJson(WallStore.Comment c, WallStore.Post p, UserStore.Info me, int replies) {
        ProfileStore.Profile a = c.deleted ? null : profiles.get(c.author);
        boolean mine = c.author.equalsIgnoreCase(me.username);
        return "{\"id\":" + c.id + ",\"parent\":" + c.parent
                + ",\"author\":" + ProfileApi.quote(c.deleted ? "" : c.author)
                + ",\"stageName\":" + ProfileApi.quote(a == null ? "" : a.stageName)
                + ",\"text\":" + ProfileApi.quote(c.deleted ? "" : c.text)
                + ",\"deleted\":" + c.deleted + ",\"created\":" + c.created
                + ",\"mine\":" + mine
                + ",\"canDelete\":" + (!c.deleted && (mine || p.author.equalsIgnoreCase(me.username)
                        || me.role == UserStore.Role.ADMIN))
                + ",\"replies\":" + replies + "}";
    }

    private void addComment(HttpExchange ex, UserStore.Info me, String body) throws Exception {
        WallStore.Post p = existing(MediaAuthHttpServer.queryParam(body, "post"), me);
        if (me.role == UserStore.Role.ADMIN) {
            throw new Bad(403, "the super-admin account does not comment");
        }
        if (chat != null && chat.isMuted(me.username)) {
            throw new Bad(403, "you are muted and cannot comment right now");
        }
        if (blocked(p.author, me.username)) {
            throw new Bad(403, "you cannot comment on this person's posts");
        }
        long parent = 0;
        String parentText = MediaAuthHttpServer.queryParam(body, "parent");
        if (parentText != null && !parentText.trim().isEmpty() && !"0".equals(parentText.trim())) {
            parent = id(parentText);
            WallStore.Comment pc = wall.comment(parent);
            if (pc == null || pc.post != p.id) {
                throw new Bad(404, "that comment no longer exists");
            }
            if (!pc.deleted && blocked(pc.author, me.username)) {
                throw new Bad(403, "you cannot answer this person's comments");
            }
        }
        String text = cleanText(MediaAuthHttpServer.queryParam(body, "text"));
        if (text.length() > WallStore.MAX_COMMENT) {
            text = text.substring(0, WallStore.MAX_COMMENT).trim();
        }
        if (text.isEmpty()) {
            throw new Bad(400, "write a comment first");
        }
        if (wall.commentsBy(me.username, clock.getAsLong() - 3600000L) >= 60) {
            throw new Bad(429, "you wrote many comments in the last hour - please wait a little");
        }
        WallStore.Comment c = wall.addComment(p.id, parent, me.username, text, clock.getAsLong());
        if (c == null) {
            throw new Bad(409, "this post cannot take more comments");
        }
        String parentAuthor = "";
        if (parent != 0) {
            WallStore.Comment pc = wall.comment(parent);
            parentAuthor = pc == null || pc.deleted ? "" : pc.author;
        }
        if (!parentAuthor.isEmpty()) {
            notify(parentAuthor, NotificationStore.REPLY, me.username, p.id, c.id, text);
        }
        if (!p.author.equalsIgnoreCase(parentAuthor)) {
            notify(p.author, NotificationStore.COMMENT, me.username, p.id, c.id, text);
        }
        wall.flushIfDirty();
        send(ex, 200, "{\"comment\":" + commentJson(c, p, me, 0) + ",\"count\":" + wall.commentCount(p.id) + "}");
    }

    /** A post that exists and that me may see, or a 404 (a hidden post looks the same as a missing one). */
    private WallStore.Post existing(String idText, UserStore.Info me) throws Bad {
        WallStore.Post p = wall.get(id(idText));
        if (p == null || !canSee(p, me)) {
            throw new Bad(404, "no such post");
        }
        return p;
    }

    private void userWall(HttpExchange ex, UserStore.Info me, String u, String before) throws Exception {
        UserStore.Info who = u == null ? null : users.info(u.trim());
        if (who == null || who.role == UserStore.Role.ADMIN || (who.disabled && me.role != UserStore.Role.ADMIN)) {
            throw new Bad(404, "no such person");
        }
        long b = 0;
        try {
            b = before == null ? 0 : Long.parseLong(before.trim());
        } catch (NumberFormatException e) {
            b = 0;
        }
        StringBuilder sb = new StringBuilder("{\"posts\":[");
        boolean first = true;
        int shown = 0;
        long cursor = b;
        // Read in pages until enough visible posts are found (friends-only posts may be skipped).
        for (int round = 0; round < 10 && shown < PAGE; round++) {
            List<WallStore.Post> page = wall.byAuthor(who.username, cursor, PAGE);
            if (page.isEmpty()) {
                break;
            }
            for (WallStore.Post p : page) {
                cursor = p.id;
                if (shown < PAGE && canSee(p, me)) {
                    if (!first) {
                        sb.append(',');
                    }
                    first = false;
                    sb.append(postJson(p, me));
                    shown++;
                }
            }
        }
        sb.append("],\"more\":").append(shown >= PAGE).append(",\"next\":").append(cursor).append('}');
        send(ex, 200, sb.toString());
    }

    private void randomPost(HttpExchange ex, UserStore.Info me, String skip) throws IOException {
        List<Long> skipIds = new ArrayList<Long>();
        if (skip != null) {
            for (String s : skip.split(",")) {
                try {
                    skipIds.add(Long.parseLong(s.trim()));
                } catch (NumberFormatException e) {
                    // ignore
                }
                if (skipIds.size() >= 500) {
                    break;
                }
            }
        }
        WallStore.Settings s = wall.settings();
        WallStore.Post p = s.feedEvery > 0 ? wall.randomPublic(me.username, skipIds, random) : null;
        if (p != null) {
            UserStore.Info a = users.info(p.author);
            if (a == null || a.disabled) {
                p = null;
            }
        }
        send(ex, 200, "{\"every\":" + s.feedEvery + ",\"post\":" + (p == null ? "null" : postJson(p, me)) + "}");
    }

    private void post(HttpExchange ex, UserStore.Info me) throws Exception {
        if (me.role == UserStore.Role.ADMIN) {
            throw new Bad(403, "the super-admin account does not have a wall");
        }
        if (chat != null && chat.isMuted(me.username)) {
            throw new Bad(403, "you are muted and cannot post right now");
        }
        String body = readBody(ex, MAX_UPLOAD);
        if (wall.countBy(me.username, clock.getAsLong() - DAY) >= POSTS_PER_DAY) {
            throw new Bad(429, "you can post " + POSTS_PER_DAY + " times a day - please wait");
        }
        String text = cleanText(MediaAuthHttpServer.queryParam(body, "text"));
        String visibility = MediaAuthHttpServer.queryParam(body, "visibility");
        boolean friendsOnly = "friends".equalsIgnoreCase(visibility);
        String data = MediaAuthHttpServer.queryParam(body, "image");
        byte[] img = null;
        String ext = null;
        if (data != null && !data.isEmpty()) {
            int comma = data.indexOf(',');
            try {
                img = Base64.getDecoder().decode(comma >= 0 ? data.substring(comma + 1) : data);
            } catch (IllegalArgumentException e) {
                throw new Bad(400, "the picture could not be read");
            }
            if (img.length > WallStore.MAX_IMAGE) {
                throw new Bad(400, "the picture is too big (2 MB at most)");
            }
            if (img.length > 8 && (img[0] & 0xFF) == 0x89 && img[1] == 'P' && img[2] == 'N' && img[3] == 'G') {
                ext = "png";
            } else if (img.length > 3 && (img[0] & 0xFF) == 0xFF && (img[1] & 0xFF) == 0xD8 && (img[2] & 0xFF) == 0xFF) {
                ext = "jpg";
            } else {
                throw new Bad(400, "the picture must be a PNG or JPEG");
            }
        }
        if (text.isEmpty() && img == null) {
            throw new Bad(400, "write something or add a picture");
        }
        ProfileStore.Profile prof = profiles.get(me.username);
        WallStore.Post p = wall.create(me.username, text, img, ext, friendsOnly, prof != null && prof.test, clock.getAsLong());
        wall.flushIfDirty();
        send(ex, 200, "{\"post\":" + postJson(p, me) + "}");
    }

    /** Keeps line breaks (at most 2 in a row), drops other control characters, trims, and shortens. */
    static String cleanText(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\n') {
                sb.append('\n');
            } else if (c == '\t') {
                sb.append(' ');
            } else if (c >= 0x20 && c != 0x7F) {
                sb.append(c);
            }
        }
        String t = sb.toString().replaceAll("\n{3,}", "\n\n").trim();
        return t.length() > WallStore.MAX_TEXT ? t.substring(0, WallStore.MAX_TEXT).trim() : t;
    }

    private void image(HttpExchange ex, long id, String key) throws IOException {
        WallStore.Post p = wall.get(id);
        byte[] img = p == null ? null : wall.image(id, key);
        if (img == null) {
            send(ex, 404, error("no such picture"));
            return;
        }
        ex.getResponseHeaders().set("Content-Type", p.image.endsWith(".png") ? "image/png" : "image/jpeg");
        ex.getResponseHeaders().set("Cache-Control", "private, max-age=3600");
        ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        ex.sendResponseHeaders(200, img.length);
        OutputStream os = ex.getResponseBody();
        os.write(img);
        os.close();
    }

    private String postJson(WallStore.Post p, UserStore.Info viewer) {
        ProfileStore.Profile a = profiles.get(p.author);
        StringBuilder sb = new StringBuilder("{\"id\":").append(p.id);
        sb.append(",\"author\":").append(ProfileApi.quote(p.author));
        sb.append(",\"stageName\":").append(ProfileApi.quote(a == null ? "" : a.stageName));
        sb.append(",\"name\":").append(ProfileApi.quote(a == null ? "" : (a.firstName + " " + a.lastName).trim()));
        sb.append(",\"country\":").append(ProfileApi.quote(a == null ? "" : a.country));
        sb.append(",\"text\":").append(ProfileApi.quote(p.text));
        sb.append(",\"imageUrl\":").append(ProfileApi.quote(p.image.isEmpty() ? ""
                : "/api/wall/image?id=" + p.id + "&k=" + p.imageKey()));
        sb.append(",\"friendsOnly\":").append(p.friendsOnly);
        sb.append(",\"test\":").append(p.test);
        sb.append(",\"created\":").append(p.created);
        sb.append(",\"likes\":").append(p.likes.size());
        sb.append(",\"liked\":").append(p.likedBy(viewer.username));
        sb.append(",\"mine\":").append(p.author.equalsIgnoreCase(viewer.username));
        sb.append(",\"comments\":").append(wall.commentCount(p.id));
        sb.append(",\"canComment\":").append(mayComment(p, viewer));
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
                case "/api/admin/wall/status":
                    if (requireMethod(ex, method, "GET")) {
                        send(ex, 200, statusJson(me));
                    }
                    break;
                case "/api/admin/wall/settings":
                    if (requireMethod(ex, method, "POST")) {
                        String body = readBody(ex, MAX_BODY);
                        WallStore.Settings s = wall.settings();
                        String every = MediaAuthHttpServer.queryParam(body, "feedEvery");
                        if (every != null) {
                            try {
                                int n = Integer.parseInt(every.trim());
                                if (n != 0 && (n < 2 || n > 50)) {
                                    throw new Bad(400, "a post can come every 2 to 50 stories (0 = never)");
                                }
                                s.feedEvery = n;
                            } catch (NumberFormatException e) {
                                throw new Bad(400, "feedEvery must be a number");
                            }
                        }
                        String tp = MediaAuthHttpServer.queryParam(body, "testPostsOn");
                        if (tp != null) {
                            s.testPostsOn = "true".equals(tp);
                        }
                        wall.setSettings(s);
                        wall.flushIfDirty();
                        send(ex, 200, statusJson(me));
                    }
                    break;
                case "/api/admin/wall/remove":
                    if (requireMethod(ex, method, "POST")) {
                        if (!wall.delete(id(MediaAuthHttpServer.queryParam(readBody(ex, MAX_BODY), "id")))) {
                            throw new Bad(404, "no such post");
                        }
                        wall.flushIfDirty();
                        send(ex, 200, statusJson(me));
                    }
                    break;
                case "/api/admin/wall/dismiss":
                    if (requireMethod(ex, method, "POST")) {
                        if (!wall.dismissReports(id(MediaAuthHttpServer.queryParam(readBody(ex, MAX_BODY), "id")))) {
                            throw new Bad(404, "no reports on that post");
                        }
                        wall.flushIfDirty();
                        send(ex, 200, statusJson(me));
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

    private String statusJson(UserStore.Info admin) {
        WallStore.Settings s = wall.settings();
        StringBuilder sb = new StringBuilder("{\"feedEvery\":").append(s.feedEvery)
                .append(",\"testPostsOn\":").append(s.testPostsOn).append(",\"posts\":").append(wall.size())
                .append(",\"reported\":[");
        boolean first = true;
        for (WallStore.Post p : wall.reported()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            StringBuilder reasons = new StringBuilder();
            for (java.util.Map.Entry<String, String> e : p.reports.entrySet()) {
                if (reasons.length() > 0) {
                    reasons.append("; ");
                }
                reasons.append(e.getKey()).append(e.getValue().isEmpty() ? "" : ": " + e.getValue());
            }
            String json = postJson(p, admin);
            sb.append(json, 0, json.length() - 1).append(",\"reports\":").append(p.reports.size())
                    .append(",\"reasons\":").append(ProfileApi.quote(reasons.toString())).append('}');
        }
        return sb.append("]}").toString();
    }

    // ---- Helpers ----

    private static long id(String s) throws Bad {
        try {
            return Long.parseLong(s == null ? "" : s.trim());
        } catch (NumberFormatException e) {
            throw new Bad(400, "bad post id");
        }
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
                throw new Bad(413, "too much data sent (pictures can be 2 MB at most)");
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