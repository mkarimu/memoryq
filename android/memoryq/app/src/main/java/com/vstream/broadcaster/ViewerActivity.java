package com.vstream.broadcaster;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Audience screen: pick a live channel, watch it with sound, and chat about it beside the player. */
public class ViewerActivity extends AppCompatActivity {

    private static final String PREFS = "vstream";
    private static final String STATE_PLAYING = "playing";
    private static final String STATE_TAB = "tab";
    private static final long REFRESH_MS = 3000;

    private SharedPreferences prefs;
    private TextView statusView;
    private TextView playerNote;
    private TextView chatHint;
    private Button findBtn;
    private Button tabChannels;
    private Button tabChat;
    private PlayerView playerView;
    private LinearLayout tileList;
    private View tileScroll;
    private View chatBox;
    private ChatPanel chatPanel;

    private ExoPlayer player;
    private final List<String> liveIds = new ArrayList<>();
    private Map<String, String[]> channelMeta = new HashMap<>();
    private String playingId = null;
    private String resumeId = null;
    private boolean chatTab = false;
    private volatile boolean searching = false;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newCachedThreadPool();

    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            fetchChannels();
            handler.postDelayed(this, REFRESH_MS);
        }
    };

    private final Runnable retryPlayback = new Runnable() {
        @Override
        public void run() {
            if (player != null && playingId != null) {
                player.prepare();
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_viewer);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);

        statusView = findViewById(R.id.status);
        playerNote = findViewById(R.id.playerNote);
        chatHint = findViewById(R.id.chatHint);
        findBtn = findViewById(R.id.findBtn);
        tabChannels = findViewById(R.id.tabChannels);
        tabChat = findViewById(R.id.tabChat);
        playerView = findViewById(R.id.player);
        tileList = findViewById(R.id.tileList);
        tileScroll = findViewById(R.id.tileScroll);
        chatBox = findViewById(R.id.chatBox);
        chatPanel = findViewById(R.id.chatPanel);

        if (savedInstanceState != null) {
            resumeId = savedInstanceState.getString(STATE_PLAYING);
            chatTab = savedInstanceState.getBoolean(STATE_TAB);
        }
        findViewById(R.id.homeBtn).setOnClickListener(v -> {
            Intent i = new Intent(this, HomeActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(i);
            finish();
        });
        findBtn.setOnClickListener(v -> findServer());
        tabChannels.setOnClickListener(v -> showTab(false));
        tabChat.setOnClickListener(v -> showTab(true));
        showTab(chatTab);
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString(STATE_PLAYING, playingId != null ? playingId : resumeId);
        outState.putBoolean(STATE_TAB, chatTab);
    }

    @Override
    protected void onStart() {
        super.onStart();
        player = new ExoPlayer.Builder(this).build();
        player.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build(), true);
        player.setVolume(1f);
        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                if (playingId == null) {
                    return;
                }
                if (state == Player.STATE_BUFFERING) {
                    playerNote.setText("Buffering...");
                    playerNote.setVisibility(View.VISIBLE);
                } else if (state == Player.STATE_READY) {
                    playerNote.setVisibility(View.GONE);
                }
            }

            @Override
            public void onPlayerError(@NonNull PlaybackException error) {
                if (playingId == null) {
                    return;
                }
                playerNote.setText("Reconnecting...");
                playerNote.setVisibility(View.VISIBLE);
                handler.removeCallbacks(retryPlayback);
                handler.postDelayed(retryPlayback, 3000);
            }
        });
        playerView.setPlayer(player);
        playerView.setUseController(false);
        addLiveControls();
        setMuted(muted);
        RadioPlayer.setStopOthers(this::stopChannel);
        playingId = null;
        playerNote.setText("Pick a channel");
        playerNote.setVisibility(View.VISIBLE);
        updateChat();
        handler.post(refresh);
    }

    @Override
    protected void onStop() {
        super.onStop();
        RadioPlayer.setStopOthers(null);
        if (liveControls != null) {
            liveControls.setVisibility(View.GONE);
        }
        handler.removeCallbacks(refresh);
        handler.removeCallbacks(retryPlayback);
        chatPanel.stop();
        if (playingId != null) {
            resumeId = playingId;
        }
        playerView.setPlayer(null);
        if (player != null) {
            player.release();
            player = null;
        }
        playingId = null;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        chatPanel.release();
        executor.shutdownNow();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private String host() {
        return prefs.getString("host", "");
    }

    // ---- Tabs and chat ----

    private void showTab(boolean chat) {
        chatTab = chat;
        tileScroll.setVisibility(chat ? View.GONE : View.VISIBLE);
        chatBox.setVisibility(chat ? View.VISIBLE : View.GONE);
        tabChannels.setEnabled(chat);
        tabChat.setEnabled(!chat);
        updateChat();
    }

    private void updateChat() {
        boolean hasRoom = playingId != null;
        chatPanel.setVisibility(hasRoom ? View.VISIBLE : View.GONE);
        chatHint.setVisibility(hasRoom ? View.GONE : View.VISIBLE);
        if (chatTab && hasRoom) {
            chatPanel.start();
        } else {
            chatPanel.stop();
        }
    }

    // ---- Playing ----

    private void play(String channelId) {
        String host = host();
        if (host.isEmpty() || player == null) {
            return;
        }
        handler.removeCallbacks(retryPlayback);
        playingId = channelId;
        if (RadioPlayer.playing()) {
            RadioPlayer.pause();
        }
        player.setMediaItem(MediaItem.fromUri(
                Api.hls(host) + "/live/" + channelId + "/index.m3u8"));
        player.prepare();
        player.play();
        showLiveControls();
        playerNote.setText("Loading...");
        playerNote.setVisibility(View.VISIBLE);
        statusView.setText("Watching " + channelId);
        updateTileHighlight();
        chatPanel.bind(channelId, "");
        showTab(true);
    }

    // ---- Floating controls on the video: mute and stop ----

    private LinearLayout liveControls;
    private TextView muteBtn;
    private boolean muted = false;

    private void addLiveControls() {
        if (liveControls != null) {
            return;
        }
        android.widget.FrameLayout overlay = playerView.getOverlayFrameLayout();
        ViewGroup holder = overlay != null ? overlay : playerView;
        liveControls = new LinearLayout(this);
        liveControls.setOrientation(LinearLayout.HORIZONTAL);
        liveControls.setGravity(Gravity.CENTER_VERTICAL);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(0x99000000);
        bg.setCornerRadius(dp(20));
        liveControls.setBackground(bg);
        liveControls.setPadding(dp(4), dp(2), dp(4), dp(2));
        muteBtn = controlButton("\uD83D\uDD0A", "Mute");
        muteBtn.setOnClickListener(v -> setMuted(!muted));
        TextView stop = controlButton("\u25A0  Stop", "Stop watching");
        stop.setOnClickListener(v -> stopChannel());
        liveControls.addView(muteBtn);
        liveControls.addView(stop);
        android.widget.FrameLayout.LayoutParams lp = new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
        lp.setMargins(dp(8), dp(8), dp(8), dp(8));
        holder.addView(liveControls, lp);
        liveControls.setVisibility(View.GONE);
    }

    private TextView controlButton(String text, String description) {
        TextView b = new TextView(this);
        b.setText(text);
        b.setContentDescription(description);
        b.setTextColor(0xFFFFFFFF);
        b.setTextSize(14);
        b.setGravity(Gravity.CENTER);
        b.setMinHeight(dp(40));
        b.setPadding(dp(12), 0, dp(12), 0);
        return b;
    }

    private void setMuted(boolean m) {
        muted = m;
        if (player != null) {
            player.setVolume(m ? 0f : 1f);
        }
        if (muteBtn != null) {
            muteBtn.setText(m ? "\uD83D\uDD07" : "\uD83D\uDD0A");
            muteBtn.setContentDescription(m ? "Unmute" : "Mute");
        }
    }

    private void showLiveControls() {
        if (liveControls != null) {
            liveControls.setVisibility(playingId != null ? View.VISIBLE : View.GONE);
        }
    }

    /** Stops the video and its sound; the channel list stays so another can be picked. */
    private void stopChannel() {
        handler.removeCallbacks(retryPlayback);
        if (player != null) {
            player.stop();
        }
        playingId = null;
        resumeId = null;
        playerNote.setText("Stopped - pick a channel");
        playerNote.setVisibility(View.VISIBLE);
        statusView.setText("Stopped");
        updateTileHighlight();
        updateChat();
        showTab(false);
        showLiveControls();
    }    // ---- Channel list ----

    private void fetchChannels() {
        final String host = host();
        if (host.isEmpty()) {
            statusView.setText("Open Settings and find the server");
            return;
        }
        executor.execute(() -> {
            List<String> found = new ArrayList<>();
            final Map<String, String[]> meta = new HashMap<>();
            String error = null;
            try {
                HttpURLConnection c = (HttpURLConnection)
                        new URL(Api.base(host) + "/channels").openConnection();
                c.setConnectTimeout(2500);
                c.setReadTimeout(2500);
                try {
                    int code = c.getResponseCode();
                    if (code != 200) {
                        throw new IOException("HTTP " + code);
                    }
                    BufferedReader reader = new BufferedReader(
                            new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        sb.append(line);
                    }
                    reader.close();
                    JSONArray arr = new JSONObject(sb.toString()).getJSONArray("channels");
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject item = arr.getJSONObject(i);
                        String cid = item.getString("channelId");
                        found.add(cid);
                        meta.put(cid, new String[]{item.optString("title", ""),
                                String.valueOf(item.optInt("viewers", 0))});
                    }
                } finally {
                    c.disconnect();
                }
            } catch (Exception e) {
                error = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            }
            final List<String> result = found;
            final String err = error;
            runOnUiThread(() -> showChannels(result, err, meta));
        });
    }

    private void showChannels(List<String> live, String err, Map<String, String[]> meta) {
        if (isFinishing() || isDestroyed() || player == null) {
            return;
        }
        if (err != null) {
            liveIds.clear();
            tileList.removeAllViews();
            statusView.setText("Cannot reach server: " + Mask.ips(err) + " (tap Find server)");
            return;
        }
        channelMeta = meta;
        if (!live.equals(liveIds)) {
            liveIds.clear();
            liveIds.addAll(live);
            rebuildTiles();
        }
        updateTileTexts();
        pingWatching();

        if (resumeId != null) {
            String id = resumeId;
            resumeId = null;
            if (liveIds.contains(id)) {
                play(id);
                return;
            }
        }
        if (playingId == null) {
            statusView.setText(liveIds.isEmpty() ? "No live channels"
                    : liveIds.size() + " live - tap one to watch");
        } else if (!liveIds.contains(playingId)) {
            handler.removeCallbacks(retryPlayback);
            player.stop();
            playerNote.setText(playingId + " has ended");
            playerNote.setVisibility(View.VISIBLE);
            statusView.setText(playingId + " has ended");
            playingId = null;
            showLiveControls();
            updateTileHighlight();
            updateChat();
        }
    }

    private String labelFor(String id) {
        String[] m = channelMeta.get(id);
        String title = m != null ? m[0] : "";
        String viewers = m != null ? m[1] : "0";
        String line = id + "   \u25CF LIVE   " + viewers + " watching";
        return title.isEmpty() ? line : title + "\n" + line;
    }

    private void rebuildTiles() {
        tileList.removeAllViews();
        for (final String id : liveIds) {
            TextView tile = new TextView(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(72));
            lp.setMargins(dp(8), dp(4), dp(8), dp(4));
            tile.setLayoutParams(lp);
            tile.setGravity(Gravity.CENTER_VERTICAL);
            tile.setPadding(dp(16), 0, dp(16), 0);
            tile.setTextColor(0xFFFFFFFF);
            tile.setTextSize(16);
            tile.setTag(id);
            tile.setText(labelFor(id));
            tile.setOnClickListener(v -> play(id));
            tileList.addView(tile);
        }
        updateTileHighlight();
    }

    private void updateTileTexts() {
        for (int i = 0; i < tileList.getChildCount(); i++) {
            View child = tileList.getChildAt(i);
            Object tag = child.getTag();
            if (tag instanceof String && child instanceof TextView) {
                ((TextView) child).setText(labelFor((String) tag));
            }
        }
    }

    private void updateTileHighlight() {
        for (int i = 0; i < tileList.getChildCount(); i++) {
            View child = tileList.getChildAt(i);
            boolean selected = child.getTag() != null && child.getTag().equals(playingId);
            child.setBackgroundColor(selected ? 0xFF1976D2 : 0xFF455A64);
        }
    }

    /** Tells the server this phone is watching, so the channel can show a viewer count. */
    private void pingWatching() {
        final String id = playingId;
        if (id == null) {
            return;
        }
        final String host = host();
        final String viewer = viewerId();
        executor.execute(() -> {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(
                        Api.base(host) + "/watch?channel=" + id + "&viewer=" + viewer).openConnection();
                c.setRequestMethod("POST");
                c.setConnectTimeout(2000);
                c.setReadTimeout(2000);
                c.getResponseCode();
                c.disconnect();
            } catch (Exception ignored) {
                // the next refresh tries again
            }
        });
    }

    private String viewerId() {
        String v = prefs.getString("viewerId", null);
        if (v == null) {
            v = java.util.UUID.randomUUID().toString();
            prefs.edit().putString("viewerId", v).apply();
        }
        return v;
    }

    // ---- Find server ----

    private void findServer() {
        if (searching) {
            return;
        }
        searching = true;
        findBtn.setEnabled(false);
        statusView.setText("Searching for the server...");
        final String saved = host();
        executor.execute(() -> {
            final String result = ServerFinder.find(saved);
            runOnUiThread(() -> {
                searching = false;
                findBtn.setEnabled(true);
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                if (result != null) {
                    prefs.edit().putString("host", result).apply();
                    statusView.setText("Server found");
                } else {
                    statusView.setText("Cannot reach memoryq.com - check the internet connection");
                }
            });
        });
    }
}