package com.vstream.Vidiserver;

/**
 * Builds MediaMTX URLs for a channel. The media host defaults to "localhost";
 * override with: java -Dvstream.mediaHost=192.168.1.50 ...
 */
public final class StreamUrls {

    public static final int RTMP_PORT = 1935;
    public static final int HLS_PORT = 8888;
    public static final int WEBRTC_PORT = 8889;

    private static final String HOST = System.getProperty("vstream.mediaHost", "localhost");

    private StreamUrls() {
    }

    public static String path(String channelId) {
        return "live/" + channelId;
    }

    public static String rtmpPublish(String channelId) {
        return "rtmp://" + HOST + ":" + RTMP_PORT + "/" + path(channelId);
    }

    public static String hlsPlayback(String channelId) {
        return "http://" + HOST + ":" + HLS_PORT + "/" + path(channelId) + "/index.m3u8";
    }

    public static String webrtcPlayback(String channelId) {
        return "http://" + HOST + ":" + WEBRTC_PORT + "/" + path(channelId);
    }
}