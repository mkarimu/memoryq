package com.vstream.broadcaster;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * What you can do with a person you can see (in a chat room or the online list): voice call, video call, or a
 * private chat. A private chat is a private group for just the two of you; the other person gets an invitation.
 */
final class PersonActions {

    private PersonActions() {
    }

    static void show(final Activity a, final SharedPreferences prefs, final String name, final String room) {
        if (name == null || name.isEmpty()) {
            return;
        }
        String me = prefs.getString("username", "");
        if (name.equalsIgnoreCase(me)) {
            return;
        }
        if (!Account.signedIn(prefs)) {
            Toast.makeText(a, "Sign in to call " + name + " or chat privately", Toast.LENGTH_LONG).show();
            return;
        }
        final List<String> items = new ArrayList<>();
        items.add("View profile");
        if (CallStarter.enabled(prefs)) {
            items.add("Voice call");
            items.add("Video call");
        }
        items.add("Private chat");
        new AlertDialog.Builder(a)
                .setTitle(name)
                .setItems(items.toArray(new String[0]), (d, which) -> {
                    String choice = items.get(which);
                    if ("View profile".equals(choice)) {
                        ProfileUi.openProfile(a, name);
                    } else if ("Voice call".equals(choice)) {
                        CallStarter.callPerson(a, prefs, name, false, room);
                    } else if ("Video call".equals(choice)) {
                        CallStarter.callPerson(a, prefs, name, true, room);
                    } else {
                        privateChat(a, prefs, name);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** Opens your private chat with this person, creating it (and inviting them) the first time. */
    static void privateChat(final Activity a, final SharedPreferences prefs, final String name) {
        final String host = prefs.getString("host", "");
        final String session = prefs.getString("session", "");
        final String me = prefs.getString("username", "");
        final String title = me + " & " + name;
        final String other = name + " & " + me;
        Toast.makeText(a, "Opening your private chat with " + name + "...", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            JSONArray rooms = ChatClient.rooms(host, session);
            if (rooms != null) {
                for (int i = 0; i < rooms.length(); i++) {
                    JSONObject o = rooms.optJSONObject(i);
                    if (o == null || !o.optBoolean("private")) {
                        continue;
                    }
                    String t = o.optString("title");
                    if (t.equalsIgnoreCase(title) || t.equalsIgnoreCase(other)) {
                        final JSONObject found = o;
                        a.runOnUiThread(() -> open(a, found.optString("id"), t, found.optString("owner")));
                        return;
                    }
                }
            }
            Api.Result r = Api.post(host, "/api/chat/groups/create", Api.form("title", title, "private", "true"), session);
            if (r.code == 401) {
                Account.clear(prefs);
            }
            if (r.code != 200) {
                final String err = r.code == 0 ? "Cannot reach the server" : r.error();
                a.runOnUiThread(() -> toast(a, "Could not start a private chat: " + err));
                return;
            }
            final JSONObject g = r.json();
            final String id = g.optString("id");
            Api.Result inv = Api.post(host, "/api/chat/groups/invite/user", Api.form("room", id, "username", name), session);
            final String note = inv.code == 200
                    ? name + " gets an invitation in their Chat window."
                    : "Created, but the invitation failed: " + inv.error();
            a.runOnUiThread(() -> {
                toast(a, note);
                open(a, id, g.optString("title", title), g.optString("owner", me));
            });
        }).start();
    }

    private static void open(Activity a, String id, String title, String owner) {
        if (a.isFinishing() || a.isDestroyed() || id == null || id.isEmpty()) {
            return;
        }
        Intent i = new Intent(a, ChatRoomActivity.class);
        i.putExtra(ChatRoomActivity.EXTRA_ROOM, id);
        i.putExtra(ChatRoomActivity.EXTRA_TITLE, title);
        i.putExtra(ChatRoomActivity.EXTRA_OWNER, owner == null ? "" : owner);
        a.startActivity(i);
    }

    private static void toast(Activity a, String msg) {
        if (!a.isFinishing() && !a.isDestroyed()) {
            Toast.makeText(a, msg, Toast.LENGTH_LONG).show();
        }
    }

    /** The Activity a view lives in, or null. */
    static Activity activityOf(android.content.Context c) {
        while (c instanceof android.content.ContextWrapper) {
            if (c instanceof Activity) {
                return (Activity) c;
            }
            c = ((android.content.ContextWrapper) c).getBaseContext();
        }
        return null;
    }
}