package com.jeep.claudecode

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.PopupWindow
import kotlin.math.max
import kotlin.math.min
import org.json.JSONArray
import org.json.JSONObject

/** What can be done with a message: its menu, selecting text, translating; going back to a message (/tree), taking one out. */
class MessageActions(app: MainActivity) : Part(app) {

    /** The conversation read again from its transcript; shown again if it is the one open. */
    private fun reload(chat: Chat, data: JSONObject) {
        chat.load(data)
        chat.loaded = true
        if (app.current === chat) {
            app.screen.adapter.show(chat, true)
            app.renderNow()
        }
    }

    /**
     * /tree: the user's messages in this conversation, the last first. One picked, the conversation
     * goes back to before it (the bridge's /rewind cuts the transcript there: the model never sees
     * what was after, a message its safeguards refused included) and its text is in the box again.
     */
    fun tree() {
        val chat = app.current
        val id = chat.sessionId
        if (id == null) {
            toast("这个对话还没有可以回退的消息")
            return
        }
        if (chat.running) {
            toast("回复结束后才能回退")
            return
        }
        if (!app.link.linked) {
            toast("Claude Code 正在启动，稍后再试")
            return
        }
        // Read again: a message sent from here knows its uuid only from the transcript.
        bridge.get("/history?id=" + Uri.encode(id)) { status, data ->
            if (status != 200) {
                toast("读取对话失败：" + data.optString("error", "HTTP $status"))
                return@get
            }
            reload(chat, data)
            val users = chat.messages.filter { it.kind == Chat.Msg.USER && it.uuid != null }
            if (users.isEmpty()) {
                toast("没有可以回退的消息")
                return@get
            }
            val sheet = sheets.sheet("回到哪条消息之前", "选中的消息和它之后的对话都会删掉，它的文字放回输入框")
            val card = sheets.card(sheet)
            for (i in users.indices.reversed()) {
                val m = users[i]
                sheets.option(card, 0, "${i + 1}. " + preview(m), null, false) {
                    sheet.dismiss()
                    rewindAsk(chat, m)
                }
            }
            sheet.show()
        }
    }

    private fun rewindAsk(chat: Chat, m: Chat.Msg) {
        val sheet = sheets.sheet("回到这条消息之前？", preview(m) + "\n\n它和之后的对话会删掉，不能恢复。")
        val cancel = sheets.button("取消", false) { sheet.dismiss() }
        val ok = sheets.button("回退", true) {
            sheet.dismiss()
            rewind(chat, m)
        }
        sheets.actions(sheet, cancel, ok)
        sheet.show()
    }

    private fun rewind(chat: Chat, m: Chat.Msg) {
        val id = chat.sessionId ?: return
        bridge.post("/rewind", obj("chat", chat.key, "sessionId", id, "uuid", m.uuid)) { status, data ->
            if (status != 200) {
                toast("回退失败：" + data.optString("error", "HTTP $status"))
                return@post
            }
            if (data.optInt("left") == 0) {
                gone(chat, id, m.text) // nothing was before it: a new conversation starts with its text
            } else {
                val at = chat.messages.indexOf(m)
                if (at >= 0) chat.messages.subList(at, chat.messages.size).clear()
                if (app.current === chat) {
                    app.box.input.setText(m.text)
                    app.box.input.setSelection(app.box.input.length())
                    app.screen.stick = true
                    app.screen.adapter.show(chat, true)
                    app.renderNow()
                } else {
                    chat.draft = m.text
                }
            }
            toast(if (m.images.isNotEmpty()) "已回退，图片要重新添加" else "已回退")
            app.drawer.loadSessions()
        }
    }

    /** The conversation's transcript is gone (nothing left in it): a new one takes its place, with this draft. */
    private fun gone(chat: Chat, id: String, draft: String) {
        val next = JSONArray()
        val sessions = app.drawer.sessions
        for (i in 0 until sessions.length()) {
            val s = sessions.optJSONObject(i)
            if (s != null && id != s.optString("id")) next.put(s)
        }
        app.drawer.keepSessions(next)
        app.drop(chat.key, id)
        val fresh = app.newChat()
        fresh.cwd = chat.cwd
        fresh.draft = draft
        if (app.current === chat) app.switchTo(fresh)
    }

