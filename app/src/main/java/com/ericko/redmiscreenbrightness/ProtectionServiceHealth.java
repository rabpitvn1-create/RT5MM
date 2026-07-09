package com.ericko.redmiscreenbrightness;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Locale;

public final class ProtectionServiceHealth {
    private static final String KEY_SERVICE_CREATED_AT = "protection_service_created_at";
    private static final String KEY_SERVICE_STARTED_AT = "protection_service_started_at";
    private static final String KEY_SERVICE_HEARTBEAT_AT = "protection_service_heartbeat_at";
    private static final String KEY_SERVICE_HEARTBEAT_REASON = "protection_service_heartbeat_reason";
    private static final String KEY_SERVICE_STOPPED_AT = "protection_service_stopped_at";
    private static final String KEY_SERVICE_STOP_REASON = "protection_service_stop_reason";
    private static final String KEY_FOREGROUND_ACTIVE = "protection_service_foreground_active";
    private static final String KEY_FOREGROUND_REASON = "protection_service_foreground_reason";
    private static final String KEY_SCREEN_RECEIVER_REGISTERED = "protection_screen_receiver_registered";
    private static final String KEY_BOOT_RESTORE_AT = "protection_boot_restore_at";
    private static final String KEY_BOOT_RESTORE_ACTION = "protection_boot_restore_action";

    private static final long ACTIVE_HEARTBEAT_STALE_MS = 30000L;
    private static final long USER_HOLD_HEARTBEAT_STALE_MS = 120000L;
    private static final long SLEEP_HEARTBEAT_STALE_MS = 10L * 60L * 1000L;

    private ProtectionServiceHealth() {
    }

    public static void markServiceCreated(Context context, String reason) {
        long now = System.currentTimeMillis();
        getPrefs(context).edit()
                .putLong(KEY_SERVICE_CREATED_AT, now)
                .putLong(KEY_SERVICE_STARTED_AT, now)
                .putLong(KEY_SERVICE_HEARTBEAT_AT, now)
                .putString(KEY_SERVICE_HEARTBEAT_REASON, safe(reason))
                .putLong(KEY_SERVICE_STOPPED_AT, 0L)
                .putString(KEY_SERVICE_STOP_REASON, "")
                .putBoolean(KEY_FOREGROUND_ACTIVE, false)
                .putString(KEY_FOREGROUND_REASON, "starting")
                .putBoolean(KEY_SCREEN_RECEIVER_REGISTERED, false)
                .apply();
    }

    public static void markHeartbeat(Context context, String reason) {
        long now = System.currentTimeMillis();
        getPrefs(context).edit()
                .putLong(KEY_SERVICE_HEARTBEAT_AT, now)
                .putString(KEY_SERVICE_HEARTBEAT_REASON, safe(reason))
                .apply();
    }

    public static void markForeground(Context context, boolean active, String reason) {
        getPrefs(context).edit()
                .putBoolean(KEY_FOREGROUND_ACTIVE, active)
                .putString(KEY_FOREGROUND_REASON, safe(reason))
                .apply();
    }

    public static void markScreenReceiver(Context context, boolean registered) {
        getPrefs(context).edit()
                .putBoolean(KEY_SCREEN_RECEIVER_REGISTERED, registered)
                .apply();
    }

    public static void markServiceStopped(Context context, String reason) {
        long now = System.currentTimeMillis();
        getPrefs(context).edit()
                .putLong(KEY_SERVICE_STOPPED_AT, now)
                .putString(KEY_SERVICE_STOP_REASON, safe(reason))
                .putBoolean(KEY_FOREGROUND_ACTIVE, false)
                .putBoolean(KEY_SCREEN_RECEIVER_REGISTERED, false)
                .apply();
    }

    public static void markBootRestore(Context context, String action) {
        getPrefs(context).edit()
                .putLong(KEY_BOOT_RESTORE_AT, System.currentTimeMillis())
                .putString(KEY_BOOT_RESTORE_ACTION, safe(action))
                .apply();
    }

    public static boolean isServiceHealthy(Context context) {
        return AutoBrightnessManager.isAutoEnabled(context)
                && isHeartbeatFresh(context)
                && getPrefs(context).getBoolean(KEY_FOREGROUND_ACTIVE, false);
    }

