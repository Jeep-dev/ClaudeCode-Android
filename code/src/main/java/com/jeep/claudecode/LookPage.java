package com.jeep.claudecode;

import android.app.Dialog;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Canvas;
import android.graphics.Typeface;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.InputStream;
import java.util.List;
import java.util.Locale;

/** Settings → 外观: the colours (a palette each, light, dark or the system's), what is shown, the text. */
final class LookPage extends Part {
    /** Which sheet a font file is imported from, to return to it. */
    private boolean fontCjk = true;

    LookPage(MainActivity app) { super(app); }

    void fillLook(LinearLayout box) {
        app.settings.section(box, "主题");
        LinearLayout themes = app.settings.card(box);
        // As RikkaHub has them: four to a row, all as wide, the last row's gaps kept.
        LinearLayout grid = Ui.column(app);
        grid.setPadding(dp(8), dp(12), dp(8), dp(12));
        String now = Themes.current(app).id;
        boolean dark = Themes.dark(app);
        LinearLayout row = null;
        for (int i = 0; i < Themes.ALL.length; i++) {
            if (i % 4 == 0) {
                row = Ui.row(app);
                row.setGravity(Gravity.TOP);
                grid.addView(row, Ui.fill());
                if (i > 0) Ui.margins(row, app, 0, 8, 0, 0);
            }
            Themes.Theme t = Themes.ALL[i];
            row.addView(swatch(t, t.id.equals(now), dark), new LinearLayout.LayoutParams(0, -2, 1));
            if (i % 4 > 0) Ui.margins(row.getChildAt(row.getChildCount() - 1), app, 4, 0, 0, 0);
        }
        for (int i = Themes.ALL.length % 4; i > 0 && i < 4; i++) {
            row.addView(new View(app), new LinearLayout.LayoutParams(0, 1, 1));
            Ui.margins(row.getChildAt(row.getChildCount() - 1), app, 4, 0, 0, 0);
        }
        themes.addView(grid, Ui.fill());
        sheets.choice(themes, "深色模式", Themes.MODES, Themes.mode(app), m -> {
            Themes.setMode(app, m);
            app.restyle();
        });
        sheets.toggle(themes, "纯黑背景", "深色时页面用纯黑", Themes.pureBlack(app), on -> {
            Themes.setPureBlack(app, on);
            if (Themes.dark(app)) app.restyle();
        });

        app.settings.section(box, "显示");
        LinearLayout show = app.settings.card(box);
        sheets.toggle(show, "沉浸式", "对话延伸到状态栏下，按钮和输入框是玻璃效果", Themes.immersive(app), on -> {
            Themes.setImmersive(app, on);
            app.restyle();
        });
        sheets.toggle(show, "标题", "在对话上方显示它的标题", Themes.showTitle(app), on -> {
            Themes.setShowTitle(app, on);
            app.restyle();
        });
        sheets.toggle(show, "思考", "显示模型的思考过程", Themes.showThinking(app), on -> {
            Themes.setShowThinking(app, on);
            app.settings.fillSettings(app.settings.launchCache);
            app.renderNow();
        });
        sheets.toggle(show, "对话时间", "侧栏里显示每个对话多久以前用过", prefs.getBoolean("drawerTimes", true), on -> {
            prefs.edit().putBoolean("drawerTimes", on).apply();
            app.settings.fillSettings(app.settings.launchCache);
            app.drawer.renderSessions();
        });
        sheets.toggle(show, "只显示当前目录", "侧栏只列出当前工作目录里的对话", app.drawer.onlyHere(), on -> {
            prefs.edit().putBoolean("drawerOnlyHere", on).apply();
            app.settings.fillSettings(app.settings.launchCache);
            app.drawer.renderSessions();
        });
        String footer = prefs.getString("footer", "");
        sheets.option(show, 0, "底部文字", footer.isEmpty() ? "不显示" : footer, false, () -> sheets.textSheet("底部文字", footer, "", false, (t, done) -> {
            prefs.edit().putString("footer", t.trim()).apply();
            app.drawer.shownLink = null;
            app.drawer.updateLink();
            done.run();
        }, app.settings::refreshSettings));

        app.settings.section(box, "螃蟹");
        LinearLayout crabs = app.settings.card(box);
        LinearLayout styles = Ui.column(app);
        styles.setPadding(dp(8), dp(8), dp(8), dp(8));
        LinearLayout line = null;
        for (int i = 0; i < Crab.NAMES.length; i++) {
            if (i % 3 == 0) {
                line = Ui.row(app);
                styles.addView(line, Ui.fill());
            }
            line.addView(crabCell(i), new LinearLayout.LayoutParams(0, -2, 1));
        }
        crabs.addView(styles, Ui.fill());

        app.settings.section(box, "文字");
        LinearLayout text = app.settings.card(box);
        sheets.choice(text, "大小", Fonts.SIZES, Fonts.size(app), i -> {
            Fonts.setSize(app, i);
            app.restyle();
        });
        sheets.option(text, 0, "中文字体", Fonts.name(Fonts.cjk(app)), false, () -> fontSheet(true));
        sheets.option(text, 0, "英文字体", Fonts.name(Fonts.latin(app)), false, () -> fontSheet(false));
    }

