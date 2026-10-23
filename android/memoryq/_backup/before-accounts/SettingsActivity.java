package com.vstream.broadcaster;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.method.HideReturnsTransformationMethod;
import android.text.method.PasswordTransformationMethod;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/** Server address and stream key. The key is masked unless "Show key" is ticked. */
public class SettingsActivity extends AppCompatActivity {

    private static final String PREFS = "vstream";
    private static final Pattern KEY_FORMAT = Pattern.compile("CHANNEL-\\d+\\?token=[0-9a-f]{48}");

    private EditText hostField;
    private EditText keyField;
    private TextView status;
    private Button findBtn;
    private volatile boolean searching = false;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        hostField = findViewById(R.id.settingsHost);
        keyField = findViewById(R.id.settingsKey);
        status = findViewById(R.id.settingsStatus);
        findBtn = findViewById(R.id.settingsFind);
        CheckBox showKey = findViewById(R.id.showKey);

        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        hostField.setText(prefs.getString("host", ""));
        keyField.setText(prefs.getString("key", ""));

        showKey.setOnCheckedChangeListener((button, checked) -> {
            keyField.setTransformationMethod(checked
                    ? HideReturnsTransformationMethod.getInstance()
                    : PasswordTransformationMethod.getInstance());
            keyField.setSelection(keyField.getText().length());
        });

        findBtn.setOnClickListener(v -> findServer());

        findViewById(R.id.settingsSave).setOnClickListener(v -> save());

        findViewById(R.id.settingsHome).setOnClickListener(v -> {
            Intent i = new Intent(this, HomeActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(i);
            finish();
        });
    }

    private void save() {
        String host = hostField.getText().toString().trim();
        String key = keyField.getText().toString().trim();
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString("host", host).putString("key", key).apply();
        if (!key.isEmpty() && !KEY_FORMAT.matcher(key).matches()) {
            Toast.makeText(this,
                    "Saved. The key does not look like CHANNEL-001?token=... - check it",
                    Toast.LENGTH_LONG).show();
        } else {
            Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show();
        }
        finish();
    }

    private void findServer() {
        if (searching) {
            return;
        }
        searching = true;
        findBtn.setEnabled(false);
        status.setText("Searching for the server...");
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
                    status.setText("Server found");
                } else {
                    status.setText("Server not found. Is the PC running and on the same Wi-Fi?");
                }
            });
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }
}