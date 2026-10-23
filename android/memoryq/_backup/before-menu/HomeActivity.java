package com.vstream.broadcaster;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Landing page: server status, your channel, a chat excerpt, and links to every part of the app. */
public class HomeActivity extends AppCompatActivity {

    private static final String PREFS = "vstream";
    private static final long REFRESH_MS = 5000;

    private SharedPreferences prefs;
    private TextView statusView;
    private TextView banner;
    private TextView chatTitle;
    private TextView chatExcerpt;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            checkServer();
            handler.postDelayed(this, REFRESH_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_home);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);

        statusView = findViewById(R.id.homeStatus);
        banner = findViewById(R.id.homeChannel);
        chatTitle = findViewById(R.id.chatTitle);
        chatExcerpt = findViewById(R.id.chatExcerpt);
        findViewById(R.id.cardLive).setOnClickListener(
                v -> startActivity(new Intent(this, MainActivity.class)));
        findViewById(R.id.cardWatch).setOnClickListener(
                v -> startActivity(new Intent(this, ViewerActivity.class)));
        findViewById(R.id.cardChat).setOnClickListener(
                v -> startActivity(new Intent(this, ChatListActivity.class)));
        findViewById(R.id.cardSettings).setOnClickListener(
                v -> startActivity(new Intent(this, SettingsActivity.class)));
        banner.setOnClickListener(v -> onBannerTap());
    }

    @Override
    protected void onStart() {
        super.onStart();
        statusView.setText("Checking server...");
        showBanner();
        handler.post(refresh);
    }

    @Override
    protected void onStop() {
        super.onStop();
        handler.removeCallbacks(refresh);
    }

    private void checkServer() {
        final String host = prefs.getString("host", "10.158.91.70");
        executor.execute(() -> {
            int count = -1;
            try {
                HttpURLConnection c = (HttpURLConnection)
                        new URL("http://" + host + ":8080/channels").openConnection();
                c.setConnectTimeout(2000);
                c.setReadTimeout(2000);
                try {
                    if (c.getResponseCode() == 200) {
                        BufferedReader reader = new BufferedReader(
                                new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8));
                        StringBuilder sb = new StringBuilder();
                        String line;
                        while ((line = reader.readLine()) != null && sb.length() < 65536) {
                            sb.append(line);
                        }
                        reader.close();
                        count = new JSONObject(sb.toString()).getInt("count");
                    }
                } finally {
                    c.disconnect();
                }
            } catch (Exception e) {
                count = -1;
            }

            String excerpt = null;
            int unreadTotal = 0;
            boolean chatOk = false;
            String session = prefs.getString("session", "");
            if (count >= 0) {
                if (Account.signedIn(prefs)) {
                    Api.Result r = Api.get(host, "/api/me", session);
                    if (r.code == 200) {
                        Account.apply(prefs, r.json(), null);
                    } else if (r.code == 401) {
                        Account.clear(prefs);
                    }
                }
                Api.Result top = Api.get(host, "/api/chat/top", null);
                if (top.code == 200) {
                    chatOk = true;
                    JSONObject room = top.json().optJSONObject("room");
                    if (room != null) {
                        excerpt = room.optString("title") + " \u00b7 " + room.optString("lastUser")
                                + ": " + room.optString("lastText");
                    }
                }
                JSONArray rooms = ChatClient.rooms(host);
                unreadTotal = ChatClient.total(ChatClient.unread(host, session, prefs, rooms));
            }
            final int result = count;
            final String finalExcerpt = excerpt;
            final int finalUnread = unreadTotal;
            final boolean finalChatOk = chatOk;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                showStatus(result);
                showBanner();
                showChat(finalChatOk, finalExcerpt, finalUnread);
            });
        });
    }

    private void showStatus(int liveCount) {
        if (liveCount >= 0) {
            statusView.setTextColor(0xFF2E7D32);
            statusView.setText("\u25CF Server online      Live channels: " + liveCount);
        } else {
            statusView.setTextColor(0xFFC62828);
            statusView.setText("\u25CF Server offline - open Go Live or Watch and tap Find server");
        }
    }

    private void showChat(boolean chatOk, String excerpt, int unread) {
        chatTitle.setText(unread > 0 ? "Chat  \u00b7  " + unread + " new" : "Chat");
        if (excerpt != null) {
            chatExcerpt.setText(excerpt);
        } else {
            chatExcerpt.setText(chatOk ? "No recent messages - say hello"
                    : "Rooms for every live channel and public groups");
        }
    }

    private void showBanner() {
        if (!Account.signedIn(prefs) || "ADMIN".equals(prefs.getString("role", ""))) {
            banner.setVisibility(View.GONE);
            return;
        }
        String notice = prefs.getString("notice", "");
        String channel = prefs.getString("claimChannel", "");
        int hours = prefs.getInt("hoursLeft", -1);
        boolean warn = prefs.getBoolean("warning", false);
        String name = prefs.getString("username", "");
        String text;
        int background;
        if (!notice.isEmpty()) {
            text = notice + "\n(tap to dismiss)";
            background = 0xFFFFE0B2;
        } else if (!channel.isEmpty()) {
            text = "Your channel: " + channel + (hours >= 0 ? "  \u00b7  " + hours + " h left" : "");
            if (hours >= 0 && warn) {
                text += "\nUse it soon or it returns to the pool.";
            }
            background = (hours >= 0 && warn) ? 0xFFFFE0B2 : 0xFFE3F2FD;
        } else if ("GUEST".equals(prefs.getString("role", ""))) {
            text = "Signed in as " + name + " (guest) - you can chat. Create a registered account to broadcast.";
            background = 0xFFE3F2FD;
        } else {
            text = "Signed in as " + name + " - open Settings to pick a channel";
            background = 0xFFE3F2FD;
        }
        banner.setText(text);
        banner.setBackgroundColor(background);
        banner.setVisibility(View.VISIBLE);
    }

    private void onBannerTap() {
        if (!prefs.getString("notice", "").isEmpty()) {
            final String host = prefs.getString("host", "");
            final String session = prefs.getString("session", "");
            prefs.edit().remove("notice").apply();
            showBanner();
            executor.execute(() -> Api.post(host, "/api/ack", "", session));
        } else {
            startActivity(new Intent(this, SettingsActivity.class));
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }
}