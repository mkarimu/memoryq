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

/** Landing page: the hamburger menu (Go Live, On-Air, Radio, Chats, Calls), server status, your channel, and the news. */
public class HomeActivity extends AppCompatActivity {

    private static final String PREFS = "vstream";
    private static final long REFRESH_MS = 5000;
    private static final int NEWS_PAGE = 10;
    private static final long NEWS_STALE_MS = 10L * 60 * 1000;

    private SharedPreferences prefs;
    private ScrollView scroll;
    private TextView statusView;
    private TextView banner;
    private TextView optionsBtn;
    private TextView newsStatus;
    private int liveCount = -1;
    private int unreadChats = 0;
    private TextView bell;
    private int newsShown = 0;
    private int sliderEvery = 20;
    private int wallEvery = 5;
    private final java.util.LinkedHashSet<Long> wallShown = new java.util.LinkedHashSet<>();
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
        statusView.setText("");
        statusView.setTextSize(10f);
        banner = findViewById(R.id.homeChannel);
        optionsBtn = findViewById(R.id.homeHamburger);
        newsStatus = findViewById(R.id.newsStatus);
        newsList = findViewById(R.id.newsList);
        optionsBtn.setOnClickListener(this::showOptions);
        findViewById(R.id.menuBtn).setOnClickListener(this::showMenu);
        bell = findViewById(R.id.homeBell);
        bell.setOnClickListener(v -> startActivity(new Intent(this, NotificationsActivity.class)));
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
        showBanner();
        handler.post(refresh);
        EmailVerify.maybePrompt(this);
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

    // ---- The hamburger menu ----

    private void showOptions(View anchor) {
        final java.util.List<String> items = new java.util.ArrayList<>();
        final java.util.List<Class<?>> targets = new java.util.ArrayList<>();
        // only the services the super-admin gave this member (Admin -> Profiler -> Services)
        if (Account.allows(prefs, "live")) {
            items.add("\uD83D\uDD34  Go Live  -  broadcast from this phone");
            targets.add(MainActivity.class);
        }
        if (Account.allows(prefs, "tv")) {
            items.add("\uD83D\uDCFA  On-Air  -  watch who is live" + (liveCount > 0 ? " (" + liveCount + " live)" : ""));
            targets.add(ViewerActivity.class);
        }
        long radioOn = RadioPlayer.stationId();
        if (Account.allows(prefs, "radio")) {
            items.add("\uD83D\uDCFB  Radio" + (radioOn != 0 ? "  -  playing " + RadioPlayer.stationName() : ""));
            targets.add(RadioActivity.class);
        }
        if (Account.allows(prefs, "chat")) {
            items.add("\uD83D\uDCAC  Chats" + (unreadChats > 0 ? "  -  " + unreadChats + " new" : ""));
            targets.add(ChatListActivity.class);
        }
        if (Account.allows(prefs, "market")) {
            items.add("\uD83D\uDED2  Marketplace  -  buy and sell");
            targets.add(MarketActivity.class);
        }
        if (Account.signedIn(prefs) && Account.allows(prefs, "people")) {
            items.add("\uD83D\uDC65  People");
            targets.add(PeopleActivity.class);
        }
        PopupMenu menu = new PopupMenu(this, anchor, Gravity.START);
        for (int i = 0; i < items.size(); i++) {
            menu.getMenu().add(0, i, i, items.get(i));
        }
        menu.setOnMenuItemClickListener(item -> {
            startActivity(new Intent(this, targets.get(item.getItemId())));
            return true;
        });
        menu.show();
    }

