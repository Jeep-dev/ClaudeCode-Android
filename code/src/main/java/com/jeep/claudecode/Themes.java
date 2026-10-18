package com.jeep.claudecode;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.drawable.ColorDrawable;
import android.util.SparseIntArray;
import android.view.View;
import android.view.Window;

/**
 * The app's colours: the light or dark set from res (following the system, or one of them
 * always), with a theme's own colours over it. Every colour is read through {@link Ui#color}.
 */
final class Themes {
    /** A theme: its id, name, light or dark (null: as the system), and colours over that set. */
    static final class Theme {
        final String id, name;
        final Boolean dark;
        final int[] colors;

        Theme(String id, String name, Boolean dark, int... colors) {
            this.id = id;
            this.name = name;
            this.dark = dark;
            this.colors = colors;
        }
    }

    static final Theme[] ALL = {
            new Theme("system", "跟随系统", null),
            new Theme("light", "浅色", false),
            new Theme("dark", "深色", true),
            new Theme("black", "纯黑", true,
                    R.color.cream, 0xFF000000, R.color.bg, 0xFF000000, R.color.sidebar, 0xFF0B0B0B,
                    R.color.surface, 0xFF171717, R.color.surface2, 0xFF111111, R.color.user, 0xFF1F1F1F,
                    R.color.codeBg, 0xFF0E0E0E),
            new Theme("white", "纯白", false,
                    R.color.cream, 0xFFFCFCFA, R.color.bg, 0xFFFCFCFA, R.color.sidebar, 0xFFF5F5F2,
                    R.color.surface, 0xFFFFFFFF, R.color.surface2, 0xFFF3F3F0, R.color.user, 0xFFEFEFEC,
                    R.color.codeBg, 0xFFF6F6F3, R.color.text, 0xFF141413, R.color.text2, 0xFF5C5C59,
                    R.color.text3, 0xFF999995),
            new Theme("paper", "纸张", false,
                    R.color.cream, 0xFFF5EEDC, R.color.bg, 0xFFF5EEDC, R.color.sidebar, 0xFFEDE4CE,
                    R.color.surface, 0xFFFBF7EC, R.color.surface2, 0xFFF0E8D4, R.color.user, 0xFFE9DFC6,
                    R.color.codeBg, 0xFFEFE7D3, R.color.text, 0xFF33291F, R.color.text2, 0xFF63564A,
                    R.color.text3, 0xFF958877),
            new Theme("mist", "雾白", false,
                    R.color.cream, 0xFFF7F8FA, R.color.bg, 0xFFF7F8FA, R.color.sidebar, 0xFFEEF0F3,
                    R.color.surface, 0xFFFFFFFF, R.color.surface2, 0xFFF1F3F5, R.color.user, 0xFFE9ECF0,
                    R.color.codeBg, 0xFFF2F4F6, R.color.text, 0xFF1C1E21, R.color.text2, 0xFF575C63,
                    R.color.text3, 0xFF8A9098),
            new Theme("slate", "石板", true,
                    R.color.cream, 0xFF1E2125, R.color.bg, 0xFF1E2125, R.color.sidebar, 0xFF181A1E,
                    R.color.surface, 0xFF2A2E33, R.color.surface2, 0xFF24272C, R.color.user, 0xFF32373D,
                    R.color.codeBg, 0xFF181A1E, R.color.text, 0xFFE8EAED, R.color.text2, 0xFFB4B9C0,
                    R.color.text3, 0xFF848A93),
    };

    private static Theme theme;
    private static final SparseIntArray over = new SparseIntArray();
    /** Where res colours are read when the theme fixes light or dark; null: the asking context. */
    private static Context res;

    private Themes() {}

    static Theme current(Context c) {
        if (theme == null) load(c, c.getSharedPreferences("app", Context.MODE_PRIVATE).getString("theme", "system"));
        return theme;
    }

    /** The immersive layout: the conversation under the status bar, the buttons and box as glass. */
    static boolean immersive(Context c) {
        return c.getSharedPreferences("app", Context.MODE_PRIVATE).getBoolean("immersive", false);
    }

    /** The conversation's title over it (off by default). */
    static boolean showTitle(Context c) {
        return c.getSharedPreferences("app", Context.MODE_PRIVATE).getBoolean("title", false);
    }

    /** The model's thinking as rows in the conversation (on by default). */
    static boolean showThinking(Context c) {
        return c.getSharedPreferences("app", Context.MODE_PRIVATE).getBoolean("thinking", true);
    }

    static void setShowThinking(Context c, boolean on) {
        c.getSharedPreferences("app", Context.MODE_PRIVATE).edit().putBoolean("thinking", on).apply();
    }

    static void setShowTitle(Context c, boolean on) {
        c.getSharedPreferences("app", Context.MODE_PRIVATE).edit().putBoolean("title", on).apply();
    }

    static void setImmersive(Context c, boolean on) {
        c.getSharedPreferences("app", Context.MODE_PRIVATE).edit().putBoolean("immersive", on).apply();
    }

    static void set(Context c, String id) {
        c.getSharedPreferences("app", Context.MODE_PRIVATE).edit().putString("theme", id).apply();
        load(c, id);
    }

    private static synchronized void load(Context c, String id) {
        Theme t = ALL[0];
        for (Theme x : ALL) if (x.id.equals(id)) t = x;
        over.clear();
        for (int i = 0; i + 1 < t.colors.length; i += 2) over.put(t.colors[i], t.colors[i + 1]);
        res = t.dark == null ? null : night(c, t.dark);
        theme = t;
    }

    static int color(Context c, int id) {
        current(c);
        int i = over.indexOfKey(id);
        if (i >= 0) return over.valueAt(i);
        return (res != null ? res : c).getColor(id);
    }

    static boolean dark(Context c) {
        Theme t = current(c);
        if (t.dark != null) return t.dark;
        return (c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
    }

    private static Context lightRes, darkRes;

    /** A theme's background and text colours; one following the system as light or as dark. */
    static int[] preview(Context c, Theme t, boolean darkIfSystem) {
        boolean dark = t.dark != null ? t.dark : darkIfSystem;
        if (lightRes == null) {
            lightRes = night(c, false);
            darkRes = night(c, true);
        }
        Context from = dark ? darkRes : lightRes;
        int[] out = {from.getColor(R.color.bg), from.getColor(R.color.text)};
        for (int i = 0; i + 1 < t.colors.length; i += 2) {
            if (t.colors[i] == R.color.bg) out[0] = t.colors[i + 1];
            if (t.colors[i] == R.color.text) out[1] = t.colors[i + 1];
        }
        return out;
    }

    private static Context night(Context c, boolean dark) {
        Context app = c.getApplicationContext();
        Configuration cfg = new Configuration(app.getResources().getConfiguration());
        cfg.uiMode = (cfg.uiMode & ~Configuration.UI_MODE_NIGHT_MASK)
                | (dark ? Configuration.UI_MODE_NIGHT_YES : Configuration.UI_MODE_NIGHT_NO);
        return app.createConfigurationContext(cfg);
    }

    /** A window in the theme's background, its bar icons dark on a light theme. */
    static void window(Window w, Context c) {
        if (w == null) return;
        int bg = color(c, R.color.bg);
        w.setBackgroundDrawable(new ColorDrawable(bg));
        w.setStatusBarColor(bg);
        w.setNavigationBarColor(bg);
        View d = w.getDecorView();
        int light = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        int flags = d.getSystemUiVisibility();
        d.setSystemUiVisibility(dark(c) ? flags & ~light : flags | light);
    }
}
