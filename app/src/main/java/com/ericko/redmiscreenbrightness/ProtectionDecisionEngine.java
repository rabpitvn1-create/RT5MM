package com.ericko.redmiscreenbrightness;

/**
 * Central decision layer for screen protection.
 *
 * AutoBrightnessManager owns Android side effects. This class owns the question:
 * should the app apply, wait, hold, or block a brightness change, and why?
 */
public final class ProtectionDecisionEngine {
    public enum Action {
        APPLY, WAIT, HOLD, BLOCKED
    }

    public enum Reason {
        APPLY_TARGET,
        ALIGN_TARGET_RAW,
        CANDIDATE_CHANGED,
        SAME_BUCKET,
        STABLE_DELAY_WAIT,
        WRITE_BUDGET_WAIT,
        USER_HOLD_ACTIVE,
        SENSOR_UNTRUSTED,
        INVALID_REQUEST
    }

    public enum TargetReason {
        LUX_BAND_UP,
        LUX_BAND_DOWN,
        LEARNED_TARGET,
        SAME_BUCKET,
        UNKNOWN
    }

    public static final class Request {
        public final float lux;
        public final int currentPercent;
        public final int currentRaw;
        public final int candidatePercent;
        public final long candidateSince;
        public final long now;
        public final long lastWriteAt;
        public final long writeBudgetMs;
        public final boolean forceApply;
        public final boolean userHoldActive;
        public final boolean sensorTrusted;
        public final int learnedPercent;
        public final int rawChangeTolerance;

        public Request(
                float lux,
                int currentPercent,
                int currentRaw,
                int candidatePercent,
                long candidateSince,
                long now,
                long lastWriteAt,
                long writeBudgetMs,
                boolean forceApply,
                boolean userHoldActive,
                boolean sensorTrusted,
                int learnedPercent,
                int rawChangeTolerance
        ) {
            this.lux = lux;
            this.currentPercent = currentPercent;
            this.currentRaw = currentRaw;
            this.candidatePercent = candidatePercent;
            this.candidateSince = candidateSince;
            this.now = now;
            this.lastWriteAt = lastWriteAt;
            this.writeBudgetMs = writeBudgetMs;
            this.forceApply = forceApply;
            this.userHoldActive = userHoldActive;
            this.sensorTrusted = sensorTrusted;
            this.learnedPercent = learnedPercent;
            this.rawChangeTolerance = rawChangeTolerance;
        }
    }

    public static final class Decision {
        public final Action action;
        public final Reason reason;
        public final TargetReason targetReason;
        public final int targetPercent;
        public final int targetRaw;
        public final int nextCandidatePercent;
        public final long nextCandidateSince;
        public final long stableMs;
        public final long waitMs;
        public final float confidence;

        private Decision(
                Action action,
                Reason reason,
                TargetReason targetReason,
                int targetPercent,
                int targetRaw,
                int nextCandidatePercent,
                long nextCandidateSince,
                long stableMs,
                long waitMs,
                float confidence
        ) {
            this.action = action;
            this.reason = reason;
            this.targetReason = targetReason;
            this.targetPercent = targetPercent;
            this.targetRaw = targetRaw;
            this.nextCandidatePercent = nextCandidatePercent;
            this.nextCandidateSince = nextCandidateSince;
            this.stableMs = stableMs;
            this.waitMs = waitMs;
            this.confidence = confidence;
        }

        public String toLogSuffix() {
            return action.name()
                    + "_" + reason.name()
                    + "_" + targetReason.name()
                    + "_TARGET_" + targetPercent
                    + "_RAW_" + targetRaw
                    + "_WAIT_" + waitMs
                    + "_CONF_" + Math.round(confidence * 100f);
        }
    }

    private final ProtectionPolicy protectionPolicy;

    public ProtectionDecisionEngine(ProtectionPolicy protectionPolicy) {
        this.protectionPolicy = protectionPolicy == null ? new ProtectionPolicy() : protectionPolicy;
    }

