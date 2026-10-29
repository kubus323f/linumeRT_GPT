/* SPDX-License-Identifier: GPL-2.0-or-later */
package pl.jakubzietarski.linumert.mobile;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Foreground process host for the *already verified* loopback-only simulation server.
 * The Activity starts WebHMI and verifies /api/health and authenticated /api/state.
 * This service does not automatically install runtimes, issue CNC commands or restart
 * the server after Android kills the process.
 */
public final class LocalServerService extends Service {
    static final String ACTION_KEEP_ALIVE = "pl.jakubzietarski.linumert.mobile.KEEP_ALIVE";
    static final String ACTION_STOP = "pl.jakubzietarski.linumert.mobile.STOP_LOCAL";
    private static final String CHANNEL = "linumert_local_webhmi";
    private static final int NOTIFICATION_ID = 8765;
    private final AtomicBoolean stopping = new AtomicBoolean(false);

    static void retain(Context context) {
        Intent intent = new Intent(context, LocalServerService.class);
        intent.setAction(ACTION_KEEP_ALIVE);
        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent);
        else context.startService(intent);
    }

    static void stop(Context context) {
        Intent intent = new Intent(context, LocalServerService.class);
        intent.setAction(ACTION_STOP);
        context.startService(intent);
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!BuildConfig.LOCAL_WEBHMI_ENABLED) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        String action = intent == null ? "" : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            if (stopping.compareAndSet(false, true)) {
                new Thread(() -> {
                    try {
                        LocalWebHmiClient.stop();
                    } finally {
                        stopForeground(STOP_FOREGROUND_REMOVE);
                        stopSelf(startId);
                    }
                }, "linumert-local-stop").start();
            }
            return START_NOT_STICKY;
        }
        if (stopping.get()) return START_NOT_STICKY;

        NotificationManager manager =
                (NotificationManager)getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(CHANNEL,
                    "linumeRT: lokalna symulacja", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Serwer WebHMI tylko na tym telefonie");
            manager.createNotificationChannel(channel);
        }
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent openIntent = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Intent stop = new Intent(this, LocalServerService.class).setAction(ACTION_STOP);
        PendingIntent stopIntent = PendingIntent.getService(this, 1, stop,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        Notification notification = builder
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("linumeRT — lokalny serwer symulacji")
                .setContentText("127.0.0.1:8765 · Bez sterowania fizyczną maszyną")
                .setContentIntent(openIntent)
                .addAction(android.R.drawable.ic_media_pause, "Zatrzymaj", stopIntent)
                .setOngoing(true).build();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
        return START_NOT_STICKY;
    }
}
