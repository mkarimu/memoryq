package com.vstream.broadcaster;

import android.app.Application;

/** Applies the saved theme before any screen opens. */
public class VStreamApp extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        AppTheme.apply(getSharedPreferences("vstream", MODE_PRIVATE).getString("theme", "system"));
    }
}