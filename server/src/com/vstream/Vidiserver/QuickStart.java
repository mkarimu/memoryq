package com.vstream.Vidiserver;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Vstream "Try it now - no account needed": while the admin has it on (for the Play Store's 14-day closed test), the
 * app can make a tester login by itself (tester_123456, its own random password, no email) and go live at once.
 * It switches itself off on the end date. Limits: 3 new testers per address an hour, 200 a day in all.
 *   GET  /api/vstream/quick          {on, until}
 *   POST /api/vstream/quick          -> {token, username, role, password}   (the app keeps the password for next time)
 *   GET/POST /api/admin/vstream-quick   on=1|0, days=N                        (admin)
 */
public final class QuickStart {

    static final int PER_IP_HOUR = 3;
    static final int PER_DAY = 200;

    private final UserStore users;
    private final SessionStore sessions;
    private final LongSupplier clock;
    private final Path file;
    private final SecureRandom random = new SecureRandom();
    private boolean on;
    private long until;
    private final Map<String, Deque<Long>> byIp = new HashMap<String, Deque<Long>>();
    private final Deque<Long> all = new ArrayDeque<Long>();

    public QuickStart(UserStore users, SessionStore sessions, LongSupplier clock, Path file) {
        this.users = users;
        this.sessions = sessions;
        this.clock = clock;
        this.file = file;
    }

    public synchronized void init() {
        if (file == null || !Files.isRegularFile(file)) {
            return;
        }
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String[] kv = line.split("\t", 2);
                if (kv.length == 2 && "on".equals(kv[0])) {
                    on = "1".equals(kv[1].trim());
                } else if (kv.length == 2 && "until".equals(kv[0]) && kv[1].trim().matches("\\d+")) {
                    until = Long.parseLong(kv[1].trim());
                }
            }
        } catch (IOException e) {
            System.out.println("[quick] " + e.getMessage());
        }
    }

    private void save() {
        if (file == null) {
            return;
        }
        try {
            Files.write(file, ("on\t" + (on ? "1" : "0") + "\nuntil\t" + until + "\n").getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            System.out.println("[quick] could not save: " + e.getMessage());
        }
    }

    /** On, and the end date not passed. */
    public synchronized boolean isOn() {
        return on && clock.getAsLong() < until;
    }

    public synchronized void set(boolean wantOn, int days) {
        on = wantOn;
        if (wantOn) {
            until = clock.getAsLong() + Math.max(1, Math.min(90, days)) * 86400000L;
        }
        save();
    }

    /** A new tester login, or null with the reason in why[0]. */
    synchronized String[] newTester(String ip, String[] why) {
        long now = clock.getAsLong();
        if (!isOn()) {
            why[0] = "Trying without an account has ended - create a free account (it takes a minute).";
            return null;
        }
        Deque<Long> mine = byIp.computeIfAbsent(ip == null ? "" : ip, k -> new ArrayDeque<Long>());
        while (!mine.isEmpty() && now - mine.peekFirst() > 3600000L) {
            mine.pollFirst();
        }
        while (!all.isEmpty() && now - all.peekFirst() > 86400000L) {
            all.pollFirst();
        }
        if (mine.size() >= PER_IP_HOUR || all.size() >= PER_DAY) {
            why[0] = "Too many new testers right now - try again later, or create a free account.";
            return null;
        }
        String abc = "abcdefghjkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        for (int tries = 0; tries < 20; tries++) {
            String name = "tester_" + (100000 + random.nextInt(900000));
            if (users.info(name) != null) {
                continue;
            }
            StringBuilder pw = new StringBuilder();
            for (int i = 0; i < 16; i++) {
                pw.append(abc.charAt(random.nextInt(abc.length())));
            }
            try {
                users.createUser(name, pw.toString(), UserStore.Role.CHATTER);
            } catch (UserStore.UserException e) {
                continue;
            }
            mine.addLast(now);
            all.addLast(now);
            System.out.println("[quick] new tester " + name);
            return new String[]{name, pw.toString()};
        }
        why[0] = "Could not make a tester login - please try again.";
        return null;
    }

    public void register(HttpServer server) {
        server.createContext("/api/vstream/quick", this::handle);
        server.createContext("/api/admin/vstream-quick", this::handleAdmin);
    }

    private synchronized String stateJson() {
        return "{\"on\":" + isOn() + ",\"until\":" + until + "}";
    }

    private void handle(HttpExchange ex) throws IOException {
        try {
            if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
                send(ex, 200, stateJson());
                return;
            }
            String ip = ex.getRemoteAddress() == null ? "" : ex.getRemoteAddress().getAddress().getHostAddress();
            String fwd = ex.getRequestHeaders().getFirst("X-Forwarded-For");
            if (fwd != null && !fwd.trim().isEmpty() && (ip.startsWith("127.") || "0:0:0:0:0:0:0:1".equals(ip))) {
                ip = fwd.split(",")[0].trim(); // behind Caddy
            }
            String[] why = new String[1];
            String[] made = newTester(ip, why);
            if (made == null) {
                send(ex, 403, "{\"error\":" + ProfileApi.quote(why[0]) + "}");
                return;
            }
            String token = sessions.create(made[0], UserStore.Role.CHATTER);
            send(ex, 200, "{\"token\":" + ProfileApi.quote(token) + ",\"username\":" + ProfileApi.quote(made[0])
                    + ",\"role\":\"CHATTER\",\"password\":" + ProfileApi.quote(made[1]) + ",\"tester\":true}");
        } finally {
            ex.close();
        }
    }

    private void handleAdmin(HttpExchange ex) throws IOException {
        try {
            String auth = ex.getRequestHeaders().getFirst("Authorization");
            String token = auth != null && auth.startsWith("Bearer ") ? auth.substring(7).trim() : "";
            SessionStore.Session s = token.isEmpty() ? null : sessions.get(token);
            UserStore.Info me = s == null ? null : users.info(s.username);
            if (me == null || me.role != UserStore.Role.ADMIN) {
                send(ex, 403, "{\"error\":\"admins only\"}");
                return;
            }
            if ("POST".equalsIgnoreCase(ex.getRequestMethod())) {
                String body = new String(AudioTools.readAll(ex.getRequestBody()), StandardCharsets.UTF_8);
                String days = MediaAuthHttpServer.queryParam(body, "days");
                int d = 21;
                try {
                    d = days == null ? 21 : Integer.parseInt(days.trim());
                } catch (NumberFormatException e) {
                    // 21
                }
                set("1".equals(MediaAuthHttpServer.queryParam(body, "on")), d);
                System.out.println("[quick] Vstream quick start " + (isOn() ? "ON for " + d + " days" : "OFF"));
            }
            send(ex, 200, stateJson());
        } finally {
            ex.close();
        }
    }

    private static void send(HttpExchange ex, int status, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(status, b.length);
        ex.getResponseBody().write(b);
    }
}