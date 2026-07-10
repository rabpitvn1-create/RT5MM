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
