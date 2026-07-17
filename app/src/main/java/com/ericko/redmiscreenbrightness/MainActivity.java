package com.ericko.redmiscreenbrightness;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
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
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public final class MainActivity extends Activity {
    private static final int REQUEST_NOTIFICATIONS = 1001;
    private static final long REFRESH_MS = 1200L;
    private static final int ORANGE = 0xFFFF6900;
    private static final int INK = 0xFF1D1D1F;
    private static final int MUTED = 0xFF66666A;
    private static final int CARD = 0xFFFFFFFF;
    private static final int BACKGROUND = 0xFFFFFCF7;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView stateText;
    private TextView detailText;
    private TextView setupText;
    private Button primaryButton;
    private Button writeButton;
    private Button usageAccessButton;
    private Button notificationButton;
    private Button batteryButton;
    private Button hyperOsButton;
    private Button diagnosticsButton;
    private boolean diagnosticsVisible;

    private final Runnable refreshRunnable = new Runnable() {
        @Override
        public void run() {
            refresh();
            handler.postDelayed(this, REFRESH_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildContent());
        refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (AutoBrightnessManager.isAutoEnabled(this)) AutoBrightnessService.start(this);
        handler.removeCallbacks(refreshRunnable);
        handler.post(refreshRunnable);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(refreshRunnable);
        super.onPause();
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_NOTIFICATIONS) refresh();
    }

    private View buildContent() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BACKGROUND);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(22), dp(34), dp(22), dp(34));
        scroll.addView(root, new ScrollView.LayoutParams(-1, -2));

        TextView brand = text(getString(R.string.brand_hyperos), 14, ORANGE, true);
        brand.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(brand, matchWrap());

        TextView title = text(getString(R.string.app_name), 30, INK, true);
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams titleParams = matchWrap();
        titleParams.setMargins(0, dp(8), 0, dp(6));
        root.addView(title, titleParams);

        TextView subtitle = text(
                getString(R.string.app_subtitle),
                14,
                MUTED,
                false);
        subtitle.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(subtitle, matchWrap());

        LinearLayout statusCard = card();
        LinearLayout.LayoutParams cardParams = matchWrap();
        cardParams.setMargins(0, dp(24), 0, 0);
        root.addView(statusCard, cardParams);

        stateText = text("", 23, INK, true);
        statusCard.addView(stateText, matchWrap());

        detailText = text("", 15, MUTED, false);
        LinearLayout.LayoutParams detailParams = matchWrap();
        detailParams.setMargins(0, dp(10), 0, 0);
        statusCard.addView(detailText, detailParams);

        primaryButton = button("");
        primaryButton.setOnClickListener(v -> toggleProtection());
        LinearLayout.LayoutParams primaryParams = matchHeight(58);
        primaryParams.setMargins(0, dp(18), 0, 0);
        root.addView(primaryButton, primaryParams);

        LinearLayout setupCard = card();
        LinearLayout.LayoutParams setupCardParams = matchWrap();
        setupCardParams.setMargins(0, dp(18), 0, 0);
        root.addView(setupCard, setupCardParams);

        TextView setupTitle = text(getString(R.string.setup_title), 18, INK, true);
        setupCard.addView(setupTitle, matchWrap());
        setupText = text("", 14, MUTED, false);
        LinearLayout.LayoutParams setupParams = matchWrap();
        setupParams.setMargins(0, dp(8), 0, dp(8));
        setupCard.addView(setupText, setupParams);

        writeButton = secondaryButton(
                getString(R.string.action_allow_brightness), v -> openWriteSettings());
        usageAccessButton = secondaryButton(
                getString(R.string.action_allow_usage_access), v -> openUsageAccessSettings());
        notificationButton = secondaryButton(
                getString(R.string.action_allow_notifications), v -> requestNotifications());
        batteryButton = secondaryButton(
                getString(R.string.action_open_battery_settings), v -> openBatterySettings());
        hyperOsButton = secondaryButton(
                getString(R.string.action_open_hyperos_settings), v -> openHyperOsSettings());
        setupCard.addView(writeButton, matchHeight(48));
        setupCard.addView(usageAccessButton, matchHeight(48));
        setupCard.addView(notificationButton, matchHeight(48));
        setupCard.addView(batteryButton, matchHeight(48));
        setupCard.addView(hyperOsButton, matchHeight(48));

        diagnosticsButton = secondaryButton(getString(R.string.action_show_diagnostics), v -> {
            diagnosticsVisible = !diagnosticsVisible;
            refresh();
        });
        LinearLayout.LayoutParams diagnosticParams = matchHeight(50);
        diagnosticParams.setMargins(0, dp(14), 0, 0);
        root.addView(diagnosticsButton, diagnosticParams);

        Button shareButton = secondaryButton(
                getString(R.string.action_share_diagnostics), v -> shareDiagnostics());
        root.addView(shareButton, matchHeight(50));

        TextView footer = text(
                getString(R.string.app_footer),
                12,
                MUTED,
                false);
        footer.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams footerParams = matchWrap();
        footerParams.setMargins(0, dp(18), 0, 0);
        root.addView(footer, footerParams);

        return scroll;
    }

    private void toggleProtection() {
        if (AutoBrightnessManager.isAutoEnabled(this)) {
            AutoBrightnessService.stop(this);
            Toast.makeText(this, R.string.toast_protection_off, Toast.LENGTH_SHORT).show();
            refresh();
            return;
        }
        if (!AutoBrightnessManager.hasLightSensor(this)) {
            AutoBrightnessManager.markUnavailable(this);
            Toast.makeText(this, R.string.toast_sensor_unavailable, Toast.LENGTH_LONG).show();
            refresh();
            return;
        }
        if (!Settings.System.canWrite(this)) {
            openWriteSettings();
            return;
        }

        AutoBrightnessService.start(this);
        if (!notificationsGranted()) requestNotifications();
        Toast.makeText(this, R.string.toast_protection_on, Toast.LENGTH_SHORT).show();
        refresh();
    }

    private void refresh() {
        boolean enabled = AutoBrightnessManager.isAutoEnabled(this);
        AutoBrightnessManager.Mode mode = AutoBrightnessManager.getSavedMode(this);
        boolean sensor = AutoBrightnessManager.hasLightSensor(this);
        boolean write = Settings.System.canWrite(this);
        boolean usageAccess = ForegroundAppTracker.hasUsageAccess(this);
        boolean notification = notificationsGranted();
        boolean battery = batteryOptimizationIgnored();
        float lux = AutoBrightnessManager.getLastLux(this);
        int raw = BrightnessLevels.getSystemRaw(this, -1);
        int percent = raw < 0 ? -1 : BrightnessLevels.getPercentForRaw(raw);

        if (!sensor || mode == AutoBrightnessManager.Mode.UNAVAILABLE) {
            stateText.setText(R.string.state_unavailable);
        } else if (enabled && mode == AutoBrightnessManager.Mode.USER_HOLD) {
            stateText.setText(R.string.state_user_hold);
        } else if (enabled) {
            stateText.setText(R.string.state_protecting);
        } else {
            stateText.setText(R.string.state_off);
        }

        String luxText = lux < 0f
                ? getString(R.string.learning_room)
                : getString(
                        R.string.lux_profile_format,
                        lux,
                        ProtectionCurveEngine.getProfileName(this, lux));
        String brightnessText = raw < 0
                ? getString(R.string.brightness_unavailable)
                : getString(R.string.brightness_format, percent, raw);
        detailText.setText(
                luxText
                        + "\n" + brightnessText
                        + "\n" + ProtectionServiceHealth.getMainHealthText(this)
                        + (diagnosticsVisible
                        ? "\n\n" + AutoBrightnessManager.getDiagnosticText(this)
                        + "\n\n" + ProtectionServiceHealth.getDiagnosticText(this)
                        : ""));

        primaryButton.setText(enabled
                ? R.string.action_turn_protection_off
                : R.string.action_turn_protection_on);
        primaryButton.setEnabled(sensor);
        diagnosticsButton.setText(diagnosticsVisible
                ? R.string.action_hide_diagnostics
                : R.string.action_show_diagnostics);

        setupText.setText(
                statusLine(write, getString(R.string.setup_brightness_control), true)
                        + statusLine(sensor, getString(R.string.setup_light_sensor), true)
                        + statusLine(
                                usageAccess,
                                getString(R.string.setup_usage_access),
                                false)
                        + statusLine(notification, getString(R.string.setup_notifications), false)
                        + statusLine(battery, getString(R.string.setup_battery_optimization), false)
                        + getString(R.string.setup_note));
        writeButton.setVisibility(write ? View.GONE : View.VISIBLE);
        usageAccessButton.setVisibility(usageAccess ? View.GONE : View.VISIBLE);
        notificationButton.setVisibility(notification ? View.GONE : View.VISIBLE);
        batteryButton.setVisibility(battery ? View.GONE : View.VISIBLE);
        hyperOsButton.setVisibility(View.VISIBLE);
    }

    private String statusLine(boolean ok, String label, boolean required) {
        return getString(
                ok ? R.string.setup_item_ready
                        : (required ? R.string.setup_item_required : R.string.setup_item_recommended),
                label);
    }

    private void requestNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, REQUEST_NOTIFICATIONS);
        }
    }

    private void openWriteSettings() {
        Intent intent = new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS);
        intent.setData(Uri.parse("package:" + getPackageName()));
        if (!tryStart(intent)) openAppDetails();
    }

    private void openUsageAccessSettings() {
        if (!tryStart(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))) openAppDetails();
    }

    private void openBatterySettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (tryStart(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))) return;
        }
        openAppDetails();
    }

    private void openHyperOsSettings() {
        Intent[] intents = new Intent[] {
                new Intent().setComponent(new ComponentName(
                        "com.miui.securitycenter",
                        "com.miui.permcenter.autostart.AutoStartManagementActivity")),
                new Intent().setComponent(new ComponentName(
                        "com.miui.powerkeeper",
                        "com.miui.powerkeeper.ui.HiddenAppsConfigActivity")),
                new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        .setData(Uri.parse("package:" + getPackageName()))
        };
        for (Intent intent : intents) {
            if (tryStart(intent)) return;
        }
        openAppDetails();
    }

    private void shareDiagnostics() {
        String text = BrightnessLogManager.exportText(this)
                + "\n\n" + AutoBrightnessManager.getDiagnosticText(this)
                + "\n\n" + ProtectionServiceHealth.getDiagnosticText(this);
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_SUBJECT, getString(R.string.diagnostics_share_subject));
        send.putExtra(Intent.EXTRA_TEXT, text);
        startActivity(Intent.createChooser(send, getString(R.string.diagnostics_share_chooser)));
    }

    private boolean notificationsGranted() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED;
    }

    private boolean batteryOptimizationIgnored() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true;
        PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
        return power == null || power.isIgnoringBatteryOptimizations(getPackageName());
    }

    private void openAppDetails() {
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        intent.setData(Uri.parse("package:" + getPackageName()));
        tryStart(intent);
    }

    private boolean tryStart(Intent intent) {
        try {
            startActivity(intent);
            return true;
        } catch (ActivityNotFoundException | SecurityException ignored) {
            return false;
        }
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(18), dp(18), dp(18));
        card.setBackground(rounded(CARD, 20, 0xFFE9E4DC, 1));
        return card;
    }

    private Button button(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(17);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setTextColor(0xFFFFFFFF);
        button.setAllCaps(false);
        button.setBackground(rounded(ORANGE, 18, ORANGE, 0));
        return button;
    }

    private Button secondaryButton(String label, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(14);
        button.setTextColor(INK);
        button.setAllCaps(false);
        button.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        button.setPadding(dp(14), 0, dp(14), 0);
        button.setBackground(rounded(0xFFF6F2EC, 14, 0xFFE4DDD3, 1));
        button.setOnClickListener(listener);
        return button;
    }

    private TextView text(String value, int sp, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        view.setLineSpacing(0f, 1.15f);
        if (bold) view.setTypeface(Typeface.DEFAULT_BOLD);
        return view;
    }

    private GradientDrawable rounded(int fill, int radiusDp, int stroke, int strokeDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(dp(radiusDp));
        if (strokeDp > 0) drawable.setStroke(dp(strokeDp), stroke);
        return drawable;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(-1, -2);
    }

    private LinearLayout.LayoutParams matchHeight(int heightDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(heightDp));
        params.setMargins(0, dp(6), 0, 0);
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
