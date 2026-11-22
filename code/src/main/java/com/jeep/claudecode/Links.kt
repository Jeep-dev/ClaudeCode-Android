package com.jeep.claudecode

import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.MarkwonPlugin
import io.noties.markwon.MarkwonVisitor
import io.noties.markwon.SpannableBuilder
import io.noties.markwon.core.CorePlugin
import io.noties.markwon.core.CoreProps
import java.util.regex.Pattern
import org.commonmark.node.Link

/**
 * Web addresses and e-mails in prose become links, found here and not by Android's Linkify: it took
 * Chinese as part of an address (「访问不了。gol.gg」 linked from 访) and any digits for a phone number
 * (0.4.125, 776564b). Here a host is ASCII only, an address stops at 。，（）, and nothing is a phone.
 */
class Links : AbstractMarkwonPlugin() {
    override fun configure(registry: MarkwonPlugin.Registry) {
        registry.require(CorePlugin::class.java) { core -> core.addOnTextAddedListener(::added) }
    }

    private companion object {
        /** What ends an address: spaces, quotes, and CJK punctuation and full-width forms. */
        const val REST = "[^\\s<>\"'`\\u3000-\\u303F\\uFF00-\\uFFEF]*"
        val URL: Pattern = Pattern.compile(
            "(?:https?://[A-Za-z0-9.-]+(?::\\d+)?" +
                // without http:// only a known ending: Md.java, build.sh are files
                "|(?<![A-Za-z0-9.@/_-])(?:[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?\\.)+" +
                "(?:com|net|org|io|gg|cn|dev|app|ai|co|me|tv|info|xyz|edu|gov|jp|kr|uk|de|fr|ru|us|top|site|link|cc|hk|tw)" +
                "(?![A-Za-z0-9-]))" +
                "(?:[/?#]" + REST + ")?")

        val EMAIL: Pattern = Pattern.compile(
            "(?<![A-Za-z0-9._%+-])[A-Za-z0-9._%+-]+@(?:[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?\\.)+[A-Za-z]{2,}(?![A-Za-z0-9-])")

        fun added(visitor: MarkwonVisitor, text: String, start: Int) {
            val configuration = visitor.configuration()
            val factory = configuration.spansFactory().get(Link::class.java) ?: return
            val found = ArrayList<IntArray>()
            val urls = ArrayList<String>()
            val e = EMAIL.matcher(text)
            while (e.find()) {
                found.add(intArrayOf(e.start(), e.end()))
                urls.add("mailto:" + e.group())
            }
            val m = URL.matcher(text)
            while (m.find()) {
                val a = m.start()
                val b = trim(text, a, m.end())
                if (b <= a || overlaps(found, a, b)) continue
                val url = text.substring(a, b)
                found.add(intArrayOf(a, b))
                urls.add(if (url.contains("://")) url else "http://$url")
            }
            for (i in found.indices) {
                CoreProps.LINK_DESTINATION.set(visitor.renderProps(), urls[i])
                SpannableBuilder.setSpans(visitor.builder(), factory.getSpans(configuration, visitor.renderProps()),
                    start + found[i][0], start + found[i][1])
            }
        }

        /** An address does not end with the sentence's . , ! ? or with a ) it did not open. */
        fun trim(text: String, a: Int, end: Int): Int {
            var b = end
            while (b > a) {
                val last = text[b - 1]
                if (".,;:!?'\"*_~".indexOf(last) >= 0) b--
                else if (last == ')' && count(text, a, b, '(') < count(text, a, b, ')')) b--
                else break
            }
            return b
        }

        fun count(text: String, a: Int, b: Int, ch: Char): Int = (a until b).count { text[it] == ch }

        fun overlaps(found: List<IntArray>, a: Int, b: Int): Boolean = found.any { a < it[1] && it[0] < b }
    }
}
