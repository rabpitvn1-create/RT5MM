package com.ericko.redmiscreenbrightness;

/**
 * Pure brightness-protection policy.
 *
 * This class is intentionally free of Android framework calls so the protection
 * logic can evolve independently from sensors, services, permissions, and UI.
 */
public final class ProtectionPolicy {
    public static final int LEVEL_20 = 20;
    public static final int LEVEL_23 = 23;
    public static final int LEVEL_25 = 25;
    public static final int LEVEL_28 = 28;
    public static final int LEVEL_30 = 30;
    public static final int LEVEL_33 = 33;
    public static final int LEVEL_35 = 35;
    public static final int LEVEL_38 = 38;
    public static final int LEVEL_40 = 40;
    public static final int LEVEL_43 = 43;
    public static final int LEVEL_45 = 45;
    public static final int LEVEL_48 = 48;
    public static final int LEVEL_50 = 50;
    public static final int LEVEL_53 = 53;
    public static final int LEVEL_55 = 55;
    public static final int LEVEL_58 = 58;
    public static final int LEVEL_60 = 60;

    private static final int[] LEVELS = new int[] {
            LEVEL_20, LEVEL_23, LEVEL_25, LEVEL_28, LEVEL_30,
            LEVEL_33, LEVEL_35, LEVEL_38, LEVEL_40, LEVEL_43,
            LEVEL_45, LEVEL_48, LEVEL_50, LEVEL_53, LEVEL_55,
            LEVEL_58, LEVEL_60
    };

    private static final float[] LUX_CEILINGS = new float[] {
            8f, 13f, 20f, 35f, 60f,
            85f, 120f, 175f, 250f, 355f,
            500f, 710f, 1000f, 1580f, 2500f,
            5000f, Float.MAX_VALUE
    };

    private static final String[] BAND_KEYS = new String[] {
            "0_8", "8_13", "13_20", "20_35", "35_60",
            "60_85", "85_120", "120_175", "175_250", "250_355",
            "355_500", "500_710", "710_1000", "1000_1580", "1580_2500",
            "2500_5000", "5000_plus"
    };

    private static final String[] BAND_LABELS = new String[] {
            "0-8 lx", "8-13 lx", "13-20 lx", "20-35 lx", "35-60 lx",
            "60-85 lx", "85-120 lx", "120-175 lx", "175-250 lx", "250-355 lx",
            "355-500 lx", "500-710 lx", "710-1000 lx", "1000-1580 lx", "1580-2500 lx",
            "2500-5000 lx", "5000+ lx"
    };

    private static final int[] BAND_MAX_LEARNED = new int[] {
            LEVEL_23, LEVEL_25, LEVEL_28, LEVEL_30, LEVEL_33,
            LEVEL_35, LEVEL_38, LEVEL_40, LEVEL_43, LEVEL_45,
            LEVEL_48, LEVEL_50, LEVEL_53, LEVEL_55, LEVEL_58,
            LEVEL_60, LEVEL_60
    };

    private static final float VERY_DARK_MAX_LUX = 12f;
    private static final float DIM_ROOM_MAX_LUX = 90f;
    private static final float ROOM_MAX_LUX = 350f;
    private static final float BRIGHT_ROOM_MAX_LUX = 2100f;

    private static final long STABLE_UP_MS = 1800L;
    private static final long STABLE_DOWN_MS = 5200L;
    private static final long STABLE_SAME_MS = 1600L;

    private static final float SUDDEN_DARK_MAX_LUX = 3f;
    private static final float SUDDEN_DARK_DROP_RATIO = 0.18f;
    private static final float SUDDEN_DARK_MIN_PREVIOUS_LUX = 80f;
    private static final long SUDDEN_DARK_CONFIRM_MS = 8000L;

    private float lastTrustedLux = -1f;
    private long suddenDarkSince = 0L;

