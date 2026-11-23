package com.jeep.claudecode

import android.app.Dialog
import android.content.ClipData
import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.media.MediaScannerConnection
import android.os.Environment
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

/**
 * The files of the userland, from the open conversation's working folder: a full-screen page (the top bar's menu → 文件)
 * with the way back up as chips. A file is previewed (text, pictures) or opened in another app, shared, saved to the
 * phone's downloads, named in the message, renamed or deleted; a folder made the one new conversations start in.
 */
class FilesPage(app: MainActivity) : Part(app) {
    private var page: Dialog? = null
    private lateinit var stage: FrameLayout
    private lateinit var list: RecyclerView
    private lateinit var adapter: FileKit.Adapter
    private lateinit var title: TextView
    private lateinit var info: TextView
    private lateinit var crumbs: LinearLayout
    private lateinit var crumbScroll: HorizontalScrollView
    /** The folder shown, as the userland names it (/root/work…). */
    private var at = ""
    /** The folders gone through, for back to go back to them. */
    private val trail = ArrayList<String>()
    private val scrolled = HashMap<String, Int>()
    private var hidden: Boolean
        get() = prefs.getBoolean("filesHidden", false)
        set(v) = prefs.edit().putBoolean("filesHidden", v).apply()

    /** Where a path of the userland is on the phone: /sdcard and /storage as they are shared in, the rest in the userland's own files. */
    fun host(path: String): File = when {
        path == "/sdcard" || path.startsWith("/sdcard/") -> File(Environment.getExternalStorageDirectory().path + path.substring(7))
        path == "/storage" || path.startsWith("/storage/") -> File(path)
        else -> File(userland.rootfs, path.trimStart('/'))
    }

    private fun child(path: String, name: String) = if (path == "/") "/$name" else "$path/$name"

    private fun parent(path: String): String? {
        if (path == "/" || path.isEmpty()) return null
        val i = path.lastIndexOf('/')
        return if (i <= 0) "/" else path.substring(0, i)
    }

    fun open() {
        if (page != null) return
        if (!userland.rootfs.isDirectory) {
            toast("运行环境还没准备好")
            return
        }
        val d = Dialog(app, R.style.Page)
        stage = FrameLayout(app)
        stage.addView(view())
        d.setContentView(stage)
        Themes.window(d.window, app)
        val under = app.findViewById<View>(android.R.id.content)
        d.window?.let { w ->
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            w.setWindowAnimations(0)
            w.setBackgroundDrawable(ColorDrawable(0))
        }
        d.setOnKeyListener { _, code, e ->
            if (code != KeyEvent.KEYCODE_BACK) return@setOnKeyListener false
            if (e.action == KeyEvent.ACTION_UP && !e.isCanceled) back()
            true
        }
        d.setOnDismissListener {
            page = null
            Motion.reset(under)
        }
        page = d
        trail.clear()
        go(app.current.cwd.ifEmpty { claude.workFolder() }.ifEmpty { "/root" }, null)
        d.show()
        Motion.push(stage, under, null)
    }

    private fun close(then: Runnable? = null) {
        val d = page ?: return
        Motion.pop(app.findViewById(android.R.id.content), stage) {
            d.dismiss()
            then?.run()
        }
    }

    /** Back: to the folder before, else out of the page. */
    private fun back() {
        if (trail.isEmpty()) close() else go(trail.removeAt(trail.size - 1), false)
    }

