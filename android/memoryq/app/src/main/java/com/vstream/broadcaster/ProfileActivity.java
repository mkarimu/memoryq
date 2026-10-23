package com.vstream.broadcaster;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;

import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Personal page: your profile (picture, names, place, education, hobbies, friends, wall). The menu under Home has
 * Edit profile (with the profile photo), Card colour, Appearance, Chat privacy and account deletion.
 */
public class ProfileActivity extends AppCompatActivity {

    private static final String PREFS = "vstream";

    private SharedPreferences prefs;
    private boolean loading = false;
    private android.widget.LinearLayout myBox;
    private TextView nameView;
    private TextView roleView;
    private android.widget.Button signInBtn;
    /** The latest profile from the server (for Edit profile and the deletion state). */
    private JSONObject lastProfile;
    private final androidx.activity.result.ActivityResultLauncher<Intent> pickPhoto = registerForActivityResult(
            new androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(), result -> {
                Intent d = result.getData();
                if (result.getResultCode() == RESULT_OK && d != null && d.getData() != null) {
                    uploadPhoto(d.getData());
                }
            });
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        setContentView(buildScreen());
    }

    private boolean isDark() {
        return ProfileUi.dark(this);
    }

    /** Header: Home, then "My profile" with the menu on the right; below it the profile itself. */
    private View buildScreen() {
        boolean dark = isDark();
        int ink = dark ? 0xFFE6EBF0 : 0xFF1B1F23;
        int muted = dark ? 0xFF9FB0C0 : 0xFF5F6E7D;
        android.widget.LinearLayout root = new android.widget.LinearLayout(this);
        root.setOrientation(android.widget.LinearLayout.VERTICAL);
        root.setFitsSystemWindows(true);

        android.widget.LinearLayout header = new android.widget.LinearLayout(this);
        header.setOrientation(android.widget.LinearLayout.VERTICAL);
        header.setBackgroundColor(dark ? 0xFF182029 : 0xFFFFFFFF);
        header.setPadding(dp(12), dp(6), dp(8), dp(8));
        header.setElevation(dp(2));
        TextView home = new TextView(this);
        home.setText("\u2302  Home");
        home.setTextSize(15);
        home.setTextColor(muted);
        home.setPadding(dp(4), dp(8), dp(12), dp(4));
        home.setOnClickListener(v -> {
            Intent i = new Intent(this, HomeActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(i);
            finish();
        });
        android.widget.LinearLayout homeRow = new android.widget.LinearLayout(this);
        homeRow.addView(home);
        header.addView(homeRow);
        android.widget.LinearLayout titleRow = new android.widget.LinearLayout(this);
        titleRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this);
        title.setText("My profile");
        title.setTextSize(24);
        title.setTextColor(ink);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        title.setPadding(dp(4), 0, 0, 0);
        titleRow.addView(title, new android.widget.LinearLayout.LayoutParams(0,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView dots = new TextView(this);
        dots.setText("\u22EE");
        dots.setTextSize(24);
        dots.setTextColor(ink);
        dots.setTypeface(dots.getTypeface(), android.graphics.Typeface.BOLD);
        dots.setGravity(android.view.Gravity.CENTER);
        dots.setContentDescription("More: edit profile, card colour, appearance, chat privacy, account deletion");
        dots.setOnClickListener(this::showMenu);
        titleRow.addView(dots, new android.widget.LinearLayout.LayoutParams(dp(44), dp(44)));
        header.addView(titleRow);
        root.addView(header);

        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        android.widget.LinearLayout body = new android.widget.LinearLayout(this);
        body.setOrientation(android.widget.LinearLayout.VERTICAL);
        body.setPadding(dp(20), dp(10), dp(20), dp(24));
        nameView = ProfileUi.text(this, "", 16, true);
        body.addView(nameView);
        roleView = ProfileUi.text(this, "", 13, false);
        roleView.setTextColor(muted);
        body.addView(roleView);
        signInBtn = new android.widget.Button(this);
        signInBtn.setText("Sign in or create an account");
        signInBtn.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
        body.addView(signInBtn);
        myBox = new android.widget.LinearLayout(this);
        myBox.setOrientation(android.widget.LinearLayout.VERTICAL);
        myBox.setPadding(0, dp(8), 0, dp(8));
        body.addView(myBox);
        scroll.addView(body);
        root.addView(scroll, new android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return root;
    }

    private int dp(int v) {
        return ProfileUi.dp(this, v);
    }

    /** The menu under Home, on the right. */
    private void showMenu(View anchor) {
        boolean signed = Account.signedIn(prefs);
        android.widget.PopupMenu m = new android.widget.PopupMenu(this, anchor);
        if (signed && lastProfile != null) {
            m.getMenu().add(0, 1, 0, "Edit profile");
            m.getMenu().add(0, 5, 0, "Card colour");
        }
        m.getMenu().add(0, 2, 1, "Appearance");
        if (signed) {
            m.getMenu().add(0, 3, 2, "Chat privacy");
            JSONObject del = lastProfile == null ? null : lastProfile.optJSONObject("deletion");
            boolean pending = del != null && "pending".equals(del.optString("status"));
            m.getMenu().add(0, 4, 3, pending ? "Cancel deletion request" : "Request account deletion");
        }
        m.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1:
                    editProfile(lastProfile);
                    break;
                case 2:
                    showAppearance();
                    break;
                case 5:
                    showCardColor(lastProfile);
                    break;
                case 3:
                    showPrivacy();
                    break;
                case 4:
                    JSONObject d = lastProfile == null ? null : lastProfile.optJSONObject("deletion");
                    if (d != null && "pending".equals(d.optString("status"))) {
                        postDeletion("/api/profiles/deletion/cancel", "", "Request cancelled");
                    } else {
                        askDeletion();
                    }
                    break;
                default:
                    break;
            }
            return true;
        });
        m.show();
    }

    /** The colours offered for your card (all dark enough for white writing). */
    private static final String[] CARD_COLORS = {"#c62828", "#ad1457", "#6a1b9a", "#4527a0", "#283593", "#1565c0",
            "#0277bd", "#00838f", "#00695c", "#2e7d32", "#558b2f", "#8d6e00", "#e65100", "#bf360c", "#5d4037",
            "#455a64", "#37474f", "#212121"};

    /** Card colour: pick the colour people see your card in (People, friends); a preview shows it. */
    private void showCardColor(final JSONObject p) {
        if (p == null) {
            return;
        }
        final String user = p.optString("username");
        final String stage = p.optString("stageName");
        final String full = ProfileUi.fullName(p);
        final String[] chosen = {p.optBoolean("colorSet") ? p.optString("color").toLowerCase(java.util.Locale.ROOT) : ""};

        android.widget.LinearLayout box = new android.widget.LinearLayout(this);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), dp(4));

        // preview: your card as others see it
        final android.widget.LinearLayout card = new android.widget.LinearLayout(this);
        card.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        card.setGravity(android.view.Gravity.CENTER_VERTICAL);
        card.setPadding(dp(16), dp(14), dp(14), dp(14));
        final android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setCornerRadius(dp(6));
        card.setBackground(bg);
        android.widget.LinearLayout col = new android.widget.LinearLayout(this);
        col.setOrientation(android.widget.LinearLayout.VERTICAL);
        TextView nm = new TextView(this);
        nm.setText(!stage.isEmpty() ? stage : (!full.isEmpty() ? full : user));
        nm.setTextSize(18);
        nm.setTextColor(0xFFFFFFFF);
        nm.setTypeface(nm.getTypeface(), android.graphics.Typeface.BOLD);
        nm.setSingleLine(true);
        col.addView(nm);
        TextView sub = new TextView(this);
        sub.setText("@" + user);
        sub.setTextSize(14);
        sub.setTextColor(0xE6FFFFFF);
        col.addView(sub);
        card.addView(col, new android.widget.LinearLayout.LayoutParams(0,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        android.widget.ImageView av = ProfileUi.roundImage(this, 48, true);
        ImageLoader.load(av, prefs.getString("host", ""), user, true);
        card.addView(av);
        box.addView(card);

        final TextView hint = ProfileUi.text(this, "", 13, false);
        hint.setAlpha(0.75f);
        hint.setPadding(0, dp(10), 0, dp(6));
        box.addView(hint);

        // swatches: Automatic first, then the colours (6 to a row)
        final java.util.List<TextView> dots = new java.util.ArrayList<TextView>();
        final String autoHex = String.format("#%06x", PeopleActivity.colorFor(user) & 0xFFFFFF);
        final Runnable redraw = () -> {
            String c = chosen[0].isEmpty() ? autoHex : chosen[0];
            bg.setColor(0xFF000000 | Integer.parseInt(c.substring(1), 16));
            hint.setText(chosen[0].isEmpty() ? "Automatic - a colour worked out from your name." : "Your chosen colour.");
            for (TextView d : dots) {
                String tag = (String) d.getTag();
                boolean on = tag.equals(chosen[0]);
                d.setText(on ? "\u2713" : (tag.isEmpty() ? "A" : ""));
            }
        };
        android.widget.LinearLayout row = null;
        for (int i = 0; i <= CARD_COLORS.length; i++) {
            if (i % 6 == 0) {
                row = new android.widget.LinearLayout(this);
                row.setPadding(0, dp(4), 0, dp(4));
                box.addView(row);
            }
            final String hex = i == 0 ? "" : CARD_COLORS[i - 1];
            TextView d = new TextView(this);
            d.setTag(hex);
            d.setGravity(android.view.Gravity.CENTER);
            d.setTextColor(0xFFFFFFFF);
            d.setTextSize(16);
            d.setTypeface(d.getTypeface(), android.graphics.Typeface.BOLD);
            android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
            g.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            if (hex.isEmpty()) {
                g.setColors(new int[]{0xFFC62828, 0xFF2E7D32, 0xFF1565C0, 0xFF6A1B9A});
                g.setGradientType(android.graphics.drawable.GradientDrawable.SWEEP_GRADIENT);
                d.setContentDescription("Automatic colour");
            } else {
                g.setColor(0xFF000000 | Integer.parseInt(hex.substring(1), 16));
                d.setContentDescription("Colour " + hex);
            }
            d.setBackground(g);
            d.setOnClickListener(v -> {
                chosen[0] = hex;
                redraw.run();
            });
            dots.add(d);
            android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(dp(36), dp(36));
            lp.setMargins(0, 0, dp(8), 0);
            row.addView(d, lp);
        }
        redraw.run();
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.addView(box);
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Card colour")
                .setView(scroll)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Save", (dlg, w) -> saveCardColor(chosen[0].isEmpty() ? "auto" : chosen[0]))
                .show();
    }

    private void saveCardColor(final String color) {
        final String host = prefs.getString("host", "");
        final String session = prefs.getString("session", "");
        executor.execute(() -> {
            final Api.Result r = Api.post(host, "/api/profiles/color", Api.form("color", color), session);
            runOnUiThread(() -> {
                if (isFinishing()) {
                    return;
                }
                if (r.code == 200) {
                    lastProfile = r.json();
                    android.widget.Toast.makeText(this, "Card colour saved", android.widget.Toast.LENGTH_SHORT).show();
                } else if (r.code == 401) {
                    Account.clear(prefs);
                } else {
                    String msg = r.json().optString("error", "Could not save the colour");
                    android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_LONG).show();
                }
            });
        });
    }

    /** Appearance: follow the phone, light or dark. */
    private void showAppearance() {
        final String[] labels = {"Same as the phone", "Light", "Dark"};
        final String[] modes = {"system", "light", "dark"};
        String now = prefs.getString("theme", "system");
        int checked = "light".equals(now) ? 1 : ("dark".equals(now) ? 2 : 0);
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Appearance")
                .setSingleChoiceItems(labels, checked, (d, which) -> {
                    d.dismiss();
                    String mode = modes[which];
                    if (!mode.equals(prefs.getString("theme", "system"))) {
                        prefs.edit().putString("theme", mode).apply();
                        AppTheme.apply(mode);
                    }
                })
                .setNegativeButton("Close", null)
                .show();
    }

    /** Chat privacy: show when I am online, send read receipts. */
    private void showPrivacy() {
        android.widget.LinearLayout box = new android.widget.LinearLayout(this);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        final SwitchCompat online = new SwitchCompat(this);
        online.setText("Show when I am online");
        online.setChecked(prefs.getBoolean("showOnline", true));
        final SwitchCompat receipts = new SwitchCompat(this);
        receipts.setText("Send read receipts");
        receipts.setChecked(prefs.getBoolean("sendReceipts", true));
        box.addView(online);
        box.addView(receipts);
        TextView note = ProfileUi.text(this, "If you turn read receipts off, you also will not see who read your messages. "
                + "Delivered ticks always show.", 13, false);
        note.setAlpha(0.75f);
        note.setPadding(0, dp(8), 0, 0);
        box.addView(note);
        online.setOnCheckedChangeListener((b, c) -> pushPrivacy(online.isChecked(), receipts.isChecked()));
        receipts.setOnCheckedChangeListener((b, c) -> pushPrivacy(online.isChecked(), receipts.isChecked()));
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Chat privacy")
                .setView(box)
                .setPositiveButton("Done", null)
                .show();
    }

    @Override
    protected void onStart() {
        super.onStart();
        showAccount();
        loadPrivacy();
        loadMyProfile();
    }

    // ---- My profile ----

    private void loadMyProfile() {
        if (!Account.signedIn(prefs)) {
            myBox.setVisibility(View.GONE);
            return;
        }
        myBox.setVisibility(View.VISIBLE);
        final String host = prefs.getString("host", "");
        final String session = prefs.getString("session", "");
        executor.execute(() -> {
            final Api.Result r = Api.get(host, "/api/profiles/me", session);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                myBox.removeAllViews();
                if (r.code != 200) {
                    myBox.addView(ProfileUi.text(this, r.code == 0 ? "Cannot reach the server"
                            : (r.code == 404 ? "Profiles need the latest server update." : r.error()), 14, false));
                    return;
                }
                renderMyProfile(r.json());
            });
        });
    }

    private void renderMyProfile(final JSONObject p) {
        final String host = prefs.getString("host", "");
        lastProfile = p;
        myBox.addView(ProfileUi.card(this, host, p));
        android.widget.Button people = new android.widget.Button(this);
        people.setText("Find people");
        people.setOnClickListener(v -> startActivity(new Intent(this, PeopleActivity.class)));
        myBox.addView(people);
        JSONObject del = p.optJSONObject("deletion");
        String st = del == null ? "" : del.optString("status");
        if ("pending".equals(st)) {
            String when = java.text.DateFormat.getDateTimeInstance().format(new java.util.Date(del.optLong("at")));
            TextView t = ProfileUi.text(this, "You asked for your account to be deleted on " + when
                    + ". The admin will review it. (Cancel it from the menu at the top.)", 14, false);
            t.setTextColor(0xFFEF6C00);
            t.setPadding(0, dp(8), 0, 0);
            myBox.addView(t);
        } else if ("declined".equals(st)) {
            String note = del.optString("note");
            TextView t = ProfileUi.text(this, "The admin declined your deletion request" + (note.isEmpty() ? "." : ": " + note), 14, false);
            t.setTextColor(0xFFC62828);
            t.setPadding(0, dp(8), 0, 0);
            myBox.addView(t);
        }
        ProfileUi.friends(this, host, myBox, p);
        TextView wallTitle = ProfileUi.text(this, "My wall", 18, true);
        wallTitle.setPadding(0, dp(16), 0, dp(4));
        myBox.addView(wallTitle);
        android.widget.Button write = new android.widget.Button(this);
        write.setText("\u270F\uFE0F  Write a post");
        write.setOnClickListener(v -> startActivity(new Intent(this, WallComposerActivity.class)));
        myBox.addView(write);
        android.widget.LinearLayout wallList = new android.widget.LinearLayout(this);
        wallList.setOrientation(android.widget.LinearLayout.VERTICAL);
        myBox.addView(wallList);
        WallUi.loadWall(this, prefs, p.optString("username"), wallList, 0);
    }

    private void choosePhoto() {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.setType("image/*");
        i.addCategory(Intent.CATEGORY_OPENABLE);
        try {
            pickPhoto.launch(Intent.createChooser(i, "Choose your photo"));
        } catch (RuntimeException e) {
            android.widget.Toast.makeText(this, "No app found to choose photos", android.widget.Toast.LENGTH_LONG).show();
        }
    }

    /** Square photos look best; the server crops the middle square and shrinks it. */
    private void uploadPhoto(final android.net.Uri uri) {
        android.widget.Toast.makeText(this, "Uploading your photo...", android.widget.Toast.LENGTH_SHORT).show();
        executor.execute(() -> {
            PhotoUtil.Photo ph = PhotoUtil.load(this, uri, 1080, 1500 * 1024);
            if (ph == null) {
                runOnUiThread(() -> android.widget.Toast.makeText(this, "That photo could not be opened",
                        android.widget.Toast.LENGTH_LONG).show());
                return;
            }
            String form = Api.form("image", "data:image/jpeg;base64,"
                    + android.util.Base64.encodeToString(ph.jpeg, android.util.Base64.NO_WRAP));
            runOnUiThread(() -> sendPhoto("/api/profiles/photo", form, "Photo saved"));
        });
    }

    private void sendPhoto(final String path, final String form, final String done) {
        final String host = prefs.getString("host", "");
        final String session = prefs.getString("session", "");
        final String me = prefs.getString("username", "");
        executor.execute(() -> {
            final Api.Result r = Api.post(host, path, form, session);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                if (r.code == 200) {
                    ImageLoader.forget(me);
                    android.widget.Toast.makeText(this, done, android.widget.Toast.LENGTH_SHORT).show();
                    loadMyProfile();
                } else {
                    android.widget.Toast.makeText(this, r.code == 0 ? "Cannot reach the server" : r.error(),
                            android.widget.Toast.LENGTH_LONG).show();
                }
            });
        });
    }

    private android.widget.EditText field(android.widget.LinearLayout box, String label, String value, int max) {
        TextView l = ProfileUi.text(this, label, 13, false);
        l.setPadding(0, ProfileUi.dp(this, 8), 0, 0);
        box.addView(l);
        android.widget.EditText e = new android.widget.EditText(this);
        e.setSingleLine(true);
        e.setText(value);
        e.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(max)});
        box.addView(e);
        return e;
    }

    private void editProfile(JSONObject p) {
        if (p == null) {
            return;
        }
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        android.widget.LinearLayout box = new android.widget.LinearLayout(this);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = ProfileUi.dp(this, 20);
        box.setPadding(pad, ProfileUi.dp(this, 4), pad, 0);
        scroll.addView(box);
        StringBuilder hobbies = new StringBuilder();
        org.json.JSONArray hs = p.optJSONArray("hobbies");
        for (int i = 0; hs != null && i < hs.length(); i++) {
            if (i > 0) {
                hobbies.append(", ");
            }
            hobbies.append(hs.optString(i));
        }
        // Profile photo, first in the edit window
        TextView ph = ProfileUi.text(this, "Profile photo", 13, false);
        ph.setPadding(0, dp(8), 0, dp(4));
        box.addView(ph);
        android.widget.LinearLayout photoRow = new android.widget.LinearLayout(this);
        photoRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        android.widget.ImageView thumb = ProfileUi.roundImage(this, 64, true);
        ImageLoader.load(thumb, prefs.getString("host", ""), p.optString("username"), true);
        photoRow.addView(thumb);
        android.widget.LinearLayout photoButtons = new android.widget.LinearLayout(this);
        photoButtons.setOrientation(android.widget.LinearLayout.VERTICAL);
        photoButtons.setPadding(dp(12), 0, 0, 0);
        final android.widget.Button upload = new android.widget.Button(this);
        upload.setText(p.optBoolean("hasPhoto") ? "\uD83D\uDCF7 Change photo" : "\uD83D\uDCF7 Upload a photo");
        photoButtons.addView(upload);
        final android.widget.Button drawn = new android.widget.Button(this);
        drawn.setText("Use drawn picture");
        drawn.setVisibility(p.optBoolean("hasPhoto") ? View.VISIBLE : View.GONE);
        photoButtons.addView(drawn);
        photoRow.addView(photoButtons, new android.widget.LinearLayout.LayoutParams(0,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        box.addView(photoRow);
        final android.widget.Button colorBtn = new android.widget.Button(this);
        colorBtn.setText("\uD83C\uDFA8 Card colour");
        box.addView(colorBtn);
        final android.widget.EditText first = field(box, "First name", p.optString("firstName"), 40);
        final android.widget.EditText last = field(box, "Last name", p.optString("lastName"), 40);
        final android.widget.EditText stage = field(box, "Stage name", p.optString("stageName"), 40);
        final android.widget.EditText city = field(box, "City", p.optString("city"), 60);
        final android.widget.EditText country = field(box, "Country", p.optString("country"), 60);
        final android.widget.EditText edu = field(box, "Education", p.optString("education"), 120);
        final android.widget.EditText hob = field(box, "Hobbies (separated by commas)", hobbies.toString(), 300);
        final androidx.appcompat.app.AlertDialog dialog = new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Edit profile")
                .setView(scroll)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Save", (d, w) -> saveProfile(Api.form(
                        "firstName", first.getText().toString(), "lastName", last.getText().toString(),
                        "stageName", stage.getText().toString(), "city", city.getText().toString(),
                        "country", country.getText().toString(), "education", edu.getText().toString(),
                        "hobbies", hob.getText().toString())))
                .create();
        colorBtn.setOnClickListener(v -> {
            dialog.dismiss();
            showCardColor(p);
        });
        upload.setOnClickListener(v -> {
            dialog.dismiss();
            choosePhoto();
        });
        drawn.setOnClickListener(v -> {
            dialog.dismiss();
            sendPhoto("/api/profiles/photo/remove", "", "Photo removed");
        });
        dialog.show();
    }

    private void saveProfile(final String form) {
        final String host = prefs.getString("host", "");
        final String session = prefs.getString("session", "");
        final String me = prefs.getString("username", "");
        executor.execute(() -> {
            final Api.Result r = Api.post(host, "/api/profiles/me", form, session);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                if (r.code == 200) {
                    ImageLoader.forget(me);
                    android.widget.Toast.makeText(this, "Profile saved", android.widget.Toast.LENGTH_SHORT).show();
                    loadMyProfile();
                } else {
                    android.widget.Toast.makeText(this, r.code == 0 ? "Cannot reach the server" : r.error(),
                            android.widget.Toast.LENGTH_LONG).show();
                }
            });
        });
    }

    private void askDeletion() {
        final android.widget.EditText reason = new android.widget.EditText(this);
        reason.setHint("Reason (optional)");
        reason.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(300)});
        android.widget.LinearLayout box = new android.widget.LinearLayout(this);
        int pad = ProfileUi.dp(this, 20);
        box.setPadding(pad, ProfileUi.dp(this, 8), pad, 0);
        box.addView(reason, new android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT));
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Delete your account?")
                .setMessage("Your request goes to the admin. If they approve it, your account, your groups and your "
                        + "messages are removed for good.")
                .setView(box)
                .setNegativeButton("Keep my account", null)
                .setPositiveButton("Send request", (d, w) -> postDeletion("/api/profiles/deletion/request",
                        Api.form("reason", reason.getText().toString()), "Request sent to the admin"))
                .show();
    }

    private void postDeletion(final String path, final String form, final String done) {
        final String host = prefs.getString("host", "");
        final String session = prefs.getString("session", "");
        executor.execute(() -> {
            final Api.Result r = Api.post(host, path, form, session);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                android.widget.Toast.makeText(this, r.code == 200 ? done
                        : (r.code == 0 ? "Cannot reach the server" : r.error()), android.widget.Toast.LENGTH_LONG).show();
                loadMyProfile();
            });
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }

    private void showAccount() {
        if (Account.signedIn(prefs)) {
            String user = prefs.getString("username", "");
            nameView.setText(user);
            String ch = prefs.getString("claimChannel", "");
            roleView.setText(ChatClient.roleLabel(prefs.getString("role", "")) + (ch.isEmpty() ? "" : "  \u00B7  Your channel: " + ch));
            signInBtn.setVisibility(View.GONE);
        } else {
            nameView.setText("Viewer");
            roleView.setText("Not signed in - you can watch and read chat");
            signInBtn.setVisibility(View.VISIBLE);
        }
    }

    // ---- Chat privacy ----

    /** Gets the saved privacy switches from the server (shown in Chat privacy in the menu). */
    private void loadPrivacy() {
        if (!Account.signedIn(prefs)) {
            return;
        }
        final String host = prefs.getString("host", "");
        final String session = prefs.getString("session", "");
        if (host.isEmpty()) {
            return;
        }
        executor.execute(() -> {
            Api.Result r = Api.get(host, "/api/chat/prefs", session);
            if (r.code == 200) {
                JSONObject o = r.json();
                prefs.edit().putBoolean("showOnline", o.optBoolean("showOnline", true))
                        .putBoolean("sendReceipts", o.optBoolean("sendReceipts", true)).apply();
            } else if (r.code == 401) {
                Account.clear(prefs);
            }
        });
    }

    private void pushPrivacy(final boolean online, final boolean receipts) {
        prefs.edit().putBoolean("showOnline", online).putBoolean("sendReceipts", receipts).apply();
        if (!Account.signedIn(prefs)) {
            return;
        }
        final String host = prefs.getString("host", "");
        final String session = prefs.getString("session", "");
        executor.execute(() -> {
            Api.Result r = Api.post(host, "/api/chat/prefs",
                    Api.form("showOnline", String.valueOf(online), "sendReceipts", String.valueOf(receipts)), session);
            if (r.code == 401) {
                Account.clear(prefs);
            }
        });
    }
}