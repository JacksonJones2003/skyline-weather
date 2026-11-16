package com.skyline.weather;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.location.LocationManager;
import android.os.Build;
import android.os.CancellationSignal;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Keeps the widget's place in step with where the phone actually is.
 *
 * Until now the place only changed when the WebView geolocated while the app was open, so the
 * widget sat on whatever city was last picked. This reads the platform's location (last known fix,
 * or a fresh one-shot when that is stale) from the same background refresh loop that updates the
 * weather, and rewrites the stored place when the phone has actually moved.
 */
final class LocationFix {
    private static final float MOVE_METERS = 1500f;              // ignore drift / same neighbourhood
    private static final long MAX_FIX_AGE_MS = 10 * 60 * 1000L;  // a 10-minute-old fix is still "here"
    private static final long FRESH_TIMEOUT_MS = 6000L;

    private LocationFix() { }

    /** @return true when the stored place changed and the widget needs a fetch. */
    static boolean updateIfMoved(Context c) {
        if (!hasPermission(c)) return false;
        LocationManager lm = (LocationManager) c.getSystemService(Context.LOCATION_SERVICE);
        if (lm == null) return false;

        Location best = newest(lm);
        if (best == null || System.currentTimeMillis() - best.getTime() > MAX_FIX_AGE_MS) {
            Location fresh = freshFix(c, lm);
            if (fresh != null && (best == null || fresh.getTime() >= best.getTime())) best = fresh;
        }
        if (best == null) return false;

        SharedPreferences sp = c.getSharedPreferences(WeatherWidget.PREFS, Context.MODE_PRIVATE);
        boolean known = sp.contains("lat");
        Location prev = new Location("");
        prev.setLatitude(sp.getFloat("lat", 0f));
        prev.setLongitude(sp.getFloat("lon", 0f));
        float moved = known ? prev.distanceTo(best) : Float.MAX_VALUE;
        if (known && moved < MOVE_METERS) return false;

        String name = known ? sp.getString("name", "") : "";
        String found = geocode(c, best.getLatitude(), best.getLongitude());
        if (found != null && !found.isEmpty()) name = found;
        if (name == null || name.isEmpty()) name = "My location";

        sp.edit().putString("name", name)
                .putFloat("lat", (float) best.getLatitude())
                .putFloat("lon", (float) best.getLongitude())
                .putLong("at", 0L)          // force the next refresh to fetch for the new place
                .apply();
        android.util.Log.i("SkylineWidget", "moved " + Math.round(moved) + " m -> " + name);
        return true;
    }

    private static boolean hasPermission(Context c) {
        return c.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || c.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private static Location newest(LocationManager lm) {
        Location best = null;
        for (String p : new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER}) {
            try {
                Location l = lm.getLastKnownLocation(p);
                if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
            } catch (Throwable ignored) { }
        }
        return best;
    }

    private static Location freshFix(Context c, LocationManager lm) {
        if (Build.VERSION.SDK_INT < 30) return null;
        for (String p : new String[]{LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER}) {
            final CountDownLatch done = new CountDownLatch(1);
            final Location[] out = new Location[1];
            try {
                CancellationSignal cancel = new CancellationSignal();
                lm.getCurrentLocation(p, cancel, c.getMainExecutor(), loc -> { out[0] = loc; done.countDown(); });
                done.await(FRESH_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            } catch (SecurityException e) {
                android.util.Log.w("SkylineWidget", "background location refused", e);
                return null;
            } catch (Throwable ignored) { }
            if (out[0] != null) return out[0];
        }
        return null;
    }

    private static String geocode(Context c, double lat, double lon) {
        try {
            Geocoder g = new Geocoder(c, Locale.getDefault());
            List<Address> list = g.getFromLocation(lat, lon, 1);
            if (list == null || list.isEmpty()) return null;
            Address a = list.get(0);
            String city = a.getLocality();
            if (city == null || city.isEmpty()) city = a.getSubAdminArea();
            if (city == null || city.isEmpty()) city = a.getAdminArea();
            return city;
        } catch (Throwable t) {
            android.util.Log.w("SkylineWidget", "reverse geocode failed", t);
            return null;
        }
    }
}
