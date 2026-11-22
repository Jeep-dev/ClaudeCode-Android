package com.jeep.claudecode

import android.app.Application
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * /find counts the places in the texts a reply's views show, not in the Markdown it was written in:
 * Md.findable must give the same texts, in the same order, as the views Finder marks them in.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FindTest {
    private val replies = arrayOf(
        "See [the docs](http://x.com/python) and `python` too.",
        "Run:\n```python\nprint('python')\n```\nDone **python**.",
        "| name | what |\n|---|:--:|\n| python | **bold** |\n| [link](http://p.y/python) |\n\nafter",
        "![alt python](pic.png)\n\nplain python",
        "# Title\n\n- one\n- two python\n\n1. first\n2. second",
    )

    private val app: Application = RuntimeEnvironment.getApplication()
    private val md = Md(app, {}, {}, { null })

    private fun count(texts: List<String>, query: String): Int = texts.sumOf { text ->
        val s = text.lowercase(Locale.ROOT)
        var n = 0
        var i = s.indexOf(query)
        while (i >= 0) { n++; i = s.indexOf(query, i + query.length) }
        n
    }

    @Test fun findableTextsAreTheOnesTheViewsShow() {
        for (reply in replies) {
            val box = LinearLayout(app)
            md.render(box, reply)
            val views = ArrayList<TextView>()
            Finder.findTexts(box, views)
            assertEquals(reply, views.map { it.text.toString() }, md.findable(reply))
        }
    }

    @Test fun whatIsNotOnTheScreenIsNotFound() {
        // The link's address and the language after the fence are in the Markdown only.
        assertEquals(1, count(md.findable(replies[0]), "python")) // `python`, not .../python
        assertEquals(2, count(md.findable(replies[1]), "python")) // inside the code, and the bold one; not ```python
        assertEquals(1, count(md.findable(replies[2]), "python")) // the cell, not the link's address
        assertEquals(1, count(md.findable(replies[3]), "python")) // not the picture's alt text
    }
}
