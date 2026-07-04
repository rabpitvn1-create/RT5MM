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
    private TextView masterAdjustText;
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
        root.setPadding(pad, dp(64), pad, pad);

        TextView hyperOsLogo = new TextView(this);
        hyperOsLogo.setText("Xiaomi HyperOS");
        hyperOsLogo.setTextSize(28);
        hyperOsLogo.setTypeface(Typeface.DEFAULT_BOLD);
        hyperOsLogo.setTextColor(0xFFFF6900);
        hyperOsLogo.setGravity(Gravity.CENTER);
        root.addView(hyperOsLogo, new LinearLayout.LayoutParams(-1, -2));

        TextView title = new TextView(this);
        title.setText("Redmi Screen Brightness");
        title.setTextSize(22);
        title.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(-1, -2);
        titleParams.setMargins(0, dp(48), 0, 0);
        root.addView(title, titleParams);

        statusText = new TextView(this);
        statusText.setTextSize(16);
        statusText.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(-1, -2);
        statusParams.setMargins(0, dp(16), 0, dp(8));
        root.addView(statusText, statusParams);

        masterAdjustText = new TextView(this);
        masterAdjustText.setTextSize(14);
        masterAdjustText.setGravity(Gravity.CENTER);
        root.addView(masterAdjustText, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout adjustRow = new LinearLayout(this);
        adjustRow.setOrientation(LinearLayout.HORIZONTAL);
        adjustRow.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams adjustRowParams = new LinearLayout.LayoutParams(-1, -2);
        adjustRowParams.setMargins(0, dp(8), 0, dp(8));

        Button dimmerButton = new Button(this);
        dimmerButton.setText("Auto dimmer");
        dimmerButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                changeMasterAdjust(-2);
            }
        });
        adjustRow.addView(dimmerButton, new LinearLayout.LayoutParams(0, -2, 1f));

        Button brighterButton = new Button(this);
        brighterButton.setText("Auto brighter");
        brighterButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                changeMasterAdjust(2);
            }
        });
        adjustRow.addView(brighterButton, new LinearLayout.LayoutParams(0, -2, 1f));

        root.addView(adjustRow, adjustRowParams);

        Button permissionButton = new Button(this);
        permissionButton.setText("Open modify system settings permission");
        permissionButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openPermissionScreen();
            }
        });
        root.addView(permissionButton, new LinearLayout.LayoutParams(-1, -2));

        Button enableAutoButton = new Button(this);
        enableAutoButton.setText("Enable Auto Brightness");
        enableAutoButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                enableAutoBrightness();
            }
        });
        root.addView(enableAutoButton, new LinearLayout.LayoutParams(-1, -2));

        Button disableAutoButton = new Button(this);
        disableAutoButton.setText("Disable Auto Brightness");
        disableAutoButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                AutoBrightnessService.stop(MainActivity.this);
                BrightnessLogManager.appendSnapshot(MainActivity.this, "REDMI_AUTO_DISABLED", AutoBrightnessManager.getLastLux(MainActivity.this));
                Toast.makeText(MainActivity.this, "Auto Brightness disabled", Toast.LENGTH_SHORT).show();
                refreshStatus();
            }
        });
        root.addView(disableAutoButton, new LinearLayout.LayoutParams(-1, -2));

        Button testButton = new Button(this);
        testButton.setText("Test set 30% raw " + BrightnessLevels.getRawForPercent(this, 30));
        testButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean ok = BrightnessTileService.applyBrightness(MainActivity.this, 30);
                if (ok) {
                    AutoBrightnessManager.recordManualOverride(MainActivity.this);
                    AutoBrightnessService.refresh(MainActivity.this);
                    BrightnessLogManager.appendSnapshot(MainActivity.this, "TEST_SET_30_PERCENT", AutoBrightnessManager.getLastLux(MainActivity.this));
                }
                Toast.makeText(MainActivity.this, ok ? "Brightness set to 30%" : "Permission missing or blocked", Toast.LENGTH_SHORT).show();
                refreshStatus();
            }
        });
        root.addView(testButton, new LinearLayout.LayoutParams(-1, -2));

        Button exportLogButton = new Button(this);
        exportLogButton.setText("Export brightness diagnostic log");
        exportLogButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                exportBrightnessLog();
            }
        });
        root.addView(exportLogButton, new LinearLayout.LayoutParams(-1, -2));

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

    private void enableAutoBrightness() {
        if (!AutoBrightnessManager.hasLightSensor(this)) {
            AutoBrightnessManager.markUnavailable(this);
            BrightnessLogManager.appendSnapshot(this, "REDMI_AUTO_UNAVAILABLE", AutoBrightnessManager.getLastLux(this));
            Toast.makeText(this, "Auto Brightness unavailable", Toast.LENGTH_SHORT).show();
            refreshStatus();
            return;
        }

        if (!Settings.System.canWrite(this)) {
            BrightnessLogManager.appendSnapshot(this, "WRITE_SETTINGS_MISSING", AutoBrightnessManager.getLastLux(this));
            Toast.makeText(this, "Grant modify system settings permission first", Toast.LENGTH_SHORT).show();
            openPermissionScreen();
            return;
        }

        requestNotificationPermissionIfNeeded();
        AutoBrightnessService.start(this);
        BrightnessLogManager.appendSnapshot(this, "REDMI_AUTO_ENABLED", AutoBrightnessManager.getLastLux(this));
        Toast.makeText(this, "Auto Brightness enabled", Toast.LENGTH_SHORT).show();
        refreshStatus();
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, REQUEST_POST_NOTIFICATIONS);
        }
    }

    private void refreshStatus() {
        boolean granted = Settings.System.canWrite(this);
        int currentRaw = BrightnessLevels.getSystemRaw(this, -1);
        int percent = BrightnessLevels.getCurrentPercent(this);
        String notificationStatus = getNotificationPermissionStatus();
        BrightnessLogManager.logSnapshotIfChanged(this, "MAIN_STATUS_REFRESH", AutoBrightnessManager.getLastLux(this));
        updateMasterAdjustText();

        statusText.setText(
                (granted ? "Permission: granted" : "Permission: missing")
                        + "\nNotification: " + notificationStatus
                        + "\nCurrent level: " + percent + "% / raw " + currentRaw
                        + "\n" + AutoBrightnessManager.getStatusText(this)
        );
    }

    private void updateMasterAdjustText() {
        if (masterAdjustText == null) {
            return;
        }
        int adjust = BrightnessLevels.getMasterAdjust(this);
        String adjustText = adjust > 0 ? "+" + adjust : String.valueOf(adjust);
        masterAdjustText.setText("Master brightness adjust: " + adjustText + " raw");
    }

    private void changeMasterAdjust(int delta) {
        int adjust = BrightnessLevels.changeMasterAdjust(this, delta);
        BrightnessLogManager.appendSnapshot(this, "MASTER_ADJUST_CHANGED_" + adjust, AutoBrightnessManager.getLastLux(this));
        if (AutoBrightnessManager.isAutoEnabled(this)) {
            AutoBrightnessService.refresh(this);
        }
        refreshStatus();
    }

    private String getNotificationPermissionStatus() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return "not required";
        }
        return checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
                ? "granted"
                : "missing or denied";
    }

    private void exportBrightnessLog() {
        String logText = BrightnessLogManager.exportText(this);
        Intent sendIntent = new Intent(Intent.ACTION_SEND);
        sendIntent.setType("text/plain");
        sendIntent.putExtra(Intent.EXTRA_SUBJECT, "Redmi Screen Brightness diagnostic log");
        sendIntent.putExtra(Intent.EXTRA_TEXT, logText);
        startActivity(Intent.createChooser(sendIntent, "Export brightness diagnostic log"));
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
