package com.vstream.broadcaster;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

/** Builds the profile card (picture, names, place, education, hobbies, friends) and people rows. */
final class ProfileUi {

    private ProfileUi() {
    }

    static int dp(Activity a, int v) {
        return Math.round(v * a.getResources().getDisplayMetrics().density);
    }

    static boolean dark(Activity a) {
        return (a.getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
    }

    static TextView text(Activity a, String s, int sp, boolean bold) {
        TextView t = new TextView(a);
        t.setText(s);
        t.setTextSize(sp);
        if (bold) {
            t.setTypeface(t.getTypeface(), Typeface.BOLD);
        }
        return t;
    }

    static ImageView roundImage(Activity a, int sizeDp, final boolean circle) {
        ImageView iv = new ImageView(a);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        final int radius = dp(a, 14);
        iv.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                if (circle) {
                    outline.setOval(0, 0, view.getWidth(), view.getHeight());
                } else {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
                }
            }
        });
        iv.setClipToOutline(true);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0x22000000);
        if (circle) {
            bg.setShape(GradientDrawable.OVAL);
        } else {
            bg.setCornerRadius(radius);
        }
        iv.setBackground(bg);
        iv.setLayoutParams(new LinearLayout.LayoutParams(dp(a, sizeDp), dp(a, sizeDp)));
        return iv;
    }

    static String fullName(JSONObject p) {
        return (p.optString("firstName") + " " + p.optString("lastName")).trim();
    }

    /** The big card: picture, stage name, names, place, education, hobbies and a "test account" note. */
    static View card(Activity a, String host, JSONObject p) {
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(0, dp(a, 8), 0, dp(a, 8));

        ImageView pic = roundImage(a, 168, false);
        ImageLoader.load(pic, host, p.optString("username"), false);
        final String picUser = p.optString("username");
        pic.setOnClickListener(v -> showPhoto(a, host, picUser));
        box.addView(pic);

        String stage = p.optString("stageName");
        String name = fullName(p);
        TextView title = text(a, stage.isEmpty() ? (name.isEmpty() ? p.optString("username") : name) : stage, 22, true);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, dp(a, 10), 0, 0);
        box.addView(title);
        String second = (!stage.isEmpty() && !name.isEmpty() ? name + "  \u00b7  " : "") + "@" + p.optString("username");
        TextView sub = text(a, second, 14, false);
        sub.setGravity(Gravity.CENTER);
        sub.setAlpha(0.75f);
        box.addView(sub);
        if (p.optBoolean("test")) {
            TextView badge = text(a, "Test account (made by the profiler)", 12, true);
            badge.setTextColor(0xFFEF6C00);
            badge.setGravity(Gravity.CENTER);
            badge.setPadding(0, dp(a, 4), 0, 0);
            box.addView(badge);
        }

        LinearLayout facts = new LinearLayout(a);
        facts.setOrientation(LinearLayout.VERTICAL);
        facts.setPadding(dp(a, 4), dp(a, 12), dp(a, 4), 0);
        String city = p.optString("city");
        String country = p.optString("country");
        String place = city.isEmpty() ? country : (country.isEmpty() ? city : city + ", " + country);
        addFact(a, facts, "\ud83d\udccd", place);
        addFact(a, facts, "\ud83c\udf93", p.optString("education"));
        JSONArray hs = p.optJSONArray("hobbies");
        StringBuilder hb = new StringBuilder();
        if (hs != null) {
            for (int i = 0; i < hs.length(); i++) {
                if (hb.length() > 0) {
                    hb.append(", ");
                }
                hb.append(hs.optString(i));
            }
        }
        addFact(a, facts, "\u2764", hb.toString());
        if (facts.getChildCount() == 0) {
            TextView none = text(a, p.optBoolean("me") ? "Your profile is empty - tap Edit profile to fill it in."
                    : "No details yet.", 14, false);
            none.setAlpha(0.7f);
            facts.addView(none);
        }
        box.addView(facts, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return box;
    }

    private static void addFact(Activity a, LinearLayout facts, String icon, String value) {
        if (value == null || value.trim().isEmpty()) {
            return;
        }
        TextView t = text(a, icon + "  " + value, 15, false);
        t.setPadding(0, dp(a, 3), 0, dp(a, 3));
        facts.addView(t);
    }

    /** "Friends (3)" and a row per friend; tapping a friend opens their profile. */
    static void friends(Activity a, String host, LinearLayout into, JSONObject p) {
        JSONArray fs = p.optJSONArray("friends");
        int n = fs == null ? 0 : fs.length();
        TextView h = text(a, "Friends" + (n > 0 ? " (" + n + ")" : ""), 18, true);
        h.setPadding(0, dp(a, 16), 0, dp(a, 4));
        into.addView(h);
        if (n == 0) {
            TextView none = text(a, p.optBoolean("me") ? "No friends yet. Open someone's profile and tap Add friend."
                    : "No friends yet.", 14, false);
            none.setAlpha(0.7f);
            into.addView(none);
            return;
        }
        for (int i = 0; i < n; i++) {
            JSONObject f = fs.optJSONObject(i);
            if (f != null) {
                into.addView(personRow(a, host, f));
            }
        }
    }

    /** One compact row: avatar, stage name or name, and country. Tap opens the profile. */
    static View personRow(final Activity a, String host, final JSONObject f) {
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(a, 6), 0, dp(a, 6));
        android.util.TypedValue tv = new android.util.TypedValue();
        if (a.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true)) {
            row.setBackgroundResource(tv.resourceId);
        }
        ImageView av = roundImage(a, 40, true);
        final String user = f.optString("username");
        ImageLoader.load(av, host, user, true);
        row.addView(av);
        LinearLayout texts = new LinearLayout(a);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding(dp(a, 12), 0, 0, 0);
        String stage = f.optString("stageName");
        String name = fullName(f);
        texts.addView(text(a, !stage.isEmpty() ? stage : (!name.isEmpty() ? name : user), 16, true));
        String country = f.optString("country");
        String sub = (!stage.isEmpty() && !name.isEmpty() ? name : "@" + user) + (country.isEmpty() ? "" : "  \u00b7  " + country)
                + (f.optBoolean("test") ? "  \u00b7  test" : "");
        TextView s = text(a, sub, 13, false);
        s.setAlpha(0.7f);
        texts.addView(s);
        row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.setOnClickListener(v -> openProfile(a, user));
        return row;
    }

    /** The person's picture filling the screen (tap anywhere or Back to close). */
    static void showPhoto(Activity a, String host, String username) {
        final android.app.Dialog d = new android.app.Dialog(a, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        android.widget.FrameLayout box = new android.widget.FrameLayout(a);
        box.setBackgroundColor(0xFF000000);
        ImageView iv = new ImageView(a);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        iv.setAdjustViewBounds(false);
        box.addView(iv, new android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        ImageLoader.load(iv, host, username, false);
        box.setOnClickListener(v -> d.dismiss());
        iv.setOnClickListener(v -> d.dismiss());
        d.setContentView(box);
        d.show();
    }
    static void openProfile(Activity a, String username) {
        Intent i = new Intent(a, PersonProfileActivity.class);
        i.putExtra(PersonProfileActivity.EXTRA_USER, username);
        a.startActivity(i);
    }
}