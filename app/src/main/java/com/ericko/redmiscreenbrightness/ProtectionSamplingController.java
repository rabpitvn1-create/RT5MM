package com.ericko.redmiscreenbrightness;

/** Pure, monotonic policy for selecting the light-sensor sampling rate. */
public final class ProtectionSamplingController {
    private static final long FAST_TRACK_MS = 3500L;
    private static final long STABLE_ECO_AFTER_MS = 9000L;
    private static final long MIN_MODE_DWELL_MS = 2500L;

    public enum Mode {
        FAST_TRACK(100_000, 0),
        ACTIVE_TRACK(300_000, 0),
        STABLE_ECO(850_000, 300_000),
        USER_HOLD_ECO(1_500_000, 500_000),
        SCREEN_OFF_SLEEP(0, 0);

        public final int samplingPeriodUs;
        public final int maxReportLatencyUs;

        Mode(int samplingPeriodUs, int maxReportLatencyUs) {
            this.samplingPeriodUs = samplingPeriodUs;
            this.maxReportLatencyUs = maxReportLatencyUs;
        }
    }

    private Mode mode = Mode.FAST_TRACK;
    private long modeSinceMs = -1L;
    private long fastUntilMs = -1L;
    private long stableSinceMs = -1L;

    public void reset(long nowMs) {
        mode = Mode.FAST_TRACK;
        modeSinceMs = nowMs;
        fastUntilMs = nowMs + FAST_TRACK_MS;
        stableSinceMs = -1L;
    }

    public Mode onScreenOff(long nowMs) {
        stableSinceMs = -1L;
        return commit(Mode.SCREEN_OFF_SLEEP, nowMs, true);
    }

    public Mode onScreenWake(long nowMs) {
        stableSinceMs = -1L;
        fastUntilMs = nowMs + FAST_TRACK_MS;
        return commit(Mode.FAST_TRACK, nowMs, true);
    }

    public Mode onAmbientResult(
            long nowMs,
            ProtectionAmbientController.Action action,
            String reason,
            boolean userHoldActive) {
        if (modeSinceMs < 0L) reset(nowMs);

        if (userHoldActive) {
            stableSinceMs = -1L;
            return commit(Mode.USER_HOLD_ECO, nowMs, false);
        }

        if (isMeaningfulAction(action)) {
            stableSinceMs = -1L;
            fastUntilMs = nowMs + FAST_TRACK_MS;
            return commit(Mode.FAST_TRACK, nowMs, true);
        }

        if (nowMs < fastUntilMs) {
            return commit(Mode.FAST_TRACK, nowMs, false);
        }

        boolean stableHold = action == ProtectionAmbientController.Action.HOLD
                && "AMBIENT_HYSTERESIS_HOLD".equals(reason);
        if (stableHold) {
            if (stableSinceMs < 0L) stableSinceMs = nowMs;
            if (nowMs - stableSinceMs >= STABLE_ECO_AFTER_MS) {
                return commit(Mode.STABLE_ECO, nowMs, false);
            }
        } else {
            stableSinceMs = -1L;
        }
        return commit(Mode.ACTIVE_TRACK, nowMs, false);
    }

    public Mode getMode() {
        return mode;
    }

    public boolean shouldReregister(Mode requestedMode, long nowMs) {
        if (requestedMode == mode) return false;
        if (requestedMode == Mode.SCREEN_OFF_SLEEP || mode == Mode.SCREEN_OFF_SLEEP) return true;
        if (requestedMode == Mode.FAST_TRACK) return true;
        return modeSinceMs < 0L || nowMs - modeSinceMs >= MIN_MODE_DWELL_MS;
    }

    public Mode commitMode(Mode requestedMode, long nowMs) {
        return commit(requestedMode, nowMs, true);
    }

    private Mode commit(Mode requestedMode, long nowMs, boolean force) {
        if (requestedMode == mode) return mode;
        if (!force && !shouldReregister(requestedMode, nowMs)) return mode;
        mode = requestedMode;
        modeSinceMs = nowMs;
        return mode;
    }

    private boolean isMeaningfulAction(ProtectionAmbientController.Action action) {
        return action == ProtectionAmbientController.Action.SUNLIGHT_RESCUE
                || action == ProtectionAmbientController.Action.DARK_SETTLE
                || action == ProtectionAmbientController.Action.AMBIENT_BRIGHTENED
                || action == ProtectionAmbientController.Action.AMBIENT_DARKENED
                || action == ProtectionAmbientController.Action.INITIALIZED;
    }
}
