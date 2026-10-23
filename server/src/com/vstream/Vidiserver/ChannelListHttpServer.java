package com.vstream.Vidiserver;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;
import java.util.regex.Pattern;

/**
 * Viewer-facing HTTP API.
 *   GET  /channels  the live channels as JSON, with title and viewer count
 *   POST /watch     a viewer app says "I am watching this channel" (feeds the viewer count)
 */
public class ChannelListHttpServer {

    public static final int DEFAULT_PORT = 8080;
    private static final Pattern CHANNEL_ID = Pattern.compile("CHANNEL-\\d{1,9}");
    private static final Pattern VIEWER_ID = Pattern.compile("[A-Za-z0-9-]{8,64}");

    private final LiveChannelRegistry registry;
    private final int requestedPort;
    private final ViewerTracker viewers = new ViewerTracker();
    private volatile Predicate<LiveChannel> channelFilter = null;
    private HttpServer httpServer;
    /** Puts a check in front of every address (the Studio's shared try-it login), or null. */
    private volatile java.util.function.UnaryOperator<HttpServer> gate;

    public void setGate(java.util.function.UnaryOperator<HttpServer> g) {
        this.gate = g;
    }
    private volatile AccountApi accountApi = null;
    private volatile ChatApi chatApi = null;
    private volatile NewsApi newsApi = null;
    private volatile CallApi callApi = null;
    private volatile ProfileApi profileApi = null;
    private volatile FeedApi feedApi = null;
    private volatile WallApi wallApi = null;
    private volatile NotifyApi notifyApi = null;
    private volatile RadioApi radioApi = null;

    public ChannelListHttpServer(LiveChannelRegistry registry) {
        this(registry, DEFAULT_PORT);
    }

    public ChannelListHttpServer(LiveChannelRegistry registry, int port) {
        this.registry = registry;
        this.requestedPort = port;
    }

    /** Optional extra rule: only channels the filter accepts are listed. */
    public void setChannelFilter(Predicate<LiveChannel> filter) {
        this.channelFilter = filter;
    }

    /** Adds the sign-in and admin endpoints to this server. */
    public void setCallApi(CallApi api) {
        this.callApi = api;
    }

    public void setRadioApi(RadioApi api) {
        this.radioApi = api;
    }

    public void setNotifyApi(NotifyApi api) {
        this.notifyApi = api;
    }

    public void setWallApi(WallApi api) {
        this.wallApi = api;
    }

    public void setFeedApi(FeedApi api) {
        this.feedApi = api;
    }

    public void setProfileApi(ProfileApi api) {
        this.profileApi = api;
    }

    public void setNewsApi(NewsApi api) {
        this.newsApi = api;
    }

    public void setChatApi(ChatApi api) {
        this.chatApi = api;
    }

    public void setAccountApi(AccountApi api) {
        this.accountApi = api;
    }

    /** The channels that are really on air right now (what /channels lists). */
    public List<LiveChannel> liveChannels() {
        return visibleChannels();
    }

    /** Extra fields for a channel (the radio station a Live DJ Pro set is on), or null. */
    public String extraFor(String channelId) {
        RadioApi ra = radioApi;
        return ra == null ? null : ra.channelExtra(channelId);
    }

    public ViewerTracker getViewerTracker() {
        return viewers;
    }

