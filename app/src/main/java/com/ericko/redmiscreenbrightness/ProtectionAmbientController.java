package com.ericko.redmiscreenbrightness;

/**
 * AOSP-inspired ambient-light state estimator for app-level brightness control.
 * It separates raw observations from accepted ambient state with timestamped
 * fast/slow lux, hysteresis, asymmetric debounce, and guarded fast paths.
 */
public final class ProtectionAmbientController {
    private static final long BUFFER_HORIZON_MS = 8000L;
    private static final long FAST_HORIZON_MS = 700L;
    private static final long SLOW_HORIZON_MS = 3200L;

    private static final long NORMAL_BRIGHTEN_DEBOUNCE_MS = 350L;
    private static final long STRONG_BRIGHTEN_DEBOUNCE_MS = 100L;
    private static final long FAST_ONLY_BRIGHTEN_DEBOUNCE_MS = 140L;
    private static final long NORMAL_DARKEN_DEBOUNCE_MS = 650L;
    private static final long STRONG_DARKEN_DEBOUNCE_MS = 220L;
    private static final long FAST_ONLY_DARKEN_DEBOUNCE_MS = 260L;
    private static final long DEEP_NIGHT_DEBOUNCE_MS = 3000L;

    private static final long INITIAL_NORMAL_WARMUP_MS = 120L;
    private static final long INITIAL_DEEP_NIGHT_WARMUP_MS = 2200L;
    private static final long FAST_EVIDENCE_WINDOW_MS = 700L;
    private static final long INTERMEDIATE_COOLDOWN_MS = 900L;

    private static final float SUNLIGHT_MIN_LUX = 500f;
    private static final int SUNLIGHT_MIN_TARGET_RAW = 28;
    private static final int SUNLIGHT_MIN_RAW_DELTA = 6;
    private static final int SUNLIGHT_RESCUE_LOW_RAW = 31;
    private static final int SUNLIGHT_RESCUE_MID_RAW = 37;
    private static final int SUNLIGHT_RESCUE_HIGH_RAW = 43;

