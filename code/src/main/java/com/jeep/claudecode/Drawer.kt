package com.jeep.claudecode

import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.text.Editable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.RejectedExecutionException
import kotlin.math.ceil
import kotlin.math.max
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** The drawer: the list of conversations, found by words or by folder, pinned, renamed or deleted, and the state of the link at its foot. */
class Drawer(app: MainActivity) : Part(app) {
    /** The conversations as the bridge last listed them (or as they were kept on the last start). */
    @JvmField var sessions = JSONArray()
    /** The words in the drawer's search box. */
    private var search = ""
    /** For the search in the drawer: sessions whose conversation has the words, with that text. */
    private val hits = HashMap<String, String>()
    private val searchText = Runnable { searchText() }
    /** The list of conversations, and the link's state at the drawer's foot. */
    private var sessionsBox: LinearLayout? = null
    private lateinit var linkText: TextView
    private var linkDot: View? = null
    /** The drawer as a whole: the immersive layout keeps it clear of the system bars. */
    @JvmField var drawerPanel: LinearLayout? = null
    /** The list was read from the bridge (or changed here) in this run: the kept one is older. */
    @JvmField var sessionsRead = false
    /** What the drawer's list was last built with of the live conversations (see drawerState). */
    @JvmField var drawnState = ""
    /** What the foot last showed: drawn again only when that changes. */
    @JvmField var shownLink: String? = null

    /** The screen is built again: the drawer comes new, and its state is shown again. */
    fun reset() {
        shownLink = null
        drawerPanel = null
    }

    /** A conversation's key here, by its session's id. */
    private fun keyOf(id: String): String = app.bySession[id] ?: id

