package com.vstream.broadcaster;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * One chat room as a view: bubbles with ticks, reactions, replies, edits, forwards and pins, the online
 * and typing lines, and the message box. Used by the Chat window and, beside the player, by Watch Channels.
 */
public class ChatPanel extends LinearLayout {

    static final String[] EMOJI = {
            "\uD83D\uDC4D", "\u2764\uFE0F", "\uD83D\uDE02", "\uD83D\uDE2E", "\uD83D\uDE22", "\uD83D\uDE4F"};

    private static final String PREFS = "vstream";
    private static final long POLL_MS = 2000;
    private static final long TYPING_EVERY_MS = 3000;
    private static final long EDIT_WINDOW_MS = 20L * 60 * 1000;
    private static final int GREEN = 0xFF2E7D32;
    private static final int TICK_READ = 0xFF1E88E5;
    private static final int TICK_SENT = 0xFF757575;
    private static final String PIN = "\uD83D\uDCCC";

    private final SharedPreferences prefs;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final SimpleDateFormat clock = new SimpleDateFormat("HH:mm", Locale.getDefault());

    private final TextView statusView;
    private final TextView onlineView;
    private final TextView pinnedView;
    private final TextView typingView;
    private final TextView errorView;
    private final TextView barText;
    private final ScrollView scroll;
    private final LinearLayout list;
    private final View bar;
    private final View inputRow;
    private final View viewerBar;
    private final EditText input;
    private final Button sendBtn;

