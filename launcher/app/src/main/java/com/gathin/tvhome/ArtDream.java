package com.gathin.tvhome;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.service.dreams.DreamService;
import android.widget.FrameLayout;
import android.widget.ImageView;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * The art screensaver: the theme's wallpapers full screen, one after another, nothing else on screen. Each painting
 * drifts slowly (a few percent of zoom and pan over its turn) and fades into the next. Offline, and on the launcher's
 * own frame loop, since the animator duration scale may be 0. Pick it with
 * {@code settings put secure screensaver_components com.gathin.tvhome/.ArtDream}.
 */
public final class ArtDream extends DreamService {
    private static final long TURN_MS = 75_000L;
    private static final long FADE_MS = 2_500L;
    private static final float ZOOM = 0.06f;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Random random = new Random();
    private final List<File> files = new ArrayList<>();
    private FrameLayout root;
    private ImageView front;
    private ImageView back;
    private Bitmap frontBitmap;
    private Bitmap backBitmap;
    private int next;
    private long turnStart;
    private long fadeStart = -1;
    private float panX;
    private float panY;
    private boolean running;
    private Thread loader;

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);
        setFullscreen(true);
        setScreenBright(true);
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        back = image();
        front = image();
        root.addView(back);
        root.addView(front);
        setContentView(root);

        File dir = Config.dir(this);
        File[] list = dir == null ? null : new File(dir, "wallpapers").listFiles(
                (d, name) -> name.toLowerCase().endsWith(".jpg") || name.toLowerCase().endsWith(".jpeg")
                        || name.toLowerCase().endsWith(".png") || name.toLowerCase().endsWith(".webp"));
        if (list != null) files.addAll(Arrays.asList(list));
        Collections.shuffle(files, random);
    }

    private ImageView image() {
        ImageView v = new ImageView(this);
        v.setScaleType(ImageView.ScaleType.CENTER_CROP);
        v.setLayoutParams(new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        return v;
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        if (files.isEmpty()) return;
        running = true;
        load(bitmap -> {
            frontBitmap = bitmap;
            front.setImageBitmap(bitmap);
            front.setAlpha(1f);
            startTurn();
            root.postOnAnimation(frame);
        });
    }

    @Override
    public void onDreamingStopped() {
        running = false;
        super.onDreamingStopped();
    }

    @Override
    public void onDetachedFromWindow() {
        running = false;
        main.removeCallbacksAndMessages(null);
        if (front != null) front.setImageDrawable(null);
        if (back != null) back.setImageDrawable(null);
        frontBitmap = null;
        backBitmap = null;
        super.onDetachedFromWindow();
    }

    private void startTurn() {
        turnStart = SystemClock.uptimeMillis();
        // A gentle drift in a random direction: at most ZOOM/2 of the picture either way.
        panX = (random.nextFloat() - 0.5f) * ZOOM;
        panY = (random.nextFloat() - 0.5f) * ZOOM * 0.6f;
        // The next painting loads in the background while this one shows.
        load(bitmap -> backBitmap = bitmap);
    }

    private interface Loaded {
        void done(Bitmap bitmap);
    }

    private void load(Loaded then) {
        if (files.isEmpty()) return;
        final File file = files.get(next);
        next = (next + 1) % files.size();
        final int w = getResources().getDisplayMetrics().widthPixels;
        loader = new Thread(() -> {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getPath(), o);
            int sample = 1;
            while (o.outWidth / (sample * 2) >= w) sample *= 2;
            o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            Bitmap bitmap = BitmapFactory.decodeFile(file.getPath(), o);
            main.post(() -> {
                if (running && bitmap != null) then.done(bitmap);
            });
        }, "ArtDream");
        loader.start();
    }

    private final Runnable frame = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            long now = SystemClock.uptimeMillis();
            float t = Math.min(1f, (now - turnStart) / (float) (TURN_MS + FADE_MS));
            drift(front, t);
            if (fadeStart < 0 && now - turnStart >= TURN_MS && backBitmap != null) {
                // Swap: the loaded painting goes behind, then the front one fades out over it.
                back.setImageBitmap(backBitmap);
                back.setAlpha(1f);
                drift(back, 0f);
                fadeStart = now;
            }
            if (fadeStart >= 0) {
                float f = Math.min(1f, (now - fadeStart) / (float) FADE_MS);
                front.setAlpha(1f - f);
                if (f >= 1f) {
                    ImageView v = front;
                    front = back;
                    back = v;
                    back.setImageDrawable(null);
                    back.setAlpha(0f);
                    frontBitmap = backBitmap;
                    backBitmap = null;
                    fadeStart = -1;
                    front.bringToFront();
                    startTurn();
                }
            }
            root.postOnAnimation(this);
        }
    };

    private void drift(ImageView v, float t) {
        float scale = 1f + ZOOM * t;
        v.setScaleX(scale);
        v.setScaleY(scale);
        v.setTranslationX(panX * t * v.getWidth());
        v.setTranslationY(panY * t * v.getHeight());
    }
}
