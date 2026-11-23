package com.ericko.redmiscreenbrightness;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.content.res.ColorStateList;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import static com.ericko.redmiscreenbrightness.ObsidianStyle.*;

final class ObsidianPowerButton extends LinearLayout {
    enum Icon { POWER, SETTINGS, RETRY, BUSY }

    private final TextView label;
    private final Glyph glyph;
    private final ProgressBar progress;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final android.animation.ArgbEvaluator colorEvaluator = new android.animation.ArgbEvaluator();
    private final RectF outline = new RectF();
    private boolean wide;
    private boolean controlling;
    private Icon icon = Icon.POWER;
    private float activeFraction;
    private float pressedFraction;
    private ValueAnimator borderAnimator;
    private ValueAnimator pressAnimator;

    ObsidianPowerButton(Context context) {
        super(context);
        setGravity(Gravity.CENTER);
        setFocusable(true);
        setClickable(true);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        setWillNotDraw(false);

        glyph = new Glyph(context);
        glyph.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        progress = new ProgressBar(context, null, android.R.attr.progressBarStyleSmall);
        progress.setIndeterminateTintList(ColorStateList.valueOf(ObsidianStyle.BUSY));
        progress.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        label = text(context, 16, 22, 600, PRIMARY);
        label.setGravity(Gravity.CENTER);
        label.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        progress.setVisibility(GONE);
        addView(glyph);
        addView(progress);
        addView(label);
        applyShape(false);
    }

    void applyShape(boolean expanded) {
        wide = expanded;
        setOrientation(wide ? HORIZONTAL : VERTICAL);
        setPadding(d(wide ? 16 : 20), d(wide ? 12 : 0),
                d(wide ? 16 : 20), d(wide ? 12 : 0));
        setMinimumHeight(d(wide ? 72 : 144));
        glyph.setLayoutParams(new LayoutParams(d(26), d(26)));
        progress.setLayoutParams(new LayoutParams(d(26), d(26)));
        LayoutParams labelParams = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        if (wide) labelParams.setMarginStart(d(12)); else labelParams.topMargin = d(8);
        label.setGravity(wide ? Gravity.START | Gravity.CENTER_VERTICAL : Gravity.CENTER);
        label.setMaxWidth(wide ? Integer.MAX_VALUE : d(104));
        label.setLayoutParams(labelParams);
    }

    void show(int labelRes, Icon nextIcon, boolean active) {
        String name = getResources().getString(labelRes);
        if (!name.contentEquals(label.getText())) {
            label.setText(name);
            setContentDescription(name);
        }
        if (icon != nextIcon) {
            icon = nextIcon;
            progress.setVisibility(icon == Icon.BUSY ? VISIBLE : GONE);
            glyph.setVisibility(icon == Icon.BUSY ? GONE : VISIBLE);
            label.setTextColor(icon == Icon.BUSY ? ObsidianStyle.BUSY : PRIMARY);
            setEnabled(icon != Icon.BUSY);
            glyph.invalidate();
        }
        if (controlling != active) {
            controlling = active;
            if (borderAnimator != null) borderAnimator.cancel();
            if (active) {
                borderAnimator = animateFraction(activeFraction, 1f, 160L, true);
            } else {
                setFraction(0f, true);
            }
        }
    }

    @Override protected void drawableStateChanged() {
        super.drawableStateChanged();
        if (paint == null) return;
        float target = isPressed() && isEnabled() ? 1f : 0f;
        if (pressAnimator != null) pressAnimator.cancel();
        pressAnimator = animateFraction(pressedFraction, target, 120L, false);
    }

    private ValueAnimator animateFraction(float from, float to, long duration, boolean border) {
        if (!isLaidOut() || from == to
                || (android.os.Build.VERSION.SDK_INT >= 26 && !ValueAnimator.areAnimatorsEnabled())) {
            setFraction(to, border);
            return null;
        }
        ValueAnimator animator = ValueAnimator.ofFloat(from, to);
        animator.setDuration(duration);
        animator.addUpdateListener(value -> setFraction((float) value.getAnimatedValue(), border));
        animator.start();
        return animator;
    }

    private void setFraction(float value, boolean border) {
        if (border) {
            activeFraction = value;
            glyph.invalidate();
        } else {
            pressedFraction = value;
        }
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float radius = wide ? d(24) : getWidth() / 2f;
        float stroke = d(2);
        float center = stroke / 2f;
        outline.set(center, center, getWidth() - center, getHeight() - center);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(blend(SURFACE, PRESSED, pressedFraction));
        canvas.drawRoundRect(outline, radius, radius, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(stroke);
        paint.setColor(blend(BORDER, MINT, activeFraction));
        canvas.drawRoundRect(outline, radius, radius, paint);
        if (isFocused() || isAccessibilityFocused()) {
            float offset = d(5);
            outline.set(-offset, -offset, getWidth() + offset, getHeight() + offset);
            paint.setColor(FOCUS);
            canvas.drawRoundRect(outline, radius + offset, radius + offset, paint);
        }
    }

    @Override public boolean dispatchTouchEvent(android.view.MotionEvent event) {
        if (!wide && event.getActionMasked() == android.view.MotionEvent.ACTION_DOWN) {
            float dx = event.getX() - getWidth() / 2f;
            float dy = event.getY() - getHeight() / 2f;
            float radius = getWidth() / 2f;
            if (dx * dx + dy * dy > radius * radius) return false;
        }
        return super.dispatchTouchEvent(event);
    }

    @Override public CharSequence getAccessibilityClassName() {
        return android.widget.Button.class.getName();
    }

    private int d(float value) { return dp(getContext(), value); }

    private int blend(int first, int second, float fraction) {
        return (Integer) colorEvaluator.evaluate(fraction, first, second);
    }

    @Override protected void onDetachedFromWindow() {
        if (pressAnimator != null) pressAnimator.cancel();
        if (borderAnimator != null) borderAnimator.cancel();
        pressedFraction = 0f;
        activeFraction = controlling ? 1f : 0f;
        super.onDetachedFromWindow();
    }

    private final class Glyph extends View {
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        Glyph(Context context) { super(context); }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            canvas.save();
            canvas.scale(getWidth() / 26f, getHeight() / 26f);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeWidth(2f);
            stroke.setStrokeCap(Paint.Cap.ROUND);
            stroke.setStrokeJoin(Paint.Join.ROUND);
            stroke.setColor(blend(SECONDARY, MINT, activeFraction));
            switch (icon) {
                case SETTINGS:
                    canvas.drawRoundRect(5, 5, 21, 21, 4, 4, stroke);
                    canvas.drawLine(9, 10, 17, 10, stroke);
                    canvas.drawLine(9, 15, 17, 15, stroke);
                    canvas.drawLine(12, 8, 12, 12, stroke);
                    canvas.drawLine(15, 13, 15, 17, stroke);
                    break;
                case RETRY:
                    canvas.drawArc(4, 4, 22, 22, -50, 300, false, stroke);
                    canvas.drawLine(21, 5, 21, 11, stroke);
                    canvas.drawLine(21, 11, 15, 11, stroke);
                    break;
                default:
                    canvas.drawArc(4, 5, 22, 23, -49, 278, false, stroke);
                    canvas.drawLine(13, 3, 13, 12, stroke);
            }
            canvas.restore();
        }
    }
}
