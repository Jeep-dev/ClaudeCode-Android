package com.jeep.claudecode;

import android.content.Context;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.LinearLayout;

/**
 * A sheet's box that can be pulled down to close, as Material 3's bottom sheet: a downward drag
 * moves it with the finger unless what is under the finger can still scroll up; let go far enough
 * down (a third of it) or quickly, it closes, else it goes back up.
 */
final class SheetBox extends LinearLayout {
    /** Closes the sheet (moving the rest of the way down). */
    Runnable close;
    private final int slop;
    private float downX, downY;
    private boolean dragging;
    private VelocityTracker velocity;

    SheetBox(Context c) {
        super(c);
        setOrientation(VERTICAL);
        slop = ViewConfiguration.get(c).getScaledTouchSlop();
    }

    @Override public boolean onInterceptTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getRawX();
                downY = e.getRawY();
                dragging = false;
                track(e, true);
                return false;
            case MotionEvent.ACTION_MOVE:
                track(e, false);
                float dy = e.getRawY() - downY, dx = Math.abs(e.getRawX() - downX);
                if (dy > slop && dy > dx && !scrollsUp(this, e.getX(), e.getY())) {
                    dragging = true;
                    downY = e.getRawY() - getTranslationY();
                    return true;
                }
                return false;
            default:
                return false;
        }
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getRawX();
                downY = e.getRawY();
                track(e, true);
                return true; // a drag may start on the box itself (its handle, its title)
            case MotionEvent.ACTION_MOVE:
                track(e, false);
                float dy = e.getRawY() - downY;
                if (!dragging && dy > slop) {
                    dragging = true;
                    downY = e.getRawY();
                    dy = 0;
                }
                if (dragging) setTranslationY(Math.max(0, dy));
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                track(e, false);
                float speed = 0;
                if (velocity != null) {
                    velocity.computeCurrentVelocity(1000);
                    speed = velocity.getYVelocity();
                    velocity.recycle();
                    velocity = null;
                }
                if (!dragging) return true;
                dragging = false;
                float fast = getResources().getDisplayMetrics().density * 900;
                if (getTranslationY() > getHeight() / 3f || speed > fast) {
                    if (close != null) close.run();
                } else {
                    animate().translationY(0).setDuration(250).setInterpolator(Motion.EMPHASIZED).start();
                }
                return true;
            default:
                return true;
        }
    }

    private void track(MotionEvent e, boolean fresh) {
        if (fresh || velocity == null) {
            if (velocity != null) velocity.recycle();
            velocity = VelocityTracker.obtain();
        }
        MotionEvent raw = MotionEvent.obtain(e);
        raw.setLocation(e.getRawX(), e.getRawY());
        velocity.addMovement(raw);
        raw.recycle();
    }

    /** Whether some view under (x, y) can still scroll up: the drag is its scroll, not the sheet's. */
    private static boolean scrollsUp(View v, float x, float y) {
        if (v.getVisibility() != VISIBLE) return false;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = g.getChildCount() - 1; i >= 0; i--) {
                View child = g.getChildAt(i);
                float cx = x + g.getScrollX() - child.getLeft() - child.getTranslationX();
                float cy = y + g.getScrollY() - child.getTop() - child.getTranslationY();
                if (cx >= 0 && cy >= 0 && cx < child.getWidth() && cy < child.getHeight()
                        && scrollsUp(child, cx, cy)) return true;
            }
        }
        return v.canScrollVertically(-1);
    }
}
