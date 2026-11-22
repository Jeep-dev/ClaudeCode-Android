package com.jeep.claudecode

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Paint
import android.graphics.Typeface
import android.os.SystemClock
import android.text.Editable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.TextWatcher
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.ActionMode
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.widget.NestedScrollView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import org.json.JSONArray
import org.json.JSONObject

/** The conversation as a list: one row per user message, note, reply block and status. A row opened or closed is told to changed, with its view. */
class ThreadAdapter(
    private val c: Context,
    private val md: Md,
    private val changed: (View) -> Unit,
) : RecyclerView.Adapter<ThreadAdapter.Holder>() {

    /** What the queued messages' buttons do. */
    interface Queue {
        fun edit(q: Chat.Queued)
        fun cancel(q: Chat.Queued)
        fun sendNow(q: Chat.Queued)
    }

    class Item(
        @JvmField val type: Int,
        @JvmField val id: Long,
        @JvmField val sig: Long,
        @JvmField val msg: Chat.Msg?,
        @JvmField val block: Chat.Block?,
        @JvmField val gap: Int,
    ) {
        @JvmField var queued: Chat.Queued? = null
    }

    @JvmField var queue: Queue? = null
    /** Answers Claude Code's question whether a tool may run. */
    @JvmField var asks: ((Chat, Chat.Block, Boolean, JSONObject?) -> Unit)? = null
    /** Told of each row once it is bound or its text rendered again (marks what /find looks for). */
    @JvmField var marker: ((View, Int) -> Unit)? = null
    /** A message was pressed long: its menu (copy, select, delete…). */
    @JvmField var menu: ((Chat.Msg) -> Unit)? = null
    /** Translates text selected in thinking (the model's, in English): its 翻译 beside 复制. */
    @JvmField var translate: ((String) -> Unit)? = null
    private var items: List<Item> = ArrayList()
    private var chat: Chat? = null
    @JvmField val statusRows = ArrayList<Holder>()

    /** The row being opened: its body grows out of its head once built (grow). */
    private var growing: Chat.Block? = null
    /**
     * The row opened last (its box): the list keeps it in sight as it grows (opening, a picture or
     * a result coming in) until the list is moved otherwise (ChatScreen lets go of it).
     */
    @JvmField var opened: View? = null
    /** A row moving open or closed: taps on it meanwhile wait. */
    private val moving = HashSet<Chat.Block>()

    init {
        setHasStableIds(true)
    }

    /** Rebuilds the rows for the chat and updates only what changed. */
    fun show(chat: Chat, reset: Boolean) {
        this.chat = chat
        val next = flatten(chat, Themes.showThinking(c))
        if (reset) {
            opened = null
            items = next
            notifyDataSetChanged()
            return
        }
        val prev = items
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize(): Int = prev.size
            override fun getNewListSize(): Int = next.size
            override fun areItemsTheSame(o: Int, n: Int): Boolean = prev[o].id == next[n].id
            override fun areContentsTheSame(o: Int, n: Int): Boolean = prev[o].sig == next[n].sig && prev[o].gap == next[n].gap
        }, false)
        items = next
        diff.dispatchUpdatesTo(this)
    }

    /** Whether /find looks in a row: the user's messages and the replies' text, not the rest. */
    fun searchable(position: Int): Boolean {
        if (position < 0 || position >= items.size) return false
        val type = items[position].type
        return type == USER || type == TEXT
    }

    /** The texts /find looks in for a row, as its views show them (Md.findable); null for a row it does not. */
    fun searchText(position: Int): List<String>? {
        if (!searchable(position)) return null
        val it = items[position]
        return if (it.type == USER) listOf(it.msg!!.text) else md.findable(it.block!!.text)
    }

    override fun getItemCount(): Int = items.size

    override fun getItemId(position: Int): Long = items[position].id

    override fun getItemViewType(position: Int): Int = items[position].type

    class Holder(@JvmField val box: LinearLayout) : RecyclerView.ViewHolder(box) {
        @JvmField var item: Item? = null
        // status row parts
        @JvmField var glyph: TextView? = null
        @JvmField var verb: TextView? = null
        @JvmField var elapsed: TextView? = null
        @JvmField var detail: TextView? = null
        // thinking row parts, updated in place while it streams
        @JvmField var thinkLabel: TextView? = null
        @JvmField var thinkBody: TextView? = null
        @JvmField var thinkScroll: MaxScroll? = null
        @JvmField var thinkOpen = false
        // reply text: when it may be rendered again while it streams
        @JvmField var nextRender = 0L
        @JvmField var renderPending = false
    }

    override fun onCreateViewHolder(parent: ViewGroup, type: Int): Holder {
        val box = Ui.column(c)
        box.layoutParams = RecyclerView.LayoutParams(-1, -2)
        return Holder(box)
    }

    override fun onBindViewHolder(h: Holder, position: Int) {
        bind(h, position)
        marker?.invoke(h.box, position)
    }

    private fun bind(h: Holder, position: Int) {
        val it = items[position]
        val before = h.item
        val same = before != null && before.id == it.id && before.type == it.type
        if (!same && opened === h.box) opened = null // another row in its box now
        h.item = it
        if (same && it.type == TEXT) { // the same reply text, grown: update its views in place
            h.box.setPadding(0, Ui.dp(c, it.gap), 0, 0)
            text(h, it)
            return
        }
        val block = it.block
        if (same && it.type == THINKING && block != null && h.thinkOpen == block.open) { // same: no rebuild, no jump
            h.box.setPadding(0, Ui.dp(c, it.gap), 0, 0)
            h.thinkLabel?.text = thinkingLabel(block)
            val text = thinkingText(block)
            val body = h.thinkBody
            if (body != null && !text.contentEquals(body.text)) {
                body.text = text // the box follows the end unless scrolled up by hand
            }
            return
        }
        h.box.removeAllViews()
        h.box.setOnClickListener(null) // the status row's; a reused box must not keep it
        h.box.isClickable = false
        h.box.setPadding(0, Ui.dp(c, it.gap), 0, 0)
        h.box.gravity = Gravity.NO_GRAVITY
        statusRows.remove(h)
        when (it.type) {
            USER -> user(h.box, it.msg!!)
            NOTE -> note(h.box, it.msg!!)
            DIVIDER -> divider(h.box, it.msg!!)
            TEXT -> {
                h.nextRender = 0
                text(h, it)
            }
            THINKING -> thinking(h, block!!)
            TOOL -> tool(h.box, block!!)
            ERROR -> error(h.box, block!!)
            STOPPED -> h.box.addView(Ui.text(c, "已停止", 13.5f, R.color.text3))
            STATUS -> status(h)
            QUEUED -> queued(h.box, it.queued!!)
        }
        if (block != null && block === growing) {
            growing = null
            grow(h.box, block)
        }
    }

    override fun onViewRecycled(h: Holder) {
        statusRows.remove(h)
        if (opened === h.box) opened = null
    }

    /**
     * A reply's text, rendered again as it grows. A long one costs more each time: while it
     * streams, it is rendered again only after a pause three times as long as the last rendering
     * took, so the screen keeps up however long it gets. The last version is always rendered.
     */
    private fun text(h: Holder, it: Item) {
        val now = SystemClock.uptimeMillis()
        val msg = it.msg!!
        if (!msg.done && now < h.nextRender) {
            if (!h.renderPending) {
                h.renderPending = true
                h.box.postDelayed({
                    h.renderPending = false
                    val again = h.item
                    if (again != null && again.type == TEXT) text(h, again)
                }, h.nextRender - now)
            }
            return
        }
        md.render(h.box, it.block!!.text)
        pressable(h.box, msg)
        if (h.bindingAdapterPosition != RecyclerView.NO_POSITION) marker?.invoke(h.box, h.bindingAdapterPosition)
        val end = SystemClock.uptimeMillis()
        h.nextRender = end + 3 * (end - now)
    }

    // ---- rows ----

    private fun user(box: LinearLayout, m: Chat.Msg) {
        box.gravity = Gravity.END
        val max = (c.resources.displayMetrics.widthPixels * 0.78f).toInt()
        if (m.images.isNotEmpty()) {
            // More pictures than fit across scroll sideways, from the first.
            val scroll = HorizontalScrollView(c)
            scroll.isHorizontalScrollBarEnabled = false
            val row = Ui.row(c)
            row.gravity = Gravity.END
            scroll.addView(row)
            for (src in m.images) {
                val img = ImageView(c)
                img.scaleType = ImageView.ScaleType.CENTER_CROP
                img.background = Ui.round(Ui.color(c, R.color.surface2), Ui.dp(c, 14), Ui.dp(c, 1), Ui.color(c, R.color.border))
                img.clipToOutline = true
                if (src != null) {
                    Images.load(img, src, null, Ui.dp(c, 224), null)
                    img.setOnClickListener { Images.show(c, src, null) }
                }
                val p = LinearLayout.LayoutParams(Ui.dp(c, 112), Ui.dp(c, 112))
                p.leftMargin = Ui.dp(c, 6)
                row.addView(img, p)
            }
            box.addView(scroll, Ui.wrap())
            Ui.margins(scroll, c, 0, 0, 0, 6)
        }
        for (f in m.files) {
            val chip = Ui.row(c)
            chip.setPadding(Ui.dp(c, 12), Ui.dp(c, 8), Ui.dp(c, 12), Ui.dp(c, 8))
            chip.background = Ui.round(Ui.color(c, R.color.surface), Ui.dp(c, 14), Ui.dp(c, 1), Ui.color(c, R.color.border))
            chip.addView(Ui.icon(c, R.drawable.ic_file, 17, R.color.text2))
            val name = Ui.text(c, f, 14, R.color.text)
            name.isSingleLine = true
            name.ellipsize = TextUtils.TruncateAt.MIDDLE
            name.maxWidth = max - Ui.dp(c, 50)
            name.setPadding(Ui.dp(c, 8), 0, 0, 0)
            chip.addView(name)
            box.addView(chip, Ui.wrap())
            Ui.margins(chip, c, 0, 0, 0, 6)
        }
        if (m.text.isNotEmpty()) {
            val bubble = Ui.text(c, m.text, 16, R.color.text)
            bubble.typeface = Fonts.body(c)
            bubble.includeFontPadding = true
            bubble.setLineSpacing(0f, 1.15f)
            bubble.maxWidth = max
            bubble.setPadding(Ui.dp(c, 15), Ui.dp(c, 10), Ui.dp(c, 15), Ui.dp(c, 10))
            val bg = Ui.round(Ui.color(c, R.color.user), 0)
            val r = Ui.dp(c, 20).toFloat()
            val s = Ui.dp(c, 6).toFloat()
            bg.cornerRadii = floatArrayOf(r, r, r, r, s, s, r, r)
            bubble.background = bg
            bubble.setTag(R.id.findable, true)
            pressable(bubble, m)
            box.addView(bubble, Ui.wrap())
        }
    }

    /** A message waiting for the turn to end: its text, dimmed, and edit, cancel and send-now. */
    private fun queued(box: LinearLayout, q: Chat.Queued) {
        box.gravity = Gravity.END
        val max = (c.resources.displayMetrics.widthPixels * 0.78f).toInt()
        val text = StringBuilder(q.text)
        for (a in q.attachments) text.append(if (text.isNotEmpty()) "\n" else "").append(if (a.image) "[图片] " else "[文件] ").append(a.name)
        val bubble = Ui.text(c, text, 16, R.color.text2)
        bubble.includeFontPadding = true
        bubble.setLineSpacing(0f, 1.15f)
        bubble.maxWidth = max
        bubble.setPadding(Ui.dp(c, 15), Ui.dp(c, 10), Ui.dp(c, 15), Ui.dp(c, 10))
        bubble.background = Ui.round(0, Ui.dp(c, 20), Ui.dp(c, 1), Ui.color(c, R.color.borderStrong))
        box.addView(bubble, Ui.wrap())
        val row = Ui.row(c)
        row.gravity = Gravity.END or Gravity.CENTER_VERTICAL
        if (q.now) {
            row.addView(Ui.text(c, "停止当前回复后发送…", 13, R.color.text3))
        } else {
            val edit = Ui.iconButton(c, R.drawable.ic_pencil, 34, 17, R.color.text3)
            edit.setOnClickListener { queue?.edit(q) }
            row.addView(edit, LinearLayout.LayoutParams(Ui.dp(c, 34), Ui.dp(c, 34)))
            val cancel = Ui.iconButton(c, R.drawable.ic_x, 34, 17, R.color.text3)
            cancel.setOnClickListener { queue?.cancel(q) }
            row.addView(cancel, LinearLayout.LayoutParams(Ui.dp(c, 34), Ui.dp(c, 34)))
            val now = Ui.text(c, "立即发送", 13.5f, R.color.text2)
            now.setPadding(Ui.dp(c, 10), Ui.dp(c, 7), Ui.dp(c, 4), Ui.dp(c, 7))
            now.setOnClickListener { queue?.sendNow(q) }
            row.addView(now)
        }
        box.addView(row, Ui.wrap())
        Ui.margins(row, c, 0, 4, 0, 0)
    }

    private fun note(box: LinearLayout, m: Chat.Msg) {
        box.gravity = Gravity.CENTER_HORIZONTAL
        val t = Ui.text(c, m.note, 14, R.color.text2)
        t.setLineSpacing(0f, 1.3f)
        t.setPadding(Ui.dp(c, 14), Ui.dp(c, 10), Ui.dp(c, 14), Ui.dp(c, 10))
        t.background = Ui.round(Ui.color(c, R.color.surface2), Ui.dp(c, 14), Ui.dp(c, 1), Ui.color(c, R.color.border))
        t.setTextIsSelectable(true)
        box.addView(t, Ui.wrap())
    }

    private fun divider(box: LinearLayout, m: Chat.Msg) {
        val row = Ui.row(c)
        val a = View(c)
        val b = View(c)
        a.setBackgroundColor(Ui.color(c, R.color.border))
        b.setBackgroundColor(Ui.color(c, R.color.border))
        row.addView(a, LinearLayout.LayoutParams(0, Ui.dp(c, 1), 1f))
        val t = Ui.text(c, m.text, 12.5f, R.color.text3)
        t.setPadding(Ui.dp(c, 10), 0, Ui.dp(c, 10), 0)
        row.addView(t)
        row.addView(b, LinearLayout.LayoutParams(0, Ui.dp(c, 1), 1f))
        box.addView(row, Ui.fill())
    }

    private fun head(icon: Int, boxed: Boolean): LinearLayout {
        val head = Ui.row(c)
        head.minimumHeight = Ui.dp(c, 32)
        head.setPadding(Ui.dp(c, 6), Ui.dp(c, 2), Ui.dp(c, 8), Ui.dp(c, 2))
        head.background = Ui.press(c, null, Ui.dp(c, 10))
        val lp = Ui.fill()
        lp.leftMargin = -Ui.dp(c, 6)
        head.layoutParams = lp
        if (boxed) {
            val frame = FrameLayout(c)
            frame.background = Ui.round(Ui.color(c, R.color.surface), Ui.dp(c, 8), Ui.dp(c, 1), Ui.color(c, R.color.border))
            val i = Ui.icon(c, icon, 16, R.color.text2)
            frame.addView(i, FrameLayout.LayoutParams(Ui.dp(c, 16), Ui.dp(c, 16), Gravity.CENTER))
            head.addView(frame, LinearLayout.LayoutParams(Ui.dp(c, 24), Ui.dp(c, 24)))
        } else if (icon != 0) {
            head.addView(Ui.icon(c, icon, 17, R.color.text2))
        }
        return head
    }

    private fun chevron(open: Boolean): ImageView {
        val v = Ui.icon(c, R.drawable.ic_chevron_right, 16, R.color.text3)
        v.rotation = if (open) 90f else 0f
        Ui.margins(v, c, 6, 0, 0, 0)
        return v
    }

    /** Whether a row is growing open or shrinking shut just now (its height is not its own); for a status row, the thinking it opens. */
    fun moving(h: RecyclerView.ViewHolder): Boolean {
        if (h !is Holder) return false
        val b = if (statusRows.contains(h)) streamingThinking() else h.item?.block
        return b != null && moving.contains(b)
    }

    /** Opens or closes a row: its height moves between the head alone and the whole, the chevron turns. */
    private fun toggle(b: Chat.Block, row: View) {
        if (moving.contains(b)) return
        opened = null // a row opened is followed from when it grows (grow), not one before it
        if (!b.open) {
            growing = b
            b.open = true
            changed(row)
            return
        }
        val box = row as ViewGroup
        val head = box.getChildAt(0)
        val from = box.height
        val to = head.bottom + box.paddingBottom
        turn(head, 90f, 0f)
        if (from <= to) {
            b.open = false
            changed(row)
            return
        }
        moving.add(b)
        resize(box, from, to) {
            moving.remove(b)
            b.open = false
            changed(row)
        }
    }

    /** A row just opened: built whole, it is drawn from its head's height down to that. */
    private fun grow(box: LinearLayout, b: Chat.Block) {
        if (box.childCount == 0) return
        val head = box.getChildAt(0)
        turn(head, 0f, 90f)
        moving.add(b)
        box.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                box.viewTreeObserver.removeOnPreDrawListener(this)
                opened = box
                val to = box.height
                val from = head.bottom + box.paddingBottom
                if (to <= from) {
                    moving.remove(b)
                    return true
                }
                for (i in 1 until box.childCount) {
                    val v = box.getChildAt(i)
                    v.alpha = 0f
                    v.animate().alpha(1f).setDuration(Motion.PAGE).setInterpolator(Motion.EMPHASIZED).start()
                }
                resize(box, from, to) {
                    moving.remove(b)
                    changed(box) // where it ends: the list follows it as an opened row
                }
                return false // this frame was whole: the next starts at the head's height
            }
        })
    }

    private fun thinking(h: Holder, b: Chat.Block) {
        val box = h.box
        // The words and the chevron, no icon.
        val head = head(0, false)
        val t = Ui.text(c, thinkingLabel(b), 14.5f, R.color.text2)
        h.thinkLabel = t
        h.thinkOpen = b.open
        h.thinkBody = null
        h.thinkScroll = null
        head.addView(t, Ui.weight())
        head.addView(chevron(b.open))
        head.setOnClickListener { v -> toggle(b, v.parent as View) }
        box.addView(head)
        if (!b.open) return
        val scroll = MaxScroll(c, Ui.dp(c, 300))
        val body = Ui.text(c, thinkingText(b), 14.5f, R.color.text2)
        body.typeface = Fonts.body(c)
        body.setLineSpacing(0f, 1.2f)
        body.setTextIsSelectable(true)
        body.customSelectionActionModeCallback = object : ActionMode.Callback {
            override fun onCreateActionMode(mode: ActionMode, m: Menu): Boolean {
                if (translate != null) m.add(Menu.NONE, TRANSLATE, 100, "翻译")
                return true
            }

            override fun onPrepareActionMode(mode: ActionMode, m: Menu): Boolean = false

            override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
                if (item.itemId != TRANSLATE) return false
                val a = min(body.selectionStart, body.selectionEnd)
                val z = max(body.selectionStart, body.selectionEnd)
                if (a in 0 until z) translate?.invoke(body.text.subSequence(a, z).toString())
                mode.finish()
                return true
            }

            override fun onDestroyActionMode(mode: ActionMode) {}
        }
        scroll.addView(body)
        h.thinkBody = body
        h.thinkScroll = scroll
        val wrap = Ui.row(c)
        val bar = View(c)
        bar.setBackgroundColor(Ui.color(c, R.color.border))
        wrap.addView(bar, LinearLayout.LayoutParams(Ui.dp(c, 2), -1))
        wrap.addView(scroll, LinearLayout.LayoutParams(0, -2, 1f))
        scroll.setPadding(Ui.dp(c, 14), Ui.dp(c, 2), Ui.dp(c, 6), Ui.dp(c, 2))
        box.addView(wrap, Ui.fill())
        Ui.margins(wrap, c, 3, 4, 0, 2)
        scroll.follow = b.streaming
    }

    private fun tool(box: LinearLayout, b: Chat.Block) {
        val info = toolInfo(b)
        // The row shows what the step does; opened, the tool's name heads the command, as in
        // Claude Code's terminal.
        val head = head(iconRes(info[0]), true)
        val detail = Ui.text(c, info[2], 14.5f, R.color.text)
        if (!b.open) { // opened, it is shown whole
            detail.isSingleLine = true
            detail.ellipsize = TextUtils.TruncateAt.END
        }
        detail.setPadding(Ui.dp(c, 9), 0, 0, 0)
        head.addView(detail, Ui.weight())
        val state: View
        if (b.running) {
            val spin = ProgressBar(c)
            spin.indeterminateTintList = ColorStateList.valueOf(Ui.color(c, R.color.accent))
            state = spin
            state.layoutParams = LinearLayout.LayoutParams(Ui.dp(c, 15), Ui.dp(c, 15))
        } else {
            state = Ui.icon(c, if (b.error) R.drawable.ic_x else R.drawable.ic_check, 15, if (b.error) R.color.danger else R.color.ok)
        }
        if (b.images.isNotEmpty() && !b.open) {
            // A picture it read: shown small at the end of the row, whole when opened.
            val src = b.images[0]
            val thumb = ImageView(c)
            thumb.scaleType = ImageView.ScaleType.CENTER_CROP
            thumb.background = Ui.round(Ui.color(c, R.color.surface2), Ui.dp(c, 8), Ui.dp(c, 1), Ui.color(c, R.color.border))
            thumb.clipToOutline = true
            Images.load(thumb, src, null, Ui.dp(c, 56), null)
            thumb.setOnClickListener { Images.show(c, src, null) }
            head.addView(thumb, LinearLayout.LayoutParams(Ui.dp(c, 28), Ui.dp(c, 28)))
            Ui.margins(thumb, c, 8, 0, 0, 0)
        }
        Ui.margins(state, c, 8, 0, 0, 0)
        head.addView(state)
        head.addView(chevron(b.open))
        head.setOnClickListener { v -> toggle(b, v.parent as View) }
        box.addView(head)
        if (b.name == "TodoWrite") todos(box, b)
        // Claude Code asks the user something: its questions to answer, not whether it may run.
        if (b.name == "AskUserQuestion" && b.ask != null) {
            questions(box, b)
            return
        }
        // Asked whether it may run: what it would do is shown, and the answer.
        if (b.open || b.ask != null) toolBody(box, b)
        if (b.ask != null) ask(box, b)
    }

    /** A button of a question's or a permission's answers: filled when it is the answer, else outlined. */
    private fun answerButton(label: String, filled: Boolean): TextView {
        val t = Ui.text(c, label, 14.5f, if (filled) R.color.bg else R.color.text)
        t.gravity = Gravity.CENTER
        t.setPadding(Ui.dp(c, 18), 0, Ui.dp(c, 18), 0)
        t.minHeight = Ui.dp(c, 36)
        t.background = Ui.press(c, if (filled) Ui.round(Ui.color(c, R.color.text), Ui.dp(c, 10))
            else Ui.round(Ui.color(c, R.color.surface), Ui.dp(c, 10), Ui.dp(c, 1), Ui.color(c, R.color.borderStrong)), Ui.dp(c, 10))
        return t
    }

    /**
     * Claude Code's questions to the user (AskUserQuestion): each with its options to pick (one, or
     * several where it says so), a line for an answer of one's own (其他), then 跳过 or 提交. What is
     * picked stays on the block, so the row drawn again keeps it.
     */
    private fun questions(box: LinearLayout, b: Chat.Block) {
        val qs = b.input.optJSONArray("questions")
        if (qs == null || qs.length() == 0) {
            ask(box, b)
            return
        }
        val all = Ui.column(c)
        val repaint = ArrayList<() -> Unit>()
        for (qi in 0 until qs.length()) {
            val q = qs.optJSONObject(qi) ?: continue
            val multi = q.optBoolean("multiSelect")
            val picked = b.picked.getOrPut(qi) { LinkedHashSet() }
            val header = q.optString("header")
            if (header.isNotEmpty()) {
                val h = Ui.text(c, header, 12.5f, R.color.text3)
                all.addView(h)
                Ui.margins(h, c, 0, if (qi == 0) 0 else 16, 0, 2)
            }
            val asked = q.optString("question")
            // Several may be picked: said so, unless the question says it already.
            val question = Ui.text(c, asked + if (multi && !asked.contains("多选")) "（可多选）" else "", 15.5f, R.color.text)
            all.addView(question)
            if (header.isEmpty() && qi > 0) Ui.margins(question, c, 0, 16, 0, 0)
            val other = EditText(c)
            val opts = q.optJSONArray("options")
            if (opts != null) for (oi in 0 until opts.length()) {
                val o = opts.optJSONObject(oi) ?: continue
                val label = o.optString("label")
                val opt = Ui.column(c)
                opt.setPadding(Ui.dp(c, 12), Ui.dp(c, 9), Ui.dp(c, 12), Ui.dp(c, 9))
                opt.addView(Ui.text(c, label, 15, R.color.text))
                val desc = o.optString("description")
                if (desc.isNotEmpty()) {
                    val d = Ui.text(c, desc, 13, R.color.text2)
                    d.setPadding(0, Ui.dp(c, 2), 0, 0)
                    opt.addView(d)
                }
                val paint = {
                    val on = picked.contains(label)
                    opt.background = Ui.press(c, Ui.round(Ui.color(c, if (on) R.color.active else R.color.surface), Ui.dp(c, 10),
                        Ui.dp(c, if (on) 2 else 1), Ui.color(c, if (on) R.color.accent else R.color.border)), Ui.dp(c, 10))
                }
                paint()
                repaint.add(paint)
                opt.setOnClickListener {
                    if (multi) {
                        if (!picked.remove(label)) picked.add(label)
                    } else {
                        val was = picked.contains(label)
                        picked.clear()
                        if (!was) picked.add(label)
                        if (!was) other.setText("") // one answer: an option instead of what was written
                    }
                    for (r in repaint) r()
                }
                all.addView(opt, Ui.fill())
                Ui.margins(opt, c, 0, 6, 0, 0)
            }
            other.hint = "其他（自己填写）"
            other.setText(b.other[qi] ?: "")
            other.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            other.setTextColor(Ui.color(c, R.color.text))
            other.setHintTextColor(Ui.color(c, R.color.text3))
            other.setPadding(Ui.dp(c, 12), Ui.dp(c, 9), Ui.dp(c, 12), Ui.dp(c, 9))
            other.background = Ui.round(Ui.color(c, R.color.surface), Ui.dp(c, 10), Ui.dp(c, 1), Ui.color(c, R.color.border))
            other.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence, a: Int, n: Int, k: Int) {}
                override fun onTextChanged(s: CharSequence, a: Int, n: Int, k: Int) {}
                override fun afterTextChanged(s: Editable) {
                    val t = s.toString()
                    if (t.trim().isEmpty()) {
                        b.other.remove(qi)
                    } else {
                        b.other[qi] = t
                        if (!multi) picked.clear() // one answer: what is written instead of an option
                    }
                    for (r in repaint) r()
                }
            })
            all.addView(other, Ui.fill())
            Ui.margins(other, c, 0, 6, 0, 0)
        }
        val row = Ui.row(c)
        row.gravity = Gravity.END or Gravity.CENTER_VERTICAL
        for (submit in booleanArrayOf(false, true)) {
            val t = answerButton(if (submit) "提交" else "跳过", submit)
            if (submit) {
                // Ready once every question has an answer.
                val ready = {
                    val answered = (0 until qs.length()).all { k -> !b.picked[k].isNullOrEmpty() || b.other.containsKey(k) }
                    t.isEnabled = answered
                    t.alpha = if (answered) 1f else 0.4f
                }
                ready()
                repaint.add(ready)
                t.setOnClickListener {
                    val answers = JSONObject()
                    for (k in 0 until qs.length()) {
                        val q = qs.optJSONObject(k) ?: continue
                        val parts = ArrayList<String>()
                        b.picked[k]?.let { parts.addAll(it) }
                        b.other[k]?.let { parts.add(it.trim()) }
                        answers.put(q.optString("question"), parts.joinToString(", "))
                    }
                    asks?.invoke(chat!!, b, true, answers)
                }
            } else {
                t.setOnClickListener { asks?.invoke(chat!!, b, false, null) }
            }
            row.addView(t, Ui.wrap())
            if (submit) Ui.margins(t, c, 8, 0, 0, 0)
        }
        all.addView(row, Ui.fill())
        Ui.margins(row, c, 0, 12, 0, 0)
        box.addView(all, Ui.fill())
        Ui.margins(all, c, 32, 8, 0, 6)
    }

    /** Allow or deny, for Claude Code's question whether this tool may run. */
    private fun ask(box: LinearLayout, b: Chat.Block) {
        val row = Ui.row(c)
        row.gravity = Gravity.END or Gravity.CENTER_VERTICAL
        for (allow in booleanArrayOf(false, true)) {
            val t = answerButton(if (allow) "允许" else "拒绝", allow)
            t.setOnClickListener { asks?.invoke(chat!!, b, allow, null) }
            row.addView(t, Ui.wrap())
            if (allow) Ui.margins(t, c, 8, 0, 0, 0)
        }
        box.addView(row, Ui.fill())
        Ui.margins(row, c, 32, 8, 0, 6)
    }

    private fun toolBody(box: LinearLayout, b: Chat.Block) {
        val body = Ui.column(c)
        val i = b.input
        // Its input still coming: each box keeps its end in view as it grows.
        val streaming = b.partial.isNotEmpty()
        when (b.name ?: "") {
            "Bash" -> {
                val shown = io(b.name, i.optString("command"), R.color.text)
                shown.follow = streaming
                body.addView(shown)
            }
            "Read" -> body.addView(io(b.name, i.optString("file_path"), R.color.text))
            "Edit" -> {
                body.addView(io(b.name, i.optString("file_path"), R.color.text))
                val shown = diff(i.optString("old_string"), i.optString("new_string"))
                shown.follow = streaming
                body.addView(shown)
            }
            "MultiEdit" -> {
                body.addView(io(b.name, i.optString("file_path"), R.color.text))
                val edits = i.optJSONArray("edits")
                if (edits != null) for (k in 0 until edits.length()) {
                    val e = edits.optJSONObject(k) ?: continue
                    val shown = diff(e.optString("old_string"), e.optString("new_string"))
                    shown.follow = streaming && k == edits.length() - 1
                    body.addView(shown)
                }
            }
            "Write" -> {
                var text = i.optString("content")
                // Still being written: only its end (a whole long file drawn every 300 ms is slow).
                if (streaming && text.length > 4000) {
                    val cut = text.indexOf('\n', text.length - 4000)
                    text = "…\n" + text.substring(if (cut < 0) text.length - 4000 else cut + 1)
                }
                val shown = io(b.name, i.optString("file_path") + "\n\n" + text, R.color.text)
                shown.follow = streaming
                body.addView(shown)
            }
            "TodoWrite" -> {}
            "AskUserQuestion" -> {
                // Each question with what was answered (read from the result).
                val qs = i.optJSONArray("questions")
                var answered = false
                if (qs != null) for (k in 0 until qs.length()) {
                    val q = qs.optJSONObject(k) ?: continue
                    val a = answerOf(b.result, q.optString("question"))
                    answered = answered or a.isNotEmpty()
                    body.addView(io(q.optString("question"), a.ifEmpty { "—" }, R.color.text))
                }
                if (answered) {
                    box.addView(body, Ui.fill())
                    Ui.margins(body, c, 32, 6, 0, 8)
                    return
                }
            }
            else -> body.addView(io(b.name, pretty(i), R.color.text2))
        }
        for (src in b.images) body.addView(picture(src))
        val result = b.result
        val shownResult = if (result == null) "" else if (b.images.isEmpty()) result else result.replace("[图片]", "").trim()
        if (shownResult.isNotEmpty()) body.addView(io(null, shownResult, if (b.error) R.color.danger else R.color.text2))
        for (k in 1 until body.childCount) Ui.margins(body.getChildAt(k), c, 0, 8, 0, 0)
        box.addView(body, Ui.fill())
        Ui.margins(body, c, 32, 6, 0, 8)
    }

    /** A picture across the row, at most a screen's half high; a tap shows it whole. */
    private fun picture(src: String): View {
        val img = ImageView(c)
        img.adjustViewBounds = true
        img.scaleType = ImageView.ScaleType.FIT_START
        img.maxHeight = c.resources.displayMetrics.heightPixels / 2
        img.background = Ui.round(Ui.color(c, R.color.surface2), Ui.dp(c, 10), Ui.dp(c, 1), Ui.color(c, R.color.border))
        img.clipToOutline = true
        img.layoutParams = Ui.wrap()
        Images.load(img, src, null, c.resources.displayMetrics.widthPixels, null)
        img.setOnClickListener { Images.show(c, src, null) }
        return img
    }

    /** A box of monospace text, headed by the tool's name when given. */
    private fun io(label: String?, text: String?, colorRes: Int): MaxScroll {
        val body = text ?: ""
        val s = SpannableStringBuilder()
        if (label != null) {
            s.append(label)
            s.setSpan(StyleSpan(Typeface.BOLD), 0, label.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            s.setSpan(ForegroundColorSpan(Ui.color(c, R.color.accentPress)), 0, label.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (body.isNotEmpty()) s.append('\n')
        }
        s.append(body)
        val scroll = MaxScroll(c, Ui.dp(c, 320))
        scroll.background = Ui.round(Ui.color(c, R.color.codeBg), Ui.dp(c, 10), Ui.dp(c, 1), Ui.color(c, R.color.border))
        val t = Ui.text(c, s, 12.5f, colorRes)
        t.typeface = Ui.MONO
        t.setLineSpacing(0f, 1.25f)
        t.setPadding(Ui.dp(c, 10), Ui.dp(c, 8), Ui.dp(c, 10), Ui.dp(c, 8))
        t.setTextIsSelectable(true)
        scroll.addView(t)
        scroll.layoutParams = Ui.fill()
        return scroll
    }

    private fun diff(before: String, after: String): MaxScroll {
        val s = SpannableStringBuilder()
        val del = Ui.color(c, R.color.diffDel)
        val delBg = Ui.color(c, R.color.diffDelBg)
        val add = Ui.color(c, R.color.diffAdd)
        val addBg = Ui.color(c, R.color.diffAddBg)
        var lines = 0
        for (l in before.split("\n")) {
            if (lines++ > 300) break
            val st = s.length
            s.append("- ").append(l).append('\n')
            s.setSpan(ForegroundColorSpan(del), st, s.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            s.setSpan(BackgroundColorSpan(delBg), st, s.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        for (l in after.split("\n")) {
            if (lines++ > 600) break
            val st = s.length
            s.append("+ ").append(l).append('\n')
            s.setSpan(ForegroundColorSpan(add), st, s.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            s.setSpan(BackgroundColorSpan(addBg), st, s.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        val scroll = MaxScroll(c, Ui.dp(c, 320))
        scroll.background = Ui.round(Ui.color(c, R.color.codeBg), Ui.dp(c, 10), Ui.dp(c, 1), Ui.color(c, R.color.border))
        val t = TextView(c)
        t.text = s
        t.textSize = 12.5f
        t.typeface = Ui.MONO
        t.setTextColor(Ui.color(c, R.color.text))
        t.setPadding(Ui.dp(c, 10), Ui.dp(c, 8), Ui.dp(c, 10), Ui.dp(c, 8))
        t.setTextIsSelectable(true)
        scroll.addView(t)
        scroll.layoutParams = Ui.fill()
        return scroll
    }

    private fun todos(box: LinearLayout, b: Chat.Block) {
        val list = b.input.optJSONArray("todos")
        if (list == null || list.length() == 0) return
        val col = Ui.column(c)
        for (k in 0 until list.length()) {
            val t = list.optJSONObject(k) ?: continue
            val status = t.optString("status")
            val row = Ui.row(c)
            row.gravity = Gravity.TOP
            val check = FrameLayout(c)
            val done = status == "completed"
            val now = status == "in_progress"
            check.background = if (done) Ui.round(Ui.color(c, R.color.ok), Ui.dp(c, 5))
                else Ui.round(0, Ui.dp(c, 5), Ui.dp(c, 1.5f), Ui.color(c, if (now) R.color.accent else R.color.borderStrong))
            if (done) check.addView(Ui.icon(c, R.drawable.ic_check, 12, android.R.color.white),
                FrameLayout.LayoutParams(Ui.dp(c, 12), Ui.dp(c, 12), Gravity.CENTER))
            if (now) {
                val dot = View(c)
                dot.background = Ui.round(Ui.color(c, R.color.accent), Ui.dp(c, 2))
                check.addView(dot, FrameLayout.LayoutParams(Ui.dp(c, 6), Ui.dp(c, 6), Gravity.CENTER))
            }
            val cp = LinearLayout.LayoutParams(Ui.dp(c, 16), Ui.dp(c, 16))
            cp.topMargin = Ui.dp(c, 3)
            row.addView(check, cp)
            val label = Ui.text(c, t.optString("content"), 14.5f, if (done) R.color.text3 else if (now) R.color.text else R.color.text2)
            label.includeFontPadding = true
            if (done) label.paintFlags = label.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
            if (now) label.typeface = Ui.MEDIUM
            label.setPadding(Ui.dp(c, 9), 0, 0, 0)
            row.addView(label, Ui.weight())
            col.addView(row, Ui.fill())
            Ui.margins(row, c, 0, 3, 0, 2)
        }
        box.addView(col, Ui.fill())
        Ui.margins(col, c, 32, 4, 0, 4)
    }

    private fun error(box: LinearLayout, b: Chat.Block) {
        val card = Ui.column(c)
        card.setPadding(Ui.dp(c, 14), Ui.dp(c, 11), Ui.dp(c, 14), Ui.dp(c, 11))
        card.background = Ui.round(Ui.color(c, R.color.dangerSoft), Ui.dp(c, 12))
        val title = Ui.text(c, b.title, 14, R.color.danger)
        title.typeface = Typeface.DEFAULT_BOLD
        card.addView(title)
        if (b.text.isNotEmpty()) {
            val t = Ui.text(c, b.text, 14, R.color.danger)
            t.setLineSpacing(0f, 1.2f)
            t.setTextIsSelectable(true)
            card.addView(t)
            Ui.margins(t, c, 0, 4, 0, 0)
        }
        box.addView(card, Ui.fill())
    }

    private fun status(h: Holder) {
        val row = Ui.row(c)
        val glyph = Ui.text(c, GLYPHS[0], 19, R.color.accent)
        h.glyph = glyph
        glyph.typeface = Ui.MONO
        glyph.gravity = Gravity.CENTER
        glyph.includeFontPadding = false
        // A fixed box: the glyphs come from fonts of different heights, and a row changing height
        // every frame shakes the whole list while it keeps the bottom in view.
        row.addView(glyph, LinearLayout.LayoutParams(Ui.dp(c, 20), Ui.dp(c, 24)))
        val verb = Ui.text(c, "", 14.5f, R.color.text2)
        h.verb = verb
        verb.setPadding(Ui.dp(c, 10), 0, Ui.dp(c, 10), 0)
        row.addView(verb)
        val elapsed = Ui.text(c, "", 13, R.color.text3)
        h.elapsed = elapsed
        elapsed.fontFeatureSettings = "tnum"
        row.addView(elapsed)
        h.box.addView(row)
        val detail = Ui.text(c, "", 12.5f, R.color.text3)
        h.detail = detail
        detail.setPadding(Ui.dp(c, 30), Ui.dp(c, 4), 0, 0)
        h.box.addView(detail)
        h.box.setOnClickListener {
            val b = streamingThinking()
            if (b != null) toggle(b, h.box)
        }
        statusRows.add(h)
        tick(h)
    }

    /** Animates the status rows: glyph, verb, elapsed time, retries. */
    fun tick() {
        for (h in statusRows) tick(h)
    }

    private fun tick(h: Holder) {
        val chat = chat ?: return
        val glyph = h.glyph ?: return
        val now = System.currentTimeMillis()
        glyph.text = GLYPHS[(now / 120 % GLYPHS.size).toInt()]
        h.verb?.text = chat.verb + "…"
        val secs = (now - if (chat.startedAt == 0L) now else chat.startedAt) / 1000
        h.elapsed?.text = if (secs > 0) "${secs}s" else ""
        val detail = chat.detail // retries
        h.detail?.let {
            it.text = detail
            it.visibility = if (detail.isEmpty()) View.GONE else View.VISIBLE
            it.setTextColor(Ui.color(c, if (detail.isEmpty()) R.color.text3 else R.color.warn))
        }
    }

    private fun streamingThinking(): Chat.Block? {
        val chat = chat ?: return null
        if (chat.messages.isEmpty()) return null
        return chat.messages[chat.messages.size - 1].blocks.firstOrNull { it.kind == Chat.Block.THINKING && it.streaming }
    }

    /** A long press on a message's text (each of its views) opens its menu, instead of selecting. */
    private fun pressable(v: View, m: Chat.Msg) {
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) pressable(v.getChildAt(i), m)
        } else if (v is TextView) {
            v.setOnLongClickListener {
                menu?.invoke(m)
                true
            }
        }
    }

    /** A vertical scroll box with a maximum height, scrolling inside the list (ThreadList): what it does not use goes on to the list. */
    class MaxScroll(c: Context, private val max: Int) : NestedScrollView(c) {
        /** Keeps the end in view as the text grows; scrolling up by hand stops it, back at the end resumes it. */
        @JvmField var follow = false
        private var touching = false

        init {
            isFillViewport = false
        }

        override fun onMeasure(w: Int, h: Int) {
            super.onMeasure(w, MeasureSpec.makeMeasureSpec(max, MeasureSpec.AT_MOST))
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            super.onLayout(changed, l, t, r, b)
            if (follow && childCount > 0) {
                val end = getChildAt(0).height - (height - paddingTop - paddingBottom)
                if (end > 0 && scrollY != end) scrollTo(0, end)
            }
        }

        override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
            super.onScrollChanged(l, t, oldl, oldt)
            if (touching) follow = !canScrollVertically(1)
            else if (!canScrollVertically(1)) follow = true
        }

        override fun dispatchTouchEvent(e: MotionEvent): Boolean {
            val action = e.actionMasked
            if (action == MotionEvent.ACTION_DOWN) touching = true
            else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) touching = false
            val handled = super.dispatchTouchEvent(e)
            // A text's selection keeps the end of the touch from this box's own handling: its
            // scrolling with the list ends here all the same.
            if (!touching) stopNestedScroll(ViewCompat.TYPE_TOUCH)
            return handled
        }
    }

    companion object {
        const val USER = 0
        const val NOTE = 1
        const val DIVIDER = 2
        const val TEXT = 3
        const val THINKING = 4
        const val TOOL = 5
        const val ERROR = 6
        const val STOPPED = 7
        const val STATUS = 8
        const val QUEUED = 10
        /** 翻译 in thinking's selection menu (the system's items have ids of android.R.id). */
        private const val TRANSLATE = 1
        @JvmField val GLYPHS = arrayOf("·", "✢", "✳", "✶", "✻", "✽", "✻", "✶", "✳", "✢")

        private fun flatten(chat: Chat, thinking: Boolean): List<Item> {
            val out = ArrayList<Item>()
            val msgs = chat.messages
            for (i in msgs.indices) {
                val m = msgs[i]
                val top = if (out.isEmpty()) 6 else 24
                when (m.kind) {
                    Chat.Msg.USER -> out.add(Item(USER, m.id, m.version.toLong(), m, null, top))
                    Chat.Msg.NOTE -> out.add(Item(NOTE, m.id, m.version.toLong(), m, null, top))
                    Chat.Msg.DIVIDER -> out.add(Item(DIVIDER, m.id, m.version.toLong(), m, null, top))
                    else -> {
                        var prev: Chat.Block? = null
                        for (b in m.blocks) {
                            // The model may send a thinking block with nothing in it: once it ends, there is nothing to show.
                            if (b.kind == Chat.Block.THINKING && !b.streaming && b.text.trim().isEmpty()) continue
                            // Thinking can be left out altogether (a setting), whatever the model.
                            if (b.kind == Chat.Block.THINKING && !thinking) continue
                            // Thinking in progress is the status row's "思考中… Ns"; tapping that opens it here.
                            if (b.kind == Chat.Block.THINKING && b.streaming && !b.open) continue
                            val type = when (b.kind) {
                                Chat.Block.TEXT -> TEXT
                                Chat.Block.THINKING -> THINKING
                                Chat.Block.TOOL -> TOOL
                                Chat.Block.ERROR -> ERROR
                                else -> STOPPED
                            }
                            // Steps (tools and thinking) one after another sit close, as one list.
                            val step = b.kind == Chat.Block.TOOL || b.kind == Chat.Block.THINKING
                            val prevStep = prev != null && (prev.kind == Chat.Block.TOOL || prev.kind == Chat.Block.THINKING)
                            val gap = if (prev == null) (if (out.isEmpty()) 6 else 20) else if (step && prevStep) 2 else 14
                            val sig = (b.version.toLong() shl 1) or if (b.open) 1L else 0L
                            out.add(Item(type, b.id, sig, m, b, gap))
                            prev = b
                        }
                        val last = i == msgs.size - 1
                        if (last && chat.running) out.add(Item(STATUS, -1, 0, m, null, if (m.blocks.isEmpty()) 20 else 12))
                    }
                }
            }
            if (chat.running && (msgs.isEmpty() || msgs[msgs.size - 1].kind != Chat.Msg.ASSISTANT)) {
                out.add(Item(STATUS, -1, 0, null, null, 20))
            }
            for (q in chat.queue) {
                val it = Item(QUEUED, -q.id - 1_000_000_000L, if (q.now) 1 else 0, null, null, if (out.isEmpty()) 6 else 18)
                it.queued = q
                out.add(it)
            }
            return out
        }

        /** The chevron at the head's end, turned from one angle to another. */
        private fun turn(head: View, from: Float, to: Float) {
            if (head !is ViewGroup || head.childCount == 0) return
            val chevron = head.getChildAt(head.childCount - 1)
            if (chevron !is ImageView) return
            chevron.rotation = from
            chevron.animate().rotation(to).setDuration(250).setInterpolator(Motion.EMPHASIZED).start()
        }

        /** A box's height moved from one to another, then back to its own (wrap_content). */
        private fun resize(box: View, from: Int, to: Int, end: () -> Unit) {
            val p = box.layoutParams
            p.height = from
            box.layoutParams = p
            val a = ValueAnimator.ofInt(from, to)
            a.duration = min(Motion.PAGE, (200 + abs(to - from) / 4).toLong())
            a.interpolator = Motion.EMPHASIZED
            a.addUpdateListener { x ->
                p.height = x.animatedValue as Int
                box.layoutParams = p
            }
            a.addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(x: Animator) {
                    p.height = ViewGroup.LayoutParams.WRAP_CONTENT
                    box.layoutParams = p
                    end()
                }
            })
            a.start()
        }

        private fun thinkingLabel(b: Chat.Block): String = when {
            b.streaming -> "思考中…"
            b.millis > 0 -> "已思考 " + max(1, (b.millis / 1000f).roundToInt()) + " 秒"
            else -> "已思考"
        }

        /** The model ends its thinking with blank lines; shown, they leave an empty tail under the words. */
        private fun thinkingText(b: Chat.Block): String = b.text.replace(Regex("\\s+$"), "")

        /** {icon, tool name, what it acts on}: as Claude Code's terminal shows a step. */
        @JvmStatic
        fun toolInfo(b: Chat.Block): Array<String> {
            val i = b.input
            val name = if (b.name.isNullOrEmpty()) "Tool" else b.name!!
            return when (name) {
                "Bash" -> {
                    val summary = i.optString("description")
                    arrayOf("terminal", name, summary.ifEmpty { i.optString("command").trim().split("\n")[0] })
                }
                "Read" -> arrayOf("file", name, Ui.base(i.optString("file_path")))
                "Write" -> arrayOf("filePlus", name, Ui.base(i.optString("file_path")))
                "Edit" -> arrayOf("pencil", name, Ui.base(i.optString("file_path")))
                "MultiEdit" -> arrayOf("editLines", name, Ui.base(i.optString("file_path")))
                "NotebookEdit" -> arrayOf("book", name, Ui.base(i.optString("notebook_path")))
                "Glob" -> arrayOf("folder", name, i.optString("pattern"))
                "Grep" -> arrayOf("search", name, i.optString("pattern"))
                "WebFetch" -> arrayOf("link", name, i.optString("url"))
                "WebSearch" -> arrayOf("globe", name, i.optString("query"))
                "Task", "Agent" -> arrayOf("bot", name, i.optString("description"))
                "TodoWrite" -> arrayOf("list", "TodoWrite", "Update Todos")
                "Skill" -> arrayOf("spark", name, i.optString("skill", i.optString("command")))
                "AskUserQuestion" -> {
                    val first = i.optJSONArray("questions")?.optJSONObject(0)
                    arrayOf("list", "提问", first?.optString("question") ?: "提问")
                }
                else -> {
                    // An MCP tool (mcp__server__tool) or any other: its own name and what it was given first.
                    val cut = if (name.startsWith("mcp__")) name.indexOf("__", 5) else -1
                    val shown = if (cut > 0) name.substring(cut + 2) else name
                    val arg = firstArg(i)
                    arrayOf("tool", name, if (arg.isEmpty()) shown else "$shown · $arg")
                }
            }
        }

        /** The first text a tool was given (a web address without its scheme), on one line. */
        private fun firstArg(input: JSONObject): String {
            for (key in input.keys()) {
                var v = input.opt(key)
                if (v is JSONArray && v.length() > 0) v = v.opt(0)
                if (v !is String || v.trim().isEmpty()) continue
                return v.trim().split("\n")[0].replaceFirst(Regex("^https?://"), "")
            }
            return ""
        }

        private fun iconRes(name: String): Int = when (name) {
            "terminal" -> R.drawable.ic_terminal
            "file" -> R.drawable.ic_file
            "filePlus" -> R.drawable.ic_file_plus
            "pencil" -> R.drawable.ic_pencil
            "search" -> R.drawable.ic_search
            "globe" -> R.drawable.ic_globe
            "spark" -> R.drawable.ic_spark
            "list" -> R.drawable.ic_list
            "editLines" -> R.drawable.ic_edit_lines
            "book" -> R.drawable.ic_book
            "folder" -> R.drawable.ic_folder
            "link" -> R.drawable.ic_link
            "bot" -> R.drawable.ic_bot
            else -> R.drawable.ic_tool
        }

        /** What the user answered to a question, from AskUserQuestion's result ("question"="answer"); "" if none. */
        private fun answerOf(result: String?, question: String): String {
            if (result == null) return ""
            val key = "\"$question\"=\""
            val at = result.indexOf(key)
            if (at < 0) return ""
            val from = at + key.length
            val to = result.indexOf('"', from)
            return if (to < 0) "" else result.substring(from, to)
        }

        private fun pretty(o: JSONObject): String = try {
            // JSONObject writes / as \/: shown as typed.
            o.toString(2).replace("\\/", "/")
        } catch (e: Exception) {
            o.toString().replace("\\/", "/")
        }
    }
}
