package com.vstream.broadcaster;

import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Radio stations: each with what is playing and what the DJ is saying. Tap Listen to play; the radio keeps
 * playing while you use the rest of the app (stop it here or with the bar at the bottom).
 */
public class RadioActivity extends AppCompatActivity implements RadioPlayer.Listener {

    private static final long POLL_MS = 5000;

    private SharedPreferences prefs;
    private LinearLayout list;
    private TextView status;
    private LinearLayout bar;
    private TextView barTitle;
    private TextView barLine;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private JSONArray lastStations;
    private Button likeBtn;
    private Button getBtn;
    private JSONObject nowSong;

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            load();
            handler.postDelayed(this, POLL_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("vstream", MODE_PRIVATE);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setFitsSystemWindows(true);

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(8), dp(4), dp(16), dp(4));
        TextView back = ProfileUi.text(this, "\u2039", 30, true);
        back.setPadding(dp(10), 0, dp(14), dp(4));
        back.setOnClickListener(v -> finish());
        top.addView(back);
        top.addView(ProfileUi.text(this, "\uD83D\uDCFB  Radio", 20, true));
        root.addView(top);

        ScrollView scroll = new ScrollView(this);
        LinearLayout inner = new LinearLayout(this);
        inner.setOrientation(LinearLayout.VERTICAL);
        inner.setPadding(dp(12), 0, dp(12), dp(24));
        status = ProfileUi.text(this, "Loading stations...", 14, false);
        status.setPadding(dp(4), dp(8), 0, dp(8));
        inner.addView(status);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        inner.addView(list);
        scroll.addView(inner);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.VERTICAL);
        bar.setPadding(dp(16), dp(10), dp(12), dp(10));
        bar.setBackgroundColor(0xFF16202A);
        LinearLayout barTop = new LinearLayout(this);
        barTop.setOrientation(LinearLayout.HORIZONTAL);
        barTop.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        barTitle = ProfileUi.text(this, "", 15, true);
        barTitle.setTextColor(0xFFFFFFFF);
        barLine = ProfileUi.text(this, "", 13, false);
        barLine.setTextColor(0xFFB8C6D3);
        barLine.setMaxLines(2);
        texts.addView(barTitle);
        texts.addView(barLine);
        barTop.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button stop = new Button(this);
        stop.setText("Stop");
        stop.setAllCaps(false);
        stop.setOnClickListener(v -> {
            RadioPlayer.stop();
            render();
        });
        barTop.addView(stop);
        bar.addView(barTop);
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        likeBtn = smallButton("\u2661 Like", v -> like());
        getBtn = smallButton("Get this song", v -> getSong());
        Button req = smallButton("Request a song", v -> requestSong());
        actions.addView(likeBtn);
        actions.addView(getBtn);
        actions.addView(req);
        bar.addView(actions);
        root.addView(bar);
        setContentView(root);
    }

    @Override
    protected void onStart() {
        super.onStart();
        RadioPlayer.setListener(this);
        handler.post(poll);
    }

    @Override
    protected void onStop() {
        super.onStop();
        RadioPlayer.setListener(null);
        handler.removeCallbacks(poll);
    }

    @Override
    public void onRadioChanged() {
        if (!isFinishing() && !isDestroyed()) {
            render();
        }
    }

    private int dp(int v) {
        return ProfileUi.dp(this, v);
    }

    private void load() {
        final String host = prefs.getString("host", "");
        if (host.isEmpty()) {
            status.setText("Set the server address in Settings first.");
            return;
        }
        new Thread(() -> {
            final Api.Result r = Api.get(host, "/api/radio/stations", prefs.getString("session", ""));
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                if (r.code != 200) {
                    status.setText(r.code == 0 ? "Cannot reach the server" : r.error());
                    return;
                }
                lastStations = r.json().optJSONArray("stations");
                render();
            });
        }).start();
    }

    private void render() {
        list.removeAllViews();
        int n = lastStations == null ? 0 : lastStations.length();
        String err = RadioPlayer.error();
        status.setText(n == 0 ? "No radio stations yet." : err);
        status.setVisibility(n == 0 || !err.isEmpty() ? View.VISIBLE : View.GONE);
        JSONObject playing = null;
        for (int i = 0; i < n; i++) {
            JSONObject s = lastStations.optJSONObject(i);
            if (s == null) {
                continue;
            }
            if (RadioPlayer.isPlaying(s.optLong("id"))) {
                playing = s;
            }
            list.addView(card(s));
        }
        if (RadioPlayer.stationId() != 0) {
            bar.setVisibility(View.VISIBLE);
            barTitle.setText(RadioPlayer.stationName());
            barLine.setText(playing == null ? "Tuning in..." : nowLine(playing));
            nowSong = playing == null ? null : playing.optJSONObject("now");
            likeBtn.setVisibility(nowSong == null ? View.GONE : View.VISIBLE);
            if (nowSong != null) {
                likeBtn.setText((nowSong.optBoolean("liked") ? "\u2764 " : "\u2661 ") + nowSong.optInt("likes"));
            }
            getBtn.setVisibility(nowSong != null && nowSong.optBoolean("hasLink") ? View.VISIBLE : View.GONE);
        } else {
            bar.setVisibility(View.GONE);
        }
    }

    private Button smallButton(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(13);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(4), dp(6), 0);
        b.setLayoutParams(lp);
        return b;
    }

    private void toast(String s) {
        if (!isFinishing() && !isDestroyed()) {
            Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
        }
    }

    private void like() {
        final JSONObject song = nowSong;
        if (song == null) {
            return;
        }
        if (!Account.signedIn(prefs)) {
            toast("Sign in to like songs");
            return;
        }
        final String host = prefs.getString("host", "");
        final String session = prefs.getString("session", "");
        final boolean like = !song.optBoolean("liked");
        new Thread(() -> {
            final Api.Result r = Api.post(host, "/api/radio/like",
                    Api.form("track", String.valueOf(song.optLong("trackId")), "like", String.valueOf(like)), session);
            runOnUiThread(() -> {
                if (r.code == 200) {
                    try {
                        song.put("liked", r.json().optBoolean("liked"));
                        song.put("likes", r.json().optInt("likes"));
                    } catch (Exception e) {
                        // shown on the next refresh
                    }
                    likeBtn.setText((song.optBoolean("liked") ? "\u2764 " : "\u2661 ") + song.optInt("likes"));
                } else {
                    toast(r.error());
                }
            });
        }).start();
    }

    private void getSong() {
        final JSONObject song = nowSong;
        if (song == null) {
            return;
        }
        final String host = prefs.getString("host", "");
        new Thread(() -> {
            final Api.Result r = Api.post(host, "/api/radio/tap",
                    Api.form("track", String.valueOf(song.optLong("trackId"))), null);
            runOnUiThread(() -> {
                String link = r.code == 200 ? r.json().optString("link") : "";
                if (link.startsWith("http://") || link.startsWith("https://")) {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(link)));
                    } catch (RuntimeException e) {
                        toast("No browser found to open the link");
                    }
                } else {
                    toast("No link for this song yet");
                }
            });
        }).start();
    }

    private void requestSong() {
        final long station = RadioPlayer.stationId();
        if (station == 0) {
            return;
        }
        if (!Account.signedIn(prefs)) {
            toast("Sign in to request songs");
            return;
        }
        final String host = prefs.getString("host", "");
        final String session = prefs.getString("session", "");
        new Thread(() -> {
            final Api.Result r = Api.get(host, "/api/radio/songs?station=" + station, session);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                JSONArray songs = r.code == 200 ? r.json().optJSONArray("songs") : null;
                if (songs == null || songs.length() == 0) {
                    toast(r.code == 200 ? "No songs on this station yet" : r.error());
                    return;
                }
                final String[] labels = new String[songs.length()];
                final long[] ids = new long[songs.length()];
                for (int i = 0; i < songs.length(); i++) {
                    JSONObject o = songs.optJSONObject(i);
                    ids[i] = o.optLong("id");
                    String artist = o.optString("artist");
                    labels[i] = o.optString("title") + (artist.isEmpty() ? "" : "  \u2014  " + artist);
                }
                new AlertDialog.Builder(this)
                        .setTitle("Request a song")
                        .setItems(labels, (d, which) -> sendRequest(station, ids[which]))
                        .setNegativeButton("Cancel", null)
                        .show();
            });
        }).start();
    }

    private void sendRequest(final long station, final long track) {
        final String host = prefs.getString("host", "");
        final String session = prefs.getString("session", "");
        new Thread(() -> {
            final Api.Result r = Api.post(host, "/api/radio/request",
                    Api.form("station", String.valueOf(station), "track", String.valueOf(track)), session);
            runOnUiThread(() -> {
                if (r.code == 200) {
                    int place = r.json().optInt("place", 1);
                    toast(place <= 1 ? "Your song plays next - listen for your name!"
                            : "Requested - number " + place + " in line");
                    load();
                } else {
                    toast(r.error());
                }
            });
        }).start();
    }

    private static String nowLine(JSONObject s) {
        JSONObject talk = s.optJSONObject("talk");
        if (talk != null) {
            return "\uD83C\uDF99 " + s.optString("djName") + ": " + talk.optString("text");
        }
        JSONObject now = s.optJSONObject("now");
        String line = now == null ? "" : "\u266A " + now.optString("label");
        String next = s.optString("next", "");
        if (!next.isEmpty() && !"null".equals(next)) {
            line += "  \u00B7  next: " + next;
        }
        return line;
    }

    private View card(final JSONObject s) {
        final long id = s.optLong("id");
        final boolean live = s.optBoolean("live");
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(14), dp(12), dp(14), dp(12));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(12));
        bg.setColor(ProfileUi.dark(this) ? 0xFF1E2833 : 0xFFF2F5F9);
        c.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(6), 0, dp(6));
        c.setLayoutParams(lp);

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(ProfileUi.text(this, s.optString("name"), 18, true),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (live) {
            TextView onAir = ProfileUi.text(this, " ON AIR ", 11, true);
            onAir.setTextColor(0xFFFFFFFF);
            GradientDrawable red = new GradientDrawable();
            red.setCornerRadius(dp(4));
            red.setColor(0xFFC62828);
            onAir.setBackground(red);
            head.addView(onAir);
        }
        c.addView(head);
        String desc = s.optString("description");
        if (!desc.isEmpty()) {
            TextView d = ProfileUi.text(this, desc, 13, false);
            d.setAlpha(0.7f);
            c.addView(d);
        }
        if (live) {
            TextView now = ProfileUi.text(this, nowLine(s), 14, false);
            now.setPadding(0, dp(6), 0, 0);
            c.addView(now);
            TextView who = ProfileUi.text(this, "with " + s.optString("djName") + "  \u00B7  "
                    + s.optInt("listeners") + " listening", 12, false);
            who.setAlpha(0.6f);
            c.addView(who);
        } else {
            TextView off = ProfileUi.text(this, "Off air", 14, false);
            off.setAlpha(0.6f);
            c.addView(off);
        }
        final boolean mine = RadioPlayer.isPlaying(id);
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setText(mine ? "\u25A0  Stop" : "\u25B6  Listen");
        b.setEnabled(live || mine);
        b.setOnClickListener(v -> {
            if (RadioPlayer.isPlaying(id)) {
                RadioPlayer.stop();
            } else {
                RadioPlayer.play(this, prefs.getString("host", ""), id, s.optString("name"));
            }
            render();
        });
        c.addView(b);
        return c;
    }
}