package com.vstream.Vidiserver;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Old listening addresses keep working after the move to Memoryq. Listeners' players, Radio.garden, TuneIn and
 * websites still use the old links; Memoryq plays the right station on them directly (no redirect, so players that
 * do not follow redirects work too):
 *   - Centova:   http://184.75.212.211:PORT/anything  -> the station imported from the Centova account on that port
 *                (the address is moved to this server; ports are opened as soon as it is there)
 *   - /listen/NAME/anything -> the station named NAME (Azuracast-style links, for a domain sent here later)
 * Imported accounts give the rules (their old port and their Centova username as NAME); the admin can add ports,
 * change a station or switch a rule off (Admin - Radio - Import - Old listening links). The address is put on this
 * server with oldlinks.sh (served at /radio/oldlinks.sh, run as root on the VPS).
 *   GET /listen/NAME/...                     the station
 *   GET /api/admin/old-links/list            POST save (kind, key, station) | delete (kind, key)
 */
public final class OldLinks {

    private final RadioApi api;
    private final CentovaImport importer;
    private final RadioService radio;
    private final UserStore users;
    private final SessionStore sessions;
    private final Path file;
    private final String ip;
    /** Rules the admin set: "port|8005" or "name|mkarimu" -> station (0 = switched off). */
    private final Map<String, Long> manual = new LinkedHashMap<String, Long>();
    private final Map<Integer, HttpServer> open = new TreeMap<Integer, HttpServer>();
    private final Map<Integer, String> problems = new TreeMap<Integer, String>();
    private volatile boolean ipHere;
    private ScheduledExecutorService timer;

    public static final class Rule {
        public String kind = "";
        public String key = "";
        public long station;
        public boolean auto;
    }

