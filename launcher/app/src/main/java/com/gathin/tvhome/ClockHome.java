package com.gathin.tvhome;

import android.app.Activity;
import android.content.Context;
import android.graphics.Outline;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * clock_home: Home is the bare wallpaper. Down brings the clock, date and weather (top left) with the dock: one endless
 * row of the person's apps on a glass band at the bottom, the focus fixed on its first slot. Up (or the remote's
 * Settings button) brings the settings row into the header, built the same way. Up from the dock or Back returns to the
 * bare page. Holding OK on an app opens Move / Remove from Home / App info; All apps (in the settings row) adds apps.
 *
 * The launcher's grid stays loaded but hidden and never takes focus; every key on Home comes here. All slides run on
 * the launcher's own frame loop with one length and curve (Carousel.MOVE_MS), since the animator scale may be 0.
 */
final class ClockHome {
    interface Host {
        /** The Inputs entry of the settings row. */
        void openInputs();

        /** The frosted wallpaper for the glass in front, or null (no wallpaper, or Android before 12). */
        FrostLayer frost();

        /** The theme's dock_apps, the row's first apps until the person changes it. */
        List<String> themeDock();

        /** dock_tile_style: glyph (white glyph on clear glass), placard, framed, smoked or ivory. */
        String tileStyle();

        /** clock_home_idle_seconds: back to the bare page after this long without a key; 0 never. */
        int idleSeconds();

        /** The painting collections, or null without art_source. */
        ArtLibrary art();

        /** A collection (or Auto) was chosen: show a painting from it now. */
        void artChanged();

        /** The slideshow's change interval was chosen on the TV. */
        void intervalChanged(int minutes);
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
    private final boolean animations;
    private final float density;
    private final FrameLayout overlay;
    private final View header;
    private final DockApps store;
    private final List<DockApps.App> apps = new ArrayList<>();
    private final List<SettingsItems.Item> settings;
    private final Carousel dock;
    private final Carousel settingsRow;
    private final TextView hero;
    private final LinearLayout menu;
    private final List<TextView> menuRows = new ArrayList<>();
    private final FrameLayout allPanel;
    private final Carousel allList;
    private final List<DockApps.App> allApps = new ArrayList<>();
    /** The panel shows the painting collections (Art) instead of the apps (All apps). */
    private boolean artMode;
    private final List<String[]> artRows = new ArrayList<>();   // {kind, id, name, detail}
    private static final int[] INTERVALS = {5, 10, 30, 60, 1440};
    private TextView panelTitle;
    private final Typeface light;
    private final Typeface regular;
    private final String style;
    /** The painting's title and painter, top right, shown with the dock. */
    private final LinearLayout caption;
    private final TextView captionTitle;
    private final TextView captionArtist;
    private java.util.Map<String, String> captions;

    private boolean home = true;
    private boolean barOpen;
    private boolean menuOpen;
    private int menuSelected;
    private boolean moving;
    private boolean allOpen;
    private boolean okLong;
    private boolean okDown;
    private long lastTransition;
    private Tween dockTween;
    private Tween barTween;
    private Tween allTween;

    /** clock_home_idle_seconds: with no key for this long, the dock, the settings row and All apps put themselves away. */
    private final long idleMs;
    private final Runnable idle = () -> {
        if (!home || barOpen || allOpen || menuOpen) show(true);
    };

