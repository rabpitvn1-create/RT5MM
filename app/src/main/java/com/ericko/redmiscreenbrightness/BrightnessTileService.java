package com.ericko.redmiscreenbrightness;

import android.graphics.drawable.Icon;
import android.os.Build;
import android.provider.Settings;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.widget.Toast;

public class BrightnessTileService extends TileService {

    @Override
    public void onTileAdded() {
        super.onTileAdded();
        updateTileView();
    }

    @Override
    public void onStartListening() {
        super.onStartListening();
        if (AutoBrightnessManager.isAutoEnabled(this)) {
            AutoBrightnessService.start(this);
        }
        updateTileView();
    }

    @Override
    public void onClick() {
        super.onClick();

        if (AutoBrightnessManager.isAutoEnabled(this)) {
            AutoBrightnessService.stop(this);
            BrightnessLogManager.appendSnapshot(this, "TILE_PROTECTION_DISABLED", AutoBrightnessManager.getLastLux(this));
            Toast.makeText(this, "Screen Protection off", Toast.LENGTH_SHORT).show();
            updateTileView();
            return;
        }

        if (!Settings.System.canWrite(this)) {
            BrightnessLogManager.appendSnapshot(this, "TILE_WRITE_SETTINGS_MISSING", AutoBrightnessManager.getLastLux(this));
            Toast.makeText(this, "Open app to grant modify system settings", Toast.LENGTH_LONG).show();
            updateTileView();
            return;
        }

        if (!AutoBrightnessManager.hasLightSensor(this)) {
            AutoBrightnessManager.markUnavailable(this);
            BrightnessLogManager.appendSnapshot(this, "TILE_PROTECTION_UNAVAILABLE", AutoBrightnessManager.getLastLux(this));
            Toast.makeText(this, "Light sensor unavailable", Toast.LENGTH_SHORT).show();
            updateTileView();
            return;
        }

        AutoBrightnessService.start(this);
        BrightnessLogManager.appendSnapshot(this, "TILE_PROTECTION_ENABLED", AutoBrightnessManager.getLastLux(this));
        Toast.makeText(this, "Screen Protection on", Toast.LENGTH_SHORT).show();
        updateTileView();
    }

    private int getIconForPercent(int percent) {
        if (percent == 20) return R.drawable.ic_tile_20;
        if (percent == 40) return R.drawable.ic_tile_40;
        if (percent == 50) return R.drawable.ic_tile_50;
        if (percent == 60) return R.drawable.ic_tile_60;
        return R.drawable.ic_tile_30;
    }

    private void updateTileView() {
        Tile tile = getQsTile();
        if (tile == null) {
            return;
        }

        boolean enabled = AutoBrightnessManager.isAutoEnabled(this);
        int percent = BrightnessLevels.getCurrentPercent(this);
        tile.setLabel(enabled ? "Screen Protection" : "Protection Off");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.setSubtitle(enabled ? percent + "% protected" : "Tap to protect");
        }
        tile.setIcon(Icon.createWithResource(this, getIconForPercent(percent)));
        tile.setState(enabled ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        tile.updateTile();
    }
}
