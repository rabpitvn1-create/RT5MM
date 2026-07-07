package com.ericko.redmiscreenbrightness;

/**
 * Pure brightness-protection policy.
 *
 * This class is intentionally free of Android framework calls so the protection
 * logic can evolve independently from sensors, services, permissions, and UI.
 */
public final class ProtectionPolicy {
    public static final int LEVEL_20 = 20;
    public static final int LEVEL_25 = 25;
    public static final int LEVEL_30 = 30;
    public static final int LEVEL_35 = 35;
    public static final int LEVEL_40 = 40;
    public static final int LEVEL_45 = 45;
    public static final int LEVEL_50 = 50;
    public static final int LEVEL_55 = 55;
    public static final int LEVEL_60 = 60;

    private static final int[] LEVELS = new int[] {
            LEVEL_20, LEVEL_25, LEVEL_30, LEVEL_35, LEVEL_40,
            LEVEL_45, LEVEL_50, LEVEL_55, LEVEL_60
    };

    private static final float[] LUX_CEILINGS = new float[] {
            8f, 20f, 60f, 120f, 250f, 500f, 1000f, 2500f, Float.MAX_VALUE
    };

    private static final float VERY_DARK_MAX_LUX = 12f;
    private static final float DIM_ROOM_MAX_LUX = 90f;
    private static final float ROOM_MAX_LUX = 350f;
    private static final float BRIGHT_ROOM_MAX_LUX = 2100f;

    private static final long STABLE_UP_MS = 2200L;
    private static final long STABLE_DOWN_MS = 6500L;
    private static final long STABLE_SAME_MS = 2000L;

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

    private int getDesiredPercent(float lux) {
        if (lux < 0f) {
            return LEVEL_20;
        }
        for (int i = 0; i < LUX_CEILINGS.length; i++) {
            if (lux <= LUX_CEILINGS[i]) {
                return LEVELS[i];
            }
        }
        return LEVEL_60;
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
