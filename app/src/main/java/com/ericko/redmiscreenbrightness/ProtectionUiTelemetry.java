package com.ericko.redmiscreenbrightness;

import android.os.SystemClock;

/**
 * Short-lived presentation telemetry. Never persisted, never used by the
 * adaptive controller. A sensor value from a previous process/session/wake
 * is never treated as a current reading on the dashboard.
 */
public final class ProtectionUiTelemetry {
    private static final long FRESH_SAMPLE_MS = 10000L;

    private static volatile boolean sensorRegistered;
    private static volatile long lastSampleElapsed = -1L;
    private static volatile float latestLux = -1f;

    private ProtectionUiTelemetry() {}

    public static void sensorRegistrationChanged(boolean registered) {
        if (!registered || !sensorRegistered) {
            latestLux = -1f;
            lastSampleElapsed = -1L;
        }
        sensorRegistered = registered;
    }

    public static void onValidSensorSample(float lux) {
        if (!sensorRegistered || !Float.isFinite(lux) || lux < 0f) return;
        latestLux = lux;
        lastSampleElapsed = SystemClock.elapsedRealtime();
    }

    public static boolean isSensorRegistered() {
        return sensorRegistered;
    }

    public static boolean hasObservedSample() {
        return sensorRegistered && lastSampleElapsed >= 0L;
    }

    public static boolean hasFreshSample() {
        long sampledAt = lastSampleElapsed;
        long now = SystemClock.elapsedRealtime();
        return sensorRegistered && sampledAt >= 0L && now >= sampledAt
                && now - sampledAt <= FRESH_SAMPLE_MS && latestLux >= 0f;
    }

    public static float getFreshLux() {
        return hasFreshSample() ? latestLux : -1f;
    }
}
