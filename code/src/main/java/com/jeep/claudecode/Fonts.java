package com.jeep.claudecode;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.fonts.Font;
import android.graphics.fonts.FontFamily;
import android.graphics.fonts.SystemFonts;
import android.os.Build;
import android.os.LocaleList;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The conversation's font: one for Latin text and one for Chinese, each the system's serif or
 * sans-serif, a font the phone has, or a font file added here (files/fonts). Nothing is bundled.
 */
final class Fonts {
    static final String SERIF = "serif", SANS = "sans-serif";
    private static final String PREFS = "fonts", LATIN = "latin", CJK = "cjk", SIZE = "size";
    /** Conversation text sizes, and each one's scale over the system's text size. */
    static final String[] SIZES = {"小", "中", "大"};
    private static final float[] SCALES = {0.9f, 1f, 1.12f};

    /** A font to choose: its id (SERIF, SANS, "sys:" + group, "file:" + name) and name. */
    static final class Choice {
        final String id, name;
        final Typeface preview;

        Choice(String id, String name, Typeface preview) {
            this.id = id;
            this.name = name;
            this.preview = preview;
        }
    }

    private static Typeface body;
    /** The phone's fonts, by group (file name without its weight): read once. */
    private static Map<String, List<Font>> system;

    private Fonts() {}

