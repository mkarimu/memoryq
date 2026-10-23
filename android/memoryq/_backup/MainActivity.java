package com.vstream.broadcaster;

import android.Manifest;
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

public class MainActivity extends AppCompatActivity
        implements ConnectChecker, SurfaceHolder.Callback {

    private static final int REQUEST_PERMISSIONS = 1;
    private static final String PREFS = "vstream";

    private RtmpStream rtmpStream;
    private SurfaceView preview;
    private TextView status;
    private EditText hostField;
    private EditText keyField;
    private Button startStop;

    private boolean surfaceReady = false;
    private boolean prepared = false;
    private volatile String lastError = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        preview = findViewById(R.id.preview);
        status = findViewById(R.id.status);
        hostField = findViewById(R.id.host);
        keyField = findViewById(R.id.key);
        startStop = findViewById(R.id.startStop);

        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        hostField.setText(prefs.getString("host", "10.158.91.70"));
        keyField.setText(prefs.getString("key", ""));

        rtmpStream = new RtmpStream(this, this);
        preview.getHolder().addCallback(this);

        startStop.setOnClickListener(v -> {
            if (rtmpStream.isStreaming()) {
                stopStreaming();
            } else {
                startStreaming();
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
        setStatus("Connecting to " + host + " ...");
        rtmpStream.startStream("rtmp://" + host + ":1935/live/" + key);
        startStop.setText("Stop");
    }

    private void stopStreaming() {
        if (rtmpStream.isStreaming()) {
            rtmpStream.stopStream();
        }
        startStop.setText("Start");
        setStatus("Stopped");
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
        runOnUiThread(() -> setStatus("LIVE"));
    }

    @Override
    public void onConnectionFailed(@NonNull String reason) {
        runOnUiThread(() -> {
            if (rtmpStream.isStreaming()) {
                rtmpStream.stopStream();
            }
            startStop.setText("Start");
            lastError = reason;
            android.util.Log.e("VStream", "Connection failed: " + reason);
            setStatus("Connection failed: " + reason);
        });
    }

    @Override
    public void onNewBitrate(long bitrate) {
    }

    @Override
    public void onDisconnect() {
        runOnUiThread(() -> {
            startStop.setText("Start");
            if (lastError == null) {
                setStatus("Disconnected");
            }
        });
    }

    @Override
    public void onAuthError() {
        runOnUiThread(() -> {
            startStop.setText("Start");
            lastError = "auth";
            setStatus("Rejected by server (check the stream key)");
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