    ClockHome(Activity activity, Host host, boolean animations, View... hiddenForGood) {
        this.activity = activity;
        this.host = host;
        this.animations = animations;
        density = activity.getResources().getDisplayMetrics().density;
        light = font(R.string.font_light);
        regular = font(R.string.font_regular);
        store = new DockApps(activity);
        style = host.tileStyle() == null ? "glyph" : host.tileStyle();
        idleMs = Math.max(0, host.idleSeconds()) * 1000L;

        // The grid and its floating name and highlight stay out of the way for good.
        View gridWrap = activity.findViewById(R.id.gridWrap);
        gridWrap.setVisibility(View.INVISIBLE);
        ((ViewGroup) gridWrap).setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        for (View v : hiddenForGood) {
            v.animate().cancel();
            v.setAlpha(0f);
            v.setVisibility(View.INVISIBLE);
        }
        header = (View) activity.findViewById(R.id.leftBlock).getParent();
        header.setAlpha(0f);
        overlay = activity.findViewById(R.id.clockHome);
        overlay.setVisibility(View.VISIBLE);
        View oldRow = activity.findViewById(R.id.settingsScroll);
        if (oldRow != null) oldRow.setVisibility(View.GONE);

        int screenW = activity.getResources().getDisplayMetrics().widthPixels;
        int side = px(56);
        int bandW = screenW - 2 * side;

        // ---- the dock --------------------------------------------------------------------------------------
        int pad = px(16);
        int gap = px(18);
        int pitch = Math.round((bandW - 2 * pad) / SettingsItems.SLOTS);
        int tileW = pitch - gap;
        int tileH = Math.round(tileW * 9f / 16f);
        dock = new Carousel(activity, density, tileW, tileH, gap, pad, 28 * density,
                LiquidGlass.band(density), true, 14 * density, new Carousel.Binder() {
            @Override
            public View create(Context c, int width, int height) {
                return tile(c);
            }

            @Override
            public void bind(View view, int index) {
                bindTile(view, apps.get(index));
            }
        });
        FrameLayout.LayoutParams dockLp = new FrameLayout.LayoutParams(bandW, dock.bandHeight(),
                Gravity.BOTTOM | Gravity.START);
        dockLp.leftMargin = side;
        dockLp.bottomMargin = px(44);
        switch (style) {
            case "framed":
            case "smoked":
                dock.setRestGlass(0x8C121418, 0xA60A0B0E);
                break;
            case "ivory":
                dock.setRestGlass(0xF2EFE9DD, 0xF2E3DCCB);
                break;
            default:
                break;
        }
        overlay.addView(dock, dockLp);
        dock.setAlpha(0f);
        dock.setOnTarget(this::showName);

        hero = new TextView(activity);
        hero.setTextColor(0xFFFFFFFF);
        hero.setTextSize(TypedValue.COMPLEX_UNIT_SP, 44);
        hero.setSingleLine(true);
        hero.setIncludeFontPadding(false);
        if (light != null) hero.setTypeface(light);
        FrameLayout.LayoutParams heroLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.START);
        heroLp.leftMargin = side + pad;
        heroLp.bottomMargin = px(44) + dock.bandHeight() + px(16);
        overlay.addView(hero, heroLp);
        hero.setAlpha(0f);

        // ---- the painting's caption, top right ---------------------------------------------------------------
        caption = new LinearLayout(activity);
        caption.setOrientation(LinearLayout.VERTICAL);
        caption.setGravity(Gravity.END);
        captionTitle = new TextView(activity);
        captionTitle.setTextColor(0xFFFFFFFF);
        captionTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        captionTitle.setGravity(Gravity.END);
        captionTitle.setSingleLine(true);
        if (light != null) captionTitle.setTypeface(light);
        // A fixed-width line, right-aligned: a wrap_content title in this column was cut short on the TV.
        caption.addView(captionTitle, new LinearLayout.LayoutParams(px(720), ViewGroup.LayoutParams.WRAP_CONTENT));
        captionArtist = new TextView(activity);
        captionArtist.setTextColor(0xC8FFFFFF);
        captionArtist.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        captionArtist.setGravity(Gravity.END);
        captionArtist.setSingleLine(true);
        if (regular != null) captionArtist.setTypeface(regular);
        LinearLayout.LayoutParams artistLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        artistLp.topMargin = px(4);
        artistLp.gravity = Gravity.END;
        caption.addView(captionArtist, artistLp);
        FrameLayout.LayoutParams captionLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
        captionLp.rightMargin = side;
        captionLp.topMargin = px(48);
        overlay.addView(caption, captionLp);
        caption.setAlpha(0f);

