package com.vstream.broadcaster;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONArray;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * One chat room on its own screen. The room itself is the shared ChatPanel. Everything else is in the menu at the
 * top right: for a chat with one person only Block / Unblock; for a group Group info, Clear chat, and Delete group
 * (if you created it) or Exit group (if you did not).
 */
public class ChatRoomActivity extends AppCompatActivity {

    static final String EXTRA_ROOM = "room";
    static final String EXTRA_TITLE = "title";
    static final String EXTRA_OWNER = "owner";

    private SharedPreferences prefs;
    private ChatPanel panel;
    private TextView titleView;
    private String room;
    private String title;
    private String owner;
    /** The other person in a one-to-one chat, or null for groups and live channel rooms. */
    private String peer;
    private boolean privateGroup = false;
    private boolean peerBlocked = false;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private final ActivityResultLauncher<Intent> infoLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                Intent d = result.getData();
                if (d != null && d.hasExtra("title") && titleView != null) {
                    title = d.getStringExtra("title");
                    titleView.setText(title);
                }
            });

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_chat_room);
        prefs = getSharedPreferences("vstream", MODE_PRIVATE);

        room = getIntent().getStringExtra(EXTRA_ROOM);
        title = getIntent().getStringExtra(EXTRA_TITLE);
        owner = getIntent().getStringExtra(EXTRA_OWNER);
        if (owner == null) {
            owner = "";
        }
        if (room == null) {
            finish();
            return;
        }
        peer = peerOf(title);
        titleView = findViewById(R.id.roomTitle);
        titleView.setText(peer != null ? peer : (title == null ? room : title));
        panel = findViewById(R.id.chatPanel);
        panel.bind(room, owner);

        View rooms = findViewById(R.id.roomBack);
        rooms.setOnClickListener(v -> finish());
        if (peer != null) {
            rooms.setVisibility(View.GONE);
        }
        findViewById(R.id.roomHome).setOnClickListener(v -> {
            Intent i = new Intent(this, HomeActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(i);
            finish();
        });
        findViewById(R.id.roomInfo).setVisibility(View.GONE);
        addMenuButton();
        loadDetails();
    }

    /** "ann & bob" -> the one who is not me (a one-to-one chat), otherwise null. */
    private String peerOf(String t) {
        if (t == null || room == null || !room.startsWith("g-")) {
            return null;
        }
        String me = prefs.getString("username", "");
        String[] parts = t.split(" & ");
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

    /** The menu button at the right end of the title bar. */
    private void addMenuButton() {
        if (!(titleView.getParent() instanceof ViewGroup)) {
            return;
        }
        ViewGroup bar = (ViewGroup) titleView.getParent();
        TextView dots = new TextView(this);
        dots.setText("\u22EE");
        dots.setTextSize(24);
        dots.setTypeface(dots.getTypeface(), android.graphics.Typeface.BOLD);
        dots.setTextColor(titleView.getCurrentTextColor());
        dots.setGravity(Gravity.CENTER);
        dots.setContentDescription(peer != null ? "More: block" : "More: group info, clear chat, exit or delete");
        dots.setOnClickListener(this::showMenu);
        if (bar instanceof LinearLayout) {
            ViewGroup.LayoutParams tl = titleView.getLayoutParams();
            if (tl instanceof LinearLayout.LayoutParams && ((LinearLayout.LayoutParams) tl).weight == 0
                    && tl.width != ViewGroup.LayoutParams.MATCH_PARENT) {
                View spacer = new View(this);
                bar.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));
            }
            bar.addView(dots, new LinearLayout.LayoutParams(dp(44), dp(44)));
        } else {
            bar.addView(dots, new ViewGroup.LayoutParams(dp(44), dp(44)));
        }
    }

    /** Learns who created the group, whether it is private, and whether the other person is blocked. */
    private void loadDetails() {
        final String host = prefs.getString("host", "");
        final String session = prefs.getString("session", "");
        if (host.isEmpty() || !Account.signedIn(prefs)) {
            return;
        }
        executor.execute(() -> {
            if (peer != null) {
                Api.Result b = Api.get(host, "/api/wall/blocked", session);
                JSONArray list = b.code == 200 ? b.json().optJSONArray("blocked") : null;
                boolean blocked = false;
                for (int i = 0; list != null && i < list.length(); i++) {
                    blocked |= list.optString(i).equalsIgnoreCase(peer);
                }
                final boolean now = blocked;
                runOnUiThread(() -> peerBlocked = now);
            } else if (room.startsWith("g-")) {
                Api.Result m = Api.get(host, "/api/chat/groups/members?room=" + room, session);
                if (m.code == 200) {
                    final String o = m.json().optString("owner", owner);
                    runOnUiThread(() -> {
                        privateGroup = true;
                        if (!o.isEmpty()) {
                            owner = o;
                        }
                    });
                }
            }
        });
    }

    private boolean mine() {
        String me = prefs.getString("username", "");
        return !me.isEmpty() && me.equalsIgnoreCase(owner);
    }

    private void showMenu(View anchor) {
        PopupMenu m = new PopupMenu(this, anchor);
        if (peer != null) {
            m.getMenu().add(0, 5, 0, peerBlocked ? "Unblock " + peer : "Block " + peer);
        } else {
            if (room.startsWith("g-")) {
                m.getMenu().add(0, 1, 0, "Group info");
            }
            m.getMenu().add(0, 2, 1, "Clear chat");
            if (room.startsWith("g-")) {
                if (mine()) {
                    m.getMenu().add(0, 4, 3, "Delete group");
                } else if (privateGroup) {
                    m.getMenu().add(0, 3, 2, "Exit group");
                }
            }
        }
        m.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1:
                    Intent i = new Intent(this, GroupInfoActivity.class);
                    i.putExtra(GroupInfoActivity.EXTRA_ROOM, room);
                    i.putExtra(GroupInfoActivity.EXTRA_TITLE, title == null ? room : title);
                    i.putExtra(GroupInfoActivity.EXTRA_OWNER, owner);
                    infoLauncher.launch(i);
                    break;
                case 2:
                    new AlertDialog.Builder(this)
                            .setTitle("Clear this chat?")
                            .setMessage("The messages so far disappear from this phone. Other people still see them.")
                            .setNegativeButton("Cancel", null)
                            .setPositiveButton("Clear", (d, w) -> panel.clearHistory())
                            .show();
                    break;
                case 3:
                    new AlertDialog.Builder(this)
                            .setTitle("Exit " + title + "?")
                            .setMessage("You will no longer see this group. You can only come back if you are invited again.")
                            .setNegativeButton("Cancel", null)
                            .setPositiveButton("Exit", (d, w) -> leaveOrDelete("/api/chat/groups/leave", "You left the group"))
                            .show();
                    break;
                case 4:
                    new AlertDialog.Builder(this)
                            .setTitle("Delete " + title + "?")
                            .setMessage("The group and all its messages will be deleted for everyone. This cannot be undone.")
                            .setNegativeButton("Cancel", null)
                            .setPositiveButton("Delete", (d, w) -> leaveOrDelete("/api/chat/groups/delete", "Group deleted"))
                            .show();
                    break;
                case 5:
                    if (peerBlocked) {
                        setBlocked(false);
                    } else {
                        new AlertDialog.Builder(this)
                                .setTitle("Block " + peer + "?")
                                .setMessage("They will not be able to call you or comment on your posts. They are not told.")
                                .setNegativeButton("Cancel", null)
                                .setPositiveButton("Block", (d, w) -> setBlocked(true))
                                .show();
                    }
                    break;
                default:
                    break;
            }
            return true;
        });
        m.show();
    }

    private void leaveOrDelete(final String path, final String done) {
        final String host = prefs.getString("host", "");
        final String session = prefs.getString("session", "");
        executor.execute(() -> {
            Api.Result r = Api.post(host, path, Api.form("room", room), session);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                if (r.code == 200) {
                    Toast.makeText(this, done, Toast.LENGTH_SHORT).show();
                    Intent i = new Intent(this, ChatListActivity.class);
                    i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                    startActivity(i);
                    finish();
                } else {
                    Toast.makeText(this, r.code == 0 ? "Cannot reach the server" : r.error(), Toast.LENGTH_LONG).show();
                }
            });
        });
    }

    private void setBlocked(final boolean block) {
        final String host = prefs.getString("host", "");
        final String session = prefs.getString("session", "");
        final String who = peer;
        executor.execute(() -> {
            Api.Result r = Api.post(host, block ? "/api/wall/block" : "/api/wall/unblock", Api.form("u", who), session);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                if (r.code == 200) {
                    peerBlocked = block;
                    Toast.makeText(this, block ? who + " is blocked" : who + " is unblocked", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, r.code == 0 ? "Cannot reach the server" : r.error(), Toast.LENGTH_LONG).show();
                }
            });
        });
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (panel != null) {
            panel.start();
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (panel != null) {
            panel.stop();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (panel != null) {
            panel.release();
        }
        executor.shutdownNow();
    }
}