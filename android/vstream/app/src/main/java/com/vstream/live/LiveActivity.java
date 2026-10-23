package com.vstream.live;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;

import com.pedro.common.ConnectChecker;
import com.pedro.encoder.input.sources.audio.MicrophoneSource;
import com.pedro.encoder.input.sources.audio.NoAudioSource;
import com.pedro.encoder.input.sources.video.Camera2Source;
import com.pedro.encoder.input.sources.video.NoVideoSource;
import com.pedro.library.rtmp.RtmpStream;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The live screen. Camera preview on the left, a menu on the right: Start / Pause / Stop, Live streams,
 * Switch camera, Mute and Settings. "Live streams" splits the screen in three: your camera (far left),
 * the streams live on Memoryq with yours first (middle; muted while you are live), and the menu (right).
 * By default the stream goes to Memoryq on a free channel picked for this account; with "Use my own server"
 * on, it goes to the user's RTMP server instead.
 */
public class LiveActivity extends AppCompatActivity implements ConnectChecker, SurfaceHolder.Callback {

    private static final int REQUEST_PERMISSIONS = 7;

    private final ExecutorService pool = Executors.newFixedThreadPool(2);
    private final Handler ui = new Handler(Looper.getMainLooper());

    private RtmpStream stream;
    private SurfaceView preview;
    private TextView badge;
    private TextView dest;
    private TextView status;
    private EditText titleField;
    private LinearLayout chips;
    private HorizontalScrollView chipScroll;

    // right-hand menu
    private Button startBtn;
    private Button stopBtn;
    private Button streamsBtn;
    private Button switchBtn;
    private Button muteBtn;
    private Button gearBtn;

    // "Live streams" monitor (middle section)
    private LinearLayout monitor;
    private SurfaceView playerSurface;
    private TextView playerLabel;
    private LinearLayout streamList;
    private TextView monitorNote;
    private ExoPlayer player;
    private String playingId;
    private JSONArray lastChannels = new JSONArray();
    /** Recorded streams (Recent streams); refreshed every 30 seconds while the list is open. */
    private JSONArray recent = new JSONArray();
    private long recentAt;
    private static final String REC = "rec:";

