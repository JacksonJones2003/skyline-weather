package com.skyline.weather;

import android.app.Activity;
import android.content.pm.PackageManager;

import rikka.shizuku.Shizuku;

/**
 * Optional Shizuku integration: start a watchdog loop with shell identity so a killed process is
 * revived in under a minute instead of waiting for the next scheduled job.
 *
 * This is what makes the app appear in Shizuku's authorised-apps list, and the permission dialog is
 * Shizuku's own. Everything degrades to the JobScheduler chain when Shizuku is missing or denied.
 */
final class ShizukuWatchdog {
    static final int REQUEST_CODE = 4242;

    // Single instance guard: the pidfile makes repeated service starts idempotent, and a stale pid
    // fails kill(0) so the loop can always be restarted.
    private static final String LOOP =
            "PIDF=/data/local/tmp/skyline.wd.pid; " +
            "if [ -f \"$PIDF\" ] && kill -0 \"$(cat $PIDF)\" 2>/dev/null; then exit 0; fi; " +
            "echo $$ > \"$PIDF\"; " +
            "while true; do " +
            "pidof com.skyline.weather >/dev/null 2>&1 || " +
            "cmd jobscheduler run -f com.skyline.weather 8127 >/dev/null 2>&1; " +
            "sleep 45; done";

    private static Shizuku.OnBinderReceivedListener binderListener;

    private ShizukuWatchdog() { }

    static boolean available() {
        try { return Shizuku.pingBinder(); } catch (Throwable t) { return false; }
    }

    static boolean granted() {
        try {
            return available() && !Shizuku.isPreV11()
                    && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Ask Shizuku for permission (it shows its own dialog) and start the loop once granted. */
    static void request(Activity activity) {
        try {
            Diag.log(activity, "shizuku ping=" + available() + " granted=" + granted());
            if (Shizuku.isPreV11()) return;
            if (granted()) { startLoop(); return; }
            // The provider hands over the binder asynchronously, so at first resume it is usually
            // not ready yet; wait for it and ask then.
            if (binderListener == null) {
                binderListener = () -> activity.runOnUiThread(() -> request(activity));
                Shizuku.addBinderReceivedListenerSticky(binderListener);
            }
            if (!available()) return;
            if (Shizuku.shouldShowRequestPermissionRationale()) {
                Diag.log(activity, "shizuku: rationale (denied previously)");
                return;
            }
            Diag.log(activity, "shizuku: requesting permission");
            Shizuku.requestPermission(REQUEST_CODE);
        } catch (Throwable ignored) { }
    }

    static void startLoop() {
        if (!granted()) return;
        try {
            // newProcess is private in 13.1.x (deprecated in favour of UserService, which needs
            // AIDL stubs this dependency-free build does not generate). Reflection keeps us on the
            // stable public permission API while still getting a shell process.
            java.lang.reflect.Method m = Shizuku.class.getDeclaredMethod(
                    "newProcess", String[].class, String[].class, String.class);
            m.setAccessible(true);
            Process p = (Process) m.invoke(null, new String[]{"sh", "-c", LOOP}, null, null);
            // The loop writes nothing; close the pipes so nothing blocks, and let it detach.
            try { p.getInputStream().close(); } catch (Throwable ignored) { }
            try { p.getErrorStream().close(); } catch (Throwable ignored) { }
            try { p.getOutputStream().close(); } catch (Throwable ignored) { }
        } catch (Throwable ignored) { }
    }
}
