package com.ericko.redmiscreenbrightness;

import android.content.Context;
import android.content.SharedPreferences;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;

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
    private static final long WRITE_BUDGET_MS = 6000L;
    private static final long WRITE_BUDGET_UP_SMALL_MS = 3000L;
    private static final long WRITE_BUDGET_UP_STRONG_MS = 1000L;
    private static final long WRITE_BUDGET_NIGHT_18_MS = 9000L;
    private static final long WRITE_BUDGET_NIGHT_15_MS = 10000L;
    private static final long WRITE_BUDGET_NIGHT_12_MS = 12000L;
    private static final long SAME_BAND_EVALUATE_MS = 12000L;
    private static final long USER_HOLD_EVALUATE_MS = 25000L;
    private static final long LUX_PERSIST_MS = 30000L;
    private static final float LUX_PERSIST_RELATIVE_DELTA = 0.12f;
    private static final float LUX_PERSIST_ABSOLUTE_DELTA = 3f;
    private static final float STRONG_RISE_RELATIVE = 1.8f;
    private static final float STRONG_RISE_ABSOLUTE = 30f;
    public static final long USER_HOLD_MS = 45L * 60L * 1000L;
    private static final float USER_HOLD_RELEASE_PCT = 120f;
    private static final float USER_HOLD_RELEASE_MIN_LUX = 60f;

    public enum Mode {
        OFF, PROTECTING, USER_HOLD, UNAVAILABLE
    }

    private final Context appContext;
    private final SensorManager sensorManager;
    private final Sensor lightSensor;
    private final ProtectionPolicy protectionPolicy = new ProtectionPolicy();
    private final ProtectionDecisionEngine decisionEngine = new ProtectionDecisionEngine(protectionPolicy);
    private final float[] samples = new float[SAMPLE_COUNT];
    private int sampleCount = 0;
    private int sampleIndex = 0;
    private boolean registered = false;
    private boolean screenAwake = true;
    private int candidatePercent = -1;
    private long candidateSince = 0L;
    private float lastRamLux = -1f;
    private float lastPersistedLux = -1f;
    private long lastLuxPersistAt = 0L;
    private String lastEvaluatedBandKey = "";
    private int lastEvaluatedTargetPercent = -1;
    private float lastEvaluatedLux = -1f;
    private long lastEvaluateAt = 0L;

    public AutoBrightnessManager(Context context) {
        appContext = context.getApplicationContext();
        sensorManager = (SensorManager) appContext.getSystemService(Context.SENSOR_SERVICE);
        lightSensor = sensorManager == null ? null : sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT);
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
        stopSensor("PROTECTION_SENSOR_UNREGISTERED");
        ProtectionBatteryStats.flush(appContext);
    }

    public void enterScreenOffSleep(String event) {
        screenAwake = false;
        candidatePercent = -1;
        candidateSince = 0L;
        stopSensor("PROTECTION_SENSOR_SLEEP_SCREEN_OFF");
        saveMode(appContext, isAutoEnabled(appContext) ? Mode.PROTECTING : Mode.OFF);
        ProtectionBatteryStats.recordScreenOff(appContext);
        ProtectionBatteryStats.setPowerState(appContext, ProtectionPowerState.SCREEN_OFF_SLEEP, event);
        BrightnessLogManager.appendSnapshot(appContext, event, getBestLux());
    }

    public void forceAutoReevaluate(String event) {
        clearUserHold(appContext);
        clearAutoWriteTracking(appContext);
        clearExternalCandidate(appContext);
        beginAppWriteGrace(appContext);
        saveMode(appContext, Mode.PROTECTING);
        screenAwake = true;
        startSensor("PROTECTION_SENSOR_REGISTERED_FORCE");
        resetEvaluationThrottle();
        candidatePercent = -1;
        candidateSince = 0L;
        ProtectionBatteryStats.setPowerState(appContext, ProtectionPowerState.RECOVERY_WAKE, event);
        BrightnessLogManager.appendSnapshot(appContext, event, getBestLux());
        evaluateLastLux(event);
    }

    public void resumeProtection(String event) {
        forceAutoReevaluate(event);
    }

    public void onScreenWake(String event) {
        screenAwake = true;
        ProtectionBatteryStats.recordScreenOn(appContext);
        ProtectionBatteryStats.setPowerState(appContext, ProtectionPowerState.RECOVERY_WAKE, event);
        startSensor("PROTECTION_SENSOR_REGISTERED_SCREEN_ON");
        beginAppWriteGrace(appContext);
        clearExternalCandidate(appContext);
        resetEvaluationThrottle();
        candidatePercent = -1;
        candidateSince = 0L;
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
        detectUserBrightnessChange(now, lux);
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

        if (!shouldEvaluateSensorSample(trustedLux, now)) {
            ProtectionBatteryStats.recordThrottledEvaluation(appContext);
            return;
        }
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
        trackEvaluationStart(avgLux, now);
        ProtectionBatteryStats.recordEvaluation(appContext);

        if (isUserHoldActive(appContext, now)) {
            ProtectionBatteryStats.setPowerState(appContext, ProtectionPowerState.USER_HOLD_LOW_POWER, "USER_HOLD_ACTIVE");
            float holdLux = getUserHoldLux(appContext);
            if (!shouldReleaseUserHold(avgLux, holdLux)) {
                ProtectionDecisionEngine.Decision decision = makeDecision(avgLux, now, true, -1, false);
                updateCandidate(decision);
                saveLastDecision(appContext, decision, now);
                saveMode(appContext, Mode.USER_HOLD);
                logDecision(event, decision, avgLux);
                return;
            }
            clearUserHold(appContext);
            clearAutoWriteTracking(appContext);
            clearExternalCandidate(appContext);
            candidatePercent = -1;
            candidateSince = 0L;
            BrightnessLogManager.appendSnapshot(appContext, "USER_HOLD_RELEASED_BY_LUX_CHANGE", avgLux);
        }

        ProtectionBatteryStats.setPowerState(appContext, ProtectionPowerState.ACTIVE_SCREEN_ON, event);

        ProtectionDecisionEngine.Decision decision = makeDecision(
                avgLux,
                now,
                false,
                getSafeLearnedPercent(avgLux),
                shouldForceApply(event)
        );
        updateCandidate(decision);
        lastEvaluatedTargetPercent = decision.targetPercent;
        saveLastDecision(appContext, decision, now);
        logDecision(event, decision, avgLux);

        if (decision.action == ProtectionDecisionEngine.Action.APPLY) {
            applyAutoPercent(decision.targetPercent);
            return;
        }

        if (decision.action == ProtectionDecisionEngine.Action.HOLD) {
            if (decision.reason == ProtectionDecisionEngine.Reason.SAME_BUCKET && getLastAutoRaw(appContext) < 0) {
                saveLastAutoRaw(appContext, decision.targetRaw);
            }
            saveMode(appContext, Mode.PROTECTING);
        }
    }

    private ProtectionDecisionEngine.Decision makeDecision(float lux, long now, boolean userHoldActive, int learnedPercent, boolean forceApply) {
        int currentPercent = BrightnessLevels.getCurrentPercent(appContext);
        int currentRaw = BrightnessLevels.getSystemRaw(appContext, BrightnessLevels.getRawForPercent(currentPercent));
        int targetPercent = protectionPolicy.getTargetPercent(lux, currentPercent);
        long dynamicWriteBudgetMs = getDynamicWriteBudgetMs(lux, currentPercent, targetPercent);
        return decisionEngine.decide(new ProtectionDecisionEngine.Request(
                lux,
                currentPercent,
                currentRaw,
                candidatePercent,
                candidateSince,
                now,
                getLastAutoAt(appContext),
                dynamicWriteBudgetMs,
                forceApply,
                userHoldActive,
                true,
                learnedPercent,
                RAW_CHANGE_TOLERANCE
        ));
    }

    private boolean shouldForceApply(String event) {
        return event != null && !"PROTECTION_SENSOR_SAMPLE".equals(event);
    }

    private void updateCandidate(ProtectionDecisionEngine.Decision decision) {
        if (decision == null) {
            return;
        }
        candidatePercent = decision.nextCandidatePercent;
        candidateSince = decision.nextCandidateSince;
    }

    private void applyAutoPercent(int percent) {
        int raw = BrightnessLevels.getRawForPercent(percent);
        int currentRaw = BrightnessLevels.getSystemRaw(appContext, raw);
        if (Math.abs(currentRaw - raw) <= RAW_CHANGE_TOLERANCE) {
            beginAppWriteGrace(appContext);
            saveLastAutoRaw(appContext, raw);
            saveMode(appContext, Mode.PROTECTING);
            ProtectionBatteryStats.recordBrightnessWriteSkip(appContext);
            BrightnessLogManager.logSnapshotIfChanged(appContext, "PROTECTION_AT_" + percent + "_PERCENT", getBestLux());
            return;
        }

        beginAppWriteGrace(appContext);
        boolean ok = BrightnessLevels.applyBrightness(appContext, percent, raw);
        if (ok) {
            saveLastAutoRaw(appContext, raw);
            saveMode(appContext, Mode.PROTECTING);
            ProtectionBatteryStats.recordBrightnessWrite(appContext);
            BrightnessLogManager.appendSnapshot(appContext, "PROTECTION_APPLIED_" + percent + "_PERCENT_RAW_" + raw, getBestLux());
        } else {
            BrightnessLogManager.appendSnapshot(appContext, "PROTECTION_APPLY_FAILED_" + percent + "_PERCENT_RAW_" + raw, getBestLux());
        }
    }

    private int getSafeLearnedPercent(float lux) {
        return ProtectionLearningStore.getSafeLearnedPercent(appContext, lux);
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
        candidatePercent = -1;
        candidateSince = 0L;
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

    private boolean shouldEvaluateSensorSample(float lux, long now) {
        if (lastEvaluateAt <= 0L) {
            return true;
        }
        String bandKey = protectionPolicy.getBandKey(lux);
        if (!bandKey.equals(lastEvaluatedBandKey)) {
            return true;
        }
        if (isStrongLuxRise(lux)) {
            return true;
        }
        long interval = isUserHoldActive(appContext, now) ? USER_HOLD_EVALUATE_MS : SAME_BAND_EVALUATE_MS;
        return now - lastEvaluateAt >= interval;
    }

    private boolean isStrongLuxRise(float lux) {
        if (lastEvaluatedLux < 0f) {
            return false;
        }
        float delta = lux - lastEvaluatedLux;
        return delta >= STRONG_RISE_ABSOLUTE && lux >= lastEvaluatedLux * STRONG_RISE_RELATIVE;
    }

    private void trackEvaluationStart(float lux, long now) {
        lastEvaluateAt = now;
        lastEvaluatedLux = lux;
        lastEvaluatedBandKey = protectionPolicy.getBandKey(lux);
    }

    private void resetEvaluationThrottle() {
        lastEvaluateAt = 0L;
        lastEvaluatedLux = -1f;
        lastEvaluatedBandKey = "";
        lastEvaluatedTargetPercent = -1;
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

    private void logDecision(String event, ProtectionDecisionEngine.Decision decision, float lux) {
        if (decision == null) {
            return;
        }
        ProtectionBatteryStats.recordDecision(appContext);
        if (!"PROTECTION_SENSOR_SAMPLE".equals(event)
                || decision.action == ProtectionDecisionEngine.Action.APPLY
                || decision.reason == ProtectionDecisionEngine.Reason.USER_HOLD_ACTIVE
                || decision.reason == ProtectionDecisionEngine.Reason.SENSOR_UNTRUSTED
                || decision.reason == ProtectionDecisionEngine.Reason.INVALID_REQUEST) {
            BrightnessLogManager.logSnapshotIfChanged(appContext, event + "_DECISION_" + decision.toLogSuffix(), lux);
        } else {
            ProtectionBatteryStats.recordNormalLogSkip(appContext);
        }
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
            ProtectionBatteryStats.reset(context);
        } else {
            ProtectionBatteryStats.flush(context);
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
        getPrefs(context).edit()
                .putLong(KEY_USER_HOLD_UNTIL, now + USER_HOLD_MS)
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

        String luxText = lastLux < 0f ? "unknown" : String.format(Locale.US, "%.1f lx", lastLux);
        String holdText = hold > 0L ? (hold / 1000L) + "s" + (holdRaw >= 0 ? " / raw " + holdRaw : "") : "inactive";
        String learnedText = learnedPercent > 0 ? learnedPercent + "% / conf " + learnedConfidence : "none";

        return "Protection: " + (enabled ? "On" : "Off")
                + "\nLux: " + luxText + " / " + profile
                + "\nMode: " + getDisplayMode(mode)
                + "\nPower: " + powerState.name()
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
        long writeWait = getWriteBudgetRemainingMs(context, now);
        long hold = getUserHoldRemainingMs(context);
        int holdRaw = getUserHoldRaw(context);

        String luxText = lastLux < 0f ? "unknown" : String.format(Locale.US, "%.1f lx", lastLux);
        String learnedText = learnedPercent > 0 ? learnedPercent + "%" : "none";
        String lastWriteText = lastWriteAt <= 0L ? "never" : (Math.max(0L, now - lastWriteAt) / 1000L) + "s ago";
        String decisionAgeText = lastDecisionAt <= 0L ? "never" : (Math.max(0L, now - lastDecisionAt) / 1000L) + "s ago";
        String holdText = hold > 0L ? (hold / 1000L) + "s" + (holdRaw >= 0 ? " / raw " + holdRaw : "") : "inactive";

        return "Diagnostic mode"
                + "\nProtection: " + (isAutoEnabled(context) ? "On" : "Off")
                + "\nMode: " + getDisplayMode(getSavedMode(context))
                + "\nPower: " + ProtectionBatteryStats.getPowerState(context).name()
                + "\nLux: " + luxText + " / " + profile
                + "\nCurrent: " + currentPercent + "% / raw " + currentRaw
                + "\nTarget: " + prefs.getInt(KEY_LAST_DECISION_TARGET_PERCENT, -1) + "% / raw " + prefs.getInt(KEY_LAST_DECISION_TARGET_RAW, -1)
                + "\nDecision: " + prefs.getString(KEY_LAST_DECISION_ACTION, "none")
                + " / " + prefs.getString(KEY_LAST_DECISION_REASON, "none")
                + "\nTarget reason: " + prefs.getString(KEY_LAST_DECISION_TARGET_REASON, "none")
                + "\nConfidence: " + Math.round(prefs.getFloat(KEY_LAST_DECISION_CONFIDENCE, 0f) * 100f) + "%"
                + "\nStable wait: " + prefs.getLong(KEY_LAST_DECISION_STABLE_MS, 0L) + "ms"
                + "\nDecision wait: " + prefs.getLong(KEY_LAST_DECISION_WAIT_MS, 0L) + "ms"
                + "\nWrite budget: " + (writeWait > 0L ? writeWait + "ms remaining" : "ready")
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

    private static void saveLastDecision(Context context, ProtectionDecisionEngine.Decision decision, long now) {
        if (decision == null) {
            return;
        }
        getPrefs(context).edit()
                .putLong(KEY_LAST_DECISION_AT, now)
                .putString(KEY_LAST_DECISION_ACTION, decision.action.name())
                .putString(KEY_LAST_DECISION_REASON, decision.reason.name())
                .putString(KEY_LAST_DECISION_TARGET_REASON, decision.targetReason.name())
                .putInt(KEY_LAST_DECISION_TARGET_PERCENT, decision.targetPercent)
                .putInt(KEY_LAST_DECISION_TARGET_RAW, decision.targetRaw)
                .putLong(KEY_LAST_DECISION_STABLE_MS, decision.stableMs)
                .putLong(KEY_LAST_DECISION_WAIT_MS, decision.waitMs)
                .putFloat(KEY_LAST_DECISION_CONFIDENCE, decision.confidence)
                .apply();
    }

    private static long getWriteBudgetRemainingMs(Context context, long now) {
        long lastWriteAt = getLastAutoAt(context);
        if (lastWriteAt <= 0L) {
            return 0L;
        }
        float lux = getLastLux(context);
        int currentPercent = BrightnessLevels.getCurrentPercent(context);
        int targetPercent = new ProtectionPolicy().getTargetPercent(lux, currentPercent);
        long budgetMs = getDynamicWriteBudgetMs(lux, currentPercent, targetPercent);
        long elapsed = Math.max(0L, now - lastWriteAt);
        if (elapsed >= budgetMs) {
            return 0L;
        }
        return budgetMs - elapsed;
    }

    private static long getDynamicWriteBudgetMs(float lux, int currentPercent, int targetPercent) {
        if (targetPercent > currentPercent) {
            int delta = targetPercent - currentPercent;
            if (lux >= 1200f || delta >= 8) {
                return WRITE_BUDGET_UP_STRONG_MS;
            }
            return WRITE_BUDGET_UP_SMALL_MS;
        }
        if (targetPercent < currentPercent) {
            if (targetPercent == ProtectionPolicy.LEVEL_12) return WRITE_BUDGET_NIGHT_12_MS;
            if (targetPercent == ProtectionPolicy.LEVEL_15) return WRITE_BUDGET_NIGHT_15_MS;
            if (targetPercent == ProtectionPolicy.LEVEL_18) return WRITE_BUDGET_NIGHT_18_MS;
            return WRITE_BUDGET_MS;
        }
        return 0L;
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
        getPrefs(context).edit().putLong(KEY_IGNORE_EXTERNAL_UNTIL, System.currentTimeMillis() + APP_WRITE_GRACE_MS).apply();
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
