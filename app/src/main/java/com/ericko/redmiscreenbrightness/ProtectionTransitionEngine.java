package com.ericko.redmiscreenbrightness;

import android.content.Context;
import android.os.Handler;

public final class ProtectionTransitionEngine {
    private static final int MIN_UP_STEP_RAW = 2;
    private static final int MAX_UP_STEP_RAW = 8;
    private static final int MAX_DOWN_STEP_RAW = 6;
    private static final int MAX_DEEP_NIGHT_STEP_RAW = 2;

    private static final long UP_STEP_MS = 90L;
    private static final long DOWN_STEP_MS = 120L;
    private static final long DEEP_NIGHT_STEP_MS = 160L;

    private final Context appContext;
    private final Handler handler;
    private int targetRaw = -1;
    private boolean running = false;

    public ProtectionTransitionEngine(Context context, Handler handler) {
        this.appContext = context.getApplicationContext();
        this.handler = handler;
    }

    public void cancel() {
        running = false;
        targetRaw = -1;
        handler.removeCallbacks(stepRunnable);
    }

    public boolean isRunning() {
        return running;
    }

    public int getTargetRaw() {
        return targetRaw;
    }

    public void recoverToReadableRaw(
            int requestedRescueRaw,
            int requestedFinalTargetRaw,
            String reason) {
        int safeRescue = ProtectionCurveEngine.clampRaw(requestedRescueRaw);
        int safeFinalTarget = ProtectionCurveEngine.clampRaw(requestedFinalTargetRaw);
        int currentRaw = BrightnessLevels.getSystemRaw(appContext, safeRescue);
        if (safeRescue <= currentRaw) {
            BrightnessLogManager.logSnapshotIfChanged(
                    appContext,
                    "SUNLIGHT_RESCUE_SKIPPED_RAW_" + currentRaw + "_TO_" + safeRescue
                            + "_FINAL_" + safeFinalTarget + "_" + safe(reason),
                    AutoBrightnessManager.getLastLux(appContext));
            return;
        }

        stopCurrentTransitionAt(safeRescue);
        BrightnessLevels.markAppBrightnessWriteGrace(appContext);
        boolean ok = BrightnessLevels.applyProtectedRaw(appContext, safeRescue);
        if (ok) {
            ProtectionBatteryStats.recordBrightnessWrite(appContext);
            BrightnessLogManager.appendSnapshot(
                    appContext,
                    "SUNLIGHT_RESCUE_RAW_" + currentRaw + "_TO_" + safeRescue
                            + "_FINAL_" + safeFinalTarget + "_" + safe(reason),
                    AutoBrightnessManager.getLastLux(appContext));
        } else {
            BrightnessLogManager.appendSnapshot(
                    appContext,
                    "SUNLIGHT_RESCUE_WRITE_FAILED_RAW_" + safeRescue + "_" + safe(reason),
                    AutoBrightnessManager.getLastLux(appContext));
        }
    }

    public void settleToComfortableDarkRaw(
            int requestedSettleRaw,
            int requestedFinalTargetRaw,
            String reason) {
        int safeSettle = ProtectionCurveEngine.clampRaw(requestedSettleRaw);
        int safeFinalTarget = ProtectionCurveEngine.clampRaw(requestedFinalTargetRaw);
        int currentRaw = BrightnessLevels.getSystemRaw(appContext, safeSettle);
        if (safeSettle >= currentRaw) {
            BrightnessLogManager.logSnapshotIfChanged(
                    appContext,
                    "DARK_SETTLE_SKIPPED_RAW_" + currentRaw + "_TO_" + safeSettle
                            + "_FINAL_" + safeFinalTarget + "_" + safe(reason),
                    AutoBrightnessManager.getLastLux(appContext));
            return;
        }

        stopCurrentTransitionAt(safeSettle);
        BrightnessLevels.markAppBrightnessWriteGrace(appContext);
        boolean ok = BrightnessLevels.applyProtectedRaw(appContext, safeSettle);
        if (ok) {
            ProtectionBatteryStats.recordBrightnessWrite(appContext);
            BrightnessLogManager.appendSnapshot(
                    appContext,
                    "DARK_SETTLE_RAW_" + currentRaw + "_TO_" + safeSettle
                            + "_FINAL_" + safeFinalTarget + "_" + safe(reason),
                    AutoBrightnessManager.getLastLux(appContext));
        } else {
            BrightnessLogManager.appendSnapshot(
                    appContext,
                    "DARK_SETTLE_WRITE_FAILED_RAW_" + safeSettle + "_" + safe(reason),
                    AutoBrightnessManager.getLastLux(appContext));
        }
    }

