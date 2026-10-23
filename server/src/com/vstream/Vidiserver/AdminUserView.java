package com.vstream.Vidiserver;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Predicate;

/**
 * The super-admin's view of one account: everything it has in one place - account, profile, streams and
 * channel, "also stream to" destinations, radio stations, videos and video comments, wall posts, chat
 * messages and groups - so the admin can check a suspect account and suspend it.
 *
 *   GET /api/admin/user360?u=NAME      (super-admin only)
 *
 * Every part is optional: a part whose store is not connected is simply left out.
 */
public final class AdminUserView {

    private final UserStore users;
    private final SessionStore sessions;
    private volatile ProfileStore profiles;
    private volatile EmailService emails;
    private volatile ModerationService moderation;
    private volatile ClaimStore claims;
    private volatile Predicate<String> isLive = id -> false;
    private volatile Restreamer restreamer;
    private volatile RadioStore radio;
    private volatile VideoStore videos;
    private volatile WallStore wall;
    private volatile ChatStore chat;

    public AdminUserView(UserStore users, SessionStore sessions) {
        this.users = users;
        this.sessions = sessions;
    }

    public AdminUserView profiles(ProfileStore p) { this.profiles = p; return this; }
    public AdminUserView emails(EmailService e) { this.emails = e; return this; }
    public AdminUserView moderation(ModerationService m) { this.moderation = m; return this; }
    public AdminUserView claims(ClaimStore c, Predicate<String> live) { this.claims = c; this.isLive = live; return this; }
    public AdminUserView restreamer(Restreamer r) { this.restreamer = r; return this; }
    public AdminUserView radio(RadioStore r) { this.radio = r; return this; }
    public AdminUserView videos(VideoStore v) { this.videos = v; return this; }
    public AdminUserView wall(WallStore w) { this.wall = w; return this; }
    public AdminUserView chat(ChatStore c) { this.chat = c; return this; }

    public void register(HttpServer server) {
        server.createContext("/api/admin/user360", this::handle);
    }

    private void handle(HttpExchange ex) throws IOException {
        try {
            String auth = ex.getRequestHeaders().getFirst("Authorization");
            String token = auth != null && auth.startsWith("Bearer ") ? auth.substring(7).trim() : "";
            SessionStore.Session s = token.isEmpty() ? null : sessions.get(token);
            if (s == null) {
                send(ex, 401, "{\"error\":\"sign in required\"}");
                return;
            }
            if (s.role != UserStore.Role.ADMIN) {
                send(ex, 403, "{\"error\":\"super-admin only\"}");
                return;
            }
            String u = MediaAuthHttpServer.queryParam(ex.getRequestURI().getRawQuery(), "u");
            UserStore.Info info = u == null ? null : users.info(u);
            if (info == null) {
                send(ex, 404, "{\"error\":\"no such account\"}");
                return;
            }
            send(ex, 200, json(info));
        } catch (RuntimeException e) {
            send(ex, 500, "{\"error\":" + q("server error: " + e) + "}");
        } finally {
            ex.close();
        }
    }

