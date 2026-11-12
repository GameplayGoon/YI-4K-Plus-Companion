package org.yi4kplus.companion;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;

/** Visible user-initiated session, not a hidden always-on background task.
 * Keeps the app's process and camera Wi-Fi awake while connected. Actual socket and
 * live decoder are managed by MainActivity; RTSP is suspended while backgrounded.
 */
public final class CameraConnectionService extends Service {
    private static final String CHANNEL = "yi_camera_link";
    private static final int NOTICE_ID = 701;
    private static final long CPU_LEASE_MILLIS = 10 * 60 * 1000L;
    private static final long CPU_RENEW_MILLIS = 5 * 60 * 1000L;
    private WifiManager.WifiLock wifiLock;
    private PowerManager.WakeLock cpuLock;
    private final Handler wakeHandler = new Handler(Looper.getMainLooper());
    private boolean sessionActive;
    private final Runnable renewCpuLease = new Runnable() {
        @Override public void run() {
            if (!sessionActive) return;
            acquireCpuLease();
            if (sessionActive) wakeHandler.postDelayed(this, CPU_RENEW_MILLIS);
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(
                new NotificationChannel(CHANNEL, "Camera connection", NotificationManager.IMPORTANCE_LOW));
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        Intent open = new Intent(this, MainActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pending = PendingIntent.getActivity(this, 0, open,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26 ?
            new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        Notification note = builder.setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("YI 4K+ camera link")
            .setContentText("Keeping the camera link active · tap to return")
            .setContentIntent(pending).setCategory(Notification.CATEGORY_SERVICE)
            .setOngoing(true).setShowWhen(false).build();
        try {
            startForeground(NOTICE_ID, note, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
            DebugLog.info("Camera connection foreground service started; notification visibility depends on Android permissions");
        } catch (RuntimeException e) {
            DebugLog.warn("Connected-device service could not start: " + e.getClass().getSimpleName());
            stopSelf();
            return START_NOT_STICKY;
        }
        // A foreground notification alone does not keep the CPU running with the
        // screen off. Keep heartbeat and in-progress transfers awake only for the
        // lifetime of this explicit camera session, never as a sticky service.
        sessionActive = true;
        wakeHandler.removeCallbacks(renewCpuLease);
        acquireCpuLease();
        wakeHandler.postDelayed(renewCpuLease, CPU_RENEW_MILLIS);
        try {
            WifiManager manager = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            if (manager != null) {
                if (wifiLock == null) {
                    wifiLock = manager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "yi4kplus:camera_wifi");
                    wifiLock.setReferenceCounted(false);
                }
                if (!wifiLock.isHeld()) wifiLock.acquire();
            }
        } catch (RuntimeException e) {
            // Some phone vendors restrict high-performance Wi-Fi locks. Retain
            // the foreground session even when the optional lock is unavailable.
            DebugLog.warn("Camera Wi-Fi wake lock unavailable: " + e.getClass().getSimpleName());
        }
        return START_NOT_STICKY;
    }

    private void acquireCpuLease() {
        try {
            PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
            if (power != null) {
                if (cpuLock == null) {
                    cpuLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "yi4kplus:camera_cpu");
                    cpuLock.setReferenceCounted(false);
                }
                // A timed, non-reference-counted acquisition renews the lease.
                // If service cleanup is ever missed, it still expires on its own.
                cpuLock.acquire(CPU_LEASE_MILLIS);
            }
        } catch (RuntimeException e) {
            DebugLog.warn("Camera CPU wake lock unavailable: " + e.getClass().getSimpleName());
        }
    }

    @Override public void onDestroy() {
        sessionActive = false;
        wakeHandler.removeCallbacks(renewCpuLease);
        if (wifiLock != null) {
            try { if (wifiLock.isHeld()) wifiLock.release(); } catch (RuntimeException ignored) {}
            wifiLock = null;
        }
        if (cpuLock != null) {
            try { if (cpuLock.isHeld()) cpuLock.release(); } catch (RuntimeException ignored) {}
            cpuLock = null;
        }
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
