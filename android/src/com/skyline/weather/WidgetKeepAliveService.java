package com.skyline.weather;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

/**
 * Holds the widget's refresh loop while the phone would otherwise freeze the app.
 *
 * On this device a cached process has its jobs deferred and its alarm broadcasts parked in
 * INFINITE_DEFER, so no timer fires reliably. A foreground service keeps the process out of the
 * cached state, which makes the timer below (and the JobScheduler/AlarmManager fallbacks) actually
 * run. It shows one silent, minimum-importance notification while it is alive.
 */
public class WidgetKeepAliveService extends Service {
    private static final int NOTIFICATION_ID = 8127;
    private static final String CHANNEL = "widget_refresh";
    private static final long INTERVAL_MS = 15 * 60 * 1000L;

    // The 15-minute timer counts awake time only, so a phone that slept for an hour lands its next
    // tick an hour late. The service keeps this process alive, which lets a runtime receiver catch
    // the screen coming on and refresh right when the widget is actually looked at.
    private final BroadcastReceiver wake = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { refresh("screen", 60 * 1000L); }
    };
    private boolean wakeRegistered;
    private ConnectivityManager.NetworkCallback netCallback;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            // Re-assert the scheduler chain every tick: a package update cancels the persisted job
            // and its MY_PACKAGE_REPLACED rebroadcast can be deferred, which left the app with no
            // wake-up at all once lmkd killed the process.
            try { WeatherRefreshJob.schedule(getApplicationContext()); } catch (Throwable ignored) { }
            refresh("tick", 5 * 60 * 1000L);
            handler.postDelayed(this, INTERVAL_MS);
        }
    };

    // rain alerts check every 5 minutes (RainAlert throttles itself and does nothing when switched off)
    private static final long RAIN_MS = 5 * 60 * 1000L;
    private final Runnable rainTick = new Runnable() {
        @Override public void run() {
            new Thread(() -> {
                try { RainAlert.check(getApplicationContext()); } catch (Throwable ignored) { }
                try { WeatherAlerts.check(getApplicationContext()); } catch (Throwable ignored) { }
                try { MorningBrief.check(getApplicationContext()); } catch (Throwable ignored) { }
            }, "SkylineRain").start();
            handler.postDelayed(this, RAIN_MS);
        }
    };

    private void refresh(String why, long maxAgeMs) {
        new Thread(() -> {
            try { WeatherWidget.update(getApplicationContext(), true, maxAgeMs); }
            catch (Throwable ignored) { }
        }, "SkylineWidget-" + why).start();
    }

    static boolean hasWidgets(Context c) {
        AppWidgetManager m = AppWidgetManager.getInstance(c);
        return m.getAppWidgetIds(new ComponentName(c, WeatherWidget.class)).length > 0;
    }

    // runs for the widget, and for rain alerts even without a widget on the home screen
    static boolean needed(Context c) { return hasWidgets(c) || RainAlert.enabled(c) || WeatherAlerts.enabled(c); }

    static void start(Context c) {
        if (!needed(c)) return;
        Intent i = new Intent(c, WidgetKeepAliveService.class);
        try {
            c.startForegroundService(i);
        } catch (Throwable t) {
            try { c.startService(i); } catch (Throwable ignored) { }
        }
    }

    static void stop(Context c) {
        try { c.stopService(new Intent(c, WidgetKeepAliveService.class)); } catch (Throwable ignored) { }
    }

    @Override public void onCreate() {
        super.onCreate();
        startForeground(NOTIFICATION_ID, buildNotification());
        IntentFilter f = new IntentFilter(Intent.ACTION_SCREEN_ON);
        f.addAction(Intent.ACTION_USER_PRESENT);
        try {
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(wake, f, Context.RECEIVER_NOT_EXPORTED);
            else registerReceiver(wake, f);
            wakeRegistered = true;
        } catch (Throwable ignored) { }
        // A failed fetch used to wait for the next tick even after the network came back; refresh as
        // soon as a validated network appears instead.
        try {
            ConnectivityManager cm = getSystemService(ConnectivityManager.class);
            NetworkRequest req = new NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                    .build();
            netCallback = new ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(Network network) { refresh("network", 60 * 1000L); }
            };
            cm.registerNetworkCallback(req, netCallback);
        } catch (Throwable ignored) { }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (!needed(this)) { stopSelf(); return START_NOT_STICKY; }
        try { WeatherRefreshJob.schedule(this); } catch (Throwable ignored) { }
        try { ShizukuWatchdog.startLoop(); } catch (Throwable ignored) { }
        handler.removeCallbacks(tick);
        handler.post(tick);          // refresh now, then every 15 minutes while alive
        handler.removeCallbacks(rainTick);
        handler.postDelayed(rainTick, 20 * 1000L);   // first rain check shortly after start, then every 5 minutes
        return START_STICKY;
    }

    private Notification buildNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null && Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Widget refresh", NotificationManager.IMPORTANCE_MIN);
            ch.setDescription("Keeps the home screen widget up to date");
            ch.setShowBadge(false);
            ch.setSound(null, null);
            ch.enableVibration(false);
            nm.createNotificationChannel(ch);
        }
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        b.setContentTitle("Skyline Weather")
                .setContentText("Keeping the widget updated")
                .setSmallIcon(R.drawable.ic_stat_blank)
                .setContentIntent(open)
                // Dismissible on purpose: the service is not tied to the notification, so swiping it
                // away (or letting BuzzKill do it) removes the shade entry without stopping refreshes.
                // The system re-posts it from the manifest declaration if the service restarts.
                .setOngoing(false)
                .setAutoCancel(false)
                .setShowWhen(false)
                .setPriority(Notification.PRIORITY_MIN);
        return b.build();
    }

    @Override public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= TRIM_MEMORY_RUNNING_LOW) WeatherWidget.trim();
    }

    @Override public void onDestroy() {
        handler.removeCallbacks(tick);
        handler.removeCallbacks(rainTick);
        if (wakeRegistered) {
            try { unregisterReceiver(wake); } catch (Throwable ignored) { }
            wakeRegistered = false;
        }
        if (netCallback != null) {
            try {
                ConnectivityManager cm = getSystemService(ConnectivityManager.class);
                if (cm != null) cm.unregisterNetworkCallback(netCallback);
            } catch (Throwable ignored) { }
            netCallback = null;
        }
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
