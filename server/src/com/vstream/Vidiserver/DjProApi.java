package com.vstream.Vidiserver;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Live DJ Pro, the DJ console in the browser (/djpro). The station owner (signed in) or one of the station's remote
 * DJs (their DJ login) mixes in the browser; ON AIR sends the mix here in small pieces and the station plays it live
 * (AutoDJ waits in the background). With the camera on, the browser also sends picture + mix to a Live Channel.
 *   POST /api/djpro/login     station, login, password      a remote DJ signs in -> {token}
 *   GET  /api/djpro/state?station=ID                        the station, who is live, my session
 *   GET  /api/djpro/tracks?station=ID                       the station's music for the decks
 *   GET  /api/djpro/track?station=ID&id=N                   one song's sound file
 *   POST /api/djpro/onair     station, name                 -> {session}
 *   POST /api/djpro/audio?session=S   (sound bytes)         the next piece of the mix
 *   POST /api/djpro/offair    session
 *   POST /api/djpro/camera    station, title                -> {whipUrl, channelId}
 *   GET  /api/djpro/settings                                {dropboxKey} (any signed-in caller)
 *   POST /api/djpro/settings  dropboxKey                    admin only
 *   GET  /api/djpro/fetch?url=U                             a Dropbox song, passed through (when the browser cannot fetch it)
 */
public final class DjProApi {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final long DJ_TOKEN_MS = 12L * 3600 * 1000;
    /** No sound for this long: the session ends and AutoDJ comes back. */
    static final long SILENCE_MS = Long.getLong("vstream.djproSilenceMs", 15000L);
    private static final int MAX_CHUNK = 1024 * 1024;
    private static final int WEBRTC_PORT = Integer.getInteger("vstream.webrtcPort", 8889);

    private final RadioService radio;
    private final UserStore users;
    private final SessionStore sessions;
    private final LongSupplier clock;
    private volatile RadioBilling billing;
    private volatile ClaimStore claims;
    /** Console settings (the Dropbox app key), kept in djpro.txt next to the other data. */
    private volatile Path settingsFile = null;
    private volatile String dropboxKey = "";
    private volatile String videoCacheKey = "";
    private volatile boolean videoCacheLive = false;
    private volatile long videoCacheAt = 0;

    /** A remote DJ signed in to the console. */
    private static final class DjLogin {
        long station;
        long djId;
        String name;
        long expires;
    }

    /** One console on air. */
    private static final class Live {
        String id;
        long station;
        String who;
        String name;
        long djId;
        long start;
        volatile long lastData;
        /** the Live Channel showing this set (camera or console picture), "" = none */
        volatile String video = "";
        LiveInput.LiveSource src;
    }

    /** Who is calling: an account (owner/admin) or a remote DJ. */
    private static final class Who {
        UserStore.Info user;
        DjLogin dj;
        String key;

        boolean admin() {
            return user != null && user.role == UserStore.Role.ADMIN;
        }
    }

    private final Map<String, DjLogin> djTokens = new HashMap<String, DjLogin>();
    private final Map<String, Live> lives = new HashMap<String, Live>();

    public DjProApi(RadioService radio, UserStore users, SessionStore sessions, LongSupplier clock) {
        this.radio = radio;
        this.users = users;
        this.sessions = sessions;
        this.clock = clock;
    }

    public void setBilling(RadioBilling b) {
        this.billing = b;
    }

    /** The channel pool, for the camera (a Live Channel held by the station owner). */
    public void setClaims(ClaimStore c) {
        this.claims = c;
    }

