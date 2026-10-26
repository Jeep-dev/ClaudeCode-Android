package com.jeep.claudecode;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/** A Material 3 switch: off, an outlined track with a small thumb on the left; on, a filled track with a big thumb on the right. */
final class Toggle extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private boolean on;
    /** 0 off, 1 on; between while it moves. */
    private float at;
    /** 0 let go, 1 held: the thumb grows while its row is held. */
    private float held;
    private ValueAnimator holding;

    Toggle(Context c, boolean on) {
        super(c);
        this.on = on;
        at = on ? 1 : 0;
        // Pressed with the row it is on.
        setDuplicateParentStateEnabled(true);
    }

    @Override protected void drawableStateChanged() {
        super.drawableStateChanged();
        float to = isPressed() ? 1 : 0;
        if (holding == null && to == held) return;
        if (holding != null) holding.cancel();
        holding = ValueAnimator.ofFloat(held, to);
        holding.setDuration(120);
        holding.setInterpolator(Motion.EMPHASIZED);
        holding.addUpdateListener(x -> { held = (float) x.getAnimatedValue(); invalidate(); });
        holding.start();
    }

    boolean isOn() { return on; }

    /** Turns it to the other side, moving. */
    void flip() {
        on = !on;
        performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK);
        ValueAnimator a = ValueAnimator.ofFloat(at, on ? 1 : 0);
        a.setDuration(250);
        a.setInterpolator(Motion.EMPHASIZED);
        a.addUpdateListener(x -> { at = (float) x.getAnimatedValue(); invalidate(); });
        a.start();
    }

    @Override protected void onMeasure(int w, int h) {
        setMeasuredDimension(Ui.dp(getContext(), 52), Ui.dp(getContext(), 32));
    }

    @Override protected void onDraw(Canvas canvas) {
        Context c = getContext();
        float w = getWidth(), h = getHeight(), r = h / 2f, line = Ui.dp(c, 2);
        int accent = Ui.color(c, R.color.accent), off = Ui.color(c, R.color.text3);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(blend(Ui.color(c, R.color.surface2), accent, at));
        rect.set(0, 0, w, h);
        canvas.drawRoundRect(rect, r, r, paint);
        if (at < 1) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(line);
            paint.setColor(blend(off, accent, at));
            rect.set(line / 2, line / 2, w - line / 2, h - line / 2);
            canvas.drawRoundRect(rect, r - line / 2, r - line / 2, paint);
        }
        // Radius: 16 dp across off, 24 on, 28 held either way.
        float rest = 8 + 4 * at;
        float thumb = Ui.dp(c, rest + (14 - rest) * held);
        float x = r + (w - 2 * r) * at;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(blend(off, 0xFFFFFFFF, at));
        canvas.drawCircle(x, r, thumb, paint);
    }

    private static int blend(int a, int b, float t) {
        int ar = a >>> 24, rr = (a >> 16) & 255, gr = (a >> 8) & 255, br = a & 255;
        return (Math.round(ar + ((b >>> 24) - ar) * t) << 24) | (Math.round(rr + (((b >> 16) & 255) - rr) * t) << 16)
                | (Math.round(gr + (((b >> 8) & 255) - gr) * t) << 8) | Math.round(br + ((b & 255) - br) * t);
    }
}
