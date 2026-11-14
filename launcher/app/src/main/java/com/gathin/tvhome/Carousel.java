package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Outline;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;

import java.util.ArrayList;
import java.util.List;

/**
 * clock_home: one endless row on a glass band. The focus never moves: it is the first slot, a bright glass slab, and
 * Left and Right slide the items into it on the launcher's own frame loop (the animator scale may be 0). Past the last
 * item the row starts again with the first. A fixed set of slot views is rebound as the row turns, so a long list costs
 * no more than a short one, and every background is made once.
 */
final class Carousel extends FrameLayout {
    interface Binder {
        /** A new, empty slot view of the given size. */
        View create(Context context, int width, int height);

        /** Show item index in this slot view. */
        void bind(View view, int index);
    }

    /** One move of the row, in milliseconds; every slide in clock_home uses the same length and curve. */
    static final long MOVE_MS = 220L;

    private final float density;
    private final int itemW;
    private final int itemH;
    private final int pitch;
    private final int pad;
    private final float radius;
    private final Binder binder;
    /** Each slot's bright glass, faded in as the slot nears the focus. */
    private final List<LiquidGlass> lifts = new ArrayList<>();
    private final boolean restGlass;
    private final float tileRadius;
    private int restTop = 0x38FFFFFF, restBottom = 0x17FFFFFF;
    private final List<View> slots = new ArrayList<>();
    private final List<Integer> bound = new ArrayList<>();
    private int count;
    private float offset;
    private int target;
    private long tweenStart;
    private float tweenFrom;
    private boolean moving;
    private Runnable onTarget;
    /** A column instead of a row (All apps): items stack downwards and Up/Down turn it. */
    private boolean vertical;
    private float focusScale = 0.10f;

    /**
     * @param itemW, itemH  a slot's size; gap  the space between two slots; pad  the band's padding around them.
     */
    /**
     * @param band       the band's glass, or null for none (All apps sits on its own panel).
     * @param restGlass  every slot is a faint glass tile at rest (false: only the focused one shows glass).
     * @param tileRadius the corner radius of a slot's glass.
     */
    Carousel(Context context, float density, int itemW, int itemH, int gap, int pad, float radius,
             LiquidGlass band, boolean restGlass, float tileRadius, Binder binder) {
        super(context);
        this.density = density;
        this.itemW = itemW;
        this.itemH = itemH;
        this.pitch = itemW + gap;
        this.pad = pad;
        this.radius = radius;
        this.binder = binder;
        this.restGlass = restGlass;
        this.tileRadius = tileRadius;
        setBackground(band);
        setClipChildren(true);
        setClipToOutline(true);
        setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), Carousel.this.radius);
            }
        });
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    /** The band's height for these items. */
    int bandHeight() {
        return itemH + 2 * pad;
    }

    /** The colour of every slot's glass at rest (set before the carousel is laid out). */
    void setRestGlass(int top, int bottom) {
        restTop = top;
        restBottom = bottom;
    }

    /** Make this a column: slots stack downwards, the focus is the top slot. */
    void setVertical(float focusScale) {
        vertical = true;
        this.focusScale = focusScale;
    }

    void setOnTarget(Runnable onTarget) {
        this.onTarget = onTarget;
    }

    /** A new list (count items); the first slot shows first (0 to count-1). */
    void setCount(int count, int first) {
        this.count = count;
        target = count == 0 ? 0 : Math.max(0, Math.min(count - 1, first));
        offset = target;
        moving = false;
        for (int i = 0; i < bound.size(); i++) bound.set(i, Integer.MIN_VALUE);
        layoutSlots();
    }

    /** Rebind every slot (an item's look changed). */
    void refresh() {
        for (int i = 0; i < bound.size(); i++) bound.set(i, Integer.MIN_VALUE);
        layoutSlots();
    }

    int count() {
        return count;
    }

    /** The item in the focus slot (where the row is going, if it is moving). */
    int current() {
        return count == 0 ? -1 : Math.floorMod(target, count);
    }

    /**
     * One step left (-1) or right (+1). A held arrow (repeat) only steps once the row has nearly arrived, so it keeps a
     * steady pace and never runs ahead of what is on screen.
     */
    void step(int direction, boolean repeat) {
        if (count == 0) return;
        if (repeat && Math.abs(target - offset) > 0.3f) return;
        target += direction;
        tweenFrom = offset;
        tweenStart = SystemClock.uptimeMillis();
        if (onTarget != null) onTarget.run();
        if (!moving) {
            moving = true;
            postOnAnimation(frame);
        }
    }

    /** Jump to item index without a slide (after a reorder). */
    void jumpTo(int index) {
        if (count == 0) return;
        target = index;
        offset = index;
        moving = false;
        refresh();
        if (onTarget != null) onTarget.run();
    }

    private final Runnable frame = new Runnable() {
        @Override
        public void run() {
            float t = Math.min(1f, (SystemClock.uptimeMillis() - tweenStart) / (float) MOVE_MS);
            float inverse = 1f - t;
            float eased = 1f - inverse * inverse * inverse;
            offset = tweenFrom + (target - tweenFrom) * eased;
            if (t >= 1f) {
                offset = target;
                // Keep the numbers small: the same item, counted from the first lap.
                if (count > 0) {
                    int lap = Math.floorDiv(target, count) * count;
                    target -= lap;
                    offset -= lap;
                    for (int i = 0; i < bound.size(); i++) bound.set(i, Integer.MIN_VALUE);
                }
                moving = false;
            }
            layoutSlots();
            if (moving) postOnAnimation(this);
        }
    };

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        // One slot to the left (sliding out), the visible ones, one more (sliding in).
        int visible = (int) Math.ceil(((vertical ? h : w) - pad) / (float) pitch) + 2;
        while (slots.size() < visible) {
            View v = binder.create(getContext(), itemW, itemH);
            // The focus is the slot's own glass turning bright: no separate slab behind it.
            LiquidGlass lift = LiquidGlass.lift(density, tileRadius);
            lift.setAlpha(0);
            v.setBackground(restGlass
                    ? new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[]{
                            new LiquidGlass(density, tileRadius, restTop, restBottom, false), lift})
                    : lift);
            addView(v, new LayoutParams(itemW, itemH));
            slots.add(v);
            lifts.add(lift);
            bound.add(Integer.MIN_VALUE);
        }
        // Slots added during this layout pass miss it (they stay 0x0 until something else asks for a layout): ask now.
        post(() -> {
            requestLayout();
            layoutSlots();
        });
    }

    private void layoutSlots() {
        if (slots.isEmpty()) return;
        int base = (int) Math.floor(offset);
        float frac = offset - base;
        for (int k = 0; k < slots.size(); k++) {
            View v = slots.get(k);
            int slot = k - 1;
            if (count == 0) {
                v.setVisibility(INVISIBLE);
                continue;
            }
            int index = Math.floorMod(base + slot, count);
            if (bound.get(k) != base + slot) {
                binder.bind(v, index);
                bound.set(k, base + slot);
            }
            v.setVisibility(VISIBLE);
            float d = slot - frac;
            v.setTranslationX(vertical ? pad : pad + d * pitch);
            v.setTranslationY(vertical ? pad + d * pitch : pad);
            float near = Math.max(0f, 1f - Math.abs(d));
            float s = 1f + focusScale * near;
            v.setScaleX(s);
            v.setScaleY(s);
            lifts.get(k).setAlpha(Math.round(255 * near));
        }
    }
}
