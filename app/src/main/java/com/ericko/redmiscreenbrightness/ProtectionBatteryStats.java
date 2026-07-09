package com.ericko.redmiscreenbrightness;

import android.content.Context;
import android.content.SharedPreferences;

public final class ProtectionBatteryStats {
    private static final String KEY_POWER_STATE = "battery_power_state";
    private static final String KEY_LAST_POWER_REASON = "battery_last_power_reason";
    private static final String KEY_LAST_POWER_AT = "battery_last_power_at";
    private static final String KEY_STATE_TRANSITIONS = "battery_state_transitions";
    private static final String KEY_SENSOR_REGISTERED = "battery_sensor_registered";
    private static final String KEY_SENSOR_UNREGISTERED = "battery_sensor_unregistered";
    private static final String KEY_SCREEN_ON = "battery_screen_on";
    private static final String KEY_SCREEN_OFF = "battery_screen_off";
    private static final String KEY_SENSOR_SAMPLES = "battery_sensor_samples";
    private static final String KEY_EVALUATIONS = "battery_evaluations";
    private static final String KEY_THROTTLED_EVALUATIONS = "battery_throttled_evaluations";
    private static final String KEY_LUX_PERSISTED = "battery_lux_persisted";
    private static final String KEY_DECISIONS = "battery_decisions";
    private static final String KEY_BRIGHTNESS_WRITES = "battery_brightness_writes";
    private static final String KEY_BRIGHTNESS_WRITE_SKIPS = "battery_brightness_write_skips";
    private static final String KEY_NORMAL_LOG_SKIPS = "battery_normal_log_skips";

    private ProtectionBatteryStats() {
    }

    public static void setPowerState(Context context, ProtectionPowerState state, String reason) {
        if (context == null || state == null) {
            return;
        }
        SharedPreferences prefs = getPrefs(context);
        String oldState = prefs.getString(KEY_POWER_STATE, ProtectionPowerState.OFF.name());
        SharedPreferences.Editor editor = prefs.edit()
                .putString(KEY_POWER_STATE, state.name())
                .putString(KEY_LAST_POWER_REASON, safe(reason))
                .putLong(KEY_LAST_POWER_AT, System.currentTimeMillis());
        if (!state.name().equals(oldState)) {
            editor.putLong(KEY_STATE_TRANSITIONS, prefs.getLong(KEY_STATE_TRANSITIONS, 0L) + 1L);
        }
        editor.apply();
    }

    public static ProtectionPowerState getPowerState(Context context) {
        String value = getPrefs(context).getString(KEY_POWER_STATE, ProtectionPowerState.OFF.name());
        try {
            return ProtectionPowerState.valueOf(value);
        } catch (Throwable t) {
            return ProtectionPowerState.OFF;
        }
    }

    public static void recordSensorRegistered(Context context) {
        increment(context, KEY_SENSOR_REGISTERED);
    }

    public static void recordSensorUnregistered(Context context) {
        increment(context, KEY_SENSOR_UNREGISTERED);
    }

    public static void recordScreenOn(Context context) {
        increment(context, KEY_SCREEN_ON);
    }

    public static void recordScreenOff(Context context) {
        increment(context, KEY_SCREEN_OFF);
    }

    public static void recordSensorSample(Context context) {
        increment(context, KEY_SENSOR_SAMPLES);
    }

    public static void recordEvaluation(Context context) {
        increment(context, KEY_EVALUATIONS);
    }

    public static void recordThrottledEvaluation(Context context) {
        increment(context, KEY_THROTTLED_EVALUATIONS);
    }

    public static void recordLuxPersisted(Context context) {
        increment(context, KEY_LUX_PERSISTED);
    }

    public static void recordDecision(Context context) {
        increment(context, KEY_DECISIONS);
    }

    public static void recordBrightnessWrite(Context context) {
        increment(context, KEY_BRIGHTNESS_WRITES);
    }

    public static void recordBrightnessWriteSkip(Context context) {
        increment(context, KEY_BRIGHTNESS_WRITE_SKIPS);
    }

    public static void recordNormalLogSkip(Context context) {
        increment(context, KEY_NORMAL_LOG_SKIPS);
    }

    public static String getDiagnosticText(Context context) {
        SharedPreferences prefs = getPrefs(context);
        long now = System.currentTimeMillis();
        long lastAt = prefs.getLong(KEY_LAST_POWER_AT, 0L);
        String lastAge = lastAt <= 0L ? "never" : (Math.max(0L, now - lastAt) / 1000L) + "s ago";
        return "Battery layer"
                + "\nPower state: " + prefs.getString(KEY_POWER_STATE, ProtectionPowerState.OFF.name())
                + "\nLast power reason: " + prefs.getString(KEY_LAST_POWER_REASON, "none")
                + "\nLast power change: " + lastAge
                + "\nState transitions: " + prefs.getLong(KEY_STATE_TRANSITIONS, 0L)
                + "\nScreen on/off: " + prefs.getLong(KEY_SCREEN_ON, 0L) + " / " + prefs.getLong(KEY_SCREEN_OFF, 0L)
                + "\nSensor reg/unreg: " + prefs.getLong(KEY_SENSOR_REGISTERED, 0L) + " / " + prefs.getLong(KEY_SENSOR_UNREGISTERED, 0L)
                + "\nSensor samples: " + prefs.getLong(KEY_SENSOR_SAMPLES, 0L)
                + "\nEvaluations: " + prefs.getLong(KEY_EVALUATIONS, 0L)
                + "\nThrottled evaluations: " + prefs.getLong(KEY_THROTTLED_EVALUATIONS, 0L)
                + "\nLux persisted: " + prefs.getLong(KEY_LUX_PERSISTED, 0L)
                + "\nDecisions: " + prefs.getLong(KEY_DECISIONS, 0L)
                + "\nBrightness writes/skips: " + prefs.getLong(KEY_BRIGHTNESS_WRITES, 0L) + " / " + prefs.getLong(KEY_BRIGHTNESS_WRITE_SKIPS, 0L)
                + "\nNormal log skips: " + prefs.getLong(KEY_NORMAL_LOG_SKIPS, 0L);
    }

    private static void increment(Context context, String key) {
        if (context == null || key == null) {
            return;
        }
        SharedPreferences prefs = getPrefs(context);
        prefs.edit().putLong(key, prefs.getLong(key, 0L) + 1L).apply();
    }

    private static String safe(String value) {
        return value == null ? "none" : value;
    }

    private static SharedPreferences getPrefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(BrightnessLevels.PREFS, Context.MODE_PRIVATE);
    }
}
