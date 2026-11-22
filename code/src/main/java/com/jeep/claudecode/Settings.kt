package com.jeep.claudecode

import android.app.Dialog
import android.content.res.ColorStateList
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject

/** Settings, a full-screen page of pages: the list of them and how one moves to the next, and the pages 对话 and 模型. */
class Settings(app: MainActivity) : Part(app) {
    /** Settings, while it is open. */
    @JvmField var settingsPage: Dialog? = null
    /** What the page in front holds, and its title when it is the list of pages. */
    private lateinit var settingsBox: LinearLayout
    private lateinit var settingsTitle: TextView
    /** The page of Settings shown: null for the list of pages, else one of them. */
    @JvmField var settingsAt: String? = null
    /** The launch options last read from the bridge, shown while they are read again. */
    @JvmField var launchCache: JSONObject? = null
    /** Its pages, one over another while they move. */
    private lateinit var settingsStage: FrameLayout
    /** Reopened at once (the colours or text size changed), not moved in. */
    @JvmField var settingsInstant = false
    /** Until when a move is under way (settingsMoving). */
    private var settingsStill = 0L

    /** Opens Settings, a full-screen page (or refreshes it when open). */
    fun settingsSheet() {
        if (settingsPage == null) {
            val d = Dialog(app, R.style.Page)
            // Its pages, one over another while they move.
            settingsStage = FrameLayout(app)
            settingsStage.addView(settingsView())
            d.setContentView(settingsStage)
            Themes.window(d.window, app)
            val under = app.findViewById<View>(android.R.id.content)
            d.window?.let { w ->
                w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                w.statusBarColor = col(R.color.sidebar)
                w.navigationBarColor = col(R.color.sidebar)
                // It moves itself in (Motion), over the conversation seen through it meanwhile.
                w.setWindowAnimations(0)
                w.setBackgroundDrawable(ColorDrawable(0))
            }
            d.setOnKeyListener { _, code, e ->
                if (code != KeyEvent.KEYCODE_BACK) return@setOnKeyListener false
                if (e.action == KeyEvent.ACTION_UP && !e.isCanceled) settingsBack()
                true
            }
            d.setOnDismissListener {
                // Told after restyle has opened the next one already: that one stays as it is.
                if (settingsPage !== d) return@setOnDismissListener
                settingsPage = null
                settingsAt = null
                Motion.reset(under)
            }
            settingsPage = d
            fillSettings(if (app.link.linked) launchCache else null)
            d.show()
            if (!settingsInstant) {
                settingsMoving()
                Motion.push(settingsStage, under, null)
            }
        }
        refreshSettings()
    }

    /** A page of Settings: the round back button (with the name beside it on the list of pages), its contents scrolling under. */
    private fun settingsView(): View {
        val root = Ui.column(app)
        // Tiles a shade lighter than the page under them.
        root.setBackgroundColor(col(R.color.sidebar))
        root.isClickable = true // touches stay on the page in front while two are shown
        val top = Ui.row(app)
        top.setPadding(dp(14), dp(8), dp(14), dp(4))
        val back = Ui.iconButton(app, R.drawable.ic_back, 48, 22, R.color.text)
        back.background = Ui.press(app, Ui.round(col(R.color.surface), dp(24)), dp(24))
        back.setOnClickListener { settingsBack() }
        top.addView(back, LinearLayout.LayoutParams(dp(48), dp(48)))
        settingsTitle = Ui.text(app, "设置", 26, R.color.text)
        settingsTitle.typeface = Ui.SERIF
        settingsTitle.setPadding(dp(14), 0, 0, 0)
        top.addView(settingsTitle)
        root.addView(top, Ui.fill())
        val scroll = ScrollView(app)
        settingsBox = Ui.column(app)
        settingsBox.setPadding(dp(16), dp(4), dp(16), dp(32))
        scroll.addView(settingsBox)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        return root
    }

    /** A move has begun: taps meanwhile (a second back, a second page) wait for it to end. */
    private fun settingsMoving(): Boolean {
        val now = SystemClock.uptimeMillis()
        if (now < settingsStill) return false
        settingsStill = now + Motion.PAGE
        return true
    }

    /** Back: from a page to the list of pages, from the list out of Settings. */
    private fun settingsBack() {
        val d = settingsPage
        if (d == null || !settingsMoving()) return
        if (settingsAt != null) {
            settingsGo(null)
            return
        }
        Motion.pop(app.findViewById(android.R.id.content), settingsStage) { d.dismiss() }
    }

    private fun settingsOpen(page: String?) {
        if (settingsPage == null || !settingsMoving()) return
        settingsGo(page)
    }

