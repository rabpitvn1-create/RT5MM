package com.ericko.redmiscreenbrightness;

/**
 * Final raw-brightness gate.
 *
 * Ambient estimation, hysteresis, debounce, sunlight rescue, and dark settle are
 * handled by {@link ProtectionAmbientController}. This class deliberately stays
 * small: it prevents insignificant writes and converts only accepted ambient
 * state into APPLY / NOOP / WAIT.
 */
public final class BrightnessDecisionEngine {
    private static final int SCREEN_RAW_HYSTERESIS = 1;

    public enum Action {
        APPLY,
        NOOP,
        WAIT,
        // Kept for source compatibility with older diagnostics paths.
        IGNORE_SPIKE,
        SENSOR_NOISY,
        SUNLIGHT_RESCUE
    }

    public static final class Decision {
        public final Action action;
        public final String reason;
        public final int targetRaw;
        public final int rescueRaw;
        public final long waitMs;
        public final float confidence;

        private Decision(
                Action action,
                String reason,
                int targetRaw,
                int rescueRaw,
                long waitMs,
                float confidence) {
            this.action = action;
            this.reason = reason;
            this.targetRaw = targetRaw;
            this.rescueRaw = rescueRaw;
            this.waitMs = waitMs;
            this.confidence = confidence;
        }

        public boolean shouldApply() {
            return action == Action.APPLY;
        }

        public boolean shouldRescue() {
            return action == Action.SUNLIGHT_RESCUE;
        }

        public boolean isNoop() {
            return action == Action.NOOP;
        }
    }

    public void reset() {
        // Ambient history belongs to ProtectionAmbientController.
    }

    /**
     * Evaluates a lux value that has already passed ambient confirmation.
     * forceApply means the caller is attempting to use a cached/recovery value;
     * cached lux is diagnostic-only and must never bypass the ambient controller.
     */
    public Decision decideConfirmedAmbient(float ambientLux, int currentRaw, boolean forceApply) {
        int targetRaw = ProtectionCurveEngine.getTargetRaw(ambientLux);

        if (forceApply) {
            return decision(
                    Action.WAIT,
                    "WAIT_FRESH_AMBIENT_CONFIRMATION",
                    targetRaw,
                    0L,
                    0.20f);
        }

        int delta = targetRaw - currentRaw;
        int absDelta = Math.abs(delta);
        boolean deepNightTarget = ProtectionCurveEngine.isDeepNightRaw(targetRaw);

        if (absDelta <= SCREEN_RAW_HYSTERESIS) {
            return decision(
                    Action.NOOP,
                    "NOOP_SCREEN_RAW_HYSTERESIS",
                    targetRaw,
                    0L,
                    0.95f);
        }

        if (delta > 0) {
            return decision(
                    Action.APPLY,
                    "APPLY_CONFIRMED_AMBIENT_UP",
                    targetRaw,
                    0L,
                    0.90f);
        }
        if (deepNightTarget) {
            return decision(
                    Action.APPLY,
                    "APPLY_CONFIRMED_DEEP_NIGHT",
                    targetRaw,
                    0L,
                    0.95f);
        }
        return decision(
                Action.APPLY,
                "APPLY_CONFIRMED_AMBIENT_DOWN",
                targetRaw,
                0L,
                0.90f);
    }

    /** Compatibility entry point for callers compiled against the previous API. */
    public Decision decide(float lux, int currentRaw, long now, boolean forceApply) {
        return decideConfirmedAmbient(lux, currentRaw, forceApply);
    }

    public String getShortDebugText() {
        return "confirmed-ambient raw gate / hysteresis " + SCREEN_RAW_HYSTERESIS;
    }

    private Decision decision(
            Action action,
            String reason,
            int targetRaw,
            long waitMs,
            float confidence) {
        return new Decision(
                action,
                reason,
                ProtectionCurveEngine.clampRaw(targetRaw),
                -1,
                Math.max(0L, waitMs),
                clampConfidence(confidence));
    }

    private float clampConfidence(float value) {
        if (value < 0f) {
            return 0f;
        }
        if (value > 1f) {
            return 1f;
        }
        return value;
    }
}
