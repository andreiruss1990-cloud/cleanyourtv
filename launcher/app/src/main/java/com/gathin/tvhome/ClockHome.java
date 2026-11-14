package com.gathin.tvhome;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * clock_home: Home is the wallpaper with the clock, date and weather top left and the dock out of sight. Down slides
 * the dock (and the rows of apps under it) in and focuses it; Up from the dock row (or Back) puts it away again;
 * Up on the bare page opens a row of settings (Wi-Fi, Bluetooth, Display, Sound, Inputs, Apps, Settings) that Left
 * and Right scroll through. The bare page and the settings row take every arrow key themselves; the grid only sees
 * keys once the dock is in.
 *
 * The slides run on their own frame loop, not on Android's animators: many TVs ship with the animator duration
 * scale at 0, which turns every ViewPropertyAnimator into a jump cut.
 */
final class ClockHome {
    interface Host {
        /** The dock slid in: focus it. */
        void focusDock();

        /** The Inputs entry of the settings row. */
        void openInputs();

        /** The dock band's bottom edge in gridWrap's coordinates with the dock parked at the grid's top, or -1. */
        float dockBottomInWrap();

        /** gridWrap moved or faded (every frame of a slide): things drawn outside it that follow it catch up. */
        void gridMoved();

        /** A slide ended: put the highlight and the app name back on the focused tile. */
        void refocus();
    }

    private interface Step {
        void apply(float progress);
    }

    /** One timed change, driven by the view's own frame callbacks. */
    private final class Tween implements Runnable {
        private final long start = SystemClock.uptimeMillis();
        private final long duration;
        private final Step step;
        private final Runnable end;
        private boolean cancelled;

        Tween(long duration, Step step, Runnable end) {
            this.duration = duration;
            this.step = step;
            this.end = end;
        }

        void cancel() {
            cancelled = true;
        }

        @Override
        public void run() {
            if (cancelled) return;
            float t = duration <= 0 ? 1f : Math.min(1f, (SystemClock.uptimeMillis() - start) / (float) duration);
            float inverse = 1f - t;
            step.apply(1f - inverse * inverse * inverse);   // ease out
            if (t < 1f) {
                overlay.postOnAnimation(this);
            } else if (end != null) {
                end.run();
            }
        }
    }

    private final Activity activity;
    private final Host host;
    private final View gridWrap;
    /** The clock, date and weather: hidden on the bare page, shown with the dock. */
    private final View header;
    private final View overlay;
    private final HorizontalScrollView scroll;
    private final LinearLayout bar;
    private final View[] hiddenWhileHome;
    private final boolean animations;
    private final float density;
    private final int savedFocusability;

    private final List<View> cards = new ArrayList<>();
    private final List<Runnable> actions = new ArrayList<>();
    private boolean home;
    /** With the dock in: true once Down went on into the rows of apps, false while only the dock shows. */
    private boolean rows;
    /** Where the slide currently running leaves gridWrap's translation (the highlight aims for it). */
    private float shiftTarget;
    /** gridWrap's clip bottom with only the dock showing, or -1 when it was not measured yet. */
    private float dockClip = -1f;
    private boolean barOpen;
    private int selected;
    private long lastTransition;
    private Tween gridTween;
    private Tween barTween;

    ClockHome(Activity activity, Host host, boolean animations, View... hiddenWhileHome) {
        this.activity = activity;
        this.host = host;
        this.animations = animations;
        this.hiddenWhileHome = hiddenWhileHome;
        density = activity.getResources().getDisplayMetrics().density;
        gridWrap = activity.findViewById(R.id.gridWrap);
        header = (View) activity.findViewById(R.id.leftBlock).getParent();
        overlay = activity.findViewById(R.id.clockHome);
        scroll = activity.findViewById(R.id.settingsScroll);
        bar = activity.findViewById(R.id.settingsBar);
        savedFocusability = ((ViewGroup) gridWrap).getDescendantFocusability();
    }

    /** True while the bare page (no dock) is showing. */
    boolean isHome() {
        return home;
    }

    /** A held arrow repeats while a slide runs: those repeats must not also move the dock selection. */
    boolean swallowRepeat(KeyEvent event) {
        return event.getRepeatCount() > 0 && System.currentTimeMillis() - lastTransition < 700;
    }

    private long duration(long ms) {
        return animations ? ms : 0;
    }

    /** Where the dock-only state puts gridWrap: the band just above the bottom of the screen. */
    private float dockShift() {
        float bottom = host.dockBottomInWrap();
        View screen = gridWrap.getRootView();
        if (bottom < 0 || screen == null || screen.getHeight() == 0) {
            dockClip = -1f;
            return 0f;
        }
        int[] at = new int[2];
        gridWrap.getLocationInWindow(at);
        float wrapTop = at[1] - gridWrap.getTranslationY();
        dockClip = bottom + 4 * density;
        return Math.max(0f, screen.getHeight() - 44 * density - (wrapTop + bottom));
    }

    /** The translation the focused tile's highlight and name should be placed for. */
    float gridShiftTarget() {
        return shiftTarget;
    }

