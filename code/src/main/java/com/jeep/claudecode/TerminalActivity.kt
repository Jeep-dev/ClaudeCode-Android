package com.jeep.claudecode

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.termux.terminal.TerminalColors
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import java.io.IOException
import java.util.Properties

/**
 * A terminal in the userland (bash as root in /root), for commands and interactive programs
 * such as vim or Claude Code's own /login. The shell lives on while the app does: leaving the
 * screen keeps it, `exit` ends it.
 */
class TerminalActivity : Activity(), TerminalSessionClient, TerminalViewClient {
    private var view: TerminalView? = null
    private var ctrl = false
    private var alt = false
    private lateinit var ctrlKey: TextView
    private lateinit var altKey: TextView

    /** Back as Settings' pages go back (Motion.pop): away to the right, the conversation coming up from under. */
    override fun finish() {
        super.finish()
        overridePendingTransition(R.anim.page_under_in, R.anim.page_out)
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val userland = Userland(this)
        if (!userland.ready()) {
            Toast.makeText(this, "运行环境还没准备好", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Ui.color(this, R.color.bg))
        Themes.window(window, this)
        applyColors()
        val v = TerminalView(this, null)
        view = v
        v.setTerminalViewClient(this)
        v.setTextSize(Ui.dp(this, textSizeDp)) // makes the renderer (monospace): before anything else on the view
        v.isFocusable = true
        v.isFocusableInTouchMode = true
        root.addView(v, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(keys(), LinearLayout.LayoutParams(-1, Ui.dp(this, 40)))
        setContentView(root)

        val running = session
        if (running != null && running.isRunning) {
            running.updateTerminalSessionClient(this)
            attach()
            return
        }
        // Getting the userland ready writes files (and may copy Shizuku's dex): not on the main thread.
        Thread({
            val cmd = try {
                userland.command("/bin/bash", "-l")
            } catch (e: IOException) {
                runOnUiThread {
                    Toast.makeText(this, "启动终端失败：" + e.message, Toast.LENGTH_LONG).show()
                    finish()
                }
                return@Thread
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                session = TerminalSession(cmd[0], userland.base.path, cmd.toTypedArray(), userland.env(), 3000, this)
                attach()
            }
        }, "terminal-start").start()
    }

    private fun attach() {
        shown = this
        val s = session ?: return
        view?.attachSession(s)
        s.emulator?.mColors?.reset() // a shell from before: new colours
        view?.requestFocus()
    }

    /** The shell lives on after the screen; it must not keep the screen (and its views) with it. */
    override fun onDestroy() {
        if (shown === this) {
            shown = null
            session?.updateTerminalSessionClient(AWAY)
        }
        super.onDestroy()
    }

    /**
     * The app's own colours: its background and text, and in the light theme ANSI colours dark
     * enough to read on it (the standard ones are made for a black screen).
     */
    private fun applyColors() {
        val p = Properties()
        p["background"] = hex(Ui.color(this, R.color.bg))
        p["foreground"] = hex(Ui.color(this, R.color.text))
        p["cursor"] = hex(Ui.color(this, R.color.accent))
        val ansi = if (Themes.dark(this)) arrayOf("#000000", "#cd3131", "#0dbc79", "#e5e510", "#2472c8", "#bc3fbc", "#11a8cd", "#e5e5e5",
            "#666666", "#f14c4c", "#23d18b", "#f5f543", "#3b8eea", "#d670d6", "#29b8db", "#ffffff")
        else arrayOf("#1f1e1d", "#c4261d", "#1e7a34", "#946200", "#1f56c7", "#a2349f", "#0e7584", "#6b6a66",
            "#5c5b57", "#d93a2f", "#2a9145", "#a87400", "#3567d9", "#b842b4", "#148a9b", "#3d3c39")
        for (i in ansi.indices) p["color$i"] = ansi[i]
        TerminalColors.COLOR_SCHEME.updateWith(p)
    }

    /**
     * Back (the button or the system's swipe) leaves the screen, the shell staying; the terminal
     * view would otherwise take it as a key. Ending a text selection comes first.
     */
    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (e.keyCode != KeyEvent.KEYCODE_BACK) return super.dispatchKeyEvent(e)
        if (e.action == KeyEvent.ACTION_UP && !e.isCanceled) {
            val v = view
            if (v != null && v.isSelectingText) v.stopTextSelectionMode() else finish()
        }
        return true
    }

    /** ESC, TAB, CTRL, ALT, arrows and the symbols phone keyboards hide. */
    private fun keys(): View {
        val scroll = HorizontalScrollView(this)
        scroll.isHorizontalScrollBarEnabled = false
        scroll.setBackgroundColor(Ui.color(this, R.color.surface2))
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        key(row, "ESC") { view?.handleKeyCode(KeyEvent.KEYCODE_ESCAPE, 0) }
        key(row, "TAB") { view?.handleKeyCode(KeyEvent.KEYCODE_TAB, 0) }
        ctrlKey = key(row, "CTRL") {
            ctrl = !ctrl
            paint()
        }
        altKey = key(row, "ALT") {
            alt = !alt
            paint()
        }
        key(row, "←") { view?.handleKeyCode(KeyEvent.KEYCODE_DPAD_LEFT, 0) }
        key(row, "↓") { view?.handleKeyCode(KeyEvent.KEYCODE_DPAD_DOWN, 0) }
        key(row, "↑") { view?.handleKeyCode(KeyEvent.KEYCODE_DPAD_UP, 0) }
        key(row, "→") { view?.handleKeyCode(KeyEvent.KEYCODE_DPAD_RIGHT, 0) }
        for (c in arrayOf("/", "-", "|", "~", "*", "&", "$", ">")) key(row, c) { type(c.codePointAt(0)) }
        key(row, "粘贴", ::paste)
        scroll.addView(row)
        return scroll
    }

    private fun key(row: LinearLayout, label: String, action: () -> Unit): TextView {
        val k = TextView(this)
        k.text = label
        k.setTextColor(Ui.color(this, R.color.text))
        k.textSize = 14f
        k.typeface = Typeface.MONOSPACE
        k.gravity = Gravity.CENTER
        k.setPadding(Ui.dp(this, 14), 0, Ui.dp(this, 14), 0)
        k.minWidth = Ui.dp(this, 44)
        k.setOnClickListener { if (shown === this) action() } // once the shell is up
        row.addView(k, LinearLayout.LayoutParams(-2, -1))
        return k
    }

    private fun paint() {
        val on = Ui.color(this, R.color.accent)
        val off = Ui.color(this, R.color.text)
        ctrlKey.setTextColor(if (ctrl) on else off)
        altKey.setTextColor(if (alt) on else off)
    }

    private fun type(codePoint: Int) {
        view?.inputCodePoint(codePoint, readControlKey(), readAltKey())
    }

    private fun paste() {
        val cm = getSystemService(ClipboardManager::class.java)
        val clip = cm.primaryClip
        if (clip == null || clip.itemCount == 0) return
        val text = clip.getItemAt(0).coerceToText(this)
        if (text != null) session?.emulator?.paste(text.toString())
    }

    private fun showKeyboard() {
        val v = view ?: return
        v.requestFocus()
        getSystemService(InputMethodManager::class.java).showSoftInput(v, 0)
    }

    // ---- TerminalSessionClient ----

    override fun onTextChanged(s: TerminalSession) {
        view?.onScreenUpdated()
    }

    override fun onTitleChanged(s: TerminalSession) {}

    override fun onSessionFinished(s: TerminalSession) {
        if (session === s) session = null
        finish()
    }

    override fun onCopyTextToClipboard(s: TerminalSession, text: String?) {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("终端", text))
        Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show()
    }

