package com.jeep.claudecode;

import android.app.Dialog;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Sheets rising from the bottom, and the rows, switches and choices in them: what the settings pages and the menus are built of. */
final class Sheets extends Part {
    /** The dim behind a sheet (its style's backgroundDimAmount). */
    private static final float SHEET_DIM = 0.34f;
    /** Settings is being filled: cards and options are made as its tiles, not as in a sheet. */
    boolean tiled;

    Sheets(MainActivity app) { super(app); }

    Dialog sheet(String titleText, String sub) {
        SheetBox box = new SheetBox(app);
        Dialog d = new Dialog(app, R.style.Sheet) {
            private boolean leaving;

            /** Up from the bottom edge, the dim coming with it. */
            @Override public void show() {
                super.show();
                Window w = getWindow();
                box.setTranslationY(app.getResources().getDisplayMetrics().heightPixels);
                box.post(() -> {
                    box.setTranslationY(box.getHeight());
                    box.animate().translationY(0).setDuration(Motion.PAGE).setInterpolator(Motion.EMPHASIZED).start();
                    dim(w, 0f, SHEET_DIM);
                });
            }

            /** Down out of sight, the dim going, and only then away: however it is closed. */
            @Override public void dismiss() {
                if (leaving) return;
                if (!isShowing() || app.isFinishing() || app.isDestroyed() || box.getHeight() == 0) { super.dismiss(); return; }
                leaving = true;
                dim(getWindow(), SHEET_DIM, 0f);
                box.animate().translationY(box.getHeight()).setDuration(250).setInterpolator(Motion.EMPHASIZED)
                        .withEndAction(super::dismiss).start();
            }
        };
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        box.close = d::cancel;
        android.graphics.drawable.GradientDrawable bg = Ui.round(col(R.color.bg), 0);
        float r = dp(22);
        bg.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        box.setBackground(bg);
        box.setPadding(dp(18), dp(8), dp(18), dp(18));
        View grab = new View(app);
        grab.setBackground(Ui.round(col(R.color.borderStrong), dp(2)));
        LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(dp(38), dp(4));
        gp.gravity = Gravity.CENTER_HORIZONTAL;
        gp.bottomMargin = dp(14);
        box.addView(grab, gp);
        TextView t = Ui.text(app, titleText, 21, R.color.text);
        t.setTypeface(Ui.SERIF);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        box.addView(t);
        if (sub != null && !sub.isEmpty()) {
            TextView s = Ui.text(app, sub, 14, R.color.text2);
            s.setLineSpacing(0, 1.25f);
            s.setPadding(0, dp(8), 0, 0);
            box.addView(s);
        }
        ScrollView scroll = new ScrollView(app) {
            @Override protected void onMeasure(int w, int h) {
                super.onMeasure(w, MeasureSpec.makeMeasureSpec((int) (getResources().getDisplayMetrics().heightPixels * 0.62f), MeasureSpec.AT_MOST));
            }

            // A long press that starts selecting gives the text focus, and the scroll would then bring
            // the whole text into view: by its top, past the padding, when it is taller than the sheet.
            // It stays put; dragging a handle to the edge still scrolls (another path).
            @Override public void requestChildFocus(View child, View focused) {
                int y = getScrollY();
                super.requestChildFocus(child, focused);
                scrollTo(getScrollX(), y);
            }
        };
        LinearLayout content = Ui.column(app);
        content.setPadding(0, dp(12), 0, 0);
        scroll.addView(content);
        box.addView(scroll, Ui.fill());
        box.setTag(content);
        d.setContentView(box);
        Window w = d.getWindow();
        if (w != null) {
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setGravity(Gravity.BOTTOM);
            // It moves itself (show, dismiss); the dim follows it.
            w.setWindowAnimations(0);
            w.setDimAmount(0f);
        }
        d.setCanceledOnTouchOutside(true);
        return d;
    }

    private static void dim(Window w, float from, float to) {
        if (w == null) return;
        android.animation.ValueAnimator a = android.animation.ValueAnimator.ofFloat(from, to);
        a.setDuration(to > from ? Motion.PAGE : 250);
        a.setInterpolator(Motion.EMPHASIZED);
        a.addUpdateListener(x -> w.setDimAmount((float) x.getAnimatedValue()));
        a.start();
    }

    LinearLayout content(Dialog d) {
        ViewGroup root = d.findViewById(android.R.id.content);
        return (LinearLayout) root.getChildAt(0).getTag();
    }