    static File dir(Context c) { return new File(c.getFilesDir(), "fonts"); }

    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }

    static String latin(Context c) { return prefs(c).getString(LATIN, SERIF); }

    static String cjk(Context c) { return prefs(c).getString(CJK, SERIF); }

    static int size(Context c) { return Math.max(0, Math.min(SIZES.length - 1, prefs(c).getInt(SIZE, 1))); }

    static void setSize(Context c, int size) { prefs(c).edit().putInt(SIZE, size).apply(); }

    static float scale(Context c) { return SCALES[size(c)]; }

    static void set(Context c, boolean cjk, String id) {
        prefs(c).edit().putString(cjk ? CJK : LATIN, id).apply();
        body = null;
    }

    /** The typeface for conversation text: Latin from one font, Chinese from the other. */
    static synchronized Typeface body(Context c) {
        if (body == null) body = build(c, latin(c), cjk(c));
        return body;
    }

    private static Typeface build(Context c, String latin, String cjk) {
        if (Build.VERSION.SDK_INT < 29) {
            // No mixing before Android 10: the Latin choice for everything.
            Typeface t = latin.startsWith("file:") ? fileTypeface(c, latin.substring(5)) : null;
            return t != null ? t : Typeface.create(latin.equals(SANS) ? SANS : SERIF, Typeface.NORMAL);
        }
        if (latin.equals(cjk) && (latin.equals(SERIF) || latin.equals(SANS))) return Typeface.create(latin, Typeface.NORMAL);
        try {
            // Latin first, so the Chinese font's own Latin letters are not used.
            FontFamily first = family(c, latin);
            FontFamily second = cjk.equals(SERIF) || cjk.equals(SANS) ? null : family(c, cjk);
            Typeface.CustomFallbackBuilder b;
            if (first != null) {
                b = new Typeface.CustomFallbackBuilder(first);
                if (second != null) b.addCustomFallback(second);
            } else if (second != null) {
                b = new Typeface.CustomFallbackBuilder(second);
            } else {
                return Typeface.create(latin, Typeface.NORMAL);
            }
            b.setSystemFallback(second == null ? cjk : SANS);
            return b.build();
        } catch (RuntimeException e) {
            return Typeface.create(SERIF, Typeface.NORMAL);
        }
    }

    /** A choice as a font family; the system's serif and sans-serif as the phone's own files. */
    private static FontFamily family(Context c, String id) {
        if (Build.VERSION.SDK_INT < 29) return null;
        try {
            if (id.startsWith("file:")) {
                return new FontFamily.Builder(new Font.Builder(new File(dir(c), id.substring(5))).build()).build();
            }
            String group = id.startsWith("sys:") ? id.substring(4) : generic(id);
            List<Font> fonts = group == null ? null : systemFonts().get(group);
            if (fonts == null || fonts.isEmpty()) return null;
            FontFamily.Builder b = null;
            Set<Integer> styles = new HashSet<>();
            for (Font f : fonts) {
                int key = f.getStyle().getWeight() * 2 + f.getStyle().getSlant();
                if (!styles.add(key)) continue;
                if (b == null) b = new FontFamily.Builder(f); else b.addFont(f);
            }
            return b == null ? null : b.build();
        } catch (Exception e) {
            return null;
        }
    }

    /** The group of the phone's font that draws Latin letters for serif or sans-serif. */
    private static String generic(String id) {
        Font f = drawnBy(Typeface.create(id, Typeface.NORMAL), "a");
        if (f != null && f.getFile() != null) {
            String g = group(f.getFile().getName());
            if (systemFonts().containsKey(g)) return g;
        }
        String guess = id.equals(SERIF) ? "NotoSerif" : "Roboto";
        return systemFonts().containsKey(guess) ? guess : null;
    }

    /** The font that actually draws the first character of the text (Android 12+), else null. */
    private static Font drawnBy(Typeface t, String text) {
        if (Build.VERSION.SDK_INT < 31) return null;
        Paint p = new Paint();
        p.setTypeface(t);
        android.graphics.text.PositionedGlyphs g = android.graphics.text.TextRunShaper.shapeTextRun(
                text, 0, text.length(), 0, text.length(), 0f, 0f, false, p);
        return g.glyphCount() > 0 ? g.getFont(0) : null;
    }

    private static String group(String fileName) {
        return fileName.replaceAll("\\.(ttf|otf|ttc)$", "").replaceAll("-[^-]*$", "");
    }

    private static Typeface fileTypeface(Context c, String name) {
        try {
            return Typeface.createFromFile(new File(dir(c), name));
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The phone's fonts grouped by file name without weight or style ("NotoSerif-Bold.ttf" → NotoSerif). */
    private static synchronized Map<String, List<Font>> systemFonts() {
        if (system != null) return system;
        Map<String, List<Font>> out = new LinkedHashMap<>();
        if (Build.VERSION.SDK_INT >= 29) {
            for (Font f : SystemFonts.getAvailableFonts()) {
                File file = f.getFile();
                if (file == null) continue;
                // A collection holds several languages' fonts: keep the Simplified Chinese one, else the first.
                LocaleList locales = f.getLocaleList();
                String group = group(file.getName());
                List<Font> list = out.get(group);
                if (list == null) out.put(group, list = new ArrayList<>());
                boolean hans = locales != null && locales.toLanguageTags().contains("Hans");
                if (file.getName().endsWith(".ttc") && f.getTtcIndex() > 0 && !hans) continue;
                if (hans) list.add(0, f); else list.add(f);
            }
        }
        system = out;
        return out;
    }

    /** What can be chosen for Latin (cjk false) or Chinese text, each with a preview of itself. */
    static List<Choice> choices(Context c, boolean cjk) {
        List<Choice> out = new ArrayList<>();
        out.add(new Choice(SERIF, "衬线（系统）", Typeface.create(SERIF, Typeface.NORMAL)));
        out.add(new Choice(SANS, "无衬线（系统）", Typeface.create(SANS, Typeface.NORMAL)));
        String sample = cjk ? "中" : "a";
        if (Build.VERSION.SDK_INT >= 29) {
            for (String group : systemFonts().keySet()) {
                // Fonts for one other script (Noto Sans Arabic …) have no place here.
                if (!cjk && group.matches("Noto(Sans|Serif)(?!CJK|Mono)[A-Z]\\w+.*")) continue;
                FontFamily fam = family(c, "sys:" + group);
                Typeface t = typeface(fam);
                if (t != null && covers(t, sample, fam.getFont(0))) out.add(new Choice("sys:" + group, group, t));
            }
        }
        File[] files = dir(c).listFiles();
        if (files != null) {
            for (File f : files) {
                Typeface t = fileTypeface(c, f.getName());
                FontFamily fam = family(c, "file:" + f.getName());
                if (t != null && (fam == null || covers(typeface(fam), sample, fam.getFont(0)))) {
                    out.add(new Choice("file:" + f.getName(), f.getName().replaceAll("\\.(ttf|otf|ttc)$", ""), t));
                }
            }
        }
        return out;
    }

    private static Typeface typeface(FontFamily f) {
        if (f == null || Build.VERSION.SDK_INT < 29) return null;
        try {
            return new Typeface.CustomFallbackBuilder(f).build();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Whether the font itself (not a fallback) draws the character; unknown before Android 12: yes. */
    private static boolean covers(Typeface t, String ch, Font own) {
        if (Build.VERSION.SDK_INT < 31) return true;
        if (t == null) return false;
        Font used = drawnBy(t, ch);
        return used != null && used.getFile() != null && own.getFile() != null
                && used.getFile().equals(own.getFile()) && used.getTtcIndex() == own.getTtcIndex();
    }

    /** The name shown for a choice. */
    static String name(String id) {
        if (id.equals(SERIF)) return "衬线";
        if (id.equals(SANS)) return "无衬线";
        String n = id.substring(id.indexOf(':') + 1);
        return n.replaceAll("\\.(ttf|otf|ttc)$", "");
    }
}
