package com.ericko.redmiscreenbrightness;

import android.content.Context;
import android.os.Build;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.AbsoluteSizeSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.TypefaceSpan;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import static com.ericko.redmiscreenbrightness.ObsidianStyle.*;

final class ObsidianDashboard extends ScrollView {
    private final TextView title;
    private final TextView description;
    private final View indicator;
    private final ObsidianPowerButton power;
    private final TextView raw;
    private final TextView lux;
    private final TextView guideHint;
    private int lastIndicator;

    private final int[] titleOptions = {
            R.string.obsidian_off, R.string.obsidian_permission, R.string.obsidian_starting,
            R.string.obsidian_stopping, R.string.obsidian_protecting, R.string.obsidian_hold,
            R.string.obsidian_waiting, R.string.obsidian_unconfirmed, R.string.obsidian_service_error,
            R.string.obsidian_restore_error
    };
    private final int[] detailOptions = {
            R.string.obsidian_off_desc, R.string.obsidian_permission_desc, R.string.obsidian_hold_desc,
            R.string.obsidian_service_error_desc, R.string.obsidian_sensor_error_desc,
            R.string.obsidian_restore_desc, R.string.obsidian_restore_manual,
            R.string.obsidian_permission_manual, R.string.settings_unavailable
    };
    private final int[] buttonOptions = {
            R.string.obsidian_enable, R.string.obsidian_disable, R.string.obsidian_grant,
            R.string.obsidian_start_pending, R.string.obsidian_stop_pending, R.string.obsidian_retry,
            R.string.obsidian_check, R.string.obsidian_try_restore, R.string.obsidian_display_settings
    };

    ObsidianDashboard(Context context, Runnable onPower, Runnable onGuide) {
        super(context);
        setFillViewport(true);
        setVerticalScrollBarEnabled(false);

        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(0, d(16), 0, d(12));
        column.setClipChildren(false);
        addView(column, new FrameLayout.LayoutParams(-1, -2));

        TextView brand = text(context, 14, 20, 500, PRIMARY);
        brand.setText(R.string.obsidian_brand);
        brand.setMinHeight(d(32));
        brand.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        column.addView(brand, row(0));

        View before = new View(context);
        column.addView(before, spacer(16));

        title = text(context, 30, 36, 600, PRIMARY);
        title.setGravity(Gravity.CENTER);
        if (Build.VERSION.SDK_INT >= 28) title.setAccessibilityHeading(true);
        title.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        description = text(context, 14, 20, 400, SECONDARY);
        description.setGravity(Gravity.CENTER);
        indicator = new View(context);
        indicator.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        power = new ObsidianPowerButton(context);
        power.setOnClickListener(view -> onPower.run());
        column.addView(new Center(context), row(0));

        View after = new View(context);
        column.addView(after, spacer(24));

        View rule = new View(context);
        rule.setBackgroundColor(DIVIDER);
        column.addView(rule, new LinearLayout.LayoutParams(-1, d(1)));

        raw = text(context, 14, 20, 500, PRIMARY);
        lux = text(context, 14, 20, 500, SECONDARY);
        column.addView(new Metrics(context), row(0));

        LinearLayout guideRow = new LinearLayout(context);
        guideRow.setGravity(Gravity.CENTER_VERTICAL);
        guideRow.setMinimumHeight(d(56));
        guideRow.setPadding(0, d(8), 0, d(8));
        LinearLayout guideWords = new LinearLayout(context);
        guideWords.setOrientation(LinearLayout.VERTICAL);
        TextView guideLabel = text(context, 14, 20, 600, PRIMARY);
        guideLabel.setText(R.string.guide_entry);
        guideWords.addView(guideLabel, row(0));
        guideHint = text(context, 13, 18, 400, SECONDARY);
        guideHint.setText(R.string.guide_hint);
        guideWords.addView(guideHint, row(0));
        guideRow.addView(guideWords, new LinearLayout.LayoutParams(0, -2, 1f));
        View arrow = new View(context) {
            private final android.graphics.Paint paint =
                    new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            @Override protected void onDraw(android.graphics.Canvas canvas) {
                paint.setColor(SECONDARY);
                paint.setStrokeWidth(d(2));
                paint.setStrokeCap(android.graphics.Paint.Cap.ROUND);
                float center = getWidth() / 2f;
                float direction = getLayoutDirection() == LAYOUT_DIRECTION_RTL ? -1f : 1f;
                canvas.drawLine(center - d(2) * direction, d(5), center + d(3) * direction, d(10), paint);
                canvas.drawLine(center + d(3) * direction, d(10), center - d(2) * direction, d(15), paint);
            }
        };
        guideRow.addView(arrow, new LinearLayout.LayoutParams(d(20), d(20)));
        guideRow.setContentDescription(context.getText(R.string.guide_entry));
        guideRow.setFocusable(true);
        guideRow.setBackgroundResource(android.R.drawable.list_selector_background);
        guideRow.setOnClickListener(view -> onGuide.run());
        guideWords.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        column.addView(guideRow, row(0));
    }

