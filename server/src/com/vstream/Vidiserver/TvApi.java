package com.vstream.Vidiserver;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;
import java.util.regex.Pattern;

/**
 * The public "Memoryq TV" page's data: every live channel with a prominence score, so the page can order the
 * list (and re-order it as things change), plus a small picture of each channel taken every 20 seconds.
 *
 *   GET /api/tv                 {"updated":..,"channels":[{channelId,title,viewers,peak,rising,since,uptime,score,rating,thumb,radio?}]}
 *   GET /api/tv/thumb/CHANNEL   the latest picture (JPEG), 404 until there is one
 *
 * Score (higher = shown first): people watching now count most, then the most who watched this broadcast, then
 * growth over the last 5 minutes, a little for staying on air (up to 2 hours) and a boost in the first 10 minutes
 * so new streams get seen. The rating (stars, when ratings come) is already part of the formula; it is null for now.
 */
public final class TvApi {

    private static final Pattern CHANNEL = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final long SAMPLE_MS = 10000;
    private static final long THUMB_MS = 20000;
    private static final long RISE_WINDOW_MS = 5 * 60000L;

    /** What is remembered about one channel while it is live. */
    static final class Stat {
        long since;
        int peak;
        final ArrayDeque<long[]> history = new ArrayDeque<long[]>(); // {time, viewers}
        volatile byte[] thumb;
        volatile long thumbAt;
        volatile boolean grabbing;
        Double rating; // stars 0..5, when ratings exist
    }

