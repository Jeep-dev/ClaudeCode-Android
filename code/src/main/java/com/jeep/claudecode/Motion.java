package com.jeep.claudecode;

import android.animation.TimeInterpolator;
import android.view.View;
import android.view.animation.PathInterpolator;

/**
 * Moving from page to page as RikkaHub does (its NavDisplay): the new page comes in from the right
 * edge over the old one, which goes half a width left, down to 0.7 and out; back is the same reversed.
 */
final class Motion {
    private Motion() {}

    /** Material 3's emphasized easing: quick out of the start, long settling at the end. */
    static final TimeInterpolator EMPHASIZED = new PathInterpolator(0.2f, 0f, 0f, 1f);
    static final long PAGE = 400;

    private static float width(View v) {
        return v.getWidth() > 0 ? v.getWidth() : v.getResources().getDisplayMetrics().widthPixels;
    }

    /** {@code in} slides over {@code under} (null: nothing under it); then {@code end}. */
    static void push(View in, View under, Runnable end) {
        float w = width(in);
        in.setTranslationX(w);
        in.animate().translationX(0).setInterpolator(EMPHASIZED).setDuration(PAGE).withLayer()
                .withEndAction(end).start();
        if (under != null) {
            under.animate().translationX(-w / 2).scaleX(0.7f).scaleY(0.7f).alpha(0f)
                    .setInterpolator(EMPHASIZED).setDuration(PAGE).withLayer().start();
        }
    }

    /** {@code out} slides away to the right, {@code back} comes up from under it; then {@code end}. */
    static void pop(View back, View out, Runnable end) {
        float w = width(out);
        back.setTranslationX(-w / 2);
        back.setScaleX(0.7f);
        back.setScaleY(0.7f);
        back.setAlpha(0f);
        back.animate().translationX(0).scaleX(1f).scaleY(1f).alpha(1f)
                .setInterpolator(EMPHASIZED).setDuration(PAGE).withLayer().start();
        out.animate().translationX(w).setInterpolator(EMPHASIZED).setDuration(PAGE).withLayer()
                .withEndAction(end).start();
    }

    /** As it was before any of this. */
    static void reset(View v) {
        v.animate().cancel();
        v.setTranslationX(0);
        v.setScaleX(1f);
        v.setScaleY(1f);
        v.setAlpha(1f);
    }
}
