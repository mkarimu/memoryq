package com.vstream.broadcaster;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
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
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** One chat room. Anyone can read; posting needs an account. */
public class ChatRoomActivity extends AppCompatActivity {

    static final String EXTRA_ROOM = "room";
    static final String EXTRA_TITLE = "title";
    static final String EXTRA_OWNER = "owner";

    private static final String PREFS = "vstream";
    private static final long POLL_MS = 2000;

    private SharedPreferences prefs;
    private String roomId;
    private String roomOwner;
    private TextView statusView;
    private TextView errorView;
    private TextView replyText;
    private ScrollView scroll;
    private LinearLayout list;
    private View replyBar;
    private View inputRow;
    private View viewerBar;
    private EditText input;
    private Button sendBtn;

    private long replyToId = 0;
    private String signature = "";
    private volatile boolean sending = false;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final SimpleDateFormat clock = new SimpleDateFormat("HH:mm", Locale.getDefault());

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
        setContentView(R.layout.activity_chat_room);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);

        roomId = getIntent().getStringExtra(EXTRA_ROOM);
        roomOwner = getIntent().getStringExtra(EXTRA_OWNER);
        String title = getIntent().getStringExtra(EXTRA_TITLE);
        if (roomId == null) {
            finish();
            return;
        }
        statusView = findViewById(R.id.roomStatus);
        errorView = findViewById(R.id.roomError);
        replyText = findViewById(R.id.replyText);
        scroll = findViewById(R.id.roomScroll);
        list = findViewById(R.id.roomMessages);
        replyBar = findViewById(R.id.replyBar);
        inputRow = findViewById(R.id.inputRow);
        viewerBar = findViewById(R.id.viewerBar);
        input = findViewById(R.id.roomInput);
        sendBtn = findViewById(R.id.roomSend);
        ((TextView) findViewById(R.id.roomTitle)).setText(title == null ? roomId : title);

        findViewById(R.id.roomBack).setOnClickListener(v -> finish());
        findViewById(R.id.roomHome).setOnClickListener(v -> {
            Intent i = new Intent(this, HomeActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(i);
            finish();
        });
        findViewById(R.id.joinBtn).setOnClickListener(
                v -> startActivity(new Intent(this, SettingsActivity.class)));
        findViewById(R.id.replyClose).setOnClickListener(v -> clearReply());
        sendBtn.setOnClickListener(v -> send());
    }

    @Override
    protected void onStart() {
        super.onStart();
        applyAccountState();
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

    private String ownName() {
        return prefs.getString("username", "");
    }

    private void applyAccountState() {
        boolean signed = Account.signedIn(prefs);
        inputRow.setVisibility(signed ? View.VISIBLE : View.GONE);
        viewerBar.setVisibility(signed ? View.GONE : View.VISIBLE);
        if (!signed) {
            clearReply();
        }
    }

    // ---- Loading messages ----

    private void fetch() {
        final String host = prefs.getString("host", "");
        if (host.isEmpty()) {
            statusView.setText("Set the server in Settings first");
            return;
        }
        executor.execute(() -> {
            Api.Result r = Api.get(host, "/api/chat/messages?room=" + roomId + "&limit=50", null);
            ui(() -> showMessages(r));
        });
    }

    private void showMessages(Api.Result r) {
        if (r.code == 404) {
            statusView.setText("This room no longer exists");
            return;
        }
        if (r.code != 200) {
            statusView.setText(r.code == 0 ? "Cannot reach the server" : r.error());
            return;
        }
        statusView.setText("");
        JSONObject data = r.json();
        JSONArray arr = data.optJSONArray("messages");
        if (arr == null) {
            return;
        }
        StringBuilder sig = new StringBuilder();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject m = arr.optJSONObject(i);
            if (m != null) {
                sig.append(m.optLong("id")).append(m.optBoolean("deleted") ? "d" : "").append(',');
            }
        }
        ChatClient.markSeen(prefs, roomId, data.optLong("lastId", 0));
        if (sig.toString().equals(signature)) {
            return;
        }
        signature = sig.toString();

        View content = scroll.getChildAt(0);
        boolean atBottom = content == null
                || scroll.getScrollY() + scroll.getHeight() >= content.getHeight() - dp(60);
        list.removeAllViews();
        if (arr.length() == 0) {
            TextView empty = text("No messages yet. Say hello!", 15, false, 0xFF757575);
            empty.setPadding(dp(16), dp(16), dp(16), dp(16));
            list.addView(empty);
        }
        for (int i = 0; i < arr.length(); i++) {
            JSONObject m = arr.optJSONObject(i);
            if (m != null) {
                list.addView(bubble(m));
            }
        }
        if (atBottom) {
            scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
        }
    }

    private TextView text(String value, int sp, boolean bold, int color) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) {
            t.setTypeface(t.getTypeface(), Typeface.BOLD);
        }
        return t;
    }

    private View bubble(final JSONObject m) {
        String user = m.optString("user");
        String role = m.optString("role");
        boolean own = user.equalsIgnoreCase(ownName());
        boolean deleted = m.optBoolean("deleted");
        int maxWidth = (int) (getResources().getDisplayMetrics().widthPixels * 0.8f);

        LinearLayout b = new LinearLayout(this);
        b.setOrientation(LinearLayout.VERTICAL);
        b.setPadding(dp(10), dp(6), dp(10), dp(6));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(10));
        bg.setColor(own ? 0xFFDCF8C6 : 0xFFECEFF1);
        b.setBackground(bg);

        if (!own) {
            String tag = "GUEST".equals(role) ? " (guest)" : ("ADMIN".equals(role) ? " \u00b7 admin" : "");
            TextView name = text(user + tag, 14, true, 0xFF00695C);
            name.setMaxWidth(maxWidth);
            b.addView(name);
        }
        JSONObject reply = m.optJSONObject("reply");
        if (reply != null) {
            String quoted = reply.optBoolean("deleted") ? "message deleted" : reply.optString("text");
            TextView q = text(reply.optString("user") + ": " + quoted, 13, false, 0xFF424242);
            q.setBackgroundColor(0x1A000000);
            q.setPadding(dp(8), dp(4), dp(8), dp(4));
            q.setMaxWidth(maxWidth);
            q.setMaxLines(2);
            q.setEllipsize(android.text.TextUtils.TruncateAt.END);
            b.addView(q);
        }
        TextView body = text(deleted ? "Message deleted" : m.optString("text"), 16, false,
                deleted ? 0xFF757575 : 0xFF212121);
        if (deleted) {
            body.setTypeface(body.getTypeface(), Typeface.ITALIC);
        }
        body.setMaxWidth(maxWidth);
        b.addView(body);

        TextView time = text(clock.format(new Date(m.optLong("time"))), 12, false, 0xFF757575);
        time.setGravity(Gravity.END);
        b.addView(time, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = own ? Gravity.END : Gravity.START;
        lp.setMargins(dp(8), dp(3), dp(8), dp(3));
        b.setLayoutParams(lp);
        if (!deleted) {
            b.setOnLongClickListener(v -> {
                showMenu(m);
                return true;
            });
        }
        return b;
    }

    // ---- Message actions ----

    private void showMenu(final JSONObject m) {
        final long id = m.optLong("id");
        final String user = m.optString("user");
        final String body = m.optString("text");
        boolean signed = Account.signedIn(prefs);
        boolean mine = user.equalsIgnoreCase(ownName());
        boolean admin = "ADMIN".equals(prefs.getString("role", ""));
        boolean owner = roomOwner != null && !roomOwner.isEmpty() && roomOwner.equalsIgnoreCase(ownName());

        final List<String> items = new ArrayList<>();
        if (signed) {
            items.add("Reply");
        }
        items.add("Copy");
        if (signed && (mine || admin || owner)) {
            items.add("Delete");
        }
        new AlertDialog.Builder(this)
                .setItems(items.toArray(new String[0]), (d, which) -> {
                    String choice = items.get(which);
                    if ("Reply".equals(choice)) {
                        startReply(id, user, body);
                    } else if ("Copy".equals(choice)) {
                        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                        if (cm != null) {
                            cm.setPrimaryClip(ClipData.newPlainText("message", body));
                            Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show();
                        }
                    } else {
                        deleteMessage(id);
                    }
                })
                .show();
    }

    private void startReply(long id, String user, String body) {
        replyToId = id;
        String shortText = body.length() > 60 ? body.substring(0, 59) + "\u2026" : body;
        replyText.setText("Replying to " + user + ": " + shortText);
        replyBar.setVisibility(View.VISIBLE);
        input.requestFocus();
    }

    private void clearReply() {
        replyToId = 0;
        replyBar.setVisibility(View.GONE);
    }

    private void deleteMessage(final long id) {
        final String host = prefs.getString("host", "");
        final String session = prefs.getString("session", "");
        executor.execute(() -> {
            Api.Result r = Api.post(host, "/api/chat/delete",
                    Api.form("room", roomId, "id", String.valueOf(id)), session);
            if (r.code == 401) {
                Account.clear(prefs);
            }
            ui(() -> {
                if (r.code == 200) {
                    errorView.setText("");
                    fetch();
                } else {
                    errorView.setText(r.error());
                    applyAccountState();
                }
            });
        });
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
        final long reply = replyToId;
        executor.execute(() -> {
            Api.Result r = Api.post(host, "/api/chat/post",
                    Api.form("room", roomId, "text", text, "reply", String.valueOf(reply)), session);
            if (r.code == 401) {
                Account.clear(prefs);
            }
            ui(() -> {
                sending = false;
                sendBtn.setEnabled(true);
                if (r.code == 200) {
                    input.setText("");
                    clearReply();
                    fetch();
                } else {
                    errorView.setText(r.code == 401 ? "Your sign-in ended. Sign in again." : r.error());
                    applyAccountState();
                }
            });
        });
    }
}