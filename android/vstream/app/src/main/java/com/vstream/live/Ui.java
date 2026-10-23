package com.vstream.live;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/** The app draws its screens in code; these are the shared pieces and colours. */
final class Ui {

    /**
     * Keeps the screen clear of the phone's own bars (status bar, navigation buttons or gesture bar, camera cut-out)
     * and of the keyboard. New Android versions draw apps under them ("edge to edge"), which hid the right-hand menu.
     */
    static void edgeSafe(Activity a) {
        View content = a.findViewById(android.R.id.content);
        if (content == null) {
            return;
        }
        content.setOnApplyWindowInsetsListener((v, insets) -> {
            int l, t, r, b;
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(android.view.WindowInsets.Type.systemBars()
                        | android.view.WindowInsets.Type.displayCutout());
                android.graphics.Insets ime = insets.getInsets(android.view.WindowInsets.Type.ime());
                l = bars.left;
                t = bars.top;
                r = bars.right;
                b = Math.max(bars.bottom, ime.bottom);
            } else {
                l = insets.getSystemWindowInsetLeft();
                t = insets.getSystemWindowInsetTop();
                r = insets.getSystemWindowInsetRight();
                b = insets.getSystemWindowInsetBottom();
            }
            v.setPadding(l, t, r, b);
            return insets;
        });
        content.requestApplyInsets();
    }

    static final int BG = Color.parseColor("#0F1419");
    static final int PANEL = Color.parseColor("#182029");
    static final int LINE = Color.parseColor("#2A3542");
    static final int INK = Color.parseColor("#E6EBF0");
    static final int MUTED = Color.parseColor("#8B9AA8");
    static final int ACCENT = Color.parseColor("#2F6FED");
    static final int LIVE = Color.parseColor("#E53935");
    static final int OK = Color.parseColor("#2E9E5B");
    static final int WARN = Color.parseColor("#E0A21B");

    private Ui() {
    }

    static int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics()));
    }

    static GradientDrawable round(Context c, int fill, float radiusDp, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(c, radiusDp));
        if (stroke != 0) {
            g.setStroke(dp(c, 1), stroke);
        }
        return g;
    }

    static TextView text(Context c, String s, float sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) {
            t.setTypeface(Typeface.DEFAULT_BOLD);
        }
        return t;
    }

    /** The Vstream logo (the app icon, rounded) with the name next to it - for the welcome and sign-in screens. */
    static LinearLayout logo(Context c) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        android.widget.ImageView img = new android.widget.ImageView(c);
        img.setImageResource(R.drawable.vstream_logo);
        img.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
        img.setBackground(round(c, PANEL, 14, 0));
        img.setClipToOutline(true);
        img.setContentDescription("Vstream");
        int s = dp(c, 64);
        row.addView(img, new LinearLayout.LayoutParams(s, s));
        TextView name = text(c, "Vstream", 30, INK, true);
        name.setPadding(dp(c, 14), 0, 0, 0);
        row.addView(name);
        return row;
    }

    static TextView title(Context c, String s) {
        TextView t = text(c, s, 24, INK, true);
        t.setPadding(0, dp(c, 8), 0, dp(c, 4));
        return t;
    }

    static TextView section(Context c, String s) {
        TextView t = text(c, s.toUpperCase(java.util.Locale.ROOT), 12, MUTED, true);
        t.setLetterSpacing(0.08f);
        t.setPadding(dp(c, 4), dp(c, 22), 0, dp(c, 8));
        return t;
    }

    static TextView note(Context c, String s) {
        TextView t = text(c, s, 13, MUTED, false);
        t.setLineSpacing(0, 1.15f);
        return t;
    }

    static Button button(Context c, String s, int color) {
        Button b = new Button(c);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setBackground(round(c, color, 12, 0));
        b.setMinHeight(dp(c, 50));
        b.setStateListAnimator(null);
        return b;
    }

    static Button flat(Context c, String s) {
        Button b = new Button(c);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextColor(ACCENT);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        b.setBackgroundColor(Color.TRANSPARENT);
        b.setStateListAnimator(null);
        b.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        b.setPadding(dp(c, 4), 0, dp(c, 4), 0);
        return b;
    }

    static EditText field(Context c, String hint, boolean secret) {
        EditText e = new EditText(c);
        e.setHint(hint);
        e.setSingleLine(true);
        e.setTextColor(INK);
        e.setHintTextColor(MUTED);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        e.setBackground(round(c, BG, 10, LINE));
        e.setPadding(dp(c, 14), dp(c, 12), dp(c, 14), dp(c, 12));
        e.setInputType(secret ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD
                : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        return e;
    }

    static LinearLayout card(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setBackground(round(c, PANEL, 16, LINE));
        l.setPadding(dp(c, 16), dp(c, 14), dp(c, 16), dp(c, 14));
        return l;
    }

    static LinearLayout.LayoutParams wide(Context c, int topDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        p.topMargin = dp(c, topDp);
        return p;
    }

    static void add(LinearLayout parent, View v, int topDp) {
        parent.addView(v, wide(parent.getContext(), topDp));
    }

    static void toast(Context c, String s) {
        Toast.makeText(c, s, Toast.LENGTH_LONG).show();
    }

    /** Opens one part of the terms and policies (an anchor in the bundled legal page). */
    static void openDoc(Activity a, String anchor) {
        Intent i = new Intent(a, DocActivity.class);
        i.putExtra(DocActivity.EXTRA_ANCHOR, anchor);
        a.startActivity(i);
    }

    static void openWeb(Context c, String url) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
        } catch (Exception e) {
            toast(c, "No app can open " + url);
        }
    }

    /** Hides stream keys and tokens if they ever appear in an error message. */
    static String mask(String s) {
        if (s == null) {
            return "";
        }
        return s.replaceAll("token=[^&\\s]+", "token=****").replaceAll("(rtmps?://[^\\s/]+(?:/[^\\s/]+)?)/[^\\s]+", "$1/****");
    }
}