package com.ericko.redmiscreenbrightness;

/**
 * Pure brightness-protection policy.
 *
 * This class is intentionally free of Android framework calls so the protection
 * logic can evolve independently from sensors, services, permissions, and UI.
 */
public final class ProtectionPolicy {
    public static final int LEVEL_12 = 12;
    public static final int LEVEL_15 = 15;
    public static final int LEVEL_18 = 18;
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
            LEVEL_12, LEVEL_15, LEVEL_18, LEVEL_20, LEVEL_23,
            LEVEL_25, LEVEL_28, LEVEL_30, LEVEL_33, LEVEL_35,
            LEVEL_38, LEVEL_40, LEVEL_43, LEVEL_45, LEVEL_48,
            LEVEL_50, LEVEL_53, LEVEL_55, LEVEL_58, LEVEL_60
    };

    private static final float[] LUX_CEILINGS = new float[] {
            1f, 3f, 6f, 10f, 16f,
            25f, 40f, 65f, 100f, 150f,
            230f, 350f, 520f, 800f, 1200f,
            1800f, 2600f, 3800f, 5500f, Float.MAX_VALUE
    };

    private static final String[] BAND_KEYS = new String[] {
            "0_1", "1_3", "3_6", "6_10", "10_16",
            "16_25", "25_40", "40_65", "65_100", "100_150",
            "150_230", "230_350", "350_520", "520_800", "800_1200",
            "1200_1800", "1800_2600", "2600_3800", "3800_5500", "5500_plus"
    };

    private static final String[] BAND_LABELS = new String[] {
            "0-1 lx", "1-3 lx", "3-6 lx", "6-10 lx", "10-16 lx",
            "16-25 lx", "25-40 lx", "40-65 lx", "65-100 lx", "100-150 lx",
            "150-230 lx", "230-350 lx", "350-520 lx", "520-800 lx", "800-1200 lx",
            "1200-1800 lx", "1800-2600 lx", "2600-3800 lx", "3800-5500 lx", "5500+ lx"
    };

    private static final int[] BAND_MAX_LEARNED = new int[] {
            LEVEL_12, LEVEL_15, LEVEL_18, LEVEL_20, LEVEL_23,
            LEVEL_25, LEVEL_28, LEVEL_30, LEVEL_33, LEVEL_35,
            LEVEL_38, LEVEL_40, LEVEL_43, LEVEL_45, LEVEL_48,
            LEVEL_50, LEVEL_53, LEVEL_55, LEVEL_58, LEVEL_60
    };

    private static final float VERY_DARK_MAX_LUX = 6f;
    private static final float DIM_ROOM_MAX_LUX = 65f;
    private static final float ROOM_MAX_LUX = 350f;
    private static final float BRIGHT_ROOM_MAX_LUX = 1800f;

    private static final long STABLE_UP_MS = 1600L;
    private static final long STABLE_UP_STRONG_MS = 900L;
    private static final long STABLE_DOWN_MS = 5200L;
    private static final long STABLE_SAME_MS = 1400L;
    private static final long STABLE_NIGHT_18_MS = 7000L;
    private static final long STABLE_NIGHT_15_MS = 9500L;
    private static final long STABLE_NIGHT_12_MS = 14000L;

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
            return getUpwardTarget(current, desired);
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
            return getIndex(target) - getIndex(current) >= 2 ? STABLE_UP_STRONG_MS : STABLE_UP_MS;
        }
        if (target < current) {
            if (target == LEVEL_12) return STABLE_NIGHT_12_MS;
            if (target == LEVEL_15) return STABLE_NIGHT_15_MS;
            if (target == LEVEL_18) return STABLE_NIGHT_18_MS;
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
        return max >= 0 && percent >= LEVEL_12 && percent <= max;
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

    private int getUpwardTarget(int current, int desired) {
        if (current == LEVEL_12 && desired >= LEVEL_20) return LEVEL_20;
        if (current == LEVEL_15 && desired >= LEVEL_23) return LEVEL_23;
        if (current == LEVEL_18 && desired >= LEVEL_25) return LEVEL_25;

        int currentIndex = getIndex(current);
        int desiredIndex = getIndex(desired);
        if (currentIndex < 0 || desiredIndex <= currentIndex) {
            return getNextPercent(current);
        }
        int distance = desiredIndex - currentIndex;
        int steps = distance >= 6 ? 3 : (distance >= 3 ? 2 : 1);
        return LEVELS[Math.min(desiredIndex, currentIndex + steps)];
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
        return LEVEL_12;
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

    private int getIndex(int percent) {
        int normalized = normalizePercent(percent);
        for (int i = 0; i < LEVELS.length; i++) {
            if (LEVELS[i] == normalized) {
                return i;
            }
        }
        return -1;
    }
}
