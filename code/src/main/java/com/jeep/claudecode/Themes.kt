package com.jeep.claudecode

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.drawable.ColorDrawable
import android.util.SparseIntArray
import android.view.View
import android.view.Window

/**
 * The app's colours: a palette (Claude, the app's own in res, or one of RikkaHub's) in its light
 * or dark set, as the system is or one of them always, and on pure black when dark if chosen.
 * Every colour is read through [Ui.color].
 */
object Themes {
    /**
     * A palette: its id, name, its colours over res's light set and over its dark set (pairs:
     * colour, value), and what its circle shows in each (primary, its container, the secondary's
     * and the tertiary's containers, and the colour on primary).
     */
    class Theme(
        @JvmField val id: String,
        @JvmField val name: String,
        @JvmField val light: IntArray,
        @JvmField val dark: IntArray,
        @JvmField val swatchLight: IntArray,
        @JvmField val swatchDark: IntArray,
    ) {
        fun swatch(night: Boolean): IntArray = if (night) swatchDark else swatchLight
    }

    /** Colours written as hex (ARGB over Int's range) and colour ids, as ints. */
    private fun ints(vararg v: Number): IntArray = IntArray(v.size) { v[it].toInt() }

    // RikkaHub's presets (github.com/rikkahub/rikkahub, ui/theme/presets), in its order, their
    // Material 3 roles as this app's colours: bg the background; Settings' page surfaceContainer
    // under tiles of surfaceContainerLowest when light, surfaceContainerLowest under
    // surfaceContainerHigh when dark, surface2 a step between; the user's messages and the
    // accent's pale round primaryContainer; hover the next container up; border outlineVariant,
    // borderStrong halfway to outline; text onSurface, text2 onSurfaceVariant, text3 that 30% of
    // the way to the background; the accent primary (12% of it where pressed), links halfway from
    // it to onPrimaryContainer, onPrimary on it; code in a line primary.
    /** The palettes, the app's own (Claude) last. */
    @JvmField val ALL = arrayOf(
        Theme("sakura", "樱花粉",
                ints(
                        R.color.cream, 0xFFFFF8F7, R.color.bg, 0xFFFFF8F7, R.color.sidebar, 0xFFFBEAEB, R.color.surface, 0xFFFFFFFF,
                        R.color.surface2, 0xFFFFF0F1, R.color.user, 0xFFFFD9DD, R.color.codeBg, 0xFFFFF0F1, R.color.hover, 0xFFF6E4E5,
                        R.color.active, 0x1F8E4955, R.color.border, 0xFFD7C1C3, R.color.borderStrong, 0xFFAE9A9C, R.color.text, 0xFF22191A,
                        R.color.text2, 0xFF524345, R.color.text3, 0xFF86797A, R.color.accent, 0xFF8E4955, R.color.accentPress, 0xFF803E4A,
                        R.color.accentSoft, 0xFFFFD9DD, R.color.onAccent, 0xFFFFFFFF, R.color.inlineCode, 0xFF8E4955),
                ints(
                        R.color.cream, 0xFF1A1112, R.color.bg, 0xFF1A1112, R.color.sidebar, 0xFF140C0D, R.color.surface, 0xFF312828,
                        R.color.surface2, 0xFF261D1E, R.color.user, 0xFF72333E, R.color.codeBg, 0xFF140C0D, R.color.hover, 0xFF3D3233,
                        R.color.active, 0x1FFFB2BC, R.color.border, 0xFF524345, R.color.borderStrong, 0xFF78686A, R.color.text, 0xFFF0DEDF,
                        R.color.text2, 0xFFD7C1C3, R.color.text3, 0xFF9E8C8E, R.color.accent, 0xFFFFB2BC, R.color.accentPress, 0xFFFFC6CC,
                        R.color.accentSoft, 0xFF72333E, R.color.onAccent, 0xFF561D28, R.color.inlineCode, 0xFFFFB2BC),
                ints(0xFF8E4955, 0xFFFFD9DD, 0xFFFFD9DD, 0xFFFFDDB8, 0xFFFFFFFF),
                ints(0xFFFFB2BC, 0xFF72333E, 0xFF5C3F43, 0xFF5E411C, 0xFF561D28)),
        Theme("ocean", "海湾蓝",
                ints(
                        R.color.cream, 0xFFF6FAFD, R.color.bg, 0xFFF6FAFD, R.color.sidebar, 0xFFEAEEF2, R.color.surface, 0xFFFFFFFF,
                        R.color.surface2, 0xFFF0F4F8, R.color.user, 0xFFBDE9FF, R.color.codeBg, 0xFFF0F4F8, R.color.hover, 0xFFE4E9EC,
                        R.color.active, 0x1F116682, R.color.border, 0xFFC0C8CD, R.color.borderStrong, 0xFF98A0A5, R.color.text, 0xFF171C1F,
                        R.color.text2, 0xFF40484C, R.color.text3, 0xFF777D81, R.color.accent, 0xFF116682, R.color.accentPress, 0xFF085A73,
                        R.color.accentSoft, 0xFFBDE9FF, R.color.onAccent, 0xFFFFFFFF, R.color.inlineCode, 0xFF116682),
                ints(
                        R.color.cream, 0xFF0F1417, R.color.bg, 0xFF0F1417, R.color.sidebar, 0xFF0A0F11, R.color.surface, 0xFF262B2D,
                        R.color.surface2, 0xFF1B2023, R.color.user, 0xFF004D64, R.color.codeBg, 0xFF0A0F11, R.color.hover, 0xFF303538,
                        R.color.active, 0x1F8BD0EF, R.color.border, 0xFF40484C, R.color.borderStrong, 0xFF656D72, R.color.text, 0xFFDFE3E7,
                        R.color.text2, 0xFFC0C8CD, R.color.text3, 0xFF8B9296, R.color.accent, 0xFF8BD0EF, R.color.accentPress, 0xFFA4DCF7,
                        R.color.accentSoft, 0xFF004D64, R.color.onAccent, 0xFF003546, R.color.inlineCode, 0xFF8BD0EF),
                ints(0xFF116682, 0xFFBDE9FF, 0xFFD0E6F2, 0xFFE3DFFF, 0xFFFFFFFF),
                ints(0xFF8BD0EF, 0xFF004D64, 0xFF354A53, 0xFF454364, 0xFF003546)),
        Theme("spring", "原野绿",
                ints(
                        R.color.cream, 0xFFF9FAEF, R.color.bg, 0xFFF9FAEF, R.color.sidebar, 0xFFEEEFE3, R.color.surface, 0xFFFFFFFF,
                        R.color.surface2, 0xFFF3F4E9, R.color.user, 0xFFCDEDA3, R.color.codeBg, 0xFFF3F4E9, R.color.hover, 0xFFE8E9DE,
                        R.color.active, 0x1F4C662B, R.color.border, 0xFFC5C8BA, R.color.borderStrong, 0xFF9DA093, R.color.text, 0xFF1A1C16,
                        R.color.text2, 0xFF44483D, R.color.text3, 0xFF7A7D72, R.color.accent, 0xFF4C662B, R.color.accentPress, 0xFF405A20,
                        R.color.accentSoft, 0xFFCDEDA3, R.color.onAccent, 0xFFFFFFFF, R.color.inlineCode, 0xFF4C662B),
                ints(
                        R.color.cream, 0xFF12140E, R.color.bg, 0xFF12140E, R.color.sidebar, 0xFF0C0F09, R.color.surface, 0xFF282B24,
                        R.color.surface2, 0xFF1E201A, R.color.user, 0xFF354E16, R.color.codeBg, 0xFF0C0F09, R.color.hover, 0xFF33362E,
                        R.color.active, 0x1FB1D18A, R.color.border, 0xFF44483D, R.color.borderStrong, 0xFF6A6D61, R.color.text, 0xFFE2E3D8,
                        R.color.text2, 0xFFC5C8BA, R.color.text3, 0xFF8F9286, R.color.accent, 0xFFB1D18A, R.color.accentPress, 0xFFBFDF96,
                        R.color.accentSoft, 0xFF354E16, R.color.onAccent, 0xFF1F3701, R.color.inlineCode, 0xFFB1D18A),
                ints(0xFF4C662B, 0xFFCDEDA3, 0xFFDCE7C8, 0xFFBCECE7, 0xFFFFFFFF),
                ints(0xFFB1D18A, 0xFF354E16, 0xFF404A33, 0xFF1F4E4B, 0xFF1F3701)),
        Theme("autumn", "秋黄",
                ints(
                        R.color.cream, 0xFFFFF8F1, R.color.bg, 0xFFFFF8F1, R.color.sidebar, 0xFFF5EDDF, R.color.surface, 0xFFFFFFFF,
                        R.color.surface2, 0xFFFBF3E5, R.color.user, 0xFFFFE08B, R.color.codeBg, 0xFFFBF3E5, R.color.hover, 0xFFF0E7D9,
                        R.color.active, 0x1F735C0C, R.color.border, 0xFFCFC6B4, R.color.borderStrong, 0xFFA69E8E, R.color.text, 0xFF1F1B13,
                        R.color.text2, 0xFF4C4639, R.color.text3, 0xFF827B70, R.color.accent, 0xFF735C0C, R.color.accentPress, 0xFF665006,
                        R.color.accentSoft, 0xFFFFE08B, R.color.onAccent, 0xFFFFFFFF, R.color.inlineCode, 0xFF735C0C),
                ints(
                        R.color.cream, 0xFF16130B, R.color.bg, 0xFF16130B, R.color.sidebar, 0xFF110E07, R.color.surface, 0xFF2D2A21,
                        R.color.surface2, 0xFF231F17, R.color.user, 0xFF584400, R.color.codeBg, 0xFF110E07, R.color.hover, 0xFF38342B,
                        R.color.active, 0x1FE3C46D, R.color.border, 0xFF4C4639, R.color.borderStrong, 0xFF726B5C, R.color.text, 0xFFEAE1D4,
                        R.color.text2, 0xFFCFC6B4, R.color.text3, 0xFF989081, R.color.accent, 0xFFE3C46D, R.color.accentPress, 0xFFF1D27C,
                        R.color.accentSoft, 0xFF584400, R.color.onAccent, 0xFF3D2F00, R.color.inlineCode, 0xFFE3C46D),
                ints(0xFF735C0C, 0xFFFFE08B, 0xFFF2E1BB, 0xFFC8ECC9, 0xFFFFFFFF),
                ints(0xFFE3C46D, 0xFF584400, 0xFF50462A, 0xFF2F4D34, 0xFF3D2F00)),
        Theme("black", "中性黑",
                ints(
                        R.color.cream, 0xFFFFFFFF, R.color.bg, 0xFFFFFFFF, R.color.sidebar, 0xFFF7F7F7, R.color.surface, 0xFFFFFFFF,
                        R.color.surface2, 0xFFF8F8F8, R.color.user, 0xFFE6E6E6, R.color.codeBg, 0xFFF8F8F8, R.color.hover, 0xFFEBEBEB,
                        R.color.active, 0x1F606060, R.color.border, 0xFFEBEBEB, R.color.borderStrong, 0xFFD0D0D0, R.color.text, 0xFF252525,
                        R.color.text2, 0xFF444444, R.color.text3, 0xFF7C7C7C, R.color.accent, 0xFF606060, R.color.accentPress, 0xFF515151,
                        R.color.accentSoft, 0xFFE6E6E6, R.color.onAccent, 0xFFFFFCFC, R.color.inlineCode, 0xFF606060),
                ints(
                        R.color.cream, 0xFF1C1C1C, R.color.bg, 0xFF1C1C1C, R.color.sidebar, 0xFF1A1A1A, R.color.surface, 0xFF343434,
                        R.color.surface2, 0xFF2A2A2A, R.color.user, 0xFF3B3B3B, R.color.codeBg, 0xFF1A1A1A, R.color.hover, 0xFF3F3F3F,
                        R.color.active, 0x1FEBEBEB, R.color.border, 0xFF444444, R.color.borderStrong, 0xFF696969, R.color.text, 0xFFFCFCFC,
                        R.color.text2, 0xFFB5B5B5, R.color.text3, 0xFF878787, R.color.accent, 0xFFEBEBEB, R.color.accentPress, 0xFFD0D0D0,
                        R.color.accentSoft, 0xFF3B3B3B, R.color.onAccent, 0xFF343434, R.color.inlineCode, 0xFFEBEBEB),
                ints(0xFF606060, 0xFFE6E6E6, 0xFFF3F3F3, 0xFF444444, 0xFFFFFCFC),
                ints(0xFFEBEBEB, 0xFF3B3B3B, 0xFF444444, 0xFF444444, 0xFF343434)),
        Theme("minimal", "极简白",
                ints(
                        R.color.cream, 0xFFFFFFFF, R.color.bg, 0xFFFFFFFF, R.color.sidebar, 0xFFF6F7F8, R.color.surface, 0xFFFFFFFF,
                        R.color.surface2, 0xFFFAFAFB, R.color.user, 0xFFE7F0FE, R.color.codeBg, 0xFFFAFAFB, R.color.hover, 0xFFF1F2F4,
                        R.color.active, 0x1F2563EB, R.color.border, 0xFFE8E9EC, R.color.borderStrong, 0xFFD8DADE, R.color.text, 0xFF16181D,
                        R.color.text2, 0xFF6A6E75, R.color.text3, 0xFF979A9E, R.color.accent, 0xFF2563EB, R.color.accentPress, 0xFF2056DB,
                        R.color.accentSoft, 0xFFE7F0FE, R.color.onAccent, 0xFFFFFFFF, R.color.inlineCode, 0xFF2563EB),
                ints(
                        R.color.cream, 0xFF0F1012, R.color.bg, 0xFF0F1012, R.color.sidebar, 0xFF0A0B0C, R.color.surface, 0xFF222327,
                        R.color.surface2, 0xFF1A1B1E, R.color.user, 0xFF1D3E7F, R.color.codeBg, 0xFF0A0B0C, R.color.hover, 0xFF2A2C30,
                        R.color.active, 0x1F8CB0FF, R.color.border, 0xFF2E3035, R.color.borderStrong, 0xFF3D4045, R.color.text, 0xFFE6E7EA,
                        R.color.text2, 0xFFA0A4AB, R.color.text3, 0xFF74787D, R.color.accent, 0xFF8CB0FF, R.color.accentPress, 0xFFB2CAFF,
                        R.color.accentSoft, 0xFF1D3E7F, R.color.onAccent, 0xFF082C6E, R.color.inlineCode, 0xFF8CB0FF),
                ints(0xFF2563EB, 0xFFE7F0FE, 0xFFEFF0F2, 0xFFFCEFCE, 0xFFFFFFFF),
                ints(0xFF8CB0FF, 0xFF1D3E7F, 0xFF3A3D43, 0xFF5B4400, 0xFF082C6E)),
        // The app's own (res); its circle has Claude's containers as RikkaHub has them.
        Theme("claude", "Claude", IntArray(0), IntArray(0),
                ints(0xFFD97757, 0xFFF7E3D9, 0xFFE9E6DC, 0xFFEBDBBC, 0xFFFFFFFF),
                ints(0xFFD97757, 0xFF7A3620, 0xFF4A453A, 0xFF5C4525, 0xFFFFFFFF)),
    )

