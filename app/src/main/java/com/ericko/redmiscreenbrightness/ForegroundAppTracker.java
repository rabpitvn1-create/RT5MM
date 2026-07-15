package com.ericko.redmiscreenbrightness;

import android.app.AppOpsManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.os.Build;
import android.os.Process;

/** Reads foreground-app transitions only while a manual brightness hold is active. */
public final class ForegroundAppTracker {
    private static final long INITIAL_LOOKBACK_MS = 24L * 60L * 60L * 1000L;
    private static final String SYSTEM_UI_PACKAGE = "com.android.systemui";

    private ForegroundAppTracker() {
    }

    public static boolean hasUsageAccess(Context context) {
        if (context == null) return false;
        try {
            Context app = context.getApplicationContext();
            AppOpsManager operations =
                    (AppOpsManager) app.getSystemService(Context.APP_OPS_SERVICE);
            return operations != null
                    && operations.checkOpNoThrow(
                            AppOpsManager.OPSTR_GET_USAGE_STATS,
                            Process.myUid(),
                            app.getPackageName()) == AppOpsManager.MODE_ALLOWED;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Captures the app below the notification shade when the user finishes moving
     * Android's brightness slider. This query runs once per new manual session.
     */
    public static String captureForegroundPackage(Context context) {
        if (!hasUsageAccess(context)) return "";
        long now = System.currentTimeMillis();
        return findForegroundPackageSince(context, now - INITIAL_LOOKBACK_MS, "");
    }

    /** Returns the latest eligible foreground transition, or fallback when none is visible. */
    public static String findForegroundPackageSince(
            Context context, long beginWallMs, String fallbackPackage) {
        String fallback = fallbackPackage == null ? "" : fallbackPackage;
        if (context == null || !hasUsageAccess(context)) return fallback;
        try {
            UsageStatsManager usage = (UsageStatsManager) context.getApplicationContext()
                    .getSystemService(Context.USAGE_STATS_SERVICE);
            if (usage == null) return fallback;

            long now = System.currentTimeMillis();
            long begin = Math.max(0L, Math.min(beginWallMs, now));
            UsageEvents events = usage.queryEvents(begin, now);
            if (events == null) return fallback;

            UsageEvents.Event event = new UsageEvents.Event();
            String latestPackage = fallback;
            long latestTimestamp = Long.MIN_VALUE;
            while (events.hasNextEvent()) {
                events.getNextEvent(event);
                if (!isForegroundEvent(event.getEventType())) continue;
                String packageName = event.getPackageName();
                if (!isEligiblePackage(packageName)) continue;
                if (event.getTimeStamp() >= latestTimestamp) {
                    latestTimestamp = event.getTimeStamp();
                    latestPackage = packageName;
                }
            }
            return latestPackage;
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private static boolean isForegroundEvent(int eventType) {
        if (eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) return true;
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                && eventType == UsageEvents.Event.ACTIVITY_RESUMED;
    }

    private static boolean isEligiblePackage(String packageName) {
        if (packageName == null || packageName.trim().isEmpty()) return false;
        return !SYSTEM_UI_PACKAGE.equals(packageName)
                && !packageName.endsWith(".systemui");
    }
}
