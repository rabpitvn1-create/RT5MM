package com.ericko.redmiscreenbrightness;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.provider.Settings;

public final class BrightnessLevels {
    public static final String PREFS = "brightness_state";

    private static final String KEY_PERCENT = "percent";
    private static final String KEY_PREVIOUS_BRIGHTNESS_MODE = "previous_system_brightness_mode";
    private static final String KEY_PREVIOUS_BRIGHTNESS_MODE_VALID = "previous_system_brightness_mode_valid";
    private static final String KEY_LAST_APP_WRITE_RAW = "last_app_brightness_write_raw";
    private static final String KEY_LAST_APP_WRITE_AT = "last_app_brightness_write_at";

    private static final long APP_WRITE_OBSERVER_WINDOW_MS = 2500L;
    private static volatile int cachedSystemRaw = -1;

    // Preserve the original calibrated Redmi raw anchors exactly.
    private static final int[] PERCENTS = new int[] {
            5, 8, 10, 12, 15, 18, 20, 23, 25, 28, 30, 33,
            35, 38, 40, 43, 45, 48, 50, 53, 55, 58, 60
    };
    private static final int[] RAW_VALUES = new int[] {
            4, 5, 6, 7, 8, 10, 11, 13, 14, 16, 17, 19,
            21, 23, 26, 28, 31, 34, 38, 40, 43, 46, 49
    };

    private BrightnessLevels() {
    }

    public static int getCurrentPercent(Context context) {
        int raw = getSystemRaw(context, getRawForPercent(getSavedPercent(context)));
        int percent = getPercentForRaw(raw);
        saveCurrentPercent(context, percent);
        return percent;
    }

    public static int getSavedPercent(Context context) {
        return prefs(context).getInt(KEY_PERCENT, getMaxPercent());
    }

    public static void saveCurrentPercent(Context context, int percent) {
        int normalized = getNearestPercent(percent);
        SharedPreferences state = prefs(context);
        if (state.getInt(KEY_PERCENT, -1) == normalized) return;
        state.edit().putInt(KEY_PERCENT, normalized).apply();
    }

    /** Compatibility method retained for older callers. */
    public static void markAppBrightnessWriteGrace(Context context) {
        int raw = getCachedSystemRaw(context, -1);
        if (raw >= 0) recordAppWrite(context, raw);
    }

    public static boolean isRecentAppWrite(Context context, int currentRaw) {
        SharedPreferences state = prefs(context);
        int writtenRaw = state.getInt(KEY_LAST_APP_WRITE_RAW, -1);
        long writtenAt = state.getLong(KEY_LAST_APP_WRITE_AT, 0L);
        long age = Math.max(0L, System.currentTimeMillis() - writtenAt);
        return currentRaw == writtenRaw && writtenAt > 0L && age <= APP_WRITE_OBSERVER_WINDOW_MS;
    }

    public static int getNextPercent(int current) {
        int normalized = getNearestPercent(current);
        for (int i = 0; i < PERCENTS.length - 1; i++) {
            if (PERCENTS[i] == normalized) return PERCENTS[i + 1];
        }
        return PERCENTS[0];
    }

    public static int getRawForPercent(int percent) {
        int normalized = getNearestPercent(percent);
        for (int i = 0; i < PERCENTS.length; i++) {
            if (PERCENTS[i] == normalized) return RAW_VALUES[i];
        }
        return RAW_VALUES[0];
    }

    public static int getRawForPercent(Context context, int percent) {
        return getRawForPercent(percent);
    }

    public static int getPercentForRaw(int raw) {
        int best = 0;
        int distance = Math.abs(raw - RAW_VALUES[0]);
        for (int i = 1; i < RAW_VALUES.length; i++) {
            int candidate = Math.abs(raw - RAW_VALUES[i]);
            if (candidate < distance) {
                best = i;
                distance = candidate;
            }
        }
        return PERCENTS[best];
    }

    public static int getPercentForRaw(Context context, int raw) {
        return getPercentForRaw(raw);
    }

    public static int getSystemRaw(Context context, int fallbackRaw) {
        try {
            int raw = Settings.System.getInt(
                    context.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS);
            cachedSystemRaw = raw;
            return raw;
        } catch (Throwable ignored) {
            if (fallbackRaw >= 0) cachedSystemRaw = fallbackRaw;
            return fallbackRaw;
        }
    }

