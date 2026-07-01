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
    private static final String KEY_MANUAL_HOLD = "auto_brightness_manual_hold";

    private static final int DARK_PERCENT = 20;
    private static final int NORMAL_PERCENT = 30;
    private static final float DARK_ENTER_LUX = 20f;
    private static final float DARK_EXIT_LUX = 40f;
    private static final float BRIGHT_ENTER_LUX = 10000f;
    private static final float BRIGHT_EXIT_LUX = 7000f;
    private static final float MAX_LUX = 120000f;
    private static final int SAMPLE_COUNT = 5;
    private static final long STABLE_MS = 3000L;

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

        if (isManualHoldEnabled(appContext)) {
            saveMode(appContext, Mode.MANUAL_OVERRIDE);
            return;
        }

        long now = System.currentTimeMillis();
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
            boolean returnedFromExtreme = previous == Mode.DARK || previous == Mode.BRIGHT || previous == Mode.MANUAL_OVERRIDE;
            saveMode(appContext, Mode.NORMAL_LOCKED);
            if (returnedFromExtreme) {
                applyAutoPercent(NORMAL_PERCENT, Mode.NORMAL_LOCKED);
            }
        }
    }

    private void applyAutoPercent(int percent, Mode mode) {
        if (BrightnessLevels.getCurrentPercent(appContext) == percent && getSavedMode(appContext) == mode) {
            return;
        }
        boolean ok = BrightnessLevels.applyBrightness(appContext, percent);
        if (ok || getSavedMode(appContext) != mode) {
            saveMode(appContext, mode);
        }
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
        getPrefs(context).edit()
                .putBoolean(KEY_AUTO_ENABLED, enabled)
                .putBoolean(KEY_MANUAL_HOLD, false)
                .putString(KEY_AUTO_MODE, enabled ? Mode.NORMAL_LOCKED.name() : Mode.OFF.name())
                .apply();
    }

    public static boolean isAutoEnabled(Context context) {
        return getPrefs(context).getBoolean(KEY_AUTO_ENABLED, false);
    }

    public static void recordManualOverride(Context context) {
        getPrefs(context).edit()
                .putBoolean(KEY_MANUAL_HOLD, true)
                .putString(KEY_AUTO_MODE, Mode.MANUAL_OVERRIDE.name())
                .apply();
    }

    public static void clearManualOverride(Context context) {
        SharedPreferences prefs = getPrefs(context);
        boolean autoEnabled = prefs.getBoolean(KEY_AUTO_ENABLED, false);
        Mode currentMode = getSavedMode(context);
        SharedPreferences.Editor editor = prefs.edit().putBoolean(KEY_MANUAL_HOLD, false);
        if (currentMode == Mode.MANUAL_OVERRIDE) {
            editor.putString(KEY_AUTO_MODE, autoEnabled ? Mode.NORMAL_LOCKED.name() : Mode.OFF.name());
        }
        editor.apply();
    }

    public static boolean isManualHoldEnabled(Context context) {
        return getPrefs(context).getBoolean(KEY_MANUAL_HOLD, false);
    }

    public static String getManualOverrideStatusText(Context context) {
        return isManualHoldEnabled(context) ? "until screen off" : "inactive";
    }

    public static float getLastLux(Context context) {
        return getPrefs(context).getFloat(KEY_LAST_LUX, -1f);
    }

    public static void markUnavailable(Context context) {
        getPrefs(context).edit()
                .putBoolean(KEY_AUTO_ENABLED, false)
                .putBoolean(KEY_MANUAL_HOLD, false)
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

        String luxText = lastLux < 0f ? "unknown" : String.format(Locale.US, "%.1f lx", lastLux);

        return "Auto Brightness: " + (enabled ? "On" : "Off")
                + "\nLux: " + luxText
                + "\nMode: " + getDisplayMode(mode)
                + "\nManual override: " + getManualOverrideStatusText(context);
    }

    public static String getDisplayMode(Mode mode) {
        if (mode == Mode.DARK) return "Dark";
        if (mode == Mode.NORMAL_LOCKED) return "Normal Locked";
        if (mode == Mode.BRIGHT) return "Bright";
        if (mode == Mode.MANUAL_OVERRIDE) return "Manual Override";
        if (mode == Mode.UNAVAILABLE) return "Unavailable";
        return "Off";
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