    void show(int titleRes, int detailRes, int indicatorColor, int labelRes,
              ObsidianPowerButton.Icon icon, boolean controlling, int rawValue, float luxValue) {
        updateText(title, getContext().getText(titleRes));
        description.setVisibility(detailRes == 0 ? GONE : VISIBLE);
        if (detailRes != 0) updateText(description, getContext().getText(detailRes));
        indicator.setVisibility(detailRes != 0 && indicatorColor != 0 ? VISIBLE : GONE);
        if (indicatorColor != 0 && indicatorColor != lastIndicator) {
            lastIndicator = indicatorColor;
            android.graphics.drawable.GradientDrawable dot = new android.graphics.drawable.GradientDrawable();
            dot.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            dot.setColor(indicatorColor);
            indicator.setBackground(dot);
        }
        power.show(labelRes, icon, controlling);
        updateText(raw, rawValue < 0 ? getContext().getText(R.string.obsidian_raw_unavailable)
                : getContext().getString(R.string.obsidian_raw_value, rawValue));
        CharSequence luxText = luxValue < 0 ? pendingLuxText()
                : luxText(luxValue, ObsidianUiState.ambient(luxValue));
        updateText(lux, luxText);
    }

    void showGuideHint(boolean visible) { guideHint.setVisibility(visible ? VISIBLE : GONE); }

