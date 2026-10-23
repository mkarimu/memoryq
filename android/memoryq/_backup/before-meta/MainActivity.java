package com.vstream.broadcaster;

import android.Manifest;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;

import com.pedro.common.ConnectChecker;
import com.pedro.library.rtmp.RtmpStream;
import com.pedro.encoder.input.sources.audio.MicrophoneSource;
import com.pedro.encoder.input.sources.audio.NoAudioSource;
import com.pedro.encoder.input.sources.video.Camera2Source;
import com.pedro.encoder.input.sources.video.NoVideoSource;

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
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.util.Collections;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;

public class MainActivity extends AppCompatActivity
        implements ConnectChecker, SurfaceHolder.Callback {

    private static final int REQUEST_PERMISSIONS = 1;
    private static final String PREFS = "vstream";
    private static final long REFRESH_MS = 3000;

    // ---- broadcaster ----
    private RtmpStream rtmpStream;
    private SurfaceView preview;
    private TextView status;
    private EditText hostField;
    private EditText keyField;
    private Button startBtn;
    private Button pauseBtn;
    private Button stopBtn;
    private Button switchBtn;
    private Button watchBtn;
    private Button findBtn;
    private volatile boolean searching = false;

    private boolean surfaceReady = false;
    private boolean prepared = false;
    private boolean paused = false;
    private volatile boolean stoppedByUser = false;
    private volatile String lastError = null;
    private volatile boolean everConnected = false;
    private volatile boolean reconnecting = false;

    // ---- silent monitor (viewer) ----
    private PlayerView playerView;
    private ScrollView tileScroll;
    private LinearLayout tileRow;
    private TextView viewerNote;
    private ExoPlayer player;
    private final List<String> liveIds = new ArrayList<>();
    private boolean watching = false;
    private String playingId = null;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            fetchChannels();
            handler.postDelayed(this, REFRESH_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        preview = findViewById(R.id.preview);
        preview.setZOrderMediaOverlay(true);
        status = findViewById(R.id.status);
        hostField = findViewById(R.id.host);
        keyField = findViewById(R.id.key);
        startBtn = findViewById(R.id.startBtn);
        pauseBtn = findViewById(R.id.pauseBtn);
        stopBtn = findViewById(R.id.stopBtn);
        switchBtn = findViewById(R.id.switchBtn);
        watchBtn = findViewById(R.id.watchBtn);
        findBtn = findViewById(R.id.findBtn);
        findBtn.setOnClickListener(v -> findServer(false));
        playerView = findViewById(R.id.player);
        tileScroll = findViewById(R.id.tileScroll);
        tileRow = findViewById(R.id.tileRow);
        viewerNote = findViewById(R.id.viewerNote);

        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        hostField.setText(prefs.getString("host", "10.158.91.70"));
        keyField.setText(prefs.getString("key", ""));

        rtmpStream = new RtmpStream(this, this);
        rtmpStream.getStreamClient().setReTries(20);
        preview.getHolder().addCallback(this);
        findServer(true);

        startBtn.setOnClickListener(v -> startStreaming());
        pauseBtn.setOnClickListener(v -> {
            if (paused) {
                resumeStreaming();
            } else {
                pauseStreaming();
            }
        });
        stopBtn.setOnClickListener(v -> stopStreaming());
        switchBtn.setOnClickListener(v -> switchCamera());
        findViewById(R.id.homeBtn).setOnClickListener(v -> goHome());
        getOnBackPressedDispatcher().addCallback(this, new androidx.activity.OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                goHome();
            }
        });
        watchBtn.setOnClickListener(v -> {
            if (watching) {
                exitWatchMode();
            } else {
                enterWatchMode();
            }
        });

        if (!hasPermissions()) {
            ActivityCompat.requestPermissions(this,
                    permissionsToRequest(),
                    REQUEST_PERMISSIONS);
            setStatus("Allow camera and microphone");
        } else {
            setStatus("Ready");
        }
        updateButtons();
    }

    private String[] permissionsToRequest() {
        if (Build.VERSION.SDK_INT >= 33) {
            return new String[]{Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.POST_NOTIFICATIONS};
        }
        return new String[]{Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO};
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private boolean hasPermissions() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED
                && ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_PERMISSIONS) {
            if (hasPermissions()) {
                setStatus("Ready");
                startPreviewIfPossible();
            } else {
                setStatus("Camera and microphone permission needed");
            }
        }
    }

    private boolean prepare() {
        if (prepared) {
            return true;
        }
        try {
            prepared = rtmpStream.prepareVideo(1280, 720, 2500 * 1000)
                    && rtmpStream.prepareAudio(44100, true, 128 * 1000);
        } catch (IllegalArgumentException e) {
            prepared = false;
        }
        return prepared;
    }

    private void startPreviewIfPossible() {
        if (surfaceReady && hasPermissions() && !rtmpStream.isOnPreview()) {
            if (prepare()) {
                rtmpStream.startPreview(preview);
            } else {
                setStatus("This phone cannot encode 1280x720");
            }
        }
    }

    // ---- Broadcaster buttons ----

    private void startStreaming() {
        String host = hostField.getText().toString().trim();
        String key = keyField.getText().toString().trim();
        if (host.isEmpty() || key.isEmpty()) {
            setStatus("Enter the server address and stream key");
            return;
        }
        if (!hasPermissions()) {
            setStatus("Camera and microphone permission needed");
            return;
        }
        if (!prepare()) {
            setStatus("This phone cannot encode 1280x720");
            return;
        }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString("host", host).putString("key", key).apply();

        if (!rtmpStream.isOnPreview()) {
            rtmpStream.startPreview(preview);
        }
        lastError = null;
        stoppedByUser = false;
        paused = false;
        setStatus("Connecting to " + host + " ...");
        everConnected = false;
        reconnecting = false;
        startKeepAliveService(key);
        rtmpStream.startStream("rtmp://" + host + ":1935/live/" + key);
        updateButtons();
    }

    private void pauseStreaming() {
        if (!rtmpStream.isStreaming() || paused) {
            return;
        }
        try {
            rtmpStream.changeVideoSource(new NoVideoSource());
            rtmpStream.changeAudioSource(new NoAudioSource());
            paused = true;
            setStatus("PAUSED (viewers see a black screen)");
        } catch (RuntimeException e) {
            setStatus("Cannot pause: " + e.getMessage());
        }
        updateButtons();
    }

    private void resumeStreaming() {
        if (!paused) {
            return;
        }
        try {
            restoreSources();
            setStatus("LIVE");
        } catch (RuntimeException e) {
            setStatus("Cannot resume: " + e.getMessage());
        }
        updateButtons();
    }

    private void stopStreaming() {
        stoppedByUser = true;
        reconnecting = false;
        stopKeepAliveService();
        if (rtmpStream.isStreaming()) {
            rtmpStream.stopStream();
        }
        try {
            if (paused) {
                restoreSources();
            }
        } catch (RuntimeException e) {
            // preview may stay black until the app is reopened
        }
        paused = false;
        setStatus("Stopped");
        updateButtons();
    }

    private void switchCamera() {
        if (paused) {
            return;
        }
        try {
            Object source = rtmpStream.getVideoSource();
            if (source instanceof Camera2Source) {
                ((Camera2Source) source).switchCamera();
            } else {
                setStatus("Camera is not active");
            }
        } catch (RuntimeException e) {
            setStatus("Cannot switch camera: " + e.getMessage());
        }
    }

    private void restoreSources() {
        rtmpStream.changeVideoSource(new Camera2Source(getApplicationContext()));
        rtmpStream.changeAudioSource(new MicrophoneSource());
        paused = false;
    }

    private void startKeepAliveService(String key) {
        int q = key.indexOf('?');
        String label = q > 0 ? key.substring(0, q) : "stream";
        Intent i = new Intent(this, StreamService.class);
        i.putExtra(StreamService.EXTRA_LABEL, label);
        ContextCompat.startForegroundService(this, i);
    }

    private void stopKeepAliveService() {
        stopService(new Intent(this, StreamService.class));
    }

    private void goHome() {
        if (rtmpStream.isStreaming()) {
            new androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("You are live")
                    .setMessage("Going Home will stop your broadcast.")
                    .setNegativeButton("Stay live", null)
                    .setPositiveButton("Stop and go Home", (d, w) -> {
                        stopStreaming();
                        openHome();
                    })
                    .show();
        } else {
            openHome();
        }
    }

    private void openHome() {
        android.content.Intent i = new android.content.Intent(this, HomeActivity.class);
        i.addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
                | android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(i);
        finish();
    }

    private void updateButtons() {
        boolean streaming = rtmpStream.isStreaming();
        startBtn.setEnabled(!streaming);
        pauseBtn.setEnabled(streaming);
        pauseBtn.setText(paused ? "Resume" : "Pause");
        stopBtn.setEnabled(streaming);
        switchBtn.setEnabled(!paused);
        findBtn.setEnabled(!streaming && !searching);
    }

    private void setStatus(String text) {
        status.setText(text);
    }

        // ---- Find the server on this Wi-Fi ----

    private void findServer(final boolean onLaunch) {
        if (searching) {
            return;
        }
        searching = true;
        final String saved = hostField.getText().toString().trim();
        setStatus(onLaunch ? "Checking server..." : "Searching for the server...");
        updateButtons();
        executor.execute(() -> {
            String found = null;
            if (!saved.isEmpty() && isServer(saved, 1000)) {
                found = saved;
            }
            if (found == null) {
                found = scanSubnet();
            }
            final String result = found;
            runOnUiThread(() -> serverSearchDone(result, saved));
        });
    }

    private void serverSearchDone(String result, String saved) {
        searching = false;
        if (isFinishing() || isDestroyed()) {
            return;
        }
        if (result != null) {
            if (!result.equals(saved)) {
                hostField.setText(result);
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("host", result).apply();
                setStatus("Server found at " + result);
            } else {
                setStatus("Server OK at " + result);
            }
        } else {
            setStatus("Server not found. Is the PC running and on the same Wi-Fi?");
        }
        updateButtons();
    }

    /** True if this host answers http://host:8080/channels with the channel list. */
    private boolean isServer(String host, int timeoutMs) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, 8080), timeoutMs);
        } catch (IOException e) {
            return false;
        }
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL("http://" + host + ":8080/channels").openConnection();
            c.setConnectTimeout(timeoutMs);
            c.setReadTimeout(1500);
            if (c.getResponseCode() != 200) {
                return false;
            }
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null && sb.length() < 4096) {
                sb.append(line);
            }
            reader.close();
            return sb.toString().contains("\"channels\"");
        } catch (Exception e) {
            return false;
        } finally {
            if (c != null) {
                c.disconnect();
            }
        }
    }

    /** This phone's Wi-Fi network prefix, for example "10.158.91.". Null if not on Wi-Fi. */
    private String localSubnetBase() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback() || !ni.getName().startsWith("wlan")) {
                    continue;
                }
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address) {
                        String ip = a.getHostAddress();
                        return ip.substring(0, ip.lastIndexOf('.') + 1);
                    }
                }
            }
        } catch (Exception e) {
            // fall through
        }
        return null;
    }

    private String scanSubnet() {
        String base = localSubnetBase();
        if (base == null) {
            return null;
        }
        ExecutorService pool = Executors.newFixedThreadPool(48);
        List<Future<String>> futures = new ArrayList<>();
        for (int i = 1; i <= 254; i++) {
            final String host = base + i;
            futures.add(pool.submit((Callable<String>) () -> isServer(host, 400) ? host : null));
        }
        String found = null;
        for (Future<String> f : futures) {
            try {
                String r = f.get();
                if (r != null) {
                    found = r;
                    break;
                }
            } catch (Exception ignored) {
                // keep looking
            }
        }
        pool.shutdownNow();
        return found;
    }
