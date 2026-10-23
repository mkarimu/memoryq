package com.vstream.Vidiserver;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Notifications for the signed-in person.
 *   GET  /api/notify/count            {"unread":n}
 *   GET  /api/notify/list?limit=50    newest first
 *   POST /api/notify/read  id=        one (or, without id, all) as read
 */
public final class NotifyApi {

    private final NotificationStore notes;
    private final ProfileStore profiles;
    private final UserStore users;
    private final SessionStore sessions;

    public NotifyApi(NotificationStore notes, ProfileStore profiles, UserStore users, SessionStore sessions) {
        this.notes = notes;
        this.profiles = profiles;
        this.users = users;
        this.sessions = sessions;
    }

    public void register(HttpServer server) {
        server.createContext("/api/notify/", new HttpHandler() {
            @Override
            public void handle(HttpExchange ex) throws IOException {
                handle2(ex);
            }
        });
    }

    private void handle2(HttpExchange ex) throws IOException {
        try {
            String auth = ex.getRequestHeaders().getFirst("Authorization");
            String token = auth != null && auth.startsWith("Bearer ") ? auth.substring(7).trim() : null;
            SessionStore.Session s = sessions.get(token);
            UserStore.Info me = s == null ? null : users.info(s.username);
            if (me == null || me.disabled) {
                send(ex, 401, "{\"error\":\"sign in required\"}");
                return;
            }
            String path = ex.getRequestURI().getPath();
            String method = ex.getRequestMethod();
            if (path.equals("/api/notify/count") && method.equalsIgnoreCase("GET")) {
                send(ex, 200, "{\"unread\":" + notes.unread(me.username) + "}");
            } else if (path.equals("/api/notify/list") && method.equalsIgnoreCase("GET")) {
                int limit = 50;
                try {
                    String l = MediaAuthHttpServer.queryParam(ex.getRequestURI().getRawQuery(), "limit");
                    limit = l == null ? 50 : Math.max(1, Math.min(200, Integer.parseInt(l.trim())));
                } catch (NumberFormatException e) {
                    limit = 50;
                }
                StringBuilder sb = new StringBuilder("{\"unread\":").append(notes.unread(me.username)).append(",\"items\":[");
                boolean first = true;
                for (NotificationStore.Note n : notes.list(me.username, limit)) {
                    UserStore.Info a = users.info(n.actor);
                    if (a == null || a.disabled) {
                        continue;
                    }
                    ProfileStore.Profile p = profiles.get(n.actor);
                    if (!first) {
                        sb.append(',');
                    }
                    first = false;
                    sb.append("{\"id\":").append(n.id).append(",\"type\":").append(ProfileApi.quote(n.type))
                            .append(",\"actor\":").append(ProfileApi.quote(a.username))
                            .append(",\"stageName\":").append(ProfileApi.quote(p == null ? "" : p.stageName))
                            .append(",\"post\":").append(n.post).append(",\"comment\":").append(n.comment)
                            .append(",\"text\":").append(ProfileApi.quote(n.text))
                            .append(",\"created\":").append(n.created).append(",\"read\":").append(n.read).append('}');
                }
                send(ex, 200, sb.append("]}").toString());
            } else if (path.equals("/api/notify/read") && method.equalsIgnoreCase("POST")) {
                long id = 0;
                try {
                    String v = MediaAuthHttpServer.queryParam(readBody(ex), "id");
                    id = v == null || v.trim().isEmpty() ? 0 : Long.parseLong(v.trim());
                } catch (NumberFormatException e) {
                    id = 0;
                }
                notes.markRead(me.username, id);
                notes.flushIfDirty();
                send(ex, 200, "{\"unread\":" + notes.unread(me.username) + "}");
            } else {
                send(ex, 404, "{\"error\":\"not found\"}");
            }
        } catch (Exception e) {
            send(ex, 500, "{\"error\":\"server error\"}");
        } finally {
            ex.close();
        }
    }

    private static String readBody(HttpExchange ex) throws IOException {
        InputStream in = ex.getRequestBody();
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] tmp = new byte[1024];
        int total = 0;
        int n;
        while ((n = in.read(tmp)) != -1) {
            total += n;
            if (total > 4096) {
                throw new IOException("request body too large");
            }
            buf.write(tmp, 0, n);
        }
        return new String(buf.toByteArray(), StandardCharsets.UTF_8);
    }

    private static void send(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(status, bytes.length);
        OutputStream os = ex.getResponseBody();
        os.write(bytes);
        os.close();
    }
}