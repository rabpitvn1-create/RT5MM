package com.ericko.redmiscreenbrightness;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    private TextView statusText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        int pad = dp(24);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("Redmi Screen Brightness");
        title.setTextSize(22);
        title.setGravity(Gravity.CENTER);
        root.addView(title, new LinearLayout.LayoutParams(-1, -2));

        statusText = new TextView(this);
        statusText.setTextSize(16);
        statusText.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(-1, -2);
        statusParams.setMargins(0, dp(16), 0, dp(16));
        root.addView(statusText, statusParams);

        Button permissionButton = new Button(this);
        permissionButton.setText("Open modify system settings permission");
        permissionButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openPermissionScreen();
            }
        });
        root.addView(permissionButton, new LinearLayout.LayoutParams(-1, -2));

        Button testButton = new Button(this);
        testButton.setText("Test set 30% raw 19");
        testButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean ok = BrightnessTileService.applyBrightness(MainActivity.this, 30, 19);
                Toast.makeText(MainActivity.this, ok ? "Brightness set to 30%" : "Permission missing or blocked", Toast.LENGTH_SHORT).show();
                refreshStatus();
            }
        });
        root.addView(testButton, new LinearLayout.LayoutParams(-1, -2));

        setContentView(root);
        refreshStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
    }

    private void refreshStatus() {
        boolean granted = Settings.System.canWrite(this);
        statusText.setText(granted ? "Permission granted. Add the tile to Control Center." : "Permission missing. Grant modify system settings.");
    }

    private void openPermissionScreen() {
        Intent intent = new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS);
        intent.setData(Uri.parse("package:" + getPackageName()));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