    /** Light or dark (Settings → 外观): as the system, light, dark. */
    @JvmField val MODES = arrayOf("跟随系统", "浅色", "深色")

    private var theme: Theme? = null
    private var mode = 0
    private var black = false
    private val overLight = SparseIntArray()
    private val overDark = SparseIntArray()
    /** res's light and dark sets, for a mode that is not the system's. */
    private var lightRes: Context? = null
    private var darkRes: Context? = null

    private fun prefs(c: Context): SharedPreferences = c.getSharedPreferences("app", Context.MODE_PRIVATE)

    @JvmStatic
    fun current(c: Context): Theme = theme ?: run {
        load(c)
        theme!!
    }

    /** 0 as the system, 1 light, 2 dark. */
    @JvmStatic
    fun mode(c: Context): Int {
        current(c)
        return mode
    }

    /** Dark on pure black (RikkaHub's AMOLED): the page and Settings' under its tiles. */
    @JvmStatic
    fun pureBlack(c: Context): Boolean {
        current(c)
        return black
    }

    /** The immersive layout: the conversation under the status bar, the buttons and box as glass. */
    @JvmStatic
    fun immersive(c: Context): Boolean = prefs(c).getBoolean("immersive", false)

    /** The conversation's title over it (off by default). */
    @JvmStatic
    fun showTitle(c: Context): Boolean = prefs(c).getBoolean("title", false)