    /** The bell shows when signed in, with the number of unread notifications in red. */
    private void showBell(int unread) {
        if (bell == null) {
            return;
        }
        if (unread < 0 || !Account.signedIn(prefs)) {
            bell.setVisibility(View.GONE);
            return;
        }
        bell.setVisibility(View.VISIBLE);
        if (unread == 0) {
            bell.setText("\uD83D\uDD14");
        } else {
            String n = unread > 99 ? "99+" : String.valueOf(unread);
            android.text.SpannableString s = new android.text.SpannableString("\uD83D\uDD14" + n);
            s.setSpan(new android.text.style.ForegroundColorSpan(0xFFE53935), 2, 2 + n.length(),
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            s.setSpan(new android.text.style.RelativeSizeSpan(0.7f), 2, 2 + n.length(),
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            s.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD), 2, 2 + n.length(),
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            bell.setText(s);
        }
    }

    /** A small red dot on the hamburger when there are new chat messages or missed calls. */
    private void updateOptionsBtn() {
        int missed = CallMonitor.callsEnabled() && Account.signedIn(prefs) ? CallMonitor.missedCount() : 0;
        if (unreadChats > 0 || missed > 0) {
            android.text.SpannableString s = new android.text.SpannableString("\u2630\u25CF");
            s.setSpan(new android.text.style.ForegroundColorSpan(0xFFE53935), 1, 2,
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            s.setSpan(new android.text.style.RelativeSizeSpan(0.45f), 1, 2,
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            optionsBtn.setText(s);
        } else {
            optionsBtn.setText("\u2630");
        }
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
        if (signed && !"ADMIN".equals(prefs.getString("role", "")) && prefs.getInt("adminMailTotal", 0) > 0) {
            int unreadMail = prefs.getInt("adminMailUnread", 0);
            menu.getMenu().add(0, 6, 3, "Messages from admin" + (unreadMail > 0 ? " (" + unreadMail + " new)" : ""));
        }
        menu.getMenu().add(0, 5, 4, "Settings");
        menu.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == 6) {
                startActivity(new Intent(this, AdminMailActivity.class));
                return true;
            }
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
        newsShown = 0;
        wallShown.clear();
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
            newsStatus.setText("News appears once the server is found");
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
                newsShown++;
                if (newsShown % sliderEvery == 0) {
                    addSlider();
                } else if (wallEvery > 0 && newsShown % wallEvery == 0 && Account.signedIn(prefs)) {
                    addWallPost();
                }
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

    /** A random public wall post after every few stories; the server says how many. */
    private void addWallPost() {
        final LinearLayout slot = new LinearLayout(this);
        slot.setOrientation(LinearLayout.VERTICAL);
        newsList.addView(slot, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        final String host = prefs.getString("host", "");
        final String session = prefs.getString("session", "");
        StringBuilder skip = new StringBuilder();
        for (Long id : wallShown) {
            if (skip.length() > 0) {
                skip.append(',');
            }
            skip.append(id);
        }
        final String skipList = skip.toString();
        newsExecutor.execute(() -> {
            final Api.Result r = Api.get(host, "/api/wall/random?" + Api.form("skip", skipList), session);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                JSONObject o = r.code == 200 ? r.json() : null;
                JSONObject post = o == null ? null : o.optJSONObject("post");
                if (o != null) {
                    wallEvery = o.optInt("every", wallEvery);
                }
                if (post == null) {
                    newsList.removeView(slot);
                    return;
                }
                wallShown.add(post.optLong("id"));
                slot.addView(WallUi.card(this, prefs, post, true));
            });
        });
    }

    /** A slider (4 people and an ad) after every few stories; the server says how many. */
    private void addSlider() {
        LinearLayout slot = new LinearLayout(this);
        slot.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(4), 0, dp(12));
        slot.setLayoutParams(lp);
        newsList.addView(slot);
        NewsSlider.fill(this, prefs, slot, n -> sliderEvery = Math.max(5, Math.min(100, n)));
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
        card.setPadding(dp(12), dp(8), dp(12), dp(8));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(10));
        bg.setColor(dark ? 0xFF2B2B2B : 0xFFF1F3F4);
        card.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, dp(8));
        card.setLayoutParams(lp);