    /**
     * One slide of gridWrap: its alpha, its translation and how much of it shows (clip: its bottom edge in its own
     * coordinates, or -1 for all of it), with the header's alpha, all on one frame loop.
     */
    private void gridTo(float alpha, float shift, float clip, float headerAlpha, long ms, Runnable end) {
        if (gridTween != null) gridTween.cancel();
        final float fromAlpha = gridWrap.getAlpha();
        final float fromShift = gridWrap.getTranslationY();
        final float fromHeader = header.getAlpha();
        final float full = Math.max(1f, gridWrap.getHeight());
        Rect current = gridWrap.getClipBounds();
        final float fromClip = current == null ? full : current.bottom;
        final float toClip = clip < 0 ? full : clip;
        final Rect bounds = new Rect();
        shiftTarget = shift;
        gridTween = new Tween(ms, p -> {
            gridWrap.setAlpha(fromAlpha + (alpha - fromAlpha) * p);
            gridWrap.setTranslationY(fromShift + (shift - fromShift) * p);
            header.setAlpha(fromHeader + (headerAlpha - fromHeader) * p);
            float c = fromClip + (toClip - fromClip) * p;
            if (p >= 1f && clip < 0) {
                gridWrap.setClipBounds(null);
            } else {
                bounds.set(0, 0, gridWrap.getWidth(), Math.round(c));
                gridWrap.setClipBounds(bounds);
            }
            host.gridMoved();
        }, () -> {
            host.refocus();
            if (end != null) end.run();
        });
        gridTween.run();
    }

    /** Down from the dock row: the rows of apps slide up into view under it. */
    void toRows() {
        if (home || rows) return;
        rows = true;
        lastTransition = System.currentTimeMillis();
        gridTo(1f, 0f, -1f, 1f, duration(300), null);
    }

    /** Up from the first row of apps: back to the dock alone at the bottom. */
    void toDock() {
        if (home || !rows) return;
        rows = false;
        lastTransition = System.currentTimeMillis();
        gridTo(1f, dockShift(), dockClip, 1f, duration(300), this::settleDock);
    }

    /** After a slide to the dock: measure again now the band has been drawn, and correct the place if it was off. */
    private void settleDock() {
        if (home || rows) return;
        float shift = dockShift();
        if (Math.abs(shift - gridWrap.getTranslationY()) < 1f && dockClip >= 0
                && gridWrap.getClipBounds() != null && Math.abs(gridWrap.getClipBounds().bottom - dockClip) < 1f) return;
        gridTo(1f, shift, dockClip, 1f, duration(120), null);
    }

    boolean showingRows() {
        return !home && rows;
    }

    /** Shows the bare page: the dock slides down and out of sight. */
    void show(boolean animated) {
        lastTransition = System.currentTimeMillis();
        if (home) {
            closeBar(false);
            return;
        }
        home = true;
        ((ViewGroup) gridWrap).setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        View focus = activity.getCurrentFocus();
        if (focus != null) focus.clearFocus();
        for (View v : hiddenWhileHome) {
            v.animate().cancel();
            v.setAlpha(0f);
        }
        overlay.setVisibility(View.VISIBLE);
        rows = false;
        float shift = dockShift();
        gridTo(0f, shift + 44 * density, dockClip, 0f, animated ? duration(280) : 0, null);
    }

    /** Leaves the bare page: the dock slides up and takes focus. */
    private void leave() {
        if (!home) return;
        home = false;
        lastTransition = System.currentTimeMillis();
        closeBar(false);
        ((ViewGroup) gridWrap).setDescendantFocusability(savedFocusability);
        rows = false;
        float shift = dockShift();
        if (gridWrap.getAlpha() == 0f) {
            // From the bare page: start just under where the dock will rest, so it rises into place.
            gridWrap.setTranslationY(shift + 44 * density);
        }
        gridTo(1f, shift, dockClip, 1f, duration(300), this::settleDock);
        host.focusDock();
    }

    /** True when the key was used. Only the bare page and the settings row take keys; the grid has the rest. */
    boolean handleKey(KeyEvent event) {
        if (!home) return false;
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

    /** liquid_glass: the settings cards are clear glass slabs. */
    private boolean liquid;

    void setLiquid(boolean liquid) {
        this.liquid = liquid;
    }

    private android.graphics.drawable.Drawable background(boolean on) {
        if (liquid) {
            return on ? new LiquidGlass(density, px(28), 0x47FFFFFF, 0x1FFFFFFF, true)
                    : new LiquidGlass(density, px(28), 0x2EFFFFFF, 0x0FFFFFFF, false);
        }
        return plainBackground(on);
    }

    private GradientDrawable plainBackground(boolean on) {
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
        scroll.setVisibility(View.VISIBLE);
        barTo(1f, duration(240), null);
    }

    private void closeBar(boolean animated) {
        if (!barOpen) return;
        barOpen = false;
        lastTransition = System.currentTimeMillis();
        barTo(0f, animated ? duration(200) : 0, () -> {
            if (!barOpen) scroll.setVisibility(View.GONE);
        });
    }

    private void barTo(float target, long ms, Runnable end) {
        if (barTween != null) barTween.cancel();
        final float from = scroll.getAlpha();
        final float distance = 28 * density;
        barTween = new Tween(ms, p -> {
            float v = from + (target - from) * p;
            scroll.setAlpha(v);
            scroll.setTranslationY((1f - v) * distance);
        }, end);
        barTween.run();
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
            card.setScaleX(on ? 1.08f : 1f);
            card.setScaleY(on ? 1.08f : 1f);
            card.setAlpha(on ? 1f : 0.8f);
        }
        View current = cards.get(selected);
        scroll.post(() -> {
            int target = scroll.getPaddingLeft() + current.getLeft() + current.getWidth() / 2 - scroll.getWidth() / 2;
            if (animated && animations) scroll.smoothScrollTo(Math.max(0, target), 0);
            else scroll.scrollTo(Math.max(0, target), 0);
        });
    }

    private void activate() {
        Runnable action = actions.get(selected);
        closeBar(false);
        action.run();
    }
}
