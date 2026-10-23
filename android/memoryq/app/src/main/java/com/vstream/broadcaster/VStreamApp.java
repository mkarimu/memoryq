package com.vstream.broadcaster;

import android.app.Application;

/** Applies the saved theme before any screen opens and starts watching for incoming calls. */
public class VStreamApp extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        registerActivityLifecycleCallbacks(new MiniRadioBar());
        AppTheme.apply(getSharedPreferences("vstream", MODE_PRIVATE).getString("theme", "system"));
        CallMonitor.install(this);
        new Thread(() -> {
            try {
                com.google.android.gms.ads.MobileAds.initialize(this);
            } catch (RuntimeException e) {
                // ads are optional; the app works without them
            }
        }).start();
    }
}