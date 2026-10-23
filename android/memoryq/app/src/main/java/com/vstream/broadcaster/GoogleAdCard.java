package com.vstream.broadcaster;

import android.app.Activity;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.android.gms.ads.AdListener;
import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.AdSize;
import com.google.android.gms.ads.AdView;
import com.google.android.gms.ads.LoadAdError;

/**
 * A Google (AdMob) ad card for the news slider: a 300 x 250 ad with an "Ad" label. If Google has no ad to show,
 * the card removes itself. The ad unit comes from the admin page; Google's test unit shows test ads.
 */
final class GoogleAdCard {

    private GoogleAdCard() {
    }

    static View make(Activity a, String unitId, boolean dark) {
        if (unitId == null || unitId.isEmpty()) {
            return null;
        }
        final LinearLayout c = new LinearLayout(a);
        c.setOrientation(LinearLayout.VERTICAL);
        int pad = ProfileUi.dp(a, 8);
        c.setPadding(pad, pad, pad, pad);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(ProfileUi.dp(a, 12));
        bg.setColor(dark ? 0xFF2B2B2B : 0xFFF1F3F4);
        c.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, ProfileUi.dp(a, 8), 0);
        c.setLayoutParams(lp);
        TextView label = ProfileUi.text(a, "Ad", 11, true);
        label.setTextColor(0xFFEF6C00);
        c.addView(label);
        AdView adView = new AdView(a);
        adView.setAdSize(AdSize.MEDIUM_RECTANGLE);
        adView.setAdUnitId(unitId);
        adView.setAdListener(new AdListener() {
            @Override
            public void onAdFailedToLoad(LoadAdError error) {
                ViewGroup parent = (ViewGroup) c.getParent();
                if (parent != null) {
                    parent.removeView(c);
                }
            }
        });
        c.addView(adView, new LinearLayout.LayoutParams(ProfileUi.dp(a, 300), ProfileUi.dp(a, 250)));
        try {
            adView.loadAd(new AdRequest.Builder().build());
        } catch (RuntimeException e) {
            return null;
        }
        return c;
    }
}