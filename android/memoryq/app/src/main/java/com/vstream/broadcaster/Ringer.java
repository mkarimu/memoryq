package com.vstream.broadcaster;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Vibrator;

/** Rings and vibrates for an incoming call, following the phone's ringer mode. */
final class Ringer {

    private static MediaPlayer player;
    private static Vibrator vibrator;

    private Ringer() {
    }

    static synchronized void start(Context context) {
        stop();
        AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        int mode = am == null ? AudioManager.RINGER_MODE_NORMAL : am.getRingerMode();
        if (mode == AudioManager.RINGER_MODE_SILENT) {
            return;
        }
        Vibrator v = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
        if (v != null && v.hasVibrator()
                && context.checkSelfPermission(Manifest.permission.VIBRATE) == PackageManager.PERMISSION_GRANTED) {
            vibrator = v;
            v.vibrate(new long[]{0, 700, 1000}, 0);
        }
        if (mode == AudioManager.RINGER_MODE_NORMAL) {
            try {
                Uri uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
                MediaPlayer p = new MediaPlayer();
                p.setDataSource(context, uri);
                p.setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build());
                p.setLooping(true);
                p.prepare();
                p.start();
                player = p;
            } catch (Exception e) {
                player = null;
            }
        }
    }

    static synchronized void stop() {
        if (player != null) {
            try {
                player.stop();
            } catch (IllegalStateException e) {
                // already stopped
            }
            player.release();
            player = null;
        }
        if (vibrator != null) {
            vibrator.cancel();
            vibrator = null;
        }
    }
}