    private fun settingsGo(page: String?) {
        val old = settingsStage.getChildAt(settingsStage.childCount - 1)
        settingsAt = page
        val fresh = settingsView()
        // A page comes in over the list; the list comes back from under the page.
        if (page != null) settingsStage.addView(fresh) else settingsStage.addView(fresh, 0)
        fillSettings(if (app.link.linked) launchCache else null)
        if (page != null) Motion.push(fresh, old) { settingsStage.removeView(old) }
        else Motion.pop(fresh, old) { settingsStage.removeView(old) }
        if (page == "claude") app.claudePage.loadSchema {}
        if (page == "storage") app.appPage.measureStorage()
    }

    /** Reads the options again and redraws Settings, if open. */
    fun refreshSettings() {
        if (settingsPage == null) return
        if (!app.link.linked) {
            fillSettings(null)
            return
        }
        // Claude Code's version is found a moment after the bridge starts.
        bridge.get("/config") { st, config ->
            if (st == 200) claude.applyConfig(config)
            bridge.get("/launch") { status, launch ->
                if (status == 200) launchCache = launch
                fillSettings(if (status == 200) launch else null)
            }
        }
    }

    fun card(box: LinearLayout): LinearLayout {
        val card = Ui.column(app)
        if (sheets.tiled) {
            // Its rows become tiles once Settings is filled (tile).
            card.tag = TILES
            box.addView(card, Ui.fill())
            return card
        }
        card.setPadding(dp(4), dp(4), dp(4), dp(4))
        card.background = Ui.round(col(R.color.surface), dp(14), dp(1), col(R.color.border))
        box.addView(card, Ui.fill())
        return card
    }

    /** Settings' contents; the launch options only with the bridge running (null launch). */
    fun fillSettings(launch: JSONObject?) {
        traced("cc settings") {
            if (settingsPage == null) return
            val box = settingsBox
            box.removeAllViews()
            val at = settingsAt
            val name = PAGES.lastOrNull { it[0] == at }?.get(1) ?: "设置"
            // The list of pages has its name beside the back button; a page, big above its contents.
            settingsTitle.text = if (at == null) name else ""
            if (at != null) {
                val big = Ui.text(app, name, 34, R.color.text)
                big.typeface = Ui.SERIF
                big.setPadding(dp(6), dp(10), 0, dp(14))
                box.addView(big)
            }
            sheets.tiled = true
            try {
                when (at) {
                    null -> fillPages(box)
                    "look" -> app.look.fillLook(box)
                    "talk" -> fillTalk(box, launch)
                    "model" -> fillModel(box)
                    "claude" -> app.claudePage.fillClaude(box)
                    "ext" -> app.ext.fillExt(box, launch)
                    "storage" -> app.appPage.fillStorage(box)
                    "keep" -> app.appPage.fillKeep(box)
                    else -> app.appPage.fillAbout(box)
                }
            } finally {
                sheets.tiled = false
            }
            tile(box)
        }
    }

    /** Each card's rows as tiles one under another: the group's outer corners round, its inner ones nearly square. */
    private fun tile(box: LinearLayout) {
        val big = dp(24).toFloat()
        val small = dp(5).toFloat()
        for (i in 0 until box.childCount) {
            val card = box.getChildAt(i)
            if (TILES != card.tag) continue
            card as LinearLayout
            val n = card.childCount
            for (j in 0 until n) {
                val row = card.getChildAt(j)
                val t = if (j == 0) big else small
                val b = if (j == n - 1) big else small
                val radii = floatArrayOf(t, t, t, t, b, b, b, b)
                val shape = GradientDrawable()
                shape.setColor(col(R.color.surface))
                shape.cornerRadii = radii
                if (row.isClickable) {
                    val mask = GradientDrawable()
                    mask.setColor(0xFF000000.toInt())
                    mask.cornerRadii = radii
                    row.background = RippleDrawable(ColorStateList.valueOf(col(R.color.active)), shape, mask)
                } else {
                    row.background = shape
                }
                if (j > 0) Ui.margins(row, app, 0, 2, 0, 0)
            }
        }
    }

    /** The first level: a row for each page. */
    private fun fillPages(box: LinearLayout) {
        section(box, "通用")
        val a = card(box)
        page(a, R.drawable.ic_image, "look")
        page(a, R.drawable.ic_compose, "talk")
        page(a, R.drawable.ic_link, "model")
        section(box, "Claude Code")
        val b = card(box)
        page(b, R.drawable.ic_gear, "claude")
        page(b, R.drawable.ic_tool, "ext")
        section(box, "应用")
        val c = card(box)
        page(c, R.drawable.ic_folder, "storage")
        page(c, R.drawable.ic_refresh, "keep")
        page(c, R.drawable.ic_book, "about")
    }

