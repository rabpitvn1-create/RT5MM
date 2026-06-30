package com.ericko.redmiscreenbrightness;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;

public class AutoBrightnessService extends Service {
    private AutoBrightnessManager manager;

    @Override
    public void onCreate() {
        super.onCreate();
        manager = new AutoBrightnessManager(this);
        manager.start();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!AutoBrightnessManager.isAutoEnabled(this)) {
            stopSelf();
            return START_NOT_STICKY;
        }

        if (manager == null) {
            manager = new AutoBrightnessManager(this);
        }

        boolean started = manager.start();
        if (!started && !AutoBrightnessManager.hasLightSensor(this)) {
            stopSelf();
            return START_NOT_STICKY;
        }

        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (manager != null) {
            manager.stop();
            manager = null;
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    public static void start(Context context) {
        AutoBrightnessManager.setAutoEnabled(context, true);
        try {
            Intent intent = new Intent(context.getApplicationContext(), AutoBrightnessService.class);
            context.getApplicationContext().startService(intent);
        } catch (Throwable t) {
            AutoBrightnessManager.setAutoEnabled(context, false);
        }
    }

    public static void stop(Context context) {
        AutoBrightnessManager.setAutoEnabled(context, false);
        try {
            Intent intent = new Intent(context.getApplicationContext(), AutoBrightnessService.class);
            context.getApplicationContext().stopService(intent);
        } catch (Throwable ignored) {
        }
    }
}
