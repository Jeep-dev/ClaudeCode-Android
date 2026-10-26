package com.jeep.claudecode;

import android.content.Context;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.core.view.NestedScrollingParent3;
import androidx.core.view.NestedScrollingParentHelper;
import androidx.core.view.ViewCompat;
import androidx.recyclerview.widget.RecyclerView;

/**
 * The conversation: an opened row's boxes that scroll (thinking, a command, a result) hand on to it
 * what they do not use. A box with its edge out of sight is brought in by the list first, then
 * scrolls itself; a drag that goes on past a box's end becomes the list's own drag, and a fling
 * the box cannot use the list's own fling, so the list moves (and is stopped) as when dragged.
 */
final class ThreadList extends RecyclerView implements NestedScrollingParent3 {
    private final NestedScrollingParentHelper parent = new NestedScrollingParentHelper(this);
    private final int[] at = new int[2], listAt = new int[2];
    /** A finger drags a box: the list leaves the drag to it. */
    private boolean boxDragged;
    /** The box dragged is at its end: the list takes the drag at the finger's next move. */
    private boolean take;

    /** Whether the reader is at the end: the list then keeps it in sight as rows change. */
    interface AtEnd { boolean at(); }
    AtEnd atEnd;

    ThreadList(Context c) {
        super(c);
    }

    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        // Rows are laid out from the top: one growing at the end (opened, its text coming) pushes
        // the end down out of sight. At the end, the list goes after it before the frame is drawn,
        // so the row grows upwards, frame by frame, instead of below the box and back up after.
        if (atEnd != null && atEnd.at() && !byHand()) keepEnd();
    }

    /** Scrolls until the last row's bottom is at the list's end, if it is below it. */
    private void keepEnd() {
        Adapter<?> a = getAdapter();
        LayoutManager lm = getLayoutManager();
        if (a == null || lm == null) return;
        int last = a.getItemCount() - 1;
        for (int i = 0; i < 20 && last >= 0; i++) {
            View v = lm.findViewByPosition(last);
            int below;
            if (v != null) {
                below = lm.getDecoratedBottom(v) + ((LayoutParams) v.getLayoutParams()).bottomMargin
                        - (getHeight() - getPaddingBottom());
                if (below <= 0) return;
            } else {
                if (!canScrollVertically(1)) return;
                below = getHeight(); // not laid out: a screen at a time until it is
            }
            scrollBy(0, below);
        }
    }

    /** Whether the list moved last by a finger, on it or on a box in it. */
    boolean byHand() {
        return getScrollState() != SCROLL_STATE_IDLE || boxDragged;
    }

    @Override public boolean dispatchTouchEvent(MotionEvent e) {
        int action = e.getActionMasked();
        if (action == MotionEvent.ACTION_MOVE && take) {
            // The box asked to keep the list off; now the list looks at the move, and takes it
            // (from where the finger is, the box getting a cancel).
            take = false;
            boxDragged = false;
            requestDisallowInterceptTouchEvent(false);
        }
        boolean handled = super.dispatchTouchEvent(e);
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            // Whatever ended the touch (a selection's handle keeps the box from seeing it, say).
            boxDragged = false;
            take = false;
        }
        return handled;
    }

    @Override public boolean onInterceptTouchEvent(MotionEvent e) {
        return !boxDragged && super.onInterceptTouchEvent(e);
    }

    /** Only a finger's scrolling: a box's fling stays in the box, what it cannot use the list flings itself. */
    @Override public boolean onStartNestedScroll(@NonNull View child, @NonNull View target, int axes, int type) {
        return type == ViewCompat.TYPE_TOUCH && (axes & ViewCompat.SCROLL_AXIS_VERTICAL) != 0;
    }

    @Override public void onNestedScrollAccepted(@NonNull View child, @NonNull View target, int axes, int type) {
        parent.onNestedScrollAccepted(child, target, axes, type);
        boxDragged = true;
    }

    @Override public void onStopNestedScroll(@NonNull View target, int type) {
        parent.onStopNestedScroll(target, type);
        boxDragged = false;
    }

    @Override public int getNestedScrollAxes() {
        return parent.getNestedScrollAxes();
    }

    @Override public void onNestedPreScroll(@NonNull View target, int dx, int dy, @NonNull int[] consumed, int type) {
        if (dy == 0 || take) return;
        // Brought in only as far as its edge: the box stays on screen, its row with it.
        int out = out(target, dy);
        if (out > 0) consumed[1] += scroll(target, dy > 0 ? Math.min(dy, out) : Math.max(dy, -out));
    }

    @Override public void onNestedScroll(@NonNull View target, int dxConsumed, int dyConsumed, int dxUnconsumed,
                                         int dyUnconsumed, int type, @NonNull int[] consumed) {
        if (dyUnconsumed == 0 || !canScrollVertically(dyUnconsumed > 0 ? 1 : -1)) return;
        take = true;
        consumed[1] += dyUnconsumed; // no glow on the box: the list goes on
    }

    @Override public void onNestedScroll(@NonNull View target, int dxConsumed, int dyConsumed, int dxUnconsumed,
                                         int dyUnconsumed, int type) {
        onNestedScroll(target, dxConsumed, dyConsumed, dxUnconsumed, dyUnconsumed, type, new int[2]);
    }

    /** How far the box's edge that way (dy) is out of the list's sight. */
    private int out(View target, int dy) {
        target.getLocationInWindow(at);
        getLocationInWindow(listAt);
        int top = at[1] - listAt[1];
        return dy > 0 ? top + target.getHeight() - (getHeight() - getPaddingBottom()) : getPaddingTop() - top;
    }

    /** Scrolls the list by dy, as far as it goes: how far the box moved with it. */
    private int scroll(View target, int dy) {
        if (!canScrollVertically(dy > 0 ? 1 : -1)) return 0;
        target.getLocationInWindow(at);
        int before = at[1];
        scrollBy(0, dy);
        target.getLocationInWindow(at);
        return before - at[1];
    }

    // The older parent interfaces, for children that call them.
    @Override public boolean onStartNestedScroll(@NonNull View child, @NonNull View target, int axes) {
        return onStartNestedScroll(child, target, axes, ViewCompat.TYPE_TOUCH);
    }

    @Override public void onNestedScrollAccepted(@NonNull View child, @NonNull View target, int axes) {
        onNestedScrollAccepted(child, target, axes, ViewCompat.TYPE_TOUCH);
    }

    @Override public void onStopNestedScroll(@NonNull View target) {
        onStopNestedScroll(target, ViewCompat.TYPE_TOUCH);
    }

    @Override public void onNestedPreScroll(@NonNull View target, int dx, int dy, @NonNull int[] consumed) {
        onNestedPreScroll(target, dx, dy, consumed, ViewCompat.TYPE_TOUCH);
    }

    @Override public void onNestedScroll(@NonNull View target, int dxConsumed, int dyConsumed, int dxUnconsumed, int dyUnconsumed) {
        onNestedScroll(target, dxConsumed, dyConsumed, dxUnconsumed, dyUnconsumed, ViewCompat.TYPE_TOUCH);
    }

    /** A fling the box cannot use that way (at its end, or its edge out of sight) is the list's. */
    @Override public boolean onNestedPreFling(@NonNull View target, float velocityX, float velocityY) {
        int way = velocityY > 0 ? 1 : -1;
        if (target.canScrollVertically(way) && out(target, way) <= 0) return false;
        return fling(0, (int) velocityY);
    }

    @Override public boolean onNestedFling(@NonNull View target, float velocityX, float velocityY, boolean consumed) {
        return false;
    }
}
