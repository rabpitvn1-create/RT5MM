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
import android.provider.Settings;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/** One actionable UI control. Permissions are requested from the same button. */
public final class MainActivity extends Activity {
    private static final int NOTIFICATION_REQUEST = 1001;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refreshTask = new Runnable() {
        @Override public void run() {
            updateState();
            handler.postDelayed(this, 2000L);
        }
    };
    private Button toggle;
    private TextView status;
    private TextView details;
    private boolean awaitingPermission = false;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(0xFFFFFCF7);
        root.setPadding(dp(26), dp(26), dp(26), dp(26));
        TextView title = new TextView(this);
        title.setText(getString(R.string.app_name));
        title.setTextSize(27);
        title.setTextColor(0xFF1D1D1F);
        title.setGravity(Gravity.CENTER);
        root.addView(title, new LinearLayout.LayoutParams(-1, -2));
        details = new TextView(this);
        details.setTextSize(15);
        details.setTextColor(0xFF66666A);
        details.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams description = new LinearLayout.LayoutParams(-1, -2);
        description.topMargin = dp(14);
        description.bottomMargin = dp(24);
        root.addView(details, description);
        toggle = new Button(this);
        toggle.setTextSize(18);
        toggle.setAllCaps(false);
        root.addView(toggle, new LinearLayout.LayoutParams(-1, dp(60)));
        toggle.setOnClickListener(v -> toggleProtection());
        status = new TextView(this);
        status.setTextSize(13);
        status.setGravity(Gravity.CENTER);
        status.setTextColor(0xFF6B6B70);
        LinearLayout.LayoutParams note = new LinearLayout.LayoutParams(-1,-2);
        note.topMargin = dp(18);
        root.addView(status, note);
        setContentView(root);
        updateState();
    }

    @Override protected void onResume() {
        super.onResume();
        if (awaitingPermission) {
            awaitingPermission = false;
            if (Settings.System.canWrite(this)) startProtection();
        }
        handler.removeCallbacks(refreshTask);
        handler.post(refreshTask);
    }

    @Override protected void onPause() {
        handler.removeCallbacks(refreshTask);
        super.onPause();
    }

    private void toggleProtection() {
        if (AutoBrightnessManager.isAutoEnabled(this)) {
            AutoBrightnessService.stop(this);
            updateState();
            return;
        }
        if (!AutoBrightnessManager.hasLightSensor(this)) {
            status.setText(getString(R.string.toast_sensor_unavailable));
            return;
        }
        if (!Settings.System.canWrite(this)) {
            awaitingPermission = true;
            Intent intent = new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,
                    Uri.parse("package:" + getPackageName()));
            try { startActivity(intent); }
            catch (ActivityNotFoundException | SecurityException ex) {
                awaitingPermission = false;
                status.setText("Please grant Modify system settings in Android app settings.");
            }
            return;
        }
        startProtection();
    }

    private void startProtection() {
        AutoBrightnessService.start(this);
        if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    NOTIFICATION_REQUEST);
        }
        updateState();
        if (!AutoBrightnessManager.isAutoEnabled(this)) {
            Toast.makeText(this, "Unable to start screen protection", Toast.LENGTH_SHORT).show();
        }
    }

    private void updateState() {
        if (toggle == null) return;
        boolean enabled = AutoBrightnessManager.isAutoEnabled(this);
        toggle.setText(enabled ? R.string.action_turn_protection_off
                               : R.string.action_turn_protection_on);
        toggle.setEnabled(AutoBrightnessManager.hasLightSensor(this));
        float lux = AutoBrightnessManager.getLastLux(this);
        int raw = BrightnessLevels.getSystemRaw(this, -1);
        long latency = ProtectionLatencyProbe.getLatestLatencyMs();
        details.setText(enabled ? "Adaptive Core active. Android auto brightness: MANUAL"
                : "Protection is off. Android's previous brightness mode is restored.");
        status.setText((lux >= 0 ? String.format(java.util.Locale.US, "Last ambient: %.1f lx", lux)
                : "Waiting for fresh sensor data")
                + (raw >= 0 ? " | raw " + raw : "")
                + (latency >= 0 ? " | sensor-to-write " + latency + " ms" : "")
                + "\nManual changes are held until ambient light changes substantially."
                + "\nK90 brightness scale is not yet verified.");
    }
    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