    private boolean surfaceReady;
    private int preparedFor = 0;
    private boolean starting;
    private boolean everConnected;
    private boolean stoppedByUser;
    private boolean retriedKey;
    private boolean muted;
    private boolean paused;
    private boolean toMemoryq;
    private long liveSince;
    /** The "before live" ad box (above the title field); empty while live. */
    private FrameLayout adBox;
    private com.google.android.gms.ads.AdView banner;
    private Button closeBtn;
    /** Set while this screen exists: the service calls it when the app is swiped away from the recent apps. */
    static volatile Runnable stopNow;
    static final String EXTRA_STOP = "stop";

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (stream != null && stream.isStreaming() && everConnected) {
                long s = (System.currentTimeMillis() - liveSince) / 1000;
                badge.setText(String.format(Locale.ROOT, "%s  %d:%02d:%02d", paused ? "\u275A\u275A PAUSED" : "\u25CF LIVE",
                        s / 3600, (s / 60) % 60, s % 60));
                ui.postDelayed(this, 1000);
            }
        }
    };

    private final Runnable pollRestream = new Runnable() {
        @Override
        public void run() {
            if (stream == null || !stream.isStreaming() || !toMemoryq) {
                return;
            }
            String host = Prefs.host(LiveActivity.this);
            String token = Prefs.session(LiveActivity.this);
            pool.execute(() -> {
                Api.Result r = Api.get(host, "/api/restream", token);
                if (r.code == 200) {
                    runOnUiThread(() -> showChips(r.json().optJSONArray("platforms")));
                }
            });
            ui.postDelayed(this, 6000);
        }
    };

    private final Runnable pollChannels = new Runnable() {
        @Override
        public void run() {
            if (monitor.getVisibility() != View.VISIBLE) {
                return;
            }
            String host = Prefs.host(LiveActivity.this);
            pool.execute(() -> {
                Api.Result r = Api.get(host, "/channels", null);
                Api.Result rec = null;
                if (System.currentTimeMillis() - recentAt > 30000) {
                    rec = Api.get(host, "/api/streams/recent", Prefs.session(LiveActivity.this));
                }
                final Api.Result recRes = rec;
                runOnUiThread(() -> {
                    if (recRes != null && recRes.code == 200) {
                        JSONArray ra = recRes.json().optJSONArray("streams");
                        recent = ra == null ? new JSONArray() : ra;
                        recentAt = System.currentTimeMillis();
                    }
                    if (isFinishing() || monitor.getVisibility() != View.VISIBLE) {
                        return;
                    }
                    if (r.code == 200) {
                        JSONArray a = r.json().optJSONArray("channels");
                        lastChannels = a == null ? new JSONArray() : a;
                        monitorNote.setText("");
                    } else {
                        monitorNote.setText(r.error());
                    }
                    drawStreams();
                });
            });
            ui.postDelayed(this, 5000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!Prefs.agreed(this)) {
            startActivity(new Intent(this, AgreementActivity.class));
            finish();
            return;
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        buildUi();
        stream = new RtmpStream(this, this);
        stream.getStreamClient().setReTries(10);
        preview.getHolder().addCallback(this);
        if (!hasPermissions()) {
            askPermissions();
        }
        Ads.start(this, this::adsReady);
        Ui.edgeSafe(this);
        getOnBackPressedDispatcher().addCallback(this, new androidx.activity.OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (busyNow()) {
                    confirmStop();
                } else if (monitor.getVisibility() == View.VISIBLE) {
                    toggleMonitor();
                } else {
                    closeApp();
                }
            }
        });
        stopNow = () -> runOnUiThread(() -> {
            if (busyNow()) {
                stopLive("Stream ended", false);
            }
            finishAndRemoveTask();
        });
        handleStopIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleStopIntent(intent);
    }

    /** "Stop" in the live notification. */
    private void handleStopIntent(Intent intent) {
        if (intent != null && intent.getBooleanExtra(EXTRA_STOP, false)) {
            intent.removeExtra(EXTRA_STOP);
            if (busyNow()) {
                stopLive("Stream ended");
            }
        }
    }

    /** Closes Vstream (ending the stream first, after asking). The camera and microphone go off. */
    private void closeApp() {
        if (busyNow()) {
            new AlertDialog.Builder(this).setTitle("End your live stream and close Vstream?")
                    .setNegativeButton("Keep streaming", null)
                    .setPositiveButton("End and close", (d, w) -> {
                        stopLive("Stream ended", false);
                        finishAndRemoveTask();
                    })
                    .show();
            return;
        }
        finishAndRemoveTask();
    }

    /** Off air the microphone is not used (only the camera picture is shown); it is switched on when you go live. */
    private void micOff() {
        try {
            if (!(stream.getAudioSource() instanceof NoAudioSource)) {
                stream.changeAudioSource(new NoAudioSource());
            }
        } catch (RuntimeException e) {
            // the next preview tries again
        }
    }

    private void micOn() {
        try {
            if (!(stream.getAudioSource() instanceof MicrophoneSource)) {
                MicrophoneSource mic = new MicrophoneSource();
                stream.changeAudioSource(mic);
                if (muted) {
                    mic.mute();
                }
            }
        } catch (RuntimeException e) {
            setStatus("The microphone could not start: " + e.getMessage());
        }
    }

    // ---- Ads: only before and after a stream, never while live ----

    private boolean busyNow() {
        return stream != null && (stream.isStreaming() || starting);
    }

    private void adsReady() {
        if (isFinishing() || isDestroyed() || busyNow()) {
            return;
        }
        showBanner();
        Ads.prepareAfterLive(this);
    }

    private void showBanner() {
        if (!Ads.ready() || banner != null || busyNow()) {
            return;
        }
        adBox.post(() -> {
            if (banner != null || busyNow() || isFinishing() || isDestroyed()) {
                return;
            }
            int w = adBox.getWidth() > 0 ? adBox.getWidth() : getResources().getDisplayMetrics().widthPixels - Ui.dp(this, 112);
            banner = Ads.showBanner(this, adBox, w);
        });
    }

    private void hideBanner() {
        Ads.hideBanner(adBox);
        banner = null;
    }

    // ---- Screen ----

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setBackgroundColor(Color.BLACK);

        // far left: what you are streaming
        FrameLayout stage = new FrameLayout(this);
        preview = new SurfaceView(this);
        stage.addView(preview, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        int p = Ui.dp(this, 10);
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.VERTICAL);
        top.setPadding(p, p, p, 0);
        badge = Ui.text(this, "OFFLINE", 13, Color.WHITE, true);
        badge.setPadding(Ui.dp(this, 10), Ui.dp(this, 4), Ui.dp(this, 10), Ui.dp(this, 4));
        badge.setBackground(Ui.round(this, 0xAA000000, 8, 0));
        top.addView(badge, new LinearLayout.LayoutParams(-2, -2));
        dest = Ui.text(this, "", 13, Color.WHITE, true);
        dest.setShadowLayer(4, 0, 1, Color.BLACK);
        dest.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 4));
        dest.setOnClickListener(v -> chooseDestination());
        status = Ui.text(this, "", 12, 0xFFDDE3EA, false);
        status.setShadowLayer(4, 0, 1, Color.BLACK);
        Ui.add(top, dest, 6);
        top.addView(status);
        stage.addView(top, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));
        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setPadding(p, 0, p, p);
        adBox = new FrameLayout(this);
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(-1, -2);
        ap.bottomMargin = Ui.dp(this, 6);
        bottom.addView(adBox, ap);
        chipScroll = new HorizontalScrollView(this);
        chipScroll.setHorizontalScrollBarEnabled(false);
        chips = new LinearLayout(this);
        chipScroll.addView(chips);
        chipScroll.setVisibility(View.GONE);
        bottom.addView(chipScroll, new LinearLayout.LayoutParams(-2, -2));
        titleField = Ui.field(this, "Stream title (optional)", false);
        titleField.setBackground(Ui.round(this, 0xCC0F1419, 12, 0x55FFFFFF));
        titleField.setText(Prefs.of(this).getString("title", ""));
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-1, -2);
        tp.topMargin = Ui.dp(this, 6);
        bottom.addView(titleField, tp);
        stage.addView(bottom, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        root.addView(stage, new LinearLayout.LayoutParams(0, -1, 1f));

        // middle: the live streams (hidden until "Live streams" is tapped)
        monitor = new LinearLayout(this);
        monitor.setOrientation(LinearLayout.VERTICAL);
        monitor.setBackgroundColor(Ui.BG);
        monitor.setPadding(Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 8), 0);
        FrameLayout pf = new FrameLayout(this);
        pf.setBackgroundColor(Color.BLACK);
        playerSurface = new SurfaceView(this);
        pf.addView(playerSurface, new FrameLayout.LayoutParams(-1, -1));
        playerLabel = Ui.text(this, "", 12, Color.WHITE, true);
        playerLabel.setPadding(Ui.dp(this, 8), Ui.dp(this, 3), Ui.dp(this, 8), Ui.dp(this, 3));
        playerLabel.setBackground(Ui.round(this, 0xAA000000, 6, 0));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
        lp.setMargins(Ui.dp(this, 6), Ui.dp(this, 6), 0, 0);
        pf.addView(playerLabel, lp);
        monitor.addView(pf, new LinearLayout.LayoutParams(-1, 0, 1.1f));
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 2));
        head.addView(Ui.text(this, "Live now", 14, Ui.INK, true), new LinearLayout.LayoutParams(0, -2, 1f));
        monitorNote = Ui.text(this, "", 11, Ui.MUTED, false);
        head.addView(monitorNote);
        monitor.addView(head);
        ScrollView ls = new ScrollView(this);
        streamList = new LinearLayout(this);
        streamList.setOrientation(LinearLayout.VERTICAL);
        ls.addView(streamList);
        monitor.addView(ls, new LinearLayout.LayoutParams(-1, 0, 1f));
        monitor.setVisibility(View.GONE);
        root.addView(monitor, new LinearLayout.LayoutParams(0, -1, 1f));

        // right: the menu
        ScrollView menuScroll = new ScrollView(this);
        menuScroll.setBackgroundColor(0xFF111820);
        menuScroll.setFillViewport(true);
        LinearLayout menu = new LinearLayout(this);
        menu.setOrientation(LinearLayout.VERTICAL);
        menu.setGravity(Gravity.CENTER_HORIZONTAL);
        menu.setPadding(Ui.dp(this, 6), Ui.dp(this, 6), Ui.dp(this, 6), Ui.dp(this, 6));
        menuScroll.addView(menu);
        startBtn = menuButton("\u25CF", "Start");
        startBtn.setOnClickListener(v -> onStartPause());
        stopBtn = menuButton("\u25A0", "Stop");
        stopBtn.setOnClickListener(v -> confirmStop());
        streamsBtn = menuButton("\u25A6", "Live streams");
        streamsBtn.setOnClickListener(v -> toggleMonitor());
        switchBtn = menuButton("\u21C4", "Switch camera");
        switchBtn.setOnClickListener(v -> switchCamera());
        muteBtn = menuButton("\uD83C\uDFA4", "Mute");
        muteBtn.setOnClickListener(v -> toggleMute());
        gearBtn = menuButton("\u2699", "Settings");
        gearBtn.setOnClickListener(v -> openSettings());
        closeBtn = menuButton("\u2715", "Close");
        closeBtn.setOnClickListener(v -> closeApp());
        for (Button b : new Button[] {startBtn, stopBtn, streamsBtn, switchBtn, muteBtn, gearBtn, closeBtn}) {
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, 0, 1f);
            bp.topMargin = Ui.dp(this, 3);
            bp.bottomMargin = Ui.dp(this, 3);
            menu.addView(b, bp);
            b.setMinHeight(Ui.dp(this, 50));
        }
        root.addView(menuScroll, new LinearLayout.LayoutParams(Ui.dp(this, 92), -1));
        setContentView(root);
    }

    private Button menuButton(String glyph, String label) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setTextSize(11);
        b.setPadding(0, Ui.dp(this, 2), 0, Ui.dp(this, 2));
        b.setStateListAnimator(null);
        b.setContentDescription(label);
        setMenu(b, glyph, label, 0xFF223040);
        return b;
    }

    private void setMenu(Button b, String glyph, String label, int color) {
        android.text.SpannableString s = new android.text.SpannableString(glyph + "\n" + label);
        s.setSpan(new android.text.style.RelativeSizeSpan(1.8f), 0, glyph.length(), 0);
        b.setText(s);
        b.setBackground(Ui.round(this, color, 12, 0));
    }

    private void refreshUi() {
        boolean live = stream != null && stream.isStreaming();
        boolean busy = live || starting;
        if (!busy) {
            setMenu(startBtn, "\u25CF", "Start", Ui.LIVE);
        } else if (paused) {
            setMenu(startBtn, "\u25B6", "Resume", Ui.OK);
        } else {
            setMenu(startBtn, "\u275A\u275A", "Pause", 0xFF3A4654);
        }
        startBtn.setEnabled(!starting || paused);
        enable(stopBtn, busy);
        setMenu(stopBtn, "\u25A0", "Stop", busy ? 0xFF5A2328 : 0xFF223040);
        boolean watching = monitor.getVisibility() == View.VISIBLE;
        setMenu(streamsBtn, "\u25A6", watching ? "Hide streams" : "Live streams", watching ? Ui.ACCENT : 0xFF223040);
        enable(switchBtn, !paused);
        setMenu(muteBtn, muted ? "\uD83D\uDD07" : "\uD83C\uDFA4", muted ? "Unmute" : "Mute", muted ? 0xFFB3261E : 0xFF223040);
        enable(muteBtn, !paused);
        enable(gearBtn, !busy);
        titleField.setVisibility(busy ? View.GONE : View.VISIBLE);
        if (busy) {
            hideBanner(); // going live: the ad goes away completely
        } else {
            showBanner();
        }
        if (!busy) {
            badge.setText("OFFLINE");
            badge.setBackground(Ui.round(this, 0xAA000000, 8, 0));
            chipScroll.setVisibility(View.GONE);
            SharedPreferences sp = Prefs.of(this);
            if (Prefs.ownServer(this)) {
                String u = sp.getString("ownUrl", "");
                dest.setText((u.isEmpty() ? "Own server - not set up yet" : "Own server: " + hostOf(u)) + "  \u25BE change");
            } else {
                String ch = sp.getString("channel", "");
                dest.setText((ch.isEmpty() ? "Memoryq - a free channel is picked when you start" : "Memoryq \u00B7 " + ch) + "  \u25BE change");
            }
        } else if (!everConnected) {
            badge.setText("CONNECTING");
            badge.setBackground(Ui.round(this, 0xCCE0A21B, 8, 0));
        } else {
            badge.setBackground(Ui.round(this, paused ? 0xCC3A4654 : Ui.LIVE, 8, 0));
        }
        applyPlayerVolume();
    }

    private static void enable(Button b, boolean on) {
        b.setEnabled(on);
        b.setAlpha(on ? 1f : 0.4f);
    }

    private static String hostOf(String url) {
        String s = url.replaceFirst("^rtmps?://", "");
        int i = s.indexOf('/');
        return i > 0 ? s.substring(0, i) : s;
    }

    private void setStatus(String s) {
        status.setText(s);
    }

    // ---- Live streams (middle section) ----

    private void toggleMonitor() {
        if (monitor.getVisibility() == View.VISIBLE) {
            monitor.setVisibility(View.GONE);
            ui.removeCallbacks(pollChannels);
            releasePlayer();
        } else {
            monitor.setVisibility(View.VISIBLE);
            monitorNote.setText("Loading...");
            recentAt = 0;
            ensurePlayer();
            ui.removeCallbacks(pollChannels);
            ui.post(pollChannels);
        }
        refreshUi();
    }

    private String ownChannel() {
        return toMemoryq || !Prefs.ownServer(this) ? Prefs.of(this).getString("channel", "") : "";
    }

    private boolean isLiveOnServer(String id) {
        for (int i = 0; i < lastChannels.length(); i++) {
            JSONObject c = lastChannels.optJSONObject(i);
            if (c != null && id.equals(c.optString("channelId"))) {
                return true;
            }
        }
        return false;
    }

    /** Your own stream first, then everyone else who is live. */
    private void drawStreams() {
        streamList.removeAllViews();
        String own = ownChannel();
        boolean ownLive = !own.isEmpty() && isLiveOnServer(own);
        String ownText;
        if (Prefs.ownServer(this)) {
            ownText = "You \u00B7 your own server (not shown on Memoryq)";
        } else if (own.isEmpty()) {
            ownText = "You \u00B7 not live yet";
        } else {
            ownText = "You \u00B7 " + own + (ownLive ? "  \u25CF LIVE  (what viewers see, a few seconds behind)" : "  \u00B7 not live");
        }
        streamList.addView(row(ownLive ? own : null, ownText, true));
        List<String> ids = new ArrayList<String>();
        for (int i = 0; i < lastChannels.length(); i++) {
            JSONObject c = lastChannels.optJSONObject(i);
            if (c == null) {
                continue;
            }
            String id = c.optString("channelId");
            ids.add(id);
            if (id.equals(own)) {
                continue;
            }
            String title = c.optString("title", "");
            int viewers = c.optInt("viewers", 0);
            streamList.addView(row(id, (title.isEmpty() ? id : title + " \u00B7 " + id) + "\n\u25CF LIVE \u00B7 " + viewers + " watching", false));
        }
        if (ids.isEmpty() || (ids.size() == 1 && ids.get(0).equals(own))) {
            TextView none = Ui.note(this, "No one else is live right now.");
            none.setPadding(Ui.dp(this, 4), Ui.dp(this, 8), 0, 0);
            streamList.addView(none);
        }
        drawRecent();
        // keep playing what was chosen; start with your own stream; stop if it ended
        if (playingId != null && !playingId.startsWith(REC) && !ids.contains(playingId)) {
            stopPlaying(playingId.equals(own) ? "Your stream is not on Memoryq right now" : playingId + " has ended");
        }
        if (playingId == null && ownLive) {
            play(own);
        }
    }

    /** Recent streams: recordings of the last 30 days - tap to watch, press and hold your own to remove it. */
    private void drawRecent() {
        TextView head = Ui.text(this, "Recent streams", 14, Ui.INK, true);
        head.setPadding(Ui.dp(this, 4), Ui.dp(this, 14), 0, Ui.dp(this, 6));
        streamList.addView(head);
        if (recent.length() == 0) {
            TextView none = Ui.note(this, recentAt == 0 ? "Loading..." : "No recorded streams yet. Streams on Memoryq can be watched again here for 30 days.");
            none.setPadding(Ui.dp(this, 4), 0, 0, 0);
            streamList.addView(none);
            return;
        }
        java.text.DateFormat df = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT);
        for (int i = 0; i < recent.length(); i++) {
            JSONObject r = recent.optJSONObject(i);
            if (r == null) {
                continue;
            }
            String title = r.optString("title", "");
            String user = r.optString("user", "");
            long secs = r.optLong("seconds", 0);
            String when = df.format(new java.util.Date(r.optLong("started", 0)));
            String len = secs >= 3600 ? String.format(Locale.ROOT, "%d:%02d:%02d", secs / 3600, (secs / 60) % 60, secs % 60)
                    : String.format(Locale.ROOT, "%d:%02d", secs / 60, secs % 60);
            String text = (title.isEmpty() ? r.optString("channel") : title) + (user.isEmpty() ? "" : " \u00B7 " + user)
                    + "\n\u25B6 " + when + " \u00B7 " + len + (r.optBoolean("mine") ? " \u00B7 yours (hold to remove)" : "");
            View v = row(REC + r.optString("path"), text, false);
            v.setOnClickListener(x -> playRecording(r));
            if (r.optBoolean("mine")) {
                v.setOnLongClickListener(x -> {
                    removeRecording(r);
                    return true;
                });
            }
            streamList.addView(v);
        }
    }

    private void playRecording(JSONObject r) {
        ensurePlayer();
        playingId = REC + r.optString("path");
        player.setMediaItem(MediaItem.fromUri(Api.base(Prefs.host(this)) + r.optString("url")));
        player.prepare();
        player.play();
        applyPlayerVolume();
        String title = r.optString("title", "");
        playerLabel.setText("Recorded: " + (title.isEmpty() ? r.optString("channel") : title)
                + (isMyStreamOn() ? "  \u00B7 muted while you are live" : ""));
        drawStreams();
    }

    private void removeRecording(JSONObject r) {
        new AlertDialog.Builder(this).setTitle("Remove this recording?")
                .setMessage("It is deleted from Memoryq and no one can watch it again.")
                .setNegativeButton("Keep it", null)
                .setPositiveButton("Remove", (d, w) -> {
                    String host = Prefs.host(this);
                    String token = Prefs.session(this);
                    String path = r.optString("path");
                    pool.execute(() -> {
                        Api.Result res = Api.post(host, "/api/streams/delete", Api.form("path", path), token);
                        runOnUiThread(() -> {
                            if (res.code == 200) {
                                JSONArray ra = res.json().optJSONArray("streams");
                                recent = ra == null ? new JSONArray() : ra;
                                if ((REC + path).equals(playingId)) {
                                    stopPlaying("Recording removed");
                                }
                                drawStreams();
                                Ui.toast(this, "Recording removed");
                            } else {
                                Ui.toast(this, res.error());
                            }
                        });
                    });
                })
                .show();
    }

    private View row(String id, String text, boolean self) {
        TextView t = Ui.text(this, text, 13, Ui.INK, self);
        t.setPadding(Ui.dp(this, 10), Ui.dp(this, 8), Ui.dp(this, 10), Ui.dp(this, 8));
        boolean selected = id != null && id.equals(playingId);
        t.setBackground(Ui.round(this, selected ? 0xFF1F4FB0 : (self ? 0xFF1E2A36 : Ui.PANEL), 10, selected ? Ui.ACCENT : Ui.LINE));
        if (id != null) {
            t.setOnClickListener(v -> play(id));
        } else {
            t.setAlpha(0.7f);
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = Ui.dp(this, 6);
        t.setLayoutParams(lp);
        return t;
    }

    private void ensurePlayer() {
        if (player != null) {
            return;
        }
        player = new ExoPlayer.Builder(this).build();
        player.setVideoSurfaceView(playerSurface);
        player.addListener(new Player.Listener() {
            @Override
            public void onPlayerError(PlaybackException error) {
                playerLabel.setText((playingId == null ? "" : playingId + ": ") + "cannot play (it may have just started - tap it again)");
            }
        });
        applyPlayerVolume();
    }

    private void play(String id) {
        ensurePlayer();
        playingId = id;
        String own = ownChannel();
        player.setMediaItem(MediaItem.fromUri(Api.hls(Prefs.host(this)) + "/live/" + id + "/index.m3u8"));
        player.prepare();
        player.play();
        applyPlayerVolume();
        playerLabel.setText((id.equals(own) ? "You \u00B7 " : "") + id + (isMyStreamOn() ? "  \u00B7 muted while you are live" : ""));
        drawStreams();
    }

    private void stopPlaying(String why) {
        playingId = null;
        if (player != null) {
            player.stop();
        }
        playerLabel.setText(why);
    }

    private boolean isMyStreamOn() {
        return stream != null && (stream.isStreaming() || starting);
    }

    /** Other streams are silent while you are live (so their sound never ends up in your stream). */
    private void applyPlayerVolume() {
        if (player != null) {
            player.setVolume(isMyStreamOn() ? 0f : 1f);
        }
    }

    private void releasePlayer() {
        playingId = null;
        if (player != null) {
            player.release();
            player = null;
        }
        playerLabel.setText("");
    }

    // ---- Permissions and preview ----

    private boolean hasPermissions() {
        return checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    private void askPermissions() {
        String[] perms = Build.VERSION.SDK_INT >= 33
                ? new String[] {Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS}
                : new String[] {Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO};
        requestPermissions(perms, REQUEST_PERMISSIONS);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (hasPermissions()) {
            setStatus("Ready");
            startPreviewIfPossible();
        } else {
            setStatus("Vstream needs the camera and microphone. Allow them in Android Settings - Apps - Vstream.");
        }
    }

    private boolean prepare() {
        int want = Prefs.quality(this);
        if (preparedFor == want) {
            return true;
        }
        int[][] modes = {{1920, 1080, 4500}, {1280, 720, 2500}, {854, 480, 1200}};
        for (int[] m : modes) {
            if (m[1] > want) {
                continue;
            }
            try {
                if (stream.prepareVideo(m[0], m[1], m[2] * 1000) && stream.prepareAudio(44100, true, 128 * 1000)) {
                    preparedFor = want;
                    if (m[1] != want) {
                        setStatus("This phone streams at " + m[1] + "p at most");
                    }
                    return true;
                }
            } catch (IllegalArgumentException e) {
                // try the next size
            }
        }
        return false;
    }

    private void startPreviewIfPossible() {
        if (!surfaceReady || !hasPermissions()) {
            return;
        }
        if (preparedFor != 0 && preparedFor != Prefs.quality(this) && !stream.isStreaming() && stream.isOnPreview()) {
            stream.stopPreview();
        }
        if (!stream.isOnPreview()) {
            if (prepare()) {
                if (!busyNow()) {
                    micOff();
                }
                stream.startPreview(preview);
            } else {
                setStatus("This phone's video encoder could not start");
            }
        }
    }

    @Override
    public void surfaceCreated(@NonNull SurfaceHolder holder) {
        surfaceReady = true;
        startPreviewIfPossible();
    }

    @Override
    public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int width, int height) {
    }

    @Override
    public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
        surfaceReady = false;
        if (stream != null && stream.isOnPreview()) {
            stream.stopPreview(); // the stream itself keeps going while the screen is off
        }
    }

    // ---- Start / Pause / Stop ----

    private void onStartPause() {
        boolean busy = stream.isStreaming() || starting;
        if (!busy) {
            goLive();
        } else if (paused) {
            resume();
        } else if (everConnected) {
            pause();
        }
    }

    private void pause() {
        try {
            stream.changeVideoSource(new NoVideoSource());
            stream.changeAudioSource(new NoAudioSource());
            paused = true;
            setStatus("PAUSED - viewers see a black screen and hear nothing. Tap Resume to continue.");
        } catch (RuntimeException e) {
            setStatus("Cannot pause: " + e.getMessage());
        }
        refreshUi();
    }

    private void resume() {
        try {
            stream.changeVideoSource(new Camera2Source(getApplicationContext()));
            MicrophoneSource mic = new MicrophoneSource();
            stream.changeAudioSource(mic);
            if (muted) {
                mic.mute();
            }
            paused = false;
            setStatus(toMemoryq ? "You are live on memoryq.com" : "You are live on your server");
        } catch (RuntimeException e) {
            setStatus("Cannot resume: " + e.getMessage());
        }
        refreshUi();
    }

    private void goLive() {
        if (!hasPermissions()) {
            askPermissions();
            return;
        }
        if (!prepare()) {
            setStatus("This phone's video encoder could not start");
            return;
        }
        hideKeyboard();
        String title = titleField.getText().toString().trim();
        Prefs.of(this).edit().putString("title", title).apply();
        stoppedByUser = false;
        everConnected = false;
        retriedKey = false;
        paused = false;
        toMemoryq = !Prefs.ownServer(this);
        if (!toMemoryq) {
            SharedPreferences sp = Prefs.of(this);
            String url = sp.getString("ownUrl", "").trim();
            String key = sp.getString("ownKey", "").trim();
            if (!url.matches("(?i)^rtmps?://.+")) {
                chooseDestination();
                return;
            }
            while (url.endsWith("/")) {
                url = url.substring(0, url.length() - 1);
            }
            dest.setText("Own server: " + hostOf(url));
            connect(key.isEmpty() ? url : url + "/" + key, "your own server");
            return;
        }
        if (!Prefs.signedIn(this)) {
            startActivity(new Intent(this, SignInActivity.class));
            finish();
            return;
        }
        starting = true;
        refreshUi();
        setStatus("Getting your channel...");
        fetchKey();
    }

    /** Claims a free channel: "auto" on new servers; on older servers, the first FREE one from /api/pool. */
    private static Api.Result claimFree(String host, String token) {
        Api.Result c = Api.post(host, "/api/claim", Api.form("channel", "auto"), token);
        if (c.code != 400) {
            return c;
        }
        Api.Result list = Api.get(host, "/api/pool", token);
        JSONArray pool = list.json().optJSONArray("pool");
        if (list.code != 200 || pool == null) {
            return list.code == 200 ? c : list;
        }
        Api.Result last = new Api.Result(409, "{\"error\":\"all channels are in use right now - please try again in a few minutes\"}");
        for (int i = 0; i < pool.length(); i++) {
            JSONObject e = pool.optJSONObject(i);
            if (e == null || !"FREE".equals(e.optString("status"))) {
                continue;
            }
            Api.Result r = Api.post(host, "/api/claim", Api.form("channel", e.optString("channelId")), token);
            if (r.code == 200 || r.code == 401) {
                return r;
            }
            last = r;
        }
        return last;
    }

    /** Uses the channel this account already has, or picks a free one. */
    private void fetchKey() {
        String host = Prefs.host(this);
        String token = Prefs.session(this);
        pool.execute(() -> {
            String key = "";
            String channel = "";
            String error = null;
            boolean signedOut = false;
            Api.Result me = Api.get(host, "/api/me", token);
            if (me.code == 401) {
                signedOut = true;
            } else if (me.code != 200) {
                error = me.error();
            } else {
                JSONObject j = me.json();
                JSONObject claim = j.optJSONObject("claim");
                if (!j.optString("streamKey", "").isEmpty()) {
                    key = j.optString("streamKey");
                    channel = j.optString("channelId", "");
                } else if (claim != null && !claim.optString("streamKey", "").isEmpty()) {
                    key = claim.optString("streamKey");
                    channel = claim.optString("channelId", "");
                } else {
                    Api.Result c = claimFree(host, token);
                    if (c.code == 200) {
                        key = c.json().optString("streamKey", "");
                        channel = c.json().optString("channelId", "");
                    } else if (c.code == 401) {
                        signedOut = true;
                    } else {
                        error = c.error();
                    }
                }
            }
            final String k = key;
            final String ch = channel;
            final String err = error;
            final boolean out = signedOut;
            runOnUiThread(() -> {
                if (isFinishing() || !starting) {
                    return;
                }
                if (out) {
                    starting = false;
                    Prefs.signOut(this);
                    Ui.toast(this, "Please sign in again");
                    startActivity(new Intent(this, SignInActivity.class));
                    finish();
                    return;
                }
                if (err != null || k.isEmpty()) {
                    starting = false;
                    setStatus(err != null ? err : "No channel is available right now - please try again in a few minutes");
                    refreshUi();
                    return;
                }
                Prefs.of(this).edit().putString("key", k).putString("channel", ch).apply();
                dest.setText("Memoryq \u00B7 " + ch);
                connect("rtmp://" + Prefs.host(this) + ":1935/live/" + k + titleQuery(k), ch);
            });
        });
    }

    private String titleQuery(String key) {
        String title = Prefs.of(this).getString("title", "");
        if (title.isEmpty()) {
            return "";
        }
        if (title.length() > 40) {
            title = title.substring(0, 40);
        }
        try {
            return (key.contains("?") ? "&" : "?") + "title=" + java.net.URLEncoder.encode(title, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            return "";
        }
    }

    private void connect(String url, String label) {
        starting = true;
        if (!stream.isOnPreview() && surfaceReady) {
            stream.startPreview(preview);
        }
        setStatus("Connecting...");
        Intent i = new Intent(this, StreamService.class);
        i.putExtra(StreamService.EXTRA_LABEL, label);
        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(i);
        } else {
            startService(i);
        }
        micOn();
        stream.startStream(url);
        starting = false;
        refreshUi();
    }

    private void confirmStop() {
        if (stream == null || !(stream.isStreaming() || starting)) {
            return;
        }
        new AlertDialog.Builder(this).setTitle("End your live stream?")
                .setNegativeButton("Keep streaming", null)
                .setPositiveButton("End stream", (d, w) -> stopLive("Stream ended"))
                .show();
    }

    private void stopLive(String why) {
        stopLive(why, true);
    }

    private void stopLive(String why, boolean ads) {
        long streamed = everConnected && liveSince > 0 ? System.currentTimeMillis() - liveSince : 0;
        boolean endedByYou = "Stream ended".equals(why);
        liveSince = 0;
        stoppedByUser = true;
        starting = false;
        everConnected = false;
        ui.removeCallbacks(tick);
        ui.removeCallbacks(pollRestream);
        stopService(new Intent(this, StreamService.class));
        if (stream != null && stream.isStreaming()) {
            stream.stopStream();
        }
        if (paused) {
            try {
                stream.changeVideoSource(new Camera2Source(getApplicationContext()));
            } catch (RuntimeException e) {
                // the preview comes back when the app is reopened
            }
            paused = false;
        }
        micOff(); // off air: the microphone is released
        setStatus(why);
        refreshUi();
        if (!ads) {
            return;
        }
        // after a stream you ended (of at least a minute): the "after live" ad, then the next one is made ready
        ui.postDelayed(() -> {
            if (busyNow()) {
                return;
            }
            if (!(endedByYou && Ads.showAfterLive(this, streamed, () -> Ads.prepareAfterLive(this)))) {
                Ads.prepareAfterLive(this);
            }
        }, 700);
    }

    private void switchCamera() {
        try {
            Object source = stream.getVideoSource();
            if (source instanceof Camera2Source) {
                ((Camera2Source) source).switchCamera();
            }
        } catch (RuntimeException e) {
            setStatus("Cannot switch camera: " + e.getMessage());
        }
    }

    private void toggleMute() {
        try {
            Object source = stream.getAudioSource();
            if (source instanceof MicrophoneSource) {
                MicrophoneSource mic = (MicrophoneSource) source;
                if (muted) {
                    mic.unMute();
                } else {
                    mic.mute();
                }
                muted = !muted;
                setStatus(muted ? "Microphone off - viewers hear nothing" : "Microphone on");
            } else {
                muted = !muted; // not live yet: remembered for when you go live
                setStatus(muted ? "You will go live with the microphone off" : "You will go live with the microphone on");
                refreshUi();
            }
        } catch (RuntimeException e) {
            setStatus("Cannot change the microphone: " + e.getMessage());
        }
        refreshUi();
    }

    private void showChips(JSONArray platforms) {
        chips.removeAllViews();
        if (platforms == null) {
            chipScroll.setVisibility(View.GONE);
            return;
        }
        int shown = 0;
        for (int i = 0; i < platforms.length(); i++) {
            JSONObject p = platforms.optJSONObject(i);
            if (p == null || !p.optBoolean("on", false)) {
                continue;
            }
            String state = p.optString("state", "");
            String label = p.optString("label", p.optString("platform"));
            int color = state.equals("live") ? Ui.OK : state.startsWith("failed") ? Ui.LIVE : Ui.WARN;
            String text = label + ": " + (state.isEmpty() ? "waiting" : state.equals("live") ? "live" : Ui.mask(state));
            if (text.length() > 60) {
                text = text.substring(0, 57) + "...";
            }
            TextView chip = Ui.text(this, text, 12, Color.WHITE, true);
            chip.setPadding(Ui.dp(this, 10), Ui.dp(this, 5), Ui.dp(this, 10), Ui.dp(this, 5));
            chip.setBackground(Ui.round(this, (color & 0x00FFFFFF) | 0xDD000000, 14, 0));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.rightMargin = Ui.dp(this, 6);
            chips.addView(chip, lp);
            shown++;
        }
        chipScroll.setVisibility(shown > 0 ? View.VISIBLE : View.GONE);
    }

    /** Tap the destination line: switch between Memoryq and your own server at any time (not while live). */
    private void chooseDestination() {
        if (stream != null && (stream.isStreaming() || starting)) {
            Ui.toast(this, "End the stream to change where it goes");
            return;
        }
        boolean own = Prefs.ownServer(this);
        String url = Prefs.of(this).getString("ownUrl", "");
        String[] items = {"Memoryq (free channel, default)", url.isEmpty() ? "My own RTMP server (set it up)" : "My own server: " + hostOf(url)};
        new AlertDialog.Builder(this).setTitle("Stream to")
                .setSingleChoiceItems(items, own ? 1 : 0, (d, which) -> {
                    d.dismiss();
                    if (which == 0) {
                        Prefs.of(this).edit().putBoolean("ownOn", false).apply();
                        if (!Prefs.signedIn(this)) {
                            startActivity(new Intent(this, SignInActivity.class));
                            finish();
                            return;
                        }
                        setStatus("Streaming to Memoryq");
                        refreshUi();
                    } else if (url.matches("(?i)^rtmps?://.+")) {
                        Prefs.of(this).edit().putBoolean("ownOn", true).apply();
                        setStatus("Streaming to your own server");
                        refreshUi();
                    } else {
                        Prefs.of(this).edit().putBoolean("ownOn", true).apply();
                        openSettings();
                    }
                })
                .setNegativeButton("Cancel", null).show();
    }

    private void openSettings() {
        if (stream != null && (stream.isStreaming() || starting)) {
            Ui.toast(this, "End the stream to change settings");
            return;
        }
        startActivity(new Intent(this, SettingsActivity.class));
    }

    private void hideKeyboard() {
        InputMethodManager im = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (im != null) {
            im.hideSoftInputFromWindow(titleField.getWindowToken(), 0);
        }
        titleField.clearFocus();
    }

    // ---- ConnectChecker (background threads) ----

    @Override
    public void onConnectionStarted(@NonNull String url) {
    }

    @Override
    public void onConnectionSuccess() {
        runOnUiThread(() -> {
            everConnected = true;
            liveSince = System.currentTimeMillis();
            setStatus(toMemoryq ? "You are live on memoryq.com" : "You are live on your server");
            refreshUi();
            ui.removeCallbacks(tick);
            ui.post(tick);
            ui.removeCallbacks(pollRestream);
            if (toMemoryq) {
                ui.postDelayed(pollRestream, 4000);
            }
            if (monitor.getVisibility() == View.VISIBLE) {
                ui.removeCallbacks(pollChannels);
                ui.postDelayed(pollChannels, 3000); // your own stream appears at the top of the list
            }
        });
    }

    @Override
    public void onConnectionFailed(@NonNull String reason) {
        runOnUiThread(() -> {
            if (stoppedByUser) {
                return;
            }
            if (everConnected && stream.getStreamClient().reTry(5000L, reason, null)) {
                setStatus("Connection lost - reconnecting...");
                return;
            }
            if (!everConnected && toMemoryq && !retriedKey) {
                retriedKey = true; // the saved key may have expired: ask for a fresh one once
                if (stream.isStreaming()) {
                    stream.stopStream();
                }
                Prefs.of(this).edit().remove("key").apply();
                starting = true;
                setStatus("Getting a fresh stream key...");
                fetchKey();
                return;
            }
            stopLive("Could not connect: " + Ui.mask(reason));
        });
    }

    @Override
    public void onNewBitrate(long bitrate) {
    }

    @Override
    public void onDisconnect() {
        runOnUiThread(() -> {
            if (!stoppedByUser && !starting && !stream.isStreaming()) {
                stopLive("Disconnected");
            }
        });
    }

    @Override
    public void onAuthError() {
        runOnUiThread(() -> stopLive(toMemoryq ? "Memoryq did not accept the stream key - sign out and in again"
                : "Your server did not accept the stream key - check it in Settings"));
    }

    @Override
    public void onAuthSuccess() {
    }

    // ---- Lifecycle ----

    @Override
    protected void onPause() {
        if (banner != null) {
            banner.pause();
        }
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (stream == null) {
            return;
        }
        if (banner != null) {
            banner.resume();
        }
        if (!stream.isStreaming() && !starting) {
            if (!Prefs.ownServer(this) && !Prefs.signedIn(this)) {
                startActivity(new Intent(this, SignInActivity.class));
                finish();
                return;
            }
            startPreviewIfPossible();
        }
        if (monitor.getVisibility() == View.VISIBLE) {
            ensurePlayer();
            ui.removeCallbacks(pollChannels);
            ui.post(pollChannels);
        }
        refreshUi();
    }

    @Override
    protected void onStop() {
        super.onStop();
        // watching others is only for while the screen is open; your own stream keeps going
        ui.removeCallbacks(pollChannels);
        if (player != null) {
            releasePlayer();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopNow = null;
        ui.removeCallbacksAndMessages(null);
        hideBanner();
        pool.shutdownNow();
        releasePlayer();
        stopService(new Intent(this, StreamService.class));
        if (stream != null) {
            if (stream.isStreaming()) {
                stream.stopStream();
            }
            if (stream.isOnPreview()) {
                stream.stopPreview();
            }
        }
    }
}