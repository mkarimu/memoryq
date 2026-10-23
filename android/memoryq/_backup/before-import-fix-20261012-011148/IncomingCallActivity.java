package com.vstream.broadcaster;

import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Someone is calling you: caller, type, a countdown, and Accept / Decline. Rings while it is shown. */
public class IncomingCallActivity extends AppCompatActivity {

    static final String EXTRA_ID = "id";
    static final String EXTRA_FROM = "from";
    static final String EXTRA_VIDEO = "video";
    static final String EXTRA_GROUP = "group";
    static final String EXTRA_ROOM_TITLE = "roomTitle";

    private static final long POLL_MS = 2000;

    private SharedPreferences prefs;
    private String callId;
    private TextView countdown;
    private Button acceptBtn;
    private Button declineBtn;
    private boolean finishing = false;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            checkStillRinging();
            handler.postDelayed(this, POLL_MS);
        }
    };

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("vstream", MODE_PRIVATE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        callId = getIntent().getStringExtra(EXTRA_ID);
        String from = getIntent().getStringExtra(EXTRA_FROM);
        boolean video = getIntent().getBooleanExtra(EXTRA_VIDEO, true);
        boolean group = getIntent().getBooleanExtra(EXTRA_GROUP, false);
        String roomTitle = getIntent().getStringExtra(EXTRA_ROOM_TITLE);
        if (callId == null) {
            finish();
            return;
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(24), dp(72), dp(24), dp(40));

        TextView type = new TextView(this);
        type.setText(group ? "Incoming group " + (video ? "video" : "voice") + " call"
                : "Incoming " + (video ? "video" : "voice") + " call");
        type.setTextSize(18);
        type.setGravity(Gravity.CENTER);
        root.addView(type);

        TextView who = new TextView(this);
        who.setText(from == null ? "" : from);
        who.setTextSize(34);
        who.setTypeface(who.getTypeface(), android.graphics.Typeface.BOLD);
        who.setGravity(Gravity.CENTER);
        who.setPadding(0, dp(24), 0, dp(8));
        root.addView(who);

        TextView where = new TextView(this);
        where.setText(roomTitle == null || roomTitle.isEmpty() ? "" : (group ? "in " : "through ") + roomTitle);
        where.setTextSize(16);
        where.setGravity(Gravity.CENTER);
        root.addView(where);

        countdown = new TextView(this);
        countdown.setText("Ringing...");
        countdown.setTextSize(16);
        countdown.setGravity(Gravity.CENTER);
        countdown.setPadding(0, dp(24), 0, 0);
        root.addView(countdown);

        View spacer = new View(this);
        root.addView(spacer, new LinearLayout.LayoutParams(1, 0, 1f));

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        declineBtn = new Button(this);
        declineBtn.setText("Decline");
        declineBtn.setTextColor(0xFFFFFFFF);
        declineBtn.setBackgroundTintList(ColorStateList.valueOf(0xFFC62828));
        declineBtn.setOnClickListener(v -> answer(false));
        acceptBtn = new Button(this);
        acceptBtn.setText("Accept");
        acceptBtn.setTextColor(0xFFFFFFFF);
        acceptBtn.setBackgroundTintList(ColorStateList.valueOf(0xFF2E7D32));
        acceptBtn.setOnClickListener(v -> answer(true));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(dp(8), 0, dp(8), 0);
        buttons.addView(declineBtn, lp);
        buttons.addView(acceptBtn, lp);
        root.addView(buttons, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(root);
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (callId != null) {
            Ringer.start(this);
            handler.post(poll);
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        handler.removeCallbacks(poll);
        Ringer.stop();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Ringer.stop();
        executor.shutdownNow();
        if (callId != null && !finishing) {
            CallMonitor.ringFinished(callId, false);
        }
    }

    @Override
    public void onBackPressed() {
        // Accept or decline; Back does nothing while it is ringing.
    }

    private void checkStillRinging() {
        final String host = prefs.getString("host", "");
        final String session = prefs.getString("session", "");
        try {
            executor.execute(() -> {
                final Api.Result r = Api.get(host, "/api/calls/state?id=" + callId, session);
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed() || finishing) {
                        return;
                    }
                    if (r.code == 404 || r.code == 401) {
                        close(false);
                        return;
                    }
                    if (r.code != 200) {
                        return;
                    }
                    JSONObject call = r.json().optJSONObject("call");
                    if (call == null || call.optBoolean("ended") || !"INVITED".equals(call.optString("myState"))) {
                        close(false);
                        return;
                    }
                    countdown.setText("Ringing... " + call.optInt("ringLeft") + "s");
                });
            });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            // closing
        }
    }

    private void close(boolean dismiss) {
        if (finishing) {
            return;
        }
        finishing = true;
        CallMonitor.ringFinished(callId, dismiss);
        finish();
    }

    private void answer(final boolean accept) {
        acceptBtn.setEnabled(false);
        declineBtn.setEnabled(false);
        Ringer.stop();
        final String host = prefs.getString("host", "");
        final String session = prefs.getString("session", "");
        new Thread(() -> {
            final Api.Result r = Api.post(host, accept ? "/api/calls/accept" : "/api/calls/decline",
                    Api.form("id", callId), session);
            runOnUiThread(() -> {
                if (r.code == 401) {
                    Account.clear(prefs);
                }
                if (accept && r.code == 200) {
                    CallMonitor.callScreenOpened(callId);
                    Intent i = new Intent(this, CallActivity.class);
                    i.putExtra(CallActivity.EXTRA_ID, callId);
                    startActivity(i);
                } else if (r.code != 200) {
                    Toast.makeText(this, r.code == 0 ? "Cannot reach the server" : r.error(), Toast.LENGTH_LONG).show();
                }
                close(false);
            });
        }).start();
    }
}