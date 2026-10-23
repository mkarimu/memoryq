package com.vstream.broadcaster;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Landing page: server status, your channel, chat, the news feed, and the menu. */
public class HomeActivity extends AppCompatActivity {

    private static final String PREFS = "vstream";
    private static final long REFRESH_MS = 5000;
    private static final int NEWS_PAGE = 10;
    private static final long NEWS_STALE_MS = 10L * 60 * 1000;

    private SharedPreferences prefs;
    private ScrollView scroll;
    private TextView statusView;
    private TextView banner;
    private TextView chatTitle;
    private TextView chatExcerpt;
    private TextView newsStatus;
    private LinearLayout newsList;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final ExecutorService newsExecutor = Executors.newSingleThreadExecutor();

    private long newsSeed = new Random().nextLong();
    private int newsOffset = 0;
    private int newsTotal = -1;
    private boolean newsLoading = false;
    private long newsLoadedAt = 0;

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

        scroll = findViewById(R.id.homeScroll);
        statusView = findViewById(R.id.homeStatus);
        banner = findViewById(R.id.homeChannel);
        chatTitle = findViewById(R.id.chatTitle);
        chatExcerpt = findViewById(R.id.chatExcerpt);
        newsStatus = findViewById(R.id.newsStatus);
        newsList = findViewById(R.id.newsList);
        findViewById(R.id.cardLive).setOnClickListener(
                v -> startActivity(new Intent(this, MainActivity.class)));
        findViewById(R.id.cardWatch).setOnClickListener(
                v -> startActivity(new Intent(this, ViewerActivity.class)));
        findViewById(R.id.cardChat).setOnClickListener(
                v -> startActivity(new Intent(this, ChatListActivity.class)));
        findViewById(R.id.menuBtn).setOnClickListener(this::showMenu);
        findViewById(R.id.newsShuffle).setOnClickListener(v -> reshuffleNews());
        banner.setOnClickListener(v -> onBannerTap());
        scroll.setOnScrollChangeListener((v, scrollX, scrollY, oldX, oldY) -> {
            View content = scroll.getChildAt(0);
            if (content != null && scrollY + scroll.getHeight() >= content.getHeight() - dp(400)) {
                loadNews();
            }
        });
    }

    @Override
    protected void onStart() {
        super.onStart();
        statusView.setText("Checking server...");
        showBanner();
        handler.post(refresh);
        if (newsList.getChildCount() == 0
                || System.currentTimeMillis() - newsLoadedAt > NEWS_STALE_MS) {
            reshuffleNews();
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        handler.removeCallbacks(refresh);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private boolean isDark() {
        return (getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
    }

    // ---- The three-dots menu ----

    private void showMenu(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor, Gravity.END);
        boolean signed = Account.signedIn(prefs);
        if (!signed) {
            menu.getMenu().add(0, 1, 0, "Sign up");
            menu.getMenu().add(0, 2, 1, "Log in");
        }
        menu.getMenu().add(0, 3, 2, "Profile");
        if (signed) {
            menu.getMenu().add(0, 4, 3, "Log out (" + prefs.getString("username", "") + ")");
        }
        menu.getMenu().add(0, 5, 4, "Settings");
        menu.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1:
                case 2:
                case 5:
                    startActivity(new Intent(this, SettingsActivity.class));
                    return true;
                case 3:
                    startActivity(new Intent(this, ProfileActivity.class));
                    return true;
                case 4:
                    logOut();
                    return true;
                default:
                    return false;
            }
        });
        menu.show();
    }

    private void logOut() {
        final String host = prefs.getString("host", "");
        final String session = prefs.getString("session", "");
        executor.execute(() -> {
            if (!host.isEmpty()) {
                Api.post(host, "/api/logout", "", session);
            }
            Account.clear(prefs);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                showBanner();
                Toast.makeText(this, "Logged out", Toast.LENGTH_SHORT).show();
            });
        });
    }

    // ---- News ----

    private void reshuffleNews() {
        newsSeed = new Random().nextLong();
        newsOffset = 0;
        newsTotal = -1;
        newsLoading = false;
        newsList.removeAllViews();
        newsStatus.setText("Loading news...");
        newsStatus.setVisibility(View.VISIBLE);
        loadNews();
    }

    private void loadNews() {
        if (newsLoading || (newsTotal >= 0 && newsOffset >= newsTotal)) {
            return;
        }
        final String host = prefs.getString("host", "");
        if (host.isEmpty()) {
            newsStatus.setText("Open Settings and find the server to see the news");
            newsStatus.setVisibility(View.VISIBLE);
            return;
        }
        newsLoading = true;
        final int offset = newsOffset;
        final long seed = newsSeed;
        newsExecutor.execute(() -> {
            final Api.Result r = Api.get(host,
                    "/api/news?seed=" + seed + "&offset=" + offset + "&limit=" + NEWS_PAGE, null);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed() || seed != newsSeed) {
                    return;
                }
                showNews(r);
            });
        });
    }

    private void showNews(Api.Result r) {
        newsLoading = false;
        if (r.code != 200) {
            if (newsList.getChildCount() == 0) {
                newsStatus.setText("News is unavailable right now");
                newsStatus.setVisibility(View.VISIBLE);
            }
            return;
        }
        JSONObject data = r.json();
        JSONArray items = data.optJSONArray("items");
        newsTotal = data.optInt("total", 0);
        newsLoadedAt = System.currentTimeMillis();
        int added = items == null ? 0 : items.length();
        for (int i = 0; i < added; i++) {
            JSONObject o = items.optJSONObject(i);
            if (o != null) {
                newsList.addView(newsCard(o));
            }
        }
        newsOffset += added;
        if (newsList.getChildCount() == 0) {
            newsStatus.setText("No news yet. Stories appear once the server has read its news sources.");
            newsStatus.setVisibility(View.VISIBLE);
        } else {
            newsStatus.setVisibility(View.GONE);
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

    private View newsCard(final JSONObject o) {
        boolean dark = isDark();
        int main = dark ? 0xFFEEEEEE : 0xFF212121;
        int body = dark ? 0xFFBDBDBD : 0xFF424242;
        int meta = dark ? 0xFF9E9E9E : 0xFF616161;
        int link = dark ? 0xFF90CAF9 : 0xFF1565C0;

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(12));
        bg.setColor(dark ? 0xFF2B2B2B : 0xFFF1F3F4);
        card.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, dp(12));
        card.setLayoutParams(lp);

        long when = o.optLong("published", 0);
        if (when <= 0) {
            when = o.optLong("created", 0);
        }
        final String source = o.optString("source");
        card.addView(text(source + "  \u00b7  " + o.optString("topic") + "  \u00b7  " + ago(when)
                + "  \u00b7  AI summary", 13, false, meta));
        TextView headline = text(o.optString("headline"), 18, true, main);
        headline.setPadding(0, dp(6), 0, 0);
        card.addView(headline);
        TextView summary = text(o.optString("summary"), 15, false, body);
        summary.setPadding(0, dp(6), 0, 0);
        card.addView(summary);
        TextView read = text("Read original at " + source + " \u2197", 14, true, link);
        read.setPadding(0, dp(10), 0, 0);
        card.addView(read);

        final String url = o.optString("link");
        card.setOnClickListener(v -> openLink(url));
        return card;
    }

    private void openLink(String url) {
        String l = url == null ? "" : url.toLowerCase(java.util.Locale.ROOT);
        if (!(l.startsWith("https://") || l.startsWith("http://"))) {
            return;
        }
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "No browser found to open this link", Toast.LENGTH_SHORT).show();
        }
    }

    private static String ago(long ms) {
        if (ms <= 0) {
            return "";
        }
        long minutes = Math.max(0, System.currentTimeMillis() - ms) / 60000;
        if (minutes < 1) {
            return "just now";
        }
        if (minutes < 60) {
            return minutes + " min ago";
        }
        long hours = minutes / 60;
        if (hours < 24) {
            return hours + " h ago";
        }
        return (hours / 24) + " d ago";
    }

    // ---- Server status, chat excerpt, banner ----

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
        newsExecutor.shutdownNow();
    }
}