    LinearLayout card(Dialog d) {
        LinearLayout card = Ui.column(app);
        card.setPadding(dp(4), dp(4), dp(4), dp(4));
        card.setBackground(Ui.round(col(R.color.surface), dp(14), dp(1), col(R.color.border)));
        content(d).addView(card, Ui.fill());
        return card;
    }

    /** A row: icon, label, and its value on the right. */
    void option(LinearLayout card, int iconRes, String label, String value, boolean danger, Runnable click) {
        if (tiled) { tileOption(card, iconRes, label, value, danger, click); return; }
        LinearLayout row = Ui.row(app);
        row.setPadding(dp(14), 0, dp(14), 0);
        row.setMinimumHeight(dp(50));
        if (click != null) row.setBackground(Ui.press(app, null, dp(12)));
        if (iconRes != 0) {
            row.addView(Ui.icon(app, iconRes, 19, danger ? R.color.danger : R.color.text2));
            row.addView(new View(app), new LinearLayout.LayoutParams(dp(14), 1));
        }
        TextView l = Ui.text(app, label, 15.5f, danger ? R.color.danger : R.color.text);
        l.setSingleLine(true);
        l.setEllipsize(TextUtils.TruncateAt.END);
        row.addView(l, new LinearLayout.LayoutParams(0, -2, 1));
        if (value != null && !value.isEmpty()) {
            TextView v = Ui.text(app, value, 14, R.color.text3);
            v.setSingleLine(true);
            v.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            v.setMaxWidth((int) (app.getResources().getDisplayMetrics().widthPixels * 0.45f));
            v.setPadding(dp(12), 0, 0, 0);
            row.addView(v);
        }
        if (click != null) row.setOnClickListener(v -> click.run());
        card.addView(row, Ui.fill());
    }

    /** A row of Settings: its icon in a tinted circle, the label with the value under it. */
    private void tileOption(LinearLayout card, int iconRes, String label, String value, boolean danger, Runnable click) {
        LinearLayout row = tileRow(card, iconRes, label, value, danger);
        if (click != null) row.setOnClickListener(v -> click.run());
    }

    /** A tile's row: icon, label and what is under it; whatever goes on its right is added after. */
    private LinearLayout tileRow(LinearLayout card, int iconRes, String label, String value, boolean danger) {
        LinearLayout row = Ui.row(app);
        row.setPadding(dp(16), dp(12), dp(16), dp(12));
        row.setMinimumHeight(dp(64));
        if (iconRes != 0) {
            ImageView icon = Ui.icon(app, iconRes, 44, danger ? R.color.danger : R.color.accent);
            icon.setPadding(dp(11), dp(11), dp(11), dp(11));
            icon.setBackground(Ui.round(col(danger ? R.color.dangerSoft : R.color.accentSoft), dp(22)));
            row.addView(icon);
            Ui.margins(icon, app, 0, 0, 16, 0);
        }
        LinearLayout words = Ui.column(app);
        TextView l = Ui.text(app, label, 16.5f, danger ? R.color.danger : R.color.text);
        l.setSingleLine(true);
        l.setEllipsize(TextUtils.TruncateAt.END);
        words.addView(l);
        if (value != null && !value.isEmpty()) {
            TextView v = Ui.text(app, value, 13.5f, R.color.text2);
            v.setMaxLines(2);
            v.setEllipsize(TextUtils.TruncateAt.END);
            v.setLineSpacing(0, 1.15f);
            v.setPadding(0, dp(5), 0, 0);
            words.addView(v);
        }
        row.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
        card.addView(row, Ui.fill());
        return row;
    }

    /** A setting that is on or off: the whole row flips it. */
    void toggle(LinearLayout card, String label, String about, boolean on, java.util.function.Consumer<Boolean> set) {
        LinearLayout row = tileRow(card, 0, label, about, false);
        Toggle t = new Toggle(app, on);
        row.addView(t);
        Ui.margins(t, app, 16, 0, 0, 0);
        row.setOnClickListener(v -> {
            t.flip();
            // Redrawn by what it sets: after the thumb has moved.
            ui.postDelayed(() -> set.accept(t.isOn()), 260);
        });
    }

