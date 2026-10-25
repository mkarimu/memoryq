package com.vstream.broadcaster;

import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.text.method.HideReturnsTransformationMethod;
import android.text.method.PasswordTransformationMethod;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/** Server address, account (registered / guest / viewer), the channel pool, and a manual stream key. */
public class SettingsActivity extends AppCompatActivity {

    private static final String PREFS = "vstream";
    private static final Pattern KEY_FORMAT = Pattern.compile("CHANNEL-\\d+\\?token=[0-9a-f]{48}");
    private static final int LOGIN = 0;
    private static final int REGISTER = 1;
    private static final int GUEST = 2;

    private SharedPreferences prefs;
    private EditText hostField;
    private EditText keyField;
    private EditText userField;
    private EditText passField;
    private TextView status;
    private TextView accWho;
    private TextView accMessage;
    private TextView claimText;
    private TextView poolHint;
    private TextView noticeText;
    private Button findBtn;
    private Button signInBtn;
    private Button registerBtn;
    private Button guestBtn;
    private Button releaseBtn;
    private View accountOut;
    private View accountIn;
    private View channelSection;
    private View keySection;
    private View noticeRow;
    private LinearLayout poolList;
    private CheckBox rememberBox;
    private volatile boolean searching = false;
    private final ExecutorService executor = Executors.newCachedThreadPool();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);

        hostField = findViewById(R.id.settingsHost);
        keyField = findViewById(R.id.settingsKey);
        userField = findViewById(R.id.accUser);
        passField = findViewById(R.id.accPass);
        status = findViewById(R.id.settingsStatus);
        accWho = findViewById(R.id.accWho);
        accMessage = findViewById(R.id.accMessage);
        claimText = findViewById(R.id.claimText);
        poolHint = findViewById(R.id.poolHint);
        noticeText = findViewById(R.id.noticeText);
        findBtn = findViewById(R.id.settingsFind);
        signInBtn = findViewById(R.id.accSignIn);
        registerBtn = findViewById(R.id.accRegister);
        guestBtn = findViewById(R.id.accGuest);
        releaseBtn = findViewById(R.id.releaseBtn);
        accountOut = findViewById(R.id.accountOut);
        accountIn = findViewById(R.id.accountIn);
        channelSection = findViewById(R.id.channelSection);
        keySection = findViewById(R.id.keySection);
        noticeRow = findViewById(R.id.noticeRow);
        poolList = findViewById(R.id.poolList);
        CheckBox showKey = findViewById(R.id.showKey);

        hostField.setText(prefs.getString("host", ""));
        keyField.setText(prefs.getString("key", ""));

        showKey.setOnCheckedChangeListener((button, checked) -> {
            keyField.setTransformationMethod(checked
                    ? HideReturnsTransformationMethod.getInstance()
                    : PasswordTransformationMethod.getInstance());
            keyField.setSelection(keyField.getText().length());
        });
        findBtn.setOnClickListener(v -> findServer());
        final View serverSection = findViewById(R.id.serverSection);
        status.setOnClickListener(v -> serverSection.setVisibility(
                serverSection.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE));
        addSignInExtras();
        signInBtn.setOnClickListener(v -> signIn(LOGIN));
        registerBtn.setOnClickListener(v -> signIn(REGISTER));
        guestBtn.setOnClickListener(v -> signIn(GUEST));
        findViewById(R.id.accViewer).setOnClickListener(v -> {
            Toast.makeText(this, "Viewer mode: you can watch and read chat", Toast.LENGTH_SHORT).show();
            finish();
        });
        findViewById(R.id.accSignOut).setOnClickListener(v -> signOut());
        releaseBtn.setOnClickListener(v -> confirmRelease());
        findViewById(R.id.noticeOk).setOnClickListener(v -> dismissNotice());
        findViewById(R.id.settingsSave).setOnClickListener(v -> save());
        findViewById(R.id.settingsHome).setOnClickListener(v -> {
            Intent i = new Intent(this, HomeActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(i);
            finish();
        });
        takeWebSignIn(getIntent());
    }

    /** "Remember me for 30 days" and "Sign in with Google" next to the Sign in button. */
    private void addSignInExtras() {
        if (!(signInBtn.getParent() instanceof LinearLayout)) {
            return;
        }
        LinearLayout box = (LinearLayout) signInBtn.getParent();
        int at = box.indexOfChild(signInBtn);
        rememberBox = new CheckBox(this);
        rememberBox.setText("Remember me for 30 days");
        rememberBox.setChecked(prefs.getBoolean("remember", true));
        rememberBox.setOnCheckedChangeListener((b, on) -> prefs.edit().putBoolean("remember", on).apply());
        box.addView(rememberBox, at);
        Button google = new Button(this);
        google.setText("Sign in with Google");
        google.setAllCaps(false);
        google.setOnClickListener(v -> openWebSignIn());
        box.addView(google, box.indexOfChild(signInBtn) + 1);
    }

    /** Google (and any Memoryq sign-in) happens on memoryq.com in the browser; it comes back with a one-time code. */
    private void openWebSignIn() {
        String h = host();
        String base = h.isEmpty() ? "https://memoryq.com" : Api.base(h);
        String url = base + "/login?app=memoryq-app&return=" + Uri.encode("memoryq-app://signin");
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
            message("Sign in on the page that opened - the app comes back by itself.");
        } catch (Exception e) {
            message("No web browser was found on this phone.");
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        takeWebSignIn(intent);
    }

    /** memoryq-app://signin#mq=CODE: swaps the one-time code for this app's own sign-in. */
    private void takeWebSignIn(Intent intent) {
        Uri data = intent == null ? null : intent.getData();
        if (data == null || !"memoryq-app".equals(data.getScheme())) {
            return;
        }
        String frag = data.getEncodedFragment();
        String code = null;
        if (frag != null) {
            for (String part : frag.split("&")) {
                if (part.startsWith("mq=")) {
                    code = part.substring(3);
                }
            }
        }
        intent.setData(null);
        if (code == null || code.isEmpty()) {
            return;
        }
        final String theCode = code;
        setBusy(true);
        message("Signing in...");
        executor.execute(() -> {
            String host = host();
            if (host.isEmpty()) {
                String found = ServerFinder.ensure(prefs, true);
                host = found == null ? "memoryq.com" : found;
                prefs.edit().putString("host", host).apply();
            }
            Api.Result r = Api.post(host, "/api/auth/exchange", Api.form("code", theCode), null);
            JSONObject me = r.json();
            ui(() -> {
                setBusy(false);
                if (r.code == 200 && !me.optString("token", "").isEmpty()) {
                    Account.apply(prefs, me, me.optString("token"));
                    message("");
                    render();
                    loadPool();
                    Toast.makeText(this, "Signed in as " + me.optString("username"), Toast.LENGTH_SHORT).show();
                } else {
                    message(r.code == 401 ? "That sign-in link has expired - press Sign in with Google again." : r.error());
                }
            });
        });
    }

    @Override
    protected void onStart() {
        super.onStart();
        render();
        showServer();
        if (prefs.getString("host", "").isEmpty()) {
            autoFind();
        } else {
            refreshAccount();
        }
    }

    /** One quiet line about the server; tapping it opens the advanced server settings. */
    private void showServer() {
        String h = prefs.getString("host", "");
        status.setText(h.isEmpty() ? "Server: connecting...  (tap for options)"
                : "Server: " + h + " (found automatically)  \u00b7  tap to change");
    }

    private void autoFind() {
        if (searching) {
            return;
        }
        searching = true;
        executor.execute(() -> {
            final String found = ServerFinder.ensure(prefs, true);
            ui(() -> {
                searching = false;
                if (found != null) {
                    hostField.setText(found);
                    showServer();
                    refreshAccount();
                } else {
                    status.setText("Cannot reach the server - check the internet connection  (tap for options)");
                }
            });
        });
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void ui(Runnable r) {
        runOnUiThread(() -> {
            if (!isFinishing() && !isDestroyed()) {
                r.run();
            }
        });
    }

    private String host() {
        String h = hostField.getText().toString().trim();
        return h.isEmpty() ? prefs.getString("host", "") : h;
    }

    private void message(String text) {
        accMessage.setText(text);
    }

    private void setBusy(boolean busy) {
        signInBtn.setEnabled(!busy);
        registerBtn.setEnabled(!busy);
        guestBtn.setEnabled(!busy);
    }

    // ---- Showing the current state ----

    private void render() {
        boolean signed = Account.signedIn(prefs);
        String role = prefs.getString("role", "");
        boolean admin = "ADMIN".equals(role);
        boolean fixed = "BROADCASTER".equals(role);
        boolean guest = "GUEST".equals(role);

        accountOut.setVisibility(signed ? View.GONE : View.VISIBLE);
        accountIn.setVisibility(signed ? View.VISIBLE : View.GONE);
        keySection.setVisibility(signed ? View.GONE : View.VISIBLE);
        channelSection.setVisibility(signed ? View.VISIBLE : View.GONE);
        if (!signed) {
            keyField.setText(prefs.getString("key", ""));
            return;
        }
        accWho.setText("Signed in as " + prefs.getString("username", "")
                + " (" + ChatClient.roleLabel(role) + ")");

        String notice = prefs.getString("notice", "");
        noticeRow.setVisibility(notice.isEmpty() ? View.GONE : View.VISIBLE);
        noticeText.setText(notice);

        String channel = prefs.getString("claimChannel", "");
        int hours = prefs.getInt("hoursLeft", -1);
        boolean warn = prefs.getBoolean("warning", false);
        releaseBtn.setVisibility(!channel.isEmpty() && !fixed ? View.VISIBLE : View.GONE);
        boolean showPool = !admin && !fixed && !guest;
        poolList.setVisibility(showPool ? View.VISIBLE : View.GONE);
        poolHint.setVisibility(showPool ? View.VISIBLE : View.GONE);

        if (admin) {
            claimText.setText("The admin account is managed in the web admin page. Sign in with another account to broadcast.");
        } else if (guest) {
            claimText.setText("Guest account: you can watch and chat. To broadcast, sign out and create a registered account.");
        } else if (fixed) {
            claimText.setText(channel.isEmpty()
                    ? "No channel is assigned to this account yet."
                    : "Your channel: " + channel + " (assigned by the admin).\nTell your audience to look for "
                            + channel + " in the app.");
        } else if (!channel.isEmpty()) {
            claimText.setText("Your channel: " + channel + (hours >= 0 ? " - " + hours + " h left" : "")
                    + "\nTell your audience to look for " + channel + " in the app."
                    + (warn ? "\nBroadcast on it soon or it returns to the pool." : ""));
        } else {
            claimText.setText("You have no channel yet. Pick a free one below.");
        }
    }

    private void showPool(JSONObject data) {
        poolList.removeAllViews();
        boolean hasClaim = !prefs.getString("claimChannel", "").isEmpty();
        poolHint.setText(hasClaim
                ? "Release your channel to pick a different one."
                : "Pick a free channel. A channel not used for " + data.optLong("ttlHours", 48)
                        + " hours returns to the pool.");
        JSONArray pool = data.optJSONArray("pool");
        if (pool == null) {
            return;
        }
        for (int i = 0; i < pool.length(); i++) {
            JSONObject o = pool.optJSONObject(i);
            if (o == null) {
                continue;
            }
            final String id = o.optString("channelId");
            String st = o.optString("status");
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(8), dp(4), dp(8), dp(4));
            TextView label = new TextView(this);
            label.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            label.setTextSize(16);
            if ("YOURS".equals(st)) {
                label.setText(id + "     Yours" + (o.has("hoursLeft") ? " - " + o.optInt("hoursLeft") + " h left" : ""));
                row.setBackgroundColor(0x331976D2);
            } else if ("TAKEN".equals(st)) {
                label.setText(id + "     Taken");
                label.setAlpha(0.55f);
            } else {
                label.setText(id + "     Free");
            }
            row.addView(label);
            if ("FREE".equals(st)) {
                Button pick = new Button(this);
                pick.setText("Pick");
                pick.setEnabled(!hasClaim);
                pick.setOnClickListener(v -> claim(id));
                row.addView(pick);
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, dp(2), 0, dp(2));
            poolList.addView(row, lp);
        }
    }

    // ---- Talking to the server ----

    private void refreshAccount() {
        if (!Account.signedIn(prefs)) {
            render();
            return;
        }
        final String host = host();
        final String session = prefs.getString("session", "");
        if (host.isEmpty()) {
            return;
        }
        executor.execute(() -> {
            Api.Result r = Api.get(host, "/api/me", session);
            if (r.code == 200) {
                Account.apply(prefs, r.json(), null);
            } else if (r.code == 401) {
                Account.clear(prefs);
            }
            ui(() -> {
                message(r.code == 0 ? "Cannot reach the server" : "");
                render();
                loadPool();
            });
        });
    }

    private void loadPool() {
        if (!Account.signedIn(prefs)) {
            return;
        }
        String role = prefs.getString("role", "");
        if ("ADMIN".equals(role) || "BROADCASTER".equals(role) || "GUEST".equals(role)) {
            return;
        }
        final String host = host();
        final String session = prefs.getString("session", "");
        if (host.isEmpty()) {
            return;
        }
        executor.execute(() -> {
            Api.Result r = Api.get(host, "/api/pool", session);
            if (r.code == 401) {
                Account.clear(prefs);
            }
            ui(() -> {
                if (r.code == 200) {
                    showPool(r.json());
                } else if (r.code == 401) {
                    render();
                    message("Your sign-in ended. Sign in again.");
                } else {
                    poolHint.setText(r.code == 0 ? "Cannot reach the server" : r.error());
                }
            });
        });
    }

    private void signIn(final int mode) {
        final String user = userField.getText().toString().trim();
        final String pass = passField.getText().toString();
        final String typed = host();
        if (user.isEmpty() || pass.isEmpty()) {
            message("Enter a username and a password");
            return;
        }
        setBusy(true);
        message(mode == LOGIN ? "Signing in..." : (mode == GUEST ? "Joining as a guest..." : "Creating account..."));
        executor.execute(() -> {
            String host = typed;
            if (host.isEmpty() || !ServerFinder.isServer(host, 1500)) {
                ui(() -> message("Connecting to the server..."));
                String found = ServerFinder.ensure(prefs, true);
                if (found == null) {
                    ui(() -> {
                        setBusy(false);
                        message("Cannot reach memoryq.com - check the internet connection (or set the server address below).");
                    });
                    return;
                }
                host = found;
                final String shown = found;
                ui(() -> {
                    hostField.setText(shown);
                    showServer();
                });
            }
            if (mode == REGISTER) {
                final String regHost = host;
                ui(() -> {
                    setBusy(false);
                    message("");
                    EmailVerify.register(this, prefs, regHost, user, pass, () -> {
                        passField.setText("");
                        render();
                        loadPool();
                    });
                });
                return;
            }
            String remember = rememberBox == null || rememberBox.isChecked() ? "1" : "0";
            String body = mode == GUEST
                    ? Api.form("username", user, "password", pass, "type", "guest")
                    : Api.form("username", user, "password", pass, "remember", remember);
            Api.Result r = Api.post(host, mode == LOGIN ? "/api/login" : "/api/register", body, null);
            JSONObject me = r.json();
            if (r.code == 200 && !me.optString("token", "").isEmpty()) {
                prefs.edit().putString("host", host).apply();
                Account.apply(prefs, me, me.optString("token"));
                ui(() -> {
                    passField.setText("");
                    message("");
                    setBusy(false);
                    render();
                    loadPool();
                });
            } else {
                final String err = r.error();
                ui(() -> {
                    setBusy(false);
                    message(err);
                });
            }
        });
    }

    private void signOut() {
        final String host = host();
        final String session = prefs.getString("session", "");
        executor.execute(() -> {
            if (!host.isEmpty()) {
                Api.post(host, "/api/logout", "", session);
            }
            Account.clear(prefs);
            ui(() -> {
                poolList.removeAllViews();
                message("");
                render();
            });
        });
    }

    private void claim(final String channelId) {
        final String host = host();
        final String session = prefs.getString("session", "");
        message("");
        executor.execute(() -> {
            Api.Result r = Api.post(host, "/api/claim", Api.form("channel", channelId), session);
            if (r.code == 200) {
                ui(() -> Toast.makeText(this, "You are on " + channelId, Toast.LENGTH_SHORT).show());
                refreshAccount();
            } else {
                if (r.code == 401) {
                    Account.clear(prefs);
                }
                final String err = r.error();
                ui(() -> {
                    message(err);
                    render();
                    loadPool();
                });
            }
        });
    }

    private void confirmRelease() {
        String channel = prefs.getString("claimChannel", "");
        new AlertDialog.Builder(this)
                .setTitle("Release " + channel + "?")
                .setMessage("Your key stops working and anyone can pick this channel.")
                .setNegativeButton("Keep it", null)
                .setPositiveButton("Release", (d, w) -> release())
                .show();
    }

    private void release() {
        final String host = host();
        final String session = prefs.getString("session", "");
        executor.execute(() -> {
            Api.Result r = Api.post(host, "/api/release", "", session);
            if (r.code != 200 && r.code != 409) {
                final String err = r.error();
                ui(() -> message(err));
            }
            refreshAccount();
        });
    }

    private void dismissNotice() {
        final String host = host();
        final String session = prefs.getString("session", "");
        prefs.edit().remove("notice").apply();
        render();
        executor.execute(() -> Api.post(host, "/api/ack", "", session));
    }

    // ---- Saving and finding the server ----

    private void save() {
        String host = hostField.getText().toString().trim();
        SharedPreferences.Editor e = prefs.edit().putString("host", host);
        String key = "";
        if (keySection.getVisibility() == View.VISIBLE) {
            key = keyField.getText().toString().trim();
            e.putString("key", key).putBoolean("keyManaged", false);
        }
        e.apply();
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
            ui(() -> {
                searching = false;
                findBtn.setEnabled(true);
                if (result != null) {
                    hostField.setText(result);
                    prefs.edit().putString("host", result).apply();
                    showServer();
                    refreshAccount();
                } else {
                    status.setText("Cannot reach memoryq.com - check the internet connection");
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