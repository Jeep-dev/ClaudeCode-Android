package com.jeep.claudecode

import android.graphics.Bitmap
import android.net.Uri
import java.util.Random
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

private val IDS = AtomicLong(1)

/** One conversation, built from Claude Code's stream-json output or its saved transcript. */
class Chat(@JvmField val key: String) {

    class Msg(@JvmField val kind: Int) {
        @JvmField val id = IDS.getAndIncrement()
        @JvmField var text = ""
        /** Its uuid in the transcript (from /history): a user's message's, or a reply's first entry's;
         *  what /tree goes back to and 删除 takes out. */
        @JvmField var uuid: String? = null
        @JvmField var note: CharSequence? = null
        @JvmField val images = ArrayList<String?>() // data: URLs, null when not kept
        @JvmField val files = ArrayList<String>()
        @JvmField val blocks = ArrayList<Block>()
        @JvmField var done = false
        @JvmField var version = 0
        @JvmField val index = HashMap<Int, Block>() // stream block index -> block
        @JvmField val streamed = HashSet<String>()

        fun touch() { version++ }

        companion object {
            const val USER = 0
            const val ASSISTANT = 1
            const val NOTE = 2
            const val DIVIDER = 3
        }
    }

    class Block(@JvmField val kind: Int) {
        @JvmField val id = IDS.getAndIncrement()
        @JvmField var text = ""
        @JvmField var title = ""
        // tools
        @JvmField var toolId: String? = null
        @JvmField var name: String? = null
        @JvmField var result: String? = null
        /** The tool's input as it streams, read whole when its block ends. */
        @JvmField val partial = StringBuilder()
        /** When the streaming input was last read into input. */
        @JvmField var shown = 0L
        /** Images in the tool's result (a picture Read): data: URLs, or ccimg: references to a saved one. */
        @JvmField val images = ArrayList<String>()
        @JvmField var input = JSONObject()
        @JvmField var running = false
        @JvmField var error = false
        /** Claude Code asks whether this tool may run: the question to answer; null once answered. */
        @JvmField var ask: String? = null
        /** AskUserQuestion: the options picked so far, per question, and an answer written instead
         *  (其他). Kept here so the row, drawn again, keeps them. */
        @JvmField val picked = HashMap<Int, LinkedHashSet<String>>()
        @JvmField val other = HashMap<Int, String>()
        // thinking
        @JvmField var streaming = false
        @JvmField var started = 0L
        @JvmField var millis = 0L
        @JvmField var open = false
        @JvmField var version = 0

        fun touch() { version++ }

        companion object {
            const val TEXT = 0
            const val THINKING = 1
            const val TOOL = 2
            const val ERROR = 3
            const val STOPPED = 4

            /** A tool Claude Code runs (or asks to run): its row, running. */
            @JvmStatic
            fun tool(toolId: String?, name: String?) = Block(TOOL).apply {
                this.toolId = toolId
                this.name = name
                running = true
            }
        }
    }

    class Attachment(
        @JvmField val image: Boolean,
        @JvmField val name: String,
        @JvmField val type: String?,
        /** A picture: base64. */
        @JvmField val data: String?,
        /** A file: where it is, read when it is saved (never held here). */
        @JvmField val uri: Uri?,
        @JvmField val size: Long,
        @JvmField val thumb: Bitmap?,
    ) {
        /** A picture: the copy the thread shows (a JPEG of the size the model sees); the one sent may be up to 10 MB. */
        @JvmField var shown: String? = null
    }

    class Queued(@JvmField val text: String, @JvmField val attachments: List<Attachment>) {
        @JvmField val id = IDS.getAndIncrement()
        /** Sent as soon as the turn being stopped for it has ended. */
        @JvmField var now = false
        /** Handed to Claude Code while a reply ran (插入): in its queue, still waiting (taken back from
         *  there when edited or cancelled), until the model gets it with its next request or Claude
         *  Code starts a turn for it (command_lifecycle started: it is then a message of the conversation). */
        @JvmField var inserted = false
        /** Its uuid, as Claude Code knows it once handed over. */
        @JvmField var uuid: String? = null
        /** Could not be inserted: waits for the reply to end instead. */
        @JvmField var notInserted = false
    }

