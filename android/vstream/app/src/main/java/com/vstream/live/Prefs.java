package com.vstream.live;

import android.content.Context;
import android.content.SharedPreferences;

/** Everything the app remembers, in its private storage on the phone. */
final class Prefs {

    /** Change this when the legal page changes in a way people must agree to again. */
    static final String LEGAL_VERSION = "2026-10-22";
    static final String DEFAULT_HOST = "memoryq.com";

    private Prefs() {
    }

    static SharedPreferences of(Context c) {
        return c.getSharedPreferences("vstream_live", Context.MODE_PRIVATE);
    }

    static String host(Context c) {
        return of(c).getString("host", DEFAULT_HOST);
    }

    static String session(Context c) {
        return of(c).getString("session", "");
    }

    static boolean signedIn(Context c) {
        return !session(c).isEmpty();
    }

    static boolean agreed(Context c) {
        return LEGAL_VERSION.equals(of(c).getString("agreed", ""));
    }

    /** True when the stream goes to the user's own RTMP server instead of Memoryq. */
    static boolean ownServer(Context c) {
        return of(c).getBoolean("ownOn", false);
    }

    /** 480, 720 or 1080. */
    static int quality(Context c) {
        return of(c).getInt("quality", 720);
    }

    static void signOut(Context c) {
        of(c).edit().remove("session").remove("username").remove("key").remove("channel").apply();
    }
}