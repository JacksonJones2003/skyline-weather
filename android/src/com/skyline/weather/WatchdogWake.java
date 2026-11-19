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

    static final String RAIN_TEST = "com.skyline.weather.RAIN_TEST";

    @Override public void onReceive(Context context, Intent intent) {
        // testing aid: run the rain check now (at the given spot, or the widget's) and write what it found to fetch.log;
        // it only reads public radar and, if asked, posts this app's own notification
        if (intent != null && RAIN_TEST.equals(intent.getAction())) {
            final Context c = context.getApplicationContext();
            final float lat = intent.getFloatExtra("lat", Float.NaN), lon = intent.getFloatExtra("lon", Float.NaN);
            final boolean notify = intent.getBooleanExtra("notify", false);
            final PendingResult pr = goAsync();
            new Thread(() -> {
                try {
                    if (Float.isNaN(lat)) { android.content.SharedPreferences sp = c.getSharedPreferences(WeatherWidget.PREFS, Context.MODE_PRIVATE);
                        RainAlert.test(c, sp.getFloat("lat", 999), sp.getFloat("lon", 999), notify); }
                    else RainAlert.test(c, lat, lon, notify);
                } finally { pr.finish(); }
            }).start();
            return;
        }
        WeatherRefreshJob.schedule(context);
        WidgetKeepAliveService.start(context);
    }
}
