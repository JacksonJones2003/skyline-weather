package com.skyline.weather;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * A morning summary of the day ahead, once a day at a time the person picks (7:00 by default), for the widget's
 * location: the high and low and the sky, when rain or snow is likely, and a strong wind if there is one.
 *
 * It rides on the same 5-minute background tick as the rain and warning alerts: on the first tick after the chosen
 * time it fetches today's forecast (Open-Meteo, no key) and posts one notification. If the phone was asleep at that
 * moment it still goes out within the next three hours, and never twice in a day.
 */
final class MorningBrief {
    static final String PREF_ON = "briefOn", PREF_AT = "briefAt";
    private static final String CHANNEL = "morning_brief";
    private static final int NOTE_ID = 6120, LATE_MIN = 180;
    private static volatile boolean busy;

    private MorningBrief() { }

    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences(WeatherWidget.PREFS, Context.MODE_PRIVATE); }
    static boolean enabled(Context c) { return prefs(c).getBoolean(PREF_ON, true); }
    /** Minutes after midnight. */
    static int at(Context c) { return prefs(c).getInt(PREF_AT, 7 * 60); }
    static void set(Context c, boolean on, int minutes) {
        prefs(c).edit().putBoolean(PREF_ON, on).putInt(PREF_AT, Math.max(0, Math.min(24 * 60 - 1, minutes))).remove("briefDay").apply();
        if (on) WidgetKeepAliveService.start(c);
        else { NotificationManager nm = c.getSystemService(NotificationManager.class); if (nm != null) nm.cancel(NOTE_ID); }
    }

    /** The regular check (cheap unless it's time). Network work runs on the calling thread: call it off the UI thread. */
    static void check(Context c) { run(c, false); }
    /** For testing from adb: send today's summary now. */
    static void test(Context c) { run(c, true); }

    private static void run(Context c, boolean force) {
        if (busy) return;
        SharedPreferences sp = prefs(c);
        if (!force && !enabled(c)) return;
        Calendar now = Calendar.getInstance();
        String day = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(now.getTime());
        int mins = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE), at = at(c);
        if (!force && (day.equals(sp.getString("briefDay", "")) || mins < at || mins > at + LATE_MIN)) return;
        float lat = sp.getFloat("lat", 999), lon = sp.getFloat("lon", 999);
        if (lat == 999) return;
        busy = true;
        try {
            JSONObject j = new JSONObject(get(String.format(Locale.US,
                    "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f&timezone=auto&forecast_days=1"
                            + "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max"
                            + "&hourly=precipitation_probability,precipitation,snowfall,wind_gusts_10m", lat, lon)));
            String[] text = compose(j, sp.getString("unit", "F"), sp.getString("name", ""));
            notify(c, text[0], text[1]);
            if (!force) sp.edit().putString("briefDay", day).apply();
            Diag.log(c, "brief " + (force ? "test " : "") + text[0]);
        } catch (Throwable t) {
            Diag.log(c, "brief failed: " + t);
        } finally {
            busy = false;
        }
    }

    /** [title, body] for today's forecast. */
    static String[] compose(JSONObject j, String unit, String place) throws Exception {
        boolean f = "F".equals(unit);
        JSONObject d = j.getJSONObject("daily"), h = j.getJSONObject("hourly");
        long hi = t(d.getJSONArray("temperature_2m_max").getDouble(0), f), lo = t(d.getJSONArray("temperature_2m_min").getDouble(0), f);
        String sky = sky(d.getJSONArray("weather_code").getInt(0));
        String title = (place.isEmpty() ? "Today" : place + " today") + ": " + hi + "° / " + lo + "° · " + sky;

        List<String> parts = new ArrayList<>();
        JSONArray time = h.getJSONArray("time"), pp = h.getJSONArray("precipitation_probability"), pr = h.getJSONArray("precipitation"),
                sn = h.getJSONArray("snowfall"), gu = h.getJSONArray("wind_gusts_10m");
        // the wettest stretch of the day: hours with a 50% chance or more and some rain or snow in them
        int first = -1, last = -1, peak = 0; boolean snow = false;
        for (int i = 0; i < time.length(); i++) {
            int p = pp.optInt(i, 0);
            if (p >= 50 && pr.optDouble(i, 0) >= .1) { if (first < 0) first = i; last = i; peak = Math.max(peak, p); snow |= sn.optDouble(i, 0) > 0; }
        }
        if (first >= 0) parts.add((snow ? "Snow" : "Rain") + " likely " + (first == last ? "around " + clock(time.getString(first))
                : clock(time.getString(first)) + "–" + clock(time.getString(last) , 1)) + " (" + peak + "%)");
        else {
            int max = d.getJSONArray("precipitation_probability_max").optInt(0, 0);
            parts.add(max >= 25 ? "A small chance of rain (" + max + "%)" : "Dry all day");
        }
        // a strong wind: gusts over 45 km/h, and when the strongest are
        int g = 0; double gmax = 0;
        for (int i = 0; i < gu.length(); i++) if (gu.optDouble(i, 0) > gmax) { gmax = gu.optDouble(i, 0); g = i; }
        if (gmax >= 45) parts.add("Windy " + part(time.getString(g)) + ", gusts to " + Math.round(f ? gmax * 0.621371 : gmax) + (f ? " mph" : " km/h"));
        // a frosty start
        if (d.getJSONArray("temperature_2m_min").getDouble(0) <= 0) parts.add("Below freezing this morning");
        return new String[]{title, String.join(". ", parts) + "."};
    }

    private static long t(double c, boolean f) { return Math.round(f ? c * 9 / 5 + 32 : c); }
    private static String clock(String iso) { return clock(iso, 0); }
    /** "3 PM" for a forecast hour (optionally the end of it, plus = 1). */
    private static String clock(String iso, int plus) {
        int hr = (Integer.parseInt(iso.substring(11, 13)) + plus) % 24;
        return (hr % 12 == 0 ? 12 : hr % 12) + (hr < 12 ? " AM" : " PM");
    }
    private static String part(String iso) {
        int hr = Integer.parseInt(iso.substring(11, 13));
        return hr < 12 ? "this morning" : hr < 17 ? "this afternoon" : "this evening";
    }
    private static String sky(int code) {
        if (code == 0) return "Clear";
        if (code <= 2) return "Partly cloudy";
        if (code == 3) return "Cloudy";
        if (code == 45 || code == 48) return "Foggy";
        if (code <= 57) return "Drizzle";
        if (code <= 67) return "Rain";
        if (code <= 77) return "Snow";
        if (code <= 82) return "Showers";
        if (code <= 86) return "Snow showers";
        return "Thunderstorms";
    }

    private static String get(String url) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(12000); conn.setReadTimeout(15000);
        if (conn.getResponseCode() != 200) throw new java.io.IOException("HTTP " + conn.getResponseCode());
        try (InputStream in = conn.getInputStream()) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(); byte[] buf = new byte[8192]; int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString("UTF-8");
        } finally { conn.disconnect(); }
    }

    private static void notify(Context c, String title, String text) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm == null) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL, "Morning summary", NotificationManager.IMPORTANCE_DEFAULT);
        ch.setDescription("The day's weather each morning at the time you choose");
        nm.createNotificationChannel(ch);
        PendingIntent open = PendingIntent.getActivity(c, 12, new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(c, CHANNEL).setSmallIcon(R.drawable.ic_stat_sun)
                .setContentTitle(title).setContentText(text).setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(open).setAutoCancel(true).setShowWhen(true).setWhen(new Date().getTime())
                .setTimeoutAfter(14 * 3600 * 1000L).build();                    // gone by the evening
        try { nm.notify(NOTE_ID, n); } catch (SecurityException ignored) { }   // notifications not allowed
    }
}
