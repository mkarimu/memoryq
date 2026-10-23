package com.vstream.broadcaster;

import android.app.PendingIntent;
import android.content.Intent;

import androidx.media3.common.Player;
import androidx.media3.session.MediaSession;
import androidx.media3.session.MediaSessionService;

/**
 * Keeps the radio playing in the background (screen off, other apps open): Android lets a "media playback" service
 * run while sound plays. It also puts the station on the lock screen and in the notifications, with play / pause,
 * and lets headphone buttons, car stereos and watches control it. Tapping the notification opens the Radio screen.
 * The player itself is RadioPlayer's (one for the whole app).
 */
public class RadioService extends MediaSessionService {

    private MediaSession session;

    @Override
    public void onCreate() {
        super.onCreate();
        Intent open = new Intent(this, RadioActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent tap = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        session = new MediaSession.Builder(this, RadioPlayer.player(this))
                .setSessionActivity(tap)
                .build();
        addSession(session);
    }

    @Override
    public MediaSession onGetSession(MediaSession.ControllerInfo controllerInfo) {
        return session;
    }

    /** The app was swiped away: keep playing if the radio is on, otherwise stop. */
    @Override
    public void onTaskRemoved(Intent rootIntent) {
        Player p = session == null ? null : session.getPlayer();
        if (p == null || !p.getPlayWhenReady() || p.getMediaItemCount() == 0) {
            stopSelf();
        }
    }

    @Override
    public void onDestroy() {
        if (session != null) {
            session.release();   // the player stays (it is the app's)
            session = null;
        }
        super.onDestroy();
    }
}