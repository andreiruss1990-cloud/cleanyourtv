package com.gathin.tvhome;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * The panel the remote's Settings button opens when {@code settings_key_panel} is on (HomeKeyService):
 * a few glass rows under the Settings pill, over Home or whatever app is playing. Translucent and in a
 * task of its own, so the app behind keeps running and Back returns to it. Pressing Settings again
 * closes the panel.
 */
public final class QuickSettingsActivity extends Activity {
    @Override
    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(LauncherLocale.wrap(base));   // locale
    }


    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_quick_settings);
        if (getResources().getBoolean(R.bool.rtl_menu_bar)) {
            View panel = findViewById(R.id.panel);
            android.widget.FrameLayout.LayoutParams position =
                    (android.widget.FrameLayout.LayoutParams) panel.getLayoutParams();
            position.gravity = android.view.Gravity.TOP | android.view.Gravity.LEFT;
            int inset = position.getMarginEnd();
            position.setMarginEnd(0);
            position.setMargins(inset, position.topMargin, 0, position.bottomMargin);
            panel.setLayoutParams(position);
        }
        Config config = Config.load(this);
        boolean side = config.quickPanelSide;
        if (side) placeOnSide(config.liquidGlass);
        findViewById(R.id.scrim).setOnClickListener(v -> finish());
        LinearLayout rows = findViewById(R.id.rows);
        addRow(rows, R.drawable.ic_settings, R.string.tv_settings, 0, SystemSettings.intent(this, Settings.ACTION_SETTINGS));
        addRow(rows, R.drawable.ic_picture, R.string.quick_picture, 0, SystemSettings.displayIntent(this));
        addRow(rows, R.drawable.ic_wifi, R.string.quick_network, 0, SystemSettings.intent(this, Settings.ACTION_WIFI_SETTINGS));
        addRow(rows, R.drawable.ic_apps, R.string.quick_apps, R.string.quick_apps_hint,
                SystemSettings.intent(this, Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS));
        // Through Home, so it rebuilds with the new choices when Launcher settings closes.
        addRow(rows, R.drawable.ic_home_settings, R.string.launcher_settings, 0,
                new Intent(this, MainActivity.class).setAction(MainActivity.ACTION_LAUNCHER_SETTINGS));
        if (side) spaceRows(rows);
        rows.getChildAt(0).requestFocus();
    }

    /**
     * quick_panel_side: full height against the right edge (left in Hebrew), see-through. With liquid_glass it floats
     * 24dp in from the edges as a clear glass slab with large rounded corners.
     */
    private void placeOnSide(boolean liquid) {
        float dp = getResources().getDisplayMetrics().density;
        View panel = findViewById(R.id.panel);
        android.widget.FrameLayout.LayoutParams lp = new android.widget.FrameLayout.LayoutParams(
                Math.round(320 * dp), android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                getResources().getBoolean(R.bool.rtl_menu_bar) ? android.view.Gravity.LEFT : android.view.Gravity.RIGHT);
        if (liquid) {
            int inset = Math.round(24 * dp);
            lp.setMargins(inset, inset, inset, inset);
            panel.setBackground(new LiquidGlass(dp, 40 * dp, 0xB3181A20, 0xD90C0D10, false));
            panel.setPadding(Math.round(22 * dp), Math.round(40 * dp), Math.round(22 * dp), Math.round(24 * dp));
        } else {
            panel.setBackgroundResource(R.drawable.glass_side_panel);
            panel.setPadding(Math.round(28 * dp), Math.round(64 * dp), Math.round(28 * dp), Math.round(32 * dp));
        }
        panel.setLayoutParams(lp);
        panel.setElevation(0);
    }

    /** Taller rows with air between them, to suit the full-height sheet. */
    private static void spaceRows(LinearLayout rows) {
        float dp = rows.getResources().getDisplayMetrics().density;
        for (int i = 0; i < rows.getChildCount(); i++) {
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) rows.getChildAt(i).getLayoutParams();
            lp.height = Math.round(50 * dp);
            lp.bottomMargin = Math.round(6 * dp);
            rows.getChildAt(i).setLayoutParams(lp);
        }
    }

    /** Settings pressed again while the panel is up. */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        finish();
    }

    /** Anything else taking the screen (Home, an app, a screensaver) closes the panel. */
    @Override
    @SuppressWarnings("deprecation")
    protected void onPause() {
        super.onPause();
        finish();
        overridePendingTransition(0, 0);
    }

    private void addRow(LinearLayout rows, int icon, int title, int status, Intent target) {
        View row = getLayoutInflater().inflate(R.layout.item_input, rows, false);
        ((ImageView) row.findViewById(R.id.icon)).setImageResource(icon);
        ((TextView) row.findViewById(R.id.title)).setText(title);
        row.findViewById(R.id.dot).setVisibility(View.GONE);
        TextView hint = row.findViewById(R.id.status);
        if (status != 0) hint.setText(status);
        else hint.setVisibility(View.GONE);
        row.setOnClickListener(v -> {
            try {
                startActivity(target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (ActivityNotFoundException | SecurityException e) {
                Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
            }
            finish();
        });
        rows.addView(row);
    }
}
