package com.jeep.claudecode

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.fonts.Font
import android.graphics.fonts.FontFamily
import android.graphics.fonts.SystemFonts
import android.graphics.text.TextRunShaper
import android.os.Build
import java.io.File

/**
 * The conversation's font: one for Latin text and one for Chinese, each the system's serif or
 * sans-serif, a font the phone has, or a font file added here (files/fonts). Nothing is bundled.
 */
object Fonts {
    const val SERIF = "serif"
    const val SANS = "sans-serif"
    private const val PREFS = "fonts"
    private const val LATIN = "latin"
    private const val CJK = "cjk"
    private const val SIZE = "size"
    /** Conversation text sizes, and each one's scale over the system's text size. */
    @JvmField val SIZES = arrayOf("小", "中", "大")
    private val SCALES = floatArrayOf(0.9f, 1f, 1.12f)
    private val FONT_FILE = Regex("\\.(ttf|otf|ttc)$")

    /** A font to choose: its id (SERIF, SANS, "sys:" + group, "file:" + name) and name. */
    class Choice(@JvmField val id: String, @JvmField val name: String, @JvmField val preview: Typeface)

    private var body: Typeface? = null
    /** The phone's fonts, by group (file name without its weight): read once. */
    private var system: Map<String, MutableList<Font>>? = null

    @JvmStatic
    fun dir(c: Context): File = File(c.filesDir, "fonts")

    private fun prefs(c: Context): SharedPreferences = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @JvmStatic
    fun latin(c: Context): String = prefs(c).getString(LATIN, SERIF) ?: SERIF

    @JvmStatic
    fun cjk(c: Context): String = prefs(c).getString(CJK, SERIF) ?: SERIF

    @JvmStatic
    fun size(c: Context): Int = prefs(c).getInt(SIZE, 1).coerceIn(0, SIZES.size - 1)

    @JvmStatic
    fun setSize(c: Context, size: Int) = prefs(c).edit().putInt(SIZE, size).apply()

    @JvmStatic
    fun scale(c: Context): Float = SCALES[size(c)]

    @JvmStatic
    fun set(c: Context, cjk: Boolean, id: String) {
        prefs(c).edit().putString(if (cjk) CJK else LATIN, id).apply()
        body = null
    }

    /** The typeface for conversation text: Latin from one font, Chinese from the other. */
    @JvmStatic
    @Synchronized
    fun body(c: Context): Typeface = body ?: build(c, latin(c), cjk(c)).also { body = it }

    private fun build(c: Context, latin: String, cjk: String): Typeface {
        if (Build.VERSION.SDK_INT < 29) {
            // No mixing before Android 10: the Latin choice for everything.
            val t = if (latin.startsWith("file:")) fileTypeface(c, latin.substring(5)) else null
            return t ?: Typeface.create(if (latin == SANS) SANS else SERIF, Typeface.NORMAL)
        }
        if (latin == cjk && (latin == SERIF || latin == SANS)) return Typeface.create(latin, Typeface.NORMAL)
        return try {
            // Latin first, so the Chinese font's own Latin letters are not used.
            val first = family(c, latin)
            val second = if (cjk == SERIF || cjk == SANS) null else family(c, cjk)
            val b = when {
                first != null -> Typeface.CustomFallbackBuilder(first).also { if (second != null) it.addCustomFallback(second) }
                second != null -> Typeface.CustomFallbackBuilder(second)
                else -> return Typeface.create(latin, Typeface.NORMAL)
            }
            b.setSystemFallback(if (second == null) cjk else SANS)
            b.build()
        } catch (e: RuntimeException) {
            Typeface.create(SERIF, Typeface.NORMAL)
        }
    }

    /** A choice as a font family; the system's serif and sans-serif as the phone's own files. */
    private fun family(c: Context, id: String): FontFamily? {
        if (Build.VERSION.SDK_INT < 29) return null
        return try {
            if (id.startsWith("file:")) {
                return FontFamily.Builder(Font.Builder(File(dir(c), id.substring(5))).build()).build()
            }
            val group = if (id.startsWith("sys:")) id.substring(4) else generic(id)
            val fonts = group?.let { systemFonts()[it] }
            if (fonts.isNullOrEmpty()) return null
            var b: FontFamily.Builder? = null
            val styles = HashSet<Int>()
            for (f in fonts) {
                val key = f.style.weight * 2 + f.style.slant
                if (!styles.add(key)) continue
                if (b == null) b = FontFamily.Builder(f) else b.addFont(f)
            }
            b?.build()
        } catch (e: Exception) {
            null
        }
    }

