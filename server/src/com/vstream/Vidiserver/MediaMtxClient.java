package com.vstream.Vidiserver;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Talks to the MediaMTX HTTP API (default http://127.0.0.1:9997).
 * Override with: java -Dvstream.mediaApi=http://127.0.0.1:9997 ...
 */
public final class MediaMtxClient {

    private static final String API =
            System.getProperty("vstream.mediaApi", "http://127.0.0.1:9997");
    private static final long CACHE_MS = 2000;
    private static final Object LOCK = new Object();

    private static Set<String> cachedReady = Collections.emptySet();
    private static long cachedAt = 0;

    private static final Pattern NAME = Pattern.compile("\"name\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern ID = Pattern.compile("\"id\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern READY = Pattern.compile("\"ready\"\\s*:\\s*true");
    private static final Pattern STATE_PUBLISH =
            Pattern.compile("\"state\"\\s*:\\s*\"publish\"");

    private MediaMtxClient() {
    }

    /** True if MediaMTX currently has a ready stream on this channel's path. */
    public static boolean isChannelLive(String channelId) {
        return readyPaths().contains(StreamUrls.path(channelId));
    }

    /** Kicks the channel's RTMP publisher on a background thread. */
    public static void kickPublisherAsync(final String channelId) {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                kickPublisher(channelId);
            }
        }, "media-kick");
        t.setDaemon(true);
        t.start();
    }

    /** Kicks every RTMP publisher on the channel's path. Returns how many were kicked. */
    public static int kickPublisher(String channelId) {
        String path = StreamUrls.path(channelId);
        int kicked = 0;
        try {
            String body = request("GET", API + "/v3/rtmpconns/list?itemsPerPage=1000");
            for (String id : parsePublisherIds(body, path)) {
                request("POST", API + "/v3/rtmpconns/kick/" + id);
                kicked++;
                System.out.println("[media] Kicked publisher " + id + " from " + path);
            }
        } catch (ConnectException e) {
            // MediaMTX is not running: nothing to kick.
        } catch (IOException e) {
            System.out.println("[media] Kick failed for " + path + ": " + e.getMessage());
        }
        return kicked;
    }

    /** Names of all paths MediaMTX reports as ready, cached for 2 seconds. */
    static Set<String> readyPaths() {
        synchronized (LOCK) {
            long now = System.currentTimeMillis();
            if (now - cachedAt < CACHE_MS) {
                return cachedReady;
            }
            Set<String> result;
            try {
                String body = request("GET", API + "/v3/paths/list?itemsPerPage=1000");
                result = parseReadyPaths(body);
            } catch (IOException e) {
                result = Collections.emptySet();
            }
            cachedReady = result;
            cachedAt = now;
            return result;
        }
    }

    /** Finds every path object whose "ready" field is true. */
    static Set<String> parseReadyPaths(String body) {
        Set<String> ready = new HashSet<String>();
        for (String chunk : chunks(body, NAME)) {
            Matcher n = NAME.matcher(chunk);
            if (n.find() && READY.matcher(chunk).find()) {
                ready.add(n.group(1));
            }
        }
        return ready;
    }

    /** Finds the ids of connections that are publishing to the given path. */
    static List<String> parsePublisherIds(String body, String path) {
        List<String> ids = new ArrayList<String>();
        Pattern pathPattern = Pattern.compile("\"path\"\\s*:\\s*\"" + Pattern.quote(path) + "\"");
        for (String chunk : chunks(body, ID)) {
            if (STATE_PUBLISH.matcher(chunk).find() && pathPattern.matcher(chunk).find()) {
                Matcher m = ID.matcher(chunk);
                if (m.find()) {
                    ids.add(m.group(1));
                }
            }
        }
        return ids;
    }

    /** Splits the text into pieces, each starting at a match of the pattern. */
    private static List<String> chunks(String body, Pattern start) {
        List<String> result = new ArrayList<String>();
        if (body == null) {
            return result;
        }
        List<Integer> starts = new ArrayList<Integer>();
        Matcher m = start.matcher(body);
        while (m.find()) {
            starts.add(m.start());
        }
        for (int i = 0; i < starts.size(); i++) {
            int end = (i + 1 < starts.size()) ? starts.get(i + 1) : body.length();
            result.add(body.substring(starts.get(i), end));
        }
        return result;
    }

    private static String request(String method, String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setRequestMethod(method);
            c.setConnectTimeout(1000);
            c.setReadTimeout(2000);
            int code = c.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new IOException("HTTP " + code + " from " + url);
            }
            InputStream in = c.getInputStream();
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] tmp = new byte[4096];
            int n;
            while ((n = in.read(tmp)) != -1) {
                buf.write(tmp, 0, n);
            }
            in.close();
            return new String(buf.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            c.disconnect();
        }
    }
}