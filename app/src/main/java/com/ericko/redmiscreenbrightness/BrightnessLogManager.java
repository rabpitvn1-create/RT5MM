package com.ericko.redmiscreenbrightness;

import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class BrightnessLogManager {
    private static final String KEY_LOG = "brightness_debug_log";
    private static final String KEY_LAST_SIGNATURE = "brightness_debug_last_signature";
    private static final int MAX_LOG_CHARS = 24000;

    private BrightnessLogManager() {
    }

    public static void logSnapshotIfChanged(Context context, String event, float lux) {
        String signature = buildSignature(context, event, lux);
        SharedPreferences prefs = getPrefs(context);
        String lastSignature = prefs.getString(KEY_LAST_SIGNATURE, "");
        if (signature.equals(lastSignature)) {
            return;
        }
        prefs.edit().putString(KEY_LAST_SIGNATURE, signature).apply();
        appendSnapshot(context, event, lux);
    }

    public static void appendSnapshot(Context context, String event, float lux) {
        SharedPreferences prefs = getPrefs(context);
        String oldLog = prefs.getString(KEY_LOG, "");
        String entry = buildEntry(context, event, lux);
        String newLog = oldLog + entry;
        if (newLog.length() > MAX_LOG_CHARS) {
            newLog = newLog.substring(newLog.length() - MAX_LOG_CHARS);
            int firstLineBreak = newLog.indexOf('\n');
            if (firstLineBreak >= 0 && firstLineBreak + 1 < newLog.length()) {
                newLog = newLog.substring(firstLineBreak + 1);
            }
        }
        prefs.edit().putString(KEY_LOG, newLog).apply();
    }

    public static String exportText(Context context) {
        appendSnapshot(context, "EXPORT_LOG", AutoBrightnessManager.getLastLux(context));
        String log = getPrefs(context).getString(KEY_LOG, "");
        int masterAdjust = BrightnessLevels.getMasterAdjust(context);
        String adjustText = masterAdjust > 0 ? "+" + masterAdjust : String.valueOf(masterAdjust);
        return "Redmi Screen Brightness diagnostic log\n"
                + "Device mode: " + getSystemModeText(context) + "\n"
                + "App auto: " + (AutoBrightnessManager.isAutoEnabled(context) ? "on" : "off") + "\n"
                + "App mode: " + AutoBrightnessManager.getDisplayMode(AutoBrightnessManager.getSavedMode(context)) + "\n"
                + "Current raw: " + BrightnessLevels.getSystemRaw(context, -1) + "\n"
                + "Current bucket: " + BrightnessLevels.getCurrentPercent(context) + "%\n"
                + "Master adjust: " + adjustText + " raw\n"
                + "Last lux: " + formatLux(AutoBrightnessManager.getLastLux(context)) + "\n\n"
                + "Events:\n"
                + (log.length() == 0 ? "No events yet.\n" : log);
    }

    public static void clear(Context context) {
        getPrefs(context).edit()
                .remove(KEY_LOG)
                .remove(KEY_LAST_SIGNATURE)
                .apply();
    }

    private static String buildSignature(Context context, String event, float lux) {
        int raw = BrightnessLevels.getSystemRaw(context, -1);
        int mode = getSystemMode(context);
        AutoBrightnessManager.Mode appMode = AutoBrightnessManager.getSavedMode(context);
        boolean appAuto = AutoBrightnessManager.isAutoEnabled(context);
        int luxBucket = lux < 0f ? -1 : Math.round(lux / 5f) * 5;
        int masterAdjust = BrightnessLevels.getMasterAdjust(context);
        return event + "|" + mode + "|" + raw + "|" + appAuto + "|" + appMode.name() + "|" + luxBucket + "|" + masterAdjust;
    }

    private static String buildEntry(Context context, String event, float lux) {
        int raw = BrightnessLevels.getSystemRaw(context, -1);
        int percent = BrightnessLevels.getPercentForRaw(context, raw);
        int masterAdjust = BrightnessLevels.getMasterAdjust(context);
        boolean appAuto = AutoBrightnessManager.isAutoEnabled(context);
        String source;
        if (appAuto) {
            source = "REDMI_AUTO_BRIGHTNESS";
        } else if (getSystemMode(context) == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC) {
            source = "SYSTEM_AUTO_BRIGHTNESS";
        } else {
            source = "SYSTEM_MANUAL_BRIGHTNESS";
        }

        return nowText()
                + " | source=" + source
                + " | event=" + event
                + " | systemMode=" + getSystemModeText(context)
                + " | raw=" + raw
                + " | bucket=" + percent + "%"
                + " | masterAdjust=" + masterAdjust
                + " | lux=" + formatLux(lux)
                + " | appAuto=" + (appAuto ? "on" : "off")
                + " | appMode=" + AutoBrightnessManager.getDisplayMode(AutoBrightnessManager.getSavedMode(context))
                + " | cooldownMs=" + AutoBrightnessManager.getCooldownRemainingMs(context)
                + "\n";
    }

    private static int getSystemMode(Context context) {
        try {
            return Settings.System.getInt(
                    context.getContentResolver(),
                    Settings.System.SCREEN_BRIGHTNESS_MODE,
                    Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
            );
        } catch (Throwable t) {
            return Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL;
        }
    }

    private static String getSystemModeText(Context context) {
        return getSystemMode(context) == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC ? "automatic" : "manual";
    }

    private static String formatLux(float lux) {
        if (lux < 0f) {
            return "unknown";
        }
        return String.format(Locale.US, "%.1f", lux);
    }

    private static String nowText() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(new Date());
    }

    private static SharedPreferences getPrefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(BrightnessLevels.PREFS, Context.MODE_PRIVATE);
    }
}
