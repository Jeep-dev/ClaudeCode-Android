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
 * what they do not use, so a drag or a fling over them goes on through the list. A box with its
 * edge out of sight that way is brought in by the list first, then scrolls itself.
 */
final class ThreadList extends RecyclerView implements NestedScrollingParent3 {
    private final NestedScrollingParentHelper parent = new NestedScrollingParentHelper(this);
    private final int[] at = new int[2], listAt = new int[2];
    /** A finger drags a box: the list leaves the drag to it. */
    private boolean boxDragged;
    /** A box's fling goes on into the list, until a finger is put down. */
    private boolean boxFlung;

    ThreadList(Context c) {
        super(c);
    }

    /** Whether the list moved last by a finger, on it or on a box in it. */
    boolean byHand() {
        return getScrollState() != SCROLL_STATE_IDLE || boxDragged;
    }

    @Override public boolean dispatchTouchEvent(MotionEvent e) {
        if (e.getActionMasked() == MotionEvent.ACTION_DOWN) boxFlung = false;
        return super.dispatchTouchEvent(e);
    }

    @Override public boolean onInterceptTouchEvent(MotionEvent e) {
        return !boxDragged && super.onInterceptTouchEvent(e);
    }

    @Override public boolean onStartNestedScroll(@NonNull View child, @NonNull View target, int axes, int type) {
        return (axes & ViewCompat.SCROLL_AXIS_VERTICAL) != 0;
    }

    @Override public void onNestedScrollAccepted(@NonNull View child, @NonNull View target, int axes, int type) {
        parent.onNestedScrollAccepted(child, target, axes, type);
        if (type == ViewCompat.TYPE_TOUCH) {
            boxDragged = true;
            stopScroll();
        } else {
            boxFlung = true;
        }
    }

    @Override public void onStopNestedScroll(@NonNull View target, int type) {
        parent.onStopNestedScroll(target, type);
        if (type == ViewCompat.TYPE_TOUCH) boxDragged = false;
        else boxFlung = false;
    }

    @Override public int getNestedScrollAxes() {
        return parent.getNestedScrollAxes();
    }

    @Override public void onNestedPreScroll(@NonNull View target, int dx, int dy, @NonNull int[] consumed, int type) {
        if (dy == 0 || !moves(type)) return;
        // How far the box's edge that way is out of sight.
        target.getLocationInWindow(at);
        getLocationInWindow(listAt);
        int top = at[1] - listAt[1];
        int out = dy > 0 ? top + target.getHeight() - (getHeight() - getPaddingBottom()) : getPaddingTop() - top;
        if (out <= 0) return;
        consumed[1] += scroll(target, dy > 0 ? Math.min(dy, out) : Math.max(dy, -out));
    }

    @Override public void onNestedScroll(@NonNull View target, int dxConsumed, int dyConsumed, int dxUnconsumed,
                                         int dyUnconsumed, int type, @NonNull int[] consumed) {
        if (dyUnconsumed != 0 && moves(type)) consumed[1] += scroll(target, dyUnconsumed);
    }

    @Override public void onNestedScroll(@NonNull View target, int dxConsumed, int dyConsumed, int dxUnconsumed,
                                         int dyUnconsumed, int type) {
        onNestedScroll(target, dxConsumed, dyConsumed, dxUnconsumed, dyUnconsumed, type, new int[2]);
    }

    /** A box's fling stops at its end once a finger is down again. */
    private boolean moves(int type) {
        return type == ViewCompat.TYPE_TOUCH || boxFlung;
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

    @Override public boolean onNestedPreFling(@NonNull View target, float velocityX, float velocityY) {
        return false;
    }

    @Override public boolean onNestedFling(@NonNull View target, float velocityX, float velocityY, boolean consumed) {
        return false;
    }
}
