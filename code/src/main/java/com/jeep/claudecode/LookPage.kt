package com.jeep.claudecode

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale

/** Settings → 外观: the colours (a palette each, light, dark or the system's), what is shown, the text. */
class LookPage(app: MainActivity) : Part(app) {
    /** Which sheet a font file is imported from, to return to it. */
    private var fontCjk = true

    fun fillLook(box: LinearLayout) {
        app.settings.section(box, "主题")
        val themes = app.settings.card(box)
        // As RikkaHub has them: four to a row, all as wide, the last row's gaps kept.
        val grid = Ui.column(app)
        grid.setPadding(dp(8f), dp(12f), dp(8f), dp(12f))
        val now = Themes.current(app).id
        val dark = Themes.dark(app)
        for ((r, row) in Themes.ALL.toList().chunked(4).withIndex()) {
            val line = Ui.row(app)
            line.gravity = Gravity.TOP
            grid.addView(line, Ui.fill())
            if (r > 0) Ui.margins(line, app, 0, 8, 0, 0)
            for (i in 0 until 4) {
                val t = row.getOrNull(i)
                line.addView(if (t == null) View(app) else swatch(t, t.id == now, dark), LinearLayout.LayoutParams(0, if (t == null) 1 else -2, 1f))
                if (i > 0) Ui.margins(line.getChildAt(i), app, 4, 0, 0, 0)
            }
        }
        themes.addView(grid, Ui.fill())
        sheets.choice(themes, "深色模式", Themes.MODES, Themes.mode(app)) { m ->
            Themes.setMode(app, m)
            app.restyle()
        }
        sheets.toggle(themes, "纯黑背景", "深色时页面用纯黑", Themes.pureBlack(app)) { on ->
            Themes.setPureBlack(app, on)
            if (Themes.dark(app)) app.restyle()
        }

        app.settings.section(box, "显示")
        val show = app.settings.card(box)
        sheets.toggle(show, "沉浸式", "对话延伸到状态栏下，按钮和输入框是玻璃效果", Themes.immersive(app)) { on ->
            Themes.setImmersive(app, on)
            app.restyle()
        }
        sheets.toggle(show, "标题", "在对话上方显示它的标题", Themes.showTitle(app)) { on ->
            Themes.setShowTitle(app, on)
            app.restyle()
        }
        sheets.toggle(show, "思考", "显示模型的思考过程", Themes.showThinking(app)) { on ->
            Themes.setShowThinking(app, on)
            refresh()
            app.renderNow()
        }
        sheets.toggle(show, "对话时间", "侧栏里显示每个对话多久以前用过", prefs.getBoolean("drawerTimes", true)) { on ->
            prefs.edit().putBoolean("drawerTimes", on).apply()
            refresh()
            app.drawer.renderSessions()
        }
        sheets.toggle(show, "只显示当前目录", "侧栏只列出当前工作目录里的对话", app.drawer.onlyHere()) { on ->
            prefs.edit().putBoolean("drawerOnlyHere", on).apply()
            refresh()
            app.drawer.renderSessions()
        }
        val footer = prefs.getString("footer", "") ?: ""
        sheets.option(show, 0, "底部文字", footer.ifEmpty { "不显示" }, false) {
            sheets.textSheet("底部文字", footer, "", false, { t, done ->
                prefs.edit().putString("footer", t.trim()).apply()
                app.drawer.shownLink = null
                app.drawer.updateLink()
                done.run()
            }, app.settings::refreshSettings)
        }

        app.settings.section(box, "螃蟹")
        val crabs = app.settings.card(box)
        val styles = Ui.column(app)
        styles.setPadding(dp(8f), dp(8f), dp(8f), dp(8f))
        for (row in Crab.NAMES.indices.chunked(3)) {
            val line = Ui.row(app)
            styles.addView(line, Ui.fill())
            for (style in row) line.addView(crabCell(style), LinearLayout.LayoutParams(0, -2, 1f))
        }
        crabs.addView(styles, Ui.fill())

        app.settings.section(box, "文字")
        val text = app.settings.card(box)
        sheets.choice(text, "大小", Fonts.SIZES, Fonts.size(app)) { i ->
            Fonts.setSize(app, i)
            app.restyle()
        }
        sheets.option(text, 0, "中文字体", Fonts.name(Fonts.cjk(app)), false) { fontSheet(true) }
        sheets.option(text, 0, "英文字体", Fonts.name(Fonts.latin(app)), false) { fontSheet(false) }
    }

    private fun refresh() = app.settings.fillSettings(app.settings.launchCache)

    // ---- 主题、螃蟹 ----

    /** One of the crab's styles, wearing it, the chosen one marked. */
    private fun crabCell(style: Int): View {
        val chosen = Themes.crabStyle(app) == style
        val cell = Ui.column(app)
        cell.gravity = Gravity.CENTER_HORIZONTAL
        cell.setPadding(0, dp(6f), 0, dp(8f))
        cell.background = Ui.press(app, null, dp(16f))
        cell.addView(Crab(app, style, true), LinearLayout.LayoutParams(-2, -2))
        val name = Ui.text(app, Crab.NAMES[style], 12.5f, if (chosen) R.color.accentPress else R.color.text2)
        if (chosen) name.typeface = Ui.MEDIUM
        name.setPadding(0, dp(2f), 0, 0)
        cell.addView(name)
        cell.isSelected = chosen
        cell.setOnClickListener {
            if (chosen) return@setOnClickListener
            Themes.setCrabStyle(app, style)
            app.restyle()
        }
        return cell
    }

