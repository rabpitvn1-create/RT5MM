package com.ericko.redmiscreenbrightness;

/**
 * AOSP-inspired ambient-light state estimator for app-level brightness control.
 * It separates raw sensor observations from the accepted ambient state by using
 * timestamped fast/slow lux estimates, hysteresis, asymmetric debounce, and
 * guarded fast paths for sudden sunlight and sudden darkness.
 */
public final class ProtectionAmbientController {
    private static final long BUFFER_HORIZON_MS = 10000L;
    private static final long FAST_HORIZON_MS = 1200L;
    private static final long SLOW_HORIZON_MS = 6000L;
    private static final long NORMAL_BRIGHTEN_DEBOUNCE_MS = 700L;
    private static final long STRONG_BRIGHTEN_DEBOUNCE_MS = 250L;
    private static final long NORMAL_DARKEN_DEBOUNCE_MS = 1400L;
    private static final long STRONG_DARKEN_DEBOUNCE_MS = 700L;
    private static final long DEEP_NIGHT_DEBOUNCE_MS = 5000L;
    private static final long INITIAL_NORMAL_WARMUP_MS = 250L;
    private static final long INITIAL_DEEP_NIGHT_WARMUP_MS = 3500L;
    private static final long RESCUE_SAMPLE_WINDOW_MS = 900L;
    private static final long RESCUE_COOLDOWN_MS = 1800L;

    private static final float SUNLIGHT_MIN_LUX = 800f;
    private static final int SUNLIGHT_MIN_TARGET_RAW = 31;
    private static final int SUNLIGHT_MIN_RAW_DELTA = 10;
    private static final int SUNLIGHT_RESCUE_LOW_RAW = 25;
    private static final int SUNLIGHT_RESCUE_MID_RAW = 31;
    private static final int SUNLIGHT_RESCUE_HIGH_RAW = 34;

    private static final float DARK_SETTLE_MAX_LUX = 12f;
    private static final int DARK_SETTLE_MIN_RAW_DELTA = 8;
    private static final int DARK_SETTLE_FLOOR_RAW = 7;
    private static final int DARK_SETTLE_CEILING_RAW = 11;

    public enum Action {
        HOLD,
        INITIALIZED,
        AMBIENT_BRIGHTENED,
        AMBIENT_DARKENED,
        SUNLIGHT_RESCUE,
        DARK_SETTLE
    }

    public static final class Result {
        public final Action action;
        public final String reason;
        public final boolean ambientValid;
        public final float ambientLux;
        public final float fastLux;
        public final float slowLux;
        public final float brighteningThreshold;
        public final float darkeningThreshold;
        public final int intermediateRaw;
        public final int finalTargetRaw;

        private Result(
                Action action,
                String reason,
                boolean ambientValid,
                float ambientLux,
                float fastLux,
                float slowLux,
                float brighteningThreshold,
                float darkeningThreshold,
                int intermediateRaw,
                int finalTargetRaw) {
            this.action = action;
            this.reason = reason;
            this.ambientValid = ambientValid;
            this.ambientLux = ambientLux;
            this.fastLux = fastLux;
            this.slowLux = slowLux;
            this.brighteningThreshold = brighteningThreshold;
            this.darkeningThreshold = darkeningThreshold;
            this.intermediateRaw = intermediateRaw;
            this.finalTargetRaw = finalTargetRaw;
        }

        public boolean ambientChanged() {
            return action == Action.INITIALIZED
                    || action == Action.AMBIENT_BRIGHTENED
                    || action == Action.AMBIENT_DARKENED;
        }
    }

    private final AmbientLightRingBuffer ringBuffer = new AmbientLightRingBuffer();

    private boolean ambientValid;
    private float ambientLux = -1f;
    private float fastLux = -1f;
    private float slowLux = -1f;
    private float brighteningThreshold = Float.POSITIVE_INFINITY;
    private float darkeningThreshold = 0f;
    private long firstSampleAt;
    private long brighteningCandidateSince;
    private long darkeningCandidateSince;
    private long lastSunlightRescueAt;
    private long lastDarkSettleAt;

    public void reset() {
        ringBuffer.clear();
        ambientValid = false;
        ambientLux = -1f;
        fastLux = -1f;
        slowLux = -1f;
        brighteningThreshold = Float.POSITIVE_INFINITY;
        darkeningThreshold = 0f;
        firstSampleAt = 0L;
        brighteningCandidateSince = 0L;
        darkeningCandidateSince = 0L;
        lastSunlightRescueAt = 0L;
        lastDarkSettleAt = 0L;
    }

