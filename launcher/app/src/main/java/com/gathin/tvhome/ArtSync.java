package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * art_source: keeps files/art/ in step with the collections in the person's repository. It reads art/index.json under
 * the art_source address (raw files of a GitHub branch), downloads the paintings the TV lacks, deletes the ones the
 * index dropped and saves the index last, so ArtLibrary only ever sees complete files. It runs when Home starts, at
 * most every art_refresh_hours, on one background thread, and touches nothing but that address.
 */
final class ArtSync {
    private static final long MAX_INDEX = 2L << 20;
    private static final long MAX_PICTURE = 8L << 20;

    private final Context context;
    private final String base;
    private final long everyMs;
    private final SharedPreferences prefs;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean busy;

    ArtSync(Context context, String base, int everyHours) {
        this.context = context;
        this.base = base.endsWith("/") ? base : base + "/";
        this.everyMs = Math.max(1, everyHours) * 3_600_000L;
        prefs = context.getSharedPreferences("art", Context.MODE_PRIVATE);
    }

    /**
     * Syncs if the last good sync is older than art_refresh_hours. done runs on the main thread when anything changed,
     * and also every few dozen new pictures during a long first download, so collections fill in as they arrive.
     */
    void maybeRun(Runnable done) {
        if (busy || System.currentTimeMillis() - prefs.getLong("synced_at", 0) < everyMs) return;
        busy = true;
        executor.execute(() -> {
            boolean changed = false;
            boolean ok = false;
            try {
                changed = run(done);
                ok = true;
            } catch (Exception | OutOfMemoryError ignored) {
                // offline or the address is wrong: try again on a later Home
            }
            final boolean any = changed;
            final boolean good = ok;
            main.post(() -> {
                busy = false;
                if (good) prefs.edit().putLong("synced_at", System.currentTimeMillis()).apply();
                if (any && done != null) done.run();
            });
        });
    }

    void close() {
        executor.shutdownNow();
    }

    /** index.json version 2: "pictures" (name -> url, ...) and "collections" (lists of names); pictures live in pool/. */
    private boolean run(Runnable progress) throws Exception {
        File root = ArtLibrary.dir(context);
        if (root == null) return false;
        byte[] indexBytes = get(base + "art/index.json", MAX_INDEX);
        // Not there (yet) or not reachable: not a sync, so the next Home tries again instead of waiting hours.
        if (indexBytes == null) throw new java.io.IOException("no art/index.json at " + base);
        String indexText = new String(indexBytes, StandardCharsets.UTF_8);
        JSONObject pictures = new JSONObject(indexText).getJSONObject("pictures");
        File localIndex = new File(root, "index.json");
        File pool = new File(root, "pool");
        pool.mkdirs();
        // The index first, so the pictures already on the TV show while the rest download.
        boolean changed = writeIndex(root, localIndex, indexText, indexBytes);
        if (changed) main.post(progress);
        Set<String> names = new HashSet<>();
        int fresh = 0;
        java.util.Iterator<String> keys = pictures.keys();
        while (keys.hasNext()) {
            String name = keys.next();
            if (!name.matches("[A-Za-z0-9._-]+")) continue;
            names.add(name);
            File file = new File(pool, name);
            if (file.isFile() && file.length() > 0) continue;
            String url = pictures.getJSONObject(name).optString("url", "");
            if (url.isEmpty() || url.contains("..")) continue;
            byte[] data = get(url.startsWith("https://") ? url : base + url, MAX_PICTURE);
            if (data == null || data.length == 0) continue;
            File part = new File(pool, name + ".part");
            try (FileOutputStream out = new FileOutputStream(part)) {
                out.write(data);
            }
            if (part.renameTo(file)) {
                changed = true;
                if (++fresh % 30 == 0 && progress != null) main.post(progress);
            }
        }
        File[] present = pool.listFiles();
        if (present != null) {
            for (File f : present) {
                if (!names.contains(f.getName()) && f.delete()) changed = true;
            }
        }
        // Folders from the first version of the list (one per collection) are no longer used.
        File[] folders = root.listFiles(File::isDirectory);
        if (folders != null) {
            for (File folder : folders) {
                if (folder.getName().equals("pool")) continue;
                File[] inside = folder.listFiles();
                if (inside != null) for (File f : inside) f.delete();
                folder.delete();
            }
        }
        return changed;
    }

    private static boolean writeIndex(File root, File localIndex, String text, byte[] bytes) throws Exception {
        String old = localIndex.isFile() ? new String(java.nio.file.Files.readAllBytes(localIndex.toPath()),
                StandardCharsets.UTF_8) : "";
        if (old.equals(text)) return false;
        File part = new File(root, "index.json.part");
        try (FileOutputStream out = new FileOutputStream(part)) {
            out.write(bytes);
        }
        return part.renameTo(localIndex);
    }

    private static byte[] get(String address, long max) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        try {
            connection.setConnectTimeout(10_000);
            connection.setReadTimeout(30_000);
            if (connection.getResponseCode() != 200) return null;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (InputStream in = connection.getInputStream()) {
                byte[] buffer = new byte[16384];
                int n;
                while ((n = in.read(buffer)) > 0) {
                    out.write(buffer, 0, n);
                    if (out.size() > max) return null;
                }
            }
            return out.toByteArray();
        } finally {
            connection.disconnect();
        }
    }
}
