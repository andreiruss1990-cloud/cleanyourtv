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