    public OldLinks(RadioApi api, CentovaImport importer, RadioService radio, UserStore users, SessionStore sessions, Path file, String ip) {
        this.api = api;
        this.importer = importer;
        this.radio = radio;
        this.users = users;
        this.sessions = sessions;
        this.file = file;
        this.ip = ip == null ? "" : ip.trim();
        try {
            if (Files.isRegularFile(file)) {
                for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    String[] v = line.split("\t");
                    if (v.length == 3 && ("port".equals(v[0]) || "name".equals(v[0])) && v[2].matches("\\d{1,9}")) {
                        manual.put(v[0] + "|" + v[1], Long.parseLong(v[2]));
                    }
                }
            }
        } catch (IOException e) {
            System.out.println("[old links] could not read " + file + ": " + e.getMessage());
        }
    }

    public void register(HttpServer server) {
        server.createContext("/listen/", this::handleListen);
        server.createContext("/api/admin/old-links/", this::handleAdmin);
        server.createContext("/radio/oldlinks.sh", ex -> {
            try {
                byte[] b = OldLinksScript.TEXT.getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
                ex.getResponseHeaders().set("Cache-Control", "no-store");
                ex.sendResponseHeaders(200, b.length);
                ex.getResponseBody().write(b);
            } finally {
                ex.close();
            }
        });
    }

    /** Opens the old Centova ports when the old address is on this server (checked every minute). */
    public synchronized void start() {
        if (timer != null || ip.isEmpty()) {
            return;
        }
        timer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "old-links");
            t.setDaemon(true);
            return t;
        });
        timer.scheduleWithFixedDelay(() -> {
            try {
                refresh();
            } catch (RuntimeException e) {
                System.out.println("[old links] " + e);
            }
        }, 5, 60, TimeUnit.SECONDS);
    }

    public synchronized void stop() {
        if (timer != null) {
            timer.shutdownNow();
            timer = null;
        }
        for (HttpServer s : open.values()) {
            s.stop(0);
        }
        open.clear();
    }

    // ---- The rules ----

    /** All rules: from the imported Centova accounts, then the admin's own (which win). */
    public synchronized List<Rule> rules() {
        Map<String, Rule> out = new LinkedHashMap<String, Rule>();
        try {
            for (Map.Entry<String, long[]> e : importer.importedStations().entrySet()) {
                long station = e.getValue()[0];
                Rule n = new Rule();
                n.kind = "name";
                n.key = e.getKey().toLowerCase(Locale.ROOT);
                n.station = station;
                n.auto = true;
                out.put("name|" + n.key, n);
                if (e.getValue()[1] > 0) {
                    Rule p = new Rule();
                    p.kind = "port";
                    p.key = String.valueOf(e.getValue()[1]);
                    p.station = station;
                    p.auto = true;
                    out.put("port|" + p.key, p);
                }
            }
        } catch (IOException e) {
            System.out.println("[old links] the import list could not be read: " + e.getMessage());
        }
        for (Map.Entry<String, Long> e : manual.entrySet()) {
            String[] k = e.getKey().split("\\|", 2);
            Rule r = out.get(e.getKey());
            if (r == null) {
                r = new Rule();
                r.kind = k[0];
                r.key = k[1];
                out.put(e.getKey(), r);
            }
            r.station = e.getValue();
            r.auto = false;
        }
        return new ArrayList<Rule>(out.values());
    }

    /** The station for an old address, or 0. */
    long stationFor(String kind, String key) {
        String k = key == null ? "" : key.trim().toLowerCase(Locale.ROOT);
        for (Rule r : rules()) {
            if (r.kind.equals(kind) && r.key.equals(k)) {
                return r.station > 0 && radio.store().station(r.station) != null ? r.station : 0;
            }
        }
        return 0;
    }

    public synchronized void save(String kind, String key, long station) throws IOException {
        String k = key == null ? "" : key.trim().toLowerCase(Locale.ROOT);
        if ("port".equals(kind)) {
            if (!k.matches("\\d{2,5}") || Integer.parseInt(k) < 1024 || Integer.parseInt(k) > 65535) {
                throw new IllegalArgumentException("a port is a number from 1024 to 65535");
            }
        } else if ("name".equals(kind)) {
            if (!k.matches("[a-z0-9_.-]{1,60}")) {
                throw new IllegalArgumentException("a name has letters, numbers, - _ or . only (the part after /listen/)");
            }
        } else {
            throw new IllegalArgumentException("unknown kind of address");
        }
        if (station != 0 && radio.store().station(station) == null) {
            throw new IllegalArgumentException("no such station");
        }
        manual.put(kind + "|" + k, station);
        write();
        if ("port".equals(kind)) {
            refresh();
        }
    }

    /** Removes the admin's rule (an imported one comes back); for imported ones without an admin rule, switches it off. */
    public synchronized void delete(String kind, String key) throws IOException {
        String k = kind + "|" + (key == null ? "" : key.trim().toLowerCase(Locale.ROOT));
        if (manual.remove(k) == null) {
            manual.put(k, 0L);
        }
        write();
        refresh();
    }

    private void write() throws IOException {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Long> e : manual.entrySet()) {
            String[] k = e.getKey().split("\\|", 2);
            sb.append(k[0]).append('\t').append(k[1]).append('\t').append(e.getValue()).append('\n');
        }
        Files.createDirectories(file.toAbsolutePath().getParent());
        Files.write(file, sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    // ---- The old Centova ports ----

    /** Whether the old address is on one of this server's network cards. */
    boolean ipPresent() {
        try {
            InetAddress want = InetAddress.getByName(ip);
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a.equals(want)) {
                        return true;
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            return false;
        }
        return false;
    }

    /** Opens the ports that have a station, closes the others. */
    public synchronized void refresh() {
        boolean here = ipPresent();
        if (here != ipHere) {
            System.out.println("[old links] the old address " + ip + (here ? " is on this server - opening the old ports"
                    : " is not on this server - old ports closed"));
        }
        ipHere = here;
        java.util.Set<Integer> want = new java.util.TreeSet<Integer>();
        if (here) {
            for (Rule r : rules()) {
                if ("port".equals(r.kind) && r.station > 0 && radio.store().station(r.station) != null) {
                    want.add(Integer.parseInt(r.key));
                }
            }
        }
        for (Integer p : new ArrayList<Integer>(open.keySet())) {
            if (!want.contains(p)) {
                open.remove(p).stop(0);
            }
        }
        problems.keySet().retainAll(want);
        for (Integer p : want) {
            if (open.containsKey(p)) {
                continue;
            }
            try {
                HttpServer s = HttpServer.create(new InetSocketAddress(InetAddress.getByName(ip), p), 50);
                final int port = p;
                s.createContext("/", ex -> handlePort(ex, port));
                s.setExecutor(Executors.newCachedThreadPool(r -> {
                    Thread t = new Thread(r, "old-port-" + port);
                    t.setDaemon(true);
                    return t;
                }));
                s.start();
                open.put(p, s);
                problems.remove(p);
                System.out.println("[old links] listening on " + ip + ":" + p);
            } catch (IOException | RuntimeException e) {
                problems.put(p, e.getMessage() == null ? e.toString() : e.getMessage());
            }
        }
    }

    private void handlePort(HttpExchange ex, int port) throws IOException {
        try {
            String path = ex.getRequestURI().getPath();
            if (path.equals("/favicon.ico") || path.equals("/robots.txt")) {
                text(ex, 404, "not found");
                return;
            }
            long station = stationFor("port", String.valueOf(port));
            if (station == 0) {
                text(ex, 404, "This old address has no station any more.");
                return;
            }
            if (path.toLowerCase(Locale.ROOT).matches(".*\\.(pls|m3u)$")) {
                playlist(ex, "http://" + ip + ":" + port + "/stream", radio.store().station(station).name);
                return;
            }
            api.serveStation(ex, station);
        } finally {
            ex.close();
        }
    }

    // ---- Azuracast links: /listen/NAME/... ----

    private void handleListen(HttpExchange ex) throws IOException {
        try {
            String[] parts = ex.getRequestURI().getPath().split("/");
            String name = parts.length > 2 ? parts[2] : "";
            long station = stationFor("name", name);
            if (station == 0) {
                text(ex, 404, "No station at this address.");
                return;
            }
            String last = parts.length > 3 ? parts[parts.length - 1].toLowerCase(Locale.ROOT) : "";
            if (last.endsWith(".pls") || last.endsWith(".m3u")) {
                String host = String.valueOf(ex.getRequestHeaders().getFirst("Host"));
                playlist(ex, "https://" + (host.matches("[A-Za-z0-9.-]+") ? host : "memoryq.com") + "/listen/" + name + "/stream",
                        radio.store().station(station).name);
                return;
            }
            api.serveStation(ex, station);
        } finally {
            ex.close();
        }
    }

    private static void playlist(HttpExchange ex, String url, String name) throws IOException {
        byte[] b = ("#EXTM3U\n#EXTINF:-1," + name + "\n" + url + "\n").getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "audio/x-mpegurl");
        ex.sendResponseHeaders(200, b.length);
        ex.getResponseBody().write(b);
    }

    private static void text(HttpExchange ex, int status, String t) throws IOException {
        byte[] b = t.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        ex.sendResponseHeaders(status, b.length);
        ex.getResponseBody().write(b);
    }

    // ---- The admin ----

    private void handleAdmin(HttpExchange ex) throws IOException {
        try {
            String auth = ex.getRequestHeaders().getFirst("Authorization");
            String token = auth != null && auth.startsWith("Bearer ") ? auth.substring(7).trim() : "";
            SessionStore.Session s = token.isEmpty() ? null : sessions.get(token);
            UserStore.Info me = s == null ? null : users.info(s.username);
            if (me == null || me.role != UserStore.Role.ADMIN) {
                json(ex, 403, "{\"error\":\"admins only\"}");
                return;
            }
            String op = ex.getRequestURI().getPath().substring("/api/admin/old-links/".length());
            boolean post = "POST".equalsIgnoreCase(ex.getRequestMethod());
            String body = post ? new String(AudioTools.readAll(ex.getRequestBody()), StandardCharsets.UTF_8) : "";
            try {
                if ("save".equals(op) && post) {
                    String st = MediaAuthHttpServer.queryParam(body, "station");
                    save(String.valueOf(MediaAuthHttpServer.queryParam(body, "kind")), MediaAuthHttpServer.queryParam(body, "key"),
                            st != null && st.trim().matches("\\d{1,9}") ? Long.parseLong(st.trim()) : -1);
                } else if ("delete".equals(op) && post) {
                    delete(String.valueOf(MediaAuthHttpServer.queryParam(body, "kind")), MediaAuthHttpServer.queryParam(body, "key"));
                } else if (!"list".equals(op)) {
                    json(ex, 404, "{\"error\":\"not found\"}");
                    return;
                }
            } catch (IllegalArgumentException e) {
                json(ex, 400, "{\"error\":" + ProfileApi.quote(e.getMessage()) + "}");
                return;
            }
            json(ex, 200, listJson());
        } finally {
            ex.close();
        }
    }

    synchronized String listJson() {
        StringBuilder sb = new StringBuilder("{\"ip\":").append(ProfileApi.quote(ip)).append(",\"ipHere\":").append(ipPresent())
                .append(",\"rules\":[");
        int i = 0;
        for (Rule r : rules()) {
            RadioStore.Station st = r.station > 0 ? radio.store().station(r.station) : null;
            String status;
            if ("name".equals(r.kind)) {
                status = st == null ? "off" : "ready";
            } else if (st == null) {
                status = "off";
            } else if (open.containsKey(Integer.parseInt(r.key))) {
                status = "listening";
            } else if (problems.containsKey(Integer.parseInt(r.key))) {
                status = "port problem: " + problems.get(Integer.parseInt(r.key));
            } else {
                status = "waiting for the address " + ip;
            }
            sb.append(i++ > 0 ? "," : "").append("{\"kind\":").append(ProfileApi.quote(r.kind)).append(",\"key\":").append(ProfileApi.quote(r.key))
                    .append(",\"station\":").append(r.station).append(",\"stationName\":").append(ProfileApi.quote(st == null ? "" : st.name))
                    .append(",\"auto\":").append(r.auto).append(",\"status\":").append(ProfileApi.quote(status)).append('}');
        }
        sb.append("],\"stations\":[");
        i = 0;
        for (RadioStore.Station st : radio.store().stations()) {
            sb.append(i++ > 0 ? "," : "").append("{\"id\":").append(st.id).append(",\"name\":").append(ProfileApi.quote(st.name)).append('}');
        }
        return sb.append("]}").toString();
    }

    private static void json(HttpExchange ex, int status, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(status, b.length);
        ex.getResponseBody().write(b);
    }
}