package com.vstream.live;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

/**
 * Keeps the stream alive while the screen is off or another app is open: a "you are live" notification,
 * the camera/microphone foreground-service type, and CPU and Wi-Fi locks. The encoder stays in LiveActivity.
 */
public class StreamService extends Service {

    static final String EXTRA_LABEL = "label";
    private static final String CHANNEL_ID = "vstream_live";
    private static final int NOTIFICATION_ID = 2001;

    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String label = intent != null ? intent.getStringExtra(EXTRA_LABEL) : null;
        if (label == null || label.isEmpty()) {
            label = "your stream";
        }
        Intent open = new Intent(this, LiveActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pending = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                nm.createNotificationChannel(new NotificationChannel(CHANNEL_ID, "Live stream", NotificationManager.IMPORTANCE_LOW));
            }
            b = new Notification.Builder(this, CHANNEL_ID);
        } else {
            b = new Notification.Builder(this);
        }
        Notification n = b.setSmallIcon(android.R.drawable.presence_video_online)
                .setContentTitle("Vstream is live")
                .setContentText("Camera and microphone are on - streaming to " + label + ". Tap to open.")
                .setContentIntent(pending)
                .addAction(new Notification.Action.Builder(null, "Stop stream", stopPending()).build())
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= 30) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA | ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        } else {
            startForeground(NOTIFICATION_ID, n);
        }
        if (wakeLock == null) {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "vstream:live");
                wakeLock.setReferenceCounted(false);
                wakeLock.acquire(12L * 60 * 60 * 1000);
            }
        }
        if (wifiLock == null) {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                wifiLock = wm.createWifiLock(Build.VERSION.SDK_INT >= 29 ? WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                        : WifiManager.WIFI_MODE_FULL_HIGH_PERF, "vstream:wifi");
                wifiLock.setReferenceCounted(false);
                wifiLock.acquire();
            }
        }
        return START_NOT_STICKY;
    }

    /** "Stop stream" in the notification: opens Vstream and ends the stream there. */
    private PendingIntent stopPending() {
        Intent stop = new Intent(this, LiveActivity.class);
        stop.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        stop.putExtra(LiveActivity.EXTRA_STOP, true);
        return PendingIntent.getActivity(this, 1, stop, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    /** Vstream was swiped away from the recent apps: the stream ends (camera and microphone off). */
    @Override
    public void onTaskRemoved(Intent rootIntent) {
        Runnable r = LiveActivity.stopNow;
        if (r != null) {
            r.run();
        }
        stopSelf();
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        if (wifiLock != null && wifiLock.isHeld()) {
            wifiLock.release();
        }
        if (Build.VERSION.SDK_INT >= 24) {
            stopForeground(Service.STOP_FOREGROUND_REMOVE);
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}