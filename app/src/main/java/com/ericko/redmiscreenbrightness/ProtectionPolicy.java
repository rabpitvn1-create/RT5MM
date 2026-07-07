package com.ericko.redmiscreenbrightness;

/**
 * Pure brightness-protection policy.
 *
 * This class is intentionally free of Android framework calls so the protection
 * logic can evolve independently from sensors, services, permissions, and UI.
 */
public final class ProtectionPolicy {
    public static final int LEVEL_20 = 20;
    public static final int LEVEL_30 = 30;
    public static final int LEVEL_40 = 40;
    public static final int LEVEL_50 = 50;
    public static final int LEVEL_60 = 60;

    private static final float VERY_DARK_MAX_LUX = 12f;
    private static final float DIM_ROOM_MAX_LUX = 90f;
    private static final float ROOM_MAX_LUX = 350f;
    private static final float BRIGHT_ROOM_MAX_LUX = 2100f;

    private static final float STEP_UP_20_TO_30_LUX = 30f;
    private static final float STEP_UP_30_TO_40_LUX = 160f;
    private static final float STEP_UP_40_TO_50_LUX = 650f;
    private static final float STEP_UP_50_TO_60_LUX = 3500f;

    private static final float STEP_DOWN_30_TO_20_LUX = 12f;
    private static final float STEP_DOWN_40_TO_30_LUX = 90f;
    private static final float STEP_DOWN_50_TO_40_LUX = 350f;
    private static final float STEP_DOWN_60_TO_50_LUX = 2100f;

    private static final long STABLE_UP_MS = 2500L;
    private static final long STABLE_DOWN_MS = 7000L;
    private static final long STABLE_SAME_MS = 2500L;

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
        int normalizedCurrent = normalizePercent(currentPercent);
        if (normalizedCurrent <= LEVEL_20) {
            return lux > STEP_UP_20_TO_30_LUX ? LEVEL_30 : LEVEL_20;
        }
        if (normalizedCurrent == LEVEL_30) {
            if (lux < STEP_DOWN_30_TO_20_LUX) return LEVEL_20;
            if (lux > STEP_UP_30_TO_40_LUX) return LEVEL_40;
            return LEVEL_30;
        }
        if (normalizedCurrent == LEVEL_40) {
            if (lux < STEP_DOWN_40_TO_30_LUX) return LEVEL_30;
            if (lux > STEP_UP_40_TO_50_LUX) return LEVEL_50;
            return LEVEL_40;
        }
        if (normalizedCurrent == LEVEL_50) {
            if (lux < STEP_DOWN_50_TO_40_LUX) return LEVEL_40;
            if (lux > STEP_UP_50_TO_60_LUX) return LEVEL_60;
            return LEVEL_50;
        }
        if (lux < STEP_DOWN_60_TO_50_LUX) return LEVEL_50;
        return LEVEL_60;
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

    private int normalizePercent(int percent) {
        if (percent <= LEVEL_20) return LEVEL_20;
        if (percent <= LEVEL_30) return LEVEL_30;
        if (percent <= LEVEL_40) return LEVEL_40;
        if (percent <= LEVEL_50) return LEVEL_50;
        return LEVEL_60;
    }
}
