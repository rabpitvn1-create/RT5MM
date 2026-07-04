package com.ericko.redmiscreenbrightness;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    private static final int REQUEST_POST_NOTIFICATIONS = 1001;

    private TextView statusText;
    private Button toggleButton;
    private final Handler statusHandler = new Handler(Looper.getMainLooper());
    private final Runnable statusRefreshRunnable = new Runnable() {
        @Override
        public void run() {
            refreshStatus();
            statusHandler.postDelayed(this, 1000L);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        int pad = dp(24);
        root.setPadding(pad, dp(72), pad, pad);

        TextView hyperOsLogo = new TextView(this);
        hyperOsLogo.setText("Xiaomi HyperOS");
        hyperOsLogo.setTextSize(28);
        hyperOsLogo.setTypeface(Typeface.DEFAULT_BOLD);
        hyperOsLogo.setTextColor(0xFFFF6900);
        hyperOsLogo.setGravity(Gravity.CENTER);
        root.addView(hyperOsLogo, new LinearLayout.LayoutParams(-1, -2));

        TextView title = new TextView(this);
        title.setText("Screen Protection");
        title.setTextSize(24);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(-1, -2);
        titleParams.setMargins(0, dp(40), 0, 0);
        root.addView(title, titleParams);

        statusText = new TextView(this);
        statusText.setTextSize(16);
        statusText.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(-1, -2);
        statusParams.setMargins(0, dp(20), 0, dp(20));
        root.addView(statusText, statusParams);

        toggleButton = new Button(this);
        toggleButton.setTextSize(18);
        toggleButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleProtection();
            }
        });
        root.addView(toggleButton, new LinearLayout.LayoutParams(-1, dp(64)));

        setContentView(root);
        refreshStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (AutoBrightnessManager.isAutoEnabled(this)) {
            AutoBrightnessService.start(this);
        }
        BrightnessLogManager.appendSnapshot(this, "MAIN_RESUME", AutoBrightnessManager.getLastLux(this));
        statusHandler.removeCallbacks(statusRefreshRunnable);
        statusHandler.post(statusRefreshRunnable);
    }

    @Override
    protected void onPause() {
        statusHandler.removeCallbacks(statusRefreshRunnable);
        super.onPause();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_POST_NOTIFICATIONS) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                    && grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Notification permission granted", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "Notification permission denied. Foreground service may still run without drawer notification.", Toast.LENGTH_LONG).show();
            }
            BrightnessLogManager.appendSnapshot(this, "NOTIFICATION_PERMISSION_RESULT", AutoBrightnessManager.getLastLux(this));
            refreshStatus();
        }
    }

    private void toggleProtection() {
        if (!Settings.System.canWrite(this)) {
            BrightnessLogManager.appendSnapshot(this, "WRITE_SETTINGS_MISSING_BUTTON", AutoBrightnessManager.getLastLux(this));
            Toast.makeText(this, "Grant modify system settings permission first", Toast.LENGTH_SHORT).show();
            openPermissionScreen();
            refreshStatus();
            return;
        }

        boolean enabled = AutoBrightnessManager.isAutoEnabled(this);
        AutoBrightnessManager.Mode mode = AutoBrightnessManager.getSavedMode(this);
        if (enabled && mode == AutoBrightnessManager.Mode.MANUAL_OVERRIDE) {
            AutoBrightnessService.refresh(this);
            BrightnessLogManager.appendSnapshot(this, "PROTECTION_RESTART_FROM_BUTTON", AutoBrightnessManager.getLastLux(this));
            Toast.makeText(this, "Screen Protection restarted", Toast.LENGTH_SHORT).show();
            refreshStatus();
            return;
        }

        if (enabled) {
            AutoBrightnessService.stop(this);
            BrightnessLogManager.appendSnapshot(this, "PROTECTION_DISABLED", AutoBrightnessManager.getLastLux(this));
            Toast.makeText(this, "Screen Protection off", Toast.LENGTH_SHORT).show();
            refreshStatus();
            return;
        }
        enableProtection();
    }

    private void enableProtection() {
        if (!AutoBrightnessManager.hasLightSensor(this)) {
            AutoBrightnessManager.markUnavailable(this);
            BrightnessLogManager.appendSnapshot(this, "PROTECTION_UNAVAILABLE", AutoBrightnessManager.getLastLux(this));
            Toast.makeText(this, "Light sensor unavailable", Toast.LENGTH_SHORT).show();
            refreshStatus();
            return;
        }

        if (!Settings.System.canWrite(this)) {
            BrightnessLogManager.appendSnapshot(this, "WRITE_SETTINGS_MISSING", AutoBrightnessManager.getLastLux(this));
            Toast.makeText(this, "Grant modify system settings permission first", Toast.LENGTH_SHORT).show();
            openPermissionScreen();
            refreshStatus();
            return;
        }

        requestNotificationPermissionIfNeeded();
        AutoBrightnessService.start(this);
        BrightnessLogManager.appendSnapshot(this, "PROTECTION_ENABLED", AutoBrightnessManager.getLastLux(this));
        Toast.makeText(this, "Screen Protection on", Toast.LENGTH_SHORT).show();
        refreshStatus();
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, REQUEST_POST_NOTIFICATIONS);
        }
    }

    private void refreshStatus() {
        boolean canWrite = Settings.System.canWrite(this);
        boolean enabled = AutoBrightnessManager.isAutoEnabled(this);
        AutoBrightnessManager.Mode mode = AutoBrightnessManager.getSavedMode(this);
        int currentRaw = BrightnessLevels.getSystemRaw(this, -1);
        int percent = BrightnessLevels.getCurrentPercent(this);
        String notificationStatus = getNotificationPermissionStatus();
        BrightnessLogManager.logSnapshotIfChanged(this, "MAIN_STATUS_REFRESH", AutoBrightnessManager.getLastLux(this));

        if (!canWrite) {
            toggleButton.setText("Grant Permission");
        } else if (enabled && mode == AutoBrightnessManager.Mode.MANUAL_OVERRIDE) {
            toggleButton.setText("Restart Protection");
        } else {
            toggleButton.setText(enabled ? "Turn Protection Off" : "Turn Protection On");
        }

        statusText.setText(
                "Permission: " + (canWrite ? "granted" : "missing")
                        + "\nNotification: " + notificationStatus
                        + "\nCurrent level: " + percent + "% / raw " + currentRaw
                        + "\n" + AutoBrightnessManager.getStatusText(this)
        );
    }

    private String getNotificationPermissionStatus() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return "not required";
        }
        return checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
                ? "granted"
                : "missing or denied";
    }

    private void openPermissionScreen() {
        Intent intent = new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS);
        intent.setData(Uri.parse("package:" + getPackageName()));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