    @JvmField var sessionId: String? = null
    @JvmField var title = ""
    @JvmField var cwd = ""
    @JvmField var model: String? = null
    @JvmField val messages = ArrayList<Msg>()
    @JvmField var running = false
    @JvmField var stopping = false
    @JvmField var loaded = true
    /** The last turn ended because it was stopped: queued messages then wait to be sent by hand. */
    @JvmField var stopped = false
    /** Messages written while a turn runs, waiting until the model gets them: sent one by one when
     *  it ends, or (插入) handed to Claude Code while it runs a tool (Queued.inserted). */
    @JvmField val queue = ArrayList<Queued>()
    @JvmField var startedAt = 0L
    @JvmField var lastEvent = 0L
    @JvmField var verb = VERBS[0]
    @JvmField var detail = ""
    @JvmField var draft = ""
    @JvmField val attachments = ArrayList<Attachment>()

    fun randomVerb(): String = VERBS[Random().nextInt(VERBS.size)]

    /** The running reply has a tool running (or being written): a message sent now goes to the
     *  model with the tool's result. */
    fun toolRunning(): Boolean =
        // The reply goes on below a message inserted into it: its earlier part may run the tool.
        messages.any { m -> m.kind == Msg.ASSISTANT && !m.done && m.blocks.any { it.kind == Block.TOOL && it.running } }

    /** Something waiting is with Claude Code, which goes on (or starts a turn) for it. */
    fun handed(): Boolean = queue.any { it.inserted }

    /** Claude Code is gone (or was replaced) with what it held: that waits here again, to be sent. */
    fun unhand() {
        for (q in queue) { q.inserted = false; q.uuid = null }
    }

    /** A message the model has got (handed over while a reply ran): from now a message of the
     *  conversation, the reply going on below it. */
    private fun showInserted(q: Queued) {
        val u = Msg(Msg.USER)
        u.text = q.text
        for (a in q.attachments) {
            if (a.image) u.images.add(a.shown) else u.files.add(a.name)
        }
        messages.add(u)
    }

    /** The reply being written, created on demand. */
    fun reply(create: Boolean): Msg? {
        val last = messages.lastOrNull()
        if (last != null && last.kind == Msg.ASSISTANT && !last.done) return last
        if (!create) return null
        return Msg(Msg.ASSISTANT).also { messages.add(it) }
    }

    private fun reply(): Msg = reply(true)!!

    fun tool(id: String): Block? {
        for (i in messages.indices.reversed()) {
            messages[i].blocks.firstOrNull { it.kind == Block.TOOL && id == it.toolId }?.let { return it }
        }
        return null
    }

    fun owner(block: Block): Msg? = messages.lastOrNull { block in it.blocks }

    /**
     * Applies one Claude Code stream-json message. Returns true when the reply ended (the
     * conversation list should be refreshed).
     */
    fun apply(d: JSONObject): Boolean {
        // Inside a sub-agent: shown as its step, not as part of the reply.
        val sideChain = !d.isNull("parent_tool_use_id") && d.has("parent_tool_use_id")
        when (d.optString("type")) {
            "command_lifecycle" -> commandLifecycle(d)
            "control_request" -> controlRequest(d)
            "control_cancel_request" -> controlCancel(d)
            "system" -> system(d)
            "stream_event" -> if (!sideChain) streamEvent(d)
            "assistant" -> if (!sideChain) assistant(d)
            "user" -> if (!sideChain) toolResults(d)
            "result" -> return result(d)
        }
        return false
    }

    /** What happens to a message handed to Claude Code while a reply ran. */
    private fun commandLifecycle(d: JSONObject) {
        val id = d.optString("command_uuid")
        val state = d.optString("state")
        val q = queue.firstOrNull { it.inserted && id == it.uuid } ?: return
        if (state == "started") {
            queue.remove(q)
            showInserted(q)
        } else if (state != "queued") { // it will not reach the model: it waits here again
            q.inserted = false
            q.uuid = null
            q.notInserted = true
        }
    }

    /** Claude Code asks whether a tool may run (a permission mode that asks): its row asks the user. */
    private fun controlRequest(d: JSONObject) {
        val r = d.optJSONObject("request")
        if (r == null || r.optString("subtype") != "can_use_tool") return
        val id = r.optString("tool_use_id")
        val b = (if (id.isEmpty()) null else tool(id)) ?: Block.tool(id, r.optString("tool_name")).also {
            val m = reply()
            m.blocks.add(it)
            m.touch()
        }
        r.optJSONObject("input")?.let { b.input = it }
        b.ask = d.optString("request_id")
        b.touch()
    }

    private fun controlCancel(d: JSONObject) {
        val id = d.optString("request_id")
        for (m in messages) for (b in m.blocks) if (id == b.ask) { b.ask = null; b.touch() }
    }

