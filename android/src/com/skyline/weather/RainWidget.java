package com.skyline.weather;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.os.Bundle;

/** The "rain in the next 2 hours" home-screen widget; drawn by ExtraWidgets. */
public class RainWidget extends AppWidgetProvider {
    @Override public void onEnabled(Context c) { WeatherRefreshJob.schedule(c); WidgetKeepAliveService.start(c); }
    @Override public void onUpdate(Context c, AppWidgetManager m, int[] ids) { refresh(this, c); }
    @Override public void onAppWidgetOptionsChanged(Context c, AppWidgetManager m, int id, Bundle o) { refresh(this, c); }

    static void refresh(AppWidgetProvider p, Context c) {
        WeatherRefreshJob.schedule(c);
        WidgetKeepAliveService.start(c);
        final PendingResult pr = p.goAsync();
        final Context app = c.getApplicationContext();
        new Thread(() -> { try { ExtraWidgets.update(app, true); } catch (Throwable ignored) { } finally { pr.finish(); } }).start();
    }
}
