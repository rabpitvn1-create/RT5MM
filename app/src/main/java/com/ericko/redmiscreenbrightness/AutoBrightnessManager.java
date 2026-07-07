package com.ericko.redmiscreenbrightness;

import android.content.Context;
import android.content.SharedPreferences;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;

import java.util.Locale;

public class AutoBrightnessManager implements SensorEventListener {
    private static final String KEY_AUTO_ENABLED = "auto_brightness_enabled";
    private static final String KEY_AUTO_MODE = "auto_brightness_mode";
    private static final String KEY_LAST_LUX = "auto_brightness_last_lux";
    private static final String KEY_SENSOR_AVAILABLE = "auto_brightness_sensor_available";
    private static final String KEY_USER_HOLD_UNTIL = "auto_brightness_user_hold_until";
    private static final String KEY_USER_HOLD_LUX = "auto_brightness_user_hold_lux";
    private static final String KEY_USER_HOLD_RAW = "auto_brightness_user_hold_raw";
    private static final String KEY_USER_HOLD_AT = "auto_brightness_user_hold_at";
    private static final String KEY_LAST_AUTO_RAW = "auto_brightness_last_auto_raw";
    private static final String KEY_LAST_AUTO_AT = "auto_brightness_last_auto_at";
    private static final String KEY_IGNORE_EXTERNAL_UNTIL = "auto_brightness_ignore_external_until";
    private static final String KEY_EXTERNAL_CANDIDATE_RAW = "auto_brightness_external_candidate_raw";
    private static final String KEY_EXTERNAL_CANDIDATE_SINCE = "auto_brightness_external_candidate_since";

    private static final float MAX_LUX = 120000f;
    private static final int SAMPLE_COUNT = 5;
    private static final int RAW_CHANGE_TOLERANCE = 2;
    private static final int USER_CHANGE_MIN_RAW = 6;
    private static final long APP_WRITE_GRACE_MS = 12000L;
    private static final long USER_INTENT_STABLE_MS = 3000L;
    public static final long USER_HOLD_MS = 45L * 60L * 1000L;
    private static final float USER_HOLD_RELEASE_PCT = 120f;
    private static final float USER_HOLD_RELEASE_MIN_LUX = 60f;

    public enum Mode {
        OFF, PROTECTING, USER_HOLD, UNAVAILABLE
    }

    private final Context appContext;
    private final SensorManager sensorManager;
    private final Sensor lightSensor;
    private final ProtectionPolicy protectionPolicy = new ProtectionPolicy();
    private final float[] samples = new float[SAMPLE_COUNT];
    private int sampleCount = 0;
    private int sampleIndex = 0;
    private boolean registered = false;
    private int candidatePercent = -1;
    private long candidateSince = 0L;

