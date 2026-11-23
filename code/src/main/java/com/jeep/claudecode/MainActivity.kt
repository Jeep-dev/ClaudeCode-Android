package com.jeep.claudecode

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ContextThemeWrapper
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import org.json.JSONArray
import org.json.JSONObject

/** Claude Code front end: native interface for Claude Code running in this app's own userland behind the bridge. */
class MainActivity : Activity(), Bridge.Events {
    // What the parts use: the bridge to Claude Code, the userland it runs in, and the app's own threads and settings.
    lateinit var bridge: Bridge
    lateinit var userland: Userland
    lateinit var prefs: SharedPreferences
    @JvmField val ui = Handler(Looper.getMainLooper())
    @JvmField val work: ExecutorService = Executors.newSingleThreadExecutor()
    lateinit var md: Md

    // The parts the screen is made of: each is built for this activity and reaches the others through it (see Part).
    lateinit var claude: Claude
    lateinit var sheets: Sheets
    lateinit var link: Link
    lateinit var screen: ChatScreen
    lateinit var box: Box
    lateinit var attachments: Attachments
    lateinit var slash: SlashCommands
    lateinit var sender: Sender
    lateinit var finder: Finder
    lateinit var messages: MessageActions
    lateinit var drawer: Drawer
    lateinit var settings: Settings
    lateinit var look: LookPage
    lateinit var claudePage: ClaudePage
    lateinit var ext: ExtPage
    lateinit var skills: Skills
    lateinit var mcp: McpServers
    lateinit var appPage: AppPage
    lateinit var pickers: Pickers
    lateinit var voice: Voice
    lateinit var usage: UsageSheet
    lateinit var files: FilesPage

    /** Claude and Sheets are made: the other parts may be (Part checks it). */
    val hasClaude: Boolean get() = ::claude.isInitialized
    val hasSheets: Boolean get() = ::sheets.isInitialized

    // state: the conversations, by key and by the session each comes to have
    @JvmField val chats = HashMap<String, Chat>()
    @JvmField val bySession = HashMap<String, String>()
    private val dropped = HashSet<String>() // keys and sessions of conversations deleted: events still on the way are let go
    /** The conversation shown; none only while the screen is built again (restyle). */
    private var shown: Chat? = null
    val current: Chat get() = shown!!
    val hasCurrent: Boolean get() = shown != null