    /** A message pressed long: a small card where the finger is, in place of selecting its text. */
    fun messageMenu(m: Chat.Msg) {
        app.screen.list.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        val chat = app.current
        val user = m.kind == Chat.Msg.USER
        val shade = dp(24) // room for the card's whole shadow
        val room = FrameLayout(app)
        room.setPadding(shade, shade, shade, shade)
        val card = sheets.menuCard()
        room.addView(card, FrameLayout.LayoutParams(-2, -2))
        val pop = PopupWindow(room, -2, -2, true)
        pop.setBackgroundDrawable(ColorDrawable(0))
        pop.isOutsideTouchable = true
        pop.elevation = 0f // the window's own shadow is square: only the card's, round, is drawn
        // Selecting is what is done most; it shows the Markdown, so a table's cells can be selected too.
        sheets.menuItem(card, pop, R.drawable.ic_edit_lines, "选择文字", null) { selectSheet(if (user) m.text else replyText(m, false)) }
        if (user) {
            sheets.menuItem(card, pop, R.drawable.ic_copy, "复制", null) { app.copy(m.text) }
        } else {
            sheets.menuItem(card, pop, R.drawable.ic_file, "复制 Markdown", null) { app.copy(replyText(m, false)) }
            sheets.menuItem(card, pop, R.drawable.ic_copy, "复制文字", null) { app.copy(replyText(m, true)) }
        }
        if (user) sheets.menuItem(card, pop, R.drawable.ic_pencil, "编辑重发", null) {
            if (canChange(chat, "回退")) withUuid(chat, m) { found -> rewindAsk(chat, found) }
        }
        sheets.menuItem(card, pop, R.drawable.ic_trash, "删除", null) {
            if (canChange(chat, "删除")) withUuid(chat, m) { found -> deleteAsk(chat, found) }
        }
        // Where the finger was, kept on the screen: above it when there is no room below.
        room.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val w = room.measuredWidth
        val h = room.measuredHeight
        val screenW = app.resources.displayMetrics.widthPixels
        val screenH = app.resources.displayMetrics.heightPixels
        val touchX = app.screen.touchX
        val touchY = app.screen.touchY
        val x = max(0, min(touchX.toInt() - w / 2, screenW - w))
        val y = if (touchY.toInt() + h > screenH - dp(24)) touchY.toInt() - h else touchY.toInt()
        pop.showAtLocation(app.screen.list, Gravity.NO_GRAVITY, x, max(0, y))
        // It grows out of the finger.
        card.pivotX = max(0f, min((w - 2 * shade).toFloat(), touchX - x - shade))
        card.pivotY = max(0f, min((h - 2 * shade).toFloat(), touchY - max(0, y) - shade))
        card.alpha = 0f
        card.scaleX = 0.8f
        card.scaleY = 0.8f
        card.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(250)
            .setInterpolator(Motion.EMPHASIZED).start()
    }

    /** A reply's text blocks: as shown (plain) or as written (Markdown). */
    private fun replyText(m: Chat.Msg, plain: Boolean): String =
        m.blocks.filter { it.kind == Chat.Block.TEXT }.joinToString("\n\n") { if (plain) app.md.plain(it.text) else it.text }

    /** The text in a sheet, to select a part of it. */
    private fun selectSheet(text: String) {
        val sheet = sheets.sheet("选择文字", null)
        val t = Ui.text(app, text, 15.5f, R.color.text)
        t.setLineSpacing(0f, 1.1f)
        t.setTextIsSelectable(true)
        sheets.content(sheet).addView(t, Ui.fill())
        // The sheet reaches the screen's bottom edge (no navigation bar): the text, and the scroll it
        // is in, end above the system's gesture strip, or a swipe there is the system's (one-handed
        // mode), and with room under them.
        val box = sheet.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
        val insets = app.window.decorView.rootWindowInsets
        val gestures = if (insets != null && Build.VERSION.SDK_INT >= 29) insets.mandatorySystemGestureInsets.bottom else 0
        box.setPadding(box.paddingLeft, box.paddingTop, box.paddingRight, box.paddingBottom + gestures)
        sheet.show()
    }

