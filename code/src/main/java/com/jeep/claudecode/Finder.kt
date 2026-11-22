package com.jeep.claudecode

import android.text.Spannable
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/** /find: the places in a conversation where some words are, marked, and the way between them. */
class Finder(app: MainActivity) : Part(app) {
    @JvmField var findQuery = "" // lower case; empty when not looking
    @JvmField var findLine = "" // the /find line as sent, kept in the box while it looks
    private val findHits = ArrayList<IntArray>() // {row, which place in the row}
    @JvmField var findAt = 0
    private var findToast: Toast? = null

    private class Found(color: Int) : BackgroundColorSpan(color)

    fun finding(): Boolean = findHits.isNotEmpty()

    /** Each place the query is in s, from the start. */
    private fun places(s: String): Sequence<Int> =
        generateSequence(s.indexOf(findQuery).takeIf { it >= 0 }) { i ->
            s.indexOf(findQuery, i + findQuery.length).takeIf { it >= 0 }
        }

    /**
     * /find: the places in this conversation's messages and replies where the words are, marked,
     * from the last; ↑↓ go between them. With no words it stops looking.
     */
    fun find(words: String) {
        findQuery = words.lowercase(Locale.ROOT)
        findHits.clear()
        unCache()
        var p = 0
        while (findQuery.isNotEmpty() && p < app.screen.adapter.itemCount) {
            val texts = app.screen.adapter.searchText(p)
            if (texts != null) {
                // Counted in the texts the row's views show, in their order, as mark and findSpot count.
                var k = 0
                for (text in texts) for (i in places(text.lowercase(Locale.ROOT))) findHits.add(intArrayOf(p, k++))
            }
            p++
        }
        if (findHits.isEmpty()) {
            findEnd()
            return
        }
        findLine = app.box.input.text.toString().trim()
        app.box.hideKeyboard()
        findGo(findHits.size - 1)
    }

    fun findEnd() {
        findQuery = ""
        findHits.clear()
        unCache()
        markShown()
        app.screen.showJump()
    }

    private fun markShown() {
        val list = app.screen.list
        for (i in 0 until list.childCount) mark(list.getChildAt(i), list.getChildAdapterPosition(list.getChildAt(i)))
    }

    /** Rows kept aside off screen come back without being bound again: they must not keep old marks. */
    private fun unCache() {
        app.screen.list.setItemViewCacheSize(0)
        app.screen.list.setItemViewCacheSize(2) // RecyclerView's default
    }

    /** Goes to a place found: its line a third of the way down the screen. */
    fun findGo(i: Int) {
        findAt = max(0, min(findHits.size - 1, i))
        val at = findHits[findAt]
        val list = app.screen.list
        app.screen.stick = false
        app.screen.adapter.opened = null
        list.stopScroll()
        val lm = list.layoutManager as LinearLayoutManager
        lm.scrollToPositionWithOffset(at[0], list.height / 4)
        list.post {
            if (!finding()) return@post
            markShown()
            val row = lm.findViewByPosition(at[0])
            val y = if (row == null) -1 else findSpot(row, at[1])
            if (y >= 0) list.scrollBy(0, y - list.height / 3)
            app.screen.showJump()
        }
        findToast?.cancel()
        findToast = Toast.makeText(app, "${findAt + 1} / ${findHits.size}", Toast.LENGTH_SHORT).also { it.show() }
    }

    /** How far down the list the k-th place found in a row is; -1 when it is not shown. */
    private fun findSpot(row: View, k: Int): Int {
        val texts = ArrayList<TextView>()
        findTexts(row, texts)
        var n = 0
        for (t in texts) {
            for (i in places(t.text.toString().lowercase(Locale.ROOT))) {
                if (n++ < k) continue
                val layout = t.layout ?: return -1
                val a = IntArray(2)
                val b = IntArray(2)
                t.getLocationInWindow(a)
                app.screen.list.getLocationInWindow(b)
                return a[1] - b[1] + t.totalPaddingTop + layout.getLineTop(layout.getLineForOffset(i))
            }
        }
        return -1
    }

    /** Marks the places found in a row, the one gone to darker; or takes the marks away. */
    fun mark(row: View, position: Int) {
        val marked = findQuery.isNotEmpty() && app.screen.adapter.searchable(position)
        val at = if (finding()) findHits[findAt] else null
        val texts = ArrayList<TextView>()
        findTexts(row, texts)
        var n = 0
        for (t in texts) {
            // A message's own text is plain (a user's, code): made one that takes marks.
            if (t.text !is Spannable) t.setText(t.text, TextView.BufferType.SPANNABLE)
            val s = t.text as Spannable
            for (f in s.getSpans(0, s.length, Found::class.java)) s.removeSpan(f)
            if (!marked) continue
            for (i in places(s.toString().lowercase(Locale.ROOT))) {
                val here = at != null && at[0] == position && at[1] == n
                s.setSpan(Found(Glass.alpha(col(R.color.accent), if (here) 0.55f else 0.2f)),
                    i, min(s.length, i + findQuery.length), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                n++
            }
        }
    }

    companion object {
        /** The text a row shows that /find looks in: a message's own (tagged findable), not labels such as 复制. */
        @JvmStatic
        fun findTexts(v: View, out: MutableList<TextView>) {
            if (v is TextView) {
                if (v.getTag(R.id.findable) != null) out.add(v)
                return
            }
            if (v !is ViewGroup) return
            for (i in 0 until v.childCount) findTexts(v.getChildAt(i), out)
        }
    }
}
