package com.skyline.weather;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import org.json.JSONObject;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * "Rain starting soon" alerts, worked out on the phone from the same NOAA radar data the radar map uses
 * (served by Iowa State's mesonet; US only, no key):
 *
 *  - the live radar mosaic at the widget's location (a patch about 2 km across), and
 *  - the HRRR model's simulated radar for the next 15, 30, 45 and 60 minutes at the same spot.
 *
 * Dry now and rain within 30 minutes: "Rain in about 15 minutes". Rain arriving without a warning:
 * "It's starting to rain". After an alert it stays quiet until it has been dry for 45 minutes, so one
 * shower gives one alert. Runs every few minutes from WidgetKeepAliveService and from WeatherRefreshJob.
 */
final class RainAlert {
    static final String PREF_ON = "rainAlerts";
    private static final String CHANNEL = "rain_alerts";
    private static final int NOTE_ID = 5310;
    private static final String IEM = "https://mesonet.agron.iastate.edu";
    private static final int Z = 8;                         // map zoom: one radar pixel is about 0.5 km here
    private static final long MIN_GAP_MS = 4 * 60 * 1000L, QUIET_AFTER_DRY_MS = 45 * 60 * 1000L;
    private static volatile boolean busy;

    private RainAlert() { }

    static boolean enabled(Context c) { return prefs(c).getBoolean(PREF_ON, true); }
    static void setEnabled(Context c, boolean on) {
        prefs(c).edit().putBoolean(PREF_ON, on).apply();
        if (on) WidgetKeepAliveService.start(c);
        else { NotificationManager nm = c.getSystemService(NotificationManager.class); if (nm != null) nm.cancel(NOTE_ID); }
    }
    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences(WeatherWidget.PREFS, Context.MODE_PRIVATE); }
    static boolean inUS(double lat, double lon) { return lat > 20 && lat < 53 && lon > -130 && lon < -60; }

    /** The regular check (throttled). Runs its network work on the calling thread: call it off the UI thread. */
    static void check(Context c) { run(c, Double.NaN, Double.NaN, true, false); }

    /** For testing from adb: check any spot, ignoring the throttle; with notify=false it only logs what it found. */
    static void test(Context c, double lat, double lon, boolean notify) { run(c, lat, lon, notify, true); }

    private static void run(Context c, double lat, double lon, boolean notify, boolean force) {
        if (busy) return;
        SharedPreferences sp = prefs(c);
        if (!force && !enabled(c)) return;
        long now = System.currentTimeMillis();
        if (!force && now - sp.getLong("rainCheckedAt", 0) < MIN_GAP_MS) return;
        boolean override = !Double.isNaN(lat);
        if (!override) { lat = sp.getFloat("lat", 999); lon = sp.getFloat("lon", 999); }
        if (lat == 999 || !inUS(lat, lon)) return;
        busy = true;
        try {
            if (!override) sp.edit().putLong("rainCheckedAt", now).apply();
            // the live radar, and the model run whose 15-minute steps follow it
            JSONObject radar = new JSONObject(get(IEM + "/data/gis/images/4326/USCOMP/n0q_0.json"));
            JSONObject run = new JSONObject(get(IEM + "/data/gis/images/4326/hrrr/refd_0000.json"));
            long radarT = iso(radar.getJSONObject("meta").getString("valid")), init = iso(run.getString("model_init_utc"));
            float nowT = intensity(IEM + "/cache/tile.py/1.0.0/nexrad-n0q-900913/", "?v=" + radarT / 1000, lat, lon);
            // forecast steps: the next quarter hours after the radar image, up to an hour ahead
            long first = ((radarT / 1000 + 60) / 900 + 1) * 900 * 1000;
            int leadMin = -1; float leadT = 0; long leadAt = 0;
            for (long t = first; t <= radarT + 60 * 60 * 1000L; t += 15 * 60 * 1000L) {
                long m = Math.round((t - init) / 60000.0);
                if (m < 0 || m > 1080) continue;
                float f = intensity(IEM + "/cache/tile.py/1.0.0/hrrr::REFD-F" + String.format(Locale.US, "%04d", m) + "-0/", "?run=" + init / 1000, lat, lon);
                if (f >= 0) { leadMin = (int) Math.max(5, Math.round((t - now) / 300000.0) * 5); leadT = f; leadAt = t; break; }
            }
            Diag.log(c, String.format(Locale.US, "rain %s%.3f,%.3f now=%.2f next=%s", override ? "test " : "", lat, lon, nowT,
                    leadMin < 0 ? "none" : leadMin + "min/" + String.format(Locale.US, "%.2f", leadT)));
            if (override && !notify) return;
            decide(c, sp, now, nowT, leadMin, leadT, leadAt);
        } catch (Throwable t) {
            Diag.log(c, "rain check failed: " + t);
        } finally {
            busy = false;
        }
    }

    // When to speak up. State: last time it was wet here, and when the last alert went out.
    private static void decide(Context c, SharedPreferences sp, long now, float nowT, int leadMin, float leadT, long leadAt) {
        long lastWet = sp.getLong("rainLastWet", 0), lastAlert = sp.getLong("rainAlertAt", 0);
        boolean quiet = now - lastAlert < QUIET_AFTER_DRY_MS || now - lastWet < QUIET_AFTER_DRY_MS;
        String place = sp.getString("name", "");
        if (nowT >= 0) {                                    // raining here now
            if (!quiet) {
                notify(c, "It's starting to rain", word(nowT) + " rain" + (place.isEmpty() ? "" : " in " + place) + " right now.");
                sp.edit().putLong("rainAlertAt", now).apply();
            }
            sp.edit().putLong("rainLastWet", now).apply();
        } else if (leadMin >= 0 && leadMin <= 30 && !quiet) {   // dry, but rain within half an hour
            notify(c, "Rain in about " + leadMin + " minutes",
                    word(leadT) + " rain expected" + (place.isEmpty() ? "" : " in " + place) + " around " + clock(leadAt) + ".");
            sp.edit().putLong("rainAlertAt", now).apply();
        }
    }

    private static String word(float t) { return t < .4 ? "Light" : t < .66 ? "Moderate" : t < .85 ? "Heavy" : "Very heavy"; }
    private static String clock(long t) { return new SimpleDateFormat("h:mm a", Locale.US).format(new Date(t)); }

    /**
     * How heavy the rain is at a spot on one radar layer: -1 for none, else 0 (light) to 1 (extreme), the heaviest
     * pixel in a 5x5 patch around the point. The tiles use the weather service's standard colors, read back the
     * same way the radar map does (greens light, yellow, orange, red, magenta heavy, white extreme).
     */
    private static float intensity(String layer, String query, double lat, double lon) throws Exception {
        double n = Math.pow(2, Z) * 256, x = (lon + 180) / 360 * n, r = Math.toRadians(lat);
        double y = (1 - Math.log(Math.tan(r) + 1 / Math.cos(r)) / Math.PI) / 2 * n;
        int tx = (int) (x / 256), ty = (int) (y / 256), px = (int) (x % 256), py = (int) (y % 256);
        Bitmap b;
        try (InputStream in = open(layer + Z + "/" + tx + "/" + ty + ".png" + query)) { b = BitmapFactory.decodeStream(in); }
        if (b == null) return -1;
        float best = -1;
        for (int dy = -2; dy <= 2; dy++) for (int dx = -2; dx <= 2; dx++) {
            int qx = px + dx, qy = py + dy;
            if (qx < 0 || qy < 0 || qx >= b.getWidth() || qy >= b.getHeight()) continue;
            best = Math.max(best, heaviness(b.getPixel(qx, qy)));
        }
        b.recycle();
        return best;
    }

    static float heaviness(int argb) {
        if ((argb >>> 24) < 40) return -1;
        int r = (argb >> 16) & 255, g = (argb >> 8) & 255, bl = argb & 255;
        int mx = Math.max(r, Math.max(g, bl)), mn = Math.min(r, Math.min(g, bl));
        float sat = mx == 0 ? 0 : (mx - mn) / (float) mx, v = mx / 255f;
        if (v > .88f && sat < .22f) return 1;                                  // white: extreme
        if (sat < .55f || (bl == mx && bl > g + 25 && r < 120)) return -1;      // grays, and the blues below light rain
        float c = mx - mn, h;
        if (mx == r) h = ((g - bl) / c) % 6; else if (mx == g) h = (bl - r) / c + 2; else h = (r - g) / c + 4;
        h = (h * 60 + 360) % 360;
        if (h >= 75 && h < 170) return .06f + .32f * (1 - v);                   // greens: the darker, the heavier
        if (h >= 45 && h < 75) return .5f;
        if (h >= 18 && h < 45) return .62f;
        if (h < 18 || h >= 345) return .72f + .1f * (1 - v);
        if (h >= 250) return .9f;
        return -1;
    }

    private static InputStream open(String url) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(12000); conn.setReadTimeout(15000);
        conn.setRequestProperty("User-Agent", "SkylineWeather-RainAlert");
        if (conn.getResponseCode() != 200) throw new java.io.IOException("HTTP " + conn.getResponseCode() + " " + url);
        return conn.getInputStream();
    }
    private static String get(String url) throws Exception {
        try (InputStream in = open(url)) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(); byte[] buf = new byte[8192]; int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString("UTF-8");
        }
    }
    private static long iso(String s) throws Exception {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US); f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.parse(s.replace("Z", "")).getTime();
    }

    private static void notify(Context c, String title, String text) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm == null) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL, "Rain alerts", NotificationManager.IMPORTANCE_HIGH);
        ch.setDescription("Rain starting soon where you are");
        nm.createNotificationChannel(ch);
        PendingIntent open = PendingIntent.getActivity(c, 11, new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(c, CHANNEL).setSmallIcon(R.drawable.ic_stat_rain)
                .setContentTitle(title).setContentText(text).setContentIntent(open).setAutoCancel(true)
                .setTimeoutAfter(90 * 60 * 1000L).build();                       // old news after an hour and a half
        try { nm.notify(NOTE_ID, n); } catch (SecurityException ignored) { }   // notifications not allowed
    }
}
