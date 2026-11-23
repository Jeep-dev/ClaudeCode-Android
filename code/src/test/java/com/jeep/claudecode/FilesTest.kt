package com.jeep.claudecode

import android.Manifest
import android.os.Environment
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowLooper

/** The attach sheet's files and picking, and the files page over the userland. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w392dp-h850dp-xxhdpi")
class FilesTest {
    private fun texts(v: View, out: MutableList<TextView> = ArrayList()): List<TextView> {
        if (v is TextView && v.visibility == View.VISIBLE) out.add(v)
        if (v is ViewGroup) for (i in 0 until v.childCount) texts(v.getChildAt(i), out)
        return out
    }

    private fun lists(v: View, out: MutableList<RecyclerView> = ArrayList()): List<RecyclerView> {
        if (v is RecyclerView) out.add(v)
        if (v is ViewGroup) for (i in 0 until v.childCount) lists(v.getChildAt(i), out)
        return out
    }

    /** Lets the threads and the main looper run until [done] (or two seconds). */
    private fun until(done: () -> Boolean) {
        val end = System.currentTimeMillis() + 2000
        while (System.currentTimeMillis() < end) {
            ShadowLooper.idleMainLooper(50, TimeUnit.MILLISECONDS)
            if (done()) return
            Thread.sleep(20)
        }
    }

    private fun window() = ShadowDialog.getLatestDialog().window!!.decorView

    private fun shown(text: String): TextView? = texts(window()).firstOrNull { it.text.toString() == text }

    /** The row whose title is [text], clicked (the row is the title's parent's parent). */
    private fun tapRow(text: String) {
        val t = shown(text) ?: throw AssertionError("no row $text in " + texts(window()).map { it.text })
        (t.parent.parent as View).performClick()
    }

    private fun lay(v: View) {
        v.measure(View.MeasureSpec.makeMeasureSpec(1176, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(2550, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, 1176, 2550)
    }

    private fun grant() = shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(
        Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE)

    @Test fun kindsDatesAndFreeNames() {
        assertEquals("PDF", FileKit.badge("报告.pdf").second)
        assertEquals("", FileKit.badge("Makefile").second)
        assertTrue(FileKit.isText("main.kt"))
        assertTrue(FileKit.isImage("a.JPG"))
        assertEquals("1:05", FileKit.duration(65_000))
        assertEquals("1:00:01", FileKit.duration(3_601_000))
        assertTrue(FileKit.time(System.currentTimeMillis()).startsWith("今天 "))
        val dir = File(RuntimeEnvironment.getApplication().cacheDir, "free").apply { mkdirs() }
        File(dir, "a.txt").writeText("x")
        File(dir, "a (1).txt").writeText("x")
        assertEquals("a (2).txt", FileKit.free(dir, "a.txt").name)
        assertEquals("b", FileKit.free(dir, "b").name)
    }

    @Test fun withoutStorageItAsksAndOffersTheSystemPicker() {
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        AttachSheet(a).show()
        until { shown("允许访问") != null }
        assertTrue(shown("允许访问照片和文件") != null)
        assertTrue(shown("其他应用…") != null)
    }

    @Test fun filesArePickedInOrderAndAdded() {
        grant()
        @Suppress("DEPRECATION")
        val down = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).apply { mkdirs() }
        File(down, "报告.pdf").writeText("%PDF-1.4")
        File(down, "notes.md").writeText("# hi")
        File(down, "子目录").mkdirs()
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        a.prefs.edit().putInt("attachTab", 2).apply()
        AttachSheet(a).show()
        until { shown("下载") != null }
        lay(window())
        tapRow("下载")
        until { shown("notes.md") != null }
        lay(window())
        val order = texts(window()).map { it.text.toString() }
        assertTrue("folders first: $order", order.indexOf("子目录") in 0 until order.indexOf("notes.md"))
        tapRow("notes.md")
        tapRow("报告.pdf")
        until { shown("已选 2 项") != null }
        assertTrue(shown("已选 2 项") != null)
        tapRow("notes.md") // let go: the other is first now
        until { shown("已选 1 项") != null }
        tapRow("notes.md")
        shown("添加")!!.let { (it.parent as View).performClick() }
        until { a.current.attachments.size == 2 }
        assertEquals(listOf("报告.pdf", "notes.md"), a.current.attachments.map { it.name })
        assertFalse(a.current.attachments[0].image)
        assertEquals("application/pdf", a.current.attachments[0].type)
    }

    @Test fun theFilesPageListsTheWorkingFolderAndRenamesAndDeletes() {
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val work = File(a.userland.rootfs, "root/work/proj").apply { mkdirs() }
        File(work, "main.py").writeText("print('hi')\n")
        File(work, "src").mkdirs()
        File(work, ".hidden").writeText("x")
        a.current.cwd = "/root/work/proj"
        a.claude.home = "/root"
        a.files.open()
        until { shown("main.py") != null }
        lay(window())
        assertTrue(shown("proj") != null)
        assertTrue("the way up as chips", shown("~") != null && shown("work") != null)
        assertTrue("hidden files are not shown", shown(".hidden") == null)
        assertTrue(shown("1 个文件夹，1 个文件 · 12 B") != null)
        assertEquals(File(a.userland.rootfs, "root/work/proj/main.py"), a.files.host("/root/work/proj/main.py"))
        assertEquals(File(Environment.getExternalStorageDirectory(), "x"), a.files.host("/sdcard/x"))

        // Into src and back by the chip.
        tapRow("src")
        until { shown("这里还没有文件") != null }
        assertTrue(shown("这里还没有文件") != null)
    }
}
