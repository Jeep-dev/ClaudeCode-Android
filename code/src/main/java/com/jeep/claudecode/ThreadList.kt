package com.jeep.claudecode

import android.content.Context
import android.view.MotionEvent
import android.view.View
import androidx.core.view.NestedScrollingParent3
import androidx.core.view.NestedScrollingParentHelper
import androidx.core.view.ViewCompat
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.max
import kotlin.math.min

/**
 * The conversation: an opened row's boxes that scroll (thinking, a command, a result) hand on to it
 * what they do not use. A box with its edge out of sight is brought in by the list first, then
 * scrolls itself; a drag that goes on past a box's end becomes the list's own drag, and a fling
 * the box cannot use the list's own fling, so the list moves (and is stopped) as when dragged.
 */
class ThreadList(c: Context) : RecyclerView(c), NestedScrollingParent3 {
    private val parent = NestedScrollingParentHelper(this)
    private val at = IntArray(2)
    private val listAt = IntArray(2)
    /** A finger drags a box: the list leaves the drag to it. */
    private var boxDragged = false
    /** The box dragged is at its end: the list takes the drag at the finger's next move. */
    private var take = false

    /** What the list follows by itself: the end, when the reader is there, and the row opened last. */
    interface Follow {
        fun atEnd(): Boolean
        fun opened(): View?
        /** Whether a row is growing open or shrinking shut just now. */
        fun moving(row: View): Boolean
    }

