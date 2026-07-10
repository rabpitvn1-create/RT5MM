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

/** Foreground lifecycle shell around the ambient-light controller. */
public final class AutoBrightnessService extends Service {
    private static final String ACTION_START =
            "com.ericko.redmiscreenbrightness.PROTECTION_START";
    private static final String ACTION_STOP =
            "com.ericko.redmiscreenbrightness.PROTECTION_STOP";
    private static final String ACTION_REFRESH =
            "com.ericko.redmiscreenbrightness.PROTECTION_REFRESH";

    private static final String CHANNEL_ID = "screen_protection_channel";
    private static final String CHANNEL_NAME = "Screen Protection";
    private static final int NOTIFICATION_ID = 3001;
    private static final long NOTIFICATION_REFRESH_MS = 15L * 60L * 1000L;
    private static final long HEALTH_UPDATE_MS = 5L * 60L * 1000L;
    private static final long MANUAL_BRIGHTNESS_CONFIRM_MS = 1200L;

    private AutoBrightnessManager manager;
    private Handler handler;
    private boolean foregroundStarted;
    private boolean screenReceiverRegistered;
    private boolean brightnessObserverRegistered;
    private String lastNotificationSignature = "";
    private ContentObserver brightnessObserver;

    private final BroadcastReceiver screenEventReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || !AutoBrightnessManager.isAutoEnabled(AutoBrightnessService.this)) {
                return;
            }
            ensureManager();
            String action = intent.getAction();
            ProtectionServiceHealth.markHeartbeat(AutoBrightnessService.this, "SCREEN_EVENT");

            if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                manager.enterScreenOffSleep("PROTECTION_SCREEN_OFF");
                removeProtectionUpdates();
                BrightnessLogManager.flush(AutoBrightnessService.this);
                updateNotification(true);
                return;
            }

            if (Intent.ACTION_SCREEN_ON.equals(action)) {
                wakeControllerIfSleeping("PROTECTION_SCREEN_ON");
                return;
            }

            if (Intent.ACTION_USER_PRESENT.equals(action)) {
                // SCREEN_ON and USER_PRESENT normally arrive back-to-back. Do not reset
                // fresh ambient history twice; USER_PRESENT is only a fallback wake signal.
                wakeControllerIfSleeping("PROTECTION_USER_PRESENT");
                updateNotification(true);
            }
        }
    };

    private final Runnable notificationRefreshRunnable = new Runnable() {
        @Override
        public void run() {
            if (!AutoBrightnessManager.isAutoEnabled(AutoBrightnessService.this)) return;
            ProtectionServiceHealth.markHeartbeat(
                    AutoBrightnessService.this, "NOTIFICATION_REFRESH");
            updateNotification(false);
            if (handler != null) handler.postDelayed(this, NOTIFICATION_REFRESH_MS);
        }
    };

    /** Health heartbeat only; it never replays persisted lux into the control path. */
    private final Runnable protectionUpdateRunnable = new Runnable() {
        @Override
        public void run() {
            if (handler == null || !AutoBrightnessManager.isAutoEnabled(AutoBrightnessService.this)) {
                return;
            }
            ProtectionServiceHealth.markHeartbeat(
                    AutoBrightnessService.this, "AMBIENT_HEALTH_TICK");
            updateNotification(false);
            if (manager != null && !manager.isScreenOffSleep()) {
                handler.postDelayed(this, HEALTH_UPDATE_MS);
            }
        }
    };

    private final Runnable manualBrightnessConfirmRunnable = new Runnable() {
        @Override
        public void run() {
            if (manager == null || !AutoBrightnessManager.isAutoEnabled(AutoBrightnessService.this)) {
                return;
            }
            manager.confirmPendingExternalBrightnessChange(
                    "USER_BRIGHTNESS_OBSERVER_CONFIRM");
            updateNotification(true);
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
            shutdownAndStop("SERVICE_STOPPED");
            return START_NOT_STICKY;
        }

        if (!AutoBrightnessManager.hasLightSensor(this)) {
            AutoBrightnessManager.markUnavailable(this);
            shutdownAndStop("LIGHT_SENSOR_UNAVAILABLE");
            return START_NOT_STICKY;
        }

        if (!foregroundStarted && !startForegroundSafely()) {
            return START_NOT_STICKY;
        }

        ensureManager();
        registerBrightnessObserver();

        if (isDeviceInteractive()) {
            if (!manager.start()) {
                AutoBrightnessManager.setAutoEnabled(this, false);
                BrightnessLevels.restorePreviousBrightnessMode(this);
                shutdownAndStop("CONTROLLER_START_FAILED");
                return START_NOT_STICKY;
            }
            scheduleProtectionUpdates();
        } else {
            manager.enterScreenOffSleep("PROTECTION_START_SCREEN_OFF");
            removeProtectionUpdates();
        }

        if (ACTION_REFRESH.equals(action)) {
            ProtectionServiceHealth.markHeartbeat(this, "PROTECTION_REFRESH_REQUEST");
            updateNotification(true);
        }

        ProtectionServiceHealth.markHeartbeat(this, "SERVICE_RUNNING");
        scheduleNotificationRefresh();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        removeAllCallbacks();
        unregisterBrightnessObserver();
        unregisterScreenReceiver();
        if (manager != null) {
            manager.stop();
            manager = null;
        }
        ProtectionBatteryStats.flush(this);
        BrightnessLogManager.flush(this);
        stopForegroundCompat();
        ProtectionServiceHealth.markServiceStopped(this, "SERVICE_DESTROYED");
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void ensureManager() {
        if (manager == null) manager = new AutoBrightnessManager(this);
    }

    private void wakeControllerIfSleeping(String event) {
        ensureManager();
        if (manager.isScreenOffSleep()) manager.onScreenWake(event);
        scheduleProtectionUpdates();
        updateNotification(true);
    }

    private boolean startForegroundSafely() {
        try {
            Notification notification = buildNotification();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                        NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
            foregroundStarted = true;
            lastNotificationSignature = buildNotificationSignature();
            ProtectionServiceHealth.markForeground(this, true, "FOREGROUND_STARTED");
            ProtectionServiceHealth.markHeartbeat(this, "FOREGROUND_STARTED");
            return true;
        } catch (Throwable ignored) {
            AutoBrightnessManager.setAutoEnabled(this, false);
            BrightnessLevels.restorePreviousBrightnessMode(this);
            ProtectionServiceHealth.markServiceStopped(this, "FOREGROUND_START_FAILED");
            stopSelf();
            return false;
        }
    }

    private void updateNotification(boolean force) {
        if (!foregroundStarted) return;
        String signature = buildNotificationSignature();
        if (!force && signature.equals(lastNotificationSignature)) return;
        lastNotificationSignature = signature;
        try {
            NotificationManager notifications =
                    (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (notifications != null) notifications.notify(NOTIFICATION_ID, buildNotification());
        } catch (Throwable ignored) {
        }
    }

    private Notification buildNotification() {
        Intent openIntent = new Intent(this, MainActivity.class);
        int pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            pendingFlags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent pendingIntent =
                PendingIntent.getActivity(this, 0, openIntent, pendingFlags);

        AutoBrightnessManager.Mode mode = AutoBrightnessManager.getSavedMode(this);
        ProtectionPowerState powerState = ProtectionBatteryStats.getPowerState(this);
        float lux = AutoBrightnessManager.getLastLux(this);
        long holdMs = AutoBrightnessManager.getUserHoldRemainingMs(this);
        int raw = BrightnessLevels.getSystemRaw(this, -1);
        int percent = raw < 0 ? -1 : BrightnessLevels.getPercentForRaw(raw);

        String contentText;
        String bigText;
        if (powerState == ProtectionPowerState.SCREEN_OFF_SLEEP) {
            contentText = "Sleeping · light sensor paused";
            bigText = "Screen Protection is sleeping to save battery"
                    + "\nLight sensor: paused until the screen turns on"
                    + "\nLast environment: " + formatLux(lux)
                    + "\nMode: " + AutoBrightnessManager.getDisplayMode(mode);
        } else {
            contentText = formatLux(lux)
                    + (raw >= 0 ? " · Raw " + raw : "")
                    + " · " + readablePowerState(powerState);
            bigText = "Screen Protection is active"
                    + "\nEnvironment: " + formatLux(lux)
                    + (percent >= 0 ? "\nBrightness: " + percent + "% · raw " + raw : "")
                    + "\nMode: " + AutoBrightnessManager.getDisplayMode(mode)
                    + "\nPower: " + readablePowerState(powerState)
                    + (holdMs > 0L ? "\nManual hold: " + (holdMs / 1000L) + "s" : "");
        }

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return builder
                .setSmallIcon(iconForPercent(percent))
                .setContentTitle("Screen Protection")
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
        ProtectionPowerState power = ProtectionBatteryStats.getPowerState(this);
        int raw = power == ProtectionPowerState.SCREEN_OFF_SLEEP
                ? -1 : BrightnessLevels.getSystemRaw(this, -1);
        long holdBucket = AutoBrightnessManager.getUserHoldRemainingMs(this) / 60_000L;
        long luxBucket = Math.round(AutoBrightnessManager.getLastLux(this));
        return mode.name() + '|' + power.name() + '|' + raw + '|' + luxBucket + '|' + holdBucket;
    }

    private int iconForPercent(int percent) {
        if (percent < 25) return R.drawable.ic_tile_20;
        if (percent < 35) return R.drawable.ic_tile_30;
        if (percent < 45) return R.drawable.ic_tile_40;
        if (percent < 55) return R.drawable.ic_tile_50;
        return R.drawable.ic_tile_60;
    }

    private void scheduleNotificationRefresh() {
        if (handler == null) handler = new Handler(Looper.getMainLooper());
        handler.removeCallbacks(notificationRefreshRunnable);
        handler.postDelayed(notificationRefreshRunnable, NOTIFICATION_REFRESH_MS);
    }

    private void scheduleProtectionUpdates() {
        if (handler == null) handler = new Handler(Looper.getMainLooper());
        handler.removeCallbacks(protectionUpdateRunnable);
        if (manager != null && !manager.isScreenOffSleep()) {
            handler.postDelayed(protectionUpdateRunnable, HEALTH_UPDATE_MS);
        }
    }

    private void removeProtectionUpdates() {
        if (handler != null) handler.removeCallbacks(protectionUpdateRunnable);
    }

    private void removeAllCallbacks() {
        if (handler == null) return;
        handler.removeCallbacks(notificationRefreshRunnable);
        handler.removeCallbacks(protectionUpdateRunnable);
        handler.removeCallbacks(manualBrightnessConfirmRunnable);
    }

    private void registerBrightnessObserver() {
        if (brightnessObserverRegistered || handler == null) return;
        brightnessObserver = new ContentObserver(handler) {
            @Override
            public void onChange(boolean selfChange) {
                super.onChange(selfChange);
                if (manager == null || !AutoBrightnessManager.isAutoEnabled(AutoBrightnessService.this)) {
                    return;
                }
                boolean candidate = manager.onSystemBrightnessChanged("USER_BRIGHTNESS_OBSERVER");
                if (candidate) {
                    handler.removeCallbacks(manualBrightnessConfirmRunnable);
                    handler.postDelayed(
                            manualBrightnessConfirmRunnable,
                            MANUAL_BRIGHTNESS_CONFIRM_MS);
                    updateNotification(true);
                }
            }
        };
        try {
            getContentResolver().registerContentObserver(
                    Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS),
                    false,
                    brightnessObserver);
            brightnessObserverRegistered = true;
        } catch (Throwable ignored) {
            brightnessObserverRegistered = false;
        }
    }

    private void unregisterBrightnessObserver() {
        if (brightnessObserverRegistered && brightnessObserver != null) {
            try {
                getContentResolver().unregisterContentObserver(brightnessObserver);
            } catch (Throwable ignored) {
            }
        }
        brightnessObserverRegistered = false;
        brightnessObserver = null;
    }

    private void registerScreenReceiver() {
        if (screenReceiverRegistered) return;
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
        if (screenReceiverRegistered) {
            try {
                unregisterReceiver(screenEventReceiver);
            } catch (Throwable ignored) {
            }
        }
        screenReceiverRegistered = false;
        ProtectionServiceHealth.markScreenReceiver(this, false);
    }

    private void shutdownAndStop(String reason) {
        ProtectionServiceHealth.markServiceStopped(this, reason);
        removeAllCallbacks();
        unregisterBrightnessObserver();
        unregisterScreenReceiver();
        if (manager != null) {
            manager.stop();
            manager = null;
        }
        ProtectionBatteryStats.flush(this);
        BrightnessLogManager.flush(this);
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

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Keeps Screen Protection active while enabled.");
        channel.setShowBadge(false);
        NotificationManager notifications =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (notifications != null) notifications.createNotificationChannel(channel);
    }

    private boolean isDeviceInteractive() {
        try {
            PowerManager power = (PowerManager) getSystemService(Context.POWER_SERVICE);
            return power == null || power.isInteractive();
        } catch (Throwable ignored) {
            return true;
        }
    }

    private String formatLux(float lux) {
        if (Float.isNaN(lux) || Float.isInfinite(lux) || lux < 0f) return "Learning environment";
        return String.format(Locale.US, "%.1f lx", lux);
    }

    private String readablePowerState(ProtectionPowerState state) {
        return state.name().toLowerCase(Locale.US).replace('_', ' ');
    }

    public static void start(Context context) {
        Context app = context.getApplicationContext();
        AutoBrightnessManager.setAutoEnabled(app, true);
        if (!AutoBrightnessManager.isAutoEnabled(app)) return;
        Intent intent = new Intent(app, AutoBrightnessService.class).setAction(ACTION_START);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                app.startForegroundService(intent);
            } else {
                app.startService(intent);
            }
        } catch (Throwable ignored) {
            AutoBrightnessManager.setAutoEnabled(app, false);
            BrightnessLevels.restorePreviousBrightnessMode(app);
        }
    }

    public static void stop(Context context) {
        Context app = context.getApplicationContext();
        AutoBrightnessManager.setAutoEnabled(app, false);
        BrightnessLevels.restorePreviousBrightnessMode(app);
        ProtectionServiceHealth.markServiceStopped(app, "USER_STOP_REQUEST");
        BrightnessLogManager.flush(app);
        Intent intent = new Intent(app, AutoBrightnessService.class).setAction(ACTION_STOP);
        try {
            app.startService(intent);
        } catch (Throwable ignored) {
            app.stopService(new Intent(app, AutoBrightnessService.class));
        }
    }

    public static void refresh(Context context) {
        Context app = context.getApplicationContext();
        if (!AutoBrightnessManager.isAutoEnabled(app)) return;
        Intent intent = new Intent(app, AutoBrightnessService.class).setAction(ACTION_REFRESH);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                app.startForegroundService(intent);
            } else {
                app.startService(intent);
            }
        } catch (Throwable ignored) {
        }
    }
}