    private fun system(d: JSONObject) {
        when (d.optString("subtype")) {
            "init" -> {
                // A turn begins: also one Claude Code starts by itself, for a message inserted too late
                // for the turn before.
                if (!running) {
                    running = true
                    startedAt = System.currentTimeMillis()
                }
                if (d.has("session_id")) sessionId = d.optString("session_id")
                model = d.optString("model", model)
            }
            "api_retry" -> {
                // No HTTP status (null) means the connection itself failed, e.g. dropped mid-request;
                // Claude Code then calls the error "unknown".
                val error = if (d.isNull("error")) "" else d.optString("error")
                val why = when {
                    !d.isNull("error_status") -> (d.optString("error_status") + " " + error).trim()
                    error.isEmpty() || error == "unknown" -> "连接断开"
                    else -> error
                }
                detail = "重试 " + d.optInt("attempt") + "/" + d.optInt("max_retries") + (if (why.isEmpty()) "" else " · $why")
            }
            "status" -> if (d.optString("status") == "requesting") detail = ""
            "compact_boundary" -> messages.add(Msg(Msg.DIVIDER).apply { text = "上下文已压缩" })
        }
    }

    /** The reply as it is written, piece by piece (--include-partial-messages). */
    private fun streamEvent(d: JSONObject) {
        detail = ""
        val e = d.optJSONObject("event") ?: return
        val m = reply()
        val index = e.optInt("index")
        when (e.optString("type")) {
            "message_start" -> messageStart(m, e)
            "content_block_start" -> blockStart(m, e, index)
            "content_block_delta" -> blockDelta(m, e, index)
            "content_block_stop" -> blockStop(m, index)
        }
    }

    private fun messageStart(m: Msg, e: JSONObject) {
        m.index.clear()
        e.optJSONObject("message")?.let { m.streamed.add(it.optString("id")) }
    }

    private fun blockStart(m: Msg, e: JSONObject, index: Int) {
        val cb = e.optJSONObject("content_block") ?: return
        val b = when (cb.optString("type")) {
            "text" -> Block(Block.TEXT).apply { text = cb.optString("text") }
            "thinking" -> {
                verb = "思考中"
                Block(Block.THINKING).apply {
                    streaming = true
                    started = System.currentTimeMillis()
                }
            }
            "tool_use" -> {
                verb = "运行中"
                Block.tool(cb.optString("id"), cb.optString("name"))
            }
            else -> return
        }
        m.blocks.add(b)
        m.index[index] = b
        m.touch()
    }

    private fun blockDelta(m: Msg, e: JSONObject, index: Int) {
        val b = m.index[index] ?: return
        val dl = e.optJSONObject("delta") ?: return
        when (dl.optString("type")) {
            "text_delta" -> {
                b.text += dl.optString("text")
                verb = "生成中"
            }
            "thinking_delta" -> b.text += dl.optString("thinking")
            "input_json_delta" -> {
                // A long file's input is thousands of pieces: what came so far (its path, its
                // text) is read at most every 300 ms, so a Write shows as it is written and the
                // row is not drawn again for each piece.
                b.partial.append(dl.optString("partial_json"))
                val now = System.currentTimeMillis()
                if (now - b.shown < 300) return
                b.shown = now
                b.input = partialInput(b.partial)
            }
        }
        b.touch()
    }

    private fun blockStop(m: Msg, index: Int) {
        val b = m.index[index] ?: return
        if (b.kind == Block.TOOL && b.partial.isNotEmpty()) {
            try { b.input = JSONObject(b.partial.toString()) } catch (ignored: Exception) { /* incomplete */ }
            b.partial.setLength(0)
            b.touch()
        }
        if (b.kind == Block.THINKING) {
            b.streaming = false
            b.millis = System.currentTimeMillis() - b.started
            b.touch()
        }
    }

    /** A whole message of the model's: what the stream did not already bring, and each tool's final input. */
    private fun assistant(d: JSONObject) {
        val msg = d.optJSONObject("message") ?: return
        val m = reply()
        val streamed = msg.optString("id") in m.streamed
        val content = msg.optJSONArray("content") ?: return
        for (i in 0 until content.length()) {
            val c = content.optJSONObject(i) ?: continue
            val ct = c.optString("type")
            if (ct == "tool_use") toolUse(m, c)
            else if (!streamed) {
                if (ct == "text" && c.optString("text").isNotEmpty()) {
                    m.blocks.add(Block(Block.TEXT).apply { text = c.optString("text") })
                } else if (ct == "thinking" && c.optString("thinking").isNotEmpty()) {
                    m.blocks.add(Block(Block.THINKING).apply { text = c.optString("thinking") })
                }
                m.touch()
            }
        }
    }

