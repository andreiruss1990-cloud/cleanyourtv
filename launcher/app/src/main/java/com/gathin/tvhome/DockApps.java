package com.gathin.tvhome;

import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.provider.Settings;
import android.widget.Toast;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * clock_home: the apps in the dock row, in the person's order, kept in the launcher's own preferences. It starts with
 * the theme's dock_apps and the Play Store; All apps adds to it and the tile menu removes from it or moves within it.
 */
final class DockApps {
    static final class App {
        final String pkg;
        final String label;
        final Drawable banner;   // null: the app has no TV banner, show its icon
        final Drawable icon;
        private Drawable glyph;

        App(String pkg, String label, Drawable banner, Drawable icon) {
            this.pkg = pkg;
            this.label = label;
            this.banner = banner;
            this.icon = icon;
        }

        /** The icon as a white silhouette (made once), for the glass tiles. */
        Drawable glyph(android.content.res.Resources res) {
            if (glyph == null) glyph = new android.graphics.drawable.BitmapDrawable(res, Glyphs.silhouette(icon, 160));
            return glyph;
        }
    }

    private static final String PREFS = "clock_dock";
    private static final String KEY = "apps";
    private static final String PLAY_STORE = "com.android.vending";

    private final Context context;
    private final PackageManager pm;
    private final Map<String, App> cache = new HashMap<>();

    DockApps(Context context) {
        this.context = context;
        this.pm = context.getPackageManager();
    }

    /** The row's apps, in order, leaving out any that are no longer installed or launchable. */
    List<App> load(List<String> themeDock) {
        SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String saved = p.getString(KEY, null);
        List<String> pkgs = new ArrayList<>();
        if (saved == null) {
            pkgs.addAll(themeDock);
            pkgs.add(PLAY_STORE);
        } else if (!saved.isEmpty()) {
            Collections.addAll(pkgs, saved.split(","));
        }
        List<App> apps = new ArrayList<>();
        for (String pkg : new LinkedHashSet<>(pkgs)) {
            App app = app(pkg);
            if (app != null) apps.add(app);
        }
        return apps;
    }

    void save(List<App> apps) {
        StringBuilder b = new StringBuilder();
        for (App a : apps) {
            if (b.length() > 0) b.append(',');
            b.append(a.pkg);
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, b.toString()).apply();
    }

    /** Every app a TV can open (leanback first, phone launchers too), sorted by name; the launcher itself left out. */
    List<App> all() {
        LinkedHashSet<String> pkgs = new LinkedHashSet<>();
        for (String category : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            Intent main = new Intent(Intent.ACTION_MAIN).addCategory(category);
            for (ResolveInfo r : pm.queryIntentActivities(main, 0)) pkgs.add(r.activityInfo.packageName);
        }
        pkgs.remove(context.getPackageName());
        List<App> apps = new ArrayList<>();
        for (String pkg : pkgs) {
            App app = app(pkg);
            if (app != null) apps.add(app);
        }
        Collator c = Collator.getInstance();
        apps.sort((x, y) -> c.compare(x.label, y.label));
        return apps;
    }

    App app(String pkg) {
        App cached = cache.get(pkg);
        if (cached != null) return cached;
        Intent launch = launchIntent(pkg);
        if (launch == null) return null;
        ComponentName component = launch.getComponent();
        Drawable banner = null;
        Drawable icon = null;
        String label = pkg;
        try {
            if (component != null) {
                banner = pm.getActivityBanner(component);
                icon = pm.getActivityIcon(component);
            }
            if (banner == null) banner = pm.getApplicationBanner(pkg);
            if (icon == null) icon = pm.getApplicationIcon(pkg);
            label = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
        App app = new App(pkg, label, banner, icon);
        cache.put(pkg, app);
        return app;
    }

    private Intent launchIntent(String pkg) {
        Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (i == null) i = pm.getLaunchIntentForPackage(pkg);
        return i;
    }

    void launch(String pkg) {
        Intent i = launchIntent(pkg);
        if (i == null) return;
        try {
            context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(context, R.string.not_available, Toast.LENGTH_SHORT).show();
        }
    }

    void appInfo(String pkg) {
        try {
            context.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + pkg))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(context, R.string.not_available, Toast.LENGTH_SHORT).show();
        }
    }
}
