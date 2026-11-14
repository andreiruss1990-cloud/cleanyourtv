package com.gathin.tvhome;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;

/**
 * liquid_glass: the look of a slab of clear glass. A tint that fades from the top down, a bright rim that catches
 * the light along the top and again, fainter, along the bottom, and a thin specular line just inside the top edge.
 * {@link #draw(Canvas, RectF, float)} paints it over whatever is behind (the dock's frosted wallpaper); as a
 * Drawable it backs the quick panel and the settings cards.
 */
final class LiquidGlass extends Drawable {
    private final float density;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shine = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();
    private final RectF inner = new RectF();
    private final int tintTop;
    private final int tintBottom;
    private final boolean bright;
    private float radius;
    private float shaderTop = Float.NaN, shaderBottom = Float.NaN;
    private int alpha = 255;

    /**
     * @param tintTop    the glass colour at the top (ARGB); tintBottom at the bottom.
     * @param bright     a focused slab: a stronger rim and shine.
     */
    LiquidGlass(float density, float radiusPx, int tintTop, int tintBottom, boolean bright) {
        this.density = density;
        this.radius = radiusPx;
        this.tintTop = tintTop;
        this.tintBottom = tintBottom;
        this.bright = bright;
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth((bright ? 2f : 1.2f) * density);
        shine.setStyle(Paint.Style.STROKE);
        shine.setStrokeWidth(1f * density);
    }

    // ---- the one recipe every clock_home surface uses -------------------------------------------------------

    /** A band holding a row (the dock, the settings row): light glossy glass. */
    static LiquidGlass band(float density) {
        // Nearly clear: the painting shows through sharp, so the tiles on it read as the glass objects.
        return new LiquidGlass(density, 28 * density, 0x14FFFFFF, 0x05FFFFFF, false);
    }

    /** A slot at rest: faint clear glass. */
    static LiquidGlass tile(float density, float radiusPx) {
        return new LiquidGlass(density, radiusPx, 0x38FFFFFF, 0x17FFFFFF, false);
    }

    /** A slot in focus: the same glass, brighter and glossier (laid over tile() and faded in). */
    static LiquidGlass lift(float density, float radiusPx) {
        return new LiquidGlass(density, radiusPx, 0x52FFFFFF, 0x1FFFFFFF, true);
    }

    /** The fixed focus slot: a brighter slab with a strong rim. */
    static LiquidGlass focus(float density) {
        return new LiquidGlass(density, 20 * density, 0x66FFFFFF, 0x2EFFFFFF, true);
    }

    /** A card on a band (a settings entry): the same glass, fainter. */
    static LiquidGlass card(float density) {
        return new LiquidGlass(density, 14 * density, 0x1FFFFFFF, 0x0AFFFFFF, false);
    }

    /** Glass over a picture that moves (an app playing) or holding text (All apps): darker, so it stays readable. */
    static LiquidGlass dark(float density) {
        return new LiquidGlass(density, 28 * density, 0xB31C1E24, 0xCC101114, false);
    }

    void setRadius(float radiusPx) {
        radius = radiusPx;
        invalidateSelf();
    }

    /** Paints the glass into rect, over what the canvas already holds. */
    void draw(Canvas c, RectF rect, float r) {
        if (rect.top != shaderTop || rect.bottom != shaderBottom) {
            float h = rect.height();
            fill.setShader(new LinearGradient(0, rect.top, 0, rect.bottom, tintTop, tintBottom, Shader.TileMode.CLAMP));
            int top = bright ? 0xCCFFFFFF : 0x8CFFFFFF;
            rim.setShader(new LinearGradient(0, rect.top, 0, rect.bottom,
                    new int[]{top, 0x1AFFFFFF, 0x0DFFFFFF, bright ? 0x59FFFFFF : 0x38FFFFFF},
                    new float[]{0f, 0.35f, 0.7f, 1f}, Shader.TileMode.CLAMP));
            float shineEnd = rect.top + Math.min(h * 0.45f, 60 * density);
            shine.setShader(new LinearGradient(0, rect.top, 0, shineEnd,
                    bright ? 0x80FFFFFF : 0x4DFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
            shaderTop = rect.top;
            shaderBottom = rect.bottom;
        }
        fill.setAlpha(alpha);
        rim.setAlpha(alpha);
        shine.setAlpha(alpha);
        c.drawRoundRect(rect, r, r, fill);
        float in = 2.5f * density;
        inner.set(rect.left + in, rect.top + in, rect.right - in, rect.bottom - in);
        c.drawRoundRect(inner, Math.max(0f, r - in), Math.max(0f, r - in), shine);
        float half = rim.getStrokeWidth() / 2f;
        inner.set(rect.left + half, rect.top + half, rect.right - half, rect.bottom - half);
        c.drawRoundRect(inner, Math.max(0f, r - half), Math.max(0f, r - half), rim);
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        Rect b = getBounds();
        box.set(b);
        draw(canvas, box, Math.min(radius, Math.min(box.width(), box.height()) / 2f));
    }

    @Override
    public void setAlpha(int alpha) {
        this.alpha = alpha;
        invalidateSelf();
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        fill.setColorFilter(colorFilter);
    }

    @Override
    @SuppressWarnings("deprecation")
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
