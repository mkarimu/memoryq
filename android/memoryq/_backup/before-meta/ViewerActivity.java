package com.vstream.broadcaster;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
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
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Audience screen: pick a live channel and watch it, with sound. */
public class ViewerActivity extends AppCompatActivity {

    private static final String PREFS = "vstream";
    private static final String STATE_PLAYING = "playing";
    private static final long REFRESH_MS = 3000;

    private EditText hostField;
    private Button findBtn;
    private TextView statusView;
    private TextView playerNote;
    private PlayerView playerView;
    private LinearLayout tileList;

    private ExoPlayer player;
    private final List<String> liveIds = new ArrayList<>();
    private String playingId = null;
    private String resumeId = null;
    private volatile boolean searching = false;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

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

        hostField = findViewById(R.id.host);
        findBtn = findViewById(R.id.findBtn);
        statusView = findViewById(R.id.status);
        playerNote = findViewById(R.id.playerNote);
        playerView = findViewById(R.id.player);
        tileList = findViewById(R.id.tileList);

        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        hostField.setText(prefs.getString("host", "10.158.91.70"));
        if (savedInstanceState != null) {
            resumeId = savedInstanceState.getString(STATE_PLAYING);
        }
        findBtn.setOnClickListener(v -> findServer());
        findViewById(R.id.homeBtn).setOnClickListener(v -> goHome());
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString(STATE_PLAYING, playingId != null ? playingId : resumeId);
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
                statusView.setText("Playback problem: " + error.getErrorCodeName());
                handler.removeCallbacks(retryPlayback);
                handler.postDelayed(retryPlayback, 3000);
            }
        });
        playerView.setPlayer(player);
        playerView.setUseController(false);
        playingId = null;
        playerNote.setText("Pick a channel");
        playerNote.setVisibility(View.VISIBLE);
        handler.post(refresh);
    }

    @Override
    protected void onStop() {
        super.onStop();
        handler.removeCallbacks(refresh);
        handler.removeCallbacks(retryPlayback);
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
        executor.shutdownNow();
    }

    private void goHome() {
        android.content.Intent i = new android.content.Intent(this, HomeActivity.class);
        i.addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
                | android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(i);
        finish();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    // ---- Playing ----

    private void play(String channelId) {
        String host = hostField.getText().toString().trim();
        if (host.isEmpty() || player == null) {
            return;
        }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("host", host).apply();
        handler.removeCallbacks(retryPlayback);
        playingId = channelId;
        player.setMediaItem(MediaItem.fromUri(
                "http://" + host + ":8888/live/" + channelId + "/index.m3u8"));
        player.prepare();
        player.play();
        playerNote.setText("Loading...");
        playerNote.setVisibility(View.VISIBLE);
        statusView.setText("Watching " + channelId);
        updateTileHighlight();
    }

    // ---- Channel list ----

    private void fetchChannels() {
        final String host = hostField.getText().toString().trim();
        if (host.isEmpty()) {
            statusView.setText("Enter the server address");
            return;
        }
        executor.execute(() -> {
            List<String> found = new ArrayList<>();
            String error = null;
            try {
                HttpURLConnection c = (HttpURLConnection)
                        new URL("http://" + host + ":8080/channels").openConnection();
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
                        found.add(arr.getJSONObject(i).getString("channelId"));
                    }
                } finally {
                    c.disconnect();
                }
            } catch (Exception e) {
                error = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            }
            final List<String> result = found;
            final String err = error;
            runOnUiThread(() -> showChannels(result, err));
        });
    }

    private void showChannels(List<String> live, String err) {
        if (isFinishing() || isDestroyed() || player == null) {
            return;
        }
        if (err != null) {
            liveIds.clear();
            tileList.removeAllViews();
            statusView.setText("Cannot reach server: " + err + " (tap Find server)");
            return;
        }
        if (!live.equals(liveIds)) {
            liveIds.clear();
            liveIds.addAll(live);
            rebuildTiles();
        }
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
            updateTileHighlight();
        }
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
            tile.setText(id + "     \u25CF LIVE");
            tile.setOnClickListener(v -> play(id));
            tileList.addView(tile);
        }
        updateTileHighlight();
    }

    private void updateTileHighlight() {
        for (int i = 0; i < tileList.getChildCount(); i++) {
            View child = tileList.getChildAt(i);
            boolean selected = child.getTag() != null && child.getTag().equals(playingId);
            child.setBackgroundColor(selected ? 0xFF1976D2 : 0xFF455A64);
        }
    }

    // ---- Find server ----

    private void findServer() {
        if (searching) {
            return;
        }
        searching = true;
        findBtn.setEnabled(false);
        statusView.setText("Searching for the server...");
        final String saved = hostField.getText().toString().trim();
        executor.execute(() -> {
            final String result = ServerFinder.find(saved);
            runOnUiThread(() -> {
                searching = false;
                findBtn.setEnabled(true);
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                if (result != null) {
                    hostField.setText(result);
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("host", result).apply();
                    statusView.setText("Server found at " + result);
                } else {
                    statusView.setText("Server not found. Is the PC running and on the same Wi-Fi?");
                }
            });
        });
    }
}