    private final Supplier<List<LiveChannel>> channels;
    private final ToIntFunction<String> viewers;
    private final Function<String, String> extra;
    private final LongSupplier clock;
    private final Map<String, Stat> stats = new ConcurrentHashMap<String, Stat>();
    private volatile Supplier<String> ffmpeg = () -> null;
    private final ExecutorService grabbers = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "tv-thumb");
        t.setDaemon(true);
        return t;
    });

    public TvApi(Supplier<List<LiveChannel>> channels, ToIntFunction<String> viewers, Function<String, String> extra,
                 LongSupplier clock) {
        this.channels = channels;
        this.viewers = viewers;
        this.extra = extra;
        this.clock = clock;
    }

    public void setFfmpeg(Supplier<String> path) {
        this.ffmpeg = path == null ? () -> null : path;
    }

    /** Samples viewers every 10 seconds and refreshes pictures every 20 seconds. */
    public void start() {
        Thread t = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    sample();
                    grabThumbs();
                    Thread.sleep(SAMPLE_MS);
                } catch (InterruptedException e) {
                    return;
                } catch (RuntimeException e) {
                    System.out.println("[tv] " + e);
                }
            }
        }, "tv-sampler");
        t.setDaemon(true);
        t.start();
    }

    /** Records the viewers of every live channel and forgets channels that ended. */
    synchronized void sample() {
        long now = clock.getAsLong();
        Set<String> live = new HashSet<String>();
        for (LiveChannel c : channels.get()) {
            String id = c.getChannelId();
            live.add(id);
            Stat s = stats.computeIfAbsent(id, k -> new Stat());
            if (s.since == 0) {
                s.since = c.getStartTime() > 0 && c.getStartTime() <= now ? c.getStartTime() : now;
            }
            int v = viewers.applyAsInt(id);
            s.peak = Math.max(s.peak, v);
            s.history.addLast(new long[] {now, v});
            while (s.history.size() > 2 && s.history.peekFirst()[0] < now - RISE_WINDOW_MS - SAMPLE_MS) {
                s.history.removeFirst();
            }
        }
        stats.keySet().retainAll(live);
    }

    private void grabThumbs() {
        String bin = ffmpeg.get();
        if (bin == null) {
            return;
        }
        long now = clock.getAsLong();
        for (Map.Entry<String, Stat> e : stats.entrySet()) {
            Stat s = e.getValue();
            if (s.grabbing || now - s.thumbAt < THUMB_MS) {
                continue;
            }
            s.grabbing = true;
            String id = e.getKey();
            grabbers.execute(() -> {
                try {
                    byte[] jpg = grab(bin, id);
                    if (jpg != null && jpg.length > 500) {
                        s.thumb = jpg;
                    }
                } finally {
                    s.thumbAt = clock.getAsLong();
                    s.grabbing = false;
                }
            });
        }
    }

    static List<String> grabCommand(String bin, String channel) {
        List<String> cmd = new ArrayList<String>();
        Collections.addAll(cmd, bin, "-hide_banner", "-loglevel", "quiet", "-rw_timeout", "8000000",
                "-i", "rtmp://127.0.0.1:1935/live/" + channel, "-frames:v", "1", "-vf", "scale=320:-2",
                "-q:v", "6", "-f", "image2", "-vcodec", "mjpeg", "pipe:1");
        return cmd;
    }

    private static byte[] grab(String bin, String channel) {
        Process p = null;
        try {
            p = new ProcessBuilder(grabCommand(bin, channel)).start();
            final Process proc = p;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            Thread reader = new Thread(() -> {
                try (InputStream in = proc.getInputStream()) {
                    byte[] b = new byte[8192];
                    int n;
                    while ((n = in.read(b)) > 0 && out.size() < 2_000_000) {
                        out.write(b, 0, n);
                    }
                } catch (IOException ignored) {
                    // ffmpeg was stopped
                }
            });
            reader.start();
            if (!p.waitFor(12, TimeUnit.SECONDS)) {
                p.destroyForcibly();
            }
            reader.join(2000);
            return p.exitValue() == 0 ? out.toByteArray() : null;
        } catch (Exception e) {
            return null;
        } finally {
            if (p != null && p.isAlive()) {
                p.destroyForcibly();
            }
        }
    }

    static double score(int viewers, int peak, int rising, long uptimeMs, Double rating) {
        double minutes = uptimeMs / 60000.0;
        double s = viewers * 10.0 + peak * 3.0 + Math.max(0, rising) * 5.0 + Math.min(minutes, 120) / 12.0;
        if (minutes < 10) {
            s += 8; // new streams get a moment at the top
        }
        if (rating != null) {
            s += rating * 4.0;
        }
        return Math.round(s * 10) / 10.0;
    }

    /** The live channels, highest score first. */
    synchronized String json() {
        long now = clock.getAsLong();
        List<Object[]> rows = new ArrayList<Object[]>();
        for (LiveChannel c : channels.get()) {
            String id = c.getChannelId();
            Stat s = stats.get(id);
            int v = viewers.applyAsInt(id);
            long since = s != null && s.since > 0 ? s.since : (c.getStartTime() > 0 ? c.getStartTime() : now);
            int peak = Math.max(v, s == null ? 0 : s.peak);
            int rising = 0;
            if (s != null && !s.history.isEmpty()) {
                long[] old = s.history.peekFirst();
                for (long[] h : s.history) {
                    if (h[0] >= now - RISE_WINDOW_MS) {
                        old = h;
                        break;
                    }
                }
                rising = v - (int) old[1];
            }
            Double rating = s == null ? null : s.rating;
            double score = score(v, peak, rising, now - since, rating);
            StringBuilder sb = new StringBuilder();
            sb.append("{\"channelId\":").append(q(id)).append(",\"title\":").append(q(c.getTitle() == null ? "" : c.getTitle()))
                    .append(",\"viewers\":").append(v).append(",\"peak\":").append(peak).append(",\"rising\":").append(rising)
                    .append(",\"since\":").append(since).append(",\"uptime\":").append(Math.max(0, (now - since) / 1000))
                    .append(",\"score\":").append(score).append(",\"rating\":").append(rating == null ? "null" : rating.toString())
                    .append(",\"thumb\":").append(s != null && s.thumb != null ? q("/api/tv/thumb/" + id + "?t=" + s.thumbAt) : "null");
            String more = extra == null ? null : extra.apply(id);
            if (more != null) {
                sb.append(more);
            }
            sb.append('}');
            rows.add(new Object[] {score, since, sb.toString()});
        }
        rows.sort((a, b) -> {
            int c = Double.compare((Double) b[0], (Double) a[0]);
            return c != 0 ? c : Long.compare((Long) b[1], (Long) a[1]);
        });
        StringBuilder out = new StringBuilder("{\"updated\":").append(now).append(",\"channels\":[");
        for (int i = 0; i < rows.size(); i++) {
            out.append(i > 0 ? "," : "").append((String) rows.get(i)[2]);
        }
        return out.append("]}").toString();
    }

    byte[] thumb(String channel) {
        Stat s = stats.get(channel);
        return s == null ? null : s.thumb;
    }

    /** For tests: put a picture in place. */
    void setThumb(String channel, byte[] jpg, long at) {
        Stat s = stats.computeIfAbsent(channel, k -> new Stat());
        s.thumb = jpg;
        s.thumbAt = at;
    }

    public void register(HttpServer server) {
        server.createContext("/api/tv", this::handle);
    }

    private void handle(HttpExchange ex) throws IOException {
        try {
            String path = ex.getRequestURI().getPath();
            if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
                send(ex, 405, "application/json", "{\"error\":\"use GET\"}".getBytes(StandardCharsets.UTF_8), false);
                return;
            }
            if (path.equals("/api/tv") || path.equals("/api/tv/")) {
                send(ex, 200, "application/json; charset=utf-8", json().getBytes(StandardCharsets.UTF_8), false);
                return;
            }
            if (path.startsWith("/api/tv/thumb/")) {
                String id = path.substring("/api/tv/thumb/".length());
                byte[] jpg = CHANNEL.matcher(id).matches() ? thumb(id) : null;
                if (jpg == null) {
                    send(ex, 404, "application/json", "{\"error\":\"no picture yet\"}".getBytes(StandardCharsets.UTF_8), false);
                } else {
                    send(ex, 200, "image/jpeg", jpg, true);
                }
                return;
            }
            send(ex, 404, "application/json", "{\"error\":\"not found\"}".getBytes(StandardCharsets.UTF_8), false);
        } catch (RuntimeException e) {
            send(ex, 500, "application/json", ("{\"error\":" + q("server error: " + e.getMessage()) + "}").getBytes(StandardCharsets.UTF_8), false);
        } finally {
            ex.close();
        }
    }

    private static void send(HttpExchange ex, int status, String type, byte[] body, boolean cache) throws IOException {
        ex.getResponseHeaders().set("Content-Type", type);
        ex.getResponseHeaders().set("Cache-Control", cache ? "public, max-age=60" : "no-store");
        ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        ex.sendResponseHeaders(status, body.length);
        OutputStream os = ex.getResponseBody();
        os.write(body);
        os.close();
    }

    private static String q(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\') {
                sb.append('\\').append(c);
            } else if (c < 0x20) {
                sb.append(String.format("\\u%04x", (int) c));
            } else {
                sb.append(c);
            }
        }
        return sb.append('"').toString();
    }
}