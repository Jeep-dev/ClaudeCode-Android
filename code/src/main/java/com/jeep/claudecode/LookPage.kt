package com.jeep.claudecode

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.provider.OpenableColumns
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale

/**
 * Settings → 外观: the colours (a palette each, light, dark or the system's), what is shown, the text.
 * The page is drawn with Compose (Tiles) inside Settings' views; the font sheets stay views, each font's
 * name shown in that font.
 */
class LookPage(app: MainActivity) : Part(app) {
    /** Which sheet a font file is imported from, to return to it. */
    private var fontCjk = true

    fun fillLook(box: LinearLayout) {
        val c = AppColors(app)
        // What the page shows, read as it is filled (each change fills it again).
        val now = Themes.current(app).id
        val dark = c.dark
        val crab = Themes.crabStyle(app)
        val footer = prefs.getString("footer", "") ?: ""
        composeIn(app.settings.settingsPage!!, box) {
            Column(Modifier.fillMaxWidth()) {
                Section(c, "主题", afterGroup = false)
                Tiles(c) {
                    custom { shape -> Swatches(c, shape, now, dark) }
                    choice("深色模式", Themes.MODES.toList(), Themes.mode(app)) { m ->
                        Themes.setMode(app, m)
                        app.restyle()
                    }
                    toggle("纯黑背景", "深色时页面用纯黑", Themes.pureBlack(app)) { on ->
                        Themes.setPureBlack(app, on)
                        if (Themes.dark(app)) app.restyle()
                    }
                }

                Section(c, "显示", afterGroup = true)
                Tiles(c) {
                    toggle("沉浸式", "对话延伸到状态栏下，按钮和输入框是玻璃效果", Themes.immersive(app)) { on ->
                        Themes.setImmersive(app, on)
                        app.restyle()
                    }
                    toggle("标题", "在对话上方显示它的标题", Themes.showTitle(app)) { on ->
                        Themes.setShowTitle(app, on)
                        app.restyle()
                    }
                    toggle("思考", "显示模型的思考过程", Themes.showThinking(app)) { on ->
                        Themes.setShowThinking(app, on)
                        refresh()
                        app.renderNow()
                    }
                    toggle("对话时间", "侧栏里显示每个对话多久以前用过", prefs.getBoolean("drawerTimes", true)) { on ->
                        prefs.edit().putBoolean("drawerTimes", on).apply()
                        refresh()
                        app.drawer.renderSessions()
                    }
                    toggle("只显示当前目录", "侧栏只列出当前工作目录里的对话", app.drawer.onlyHere()) { on ->
                        prefs.edit().putBoolean("drawerOnlyHere", on).apply()
                        refresh()
                        app.drawer.renderSessions()
                    }
                    option(0, "底部文字", footer.ifEmpty { "不显示" }) {
                        sheets.textSheet("底部文字", footer, "", false, { t, done ->
                            prefs.edit().putString("footer", t.trim()).apply()
                            app.drawer.shownLink = null
                            app.drawer.updateLink()
                            done.run()
                        }, app.settings::refreshSettings)
                    }
                }

                Section(c, "螃蟹", afterGroup = true)
                Tiles(c) {
                    custom { shape -> Crabs(c, shape, crab) }
                }

                Section(c, "文字", afterGroup = true)
                Tiles(c) {
                    choice("大小", Fonts.SIZES.toList(), Fonts.size(app)) { i ->
                        Fonts.setSize(app, i)
                        app.restyle()
                    }
                    option(0, "中文字体", Fonts.name(Fonts.cjk(app))) { fontSheet(true) }
                    option(0, "英文字体", Fonts.name(Fonts.latin(app))) { fontSheet(false) }
                }
            }
        }
    }

    private fun refresh() = app.settings.fillSettings(app.settings.launchCache)

    // ---- 主题、螃蟹 ----

    /** The palettes, as RikkaHub has them: four to a row, all as wide, the last row's gaps kept. */
    @Composable
    private fun Swatches(c: AppColors, shape: Shape, now: String, dark: Boolean) {
        Column(
            Modifier.fillMaxWidth().clip(shape).background(c.surface).padding(horizontal = 8.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Themes.ALL.toList().chunked(4).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (t in row) Box(Modifier.weight(1f)) { Swatch(c, t, t.id == now, dark) }
                    repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }

    /**
     * A palette as RikkaHub draws it, in the light or dark in use: a round of its primary's
     * container (left half), the secondary's (top right) and the tertiary's (bottom right), its
     * primary in the middle, larger and ticked when chosen; its name below in its primary.
     */
    @Composable
    private fun Swatch(c: AppColors, t: Themes.Theme, chosen: Boolean, dark: Boolean) {
        val s = t.swatch(dark).map { Color(it) } // primary, its container, the secondary's, the tertiary's, on primary
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                .pressable(c, role = Role.RadioButton) {
                    if (chosen) return@pressable
                    Themes.set(app, t.id)
                    app.restyle()
                }
                .semantics { selected = chosen }
                .padding(vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(48.dp)) {
                    drawArc(s[1], 90f, 180f, true)
                    drawArc(s[2], 270f, 90f, true)
                    drawArc(s[3], 0f, 90f, true)
                    drawCircle(s[0], (if (chosen) 12 else 8).dp.toPx(), Offset(size.width / 2, size.height / 2))
                }
                if (chosen) Image(painterResource(R.drawable.ic_check), null, Modifier.size(16.dp), colorFilter = ColorFilter.tint(s[4]))
            }
            Words(t.name, 12.5.sp, s[0], Modifier.padding(top = 6.dp), medium = true, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }

    /** The crab's styles, three to a row, each worn by it (its own view, standing still), the chosen one marked. */
    @Composable
    private fun Crabs(c: AppColors, shape: Shape, chosen: Int) {
        Column(Modifier.fillMaxWidth().clip(shape).background(c.surface).padding(8.dp)) {
            Crab.NAMES.indices.chunked(3).forEach { row ->
                Row(Modifier.fillMaxWidth()) {
                    for (style in row) Box(Modifier.weight(1f)) { CrabCell(c, style, style == chosen) }
                }
            }
        }
    }

    @Composable
    private fun CrabCell(c: AppColors, style: Int, chosen: Boolean) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                .pressable(c, role = Role.RadioButton) {
                    if (chosen) return@pressable
                    Themes.setCrabStyle(app, style)
                    app.restyle()
                }
                .semantics { selected = chosen }
                .padding(top = 6.dp, bottom = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AndroidView({ Crab(it, style, true) })
            Words(Crab.NAMES[style], 12.5.sp, if (chosen) c.accentPress else c.text2, Modifier.padding(top = 2.dp), medium = chosen)
        }
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
