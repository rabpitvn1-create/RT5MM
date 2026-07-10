package com.ericko.redmiscreenbrightness;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.os.Build;
import android.os.LocaleList;

import java.util.Locale;

/** Stores and applies the language selected inside the app. */
public final class AppLanguage {
    private static final String KEY_LANGUAGE = "app_language";
    public static final String ENGLISH = "en";
    public static final String VIETNAMESE = "vi";

    private AppLanguage() {
    }

    public static Context wrap(Context base) {
        Locale locale = locale(base);
        Configuration configuration = new Configuration(base.getResources().getConfiguration());
        configuration.setLocale(locale);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            configuration.setLocales(new LocaleList(locale));
        }
        return base.createConfigurationContext(configuration);
    }

    public static String get(Context context, int stringRes) {
        return wrap(context).getString(stringRes);
    }

    public static String get(Context context, int stringRes, Object... args) {
        return wrap(context).getString(stringRes, args);
    }

    public static boolean isVietnamese(Context context) {
        return VIETNAMESE.equals(languageCode(context));
    }

    public static void setLanguage(Context context, String languageCode) {
        String normalized = VIETNAMESE.equals(languageCode) ? VIETNAMESE : ENGLISH;
        prefs(context).edit().putString(KEY_LANGUAGE, normalized).apply();
    }

    public static String languageCode(Context context) {
        String saved = prefs(context).getString(KEY_LANGUAGE, null);
        if (VIETNAMESE.equals(saved) || ENGLISH.equals(saved)) return saved;
        return VIETNAMESE.equals(Locale.getDefault().getLanguage()) ? VIETNAMESE : ENGLISH;
    }

    public static String profileName(Context context, float lux) {
        if (Float.isNaN(lux) || Float.isInfinite(lux) || lux < 0f) {
            return isVietnamese(context) ? "không xác định" : "unknown";
        }
        if (!isVietnamese(context)) return ProtectionCurveEngine.getProfileName(lux);
        if (lux <= 3f) return "đêm rất tối";
        if (lux <= 25f) return "rất thiếu sáng";
        if (lux <= 150f) return "trong nhà hơi tối";
        if (lux <= 800f) return "trong nhà";
        if (lux <= 2600f) return "trong nhà sáng";
        return "ngoài trời";
    }

    public static String modeName(Context context, AutoBrightnessManager.Mode mode) {
        if (mode == AutoBrightnessManager.Mode.PROTECTING) {
            return get(context, R.string.mode_protecting);
        }
        if (mode == AutoBrightnessManager.Mode.USER_HOLD) {
            return get(context, R.string.mode_holding);
        }
        if (mode == AutoBrightnessManager.Mode.UNAVAILABLE) {
            return get(context, R.string.mode_unavailable);
        }
        return get(context, R.string.mode_off);
    }

    public static String powerStateName(Context context, ProtectionPowerState state) {
        if (state == ProtectionPowerState.SCREEN_OFF_SLEEP) {
            return get(context, R.string.power_screen_off_sleep);
        }
        if (state == ProtectionPowerState.USER_HOLD_LOW_POWER) {
            return get(context, R.string.power_user_hold_low);
        }
        if (state == ProtectionPowerState.RECOVERY_WAKE) {
            return get(context, R.string.power_recovery_wake);
        }
        if (state == ProtectionPowerState.ACTIVE_SCREEN_ON) {
            return get(context, R.string.power_active_screen_on);
        }
        return get(context, R.string.power_off);
    }

    private static Locale locale(Context context) {
        String code = languageCode(context);
        Locale locale = new Locale(code);
        Locale.setDefault(locale);
        return locale;
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(BrightnessLevels.PREFS, Context.MODE_PRIVATE);
    }
}
