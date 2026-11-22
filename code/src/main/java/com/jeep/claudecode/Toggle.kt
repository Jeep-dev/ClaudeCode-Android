package com.jeep.claudecode

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.HapticFeedbackConstants
import android.view.View
import kotlin.math.roundToInt

/** A Material 3 switch: off, an outlined track with a small thumb on the left; on, a filled track with a big thumb on the right. */
class Toggle(c: Context, on: Boolean) : View(c) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    var isOn = on
        private set
    /** 0 off, 1 on; between while it moves. */
    private var at = if (on) 1f else 0f
    /** 0 let go, 1 held: the thumb grows while its row is held. */
    private var held = 0f
    private var holding: ValueAnimator? = null

    init {
        // Pressed with the row it is on.
        isDuplicateParentStateEnabled = true
    }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        val to = if (isPressed) 1f else 0f
        if (holding == null && to == held) return
        holding?.cancel()
        holding = ValueAnimator.ofFloat(held, to).apply {
            duration = 120
            interpolator = Motion.EMPHASIZED
            addUpdateListener { x -> held = x.animatedValue as Float; invalidate() }
            start()
        }
    }

    /** Turns it to the other side, moving. */
    fun flip() {
        isOn = !isOn
        performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
        ValueAnimator.ofFloat(at, if (isOn) 1f else 0f).apply {
            duration = 250
            interpolator = Motion.EMPHASIZED
            addUpdateListener { x -> at = x.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onMeasure(w: Int, h: Int) {
        setMeasuredDimension(Ui.dp(context, 52), Ui.dp(context, 32))
    }

    override fun onDraw(canvas: Canvas) {
        val c = context
        val w = width.toFloat()
        val h = height.toFloat()
        val r = h / 2f
        val line = Ui.dp(c, 2).toFloat()
        val accent = Ui.color(c, R.color.accent)
        val off = Ui.color(c, R.color.text3)
        paint.style = Paint.Style.FILL
        paint.color = blend(Ui.color(c, R.color.surface2), accent, at)
        rect.set(0f, 0f, w, h)
        canvas.drawRoundRect(rect, r, r, paint)
        if (at < 1) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = line
            paint.color = blend(off, accent, at)
            rect.set(line / 2, line / 2, w - line / 2, h - line / 2)
            canvas.drawRoundRect(rect, r - line / 2, r - line / 2, paint)
        }
        // Radius: 16 dp across off, 24 on, 28 held either way.
        val rest = 8 + 4 * at
        val thumb = Ui.dp(c, rest + (14 - rest) * held).toFloat()
        val x = r + (w - 2 * r) * at
        paint.style = Paint.Style.FILL
        paint.color = blend(off, Ui.color(c, R.color.onAccent), at)
        canvas.drawCircle(x, r, thumb, paint)
    }

    private companion object {
        fun blend(a: Int, b: Int, t: Float): Int {
            val ar = a ushr 24
            val rr = (a shr 16) and 255
            val gr = (a shr 8) and 255
            val br = a and 255
            return ((ar + ((b ushr 24) - ar) * t).roundToInt() shl 24) or
                ((rr + (((b shr 16) and 255) - rr) * t).roundToInt() shl 16) or
                ((gr + (((b shr 8) and 255) - gr) * t).roundToInt() shl 8) or
                (br + ((b and 255) - br) * t).roundToInt()
        }
    }
}