    private fun toolUse(m: Msg, c: JSONObject) {
        val b = tool(c.optString("id"))
        if (b != null) {
            c.optJSONObject("input")?.let { b.input = it }
            b.touch()
        } else {
            val t = Block.tool(c.optString("id"), c.optString("name"))
            t.input = c.optJSONObject("input") ?: JSONObject()
            m.blocks.add(t)
            m.touch()
        }
    }

    /** What the tools gave back. */
    private fun toolResults(d: JSONObject) {
        val content = d.optJSONObject("message")?.optJSONArray("content")
        for (i in 0 until (content?.length() ?: 0)) {
            val c = content!!.optJSONObject(i)
            if (c == null || c.optString("type") != "tool_result") continue
            val b = tool(c.optString("tool_use_id")) ?: continue
            b.result = resultText(c.opt("content"))
            b.images.clear()
            b.images.addAll(resultImages(c.opt("content")))
            b.error = c.optBoolean("is_error")
            b.running = false
            b.ask = null
            b.touch()
        }
        verb = randomVerb()
    }

    /** The turn ends. True: the conversation list should be refreshed. */
    private fun result(d: JSONObject): Boolean {
        val sub = d.optString("subtype")
        // The reply may be in parts, a message inserted into it between them (showInserted): all
        // end; how it ended goes under its last part, above a message inserted too late for it.
        var m: Msg? = null
        for (x in messages) {
            if (x.kind != Msg.ASSISTANT || x.done) continue
            m?.let { it.done = true; it.touch() }
            m = x
            for (b in x.blocks) {
                if (b.kind == Block.TOOL && (b.running || b.ask != null)) { b.running = false; b.ask = null; b.touch() }
                if (b.kind == Block.THINKING && b.streaming) { b.streaming = false; b.touch() }
            }
        }
        val last = m ?: reply()
        if (sub == "error_during_execution" || stopping) {
            last.blocks.add(Block(Block.STOPPED))
        } else if (d.optBoolean("is_error")) {
            last.blocks.add(Block(Block.ERROR).apply {
                title = "请求失败"
                text = d.optString("result", sub)
            })
        }
        last.done = true
        last.touch()
        // A message inserted too late for this turn gets one of its own (Claude Code starts it).
        running = handed()
        stopped = stopping || sub == "error_during_execution"
        stopping = false
        if (d.has("session_id")) sessionId = d.optString("session_id")
        return true
    }

    /** Messages from the bridge's /history (Claude Code's transcript). */
    fun load(history: JSONObject) {
        val live = reply(false) // a reply under way is not in the transcript yet: it stays, after it
        messages.clear()
        if (history.optString("model").isNotEmpty() && model == null) model = history.optString("model")
        val list = history.optJSONArray("messages")
        for (i in 0 until (list?.length() ?: 0)) {
            val h = list!!.optJSONObject(i) ?: continue
            when (h.optString("role")) {
                "divider" -> messages.add(Msg(Msg.DIVIDER).apply { text = h.optString("text") })
                "user" -> messages.add(savedUser(h))
                "assistant" -> messages.add(savedReply(h))
            }
        }
        if (live != null) messages.add(live)
    }

