package com.jeep.claudecode

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.app.Dialog
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView

/** Sheets rising from the bottom, and the rows, switches and choices in them: what the settings pages and the menus are built of. */
class Sheets(app: MainActivity) : Part(app) {
    /** Settings is being filled: cards and options are made as its tiles, not as in a sheet. */
    @JvmField var tiled = false

    fun sheet(titleText: String, sub: String?): Dialog {
        val box = SheetBox(app)
        val d = object : Dialog(app, R.style.Sheet) {
            private var leaving = false

            /** Up from the bottom edge, the dim coming with it. */
            override fun show() {
                super.show()
                val w = window
                box.translationY = app.resources.displayMetrics.heightPixels.toFloat()
                box.post {
                    box.translationY = box.height.toFloat()
                    box.animate().translationY(0f).setDuration(Motion.PAGE).setInterpolator(Motion.EMPHASIZED).start()
                    dim(w, 0f, SHEET_DIM)
                }
            }

            /** Down out of sight, the dim going, and only then away: however it is closed. */
            override fun dismiss() {
                if (leaving) return
                if (!isShowing || app.isFinishing || app.isDestroyed || box.height == 0) {
                    super.dismiss()
                    return
                }
                leaving = true
                dim(window, SHEET_DIM, 0f)
                box.animate().translationY(box.height.toFloat()).setDuration(250).setInterpolator(Motion.EMPHASIZED)
                    .withEndAction { super.dismiss() }.start()
            }
        }
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        box.close = Runnable { d.cancel() }
        val bg = Ui.round(col(R.color.bg), 0)
        val r = dp(22).toFloat()
        bg.cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
        box.background = bg
        box.setPadding(dp(18), dp(8), dp(18), dp(18))
        val grab = View(app)
        grab.background = Ui.round(col(R.color.borderStrong), dp(2))
        val gp = LinearLayout.LayoutParams(dp(38), dp(4))
        gp.gravity = Gravity.CENTER_HORIZONTAL
        gp.bottomMargin = dp(14)
        box.addView(grab, gp)
        val t = Ui.text(app, titleText, 21, R.color.text)
        t.typeface = Ui.SERIF
        t.isSingleLine = true
        t.ellipsize = TextUtils.TruncateAt.END
        box.addView(t)
        if (!sub.isNullOrEmpty()) {
            val s = Ui.text(app, sub, 14, R.color.text2)
            s.setLineSpacing(0f, 1.25f)
            s.setPadding(0, dp(8), 0, 0)
            box.addView(s)
        }
        val scroll = object : ScrollView(app) {
            override fun onMeasure(w: Int, h: Int) {
                super.onMeasure(w, MeasureSpec.makeMeasureSpec((resources.displayMetrics.heightPixels * 0.62f).toInt(), MeasureSpec.AT_MOST))
            }

            // A long press that starts selecting gives the text focus, and the scroll would then bring
            // the whole text into view: by its top, past the padding, when it is taller than the sheet.
            // It stays put; dragging a handle to the edge still scrolls (another path).
            override fun requestChildFocus(child: View?, focused: View?) {
                val y = scrollY
                super.requestChildFocus(child, focused)
                scrollTo(scrollX, y)
            }
        }
        val content = Ui.column(app)
        content.setPadding(0, dp(12), 0, 0)
        scroll.addView(content)
        box.addView(scroll, Ui.fill())
        box.tag = content
        d.setContentView(box)
        d.window?.let { w ->
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            w.setGravity(Gravity.BOTTOM)
            // It moves itself (show, dismiss); the dim follows it.
            w.setWindowAnimations(0)
            w.setDimAmount(0f)
        }
        d.setCanceledOnTouchOutside(true)
        return d
    }

    fun content(d: Dialog): LinearLayout {
        val root = d.findViewById<ViewGroup>(android.R.id.content)
        return root.getChildAt(0).tag as LinearLayout
    }

    fun card(d: Dialog): LinearLayout {
        val card = Ui.column(app)
        card.setPadding(dp(4), dp(4), dp(4), dp(4))
        card.background = Ui.round(col(R.color.surface), dp(14), dp(1), col(R.color.border))
        content(d).addView(card, Ui.fill())
        return card
    }