    /** Which of the greeting crab's styles it wears (Crab.NAMES). */
    @JvmStatic
    fun crabStyle(c: Context): Int = prefs(c).getInt("crabStyle", 0)

    @JvmStatic
    fun setCrabStyle(c: Context, style: Int) = prefs(c).edit().putInt("crabStyle", style).apply()

    /** The model's thinking as rows in the conversation (on by default). */
    @JvmStatic
    fun showThinking(c: Context): Boolean = prefs(c).getBoolean("thinking", true)

    @JvmStatic
    fun setShowThinking(c: Context, on: Boolean) = prefs(c).edit().putBoolean("thinking", on).apply()

    @JvmStatic
    fun setShowTitle(c: Context, on: Boolean) = prefs(c).edit().putBoolean("title", on).apply()

    @JvmStatic
    fun setImmersive(c: Context, on: Boolean) = prefs(c).edit().putBoolean("immersive", on).apply()

    @JvmStatic
    fun set(c: Context, id: String) {
        prefs(c).edit().putString("palette", id).apply()
        load(c)
    }

    @JvmStatic
    fun setMode(c: Context, m: Int) {
        prefs(c).edit().putInt("mode", m).apply()
        load(c)
    }

    @JvmStatic
    fun setPureBlack(c: Context, on: Boolean) {
        prefs(c).edit().putBoolean("pureBlack", on).apply()
        load(c)
    }

