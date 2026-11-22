package com.jeep.claudecode

import android.view.View

/** The link to the bridge, and what comes before it: unpacking the userland the bridge runs in. */
class Link(app: MainActivity) : Part(app) {
    @JvmField var linked = false
    @JvmField var everLinked = false
    @JvmField var connecting = false
    @JvmField var resumed = false
    /** When the bridge in use started; another value means a new bridge and lost turns. */
    private var bridgeStarted = 0.0
    /** Why the bridge cannot be reached ("install" while the userland unpacks), or null. */
    @JvmField var setup: String? = null
    @JvmField var installing = false
    /** How far preparing the userland is (downloading, unpacking). */
    @JvmField var installStatus = ""
    @JvmField var installError = ""
    private var connectAttempt = 0

    /** The app came to the front: the link is made if it is not there, or the open conversation is looked at again. */
    fun resume() {
        resumed = true
        if (!linked && !connecting && setup == null) connect(false)
        else if (linked) app.sender.opened(app.current)
    }

    fun pause() {
        resumed = false
    }

    /** The link stayed down: start the bridge again if it is gone (the service also restarts one that died). */
    private val recover = Runnable { if (!linked && !connecting && resumed) connect(false) }

    /** The bridge says its link went up or down. */
    fun changed(up: Boolean) {
        if (up == linked) return
        if (up) {
            afterLink()
        } else {
            linked = false
            app.drawer.updateLink()
            app.render()
            ui.removeCallbacks(recover)
            ui.postDelayed(recover, 2000)
        }
    }

    /** Connects to the bridge; a restart is never refused, and only the latest attempt counts. */
    fun connect(restart: Boolean) {
        if (connecting && !restart) return
        connecting = true
        val attempt = ++connectAttempt
        app.drawer.updateLink()
        bridge.connect(restart) { result ->
            if (attempt != connectAttempt) return@connect
            connecting = false
            when (result) {
                "ok" -> {
                    setup = null
                    afterLink()
                }
                "install" -> install()
                else -> {
                    setup = result
                    linked = false
                    app.renderNow()
                }
            }
        }
    }

    /** Unpacks the userland (first launch, or an update that brings a new one), then connects. */
    private fun install() {
        if (installing) return
        installing = true
        setup = "install"
        installStatus = ""
        linked = false
        app.renderNow()
        work.execute {
            var error: String? = null
            try {
                userland.install { p ->
                    ui.post {
                        installStatus = p
                        if (setup == "install" && app.screen.emptyView.visibility == View.VISIBLE) app.screen.fillEmpty()
                    }
                }
            } catch (e: Exception) {
                error = e.message ?: e.toString()
            }
            val err = error
            ui.post {
                installing = false
                if (err != null) {
                    setup = "installFailed"
                    installError = err
                    app.renderNow()
                    return@post
                }
                setup = null
                connect(false)
                if (!prefs.getBoolean("askedStorage", false)) {
                    prefs.edit().putBoolean("askedStorage", true).apply()
                    app.requestStorage()
                }
            }
        }
    }

    private fun afterLink() {
        linked = true
        everLinked = true
        setup = null
        bridge.get("/health") { status, data ->
            if (status != 200) return@get
            val started = data.optDouble("started", 0.0)
            if (bridgeStarted != 0.0 && started != bridgeStarted) {
                // A new bridge: the old one's turns ended with it.
                for (ch in app.chats.values) {
                    if (ch.running) app.sender.fail(ch, "后台重启了", "这一轮被中断，发消息即可接着这个对话继续。")
                    ch.unhand() // what the old one held waits here again
                }
            }
            bridgeStarted = started
            claude.home = data.optString("home")
            claude.defaultCwd = data.optString("defaultCwd")
            for (ch in app.chats.values) if (ch.cwd.isEmpty()) ch.cwd = claude.cwd.ifEmpty { claude.defaultCwd }
            app.renderNow()
        }
        bridge.get("/config") { status, data ->
            if (status != 200) return@get
            claude.applyConfig(data)
            if (app.current.messages.isEmpty()) app.renderNow()
        }
        app.renderNow()
        app.drawer.loadSessions()
        app.loadHistory(app.current)
        if (app.appPage.keepAlive == AppPage.UNKNOWN) app.appPage.keepAlive(false) // once a run: the userland is there now
    }
}
