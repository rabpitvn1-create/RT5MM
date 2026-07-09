package com.ericko.redmiscreenbrightness;

public final class ProtectionCurveEngine {
    private static final float DEEP_NIGHT_MAX_LUX = 0.5f;

    private static final float[] LUX_POINTS = new float[] {
            0.5f, 1.5f, 3f, 6f, 10f, 16f, 25f, 40f, 65f, 100f,
            150f, 230f, 350f, 520f, 800f, 1200f, 1800f, 2600f, 3800f, 5500f
    };

    private static final int[] RAW_POINTS = new int[] {
            4, 5, 6, 7, 11, 13, 14, 16, 17, 19,
            21, 23, 25, 28, 31, 34, 37, 40, 43, 46
    };

    private static final int MAX_PROTECTION_RAW = 49;
    private static final int MIN_PROTECTION_RAW = 4;

    private ProtectionCurveEngine() {
    }

    public static int getTargetRaw(float lux) {
        if (Float.isNaN(lux) || lux < 0f) {
            lux = 0f;
        }
        if (lux <= LUX_POINTS[0]) {
            return MIN_PROTECTION_RAW;
        }
        for (int i = 1; i < LUX_POINTS.length; i++) {
            if (lux <= LUX_POINTS[i]) {
                return interpolateRaw(lux, LUX_POINTS[i - 1], LUX_POINTS[i], RAW_POINTS[i - 1], RAW_POINTS[i]);
            }
        }
        return MAX_PROTECTION_RAW;
    }

    public static boolean isDeepNightRaw(int raw) {
        return raw <= RAW_POINTS[2];
    }

    public static boolean isDeepNightLux(float lux) {
        return !Float.isNaN(lux) && lux >= 0f && lux <= DEEP_NIGHT_MAX_LUX;
    }

    public static int clampRaw(int raw) {
        if (raw < MIN_PROTECTION_RAW) {
            return MIN_PROTECTION_RAW;
        }
        if (raw > MAX_PROTECTION_RAW) {
            return MAX_PROTECTION_RAW;
        }
        return raw;
    }

    public static int nearestProtectionPercentForRaw(int raw) {
        return BrightnessLevels.getPercentForRaw(clampRaw(raw));
    }

    private static int interpolateRaw(float lux, float x1, float x2, int y1, int y2) {
        if (x2 <= x1) {
            return clampRaw(y1);
        }
        float coef = (lux - x1) / (x2 - x1);
        int raw = Math.round(y1 + (y2 - y1) * coef);
        return clampRaw(raw);
    }
}
