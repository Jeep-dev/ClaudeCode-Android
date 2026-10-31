package com.jeep.claudecode;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/** A column chart of counts: each bar with its count over it (when there is room), some bars named under. */
final class Bars extends View {
    private final Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG), words = new Paint(Paint.ANTI_ALIAS_FLAG),
            base = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final int[] counts;
    /** A bar's name, or null for none under it. */
    private final String[] names;
    private final int strong;
    private final float dp;

    /** strong: the bar drawn in the accent at full strength (today, now); the others a shade lighter. -1 for none. */
    Bars(Context c, int[] counts, String[] names, int strong) {
        super(c);
        this.counts = counts;
        this.names = names;
        this.strong = strong;
        dp = c.getResources().getDisplayMetrics().density;
        bar.setColor(Ui.color(c, R.color.accent));
        words.setColor(Ui.color(c, R.color.text3));
        words.setTextSize(10.5f * c.getResources().getDisplayMetrics().scaledDensity);
        words.setTextAlign(Paint.Align.CENTER);
        words.setFontFeatureSettings("tnum");
        base.setColor(Ui.color(c, R.color.border));
        base.setStrokeWidth(dp);
    }

    @Override protected void onMeasure(int w, int h) {
        setMeasuredDimension(MeasureSpec.getSize(w), Math.round(150 * dp));
    }

    @Override protected void onDraw(Canvas canvas) {
        int n = counts.length;
        if (n == 0) return;
        int max = 1;
        for (int v : counts) max = Math.max(max, v);
        float text = words.getTextSize(), left = getPaddingLeft(), width = getWidth() - left - getPaddingRight();
        float top = getPaddingTop() + text + 6 * dp, bottom = getHeight() - getPaddingBottom() - text - 8 * dp;
        float slot = width / n, gap = Math.max(1.5f * dp, slot * 0.22f), w = slot - gap;
        // A count over its bar only when the widest one fits in the bar's slot.
        boolean counted = words.measureText(String.valueOf(max)) <= slot + 2 * dp;
        int full = bar.getColor();
        for (int i = 0; i < n; i++) {
            float x = left + i * slot + gap / 2, h = (bottom - top) * counts[i] / max;
            if (counts[i] > 0) {
                h = Math.max(h, 2 * dp);
                rect.set(x, bottom - h, x + w, bottom);
                bar.setColor(strong < 0 || i == strong ? full : Glass.alpha(full, 0.55f));
                float r = Math.min(w / 2, 4 * dp);
                canvas.drawRoundRect(rect, r, r, bar);
                canvas.drawRect(x, bottom - Math.min(h, r), x + w, bottom, bar); // square at its foot
                if (counted) canvas.drawText(String.valueOf(counts[i]), x + w / 2, bottom - h - 4 * dp, words);
            }
            if (names[i] != null) canvas.drawText(names[i], x + w / 2, bottom + text + 5 * dp, words);
        }
        bar.setColor(full);
        canvas.drawLine(left, bottom, left + width, bottom, base);
    }
}
