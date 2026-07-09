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
    private static final String KEY_IGNORE_EXTERNAL_UNTIL = "auto_brightness_ignore_external_until";
    private static final long APP_WRITE_GRACE_MS = 12000L;

    /*
     * The only brightness levels used by the app.
     * Keep these raw values conservative for Redmi/HyperOS to protect the screen,
     * reduce eye strain, and save battery.
     *
     * The 5/8/10% buckets are reserved for confirmed deep-night behavior.
     */
    private static final int[] PERCENTS = new int[] {
            5, 8, 10, 12, 15, 18, 20, 23, 25, 28, 30, 33,
            35, 38, 40, 43, 45, 48, 50, 53, 55, 58, 60
    };
    private static final int[] RAW_VALUES = new int[] {
            4, 5, 6, 7, 8, 10, 11, 13, 14, 16, 17, 19,
            21, 23, 25, 28, 31, 34, 37, 40, 43, 46, 49
    };

    private static final int MIN_RAW = 1;
    private static final int MAX_RAW = 255;

    private BrightnessLevels() {
    }

    public static int getCurrentPercent(Context context) {
        int raw = getSystemRaw(context, getRawForPercent(getSavedPercent(context)));
        int percent = getPercentForRaw(raw);
        saveCurrentPercent(context, percent);
        return percent;
    }

    public static int getSavedPercent(Context context) {
        SharedPreferences prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return prefs.getInt(KEY_PERCENT, getMaxPercent());
    }

    public static void saveCurrentPercent(Context context, int percent) {
        SharedPreferences prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        prefs.edit().putInt(KEY_PERCENT, percent).apply();
    }

    public static void markAppBrightnessWriteGrace(Context context) {
        context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putLong(KEY_IGNORE_EXTERNAL_UNTIL, System.currentTimeMillis() + APP_WRITE_GRACE_MS)
                .apply();
    }

    public static int getNextPercent(int current) {
        int normalized = getNearestPercent(current);
        for (int i = 0; i < PERCENTS.length - 1; i++) {
            if (PERCENTS[i] == normalized) {
                return PERCENTS[i + 1];
            }
        }
        return PERCENTS[0];
    }

    public static int getRawForPercent(int percent) {
        int normalized = getNearestPercent(percent);
        for (int i = 0; i < PERCENTS.length; i++) {
            if (PERCENTS[i] == normalized) {
                return RAW_VALUES[i];
            }
        }
        return RAW_VALUES[0];
    }

    public static int getRawForPercent(Context context, int percent) {
        return getRawForPercent(percent);
    }

    public static int getPercentForRaw(int raw) {
        int bestIndex = 0;
        int bestDistance = Math.abs(raw - RAW_VALUES[0]);
        for (int i = 1; i < RAW_VALUES.length; i++) {
            int distance = Math.abs(raw - RAW_VALUES[i]);
            if (distance < bestDistance) {
                bestDistance = distance;
                bestIndex = i;
            }
        }
        return PERCENTS[bestIndex];
    }

    public static int getPercentForRaw(Context context, int raw) {
        return getPercentForRaw(raw);
    }

    public static int getSystemRaw(Context context, int fallbackRaw) {
        try {
            return Settings.System.getInt(
                    context.getContentResolver(),
                    Settings.System.SCREEN_BRIGHTNESS
            );
        } catch (Throwable t) {
            return fallbackRaw;
        }
    }

    public static int getSystemBrightnessMode(Context context) {
        try {
            return Settings.System.getInt(
                    context.getContentResolver(),
                    Settings.System.SCREEN_BRIGHTNESS_MODE,
                    Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
            );
        } catch (Throwable t) {
            return Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL;
        }
    }

    public static boolean captureAndForceManualMode(Context context) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.System.canWrite(context)) {
                return false;
            }
            Context appContext = context.getApplicationContext();
            SharedPreferences prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            int currentMode = getSystemBrightnessMode(appContext);
            SharedPreferences.Editor editor = prefs.edit();
            if (!prefs.getBoolean(KEY_PREVIOUS_BRIGHTNESS_MODE_VALID, false)) {
                editor.putInt(KEY_PREVIOUS_BRIGHTNESS_MODE, currentMode)
                        .putBoolean(KEY_PREVIOUS_BRIGHTNESS_MODE_VALID, true);
            }
            editor.apply();

            if (currentMode != Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL) {
                Settings.System.putInt(
                        appContext.getContentResolver(),
                        Settings.System.SCREEN_BRIGHTNESS_MODE,
                        Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
                );
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean restorePreviousBrightnessMode(Context context) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.System.canWrite(context)) {
                return false;
            }
            Context appContext = context.getApplicationContext();
            SharedPreferences prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            if (!prefs.getBoolean(KEY_PREVIOUS_BRIGHTNESS_MODE_VALID, false)) {
                return true;
            }
            int previousMode = prefs.getInt(
                    KEY_PREVIOUS_BRIGHTNESS_MODE,
                    Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
            );
            Settings.System.putInt(
                    appContext.getContentResolver(),
                    Settings.System.SCREEN_BRIGHTNESS_MODE,
                    previousMode
            );
            prefs.edit()
                    .remove(KEY_PREVIOUS_BRIGHTNESS_MODE)
                    .remove(KEY_PREVIOUS_BRIGHTNESS_MODE_VALID)
                    .apply();
            return true;
        } catch (Throwable t) {
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
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.System.canWrite(context)) {
                return false;
            }

            int clampedRaw = clampRaw(raw);
            int currentRaw = getSystemRaw(context, -1);
            captureAndForceManualMode(context);

            if (currentRaw != clampedRaw) {
                Settings.System.putInt(
                        context.getContentResolver(),
                        Settings.System.SCREEN_BRIGHTNESS,
                        clampedRaw
                );
            }

            saveCurrentPercent(context, percent);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean applyProtectedRaw(Context context, int raw) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.System.canWrite(context)) {
                return false;
            }
            int clampedRaw = ProtectionCurveEngine.clampRaw(raw);
            int currentRaw = getSystemRaw(context, -1);
            captureAndForceManualMode(context);
            if (currentRaw != clampedRaw) {
                Settings.System.putInt(
                        context.getContentResolver(),
                        Settings.System.SCREEN_BRIGHTNESS,
                        clampedRaw
                );
            }
            saveCurrentPercent(context, ProtectionCurveEngine.nearestProtectionPercentForRaw(clampedRaw));
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static int getNearestPercent(int percent) {
        int bestIndex = 0;
        int bestDistance = Math.abs(percent - PERCENTS[0]);
        for (int i = 1; i < PERCENTS.length; i++) {
            int distance = Math.abs(percent - PERCENTS[i]);
            if (distance < bestDistance) {
                bestDistance = distance;
                bestIndex = i;
            }
        }
        return PERCENTS[bestIndex];
    }

    private static int clampRaw(int raw) {
        if (raw < MIN_RAW) return MIN_RAW;
        if (raw > MAX_RAW) return MAX_RAW;
        return raw;
    }
}