    /**
     * A palette as RikkaHub draws it, in the light or dark in use: a round of its primary's
     * container (left half), the secondary's (top right) and the tertiary's (bottom right), its
     * primary in the middle, larger and ticked when chosen; its name below in its primary.
     */
    /** One of the crab's styles, wearing it, the chosen one marked. */
    private View crabCell(int style) {
        boolean chosen = Themes.crabStyle(app) == style;
        LinearLayout cell = Ui.column(app);
        cell.setGravity(Gravity.CENTER_HORIZONTAL);
        cell.setPadding(0, dp(6), 0, dp(8));
        cell.setBackground(Ui.press(app, null, dp(16)));
        cell.addView(new Crab(app, style, true), new LinearLayout.LayoutParams(-2, -2));
        TextView name = Ui.text(app, Crab.NAMES[style], 12.5f, chosen ? R.color.accentPress : R.color.text2);
        if (chosen) name.setTypeface(Ui.MEDIUM);
        name.setPadding(0, dp(2), 0, 0);
        cell.addView(name);
        cell.setOnClickListener(v -> {
            if (chosen) return;
            Themes.setCrabStyle(app, style);
            app.restyle();
        });
        return cell;
    }

    private View swatch(Themes.Theme t, boolean chosen, boolean dark) {
        int[] c = t.swatch(dark); // primary, its container, the secondary's, the tertiary's, on primary
        LinearLayout cell = Ui.column(app);
        cell.setGravity(Gravity.CENTER_HORIZONTAL);
        cell.setPadding(0, dp(8), 0, dp(8));
        cell.setBackground(Ui.press(app, null, dp(16)));
        FrameLayout disc = new FrameLayout(app);
        View circle = new View(app) {
            final android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            final android.graphics.RectF oval = new android.graphics.RectF();

            @Override protected void onDraw(Canvas canvas) {
                float w = getWidth(), r = w / 2f;
                oval.set(0, 0, w, w);
                p.setColor(c[1]);
                canvas.drawArc(oval, 90, 180, true, p);
                p.setColor(c[2]);
                canvas.drawArc(oval, 270, 90, true, p);
                p.setColor(c[3]);
                canvas.drawArc(oval, 0, 90, true, p);
                p.setColor(c[0]);
                canvas.drawCircle(r, r, dp(chosen ? 12 : 8), p);
            }
        };
        disc.addView(circle, new FrameLayout.LayoutParams(dp(48), dp(48)));
        if (chosen) {
            ImageView tick = new ImageView(app);
            tick.setImageResource(R.drawable.ic_check);
            tick.setImageTintList(android.content.res.ColorStateList.valueOf(c[4]));
            disc.addView(tick, new FrameLayout.LayoutParams(dp(16), dp(16), Gravity.CENTER));
        }
        cell.addView(disc, new LinearLayout.LayoutParams(dp(48), dp(48)));
        TextView name = Ui.text(app, t.name, 12.5f, R.color.text);
        name.setTextColor(c[0]);
        name.setTypeface(Ui.MEDIUM);
        name.setPadding(0, dp(6), 0, 0);
        name.setSingleLine(true);
        cell.addView(name);
        cell.setOnClickListener(v -> {
            if (chosen) return;
            Themes.set(app, t.id);
            app.restyle();
        });
        return cell;
    }

