package com.ericko.redmiscreenbrightness;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;

/**
 * Exactly one interactive visual element. Flat fill, no halo/gradient,
 * custom thin-stroke power glyph and 16sp label. Uses the stock accessibility
 * button semantics and respects Android's disabled animator scale.
 */
public final class ObsidianPowerButton extends View {
    private static final int OFF = Color.rgb(21, 29, 35);
    private static final int ON = Color.rgb(167, 215, 191);
    private static final int TEXT_OFF = Color.rgb(242, 245, 245);
    private static final int TEXT_ON = Color.rgb(16, 32, 25);
    private static final int BORDER_OFF = Color.rgb(61, 78, 87);
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF arc = new RectF();
    private String label = "";
    private float activated = 0f;
    private ValueAnimator animator;
    private int accent = ON;

    public ObsidianPowerButton(Context context) {
        super(context);
        init();
    }

    public ObsidianPowerButton(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        setClickable(true);
        setFocusable(true);
        setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        setLayerType(View.LAYER_TYPE_SOFTWARE, null);
    }

    public void show(String newLabel, boolean on, int stateAccent, boolean animate) {
        label = newLabel;
        setContentDescription(newLabel);
        accent = stateAccent;
        float target = on ? 1f : 0f;
        if (animator != null) animator.cancel();
        if (animate && Math.abs(activated - target) > .001f) {
            animator = ValueAnimator.ofFloat(activated, target);
            animator.setDuration(200L);
            animator.addUpdateListener(value -> {
                activated = (float) value.getAnimatedValue();
                invalidate();
            });
            animator.start();
        } else {
            activated = target;
            invalidate();
        }
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float density = getResources().getDisplayMetrics().density;
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float radius = Math.min(getWidth(), getHeight()) / 2f - density * 3f;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(blend(OFF, accent, activated));
        canvas.drawCircle(cx, cy, radius, paint);
        paint.setColor(blend(BORDER_OFF, accent, activated));
        paint.setStrokeWidth(density * 1.4f);
        paint.setStyle(Paint.Style.STROKE);
        canvas.drawCircle(cx, cy, radius, paint);

        paint.setColor(blend(TEXT_OFF, TEXT_ON, activated));
        paint.setStrokeWidth(density * 2.25f);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStyle(Paint.Style.STROKE);

        // 28dp power glyph, with open section at twelve o'clock.
        float glyphY = cy - density * 16f;
        float glyphRadius = density * 11.5f;
        arc.set(cx - glyphRadius, glyphY - glyphRadius,
                cx + glyphRadius, glyphY + glyphRadius);
        canvas.drawArc(arc, -45f, 270f, false, paint);
        canvas.drawLine(cx, glyphY - density * 16f,
                cx, glyphY - density * 3f, paint);

        paint.setStyle(Paint.Style.FILL);
        paint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        paint.setTextSize(getResources().getDisplayMetrics().scaledDensity * 16f);
        paint.setTextAlign(Paint.Align.CENTER);
        canvas.drawText(label, cx, cy + density * 35f, paint);
    }

    @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.setClassName(android.widget.Button.class.getName());
    }

    private static int blend(int start, int end, float t) {
        float v = Math.max(0f, Math.min(1f, t));
        return Color.rgb(
                Math.round(Color.red(start) + (Color.red(end) - Color.red(start)) * v),
                Math.round(Color.green(start) + (Color.green(end) - Color.green(start)) * v),
                Math.round(Color.blue(start) + (Color.blue(end) - Color.blue(start)) * v));
    }

    @Override protected void onDetachedFromWindow() {
        if (animator != null) animator.cancel();
        super.onDetachedFromWindow();
    }
}
