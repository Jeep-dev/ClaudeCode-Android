package com.jeep.claudecode

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.TimeUnit

/** The drawer's list is built again only when what it shows changed; its times are read again in place. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DrawerListTest {
    private fun texts(v: View, out: MutableList<TextView> = ArrayList()): List<TextView> {
        if (v is TextView) out.add(v)
        if (v is ViewGroup) for (i in 0 until v.childCount) texts(v.getChildAt(i), out)
        return out
    }

    private fun shown(a: MainActivity, text: String) = texts(a.drawer.drawerPanel!!).single { it.text.toString() == text }

    private fun sessions(now: Long, firstAgo: Long, firstTitle: String = "第一个") = JSONArray(
        "[{\"id\":\"a\",\"title\":\"$firstTitle\",\"cwd\":\"/root/w\",\"mtime\":${now - firstAgo}}," +
            "{\"id\":\"b\",\"title\":\"第二个\",\"cwd\":\"/root/w\",\"mtime\":${now - 7200}}]")

    @Test fun theSameListKeepsItsRowsAndReadsItsTimesAgain() {
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val now = System.currentTimeMillis() / 1000
        a.drawer.sessions = sessions(now, 10)
        a.drawer.renderSessions()
        val title = shown(a, "第一个")
        shown(a, "now")

        a.drawer.sessions = sessions(now, 300) // it changed five minutes ago, still the first
        a.drawer.renderSessions()
        assertSame("the row stays", title, shown(a, "第一个"))
        assertEquals("5m", texts(a.drawer.drawerPanel!!).first { it.text.toString().endsWith("m") }.text.toString())

        a.drawer.sessions = sessions(now, 300, "改了名")
        a.drawer.renderSessions()
        assertNotSame("a new name: built again", title, shown(a, "改了名"))
    }

    @Test fun whatWaitsForTheDrawerRunsOnlyOnceItIsStill() {
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        var ran = 0
        a.frame.open()
        a.frame.whenStill { ran++ }
        assertEquals("not while it opens", 0, ran)
        a.frame.close() // the opening cut short by the closing: not at its start either
        assertEquals("not as the next move begins", 0, ran)
        ShadowLooper.idleMainLooper(2, TimeUnit.SECONDS)
        assertEquals("once it is still", 1, ran)
    }
}