        // ---- the settings row, in the header -----------------------------------------------------------------
        settings = SettingsItems.build(activity, host::openInputs, this::openAllApps, this::openArt);
        settingsRow = SettingsItems.row(activity, density, bandW, settings);
        FrameLayout.LayoutParams rowLp = new FrameLayout.LayoutParams(bandW, settingsRow.bandHeight(),
                Gravity.TOP | Gravity.START);
        rowLp.leftMargin = side;
        rowLp.topMargin = px(44);
        overlay.addView(settingsRow, rowLp);
        settingsRow.setAlpha(0f);
        settingsRow.setVisibility(View.INVISIBLE);

        // ---- the tile menu ---------------------------------------------------------------------------------
        menu = new LinearLayout(activity);
        menu.setOrientation(LinearLayout.VERTICAL);
        menu.setBackground(LiquidGlass.dark(density));
        menu.setPadding(px(10), px(10), px(10), px(10));
        for (String label : new String[]{activity.getString(R.string.tile_move),
                activity.getString(R.string.library_remove_shortcut), activity.getString(R.string.library_app_info)}) {
            TextView row = new TextView(activity);
            row.setText(label);
            row.setTextColor(0xFFFFFFFF);
            row.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(px(18), 0, px(18), 0);
            if (regular != null) row.setTypeface(regular);
            menu.addView(row, new LinearLayout.LayoutParams(px(240), px(46)));
            menuRows.add(row);
        }
        FrameLayout.LayoutParams menuLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.START);
        menuLp.leftMargin = side;
        menuLp.bottomMargin = px(44) + dock.bandHeight() + px(12);
        overlay.addView(menu, menuLp);
        menu.setVisibility(View.GONE);

