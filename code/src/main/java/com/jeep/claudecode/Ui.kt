package com.jeep.claudecode

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale
import kotlin.math.roundToInt

/** Small helpers for building the interface in code. */
object Ui {
    @JvmField val SERIF: Typeface = Typeface.create("serif", Typeface.NORMAL)
    @JvmField val MONO: Typeface = Typeface.MONOSPACE
    @JvmField val MEDIUM: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    @JvmStatic
    fun dp(c: Context, v: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.resources.displayMetrics).roundToInt()

    @JvmStatic
    fun dp(c: Context, v: Int): Int = dp(c, v.toFloat())

    @JvmStatic
    fun color(c: Context, res: Int): Int = Themes.color(c, res)

    @JvmStatic
    fun round(fill: Int, radius: Number): GradientDrawable = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = radius.toFloat()
    }

    @JvmStatic
    fun round(fill: Int, radius: Number, strokeWidth: Int, stroke: Int): GradientDrawable =
        round(fill, radius).apply { setStroke(strokeWidth, stroke) }

    /** A pressed-state ripple over a shape (or over the view's bounds when content is null). */
    @JvmStatic
    fun press(c: Context, content: Drawable?, radius: Number): Drawable =
        RippleDrawable(ColorStateList.valueOf(color(c, R.color.active)), content, round(0xFF000000.toInt(), radius))

    @JvmStatic
    fun text(c: Context, s: CharSequence?, sp: Number, colorRes: Int): TextView = TextView(c).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp.toFloat())
        setTextColor(color(c, colorRes))
        includeFontPadding = false
    }

    @JvmStatic
    fun icon(c: Context, drawable: Int, sizeDp: Int, colorRes: Int): ImageView = ImageView(c).apply {
        setImageResource(drawable)
        imageTintList = ColorStateList.valueOf(color(c, colorRes))
        layoutParams = LinearLayout.LayoutParams(dp(c, sizeDp), dp(c, sizeDp))
    }

    /** A square tap target with an icon in the middle. */
    @JvmStatic
    fun iconButton(c: Context, drawable: Int, sizeDp: Int, iconDp: Int, colorRes: Int): ImageView =
        icon(c, drawable, sizeDp, colorRes).apply {
            val pad = dp(c, (sizeDp - iconDp) / 2f)
            setPadding(pad, pad, pad, pad)
            background = press(c, null, dp(c, 12))
            isClickable = true
        }

    @JvmStatic
    fun row(c: Context): LinearLayout = LinearLayout(c).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    @JvmStatic
    fun column(c: Context): LinearLayout = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }

    @JvmStatic
    fun wrap() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    @JvmStatic
    fun fill() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    @JvmStatic
    fun weight() = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)

    @JvmStatic
    fun <T : View> margins(v: T, c: Context, l: Int, t: Int, r: Int, b: Int): T {
        val p = v.layoutParams
        val m = p as? ViewGroup.MarginLayoutParams ?: LinearLayout.LayoutParams(p ?: fill())
        m.setMargins(dp(c, l), dp(c, t), dp(c, r), dp(c, b))
        v.layoutParams = m
        return v
    }

    @JvmStatic
    fun base(path: String?): String {
        if (path.isNullOrEmpty()) return ""
        val p = path.replace(Regex("/+$"), "")
        val i = p.lastIndexOf('/')
        return if (i < 0) p else p.substring(i + 1).ifEmpty { "/" }
    }

    @JvmStatic
    fun size(n: Long): String = when {
        n >= 1 shl 20 -> String.format(Locale.ROOT, "%.1f MB", n / 1048576.0)
        n >= 1024 -> "${n / 1024} KB"
        else -> "$n B"
    }
}
