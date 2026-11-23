package com.ericko.redmiscreenbrightness;

/** Presentation-only reducer. Lux freshness does not determine controller health. */
public final class ObsidianUiState {
    public enum Phase {
        OFF, PERMISSION_REQUIRED, STARTING, STOPPING, PROTECTING, USER_HOLD,
        WAITING_SENSOR, UNCONFIRMED, SERVICE_ERROR, RESTORE_FAILED
    }

    public enum Ambient { UNKNOWN, LOW, MEDIUM, HIGH }

    private ObsidianUiState() {}

    public static Phase resolve(
            boolean requestedOn, boolean hasPermission, boolean hasSensor,
            boolean restoreFailed, boolean stopping, boolean starting,
            boolean confirmedControl, boolean manualHold,
            boolean readingSensor, boolean confirmedFailure) {
        if (restoreFailed) return Phase.RESTORE_FAILED;
        if (stopping) return Phase.STOPPING;
        if (!hasPermission) return Phase.PERMISSION_REQUIRED;
        if (starting) return Phase.STARTING;
        if (!hasSensor || confirmedFailure) return Phase.SERVICE_ERROR;
        if (!requestedOn) return Phase.OFF;
        if (confirmedControl) return manualHold ? Phase.USER_HOLD : Phase.PROTECTING;
        return readingSensor ? Phase.WAITING_SENSOR : Phase.UNCONFIRMED;
    }

    public static Ambient ambient(float lux) {
        if (!Float.isFinite(lux) || lux < 0f) return Ambient.UNKNOWN;
        if (lux <= 70f) return Ambient.LOW;
        if (lux <= 800f) return Ambient.MEDIUM;
        return Ambient.HIGH;
    }

    public static boolean isControlling(Phase phase) {
        return phase == Phase.PROTECTING || phase == Phase.USER_HOLD;
    }
}
