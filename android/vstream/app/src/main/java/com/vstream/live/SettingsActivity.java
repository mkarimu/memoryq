package com.vstream.live;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.DateFormat;
import java.util.Date;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Settings: where the stream goes (Memoryq by default, or the user's own RTMP server), quality,
 * Advanced "also stream to" X / Facebook / Twitch / YouTube / TikTok, the legal texts, and the account.
 */
public class SettingsActivity extends AppCompatActivity {

    static final String EXTRA_FIRST_OWN = "firstOwn";

    private final ExecutorService pool = Executors.newSingleThreadExecutor();
    private SharedPreferences prefs;
    private Switch ownSwitch;
    private LinearLayout ownBox;
    private TextView memoryqNote;
    private EditText ownUrl;
    private EditText ownKey;
    private LinearLayout advanced;
    private LinearLayout platformList;
    private TextView advancedNote;
    private Button advancedToggle;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = Prefs.of(this);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Ui.BG);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(this, 18);
        col.setPadding(pad, Ui.dp(this, 6), pad, Ui.dp(this, 40));
        scroll.addView(col);

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(Ui.title(this, "Settings"), new LinearLayout.LayoutParams(0, -2, 1f));
        Button done = Ui.button(this, "Done", Ui.ACCENT);
        done.setOnClickListener(v -> done());
        head.addView(done, new LinearLayout.LayoutParams(Ui.dp(this, 100), Ui.dp(this, 44)));
        col.addView(head);

        // ---- Destination ----
        Ui.add(col, Ui.section(this, "Destination"), 0);
        LinearLayout dest = Ui.card(this);
        memoryqNote = Ui.note(this, "");
        dest.addView(memoryqNote);
        ownSwitch = new Switch(this);
        ownSwitch.setText("Use my own RTMP server");
        ownSwitch.setTextColor(Ui.INK);
        ownSwitch.setTextSize(16);
        ownSwitch.setChecked(Prefs.ownServer(this));
        Ui.add(dest, ownSwitch, 12);
        ownBox = new LinearLayout(this);
        ownBox.setOrientation(LinearLayout.VERTICAL);
        ownUrl = Ui.field(this, "rtmp://your-server.com/live", false);
        ownUrl.setText(prefs.getString("ownUrl", ""));
        ownKey = Ui.field(this, "Stream key (if your server needs one)", true);
        ownKey.setText(prefs.getString("ownKey", ""));
        Ui.add(ownBox, Ui.note(this, "Server address (rtmp:// or rtmps://)"), 8);
        Ui.add(ownBox, ownUrl, 4);
        Ui.add(ownBox, Ui.note(this, "Stream key"), 10);
        Ui.add(ownBox, ownKey, 4);
        Ui.add(ownBox, Ui.note(this, "Vstream sends your stream straight to SERVER/KEY. Memoryq is not involved, and \"Also stream to\" "
                + "is not available while this is on. You are responsible for your server - see Other platforms and your own server."), 10);
        Ui.add(dest, ownBox, 4);
        ownSwitch.setOnCheckedChangeListener((b, on) -> {
            prefs.edit().putBoolean("ownOn", on).apply();
            if (!on && !Prefs.signedIn(this)) {
                startActivity(new Intent(this, SignInActivity.class));
                finish();
                return;
            }
            refreshDestination();
        });
        Ui.add(col, dest, 0);

        // ---- Quality ----
        Ui.add(col, Ui.section(this, "Video quality"), 0);
        LinearLayout qcard = Ui.card(this);
        RadioGroup rg = new RadioGroup(this);
        int[] qs = {480, 720, 1080};
        String[] ql = {"480p - for weak connections (about 0.6 GB per hour)", "720p HD - recommended (about 1.2 GB per hour)",
            "1080p Full HD - needs a strong connection (about 2 GB per hour)"};
        for (int i = 0; i < qs.length; i++) {
            RadioButton r = new RadioButton(this);
            r.setId(View.generateViewId());
            r.setText(ql[i]);
            r.setTextColor(Ui.INK);
            r.setChecked(Prefs.quality(this) == qs[i]);
            int q = qs[i];
            r.setOnCheckedChangeListener((b, on) -> {
                if (on) {
                    prefs.edit().putInt("quality", q).apply();
                }
            });
            rg.addView(r);
        }
        qcard.addView(rg);
        Ui.add(col, qcard, 0);

        // ---- Advanced: also stream to ----
        advancedToggle = Ui.flat(this, "Advanced settings  \u25B8");
        advancedToggle.setOnClickListener(v -> {
            boolean show = advanced.getVisibility() != View.VISIBLE;
            advanced.setVisibility(show ? View.VISIBLE : View.GONE);
            advancedToggle.setText(show ? "Advanced settings  \u25BE" : "Advanced settings  \u25B8");
            if (show) {
                loadPlatforms();
            }
        });
        Ui.add(col, advancedToggle, 14);
        advanced = new LinearLayout(this);
        advanced.setOrientation(LinearLayout.VERTICAL);
        advanced.setVisibility(View.GONE);
        Ui.add(advanced, Ui.section(this, "Also stream to"), 0);
        LinearLayout acard = Ui.card(this);
        advancedNote = Ui.note(this, "");
        acard.addView(advancedNote);
        platformList = new LinearLayout(this);
        platformList.setOrientation(LinearLayout.VERTICAL);
        Ui.add(acard, platformList, 6);
        advanced.addView(acard);
        Ui.add(col, advanced, 0);

        // ---- Legal ----
        Ui.add(col, Ui.section(this, "Terms and policies"), 0);
        LinearLayout lcard = Ui.card(this);
        String[][] docs = {
            {"Terms of Use", "terms"}, {"Streaming rules", "guidelines"}, {"Privacy policy", "privacy"},
            {"Copyright and music", "copyright"}, {"Other platforms and your own server", "platforms"}, {"Disclaimers and safety", "disclaimer"},
        };
        for (String[] d : docs) {
            Button b = Ui.flat(this, d[0] + "  \u203A");
            b.setOnClickListener(v -> Ui.openDoc(this, d[1]));
            lcard.addView(b);
        }
        if (Ads.privacyChoicesNeeded(this)) {
            Button pc = Ui.flat(this, "Privacy choices (ads)  \u203A");
            pc.setOnClickListener(v -> Ads.showPrivacyChoices(this));
            lcard.addView(pc);
        }
        long at = prefs.getLong("agreedAt", 0);
        if (at > 0) {
            Ui.add(lcard, Ui.note(this, "You agreed on " + DateFormat.getDateInstance().format(new Date(at)) + "."), 4);
        }
        Ui.add(col, lcard, 0);

        // ---- Account ----
        Ui.add(col, Ui.section(this, "Account"), 0);
        LinearLayout ucard = Ui.card(this);
        if (Prefs.signedIn(this)) {
            ucard.addView(Ui.text(this, "Signed in as " + prefs.getString("username", ""), 16, Ui.INK, true));
            Button out = Ui.flat(this, "Sign out");
            out.setOnClickListener(v -> signOut());
            Ui.add(ucard, out, 6);
            Button del = Ui.flat(this, "Delete my account and data  \u203A");
            del.setTextColor(Ui.LIVE);
            del.setOnClickListener(v -> Ui.openWeb(this, Api.base(Prefs.host(this)) + "/privacy#delete"));
            ucard.addView(del);
        } else {
            ucard.addView(Ui.note(this, "Not signed in. Sign in to stream to Memoryq and to use \"Also stream to\"."));
            Button in = Ui.flat(this, "Sign in or create an account  \u203A");
            in.setOnClickListener(v -> {
                startActivity(new Intent(this, SignInActivity.class));
                finish();
            });
            Ui.add(ucard, in, 6);
        }
        Ui.add(col, ucard, 0);
        Ui.add(col, Ui.note(this, "Vstream 1.0 \u00B7 Memoryq \u00B7 support@memoryq.com"), 20);

        setContentView(scroll);
        Ui.edgeSafe(this);
        // Back = Done (new Android versions no longer call onBackPressed)
        getOnBackPressedDispatcher().addCallback(this, new androidx.activity.OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                done();
            }
        });
        refreshDestination();
        if (getIntent().getBooleanExtra(EXTRA_FIRST_OWN, false)) {
            ownUrl.requestFocus();
        }
    }

    private void refreshDestination() {
        boolean own = ownSwitch.isChecked();
        ownBox.setVisibility(own ? View.VISIBLE : View.GONE);
        String ch = prefs.getString("channel", "");
        memoryqNote.setText(own ? "Your stream goes to your own server." : "Memoryq (default): you get a free live channel"
                + (ch.isEmpty() ? "" : " - now " + ch) + " and your stream key is set up for you. Viewers watch on memoryq.com.");
        if (advanced.getVisibility() == View.VISIBLE) {
            loadPlatforms();
        }
    }

    private boolean saveOwn() {
        String url = ownUrl.getText().toString().trim();
        if (ownSwitch.isChecked() && !url.matches("(?i)^rtmps?://[^\\s/]+.*")) {
            ownUrl.setError("Start with rtmp:// or rtmps://");
            ownUrl.requestFocus();
            return false;
        }
        prefs.edit().putString("ownUrl", url).putString("ownKey", ownKey.getText().toString().trim()).apply();
        return true;
    }

    /** Leaving Settings never gets stuck: an unfinished own-server setup can be dropped in one tap. */
    private void done() {
        if (!saveOwn()) {
            new AlertDialog.Builder(this).setTitle("Your own server is not set up")
                    .setMessage("Enter its rtmp:// address, or go back to streaming on Memoryq.")
                    .setNegativeButton("Keep editing", null)
                    .setPositiveButton("Use Memoryq instead", (d, w) -> useMemoryq())
                    .show();
            return;
        }
        startActivity(new Intent(this, LiveActivity.class));
        finish();
    }

    private void useMemoryq() {
        ownUrl.setError(null);
        prefs.edit().putBoolean("ownOn", false).apply();
        startActivity(new Intent(this, Prefs.signedIn(this) ? LiveActivity.class : SignInActivity.class));
        finish();
    }

    private void signOut() {
        String host = Prefs.host(this);
        String token = Prefs.session(this);
        pool.execute(() -> Api.post(host, "/api/logout", "", token));
        Prefs.signOut(this);
        prefs.edit().putBoolean("ownOn", false).apply();
        startActivity(new Intent(this, SignInActivity.class));
        finish();
    }

    // ---- Also stream to ----

    private static String help(String platform) {
        switch (platform) {
            case "youtube":
                return "YouTube Studio - Create - Go live - Stream. Copy the Stream key. The server is filled in for you.";
            case "facebook":
                return "Facebook - Live video - Streaming software. Copy the Stream key. The secure (rtmps) server is filled in for you.";
            case "twitch":
                return "Twitch Creator Dashboard - Settings - Stream. Copy the Primary Stream key.";
            case "x":
                return "X Media Studio - Producer - Sources - create an RTMP source. Copy its RTMP URL and Stream key (X may require a Premium or verified account).";
            case "tiktok":
                return "TikTok LIVE Center / LIVE Studio - stream key. Copy the Server URL and Stream key (needs TikTok LIVE access).";
            default:
                return "Any RTMP or RTMPS server: its address and stream key.";
        }
    }

    private void loadPlatforms() {
        platformList.removeAllViews();
        if (ownSwitch.isChecked()) {
            advancedNote.setText("\"Also stream to\" works when Memoryq is your destination. Turn off \"Use my own server\" to use it.");
            return;
        }
        if (!Prefs.signedIn(this)) {
            advancedNote.setText("Sign in to use \"Also stream to\".");
            return;
        }
        advancedNote.setText("When you go live on Memoryq, memoryq.com also sends your stream to the platforms you switch on here, "
                + "so you are live everywhere at once. Get a stream key from each platform. Vstream is not affiliated with these platforms, "
                + "and their own rules apply.");
        TextView loading = Ui.note(this, "Loading...");
        platformList.addView(loading);
        String host = Prefs.host(this);
        String token = Prefs.session(this);
        pool.execute(() -> {
            Api.Result r = Api.get(host, "/api/restream", token);
            runOnUiThread(() -> {
                if (isFinishing()) {
                    return;
                }
                if (r.code == 200) {
                    drawPlatforms(r.json().optJSONArray("platforms"));
                } else {
                    loading.setText(r.code == 404 ? "This server does not offer \"Also stream to\" yet." : r.error());
                }
            });
        });
    }

    private void drawPlatforms(JSONArray list) {
        platformList.removeAllViews();
        if (list == null) {
            return;
        }
        for (int i = 0; i < list.length(); i++) {
            JSONObject p = list.optJSONObject(i);
            if (p == null) {
                continue;
            }
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 10));
            LinearLayout txt = new LinearLayout(this);
            txt.setOrientation(LinearLayout.VERTICAL);
            txt.addView(Ui.text(this, p.optString("label"), 16, Ui.INK, true));
            boolean saved = p.optBoolean("saved", false);
            String sub = saved ? "Key " + p.optString("keyHint", "saved") : "Not set up";
            String st = p.optString("state", "");
            if (!st.isEmpty()) {
                sub += " \u00B7 last stream: " + Ui.mask(st);
            }
            txt.addView(Ui.note(this, sub));
            row.addView(txt, new LinearLayout.LayoutParams(0, -2, 1f));
            Button edit = Ui.flat(this, saved ? "Edit" : "Set up");
            edit.setOnClickListener(v -> editPlatform(p));
            row.addView(edit, new LinearLayout.LayoutParams(-2, Ui.dp(this, 44)));
            Switch on = new Switch(this);
            on.setChecked(p.optBoolean("on", false));
            on.setEnabled(saved);
            on.setContentDescription("Stream to " + p.optString("label"));
            on.setOnCheckedChangeListener((b, checked) -> save(p.optString("platform"), checked, p.optString("url"), "", null));
            row.addView(on);
            platformList.addView(row);
            if (i < list.length() - 1) {
                View line = new View(this);
                line.setBackgroundColor(Ui.LINE);
                platformList.addView(line, new LinearLayout.LayoutParams(-1, Ui.dp(this, 1)));
            }
        }
    }

    private void editPlatform(JSONObject p) {
        String platform = p.optString("platform");
        String label = p.optString("label");
        boolean saved = p.optBoolean("saved", false);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(this, 20);
        box.setPadding(pad, Ui.dp(this, 6), pad, 0);
        box.addView(Ui.note(this, help(platform)));
        String current = p.optString("url", "");
        if (current.isEmpty()) {
            current = p.optString("defaultUrl", "");
        }
        EditText url = Ui.field(this, "rtmp:// or rtmps:// server address", false);
        url.setText(current);
        EditText key = Ui.field(this, saved ? "Saved (" + p.optString("keyHint") + ") - leave empty to keep" : "Stream key", true);
        Ui.add(box, Ui.note(this, "Server"), 12);
        Ui.add(box, url, 4);
        Ui.add(box, Ui.note(this, "Stream key"), 10);
        Ui.add(box, key, 4);
        Ui.add(box, Ui.note(this, "By saving you confirm you may stream to " + label + " and will follow its rules. Keep your key private - "
                + "anyone with it can stream as you. Sending to more platforms does not use more of your phone's data."), 10);
        TextView err = Ui.text(this, "", 13, Ui.LIVE, false);
        Ui.add(box, err, 6);
        ScrollView sv = new ScrollView(this);
        sv.addView(box);
        AlertDialog.Builder b = new AlertDialog.Builder(this).setTitle(label).setView(sv)
                .setNegativeButton("Cancel", null).setPositiveButton("Save and switch on", null);
        if (saved) {
            b.setNeutralButton("Remove", (d, w) -> remove(platform));
        }
        AlertDialog d = b.create();
        d.setOnShowListener(x -> d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v ->
                save(platform, true, url.getText().toString().trim(), key.getText().toString().trim(), new Runnable() {
                    @Override
                    public void run() {
                        d.dismiss();
                    }
                }, err)));
        d.show();
    }

    private void save(String platform, boolean on, String url, String key, Runnable ok) {
        save(platform, on, url, key, ok, null);
    }

    private void save(String platform, boolean on, String url, String key, Runnable ok, TextView err) {
        String host = Prefs.host(this);
        String token = Prefs.session(this);
        String body = Api.form("platform", platform, "on", String.valueOf(on), "url", url, "key", key);
        pool.execute(() -> {
            Api.Result r = Api.post(host, "/api/restream/save", body, token);
            runOnUiThread(() -> {
                if (isFinishing()) {
                    return;
                }
                if (r.code == 200) {
                    if (ok != null) {
                        ok.run();
                    }
                    drawPlatforms(r.json().optJSONArray("platforms"));
                } else if (err != null) {
                    err.setText(r.error());
                } else {
                    Ui.toast(this, r.error());
                    loadPlatforms();
                }
            });
        });
    }

    private void remove(String platform) {
        String host = Prefs.host(this);
        String token = Prefs.session(this);
        pool.execute(() -> {
            Api.Result r = Api.post(host, "/api/restream/remove", Api.form("platform", platform), token);
            runOnUiThread(() -> {
                if (r.code == 200) {
                    drawPlatforms(r.json().optJSONArray("platforms"));
                } else {
                    Ui.toast(this, r.error());
                }
            });
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        pool.shutdownNow();
    }
}