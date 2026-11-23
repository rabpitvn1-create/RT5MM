package com.ericko.redmiscreenbrightness;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Insets;
import android.os.Build;
import android.view.DisplayCutout;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.FrameLayout;

import static com.ericko.redmiscreenbrightness.ObsidianStyle.*;

final class ObsidianSafeFrame extends FrameLayout {
    private int leftInset;
    private int topInset;
    private int rightInset;
    private int bottomInset;

    ObsidianSafeFrame(Activity activity) {
        super(activity);
        configureWindow(activity.getWindow());
        setBackgroundColor(BACKGROUND);
        setOnApplyWindowInsetsListener((view, insets) -> {
            readInsets(insets);
            requestLayout();
            if (Build.VERSION.SDK_INT >= 30) return WindowInsets.CONSUMED;
            return insets.consumeSystemWindowInsets();
        });
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        requestApplyInsets();
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int safeWidth = Math.max(0, MeasureSpec.getSize(widthSpec) - leftInset - rightInset);
        int margin = dp(getContext(), safeWidth < dp(getContext(), 360) ? 20 : 24);
        int available = Math.max(0, safeWidth - 2 * margin);
        int excess = Math.max(0, available - dp(getContext(), 480));
        int left = leftInset + margin + excess / 2;
        int right = rightInset + margin + excess - excess / 2;
        if (left != getPaddingLeft() || right != getPaddingRight()
                || topInset != getPaddingTop() || bottomInset != getPaddingBottom()) {
            setPadding(left, topInset, right, bottomInset);
        }
        super.onMeasure(widthSpec, heightSpec);
    }

    @SuppressWarnings("deprecation")
    private void readInsets(WindowInsets insets) {
        if (Build.VERSION.SDK_INT >= 30) {
            Insets bars = insets.getInsets(WindowInsets.Type.systemBars()
                    | WindowInsets.Type.displayCutout());
            Insets gestures = insets.getInsets(WindowInsets.Type.systemGestures());
            leftInset = Math.max(bars.left, gestures.left);
            topInset = bars.top;
            rightInset = Math.max(bars.right, gestures.right);
            bottomInset = Math.max(bars.bottom, gestures.bottom);
            return;
        }
        leftInset = insets.getSystemWindowInsetLeft();
        topInset = insets.getSystemWindowInsetTop();
        rightInset = insets.getSystemWindowInsetRight();
        bottomInset = insets.getSystemWindowInsetBottom();
        if (Build.VERSION.SDK_INT >= 28) {
            DisplayCutout cutout = insets.getDisplayCutout();
            if (cutout != null) {
                leftInset = Math.max(leftInset, cutout.getSafeInsetLeft());
                topInset = Math.max(topInset, cutout.getSafeInsetTop());
                rightInset = Math.max(rightInset, cutout.getSafeInsetRight());
                bottomInset = Math.max(bottomInset, cutout.getSafeInsetBottom());
            }
        }
        if (Build.VERSION.SDK_INT >= 29) {
            Insets gestures = insets.getSystemGestureInsets();
            leftInset = Math.max(leftInset, gestures.left);
            rightInset = Math.max(rightInset, gestures.right);
            bottomInset = Math.max(bottomInset, gestures.bottom);
        }
    }

    @SuppressWarnings("deprecation")
    private static void configureWindow(Window window) {
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT >= 28) {
            WindowManager.LayoutParams attrs = window.getAttributes();
            attrs.layoutInDisplayCutoutMode = Build.VERSION.SDK_INT >= 30
                    ? WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                    : WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            window.setAttributes(attrs);
        }
        if (Build.VERSION.SDK_INT >= 29) window.setNavigationBarContrastEnforced(true);
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false);
            android.view.WindowInsetsController controller = window.getInsetsController();
            if (controller != null) controller.setSystemBarsAppearance(0,
                    android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                    | android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
        } else {
            window.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
    }
}
