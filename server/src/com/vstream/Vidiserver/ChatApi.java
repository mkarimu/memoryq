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
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * Chat endpoints. Reading public rooms is open; everything else needs a sign-in, and private groups are
 * visible to their members only.
 *   GET  /api/chat/rooms  /top  /unread  /messages  /search  /info  /prefs  /invite?code=  /invitations
 *        /groups/members  /groups/invites
 *   POST /api/chat/post  /edit  /react  /typing  /delete  /read  /forward  /pin  /prefs
 *        /groups/create (private=true for an invite-only group)  /groups/delete  /groups/join
 *        /groups/invite/create  /groups/invite/revoke  /groups/invite/user  /groups/remove  /groups/leave
 *        /invitations/accept  /invitations/decline
 *   GET  /join/CODE   a small web page for people who tap an invite link
 *   /api/admin/chat/mute|unmute|muted|clear|groups   (admin only)
 */
public final class ChatApi {

    private static final int MAX_BODY = 4 * 1024;
    private static final Pattern CHANNEL_ID = Pattern.compile("CHANNEL-\\d{1,9}");

    private static final class Entry {
        final String json;
        final long lastTime;
        final boolean group;
        final int recent;

        Entry(String json, long lastTime, boolean group, int recent) {
            this.json = json;
            this.lastTime = lastTime;
            this.group = group;
            this.recent = recent;
        }
    }

    private final ChatStore chat;
    private final UserStore users;
    private final SessionStore sessions;
    private final LiveChannelRegistry registry;
    private final Predicate<String> liveCheck;
    private final Limiter searchLimiter = new Limiter();
    private final Limiter joinLimiter = new Limiter();
    private final Limiter previewLimiter = new Limiter();
    private volatile ModerationService moderation;

    public ChatApi(ChatStore chat, UserStore users, SessionStore sessions,
                   LiveChannelRegistry registry, Predicate<String> liveCheck) {
        this.chat = chat;
        this.users = users;
        this.sessions = sessions;
        this.registry = registry;
        this.liveCheck = liveCheck;
    }

    public void setModeration(ModerationService m) {
        this.moderation = m;
    }

    public void register(HttpServer server) {
        server.createContext("/api/chat/", new HttpHandler() {
            @Override
            public void handle(HttpExchange ex) throws IOException {
                handleChat(ex);
            }
        });
        server.createContext("/api/admin/chat/", new HttpHandler() {
            @Override
            public void handle(HttpExchange ex) throws IOException {
                handleAdmin(ex);
            }
        });
        server.createContext("/join/", new HttpHandler() {
            @Override
            public void handle(HttpExchange ex) throws IOException {
                handleJoinPage(ex);
            }
        });
    }

    // ---- Routing ----

