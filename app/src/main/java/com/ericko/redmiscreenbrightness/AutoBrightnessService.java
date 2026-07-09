package com.ericko.redmiscreenbrightness;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.database.ContentObserver;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;

import java.util.Locale;

public class AutoBrightnessService extends Service {
    private static final String ACTION_START = "com.ericko.redmiscreenbrightness.PROTECTION_START";
    private static final String ACTION_STOP = "com.ericko.redmiscreenbrightness.PROTECTION_STOP";
    private static final String ACTION_REFRESH = "com.ericko.redmiscreenbrightness.PROTECTION_REFRESH";
    private static final String CHANNEL_ID = "screen_protection_channel";
    private static final String CHANNEL_NAME = "Screen Protection";
    private static final int NOTIFICATION_ID = 3001;
    private static final long NOTIFICATION_REFRESH_MS = 60000L;
    private static final long PROTECTION_UPDATE_MS = 15000L;
    private static final long MANUAL_BRIGHTNESS_CONFIRM_MS = 3200L;

    private AutoBrightnessManager manager;
    private Handler handler;
    private boolean foregroundStarted = false;
    private boolean screenReceiverRegistered = false;
    private boolean brightnessObserverRegistered = false;
    private String lastNotificationSignature = "";
    private ContentObserver brightnessObserver;

