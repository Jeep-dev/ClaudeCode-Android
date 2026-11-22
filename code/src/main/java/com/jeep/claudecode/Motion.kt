package com.jeep.claudecode

import android.animation.TimeInterpolator
import android.view.View
import android.view.animation.PathInterpolator

/**
 * Moving from page to page as RikkaHub does (its NavDisplay): the new page comes in from the right
 * edge over the old one, which goes half a width left, down to 0.7 and out; back is the same reversed.
 */
object Motion {
    /** Material 3's emphasized easing: quick out of the start, long settling at the end. */
    @JvmField val EMPHASIZED: TimeInterpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
    const val PAGE = 400L

    private fun width(v: View): Float =
        (if (v.width > 0) v.width else v.resources.displayMetrics.widthPixels).toFloat()

    /** [view] slides over [under] (null: nothing under it); then [end]. */
    @JvmStatic
    fun push(view: View, under: View?, end: Runnable?) {
        val w = width(view)
        view.translationX = w
        view.animate().translationX(0f).setInterpolator(EMPHASIZED).setDuration(PAGE).withLayer()
            .withEndAction(end).start()
        under?.animate()?.translationX(-w / 2)?.scaleX(0.7f)?.scaleY(0.7f)?.alpha(0f)
            ?.setInterpolator(EMPHASIZED)?.setDuration(PAGE)?.withLayer()?.start()
    }

    /** [out] slides away to the right, [back] comes up from under it; then [end]. */
    @JvmStatic
    fun pop(back: View, out: View, end: Runnable?) {
        val w = width(out)
        back.translationX = -w / 2
        back.scaleX = 0.7f
        back.scaleY = 0.7f
        back.alpha = 0f
        back.animate().translationX(0f).scaleX(1f).scaleY(1f).alpha(1f)
            .setInterpolator(EMPHASIZED).setDuration(PAGE).withLayer().start()
        out.animate().translationX(w).setInterpolator(EMPHASIZED).setDuration(PAGE).withLayer()
            .withEndAction(end).start()
    }

    /** As it was before any of this. */
    @JvmStatic
    fun reset(v: View) {
        v.animate().cancel()
        v.translationX = 0f
        v.scaleX = 1f
        v.scaleY = 1f
        v.alpha = 1f
    }
}