    /** Starts listening. Use port 0 to let the OS choose a free port. */
    public synchronized void start() throws IOException {
        if (httpServer != null) {
            return;
        }
        httpServer = HttpServer.create(new InetSocketAddress(requestedPort), 0);
        java.util.function.UnaryOperator<HttpServer> g = gate;
        HttpServer reg = g == null ? httpServer : g.apply(httpServer); // every address goes through the gate
        reg.createContext("/", new Handler());
        AccountApi api = accountApi;
        if (api != null) {
            api.register(reg);
        }
        ChatApi chat = chatApi;
        if (chat != null) {
            chat.register(reg);
        }
        NewsApi news = newsApi;
        if (news != null) {
            news.register(reg);
        }
        CallApi callsApi = callApi;
        if (callsApi != null) {
            callsApi.register(reg);
        }
        ProfileApi profiles = profileApi;
        if (profiles != null) {
            profiles.register(reg);
        }
        FeedApi feed = feedApi;
        if (feed != null) {
            feed.register(reg);
        }
        WallApi walls = wallApi;
        if (walls != null) {
            walls.register(reg);
        }
        RadioApi radio = radioApi;
        if (radio != null) {
            radio.register(reg);
        }
        NotifyApi notify = notifyApi;
        if (notify != null) {
            notify.register(reg);
        }
        httpServer.setExecutor(Executors.newCachedThreadPool(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "channel-list-http");
                t.setDaemon(true);
                return t;
            }
        }));
        httpServer.start();
        System.out.println("[server] Channel list listening on http://localhost:"
                + getPort() + "/channels");
    }

    public synchronized void stop() {
        if (httpServer == null) {
            return;
        }
        httpServer.stop(0);
        httpServer = null;
        System.out.println("[server] Channel list stopped");
    }

    public synchronized int getPort() {
        return httpServer == null ? -1 : httpServer.getAddress().getPort();
    }

    private List<LiveChannel> visibleChannels() {
        List<LiveChannel> all = registry.getLiveChannels();
        Predicate<LiveChannel> f = channelFilter;
        if (f == null) {
            return all;
        }
        List<LiveChannel> result = new ArrayList<LiveChannel>();
        for (LiveChannel c : all) {
            if (f.test(c)) {
                result.add(c);
            }
        }
        return result;
    }

    /** JSON for a list of channels, with zero viewers. */
    static String toJson(List<LiveChannel> channels) {
        return toJson(channels, id -> 0);
    }

    /** JSON for a list of channels. The publish token is never included. */
    static String toJson(List<LiveChannel> channels, ToIntFunction<String> viewerCount) {
        return toJson(channels, viewerCount, null);
    }

    /** extra (may be null) adds fields for a channel, e.g. the radio station a Live DJ Pro set is on. */
    static String toJson(List<LiveChannel> channels, ToIntFunction<String> viewerCount,
                         java.util.function.Function<String, String> extra) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"count\":").append(channels.size()).append(",\"channels\":[");
        boolean first = true;
        for (LiveChannel c : channels) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append("{\"channelId\":").append(quote(c.getChannelId()));
            sb.append(",\"broadcasterId\":").append(quote(c.getBroadcasterId()));
            sb.append(",\"streamAddress\":").append(quote(c.getStreamAddress()));
            sb.append(",\"live\":").append(c.isLive());
            sb.append(",\"startTime\":").append(c.getStartTime());
            sb.append(",\"lastActivity\":").append(c.getLastActivity());
            sb.append(",\"title\":").append(quote(c.getTitle()));
            sb.append(",\"viewers\":").append(viewerCount.applyAsInt(c.getChannelId()));
            String more = extra == null ? null : extra.apply(c.getChannelId());
            if (more != null) {
                sb.append(more);
            }
            sb.append('}');
        }
        sb.append("]}");
        return sb.toString();
    }

    private static String quote(String s) {
        if (s == null) {
            return "null";
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

    private class Handler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                String path = exchange.getRequestURI().getPath();
                String method = exchange.getRequestMethod();
                if (path.equals("/")) {
                    // The site address on its own shows the public homepage (home.html), or the web dashboard.
                    AccountApi home = accountApi;
                    if (home != null && method.equalsIgnoreCase("GET") && home.serveHome(exchange)) {
                        return;
                    }
                    exchange.getResponseHeaders().set("Location", "/me");
                    exchange.sendResponseHeaders(302, -1);
                    return;
                }
                if (path.equals("/channels") || path.equals("/channels/")) {
                    if (!method.equalsIgnoreCase("GET")) {
                        exchange.getResponseHeaders().set("Allow", "GET");
                        send(exchange, 405, "{\"error\":\"method not allowed\"}");
                        return;
                    }
                    RadioApi ra = radioApi;
                    send(exchange, 200, toJson(visibleChannels(), viewers::count, ra == null ? null : ra::channelExtra));
                } else if (path.equals("/watch")) {
                    if (!method.equalsIgnoreCase("POST")) {
                        exchange.getResponseHeaders().set("Allow", "POST");
                        send(exchange, 405, "{\"error\":\"method not allowed\"}");
                        return;
                    }
                    handleWatch(exchange);
                } else {
                    send(exchange, 404, "{\"error\":\"not found\"}");
                }
            } finally {
                exchange.close();
            }
        }

        private void handleWatch(HttpExchange exchange) throws IOException {
            String query = exchange.getRequestURI().getRawQuery();
            String channel = MediaAuthHttpServer.queryParam(query, "channel");
            String viewer = MediaAuthHttpServer.queryParam(query, "viewer");
            if (channel == null || viewer == null
                    || !CHANNEL_ID.matcher(channel).matches()
                    || !VIEWER_ID.matcher(viewer).matches()) {
                send(exchange, 400, "{\"error\":\"bad request\"}");
                return;
            }
            LiveChannel c = registry.getChannel(channel);
            if (c == null || !c.isLive()) {
                send(exchange, 404, "{\"error\":\"unknown channel\"}");
                return;
            }
            viewers.ping(channel, viewer);
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
            exchange.sendResponseHeaders(204, -1);
        }

        private void send(HttpExchange exchange, int status, String body) throws IOException {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            exchange.sendResponseHeaders(status, bytes.length);
            OutputStream os = exchange.getResponseBody();
            os.write(bytes);
            os.close();
        }
    }
}