package com.vstream.Vidiserver;

import java.security.SecureRandom;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/** Signed-in sessions, kept in memory: a restart signs everyone out. */
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

    private static final long TTL_MS = 7L * 24 * 60 * 60 * 1000;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final ConcurrentHashMap<String, Session> sessions = new ConcurrentHashMap<String, Session>();
    private final LongSupplier clock;

    public SessionStore() {
        this(System::currentTimeMillis);
    }

    public SessionStore(LongSupplier clock) {
        this.clock = clock;
    }

    public String create(String username, UserStore.Role role) {
        if (sessions.size() > 10000) {
            final long now = clock.getAsLong();
            sessions.values().removeIf(s -> s.expires < now);
        }
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        StringBuilder sb = new StringBuilder(64);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        String token = sb.toString();
        sessions.put(token, new Session(username, role, clock.getAsLong() + TTL_MS));
        return token;
    }

    public Session get(String token) {
        if (token == null || token.isEmpty()) {
            return null;
        }
        Session s = sessions.get(token);
        if (s == null) {
            return null;
        }
        if (s.expires < clock.getAsLong()) {
            sessions.remove(token);
            return null;
        }
        return s;
    }

    public void remove(String token) {
        if (token != null) {
            sessions.remove(token);
        }
    }

    public void removeUser(final String username) {
        sessions.values().removeIf(s -> s.username.equalsIgnoreCase(username));
    }
}