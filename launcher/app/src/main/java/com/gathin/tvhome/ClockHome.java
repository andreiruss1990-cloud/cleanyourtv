package com.gathin.tvhome;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * clock_home: Home opens on a big centred clock over the wallpaper. Down shrinks the clock into the header
 * and brings the dock into focus; Up from the dock (or Back) returns to the clock; Up on the clock opens a row of
 * settings (Wi-Fi, Bluetooth, Display, Sound, Inputs, Apps, Settings) that Left and Right scroll through.
 * The clock screen and the settings row take every arrow key themselves; the grid only sees keys once the
 * clock has been left.
 */
final class ClockHome {
    interface Host {
        /** The clock was left: focus the dock. */
        void focusDock();

        /** The Inputs entry of the settings row. */
        void openInputs();
    }

    private final Activity activity;
    private final Host host;
    private final View content;
    private final View overlay;
    private final TextClock bigClock;
    private final TextClock bigDate;
    private final TextClock headerClock;
    private final View block;
    private final HorizontalScrollView scroll;
    private final LinearLayout bar;
    private final View[] hiddenWhileActive;
    private final boolean animations;
    private final float density;
    private final int savedFocusability;

    private final List<View> cards = new ArrayList<>();
    private final List<Runnable> actions = new ArrayList<>();
    private boolean active;
    private boolean barOpen;
    private int selected;
    private long lastTransition;

    ClockHome(Activity activity, Host host, boolean animations, View... hiddenWhileActive) {
        this.activity = activity;
        this.host = host;
        this.animations = animations;
        this.hiddenWhileActive = hiddenWhileActive;
        density = activity.getResources().getDisplayMetrics().density;
        content = activity.findViewById(R.id.content);
        overlay = activity.findViewById(R.id.clockHome);
        bigClock = activity.findViewById(R.id.bigClock);
        bigDate = activity.findViewById(R.id.bigDate);
        headerClock = activity.findViewById(R.id.clock);
        block = activity.findViewById(R.id.clockHomeBlock);
        scroll = activity.findViewById(R.id.settingsScroll);
        bar = activity.findViewById(R.id.settingsBar);
        savedFocusability = ((ViewGroup) content).getDescendantFocusability();
    }

    boolean isActive() {
        return active;
    }

    /** A held arrow repeats while a transition runs: those repeats must not move the dock selection too. */
    boolean swallowRepeat(KeyEvent event) {
        return event.getRepeatCount() > 0 && System.currentTimeMillis() - lastTransition < 700;
    }

    private long duration(long ms) {
        return animations ? ms : 0;
    }

    /** Shows the clock screen. */
    void show(boolean animated) {
        if (active) {
            if (barOpen) closeBar(false);
            return;
        }
        active = true;
        lastTransition = System.currentTimeMillis();
        ((ViewGroup) content).setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        View focus = activity.getCurrentFocus();
        if (focus != null) focus.clearFocus();
        for (View v : hiddenWhileActive) {
            v.animate().cancel();
            v.setAlpha(0f);
        }
        bigClock.setFormat24Hour(headerClock.getFormat24Hour());
        bigClock.setFormat12Hour(headerClock.getFormat12Hour());
        resetBlock();
        overlay.animate().cancel();
        content.animate().cancel();
        long d = animated ? duration(300) : 0;
        overlay.setVisibility(View.VISIBLE);
        if (d == 0) {
            overlay.setAlpha(1f);
            content.setAlpha(0f);
        } else {
            overlay.setAlpha(0f);
            overlay.animate().alpha(1f).setDuration(d).start();
            content.animate().alpha(0f).setDuration(d).start();
        }
    }

    /** Leaves the clock screen: the clock shrinks into the header and the dock takes focus. */
    private void leave() {
        if (!active) return;
        active = false;
        lastTransition = System.currentTimeMillis();
        closeBar(false);
        ((ViewGroup) content).setDescendantFocusability(savedFocusability);
        long d = duration(320);
        content.animate().cancel();
        overlay.animate().cancel();
        if (d == 0) {
            content.setAlpha(1f);
            overlay.setVisibility(View.GONE);
            resetBlock();
        } else {
            int[] from = new int[2];
            int[] to = new int[2];
            bigClock.getLocationInWindow(from);
            headerClock.getLocationInWindow(to);
            float scale = headerClock.getTextSize() / bigClock.getTextSize();
            bigClock.setPivotX(0f);
            bigClock.setPivotY(0f);
            bigClock.animate().translationX(to[0] - from[0]).translationY(to[1] - from[1])
                    .scaleX(scale).scaleY(scale).alpha(0f).setDuration(d).start();
            bigDate.animate().alpha(0f).setDuration(d / 2).start();
            content.animate().alpha(1f).setDuration(d).start();
            overlay.animate().alpha(1f).setDuration(d).withEndAction(() -> {
                if (!active) {
                    overlay.setVisibility(View.GONE);
                    resetBlock();
                }
            }).start();
        }
        host.focusDock();
    }

    private void resetBlock() {
        bigClock.animate().cancel();
        bigDate.animate().cancel();
        block.animate().cancel();
        bigClock.setTranslationX(0f);
        bigClock.setTranslationY(0f);
        bigClock.setScaleX(1f);
        bigClock.setScaleY(1f);
        bigClock.setPivotX(bigClock.getWidth() / 2f);
        bigClock.setPivotY(bigClock.getHeight() / 2f);
        bigClock.setAlpha(1f);
        bigDate.setAlpha(1f);
        block.setAlpha(1f);
    }

