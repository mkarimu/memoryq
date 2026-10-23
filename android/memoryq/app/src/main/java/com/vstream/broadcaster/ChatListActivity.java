package com.vstream.broadcaster;

import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
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

/** Chat window: invitations, search, public rooms and your private groups, with unread badges. */
public class ChatListActivity extends AppCompatActivity {

    private static final String PREFS = "vstream";
    private static final long REFRESH_MS = 4000;
    private static final String LOCK = "\uD83D\uDD12 ";

    private SharedPreferences prefs;
    private TextView accountView;
    private TextView statusView;
    private Button joinBtn;
    private EditText searchField;
    private LinearLayout roomsView;
    private LinearLayout invitesView;
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
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        setContentView(buildScreen());
        handleDeepLink(getIntent());
    }

    // ---- The screen: header (Home, then "Chats" with the search box and a menu), then the cards ----

    private int pageColor() {
        return isDark() ? 0xFF0F1419 : 0xFFE6E9ED;
    }

    private int cardColor() {
        return isDark() ? 0xFF2A3442 : 0xFF4E5B6E;
    }

    private View buildScreen() {
        boolean dark = isDark();
        int ink = dark ? 0xFFE6EBF0 : 0xFF1B1F23;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(pageColor());
        root.setFitsSystemWindows(true);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setBackgroundColor(dark ? 0xFF182029 : 0xFFFFFFFF);
        header.setPadding(dp(12), dp(6), dp(8), dp(10));
        header.setElevation(dp(2));

        TextView home = text("\u2302  Home", 15, false, dark ? 0xFF9FB0C0 : 0xFF5F6E7D);
        home.setPadding(dp(4), dp(8), dp(12), dp(4));
        home.setOnClickListener(v -> {
            Intent i = new Intent(this, HomeActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(i);
            finish();
        });
        LinearLayout homeRow = new LinearLayout(this);
        homeRow.addView(home);
        header.addView(homeRow);

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text("Chats", 24, true, ink);
        title.setPadding(dp(4), 0, dp(12), 0);
        titleRow.addView(title);

        searchField = new EditText(this);
        searchField.setHint("\uD83D\uDD0D  Search messages");
        searchField.setSingleLine(true);
        searchField.setTextSize(15);
        searchField.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        searchField.setInputType(InputType.TYPE_CLASS_TEXT);
        searchField.setPadding(dp(14), dp(8), dp(14), dp(8));
        searchField.setTextColor(ink);
        searchField.setHintTextColor(dark ? 0xFF7D8B99 : 0xFF8A96A3);
        GradientDrawable sbg = new GradientDrawable();
        sbg.setColor(dark ? 0xFF223040 : 0xFFF0F2F5);
        sbg.setCornerRadius(dp(20));
        searchField.setBackground(sbg);
        searchField.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                doSearch();
                return true;
            }
            return false;
        });
        titleRow.addView(searchField, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView menu = text("\u22EE", 22, true, ink);
        menu.setGravity(Gravity.CENTER);
        menu.setContentDescription("More: new group");
        menu.setOnClickListener(this::showMainMenu);
        titleRow.addView(menu, new LinearLayout.LayoutParams(dp(44), dp(44)));
        header.addView(titleRow);
        root.addView(header);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(8), dp(8), dp(8), dp(16));

        accountView = text("", 13, false, dark ? 0xFF9FB0C0 : 0xFF5F6E7D);
        accountView.setPadding(dp(6), dp(2), dp(6), dp(4));
        body.addView(accountView);
        joinBtn = new Button(this);
        joinBtn.setText("Sign in or join");
        joinBtn.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
        body.addView(joinBtn, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        statusView = text("", 13, false, dark ? 0xFF9FB0C0 : 0xFF5F6E7D);
        statusView.setPadding(dp(6), dp(2), dp(6), dp(2));
        body.addView(statusView);
        invitesView = new LinearLayout(this);
        invitesView.setOrientation(LinearLayout.VERTICAL);
        body.addView(invitesView);
        roomsView = new LinearLayout(this);
        roomsView.setOrientation(LinearLayout.VERTICAL);
        body.addView(roomsView);
        scroll.addView(body);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return root;
    }

    /** The menu under Home, on the right: new group. */
    private void showMainMenu(View anchor) {
        android.widget.PopupMenu m = new android.widget.PopupMenu(this, anchor);
        m.getMenu().add(0, 1, 0, "New group");
        m.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == 1) {
                newGroup();
            }
            return true;
        });
        m.show();
    }

    /** The menu beside each chat's icon: mark read/unread, join a group with a code. */
    private void showRowMenu(View anchor, final String id, final long lastId, final boolean isUnread) {
        android.widget.PopupMenu m = new android.widget.PopupMenu(this, anchor);
        m.getMenu().add(0, 1, 0, isUnread ? "Mark as read" : "Mark as unread");
        m.getMenu().add(0, 2, 1, "Join a group with a code");
        m.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == 1) {
                if (isUnread) {
                    ChatClient.setForcedUnread(prefs, id, false);
                    ChatClient.markSeen(prefs, id, lastId);
                } else {
                    ChatClient.setForcedUnread(prefs, id, true);
                }
                signature = "";
                refreshRooms();
            } else if (item.getItemId() == 2) {
                askForCode();
            }
            return true;
        });
        m.show();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleDeepLink(intent);
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

    // ---- The room list and invitations ----

    private void refreshRooms() {
        final String host = prefs.getString("host", "");
        final String session = prefs.getString("session", "");
        if (host.isEmpty()) {
            statusView.setText("Set the server in Settings first");
            return;
        }
        executor.execute(() -> {
            final JSONArray rooms = ChatClient.rooms(host, session);
            final Map<String, Integer> unread = rooms == null ? null
                    : ChatClient.unread(host, session, prefs, rooms);
            JSONArray inv = null;
            if (Account.signedIn(prefs)) {
                Api.Result r = Api.get(host, "/api/chat/invitations", session);
                if (r.code == 200) {
                    inv = r.json().optJSONArray("invitations");
                } else if (r.code == 401) {
                    Account.clear(prefs);
                }
            }
            final JSONArray invitations = inv;
            ui(() -> showRooms(rooms, unread, invitations));
        });
    }

    private void showRooms(JSONArray rooms, Map<String, Integer> unread, JSONArray invitations) {
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
                        .append(o.optString("title")).append(':').append(o.optInt("members")).append(':')
                        .append(ChatClient.forcedUnread(prefs, id)).append(';');
            }
        }
        sig.append('|');
        if (invitations != null) {
            for (int i = 0; i < invitations.length(); i++) {
                JSONObject o = invitations.optJSONObject(i);
                if (o != null) {
                    sig.append(o.optString("room")).append(o.optString("from")).append(';');
                }
            }
        }
        if (sig.toString().equals(signature)) {
            return;
        }
        signature = sig.toString();

        invitesView.removeAllViews();
        if (invitations != null) {
            for (int i = 0; i < invitations.length(); i++) {
                JSONObject o = invitations.optJSONObject(i);
                if (o != null) {
                    invitesView.addView(invitationRow(o));
                }
            }
        }
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
        if (color != 0) {
            t.setTextColor(color);
        }
        if (bold) {
            t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        }
        return t;
    }

    private View invitationRow(final JSONObject o) {
        final String room = o.optString("room");
        final String title = o.optString("title");
        final String from = o.optString("from");
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(12), dp(16), dp(12));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(isDark() ? 0xFF1B3A5C : 0xFFE3F2FD);
        bg.setCornerRadius(dp(6));
        box.setBackground(bg);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.setMargins(0, 0, 0, dp(8));
        box.setLayoutParams(blp);
        box.addView(text("Invitation: " + LOCK + title, 17, true, 0));
        box.addView(text(from + " invited you to join this private group", 14, false, 0));
        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        Button accept = new Button(this);
        accept.setText("Accept");
        accept.setOnClickListener(v -> answerInvitation(room, title, from, true));
        Button decline = new Button(this);
        decline.setText("Decline");
        decline.setOnClickListener(v -> answerInvitation(room, title, from, false));
        buttons.addView(accept);
        buttons.addView(decline);
        box.addView(buttons);
        return box;
    }

    private void answerInvitation(final String room, final String title, final String from, final boolean accept) {
        final String host = prefs.getString("host", "");
        final String session = prefs.getString("session", "");
        executor.execute(() -> {
            Api.Result r = Api.post(host, accept ? "/api/chat/invitations/accept" : "/api/chat/invitations/decline",
                    Api.form("room", room), session);
            if (r.code == 401) {
                Account.clear(prefs);
            }
            ui(() -> {
                signature = "";
                if (r.code == 200) {
                    if (accept) {
                        openRoom(room, title, from);
                    } else {
                        refreshRooms();
                    }
                } else {
                    Toast.makeText(this, r.error(), Toast.LENGTH_LONG).show();
                    refreshRooms();
                }
            });
        });
    }

    /** One chat as a card: name and (time, last message) on the left, the icon with its unread count and a menu on the right. */
    private View row(final JSONObject o, final int unread) {
        final String id = o.optString("id");
        final String title = o.optString("title");
        final long lastId = o.optLong("lastId");
        final boolean forced = ChatClient.forcedUnread(prefs, id);
        final boolean channel = "channel".equals(o.optString("type"));
        final String peer = peerOf(o);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(16), dp(14), dp(4), dp(14));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(cardColor());
        bg.setCornerRadius(dp(6));
        card.setBackground(bg);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView name = text(peer != null ? peer : title, 18, true, 0xFFFFFFFF);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        if (channel && o.optBoolean("live")) {
            name.setText(name.getText() + "   \u25CF LIVE");
        }
        col.addView(name);
        LinearLayout line = new LinearLayout(this);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setPadding(0, dp(4), 0, 0);
        long when = o.optLong("lastTime");
        if (when > 0) {
            TextView time = text(timeLabel(when), 13, true, 0xFFB4C3D3);
            time.setPadding(0, 0, dp(8), 0);
            line.addView(time);
        }
        String last = o.optString("lastText");
        String who = o.optString("lastUser");
        TextView excerpt = text(last.isEmpty() ? "No messages yet"
                : (who.isEmpty() || who.equalsIgnoreCase(peer == null ? "" : peer) ? "" : who + ": ") + last,
                14, false, 0xFFDCE3EA);
        excerpt.setSingleLine(true);
        excerpt.setEllipsize(TextUtils.TruncateAt.END);
        line.addView(excerpt);
        col.addView(line);
        card.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        card.addView(icon(o, peer, title, channel, unread, forced));

        TextView dots = text("\u22EE", 22, true, 0xFFDCE3EA);
        dots.setGravity(Gravity.CENTER);
        dots.setContentDescription("More for " + title);
        dots.setOnClickListener(v -> showRowMenu(v, id, lastId, unread > 0 || forced));
        card.addView(dots, new LinearLayout.LayoutParams(dp(36), dp(44)));

        card.setOnClickListener(v -> openRoom(id, title, o.optString("owner")));
        card.setOnLongClickListener(v -> {
            showRowMenu(dots, id, lastId, unread > 0 || forced);
            return true;
        });

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, dp(8));
        card.setLayoutParams(lp);
        return card;
    }

    /** The person in a two-person private chat ("ann & bob"), or null for groups and channels. */
    private String peerOf(JSONObject o) {
        if (!o.optBoolean("private")) {
            return null;
        }
        String me = prefs.getString("username", "");
        String[] parts = o.optString("title").split(" & ");
        if (parts.length == 2 && !me.isEmpty()) {
            if (parts[0].equalsIgnoreCase(me)) {
                return parts[1];
            }
            if (parts[1].equalsIgnoreCase(me)) {
                return parts[0];
            }
        }
        return null;
    }

    /** "14:05" today, "Mon" this week, "3 Oct" before that. */
    private static String timeLabel(long when) {
        long now = System.currentTimeMillis();
        if (android.text.format.DateUtils.isToday(when)) {
            return android.text.format.DateFormat.format("HH:mm", new Date(when)).toString();
        }
        if (now - when < 6L * 24 * 3600 * 1000) {
            return android.text.format.DateFormat.format("EEE", new Date(when)).toString();
        }
        return android.text.format.DateFormat.format("d MMM", new Date(when)).toString();
    }

    /** The round icon (a person's photo, or the group's letters) with the number of new messages on top. */
    private View icon(JSONObject o, String peer, String title, boolean channel, int unread, boolean forced) {
        android.widget.FrameLayout box = new android.widget.FrameLayout(this);
        int size = dp(46);
        if (peer != null) {
            android.widget.ImageView img = new android.widget.ImageView(this);
            img.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
            GradientDrawable circle = new GradientDrawable();
            circle.setShape(GradientDrawable.OVAL);
            circle.setColor(0xFF6B7A8C);
            img.setBackground(circle);
            img.setClipToOutline(true);
            ImageLoader.load(img, prefs.getString("host", ""), peer, true);
            box.addView(img, new android.widget.FrameLayout.LayoutParams(size, size, Gravity.CENTER));
        } else {
            TextView t = text(channel ? "\uD83D\uDCE1" : initials(title), 16, true, 0xFFFFFFFF);
            t.setGravity(Gravity.CENTER);
            GradientDrawable circle = new GradientDrawable();
            circle.setShape(GradientDrawable.OVAL);
            int[] colors = {0xFF2F6FED, 0xFF7A5AF8, 0xFF0E9F6E, 0xFFD97706, 0xFFDB2777, 0xFF0891B2};
            circle.setColor(colors[Math.abs(title.hashCode()) % colors.length]);
            t.setBackground(circle);
            box.addView(t, new android.widget.FrameLayout.LayoutParams(size, size, Gravity.CENTER));
        }
        if (unread > 0 || forced) {
            TextView badge = text(unread > 0 ? (unread >= 99 ? "99+" : String.valueOf(unread)) : "", 11, true, 0xFFFFFFFF);
            badge.setGravity(Gravity.CENTER);
            badge.setMinWidth(dp(20));
            badge.setPadding(dp(5), 0, dp(5), 0);
            GradientDrawable b = new GradientDrawable();
            b.setColor(0xFFE5484D);
            b.setCornerRadius(dp(10));
            b.setStroke(dp(2), cardColor());
            badge.setBackground(b);
            android.widget.FrameLayout.LayoutParams lp = new android.widget.FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(unread > 0 ? 20 : 14), Gravity.TOP | Gravity.END);
            box.addView(badge, lp);
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(54), dp(54));
        lp.setMargins(dp(8), 0, 0, 0);
        box.setLayoutParams(lp);
        return box;
    }

    private static String initials(String title) {
        StringBuilder sb = new StringBuilder();
        for (String w : title.trim().split("\\s+")) {
            if (!w.isEmpty() && Character.isLetterOrDigit(w.charAt(0))) {
                sb.append(Character.toUpperCase(w.charAt(0)));
            }
            if (sb.length() == 2) {
                break;
            }
        }
        return sb.length() == 0 ? "#" : sb.toString();
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
        final String session = prefs.getString("session", "");
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
            final Api.Result r = Api.get(host, "/api/chat/search?q=" + encoded, session);
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
        final RadioGroup kind = new RadioGroup(this);
        final RadioButton privateBtn = new RadioButton(this);
        privateBtn.setId(View.generateViewId());
        privateBtn.setText("Private - only people you invite can read it");
        final RadioButton publicBtn = new RadioButton(this);
        publicBtn.setId(View.generateViewId());
        publicBtn.setText("Public - anyone can read it");
        kind.addView(privateBtn);
        kind.addView(publicBtn);
        kind.check(privateBtn.getId());
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        box.addView(input, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        box.addView(kind);
        new AlertDialog.Builder(this)
                .setTitle("New group")
                .setView(box)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Create", (d, w) -> createGroup(input.getText().toString().trim(),
                        kind.getCheckedRadioButtonId() == privateBtn.getId()))
                .show();
    }

    private void createGroup(final String title, final boolean priv) {
        final String host = prefs.getString("host", "");
        final String session = prefs.getString("session", "");
        executor.execute(() -> {
            Api.Result r = Api.post(host, "/api/chat/groups/create",
                    Api.form("title", title, "private", String.valueOf(priv)), session);
            if (r.code == 401) {
                Account.clear(prefs);
            }
            ui(() -> {
                signature = "";
                if (r.code == 200) {
                    JSONObject o = r.json();
                    if (priv) {
                        Toast.makeText(this, "Group created. Tap Info in the room to invite people.", Toast.LENGTH_LONG).show();
                    }
                    openRoom(o.optString("id"), o.optString("title"), o.optString("owner"));
                } else {
                    Toast.makeText(this, r.error(), Toast.LENGTH_LONG).show();
                    renderAccount();
                }
            });
        });
    }

    // ---- Joining with a code or a link ----

    private void handleDeepLink(Intent intent) {
        if (intent == null) {
            return;
        }
        Uri d = intent.getData();
        if (d != null && "vstream".equals(d.getScheme()) && "join".equals(d.getHost())) {
            String code = d.getQueryParameter("code");
            intent.setData(null);
            if (code != null && !code.trim().isEmpty()) {
                startJoin(code.trim());
            }
        }
    }

    private void askForCode() {
        if (!Account.signedIn(prefs)) {
            Toast.makeText(this, "Sign in or join as a guest first", Toast.LENGTH_LONG).show();
            startActivity(new Intent(this, SettingsActivity.class));
            return;
        }
        final EditText input = new EditText(this);
        input.setHint("Invite code (XXXXX-XXXXX)");
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(14)});
        LinearLayout box = new LinearLayout(this);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        box.addView(input, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        new AlertDialog.Builder(this)
                .setTitle("Join with a code")
                .setView(box)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Next", (d, w) -> startJoin(input.getText().toString().trim()))
                .show();
    }

    private void startJoin(final String code) {
        if (code.isEmpty()) {
            return;
        }
        if (!Account.signedIn(prefs)) {
            new AlertDialog.Builder(this)
                    .setTitle("Sign in first")
                    .setMessage("To join a group you need an account or a guest account. Sign in, then open the invite again.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Sign in / join", (d, w) -> startActivity(new Intent(this, SettingsActivity.class)))
                    .show();
            return;
        }
        final String host = prefs.getString("host", "");
        executor.execute(() -> {
            String encoded;
            try {
                encoded = URLEncoder.encode(code, "UTF-8");
            } catch (java.io.UnsupportedEncodingException e) {
                encoded = code;
            }
            final Api.Result p = Api.get(host, "/api/chat/invite?code=" + encoded, null);
            ui(() -> showJoinPreview(code, p));
        });
    }

    private void showJoinPreview(final String code, Api.Result p) {
        if (p.code != 200) {
            Toast.makeText(this, p.code == 0 ? "Cannot reach the server" : p.error(), Toast.LENGTH_LONG).show();
            return;
        }
        JSONObject o = p.json();
        final String title = o.optString("title");
        final String owner = o.optString("owner");
        int members = o.optInt("members");
        new AlertDialog.Builder(this)
                .setTitle("Join " + title + "?")
                .setMessage("Private group created by " + owner + " with " + members
                        + (members == 1 ? " member." : " members.") + "\n\nOnly members can read it.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Join", (d, w) -> joinNow(code, title, owner))
                .show();
    }

    private void joinNow(final String code, final String title, final String owner) {
        final String host = prefs.getString("host", "");
        final String session = prefs.getString("session", "");
        executor.execute(() -> {
            Api.Result r = Api.post(host, "/api/chat/groups/join", Api.form("code", code), session);
            if (r.code == 401) {
                Account.clear(prefs);
            }
            ui(() -> {
                signature = "";
                if (r.code == 200) {
                    JSONObject o = r.json();
                    openRoom(o.optString("room"), o.optString("title", title), owner);
                } else {
                    Toast.makeText(this, r.error(), Toast.LENGTH_LONG).show();
                    renderAccount();
                }
            });
        });
    }
}