package com.jeep.claudecode

import android.app.Dialog
import android.content.ContentUris
import android.content.Intent
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import kotlin.math.max
import kotlin.math.min

/**
 * The + button's sheet, as Telegram's: the gallery's pictures and videos in a grid to pick from (the camera first), and
 * the phone's own files (internal storage, downloads, recent files) browsed here; what the app does not reach goes to
 * the system's picker (其他应用). Things picked across both are numbered, and 添加 puts them in the box.
 */
class AttachSheet(app: MainActivity) : Part(app) {
    /** A picture or video of the gallery. */
    internal class Media(val id: Long, val uri: Uri, val video: Boolean, val name: String, val mime: String?, val size: Long,
                        val bucket: Long, val bucketName: String, val duration: Long, val added: Long)

    /** An album: its pictures and videos, newest first. */
    private class Album(val id: Long?, val name: String, val items: List<Media>)

    private lateinit var dialog: Dialog
    /** What was picked, in the order picked: the key ("m:" and the gallery's uri, "f:" and a file's path) and how to read it. */
    private val picks = LinkedHashMap<String, Attachments.Pick>()

    private lateinit var body: FrameLayout
    private lateinit var head: FrameLayout
    private lateinit var tabBar: LinearLayout
    private lateinit var addBar: LinearLayout
    private lateinit var addCount: TextView
    private val tabViews = ArrayList<View>()
    /** 0: the gallery, 2: the files (1 is the camera, which is not a page). */
    private var tab = 0

    // the gallery
    private var albums: List<Album> = emptyList()
    private var album: Album? = null
    private var grid: RecyclerView? = null
    private val gridAdapter = GridAdapter()

    // the files
    /** The folder shown; null: the files' home (places and recent files). */
    private var dir: File? = null
    private var files: RecyclerView? = null
    private lateinit var fileAdapter: FileKit.Adapter
    private var recent: List<FileKit.Entry>? = null
    /** What each folder was scrolled to, kept while going in and out. */
    private val scrolled = HashMap<String, Int>()

    private val storage: File get() = Environment.getExternalStorageDirectory()

    private lateinit var pull: PullSheet
    private lateinit var grab: View
    private lateinit var sheetBg: android.graphics.drawable.GradientDrawable
    /** In the head while the sheet is whole and the page has no back of its own: back down to half. */
    private var lower: View? = null
    private var whole = 0f

    fun show() {
        pull = PullSheet(app)
        val box = Ui.column(app)
        sheetBg = Ui.round(col(R.color.bg), 0)
        box.background = sheetBg
        box.isClickable = true // touches on the sheet stay on it
        grab = View(app)
        grab.background = Ui.round(col(R.color.borderStrong), dp(2))
        val gp = LinearLayout.LayoutParams(dp(38), dp(4))
        gp.gravity = Gravity.CENTER_HORIZONTAL
        gp.topMargin = dp(8)
        box.addView(grab, gp)
        head = FrameLayout(app)
        box.addView(head, LinearLayout.LayoutParams(-1, dp(56)))
        body = FrameLayout(app)
        box.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        pull.sheet = box
        pull.addView(box, FrameLayout.LayoutParams(-1, -1))

        fileAdapter = FileKit.Adapter(app, { e -> number("f:" + e.file.path) }, { e -> tapFile(e) }, null)

        // The bars stay at the screen's foot, the list passing under them, whatever the sheet's height.
        val bars = FrameLayout(app)
        tabBar = tabs()
        bars.addView(tabBar, FrameLayout.LayoutParams(-1, dp(64)))
        addBar = adds()
        addBar.visibility = View.INVISIBLE
        bars.addView(addBar, FrameLayout.LayoutParams(-1, dp(64)))
        val bp = FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM)
        bp.setMargins(dp(12), 0, dp(12), dp(12))
        pull.addView(bars, bp)

        pull.onMoved = { at ->
            whole = at
            // Whole: square at the top, no handle, the way back down in the head.
            val r = dp(22) * (1 - at.coerceIn(0f, 1f))
            sheetBg.cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            grab.alpha = 1 - at.coerceIn(0f, 1f)
            lower?.let {
                it.alpha = ((at - 0.5f) * 2).coerceIn(0f, 1f)
                it.visibility = if (at > 0.5f) View.VISIBLE else View.GONE
            }
            // On the way out the bars go down with the sheet.
            bars.translationY = if (at < 0) -at * (bars.height + dp(12)) else 0f
        }

