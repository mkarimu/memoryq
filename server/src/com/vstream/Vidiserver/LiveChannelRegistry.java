package com.vstream.Vidiserver;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicInteger;

public class LiveChannelRegistry {

    private static final SecureRandom RANDOM = new SecureRandom();

    private static final Comparator<String> ID_ORDER = new Comparator<String>() {
        @Override
        public int compare(String a, String b) {
            if (a.length() != b.length()) {
                return Integer.compare(a.length(), b.length());
            }
            return a.compareTo(b);
        }
    };

    private final ConcurrentSkipListMap<String, LiveChannel> channels =
            new ConcurrentSkipListMap<String, LiveChannel>(ID_ORDER);

    private final AtomicInteger idCounter = new AtomicInteger(0);

    /** 24 random bytes as 48 hex characters. */
    static String newToken() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        StringBuilder sb = new StringBuilder(48);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /**
     * Creates a LIVE channel with a server-generated ID and a random publish token.
     * Returns the channel ID. Read the token with getChannel(id).getPublishToken().
     */
    public String registerChannel(String broadcasterId, String streamAddress) {
        String channelId;
        do {
            channelId = String.format("CHANNEL-%03d", idCounter.incrementAndGet());
        } while (channels.containsKey(channelId));
        LiveChannel channel = new LiveChannel(channelId, broadcasterId, streamAddress);
        channel.setLive(true);
        channel.setPublishToken(newToken());
        channels.put(channelId, channel);
        return channelId;
    }

    /** Registers a channel with a chosen ID and key (used for saved accounts). Keeps the ID counter above it. */
    public synchronized String registerChannelWithId(String channelId, String broadcasterId,
                                                     String streamAddress, String token) {
        LiveChannel channel = new LiveChannel(channelId, broadcasterId, streamAddress);
        channel.setLive(true);
        channel.setPublishToken(token);
        channels.put(channelId, channel);
        try {
            int n = Integer.parseInt(channelId.substring("CHANNEL-".length()));
            int current;
            while ((current = idCounter.get()) < n && !idCounter.compareAndSet(current, n)) {
                // retry
            }
        } catch (RuntimeException e) {
            // an ID that is not CHANNEL-nnn does not move the counter
        }
        return channelId;
    }

    /** Registers a pool channel without moving the ID counter. */
    public synchronized void registerPoolChannel(String channelId, String ownerId,
                                                 String streamAddress, String token) {
        LiveChannel channel = new LiveChannel(channelId, ownerId, streamAddress);
        channel.setLive(true);
        channel.setPublishToken(token);
        channels.put(channelId, channel);
    }

    public boolean removeChannel(String channelId) {
        if (channelId == null) {
            return false;
        }
        return channels.remove(channelId) != null;
    }

    public LiveChannel getChannel(String channelId) {
        if (channelId == null) {
            return null;
        }
        return channels.get(channelId);
    }

    public List<LiveChannel> getAllChannels() {
        return new ArrayList<LiveChannel>(channels.values());
    }

    public boolean channelExists(String channelId) {
        if (channelId == null) {
            return false;
        }
        return channels.containsKey(channelId);
    }

    public boolean updateActivity(String channelId) {
        LiveChannel channel = getChannel(channelId);
        if (channel == null) {
            return false;
        }
        channel.updateActivity();
        return true;
    }

    public boolean markOffline(String channelId) {
        LiveChannel channel = getChannel(channelId);
        if (channel == null) {
            return false;
        }
        channel.setLive(false);
        return true;
    }

    public List<LiveChannel> getLiveChannels() {
        List<LiveChannel> result = new ArrayList<LiveChannel>();
        for (Map.Entry<String, LiveChannel> entry : channels.entrySet()) {
            if (entry.getValue().isLive()) {
                result.add(entry.getValue());
            }
        }
        return result;
    }
}