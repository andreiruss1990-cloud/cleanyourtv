package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Outline;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;

/**
 * liquid_glass: frosted glass behind the dock. A rounded window, clipped to the dock band, onto a blurred copy of the
 * wallpaper that stays aligned with the real one, so the band shows exactly the picture behind it, softened. The blur
 * runs on the GPU (RenderEffect, Android 12+) and is cached until the picture changes; moving the window only moves
 * the copy the other way. Sits just above the wallpaper, under everything else.
 */
final class FrostLayer {
    private final FrameLayout window;
    private final ImageView copy;
    private final Rect clip = new Rect();
    private float radius;

    static boolean supported() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S;
    }

    FrostLayer(Context context, FrameLayout root, int index, float blurPx) {
        window = new FrameLayout(context);
        window.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        window.setClipToOutline(true);
        window.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
            }
        });
        window.setAlpha(0f);
        copy = new ImageView(context);
        copy.setScaleType(ImageView.ScaleType.CENTER_CROP);
        if (supported()) copy.setRenderEffect(RenderEffect.createBlurEffect(blurPx, blurPx, Shader.TileMode.CLAMP));
        // The copy is the size of the screen; the window shows the part of it that lies under the band.
        DisplayCopySize size = new DisplayCopySize(context);
        window.addView(copy, new FrameLayout.LayoutParams(size.width, size.height));
        root.addView(window, Math.min(index, root.getChildCount()),
                new FrameLayout.LayoutParams(0, 0));
    }

    /** The picture on screen now; null hides the glass (the wallpaper was released). */
    void setBitmap(Bitmap bitmap) {
        copy.setImageBitmap(bitmap);
        window.setVisibility(bitmap == null ? View.INVISIBLE : View.VISIBLE);
    }

    /**
     * Puts the glass over rect (root coordinates) with corner radius r, cut to visible (root coordinates: the part of
     * the screen the dock can show in, so a band scrolled half away is cut as the grid cuts it).
     */
    void place(RectF rect, float r, Rect visible) {
        int w = Math.round(rect.width());
        int h = Math.round(rect.height());
        ViewGroup.LayoutParams lp = window.getLayoutParams();
        if (lp.width != w || lp.height != h || r != radius) {
            lp.width = w;
            lp.height = h;
            radius = r;
            window.setLayoutParams(lp);
            window.invalidateOutline();
        }
        window.setTranslationX(rect.left);
        window.setTranslationY(rect.top);
        copy.setTranslationX(-rect.left);
        copy.setTranslationY(-rect.top);
        clip.set(visible);
        clip.offset(-Math.round(rect.left), -Math.round(rect.top));
        if (!clip.intersect(0, 0, w, h)) clip.setEmpty();
        window.setClipBounds(clip);
    }

    void setAlpha(float alpha) {
        window.setAlpha(alpha);
    }

    /** The screen size, for a copy that lines up with a CENTER_CROP wallpaper filling the root. */
    private static final class DisplayCopySize {
        final int width;
        final int height;

        DisplayCopySize(Context context) {
            android.util.DisplayMetrics m = context.getResources().getDisplayMetrics();
            width = m.widthPixels;
            height = m.heightPixels;
        }
    }
}
