package com.vstream.broadcaster;

import android.content.SharedPreferences;

import org.json.JSONObject;

/**
 * Keeps the signed-in state in the app settings. A key that came from the account (a claimed or
 * assigned channel) is stored as the stream key and removed again when the account no longer has it.
 */
final class Account {

    private Account() {
    }

    static boolean signedIn(SharedPreferences p) {
        return !p.getString("session", "").isEmpty();
    }

    /** Applies the answer of /api/login, /api/register or /api/me. */
    static void apply(SharedPreferences p, JSONObject me, String newSession) {
        SharedPreferences.Editor e = p.edit();
        if (newSession != null && !newSession.isEmpty()) {
            e.putString("session", newSession);
        }
        e.putString("username", me.optString("username", ""));
        e.putString("role", me.optString("role", ""));

        String key = "";
        String channel = "";
        int hours = -1;
        boolean warning = false;
        JSONObject claim = me.optJSONObject("claim");
        if (claim != null) {
            key = claim.optString("streamKey", "");
            channel = claim.optString("channelId", "");
            hours = claim.optInt("hoursLeft", -1);
            warning = claim.optBoolean("warning", false);
        } else if (me.has("streamKey")) {
            key = me.optString("streamKey", "");
            channel = me.optString("channelId", "");
        }
        if (!key.isEmpty()) {
            e.putString("key", key).putBoolean("keyManaged", true).putString("claimChannel", channel)
                    .putInt("hoursLeft", hours).putBoolean("warning", warning);
        } else {
            if (p.getBoolean("keyManaged", false)) {
                e.remove("key");
            }
            e.putBoolean("keyManaged", false).remove("claimChannel").remove("hoursLeft").remove("warning");
        }
        String notice = me.optString("notice", "");
        if (notice.isEmpty()) {
            e.remove("notice");
        } else {
            e.putString("notice", notice);
        }
        e.apply();
    }

    /** Local sign-out: forgets the session and any key that came from the account. */
    static void clear(SharedPreferences p) {
        SharedPreferences.Editor e = p.edit();
        if (p.getBoolean("keyManaged", false)) {
            e.remove("key");
        }
        e.remove("session").remove("username").remove("role").remove("claimChannel")
                .remove("hoursLeft").remove("warning").remove("notice")
                .putBoolean("keyManaged", false);
        e.apply();
    }
}