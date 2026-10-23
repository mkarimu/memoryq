package com.vstream.broadcaster;

import androidx.appcompat.app.AppCompatDelegate;

/** Applies the saved theme choice: "system" (follow the phone), "light" or "dark". */
final class AppTheme {

    private AppTheme() {
    }

    static void apply(String mode) {
        int night;
        if ("dark".equals(mode)) {
            night = AppCompatDelegate.MODE_NIGHT_YES;
        } else if ("light".equals(mode)) {
            night = AppCompatDelegate.MODE_NIGHT_NO;
        } else {
            night = AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
        }
        AppCompatDelegate.setDefaultNightMode(night);
    }
}