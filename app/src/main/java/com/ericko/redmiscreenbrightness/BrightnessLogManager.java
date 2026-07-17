package com.ericko.redmiscreenbrightness;

import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class BrightnessLogManager {
    private static final String KEY_LOG = "brightness_debug_log";
    private static final int MAX_LOG_CHARS = 24000;

    private static final Object LOCK = new Object();
    private static final StringBuilder RAM_LOG = new StringBuilder();
    private static String lastSignature = "";
    private static boolean loaded;
    private static boolean dirty;

    private BrightnessLogManager() {
    }

    /**
     * Sensor and transition hot paths never write SharedPreferences. Duplicate
     * suppression and log accumulation stay in RAM until an explicit flush.
     */
    public static void logSnapshotIfChanged(Context context, String event, float lux) {
        String signature = buildSignature(context, event, lux);
        synchronized (LOCK) {
            ensureLoadedLocked(context);
            if (signature.equals(lastSignature)) {
                ProtectionBatteryStats.recordNormalLogSkip(context);
                return;
            }
            lastSignature = signature;
            appendEntryLocked(buildEntry(context, event, lux));
        }
    }

    public static void appendSnapshot(Context context, String event, float lux) {
        synchronized (LOCK) {
            ensureLoadedLocked(context);
            appendEntryLocked(buildEntry(context, event, lux));
        }
    }

    /** Writes the accumulated diagnostics once, normally on service stop. */
    public static void flush(Context context) {
        synchronized (LOCK) {
            ensureLoadedLocked(context);
            if (!dirty) {
                return;
            }
            getPrefs(context).edit().putString(KEY_LOG, RAM_LOG.toString()).apply();
            dirty = false;
        }
    }

    public static String exportText(Context context) {
        appendSnapshot(context, "EXPORT_LOG", AutoBrightnessManager.getLastLux(context));
        flush(context);
        String log;
        synchronized (LOCK) {
            log = RAM_LOG.toString();
        }
        return context.getString(
                R.string.log_export_header,
                getSystemModeText(context),
                context.getString(AutoBrightnessManager.isAutoEnabled(context)
                        ? R.string.value_on : R.string.value_off),
                AutoBrightnessManager.getDisplayMode(
                        context, AutoBrightnessManager.getSavedMode(context)),
                BrightnessLevels.getSystemRaw(context, -1),
                BrightnessLevels.getCurrentPercent(context),
                formatLux(context, AutoBrightnessManager.getLastLux(context)))
                + "\n\n" + context.getString(R.string.log_events)
                + "\n" + (log.length() == 0
                        ? context.getString(R.string.log_no_events) + "\n"
                        : log);
    }

    public static void clear(Context context) {
        synchronized (LOCK) {
            RAM_LOG.setLength(0);
            lastSignature = "";
            loaded = true;
            dirty = false;
        }
        getPrefs(context).edit().remove(KEY_LOG).apply();
    }

    private static void ensureLoadedLocked(Context context) {
        if (loaded) {
            return;
        }
        String persisted = getPrefs(context).getString(KEY_LOG, "");
        RAM_LOG.setLength(0);
        RAM_LOG.append(persisted);
        trimLocked();
        loaded = true;
        dirty = false;
    }

    private static void appendEntryLocked(String entry) {
        RAM_LOG.append(entry);
        trimLocked();
        dirty = true;
    }

    private static void trimLocked() {
        if (RAM_LOG.length() <= MAX_LOG_CHARS) {
            return;
        }
        int removeCount = RAM_LOG.length() - MAX_LOG_CHARS;
        int firstLineBreak = RAM_LOG.indexOf("\n", removeCount);
        if (firstLineBreak >= 0 && firstLineBreak + 1 < RAM_LOG.length()) {
            RAM_LOG.delete(0, firstLineBreak + 1);
        } else {
            RAM_LOG.delete(0, removeCount);
        }
    }

    private static String buildSignature(Context context, String event, float lux) {
        int raw = BrightnessLevels.getCachedSystemRaw(context, -1);
        int mode = getSystemMode(context);
        AutoBrightnessManager.Mode appMode = AutoBrightnessManager.getSavedMode(context);
        boolean appAuto = AutoBrightnessManager.isAutoEnabled(context);
        int luxBucket = lux < 0f ? -1 : Math.round(lux / 5f) * 5;
        return event + "|" + mode + "|" + raw + "|" + appAuto + "|" + appMode.name() + "|" + luxBucket;
    }

    private static String buildEntry(Context context, String event, float lux) {
        int raw = BrightnessLevels.getCachedSystemRaw(context, -1);
        int percent = BrightnessLevels.getPercentForRaw(raw);
        boolean appAuto = AutoBrightnessManager.isAutoEnabled(context);
        String source;
        if (appAuto) {
            source = "SCREEN_PROTECTION";
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
                + " | lux=" + formatLux(context, lux)
                + " | protection=" + (appAuto ? "on" : "off")
                + " | appMode=" + AutoBrightnessManager.getDisplayMode(
                        context, AutoBrightnessManager.getSavedMode(context))
                + " | userHoldActive=" + AutoBrightnessManager.isUserHoldActive(context)
                + " | userHoldPackage=" + AutoBrightnessManager.getUserHoldPackage(context)
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
        return getSystemMode(context) == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
                ? context.getString(R.string.system_mode_automatic)
                : context.getString(R.string.system_mode_manual);
    }

    private static String formatLux(Context context, float lux) {
        if (lux < 0f) {
            return context.getString(R.string.value_unknown);
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
