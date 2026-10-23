package com.vstream.Vidiserver;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MediaMTX calls POST /auth before every publish, read or API call.
 * Answer 200 = allowed, 401 = denied. Listens on 127.0.0.1 only.
 *
 * Rules:
 *   api      allowed only from a loopback address
 *   read     allowed for a registered, live channel path live/CHANNEL-nnn
 *   publish  allowed only for a registered, live channel AND a matching ?token=
 *   anything else is denied
 */
public class MediaAuthHttpServer {

    public static final int DEFAULT_PORT = 9120;
    private static final int MAX_BODY = 16 * 1024;
    private static final Pattern CHANNEL_PATH = Pattern.compile("live/(CHANNEL-\\d+)");
    private static final Pattern CALL_PATH = Pattern.compile("call/([0-9a-f]{16})/([a-z0-9_]{1,24})");

    /** Decides who may publish to or read a call stream (null = allowed). Only set when the calls add-on exists. */
    public interface CallPathAuth {
        String deny(String action, String callId, String userKey, String token);
    }

    private final LiveChannelRegistry registry;
    private volatile java.util.function.Consumer<String> publishListener = null;
    private volatile CallPathAuth callAuth = null;
    private final int requestedPort;
    private HttpServer httpServer;

    public MediaAuthHttpServer(LiveChannelRegistry registry) {
        this(registry, DEFAULT_PORT);
    }

    public MediaAuthHttpServer(LiveChannelRegistry registry, int port) {
        this.registry = registry;
        this.requestedPort = port;
    }