    private SpannableString luxText(float value, ObsidianUiState.Ambient ambient) {
        int band = switch (ambient) {
            case LOW -> R.string.obsidian_light_low;
            case MEDIUM -> R.string.obsidian_light_medium;
            case HIGH -> R.string.obsidian_light_high;
            default -> R.string.obsidian_light_unknown;
        };
        String numeric = getContext().getString(R.string.obsidian_lux_value, value);
        SpannableString combined = new SpannableString(numeric + " · " + getContext().getString(band));
        combined.setSpan(new ForegroundColorSpan(PRIMARY), 0, numeric.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        styleMetricDetail(combined, numeric.length());
        return combined;
    }

    private SpannableString pendingLuxText() {
        SpannableString pending = new SpannableString(getContext().getText(R.string.obsidian_light_unknown));
        styleMetricDetail(pending, 0);
        return pending;
    }

    private void styleMetricDetail(SpannableString value, int start) {
        value.setSpan(new AbsoluteSizeSpan(sp(getContext(), 13)), start, value.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        TypefaceSpan regular = Build.VERSION.SDK_INT >= 28
                ? new TypefaceSpan(android.graphics.Typeface.create("sans-serif", 0))
                : new TypefaceSpan("sans-serif");
        value.setSpan(regular, start, value.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    private static void updateText(TextView view, CharSequence value) {
        if (!android.text.TextUtils.equals(value, view.getText())) view.setText(value);
    }

    private int d(int value) { return dp(getContext(), value); }

    private LinearLayout.LayoutParams row(int topGap) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = d(topGap);
        return params;
    }

    private LinearLayout.LayoutParams spacer(int minimum) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(1, d(minimum), 1f);
        return params;
    }

    private final class Center extends LinearLayout {
        private final FrameLayout textSlot;
        private final TextView titleProbe;
        private final TextView detailProbe;
        private final TextView buttonProbe;
        private int lastWidth = -1;

        Center(Context context) {
            super(context);
            setOrientation(VERTICAL);
            setGravity(Gravity.CENTER_HORIZONTAL);
            setClipChildren(false);
            setClipToPadding(false);

            textSlot = new FrameLayout(context);
            titleProbe = text(context, 30, 36, 600, PRIMARY);
            detailProbe = text(context, 14, 20, 400, SECONDARY);
            buttonProbe = text(context, 16, 22, 600, PRIMARY);
            LinearLayout textStack = new LinearLayout(context);
            textStack.setOrientation(VERTICAL);
            textStack.addView(title, row(0));
            LinearLayout detailRow = new LinearLayout(context);
            detailRow.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams dot = new LinearLayout.LayoutParams(d(6), d(6));
            dot.setMarginEnd(d(8));
            detailRow.addView(indicator, dot);
            description.setMaxWidth(Integer.MAX_VALUE);
            detailRow.addView(description, new LinearLayout.LayoutParams(-2, -2));
            textStack.addView(detailRow, row(6));
            textSlot.addView(textStack, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
            addView(textSlot, row(0));
            addView(power, row(20));
        }

        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            int width = MeasureSpec.getSize(widthSpec);
            if (width != lastWidth) {
                lastWidth = width;
                int textHeight = maxHeight(titleProbe, titleOptions, width, 2, 36)
                        + d(6) + maxHeight(detailProbe, detailOptions, width - d(14), 2, 20);
                textSlot.getLayoutParams().height = textHeight;

                boolean useWide = width < d(144);
                for (int labelId : buttonOptions) {
                    measureText(buttonProbe, labelId, d(104));
                    useWide |= buttonProbe.getLineCount() > 2
                            || buttonProbe.getMeasuredHeight() + d(34) > d(88);
                }

                power.applyShape(useWide);
                int buttonWidth = useWide ? Math.min(d(320), width) : d(144);
                int wideLabel = Math.max(1, buttonWidth - d(70));
                int buttonHeight = useWide ? Math.max(d(72), d(24)
                        + Math.max(d(26), maxHeight(buttonProbe, buttonOptions, wideLabel, 1, 22))) : d(144);
                power.getLayoutParams().width = buttonWidth;
                power.getLayoutParams().height = buttonHeight;
            }
            super.onMeasure(widthSpec, heightSpec);
        }

        private int maxHeight(TextView probe, int[] options, int width, int lines, int lineSpacing) {
            int height = lines * sp(getContext(), lineSpacing);
            for (int option : options) {
                measureText(probe, option, width);
                height = Math.max(height, probe.getMeasuredHeight());
            }
            return height;
        }

        private void measureText(TextView probe, int resource, int width) {
            probe.setText(resource);
            probe.measure(MeasureSpec.makeMeasureSpec(Math.max(1, width), MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
        }
    }

    private final class Metrics extends LinearLayout {
        private int lastWidth = -1;

        Metrics(Context context) {
            super(context);
            setMinimumHeight(d(48));
            setGravity(Gravity.CENTER_VERTICAL);
            setPadding(0, d(8), 0, d(8));
            addView(raw, row(0));
            addView(lux, row(0));
        }

        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            int width = MeasureSpec.getSize(widthSpec);
            if (width != lastWidth) {
                lastWidth = width;
                int rawWidth = Math.max(widthOf(raw, getContext().getString(R.string.obsidian_raw_value, 52)),
                        widthOf(raw, getContext().getText(R.string.obsidian_raw_unavailable)));
                int luxWidth = widthOf(lux, luxText(999999, ObsidianUiState.Ambient.HIGH));
                luxWidth = Math.max(luxWidth,
                        widthOf(lux, luxText(800, ObsidianUiState.Ambient.MEDIUM)));
                luxWidth = Math.max(luxWidth, widthOf(lux, pendingLuxText()));
                boolean stacked = rawWidth + d(12) + luxWidth > width;
                setOrientation(stacked ? VERTICAL : HORIZONTAL);
                raw.setLayoutParams(stacked ? row(0) : new LinearLayout.LayoutParams(rawWidth, -2));
                LinearLayout.LayoutParams second = stacked ? row(4) : new LinearLayout.LayoutParams(0, -2, 1f);
                if (!stacked) second.setMarginStart(d(12));
                lux.setLayoutParams(second);
            }
            super.onMeasure(widthSpec, heightSpec);
        }

        private int widthOf(TextView view, CharSequence label) {
            return (int) Math.ceil(android.text.Layout.getDesiredWidth(label, view.getPaint()));
        }
    }
}
