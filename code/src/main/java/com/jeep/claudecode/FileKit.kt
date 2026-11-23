package com.jeep.claudecode

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.TextUtils
import android.util.LruCache
import android.util.Size
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.webkit.MimeTypeMap
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

/** What the attach sheet and the files page share: a file's kind and colour, thumbnails, the pick mark and the list of files. */
object FileKit {
    /** A file or folder as listed. */
    class Entry(@JvmField val file: File, @JvmField val dir: Boolean, @JvmField val size: Long, @JvmField val time: Long) {
        val name: String get() = file.name
        /** A folder's number of items (not hidden), when counted; -1 when not. */
        @JvmField var count = -1
    }

    /** A row of the list: a section's title, a place (a coloured round icon), or a file. */
    sealed class Item {
        class Section(val title: String) : Item()
        class Place(val icon: Int, val tint: Int, val title: String, val sub: String?, val go: Runnable) : Item()
        class Row(val entry: Entry) : Item()
        class Note(val text: String) : Item()
    }

    fun ext(name: String): String {
        val dot = name.lastIndexOf('.')
        return if (dot <= 0 || dot == name.length - 1) "" else name.substring(dot + 1).lowercase(Locale.ROOT)
    }

    /** A file's type by its name: the common ones known here (code as text/plain, which more apps open), else as the system knows them. */
    fun mime(name: String): String? = when (val e = ext(name)) {
        "" -> null
        "md", "kt", "kts", "py", "sh", "js", "ts", "json", "yaml", "yml", "toml", "log", "gradle", "c", "h", "cpp", "rs", "go", "java", "txt" -> "text/plain"
        "pdf" -> "application/pdf"
        "zip" -> "application/zip"
        "doc" -> "application/msword"
        "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        "xls" -> "application/vnd.ms-excel"
        "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        "ppt" -> "application/vnd.ms-powerpoint"
        "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        "csv" -> "text/csv"
        "html", "htm" -> "text/html"
        "apk" -> "application/vnd.android.package-archive"
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "mp4" -> "video/mp4"
        "mp3" -> "audio/mpeg"
        else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(e)
    }

