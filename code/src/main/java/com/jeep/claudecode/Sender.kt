package com.jeep.claudecode

import android.os.SystemClock
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** Sending: a message written, queued while a reply runs, inserted, answered to Claude Code; the reply stopped. */
class Sender(app: MainActivity) : Part(app) {
    /** When a new conversation was last opened (or written in): see warm. */
    private var warmedAt = 0L

    @JvmField val queueActions: ThreadAdapter.Queue = object : ThreadAdapter.Queue {
        override fun edit(q: Chat.Queued) {
            val chat = app.current
            takeBack(chat, q) {
                if (!chat.queue.remove(q)) return@takeBack
                chat.attachments.addAll(0, q.attachments)
                val here = app.current === chat
                val typed = if (here) app.box.input.text.toString() else chat.draft
                val text = if (typed.trim().isEmpty()) q.text else q.text + "\n" + typed
                if (!here) { // another conversation was opened meanwhile: it waits in this one's draft
                    chat.draft = text
                    return@takeBack
                }
                app.box.input.setText(text)
                app.box.input.setSelection(app.box.input.length())
                app.box.input.requestFocus()
                app.box.showKeyboard()
                app.renderNow()
            }
        }

        override fun cancel(q: Chat.Queued) {
            val chat = app.current
            takeBack(chat, q) {
                chat.queue.remove(q)
                app.renderNow()
            }
        }

        override fun sendNow(q: Chat.Queued) {
            val chat = app.current
            // With Claude Code already: stopping the reply is enough, it then starts a turn for it.
            if (q.inserted) {
                if (chat.running) stop(chat)
                app.renderNow()
                return
            }
            chat.queue.remove(q)
            chat.queue.add(0, q)
            q.now = true
            if (chat.running) stop(chat) else sendQueued(chat)
            app.renderNow()
        }
    }

    fun send() {
        app.voice.cancel() // what was heard is sent; nothing more comes into the emptied box
        val chat = app.current
        val text = app.box.input.text.toString().replace(Regex("\\s+$"), "")
        if (text.trim().isEmpty() && chat.attachments.isEmpty()) {
            if (chat.running) stop(chat)
            return
        }
        if (text.matches(Regex("^/\\S[^\\n]*$")) && app.slash.localCommand(text)) {
            if (!text.startsWith("/find ")) app.box.input.setText("") // /find's words stay in the box
            app.box.hideMenu()
            app.screen.updateChrome()
            return
        }
        if (!app.link.linked) {
            toast(if (app.link.installing) "运行环境还在准备，稍后再发" else "Claude Code 正在启动，稍后再发")
            if (!app.link.connecting) app.link.connect(false)
            return
        }
        if (app.finder.finding()) app.finder.findEnd()
        val attached = ArrayList(chat.attachments)
        chat.attachments.clear()
        app.box.input.setText("")
        app.box.hideMenu()
        if (chat.running) {
            // Written while a reply runs: it waits (edited, taken back or sent now meanwhile), and goes
            // when the reply runs a tool (插入) or when it ends (排队): sendQueued.
            chat.queue.add(Chat.Queued(text, attached))
            app.screen.stick = true
            sendQueued(chat)
            app.renderNow()
            return
        }
        app.box.hideKeyboard()
        submit(chat, text, attached)
    }

    /** What is written while a reply runs: 插入 (sent when the reply runs a tool, the model gets it
     *  with the tool's result; the default) or 排队 (sent when the reply ends). Settings → 对话. */
    fun insertWhileRunning(): Boolean = prefs.getBoolean("insertWhileRunning", true)

    /** Hands a message to Claude Code while a reply runs: the model gets it with its next request,
     *  or Claude Code starts a turn for it when this one ends. It waits as it was (edited, taken back
     *  or sent now) until the model has it (Chat.apply, command_lifecycle). */
    private fun insert(chat: Chat, q: Chat.Queued) {
        q.inserted = true
        q.uuid = UUID.randomUUID().toString()
        upload(chat, q.attachments, 0, ArrayList(), q.text, q)
    }

    /** An inserted message could not be sent: it waits for the reply to end instead. */
    private fun notInserted(chat: Chat, q: Chat.Queued, why: String) {
        if (!q.inserted) return
        q.inserted = false
        q.uuid = null
        q.notInserted = true // not tried again: it goes when the reply ends
        toast("没能插入，改为回复结束后发送：$why")
        if (!chat.running) sendQueued(chat)
        app.render()
    }