    /** A theme of before (one list of the light, the dark and those following the system) as the
     *  palette and mode it became, the like ones as the one kept. */
    private fun migrate(p: SharedPreferences) {
        if (p.contains("palette") || !p.contains("theme")) return
        var palette = "claude"
        var m = 0
        var pure = false
        when (p.getString("theme", "system")) {
            "light" -> m = 1
            "dark" -> m = 2
            "black" -> { m = 2; pure = true }
            "white", "mist" -> { palette = "minimal"; m = 1 }
            "paper" -> { palette = "autumn"; m = 1 }
            "blue" -> { palette = "ocean"; m = 1 }
            "slate" -> { palette = "black"; m = 2 }
        }
        p.edit().putString("palette", palette).putInt("mode", m).putBoolean("pureBlack", pure).remove("theme").apply()
    }

    @Synchronized
    private fun load(c: Context) {
        val p = prefs(c)
        migrate(p)
        val id = p.getString("palette", "claude")
        val t = ALL.lastOrNull { it.id == id } ?: ALL[ALL.size - 1]
        mode = p.getInt("mode", 0)
        black = p.getBoolean("pureBlack", false)
        fill(overLight, t.light)
        fill(overDark, t.dark)
        if (lightRes == null) {
            lightRes = night(c, false)
            darkRes = night(c, true)
        }
        theme = t
    }

