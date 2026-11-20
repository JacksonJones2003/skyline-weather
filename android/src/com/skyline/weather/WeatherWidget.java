package com.skyline.weather;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.*;
import android.os.Bundle;
import android.os.Build;
import android.os.Process;
import android.widget.RemoteViews;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Random;
import org.json.JSONArray;
import org.json.JSONObject;

public class WeatherWidget extends AppWidgetProvider {
    public static final String PREFS = "skyline";
    public static final String ACTION_REFRESH = "com.skyline.weather.REFRESH";
    public static final String ACTION_GBOARD_REFRESH = "com.skyline.weather.GBOARD_REFRESH";
    static String PREVIEW;
    // Several wake-ups can land together (job, alarm, service tick, unlock, install). Rendering
    // concurrently multiplied peak memory and helped lmkd kill the process, so only one runs.
    private static final java.util.concurrent.atomic.AtomicBoolean BUSY = new java.util.concurrent.atomic.AtomicBoolean();

    @Override public void onEnabled(Context c) { WeatherRefreshJob.schedule(c); WidgetKeepAliveService.start(c); }
    @Override public void onDisabled(Context c) { WidgetKeepAliveService.stop(c); }
    @Override public void onUpdate(Context c, AppWidgetManager m, int[] ids) {
        WeatherRefreshJob.schedule(c);
        WidgetKeepAliveService.start(c);
        refresh(c, true);
    }
    @Override public void onAppWidgetOptionsChanged(Context c, AppWidgetManager m, int id, Bundle o) { refresh(c, false); }
    @Override public void onReceive(Context c, Intent i) {
        String action = i.getAction();
        boolean platformWidgetAction = AppWidgetManager.ACTION_APPWIDGET_UPDATE.equals(action)
                || AppWidgetManager.ACTION_APPWIDGET_OPTIONS_CHANGED.equals(action)
                || AppWidgetManager.ACTION_APPWIDGET_ENABLED.equals(action)
                || AppWidgetManager.ACTION_APPWIDGET_DISABLED.equals(action);
        if (platformWidgetAction && senderUid() != Process.SYSTEM_UID) return;
        super.onReceive(c, i);
        if (ACTION_GBOARD_REFRESH.equals(i.getAction())) {
            if (!isGboardSender(c)) return;
            SharedPreferences sp = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            long at = sp.getLong("at", 0);
            boolean fetch = at == 0 || System.currentTimeMillis() - at > 5 * 60 * 1000L;
            refresh(c, fetch);
            return;
        }
        if (ACTION_REFRESH.equals(i.getAction())) {
            int sender = senderUid();
            // Our own app, adb shell, or the platform: an AlarmManager PendingIntent is delivered by
            // the system uid, and getSentFromUid() reports -1 when it cannot attribute the sender.
            // The old myUid/SHELL_UID-only check silently dropped every alarm-triggered refresh.
            if (sender != Process.myUid() && sender != Process.SHELL_UID
                    && sender != Process.SYSTEM_UID && sender != -1) return;
            PREVIEW = i.getStringExtra("preview");   // test hook: show this weather (e.g. "rain") instead of the real one
            // A delivered alarm means the chain survived a reboot, reinstall or Doze; keep it armed.
            WeatherRefreshJob.schedule(c);
            refresh(c, i.getBooleanExtra("fetch", true));
        }
    }

    private int senderUid() {
        return Build.VERSION.SDK_INT >= 34 ? getSentFromUid() : -1;
    }

    private boolean isGboardSender(Context c) {
        int uid = senderUid();
        if (uid < 0) return false;
        String[] packages = c.getPackageManager().getPackagesForUid(uid);
        if (packages == null) return false;
        for (String name : packages) {
            if ("dev.jason.com.google.android.inputmethod.latin".equals(name)
                    || "com.google.android.inputmethod.latin".equals(name)) return true;
        }
        return false;
    }

    private void refresh(Context c, boolean fetch) {
        final PendingResult pr = goAsync();
        final Context app = c.getApplicationContext();
        new Thread(() -> {
            try { update(app, fetch); } catch (Throwable t) { android.util.Log.w("SkylineWidget", "widget update failed", t); } finally { pr.finish(); }
        }).start();
    }

    static boolean update(Context c, boolean fetch) { return update(c, fetch, 5 * 60 * 1000L); }

    /** maxAgeMs: how old the cached reading may be before this call refreshes it from the network. */
    static boolean update(Context c, boolean fetch, long maxAgeMs) {
        if (!BUSY.compareAndSet(false, true)) return true;   // another refresh is already running
        try { return updateLocked(c, fetch, maxAgeMs); }
        finally { BUSY.set(false); }
    }

    /** Drop the cached cloud bitmaps when the system asks for memory back. */
    static void trim() {
        try { CLOUD_CACHE.clear(); } catch (Throwable ignored) { }
    }

