package com.vstream.broadcaster;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.StyleSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONArray;
import org.json.JSONObject;

/** Your notifications, newest first: comments, replies, likes and new friends. Tap one to open it. */
public class NotificationsActivity extends AppCompatActivity {

    private SharedPreferences prefs;
    private LinearLayout list;
    private TextView status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("vstream", MODE_PRIVATE);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setFitsSystemWindows(true);
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(8), dp(4), dp(16), dp(4));
        TextView back = ProfileUi.text(this, "\u2039", 30, true);
        back.setPadding(dp(10), 0, dp(14), dp(4));
        back.setOnClickListener(v -> finish());
        bar.addView(back);
        bar.addView(ProfileUi.text(this, "Notifications", 20, true));
        root.addView(bar);
        ScrollView scroll = new ScrollView(this);
        LinearLayout inner = new LinearLayout(this);
        inner.setOrientation(LinearLayout.VERTICAL);
        inner.setPadding(dp(12), 0, dp(12), dp(24));
        status = ProfileUi.text(this, "Loading...", 14, false);
        status.setPadding(dp(4), dp(8), 0, dp(8));
        inner.addView(status);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        inner.addView(list);
        scroll.addView(inner);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
    }

    @Override
    protected void onStart() {
        super.onStart();
        load();
    }

    private int dp(int v) {
        return ProfileUi.dp(this, v);
    }

    private void load() {
        final String host = prefs.getString("host", "");
        final String session = prefs.getString("session", "");
        new Thread(() -> {
            final Api.Result r = Api.get(host, "/api/notify/list?limit=100", session);
            if (r.code == 200) {
                Api.post(host, "/api/notify/read", "", session);
            }
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                list.removeAllViews();
                if (r.code != 200) {
                    status.setText(r.code == 0 ? "Cannot reach the server" : (r.code == 401 ? "Sign in to see notifications." : r.error()));
                    return;
                }
                JSONArray items = r.json().optJSONArray("items");
                int n = items == null ? 0 : items.length();
                status.setVisibility(n == 0 ? View.VISIBLE : View.GONE);
                status.setText("Nothing yet. When people comment on or like your posts, or add you as a friend, it shows here.");
                for (int i = 0; i < n; i++) {
                    JSONObject o = items.optJSONObject(i);
                    if (o != null) {
                        list.addView(row(o, host));
                    }
                }
            });
        }).start();
    }

    private View row(final JSONObject o, String host) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(6), dp(10), dp(6), dp(10));
        if (!o.optBoolean("read")) {
            row.setBackgroundColor(ProfileUi.dark(this) ? 0xFF1E2A38 : 0xFFE3F2FD);
        }
        final String actor = o.optString("actor");
        ImageView av = ProfileUi.roundImage(this, 44, true);
        ImageLoader.load(av, host, actor, true);
        row.addView(av);
        String stage = o.optString("stageName");
        String who = stage.isEmpty() ? actor : stage;
        String what;
        switch (o.optString("type")) {
            case "comment": what = " commented on your post"; break;
            case "reply": what = " replied to your comment"; break;
            case "like": what = " liked your post"; break;
            case "friend": what = " added you as a friend"; break;
            default: what = ""; break;
        }
        SpannableStringBuilder sb = new SpannableStringBuilder(who);
        sb.setSpan(new StyleSpan(android.graphics.Typeface.BOLD), 0, who.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.append(what);
        String text = o.optString("text");
        if (!text.isEmpty() && !"friend".equals(o.optString("type"))) {
            sb.append(": \u201c").append(text).append("\u201d");
        }
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding(dp(12), 0, 0, 0);
        TextView main = ProfileUi.text(this, "", 14, false);
        main.setText(sb);
        main.setMaxLines(3);
        main.setEllipsize(android.text.TextUtils.TruncateAt.END);
        texts.addView(main);
        TextView when = ProfileUi.text(this, WallUi.ago(o.optLong("created")), 12, false);
        when.setAlpha(0.6f);
        texts.addView(when);
        row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        final long post = o.optLong("post");
        row.setOnClickListener(v -> {
            if (post > 0) {
                Intent i = new Intent(this, PostActivity.class);
                i.putExtra(PostActivity.EXTRA_ID, post);
                startActivity(i);
            } else {
                ProfileUi.openProfile(this, actor);
            }
        });
        return row;
    }
}