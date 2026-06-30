package com.ericko.redmiscreenbrightness;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.provider.Settings;

public final class BrightnessLevels {
    public static final String PREFS = "brightness_state";
    private static final String KEY_PERCENT = "percent";

    private static final int[] PERCENTS = new int[] {20, 30, 40, 50, 60};

    /*
     * Internal raw brightness values used by this APK.
     * 30% and 40% intentionally keep their labels, but use +2 raw points
     * compared with the previous fixed values:
     * 30%: 17 -> 19
     * 40%: 26 -> 28
     */
    private static final int[] RAW_VALUES = new int[] {11, 19, 28, 38, 49};

    private BrightnessLevels() {
    }

    public static int getCurrentPercent(Context context) {
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
            Settings.System.putInt(
                    context.getContentResolver(),
                    Settings.System.SCREEN_BRIGHTNESS_MODE,
                    Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
            );
            Settings.System.putInt(
                    context.getContentResolver(),
                    Settings.System.SCREEN_BRIGHTNESS,
                    raw
            );
            saveCurrentPercent(context, percent);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}