    private static final float DARK_SETTLE_MAX_LUX = 25f;
    private static final int DARK_SETTLE_MIN_RAW_DELTA = 5;
    private static final int DARK_SETTLE_FLOOR_RAW = 8;
    private static final int DARK_SETTLE_CEILING_RAW = 14;

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
    private long firstSampleAt = -1L;
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
        firstSampleAt = -1L;
        brighteningCandidateSince = 0L;
        darkeningCandidateSince = 0L;
        lastSunlightRescueAt = 0L;
        lastDarkSettleAt = 0L;
    }

    public Result onLuxSample(long now, float lux, int currentRaw) {
        float safeLux = sanitizeLux(lux);
        if (firstSampleAt < 0L) {
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

        boolean fastBright = fastLux > brighteningThreshold;
        boolean slowBright = slowLux > brighteningThreshold;
        boolean fastDark = fastLux < darkeningThreshold;
        boolean slowDark = slowLux < darkeningThreshold;

        if (fastBright) {
            darkeningCandidateSince = 0L;
            if (brighteningCandidateSince == 0L) {
                brighteningCandidateSince = now;
            }

            boolean fastOnlyConfirmed = isFastOnlyBrighteningConfirmed(now);
            long required = slowBright
                    ? (isStrongBrightening() ? STRONG_BRIGHTEN_DEBOUNCE_MS : NORMAL_BRIGHTEN_DEBOUNCE_MS)
                    : FAST_ONLY_BRIGHTEN_DEBOUNCE_MS;

            if ((slowBright || fastOnlyConfirmed)
                    && now - brighteningCandidateSince >= required) {
                setAmbientLux(blendLuxForBrightening());
                brighteningCandidateSince = 0L;
                return result(
                        Action.AMBIENT_BRIGHTENED,
                        slowBright ? "FAST_SLOW_BRIGHT_CONFIRMED" : "FAST_BRIGHT_PROVISIONAL_CONFIRMED",
                        -1,
                        -1);
            }
            return result(
                    Action.HOLD,
                    slowBright ? "WAIT_BRIGHTEN_DEBOUNCE" : "WAIT_FAST_BRIGHT_EVIDENCE",
                    -1,
                    -1);
        }

        if (fastDark) {
            brighteningCandidateSince = 0L;
            if (darkeningCandidateSince == 0L) {
                darkeningCandidateSince = now;
            }

            boolean deepNightCandidate = ProtectionCurveEngine.isDeepNightRaw(
                    ProtectionCurveEngine.getTargetRaw(fastLux));
            boolean fastOnlyConfirmed = !deepNightCandidate && isFastOnlyDarkeningConfirmed(now);
            long required = slowDark
                    ? getDarkeningDebounceMs()
                    : FAST_ONLY_DARKEN_DEBOUNCE_MS;

            if ((slowDark || fastOnlyConfirmed)
                    && now - darkeningCandidateSince >= required) {
                float accepted = blendLuxForDarkening();
                if (ProtectionCurveEngine.isDeepNightRaw(ProtectionCurveEngine.getTargetRaw(accepted))
                        && slowLux > 3f) {
                    return result(Action.HOLD, "WAIT_DEEP_NIGHT_SLOW_CONFIRMATION", -1, -1);
                }
                setAmbientLux(accepted);
                darkeningCandidateSince = 0L;
                return result(
                        Action.AMBIENT_DARKENED,
                        accepted <= 3f
                                ? "FAST_SLOW_DEEP_NIGHT_CONFIRMED"
                                : (slowDark
                                        ? "FAST_SLOW_DARK_CONFIRMED"
                                        : "FAST_DARK_PROVISIONAL_CONFIRMED"),
                        -1,
                        -1);
            }
            return result(
                    Action.HOLD,
                    deepNightCandidate
                            ? "WAIT_DEEP_NIGHT_DEBOUNCE"
                            : (slowDark ? "WAIT_DARKEN_DEBOUNCE" : "WAIT_FAST_DARK_EVIDENCE"),
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

        setAmbientLux(blendInitialLux());
        return result(Action.INITIALIZED, "AMBIENT_INITIALIZED", -1, -1);
    }

    private Result maybeCreateSunlightRescue(long now, int currentRaw) {
        if (now - lastSunlightRescueAt < INTERMEDIATE_COOLDOWN_MS) {
            return null;
        }
        float recentHighLux = Math.max(fastLux, ringBuffer.getLatestLux());
        int latestTargetRaw = ProtectionCurveEngine.getTargetRaw(recentHighLux);
        if (latestTargetRaw < SUNLIGHT_MIN_TARGET_RAW
                || latestTargetRaw - currentRaw < SUNLIGHT_MIN_RAW_DELTA) {
            return null;
        }

        float sampleThreshold = Math.max(
                SUNLIGHT_MIN_LUX,
                ambientValid ? ambientLux * 1.5f : SUNLIGHT_MIN_LUX);
        if (ringBuffer.countRecentAtOrAbove(now, FAST_EVIDENCE_WINDOW_MS, sampleThreshold) < 2) {
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
        if (now - lastDarkSettleAt < INTERMEDIATE_COOLDOWN_MS) {
            return null;
        }
        float recentLux = Math.min(fastLux, ringBuffer.getLatestLux());
        int latestTargetRaw = ProtectionCurveEngine.getTargetRaw(recentLux);
        if (recentLux > DARK_SETTLE_MAX_LUX
                || currentRaw - latestTargetRaw < DARK_SETTLE_MIN_RAW_DELTA
                || currentRaw <= DARK_SETTLE_CEILING_RAW) {
            return null;
        }
        if (ringBuffer.countRecentAtOrBelow(now, FAST_EVIDENCE_WINDOW_MS, DARK_SETTLE_MAX_LUX) < 2) {
            return null;
        }

        int settleRaw = Math.max(
                DARK_SETTLE_FLOOR_RAW,
                Math.min(DARK_SETTLE_CEILING_RAW, latestTargetRaw));
        if (settleRaw >= currentRaw) {
            return null;
        }
        lastDarkSettleAt = now;
        return result(Action.DARK_SETTLE, "DARK_FAST_SETTLE", settleRaw, latestTargetRaw);
    }

    private boolean isFastOnlyBrighteningConfirmed(long now) {
        if (ringBuffer.countRecentAtOrAbove(now, FAST_EVIDENCE_WINDOW_MS, brighteningThreshold) < 2) {
            return false;
        }
        return fastLux >= ambientLux * 1.30f
                && fastLux >= slowLux * 1.15f;
    }

    private boolean isFastOnlyDarkeningConfirmed(long now) {
        if (ringBuffer.countRecentAtOrBelow(now, FAST_EVIDENCE_WINDOW_MS, darkeningThreshold) < 2) {
            return false;
        }
        return fastLux <= ambientLux * 0.72f
                && fastLux <= slowLux * 0.82f;
    }

    private boolean isStrongBrightening() {
        return fastLux >= Math.max(SUNLIGHT_MIN_LUX, ambientLux * 3f);
    }

    private long getDarkeningDebounceMs() {
        if (slowLux <= 3f) {
            return DEEP_NIGHT_DEBOUNCE_MS;
        }
        if (ambientLux >= 40f && fastLux <= ambientLux * 0.30f) {
            return STRONG_DARKEN_DEBOUNCE_MS;
        }
        return NORMAL_DARKEN_DEBOUNCE_MS;
    }

    private float blendInitialLux() {
        return clampBetween(fastLux * 0.55f + slowLux * 0.45f, fastLux, slowLux);
    }

    private float blendLuxForBrightening() {
        return clampBetween(fastLux * 0.72f + slowLux * 0.28f, fastLux, slowLux);
    }

    private float blendLuxForDarkening() {
        return clampBetween(fastLux * 0.68f + slowLux * 0.32f, fastLux, slowLux);
    }

    private float clampBetween(float value, float a, float b) {
        float low = Math.min(a, b);
        float high = Math.max(a, b);
        return Math.max(low, Math.min(high, value));
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
            brightRatio = 0.40f;
            darkRatio = 0.25f;
            brightAbsolute = 0.7f;
            darkAbsolute = 0.3f;
        } else if (ambientLux <= 350f) {
            brightRatio = 0.32f;
            darkRatio = 0.28f;
            brightAbsolute = 10f;
            darkAbsolute = 8f;
        } else if (ambientLux <= 1800f) {
            brightRatio = 0.22f;
            darkRatio = 0.28f;
            brightAbsolute = 50f;
            darkAbsolute = 40f;
        } else {
            brightRatio = 0.15f;
            darkRatio = 0.32f;
            brightAbsolute = 180f;
            darkAbsolute = 150f;
        }

        brighteningThreshold = ambientLux + Math.max(brightAbsolute, ambientLux * brightRatio);
        darkeningThreshold = Math.max(
                0f,
                ambientLux - Math.max(darkAbsolute, ambientLux * darkRatio));
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