    // ---- fonts ----

    private void fontSheet(boolean cjk) {
        fontCjk = cjk;
        Dialog sheet = sheets.sheet(cjk ? "中文字体" : "英文字体", null);
        LinearLayout card = sheets.card(sheet);
        String now = cjk ? Fonts.cjk(app) : Fonts.latin(app);
        TextView wait = Ui.text(app, "…", 14, R.color.text3);
        wait.setGravity(Gravity.CENTER);
        wait.setPadding(0, dp(18), 0, dp(18));
        card.addView(wait, Ui.fill());
        LinearLayout add = sheets.card(sheet);
        Ui.margins(add, app, 0, 10, 0, 0);
        sheets.option(add, R.drawable.ic_plus, "导入字体文件", "ttf / otf", false, () -> {
            sheet.dismiss();
            Intent pick = new Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
            try {
                app.startActivityForResult(pick, MainActivity.PICK_FONT);
            } catch (android.content.ActivityNotFoundException e) {
                toast("没有可用的文件选择器");
            }
        });
        sheet.show();
        // Reading the phone's fonts takes a moment: off the main thread.
        work.execute(() -> {
            List<Fonts.Choice> all = Fonts.choices(app, cjk);
            ui.post(() -> {
                card.removeAllViews();
                String sample = cjk ? "  永字八法" : "  Aa";
                for (Fonts.Choice f : all) {
                    sheets.option(card, 0, f.name + sample, null, false, () -> {
                        Fonts.set(app, cjk, f.id);
                        app.md.fontChanged();
                        sheet.dismiss();
                        app.screen.adapter.notifyDataSetChanged();
                        app.settings.refreshSettings();
                    });
                    LinearLayout row = (LinearLayout) card.getChildAt(card.getChildCount() - 1);
                    ((TextView) row.getChildAt(0)).setTypeface(f.preview);
                    if (f.id.equals(now)) sheets.check(card);
                }
            });
        });
    }

    /** Copies a picked font file into files/fonts, where it is offered with the others. */
    void addFont(Uri uri) {
        work.execute(() -> {
            String name = "font.ttf";
            try (Cursor c = app.getContentResolver().query(uri, null, null, null, null)) {
                if (c != null && c.moveToFirst()) {
                    int n = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (n >= 0 && c.getString(n) != null) name = c.getString(n);
                }
            } catch (RuntimeException ignored) {
                // default name
            }
            String safe = name.replaceAll("[/\\\\]", "_");
            if (!safe.toLowerCase(Locale.ROOT).matches(".*\\.(ttf|otf|ttc)$")) {
                ui.post(() -> toast("只支持 ttf、otf、ttc"));
                return;
            }
            java.io.File out = new java.io.File(Fonts.dir(app), safe);
            String error = null;
            try (InputStream in = app.getContentResolver().openInputStream(uri)) {
                if (in == null) throw new java.io.IOException("读不到文件");
                Fonts.dir(app).mkdirs();
                try (java.io.OutputStream o = new java.io.FileOutputStream(out)) {
                    Userland.copy(in, o);
                }
                if (Typeface.createFromFile(out) == Typeface.DEFAULT) throw new java.io.IOException("不是可用的字体");
            } catch (Exception e) {
                out.delete();
                error = e.getMessage();
            }
            String err = error;
            ui.post(() -> {
                if (err != null) { toast("导入失败：" + err); return; }
                toast("已导入");
                fontSheet(fontCjk);
            });
        });
    }
}
