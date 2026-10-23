package com.vstream.broadcaster;

import android.content.SharedPreferences;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Everyone on the server, searchable by name, stage name or country. Tap a person to open their profile. */
public class PeopleActivity extends AppCompatActivity {

    private SharedPreferences prefs;
    private EditText search;
    private TextView status;
    private LinearLayout list;
    private int requestNo = 0;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Runnable doSearch = this::load;

    /**
     * Each person's own card colour, always the same (worked out from their username, so it is the same on every
     * phone and never changes): any shade around the colour wheel, kept deep enough for white text.
     */
    /** The card colour the person chose (sent by the server), or their automatic one. */
    static int colorFor(JSONObject p) {
        String c = p == null ? "" : p.optString("color");
        if (c.matches("#[0-9a-fA-F]{6}")) {
            return 0xFF000000 | Integer.parseInt(c.substring(1), 16);
        }
        return colorFor(p == null ? "" : p.optString("username"));
    }

    static int colorFor(String username) {
        int hue = 210;
        try {
            byte[] d = java.security.MessageDigest.getInstance("MD5")
                    .digest((username == null ? "" : username.toLowerCase(Locale.ROOT)).getBytes("UTF-8"));
            hue = (((d[0] & 0xff) << 8) | (d[1] & 0xff)) % 360;
        } catch (Exception e) {
            // keep the default
        }
        return android.graphics.Color.HSVToColor(new float[]{hue, 0.50f, 0.48f});
    }