    /** A row: icon, label, and its value on the right. */
    fun option(card: LinearLayout, iconRes: Int, label: String, value: String?, danger: Boolean, click: Runnable?) {
        if (tiled) {
            tileOption(card, iconRes, label, value, danger, click)
            return
        }
        val row = Ui.row(app)
        row.setPadding(dp(14), 0, dp(14), 0)
        row.minimumHeight = dp(50)
        if (click != null) row.background = Ui.press(app, null, dp(12))
        if (iconRes != 0) {
            row.addView(Ui.icon(app, iconRes, 19, if (danger) R.color.danger else R.color.text2))
            row.addView(View(app), LinearLayout.LayoutParams(dp(14), 1))
        }
        val l = Ui.text(app, label, 15.5f, if (danger) R.color.danger else R.color.text)
        l.isSingleLine = true
        l.ellipsize = TextUtils.TruncateAt.END
        row.addView(l, LinearLayout.LayoutParams(0, -2, 1f))
        if (!value.isNullOrEmpty()) {
            val v = Ui.text(app, value, 14, R.color.text3)
            v.isSingleLine = true
            v.ellipsize = TextUtils.TruncateAt.MIDDLE
            v.maxWidth = (app.resources.displayMetrics.widthPixels * 0.45f).toInt()
            v.setPadding(dp(12), 0, 0, 0)
            row.addView(v)
        }
        if (click != null) row.setOnClickListener { click.run() }
        card.addView(row, Ui.fill())
    }

    /** A row of Settings: its icon in a tinted circle, the label with the value under it. */
    private fun tileOption(card: LinearLayout, iconRes: Int, label: String, value: String?, danger: Boolean, click: Runnable?) {
        val row = tileRow(card, iconRes, label, value, danger)
        if (click != null) row.setOnClickListener { click.run() }
    }

    /** A tile's row: icon, label and what is under it; whatever goes on its right is added after. */
    private fun tileRow(card: LinearLayout, iconRes: Int, label: String, value: String?, danger: Boolean): LinearLayout {
        val row = Ui.row(app)
        row.setPadding(dp(16), dp(12), dp(16), dp(12))
        row.minimumHeight = dp(64)
        if (iconRes != 0) {
            val icon = Ui.icon(app, iconRes, 44, if (danger) R.color.danger else R.color.accent)
            icon.setPadding(dp(11), dp(11), dp(11), dp(11))
            icon.background = Ui.round(col(if (danger) R.color.dangerSoft else R.color.accentSoft), dp(22))
            row.addView(icon)
            Ui.margins(icon, app, 0, 0, 16, 0)
        }
        val words = Ui.column(app)
        val l = Ui.text(app, label, 16.5f, if (danger) R.color.danger else R.color.text)
        l.isSingleLine = true
        l.ellipsize = TextUtils.TruncateAt.END
        words.addView(l)
        if (!value.isNullOrEmpty()) {
            val v = Ui.text(app, value, 13.5f, R.color.text2)
            v.maxLines = 2
            v.ellipsize = TextUtils.TruncateAt.END
            v.setLineSpacing(0f, 1.15f)
            v.setPadding(0, dp(5), 0, 0)
            words.addView(v)
        }
        row.addView(words, LinearLayout.LayoutParams(0, -2, 1f))
        card.addView(row, Ui.fill())
        return row
    }

    /** A setting that is on or off: the whole row flips it. */
    fun toggle(card: LinearLayout, label: String, about: String?, on: Boolean, set: (Boolean) -> Unit) {
        val row = tileRow(card, 0, label, about, false)
        val t = Toggle(app, on)
        row.addView(t)
        Ui.margins(t, app, 16, 0, 0, 0)
        row.setOnClickListener {
            t.flip()
            // Redrawn by what it sets: after the thumb has moved.
            ui.postDelayed({ set(t.isOn) }, 260)
        }
    }