    private fun page(card: LinearLayout, icon: Int, id: String) {
        val p = PAGES.lastOrNull { it[0] == id }
        sheets.option(card, icon, p?.get(1) ?: id, p?.get(2), false) { settingsOpen(id) }
    }

    // ---- 对话 ----
    private fun fillTalk(box: LinearLayout, launch: JSONObject?) {
        val talk = card(box)
        sheets.choice(talk, "回复中发消息", arrayOf("排队", "插入"), if (app.sender.insertWhileRunning()) 1 else 0) { i ->
            prefs.edit().putBoolean("insertWhileRunning", i == 1).apply()
            fillSettings(launchCache) // shown chosen
        }
        val folderNow = claude.cwd.ifEmpty { claude.defaultCwd }
        sheets.option(talk, R.drawable.ic_folder, "工作目录", if (folderNow.isEmpty()) "~/work" else claude.tilde(folderNow), false) {
            if (!app.link.linked) {
                toast("Claude Code 还没启动好")
                return@option
            }
            app.pickers.folderSheet(null)
        }
        sheets.option(talk, R.drawable.ic_mic, "语音识别", app.voice.serviceLabel(), false) { app.voice.serviceSheet(::refreshSettings) }
        if (launch == null) return
        val mode = launch.optString("permissionMode", "bypassPermissions")
        val prompt = launch.optString("appendSystemPrompt")
        val extra = launch.optString("extraArgs")
        sheets.option(talk, R.drawable.ic_tool, "权限", mode, false) { app.pickers.permissionSheet(mode) }
        sheets.option(talk, R.drawable.ic_spark, "系统提示", oneLine(prompt), false) {
            sheets.textSheet("系统提示", prompt, "", true, { t, done -> claude.saveLaunch("appendSystemPrompt", t, done) }, ::refreshSettings)
        }
        sheets.option(talk, R.drawable.ic_edit_lines, "启动参数", extra.trim(), false) {
            sheets.textSheet("启动参数", extra, "--max-turns 50", false, { t, done -> claude.saveLaunch("extraArgs", t, done) }, ::refreshSettings)
        }
        section(box, "CLAUDE.md")
        val memory = card(box)
        val folder = app.current.cwd.ifEmpty { folderNow }
        sheets.option(memory, R.drawable.ic_book, "全局", "~/.claude", false) { app.pickers.memorySheet("global", null, "CLAUDE.md") }
        sheets.option(memory, R.drawable.ic_book, Ui.base(folder), claude.tilde(folder), false) {
            app.pickers.memorySheet("folder", folder, "CLAUDE.md · " + Ui.base(folder))
        }
    }

    // ---- 模型 ----
    private fun fillModel(box: LinearLayout) {
        val api = card(box)
        sheets.option(api, R.drawable.ic_link, "API Key", null, false) { app.claudePage.apiSheet() }
    }

    /** The scrolling of the Settings page in front. */
    fun settingsScroll(): ScrollView = settingsBox.parent as ScrollView

    fun section(box: LinearLayout, label: String) {
        val t = Ui.text(app, label, 14, R.color.accent)
        t.typeface = Ui.MEDIUM
        // Close under the page's title, apart from a group above.
        val afterGroup = box.childCount > 0 && TILES == box.getChildAt(box.childCount - 1).tag
        t.setPadding(dp(12), dp(if (afterGroup) 22 else 4), 0, dp(10))
        box.addView(t)
    }

    private companion object {
        /** The pages: id, name, what is in it. */
        val PAGES = arrayOf(
            arrayOf("look", "外观", "主题、显示和字体"), arrayOf("talk", "对话", "工作目录、权限、系统提示和 CLAUDE.md"),
            arrayOf("model", "模型", "API Key"),
            arrayOf("claude", "Claude Code", "settings.json 和环境变量"), arrayOf("ext", "扩展", "技能、插件、MCP、子代理和 Hooks"),
            arrayOf("storage", "存储", "占用的空间和清理"), arrayOf("keep", "后台", "电池优化和保活"),
            arrayOf("about", "关于", "版本、日志和终端"),
        )
        /** The tag of a card whose rows become tiles once the page is filled (tile). */
        const val TILES = "tiles"

        fun oneLine(text: String): String {
            val t = text.trim().replace('\n', ' ')
            return if (t.length > 40) t.substring(0, 40) + "…" else t
        }
    }
}
