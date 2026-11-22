package com.jeep.claudecode

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.text.Layout
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ClickableSpan
import android.text.style.URLSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.Markwon
import io.noties.markwon.core.MarkwonTheme
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.ext.tables.TableTheme
import io.noties.markwon.movement.MovementMethodPlugin
import java.io.File
import java.util.regex.Pattern
import kotlin.math.max

/**
 * Markdown in native views: prose through Markwon, fenced code as cards with a copy button. A picture's
 * path in the userland (absolute, ~/…, or from the conversation's folder) is made a file by files.
 */
class Md(
    private val c: Context,
    private val copy: (String) -> Unit,
    private val openLink: (String) -> Unit,
    private val files: (String) -> File?,
) {
    private var markwon: Markwon = build()
    /** Bumped when the conversation font changes: text set before is set again. */
    private var fontGen = 0

    /** The conversation font changed: headings and text take it on their next update. */
    fun fontChanged() {
        markwon = build()
        fontGen++
    }

    private fun build(): Markwon {
        val table = TableTheme.buildWithDefaults(c)
            .tableBorderColor(Ui.color(c, R.color.border))
            .tableBorderWidth(Ui.dp(c, 1))
            .tableCellPadding(Ui.dp(c, 8))
            .tableHeaderRowBackgroundColor(Ui.color(c, R.color.surface2))
            .tableEvenRowBackgroundColor(Ui.color(c, R.color.bg))
            .tableOddRowBackgroundColor(Ui.color(c, R.color.bg))
            .build()
        return Markwon.builder(c)
            .usePlugin(StrikethroughPlugin.create())
            .usePlugin(TablePlugin.create(table))
            .usePlugin(Links())
            .usePlugin(MovementMethodPlugin.none()) // links: links(); no tap on one after a long press
            .usePlugin(object : AbstractMarkwonPlugin() {
                override fun configureTheme(b: MarkwonTheme.Builder) {
                    b.codeTextColor(Ui.color(c, R.color.inlineCode))
                        .codeBackgroundColor(Ui.color(c, R.color.surface2))
                        .codeTypeface(Ui.MONO)
                        .codeTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 14f, c.resources.displayMetrics).toInt())
                        .codeBlockTextColor(Ui.color(c, R.color.text))
                        .codeBlockBackgroundColor(Ui.color(c, R.color.codeBg))
                        .linkColor(Ui.color(c, R.color.accentPress))
                        .blockQuoteColor(Ui.color(c, R.color.borderStrong))
                        .blockMargin(Ui.dp(c, 18))
                        .bulletWidth(Ui.dp(c, 5))
                        .listItemColor(Ui.color(c, R.color.text2))
                        .headingBreakHeight(0)
                        .headingTypeface(Fonts.body(c))
                        .headingTextSizeMultipliers(floatArrayOf(1.4f, 1.25f, 1.12f, 1f, 1f, 1f))
                        .thematicBreakColor(Ui.color(c, R.color.border))
                }
            })
            .build()
    }

    /**
     * Fills the container with views for the reply text, reusing the views already there (a reply
     * being streamed keeps its views, their widths and the reader's place).
     */
    fun render(box: LinearLayout, text: String) {
        val parts = split(text)
        for (i in parts.indices) {
            val p = parts[i]
            val old = if (i < box.childCount) box.getChildAt(i) else null
            if (old != null && p[0] == old.tag) {
                when (p[0]) {
                    "code" -> updateCode(old, p[1], p[2])
                    "table" -> fillTable(old, p[1])
                    "image" -> if (p[1] != old.getTag(R.id.markdown)) {
                        box.removeViewAt(i)
                        box.addView(picture(p[1], p[2]), i)
                    }
                    else -> setProse(old as TextView, p[1])
                }
                continue
            }
            while (box.childCount > i) box.removeViewAt(i)
            val v = when (p[0]) {
                "code" -> code(p[1], p[2])
                "table" -> table(p[1])
                "image" -> picture(p[1], p[2])
                else -> prose(p[1])
            }
            if (i > 0) Ui.margins(v, c, 0, 14, 0, 0)
            box.addView(v)
        }
        while (box.childCount > parts.size) box.removeViewAt(parts.size)
    }

    /** Markdown as the text it shows, without its marks. */
    fun plain(markdown: String): String = markwon.toMarkdown(markdown.trim()).toString()

    fun prose(markdown: String): TextView {
        val t = TextView(c)
        t.tag = "text"
        t.layoutParams = Ui.fill()
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        t.setTextColor(Ui.color(c, R.color.text))
        t.setLineSpacing(0f, 1.05f)
        t.breakStrategy = Layout.BREAK_STRATEGY_SIMPLE
        links(t)
        setProse(t, markdown)
        return t
    }

    private fun setProse(t: TextView, markdown: String) {
        val md = markdown.trim()
        if (md == t.getTag(R.id.markdown) && fontGen == t.getTag(R.id.font)) return
        t.setTag(R.id.markdown, md)
        t.setTag(R.id.font, fontGen)
        t.typeface = Fonts.body(c)
        markwon.setMarkdown(t, md)
        if (t.width == 0 && md.contains("|")) {
            // Tables size their columns from the text's width: render again once that is known.
            t.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
                override fun onLayoutChange(v: View, l: Int, top: Int, r: Int, b: Int, ol: Int, ot: Int, or: Int, ob: Int) {
                    if (r - l == 0) return
                    t.removeOnLayoutChangeListener(this)
                    t.post { markwon.setMarkdown(t, t.getTag(R.id.markdown) as String) }
                }
            })
        }
    }

    private fun table(markdown: String): View {
        val scroll = HorizontalScrollView(c)
        scroll.tag = "table"
        scroll.isHorizontalScrollBarEnabled = false
        scroll.isFillViewport = true
        scroll.layoutParams = Ui.fill()
        val t = TableLayout(c)
        t.isStretchAllColumns = true
        t.setBackgroundColor(Ui.color(c, R.color.border))
        val line = max(1, Ui.dp(c, 1))
        t.setPadding(line, line, 0, 0)
        scroll.addView(t, FrameLayout.LayoutParams(-1, -2))
        fillTable(scroll, markdown)
        return scroll
    }

    /** Rows of cells, each measured as it is laid out: the same height on every update. */
    private fun fillTable(scroll: View, markdown: String) {
        if (markdown == scroll.getTag(R.id.markdown) && fontGen == scroll.getTag(R.id.font)) return
        scroll.setTag(R.id.markdown, markdown)
        scroll.setTag(R.id.font, fontGen)
        val t = (scroll as ViewGroup).getChildAt(0) as TableLayout
        val lines = markdown.split("\n")
        val aligns = cells(lines[1])
        val line = max(1, Ui.dp(c, 1))
        val maxCell = (c.resources.displayMetrics.widthPixels * 0.7f).toInt()
        var r = 0
        for (i in lines.indices) {
            if (i == 1) continue
            val cells = cells(lines[i])
            val row = (if (r < t.childCount) t.getChildAt(r) as TableRow else null) ?: TableRow(c).also { t.addView(it) }
            val n = max(cells.size, aligns.size)
            for (k in 0 until n) {
                val cell = (if (k < row.childCount) row.getChildAt(k) as TextView else null) ?: TextView(c).also { cell ->
                    cell.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                    cell.setTextColor(Ui.color(c, R.color.text))
                    cell.setLineSpacing(0f, 1.1f)
                    cell.maxWidth = maxCell
                    cell.setPadding(Ui.dp(c, 10), Ui.dp(c, 7), Ui.dp(c, 10), Ui.dp(c, 7))
                    cell.setBackgroundColor(Ui.color(c, if (i == 0) R.color.surface2 else R.color.bg))
                    links(cell)
                    val lp = TableRow.LayoutParams(-2, -1)
                    lp.setMargins(0, 0, line, line)
                    row.addView(cell, lp)
                }
                val a = if (k < aligns.size) aligns[k] else ""
                cell.gravity = if (a.startsWith(":") && a.endsWith(":")) Gravity.CENTER_HORIZONTAL
                    else if (a.endsWith(":")) Gravity.END else Gravity.START
                val md = if (k < cells.size) cells[k] else ""
                cell.typeface = if (i == 0) Typeface.create(Fonts.body(c), Typeface.BOLD) else Fonts.body(c)
                if (md != cell.getTag(R.id.markdown) || fontGen != cell.getTag(R.id.font)) {
                    cell.setTag(R.id.font, fontGen)
                    cell.setTag(R.id.markdown, md)
                    cell.text = cellText(md)
                }
            }
            while (row.childCount > n) row.removeViewAt(n)
            r++
        }
        while (t.childCount > r) t.removeViewAt(r)
    }

    /** What a table cell shows: its Markdown rendered, without the line ends after it. */
    private fun cellText(markdown: String): CharSequence {
        val styled = markwon.toMarkdown(markdown)
        var end = styled.length
        while (end > 0 && Character.isWhitespace(styled[end - 1])) end--
        return styled.subSequence(0, end)
    }

    /**
     * The texts /find looks in for a reply, one for each of its views that takes marks and in the order
     * of the views (render): prose as it shows, not as it was written (a link's address, the language
     * after a fence and a table's rules are not on the screen), the code, and each cell of a table.
     */
    fun findable(text: String): List<String> {
        val out = ArrayList<String>()
        for (p in split(text)) {
            when (p[0]) {
                "code" -> out.add(p[1])
                "table" -> {
                    val lines = p[1].split("\n")
                    val aligned = cells(lines[1]).size
                    for (i in lines.indices) {
                        if (i == 1) continue
                        val cells = cells(lines[i])
                        for (k in 0 until max(cells.size, aligned)) out.add(cellText(if (k < cells.size) cells[k] else "").toString())
                    }
                }
                "image" -> {}
                else -> out.add(plain(p[1]))
            }
        }
        return out
    }

    /**
     * A picture in the reply: a file Claude made or a web address; a tap shows it whole. One on the
     * web is fetched only when tapped: what a reply shows must not send what Claude read anywhere.
     */
    private fun picture(where: String, alt: String): View {
        val box = Ui.column(c)
        box.tag = "image"
        box.setTag(R.id.markdown, where)
        box.layoutParams = Ui.fill()
        if (where.startsWith("http://") || where.startsWith("https://")) {
            val ask = Ui.row(c)
            ask.setPadding(Ui.dp(c, 12), Ui.dp(c, 10), Ui.dp(c, 14), Ui.dp(c, 10))
            ask.background = Ui.press(c, Ui.round(Ui.color(c, R.color.surface2), Ui.dp(c, 12), Ui.dp(c, 1), Ui.color(c, R.color.border)), Ui.dp(c, 12))
            ask.addView(Ui.icon(c, R.drawable.ic_image, 17, R.color.text2))
            val host = Uri.parse(where).host
            val name = Ui.text(c, if (alt.trim().isEmpty()) host ?: where else alt.trim(), 14, R.color.text2)
            name.isSingleLine = true
            name.ellipsize = TextUtils.TruncateAt.END
            name.setPadding(Ui.dp(c, 8), 0, 0, 0)
            ask.addView(name)
            ask.setOnClickListener {
                box.removeAllViews()
                show(box, where, where, null, alt)
            }
            box.addView(ask, Ui.wrap())
            return box
        }
        val data = where.startsWith("data:")
        show(box, where, if (data) where else null, if (data) null else files(if (where.startsWith("file://")) where.substring(7) else where), alt)
        return box
    }

    private fun show(box: LinearLayout, where: String, src: String?, file: File?, alt: String) {
        val img = ImageView(c)
        img.adjustViewBounds = true
        img.scaleType = ImageView.ScaleType.FIT_START
        img.maxHeight = (c.resources.displayMetrics.heightPixels * 0.6f).toInt()
        img.background = Ui.round(Ui.color(c, R.color.surface2), Ui.dp(c, 12), Ui.dp(c, 1), Ui.color(c, R.color.border))
        img.clipToOutline = true
        img.contentDescription = alt
        img.setOnClickListener { Images.show(c, src, file) }
        box.addView(img, Ui.wrap())
        Images.load(img, src, file, c.resources.displayMetrics.widthPixels) {
            box.removeAllViews()
            val t = Ui.text(c, "图片打不开：$where", 13, R.color.text3)
            t.typeface = Ui.MONO
            box.addView(t)
        }
    }

    private fun updateCode(card: View, code: String, lang: String) {
        val name = card.findViewWithTag<TextView>("lang")
        val body = card.findViewWithTag<TextView>("body")
        name.text = lang.ifEmpty { "代码" }
        if (!code.contentEquals(body.text)) body.text = Highlight.code(c, code, lang)
        card.setTag(R.id.markdown, code)
    }

    private fun code(code: String, lang: String): View {
        val card = Ui.column(c)
        card.tag = "code"
        card.setTag(R.id.markdown, code)
        card.layoutParams = Ui.fill()
        card.background = Ui.round(Ui.color(c, R.color.codeBg), Ui.dp(c, 12), Ui.dp(c, 1), Ui.color(c, R.color.border))
        card.clipToOutline = true
        val head = Ui.row(c)
        head.setPadding(Ui.dp(c, 12), 0, Ui.dp(c, 4), 0)
        head.minimumHeight = Ui.dp(c, 34)
        val name = Ui.text(c, lang.ifEmpty { "代码" }, 12.5f, R.color.text3)
        name.tag = "lang"
        head.addView(name, Ui.weight())
        val copyBtn = Ui.row(c)
        copyBtn.setPadding(Ui.dp(c, 8), Ui.dp(c, 5), Ui.dp(c, 8), Ui.dp(c, 5))
        copyBtn.background = Ui.press(c, null, Ui.dp(c, 7))
        copyBtn.addView(Ui.icon(c, R.drawable.ic_copy, 14, R.color.text2))
        val label = Ui.text(c, "复制", 12.5f, R.color.text2)
        label.setPadding(Ui.dp(c, 5), 0, 0, 0)
        copyBtn.addView(label)
        copyBtn.setOnClickListener { copy(card.getTag(R.id.markdown) as String) }
        head.addView(copyBtn)
        card.addView(head, Ui.fill())
        val line = View(c)
        line.setBackgroundColor(Ui.color(c, R.color.border))
        card.addView(line, LinearLayout.LayoutParams(-1, Ui.dp(c, 1)))
        val scroll = HorizontalScrollView(c)
        scroll.isHorizontalScrollBarEnabled = false
        val body = Ui.text(c, Highlight.code(c, code, lang), 13, R.color.text)
        body.tag = "body"
        body.setTag(R.id.findable, true)
        body.typeface = Ui.MONO
        body.setLineSpacing(0f, 1.12f)
        body.setPadding(Ui.dp(c, 14), Ui.dp(c, 12), Ui.dp(c, 14), Ui.dp(c, 12))
        body.setHorizontallyScrolling(true)
        // Wrap-content: a TextView whose width is fixed is not measured again when its text
        // grows within one line, so a streamed one-line block kept the width of its first letters.
        scroll.addView(body, FrameLayout.LayoutParams(-2, -2))
        card.addView(scroll, Ui.fill())
        return card
    }

    /** Links that open with a tap. The text is not selectable: a long press is the thread's (its menu). */
    fun links(t: TextView) {
        t.setTag(R.id.findable, true)
        t.setOnTouchListener { _, e ->
            val text = t.text
            if (e.action != MotionEvent.ACTION_UP || text !is Spanned) return@setOnTouchListener false
            if (e.eventTime - e.downTime > 350) return@setOnTouchListener false
            val layout = t.layout ?: return@setOnTouchListener false
            val x = e.x.toInt() - t.totalPaddingLeft + t.scrollX
            val y = e.y.toInt() - t.totalPaddingTop + t.scrollY
            val line = layout.getLineForVertical(y)
            if (x < layout.getLineLeft(line) || x > layout.getLineRight(line)) return@setOnTouchListener false
            val off = layout.getOffsetForHorizontal(line, x.toFloat())
            val links = text.getSpans(off, off, ClickableSpan::class.java)
            if (links.isEmpty()) return@setOnTouchListener false
            t.cancelLongPress() // the up is taken here: the menu must not open after the tap
            t.isPressed = false
            val first = links[0]
            if (first is URLSpan) openLink(first.url) else first.onClick(t)
            true
        }
    }

    companion object {
        private val FENCE = Pattern.compile("^\\s*(`{3,}|~{3,})\\s*([^`\\s]*).*$")
        /** A table's second line: |---|:--:| */
        private val TABLE_RULE = Pattern.compile("^\\s*\\|?\\s*:?-+:?\\s*(\\|\\s*:?-*:?\\s*)*\\|?\\s*$")
        /** A line that is only a picture: ![alt](where "title") */
        private val IMAGE = Pattern.compile("^\\s*!\\[([^\\]]*)\\]\\(\\s*<?([^)>\\s]+)>?(?:\\s+[\"'][^\"']*[\"'])?\\s*\\)\\s*$")

        /**
         * Text, code and table segments of a reply; an unclosed fence (still streaming) is code.
         * Tables are views of their own: Markwon's measure their rows after drawing, so a table being
         * streamed changed height on every update and shook the list.
         */
        @JvmStatic
        fun split(text: String): List<Array<String>> {
            val out = ArrayList<Array<String>>()
            val prose = StringBuilder()
            fun flush() {
                if (prose.toString().trim().isNotEmpty()) out.add(arrayOf("text", prose.toString()))
                prose.setLength(0)
            }
            val lines = text.split("\n")
            var i = 0
            while (i < lines.size) {
                val picture = if (lines[i].contains("![")) IMAGE.matcher(lines[i]) else null
                if (picture != null && picture.matches()) {
                    flush()
                    out.add(arrayOf("image", picture.group(2)!!, picture.group(1)!!))
                    i++
                    continue
                }
                if (lines[i].contains("|") && i + 1 < lines.size && lines[i + 1].contains("-") &&
                    TABLE_RULE.matcher(lines[i + 1]).matches()) {
                    val table = StringBuilder(lines[i]).append('\n').append(lines[i + 1])
                    var j = i + 2
                    while (j < lines.size && lines[j].contains("|") && lines[j].trim().isNotEmpty()) {
                        table.append('\n').append(lines[j])
                        j++
                    }
                    flush()
                    out.add(arrayOf("table", table.toString()))
                    i = j
                    continue
                }
                val m = FENCE.matcher(lines[i])
                if (!m.matches()) {
                    prose.append(lines[i]).append('\n')
                    i++
                    continue
                }
                val fence = m.group(1)!!
                val lang = m.group(2)!!
                val code = StringBuilder()
                var j = i + 1
                while (j < lines.size) {
                    if (lines[j].trim().startsWith(fence) && lines[j].trim().replace(fence.substring(0, 1), "").isEmpty()) break
                    if (code.isNotEmpty()) code.append('\n')
                    code.append(lines[j])
                    j++
                }
                flush()
                out.add(arrayOf("code", code.toString(), lang))
                i = j + 1
            }
            flush()
            return out
        }

        /** A table line's cells: | a | b | → [a, b]; \| stays in its cell. */
        private fun cells(line: String): List<String> {
            var s = line.trim()
            if (s.startsWith("|")) s = s.substring(1)
            if (s.endsWith("|") && !s.endsWith("\\|")) s = s.substring(0, s.length - 1)
            return s.split(Regex("(?<!\\\\)\\|")).map { it.trim().replace("\\|", "|") }
        }
    }
}