    /** Returns the last observer/write-backed value without querying SettingsProvider. */
    public static int getCachedSystemRaw(Context context, int fallbackRaw) {
        int raw = cachedSystemRaw;
        return raw >= 0 ? raw : getSystemRaw(context, fallbackRaw);
    }

    public static void updateCachedSystemRaw(int raw) {
        if (raw >= 0) cachedSystemRaw = raw;
    }

    public static int getSystemBrightnessMode(Context context) {
        try {
            return Settings.System.getInt(
                    context.getContentResolver(),
                    Settings.System.SCREEN_BRIGHTNESS_MODE,
                    Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
        } catch (Throwable ignored) {
            return Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL;
        }
    }

    public static boolean captureAndForceManualMode(Context context) {
        if (!canWrite(context)) return false;
        try {
            Context app = context.getApplicationContext();
            SharedPreferences state = prefs(app);
            int currentMode = getSystemBrightnessMode(app);
            if (!state.getBoolean(KEY_PREVIOUS_BRIGHTNESS_MODE_VALID, false)) {
                state.edit()
                        .putInt(KEY_PREVIOUS_BRIGHTNESS_MODE, currentMode)
                        .putBoolean(KEY_PREVIOUS_BRIGHTNESS_MODE_VALID, true)
                        .apply();
            }
            if (currentMode != Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL) {
                return Settings.System.putInt(
                        app.getContentResolver(),
                        Settings.System.SCREEN_BRIGHTNESS_MODE,
                        Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
            }
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean restorePreviousBrightnessMode(Context context) {
        if (!canWrite(context)) return false;
        try {
            Context app = context.getApplicationContext();
            SharedPreferences state = prefs(app);
            if (!state.getBoolean(KEY_PREVIOUS_BRIGHTNESS_MODE_VALID, false)) return true;
            int previous = state.getInt(
                    KEY_PREVIOUS_BRIGHTNESS_MODE,
                    Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
            boolean restored = Settings.System.putInt(
                    app.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE, previous);
            if (restored) {
                state.edit()
                        .remove(KEY_PREVIOUS_BRIGHTNESS_MODE)
                        .remove(KEY_PREVIOUS_BRIGHTNESS_MODE_VALID)
                        .apply();
            }
            return restored;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static int getMaxPercent() {
        return PERCENTS[PERCENTS.length - 1];
    }

    public static boolean applyBrightness(Context context, int percent) {
        return applyBrightness(context, percent, getRawForPercent(percent));
    }

    public static boolean applyBrightness(Context context, int percent, int raw) {
        int safeRaw = Math.max(1, Math.min(255, raw));
        boolean ok = writeRaw(context, safeRaw);
        if (ok) saveCurrentPercent(context, percent);
        return ok;
    }

    public static boolean applyProtectedRaw(Context context, int raw) {
        int safeRaw = ProtectionCurveEngine.clampRaw(raw);
        boolean ok = writeRaw(context, safeRaw);
        if (ok) {
            saveCurrentPercent(context, ProtectionCurveEngine.nearestProtectionPercentForRaw(safeRaw));
        }
        return ok;
    }

    private static boolean writeRaw(Context context, int raw) {
        if (!canWrite(context)) return false;
        try {
            Context app = context.getApplicationContext();
            if (!captureAndForceManualMode(app)) return false;
            int current = getSystemRaw(app, -1);
            if (current == raw) {
                cachedSystemRaw = raw;
                recordAppWrite(app, raw);
                return true;
            }
            boolean ok = Settings.System.putInt(
                    app.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, raw);
            if (ok) {
                cachedSystemRaw = raw;
                recordAppWrite(app, raw);
            }
            return ok;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void recordAppWrite(Context context, int raw) {
        prefs(context).edit()
                .putInt(KEY_LAST_APP_WRITE_RAW, raw)
                .putLong(KEY_LAST_APP_WRITE_AT, System.currentTimeMillis())
                .apply();
    }

    private static boolean canWrite(Context context) {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.System.canWrite(context);
    }

    private static int getNearestPercent(int percent) {
        int best = 0;
        int distance = Math.abs(percent - PERCENTS[0]);
        for (int i = 1; i < PERCENTS.length; i++) {
            int candidate = Math.abs(percent - PERCENTS[i]);
            if (candidate < distance) {
                best = i;
                distance = candidate;
            }
        }
        return PERCENTS[best];
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
