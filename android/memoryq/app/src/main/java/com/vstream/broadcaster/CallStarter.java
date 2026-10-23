package com.vstream.broadcaster;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Starts calls and adds people to them. A call always starts with one person; when several people are chosen,
 * the first one who can be rung starts the call and the rest are added to it straight away.
 */
final class CallStarter {

    private CallStarter() {
    }

    static boolean enabled(SharedPreferences prefs) {
        return CallMonitor.callsEnabled() && Account.signedIn(prefs);
    }

    /** Most people a call can have, counting you. */
    static int maxPeople(boolean video) {
        return video ? 6 : 8;
    }

    static void callPerson(final Activity activity, final SharedPreferences prefs, final String name,
                           final boolean video, final String room) {
        callPeople(activity, prefs, Collections.singletonList(name), video, room);
    }

    static void callPeople(final Activity activity, final SharedPreferences prefs, final List<String> names,
                           final boolean video, final String room) {
        if (names == null || names.isEmpty()) {
            return;
        }
        final String host = prefs.getString("host", "");
        final String session = prefs.getString("session", "");
        Toast.makeText(activity, names.size() == 1 ? "Calling " + names.get(0) + "..." : "Calling "
                + names.size() + " people...", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            List<String> notRung = new ArrayList<>();
            Api.Result started = null;
            int next = 0;
            while (next < names.size()) {
                String name = names.get(next++);
                String body = (room == null || room.isEmpty())
                        ? Api.form("to", name, "video", String.valueOf(video))
                        : Api.form("to", name, "video", String.valueOf(video), "room", room);
                Api.Result r = Api.post(host, "/api/calls/start", body, session);
                if (r.code == 401) {
                    Account.clear(prefs);
                }
                if (r.code == 200) {
                    started = r;
                    break;
                }
                if (names.size() > 1 && aboutThatPerson(r)) {
                    notRung.add(name + " (" + shortReason(r.error()) + ")");
                    continue;
                }
                final Api.Result failed = r;
                activity.runOnUiThread(() -> toast(activity, failed.code == 0 ? "Cannot reach the server" : failed.error()));
                return;
            }
            if (started == null) {
                final String msg = "Nobody could be rung: " + join(notRung);
                activity.runOnUiThread(() -> toast(activity, msg));
                return;
            }
            JSONObject call = started.json().optJSONObject("call");
            final String id = call == null ? "" : call.optString("id");
            if (!id.isEmpty() && next < names.size()) {
                Api.Result ar = Api.post(host, "/api/calls/add",
                        Api.form("id", id, "to", join(names.subList(next, names.size()))), session);
                collectNotRung(ar, names.subList(next, names.size()), notRung);
            }
            final String msg = notRung.isEmpty() ? null : "Not rung: " + join(notRung);
            activity.runOnUiThread(() -> {
                if (activity.isFinishing() || activity.isDestroyed()) {
                    return;
                }
                if (!id.isEmpty()) {
                    CallMonitor.callScreenOpened(id);
                    Intent i = new Intent(activity, CallActivity.class);
                    i.putExtra(CallActivity.EXTRA_ID, id);
                    activity.startActivity(i);
                }
                if (msg != null) {
                    toast(activity, msg);
                }
            });
        }).start();
    }

    /** Rings more people into a call you are in. after runs on the main thread when it is done. */
    static void addPeople(final Activity activity, final SharedPreferences prefs, final String callId,
                          final List<String> names, final Runnable after) {
        final String host = prefs.getString("host", "");
        final String session = prefs.getString("session", "");
        new Thread(() -> {
            Api.Result r = Api.post(host, "/api/calls/add", Api.form("id", callId, "to", join(names)), session);
            if (r.code == 401) {
                Account.clear(prefs);
            }
            List<String> notRung = new ArrayList<>();
            collectNotRung(r, names, notRung);
            int added = 0;
            JSONArray a = r.code == 200 ? r.json().optJSONArray("added") : null;
            if (a != null) {
                added = a.length();
            }
            final String msg;
            if (r.code == 0) {
                msg = "Cannot reach the server";
            } else if (r.code != 200 && names.size() > 1) {
                msg = r.error();
            } else if (notRung.isEmpty()) {
                msg = added == 1 ? "Ringing " + names.get(0) + "..." : "Ringing " + added + " people...";
            } else {
                msg = (added > 0 ? "Ringing " + added + ". " : "") + "Not rung: " + join(notRung);
            }
            activity.runOnUiThread(() -> {
                if (activity.isFinishing() || activity.isDestroyed()) {
                    return;
                }
                toast(activity, msg);
                if (after != null) {
                    after.run();
                }
            });
        }).start();
    }

    // ---- Helpers ----

    /** Reads who was skipped from a start/add reply. A refused single add counts as that person skipped. */
    private static void collectNotRung(Api.Result r, List<String> asked, List<String> out) {
        if (r.code == 200) {
            JSONArray s = r.json().optJSONArray("skipped");
            if (s != null) {
                for (int i = 0; i < s.length(); i++) {
                    JSONObject o = s.optJSONObject(i);
                    if (o != null) {
                        out.add(o.optString("name") + " (" + shortReason(o.optString("reason")) + ")");
                    }
                }
            }
        } else if (asked.size() == 1) {
            out.add(asked.get(0) + " (" + shortReason(r.code == 0 ? "" : r.error()) + ")");
        }
    }

    /** True if the server refused because of the person being called, not because of you. */
    private static boolean aboutThatPerson(Api.Result r) {
        if (r.code != 403 && r.code != 404 && r.code != 409 && r.code != 429 && r.code != 400) {
            return false;
        }
        String e = r.error();
        return e.contains("this person") || e.startsWith("no such user") || e.contains("share a private group")
                || e.contains("cannot be called");
    }

    private static String shortReason(String reason) {
        if (reason == null || reason.isEmpty()) {
            return "no connection";
        }
        if (reason.contains("busy")) {
            return "busy on another call";
        }
        if (reason.contains("already in the call")) {
            return "already in the call";
        }
        if (reason.contains("you have blocked")) {
            return "you blocked them";
        }
        if (reason.contains("several times")) {
            return "rung too often - try later";
        }
        if (reason.contains("share a private group")) {
            return "no shared private group";
        }
        if (reason.startsWith("no such user")) {
            return "no such user";
        }
        return "not available";
    }

    private static String join(List<String> names) {
        StringBuilder sb = new StringBuilder();
        for (String n : names) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(n);
        }
        return sb.toString();
    }

    private static void toast(Activity a, String msg) {
        if (!a.isFinishing() && !a.isDestroyed()) {
            Toast.makeText(a, msg, Toast.LENGTH_LONG).show();
        }
    }
}