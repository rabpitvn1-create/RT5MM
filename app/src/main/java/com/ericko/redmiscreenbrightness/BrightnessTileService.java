package com.ericko.redmiscreenbrightness;

import android.content.Context;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.widget.Toast;

public class BrightnessTileService extends TileService {

    @Override
    public void onTileAdded() {
        super.onTileAdded();
        updateTileView(BrightnessLevels.getCurrentPercent(this));
    }

    @Override
    public void onStartListening() {
        super.onStartListening();
        if (AutoBrightnessManager.isAutoEnabled(this)) {
            AutoBrightnessService.start(this);
        }
        updateTileView(BrightnessLevels.getCurrentPercent(this));
    }

    @Override
    public void onClick() {
        super.onClick();

        int current = BrightnessLevels.getCurrentPercent(this);
        int next = BrightnessLevels.getNextPercent(current);
        int raw = BrightnessLevels.getRawForPercent(next);

        boolean ok = applyBrightness(this, next, raw);
        if (ok) {
            AutoBrightnessManager.recordManualOverride(this);
            AutoBrightnessService.refresh(this);
            updateTileView(next);
            Toast.makeText(this, "Brightness " + next + "%", Toast.LENGTH_SHORT).show();
        } else {
            updateTileView(current);
            Toast.makeText(this, "Grant modify system settings permission", Toast.LENGTH_SHORT).show();
        }
    }

    public static boolean applyBrightness(Context context, int percent) {
        return BrightnessLevels.applyBrightness(context, percent);
    }

    public static boolean applyBrightness(Context context, int percent, int raw) {
        return BrightnessLevels.applyBrightness(context, percent, raw);
    }

    public static int getRawForPercent(int percent) {
        return BrightnessLevels.getRawForPercent(percent);
    }

    private int getIconForPercent(int percent) {
        if (percent == 20) return R.drawable.ic_tile_20;
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
