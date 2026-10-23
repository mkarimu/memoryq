package com.vstream.broadcaster;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Someone's profile: picture (tap to see it full screen), details, friends and Add friend; call, video, chat and block in the top-right menu. */
public class PersonProfileActivity extends AppCompatActivity {

    static final String EXTRA_USER = "user";

    private SharedPreferences prefs;
    private String user;
    private LinearLayout content;
    private TextView status;
    private TextView menuBtn;
    /** What the top-right menu offers, set once the profile has loaded. */
    private String menuName = "";
    private boolean menuCanBlock = false;
    private boolean menuBlocked = false;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("vstream", MODE_PRIVATE);
        user = getIntent().getStringExtra(EXTRA_USER);
        if (user == null || user.isEmpty()) {
            finish();
            return;
        }
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
        TextView title = ProfileUi.text(this, "Profile", 20, true);
        bar.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        menuBtn = ProfileUi.text(this, "\u22EE", 24, true);
        menuBtn.setGravity(Gravity.CENTER);
        menuBtn.setContentDescription("More: call, video, chat, block");
        menuBtn.setVisibility(android.view.View.GONE);
        menuBtn.setOnClickListener(this::showMenu);
        bar.addView(menuBtn, new LinearLayout.LayoutParams(dp(44), dp(44)));
        root.addView(bar);

        ScrollView scroll = new ScrollView(this);
        LinearLayout inner = new LinearLayout(this);
        inner.setOrientation(LinearLayout.VERTICAL);
        inner.setPadding(dp(20), 0, dp(20), dp(24));
        status = ProfileUi.text(this, "Loading...", 14, false);
        inner.addView(status);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        inner.addView(content);
        scroll.addView(inner);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (user != null) {
            load();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }

    private int dp(int v) {
        return ProfileUi.dp(this, v);
    }

    private void ui(Runnable r) {
        runOnUiThread(() -> {
            if (!isFinishing() && !isDestroyed()) {
                r.run();
            }
        });
    }

    private void load() {
        if (!Account.signedIn(prefs)) {
            status.setText("Sign in to see people's profiles.");
            return;
        }
        final String host = prefs.getString("host", "");
        final String session = prefs.getString("session", "");
        executor.execute(() -> {
            final Api.Result r = Api.get(host, "/api/profiles/view?u=" + Api.form("x", user).substring(2), session);
            final Api.Result br = Api.get(host, "/api/wall/blocked", session);
            boolean isBlocked = false;
            org.json.JSONArray bl = br.code == 200 ? br.json().optJSONArray("blocked") : null;
            for (int i = 0; bl != null && i < bl.length(); i++) {
                isBlocked |= bl.optString(i).equalsIgnoreCase(user);
            }
            final boolean blockedNow = isBlocked;
            final boolean canBlock = br.code == 200;
            if (r.code == 401) {
                Account.clear(prefs);
            }
            ui(() -> {
                if (r.code != 200) {
                    status.setText(r.code == 0 ? "Cannot reach the server" : r.error());
                    return;
                }
                status.setVisibility(android.view.View.GONE);
                render(r.json(), canBlock, blockedNow);
            });
        });
    }

    private void render(final JSONObject p, boolean canBlock, final boolean blocked) {
        final String host = prefs.getString("host", "");
        content.removeAllViews();
        content.addView(ProfileUi.card(this, host, p));
        final String name = p.optString("username");
        if (!p.optBoolean("me")) {
            menuName = name;
            menuCanBlock = canBlock;
            menuBlocked = blocked;
            menuBtn.setVisibility(android.view.View.VISIBLE);
            final boolean friend = p.optBoolean("isFriend");
            Button fb = new Button(this);
            fb.setText(friend ? "Remove from friends" : "Add friend");
            fb.setOnClickListener(v -> setFriend(name, !friend));
            content.addView(fb);
        } else {
            menuBtn.setVisibility(android.view.View.GONE);
        }
        ProfileUi.friends(this, host, content, p);
        TextView wallTitle = ProfileUi.text(this, "Wall", 18, true);
        wallTitle.setPadding(0, dp(16), 0, dp(4));
        content.addView(wallTitle);
        LinearLayout wallList = new LinearLayout(this);
        wallList.setOrientation(LinearLayout.VERTICAL);
        content.addView(wallList);
        WallUi.loadWall(this, prefs, name, wallList, 0);
    }

    /** The top-right menu: voice call, video call, private chat, block / unblock. */
    private void showMenu(android.view.View anchor) {
        final String name = menuName;
        if (name.isEmpty()) {
            return;
        }
        android.widget.PopupMenu m = new android.widget.PopupMenu(this, anchor);
        if (CallStarter.enabled(prefs)) {
            m.getMenu().add(0, 1, 0, "\uD83D\uDCDE  Voice call");
            m.getMenu().add(0, 2, 1, "\uD83C\uDFA5  Video call");
        }
        m.getMenu().add(0, 3, 2, "\uD83D\uDCAC  Private chat");
        if (menuCanBlock) {
            m.getMenu().add(0, 4, 3, menuBlocked ? "Unblock" : "Block");
        }
        m.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1:
                    CallStarter.callPerson(this, prefs, name, false, null);
                    break;
                case 2:
                    CallStarter.callPerson(this, prefs, name, true, null);
                    break;
                case 3:
                    PersonActions.privateChat(this, prefs, name);
                    break;
                case 4:
                    if (menuBlocked) {
                        setBlocked(name, false);
                    } else {
                        new androidx.appcompat.app.AlertDialog.Builder(this)
                                .setTitle("Block " + name + "?")
                                .setMessage("They will not be able to call you or comment on your posts. They are not told.")
                                .setNegativeButton("Cancel", null)
                                .setPositiveButton("Block", (d, w) -> setBlocked(name, true))
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

    private void setBlocked(final String name, final boolean block) {
        final String host = prefs.getString("host", "");
        final String session = prefs.getString("session", "");
        executor.execute(() -> {
            Api.Result r = Api.post(host, block ? "/api/wall/block" : "/api/wall/unblock", Api.form("u", name), session);
            ui(() -> {
                if (r.code == 200) {
                    Toast.makeText(this, block ? name + " is blocked" : name + " is unblocked", Toast.LENGTH_SHORT).show();
                    load();
                } else {
                    Toast.makeText(this, r.code == 0 ? "Cannot reach the server" : r.error(), Toast.LENGTH_LONG).show();
                }
            });
        });
    }

    private void setFriend(final String name, final boolean add) {
        final String host = prefs.getString("host", "");
        final String session = prefs.getString("session", "");
        executor.execute(() -> {
            Api.Result r = Api.post(host, add ? "/api/profiles/friends/add" : "/api/profiles/friends/remove",
                    Api.form("u", name), session);
            ui(() -> {
                if (r.code == 200) {
                    Toast.makeText(this, add ? name + " added to your friends" : name + " removed from your friends",
                            Toast.LENGTH_SHORT).show();
                    load();
                } else {
                    Toast.makeText(this, r.code == 0 ? "Cannot reach the server" : r.error(), Toast.LENGTH_LONG).show();
                }
            });
        });
    }
}