    var follow: Follow? = null

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        val f = follow
        if (f != null && !byHand()) follow(f)
    }

    /**
     * After each layout, before the frame is drawn. Rows are laid out from the top, so a row
     * growing (opened, a picture or its text coming) pushes what is below it down out of sight.
     * At the end, the end is kept in sight; elsewhere, the row opened last, its head never taken
     * above the top, nor while it grows open at the end. So an opened row grows upwards as far as
     * it must from its first frame, not below the screen and back up after.
     */
    private fun follow(f: Follow) {
        val row = f.opened()?.takeIf { it.parent === this }
        repeat(20) {
            val end = f.atEnd()
            var dy = if (end) below(last()) else if (row != null) below(row) else 0
            if (row != null && (!end || f.moving(row))) dy = min(dy, row.top - paddingTop)
            if (dy <= 0 || !canScrollVertically(1)) return
            scrollBy(0, dy)
        }
    }

    /** The last row, if it is laid out. */
    private fun last(): View? {
        val a = adapter
        val lm = layoutManager
        if (a == null || lm == null || a.itemCount == 0) return null
        return lm.findViewByPosition(a.itemCount - 1)
    }

    /** How far a row's bottom is below the list's end (a screen, for a row not laid out). */
    private fun below(v: View?): Int {
        if (v == null) return height
        return layoutManager!!.getDecoratedBottom(v) + (v.layoutParams as LayoutParams).bottomMargin -
            (height - paddingBottom)
    }

    /** Whether the list moved last by a finger, on it or on a box in it. */
    fun byHand(): Boolean = scrollState != SCROLL_STATE_IDLE || boxDragged

    override fun dispatchTouchEvent(e: MotionEvent): Boolean {
        val action = e.actionMasked
        if (action == MotionEvent.ACTION_MOVE && take) {
            // The box asked to keep the list off; now the list looks at the move, and takes it
            // (from where the finger is, the box getting a cancel).
            take = false
            boxDragged = false
            requestDisallowInterceptTouchEvent(false)
        }
        val handled = super.dispatchTouchEvent(e)
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            // Whatever ended the touch (a selection's handle keeps the box from seeing it, say).
            boxDragged = false
            take = false
        }
        return handled
    }

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean = !boxDragged && super.onInterceptTouchEvent(e)

    /** Only a finger's scrolling: a box's fling stays in the box, what it cannot use the list flings itself. */
    override fun onStartNestedScroll(child: View, target: View, axes: Int, type: Int): Boolean =
        type == ViewCompat.TYPE_TOUCH && (axes and ViewCompat.SCROLL_AXIS_VERTICAL) != 0

    override fun onNestedScrollAccepted(child: View, target: View, axes: Int, type: Int) {
        parent.onNestedScrollAccepted(child, target, axes, type)
        boxDragged = true
    }

    override fun onStopNestedScroll(target: View, type: Int) {
        parent.onStopNestedScroll(target, type)
        boxDragged = false
    }

    override fun getNestedScrollAxes(): Int = parent.nestedScrollAxes

    override fun onNestedPreScroll(target: View, dx: Int, dy: Int, consumed: IntArray, type: Int) {
        if (dy == 0 || take) return
        // Brought in only as far as its edge: the box stays on screen, its row with it.
        val out = out(target, dy)
        if (out > 0) consumed[1] += scroll(target, if (dy > 0) min(dy, out) else max(dy, -out))
    }

    override fun onNestedScroll(target: View, dxConsumed: Int, dyConsumed: Int, dxUnconsumed: Int,
                                dyUnconsumed: Int, type: Int, consumed: IntArray) {
        if (dyUnconsumed == 0 || !canScrollVertically(if (dyUnconsumed > 0) 1 else -1)) return
        take = true
        consumed[1] += dyUnconsumed // no glow on the box: the list goes on
    }

    override fun onNestedScroll(target: View, dxConsumed: Int, dyConsumed: Int, dxUnconsumed: Int,
                                dyUnconsumed: Int, type: Int) {
        onNestedScroll(target, dxConsumed, dyConsumed, dxUnconsumed, dyUnconsumed, type, IntArray(2))
    }

    /** How far the box's edge that way (dy) is out of the list's sight. */
    private fun out(target: View, dy: Int): Int {
        target.getLocationInWindow(at)
        getLocationInWindow(listAt)
        val top = at[1] - listAt[1]
        return if (dy > 0) top + target.height - (height - paddingBottom) else paddingTop - top
    }

    /** Scrolls the list by dy, as far as it goes: how far the box moved with it. */
    private fun scroll(target: View, dy: Int): Int {
        if (!canScrollVertically(if (dy > 0) 1 else -1)) return 0
        target.getLocationInWindow(at)
        val before = at[1]
        scrollBy(0, dy)
        target.getLocationInWindow(at)
        return before - at[1]
    }

    // The older parent interfaces, for children that call them.
    override fun onStartNestedScroll(child: View, target: View, axes: Int): Boolean =
        onStartNestedScroll(child, target, axes, ViewCompat.TYPE_TOUCH)

    override fun onNestedScrollAccepted(child: View, target: View, axes: Int) =
        onNestedScrollAccepted(child, target, axes, ViewCompat.TYPE_TOUCH)

    override fun onStopNestedScroll(target: View) = onStopNestedScroll(target, ViewCompat.TYPE_TOUCH)

    override fun onNestedPreScroll(target: View, dx: Int, dy: Int, consumed: IntArray) =
        onNestedPreScroll(target, dx, dy, consumed, ViewCompat.TYPE_TOUCH)

    override fun onNestedScroll(target: View, dxConsumed: Int, dyConsumed: Int, dxUnconsumed: Int, dyUnconsumed: Int) =
        onNestedScroll(target, dxConsumed, dyConsumed, dxUnconsumed, dyUnconsumed, ViewCompat.TYPE_TOUCH)

    /** A fling the box cannot use that way (at its end, or its edge out of sight) is the list's. */
    override fun onNestedPreFling(target: View, velocityX: Float, velocityY: Float): Boolean {
        val way = if (velocityY > 0) 1 else -1
        if (target.canScrollVertically(way) && out(target, way) <= 0) return false
        return fling(0, velocityY.toInt())
    }

    override fun onNestedFling(target: View, velocityX: Float, velocityY: Float, consumed: Boolean): Boolean = false
}
