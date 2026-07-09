package com.ericko.redmiscreenbrightness;

import java.util.Arrays;

public final class BrightnessDecisionEngine {
    private static final int WINDOW_SIZE = 8;
    private static final int MIN_SAMPLES = 4;
    private static final int RAW_NOISE_RANGE = 6;
    private static final int SPIKE_RAW_DELTA = 4;
    private static final int SAME_TARGET_TOLERANCE_RAW = 0;
    private static final long CONFIRM_UP_FAST_MS = 1600L;
    private static final long CONFIRM_UP_SMALL_MS = 2600L;
    private static final long CONFIRM_DOWN_MS = 4800L;
    private static final long CONFIRM_DOWN_NIGHT_MS = 7000L;

    public enum Action {
        APPLY,
        NOOP,
        WAIT,
        IGNORE_SPIKE,
        SENSOR_NOISY
    }

    public static final class Decision {
        public final Action action;
        public final String reason;
        public final int targetRaw;
        public final long waitMs;
        public final float confidence;

        private Decision(Action action, String reason, int targetRaw, long waitMs, float confidence) {
            this.action = action;
            this.reason = reason;
            this.targetRaw = targetRaw;
            this.waitMs = waitMs;
            this.confidence = confidence;
        }

        public boolean shouldApply() {
            return action == Action.APPLY;
        }

        public boolean isNoop() {
            return action == Action.NOOP;
        }
    }

    private final float[] luxWindow = new float[WINDOW_SIZE];
    private final int[] rawWindow = new int[WINDOW_SIZE];
    private final long[] timeWindow = new long[WINDOW_SIZE];
    private int count = 0;
    private int index = 0;
    private int lastNoopBaselineRaw = -1;

    public void reset() {
        count = 0;
        index = 0;
        lastNoopBaselineRaw = -1;
        Arrays.fill(luxWindow, -1f);
        Arrays.fill(rawWindow, -1);
        Arrays.fill(timeWindow, 0L);
    }

    public Decision decide(float lux, int currentRaw, long now, boolean forceApply) {
        int latestRaw = ProtectionCurveEngine.getTargetRaw(lux);
        addSample(lux, latestRaw, now);

        int candidateRaw = getMedianRaw();
        int delta = candidateRaw - currentRaw;
        int absDelta = Math.abs(delta);

        if (forceApply) {
            if (absDelta <= SAME_TARGET_TOLERANCE_RAW) {
                lastNoopBaselineRaw = candidateRaw;
                return decision(Action.NOOP, "FORCE_SAME_TARGET", candidateRaw, 0L, 1f);
            }
            return decision(Action.APPLY, "FORCE_CONFIRMED", candidateRaw, 0L, 1f);
        }

        if (count < MIN_SAMPLES) {
            return decision(Action.WAIT, "WAIT_WINDOW", candidateRaw, 0L, 0.25f);
        }

        if (absDelta <= SAME_TARGET_TOLERANCE_RAW) {
            if (lastNoopBaselineRaw != candidateRaw) {
                lastNoopBaselineRaw = candidateRaw;
                return decision(Action.NOOP, "NOOP_SAME_TARGET", candidateRaw, 0L, confidenceForAgreement(candidateRaw));
            }
            return decision(Action.WAIT, "NOOP_SAME_TARGET", candidateRaw, 0L, confidenceForAgreement(candidateRaw));
        }

        if (isLikelySpike(latestRaw, candidateRaw, currentRaw)) {
            return decision(Action.IGNORE_SPIKE, "IGNORE_SINGLE_SPIKE", candidateRaw, 0L, 0.35f);
        }

        int rawRange = getRawRange();
        int agreement = countWithinRaw(candidateRaw, 1);
        if (rawRange >= RAW_NOISE_RANGE && agreement < requiredAgreement(absDelta)) {
            return decision(Action.SENSOR_NOISY, "SENSOR_NOISY_RANGE_" + rawRange, candidateRaw, 0L, 0.20f);
        }

        long evidenceMs = getEvidenceAgeMs(now);
        long requiredMs = getRequiredConfirmationMs(delta, absDelta, candidateRaw);
        if (evidenceMs < requiredMs) {
            return decision(Action.WAIT, delta > 0 ? "WAIT_UP_CONFIRMATION" : "WAIT_DOWN_CONFIRMATION", candidateRaw, requiredMs - evidenceMs, 0.45f);
        }

        if (agreement < requiredAgreement(absDelta)) {
            return decision(Action.WAIT, "WAIT_MORE_AGREEMENT", candidateRaw, 0L, confidenceForAgreement(candidateRaw));
        }

        return decision(Action.APPLY, delta > 0 ? "APPLY_CONFIRMED_UP" : "APPLY_CONFIRMED_DOWN", candidateRaw, 0L, confidenceForAgreement(candidateRaw));
    }

