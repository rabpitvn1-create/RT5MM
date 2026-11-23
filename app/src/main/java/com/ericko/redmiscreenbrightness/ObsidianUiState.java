package com.ericko.redmiscreenbrightness;

/**
 * Presentation-only state reducer. Inputs are externally confirmed runtime facts,
 * not the persisted "user wants protection enabled" flag alone.
 */
public final class ObsidianUiState {
    public enum Phase {
        OFF,
        PERMISSION_REQUIRED,
        STARTING,
        PROTECTING,
        USER_HOLD,
        WAITING_SENSOR,
        SERVICE_ERROR,
        RESTORE_FAILED
    }

    public enum Ambient {
        UNKNOWN, LOW, MEDIUM, HIGH
    }

    private ObsidianUiState() {}

    public static Phase resolve(
            boolean requestedOn,
            boolean hasWritePermission,
            boolean hasLightSensor,
            boolean restorePending,
            boolean foregroundHealthy,
            boolean systemManualConfirmed,
            boolean sensorRegistered,
            boolean sensorFresh,
            boolean manualHold,
            boolean insideStartGrace) {
        if (!requestedOn) {
            if (restorePending) return Phase.RESTORE_FAILED;
            if (!hasLightSensor) return Phase.SERVICE_ERROR;
            if (!hasWritePermission) return Phase.PERMISSION_REQUIRED;
            return Phase.OFF;
        }

        if (!hasWritePermission || !hasLightSensor) return Phase.SERVICE_ERROR;
        if (foregroundHealthy && systemManualConfirmed && sensorRegistered) {
            if (!sensorFresh) return Phase.WAITING_SENSOR;
            return manualHold ? Phase.USER_HOLD : Phase.PROTECTING;
        }
        return insideStartGrace ? Phase.STARTING : Phase.SERVICE_ERROR;
    }

    public static Ambient ambient(float lux) {
        if (!Float.isFinite(lux) || lux < 0f) return Ambient.UNKNOWN;
        if (lux <= 70f) return Ambient.LOW;
        if (lux <= 800f) return Ambient.MEDIUM;
        return Ambient.HIGH;
    }

    public static boolean canShowMetrics(Phase phase) {
        return phase == Phase.PROTECTING || phase == Phase.USER_HOLD;
    }
}
