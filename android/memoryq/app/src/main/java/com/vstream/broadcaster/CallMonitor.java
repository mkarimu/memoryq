package com.vstream.broadcaster;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * Watches for calls while the app is open: asks the server every few seconds whether the calls add-on is on,
 * whether someone is calling you, and whether you are in a call. Opens the incoming-call screen or returns you
 * to a call that is still running. Does nothing while the app is in the background.
 */
final class CallMonitor implements Application.ActivityLifecycleCallbacks {

    private static final long POLL_MS = 3000;
    private static final long FEATURES_MS = 60000;
    private static final long FEATURES_RETRY_MS = 15000;
    private static final long LEFT_GRACE_MS = 10000;

    private static CallMonitor instance;

    private final Application app;
    private final SharedPreferences prefs;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Set<String> dismissed = new HashSet<>();
    private int started = 0;
    private volatile boolean enabled = false;
    private volatile int missed = 0;
    private volatile boolean polling = false;
    private long featuresChecked = 0;
    private String screenId = "";
    private String ringingId = "";
    private String leftId = "";
    private long leftAt = 0;

    private final Runnable loop = new Runnable() {
        @Override
        public void run() {
            if (started <= 0) {
                return;
            }
            pollAsync();
            handler.postDelayed(this, POLL_MS);
        }
    };

    private CallMonitor(Application app) {
        this.app = app;
        this.prefs = app.getSharedPreferences("vstream", Context.MODE_PRIVATE);
    }

    static void install(Application app) {
        if (instance == null) {
            instance = new CallMonitor(app);
            app.registerActivityLifecycleCallbacks(instance);
        }
    }

    static boolean callsEnabled() {
        return instance != null && instance.enabled;
    }

    static int missedCount() {
        return instance == null ? 0 : instance.missed;
    }

    /** The call screen for this call is open (so the monitor must not open another). */
    static void callScreenOpened(String id) {
        if (instance != null) {
            instance.screenId = id;
        }
    }

    static void callScreenClosed(String id) {
        if (instance != null) {
            if (id.equals(instance.screenId)) {
                instance.screenId = "";
            }
            instance.leftId = id;
            instance.leftAt = SystemClock.elapsedRealtime();
        }
    }

    /** The incoming-call screen closed. If it was dismissed, the same call will not pop up again. */
    static void ringFinished(String id, boolean dismiss) {
        if (instance != null) {
            if (id.equals(instance.ringingId)) {
                instance.ringingId = "";
            }
            if (dismiss) {
                if (instance.dismissed.size() > 50) {
                    instance.dismissed.clear();
                }
                instance.dismissed.add(id);
            }
        }
    }

    // ---- Knowing whether the app is in front ----

    @Override
    public void onActivityStarted(Activity activity) {
        started++;
        if (started == 1) {
            handler.removeCallbacks(loop);
            handler.post(loop);
        }
    }

    @Override
    public void onActivityStopped(Activity activity) {
        started--;
        if (started <= 0) {
            started = 0;
            handler.removeCallbacks(loop);
        }
    }

    @Override
    public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
    }

    @Override
    public void onActivityResumed(Activity activity) {
    }

    @Override
    public void onActivityPaused(Activity activity) {
    }

    @Override
    public void onActivitySaveInstanceState(Activity activity, Bundle outState) {
    }

    @Override
    public void onActivityDestroyed(Activity activity) {
    }

    // ---- Asking the server ----

    private void pollAsync() {
        if (polling || !Account.signedIn(prefs)) {
            return;
        }
        final String host = prefs.getString("host", "");
        if (host.isEmpty()) {
            return;
        }
        final String session = prefs.getString("session", "");
        polling = true;
        try {
            executor.execute(() -> {
                try {
                    pollOnce(host, session);
                } finally {
                    polling = false;
                }
            });
        } catch (RejectedExecutionException e) {
            polling = false;
        }
    }

    private void pollOnce(String host, String session) {
        long now = SystemClock.elapsedRealtime();
        long wait = enabled ? FEATURES_MS : FEATURES_RETRY_MS;
        if (featuresChecked == 0 || now - featuresChecked > wait) {
            Api.Result f = Api.get(host, "/api/calls/features", null);
            if (f.code == 200) {
                enabled = f.json().optBoolean("calls", false);
                featuresChecked = now;
            } else if (f.code == 404) {
                enabled = false;
                featuresChecked = now;
            }
        }
        if (!enabled) {
            missed = 0;
            return;
        }
        Api.Result r = Api.get(host, "/api/calls/poll", session);
        if (r.code == 401) {
            Account.clear(prefs);
            return;
        }
        if (r.code == 404) {
            enabled = false;
            featuresChecked = now;
            missed = 0;
            return;
        }
        if (r.code != 200) {
            return;
        }
        final JSONObject o = r.json();
        handler.post(new Runnable() {
            @Override
            public void run() {
                handle(o);
            }
        });
    }

    private void handle(JSONObject o) {
        missed = o.optInt("missed", 0);
        JSONObject current = o.optJSONObject("current");
        if (current != null) {
            String id = current.optString("id");
            boolean justLeft = id.equals(leftId) && SystemClock.elapsedRealtime() - leftAt < LEFT_GRACE_MS;
            if (!id.equals(screenId) && !justLeft) {
                openCall(id);
            }
            return;
        }
        JSONArray incoming = o.optJSONArray("incoming");
        if (incoming != null && incoming.length() > 0 && screenId.isEmpty()) {
            JSONObject c = incoming.optJSONObject(0);
            if (c != null) {
                String id = c.optString("id");
                if (!id.equals(ringingId) && !dismissed.contains(id)) {
                    ring(c);
                }
            }
        }
    }

    private void openCall(String id) {
        screenId = id;
        Intent i = new Intent(app, CallActivity.class);
        i.putExtra(CallActivity.EXTRA_ID, id);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        app.startActivity(i);
    }

    private void ring(JSONObject c) {
        ringingId = c.optString("id");
        Intent i = new Intent(app, IncomingCallActivity.class);
        i.putExtra(IncomingCallActivity.EXTRA_ID, ringingId);
        i.putExtra(IncomingCallActivity.EXTRA_FROM, c.optString("initiator"));
        i.putExtra(IncomingCallActivity.EXTRA_VIDEO, c.optBoolean("video"));
        i.putExtra(IncomingCallActivity.EXTRA_GROUP, c.optBoolean("group"));
        i.putExtra(IncomingCallActivity.EXTRA_ROOM_TITLE, c.optString("roomTitle"));
        String by = c.optString("invitedBy");
        String me = prefs.getString("username", "");
        StringBuilder with = new StringBuilder();
        JSONArray parts = c.optJSONArray("participants");
        if (parts != null) {
            for (int k = 0; k < parts.length(); k++) {
                JSONObject p = parts.optJSONObject(k);
                if (p == null || !"JOINED".equals(p.optString("state"))) {
                    continue;
                }
                String n = p.optString("name");
                if (n.equalsIgnoreCase(by) || n.equalsIgnoreCase(me)) {
                    continue;
                }
                if (with.length() > 0) {
                    with.append(", ");
                }
                with.append(n);
            }
        }
        i.putExtra(IncomingCallActivity.EXTRA_BY, by);
        i.putExtra(IncomingCallActivity.EXTRA_WITH, with.toString());
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        app.startActivity(i);
    }
}