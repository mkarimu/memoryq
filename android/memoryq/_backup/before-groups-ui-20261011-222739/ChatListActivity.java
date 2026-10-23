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
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
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

import java.net.URLEncoder;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Chat window: search, every public group and every live channel's room, with unread badges and online counts. */
public class ChatListActivity extends AppCompatActivity {

    private static final String PREFS = "vstream";
    private static final long REFRESH_MS = 4000;

    private SharedPreferences prefs;
    private TextView accountView;
    private TextView statusView;
    private Button joinBtn;
    private EditText searchField;
    private LinearLayout roomsView;
    private String signature = "";
    private final Map<String, String> owners = new HashMap<>();
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
        searchField = findViewById(R.id.chatSearch);
        roomsView = findViewById(R.id.chatRooms);

        findViewById(R.id.chatListHome).setOnClickListener(v -> {
            Intent i = new Intent(this, HomeActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(i);
            finish();
        });
        joinBtn.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
        findViewById(R.id.chatNewGroup).setOnClickListener(v -> newGroup());
        findViewById(R.id.chatSearchBtn).setOnClickListener(v -> doSearch());
        searchField.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                doSearch();
                return true;
            }
            return false;
        });
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

    // ---- The room list ----

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
                String id = o.optString("id");
                owners.put(id, o.optString("owner"));
                Integer u = unread.get(id);
                sig.append(id).append(':').append(o.optLong("lastId")).append(':')
                        .append(u == null ? 0 : u).append(':').append(o.optInt("online")).append(':')
                        .append(ChatClient.forcedUnread(prefs, id)).append(';');
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

    private View row(final JSONObject o, final int unread) {
        final String id = o.optString("id");
        final String title = o.optString("title");
        final long lastId = o.optLong("lastId");
        final boolean forced = ChatClient.forcedUnread(prefs, id);
        boolean channel = "channel".equals(o.optString("type"));
        boolean dark = isDark();

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(12), dp(16), dp(12));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        col.addView(text(title + (channel ? "   \u25CF LIVE" : ""), 18, true,
                dark ? 0xFFEEEEEE : 0xFF212121));
        String last = o.optString("lastText");
        TextView excerpt = text(last.isEmpty() ? "No messages yet" : o.optString("lastUser") + ": " + last,
                15, false, dark ? 0xFFBDBDBD : 0xFF616161);
        excerpt.setMaxLines(2);
        excerpt.setEllipsize(TextUtils.TruncateAt.END);
        col.addView(excerpt);
        int online = o.optInt("online");
        if (online > 0) {
            col.addView(text("\u25CF " + online + " online", 13, false, 0xFF43A047));
        }
        row.addView(col);

        if (unread > 0) {
            TextView badge = text(unread >= 99 ? "99+" : String.valueOf(unread), 14, true, 0xFFFFFFFF);
            badge.setPadding(dp(10), dp(4), dp(10), dp(4));
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(0xFF1976D2);
            bg.setCornerRadius(dp(14));
            badge.setBackground(bg);
            row.addView(badge);
        } else if (forced) {
            View dot = new View(this);
            GradientDrawable bg = new GradientDrawable();
            bg.setShape(GradientDrawable.OVAL);
            bg.setColor(0xFF1976D2);
            dot.setBackground(bg);
            row.addView(dot, new LinearLayout.LayoutParams(dp(14), dp(14)));
        }
        row.setOnClickListener(v -> openRoom(id, title, o.optString("owner")));
        row.setOnLongClickListener(v -> {
            showRowMenu(id, lastId, unread > 0 || forced);
            return true;
        });

        LinearLayout holder = new LinearLayout(this);
        holder.setOrientation(LinearLayout.VERTICAL);
        holder.addView(row);
        View divider = new View(this);
        divider.setBackgroundColor(0x1F000000);
        holder.addView(divider, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1));
        return holder;
    }

    private void showRowMenu(final String id, final long lastId, final boolean isUnread) {
        final String[] items = {isUnread ? "Mark as read" : "Mark as unread"};
        new AlertDialog.Builder(this)
                .setItems(items, (d, which) -> {
                    if (isUnread) {
                        ChatClient.setForcedUnread(prefs, id, false);
                        ChatClient.markSeen(prefs, id, lastId);
                    } else {
                        ChatClient.setForcedUnread(prefs, id, true);
                    }
                    signature = "";
                    refreshRooms();
                })
                .show();
    }

    private void openRoom(String id, String title, String owner) {
        ChatClient.setForcedUnread(prefs, id, false);
        Intent i = new Intent(this, ChatRoomActivity.class);
        i.putExtra(ChatRoomActivity.EXTRA_ROOM, id);
        i.putExtra(ChatRoomActivity.EXTRA_TITLE, title);
        i.putExtra(ChatRoomActivity.EXTRA_OWNER, owner == null ? "" : owner);
        startActivity(i);
    }

    // ---- Search ----

    private void doSearch() {
        final String q = searchField.getText().toString().trim();
        if (q.length() < 2) {
            Toast.makeText(this, "Type at least 2 letters to search", Toast.LENGTH_SHORT).show();
            return;
        }
        final String host = prefs.getString("host", "");
        if (host.isEmpty()) {
            Toast.makeText(this, "Set the server in Settings first", Toast.LENGTH_SHORT).show();
            return;
        }
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(searchField.getWindowToken(), 0);
        }
        executor.execute(() -> {
            String encoded;
            try {
                encoded = URLEncoder.encode(q, "UTF-8");
            } catch (java.io.UnsupportedEncodingException e) {
                encoded = q;
            }
            final Api.Result r = Api.get(host, "/api/chat/search?q=" + encoded, null);
            ui(() -> showResults(q, r));
        });
    }

    private void showResults(String q, Api.Result r) {
        if (r.code != 200) {
            Toast.makeText(this, r.code == 0 ? "Cannot reach the server" : r.error(), Toast.LENGTH_LONG).show();
            return;
        }
        JSONArray results = r.json().optJSONArray("results");
        if (results == null || results.length() == 0) {
            Toast.makeText(this, "No messages found for \"" + q + "\"", Toast.LENGTH_LONG).show();
            return;
        }
        boolean dark = isDark();
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(8), dp(16), dp(8));
        final AlertDialog[] dialog = new AlertDialog[1];
        for (int i = 0; i < results.length(); i++) {
            final JSONObject o = results.optJSONObject(i);
            if (o == null) {
                continue;
            }
            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setPadding(0, dp(8), 0, dp(8));
            item.addView(text(o.optString("roomTitle") + "  \u00b7  " + o.optString("user") + "  \u00b7  "
                    + android.text.format.DateFormat.format("d MMM HH:mm", new Date(o.optLong("time"))),
                    13, true, dark ? 0xFF90CAF9 : 0xFF1565C0));
            TextView body = text(o.optString("text"), 15, false, dark ? 0xFFEEEEEE : 0xFF212121);
            body.setMaxLines(4);
            body.setEllipsize(TextUtils.TruncateAt.END);
            item.addView(body);
            item.setOnClickListener(v -> {
                dialog[0].dismiss();
                String room = o.optString("room");
                openRoom(room, o.optString("roomTitle"), owners.get(room));
            });
            box.addView(item);
        }
        ScrollView scrollView = new ScrollView(this);
        scrollView.addView(box);
        dialog[0] = new AlertDialog.Builder(this)
                .setTitle(results.length() + " result" + (results.length() == 1 ? "" : "s") + " for \"" + q + "\"")
                .setView(scrollView)
                .setNegativeButton("Close", null)
                .create();
        dialog[0].show();
    }

    // ---- New group ----

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