    public Result onLuxSample(long now, float lux, int currentRaw) {
        float safeLux = sanitizeLux(lux);
        if (firstSampleAt == 0L) {
            firstSampleAt = now;
        }

        ringBuffer.push(now, safeLux);
        ringBuffer.prune(now - BUFFER_HORIZON_MS);
        fastLux = ringBuffer.calculateWeightedLux(now, FAST_HORIZON_MS);
        slowLux = ringBuffer.calculateWeightedLux(now, SLOW_HORIZON_MS);

        if (!ambientValid) {
            Result guardedInitialization = handleInitialState(now, currentRaw);
            if (guardedInitialization != null) {
                return guardedInitialization;
            }
        }

        Result sunlight = maybeCreateSunlightRescue(now, currentRaw);
        if (sunlight != null) {
            return sunlight;
        }

        Result darkSettle = maybeCreateDarkSettle(now, currentRaw);
        if (darkSettle != null) {
            return darkSettle;
        }

        if (!ambientValid) {
            return result(Action.HOLD, "AMBIENT_WARMUP", -1, -1);
        }

        boolean brightening = fastLux > brighteningThreshold && slowLux > brighteningThreshold;
        boolean darkening = fastLux < darkeningThreshold && slowLux < darkeningThreshold;

        if (brightening) {
            darkeningCandidateSince = 0L;
            if (brighteningCandidateSince == 0L) {
                brighteningCandidateSince = now;
            }
            long required = isStrongBrightening() ? STRONG_BRIGHTEN_DEBOUNCE_MS : NORMAL_BRIGHTEN_DEBOUNCE_MS;
            if (now - brighteningCandidateSince >= required) {
                float accepted = Math.max(fastLux, slowLux);
                setAmbientLux(accepted);
                brighteningCandidateSince = 0L;
                return result(Action.AMBIENT_BRIGHTENED, "FAST_SLOW_BRIGHT_CONFIRMED", -1, -1);
            }
            return result(Action.HOLD, "WAIT_BRIGHTEN_DEBOUNCE", -1, -1);
        }

        if (darkening) {
            brighteningCandidateSince = 0L;
            if (darkeningCandidateSince == 0L) {
                darkeningCandidateSince = now;
            }
            long required = getDarkeningDebounceMs();
            if (now - darkeningCandidateSince >= required) {
                float accepted = Math.min(fastLux, slowLux);
                setAmbientLux(accepted);
                darkeningCandidateSince = 0L;
                return result(Action.AMBIENT_DARKENED,
                        accepted <= 3f ? "FAST_SLOW_DEEP_NIGHT_CONFIRMED" : "FAST_SLOW_DARK_CONFIRMED",
                        -1,
                        -1);
            }
            return result(Action.HOLD,
                    slowLux <= 3f ? "WAIT_DEEP_NIGHT_DEBOUNCE" : "WAIT_DARKEN_DEBOUNCE",
                    -1,
                    -1);
        }

        brighteningCandidateSince = 0L;
        darkeningCandidateSince = 0L;
        return result(Action.HOLD, "AMBIENT_HYSTERESIS_HOLD", -1, -1);
    }

    public boolean isAmbientValid() {
        return ambientValid;
    }

    public float getAmbientLux() {
        return ambientLux;
    }

    public float getFastLux() {
        return fastLux;
    }

    public float getSlowLux() {
        return slowLux;
    }

    public String getDebugText() {
        return "ambient " + format(ambientLux)
                + " / fast " + format(fastLux)
                + " / slow " + format(slowLux)
                + " / dark<" + format(darkeningThreshold)
                + " / bright>" + format(brighteningThreshold)
                + " / samples " + ringBuffer.size();
    }

    private Result handleInitialState(long now, int currentRaw) {
        if (ringBuffer.size() < 2) {
            return result(Action.HOLD, "WAIT_INITIAL_SECOND_SAMPLE", -1, -1);
        }

        if (fastLux <= 3f) {
            Result darkSettle = maybeCreateDarkSettle(now, currentRaw);
            if (darkSettle != null) {
                return darkSettle;
            }
            if (ringBuffer.size() < 4 || now - firstSampleAt < INITIAL_DEEP_NIGHT_WARMUP_MS) {
                return result(Action.HOLD, "WAIT_INITIAL_DEEP_NIGHT_GUARD", -1, -1);
            }
        } else if (now - firstSampleAt < INITIAL_NORMAL_WARMUP_MS) {
            return result(Action.HOLD, "WAIT_INITIAL_WARMUP", -1, -1);
        }

        setAmbientLux(Math.max(0f, slowLux));
        return result(Action.INITIALIZED, "AMBIENT_INITIALIZED", -1, -1);
    }