    /** Sends a message as the chat's next turn. */
    private fun submit(chat: Chat, text: String, attached: List<Chat.Attachment>) {
        val user = Chat.Msg(Chat.Msg.USER)
        user.text = text
        for (a in attached) {
            if (a.image) user.images.add(a.shown) else user.files.add(a.name)
        }
        chat.messages.add(user)
        if (chat.title.isEmpty()) {
            // A short message is the name until one is made (Claude Code makes none for those); a long one is not shown.
            val t = text.trim().replace(Regex("\\s+"), " ")
            if (t.isNotEmpty() && t.length <= SHORT_TITLE) chat.title = t
        }
        if (chat.cwd.isEmpty()) chat.cwd = claude.cwd.ifEmpty { claude.defaultCwd }
        chat.running = true
        chat.stopped = false
        chat.startedAt = System.currentTimeMillis()
        chat.verb = chat.randomVerb()
        chat.detail = ""
        app.screen.stick = true
        app.renderNow()
        upload(chat, attached, 0, ArrayList(), text, null)
    }

    /** The reply ended: the next queued message goes, unless the reply was stopped by hand. */
    fun sendQueued(chat: Chat) {
        if (chat.queue.isEmpty() || !app.link.linked) return
        if (chat.running) {
            // 插入: while the reply runs a tool, what waits goes now and the model gets it with the
            // tool's result, without the reply ending (a stop asked for keeps it waiting).
            if (!insertWhileRunning() || chat.stopping || !chat.toolRunning()) return
            for (q in ArrayList(chat.queue)) if (!q.inserted && !q.now && !q.notInserted) insert(chat, q)
            return
        }
        val q = chat.queue.firstOrNull { !it.inserted } ?: return // Claude Code has the others
        if (chat.stopped && !q.now) return
        chat.queue.remove(q)
        submit(chat, q.text, q.attachments)
    }

    /** Takes a waiting message back from Claude Code, if it was handed over, then goes on; when the
     *  model has got it meanwhile, it is a message of the conversation and nothing is done. */
    private fun takeBack(chat: Chat, q: Chat.Queued, then: () -> Unit) {
        if (!q.inserted) {
            then()
            return
        }
        bridge.post("/cancel", obj("chat", chat.key, "sessionId", chat.sessionId, "uuid", q.uuid)) { status, data ->
            if (status == 200 && data.optBoolean("cancelled")) {
                q.inserted = false
                q.uuid = null
                then()
            } else if (status == 200) {
                toast("模型已经读到这条消息了")
            } else {
                toast("没能撤回：" + data.optString("error", "HTTP $status"))
            }
        }
    }

    /** Saves non-picture files into the working folder one by one, then sends. */
    private fun upload(chat: Chat, attached: List<Chat.Attachment>, from: Int, saved: MutableList<String>, text: String, inserted: Chat.Queued?) {
        var i = from
        while (i < attached.size && attached[i].image) i++
        if (i >= attached.size) {
            deliver(chat, attached, saved, text, inserted)
            return
        }
        val f = attached[i]
        val next = i + 1
        bridge.upload(chat.cwd, f.name, f.uri!!) { status, data ->
            if (status != 200) {
                val why = f.name + " 没能存到 " + Ui.base(chat.cwd) + "：" + data.optString("error", status.toString())
                if (inserted != null) notInserted(chat, inserted, why) else fail(chat, "发送失败", why)
                return@upload
            }
            saved.add(data.optString("path"))
            upload(chat, attached, next, saved, text, inserted)
        }
    }

