package com.skyline.weather;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Re-arms the refresh chain after events that silently drop scheduled work: a reboot, an app
 * update (every adb install replaces the package), or a clock/timezone change. Without this the
 * widget only refreshed again once the app was opened by hand.
 */
public class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (action == null) return;
        boolean relevant = Intent.ACTION_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)
                || Intent.ACTION_TIME_CHANGED.equals(action)
                || Intent.ACTION_TIMEZONE_CHANGED.equals(action)
                || Intent.ACTION_DATE_CHANGED.equals(action)
                || "android.intent.action.QUICKBOOT_POWERON".equals(action)      // vivo / HTC fast boot
                || "com.htc.intent.action.QUICKBOOT_POWERON".equals(action);
        if (!relevant) return;
        WeatherRefreshJob.schedule(context);
        WeatherRefreshJob.scheduleNow(context);
        WidgetKeepAliveService.start(context);
    }
}
