package com.vstream.broadcaster;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;

import java.util.ArrayList;
import java.util.List;

/**
 * The radio player. One player for the whole app (not just the Radio screen), so the station keeps playing while
 * you use the rest of the app, other apps, or the screen is off: RadioService keeps it alive in the background, with
 * play / pause on the lock screen and in the notifications.
 * It shares the sound with other apps like a music app should: when another app starts playing (YouTube, Spotify)
 * the radio pauses; during a phone call it pauses and comes back after the call; when headphones are pulled out it
 * pauses. After a pause of more than a few seconds it goes back to the live sound (not where it stopped).
 * It remembers the last station, so the slim player at the top of every screen can stop and start it again.
 * Use it from the main thread.
 */
final class RadioPlayer {

    interface Listener {
        void onRadioChanged();
    }

    /** After a longer pause the stream is opened again, so listeners hear what is on now. */
    private static final long BACK_TO_LIVE_MS = 5000;

    private static ExoPlayer player;
    private static Context app;
    /** A station is loaded (playing, or paused by a call / another app); false after pause() and stop(). */
    private static boolean active;
    private static long stationId;
    private static String stationName = "";
    private static String host = "";
    private static String error = "";
    private static Listener listener;
    private static final List<Listener> watchers = new ArrayList<Listener>();
    /** The station the slim player shows (also while paused); 0 = none. */
    private static long lastId;
    private static String lastName = "";
    private static String lastHost = "";
    /** Stops other sound (a live channel being watched) when the radio starts. */
    private static Runnable stopOthers;
    /** What is on now, e.g. "Song - Artist" (filled in by the slim player). */
    private static String nowText = "";
    private static long pausedAt;
    private static long suppressedAt;

    private RadioPlayer() {
    }

    /** The app's radio player (made the first time). RadioService shows it on the lock screen. */
    static ExoPlayer player(Context context) {
        if (player == null) {
            app = context.getApplicationContext();
            AudioAttributes music = new AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build();
            player = new ExoPlayer.Builder(app)
                    .setAudioAttributes(music, true)          // share the sound: pause for calls and other apps
                    .setHandleAudioBecomingNoisy(true)        // headphones out: pause, don't blast the speaker
                    .setWakeMode(C.WAKE_MODE_NETWORK)         // keep playing with the screen off
                    .build();
            player.addListener(new Player.Listener() {
                @Override
                public void onPlayerError(PlaybackException e) {
                    error = "The station stopped playing - tap play to try again.";
                    new Handler(Looper.getMainLooper()).post(RadioPlayer::pause);
                }

                @Override
                public void onIsPlayingChanged(boolean playing) {
                    changed();
                }

                @Override
                public void onPlayWhenReadyChanged(boolean playWhenReady, int reason) {
                    long now = SystemClock.elapsedRealtime();
                    if (!playWhenReady) {
                        pausedAt = now;           // paused from the lock screen, another app, headphones out...
                    } else {
                        if (active && pausedAt > 0 && now - pausedAt > BACK_TO_LIVE_MS) {
                            backToLive();
                        }
                        pausedAt = 0;
                    }
                    changed();
                }

                @Override
                public void onPlaybackSuppressionReasonChanged(int reason) {
                    long now = SystemClock.elapsedRealtime();
                    if (reason == Player.PLAYBACK_SUPPRESSION_REASON_NONE) {
                        // a phone call (or another short sound) is over: carry on, live
                        if (active && suppressedAt > 0 && now - suppressedAt > BACK_TO_LIVE_MS) {
                            backToLive();
                        }
                        suppressedAt = 0;
                    } else if (suppressedAt == 0) {
                        suppressedAt = now;
                    }
                    changed();
                }
            });
        }
        return player;
    }

    /** The Radio screen's listener. */
    static void setListener(Listener l) {
        listener = l;
    }

    /** More listeners (the slim player on each screen). */
    static void addWatcher(Listener l) {
        if (!watchers.contains(l)) {
            watchers.add(l);
        }
    }

    static void removeWatcher(Listener l) {
        watchers.remove(l);
    }

    static long stationId() {
        return active ? stationId : 0;
    }

    static String stationName() {
        return stationName;
    }

    static String error() {
        return error;
    }

    static boolean isPlaying(long id) {
        return active && stationId == id;
    }

    /** Playing, or about to (false when paused - by you, the lock screen, a call or another app). */
    static boolean playing() {
        return active && player != null && player.getPlayWhenReady();
    }

    static long lastStationId() {
        return lastId;
    }

    static String lastStationName() {
        return lastName;
    }

    static String lastHost() {
        return lastHost;
    }

    static String nowText() {
        return nowText;
    }

    static void setNowText(String text) {
        String t = text == null ? "" : text;
        if (!t.equals(nowText)) {
            nowText = t;
            changed();
        }
    }

    /** A screen playing video sets this so the radio can stop it (null when that screen closes). */
    static void setStopOthers(Runnable r) {
        stopOthers = r;
    }

    static void play(Context context, String serverHost, long id, String name) {
        Runnable other = stopOthers;
        if (other != null) {
            other.run();
        }
        error = "";
        stationId = id;
        stationName = name;
        host = serverHost;
        if (id != lastId) {
            nowText = "";
        }
        lastId = id;
        lastName = name;
        lastHost = serverHost;
        ExoPlayer p = player(context);
        active = true;
        pausedAt = 0;
        suppressedAt = 0;
        MediaMetadata info = new MediaMetadata.Builder()
                .setTitle(name)
                .setArtist("Memoryq radio")
                .build();
        p.setMediaItem(new MediaItem.Builder()
                .setUri(Api.base(serverHost) + "/radio/" + id + ".mp3")
                .setMediaMetadata(info)
                .build());
        p.prepare();
        p.play();
        startService(context);
        changed();
    }

    /** Plays the last station again (from the slim player). */
    static void resume(Context context) {
        if (lastId != 0 && !lastHost.isEmpty()) {
            play(context, lastHost, lastId, lastName);
        }
    }

    /** Stops the sound but keeps the slim player, so it can start again. */
    static void pause() {
        active = false;
        if (player != null) {
            player.stop();
        }
        changed();
    }

    /** Stops and forgets the station: the slim player and the notification go away. */
    static void stop() {
        active = false;
        if (player != null) {
            player.stop();
            player.clearMediaItems();
        }
        lastId = 0;
        lastName = "";
        nowText = "";
        if (app != null) {
            try {
                app.stopService(new Intent(app, RadioService.class));
            } catch (RuntimeException e) {
                // not running
            }
        }
        changed();
    }

    /** Opens the stream again, so it plays what is on now. */
    private static void backToLive() {
        if (player == null) {
            return;
        }
        MediaItem current = player.getCurrentMediaItem();
        if (current != null) {
            player.setMediaItem(current);
            player.prepare();
        }
    }

    /** The background service (keeps the radio playing with the screen off, shows the lock-screen controls). */
    private static void startService(Context context) {
        try {
            context.getApplicationContext().startService(new Intent(context.getApplicationContext(), RadioService.class));
        } catch (RuntimeException e) {
            // the app is not in front (cannot happen when a person taps play) - it still plays while the app is open
        }
    }

    private static void changed() {
        if (listener != null) {
            listener.onRadioChanged();
        }
        for (Listener w : new ArrayList<Listener>(watchers)) {
            w.onRadioChanged();
        }
    }
}