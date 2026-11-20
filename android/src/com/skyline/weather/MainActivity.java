package com.skyline.weather;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.webkit.GeolocationPermissions;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.FrameLayout;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;

public class MainActivity extends Activity {
    private WebView web;
    private float statusBarCss = 0;

    // The file behind the system's "sans-serif" font, as listed in the phone's font config.
    private java.io.File systemSansFont() {
        try {
            String xml = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get("/system/etc/fonts.xml")), "UTF-8");
            int at = xml.indexOf("<family name=\"sans-serif\"");
            if (at >= 0) {
                java.util.regex.Matcher m = java.util.regex.Pattern.compile(">\\s*([\\w.-]+\\.(?:ttf|otf))").matcher(xml.substring(at));
                if (m.find()) { java.io.File f = new java.io.File("/system/fonts/" + m.group(1)); if (f.canRead()) return f; }
            }
        } catch (Exception ignored) {}
        java.io.File roboto = new java.io.File("/system/fonts/Roboto-Regular.ttf");
        return roboto.canRead() ? roboto : null;
    }

    // Ask for the screen's fastest refresh rate (e.g. 120 Hz) at the current resolution, so animations are as smooth as the display allows
    private void highRefresh() {
        try {
            android.view.Display d = getDisplay(); if (d == null) return;
            android.view.Display.Mode cur = d.getMode(), best = cur;
            for (android.view.Display.Mode m : d.getSupportedModes())
                if (m.getPhysicalWidth() == cur.getPhysicalWidth() && m.getPhysicalHeight() == cur.getPhysicalHeight() && m.getRefreshRate() > best.getRefreshRate()) best = m;
            android.view.WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.preferredDisplayModeId = best.getModeId();
            lp.preferredRefreshRate = best.getRefreshRate();
            getWindow().setAttributes(lp);
        } catch (Exception ignored) {}
    }
    @Override public void onAttachedToWindow() { super.onAttachedToWindow(); highRefresh(); }

    private boolean night() {
        return (getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES;
    }
    private void pushDark() { if (web != null) web.evaluateJavascript("window.__setDark && window.__setDark(" + night() + ")", null); }
    @Override public void onConfigurationChanged(android.content.res.Configuration c) { super.onConfigurationChanged(c); pushDark(); }

    private void pushStatusBar() {
        if (web != null) web.evaluateJavascript("(function(r){r.setProperty('--sat','" + statusBarCss + "px');r.setProperty('--sal','"
                + sideLeftCss + "px');r.setProperty('--sar','" + sideRightCss + "px')})(document.documentElement.style)", null);
    }
    private float sideLeftCss, sideRightCss;
    private FrameLayout root;
    private GeolocationPermissions.Callback geoCallback;
    private String geoOrigin;

    /**
     * Finish when backgrounded so onDestroy can release the WebView.
     *
     * The keep-alive service keeps this process alive for the widget, and a live WebView costs
     * hundreds of MB; on a phone this memory-tight that is what lmkd kills. The widget only needs
     * the service, so the UI does not need to linger in the background.
     */
    @Override protected void onStop() {
        super.onStop();
        if (!isChangingConfigurations()) finish();
    }

    /**
     * Free the WebView when the screen is gone: the keep-alive service keeps this process alive, so
     * holding a whole Chromium instance there made lmkd kill the app on a memory-tight phone.
     */
    @Override protected void onDestroy() {
        if (current == this) current = null;
        try {
            if (root != null && web != null) root.removeView(web);
            if (web != null) { web.loadUrl("about:blank"); web.destroy(); web = null; }
        } catch (Throwable ignored) { }
        super.onDestroy();
    }

    // ---- in-app updates (see Updater): the page shows progress through window.__updState ----
    private static volatile MainActivity current;
    private boolean resumed;
    static boolean isOpen() { MainActivity m = current; return m != null && m.resumed; }
    /** Tells the open page how an update is going; does nothing if the app isn't open. */
    static void report(String state, int pct, String msg) {
        MainActivity m = current; if (m == null) return;
        String js = "window.__updState&&window.__updState(" + org.json.JSONObject.quote(state) + "," + pct + "," + org.json.JSONObject.quote(msg == null ? "" : msg) + ")";
        m.runOnUiThread(() -> { if (m.web != null) m.web.evaluateJavascript(js, null); });
    }
    @Override protected void onPause() { resumed = false; super.onPause(); }

    /** Runs the page's "use my current location" flow so the home place follows the phone. */
    private void followLocation() {
        if (web == null) return;
        web.evaluateJavascript(
                "(function(){var b=document.getElementById('useLoc');"
                + "if(!b)return 0;b.click();return 1})()", null);
    }

    // a weather alert notification that opened the app: the page shows that alert (takeAlertId / __openAlert)
    private volatile String pendingAlert;
    @Override protected void onNewIntent(android.content.Intent i) {
        super.onNewIntent(i); setIntent(i);
        String id = i == null ? null : i.getStringExtra(WeatherAlerts.EXTRA);
        if (id != null && web != null) web.evaluateJavascript("window.__openAlert&&window.__openAlert(" + org.json.JSONObject.quote(id) + ")", null);
    }

    /** Rain alerts need notification permission (Android 13+): ask once, a moment after the app opens. */
    void askNotifications() {
        if (android.os.Build.VERSION.SDK_INT < 33 || !(RainAlert.enabled(this) || WeatherAlerts.enabled(this))) return;
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return;
        try { requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 7); } catch (Throwable ignored) { }
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        android.content.SharedPreferences sp = getSharedPreferences(WeatherWidget.PREFS, MODE_PRIVATE);
        if (!sp.getBoolean("askedNotif", false)) {
            sp.edit().putBoolean("askedNotif", true).apply();
            new android.os.Handler(getMainLooper()).postDelayed(() -> { if (resumed) askNotifications(); }, 2500);
        }
        try { WidgetKeepAliveService.start(this); } catch (Throwable ignored) { }   // rain alerts run from it too
        followLocation();
        // optional: lets the app revive itself through Shizuku, and makes it appear in Shizuku's list
        ShizukuWatchdog.request(this);
    }

    @Override protected void onCreate(Bundle b) {
        if (getIntent() != null) pendingAlert = getIntent().getStringExtra(WeatherAlerts.EXTRA);
        super.onCreate(b);
        WeatherRefreshJob.schedule(this);
        WidgetKeepAliveService.start(this);
        // test hook: "am start ... --es widgetPreview rain" shows that weather on the widget, then closes
        String wp = getIntent().getStringExtra("widgetPreview");
        if (wp != null) {
            android.content.Intent i = new android.content.Intent(this, WeatherWidget.class);
            i.setAction(WeatherWidget.ACTION_REFRESH);
            i.putExtra("fetch", false);
            if (!wp.isEmpty() && !wp.equals("off")) i.putExtra("preview", wp);
            sendBroadcast(i);
            finish();
            return;
        }
        // Opening the app is the one moment the process is guaranteed to be running and unfrozen,
        // so make the widget current right here instead of waiting for the next scheduled run.
        new Thread(() -> { try { WeatherWidget.update(getApplicationContext(), true); } catch (Throwable ignored) {} }).start();
        getWindow().setDecorFitsSystemWindows(false);
        // draw into the camera-hole area on every edge, sideways too
        android.view.WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        getWindow().setAttributes(lp);
        highRefresh();
        root = new FrameLayout(this);
        setSky("#2f74c8", "#8fc6f0");
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            Insets s = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            // the page runs up under the status bar so the sky shows behind the clock and battery;
            // it is told the bar's height so its header still sits below it
            // the sky also runs under the camera hole and side bars when the phone is sideways; only the
            // page's buttons and panels are moved clear of them (through --sal / --sar)
            v.setPadding(0, 0, 0, s.bottom);
            float dp = getResources().getDisplayMetrics().density;
            // the top of the page has to clear both the status bar and the camera hole, whichever reaches lower
            int top = Math.max(s.top, insets.getInsetsIgnoringVisibility(WindowInsets.Type.statusBars()).top);
            android.view.DisplayCutout cut = insets.getDisplayCutout();
            if (cut != null) for (android.graphics.Rect r : cut.getBoundingRects()) if (r.top == 0 && r.bottom < v.getHeight() / 3) top = Math.max(top, r.bottom);
            // plus a little breathing room so headers never crowd the camera hole
            statusBarCss = top / dp + 8;
            sideLeftCss = s.left / dp;
            sideRightCss = s.right / dp;
            pushStatusBar();
            return WindowInsets.CONSUMED;
        });

        web = new WebView(this);
        web.setBackgroundColor(Color.TRANSPARENT);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER); // no rubber-band stretch when pulling past the top
        WebSettings ws = web.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setGeolocationEnabled(true);
        web.addJavascriptInterface(new Bridge(), "AndroidApp");
        current = this;
        // the page asks for its cloud images at https://skyline.app/<name>.webp; hand them over from the app's own files
        web.setWebViewClient(new android.webkit.WebViewClient() {
            @Override public android.webkit.WebResourceResponse shouldInterceptRequest(WebView v, android.webkit.WebResourceRequest r) {
                android.net.Uri u = r.getUrl();
                String name = u.getLastPathSegment();
                // story and video feeds, fetched by the app (allowed hosts only)
                if ("skyline.app".equals(u.getHost()) && "feed".equals(name)) {
                    try {
                        java.net.URL f = new java.net.URL(u.getQueryParameter("u"));
                        String host = f.getHost();
                        if (!"https".equals(f.getProtocol()) || !(host.equals("www.bing.com") || host.equals("www.youtube.com"))) throw new Exception("blocked");
                        java.net.HttpURLConnection h = (java.net.HttpURLConnection) f.openConnection();
                        h.setRequestProperty("User-Agent", "Mozilla/5.0"); h.setConnectTimeout(8000); h.setReadTimeout(8000);
                        return new android.webkit.WebResourceResponse("text/xml", "UTF-8", h.getInputStream());
                    } catch (Exception e) { return new android.webkit.WebResourceResponse("text/plain", null, 502, "Bad Gateway", null, null); }
                }
                // the phone's own system font, read from the phone at runtime (follows the font set in Settings)
                if ("skyline.app".equals(u.getHost()) && "phone-font.ttf".equals(name)) {
                    java.io.File f = systemSansFont();
                    if (f != null) {
                        try { return new android.webkit.WebResourceResponse("font/ttf", null, new java.io.FileInputStream(f)); }
                        catch (java.io.IOException ignored) {}
                    }
                    return new android.webkit.WebResourceResponse("font/ttf", null, 404, "Not Found", null, null);
                }
                if ("skyline.app".equals(u.getHost()) && name != null && name.matches("clouds-[a-z]+-[0-9]+\\.webp")) {
                    try { return new android.webkit.WebResourceResponse("image/webp", null, getAssets().open(name)); }
                    catch (java.io.IOException ignored) {}
                }
                return null;
            }
            @Override public void onPageFinished(WebView v, String url) {
                pushStatusBar(); pushDark();
                // Follow the phone: run the page's own "use my current location" flow, which
                // makes the current city the home place (and therefore the widget's place).
                v.evaluateJavascript(
                        "(function(){var b=document.getElementById('useLoc');" 
                        + "if(!b)return 0;b.click();return 1})()", null);
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback cb) {
                if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                        || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                    cb.invoke(origin, true, true);
                } else {
                    geoCallback = cb; geoOrigin = origin;
                    requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, 1);
                }
            }
        });
        root.addView(web, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);

        WindowInsetsController c = getWindow().getInsetsController();
        if (c != null) c.setSystemBarsAppearance(0,
            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);

        try (InputStream in = getAssets().open("weather.html")) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192]; int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            web.loadDataWithBaseURL("https://skyline.app/", out.toString("UTF-8"), "text/html", "UTF-8", null);
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    private void setSky(String a, String b) {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            new int[]{Color.parseColor(a), Color.parseColor(b)});
        root.setBackground(g);
    }

    @Override public void onRequestPermissionsResult(int code, String[] p, int[] r) {
        boolean ok = false;
        for (int x : r) if (x == PackageManager.PERMISSION_GRANTED) ok = true;
        if (geoCallback != null) { geoCallback.invoke(geoOrigin, ok, ok); geoCallback = null; }
    }

    @Override public void onBackPressed() {
        web.evaluateJavascript("window.__closeSheet?window.__closeSheet():0",
            v -> { if (!"1".equals(v)) finish(); });
    }

    class Bridge {
        @JavascriptInterface public void openReader(String url, String title, String top, String bottom) {
            android.content.Intent i = new android.content.Intent(MainActivity.this, ReaderActivity.class);
            i.putExtra("url", url); i.putExtra("title", title); i.putExtra("top", top); i.putExtra("bottom", bottom);
            runOnUiThread(() -> startActivity(i));
        }
        // dark status bar icons while a light page (city management) is showing
        @JavascriptInterface public void lightPage(boolean on) {
            runOnUiThread(() -> {
                WindowInsetsController c = getWindow().getInsetsController();
                if (c != null) c.setSystemBarsAppearance(on ? WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS : 0,
                        WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS);
            });
        }
        @JavascriptInterface public void savePrefs(String name, double lat, double lon, String unit) {
            android.content.SharedPreferences sp = getSharedPreferences(WeatherWidget.PREFS, MODE_PRIVATE);
            boolean moved = Math.abs(sp.getFloat("lat", 999) - (float) lat) > 0.001 || Math.abs(sp.getFloat("lon", 999) - (float) lon) > 0.001;
            boolean changed = moved || !unit.equals(sp.getString("unit", "")) || !name.equals(sp.getString("name", ""));
            if (!changed) return;
            sp.edit().putString("name", name).putFloat("lat", (float) lat).putFloat("lon", (float) lon).putString("unit", unit).apply();
            android.content.Intent i = new android.content.Intent(MainActivity.this, WeatherWidget.class);
            i.setAction(WeatherWidget.ACTION_REFRESH);
            i.putExtra("fetch", moved);
            sendBroadcast(i);
        }
        // The app just loaded a fresh forecast for the widget's city: hand it over so the widget redraws now,
        // instead of waiting for its own 30-minute refresh.
        @JavascriptInterface public void pushForecast(double lat, double lon, String json) {
            android.content.SharedPreferences sp = getSharedPreferences(WeatherWidget.PREFS, MODE_PRIVATE);
            if (Math.abs(sp.getFloat("lat", 999) - (float) lat) > 0.001 || Math.abs(sp.getFloat("lon", 999) - (float) lon) > 0.001) return;
            sp.edit().putString("json", json).putLong("at", System.currentTimeMillis()).apply();
            android.content.Intent i = new android.content.Intent(MainActivity.this, WeatherWidget.class);
            i.setAction(WeatherWidget.ACTION_REFRESH);
            i.putExtra("fetch", false);
            sendBroadcast(i);
        }
        @JavascriptInterface public void share(String text) {
            runOnUiThread(() -> {
                android.content.Intent i = new android.content.Intent(android.content.Intent.ACTION_SEND);
                i.setType("text/plain"); i.putExtra(android.content.Intent.EXTRA_TEXT, text);
                startActivity(android.content.Intent.createChooser(i, "Share weather"));
            });
        }
        // foldable phones report a hinge-angle sensor; the page offers its unfolded-only options on them even while folded
        @JavascriptInterface public boolean isFoldable() {
            try { return getPackageManager().hasSystemFeature("android.hardware.sensor.hinge_angle"); } catch (Exception e) { return false; }
        }
        // Saved cities and settings, kept in Android's own app storage as well as the page's. The page's storage
        // (inside the WebView) can lose recent writes when the app is closed for an update; this copy survives.
        @JavascriptInterface public void keep(String key, String json) {
            if (key == null || key.isEmpty() || key.length() > 40) return;
            getSharedPreferences("page_store", MODE_PRIVATE).edit().putString(key, json).commit();   // on the bridge thread, not the UI
        }
        @JavascriptInterface public String kept() {
            try {
                org.json.JSONObject o = new org.json.JSONObject();
                for (java.util.Map.Entry<String, ?> e : getSharedPreferences("page_store", MODE_PRIVATE).getAll().entrySet())
                    if (e.getValue() instanceof String) o.put(e.getKey(), e.getValue());
                // nothing kept yet (first run with this): the widget's city is still known, so offer it as the city list
                if (!o.has("places")) {
                    android.content.SharedPreferences w = getSharedPreferences(WeatherWidget.PREFS, MODE_PRIVATE);
                    String name = w.getString("name", null); float lat = w.getFloat("lat", 999), lon = w.getFloat("lon", 999);
                    if (name != null && lat != 999 && lon != 999) {
                        org.json.JSONObject p = new org.json.JSONObject().put("name", name).put("lat", (double) lat).put("lon", (double) lon);
                        o.put("widgetPlace", new org.json.JSONArray().put(p).toString());
                    }
                }
                return o.toString();
            } catch (Exception e) { return "{}"; }
        }
        // weather warnings (NWS) on / off, and the alert a notification was tapped for (taken once by the page)
        @JavascriptInterface public boolean weatherAlerts() { return WeatherAlerts.enabled(MainActivity.this); }
        @JavascriptInterface public void setWeatherAlerts(boolean on) {
            WeatherAlerts.setEnabled(MainActivity.this, on);
            if (on) runOnUiThread(MainActivity.this::askNotifications);
        }
        @JavascriptInterface public String takeAlertId() { String id = pendingAlert; pendingAlert = null; return id == null ? "" : id; }
        // morning summary (the ⋮ menu): on / off and the time, in minutes after midnight
        @JavascriptInterface public String morningBrief() { return (MorningBrief.enabled(MainActivity.this) ? "1," : "0,") + MorningBrief.at(MainActivity.this); }
        @JavascriptInterface public void setMorningBrief(boolean on, int minutes) {
            MorningBrief.set(MainActivity.this, on, minutes);
            if (on) runOnUiThread(MainActivity.this::askNotifications);
        }
        // rain alerts on / off (the ⋮ menu); turning them on asks for notification permission if needed
        @JavascriptInterface public boolean rainAlerts() { return RainAlert.enabled(MainActivity.this); }
        @JavascriptInterface public void setRainAlerts(boolean on) {
            RainAlert.setEnabled(MainActivity.this, on);
            if (on) runOnUiThread(MainActivity.this::askNotifications);
        }
        // in-app updates: download, check and install a release from this app's GitHub (see Updater)
        @JavascriptInterface public void installUpdate(String url, String sha256) { Updater.start(MainActivity.this, url, sha256); }
        @JavascriptInterface public boolean canInstallUpdates() { return Updater.canInstall(MainActivity.this); }
        @JavascriptInterface public void openInstallSettings() { runOnUiThread(() -> Updater.openInstallSettings(MainActivity.this)); }
        // whether the phone is on Wi-Fi right now: the automatic update check only runs then (never on mobile data)
        @JavascriptInterface public boolean onWifi() {
            try {
                android.net.ConnectivityManager cm = getSystemService(android.net.ConnectivityManager.class);
                android.net.NetworkCapabilities nc = cm == null ? null : cm.getNetworkCapabilities(cm.getActiveNetwork());
                return nc != null && nc.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI);
            } catch (Exception e) { return false; }
        }
        // the page's update check compares this with the newest GitHub release
        @JavascriptInterface public String appVersion() {
            try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception e) { return ""; }
        }
        // opens a download or release page in the browser; only this app's own GitHub releases are allowed
        @JavascriptInterface public void openUrl(String url) {
            if (url == null || !url.startsWith("https://github.com/JacksonJones2003/skyline-weather/")) return;
            runOnUiThread(() -> {
                try { startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))); } catch (Exception ignored) {}
            });
        }
        @JavascriptInterface public void setSky(String a, String b) {
            runOnUiThread(() -> { try { MainActivity.this.setSky(a, b); } catch (Exception ignored) {} });
        }
    }
}
