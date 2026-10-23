package com.vstream.Vidiserver;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Call endpoints (all need a sign-in except /features). Off until the super-admin turns the add-on on.
 * A call starts with one person; people in the call can add others.
 *   GET  /api/calls/features  /poll  /state?id=  /history  /prefs  /blocked
 *   POST /api/calls/start  /add  /accept  /decline  /leave  /end  /history/seen  /prefs  /block  /unblock
 *   /api/admin/calls/status|enable|active|end   (super-admin only)
 */
public final class CallApi {

    private static final int MAX_BODY = 4 * 1024;
    private static final int WEBRTC_PORT = Integer.getInteger("vstream.webrtcPort", 8889);
    /** The body of the request being handled on this thread (never shared between requests). */
    private static final ThreadLocal<String> REQUEST_BODY = new ThreadLocal<String>();

    private final CallService calls;
    private final ChatStore chat;
    private final UserStore users;
    private final SessionStore sessions;

    public CallApi(CallService calls, ChatStore chat, UserStore users, SessionStore sessions) {
        this.calls = calls;
        this.chat = chat;
        this.users = users;
        this.sessions = sessions;
    }

    public void register(HttpServer server) {
        server.createContext("/api/calls/", new HttpHandler() {
            @Override
            public void handle(HttpExchange ex) throws IOException {
                handleCalls(ex);
            }
        });
        server.createContext("/api/admin/calls/", new HttpHandler() {
            @Override
            public void handle(HttpExchange ex) throws IOException {
                handleAdmin(ex);
            }
        });
    }

    // ---- People ----

    private void handleCalls(HttpExchange ex) throws IOException {
        REQUEST_BODY.remove();
        try {
            String path = ex.getRequestURI().getPath();
            String method = ex.getRequestMethod();
            if (path.equals("/api/calls/features")) {
                if (requireMethod(ex, method, "GET")) {
                    send(ex, 200, "{\"calls\":" + calls.enabled() + "}");
                }
                return;
            }
            calls.requireEnabled();
            UserStore.Info me = signedIn(ex);
            if (me == null) {
                send(ex, 401, error("sign in required"));
                return;
            }
            switch (path) {
                case "/api/calls/poll":
                    if (requireMethod(ex, method, "GET")) {
                        poll(ex, me);
                    }
                    break;
                case "/api/calls/start":
                    if (requireMethod(ex, method, "POST")) {
                        start(ex, me);
                    }
                    break;
                case "/api/calls/add":
                    if (requireMethod(ex, method, "POST")) {
                        addPeople(ex, me);
                    }
                    break;
                case "/api/calls/accept":
                    if (requireMethod(ex, method, "POST")) {
                        send(ex, 200, withMedia(ex, me, calls.accept(me.username, param(ex, "id")), true));
                    }
                    break;
                case "/api/calls/decline":
                    if (requireMethod(ex, method, "POST")) {
                        calls.decline(me.username, param(ex, "id"));
                        send(ex, 200, "{\"ok\":true}");
                    }
                    break;
                case "/api/calls/leave":
                    if (requireMethod(ex, method, "POST")) {
                        calls.leave(me.username, param(ex, "id"));
                        send(ex, 200, "{\"ok\":true}");
                    }
                    break;
                case "/api/calls/end":
                    if (requireMethod(ex, method, "POST")) {
                        calls.end(me.username, param(ex, "id"));
                        send(ex, 200, "{\"ok\":true}");
                    }
                    break;
                case "/api/calls/state":
                    if (requireMethod(ex, method, "GET")) {
                        String id = MediaAuthHttpServer.queryParam(ex.getRequestURI().getRawQuery(), "id");
                        send(ex, 200, withMedia(ex, me, calls.state(me.username, id), false));
                    }
                    break;
                case "/api/calls/history":
                    if (requireMethod(ex, method, "GET")) {
                        history(ex, me);
                    }
                    break;
                case "/api/calls/history/seen":
                    if (requireMethod(ex, method, "POST")) {
                        calls.markMissedSeen(me.username);
                        send(ex, 200, "{\"ok\":true}");
                    }
                    break;
                case "/api/calls/prefs":
                    prefs(ex, me, method);
                    break;
                case "/api/calls/block":
                    if (requireMethod(ex, method, "POST")) {
                        calls.block(me.username, param(ex, "username"));
                        send(ex, 200, "{\"ok\":true}");
                    }
                    break;
                case "/api/calls/unblock":
                    if (requireMethod(ex, method, "POST")) {
                        calls.unblock(me.username, param(ex, "username"));
                        send(ex, 200, "{\"ok\":true}");
                    }
                    break;
                case "/api/calls/blocked":
                    if (requireMethod(ex, method, "GET")) {
                        StringBuilder sb = new StringBuilder("{\"blocked\":[");
                        boolean first = true;
                        for (String n : calls.blockedBy(me.username)) {
                            if (!first) {
                                sb.append(',');
                            }
                            first = false;
                            sb.append(quote(n));
                        }
                        send(ex, 200, sb.append("]}").toString());
                    }
                    break;
                default:
                    send(ex, 404, error("not found"));
            }
        } catch (CallService.CallException e) {
            send(ex, e.status, error(e.getMessage()));
        } catch (Exception e) {
            send(ex, 500, error("server error"));
        } finally {
            REQUEST_BODY.remove();
            ex.close();
        }
    }

