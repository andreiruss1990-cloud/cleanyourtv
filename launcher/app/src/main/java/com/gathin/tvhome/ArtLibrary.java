package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * art_source: the painting collections ArtSync keeps on the TV (files/art/<collection>/, described by files/art/index.json),
 * the one the person chose in the settings row, and Auto, which picks from every collection by the moment: nocturnes
 * matching the live weather (snow, rain, fog), then the season, then anything.
 */
final class ArtLibrary {
    static final String AUTO = "auto";

    static final class Piece {
        final File file;
        final String caption;
        final Set<String> tags;

        Piece(File file, String caption, Set<String> tags) {
            this.file = file;
            this.caption = caption;
            this.tags = tags;
        }
    }

    static final class Shelf {
        final String id;
        final String name;
        /** Subjects, Movements, Painters or Your folders: the heading it sits under in the Art list. */
        final String group;
        final List<Piece> pieces;

        Shelf(String id, String name, String group, List<Piece> pieces) {
            this.id = id;
            this.name = name;
            this.group = group;
            this.pieces = pieces;
        }
    }

    /** What Auto matches: night (between sunset and sunrise), a weather tag or null, and the season. */
    static final class Moment {
        final boolean night;
        final String weather;
        final String season;

        Moment(boolean night, String weather, String season) {
            this.night = night;
            this.weather = weather;
            this.season = season;
        }
    }

    /** Fewer matching paintings than this and Auto widens to the next rule, so the slideshow never loops on a few. */
    private static final int ENOUGH = 8;

    private final Context context;
    private final SharedPreferences prefs;
    private final List<Shelf> shelves = new ArrayList<>();
    private final Map<String, String> captions = new HashMap<>();

    ArtLibrary(Context context) {
        this.context = context;
        prefs = context.getSharedPreferences("art", Context.MODE_PRIVATE);
        load();
    }

    static File dir(Context context) {
        File base = Config.dir(context);
        return base == null ? null : new File(base, "art");
    }

    /** Reads files/art/index.json (version 2), keeping only the pictures already downloaded to pool/. */
    void load() {
        shelves.clear();
        captions.clear();
        File root = dir(context);
        File index = root == null ? null : new File(root, "index.json");
        if (index == null || !index.isFile()) return;
        try {
            JSONObject json = new JSONObject(new String(Files.readAllBytes(index.toPath()), StandardCharsets.UTF_8));
            JSONObject pictures = json.getJSONObject("pictures");
            File pool = new File(root, "pool");
            Map<String, Piece> pieces = new HashMap<>();
            java.util.Iterator<String> keys = pictures.keys();
            while (keys.hasNext()) {
                String name = keys.next();
                File file = new File(pool, name);
                if (!file.isFile()) continue;
                JSONObject p = pictures.getJSONObject(name);
                Set<String> tags = new HashSet<>();
                JSONArray t = p.optJSONArray("tags");
                if (t != null) for (int k = 0; k < t.length(); k++) tags.add(t.getString(k));
                String caption = p.optString("caption", "");
                pieces.put(name, new Piece(file, caption, tags));
                if (!caption.isEmpty()) captions.put(file.getAbsolutePath(), caption);
            }
            JSONArray list = json.getJSONArray("collections");
            for (int i = 0; i < list.length(); i++) {
                JSONObject c = list.getJSONObject(i);
                List<Piece> in = new ArrayList<>();
                JSONArray files = c.getJSONArray("files");
                for (int j = 0; j < files.length(); j++) {
                    Piece piece = pieces.get(files.getString(j));
                    if (piece != null) in.add(piece);
                }
                if (!in.isEmpty()) shelves.add(new Shelf(c.getString("id"), c.optString("name", c.getString("id")),
                        c.optString("group", ""), in));
            }
        } catch (Exception e) {
            shelves.clear();
            captions.clear();
        }
    }

    List<Shelf> shelves() {
        return Collections.unmodifiableList(shelves);
    }

    String selection() {
        return prefs.getString("selection", AUTO);
    }

    /** The change interval chosen on the TV in minutes, or 0 for the theme's own. */
    int intervalMinutes() {
        return prefs.getInt("interval", 0);
    }

    void setIntervalMinutes(int minutes) {
        prefs.edit().putInt("interval", minutes).apply();
    }

    void select(String id) {
        prefs.edit().putString("selection", id).apply();
    }

    /** "Title|Painter, year" for a painting, or null when it is not one of the collections'. */
    String caption(File file) {
        return file == null ? null : captions.get(file.getAbsolutePath());
    }

    /** The paintings the slideshow picks from now; empty when there are no collections (the theme's own play). */
    List<File> pool(Moment moment) {
        String chosen = selection();
        List<Piece> all = new ArrayList<>();
        for (Shelf s : shelves) {
            if (AUTO.equals(chosen) || s.id.equals(chosen)) all.addAll(s.pieces);
        }
        if (all.isEmpty()) {
            for (Shelf s : shelves) all.addAll(s.pieces);   // the chosen collection is gone: show everything
        }
        // Auto: paintings that match the weather outside (snow, rain, fog), else the season, else all of them; each
        // painting once, though it may sit in several collections.
        java.util.LinkedHashMap<String, Piece> unique = new java.util.LinkedHashMap<>();
        for (Piece p : all) unique.put(p.file.getAbsolutePath(), p);
        List<Piece> picked = new ArrayList<>(unique.values());
        if (AUTO.equals(chosen) && moment != null) {
            List<Piece> narrowed = null;
            if (moment.weather != null) narrowed = tagged(picked, moment.weather);
            if (narrowed == null && moment.season != null) narrowed = tagged(picked, moment.season);
            if (narrowed != null) picked = narrowed;
        }
        List<File> files = new ArrayList<>(picked.size());
        for (Piece p : picked) files.add(p.file);
        return files;
    }

    private static List<Piece> tagged(List<Piece> pieces, String tag) {
        List<Piece> out = new ArrayList<>();
        for (Piece p : pieces) if (p.tags.contains(tag)) out.add(p);
        return out.size() >= ENOUGH ? out : null;
    }

    /** The season in the northern hemisphere for a month 1-12. */
    static String season(int month) {
        if (month >= 3 && month <= 5) return "spring";
        if (month >= 6 && month <= 8) return "summer";
        if (month >= 9 && month <= 11) return "autumn";
        return "winter";
    }
}