    private Result maybeCreateSunlightRescue(long now, int currentRaw) {
        if (now - lastSunlightRescueAt < RESCUE_COOLDOWN_MS) {
            return null;
        }
        int latestTargetRaw = ProtectionCurveEngine.getTargetRaw(Math.max(fastLux, ringBuffer.getLatestLux()));
        if (latestTargetRaw < SUNLIGHT_MIN_TARGET_RAW
                || latestTargetRaw - currentRaw < SUNLIGHT_MIN_RAW_DELTA) {
            return null;
        }

        float sampleThreshold = Math.max(SUNLIGHT_MIN_LUX, ambientValid ? ambientLux * 2f : SUNLIGHT_MIN_LUX);
        if (ringBuffer.countRecentAtOrAbove(now, RESCUE_SAMPLE_WINDOW_MS, sampleThreshold) < 2) {
            return null;
        }

        int rescueRaw = getSunlightRescueRaw(currentRaw, latestTargetRaw);
        if (rescueRaw <= currentRaw) {
            return null;
        }
        lastSunlightRescueAt = now;
        return result(Action.SUNLIGHT_RESCUE, "SUNLIGHT_FAST_RECOVERY", rescueRaw, latestTargetRaw);
    }

    private Result maybeCreateDarkSettle(long now, int currentRaw) {
        if (now - lastDarkSettleAt < RESCUE_COOLDOWN_MS) {
            return null;
        }
        float recentLux = Math.min(fastLux, ringBuffer.getLatestLux());
        int latestTargetRaw = ProtectionCurveEngine.getTargetRaw(recentLux);
        if (recentLux > DARK_SETTLE_MAX_LUX
                || currentRaw - latestTargetRaw < DARK_SETTLE_MIN_RAW_DELTA
                || currentRaw <= DARK_SETTLE_CEILING_RAW) {
            return null;
        }
        if (ringBuffer.countRecentAtOrBelow(now, RESCUE_SAMPLE_WINDOW_MS, DARK_SETTLE_MAX_LUX) < 2) {
            return null;
        }

        int settleRaw = Math.max(DARK_SETTLE_FLOOR_RAW, Math.min(DARK_SETTLE_CEILING_RAW, latestTargetRaw));
        if (settleRaw >= currentRaw) {
            return null;
        }
        lastDarkSettleAt = now;
        return result(Action.DARK_SETTLE, "DARK_FAST_SETTLE", settleRaw, latestTargetRaw);
    }

    private boolean isStrongBrightening() {
        return fastLux >= Math.max(SUNLIGHT_MIN_LUX, ambientLux * 4f);
    }

    private long getDarkeningDebounceMs() {
        if (slowLux <= 3f) {
            return DEEP_NIGHT_DEBOUNCE_MS;
        }
        if (ambientLux >= 40f && fastLux <= ambientLux * 0.25f) {
            return STRONG_DARKEN_DEBOUNCE_MS;
        }
        return NORMAL_DARKEN_DEBOUNCE_MS;
    }

    private int getSunlightRescueRaw(int currentRaw, int targetRaw) {
        if (currentRaw < 12) {
            return Math.min(targetRaw, SUNLIGHT_RESCUE_LOW_RAW);
        }
        if (currentRaw < 20) {
            return Math.min(targetRaw, SUNLIGHT_RESCUE_MID_RAW);
        }
        if (currentRaw < 28) {
            return Math.min(targetRaw, SUNLIGHT_RESCUE_HIGH_RAW);
        }
        return -1;
    }

    private void setAmbientLux(float newAmbientLux) {
        ambientLux = sanitizeLux(newAmbientLux);
        ambientValid = true;
        updateThresholds();
    }

    private void updateThresholds() {
        float brightRatio;
        float darkRatio;
        float brightAbsolute;
        float darkAbsolute;

        if (ambientLux <= 6f) {
            brightRatio = 0.50f;
            darkRatio = 0.30f;
            brightAbsolute = 1f;
            darkAbsolute = 0.5f;
        } else if (ambientLux <= 350f) {
            brightRatio = 0.50f;
            darkRatio = 0.40f;
            brightAbsolute = 15f;
            darkAbsolute = 10f;
        } else if (ambientLux <= 1800f) {
            brightRatio = 0.30f;
            darkRatio = 0.35f;
            brightAbsolute = 75f;
            darkAbsolute = 50f;
        } else {
            brightRatio = 0.20f;
            darkRatio = 0.40f;
            brightAbsolute = 250f;
            darkAbsolute = 200f;
        }

        brighteningThreshold = ambientLux + Math.max(brightAbsolute, ambientLux * brightRatio);
        darkeningThreshold = Math.max(0f, ambientLux - Math.max(darkAbsolute, ambientLux * darkRatio));
    }

    private Result result(Action action, String reason, int intermediateRaw, int finalTargetRaw) {
        return new Result(
                action,
                reason,
                ambientValid,
                ambientLux,
                fastLux,
                slowLux,
                brighteningThreshold,
                darkeningThreshold,
                intermediateRaw,
                finalTargetRaw);
    }

    private float sanitizeLux(float lux) {
        if (Float.isNaN(lux) || Float.isInfinite(lux) || lux < 0f) {
            return 0f;
        }
        return lux;
    }

    private String format(float value) {
        if (value < 0f || Float.isInfinite(value)) {
            return "unknown";
        }
        return String.format(java.util.Locale.US, "%.1f", value);
    }
}
