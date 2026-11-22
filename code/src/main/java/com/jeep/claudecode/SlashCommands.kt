package com.jeep.claudecode

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import java.util.Locale

/** The / commands: those the app handles itself, and the list of all that the box offers. */
class SlashCommands(app: MainActivity) : Part(app) {

    /** name -> {args, description, "local" when the app handles it} */
    fun commandList(): LinkedHashMap<String, Array<String>> {
        val all = LinkedHashMap<String, Array<String>>()
        for (c in LOCAL) all[c[0]] = arrayOf(c[1], c[2], "local")
        for (c in claude.commands) if (!all.containsKey(c)) all[c] = arrayOf("", "", "")
        return all
    }

    private fun note(text: CharSequence) {
        val m = Chat.Msg(Chat.Msg.NOTE)
        m.note = text
        app.current.messages.add(m)
        app.screen.stick = true
        app.renderNow()
    }

    /** Text from parts: strings as they are, {"b", text} bold, {"c", text} command. */
    private fun styled(parts: List<Any>): CharSequence {
        val s = SpannableStringBuilder()
        for (p in parts) {
            if (p is Array<*>) {
                val st = s.length
                s.append(p[1] as String)
                if (p[0] == "b") {
                    s.setSpan(StyleSpan(Typeface.BOLD), st, s.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    s.setSpan(ForegroundColorSpan(col(R.color.text)), st, s.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                } else {
                    s.setSpan(TypefaceSpan("monospace"), st, s.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    s.setSpan(ForegroundColorSpan(col(R.color.accentPress)), st, s.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            } else {
                s.append(p.toString())
            }
        }
        return s
    }

    fun localCommand(text: String): Boolean {
        val t = text.trim().substring(1)
        val name = t.split(Regex("\\s+"), 2)[0]
        val arg = if (t.contains(" ")) t.substring(t.indexOf(' ') + 1).trim() else ""
        when (name) {
            "help" -> {
                val parts = ArrayList<Any>()
                parts.add(arrayOf("b", "命令"))
                for ((key, value) in commandList()) {
                    parts.add("\n")
                    parts.add(arrayOf("c", "/" + key + if (value[0].isEmpty()) "" else " " + value[0]))
                    parts.add("  " + value[1])
                }
                note(styled(parts))
            }
            "new", "clear" -> app.switchTo(app.newChat())
            "stop" -> if (app.current.running) app.sender.stop(app.current) else toast("现在没有在运行")
            "find" -> app.finder.find(arg)
            "tree", "rewind" -> app.messages.tree()
            "folder" ->
                if (app.current.sessionId != null) toast("已有对话的目录不能更改，新对话可以选")
                else app.pickers.folderSheet(null)
            "model" -> {
                if (arg.isEmpty()) {
                    app.pickers.modelSheet()
                    return true
                }
                claude.model = arg
                prefs.edit().putString("model", claude.model).apply()
                app.screen.updateChrome()
            }
            "effort" -> {
                val a = arg.lowercase(Locale.ROOT)
                if (a.isEmpty() || (claude.efforts.isNotEmpty() && !claude.efforts.contains(a))) {
                    app.pickers.modelSheet()
                    return true
                }
                claude.effort = a
                prefs.edit().putString("effort", claude.effort).apply()
                app.screen.updateChrome()
            }
            else -> return false // Claude Code's own command or skill
        }
        return true
    }

    private companion object {
        val LOCAL = arrayOf(
            arrayOf("help", "", "所有命令"), arrayOf("new", "", "新对话"), arrayOf("clear", "", "新对话"),
            arrayOf("effort", "", "思考级别"),
            arrayOf("model", "", "模型"), arrayOf("folder", "", "工作目录"),
            arrayOf("find", "<词>", "在本对话里搜索，↑↓ 换一处，不带词结束"),
            arrayOf("tree", "", "回到之前的某条消息，重新编辑"), arrayOf("rewind", "", "同 /tree"),
            arrayOf("stop", "", "停止"))
    }
}
