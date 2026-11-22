package com.jeep.claudecode

import android.content.Context
import android.content.res.ColorStateList
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Locale
import kotlin.math.min

/** The box the message is written in: the text, the send button, the menu of / commands and the files waiting to be sent. */
class Box(app: MainActivity) : Part(app) {
    private lateinit var sendBtn: ImageView
    /** The model and thinking level, in a pill under the text. */
    private lateinit var modelChip: TextView
    lateinit var input: EditText
    /** The files waiting to be sent (a row in a scroll of its own), and the / commands to pick from (a column). */
    private lateinit var attachments: LinearLayout
    private lateinit var slashMenu: LinearLayout
    private lateinit var attachScroll: HorizontalScrollView
    lateinit var slashScroll: ScrollView

    /** The box, built (again after a restyle) for the layout in use. */
    fun buildComposer(): View {
        val wrap = Ui.column(app)
        wrap.setPadding(dp(16), dp(4), dp(16), dp(16))
        val card: LinearLayout
        if (app.screen.immersive) {
            card = Glass.Pane(app, app.screen.host!!, dp(26).toFloat(), col(R.color.bg), Glass.alpha(col(R.color.surface), 0.55f),
                Glass.alpha(col(R.color.surface), 0.94f), col(R.color.border))
            card.orientation = LinearLayout.VERTICAL
        } else {
            card = Ui.column(app)
            card.background = Ui.round(col(R.color.surface), dp(26), dp(1), col(R.color.border))
            card.elevation = dp(2).toFloat()
        }
        card.setPadding(dp(7.5f), dp(6.5f), dp(7.5f), dp(7f))

        slashScroll = object : ScrollView(app) {
            override fun onMeasure(w: Int, h: Int) {
                super.onMeasure(w, MeasureSpec.makeMeasureSpec(min(dp(300),
                    (resources.displayMetrics.heightPixels * 0.4f).toInt()), MeasureSpec.AT_MOST))
            }
        }
        slashMenu = Ui.column(app)
        slashScroll.addView(slashMenu)
        slashScroll.visibility = View.GONE
        card.addView(slashScroll, Ui.fill())

        attachScroll = HorizontalScrollView(app)
        attachScroll.isHorizontalScrollBarEnabled = false
        attachments = Ui.row(app)
        attachments.setPadding(dp(6), dp(4), dp(6), dp(4))
        attachScroll.addView(attachments)
        attachScroll.visibility = View.GONE
        card.addView(attachScroll, Ui.fill())

        // The text on top, the buttons in a line below it: + and the model on the left, the mic and send on the right.
        input = EditText(app)
        input.background = null
        input.hint = "回复 Claude…"
        input.setHintTextColor(col(R.color.text3))
        input.setTextColor(col(R.color.text))
        input.textSize = 17.5f
        input.setPadding(dp(6.5f), dp(7f), dp(6.5f), dp(6f))
        input.minHeight = dp(37f)
        input.maxHeight = (app.resources.displayMetrics.heightPixels * 0.34f).toInt()
        // The keyboard's Enter sends (its key shows Send); the text still wraps over several lines.
        input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        input.setRawInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)
        input.imeOptions = EditorInfo.IME_ACTION_SEND
        input.setHorizontallyScrolling(false)
        input.maxLines = Int.MAX_VALUE
        input.setOnEditorActionListener { _, action, event ->
            if (action == EditorInfo.IME_ACTION_SEND || action == EditorInfo.IME_NULL) {
                if (event == null || event.action == KeyEvent.ACTION_DOWN) app.sender.send()
                true
            } else {
                false
            }
        }
        // A physical Enter sends too; Shift+Enter makes a new line.
        input.setOnKeyListener { _, code, event ->
            if (code != KeyEvent.KEYCODE_ENTER || event.isShiftPressed) return@setOnKeyListener false
            if (event.action == KeyEvent.ACTION_DOWN) app.sender.send()
            true
        }
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable) {
                // The /find line stays in the box while it looks; changed or cleared, it stops.
                if (app.finder.finding() && s.toString().trim() != app.finder.findLine) app.finder.findEnd()
                showMenu()
                app.screen.updateChrome()
                if (s.isNotEmpty()) app.sender.warm()
            }
        })
        card.addView(input, Ui.fill())

        val row = Ui.row(app)
        row.setPadding(0, dp(6f), 0, 0)
        val attach = round(R.drawable.ic_plus)
        attach.contentDescription = "添加文件"
        attach.setOnClickListener { app.attachments.pickFiles() }
        row.addView(attach, LinearLayout.LayoutParams(dp(BUTTON), dp(BUTTON)))

        // The model and thinking level in use; tapped, both are picked.
        modelChip = Ui.text(app, "", 15f, R.color.text)
        modelChip.gravity = Gravity.CENTER
        modelChip.isSingleLine = true
        modelChip.ellipsize = TextUtils.TruncateAt.END
        modelChip.setPadding(dp(14.5f), 0, dp(14.5f), 0)
        modelChip.background = Ui.press(app, Ui.round(fill(app), dp(BUTTON / 2)), dp(BUTTON / 2))
        modelChip.setOnClickListener { app.pickers.modelSheet() }
        // As wide as its words, but never pushing the mic and send off the line (a long name is cut short).
        val chipRoom = FrameLayout(app)
        chipRoom.addView(modelChip, FrameLayout.LayoutParams(-2, dp(BUTTON), Gravity.START or Gravity.CENTER_VERTICAL))
        val mp = LinearLayout.LayoutParams(0, dp(BUTTON), 1f)
        mp.setMargins(dp(8), 0, dp(8), 0)
        row.addView(chipRoom, mp)

        val mic = round(R.drawable.ic_mic)
        app.voice.attach(mic)
        row.addView(mic, LinearLayout.LayoutParams(dp(BUTTON), dp(BUTTON)))

        // Send: the Enter icon, the ringed square to stop, on a round button as the others.
        sendBtn = ImageView(app)
        sendBtn.scaleType = ImageView.ScaleType.CENTER_INSIDE
        sendBtn.contentDescription = "发送"
        sendBtn.setOnClickListener { app.sender.send() }
        val sp = LinearLayout.LayoutParams(dp(BUTTON), dp(BUTTON))
        sp.setMargins(dp(11f), 0, 0, 0)
        row.addView(sendBtn, sp)
        card.addView(row, Ui.fill())
        wrap.addView(card, Ui.fill())

        return wrap
    }

    /** A round button under the text (+, the mic): its icon on a light fill of the text's colour. */
    private fun round(icon: Int): ImageView {
        val b = Ui.iconButton(app, icon, 32, 18, R.color.text)
        val pad = dp((BUTTON - ICON) / 2)
        b.setPadding(pad, pad, pad, pad)
        b.background = Ui.press(app, Ui.round(fill(app), dp(BUTTON / 2)), dp(BUTTON / 2))
        return b
    }

    private fun showMenu() {
        val v = input.text.toString()
        if (!v.matches(Regex("^/\\S*$"))) {
            hideMenu()
            return
        }
        val q = v.substring(1).lowercase(Locale.ROOT)
        val hits = ArrayList<Map.Entry<String, Array<String>>>()
        val rest = ArrayList<Map.Entry<String, Array<String>>>()
        for (e in app.slash.commandList().entries) {
            val n = e.key.lowercase(Locale.ROOT)
            if (n.startsWith(q)) hits.add(e) else if (n.contains(q)) rest.add(e)
        }
        hits.addAll(rest)
        if (hits.isEmpty()) {
            hideMenu()
            return
        }
        slashMenu.removeAllViews()
        for ((name, value) in hits) {
            val row = Ui.row(app)
            row.setPadding(dp(10), dp(9), dp(10), dp(9))
            row.background = Ui.press(app, null, dp(10))
            val n = Ui.text(app, "/$name", 13.5f, R.color.accentPress)
            n.typeface = Ui.MONO
            row.addView(n)
            val d = Ui.text(app, value[1], 13.5f, R.color.text2)
            d.isSingleLine = true
            d.ellipsize = TextUtils.TruncateAt.END
            d.setPadding(dp(10), 0, 0, 0)
            row.addView(d, Ui.weight())
            val args = value[0].isNotEmpty()
            val local = value[2] == "local"
            row.setOnClickListener {
                val text = "/" + name + if (args) " " else ""
                input.setText(text)
                input.setSelection(text.length)
                hideMenu()
                if (!args && local) app.sender.send()
            }
            slashMenu.addView(row, Ui.fill())
        }
        slashScroll.visibility = View.VISIBLE
        slashScroll.scrollTo(0, 0)
    }

    fun hideMenu() {
        slashScroll.visibility = View.GONE
    }

    /** The send button as the box stands (text written or a file waiting; a reply running; linked or not), and the files waiting. */
    fun update() {
        val typed = input.text.toString().trim().isNotEmpty() || app.current.attachments.isNotEmpty()
        val canSend = app.link.linked && typed
        // Plain icons, no filled button: Enter to send, a ringed square to stop the reply.
        val stop = app.current.running && !typed
        sendBtn.setImageResource(if (stop) R.drawable.ic_stop_circle else R.drawable.ic_enter)
        val live = stop || canSend
        // On the same faint fill as + and the mic while there is nothing to send; solid in the text's colour
        // (white on dark) when there is, as the claude.ai app's round button.
        sendBtn.imageTintList = ColorStateList.valueOf(if (live) col(R.color.bg) else col(R.color.text3))
        sendBtn.background = Ui.press(app, Ui.round(if (live) col(R.color.text) else fill(app), dp(BUTTON / 2)), dp(BUTTON / 2))
        sendBtn.isEnabled = live
        sendBtn.setPadding(dp(7.5f), dp(7.5f), dp(7.5f), dp(7.5f))
        modelChip.text = app.pickers.modelLabel()
        renderAttachments()
    }

    private fun renderAttachments() {
        val items = app.current.attachments
        attachScroll.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
        val key = if (items.isEmpty()) "" else
            System.identityHashCode(items).toString() + ":" + items.size + ":" + System.identityHashCode(items[items.size - 1])
        if (key == attachments.tag) return
        attachments.tag = key
        attachments.removeAllViews()
        for (a in ArrayList(items)) {
            val cell = FrameLayout(app)
            cell.setPadding(0, dp(7), dp(7), 0)
            if (a.image) {
                val img = ImageView(app)
                img.setImageBitmap(a.thumb)
                img.scaleType = ImageView.ScaleType.CENTER_CROP
                img.background = Ui.round(col(R.color.surface2), dp(12), dp(1), col(R.color.border))
                img.clipToOutline = true
                cell.addView(img, FrameLayout.LayoutParams(dp(64), dp(64)))
            } else {
                val f = Ui.column(app)
                f.gravity = Gravity.CENTER_VERTICAL
                f.setPadding(dp(10), dp(8), dp(10), dp(8))
                f.background = Ui.round(col(R.color.surface2), dp(12), dp(1), col(R.color.border))
                f.addView(Ui.icon(app, R.drawable.ic_file, 16, R.color.text2))
                val n = Ui.text(app, a.name, 12.5f, R.color.text)
                n.isSingleLine = true
                n.ellipsize = TextUtils.TruncateAt.MIDDLE
                n.setPadding(0, dp(4), 0, dp(2))
                f.addView(n)
                f.addView(Ui.text(app, if (a.size >= 0) Ui.size(a.size) else "", 11, R.color.text3))
                cell.addView(f, FrameLayout.LayoutParams(dp(150), dp(64)))
            }
            val remove = Ui.icon(app, R.drawable.ic_x, 22, R.color.bg)
            remove.setPadding(dp(5), dp(5), dp(5), dp(5))
            remove.background = Ui.round(col(R.color.text), dp(11), dp(2), col(R.color.surface))
            remove.setOnClickListener {
                app.current.attachments.remove(a)
                attachments.tag = null
                app.screen.updateChrome()
            }
            cell.addView(remove, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.END or Gravity.TOP))
            (remove.layoutParams as FrameLayout.LayoutParams).setMargins(0, -dp(7), -dp(7), 0)
            cell.clipChildren = false
            cell.clipToPadding = false
            attachments.addView(cell)
            Ui.margins(cell, app, 0, 0, 4, 0)
        }
    }

    fun hideKeyboard() {
        val imm = app.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(input.windowToken, 0)
        input.clearFocus()
    }

    fun showKeyboard() {
        val imm = app.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(input, 0)
    }

    companion object {
        /** The round buttons under the text, and their icons (dp). */
        const val BUTTON = 34f
        const val ICON = 19f

        /** The round buttons' fill: the text's colour, faint, so it shows on the plain box and on the glass. */
        @JvmStatic
        fun fill(c: Context): Int = Glass.alpha(Ui.color(c, R.color.text), 0.08f)
    }
}