    // views
    lateinit var frame: DrawerFrame
    private var renderQueued = false
    /** The system's light or dark when the screen was built, to follow it when it changes. */
    private var night = 0

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        prefs = getSharedPreferences("app", MODE_PRIVATE)
        bridge = Bridge(this, this)
        Images.saved = bridge::image
        userland = Userland(this)
        // The parts of the screen, the first two being what the others reach by name (Part, which checks it).
        claude = Claude(this)
        sheets = Sheets(this)
        link = Link(this)
        screen = ChatScreen(this)
        box = Box(this)
        attachments = Attachments(this)
        slash = SlashCommands(this)
        sender = Sender(this)
        finder = Finder(this)
        messages = MessageActions(this)
        drawer = Drawer(this)
        settings = Settings(this)
        look = LookPage(this)
        claudePage = ClaudePage(this)
        ext = ExtPage(this)
        skills = Skills(this)
        mcp = McpServers(this)
        appPage = AppPage(this)
        pickers = Pickers(this)
        voice = Voice(this)
        usage = UsageSheet(this)
        files = FilesPage(this)
        md = newMd()
        // The keyboard opens when the box is tapped, never by itself (coming back from the background).
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
            WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        night = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        build()
        switchTo(newChat())
        drawer.renderSessions()
        work.execute {
            val kept = drawer.keptSessions()
            ui.post {
                if (drawer.sessionsRead || kept.length() == 0) return@post // the bridge's own list came first
                drawer.sessions = kept
                drawer.renderSessions()
            }
        }
        link.connect(false)
        ui.postDelayed(ticker, 120)
    }

    private fun newMd() = Md(talkContext(), ::copy, ::openUrl, ::userFile)

    private val ticker = object : Runnable {
        override fun run() {
            screen.adapter.tick()
            drawer.updateLink()
            ui.postDelayed(this, 120)
        }
    }

    override fun onResume() {
        super.onResume()
        link.resume()
        voice.warm() // the mic listens at once when pressed
    }

    override fun onPause() {
        super.onPause()
        link.pause()
        voice.pause()
    }

    override fun onDestroy() {
        ui.removeCallbacks(ticker)
        bridge.close()
        work.shutdownNow()
        super.onDestroy()
    }

    @Deprecated("Back is handled here as before (the app's own minimum API has no other way)")
    override fun onBackPressed() {
        if (box.slashScroll.visibility == View.VISIBLE) {
            box.hideMenu()
            return
        }
        if (frame.isOpen) {
            frame.close()
            return
        }
        moveTaskToBack(true)
    }

    /** The screen: the conversation, and the drawer beside it. Built again when the colours or text size change. */
    private fun build() {
        drawer.reset()
        val main = screen.build()
        frame = DrawerFrame(this, main, drawer.buildDrawer())
        screen.placeImmersive() // the drawer too, now that it is there
        frame.setListener {
            box.hideKeyboard()
            drawer.loadSessions()
        }
        setContentView(frame)
    }

    fun newChat(): Chat {
        val chat = Chat(Chat.newKey())
        chat.cwd = claude.cwd.ifEmpty { claude.defaultCwd }
        chats[chat.key] = chat
        return chat
    }

    fun switchTo(chat: Chat) {
        if (finder.finding()) finder.findEnd()
        voice.cancel()
        shown?.draft = box.input.text.toString()
        shown = chat
        box.input.setText(chat.draft)
        box.input.setSelection(box.input.length())
        screen.stick = true
        screen.adapter.show(chat, true)
        renderNow()
        drawer.renderSessions()
    }

    /** Coalesces updates to one per frame. */
    fun render() {
        if (renderQueued) return
        renderQueued = true
        screen.list.postOnAnimation {
            renderQueued = false
            renderNow()
        }
    }

    fun renderNow() {
        traced("cc renderNow") {
            screen.adapter.show(current, false)
            val empty = current.messages.isEmpty() && !current.running
            screen.emptyView.visibility = if (empty) View.VISIBLE else View.GONE
            if (empty) screen.fillEmpty()
            if (screen.stick) screen.scrollToEnd(false)
            screen.updateChrome()
            drawer.updateLink()
        }
    }

    /** A path as Claude Code means it (absolute, ~/…, or from this conversation's folder) as a file here. */
    private fun userFile(path: String): File {
        var p = Uri.decode(path)
        if (p.startsWith("~/")) p = Userland.HOME + p.substring(1)
        else if (!p.startsWith("/")) p = current.cwd.ifEmpty { claude.defaultCwd } + "/" + p
        return userland.file(p)
    }

    /** Reads a saved conversation once; a failed read is tried again on the next link or tap. */
    fun loadHistory(target: Chat) {
        val id = target.sessionId
        if (target.loaded || id == null || !link.linked) return
        target.loaded = true
        bridge.get("/history?id=" + Uri.encode(id)) { status, data ->
            if (status != 200) {
                target.loaded = false
                toast(when (status) {
                    -2 -> "读取对话失败：桥接 30 秒没有回应"
                    -1, Bridge.REFUSED -> "读取对话失败：连不上桥接"
                    else -> "读取对话失败：" + data.optString("error", "HTTP $status")
                })
                return@get
            }
            target.load(data)
            if (data.optString("cwd").isNotEmpty()) target.cwd = data.optString("cwd")
            if (shown === target) {
                screen.adapter.show(target, true)
                screen.stick = true
                renderNow()
            }
        }
    }

    override fun events(batch: List<JSONObject>) {
        traced("cc events") {
            for (ev in batch) onEvent(ev)
            for (ch in ArrayList(chats.values)) sender.sendQueued(ch)
            render()
            // Events come many times a second while a reply streams: the list is built again only when
            // what it shows of the live conversations changed.
            if (frame.isOpen && drawer.drawerState() != drawer.drawnState) drawer.renderSessions()
        }
    }

    /** A conversation deleted: it and its session are forgotten, and what its claude still says is let go (see chatFor). */
    fun drop(key: String, sessionId: String) {
        chats.remove(key)
        bySession.remove(sessionId)
        bySession.values.remove(key)
        dropped.add(key)
        dropped.add(sessionId)
    }

    /** The conversation an event belongs to, made when it is not known yet; null for one deleted. */
    private fun chatFor(ev: JSONObject): Chat? {
        val key = ev.optString("chat")
        chats[key]?.let { return it }
        if (dropped.contains(key)) return null
        val data = ev.optJSONObject("data")
        val sid = if (data != null && data.has("session_id")) data.optString("session_id") else if (key.startsWith("n-")) null else key
        if (sid != null && dropped.contains(sid)) return null
        if (sid != null) bySession[sid]?.let { k -> chats[k]?.let { return it } }
        // Started before this app was: its history loads from the transcript when opened.
        val chat = Chat(key)
        chat.sessionId = sid
        chat.cwd = claude.defaultCwd
        chat.loaded = false
        chats[key] = chat
        if (sid != null) bySession[sid] = key
        return chat
    }

    private fun onEvent(ev: JSONObject) {
        if (ev.optString("kind") == "gap") {
            gap()
            return
        }
        val chat = chatFor(ev) ?: return
        chat.lastEvent = System.currentTimeMillis()
        val kind = ev.optString("kind")
        val data = ev.optJSONObject("data")
        when {
            kind == "cc" && data != null -> {
                if (data.optString("type") == "system" && data.optString("subtype") == "init") keepWhatItOffers(data)
                if (chat.apply(data)) {
                    drawer.loadSessions()
                    loadHistory(chat) // one not read yet (or since a gap) is read now that the turn is whole
                }
                chat.sessionId?.let { bySession[it] = chat.key }
            }
            kind == "title" -> if (data != null && data.optString("title").isNotEmpty()) {
                chat.title = data.optString("title") // a short name made after the first turn
                screen.updateChrome()
                drawer.loadSessions()
            }
            kind == "state" -> if (data != null && data.optBoolean("running") && !chat.running) {
                chat.running = true
                chat.startedAt = System.currentTimeMillis()
            }
            kind == "exit" -> exited(chat, data)
        }
    }

    /** What a new Claude Code run says it has (its init message): the tools, servers, skills, agents and commands, kept for the screens that list them. */
    private fun keepWhatItOffers(data: JSONObject) {
        val e = prefs.edit()
        data.optJSONArray("tools")?.let { e.putString("tools", it.toString()) }
        data.optJSONArray("mcp_servers")?.let { e.putString("mcpServers", it.toString()) }
        data.optJSONArray("skills")?.let { e.putString("skills", it.toString()) }
        data.optJSONArray("agents")?.let { e.putString("agents", it.toString()) }
        data.optJSONArray("slash_commands")?.let { e.putString("slashCommands", it.toString()) }
        e.apply()
        val cmds = data.optJSONArray("slash_commands")
        if (cmds != null && cmds.length() > 0) {
            claude.commands.clear()
            for (i in 0 until cmds.length()) if (!cmds.optString(i).startsWith("_")) claude.commands.add(cmds.optString(i))
            prefs.edit().putString("commands", JSONArray(claude.commands).toString()).apply()
        }
    }

    /** Claude Code's process ended; when it did so in the middle of a turn, the turn ends with an error saying how. */
    private fun exited(chat: Chat, data: JSONObject?) {
        if (data != null && data.optBoolean("running")) {
            val m = chat.reply(true)!!
            val b = Chat.Block(Chat.Block.ERROR)
            b.title = "Claude Code 意外退出"
            val code = data.optInt("code")
            // Popen gives a negative code for a signal; 9 is the system killing it.
            val how = if (code == -9) "被系统强制结束（SIGKILL）" else if (code < 0) "被信号 " + -code + " 结束" else "退出码 $code"
            val err = StringBuilder()
            for (line in data.optString("stderr").split("\n")) {
                // Claude Code notes models it does not know (DeepSeek's) on every run: not the cause.
                if (line.trim().isEmpty() || line.contains("unrecognized_model")) continue
                err.append('\n').append(line)
            }
            b.text = how + err
            m.blocks.add(b)
            m.done = true
            m.touch()
            chat.stopped = true // Claude Code died: what is queued waits to be sent by hand
        }
        chat.unhand() // what it held waits here again
        chat.running = false
        chat.stopping = false // no result is coming to end the stop asked for: the next turn is its own
    }

    override fun link(up: Boolean) = link.changed(up)

    fun hasStorage(): Boolean = checkSelfPermission(STORAGE) == PackageManager.PERMISSION_GRANTED

    /** What asked for storage last (the attach sheet), told when the answer comes. */
    @JvmField var storageAsked: Runnable? = null

    fun requestStorage() {
        requestPermissions(arrayOf(STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE), ASK_STORAGE)
    }

    override fun onRequestPermissionsResult(request: Int, permissions: Array<String>, results: IntArray) {
        if (request == Voice.ASK_MIC) {
            voice.granted(results.isNotEmpty() && results[0] == PackageManager.PERMISSION_GRANTED)
            return
        }
        if (request != ASK_STORAGE) return
        settings.refreshSettings()
        storageAsked?.run()
        storageAsked = null
        if (!hasStorage()) {
            toast("没有存储权限：Claude Code 看不到手机里的文件")
            return
        }
        // /sdcard is shared into the userland when the bridge starts.
        if (!link.installing) link.connect(true)
    }

    /**
     * Events were dropped while the app was away (the bridge keeps only the last ones): the
     * conversations are read again from their transcripts, a running one when its turn ends.
     */
    private fun gap() {
        for (ch in chats.values) if (ch.sessionId != null) ch.loaded = false
        if (!current.running) loadHistory(current)
    }

    @Deprecated("The app's own pickers answer here (a plain Activity)")
    override fun onActivityResult(request: Int, result: Int, data: Intent?) {
        val picked = data?.data
        if (request == PICK_FONT && result == RESULT_OK && picked != null) {
            look.addFont(picked)
            return
        }
        if (request == VOICE) {
            voice.picked(result, data)
            return
        }
        if (request == CAMERA) {
            attachments.shot(result == RESULT_OK)
            return
        }
        if (request != PICK_FILES || result != RESULT_OK || data == null) return
        attachments.picked(data)
    }

    /** The colours or text size changed: the screen is built again around the same conversations. */
    fun restyle() {
        traced("cc restyle") {
            val at = settings.settingsAt
            val page = settings.settingsPage
            val open = page != null
            val scrolled = if (open) settings.settingsScroll().scrollY else 0
            if (page != null) {
                // Its dismissal is told later: Settings is let go of now, to be opened again below.
                page.dismiss()
                settings.settingsPage = null
            }
            // The drawer (Settings is opened from it) and a /find are as they were, in the new screen.
            val drawerOpen = frame.isOpen
            val finding = if (finder.finding()) finder.findQuery else null
            val foundAt = finder.findAt
            val chat = current
            chat.draft = box.input.text.toString()
            shown = null
            md = newMd()
            build()
            if (drawerOpen) frame.openNow()
            switchTo(chat)
            if (finding != null) screen.list.post {
                finder.find(finding)
                if (finder.finding()) finder.findGo(foundAt)
            }
            drawer.renderSessions()
            drawer.updateLink()
            if (open) {
                settings.settingsAt = at
                settings.settingsInstant = true
                settings.settingsSheet()
                settings.settingsInstant = false
                // Where it was, not back at its top.
                val again = settings.settingsScroll()
                again.post { again.scrollTo(0, scrolled) }
            }
        }
    }

    /** The context conversation text is made in: the chosen text size over the system's. */
    fun talkContext(): Context {
        val k = Fonts.scale(this)
        if (k == 1f) return this
        val o = Configuration()
        o.fontScale = resources.configuration.fontScale * k
        val w = ContextThemeWrapper(this, theme)
        w.applyOverrideConfiguration(o)
        return w
    }

    override fun onConfigurationChanged(config: Configuration) {
        super.onConfigurationChanged(config)
        val now = config.uiMode and Configuration.UI_MODE_NIGHT_MASK
        if (now == night) return
        night = now
        if (Themes.mode(this) == 0) restyle()
    }

    fun openTerminal() {
        if (!userland.ready()) {
            toast("运行环境还没准备好")
            return
        }
        startActivity(Intent(this, TerminalActivity::class.java))
        @Suppress("DEPRECATION")
        overridePendingTransition(R.anim.page_in, R.anim.page_under_out)
    }

    fun copy(text: String) {
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("Claude Code", text))
        toast("已复制")
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: RuntimeException) {
            toast(url)
        }
    }

    fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    companion object {
        /** What is asked of another app (a file to attach, a font file) and of the system (the storage permission). */
        const val PICK_FILES = 1
        const val PICK_FONT = 2
        const val VOICE = 3
        const val CAMERA = 4
        private const val ASK_STORAGE = 2
        private const val STORAGE = Manifest.permission.WRITE_EXTERNAL_STORAGE
    }
}