    private fun fill(over: SparseIntArray, pairs: IntArray) {
        over.clear()
        var i = 0
        while (i + 1 < pairs.size) {
            over.put(pairs[i], pairs[i + 1])
            i += 2
        }
    }

    @JvmStatic
    fun color(c: Context, id: Int): Int {
        current(c)
        val d = dark(c)
        if (d && black && (id == R.color.bg || id == R.color.cream || id == R.color.sidebar)) return 0xFF000000.toInt()
        val over = if (d) overDark else overLight
        val i = over.indexOfKey(id)
        if (i >= 0) return over.valueAt(i)
        return (if (mode == 0) c else if (d) darkRes!! else lightRes!!).getColor(id)
    }

    @JvmStatic
    fun dark(c: Context): Boolean {
        current(c)
        if (mode != 0) return mode == 2
        return (c.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    }

    private fun night(c: Context, dark: Boolean): Context {
        val app = c.applicationContext
        val cfg = Configuration(app.resources.configuration)
        cfg.uiMode = (cfg.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
            if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        return app.createConfigurationContext(cfg)
    }

    /** A window in the theme's background, its bar icons dark on a light theme. */
    @JvmStatic
    fun window(w: Window?, c: Context) {
        if (w == null) return
        val bg = color(c, R.color.bg)
        w.setBackgroundDrawable(ColorDrawable(bg))
        w.statusBarColor = bg
        w.navigationBarColor = bg
        val d = w.decorView
        val light = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        val flags = d.systemUiVisibility
        d.systemUiVisibility = if (dark(c)) flags and light.inv() else flags or light
    }
}
