package com.vstream.Vidiserver;

public class LiveChannel {

    private final String channelId;
    private final String broadcasterId;
    private volatile String streamAddress;
    private volatile boolean live;
    private final long startTime;
    private volatile long lastActivity;
    private volatile String publishToken;
    private volatile String title = "";

    public LiveChannel(String channelId, String broadcasterId, String streamAddress) {
        this(channelId, broadcasterId, streamAddress, true, System.currentTimeMillis());
    }

    public LiveChannel(String channelId, String broadcasterId, String streamAddress,
                       boolean live, long startTime) {
        this.channelId = channelId;
        this.broadcasterId = broadcasterId;
        this.streamAddress = streamAddress;
        this.live = live;
        this.startTime = startTime;
        this.lastActivity = startTime;
    }

    public String getChannelId() {
        return channelId;
    }

    public String getBroadcasterId() {
        return broadcasterId;
    }

    public String getStreamAddress() {
        return streamAddress;
    }

    public void setStreamAddress(String streamAddress) {
        this.streamAddress = streamAddress;
    }

    public boolean isLive() {
        return live;
    }

    public void setLive(boolean live) {
        this.live = live;
    }

    public long getStartTime() {
        return startTime;
    }

    public long getLastActivity() {
        return lastActivity;
    }

    public void setLastActivity(long lastActivity) {
        this.lastActivity = lastActivity;
    }

    public void updateActivity() {
        this.lastActivity = System.currentTimeMillis();
    }

    /** Secret the broadcaster must present when publishing. Never list or log it. */
    public String getPublishToken() {
        return publishToken;
    }

    public void setPublishToken(String publishToken) {
        this.publishToken = publishToken;
    }

    /** Title chosen by the broadcaster. Empty if none. */
    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title == null ? "" : title;
    }

    @Override
    public String toString() {
        return channelId + " " + (live ? "LIVE" : "OFFLINE");
    }
}