package com.vstream.live;

import android.app.Activity;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import androidx.annotation.NonNull;
import com.google.android.gms.ads.AdError;
import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.AdSize;
import com.google.android.gms.ads.AdView;
import com.google.android.gms.ads.FullScreenContentCallback;
import com.google.android.gms.ads.LoadAdError;
import com.google.android.gms.ads.MobileAds;
import com.google.android.gms.ads.interstitial.InterstitialAd;
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback;
import com.google.android.ump.ConsentInformation;
import com.google.android.ump.ConsentRequestParameters;
import com.google.android.ump.UserMessagingPlatform;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Ads (Google AdMob), only around a stream - never while you are live:
 *  - a banner on the get-ready screen ("Vstream - before live"), removed the moment you start;
 *  - a full-screen ad after a stream of at least a minute has ended ("Vstream - after live"), at most every few minutes.
 * Asks for consent first where the law requires it (EEA, UK, Switzerland); Settings has "Privacy choices".
 * Test builds (installed from Android Studio / the build script) always show Google's test ads; the Play Store version
 * shows real ads. Never tap your own real ads.
 */
final class Ads {

    static final String APP_ID = "ca-app-pub-2298775225198657~6632453928";
    private static final String BANNER_ID = "ca-app-pub-2298775225198657/9823478061";
    private static final String AFTER_ID = "ca-app-pub-2298775225198657/3457380262";
    // Google's sample units, used by test builds
    private static final String TEST_BANNER_ID = "ca-app-pub-3940256099942544/9214589741";
    private static final String TEST_AFTER_ID = "ca-app-pub-3940256099942544/1033173712";

    /** A stream must have lasted this long for the "after live" ad. */
    static final long MIN_STREAM_MS = 60_000L;
    /** Not more often than this. */
    private static final long GAP_MS = 4 * 60_000L;

    private static final AtomicBoolean started = new AtomicBoolean(false);
    private static volatile boolean ready;
    private static InterstitialAd after;
    private static boolean loadingAfter;
    private static long lastShown;

    private Ads() {
    }

    private static boolean testBuild(Context c) {
        return (c.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
    }

    static ConsentInformation consent(Context c) {
        return UserMessagingPlatform.getConsentInformation(c.getApplicationContext());
    }

    /** At start-up: the consent question where needed, then the ads SDK. onReady runs on the main thread. */
    static void start(Activity a, Runnable onReady) {
        ConsentInformation ci = consent(a);
        ConsentRequestParameters params = new ConsentRequestParameters.Builder().build();
        ci.requestConsentInfoUpdate(a, params,
                () -> UserMessagingPlatform.loadAndShowConsentFormIfRequired(a, formError -> init(a, onReady)),
                error -> init(a, onReady));
        if (ci.canRequestAds()) {
            init(a, onReady); // consent from an earlier time: no need to wait
        }
    }

    private static void init(Activity a, Runnable onReady) {
        if (!consent(a).canRequestAds()) {
            return;
        }
        Context app = a.getApplicationContext();
        Handler main = new Handler(Looper.getMainLooper());
        if (started.compareAndSet(false, true)) {
            new Thread(() -> MobileAds.initialize(app, status -> {
                ready = true;
                main.post(onReady);
            }), "ads-init").start();
        } else if (ready) {
            main.post(onReady);
        }
    }

    static boolean ready() {
        return ready;
    }

    /** Settings shows "Privacy choices" when the user's region needs it. */
    static boolean privacyChoicesNeeded(Context c) {
        return consent(c).getPrivacyOptionsRequirementStatus() == ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED;
    }

    static void showPrivacyChoices(Activity a) {
        UserMessagingPlatform.showPrivacyOptionsForm(a, formError -> {
            if (formError != null) {
                Ui.toast(a, "Privacy choices are not available right now");
            }
        });
    }

    // ---- Before live: the banner ----

    /** Puts a banner in the box (if ads are ready). Returns it, or null. */
    static AdView showBanner(Activity a, FrameLayout box, int widthPx) {
        if (!ready || box == null) {
            return null;
        }
        hideBanner(box);
        DisplayMetrics dm = a.getResources().getDisplayMetrics();
        int widthDp = Math.max(320, Math.min(728, (int) (widthPx / dm.density)));
        AdView v = new AdView(a);
        v.setAdUnitId(testBuild(a) ? TEST_BANNER_ID : BANNER_ID);
        v.setAdSize(AdSize.getLargeAnchoredAdaptiveBannerAdSize(a, widthDp));
        box.addView(v, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.CENTER_HORIZONTAL));
        v.loadAd(new AdRequest.Builder().build());
        return v;
    }

    /** Takes the banner away completely (going live: no ad code runs while you stream). */
    static void hideBanner(FrameLayout box) {
        if (box == null) {
            return;
        }
        for (int i = box.getChildCount() - 1; i >= 0; i--) {
            if (box.getChildAt(i) instanceof AdView) {
                AdView v = (AdView) box.getChildAt(i);
                box.removeViewAt(i);
                v.destroy();
            }
        }
    }

    // ---- After live: the full-screen ad ----

    /** Gets the next "after live" ad ready - only while NOT live (it would take upload speed from the stream). */
    static void prepareAfterLive(Activity a) {
        if (!ready || after != null || loadingAfter) {
            return;
        }
        loadingAfter = true;
        InterstitialAd.load(a, testBuild(a) ? TEST_AFTER_ID : AFTER_ID, new AdRequest.Builder().build(),
                new InterstitialAdLoadCallback() {
                    @Override
                    public void onAdLoaded(@NonNull InterstitialAd ad) {
                        loadingAfter = false;
                        after = ad;
                    }

                    @Override
                    public void onAdFailedToLoad(@NonNull LoadAdError error) {
                        loadingAfter = false;
                        after = null;
                    }
                });
    }

    /** After a stream ended: shows the ad if the stream was long enough and there was none lately. */
    static boolean showAfterLive(Activity a, long streamedMs, Runnable whenClosed) {
        long now = SystemClock.elapsedRealtime();
        if (!ready || after == null || streamedMs < MIN_STREAM_MS || (lastShown > 0 && now - lastShown < GAP_MS)
                || a.isFinishing() || a.isDestroyed()) {
            return false;
        }
        InterstitialAd ad = after;
        after = null;
        ad.setFullScreenContentCallback(new FullScreenContentCallback() {
            @Override
            public void onAdDismissedFullScreenContent() {
                if (whenClosed != null) {
                    whenClosed.run();
                }
            }

            @Override
            public void onAdFailedToShowFullScreenContent(@NonNull AdError adError) {
                if (whenClosed != null) {
                    whenClosed.run();
                }
            }
        });
        lastShown = now;
        ad.show(a);
        return true;
    }
}