package com.ericko.redmiscreenbrightness;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.provider.Settings;

public final class BrightnessLevels {
    public static final String PREFS = "brightness_state";
    private static final String KEY_PERCENT = "percent";
    private static final String KEY_MASTER_ADJUST = "master_adjust_raw";

    private static final int[] PERCENTS = new int[] {20, 30, 40, 50, 60};

    /*
     * Internal raw brightness values used by this APK.
     * These are intentionally conservative for Redmi/HyperOS. The user's
     * master adjust value is added on top so the whole curve can be made
     * brighter or dimmer without changing every threshold.
     */
    private static final int[] RAW_VALUES = new int[] {11, 19, 28, 38, 49};
    private static final int MIN_RAW = 1;
    private static final int MAX_RAW = 255;
    private static final int MIN_MASTER_ADJUST = -10;
    private static final int MAX_MASTER_ADJUST = 20;

    private BrightnessLevels() {
    }

    public static int getCurrentPercent(Context context) {
        int savedPercent = getSavedPercent(context);
        int raw = getSystemRaw(context, getRawForPercent(context, savedPercent));
        int percent = getPercentForRaw(context, raw);
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

    public static int getNextPercent(int current) {
        if (current == 20) return 30;
        if (current == 30) return 40;
        if (current == 40) return 50;
        if (current == 50) return 60;
        return 20;
    }

    public static int getRawForPercent(int percent) {
        for (int i = 0; i < PERCENTS.length; i++) {
            if (PERCENTS[i] == percent) {
                return RAW_VALUES[i];
            }
        }
        return RAW_VALUES[0];
    }

    public static int getRawForPercent(Context context, int percent) {
        return clampRaw(getRawForPercent(percent) + getMasterAdjust(context));
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
        int bestIndex = 0;
        int bestDistance = Math.abs(raw - getRawForPercent(context, PERCENTS[0]));
        for (int i = 1; i < PERCENTS.length; i++) {
            int distance = Math.abs(raw - getRawForPercent(context, PERCENTS[i]));
            if (distance < bestDistance) {
                bestDistance = distance;
                bestIndex = i;
            }
        }
        return PERCENTS[bestIndex];
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

    public static int getMaxPercent() {
        return PERCENTS[PERCENTS.length - 1];
    }

    public static int getMasterAdjust(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getInt(KEY_MASTER_ADJUST, 0);
    }

    public static int changeMasterAdjust(Context context, int delta) {
        int value = getMasterAdjust(context) + delta;
        if (value < MIN_MASTER_ADJUST) value = MIN_MASTER_ADJUST;
        if (value > MAX_MASTER_ADJUST) value = MAX_MASTER_ADJUST;
        context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putInt(KEY_MASTER_ADJUST, value)
                .apply();
        return value;
    }

    public static boolean applyBrightness(Context context, int percent) {
        return applyBrightness(context, percent, getRawForPercent(context, percent));
    }

    public static boolean applyBrightness(Context context, int percent, int raw) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.System.canWrite(context)) {
                return false;
            }

            int currentRaw = getSystemRaw(context, -1);
            int currentMode = Settings.System.getInt(
                    context.getContentResolver(),
                    Settings.System.SCREEN_BRIGHTNESS_MODE,
                    Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
            );

            if (currentMode != Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL) {
                Settings.System.putInt(
                        context.getContentResolver(),
                        Settings.System.SCREEN_BRIGHTNESS_MODE,
                        Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
                );
            }

            if (currentRaw != raw) {
                Settings.System.putInt(
                        context.getContentResolver(),
                        Settings.System.SCREEN_BRIGHTNESS,
                        clampRaw(raw)
                );
            }

            saveCurrentPercent(context, percent);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static int clampRaw(int raw) {
        if (raw < MIN_RAW) return MIN_RAW;
        if (raw > MAX_RAW) return MAX_RAW;
        return raw;
    }
}
