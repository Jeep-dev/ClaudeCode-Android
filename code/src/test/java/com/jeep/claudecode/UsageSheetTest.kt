package com.jeep.claudecode

import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/**
 * 用量 drawn with Compose inside the app's own sheet (no picture: that is ScreensTest's, on a machine with
 * Robolectric's native graphics): it composes in a window with no lifecycle of the activity's, shows what
 * it is given, and is let go with the sheet.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w392dp-h850dp-xxhdpi")
class UsageSheetTest {
    private fun idle() = repeat(30) { ShadowLooper.idleMainLooper(50, TimeUnit.MILLISECONDS) }

    private fun find(v: View): ComposeView? {
        if (v is ComposeView) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) find(v.getChildAt(i))?.let { return it }
        return null
    }

    /** Every text and description Compose drew. */
    private fun words(view: ComposeView): List<String> {
        val root = (view.getChildAt(0) as ViewRootForTest).semanticsOwner.unmergedRootSemanticsNode
        val out = ArrayList<String>()
        fun walk(n: SemanticsNode) {
            n.config.getOrNull(SemanticsProperties.Text)?.forEach { out.add(it.text) }
            n.config.getOrNull(SemanticsProperties.ContentDescription)?.let { out.addAll(it) }
            n.children.forEach(::walk)
        }
        walk(root)
        return out
    }

    @Test
    fun drawsWhatItIsGiven() {
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        idle()
        val cards = a.usage.open()
        idle()
        val view = find(cards.sheet.window!!.decorView)
        assertNotNull("the sheet holds Compose", view)
        assertTrue("it is laid out", view!!.height > 0)
        assertTrue(words(view).count { it == "正在读取…" } == 2)

        cards.window.value = UsageSheet.Shown.Ready(Usage.window(JSONObject("""{"totalTokens": 50000, "maxTokens": 200000,
            "categories": [{"name": "Messages", "tokens": 40000}, {"name": "Autocompact buffer", "tokens": 33000}]}""")))
        cards.plan.value = UsageSheet.Shown.Ready(Usage.plan(200, JSONObject("""{"limits": [
            {"kind": "session", "group": "session", "percent": 92},
            {"kind": "weekly_scoped", "group": "weekly", "percent": 3, "model": "Fable", "shareOfWeekly": 40}],
            "resets": [{"id": "a", "left": 1, "usableNow": true, "week": "all"}]}""")))
        idle()
        val shown = words(view)
        for (w in listOf("25%", "50k / 200k", "消息", "40k", "自动压缩预留", "空闲", "上下文已用 50k，共 200k",
                "5 小时额度", "快用完了", "92%", "每周额度 · Fable", "最多占每周额度的 40%", "额度重置", "剩 1 次", "免费重置")) {
            assertTrue("shows $w: $shown", w in shown)
        }
        assertFalse(shown.contains("正在读取…"))

        // A note instead of the limits: no resets either.
        cards.plan.value = UsageSheet.Shown.Note("读取失败（network）")
        idle()
        assertTrue(words(view).contains("读取失败（network）"))
        assertFalse(words(view).contains("免费重置"))

        cards.sheet.dismiss()
        idle()
        assertFalse("let go with the sheet", view.isAttachedToWindow)
    }
}