    String json(UserStore.Info info) {
        String name = info.username;
        StringBuilder sb = new StringBuilder("{");
        // account
        sb.append("\"account\":{\"username\":").append(q(name)).append(",\"role\":").append(q(info.role.name()))
                .append(",\"disabled\":").append(info.disabled).append(",\"lastActive\":").append(users.lastActive(name))
                .append(",\"online\":").append(chat != null && chat.isOnline(name));
        EmailService es = emails;
        EmailService.Entry e = es == null ? null : es.get(name);
        sb.append(",\"email\":").append(e == null ? "null" : q(e.email)).append(",\"emailVerified\":").append(e != null && e.verified);
        ModerationService mod = moderation;
        ModerationService.Suspension sus = null;
        if (mod != null) {
            for (ModerationService.Suspension x : mod.suspensions()) {
                if (x.username.equalsIgnoreCase(name)) {
                    sus = x;
                }
            }
        }
        sb.append(",\"suspension\":");
        if (sus == null) {
            sb.append("null");
        } else {
            sb.append("{\"since\":").append(sus.since).append(",\"until\":").append(sus.until).append(",\"by\":").append(q(sus.by))
                    .append(",\"notice\":").append(q(sus.notice)).append(",\"reason\":").append(q(sus.reason)).append('}');
        }
        sb.append('}');

        // profile
        ProfileStore ps = profiles;
        ProfileStore.Profile p = ps == null ? null : ps.get(name);
        sb.append(",\"profile\":");
        if (p == null) {
            sb.append("null");
        } else {
            sb.append("{\"firstName\":").append(q(p.firstName)).append(",\"lastName\":").append(q(p.lastName))
                    .append(",\"stageName\":").append(q(p.stageName)).append(",\"city\":").append(q(p.city))
                    .append(",\"country\":").append(q(p.country)).append(",\"education\":").append(q(p.education))
                    .append(",\"hobbies\":").append(list(p.hobbies)).append(",\"friends\":").append(p.friends.size())
                    .append(",\"test\":").append(p.test).append(",\"picture\":").append(q("/api/profiles/picture?u=" + name)).append('}');
        }

        // streams
        sb.append(",\"streams\":{");
        String channel = info.channelId == null ? "" : info.channelId;
        ClaimStore cs = claims;
        ClaimStore.Claim c = cs == null ? null : cs.claimOf(name);
        if (c != null) {
            channel = c.channelId;
        }
        sb.append("\"channel\":").append(channel.isEmpty() ? "null" : q(channel)).append(",\"assigned\":").append(info.channelId != null && !info.channelId.isEmpty())
                .append(",\"liveNow\":").append(!channel.isEmpty() && isLive.test(channel));
        if (c != null) {
            sb.append(",\"claimLastUsed\":").append(c.lastUsed);
        }
        Restreamer rs = restreamer;
        sb.append(",\"alsoStreamTo\":").append(rs == null ? "null" : rs.json(name.toLowerCase(java.util.Locale.ROOT)));
        sb.append('}');

        // radio stations
        RadioStore rst = radio;
        sb.append(",\"stations\":[");
        if (rst != null) {
            int n = 0;
            for (RadioStore.Station st : rst.stations()) {
                if (st.owner != null && st.owner.equalsIgnoreCase(name)) {
                    sb.append(n++ > 0 ? "," : "").append("{\"id\":").append(st.id).append(",\"name\":").append(q(st.name))
                            .append(",\"enabled\":").append(st.enabled).append('}');
                }
            }
        }
        sb.append(']');

        // videos and video comments
        VideoStore vs = videos;
        sb.append(",\"videos\":[");
        if (vs != null) {
            int n = 0;
            for (VideoStore.Video v : vs.byOwner(name)) {
                sb.append(n++ > 0 ? "," : "").append("{\"id\":").append(v.id).append(",\"title\":").append(q(v.title))
                        .append(",\"kind\":").append(q(v.kind)).append(",\"status\":").append(q(v.status)).append(",\"paused\":").append(q(v.paused))
                        .append(",\"featured\":").append(v.featured).append(",\"views\":").append(v.views).append(",\"created\":").append(v.created)
                        .append(",\"comments\":").append(vs.commentCount(v.id))
                        .append(",\"link\":").append(VideoStore.YOUTUBE.equals(v.kind) ? q("https://youtu.be/" + v.ref) : q("/api/videos/file/" + v.id + ".mp4"))
                        .append('}');
            }
        }
        sb.append("],\"videoComments\":[");
        if (vs != null) {
            int n = 0;
            for (VideoStore.Comment cm : vs.commentsBy(name, 50)) {
                VideoStore.Video v = vs.get(cm.video);
                sb.append(n++ > 0 ? "," : "").append("{\"id\":").append(cm.id).append(",\"video\":").append(cm.video)
                        .append(",\"videoTitle\":").append(q(v == null ? "" : v.title)).append(",\"text\":").append(q(cm.text))
                        .append(",\"created\":").append(cm.created).append('}');
            }
        }
        sb.append(']');

        // wall posts
        WallStore ws = wall;
        sb.append(",\"posts\":[");
        if (ws != null) {
            int n = 0;
            for (WallStore.Post post : ws.byAuthor(name, Long.MAX_VALUE, 50)) {
                sb.append(n++ > 0 ? "," : "").append("{\"id\":").append(post.id).append(",\"text\":").append(q(post.text))
                        .append(",\"created\":").append(post.created).append(",\"likes\":").append(post.likes.size())
                        .append(",\"reports\":").append(post.reports.size()).append(",\"image\":").append(!post.image.isEmpty())
                        .append(",\"friendsOnly\":").append(post.friendsOnly).append('}');
            }
        }
        sb.append(']');

        // chat
        ChatStore ch = chat;
        sb.append(",\"chat\":{\"messages\":[");
        if (ch != null) {
            int n = 0;
            for (ChatStore.Message m : ch.messagesBy(name, 60)) {
                sb.append(n++ > 0 ? "," : "").append("{\"id\":").append(m.id).append(",\"room\":").append(q(m.room))
                        .append(",\"time\":").append(m.time).append(",\"text\":").append(q(m.text)).append('}');
            }
        }
        sb.append("],\"groups\":[");
        if (ch != null) {
            int n = 0;
            for (String[] g : ch.groupsOf(name)) {
                sb.append(n++ > 0 ? "," : "").append("{\"id\":").append(q(g[0])).append(",\"title\":").append(q(g[1]))
                        .append(",\"as\":").append(q(g[2])).append(",\"members\":").append(g[3]).append(",\"type\":").append(q(g[4])).append('}');
            }
        }
        sb.append("]}");
        return sb.append('}').toString();
    }

    private static String list(List<String> items) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < items.size(); i++) {
            sb.append(i > 0 ? "," : "").append(q(items.get(i)));
        }
        return sb.append(']').toString();
    }

    private static String q(String s) {
        return VideoApi.jq(s == null ? "" : s);
    }

    private static void send(HttpExchange ex, int status, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(status, b.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(b);
        }
    }
}