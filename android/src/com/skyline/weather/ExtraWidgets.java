package com.skyline.weather;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.widget.RemoteViews;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;

/**
 * The two smaller home-screen widgets, alongside the main one (WeatherWidget):
 *
 *  - Rain (RainWidget): rain over the next 2 hours in 15-minute steps, as a sentence ("Rain starting around 3:15 PM")
 *    and a little bar chart.
 *  - Forecast (ForecastWidget): the temperature now and the next five days with icons, highs, lows and rain chance.
 *
 * Both draw on the main widget's sky backgrounds and icons, follow the same place (the widget's), and are refreshed
 * whenever the main widget is (its job, alarm and keep-alive service), so they need no schedule of their own.
 */
final class ExtraWidgets {
    private static final long MAX_AGE_MS = 5 * 60 * 1000L;
    private static final int[] D_N = {R.id.d0n, R.id.d1n, R.id.d2n, R.id.d3n, R.id.d4n}, D_I = {R.id.d0i, R.id.d1i, R.id.d2i, R.id.d3i, R.id.d4i},
            D_H = {R.id.d0h, R.id.d1h, R.id.d2h, R.id.d3h, R.id.d4h}, D_L = {R.id.d0l, R.id.d1l, R.id.d2l, R.id.d3l, R.id.d4l},
            D_P = {R.id.d0p, R.id.d1p, R.id.d2p, R.id.d3p, R.id.d4p};
    private static final String[] DOW = {"Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"};

    private ExtraWidgets() { }

    static boolean any(Context c) {
        AppWidgetManager m = AppWidgetManager.getInstance(c);
        return m.getAppWidgetIds(new ComponentName(c, RainWidget.class)).length > 0 || m.getAppWidgetIds(new ComponentName(c, ForecastWidget.class)).length > 0;
    }

