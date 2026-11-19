package com.skyline.weather;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * In-app updates: downloads a release APK from this app's own GitHub releases, checks it, and hands
 * it to Android's package installer, which updates the app in place.
 *
 * Before anything is installed the download must be:
 *  - from this repository's release downloads (nothing else is accepted),
 *  - byte-for-byte the file GitHub lists for the release (its SHA-256),
 *  - this same app (same package name), with a higher version than the one running,
 *  - signed with the same key as the running app.
 * Android itself also refuses an update signed with a different key.
 *
 * Android shows its own confirmation the first times; the app can't (and shouldn't) install
 * without the person agreeing. Progress is reported to the page through MainActivity.report.
 */
final class Updater {
    static final String PREFIX = "https://github.com/JacksonJones2003/skyline-weather/releases/download/";
    static final String STATUS_ACTION = "com.skyline.weather.INSTALL_STATUS";
    private static volatile boolean busy;

    private Updater() { }

    /** Whether Android currently lets this app install packages ("Install unknown apps" for this app). */
    static boolean canInstall(Context c) {
        return c.getPackageManager().canRequestPackageInstalls();
    }

    /** Opens this app's "Install unknown apps" switch in Settings. */
    static void openInstallSettings(Context c) {
        Intent i = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + c.getPackageName()))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { c.startActivity(i); } catch (Exception e) {
            try { c.startActivity(new Intent(Settings.ACTION_SECURITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); } catch (Exception ignored) { }
        }
    }

    static void start(Context context, String url, String sha256) {
        final Context c = context.getApplicationContext();
        if (url == null || !url.startsWith(PREFIX) || !url.endsWith(".apk")) {
            MainActivity.report("error", 0, "That download isn't a Skyline Weather release.");
            return;
        }
        if (!canInstall(c)) { MainActivity.report("permission", 0, ""); return; }
        if (busy) return;
        busy = true;
        new Thread(() -> {
            try {
                File apk = download(c, url, sha256);
                MainActivity.report("verifying", 100, "");
                verify(c, apk);
                MainActivity.report("installing", 100, "");
                install(c, apk);
            } catch (Exception e) {
                String msg = e.getMessage();
                MainActivity.report("error", 0, msg == null || msg.isEmpty() ? "The update couldn't be downloaded. Try again." : msg);
            } finally {
                busy = false;
            }
        }, "skyline-updater").start();
    }

    private static File download(Context c, String url, String sha256) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setInstanceFollowRedirects(true);              // GitHub sends release files from its download servers
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        conn.setRequestProperty("User-Agent", "SkylineWeather-Updater");
        int code = conn.getResponseCode();
        if (code != 200) throw new IOException("The download failed (HTTP " + code + "). Try again.");
        if (!"https".equals(conn.getURL().getProtocol())) throw new IOException("The download wasn't secure, so it was stopped.");
        long total = conn.getContentLengthLong();
        File dir = new File(c.getCacheDir(), "update");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("There's no room to save the update.");
        File out = new File(dir, "SkylineWeather.apk");
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = conn.getInputStream(); OutputStream os = new FileOutputStream(out)) {
            byte[] buf = new byte[64 * 1024];
            long got = 0; int lastPct = -1, n;
            while ((n = in.read(buf)) > 0) {
                os.write(buf, 0, n); md.update(buf, 0, n); got += n;
                int pct = total > 0 ? (int) (got * 100 / total) : 0;
                if (pct != lastPct && pct % 2 == 0) { lastPct = pct; MainActivity.report("downloading", pct, ""); }
            }
        } finally { conn.disconnect(); }
        String want = sha256 == null ? "" : sha256.trim().toLowerCase().replaceFirst("^sha256:", "");
        if (!want.isEmpty() && !want.equals(hex(md.digest()))) {
            out.delete();
            throw new IOException("The download didn't match the release, so it wasn't installed.");
        }
        return out;
    }

    private static void verify(Context c, File apk) throws Exception {
        PackageManager pm = c.getPackageManager();
        int flags = Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
        PackageInfo update = pm.getPackageArchiveInfo(apk.getPath(), flags);
        PackageInfo running = pm.getPackageInfo(c.getPackageName(), flags);
        if (update == null || !c.getPackageName().equals(update.packageName))
            throw new IOException("The download isn't Skyline Weather, so it wasn't installed.");
        if (update.getLongVersionCode() <= running.getLongVersionCode())
            throw new IOException("You already have this version or a newer one.");
        if (!signers(update).equals(signers(running)) || signers(running).isEmpty())
            throw new IOException("The update isn't signed by Skyline Weather, so it wasn't installed.");
    }

    @SuppressWarnings("deprecation")
    private static List<String> signers(PackageInfo p) throws Exception {
        Signature[] sigs = null;
        if (Build.VERSION.SDK_INT >= 28 && p.signingInfo != null)
            sigs = p.signingInfo.getApkContentsSigners();      // the key(s) the APK is signed with right now
        if (sigs == null) sigs = p.signatures;
        List<String> out = new ArrayList<>();
        if (sigs == null) return out;
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        for (Signature s : sigs) out.add(hex(md.digest(s.toByteArray())));
        Collections.sort(out);
        return out;
    }

    private static void install(Context c, File apk) throws Exception {
        PackageInstaller installer = c.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(c.getPackageName());
        params.setSize(apk.length());
        // Android 12+: once this app installed its own previous update, later ones may go through without a prompt
        if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
        int id = installer.createSession(params);
        try (PackageInstaller.Session session = installer.openSession(id)) {
            try (OutputStream os = session.openWrite("base.apk", 0, apk.length()); InputStream in = new FileInputStream(apk)) {
                byte[] buf = new byte[64 * 1024]; int n;
                while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
                session.fsync(os);
            }
            Intent status = new Intent(c, UpdateReceiver.class).setAction(STATUS_ACTION);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
            session.commit(PendingIntent.getBroadcast(c, 7, status, flags).getIntentSender());
        }
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }
}