    /** Where the console settings are kept; reads them now. */
    public void setSettingsFile(Path p) {
        settingsFile = p;
        try {
            if (p != null && Files.isRegularFile(p)) {
                for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
                    String[] f = line.split("\t", 2);
                    if (f.length == 2 && "dropboxKey".equals(f[0])) {
                        dropboxKey = f[1].trim();
                    }
                }
            }
        } catch (IOException e) {
            System.out.println("[djpro] could not read " + p + ": " + e.getMessage());
        }
    }

    public String dropboxKey() {
        return dropboxKey;
    }

    void saveDropboxKey(String key) throws IOException {
        String k = key == null ? "" : key.trim();
        if (!k.isEmpty() && !k.matches("[A-Za-z0-9]{6,40}")) {
            throw new IllegalArgumentException("that does not look like a Dropbox app key (letters and numbers)");
        }
        dropboxKey = k;
        Path p = settingsFile;
        if (p != null) {
            Path tmp = p.resolveSibling(p.getFileName() + ".tmp");
            Files.write(tmp, ("dropboxKey\t" + k + "\n").getBytes(StandardCharsets.UTF_8));
            Files.move(tmp, p, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** The DJ live on a station from the console, or null. */
    public synchronized String liveName(long station) {
        for (Live l : lives.values()) {
            if (l.station == station) {
                return l.name;
            }
        }
        return null;
    }

    /** The Live Channel showing a station's console set right now, or null. */
    public String liveVideo(long station) {
        String ch = null;
        synchronized (this) {
            for (Live l : lives.values()) {
                if (l.station == station && !l.video.isEmpty()) {
                    ch = l.video;
                }
            }
        }
        return ch != null && videoLive(ch) ? ch : null;
    }

    /** The station whose console set a Live Channel is showing, or 0. */
    public synchronized long stationOfVideo(String channelId) {
        for (Live l : lives.values()) {
            if (channelId != null && channelId.equals(l.video)) {
                return l.station;
            }
        }
        return 0;
    }

    /** Asks the video server whether the channel has a picture (remembered for a few seconds). */
    private boolean videoLive(String ch) {
        long now = System.currentTimeMillis();
        if (ch.equals(videoCacheKey) && now - videoCacheAt < 4000) {
            return videoCacheLive;
        }
        boolean live = Boolean.getBoolean("vstream.djproAssumeVideo") || MediaMtxClient.isChannelLive(ch);
        videoCacheKey = ch;
        videoCacheLive = live;
        videoCacheAt = now;
        return live;
    }

    public void register(HttpServer server) {
        server.createContext("/api/djpro/", this::handle);
        java.util.concurrent.ScheduledExecutorService t = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
            Thread th = new Thread(r, "djpro-watch");
            th.setDaemon(true);
            return th;
        });
        t.scheduleWithFixedDelay(() -> {
            try {
                watch();
            } catch (RuntimeException e) {
                System.out.println("[djpro] watch failed: " + e);
            }
        }, 3, 3, java.util.concurrent.TimeUnit.SECONDS);
    }

    private static final class Bad extends Exception {
        private static final long serialVersionUID = 1L;
        final int status;

        Bad(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    private void handle(HttpExchange ex) throws IOException {
        try {
            String path = ex.getRequestURI().getPath().substring("/api/djpro/".length());
            String query = ex.getRequestURI().getRawQuery();
            boolean post = "POST".equalsIgnoreCase(ex.getRequestMethod());
            if ("login".equals(path)) {
                requirePost(post);
                login(ex);
                return;
            }
            Who who = who(ex);
            if (who == null) {
                throw new Bad(401, "sign in first");
            }
            switch (path) {
                case "settings": {
                    if (post) {
                        if (!who.admin()) {
                            throw new Bad(403, "admins only");
                        }
                        saveDropboxKey(param(readBody(ex, 4096), "dropboxKey"));
                    }
                    send(ex, 200, "{\"dropboxKey\":" + q(dropboxKey) + "}");
                    break;
                }
                case "fetch": {
                    passThrough(ex, param(query, "url"));
                    break;
                }
                case "state": {
                    RadioStore.Station s = station(who, param(query, "station"));
                    send(ex, 200, stateJson(who, s));
                    break;
                }
                case "tracks": {
                    RadioStore.Station s = station(who, param(query, "station"));
                    StringBuilder sb = new StringBuilder("{\"tracks\":[");
                    List<RadioStore.Track> list = radio.store().tracks(s.id);
                    for (int i = 0; i < list.size(); i++) {
                        RadioStore.Track t = list.get(i);
                        if (i > 0) {
                            sb.append(',');
                        }
                        sb.append("{\"id\":").append(t.id).append(",\"title\":").append(q(t.title)).append(",\"artist\":")
                                .append(q(t.artist)).append(",\"durationMs\":").append(t.durationMs).append(",\"plays\":")
                                .append(t.plays).append('}');
                    }
                    // playlists with "Studio" ticked: ready in the console (their songs from the station's library)
                    sb.append("],\"playlists\":[");
                    int n = 0;
                    for (RadioStore.Playlist p : radio.store().playlists(s.id)) {
                        if (p.studio) {
                            sb.append(n++ > 0 ? "," : "").append("{\"id\":").append(p.id).append(",\"name\":").append(q(p.name))
                                    .append(",\"tracks\":").append(p.tracks.toString().replace(" ", "")).append('}');
                        }
                    }
                    send(ex, 200, sb.append("]}").toString());
                    break;
                }
                case "track": {
                    RadioStore.Station s = station(who, param(query, "station"));
                    RadioStore.Track t = radio.store().track(longParam(param(query, "id")));
                    if (t == null || t.station != s.id) {
                        throw new Bad(404, "no such song");
                    }
                    sendFile(ex, radio.store().pathOf(t));
                    break;
                }
                case "onair": {
                    requirePost(post);
                    String body = readBody(ex, 16 * 1024);
                    RadioStore.Station s = station(who, param(body, "station"));
                    send(ex, 200, onAir(who, s, param(body, "name")));
                    break;
                }
                case "audio": {
                    requirePost(post);
                    Live l = mine(who, param(query, "session"));
                    pump(ex, l);
                    send(ex, 200, "{\"ok\":true}");
                    break;
                }
                case "offair": {
                    requirePost(post);
                    Live l = mine(who, param(readBody(ex, 4096), "session"));
                    end(l, "off air");
                    send(ex, 200, "{\"ok\":true}");
                    break;
                }
                case "camera": {
                    requirePost(post);
                    String body = readBody(ex, 4096);
                    RadioStore.Station s = station(who, param(body, "station"));
                    send(ex, 200, camera(ex, who, s, param(body, "title")));
                    break;
                }
                default:
                    throw new Bad(404, "not found");
            }
        } catch (Bad e) {
            send(ex, e.status, error(e.getMessage()));
        } catch (IllegalArgumentException e) {
            send(ex, 400, error(e.getMessage()));
        } catch (Exception e) {
            send(ex, 500, error("server error: " + e.getMessage()));
        } finally {
            ex.close();
        }
    }

    // ---- Who may use the console ----

    private void login(HttpExchange ex) throws Exception {
        String body = readBody(ex, 4096);
        long station = longParam(param(body, "station"));
        RadioStore.Station s = radio.store().station(station);
        RadioStore.Dj d = s == null ? null : radio.store().djLogin(station, param(body, "login"), param(body, "password"));
        if (d == null) {
            Thread.sleep(400); // slows down guessing
            throw new Bad(401, "wrong station number, login or password");
        }
        if (!d.enabled) {
            throw new Bad(403, "your DJ login is switched off - ask the station owner");
        }
        if (!d.studio) {
            throw new Bad(403, "the station has not given you access to the live studio yet - ask the station admin to tick "
                    + "\"Live studio\" for your DJ login");
        }
        checkPro(s);
        DjLogin l = new DjLogin();
        l.station = station;
        l.djId = d.id;
        l.name = d.name;
        l.expires = clock.getAsLong() + DJ_TOKEN_MS;
        String token = "djp_" + token();
        synchronized (this) {
            djTokens.values().removeIf(x -> x.expires < clock.getAsLong());
            djTokens.put(token, l);
        }
        send(ex, 200, "{\"token\":" + q(token) + ",\"station\":" + station + ",\"name\":" + q(d.name) + "}");
    }

    private Who who(HttpExchange ex) {
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        String token = auth != null && auth.startsWith("Bearer ") ? auth.substring(7).trim() : null;
        if (token == null || token.isEmpty()) {
            return null;
        }
        Who w = new Who();
        if (token.startsWith("djp_")) {
            synchronized (this) {
                DjLogin d = djTokens.get(token);
                if (d == null || d.expires < clock.getAsLong()) {
                    return null;
                }
                w.dj = d;
            }
            RadioStore.Dj now = radio.store().dj(w.dj.djId);
            if (now == null || !now.enabled || !now.studio || now.station != w.dj.station) {
                return null; // the station admin took the access away (or removed the DJ)
            }
            w.key = "dj:" + w.dj.station + ":" + w.dj.djId;
            return w;
        }
        SessionStore.Session s = sessions.get(token);
        UserStore.Info info = s == null ? null : users.info(s.username);
        if (info == null || info.disabled || info.role == UserStore.Role.GUEST) {
            return null;
        }
        w.user = info;
        w.key = "user:" + info.username.toLowerCase(Locale.ROOT);
        return w;
    }

    /** The station this caller may mix on. */
    private RadioStore.Station station(Who who, String idText) throws Bad {
        RadioStore.Station s = radio.store().station(longParam(idText));
        if (s == null) {
            throw new Bad(404, "no such station");
        }
        if (who.dj != null) {
            if (who.dj.station != s.id) {
                throw new Bad(403, "your DJ login is for another station");
            }
        } else if (!who.admin() && (s.owner.isEmpty() || !s.owner.equalsIgnoreCase(who.user.username))
                && radio.store().staffRole(s.id, who.user.username) == null) {
            throw new Bad(403, "this is not your station");
        }
        if (RadioStore.STATUS_SUSPENDED.equals(s.status) && !who.admin()) {
            throw new Bad(403, s.name + " is paused by Memoryq");
        }
        checkPro(s);
        return s;
    }

    private void checkPro(RadioStore.Station s) throws Bad {
        RadioBilling b = billing;
        RadioBilling.Plan p = b == null || s.planId == 0 ? null : b.plan(s.planId);
        if (p != null && !p.djPro) {
            throw new Bad(403, "Live DJ Pro is not in " + s.name + "'s plan (" + p.name + ") - a bigger plan includes it");
        }
    }

    // ---- On air ----

    private String onAir(Who who, RadioStore.Station s, String wantName) throws Exception {
        if (!s.onAir()) {
            throw new Bad(409, s.name + " is off air (switched off, waiting for activation or payment)");
        }
        if (who.dj != null) {
            String no = radio.djRefusal(s.id, who.dj.djId);
            if (no != null) {
                throw new Bad(403, no);
            }
        }
        Live mineBefore = null;
        synchronized (this) {
            for (Live l : lives.values()) {
                if (l.station == s.id) {
                    if (l.who.equals(who.key)) {
                        mineBefore = l;
                    } else {
                        throw new Bad(409, l.name + " is live on " + s.name + " right now");
                    }
                }
            }
        }
        if (mineBefore != null) {
            end(mineBefore, "reconnected");
        }
        LiveInput door = radio.liveDoor(s.id);
        if (door != null && door.connected()) {
            throw new Bad(409, (door.djName().isEmpty() ? "A DJ" : door.djName()) + " is live on " + s.name
                    + " from their DJ software right now");
        }
        String name = who.dj != null ? who.dj.name : RadioStore.clean(wantName, 40);
        if (name.isEmpty()) {
            name = s.djName;
        }
        Process ff = radio.tools().liveDecoder();
        Live l = new Live();
        l.id = token();
        l.station = s.id;
        l.who = who.key;
        l.name = name;
        l.djId = who.dj == null ? 0 : who.dj.djId;
        l.start = clock.getAsLong();
        l.lastData = System.currentTimeMillis();
        l.src = new LiveInput.LiveSource(ff);
        synchronized (this) {
            lives.put(l.id, l);
        }
        radio.studioConnected(s.id, l.src, name);
        System.out.println("[djpro] " + name + " is on air on " + s.name);
        return "{\"session\":" + q(l.id) + ",\"name\":" + q(name) + "}";
    }

    private Live mine(Who who, String id) throws Bad {
        synchronized (this) {
            Live l = id == null ? null : lives.get(id);
            if (l == null || !l.who.equals(who.key)) {
                throw new Bad(410, "this live session has ended - press ON AIR again");
            }
            return l;
        }
    }

    /** Copies one piece of the mix into the station's live decoder. */
    private void pump(HttpExchange ex, Live l) throws IOException, Bad {
        InputStream in = ex.getRequestBody();
        OutputStream to = l.src.input();
        byte[] b = new byte[16384];
        int total = 0;
        int n;
        try {
            while ((n = in.read(b)) > 0) {
                total += n;
                if (total > MAX_CHUNK) {
                    throw new Bad(413, "piece too big");
                }
                to.write(b, 0, n);
            }
            to.flush();
        } catch (IOException e) {
            end(l, "decoder stopped");
            throw new Bad(410, "the live sound stopped - press ON AIR again");
        }
        if (total > 0) {
            l.lastData = System.currentTimeMillis();
        }
    }

    private void end(Live l, String why) {
        synchronized (this) {
            if (lives.remove(l.id) == null) {
                return;
            }
        }
        radio.studioDisconnected(l.station, l.src);
        long now = clock.getAsLong();
        if (now - l.start >= 30000) {
            radio.store().addShow(l.station, l.name + " (Live DJ Pro)", l.start, now, l.djId);
            radio.store().flushIfDirty();
        }
        System.out.println("[djpro] " + l.name + " left the air (" + why + ")");
    }

    /** Ends sessions that stopped sending sound (closed browser, lost network). */
    void watch() {
        long now = System.currentTimeMillis();
        List<Live> quiet = new java.util.ArrayList<Live>();
        synchronized (this) {
            for (Live l : lives.values()) {
                if (now - l.lastData > SILENCE_MS || l.src.finished()) {
                    quiet.add(l);
                }
            }
        }
        for (Live l : quiet) {
            end(l, "no sound for " + (SILENCE_MS / 1000) + " seconds");
        }
    }

    // ---- Camera: a Live Channel ----

    private String camera(HttpExchange ex, Who who, RadioStore.Station s, String title) throws Bad {
        ClaimStore c = claims;
        if (c == null) {
            throw new Bad(409, "live video is not available on this server");
        }
        if (s.owner.isEmpty()) {
            throw new Bad(409, "the camera needs a station with an owner account");
        }
        ClaimStore.Claim claim = c.claimOf(s.owner);
        if (claim == null) {
            for (ClaimStore.PoolEntry p : c.pool()) {
                if (p.holder == null) {
                    try {
                        claim = c.claim(s.owner, p.channelId);
                        break;
                    } catch (ClaimStore.ClaimException e) {
                        // taken a moment ago - try the next one
                    }
                }
            }
        }
        if (claim == null) {
            throw new Bad(409, "all live channels are in use right now - try again in a while");
        }
        String t = RadioStore.clean(title, 80);
        if (t.isEmpty()) {
            t = (who.dj != null ? who.dj.name : s.djName) + " on " + s.name;
        }
        // remember which channel shows the set, so the radio list and Live Channels can link the two
        synchronized (this) {
            for (Live l : lives.values()) {
                if (l.station == s.id && l.who.equals(who.key)) {
                    l.video = claim.channelId;
                }
            }
        }
        String proto = ex.getRequestHeaders().getFirst("X-Forwarded-Proto");
        String host = ex.getRequestHeaders().getFirst("Host");
        if (host == null || !host.matches("[A-Za-z0-9.\\-]+(:[0-9]{1,5})?")) {
            host = "localhost";
        }
        String base = "https".equalsIgnoreCase(proto == null ? "" : proto.trim())
                ? "https://" + host + "/webrtc/live/"
                : "http://" + host.replaceAll(":[0-9]+$", "") + ":" + WEBRTC_PORT + "/live/";
        String url;
        try {
            url = base + claim.channelId + "/whip?token=" + claim.token + "&title=" + URLEncoder.encode(t, "UTF-8").replace("+", "%20");
        } catch (java.io.UnsupportedEncodingException e) {
            throw new Bad(500, "encoding");
        }
        return "{\"whipUrl\":" + q(url) + ",\"channelId\":" + q(claim.channelId) + ",\"title\":" + q(t) + "}";
    }

    // ---- Dropbox songs, passed through when the browser cannot fetch them itself ----

    private static final int MAX_SONG = 80 * 1024 * 1024;

    private void passThrough(HttpExchange ex, String url) throws Bad, IOException {
        java.net.URL u;
        try {
            u = new java.net.URL(url == null ? "" : url);
        } catch (java.net.MalformedURLException e) {
            throw new Bad(400, "bad link");
        }
        String host = u.getHost().toLowerCase(Locale.ROOT);
        if (!"https".equals(u.getProtocol()) || !(host.endsWith(".dropboxusercontent.com") || host.equals("dropboxusercontent.com"))) {
            throw new Bad(400, "only Dropbox song links can be fetched");
        }
        java.net.HttpURLConnection c = (java.net.HttpURLConnection) u.openConnection();
        c.setInstanceFollowRedirects(false);
        c.setConnectTimeout(10000);
        c.setReadTimeout(30000);
        int code = c.getResponseCode();
        if (code != 200) {
            throw new Bad(502, "Dropbox said " + code + " - choose the song again");
        }
        long len = c.getContentLengthLong();
        if (len > MAX_SONG) {
            throw new Bad(413, "that file is too big for the console");
        }
        ex.getResponseHeaders().set("Content-Type", "application/octet-stream");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(200, len > 0 ? len : 0);
        try (InputStream in = c.getInputStream(); OutputStream out = ex.getResponseBody()) {
            byte[] b = new byte[65536];
            long total = 0;
            int n;
            while ((n = in.read(b)) > 0) {
                total += n;
                if (total > MAX_SONG) {
                    break;
                }
                out.write(b, 0, n);
            }
        }
    }

    // ---- State ----

    private String stateJson(Who who, RadioStore.Station s) {
        Live mineNow = null;
        Live other = null;
        synchronized (this) {
            for (Live l : lives.values()) {
                if (l.station == s.id) {
                    if (l.who.equals(who.key)) {
                        mineNow = l;
                    } else {
                        other = l;
                    }
                }
            }
        }
        LiveInput door = radio.liveDoor(s.id);
        String doorDj = door != null && door.connected() ? (door.djName().isEmpty() ? "A DJ" : door.djName()) : "";
        RadioEngine.Event now = radio.engine(s.id) == null ? null : radio.engine(s.id).nowSong();
        StringBuilder sb = new StringBuilder("{\"station\":{\"id\":").append(s.id)
                .append(",\"name\":").append(q(s.name))
                .append(",\"slogan\":").append(q(s.description))
                .append(",\"djName\":").append(q(s.djName))
                .append(",\"onAir\":").append(s.onAir())
                .append(",\"listeners\":").append(radio.listeners(s.id))
                .append(",\"nowPlaying\":").append(q(now == null ? "" : now.text))
                .append("},\"me\":{\"name\":").append(q(who.dj != null ? who.dj.name : who.user.username))
                .append(",\"dj\":").append(who.dj != null).append('}')
                .append(",\"busy\":").append(q(other != null ? other.name : doorDj))
                .append(",\"dropboxKey\":").append(q(dropboxKey))
                .append(",\"session\":");
        if (mineNow == null) {
            sb.append("null");
        } else {
            sb.append("{\"id\":").append(q(mineNow.id)).append(",\"since\":").append(mineNow.start)
                    .append(",\"video\":").append(q(mineNow.video)).append('}');
        }
        if (who.user != null) {
            sb.append(",\"stations\":[");
            List<RadioStore.Station> list = who.admin() ? radio.store().stations() : radio.store().manageableBy(who.user.username);
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append("{\"id\":").append(list.get(i).id).append(",\"name\":").append(q(list.get(i).name)).append('}');
            }
            sb.append(']');
        }
        return sb.append('}').toString();
    }

    /** Stations the caller may use, for the first screen (no station chosen yet). */
    // (the page uses /api/studio/status for owners; remote DJs have one station)

    // ---- Helpers ----

    private static String token() {
        byte[] b = new byte[18];
        RANDOM.nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) {
            sb.append(String.format("%02x", x));
        }
        return sb.toString();
    }

    private static void sendFile(HttpExchange ex, Path p) throws IOException {
        String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
        String type = name.endsWith(".mp3") ? "audio/mpeg" : name.endsWith(".m4a") || name.endsWith(".aac") ? "audio/mp4"
                : name.endsWith(".ogg") || name.endsWith(".opus") ? "audio/ogg" : name.endsWith(".flac") ? "audio/flac"
                : name.endsWith(".wav") ? "audio/wav" : "application/octet-stream";
        if (!Files.isRegularFile(p)) {
            send(ex, 404, error("the song file is missing"));
            return;
        }
        long size = Files.size(p);
        ex.getResponseHeaders().set("Content-Type", type);
        ex.getResponseHeaders().set("Cache-Control", "private, max-age=3600");
        ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        ex.sendResponseHeaders(200, size);
        try (OutputStream os = ex.getResponseBody()) {
            Files.copy(p, os);
        }
    }

    private static void requirePost(boolean post) throws Bad {
        if (!post) {
            throw new Bad(405, "use POST");
        }
    }

    private static String param(String q, String k) {
        return MediaAuthHttpServer.queryParam(q, k);
    }

    private static long longParam(String s) throws Bad {
        try {
            return Long.parseLong(s == null ? "" : s.trim());
        } catch (NumberFormatException e) {
            throw new Bad(400, "bad number");
        }
    }

    private static String q(String s) {
        return ProfileApi.quote(s == null ? "" : s);
    }

    private static String readBody(HttpExchange ex, int max) throws IOException, Bad {
        InputStream in = ex.getRequestBody();
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] tmp = new byte[4096];
        int n;
        while ((n = in.read(tmp)) != -1) {
            if (buf.size() + n > max) {
                throw new Bad(413, "too much data sent");
            }
            buf.write(tmp, 0, n);
        }
        return new String(buf.toByteArray(), StandardCharsets.UTF_8);
    }

    private static String error(String m) {
        return "{\"error\":" + ProfileApi.quote(m) + "}";
    }

    private static void send(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }
}