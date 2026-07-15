package com.ericko.redmiscreenbrightness;

/** Pure policy for deciding when a manual brightness session ends. */
public final class ManualHoldPolicy {
    private ManualHoldPolicy() {
    }

    public static boolean shouldReleaseOnScreenOff(boolean holdActive) {
        return holdActive;
    }

    public static boolean shouldReleaseForForeground(
            String anchorPackage, String foregroundPackage) {
        String anchor = normalize(anchorPackage);
        String foreground = normalize(foregroundPackage);
        return !anchor.isEmpty()
                && !foreground.isEmpty()
                && !anchor.equals(foreground);
    }

    private static String normalize(String packageName) {
        return packageName == null ? "" : packageName.trim();
    }
}