    /**
     * The text in Google Translate, as its 翻译 for selected text does (shown over the app, nothing
     * replaced); without it, the apps that take selected text, to pick one.
     */
    fun translate(text: String) {
        var s = text.trim()
        if (s.isEmpty()) return
        // Far more than it translates at once, and within what an intent may carry.
        if (s.length > 100_000) s = s.substring(0, 100_000)
        val i = Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain")
            .putExtra(Intent.EXTRA_PROCESS_TEXT, s).putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)
        try {
            app.startActivity(Intent(i).setPackage("com.google.android.apps.translate"))
        } catch (e: ActivityNotFoundException) {
            try {
                app.startActivity(Intent.createChooser(i, "翻译"))
            } catch (e2: RuntimeException) {
                toast("没有可以翻译的应用")
            }
        }
    }

    /** Whether the conversation's transcript may be changed now; says why not. */
    private fun canChange(chat: Chat, what: String): Boolean {
        if (chat.sessionId == null) {
            toast("这条消息还没存进对话记录")
            return false
        }
        if (chat.running) {
            toast("回复结束后才能$what")
            return false
        }
        if (!app.link.linked) {
            toast("Claude Code 正在启动，稍后再试")
            return false
        }
        return true
    }

    /**
     * The message with its uuid. One sent or received here knows it only from the transcript: that
     * is read again, and the message found there by its place among those of its kind and its text.
     */
    private fun withUuid(chat: Chat, m: Chat.Msg, then: (Chat.Msg) -> Unit) {
        if (m.uuid != null) {
            then(m)
            return
        }
        var place = 0
        for (o in chat.messages) {
            if (o === m) break
            if (o.kind == m.kind) place++
        }
        val text = if (m.kind == Chat.Msg.USER) m.text else replyText(m, false)
        bridge.get("/history?id=" + Uri.encode(chat.sessionId)) { status, data ->
            if (status != 200) {
                toast("读取对话失败：" + data.optString("error", "HTTP $status"))
                return@get
            }
            reload(chat, data)
            val o = chat.messages.filter { it.kind == m.kind }.getOrNull(place)
            if (o != null && o.uuid != null && text == (if (o.kind == Chat.Msg.USER) o.text else replyText(o, false))) then(o)
            else toast("对话记录里找不到这条消息")
        }
    }

    private fun deleteAsk(chat: Chat, m: Chat.Msg) {
        val what = if (m.kind == Chat.Msg.USER) preview(m) else replyText(m, true).trim().replace(Regex("\\s+"), " ")
        val sheet = sheets.sheet("删除这条消息？", (if (what.length > 80) what.substring(0, 80) + "…" else what) +
            "\n\n" + (if (m.kind == Chat.Msg.USER) "" else "这条回复里的工具调用也一起删掉。") +
            "Claude Code 的对话记录里也会删掉，不能恢复。")
        val cancel = sheets.button("取消", false) { sheet.dismiss() }
        val ok = sheets.button("删除", true) {
            sheet.dismiss()
            deleteMessage(chat, m)
        }
        sheets.actions(sheet, cancel, ok)
        sheet.show()
    }

    /**
     * Takes the message out of the transcript (the bridge's /delete), then reads it again: what was
     * on its two sides is shown as the transcript now has it, and pictures are named anew.
     */
    private fun deleteMessage(chat: Chat, m: Chat.Msg) {
        val id = chat.sessionId ?: return
        val role = if (m.kind == Chat.Msg.USER) "user" else "assistant"
        bridge.post("/delete-message", obj("chat", chat.key, "sessionId", id, "uuid", m.uuid, "role", role)) { status, data ->
            if (status != 200 || !data.has("left")) {
                toast("删除失败：" + data.optString("error", "HTTP $status"))
                return@post
            }
            toast("已删除")
            app.drawer.loadSessions()
            if (data.optInt("left") == 0) {
                gone(chat, id, "")
                return@post
            }
            Images.forget()
            bridge.get("/history?id=" + Uri.encode(id)) { st, history ->
                if (st != 200) return@get
                reload(chat, history)
            }
        }
    }

    private companion object {
        fun preview(m: Chat.Msg): String {
            val t = m.text.trim().replace(Regex("\\s+"), " ")
            return t.ifEmpty { if (m.images.isNotEmpty()) "[图片]" else "（空）" }
        }
    }
}
