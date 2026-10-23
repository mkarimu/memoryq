package com.vstream.broadcaster;

import android.app.Activity;
import android.content.SharedPreferences;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Lets you tick people to call, or to add to a call you are in. The list shows the members of your private
 * groups (or of one group), online people first; anyone else can be called by typing their username.
 */
final class PeoplePicker {

    interface Done {
        void picked(List<String> names);
    }

    private static final class Person {
        final String name;
        boolean online;

        Person(String name) {
            this.name = name;
        }
    }

    private PeoplePicker() {
    }

    /**
     * @param room    only list this group's members, or null for everyone in your private groups
     * @param exclude people not to list (already in the call), compared without case
     * @param max     how many people may be ticked
     */
    static void pick(final Activity a, final SharedPreferences prefs, final String title, final String action,
                     final String room, final Set<String> exclude, final int max, final Done done) {
        if (max <= 0) {
            Toast.makeText(a, "The call is full", Toast.LENGTH_LONG).show();
            return;
        }
        final String host = prefs.getString("host", "");
        final String session = prefs.getString("session", "");
        final String me = prefs.getString("username", "");
        final Set<String> skip = new java.util.HashSet<>();
        if (exclude != null) {
            for (String n : exclude) {
                skip.add(n.toLowerCase(Locale.ROOT));
            }
        }
        skip.add(me.toLowerCase(Locale.ROOT));
        new Thread(() -> {
            List<String> rooms = new ArrayList<>();
            boolean reached = true;
            if (room != null && !room.isEmpty()) {
                rooms.add(room);
            } else {
                JSONArray rs = ChatClient.rooms(host, session);
                if (rs == null) {
                    reached = false;
                } else {
                    for (int i = 0; i < rs.length(); i++) {
                        JSONObject o = rs.optJSONObject(i);
                        if (o != null && o.optBoolean("private")) {
                            rooms.add(o.optString("id"));
                        }
                    }
                }
            }
            Map<String, Person> people = new LinkedHashMap<>();
            for (String r : rooms) {
                Api.Result mr = Api.get(host, "/api/chat/groups/members?room=" + r, session);
                if (mr.code == 401) {
                    Account.clear(prefs);
                }
                if (mr.code == 0) {
                    reached = false;
                }
                JSONArray ms = mr.code == 200 ? mr.json().optJSONArray("members") : null;
                if (ms == null) {
                    continue;
                }
                for (int k = 0; k < ms.length(); k++) {
                    JSONObject m = ms.optJSONObject(k);
                    if (m == null || "ADMIN".equals(m.optString("role"))) {
                        continue;
                    }
                    String name = m.optString("name");
                    String key = name.toLowerCase(Locale.ROOT);
                    if (name.isEmpty() || skip.contains(key)) {
                        continue;
                    }
                    Person p = people.get(key);
                    if (p == null) {
                        p = new Person(name);
                        people.put(key, p);
                    }
                    p.online |= m.optBoolean("online");
                }
            }
            final List<Person> list = new ArrayList<>(people.values());
            Collections.sort(list, new Comparator<Person>() {
                @Override
                public int compare(Person x, Person y) {
                    if (x.online != y.online) {
                        return x.online ? -1 : 1;
                    }
                    return x.name.compareToIgnoreCase(y.name);
                }
            });
            final boolean ok = reached;
            a.runOnUiThread(() -> show(a, title, action, list, ok, max, done));
        }).start();
    }

    private static void show(final Activity a, String title, String action, final List<Person> list, boolean reached,
                             final int max, final Done done) {
        if (a.isFinishing() || a.isDestroyed()) {
            return;
        }
        if (!reached && list.isEmpty()) {
            Toast.makeText(a, "Cannot reach the server", Toast.LENGTH_LONG).show();
            return;
        }
        if (list.isEmpty()) {
            typeName(a, title, action, done);
            return;
        }
        final String[] labels = new String[list.size()];
        final boolean[] checked = new boolean[list.size()];
        for (int i = 0; i < list.size(); i++) {
            Person p = list.get(i);
            labels[i] = p.online ? p.name + "   \u25CF online" : p.name;
        }
        final AlertDialog d = new AlertDialog.Builder(a)
                .setTitle(max == 1 ? title : title + " (up to " + max + ")")
                .setMultiChoiceItems(labels, checked, (dlg, which, isChecked) -> checked[which] = isChecked)
                .setNegativeButton("Cancel", null)
                .setNeutralButton("Type a name", (dlg, w) -> typeName(a, title, action, done))
                .setPositiveButton(action, null)
                .create();
        d.setOnShowListener(x -> d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            List<String> chosen = new ArrayList<>();
            for (int i = 0; i < checked.length; i++) {
                if (checked[i]) {
                    chosen.add(list.get(i).name);
                }
            }
            if (chosen.isEmpty()) {
                Toast.makeText(a, "Tick at least one person", Toast.LENGTH_SHORT).show();
            } else if (chosen.size() > max) {
                Toast.makeText(a, "You can choose up to " + max + (max == 1 ? " person" : " people"),
                        Toast.LENGTH_LONG).show();
            } else {
                d.dismiss();
                done.picked(chosen);
            }
        }));
        d.show();
    }

    /** Asks for a username, for calling someone who is not in the list. */
    static void typeName(final Activity a, String title, String action, final Done done) {
        final android.widget.EditText input = new android.widget.EditText(a);
        input.setHint("Their Memoryq username");
        input.setSingleLine(true);
        input.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(24)});
        android.widget.LinearLayout box = new android.widget.LinearLayout(a);
        int pad = Math.round(20 * a.getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(input, new android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT));
        new AlertDialog.Builder(a)
                .setTitle(title)
                .setView(box)
                .setNegativeButton("Cancel", null)
                .setPositiveButton(action, (dlg, w) -> {
                    String n = input.getText().toString().trim();
                    if (!n.isEmpty()) {
                        List<String> one = new ArrayList<>();
                        one.add(n);
                        done.picked(one);
                    }
                })
                .show();
    }
}