    private void handleChat(HttpExchange ex) throws IOException {
        try {
            String path = ex.getRequestURI().getPath();
            String method = ex.getRequestMethod();
            switch (path) {
                case "/api/chat/rooms":
                    if (requireMethod(ex, method, "GET")) {
                        send(ex, 200, roomsJson(signedIn(ex)));
                    }
                    break;
                case "/api/chat/top":
                    if (requireMethod(ex, method, "GET")) {
                        send(ex, 200, topJson());
                    }
                    break;
                case "/api/chat/messages":
                    if (requireMethod(ex, method, "GET")) {
                        messages(ex);
                    }
                    break;
                case "/api/chat/unread":
                    if (requireMethod(ex, method, "GET")) {
                        send(ex, 200, unreadJson(ex));
                    }
                    break;
                case "/api/chat/search":
                    if (requireMethod(ex, method, "GET")) {
                        search(ex);
                    }
                    break;
                case "/api/chat/info":
                    if (requireMethod(ex, method, "GET")) {
                        info(ex);
                    }
                    break;
                case "/api/chat/prefs":
                    prefs(ex, method);
                    break;
                case "/api/chat/post":
                    if (requireMethod(ex, method, "POST")) {
                        post(ex);
                    }
                    break;
                case "/api/chat/edit":
                    if (requireMethod(ex, method, "POST")) {
                        edit(ex);
                    }
                    break;
                case "/api/chat/react":
                    if (requireMethod(ex, method, "POST")) {
                        react(ex);
                    }
                    break;
                case "/api/chat/typing":
                    if (requireMethod(ex, method, "POST")) {
                        typing(ex);
                    }
                    break;
                case "/api/chat/read":
                    if (requireMethod(ex, method, "POST")) {
                        read(ex);
                    }
                    break;
                case "/api/chat/forward":
                    if (requireMethod(ex, method, "POST")) {
                        forward(ex);
                    }
                    break;
                case "/api/chat/pin":
                    if (requireMethod(ex, method, "POST")) {
                        pin(ex);
                    }
                    break;
                case "/api/chat/delete":
                    if (requireMethod(ex, method, "POST")) {
                        deleteMessage(ex);
                    }
                    break;
                case "/api/chat/groups/create":
                    if (requireMethod(ex, method, "POST")) {
                        createGroup(ex);
                    }
                    break;
                case "/api/chat/groups/delete":
                    if (requireMethod(ex, method, "POST")) {
                        deleteGroup(ex);
                    }
                    break;
                case "/api/chat/groups/join":
                    if (requireMethod(ex, method, "POST")) {
                        joinGroup(ex);
                    }
                    break;
                case "/api/chat/groups/invite/create":
                    if (requireMethod(ex, method, "POST")) {
                        createInvite(ex);
                    }
                    break;
                case "/api/chat/groups/invites":
                    if (requireMethod(ex, method, "GET")) {
                        listInvites(ex);
                    }
                    break;
                case "/api/chat/groups/invite/revoke":
                    if (requireMethod(ex, method, "POST")) {
                        revokeInvite(ex);
                    }
                    break;
                case "/api/chat/groups/invite/user":
                    if (requireMethod(ex, method, "POST")) {
                        inviteUser(ex);
                    }
                    break;
                case "/api/chat/groups/members":
                    if (requireMethod(ex, method, "GET")) {
                        members(ex);
                    }
                    break;
                case "/api/chat/groups/remove":
                    if (requireMethod(ex, method, "POST")) {
                        removeMember(ex);
                    }
                    break;
                case "/api/chat/groups/leave":
                    if (requireMethod(ex, method, "POST")) {
                        leaveGroup(ex);
                    }
                    break;
                case "/api/chat/groups/rename":
                    if (requireMethod(ex, method, "POST")) {
                        renameGroup(ex);
                    }
                    break;
                case "/api/chat/groups/mute":
                    if (requireMethod(ex, method, "POST")) {
                        groupMute(ex, true);
                    }
                    break;
                case "/api/chat/groups/unmute":
                    if (requireMethod(ex, method, "POST")) {
                        groupMute(ex, false);
                    }
                    break;
                case "/api/chat/groups/muted":
                    if (requireMethod(ex, method, "GET")) {
                        groupMuted(ex);
                    }
                    break;
                case "/api/chat/adminmail":
                    if (requireMethod(ex, method, "GET")) {
                        adminMail(ex);
                    }
                    break;
                case "/api/chat/adminmail/send":
                    if (requireMethod(ex, method, "POST")) {
                        adminMailReply(ex);
                    }
                    break;
                case "/api/chat/adminmail/read":
                    if (requireMethod(ex, method, "POST")) {
                        adminMailRead(ex);
                    }
                    break;
                case "/api/chat/report":
                    if (requireMethod(ex, method, "POST")) {
                        report(ex);
                    }
                    break;
                case "/api/chat/groups/suspend":
                    if (requireMethod(ex, method, "POST")) {
                        suspendMember(ex);
                    }
                    break;
                case "/api/chat/groups/banned":
                    if (requireMethod(ex, method, "GET")) {
                        banned(ex);
                    }
                    break;
                case "/api/chat/groups/reinstate":
                    if (requireMethod(ex, method, "POST")) {
                        reinstate(ex);
                    }
                    break;
                case "/api/chat/invite":
                    if (requireMethod(ex, method, "GET")) {
                        previewInvite(ex);
                    }
                    break;
                case "/api/chat/invitations":
                    if (requireMethod(ex, method, "GET")) {
                        invitations(ex);
                    }
                    break;
                case "/api/chat/invitations/accept":
                    if (requireMethod(ex, method, "POST")) {
                        answerInvitation(ex, true);
                    }
                    break;
                case "/api/chat/invitations/decline":
                    if (requireMethod(ex, method, "POST")) {
                        answerInvitation(ex, false);
                    }
                    break;
                default:
                    send(ex, 404, error("not found"));
            }
        } catch (ChatStore.ChatException e) {
            send(ex, e.status, error(e.getMessage()));
        } catch (Exception e) {
            send(ex, 500, error("server error"));
        } finally {
            ex.close();
        }
    }

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
                case "/api/admin/chat/mute": {
                    if (!requireMethod(ex, method, "POST")) {
                        break;
                    }
                    String body = readBody(ex);
                    String name = MediaAuthHttpServer.queryParam(body, "username");
                    UserStore.Info target = name == null ? null : users.info(name);
                    if (target == null) {
                        send(ex, 404, error("no such user"));
                        break;
                    }
                    if (target.role == UserStore.Role.ADMIN) {
                        send(ex, 400, error("the admin cannot be muted"));
                        break;
                    }
                    chat.mute(target.username, parseLong(MediaAuthHttpServer.queryParam(body, "hours"), 24, 0, 8760));
                    send(ex, 200, "{\"ok\":true}");
                    break;
                }
                case "/api/admin/chat/unmute": {
                    if (!requireMethod(ex, method, "POST")) {
                        break;
                    }
                    String name = MediaAuthHttpServer.queryParam(readBody(ex), "username");
                    if (name != null && chat.unmute(name)) {
                        send(ex, 200, "{\"ok\":true}");
                    } else {
                        send(ex, 404, error("that user is not muted"));
                    }
                    break;
                }
                case "/api/admin/chat/muted":
                    if (requireMethod(ex, method, "GET")) {
                        send(ex, 200, mutedJson());
                    }
                    break;
                case "/api/admin/chat/clear": {
                    if (!requireMethod(ex, method, "POST")) {
                        break;
                    }
                    String room = MediaAuthHttpServer.queryParam(readBody(ex), "room");
                    validateRoomExists(room);
                    chat.clearRoom(room);
                    send(ex, 200, "{\"ok\":true}");
                    break;
                }
                case "/api/admin/chat/groupadmins":
                    if (requireMethod(ex, method, "GET")) {
                        send(ex, 200, groupAdminsJson());
                    }
                    break;
                case "/api/admin/chat/mail/send":
                    if (requireMethod(ex, method, "POST")) {
                        superAdminMailSend(ex);
                    }
                    break;
                case "/api/admin/chat/mail/thread":
                    if (requireMethod(ex, method, "GET")) {
                        superAdminMailThread(ex);
                    }
                    break;
                case "/api/admin/chat/reports": {
                    if (!requireMethod(ex, method, "GET")) {
                        break;
                    }
                    String q = ex.getRequestURI().getRawQuery();
                    String status = MediaAuthHttpServer.queryParam(q, "status");
                    int limit = (int) parseLong(MediaAuthHttpServer.queryParam(q, "limit"), 50, 1, 200);
                    send(ex, 200, reportsJson(status == null ? "open" : status, limit));
                    break;
                }
                case "/api/admin/chat/reports/dismiss": {
                    if (!requireMethod(ex, method, "POST")) {
                        break;
                    }
                    String body = readBody(ex);
                    mod().dismiss(me.username, parseLong(MediaAuthHttpServer.queryParam(body, "id"), -1, -1, Long.MAX_VALUE),
                            MediaAuthHttpServer.queryParam(body, "note"));
                    send(ex, 200, "{\"ok\":true}");
                    break;
                }
                case "/api/admin/chat/reports/warn": {
                    if (!requireMethod(ex, method, "POST")) {
                        break;
                    }
                    String body = readBody(ex);
                    mod().warn(me.username, parseLong(MediaAuthHttpServer.queryParam(body, "id"), -1, -1, Long.MAX_VALUE),
                            MediaAuthHttpServer.queryParam(body, "text"));
                    send(ex, 200, "{\"ok\":true}");
                    break;
                }
                case "/api/admin/chat/reports/deletemessage": {
                    if (!requireMethod(ex, method, "POST")) {
                        break;
                    }
                    String body = readBody(ex);
                    mod().deleteReportedMessage(me.username,
                            parseLong(MediaAuthHttpServer.queryParam(body, "id"), -1, -1, Long.MAX_VALUE));
                    send(ex, 200, "{\"ok\":true}");
                    break;
                }
                case "/api/admin/chat/reports/suspend": {
                    if (!requireMethod(ex, method, "POST")) {
                        break;
                    }
                    String body = readBody(ex);
                    mod().suspendFromReport(me.username,
                            parseLong(MediaAuthHttpServer.queryParam(body, "id"), -1, -1, Long.MAX_VALUE),
                            parseLong(MediaAuthHttpServer.queryParam(body, "hours"), 24, 0, 8760),
                            MediaAuthHttpServer.queryParam(body, "notice"), MediaAuthHttpServer.queryParam(body, "reason"));
                    send(ex, 200, "{\"ok\":true}");
                    break;
                }
                case "/api/admin/chat/reports/deleteaccount": {
                    if (!requireMethod(ex, method, "POST")) {
                        break;
                    }
                    String body = readBody(ex);
                    int groups = mod().deleteAccountFromReport(me.username,
                            parseLong(MediaAuthHttpServer.queryParam(body, "id"), -1, -1, Long.MAX_VALUE));
                    send(ex, 200, "{\"ok\":true,\"groupsDeleted\":" + groups + "}");
                    break;
                }
                case "/api/admin/chat/accounts/suspend": {
                    if (!requireMethod(ex, method, "POST")) {
                        break;
                    }
                    String body = readBody(ex);
                    mod().suspendAccount(me.username, MediaAuthHttpServer.queryParam(body, "username"),
                            parseLong(MediaAuthHttpServer.queryParam(body, "hours"), 24, 0, 8760),
                            MediaAuthHttpServer.queryParam(body, "notice"), MediaAuthHttpServer.queryParam(body, "reason"));
                    send(ex, 200, "{\"ok\":true}");
                    break;
                }
                case "/api/admin/chat/accounts/lift": {
                    if (!requireMethod(ex, method, "POST")) {
                        break;
                    }
                    mod().lift(me.username, MediaAuthHttpServer.queryParam(readBody(ex), "username"));
                    send(ex, 200, "{\"ok\":true}");
                    break;
                }
                case "/api/admin/chat/accounts/delete": {
                    if (!requireMethod(ex, method, "POST")) {
                        break;
                    }
                    int groups = mod().deleteAccount(me.username, MediaAuthHttpServer.queryParam(readBody(ex), "username"));
                    send(ex, 200, "{\"ok\":true,\"groupsDeleted\":" + groups + "}");
                    break;
                }
                case "/api/admin/chat/suspensions":
                    if (requireMethod(ex, method, "GET")) {
                        send(ex, 200, suspensionsJson());
                    }
                    break;
                case "/api/admin/chat/audit": {
                    if (!requireMethod(ex, method, "GET")) {
                        break;
                    }
                    int limit = (int) parseLong(MediaAuthHttpServer.queryParam(ex.getRequestURI().getRawQuery(), "limit"), 30, 1, 200);
                    send(ex, 200, auditJson(limit));
                    break;
                }
                case "/api/admin/chat/groups":
                    if (requireMethod(ex, method, "GET")) {
                        StringBuilder sb = new StringBuilder("{\"groups\":[");
                        boolean first = true;
                        for (ChatStore.RoomInfo g : chat.listGroups()) {
                            if (!first) {
                                sb.append(',');
                            }
                            first = false;
                            sb.append("{\"id\":").append(quote(g.id)).append(",\"title\":").append(quote(g.title))
                                    .append(",\"owner\":").append(quote(g.owner)).append(",\"private\":").append(g.priv)
                                    .append(",\"members\":").append(g.members).append(",\"total\":").append(g.total).append('}');
                        }
                        send(ex, 200, sb.append("]}").toString());
                    }
                    break;
                default:
                    send(ex, 404, error("not found"));
            }
        } catch (ChatStore.ChatException e) {
            send(ex, e.status, error(e.getMessage()));
        } catch (Exception e) {
            send(ex, 500, error("server error"));
        } finally {
            ex.close();
        }
    }

    // ---- Reading ----

    private void messages(HttpExchange ex) throws Exception {
        String query = ex.getRequestURI().getRawQuery();
        String room = MediaAuthHttpServer.queryParam(query, "room");
        UserStore.Info me = signedIn(ex);
        validateRoom(room, me);
        long after = parseLong(MediaAuthHttpServer.queryParam(query, "after"), 0, 0, Long.MAX_VALUE);
        int limit = (int) parseLong(MediaAuthHttpServer.queryParam(query, "limit"), 50, 1, 100);
        String viewer = me == null ? null : me.username;
        List<ChatStore.Message> list = chat.messages(room, after, limit);
        if (me != null) {
            chat.touch(me.username, room);
            long max = 0;
            for (ChatStore.Message m : list) {
                max = Math.max(max, m.id);
            }
            if (max > 0) {
                chat.markDelivered(room, me.username, max);
            }
        }
        StringBuilder sb = new StringBuilder("{\"room\":").append(quote(room));
        sb.append(",\"title\":").append(quote(titleOf(room)));
        sb.append(",\"private\":").append(chat.isPrivate(room));
        sb.append(",\"lastId\":").append(chat.lastId(room));
        sb.append(",\"typing\":[");
        boolean firstTyper = true;
        for (String who : chat.typingUsers(room, viewer)) {
            if (!firstTyper) {
                sb.append(',');
            }
            firstTyper = false;
            sb.append(quote(who));
        }
        sb.append("],\"onlineCount\":").append(chat.onlineCount(room));
        sb.append(",\"online\":[");
        if (me != null) {
            boolean firstOnline = true;
            for (String who : chat.onlineUsers(room, 30)) {
                if (!firstOnline) {
                    sb.append(',');
                }
                firstOnline = false;
                sb.append(quote(who));
            }
        }
        sb.append("],\"pins\":[");
        boolean firstPin = true;
        for (ChatStore.Message p : chat.pins(room)) {
            if (!firstPin) {
                sb.append(',');
            }
            firstPin = false;
            sb.append(messageJson(p, viewer));
        }
        sb.append("],\"messages\":[");
        boolean first = true;
        for (ChatStore.Message m : list) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append(messageJson(m, viewer));
        }
        send(ex, 200, sb.append("]}").toString());
    }

    private String messageJson(ChatStore.Message m, String viewer) {
        UserStore.Info u = users.info(m.user);
        StringBuilder sb = new StringBuilder("{\"id\":").append(m.id);
        sb.append(",\"user\":").append(quote(m.user));
        sb.append(",\"role\":").append(quote(u == null ? "" : u.role.name()));
        sb.append(",\"time\":").append(m.time);
        sb.append(",\"text\":").append(quote(m.text));
        sb.append(",\"deleted\":").append(m.deleted);
        sb.append(",\"edited\":").append(m.edited);
        sb.append(",\"reactions\":[");
        boolean firstReaction = true;
        for (Map.Entry<String, List<String>> e : m.reactions.entrySet()) {
            if (!firstReaction) {
                sb.append(',');
            }
            firstReaction = false;
            boolean mine = false;
            if (viewer != null) {
                for (String who : e.getValue()) {
                    if (who.equalsIgnoreCase(viewer)) {
                        mine = true;
                        break;
                    }
                }
            }
            sb.append("{\"emoji\":").append(quote(e.getKey()));
            sb.append(",\"count\":").append(e.getValue().size());
            sb.append(",\"mine\":").append(mine).append('}');
        }
        sb.append(']');
        ChatStore.Message quoted = m.replyTo > 0 ? chat.find(m.room, m.replyTo) : null;
        if (quoted == null) {
            sb.append(",\"reply\":null");
        } else {
            sb.append(",\"reply\":{\"id\":").append(quoted.id);
            sb.append(",\"user\":").append(quote(quoted.user));
            sb.append(",\"text\":").append(quote(ChatStore.excerpt(quoted.text, 80)));
            sb.append(",\"deleted\":").append(quoted.deleted).append('}');
        }
        sb.append(",\"forwardedFrom\":").append(quote(m.forwardedFrom == null ? "" : m.forwardedFrom));
        sb.append(",\"pinned\":").append(m.pinned);
        boolean groupAdmin = ChatStore.isGroupId(m.room) && m.user.equalsIgnoreCase(chat.groupOwner(m.room))
                && (u == null || u.role != UserStore.Role.ADMIN);
        sb.append(",\"groupAdmin\":").append(groupAdmin);
        int delivered = 0;
        int read = 0;
        if (viewer != null && viewer.equalsIgnoreCase(m.user) && !m.deleted && chat.prefs(viewer)[1]) {
            ChatStore.Receipt r = chat.receipt(m.room, m.id, false);
            delivered = r.deliveredCount;
            read = r.readCount;
        }
        sb.append(",\"delivered\":").append(delivered).append(",\"read\":").append(read);
        return sb.append('}').toString();
    }

    private String titleOf(String room) {
        if (ChatStore.isGroupId(room)) {
            return chat.info(room, room).title;
        }
        LiveChannel c = registry.getChannel(room);
        String t = c == null ? "" : c.getTitle();
        return (t == null || t.isEmpty()) ? room : t;
    }

    private String roomJson(ChatStore.RoomInfo i, boolean live) {
        StringBuilder sb = new StringBuilder("{\"id\":").append(quote(i.id));
        sb.append(",\"type\":").append(quote(i.group ? "group" : "channel"));
        sb.append(",\"title\":").append(quote(i.title));
        sb.append(",\"owner\":").append(quote(i.owner == null ? "" : i.owner));
        sb.append(",\"live\":").append(live);
        sb.append(",\"lastId\":").append(i.lastId);
        sb.append(",\"lastUser\":").append(quote(i.lastUser));
        sb.append(",\"lastText\":").append(quote(i.lastText));
        sb.append(",\"lastTime\":").append(i.lastTime);
        sb.append(",\"recent\":").append(i.recent);
        sb.append(",\"total\":").append(i.total);
        sb.append(",\"online\":").append(chat.onlineCount(i.id));
        sb.append(",\"private\":").append(i.priv);
        sb.append(",\"members\":").append(i.members);
        return sb.append('}').toString();
    }

    /** The rooms this viewer may see: public groups, live channel rooms, and private groups they belong to. */
    private List<Entry> entries(String viewer) {
        List<Entry> list = new ArrayList<Entry>();
        for (ChatStore.RoomInfo g : chat.listGroups()) {
            if (g.priv && !chat.canAccess(g.id, viewer)) {
                continue;
            }
            list.add(new Entry(roomJson(g, false), g.lastTime, true, g.recent));
        }
        for (LiveChannel c : registry.getLiveChannels()) {
            String id = c.getChannelId();
            if (!liveCheck.test(id)) {
                continue;
            }
            String title = (c.getTitle() == null || c.getTitle().isEmpty()) ? id : c.getTitle();
            ChatStore.RoomInfo i = chat.info(id, title);
            list.add(new Entry(roomJson(i, true), i.lastTime, false, i.recent));
        }
        return list;
    }

    private String roomsJson(UserStore.Info me) {
        List<Entry> list = entries(me == null ? null : me.username);
        Collections.sort(list, new Comparator<Entry>() {
            @Override
            public int compare(Entry a, Entry b) {
                int c = Long.compare(b.lastTime, a.lastTime);
                if (c != 0) {
                    return c;
                }
                return Boolean.compare(b.group, a.group);
            }
        });
        StringBuilder sb = new StringBuilder("{\"rooms\":[");
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(list.get(i).json);
        }
        return sb.append("]}").toString();
    }

    /** The public room with the most messages in the last 24 hours, or {"room":null}. Private groups never appear. */
    private String topJson() {
        Entry best = null;
        for (Entry e : entries(null)) {
            if (e.recent <= 0) {
                continue;
            }
            if (best == null || e.recent > best.recent
                    || (e.recent == best.recent && e.lastTime > best.lastTime)) {
                best = e;
            }
        }
        return best == null ? "{\"room\":null}" : "{\"room\":" + best.json + "}";
    }

    /** GET /api/chat/unread?rooms=g-general:12,CHANNEL-001:0 gives the number of new messages per room. */
    private String unreadJson(HttpExchange ex) {
        String param = MediaAuthHttpServer.queryParam(ex.getRequestURI().getRawQuery(), "rooms");
        UserStore.Info me = signedIn(ex);
        String exclude = me == null ? null : me.username;
        StringBuilder sb = new StringBuilder("{\"unread\":{");
        boolean first = true;
        if (param != null) {
            String[] parts = param.split(",");
            for (int i = 0; i < parts.length && i < 30; i++) {
                int colon = parts[i].lastIndexOf(':');
                if (colon <= 0) {
                    continue;
                }
                String room = parts[i].substring(0, colon);
                if (!CHANNEL_ID.matcher(room).matches() && !ChatStore.isGroupId(room)) {
                    continue;
                }
                if (ChatStore.isGroupId(room) && !chat.canAccess(room, exclude)) {
                    continue;
                }
                long after = parseLong(parts[i].substring(colon + 1), 0, 0, Long.MAX_VALUE);
                if (!first) {
                    sb.append(',');
                }
                first = false;
                sb.append(quote(room)).append(':').append(Math.min(99, chat.countAfter(room, after, exclude)));
            }
        }
        return sb.append("}}").toString();
    }

    private String mutedJson() {
        long now = chat.now();
        StringBuilder sb = new StringBuilder("{\"muted\":[");
        boolean first = true;
        for (Map.Entry<String, Long> e : chat.mutedList().entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append("{\"username\":").append(quote(e.getKey()));
            if (e.getValue() == 0) {
                sb.append(",\"hoursLeft\":null}");
            } else {
                sb.append(",\"hoursLeft\":").append((e.getValue() - now + 3599999L) / 3600000L).append('}');
            }
        }
        return sb.append("]}").toString();
    }

    private void search(HttpExchange ex) throws Exception {
        String query = ex.getRequestURI().getRawQuery();
        String q = MediaAuthHttpServer.queryParam(query, "q");
        String room = MediaAuthHttpServer.queryParam(query, "room");
        UserStore.Info me = signedIn(ex);
        final String viewer = me == null ? null : me.username;
        if (q == null || q.trim().length() < 2 || q.length() > 60) {
            throw new ChatStore.ChatException(400, "type at least 2 characters to search");
        }
        if (room != null && !room.isEmpty()) {
            validateRoom(room, me);
        } else {
            room = null;
        }
        String ip = ex.getRemoteAddress().getAddress().getHostAddress();
        if (searchLimiter.count(ip, 60000) >= 15) {
            throw new ChatStore.ChatException(429, "too many searches - wait a moment");
        }
        searchLimiter.record(ip);
        StringBuilder sb = new StringBuilder("{\"results\":[");
        boolean first = true;
        List<ChatStore.Hit> hits = chat.search(q, room, 30, new Predicate<String>() {
            @Override
            public boolean test(String rid) {
                return chat.canAccess(rid, viewer);
            }
        });
        for (ChatStore.Hit h : hits) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append("{\"room\":").append(quote(h.room));
            sb.append(",\"roomTitle\":").append(quote(titleOf(h.room)));
            sb.append(",\"id\":").append(h.id);
            sb.append(",\"user\":").append(quote(h.user));
            sb.append(",\"time\":").append(h.time);
            sb.append(",\"text\":").append(quote(h.text)).append('}');
        }
        send(ex, 200, sb.append("]}").toString());
    }

    private void info(HttpExchange ex) throws Exception {
        UserStore.Info me = signedIn(ex);
        if (me == null) {
            send(ex, 401, error("sign in required"));
            return;
        }
        String query = ex.getRequestURI().getRawQuery();
        String room = MediaAuthHttpServer.queryParam(query, "room");
        validateRoom(room, me);
        long id = parseLong(MediaAuthHttpServer.queryParam(query, "id"), 0, 0, Long.MAX_VALUE);
        ChatStore.Message m = chat.find(room, id);
        if (m == null || m.deleted) {
            send(ex, 404, error("no such message"));
            return;
        }
        boolean admin = me.role == UserStore.Role.ADMIN;
        if (!m.user.equalsIgnoreCase(me.username) && !admin) {
            send(ex, 403, error("only the author can see who received a message"));
            return;
        }
        ChatStore.Receipt r = chat.receipt(room, id, true);
        boolean showRead = admin || chat.prefs(me.username)[1];
        StringBuilder sb = new StringBuilder("{\"id\":").append(id);
        sb.append(",\"deliveredCount\":").append(r.deliveredCount);
        sb.append(",\"readCount\":").append(showRead ? r.readCount : 0);
        sb.append(",\"delivered\":").append(names(r.delivered));
        sb.append(",\"read\":").append(names(showRead ? r.read : new ArrayList<String>()));
        send(ex, 200, sb.append('}').toString());
    }

    private static String names(List<String> list) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(quote(list.get(i)));
        }
        return sb.append(']').toString();
    }

    private void prefs(HttpExchange ex, String method) throws Exception {
        UserStore.Info me = signedIn(ex);
        if (me == null) {
            send(ex, 401, error("sign in required"));
            return;
        }
        if (method.equalsIgnoreCase("POST")) {
            String body = readBody(ex);
            boolean[] current = chat.prefs(me.username);
            String online = MediaAuthHttpServer.queryParam(body, "showOnline");
            String receipts = MediaAuthHttpServer.queryParam(body, "sendReceipts");
            chat.setPrefs(me.username, online == null ? current[0] : "true".equals(online),
                    receipts == null ? current[1] : "true".equals(receipts));
        } else if (!method.equalsIgnoreCase("GET")) {
            ex.getResponseHeaders().set("Allow", "GET, POST");
            send(ex, 405, error("method not allowed"));
            return;
        }
        boolean[] p = chat.prefs(me.username);
        send(ex, 200, "{\"showOnline\":" + p[0] + ",\"sendReceipts\":" + p[1] + "}");
    }

    // ---- Posting ----

    private void post(HttpExchange ex) throws Exception {
        UserStore.Info me = signedIn(ex);
        if (me == null) {
            send(ex, 401, error("sign in required"));
            return;
        }
        String body = readBody(ex);
        String room = MediaAuthHttpServer.queryParam(body, "room");
        validateRoom(room, me);
        long reply = parseLong(MediaAuthHttpServer.queryParam(body, "reply"), 0, 0, Long.MAX_VALUE);
        users.touchActive(me.username);
        ChatStore.Message m = chat.post(room, me.username, MediaAuthHttpServer.queryParam(body, "text"), reply);
        send(ex, 200, "{\"message\":" + messageJson(m, me.username) + "}");
    }

    private void edit(HttpExchange ex) throws Exception {
        UserStore.Info me = signedIn(ex);
        if (me == null) {
            send(ex, 401, error("sign in required"));
            return;
        }
        String body = readBody(ex);
        String room = MediaAuthHttpServer.queryParam(body, "room");
        validateRoom(room, me);
        long id = parseLong(MediaAuthHttpServer.queryParam(body, "id"), 0, 0, Long.MAX_VALUE);
        users.touchActive(me.username);
        ChatStore.Message m = chat.edit(room, id, me.username, MediaAuthHttpServer.queryParam(body, "text"));
        send(ex, 200, "{\"message\":" + messageJson(m, me.username) + "}");
    }

    private void react(HttpExchange ex) throws Exception {
        UserStore.Info me = signedIn(ex);
        if (me == null) {
            send(ex, 401, error("sign in required"));
            return;
        }
        String body = readBody(ex);
        String room = MediaAuthHttpServer.queryParam(body, "room");
        validateRoom(room, me);
        long id = parseLong(MediaAuthHttpServer.queryParam(body, "id"), 0, 0, Long.MAX_VALUE);
        boolean added = chat.react(room, id, me.username, MediaAuthHttpServer.queryParam(body, "emoji"));
        ChatStore.Message m = chat.find(room, id);
        send(ex, 200, "{\"added\":" + added + ",\"message\":" + (m == null ? "null" : messageJson(m, me.username)) + "}");
    }

    private void typing(HttpExchange ex) throws Exception {
        UserStore.Info me = signedIn(ex);
        if (me == null) {
            send(ex, 401, error("sign in required"));
            return;
        }
        String room = MediaAuthHttpServer.queryParam(readBody(ex), "room");
        validateRoom(room, me);
        chat.touch(me.username, room);
        chat.typing(room, me.username);
        send(ex, 200, "{\"ok\":true}");
    }

    /** The app reports that the user has read the room up to a message. */
    private void read(HttpExchange ex) throws Exception {
        UserStore.Info me = signedIn(ex);
        if (me == null) {
            send(ex, 401, error("sign in required"));
            return;
        }
        String body = readBody(ex);
        String room = MediaAuthHttpServer.queryParam(body, "room");
        validateRoom(room, me);
        long upTo = parseLong(MediaAuthHttpServer.queryParam(body, "upTo"), 0, 0, Long.MAX_VALUE);
        upTo = Math.min(upTo, chat.lastId(room));
        chat.touch(me.username, room);
        chat.markRead(room, me.username, upTo);
        send(ex, 200, "{\"ok\":true}");
    }

    private void forward(HttpExchange ex) throws Exception {
        UserStore.Info me = signedIn(ex);
        if (me == null) {
            send(ex, 401, error("sign in required"));
            return;
        }
        String body = readBody(ex);
        String from = MediaAuthHttpServer.queryParam(body, "fromRoom");
        String to = MediaAuthHttpServer.queryParam(body, "toRoom");
        validateRoom(from, me);
        validateRoom(to, me);
        long id = parseLong(MediaAuthHttpServer.queryParam(body, "id"), 0, 0, Long.MAX_VALUE);
        users.touchActive(me.username);
        ChatStore.Message m = chat.forward(from, id, to, me.username);
        send(ex, 200, "{\"message\":" + messageJson(m, me.username) + "}");
    }

    private boolean canPin(UserStore.Info me, String room) {
        if (me.role == UserStore.Role.ADMIN && !chat.isPrivate(room)) {
            return true;
        }
        if (ChatStore.isGroupId(room)) {
            return chat.groupOwner(room).equalsIgnoreCase(me.username);
        }
        LiveChannel c = registry.getChannel(room);
        return c != null && me.username.equalsIgnoreCase(c.getBroadcasterId());
    }

    private void pin(HttpExchange ex) throws Exception {
        UserStore.Info me = signedIn(ex);
        if (me == null) {
            send(ex, 401, error("sign in required"));
            return;
        }
        String body = readBody(ex);
        String room = MediaAuthHttpServer.queryParam(body, "room");
        validateRoom(room, me);
        if (!canPin(me, room)) {
            send(ex, 403, error("only the room owner, the broadcaster or the admin can pin messages"));
            return;
        }
        long id = parseLong(MediaAuthHttpServer.queryParam(body, "id"), 0, 0, Long.MAX_VALUE);
        chat.setPinned(room, id, !"false".equals(MediaAuthHttpServer.queryParam(body, "pinned")));
        send(ex, 200, "{\"ok\":true}");
    }

    private void deleteMessage(HttpExchange ex) throws Exception {
        UserStore.Info me = signedIn(ex);
        if (me == null) {
            send(ex, 401, error("sign in required"));
            return;
        }
        String body = readBody(ex);
        String room = MediaAuthHttpServer.queryParam(body, "room");
        validateRoom(room, me);
        long id = parseLong(MediaAuthHttpServer.queryParam(body, "id"), 0, 0, Long.MAX_VALUE);
        chat.delete(room, id, me.username, me.role == UserStore.Role.ADMIN);
        send(ex, 200, "{\"ok\":true}");
    }

    // ---- Groups: create, delete, members, invites ----

    private void createGroup(HttpExchange ex) throws Exception {
        UserStore.Info me = signedIn(ex);
        if (me == null) {
            send(ex, 401, error("sign in required"));
            return;
        }
        if (me.role == UserStore.Role.GUEST) {
            send(ex, 403, error("guests cannot create groups - create a registered account"));
            return;
        }
        String body = readBody(ex);
        String title = MediaAuthHttpServer.queryParam(body, "title");
        boolean priv = "true".equals(MediaAuthHttpServer.queryParam(body, "private"));
        ChatStore.RoomInfo g = chat.createGroup(title, me.username, me.role == UserStore.Role.ADMIN, priv);
        send(ex, 200, roomJson(g, false));
    }

    private void deleteGroup(HttpExchange ex) throws Exception {
        UserStore.Info me = signedIn(ex);
        if (me == null) {
            send(ex, 401, error("sign in required"));
            return;
        }
        String room = MediaAuthHttpServer.queryParam(readBody(ex), "room");
        if (!ChatStore.isGroupId(room)) {
            send(ex, 400, error("bad room"));
            return;
        }
        if (chat.isPrivate(room) && !chat.canAccess(room, me.username) && me.role != UserStore.Role.ADMIN) {
            throw new ChatStore.ChatException(404, "no such group");
        }
        chat.deleteGroup(room, me.username, me.role == UserStore.Role.ADMIN);
        send(ex, 200, "{\"ok\":true}");
    }

    private String groupRoom(String body, UserStore.Info me) throws ChatStore.ChatException {
        String room = MediaAuthHttpServer.queryParam(body, "room");
        if (!ChatStore.isGroupId(room)) {
            throw new ChatStore.ChatException(400, "bad room");
        }
        validateRoom(room, me);
        if (!chat.isPrivate(room)) {
            throw new ChatStore.ChatException(400, "this group is public - only private groups have members and invites");
        }
        return room;
    }

    private void createInvite(HttpExchange ex) throws Exception {
        UserStore.Info me = signedIn(ex);
        if (me == null) {
            send(ex, 401, error("sign in required"));
            return;
        }
        String body = readBody(ex);
        String room = groupRoom(body, me);
        int uses = (int) parseLong(MediaAuthHttpServer.queryParam(body, "uses"), 1, 1, 50);
        int days = (int) parseLong(MediaAuthHttpServer.queryParam(body, "days"), 7, 1, 30);
        ChatStore.Invite inv = chat.createInvite(room, me.username, uses, days);
        send(ex, 200, inviteJson(inv, hostOf(ex)));
    }

    private void listInvites(HttpExchange ex) throws Exception {
        UserStore.Info me = signedIn(ex);
        if (me == null) {
            send(ex, 401, error("sign in required"));
            return;
        }
        String room = groupRoom("room=" + java.net.URLEncoder.encode(
                String.valueOf(MediaAuthHttpServer.queryParam(ex.getRequestURI().getRawQuery(), "room")), "UTF-8"), me);
        String host = hostOf(ex);
        StringBuilder sb = new StringBuilder("{\"invites\":[");
        boolean first = true;
        for (ChatStore.Invite i : chat.listInvites(room, me.username)) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append(inviteJson(i, host));
        }
        send(ex, 200, sb.append("]}").toString());
    }

    private void revokeInvite(HttpExchange ex) throws Exception {
        UserStore.Info me = signedIn(ex);
        if (me == null) {
            send(ex, 401, error("sign in required"));
            return;
        }
        String body = readBody(ex);
        String room = groupRoom(body, me);
        chat.revokeInvite(room, MediaAuthHttpServer.queryParam(body, "code"), me.username);
        send(ex, 200, "{\"ok\":true}");
    }

    private void inviteUser(HttpExchange ex) throws Exception {
        UserStore.Info me = signedIn(ex);
        if (me == null) {
            send(ex, 401, error("sign in required"));
            return;
        }
        String body = readBody(ex);
        String room = groupRoom(body, me);
        String name = MediaAuthHttpServer.queryParam(body, "username");
        UserStore.Info target = name == null ? null : users.info(name);
        if (target == null || target.disabled) {
            throw new ChatStore.ChatException(404, "no such user");
        }
        chat.inviteUser(room, me.username, target.username);
        send(ex, 200, "{\"ok\":true}");
    }

    private void members(HttpExchange ex) throws Exception {
        UserStore.Info me = signedIn(ex);
        if (me == null) {
            send(ex, 401, error("sign in required"));
            return;
        }
        String room = groupRoom("room=" + java.net.URLEncoder.encode(
                String.valueOf(MediaAuthHttpServer.queryParam(ex.getRequestURI().getRawQuery(), "room")), "UTF-8"), me);
        StringBuilder sb = new StringBuilder("{\"room\":").append(quote(room));
        sb.append(",\"owner\":").append(quote(chat.groupOwner(room))).append(",\"members\":[");
        boolean first = true;
        for (ChatStore.Member m : chat.members(room)) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            UserStore.Info u = users.info(m.name);
            sb.append("{\"name\":").append(quote(m.name));
            sb.append(",\"owner\":").append(m.owner);
            sb.append(",\"label\":").append(quote(m.owner ? "Group admin" : ""));
            sb.append(",\"role\":").append(quote(u == null ? "" : u.role.name()));
            sb.append(",\"online\":").append(chat.isOnline(m.name));
            sb.append(",\"joined\":").append(m.joined).append('}');
        }
        send(ex, 200, sb.append("]}").toString());
    }

    private void removeMember(HttpExchange ex) throws Exception {
        UserStore.Info me = signedIn(ex);
        if (me == null) {
            send(ex, 401, error("sign in required"));
            return;
        }
        String body = readBody(ex);
        String room = groupRoom(body, me);
        String name = MediaAuthHttpServer.queryParam(body, "username");
        if (name == null) {
            throw new ChatStore.ChatException(400, "who should be removed?");
        }
        chat.removeMember(room, name, me.username, false);
        send(ex, 200, "{\"ok\":true}");
    }

    private void leaveGroup(HttpExchange ex) throws Exception {
        UserStore.Info me = signedIn(ex);
        if (me == null) {
            send(ex, 401, error("sign in required"));
            return;
        }
        String room = MediaAuthHttpServer.queryParam(readBody(ex), "room");
        if (!ChatStore.isGroupId(room) || !chat.hasGroup(room) || !chat.isPrivate(room)) {
            throw new ChatStore.ChatException(404, "no such group");
        }
        chat.leave(room, me.username);
        send(ex, 200, "{\"ok\":true}");
    }

    private void joinGroup(HttpExchange ex) throws Exception {
        UserStore.Info me = signedIn(ex);
        if (me == null) {
            send(ex, 401, error("sign in required"));
            return;
        }
        String ip = ex.getRemoteAddress().getAddress().getHostAddress();
        String uk = "u|" + me.username.toLowerCase(java.util.Locale.ROOT);
        if (joinLimiter.count("i|" + ip, 60000) >= 20 || joinLimiter.count(uk, 60000) >= 10) {
            throw new ChatStore.ChatException(429, "too many attempts - wait a moment");
        }
        joinLimiter.record("i|" + ip);
        joinLimiter.record(uk);
        String code = MediaAuthHttpServer.queryParam(readBody(ex), "code");
        String room = chat.joinByCode(code, me.username);
        users.touchActive(me.username);
        send(ex, 200, "{\"room\":" + quote(room) + ",\"title\":" + quote(titleOf(room)) + ",\"private\":true}");
    }

    private void previewInvite(HttpExchange ex) throws Exception {
        String ip = ex.getRemoteAddress().getAddress().getHostAddress();
        if (previewLimiter.count(ip, 60000) >= 30) {
            throw new ChatStore.ChatException(429, "too many attempts - wait a moment");
        }
        previewLimiter.record(ip);
        ChatStore.InvitePreview p = chat.previewInvite(
                MediaAuthHttpServer.queryParam(ex.getRequestURI().getRawQuery(), "code"));
        if (p == null) {
            throw new ChatStore.ChatException(404, "this invite is not valid any more");
        }
        send(ex, 200, "{\"valid\":true,\"title\":" + quote(p.title) + ",\"owner\":" + quote(p.owner)
                + ",\"members\":" + p.members + ",\"expires\":" + p.expires + "}");
    }

    private void invitations(HttpExchange ex) throws Exception {
        UserStore.Info me = signedIn(ex);
        if (me == null) {
            send(ex, 401, error("sign in required"));
            return;
        }
        StringBuilder sb = new StringBuilder("{\"invitations\":[");
        boolean first = true;
        for (ChatStore.Invitation i : chat.invitationsFor(me.username)) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append("{\"room\":").append(quote(i.room)).append(",\"title\":").append(quote(i.title))
                    .append(",\"from\":").append(quote(i.from)).append(",\"time\":").append(i.time).append('}');
        }
        send(ex, 200, sb.append("]}").toString());
    }

    private void answerInvitation(HttpExchange ex, boolean accept) throws Exception {
        UserStore.Info me = signedIn(ex);
        if (me == null) {
            send(ex, 401, error("sign in required"));
            return;
        }
        String room = MediaAuthHttpServer.queryParam(readBody(ex), "room");
        if (!ChatStore.isGroupId(room)) {
            throw new ChatStore.ChatException(400, "bad room");
        }
        if (accept) {
            chat.acceptInvitation(room, me.username);
            send(ex, 200, "{\"room\":" + quote(room) + ",\"title\":" + quote(titleOf(room)) + ",\"private\":true}");
        } else {
            chat.declineInvitation(room, me.username);
            send(ex, 200, "{\"ok\":true}");
        }
    }

    private String inviteJson(ChatStore.Invite i, String host) {
        String raw = i.code.replace("-", "");
        return "{\"code\":" + quote(i.code) + ",\"link\":" + quote("http://" + host + "/join/" + raw)
                + ",\"deepLink\":" + quote("vstream://join?code=" + raw) + ",\"expires\":" + i.expires
                + ",\"maxUses\":" + i.maxUses + ",\"uses\":" + i.uses + "}";
    }

    private static String hostOf(HttpExchange ex) {
        String host = ex.getRequestHeaders().getFirst("Host");
        return (host != null && host.matches("[A-Za-z0-9.:\\-\\[\\]]{1,100}")) ? host : "localhost";
    }

    // ---- Group admin tools and private mail ----

    private void renameGroup(HttpExchange ex) throws Exception {
        UserStore.Info me = signedIn(ex);
        if (me == null) {
            send(ex, 401, error("sign in required"));
            return;
        }
        String body = readBody(ex);
        String room = MediaAuthHttpServer.queryParam(body, "room");
        if (!ChatStore.isGroupId(room) || !chat.hasGroup(room)) {
            throw new ChatStore.ChatException(404, "no such group");
        }
        boolean superAdmin = me.role == UserStore.Role.ADMIN;
        if (chat.isPrivate(room) && !chat.canAccess(room, me.username) && !superAdmin) {
            throw new ChatStore.ChatException(404, "no such group");
        }
        chat.renameGroup(room, MediaAuthHttpServer.queryParam(body, "title"), me.username, superAdmin);
        send(ex, 200, "{\"room\":" + quote(room) + ",\"title\":" + quote(titleOf(room)) + "}");
    }

    /** Mute (mute = true) or unmute a person inside a group. Group admin only. */
    private void groupMute(HttpExchange ex, boolean mute) throws Exception {
        UserStore.Info me = signedIn(ex);
        if (me == null) {
            send(ex, 401, error("sign in required"));
            return;
        }
        String body = readBody(ex);
        String room = MediaAuthHttpServer.queryParam(body, "room");
        validateRoom(room, me);
        if (!ChatStore.isGroupId(room)) {
            throw new ChatStore.ChatException(400, "only groups have a group admin");
        }
        String name = MediaAuthHttpServer.queryParam(body, "username");
        UserStore.Info target = name == null ? null : users.info(name);
        if (target == null) {
            throw new ChatStore.ChatException(404, "no such user");
        }
        if (target.role == UserStore.Role.ADMIN) {
            throw new ChatStore.ChatException(400, "the super-admin cannot be muted by a group admin");
        }
        if (mute) {
            chat.muteInGroup(room, target.username, me.username,
                    parseLong(MediaAuthHttpServer.queryParam(body, "hours"), 24, 0, 8760));
        } else {
            chat.unmuteInGroup(room, target.username, me.username);
        }
        send(ex, 200, "{\"ok\":true}");
    }

    private void groupMuted(HttpExchange ex) throws Exception {
        UserStore.Info me = signedIn(ex);
        if (me == null) {
            send(ex, 401, error("sign in required"));
            return;
        }
        String room = MediaAuthHttpServer.queryParam(ex.getRequestURI().getRawQuery(), "room");
        validateRoom(room, me);
        if (!ChatStore.isGroupId(room)) {
            throw new ChatStore.ChatException(400, "only groups have a group admin");
        }
        long now = chat.now();
        StringBuilder sb = new StringBuilder("{\"muted\":[");
        boolean first = true;
        for (Map.Entry<String, Long> e : chat.groupMutedList(room, me.username).entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append("{\"username\":").append(quote(e.getKey()));
            if (e.getValue() == 0) {
                sb.append(",\"hoursLeft\":null}");
            } else {
                sb.append(",\"hoursLeft\":").append((e.getValue() - now + 3599999L) / 3600000L).append('}');
            }
        }
        send(ex, 200, sb.append("]}").toString());
    }

    private static String mailJson(List<ChatStore.Mail> list) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < list.size(); i++) {
            ChatStore.Mail m = list.get(i);
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"id\":").append(m.id).append(",\"fromAdmin\":").append(m.fromAdmin)
                    .append(",\"time\":").append(m.time).append(",\"text\":").append(quote(m.text))
                    .append(",\"read\":").append(m.read).append('}');
        }
        return sb.append(']').toString();
    }

    /** A group admin reads the private messages from the super-admin. */
    private void adminMail(HttpExchange ex) throws Exception {
        UserStore.Info me = signedIn(ex);
        if (me == null) {
            send(ex, 401, error("sign in required"));
            return;
        }
        if (me.role == UserStore.Role.ADMIN) {
            throw new ChatStore.ChatException(400, "the super-admin uses the admin page for these messages");
        }
        send(ex, 200, "{\"unread\":" + chat.mailUnread(me.username, false) + ",\"messages\":"
                + mailJson(chat.mailThread(me.username, false)) + "}");
    }

    private void adminMailReply(HttpExchange ex) throws Exception {
        UserStore.Info me = signedIn(ex);
        if (me == null) {
            send(ex, 401, error("sign in required"));
            return;
        }
        if (me.role == UserStore.Role.ADMIN) {
            throw new ChatStore.ChatException(400, "the super-admin uses the admin page for these messages");
        }
        ChatStore.Mail m = chat.mailFromUser(me.username, MediaAuthHttpServer.queryParam(readBody(ex), "text"));
        send(ex, 200, "{\"messages\":" + mailJson(java.util.Collections.singletonList(m)) + "}");
    }

    private void adminMailRead(HttpExchange ex) throws Exception {
        UserStore.Info me = signedIn(ex);
        if (me == null) {
            send(ex, 401, error("sign in required"));
            return;
        }
        readBody(ex);
        chat.markMailRead(me.username, false);
        send(ex, 200, "{\"ok\":true}");
    }

    /** The super-admin's list of group admins: names, labels and the groups they run (no members, no messages). */
    private String groupAdminsJson() {
        StringBuilder sb = new StringBuilder("{\"admins\":[");
        boolean first = true;
        for (ChatStore.GroupAdminInfo a : chat.groupAdmins()) {
            UserStore.Info u = users.info(a.name);
            if (u == null || u.role == UserStore.Role.ADMIN) {
                continue;
            }
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append("{\"name\":").append(quote(a.name));
            sb.append(",\"label\":\"Group admin\"");
            sb.append(",\"role\":").append(quote(u.role.name()));
            sb.append(",\"online\":").append(chat.isOnline(a.name));
            sb.append(",\"unreadMail\":").append(chat.mailUnread(a.name, true));
            sb.append(",\"groups\":[");
            boolean firstGroup = true;
            for (ChatStore.RoomInfo g : a.groups) {
                if (!firstGroup) {
                    sb.append(',');
                }
                firstGroup = false;
                sb.append("{\"id\":").append(quote(g.id)).append(",\"title\":").append(quote(g.title))
                        .append(",\"private\":").append(g.priv).append(",\"members\":").append(g.members)
                        .append(",\"messages\":").append(g.total).append('}');
            }
            sb.append("]}");
        }
        return sb.append("]}").toString();
    }

    private void superAdminMailSend(HttpExchange ex) throws Exception {
        String body = readBody(ex);
        String name = MediaAuthHttpServer.queryParam(body, "username");
        UserStore.Info target = name == null ? null : users.info(name);
        if (target == null || target.disabled) {
            throw new ChatStore.ChatException(404, "no such user");
        }
        if (target.role == UserStore.Role.ADMIN || !chat.isGroupAdmin(target.username)) {
            throw new ChatStore.ChatException(400, "that person is not a group admin");
        }
        chat.mailFromAdmin(target.username, MediaAuthHttpServer.queryParam(body, "text"));
        send(ex, 200, "{\"ok\":true}");
    }

    private void superAdminMailThread(HttpExchange ex) throws Exception {
        String name = MediaAuthHttpServer.queryParam(ex.getRequestURI().getRawQuery(), "username");
        UserStore.Info target = name == null ? null : users.info(name);
        if (target == null) {
            throw new ChatStore.ChatException(404, "no such user");
        }
        List<ChatStore.Mail> list = chat.mailThread(target.username, true);
        chat.markMailRead(target.username, true);
        send(ex, 200, "{\"username\":" + quote(target.username) + ",\"messages\":" + mailJson(list) + "}");
    }

    // ---- Reports, suspensions and bans ----

    private ModerationService mod() throws ChatStore.ChatException {
        ModerationService m = moderation;
        if (m == null) {
            throw new ChatStore.ChatException(503, "moderation is not enabled on this server");
        }
        return m;
    }

    /** Any signed-in person reports a message, a person or a group they can see. */
    private void report(HttpExchange ex) throws Exception {
        UserStore.Info me = signedIn(ex);
        if (me == null) {
            send(ex, 401, error("sign in required"));
            return;
        }
        String body = readBody(ex);
        String type = MediaAuthHttpServer.queryParam(body, "type");
        String room = MediaAuthHttpServer.queryParam(body, "room");
        validateRoom(room, me);
        long id = parseLong(MediaAuthHttpServer.queryParam(body, "id"), 0, 0, Long.MAX_VALUE);
        long reportId = mod().submitReport(me.username, type == null ? "message" : type, room, id,
                MediaAuthHttpServer.queryParam(body, "username"), MediaAuthHttpServer.queryParam(body, "reason"),
                MediaAuthHttpServer.queryParam(body, "note"));
        send(ex, 200, "{\"ok\":true,\"id\":" + reportId + "}");
    }

    private void suspendMember(HttpExchange ex) throws Exception {
        UserStore.Info me = signedIn(ex);
        if (me == null) {
            send(ex, 401, error("sign in required"));
            return;
        }
        String body = readBody(ex);
        String room = groupRoom(body, me);
        String name = MediaAuthHttpServer.queryParam(body, "username");
        UserStore.Info target = name == null ? null : users.info(name);
        if (target == null) {
            throw new ChatStore.ChatException(404, "no such user");
        }
        chat.suspendMember(room, target.username, me.username,
                parseLong(MediaAuthHttpServer.queryParam(body, "hours"), 24, 0, 8760));
        send(ex, 200, "{\"ok\":true}");
    }

    private void banned(HttpExchange ex) throws Exception {
        UserStore.Info me = signedIn(ex);
        if (me == null) {
            send(ex, 401, error("sign in required"));
            return;
        }
        String room = groupRoom("room=" + java.net.URLEncoder.encode(
                String.valueOf(MediaAuthHttpServer.queryParam(ex.getRequestURI().getRawQuery(), "room")), "UTF-8"), me);
        long now = chat.now();
        StringBuilder sb = new StringBuilder("{\"banned\":[");
        boolean first = true;
        for (ChatStore.BanInfo b : chat.bannedList(room, me.username)) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            UserStore.Info u = users.info(b.key);
            sb.append("{\"username\":").append(quote(u == null ? b.key : u.username));
            sb.append(",\"kind\":").append(quote(b.suspended ? "suspended" : "removed"));
            if (b.suspended && b.until != 0) {
                sb.append(",\"hoursLeft\":").append(Math.max(1, (b.until - now + 3599999L) / 3600000L)).append('}');
            } else {
                sb.append(",\"hoursLeft\":null}");
            }
        }
        send(ex, 200, sb.append("]}").toString());
    }

    private void reinstate(HttpExchange ex) throws Exception {
        UserStore.Info me = signedIn(ex);
        if (me == null) {
            send(ex, 401, error("sign in required"));
            return;
        }
        String body = readBody(ex);
        String room = groupRoom(body, me);
        String name = MediaAuthHttpServer.queryParam(body, "username");
        UserStore.Info target = name == null ? null : users.info(name);
        if (target == null || target.disabled) {
            throw new ChatStore.ChatException(404, "no such user");
        }
        chat.reinstate(room, target.username, me.username);
        send(ex, 200, "{\"ok\":true}");
    }

    private String roomTitleSafe(String room) {
        if (room == null || room.isEmpty()) {
            return "";
        }
        if (ChatStore.isGroupId(room)) {
            return chat.hasGroup(room) ? chat.info(room, room).title : room + " (deleted)";
        }
        return titleOf(room);
    }

    private String reportsJson(String status, int limit) throws ChatStore.ChatException {
        ModerationService m = mod();
        StringBuilder sb = new StringBuilder("{\"reports\":[");
        boolean first = true;
        for (ModerationService.Report r : m.list(status, limit)) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            UserStore.Info t = users.info(r.target);
            sb.append("{\"id\":").append(r.id).append(",\"time\":").append(r.time);
            sb.append(",\"reporter\":").append(quote(r.reporter));
            sb.append(",\"type\":").append(quote(r.type));
            sb.append(",\"room\":").append(quote(r.room));
            sb.append(",\"roomTitle\":").append(quote(roomTitleSafe(r.room)));
            sb.append(",\"private\":").append(chat.isPrivate(r.room));
            sb.append(",\"owner\":").append(quote(chat.groupOwner(r.room)));
            sb.append(",\"target\":").append(quote(r.target));
            sb.append(",\"targetExists\":").append(t != null);
            sb.append(",\"targetRole\":").append(quote(t == null ? "" : t.role.name()));
            sb.append(",\"messageId\":").append(r.messageId);
            sb.append(",\"reason\":").append(quote(r.reason));
            sb.append(",\"note\":").append(quote(r.note));
            sb.append(",\"status\":").append(quote(r.status));
            sb.append(",\"actions\":").append(quote(r.actions));
            sb.append(",\"handledBy\":").append(quote(r.handledBy));
            sb.append(",\"handledTime\":").append(r.handledTime);
            sb.append(",\"context\":[");
            boolean firstLine = true;
            for (ModerationService.Line l : r.context) {
                if (!firstLine) {
                    sb.append(',');
                }
                firstLine = false;
                sb.append("{\"id\":").append(l.id).append(",\"user\":").append(quote(l.user))
                        .append(",\"time\":").append(l.time).append(",\"text\":").append(quote(l.text)).append('}');
            }
            sb.append("]}");
        }
        return sb.append("]}").toString();
    }

    private String suspensionsJson() throws ChatStore.ChatException {
        ModerationService m = mod();
        long now = chat.now();
        StringBuilder sb = new StringBuilder("{\"suspensions\":[");
        boolean first = true;
        for (ModerationService.Suspension s : m.suspensions()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append("{\"username\":").append(quote(s.username));
            sb.append(",\"since\":").append(s.since).append(",\"until\":").append(s.until);
            sb.append(",\"hoursLeft\":").append(s.until == 0 ? "null" : String.valueOf(Math.max(1, (s.until - now + 3599999L) / 3600000L)));
            sb.append(",\"by\":").append(quote(s.by));
            sb.append(",\"notice\":").append(quote(s.notice));
            sb.append(",\"reason\":").append(quote(s.reason)).append('}');
        }
        return sb.append("]}").toString();
    }

    private String auditJson(int limit) throws ChatStore.ChatException {
        StringBuilder sb = new StringBuilder("{\"audit\":[");
        boolean first = true;
        for (ModerationService.Audit a : mod().audit(limit)) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append("{\"time\":").append(a.time).append(",\"actor\":").append(quote(a.actor))
                    .append(",\"action\":").append(quote(a.action)).append(",\"target\":").append(quote(a.target))
                    .append(",\"detail\":").append(quote(a.detail)).append('}');
        }
        return sb.append("]}").toString();
    }

    // ---- The web page behind an invite link ----

    private void handleJoinPage(HttpExchange ex) throws IOException {
        try {
            if (!ex.getRequestMethod().equalsIgnoreCase("GET")) {
                ex.getResponseHeaders().set("Allow", "GET");
                sendHtml(ex, 405, page("Not allowed", "<p>This page can only be opened.</p>"));
                return;
            }
            String path = ex.getRequestURI().getPath();
            String raw = path.length() > 6 ? path.substring(6) : "";
            String ip = ex.getRemoteAddress().getAddress().getHostAddress();
            if (previewLimiter.count(ip, 60000) >= 30) {
                sendHtml(ex, 429, page("Slow down", "<p>Too many attempts. Please wait a minute.</p>"));
                return;
            }
            previewLimiter.record(ip);
            ChatStore.InvitePreview p = raw.matches("[A-Za-z0-9\\-]{6,24}") ? chat.previewInvite(raw) : null;
            if (p == null) {
                sendHtml(ex, 404, page("Invite not valid",
                        "<p>This invite is no longer valid. Ask the person who invited you for a new one.</p>"));
                return;
            }
            String code = p.code.replace("-", "");
            String body = "<p><b>" + html(p.title) + "</b></p>"
                    + "<p>Invited by <b>" + html(p.owner) + "</b> &middot; " + p.members + " member"
                    + (p.members == 1 ? "" : "s") + "</p>"
                    + "<p>Your invite code:<br><span class=\"code\">" + html(p.code) + "</span></p>"
                    + "<p><a class=\"btn\" href=\"vstream://join?code=" + html(code) + "\">Open in Memoryq</a></p>"
                    + "<p class=\"small\">No app yet? Install Memoryq, then open Chat and choose &quot;Join with a code&quot;.</p>";
            sendHtml(ex, 200, page("You are invited to a Memoryq group", body));
        } catch (Exception e) {
            sendHtml(ex, 500, page("Error", "<p>Something went wrong.</p>"));
        } finally {
            ex.close();
        }
    }

    private static String page(String title, String body) {
        return "<!doctype html><html><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>" + html(title) + "</title>"
                + "<style>body{font-family:sans-serif;max-width:480px;margin:24px auto;padding:0 16px;line-height:1.5}"
                + ".code{font-size:28px;font-weight:bold;letter-spacing:2px}"
                + ".btn{display:inline-block;background:#1976D2;color:#fff;padding:12px 18px;border-radius:8px;text-decoration:none}"
                + ".small{color:#555;font-size:14px}</style></head><body><h2>" + html(title) + "</h2>" + body + "</body></html>";
    }

    private static String html(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '&': sb.append("&amp;"); break;
                case '<': sb.append("&lt;"); break;
                case '>': sb.append("&gt;"); break;
                case '"': sb.append("&quot;"); break;
                case '\'': sb.append("&#39;"); break;
                default: sb.append(ch);
            }
        }
        return sb.toString();
    }

    private static void sendHtml(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        ex.getResponseHeaders().set("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'");
        ex.sendResponseHeaders(status, bytes.length);
        OutputStream os = ex.getResponseBody();
        os.write(bytes);
        os.close();
    }

    // ---- Helpers ----

    /** The room exists. Says nothing about who may see it. */
    private void validateRoomExists(String room) throws ChatStore.ChatException {
        if (room == null) {
            throw new ChatStore.ChatException(400, "bad room");
        }
        if (CHANNEL_ID.matcher(room).matches()) {
            if (registry.getChannel(room) == null) {
                throw new ChatStore.ChatException(404, "no such channel");
            }
        } else if (ChatStore.isGroupId(room)) {
            if (!chat.hasGroup(room)) {
                throw new ChatStore.ChatException(404, "no such room");
            }
        } else {
            throw new ChatStore.ChatException(400, "bad room");
        }
    }

    /** The room exists and this person may see it. A private group looks like it does not exist to outsiders. */
    private void validateRoom(String room, UserStore.Info me) throws ChatStore.ChatException {
        validateRoomExists(room);
        if (ChatStore.isGroupId(room) && !chat.canAccess(room, me == null ? null : me.username)) {
            throw new ChatStore.ChatException(404, "no such room");
        }
    }

    private UserStore.Info signedIn(HttpExchange ex) {
        SessionStore.Session s = sessions.get(bearerToken(ex));
        UserStore.Info info = s == null ? null : users.info(s.username);
        return (info == null || info.disabled) ? null : info;
    }

    private static String bearerToken(HttpExchange ex) {
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        if (auth == null || !auth.startsWith("Bearer ")) {
            return null;
        }
        return auth.substring(7).trim();
    }

    private static long parseLong(String text, long fallback, long min, long max) {
        if (text == null) {
            return fallback;
        }
        try {
            long v = Long.parseLong(text.trim());
            return Math.max(min, Math.min(max, v));
        } catch (NumberFormatException e) {
            return fallback;
        }
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