package com.vstream.broadcaster;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Email addresses: a new account is created only after the 6-digit code from the email is typed in,
 * and people with an older account are asked (once each time the app starts) to add and confirm one.
 */
final class EmailVerify {

    private static final ExecutorService POOL = Executors.newSingleThreadExecutor();
    private static boolean askedThisRun = false;

    private EmailVerify() {
    }

    // ---- New accounts ----

    /** Asks for the email address, sends the code, then asks for the code. onDone runs once signed in. */
    static void register(Activity a, SharedPreferences prefs, String host, String user, String pass, Runnable onDone) {
        askEmail(a, "Create your account",
                "Your email address. We send you a 6-digit code to confirm it - the account is ready once you type it in.",
                prefs.getString("pendingEmail", ""), (email, dialog, error) -> {
                    String body = Api.form("username", user, "password", pass, "email", email, "type", "chatter");
                    POOL.execute(() -> {
                        Api.Result r = Api.post(host, "/api/register", body, null);
                        JSONObject j = r.json();
                        a.runOnUiThread(() -> {
                            if (a.isFinishing()) {
                                return;
                            }
                            if (r.code == 200 && j.optBoolean("verify", false)) {
                                prefs.edit().putString("pendingEmail", email).apply();
                                dialog.dismiss();
                                askSignupCode(a, prefs, host, j.optString("username", user), j.optString("email", email), onDone);
                            } else if (r.code == 200 && !j.optString("token", "").isEmpty()) {
                                // A server without email checks: the account is ready at once.
                                dialog.dismiss();
                                signedIn(a, prefs, host, j, onDone);
                            } else {
                                error.setText(r.error());
                            }
                        });
                    });
                });
    }

    private static void askSignupCode(Activity a, SharedPreferences prefs, String host, String user, String masked,
                                      Runnable onDone) {
        askCode(a, "Check your email", "We sent a 6-digit code to " + masked
                        + ". Type it here to finish creating your account (look in the spam folder too).",
                new CodeHandler() {
                    @Override
                    public void confirm(String code, AlertDialog dialog, TextView error) {
                        String body = Api.form("username", user, "code", code);
                        POOL.execute(() -> {
                            Api.Result r = Api.post(host, "/api/register/verify", body, null);
                            JSONObject j = r.json();
                            a.runOnUiThread(() -> {
                                if (a.isFinishing()) {
                                    return;
                                }
                                if (r.code == 200 && !j.optString("token", "").isEmpty()) {
                                    dialog.dismiss();
                                    prefs.edit().remove("pendingEmail").apply();
                                    signedIn(a, prefs, host, j, onDone);
                                    Toast.makeText(a, "Welcome to Memoryq!", Toast.LENGTH_LONG).show();
                                } else {
                                    error.setText(r.error());
                                    if (r.code == 404) {
                                        dialog.dismiss();
                                        Toast.makeText(a, r.error(), Toast.LENGTH_LONG).show();
                                    }
                                }
                            });
                        });
                    }

                    @Override
                    public void resend(AlertDialog dialog, TextView error) {
                        String body = Api.form("username", user);
                        POOL.execute(() -> {
                            Api.Result r = Api.post(host, "/api/register/resend", body, null);
                            a.runOnUiThread(() -> error.setText(r.code == 200
                                    ? "A new code is on its way to " + r.json().optString("email", masked) + "."
                                    : r.error()));
                        });
                    }
                });
    }

    private static void signedIn(Activity a, SharedPreferences prefs, String host, JSONObject me, Runnable onDone) {
        prefs.edit().putString("host", host).apply();
        Account.apply(prefs, me, me.optString("token"));
        askedThisRun = true;
        if (onDone != null) {
            onDone.run();
        }
    }

    // ---- Older accounts ----

    /** Once per app start: if this account has no confirmed email address, offer to add one. */
    static void maybePrompt(Activity a) {
        if (askedThisRun) {
            return;
        }
        SharedPreferences prefs = a.getSharedPreferences("vstream", Context.MODE_PRIVATE);
        String token = prefs.getString("session", "");
        String role = prefs.getString("role", "");
        String host = prefs.getString("host", "");
        if (token.isEmpty() || host.isEmpty() || "GUEST".equals(role) || "ADMIN".equals(role)) {
            return;
        }
        askedThisRun = true;
        POOL.execute(() -> {
            Api.Result r = Api.get(host, "/api/email", token);
            JSONObject j = r.json();
            if (r.code != 200 || j.optBoolean("verified", false) || !j.optBoolean("needed", false)) {
                return;
            }
            String pending = j.optString("pending", "");
            a.runOnUiThread(() -> {
                if (a.isFinishing()) {
                    return;
                }
                if (!pending.isEmpty()) {
                    askChangeCode(a, host, token, pending);
                    return;
                }
                new AlertDialog.Builder(a)
                        .setTitle("Add your email address")
                        .setMessage("It keeps your account safe and lets us reach you about your account. It takes a minute.")
                        .setPositiveButton("Add email", (d, w) -> askChangeEmail(a, host, token))
                        .setNegativeButton("Later", null)
                        .show();
            });
        });
    }