    /** A choice of a few: its label, the buttons across the tile under it, the chosen one filled. */
    fun choice(card: LinearLayout, label: String, labels: Array<String>, chosen: Int, pick: (Int) -> Unit) {
        val tile = Ui.column(app)
        tile.setPadding(dp(16), dp(16), dp(16), dp(16))
        tile.addView(Ui.text(app, label, 16.5f, R.color.text))
        val bar = Ui.row(app)
        val n = labels.size
        val shapes = Array(n) { GradientDrawable() }
        val buttons = ArrayList<TextView>()
        var busy = false
        for (i in 0 until n) {
            val b = Ui.text(app, labels[i], 15, R.color.text)
            b.gravity = Gravity.CENTER
            choiceLook(b, shapes[i], i, n, if (i == chosen) 1f else 0f)
            b.background = Ui.press(app, shapes[i], dp(10))
            b.setOnClickListener { v ->
                if (i == chosen || busy) return@setOnClickListener
                busy = true
                v.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                // The fill and the round ends move from the chosen one to this, then it is set.
                val a = ValueAnimator.ofFloat(0f, 1f)
                a.duration = 250
                a.interpolator = Motion.EMPHASIZED
                a.addUpdateListener { x ->
                    val f = x.animatedValue as Float
                    choiceLook(buttons[chosen], shapes[chosen], chosen, n, 1 - f)
                    choiceLook(buttons[i], shapes[i], i, n, f)
                }
                a.addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(x: Animator) = pick(i)
                })
                a.start()
            }
            buttons.add(b)
            bar.addView(b, LinearLayout.LayoutParams(0, dp(48), 1f))
            if (i > 0) Ui.margins(b, app, 4, 0, 0, 0)
        }
        tile.addView(bar, Ui.fill())
        Ui.margins(bar, app, 0, 14, 0, 0)
        card.addView(tile, Ui.fill())
    }

    /** A choice's button [on] of the way to chosen: filled, in the colour on the accent, round at both ends; else only the row's ends round. */
    private fun choiceLook(b: TextView, shape: GradientDrawable, i: Int, n: Int, on: Float) {
        val big = dp(24).toFloat()
        val small = dp(10).toFloat()
        val l = if (i == 0) big else small + (big - small) * on
        val r = if (i == n - 1) big else small + (big - small) * on
        shape.cornerRadii = floatArrayOf(l, l, r, r, r, r, l, l)
        shape.setColor(blend(col(R.color.surface2), col(R.color.accent), on))
        b.setTextColor(blend(col(R.color.text), col(R.color.onAccent), on))
        b.typeface = if (on > 0.5f) Ui.MEDIUM else Typeface.DEFAULT
    }

    fun check(card: LinearLayout) {
        val last = card.getChildAt(card.childCount - 1) as LinearLayout
        val tick = Ui.icon(app, R.drawable.ic_check, 19, R.color.accent)
        last.addView(tick)
        Ui.margins(tick, app, 12, 0, 0, 0)
    }

    /** What a list shows when it has nothing to list (or is still being read): a line in the middle of its card. */
    fun empty(card: LinearLayout, text: String): TextView {
        val none = Ui.text(app, text, 14, R.color.text3)
        none.gravity = Gravity.CENTER
        none.setPadding(0, dp(18), 0, dp(18))
        card.addView(none, Ui.fill())
        return none
    }

    /** The two buttons that end a sheet, side by side below what it holds: the left one (cancel) and the right one (the answer). */
    fun actions(sheet: Dialog, left: TextView, right: TextView) {
        val row = Ui.row(app)
        row.addView(left, LinearLayout.LayoutParams(0, dp(42), 1f))
        row.addView(right, LinearLayout.LayoutParams(0, dp(42), 1f))
        Ui.margins(right, app, 10, 0, 0, 0)
        content(sheet).addView(row, Ui.fill())
        Ui.margins(row, app, 0, 14, 0, 0)
    }

    fun button(label: String, accent: Boolean, click: View.OnClickListener?): TextView {
        val b = Ui.text(app, label, 14.5f, if (accent) R.color.onAccent else R.color.text)
        b.typeface = Ui.MEDIUM
        b.gravity = Gravity.CENTER
        b.setPadding(dp(16), 0, dp(16), 0)
        b.minHeight = dp(38)
        b.background = Ui.press(app, if (accent) Ui.round(col(R.color.accent), dp(10))
            else Ui.round(col(R.color.surface), dp(10), dp(1), col(R.color.borderStrong)), dp(10))
        b.setOnClickListener(click)
        b.layoutParams = Ui.wrap()
        return b
    }

    fun field(sheet: Dialog, label: String, value: String?, hint: String?): EditText {
        val l = Ui.text(app, label, 13, R.color.text2)
        l.setPadding(dp(2), dp(8), 0, dp(6))
        content(sheet).addView(l)
        val f = EditText(app)
        f.setText(value)
        f.hint = hint
        f.isSingleLine = true
        f.textSize = 14.5f
        f.typeface = Ui.MONO
        f.setTextColor(col(R.color.text))
        f.setHintTextColor(col(R.color.text3))
        f.setPadding(dp(12), 0, dp(12), 0)
        f.background = Ui.round(col(R.color.surface), dp(10), dp(1), col(R.color.borderStrong))
        content(sheet).addView(f, LinearLayout.LayoutParams(-1, dp(44)))
        return f
    }

    fun textSheet(title: String, value: String?, hint: String?, multiLine: Boolean, save: (String, Runnable) -> Unit, back: Runnable?) {
        val sheet = sheet(title, null)
        val field = EditText(app)
        field.setText(value)
        field.hint = hint
        field.textSize = 15f
        field.setTextColor(col(R.color.text))
        field.setHintTextColor(col(R.color.text3))
        field.gravity = Gravity.TOP or Gravity.START
        field.setPadding(dp(12), dp(10), dp(12), dp(10))
        field.background = Ui.round(col(R.color.surface), dp(10), dp(1), col(R.color.borderStrong))
        if (multiLine) {
            field.minLines = 6
            field.maxLines = 14
            field.textSize = 14f
        } else {
            field.isSingleLine = true
            field.typeface = Ui.MONO
        }
        content(sheet).addView(field, Ui.fill())
        val cancel = button("取消", false) {
            sheet.dismiss()
            back?.run()
        }
        val ok = button("保存", true) {
            save(field.text.toString(), Runnable {
                sheet.dismiss()
                back?.run()
            })
        }
        ok.background = Ui.press(app, Ui.round(col(R.color.text), dp(10)), dp(10))
        ok.setTextColor(col(R.color.bg))
        actions(sheet, cancel, ok)
        sheet.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        sheet.show()
        field.requestFocus()
    }

    /** A message to read, such as what Claude Code said when something did not work. */
    fun textSheetMessage(title: String, text: String) {
        val sheet = sheet(title, null)
        val t = Ui.text(app, text, 13, R.color.text2)
        t.typeface = Ui.MONO
        t.setTextIsSelectable(true)
        content(sheet).addView(t, Ui.fill())
        sheet.show()
    }

    /** A small menu's card, as the claude.ai app's: no outline, a soft shadow; as wide as its rows. */
    fun menuCard(): LinearLayout {
        val card = Ui.column(app)
        card.setPadding(0, dp(6), 0, dp(6))
        card.background = Ui.round(col(R.color.surface), dp(16))
        card.elevation = dp(10).toFloat()
        card.clipToOutline = true // round at the corners, its rows too
        card.minimumWidth = dp(150)
        return card
    }

    fun menuItem(card: LinearLayout, pop: PopupWindow, icon: Int, label: String, command: String?, click: Runnable) {
        val row = Ui.row(app)
        row.setPadding(dp(18), 0, dp(20), 0)
        row.minimumHeight = dp(48)
        row.background = Ui.press(app, null, 0)
        row.addView(Ui.icon(app, icon, 21, R.color.text2))
        val l = Ui.text(app, label, 16, R.color.text)
        l.setPadding(dp(14), 0, if (command != null) dp(14) else 0, 0)
        l.isSingleLine = true
        row.addView(l, LinearLayout.LayoutParams(0, -2, 1f))
        if (command != null) {
            val c = Ui.text(app, command, 12.5f, R.color.text3)
            c.typeface = Ui.MONO
            row.addView(c)
        }
        row.setOnClickListener {
            pop.dismiss()
            click.run()
        }
        card.addView(row, Ui.fill())
    }

    private companion object {
        /** The dim behind a sheet (its style's backgroundDimAmount). */
        const val SHEET_DIM = 0.34f

        fun dim(w: Window?, from: Float, to: Float) {
            if (w == null) return
            val a = ValueAnimator.ofFloat(from, to)
            a.duration = if (to > from) Motion.PAGE else 250
            a.interpolator = Motion.EMPHASIZED
            a.addUpdateListener { x -> w.setDimAmount(x.animatedValue as Float) }
            a.start()
        }

        fun blend(a: Int, b: Int, t: Float): Int = ArgbEvaluator().evaluate(t, a, b) as Int
    }
}
