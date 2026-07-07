package com.ericko.redmiscreenbrightness;

import android.content.Context;
import android.content.SharedPreferences;

public final class ProtectionLearningStore {
    private static final String KEY_LEARNING_SCHEMA_VERSION = "protection_learning_schema_version";
    private static final String KEY_LEARNED_BAND_PERCENT_PREFIX = "protection_v2_learned_percent_";
    private static final String KEY_LEARNED_BAND_CONFIDENCE_PREFIX = "protection_v2_learned_confidence_";
    private static final String KEY_PENDING_BAND_PERCENT_PREFIX = "protection_v2_pending_percent_";
    private static final String KEY_CONFIRM_COUNT_BAND_PREFIX = "protection_v2_confirm_count_";
    private static final String KEY_ROLLBACK_COUNT_BAND_PREFIX = "protection_v2_rollback_count_";

    private static final int CURRENT_SCHEMA_VERSION = 2;
    private static final int REQUIRED_CONFIRMATIONS = 2;
    private static final int REQUIRED_ROLLBACKS = 2;
    private static final int MAX_CONFIDENCE = 3;

    private ProtectionLearningStore() {
    }

    public static void recordUserPreference(Context context, float lux, int percent, String event) {
        ProtectionPolicy policy = new ProtectionPolicy();
        String bandKey = policy.getBandKey(lux);
        String bandLabel = policy.getBandLabel(lux);
        if ("unknown".equals(bandKey) || !policy.isSafeLearnedPercentForBand(lux, percent)) {
            BrightnessLogManager.appendSnapshot(context, "LEARNING_V2_SKIPPED_" + bandKey + "_" + percent + "_PERCENT", lux);
            return;
        }

        SharedPreferences prefs = getPrefs(context);
        ensureSchema(prefs);

        String learnedKey = KEY_LEARNED_BAND_PERCENT_PREFIX + bandKey;
        String confidenceKey = KEY_LEARNED_BAND_CONFIDENCE_PREFIX + bandKey;
        String pendingKey = KEY_PENDING_BAND_PERCENT_PREFIX + bandKey;
        String countKey = KEY_CONFIRM_COUNT_BAND_PREFIX + bandKey;
        String rollbackKey = KEY_ROLLBACK_COUNT_BAND_PREFIX + bandKey;

        int learnedPercent = prefs.getInt(learnedKey, -1);
        SharedPreferences.Editor editor = prefs.edit().putInt(KEY_LEARNING_SCHEMA_VERSION, CURRENT_SCHEMA_VERSION);

        if (learnedPercent == percent) {
            int confidence = Math.min(MAX_CONFIDENCE, prefs.getInt(confidenceKey, 1) + 1);
            editor.putInt(confidenceKey, confidence)
                    .remove(rollbackKey)
                    .apply();
            BrightnessLogManager.appendSnapshot(context, "LEARNING_V2_CONFIRMED_" + bandKey + "_" + percent + "_CONF_" + confidence, lux);
            return;
        }

        if (learnedPercent >= ProtectionPolicy.LEVEL_20 && learnedPercent != percent) {
            int rollbackCount = prefs.getInt(rollbackKey, 0) + 1;
            editor.putInt(rollbackKey, rollbackCount);
            BrightnessLogManager.appendSnapshot(context, "LEARNING_V2_ROLLBACK_CANDIDATE_" + bandKey + "_OLD_" + learnedPercent + "_NEW_" + percent + "_COUNT_" + rollbackCount, lux);
            if (rollbackCount >= REQUIRED_ROLLBACKS) {
                editor.remove(learnedKey)
                        .remove(confidenceKey)
                        .remove(rollbackKey);
                BrightnessLogManager.appendSnapshot(context, "LEARNING_V2_ROLLED_BACK_" + bandKey + "_OLD_" + learnedPercent, lux);
            }
        }

        int pendingPercent = prefs.getInt(pendingKey, -1);
        int count = pendingPercent == percent ? prefs.getInt(countKey, 0) + 1 : 1;
        editor.putInt(pendingKey, percent)
                .putInt(countKey, count);

        if (count >= REQUIRED_CONFIRMATIONS) {
            int confidence = Math.min(MAX_CONFIDENCE, Math.max(2, count));
            editor.putInt(learnedKey, percent)
                    .putInt(confidenceKey, confidence)
                    .remove(rollbackKey);
            BrightnessLogManager.appendSnapshot(context, "LEARNING_V2_LEARNED_" + bandKey + "_" + percent + "_PERCENT_CONF_" + confidence, lux);
        } else {
            BrightnessLogManager.appendSnapshot(context, "LEARNING_V2_PENDING_" + bandKey + "_" + percent + "_PERCENT_COUNT_" + count + "_LABEL_" + safeLog(bandLabel), lux);
        }
        editor.apply();
    }