    companion object {
        @JvmField val VERBS = arrayOf("琢磨中", "推敲中", "酝酿中", "构思中", "运转中")

        @JvmStatic
        fun newKey(): String =
            "n-" + System.currentTimeMillis().toString(36) + Random().nextInt(1 shl 20).toString(36)

        /** The images in a tool result, as data: URLs. */
        @JvmStatic
        fun resultImages(content: Any?): List<String> {
            val out = ArrayList<String>()
            if (content !is JSONArray) return out
            for (i in 0 until content.length()) {
                val c = content.optJSONObject(i)
                val src = if (c == null || c.optString("type") != "image") null else c.optJSONObject("source")
                if (src != null && src.optString("type") == "base64" && src.optString("data").isNotEmpty()) {
                    out.add("data:" + src.optString("media_type", "image/png") + ";base64," + src.optString("data"))
                }
            }
            return out
        }

        @JvmStatic
        fun resultText(content: Any?): String {
            if (content is String) return content
            if (content !is JSONArray) return ""
            val s = StringBuilder()
            for (i in 0 until content.length()) {
                val c = content.optJSONObject(i) ?: continue
                if (s.isNotEmpty()) s.append('\n')
                when (c.optString("type")) {
                    "text" -> s.append(c.optString("text"))
                    "image" -> s.append("[图片]")
                }
            }
            return s.toString()
        }

        private fun uuidOf(h: JSONObject): String? = if (h.isNull("uuid")) null else h.optString("uuid", null)

        private fun savedUser(h: JSONObject) = Msg(Msg.USER).apply {
            text = h.optString("text")
            uuid = uuidOf(h)
            val imgs = h.optJSONArray("images")
            for (k in 0 until (imgs?.length() ?: 0)) images.add(if (imgs!!.isNull(k)) null else imgs.optString(k))
        }

        private fun savedReply(h: JSONObject) = Msg(Msg.ASSISTANT).apply {
            done = true
            uuid = uuidOf(h)
            val list = h.optJSONArray("blocks")
            for (k in 0 until (list?.length() ?: 0)) {
                val o = list!!.optJSONObject(k) ?: continue
                when (o.optString("type")) {
                    "text" -> blocks.add(Block(Block.TEXT).apply { text = o.optString("text") })
                    "thinking" -> blocks.add(Block(Block.THINKING).apply { text = o.optString("text") })
                    "tool" -> blocks.add(savedTool(o))
                }
            }
        }

        private fun savedTool(o: JSONObject) = Block(Block.TOOL).apply {
            toolId = o.optString("id")
            name = o.optString("name")
            input = o.optJSONObject("input") ?: JSONObject()
            result = if (o.has("result")) o.optString("result") else null
            val imgs = o.optJSONArray("images")
            for (n in 0 until (imgs?.length() ?: 0)) images.add(imgs!!.optString(n))
            error = o.optBoolean("isError")
        }

        /**
         * An object's JSON cut off anywhere: the keys whole so far, a string value cut off as far as it
         * came. A nested value is kept only once it is whole.
         */
        @JvmStatic
        fun partialInput(s: CharSequence): JSONObject {
            val o = JSONObject()
            val n = s.length
            var i = skip(s, 0)
            if (i >= n || s[i] != '{') return o
            i++
            val key = StringBuilder()
            val value = StringBuilder()
            try {
                while (true) {
                    i = skip(s, i)
                    while (i < n && s[i] == ',') i = skip(s, i + 1)
                    if (i >= n || s[i] != '"') return o
                    key.setLength(0)
                    i = readString(s, i + 1, key)
                    if (i < 0) return o
                    i = skip(s, i)
                    if (i >= n || s[i] != ':') return o
                    i = skip(s, i + 1)
                    if (i >= n) return o
                    if (s[i] == '"') {
                        value.setLength(0)
                        i = readString(s, i + 1, value)
                        o.put(key.toString(), value.toString())
                        if (i < 0) return o
                        continue
                    }
                    // a number, true, an array, an object: up to the , or } that ends it outside a string
                    var depth = 0
                    var j = i
                    var inString = false
                    while (j < n) {
                        val ch = s[j]
                        if (inString) {
                            if (ch == '\\') j++
                            else if (ch == '"') inString = false
                        } else if (ch == '"') inString = true
                        else if (ch == '{' || ch == '[') depth++
                        else if (ch == '}' || ch == ']') { if (depth-- == 0) break }
                        else if (ch == ',' && depth == 0) break
                        j++
                    }
                    if (j >= n) return o
                    o.put(key.toString(), JSONTokener(s.subSequence(i, j).toString()).nextValue())
                    i = j
                    if (s[i] == '}') return o
                }
            } catch (e: Exception) {
                return o
            }
        }

        private fun skip(s: CharSequence, start: Int): Int {
            var i = start
            while (i < s.length && Character.isWhitespace(s[i])) i++
            return i
        }

        /** Reads a JSON string's inside from i into out: the index after its closing ", or -1 if cut off. */
        private fun readString(s: CharSequence, start: Int, out: StringBuilder): Int {
            val n = s.length
            var i = start
            while (i < n) {
                val ch = s[i++]
                if (ch == '"') return i
                if (ch != '\\') { out.append(ch); continue }
                if (i >= n) return -1
                when (val e = s[i++]) {
                    'n' -> out.append('\n')
                    't' -> out.append('\t')
                    'r' -> out.append('\r')
                    'b' -> out.append('\b')
                    'f' -> out.append('\u000C')
                    'u' -> {
                        if (i + 4 > n) return -1
                        out.append(s.subSequence(i, i + 4).toString().toInt(16).toChar())
                        i += 4
                    }
                    else -> out.append(e)
                }
            }
            return -1
        }
    }
}
