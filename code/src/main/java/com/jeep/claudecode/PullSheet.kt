package com.jeep.claudecode

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * A sheet that rests at two heights, as Telegram's attach sheet: half the screen when it opens, the whole of it when
 * pulled up (or when what is in it is scrolled up while it is half). Pulled down from the whole it rests at half; from
 * half it closes, as does a tap above it. The screen behind is dimmed as much as the sheet is in view.
 */
class PullSheet(c: Context) : FrameLayout(c) {
    /** The sheet itself: as tall as this, moved down by its translation. */
    lateinit var sheet: View
    /** Asked to close (moved out of sight first): the dialog goes. */
    var onClose: Runnable? = null
    /** Where it is: 1 at the whole screen, 0 at half, below 0 on its way out (-1 out of sight). */
    var onMoved: ((Float) -> Unit)? = null

    private val slop = ViewConfiguration.get(c).scaledTouchSlop
    private val fast = c.resources.displayMetrics.density * 900
    private var downX = 0f
    private var downY = 0f
    private var from = 0f
    private var dragging = false
    private var outside = false
    private var velocity: VelocityTracker? = null
    private var move: ValueAnimator? = null

    /** How far down the sheet rests at half. */
    private fun half() = height * HIDDEN

    val expanded: Boolean get() = sheet.translationY < 1f

    private fun place(y: Float) {
        sheet.translationY = y
        val h = half()
        val at = if (y <= h) 1 - y / max(1f, h) else -(y - h) / max(1f, height - h)
        setBackgroundColor(Color.argb((DIM * 255 * min(1f, 1 + at)).toInt().coerceIn(0, 255), 0, 0, 0))
        onMoved?.invoke(at)
    }

    /** Up from the bottom edge to half. */
    fun open() {
        post {
            place(height.toFloat())
            go(half(), Motion.PAGE, null)
        }
    }

    fun expand() = go(0f, 300, null)

    fun collapse() = go(half(), 300, null)

    /** Down out of sight, then [end]. */
    fun close(end: Runnable?) = go(height.toFloat(), 250, end)

    private fun go(to: Float, ms: Long, end: Runnable?) {
        move?.cancel()
        val a = ValueAnimator.ofFloat(sheet.translationY, to)
        a.duration = ms
        a.interpolator = Motion.EMPHASIZED
        a.addUpdateListener { place(it.animatedValue as Float) }
        if (end != null) a.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(x: android.animation.Animator) = end.run()
        })
        move = a
        a.start()
    }

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                begin(e)
                // Above the sheet: a tap there closes it.
                outside = e.y < sheet.translationY
                return outside
            }
            MotionEvent.ACTION_MOVE -> {
                track(e, false)
                val dy = e.rawY - downY
                val dx = abs(e.rawX - downX)
                if (abs(dy) <= slop || abs(dy) <= dx) return false
                val y = sheet.translationY
                // Up while not whole: the sheet rises, not what is in it. Down when what is under the finger is at its top: the sheet comes down.
                if ((dy < 0 && y > 0.5f) || (dy > 0 && !scrollsUp(sheet, e.x, e.y - y))) {
                    dragging = true
                    downY = e.rawY
                    from = y
                    return true
                }
                return false
            }
            else -> return false
        }
    }

    private fun begin(e: MotionEvent) {
        move?.cancel()
        downX = e.rawX
        downY = e.rawY
        from = sheet.translationY
        dragging = false
        track(e, true)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                begin(e)
                outside = e.y < sheet.translationY
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                track(e, false)
                if (!dragging && abs(e.rawY - downY) > slop) {
                    dragging = true
                    downY = e.rawY
                }
                if (dragging) place((from + e.rawY - downY).coerceIn(0f, height.toFloat()))
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                track(e, false)
                var speed = 0f
                velocity?.let {
                    it.computeCurrentVelocity(1000)
                    speed = it.yVelocity
                    it.recycle()
                    velocity = null
                }
                if (!dragging) {
                    if (outside && e.actionMasked == MotionEvent.ACTION_UP) onClose?.run()
                    return true
                }
                dragging = false
                settle(speed)
                return true
            }
            else -> return true
        }
    }

    /** Let go: to the whole, to half or out, by how fast and where it was going. */
    private fun settle(speed: Float) {
        val y = sheet.translationY
        val h = half()
        when {
            speed < -fast -> expand()
            speed > fast -> if (y < h - 1) collapse() else onClose?.run()
            y < h / 2 -> expand()
            y < h + (height - h) * 0.35f -> collapse()
            else -> onClose?.run()
        }
    }

    private fun track(e: MotionEvent, fresh: Boolean) {
        if (fresh || velocity == null) {
            velocity?.recycle()
            velocity = VelocityTracker.obtain()
        }
        val raw = MotionEvent.obtain(e)
        raw.setLocation(e.rawX, e.rawY)
        velocity!!.addMovement(raw)
        raw.recycle()
    }

    companion object {
        /** At half, this much of the sheet is below the screen's edge. */
        const val HIDDEN = 0.4f
        const val DIM = 0.34f

        /** Whether some view under (x, y) can still scroll up: the drag is its scroll, not the sheet's. */
        fun scrollsUp(v: View, x: Float, y: Float): Boolean {
            if (v.visibility != VISIBLE) return false
            if (v is ViewGroup) {
                for (i in v.childCount - 1 downTo 0) {
                    val child = v.getChildAt(i)
                    val cx = x + v.scrollX - child.left - child.translationX
                    val cy = y + v.scrollY - child.top - child.translationY
                    if (cx >= 0 && cy >= 0 && cx < child.width && cy < child.height && scrollsUp(child, cx, cy)) return true
                }
            }
            return v.canScrollVertically(-1)
        }
    }
}
