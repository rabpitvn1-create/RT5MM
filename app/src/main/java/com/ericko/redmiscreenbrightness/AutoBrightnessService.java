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
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import java.util.Locale;

public class AutoBrightnessService extends Service {
    private static final String ACTION_START = "com.ericko.redmiscreenbrightness.PROTECTION_START";
    private static final String ACTION_STOP = "com.ericko.redmiscreenbrightness.PROTECTION_STOP";
    private static final String ACTION_REFRESH = "com.ericko.redmiscreenbrightness.PROTECTION_REFRESH";
    private static final String CHANNEL_ID = "screen_protection_channel";
    private static final String CHANNEL_NAME = "Screen Protection";
    private static final int NOTIFICATION_ID = 3001;
    private static final long NOTIFICATION_REFRESH_MS = 30000L;
    private static final long PROTECTION_UPDATE_MS = 5000L;

    private AutoBrightnessManager manager;
    private Handler handler;
    private boolean foregroundStarted = false;
    private boolean screenReceiverRegistered = false;
    private String lastNotificationSignature = "";

    private final BroadcastReceiver screenEventReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || !AutoBrightnessManager.isAutoEnabled(AutoBrightnessService.this)) {
                return;
            }
            String action = intent.getAction();
            if (Intent.ACTION_SCREEN_ON.equals(action) || Intent.ACTION_USER_PRESENT.equals(action)) {
                if (manager == null) {
                    manager = new AutoBrightnessManager(AutoBrightnessService.this);
                }
                manager.resumeProtection(Intent.ACTION_USER_PRESENT.equals(action) ? "PROTECTION_USER_PRESENT" : "PROTECTION_SCREEN_ON");
                updateNotification(true);
                scheduleProtectionUpdates();
            }
        }
    };

    private final Runnable notificationRefreshRunnable = new Runnable() {
        @Override
        public void run() {
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
            if (manager != null) {
                manager.evaluateLastLux("PROTECTION_INTERVAL_TICK");
            }
            updateNotification(false);
            handler.postDelayed(this, PROTECTION_UPDATE_MS);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        handler = new Handler(Looper.getMainLooper());
        manager = new AutoBrightnessManager(this);
        createNotificationChannel();
        registerScreenReceiver();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();

        if (ACTION_STOP.equals(action) || !AutoBrightnessManager.isAutoEnabled(this)) {
            shutdownAndStop();
            return START_NOT_STICKY;
        }

        if (!AutoBrightnessManager.hasLightSensor(this)) {
            AutoBrightnessManager.markUnavailable(this);
            shutdownAndStop();
            return START_NOT_STICKY;
        }

        if (!foregroundStarted) {
            startForegroundSafely();
        }

        if (manager == null) {
            manager = new AutoBrightnessManager(this);
        }
        manager.start();
        scheduleNotificationRefresh();
        scheduleProtectionUpdates();

        if (ACTION_REFRESH.equals(action)) {
            manager.resumeProtection("PROTECTION_REFRESH_REQUEST");
            updateNotification(true);
        }

        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (handler != null) {
            handler.removeCallbacks(notificationRefreshRunnable);
            handler.removeCallbacks(protectionUpdateRunnable);
        }
        unregisterScreenReceiver();
        if (manager != null) {
            manager.stop();
            manager = null;
        }
        stopForegroundCompat();
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
            lastNotificationSignature = buildNotificationSignature();
        } catch (Throwable t) {
            AutoBrightnessManager.setAutoEnabled(this, false);
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
        int percent = BrightnessLevels.getCurrentPercent(this);
        int raw = BrightnessLevels.getSystemRaw(this, BrightnessLevels.getRawForPercent(percent));
        float lux = AutoBrightnessManager.getLastLux(this);
        long cooldownMs = AutoBrightnessManager.getCooldownRemainingMs(this);

        String luxText = formatLux(lux);
        String modeText = AutoBrightnessManager.getDisplayMode(mode);
        String cooldownText = cooldownMs > 0L ? "Manual pause: " + (cooldownMs / 1000L) + "s" : "Manual pause: inactive";
        String contentText = "Lux: " + luxText + " · Raw: " + raw + " · " + modeText;
        String bigText = "Screen Protection đang bật"
                + "\nLux hiện tại: " + luxText
                + "\nBrightness: " + percent + "% / raw " + raw
                + "\nMode: " + modeText
                + "\n" + cooldownText;

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
        int percent = BrightnessLevels.getCurrentPercent(this);
        long cooldownBucket = AutoBrightnessManager.getCooldownRemainingMs(this) / 1000L;
        float lux = AutoBrightnessManager.getLastLux(this);
        int raw = BrightnessLevels.getSystemRaw(this, -1);
        return mode.name() + "|" + percent + "|" + raw + "|" + Math.round(lux) + "|" + cooldownBucket;
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
        handler.postDelayed(protectionUpdateRunnable, PROTECTION_UPDATE_MS);
    }

    private void shutdownAndStop() {
        if (handler != null) {
            handler.removeCallbacks(notificationRefreshRunnable);
            handler.removeCallbacks(protectionUpdateRunnable);
        }
        unregisterScreenReceiver();
        if (manager != null) {
            manager.stop();
            manager = null;
        }
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
    }

    private void registerScreenReceiver() {
        if (screenReceiverRegistered) {
            return;
        }
        IntentFilter filter = new IntentFilter();
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
    }

    private void unregisterScreenReceiver() {
        if (!screenReceiverRegistered) {
            return;
        }
        try {
            unregisterReceiver(screenEventReceiver);
        } catch (Throwable ignored) {
        }
        screenReceiverRegistered = false;
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

    private String formatLux(float lux) {
        if (lux < 0f) {
            return "unknown";
        }
        return String.format(Locale.US, "%.1f lx", lux);
    }

    public static void start(Context context) {
        AutoBrightnessManager.setAutoEnabled(context, true);
        startServiceWithAction(context, ACTION_START);
    }

    public static void refresh(Context context) {
        if (AutoBrightnessManager.isAutoEnabled(context)) {
            startServiceWithAction(context, ACTION_REFRESH);
        }
    }

    public static void stop(Context context) {
        AutoBrightnessManager.setAutoEnabled(context, false);
        startServiceWithAction(context, ACTION_STOP);
    }

    private static void startServiceWithAction(Context context, String action) {
        try {
            Intent intent = new Intent(context.getApplicationContext(), AutoBrightnessService.class);
            intent.setAction(action);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.getApplicationContext().startForegroundService(intent);
            } else {
                context.getApplicationContext().startService(intent);
            }
        } catch (Throwable t) {
            if (!ACTION_STOP.equals(action)) {
                AutoBrightnessManager.setAutoEnabled(context, false);
            }
        }
    }
}
