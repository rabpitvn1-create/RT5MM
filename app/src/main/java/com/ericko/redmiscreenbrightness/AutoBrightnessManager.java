package com.ericko.redmiscreenbrightness;

import android.content.Context;
import android.content.SharedPreferences;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.util.Locale;

/**
 * Single Android integration layer for the brightness engine.
 *
 * Raw sensor observations, accepted ambient state, desired raw brightness and
 * physical brightness writes stay separate. Runtime timing uses elapsedRealtime;
 * wall clock is used only for state that must survive process death.
 */
public final class AutoBrightnessManager implements SensorEventListener {
    private static final String KEY_AUTO_ENABLED = "auto_brightness_enabled";
    private static final String KEY_AUTO_MODE = "auto_brightness_mode";
    private static final String KEY_LAST_LUX = "auto_brightness_last_lux";
    private static final String KEY_SENSOR_AVAILABLE = "auto_brightness_sensor_available";
    private static final String KEY_USER_HOLD_UNTIL = "auto_brightness_user_hold_until";
    private static final String KEY_USER_HOLD_LUX = "auto_brightness_user_hold_lux";
    private static final String KEY_USER_HOLD_RAW = "auto_brightness_user_hold_raw";
    private static final String KEY_USER_HOLD_AT = "auto_brightness_user_hold_at";
    private static final String KEY_LAST_AUTO_RAW = "auto_brightness_last_auto_raw";
    private static final String KEY_LAST_AUTO_AT = "auto_brightness_last_auto_at";
    private static final String KEY_LAST_DECISION_AT = "protection_last_decision_at";
    private static final String KEY_LAST_DECISION_ACTION = "protection_last_decision_action";
    private static final String KEY_LAST_DECISION_REASON = "protection_last_decision_reason";
    private static final String KEY_LAST_DECISION_TARGET_REASON = "protection_last_decision_target_reason";
    private static final String KEY_LAST_DECISION_TARGET_PERCENT = "protection_last_decision_target_percent";
    private static final String KEY_LAST_DECISION_TARGET_RAW = "protection_last_decision_target_raw";
    private static final String KEY_LAST_DECISION_STABLE_MS = "protection_last_decision_stable_ms";
    private static final String KEY_LAST_DECISION_WAIT_MS = "protection_last_decision_wait_ms";
    private static final String KEY_LAST_DECISION_CONFIDENCE = "protection_last_decision_confidence";
    private static final String KEY_AMBIENT_ACTION = "protection_ambient_action";
    private static final String KEY_AMBIENT_REASON = "protection_ambient_reason";
    private static final String KEY_AMBIENT_FAST_LUX = "protection_ambient_fast_lux";
    private static final String KEY_AMBIENT_SLOW_LUX = "protection_ambient_slow_lux";
    private static final String KEY_AMBIENT_BRIGHT_THRESHOLD = "protection_ambient_bright_threshold";
    private static final String KEY_AMBIENT_DARK_THRESHOLD = "protection_ambient_dark_threshold";
    private static final String KEY_SAMPLING_MODE = "protection_sampling_mode";

    private static final float MAX_LUX = 120000f;
    private static final int RAW_HYSTERESIS = 1;
    private static final int USER_CHANGE_MIN_RAW = 3;
    private static final long USER_INTENT_STABLE_MS = 900L;
    private static final long USER_HOLD_NORMAL_MS = 10L * 60L * 1000L;
    private static final long USER_HOLD_NIGHT_MS = 5L * 60L * 1000L;
    public static final long USER_HOLD_MS = USER_HOLD_NORMAL_MS;
    private static final long LUX_PERSIST_INTERVAL_MS = 60_000L;
    private static final float LUX_PERSIST_RELATIVE_DELTA = 0.20f;
    private static final float LUX_PERSIST_ABSOLUTE_DELTA = 5f;
    private static final long DIAGNOSTIC_PERSIST_INTERVAL_MS = 60_000L;
    private static final long OCCLUSION_GUARD_MS = 1600L;
    private static final float OCCLUSION_PREVIOUS_MIN_LUX = 80f;
    private static final float OCCLUSION_SAMPLE_MAX_LUX = 6f;

    public enum Mode {
        OFF, PROTECTING, USER_HOLD, UNAVAILABLE
    }

    private static volatile float liveLux = -1f;

    private final Context appContext;
    private final SensorManager sensorManager;
    private final Sensor lightSensor;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ProtectionTransitionEngine transitionEngine;
    private final BrightnessDecisionEngine decisionEngine = new BrightnessDecisionEngine();
    private final ProtectionAmbientController ambientController = new ProtectionAmbientController();
    private final ProtectionSamplingController samplingController = new ProtectionSamplingController();