    private fun view(): View {
        val root = Ui.column(app)
        root.setBackgroundColor(col(R.color.bg))
        root.isClickable = true
        val top = Ui.row(app)
        top.setPadding(dp(14), dp(8), dp(8), dp(4))
        val back = Ui.iconButton(app, R.drawable.ic_back, 48, 22, R.color.text)
        back.background = Ui.press(app, Ui.round(col(R.color.surface), dp(24)), dp(24))
        back.setOnClickListener { close() }
        top.addView(back, LinearLayout.LayoutParams(dp(48), dp(48)))
        val words = Ui.column(app)
        words.setPadding(dp(14), 0, dp(8), 0)
        title = Ui.text(app, "", 24, R.color.text)
        title.typeface = Ui.SERIF
        title.isSingleLine = true
        title.ellipsize = TextUtils.TruncateAt.END
        words.addView(title)
        info = Ui.text(app, "", 13, R.color.text3)
        info.setPadding(0, dp(3), 0, 0)
        words.addView(info)
        top.addView(words, LinearLayout.LayoutParams(0, -2, 1f))
        val more = Ui.iconButton(app, R.drawable.ic_more, 48, 22, R.color.text)
        more.background = Ui.press(app, null, dp(24))
        more.setOnClickListener { menu(it) }
        top.addView(more, LinearLayout.LayoutParams(dp(48), dp(48)))
        root.addView(top, Ui.fill())

        crumbScroll = HorizontalScrollView(app)
        crumbScroll.isHorizontalScrollBarEnabled = false
        crumbs = Ui.row(app)
        crumbs.setPadding(dp(16), dp(6), dp(16), dp(8))
        crumbScroll.addView(crumbs)
        root.addView(crumbScroll, Ui.fill())

        list = RecyclerView(app)
        list.layoutManager = LinearLayoutManager(app)
        list.itemAnimator = null
        list.setPadding(0, dp(4), 0, dp(32))
        list.clipToPadding = false
        adapter = FileKit.Adapter(app, null, { e -> tap(e) }, { e -> actions(e) })
        list.adapter = adapter
        root.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        return root
    }

    /** Shows [path]: [deeper] true slides it in from the right, false from the left, null not at all. */
    private fun go(path: String, deeper: Boolean?) {
        if (at.isNotEmpty() && deeper == true) trail.add(at)
        if (at.isNotEmpty()) scrolled[at] = (list.layoutManager as LinearLayoutManager).findFirstVisibleItemPosition()
        at = path
        title.text = if (path == claude.home && claude.home.isNotEmpty()) "~" else Ui.base(path)
        info.text = ""
        showCrumbs()
        reload()
        if (deeper != null) {
            list.translationX = dp(if (deeper) 32 else -32).toFloat()
            list.alpha = 0f
            list.animate().translationX(0f).alpha(1f).setDuration(220).setInterpolator(Motion.EMPHASIZED).start()
        }
    }

