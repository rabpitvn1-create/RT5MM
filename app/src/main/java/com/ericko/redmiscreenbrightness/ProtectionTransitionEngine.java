package com.ericko.redmiscreenbrightness;

import android.content.Context;
import android.os.Handler;

public final class ProtectionTransitionEngine {
    private static final int MAX_WRITES_PER_TRANSITION = 3;
    private static final int MAX_WRITES_AFTER_INTERMEDIATE = 2;
    private static final long INTERMEDIATE_LINK_WINDOW_MS = 5000L;

    private static final long UP_STEP_MS = 90L;
    private static final long DOWN_STEP_MS = 120L;
    private static final long DEEP_NIGHT_STEP_MS = 160L;

    private final Context appContext;
    private final Handler handler;
    private int targetRaw = -1;
    private int writesRemaining;
    private boolean running = false;
    private int linkedFinalTargetRaw = -1;
    private long linkedIntermediateAt;

    public ProtectionTransitionEngine(Context context, Handler handler) {
        this.appContext = context.getApplicationContext();
        this.handler = handler;
    }

    public void cancel() {
        running = false;
        targetRaw = -1;
        writesRemaining = 0;
        linkedFinalTargetRaw = -1;
        linkedIntermediateAt = 0L;
        handler.removeCallbacks(stepRunnable);
    }

    public boolean isRunning() {
        return running;
    }

    public int getTargetRaw() {
        return targetRaw;
    }

    public int getWritesRemainingForTest() {
        return writesRemaining;
    }

    public void recoverToReadableRaw(
            int requestedRescueRaw,
            int requestedFinalTargetRaw,
            String reason) {
        int safeRescue = ProtectionCurveEngine.clampRaw(requestedRescueRaw);
        int safeFinalTarget = ProtectionCurveEngine.clampRaw(requestedFinalTargetRaw);
        int currentRaw = BrightnessLevels.getCachedSystemRaw(appContext, safeRescue);
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
            linkIntermediateToFinal(safeFinalTarget);
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
        int currentRaw = BrightnessLevels.getCachedSystemRaw(appContext, safeSettle);
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
            linkIntermediateToFinal(safeFinalTarget);
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
        int currentRaw = BrightnessLevels.getCachedSystemRaw(appContext, safeTarget);
        if (currentRaw == safeTarget) {
            targetRaw = safeTarget;
            running = false;
            writesRemaining = 0;
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

        if (running && targetRaw == safeTarget) {
            return;
        }

        targetRaw = safeTarget;
        writesRemaining = isLinkedIntermediate(safeTarget)
                ? MAX_WRITES_AFTER_INTERMEDIATE
                : MAX_WRITES_PER_TRANSITION;
        clearIntermediateLink();
        running = true;
        handler.removeCallbacks(stepRunnable);
        BrightnessLogManager.appendSnapshot(
                appContext,
                "TRANSITION_START_RAW_" + currentRaw + "_TO_" + safeTarget
                        + "_BUDGET_" + writesRemaining + "_" + safe(reason),
                AutoBrightnessManager.getLastLux(appContext));
        handler.post(stepRunnable);
    }

    private final Runnable stepRunnable = new Runnable() {
        @Override
        public void run() {
            if (!running || targetRaw < 0 || writesRemaining <= 0) {
                running = false;
                return;
            }

            int currentRaw = BrightnessLevels.getCachedSystemRaw(appContext, targetRaw);
            if (currentRaw == targetRaw) {
                finishTransition();
                return;
            }

            int nextRaw = calculateBudgetedNextRaw(currentRaw, targetRaw, writesRemaining);
            BrightnessLevels.markAppBrightnessWriteGrace(appContext);
            boolean ok = BrightnessLevels.applyProtectedRaw(appContext, nextRaw);
            if (!ok) {
                BrightnessLogManager.appendSnapshot(
                        appContext,
                        "TRANSITION_WRITE_FAILED_RAW_" + nextRaw,
                        AutoBrightnessManager.getLastLux(appContext));
                running = false;
                writesRemaining = 0;
                return;
            }

            writesRemaining--;
            ProtectionBatteryStats.recordBrightnessWrite(appContext);

            if (nextRaw == targetRaw || writesRemaining <= 0) {
                if (nextRaw != targetRaw) {
                    BrightnessLevels.markAppBrightnessWriteGrace(appContext);
                    if (BrightnessLevels.applyProtectedRaw(appContext, targetRaw)) {
                        ProtectionBatteryStats.recordBrightnessWrite(appContext);
                    }
                }
                finishTransition();
                return;
            }

            handler.postDelayed(this, getAdaptiveDelayMs(currentRaw, targetRaw));
        }
    };

    static int calculateBudgetedNextRaw(int currentRaw, int targetRaw, int writesRemaining) {
        if (writesRemaining <= 1) {
            return targetRaw;
        }
        int distance = Math.abs(targetRaw - currentRaw);
        if (distance <= 2) {
            return targetRaw;
        }

        float fraction = writesRemaining >= 3 ? 0.58f : 0.72f;
        int move = Math.max(1, (int) Math.ceil(distance * fraction));
        return currentRaw + (targetRaw > currentRaw ? move : -move);
    }

    private void finishTransition() {
        running = false;
        writesRemaining = 0;
        BrightnessLogManager.logSnapshotIfChanged(
                appContext,
                "TRANSITION_DONE_RAW_" + targetRaw,
                AutoBrightnessManager.getLastLux(appContext));
    }

    private void stopCurrentTransitionAt(int raw) {
        running = false;
        targetRaw = raw;
        writesRemaining = 0;
        handler.removeCallbacks(stepRunnable);
    }

    private void linkIntermediateToFinal(int finalTargetRaw) {
        linkedFinalTargetRaw = finalTargetRaw;
        linkedIntermediateAt = System.currentTimeMillis();
    }

    private boolean isLinkedIntermediate(int finalTargetRaw) {
        return linkedFinalTargetRaw == finalTargetRaw
                && System.currentTimeMillis() - linkedIntermediateAt <= INTERMEDIATE_LINK_WINDOW_MS;
    }

    private void clearIntermediateLink() {
        linkedFinalTargetRaw = -1;
        linkedIntermediateAt = 0L;
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

    private String safe(String value) {
        if (value == null || value.length() == 0) {
            return "NO_REASON";
        }
        return value.replace(' ', '_');
    }
}