    /** A choice of a few: its label, the buttons across the tile under it, the chosen one filled. */
    void choice(LinearLayout card, String label, String[] labels, int chosen, java.util.function.IntConsumer pick) {
        LinearLayout tile = Ui.column(app);
        tile.setPadding(dp(16), dp(16), dp(16), dp(16));
        tile.addView(Ui.text(app, label, 16.5f, R.color.text));
        LinearLayout bar = Ui.row(app);
        int n = labels.length;
        TextView[] buttons = new TextView[n];
        GradientDrawable[] shapes = new GradientDrawable[n];
        boolean[] busy = {false};
        for (int i = 0; i < n; i++) {
            int index = i;
            TextView b = Ui.text(app, labels[i], 15, R.color.text);
            b.setGravity(Gravity.CENTER);
            shapes[i] = new GradientDrawable();
            choiceLook(b, shapes[i], i, n, i == chosen ? 1 : 0);
            b.setBackground(Ui.press(app, shapes[i], dp(10)));
            b.setOnClickListener(v -> {
                if (index == chosen || busy[0]) return;
                busy[0] = true;
                v.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK);
                // The fill and the round ends move from the chosen one to this, then it is set.
                android.animation.ValueAnimator a = android.animation.ValueAnimator.ofFloat(0f, 1f);
                a.setDuration(250);
                a.setInterpolator(Motion.EMPHASIZED);
                a.addUpdateListener(x -> {
                    float f = (float) x.getAnimatedValue();
                    choiceLook(buttons[chosen], shapes[chosen], chosen, n, 1 - f);
                    choiceLook(buttons[index], shapes[index], index, n, f);
                });
                a.addListener(new android.animation.AnimatorListenerAdapter() {
                    @Override public void onAnimationEnd(android.animation.Animator x) { pick.accept(index); }
                });
                a.start();
            });
            buttons[i] = b;
            bar.addView(b, new LinearLayout.LayoutParams(0, dp(48), 1));
            if (i > 0) Ui.margins(b, app, 4, 0, 0, 0);
        }
        tile.addView(bar, Ui.fill());
        Ui.margins(bar, app, 0, 14, 0, 0);
        card.addView(tile, Ui.fill());
    }

    /** A choice's button {@code on} of the way to chosen: filled, in the colour on the accent, round at both ends; else only the row's ends round. */
    private void choiceLook(TextView b, GradientDrawable shape, int i, int n, float on) {
        float big = dp(24), small = dp(10);
        float l = i == 0 ? big : small + (big - small) * on, r = i == n - 1 ? big : small + (big - small) * on;
        shape.setCornerRadii(new float[]{l, l, r, r, r, r, l, l});
        shape.setColor(blend(col(R.color.surface2), col(R.color.accent), on));
        b.setTextColor(blend(col(R.color.text), col(R.color.onAccent), on));
        b.setTypeface(on > 0.5f ? Ui.MEDIUM : Typeface.DEFAULT);
    }

    private static int blend(int a, int b, float t) {
        return (int) new android.animation.ArgbEvaluator().evaluate(t, a, b);
    }

    void check(LinearLayout card) {
        LinearLayout last = (LinearLayout) card.getChildAt(card.getChildCount() - 1);
        View tick = Ui.icon(app, R.drawable.ic_check, 19, R.color.accent);
        last.addView(tick);
        Ui.margins(tick, app, 12, 0, 0, 0);
    }

    /** What a list shows when it has nothing to list (or is still being read): a line in the middle of its card. */
    TextView empty(LinearLayout card, String text) {
        TextView none = Ui.text(app, text, 14, R.color.text3);
        none.setGravity(Gravity.CENTER);
        none.setPadding(0, dp(18), 0, dp(18));
        card.addView(none, Ui.fill());
        return none;
    }

    /** The two buttons that end a sheet, side by side below what it holds: the left one (cancel) and the right one (the answer). */
    void actions(Dialog sheet, TextView left, TextView right) {
        LinearLayout row = Ui.row(app);
        row.addView(left, new LinearLayout.LayoutParams(0, dp(42), 1));
        row.addView(right, new LinearLayout.LayoutParams(0, dp(42), 1));
        Ui.margins(right, app, 10, 0, 0, 0);
        content(sheet).addView(row, Ui.fill());
        Ui.margins(row, app, 0, 14, 0, 0);
    }

    TextView button(String label, boolean accent, View.OnClickListener click) {
        TextView b = Ui.text(app, label, 14.5f, accent ? R.color.onAccent : R.color.text);
        b.setTypeface(Ui.MEDIUM);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(16), 0, dp(16), 0);
        b.setMinHeight(dp(38));
        b.setBackground(Ui.press(app, accent ? Ui.round(col(R.color.accent), dp(10))
                : Ui.round(col(R.color.surface), dp(10), dp(1), col(R.color.borderStrong)), dp(10)));
        b.setOnClickListener(click);
        b.setLayoutParams(Ui.wrap());
        return b;
    }

    EditText field(Dialog sheet, String label, String value, String hint) {
        TextView l = Ui.text(app, label, 13, R.color.text2);
        l.setPadding(dp(2), dp(8), 0, dp(6));
        content(sheet).addView(l);
        EditText f = new EditText(app);
        f.setText(value);
        f.setHint(hint);
        f.setSingleLine(true);
        f.setTextSize(14.5f);
        f.setTypeface(Ui.MONO);
        f.setTextColor(col(R.color.text));
        f.setHintTextColor(col(R.color.text3));
        f.setPadding(dp(12), 0, dp(12), 0);
        f.setBackground(Ui.round(col(R.color.surface), dp(10), dp(1), col(R.color.borderStrong)));
        content(sheet).addView(f, new LinearLayout.LayoutParams(-1, dp(44)));
        return f;
    }

    void textSheet(String title, String value, String hint, boolean multiLine,
                           java.util.function.BiConsumer<String, Runnable> save, Runnable back) {
        Dialog sheet = sheet(title, null);
        EditText field = new EditText(app);
        field.setText(value);
        field.setHint(hint);
        field.setTextSize(15);
        field.setTextColor(col(R.color.text));
        field.setHintTextColor(col(R.color.text3));
        field.setGravity(Gravity.TOP | Gravity.START);
        field.setPadding(dp(12), dp(10), dp(12), dp(10));
        field.setBackground(Ui.round(col(R.color.surface), dp(10), dp(1), col(R.color.borderStrong)));
        if (multiLine) {
            field.setMinLines(6);
            field.setMaxLines(14);
            field.setTextSize(14);
        } else {
            field.setSingleLine(true);
            field.setTypeface(Ui.MONO);
        }
        content(sheet).addView(field, Ui.fill());
        TextView cancel = button("取消", false, v -> { sheet.dismiss(); if (back != null) back.run(); });
        TextView ok = button("保存", true, v -> save.accept(field.getText().toString(), () -> {
            sheet.dismiss();
            if (back != null) back.run();
        }));
        ok.setBackground(Ui.press(app, Ui.round(col(R.color.text), dp(10)), dp(10)));
        ok.setTextColor(col(R.color.bg));
        actions(sheet, cancel, ok);
        if (sheet.getWindow() != null) sheet.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        sheet.show();
        field.requestFocus();
    }

    /** A message to read, such as what Claude Code said when something did not work. */
    void textSheetMessage(String title, String text) {
        Dialog sheet = sheet(title, null);
        TextView t = Ui.text(app, text, 13, R.color.text2);
        t.setTypeface(Ui.MONO);
        t.setTextIsSelectable(true);
        content(sheet).addView(t, Ui.fill());
        sheet.show();
    }

    /** A small menu's card, as the claude.ai app's: no outline, a soft shadow; as wide as its rows. */
    LinearLayout menuCard() {
        LinearLayout card = Ui.column(app);
        card.setPadding(0, dp(6), 0, dp(6));
        card.setBackground(Ui.round(col(R.color.surface), dp(16)));
        card.setElevation(dp(10));
        card.setMinimumWidth(dp(150));
        return card;
    }

    /** A small menu's grey title line, above its rows. */
    void menuTitle(LinearLayout card, String title) {
        TextView t = Ui.text(app, title, 14, R.color.text3);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        t.setMaxWidth(dp(200));
        t.setPadding(dp(18), dp(10), dp(18), dp(6));
        card.addView(t, Ui.fill());
    }

    void menuItem(LinearLayout card, android.widget.PopupWindow pop, int icon, String label, String command, Runnable click) {
        LinearLayout row = Ui.row(app);
        row.setPadding(dp(18), 0, dp(20), 0);
        row.setMinimumHeight(dp(48));
        row.setBackground(Ui.press(app, null, 0));
        row.addView(Ui.icon(app, icon, 21, R.color.text2));
        TextView l = Ui.text(app, label, 16, R.color.text);
        l.setPadding(dp(14), 0, command != null ? dp(14) : 0, 0);
        l.setSingleLine(true);
        row.addView(l, new LinearLayout.LayoutParams(0, -2, 1));
        if (command != null) {
            TextView c = Ui.text(app, command, 12.5f, R.color.text3);
            c.setTypeface(Ui.MONO);
            row.addView(c);
        }
        row.setOnClickListener(v -> {
            pop.dismiss();
            click.run();
        });
        card.addView(row, Ui.fill());
    }
}