    public Decision decide(Request request) {
        if (request == null) {
            return fallback(Action.BLOCKED, Reason.INVALID_REQUEST, TargetReason.UNKNOWN, ProtectionPolicy.LEVEL_20, 0L, 0L, 0f);
        }

        int currentPercent = normalizePercent(request.currentPercent);
        int currentRaw = request.currentRaw;
        long now = Math.max(0L, request.now);

        if (request.userHoldActive) {
            int raw = BrightnessLevels.getRawForPercent(currentPercent);
            return new Decision(
                    Action.HOLD,
                    Reason.USER_HOLD_ACTIVE,
                    TargetReason.SAME_BUCKET,
                    currentPercent,
                    raw,
                    currentPercent,
                    now,
                    0L,
                    0L,
                    1f
            );
        }

        if (!request.sensorTrusted || Float.isNaN(request.lux) || request.lux < 0f) {
            int raw = BrightnessLevels.getRawForPercent(currentPercent);
            return new Decision(
                    Action.WAIT,
                    Reason.SENSOR_UNTRUSTED,
                    TargetReason.UNKNOWN,
                    currentPercent,
                    raw,
                    request.candidatePercent,
                    request.candidateSince,
                    0L,
                    0L,
                    0f
            );
        }

        int baseTarget = normalizePercent(protectionPolicy.getTargetPercent(request.lux, currentPercent));
        boolean useLearned = request.learnedPercent >= ProtectionPolicy.LEVEL_12
                && request.learnedPercent <= ProtectionPolicy.LEVEL_60;
        int targetPercent = normalizePercent(useLearned ? request.learnedPercent : baseTarget);
        int targetRaw = BrightnessLevels.getRawForPercent(targetPercent);
        TargetReason targetReason = getTargetReason(useLearned, currentPercent, targetPercent);

        if (targetPercent == currentPercent) {
            if (Math.abs(currentRaw - targetRaw) > Math.max(0, request.rawChangeTolerance)) {
                return new Decision(
                        Action.APPLY,
                        Reason.ALIGN_TARGET_RAW,
                        targetReason,
                        targetPercent,
                        targetRaw,
                        targetPercent,
                        now,
                        0L,
                        0L,
                        0.95f
                );
            }
            return new Decision(
                    Action.HOLD,
                    Reason.SAME_BUCKET,
                    targetReason,
                    targetPercent,
                    targetRaw,
                    targetPercent,
                    now,
                    0L,
                    0L,
                    1f
            );
        }

        if (request.candidatePercent != targetPercent) {
            return new Decision(
                    Action.WAIT,
                    Reason.CANDIDATE_CHANGED,
                    targetReason,
                    targetPercent,
                    targetRaw,
                    targetPercent,
                    now,
                    protectionPolicy.getStableMs(currentPercent, targetPercent),
                    0L,
                    0.35f
            );
        }

        long stableMs = protectionPolicy.getStableMs(currentPercent, targetPercent);
        long candidateSince = Math.max(0L, request.candidateSince);
        long elapsed = Math.max(0L, now - candidateSince);
        if (elapsed < stableMs) {
            float confidence = stableMs <= 0L ? 0.8f : Math.min(0.85f, Math.max(0.35f, (float) elapsed / (float) stableMs));
            return new Decision(
                    Action.WAIT,
                    Reason.STABLE_DELAY_WAIT,
                    targetReason,
                    targetPercent,
                    targetRaw,
                    targetPercent,
                    candidateSince,
                    stableMs,
                    stableMs - elapsed,
                    confidence
            );
        }

        long budgetWaitMs = getWriteBudgetWaitMs(request, now, currentPercent, targetPercent);
        if (budgetWaitMs > 0L) {
            return new Decision(
                    Action.WAIT,
                    Reason.WRITE_BUDGET_WAIT,
                    targetReason,
                    targetPercent,
                    targetRaw,
                    targetPercent,
                    candidateSince,
                    stableMs,
                    budgetWaitMs,
                    0.9f
            );
        }

        return new Decision(
                Action.APPLY,
                Reason.APPLY_TARGET,
                targetReason,
                targetPercent,
                targetRaw,
                targetPercent,
                candidateSince,
                stableMs,
                0L,
                1f
        );
    }

    private long getWriteBudgetWaitMs(Request request, long now, int currentPercent, int targetPercent) {
        if (request.forceApply) {
            return 0L;
        }
        if (targetPercent > currentPercent && request.lux >= 1000f) {
            return 0L;
        }
        long budgetMs = Math.max(0L, request.writeBudgetMs);
        long lastWriteAt = Math.max(0L, request.lastWriteAt);
        if (budgetMs <= 0L || lastWriteAt <= 0L) {
            return 0L;
        }
        long elapsedSinceWrite = Math.max(0L, now - lastWriteAt);
        if (elapsedSinceWrite >= budgetMs) {
            return 0L;
        }
        return budgetMs - elapsedSinceWrite;
    }

    private Decision fallback(Action action, Reason reason, TargetReason targetReason, int percent, long now, long waitMs, float confidence) {
        int normalized = normalizePercent(percent);
        int raw = BrightnessLevels.getRawForPercent(normalized);
        return new Decision(action, reason, targetReason, normalized, raw, normalized, now, 0L, waitMs, confidence);
    }

    private TargetReason getTargetReason(boolean useLearned, int currentPercent, int targetPercent) {
        if (useLearned) {
            return TargetReason.LEARNED_TARGET;
        }
        if (targetPercent > currentPercent) {
            return TargetReason.LUX_BAND_UP;
        }
        if (targetPercent < currentPercent) {
            return TargetReason.LUX_BAND_DOWN;
        }
        return TargetReason.SAME_BUCKET;
    }

    private int normalizePercent(int percent) {
        if (percent < ProtectionPolicy.LEVEL_12) {
            return ProtectionPolicy.LEVEL_12;
        }
        if (percent > ProtectionPolicy.LEVEL_60) {
            return ProtectionPolicy.LEVEL_60;
        }
        return BrightnessLevels.getPercentForRaw(BrightnessLevels.getRawForPercent(percent));
    }
}
