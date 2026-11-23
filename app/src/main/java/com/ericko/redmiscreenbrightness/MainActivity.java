package com.ericko.redmiscreenbrightness;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;

/** Obsidian Precision: one interactive control, eight truthful states. */
public final class MainActivity extends Activity {
    private static final int NOTIFICATION_REQUEST = 1001;
    private static final long START_GRACE_MS = 6500L;
    private static final int BG = Color.rgb(12,17,22);
    private static final int SURFACE = Color.rgb(21,29,35);
    private static final int PRIMARY = Color.rgb(242,245,245);
    private static final int SECONDARY = Color.rgb(157,173,177);
    private static final int MINT = Color.rgb(167,215,191);
    private static final int WARNING = Color.rgb(231,186,116);
    private static final int ERROR = Color.rgb(240,164,164);

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refreshTask = new Runnable() {
        @Override public void run() {
            render();
            handler.postDelayed(this, 800L);
        }
    };

    private ObsidianPowerButton toggle;
    private TextView headline, explanation, metricRaw, metricLux, metricDetail;
    private ObsidianUiState.Phase lastPhase;
    private boolean awaitingWritePermission;
    private boolean requestingStart;
    private long startGraceUntil;
    private String transientMessage = "";

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        // Solid graphite, never a full-screen overlay or animated ambient halo.
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setBackgroundColor(BG);
        root.setPadding(dp(30), dp(24), dp(30), dp(30));

        TextView brand = text(R.string.obsidian_brand,13,PRIMARY,true);
        brand.setLetterSpacing(.12f);
        brand.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        root.addView(brand,new LinearLayout.LayoutParams(-1,dp(32)));

        addFlexibleSpace(root);

        LinearLayout center = new LinearLayout(this);
        center.setOrientation(LinearLayout.VERTICAL);
        center.setGravity(Gravity.CENTER_HORIZONTAL);
        headline = text(R.string.obsidian_off,34,PRIMARY,true);
        headline.setGravity(Gravity.CENTER);
        headline.setMaxLines(3);
        center.addView(headline,new LinearLayout.LayoutParams(-1,-2));

        explanation = text(R.string.obsidian_off_desc,14,SECONDARY,false);
        explanation.setGravity(Gravity.CENTER);
        explanation.setMaxLines(3);
        LinearLayout.LayoutParams summaryParams = new LinearLayout.LayoutParams(-1,-2);
        summaryParams.topMargin=dp(10);
        center.addView(explanation,summaryParams);

        toggle = new ObsidianPowerButton(this);
        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(dp(152),dp(152));
        buttonParams.gravity=Gravity.CENTER_HORIZONTAL;
        buttonParams.topMargin=dp(34);
        center.addView(toggle,buttonParams);
        toggle.setOnClickListener(v -> {
            toggle.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            toggle.animate().scaleX(.96f).scaleY(.96f).setDuration(90L)
                    .withEndAction(() -> toggle.animate()
                            .scaleX(1f).scaleY(1f).setDuration(90L).start())
                    .start();
            onToggle();
        });

        root.addView(center,new LinearLayout.LayoutParams(-1,-2));
        addFlexibleSpace(root);

        View rule = new View(this);
        rule.setBackgroundColor(Color.rgb(48,64,73));
        root.addView(rule,new LinearLayout.LayoutParams(-1,dp(1)));

        LinearLayout metrics = new LinearLayout(this);
        metrics.setOrientation(LinearLayout.HORIZONTAL);
        metrics.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams metricsParams = new LinearLayout.LayoutParams(-1,-2);
        metricsParams.topMargin=dp(24);

        LinearLayout rawColumn = new LinearLayout(this);
        rawColumn.setOrientation(LinearLayout.VERTICAL);
        metricRaw=text(R.string.obsidian_raw_unavailable,24,PRIMARY,true);
        rawColumn.addView(metricRaw,new LinearLayout.LayoutParams(-1,-2));
        TextView rawLabel=text(R.string.obsidian_raw_label,12,SECONDARY,false);
        LinearLayout.LayoutParams labelParams=new LinearLayout.LayoutParams(-1,-2);
        labelParams.topMargin=dp(4);
        rawColumn.addView(rawLabel,labelParams);
        metrics.addView(rawColumn,new LinearLayout.LayoutParams(0,-2,1));