    /** The group of the phone's font that draws Latin letters for serif or sans-serif. */
    private fun generic(id: String): String? {
        val file = drawnBy(Typeface.create(id, Typeface.NORMAL), "a")?.file
        if (file != null) {
            val g = group(file.name)
            if (systemFonts().containsKey(g)) return g
        }
        val guess = if (id == SERIF) "NotoSerif" else "Roboto"
        return if (systemFonts().containsKey(guess)) guess else null
    }

    /** The font that actually draws the first character of the text (Android 12+), else null. */
    private fun drawnBy(t: Typeface, text: String): Font? {
        if (Build.VERSION.SDK_INT < 31) return null
        val p = Paint()
        p.typeface = t
        val g = TextRunShaper.shapeTextRun(text, 0, text.length, 0, text.length, 0f, 0f, false, p)
        return if (g.glyphCount() > 0) g.getFont(0) else null
    }

    private fun group(fileName: String): String = fileName.replace(FONT_FILE, "").replace(Regex("-[^-]*$"), "")

    private fun fileTypeface(c: Context, name: String): Typeface? = try {
        Typeface.createFromFile(File(dir(c), name))
    } catch (e: RuntimeException) {
        null
    }

    /** The phone's fonts grouped by file name without weight or style ("NotoSerif-Bold.ttf" → NotoSerif). */
    @Synchronized
    private fun systemFonts(): Map<String, MutableList<Font>> {
        system?.let { return it }
        val out = LinkedHashMap<String, MutableList<Font>>()
        if (Build.VERSION.SDK_INT >= 29) {
            for (f in SystemFonts.getAvailableFonts()) {
                val file = f.file ?: continue
                // A collection holds several languages' fonts: keep the Simplified Chinese one, else the first.
                val locales = f.localeList
                val list = out.getOrPut(group(file.name)) { ArrayList() }
                val hans = locales.toLanguageTags().contains("Hans")
                if (file.name.endsWith(".ttc") && f.ttcIndex > 0 && !hans) continue
                if (hans) list.add(0, f) else list.add(f)
            }
        }
        system = out
        return out
    }

    /** What can be chosen for Latin (cjk false) or Chinese text, each with a preview of itself. */
    @JvmStatic
    fun choices(c: Context, cjk: Boolean): List<Choice> {
        val out = ArrayList<Choice>()
        out.add(Choice(SERIF, "衬线（系统）", Typeface.create(SERIF, Typeface.NORMAL)))
        out.add(Choice(SANS, "无衬线（系统）", Typeface.create(SANS, Typeface.NORMAL)))
        val sample = if (cjk) "中" else "a"
        if (Build.VERSION.SDK_INT >= 29) {
            val otherScript = Regex("Noto(Sans|Serif)(?!CJK|Mono)[A-Z]\\w+.*")
            for (group in systemFonts().keys) {
                // Fonts for one other script (Noto Sans Arabic …) have no place here.
                if (!cjk && group.matches(otherScript)) continue
                val fam = family(c, "sys:$group") ?: continue
                val t = typeface(fam)
                if (t != null && covers(t, sample, fam.getFont(0))) out.add(Choice("sys:$group", group, t))
            }
        }
        dir(c).listFiles()?.forEach { f ->
            val t = fileTypeface(c, f.name)
            val fam = family(c, "file:" + f.name)
            if (t != null && (fam == null || covers(typeface(fam), sample, fam.getFont(0)))) {
                out.add(Choice("file:" + f.name, f.name.replace(FONT_FILE, ""), t))
            }
        }
        return out
    }

    private fun typeface(f: FontFamily?): Typeface? {
        if (f == null || Build.VERSION.SDK_INT < 29) return null
        return try {
            Typeface.CustomFallbackBuilder(f).build()
        } catch (e: RuntimeException) {
            null
        }
    }

    /** Whether the font itself (not a fallback) draws the character; unknown before Android 12: yes. */
    private fun covers(t: Typeface?, ch: String, own: Font): Boolean {
        if (Build.VERSION.SDK_INT < 31) return true
        if (t == null) return false
        val used = drawnBy(t, ch)
        return used?.file != null && own.file != null && used.file == own.file && used.ttcIndex == own.ttcIndex
    }

    /** The name shown for a choice. */
    @JvmStatic
    fun name(id: String): String {
        if (id == SERIF) return "衬线"
        if (id == SANS) return "无衬线"
        return id.substring(id.indexOf(':') + 1).replace(FONT_FILE, "")
    }
}
