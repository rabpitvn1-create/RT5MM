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
    private static final String KEY_LAST_AUTO_RAW = "auto_brightness_last_auto_raw";
    private static final String KEY_LAST_AUTO_AT = "auto_brightness_last_auto_at";

    private static final int DARK_PERCENT = 20;
    private static final int NORMAL_PERCENT = 30;
    private static final float DARK_ENTER_LUX = 20f;
    private static final float DARK_EXIT_LUX = 40f;
    private static final float BRIGHT_ENTER_LUX = 10000f;
    private static final float BRIGHT_EXIT_LUX = 7000f;
    private static final float MAX_LUX = 120000f;
    private static final int SAMPLE_COUNT = 5;
    private static final long STABLE_MS = 3000L;
    private static final int RAW_CHANGE_TOLERANCE = 2;
    private static final long AUTO_WRITE_GRACE_MS = 4000L;
    public static final long MANUAL_COOLDOWN_MS = 120000L;

    public enum Mode {
        OFF, DARK, NORMAL_LOCKED, BRIGHT, MANUAL_OVERRIDE, UNAVAILABLE
    }

    private final Context appContext;
    private final SensorManager sensorManager;
    private final Sensor lightSensor;
    private final float[] samples = new float[SAMPLE_COUNT];
    private int sampleCount = 0;
    private int sampleIndex = 0;
    private boolean registered = false;
    private Mode candidate = null;
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
        }
        return registered;
    }

    public void stop() {
        if (registered && sensorManager != null) {
            sensorManager.unregisterListener(this);
        }
        registered = false;
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event == null || event.sensor == null || event.sensor.getType() != Sensor.TYPE_LIGHT || event.values.length == 0) {
            return;
        }

        float avgLux = smooth(clampLux(event.values[0]));
        saveLastLux(appContext, avgLux);

        long now = System.currentTimeMillis();
        if (getManualUntil(appContext) > now) {
            saveMode(appContext, Mode.MANUAL_OVERRIDE);
            return;
        }

        if (detectExternalBrightnessChange(now)) {
            saveMode(appContext, Mode.MANUAL_OVERRIDE);
            return;
        }

        Mode previous = getSavedMode(appContext);
        Mode next = classify(avgLux, previous);
        if (candidate != next) {
            candidate = next;
            candidateSince = now;
            return;
        }
        if (now - candidateSince < STABLE_MS) {
            return;
        }

        applyStableMode(next, previous);
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    private void applyStableMode(Mode next, Mode previous) {
        if (next == Mode.DARK) {
            applyAutoPercent(DARK_PERCENT, Mode.DARK);
        } else if (next == Mode.BRIGHT) {
            applyAutoPercent(BrightnessLevels.getMaxPercent(), Mode.BRIGHT);
        } else {
            boolean returnedFromExtreme = previous == Mode.DARK || previous == Mode.BRIGHT;
            saveMode(appContext, Mode.NORMAL_LOCKED);
            if (returnedFromExtreme) {
                applyAutoPercent(NORMAL_PERCENT, Mode.NORMAL_LOCKED);
            }
        }
    }

    private void applyAutoPercent(int percent, Mode mode) {
        int raw = BrightnessLevels.getRawForPercent(percent);
        if (BrightnessLevels.getCurrentPercent(appContext) == percent && getSavedMode(appContext) == mode) {
            saveLastAutoRaw(appContext, raw);
            return;
        }
        boolean ok = BrightnessLevels.applyBrightness(appContext, percent, raw);
        if (ok) {
            saveLastAutoRaw(appContext, raw);
        }
        if (ok || getSavedMode(appContext) != mode) {
            saveMode(appContext, mode);
        }
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
        candidate = null;
        candidateSince = 0L;
        return true;
    }

    private Mode classify(float lux, Mode mode) {
        if (mode == Mode.DARK) {
            return lux < DARK_EXIT_LUX ? Mode.DARK : Mode.NORMAL_LOCKED;
        }
        if (mode == Mode.BRIGHT) {
            return lux > BRIGHT_EXIT_LUX ? Mode.BRIGHT : Mode.NORMAL_LOCKED;
        }
        if (lux <= DARK_ENTER_LUX) return Mode.DARK;
        if (lux >= BRIGHT_ENTER_LUX) return Mode.BRIGHT;
        return Mode.NORMAL_LOCKED;
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
                .putString(KEY_AUTO_MODE, enabled ? Mode.NORMAL_LOCKED.name() : Mode.OFF.name())
                .putLong(KEY_MANUAL_UNTIL, 0L);
        if (enabled) {
            editor.remove(KEY_LAST_AUTO_RAW).remove(KEY_LAST_AUTO_AT);
        }
        editor.apply();
    }

    public static boolean isAutoEnabled(Context context) {
        return getPrefs(context).getBoolean(KEY_AUTO_ENABLED, false);
    }

    public static void recordManualOverride(Context context) {
        getPrefs(context).edit()
                .putLong(KEY_MANUAL_UNTIL, System.currentTimeMillis() + MANUAL_COOLDOWN_MS)
                .putString(KEY_AUTO_MODE, Mode.MANUAL_OVERRIDE.name())
                .apply();
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
            return "Auto Brightness unavailable";
        }

        boolean enabled = isAutoEnabled(context);
        float lastLux = getLastLux(context);
        Mode mode = getSavedMode(context);
        long cooldown = getCooldownRemainingMs(context);

        String luxText = lastLux < 0f ? "unknown" : String.format(Locale.US, "%.1f lx", lastLux);
        String cooldownText = cooldown > 0L ? (cooldown / 1000L) + "s" : "inactive";

        return "Auto Brightness: " + (enabled ? "On" : "Off")
                + "\nLux: " + luxText
                + "\nMode: " + getDisplayMode(mode)
                + "\nManual cooldown: " + cooldownText;
    }

    public static String getDisplayMode(Mode mode) {
        if (mode == Mode.DARK) return "Dark";
        if (mode == Mode.NORMAL_LOCKED) return "Normal Locked";
        if (mode == Mode.BRIGHT) return "Bright";
        if (mode == Mode.MANUAL_OVERRIDE) return "Manual Override";
        if (mode == Mode.UNAVAILABLE) return "Unavailable";
        return "Off";
    }

    private static long getManualUntil(Context context) {
        return getPrefs(context).getLong(KEY_MANUAL_UNTIL, 0L);
    }

    private static int getLastAutoRaw(Context context) {
        return getPrefs(context).getInt(KEY_LAST_AUTO_RAW, -1);
    }

    private static long getLastAutoAt(Context context) {
        return getPrefs(context).getLong(KEY_LAST_AUTO_AT, 0L);
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
