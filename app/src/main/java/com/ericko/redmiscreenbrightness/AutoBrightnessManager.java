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
    private static final String KEY_MANUAL_UNTIL = "auto_brightness_manual_until";
    private static final String KEY_MANUAL_LUX = "auto_brightness_manual_lux";
    private static final String KEY_LAST_AUTO_RAW = "auto_brightness_last_auto_raw";
    private static final String KEY_LAST_AUTO_AT = "auto_brightness_last_auto_at";

    private static final int LEVEL_20 = 20;
    private static final int LEVEL_30 = 30;
    private static final int LEVEL_40 = 40;
    private static final int LEVEL_50 = 50;
    private static final int LEVEL_60 = 60;

    private static final float MAX_LUX = 120000f;
    private static final int SAMPLE_COUNT = 5;
    private static final long STABLE_MS = 2500L;
    private static final int RAW_CHANGE_TOLERANCE = 2;
    private static final long AUTO_WRITE_GRACE_MS = 4000L;
    private static final float LUX_CHANGE_PCT = 75f;
    private static final float LUX_CHANGE_MIN = 5f;
    public static final long MANUAL_COOLDOWN_MS = 120000L;

    public enum Mode {
        OFF, PROTECTING, MANUAL_OVERRIDE, UNAVAILABLE
    }

    private final Context appContext;
    private final SensorManager sensorManager;
    private final Sensor lightSensor;
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

    public void resumeProtection(String event) {
        clearManualOverride(appContext);
        clearAutoWriteTracking(appContext);
        saveMode(appContext, Mode.PROTECTING);
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

        float avgLux = smooth(clampLux(event.values[0]));
        saveLastLux(appContext, avgLux);
        BrightnessLogManager.logSnapshotIfChanged(appContext, "PROTECTION_SENSOR_SAMPLE", avgLux);
        evaluateLux(avgLux, "PROTECTION_SENSOR_SAMPLE");
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    private void evaluateLux(float avgLux, String event) {
        long now = System.currentTimeMillis();
        if (getManualUntil(appContext) > now) {
            float manualLux = getManualLux(appContext);
            if (!luxChangedEnough(avgLux, manualLux)) {
                saveMode(appContext, Mode.MANUAL_OVERRIDE);
                BrightnessLogManager.logSnapshotIfChanged(appContext, "MANUAL_OVERRIDE_HELD", avgLux);
                return;
            }
            clearManualOverride(appContext);
            clearAutoWriteTracking(appContext);
            candidatePercent = -1;
            candidateSince = 0L;
            BrightnessLogManager.appendSnapshot(appContext, "MANUAL_OVERRIDE_RESUMED_BY_LUX_CHANGE", avgLux);
        }

        if (detectExternalBrightnessChange(now)) {
            saveMode(appContext, Mode.MANUAL_OVERRIDE);
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
        if (now - candidateSince < STABLE_MS) {
            return;
        }

        applyAutoPercent(targetPercent);
    }

    private void applyAutoPercent(int percent) {
        int raw = BrightnessLevels.getRawForPercent(percent);
        int currentRaw = BrightnessLevels.getSystemRaw(appContext, raw);
        if (Math.abs(currentRaw - raw) <= RAW_CHANGE_TOLERANCE) {
            saveLastAutoRaw(appContext, raw);
            saveMode(appContext, Mode.PROTECTING);
            BrightnessLogManager.logSnapshotIfChanged(appContext, "PROTECTION_AT_" + percent + "_PERCENT", getLastLux(appContext));
            return;
        }

        boolean ok = BrightnessLevels.applyBrightness(appContext, percent, raw);
        if (ok) {
            saveLastAutoRaw(appContext, raw);
            saveMode(appContext, Mode.PROTECTING);
            BrightnessLogManager.appendSnapshot(appContext, "PROTECTION_APPLIED_" + percent + "_PERCENT_RAW_" + raw, getLastLux(appContext));
        } else {
            BrightnessLogManager.appendSnapshot(appContext, "PROTECTION_APPLY_FAILED_" + percent + "_PERCENT_RAW_" + raw, getLastLux(appContext));
        }
    }

    private int getTargetPercent(float lux, int currentPercent) {
        if (currentPercent <= LEVEL_20) {
            return lux > 30f ? LEVEL_30 : LEVEL_20;
        }
        if (currentPercent == LEVEL_30) {
            if (lux < 12f) return LEVEL_20;
            if (lux > 120f) return LEVEL_40;
            return LEVEL_30;
        }
        if (currentPercent == LEVEL_40) {
            if (lux < 75f) return LEVEL_30;
            if (lux > 500f) return LEVEL_50;
            return LEVEL_40;
        }
        if (currentPercent == LEVEL_50) {
            if (lux < 300f) return LEVEL_40;
            if (lux > 3000f) return LEVEL_60;
            return LEVEL_50;
        }
        if (lux < 1800f) return LEVEL_50;
        return LEVEL_60;
    }

    private boolean detectExternalBrightnessChange(long now) {
        int lastAutoRaw = getLastAutoRaw(appContext);
        if (lastAutoRaw < 0) {
            return false;
        }
        long lastAutoAt = getLastAutoAt(appContext);
        if (now - lastAutoAt < AUTO_WRITE_GRACE_MS) {
            return false;
        }
        int currentRaw = BrightnessLevels.getSystemRaw(appContext, lastAutoRaw);
        if (Math.abs(currentRaw - lastAutoRaw) <= RAW_CHANGE_TOLERANCE) {
            return false;
        }

        BrightnessLevels.saveCurrentPercent(appContext, BrightnessLevels.getPercentForRaw(currentRaw));
        recordManualOverride(appContext);
        BrightnessLogManager.appendSnapshot(appContext, "EXTERNAL_BRIGHTNESS_CHANGE_DETECTED", getLastLux(appContext));
        candidatePercent = -1;
        candidateSince = 0L;
        return true;
    }

    private boolean luxChangedEnough(float currentLux, float manualLux) {
        if (manualLux < 0f) {
            return false;
        }
        float delta = Math.abs(currentLux - manualLux);
        if (delta < LUX_CHANGE_MIN) {
            return false;
        }
        if (manualLux <= 0f) {
            return true;
        }
        return (delta * 100f / manualLux) >= LUX_CHANGE_PCT;
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
                .putLong(KEY_MANUAL_UNTIL, 0L)
                .putFloat(KEY_MANUAL_LUX, -1f);
        if (enabled) {
            editor.remove(KEY_LAST_AUTO_RAW).remove(KEY_LAST_AUTO_AT);
        }
        editor.apply();
        BrightnessLogManager.appendSnapshot(context, enabled ? "PROTECTION_STATE_ON" : "PROTECTION_STATE_OFF", getLastLux(context));
    }

    public static boolean isAutoEnabled(Context context) {
        return getPrefs(context).getBoolean(KEY_AUTO_ENABLED, false);
    }

    public static void recordManualOverride(Context context) {
        float lux = getLastLux(context);
        getPrefs(context).edit()
                .putLong(KEY_MANUAL_UNTIL, System.currentTimeMillis() + MANUAL_COOLDOWN_MS)
                .putFloat(KEY_MANUAL_LUX, lux)
                .putString(KEY_AUTO_MODE, Mode.MANUAL_OVERRIDE.name())
                .apply();
        BrightnessLogManager.appendSnapshot(context, "MANUAL_OVERRIDE_RECORDED", lux);
    }

    public static long getCooldownRemainingMs(Context context) {
        return Math.max(0L, getManualUntil(context) - System.currentTimeMillis());
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
        long cooldown = getCooldownRemainingMs(context);

        String luxText = lastLux < 0f ? "unknown" : String.format(Locale.US, "%.1f lx", lastLux);
        String cooldownText = cooldown > 0L ? (cooldown / 1000L) + "s" : "inactive";

        return "Protection: " + (enabled ? "On" : "Off")
                + "\nLux: " + luxText
                + "\nMode: " + getDisplayMode(mode)
                + "\nManual pause: " + cooldownText;
    }

    public static String getDisplayMode(Mode mode) {
        if (mode == Mode.PROTECTING) return "Protecting";
        if (mode == Mode.MANUAL_OVERRIDE) return "Manual Override";
        if (mode == Mode.UNAVAILABLE) return "Unavailable";
        return "Off";
    }

    private static long getManualUntil(Context context) {
        return getPrefs(context).getLong(KEY_MANUAL_UNTIL, 0L);
    }

    private static float getManualLux(Context context) {
        return getPrefs(context).getFloat(KEY_MANUAL_LUX, -1f);
    }

    private static void clearManualOverride(Context context) {
        getPrefs(context).edit()
                .putLong(KEY_MANUAL_UNTIL, 0L)
                .putFloat(KEY_MANUAL_LUX, -1f)
                .apply();
    }

    private static int getLastAutoRaw(Context context) {
        return getPrefs(context).getInt(KEY_LAST_AUTO_RAW, -1);
    }

    private static long getLastAutoAt(Context context) {
        return getPrefs(context).getLong(KEY_LAST_AUTO_AT, 0L);
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