    public String getShortDebugText() {
        if (count <= 0) {
            return "empty";
        }
        return "samples " + count
                + " / median raw " + getMedianRaw()
                + " / range " + getRawRange()
                + " / agreement " + countWithinRaw(getMedianRaw(), 1);
    }

    private void addSample(float lux, int raw, long now) {
        luxWindow[index] = lux;
        rawWindow[index] = raw;
        timeWindow[index] = now;
        index = (index + 1) % WINDOW_SIZE;
        if (count < WINDOW_SIZE) {
            count++;
        }
    }

    private Decision decision(Action action, String reason, int targetRaw, long waitMs, float confidence) {
        return new Decision(action, reason, ProtectionCurveEngine.clampRaw(targetRaw), Math.max(0L, waitMs), clampConfidence(confidence));
    }

    private int getMedianRaw() {
        if (count <= 0) {
            return ProtectionCurveEngine.clampRaw(ProtectionCurveEngine.getTargetRaw(0f));
        }
        int[] copy = new int[count];
        for (int i = 0; i < count; i++) {
            copy[i] = rawWindow[i];
        }
        Arrays.sort(copy);
        if ((count & 1) == 1) {
            return copy[count / 2];
        }
        return Math.round((copy[count / 2 - 1] + copy[count / 2]) / 2f);
    }

    private int getRawRange() {
        if (count <= 0) {
            return 0;
        }
        int min = rawWindow[0];
        int max = rawWindow[0];
        for (int i = 1; i < count; i++) {
            min = Math.min(min, rawWindow[i]);
            max = Math.max(max, rawWindow[i]);
        }
        return max - min;
    }

    private int countWithinRaw(int raw, int tolerance) {
        int agreement = 0;
        for (int i = 0; i < count; i++) {
            if (Math.abs(rawWindow[i] - raw) <= tolerance) {
                agreement++;
            }
        }
        return agreement;
    }

    private boolean isLikelySpike(int latestRaw, int medianRaw, int currentRaw) {
        int latestVsMedian = Math.abs(latestRaw - medianRaw);
        int medianVsCurrent = Math.abs(medianRaw - currentRaw);
        return latestVsMedian >= SPIKE_RAW_DELTA && medianVsCurrent <= 1;
    }

    private int requiredAgreement(int absDelta) {
        if (absDelta >= 3) {
            return 3;
        }
        return 4;
    }

    private long getEvidenceAgeMs(long now) {
        long oldest = now;
        for (int i = 0; i < count; i++) {
            if (timeWindow[i] > 0L) {
                oldest = Math.min(oldest, timeWindow[i]);
            }
        }
        return Math.max(0L, now - oldest);
    }

    private long getRequiredConfirmationMs(int delta, int absDelta, int candidateRaw) {
        if (delta > 0) {
            return absDelta >= 3 ? CONFIRM_UP_FAST_MS : CONFIRM_UP_SMALL_MS;
        }
        if (candidateRaw <= 10) {
            return CONFIRM_DOWN_NIGHT_MS;
        }
        return CONFIRM_DOWN_MS;
    }

    private float confidenceForAgreement(int targetRaw) {
        if (count <= 0) {
            return 0f;
        }
        float agreement = countWithinRaw(targetRaw, 1) / (float) count;
        float rangePenalty = Math.min(0.35f, getRawRange() * 0.04f);
        return clampConfidence(agreement - rangePenalty);
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