    private boolean registered;
    private boolean screenAwake = true;
    private float lastRamLux = -1f;
    private float lastPersistedLux = -1f;
    private long lastLuxPersistElapsed;
    private long lastDiagnosticPersistElapsed;
    private String lastDiagnosticSignature = "";
    private String lastDecisionSignature = "";
    private long lastDecisionPersistElapsed;
    private ProtectionSamplingController.Mode registeredSamplingMode;
    private int pendingExternalRaw = -1;
    private long pendingExternalSinceElapsed = -1L;
    private long occlusionSinceElapsed = -1L;

    public AutoBrightnessManager(Context context) {
        appContext = context.getApplicationContext();
        sensorManager = (SensorManager) appContext.getSystemService(Context.SENSOR_SERVICE);
        lightSensor = sensorManager == null ? null : sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT);
        transitionEngine = new ProtectionTransitionEngine(appContext, handler);
        lastPersistedLux = prefs(appContext).getFloat(KEY_LAST_LUX, -1f);
        lastRamLux = lastPersistedLux;
        if (lastPersistedLux >= 0f) liveLux = lastPersistedLux;
        BrightnessLevels.getSystemRaw(
                appContext,
                ProtectionCurveEngine.getTargetRaw(Math.max(0f, lastPersistedLux)));
        saveSensorAvailable(appContext, lightSensor != null);
        samplingController.reset(SystemClock.elapsedRealtime());
    }

    public boolean start() {
        if (!isAutoEnabled(appContext)) {
            saveMode(appContext, Mode.OFF);
            ProtectionBatteryStats.setPowerState(appContext, ProtectionPowerState.OFF, "START_DISABLED");
            return false;
        }
        if (sensorManager == null || lightSensor == null) {
            markUnavailable(appContext);
            return false;
        }
        if (!BrightnessLevels.captureAndForceManualMode(appContext)) {
            prefs(appContext).edit()
                    .putBoolean(KEY_AUTO_ENABLED, false)
                    .putString(KEY_AUTO_MODE, Mode.OFF.name())
                    .apply();
            BrightnessLogManager.appendSnapshot(appContext, "WRITE_SETTINGS_UNAVAILABLE", getBestLux());
            return false;
        }
        if (!screenAwake) {
            ProtectionBatteryStats.setPowerState(
                    appContext, ProtectionPowerState.SCREEN_OFF_SLEEP, "START_SCREEN_OFF");
            return true;
        }
        if (registered) {
            ProtectionBatteryStats.setPowerState(
                    appContext, ProtectionPowerState.ACTIVE_SCREEN_ON, "START_ALREADY_RUNNING");
            return true;
        }
        long now = SystemClock.elapsedRealtime();
        samplingController.onScreenWake(now);
        ambientController.reset();
        decisionEngine.reset();
        return registerSensor(samplingController.getMode(), "PROTECTION_SENSOR_REGISTERED");
    }

    public void stop() {
        transitionEngine.cancel();
        decisionEngine.reset();
        ambientController.reset();
        unregisterSensor("PROTECTION_SENSOR_UNREGISTERED");
        ProtectionBatteryStats.flush(appContext);
    }

    public void enterScreenOffSleep(String event) {
        screenAwake = false;
        transitionEngine.cancel();
        decisionEngine.reset();
        ambientController.reset();
        samplingController.onScreenOff(SystemClock.elapsedRealtime());
        unregisterSensor("PROTECTION_SENSOR_SLEEP_SCREEN_OFF");
        clearPendingExternal();
        saveMode(appContext, isAutoEnabled(appContext) ? Mode.PROTECTING : Mode.OFF);
        ProtectionBatteryStats.recordScreenOff(appContext);
        ProtectionBatteryStats.setPowerState(appContext, ProtectionPowerState.SCREEN_OFF_SLEEP, event);
        BrightnessLogManager.appendSnapshot(appContext, event, getBestLux());
    }

    public void forceAutoReevaluate(String event) {
        clearUserHold(appContext);
        clearPendingExternal();
        transitionEngine.cancel();
        decisionEngine.reset();
        ambientController.reset();
        occlusionSinceElapsed = -1L;
        screenAwake = true;
        saveMode(appContext, Mode.PROTECTING);
        BrightnessLevels.captureAndForceManualMode(appContext);
        samplingController.onScreenWake(SystemClock.elapsedRealtime());
        registerSensor(samplingController.getMode(), "PROTECTION_SENSOR_REGISTERED_FORCE");
        ProtectionBatteryStats.setPowerState(appContext, ProtectionPowerState.RECOVERY_WAKE, event);
        BrightnessLogManager.appendSnapshot(appContext, event, getBestLux());
    }

    public void resumeProtection(String event) {
        forceAutoReevaluate(event);
    }

    public void onScreenWake(String event) {
        screenAwake = true;
        transitionEngine.cancel();
        decisionEngine.reset();
        ambientController.reset();
        clearPendingExternal();
        occlusionSinceElapsed = -1L;
        BrightnessLevels.captureAndForceManualMode(appContext);
        ProtectionBatteryStats.recordScreenOn(appContext);
        ProtectionBatteryStats.setPowerState(appContext, ProtectionPowerState.RECOVERY_WAKE, event);
        samplingController.onScreenWake(SystemClock.elapsedRealtime());
        registerSensor(samplingController.getMode(), "PROTECTION_SENSOR_REGISTERED_SCREEN_ON");
        BrightnessLogManager.appendSnapshot(appContext, event, getBestLux());
    }

    /** Called by the service's SCREEN_BRIGHTNESS ContentObserver. */
    public boolean onSystemBrightnessChanged(String event) {
        if (!isAutoEnabled(appContext)) return false;
        int currentRaw = BrightnessLevels.getSystemRaw(appContext, -1);
        if (currentRaw < 0 || BrightnessLevels.isRecentAppWrite(appContext, currentRaw)) return false;

        int lastAutoRaw = prefs(appContext).getInt(KEY_LAST_AUTO_RAW, -1);
        if (lastAutoRaw >= 0 && Math.abs(currentRaw - lastAutoRaw) < USER_CHANGE_MIN_RAW) {
            clearPendingExternal();
            return false;
        }

        long now = SystemClock.elapsedRealtime();
        if (pendingExternalRaw != currentRaw) {
            pendingExternalRaw = currentRaw;
            pendingExternalSinceElapsed = now;
        }
        transitionEngine.cancel();
        saveMode(appContext, Mode.USER_HOLD);
        applySamplingMode(
                samplingController.onAmbientResult(
                        now,
                        ProtectionAmbientController.Action.HOLD,
                        "USER_HOLD_PENDING",
                        true),
                "USER_HOLD_PENDING");
        BrightnessLogManager.logSnapshotIfChanged(
                appContext,
                event + "_CANDIDATE_RAW_" + currentRaw,
                getBestLux());
        return true;
    }

    public void confirmPendingExternalBrightnessChange(String event) {
        if (!isAutoEnabled(appContext) || pendingExternalRaw < 0) return;
        int currentRaw = BrightnessLevels.getSystemRaw(appContext, -1);
        long now = SystemClock.elapsedRealtime();
        if (currentRaw != pendingExternalRaw) {
            pendingExternalRaw = currentRaw;
            pendingExternalSinceElapsed = now;
            return;
        }
        if (now - pendingExternalSinceElapsed < USER_INTENT_STABLE_MS) return;
        recordUserHold(appContext, currentRaw, getBestLux(), event + "_RAW_" + currentRaw);
        clearPendingExternal();
    }

    public boolean isScreenOffSleep() {
        return !screenAwake;
    }

    /** Cached lux is diagnostic-only. Recovery always waits for fresh sensor history. */
    public void evaluateLastLux(String event) {
        BrightnessLogManager.logSnapshotIfChanged(
                appContext, event + "_WAIT_FRESH_SENSOR", getLastLux(appContext));
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event == null || event.sensor == null
                || event.sensor.getType() != Sensor.TYPE_LIGHT
                || event.values.length == 0 || !screenAwake) {
            return;
        }

        long now = event.timestamp > 0L
                ? event.timestamp / 1_000_000L
                : SystemClock.elapsedRealtime();
        if (!samplingController.shouldProcessSample(now)) {
            ProtectionBatteryStats.recordSensorSampleDropped(appContext);
            return;
        }
        ProtectionBatteryStats.recordSensorSample(appContext);
        float sensorLux = clampLux(event.values[0]);
        float guardedLux = applyOcclusionGuard(sensorLux, now);

        int currentRaw = BrightnessLevels.getCachedSystemRaw(
                appContext, ProtectionCurveEngine.getTargetRaw(Math.max(0f, guardedLux)));
        ProtectionAmbientController.Result result =
                ambientController.onLuxSample(now, guardedLux, currentRaw);
        persistAmbientDiagnostics(result, now, result.action != ProtectionAmbientController.Action.HOLD);

        float observedLux = result.fastLux >= 0f ? result.fastLux : guardedLux;
        boolean holdConsumedSample = handleUserHoldObservation(observedLux, now);
        if (pendingExternalRaw >= 0 || holdConsumedSample) {
            boolean holdStillActive = pendingExternalRaw >= 0
                    || getUserHoldRemainingMs(appContext) > 0L;
            applySamplingMode(
                    samplingController.onAmbientResult(
                            now, result.action, result.reason, holdStillActive),
                    holdStillActive ? "USER_HOLD_ECO" : "USER_HOLD_RELEASE");
            return;
        }

        lastRamLux = result.ambientValid ? result.ambientLux : observedLux;
        liveLux = lastRamLux;
        ProtectionSamplingController.Mode desiredMode = samplingController.onAmbientResult(
                now, result.action, result.reason, false);

        if (result.action == ProtectionAmbientController.Action.SUNLIGHT_RESCUE) {
            applyIntermediate(result, currentRaw, true);
        } else if (result.action == ProtectionAmbientController.Action.DARK_SETTLE) {
            applyIntermediate(result, currentRaw, false);
        } else if (result.ambientChanged()) {
            lastRamLux = result.ambientLux;
            liveLux = lastRamLux;
            persistLuxIfNeeded(result.ambientLux, now, lastPersistedLux < 0f);
            evaluateConfirmedAmbient(result.ambientLux, "AMBIENT_" + result.action.name());
        } else {
            ProtectionBatteryStats.recordThrottledEvaluation(appContext);
        }
        applySamplingMode(desiredMode, result.reason);
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // Many phone light sensors report changing accuracy without a useful semantic.
        // The estimator relies on temporal consistency rather than this callback.
    }

    private float applyOcclusionGuard(float sensorLux, long now) {
        float reference = lastRamLux >= 0f ? lastRamLux : lastPersistedLux;
        boolean abruptCover = reference >= OCCLUSION_PREVIOUS_MIN_LUX
                && sensorLux <= OCCLUSION_SAMPLE_MAX_LUX;
        if (!abruptCover) {
            occlusionSinceElapsed = -1L;
            return sensorLux;
        }
        if (occlusionSinceElapsed < 0L) occlusionSinceElapsed = now;
        if (now - occlusionSinceElapsed < OCCLUSION_GUARD_MS) {
            return reference;
        }
        return sensorLux;
    }

    private boolean handleUserHoldObservation(float observedLux, long nowElapsed) {
        long remaining = getUserHoldRemainingMs(appContext);
        if (remaining <= 0L) {
            if (getSavedMode(appContext) == Mode.USER_HOLD) {
                clearUserHold(appContext);
                saveMode(appContext, Mode.PROTECTING);
                ambientController.reset();
                samplingController.onScreenWake(nowElapsed);
                BrightnessLogManager.appendSnapshot(
                        appContext, "USER_HOLD_EXPIRED", observedLux);
                return true;
            }
            return false;
        }

        float holdLux = prefs(appContext).getFloat(KEY_USER_HOLD_LUX, -1f);
        if (shouldReleaseUserHold(observedLux, holdLux)) {
            clearUserHold(appContext);
            saveMode(appContext, Mode.PROTECTING);
            ambientController.reset();
            decisionEngine.reset();
            samplingController.onScreenWake(nowElapsed);
            BrightnessLogManager.appendSnapshot(
                    appContext, "USER_HOLD_RELEASED_BY_ENVIRONMENT", observedLux);
            return true;
        }

        lastRamLux = observedLux;
        liveLux = observedLux;
        ProtectionBatteryStats.setPowerState(
                appContext, ProtectionPowerState.USER_HOLD_LOW_POWER, "USER_HOLD_ACTIVE");
        return true;
    }

    private boolean shouldReleaseUserHold(float currentLux, float holdLux) {
        if (holdLux < 0f || currentLux < 0f) return false;
        float absolute = Math.abs(currentLux - holdLux);
        if (absolute < 30f) return false;
        double logDelta = Math.abs(Math.log1p(currentLux) - Math.log1p(holdLux));
        return logDelta >= Math.log(2.5d);
    }

    private void evaluateConfirmedAmbient(float ambientLux, String event) {
        if (!screenAwake) return;
        ProtectionBatteryStats.recordEvaluation(appContext);
        ProtectionBatteryStats.setPowerState(
                appContext, ProtectionPowerState.ACTIVE_SCREEN_ON, event);

        int currentRaw = BrightnessLevels.getCachedSystemRaw(
                appContext, ProtectionCurveEngine.getTargetRaw(ambientLux));
        BrightnessDecisionEngine.Decision decision =
                decisionEngine.decideConfirmedAmbient(ambientLux, currentRaw, false);
        saveDecision(decision, event, SystemClock.elapsedRealtime());

        if (decision.shouldApply()) {
            if (Math.abs(currentRaw - decision.targetRaw) <= RAW_HYSTERESIS) {
                saveLastAutoRaw(appContext, currentRaw);
                ProtectionBatteryStats.recordBrightnessWriteSkip(appContext);
                return;
            }
            saveMode(appContext, Mode.PROTECTING);
            saveLastAutoRaw(appContext, decision.targetRaw);
            transitionEngine.transitionToRaw(decision.targetRaw, event + "_" + decision.reason);
        } else if (decision.isNoop()) {
            saveLastAutoRaw(appContext, currentRaw);
            ProtectionBatteryStats.recordBrightnessWriteSkip(appContext);
        }
    }

    private void applyIntermediate(
            ProtectionAmbientController.Result result,
            int currentRaw,
            boolean sunlight) {
        int intermediate = result.intermediateRaw;
        if (intermediate < 0) return;
        saveMode(appContext, Mode.PROTECTING);
        saveLastAutoRaw(appContext, intermediate);
        if (sunlight && intermediate > currentRaw) {
            transitionEngine.recoverToReadableRaw(
                    intermediate, result.finalTargetRaw, result.reason);
        } else if (!sunlight && intermediate < currentRaw) {
            transitionEngine.settleToComfortableDarkRaw(
                    intermediate, result.finalTargetRaw, result.reason);
        }
    }

    private boolean registerSensor(ProtectionSamplingController.Mode mode, String event) {
        if (!screenAwake || mode == ProtectionSamplingController.Mode.SCREEN_OFF_SLEEP) return true;
        if (sensorManager == null || lightSensor == null) {
            markUnavailable(appContext);
            return false;
        }
        if (registered) {
            sensorManager.unregisterListener(this);
            ProtectionBatteryStats.recordSensorUnregistered(appContext);
        }
        registered = false;
        registeredSamplingMode = null;
        try {
            registered = sensorManager.registerListener(
                    this,
                    lightSensor,
                    mode.samplingPeriodUs,
                    mode.maxReportLatencyUs,
                    handler);
        } catch (Throwable ignored) {
            registered = sensorManager.registerListener(
                    this, lightSensor, SensorManager.SENSOR_DELAY_NORMAL, handler);
        }
        if (registered) {
            ProtectionBatteryStats.recordSensorRegistered(appContext);
            ProtectionBatteryStats.setPowerState(
                    appContext, ProtectionPowerState.ACTIVE_SCREEN_ON, event);
            registeredSamplingMode = mode;
            prefs(appContext).edit().putString(KEY_SAMPLING_MODE, mode.name()).apply();
        }
        BrightnessLogManager.appendSnapshot(
                appContext,
                registered ? event + "_" + mode.name() : "SENSOR_REGISTER_FAILED",
                getBestLux());
        return registered;
    }

    private void unregisterSensor(String event) {
        if (registered && sensorManager != null) {
            try {
                sensorManager.unregisterListener(this);
            } catch (Throwable ignored) {
            }
            ProtectionBatteryStats.recordSensorUnregistered(appContext);
            BrightnessLogManager.appendSnapshot(appContext, event, getBestLux());
        }
        registered = false;
        registeredSamplingMode = null;
    }

    private void applySamplingMode(ProtectionSamplingController.Mode desired, String reason) {
        if (!screenAwake || desired == ProtectionSamplingController.Mode.SCREEN_OFF_SLEEP) return;
        if (registered && desired == registeredSamplingMode) return;
        registerSensor(desired, "SAMPLING_" + desired.name() + "_" + safe(reason));
    }

    private void persistLuxIfNeeded(float lux, long nowElapsed, boolean force) {
        boolean first = lastPersistedLux < 0f;
        boolean elapsed = nowElapsed - lastLuxPersistElapsed >= LUX_PERSIST_INTERVAL_MS;
        float delta = first ? Float.MAX_VALUE : Math.abs(lux - lastPersistedLux);
        boolean changed = first
                || delta >= LUX_PERSIST_ABSOLUTE_DELTA
                || delta / Math.max(1f, lastPersistedLux) >= LUX_PERSIST_RELATIVE_DELTA;
        if (force || (elapsed && changed)) {
            prefs(appContext).edit().putFloat(KEY_LAST_LUX, lux).apply();
            lastPersistedLux = lux;
            lastLuxPersistElapsed = nowElapsed;
            ProtectionBatteryStats.recordLuxPersisted(appContext);
        }
    }

    private void persistAmbientDiagnostics(
            ProtectionAmbientController.Result result,
            long nowElapsed,
            boolean force) {
        if (lastDiagnosticPersistElapsed > 0L
                && nowElapsed - lastDiagnosticPersistElapsed < DIAGNOSTIC_PERSIST_INTERVAL_MS) {
            return;
        }
        String signature = result.action.name() + "|" + result.reason
                + "|" + Math.round(result.ambientLux)
                + "|" + Math.round(result.fastLux)
                + "|" + Math.round(result.slowLux);
        if (!force && signature.equals(lastDiagnosticSignature)) return;
        lastDiagnosticSignature = signature;
        lastDiagnosticPersistElapsed = nowElapsed;
        prefs(appContext).edit()
                .putString(KEY_AMBIENT_ACTION, result.action.name())
                .putString(KEY_AMBIENT_REASON, result.reason)
                .putFloat(KEY_AMBIENT_FAST_LUX, result.fastLux)
                .putFloat(KEY_AMBIENT_SLOW_LUX, result.slowLux)
                .putFloat(KEY_AMBIENT_BRIGHT_THRESHOLD, result.brighteningThreshold)
                .putFloat(KEY_AMBIENT_DARK_THRESHOLD, result.darkeningThreshold)
                .apply();
    }

    private void saveDecision(
            BrightnessDecisionEngine.Decision decision,
            String event,
            long nowElapsed) {
        String signature = decision.action.name() + "|" + decision.reason + "|" + decision.targetRaw;
        if (signature.equals(lastDecisionSignature)
                && nowElapsed - lastDecisionPersistElapsed < 10_000L) {
            return;
        }
        lastDecisionSignature = signature;
        lastDecisionPersistElapsed = nowElapsed;
        prefs(appContext).edit()
                .putLong(KEY_LAST_DECISION_AT, System.currentTimeMillis())
                .putString(KEY_LAST_DECISION_ACTION, decision.action.name())
                .putString(KEY_LAST_DECISION_REASON, event + ":" + decision.reason)
                .putString(KEY_LAST_DECISION_TARGET_REASON, "ACCEPTED_AMBIENT_STATE")
                .putInt(KEY_LAST_DECISION_TARGET_PERCENT,
                        ProtectionCurveEngine.nearestProtectionPercentForRaw(decision.targetRaw))
                .putInt(KEY_LAST_DECISION_TARGET_RAW, decision.targetRaw)
                .putLong(KEY_LAST_DECISION_STABLE_MS, 0L)
                .putLong(KEY_LAST_DECISION_WAIT_MS, decision.waitMs)
                .putFloat(KEY_LAST_DECISION_CONFIDENCE, decision.confidence)
                .apply();
    }

    private float getBestLux() {
        return lastRamLux >= 0f ? lastRamLux : getLastLux(appContext);
    }

    private float clampLux(float lux) {
        if (Float.isNaN(lux) || Float.isInfinite(lux) || lux < 0f) return 0f;
        return Math.min(MAX_LUX, lux);
    }

    private void clearPendingExternal() {
        pendingExternalRaw = -1;
        pendingExternalSinceElapsed = -1L;
    }

    public static boolean hasLightSensor(Context context) {
        SensorManager manager = (SensorManager) context.getApplicationContext()
                .getSystemService(Context.SENSOR_SERVICE);
        return manager != null && manager.getDefaultSensor(Sensor.TYPE_LIGHT) != null;
    }

    public static void setAutoEnabled(Context context, boolean enabled) {
        Context app = context.getApplicationContext();
        boolean wasEnabled = isAutoEnabled(app);

        if (enabled && !BrightnessLevels.captureAndForceManualMode(app)) {
            prefs(app).edit()
                    .putBoolean(KEY_AUTO_ENABLED, false)
                    .putString(KEY_AUTO_MODE, Mode.OFF.name())
                    .apply();
            ProtectionBatteryStats.setPowerState(
                    app, ProtectionPowerState.OFF, "WRITE_SETTINGS_UNAVAILABLE");
            return;
        }

        if (wasEnabled == enabled) {
            if (enabled && getSavedMode(app) == Mode.OFF) saveMode(app, Mode.PROTECTING);
            return;
        }

        if (enabled) {
            ProtectionBatteryStats.reset(app);
        } else {
            ProtectionBatteryStats.flush(app);
            BrightnessLevels.restorePreviousBrightnessMode(app);
        }
        prefs(app).edit()
                .putBoolean(KEY_AUTO_ENABLED, enabled)
                .putString(KEY_AUTO_MODE, enabled ? Mode.PROTECTING.name() : Mode.OFF.name())
                .putLong(KEY_USER_HOLD_UNTIL, 0L)
                .putFloat(KEY_USER_HOLD_LUX, -1f)
                .remove(KEY_USER_HOLD_RAW)
                .apply();
        ProtectionBatteryStats.setPowerState(
                app,
                enabled ? ProtectionPowerState.RECOVERY_WAKE : ProtectionPowerState.OFF,
                enabled ? "PROTECTION_STATE_ON" : "PROTECTION_STATE_OFF");
        BrightnessLogManager.appendSnapshot(
                app,
                enabled ? "PROTECTION_STATE_ON" : "PROTECTION_STATE_OFF",
                getLastLux(app));
    }

    public static boolean isAutoEnabled(Context context) {
        return prefs(context).getBoolean(KEY_AUTO_ENABLED, false);
    }

    public static void recordManualOverride(Context context) {
        recordUserHold(
                context,
                BrightnessLevels.getSystemRaw(context, -1),
                getLastLux(context),
                "USER_HOLD_RECORDED");
    }

    public static void recordUserHold(Context context, int raw, String event) {
        recordUserHold(context, raw, getLastLux(context), event);
    }

    private static void recordUserHold(
            Context context, int raw, float lux, String event) {
        long now = System.currentTimeMillis();
        long duration = lux >= 0f && lux <= 6f ? USER_HOLD_NIGHT_MS : USER_HOLD_NORMAL_MS;
        prefs(context).edit()
                .putLong(KEY_USER_HOLD_UNTIL, now + duration)
                .putLong(KEY_USER_HOLD_AT, now)
                .putFloat(KEY_USER_HOLD_LUX, lux)
                .putInt(KEY_USER_HOLD_RAW, raw)
                .putString(KEY_AUTO_MODE, Mode.USER_HOLD.name())
                .apply();
        BrightnessLevels.saveCurrentPercent(context, BrightnessLevels.getPercentForRaw(raw));
        ProtectionBatteryStats.setPowerState(
                context, ProtectionPowerState.USER_HOLD_LOW_POWER, event);
        BrightnessLogManager.appendSnapshot(context, event, lux);
    }

    public static long getCooldownRemainingMs(Context context) {
        return getUserHoldRemainingMs(context);
    }

    public static long getUserHoldRemainingMs(Context context) {
        return Math.max(0L,
                prefs(context).getLong(KEY_USER_HOLD_UNTIL, 0L) - System.currentTimeMillis());
    }

    public static int getUserHoldRaw(Context context) {
        return prefs(context).getInt(KEY_USER_HOLD_RAW, -1);
    }

    public static float getLastLux(Context context) {
        float current = liveLux;
        return current >= 0f ? current : prefs(context).getFloat(KEY_LAST_LUX, -1f);
    }

    public static void markUnavailable(Context context) {
        BrightnessLevels.restorePreviousBrightnessMode(context);
        prefs(context).edit()
                .putBoolean(KEY_AUTO_ENABLED, false)
                .putBoolean(KEY_SENSOR_AVAILABLE, false)
                .putString(KEY_AUTO_MODE, Mode.UNAVAILABLE.name())
                .apply();
        ProtectionBatteryStats.setPowerState(
                context, ProtectionPowerState.OFF, "PROTECTION_UNAVAILABLE");
        BrightnessLogManager.appendSnapshot(
                context, "PROTECTION_UNAVAILABLE", getLastLux(context));
    }

    public static Mode getSavedMode(Context context) {
        String value = prefs(context).getString(KEY_AUTO_MODE, Mode.OFF.name());
        if ("MANUAL_OVERRIDE".equals(value)) return Mode.USER_HOLD;
        try {
            return Mode.valueOf(value);
        } catch (Throwable ignored) {
            return Mode.OFF;
        }
    }

    public static String getStatusText(Context context) {
        float lux = getLastLux(context);
        int raw = BrightnessLevels.getSystemRaw(context, -1);
        return context.getString(
                R.string.protection_status_text,
                onOffText(context, isAutoEnabled(context)),
                getDisplayMode(context, getSavedMode(context)),
                ProtectionCurveEngine.getProfileName(context, lux),
                formatLux(context, lux),
                raw,
                prefs(context).getString(
                        KEY_SAMPLING_MODE, context.getString(R.string.value_unknown)));
    }

    public static String getDiagnosticText(Context context) {
        SharedPreferences state = prefs(context);
        float lux = getLastLux(context);
        long decisionAt = state.getLong(KEY_LAST_DECISION_AT, 0L);
        long decisionAge = decisionAt <= 0L
                ? -1L : Math.max(0L, System.currentTimeMillis() - decisionAt);
        String none = context.getString(R.string.value_none);
        String unknown = context.getString(R.string.value_unknown);
        String decisionAgeText = decisionAge < 0L
                ? context.getString(R.string.value_never)
                : context.getString(R.string.duration_ms, decisionAge);
        return context.getString(
                R.string.auto_diagnostic_text,
                onOffText(context, isAutoEnabled(context)),
                getDisplayMode(context, getSavedMode(context)),
                getPowerStateText(context, ProtectionBatteryStats.getPowerState(context)),
                state.getString(KEY_SAMPLING_MODE, unknown),
                formatLux(context, lux),
                ProtectionCurveEngine.getProfileName(context, lux),
                formatLux(context, state.getFloat(KEY_AMBIENT_FAST_LUX, -1f)),
                formatLux(context, state.getFloat(KEY_AMBIENT_SLOW_LUX, -1f)),
                formatLux(context, state.getFloat(KEY_AMBIENT_DARK_THRESHOLD, -1f)),
                formatLux(context, state.getFloat(KEY_AMBIENT_BRIGHT_THRESHOLD, -1f)),
                state.getString(KEY_AMBIENT_ACTION, none),
                state.getString(KEY_AMBIENT_REASON, none),
                BrightnessLevels.getCurrentPercent(context),
                BrightnessLevels.getSystemRaw(context, -1),
                state.getInt(KEY_LAST_DECISION_TARGET_PERCENT, -1),
                state.getInt(KEY_LAST_DECISION_TARGET_RAW, -1),
                state.getString(KEY_LAST_DECISION_ACTION, none),
                state.getString(KEY_LAST_DECISION_REASON, none),
                Math.round(state.getFloat(KEY_LAST_DECISION_CONFIDENCE, 0f) * 100f),
                decisionAgeText,
                getUserHoldRemainingMs(context) / 1000L)
                + "\n\n" + ProtectionBatteryStats.getDiagnosticText(context);
    }

    public static String getDisplayMode(Mode mode) {
        if (mode == Mode.PROTECTING) return "Protecting";
        if (mode == Mode.USER_HOLD) return "Holding your brightness";
        if (mode == Mode.UNAVAILABLE) return "Unavailable";
        return "Off";
    }

    public static String getDisplayMode(Context context, Mode mode) {
        if (mode == Mode.PROTECTING) return context.getString(R.string.mode_protecting);
        if (mode == Mode.USER_HOLD) return context.getString(R.string.mode_user_hold);
        if (mode == Mode.UNAVAILABLE) return context.getString(R.string.mode_unavailable);
        return context.getString(R.string.mode_off);
    }

    public static String getPowerStateText(Context context, ProtectionPowerState state) {
        if (state == ProtectionPowerState.ACTIVE_SCREEN_ON) {
            return context.getString(R.string.power_active);
        }
        if (state == ProtectionPowerState.SCREEN_OFF_SLEEP) {
            return context.getString(R.string.power_sleeping);
        }
        if (state == ProtectionPowerState.USER_HOLD_LOW_POWER) {
            return context.getString(R.string.power_user_hold);
        }
        if (state == ProtectionPowerState.RECOVERY_WAKE) {
            return context.getString(R.string.power_recovery);
        }
        return context.getString(R.string.power_off);
    }

    private static void clearUserHold(Context context) {
        prefs(context).edit()
                .putLong(KEY_USER_HOLD_UNTIL, 0L)
                .putFloat(KEY_USER_HOLD_LUX, -1f)
                .remove(KEY_USER_HOLD_RAW)
                .remove(KEY_USER_HOLD_AT)
                .apply();
    }

    private static void saveLastAutoRaw(Context context, int raw) {
        SharedPreferences state = prefs(context);
        int previousRaw = state.getInt(KEY_LAST_AUTO_RAW, -1);
        long previousAt = state.getLong(KEY_LAST_AUTO_AT, 0L);
        long now = System.currentTimeMillis();
        if (previousRaw == raw && now - previousAt < 60_000L) return;
        state.edit()
                .putInt(KEY_LAST_AUTO_RAW, raw)
                .putLong(KEY_LAST_AUTO_AT, now)
                .apply();
    }

    private static void saveSensorAvailable(Context context, boolean available) {
        SharedPreferences state = prefs(context);
        if (state.getBoolean(KEY_SENSOR_AVAILABLE, !available) == available) return;
        state.edit().putBoolean(KEY_SENSOR_AVAILABLE, available).apply();
    }

    private static void saveMode(Context context, Mode mode) {
        SharedPreferences state = prefs(context);
        if (mode.name().equals(state.getString(KEY_AUTO_MODE, ""))) return;
        state.edit().putString(KEY_AUTO_MODE, mode.name()).apply();
    }

    private static String formatLux(Context context, float lux) {
        if (Float.isNaN(lux) || Float.isInfinite(lux) || lux < 0f) {
            return context.getString(R.string.value_unknown);
        }
        return String.format(Locale.US, "%.1f lx", lux);
    }

    private static String onOffText(Context context, boolean on) {
        return context.getString(on ? R.string.value_on : R.string.value_off);
    }

    private static String safe(String text) {
        if (text == null || text.length() == 0) return "NO_REASON";
        return text.replace(' ', '_');
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(BrightnessLevels.PREFS, Context.MODE_PRIVATE);
    }
}
