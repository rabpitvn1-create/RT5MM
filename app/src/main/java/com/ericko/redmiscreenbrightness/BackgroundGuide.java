package com.ericko.redmiscreenbrightness;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import static com.ericko.redmiscreenbrightness.ObsidianStyle.*;

final class BackgroundGuide extends ScrollView {
    private final MainActivity activity;
    private final SharedPreferences preferences;
    private final LinearLayout column;
    private int activeStep;

    BackgroundGuide(MainActivity activity, Runnable onBack) {
        super(activity);
        this.activity = activity;
        preferences = activity.getSharedPreferences("background_guide", android.content.Context.MODE_PRIVATE);
        setFillViewport(true);

        column = new LinearLayout(activity);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(0, d(16), 0, d(12));
        addView(column, new ScrollView.LayoutParams(-1, -2));

        LinearLayout header = new LinearLayout(activity);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView back = text(activity, 24, 30, 600, PRIMARY);
        back.setText(R.string.guide_back_icon);
        back.setContentDescription(activity.getText(R.string.guide_back));
        back.setGravity(Gravity.CENTER);
        back.setFocusable(true);
        back.setBackgroundResource(android.R.drawable.list_selector_background);
        back.setOnClickListener(view -> onBack.run());
        header.addView(back, new LinearLayout.LayoutParams(d(48), d(48)));
        TextView title = text(activity, 24, 30, 600, PRIMARY);
        title.setText(R.string.guide_title);
        if (Build.VERSION.SDK_INT >= 28) title.setAccessibilityHeading(true);
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
        column.addView(header, row(0));

        paragraph(column, R.string.guide_intro, 14, 20, 400, SECONDARY, 12);
        View gap = new View(activity);
        column.addView(gap, new LinearLayout.LayoutParams(1, d(20)));
        addStep(1, R.string.guide_autostart, R.string.guide_autostart_desc,
                R.string.guide_autostart_path, R.string.guide_autostart_search,
                R.string.guide_open_settings, new Intent(Settings.ACTION_SETTINGS));
        addStep(2, R.string.guide_battery, R.string.guide_battery_desc,
                R.string.guide_battery_path, R.string.guide_battery_search,
                R.string.guide_open_app_info, new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", activity.getPackageName(), null)));
        addStep(3, R.string.guide_recents, R.string.guide_recents_desc,
                R.string.guide_recents_path, R.string.guide_recents_search, 0, null);
    }

    boolean hasBeenOpened() { return preferences.getBoolean("opened", false); }

    void markOpened() { preferences.edit().putBoolean("opened", true).apply(); }

    int getActiveStep() { return activeStep; }

    void restorePosition(int step, int scrollY) {
        activeStep = step;
        addOnLayoutChangeListener(new OnLayoutChangeListener() {
            @Override public void onLayoutChange(View view, int left, int top, int right, int bottom,
                    int oldLeft, int oldTop, int oldRight, int oldBottom) {
                removeOnLayoutChangeListener(this);
                scrollTo(0, scrollY);
            }
        });
    }

    private void addStep(int step, int title, int description, int path, int search,
                         int shortcut, Intent destination) {
        View divider = new View(activity);
        divider.setBackgroundColor(DIVIDER);
        column.addView(divider, new LinearLayout.LayoutParams(-1, d(1)));

        LinearLayout section = new LinearLayout(activity);
        section.setOrientation(LinearLayout.VERTICAL);
        section.setPadding(0, d(16), 0, d(16));
        column.addView(section, row(0));
        TextView heading = paragraph(section, title, 16, 22, 600, PRIMARY, 0);
        if (Build.VERSION.SDK_INT >= 28) heading.setAccessibilityHeading(true);
        paragraph(section, description, 14, 20, 400, SECONDARY, 6);
        paragraph(section, path, 14, 20, 400, PRIMARY, 6);
        paragraph(section, search, 14, 20, 400, SECONDARY, 6);

        TextView failure = text(activity, 14, 20, 400, ERROR);
        failure.setText(R.string.settings_unavailable);
        failure.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        failure.setVisibility(GONE);
        if (destination != null) {
            TextView open = paragraph(section, shortcut, 14, 20, 600, PRIMARY, 8);
            open.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
            open.setMinHeight(d(48));
            open.setFocusable(true);
            open.setBackgroundResource(android.R.drawable.list_selector_background);
            open.setOnClickListener(view -> {
                activeStep = step;
                failure.setVisibility(GONE);
                try {
                    activity.startActivity(destination);
                } catch (ActivityNotFoundException | SecurityException unavailable) {
                    failure.setVisibility(VISIBLE);
                    post(() -> smoothScrollTo(0, column.getTop() + section.getTop()));
                }
            });
            section.addView(failure, row(0));
        }

        CheckBox confirmation = new CheckBox(activity);
        confirmation.setText(R.string.guide_self_confirmed);
        confirmation.setTextColor(SECONDARY);
        confirmation.setTextSize(13);
        confirmation.setTypeface(android.graphics.Typeface.create("sans-serif-medium", 0));
        confirmation.setIncludeFontPadding(false);
        if (Build.VERSION.SDK_INT >= 28) confirmation.setLineHeight(sp(activity, 18));
        confirmation.setButtonTintList(ColorStateList.valueOf(FOCUS));
        confirmation.setMinHeight(d(48));
        String key = "step_" + step;
        confirmation.setChecked(preferences.getBoolean(key, false));
        updateConfirmationDescription(confirmation, title, confirmation.isChecked());
        confirmation.setOnCheckedChangeListener((button, checked) -> {
            preferences.edit().putBoolean(key, checked).apply();
            updateConfirmationDescription(confirmation, title, checked);
        });
        section.addView(confirmation, row(destination == null ? 8 : 0));
    }

    private void updateConfirmationDescription(CheckBox box, int stepTitle, boolean checked) {
        box.setContentDescription(activity.getString(stepTitle) + ". "
                + activity.getString(R.string.guide_self_confirmed));
        if (Build.VERSION.SDK_INT >= 30) box.setStateDescription(activity.getText(
                checked ? R.string.guide_self_confirmed : R.string.guide_not_confirmed));
    }

    private TextView paragraph(LinearLayout section, int label, int size, int lineHeight,
                               int weight, int color, int top) {
        TextView text = text(activity, size, lineHeight, weight, color);
        text.setText(label);
        section.addView(text, row(top));
        return text;
    }

    private LinearLayout.LayoutParams row(int top) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = d(top);
        return params;
    }

    private int d(int value) { return dp(activity, value); }
}
