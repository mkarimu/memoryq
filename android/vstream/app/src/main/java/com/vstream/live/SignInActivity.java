package com.vstream.live;

import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Sign in to Memoryq (or create an account, confirmed with a code sent by e-mail). */
public class SignInActivity extends AppCompatActivity {

    private final ExecutorService pool = Executors.newSingleThreadExecutor();
    private EditText user;
    private EditText pass;
    private TextView error;
    private Button signIn;
    private Button create;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Ui.BG);
        scroll.setFillViewport(true);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_VERTICAL);
        int pad = Ui.dp(this, 24);
        col.setPadding(pad, pad, pad, pad);
        scroll.addView(col);

        col.addView(Ui.logo(this));
        // testers (while the Play Store test runs): go live at once, no account
        quick = Ui.button(this, "Try Vstream now - no account needed", Ui.LIVE);
        quick.setVisibility(View.GONE);
        quick.setOnClickListener(v -> quickStart());
        Ui.add(col, quick, 16);
        quickNote = Ui.note(this, "For testers: you get your own tester login and go live straight away.");
        quickNote.setVisibility(View.GONE);
        Ui.add(col, quickNote, 4);
        String qhost = Prefs.host(this);
        pool.execute(() -> {
            Api.Result r = Api.get(qhost, "/api/vstream/quick", null);
            boolean on = r.code == 200 && r.json().optBoolean("on", false);
            runOnUiThread(() -> {
                quick.setVisibility(on ? View.VISIBLE : View.GONE);
                quickNote.setVisibility(on ? View.VISIBLE : View.GONE);
            });
        });
        Ui.add(col, Ui.note(this, "Sign in with your Memoryq account. We give you a free live channel and your stream key automatically."), 14);
        user = Ui.field(this, "Username", false);
        user.setText(Prefs.of(this).getString("username", ""));
        pass = Ui.field(this, "Password", true);
        Ui.add(col, user, 22);
        Ui.add(col, pass, 10);
        error = Ui.text(this, "", 14, Ui.LIVE, false);
        Ui.add(col, error, 8);
        signIn = Ui.button(this, "Sign in", Ui.ACCENT);
        signIn.setOnClickListener(v -> signIn());
        Ui.add(col, signIn, 10);
        create = Ui.button(this, "Create a free account", Ui.PANEL);
        create.setOnClickListener(v -> askEmail());
        Ui.add(col, create, 10);
        Button forgot = Ui.flat(this, "Forgot your password?");
        forgot.setOnClickListener(v -> Ui.openWeb(this, Api.base(Prefs.host(this)) + "/me"));
        Ui.add(col, forgot, 8);

        Ui.add(col, Ui.section(this, "No Memoryq account needed"), 10);
        Button own = Ui.flat(this, "Stream to my own RTMP server instead  \u203A");
        own.setOnClickListener(v -> {
            Prefs.of(this).edit().putBoolean("ownOn", true).apply();
            Intent i = new Intent(this, SettingsActivity.class);
            i.putExtra(SettingsActivity.EXTRA_FIRST_OWN, true);
            startActivity(i);
            finish();
        });
        Ui.add(col, own, 0);
        Ui.add(col, Ui.note(this, "By signing in you accept the Vstream terms and policies."), 18);
        Button legal = Ui.flat(this, "Terms and policies");
        legal.setOnClickListener(v -> Ui.openDoc(this, ""));
        Ui.add(col, legal, 0);
        setContentView(scroll);
        Ui.edgeSafe(this);
    }

    private Button quick;
    private TextView quickNote;

    /** The tester login made for this phone before (if any), else a new one. */
    private void quickStart() {
        error.setText("");
        quick.setEnabled(false);
        quick.setText("Please wait...");
        String host = Prefs.host(this);
        String qu = Prefs.of(this).getString("quickUser", "");
        String qp = Prefs.of(this).getString("quickPass", "");
        pool.execute(() -> {
            Api.Result r = null;
            if (!qu.isEmpty() && !qp.isEmpty()) {
                r = Api.post(host, "/api/login", Api.form("username", qu, "password", qp), null);
            }
            if (r == null || r.code != 200) {
                r = Api.post(host, "/api/vstream/quick", "", null);
                if (r.code == 200) {
                    Prefs.of(this).edit().putString("quickUser", r.json().optString("username"))
                            .putString("quickPass", r.json().optString("password")).apply();
                }
            }
            final Api.Result res = r;
            runOnUiThread(() -> {
                quick.setEnabled(true);
                quick.setText("Try Vstream now - no account needed");
                if (res.code == 200 && !res.json().optString("token", "").isEmpty()) {
                    done(res.json());
                } else {
                    error.setText(res.error());
                }
            });
        });
    }

    private boolean fields() {
        if (user.getText().toString().trim().isEmpty() || pass.getText().toString().isEmpty()) {
            error.setText("Enter a username and password.");
            return false;
        }
        return true;
    }

    private void busy(boolean b) {
        signIn.setEnabled(!b);
        create.setEnabled(!b);
        signIn.setText(b ? "Please wait..." : "Sign in");
    }

    private void signIn() {
        if (!fields()) {
            return;
        }
        error.setText("");
        busy(true);
        String host = Prefs.host(this);
        String body = Api.form("username", user.getText().toString().trim(), "password", pass.getText().toString());
        pool.execute(() -> {
            Api.Result r = Api.post(host, "/api/login", body, null);
            runOnUiThread(() -> {
                busy(false);
                if (r.code == 200 && !r.json().optString("token", "").isEmpty()) {
                    done(r.json());
                } else {
                    error.setText(r.error());
                }
            });
        });
    }

    private void done(JSONObject j) {
        String role = j.optString("role", "");
        if ("GUEST".equalsIgnoreCase(role) || "ADMIN".equalsIgnoreCase(role)) {
            error.setText("This account cannot broadcast. Sign in with a registered (not guest or admin) account.");
            return;
        }
        Prefs.of(this).edit().putString("session", j.optString("token")).putString("username", j.optString("username", ""))
                .putBoolean("ownOn", false).remove("key").remove("channel").apply();
        startActivity(new Intent(this, LiveActivity.class));
        finish();
    }

    private void askEmail() {
        if (!fields()) {
            error.setText("Choose a username and password above, then tap Create.");
            return;
        }
        error.setText("");
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(this, 20);
        box.setPadding(pad, Ui.dp(this, 8), pad, 0);
        EditText mail = Ui.field(this, "you@example.com", false);
        mail.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        box.addView(Ui.note(this, "We send a 6-digit code to confirm your e-mail address."));
        Ui.add(box, mail, 10);
        TextView err = Ui.text(this, "", 13, Ui.LIVE, false);
        Ui.add(box, err, 6);
        AlertDialog d = new AlertDialog.Builder(this).setTitle("Create your account").setView(box)
                .setNegativeButton("Cancel", null).setPositiveButton("Send code", null).create();
        d.setOnShowListener(x -> d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String email = mail.getText().toString().trim();
            if (!email.contains("@")) {
                err.setText("Enter your e-mail address.");
                return;
            }
            String name = user.getText().toString().trim();
            String body = Api.form("username", name, "password", pass.getText().toString(), "email", email, "type", "chatter");
            String host = Prefs.host(this);
            pool.execute(() -> {
                Api.Result r = Api.post(host, "/api/register", body, null);
                JSONObject j = r.json();
                runOnUiThread(() -> {
                    if (r.code == 200 && j.optBoolean("verify", false)) {
                        d.dismiss();
                        askCode(j.optString("username", name), j.optString("email", email));
                    } else if (r.code == 200 && !j.optString("token", "").isEmpty()) {
                        d.dismiss();
                        done(j);
                    } else {
                        err.setText(r.error());
                    }
                });
            });
        }));
        d.show();
    }

    private void askCode(String name, String masked) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(this, 20);
        box.setPadding(pad, Ui.dp(this, 8), pad, 0);
        box.addView(Ui.note(this, "We sent a 6-digit code to " + masked + ". Type it here (check the spam folder too)."));
        EditText code = Ui.field(this, "123456", false);
        code.setInputType(InputType.TYPE_CLASS_NUMBER);
        Ui.add(box, code, 10);
        TextView err = Ui.text(this, "", 13, Ui.LIVE, false);
        Ui.add(box, err, 6);
        AlertDialog d = new AlertDialog.Builder(this).setTitle("Check your e-mail").setView(box).setCancelable(false)
                .setNegativeButton("Cancel", null).setNeutralButton("Send again", null).setPositiveButton("Confirm", null).create();
        String host = Prefs.host(this);
        d.setOnShowListener(x -> {
            d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                String body = Api.form("username", name, "code", code.getText().toString().trim());
                pool.execute(() -> {
                    Api.Result r = Api.post(host, "/api/register/verify", body, null);
                    runOnUiThread(() -> {
                        if (r.code == 200 && !r.json().optString("token", "").isEmpty()) {
                            d.dismiss();
                            Ui.toast(this, "Welcome to Memoryq!");
                            done(r.json());
                        } else {
                            err.setText(r.error());
                        }
                    });
                });
            });
            d.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> pool.execute(() -> {
                Api.Result r = Api.post(host, "/api/register/resend", Api.form("username", name), null);
                runOnUiThread(() -> err.setText(r.code == 200 ? "A new code is on its way." : r.error()));
            }));
        });
        d.show();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        pool.shutdownNow();
    }
}