    private boolean isDark() {
        return ProfileUi.dark(this);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("vstream", MODE_PRIVATE);
        boolean dark = isDark();
        int ink = dark ? 0xFFE6EBF0 : 0xFF1B1F23;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setFitsSystemWindows(true);
        root.setBackgroundColor(dark ? 0xFF0F1419 : 0xFFE6E9ED);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setBackgroundColor(dark ? 0xFF182029 : 0xFFFFFFFF);
        header.setPadding(dp(12), dp(6), dp(12), dp(10));
        header.setElevation(dp(2));
        TextView back = new TextView(this);
        back.setText("\u2039  Back");
        back.setTextSize(15);
        back.setTextColor(dark ? 0xFF9FB0C0 : 0xFF5F6E7D);
        back.setPadding(dp(4), dp(8), dp(12), dp(4));
        back.setOnClickListener(v -> finish());
        LinearLayout backRow = new LinearLayout(this);
        backRow.addView(back);
        header.addView(backRow);

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this);
        title.setText("People");
        title.setTextSize(24);
        title.setTextColor(ink);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        title.setPadding(dp(4), 0, dp(12), 0);
        titleRow.addView(title);
        search = new EditText(this);
        search.setHint("\uD83D\uDD0D  Name, stage name or country");
        search.setSingleLine(true);
        search.setTextSize(15);
        search.setTextColor(ink);
        search.setHintTextColor(dark ? 0xFF7D8B99 : 0xFF8A96A3);
        search.setPadding(dp(14), dp(8), dp(14), dp(8));
        GradientDrawable sbg = new GradientDrawable();
        sbg.setColor(dark ? 0xFF223040 : 0xFFF0F2F5);
        sbg.setCornerRadius(dp(20));
        search.setBackground(sbg);
        titleRow.addView(search, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        header.addView(titleRow);
        root.addView(header);
        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                handler.removeCallbacks(doSearch);
                handler.postDelayed(doSearch, 400);
            }
        });

        ScrollView scroll = new ScrollView(this);
        LinearLayout inner = new LinearLayout(this);
        inner.setOrientation(LinearLayout.VERTICAL);
        inner.setPadding(dp(8), dp(8), dp(8), dp(24));
        status = ProfileUi.text(this, "Loading...", 14, false);
        status.setPadding(dp(6), dp(4), dp(6), dp(4));
        inner.addView(status);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        inner.addView(list);
        scroll.addView(inner);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
    }

    /** A small round icon button (no text) on a person's card. */
    private TextView iconButton(String icon, String description, Runnable run) {
        TextView b = new TextView(this);
        b.setText(icon);
        b.setTextSize(16);
        b.setGravity(Gravity.CENTER);
        b.setContentDescription(description);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(0x33FFFFFF);
        b.setBackground(bg);
        b.setOnClickListener(v -> run.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(36), dp(36));
        lp.setMargins(0, 0, dp(10), 0);
        b.setLayoutParams(lp);
        return b;
    }

    /** One person as a card in their own colour: name, then @username and country; photo on the right, call / video / chat icons below. */
    private View personCard(String host, JSONObject p) {
        final String user = p.optString("username");
        String stage = p.optString("stageName");
        String full = ProfileUi.fullName(p);
        String country = p.optString("country");

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(16), dp(12), dp(14), dp(12));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(colorFor(p));
        bg.setCornerRadius(dp(6));
        card.setBackground(bg);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView name = new TextView(this);
        name.setText(!stage.isEmpty() ? stage : (!full.isEmpty() ? full : user));
        name.setTextSize(18);
        name.setTextColor(0xFFFFFFFF);
        name.setTypeface(name.getTypeface(), android.graphics.Typeface.BOLD);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        col.addView(name);
        TextView sub = new TextView(this);
        String who = (!stage.isEmpty() && !full.isEmpty() ? full + "  \u00B7  " : "") + "@" + user;
        sub.setText(who + (country.isEmpty() ? "" : "  \u00B7  " + country));
        sub.setTextSize(14);
        sub.setTextColor(0xE6FFFFFF);
        sub.setSingleLine(true);
        sub.setEllipsize(android.text.TextUtils.TruncateAt.END);
        sub.setPadding(0, dp(4), 0, 0);
        col.addView(sub);
        LinearLayout icons = new LinearLayout(this);
        icons.setOrientation(LinearLayout.HORIZONTAL);
        icons.setPadding(0, dp(6), 0, 0);
        if (CallStarter.enabled(prefs)) {
            icons.addView(iconButton("\uD83D\uDCDE", "Voice call " + user, () -> CallStarter.callPerson(this, prefs, user, false, null)));
            icons.addView(iconButton("\uD83C\uDFA5", "Video call " + user, () -> CallStarter.callPerson(this, prefs, user, true, null)));
        }
        icons.addView(iconButton("\uD83D\uDCAC", "Private chat with " + user, () -> PersonActions.privateChat(this, prefs, user)));
        col.addView(icons);
        card.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        FrameLayout box = new FrameLayout(this);
        ImageView av = new ImageView(this);
        av.setScaleType(ImageView.ScaleType.CENTER_CROP);
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(0x33FFFFFF);
        circle.setStroke(dp(2), 0x66FFFFFF);
        av.setBackground(circle);
        av.setClipToOutline(true);
        ImageLoader.load(av, host, user, true);
        box.addView(av, new FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(dp(54), dp(54));
        blp.setMargins(dp(8), 0, 0, 0);
        card.addView(box, blp);

        card.setOnClickListener(v -> ProfileUi.openProfile(this, user));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, dp(8));
        card.setLayoutParams(lp);
        return card;
    }

    @Override
    protected void onStart() {
        super.onStart();
        load();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacks(doSearch);
        executor.shutdownNow();
    }

    private int dp(int v) {
        return ProfileUi.dp(this, v);
    }

    private void load() {
        if (!Account.signedIn(prefs)) {
            status.setText("Sign in to see people.");
            return;
        }
        final String host = prefs.getString("host", "");
        final String session = prefs.getString("session", "");
        final String q = search.getText().toString().trim();
        final int my = ++requestNo;
        executor.execute(() -> {
            final Api.Result r = Api.get(host, "/api/profiles/people?" + Api.form("q", q), session);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed() || my != requestNo) {
                    return;
                }
                list.removeAllViews();
                if (r.code != 200) {
                    status.setVisibility(android.view.View.VISIBLE);
                    status.setText(r.code == 0 ? "Cannot reach the server" : r.error());
                    return;
                }
                JSONArray people = r.json().optJSONArray("people");
                int n = people == null ? 0 : people.length();
                status.setVisibility(n == 0 ? android.view.View.VISIBLE : android.view.View.GONE);
                status.setText(q.isEmpty() ? "Nobody else here yet." : "Nobody matches \"" + q + "\".");
                for (int i = 0; i < n; i++) {
                    JSONObject p = people.optJSONObject(i);
                    if (p != null) {
                        list.addView(personCard(host, p));
                    }
                }
            });
        });
    }
}