package com.jeep.claudecode;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Small helpers for building the interface in code. */
final class Ui {
    private Ui() {}

    static final Typeface SERIF = Typeface.create("serif", Typeface.NORMAL);
    static final Typeface MONO = Typeface.MONOSPACE;
    static final Typeface MEDIUM = Typeface.create("sans-serif-medium", Typeface.NORMAL);

    static int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics()));
    }

    static int color(Context c, int res) { return Themes.color(c, res); }

    static GradientDrawable round(int fill, float radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(radius);
        return d;
    }

    static GradientDrawable round(int fill, float radius, int strokeWidth, int stroke) {
        GradientDrawable d = round(fill, radius);
        d.setStroke(strokeWidth, stroke);
        return d;
    }

    /** A pressed-state ripple over a shape (or over the view's bounds when content is null). */
    static Drawable press(Context c, Drawable content, float radius) {
        GradientDrawable mask = round(0xFF000000, radius);
        return new RippleDrawable(ColorStateList.valueOf(color(c, R.color.active)), content, mask);
    }

    static TextView text(Context c, CharSequence s, float sp, int colorRes) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color(c, colorRes));
        t.setIncludeFontPadding(false);
        return t;
    }

    static ImageView icon(Context c, int drawable, int sizeDp, int colorRes) {
        ImageView v = new ImageView(c);
        v.setImageResource(drawable);
        v.setImageTintList(ColorStateList.valueOf(color(c, colorRes)));
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(c, sizeDp), dp(c, sizeDp)));
        return v;
    }

    /** A square tap target with an icon in the middle. */
    static ImageView iconButton(Context c, int drawable, int sizeDp, int iconDp, int colorRes) {
        ImageView v = icon(c, drawable, sizeDp, colorRes);
        int pad = dp(c, (sizeDp - iconDp) / 2f);
        v.setPadding(pad, pad, pad, pad);
        v.setBackground(press(c, null, dp(c, 12)));
        v.setClickable(true);
        return v;
    }

    static LinearLayout row(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    static LinearLayout column(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    static LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    static LinearLayout.LayoutParams fill() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    static LinearLayout.LayoutParams weight() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
    }

    static <T extends View> T margins(T v, Context c, int l, int t, int r, int b) {
        ViewGroup.LayoutParams p = v.getLayoutParams();
        ViewGroup.MarginLayoutParams m = p instanceof ViewGroup.MarginLayoutParams
                ? (ViewGroup.MarginLayoutParams) p : new LinearLayout.LayoutParams(p == null ? fill() : p);
        m.setMargins(dp(c, l), dp(c, t), dp(c, r), dp(c, b));
        v.setLayoutParams(m);
        return v;
    }

    static String base(String path) {
        if (path == null || path.isEmpty()) return "";
        String p = path.replaceAll("/+$", "");
        int i = p.lastIndexOf('/');
        return i < 0 ? p : p.substring(i + 1).isEmpty() ? "/" : p.substring(i + 1);
    }

    static String size(long n) {
        if (n >= 1 << 20) return String.format(java.util.Locale.ROOT, "%.1f MB", n / 1048576.0);
        if (n >= 1024) return (n / 1024) + " KB";
        return n + " B";
    }
}
