package com.skyline.weather;

import android.content.Context;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Append-only diagnostics in the app's external files dir.
 *
 * vivo drops this app's logcat output, which made a failing fetch invisible. This writes the same
 * facts somewhere adb can read: /sdcard/Android/data/com.skyline.weather/files/fetch.log
 */
final class Diag {
    private static final long MAX_BYTES = 64 * 1024;

    private Diag() { }

    static synchronized void log(Context c, String msg) {
        try {
            File dir = c.getExternalFilesDir(null);
            if (dir == null) return;
            if (!dir.exists()) dir.mkdirs();
            File f = new File(dir, "fetch.log");
            if (f.length() > MAX_BYTES) f.delete();
            FileWriter w = new FileWriter(f, true);
            w.write(new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(new Date()) + " " + msg + "\n");
            w.close();
        } catch (Throwable ignored) { }
    }
}
