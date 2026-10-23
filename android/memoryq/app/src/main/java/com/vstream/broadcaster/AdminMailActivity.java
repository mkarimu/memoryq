package com.vstream.broadcaster;

import android.content.SharedPreferences;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** The private messages the super-admin sent to you as a group admin, with a reply box. Never shown in any chat. */
public class AdminMailActivity extends AppCompatActivity {

    private static final String PREFS = "vstream";
    private static final long POLL_MS = 4000;

    private SharedPreferences prefs;
    private TextView statusView;
    private TextView errorView;
    private ScrollView scroll;
    private LinearLayout list;
    private View replyRow;
    private EditText input;
    private Button sendBtn;
    private String signature = "";
    private boolean sending = false;
    private final SimpleDateFormat clock = new SimpleDateFormat("d MMM HH:mm", Locale.getDefault());
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            fetch();
            handler.postDelayed(this, POLL_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_admin_mail);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        statusView = findViewById(R.id.amStatus);
        errorView = findViewById(R.id.amError);
        scroll = findViewById(R.id.amScroll);
        list = findViewById(R.id.amList);
        replyRow = findViewById(R.id.amReplyRow);
        input = findViewById(R.id.amInput);
        sendBtn = findViewById(R.id.amSend);
        findViewById(R.id.amBack).setOnClickListener(v -> finish());
        sendBtn.setOnClickListener(v -> send());
    }

    @Override
    protected void onStart() {
        super.onStart();
        signature = "";
        handler.post(poll);
    }

    @Override
    protected void onStop() {
        super.onStop();
        handler.removeCallbacks(poll);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void ui(Runnable r) {
        runOnUiThread(() -> {
            if (!isFinishing() && !isDestroyed()) {
                r.run();
            }
        });
    }

    private void fetch() {
        final String host = prefs.getString("host", "");
        final String session = prefs.getString("session", "");
        if (host.isEmpty() || !Account.signedIn(prefs)) {
            statusView.setText("Sign in to see messages from the super-admin");
            return;
        }
        executor.execute(() -> {
            final Api.Result r = Api.get(host, "/api/chat/adminmail", session);
            if (r.code == 200 && r.json().optInt("unread", 0) > 0) {
                Api.post(host, "/api/chat/adminmail/read", "", session);
            }
            if (r.code == 401) {
                Account.clear(prefs);
            }
            ui(() -> show(r));
        });
    }

    private void show(Api.Result r) {
        if (r.code != 200) {
            statusView.setText(r.code == 0 ? "Cannot reach the server" : r.error());
            return;
        }
        JSONArray arr = r.json().optJSONArray("messages");
        int n = arr == null ? 0 : arr.length();
        prefs.edit().putInt("adminMailUnread", 0).putInt("adminMailTotal", n).apply();
        statusView.setText(n == 0 ? "No messages from the super-admin." : "");
        boolean fromAdmin = false;
        StringBuilder sig = new StringBuilder();
        for (int i = 0; i < n; i++) {
            JSONObject m = arr.optJSONObject(i);
            if (m != null) {
                sig.append(m.optLong("id")).append(m.optBoolean("read") ? "r" : "u").append(',');
                fromAdmin |= m.optBoolean("fromAdmin");
            }
        }
        replyRow.setVisibility(fromAdmin ? View.VISIBLE : View.GONE);
        if (sig.toString().equals(signature)) {
            return;
        }
        signature = sig.toString();
        list.removeAllViews();
        for (int i = 0; i < n; i++) {
            JSONObject m = arr.optJSONObject(i);
            if (m != null) {
                list.addView(bubble(m));
            }
        }
        scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
    }

    private View bubble(JSONObject m) {
        boolean fromAdmin = m.optBoolean("fromAdmin");
        int maxWidth = (int) (getResources().getDisplayMetrics().widthPixels * 0.8f);
        LinearLayout b = new LinearLayout(this);
        b.setOrientation(LinearLayout.VERTICAL);
        b.setPadding(dp(10), dp(6), dp(10), dp(6));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(10));
        bg.setColor(fromAdmin ? 0xFFFFF3E0 : 0xFFDCF8C6);
        b.setBackground(bg);
        if (fromAdmin) {
            TextView who = new TextView(this);
            who.setText("Super-admin");
            who.setTextSize(14);
            who.setTextColor(0xFFE65100);
            who.setTypeface(who.getTypeface(), android.graphics.Typeface.BOLD);
            b.addView(who);
        }
        TextView body = new TextView(this);
        body.setText(m.optString("text"));
        body.setTextSize(16);
        body.setTextColor(0xFF212121);
        body.setMaxWidth(maxWidth);
        b.addView(body);
        TextView time = new TextView(this);
        time.setText(clock.format(new Date(m.optLong("time"))));
        time.setTextSize(12);
        time.setTextColor(0xFF757575);
        time.setGravity(Gravity.END);
        b.addView(time, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = fromAdmin ? Gravity.START : Gravity.END;
        lp.setMargins(dp(8), dp(3), dp(8), dp(3));
        b.setLayoutParams(lp);
        return b;
    }

    private void send() {
        final String text = input.getText().toString().trim();
        if (text.isEmpty() || sending) {
            return;
        }
        sending = true;
        sendBtn.setEnabled(false);
        errorView.setText("");
        final String host = prefs.getString("host", "");
        final String session = prefs.getString("session", "");
        executor.execute(() -> {
            Api.Result r = Api.post(host, "/api/chat/adminmail/send", Api.form("text", text), session);
            ui(() -> {
                sending = false;
                sendBtn.setEnabled(true);
                if (r.code == 200) {
                    input.setText("");
                    fetch();
                } else {
                    errorView.setText(r.code == 0 ? "Cannot reach the server" : r.error());
                }
            });
        });
    }
}