        long when = o.optLong("published", 0);
        if (when <= 0) {
            when = o.optLong("created", 0);
        }
        final String source = o.optString("source");
        TextView metaLine = text(source + "  \u00b7  " + o.optString("topic") + "  \u00b7  " + ago(when)
                + "  \u00b7  AI summary  \u2197", 11, false, meta);
        metaLine.setMaxLines(1);
        metaLine.setEllipsize(android.text.TextUtils.TruncateAt.END);
        card.addView(metaLine);
        TextView headline = text(o.optString("headline"), 15, true, main);
        headline.setPadding(0, dp(2), 0, 0);
        headline.setMaxLines(2);
        headline.setEllipsize(android.text.TextUtils.TruncateAt.END);
        card.addView(headline);
        final TextView summary = text(o.optString("summary"), 13, false, body);
        summary.setPadding(0, dp(2), 0, 0);
        summary.setMaxLines(2);
        summary.setEllipsize(android.text.TextUtils.TruncateAt.END);
        card.addView(summary);
        TextView read = text("Read original at " + source + " \u2197", 12, true, link);
        read.setPadding(0, dp(4), 0, 0);
        read.setVisibility(View.GONE);
        card.addView(read);
        // First tap shows the whole summary; the second tap (or the link) opens the original story.
        final String url0 = o.optString("link");
        read.setOnClickListener(v -> openLink(url0));

        card.setOnClickListener(v -> {
            if (read.getVisibility() == View.GONE) {
                summary.setMaxLines(Integer.MAX_VALUE);
                read.setVisibility(View.VISIBLE);
            } else {
                openLink(url0);
            }
        });
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
        executor.execute(() -> {
            String found = prefs.getString("host", "");
            if (found.isEmpty() || !ServerFinder.isServer(found, 1500)) {
                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed()) {
                        if (liveCount < 0) {
                            statusView.setTextColor(0xFF9E9E9E);
                            statusView.setText("\u25CF connecting\u2026");
                        }
                    }
                });
                String h = ServerFinder.ensure(prefs, false);
                found = h == null ? "" : h;
                if (!found.isEmpty()) {
                    runOnUiThread(() -> {
                        if (!isFinishing() && !isDestroyed() && newsList.getChildCount() == 0) {
                            reshuffleNews();
                        }
                    });
                }
            }
            final String host = found;
            int count = -1;
            if (host.isEmpty()) {
                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed()) {
                        showStatus(-1);
                    }
                });
                return;
            }
            try {
                HttpURLConnection c = (HttpURLConnection)
                        new URL(Api.base(host) + "/channels").openConnection();
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
            int notifyCount = -1;
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
                if (Account.signedIn(prefs) && !"ADMIN".equals(prefs.getString("role", ""))) {
                    Api.Result am = Api.get(host, "/api/chat/adminmail", session);
                    if (am.code == 200) {
                        JSONObject mo = am.json();
                        JSONArray ml = mo.optJSONArray("messages");
                        prefs.edit().putInt("adminMailUnread", mo.optInt("unread", 0))
                                .putInt("adminMailTotal", ml == null ? 0 : ml.length()).apply();
                    }
                }
                JSONArray rooms = ChatClient.rooms(host, session);
                unreadTotal = ChatClient.total(ChatClient.unread(host, session, prefs, rooms));
                if (Account.signedIn(prefs)) {
                    Api.Result nc = Api.get(host, "/api/notify/count", session);
                    notifyCount = nc.code == 200 ? nc.json().optInt("unread", 0) : -1;
                }
            }
            final int result = count;
            final String finalExcerpt = excerpt;
            final int finalUnread = unreadTotal;
            final int finalNotify = notifyCount;
            final boolean finalChatOk = chatOk;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                showStatus(result);
                unreadChats = finalUnread;
                updateOptionsBtn();
                showBell(finalNotify);
                showBanner();
            });
        });
    }

    private void showStatus(int count) {
        liveCount = count;
        if (liveCount >= 0) {
            statusView.setTextColor(0xFF2E7D32);
            statusView.setText(liveCount > 0 ? "\u25CF  " + liveCount + " live" : "\u25CF");
        } else {
            statusView.setTextColor(0xFFC62828);
            statusView.setText("\u25CF connecting\u2026");
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