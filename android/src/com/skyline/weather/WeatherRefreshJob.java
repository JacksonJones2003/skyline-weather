package com.skyline.weather;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;

/**
 * Keeps the widget fresh.
 *
 * A single JobScheduler periodic job used to be the only wake-up, so anything that dropped it
 * (every adb install, a reboot, Doze, an OEM battery manager) left the widget stale until the app
 * was opened by hand. Three independent paths are armed instead, and each one re-arms the others:
 * this periodic job, an AlarmManager while-idle alarm, and the provider's updatePeriodMillis.
 */
public class WeatherRefreshJob extends JobService {
    private static final int JOB_ID = 8127;
    private static final int JOB_NOW = 8129;
    private static final int ALARM_REQUEST = 8128;
    private static final long PERIOD_MS = 15 * 60 * 1000L;
    // 15 min is the shortest period Android allows; flex is what it may add on top.
    // 5 min is the documented minimum flex, so runs land every 15-20 min instead of 15-30.
    private static final long FLEX_MS = 5 * 60 * 1000L;
    // Deliberately longer than the job period: while the job is healthy it re-arms this
    // before it fires, so the alarm only actually goes off when the job has stopped.
    private static final long ALARM_MS = 15 * 60 * 1000L;
    // Match vivo's own weather widget, which stops waking the phone overnight: from 23:00 to 07:00
    // the alarm is non-wakeup, so it is delivered the next time the phone is awake anyway (and the
    // screen-on refresh in WidgetKeepAliveService covers the first look after that).
    private static final int WAKE_FROM_HOUR = 7;
    private static final int WAKE_UNTIL_HOUR = 23;

    public static void schedule(Context context) {
        JobScheduler scheduler = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (scheduler != null) {
            try {
                JobInfo job = new JobInfo.Builder(JOB_ID, new ComponentName(context, WeatherRefreshJob.class))
                        .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                        .setPersisted(true)
                        .setPeriodic(PERIOD_MS, FLEX_MS)
                        .build();
                scheduler.schedule(job);
            } catch (Throwable t) {
                android.util.Log.w("SkylineWidget", "could not schedule refresh job", t);
            }
        }
        armAlarm(context);
    }

    /** One refresh as soon as the OS allows, without waiting for the period. */
    public static void scheduleNow(Context context) {
        JobScheduler scheduler = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (scheduler == null) return;
        try {
            JobInfo job = new JobInfo.Builder(JOB_NOW, new ComponentName(context, WeatherRefreshJob.class))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setMinimumLatency(0)
                    .setOverrideDeadline(0)
                    .build();
            scheduler.schedule(job);
        } catch (Throwable t) {
            android.util.Log.w("SkylineWidget", "could not schedule immediate refresh", t);
        }
    }

    /** AlarmManager fallback: fires even when JobScheduler is deferred or the job was dropped. */
    static void armAlarm(Context context) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        Intent i = new Intent(context, WeatherWidget.class)
                .setAction(WeatherWidget.ACTION_REFRESH)
                .putExtra("fetch", true);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getBroadcast(context, ALARM_REQUEST, i, flags);
        long when = SystemClock.elapsedRealtime() + ALARM_MS;
        // An exact alarm is dispatched with a temporary app allowlist, so it is not parked in the
        // INFINITE_DEFER queue that swallows ordinary broadcasts to a cached process. Falls back to
        // an inexact while-idle alarm when the exact-alarm permission has not been granted.
        boolean waking = isWakingHours();
        boolean exact = true;
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            try { exact = am.canScheduleExactAlarms(); } catch (Throwable ignored) { exact = false; }
        }
        int type = waking ? AlarmManager.ELAPSED_REALTIME_WAKEUP : AlarmManager.ELAPSED_REALTIME;
        try {
            if (exact) am.setExactAndAllowWhileIdle(type, when, pi);
            else am.setAndAllowWhileIdle(type, when, pi);
        } catch (SecurityException e) {
            try { am.setAndAllowWhileIdle(type, when, pi); }
            catch (Throwable t) { android.util.Log.w("SkylineWidget", "could not arm refresh alarm", t); }
        } catch (Throwable t) {
            android.util.Log.w("SkylineWidget", "could not arm refresh alarm", t);
        }
        android.util.Log.i("SkylineWidget", "armed " + (exact ? "exact" : "inexact") + (waking ? " waking" : " non-waking") + " refresh alarm");
    }

    static boolean isWakingHours() {
        int hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
        return hour >= WAKE_FROM_HOUR && hour < WAKE_UNTIL_HOUR;
    }

    @Override public boolean onStartJob(JobParameters params) {
        armAlarm(getApplicationContext());   // keep the fallback alive on every run
        // A job run is how this app gets revived after the process is killed, so make sure the
        // foreground service comes back with it; otherwise the process dies again after the refresh.
        try { WidgetKeepAliveService.start(getApplicationContext()); } catch (Throwable ignored) { }
        new Thread(() -> {
            boolean success;
            try {
                success = WeatherWidget.update(getApplicationContext(), true);
            } catch (Throwable t) {
                success = false;
                android.util.Log.w("SkylineWidget", "scheduled refresh failed", t);
            }
            android.util.Log.i("SkylineWidget", "scheduled refresh " + (success ? "ok" : "failed"));
            jobFinished(params, !success);
        }, "SkylineWeatherRefresh").start();
        return true;
    }

    @Override public boolean onStopJob(JobParameters params) { return true; }
}
