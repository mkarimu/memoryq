package com.vstream.broadcaster;

import android.Manifest;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONArray;
import org.json.JSONObject;
import org.webrtc.RendererCommon;
import org.webrtc.SurfaceViewRenderer;
import org.webrtc.VideoTrack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * The call screen: who is in the call, whether it is ringing or connected, a timer, Add people and Leave. Anyone
 * in the call can add people; with three or more it is a group call. The person who started it can also end it
 * for everyone. Sound and picture go through the media server (see {@link CallMedia}).
 */
public class CallActivity extends AppCompatActivity implements CallMedia.Listener {

    static final String EXTRA_ID = "id";
    private static final long POLL_MS = 2000;

    private SharedPreferences prefs;
    private String callId;
    private TextView titleView;
    private TextView statusView;
    private TextView timerView;
    private TextView peopleView;
    private Button leaveBtn;
    private Button endBtn;
    private Button addBtn;
    private boolean video = true;
    private boolean amIn = false;
    private final Set<String> inCall = new HashSet<>();
    private boolean ended = false;
    private boolean leaving = false;
    private boolean amInitiator = false;
    private boolean group = false;
    private int joinedCount = 0;
    private long connectedAt = 0;
    private boolean polling = false;
    private static final int PERMS = 41;
    private CallMedia media;
    private boolean askedPermission = false;
    private boolean mediaAllowed = false;
    private JSONObject lastState;
    private TextView noteView;
    private LinearLayout mediaRow;
    private Button muteBtn;
    private Button speakerBtn;
    private Button camBtn;
    private Button flipBtn;
    private FrameLayout videoArea;
    private LinearLayout remoteBox;
    private SurfaceViewRenderer localView;
    private VideoTrack localTrack;
    private final Map<String, SurfaceViewRenderer> remoteViews = new HashMap<>();
    private final Map<String, VideoTrack> remoteTracks = new HashMap<>();
    private final Map<String, Boolean> hearing = new HashMap<>();
    private ScrollView peopleScroll;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            fetch();
            handler.postDelayed(this, POLL_MS);
        }
    };

    private final Runnable clock = new Runnable() {
        @Override
        public void run() {
            if (connectedAt > 0 && !ended) {
                long s = (SystemClock.elapsedRealtime() - connectedAt) / 1000;
                timerView.setText(String.format(Locale.US, "%02d:%02d", s / 60, s % 60));
            }
            handler.postDelayed(this, 1000);
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
        if (callId == null) {
            finish();
            return;
        }
        CallMonitor.callScreenOpened(callId);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(24), dp(48), dp(24), dp(24));

        titleView = new TextView(this);
        titleView.setTextSize(20);
        titleView.setGravity(Gravity.CENTER);
        root.addView(titleView);

        statusView = new TextView(this);
        statusView.setText("Connecting...");
        statusView.setTextSize(28);
        statusView.setTypeface(statusView.getTypeface(), android.graphics.Typeface.BOLD);
        statusView.setGravity(Gravity.CENTER);
        statusView.setPadding(0, dp(24), 0, dp(4));
        root.addView(statusView);

        timerView = new TextView(this);
        timerView.setTextSize(20);
        timerView.setGravity(Gravity.CENTER);
        root.addView(timerView);

        videoArea = new FrameLayout(this);
        videoArea.setVisibility(View.GONE);
        remoteBox = new LinearLayout(this);
        remoteBox.setOrientation(LinearLayout.VERTICAL);
        videoArea.addView(remoteBox, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(videoArea, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 2f));

        peopleScroll = new ScrollView(this);
        peopleView = new TextView(this);
        peopleView.setTextSize(17);
        peopleView.setPadding(0, dp(24), 0, dp(12));
        peopleScroll.addView(peopleView);
        root.addView(peopleScroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        noteView = new TextView(this);
        noteView.setTextSize(13);
        noteView.setGravity(Gravity.CENTER);
        noteView.setPadding(0, 0, 0, dp(8));
        root.addView(noteView);

        mediaRow = new LinearLayout(this);
        mediaRow.setGravity(Gravity.CENTER);
        muteBtn = smallButton("Mute", v -> {
            if (media != null) {
                media.setMuted(!media.isMuted());
                updateMediaButtons();
            }
        });
        speakerBtn = smallButton("Speaker", v -> {
            if (media != null) {
                media.setSpeaker(!media.isSpeaker());
                updateMediaButtons();
            }
        });
        camBtn = smallButton("Camera off", v -> {
            if (media != null) {
                media.setCameraOn(!media.isCameraOn());
                updateMediaButtons();
            }
        });
        flipBtn = smallButton("Flip", v -> {
            if (media != null) {
                media.switchCamera();
            }
        });
        mediaRow.addView(muteBtn);
        mediaRow.addView(speakerBtn);
        mediaRow.addView(camBtn);
        mediaRow.addView(flipBtn);
        mediaRow.setVisibility(View.GONE);
        root.addView(mediaRow);

        addBtn = new Button(this);
        addBtn.setText("Add people");
        addBtn.setVisibility(View.GONE);
        addBtn.setOnClickListener(v -> addPeople());
        root.addView(addBtn);

        endBtn = new Button(this);
        endBtn.setText("End for everyone");
        endBtn.setVisibility(View.GONE);
        endBtn.setOnClickListener(v -> confirmEnd());
        root.addView(endBtn);

        leaveBtn = new Button(this);
        leaveBtn.setText("Leave call");
        leaveBtn.setTextColor(0xFFFFFFFF);
        leaveBtn.setBackgroundTintList(ColorStateList.valueOf(0xFFC62828));
        leaveBtn.setOnClickListener(v -> leave());
        root.addView(leaveBtn);
        setContentView(root);
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (callId != null) {
            handler.post(clock);
            if (!polling && !ended) {
                polling = true;
                handler.post(poll);
            }
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        handler.removeCallbacks(clock);
        // Keep following the call while it is open (screen off, other app on top) so sound keeps working.
        if (media == null) {
            handler.removeCallbacks(poll);
            polling = false;
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (callId != null) {
            CallMonitor.callScreenClosed(callId);
        }
        stopMedia();
        handler.removeCallbacks(poll);
        executor.shutdownNow();
    }

    // ---- Sound and picture ----

    private Button smallButton(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(13);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(dp(2), 0, dp(2), 0);
        b.setLayoutParams(lp);
        return b;
    }

    private void updateMediaButtons() {
        if (media == null) {
            mediaRow.setVisibility(View.GONE);
            return;
        }
        mediaRow.setVisibility(View.VISIBLE);
        muteBtn.setText(media.isMuted() ? "Unmute" : "Mute");
        speakerBtn.setText(media.isSpeaker() ? "Earpiece" : "Speaker");
        camBtn.setVisibility(video ? View.VISIBLE : View.GONE);
        flipBtn.setVisibility(video ? View.VISIBLE : View.GONE);
        camBtn.setText(media.isCameraOn() ? "Camera off" : "Camera on");
    }

    /** Asks for the microphone (and camera) once, then starts sending and receiving. */
    private void ensureMedia() {
        if (media != null || ended || !amIn) {
            return;
        }
        List<String> need = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.RECORD_AUDIO);
        }
        if (video && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.CAMERA);
        }
        if (!need.isEmpty() && !mediaAllowed) {
            if (!askedPermission) {
                askedPermission = true;
                requestPermissions(need.toArray(new String[0]), PERMS);
            }
            return;
        }
        startMedia();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != PERMS) {
            return;
        }
        boolean mic = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        if (!mic) {
            noteView.setText("Microphone not allowed - the others cannot hear you. Allow it in Settings > Apps > Memoryq.");
        }
        if (video && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            noteView.setText(mic ? "Camera not allowed - you are sending sound only." : noteView.getText());
        }
        mediaAllowed = true;
        startMedia();
    }

    private void startMedia() {
        if (media != null || ended) {
            return;
        }
        boolean cam = video && checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
        media = new CallMedia(this, cam, this);
        if (video) {
            videoArea.setVisibility(View.VISIBLE);
            peopleView.setTextSize(14);
            peopleView.setPadding(0, dp(6), 0, dp(6));
        }
        updateMediaButtons();
        if (lastState != null) {
            media.sync(lastState);
        }
    }

    private void stopMedia() {
        if (media != null) {
            media.release();
            media = null;
        }
        for (SurfaceViewRenderer r : remoteViews.values()) {
            r.release();
        }
        remoteViews.clear();
        remoteTracks.clear();
        if (localView != null) {
            if (localTrack != null) {
                localTrack.removeSink(localView);
            }
            localView.release();
            localView = null;
        }
        localTrack = null;
        if (mediaRow != null) {
            mediaRow.setVisibility(View.GONE);
        }
    }

    private SurfaceViewRenderer newRenderer(boolean mirror) {
        SurfaceViewRenderer r = new SurfaceViewRenderer(this);
        r.init(CallMedia.egl().getEglBaseContext(), null);
        r.setMirror(mirror);
        r.setEnableHardwareScaler(true);
        r.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL);
        return r;
    }

    @Override
    public void onRemoteVideo(String key, VideoTrack track) {
        if (isFinishing() || isDestroyed() || media == null) {
            return;
        }
        if (key.isEmpty()) {
            if (localView == null) {
                localView = newRenderer(true);
                localView.setZOrderMediaOverlay(true);
                FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(96), dp(128),
                        Gravity.BOTTOM | Gravity.END);
                lp.setMargins(0, 0, dp(8), dp(8));
                videoArea.addView(localView, lp);
            }
            localTrack = track;
            track.addSink(localView);
            return;
        }
        SurfaceViewRenderer r = remoteViews.get(key);
        if (r == null) {
            r = newRenderer(false);
            remoteViews.put(key, r);
            remoteBox.addView(r, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        }
        VideoTrack old = remoteTracks.put(key, track);
        if (old != null && old != track) {
            try {
                old.removeSink(r);
            } catch (IllegalStateException e) {
                // already gone
            }
        }
        track.addSink(r);
    }

    @Override
    public void onRemoteGone(String key) {
        remoteTracks.remove(key);
        hearing.remove(key);
        SurfaceViewRenderer r = remoteViews.remove(key);
        if (r != null) {
            remoteBox.removeView(r);
            r.release();
        }
    }

    @Override
    public void onPeerLive(String name, boolean live) {
        hearing.put(name, live);
        if (lastState != null) {
            JSONObject call = lastState.optJSONObject("call");
            if (call != null && !ended) {
                render(call);
            }
        }
    }

    @Override
    public void onNote(String text) {
        if (!isFinishing() && !isDestroyed()) {
            noteView.setText(text);
        }
    }

    @Override
    public void onBackPressed() {
        if (ended) {
            finish();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Leave the call?")
                .setNegativeButton("Stay", null)
                .setPositiveButton("Leave", (d, w) -> leave())
                .show();
    }

    // ---- Talking to the server ----

    private void fetch() {
        final String host = prefs.getString("host", "");
        final String session = prefs.getString("session", "");
        try {
            executor.execute(() -> {
                final Api.Result r = Api.get(host, "/api/calls/state?id=" + callId, session);
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) {
                        return;
                    }
                    if (r.code == 401) {
                        Account.clear(prefs);
                        finish();
                    } else if (r.code == 404) {
                        showEnded("This call has ended");
                    } else if (r.code == 200) {
                        JSONObject state = r.json();
                        JSONObject call = state.optJSONObject("call");
                        if (call != null) {
                            lastState = state;
                            render(call);
                            if (!ended) {
                                ensureMedia();
                                if (media != null) {
                                    media.sync(state);
                                }
                            }
                        }
                    }
                });
            });
        } catch (RejectedExecutionException e) {
            // closing
        }
    }

    private static String stateLabel(String state) {
        switch (state) {
            case "JOINED": return "in the call";
            case "INVITED": return "ringing...";
            case "DECLINED": return "declined";
            case "MISSED": return "did not answer";
            case "LEFT": return "left";
            default: return state.toLowerCase(Locale.ROOT);
        }
    }

    private static String reasonLabel(String reason) {
        switch (reason) {
            case "declined": return "Declined";
            case "no answer": return "No answer";
            case "cancelled": return "Cancelled";
            case "nobody joined": return "Nobody joined";
            case "time limit": return "Call time limit reached";
            case "ended by the administrator": return "Ended by the administrator";
            case "calls were turned off": return "Calls were turned off";
            default: return "Call ended";
        }
    }

    private void render(JSONObject call) {
        group = call.optBoolean("group");
        video = call.optBoolean("video");
        String me = prefs.getString("username", "");
        amInitiator = call.optString("initiator").equalsIgnoreCase(me);
        JSONArray parts = call.optJSONArray("participants");
        StringBuilder people = new StringBuilder();
        String other = "";
        joinedCount = 0;
        inCall.clear();
        amIn = "JOINED".equals(call.optString("myState"));
        if (parts != null) {
            for (int i = 0; i < parts.length(); i++) {
                JSONObject p = parts.optJSONObject(i);
                if (p == null) {
                    continue;
                }
                String name = p.optString("name");
                String state = p.optString("state");
                if ("JOINED".equals(state)) {
                    joinedCount++;
                }
                if ("JOINED".equals(state) || "INVITED".equals(state)) {
                    inCall.add(name);
                }
                if (!name.equalsIgnoreCase(me)) {
                    other = name;
                }
                if (people.length() > 0) {
                    people.append('\n');
                }
                people.append("JOINED".equals(state) ? "\u25CF " : "\u25CB ").append(name)
                        .append(name.equalsIgnoreCase(me) ? " (you)" : "").append("  -  ").append(stateLabel(state));
                if ("JOINED".equals(state) && !name.equalsIgnoreCase(me) && media != null) {
                    Boolean h = hearing.get(name.toLowerCase(Locale.ROOT));
                    people.append(h != null && h ? "  \ud83d\udd0a" : "  (connecting sound...)");
                }
                String by = p.optString("by");
                if ("INVITED".equals(state) && !by.isEmpty()) {
                    people.append(" (added by ").append(by.equalsIgnoreCase(me) ? "you" : by).append(')');
                }
            }
        }
        peopleView.setText(people.toString());
        titleView.setText(group
                ? "Group " + (video ? "video" : "voice") + " call  \u00b7  " + inCall.size() + " people"
                : (video ? "Video" : "Voice") + " call" + (other.isEmpty() ? "" : " with " + other));
        if (call.optBoolean("ended")) {
            showEnded(reasonLabel(call.optString("endReason")));
            return;
        }
        if (call.optLong("answeredAt") == 0) {
            statusView.setText(!amInitiator ? "Connecting..." : group ? "Ringing..." : "Calling " + other + "...");
        } else {
            statusView.setText("Connected");
            if (connectedAt == 0) {
                connectedAt = SystemClock.elapsedRealtime();
            }
        }
        endBtn.setVisibility(group && amInitiator && joinedCount > 1 ? View.VISIBLE : View.GONE);
        addBtn.setVisibility(amIn && inCall.size() < CallStarter.maxPeople(video) ? View.VISIBLE : View.GONE);
    }

    private void addPeople() {
        if (ended || !amIn) {
            return;
        }
        int room = CallStarter.maxPeople(video) - inCall.size();
        PeoplePicker.pick(this, prefs, "Add to the call", "Ring", null, new HashSet<>(inCall), room,
                names -> CallStarter.addPeople(this, prefs, callId, names, this::fetch));
    }

    private void showEnded(String text) {
        if (ended) {
            return;
        }
        ended = true;
        handler.removeCallbacks(poll);
        stopMedia();
        statusView.setText(text);
        endBtn.setVisibility(View.GONE);
        addBtn.setVisibility(View.GONE);
        leaveBtn.setText("Close");
        leaveBtn.setOnClickListener(v -> finish());
        handler.postDelayed(() -> {
            if (!isFinishing() && !isDestroyed()) {
                finish();
            }
        }, 2500);
    }

    private void leave() {
        if (leaving) {
            return;
        }
        if (ended) {
            finish();
            return;
        }
        leaving = true;
        leaveBtn.setEnabled(false);
        final String host = prefs.getString("host", "");
        final String session = prefs.getString("session", "");
        new Thread(() -> {
            Api.post(host, "/api/calls/leave", Api.form("id", callId), session);
            runOnUiThread(() -> {
                if (!isFinishing() && !isDestroyed()) {
                    finish();
                }
            });
        }).start();
    }

    private void confirmEnd() {
        new AlertDialog.Builder(this)
                .setTitle("End the call for everyone?")
                .setMessage("Everyone in the call will be disconnected.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("End for everyone", (d, w) -> endForAll())
                .show();
    }

    private void endForAll() {
        final String host = prefs.getString("host", "");
        final String session = prefs.getString("session", "");
        new Thread(() -> {
            final Api.Result r = Api.post(host, "/api/calls/end", Api.form("id", callId), session);
            runOnUiThread(() -> {
                if (r.code != 200) {
                    Toast.makeText(this, r.code == 0 ? "Cannot reach the server" : r.error(), Toast.LENGTH_LONG).show();
                } else {
                    fetch();
                }
            });
        }).start();
    }
}