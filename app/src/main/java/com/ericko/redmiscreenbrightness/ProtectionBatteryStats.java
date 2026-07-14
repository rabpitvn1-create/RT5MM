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
    private static final String KEY_SENSOR_SAMPLES_DROPPED = "battery_sensor_samples_dropped";
    private static final String KEY_EVALUATIONS = "battery_evaluations";
    private static final String KEY_THROTTLED_EVALUATIONS = "battery_throttled_evaluations";
    private static final String KEY_LUX_PERSISTED = "battery_lux_persisted";
    private static final String KEY_DECISIONS = "battery_decisions";
    private static final String KEY_BRIGHTNESS_WRITES = "battery_brightness_writes";
    private static final String KEY_BRIGHTNESS_WRITE_SKIPS = "battery_brightness_write_skips";
    private static final String KEY_NORMAL_LOG_SKIPS = "battery_normal_log_skips";

    private static volatile ProtectionPowerState livePowerState;
    private static long sensorRegisteredDelta = 0L;
    private static long sensorUnregisteredDelta = 0L;
    private static long screenOnDelta = 0L;
    private static long screenOffDelta = 0L;
    private static long sensorSamplesDelta = 0L;
    private static long sensorSamplesDroppedDelta = 0L;
    private static long evaluationsDelta = 0L;
    private static long throttledEvaluationsDelta = 0L;
    private static long luxPersistedDelta = 0L;
    private static long decisionsDelta = 0L;
    private static long brightnessWritesDelta = 0L;
    private static long brightnessWriteSkipsDelta = 0L;
    private static long normalLogSkipsDelta = 0L;

    private ProtectionBatteryStats() {
    }

    public static void setPowerState(Context context, ProtectionPowerState state, String reason) {
        if (context == null || state == null) {
            return;
        }
        synchronized (ProtectionBatteryStats.class) {
            ProtectionPowerState previous = livePowerState;
            SharedPreferences prefs = null;
            if (previous == null) {
                prefs = getPrefs(context);
                previous = parsePowerState(prefs.getString(
                        KEY_POWER_STATE, ProtectionPowerState.OFF.name()));
                livePowerState = previous;
            }
            if (state == previous) return;

            if (prefs == null) prefs = getPrefs(context);
            livePowerState = state;
            prefs.edit()
                    .putString(KEY_POWER_STATE, state.name())
                    .putString(KEY_LAST_POWER_REASON, safe(reason))
                    .putLong(KEY_LAST_POWER_AT, System.currentTimeMillis())
                    .putLong(KEY_STATE_TRANSITIONS,
                            prefs.getLong(KEY_STATE_TRANSITIONS, 0L) + 1L)
                    .apply();
        }
    }

    public static ProtectionPowerState getPowerState(Context context) {
        ProtectionPowerState current = livePowerState;
        if (current != null) return current;
        current = parsePowerState(getPrefs(context).getString(
                KEY_POWER_STATE, ProtectionPowerState.OFF.name()));
        livePowerState = current;
        return current;
    }

    private static ProtectionPowerState parsePowerState(String value) {
        try {
            return ProtectionPowerState.valueOf(value);
        } catch (Throwable t) {
            return ProtectionPowerState.OFF;
        }
    }

    public static void reset(Context context) {
        synchronized (ProtectionBatteryStats.class) {
            livePowerState = null;
            sensorRegisteredDelta = 0L;
            sensorUnregisteredDelta = 0L;
            screenOnDelta = 0L;
            screenOffDelta = 0L;
            sensorSamplesDelta = 0L;
            sensorSamplesDroppedDelta = 0L;
            evaluationsDelta = 0L;
            throttledEvaluationsDelta = 0L;
            luxPersistedDelta = 0L;
            decisionsDelta = 0L;
            brightnessWritesDelta = 0L;
            brightnessWriteSkipsDelta = 0L;
            normalLogSkipsDelta = 0L;
        }
        getPrefs(context).edit()
                .putLong(KEY_STATE_TRANSITIONS, 0L)
                .putLong(KEY_SENSOR_REGISTERED, 0L)
                .putLong(KEY_SENSOR_UNREGISTERED, 0L)
                .putLong(KEY_SCREEN_ON, 0L)
                .putLong(KEY_SCREEN_OFF, 0L)
                .putLong(KEY_SENSOR_SAMPLES, 0L)
                .putLong(KEY_SENSOR_SAMPLES_DROPPED, 0L)
                .putLong(KEY_EVALUATIONS, 0L)
                .putLong(KEY_THROTTLED_EVALUATIONS, 0L)
                .putLong(KEY_LUX_PERSISTED, 0L)
                .putLong(KEY_DECISIONS, 0L)
                .putLong(KEY_BRIGHTNESS_WRITES, 0L)
                .putLong(KEY_BRIGHTNESS_WRITE_SKIPS, 0L)
                .putLong(KEY_NORMAL_LOG_SKIPS, 0L)
                .apply();
    }

    public static void flush(Context context) {
        long sensorRegistered;
        long sensorUnregistered;
        long screenOn;
        long screenOff;
        long sensorSamples;
        long sensorSamplesDropped;
        long evaluations;
        long throttledEvaluations;
        long luxPersisted;
        long decisions;
        long brightnessWrites;
        long brightnessWriteSkips;
        long normalLogSkips;

        synchronized (ProtectionBatteryStats.class) {
            sensorRegistered = sensorRegisteredDelta;
            sensorUnregistered = sensorUnregisteredDelta;
            screenOn = screenOnDelta;
            screenOff = screenOffDelta;
            sensorSamples = sensorSamplesDelta;
            sensorSamplesDropped = sensorSamplesDroppedDelta;
            evaluations = evaluationsDelta;
            throttledEvaluations = throttledEvaluationsDelta;
            luxPersisted = luxPersistedDelta;
            decisions = decisionsDelta;
            brightnessWrites = brightnessWritesDelta;
            brightnessWriteSkips = brightnessWriteSkipsDelta;
            normalLogSkips = normalLogSkipsDelta;

            sensorRegisteredDelta = 0L;
            sensorUnregisteredDelta = 0L;
            screenOnDelta = 0L;
            screenOffDelta = 0L;
            sensorSamplesDelta = 0L;
            sensorSamplesDroppedDelta = 0L;
            evaluationsDelta = 0L;
            throttledEvaluationsDelta = 0L;
            luxPersistedDelta = 0L;
            decisionsDelta = 0L;
            brightnessWritesDelta = 0L;
            brightnessWriteSkipsDelta = 0L;
            normalLogSkipsDelta = 0L;
        }

        if (sensorRegistered == 0L && sensorUnregistered == 0L && screenOn == 0L && screenOff == 0L
                && sensorSamples == 0L && sensorSamplesDropped == 0L
                && evaluations == 0L && throttledEvaluations == 0L
                && luxPersisted == 0L && decisions == 0L && brightnessWrites == 0L
                && brightnessWriteSkips == 0L && normalLogSkips == 0L) {
            return;
        }

        SharedPreferences prefs = getPrefs(context);
        prefs.edit()
                .putLong(KEY_SENSOR_REGISTERED, prefs.getLong(KEY_SENSOR_REGISTERED, 0L) + sensorRegistered)
                .putLong(KEY_SENSOR_UNREGISTERED, prefs.getLong(KEY_SENSOR_UNREGISTERED, 0L) + sensorUnregistered)
                .putLong(KEY_SCREEN_ON, prefs.getLong(KEY_SCREEN_ON, 0L) + screenOn)
                .putLong(KEY_SCREEN_OFF, prefs.getLong(KEY_SCREEN_OFF, 0L) + screenOff)
                .putLong(KEY_SENSOR_SAMPLES, prefs.getLong(KEY_SENSOR_SAMPLES, 0L) + sensorSamples)
                .putLong(KEY_SENSOR_SAMPLES_DROPPED,
                        prefs.getLong(KEY_SENSOR_SAMPLES_DROPPED, 0L) + sensorSamplesDropped)
                .putLong(KEY_EVALUATIONS, prefs.getLong(KEY_EVALUATIONS, 0L) + evaluations)
                .putLong(KEY_THROTTLED_EVALUATIONS, prefs.getLong(KEY_THROTTLED_EVALUATIONS, 0L) + throttledEvaluations)
                .putLong(KEY_LUX_PERSISTED, prefs.getLong(KEY_LUX_PERSISTED, 0L) + luxPersisted)
                .putLong(KEY_DECISIONS, prefs.getLong(KEY_DECISIONS, 0L) + decisions)
                .putLong(KEY_BRIGHTNESS_WRITES, prefs.getLong(KEY_BRIGHTNESS_WRITES, 0L) + brightnessWrites)
                .putLong(KEY_BRIGHTNESS_WRITE_SKIPS, prefs.getLong(KEY_BRIGHTNESS_WRITE_SKIPS, 0L) + brightnessWriteSkips)
                .putLong(KEY_NORMAL_LOG_SKIPS, prefs.getLong(KEY_NORMAL_LOG_SKIPS, 0L) + normalLogSkips)
                .apply();
    }

    public static void recordSensorRegistered(Context context) {
        synchronized (ProtectionBatteryStats.class) {
            sensorRegisteredDelta++;
        }
    }

    public static void recordSensorUnregistered(Context context) {
        synchronized (ProtectionBatteryStats.class) {
            sensorUnregisteredDelta++;
        }
    }

    public static void recordScreenOn(Context context) {
        synchronized (ProtectionBatteryStats.class) {
            screenOnDelta++;
        }
    }

    public static void recordScreenOff(Context context) {
        synchronized (ProtectionBatteryStats.class) {
            screenOffDelta++;
        }
    }

    public static void recordSensorSample(Context context) {
        synchronized (ProtectionBatteryStats.class) {
            sensorSamplesDelta++;
        }
    }

    public static void recordSensorSampleDropped(Context context) {
        synchronized (ProtectionBatteryStats.class) {
            sensorSamplesDroppedDelta++;
        }
    }

    public static void recordEvaluation(Context context) {
        synchronized (ProtectionBatteryStats.class) {
            evaluationsDelta++;
        }
    }

    public static void recordThrottledEvaluation(Context context) {
        synchronized (ProtectionBatteryStats.class) {
            throttledEvaluationsDelta++;
        }
    }

    public static void recordLuxPersisted(Context context) {
        synchronized (ProtectionBatteryStats.class) {
            luxPersistedDelta++;
        }
    }

    public static void recordDecision(Context context) {
        synchronized (ProtectionBatteryStats.class) {
            decisionsDelta++;
        }
    }

    public static void recordBrightnessWrite(Context context) {
        synchronized (ProtectionBatteryStats.class) {
            brightnessWritesDelta++;
        }
    }

    public static void recordBrightnessWriteSkip(Context context) {
        synchronized (ProtectionBatteryStats.class) {
            brightnessWriteSkipsDelta++;
        }
    }

    public static void recordNormalLogSkip(Context context) {
        synchronized (ProtectionBatteryStats.class) {
            normalLogSkipsDelta++;
        }
    }

    public static String getDiagnosticText(Context context) {
        SharedPreferences prefs = getPrefs(context);
        long now = System.currentTimeMillis();
        long lastAt = prefs.getLong(KEY_LAST_POWER_AT, 0L);
        String lastAge = lastAt <= 0L
                ? context.getString(R.string.value_never)
                : context.getString(
                        R.string.age_ago,
                        context.getString(
                                R.string.duration_seconds,
                                Math.max(0L, now - lastAt) / 1000L));

        long sensorRegistered;
        long sensorUnregistered;
        long screenOn;
        long screenOff;
        long sensorSamples;
        long sensorSamplesDropped;
        long evaluations;
        long throttledEvaluations;
        long luxPersisted;
        long decisions;
        long brightnessWrites;
        long brightnessWriteSkips;
        long normalLogSkips;
        synchronized (ProtectionBatteryStats.class) {
            sensorRegistered = sensorRegisteredDelta;
            sensorUnregistered = sensorUnregisteredDelta;
            screenOn = screenOnDelta;
            screenOff = screenOffDelta;
            sensorSamples = sensorSamplesDelta;
            sensorSamplesDropped = sensorSamplesDroppedDelta;
            evaluations = evaluationsDelta;
            throttledEvaluations = throttledEvaluationsDelta;
            luxPersisted = luxPersistedDelta;
            decisions = decisionsDelta;
            brightnessWrites = brightnessWritesDelta;
            brightnessWriteSkips = brightnessWriteSkipsDelta;
            normalLogSkips = normalLogSkipsDelta;
        }

        return context.getString(
                R.string.battery_diagnostic_text,
                AutoBrightnessManager.getPowerStateText(context, getPowerState(context)),
                prefs.getString(KEY_LAST_POWER_REASON, context.getString(R.string.value_none)),
                lastAge,
                prefs.getLong(KEY_STATE_TRANSITIONS, 0L),
                prefs.getLong(KEY_SCREEN_ON, 0L) + screenOn,
                prefs.getLong(KEY_SCREEN_OFF, 0L) + screenOff,
                prefs.getLong(KEY_SENSOR_REGISTERED, 0L) + sensorRegistered,
                prefs.getLong(KEY_SENSOR_UNREGISTERED, 0L) + sensorUnregistered,
                prefs.getLong(KEY_SENSOR_SAMPLES, 0L) + sensorSamples,
                prefs.getLong(KEY_SENSOR_SAMPLES_DROPPED, 0L) + sensorSamplesDropped,
                prefs.getLong(KEY_EVALUATIONS, 0L) + evaluations,
                prefs.getLong(KEY_THROTTLED_EVALUATIONS, 0L) + throttledEvaluations,
                prefs.getLong(KEY_LUX_PERSISTED, 0L) + luxPersisted,
                prefs.getLong(KEY_DECISIONS, 0L) + decisions,
                prefs.getLong(KEY_BRIGHTNESS_WRITES, 0L) + brightnessWrites,
                prefs.getLong(KEY_BRIGHTNESS_WRITE_SKIPS, 0L) + brightnessWriteSkips,
                prefs.getLong(KEY_NORMAL_LOG_SKIPS, 0L) + normalLogSkips);
    }

    private static String safe(String value) {
        return value == null ? "none" : value;
    }

    private static SharedPreferences getPrefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(BrightnessLevels.PREFS, Context.MODE_PRIVATE);
    }
}
