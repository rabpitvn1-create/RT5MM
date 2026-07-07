package com.ericko.redmiscreenbrightness;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    private static final int REQUEST_POST_NOTIFICATIONS = 1001;
    private static final long STATUS_REFRESH_MS = 1000L;

    private TextView setupText;
    private TextView statusText;
    private Button toggleButton;
    private Button notificationButton;
    private Button batteryButton;
    private Button hyperOsButton;
    private boolean diagnosticVisible = false;

    private final Handler statusHandler = new Handler(Looper.getMainLooper());
    private final Runnable statusRefreshRunnable = new Runnable() {
        @Override
        public void run() {
            refreshStatus();
            statusHandler.postDelayed(this, STATUS_REFRESH_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        int pad = dp(24);
        root.setPadding(pad, dp(56), pad, pad);

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
        titleParams.setMargins(0, dp(32), 0, 0);
        root.addView(title, titleParams);

        setupText = new TextView(this);
        setupText.setTextSize(14);
        setupText.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams setupParams = new LinearLayout.LayoutParams(-1, -2);
        setupParams.setMargins(0, dp(20), 0, dp(12));
        root.addView(setupText, setupParams);

        View.OnLongClickListener diagnosticLongClick = new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                toggleDiagnosticMode();
                return true;
            }
        };

        statusText = new TextView(this);
        statusText.setTextSize(16);
        statusText.setGravity(Gravity.CENTER);
        statusText.setOnLongClickListener(diagnosticLongClick);
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(-1, -2);
        statusParams.setMargins(0, dp(8), 0, dp(16));
        root.addView(statusText, statusParams);

        toggleButton = new Button(this);
        toggleButton.setTextSize(18);
        toggleButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleProtection();
            }
        });
        toggleButton.setOnLongClickListener(diagnosticLongClick);
        addButton(root, toggleButton, 64);

        notificationButton = new Button(this);
        notificationButton.setVisibility(View.GONE);
        notificationButton.setText("Grant Notification Permission");
        notificationButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openNotificationSetup();
            }
        });
        addButton(root, notificationButton, 52);

        batteryButton = new Button(this);
        batteryButton.setVisibility(View.GONE);
        batteryButton.setText("Disable Battery Optimization");
        batteryButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openBatterySetup();
            }
        });
        addButton(root, batteryButton, 52);

        hyperOsButton = new Button(this);
        hyperOsButton.setVisibility(View.GONE);
        hyperOsButton.setText("Open HyperOS Background Setup");
        hyperOsButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openHyperOsSetup();
            }
        });
        addButton(root, hyperOsButton, 52);

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
                Toast.makeText(this, "Notification permission denied. Protection can run, but visibility is limited.", Toast.LENGTH_LONG).show();
            }
            BrightnessLogManager.appendSnapshot(this, "NOTIFICATION_PERMISSION_RESULT", AutoBrightnessManager.getLastLux(this));
            refreshStatus();
        }
    }

    private void toggleProtection() {
        if (!Settings.System.canWrite(this)) {
            BrightnessLogManager.appendSnapshot(this, "WRITE_SETTINGS_MISSING_BUTTON", AutoBrightnessManager.getLastLux(this));
            Toast.makeText(this, "Grant modify system settings first", Toast.LENGTH_SHORT).show();
            openPermissionScreen();
            refreshStatus();
            return;
        }

        boolean enabled = AutoBrightnessManager.isAutoEnabled(this);
        AutoBrightnessManager.Mode mode = AutoBrightnessManager.getSavedMode(this);
        if (enabled && mode == AutoBrightnessManager.Mode.USER_HOLD) {
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
            Toast.makeText(this, "Grant modify system settings first", Toast.LENGTH_SHORT).show();
            openPermissionScreen();
            refreshStatus();
            return;
        }

        if (!isNotificationPermissionGranted()) {
            BrightnessLogManager.appendSnapshot(this, "NOTIFICATION_PERMISSION_MISSING", AutoBrightnessManager.getLastLux(this));
            requestNotificationPermissionIfNeeded();
            refreshStatus();
            return;
        }
        if (!isBatteryOptimizationIgnored()) {
            BrightnessLogManager.appendSnapshot(this, "BATTERY_OPTIMIZATION_LIMITED", AutoBrightnessManager.getLastLux(this));
            Toast.makeText(this, "Allow unrestricted battery first", Toast.LENGTH_LONG).show();
            openBatterySetup();
            refreshStatus();
            return;
        }

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
        boolean sensorOk = AutoBrightnessManager.hasLightSensor(this);
        boolean notificationOk = isNotificationPermissionGranted();
        boolean batteryOk = isBatteryOptimizationIgnored();
        int percent = BrightnessLevels.getCurrentPercent(this);
        String setupState = getSetupState(canWrite, sensorOk, notificationOk, batteryOk);
        BrightnessLogManager.logSnapshotIfChanged(this, "MAIN_STATUS_REFRESH", AutoBrightnessManager.getLastLux(this));

        if (!sensorOk) {
            toggleButton.setText("Protection Unavailable");
        } else if (!canWrite || !notificationOk || !batteryOk) {
            toggleButton.setText("Set Up Protection");
        } else if (enabled && mode == AutoBrightnessManager.Mode.USER_HOLD) {
            toggleButton.setText("Restart Protection");
        } else {
            toggleButton.setText(enabled ? "Turn Protection Off" : "Turn Protection On");
        }

        notificationButton.setVisibility(View.GONE);
        batteryButton.setVisibility(View.GONE);
        hyperOsButton.setVisibility(View.GONE);

        if (diagnosticVisible) {
            setupText.setText("Diagnostic mode · long-press status or button to hide");
            statusText.setText(AutoBrightnessManager.getDiagnosticText(this));
        } else {
            setupText.setText(getSetupMessage(canWrite, sensorOk, notificationOk, batteryOk, setupState));
            statusText.setText(getMainStatusText(enabled, mode, sensorOk, percent));
        }
    }

    private String getMainStatusText(boolean enabled, AutoBrightnessManager.Mode mode, boolean sensorOk, int percent) {
        String state;
        if (!sensorOk || mode == AutoBrightnessManager.Mode.UNAVAILABLE) {
            state = "Protection unavailable";
        } else if (enabled && mode == AutoBrightnessManager.Mode.USER_HOLD) {
            state = "Holding your brightness";
        } else if (enabled) {
            state = "Protecting your screen";
        } else {
            state = "Protection off";
        }

        return state
                + "\nLight: " + getLightText()
                + "\nBrightness: " + percent + "%"
                + "\nLong-press for diagnostics";
    }

    private String getLightText() {
        float lux = AutoBrightnessManager.getLastLux(this);
        if (lux < 0f) {
            return "unknown";
        }
        return new ProtectionPolicy().getProfileName(lux);
    }

    private String getSetupMessage(boolean canWrite, boolean sensorOk, boolean notificationOk, boolean batteryOk, String setupState) {
        if (!sensorOk) {
            return "Light sensor unavailable";
        }
        if (!canWrite) {
            return "Setup needed: allow modify system settings";
        }
        if (!notificationOk) {
            return "Setup needed: allow notifications";
        }
        if (!batteryOk) {
            return "Setup needed: allow unrestricted battery";
        }
        return "Setup: " + setupState;
    }

    private void toggleDiagnosticMode() {
        diagnosticVisible = !diagnosticVisible;
        BrightnessLogManager.appendSnapshot(this, diagnosticVisible ? "DIAGNOSTIC_MODE_ON" : "DIAGNOSTIC_MODE_OFF", AutoBrightnessManager.getLastLux(this));
        Toast.makeText(this, diagnosticVisible ? "Diagnostic mode on" : "Diagnostic mode off", Toast.LENGTH_SHORT).show();
        refreshStatus();
    }

    private String getSetupState(boolean canWrite, boolean sensorOk, boolean notificationOk, boolean batteryOk) {
        if (!canWrite || !sensorOk) {
            return "Blocked";
        }
        if (!notificationOk || !batteryOk) {
            return "Limited";
        }
        return "Ready";
    }

    private String setupLine(boolean ok, String label) {
        return (ok ? "[OK] " : "[NEED] ") + label + "\n";
    }

    private boolean isNotificationPermissionGranted() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return true;
        }
        return checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean isBatteryOptimizationIgnored() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return true;
        }
        PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        return powerManager == null || powerManager.isIgnoringBatteryOptimizations(getPackageName());
    }

    private void openPermissionScreen() {
        Intent intent = new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS);
        intent.setData(Uri.parse("package:" + getPackageName()));
        if (!tryStartActivity(intent)) {
            openAppDetails();
        }
    }

    private void openNotificationSetup() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, REQUEST_POST_NOTIFICATIONS);
            return;
        }
        openAppNotificationSettings();
    }

    private void openAppNotificationSettings() {
        Intent intent;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            intent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
            intent.putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
        } else {
            intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(Uri.parse("package:" + getPackageName()));
        }
        if (!tryStartActivity(intent)) {
            openAppDetails();
        }
    }

    private void openBatterySetup() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            openAppDetails();
            return;
        }
        if (!isBatteryOptimizationIgnored()) {
            Intent requestIntent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            requestIntent.setData(Uri.parse("package:" + getPackageName()));
            if (tryStartActivity(requestIntent)) {
                return;
            }
        }
        Intent settingsIntent = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
        if (!tryStartActivity(settingsIntent)) {
            openAppDetails();
        }
    }

    private void openHyperOsSetup() {
        Toast.makeText(this, "Enable Autostart, No restrictions, and lock the app in Recents.", Toast.LENGTH_LONG).show();
        BrightnessLogManager.appendSnapshot(this, "OPEN_HYPEROS_BACKGROUND_SETUP", AutoBrightnessManager.getLastLux(this));

        Intent miuiIntent = new Intent("miui.intent.action.APP_PERM_EDITOR");
        miuiIntent.setComponent(new ComponentName("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity"));
        miuiIntent.putExtra("extra_pkgname", getPackageName());
        if (tryStartActivity(miuiIntent)) {
            return;
        }

        Intent miuiFallback = new Intent("miui.intent.action.APP_PERM_EDITOR");
        miuiFallback.putExtra("extra_pkgname", getPackageName());
        if (tryStartActivity(miuiFallback)) {
            return;
        }

        openAppDetails();
    }

    private void openAppDetails() {
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        intent.setData(Uri.parse("package:" + getPackageName()));
        tryStartActivity(intent);
    }

    private boolean tryStartActivity(Intent intent) {
        try {
            startActivity(intent);
            return true;
        } catch (ActivityNotFoundException e) {
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    private void addButton(LinearLayout root, Button button, int heightDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(heightDp));
        params.setMargins(0, dp(8), 0, 0);
        root.addView(button, params);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
