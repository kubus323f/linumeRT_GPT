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
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;

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
    private ConnectivityManager connectivity;
    private ConnectivityManager.NetworkCallback wifiCallback;
    private volatile Network approvedWifiNetwork;

    static void retain(Context context, String wifiIp) {
        Intent intent = new Intent(context, LocalServerService.class);
        intent.setAction(ACTION_KEEP_ALIVE);
        intent.putExtra("wifi_ip", wifiIp == null ? "" : wifiIp);
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
        String approvedWifi = intent == null ? "" : intent.getStringExtra("wifi_ip");
        if (approvedWifi == null) approvedWifi = "";
        Notification notification = builder
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("linumeRT — serwer symulacji działa")
                .setContentText(approvedWifi.isEmpty()
                        ? "Tylko telefon: 127.0.0.1:8765"
                        : "Wi-Fi: " + approvedWifi + ":8765 · HTTP bez szyfrowania")
                .setContentIntent(openIntent)
                .addAction(android.R.drawable.ic_media_pause, "Zatrzymaj", stopIntent)
                .setOngoing(true).build();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
        watchApprovedWifi(approvedWifi);
        return START_NOT_STICKY;
    }

    private void watchApprovedWifi(String ip) {
        unwatchWifi();
        if (ip.isEmpty()) return;
        Network selected = WifiInterface.networkForPrivateIpv4(this, ip);
        if (selected == null) {
            revokeOnNetworkLoss();
            return;
        }
        approvedWifiNetwork = selected;
        connectivity = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (connectivity == null) {
            revokeOnNetworkLoss();
            return;
        }
        final Network approved = selected;
        wifiCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onLost(Network network) {
                if (approved.equals(network)
                        && approved.equals(approvedWifiNetwork)) revokeOnNetworkLoss();
            }

            @Override
            public void onLinkPropertiesChanged(Network network, LinkProperties properties) {
                if (!approved.equals(network) || !approved.equals(approvedWifiNetwork))
                    return;
                boolean stillHasAddress = false;
                for (LinkAddress address : properties.getLinkAddresses()) {
                    if (ip.equals(address.getAddress().getHostAddress())) {
                        stillHasAddress = true;
                        break;
                    }
                }
                if (!stillHasAddress) revokeOnNetworkLoss();
            }
        };
        try {
            connectivity.registerNetworkCallback(new NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), wifiCallback);
        } catch (RuntimeException denied) {
            revokeOnNetworkLoss();
        }
    }

    private void revokeOnNetworkLoss() {
        if (!stopping.compareAndSet(false, true)) return;
        // Fail closed: no listener persists on a different Wi-Fi with the same IP.
        new Thread(() -> {
            try {
                LocalWebHmiClient.stop();
            } catch (RuntimeException ignored) {
                // Process is still stopped as a foreground service; no false PASS.
            } finally {
                stopForeground(STOP_FOREGROUND_REMOVE);
                stopSelf();
            }
        }, "linumert-wifi-revocation").start();
    }

    private void unwatchWifi() {
        approvedWifiNetwork = null;
        if (connectivity != null && wifiCallback != null) {
            try {
                connectivity.unregisterNetworkCallback(wifiCallback);
            } catch (RuntimeException ignored) { }
        }
        wifiCallback = null;
    }

    @Override
    public void onDestroy() {
        unwatchWifi();
        super.onDestroy();
    }
}
