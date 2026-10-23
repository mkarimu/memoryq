package com.vstream.broadcaster;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONObject;

/** One post on its own screen, with its comments already open (used when a notification is tapped). */
public class PostActivity extends AppCompatActivity {

    static final String EXTRA_ID = "post";

    private SharedPreferences prefs;
    private LinearLayout content;
    private TextView status;
    private long postId;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("vstream", MODE_PRIVATE);
        postId = getIntent().getLongExtra(EXTRA_ID, 0);
        if (postId <= 0) {
            finish();
            return;
        }
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setFitsSystemWindows(true);
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(ProfileUi.dp(this, 8), ProfileUi.dp(this, 4), ProfileUi.dp(this, 16), ProfileUi.dp(this, 4));
        TextView back = ProfileUi.text(this, "\u2039", 30, true);
        back.setPadding(ProfileUi.dp(this, 10), 0, ProfileUi.dp(this, 14), ProfileUi.dp(this, 4));
        back.setOnClickListener(v -> finish());
        bar.addView(back);
        bar.addView(ProfileUi.text(this, "Post", 20, true));
        root.addView(bar);
        ScrollView scroll = new ScrollView(this);
        LinearLayout inner = new LinearLayout(this);
        inner.setOrientation(LinearLayout.VERTICAL);
        inner.setPadding(ProfileUi.dp(this, 12), 0, ProfileUi.dp(this, 12), ProfileUi.dp(this, 24));
        status = ProfileUi.text(this, "Loading...", 14, false);
        inner.addView(status);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        inner.addView(content);
        scroll.addView(inner);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
        load();
    }

    private void load() {
        final String host = prefs.getString("host", "");
        final String session = prefs.getString("session", "");
        new Thread(() -> {
            final Api.Result r = Api.get(host, "/api/wall/one?id=" + postId, session);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                JSONObject p = r.code == 200 ? r.json().optJSONObject("post") : null;
                if (p == null) {
                    status.setText(r.code == 0 ? "Cannot reach the server" : "This post was deleted or is not visible to you.");
                    return;
                }
                status.setVisibility(View.GONE);
                View card = WallUi.card(this, prefs, p, false);
                content.addView(card);
                View toggle = card.findViewWithTag("comments-toggle");
                if (toggle != null) {
                    toggle.performClick();
                }
            });
        }).start();
    }
}