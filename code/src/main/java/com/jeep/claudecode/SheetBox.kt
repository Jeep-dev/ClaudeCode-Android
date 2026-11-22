package com.jeep.claudecode

import android.content.Context
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.LinearLayout
import kotlin.math.abs
import kotlin.math.max

/**
 * A sheet's box that can be pulled down to close, as Material 3's bottom sheet: a downward drag
 * moves it with the finger unless what is under the finger can still scroll up; let go far enough
 * down (a third of it) or quickly, it closes, else it goes back up.
 */
class SheetBox(c: Context) : LinearLayout(c) {
    /** Closes the sheet (moving the rest of the way down). */
    var close: Runnable? = null
    private val slop = ViewConfiguration.get(c).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private var velocity: VelocityTracker? = null

    init {
        orientation = VERTICAL
    }

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.rawX
                downY = e.rawY
                dragging = false
                track(e, true)
                return false
            }
            MotionEvent.ACTION_MOVE -> {
                track(e, false)
                val dy = e.rawY - downY
                val dx = abs(e.rawX - downX)
                if (dy > slop && dy > dx && !scrollsUp(this, e.x, e.y)) {
                    dragging = true
                    downY = e.rawY - translationY
                    return true
                }
                return false
            }
            else -> return false
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.rawX
                downY = e.rawY
                track(e, true)
                return true // a drag may start on the box itself (its handle, its title)
            }
            MotionEvent.ACTION_MOVE -> {
                track(e, false)
                var dy = e.rawY - downY
                if (!dragging && dy > slop) {
                    dragging = true
                    downY = e.rawY
                    dy = 0f
                }
                if (dragging) translationY = max(0f, dy)
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
                if (!dragging) return true
                dragging = false
                val fast = resources.displayMetrics.density * 900
                if (translationY > height / 3f || speed > fast) {
                    close?.run()
                } else {
                    animate().translationY(0f).setDuration(250).setInterpolator(Motion.EMPHASIZED).start()
                }
                return true
            }
            else -> return true
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

    private companion object {
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
