package com.vstream.Vidiserver;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Signed-in sessions. With a file (sessions.txt) they survive restarts and updates; the file keeps only a fingerprint
 * (SHA-256) of each sign-in, never the sign-in itself.
 * How long a sign-in lasts: "remember me for 30 days" = 30 days, not remembered = 1 day, older apps that do not ask = 7 days.
 */
public final class SessionStore {

    public static final class Session {
        public final String username;
        public final UserStore.Role role;
        final long expires;

        Session(String username, UserStore.Role role, long expires) {
            this.username = username;
            this.role = role;
            this.expires = expires;
        }
    }

    public static final long DAY_MS = 24L * 60 * 60 * 1000;
    public static final long TTL_MS = 7 * DAY_MS;
    public static final long REMEMBER_MS = 30 * DAY_MS;
    public static final long SHORT_MS = DAY_MS;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final ConcurrentHashMap<String, Session> sessions = new ConcurrentHashMap<String, Session>(); // fingerprint -> session
    private final LongSupplier clock;
    private volatile Path file;
    private volatile boolean dirty;

    public SessionStore() {
        this(System::currentTimeMillis);
    }

    public SessionStore(LongSupplier clock) {
        this.clock = clock;
    }

    /** How long a sign-in lasts for a "remember" answer: "1" = 30 days, "0" = 1 day, nothing (older apps) = 7 days. */
    public static long ttlFor(String remember) {
        if ("1".equals(remember) || "true".equals(remember) || "on".equals(remember)) {
            return REMEMBER_MS;
        }
        if ("0".equals(remember) || "false".equals(remember) || "off".equals(remember)) {
            return SHORT_MS;
        }
        return TTL_MS;
    }

    static String fingerprint(String token) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : h) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static String newToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        StringBuilder sb = new StringBuilder(64);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /** Keeps the sessions in this file from now on (and reads the ones already in it). */
    public synchronized void persistTo(Path f) {
        this.file = f;
        if (f == null || !Files.isRegularFile(f)) {
            return;
        }
        long now = clock.getAsLong();
        try {
            for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                String[] p = line.split("\t");
                if (p.length < 4 || p[0].length() != 64) {
                    continue;
                }
                try {
                    long exp = Long.parseLong(p[3]);
                    if (exp > now) {
                        sessions.put(p[0], new Session(p[1], UserStore.Role.valueOf(p[2]), exp));
                    }
                } catch (RuntimeException e) {
                    // a damaged line
                }
            }
        } catch (IOException e) {
            System.out.println("[sessions] could not read " + f + ": " + e.getMessage());
        }
    }

    public synchronized void flushIfDirty() {
        Path f = file;
        if (!dirty || f == null) {
            return;
        }
        long now = clock.getAsLong();
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Session> e : sessions.entrySet()) {
            Session s = e.getValue();
            if (s.expires > now) {
                sb.append(e.getKey()).append('\t').append(s.username).append('\t').append(s.role.name()).append('\t').append(s.expires).append('\n');
            }
        }
        try {
            Path part = f.resolveSibling(f.getFileName() + ".part");
            Files.write(part, sb.toString().getBytes(StandardCharsets.UTF_8));
            Files.move(part, f, StandardCopyOption.REPLACE_EXISTING);
            dirty = false;
        } catch (IOException e) {
            System.out.println("[sessions] could not save: " + e.getMessage());
        }
    }

    public String create(String username, UserStore.Role role) {
        return create(username, role, TTL_MS);
    }

    public String create(String username, UserStore.Role role, long ttlMs) {
        final long now = clock.getAsLong();
        if (sessions.size() > 20000) {
            sessions.values().removeIf(s -> s.expires < now);
        }
        String token = newToken();
        sessions.put(fingerprint(token), new Session(username, role, now + Math.max(60000L, ttlMs)));
        dirty = true;
        if (file != null) {
            flushIfDirty();
        }
        return token;
    }

    public Session get(String token) {
        if (token == null || token.isEmpty()) {
            return null;
        }
        String k = fingerprint(token);
        Session s = sessions.get(k);
        if (s == null) {
            return null;
        }
        if (s.expires < clock.getAsLong()) {
            sessions.remove(k);
            dirty = true;
            return null;
        }
        return s;
    }

    /** How long this sign-in still lasts (ms), 0 if it is not valid. */
    public long remaining(String token) {
        Session s = get(token);
        return s == null ? 0 : s.expires - clock.getAsLong();
    }

    public void remove(String token) {
        if (token != null && sessions.remove(fingerprint(token)) != null) {
            dirty = true;
            if (file != null) {
                flushIfDirty();
            }
        }
    }

    public void removeUser(final String username) {
        if (sessions.values().removeIf(s -> s.username.equalsIgnoreCase(username))) {
            dirty = true;
            if (file != null) {
                flushIfDirty();
            }
        }
    }
}