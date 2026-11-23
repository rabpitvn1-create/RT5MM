package com.ericko.redmiscreenbrightness;

import android.os.SystemClock;
import android.util.Log;

/**
 * Measures event-to-successful-Settings-write latency, NOT panel response latency.
 * State lives only in RAM and logs only upon successful brightness writes.
 */
public final class ProtectionLatencyProbe {
    private static final String TAG = "RT5MM-Latency";
    private static float lastLux = -1f;
    private static long pendingChangeAt = -1L;
    private static long latestMs = -1L;
    private static volatile long latestLatencyMs = -1L;

    private ProtectionLatencyProbe() {}

    public static synchronized void reset() {
        lastLux = -1f;
        pendingChangeAt = -1L;
        latestMs = -1L;
        latestLatencyMs = -1L;
    }

    public static synchronized void observeSensor(long sensorTimeMs, float lux) {
        if (!Float.isFinite(lux) || lux < 0f || sensorTimeMs < 0) return;
        if (latestMs >= 0 && sensorTimeMs < latestMs) {
            reset();
        }
        latestMs = sensorTimeMs;
        if (lastLux < 0f) {
            lastLux = lux;
            pendingChangeAt = sensorTimeMs;
            return;
        }
        float significant = Math.max(12f, lastLux * 0.35f);
        if (Math.abs(lux - lastLux) >= significant) {
            if (pendingChangeAt < 0) pendingChangeAt = sensorTimeMs;
            lastLux = lux;
        }
    }

    public static synchronized void onApplied(int raw) {
        if (pendingChangeAt < 0) return;
        long elapsed = SystemClock.elapsedRealtime() - pendingChangeAt;
        if (elapsed < 0 || elapsed > 60_000L) {
            pendingChangeAt = -1L;
            return;
        }
        latestLatencyMs = elapsed;
        pendingChangeAt = -1L;
        Log.i(TAG, "sensor_to_settings_write_ms=" + elapsed + " raw=" + raw);
    }

    public static long getLatestLatencyMs() {
        return latestLatencyMs;
    }
}
