package com.vstream.broadcaster;

import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputFilter;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Chat window: every public group and every live channel's room, with unread badges. */
public class ChatListActivity extends AppCompatActivity {

    private static final String PREFS = "vstream";
    private static final long REFRESH_MS = 4000;

    private SharedPreferences prefs;
    private TextView accountView;
    private TextView statusView;
    private Button joinBtn;
    private LinearLayout roomsView;
    private String signature = "";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            refreshRooms();
            handler.postDelayed(this, REFRESH_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_chat_list);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);

        accountView = findViewById(R.id.chatAccount);
        statusView = findViewById(R.id.chatListStatus);
        joinBtn = findViewById(R.id.chatJoin);
        roomsView = findViewById(R.id.chatRooms);

        findViewById(R.id.chatListHome).setOnClickListener(v -> {
            Intent i = new Intent(this, HomeActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(i);
            finish();
        });
        joinBtn.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
        findViewById(R.id.chatNewGroup).setOnClickListener(v -> newGroup());
    }

    @Override
    protected void onStart() {
        super.onStart();
        renderAccount();
        signature = "";
        handler.post(refresh);
    }

    @Override
    protected void onStop() {
        super.onStop();
        handler.removeCallbacks(refresh);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }

    private boolean isDark() {
        return (getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
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

    private void renderAccount() {
        if (Account.signedIn(prefs)) {
            accountView.setText("Signed in as " + prefs.getString("username", "") + " ("
                    + ChatClient.roleLabel(prefs.getString("role", "")) + ")");
            joinBtn.setVisibility(View.GONE);
        } else {
            accountView.setText("You are viewing. To chat, create an account or join as a guest.");
            joinBtn.setVisibility(View.VISIBLE);
        }
    }

    private void refreshRooms() {
        final String host = prefs.getString("host", "");
        final String session = prefs.getString("session", "");
        if (host.isEmpty()) {
            statusView.setText("Set the server in Settings first");
            return;
        }
        executor.execute(() -> {
            final JSONArray rooms = ChatClient.rooms(host);
            final Map<String, Integer> unread = rooms == null ? null
                    : ChatClient.unread(host, session, prefs, rooms);
            ui(() -> showRooms(rooms, unread));
        });
    }

    private void showRooms(JSONArray rooms, Map<String, Integer> unread) {
        if (rooms == null) {
            statusView.setText("Cannot reach the server");
            return;
        }
        statusView.setText(rooms.length() == 0 ? "No rooms yet" : "");
        StringBuilder sig = new StringBuilder();
        for (int i = 0; i < rooms.length(); i++) {
            JSONObject o = rooms.optJSONObject(i);
            if (o != null) {
                Integer u = unread.get(o.optString("id"));
                sig.append(o.optString("id")).append(':').append(o.optLong("lastId")).append(':')
                        .append(u == null ? 0 : u).append(';');
            }
        }
        if (sig.toString().equals(signature)) {
            return;
        }
        signature = sig.toString();
        roomsView.removeAllViews();
        for (int i = 0; i < rooms.length(); i++) {
            JSONObject o = rooms.optJSONObject(i);
            if (o != null) {
                Integer u = unread.get(o.optString("id"));
                roomsView.addView(row(o, u == null ? 0 : u));
            }
        }
    }

    private TextView text(String value, int sp, boolean bold, int color) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) {
            t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        }
        return t;
    }

    private View row(final JSONObject o, int unread) {
        final String id = o.optString("id");
        final String title = o.optString("title");
        final String owner = o.optString("owner");
        boolean channel = "channel".equals(o.optString("type"));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(12), dp(16), dp(12));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        col.addView(text(title + (channel ? "   \u25CF LIVE" : ""), 18, true, (isDark() ? 0xFFEEEEEE : 0xFF212121)));
        String last = o.optString("lastText");
        TextView excerpt = text(last.isEmpty() ? "No messages yet" : o.optString("lastUser") + ": " + last,
                15, false, (isDark() ? 0xFFBDBDBD : 0xFF616161));
        excerpt.setMaxLines(2);
        excerpt.setEllipsize(TextUtils.TruncateAt.END);
        col.addView(excerpt);
        row.addView(col);

        if (unread > 0) {
            TextView badge = text(unread >= 99 ? "99+" : String.valueOf(unread), 14, true, 0xFFFFFFFF);
            badge.setPadding(dp(10), dp(4), dp(10), dp(4));
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(0xFF1976D2);
            bg.setCornerRadius(dp(14));
            badge.setBackground(bg);
            row.addView(badge);
        }
        row.setOnClickListener(v -> openRoom(id, title, owner));

        LinearLayout holder = new LinearLayout(this);
        holder.setOrientation(LinearLayout.VERTICAL);
        holder.addView(row);
        View divider = new View(this);
        divider.setBackgroundColor(0x1F000000);
        holder.addView(divider, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1));
        return holder;
    }

    private void openRoom(String id, String title, String owner) {
        Intent i = new Intent(this, ChatRoomActivity.class);
        i.putExtra(ChatRoomActivity.EXTRA_ROOM, id);
        i.putExtra(ChatRoomActivity.EXTRA_TITLE, title);
        i.putExtra(ChatRoomActivity.EXTRA_OWNER, owner);
        startActivity(i);
    }

    private void newGroup() {
        if (!Account.signedIn(prefs)) {
            Toast.makeText(this, "Create an account to start a group", Toast.LENGTH_LONG).show();
            return;
        }
        if ("GUEST".equals(prefs.getString("role", ""))) {
            Toast.makeText(this, "Guests cannot create groups - create a registered account", Toast.LENGTH_LONG).show();
            return;
        }
        final EditText input = new EditText(this);
        input.setHint("Group name (3 to 30 letters)");
        input.setSingleLine(true);
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(30)});
        LinearLayout box = new LinearLayout(this);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        box.addView(input, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        new AlertDialog.Builder(this)
                .setTitle("New group")
                .setView(box)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Create", (d, w) -> createGroup(input.getText().toString().trim()))
                .show();
    }

    private void createGroup(final String title) {
        final String host = prefs.getString("host", "");
        final String session = prefs.getString("session", "");
        executor.execute(() -> {
            Api.Result r = Api.post(host, "/api/chat/groups/create", Api.form("title", title), session);
            if (r.code == 401) {
                Account.clear(prefs);
            }
            ui(() -> {
                if (r.code == 200) {
                    JSONObject o = r.json();
                    openRoom(o.optString("id"), o.optString("title"), o.optString("owner"));
                } else {
                    Toast.makeText(this, r.error(), Toast.LENGTH_LONG).show();
                    renderAccount();
                }
            });
        });
    }
}