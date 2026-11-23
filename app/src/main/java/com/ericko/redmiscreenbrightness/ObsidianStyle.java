package com.ericko.redmiscreenbrightness;

import android.content.Context;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.widget.TextView;

final class ObsidianStyle {
    static final int BACKGROUND = 0xff0c1116;
    static final int SURFACE = 0xff151d23;
    static final int PRESSED = 0xff1c272c;
    static final int PRIMARY = 0xfff2f5f5;
    static final int SECONDARY = 0xffa5b1b7;
    static final int BORDER = 0xff73848c;
    static final int DIVIDER = 0xff2a363d;
    static final int BUSY = 0xff84959d;
    static final int FOCUS = 0xffcbdce5;
    static final int MINT = 0xffa7d7bf;
    static final int WARNING = 0xffe7ba74;
    static final int ERROR = 0xfff0a4a4;

    private ObsidianStyle() {}

    static int dp(Context context, float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    static int sp(Context context, float value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP,
                value, context.getResources().getDisplayMetrics()));
    }

    static TextView text(Context context, int size, int lineHeight, int weight, int color) {
        TextView view = new TextView(context);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, size);
        view.setTextColor(color);
        Typeface type = Typeface.create("sans-serif", Typeface.NORMAL);
        view.setTypeface(android.os.Build.VERSION.SDK_INT >= 28
                ? Typeface.create(type, weight, false)
                : Typeface.create(weight >= 500 ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        view.setIncludeFontPadding(false);
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            view.setLineHeight(sp(context, lineHeight));
        } else {
            view.setLineSpacing(Math.max(0, sp(context, lineHeight) - view.getLineHeight()), 1f);
        }
        return view;
    }
}