    public float filterLux(float lux, long now) {
        if (Float.isNaN(lux) || lux < 0f) {
            lux = 0f;
        }

        if (lastTrustedLux < 0f) {
            lastTrustedLux = lux;
            return lux;
        }

        boolean suddenDark = lastTrustedLux >= SUDDEN_DARK_MIN_PREVIOUS_LUX
                && lux <= SUDDEN_DARK_MAX_LUX
                && lux <= lastTrustedLux * SUDDEN_DARK_DROP_RATIO;

        if (suddenDark) {
            if (suddenDarkSince == 0L) {
                suddenDarkSince = now;
            }
            if (now - suddenDarkSince < SUDDEN_DARK_CONFIRM_MS) {
                return lastTrustedLux;
            }
        } else {
            suddenDarkSince = 0L;
        }

        lastTrustedLux = lux;
        return lux;
    }

    public int getTargetPercent(float lux, int currentPercent) {
        int current = normalizePercent(currentPercent);
        int desired = getDesiredPercent(lux);
        if (desired > current) {
            return getNextPercent(current);
        }
        if (desired < current) {
            return getPreviousPercent(current);
        }
        return current;
    }

    public long getStableMs(int currentPercent, int targetPercent) {
        int current = normalizePercent(currentPercent);
        int target = normalizePercent(targetPercent);
        if (target > current) {
            return STABLE_UP_MS;
        }
        if (target < current) {
            return STABLE_DOWN_MS;
        }
        return STABLE_SAME_MS;
    }

    public String getProfileName(float lux) {
        if (lux < 0f) return "unknown";
        if (lux <= VERY_DARK_MAX_LUX) return "very dark";
        if (lux <= DIM_ROOM_MAX_LUX) return "dim room";
        if (lux <= ROOM_MAX_LUX) return "room";
        if (lux <= BRIGHT_ROOM_MAX_LUX) return "bright room";
        return "outdoor";
    }

    public String getBandKey(float lux) {
        int index = getBandIndex(lux);
        return index < 0 ? "unknown" : BAND_KEYS[index];
    }

    public String getBandLabel(float lux) {
        int index = getBandIndex(lux);
        return index < 0 ? "unknown" : BAND_LABELS[index];
    }

    public int getBandDefaultPercent(float lux) {
        int index = getBandIndex(lux);
        return index < 0 ? LEVEL_20 : LEVELS[index];
    }

    public int getBandMaxLearnedPercent(float lux) {
        int index = getBandIndex(lux);
        return index < 0 ? -1 : BAND_MAX_LEARNED[index];
    }

    public boolean isSafeLearnedPercentForBand(float lux, int percent) {
        int max = getBandMaxLearnedPercent(lux);
        return max >= 0 && percent >= LEVEL_20 && percent <= max;
    }

    private int getDesiredPercent(float lux) {
        int index = getBandIndex(lux);
        return index < 0 ? LEVEL_20 : LEVELS[index];
    }

    private int getBandIndex(float lux) {
        if (Float.isNaN(lux) || lux < 0f) {
            return -1;
        }
        for (int i = 0; i < LUX_CEILINGS.length; i++) {
            if (lux <= LUX_CEILINGS[i]) {
                return i;
            }
        }
        return LUX_CEILINGS.length - 1;
    }

    private int getNextPercent(int percent) {
        int current = normalizePercent(percent);
        for (int i = 0; i < LEVELS.length - 1; i++) {
            if (LEVELS[i] == current) {
                return LEVELS[i + 1];
            }
        }
        return LEVEL_60;
    }

    private int getPreviousPercent(int percent) {
        int current = normalizePercent(percent);
        for (int i = 1; i < LEVELS.length; i++) {
            if (LEVELS[i] == current) {
                return LEVELS[i - 1];
            }
        }
        return LEVEL_20;
    }

    private int normalizePercent(int percent) {
        int best = LEVELS[0];
        int bestDistance = Math.abs(percent - best);
        for (int i = 1; i < LEVELS.length; i++) {
            int distance = Math.abs(percent - LEVELS[i]);
            if (distance < bestDistance) {
                best = LEVELS[i];
                bestDistance = distance;
            }
        }
        return best;
    }
}
