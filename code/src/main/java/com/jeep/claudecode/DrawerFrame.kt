package com.jeep.claudecode

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
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
 * Main content with a full-width drawer from the left that a horizontal swipe anywhere opens
 * (right) or closes (left), following the finger. Views that can scroll sideways keep their swipes.
 */
class DrawerFrame(c: Context, private val main: View, private val drawer: View) : FrameLayout(c) {
    fun interface Listener {
        fun opened()
    }

    private val scrim = View(c)
    private val slop = ViewConfiguration.get(c).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var startOffset = 0f
    private var dragging = false
    private var decided = false
    private var offset = 0f // 0 closed … 1 open
    private var velocity: VelocityTracker? = null
    private var anim: ValueAnimator? = null
    private var listener: Listener? = null
    /** Work held back while the drawer moves (the list built again), done once it is still. */
    private var whenStill: Runnable? = null

    init {
        scrim.setBackgroundColor(Ui.color(c, R.color.scrim))
        scrim.alpha = 0f
        scrim.visibility = GONE
        scrim.setOnClickListener { close() }
        addView(main, LayoutParams(-1, -1))
        addView(scrim, LayoutParams(-1, -1))
        addView(drawer, LayoutParams(-2, -1))
        drawer.elevation = Ui.dp(c, 12).toFloat()
        post { apply(offset) }
    }

    fun setListener(l: Listener?) {
        listener = l
    }

    val isOpen: Boolean get() = offset > 0.5f

    fun open() = animateTo(1f)

    /** Runs [r] now, or once the drawer stops moving under the finger or by itself: a frame that long would stop it. */
    fun whenStill(r: Runnable) {
        if (dragging || anim?.isRunning == true) whenStill = r else r.run()
    }

    private fun still() {
        val r = whenStill ?: return
        whenStill = null
        r.run()
    }

    fun close() = animateTo(0f)

    /** Open at once, without moving: a screen built again where it was open. */
    fun openNow() {
        anim?.cancel()
        apply(1f)
        still() // the move cut short does not run what waited for it
    }

    private fun width(): Int = drawer.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels

    override fun onMeasure(w: Int, h: Int) {
        val p = drawer.layoutParams
        val target = MeasureSpec.getSize(w)
        if (p.width != target) p.width = target
        super.onMeasure(w, h)
    }

    private fun apply(value: Float) {
        offset = max(0f, min(1f, value))
        drawer.translationX = -width() * (1 - offset)
        drawer.visibility = if (offset > 0) VISIBLE else INVISIBLE
        scrim.alpha = offset
        scrim.visibility = if (offset > 0) VISIBLE else GONE
    }

    private fun animateTo(target: Float) {
        anim?.cancel()
        val opening = target > offset
        anim = ValueAnimator.ofFloat(offset, target).apply {
            duration = (Motion.PAGE * max(0.35f, abs(target - offset))).toLong()
            interpolator = Motion.EMPHASIZED
            addUpdateListener { a -> apply(a.animatedValue as Float) }
            addListener(object : AnimatorListenerAdapter() {
                // Not when it was cut short by another move or by the finger: that one ends it. A cancel
                // calls onAnimationEnd at once, before the next move is set up or the drag noted, so it is
                // told apart here, not by anim or dragging.
                private var cut = false

                override fun onAnimationCancel(a: Animator) {
                    cut = true
                }

                override fun onAnimationEnd(a: Animator) {
                    if (!cut && !dragging) still()
                }
            })
            start()
        }
        if (opening) listener?.opened()
    }

    /** Whether some view under (x, y) can scroll horizontally in the swipe's direction. */
    private fun scrollsSideways(v: View, x: Float, y: Float, direction: Int): Boolean {
        if (v.visibility != VISIBLE) return false
        if (v is ViewGroup) {
            for (i in v.childCount - 1 downTo 0) {
                val child = v.getChildAt(i)
                val cx = x + v.scrollX - child.left - child.translationX
                val cy = y + v.scrollY - child.top - child.translationY
                if (cx >= 0 && cy >= 0 && cx < child.width && cy < child.height &&
                    scrollsSideways(child, cx, cy, direction)) return true
            }
        }
        return v.canScrollHorizontally(direction)
    }

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean = when (e.actionMasked) {
        MotionEvent.ACTION_DOWN -> {
            down(e)
            if (anim?.isRunning == true) {
                anim!!.cancel()
                dragging = true
                startOffset = offset
                true
            } else {
                false
            }
        }
        MotionEvent.ACTION_MOVE -> if (decided) dragging else decide(e)
        else -> false
    }

    private fun down(e: MotionEvent) {
        downX = e.x
        downY = e.y
        decided = false
        dragging = false
    }

    /** After the first movement past the touch slop: is this a drawer swipe? */
    private fun decide(e: MotionEvent): Boolean {
        val dx = e.x - downX
        val dy = e.y - downY
        if (abs(dx) < slop && abs(dy) < slop) return false
        decided = true
        val sideways = abs(dx) > abs(dy) * 1.3f
        val open = isOpen
        if (!sideways || (!open && dx < 0) || (open && dx > 0)) return false
        if (!open && scrollsSideways(main, downX, downY, -1)) return false
        dragging = true
        startOffset = offset
        downX = e.x
        return true
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!dragging) {
            // Touches nothing else takes (empty space) still start a swipe.
            if (e.actionMasked == MotionEvent.ACTION_DOWN) {
                down(e)
                return true
            }
            if (e.actionMasked != MotionEvent.ACTION_MOVE || decided || !decide(e)) return true
        }
        val tracker = velocity ?: VelocityTracker.obtain().also { velocity = it }
        tracker.addMovement(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_MOVE -> apply(startOffset + (e.x - downX) / width())
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                tracker.computeCurrentVelocity(1000)
                val v = tracker.xVelocity
                tracker.recycle()
                velocity = null
                dragging = false
                val fling = Ui.dp(context, 400).toFloat()
                animateTo(if (v > fling) 1f else if (v < -fling) 0f else if (offset > 0.5f) 1f else 0f)
            }
        }
        return true
    }
}
