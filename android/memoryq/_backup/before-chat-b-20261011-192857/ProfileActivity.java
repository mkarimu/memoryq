package com.vstream.broadcaster;

import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.View;
import android.widget.RadioGroup;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

/** Personal page: who you are, and the preferences that live on this phone. */
public class ProfileActivity extends AppCompatActivity {

    private static final String PREFS = "vstream";
    private static final int[] COLORS = {0xFF1976D2, 0xFF2E7D32, 0xFF6A1B9A, 0xFFEF6C00, 0xFFC62828, 0xFF00695C};

    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_profile);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);

        findViewById(R.id.profileHome).setOnClickListener(v -> {
            Intent i = new Intent(this, HomeActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(i);
            finish();
        });
        findViewById(R.id.profileSignIn).setOnClickListener(
                v -> startActivity(new Intent(this, SettingsActivity.class)));

        RadioGroup group = findViewById(R.id.themeGroup);
        String theme = prefs.getString("theme", "system");
        group.check("dark".equals(theme) ? R.id.themeDark
                : ("light".equals(theme) ? R.id.themeLight : R.id.themeSystem));
        group.setOnCheckedChangeListener((g, checkedId) -> {
            String mode = checkedId == R.id.themeDark ? "dark"
                    : (checkedId == R.id.themeLight ? "light" : "system");
            if (!mode.equals(prefs.getString("theme", "system"))) {
                prefs.edit().putString("theme", mode).apply();
                AppTheme.apply(mode);
            }
        });
    }

    @Override
    protected void onStart() {
        super.onStart();
        showAccount();
    }

    private void showAccount() {
        TextView avatar = findViewById(R.id.avatar);
        TextView name = findViewById(R.id.profileName);
        TextView role = findViewById(R.id.profileRole);
        TextView channel = findViewById(R.id.profileChannel);
        View signIn = findViewById(R.id.profileSignIn);

        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        if (Account.signedIn(prefs)) {
            String user = prefs.getString("username", "");
            circle.setColor(COLORS[Math.abs(user.hashCode()) % COLORS.length]);
            avatar.setText(user.isEmpty() ? "?" : user.substring(0, 1).toUpperCase());
            name.setText(user);
            role.setText(ChatClient.roleLabel(prefs.getString("role", "")));
            String ch = prefs.getString("claimChannel", "");
            channel.setText(ch.isEmpty() ? "" : "Your channel: " + ch);
            channel.setVisibility(ch.isEmpty() ? View.GONE : View.VISIBLE);
            signIn.setVisibility(View.GONE);
        } else {
            circle.setColor(0xFF9E9E9E);
            avatar.setText("?");
            name.setText("Viewer");
            role.setText("Not signed in - you can watch and read chat");
            channel.setVisibility(View.GONE);
            signIn.setVisibility(View.VISIBLE);
        }
        avatar.setBackground(circle);
    }
}