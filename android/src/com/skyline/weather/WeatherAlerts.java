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
import java.util.Iterator;
import java.util.Locale;

/**
 * Weather warnings from the National Weather Service (api.weather.gov: free, no key; the US including Alaska,
 * Hawaii and the territories), for the widget's location, checked every few minutes alongside the rain check.
 *
 * Each new warning or watch, or anything the NWS rates severe or extreme, is notified once. Updates to an alert
 * already notified (they arrive as new messages that reference the old one) stay quiet, and so do the milder
 * advisories and statements, which the app shows on screen instead. Tapping a notification opens the app on that alert.
 */
final class WeatherAlerts {
    static final String PREF_ON = "weatherAlerts", EXTRA = "alertId";
    private static final String CHANNEL = "weather_alerts", UA = "(SkylineWeather, github.com/JacksonJones2003/skyline-weather)";
    private static final long MIN_GAP_MS = 4 * 60 * 1000L;
    private static volatile boolean busy;

    private WeatherAlerts() { }

    static boolean enabled(Context c) { return prefs(c).getBoolean(PREF_ON, true); }
    static void setEnabled(Context c, boolean on) { prefs(c).edit().putBoolean(PREF_ON, on).apply(); if (on) WidgetKeepAliveService.start(c); }
    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences(WeatherWidget.PREFS, Context.MODE_PRIVATE); }

    /** Whether an alert is worth buzzing the phone for: warnings, watches, and anything rated severe or extreme. */
    static boolean important(String event, String severity) {
        return event.contains("Warning") || event.contains("Watch") || "Extreme".equals(severity) || "Severe".equals(severity);
    }

    /** The regular check (throttled). Does network work on the calling thread: call it off the UI thread. */
    static void check(Context c) { run(c, Double.NaN, Double.NaN, true, false); }

    /** For testing from adb: any spot, no throttle; notify=false only logs what it found. */
    static void test(Context c, double lat, double lon, boolean notify) { run(c, lat, lon, notify, true); }

    private static void run(Context c, double lat, double lon, boolean notify, boolean force) {
        if (busy) return;
        SharedPreferences sp = prefs(c);
        if (!force && !enabled(c)) return;
        long now = System.currentTimeMillis();
        if (!force && now - sp.getLong("warnCheckedAt", 0) < MIN_GAP_MS) return;
        boolean override = !Double.isNaN(lat);
        if (!override) { lat = sp.getFloat("lat", 999); lon = sp.getFloat("lon", 999); }
        if (lat == 999) return;
        busy = true;
        try {
            if (!override) sp.edit().putLong("warnCheckedAt", now).apply();
            JSONArray feats = new JSONObject(get(String.format(Locale.US, "https://api.weather.gov/alerts/active?point=%.4f,%.4f", lat, lon)))
                    .optJSONArray("features");
            JSONObject seen = new JSONObject(sp.getString("warnSeen", "{}"));
            // forget alerts that have run out
            for (Iterator<String> it = seen.keys(); it.hasNext(); ) if (seen.optLong(it.next(), 0) < now) it.remove();
            int found = 0, sent = 0;
            String place = sp.getString("name", "");
            for (int i = 0; feats != null && i < feats.length(); i++) {
                JSONObject p = feats.getJSONObject(i).getJSONObject("properties");
                if (!"Actual".equals(p.optString("status")) || "Cancel".equals(p.optString("messageType"))) continue;
                found++;
                String id = p.optString("id"), event = p.optString("event"), severity = p.optString("severity");
                long until = time(p.optString("ends", null), time(p.optString("expires", null), now + 6 * 3600 * 1000L));
                boolean known = seen.has(id);
                // an update or continuation of an alert already notified: treat it as seen
                JSONArray refs = p.optJSONArray("references");
                for (int k = 0; refs != null && k < refs.length() && !known; k++) known = seen.has(refs.getJSONObject(k).optString("identifier"));
                seen.put(id, Math.max(until, now + 3600 * 1000L));
                if (known || !important(event, severity)) continue;
                if (override && !notify) continue;
                notify(c, id, event, p.optString("headline"), p.optString("description"), severity, place);
                sent++;
            }
            if (!override) sp.edit().putString("warnSeen", seen.toString()).apply();   // a test never marks anything as seen
            Diag.log(c, String.format(Locale.US, "alerts %s%.3f,%.3f active=%d notified=%d", override ? "test " : "", lat, lon, found, sent));
        } catch (Throwable t) {
            Diag.log(c, "alerts check failed: " + t);
        } finally {
            busy = false;
        }
    }

    private static long time(String iso, long fallback) {
        if (iso == null || iso.isEmpty() || "null".equals(iso)) return fallback;
        try { return new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).parse(iso).getTime(); } catch (Exception e) { return fallback; }
    }

    private static String get(String url) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(12000); conn.setReadTimeout(15000);
        conn.setRequestProperty("User-Agent", UA);                 // the NWS asks every client to identify itself
        conn.setRequestProperty("Accept", "application/geo+json");
        int code = conn.getResponseCode();
        if (code == 404 || code == 400) return "{\"features\":[]}"; // outside the NWS area
        if (code != 200) throw new java.io.IOException("HTTP " + code);
        try (InputStream in = conn.getInputStream()) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(); byte[] buf = new byte[8192]; int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString("UTF-8");
        } finally { conn.disconnect(); }
    }

    private static void notify(Context c, String id, String event, String headline, String description, String severity, String place) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm == null) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL, "Weather alerts", NotificationManager.IMPORTANCE_HIGH);
        ch.setDescription("Warnings and watches from the National Weather Service for where you are");
        nm.createNotificationChannel(ch);
        // tapping opens the app on this alert
        Intent open = new Intent(c, MainActivity.class).putExtra(EXTRA, id).setAction("alert:" + id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(c, id.hashCode(), open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        String body = headline.isEmpty() ? event : headline;
        String more = description.length() > 700 ? description.substring(0, 700).trim() + "…" : description.trim();
        Notification n = new Notification.Builder(c, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_alert)
                .setContentTitle(event + (place.isEmpty() ? "" : " · " + place))
                .setContentText(body)
                .setStyle(new Notification.BigTextStyle().bigText(body + (more.isEmpty() ? "" : "\n\n" + more)))
                .setColor("Extreme".equals(severity) ? 0xFFD93025 : "Severe".equals(severity) ? 0xFFF08C00 : 0xFFE3B100)
                .setCategory(Notification.CATEGORY_EVENT)
                .setContentIntent(pi).setAutoCancel(true).build();
        try { nm.notify(id.hashCode(), n); } catch (SecurityException ignored) { }   // notifications not allowed
    }
}