    private String roomId = null;
    private String roomOwner = "";
    private long replyToId = 0;
    private long editingId = 0;
    private String signature = "";
    private boolean running = false;
    private boolean sending = false;
    private long lastTypingSent = 0;
    private long knownLastId = 0;
    private long lastReadSent = 0;
    private JSONArray pinsData = null;
    private final Set<String> onlineLower = new HashSet<>();

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            if (!running || roomId == null) {
                return;
            }
            fetch();
            handler.postDelayed(this, POLL_MS);
        }
    };

    public ChatPanel(Context context) {
        this(context, null);
    }

    public ChatPanel(Context context, AttributeSet attrs) {
        super(context, attrs);
        setOrientation(VERTICAL);
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        LayoutInflater.from(context).inflate(R.layout.view_chat_panel, this, true);

        statusView = findViewById(R.id.panelStatus);
        onlineView = findViewById(R.id.panelOnline);
        pinnedView = findViewById(R.id.panelPinned);
        typingView = findViewById(R.id.panelTyping);
        errorView = findViewById(R.id.panelError);
        barText = findViewById(R.id.panelBarText);
        scroll = findViewById(R.id.panelScroll);
        list = findViewById(R.id.panelMessages);
        bar = findViewById(R.id.panelBar);
        inputRow = findViewById(R.id.panelInputRow);
        viewerBar = findViewById(R.id.panelViewerBar);
        input = findViewById(R.id.panelInput);
        sendBtn = findViewById(R.id.panelSend);

        sendBtn.setOnClickListener(v -> send());
        pinnedView.setOnClickListener(v -> showPinsDialog());
        findViewById(R.id.panelBarClose).setOnClickListener(v -> clearBar(true));
        findViewById(R.id.panelJoin).setOnClickListener(
                v -> getContext().startActivity(new Intent(getContext(), SettingsActivity.class)));
        scroll.setOnScrollChangeListener((v, x, y, oldX, oldY) -> {
            if (isAtBottom()) {
                sendRead();
            }
        });
        input.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                maybeTyping();
            }
        });
    }

    // ---- Lifecycle ----

    /** Shows another room. Call start() to begin loading it. */
    public void bind(String room, String owner) {
        roomId = room;
        roomOwner = owner == null ? "" : owner;
        signature = "";
        knownLastId = 0;
        lastReadSent = 0;
        pinsData = null;
        onlineLower.clear();
        list.removeAllViews();
        typingView.setVisibility(GONE);
        onlineView.setVisibility(GONE);
        pinnedView.setVisibility(GONE);
        errorView.setText("");
        statusView.setText(room == null ? "" : "Loading...");
        clearBar(true);
        applyAccountState();
        if (running && room != null) {
            handler.removeCallbacks(poll);
            handler.post(poll);
        }
    }

    public void start() {
        running = true;
        applyAccountState();
        handler.removeCallbacks(poll);
        if (roomId != null) {
            handler.post(poll);
        }
    }

    public void stop() {
        running = false;
        handler.removeCallbacks(poll);
    }

    public void release() {
        stop();
        executor.shutdownNow();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void run(Runnable r) {
        try {
            executor.execute(r);
        } catch (RejectedExecutionException e) {
            // the panel was released
        }
    }

    private String ownName() {
        return prefs.getString("username", "");
    }

    private void applyAccountState() {
        boolean signed = Account.signedIn(prefs);
        inputRow.setVisibility(signed ? VISIBLE : GONE);
        viewerBar.setVisibility(signed ? GONE : VISIBLE);
        if (!signed) {
            clearBar(true);
        }
    }

    private boolean isAtBottom() {
        View content = scroll.getChildAt(0);
        return content == null || scroll.getScrollY() + scroll.getHeight() >= content.getHeight() - dp(60);
    }

    // ---- Loading ----

    private void fetch() {
        final String room = roomId;
        if (room == null) {
            return;
        }
        final String host = prefs.getString("host", "");
        if (host.isEmpty()) {
            statusView.setText("Set the server in Settings first");
            return;
        }
        final String session = prefs.getString("session", "");
        run(() -> {
            final Api.Result r = Api.get(host, "/api/chat/messages?room=" + room + "&limit=50", session);
            post(() -> {
                if (room.equals(roomId)) {
                    showMessages(r);
                }
            });
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
        showTyping(data.optJSONArray("typing"));
        showOnline(data);
        showPins(data.optJSONArray("pins"));
        JSONArray arr = data.optJSONArray("messages");
        if (arr == null) {
            return;
        }
        knownLastId = data.optLong("lastId", 0);
        StringBuilder sig = new StringBuilder();
        sig.append(String.valueOf(data.optJSONArray("online"))).append('|');
        sig.append(String.valueOf(data.optJSONArray("pins")).hashCode()).append('|');
        for (int i = 0; i < arr.length(); i++) {
            JSONObject m = arr.optJSONObject(i);
            if (m != null) {
                sig.append(m.toString().hashCode()).append(',');
            }
        }
        ChatClient.markSeen(prefs, roomId, knownLastId);
        if (!sig.toString().equals(signature)) {
            signature = sig.toString();
            boolean atBottom = isAtBottom();
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
        scroll.post(() -> {
            if (isAtBottom()) {
                sendRead();
            }
        });
    }

    private void showTyping(JSONArray typing) {
        int n = typing == null ? 0 : typing.length();
        if (n == 0) {
            typingView.setVisibility(GONE);
            return;
        }
        String line;
        if (n == 1) {
            line = typing.optString(0) + " is typing\u2026";
        } else if (n == 2) {
            line = typing.optString(0) + " and " + typing.optString(1) + " are typing\u2026";
        } else {
            line = n + " people are typing\u2026";
        }
        typingView.setText(line);
        typingView.setVisibility(VISIBLE);
    }

    private void showOnline(JSONObject data) {
        int count = data.optInt("onlineCount", 0);
        JSONArray names = data.optJSONArray("online");
        onlineLower.clear();
        StringBuilder who = new StringBuilder();
        if (names != null) {
            for (int i = 0; i < names.length(); i++) {
                String n = names.optString(i);
                onlineLower.add(n.toLowerCase(Locale.ROOT));
                if (who.length() > 0) {
                    who.append(", ");
                }
                who.append(n);
            }
        }
        if (count <= 0) {
            onlineView.setVisibility(GONE);
            return;
        }
        onlineView.setText("\u25CF " + count + " online" + (who.length() > 0 ? ": " + who : ""));
        onlineView.setVisibility(VISIBLE);
    }

    private void showPins(JSONArray pins) {
        pinsData = pins;
        if (pins == null || pins.length() == 0) {
            pinnedView.setVisibility(GONE);
            return;
        }
        JSONObject last = pins.optJSONObject(pins.length() - 1);
        if (last == null) {
            pinnedView.setVisibility(GONE);
            return;
        }
        pinnedView.setText(PIN + " " + last.optString("user") + ": " + last.optString("text")
                + (pins.length() > 1 ? "   (+" + (pins.length() - 1) + " more)" : ""));
        pinnedView.setVisibility(VISIBLE);
    }

    private void showPinsDialog() {
        if (pinsData == null || pinsData.length() == 0) {
            return;
        }
        List<String> items = new ArrayList<>();
        for (int i = pinsData.length() - 1; i >= 0; i--) {
            JSONObject p = pinsData.optJSONObject(i);
            if (p != null) {
                items.add(p.optString("user") + ": " + p.optString("text"));
            }
        }
        new AlertDialog.Builder(getContext())
                .setTitle(PIN + " Pinned messages")
                .setItems(items.toArray(new String[0]), null)
                .setPositiveButton("OK", null)
                .show();
    }

    private TextView text(String value, int sp, boolean bold, int color) {
        TextView t = new TextView(getContext());
        t.setText(value);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) {
            t.setTypeface(t.getTypeface(), Typeface.BOLD);
        }
        return t;
    }

    private static CharSequence colored(String s, int color) {
        SpannableString sp = new SpannableString(s);
        sp.setSpan(new ForegroundColorSpan(color), 0, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return sp;
    }

    private View bubble(final JSONObject m) {
        final long id = m.optLong("id");
        String user = m.optString("user");
        String role = m.optString("role");
        boolean own = user.equalsIgnoreCase(ownName());
        boolean deleted = m.optBoolean("deleted");
        int maxWidth = (int) (getResources().getDisplayMetrics().widthPixels * 0.8f);

        LinearLayout b = new LinearLayout(getContext());
        b.setOrientation(VERTICAL);
        b.setPadding(dp(10), dp(6), dp(10), dp(6));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(10));
        bg.setColor(own ? 0xFFDCF8C6 : 0xFFECEFF1);
        b.setBackground(bg);

        if (!own) {
            String tag = "GUEST".equals(role) ? " (guest)" : ("ADMIN".equals(role) ? " \u00b7 admin" : "");
            SpannableStringBuilder nameLine = new SpannableStringBuilder();
            if (onlineLower.contains(user.toLowerCase(Locale.ROOT))) {
                nameLine.append(colored("\u25CF ", GREEN));
            }
            nameLine.append(user + tag);
            TextView name = text("", 14, true, 0xFF00695C);
            name.setText(nameLine);
            name.setMaxWidth(maxWidth);
            b.addView(name);
        }
        String forwarded = m.optString("forwardedFrom");
        if (!deleted && !forwarded.isEmpty()) {
            TextView f = text("\u21AA Forwarded from " + forwarded, 12, false, 0xFF616161);
            f.setTypeface(f.getTypeface(), Typeface.ITALIC);
            b.addView(f);
        }
        JSONObject reply = m.optJSONObject("reply");
        if (reply != null) {
            String quoted = reply.optBoolean("deleted") ? "message deleted" : reply.optString("text");
            TextView q = text(reply.optString("user") + ": " + quoted, 13, false, 0xFF424242);
            q.setBackgroundColor(0x1A000000);
            q.setPadding(dp(8), dp(4), dp(8), dp(4));
            q.setMaxWidth(maxWidth);
            q.setMaxLines(2);
            q.setEllipsize(TextUtils.TruncateAt.END);
            b.addView(q);
        }
        TextView body = text(deleted ? "Message deleted" : m.optString("text"), 16, false,
                deleted ? 0xFF757575 : 0xFF212121);
        if (deleted) {
            body.setTypeface(body.getTypeface(), Typeface.ITALIC);
        }
        body.setMaxWidth(maxWidth);
        b.addView(body);

        JSONArray rx = m.optJSONArray("reactions");
        if (!deleted && rx != null && rx.length() > 0) {
            LinearLayout chips = new LinearLayout(getContext());
            chips.setOrientation(HORIZONTAL);
            for (int i = 0; i < rx.length(); i++) {
                JSONObject o = rx.optJSONObject(i);
                if (o == null) {
                    continue;
                }
                final String emoji = o.optString("emoji");
                TextView chip = text(emoji + " " + o.optInt("count"), 14, false, 0xFF212121);
                chip.setPadding(dp(8), dp(2), dp(8), dp(2));
                GradientDrawable cbg = new GradientDrawable();
                cbg.setCornerRadius(dp(12));
                cbg.setColor(o.optBoolean("mine") ? 0x331976D2 : 0x14000000);
                chip.setBackground(cbg);
                chip.setOnClickListener(v -> react(id, emoji));
                LayoutParams clp = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
                clp.setMargins(0, dp(4), dp(6), 0);
                chips.addView(chip, clp);
            }
            b.addView(chips);
        }

        SpannableStringBuilder stamp = new SpannableStringBuilder();
        if (!deleted && m.optBoolean("pinned")) {
            stamp.append(PIN + " ");
        }
        stamp.append(clock.format(new Date(m.optLong("time"))));
        if (m.optBoolean("edited") && !deleted) {
            stamp.append(" \u00b7 edited");
        }
        if (own && !deleted) {
            int delivered = m.optInt("delivered");
            int read = m.optInt("read");
            stamp.append("  ");
            stamp.append(colored(delivered > 0 || read > 0 ? "\u2713\u2713" : "\u2713",
                    read > 0 ? TICK_READ : TICK_SENT));
        }
        TextView time = text("", 12, false, 0xFF757575);
        time.setText(stamp);
        time.setGravity(Gravity.END);
        b.addView(time, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LayoutParams lp = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
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
        final boolean pinned = m.optBoolean("pinned");
        boolean signed = Account.signedIn(prefs);
        boolean mine = user.equalsIgnoreCase(ownName());
        boolean admin = "ADMIN".equals(prefs.getString("role", ""));
        boolean owner = !roomOwner.isEmpty() && roomOwner.equalsIgnoreCase(ownName());
        boolean canEdit = signed && mine && System.currentTimeMillis() - m.optLong("time") < EDIT_WINDOW_MS;

        final List<String> items = new ArrayList<>();
        if (signed) {
            items.add("React");
            items.add("Reply");
            items.add("Forward");
        }
        items.add("Copy");
        if (canEdit) {
            items.add("Edit");
        }
        if (signed) {
            items.add(pinned ? "Unpin" : "Pin");
        }
        if (signed && (mine || admin)) {
            items.add("Info");
        }
        if (signed && (mine || admin || owner)) {
            items.add("Delete");
        }
        new AlertDialog.Builder(getContext())
                .setItems(items.toArray(new String[0]), (d, which) -> {
                    String choice = items.get(which);
                    if ("React".equals(choice)) {
                        showEmojiPicker(id);
                    } else if ("Reply".equals(choice)) {
                        startReply(id, user, body);
                    } else if ("Forward".equals(choice)) {
                        startForward(id);
                    } else if ("Copy".equals(choice)) {
                        ClipboardManager cm = (ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
                        if (cm != null) {
                            cm.setPrimaryClip(ClipData.newPlainText("message", body));
                            Toast.makeText(getContext(), "Copied", Toast.LENGTH_SHORT).show();
                        }
                    } else if ("Edit".equals(choice)) {
                        startEdit(id, body);
                    } else if ("Pin".equals(choice) || "Unpin".equals(choice)) {
                        setPinned(id, "Pin".equals(choice));
                    } else if ("Info".equals(choice)) {
                        showInfo(id);
                    } else {
                        deleteMessage(id);
                    }
                })
                .show();
    }

    private void showEmojiPicker(final long id) {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        row.setPadding(dp(8), dp(16), dp(8), dp(16));
        final AlertDialog[] dialog = new AlertDialog[1];
        for (final String e : EMOJI) {
            TextView t = new TextView(getContext());
            t.setText(e);
            t.setTextSize(28);
            t.setPadding(dp(8), dp(4), dp(8), dp(4));
            t.setOnClickListener(v -> {
                dialog[0].dismiss();
                react(id, e);
            });
            row.addView(t);
        }
        dialog[0] = new AlertDialog.Builder(getContext()).setView(row).create();
        dialog[0].show();
    }

    private void react(final long id, final String emoji) {
        if (!Account.signedIn(prefs)) {
            Toast.makeText(getContext(), "Sign in to react", Toast.LENGTH_SHORT).show();
            return;
        }
        final String room = roomId;
        final String host = prefs.getString("host", "");
        final String session = prefs.getString("session", "");
        run(() -> {
            Api.Result r = Api.post(host, "/api/chat/react",
                    Api.form("room", room, "id", String.valueOf(id), "emoji", emoji), session);
            if (r.code == 401) {
                Account.clear(prefs);
            }
            post(() -> afterAction(r));
        });
    }

    private void setPinned(final long id, final boolean pinned) {
        final String room = roomId;
        final String host = prefs.getString("host", "");
        final String session = prefs.getString("session", "");
        run(() -> {
            Api.Result r = Api.post(host, "/api/chat/pin",
                    Api.form("room", room, "id", String.valueOf(id), "pinned", String.valueOf(pinned)), session);
            if (r.code == 401) {
                Account.clear(prefs);
            }
            post(() -> afterAction(r));
        });
    }

    private void afterAction(Api.Result r) {
        if (r.code == 200) {
            errorView.setText("");
            fetch();
        } else {
            errorView.setText(r.error());
            applyAccountState();
        }
    }

    private void startForward(final long id) {
        final String host = prefs.getString("host", "");
        run(() -> {
            final Api.Result r = Api.get(host, "/api/chat/rooms", null);
            post(() -> showForwardDialog(id, r));
        });
    }

    private void showForwardDialog(final long id, Api.Result r) {
        JSONArray rooms = r.code == 200 ? r.json().optJSONArray("rooms") : null;
        if (rooms == null || rooms.length() == 0) {
            Toast.makeText(getContext(), "No rooms to forward to", Toast.LENGTH_SHORT).show();
            return;
        }
        final String[] ids = new String[rooms.length()];
        final String[] titles = new String[rooms.length()];
        for (int i = 0; i < rooms.length(); i++) {
            JSONObject o = rooms.optJSONObject(i);
            ids[i] = o == null ? "" : o.optString("id");
            titles[i] = o == null ? "" : o.optString("title");
        }
        new AlertDialog.Builder(getContext())
                .setTitle("Forward to...")
                .setItems(titles, (d, which) -> forward(id, ids[which], titles[which]))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void forward(final long id, final String toRoom, final String title) {
        final String from = roomId;
        final String host = prefs.getString("host", "");
        final String session = prefs.getString("session", "");
        run(() -> {
            Api.Result r = Api.post(host, "/api/chat/forward",
                    Api.form("fromRoom", from, "toRoom", toRoom, "id", String.valueOf(id)), session);
            if (r.code == 401) {
                Account.clear(prefs);
            }
            post(() -> {
                if (r.code == 200) {
                    errorView.setText("");
                    Toast.makeText(getContext(), "Forwarded to " + title, Toast.LENGTH_SHORT).show();
                    fetch();
                } else {
                    errorView.setText(r.error());
                    applyAccountState();
                }
            });
        });
    }

    private void showInfo(final long id) {
        final String room = roomId;
        final String host = prefs.getString("host", "");
        final String session = prefs.getString("session", "");
        run(() -> {
            final Api.Result r = Api.get(host, "/api/chat/info?room=" + room + "&id=" + id, session);
            post(() -> {
                if (r.code != 200) {
                    errorView.setText(r.error());
                    return;
                }
                JSONObject o = r.json();
                String message = "Delivered to " + o.optInt("deliveredCount") + names(o.optJSONArray("delivered"))
                        + "\n\nRead by " + o.optInt("readCount") + names(o.optJSONArray("read"));
                new AlertDialog.Builder(getContext())
                        .setTitle("Message info")
                        .setMessage(message)
                        .setPositiveButton("OK", null)
                        .show();
            });
        });
    }

    private static String names(JSONArray a) {
        if (a == null || a.length() == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder(": ");
        for (int i = 0; i < a.length(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(a.optString(i));
        }
        return sb.toString();
    }

    private void startReply(long id, String user, String body) {
        clearBar(true);
        replyToId = id;
        String shortText = body.length() > 60 ? body.substring(0, 59) + "\u2026" : body;
        barText.setText("Replying to " + user + ": " + shortText);
        bar.setVisibility(VISIBLE);
        input.requestFocus();
    }

    private void startEdit(long id, String body) {
        clearBar(true);
        editingId = id;
        barText.setText("Editing your message");
        bar.setVisibility(VISIBLE);
        sendBtn.setText("Save");
        input.setText(body);
        input.setSelection(input.getText().length());
        input.requestFocus();
    }

    private void clearBar(boolean clearInputIfEditing) {
        if (editingId > 0 && clearInputIfEditing) {
            input.setText("");
        }
        replyToId = 0;
        editingId = 0;
        bar.setVisibility(GONE);
        sendBtn.setText("Send");
    }

    private void deleteMessage(final long id) {
        final String room = roomId;
        final String host = prefs.getString("host", "");
        final String session = prefs.getString("session", "");
        run(() -> {
            Api.Result r = Api.post(host, "/api/chat/delete",
                    Api.form("room", room, "id", String.valueOf(id)), session);
            if (r.code == 401) {
                Account.clear(prefs);
            }
            post(() -> afterAction(r));
        });
    }

    /** Tells the server how far this user has read, so the senders see blue ticks. */
    private void sendRead() {
        if (!running || roomId == null || !Account.signedIn(prefs) || !ChatClient.sendReceipts(prefs)
                || knownLastId <= lastReadSent) {
            return;
        }
        lastReadSent = knownLastId;
        final String room = roomId;
        final long upTo = knownLastId;
        final String host = prefs.getString("host", "");
        final String session = prefs.getString("session", "");
        run(() -> {
            Api.Result r = Api.post(host, "/api/chat/read",
                    Api.form("room", room, "upTo", String.valueOf(upTo)), session);
            if (r.code == 401) {
                Account.clear(prefs);
            }
        });
    }

    private void maybeTyping() {
        if (roomId == null || editingId > 0 || input.getText().length() == 0 || !Account.signedIn(prefs)) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastTypingSent < TYPING_EVERY_MS) {
            return;
        }
        lastTypingSent = now;
        final String room = roomId;
        final String host = prefs.getString("host", "");
        final String session = prefs.getString("session", "");
        run(() -> Api.post(host, "/api/chat/typing", Api.form("room", room), session));
    }

    private void send() {
        final String message = input.getText().toString().trim();
        if (message.isEmpty() || sending || roomId == null) {
            return;
        }
        sending = true;
        sendBtn.setEnabled(false);
        errorView.setText("");
        final String room = roomId;
        final String host = prefs.getString("host", "");
        final String session = prefs.getString("session", "");
        final long reply = replyToId;
        final long editing = editingId;
        run(() -> {
            Api.Result r = editing > 0
                    ? Api.post(host, "/api/chat/edit",
                            Api.form("room", room, "id", String.valueOf(editing), "text", message), session)
                    : Api.post(host, "/api/chat/post",
                            Api.form("room", room, "text", message, "reply", String.valueOf(reply)), session);
            if (r.code == 401) {
                Account.clear(prefs);
            }
            post(() -> {
                sending = false;
                sendBtn.setEnabled(true);
                if (r.code == 200) {
                    clearBar(false);
                    input.setText("");
                    fetch();
                } else {
                    errorView.setText(r.code == 401 ? "Your sign-in ended. Sign in again." : r.error());
                    applyAccountState();
                }
            });
        });
    }
}