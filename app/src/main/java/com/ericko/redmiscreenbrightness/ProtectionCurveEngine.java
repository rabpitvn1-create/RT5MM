package com.ericko.redmiscreenbrightness;

/**
 * Monotonic, conservative lux-to-raw curve for the Redmi/HyperOS panel.
 *
 * The five user-verified anchors are preserved exactly:
 * 20% = raw 11, 30% = raw 17, 40% = raw 26, 50% = raw 38, 60% = raw 49.
 */
public final class ProtectionCurveEngine {
    private static final float[] LUX_POINTS = new float[] {
            0.5f, 1.5f, 3f, 6f, 10f, 16f, 25f, 40f, 65f, 100f,
            150f, 230f, 350f, 520f, 800f, 1200f, 1800f, 2600f, 3800f, 5500f,
            9000f
    };

    private static final int[] RAW_POINTS = new int[] {
            4, 5, 6, 7, 11, 13, 14, 16, 17, 19,
            21, 23, 26, 28, 31, 34, 38, 40, 43, 46,
            49
    };

    private static final int MIN_PROTECTION_RAW = 4;
    private static final int MAX_PROTECTION_RAW = 49;

    private ProtectionCurveEngine() {
    }

    public static int getTargetRaw(float lux) {
        float safeLux = sanitizeLux(lux);
        if (safeLux <= LUX_POINTS[0]) {
            return MIN_PROTECTION_RAW;
        }
        for (int i = 1; i < LUX_POINTS.length; i++) {
            if (safeLux <= LUX_POINTS[i]) {
                return interpolateRaw(
                        safeLux,
                        LUX_POINTS[i - 1],
                        LUX_POINTS[i],
                        RAW_POINTS[i - 1],
                        RAW_POINTS[i]);
            }
        }
        return MAX_PROTECTION_RAW;
    }

    public static boolean isDeepNightRaw(int raw) {
        return raw <= 6;
    }

    public static boolean isDeepNightLux(float lux) {
        return !Float.isNaN(lux) && !Float.isInfinite(lux) && lux >= 0f && lux <= 3f;
    }

    public static int clampRaw(int raw) {
        return Math.max(MIN_PROTECTION_RAW, Math.min(MAX_PROTECTION_RAW, raw));
    }

    public static int nearestProtectionPercentForRaw(int raw) {
        return BrightnessLevels.getPercentForRaw(clampRaw(raw));
    }

    public static String getProfileName(float lux) {
        if (Float.isNaN(lux) || Float.isInfinite(lux) || lux < 0f) return "unknown";
        if (lux <= 3f) return "deep night";
        if (lux <= 25f) return "very dim";
        if (lux <= 150f) return "indoor dim";
        if (lux <= 800f) return "indoor";
        if (lux <= 2600f) return "bright indoor";
        return "outdoor";
    }

    public static String getBandLabel(float lux) {
        if (Float.isNaN(lux) || Float.isInfinite(lux) || lux < 0f) return "unknown";
        float previous = 0f;
        for (float ceiling : LUX_POINTS) {
            if (lux <= ceiling) {
                return format(previous) + "–" + format(ceiling) + " lx";
            }
            previous = ceiling;
        }
        return format(LUX_POINTS[LUX_POINTS.length - 1]) + "+ lx";
    }

    private static int interpolateRaw(float lux, float x1, float x2, int y1, int y2) {
        if (x2 <= x1) return clampRaw(y1);
        // Lux perception is logarithmic. Log interpolation avoids large visible jumps
        // while preserving every calibrated endpoint exactly.
        double lx = Math.log1p(lux);
        double l1 = Math.log1p(x1);
        double l2 = Math.log1p(x2);
        double ratio = (lx - l1) / Math.max(0.000001d, l2 - l1);
        return clampRaw((int) Math.round(y1 + (y2 - y1) * ratio));
    }

    private static float sanitizeLux(float lux) {
        if (Float.isNaN(lux) || Float.isInfinite(lux) || lux < 0f) return 0f;
        return Math.min(120000f, lux);
    }

    private static String format(float value) {
        if (value == Math.rint(value)) return Integer.toString((int) value);
        return Float.toString(value);
    }
}