    public static int getSafeLearnedPercent(Context context, float lux) {
        int learnedPercent = getLearnedPercent(context, lux);
        if (new ProtectionPolicy().isSafeLearnedPercentForBand(lux, learnedPercent)) {
            return learnedPercent;
        }
        return -1;
    }

    public static int getLearnedPercent(Context context, float lux) {
        ProtectionPolicy policy = new ProtectionPolicy();
        String bandKey = policy.getBandKey(lux);
        if ("unknown".equals(bandKey)) {
            return -1;
        }
        SharedPreferences prefs = getPrefs(context);
        ensureSchema(prefs);
        return prefs.getInt(KEY_LEARNED_BAND_PERCENT_PREFIX + bandKey, -1);
    }

    public static int getConfidence(Context context, float lux) {
        ProtectionPolicy policy = new ProtectionPolicy();
        String bandKey = policy.getBandKey(lux);
        if ("unknown".equals(bandKey)) {
            return 0;
        }
        SharedPreferences prefs = getPrefs(context);
        ensureSchema(prefs);
        return prefs.getInt(KEY_LEARNED_BAND_CONFIDENCE_PREFIX + bandKey, 0);
    }

    public static String getDiagnosticText(Context context, float lux) {
        ProtectionPolicy policy = new ProtectionPolicy();
        String bandKey = policy.getBandKey(lux);
        String bandLabel = policy.getBandLabel(lux);
        int learnedPercent = getLearnedPercent(context, lux);
        int confidence = getConfidence(context, lux);
        SharedPreferences prefs = getPrefs(context);
        ensureSchema(prefs);
        String pendingKey = KEY_PENDING_BAND_PERCENT_PREFIX + bandKey;
        String countKey = KEY_CONFIRM_COUNT_BAND_PREFIX + bandKey;
        String rollbackKey = KEY_ROLLBACK_COUNT_BAND_PREFIX + bandKey;

        return "Learning v2"
                + "\nSchema: " + prefs.getInt(KEY_LEARNING_SCHEMA_VERSION, CURRENT_SCHEMA_VERSION)
                + "\nBand: " + bandLabel + " / " + bandKey
                + "\nDefault: " + policy.getBandDefaultPercent(lux) + "%"
                + "\nMax learned: " + policy.getBandMaxLearnedPercent(lux) + "%"
                + "\nLearned: " + (learnedPercent > 0 ? learnedPercent + "%" : "none")
                + "\nConfidence: " + confidence + "/" + MAX_CONFIDENCE
                + "\nPending: " + prefs.getInt(pendingKey, -1) + "%"
                + "\nConfirm count: " + prefs.getInt(countKey, 0)
                + "\nRollback count: " + prefs.getInt(rollbackKey, 0);
    }

    private static void ensureSchema(SharedPreferences prefs) {
        if (prefs.getInt(KEY_LEARNING_SCHEMA_VERSION, 0) != CURRENT_SCHEMA_VERSION) {
            prefs.edit().putInt(KEY_LEARNING_SCHEMA_VERSION, CURRENT_SCHEMA_VERSION).apply();
        }
    }

    private static String safeLog(String value) {
        return value == null ? "unknown" : value.replace(' ', '_').replace('+', 'p').replace('-', '_');
    }

    private static SharedPreferences getPrefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(BrightnessLevels.PREFS, Context.MODE_PRIVATE);
    }
}