    override fun onPasteTextFromClipboard(s: TerminalSession?) = paste()

    override fun onBell(s: TerminalSession) {}

    override fun onColorsChanged(s: TerminalSession) {}

    override fun onTerminalCursorStateChange(state: Boolean) {}

    override fun getTerminalCursorStyle(): Int? = null

    // ---- TerminalViewClient ----

    override fun onScale(scale: Float): Float {
        if (scale < 0.9f || scale > 1.1f) {
            textSizeDp = (textSizeDp + if (scale > 1) 1 else -1).coerceIn(7, 28)
            view?.setTextSize(Ui.dp(this, textSizeDp))
            return 1f
        }
        return scale
    }

    override fun onSingleTapUp(e: MotionEvent?) = showKeyboard()

    override fun shouldBackButtonBeMappedToEscape(): Boolean = false

    override fun shouldEnforceCharBasedInput(): Boolean = false

    override fun shouldUseCtrlSpaceWorkaround(): Boolean = false

    override fun isTerminalViewSelected(): Boolean = true

    override fun copyModeChanged(copyMode: Boolean) {}

    override fun onKeyDown(keyCode: Int, e: KeyEvent?, s: TerminalSession?): Boolean = false

    override fun onKeyUp(keyCode: Int, e: KeyEvent?): Boolean = false

    override fun onLongPress(e: MotionEvent?): Boolean = false

    override fun readControlKey(): Boolean {
        val on = ctrl
        if (on) {
            ctrl = false
            paint()
        }
        return on
    }

    override fun readAltKey(): Boolean {
        val on = alt
        if (on) {
            alt = false
            paint()
        }
        return on
    }

    override fun readShiftKey(): Boolean = false

    override fun readFnKey(): Boolean = false

    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, s: TerminalSession?): Boolean = false

    override fun onEmulatorSet() {}

    // ---- logging (both interfaces) ----

    override fun logError(tag: String?, message: String?) {}
    override fun logWarn(tag: String?, message: String?) {}
    override fun logInfo(tag: String?, message: String?) {}
    override fun logDebug(tag: String?, message: String?) {}
    override fun logVerbose(tag: String?, message: String?) {}
    override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {}
    override fun logStackTrace(tag: String?, e: Exception?) {}

    private companion object {
        /** The running shell, kept across visits to this screen. */
        var session: TerminalSession? = null
        /** The screen showing it; when none does, it reports to AWAY. */
        var shown: TerminalActivity? = null
        var textSizeDp = 12

        /** The shell's reports while no screen shows it: only its end matters. */
        val AWAY: TerminalSessionClient = object : TerminalSessionClient {
            override fun onTextChanged(s: TerminalSession) {}
            override fun onTitleChanged(s: TerminalSession) {}
            override fun onSessionFinished(s: TerminalSession) {
                if (session === s) session = null
            }
            override fun onCopyTextToClipboard(s: TerminalSession, text: String?) {}
            override fun onPasteTextFromClipboard(s: TerminalSession?) {}
            override fun onBell(s: TerminalSession) {}
            override fun onColorsChanged(s: TerminalSession) {}
            override fun onTerminalCursorStateChange(state: Boolean) {}
            override fun getTerminalCursorStyle(): Int? = null
            override fun logError(tag: String?, message: String?) {}
            override fun logWarn(tag: String?, message: String?) {}
            override fun logInfo(tag: String?, message: String?) {}
            override fun logDebug(tag: String?, message: String?) {}
            override fun logVerbose(tag: String?, message: String?) {}
            override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {}
            override fun logStackTrace(tag: String?, e: Exception?) {}
        }

        fun hex(color: Int): String = String.format("#%06x", color and 0xFFFFFF)
    }
}