    /** Reads the folder shown again (off the main thread) and lists it. */
    private fun reload() {
        val path = at
        val show = hidden
        work.execute {
            val entries = FileKit.list(host(path), show)
            ui.post {
                if (page == null || at != path) return@post
                if (entries == null) {
                    info.text = "读不了"
                    adapter.items = listOf(FileKit.Item.Note("这个文件夹读不了"))
                    return@post
                }
                val dirs = entries.count { it.dir }
                val size = entries.filter { !it.dir }.sumOf { it.size }
                info.text = when {
                    entries.isEmpty() -> "空文件夹"
                    dirs == entries.size -> "$dirs 个文件夹"
                    dirs == 0 -> "${entries.size} 个文件 · ${Ui.size(size)}"
                    else -> "$dirs 个文件夹，${entries.size - dirs} 个文件 · ${Ui.size(size)}"
                }
                adapter.items = if (entries.isEmpty()) listOf(FileKit.Item.Note("这里还没有文件")) else entries.map { FileKit.Item.Row(it) }
                val was = scrolled[path]
                if (was != null && was > 0) (list.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(was, 0)
                else list.scrollToPosition(0)
            }
        }
    }

    /** The way up as chips: / or ~, then each folder; the one shown filled. */
    private fun showCrumbs() {
        crumbs.removeAllViews()
        val home = claude.home
        val parts = ArrayList<Pair<String, String>>() // label, path
        var rest: String
        if (home.isNotEmpty() && (at == home || at.startsWith("$home/"))) {
            parts.add("/" to "/")
            parts.add("~" to home)
            rest = at.substring(home.length)
        } else {
            parts.add("/" to "/")
            rest = at
        }
        var path = if (parts.size > 1) home else ""
        for (seg in rest.split('/')) {
            if (seg.isEmpty()) continue
            path = "$path/$seg"
            parts.add(seg to path)
        }
        for ((i, p) in parts.withIndex()) {
            val last = i == parts.size - 1
            if (i > 0) {
                val sep = Ui.icon(app, R.drawable.ic_chevron_right, 14, R.color.text3)
                crumbs.addView(sep)
                Ui.margins(sep, app, 2, 0, 2, 0)
            }
            val chip = Ui.text(app, p.first, 13.5f, if (last) R.color.accent else R.color.text2)
            chip.isSingleLine = true
            chip.gravity = Gravity.CENTER
            chip.setPadding(dp(12), 0, dp(12), 0)
            if (last) chip.typeface = Ui.MEDIUM
            chip.background = Ui.press(app, Ui.round(col(if (last) R.color.accentSoft else R.color.surface2), dp(15)), dp(15))
            chip.setOnClickListener { if (p.second != at) go(p.second, true) }
            crumbs.addView(chip, LinearLayout.LayoutParams(-2, dp(30)))
        }
        crumbScroll.post { crumbScroll.fullScroll(View.FOCUS_RIGHT) }
    }

    /** The page's menu: a new folder, hidden files shown or not, read again. */
    private fun menu(anchor: View) {
        val shade = dp(24)
        val room = FrameLayout(app)
        room.setPadding(shade, shade, shade, shade)
        val card = sheets.menuCard()
        room.addView(card, FrameLayout.LayoutParams(-2, -2))
        val pop = PopupWindow(room, -2, -2, true)
        pop.setBackgroundDrawable(ColorDrawable(0))
        pop.isOutsideTouchable = true
        pop.elevation = 0f
        pop.isClippingEnabled = false
        sheets.menuItem(card, pop, R.drawable.ic_plus, "新建文件夹", null) { newFolder() }
        sheets.menuItem(card, pop, R.drawable.ic_eye, if (hidden) "不显示隐藏文件" else "显示隐藏文件", null) {
            hidden = !hidden
            reload()
        }
        sheets.menuItem(card, pop, R.drawable.ic_refresh, "刷新", null) { reload() }
        sheets.menuItem(card, pop, R.drawable.ic_compose, "在这里开始新对话", null) { useFolder(at, true) }
        room.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val width = room.measuredWidth - 2 * shade
        pop.width = width + 2 * shade
        val loc = IntArray(2)
        anchor.getLocationOnScreen(loc)
        val screenW = app.resources.displayMetrics.widthPixels
        pop.showAtLocation(anchor, Gravity.NO_GRAVITY, screenW - dp(6) - width - shade, loc[1] + anchor.height + dp(2) - shade)
        card.pivotX = width.toFloat()
        card.pivotY = 0f
        card.alpha = 0f
        card.scaleX = 0.8f
        card.scaleY = 0.8f
        card.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(250).setInterpolator(Motion.EMPHASIZED).start()
    }

    private fun path(e: FileKit.Entry) = child(at, e.name)

    private fun tap(e: FileKit.Entry) {
        when {
            e.dir -> go(path(e), true)
            FileKit.isImage(e.name) -> Viewer.image(app, e.name) { picture(e.file) }
            FileKit.isText(e.name) || e.size in 1..(64 * 1024) && !FileKit.isVideo(e.name) && FileKit.badge(e.name).second.isEmpty() -> preview(e)
            else -> actions(e)
        }
    }

    private fun picture(f: File): android.graphics.Bitmap? {
        val o = android.graphics.BitmapFactory.Options()
        o.inJustDecodeBounds = true
        android.graphics.BitmapFactory.decodeFile(f.path, o)
        if (o.outWidth <= 0) return null
        o.inSampleSize = Viewer.sample(app, o.outWidth, o.outHeight)
        o.inJustDecodeBounds = false
        return android.graphics.BitmapFactory.decodeFile(f.path, o)
    }

    /** A text file in a sheet, its first 128 KB, selectable; one that is not text has its actions instead. */
    private fun preview(e: FileKit.Entry) {
        work.execute {
            val most = 128 * 1024
            val bytes = try {
                FileInputStream(e.file).use { input ->
                    val buf = ByteArray(minOf(most.toLong(), e.size.coerceAtLeast(0)).toInt().coerceAtLeast(1))
                    var n = 0
                    while (n < buf.size) {
                        val r = input.read(buf, n, buf.size - n)
                        if (r < 0) break
                        n += r
                    }
                    buf.copyOf(n)
                }
            } catch (x: IOException) {
                null
            }
            ui.post {
                if (bytes == null || bytes.take(4096).contains(0.toByte())) {
                    actions(e)
                    return@post
                }
                var text = String(bytes, Charsets.UTF_8)
                if (e.size > most) text += "\n\n……（只显示前 128 KB，共 ${Ui.size(e.size)}）"
                val sheet = sheets.sheet(e.name, FileKit.sub(e))
                val t = Ui.text(app, if (text.isEmpty()) "（空文件）" else text, 12.5f, R.color.text)
                t.typeface = Ui.MONO
                t.setLineSpacing(0f, 1.2f)
                t.setTextIsSelectable(true)
                t.setPadding(dp(12), dp(12), dp(12), dp(12))
                t.background = Ui.round(col(R.color.codeBg), dp(12), dp(1), col(R.color.border))
                sheets.content(sheet).addView(t, Ui.fill())
                val more = sheets.button("更多操作", false) {
                    sheet.dismiss()
                    actions(e)
                }
                val share = sheets.button("分享", true) {
                    sheet.dismiss()
                    share(e.file)
                }
                sheets.actions(sheet, more, share)
                sheet.show()
            }
        }
    }

    /** What may be done with a file or folder, in a sheet. */
    private fun actions(e: FileKit.Entry) {
        val p = path(e)
        val sheet = sheets.sheet(e.name, FileKit.sub(e))
        val card = sheets.card(sheet)
        if (e.dir) {
            sheets.option(card, R.drawable.ic_folder_open, "打开", null, false) {
                sheet.dismiss()
                go(p, true)
            }
        } else {
            if (FileKit.isImage(e.name) || FileKit.isText(e.name)) sheets.option(card, R.drawable.ic_eye, "预览", null, false) {
                sheet.dismiss()
                if (FileKit.isImage(e.name)) Viewer.image(app, e.name) { picture(e.file) } else preview(e)
            }
            sheets.option(card, R.drawable.ic_open, "用其他应用打开", null, false) {
                sheet.dismiss()
                openWith(e.file)
            }
            sheets.option(card, R.drawable.ic_share, "分享", null, false) {
                sheet.dismiss()
                share(e.file)
            }
            sheets.option(card, R.drawable.ic_download, "保存到手机", "下载", false) {
                sheet.dismiss()
                saveOut(e.file)
            }
        }
        sheets.option(card, R.drawable.ic_at, "在消息里引用", null, false) {
            sheet.dismiss()
            mention(p)
        }
        if (e.dir) sheets.option(card, R.drawable.ic_compose, "在这里开始新对话", null, false) {
            sheet.dismiss()
            useFolder(p, true)
        }
        sheets.option(card, R.drawable.ic_copy, "复制路径", null, false) {
            sheet.dismiss()
            app.copy(p)
        }
        val more = sheets.card(sheet)
        Ui.margins(more, app, 0, 10, 0, 0)
        sheets.option(more, R.drawable.ic_pencil, "重命名", null, false) {
            sheet.dismiss()
            rename(e)
        }
        sheets.option(more, R.drawable.ic_trash, "删除", null, true) {
            sheet.dismiss()
            delete(e)
        }
        sheet.show()
    }

    private fun lent(f: File) = FileShare.uri(app, f)

    private fun openWith(f: File) {
        val uri = lent(f)
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(uri, FileKit.mime(f.name) ?: "*/*")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            app.startActivity(Intent.createChooser(i, "打开方式"))
        } catch (x: RuntimeException) {
            toast("没有能打开它的应用")
        }
    }

