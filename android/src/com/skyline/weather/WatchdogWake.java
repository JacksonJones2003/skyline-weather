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

    @Override public void onReceive(Context context, Intent intent) {
        WeatherRefreshJob.schedule(context);
        WidgetKeepAliveService.start(context);
    }
}
