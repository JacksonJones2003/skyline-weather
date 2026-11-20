package com.skyline.weather;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.os.Bundle;

/** The five-day forecast home-screen widget; drawn by ExtraWidgets. */
public class ForecastWidget extends AppWidgetProvider {
    @Override public void onEnabled(Context c) { WeatherRefreshJob.schedule(c); WidgetKeepAliveService.start(c); }
    @Override public void onUpdate(Context c, AppWidgetManager m, int[] ids) { RainWidget.refresh(this, c); }
    @Override public void onAppWidgetOptionsChanged(Context c, AppWidgetManager m, int id, Bundle o) { RainWidget.refresh(this, c); }
}