    /** Reads one form field from a POST body. */
    private String param(HttpExchange ex, String name) throws IOException {
        String cached = REQUEST_BODY.get();
        if (cached == null) {
            cached = readBody(ex);
            REQUEST_BODY.set(cached);
        }
        return MediaAuthHttpServer.queryParam(cached, name);
    }

    private static List<String> names(String csv) {
        List<String> out = new ArrayList<String>();
        if (csv != null) {
            out.addAll(Arrays.asList(csv.split(",")));
        }
        return out;
    }

    private void poll(HttpExchange ex, UserStore.Info me) throws Exception {
        CallService.Snapshot s = calls.poll(me.username);
        StringBuilder sb = new StringBuilder("{\"incoming\":[");
        boolean first = true;
        for (CallService.View v : s.ringing) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append(viewJson(v));
        }
        sb.append("],\"current\":").append(s.current == null ? "null" : viewJson(s.current));
        sb.append(",\"missed\":").append(s.missed).append('}');
        send(ex, 200, sb.toString());
    }

    private void start(HttpExchange ex, UserStore.Info me) throws Exception {
        String body = readBody(ex);
        REQUEST_BODY.set(body);
        if ("true".equals(MediaAuthHttpServer.queryParam(body, "group"))) {
            throw new CallService.CallException(400, "start a call with one person, then add people to it");
        }
        boolean video = !"false".equals(MediaAuthHttpServer.queryParam(body, "video"));
        String room = MediaAuthHttpServer.queryParam(body, "room");
        CallService.StartResult r = calls.start(me.username, video, room, names(MediaAuthHttpServer.queryParam(body, "to")));
        String shared = withMedia(ex, me, r.call, true);
        send(ex, 200, shared.substring(0, shared.length() - 1) + ",\"skipped\":[]}");
    }

    private void addPeople(HttpExchange ex, UserStore.Info me) throws Exception {
        String body = readBody(ex);
        REQUEST_BODY.set(body);
        CallService.AddResult r = calls.add(me.username, MediaAuthHttpServer.queryParam(body, "id"),
                names(MediaAuthHttpServer.queryParam(body, "to")));
        StringBuilder sb = new StringBuilder();
        String shared = withMedia(ex, me, r.call, true);
        sb.append(shared, 0, shared.length() - 1);
        sb.append(",\"added\":[");
        for (int i = 0; i < r.added.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(quote(r.added.get(i)));
        }
        sb.append("],\"skipped\":[");
        boolean first = true;
        for (CallService.Skipped s : r.skipped) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append("{\"name\":").append(quote(s.name)).append(",\"reason\":").append(quote(s.reason)).append('}');
        }
        send(ex, 200, sb.append("]}").toString());
    }

    private void history(HttpExchange ex, UserStore.Info me) throws Exception {
        int limit = (int) parseLong(MediaAuthHttpServer.queryParam(ex.getRequestURI().getRawQuery(), "limit"), 50, 1, 100);
        StringBuilder sb = new StringBuilder("{\"missed\":" + calls.poll(me.username).missed + ",\"history\":[");
        boolean first = true;
        for (CallService.HistoryEntry h : calls.history(me.username, limit)) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append("{\"id\":").append(quote(h.id)).append(",\"video\":").append(h.video)
                    .append(",\"group\":").append(h.group).append(",\"outgoing\":").append(h.outgoing)
                    .append(",\"peer\":").append(quote(h.peer)).append(",\"room\":").append(quote(h.room == null ? "" : h.room))
                    .append(",\"roomTitle\":").append(quote(roomTitle(h.room)))
                    .append(",\"outcome\":").append(quote(h.outcome)).append(",\"time\":").append(h.time)
                    .append(",\"duration\":").append(h.durationMs / 1000).append(",\"people\":[");
            for (int i = 0; i < h.people.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append(quote(h.people.get(i)));
            }
            sb.append("]}");
        }
        send(ex, 200, sb.append("]}").toString());
    }

    private void prefs(HttpExchange ex, UserStore.Info me, String method) throws Exception {
        if (method.equalsIgnoreCase("POST")) {
            String dnd = param(ex, "dnd");
            if (dnd != null) {
                calls.setDnd(me.username, "true".equals(dnd));
            }
        } else if (!method.equalsIgnoreCase("GET")) {
            ex.getResponseHeaders().set("Allow", "GET, POST");
            send(ex, 405, error("method not allowed"));
            return;
        }
        send(ex, 200, "{\"dnd\":" + calls.isDnd(me.username) + "}");
    }

    // ---- JSON ----

    private String roomTitle(String room) {
        if (room == null || room.isEmpty()) {
            return "";
        }
        return chat.hasGroup(room) ? chat.info(room, room).title : "deleted group";
    }

    private String viewJson(CallService.View v) {
        StringBuilder sb = new StringBuilder("{\"id\":").append(quote(v.id));
        sb.append(",\"video\":").append(v.video).append(",\"group\":").append(v.group);
        sb.append(",\"initiator\":").append(quote(v.initiator));
        sb.append(",\"room\":").append(quote(v.room == null ? "" : v.room));
        sb.append(",\"roomTitle\":").append(quote(roomTitle(v.room)));
        sb.append(",\"ended\":").append(v.ended).append(",\"endReason\":").append(quote(v.endReason));
        sb.append(",\"created\":").append(v.created).append(",\"answeredAt\":").append(v.answeredAt);
        sb.append(",\"endedAt\":").append(v.endedAt).append(",\"ringLeft\":").append(v.ringLeftMs / 1000);
        sb.append(",\"myState\":").append(quote(v.myState)).append(",\"invitedBy\":").append(quote(v.invitedBy));
        sb.append(",\"participants\":[");
        for (int i = 0; i < v.participants.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"name\":").append(quote(v.participants.get(i).name))
                    .append(",\"state\":").append(quote(v.participants.get(i).state))
                    .append(",\"by\":").append(quote(v.participants.get(i).by)).append('}');
        }
        return sb.append("]}").toString();
    }

    /** {"call":..., "media":..., "streams":[...]}. media and streams are only filled for someone who is in the call. */
    private String withMedia(HttpExchange ex, UserStore.Info me, CallService.View v, boolean unused) {
        String host = hostOf(ex);
        // Behind the HTTPS proxy (Caddy) the browser must use https://host/webrtc/... ; phones talking plain HTTP
        // to port 8080 use MediaMTX's own port.
        String proto = ex.getRequestHeaders().getFirst("X-Forwarded-Proto");
        String base = "https".equalsIgnoreCase(proto == null ? "" : proto.trim())
                ? "https://" + publicHost(ex, host) + "/webrtc/call/" + v.id + "/"
                : "http://" + host + ":" + WEBRTC_PORT + "/call/" + v.id + "/";
        StringBuilder sb = new StringBuilder("{\"call\":").append(viewJson(v));
        if (v.pubToken.isEmpty()) {
            sb.append(",\"media\":null,\"streams\":[]");
        } else {
            String myKey = me.username.toLowerCase(Locale.ROOT);
            sb.append(",\"media\":{\"pubToken\":").append(quote(v.pubToken)).append(",\"readToken\":").append(quote(v.readToken));
            sb.append(",\"whipUrl\":").append(quote(base + myKey + "/whip?token=" + v.pubToken)).append("},\"streams\":[");
            boolean first = true;
            for (CallService.PartView p : v.participants) {
                if (p.state.equals("JOINED") && !p.name.equalsIgnoreCase(me.username)) {
                    if (!first) {
                        sb.append(',');
                    }
                    first = false;
                    sb.append("{\"name\":").append(quote(p.name)).append(",\"whepUrl\":")
                            .append(quote(base + p.name.toLowerCase(Locale.ROOT) + "/whep?token=" + v.readToken)).append('}');
                }
            }
            sb.append(']');
        }
        return sb.append('}').toString();
    }

    /** The Host header as the browser sent it (keeps a non-standard port), if it looks like a host name. */
    private static String publicHost(HttpExchange ex, String fallback) {
        String h = ex.getRequestHeaders().getFirst("Host");
        return h != null && h.matches("[A-Za-z0-9.\\-]+(:[0-9]{1,5})?") ? h : fallback;
    }

    private static String hostOf(HttpExchange ex) {
        String h = ex.getRequestHeaders().getFirst("Host");
        if (h == null) {
            return "localhost";
        }
        int colon = h.lastIndexOf(':');
        if (colon > 0 && h.indexOf(']') < colon) {
            h = h.substring(0, colon);
        }
        return h.matches("[A-Za-z0-9.\\-]{1,100}") ? h : "localhost";
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
                case "/api/admin/calls/status":
                    if (requireMethod(ex, method, "GET")) {
                        send(ex, 200, "{\"enabled\":" + calls.enabled() + ",\"webrtcPort\":" + WEBRTC_PORT
                                + ",\"webrtcReachable\":" + webrtcReachable()
                                + ",\"callsToday\":" + calls.callsSince(chat.now() - 24L * 3600000L) + "}");
                    }
                    break;
                case "/api/admin/calls/enable":
                    if (requireMethod(ex, method, "POST")) {
                        calls.setEnabled("true".equals(MediaAuthHttpServer.queryParam(readBody(ex), "enabled")));
                        calls.flushIfDirty();
                        send(ex, 200, "{\"enabled\":" + calls.enabled() + "}");
                    }
                    break;
                case "/api/admin/calls/active":
                    if (requireMethod(ex, method, "GET")) {
                        StringBuilder sb = new StringBuilder("{\"calls\":[");
                        boolean first = true;
                        for (CallService.AdminCall c : calls.adminActive()) {
                            if (!first) {
                                sb.append(',');
                            }
                            first = false;
                            sb.append("{\"id\":").append(quote(c.id)).append(",\"video\":").append(c.video)
                                    .append(",\"group\":").append(c.group).append(",\"initiator\":").append(quote(c.initiator))
                                    .append(",\"joined\":").append(c.joined).append(",\"total\":").append(c.total)
                                    .append(",\"created\":").append(c.created).append('}');
                        }
                        send(ex, 200, sb.append("]}").toString());
                    }
                    break;
                case "/api/admin/calls/end":
                    if (requireMethod(ex, method, "POST")) {
                        calls.adminEnd(MediaAuthHttpServer.queryParam(readBody(ex), "id"));
                        send(ex, 200, "{\"ok\":true}");
                    }
                    break;
                default:
                    send(ex, 404, error("not found"));
            }
        } catch (CallService.CallException e) {
            send(ex, e.status, error(e.getMessage()));
        } catch (Exception e) {
            send(ex, 500, error("server error"));
        } finally {
            ex.close();
        }
    }

    /** True if something answers on MediaMTX's WebRTC port on this computer. */
    private boolean webrtcReachable() {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:" + WEBRTC_PORT + "/").openConnection();
            c.setConnectTimeout(1500);
            c.setReadTimeout(1500);
            try {
                c.getResponseCode();
                return true;
            } finally {
                c.disconnect();
            }
        } catch (Exception e) {
            return false;
        }
    }

    // ---- Helpers ----

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