    public synchronized void start() throws IOException {
        if (httpServer != null) {
            return;
        }
        httpServer = HttpServer.create(
                new InetSocketAddress(InetAddress.getByName("127.0.0.1"), requestedPort), 0);
        httpServer.createContext("/auth", new AuthHandler());
        httpServer.setExecutor(Executors.newCachedThreadPool(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "media-auth-http");
                t.setDaemon(true);
                return t;
            }
        }));
        httpServer.start();
        System.out.println("[server] Media auth listening on http://127.0.0.1:" + getPort() + "/auth");
    }

    public synchronized void stop() {
        if (httpServer == null) {
            return;
        }
        httpServer.stop(0);
        httpServer = null;
        System.out.println("[server] Media auth stopped");
    }

    /** Called with the channel ID after every allowed publish (used to track channel use). */
    public void setCallAuth(CallPathAuth auth) {
        this.callAuth = auth;
    }

    public void setPublishListener(java.util.function.Consumer<String> listener) {
        this.publishListener = listener;
    }

    public synchronized int getPort() {
        return httpServer == null ? -1 : httpServer.getAddress().getPort();
    }

    /** Returns null if the request is allowed, otherwise a short reason for the denial. */
    static String denyReason(LiveChannelRegistry registry, String action, String path,
                             String query, String ip) {
        if (action == null || path == null) {
            return "unreadable request";
        }
        if (action.equals("api")) {
            return isLoopback(ip) ? null : "api call from a non-local address";
        }
        if (!action.equals("publish") && !action.equals("read")) {
            return "action not allowed";
        }
        if (path.startsWith("/")) {
            path = path.substring(1);
        }
        Matcher m = CHANNEL_PATH.matcher(path);
        if (!m.matches()) {
            return "not a channel path";
        }
        LiveChannel channel = registry.getChannel(m.group(1));
        if (channel == null || !channel.isLive()) {
            return "channel not registered";
        }
        if (action.equals("read")) {
            return null;
        }
        String expected = channel.getPublishToken();
        String given = queryParam(query, "token");
        if (expected == null || expected.isEmpty()) {
            return "channel has no token";
        }
        if (given == null || given.isEmpty()) {
            return "missing token";
        }
        boolean same = MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8), given.getBytes(StandardCharsets.UTF_8));
        return same ? null : "wrong token";
    }

    /** Value of one parameter in a query string like "a=1&token=xyz". Null if absent. */
    static String queryParam(String query, String name) {
        if (query == null || query.isEmpty()) {
            return null;
        }
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            String key = eq < 0 ? pair : pair.substring(0, eq);
            if (key.equals(name)) {
                String value = eq < 0 ? "" : pair.substring(eq + 1);
                try {
                    return URLDecoder.decode(value, "UTF-8");
                } catch (Exception e) {
                    return null;
                }
            }
        }
        return null;
    }

    /** Decodes JSON string escapes. Go writes "&" as \u0026, so queries arrive escaped. */
    static String unescapeJson(String s) {
        if (s == null || s.indexOf('\\') < 0) {
            return s;
        }
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch != '\\' || i + 1 >= s.length()) {
                sb.append(ch);
                continue;
            }
            char n = s.charAt(++i);
            switch (n) {
                case 'u':
                    if (i + 4 < s.length()) {
                        try {
                            sb.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16));
                            i += 4;
                            break;
                        } catch (NumberFormatException e) {
                            // fall through and keep the text as it is
                        }
                    }
                    sb.append("\\u");
                    break;
                case 'n': sb.append('\n'); break;
                case 'r': sb.append('\r'); break;
                case 't': sb.append('\t'); break;
                case 'b': sb.append('\b'); break;
                case 'f': sb.append('\f'); break;
                default:  sb.append(n);
            }
        }
        return sb.toString();
    }

    static boolean isLoopback(String ip) {
        return ip != null && (ip.equals("127.0.0.1") || ip.equals("::1")
                || ip.equals("0:0:0:0:0:0:0:1"));
    }

    /** Reads one string field from a flat JSON object. Returns null if absent. */
    static String field(String json, String name) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(name) + "\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
        return m.find() ? unescapeJson(m.group(1)) : null;
    }

    private static String readBody(InputStream in) throws IOException {
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

    /** Cleans a broadcaster title: no control characters, at most 40 characters. */
    static String cleanTitle(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < raw.length() && sb.length() < 40; i++) {
            char ch = raw.charAt(i);
            if (ch >= 0x20 && ch != 0x7f) {
                sb.append(ch);
            }
        }
        return sb.toString().trim();
    }

    /** After an allowed publish, stores the title from the publish query (or clears it). */
    private void applyTitleIfAllowed(String reason, String action, String path, String query) {
        if (reason != null || !"publish".equals(action) || path == null) {
            return;
        }
        String p = path.startsWith("/") ? path.substring(1) : path;
        Matcher m = CHANNEL_PATH.matcher(p);
        if (!m.matches()) {
            return;
        }
        LiveChannel channel = registry.getChannel(m.group(1));
        if (channel != null) {
            channel.setTitle(cleanTitle(queryParam(query, "title")));
            java.util.function.Consumer<String> listener = publishListener;
            if (listener != null) {
                listener.accept(channel.getChannelId());
            }
        }
    }

    private class AuthHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            int status = 401;
            try {
                if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                    String body = readBody(exchange.getRequestBody());
                    String action = field(body, "action");
                    String path = field(body, "path");
                    String query = field(body, "query");
                    String ip = field(body, "ip");
                    String reason;
                    CallPathAuth callGate = callAuth;
                    Matcher callMatch = (callGate == null || path == null) ? null
                            : CALL_PATH.matcher(path.startsWith("/") ? path.substring(1) : path);
                    if (callMatch != null && callMatch.matches()) {
                        reason = callGate.deny(action, callMatch.group(1), callMatch.group(2), queryParam(query, "token"));
                    } else {
                        reason = denyReason(registry, action, path, query, ip);
                    }
                    applyTitleIfAllowed(reason, action, path, query);
                    if (reason == null) {
                        status = 200;
                    }
                    // The query (which holds the token) is never logged.
                    if ("publish".equals(action)) {
                        System.out.println("[auth] " + (reason == null ? "ALLOW" : "DENY")
                                + " publish " + path + " from " + ip
                                + (reason == null ? "" : " (" + reason + ")"));
                    } else if (reason != null && ("api".equals(action) || action == null)) {
                        System.out.println("[auth] DENY " + action + " (" + reason + ") from " + ip);
                    }
                }
            } catch (Exception e) {
                status = 401;
                System.out.println("[auth] DENY, error: " + e.getMessage());
            } finally {
                exchange.sendResponseHeaders(status, -1);
                exchange.close();
            }
        }
    }
}