package com.vstream.broadcaster;

import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/** Chat helpers shared by Home, the room list and the room screens. Call off the main thread. */
final class ChatClient {

    private ChatClient() {
    }

    static JSONArray rooms(String host) {
        return rooms(host, null);
    }

    /** The rooms this person may see. With a session that includes their private groups. */
    static JSONArray rooms(String host, String session) {
        Api.Result r = Api.get(host, "/api/chat/rooms", session);
        return r.code == 200 ? r.json().optJSONArray("rooms") : null;
    }

    static long seen(SharedPreferences p, String room) {
        return p.getLong("seen_" + room, -1L);
    }

    static void markSeen(SharedPreferences p, String room, long id) {
        if (id > seen(p, room)) {
            p.edit().putLong("seen_" + room, id).apply();
        }
    }

    /** New messages per room. A room never opened before starts at "nothing new". */
    static Map<String, Integer> unread(String host, String session, SharedPreferences p, JSONArray rooms) {
        Map<String, Integer> result = new HashMap<>();
        if (rooms == null) {
            return result;
        }
        StringBuilder q = new StringBuilder();
        SharedPreferences.Editor editor = null;
        for (int i = 0; i < rooms.length(); i++) {
            JSONObject o = rooms.optJSONObject(i);
            if (o == null) {
                continue;
            }
            String id = o.optString("id");
            long seen = seen(p, id);
            if (seen < 0) {
                seen = o.optLong("lastId", 0);
                if (editor == null) {
                    editor = p.edit();
                }
                editor.putLong("seen_" + id, seen);
            }
            if (q.length() > 0) {
                q.append(',');
            }
            q.append(id).append(':').append(seen);
        }
        if (editor != null) {
            editor.apply();
        }
        if (q.length() == 0) {
            return result;
        }
        try {
            Api.Result r = Api.get(host, "/api/chat/unread?rooms=" + URLEncoder.encode(q.toString(), "UTF-8"), session);
            JSONObject u = r.code == 200 ? r.json().optJSONObject("unread") : null;
            if (u != null) {
                Iterator<String> keys = u.keys();
                while (keys.hasNext()) {
                    String k = keys.next();
                    result.put(k, u.optInt(k, 0));
                }
            }
        } catch (java.io.UnsupportedEncodingException e) {
            // UTF-8 is always available
        }
        return result;
    }

    static int total(Map<String, Integer> unread) {
        int n = 0;
        for (Integer v : unread.values()) {
            n += v == null ? 0 : v;
        }
        return n;
    }

    static boolean forcedUnread(SharedPreferences p, String room) {
        return p.getBoolean("forced_" + room, false);
    }

    static void setForcedUnread(SharedPreferences p, String room, boolean value) {
        if (value) {
            p.edit().putBoolean("forced_" + room, true).apply();
        } else {
            p.edit().remove("forced_" + room).apply();
        }
    }

    static boolean sendReceipts(SharedPreferences p) {
        return p.getBoolean("sendReceipts", true);
    }

    static String roleLabel(String role) {
        if ("ADMIN".equals(role)) {
            return "admin";
        }
        if ("BROADCASTER".equals(role)) {
            return "broadcaster";
        }
        if ("GUEST".equals(role)) {
            return "guest";
        }
        if ("CHATTER".equals(role)) {
            return "registered";
        }
        return "";
    }
}