package com.jeep.claudecode

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
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

/**
 * Settings' pages drawn with Compose inside its views: each page filled again and again in the one window
 * (moving between pages, a setting changed) still draws, as Compose keeps one recomposer for a window; and a
 * row does what it is for when tapped.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w392dp-h850dp-xxhdpi")
class SettingsPagesTest {
    private fun idle() = repeat(20) { ShadowLooper.idleMainLooper(50, TimeUnit.MILLISECONDS) }

    private fun composeViews(v: View, out: MutableList<ComposeView> = ArrayList()): List<ComposeView> {
        if (v is ComposeView) out.add(v)
        else if (v is ViewGroup) for (i in 0 until v.childCount) composeViews(v.getChildAt(i), out)
        return out
    }

    private fun nodes(view: ComposeView): List<SemanticsNode> {
        val out = ArrayList<SemanticsNode>()
        fun walk(n: SemanticsNode) { out.add(n); n.children.forEach(::walk) }
        walk((view.getChildAt(0) as ViewRootForTest).semanticsOwner.unmergedRootSemanticsNode)
        return out
    }

    private fun texts(n: SemanticsNode) = n.config.getOrNull(SemanticsProperties.Text)?.map { it.text }.orEmpty()

    /** The page in front's Compose content (one: the page being left is gone once filled again). */
    private fun page(a: MainActivity): ComposeView {
        val views = composeViews(a.settings.settingsPage.window!!.decorView).filter { it.isAttachedToWindow }
        assertEquals("one page's content", 1, views.size)
        return views[0]
    }

    private fun words(a: MainActivity) = nodes(page(a)).flatMap(::texts)

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
        val log = nodes(page(a)).firstOrNull { "日志" in texts(it) }
        val clickable = generateSequence(log) { it.parent }.firstOrNull { it.config.contains(SemanticsActions.OnClick) }
        assertNotNull("日志 can be tapped", clickable)
        clickable!!.config[SemanticsActions.OnClick].action!!.invoke()
        idle()
        val sheet = ShadowDialog.getLatestDialog()
        val title = (sheet.window!!.decorView as ViewGroup).let { findText(it, "日志") }
        assertNotNull("the log sheet opened", title)
    }

    /** Taps the row (or button) a text is on. */
    private fun tap(a: MainActivity, text: String) {
        val node = nodes(page(a)).first { text in texts(it) }
        val target = generateSequence(node) { it.parent }.first { it.config.contains(SemanticsActions.OnClick) }
        target.config[SemanticsActions.OnClick].action!!.invoke()
    }

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
        // The crabs are the app's own views, inside Compose.
        val crabs = ArrayList<View>()
        fun walk(v: View) { if (v is Crab) crabs.add(v) else if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i)) }
        walk(page(a))
        assertEquals(Crab.NAMES.size, crabs.size)
        assertTrue("the crabs are laid out", crabs.all { it.width > 0 && it.height > 0 })

        // A toggle: set once its thumb has moved, and the page shows it.
        val times = a.prefs.getBoolean("drawerTimes", true)
        tap(a, "对话时间")
        ShadowLooper.idleMainLooper(100, TimeUnit.MILLISECONDS)
        assertEquals("not yet: the thumb is moving", times, a.prefs.getBoolean("drawerTimes", true))
        idle()
        assertEquals(!times, a.prefs.getBoolean("drawerTimes", true))

        // A choice: set once the fill has moved to it.
        val size = Fonts.size(a)
        val other = if (size == 2) 0 else 2
        show(a, "look")
        tap(a, Fonts.SIZES[other])
        idle()
        assertEquals(other, Fonts.size(a))
        Fonts.setSize(a, size)
    }

    private fun findText(v: View, text: String): TextView? {
        if (v is TextView && v.text.toString() == text) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) findText(v.getChildAt(i), text)?.let { return it }
        return null
    }
}