    private val IMAGES = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "avif")
    private val VIDEOS = setOf("mp4", "mkv", "webm", "mov", "3gp", "avi")
    private val TEXT = setOf("txt", "md", "markdown", "log", "csv", "tsv", "json", "jsonl", "xml", "html", "htm", "css", "js", "mjs", "ts", "tsx",
        "jsx", "py", "kt", "kts", "java", "c", "h", "cc", "cpp", "hpp", "rs", "go", "rb", "php", "sh", "bash", "zsh", "fish", "yaml", "yml",
        "toml", "ini", "cfg", "conf", "env", "gradle", "properties", "sql", "swift", "lua", "pl", "r", "dart", "vue", "svelte", "gitignore",
        "dockerfile", "makefile", "srt", "vtt", "tex", "bib", "diff", "patch", "lock")

    fun isImage(name: String) = ext(name) in IMAGES
    fun isVideo(name: String) = ext(name) in VIDEOS
    fun isText(name: String): Boolean {
        val e = ext(name)
        if (e in TEXT) return true
        val n = name.lowercase(Locale.ROOT)
        return e.isEmpty() && (n == "makefile" || n == "dockerfile" || n == "license" || n == "readme" || n.startsWith(".") )
    }

    /** The badge of a file: its colour and the few letters on it (as Telegram's: PDF in red, ZIP in amber…). */
    fun badge(name: String): Pair<Int, String> {
        val e = ext(name)
        val color = when (e) {
            "pdf" -> 0xFFE5534B
            "doc", "docx", "odt", "rtf", "pages" -> 0xFF3E7FD6
            "xls", "xlsx", "ods", "csv", "tsv", "numbers" -> 0xFF2E9E5B
            "ppt", "pptx", "odp", "key" -> 0xFFE8803A
            "zip", "rar", "7z", "tar", "gz", "tgz", "xz", "bz2", "zst" -> 0xFFD9A21B
            "apk", "aab", "xapk" -> 0xFF3DAA6B
            "mp3", "m4a", "flac", "wav", "ogg", "opus", "aac", "amr" -> 0xFFD6577E
            in VIDEOS -> 0xFF6A5ACD
            in IMAGES -> 0xFF4AA3DF
            "md", "txt", "log", "markdown" -> 0xFF7D8A99
            else -> if (isText(name)) 0xFF2B9BA0 else 0xFF8B8F98
        }.toInt()
        val label = when {
            e.isEmpty() -> ""
            e.length <= 4 -> e.uppercase(Locale.ROOT)
            else -> e.substring(0, 4).uppercase(Locale.ROOT)
        }
        return color to label
    }

    /** When: 今天 14:32, 昨天 09:10, 11月20日, 2025年3月2日. */
    fun time(t: Long): String {
        if (t <= 0) return ""
        val now = Calendar.getInstance()
        val at = Calendar.getInstance().apply { timeInMillis = t }
        val day = { c: Calendar -> c.get(Calendar.YEAR) * 400 + c.get(Calendar.DAY_OF_YEAR) }
        val d = Date(t)
        return when {
            day(now) == day(at) -> "今天 " + SimpleDateFormat("HH:mm", Locale.ROOT).format(d)
            day(now) - day(at) == 1 -> "昨天 " + SimpleDateFormat("HH:mm", Locale.ROOT).format(d)
            now.get(Calendar.YEAR) == at.get(Calendar.YEAR) -> SimpleDateFormat("M月d日", Locale.ROOT).format(d)
            else -> SimpleDateFormat("yyyy年M月d日", Locale.ROOT).format(d)
        }
    }

    /** A video's length: 0:32, 12:05, 1:02:40. */
    fun duration(ms: Long): String {
        val s = max(0L, ms / 1000)
        return if (s >= 3600) String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
        else String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60)
    }

    /** What is under a file's name: its size and when; a folder's number of items and when. */
    fun sub(e: Entry): String {
        val first = if (e.dir) (if (e.count >= 0) "${e.count} 项" else "文件夹") else Ui.size(e.size)
        val t = time(e.time)
        return if (t.isEmpty()) first else "$first · $t"
    }

    /** A folder's files and folders: folders first, then by name; hidden ones only if asked. Folders counted when there are not too many. */
    fun list(dir: File, hidden: Boolean): List<Entry>? {
        val files = dir.listFiles() ?: return null
        val out = ArrayList<Entry>(files.size)
        for (f in files) {
            if (!hidden && f.name.startsWith(".")) continue
            val d = f.isDirectory
            out.add(Entry(f, d, if (d) 0 else f.length(), f.lastModified()))
        }
        out.sortWith(compareBy<Entry>({ !it.dir }, { it.name.lowercase(Locale.ROOT) }))
        val dirs = out.count { it.dir }
        if (dirs <= 200) for (e in out) if (e.dir) e.count = e.file.list()?.count { hidden || !it.startsWith(".") } ?: -1
        return out
    }

    /** A name not taken in [dir]: name, then "name (1).ext", "name (2).ext"… */
    fun free(dir: File, name: String): File {
        var f = File(dir, name)
        if (!f.exists()) return f
        val e = ext(name)
        val stem = if (e.isEmpty()) name else name.substring(0, name.length - e.length - 1)
        var i = 1
        while (f.exists()) f = File(dir, if (e.isEmpty()) "$stem ($i)" else "$stem ($i).$e").also { i++ }
        return f
    }

    // ---- thumbnails ----

    private val main = Handler(Looper.getMainLooper())
    private val loaders = Executors.newFixedThreadPool(3)
    private val cache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 10).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    /** Puts what [load] makes (off the main thread, kept for next time) in [v], if v still shows [key] when it is ready. */
    fun thumb(v: ImageView, key: String, load: () -> Bitmap?) {
        v.tag = key
        val hit = cache.get(key)
        if (hit != null) {
            v.setImageBitmap(hit)
            return
        }
        v.setImageDrawable(null)
        loaders.execute {
            if (v.tag != key) return@execute // scrolled past before its turn
            val b = try {
                load()
            } catch (e: Exception) {
                null
            } catch (e: OutOfMemoryError) {
                null
            } ?: return@execute
            cache.put(key, b)
            main.post {
                if (v.tag != key) return@post
                v.setImageBitmap(b)
                v.alpha = 0f
                v.animate().alpha(1f).setDuration(150).start()
            }
        }
    }

    /** A picture or video of the gallery, small (MediaStore's own thumbnail). */
    fun mediaThumb(cr: ContentResolver, uri: Uri, id: Long, video: Boolean, px: Int): Bitmap? {
        if (Build.VERSION.SDK_INT >= 29) return cr.loadThumbnail(uri, Size(px, px), null)
        @Suppress("DEPRECATION")
        return if (video) MediaStore.Video.Thumbnails.getThumbnail(cr, id, MediaStore.Video.Thumbnails.MINI_KIND, null)
        else MediaStore.Images.Thumbnails.getThumbnail(cr, id, MediaStore.Images.Thumbnails.MINI_KIND, null)
    }

    /** A picture file, decoded about [px] on its shorter side. */
    fun fileThumb(f: File, px: Int): Bitmap? {
        val o = BitmapFactory.Options()
        o.inJustDecodeBounds = true
        BitmapFactory.decodeFile(f.path, o)
        if (o.outWidth <= 0) return null
        var s = 1
        while (min(o.outWidth, o.outHeight) / (s * 2) >= px) s *= 2
        o.inJustDecodeBounds = false
        o.inSampleSize = s
        return BitmapFactory.decodeFile(f.path, o)
    }

    /** Rounds a view's corners (its own drawing and its children's). */
    fun rounded(v: View, r: Float) {
        v.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, o: Outline) = o.setRoundRect(0, 0, view.width, view.height, r)
        }
        v.clipToOutline = true
    }

    /**
     * The round mark a picked thing carries, as Telegram's: a white ring on a faint shade when not picked,
     * filled with the accent and its number in it when picked.
     */
    class PickMark(c: Context, private val onPhoto: Boolean) : View(c) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val words = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        /** 0: not picked; else its place among those picked (1, 2…). */
        var number = 0
            set(v) {
                if (field == v) return
                val was = field
                field = v
                invalidate()
                if (was == 0 && v > 0) {
                    scaleX = 0.6f
                    scaleY = 0.6f
                    animate().scaleX(1f).scaleY(1f).setDuration(220).setInterpolator(Motion.EMPHASIZED).start()
                }
            }

        override fun onDraw(canvas: Canvas) {
            val r = min(width, height) / 2f
            val cx = width / 2f
            val cy = height / 2f
            val ring = Ui.dp(context, 1.6f).toFloat()
            if (number > 0) {
                paint.style = Paint.Style.FILL
                paint.color = Ui.color(context, R.color.accent)
                canvas.drawCircle(cx, cy, r - ring / 2, paint)
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = ring
                paint.color = Color.WHITE
                if (onPhoto) canvas.drawCircle(cx, cy, r - ring / 2, paint)
                words.textSize = r * (if (number > 9) 0.9f else 1.05f)
                words.color = Ui.color(context, R.color.onAccent)
                canvas.drawText(number.toString(), cx, cy - (words.descent() + words.ascent()) / 2, words)
            } else {
                paint.style = Paint.Style.FILL
                paint.color = if (onPhoto) 0x33000000 else 0
                canvas.drawCircle(cx, cy, r - ring / 2, paint)
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = ring
                paint.color = if (onPhoto) Color.WHITE else Ui.color(context, R.color.borderStrong)
                canvas.drawCircle(cx, cy, r - ring / 2, paint)
            }
        }
    }

    /**
     * The list of files both pages show. [picked] tells a file's place among those picked (0: not; null: nothing is
     * picked here, and the row shows a chevron or nothing instead of a mark).
     */
    class Adapter(
        private val c: Context,
        private val picked: ((Entry) -> Int)?,
        private val tap: (Entry) -> Unit,
        private val hold: ((Entry) -> Unit)?,
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        var items: List<Item> = emptyList()
            set(v) {
                field = v
                @Suppress("NotifyDataSetChanged")
                notifyDataSetChanged()
            }

        private fun dp(v: Number) = Ui.dp(c, v.toFloat())

        override fun getItemCount() = items.size

        override fun getItemViewType(position: Int) = when (items[position]) {
            is Item.Section -> 0
            is Item.Place -> 1
            is Item.Row -> 2
            is Item.Note -> 3
        }

        private class Holder(v: View) : RecyclerView.ViewHolder(v)

        /** A row's parts, found again when it is bound. */
        private class RowViews(val icon: FrameLayout, val glyph: ImageView, val photo: ImageView, val badge: TextView,
                               val title: TextView, val sub: TextView, val end: FrameLayout, val mark: PickMark, val chevron: ImageView)

        override fun onCreateViewHolder(parent: ViewGroup, type: Int): RecyclerView.ViewHolder = Holder(when (type) {
            0 -> Ui.text(c, "", 13.5f, R.color.text2).apply {
                typeface = Ui.MEDIUM
                setPadding(dp(20), dp(18), dp(20), dp(8))
                layoutParams = RecyclerView.LayoutParams(-1, -2)
            }
            3 -> Ui.text(c, "", 14, R.color.text3).apply {
                gravity = Gravity.CENTER
                setLineSpacing(0f, 1.25f)
                setPadding(dp(32), dp(48), dp(32), dp(48))
                layoutParams = RecyclerView.LayoutParams(-1, -2)
            }
            else -> row()
        })

        private fun row(): View {
            val row = Ui.row(c)
            row.setPadding(dp(16), dp(9), dp(14), dp(9))
            row.minimumHeight = dp(64)
            row.background = Ui.press(c, null, 0)
            row.layoutParams = RecyclerView.LayoutParams(-1, -2)
            val icon = FrameLayout(c)
            val glyph = ImageView(c)
            glyph.scaleType = ImageView.ScaleType.CENTER_INSIDE
            icon.addView(glyph, FrameLayout.LayoutParams(-1, -1))
            val photo = ImageView(c)
            photo.scaleType = ImageView.ScaleType.CENTER_CROP
            icon.addView(photo, FrameLayout.LayoutParams(-1, -1))
            val badge = Ui.text(c, "", 11, R.color.text)
            badge.setTextColor(Color.WHITE)
            badge.typeface = Ui.MEDIUM
            badge.gravity = Gravity.CENTER
            badge.letterSpacing = 0.02f
            icon.addView(badge, FrameLayout.LayoutParams(-1, -1))
            rounded(icon, dp(12).toFloat())
            row.addView(icon, LinearLayout.LayoutParams(dp(46), dp(46)))
            val words = Ui.column(c)
            words.setPadding(dp(14), 0, dp(8), 0)
            val title = Ui.text(c, "", 15.5f, R.color.text)
            title.isSingleLine = true
            title.ellipsize = TextUtils.TruncateAt.MIDDLE
            words.addView(title)
            val sub = Ui.text(c, "", 13, R.color.text3)
            sub.isSingleLine = true
            sub.ellipsize = TextUtils.TruncateAt.END
            sub.setPadding(0, dp(5), 0, 0)
            words.addView(sub)
            row.addView(words, LinearLayout.LayoutParams(0, -2, 1f))
            val end = FrameLayout(c)
            val mark = PickMark(c, false)
            end.addView(mark, FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))
            val chevron = Ui.icon(c, R.drawable.ic_chevron_right, 18, R.color.text3)
            end.addView(chevron, FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER))
            row.addView(end, LinearLayout.LayoutParams(dp(28), dp(28)))
            row.tag = RowViews(icon, glyph, photo, badge, title, sub, end, mark, chevron)
            return row
        }

        override fun onBindViewHolder(h: RecyclerView.ViewHolder, position: Int) {
            when (val item = items[position]) {
                is Item.Section -> (h.itemView as TextView).text = item.title
                is Item.Note -> (h.itemView as TextView).text = item.text
                is Item.Place -> {
                    val v = h.itemView.tag as RowViews
                    v.icon.background = Ui.round(item.tint, dp(23))
                    rounded(v.icon, dp(23).toFloat())
                    v.glyph.setImageResource(item.icon)
                    v.glyph.imageTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
                    v.glyph.setPadding(dp(12), dp(12), dp(12), dp(12))
                    v.photo.tag = null
                    v.photo.setImageDrawable(null)
                    v.badge.text = ""
                    v.title.text = item.title
                    v.sub.text = item.sub ?: ""
                    v.sub.visibility = if (item.sub.isNullOrEmpty()) View.GONE else View.VISIBLE
                    v.mark.visibility = View.GONE
                    v.chevron.visibility = View.VISIBLE
                    h.itemView.setOnClickListener { item.go.run() }
                    h.itemView.setOnLongClickListener(null)
                    h.itemView.isLongClickable = false
                }
                is Item.Row -> bindRow(h.itemView, item.entry)
            }
        }

        override fun onBindViewHolder(h: RecyclerView.ViewHolder, position: Int, payloads: MutableList<Any>) {
            // Picked or not: only the marks change, the pictures stay as they are.
            val item = items[position]
            if (payloads.isNotEmpty() && item is Item.Row && picked != null) {
                val v = h.itemView.tag as RowViews
                v.mark.number = picked.invoke(item.entry)
                h.itemView.isActivated = v.mark.number > 0
                return
            }
            onBindViewHolder(h, position)
        }

        private fun bindRow(row: View, e: Entry) {
            val v = row.tag as RowViews
            rounded(v.icon, dp(12).toFloat())
            v.title.text = e.name
            v.sub.text = sub(e)
            v.sub.visibility = View.VISIBLE
            v.photo.setImageDrawable(null)
            v.photo.tag = null
            v.glyph.setPadding(dp(12), dp(12), dp(12), dp(12))
            if (e.dir) {
                v.icon.background = Ui.round(Ui.color(c, R.color.accentSoft), dp(12))
                v.glyph.setImageResource(R.drawable.ic_folder)
                v.glyph.imageTintList = android.content.res.ColorStateList.valueOf(Ui.color(c, R.color.accent))
                v.badge.text = ""
            } else {
                val (color, label) = badge(e.name)
                v.icon.background = Ui.round(color, dp(12))
                v.glyph.setImageDrawable(null)
                v.badge.text = label
                v.badge.textSize = if (label.length > 3) 10f else 11.5f
                if (isImage(e.name) && e.size in 1..(40L shl 20)) {
                    val px = dp(46)
                    thumb(v.photo, "f:" + e.file.path + ":" + e.time) { fileThumb(e.file, px) }
                }
            }
            val pickable = picked != null && !e.dir
            v.mark.visibility = if (pickable) View.VISIBLE else View.GONE
            v.chevron.visibility = if (e.dir) View.VISIBLE else View.GONE
            v.end.visibility = if (pickable || e.dir) View.VISIBLE else View.INVISIBLE
            if (pickable) v.mark.number = picked!!.invoke(e)
            row.setOnClickListener { tap(e) }
            if (hold != null) row.setOnLongClickListener {
                hold.invoke(e)
                true
            } else {
                row.setOnLongClickListener(null)
                row.isLongClickable = false
            }
        }
    }
}
