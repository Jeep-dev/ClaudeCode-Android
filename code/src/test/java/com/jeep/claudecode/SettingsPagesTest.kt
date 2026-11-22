package com.jeep.claudecode

import android.view.View
import android.view.ViewGroup
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowLooper

/** Settings' pages: each filled again and again in the one window (moving between pages, a setting changed) still shows what it is for, and a row does it when tapped. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w392dp-h850dp-xxhdpi")
class SettingsPagesTest {
    private fun idle() = repeat(20) { ShadowLooper.idleMainLooper(50, TimeUnit.MILLISECONDS) }

    /** The page in front's words: the one being left is gone once it has moved away. */
    private fun page(a: MainActivity): View = a.settings.settingsPage!!.window!!.decorView

    private fun words(a: MainActivity) = wordsIn(page(a))

    private fun show(a: MainActivity, at: String) {
        a.settings.settingsAt = at
        a.settings.fillSettings(null)
        idle()
    }

    @Test
    fun pagesDrawEachTimeTheyAreFilled() {
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        idle()
        a.settings.settingsSheet()
        idle()
        repeat(3) {
            show(a, "about")
            assertTrue(words(a).containsAll(listOf("应用", "Claude Code", "日志", "终端")))
            assertTrue(words(a).any { it.startsWith("v") })
            show(a, "storage")
            assertTrue(words(a).containsAll(listOf("对话记录", "工作目录", "临时文件", "运行环境", "清理临时文件", "清空对话记录")))
            assertTrue("not measured yet", words(a).contains("…"))
            show(a, "keep")
            assertTrue(words(a).containsAll(listOf("应用信息", "后台保活")))
        }
    }

    @Test
    fun aRowDoesWhatItIsFor() {
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        idle()
        a.settings.settingsSheet()
        idle()
        show(a, "about")
        tapText(page(a), "日志")
        idle()
        val sheet = ShadowDialog.getLatestDialog()
        val title = (sheet.window!!.decorView as ViewGroup).let { findText(it, "日志") }
        assertNotNull("the log sheet opened", title)
    }

    private fun tap(a: MainActivity, text: String) = tapText(page(a), text)

    @Test
    fun lookPageSetsWhatIsTapped() {
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        idle()
        a.settings.settingsSheet()
        idle()
        show(a, "look")
        val shown = words(a)
        for (w in listOf("主题", "深色模式", "跟随系统", "纯黑背景", "显示", "沉浸式", "对话时间", "底部文字", "螃蟹", "文字", "大小", "中文字体")) {
            assertTrue("shows $w: $shown", w in shown)
        }
        assertTrue("every palette", Themes.ALL.all { it.name in shown })
        assertTrue("every crab's name", Crab.NAMES.all { it in shown })
        // The crabs wearing each style.
        val crabs = ArrayList<View>()
        fun walk(v: View) { if (v is Crab) crabs.add(v) else if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i)) }
        walk(page(a))
        assertEquals(Crab.NAMES.size, crabs.size)
        assertTrue("the crabs are laid out", crabs.all { it.width > 0 && it.height > 0 })

        // A toggle: set, and the page shows it.
        val times = a.prefs.getBoolean("drawerTimes", true)
        tap(a, "对话时间")
        idle()
        assertEquals(!times, a.prefs.getBoolean("drawerTimes", true))

        // A choice: set.
        val size = Fonts.size(a)
        val other = if (size == 2) 0 else 2
        show(a, "look")
        tap(a, Fonts.SIZES[other])
        idle()
        assertEquals(other, Fonts.size(a))
        Fonts.setSize(a, size)
    }

    private fun findText(v: View, text: String) = textIn(v, text)
}
