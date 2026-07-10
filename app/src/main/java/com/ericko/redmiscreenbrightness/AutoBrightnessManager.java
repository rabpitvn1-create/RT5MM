package com.ericko.redmiscreenbrightness;

import android.content.Context;
import android.content.SharedPreferences;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.Looper;

import java.util.Locale;

public class AutoBrightnessManager implements SensorEventListener {
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
    private static final String KEY_IGNORE_EXTERNAL_UNTIL = "auto_brightness_ignore_external_until";
    private static final String KEY_EXTERNAL_CANDIDATE_RAW = "auto_brightness_external_candidate_raw";
    private static final String KEY_EXTERNAL_CANDIDATE_SINCE = "auto_brightness_external_candidate_since";
    private static final String KEY_LAST_DECISION_AT = "protection_last_decision_at";
    private static final String KEY_LAST_DECISION_ACTION = "protection_last_decision_action";
    private static final String KEY_LAST_DECISION_REASON = "protection_last_decision_reason";
    private static final String KEY_LAST_DECISION_TARGET_REASON = "protection_last_decision_target_reason";
    private static final String KEY_LAST_DECISION_TARGET_PERCENT = "protection_last_decision_target_percent";
    private static final String KEY_LAST_DECISION_TARGET_RAW = "protection_last_decision_target_raw";
    private static final String KEY_LAST_DECISION_STABLE_MS = "protection_last_decision_stable_ms";
    private static final String KEY_LAST_DECISION_WAIT_MS = "protection_last_decision_wait_ms";
    private static final String KEY_LAST_DECISION_CONFIDENCE = "protection_last_decision_confidence";

    private static final float MAX_LUX = 120000f;
    private static final int SAMPLE_COUNT = 5;
    private static final int RAW_CHANGE_TOLERANCE = 0;
    private static final int USER_CHANGE_MIN_RAW = 6;
    private static final long APP_WRITE_GRACE_MS = 12000L;
    private static final long USER_INTENT_STABLE_MS = 3000L;
    private static final long USER_HOLD_NORMAL_MS = 15L * 60L * 1000L;
    private static final long USER_HOLD_NIGHT_MS = 8L * 60L * 1000L;
    public static final long USER_HOLD_MS = USER_HOLD_NORMAL_MS;
    private static final long LUX_PERSIST_MS = 30000L;
    private static final float LUX_PERSIST_RELATIVE_DELTA = 0.12f;
    private static final float LUX_PERSIST_ABSOLUTE_DELTA = 3f;
    private static final float USER_HOLD_RELEASE_PCT = 120f;
    private static final float USER_HOLD_RELEASE_MIN_LUX = 60f;
    private static final long DECISION_PERSIST_THROTTLE_MS = 3000L;

    public enum Mode {
        OFF, PROTECTING, USER_HOLD, UNAVAILABLE
    }

    private final Context appContext;
    private final SensorManager sensorManager;
    private final Sensor lightSensor;
    private final ProtectionPolicy protectionPolicy = new ProtectionPolicy();
    private final ProtectionTransitionEngine transitionEngine;
    private final BrightnessDecisionEngine decisionEngine = new BrightnessDecisionEngine();
    private final float[] samples = new float[SAMPLE_COUNT];
    private int sampleCount = 0;
    private int sampleIndex = 0;
    private boolean registered = false;
    private boolean screenAwake = true;
    private float lastRamLux = -1f;
    private float lastPersistedLux = -1f;
    private long lastLuxPersistAt = 0L;
    private long lastDecisionPersistAt = 0L;
    private String lastDecisionSignature = "";