// ---- Silent monitor ----

    private void enterWatchMode() {
        if (watching) {
            return;
        }
        watching = true;

        // Your camera preview moves to the top of the right-hand tile column.
        // The broadcast keeps running.
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) preview.getLayoutParams();
        lp.width = dp(150);
        lp.height = dp(84);
        lp.gravity = Gravity.TOP | Gravity.END;
        lp.setMargins(0, dp(4), 0, 0);
        preview.setLayoutParams(lp);

        tileScroll.setVisibility(View.VISIBLE);
        viewerNote.setVisibility(View.VISIBLE);
        viewerNote.setText("Loading channels...");
        watchBtn.setText("Close viewer");

        player = new ExoPlayer.Builder(this).build();
        player.setVolume(0f); // studio monitor: never plays sound
        player.addListener(new Player.Listener() {
            @Override
            public void onPlayerError(@NonNull PlaybackException error) {
                viewerNote.setText("Playback error: " + error.getErrorCodeName());
            }
        });
        playerView.setPlayer(player);
        playerView.setUseController(false);
        handler.post(refresh);
    }

    private void exitWatchMode() {
        if (!watching) {
            return;
        }
        watching = false;
        handler.removeCallbacks(refresh);
        playerView.setPlayer(null);
        if (player != null) {
            player.release();
            player = null;
        }
        playingId = null;
        playerView.setVisibility(View.GONE);
        tileScroll.setVisibility(View.GONE);
        viewerNote.setVisibility(View.GONE);
        tileRow.removeAllViews();
        liveIds.clear();

        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) preview.getLayoutParams();
        lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
        lp.height = ViewGroup.LayoutParams.MATCH_PARENT;
        lp.gravity = Gravity.NO_GRAVITY;
        lp.setMargins(0, 0, 0, 0);
        preview.setLayoutParams(lp);
        watchBtn.setText("Watch channels");
    }

    /** The channel this phone broadcasts on, taken from the start of the stream key. */
    private String ownChannelId() {
        String key = keyField.getText().toString().trim();
        int q = key.indexOf('?');
        return q > 0 ? key.substring(0, q) : key;
    }

    private void playChannel(String channelId) {
        String host = hostField.getText().toString().trim();
        if (host.isEmpty() || player == null) {
            return;
        }
        playingId = channelId;
        player.setVolume(0f);
        player.setMediaItem(MediaItem.fromUri(
                "http://" + host + ":8888/live/" + channelId + "/index.m3u8"));
        player.prepare();
        player.play();
        playerView.setVisibility(View.VISIBLE);
        viewerNote.setText("Monitoring " + channelId + " (silent)");
        updateTileHighlight();
    }

    private void rebuildTiles() {
        tileRow.removeAllViews();
        String own = ownChannelId();
        for (final String id : liveIds) {
            TextView tile = new TextView(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(84));
            lp.setMargins(dp(4), dp(4), dp(4), dp(4));
            tile.setLayoutParams(lp);
            tile.setGravity(Gravity.CENTER);
            tile.setTextColor(0xFFFFFFFF);
            tile.setTextSize(13);
            tile.setTag(id);
            tile.setText(id + "\n\u25CF LIVE" + (id.equals(own) ? "\n(your stream)" : ""));
            tile.setOnClickListener(v -> playChannel(id));
            tileRow.addView(tile);
        }
        updateTileHighlight();
    }

    private void updateTileHighlight() {
        for (int i = 0; i < tileRow.getChildCount(); i++) {
            View child = tileRow.getChildAt(i);
            boolean selected = child.getTag() != null && child.getTag().equals(playingId);
            child.setBackgroundColor(selected ? 0xFF1976D2 : 0xFF455A64);
        }
    }

    private void fetchChannels() {
        final String host = hostField.getText().toString().trim();
        if (host.isEmpty()) {
            viewerNote.setText("Enter the server address");
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
        if (isFinishing() || isDestroyed() || !watching) {
            return;
        }
        if (err != null) {
            liveIds.clear();
            tileRow.removeAllViews();
            viewerNote.setText("Cannot reach server: " + err + " (tap Find server)");
            return;
        }
        if (!live.equals(liveIds)) {
            liveIds.clear();
            liveIds.addAll(live);
            rebuildTiles();
        }
        if (playingId == null) {
            viewerNote.setText(liveIds.isEmpty() ? "No live channels"
                    : liveIds.size() + " live - tap a tile to monitor it");
        } else if (!liveIds.contains(playingId)) {
            if (player != null) {
                player.stop();
            }
            playerView.setVisibility(View.GONE);
            viewerNote.setText(playingId + " has ended");
            playingId = null;
            updateTileHighlight();
        }
    }

    // ---- SurfaceHolder.Callback ----

    @Override
    public void surfaceCreated(@NonNull SurfaceHolder holder) {
        surfaceReady = true;
        startPreviewIfPossible();
    }

    @Override
    public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int width, int height) {
    }

    @Override
    public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
        surfaceReady = false;
        if (rtmpStream.isOnPreview()) {
            rtmpStream.stopPreview();
        }
    }

    // ---- ConnectChecker (called from background threads) ----

    @Override
    public void onConnectionStarted(@NonNull String url) {
    }

    @Override
    public void onConnectionSuccess() {
        everConnected = true;
        reconnecting = false;
        runOnUiThread(() -> {
            setStatus("LIVE");
            updateButtons();
        });
    }

    @Override
    public void onConnectionFailed(@NonNull String reason) {
        runOnUiThread(() -> {
            if (everConnected && !stoppedByUser && rtmpStream.getStreamClient().reTry(5000L, reason, null)) {
                reconnecting = true;
                setStatus("Reconnecting... (" + reason + ")");
                return;
            }
            reconnecting = false;
            stopKeepAliveService();
            lastError = reason;
            android.util.Log.e("VStream", "Connection failed: " + reason);
            if (rtmpStream.isStreaming()) {
                rtmpStream.stopStream();
            }
            try {
                if (paused) {
                    restoreSources();
                }
            } catch (RuntimeException e) {
                // ignore
            }
            paused = false;
            setStatus("Connection failed: " + reason);
            updateButtons();
        });
    }

    @Override
    public void onNewBitrate(long bitrate) {
    }

    @Override
    public void onDisconnect() {
        runOnUiThread(() -> {
            if (!stoppedByUser && lastError == null && !reconnecting) {
                setStatus("Disconnected");
            }
            updateButtons();
        });
    }

    @Override
    public void onAuthError() {
        runOnUiThread(() -> {
            lastError = "auth";
            setStatus("Rejected by server (check the stream key)");
            updateButtons();
        });
    }

    @Override
    public void onAuthSuccess() {
    }

    @Override
    protected void onStop() {
        super.onStop();
        exitWatchMode();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
        stopKeepAliveService();
        if (rtmpStream.isStreaming()) {
            rtmpStream.stopStream();
        }
        if (rtmpStream.isOnPreview()) {
            rtmpStream.stopPreview();
        }
    }
}