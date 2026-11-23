package com.ericko.redmiscreenbrightness;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.View;

import com.ericko.redmiscreenbrightness.ObsidianPowerButton.Icon;
import static com.ericko.redmiscreenbrightness.ObsidianStyle.*;

public final class MainActivity extends Activity {
    private static final int NOTIFICATION_REQUEST = 1001;
    private static final long START_GRACE_MS = 6500L;
    private static final long STOP_GRACE_MS = 3000L;
    private static final int RAW_CAP = ProtectionCurveEngine.clampRaw(Integer.MAX_VALUE);

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refreshTask = new Runnable() {
        @Override public void run() {
            render();
            handler.postDelayed(this, 800L);
        }
    };

    private ObsidianDashboard dashboard;
    private BackgroundGuide guide;
    private ObsidianUiState.Phase phase;
    private boolean guideOpen;
    private boolean observedLiveService;
    private boolean userStartFailed;
    private long startGraceUntil;
    private long stopGraceUntil;
    private int settingsMessage;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        ObsidianSafeFrame root = new ObsidianSafeFrame(this);
        dashboard = new ObsidianDashboard(this, this::onPower, () -> showGuide(true));
        guide = new BackgroundGuide(this, () -> showGuide(false));
        root.addView(dashboard);
        root.addView(guide);
        setContentView(root);
        showGuide(state != null && state.getBoolean("guide_open"));
        if (state != null) {
            guide.restorePosition(state.getInt("guide_step"), state.getInt("guide_scroll"));
        }
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putBoolean("guide_open", guideOpen);
        state.putInt("guide_scroll", guide.getScrollY());
        state.putInt("guide_step", guide.getActiveStep());
        super.onSaveInstanceState(state);
    }

    @Override protected void onResume() {
        super.onResume();
        settingsMessage = 0;
        render();
        // Keep the existing resume recovery without restarting a healthy controller.
        boolean wanted = AutoBrightnessManager.isAutoEnabled(this);
        if (Settings.System.canWrite(this)) {
            if (wanted && startGraceUntil == 0 && !ProtectionServiceHealth.isServiceHealthy(this)) {
                AutoBrightnessService.start(this);
            } else if (!wanted && BrightnessLevels.hasPendingModeRestore(this)) {
                BrightnessLevels.restorePreviousBrightnessMode(this);
            }
        }
        handler.removeCallbacks(refreshTask);
        handler.post(refreshTask);
    }

    @Override protected void onPause() {
        handler.removeCallbacks(refreshTask);
        super.onPause();
    }

    @SuppressWarnings("deprecation")
    @Override public void onBackPressed() {
        if (guideOpen) showGuide(false); else super.onBackPressed();
    }

    private void showGuide(boolean open) {
        guideOpen = open;
        if (open) guide.markOpened();
        guide.setVisibility(open ? View.VISIBLE : View.GONE);
        dashboard.setVisibility(open ? View.GONE : View.VISIBLE);
        dashboard.showGuideHint(!guide.hasBeenOpened());
        if (!open) render();
    }

    private void onPower() {
        render();
        switch (phase) {
            case PERMISSION_REQUIRED:
                openSettings(new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,
                        Uri.fromParts("package", getPackageName(), null)), R.string.obsidian_permission_manual);
                break;
            case RESTORE_FAILED:
                if (Settings.System.canWrite(this)) {
                    BrightnessLevels.restorePreviousBrightnessMode(this);
                } else {
                    openSettings(new Intent(Settings.ACTION_DISPLAY_SETTINGS), R.string.obsidian_restore_manual);
                }
                break;
            case PROTECTING:
            case USER_HOLD:
            case WAITING_SENSOR:
                observedLiveService = false;
                userStartFailed = false;
                startGraceUntil = 0;
                stopGraceUntil = SystemClock.elapsedRealtime() + STOP_GRACE_MS;
                AutoBrightnessService.stop(this);
                break;
            case SERVICE_ERROR:
            case OFF:
                if (AutoBrightnessManager.hasLightSensor(this)) requestStart();
                break;
            case UNCONFIRMED:
                // Read the current service/sensor facts; this action must not restart the controller.
            case STARTING:
            case STOPPING:
                break;
        }
        render();
    }

    private void requestStart() {
        observedLiveService = false;
        userStartFailed = false;
        settingsMessage = 0;
        startGraceUntil = SystemClock.elapsedRealtime() + START_GRACE_MS;
        AutoBrightnessService.start(this);
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, NOTIFICATION_REQUEST);
        }
    }

    private void openSettings(Intent intent, int fallback) {
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException unavailable) {
            settingsMessage = fallback;
        }
    }

    private void render() {
        boolean wanted = AutoBrightnessManager.isAutoEnabled(this);
        boolean permission = Settings.System.canWrite(this);
        boolean running = AutoBrightnessService.isRunning();
        boolean healthy = ProtectionServiceHealth.isServiceHealthy(this);
        boolean registered = ProtectionUiTelemetry.isSensorRegistered();
        boolean sensor = AutoBrightnessManager.hasLightSensor(this);
        boolean restorePending = !wanted && BrightnessLevels.hasPendingModeRestore(this);
        long now = SystemClock.elapsedRealtime();
        if (healthy) observedLiveService = true;

        if (startGraceUntil != 0 && (!wanted || healthy || now >= startGraceUntil)) {
            userStartFailed = !wanted && !healthy;
            startGraceUntil = 0;
        }
        if (!running || now >= stopGraceUntil) stopGraceUntil = 0;

        boolean manual = wanted && healthy && BrightnessLevels.getSystemBrightnessMode(this)
                == Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL;
        // This readback has no side effect on the controller's cached raw value.
        int raw = manual && registered
                ? Settings.System.getInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, -1)
                : -1;
        boolean hold = manual && AutoBrightnessManager.isUserHoldActive(this);
        boolean holdConfirmed = hold && raw >= 0 && raw == AutoBrightnessManager.getUserHoldRaw(this);
        boolean observedSample = ProtectionUiTelemetry.hasObservedSample();
        boolean control = permission && manual && registered && raw >= 0 && raw <= RAW_CAP
                && (hold ? holdConfirmed : observedSample);
        boolean reading = manual && registered && !observedSample;
        boolean failed = userStartFailed || (observedLiveService && !running);
        phase = ObsidianUiState.resolve(wanted, permission, sensor, restorePending,
                stopGraceUntil != 0, startGraceUntil != 0, control, hold, reading, failed);
        if (guideOpen) return;

        int title, detail = 0, indicator = 0, label;
        Icon icon = Icon.POWER;
        switch (phase) {
            case PERMISSION_REQUIRED:
                title = R.string.obsidian_permission;
                detail = R.string.obsidian_permission_desc;
                indicator = WARNING;
                label = R.string.obsidian_grant;
                icon = Icon.SETTINGS;
                break;
            case STARTING:
                title = R.string.obsidian_starting;
                label = R.string.obsidian_start_pending;
                icon = Icon.BUSY;
                break;
            case STOPPING:
                title = R.string.obsidian_stopping;
                label = R.string.obsidian_stop_pending;
                icon = Icon.BUSY;
                break;
            case PROTECTING:
                title = R.string.obsidian_protecting;
                label = R.string.obsidian_disable;
                break;
            case USER_HOLD:
                title = R.string.obsidian_hold;
                detail = R.string.obsidian_hold_desc;
                label = R.string.obsidian_disable;
                break;
            case WAITING_SENSOR:
                title = R.string.obsidian_waiting;
                label = R.string.obsidian_disable;
                break;
            case UNCONFIRMED:
                title = R.string.obsidian_unconfirmed;
                label = R.string.obsidian_check;
                icon = Icon.RETRY;
                break;
            case SERVICE_ERROR:
                title = R.string.obsidian_service_error;
                detail = sensor ? R.string.obsidian_service_error_desc : R.string.obsidian_sensor_error_desc;
                indicator = ERROR;
                label = sensor ? R.string.obsidian_retry : R.string.obsidian_check;
                icon = Icon.RETRY;
                break;
            case RESTORE_FAILED:
                title = R.string.obsidian_restore_error;
                detail = permission ? R.string.obsidian_restore_desc : R.string.obsidian_restore_manual;
                indicator = ERROR;
                label = permission ? R.string.obsidian_try_restore : R.string.obsidian_display_settings;
                icon = permission ? Icon.RETRY : Icon.SETTINGS;
                break;
            default:
                title = R.string.obsidian_off;
                detail = R.string.obsidian_off_desc;
                label = R.string.obsidian_enable;
        }

        boolean controlling = ObsidianUiState.isControlling(phase);
        float lux = wanted && healthy && registered ? ProtectionUiTelemetry.getFreshLux() : -1f;
        dashboard.show(title, settingsMessage == 0 ? detail : settingsMessage,
                indicator, label, icon, controlling, controlling ? raw : -1, lux);
    }
}
