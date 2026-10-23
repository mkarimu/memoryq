package com.vstream.broadcaster;

import android.Manifest;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ListView;
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

    private boolean surfaceReady = false;
    private boolean prepared = false;
    private boolean paused = false;
    private volatile boolean stoppedByUser = false;
    private volatile String lastError = null;

    // ---- viewer (side panel) ----
    private PlayerView playerView;
    private ListView channelList;
    private TextView viewerNote;
    private ExoPlayer player;
    private ArrayAdapter<String> tileAdapter;
    private final List<String> tiles = new ArrayList<>();
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
        status = findViewById(R.id.status);
        hostField = findViewById(R.id.host);
        keyField = findViewById(R.id.key);
        startBtn = findViewById(R.id.startBtn);
        pauseBtn = findViewById(R.id.pauseBtn);
        stopBtn = findViewById(R.id.stopBtn);
        switchBtn = findViewById(R.id.switchBtn);
        watchBtn = findViewById(R.id.watchBtn);
        playerView = findViewById(R.id.player);
        channelList = findViewById(R.id.channelList);
        viewerNote = findViewById(R.id.viewerNote);

        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        hostField.setText(prefs.getString("host", "10.158.91.70"));
        keyField.setText(prefs.getString("key", ""));

        tileAdapter = new ArrayAdapter<>(this, R.layout.item_channel, tiles);
        channelList.setAdapter(tileAdapter);
        channelList.setOnItemClickListener((parent, view, position, id) -> {
            if (position >= 0 && position < liveIds.size()) {
                playChannel(liveIds.get(position));
            }
        });

        rtmpStream = new RtmpStream(this, this);
        preview.getHolder().addCallback(this);

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
        watchBtn.setOnClickListener(v -> {
            if (watching) {
                exitWatchMode();
            } else {
                enterWatchMode();
            }
        });

        if (!hasPermissions()) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO},
                    REQUEST_PERMISSIONS);
            setStatus("Allow camera and microphone");
        } else {
            setStatus("Ready");
        }
        updateButtons();
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

    private void updateButtons() {
        boolean streaming = rtmpStream.isStreaming();
        startBtn.setEnabled(!streaming);
        pauseBtn.setEnabled(streaming);
        pauseBtn.setText(paused ? "Resume" : "Pause");
        stopBtn.setEnabled(streaming);
        switchBtn.setEnabled(!paused);
    }

    private void setStatus(String text) {
        status.setText(text);
    }

    // ---- Viewer side panel ----

    private void enterWatchMode() {
        if (watching) {
            return;
        }
        watching = true;

        // Shrink the camera preview into the top-left corner. The broadcast keeps running.
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) preview.getLayoutParams();
        lp.width = dp(120);
        lp.height = dp(68);
        lp.gravity = Gravity.TOP | Gravity.START;
        preview.setLayoutParams(lp);

        channelList.setVisibility(View.VISIBLE);
        viewerNote.setVisibility(View.VISIBLE);
        viewerNote.setText("Loading channels...");
        watchBtn.setText("Close viewer");

        player = new ExoPlayer.Builder(this).build();
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
        channelList.setVisibility(View.GONE);
        viewerNote.setVisibility(View.GONE);
        tiles.clear();
        liveIds.clear();
        tileAdapter.notifyDataSetChanged();

        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) preview.getLayoutParams();
        lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
        lp.height = ViewGroup.LayoutParams.MATCH_PARENT;
        lp.gravity = Gravity.NO_GRAVITY;
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
        boolean own = channelId.equals(ownChannelId());
        playingId = channelId;
        player.setVolume(own ? 0f : 1f);
        player.setMediaItem(MediaItem.fromUri(
                "http://" + host + ":8888/live/" + channelId + "/index.m3u8"));
        player.prepare();
        player.play();
        playerView.setVisibility(View.VISIBLE);
        viewerNote.setText("Watching " + channelId + (own ? " (your own stream, muted)" : ""));
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
        tiles.clear();
        liveIds.clear();
        if (err != null) {
            tileAdapter.notifyDataSetChanged();
            viewerNote.setText("Cannot reach server: " + err);
            return;
        }
        for (String id : live) {
            liveIds.add(id);
            tiles.add(id + "\n\u25CF LIVE");
        }
        tileAdapter.notifyDataSetChanged();

        if (playingId == null) {
            viewerNote.setText(liveIds.isEmpty() ? "No live channels"
                    : liveIds.size() + " live - tap one to watch");
        } else if (!liveIds.contains(playingId)) {
            if (player != null) {
                player.stop();
            }
            playerView.setVisibility(View.GONE);
            viewerNote.setText(playingId + " has ended");
            playingId = null;
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
        runOnUiThread(() -> {
            setStatus("LIVE");
            updateButtons();
        });
    }

    @Override
    public void onConnectionFailed(@NonNull String reason) {
        runOnUiThread(() -> {
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
            if (!stoppedByUser && lastError == null) {
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
        if (rtmpStream.isStreaming()) {
            rtmpStream.stopStream();
        }
        if (rtmpStream.isOnPreview()) {
            rtmpStream.stopPreview();
        }
    }
}