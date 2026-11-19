package com.skyline.weather;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.os.Build;

/**
 * Hears back from Android's package installer about an in-app update (see Updater).
 *
 *  - Needs the person's OK: shows Android's install confirmation. If the app is in the background
 *    (where Android won't let it open a screen), a notification offers it instead.
 *  - Done: this runs in the freshly updated app, so it posts "Updated to …" to tap and open it.
 *    The widget's refresh chain is re-armed by BootReceiver on MY_PACKAGE_REPLACED, as for any update.
 *  - Failed or cancelled: tells the page, if it's open.
 */
public class UpdateReceiver extends BroadcastReceiver {
    private static final String CHANNEL = "app_updates";
    private static final int NOTE_ID = 4207;

    @Override public void onReceive(Context c, Intent i) {
        if (i == null || !Updater.STATUS_ACTION.equals(i.getAction())) return;
        int status = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirm = Build.VERSION.SDK_INT >= 33 ? i.getParcelableExtra(Intent.EXTRA_INTENT, Intent.class) : legacyIntent(i);
            if (confirm == null) { MainActivity.report("error", 0, "Android didn't offer the install. Try again."); return; }
            confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (MainActivity.isOpen()) {
                MainActivity.report("confirm", 100, "");
                try { c.startActivity(confirm); return; } catch (Exception ignored) { }
            }
            notify(c, "Skyline Weather update ready", "Tap to install it", PendingIntent.getActivity(c, 8, confirm,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        } else if (status == PackageInstaller.STATUS_SUCCESS) {
            String v = "";
            try { v = c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName; } catch (Exception ignored) { }
            Intent open = new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            notify(c, "Skyline Weather updated" + (v.isEmpty() ? "" : " to " + v), "Tap to open it",
                    PendingIntent.getActivity(c, 9, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        } else {
            String msg = status == PackageInstaller.STATUS_FAILURE_ABORTED ? "The update was cancelled."
                    : status == PackageInstaller.STATUS_FAILURE_STORAGE ? "There isn't enough space for the update."
                    : status == PackageInstaller.STATUS_FAILURE_CONFLICT || status == PackageInstaller.STATUS_FAILURE_INCOMPATIBLE
                        ? "This update can't be installed over the current app."
                    : "The update couldn't be installed.";
            MainActivity.report("error", 0, msg);
        }
    }

    @SuppressWarnings("deprecation")
    private static Intent legacyIntent(Intent i) { return i.getParcelableExtra(Intent.EXTRA_INTENT); }

    private static void notify(Context c, String title, String text, PendingIntent tap) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm == null) return;
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "App updates", NotificationManager.IMPORTANCE_DEFAULT));
        Notification n = new Notification.Builder(c, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_blank)
                .setContentTitle(title).setContentText(text)
                .setContentIntent(tap).setAutoCancel(true).build();
        try { nm.notify(NOTE_ID, n); } catch (SecurityException ignored) { }   // notifications switched off
    }
}
