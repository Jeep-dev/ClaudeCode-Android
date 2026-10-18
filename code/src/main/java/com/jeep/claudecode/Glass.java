package com.jeep.claudecode;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.Build;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import java.util.ArrayList;
import java.util.List;

/**
 * Frosted glass for the immersive layout: the conversation is drawn once into a RenderNode, and
 * each pane over it (the buttons, the box) draws the part under it blurred, with a tint on top.
 * Android 12+ with hardware drawing; elsewhere a pane is a nearly solid fill.
 */
final class Glass {
    private Glass() {}

    static boolean blurs() { return Build.VERSION.SDK_INT >= 31; }

    /** Holds what the glass shows (the source) and the panes over it. */
    static final class Host extends FrameLayout {
        View source;
        final List<Pane> panes = new ArrayList<>();
        private Object content; // RenderNode, typed loosely for Android before 10

        Host(Context c) { super(c); }

        @Override protected boolean drawChild(Canvas canvas, View child, long time) {
            if (child != source || !blurs() || !canvas.isHardwareAccelerated()) return super.drawChild(canvas, child, time);
            if (content == null) content = new android.graphics.RenderNode("behind glass");
            android.graphics.RenderNode node = (android.graphics.RenderNode) content;
            node.setPosition(0, 0, getWidth(), getHeight());
            android.graphics.RecordingCanvas rc = node.beginRecording();
            boolean more;
            try {
                more = super.drawChild(rc, child, time);
            } finally {
                node.endRecording();
            }
            canvas.drawRenderNode(node);
            for (Pane p : panes) p.record(node, this);
            return more;
        }
    }

    /**
     * A rounded pane of glass: the page's colour (so nothing sharp shows through), what is under
     * it blurred, its tint, a hairline round it.
     */
    static final class Pane extends LinearLayout {
        private final float radius;
        private final int tint, solid, base;
        private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG), fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path shape = new Path();
        private final RectF bounds = new RectF();
        private Object blurred; // RenderNode
        private boolean recorded;

        /** radius < 0: a circle. */
        Pane(Context c, Host host, float radius, int base, int tint, int solid, int stroke) {
            super(c);
            this.radius = radius;
            this.base = base;
            this.tint = tint;
            this.solid = solid;
            setWillNotDraw(false);
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeWidth(Ui.dp(c, 1));
            line.setColor(stroke);
            fill.setColor(solid);
            host.panes.add(this);
        }

        @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
            super.onSizeChanged(w, h, ow, oh);
            float r = radius < 0 ? Math.min(w, h) / 2f : radius;
            bounds.set(0, 0, w, h);
            shape.reset();
            shape.addRoundRect(bounds, r, r, Path.Direction.CW);
            if (blurs()) {
                if (blurred == null) blurred = new android.graphics.RenderNode("glass");
                android.graphics.RenderNode node = (android.graphics.RenderNode) blurred;
                node.setPosition(0, 0, w, h);
                float blur = Ui.dp(getContext(), 26);
                node.setRenderEffect(android.graphics.RenderEffect.createBlurEffect(blur, blur, android.graphics.Shader.TileMode.CLAMP));
            }
        }

        /** Takes what is under the pane now (called each time the host draws what is behind). */
        void record(android.graphics.RenderNode content, Host host) {
            if (blurred == null || getWidth() == 0 || !isShown()) return;
            float x = 0, y = 0;
            for (View v = this; v != host && v != null; v = v.getParent() instanceof View ? (View) v.getParent() : null) {
                x += v.getX();
                y += v.getY();
            }
            android.graphics.RenderNode node = (android.graphics.RenderNode) blurred;
            android.graphics.RecordingCanvas rc = node.beginRecording();
            try {
                rc.translate(-x, -y);
                rc.drawRenderNode(content);
            } finally {
                node.endRecording();
            }
            recorded = true;
        }

        @Override protected void onDraw(Canvas canvas) {
            float r = radius < 0 ? Math.min(getWidth(), getHeight()) / 2f : radius;
            if (recorded && blurs() && canvas.isHardwareAccelerated()) {
                canvas.save();
                canvas.clipPath(shape);
                canvas.drawColor(base);
                canvas.drawRenderNode((android.graphics.RenderNode) blurred);
                canvas.drawColor(tint);
                canvas.restore();
            } else {
                fill.setColor(base);
                canvas.drawRoundRect(bounds, r, r, fill);
                fill.setColor(solid);
                canvas.drawRoundRect(bounds, r, r, fill);
            }
            float half = line.getStrokeWidth() / 2f;
            canvas.drawRoundRect(half, half, getWidth() - half, getHeight() - half, r, r, line);
        }
    }

    /** A colour with the given opacity (0–1). */
    static int alpha(int color, float a) {
        return Color.argb(Math.round(a * 255), Color.red(color), Color.green(color), Color.blue(color));
    }
}
