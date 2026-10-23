package com.vstream.broadcaster;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.pedro.common.ConnectChecker;
import com.pedro.library.rtmp.RtmpStream;
import com.pedro.encoder.input.sources.audio.MicrophoneSource;
import com.pedro.encoder.input.sources.audio.NoAudioSource;
import com.pedro.encoder.input.sources.video.Camera2Source;
import com.pedro.encoder.input.sources.video.NoVideoSource;

public class MainActivity extends AppCompatActivity
        implements ConnectChecker, SurfaceHolder.Callback {

    private static final int REQUEST_PERMISSIONS = 1;
    private static final String PREFS = "vstream";

    private RtmpStream rtmpStream;
    private SurfaceView preview;
    private TextView status;
    private EditText hostField;
    private EditText keyField;
    private Button startBtn;
    private Button pauseBtn;
    private Button stopBtn;
    private Button switchBtn;

    private boolean surfaceReady = false;
    private boolean prepared = false;
    private boolean paused = false;
    private volatile boolean stoppedByUser = false;
    private volatile String lastError = null;

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

        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        hostField.setText(prefs.getString("host", "10.158.91.70"));
        keyField.setText(prefs.getString("key", ""));

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
        findViewById(R.id.watchBtn).setOnClickListener(v -> {
            Intent intent = new Intent(MainActivity.this, WatchActivity.class);
            intent.putExtra("serverHost", hostField.getText().toString().trim());
            startActivity(intent);
        });
        findViewById(R.id.watchBtn).setOnClickListener(v -> {
            Intent intent = new Intent(MainActivity.this, WatchActivity.class);
            intent.putExtra("serverHost", hostField.getText().toString().trim());
            startActivity(intent);
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

    // ---- Buttons ----

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

    /** Puts the camera and microphone back after a pause. The camera resets to the default one. */
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
    protected void onDestroy() {
        super.onDestroy();
        if (rtmpStream.isStreaming()) {
            rtmpStream.stopStream();
        }
        if (rtmpStream.isOnPreview()) {
            rtmpStream.stopPreview();
        }
    }
}