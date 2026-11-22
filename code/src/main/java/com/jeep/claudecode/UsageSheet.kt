package com.jeep.claudecode

import android.app.Dialog
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/**
 * 用量 (the top bar's menu): what fills this conversation's context window, as Claude Code's /context
 * counts it (the bridge's /context: get_context_usage), and the Claude.ai plan's limits, the five-hour
 * session and the week, as its /usage shows them (the bridge's /usage), and the limit resets the account
 * was given (claude.ai's Settings → Usage → Limit resets), each with a button that spends one (/reset).
 *
 * The context is one segmented bar of the window, a part per kind (system prompt, tools, messages, ...),
 * each kind always in its own colour, with a legend naming them; a limit is a meter whose fill turns to
 * warning, then danger, as it runs out, said in words too.
 *
 * The sheet is the app's own (Sheets.sheet); what it holds is drawn with Compose (composeIn). What it says
 * is read in Usage.
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

    /** An open sheet's two cards: set, they are drawn again. */
    internal class Cards(val sheet: Dialog) {
        val window = mutableStateOf<Shown<Usage.Window>>(Shown.Waiting)
        val plan = mutableStateOf<Shown<Usage.Plan>>(Shown.Waiting)
    }

    fun show() {
        val cards = open()
        val chat = app.current
        bridge.post("/context", obj("chat", chat.key, "sessionId", chat.sessionId)) { status, data ->
            val c = if (status == 200 && data != null) data.optJSONObject("context") else null
            cards.window.value = if (c == null) Shown.Note("这个对话的 Claude Code 还没在运行：发一条消息后就能看到")
            else Shown.Ready(Usage.window(c))
        }
        loadPlan(cards.plan)
    }

    /** The sheet shown, both cards still reading (a test fills them itself). */
    internal fun open(): Cards {
        return traced("cc usage") {
            val cards = Cards(sheets.sheet("用量", null))
            val colors = AppColors(app)
            composeIn(cards.sheet, sheets.content(cards.sheet)) {
                Sheet(colors, cards.window.value, cards.plan.value) { r -> if (!resetting) askReset(r, cards.plan) }
            }
            cards.sheet.show()
            return cards
        }
    }

    /** Reads the limits (again, after a reset): what was shown stays until they come. */
    private fun loadPlan(plan: MutableState<Shown<Usage.Plan>>) {
        bridge.get("/usage") { status, data ->
            plan.value = when (val p = Usage.plan(status, data)) {
                is Usage.Plan.Note -> Shown.Note(p.text)
                is Usage.Plan.Limits -> Shown.Ready(p)
            }
        }
    }

    /** Asks first (a reset cannot be undone), then spends it and reads the limits again. */
    private fun askReset(r: Usage.Reset, plan: MutableState<Shown<Usage.Plan>>) {
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
                loadPlan(plan)
            }
        }
        sheets.actions(ask, cancel, ok)
        ask.show()
    }

    /** The categorical palette (validated, light and dark), slots in a fixed order. */
    private fun palette(c: AppColors) = if (c.dark) DARK else LIGHT

    private fun level(c: AppColors, l: Usage.Level) = when (l) {
        Usage.Level.OUT -> c.danger
        Usage.Level.LOW -> c.warn
        Usage.Level.OK -> c.accent
    }

    private companion object {
        val LIGHT = listOf(0xFF2A78D6, 0xFFEB6834, 0xFF1BAF7A, 0xFFEDA100, 0xFFE87BA4, 0xFF008300, 0xFF6250D6, 0xFFE34948).map { Color(it) }
        val DARK = listOf(0xFF3987E5, 0xFFD95926, 0xFF199E70, 0xFFC98500, 0xFFD55181, 0xFF008300, 0xFF9085E9, 0xFFE66767).map { Color(it) }
    }

    // ---- 界面 ----

    @Composable
    private fun Sheet(c: AppColors, window: Shown<Usage.Window>, plan: Shown<Usage.Plan>, onReset: (Usage.Reset) -> Unit) {
        Column(Modifier.fillMaxWidth()) {
            Label(c, "上下文", first = true)
            Card(c) {
                when (window) {
                    is Shown.Waiting -> Waiting(c, "正在读取…")
                    is Shown.Note -> Waiting(c, window.text)
                    is Shown.Ready -> ContextWindow(c, window.value)
                }
            }
            Label(c, "订阅额度")
            Card(c) {
                when (plan) {
                    is Shown.Waiting -> Waiting(c, "正在读取…")
                    is Shown.Note -> Waiting(c, plan.text)
                    is Shown.Ready -> Limits(c, (plan.value as Usage.Plan.Limits).limits)
                }
            }
            // 额度重置: shown only when the account has some.
            val resets = ((plan as? Shown.Ready)?.value as? Usage.Plan.Limits)?.resets.orEmpty()
            if (resets.isNotEmpty()) {
                Label(c, "额度重置")
                Card(c) { Resets(c, resets, onReset) }
            }
        }
    }

    @Composable
    private fun ContextWindow(c: AppColors, w: Usage.Window) {
        // The headline: the share of the window in use, and how much of how much.
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Words("${w.percent}%", 30.sp, c.text, medium = true)
            Spacer(Modifier.weight(1f))
            Words(Usage.tokens(w.total) + " / " + Usage.tokens(w.max), 14.sp, c.text2, Modifier.padding(bottom = 5.dp))
        }

        // The parts in use, each in its kind's colour; the buffer kept for compacting in grey; the rest free.
        val parts = w.parts.map { it.tokens to palette(c)[it.slot] } + listOfNotNull(if (w.buffer > 0) w.buffer to c.borderStrong else null)
        Segments(
            w.max, c.surface2, c.surface, parts, "上下文已用 " + Usage.tokens(w.total) + "，共 " + Usage.tokens(w.max),
            Modifier.padding(top = 10.dp, bottom = 6.dp).height(10.dp),
        )

        // The legend, in two columns (all of the sheet on one screen): each part's name and amount, in the bar's order.
        val items = w.parts.map { Triple(palette(c)[it.slot], Usage.chinese(it.name), it.tokens) } +
            listOfNotNull(if (w.buffer > 0) Triple(c.borderStrong, "自动压缩预留", w.buffer) else null) +
            Triple(c.surface2, "空闲", w.free)
        val rows = (items.size + 1) / 2
        for (r in 0 until rows) {
            Row(Modifier.fillMaxWidth()) {
                for (col in 0 until 2) {
                    val i = col * rows + r // down the first column, then the second: the bar's order
                    Box(Modifier.weight(1f).padding(end = if (col == 0) 14.dp else 0.dp)) {
                        items.getOrNull(i)?.let { (color, name, tokens) -> Legend(c, color, name, tokens) }
                    }
                }
            }
        }
    }

    @Composable
    private fun Legend(c: AppColors, color: Color, name: String, tokens: Long) {
        Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(color)
                    .border(1.dp, c.border, RoundedCornerShape(3.dp)),
            )
            BasicText(
                name, Modifier.weight(1f).padding(start = 8.dp, end = 4.dp),
                style = TextStyle(color = c.text, fontSize = 13.5.sp), maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Words(Usage.tokens(tokens), 13.5.sp, c.text2)
        }
    }

    @Composable
    private fun Limits(c: AppColors, limits: List<Usage.Limit>) {
        if (limits.isEmpty()) { Waiting(c, "这个账号没有额度上限"); return }
        limits.forEachIndexed { i, l ->
            if (i > 0) Spacer(Modifier.height(12.dp))
            val fill = level(c, l.level)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Words(l.name, 15.sp, c.text, Modifier.weight(1f), medium = true)
                // The fill carries how near it is to running out; words say it too.
                when (l.level) {
                    Usage.Level.OUT -> Words("已用完", 12.5.sp, fill, Modifier.padding(end = 8.dp))
                    Usage.Level.LOW -> Words("快用完了", 12.5.sp, fill, Modifier.padding(end = 8.dp))
                    Usage.Level.OK -> {}
                }
                Words("${l.percent}%", 15.sp, c.text, medium = true)
            }
            Segments(
                100, fill.copy(alpha = 0.18f), c.surface, listOf(l.percent.toLong() to fill), l.name + " 已用 " + l.percent + "%",
                Modifier.padding(top = 7.dp, bottom = 5.dp).height(8.dp),
            )
            if (l.note.isNotEmpty()) Words(l.note, 12.5.sp, c.text3)
        }
    }

    /**
     * The limit resets the account holds (claude.ai's 「Reset for free」): what each gives back, how many
     * are left and until when, and a button spending one, asked first (it cannot be undone).
     */
    @Composable
    private fun Resets(c: AppColors, resets: List<Usage.Reset>, onReset: (Usage.Reset) -> Unit) {
        resets.forEachIndexed { i, r ->
            if (i > 0) Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Words(r.title, 15.sp, c.text, medium = true)
                    Words(r.sub, 12.5.sp, c.text3, Modifier.padding(top = 3.dp))
                }
                SheetButton(c, if (r.usable) "免费重置" else "暂不可用", accent = r.usable, enabled = r.usable) { onReset(r) }
            }
            if (r.gives.isNotEmpty()) Words(r.gives, 12.5.sp, c.text2, Modifier.padding(top = 8.dp), lineHeight = 1.7.em)
        }
    }

    // ---- 共用 ----

    @Composable
    private fun Label(c: AppColors, text: String, first: Boolean = false) {
        Words(text, 14.sp, c.text2, Modifier.padding(start = 6.dp, top = if (first) 0.dp else 14.dp, bottom = 6.dp), medium = true)
    }

    @Composable
    private fun Card(c: AppColors, content: @Composable () -> Unit) {
        val shape = RoundedCornerShape(18.dp)
        Column(
            Modifier.fillMaxWidth().clip(shape).background(c.surface).border(1.dp, c.border, shape)
                .padding(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 13.dp),
        ) { content() }
    }

    @Composable
    private fun Waiting(c: AppColors, text: String) = Words(text, 14.sp, c.text2, lineHeight = 1.7.em)

    /**
     * One whole split into parts, left to right: each part as wide as its share, a 2 dp gap of the surface
     * between parts, the ends round; what is left is the track. A part at zero draws nothing.
     */
    @Composable
    private fun Segments(
        whole: Long, track: Color, surface: Color, parts: List<Pair<Long, Color>>, description: String, modifier: Modifier,
    ) {
        val all = maxOf(1, whole)
        Canvas(modifier.fillMaxWidth().semantics { contentDescription = description }) {
            val w = size.width
            val h = size.height
            val gap = 2.dp.toPx()
            val round = Path().apply { addRoundRect(RoundRect(0f, 0f, w, h, CornerRadius(h / 2))) }
            clipPath(round) {
                drawRect(track)
                var x = 0f
                val least = maxOf(gap * 1.5f, h / 3)
                for ((amount, color) in parts) {
                    if (amount <= 0) continue
                    val end = minOf(w, x + maxOf(least, w * amount / all))
                    val right = maxOf(x, end - if (end < w) gap else 0f)
                    drawRect(color, Offset(x, 0f), Size(right - x, h))
                    // The gap after each part shows the card's surface.
                    if (end < w) drawRect(surface, Offset(end - gap, 0f), Size(gap, h))
                    x = end
                    if (x >= w) break
                }
            }
        }
    }
}