    private final BroadcastReceiver screenEventReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || !AutoBrightnessManager.isAutoEnabled(AutoBrightnessService.this)) {
                return;
            }
            String action = intent.getAction();
            ProtectionServiceHealth.markHeartbeat(AutoBrightnessService.this, "SCREEN_EVENT");
            if (manager == null) {
                manager = new AutoBrightnessManager(AutoBrightnessService.this);
            }
            if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                manager.enterScreenOffSleep("PROTECTION_SCREEN_OFF");
                if (handler != null) {
                    handler.removeCallbacks(protectionUpdateRunnable);
                }
                updateNotification(true);
                return;
            }
            if (Intent.ACTION_SCREEN_ON.equals(action) || Intent.ACTION_USER_PRESENT.equals(action)) {
                BrightnessLevels.captureAndForceManualMode(AutoBrightnessService.this);
                manager.onScreenWake(Intent.ACTION_USER_PRESENT.equals(action) ? "PROTECTION_USER_PRESENT" : "PROTECTION_SCREEN_ON");
                updateNotification(true);
                scheduleProtectionUpdates();
            }
        }
    };

    private final Runnable notificationRefreshRunnable = new Runnable() {
        @Override
        public void run() {
            ProtectionServiceHealth.markHeartbeat(AutoBrightnessService.this, "NOTIFICATION_REFRESH");
            updateNotification(false);
            if (handler != null && AutoBrightnessManager.isAutoEnabled(AutoBrightnessService.this)) {
                handler.postDelayed(this, NOTIFICATION_REFRESH_MS);
            }
        }
    };

    private final Runnable protectionUpdateRunnable = new Runnable() {
        @Override
        public void run() {
            if (handler == null || !AutoBrightnessManager.isAutoEnabled(AutoBrightnessService.this)) {
                return;
            }
            ProtectionServiceHealth.markHeartbeat(AutoBrightnessService.this, "PROTECTION_INTERVAL_TICK");
            if (manager != null && !manager.isScreenOffSleep()) {
                manager.evaluateLastLux("PROTECTION_INTERVAL_TICK");
            }
            updateNotification(false);
            if (handler != null && manager != null && !manager.isScreenOffSleep()) {
                handler.postDelayed(this, PROTECTION_UPDATE_MS);
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        ProtectionServiceHealth.markServiceCreated(this, "SERVICE_CREATED");
        handler = new Handler(Looper.getMainLooper());
        manager = new AutoBrightnessManager(this);
        createNotificationChannel();
        registerScreenReceiver();
        registerBrightnessObserver();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        ProtectionServiceHealth.markHeartbeat(this, "SERVICE_START_COMMAND");

        if (ACTION_STOP.equals(action) || !AutoBrightnessManager.isAutoEnabled(this)) {
            BrightnessLevels.restorePreviousBrightnessMode(this);
            shutdownAndStop();
            return START_NOT_STICKY;
        }

        if (!AutoBrightnessManager.hasLightSensor(this)) {
            AutoBrightnessManager.markUnavailable(this);
            BrightnessLevels.restorePreviousBrightnessMode(this);
            ProtectionServiceHealth.markServiceStopped(this, "LIGHT_SENSOR_UNAVAILABLE");
            shutdownAndStop();
            return START_NOT_STICKY;
        }

        BrightnessLevels.captureAndForceManualMode(this);

        if (!foregroundStarted) {
            startForegroundSafely();
        }

        if (manager == null) {
            manager = new AutoBrightnessManager(this);
        }
        registerBrightnessObserver();

        if (isDeviceInteractive()) {
            manager.start();
            scheduleProtectionUpdates();
        } else {
            manager.enterScreenOffSleep("PROTECTION_START_SCREEN_OFF");
            if (handler != null) {
                handler.removeCallbacks(protectionUpdateRunnable);
            }
        }

        ProtectionServiceHealth.markHeartbeat(this, "SERVICE_RUNNING");
        scheduleNotificationRefresh();

        if (ACTION_REFRESH.equals(action)) {
            if (!manager.isScreenOffSleep()) {
                manager.evaluateLastLux("PROTECTION_REFRESH_REQUEST");
            }
            ProtectionServiceHealth.markHeartbeat(this, "PROTECTION_REFRESH_REQUEST");
            updateNotification(true);
        }

        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (handler != null) {
            handler.removeCallbacks(notificationRefreshRunnable);
            handler.removeCallbacks(protectionUpdateRunnable);
            handler.removeCallbacks(manualBrightnessConfirmRunnable);
        }
        unregisterBrightnessObserver();
        unregisterScreenReceiver();
        if (manager != null) {
            manager.stop();
            manager = null;
        }
        ProtectionBatteryStats.flush(this);
        stopForegroundCompat();
        ProtectionServiceHealth.markServiceStopped(this, "SERVICE_DESTROYED");
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void startForegroundSafely() {
        Notification notification = buildNotification();
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
            foregroundStarted = true;
            ProtectionServiceHealth.markForeground(this, true, "FOREGROUND_STARTED");
            ProtectionServiceHealth.markHeartbeat(this, "FOREGROUND_STARTED");
            lastNotificationSignature = buildNotificationSignature();
        } catch (Throwable t) {
            AutoBrightnessManager.setAutoEnabled(this, false);
            BrightnessLevels.restorePreviousBrightnessMode(this);
            ProtectionServiceHealth.markServiceStopped(this, "FOREGROUND_START_FAILED");
            stopSelf();
        }
    }

    private void updateNotification(boolean force) {
        if (!foregroundStarted) {
            return;
        }
        String signature = buildNotificationSignature();
        if (!force && signature.equals(lastNotificationSignature)) {
            return;
        }
        lastNotificationSignature = signature;
        try {
            NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (notificationManager != null) {
                notificationManager.notify(NOTIFICATION_ID, buildNotification());
            }
        } catch (Throwable ignored) {
        }
    }

    private Notification buildNotification() {
        Intent openIntent = new Intent(this, MainActivity.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, openIntent, flags);

        AutoBrightnessManager.Mode mode = AutoBrightnessManager.getSavedMode(this);
        ProtectionPowerState powerState = ProtectionBatteryStats.getPowerState(this);
        float lux = AutoBrightnessManager.getLastLux(this);
        long holdMs = AutoBrightnessManager.getUserHoldRemainingMs(this);
        int holdRaw = AutoBrightnessManager.getUserHoldRaw(this);

        String luxText = formatLux(lux);
        String modeText = AutoBrightnessManager.getDisplayMode(mode);
        String holdText = holdMs > 0L ? "Holding: " + (holdMs / 1000L) + "s" + (holdRaw >= 0 ? " / raw " + holdRaw : "") : "Holding: inactive";

        String contentText;
        String bigText;
        if (powerState == ProtectionPowerState.SCREEN_OFF_SLEEP) {
            contentText = "Sleeping · sensor paused";
            bigText = "Screen Protection đang nghỉ tiết kiệm pin"
                    + "\nSensor ánh sáng: tạm dừng đến khi màn hình bật"
                    + "\nLux gần nhất: " + luxText
                    + "\nMode: " + modeText
                    + "\n" + holdText;
        } else {
            int percent = BrightnessLevels.getCurrentPercent(this);
            int raw = BrightnessLevels.getSystemRaw(this, BrightnessLevels.getRawForPercent(percent));
            contentText = "Lux: " + luxText + " · Raw: " + raw + " · " + powerState.name();
            bigText = "Screen Protection đang bật"
                    + "\nLux hiện tại: " + luxText
                    + "\nBrightness: " + percent + "% / raw " + raw
                    + "\nMode: " + modeText
                    + "\nPower: " + powerState.name()
                    + "\n" + holdText;
        }

        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }

        return builder
                .setSmallIcon(R.drawable.ic_tile_30)
                .setContentTitle("Screen Protection đang bật")
                .setContentText(contentText)
                .setStyle(new Notification.BigTextStyle().bigText(bigText))
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setPriority(Notification.PRIORITY_LOW)
                .setCategory(Notification.CATEGORY_SERVICE)
                .build();
    }

    private String buildNotificationSignature() {
        AutoBrightnessManager.Mode mode = AutoBrightnessManager.getSavedMode(this);
        long holdBucket = AutoBrightnessManager.getUserHoldRemainingMs(this) / 60L;
        float lux = AutoBrightnessManager.getLastLux(this);
        ProtectionPowerState powerState = ProtectionBatteryStats.getPowerState(this);
        if (powerState == ProtectionPowerState.SCREEN_OFF_SLEEP) {
            return mode.name() + "|" + powerState.name() + "|sleep|" + Math.round(lux) + "|" + holdBucket;
        }
        int percent = BrightnessLevels.getCurrentPercent(this);
        int raw = BrightnessLevels.getSystemRaw(this, -1);
        return mode.name() + "|" + powerState.name() + "|" + percent + "|" + raw + "|" + Math.round(lux) + "|" + holdBucket;
    }

    private void scheduleNotificationRefresh() {
        if (handler == null) {
            handler = new Handler(Looper.getMainLooper());
        }
        handler.removeCallbacks(notificationRefreshRunnable);
        handler.postDelayed(notificationRefreshRunnable, NOTIFICATION_REFRESH_MS);
    }

    private void scheduleProtectionUpdates() {
        if (handler == null) {
            handler = new Handler(Looper.getMainLooper());
        }
        handler.removeCallbacks(protectionUpdateRunnable);
        if (manager == null || manager.isScreenOffSleep()) {
            return;
        }
        handler.postDelayed(protectionUpdateRunnable, PROTECTION_UPDATE_MS);
    }

    private void registerBrightnessObserver() {
        if (brightnessObserverRegistered || handler == null) {
            return;
        }
        brightnessObserver = new ContentObserver(handler) {
            @Override
            public void onChange(boolean selfChange) {
                super.onChange(selfChange);
                handleBrightnessObserverChange();
            }
        };
        try {
            getContentResolver().registerContentObserver(
                    Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS),
                    false,
                    brightnessObserver
            );
            brightnessObserverRegistered = true;
        } catch (Throwable ignored) {
            brightnessObserverRegistered = false;
        }
    }

    private void unregisterBrightnessObserver() {
        if (!brightnessObserverRegistered || brightnessObserver == null) {
            brightnessObserverRegistered = false;
            brightnessObserver = null;
            return;
        }
        try {
            getContentResolver().unregisterContentObserver(brightnessObserver);
        } catch (Throwable ignored) {
        }
        brightnessObserverRegistered = false;
        brightnessObserver = null;
    }

    private void handleBrightnessObserverChange() {
        if (handler == null || !AutoBrightnessManager.isAutoEnabled(this)) {
            return;
        }
        if (manager == null) {
            manager = new AutoBrightnessManager(this);
        }
        boolean candidate = manager.onSystemBrightnessChanged("USER_BRIGHTNESS_OBSERVER");
        if (candidate) {
            handler.removeCallbacks(manualBrightnessConfirmRunnable);
            handler.postDelayed(manualBrightnessConfirmRunnable, MANUAL_BRIGHTNESS_CONFIRM_MS);
            updateNotification(true);
        }
    }

    private final Runnable manualBrightnessConfirmRunnable = new Runnable() {
        @Override
        public void run() {
            if (manager != null && AutoBrightnessManager.isAutoEnabled(AutoBrightnessService.this)) {
                manager.confirmPendingExternalBrightnessChange("USER_BRIGHTNESS_OBSERVER_CONFIRM");
                updateNotification(true);
            }
        }
    };

    private void shutdownAndStop() {
        ProtectionServiceHealth.markServiceStopped(this, "SERVICE_STOPPED");
        if (handler != null) {
            handler.removeCallbacks(notificationRefreshRunnable);
            handler.removeCallbacks(protectionUpdateRunnable);
            handler.removeCallbacks(manualBrightnessConfirmRunnable);
        }
        unregisterBrightnessObserver();
        unregisterScreenReceiver();
        if (manager != null) {
            manager.stop();
            manager = null;
        }
        ProtectionBatteryStats.flush(this);
        stopForegroundCompat();
        stopSelf();
    }

    private void stopForegroundCompat() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE);
            } else {
                stopForeground(true);
            }
        } catch (Throwable ignored) {
        }
        foregroundStarted = false;
        ProtectionServiceHealth.markForeground(this, false, "FOREGROUND_STOPPED");
    }

    private void registerScreenReceiver() {
        if (screenReceiverRegistered) {
            return;
        }
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_USER_PRESENT);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(screenEventReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(screenEventReceiver, filter);
            }
            screenReceiverRegistered = true;
        } catch (Throwable ignored) {
            screenReceiverRegistered = false;
        }
        ProtectionServiceHealth.markScreenReceiver(this, screenReceiverRegistered);
    }

    private void unregisterScreenReceiver() {
        if (!screenReceiverRegistered) {
            ProtectionServiceHealth.markScreenReceiver(this, false);
            return;
        }
        try {
            unregisterReceiver(screenEventReceiver);
        } catch (Throwable ignored) {
        }
        screenReceiverRegistered = false;
        ProtectionServiceHealth.markScreenReceiver(this, false);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
        );
        channel.setDescription("Keeps screen protection active while enabled.");
        channel.setShowBadge(false);
        NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (notificationManager != null) {
            notificationManager.createNotificationChannel(channel);
        }
    }

    private boolean isDeviceInteractive() {
        try {
            PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (powerManager == null) {
                return true;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH) {
                return powerManager.isInteractive();
            }
            return powerManager.isScreenOn();
        } catch (Throwable t) {
            return true;
        }
    }

    private String formatLux(float lux) {
        if (lux < 0f) {
            return "unknown";
        }
        return String.format(Locale.US, "%.1f lx", lux);
    }

    public static void start(Context context) {
        BrightnessLevels.captureAndForceManualMode(context);
        AutoBrightnessManager.setAutoEnabled(context, true);
        Intent intent = new Intent(context, AutoBrightnessService.class);
        intent.setAction(ACTION_START);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    public static void stop(Context context) {
        AutoBrightnessManager.setAutoEnabled(context, false);
        BrightnessLevels.restorePreviousBrightnessMode(context);
        ProtectionServiceHealth.markServiceStopped(context, "USER_STOP_REQUEST");
        Intent intent = new Intent(context, AutoBrightnessService.class);
        intent.setAction(ACTION_STOP);
        try {
            context.startService(intent);
        } catch (Throwable ignored) {
        }
    }

    public static void refresh(Context context) {
        Intent intent = new Intent(context, AutoBrightnessService.class);
        intent.setAction(ACTION_REFRESH);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
        } catch (Throwable ignored) {
        }
    }
}