        View divider = new View(this);
        divider.setBackgroundColor(Color.rgb(48,64,73));
        LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(dp(1),dp(52));
        dividerParams.leftMargin=dp(12);dividerParams.rightMargin=dp(20);
        metrics.addView(divider,dividerParams);

        LinearLayout luxColumn = new LinearLayout(this);
        luxColumn.setOrientation(LinearLayout.VERTICAL);
        metricLux=text(R.string.obsidian_lux_unavailable,24,PRIMARY,true);
        luxColumn.addView(metricLux,new LinearLayout.LayoutParams(-1,-2));
        metricDetail=text(R.string.obsidian_light_unknown,12,SECONDARY,false);
        LinearLayout.LayoutParams luxLabelParams=new LinearLayout.LayoutParams(-1,-2);
        luxLabelParams.topMargin=dp(4);
        luxColumn.addView(metricDetail,luxLabelParams);
        metrics.addView(luxColumn,new LinearLayout.LayoutParams(0,-2,1));

        root.addView(metrics,metricsParams);
        setContentView(root);
        render();
    }

    private void addFlexibleSpace(LinearLayout root) {
        View spacer=new View(this);
        root.addView(spacer,new LinearLayout.LayoutParams(1,0,1f));
    }

    private TextView text(int label,int sp,int color,boolean medium) {
        TextView view=new TextView(this);
        view.setText(label);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP,sp);
        view.setTextColor(color);
        view.setTypeface(Typeface.create(medium?"sans-serif-medium":"sans-serif",
                Typeface.NORMAL));
        view.setIncludeFontPadding(false);
        return view;
    }

    @Override protected void onResume() {
        super.onResume();
        if (AutoBrightnessManager.isAutoEnabled(this)) {
            if (!ProtectionServiceHealth.isServiceHealthy(this)) {
                startGraceUntil=SystemClock.elapsedRealtime()+START_GRACE_MS;
            }
            AutoBrightnessService.start(this);
        } else if (BrightnessLevels.hasPendingModeRestore(this)
                && Settings.System.canWrite(this)) {
            BrightnessLevels.restorePreviousBrightnessMode(this);
        }
        if(awaitingWritePermission) {
            awaitingWritePermission=false;
            if(Settings.System.canWrite(this)) requestStart();
        }
        handler.removeCallbacks(refreshTask);
        handler.post(refreshTask);
    }

    @Override protected void onPause() {
        handler.removeCallbacks(refreshTask);
        super.onPause();
    }

    private void onToggle() {
        boolean wanted=AutoBrightnessManager.isAutoEnabled(this);
        if(!wanted && BrightnessLevels.hasPendingModeRestore(this)) {
            boolean restored=BrightnessLevels.restorePreviousBrightnessMode(this);
            transientMessage=restored?"":getString(R.string.obsidian_restore_desc);
            render();
            return;
        }
        if(wanted) {
            AutoBrightnessService.stop(this);
            requestingStart=false;
            transientMessage="";
            render();
            return;
        }
        if(!AutoBrightnessManager.hasLightSensor(this)) {
            transientMessage=getString(R.string.obsidian_sensor_error_desc);
            render();
            return;
        }
        if(!Settings.System.canWrite(this)) {
            awaitingWritePermission=true;
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,
                        Uri.parse("package:"+getPackageName())));
            } catch(ActivityNotFoundException | SecurityException error) {
                awaitingWritePermission=false;
                transientMessage=getString(R.string.obsidian_permission_desc);
            }
            render();
            return;
        }
        requestStart();
    }

    private void requestStart() {
        requestingStart=true;
        startGraceUntil=SystemClock.elapsedRealtime()+START_GRACE_MS;
        transientMessage="";
        AutoBrightnessService.start(this);
        if(Build.VERSION.SDK_INT>=33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    NOTIFICATION_REQUEST);
        render();
    }

    private void render() {
        if(toggle==null) return;
        boolean wanted=AutoBrightnessManager.isAutoEnabled(this);
        boolean permission=Settings.System.canWrite(this);
        boolean sensor=AutoBrightnessManager.hasLightSensor(this);
        boolean pendingRestore=BrightnessLevels.hasPendingModeRestore(this);
        boolean running=ProtectionServiceHealth.isServiceHealthy(this);
        boolean manual=BrightnessLevels.getSystemBrightnessMode(this)
                ==Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL;
        boolean registered=ProtectionUiTelemetry.isSensorRegistered();
        boolean fresh=ProtectionUiTelemetry.hasFreshSample();
        boolean held=AutoBrightnessManager.isUserHoldActive(this);
        boolean grace=(requestingStart || wanted)
                && SystemClock.elapsedRealtime()<=startGraceUntil;
        ObsidianUiState.Phase phase=ObsidianUiState.resolve(wanted,permission,sensor,
                pendingRestore,running,manual,registered,fresh,held,grace);

        int titleRes,descriptionRes,labelRes;
        int titleColor=PRIMARY,accent=MINT;
        switch(phase) {
            case PERMISSION_REQUIRED:
                titleRes=R.string.obsidian_permission;
                descriptionRes=R.string.obsidian_permission_desc;
                labelRes=R.string.obsidian_grant_and_enable;
                titleColor=WARNING;accent=WARNING;
                break;
            case STARTING:
                titleRes=R.string.obsidian_starting;
                descriptionRes=R.string.obsidian_starting_desc;
                labelRes=R.string.obsidian_disable;
                titleColor=WARNING;accent=WARNING;
                break;
            case PROTECTING:
                titleRes=R.string.obsidian_protecting;
                descriptionRes=R.string.obsidian_protecting_desc;
                labelRes=R.string.obsidian_disable;
                break;
            case USER_HOLD:
                titleRes=R.string.obsidian_hold;
                descriptionRes=R.string.obsidian_hold_desc;
                labelRes=R.string.obsidian_disable;
                break;
            case WAITING_SENSOR:
                titleRes=R.string.obsidian_waiting;
                descriptionRes=R.string.obsidian_waiting_desc;
                labelRes=R.string.obsidian_disable;
                titleColor=WARNING;accent=WARNING;
                break;
            case SERVICE_ERROR:
                titleRes=R.string.obsidian_service_error;
                descriptionRes=R.string.obsidian_service_error_desc;
                labelRes=wanted?R.string.obsidian_disable:R.string.obsidian_enable;
                titleColor=ERROR;accent=ERROR;
                break;
            case RESTORE_FAILED:
                titleRes=R.string.obsidian_restore_error;
                descriptionRes=R.string.obsidian_restore_desc;
                labelRes=R.string.obsidian_try_restore;
                titleColor=ERROR;accent=ERROR;
                break;
            case OFF:
            default:
                titleRes=R.string.obsidian_off;
                descriptionRes=R.string.obsidian_off_desc;
                labelRes=R.string.obsidian_enable;
                break;
        }

        boolean changed=lastPhase!=phase;
        lastPhase=phase;
        headline.setText(titleRes);
        headline.setTextColor(titleColor);
        explanation.setText(transientMessage.isEmpty()?getString(descriptionRes):transientMessage);
        toggle.show(getString(labelRes), wanted, accent, changed);
        toggle.setEnabled(true);
        toggle.setAlpha(1f);

        // Hide metrics while OFF, during recovery, and whenever the live
        // sensor sample cannot be confirmed. Never recycle stored lux.
        if(ObsidianUiState.canShowMetrics(phase)) {
            int raw=BrightnessLevels.getSystemRaw(this,-1);
            float lux=ProtectionUiTelemetry.getFreshLux();
            if(raw>=0) {
                metricRaw.setText(getString(R.string.obsidian_raw_value,raw));
            } else metricRaw.setText(R.string.obsidian_raw_unavailable);
            if(lux>=0f) {
                metricLux.setText(String.format(Locale.getDefault(),"%.0f lx",lux));
                int band;
                switch(ObsidianUiState.ambient(lux)) {
                    case LOW: band=R.string.obsidian_light_low;break;
                    case MEDIUM: band=R.string.obsidian_light_medium;break;
                    case HIGH: band=R.string.obsidian_light_high;break;
                    default: band=R.string.obsidian_light_unknown;break;
                }
                metricDetail.setText(band);
            } else {
                metricLux.setText(R.string.obsidian_lux_unavailable);
                metricDetail.setText(R.string.obsidian_light_unknown);
            }
        } else {
            metricRaw.setText(R.string.obsidian_raw_unavailable);
            metricLux.setText(R.string.obsidian_lux_unavailable);
            metricDetail.setText(R.string.obsidian_light_unknown);
        }
    }

    private int dp(int n) {
        return Math.round(n*getResources().getDisplayMetrics().density);
    }
}
