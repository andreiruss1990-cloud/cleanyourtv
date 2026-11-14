package com.gathin.tvhome;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.Drawable;

/**
 * clock_home: an app's icon flattened to a white silhouette, the same way for every app, so the dock reads as one set of
 * glass icons. An adaptive icon gives its foreground layer (its background is a plain tile). A legacy icon painted on
 * a solid square keeps what stands out from the colour of its corners; anything else keeps its own transparency.
 */
final class Glyphs {
    private Glyphs() {
    }

    static Bitmap silhouette(Drawable icon, int size) {
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        if (icon instanceof AdaptiveIconDrawable && ((AdaptiveIconDrawable) icon).getForeground() != null) {
            Drawable fg = ((AdaptiveIconDrawable) icon).getForeground();
            // The foreground is 108dp with the logo in the middle 72dp: draw it so that middle fills the bitmap.
            int extra = size / 4;
            fg.setBounds(-extra, -extra, size + extra, size + extra);
            fg.draw(canvas);
        } else if (icon != null) {
            icon.setBounds(0, 0, size, size);
            icon.draw(canvas);
        }
        int[] px = new int[size * size];
        bitmap.getPixels(px, 0, size, 0, 0, size, size);
        int c1 = px[0], c2 = px[size - 1], c3 = px[size * (size - 1)], c4 = px[size * size - 1];
        int inset = size / 8;
        int m1 = px[inset * size + inset], m2 = px[inset * size + size - 1 - inset];
        boolean solid = Color.alpha(c1) > 200 && Color.alpha(c2) > 200 && Color.alpha(c3) > 200 && Color.alpha(c4) > 200;
        // A rounded or circular plate leaves the corners clear but fills just inside them: judge from there instead.
        boolean plate = !solid && Color.alpha(m1) > 200 && Color.alpha(m2) > 200 && close(m1, m2);
        int bg = solid ? c1 : m1;
        // A coloured logo with white details inside (Stremio's play mark, YouTube's triangle): the white parts become
        // holes in the white silhouette, the way a two-tone icon reads in one colour.
        int coloured = 0, whites = 0;
        for (int p : px) {
            if (Color.alpha(p) < 160) continue;
            if (isWhite(p)) whites++;
            else if (saturation(p) > 0.25f || lightness(p) < 0.6f) coloured++;
        }
        int opaque = Math.max(1, coloured + whites);
        boolean holes = !solid && !plate && coloured > opaque * 0.15f && whites > opaque * 0.03f;
        for (int i = 0; i < px.length; i++) {
            if (holes && Color.alpha(px[i]) >= 40 && isWhite(px[i])) {
                px[i] = 0;
                continue;
            }
            int p = px[i];
            int a = Color.alpha(p);
            if (solid || plate) {
                int d = distance(p, bg);
                a = a < 40 ? 0 : Math.min(255, Math.max(0, (d - 40) * 3));
            }
            px[i] = Color.argb(a, 255, 255, 255);
        }
        bitmap.setPixels(px, 0, size, 0, 0, size, size);
        return trimmed(bitmap, px, size);
    }

    /** Cut away the empty margin (to a square around the shape), so every glyph fills its tile the same way. */
    private static Bitmap trimmed(Bitmap bitmap, int[] px, int size) {
        int left = size, top = size, right = -1, bottom = -1;
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                if (Color.alpha(px[y * size + x]) > 24) {
                    if (x < left) left = x;
                    if (x > right) right = x;
                    if (y < top) top = y;
                    if (y > bottom) bottom = y;
                }
            }
        }
        if (right < left || bottom < top) return bitmap;
        int side = Math.max(right - left, bottom - top) + 1;
        int cx = (left + right) / 2, cy = (top + bottom) / 2;
        int x0 = Math.max(0, Math.min(size - side, cx - side / 2));
        int y0 = Math.max(0, Math.min(size - side, cy - side / 2));
        side = Math.min(side, size - Math.max(x0, y0));
        return side < size / 4 ? bitmap : Bitmap.createBitmap(bitmap, x0, y0, side, side);
    }

    private static boolean isWhite(int p) {
        return Color.red(p) > 215 && Color.green(p) > 215 && Color.blue(p) > 215;
    }

    private static float saturation(int p) {
        float[] hsv = new float[3];
        Color.colorToHSV(p, hsv);
        return hsv[1];
    }

    private static float lightness(int p) {
        return (Color.red(p) * 0.299f + Color.green(p) * 0.587f + Color.blue(p) * 0.114f) / 255f;
    }

    private static boolean close(int a, int b) {
        return distance(a, b) < 30;
    }

    private static int distance(int a, int b) {
        int dr = Color.red(a) - Color.red(b);
        int dg = Color.green(a) - Color.green(b);
        int db = Color.blue(a) - Color.blue(b);
        return (int) Math.sqrt(dr * dr + dg * dg + db * db);
    }
}
