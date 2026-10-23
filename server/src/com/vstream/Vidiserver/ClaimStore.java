package com.vstream.Vidiserver;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Predicate;

/**
 * A pool of public channels that signed-in users can claim. A claim belongs to an account
 * (so any device signed in to it gets the same channel and key) and ends when the user
 * releases it, the admin takes it back, or it goes unused for the time to live.
 * Keys of unclaimed channels are random secrets that nobody is ever shown.
 */
public final class ClaimStore {

    public static final class ClaimException extends Exception {
        private static final long serialVersionUID = 1L;

        public ClaimException(String message) {
            super(message);
        }
    }

    public static final class Claim {
        public final String channelId;
        public final String username;
        final String token;
        public final long claimedAt;
        public long lastUsed;

        Claim(String channelId, String username, String token, long claimedAt, long lastUsed) {
            this.channelId = channelId;
            this.username = username;
            this.token = token;
            this.claimedAt = claimedAt;
            this.lastUsed = lastUsed;
        }

        /** "CHANNEL-102?token=..." - treat as secret. */
        public String streamKey() {
            return channelId + "?token=" + token;
        }

        Claim copy() {
            return new Claim(channelId, username, token, claimedAt, lastUsed);
        }
    }

    public static final class PoolEntry {
        public final String channelId;
        public final String holder;
        public final long lastUsed;
        public final long expiresAt;

        PoolEntry(String channelId, String holder, long lastUsed, long expiresAt) {
            this.channelId = channelId;
            this.holder = holder;
            this.lastUsed = lastUsed;
            this.expiresAt = expiresAt;
        }
    }

    private final Path file;
    private final LiveChannelRegistry registry;
    private final Consumer<String> kick;
    private final long ttlMs;
    private final long warnMs;
    private final LongSupplier clock;

    private final List<String> poolIds = new ArrayList<String>();
    private final Map<String, Claim> claims = new LinkedHashMap<String, Claim>();
    private final Map<String, String> notices = new LinkedHashMap<String, String>();
    private boolean dirty = false;

    public ClaimStore(Path file, LiveChannelRegistry registry, Consumer<String> kick,
                      long ttlMs, long warnMs, LongSupplier clock) {
        this.file = file;
        this.registry = registry;
        this.kick = kick;
        this.ttlMs = ttlMs;
        this.warnMs = warnMs;
        this.clock = clock;
    }

    public long ttlMs() {
        return ttlMs;
    }

    public long warnMs() {
        return warnMs;
    }

    public long now() {
        return clock.getAsLong();
    }

    /** Loads saved claims and registers the pool channels (CHANNEL-firstId ... CHANNEL-firstId+size-1). */
    public synchronized void init(int firstId, int size) throws IOException {
        poolIds.clear();
        claims.clear();
        notices.clear();
        for (int i = 0; i < size; i++) {
            poolIds.add(String.format("CHANNEL-%03d", firstId + i));
        }
        if (Files.exists(file)) {
            load();
        }
        Iterator<Claim> it = claims.values().iterator();
        while (it.hasNext()) {
            Claim c = it.next();
            if (!poolIds.contains(c.channelId)) {
                notices.put(c.username, "Your channel " + c.channelId + " is no longer available.");
                it.remove();
            }
        }
        for (String id : poolIds) {
            Claim c = claims.get(id);
            registry.registerPoolChannel(id, c != null ? c.username : "pool",
                    StreamUrls.hlsPlayback(id), c != null ? c.token : LiveChannelRegistry.newToken());
        }
        save();
    }

    // ---- Queries ----

    public synchronized List<PoolEntry> pool() {
        List<PoolEntry> result = new ArrayList<PoolEntry>();
        for (String id : poolIds) {
            Claim c = claims.get(id);
            result.add(c == null ? new PoolEntry(id, null, 0, 0)
                    : new PoolEntry(id, c.username, c.lastUsed, c.lastUsed + ttlMs));
        }
        return result;
    }

    public synchronized Claim claimOf(String username) {
        Claim c = claimOfLocked(username);
        return c == null ? null : c.copy();
    }

    public synchronized String noticeFor(String username) {
        return notices.get(username);
    }

    public synchronized void ackNotice(String username) {
        if (notices.remove(username) != null) {
            saveQuietly();
        }
    }

    // ---- Changes ----

