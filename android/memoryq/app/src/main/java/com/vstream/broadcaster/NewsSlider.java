package com.vstream.broadcaster;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;

/**
 * The slider shown between news stories: 4 random people and one ad card (a paid ad, or a Google ad when no paid
 * ad is running), side by side; swipe left or right. Fills a placeholder in the news list, or removes it when the
 * server has nothing to show.
 */
final class NewsSlider {

    interface EveryListener {
        void every(int stories);
    }

    private NewsSlider() {
    }

    /** Asks the server for a slider and builds it inside the placeholder. */
    static void fill(final Activity a, final SharedPreferences prefs, final LinearLayout slot, final EveryListener every) {
        final String host = prefs.getString("host", "");
        final String session = prefs.getString("session", "");
        new Thread(() -> {
            final Api.Result r = Api.get(host, "/api/feed/slider", session);
            a.runOnUiThread(() -> {
                if (a.isFinishing() || a.isDestroyed()) {
                    return;
                }
                JSONObject o = r.code == 200 ? r.json() : null;
                if (o != null && every != null) {
                    every.every(o.optInt("every", 20));
                }
                JSONArray people = o == null ? null : o.optJSONArray("people");
                JSONObject ad = o == null ? null : o.optJSONObject("ad");
                int n = people == null ? 0 : people.length();
                if (o == null || !o.optBoolean("enabled") || (n == 0 && ad == null)) {
                    ViewGroup parent = (ViewGroup) slot.getParent();
                    if (parent != null) {
                        parent.removeView(slot);
                    }
                    return;
                }
                build(a, prefs, host, slot, people, ad);
            });
        }).start();
    }

    private static void build(Activity a, SharedPreferences prefs, String host, LinearLayout slot,
                              JSONArray people, JSONObject ad) {
        boolean dark = ProfileUi.dark(a);
        int n = people == null ? 0 : people.length();
        TextView title = ProfileUi.text(a, (n > 0 ? "People you may know" : "") + (ad != null ? (n > 0 ? "  \u00b7  " : "")
                + "Sponsored" : ""), 13, true);
        title.setAlpha(0.75f);
        title.setPadding(ProfileUi.dp(a, 2), 0, 0, ProfileUi.dp(a, 6));
        slot.addView(title);
        HorizontalScrollView scroll = new HorizontalScrollView(a);
        scroll.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        scroll.addView(row);
        int adAt = Math.min(2, n);
        for (int i = 0; i <= n; i++) {
            if (i == adAt && ad != null) {
                View card = "paid".equals(ad.optString("type")) ? paidCard(a, prefs, host, ad, dark)
                        : GoogleAdCard.make(a, ad.optString("unitId"), dark);
                if (card != null) {
                    row.addView(card);
                }
            }
            if (i < n) {
                JSONObject p = people.optJSONObject(i);
                if (p != null) {
                    row.addView(personCard(a, host, p, dark));
                }
            }
        }
        slot.addView(scroll);
    }

    private static LinearLayout cardBox(Activity a, int widthDp, boolean dark) {
        LinearLayout c = new LinearLayout(a);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setGravity(Gravity.CENTER_HORIZONTAL);
        int pad = ProfileUi.dp(a, 10);
        c.setPadding(pad, pad, pad, pad);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(ProfileUi.dp(a, 12));
        bg.setColor(dark ? 0xFF2B2B2B : 0xFFF1F3F4);
        c.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ProfileUi.dp(a, widthDp),
                ViewGroup.LayoutParams.MATCH_PARENT);
        lp.setMargins(0, 0, ProfileUi.dp(a, 8), 0);
        c.setLayoutParams(lp);
        c.setClickable(true);
        return c;
    }

    private static View personCard(final Activity a, String host, JSONObject p, boolean dark) {
        LinearLayout c = cardBox(a, 118, dark);
        ImageView av = ProfileUi.roundImage(a, 72, true);
        final String user = p.optString("username");
        ImageLoader.load(av, host, user, true);
        c.addView(av);
        String stage = p.optString("stageName");
        String name = ProfileUi.fullName(p);
        TextView t = ProfileUi.text(a, !stage.isEmpty() ? stage : (!name.isEmpty() ? name : user), 13, true);
        t.setGravity(Gravity.CENTER);
        t.setMaxLines(1);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setPadding(0, ProfileUi.dp(a, 6), 0, 0);
        c.addView(t);
        String country = p.optString("country");
        TextView s = ProfileUi.text(a, country.isEmpty() ? "@" + user : country, 11, false);
        s.setGravity(Gravity.CENTER);
        s.setMaxLines(1);
        s.setEllipsize(TextUtils.TruncateAt.END);
        s.setAlpha(0.7f);
        c.addView(s);
        c.setOnClickListener(v -> ProfileUi.openProfile(a, user));
        return c;
    }

    private static View paidCard(final Activity a, final SharedPreferences prefs, String host, JSONObject ad, boolean dark) {
        LinearLayout c = cardBox(a, 220, dark);
        c.setGravity(Gravity.START);
        TextView label = ProfileUi.text(a, "Sponsored \u00b7 " + ad.optString("advertiser"), 11, true);
        label.setTextColor(0xFFEF6C00);
        label.setMaxLines(1);
        label.setEllipsize(TextUtils.TruncateAt.END);
        c.addView(label);
        ImageView img = new ImageView(a);
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ProfileUi.dp(a, 100));
        ilp.setMargins(0, ProfileUi.dp(a, 4), 0, ProfileUi.dp(a, 4));
        img.setLayoutParams(ilp);
        final String id = String.valueOf(ad.optLong("id"));
        ImageLoader.loadPath(img, host, ad.optString("imageUrl"), "ad|" + id);
        c.addView(img);
        TextView h = ProfileUi.text(a, ad.optString("headline"), 14, true);
        h.setMaxLines(2);
        h.setEllipsize(TextUtils.TruncateAt.END);
        c.addView(h);
        String text = ad.optString("text");
        if (!text.isEmpty()) {
            TextView t = ProfileUi.text(a, text, 12, false);
            t.setMaxLines(2);
            t.setEllipsize(TextUtils.TruncateAt.END);
            t.setAlpha(0.8f);
            c.addView(t);
        }
        final String hostF = host;
        final String session = prefs.getString("session", "");
        new Thread(() -> Api.post(hostF, "/api/feed/ad/view", Api.form("id", id), session)).start();
        c.setOnClickListener(v -> new Thread(() -> {
            Api.Result r = Api.post(hostF, "/api/feed/ad/click", Api.form("id", id), session);
            final String link = r.code == 200 ? r.json().optString("link") : "";
            a.runOnUiThread(() -> open(a, link));
        }).start());
        return c;
    }

    private static void open(Activity a, String url) {
        String l = url == null ? "" : url.toLowerCase(Locale.ROOT);
        if (a.isFinishing() || !(l.startsWith("https://") || l.startsWith("http://"))) {
            return;
        }
        try {
            a.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(a, "No browser found to open this link", Toast.LENGTH_SHORT).show();
        }
    }
}