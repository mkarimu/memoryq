package com.vstream.Vidiserver;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Counts the viewers of each channel. A viewer app reports "I am watching" every few seconds;
 * a viewer counts for 10 seconds after its last report.
 */
public final class ViewerTracker {

    private static final long TTL_MS = 10000;
    private static final int MAX_PER_CHANNEL = 5000;

    private final LongSupplier clock;
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, Long>> seen =
            new ConcurrentHashMap<String, ConcurrentHashMap<String, Long>>();

    public ViewerTracker() {
        this(System::currentTimeMillis);
    }

    public ViewerTracker(LongSupplier clock) {
        this.clock = clock;
    }

    public void ping(String channelId, String viewerId) {
        ConcurrentHashMap<String, Long> viewers = seen.get(channelId);
        if (viewers == null) {
            viewers = new ConcurrentHashMap<String, Long>();
            ConcurrentHashMap<String, Long> existing = seen.putIfAbsent(channelId, viewers);
            if (existing != null) {
                viewers = existing;
            }
        }
        long now = clock.getAsLong();
        if (viewers.size() >= MAX_PER_CHANNEL && !viewers.containsKey(viewerId)) {
            prune(viewers, now);
            if (viewers.size() >= MAX_PER_CHANNEL) {
                return;
            }
        }
        viewers.put(viewerId, now);
    }

    public int count(String channelId) {
        ConcurrentHashMap<String, Long> viewers = seen.get(channelId);
        if (viewers == null) {
            return 0;
        }
        prune(viewers, clock.getAsLong());
        return viewers.size();
    }

    private static void prune(Map<String, Long> viewers, final long now) {
        viewers.entrySet().removeIf(e -> now - e.getValue() > TTL_MS);
    }
}