    public AutoBrightnessManager(Context context) {
        appContext = context.getApplicationContext();
        sensorManager = (SensorManager) appContext.getSystemService(Context.SENSOR_SERVICE);
        lightSensor = sensorManager == null ? null : sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT);
        saveSensorAvailable(appContext, lightSensor != null);
    }

    public boolean start() {
        if (!isAutoEnabled(appContext)) {
            saveMode(appContext, Mode.OFF);
            return false;
        }
        if (sensorManager == null || lightSensor == null) {
            markUnavailable(appContext);
            return false;
        }
        if (!registered) {
            registered = sensorManager.registerListener(this, lightSensor, SensorManager.SENSOR_DELAY_NORMAL);
            BrightnessLogManager.appendSnapshot(appContext, registered ? "PROTECTION_SENSOR_REGISTERED" : "PROTECTION_SENSOR_REGISTER_FAILED", getLastLux(appContext));
        }
        return registered;
    }

    public void stop() {
        if (registered && sensorManager != null) {
            sensorManager.unregisterListener(this);
            BrightnessLogManager.appendSnapshot(appContext, "PROTECTION_SENSOR_UNREGISTERED", getLastLux(appContext));
        }
        registered = false;
    }

    public void forceAutoReevaluate(String event) {
        clearUserHold(appContext);
        clearAutoWriteTracking(appContext);
        clearExternalCandidate(appContext);
        beginAppWriteGrace(appContext);
        saveMode(appContext, Mode.PROTECTING);
        candidatePercent = -1;
        candidateSince = 0L;
        BrightnessLogManager.appendSnapshot(appContext, event, getLastLux(appContext));
        evaluateLastLux(event);
    }

    public void resumeProtection(String event) {
        forceAutoReevaluate(event);
    }

    public void onScreenWake(String event) {
        beginAppWriteGrace(appContext);
        clearExternalCandidate(appContext);
        candidatePercent = -1;
        candidateSince = 0L;
        BrightnessLogManager.appendSnapshot(appContext, event, getLastLux(appContext));
        evaluateLastLux(event);
    }

    public void evaluateLastLux(String event) {
        float lux = getLastLux(appContext);
        if (lux >= 0f) {
            evaluateLux(lux, event);
        }
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event == null || event.sensor == null || event.sensor.getType() != Sensor.TYPE_LIGHT || event.values.length == 0) {
            return;
        }

        long now = System.currentTimeMillis();
        float avgLux = smooth(clampLux(event.values[0]));
        float trustedLux = protectionPolicy.filterLux(avgLux, now);
        saveLastLux(appContext, trustedLux);
        BrightnessLogManager.logSnapshotIfChanged(appContext, "PROTECTION_SENSOR_SAMPLE", trustedLux);
        evaluateLux(trustedLux, "PROTECTION_SENSOR_SAMPLE");
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    private void evaluateLux(float avgLux, String event) {
        long now = System.currentTimeMillis();

        if (isUserHoldActive(appContext, now)) {
            float holdLux = getUserHoldLux(appContext);
            if (!shouldReleaseUserHold(avgLux, holdLux)) {
                saveMode(appContext, Mode.USER_HOLD);
                BrightnessLogManager.logSnapshotIfChanged(appContext, "USER_HOLD_ACTIVE", avgLux);
                return;
            }
            clearUserHold(appContext);
            clearAutoWriteTracking(appContext);
            clearExternalCandidate(appContext);
            candidatePercent = -1;
            candidateSince = 0L;
            BrightnessLogManager.appendSnapshot(appContext, "USER_HOLD_RELEASED_BY_LUX_CHANGE", avgLux);
        }

        if (detectUserBrightnessChange(now, avgLux)) {
            return;
        }

        int currentPercent = BrightnessLevels.getCurrentPercent(appContext);
        int targetPercent = getTargetPercent(avgLux, currentPercent);
        if (candidatePercent != targetPercent) {
            candidatePercent = targetPercent;
            candidateSince = now;
            BrightnessLogManager.logSnapshotIfChanged(appContext, event + "_TARGET_" + targetPercent + "_PERCENT", avgLux);
            return;
        }
        long stableMs = protectionPolicy.getStableMs(currentPercent, targetPercent);
        if (now - candidateSince < stableMs) {
            return;
        }

        applyAutoPercent(targetPercent);
    }

    private void applyAutoPercent(int percent) {
        int raw = BrightnessLevels.getRawForPercent(percent);
        int currentRaw = BrightnessLevels.getSystemRaw(appContext, raw);
        if (Math.abs(currentRaw - raw) <= RAW_CHANGE_TOLERANCE) {
            saveLastAutoRaw(appContext, raw);
            beginAppWriteGrace(appContext);
            saveMode(appContext, Mode.PROTECTING);
            BrightnessLogManager.logSnapshotIfChanged(appContext, "PROTECTION_AT_" + percent + "_PERCENT", getLastLux(appContext));
            return;
        }

        boolean ok = BrightnessLevels.applyBrightness(appContext, percent, raw);
        if (ok) {
            saveLastAutoRaw(appContext, raw);
            beginAppWriteGrace(appContext);
            saveMode(appContext, Mode.PROTECTING);
            BrightnessLogManager.appendSnapshot(appContext, "PROTECTION_APPLIED_" + percent + "_PERCENT_RAW_" + raw, getLastLux(appContext));
        } else {
            BrightnessLogManager.appendSnapshot(appContext, "PROTECTION_APPLY_FAILED_" + percent + "_PERCENT_RAW_" + raw, getLastLux(appContext));
        }
    }

    private int getTargetPercent(float lux, int currentPercent) {
        return protectionPolicy.getTargetPercent(lux, currentPercent);
    }

    private boolean detectUserBrightnessChange(long now, float avgLux) {
        int lastAutoRaw = getLastAutoRaw(appContext);
        if (lastAutoRaw < 0) {
            return false;
        }
        if (now < getIgnoreExternalUntil(appContext)) {
            return false;
        }
        long lastAutoAt = getLastAutoAt(appContext);
        if (now - lastAutoAt < APP_WRITE_GRACE_MS) {
            return false;
        }

        int currentRaw = BrightnessLevels.getSystemRaw(appContext, lastAutoRaw);
        if (Math.abs(currentRaw - lastAutoRaw) < USER_CHANGE_MIN_RAW) {
            clearExternalCandidate(appContext);
            return false;
        }

        int candidateRaw = getExternalCandidateRaw(appContext);
        long candidateRawSince = getExternalCandidateSince(appContext);
        if (candidateRaw != currentRaw) {
            saveExternalCandidate(appContext, currentRaw, now);
            saveMode(appContext, Mode.USER_HOLD);
            BrightnessLogManager.appendSnapshot(appContext, "USER_HOLD_CANDIDATE_RAW_" + currentRaw + "_LAST_AUTO_RAW_" + lastAutoRaw, avgLux);
            return true;
        }

        saveMode(appContext, Mode.USER_HOLD);
        if (now - candidateRawSince < USER_INTENT_STABLE_MS) {
            BrightnessLogManager.logSnapshotIfChanged(appContext, "USER_HOLD_CANDIDATE_WAITING", avgLux);
            return true;
        }

        recordUserHold(appContext, currentRaw, avgLux, "USER_HOLD_CONFIRMED_RAW_" + currentRaw + "_LAST_AUTO_RAW_" + lastAutoRaw);
        clearExternalCandidate(appContext);
        candidatePercent = -1;
        candidateSince = 0L;
        return true;
    }

    private boolean shouldReleaseUserHold(float currentLux, float holdLux) {
        if (holdLux < 0f) {
            return false;
        }
        float delta = Math.abs(currentLux - holdLux);
        if (delta < USER_HOLD_RELEASE_MIN_LUX) {
            return false;
        }
        if (holdLux <= 0f) {
            return delta >= USER_HOLD_RELEASE_MIN_LUX;
        }
        return (delta * 100f / holdLux) >= USER_HOLD_RELEASE_PCT;
    }

    private float smooth(float lux) {
        samples[sampleIndex] = lux;
        sampleIndex = (sampleIndex + 1) % samples.length;
        if (sampleCount < samples.length) {
            sampleCount++;
        }
        float total = 0f;
        for (int i = 0; i < sampleCount; i++) {
            total += samples[i];
        }
        return total / sampleCount;
    }

    private float clampLux(float lux) {
        if (Float.isNaN(lux) || lux < 0f) return 0f;
        return Math.min(lux, MAX_LUX);
    }

    public static boolean hasLightSensor(Context context) {
        SensorManager manager = (SensorManager) context.getApplicationContext().getSystemService(Context.SENSOR_SERVICE);
        return manager != null && manager.getDefaultSensor(Sensor.TYPE_LIGHT) != null;
    }

    public static void setAutoEnabled(Context context, boolean enabled) {
        SharedPreferences.Editor editor = getPrefs(context).edit()
                .putBoolean(KEY_AUTO_ENABLED, enabled)
                .putString(KEY_AUTO_MODE, enabled ? Mode.PROTECTING.name() : Mode.OFF.name())
                .putLong(KEY_USER_HOLD_UNTIL, 0L)
                .putFloat(KEY_USER_HOLD_LUX, -1f)
                .remove(KEY_EXTERNAL_CANDIDATE_RAW)
                .remove(KEY_EXTERNAL_CANDIDATE_SINCE);
        if (enabled) {
            editor.remove(KEY_LAST_AUTO_RAW)
                    .remove(KEY_LAST_AUTO_AT)
                    .putLong(KEY_IGNORE_EXTERNAL_UNTIL, System.currentTimeMillis() + APP_WRITE_GRACE_MS);
        }
        editor.apply();
        BrightnessLogManager.appendSnapshot(context, enabled ? "PROTECTION_STATE_ON" : "PROTECTION_STATE_OFF", getLastLux(context));
    }

    public static boolean isAutoEnabled(Context context) {
        return getPrefs(context).getBoolean(KEY_AUTO_ENABLED, false);
    }

    public static void recordManualOverride(Context context) {
        recordUserHold(context, BrightnessLevels.getSystemRaw(context, -1), getLastLux(context), "USER_HOLD_RECORDED_COMPAT");
    }

    public static void recordUserHold(Context context, int raw, String event) {
        recordUserHold(context, raw, getLastLux(context), event);
    }

    private static void recordUserHold(Context context, int raw, float lux, String event) {
        long now = System.currentTimeMillis();
        getPrefs(context).edit()
                .putLong(KEY_USER_HOLD_UNTIL, now + USER_HOLD_MS)
                .putLong(KEY_USER_HOLD_AT, now)
                .putFloat(KEY_USER_HOLD_LUX, lux)
                .putInt(KEY_USER_HOLD_RAW, raw)
                .putString(KEY_AUTO_MODE, Mode.USER_HOLD.name())
                .remove(KEY_EXTERNAL_CANDIDATE_RAW)
                .remove(KEY_EXTERNAL_CANDIDATE_SINCE)
                .apply();
        BrightnessLevels.saveCurrentPercent(context, BrightnessLevels.getPercentForRaw(raw));
        BrightnessLogManager.appendSnapshot(context, event, lux);
    }

    public static long getCooldownRemainingMs(Context context) {
        return getUserHoldRemainingMs(context);
    }

    public static long getUserHoldRemainingMs(Context context) {
        return Math.max(0L, getUserHoldUntil(context) - System.currentTimeMillis());
    }

    public static int getUserHoldRaw(Context context) {
        return getPrefs(context).getInt(KEY_USER_HOLD_RAW, -1);
    }

    public static float getLastLux(Context context) {
        return getPrefs(context).getFloat(KEY_LAST_LUX, -1f);
    }

    public static void markUnavailable(Context context) {
        getPrefs(context).edit()
                .putBoolean(KEY_AUTO_ENABLED, false)
                .putBoolean(KEY_SENSOR_AVAILABLE, false)
                .putString(KEY_AUTO_MODE, Mode.UNAVAILABLE.name())
                .apply();
        BrightnessLogManager.appendSnapshot(context, "PROTECTION_UNAVAILABLE", getLastLux(context));
    }

    public static Mode getSavedMode(Context context) {
        String value = getPrefs(context).getString(KEY_AUTO_MODE, Mode.OFF.name());
        if ("MANUAL_OVERRIDE".equals(value)) {
            return Mode.USER_HOLD;
        }
        try {
            return Mode.valueOf(value);
        } catch (Throwable t) {
            return Mode.OFF;
        }
    }

    public static String getStatusText(Context context) {
        SharedPreferences prefs = getPrefs(context);
        boolean sensorAvailable = prefs.getBoolean(KEY_SENSOR_AVAILABLE, hasLightSensor(context));
        if (!sensorAvailable) {
            return "Protection unavailable";
        }

        boolean enabled = isAutoEnabled(context);
        float lastLux = getLastLux(context);
        Mode mode = getSavedMode(context);
        long hold = getUserHoldRemainingMs(context);
        int holdRaw = getUserHoldRaw(context);
        String profile = new ProtectionPolicy().getProfileName(lastLux);

        String luxText = lastLux < 0f ? "unknown" : String.format(Locale.US, "%.1f lx", lastLux);
        String holdText = hold > 0L ? (hold / 1000L) + "s" + (holdRaw >= 0 ? " / raw " + holdRaw : "") : "inactive";

        return "Protection: " + (enabled ? "On" : "Off")
                + "\nLux: " + luxText + " / " + profile
                + "\nMode: " + getDisplayMode(mode)
                + "\nUser hold: " + holdText;
    }

    public static String getDisplayMode(Mode mode) {
        if (mode == Mode.PROTECTING) return "Protecting";
        if (mode == Mode.USER_HOLD) return "Holding your brightness";
        if (mode == Mode.UNAVAILABLE) return "Unavailable";
        return "Off";
    }

    private static boolean isUserHoldActive(Context context, long now) {
        return getUserHoldUntil(context) > now;
    }

    private static long getUserHoldUntil(Context context) {
        return getPrefs(context).getLong(KEY_USER_HOLD_UNTIL, 0L);
    }

    private static float getUserHoldLux(Context context) {
        return getPrefs(context).getFloat(KEY_USER_HOLD_LUX, -1f);
    }

    private static void clearUserHold(Context context) {
        getPrefs(context).edit()
                .putLong(KEY_USER_HOLD_UNTIL, 0L)
                .putFloat(KEY_USER_HOLD_LUX, -1f)
                .remove(KEY_USER_HOLD_RAW)
                .remove(KEY_USER_HOLD_AT)
                .apply();
    }

    private static int getLastAutoRaw(Context context) {
        return getPrefs(context).getInt(KEY_LAST_AUTO_RAW, -1);
    }

    private static long getLastAutoAt(Context context) {
        return getPrefs(context).getLong(KEY_LAST_AUTO_AT, 0L);
    }

    private static long getIgnoreExternalUntil(Context context) {
        return getPrefs(context).getLong(KEY_IGNORE_EXTERNAL_UNTIL, 0L);
    }

    private static void beginAppWriteGrace(Context context) {
        getPrefs(context).edit().putLong(KEY_IGNORE_EXTERNAL_UNTIL, System.currentTimeMillis() + APP_WRITE_GRACE_MS).apply();
    }

    private static void clearAutoWriteTracking(Context context) {
        getPrefs(context).edit()
                .remove(KEY_LAST_AUTO_RAW)
                .remove(KEY_LAST_AUTO_AT)
                .apply();
    }

    private static void saveLastAutoRaw(Context context, int raw) {
        getPrefs(context).edit()
                .putInt(KEY_LAST_AUTO_RAW, raw)
                .putLong(KEY_LAST_AUTO_AT, System.currentTimeMillis())
                .apply();
    }

    private static int getExternalCandidateRaw(Context context) {
        return getPrefs(context).getInt(KEY_EXTERNAL_CANDIDATE_RAW, -1);
    }

    private static long getExternalCandidateSince(Context context) {
        return getPrefs(context).getLong(KEY_EXTERNAL_CANDIDATE_SINCE, 0L);
    }

    private static void saveExternalCandidate(Context context, int raw, long since) {
        getPrefs(context).edit()
                .putInt(KEY_EXTERNAL_CANDIDATE_RAW, raw)
                .putLong(KEY_EXTERNAL_CANDIDATE_SINCE, since)
                .apply();
    }

    private static void clearExternalCandidate(Context context) {
        getPrefs(context).edit()
                .remove(KEY_EXTERNAL_CANDIDATE_RAW)
                .remove(KEY_EXTERNAL_CANDIDATE_SINCE)
                .apply();
    }

    private static void saveMode(Context context, Mode mode) {
        getPrefs(context).edit().putString(KEY_AUTO_MODE, mode.name()).apply();
    }

    private static void saveLastLux(Context context, float lux) {
        getPrefs(context).edit().putFloat(KEY_LAST_LUX, lux).apply();
    }

    private static void saveSensorAvailable(Context context, boolean available) {
        getPrefs(context).edit().putBoolean(KEY_SENSOR_AVAILABLE, available).apply();
    }

    private static SharedPreferences getPrefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(BrightnessLevels.PREFS, Context.MODE_PRIVATE);
    }
}
