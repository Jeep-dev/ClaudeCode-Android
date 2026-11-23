package com.jeep.claudecode

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Files picked for a message: read here (a picture brought down to what the model takes, a file only checked), put in the box by Box. */
class Attachments(app: MainActivity) : Part(app) {

    /** The most files one pick puts in the box. */
    val most = 8

    /** A thing picked in the attach sheet: where it is, and what is known of it already (else asked of its provider). */
    class Pick(@JvmField val uri: Uri, @JvmField val name: String?, @JvmField val type: String?, @JvmField val size: Long)

    /** The + button: the app's own attach sheet (the gallery, the camera, the phone's files). */
    fun pickFiles() = AttachSheet(app).show()

    /** The system's picker, for what the app does not reach itself (cloud drives, other apps' documents). */
    fun systemPicker() {
        val pick = Intent(Intent.ACTION_GET_CONTENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*")
            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        try {
            app.startActivityForResult(pick, MainActivity.PICK_FILES)
        } catch (e: ActivityNotFoundException) {
            toast("没有可用的文件选择器")
        }
    }

    /** Files picked (the result of systemPicker): each is read off the main thread, then put in the box of the conversation open now. */
    fun picked(data: Intent) {
        val uris = ArrayList<Uri>()
        val clip = data.clipData
        if (clip != null) {
            for (i in 0 until clip.itemCount) uris.add(clip.getItemAt(i).uri)
        } else {
            data.data?.let { uris.add(it) }
        }
        attach(uris.map { Pick(it, null, null, -1) })
    }

    /** Puts what was picked in the box of the conversation open now, each read off the main thread, in the order picked. */
    fun attach(picks: List<Pick>) {
        val chat = app.current
        val read = arrayOfNulls<Chat.Attachment>(picks.size.coerceAtMost(most))
        var left = read.size
        for ((i, p) in picks.take(most).withIndex()) {
            work.execute {
                val attached = readAttachment(p.uri, p.name, p.type, p.size)
                ui.post {
                    read[i] = attached
                    if (--left > 0) return@post
                    for (a in read) if (a != null) chat.attachments.add(a)
                    app.screen.updateChrome()
                }
            }
        }
    }

    /** Where the camera is writing the picture it takes (a new row of the gallery), until it answers. */
    private var shooting: Uri? = null

    /** Takes a picture with the phone's camera app, into the gallery (Pictures/Claude Code), and puts it in the box. */
    fun camera() {
        val name = "IMG_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(Date()) + ".jpg"
        val v = ContentValues()
        v.put(MediaStore.MediaColumns.DISPLAY_NAME, name)
        v.put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
        if (Build.VERSION.SDK_INT >= 29) {
            v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Claude Code")
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "Claude Code")
            dir.mkdirs()
            @Suppress("DEPRECATION")
            v.put(MediaStore.MediaColumns.DATA, File(dir, name).path)
        }
        val uri = try {
            app.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v)
        } catch (e: RuntimeException) {
            null
        }
        if (uri == null) {
            toast("没法新建照片：需要存储权限")
            return
        }
        val shoot = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
            .putExtra(MediaStore.EXTRA_OUTPUT, uri)
            .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        shoot.clipData = ClipData.newRawUri("", uri)
        try {
            shooting = uri
            app.startActivityForResult(shoot, MainActivity.CAMERA)
        } catch (e: ActivityNotFoundException) {
            shooting = null
            app.contentResolver.delete(uri, null, null)
            toast("没有可用的相机应用")
        }
    }

    /** The camera answered: the picture goes in the box, or its empty row out of the gallery. */
    fun shot(ok: Boolean) {
        val uri = shooting ?: return
        shooting = null
        if (!ok) {
            work.execute { try { app.contentResolver.delete(uri, null, null) } catch (ignored: RuntimeException) {} }
            return
        }
        attach(listOf(Pick(uri, null, "image/jpeg", -1)))
    }

    private fun readAttachment(uri: Uri, knownName: String?, knownType: String?, knownSize: Long): Chat.Attachment? {
        var name = knownName ?: "file"
        var size = knownSize
        if (knownName == null) try {
            app.contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val n = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val s = c.getColumnIndex(OpenableColumns.SIZE)
                    if (n >= 0 && c.getString(n) != null) name = c.getString(n)
                    if (s >= 0 && !c.isNull(s)) size = c.getLong(s)
                }
            }
        } catch (ignored: RuntimeException) {
            // name stays "file"
        }
        val type = knownType ?: app.contentResolver.getType(uri) ?: FileKit.mime(name)
        try {
            if (type != null && type.matches(Regex("image/(png|jpe?g|gif|webp|bmp|heic|heif|avif)"))) {
                // Claude Code scales a picture for the model itself and keeps the original, whose path,
                // size and scale it gives the model: one it reads goes as it is, up to 10 MB (held in
                // memory as it is sent). BMP, HEIC, HEIF and AVIF, which the API does not take, and a
                // larger one go as JPEG of up to 4096 px.
                val raw = if (type.matches(Regex("image/(bmp|heic|heif|avif)"))) null else readUpTo(uri, 10 shl 20)
                val o = BitmapFactory.Options()
                o.inJustDecodeBounds = true
                app.contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, o) }
                val longer = max(o.outWidth, o.outHeight)
                var sample = 1
                // Decoded only as large as needed: for the thread's copy (2000 px), or for the JPEG sent (4096 px).
                if (raw != null) while (longer / (sample * 2) >= 2000) sample *= 2
                else while (longer / sample > 4096) sample *= 2
                o.inJustDecodeBounds = false
                o.inSampleSize = sample
                val bmp = app.contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, o) }
                if (bmp == null) {
                    ui.post { toast("这张图片读不了") }
                    return null
                }
                val sent = raw ?: jpeg(bmp, 4096, 95)
                val thumb = Bitmap.createScaledBitmap(bmp, dp(64), max(1, dp(64) * bmp.height / bmp.width), true)
                val a = Chat.Attachment(true, name, if (raw == null || type == "image/jpg") "image/jpeg" else type,
                    Base64.encodeToString(sent, Base64.NO_WRAP), null, sent.size.toLong(), thumb)
                // The thread keeps a copy to show, of the size the model sees, not the picture sent.
                val w = if (raw != null) o.outWidth else bmp.width
                val h = if (raw != null) o.outHeight else bmp.height
                a.shown = "data:image/jpeg;base64," + Base64.encodeToString(jpeg(bmp, modelLonger(w, h), 92), Base64.NO_WRAP)
                if (thumb !== bmp) bmp.recycle() // the same bitmap when it is already thumbnail size
                return a
            }
            // A file is read only when it is saved, straight into the folder: never held here.
            app.contentResolver.openInputStream(uri).use { if (it == null) return null }
            return Chat.Attachment(false, name, type, null, uri, size, null)
        } catch (e: Exception) {
            ui.post { toast("这个文件读不了") }
            return null
        }
    }

    /** The bytes at uri, or null when there are more than max. */
    @Throws(IOException::class)
    private fun readUpTo(uri: Uri, max: Int): ByteArray? {
        val out = ByteArrayOutputStream()
        (app.contentResolver.openInputStream(uri) ?: throw IOException("unreadable")).use { input ->
            val buf = ByteArray(65536)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                out.write(buf, 0, n)
                if (out.size() > max) return null
            }
        }
        return out.toByteArray()
    }

    private companion object {
        /** The longer side of a w x h picture as the model sees it: Claude Code brings it down to 2000 px
         *  (as tried), then the API to at most 4784 visual tokens of 28 x 28 px (Claude 4.7 and later). */
        fun modelLonger(w: Int, h: Int): Int {
            var s = min(1.0, 2000.0 / max(w, h))
            while (ceil(w * s / 28) * ceil(h * s / 28) > 4784) s *= 0.99
            return (max(w, h) * s).roundToInt()
        }

        /** A picture as JPEG, scaled down to fit maxPx, on white (JPEG has no transparency). */
        fun jpeg(bmp: Bitmap, maxPx: Int, quality: Int): ByteArray {
            val scale = min(1f, maxPx.toFloat() / max(bmp.width, bmp.height))
            val out = Bitmap.createBitmap(max(1, (bmp.width * scale).roundToInt()),
                max(1, (bmp.height * scale).roundToInt()), Bitmap.Config.ARGB_8888)
            val canvas = Canvas(out)
            canvas.drawColor(Color.WHITE)
            canvas.scale(scale, scale)
            // Filtered: unfiltered scaling drops pixels and leaves text jagged and blurred.
            canvas.drawBitmap(bmp, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
            val jpeg = ByteArrayOutputStream()
            out.compress(Bitmap.CompressFormat.JPEG, quality, jpeg)
            out.recycle()
            return jpeg.toByteArray()
        }
    }
}