    public void transitionToRaw(int requestedTargetRaw, String reason) {
        int safeTarget = ProtectionCurveEngine.clampRaw(requestedTargetRaw);
        int currentRaw = BrightnessLevels.getSystemRaw(appContext, safeTarget);
        if (currentRaw == safeTarget) {
            targetRaw = safeTarget;
            running = false;
            handler.removeCallbacks(stepRunnable);
            BrightnessLevels.saveCurrentPercent(
                    appContext,
                    ProtectionCurveEngine.nearestProtectionPercentForRaw(safeTarget));
            ProtectionBatteryStats.recordBrightnessWriteSkip(appContext);
            BrightnessLogManager.logSnapshotIfChanged(
                    appContext,
                    "TRANSITION_ALREADY_AT_RAW_" + safeTarget + "_" + safe(reason),
                    AutoBrightnessManager.getLastLux(appContext));
            return;
        }

        targetRaw = safeTarget;
        running = true;
        handler.removeCallbacks(stepRunnable);
        BrightnessLogManager.appendSnapshot(
                appContext,
                "TRANSITION_START_RAW_" + currentRaw + "_TO_" + safeTarget + "_" + safe(reason),
                AutoBrightnessManager.getLastLux(appContext));
        handler.post(stepRunnable);
    }

    private final Runnable stepRunnable = new Runnable() {
        @Override
        public void run() {
            if (!running || targetRaw < 0) {
                return;
            }

            int currentRaw = BrightnessLevels.getSystemRaw(appContext, targetRaw);
            if (currentRaw == targetRaw) {
                finishTransition();
                return;
            }

            int direction = targetRaw > currentRaw ? 1 : -1;
            int distance = Math.abs(targetRaw - currentRaw);
            int stepSize = getAdaptiveStepSize(currentRaw, targetRaw, distance);
            int nextRaw = currentRaw + direction * Math.min(stepSize, distance);
            nextRaw = ProtectionCurveEngine.clampRaw(nextRaw);

            BrightnessLevels.markAppBrightnessWriteGrace(appContext);
            boolean ok = BrightnessLevels.applyProtectedRaw(appContext, nextRaw);
            if (!ok) {
                BrightnessLogManager.appendSnapshot(
                        appContext,
                        "TRANSITION_WRITE_FAILED_RAW_" + nextRaw,
                        AutoBrightnessManager.getLastLux(appContext));
                running = false;
                return;
            }
            ProtectionBatteryStats.recordBrightnessWrite(appContext);

            if (nextRaw == targetRaw) {
                finishTransition();
                return;
            }

            handler.postDelayed(this, getAdaptiveDelayMs(currentRaw, targetRaw));
        }
    };

    private void finishTransition() {
        running = false;
        BrightnessLogManager.logSnapshotIfChanged(
                appContext,
                "TRANSITION_DONE_RAW_" + targetRaw,
                AutoBrightnessManager.getLastLux(appContext));
    }

    private void stopCurrentTransitionAt(int raw) {
        running = false;
        targetRaw = raw;
        handler.removeCallbacks(stepRunnable);
    }

    private int getAdaptiveStepSize(int currentRaw, int targetRaw, int distance) {
        if (distance <= 2) {
            return distance;
        }

        if (targetRaw > currentRaw) {
            int proportional = (int) Math.ceil(distance * 0.35f);
            return clamp(proportional, MIN_UP_STEP_RAW, MAX_UP_STEP_RAW);
        }

        if (targetRaw <= 6) {
            int proportional = (int) Math.ceil(distance * 0.22f);
            return clamp(proportional, 1, MAX_DEEP_NIGHT_STEP_RAW);
        }

        int proportional = (int) Math.ceil(distance * 0.30f);
        return clamp(proportional, 1, MAX_DOWN_STEP_RAW);
    }

    private long getAdaptiveDelayMs(int currentRaw, int targetRaw) {
        if (targetRaw > currentRaw) {
            return UP_STEP_MS;
        }
        if (targetRaw <= 6) {
            return DEEP_NIGHT_STEP_MS;
        }
        return DOWN_STEP_MS;
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private String safe(String value) {
        if (value == null || value.length() == 0) {
            return "NO_REASON";
        }
        return value.replace(' ', '_');
    }
}
