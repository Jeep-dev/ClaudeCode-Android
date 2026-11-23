package com.jeep.claudecode

import android.Manifest
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.net.Uri
import android.os.Environment
import android.util.LruCache
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowLooper

/** The attach sheet (the gallery with things picked, the files) and the files page, as PNG files (SHOTS directory). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w392dp-h850dp-xxhdpi")
class FilesShotTest {
    private val out = File(System.getProperty("shots", "build/shots"))

    private fun shot(v: View, name: String) {
        out.mkdirs()
        val b = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(b))
        FileOutputStream(File(out, "$name.png")).use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** The screen with the latest sheet over it, dimmed as on the phone. */
    private fun shotOver(a: MainActivity, name: String) {
        val under = a.window.decorView
        val sheet = ShadowDialog.getLatestDialog().window!!.decorView
        val b = Bitmap.createBitmap(under.width, under.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        under.draw(c)
        c.drawColor(Color.argb((0.34f * 255).toInt(), 0, 0, 0))
        c.save()
        c.translate(0f, (under.height - sheet.height).toFloat())
        sheet.draw(c)
        c.restore()
        out.mkdirs()
        FileOutputStream(File(out, "$name.png")).use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun idle() = repeat(30) {
        ShadowLooper.idleMainLooper(50, TimeUnit.MILLISECONDS)
        Thread.sleep(10)
    }

    private fun texts(v: View, out: MutableList<TextView> = ArrayList()): List<TextView> {
        if (v is TextView && v.visibility == View.VISIBLE) out.add(v)
        if (v is ViewGroup) for (i in 0 until v.childCount) texts(v.getChildAt(i), out)
        return out
    }

    private fun tapRow(text: String) {
        val t = texts(ShadowDialog.getLatestDialog().window!!.decorView).first { it.text.toString() == text }
        (t.parent.parent as View).performClick()
    }

    /** A made-up photo: a soft gradient with a sun or hills, in the thumbnail cache under its key. */
    private fun photo(i: Int): Bitmap {
        val skies = intArrayOf(0xFF8EC5FC.toInt(), 0xFFF6D365.toInt(), 0xFFA1C4FD.toInt(), 0xFFFBC2EB.toInt(), 0xFF84FAB0.toInt(),
            0xFFFFD1A9.toInt(), 0xFFB8E1FF.toInt(), 0xFFE0C3FC.toInt())
        val grounds = intArrayOf(0xFF2E7D5B.toInt(), 0xFFB5651D.toInt(), 0xFF3A6EA5.toInt(), 0xFF6B4C9A.toInt(), 0xFF4E8F3A.toInt(),
            0xFF8C5A3C.toInt(), 0xFF2F5D8A.toInt(), 0xFF5A3E7A.toInt())
        val b = Bitmap.createBitmap(360, 360, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = LinearGradient(0f, 0f, 0f, 360f, skies[i % 8], Color.WHITE, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, 360f, 360f, p)
        p.shader = null
        p.color = 0xFFFFF3B0.toInt()
        c.drawCircle(80f + (i * 37) % 200, 90f + (i * 13) % 60, 38f, p)
        p.color = grounds[i % 8]
        c.drawOval(-120f, 230f + (i % 3) * 20, 260f, 520f, p)
        p.color = grounds[(i + 3) % 8]
        c.drawOval(120f, 260f - (i % 2) * 30, 520f, 560f, p)
        return b
    }

    @Suppress("UNCHECKED_CAST")
    private fun keep(key: String, b: Bitmap) {
        val f = FileKit::class.java.getDeclaredField("cache")
        f.isAccessible = true
        (f.get(FileKit) as LruCache<String, Bitmap>).put(key, b)
    }

    @Test fun attachSheet() {
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(
            Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE)
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        a.link.linked = true
        a.link.setup = null
        a.renderNow()
        idle()
        a.prefs.edit().putInt("attachTab", 0).apply()
        val sheet = AttachSheet(a)
        sheet.show()
        idle()
        val now = System.currentTimeMillis() / 1000
        val media = (0 until 30).map { i ->
            val uri = Uri.parse("content://media/external/images/media/${1000 + i}")
            keep("m:$uri", photo(i))
            AttachSheet.Media(1000L + i, uri, i % 7 == 3, "IMG_$i.jpg", "image/jpeg", 1000, if (i % 3 == 0) 1 else 2,
                if (i % 3 == 0) "Camera" else "Screenshots", 32_000L + i * 1000, now - i * 600)
        }
        sheet.gallery(media)
        idle()
        shotOver(a, "20-attach-gallery")

        // Three picked: numbered, smaller in their cells, and the bar below says so.
        val grid = texts(ShadowDialog.getLatestDialog().window!!.decorView) // laid out
        check(grid.isNotEmpty())
        val list = findGrid(ShadowDialog.getLatestDialog().window!!.decorView)!!
        for (pos in intArrayOf(2, 5, 4)) list.findViewHolderForAdapterPosition(pos)?.itemView?.performClick()
        idle()
        shotOver(a, "21-attach-picked")

        // The files: home with places and recent files, then a folder.
        @Suppress("DEPRECATION")
        val down = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).apply { mkdirs() }
        for (n in listOf("季度报告.pdf", "会议纪要.docx", "预算.xlsx", "slides.pptx", "archive.zip", "notes.md", "app-release.apk", "voice.m4a"))
            File(down, n).writeBytes(ByteArray(1000 + n.length * 3170))
        File(down, "Telegram").mkdirs()
        File(down, "Telegram/a.txt").writeText("x")
        tapText("文件")
        idle()
        shotOver(a, "22-attach-files")
        tapRow("下载")
        idle()
        shotOver(a, "23-attach-folder")
    }

    private fun tapText(text: String) {
        val t = texts(ShadowDialog.getLatestDialog().window!!.decorView).first { it.text.toString() == text }
        (t.parent as View).performClick()
    }

    private fun findGrid(v: View): androidx.recyclerview.widget.RecyclerView? {
        if (v is androidx.recyclerview.widget.RecyclerView) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) findGrid(v.getChildAt(i))?.let { return it }
        return null
    }

    @Test fun filesPage() {
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        a.link.linked = true
        a.link.setup = null
        a.claude.home = "/root"
        val work = File(a.userland.rootfs, "root/work/photos-app").apply { mkdirs() }
        File(work, "src").mkdirs()
        File(work, "src/main.py").writeText("print('hi')\n")
        File(work, "tests").mkdirs()
        File(work, "README.md").writeText("# Photos\n\nSorts photos by month.\n")
        File(work, "organize.py").writeText("import os\n".repeat(80))
        File(work, "report.pdf").writeBytes(ByteArray(48_000))
        File(work, "data.csv").writeText("a,b\n".repeat(300))
        File(work, "build.zip").writeBytes(ByteArray(2_400_000))
        a.current.cwd = "/root/work/photos-app"
        a.renderNow()
        idle()
        a.files.open()
        idle()
        shot(ShadowDialog.getLatestDialog().window!!.decorView, "24-files-page")
        // A file's actions.
        val row = texts(ShadowDialog.getLatestDialog().window!!.decorView).first { it.text.toString() == "report.pdf" }
        (row.parent.parent as View).performLongClick()
        idle()
        shotOver(a, "25-files-actions")
    }
}
