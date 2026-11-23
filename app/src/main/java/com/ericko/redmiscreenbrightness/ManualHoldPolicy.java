package com.ericko.redmiscreenbrightness;

/**
 * No Usage Access, foreground package tracking, timers that expire manual intent,
 * or app-specific brightness settings. Hold is released only after a sustained
 * meaningful lux change compared to the sensor lux at the manual adjustment.
 */
public final class ManualHoldPolicy {
    static final long CONFIRM_MS = 1100L;
    static final long MIN_SAMPLE_SEPARATION_MS = 250L;
    static final float ENTER_RELATIVE = 0.45f;
    static final float EXIT_RELATIVE = 0.22f;
    static final float ENTER_ABSOLUTE_LUX = 12f;
    static final float EXIT_ABSOLUTE_LUX = 7f;

    private long candidateSinceMs = -1L;
    private long lastCandidateSampleMs = -1L;
    private int confirmingSamples;

    public void reset() {
        candidateSinceMs = -1L;
        lastCandidateSampleMs = -1L;
        confirmingSamples = 0;
    }

    public boolean shouldRelease(float anchorLux, float currentLux, long nowMs) {
        if (!Float.isFinite(anchorLux) || anchorLux < 0f
                || !Float.isFinite(currentLux) || currentLux < 0f
                || nowMs < 0L) {
            reset();
            return false;
        }
        float change = Math.abs(currentLux - anchorLux);
        float enter = Math.max(ENTER_ABSOLUTE_LUX, anchorLux * ENTER_RELATIVE);
        float exit = Math.max(EXIT_ABSOLUTE_LUX, anchorLux * EXIT_RELATIVE);
        if (change <= exit) {
            reset();
            return false;
        }
        if (candidateSinceMs < 0L) {
            if (change < enter) return false;
            candidateSinceMs = nowMs;
            lastCandidateSampleMs = nowMs;
            confirmingSamples = 1;
            return false;
        }
        if (lastCandidateSampleMs < 0L || nowMs < lastCandidateSampleMs) {
            reset();
            return false;
        }
        if (nowMs - lastCandidateSampleMs >= MIN_SAMPLE_SEPARATION_MS) {
            confirmingSamples++;
            lastCandidateSampleMs = nowMs;
        }
        return confirmingSamples >= 2 && nowMs - candidateSinceMs >= CONFIRM_MS;
    }

    public static boolean shouldReleaseOnScreenOff(boolean active) {
        // A screen-off transition does not prove that ambient light has changed.
        return false;
    }
}