    /** Redraws both kinds; fetches a fresh forecast first if it's older than 5 minutes (and fetch is allowed). */
    static void update(Context c, boolean fetch) {
        if (!any(c)) return;
        SharedPreferences sp = c.getSharedPreferences(WeatherWidget.PREFS, Context.MODE_PRIVATE);
        if (!sp.contains("lat")) return;
        String json = sp.getString("json2", null);
        if (json == null || (fetch && System.currentTimeMillis() - sp.getLong("at2", 0) > MAX_AGE_MS)) {
            try {
                json = get(String.format(Locale.US, "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f&timezone=auto&forecast_days=6"
                        + "&current=temperature_2m,weather_code,is_day,precipitation&minutely_15=precipitation,snowfall&forecast_minutely_15=8"
                        + "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max", sp.getFloat("lat", 0), sp.getFloat("lon", 0)));
                sp.edit().putString("json2", json).putLong("at2", System.currentTimeMillis()).apply();
            } catch (Exception e) { Diag.log(c, "extra widgets fetch failed: " + e); }
        }
        if (json == null) return;
        JSONObject j;
        try { j = new JSONObject(json); } catch (Exception e) { return; }
        String unit = sp.getString("unit", "F"), name = sp.getString("name", "");
        float dp = c.getResources().getDisplayMetrics().density;
        AppWidgetManager m = AppWidgetManager.getInstance(c);
        PendingIntent open = PendingIntent.getActivity(c, 0, new Intent(c, MainActivity.class), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        try {
            RemoteViews rv = rain(c, j, name, dp); rv.setOnClickPendingIntent(R.id.root, open);
            for (int id : m.getAppWidgetIds(new ComponentName(c, RainWidget.class))) m.updateAppWidget(id, rv);
        } catch (Exception e) { Diag.log(c, "rain widget failed: " + e); }
        try {
            RemoteViews rv = forecast(c, j, name, unit, dp); rv.setOnClickPendingIntent(R.id.root, open);
            for (int id : m.getAppWidgetIds(new ComponentName(c, ForecastWidget.class))) m.updateAppWidget(id, rv);
        } catch (Exception e) { Diag.log(c, "forecast widget failed: " + e); }
    }

    // ---- rain in the next 2 hours ----
    private static RemoteViews rain(Context c, JSONObject j, String name, float dp) throws Exception {
        RemoteViews rv = new RemoteViews(c.getPackageName(), R.layout.widget_rain);
        JSONObject cur = j.getJSONObject("current"), m = j.getJSONObject("minutely_15");
        JSONArray t = m.getJSONArray("time"), pr = m.getJSONArray("precipitation"), sn = m.optJSONArray("snowfall");
        int i0 = 0; String now = cur.getString("time");
        for (int i = 0; i < t.length(); i++) if (t.getString(i).compareTo(now) <= 0) i0 = i;
        int n = Math.min(8, t.length() - i0);
        double[] mm = new double[n]; boolean[] snow = new boolean[n]; String[] at = new String[n];
        for (int k = 0; k < n; k++) { mm[k] = pr.optDouble(i0 + k, 0); snow[k] = sn != null && sn.optDouble(i0 + k, 0) > 0; at[k] = t.getString(i0 + k); }
        String[] story = story(mm, snow, at, cur.optDouble("precipitation", 0));
        boolean day = cur.optInt("is_day", 1) == 1;
        String k = WeatherWidget.kind(cur.getInt("weather_code"));
        rv.setImageViewBitmap(R.id.bg, WeatherWidget.background(k, day, dp));
        rv.setTextViewText(R.id.rtitle, name.isEmpty() ? "Next 2 hours" : name + " · next 2 hours");
        rv.setTextViewText(R.id.rhead, story[0]);
        rv.setTextViewText(R.id.rsub, story[1]);
        rv.setImageViewBitmap(R.id.rbars, bars(mm, snow, dp));
        return rv;
    }

    /** [headline, smaller line], worded like the app's next-hour card. */
    static String[] story(double[] mm, boolean[] snow, String[] at, double curMm) {
        if (mm.length == 0) return new String[]{"No rain data", ""};
        boolean wet0 = wet(mm[0]) || curMm > 0;
        String what0 = snow[0] ? "snow" : "rain";
        if (wet0) {
            int k = -1;
            for (int i = 1; i < mm.length; i++) if (!wet(mm[i]) && (i == mm.length - 1 || !wet(mm[i + 1]))) { k = i; break; }
            return new String[]{k > 0 ? "Stopping around " + WeatherWidget.clock(at[k]) + ampm(at[k]) : "Continuing for 2 hours",
                    word(Math.max(mm[0], curMm) * 4, snow[0]) + " now"};
        }
        for (int i = 1; i < mm.length; i++) if (wet(mm[i]))
            return new String[]{word(mm[i] * 4, snow[i]) + " around " + WeatherWidget.clock(at[i]) + ampm(at[i]), "In " + i * 15 + " min · dry now"};
        return new String[]{"No " + what0 + " for 2 hours", "Dry now"};
    }
    private static boolean wet(double mm15) { return mm15 * 4 >= .2; }
    private static String ampm(String iso) { return Integer.parseInt(iso.substring(11, 13)) < 12 ? " AM" : " PM"; }
    private static String word(double mmh, boolean snow) {
        String w = mmh < .5 ? "Drizzle" : mmh < 2.5 ? "Light" : mmh < 7.6 ? "Moderate" : mmh < 50 ? "Heavy" : "Violent";
        return snow ? ("Drizzle".equals(w) ? "Light" : w) + " snow" : "Drizzle".equals(w) ? "Drizzle" : w + " rain";
    }
    private static Bitmap bars(double[] mm, boolean[] snow, float dp) {
        int w = Math.round(300 * dp), h = Math.round(46 * dp), n = Math.max(1, mm.length);
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        float gap = 5 * dp, bw = (w - gap * (n - 1)) / n;
        for (int i = 0; i < mm.length; i++) {
            float f = (float) Math.max(.06, Math.min(1, Math.sqrt(mm[i] * 4 / 10))), top = h - f * h, x = i * (bw + gap);
            p.setShader(snow[i] ? new LinearGradient(0, top, 0, h, 0xFFFFFFFF, 0xFFC9D6FF, Shader.TileMode.CLAMP)
                    : new LinearGradient(0, top, 0, h, 0xFF9FD6FF, 0xFF4F9DFF, Shader.TileMode.CLAMP));
            p.setAlpha(wet(mm[i]) ? 255 : 80);
            cv.drawRoundRect(new RectF(x, top, x + bw, h), 4 * dp, 4 * dp, p);
        }
        return b;
    }

    // ---- the next five days ----
    private static RemoteViews forecast(Context c, JSONObject j, String name, String unit, float dp) throws Exception {
        RemoteViews rv = new RemoteViews(c.getPackageName(), R.layout.widget_forecast);
        JSONObject cur = j.getJSONObject("current"), d = j.getJSONObject("daily");
        boolean day = cur.optInt("is_day", 1) == 1;
        int code = cur.getInt("weather_code");
        rv.setImageViewBitmap(R.id.bg, WeatherWidget.background(WeatherWidget.kind(code), day, dp));
        rv.setTextViewText(R.id.fname, name.isEmpty() ? "Forecast" : name);
        rv.setTextViewText(R.id.fnow, WeatherWidget.tf(cur.getDouble("temperature_2m"), unit) + "° · " + WeatherWidget.label(code));
        JSONArray time = d.getJSONArray("time"), codes = d.getJSONArray("weather_code"), hi = d.getJSONArray("temperature_2m_max"),
                lo = d.getJSONArray("temperature_2m_min"), pp = d.optJSONArray("precipitation_probability_max");
        float s = 26 * dp;
        for (int k = 0; k < 5 && k < time.length(); k++) {
            String iso = time.getString(k);
            java.util.Calendar cal = java.util.Calendar.getInstance();
            cal.set(Integer.parseInt(iso.substring(0, 4)), Integer.parseInt(iso.substring(5, 7)) - 1, Integer.parseInt(iso.substring(8, 10)));
            rv.setTextViewText(D_N[k], k == 0 ? "Today" : DOW[cal.get(java.util.Calendar.DAY_OF_WEEK) - 1]);
            rv.setImageViewBitmap(D_I[k], WeatherWidget.icon(WeatherWidget.kind(codes.getInt(k)), true, s));
            rv.setTextViewText(D_H[k], WeatherWidget.tf(hi.getDouble(k), unit) + "°");
            rv.setTextViewText(D_L[k], WeatherWidget.tf(lo.getDouble(k), unit) + "°");
            int p = pp == null ? 0 : pp.optInt(k, 0);
            rv.setTextViewText(D_P[k], p >= 20 ? p + "%" : "");
        }
        return rv;
    }

    private static String get(String url) throws Exception {
        HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
        h.setConnectTimeout(8000); h.setReadTimeout(8000);
        if (h.getResponseCode() != 200) throw new java.io.IOException("HTTP " + h.getResponseCode());
        try (InputStream in = h.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] buf = new byte[8192]; int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            String s = out.toString("UTF-8"); new JSONObject(s); return s;
        } finally { h.disconnect(); }
    }
}