    fun buildDrawer(): View {
        val d = Ui.column(app)
        drawerPanel = d
        d.setBackgroundColor(col(R.color.bg)) // full screen: one colour with the status bar
        d.isClickable = true

        // One line down the side: the title and the conversations' words from 24dp; icons centred
        // in a column 28dp wide from 24dp, their words from 64dp. What can be tapped is round, as elsewhere.
        val head = Ui.row(app)
        head.setPadding(dp(24), dp(14), dp(24), dp(14))
        val brand = Ui.text(app, "Claude Code", 26, R.color.text)
        brand.typeface = Ui.SERIF // as Settings' titles
        head.addView(brand)
        d.addView(head, Ui.fill())

        val newChat = Ui.row(app)
        newChat.setPadding(dp(12), 0, dp(12), 0)
        newChat.minimumHeight = dp(48)
        newChat.background = Ui.press(app, null, dp(24))
        // As Settings' icons: the accent on a pale round of it.
        val plus = drawerSlot(Ui.icon(app, R.drawable.ic_plus, 18, R.color.accent), 18, 18)
        plus.background = Ui.round(col(R.color.accentSoft), dp(14))
        newChat.addView(plus)
        val nl = Ui.text(app, "新对话", 16, R.color.text)
        nl.typeface = Ui.MEDIUM
        nl.setPadding(dp(12), 0, 0, 0)
        newChat.addView(nl)
        newChat.setOnClickListener {
            app.frame.close()
            app.switchTo(app.newChat())
        }
        d.addView(newChat, Ui.fill())
        Ui.margins(newChat, app, 12, 0, 12, 8)

        val searchBox = Ui.row(app)
        searchBox.setPadding(dp(12), 0, dp(16), 0)
        // White with a hairline, round at its ends: as the message box.
        searchBox.background = Ui.round(col(R.color.surface), dp(22), dp(1), col(R.color.border))
        searchBox.addView(drawerSlot(Ui.icon(app, R.drawable.ic_search, 18, R.color.text3), 18, 18))
        val searchInput = EditText(app)
        searchInput.background = null
        searchInput.hint = "搜索对话"
        searchInput.setHintTextColor(col(R.color.text3))
        searchInput.setTextColor(col(R.color.text))
        searchInput.textSize = 15.5f
        searchInput.isSingleLine = true
        searchInput.setPadding(dp(12), 0, 0, 0)
        // Built again after a restyle: what was being looked for is still in the box.
        searchInput.setText(search)
        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable) {
                search = s.toString()
                hits.clear()
                renderSessions()
                ui.removeCallbacks(searchText)
                if (search.trim().isNotEmpty()) ui.postDelayed(searchText, 250)
            }
        })
        searchBox.addView(searchInput, Ui.weight())
        d.addView(searchBox, LinearLayout.LayoutParams(-1, dp(44)))
        Ui.margins(searchBox, app, 12, 0, 12, 4)

        // The list fades out at its ends, as the conversation does under its bar, instead of
        // being cut by a line.
        val listArea = FrameLayout(app)
        val scroll = ScrollView(app)
        val box = Ui.column(app)
        sessionsBox = box
        box.setPadding(dp(12), dp(8), dp(12), dp(24))
        scroll.addView(box)
        listArea.addView(scroll, FrameLayout.LayoutParams(-1, -1))
        val bg = col(R.color.bg)
        val fadeIn = View(app)
        fadeIn.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(bg, Glass.alpha(bg, 0f)))
        listArea.addView(fadeIn, FrameLayout.LayoutParams(-1, dp(12), Gravity.TOP))
        val fadeOut = View(app)
        fadeOut.background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(bg, Glass.alpha(bg, 0f)))
        listArea.addView(fadeOut, FrameLayout.LayoutParams(-1, dp(24), Gravity.BOTTOM))
        d.addView(listArea, LinearLayout.LayoutParams(-1, 0, 1f))

        // The whole line is Settings' button: the words (from the conversations' line) and the gear.
        val foot = Ui.row(app)
        foot.setPadding(dp(12), 0, 0, 0)
        foot.minimumHeight = dp(44)
        foot.background = Ui.press(app, null, dp(22))
        foot.setOnClickListener { if (app.link.linked) app.settings.settingsSheet() else app.link.connect(true) }
        val link = Ui.row(app)
        val dot = View(app)
        linkDot = dot
        link.addView(dot, LinearLayout.LayoutParams(dp(8), dp(8)))
        linkText = Ui.text(app, "正在连接…", 15, R.color.text)
        linkText.typeface = Ui.MEDIUM
        linkText.isSingleLine = true
        linkText.ellipsize = TextUtils.TruncateAt.END
        linkText.setPadding(dp(9), 0, 0, 0)
        link.addView(linkText)
        foot.addView(link, Ui.weight())
        // Round, white with a hairline: as the conversation's own buttons; a tap on it is the line's.
        val gear = Ui.iconButton(app, R.drawable.ic_gear, 44, 20, R.color.text2)
        gear.background = Ui.round(col(R.color.surface), dp(22), dp(1), col(R.color.border))
        gear.isClickable = false
        gear.isFocusable = false
        foot.addView(gear, LinearLayout.LayoutParams(dp(44), dp(44)))
        d.addView(foot, Ui.fill())
        Ui.margins(foot, app, 12, 4, 12, 12)
        return d
    }

    /** The drawer's column of icons: v (w×h dp) in the middle of a 28dp square. */
    private fun drawerSlot(v: View, w: Int, h: Int): FrameLayout {
        val slot = FrameLayout(app)
        slot.addView(v, FrameLayout.LayoutParams(dp(w), dp(h), Gravity.CENTER))
        slot.layoutParams = LinearLayout.LayoutParams(dp(28), dp(28))
        return slot
    }

    fun loadSessions() {
        if (!app.link.linked) {
            renderSessions()
            return
        }
        bridge.get("/sessions") { status, data ->
            val list = data.optJSONArray("sessions")
            if (status == 200 && list != null) keepSessions(list)
            app.frame.whenStill { renderSessions() } // read as the drawer opens: drawn once it is open
        }
    }

    /** The conversation list as last read, kept so it shows at once on the next start. */
    private fun sessionsFile(): File = File(app.filesDir, "sessions.json")

    fun keptSessions(): JSONArray = try {
        val kept = JSONArray(Userland.read(FileInputStream(sessionsFile())))
        for (i in 0 until kept.length()) kept.optJSONObject(i)?.remove("running") // a new start: nothing runs yet
        kept
    } catch (e: IOException) {
        JSONArray()
    } catch (e: JSONException) {
        JSONArray()
    }

    fun keepSessions(next: JSONArray) {
        sessions = next
        sessionsRead = true
        val text = next.toString()
        try {
            work.execute {
                // Written aside, then moved in: a write cut short leaves the last list whole.
                val f = sessionsFile()
                val part = File(f.path + ".tmp")
                try {
                    Userland.write(part, text)
                    if (!part.renameTo(f)) part.delete()
                } catch (ignored: IOException) {
                    // read from the bridge again next time
                }
            }
        } catch (ignored: RejectedExecutionException) {
            // closing
        }
    }

    /** Asks the bridge which conversations contain the words being searched for. */
    private fun searchText() {
        val q = search.trim()
        if (q.isEmpty() || !app.link.linked) return
        bridge.get("/search?q=" + Uri.encode(q)) { status, data ->
            if (q != search.trim() || status != 200) return@get
            hits.clear()
            val a = data.optJSONArray("results")
            if (a != null) for (i in 0 until a.length()) {
                val r = a.optJSONObject(i) ?: continue
                hits[r.optString("id")] = r.optString("snippet")
            }
            renderSessions()
        }
    }

    /** One conversation as the list shows it. */
    private class Row(
        val id: String, val title: String, val folder: String, val group: String,
        val live: Boolean, val active: Boolean, val snippet: String?, val mtime: Double,
    )

    /** What the list last drawn shows, but for its times; the box it is in; its times, row by row. */
    private var drawnRows: String? = null
    private var drawnIn: LinearLayout? = null
    private val drawnTimes = ArrayList<TextView>()

    /**
     * The list, built again only when what it shows changed: a conversation added, gone, moved, renamed,
     * running or not, the open one, the search. Otherwise only the times are read again, in place:
     * building every row anew, on the main thread, stopped the drawer for a tenth of a second as it opened.
     */
    fun renderSessions() {
        traced("cc drawer list") {
            val box = sessionsBox ?: return
            drawnState = drawerState()
            val q = search.trim().lowercase(Locale.ROOT)
            // Only the working folder's (Settings → 外观 → 只显示当前目录): new conversations' folder, not its subfolders.
            val trailing = Regex("/+$")
            val here = if (onlyHere()) claude.workFolder().replace(trailing, "") else ""
            val times = prefs.getBoolean("drawerTimes", true)
            // Pinned ones first, under their own heading.
            val pins = pinned()
            val rows = ArrayList<Row>()
            for (pass in 0..1) {
                for (i in 0 until sessions.length()) {
                    val s = sessions.optJSONObject(i) ?: continue
                    val id = s.optString("id")
                    if (pins.contains(id) != (pass == 0)) continue
                    val titleText = s.optString("title").ifEmpty { "新对话" }
                    val folder = s.optString("cwd")
                    if (here.isNotEmpty() && folder.replace(trailing, "") != here) continue
                    val byName = titleText.lowercase(Locale.ROOT).contains(q) || folder.lowercase(Locale.ROOT).contains(q)
                    if (q.isNotEmpty() && !byName && !hits.containsKey(id)) continue
                    val mtime = s.optDouble("mtime")
                    rows.add(Row(id, titleText, folder, if (pass == 0) "置顶" else group(mtime),
                        app.chats[keyOf(id)]?.running ?: s.optBoolean("running"), id == app.current.sessionId,
                        if (q.isNotEmpty() && !byName) hits[id] ?: "" else null, mtime))
                }
            }
            val shows = buildString {
                append(q).append('\n').append(here).append('\n').append(times).append(app.link.linked).append('\n')
                for (r in rows) append(r.id).append('\t').append(r.title).append('\t').append(r.folder).append('\t').append(r.group)
                    .append('\t').append(r.live).append(r.active).append('\t').append(r.snippet).append('\n')
            }
            if (box === drawnIn && shows == drawnRows) {
                for ((i, whenText) in drawnTimes.withIndex()) {
                    val t = ago(rows[i].mtime)
                    if (whenText.text.toString() != t) whenText.text = t
                }
                return
            }
            drawnIn = box
            drawnRows = shows
            drawnTimes.clear()
            box.removeAllViews()
            var group = ""
            for (r in rows) {
                if (r.group != group) {
                    group = r.group
                    // As Settings' headings: the accent, close under the search, apart from a group above.
                    val label = Ui.text(app, r.group, 14, R.color.accent)
                    label.typeface = Ui.MEDIUM
                    label.setPadding(dp(12), dp(if (box.childCount == 0) 8 else 20), dp(12), dp(8))
                    box.addView(label)
                }
                val item = Ui.column(app)
                item.setPadding(dp(12), dp(10), dp(12), dp(10))
                // The open one on a pale round of the accent, as what is chosen in Settings.
                item.background = Ui.press(app, if (r.active) Ui.round(col(R.color.accentSoft), dp(20)) else null, dp(20))
                val line = Ui.row(app)
                if (r.live) {
                    val dot = View(app)
                    dot.background = Ui.round(col(R.color.accent), dp(4))
                    line.addView(dot, LinearLayout.LayoutParams(dp(7), dp(7)))
                    Ui.margins(dot, app, 0, 0, 6, 0)
                }
                val t = Ui.text(app, r.title, 15, R.color.text)
                t.isSingleLine = true
                t.ellipsize = TextUtils.TruncateAt.END
                line.addView(t, Ui.weight())
                item.addView(line, Ui.fill())
                val meta = Ui.text(app, Ui.base(r.folder), 12.5f, R.color.text3)
                meta.setPadding(0, dp(3), 0, 0)
                meta.isSingleLine = true
                if (r.snippet != null) { // found in what was said: show where
                    val sb = SpannableStringBuilder(r.snippet)
                    val at = r.snippet.lowercase(Locale.ROOT).indexOf(q)
                    if (at >= 0) sb.setSpan(ForegroundColorSpan(col(R.color.text)), at, at + q.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    meta.text = sb
                    meta.ellipsize = TextUtils.TruncateAt.END
                }
                // How long ago, in a column of one width at the end of the folder's line, its text from the
                // column's start: 55m, 1h and 3d begin at one place.
                // Shown unless turned off (Settings → 外观 → 对话时间).
                val under = Ui.row(app)
                under.addView(meta, Ui.weight())
                if (times) {
                    val whenText = Ui.text(app, ago(r.mtime), 11.5f, R.color.text3)
                    whenText.fontFeatureSettings = "tnum"
                    whenText.isSingleLine = true
                    whenText.setPadding(0, dp(3), 0, 0)
                    val column = ceil(whenText.paint.measureText("88mo").toDouble()).toInt()
                    under.addView(whenText, LinearLayout.LayoutParams(column + dp(2), -2))
                    Ui.margins(whenText, app, 10, 0, 0, 0)
                    drawnTimes.add(whenText)
                }
                item.addView(under, Ui.fill())
                item.setOnClickListener { openSession(r.id, r.title, r.folder) }
                item.setOnLongClickListener {
                    sessionActions(r.id, r.title)
                    true
                }
                box.addView(item, Ui.fill())
            }
            if (rows.isEmpty()) {
                val none = if (q.isNotEmpty()) "没有匹配的对话" else if (here.isEmpty()) "还没有对话" else Ui.base(here) + " 里还没有对话"
                val empty = Ui.column(app)
                empty.gravity = Gravity.CENTER_HORIZONTAL
                empty.setPadding(dp(16), dp(48), dp(16), dp(28))
                val words = Ui.text(app, if (app.link.linked) none else "连接后显示历史对话", 14, R.color.text3)
                words.gravity = Gravity.CENTER
                empty.addView(words)
                box.addView(empty, Ui.fill())
            }
        }
    }

    private fun openSession(id: String, titleText: String, folder: String) {
        app.frame.close()
        val key = keyOf(id)
        val chat = app.chats[key] ?: Chat(key).also {
            it.sessionId = id
            it.cwd = folder
            it.loaded = false
            app.chats[key] = it
            app.bySession[id] = key
        }
        if (chat.title.isEmpty()) chat.title = titleText
        app.switchTo(chat)
        app.loadHistory(chat)
        if (app.link.linked) app.sender.opened(chat)
    }

    /** The drawer lists only the working folder's conversations (off by default; Settings → 外观). */
    fun onlyHere(): Boolean = prefs.getBoolean("drawerOnlyHere", false)

    private fun pinned(): MutableSet<String> {
        val out = HashSet<String>()
        val a = savedArray("pinned")
        for (i in 0 until a.length()) out.add(a.optString(i))
        return out
    }

    private fun sessionActions(id: String, titleText: String) {
        sessionsBox?.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        val sheet = sheets.sheet(titleText, null)
        val card = sheets.card(sheet)
        val pin = pinned().contains(id)
        sheets.option(card, R.drawable.ic_pin, if (pin) "取消置顶" else "置顶", null, false) {
            sheet.dismiss()
            val pins = pinned()
            if (pin) pins.remove(id) else pins.add(id)
            prefs.edit().putString("pinned", JSONArray(pins).toString()).apply()
            renderSessions()
        }
        sheets.option(card, R.drawable.ic_pencil, "重命名", null, false) {
            sheet.dismiss()
            sheets.textSheet("重命名", titleText, "", false, { t, done ->
                val name = t.trim()
                if (name.isEmpty()) return@textSheet
                bridge.post("/title", obj("id", id, "title", name)) { status, _ ->
                    if (status != 200) {
                        toast("重命名失败")
                        return@post
                    }
                    app.chats[keyOf(id)]?.title = name
                    done.run()
                    app.screen.updateChrome()
                    loadSessions()
                }
            }, null)
        }
        sheets.option(card, R.drawable.ic_trash, "删除对话", null, true) {
            sheet.dismiss()
            bridge.post("/delete", obj("id", id)) { status, _ ->
                if (status != 200) {
                    toast("删除失败")
                    return@post
                }
                val next = JSONArray()
                for (i in 0 until sessions.length()) {
                    val s = sessions.optJSONObject(i)
                    if (s != null && id != s.optString("id")) next.put(s)
                }
                keepSessions(next)
                val key = keyOf(id)
                if (app.current.key == key) app.switchTo(app.newChat())
                app.drop(key, id)
                renderSessions()
                toast("已删除")
            }
        }
        sheet.show()
    }

    /** The open conversation and those running: all the list shows that events change. */
    fun drawerState(): String {
        val s = StringBuilder(if (app.hasCurrent) app.current.sessionId.toString() else "")
        for (ch in app.chats.values) if (ch.running && ch.sessionId != null) s.append(' ').append(ch.sessionId)
        return s.toString()
    }

    fun updateLink() {
        val dot = linkDot ?: return
        val link = app.link
        // Linked: the user's own words (a setting), nothing when they wrote none.
        val label = if (link.linked) prefs.getString("footer", "") ?: "" else if (link.installing) "正在准备…"
            else if (link.connecting) "正在启动…" else "未运行 · 点此重试"
        val bannerShown = if (!link.linked && !link.connecting && link.setup == null && link.everLinked) View.VISIBLE else View.GONE
        if (app.screen.banner.visibility != bannerShown) app.screen.banner.visibility = bannerShown
        if (label == shownLink) return
        shownLink = label
        val color = if (link.linked) R.color.ok else if (link.connecting) R.color.text3 else R.color.danger
        dot.background = Ui.round(col(color), dp(4))
        dot.visibility = if (link.linked) View.GONE else View.VISIBLE
        linkText.setPadding(if (link.linked) 0 else dp(9), 0, 0, 0)
        linkText.text = label
        (linkText.parent as View).visibility = if (label.isEmpty()) View.INVISIBLE else View.VISIBLE
    }

    private companion object {
        /** How long ago a conversation last changed (its last message), short: now, 5m, 1h, 3d, 2w,
         *  4mo, 1y. Read again each time the drawer opens. */
        fun ago(mtime: Double): String {
            if (mtime.isNaN()) return ""
            val s = max(0, System.currentTimeMillis() / 1000 - mtime.toLong())
            val day = 86_400L
            return when {
                s < 60 -> "now"
                s < 3600 -> "${s / 60}m"
                s < day -> "${s / 3600}h"
                s < 7 * day -> "${s / day}d"
                s < 30 * day -> "${s / (7 * day)}w"
                s < 365 * day -> "${s / (30 * day)}mo"
                else -> "${s / (365 * day)}y"
            }
        }

        fun group(mtime: Double): String {
            val start = Calendar.getInstance()
            start.set(Calendar.HOUR_OF_DAY, 0)
            start.set(Calendar.MINUTE, 0)
            start.set(Calendar.SECOND, 0)
            val t = (mtime * 1000).toLong()
            val s = start.timeInMillis
            val day = 86_400_000L
            return when {
                t >= s -> "今天"
                t >= s - day -> "昨天"
                t >= s - 7 * day -> "过去 7 天"
                t >= s - 30 * day -> "过去 30 天"
                else -> "更早"
            }
        }
    }
}