    private static void askChangeEmail(Activity a, String host, String token) {
        askEmail(a, "Add your email address", "We send you a 6-digit code to confirm it.", "",
                (email, dialog, error) -> {
                    String body = Api.form("email", email);
                    POOL.execute(() -> {
                        Api.Result r = Api.post(host, "/api/email/start", body, token);
                        a.runOnUiThread(() -> {
                            if (a.isFinishing()) {
                                return;
                            }
                            if (r.code == 200) {
                                dialog.dismiss();
                                askChangeCode(a, host, token, r.json().optString("pending", email));
                            } else {
                                error.setText(r.error());
                            }
                        });
                    });
                });
    }

    private static void askChangeCode(Activity a, String host, String token, String masked) {
        askCode(a, "Check your email", "We sent a 6-digit code to " + masked + ". Type it here (look in spam too).",
                new CodeHandler() {
                    @Override
                    public void confirm(String code, AlertDialog dialog, TextView error) {
                        String body = Api.form("code", code);
                        POOL.execute(() -> {
                            Api.Result r = Api.post(host, "/api/email/confirm", body, token);
                            a.runOnUiThread(() -> {
                                if (a.isFinishing()) {
                                    return;
                                }
                                if (r.code == 200) {
                                    dialog.dismiss();
                                    Toast.makeText(a, "Email confirmed: " + r.json().optString("email", ""),
                                            Toast.LENGTH_LONG).show();
                                } else {
                                    error.setText(r.error());
                                    if (r.code == 404) {
                                        dialog.dismiss();
                                        askChangeEmail(a, host, token);
                                    }
                                }
                            });
                        });
                    }

                    @Override
                    public void resend(AlertDialog dialog, TextView error) {
                        dialog.dismiss();
                        askChangeEmail(a, host, token);
                    }
                });
    }

    // ---- Dialogs ----

    private interface EmailHandler {
        void send(String email, AlertDialog dialog, TextView error);
    }

    private interface CodeHandler {
        void confirm(String code, AlertDialog dialog, TextView error);

        void resend(AlertDialog dialog, TextView error);
    }

    private static LinearLayout box(Activity a, String text, EditText field, TextView error) {
        int pad = (int) (20 * a.getResources().getDisplayMetrics().density);
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, pad / 2, pad, 0);
        TextView msg = new TextView(a);
        msg.setText(text);
        box.addView(msg);
        box.addView(field);
        error.setTextColor(0xFFC62828);
        error.setVisibility(View.VISIBLE);
        box.addView(error);
        return box;
    }

    private static void askEmail(Activity a, String title, String text, String prefill, EmailHandler h) {
        EditText field = new EditText(a);
        field.setHint("you@example.com");
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        field.setSingleLine(true);
        field.setText(prefill);
        TextView error = new TextView(a);
        AlertDialog d = new AlertDialog.Builder(a)
                .setTitle(title)
                .setView(box(a, text, field, error))
                .setPositiveButton("Send code", null)
                .setNegativeButton("Cancel", null)
                .create();
        d.show();
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String email = field.getText().toString().trim();
            if (!email.contains("@") || !email.contains(".")) {
                error.setText("Enter a valid email address");
                return;
            }
            error.setText("Sending...");
            h.send(email, d, error);
        });
    }

    private static void askCode(Activity a, String title, String text, CodeHandler h) {
        EditText field = new EditText(a);
        field.setHint("6-digit code");
        field.setInputType(InputType.TYPE_CLASS_NUMBER);
        field.setSingleLine(true);
        TextView error = new TextView(a);
        AlertDialog d = new AlertDialog.Builder(a)
                .setTitle(title)
                .setView(box(a, text, field, error))
                .setPositiveButton("Confirm", null)
                .setNeutralButton("New code", null)
                .setNegativeButton("Cancel", null)
                .setCancelable(false)
                .create();
        d.show();
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String code = field.getText().toString().replaceAll("[^0-9]", "");
            if (code.length() != 6) {
                error.setText("The code has 6 digits");
                return;
            }
            error.setText("Checking...");
            h.confirm(code, d, error);
        });
        d.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
            error.setText("Sending...");
            h.resend(d, error);
        });
    }
}