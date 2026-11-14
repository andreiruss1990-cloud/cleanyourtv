package com.gathin.tvhome;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

/**
 * The window the remote's Settings button opens over an app (settings_key_panel, HomeKeyService). Translucent and in a
 * task of its own, so the app behind keeps running and Back returns to it; pressing Settings again closes it.
 *
 * With clock_home it is the same settings row Home shows in its header (SettingsItems), at the same place, without the
 * blur: this window cannot see the picture behind it. Otherwise it is the original list of glass rows top right.
 */
public final class QuickSettingsActivity extends Activity {
    private Carousel row;
    private List<SettingsItems.Item> items;

    @Override
    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(LauncherLocale.wrap(base));   // locale
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Config config = Config.load(this);
        if (config.clockHome) {
            buildRow();
            return;
        }
        setContentView(R.layout.activity_quick_settings);
        if (getResources().getBoolean(R.bool.rtl_menu_bar)) {
            View panel = findViewById(R.id.panel);
            FrameLayout.LayoutParams position = (FrameLayout.LayoutParams) panel.getLayoutParams();
            position.gravity = Gravity.TOP | Gravity.LEFT;
            int inset = position.getMarginEnd();
            position.setMarginEnd(0);
            position.setMargins(inset, position.topMargin, 0, position.bottomMargin);
            panel.setLayoutParams(position);
        }
        findViewById(R.id.scrim).setOnClickListener(v -> finish());
        LinearLayout rows = findViewById(R.id.rows);
        addRow(rows, R.drawable.ic_settings, R.string.tv_settings, 0,
                SystemSettings.intent(this, android.provider.Settings.ACTION_SETTINGS));
        addRow(rows, R.drawable.ic_picture, R.string.quick_picture, 0, SystemSettings.displayIntent(this));
        addRow(rows, R.drawable.ic_wifi, R.string.quick_network, 0,
                SystemSettings.intent(this, android.provider.Settings.ACTION_WIFI_SETTINGS));
        addRow(rows, R.drawable.ic_apps, R.string.quick_apps, R.string.quick_apps_hint,
                SystemSettings.intent(this, android.provider.Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS));
        // Through Home, so it rebuilds with the new choices when Launcher settings closes.
        addRow(rows, R.drawable.ic_home_settings, R.string.launcher_settings, 0,
                new Intent(this, MainActivity.class).setAction(MainActivity.ACTION_LAUNCHER_SETTINGS));
        rows.getChildAt(0).requestFocus();
    }

    /** clock_home: the settings row across the top, as on Home. */
    private void buildRow() {
        float density = getResources().getDisplayMetrics().density;
        FrameLayout root = new FrameLayout(this);
        root.setOnClickListener(v -> finish());
        setContentView(root);
        int side = Math.round(56 * density);
        int bandW = getResources().getDisplayMetrics().widthPixels - 2 * side;
        items = SettingsItems.build(this,
                () -> SettingsItems.start(this, new Intent(MainActivity.ACTION_VIEW_INPUTS).setPackage(getPackageName())),
                () -> SettingsItems.start(this, new Intent(this, MainActivity.class).setAction(MainActivity.ACTION_ALL_APPS)),
                () -> SettingsItems.start(this, new Intent(this, MainActivity.class).setAction(MainActivity.ACTION_ART)));
        row = SettingsItems.row(this, density, bandW, items);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(bandW, row.bandHeight(), Gravity.TOP | Gravity.START);
        lp.leftMargin = side;
        lp.topMargin = Math.round(44 * density);
        root.addView(row, lp);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (row == null) return super.dispatchKeyEvent(event);
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        boolean repeat = event.getRepeatCount() > 0;
        switch (event.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (down) row.step(-1, repeat);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (down) row.step(1, repeat);
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_ESCAPE:
                if (down && !repeat) finish();
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
                    int i = row.current();
                    finish();
                    if (i >= 0) items.get(i).action.run();
                }
                return true;
            default:
                return super.dispatchKeyEvent(event);
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
            SettingsItems.start(this, target);
            finish();
        });
        rows.addView(row);
    }
}
