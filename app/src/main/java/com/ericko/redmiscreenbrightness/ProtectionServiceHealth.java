package com.ericko.redmiscreenbrightness;

import android.content.Context;
import android.content.SharedPreferences;

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
                && AutoBrightnessService.isRunning()
                && getPrefs(context).getBoolean(KEY_FOREGROUND_ACTIVE, false);
    }

    public static String getMainHealthText(Context context) {
        if (!AutoBrightnessManager.isAutoEnabled(context)) {
            return context.getString(R.string.health_idle);
        }
        SharedPreferences prefs = getPrefs(context);
        boolean foreground = prefs.getBoolean(KEY_FOREGROUND_ACTIVE, false);
        ProtectionPowerState powerState = ProtectionBatteryStats.getPowerState(context);
        if (!AutoBrightnessService.isRunning()) {
            return context.getString(R.string.health_starting);
        }
        if (!foreground) {
            return context.getString(R.string.health_foreground_missing);
        }
        if (powerState == ProtectionPowerState.SCREEN_OFF_SLEEP) {
            return context.getString(R.string.health_sleeping);
        }
        if (powerState == ProtectionPowerState.USER_HOLD_LOW_POWER) {
            return context.getString(R.string.health_user_hold);
        }
        return context.getString(R.string.health_running);
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

        String none = context.getString(R.string.value_none);
        return context.getString(
                R.string.service_diagnostic_text,
                getMainHealthText(context),
                AutoBrightnessManager.getPowerStateText(context, powerState),
                context.getString(AutoBrightnessService.isRunning()
                        ? R.string.value_running : R.string.value_stopped),
                ageText(context, now, heartbeatAt),
                prefs.getString(KEY_SERVICE_HEARTBEAT_REASON, none),
                context.getString(prefs.getBoolean(KEY_FOREGROUND_ACTIVE, false)
                        ? R.string.value_active : R.string.value_inactive),
                prefs.getString(KEY_FOREGROUND_REASON, none),
                context.getString(prefs.getBoolean(KEY_SCREEN_RECEIVER_REGISTERED, false)
                        ? R.string.value_registered : R.string.value_not_registered),
                ageText(context, now, createdAt),
                ageText(context, now, startedAt),
                ageText(context, now, stoppedAt),
                prefs.getString(KEY_SERVICE_STOP_REASON, none),
                ageText(context, now, bootRestoreAt),
                prefs.getString(KEY_BOOT_RESTORE_ACTION, none));
    }

    private static String ageText(Context context, long now, long at) {
        if (at <= 0L) {
            return context.getString(R.string.value_never);
        }
        return context.getString(
                R.string.age_ago,
                formatAge(context, Math.max(0L, now - at)));
    }

    private static String formatAge(Context context, long ageMs) {
        if (ageMs < 1000L) {
            return context.getString(R.string.duration_ms, ageMs);
        }
        if (ageMs < 60000L) {
            return context.getString(R.string.duration_seconds, ageMs / 1000L);
        }
        return context.getString(
                R.string.duration_minutes_seconds,
                ageMs / 60000L,
                (ageMs / 1000L) % 60L);
    }

    private static String safe(String value) {
        return value == null ? "unknown" : value;
    }

    private static SharedPreferences getPrefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(BrightnessLevels.PREFS, Context.MODE_PRIVATE);
    }
}
