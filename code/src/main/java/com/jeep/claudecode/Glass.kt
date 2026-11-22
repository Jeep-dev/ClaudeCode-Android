package com.jeep.claudecode

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Shader
import android.os.Build
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Frosted glass for the immersive layout: the conversation is drawn once into a RenderNode, and
 * each pane over it (the buttons, the box) draws the part under it blurred, with a tint on top.
 * Android 12+ with hardware drawing; elsewhere a pane is a nearly solid fill.
 */
object Glass {
    @JvmStatic
    fun blurs(): Boolean = Build.VERSION.SDK_INT >= 31

    /** Holds what the glass shows (the source) and the panes over it. */
    class Host(c: Context) : FrameLayout(c) {
        var source: View? = null
        val panes = ArrayList<Pane>()
        private var content: Any? = null // RenderNode, typed loosely for Android before 10

        override fun drawChild(canvas: Canvas, child: View, time: Long): Boolean {
            if (child !== source || !blurs() || !canvas.isHardwareAccelerated) return super.drawChild(canvas, child, time)
            if (content == null) content = RenderNode("behind glass")
            val node = content as RenderNode
            node.setPosition(0, 0, width, height)
            val rc = node.beginRecording()
            val more = try {
                super.drawChild(rc, child, time)
            } finally {
                node.endRecording()
            }
            canvas.drawRenderNode(node)
            for (p in panes) p.record(node, this)
            return more
        }
    }

    /**
     * A rounded pane of glass: the page's colour (so nothing sharp shows through), what is under
     * it blurred, its tint, a hairline round it. A radius below 0: a circle.
     */
    class Pane(
        c: Context,
        host: Host,
        private val radius: Float,
        private val base: Int,
        private val tint: Int,
        private val solid: Int,
        stroke: Int,
    ) : LinearLayout(c) {
        private val line = Paint(Paint.ANTI_ALIAS_FLAG)
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val shape = Path()
        private val bounds = RectF()
        private var blurred: Any? = null // RenderNode
        private var recorded = false

        init {
            setWillNotDraw(false)
            line.style = Paint.Style.STROKE
            line.strokeWidth = Ui.dp(c, 1).toFloat()
            line.color = stroke
            fill.color = solid
            host.panes.add(this)
        }

        override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
            super.onSizeChanged(w, h, ow, oh)
            val r = if (radius < 0) min(w, h) / 2f else radius
            bounds.set(0f, 0f, w.toFloat(), h.toFloat())
            shape.reset()
            shape.addRoundRect(bounds, r, r, Path.Direction.CW)
            if (blurs()) {
                if (blurred == null) blurred = RenderNode("glass")
                val node = blurred as RenderNode
                node.setPosition(0, 0, w, h)
                val blur = Ui.dp(context, 26).toFloat()
                node.setRenderEffect(RenderEffect.createBlurEffect(blur, blur, Shader.TileMode.CLAMP))
            }
        }

        /** Takes what is under the pane now (called each time the host draws what is behind). */
        fun record(content: RenderNode, host: Host) {
            if (blurred == null || width == 0 || !isShown) return
            var x = 0f
            var y = 0f
            var v: View? = this
            while (v !== host && v != null) {
                x += v.x
                y += v.y
                v = v.parent as? View
            }
            val node = blurred as RenderNode
            val rc = node.beginRecording()
            try {
                rc.translate(-x, -y)
                rc.drawRenderNode(content)
            } finally {
                node.endRecording()
            }
            recorded = true
        }

        override fun onDraw(canvas: Canvas) {
            val r = if (radius < 0) min(width, height) / 2f else radius
            if (recorded && blurs() && canvas.isHardwareAccelerated) {
                canvas.save()
                canvas.clipPath(shape)
                canvas.drawColor(base)
                canvas.drawRenderNode(blurred as RenderNode)
                canvas.drawColor(tint)
                canvas.restore()
            } else {
                fill.color = base
                canvas.drawRoundRect(bounds, r, r, fill)
                fill.color = solid
                canvas.drawRoundRect(bounds, r, r, fill)
            }
            val half = line.strokeWidth / 2f
            canvas.drawRoundRect(half, half, width - half, height - half, r, r, line)
        }
    }

    /** A colour with the given opacity (0–1). */
    @JvmStatic
    fun alpha(color: Int, a: Float): Int = Color.argb((a * 255).roundToInt(), Color.red(color), Color.green(color), Color.blue(color))
}
