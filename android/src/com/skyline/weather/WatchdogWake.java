package com.skyline.weather;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Opt-in wake-up used by an external watchdog (Shizuku/shell) to revive the keep-alive service
 * after lmkd has killed the process. It only starts our own components, so an exported receiver
 * cannot do anything beyond causing a refresh. The JobScheduler chain stays in place independently.
 */
public class WatchdogWake extends BroadcastReceiver {
    static final String ACTION = "com.skyline.weather.WAKE";

    static final String RAIN_TEST = "com.skyline.weather.RAIN_TEST", ALERT_TEST = "com.skyline.weather.ALERT_TEST", BRIEF_TEST = "com.skyline.weather.BRIEF_TEST";

    @Override public void onReceive(Context context, Intent intent) {
        // testing aid: send today's morning summary now
        if (intent != null && BRIEF_TEST.equals(intent.getAction())) {
            final Context c = context.getApplicationContext(); final PendingResult pr = goAsync();
            new Thread(() -> { try { MorningBrief.test(c); } finally { pr.finish(); } }).start();
            return;
        }
        // testing aid: run the rain check now (at the given spot, or the widget's) and write what it found to fetch.log;
        // it only reads public radar and, if asked, posts this app's own notification
        if (intent != null && (RAIN_TEST.equals(intent.getAction()) || ALERT_TEST.equals(intent.getAction()))) {
            final boolean alerts = ALERT_TEST.equals(intent.getAction());
            final Context c = context.getApplicationContext();
            final float lat = intent.getFloatExtra("lat", Float.NaN), lon = intent.getFloatExtra("lon", Float.NaN);
            final boolean notify = intent.getBooleanExtra("notify", false);
            final PendingResult pr = goAsync();
            new Thread(() -> {
                try {
                    android.content.SharedPreferences sp = c.getSharedPreferences(WeatherWidget.PREFS, Context.MODE_PRIVATE);
                    double la = Float.isNaN(lat) ? sp.getFloat("lat", 999) : lat, lo = Float.isNaN(lat) ? sp.getFloat("lon", 999) : lon;
                    if (alerts) WeatherAlerts.test(c, la, lo, notify); else RainAlert.test(c, la, lo, notify);
                } finally { pr.finish(); }
            }).start();
            return;
        }
        WeatherRefreshJob.schedule(context);
        WidgetKeepAliveService.start(context);
    }
}