        dialog = object : Dialog(app, R.style.Sheet) {
            private var leaving = false

            override fun dismiss() {
                if (leaving) return
                if (!isShowing || app.isFinishing || app.isDestroyed || pull.height == 0) {
                    super.dismiss()
                    return
                }
                leaving = true
                pull.close { super.dismiss() }
            }
        }
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        pull.onClose = Runnable { dialog.cancel() }
        dialog.setContentView(pull, ViewGroup.LayoutParams(-1, -1))
        dialog.window?.let { w ->
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            w.setWindowAnimations(0)
            w.setDimAmount(0f)
            w.setBackgroundDrawable(ColorDrawable(0))
        }
        dialog.setOnKeyListener { _, code, e ->
            if (code != KeyEvent.KEYCODE_BACK) return@setOnKeyListener false
            val inFolder = tab == 2 && dir != null
            if (!inFolder && !pull.expanded) return@setOnKeyListener false
            if (e.action == KeyEvent.ACTION_UP && !e.isCanceled) if (inFolder) up() else pull.collapse()
            true
        }
        showTab(if (prefs.getInt("attachTab", 0) == 2) 2 else 0, false)
        pull.sheet.translationY = app.resources.displayMetrics.heightPixels.toFloat()
        dialog.show()
        pull.open()
    }

    // ---- the bars at the foot ----

    /** The tabs, floating over the list's foot in a round card: 相册, 相机, 文件, the one shown on a tinted pill. */
    private fun tabs(): LinearLayout {
        val bar = Ui.row(app)
        bar.setPadding(dp(6), dp(6), dp(6), dp(6))
        bar.background = Ui.round(col(R.color.surface), dp(32), dp(1), col(R.color.border))
        bar.elevation = dp(6).toFloat()
        val defs = arrayOf(R.drawable.ic_image to "相册", R.drawable.ic_camera to "相机", R.drawable.ic_file to "文件")
        for ((i, d) in defs.withIndex()) {
            val item = Ui.column(app)
            item.gravity = Gravity.CENTER
            val icon = Ui.icon(app, d.first, 24, R.color.text2)
            item.addView(icon)
            val label = Ui.text(app, d.second, 12.5f, R.color.text2)
            label.gravity = Gravity.CENTER
            label.isSingleLine = true
            label.setPadding(0, dp(4), 0, 0)
            item.addView(label, Ui.wrap())
            item.setOnClickListener {
                if (i == 1) {
                    dialog.dismiss()
                    app.attachments.camera()
                } else if (i != tab) {
                    showTab(i, true)
                }
            }
            tabViews.add(item)
            bar.addView(item, LinearLayout.LayoutParams(0, -1, 1f))
        }
        return bar
    }

    private fun paintTabs() {
        for ((i, item) in tabViews.withIndex()) {
            val on = i == tab
            item as LinearLayout
            (item.getChildAt(0) as ImageView).imageTintList = android.content.res.ColorStateList.valueOf(col(if (on) R.color.accent else R.color.text2))
            val label = item.getChildAt(1) as TextView
            label.setTextColor(col(if (on) R.color.accent else R.color.text2))
            label.typeface = if (on) Ui.MEDIUM else android.graphics.Typeface.DEFAULT
            item.background = if (on) Ui.round(col(R.color.accentSoft), dp(26)) else Ui.press(app, null, dp(26))
        }
    }

    /** What takes the tabs' place once something is picked: clear, how many, and 添加. */
    private fun adds(): LinearLayout {
        val bar = Ui.row(app)
        bar.setPadding(dp(8), dp(8), dp(8), dp(8))
        bar.background = Ui.round(col(R.color.surface), dp(32), dp(1), col(R.color.border))
        bar.elevation = dp(6).toFloat()
        val clear = Ui.iconButton(app, R.drawable.ic_x, 48, 20, R.color.text2)
        clear.background = Ui.press(app, Ui.round(col(R.color.surface2), dp(24)), dp(24))
        clear.contentDescription = "清除选择"
        clear.setOnClickListener { clearPicks() }
        bar.addView(clear, LinearLayout.LayoutParams(dp(48), dp(48)))
        addCount = Ui.text(app, "", 15.5f, R.color.text)
        addCount.typeface = Ui.MEDIUM
        addCount.setPadding(dp(14), 0, dp(8), 0)
        bar.addView(addCount, LinearLayout.LayoutParams(0, -2, 1f))
        val add = Ui.row(app)
        add.gravity = Gravity.CENTER
        add.setPadding(dp(20), 0, dp(16), 0)
        add.background = Ui.press(app, Ui.round(col(R.color.accent), dp(24)), dp(24))
        val label = Ui.text(app, "添加", 15.5f, R.color.onAccent)
        label.typeface = Ui.MEDIUM
        add.addView(label)
        val arrow = Ui.icon(app, R.drawable.ic_arrow_up, 18, R.color.onAccent)
        arrow.rotation = 90f
        add.addView(arrow)
        Ui.margins(arrow, app, 6, 0, 0, 0)
        add.setOnClickListener { finish() }
        bar.addView(add, LinearLayout.LayoutParams(-2, dp(48)))
        return bar
    }

    /** The add bar is the one shown (or coming in), not the tabs. */
    private var addsShown = false

    /** The bars as what is picked has it: the tabs when nothing is, else how many and 添加, one sliding in for the other. */
    private fun updateBars() {
        val n = picks.size
        if (n > 0) addCount.text = "已选 $n 项"
        val showAdd = n > 0
        if (showAdd == addsShown) return
        addsShown = showAdd
        val inView = if (showAdd) addBar else tabBar
        val out = if (showAdd) tabBar else addBar
        val lift = dp(24).toFloat()
        inView.animate().cancel()
        out.animate().cancel()
        inView.visibility = View.VISIBLE
        inView.alpha = 0f
        inView.translationY = lift
        inView.animate().alpha(1f).translationY(0f).setDuration(220).setInterpolator(Motion.EMPHASIZED).start()
        out.animate().alpha(0f).translationY(lift).setDuration(180).setInterpolator(Motion.EMPHASIZED)
            .withEndAction {
                // Not when the other way round was asked meanwhile: that one is shown again.
                if (addsShown == showAdd) out.visibility = View.INVISIBLE
            }.start()
    }

    // ---- picking ----

    /** A thing's place among those picked (1, 2…), 0 when not picked. */
    private fun number(key: String): Int {
        var i = 0
        for (k in picks.keys) {
            i++
            if (k == key) return i
        }
        return 0
    }

    /** Picks it, or lets it go when it was; the numbers and the bars follow. */
    private fun toggle(key: String, pick: Attachments.Pick): Boolean {
        if (picks.remove(key) == null) {
            if (picks.size >= app.attachments.most) {
                toast("一次最多 ${app.attachments.most} 个")
                return false
            }
            picks[key] = pick
        }
        grid?.adapter?.notifyItemRangeChanged(0, gridAdapter.itemCount, PICK)
        files?.adapter?.notifyItemRangeChanged(0, fileAdapter.itemCount, PICK)
        updateBars()
        return true
    }

    private fun clearPicks() {
        picks.clear()
        grid?.adapter?.notifyItemRangeChanged(0, gridAdapter.itemCount, PICK)
        files?.adapter?.notifyItemRangeChanged(0, fileAdapter.itemCount, PICK)
        updateBars()
    }

    private fun finish() {
        if (picks.isEmpty()) return
        val list = ArrayList(picks.values)
        dialog.dismiss()
        app.attachments.attach(list)
    }

    // ---- the pages ----

    private fun showTab(i: Int, moved: Boolean) {
        tab = i
        prefs.edit().putInt("attachTab", i).apply()
        paintTabs()
        body.removeAllViews()
        if (!app.hasStorage()) {
            askStorage()
            return
        }
        val page = if (i == 0) galleryPage() else filesPage()
        body.addView(page, FrameLayout.LayoutParams(-1, -1))
        if (moved) {
            page.alpha = 0f
            page.translationX = dp(if (i == 0) -24 else 24).toFloat()
            page.animate().alpha(1f).translationX(0f).setDuration(220).setInterpolator(Motion.EMPHASIZED).start()
        }
    }

    /** No storage permission yet: what it is for, and a button asking for it (the system's picker still works without). */
    private fun askStorage() {
        setHead(if (tab == 0) "相册" else "文件", null, null)
        val col = Ui.column(app)
        col.gravity = Gravity.CENTER_HORIZONTAL
        col.setPadding(dp(32), dp(48), dp(32), dp(96))
        val icon = Ui.icon(app, if (tab == 0) R.drawable.ic_image else R.drawable.ic_folder, 72, R.color.accent)
        icon.setPadding(dp(20), dp(20), dp(20), dp(20))
        icon.background = Ui.round(col(R.color.accentSoft), dp(36))
        col.addView(icon)
        val t = Ui.text(app, "允许访问照片和文件", 18, R.color.text)
        t.gravity = Gravity.CENTER
        t.typeface = Ui.MEDIUM
        t.setPadding(0, dp(20), 0, dp(8))
        col.addView(t)
        val s = Ui.text(app, "这样就能直接在这里挑照片和文件。\n不想给权限，也可以用「其他应用」从系统选择器里选。", 14, R.color.text2)
        s.gravity = Gravity.CENTER
        s.setLineSpacing(0f, 1.3f)
        col.addView(s)
        val allow = sheets.button("允许访问", true) {
            app.storageAsked = Runnable { if (dialog.isShowing && app.hasStorage()) showTab(tab, true) }
            app.requestStorage()
        }
        col.addView(allow, LinearLayout.LayoutParams(-2, dp(44)))
        Ui.margins(allow, app, 0, 24, 0, 0)
        val other = sheets.button("其他应用…", false) {
            dialog.dismiss()
            app.attachments.systemPicker()
        }
        col.addView(other, LinearLayout.LayoutParams(-2, dp(44)))
        Ui.margins(other, app, 0, 10, 0, 0)
        val scroll = ScrollView(app)
        scroll.addView(col, FrameLayout.LayoutParams(-1, -2))
        body.addView(scroll, FrameLayout.LayoutParams(-1, -1))
    }

    /** The head over the page: [back] on the left when given, the title (a tap target when [tapTitle] is given) and a line under it. */
    private fun setHead(title: String, sub: String?, back: Runnable?, tapTitle: ((View) -> Unit)? = null) {
        head.removeAllViews()
        val row = Ui.row(app)
        row.setPadding(dp(6), 0, dp(18), 0)
        lower = null
        if (back == null) {
            // Shown only while the sheet is whole: down to half.
            val b = Ui.iconButton(app, R.drawable.ic_chevron_down, 44, 22, R.color.text)
            b.background = Ui.press(app, null, dp(22))
            b.contentDescription = "收起"
            b.setOnClickListener { pull.collapse() }
            row.addView(b, LinearLayout.LayoutParams(dp(44), dp(44)))
            lower = b
            b.alpha = ((whole - 0.5f) * 2).coerceIn(0f, 1f)
            b.visibility = if (whole > 0.5f) View.VISIBLE else View.GONE
            val gap = View(app)
            row.addView(gap, LinearLayout.LayoutParams(dp(12), 1))
            b.tag = gap
        }
        if (back != null) {
            val b = Ui.iconButton(app, R.drawable.ic_back, 44, 22, R.color.text)
            b.background = Ui.press(app, null, dp(22))
            b.setOnClickListener { back.run() }
            row.addView(b, LinearLayout.LayoutParams(dp(44), dp(44)))
        }
        val words = Ui.column(app)
        val titleRow = Ui.row(app)
        val t = Ui.text(app, title, 17.5f, R.color.text)
        t.typeface = Ui.MEDIUM
        t.isSingleLine = true
        t.ellipsize = TextUtils.TruncateAt.END
        titleRow.addView(t, LinearLayout.LayoutParams(-2, -2, 0f))
        if (tapTitle != null) {
            val chev = Ui.icon(app, R.drawable.ic_chevron_down, 18, R.color.text2)
            titleRow.addView(chev)
            Ui.margins(chev, app, 4, 2, 0, 0)
            titleRow.setPadding(dp(10), dp(6), dp(8), dp(6))
            titleRow.background = Ui.press(app, Ui.round(col(R.color.surface2), dp(18)), dp(18))
            titleRow.setOnClickListener { tapTitle(it) }
            Ui.margins(words, app, -10, 0, 0, 0)
        }
        words.addView(titleRow, Ui.wrap())
        if (!sub.isNullOrEmpty()) {
            val s = Ui.text(app, sub, 12.5f, R.color.text3)
            s.isSingleLine = true
            s.ellipsize = TextUtils.TruncateAt.START
            s.setPadding(0, dp(3), 0, 0)
            words.addView(s)
        }
        row.addView(words, LinearLayout.LayoutParams(0, -2, 1f))
        if (back != null) Ui.margins(words, app, 4, 0, 0, 0)
        head.addView(row, FrameLayout.LayoutParams(-1, -1))
    }

    // ---- the gallery ----

    private fun galleryPage(): View {
        val list = RecyclerView(app)
        val width = app.resources.displayMetrics.widthPixels
        val columns = max(3, (width / app.resources.displayMetrics.density / 130).toInt())
        gridAdapter.columns = columns
        list.layoutManager = GridLayoutManager(app, columns)
        list.addItemDecoration(object : RecyclerView.ItemDecoration() {
            override fun getItemOffsets(out: Rect, v: View, parent: RecyclerView, state: RecyclerView.State) {
                val g = dp(1f)
                out.set(g, g, g, g)
            }
        })
        list.setPadding(dp(3f), 0, dp(3f), dp(96))
        list.clipToPadding = false
        list.itemAnimator = null
        list.adapter = gridAdapter
        grid = list
        if (albums.isEmpty()) {
            setHead("最近项目", null, null) { albumMenu(it) }
            loadGallery()
        } else {
            galleryHead()
            gridAdapter.show(album!!.items)
        }
        return list
    }

    private fun galleryHead() = setHead(album?.name ?: "最近项目", null, null) { albumMenu(it) }

    private fun loadGallery() {
        Thread {
            val all = readGallery()
            ui.post { if (dialog.isShowing) gallery(all) }
        }.start()
    }

    /** The gallery read: its albums (最近项目, 视频, then each folder by size), 最近项目 shown. */
    internal fun gallery(all: List<Media>) {
        val byBucket = LinkedHashMap<Long, ArrayList<Media>>()
        for (m in all) byBucket.getOrPut(m.bucket) { ArrayList() }.add(m)
        val list = ArrayList<Album>()
        list.add(Album(null, "最近项目", all))
        val videos = all.filter { it.video }
        if (videos.isNotEmpty()) list.add(Album(-1L, "视频", videos))
        for ((_, items) in byBucket.entries.sortedByDescending { it.value.size }) list.add(Album(items[0].bucket, items[0].bucketName, items))
        albums = list
        album = list[0]
        if (tab != 0 || grid == null) return
        galleryHead()
        gridAdapter.show(list[0].items)
    }

    /** The gallery's pictures and videos, newest first. */
    private fun readGallery(): List<Media> {
        val out = ArrayList<Media>()
        fun read(base: Uri, video: Boolean) {
            val cols = arrayListOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.MIME_TYPE,
                MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_ADDED, "bucket_id", "bucket_display_name")
            if (video) cols.add(MediaStore.Video.VideoColumns.DURATION)
            val args = Bundle()
            args.putString(android.content.ContentResolver.QUERY_ARG_SQL_SORT_ORDER, MediaStore.MediaColumns.DATE_ADDED + " DESC")
            args.putInt(android.content.ContentResolver.QUERY_ARG_LIMIT, 5000)
            try {
                app.contentResolver.query(base, cols.toTypedArray(), args, null)?.use { c ->
                    while (c.moveToNext()) {
                        val id = c.getLong(0)
                        val name = c.getString(1) ?: "file"
                        if (c.getLong(3) <= 0 && !video) continue
                        out.add(Media(id, ContentUris.withAppendedId(base, id), video, name, c.getString(2), c.getLong(3),
                            c.getLong(5), c.getString(6) ?: "其他", if (video) c.getLong(7) else 0, c.getLong(4)))
                    }
                }
            } catch (e: RuntimeException) {
                // none of that kind
            }
        }
        read(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, false)
        read(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true)
        out.sortByDescending { it.added }
        return out
    }

    /** The albums to pick from, in a card under the title: each with its newest picture and how many. */
    private fun albumMenu(anchor: View) {
        if (albums.isEmpty()) return
        val shade = dp(24)
        val room = FrameLayout(app)
        room.setPadding(shade, shade, shade, shade)
        val card = sheets.menuCard()
        card.minimumWidth = dp(240)
        val scroll = object : ScrollView(app) {
            override fun onMeasure(w: Int, h: Int) =
                super.onMeasure(w, MeasureSpec.makeMeasureSpec(min(dp(420), (resources.displayMetrics.heightPixels * 0.5f).toInt()), MeasureSpec.AT_MOST))
        }
        val rows = Ui.column(app)
        scroll.addView(rows)
        card.addView(scroll, Ui.fill())
        room.addView(card, FrameLayout.LayoutParams(-2, -2))
        val pop = PopupWindow(room, -2, -2, true)
        pop.setBackgroundDrawable(ColorDrawable(0))
        pop.isOutsideTouchable = true
        pop.elevation = 0f
        pop.isClippingEnabled = false
        for (a in albums) {
            val row = Ui.row(app)
            row.setPadding(dp(12), dp(8), dp(18), dp(8))
            row.background = Ui.press(app, null, 0)
            val cover = ImageView(app)
            cover.scaleType = ImageView.ScaleType.CENTER_CROP
            cover.setBackgroundColor(col(R.color.surface2))
            FileKit.rounded(cover, dp(8).toFloat())
            val first = a.items.firstOrNull()
            if (first != null) FileKit.thumb(cover, "m:" + first.uri) { FileKit.mediaThumb(app.contentResolver, first.uri, first.id, first.video, dp(120)) }
            row.addView(cover, LinearLayout.LayoutParams(dp(44), dp(44)))
            val words = Ui.column(app)
            words.setPadding(dp(12), 0, dp(12), 0)
            val n = Ui.text(app, a.name, 15.5f, if (a === album) R.color.accent else R.color.text)
            n.isSingleLine = true
            n.ellipsize = TextUtils.TruncateAt.END
            if (a === album) n.typeface = Ui.MEDIUM
            words.addView(n)
            val k = Ui.text(app, "${a.items.size}", 12.5f, R.color.text3)
            k.setPadding(0, dp(3), 0, 0)
            words.addView(k)
            row.addView(words, LinearLayout.LayoutParams(0, -2, 1f))
            row.setOnClickListener {
                pop.dismiss()
                if (a === album) return@setOnClickListener
                album = a
                galleryHead()
                gridAdapter.show(a.items)
                grid?.scrollToPosition(0)
            }
            rows.addView(row, LinearLayout.LayoutParams(dp(260), -2))
        }
        val at = IntArray(2)
        anchor.getLocationOnScreen(at)
        pop.showAtLocation(anchor, Gravity.NO_GRAVITY, at[0] - shade, at[1] + anchor.height + dp(4) - shade)
        card.pivotX = 0f
        card.pivotY = 0f
        card.alpha = 0f
        card.scaleX = 0.85f
        card.scaleY = 0.85f
        card.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(220).setInterpolator(Motion.EMPHASIZED).start()
    }

    /** The grid: the camera first (in 最近项目), then each picture or video with its mark; a tap picks, a long press shows it whole. */
    private inner class GridAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        var columns = 3
        private var items: List<Media> = emptyList()
        private val camera: Boolean get() = album?.id == null

        fun show(list: List<Media>) {
            items = list
            @Suppress("NotifyDataSetChanged")
            notifyDataSetChanged()
        }

        private fun at(position: Int): Media? = if (camera) (if (position == 0) null else items[position - 1]) else items[position]

        override fun getItemCount() = if (albums.isEmpty()) 0 else items.size + if (camera) 1 else 0
        override fun getItemViewType(position: Int) = if (camera && position == 0) 0 else 1

        private inner class Cell(v: View) : RecyclerView.ViewHolder(v)

        override fun onCreateViewHolder(parent: ViewGroup, type: Int): RecyclerView.ViewHolder {
            val size = (parent.width - parent.paddingLeft - parent.paddingRight) / columns - dp(2)
            val cell = FrameLayout(app)
            cell.layoutParams = RecyclerView.LayoutParams(-1, max(size, dp(80)))
            if (type == 0) {
                cell.background = Ui.press(app, Ui.round(col(R.color.surface2), 0), 0)
                val inner = Ui.column(app)
                inner.gravity = Gravity.CENTER
                inner.addView(Ui.icon(app, R.drawable.ic_camera, 30, R.color.text2))
                val t = Ui.text(app, "拍照", 13, R.color.text2)
                t.setPadding(0, dp(8), 0, 0)
                inner.addView(t, Ui.wrap())
                cell.addView(inner, FrameLayout.LayoutParams(-1, -1))
                cell.setOnClickListener {
                    dialog.dismiss()
                    app.attachments.camera()
                }
                return Cell(cell)
            }
            val photo = ImageView(app)
            photo.scaleType = ImageView.ScaleType.CENTER_CROP
            photo.setBackgroundColor(col(R.color.surface2))
            cell.addView(photo, FrameLayout.LayoutParams(-1, -1))
            val length = Ui.row(app)
            length.setPadding(dp(5), dp(2), dp(7), dp(2))
            length.background = Ui.round(0x66000000, dp(9))
            val play = Ui.icon(app, R.drawable.ic_play, 11, R.color.text)
            play.imageTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            length.addView(play)
            val lt = Ui.text(app, "", 11.5f, R.color.text)
            lt.setTextColor(Color.WHITE)
            lt.typeface = Ui.MEDIUM
            lt.setPadding(dp(3), 0, 0, 0)
            length.addView(lt)
            val lp = FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START)
            lp.setMargins(dp(6), 0, 0, dp(6))
            cell.addView(length, lp)
            val mark = FileKit.PickMark(app, true)
            val mp = FrameLayout.LayoutParams(dp(26), dp(26), Gravity.TOP or Gravity.END)
            mp.setMargins(0, dp(7), dp(7), 0)
            // A bigger target than the mark itself, as Telegram's.
            val hit = FrameLayout(app)
            hit.addView(mark, mp)
            cell.addView(hit, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.TOP or Gravity.END))
            cell.tag = arrayOf(photo, length, lt, mark, hit)
            return Cell(cell)
        }

        override fun onBindViewHolder(h: RecyclerView.ViewHolder, position: Int, payloads: MutableList<Any>) {
            val m = at(position) ?: return
            if (payloads.isNotEmpty()) {
                @Suppress("UNCHECKED_CAST")
                val parts = h.itemView.tag as Array<View>
                marked(parts[0] as ImageView, parts[3] as FileKit.PickMark, number("m:" + m.uri), true)
                return
            }
            onBindViewHolder(h, position)
        }

        override fun onBindViewHolder(h: RecyclerView.ViewHolder, position: Int) {
            val m = at(position) ?: return
            @Suppress("UNCHECKED_CAST")
            val parts = h.itemView.tag as Array<View>
            val photo = parts[0] as ImageView
            val px = dp(160)
            FileKit.thumb(photo, "m:" + m.uri) { FileKit.mediaThumb(app.contentResolver, m.uri, m.id, m.video, px) }
            parts[1].visibility = if (m.video) View.VISIBLE else View.GONE
            (parts[2] as TextView).text = FileKit.duration(m.duration)
            marked(photo, parts[3] as FileKit.PickMark, number("m:" + m.uri), false)
            val pick = View.OnClickListener {
                if (toggle("m:" + m.uri, Attachments.Pick(m.uri, m.name, m.mime, m.size))) it.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK)
            }
            h.itemView.setOnClickListener(pick)
            parts[4].setOnClickListener(pick)
            h.itemView.setOnLongClickListener {
                if (m.video) openOutside(m.uri, m.mime ?: "video/*") else Viewer.image(app, m.name) { Viewer.decode(app, m.uri) }
                true
            }
        }

        /** A picked picture sits a little smaller in its cell, its number on it. */
        private fun marked(photo: ImageView, mark: FileKit.PickMark, n: Int, animate: Boolean) {
            mark.number = n
            val s = if (n > 0) 0.88f else 1f
            if (animate) photo.animate().scaleX(s).scaleY(s).setDuration(200).setInterpolator(Motion.EMPHASIZED).start()
            else {
                photo.scaleX = s
                photo.scaleY = s
            }
        }
    }

    private fun openOutside(uri: Uri, type: String) {
        try {
            app.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, type).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (e: RuntimeException) {
            toast("没有能打开它的应用")
        }
    }

    // ---- the files ----

    private fun filesPage(): View {
        val list = RecyclerView(app)
        list.layoutManager = LinearLayoutManager(app)
        list.setPadding(0, 0, 0, dp(92))
        list.clipToPadding = false
        list.itemAnimator = null
        list.adapter = fileAdapter
        files = list
        openDir(dir, false)
        return list
    }

    private fun place(path: File): String {
        val root = storage.path
        return if (path.path == root) "内部存储" else if (path.path.startsWith("$root/")) "内部存储" + path.path.substring(root.length) else path.path
    }

    /** Shows [to] (null: the files' home), read off the main thread; [deeper]: it slides in from the right, else from the left. */
    private fun openDir(to: File?, deeper: Boolean?) {
        val list = files ?: return
        dir?.let { scrolled[it.path] = (list.layoutManager as LinearLayoutManager).findFirstVisibleItemPosition() }
        dir = to
        if (to == null) {
            setHead("文件", null, null)
            fileAdapter.items = home()
            if (recent == null) Thread {
                val r = recentFiles()
                ui.post {
                    recent = r
                    if (dialog.isShowing && dir == null && tab == 2) fileAdapter.items = home()
                }
            }.start()
        } else {
            setHead(if (to.path == storage.path) "内部存储" else to.name, place(to), Runnable { up() })
            Thread {
                val entries = FileKit.list(to, false)
                ui.post {
                    if (dir != to || !dialog.isShowing) return@post
                    fileAdapter.items = when {
                        entries == null -> listOf(FileKit.Item.Note("这个文件夹读不了"))
                        entries.isEmpty() -> listOf(FileKit.Item.Note("空文件夹"))
                        else -> entries.map { FileKit.Item.Row(it) }
                    }
                    val at = scrolled[to.path]
                    if (at != null && at > 0) (list.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(at, 0)
                }
            }.start()
        }
        if (deeper != null) {
            list.translationX = dp(if (deeper) 32 else -32).toFloat()
            list.alpha = 0f
            list.animate().translationX(0f).alpha(1f).setDuration(220).setInterpolator(Motion.EMPHASIZED).start()
        }
        if (to == null || deeper == true) list.scrollToPosition(0)
    }

    private fun up() {
        val d = dir ?: return
        val parent = d.parentFile
        openDir(if (d.path == storage.path || parent == null || !parent.path.startsWith(storage.path)) null else parent, false)
    }

    /** The files' home: where to look (as Telegram's), then the files changed last. */
    private fun home(): List<FileKit.Item> {
        val out = ArrayList<FileKit.Item>()
        var used = ""
        try {
            val st = StatFs(storage.path)
            used = "已用 " + Ui.size(st.totalBytes - st.availableBytes) + " / 共 " + Ui.size(st.totalBytes)
        } catch (e: RuntimeException) {
            // not known
        }
        out.add(FileKit.Item.Place(R.drawable.ic_phone, 0xFF3E7FD6.toInt(), "内部存储", used) { openDir(storage, true) })
        @Suppress("DEPRECATION")
        val down = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        out.add(FileKit.Item.Place(R.drawable.ic_download, 0xFF2E9E5B.toInt(), "下载", "Download") { openDir(down, true) })
        out.add(FileKit.Item.Place(R.drawable.ic_image, 0xFFE8803A.toInt(), "相册", "照片和视频") { showTab(0, true) })
        out.add(FileKit.Item.Place(R.drawable.ic_apps, 0xFF8B8F98.toInt(), "其他应用", "网盘、云文档等，用系统选择器") {
            dialog.dismiss()
            app.attachments.systemPicker()
        })
        val r = recent
        if (!r.isNullOrEmpty()) {
            out.add(FileKit.Item.Section("最近文件"))
            for (e in r) out.add(FileKit.Item.Row(e))
        }
        return out
    }

    /** The files (not pictures nor videos) changed last on the phone, as the media store knows them. */
    private fun recentFiles(): List<FileKit.Entry> {
        val out = ArrayList<FileKit.Entry>()
        try {
            val args = Bundle()
            args.putString(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION,
                MediaStore.Files.FileColumns.MEDIA_TYPE + "=" + MediaStore.Files.FileColumns.MEDIA_TYPE_NONE + " AND " +
                    MediaStore.MediaColumns.SIZE + ">0")
            args.putString(android.content.ContentResolver.QUERY_ARG_SQL_SORT_ORDER, MediaStore.MediaColumns.DATE_MODIFIED + " DESC")
            args.putInt(android.content.ContentResolver.QUERY_ARG_LIMIT, 200)
            @Suppress("DEPRECATION")
            val cols = arrayOf(MediaStore.MediaColumns.DATA)
            app.contentResolver.query(MediaStore.Files.getContentUri("external"), cols, args, null)?.use { c ->
                while (c.moveToNext() && out.size < 30) {
                    val path = c.getString(0) ?: continue
                    if (path.contains("/.") || path.contains("/Android/")) continue
                    val f = File(path)
                    if (!f.isFile) continue
                    out.add(FileKit.Entry(f, false, f.length(), f.lastModified()))
                }
            }
        } catch (e: RuntimeException) {
            // the media store would not say
        }
        if (out.isEmpty()) {
            // Else the newest in Download and Documents, a level deep.
            @Suppress("DEPRECATION")
            val roots = listOf(Environment.DIRECTORY_DOWNLOADS, Environment.DIRECTORY_DOCUMENTS).map { Environment.getExternalStoragePublicDirectory(it) }
            val all = ArrayList<File>()
            for (r in roots) r.listFiles()?.forEach { f ->
                if (f.name.startsWith(".")) return@forEach
                if (f.isFile) all.add(f) else f.listFiles()?.filter { it.isFile && !it.name.startsWith(".") }?.let { all.addAll(it) }
            }
            all.sortByDescending { it.lastModified() }
            for (f in all.take(30)) out.add(FileKit.Entry(f, false, f.length(), f.lastModified()))
        }
        return out
    }

    private fun tapFile(e: FileKit.Entry) {
        if (e.dir) {
            openDir(e.file, true)
            return
        }
        toggle("f:" + e.file.path, Attachments.Pick(Uri.fromFile(e.file), e.name, FileKit.mime(e.name), e.size))
    }

    private companion object {
        /** A change of what is picked: only the marks are drawn again. */
        const val PICK = "pick"
    }
}