    private fun share(f: File) {
        val uri = lent(f)
        val i = Intent(Intent.ACTION_SEND).setType(FileKit.mime(f.name) ?: "application/octet-stream")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        i.clipData = ClipData.newRawUri(f.name, uri)
        try {
            app.startActivity(Intent.createChooser(i, "分享 " + f.name))
        } catch (x: RuntimeException) {
            toast("没有能分享的应用")
        }
    }

    /** A copy in the phone's Download folder (never over another file), seen by the gallery and other apps at once. */
    private fun saveOut(f: File) {
        if (!app.hasStorage()) {
            app.storageAsked = Runnable { if (app.hasStorage()) saveOut(f) }
            app.requestStorage()
            return
        }
        work.execute {
            @Suppress("DEPRECATION")
            val down = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val ok = try {
                down.mkdirs()
                val to = FileKit.free(down, f.name)
                FileInputStream(f).use { input -> FileOutputStream(to).use { out -> Userland.copy(input, out) } }
                MediaScannerConnection.scanFile(app, arrayOf(to.path), null, null)
                to.name
            } catch (x: IOException) {
                null
            }
            ui.post { toast(if (ok != null) "已保存到 下载/$ok" else "保存失败") }
        }
    }

    /** The path in the box at the cursor: relative when it is in the conversation's folder, else whole. */
    private fun mention(p: String) {
        val cwd = app.current.cwd.ifEmpty { claude.workFolder() }
        val said = if (cwd.isNotEmpty() && p.startsWith("$cwd/")) p.substring(cwd.length + 1) else p
        close {
            val input = app.box.input
            val text = input.text
            val at = input.selectionStart.coerceIn(0, text.length)
            val before = if (at > 0 && !text[at - 1].isWhitespace()) " " else ""
            val after = if (at < text.length && !text[at].isWhitespace()) " " else " "
            text.insert(at, before + said + after)
            input.requestFocus()
            app.box.showKeyboard()
        }
    }