    /** True when the key was used. Only the clock screen and the settings row take keys; the grid has the rest. */
    boolean handleKey(KeyEvent event) {
        if (!active) return false;
        int code = event.getKeyCode();
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        boolean first = down && event.getRepeatCount() == 0;
        boolean ok = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
                || code == KeyEvent.KEYCODE_NUMPAD_ENTER;
        if (barOpen) {
            switch (code) {
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    if (down) select(selected - 1);
                    return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    if (down) select(selected + 1);
                    return true;
                case KeyEvent.KEYCODE_DPAD_UP:
                case KeyEvent.KEYCODE_DPAD_DOWN:
                case KeyEvent.KEYCODE_BACK:
                case KeyEvent.KEYCODE_ESCAPE:
                    if (first) closeBar(true);
                    return true;
                default:
                    if (ok) {
                        if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) activate();
                        return true;
                    }
                    return false;
            }
        }
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (first) leave();
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (first) openBar();
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                return true;
            default:
                return ok;
        }
    }

    // ---- settings row ---------------------------------------------------------------------------

    private void buildCards() {
        if (!cards.isEmpty()) return;
        add(R.drawable.ic_wifi, activity.getString(R.string.wifi),
                () -> start(SystemSettings.intent(activity, Settings.ACTION_WIFI_SETTINGS)));
        add(R.drawable.ic_bluetooth, "Bluetooth",
                () -> start(SystemSettings.intent(activity, Settings.ACTION_BLUETOOTH_SETTINGS)));
        add(R.drawable.ic_picture, activity.getString(R.string.quick_picture),
                () -> start(SystemSettings.displayIntent(activity)));
        add(R.drawable.ic_sound, "Sound",
                () -> start(SystemSettings.intent(activity, Settings.ACTION_SOUND_SETTINGS)));
        add(R.drawable.ic_input, activity.getString(R.string.inputs), host::openInputs);
        add(R.drawable.ic_apps, activity.getString(R.string.quick_apps),
                () -> start(SystemSettings.intent(activity, Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS)));
        add(R.drawable.ic_settings, activity.getString(R.string.settings),
                () -> start(SystemSettings.intent(activity, Settings.ACTION_SETTINGS)));
    }

    private void start(Intent intent) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            activity.startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(activity, R.string.not_available, Toast.LENGTH_SHORT).show();
        }
    }

    private void add(int icon, String label, Runnable action) {
        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER);
        card.setFocusable(false);
        ImageView image = new ImageView(activity);
        image.setImageResource(icon);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        card.addView(image, new LinearLayout.LayoutParams(px(38), px(38)));
        TextView text = new TextView(activity);
        text.setText(label);
        text.setTextColor(0xFFFFFFFF);
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        text.setSingleLine(true);
        text.setGravity(Gravity.CENTER);
        try {
            Typeface face = UiFont.get(activity, R.string.font_regular);
            if (face != null) text.setTypeface(face);
        } catch (Exception ignored) {
            // the system font is fine
        }
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tp.topMargin = px(10);
        card.addView(text, tp);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(px(150), px(112));
        cp.setMargins(px(10), px(6), px(10), px(6));
        bar.addView(card, cp);
        cards.add(card);
        actions.add(action);
    }

    private int px(int dp) {
        return Math.round(dp * density);
    }

    private GradientDrawable background(boolean on) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(px(24));
        g.setColor(on ? 0x66FFFFFF : 0x66000000);
        g.setStroke(px(on ? 2 : 1), on ? 0xDDFFFFFF : 0x33FFFFFF);
        return g;
    }

    private void openBar() {
        buildCards();
        barOpen = true;
        lastTransition = System.currentTimeMillis();
        selected = 0;
        refresh(false);
        scroll.scrollTo(0, 0);
        long d = duration(220);
        scroll.animate().cancel();
        scroll.setVisibility(View.VISIBLE);
        if (d == 0) {
            scroll.setAlpha(1f);
            scroll.setTranslationY(0f);
            block.setAlpha(0.3f);
        } else {
            scroll.setAlpha(0f);
            scroll.setTranslationY(-px(24));
            scroll.animate().alpha(1f).translationY(0f).setDuration(d).start();
            block.animate().alpha(0.3f).setDuration(d).start();
        }
    }

    private void closeBar(boolean animated) {
        if (!barOpen) return;
        barOpen = false;
        lastTransition = System.currentTimeMillis();
        long d = animated ? duration(200) : 0;
        scroll.animate().cancel();
        block.animate().cancel();
        if (d == 0) {
            scroll.setVisibility(View.GONE);
            block.setAlpha(1f);
        } else {
            scroll.animate().alpha(0f).translationY(-px(24)).setDuration(d)
                    .withEndAction(() -> {
                        if (!barOpen) scroll.setVisibility(View.GONE);
                    }).start();
            block.animate().alpha(1f).setDuration(d).start();
        }
    }

    private void select(int index) {
        int next = Math.max(0, Math.min(cards.size() - 1, index));
        if (next == selected) return;
        selected = next;
        refresh(true);
    }

    private void refresh(boolean animated) {
        for (int i = 0; i < cards.size(); i++) {
            View card = cards.get(i);
            boolean on = i == selected;
            card.setBackground(background(on));
            float scale = on ? 1.08f : 1f;
            float alpha = on ? 1f : 0.8f;
            if (animated && animations) card.animate().scaleX(scale).scaleY(scale).alpha(alpha).setDuration(140).start();
            else {
                card.setScaleX(scale);
                card.setScaleY(scale);
                card.setAlpha(alpha);
            }
        }
        View card = cards.get(selected);
        scroll.post(() -> {
            int target = scroll.getPaddingLeft() + card.getLeft() + card.getWidth() / 2 - scroll.getWidth() / 2;
            scroll.smoothScrollTo(Math.max(0, target), 0);
        });
    }

    private void activate() {
        Runnable action = actions.get(selected);
        closeBar(false);
        action.run();
    }
}
