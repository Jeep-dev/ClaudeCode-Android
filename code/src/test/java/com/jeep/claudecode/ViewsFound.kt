package com.jeep.claudecode

import android.view.View
import android.view.ViewGroup
import android.widget.TextView

/** What a window's views show, for the tests: every text and description, in order. */
internal fun wordsIn(v: View, out: MutableList<String> = ArrayList()): List<String> {
    if (v.visibility != View.VISIBLE) return out
    if (v is TextView) out.add(v.text.toString())
    v.contentDescription?.let { out.add(it.toString()) }
    if (v is ViewGroup) for (i in 0 until v.childCount) wordsIn(v.getChildAt(i), out)
    return out
}

internal fun textIn(v: View, text: String): TextView? {
    if (v.visibility != View.VISIBLE) return null
    if (v is TextView && v.text.toString() == text) return v
    if (v is ViewGroup) for (i in 0 until v.childCount) textIn(v.getChildAt(i), text)?.let { return it }
    return null
}

/** Taps what a text is on: the text itself, or the nearest clickable view holding it. */
internal fun tapText(root: View, text: String) {
    val t = textIn(root, text) ?: throw AssertionError("no \"$text\" shown")
    var v: View? = t
    while (v != null && !v.isClickable) v = v.parent as? View
    (v ?: throw AssertionError("\"$text\" cannot be tapped")).performClick()
}