        // ---- All apps: a column with the same fixed focus -------------------------------------------------------
        int panelW = px(460);
        int screenH = activity.getResources().getDisplayMetrics().heightPixels;
        int panelH = screenH - 2 * px(44);
        allPanel = new FrameLayout(activity);
        allPanel.setBackground(LiquidGlass.dark(density));
        allPanel.setClipToOutline(true);
        allPanel.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), 28 * density);
            }
        });
        TextView title = new TextView(activity);
        panelTitle = title;
        title.setText(R.string.clock_all_apps);
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        if (light != null) title.setTypeface(light);
        FrameLayout.LayoutParams titleLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        titleLp.leftMargin = px(28);
        titleLp.topMargin = px(22);
        allPanel.addView(title, titleLp);
        int listTop = px(70);
        allList = new Carousel(activity, density, panelW - 2 * px(16), px(54), px(6), px(16), 0f,
                null, false, 14 * density, new Carousel.Binder() {
            @Override
            public View create(Context c, int width, int height) {
                return listRow(c);
            }

            @Override
            public void bind(View view, int index) {
                if (artMode) bindArtRow(view, artRows.get(index));
                else bindListRow(view, allApps.get(index));
            }
        });
        allList.setVertical(0.03f);
        FrameLayout.LayoutParams listLp = new FrameLayout.LayoutParams(panelW, panelH - listTop);
        listLp.topMargin = listTop;
        allPanel.addView(allList, listLp);
        FrameLayout.LayoutParams panelLp = new FrameLayout.LayoutParams(panelW, panelH, Gravity.CENTER);
        overlay.addView(allPanel, panelLp);
        allPanel.setAlpha(0f);
        allPanel.setVisibility(View.INVISIBLE);

        reloadApps(0);
    }

    private Typeface font(int res) {
        try {
            return UiFont.get(activity, res);
        } catch (Exception e) {
            return null;
        }
    }

    private int px(int dp) {
        return Math.round(dp * density);
    }

    private long duration() {
        return animations ? Carousel.MOVE_MS : 0;
    }

    /** True while the bare page (no dock) is showing. */
    boolean isHome() {
        return home;
    }

    /** Arrows and OK pace themselves here; nothing else needs swallowing. */
    boolean swallowRepeat(KeyEvent event) {
        return false;
    }

    // ---- the dock's apps -----------------------------------------------------------------------------------

    private void reloadApps(int first) {
        apps.clear();
        apps.addAll(store.load(host.themeDock()));
        dock.setCount(apps.size(), Math.min(first, Math.max(0, apps.size() - 1)));
        showName();
    }

    /** A dock slot: glass (drawn by the carousel) holding the app in the theme's dock_tile_style. */
    private View tile(Context c) {
        FrameLayout t = new FrameLayout(c);
        ImageView glyph = new ImageView(c);
        glyph.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int size = px("framed".equals(style) ? 46 : 40);
        t.addView(glyph, new FrameLayout.LayoutParams(size, size, Gravity.CENTER));
        if ("ivory".equals(style)) glyph.setImageTintList(android.content.res.ColorStateList.valueOf(0xFF2B2824));
        TextView name = new TextView(c);
        name.setTextColor(0xFFFFFFFF);
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 19);
        name.setGravity(Gravity.CENTER);
        name.setMaxLines(2);
        name.setPadding(px(10), 0, px(10), 0);
        if (light != null) name.setTypeface(light);
        t.addView(name, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER));
        return t;
    }

    private void bindTile(View view, DockApps.App app) {
        FrameLayout t = (FrameLayout) view;
        ImageView glyph = (ImageView) t.getChildAt(0);
        TextView name = (TextView) t.getChildAt(1);
        boolean placard = "placard".equals(style);
        glyph.setImageDrawable(placard ? null : "framed".equals(style) ? app.icon : app.glyph(activity.getResources()));
        name.setText(placard ? app.label : "");
        // While moving, the carried app is marked by a blue edge.
        t.setForeground(moving && apps.indexOf(app) == dock.current() ? carried() : null);
    }

    private android.graphics.drawable.Drawable carried() {
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setCornerRadius(14 * density);
        g.setStroke(px(3), 0xFF6EC1FF);
        return g;
    }

    /** The wallpaper changed: its title and painter, from the collection's index or wallpapers/captions.txt. */
    void setPainting(java.io.File file) {
        if (captions == null) {
            java.io.File dir = Config.dir(activity);
            captions = WallpaperCaptions.read(dir == null ? null : new java.io.File(dir, "wallpapers"));
        }
        ArtLibrary art = host.art();
        String text = art == null ? null : art.caption(file);
        if (text == null && file != null) text = captions.get(file.getName());
        if (text == null) {
            captionTitle.setText("");
            captionArtist.setText("");
            return;
        }
        int bar = text.indexOf('|');
        captionTitle.setText(bar < 0 ? text : text.substring(0, bar).trim());
        captionArtist.setText(bar < 0 ? "" : text.substring(bar + 1).trim());
    }

    private void showName() {
        int i = dock.current();
        hero.setText(i >= 0 && i < apps.size() ? apps.get(i).label : "");
    }

    // ---- frost ------------------------------------------------------------------------------------------------

    /** The faint blur goes under the All apps panel when it is open. */
    private void placeFrost() {
        FrostLayer frost = host.frost();
        if (frost == null) return;
        // Only All apps sits on frost: the dock and settings bands are clear, so their tiles stand apart from them.
        View band = allPanel.getVisibility() == View.VISIBLE && allPanel.getAlpha() > 0f ? allPanel : null;
        if (band == null || band.getWidth() == 0) {
            frost.setAlpha(0f);
            return;
        }
        int[] at = new int[2];
        band.getLocationInWindow(at);
        RectF r = new RectF(at[0], at[1], at[0] + band.getWidth() * band.getScaleX(),
                at[1] + band.getHeight() * band.getScaleY());
        View root = band.getRootView();
        frost.place(r, 28 * density, new Rect(0, 0, root.getWidth(), root.getHeight()));
        frost.setAlpha(band.getAlpha());
    }

    // ---- states -------------------------------------------------------------------------------------------------

    private void dockTo(float target, Runnable end) {
        if (dockTween != null) dockTween.cancel();
        final float from = dock.getAlpha();
        final float distance = 40 * density;
        dockTween = new Tween(duration(), p -> {
            float v = from + (target - from) * p;
            dock.setAlpha(v);
            dock.setTranslationY((1f - v) * distance);
            hero.setAlpha(v);
            hero.setTranslationY((1f - v) * distance);
            header.setAlpha(v);
            caption.setAlpha(v);
            placeFrost();
        }, end);
        dockTween.run();
    }

    /** Shows the bare page: the dock slides down and away. */
    void show(boolean animated) {
        lastTransition = System.currentTimeMillis();
        closeMenu();
        endMove();
        closeAllApps(false);
        if (home) {
            closeBar(animated);
            return;
        }
        home = true;
        if (animated) {
            dockTo(0f, null);
        } else {
            if (dockTween != null) dockTween.cancel();
            dock.setAlpha(0f);
            hero.setAlpha(0f);
            header.setAlpha(0f);
            caption.setAlpha(0f);
            placeFrost();
        }
    }

    /** Leaves the bare page: the clock and the dock come in. */
    private void leave() {
        if (!home) return;
        closeBar(false);
        home = false;
        lastTransition = System.currentTimeMillis();
        showName();
        dockTo(1f, null);
    }

    /** The remote's Settings button on Home: the settings row, from wherever Home is. */
    void toggleSettings() {
        if (barOpen) {
            closeBar(true);
            return;
        }
        if (allOpen) closeAllApps(false);
        closeMenu();
        endMove();
        if (!home) show(true);
        openBar();
    }

    private void barTo(float target, Runnable end) {
        if (barTween != null) barTween.cancel();
        final float from = settingsRow.getAlpha();
        final float distance = 40 * density;
        barTween = new Tween(duration(), p -> {
            float v = from + (target - from) * p;
            settingsRow.setAlpha(v);
            settingsRow.setTranslationY(-(1f - v) * distance);
            placeFrost();
        }, end);
        barTween.run();
    }

    private void openBar() {
        barOpen = true;
        lastTransition = System.currentTimeMillis();
        settingsRow.jumpTo(0);
        settingsRow.setVisibility(View.VISIBLE);
        barTo(1f, null);
    }

    private void closeBar(boolean animated) {
        if (!barOpen) return;
        barOpen = false;
        lastTransition = System.currentTimeMillis();
        if (!animated) {
            if (barTween != null) barTween.cancel();
            settingsRow.setAlpha(0f);
            settingsRow.setVisibility(View.INVISIBLE);
            placeFrost();
            return;
        }
        barTo(0f, () -> {
            if (!barOpen) settingsRow.setVisibility(View.INVISIBLE);
            placeFrost();
        });
    }

    // ---- tile menu and move ----------------------------------------------------------------------------------

    private void openMenu() {
        if (apps.isEmpty()) return;
        menuOpen = true;
        menuSelected = 0;
        refreshMenu();
        menu.setVisibility(View.VISIBLE);
    }

    private void closeMenu() {
        if (!menuOpen) return;
        menuOpen = false;
        menu.setVisibility(View.GONE);
    }

    private void refreshMenu() {
        for (int i = 0; i < menuRows.size(); i++) {
            TextView row = menuRows.get(i);
            boolean on = i == menuSelected;
            row.setBackground(on ? LiquidGlass.focus(density) : null);
        }
    }

    private void menuAction() {
        int i = dock.current();
        if (i < 0 || i >= apps.size()) return;
        DockApps.App app = apps.get(i);
        closeMenu();
        switch (menuSelected) {
            case 0:
                moving = true;
                dock.refresh();
                break;
            case 1:
                apps.remove(i);
                store.save(apps);
                reloadApps(Math.max(0, Math.min(i, apps.size() - 1)));
                break;
            default:
                store.appInfo(app.pkg);
                break;
        }
    }

    /** Move mode: Left/Right carry the app one place; the row follows so it stays in the focus slot. */
    private void moveBy(int direction) {
        int i = dock.current();
        int n = apps.size();
        if (n < 2) return;
        int j = Math.floorMod(i + direction, n);
        DockApps.App app = apps.remove(i);
        apps.add(j, app);
        dock.setCount(n, j);
        showName();
    }

    private void endMove() {
        if (!moving) return;
        moving = false;
        store.save(apps);
        dock.refresh();
    }

    // ---- All apps -------------------------------------------------------------------------------------------

    private View listRow(Context c) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(px(14), 0, px(14), 0);
        ImageView icon = new ImageView(c);
        row.addView(icon, new LinearLayout.LayoutParams(px(32), px(32)));
        TextView name = new TextView(c);
        name.setTextColor(0xFFFFFFFF);
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        name.setSingleLine(true);
        if (regular != null) name.setTypeface(regular);
        LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        nameLp.leftMargin = px(14);
        row.addView(name, nameLp);
        TextView state = new TextView(c);
        state.setTextColor(0xB3FFFFFF);
        state.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        if (regular != null) state.setTypeface(regular);
        row.addView(state);
        return row;
    }

    private void bindListRow(View view, DockApps.App app) {
        LinearLayout row = (LinearLayout) view;
        ((ImageView) row.getChildAt(0)).setImageDrawable(app.glyph(activity.getResources()));
        ((TextView) row.getChildAt(1)).setText(app.label);
        ((TextView) row.getChildAt(2)).setText(onHome(app.pkg) ? activity.getString(R.string.library_on_home)
                : activity.getString(R.string.library_add_home));
    }

    private boolean onHome(String pkg) {
        for (DockApps.App a : apps) if (a.pkg.equals(pkg)) return true;
        return false;
    }

    /** All apps, from the settings row (or the Settings button's window over an app). */
    void openAllApps() {
        closeBar(false);
        closeMenu();
        endMove();
        artMode = false;
        panelTitle.setText(R.string.clock_all_apps);
        allApps.clear();
        allApps.addAll(store.all());
        allList.setCount(allApps.size(), 0);
        showPanel();
    }

    /** Art, from the settings row: Auto, the collections under their headings, and how often the painting changes. */
    void openArt() {
        closeBar(false);
        closeMenu();
        endMove();
        artMode = true;
        panelTitle.setText("Art");
        buildArtRows();
        ArtLibrary art = host.art();
        String chosen = art == null ? ArtLibrary.AUTO : art.selection();
        int first = 0;
        for (int i = 0; i < artRows.size(); i++) if (artRows.get(i)[1].equals(chosen)) first = i;
        allList.setCount(artRows.size(), first);
        showPanel();
    }

    private void buildArtRows() {
        artRows.clear();
        ArtLibrary art = host.art();
        List<ArtLibrary.Shelf> shelves = art == null ? new ArrayList<>() : art.shelves();
        artRows.add(new String[]{"auto", ArtLibrary.AUTO, "Auto",
                shelves.isEmpty() ? "Paintings are still downloading" : "Weather and season"});
        for (String group : new String[]{"Subjects", "Movements", "Painters", "Your folders"}) {
            boolean any = false;
            for (ArtLibrary.Shelf shelf : shelves) {
                if (!group.equals(shelf.group)) continue;
                if (!any) artRows.add(new String[]{"header", "", group, ""});
                any = true;
                artRows.add(new String[]{"shelf", shelf.id, shelf.name, shelf.pieces.size() + " paintings"});
            }
        }
        artRows.add(new String[]{"header", "", "Slideshow", ""});
        artRows.add(new String[]{"interval", "", "Change every", intervalText()});
    }

    private int intervalMinutes() {
        ArtLibrary art = host.art();
        int chosen = art == null ? 0 : art.intervalMinutes();
        return chosen > 0 ? chosen : 10;
    }

    private String intervalText() {
        int m = intervalMinutes();
        return m >= 1440 ? "Once a day" : m >= 60 ? (m / 60) + " hour" + (m >= 120 ? "s" : "") : m + " minutes";
    }

    private void bindArtRow(View view, String[] row) {
        LinearLayout r = (LinearLayout) view;
        ((ImageView) r.getChildAt(0)).setImageDrawable(null);
        TextView name = (TextView) r.getChildAt(1);
        TextView detail = (TextView) r.getChildAt(2);
        boolean header = "header".equals(row[0]);
        name.setText(header ? row[2].toUpperCase(java.util.Locale.ROOT) : row[2]);
        name.setTextColor(header ? 0x99FFFFFF : 0xFFFFFFFF);
        name.setLetterSpacing(header ? 0.15f : 0f);
        ArtLibrary art = host.art();
        boolean showing = !header && !"interval".equals(row[0])
                && (art != null ? art.selection().equals(row[1]) : ArtLibrary.AUTO.equals(row[1]));
        detail.setText("interval".equals(row[0]) ? intervalText() : showing ? "Showing · " + row[3] : row[3]);
    }

    private boolean artHeader(int index) {
        return index >= 0 && index < artRows.size() && "header".equals(artRows.get(index)[0]);
    }

    /** Up/Down in Art: one step, and on past a heading, so the focus always rests on something to choose. */
    private void artStep(int direction, boolean repeat) {
        allList.step(direction, repeat);
        for (int guard = 0; guard < 3 && artHeader(allList.current()); guard++) allList.step(direction, false);
    }

    private void chooseArt() {
        int i = allList.current();
        ArtLibrary art = host.art();
        if (art == null || i < 0 || i >= artRows.size()) return;
        String[] row = artRows.get(i);
        if ("interval".equals(row[0])) {
            int now = intervalMinutes(), next = INTERVALS[0];
            for (int k = 0; k < INTERVALS.length; k++) {
                if (INTERVALS[k] == now) next = INTERVALS[(k + 1) % INTERVALS.length];
            }
            art.setIntervalMinutes(next);
            host.intervalChanged(next);
        } else if (!"header".equals(row[0])) {
            art.select(row[1]);
            host.artChanged();
        }
        allList.refresh();
    }

    private void showPanel() {
        allOpen = true;
        lastTransition = System.currentTimeMillis();
        allPanel.setVisibility(View.VISIBLE);
        if (allTween != null) allTween.cancel();
        final float from = allPanel.getAlpha();
        allTween = new Tween(duration(), p -> {
            float v = from + (1f - from) * p;
            allPanel.setAlpha(v);
            allPanel.setScaleX(0.96f + 0.04f * v);
            allPanel.setScaleY(0.96f + 0.04f * v);
            placeFrost();
        }, this::placeFrost);
        allTween.run();
    }

    private void closeAllApps(boolean animated) {
        if (!allOpen) return;
        allOpen = false;
        if (allTween != null) allTween.cancel();
        if (!animated) {
            allPanel.setAlpha(0f);
            allPanel.setVisibility(View.INVISIBLE);
            placeFrost();
            return;
        }
        final float from = allPanel.getAlpha();
        allTween = new Tween(duration(), p -> {
            allPanel.setAlpha(from * (1f - p));
            placeFrost();
        }, () -> {
            allPanel.setVisibility(View.INVISIBLE);
            placeFrost();
        });
        allTween.run();
    }

    private void toggleOnHome() {
        int i = allList.current();
        if (i < 0 || i >= allApps.size()) return;
        DockApps.App app = allApps.get(i);
        int keep = Math.max(0, dock.current());
        if (onHome(app.pkg)) {
            for (int k = 0; k < apps.size(); k++) {
                if (apps.get(k).pkg.equals(app.pkg)) {
                    apps.remove(k);
                    break;
                }
            }
        } else {
            apps.add(app);
        }
        store.save(apps);
        reloadApps(Math.max(0, Math.min(keep, apps.size() - 1)));
        allList.refresh();
    }

    // ---- keys -----------------------------------------------------------------------------------------------

    private static boolean isOk(int code) {
        return code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
                || code == KeyEvent.KEYCODE_NUMPAD_ENTER;
    }

    private static boolean isNav(int code) {
        return code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT
                || code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN
                || code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE || isOk(code);
    }

    /** True when the key was used. Every arrow, OK and Back on Home comes here. */
    boolean handleKey(KeyEvent event) {
        int code = event.getKeyCode();
        if (!isNav(code)) return false;
        // Any key on Home restarts the idle countdown; it only matters while something is open.
        overlay.removeCallbacks(idle);
        if (idleMs > 0) overlay.postDelayed(idle, idleMs);
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        boolean up = event.getAction() == KeyEvent.ACTION_UP;
        boolean repeat = event.getRepeatCount() > 0;
        boolean first = down && !repeat;
        boolean back = code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE;

        if (allOpen) {
            if (back) {
                if (first) closeAllApps(true);
            } else if (code == KeyEvent.KEYCODE_DPAD_UP) {
                if (down) {
                    if (artMode) artStep(-1, repeat);
                    else allList.step(-1, repeat);
                }
            } else if (code == KeyEvent.KEYCODE_DPAD_DOWN) {
                if (down) {
                    if (artMode) artStep(1, repeat);
                    else allList.step(1, repeat);
                }
            } else if (isOk(code)) {
                if (up && !event.isCanceled()) {
                    if (artMode) chooseArt();
                    else toggleOnHome();
                }
            }
            return true;
        }
        if (menuOpen) {
            if (back) {
                if (first) closeMenu();
            } else if (code == KeyEvent.KEYCODE_DPAD_UP && first) {
                menuSelected = Math.floorMod(menuSelected - 1, menuRows.size());
                refreshMenu();
            } else if (code == KeyEvent.KEYCODE_DPAD_DOWN && first) {
                menuSelected = Math.floorMod(menuSelected + 1, menuRows.size());
                refreshMenu();
            } else if (isOk(code) && up) {
                // The release of the hold that opened the menu must not choose its first entry.
                if (okLong) {
                    okLong = false;
                } else {
                    menuAction();
                }
            }
            return true;
        }
        if (moving) {
            if (code == KeyEvent.KEYCODE_DPAD_LEFT && first) moveBy(-1);
            else if (code == KeyEvent.KEYCODE_DPAD_RIGHT && first) moveBy(1);
            else if ((back || isOk(code)) && up) endMove();
            return true;
        }
        if (barOpen) {
            if (code == KeyEvent.KEYCODE_DPAD_LEFT) {
                if (down) settingsRow.step(-1, repeat);
            } else if (code == KeyEvent.KEYCODE_DPAD_RIGHT) {
                if (down) settingsRow.step(1, repeat);
            } else if (code == KeyEvent.KEYCODE_DPAD_DOWN || back) {
                if (first) closeBar(true);
            } else if (isOk(code)) {
                if (up && !event.isCanceled()) {
                    int i = settingsRow.current();
                    Runnable action = i >= 0 ? settings.get(i).action : null;
                    closeBar(false);
                    if (action != null) action.run();
                }
            }
            return true;
        }
        if (home) {
            if (code == KeyEvent.KEYCODE_DPAD_DOWN && first) leave();
            else if (code == KeyEvent.KEYCODE_DPAD_UP && first) openBar();
            return true;
        }
        // The dock.
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (down) dock.step(-1, repeat);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (down) dock.step(1, repeat);
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (first) show(true);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                return true;
            default:
                break;
        }
        if (back) {
            if (first) show(true);
            return true;
        }
        // OK: a press opens the app, a hold opens the tile menu.
        if (first) {
            okDown = true;
            okLong = false;
        } else if (down && okDown && !okLong) {
            okLong = true;
            openMenu();
        } else if (up) {
            boolean wasLong = okLong;
            okDown = false;
            if (!wasLong && !event.isCanceled()) {
                int i = dock.current();
                if (i >= 0 && i < apps.size()) store.launch(apps.get(i).pkg);
            }
            if (!menuOpen) okLong = false;
        }
        return true;
    }
}
