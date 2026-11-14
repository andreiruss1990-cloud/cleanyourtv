package com.gathin.tvhome;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.Context;
import android.graphics.Typeface;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * clock_home: the one settings menu, in one order, for the row Up opens on Home and for the Settings button's window
 * over an app. Inputs and All apps depend on where the menu is, so the caller supplies them.
 */
final class SettingsItems {
    static final class Item {
        final int icon;
        final String label;
        final Runnable action;

        Item(int icon, String label, Runnable action) {
            this.icon = icon;
            this.label = label;
            this.action = action;
        }
    }

    private SettingsItems() {
    }

    static List<Item> build(Activity a, Runnable inputs, Runnable allApps) {
        List<Item> items = new ArrayList<>();
        items.add(new Item(R.drawable.ic_wifi, a.getString(R.string.wifi),
                () -> start(a, SystemSettings.intent(a, Settings.ACTION_WIFI_SETTINGS))));
        items.add(new Item(R.drawable.ic_bluetooth, "Bluetooth",
                () -> start(a, SystemSettings.intent(a, Settings.ACTION_BLUETOOTH_SETTINGS))));
        items.add(new Item(R.drawable.ic_picture, a.getString(R.string.quick_picture),
                () -> start(a, SystemSettings.displayIntent(a))));
        items.add(new Item(R.drawable.ic_sound, "Sound",
                () -> start(a, SystemSettings.intent(a, Settings.ACTION_SOUND_SETTINGS))));
        items.add(new Item(R.drawable.ic_input, a.getString(R.string.inputs), inputs));
        items.add(new Item(R.drawable.ic_apps, a.getString(R.string.clock_all_apps), allApps));
        items.add(new Item(R.drawable.ic_apps_pill, a.getString(R.string.quick_apps),
                () -> start(a, SystemSettings.intent(a, Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS))));
        items.add(new Item(R.drawable.ic_settings, a.getString(R.string.settings),
                () -> start(a, SystemSettings.intent(a, Settings.ACTION_SETTINGS))));
        items.add(new Item(R.drawable.ic_home_settings, a.getString(R.string.launcher_settings),
                () -> start(a, new Intent(a, MainActivity.class).setAction(MainActivity.ACTION_LAUNCHER_SETTINGS))));
        return items;
    }

    /** Slots in a band: five whole ones and a slice of the sixth, which shows that the row goes on. */
    static final float SLOTS = 5.35f;

    /** The settings row: the same band, slots and focus as the dock, with a glass card in each slot. */
    static Carousel row(Activity a, float density, int bandWidth, List<Item> items) {
        int pad = Math.round(16 * density);
        int gap = Math.round(18 * density);
        int pitch = Math.round((bandWidth - 2 * pad) / SLOTS);
        int w = pitch - gap;
        int h = Math.round(96 * density);
        Typeface face = null;
        try {
            face = UiFont.get(a, R.string.font_regular);
        } catch (Exception ignored) {
            // the system font is fine
        }
        final Typeface font = face;
        Carousel row = new Carousel(a, density, w, h, gap, pad, 28 * density,
                LiquidGlass.band(density), true, 14 * density, new Carousel.Binder() {
            @Override
            public View create(Context c, int width, int height) {
                LinearLayout card = new LinearLayout(c);
                card.setOrientation(LinearLayout.VERTICAL);
                card.setGravity(Gravity.CENTER);
                ImageView icon = new ImageView(c);
                icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
                icon.setImageTintList(android.content.res.ColorStateList.valueOf(0xFFFFFFFF));
                int size = Math.round(32 * density);
                card.addView(icon, new LinearLayout.LayoutParams(size, size));
                TextView text = new TextView(c);
                text.setTextColor(0xFFFFFFFF);
                text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
                text.setSingleLine(true);
                text.setGravity(Gravity.CENTER);
                if (font != null) text.setTypeface(font);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                lp.topMargin = Math.round(10 * density);
                card.addView(text, lp);
                return card;
            }

            @Override
            public void bind(View view, int index) {
                Item item = items.get(index);
                LinearLayout card = (LinearLayout) view;
                ((ImageView) card.getChildAt(0)).setImageResource(item.icon);
                ((TextView) card.getChildAt(1)).setText(item.label);
            }
        });
        row.setCount(items.size(), 0);
        return row;
    }

    static void start(Activity a, Intent intent) {
        try {
            a.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(a, R.string.not_available, Toast.LENGTH_SHORT).show();
        }
    }
}