    public static String getMainHealthText(Context context) {
        if (!AutoBrightnessManager.isAutoEnabled(context)) {
            return "idle";
        }
        SharedPreferences prefs = getPrefs(context);
        long heartbeatAt = prefs.getLong(KEY_SERVICE_HEARTBEAT_AT, 0L);
        boolean foreground = prefs.getBoolean(KEY_FOREGROUND_ACTIVE, false);
        ProtectionPowerState powerState = ProtectionBatteryStats.getPowerState(context);
        if (heartbeatAt <= 0L) {
            return "starting";
        }
        if (!foreground) {
            return "limited · foreground missing";
        }
        long ageMs = Math.max(0L, System.currentTimeMillis() - heartbeatAt);
        if (ageMs > getHeartbeatStaleMs(powerState)) {
            return "limited · heartbeat " + formatAge(ageMs) + " ago";
        }
        if (powerState == ProtectionPowerState.SCREEN_OFF_SLEEP) {
            return "sleeping · sensor paused";
        }
        if (powerState == ProtectionPowerState.USER_HOLD_LOW_POWER) {
            return "low power · user hold";
        }
        return "running";
    }

    public static String getDiagnosticText(Context context) {
        SharedPreferences prefs = getPrefs(context);
        long now = System.currentTimeMillis();
        long createdAt = prefs.getLong(KEY_SERVICE_CREATED_AT, 0L);
        long startedAt = prefs.getLong(KEY_SERVICE_STARTED_AT, 0L);
        long heartbeatAt = prefs.getLong(KEY_SERVICE_HEARTBEAT_AT, 0L);
        long stoppedAt = prefs.getLong(KEY_SERVICE_STOPPED_AT, 0L);
        long bootRestoreAt = prefs.getLong(KEY_BOOT_RESTORE_AT, 0L);
        ProtectionPowerState powerState = ProtectionBatteryStats.getPowerState(context);

        return "Service health"
                + "\nHealth: " + getMainHealthText(context)
                + "\nPower state: " + powerState.name()
                + "\nHeartbeat fresh: " + (isHeartbeatFresh(context) ? "yes" : "no")
                + "\nHeartbeat stale limit: " + getHeartbeatStaleMs(powerState) + "ms"
                + "\nLast heartbeat: " + ageText(now, heartbeatAt)
                + "\nHeartbeat reason: " + prefs.getString(KEY_SERVICE_HEARTBEAT_REASON, "none")
                + "\nForeground: " + (prefs.getBoolean(KEY_FOREGROUND_ACTIVE, false) ? "active" : "inactive")
                + " / " + prefs.getString(KEY_FOREGROUND_REASON, "none")
                + "\nScreen receiver: " + (prefs.getBoolean(KEY_SCREEN_RECEIVER_REGISTERED, false) ? "registered" : "not registered")
                + "\nCreated: " + ageText(now, createdAt)
                + "\nStarted: " + ageText(now, startedAt)
                + "\nStopped: " + ageText(now, stoppedAt)
                + "\nStop reason: " + prefs.getString(KEY_SERVICE_STOP_REASON, "none")
                + "\nBoot restore: " + ageText(now, bootRestoreAt)
                + "\nBoot action: " + prefs.getString(KEY_BOOT_RESTORE_ACTION, "none");
    }

    private static boolean isHeartbeatFresh(Context context) {
        long heartbeatAt = getPrefs(context).getLong(KEY_SERVICE_HEARTBEAT_AT, 0L);
        if (heartbeatAt <= 0L) {
            return false;
        }
        ProtectionPowerState powerState = ProtectionBatteryStats.getPowerState(context);
        return System.currentTimeMillis() - heartbeatAt <= getHeartbeatStaleMs(powerState);
    }

    private static long getHeartbeatStaleMs(ProtectionPowerState powerState) {
        if (powerState == ProtectionPowerState.SCREEN_OFF_SLEEP) {
            return SLEEP_HEARTBEAT_STALE_MS;
        }
        if (powerState == ProtectionPowerState.USER_HOLD_LOW_POWER) {
            return USER_HOLD_HEARTBEAT_STALE_MS;
        }
        return ACTIVE_HEARTBEAT_STALE_MS;
    }

    private static String ageText(long now, long at) {
        if (at <= 0L) {
            return "never";
        }
        return formatAge(Math.max(0L, now - at)) + " ago";
    }

    private static String formatAge(long ageMs) {
        if (ageMs < 1000L) {
            return ageMs + "ms";
        }
        if (ageMs < 60000L) {
            return (ageMs / 1000L) + "s";
        }
        return String.format(Locale.US, "%dm %02ds", ageMs / 60000L, (ageMs / 1000L) % 60L);
    }

    private static String safe(String value) {
        return value == null ? "unknown" : value;
    }

    private static SharedPreferences getPrefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(BrightnessLevels.PREFS, Context.MODE_PRIVATE);
    }
}