    private static boolean updateLocked(Context c, boolean fetch, long maxAgeMs) {
        // Follow the phone: every refresh path (job, alarm, broadcast, service tick, unlock) checks
        // the stored place against the real one before fetching, so the widget moves with you.
        try { LocationFix.updateIfMoved(c); } catch (Throwable ignored) { }
        SharedPreferences sp = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String json = sp.getString("json", null);
        boolean hasPlace = sp.contains("lat");
        boolean fetchFailed = false;
        long lastFetch = sp.getLong("at", 0);
        // The job, the fallback alarm, the system widget update and opening the app can all land
        // within a minute of each other; only go to the network if the data is actually getting old.
        boolean due = lastFetch == 0 || System.currentTimeMillis() - lastFetch > maxAgeMs;
        if (hasPlace && (json == null || (fetch && due))) {
            Diag.log(c, "refresh place=" + sp.getFloat("lat", 0) + "," + sp.getFloat("lon", 0)
                    + " age=" + (lastFetch == 0 ? -1 : (System.currentTimeMillis() - lastFetch) / 1000) + "s");
            for (int attempt = 0; attempt < 2; attempt++) {
                try {
                    json = fetch(c, sp.getFloat("lat", 0), sp.getFloat("lon", 0));
                    sp.edit().putString("json", json).putLong("at", System.currentTimeMillis()).apply();
                    fetchFailed = false;
                    Diag.log(c, "fetch ok bytes=" + json.length());
                    break;
                } catch (Exception e) {
                    fetchFailed = true;
                    Diag.log(c, "fetch fail attempt=" + (attempt + 1) + " " + e.getClass().getSimpleName() + ": " + e.getMessage());
                    android.util.Log.w("SkylineWidget", "fetch failed (attempt " + (attempt + 1) + ")", e);
                    if (attempt == 0) {
                        try { Thread.sleep(750); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
                    }
                }
            }
        }
        LAT = sp.getFloat("lat", 30);
        String name = sp.getString("name", "");
        String unit = sp.getString("unit", "F");

        AppWidgetManager m = AppWidgetManager.getInstance(c);
        int[] ids = m.getAppWidgetIds(new ComponentName(c, WeatherWidget.class));
        float dp = c.getResources().getDisplayMetrics().density;
        PendingIntent open = PendingIntent.getActivity(c, 0, new Intent(c, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        RemoteViews rv = build(c, json, name, unit, dp, sp.getLong("at", 0));
        rv.setOnClickPendingIntent(R.id.root, open);
        for (int id : ids) m.updateAppWidget(id, rv);
        try { ExtraWidgets.update(c, fetch); } catch (Throwable t) { Diag.log(c, "extra widgets failed: " + t); }   // the rain and forecast widgets
        return !fetchFailed;
    }

    static String fetch(Context c, float lat, float lon) throws Exception {
        URL u = new URL("https://api.open-meteo.com/v1/forecast?latitude=" + lat + "&longitude=" + lon
                + "&timezone=auto&forecast_days=2"
                + "&current=temperature_2m,weather_code,is_day"
                + "&hourly=temperature_2m,weather_code,is_day"
                + "&daily=temperature_2m_max,temperature_2m_min,sunrise,sunset");
        HttpURLConnection h = (HttpURLConnection) u.openConnection();
        h.setConnectTimeout(8000); h.setReadTimeout(8000);   // mobile data can be slow to warm up   // stay inside the goAsync broadcast budget
        Diag.log(c, "http " + h.getResponseCode() + " " + u.getHost());
        try (InputStream in = h.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192]; int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            String s = out.toString("UTF-8");
            new JSONObject(s); // validate
            return s;
        } finally { h.disconnect(); }
    }

    // ---------- drawing ----------
    static String kind(int code) {
        if (code <= 1) return "clear";
        if (code == 2) return "partly";
        if (code == 3) return "cloud";
        if (code == 45 || code == 48) return "fog";
        if (code >= 71 && code <= 77 || code == 85 || code == 86) return "snow";
        if (code >= 95) return "storm";
        return "rain";
    }
    static String label(int code) {
        switch (code) {
            case 0: return "Sunny"; case 1: return "Mostly sunny"; case 2: return "Partly cloudy"; case 3: return "Cloudy";
            case 45: case 48: return "Fog";
            case 51: case 53: case 55: case 56: case 57: return "Drizzle";
            case 61: return "Light rain"; case 63: return "Rain"; case 65: return "Heavy rain"; case 66: case 67: return "Freezing rain";
            case 71: return "Light snow"; case 73: case 77: return "Snow"; case 75: return "Heavy snow";
            case 80: case 81: case 82: return "Showers"; case 85: case 86: return "Snow showers";
            default: return code >= 95 ? "Storms" : "—";
        }
    }
    // the same sky colours as the app: {top, light band, lower}
    static int[] sky(String k, boolean day) {
        switch (k) {
            case "clear":  return day ? new int[]{0xFF2F6FC0, 0xFF9CC8EE, 0xFF3F78BD} : new int[]{0xFF0B1736, 0xFF24406E, 0xFF13254A};
            case "partly": return day ? new int[]{0xFF3A72B8, 0xFFA6C6E4, 0xFF4679B5} : new int[]{0xFF111D3B, 0xFF2C4268, 0xFF172846};
            case "cloud":  return day ? new int[]{0xFF3F73B8, 0xFFA9C4E3, 0xFF4A7CBD} : new int[]{0xFF1B2331, 0xFF3A4556, 0xFF222B3A};
            case "fog":    return day ? new int[]{0xFF6C7C8B, 0xFFC3CCD4, 0xFF73828F} : new int[]{0xFF20272F, 0xFF4A545E, 0xFF283039};
            case "snow":   return day ? new int[]{0xFF6687A8, 0xFFCDDBE8, 0xFF6D8DB0} : new int[]{0xFF1D293D, 0xFF4B5B73, 0xFF24324A};
            case "storm":  return day ? new int[]{0xFF2B3144, 0xFF646B85, 0xFF30374B} : new int[]{0xFF0F1119, 0xFF2A2D3D, 0xFF151824};
            default:       return day ? new int[]{0xFF38506A, 0xFF8A9FB2, 0xFF3F5670} : new int[]{0xFF121B27, 0xFF2F3D4F, 0xFF172231};
        }
    }
    static long tf(double c, String unit) { return Math.round("F".equals(unit) ? c * 9 / 5 + 32 : c); }
    static String hourLabel(String iso) {
        int h = Integer.parseInt(iso.substring(11, 13));
        return ((h % 12) == 0 ? 12 : h % 12) + (h < 12 ? " AM" : " PM");
    }
    static String clock(String iso) {
        int h = Integer.parseInt(iso.substring(11, 13));
        return ((h % 12) == 0 ? 12 : h % 12) + ":" + iso.substring(14, 16);
    }

    static final int[] H_T = {R.id.h0t, R.id.h1t, R.id.h2t, R.id.h3t, R.id.h4t, R.id.h5t};
    static final int[] H_I = {R.id.h0i, R.id.h1i, R.id.h2i, R.id.h3i, R.id.h4i, R.id.h5i};
    static final int[] H_L = {R.id.h0l, R.id.h1l, R.id.h2l, R.id.h3l, R.id.h4l, R.id.h5l};

    static RemoteViews build(Context c, String json, String name, String unit, float dp, long updatedAt) {
        RemoteViews rv = new RemoteViews(c.getPackageName(), R.layout.widget);
        JSONObject j = null;
        try { if (json != null) j = new JSONObject(json); } catch (Exception ignored) {}
        if (j == null) {
            rv.setImageViewBitmap(R.id.bg, background(c, "clear", true, dp));
            rv.setTextViewText(R.id.temp, "");
            rv.setTextViewText(R.id.cond, "Skyline Weather");
            rv.setTextViewText(R.id.range, "Open the app to pick a city");
            rv.setViewVisibility(R.id.hours, android.view.View.INVISIBLE);
            return rv;
        }
        try {
            JSONObject cur = j.getJSONObject("current"), daily = j.getJSONObject("daily"), hr = j.getJSONObject("hourly");
            int code = cur.getInt("weather_code");
            String k = PREVIEW != null ? PREVIEW.split(":")[0] : kind(code); boolean day = cur.getInt("is_day") == 1;
            if (PREVIEW != null && PREVIEW.endsWith(":night")) day = false; else if (PREVIEW != null && PREVIEW.endsWith(":day")) day = true;
            float[] sun = sunNow(j, cur, daily);
            if (PREVIEW != null && PREVIEW.contains(":")) sun = day ? new float[]{.45f, 0f, 0f} : new float[]{1.6f, 0f, 0f};
            rv.setImageViewBitmap(R.id.bg, background(k, day, dp, sun[0], sun[1], sun[2]));
            applyFx(c, rv, k, day, sun[0], sun[1]);
            rv.setTextViewText(R.id.temp, String.valueOf(tf(cur.getDouble("temperature_2m"), unit)));
            rv.setTextViewText(R.id.unit, "°" + unit);
            String lab = label(code);
            if (!day) lab = lab.equals("Sunny") ? "Clear" : lab.equals("Mostly sunny") ? "Mostly clear" : lab;   // as the system widget says at night
            rv.setTextViewText(R.id.cond, lab);
            rv.setTextViewText(R.id.city, name);
            rv.setTextViewText(R.id.range, tf(daily.getJSONArray("temperature_2m_min").getDouble(0), unit) + "~"
                    + tf(daily.getJSONArray("temperature_2m_max").getDouble(0), unit) + "°" + unit);
            if (updatedAt > 0) {
                long age = Math.max(0, System.currentTimeMillis() - updatedAt);
                String time = new java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault())
                        .format(new java.util.Date(updatedAt));
                rv.setTextViewText(R.id.updated, (age > 90 * 60 * 1000L ? "Stale · Updated " : "Updated ") + time);
                rv.setTextColor(R.id.updated, age > 90 * 60 * 1000L ? 0xFFFFD28A : 0xB8FFFFFF);
            } else {
                rv.setTextViewText(R.id.updated, "Update time unavailable");
                rv.setTextColor(R.id.updated, 0xB8FFFFFF);
            }

            JSONArray times = hr.getJSONArray("time"), temps = hr.getJSONArray("temperature_2m"),
                    codes = hr.getJSONArray("weather_code"), isDay = hr.getJSONArray("is_day");
            String now = cur.getString("time").substring(0, 13);
            int i = 0;
            while (i < times.length() && times.getString(i).substring(0, 13).compareTo(now) < 0) i++;
            JSONArray rises = daily.getJSONArray("sunrise"), sets = daily.getJSONArray("sunset");
            // "Now", then every 2 hours (like the system weather widget), with sunrise or sunset slotted in
            // at its exact time between the two hours it falls between
            java.util.List<String[]> events = new java.util.ArrayList<>();   // {time, "rise"|"set"}
            for (int d = 0; d < rises.length(); d++) { events.add(new String[]{rises.getString(d), "rise"}); events.add(new String[]{sets.getString(d), "set"}); }
            float s = 19 * dp;
            String prev = cur.getString("time");
            int col = 0; boolean first = true;
            while (col < 6 && i < times.length()) {
                String ti = times.getString(i);
                if (!first) for (String[] ev : events) {
                    if (col < 5 && ev[0].compareTo(prev) > 0 && ev[0].compareTo(ti) <= 0) {
                        int near = Math.max(0, i - 1);   // temperature of the hour the event falls in
                        rv.setTextViewText(H_T[col], String.valueOf(tf(temps.getDouble(near), unit)));
                        Bitmap b = Bitmap.createBitmap((int) s, (int) s, Bitmap.Config.ARGB_8888);
                        drawSunEvent(new Canvas(b), s / 2, s / 2 + s * .08f, s * .95f, ev[1].equals("rise"));
                        rv.setImageViewBitmap(H_I[col], b);
                        rv.setTextViewText(H_L[col], clock(ev[0]));
                        rv.setTextColor(H_L[col], 0xB8FFFFFF);
                        col++;
                    }
                }
                if (col >= 6) break;
                double tv = first ? cur.getDouble("temperature_2m") : temps.getDouble(i);
                rv.setTextViewText(H_T[col], String.valueOf(tf(tv, unit)));
                rv.setImageViewBitmap(H_I[col], icon(kind(codes.getInt(i)), isDay.getInt(i) == 1, s));
                rv.setTextViewText(H_L[col], first ? "Now" : hourLabel(ti));
                rv.setTextColor(H_L[col], first ? 0xFFFFFFFF : 0xB8FFFFFF);
                col++;
                prev = first ? prev : ti;
                first = false;
                i += 2;
            }
        } catch (Exception ignored) {}
        return rv;
    }

    static Bitmap icon(String k, boolean day, float s) {
        Bitmap b = Bitmap.createBitmap((int) s, (int) s, Bitmap.Config.ARGB_8888);
        drawIcon(new Canvas(b), k, day, s / 2, s / 2, s * .95f);
        return b;
    }

    // Sky picture behind the widget: drawn at a fixed shape and cropped to fit, so it never stretches.
    static Bitmap background(Context c, String k, boolean day, float dp) { return background(k, day, dp, .4f, 0f, 0f); }

    static int mins(String iso) { return Integer.parseInt(iso.substring(11, 13)) * 60 + Integer.parseInt(iso.substring(14, 16)); }
    // {position across the day 0..1, golden-hour warmth 0..1, dusk/dawn glow 0..1}, at the place's own clock
    static float[] sunNow(JSONObject j, JSONObject cur, JSONObject daily) {
        try {
            int rise = mins(daily.getJSONArray("sunrise").getString(0)), set = mins(daily.getJSONArray("sunset").getString(0));
            int now;
            if (j.has("utc_offset_seconds")) {
                long t = System.currentTimeMillis() / 1000 + j.getLong("utc_offset_seconds");
                now = (int) (((t % 86400) + 86400) % 86400 / 60);
            } else now = mins(cur.getString("time"));
            float f = (now - rise) / (float) (set - rise);
            float warm = Math.max(Math.max(0, 1 - Math.abs(now - rise) / 75f), Math.max(0, 1 - Math.abs(now - set) / 75f));
            if (f < -.05f || f > 1.05f) warm *= .55f;
            float twi = now > set ? Math.max(0, 1 - (now - set) / 55f) : now < rise ? Math.max(0, 1 - (rise - now) / 55f) : 0;
            return new float[]{f, warm, twi};
        } catch (Exception e) { return new float[]{.4f, 0f, 0f}; }
    }

    static Bitmap background(String k, boolean day, float dp) { return background(k, day, dp, .4f, 0f, 0f); }

    static Bitmap background(String k, boolean day, float dp, float sunF, float warm, float twi) {
        int w = 900, h = 440;
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(bmp);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        boolean up = sunF > 0 && sunF < 1, clearish = k.equals("clear") || k.equals("partly");
        // near sunrise/sunset the whole sky shifts: indigo top fading to a mauve-pink glow low down
        float D = Math.min(1, (up ? warm : twi) * (clearish ? 1f : .9f) * 1.15f);
        int[] s = sky(k, day);
        // same sunset / dusk palette as the app: indigo top, mauve haze through the light band, navy below
        int top = blend(s[0], up ? 0xFF34477F : 0xFF22306A, D), band = blend(s[1], up ? 0xFFC2A6C6 : 0xFF8F7FB4, D), low = blend(s[2], up ? 0xFF6F7FB6 : 0xFF46548F, D);
        // the widget shows the top of the app's sky: deep at the top, brightening into the light band near the bottom
        p.setShader(new LinearGradient(0, 0, 0, h, new int[]{top, blend(top, band, .4f), blend(band, low, .38f), low}, new float[]{0f, .5f, .9f, 1f}, Shader.TileMode.CLAMP));
        cv.drawRect(0, 0, w, h, p);
        p.setShader(null);
        float fc = Math.max(0, Math.min(1, sunF)), alt = up ? (float) Math.sin(Math.PI * fc) : 0;
        // the sun's glow sits where the sun is: east (left) in the morning, high at noon, west (right) toward sunset
        if (up || twi > 0) {
            float gx = w * (.08f + .84f * fc), gy = h * (.85f - 1.0f * alt) - 30;
            float strength = up ? (clearish ? 1f : .35f) : twi * .5f;
            int core = blend(0xFFFFFBEA, 0xFFFFC27A, warm), mid = blend(0xFFFFF2CC, 0xFFFF9A55, warm);
            p.setShader(new RadialGradient(gx, gy, 480, new int[]{alpha(core, .7f * strength), alpha(mid, .25f * strength), 0x00FFFFFF}, new float[]{0f, .35f, 1f}, Shader.TileMode.CLAMP));
            cv.drawRect(0, 0, w, h, p);
            p.setShader(null);
        }
        // golden hour: warm band along the horizon; after sunset, rose fading to violet
        float wa = D;
        if (wa > .01f) {
            int lo = up ? 0xFFF4BAC4 : 0xFFD6A0CE;
            p.setShader(new LinearGradient(0, h * .2f, 0, h, new int[]{0x00FFFFFF, alpha(lo, .45f * wa), alpha(lo, .2f * wa)}, new float[]{0f, .75f, 1f}, Shader.TileMode.CLAMP));
            cv.drawRect(0, 0, w, h, p);
            p.setShader(null);
        }
        // clear and cloudy nights: the app's star field (thick near the top, thinning lower down) and a dim crescent moon
        boolean starry = !day && (clearish || k.equals("cloud"));
        if (starry) {
            float vis = k.equals("clear") ? 1f : k.equals("partly") ? .8f : .75f;
            Random r = new Random(11);
            Paint sp = new Paint(Paint.ANTI_ALIAS_FLAG);
            int n = 260;
            for (int i = 0; i < n; i++) {
                float y = (float) Math.pow(r.nextFloat(), 1.5) * h * .92f, x = r.nextFloat() * w;
                float fade = 1 - y / (h * .92f);
                boolean big = r.nextFloat() < .07f;
                float rad = big ? 2.6f + r.nextFloat() * 1.2f : 1.0f + r.nextFloat() * 1.3f;
                float a = (big ? .95f : .45f + r.nextFloat() * .5f) * (.35f + .65f * fade) * vis;
                if (big) {
                    sp.setShader(new RadialGradient(x, y, rad * 4, alpha(0xFFFFFFFF, a * .45f), 0x00FFFFFF, Shader.TileMode.CLAMP));
                    cv.drawCircle(x, y, rad * 4, sp); sp.setShader(null);
                }
                sp.setColor(alpha(0xFFFFFAEE, a)); cv.drawCircle(x, y, rad, sp);
            }
            if (clearish) {
                // the moon in its real phase tonight: a faint full disc, with the sunlit part on top
                float mx = w * .405f, my = h * .15f, mr = 21;   // in the open sky between the temperature and the city
                float[] ph = moonPhase(System.currentTimeMillis());
                float lit = ph[0]; boolean right = (ph[1] > 0) == (LAT >= 0);
                Paint mp = new Paint(Paint.ANTI_ALIAS_FLAG);
                if (lit > .02f) {
                    mp.setShader(new RadialGradient(mx, my, mr * 2.6f, alpha(0xFFFFF6DC, .2f * (float) Math.sqrt(lit)), 0x00FFFFFF, Shader.TileMode.CLAMP));
                    cv.drawCircle(mx, my, mr * 2.6f, mp); mp.setShader(null);
                }
                mp.setColor(alpha(0xFFFFFAE8, .08f)); cv.drawCircle(mx, my, mr, mp);
                if (lit > .005f) { mp.setColor(alpha(0xFFFFF8DE, .72f)); cv.drawPath(moonShape(mx, my, mr, lit, right), mp); }
            }
        }
        // realistic clouds made from layered noise, heaviest at the top and thinning toward the text
        Bitmap clouds = cloudLayer(k, day);
        Paint f = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        float cw = D;
        if (cw > .01f) {   // clouds lit pink from below by the low sun
            int tint = up ? 0xFFFFA6BE : 0xFFE89AD0;
            ColorMatrix cm = new ColorMatrix(new float[]{
                1 - .5f * cw + .5f * cw * Color.red(tint) / 255f, 0, 0, 0, 0,
                0, 1 - .5f * cw + .5f * cw * Color.green(tint) / 255f, 0, 0, 0,
                0, 0, 1 - .5f * cw + .5f * cw * Color.blue(tint) / 255f, 0, 0,
                0, 0, 0, 1, 0});
            f.setColorFilter(new ColorMatrixColorFilter(cm));
        }
        // at night the clouds thin out so the stars show through the gaps (clear: wisps; mostly cloudy: broken cover)
        if (!day && (clearish || k.equals("cloud"))) f.setAlpha(k.equals("clear") ? 72 : k.equals("partly") ? 165 : 150);
        cv.drawBitmap(clouds, null, new RectF(0, 0, w, h), f);
        return bmp;
    }

    // ---------- the sky's animation ----------
    // A home-screen widget can't run its own drawing code, so the animation is a flipbook flipped every 16 ms
    // (60 frames a second, the home screen's own rate). Android caps a widget's picture memory, so frames are reused
    // to make room for sharper ones: effects that swing to and fro (rays, twinkling, mist, glow) are drawn as the
    // first half of a 3.3-second loop and played forward then back, easing at each end so the turn is invisible;
    // rain and snow use a 0.83-second loop that repeats seamlessly. The same bitmap sent twice is stored once.
    // Each layer is a single colour, so frames are alpha-only bitmaps tinted by the widget: a quarter of the memory.
    static final int FX_N = 200, FLASH_N = 30;
    static int[] ids(Context c, String prefix, int n) {
        int[] r = new int[n];
        for (int i = 0; i < n; i++) r[i] = c.getResources().getIdentifier(prefix + i, "id", c.getPackageName());
        return r;
    }
    static void applyFx(Context c, RemoteViews rv, String k, boolean day, float sunF, float warm) {
        long t0 = System.currentTimeMillis();
        boolean up = sunF > 0 && sunF < 1;
        boolean rays = day && up && (k.equals("clear") || k.equals("partly"));
        boolean stars = !day && (k.equals("clear") || k.equals("partly") || k.equals("cloud"));
        boolean wet = k.equals("rain") || k.equals("storm"), snow = k.equals("snow"), fog = k.equals("fog"), glow = k.equals("cloud") && day;
        boolean falling = wet || snow;
        int tint;
        if (rays) tint = blend(0xFFFFF8E6, 0xFFFFC98A, warm);
        else if (stars) tint = 0xFFFFFCF0;
        else if (wet) tint = 0xFFE8F2FF;
        else if (snow || glow) tint = 0xFFFFFFFF;
        else if (fog) tint = day ? 0xFFF2F5F8 : 0xFFAEB8C4;
        else { rv.setViewVisibility(R.id.fx, android.view.View.GONE); rv.setViewVisibility(R.id.flash, android.view.View.GONE); return; }
        // unique frames and their size: both kinds come to about 12-13 MB, inside the widget memory cap
        int U = falling ? 50 : 100;
        float sc = falling ? .8f : .55f;
        int w = 900, h = 440, bw = Math.round(w * sc), bh = Math.round(h * sc);
        int[] fx = ids(c, "fx", FX_N);
        Bitmap[] groups = rays ? rayGroups(w, h, sunF, k.equals("clear") ? 1f : .5f, warm, sc) : null;
        Bitmap mist = fog ? mistLayer(w, h, sc) : null;
        Bitmap clouds = stars ? cloudLayer(k, false) : null;
        float cloudA = k.equals("clear") ? 72 / 255f : k.equals("partly") ? 165 / 255f : 150 / 255f;
        Paint bp = new Paint(Paint.FILTER_BITMAP_FLAG);
        Bitmap[] frames = new Bitmap[U];
        for (int u = 0; u < U; u++) {
            Bitmap b = Bitmap.createBitmap(bw, bh, Bitmap.Config.ALPHA_8);
            Canvas cv = new Canvas(b);
            // swinging effects: an angle that eases to a stop at both ends of the forward half, so playing it back reverses smoothly
            double ph = Math.PI * Math.cos(Math.PI * u / (U - 1));
            float t = u / (float) U;
            if (rays) {
                float fc = Math.max(0, Math.min(1, sunF)), alt = (float) Math.sin(Math.PI * fc);
                float gx = w * (.08f + .84f * fc) * sc, gy = (h * (.85f - 1.0f * alt) - 30) * sc;
                cv.save(); cv.rotate((float) (.35 * Math.sin(ph)), gx, gy);
                for (int g = 0; g < groups.length; g++) {
                    bp.setAlpha(Math.round(255 * (.72f + .28f * (float) Math.sin(ph + g * 2 * Math.PI / groups.length))));
                    cv.drawBitmap(groups[g], 0, 0, bp);
                }
                cv.restore();
                float a = (k.equals("clear") ? 1f : .5f) * Math.min(1, alt * 2.5f) * (1 - warm * .5f), br = (1 + .05f * (float) Math.sin(ph)) * 150 * sc;
                Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
                p.setShader(new RadialGradient(gx, gy, br, new int[]{alpha(0xFFFFFFFF, .55f * a), alpha(0xFFFFFFFF, .22f * a), 0}, new float[]{0f, .3f, 1f}, Shader.TileMode.CLAMP));
                cv.drawCircle(gx, gy, br, p);
            }
            if (stars || falling || glow) { cv.save(); cv.scale(sc, sc); }
            if (stars) drawTwinkles(cv, w, h, clouds, cloudA, k.equals("clear") ? 1f : .8f, ph);
            if (wet) drawRain(cv, w, h, t);
            if (snow) drawSnow(cv, w, h, t);
            if (glow) drawGlow(cv, w, h, ph);
            if (stars || falling || glow) cv.restore();
            if (mist != null) {
                int bandH = mist.getHeight() / 3;
                for (int m = 0; m < 3; m++) {
                    float dx = 26 * sc * (float) Math.sin(ph + m * 2.1);
                    cv.drawBitmap(mist, new Rect(0, m * bandH, mist.getWidth(), (m + 1) * bandH), new RectF(dx, m * bandH, dx + mist.getWidth(), (m + 1) * bandH), bp);
                }
            }
            frames[u] = b;
        }
        for (int f = 0; f < FX_N; f++) {
            int u = falling ? f % U : (f < U ? f : FX_N - 1 - f);   // falling: repeat; swinging: forward then back
            rv.setImageViewBitmap(fx[f], frames[u]);
            rv.setInt(fx[f], "setColorFilter", tint);
        }
        rv.setViewVisibility(R.id.fx, android.view.View.VISIBLE);
        if (k.equals("storm")) {
            int[] fl = ids(c, "fl", FLASH_N);
            int fw = Math.round(w * .2f), fh = Math.round(h * .2f);
            Bitmap none = Bitmap.createBitmap(fw, fh, Bitmap.Config.ALPHA_8), strong = flash(fw, fh, .42f), soft = flash(fw, fh, .24f);
            for (int f = 0; f < FLASH_N; f++) {
                rv.setImageViewBitmap(fl[f], f == 0 ? strong : f == 2 ? soft : none);
                rv.setInt(fl[f], "setColorFilter", 0xFFE8ECFF);
            }
            rv.setViewVisibility(R.id.flash, android.view.View.VISIBLE);
        } else rv.setViewVisibility(R.id.flash, android.view.View.GONE);
        android.util.Log.i("SkylineWidget", "animation for " + k + (day ? " day" : " night") + ": " + U + " frames at " + bw + "x" + bh + " drawn in " + (System.currentTimeMillis() - t0) + " ms");
    }
    // Sunlight through haze: soft beams of uneven width and strength fanning from the sun, brightest near it and fading
    // with distance. They are split into six interleaved sets so the brightening and dimming ripples around the fan.
    static Bitmap[] rayGroups(int w, int h, float sunF, float strength, float warm, float sc) {
        float fc = Math.max(0, Math.min(1, sunF)), alt = (float) Math.sin(Math.PI * fc);
        float gx = w * (.08f + .84f * fc), gy = h * (.85f - 1.0f * alt) - 30;
        float a = strength * Math.min(1, alt * 2.5f) * (1 - warm * .5f);
        int G = 6;
        Bitmap[] out = new Bitmap[G];
        Canvas[] cvs = new Canvas[G];
        for (int g = 0; g < G; g++) { out[g] = Bitmap.createBitmap(Math.round(w * sc), Math.round(h * sc), Bitmap.Config.ALPHA_8); cvs[g] = new Canvas(out[g]); cvs[g].scale(sc, sc); }
        Random r = new Random(42);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setMaskFilter(new BlurMaskFilter(Math.max(2, 22 * sc), BlurMaskFilter.Blur.NORMAL));
        int n = 46;
        for (int i = 0; i < n; i++) {
            double ang = 2 * Math.PI * i / n + (r.nextFloat() - .5f) * .1;
            double wid = Math.toRadians(.5 + Math.pow(r.nextFloat(), 2) * 4.5);
            float I = a * (.25f + .75f * r.nextFloat()), len = 520 + r.nextFloat() * 520;
            p.setShader(new RadialGradient(gx, gy, len, new int[]{alpha(0xFFFFFFFF, .34f * I), alpha(0xFFFFFFFF, .13f * I), 0}, new float[]{0f, .38f, 1f}, Shader.TileMode.CLAMP));
            Path ray = new Path(); ray.moveTo(gx, gy);
            ray.lineTo(gx + (float) Math.cos(ang - wid / 2) * len, gy + (float) Math.sin(ang - wid / 2) * len);
            ray.lineTo(gx + (float) Math.cos(ang + wid / 2) * len, gy + (float) Math.sin(ang + wid / 2) * len);
            ray.close();
            cvs[(i * 5) % G].drawPath(ray, p);              // neighbouring beams land in different sets
        }
        return out;
    }
    static Bitmap mistLayer(int w, int h, float sc) {
        // three bands stacked in one bitmap (top, middle, low); each is moved on its own per frame
        Bitmap b = Bitmap.createBitmap(Math.round(w * sc), Math.round(h * sc), Bitmap.Config.ALPHA_8);
        Canvas cv = new Canvas(b); cv.scale(sc, sc);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setMaskFilter(new BlurMaskFilter(36 * sc * 2.2f, BlurMaskFilter.Blur.NORMAL));
        float[][] bands = {{.3f, .14f, .2f}, {.55f, .2f, .26f}, {.82f, .24f, .32f}};
        for (float[] bd : bands) {
            p.setColor(alpha(0xFFFFFFFF, bd[2]));
            for (int s2 = 0; s2 < 3; s2++) {
                float cx = w * (.15f + s2 * .35f);
                cv.drawOval(cx - w * .3f, h * (bd[0] - bd[1] / 2), cx + w * .3f, h * (bd[0] + bd[1] / 2), p);
            }
        }
        return b;
    }
    // the brighter stars twinkle on their own rhythm, dimmed wherever cloud passes in front of them
    static void drawTwinkles(Canvas cv, int w, int h, Bitmap clouds, float cloudA, float vis, double ph) {
        Random r = new Random(23);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        for (int i = 0; i < 46; i++) {
            float y = (float) Math.pow(r.nextFloat(), 1.4) * h * .9f, x = r.nextFloat() * w;
            float phase = r.nextFloat() * 6.283f;
            float rad = 2.2f + r.nextFloat() * 1.8f;
            int cx = Math.min(clouds.getWidth() - 1, (int) (x / w * clouds.getWidth())), cy = Math.min(clouds.getHeight() - 1, (int) (y / h * clouds.getHeight()));
            float clear = 1 - (Color.alpha(clouds.getPixel(cx, cy)) / 255f) * cloudA * 1.15f;
            float tw = (float) Math.pow(.5 + .5 * Math.sin(ph + phase), 2);
            float a = vis * Math.max(0, clear) * (1 - y / (h * 1.1f)) * tw;
            if (a < .02f) continue;
            p.setShader(new RadialGradient(x, y, rad * 4.5f, alpha(0xFFFFFFFF, a * .5f), 0, Shader.TileMode.CLAMP));
            cv.drawCircle(x, y, rad * 4.5f, p); p.setShader(null);
            p.setColor(alpha(0xFFFFFFFF, a)); cv.drawCircle(x, y, rad, p);
            if (rad > 3.2f) {
                p.setStrokeWidth(1.6f); p.setColor(alpha(0xFFFFFFFF, a * .55f));
                cv.drawLine(x - rad * 4, y, x + rad * 4, y, p); cv.drawLine(x, y - rad * 4, x, y + rad * 4, p);
            }
        }
    }
    // cloudy days: a soft patch of brighter light swells and fades over the clouds, like the sun almost breaking through
    static void drawGlow(Canvas cv, int w, int h, double ph) {
        float a = .1f + .08f * (float) Math.sin(ph);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new RadialGradient(w * .72f, h * .12f, w * .5f, new int[]{alpha(0xFFFFFFFF, a), alpha(0xFFFFFFFF, a * .4f), 0}, new float[]{0f, .5f, 1f}, Shader.TileMode.CLAMP));
        cv.drawRect(0, 0, w, h, p);
    }
    // rain in three depths, slanted. The loop is short (0.83 s), so slower drops move only part of the height per
    // loop; each comes as evenly spaced copies, so at the end of the loop every drop sits where the next one started.
    static void drawRain(Canvas cv, int w, int h, float t) {
        Random r = new Random(7);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG); p.setStrokeCap(Paint.Cap.ROUND);
        float slant = .16f;
        int[] count = {38, 35, 6}; float[] len = {19, 32, 53}, wid = {1.6f, 2.1f, 2.8f}, al = {.32f, .44f, .58f};
        int[] copies = {4, 2, 4}; int[] steps = {1, 1, 3};          // moves steps/copies of the height per loop
        for (int L = 0; L < 3; L++) for (int i = 0; i < count[L]; i++) {
            float x0 = r.nextFloat() * (w + h * slant), y0 = r.nextFloat(), j = .8f + r.nextFloat() * .4f;
            for (int cp = 0; cp < copies[L]; cp++) {
                float y = ((y0 + (cp + t * steps[L]) / copies[L]) % 1f) * (h + 80) - 40, x = x0 - y * slant, l = len[L] * j;
                p.setStrokeWidth(wid[L]);
                p.setShader(new LinearGradient(x, y, x + l * slant, y - l, alpha(0xFFFFFFFF, al[L]), 0, Shader.TileMode.CLAMP));
                cv.drawLine(x, y, x + l * slant, y - l, p);
            }
        }
    }
    // falling snow in three depths, using the same evenly-spaced-copies trick; flakes weave side to side as they
    // fall (the weave follows height, so the copies line up exactly when the loop repeats)
    static void drawSnow(Canvas cv, int w, int h, float t) {
        Random r = new Random(5);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        int[] copies = {8, 4, 5}; int[] steps = {1, 1, 2};
        int[] count = {5, 5, 8};          // about the same number of flakes on screen as before
        for (int layer = 0; layer < 3; layer++) for (int i = 0; i < count[layer]; i++) {
            float x0 = r.nextFloat() * w, y0 = r.nextFloat(), sway = 6 + r.nextFloat() * 12, phase = r.nextFloat() * 6.283f;
            float rad = (layer == 0 ? 2.2f : layer == 1 ? 3.4f : 5f) * (.8f + r.nextFloat() * .4f);
            float a = layer == 0 ? .55f : layer == 1 ? .75f : .9f;
            for (int cp = 0; cp < copies[layer]; cp++) {
                float yf = (y0 + (cp + t * steps[layer]) / copies[layer]) % 1f;
                float y = yf * (h + 30) - 15, x = x0 + sway * (float) Math.sin(yf * 6.283f * 2 + phase);
                p.setShader(new RadialGradient(x, y, rad, new int[]{alpha(0xFFFFFFFF, a), alpha(0xFFFFFFFF, a * .6f), 0}, new float[]{0f, .55f, 1f}, Shader.TileMode.CLAMP));
                cv.drawCircle(x, y, rad, p);
            }
        }
    }
    static Bitmap flash(int fw, int fh, float a) {
        Bitmap b = Bitmap.createBitmap(fw, fh, Bitmap.Config.ALPHA_8);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new RadialGradient(fw * .7f, fh * .1f, fw * .8f, new int[]{alpha(0xFFFFFFFF, a), alpha(0xFFFFFFFF, a * .4f), 0}, new float[]{0f, .5f, 1f}, Shader.TileMode.CLAMP));
        new Canvas(b).drawRect(0, 0, fw, fh, p);
        return b;
    }

    // ---------- the moon's real phase ----------
    static float LAT = 30;
    // Low-precision lunar theory (Meeus, ch. 48): {lit fraction 0..1, 1 if waxing else -1}
    static float[] moonPhase(long ms) {
        double d = (ms - 946728000000L) / 864e5, r = Math.PI / 180;
        double D = ((297.8501921 + 12.19074912 * d) % 360 + 360) % 360, M = (357.5291092 + 0.98560028 * d) % 360, Mm = (134.9633964 + 13.06499295 * d) % 360;
        double i = 180 - D - 6.289 * Math.sin(Mm * r) + 2.1 * Math.sin(M * r) - 1.274 * Math.sin((2 * D - Mm) * r)
                - .658 * Math.sin(2 * D * r) - .214 * Math.sin(2 * Mm * r) - .11 * Math.sin(D * r);
        return new float[]{(float) ((1 + Math.cos(i * r)) / 2), D < 180 ? 1 : -1};
    }
    // the lit part: a half disc on the sunlit side, with the terminator (an ellipse) added for a gibbous moon or
    // cut away for a crescent
    static Path moonShape(float cx, float cy, float r, float lit, boolean right) {
        RectF disc = new RectF(cx - r, cy - r, cx + r, cy + r);
        Path half = new Path(); half.arcTo(disc, right ? -90 : 90, 180, true); half.close();
        float rx = r * Math.abs(1 - 2 * lit);
        Path term = new Path(); term.addOval(new RectF(cx - rx, cy - r, cx + rx, cy + r), Path.Direction.CW);
        half.op(term, lit < .5f ? Path.Op.DIFFERENCE : Path.Op.UNION);
        return half;
    }

    static int alpha(int c, float a) { return (Math.round(Math.max(0, Math.min(1, a)) * 255) << 24) | (c & 0xFFFFFF); }
    static int blend(int a, int b, float t) {
        t = Math.max(0, Math.min(1, t));
        return 0xFF000000 | (Math.round(Color.red(a) + (Color.red(b) - Color.red(a)) * t) << 16)
                | (Math.round(Color.green(a) + (Color.green(b) - Color.green(a)) * t) << 8) | Math.round(Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t);
    }

    // ---------- realistic cloud generation ----------
    static final int[] PERM = new int[512];
    static {
        int[] q = new int[256];
        for (int i = 0; i < 256; i++) q[i] = i;
        Random r = new Random(1337);
        for (int i = 255; i > 0; i--) { int j = r.nextInt(i + 1); int t = q[i]; q[i] = q[j]; q[j] = t; }
        for (int i = 0; i < 512; i++) PERM[i] = q[i & 255];
    }
    static float hash(int x, int y) { return PERM[PERM[x & 255] + (y & 255)] / 255f; }
    static float noise(float x, float y) {
        int xi = (int) Math.floor(x), yi = (int) Math.floor(y);
        float xf = x - xi, yf = y - yi;
        float u = xf * xf * xf * (xf * (xf * 6 - 15) + 10), v = yf * yf * yf * (yf * (yf * 6 - 15) + 10);
        float a = hash(xi, yi), b = hash(xi + 1, yi), c = hash(xi, yi + 1), d = hash(xi + 1, yi + 1);
        return a + (b - a) * u + (c - a) * v + (a - b - c + d) * u * v;
    }
    static float fbm(float x, float y) {
        float sum = 0, amp = .5f;
        for (int o = 0; o < 6; o++) { sum += amp * noise(x, y); x = x * 2.02f + 17.3f; y = y * 2.02f + 9.1f; amp *= .5f; }
        return sum / .984f;
    }
    static float smooth(float e0, float e1, float x) { float t = Math.max(0, Math.min(1, (x - e0) / (e1 - e0))); return t * t * (3 - 2 * t); }

    static final java.util.HashMap<String, Bitmap> CLOUD_CACHE = new java.util.HashMap<>();

    static Bitmap cloudLayer(String k, boolean day) {
        String key = k + day;
        Bitmap cached = CLOUD_CACHE.get(key);
        if (cached != null) return cached;
        int W = 460, H = 225;
        float cov; // how much of the sky is cloud
        switch (k) { case "clear": cov = .36f; break; case "partly": cov = .56f; break; case "cloud": cov = .68f; break; case "fog": cov = .74f; break; default: cov = .72f; }
        boolean heavy = k.equals("rain") || k.equals("storm");
        // lit and shaded cloud colours
        // softer blue-white than pure white, so the widget's white text stays readable over the clouds
        int[] lit = day ? (heavy ? new int[]{206, 214, 226} : new int[]{226, 236, 248}) : new int[]{150, 164, 192};
        int[] shade = day ? (heavy ? new int[]{108, 123, 146} : new int[]{140, 166, 204}) : new int[]{52, 64, 92};
        float maxA = day ? .8f : .65f;
        int[] px = new int[W * H];
        for (int y = 0; y < H; y++) {
            float yy = y / (float) H;
            float band = 1 - smooth(.3f, .7f, yy);        // clouds live in the top part
            for (int x = 0; x < W; x++) {
                float xx = x / (float) W;
                float nx = xx * 2.5f, ny = yy * 2.3f; // lower frequency = bigger, fuller clouds
                float wx = fbm(nx * .8f + 3.1f, ny * .8f + 7.7f), wy = fbm(nx * .8f + 11.3f, ny * .8f + 2.9f);
                float sx = nx * 1.1f + wx * 1.4f, sy = ny * 2.1f + wy * .9f; // stretched sideways like real cloud banks
                float dn = fbm(sx, sy);
                float dens = dn * (.62f + .38f * band) - (1 - band) * .32f;
                if (xx < .4f && yy > .12f && yy < .55f) dens -= .07f; // a little thinner behind the temperature
                float c = smooth(1 - cov, 1 - cov + .2f, dens);
                if (c <= 0.003f) continue;
                float toward = fbm(sx - .06f, sy - .11f);       // sample toward the light (up-left)
                float light = .72f + (dn - toward) * 5.5f - c * .18f + (1 - yy) * .12f;
                light = Math.max(.15f, Math.min(1.08f, light));
                int r = clamp(shade[0] + (lit[0] - shade[0]) * light), g = clamp(shade[1] + (lit[1] - shade[1]) * light), b = clamp(shade[2] + (lit[2] - shade[2]) * light);
                int a = clamp(255 * c * maxA);
                px[y * W + x] = (a << 24) | (r << 16) | (g << 8) | b;
            }
        }
        Bitmap out = Bitmap.createBitmap(px, W, H, Bitmap.Config.ARGB_8888);
        CLOUD_CACHE.put(key, out);
        return out;
    }
    static int clamp(float v) { return Math.max(0, Math.min(255, Math.round(v))); }

    // ---------- icons ----------
    static Path cloud(float cx, float cy, float s) {
        Path a = new Path();
        a.addCircle(cx - s * .2f, cy + s * .02f, s * .19f, Path.Direction.CW);
        Path b = new Path(); b.addCircle(cx + s * .04f, cy - s * .08f, s * .27f, Path.Direction.CW);
        Path c = new Path(); c.addCircle(cx + s * .27f, cy + s * .05f, s * .17f, Path.Direction.CW);
        Path base = new Path(); base.addRoundRect(cx - s * .39f, cy + s * .02f, cx + s * .44f, cy + s * .22f, s * .1f, s * .1f, Path.Direction.CW);
        a.op(b, Path.Op.UNION); a.op(c, Path.Op.UNION); a.op(base, Path.Op.UNION);
        return a;
    }
    static void cloudFill(Canvas cv, float cx, float cy, float s, int top, int bot) {
        Path c = cloud(cx, cy, s);
        RectF b = new RectF(); c.computeBounds(b, true);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(0x40204A80);
        p.setMaskFilter(new BlurMaskFilter(Math.max(1, s * .05f), BlurMaskFilter.Blur.NORMAL));
        Path sh = new Path(c); sh.offset(0, s * .04f); cv.drawPath(sh, p);
        p.setMaskFilter(null);
        p.setColor(Color.WHITE); // full opacity; the gradient supplies the colour
        p.setShader(new LinearGradient(0, b.top, 0, b.bottom, top, bot, Shader.TileMode.CLAMP));
        cv.drawPath(c, p);
    }
    static void sunBall(Canvas cv, float cx, float cy, float r, boolean rays) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        if (rays) {
            p.setColor(0xFFFFC233); p.setStrokeWidth(r * .26f); p.setStrokeCap(Paint.Cap.ROUND);
            for (int a = 0; a < 360; a += 45) {
                double rad = Math.toRadians(a);
                cv.drawLine(cx + (float) Math.cos(rad) * r * 1.42f, cy + (float) Math.sin(rad) * r * 1.42f,
                            cx + (float) Math.cos(rad) * r * 1.72f, cy + (float) Math.sin(rad) * r * 1.72f, p);
            }
        }
        p.setShader(new RadialGradient(cx - r * .3f, cy - r * .35f, r * 1.5f, new int[]{0xFFFFF4A8, 0xFFFFCB3A, 0xFFFFA21F}, new float[]{0f, .55f, 1f}, Shader.TileMode.CLAMP));
        cv.drawCircle(cx, cy, r, p);
    }
    static void moon(Canvas cv, float cx, float cy, float r) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        Path m = new Path(); m.addCircle(cx, cy, r, Path.Direction.CW);
        Path cut = new Path(); cut.addCircle(cx + r * .55f, cy - r * .4f, r * .85f, Path.Direction.CW);
        m.op(cut, Path.Op.DIFFERENCE);
        p.setShader(new LinearGradient(cx - r, cy - r, cx + r, cy + r, 0xFFFFF6C8, 0xFFF0CF6E, Shader.TileMode.CLAMP));
        cv.drawPath(m, p);
    }
    static void drop(Canvas cv, float x, float y, float s) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        Path d = new Path();
        d.moveTo(x + s * .05f, y);
        d.cubicTo(x + s * .02f, y + s * .07f, x - s * .07f, y + s * .12f, x - s * .055f, y + s * .17f);
        d.cubicTo(x - s * .045f, y + s * .21f, x + s * .015f, y + s * .215f, x + s * .025f, y + s * .17f);
        d.cubicTo(x + s * .035f, y + s * .12f, x + s * .06f, y + s * .07f, x + s * .05f, y);
        d.close();
        p.setShader(new LinearGradient(0, y, 0, y + s * .2f, 0xFF8FD3FF, 0xFF2F8CEB, Shader.TileMode.CLAMP));
        cv.drawPath(d, p);
    }
    static void drawIcon(Canvas cv, String k, boolean day, float cx, float cy, float s) {
        final int W = 0xFFFFFFFF, WB = 0xFFD9E5F3;
        switch (k) {
            case "clear":
                if (day) sunBall(cv, cx, cy, s * .23f, true); else moon(cv, cx, cy, s * .34f);
                return;
            case "partly":
                if (day) sunBall(cv, cx + s * .15f, cy - s * .13f, s * .2f, false); else moon(cv, cx + s * .14f, cy - s * .14f, s * .22f);
                cloudFill(cv, cx - s * .04f, cy + s * .04f, s, W, WB);
                return;
            case "cloud":
                cloudFill(cv, cx + s * .12f, cy - s * .12f, s * .72f, 0xFFE8EEF6, 0xFFB9C8DA);
                cloudFill(cv, cx - s * .03f, cy + s * .03f, s, W, WB);
                return;
            case "fog": {
                cloudFill(cv, cx, cy - s * .08f, s, W, WB);
                Paint p = new Paint(Paint.ANTI_ALIAS_FLAG); p.setColor(0xE6FFFFFF); p.setStrokeWidth(s * .07f); p.setStrokeCap(Paint.Cap.ROUND);
                cv.drawLine(cx - s * .32f, cy + s * .3f, cx + s * .3f, cy + s * .3f, p);
                cv.drawLine(cx - s * .22f, cy + s * .42f, cx + s * .38f, cy + s * .42f, p);
                return;
            }
            case "rain":
                for (int i = -1; i <= 1; i++) drop(cv, cx + i * s * .2f, cy + s * .2f, s);
                cloudFill(cv, cx, cy - s * .12f, s, W, 0xFFCCD9E8);
                return;
            case "snow": {
                cloudFill(cv, cx, cy - s * .12f, s, W, WB);
                Paint p = new Paint(Paint.ANTI_ALIAS_FLAG); p.setColor(W);
                p.setShadowLayer(s * .03f, 0, 0, 0x55306090);
                for (int i = -1; i <= 1; i++) cv.drawCircle(cx + i * s * .2f, cy + s * (i == 0 ? .36f : .28f), s * .055f, p);
                return;
            }
            default: { // storm
                cloudFill(cv, cx, cy - s * .12f, s, 0xFFC7D1DE, 0xFF8494AB);
                Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
                Path b = new Path();
                b.moveTo(cx + s * .05f, cy + s * .06f); b.lineTo(cx - s * .12f, cy + s * .29f); b.lineTo(cx, cy + s * .29f);
                b.lineTo(cx - s * .06f, cy + s * .5f); b.lineTo(cx + s * .15f, cy + s * .22f); b.lineTo(cx + s * .03f, cy + s * .22f); b.close();
                p.setShader(new LinearGradient(0, cy, 0, cy + s * .5f, 0xFFFFE066, 0xFFFFA41B, Shader.TileMode.CLAMP));
                cv.drawPath(b, p);
            }
        }
    }
    static void drawSunEvent(Canvas cv, float cx, float cy, float s, boolean rise) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        RectF arc = new RectF(cx - s * .28f, cy - s * .16f, cx + s * .28f, cy + s * .4f);
        p.setShader(new LinearGradient(0, cy - s * .16f, 0, cy + s * .12f, 0xFFFFE07A, 0xFFFF9F1C, Shader.TileMode.CLAMP));
        cv.drawArc(arc, 180, 180, true, p);
        p.setShader(null); p.setColor(0xFFFFB23A); p.setStrokeWidth(s * .075f); p.setStrokeCap(Paint.Cap.ROUND);
        cv.drawLine(cx - s * .46f, cy + s * .14f, cx + s * .46f, cy + s * .14f, p);
        cv.drawLine(cx - s * .28f, cy + s * .29f, cx + s * .28f, cy + s * .29f, p);
        float ay = cy - s * .4f;
        if (rise) { cv.drawLine(cx, ay + s * .14f, cx, ay - s * .06f, p); cv.drawLine(cx, ay - s * .06f, cx - s * .1f, ay + s * .04f, p); cv.drawLine(cx, ay - s * .06f, cx + s * .1f, ay + s * .04f, p); }
        else { cv.drawLine(cx, ay - s * .06f, cx, ay + s * .14f, p); cv.drawLine(cx, ay + s * .14f, cx - s * .1f, ay + s * .04f, p); cv.drawLine(cx, ay + s * .14f, cx + s * .1f, ay + s * .04f, p); }
    }
}