    private fun deliver(chat: Chat, attached: List<Chat.Attachment>, saved: List<String>, text: String, inserted: Chat.Queued?) {
        val content = JSONArray()
        for (a in attached) {
            if (!a.image) continue
            content.put(obj("type", "image", "source", obj("type", "base64", "media_type", a.type, "data", a.data)))
        }
        val full = StringBuilder(if (text.trim().isEmpty()) "" else text)
        if (saved.isNotEmpty()) {
            if (full.isNotEmpty()) full.append("\n\n")
            full.append(if (saved.size > 1) "附件已保存到：" else "附件已保存为：")
            for (p in saved) full.append("\n- ").append(p)
        }
        if (full.isNotEmpty()) content.put(obj("type", "text", "text", full.toString()))
        val sentAt = System.currentTimeMillis()
        val body = obj("chat", chat.key, "sessionId", chat.sessionId, "cwd", chat.cwd,
            "model", claude.model.ifEmpty { null }, "effort", claude.effort.ifEmpty { null },
            "content", content, "mid", chat.key + ":" + sentAt,
            "uuid", inserted?.uuid)
        sendWithRetry(chat, body, sentAt, 0, inserted)
    }

    /** The answer may be lost with the message delivered: the bridge ignores a repeated mid. */
    private fun sendWithRetry(chat: Chat, body: JSONObject, sentAt: Long, attempt: Int, inserted: Chat.Queued?) {
        bridge.post("/send", body) { status, data ->
            if (status == 200) return@post
            if (status < 0 && attempt < 2) {
                ui.postDelayed({ sendWithRetry(chat, body, sentAt, attempt + 1, inserted) }, 1200)
                return@post
            }
            if (inserted != null) {
                // Refused for sure: it waits for the reply instead. No answer: it may have gone (the
                // reply runs on regardless), so it is not sent twice.
                if (status >= 400 || status == Bridge.REFUSED) notInserted(chat, inserted, data.optString("error", status.toString()))
                return@post
            }
            if (chat.lastEvent > sentAt) return@post // it is running after all
            fail(chat, "发送失败", when (status) {
                -2 -> "后台没有回应。"
                -1, Bridge.REFUSED -> "连不上后台。"
                else -> data.optString("error", status.toString())
            })
        }
    }

    fun fail(chat: Chat, titleText: String, text: String?) {
        chat.running = false
        val m = chat.reply(true)!!
        val b = Chat.Block(Chat.Block.ERROR)
        b.title = titleText
        b.text = text ?: ""
        m.blocks.add(b)
        m.done = true
        m.touch()
        app.render()
    }

    /**
     * Answers Claude Code's question whether a tool may run. answers: for AskUserQuestion, what the
     * user chose (question -> answer), sent with the allow.
     */
    fun answer(chat: Chat, b: Chat.Block, allow: Boolean, answers: JSONObject?) {
        val request = b.ask ?: return
        b.ask = null
        b.touch()
        app.renderNow()
        val body = obj("chat", chat.key, "sessionId", chat.sessionId, "requestId", request, "allow", allow)
        if (answers != null) body.put("answers", answers)
        bridge.post("/permission", body) { status, data ->
            if (status == 200 || status == 409) return@post // 409: not asked any more (the turn ended)
            b.ask = request
            b.touch()
            app.render()
            toast("没能回复：" + data.optString("error", status.toString()))
        }
    }

    fun stop(chat: Chat) {
        chat.stopping = true
        // Nothing to stop (the reply had just ended, or the request was lost): no result will say it
        // was stopped, and the next turn must not be taken for one stopped.
        bridge.post("/stop", obj("chat", chat.key, "sessionId", chat.sessionId)) { status, data ->
            if (status != 200 || !data.optBoolean("stopped")) chat.stopping = false
        }
    }

    /** A new conversation's Claude Code (the bridge's spare) is made ready, so its first message
     *  does not wait for one to start. A saved conversation's starts only when a message is sent. */
    fun opened(chat: Chat?) {
        if (chat == null || chat.sessionId != null) return
        warmedAt = SystemClock.uptimeMillis()
        bridge.post("/open", obj("chat", chat.key, "sessionId", chat.sessionId, "cwd", chat.cwd,
            "model", claude.model.ifEmpty { null }, "effort", claude.effort.ifEmpty { null }), null)
    }

    /** Something is being written in a new conversation: its Claude Code gets ready meanwhile
     *  (opened), at most every 20 s. */
    fun warm() {
        if (!app.link.linked || !app.hasCurrent || app.current.running) return
        if (SystemClock.uptimeMillis() - warmedAt < 20_000) return
        opened(app.current)
    }

    private companion object {
        const val SHORT_TITLE = 16 // as the bridge's: a first message this short names its conversation until it has a name
    }
}