    public AutoBrightnessManager(Context context) {
        appContext = context.getApplicationContext();
        sensorManager = (SensorManager) appContext.getSystemService(Context.SENSOR_SERVICE);
        lightSensor = sensorManager == null ? null : sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT);
        transitionEngine = new ProtectionTransitionEngine(appContext, new Handler(Looper.getMainLooper()));
        lastPersistedLux = getLastLux(appContext);
        lastRamLux = lastPersistedLux;
        saveSensorAvailable(appContext, lightSensor != null);
    }

    public boolean start() {
        if (!isAutoEnabled(appContext)) {
            saveMode(appContext, Mode.OFF);
            ProtectionBatteryStats.setPowerState(appContext, ProtectionPowerState.OFF, "START_DISABLED");
            return false;
        }
        BrightnessLevels.captureAndForceManualMode(appContext);
        if (sensorManager == null || lightSensor == null) {
            markUnavailable(appContext);
            return false;
        }
        if (!screenAwake) {
            ProtectionBatteryStats.setPowerState(appContext, ProtectionPowerState.SCREEN_OFF_SLEEP, "START_WHILE_SCREEN_OFF");
            return true;
        }
        return startSensor("PROTECTION_SENSOR_REGISTERED");
    }

    public void stop() {
        transitionEngine.cancel();
        decisionEngine.reset();
        stopSensor("PROTECTION_SENSOR_UNREGISTERED");
        ProtectionBatteryStats.flush(appContext);
    }

    public void enterScreenOffSleep(String event) {
        screenAwake = false;
        transitionEngine.cancel();
        decisionEngine.reset();
        stopSensor("PROTECTION_SENSOR_SLEEP_SCREEN_OFF");
        saveMode(appContext, isAutoEnabled(appContext) ? Mode.PROTECTING : Mode.OFF);
        clearExternalCandidate(appContext);
        ProtectionBatteryStats.recordScreenOff(appContext);
        ProtectionBatteryStats.setPowerState(appContext, ProtectionPowerState.SCREEN_OFF_SLEEP, event);
        BrightnessLogManager.appendSnapshot(appContext, event, getBestLux());
    }

    public void forceAutoReevaluate(String event) {
        transitionEngine.cancel();
        decisionEngine.reset();
        BrightnessLevels.captureAndForceManualMode(appContext);
        clearUserHold(appContext);
        clearAutoWriteTracking(appContext);
        clearExternalCandidate(appContext);
        beginAppWriteGrace(appContext);
        saveMode(appContext, Mode.PROTECTING);
        screenAwake = true;
        startSensor("PROTECTION_SENSOR_REGISTERED_FORCE");
        ProtectionBatteryStats.setPowerState(appContext, ProtectionPowerState.RECOVERY_WAKE, event);
        BrightnessLogManager.appendSnapshot(appContext, event, getBestLux());
        evaluateLastLux(event);
    }

    public void resumeProtection(String event) {
        forceAutoReevaluate(event);
    }

    public void onScreenWake(String event) {
        screenAwake = true;
        decisionEngine.reset();
        BrightnessLevels.captureAndForceManualMode(appContext);
        ProtectionBatteryStats.recordScreenOn(appContext);
        ProtectionBatteryStats.setPowerState(appContext, ProtectionPowerState.RECOVERY_WAKE, event);
        startSensor("PROTECTION_SENSOR_REGISTERED_SCREEN_ON");
        beginAppWriteGrace(appContext);
        clearExternalCandidate(appContext);
        BrightnessLogManager.appendSnapshot(appContext, event, getBestLux());
        evaluateLastLux(event);
    }

    public boolean onSystemBrightnessChanged(String event) {
        if (!isAutoEnabled(appContext)) {
            return false;
        }
        long now = System.currentTimeMillis();
        float lux = getBestLux();
        if (lux < 0f) {
            lux = getLastLux(appContext);
        }
        boolean handled = detectUserBrightnessChange(now, lux);
        if (handled) {
            transitionEngine.cancel();
            decisionEngine.reset();
            ProtectionBatteryStats.setPowerState(appContext, ProtectionPowerState.USER_HOLD_LOW_POWER, event);
        }
        return handled;
    }

    public void confirmPendingExternalBrightnessChange(String event) {
        if (!isAutoEnabled(appContext)) {
            return;
        }
        long now = System.currentTimeMillis();
        float lux = getBestLux();
        if (lux < 0f) {
            lux = getLastLux(appContext);
        }
        if (detectUserBrightnessChange(now, lux)) {
            transitionEngine.cancel();
            decisionEngine.reset();
        }
    }

    public boolean isScreenOffSleep() {
        return !screenAwake;
    }

    public void evaluateLastLux(String event) {
        float lux = getBestLux();
        if (lux >= 0f && !isScreenOffSleep()) {
            evaluateLux(lux, event);
        }
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event == null || event.sensor == null || event.sensor.getType() != Sensor.TYPE_LIGHT || event.values.length == 0 || isScreenOffSleep()) {
            return;
        }

        long now = System.currentTimeMillis();
        ProtectionBatteryStats.recordSensorSample(appContext);
        float avgLux = smooth(clampLux(event.values[0]));
        float trustedLux = protectionPolicy.filterLux(avgLux, now);
        lastRamLux = trustedLux;
        persistLuxIfNeeded(trustedLux, now, false);
        evaluateLux(trustedLux, "PROTECTION_SENSOR_SAMPLE");
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    private void evaluateLux(float avgLux, String event) {
        if (isScreenOffSleep()) {
            ProtectionBatteryStats.recordThrottledEvaluation(appContext);
            return;
        }
        long now = System.currentTimeMillis();
        lastRamLux = avgLux;
        persistLuxIfNeeded(avgLux, now, shouldForceApply(event));
        ProtectionBatteryStats.recordEvaluation(appContext);

        if (isUserHoldActive(appContext, now)) {
            ProtectionBatteryStats.setPowerState(appContext, ProtectionPowerState.USER_HOLD_LOW_POWER, "USER_HOLD_ACTIVE");
            float holdLux = getUserHoldLux(appContext);
            if (!shouldReleaseUserHold(avgLux, holdLux)) {
                saveMode(appContext, Mode.USER_HOLD);
                saveLastDecision(appContext, now, "HOLD", "USER_HOLD_ACTIVE", ProtectionCurveEngine.getTargetRaw(avgLux), 0L, 0f);
                BrightnessLogManager.logSnapshotIfChanged(appContext, event + "_USER_HOLD", avgLux);
                return;
            }
            clearUserHold(appContext);
            clearAutoWriteTracking(appContext);
            clearExternalCandidate(appContext);
            decisionEngine.reset();
            BrightnessLogManager.appendSnapshot(appContext, "USER_HOLD_RELEASED_BY_LUX_CHANGE", avgLux);
        }

        ProtectionBatteryStats.setPowerState(appContext, ProtectionPowerState.ACTIVE_SCREEN_ON, event);
        int fallbackRaw = ProtectionCurveEngine.getTargetRaw(avgLux);
        int currentRaw = BrightnessLevels.getSystemRaw(appContext, fallbackRaw);
        BrightnessDecisionEngine.Decision decision = decisionEngine.decide(avgLux, currentRaw, now, shouldForceApply(event));
        handleDecision(decision, event, avgLux, currentRaw, now);
    }

    private void handleDecision(BrightnessDecisionEngine.Decision decision, String event, float lux, int currentRaw, long now) {
        boolean forcePersist = decision.shouldApply() || decision.shouldRescue() || decision.isNoop() || shouldForceApply(event);
        persistDecisionIfNeeded(decision, now, forcePersist);

        if (decision.shouldRescue()) {
            applySunlightRescue(decision, event, lux, currentRaw);
            return;
        }

        if (decision.shouldApply()) {
            applyConfirmedTarget(decision.targetRaw, event, lux, currentRaw, decision.reason, decision.confidence);
            return;
        }

        if (decision.isNoop()) {
            saveMode(appContext, Mode.PROTECTING);
            if (Math.abs(currentRaw - decision.targetRaw) <= RAW_CHANGE_TOLERANCE) {
                transitionEngine.cancel();
                saveLastAutoRawBaseline(appContext, decision.targetRaw);
                BrightnessLevels.saveCurrentPercent(appContext, ProtectionCurveEngine.nearestProtectionPercentForRaw(decision.targetRaw));
                ProtectionBatteryStats.recordBrightnessWriteSkip(appContext);
            }
            BrightnessLogManager.logSnapshotIfChanged(appContext, decision.reason + "_RAW_" + decision.targetRaw, lux);
            return;
        }

        if (decision.action == BrightnessDecisionEngine.Action.IGNORE_SPIKE || decision.action == BrightnessDecisionEngine.Action.SENSOR_NOISY) {
            transitionEngine.cancel();
        }
        ProtectionBatteryStats.recordThrottledEvaluation(appContext);
        BrightnessLogManager.logSnapshotIfChanged(appContext, decision.reason + "_RAW_" + decision.targetRaw, lux);
    }

    private void applySunlightRescue(BrightnessDecisionEngine.Decision decision, String event, float lux, int currentRaw) {
        if (decision.rescueRaw <= currentRaw) {
            ProtectionBatteryStats.recordBrightnessWriteSkip(appContext);
            BrightnessLogManager.logSnapshotIfChanged(appContext, "SUNLIGHT_RESCUE_SKIP_RAW_" + currentRaw + "_TO_" + decision.rescueRaw, lux);
            return;
        }
        saveLastDecision(appContext, System.currentTimeMillis(), "SUNLIGHT_RESCUE", decision.reason, decision.targetRaw, 0L, decision.confidence);
        saveMode(appContext, Mode.PROTECTING);
        beginAppWriteGrace(appContext);
        saveLastAutoRaw(appContext, decision.rescueRaw);
        transitionEngine.recoverToReadableRaw(decision.rescueRaw, decision.targetRaw, event == null ? "SUNLIGHT_RESCUE" : event + "_" + decision.reason);
    }

    private void applyConfirmedTarget(int targetRaw, String event, float lux, int currentRaw, String reason, float confidence) {
        int targetPercent = ProtectionCurveEngine.nearestProtectionPercentForRaw(targetRaw);
        saveLastDecision(appContext, System.currentTimeMillis(), "APPLY", reason, targetRaw, 0L, confidence);
        saveMode(appContext, Mode.PROTECTING);

        if (Math.abs(currentRaw - targetRaw) <= RAW_CHANGE_TOLERANCE) {
            transitionEngine.cancel();
            saveLastAutoRawBaseline(appContext, targetRaw);
            BrightnessLevels.saveCurrentPercent(appContext, targetPercent);
            ProtectionBatteryStats.recordBrightnessWriteSkip(appContext);
            BrightnessLogManager.logSnapshotIfChanged(appContext, "DECISION_AT_RAW_" + targetRaw, lux);
            return;
        }

        beginAppWriteGrace(appContext);
        saveLastAutoRaw(appContext, targetRaw);
        transitionEngine.transitionToRaw(targetRaw, event == null ? "DECISION" : event + "_" + reason);
    }

    private void persistDecisionIfNeeded(BrightnessDecisionEngine.Decision decision, long now, boolean force) {
        String signature = decision.action.name() + "|" + decision.reason + "|" + decision.targetRaw + "|" + decision.rescueRaw;
        if (!force && signature.equals(lastDecisionSignature) && now - lastDecisionPersistAt < DECISION_PERSIST_THROTTLE_MS) {
            return;
        }
        lastDecisionSignature = signature;
        lastDecisionPersistAt = now;
        saveLastDecision(appContext, now, decision.action.name(), decision.reason, decision.targetRaw, decision.waitMs, decision.confidence);
    }

    private boolean shouldForceApply(String event) {
        return event != null && !"PROTECTION_SENSOR_SAMPLE".equals(event);
    }

    private boolean detectUserBrightnessChange(long now, float avgLux) {
        int lastAutoRaw = getLastAutoRaw(appContext);
        if (lastAutoRaw < 0) {
            return false;
        }
        if (now < getIgnoreExternalUntil(appContext)) {
            return false;
        }
        long lastAutoAt = getLastAutoAt(appContext);
        if (now - lastAutoAt < APP_WRITE_GRACE_MS) {
            return false;
        }

        int currentRaw = BrightnessLevels.getSystemRaw(appContext, lastAutoRaw);
        if (Math.abs(currentRaw - lastAutoRaw) < USER_CHANGE_MIN_RAW) {
            clearExternalCandidate(appContext);
            return false;
        }

        int candidateRaw = getExternalCandidateRaw(appContext);
        long candidateRawSince = getExternalCandidateSince(appContext);
        if (candidateRaw != currentRaw) {
            saveExternalCandidate(appContext, currentRaw, now);
            saveMode(appContext, Mode.USER_HOLD);
            BrightnessLogManager.appendSnapshot(appContext, "USER_HOLD_CANDIDATE_RAW_" + currentRaw + "_LAST_AUTO_RAW_" + lastAutoRaw, avgLux);
            return true;
        }

        saveMode(appContext, Mode.USER_HOLD);
        if (now - candidateRawSince < USER_INTENT_STABLE_MS) {
            BrightnessLogManager.logSnapshotIfChanged(appContext, "USER_HOLD_CANDIDATE_WAITING", avgLux);
            return true;
        }

        recordUserHold(appContext, currentRaw, avgLux, "USER_HOLD_CONFIRMED_RAW_" + currentRaw + "_LAST_AUTO_RAW_" + lastAutoRaw);
        clearExternalCandidate(appContext);
        return true;
    }

    private boolean shouldReleaseUserHold(float currentLux, float holdLux) {
        if (holdLux < 0f) {
            return false;
        }
        float delta = Math.abs(currentLux - holdLux);
        if (delta < USER_HOLD_RELEASE_MIN_LUX) {
            return false;
        }
        if (holdLux <= 0f) {
            return delta >= USER_HOLD_RELEASE_MIN_LUX;
        }
        return (delta * 100f / holdLux) >= USER_HOLD_RELEASE_PCT;
    }

    private float smooth(float lux) {
        samples[sampleIndex] = lux;
        sampleIndex = (sampleIndex + 1) % samples.length;
        if (sampleCount < samples.length) {
            sampleCount++;
        }
        float total = 0f;
        for (int i = 0; i < sampleCount; i++) {
            total += samples[i];
        }
        return total / sampleCount;
    }

    private float clampLux(float lux) {
        if (Float.isNaN(lux) || lux < 0f) return 0f;
        return Math.min(lux, MAX_LUX);
    }

    private boolean startSensor(String event) {
        if (registered) {
            ProtectionBatteryStats.setPowerState(appContext, ProtectionPowerState.ACTIVE_SCREEN_ON, event);
            return true;
        }
        if (sensorManager == null || lightSensor == null) {
            markUnavailable(appContext);
            return false;
        }
        registered = sensorManager.registerListener(this, lightSensor, SensorManager.SENSOR_DELAY_NORMAL);
        if (registered) {
            ProtectionBatteryStats.recordSensorRegistered(appContext);
            ProtectionBatteryStats.setPowerState(appContext, ProtectionPowerState.ACTIVE_SCREEN_ON, event);
        }
        BrightnessLogManager.appendSnapshot(appContext, registered ? event : "PROTECTION_SENSOR_REGISTER_FAILED", getBestLux());
        return registered;
    }

    private void stopSensor(String event) {
        if (registered && sensorManager != null) {
            sensorManager.unregisterListener(this);
            ProtectionBatteryStats.recordSensorUnregistered(appContext);
            BrightnessLogManager.appendSnapshot(appContext, event, getBestLux());
        }
        registered = false;
    }

    private void persistLuxIfNeeded(float lux, long now, boolean force) {
        boolean firstPersist = lastPersistedLux < 0f;
        boolean elapsed = now - lastLuxPersistAt >= LUX_PERSIST_MS;
        boolean changed = hasLuxChangedEnough(lastPersistedLux, lux);
        if (force || firstPersist || elapsed || changed) {
            saveLastLux(appContext, lux);
            lastPersistedLux = lux;
            lastLuxPersistAt = now;
            ProtectionBatteryStats.recordLuxPersisted(appContext);
        }
    }

    private boolean hasLuxChangedEnough(float oldLux, float newLux) {
        if (oldLux < 0f) {
            return true;
        }
        float delta = Math.abs(newLux - oldLux);
        if (delta >= LUX_PERSIST_ABSOLUTE_DELTA) {
            return true;
        }
        float base = Math.max(1f, oldLux);
        return delta / base >= LUX_PERSIST_RELATIVE_DELTA;
    }

    private float getBestLux() {
        return lastRamLux >= 0f ? lastRamLux : getLastLux(appContext);
    }

    public static boolean hasLightSensor(Context context) {
        SensorManager manager = (SensorManager) context.getApplicationContext().getSystemService(Context.SENSOR_SERVICE);
        return manager != null && manager.getDefaultSensor(Sensor.TYPE_LIGHT) != null;
    }

    public static void setAutoEnabled(Context context, boolean enabled) {
        if (enabled) {
            BrightnessLevels.captureAndForceManualMode(context);
            ProtectionBatteryStats.reset(context);
        } else {
            ProtectionBatteryStats.flush(context);
            BrightnessLevels.restorePreviousBrightnessMode(context);
        }
        SharedPreferences.Editor editor = getPrefs(context).edit()
                .putBoolean(KEY_AUTO_ENABLED, enabled)
                .putString(KEY_AUTO_MODE, enabled ? Mode.PROTECTING.name() : Mode.OFF.name())
                .putLong(KEY_USER_HOLD_UNTIL, 0L)
                .putFloat(KEY_USER_HOLD_LUX, -1f)
                .remove(KEY_EXTERNAL_CANDIDATE_RAW)
                .remove(KEY_EXTERNAL_CANDIDATE_SINCE);
        if (enabled) {
            editor.remove(KEY_LAST_AUTO_RAW)
                    .remove(KEY_LAST_AUTO_AT)
                    .putLong(KEY_IGNORE_EXTERNAL_UNTIL, System.currentTimeMillis() + APP_WRITE_GRACE_MS);
            ProtectionBatteryStats.setPowerState(context, ProtectionPowerState.RECOVERY_WAKE, "PROTECTION_STATE_ON");
        } else {
            ProtectionBatteryStats.setPowerState(context, ProtectionPowerState.OFF, "PROTECTION_STATE_OFF");
        }
        editor.apply();
        BrightnessLogManager.appendSnapshot(context, enabled ? "PROTECTION_STATE_ON" : "PROTECTION_STATE_OFF", getLastLux(context));
    }

    public static boolean isAutoEnabled(Context context) {
        return getPrefs(context).getBoolean(KEY_AUTO_ENABLED, false);
    }

    public static void recordManualOverride(Context context) {
        recordUserHold(context, BrightnessLevels.getSystemRaw(context, -1), getLastLux(context), "USER_HOLD_RECORDED_COMPAT");
    }

    public static void recordUserHold(Context context, int raw, String event) {
        recordUserHold(context, raw, getLastLux(context), event);
    }

    private static void recordUserHold(Context context, int raw, float lux, String event) {
        long now = System.currentTimeMillis();
        int percent = BrightnessLevels.getPercentForRaw(raw);
        long holdMs = lux >= 0f && lux <= 6f ? USER_HOLD_NIGHT_MS : USER_HOLD_NORMAL_MS;
        getPrefs(context).edit()
                .putLong(KEY_USER_HOLD_UNTIL, now + holdMs)
                .putLong(KEY_USER_HOLD_AT, now)
                .putFloat(KEY_USER_HOLD_LUX, lux)
                .putInt(KEY_USER_HOLD_RAW, raw)
                .putString(KEY_AUTO_MODE, Mode.USER_HOLD.name())
                .remove(KEY_EXTERNAL_CANDIDATE_RAW)
                .remove(KEY_EXTERNAL_CANDIDATE_SINCE)
                .apply();
        ProtectionBatteryStats.setPowerState(context, ProtectionPowerState.USER_HOLD_LOW_POWER, event);
        BrightnessLevels.saveCurrentPercent(context, percent);
        ProtectionLearningStore.recordUserPreference(context, lux, percent, event);
        BrightnessLogManager.appendSnapshot(context, event, lux);
    }

    private static int getLearnedPercent(Context context, float lux) {
        return ProtectionLearningStore.getLearnedPercent(context, lux);
    }

    public static long getCooldownRemainingMs(Context context) {
        return getUserHoldRemainingMs(context);
    }

    public static long getUserHoldRemainingMs(Context context) {
        return Math.max(0L, getUserHoldUntil(context) - System.currentTimeMillis());
    }

    public static int getUserHoldRaw(Context context) {
        return getPrefs(context).getInt(KEY_USER_HOLD_RAW, -1);
    }

    public static float getLastLux(Context context) {
        return getPrefs(context).getFloat(KEY_LAST_LUX, -1f);
    }

    public static void markUnavailable(Context context) {
        BrightnessLevels.restorePreviousBrightnessMode(context);
        getPrefs(context).edit()
                .putBoolean(KEY_AUTO_ENABLED, false)
                .putBoolean(KEY_SENSOR_AVAILABLE, false)
                .putString(KEY_AUTO_MODE, Mode.UNAVAILABLE.name())
                .apply();
        ProtectionBatteryStats.setPowerState(context, ProtectionPowerState.OFF, "PROTECTION_UNAVAILABLE");
        BrightnessLogManager.appendSnapshot(context, "PROTECTION_UNAVAILABLE", getLastLux(context));
    }

    public static Mode getSavedMode(Context context) {
        String value = getPrefs(context).getString(KEY_AUTO_MODE, Mode.OFF.name());
        if ("MANUAL_OVERRIDE".equals(value)) {
            return Mode.USER_HOLD;
        }
        try {
            return Mode.valueOf(value);
        } catch (Throwable t) {
            return Mode.OFF;
        }
    }

    public static String getStatusText(Context context) {
        SharedPreferences prefs = getPrefs(context);
        boolean sensorAvailable = prefs.getBoolean(KEY_SENSOR_AVAILABLE, hasLightSensor(context));
        if (!sensorAvailable) {
            return "Protection unavailable";
        }

        boolean enabled = isAutoEnabled(context);
        float lastLux = getLastLux(context);
        Mode mode = getSavedMode(context);
        long hold = getUserHoldRemainingMs(context);
        int holdRaw = getUserHoldRaw(context);
        ProtectionPolicy policy = new ProtectionPolicy();
        String profile = policy.getProfileName(lastLux);
        int learnedPercent = getLearnedPercent(context, lastLux);
        int learnedConfidence = ProtectionLearningStore.getConfidence(context, lastLux);
        ProtectionPowerState powerState = ProtectionBatteryStats.getPowerState(context);
        int brainRaw = ProtectionCurveEngine.getTargetRaw(lastLux);
        String action = prefs.getString(KEY_LAST_DECISION_ACTION, "none");
        String reason = prefs.getString(KEY_LAST_DECISION_REASON, "none");

        String luxText = lastLux < 0f ? "unknown" : String.format(Locale.US, "%.1f lx", lastLux);
        String holdText = hold > 0L ? (hold / 1000L) + "s" + (holdRaw >= 0 ? " / raw " + holdRaw : "") : "inactive";
        String learnedText = learnedPercent > 0 ? learnedPercent + "% / conf " + learnedConfidence : "none";

        return "Protection: " + (enabled ? "On" : "Off")
                + "\nLux: " + luxText + " / " + profile
                + "\nMode: " + getDisplayMode(mode)
                + "\nPower: " + powerState.name()
                + "\nBrain target raw: " + brainRaw
                + "\nDecision: " + action + " / " + reason
                + "\nUser hold: " + holdText
                + "\nLearned: " + learnedText;
    }

    public static String getDiagnosticText(Context context) {
        SharedPreferences prefs = getPrefs(context);
        long now = System.currentTimeMillis();
        float lastLux = getLastLux(context);
        ProtectionPolicy policy = new ProtectionPolicy();
        String profile = policy.getProfileName(lastLux);
        int currentRaw = BrightnessLevels.getSystemRaw(context, -1);
        int currentPercent = BrightnessLevels.getCurrentPercent(context);
        int learnedPercent = getLearnedPercent(context, lastLux);
        long lastWriteAt = getLastAutoAt(context);
        long lastDecisionAt = prefs.getLong(KEY_LAST_DECISION_AT, 0L);
        long hold = getUserHoldRemainingMs(context);
        int holdRaw = getUserHoldRaw(context);
        int brainRaw = ProtectionCurveEngine.getTargetRaw(lastLux);

        String luxText = lastLux < 0f ? "unknown" : String.format(Locale.US, "%.1f lx", lastLux);
        String learnedText = learnedPercent > 0 ? learnedPercent + "%" : "none";
        String lastWriteText = lastWriteAt <= 0L ? "never" : (Math.max(0L, now - lastWriteAt) / 1000L) + "s ago";
        String decisionAgeText = lastDecisionAt <= 0L ? "never" : (Math.max(0L, now - lastDecisionAt) / 1000L) + "s ago";
        String holdText = hold > 0L ? (hold / 1000L) + "s" + (holdRaw >= 0 ? " / raw " + holdRaw : "") : "inactive";

        return "Diagnostic mode"
                + "\nProtection: " + (isAutoEnabled(context) ? "On" : "Off")
                + "\nMode: " + getDisplayMode(getSavedMode(context))
                + "\nPower: " + ProtectionBatteryStats.getPowerState(context).name()
                + "\nSystem brightness mode: " + getSystemBrightnessModeText(context)
                + "\nLux: " + luxText + " / " + profile
                + "\nCurrent: " + currentPercent + "% / raw " + currentRaw
                + "\nBrain target raw: " + brainRaw
                + "\nTarget: " + prefs.getInt(KEY_LAST_DECISION_TARGET_PERCENT, -1) + "% / raw " + prefs.getInt(KEY_LAST_DECISION_TARGET_RAW, -1)
                + "\nDecision: " + prefs.getString(KEY_LAST_DECISION_ACTION, "none")
                + " / " + prefs.getString(KEY_LAST_DECISION_REASON, "none")
                + "\nTarget reason: " + prefs.getString(KEY_LAST_DECISION_TARGET_REASON, "none")
                + "\nConfidence: " + Math.round(prefs.getFloat(KEY_LAST_DECISION_CONFIDENCE, 0f) * 100f) + "%"
                + "\nStable wait: " + prefs.getLong(KEY_LAST_DECISION_STABLE_MS, 0L) + "ms"
                + "\nDecision wait: " + prefs.getLong(KEY_LAST_DECISION_WAIT_MS, 0L) + "ms"
                + "\nLast write: " + lastWriteText
                + "\nLast decision: " + decisionAgeText
                + "\nUser hold: " + holdText
                + "\nLearned: " + learnedText
                + "\n\n" + ProtectionBatteryStats.getDiagnosticText(context)
                + "\n\n" + ProtectionLearningStore.getDiagnosticText(context, lastLux);
    }

    public static String getDisplayMode(Mode mode) {
        if (mode == Mode.PROTECTING) return "Protecting";
        if (mode == Mode.USER_HOLD) return "Holding your brightness";
        if (mode == Mode.UNAVAILABLE) return "Unavailable";
        return "Off";
    }

    private static String getSystemBrightnessModeText(Context context) {
        int mode = BrightnessLevels.getSystemBrightnessMode(context);
        return mode == android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC ? "AUTO" : "MANUAL";
    }

    private static void saveLastDecision(Context context, long now, String action, String reason, int targetRaw, long waitMs, float confidence) {
        getPrefs(context).edit()
                .putLong(KEY_LAST_DECISION_AT, now)
                .putString(KEY_LAST_DECISION_ACTION, action)
                .putString(KEY_LAST_DECISION_REASON, reason)
                .putString(KEY_LAST_DECISION_TARGET_REASON, "ROLLING_WINDOW_TARGET_RAW")
                .putInt(KEY_LAST_DECISION_TARGET_PERCENT, ProtectionCurveEngine.nearestProtectionPercentForRaw(targetRaw))
                .putInt(KEY_LAST_DECISION_TARGET_RAW, targetRaw)
                .putLong(KEY_LAST_DECISION_STABLE_MS, 0L)
                .putLong(KEY_LAST_DECISION_WAIT_MS, waitMs)
                .putFloat(KEY_LAST_DECISION_CONFIDENCE, confidence)
                .apply();
    }

    private static boolean isUserHoldActive(Context context, long now) {
        return getUserHoldUntil(context) > now;
    }

    private static long getUserHoldUntil(Context context) {
        return getPrefs(context).getLong(KEY_USER_HOLD_UNTIL, 0L);
    }

    private static float getUserHoldLux(Context context) {
        return getPrefs(context).getFloat(KEY_USER_HOLD_LUX, -1f);
    }

    private static void clearUserHold(Context context) {
        getPrefs(context).edit()
                .putLong(KEY_USER_HOLD_UNTIL, 0L)
                .putFloat(KEY_USER_HOLD_LUX, -1f)
                .remove(KEY_USER_HOLD_RAW)
                .remove(KEY_USER_HOLD_AT)
                .apply();
    }

    private static int getLastAutoRaw(Context context) {
        return getPrefs(context).getInt(KEY_LAST_AUTO_RAW, -1);
    }

    private static long getLastAutoAt(Context context) {
        return getPrefs(context).getLong(KEY_LAST_AUTO_AT, 0L);
    }

    private static long getIgnoreExternalUntil(Context context) {
        return getPrefs(context).getLong(KEY_IGNORE_EXTERNAL_UNTIL, 0L);
    }

    private static void beginAppWriteGrace(Context context) {
        BrightnessLevels.markAppBrightnessWriteGrace(context);
    }

    private static void clearAutoWriteTracking(Context context) {
        getPrefs(context).edit()
                .remove(KEY_LAST_AUTO_RAW)
                .remove(KEY_LAST_AUTO_AT)
                .apply();
    }

    private static void saveLastAutoRaw(Context context, int raw) {
        getPrefs(context).edit()
                .putInt(KEY_LAST_AUTO_RAW, raw)
                .putLong(KEY_LAST_AUTO_AT, System.currentTimeMillis())
                .apply();
    }

    private static void saveLastAutoRawBaseline(Context context, int raw) {
        int lastRaw = getLastAutoRaw(context);
        long lastAt = getLastAutoAt(context);
        if (lastRaw != raw || lastAt <= 0L) {
            saveLastAutoRaw(context, raw);
        }
    }

    private static int getExternalCandidateRaw(Context context) {
        return getPrefs(context).getInt(KEY_EXTERNAL_CANDIDATE_RAW, -1);
    }

    private static long getExternalCandidateSince(Context context) {
        return getPrefs(context).getLong(KEY_EXTERNAL_CANDIDATE_SINCE, 0L);
    }

    private static void saveExternalCandidate(Context context, int raw, long since) {
        getPrefs(context).edit()
                .putInt(KEY_EXTERNAL_CANDIDATE_RAW, raw)
                .putLong(KEY_EXTERNAL_CANDIDATE_SINCE, since)
                .apply();
    }

    private static void clearExternalCandidate(Context context) {
        getPrefs(context).edit()
                .remove(KEY_EXTERNAL_CANDIDATE_RAW)
                .remove(KEY_EXTERNAL_CANDIDATE_SINCE)
                .apply();
    }

    private static void saveMode(Context context, Mode mode) {
        getPrefs(context).edit().putString(KEY_AUTO_MODE, mode.name()).apply();
    }

    private static void saveLastLux(Context context, float lux) {
        getPrefs(context).edit().putFloat(KEY_LAST_LUX, lux).apply();
    }

    private static void saveSensorAvailable(Context context, boolean available) {
        getPrefs(context).edit().putBoolean(KEY_SENSOR_AVAILABLE, available).apply();
    }

    private static SharedPreferences getPrefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(BrightnessLevels.PREFS, Context.MODE_PRIVATE);
    }
}
