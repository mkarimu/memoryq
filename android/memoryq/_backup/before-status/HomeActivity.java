package com.vstream.broadcaster;

import android.content.Intent;
import android.os.Bundle;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Landing page: links to the broadcaster, the channel viewer, and (later) chat. */
public class HomeActivity extends AppCompatActivity {

    private static final String PREFS = "vstream";

    private TextView statusView;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_home);

        statusView = findViewById(R.id.homeStatus);
        findViewById(R.id.cardLive).setOnClickListener(
                v -> startActivity(new Intent(this, MainActivity.class)));
        findViewById(R.id.cardWatch).setOnClickListener(
                v -> startActivity(new Intent(this, ViewerActivity.class)));
        findViewById(R.id.cardChat).setOnClickListener(
                v -> Toast.makeText(this, "Chat is coming soon", Toast.LENGTH_SHORT).show());
    }

    @Override
    protected void onStart() {
        super.onStart();
        checkServer();
    }

    private void checkServer() {
        final String host = getSharedPreferences(PREFS, MODE_PRIVATE)
                .getString("host", "10.158.91.70");
        statusView.setText("Checking server...");
        executor.execute(() -> {
            final boolean ok = ServerFinder.isServer(host, 1500);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                statusView.setText(ok
                        ? "Server online at " + host
                        : "Server not reachable at " + host
                                + " - open Go Live or Watch and tap Find server");
            });
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }
}