    /**
     * A palette as RikkaHub draws it, in the light or dark in use: a round of its primary's
     * container (left half), the secondary's (top right) and the tertiary's (bottom right), its
     * primary in the middle, larger and ticked when chosen; its name below in its primary.
     */
    private fun swatch(t: Themes.Theme, chosen: Boolean, dark: Boolean): View {
        val c = t.swatch(dark) // primary, its container, the secondary's, the tertiary's, on primary
        val cell = Ui.column(app)
        cell.gravity = Gravity.CENTER_HORIZONTAL
        cell.setPadding(0, dp(8f), 0, dp(8f))
        cell.background = Ui.press(app, null, dp(16f))
        val disc = FrameLayout(app)
        val middle = dp(if (chosen) 12f else 8f).toFloat()
        val circle = object : View(app) {
            private val p = Paint(Paint.ANTI_ALIAS_FLAG)
            private val oval = RectF()

            override fun onDraw(canvas: Canvas) {
                val w = width.toFloat()
                oval.set(0f, 0f, w, w)
                p.color = c[1]
                canvas.drawArc(oval, 90f, 180f, true, p)
                p.color = c[2]
                canvas.drawArc(oval, 270f, 90f, true, p)
                p.color = c[3]
                canvas.drawArc(oval, 0f, 90f, true, p)
                p.color = c[0]
                canvas.drawCircle(w / 2f, w / 2f, middle, p)
            }
        }
        disc.addView(circle, FrameLayout.LayoutParams(dp(48f), dp(48f)))
        if (chosen) {
            val tick = ImageView(app)
            tick.setImageResource(R.drawable.ic_check)
            tick.imageTintList = ColorStateList.valueOf(c[4])
            disc.addView(tick, FrameLayout.LayoutParams(dp(16f), dp(16f), Gravity.CENTER))
        }
        cell.addView(disc, LinearLayout.LayoutParams(dp(48f), dp(48f)))
        val name = Ui.text(app, t.name, 12.5f, R.color.text)
        name.setTextColor(c[0])
        name.typeface = Ui.MEDIUM
        name.setPadding(0, dp(6f), 0, 0)
        name.isSingleLine = true
        name.ellipsize = android.text.TextUtils.TruncateAt.END
        cell.addView(name)
        cell.isSelected = chosen
        cell.setOnClickListener {
            if (chosen) return@setOnClickListener
            Themes.set(app, t.id)
            app.restyle()
        }
        return cell
    }

    // ---- fonts ----

    private fun fontSheet(cjk: Boolean) {
        fontCjk = cjk
        val sheet = sheets.sheet(if (cjk) "中文字体" else "英文字体", null)
        val card = sheets.card(sheet)
        val now = if (cjk) Fonts.cjk(app) else Fonts.latin(app)
        val wait = Ui.text(app, "…", 14f, R.color.text3)
        wait.gravity = Gravity.CENTER
        wait.setPadding(0, dp(18f), 0, dp(18f))
        card.addView(wait, Ui.fill())
        val add = sheets.card(sheet)
        Ui.margins(add, app, 0, 10, 0, 0)
        sheets.option(add, R.drawable.ic_plus, "导入字体文件", "ttf / otf", false) {
            sheet.dismiss()
            val pick = Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
            try {
                app.startActivityForResult(pick, MainActivity.PICK_FONT)
            } catch (e: ActivityNotFoundException) {
                toast("没有可用的文件选择器")
            }
        }
        sheet.show()
        // Reading the phone's fonts takes a moment: off the main thread.
        work.execute {
            val all = Fonts.choices(app, cjk)
            ui.post {
                card.removeAllViews()
                val sample = if (cjk) "  永字八法" else "  Aa"
                for (f in all) {
                    sheets.option(card, 0, f.name + sample, null, false) {
                        Fonts.set(app, cjk, f.id)
                        app.md.fontChanged()
                        sheet.dismiss()
                        app.screen.adapter.notifyDataSetChanged()
                        app.settings.refreshSettings()
                    }
                    val row = card.getChildAt(card.childCount - 1) as LinearLayout
                    (row.getChildAt(0) as TextView).typeface = f.preview
                    if (f.id == now) sheets.check(card)
                }
            }
        }
    }

    /** Copies a picked font file into files/fonts, where it is offered with the others. */
    fun addFont(uri: Uri) {
        work.execute {
            var name = "font.ttf"
            try {
                app.contentResolver.query(uri, null, null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        val n = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (n >= 0) c.getString(n)?.let { name = it }
                    }
                }
            } catch (ignored: RuntimeException) {
                // default name
            }
            val safe = name.replace(Regex("[/\\\\]"), "_")
            if (!safe.lowercase(Locale.ROOT).matches(Regex(".*\\.(ttf|otf|ttc)$"))) {
                ui.post { toast("只支持 ttf、otf、ttc") }
                return@execute
            }
            val out = File(Fonts.dir(app), safe)
            var error: String? = null
            try {
                val input = app.contentResolver.openInputStream(uri) ?: throw IOException("读不到文件")
                input.use {
                    Fonts.dir(app).mkdirs()
                    FileOutputStream(out).use { o -> Userland.copy(it, o) }
                }
                if (Typeface.createFromFile(out) == Typeface.DEFAULT) throw IOException("不是可用的字体")
            } catch (e: Exception) {
                out.delete()
                error = e.message
            }
            ui.post {
                if (error != null) { toast("导入失败：$error"); return@post }
                toast("已导入")
                fontSheet(fontCjk)
            }
        }
    }
}