    /** The folder new conversations start in, as 工作目录 picks it; [now]: a new conversation is opened in it. */
    private fun useFolder(p: String, now: Boolean) {
        claude.cwd = p
        prefs.edit().putString("cwd", p).apply()
        if (app.current.sessionId == null) app.current.cwd = p
        app.drawer.renderSessions()
        app.settings.refreshSettings()
        close {
            if (now && app.current.sessionId != null) app.switchTo(app.newChat())
            app.renderNow()
            toast("新对话在 " + claude.tilde(p) + " 里开始")
        }
    }

    private fun rename(e: FileKit.Entry) {
        sheets.textSheet("重命名", e.name, "新名字", false, { t, done ->
            val n = t.trim()
            if (n.isEmpty() || n.contains("/") || n == "." || n == "..") {
                toast("名字不能为空，也不能带 /")
                return@textSheet
            }
            if (n == e.name) {
                done.run()
                return@textSheet
            }
            val to = File(e.file.parentFile, n)
            if (to.exists()) {
                toast("已经有叫 $n 的了")
                return@textSheet
            }
            if (e.file.renameTo(to)) {
                done.run()
                reload()
            } else {
                toast("重命名失败")
            }
        }, null)
    }

    private fun delete(e: FileKit.Entry) {
        val about = if (e.dir) "文件夹和里面的所有内容都会被删除，不能恢复。" else "删除后不能恢复。"
        val ask = sheets.sheet("删除 ${e.name}？", about)
        val cancel = sheets.button("取消", false) { ask.dismiss() }
        val ok = sheets.button("删除", true) {
            ask.dismiss()
            work.execute {
                val done = if (e.dir) e.file.deleteRecursively() else e.file.delete()
                ui.post {
                    if (!done) toast("删除失败")
                    reload()
                }
            }
        }
        ok.background = Ui.press(app, Ui.round(col(R.color.danger), dp(10)), dp(10))
        ok.setTextColor(0xFFFFFFFF.toInt())
        sheets.actions(ask, cancel, ok)
        ask.show()
    }

    private fun newFolder() {
        sheets.textSheet("新建文件夹", "", "文件夹名", false, { t, done ->
            val n = t.trim()
            if (n.isEmpty() || n.contains("/") || n == "." || n == "..") {
                toast("名字不能为空，也不能带 /")
                return@textSheet
            }
            val f = File(host(at), n)
            if (f.exists()) {
                toast("已经有叫 $n 的了")
                return@textSheet
            }
            if (f.mkdirs()) {
                done.run()
                reload()
            } else {
                toast("新建失败")
            }
        }, null)
    }
}