    public synchronized Claim claim(String username, String channelId) throws ClaimException {
        if (!poolIds.contains(channelId)) {
            throw new ClaimException("that channel is not in the pool");
        }
        if (claimOfLocked(username) != null) {
            throw new ClaimException("you already have a channel - release it first");
        }
        if (claims.containsKey(channelId)) {
            throw new ClaimException("that channel is already taken");
        }
        long now = clock.getAsLong();
        Claim c = new Claim(channelId, username, LiveChannelRegistry.newToken(), now, now);
        claims.put(channelId, c);
        String oldNotice = notices.remove(username);
        registry.registerPoolChannel(channelId, username, StreamUrls.hlsPlayback(channelId), c.token);
        try {
            save();
        } catch (IOException e) {
            claims.remove(channelId);
            if (oldNotice != null) {
                notices.put(username, oldNotice);
            }
            registry.registerPoolChannel(channelId, "pool", StreamUrls.hlsPlayback(channelId),
                    LiveChannelRegistry.newToken());
            throw new ClaimException("could not save the claim: " + e.getMessage());
        }
        return c.copy();
    }

    /** The holder gives the channel back. No notice is left. */
    public synchronized void release(String username) throws ClaimException {
        Claim c = claimOfLocked(username);
        if (c == null) {
            throw new ClaimException("you have no channel to release");
        }
        releaseLocked(c, null);
        saveQuietly();
    }

    /** Takes back the channel of a user (for example a disabled account) and leaves them a notice. */
    public synchronized void releaseByUser(String username, String reason) {
        Claim c = claimOfLocked(username);
        if (c != null) {
            releaseLocked(c, reason);
            saveQuietly();
        }
    }

    /** The admin takes a channel back. Returns false if it was not claimed. */
    public synchronized boolean releaseChannel(String channelId, String reason) {
        Claim c = claims.get(channelId);
        if (c == null) {
            return false;
        }
        releaseLocked(c, reason);
        saveQuietly();
        return true;
    }

    /** Called after an allowed publish: restarts the unused-time clock. */
    public synchronized void touch(String channelId) {
        Claim c = claims.get(channelId);
        if (c != null) {
            c.lastUsed = clock.getAsLong();
            dirty = true;
        }
    }

    /** Run about once a minute: live channels stay claimed, long-unused ones are released. */
    public synchronized void tick(Predicate<String> isLive) {
        long now = clock.getAsLong();
        List<Claim> expired = new ArrayList<Claim>();
        for (Claim c : claims.values()) {
            if (isLive.test(c.channelId)) {
                c.lastUsed = now;
                dirty = true;
            } else if (now - c.lastUsed >= ttlMs) {
                expired.add(c);
            }
        }
        for (Claim c : expired) {
            releaseLocked(c, "Your channel " + c.channelId + " was released because it was not used for "
                    + (ttlMs / 3600000L) + " hours.");
            dirty = true;
        }
        if (dirty) {
            saveQuietly();
        }
    }

    // ---- Helpers ----

    private Claim claimOfLocked(String username) {
        for (Claim c : claims.values()) {
            if (c.username.equalsIgnoreCase(username)) {
                return c;
            }
        }
        return null;
    }

    private void releaseLocked(Claim c, String notice) {
        claims.remove(c.channelId);
        registry.registerPoolChannel(c.channelId, "pool", StreamUrls.hlsPlayback(c.channelId),
                LiveChannelRegistry.newToken());
        kick.accept(c.channelId);
        if (notice != null) {
            notices.put(c.username, notice);
        }
    }

    // ---- Storage ----

    private void load() throws IOException {
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (line.trim().isEmpty() || line.startsWith("#")) {
                continue;
            }
            String[] p = line.split("\t", -1);
            try {
                if (p[0].equals("C") && p.length >= 6) {
                    claims.put(p[1], new Claim(p[1], p[2], p[3], Long.parseLong(p[4]), Long.parseLong(p[5])));
                } else if (p[0].equals("N") && p.length >= 3) {
                    notices.put(p[1], p[2]);
                }
            } catch (NumberFormatException e) {
                // skip a damaged line
            }
        }
    }

    private static String clean(String text) {
        return text.replace('\t', ' ').replace('\r', ' ').replace('\n', ' ');
    }

    private void saveQuietly() {
        try {
            save();
        } catch (IOException e) {
            System.out.println("[claims] could not save: " + e.getMessage());
        }
    }

    private void save() throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("# VStream channel claims - keep private. C: channel, user, key, claimed, last used. N: user, notice\n");
        for (Claim c : claims.values()) {
            sb.append("C\t").append(c.channelId).append('\t').append(c.username).append('\t')
                    .append(c.token).append('\t').append(c.claimedAt).append('\t')
                    .append(c.lastUsed).append('\n');
        }
        for (Map.Entry<String, String> n : notices.entrySet()) {
            sb.append("N\t").append(n.getKey()).append('\t').append(clean(n.getValue())).append('\n');
        }
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.write(tmp, sb.toString().getBytes(StandardCharsets.UTF_8));
        try {
            Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException | IOException e) {
            // not a POSIX file system (for example Windows)
        }
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
        dirty = false;
    }
}