package com.jeep.claudecode

import android.app.Dialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 用量 (the top bar's menu): what fills this conversation's context window, as Claude Code's /context
 * counts it (the bridge's /context: get_context_usage), and the Claude.ai plan's limits, the five-hour
 * session and the week, as its /usage shows them (the bridge's /usage), and the limit resets the account
 * was given (claude.ai's Settings → Usage → Limit resets), each with a button that spends one (/reset).
 *
 * The context is one segmented bar of the window, a part per kind (system prompt, tools, messages, ...),
 * each kind always in its own colour, with a legend naming them; a limit is a meter whose fill turns to
 * warning, then danger, as it runs out, said in words too. What it says is read in Usage.
 */
class UsageSheet(app: MainActivity) : Part(app) {
    /** A reset being spent: one at a time, so a second tap (the asking sheet is still sliding away) spends none. */
    private var resetting = false

    /** What a card shows: still reading, a line instead of it, or what was read. */
    internal sealed interface Shown<out T> {
        data object Waiting : Shown<Nothing>
        data class Note(val text: String) : Shown<Nothing>
        data class Ready<T>(val value: T) : Shown<T>
    }

    /** An open sheet's cards: each given what was read is drawn again. */
    internal inner class Cards(
        val sheet: Dialog,
        private val context: LinearLayout,
        private val plan: LinearLayout,
        /** 额度重置: shown only when the account has some. */
        private val resets: LinearLayout,
    ) {
        fun window(s: Shown<Usage.Window>) {
            context.removeAllViews()
            when (s) {
                is Shown.Waiting -> context.addView(waitingLine("正在读取…"))
                is Shown.Note -> context.addView(waitingLine(s.text))
                is Shown.Ready -> fillContext(context, s.value)
            }
        }

        fun plan(s: Shown<Usage.Plan>) {
            plan.removeAllViews()
            resets.removeAllViews()
            resets.setPadding(0, 0, 0, 0) // its gap goes with it (fillResets sets it when there are some)
            when (s) {
                is Shown.Waiting -> plan.addView(waitingLine("正在读取…"))
                is Shown.Note -> plan.addView(waitingLine(s.text))
                is Shown.Ready -> {
                    val p = s.value as Usage.Plan.Limits
                    fillPlan(plan, p.limits)
                    fillResets(resets, p.resets, this)
                }
            }
        }
    }

    fun show() {
        val cards = open()
        val chat = app.current
        bridge.post("/context", obj("chat", chat.key, "sessionId", chat.sessionId)) { status, data ->
            val c = if (status == 200 && data != null) data.optJSONObject("context") else null
            cards.window(if (c == null) Shown.Note("这个对话的 Claude Code 还没在运行：发一条消息后就能看到") else Shown.Ready(Usage.window(c)))
        }
        loadPlan(cards)
    }

    /** The sheet shown, both cards still reading (a test fills them itself). */
    internal fun open(): Cards {
        return traced("cc usage") {
            val sheet = sheets.sheet("用量", null)
            val box = sheets.content(sheet)
            label(box, "上下文")
            val context = card(box)
            label(box, "订阅额度")
            val plan = card(box)
            val resets = Ui.column(app)
            box.addView(resets, Ui.fill())
            val cards = Cards(sheet, context, plan, resets)
            cards.window(Shown.Waiting)
            cards.plan(Shown.Waiting)
            sheet.show()
            return cards
        }
    }

    /** Reads the limits (again, after a reset): what was shown stays until they come. */
    private fun loadPlan(cards: Cards) {
        bridge.get("/usage") { status, data ->
            cards.plan(when (val p = Usage.plan(status, data)) {
                is Usage.Plan.Note -> Shown.Note(p.text)
                is Usage.Plan.Limits -> Shown.Ready(p)
            })
        }
    }

    // ---- 上下文 ----

    private fun fillContext(card: LinearLayout, w: Usage.Window) {
        val palette = if (Themes.dark(app)) DARK else LIGHT

        // The headline: the share of the window in use, and how much of how much.
        val head = Ui.row(app)
        head.gravity = Gravity.BOTTOM
        val pct = Ui.text(app, "${w.percent}%", 30, R.color.text)
        pct.typeface = Ui.MEDIUM
        head.addView(pct)
        head.addView(View(app), LinearLayout.LayoutParams(0, 1, 1f))
        val of = Ui.text(app, Usage.tokens(w.total) + " / " + Usage.tokens(w.max), 14, R.color.text2)
        of.setPadding(0, 0, 0, dp(5))
        head.addView(of)
        card.addView(head, Ui.fill())

        // The parts in use, each in its kind's colour; the buffer kept for compacting in grey; the rest free.
        val bufferColor = col(R.color.borderStrong)
        val bar = Segments(app, w.max, col(R.color.surface2))
        for (p in w.parts) bar.add(p.tokens, palette[p.slot])
        bar.add(w.buffer, bufferColor)
        bar.contentDescription = "上下文已用 " + Usage.tokens(w.total) + "，共 " + Usage.tokens(w.max)
        card.addView(bar, LinearLayout.LayoutParams(-1, dp(10)))
        Ui.margins(bar, app, 0, 10, 0, 6)

        // The legend, in two columns (all of the sheet on one screen): each part's name and amount, in the bar's order.
        val items = w.parts.map { Triple(palette[it.slot], Usage.chinese(it.name), it.tokens) } +
            listOfNotNull(if (w.buffer > 0) Triple(bufferColor, "自动压缩预留", w.buffer) else null) +
            Triple(col(R.color.surface2), "空闲", w.free)
        val rows = (items.size + 1) / 2
        for (r in 0 until rows) {
            val line = Ui.row(app)
            for (column in 0 until 2) {
                val i = column * rows + r // down the first column, then the second: the bar's order
                val item = items.getOrNull(i)
                val cell = if (item == null) Ui.row(app) else legend(item.first, item.second, item.third)
                val lp = LinearLayout.LayoutParams(0, -2, 1f)
                if (column == 0) lp.setMargins(0, 0, dp(14), 0)
                line.addView(cell, lp)
            }
            card.addView(line, Ui.fill())
        }
    }

    private fun legend(color: Int, name: String, tokens: Long): LinearLayout {
        val row = Ui.row(app)
        row.setPadding(0, dp(4), 0, dp(4))
        val dot = View(app)
        dot.background = Ui.round(color, dp(3), dp(1), col(R.color.border))
        row.addView(dot, LinearLayout.LayoutParams(dp(10), dp(10)))
        val n = Ui.text(app, name, 13.5f, R.color.text)
        n.setPadding(dp(8), 0, dp(4), 0)
        n.isSingleLine = true
        n.ellipsize = TextUtils.TruncateAt.END
        row.addView(n, Ui.weight())
        row.addView(Ui.text(app, Usage.tokens(tokens), 13.5f, R.color.text2))
        return row
    }

    // ---- 订阅额度 ----

    private fun fillPlan(card: LinearLayout, limits: List<Usage.Limit>) {
        if (limits.isEmpty()) {
            card.addView(waitingLine("这个账号没有额度上限"))
            return
        }
        for ((i, l) in limits.withIndex()) {
            if (i > 0) card.addView(View(app), LinearLayout.LayoutParams(1, dp(12)))
            // The fill carries how near it is to running out; words say it too.
            val fill = when (l.level) {
                Usage.Level.OUT -> R.color.danger
                Usage.Level.LOW -> R.color.warn
                Usage.Level.OK -> R.color.accent
            }
            val head = Ui.row(app)
            val name = Ui.text(app, l.name, 15, R.color.text)
            name.typeface = Ui.MEDIUM
            head.addView(name, Ui.weight())
            if (l.level != Usage.Level.OK) {
                val s = Ui.text(app, if (l.level == Usage.Level.OUT) "已用完" else "快用完了", 12.5f, fill)
                s.setPadding(0, 0, dp(8), 0)
                head.addView(s)
            }
            val p = Ui.text(app, "${l.percent}%", 15, R.color.text)
            p.typeface = Ui.MEDIUM
            head.addView(p)
            card.addView(head, Ui.fill())

            val fillColor = col(fill)
            val meter = Segments(app, 100, Glass.alpha(fillColor, 0.18f))
            meter.add(l.percent.toLong(), fillColor)
            meter.contentDescription = l.name + " 已用 " + l.percent + "%"
            card.addView(meter, LinearLayout.LayoutParams(-1, dp(8)))
            Ui.margins(meter, app, 0, 7, 0, 5)

            if (l.note.isNotEmpty()) card.addView(Ui.text(app, l.note, 12.5f, R.color.text3))
        }
    }

    // ---- 额度重置 ----

    /**
     * The limit resets the account holds (claude.ai's 「Reset for free」): what each gives back, how many
     * are left and until when, and a button spending one, asked first (it cannot be undone).
     */
    private fun fillResets(box: LinearLayout, resets: List<Usage.Reset>, cards: Cards) {
        box.setPadding(0, if (resets.isEmpty()) 0 else dp(14), 0, 0) // label() sees an empty box: the gap above is set here
        if (resets.isEmpty()) return
        label(box, "额度重置")
        val card = card(box)
        for ((i, r) in resets.withIndex()) {
            if (i > 0) card.addView(View(app), LinearLayout.LayoutParams(1, dp(12)))
            val row = Ui.row(app)
            row.gravity = Gravity.CENTER_VERTICAL
            val words = Ui.column(app)
            val name = Ui.text(app, r.title, 15, R.color.text)
            name.typeface = Ui.MEDIUM
            words.addView(name)
            val sub = Ui.text(app, r.sub, 12.5f, R.color.text3)
            sub.setPadding(0, dp(3), 0, 0)
            words.addView(sub)
            row.addView(words, Ui.weight())
            val use = sheets.button(if (r.usable) "免费重置" else "暂不可用", r.usable,
                if (r.usable) View.OnClickListener { if (!resetting) askReset(r, cards) } else null)
            use.isEnabled = r.usable
            if (!r.usable) use.alpha = 0.5f
            row.addView(use)
            card.addView(row, Ui.fill())
            if (r.gives.isNotEmpty()) {
                val what = Ui.text(app, r.gives, 12.5f, R.color.text2)
                what.setLineSpacing(0f, 1.2f)
                what.setPadding(0, dp(8), 0, 0)
                card.addView(what)
            }
        }
    }

    /** Asks first (a reset cannot be undone), then spends it and reads the limits again. */
    private fun askReset(r: Usage.Reset, cards: Cards) {
        val ask: Dialog = sheets.sheet("使用一次" + r.title + "？", r.gives + "\n\n剩 " + r.left + " 次，用后剩 " + (r.left - 1) + " 次。")
        val cancel = sheets.button("取消", false) { ask.dismiss() }
        val ok = sheets.button("免费重置", true) { v ->
            if (resetting) return@button
            resetting = true
            v.isEnabled = false
            ask.dismiss()
            bridge.post("/reset", obj("grant", r.id)) { status, data ->
                resetting = false
                toast(Usage.resetResult(status, data))
                loadPlan(cards)
            }
        }
        sheets.actions(ask, cancel, ok)
        ask.show()
    }

    // ---- 共用 ----

    private fun label(box: LinearLayout, text: String) {
        val t = Ui.text(app, text, 14, R.color.text2)
        t.typeface = Ui.MEDIUM
        t.setPadding(dp(6), dp(if (box.childCount > 0) 14 else 0), 0, dp(6))
        box.addView(t)
    }

    private fun card(box: LinearLayout): LinearLayout {
        val card = Ui.column(app)
        card.setPadding(dp(16), dp(12), dp(16), dp(13))
        card.background = Ui.round(col(R.color.surface), dp(18), dp(1), col(R.color.border))
        box.addView(card, Ui.fill())
        return card
    }

    private fun waitingLine(text: String): TextView {
        val t = Ui.text(app, text, 14, R.color.text2)
        t.setLineSpacing(0f, 1.2f)
        return t
    }

    /**
     * One whole split into parts, left to right: each part as wide as its share, a 2 dp gap of the surface
     * between parts, the ends round; what is left is the track. A part at zero draws nothing.
     */
    private class Segments(c: Context, whole: Long, private val track: Int) : View(c) {
        private val whole = maxOf(1L, whole)
        private val surface = Ui.color(c, R.color.surface)
        private val parts = ArrayList<Pair<Long, Int>>()
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val clip = Path()
        private val r = RectF()

        fun add(amount: Long, color: Int) {
            if (amount > 0) parts.add(amount to color)
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            val gap = 2 * resources.displayMetrics.density
            clip.reset()
            r.set(0f, 0f, w, h)
            clip.addRoundRect(r, h / 2, h / 2, Path.Direction.CW)
            canvas.save()
            canvas.clipPath(clip)
            paint.color = track
            canvas.drawRect(r, paint)
            var x = 0f
            val least = maxOf(gap * 1.5f, h / 3)
            for ((amount, color) in parts) {
                val end = minOf(w, x + maxOf(least, w * amount / whole))
                paint.color = color
                canvas.drawRect(x, 0f, maxOf(x, end - if (end < w) gap else 0f), h, paint)
                // The gap after each part shows the card's surface.
                if (end < w) {
                    paint.color = surface
                    canvas.drawRect(end - gap, 0f, end, h, paint)
                }
                x = end
                if (x >= w) break
            }
            canvas.restore()
        }
    }

    private companion object {
        /** The categorical palette (validated, light and dark), slots in a fixed order. */
        val LIGHT = intArrayOf(0xFF2A78D6.toInt(), 0xFFEB6834.toInt(), 0xFF1BAF7A.toInt(), 0xFFEDA100.toInt(),
            0xFFE87BA4.toInt(), 0xFF008300.toInt(), 0xFF6250D6.toInt(), 0xFFE34948.toInt())
        val DARK = intArrayOf(0xFF3987E5.toInt(), 0xFFD95926.toInt(), 0xFF199E70.toInt(), 0xFFC98500.toInt(),
            0xFFD55181.toInt(), 0xFF008300.toInt(), 0xFF9085E9.toInt(), 0xFFE66767.toInt())
    }
}
