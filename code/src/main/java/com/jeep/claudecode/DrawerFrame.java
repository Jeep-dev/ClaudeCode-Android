package com.jeep.claudecode;

import android.animation.ValueAnimator;
import android.content.Context;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.FrameLayout;

/**
 * Main content with a full-width drawer from the left that a horizontal swipe anywhere opens
 * (right) or closes (left), following the finger. Views that can scroll sideways keep their swipes.
 */
final class DrawerFrame extends FrameLayout {
    interface Listener { void opened(); }

    private final View main, scrim, drawer;
    private final int slop;
    private float downX, downY, startOffset;
    private boolean dragging, decided;
    private float offset; // 0 closed … 1 open
    private VelocityTracker velocity;
    private ValueAnimator anim;
    private Listener listener;

    DrawerFrame(Context c, View main, View drawer) {
        super(c);
        this.main = main;
        this.drawer = drawer;
        slop = ViewConfiguration.get(c).getScaledTouchSlop();
        scrim = new View(c);
        scrim.setBackgroundColor(Ui.color(c, R.color.scrim));
        scrim.setAlpha(0);
        scrim.setVisibility(GONE);
        scrim.setOnClickListener(v -> close());
        addView(main, new LayoutParams(-1, -1));
        addView(scrim, new LayoutParams(-1, -1));
        addView(drawer, new LayoutParams(-2, -1));
        drawer.setElevation(Ui.dp(c, 12));
        post(() -> apply(offset));
    }

    void setListener(Listener l) { listener = l; }

    boolean isOpen() { return offset > 0.5f; }

    void open() { animateTo(1); }

    void close() { animateTo(0); }

    /** Open at once, without moving: a screen built again where it was open. */
    void openNow() {
        if (anim != null) anim.cancel();
        apply(1);
    }

    private int width() {
        int w = drawer.getWidth();
        return w > 0 ? w : getResources().getDisplayMetrics().widthPixels;
    }

    @Override protected void onMeasure(int w, int h) {
        ViewGroup.LayoutParams p = drawer.getLayoutParams();
        int target = MeasureSpec.getSize(w);
        if (p.width != target) p.width = target;
        super.onMeasure(w, h);
    }

    private void apply(float value) {
        offset = Math.max(0, Math.min(1, value));
        drawer.setTranslationX(-width() * (1 - offset));
        drawer.setVisibility(offset > 0 ? VISIBLE : INVISIBLE);
        scrim.setAlpha(offset);
        scrim.setVisibility(offset > 0 ? VISIBLE : GONE);
    }

    private void animateTo(float target) {
        if (anim != null) anim.cancel();
        boolean opening = target > offset;
        anim = ValueAnimator.ofFloat(offset, target);
        anim.setDuration((long) (Motion.PAGE * Math.max(0.35f, Math.abs(target - offset))));
        anim.setInterpolator(Motion.EMPHASIZED);
        anim.addUpdateListener(a -> apply((float) a.getAnimatedValue()));
        anim.start();
        if (opening && listener != null) listener.opened();
    }

    /** Whether some view under (x, y) can scroll horizontally in the swipe's direction. */
    private boolean scrollsSideways(View v, float x, float y, int direction) {
        if (v.getVisibility() != VISIBLE) return false;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = g.getChildCount() - 1; i >= 0; i--) {
                View child = g.getChildAt(i);
                float cx = x + g.getScrollX() - child.getLeft() - child.getTranslationX();
                float cy = y + g.getScrollY() - child.getTop() - child.getTranslationY();
                if (cx >= 0 && cy >= 0 && cx < child.getWidth() && cy < child.getHeight()
                        && scrollsSideways(child, cx, cy, direction)) return true;
            }
        }
        return v.canScrollHorizontally(direction);
    }

    @Override public boolean onInterceptTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                down(e);
                if (anim != null && anim.isRunning()) { anim.cancel(); dragging = true; startOffset = offset; return true; }
                return false;
            case MotionEvent.ACTION_MOVE:
                return decided ? dragging : decide(e);
            default:
                return false;
        }
    }

    private void down(MotionEvent e) {
        downX = e.getX();
        downY = e.getY();
        decided = false;
        dragging = false;
    }

    /** After the first movement past the touch slop: is this a drawer swipe? */
    private boolean decide(MotionEvent e) {
        float dx = e.getX() - downX, dy = e.getY() - downY;
        if (Math.abs(dx) < slop && Math.abs(dy) < slop) return false;
        decided = true;
        boolean sideways = Math.abs(dx) > Math.abs(dy) * 1.3f;
        boolean open = isOpen();
        if (!sideways || (!open && dx < 0) || (open && dx > 0)) return false;
        if (!open && scrollsSideways(main, downX, downY, -1)) return false;
        dragging = true;
        startOffset = offset;
        downX = e.getX();
        return true;
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        if (!dragging) {
            // Touches nothing else takes (empty space) still start a swipe.
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN) { down(e); return true; }
            if (e.getActionMasked() != MotionEvent.ACTION_MOVE || decided || !decide(e)) return true;
        }
        if (velocity == null) velocity = VelocityTracker.obtain();
        velocity.addMovement(e);
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_MOVE:
                apply(startOffset + (e.getX() - downX) / width());
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                velocity.computeCurrentVelocity(1000);
                float v = velocity.getXVelocity();
                velocity.recycle();
                velocity = null;
                dragging = false;
                float fling = Ui.dp(getContext(), 400);
                animateTo(v > fling ? 1 : v < -fling ? 0 : offset > 0.5f ? 1 : 0);
                return true;
            }
            default:
                return true;
        }
    }
}
