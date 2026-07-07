package com.ericko.redmiscreenbrightness;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) {
            return;
        }

        String action = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)
                && !"android.intent.action.QUICKBOOT_POWERON".equals(action)
                && !"com.htc.intent.action.QUICKBOOT_POWERON".equals(action)) {
            return;
        }

        if (AutoBrightnessManager.isAutoEnabled(context)) {
            ProtectionServiceHealth.markBootRestore(context, action);
            BrightnessLogManager.appendSnapshot(context, "PROTECTION_RESTORE_AFTER_BOOT", AutoBrightnessManager.getLastLux(context));
            AutoBrightnessService.start(context);
        }
    }
}
