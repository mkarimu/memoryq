package com.vstream.broadcaster;

import android.app.Activity;
import android.app.Application;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A slim radio player across the top of every screen while a station is playing (or paused): the station and
 * what is on, play/stop, and close. Tapping it opens the Radio screen. Added to each screen automatically.
 */
final class MiniRadioBar implements Application.ActivityLifecycleCallbacks {

    /** Screens that have their own player or need the whole screen. */
    private static final Set<String> SKIP = new HashSet<String>(Arrays.asList(
            "RadioActivity", "CallActivity", "IncomingCallActivity", "MainActivity"));
    private static final long POLL_MS = 15000;

    private final WeakHashMap<Activity, Bar> bars = new WeakHashMap<Activity, Bar>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService pool = Executors.newSingleThreadExecutor();
    private int resumed = 0;
    private boolean polling = false;

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            if (resumed <= 0 || !RadioPlayer.playing()) {
                polling = false;
                return;
            }
            final long id = RadioPlayer.lastStationId();
            final String host = RadioPlayer.lastHost();
            pool.execute(() -> {
                Api.Result r = Api.get(host, "/api/radio/now?station=" + id, null);
                if (r.code != 200) {
                    return;
                }
                JSONObject j = r.json();
                JSONObject now = j.optJSONObject("now");
                JSONObject talk = j.optJSONObject("talk");
                final String text = talk != null ? "On the mic: " + j.optString("djName", "the DJ")
                        : (now == null ? "" : now.optString("label", now.optString("title", "")));
                handler.post(() -> {
                    if (RadioPlayer.lastStationId() == id) {
                        RadioPlayer.setNowText(text);
                    }
                });
            });
            handler.postDelayed(this, POLL_MS);
        }
    };

    private void startPolling() {
        if (!polling && RadioPlayer.playing()) {
            polling = true;
            handler.post(poll);
        }
    }

    // ---- One bar per screen ----

    private static final class Bar implements RadioPlayer.Listener {
        final Activity activity;
        final ViewGroup content;
        final LinearLayout view;
        final TextView dot;
        final TextView text;
        final TextView toggle;
        final TextView close;
        final int baseTop;
        int shownHeight = 0;
        Runnable onPlay;

        Bar(Activity a, ViewGroup content) {
            this.activity = a;
            this.content = content;
            this.baseTop = content.getPaddingTop();
            float d = a.getResources().getDisplayMetrics().density;
            view = new LinearLayout(a);
            view.setOrientation(LinearLayout.HORIZONTAL);
            view.setGravity(Gravity.CENTER_VERTICAL);
            view.setBackgroundColor(Color.rgb(0x16, 0x20, 0x2a));
            view.setPadding((int) (12 * d), 0, (int) (4 * d), 0);
            view.setElevation(6 * d);
            dot = new TextView(a);
            dot.setText("\u25CF");
            dot.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9);
            dot.setPadding(0, 0, (int) (8 * d), 0);
            text = new TextView(a);
            text.setTextColor(Color.WHITE);
            text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            text.setSingleLine(true);
            text.setEllipsize(TextUtils.TruncateAt.END);
            toggle = button(a, d);
            close = button(a, d);
            close.setText("\u2715");
            close.setTextColor(Color.rgb(0x9f, 0xb0, 0xc0));
            view.addView(dot);
            view.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
            view.addView(toggle);
            view.addView(close);
            text.setGravity(Gravity.CENTER_VERTICAL);
            View.OnClickListener open = v -> activity.startActivity(new Intent(activity, RadioActivity.class));
            text.setOnClickListener(open);
            dot.setOnClickListener(open);
            toggle.setOnClickListener(v -> {
                if (RadioPlayer.playing()) {
                    RadioPlayer.pause();
                } else {
                    RadioPlayer.resume(activity);
                    if (onPlay != null) {
                        onPlay.run();
                    }
                }
            });
            close.setOnClickListener(v -> RadioPlayer.stop());
            view.setVisibility(View.GONE);
            if (content instanceof FrameLayout) {
                content.setClipToPadding(false);
            }
            content.addView(view, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) (36 * d), Gravity.TOP));
        }

        private static TextView button(Activity a, float d) {
            TextView b = new TextView(a);
            b.setTextColor(Color.WHITE);
            b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            b.setGravity(Gravity.CENTER);
            b.setMinWidth((int) (40 * d));
            b.setPadding((int) (8 * d), 0, (int) (8 * d), 0);
            b.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
            return b;
        }

        @Override
        public void onRadioChanged() {
            update();
        }

        void update() {
            boolean show = RadioPlayer.lastStationId() != 0;
            float d = activity.getResources().getDisplayMetrics().density;
            if (!show) {
                if (view.getVisibility() != View.GONE) {
                    view.setVisibility(View.GONE);
                    setTop(0);
                }
                return;
            }
            boolean on = RadioPlayer.playing();
            String now = RadioPlayer.nowText();
            String line = RadioPlayer.lastStationName() + (now.isEmpty() ? "" : "  \u00B7  " + now);
            if (!line.contentEquals(text.getText())) {
                text.setText(line);
            }
            dot.setTextColor(on ? Color.rgb(0xe5, 0x48, 0x4d) : Color.rgb(0x6b, 0x7a, 0x89));
            String t = on ? "\u25A0" : "\u25B6";
            if (!t.contentEquals(toggle.getText())) {
                toggle.setText(t);
            }
            toggle.setContentDescription(on ? "Stop the radio" : "Play the radio");
            close.setContentDescription("Close the radio");
            close.setVisibility(on ? View.GONE : View.VISIBLE);
            int extra = statusBarOverlap();
            int h = (int) (36 * d) + extra;
            ViewGroup.LayoutParams lp = view.getLayoutParams();
            if (lp.height != h) {
                lp.height = h;
                view.setLayoutParams(lp);
            }
            view.setPadding(view.getPaddingLeft(), extra, view.getPaddingRight(), 0);
            view.setVisibility(View.VISIBLE);
            setTop(h);
        }

        /** Pushes the screen down by the bar's height and draws the bar in the space above it. */
        private void setTop(int h) {
            if (h == shownHeight) {
                return;
            }
            shownHeight = h;
            content.setPadding(content.getPaddingLeft(), baseTop + h, content.getPaddingRight(), content.getPaddingBottom());
            view.setTranslationY(-h);
        }

        /** How much of the bar would sit under the phone's status bar (screens drawn edge to edge). */
        private int statusBarOverlap() {
            int[] loc = new int[2];
            content.getLocationOnScreen(loc);
            int id = activity.getResources().getIdentifier("status_bar_height", "dimen", "android");
            int sb = id > 0 ? activity.getResources().getDimensionPixelSize(id) : 0;
            return loc[1] > 0 && loc[1] < sb ? sb - loc[1] : (loc[1] == 0 && content.getHeight() > 0 ? sb : 0);
        }
    }

    // ---- Activity callbacks ----

    @Override
    public void onActivityResumed(Activity a) {
        resumed++;
        if (SKIP.contains(a.getClass().getSimpleName())) {
            return;
        }
        Bar b = bars.get(a);
        if (b == null) {
            View c = a.findViewById(android.R.id.content);
            if (!(c instanceof FrameLayout)) {
                return;
            }
            b = new Bar(a, (ViewGroup) c);
            b.onPlay = this::startPolling;
            bars.put(a, b);
        }
        RadioPlayer.addWatcher(b);
        final Bar bar = b;
        b.content.post(bar::update);
        startPolling();
    }

    @Override
    public void onActivityPaused(Activity a) {
        resumed = Math.max(0, resumed - 1);
        Bar b = bars.get(a);
        if (b != null) {
            RadioPlayer.removeWatcher(b);
        }
    }

    @Override
    public void onActivityDestroyed(Activity a) {
        Bar b = bars.remove(a);
        if (b != null) {
            RadioPlayer.removeWatcher(b);
        }
    }

    @Override
    public void onActivityCreated(Activity a, Bundle b) {
    }

    @Override
    public void onActivityStarted(Activity a) {
    }

    @Override
    public void onActivityStopped(Activity a) {
    }

    @Override
    public void onActivitySaveInstanceState(Activity a, Bundle b) {
    }
}