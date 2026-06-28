package com.ericko.redmiscreenbrightness;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.provider.Settings;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.widget.Toast;

public class BrightnessTileService extends TileService {
    private static final String PREFS = "brightness_state";
    private static final String KEY_PERCENT = "percent";

    private static final int[] PERCENTS = new int[] {30, 40, 50, 60};
    private static final int[] RAW_VALUES = new int[] {17, 26, 38, 49};

    @Override
    public void onTileAdded() {
        super.onTileAdded();
        updateTileView(getCurrentPercent(this));
    }

    @Override
    public void onStartListening() {
        super.onStartListening();
        updateTileView(getCurrentPercent(this));
    }

    @Override
    public void onClick() {
        super.onClick();

        int current = getCurrentPercent(this);
        int next = getNextPercent(current);
        int raw = getRawForPercent(next);

        boolean ok = applyBrightness(this, next, raw);
        if (ok) {
            saveCurrentPercent(this, next);
            updateTileView(next);
            Toast.makeText(this, "Brightness " + next + "%", Toast.LENGTH_SHORT).show();
        } else {
            updateTileView(current);
            Toast.makeText(this, "Grant modify system settings permission", Toast.LENGTH_SHORT).show();
        }
    }

    public static boolean applyBrightness(Context context, int percent, int raw) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.System.canWrite(context)) {
                return false;
            }
            Settings.System.putInt(
                    context.getContentResolver(),
                    Settings.System.SCREEN_BRIGHTNESS_MODE,
                    Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
            );
            Settings.System.putInt(
                    context.getContentResolver(),
                    Settings.System.SCREEN_BRIGHTNESS,
                    raw
            );
            saveCurrentPercent(context, percent);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static int getCurrentPercent(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, MODE_PRIVATE);
        return prefs.getInt(KEY_PERCENT, 60);
    }

    private static void saveCurrentPercent(Context context, int percent) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, MODE_PRIVATE);
        prefs.edit().putInt(KEY_PERCENT, percent).apply();
    }

    private static int getNextPercent(int current) {
        if (current == 30) return 40;
        if (current == 40) return 50;
        if (current == 50) return 60;
        return 30;
    }

    private static int getRawForPercent(int percent) {
        for (int i = 0; i < PERCENTS.length; i++) {
            if (PERCENTS[i] == percent) {
                return RAW_VALUES[i];
            }
        }
        return RAW_VALUES[0];
    }

    private int getIconForPercent(int percent) {
        if (percent == 40) return R.drawable.ic_tile_40;
        if (percent == 50) return R.drawable.ic_tile_50;
        if (percent == 60) return R.drawable.ic_tile_60;
        return R.drawable.ic_tile_30;
    }

    private void updateTileView(int percent) {
        Tile tile = getQsTile();
        if (tile == null) {
            return;
        }
        tile.setLabel("Redmi Brightness " + percent + "%");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.setSubtitle(percent + "%");
        }
        tile.setIcon(Icon.createWithResource(this, getIconForPercent(percent)));
        tile.setState(Tile.STATE_ACTIVE);
        tile.updateTile();
    }
}
