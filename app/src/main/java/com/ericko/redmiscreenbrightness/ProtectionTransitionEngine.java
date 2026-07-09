package com.ericko.redmiscreenbrightness;

import android.content.Context;
import android.os.Handler;

public final class ProtectionTransitionEngine {
    private static final int SMALL_STEP_RAW = 1;
    private static final int NORMAL_STEP_RAW = 2;
    private static final int FAST_STEP_RAW = 3;
    private static final long UP_STEP_MS = 650L;
    private static final long STRONG_UP_STEP_MS = 380L;
    private static final long DOWN_STEP_MS = 900L;
    private static final long NIGHT_DOWN_STEP_MS = 1100L;

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

    public void transitionToRaw(int requestedTargetRaw, String reason) {
        int safeTarget = ProtectionCurveEngine.clampRaw(requestedTargetRaw);
        int currentRaw = BrightnessLevels.getSystemRaw(appContext, safeTarget);
        AutoBrightnessManager.markAppBrightnessWriteGrace(appContext);
        if (currentRaw == safeTarget) {
            targetRaw = safeTarget;
            running = false;
            BrightnessLevels.applyProtectedRaw(appContext, safeTarget);
            ProtectionBatteryStats.recordBrightnessWriteSkip(appContext);
            BrightnessLogManager.logSnapshotIfChanged(appContext, "TRANSITION_ALREADY_AT_RAW_" + safeTarget + "_" + safe(reason), AutoBrightnessManager.getLastLux(appContext));
            return;
        }
        targetRaw = safeTarget;
        running = true;
        handler.removeCallbacks(stepRunnable);
        BrightnessLogManager.appendSnapshot(appContext, "TRANSITION_START_RAW_" + currentRaw + "_TO_" + safeTarget + "_" + safe(reason), AutoBrightnessManager.getLastLux(appContext));
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
                running = false;
                BrightnessLogManager.logSnapshotIfChanged(appContext, "TRANSITION_DONE_RAW_" + targetRaw, AutoBrightnessManager.getLastLux(appContext));
                return;
            }

            int direction = targetRaw > currentRaw ? 1 : -1;
            int distance = Math.abs(targetRaw - currentRaw);
            int stepSize = getStepSize(currentRaw, targetRaw, distance);
            int nextRaw = currentRaw + direction * Math.min(stepSize, distance);
            nextRaw = ProtectionCurveEngine.clampRaw(nextRaw);

            AutoBrightnessManager.markAppBrightnessWriteGrace(appContext);
            boolean ok = BrightnessLevels.applyProtectedRaw(appContext, nextRaw);
            if (ok) {
                ProtectionBatteryStats.recordBrightnessWrite(appContext);
            } else {
                BrightnessLogManager.appendSnapshot(appContext, "TRANSITION_WRITE_FAILED_RAW_" + nextRaw, AutoBrightnessManager.getLastLux(appContext));
                running = false;
                return;
            }

            if (nextRaw == targetRaw) {
                running = false;
                BrightnessLogManager.logSnapshotIfChanged(appContext, "TRANSITION_DONE_RAW_" + targetRaw, AutoBrightnessManager.getLastLux(appContext));
                return;
            }

            handler.postDelayed(this, getStepDelayMs(currentRaw, targetRaw, distance));
        }
    };

    private int getStepSize(int currentRaw, int targetRaw, int distance) {
        if (targetRaw > currentRaw) {
            if (distance >= 8) {
                return FAST_STEP_RAW;
            }
            return NORMAL_STEP_RAW;
        }
        if (targetRaw <= 10) {
            return SMALL_STEP_RAW;
        }
        return NORMAL_STEP_RAW;
    }

    private long getStepDelayMs(int currentRaw, int targetRaw, int distance) {
        if (targetRaw > currentRaw) {
            if (distance >= 8) {
                return STRONG_UP_STEP_MS;
            }
            return UP_STEP_MS;
        }
        if (targetRaw <= 10) {
            return NIGHT_DOWN_STEP_MS;
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
