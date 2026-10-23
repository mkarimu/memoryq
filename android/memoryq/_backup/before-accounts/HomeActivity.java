package com.vstream.broadcaster;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Landing page: links to the broadcaster, the channel viewer, and (later) chat. */
public class HomeActivity extends AppCompatActivity {

    private static final String PREFS = "vstream";
    private static final long REFRESH_MS = 5000;

    private TextView statusView;
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

        statusView = findViewById(R.id.homeStatus);
        findViewById(R.id.cardLive).setOnClickListener(
                v -> startActivity(new Intent(this, MainActivity.class)));
        findViewById(R.id.cardWatch).setOnClickListener(
                v -> startActivity(new Intent(this, ViewerActivity.class)));
        findViewById(R.id.cardSettings).setOnClickListener(
                v -> startActivity(new Intent(this, SettingsActivity.class)));
        findViewById(R.id.cardChat).setOnClickListener(
                v -> Toast.makeText(this, "Chat is coming soon", Toast.LENGTH_SHORT).show());
    }

    @Override
    protected void onStart() {
        super.onStart();
        statusView.setText("Checking server...");
        handler.post(refresh);
    }

    @Override
    protected void onStop() {
        super.onStop();
        handler.removeCallbacks(refresh);
    }

    private void checkServer() {
        final String host = getSharedPreferences(PREFS, MODE_PRIVATE)
                .getString("host", "10.158.91.70");
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
            final int result = count;
            runOnUiThread(() -> showStatus(result));
        });
    }

    private void showStatus(int liveCount) {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        if (liveCount >= 0) {
            statusView.setTextColor(0xFF2E7D32);
            statusView.setText("\u25CF Server online      Live channels: " + liveCount);
        } else {
            statusView.setTextColor(0xFFC62828);
            statusView.setText("\u25CF Server offline - open Go Live or Watch and tap Find server");
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }
}