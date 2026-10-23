package com.vstream.broadcaster;

import android.content.Intent;
import android.content.SharedPreferences;
import android.text.InputFilter;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Calls: who you can call, your recent calls and missed calls, do not disturb, and blocked people. */
public class CallsActivity extends AppCompatActivity {

    private static final String PREFS = "vstream";

    private static final class Person {
        final String name;
        boolean online = false;
        final List<String> groups = new ArrayList<>();

        Person(String name) {
            this.name = name;
        }
    }

    private SharedPreferences prefs;
    private TextView statusView;
    private LinearLayout content;
    private boolean loadingSwitch = false;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void ui(Runnable r) {
        runOnUiThread(() -> {
            if (!isFinishing() && !isDestroyed()) {
                r.run();
            }
        });
    }

    @Override
    protected void onCreate(android.os.Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setFitsSystemWindows(true);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(16), dp(12), dp(16), dp(8));
        TextView title = new TextView(this);
        title.setText("Calls");
        title.setTextSize(28);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        header.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button home = new Button(this);
        home.setText("Home");
        home.setOnClickListener(v -> {
            Intent i = new Intent(this, HomeActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(i);
            finish();
        });
        header.addView(home);
        root.addView(header);

        statusView = new TextView(this);
        statusView.setPadding(dp(16), 0, dp(16), dp(8));
        statusView.setTextSize(14);
        root.addView(statusView);

        ScrollView scroll = new ScrollView(this);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), 0, dp(16), dp(24));
        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
    }

    @Override
    protected void onStart() {
        super.onStart();
        load();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }

    // ---- Loading ----

    private void load() {
        statusView.setText("Loading...");
        final String host = prefs.getString("host", "");
        final String session = prefs.getString("session", "");
        final String me = prefs.getString("username", "");
        executor.execute(() -> {
            Api.Result feat = Api.get(host, "/api/calls/features", null);
            final boolean on = feat.code == 200 && feat.json().optBoolean("calls");
            if (!on) {
                ui(() -> {
                    content.removeAllViews();
                    statusView.setText(feat.code == 0 ? "Cannot reach the server"
                            : "Calls are not turned on on this server.");
                });
                return;
            }
            if (!Account.signedIn(prefs)) {
                ui(() -> {
                    content.removeAllViews();
                    statusView.setText("Sign in to make and receive calls.");
                });
                return;
            }
            Api.Result pr = Api.get(host, "/api/calls/prefs", session);
            Api.Result hr = Api.get(host, "/api/calls/history?limit=40", session);
            Api.Result br = Api.get(host, "/api/calls/blocked", session);
            if (pr.code == 401 || hr.code == 401) {
                Account.clear(prefs);
            }
            Map<String, Person> people = new LinkedHashMap<>();
            JSONArray rooms = ChatClient.rooms(host, session);
            if (rooms != null) {
                for (int i = 0; i < rooms.length(); i++) {
                    JSONObject o = rooms.optJSONObject(i);
                    if (o == null || !o.optBoolean("private")) {
                        continue;
                    }
                    String roomTitle = o.optString("title");
                    Api.Result mr = Api.get(host, "/api/chat/groups/members?room=" + o.optString("id"), session);
                    JSONArray ms = mr.code == 200 ? mr.json().optJSONArray("members") : null;
                    if (ms == null) {
                        continue;
                    }
                    for (int k = 0; k < ms.length(); k++) {
                        JSONObject m = ms.optJSONObject(k);
                        if (m == null) {
                            continue;
                        }
                        String name = m.optString("name");
                        if (name.equalsIgnoreCase(me) || "ADMIN".equals(m.optString("role"))) {
                            continue;
                        }
                        String key = name.toLowerCase(Locale.ROOT);
                        Person p = people.get(key);
                        if (p == null) {
                            p = new Person(name);
                            people.put(key, p);
                        }
                        p.online |= m.optBoolean("online");
                        p.groups.add(roomTitle);
                    }
                }
            }
            final boolean dnd = pr.code == 200 && pr.json().optBoolean("dnd");
            final JSONArray history = hr.code == 200 ? hr.json().optJSONArray("history") : null;
            final JSONArray blocked = br.code == 200 ? br.json().optJSONArray("blocked") : null;
            ui(() -> render(dnd, history, blocked, new ArrayList<>(people.values())));
            if (hr.code == 200) {
                Api.post(host, "/api/calls/history/seen", "", session);
            }
        });
    }

    // ---- Showing ----

    private TextView heading(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(18);
        t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        t.setPadding(0, dp(20), 0, dp(6));
        return t;
    }

    private View row(String title, String subtitle, int accent, View.OnClickListener click) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, dp(10), 0, dp(10));
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(17);
        if (accent != 0) {
            t.setTextColor(accent);
        }
        box.addView(t);
        if (subtitle != null && !subtitle.isEmpty()) {
            TextView s = new TextView(this);
            s.setText(subtitle);
            s.setTextSize(13);
            s.setAlpha(0.75f);
            box.addView(s);
        }
        if (click != null) {
            box.setOnClickListener(click);
        }
        return box;
    }

    private void render(boolean dnd, JSONArray history, JSONArray blocked, List<Person> people) {
        statusView.setText("");
        content.removeAllViews();

        SwitchCompat dndSwitch = new SwitchCompat(this);
        dndSwitch.setText("Do not disturb for calls");
        dndSwitch.setMinHeight(dp(48));
        loadingSwitch = true;
        dndSwitch.setChecked(dnd);
        loadingSwitch = false;
        dndSwitch.setOnCheckedChangeListener((b, checked) -> {
            if (!loadingSwitch) {
                setDnd(checked);
            }
        });
        content.addView(dndSwitch);
        TextView dndNote = new TextView(this);
        dndNote.setText("People who call you are told you are not available, and it shows as a missed call.");
        dndNote.setTextSize(13);
        content.addView(dndNote);

        content.addView(heading("Call someone"));
        for (final Person p : people) {
            StringBuilder groups = new StringBuilder();
            for (int i = 0; i < p.groups.size(); i++) {
                if (i > 0) {
                    groups.append(", ");
                }
                groups.append(p.groups.get(i));
            }
            content.addView(row((p.online ? "\u25CF " : "\u25CB ") + p.name, groups.toString(),
                    p.online ? 0xFF2E7D32 : 0, v -> chooseCall(p.name)));
        }

        content.addView(heading("Recent calls"));
        if (history == null || history.length() == 0) {
            TextView none = new TextView(this);
            none.setText("No calls yet.");
            none.setTextSize(14);
            content.addView(none);
        } else {
            for (int i = 0; i < history.length(); i++) {
                JSONObject h = history.optJSONObject(i);
                if (h != null) {
                    content.addView(historyRow(h));
                }
            }
        }

        content.addView(heading("Blocked people"));
        if (blocked == null || blocked.length() == 0) {
            TextView none = new TextView(this);
            none.setText("Nobody is blocked. A blocked person cannot call you.");
            none.setTextSize(14);
            content.addView(none);
        } else {
            for (int i = 0; i < blocked.length(); i++) {
                final String name = blocked.optString(i);
                LinearLayout line = new LinearLayout(this);
                line.setOrientation(LinearLayout.HORIZONTAL);
                line.setGravity(Gravity.CENTER_VERTICAL);
                TextView n = new TextView(this);
                n.setText(name);
                n.setTextSize(17);
                line.addView(n, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                Button un = new Button(this);
                un.setText("Unblock");
                un.setOnClickListener(v -> postThenReload("/api/calls/unblock", Api.form("username", name), name + " unblocked"));
                line.addView(un);
                content.addView(line);
            }
        }
        Button block = new Button(this);
        block.setText("Block someone");
        block.setOnClickListener(v -> askBlock());
        content.addView(block);
    }

    private View historyRow(JSONObject h) {
        boolean group = h.optBoolean("group");
        boolean outgoing = h.optBoolean("outgoing");
        String outcome = h.optString("outcome");
        String peer = h.optString("peer");
        String who = group ? h.optString("roomTitle") + " (group)" : peer;
        String icon;
        String label;
        int accent = 0;
        if (outgoing) {
            icon = "\u2197 ";
            label = "answered".equals(outcome) ? "Outgoing" : ("declined".equals(outcome) ? "Declined" : "No answer");
        } else if ("answered".equals(outcome)) {
            icon = "\u2199 ";
            label = "Incoming";
        } else {
            icon = "\u2716 ";
            label = "dnd".equals(outcome) ? "Missed (do not disturb)" : ("declined".equals(outcome) ? "Declined" : "Missed");
            accent = "declined".equals(outcome) ? 0 : 0xFFC62828;
        }
        long secs = h.optLong("duration");
        String length = secs > 0 ? "  \u00b7  " + (secs >= 60 ? (secs / 60) + " min " + (secs % 60) + " s" : secs + " s") : "";
        String sub = (h.optBoolean("video") ? "Video" : "Voice") + "  \u00b7  " + label + length + "  \u00b7  "
                + android.text.format.DateFormat.format("d MMM HH:mm", new Date(h.optLong("time")));
        final String callBack = group ? "" : peer;
        return row(icon + who, sub, accent, callBack.isEmpty() ? null : v -> chooseCall(callBack));
    }

    // ---- Actions ----

    private void chooseCall(final String name) {
        final String[] kinds = {"Video call", "Voice call"};
        new AlertDialog.Builder(this)
                .setTitle("Call " + name)
                .setItems(kinds, (d, which) -> CallStarter.callPerson(this, prefs, name, which == 0, null))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void setDnd(boolean on) {
        final String host = prefs.getString("host", "");
        final String session = prefs.getString("session", "");
        executor.execute(() -> {
            Api.Result r = Api.post(host, "/api/calls/prefs", Api.form("dnd", String.valueOf(on)), session);
            ui(() -> {
                if (r.code != 200) {
                    Toast.makeText(this, r.code == 0 ? "Cannot reach the server" : r.error(), Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(this, on ? "Do not disturb is on" : "Do not disturb is off", Toast.LENGTH_SHORT).show();
                }
            });
        });
    }

    private void askBlock() {
        final EditText input = new EditText(this);
        input.setHint("Their username");
        input.setSingleLine(true);
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(24)});
        LinearLayout box = new LinearLayout(this);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        box.addView(input, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        new AlertDialog.Builder(this)
                .setTitle("Block someone")
                .setMessage("They will not be able to call you. They are not told.")
                .setView(box)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Block", (d, w) -> {
                    String name = input.getText().toString().trim();
                    if (!name.isEmpty()) {
                        postThenReload("/api/calls/block", Api.form("username", name), name + " blocked");
                    }
                })
                .show();
    }

    private void postThenReload(final String path, final String body, final String done) {
        final String host = prefs.getString("host", "");
        final String session = prefs.getString("session", "");
        executor.execute(() -> {
            Api.Result r = Api.post(host, path, body, session);
            ui(() -> {
                if (r.code == 200) {
                    Toast.makeText(this, done, Toast.LENGTH_SHORT).show();
                    load();
                } else {
                    Toast.makeText(this, r.code == 0 ? "Cannot reach the server" : r.error(), Toast.LENGTH_LONG).show();
                }
            });
        });
    }
}