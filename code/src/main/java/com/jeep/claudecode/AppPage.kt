package com.jeep.claudecode

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import java.io.File
import org.json.JSONArray

/** Settings → 存储, 后台 and 关于: what is taken up, keeping the app running, the version and the logs. */
class AppPage(app: MainActivity) : Part(app) {
    /** Sizes in the userland, measured when the page opens: conversations, work, temporary files, all. */
    private var storage: LongArray? = null
    private var measured = 0 // how many times it was measured: only the last answers
    /** How keeping alive was found (KeepAlive's states), UNKNOWN until checked in this run. */
    @JvmField var keepAlive = UNKNOWN
    private var keepChecking = false

    /** Settings is on this page: it is drawn again with what changed. */
    private fun refresh(page: String) {
        if (page == app.settings.settingsAt) app.settings.fillSettings(app.settings.launchCache)
    }

    // ---- 存储 ----

    fun measureStorage() {
        storage = null
        val gen = ++measured
        // A thread of its own: walking the whole userland takes seconds, and work reads the files being attached.
        val walk = Thread({
            val s = longArrayOf(
                Userland.size(userland.file(Userland.HOME + "/.claude/projects")),
                Userland.size(userland.file(Userland.HOME + "/work")),
                Userland.size(userland.file("/tmp")) + Userland.size(userland.file(Userland.HOME + "/.cache")),
                Userland.size(userland.file("/")),
            )
            ui.post {
                if (gen != measured) return@post // measured again meanwhile: that one answers
                storage = s
                refresh("storage")
            }
        }, "measure-storage")
        walk.isDaemon = true
        walk.start()
    }

    fun fillStorage(box: LinearLayout) {
        val s = storage
        fun size(n: Long) = if (s == null) "…" else Ui.size(n)
        val sizes = app.settings.card(box)
        sheets.option(sizes, R.drawable.ic_compose, "对话记录", size(s?.get(0) ?: 0), false, null)
        sheets.option(sizes, R.drawable.ic_folder, "工作目录", size(s?.get(1) ?: 0), false, null)
        sheets.option(sizes, R.drawable.ic_file, "临时文件", size(s?.get(2) ?: 0), false, null)
        sheets.option(sizes, R.drawable.ic_terminal, "运行环境", size(if (s == null) 0 else maxOf(0, s[3] - s[0] - s[1] - s[2])), false, null)
        val clean = app.settings.card(box)
        Ui.margins(clean, app, 0, 12, 0, 0)
        sheets.option(clean, R.drawable.ic_trash, "清理临时文件", null, false) {
            confirm("清理临时文件", "删除 /tmp 和 ~/.cache 里的文件", false) {
                clear(userland.file("/tmp"))
                clear(userland.file(Userland.HOME + "/.cache"))
            }
        }
        sheets.option(clean, R.drawable.ic_trash, "清空对话记录", null, true) {
            confirm("清空对话记录", "所有对话都会被删除，不能恢复", true) {
                clear(userland.file(Userland.HOME + "/.claude/projects"))
                ui.post {
                    app.drawer.keepSessions(JSONArray())
                    app.drawer.loadSessions()
                }
            }
        }
    }

    private fun clear(dir: File) {
        dir.listFiles()?.forEach { Userland.delete(it) }
    }

    /** Asks before doing something that cannot be undone; does it off the main thread. */
    private fun confirm(title: String, what: String, danger: Boolean, action: () -> Unit) {
        val sheet: Dialog = sheets.sheet(title, what)
        val cancel = sheets.button("取消", false) { sheet.dismiss() }
        val ok = sheets.button("确定", true) {
            sheet.dismiss()
            work.execute {
                action()
                ui.post { toast("完成"); measureStorage() }
            }
        }
        if (danger) ok.background = Ui.press(app, Ui.round(col(R.color.danger), dp(10f).toFloat()), dp(10f).toFloat())
        sheets.actions(sheet, cancel, ok)
        sheet.show()
    }

    // ---- 后台 ----

    /** Checks keeping alive and sets what is not set, through rish; asked: by a tap, which is told how it went. */
    fun keepAlive(asked: Boolean) {
        if (keepChecking) return
        keepChecking = true
        refresh("keep")
        val pkg = app.packageName
        // A thread of its own: rish may take long, and work reads the files being attached.
        Thread({
            val state = KeepAlive.ensure(userland, pkg)
            ui.post {
                keepChecking = false
                keepAlive = state
                if (state == KeepAlive.SET) toast("已开启后台保活")
                else if (asked) toast(when (state) {
                    KeepAlive.ON -> "后台保活已经开着"
                    KeepAlive.OFF -> "没能开启后台保活"
                    else -> "rish 没有运行：请确认 Shizuku 在运行并已授权本应用"
                })
                refresh("keep")
            }
        }, "keep-alive").start()
    }

    fun fillKeep(box: LinearLayout) {
        val storage = app.hasStorage()
        val pm = app.getSystemService(Context.POWER_SERVICE) as PowerManager
        val battery = pm.isIgnoringBatteryOptimizations(app.packageName)
        // Android 12+ kills an app's child processes beyond a limit, or busy ones in the background:
        // the app checks and sets that itself (KeepAlive); a tap checks again.
        val state = when {
            keepChecking -> "检查中…"
            keepAlive == UNKNOWN -> "未检查"
            keepAlive == KeepAlive.ON || keepAlive == KeepAlive.SET -> "已开启"
            keepAlive == KeepAlive.OFF -> "没能开启，点此重试"
            else -> "需要 Shizuku，点此重试"
        }
        val keep = app.settings.card(box)
        if (!storage) sheets.option(keep, R.drawable.ic_folder, "允许访问存储", null, false) { app.requestStorage() }
        if (!battery) sheets.option(keep, R.drawable.ic_check, "关闭电池优化", null, false) {
            open(Intent(AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + app.packageName)), "系统不支持这个设置")
        }
        sheets.option(keep, R.drawable.ic_gear, "应用信息", null, false) {
            open(Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + app.packageName)), "打不开应用信息")
        }
        sheets.option(keep, R.drawable.ic_check, "后台保活", state, false) { keepAlive(true) }
    }

    private fun open(intent: Intent, failed: String) {
        try {
            app.startActivity(intent)
        } catch (e: RuntimeException) {
            toast(failed)
        }
    }

    // ---- 关于 ----

    private fun appVersion(): String = try {
        app.packageManager.getPackageInfo(app.packageName, 0).versionName ?: ""
    } catch (e: Exception) {
        ""
    }

    fun fillAbout(box: LinearLayout) {
        val about = app.settings.card(box)
        sheets.option(about, 0, "应用", "v" + appVersion(), false, null)
        sheets.option(about, 0, "Claude Code", claude.claudeVersion, false, null)
        val tools = app.settings.card(box)
        Ui.margins(tools, app, 0, 12, 0, 0)
        sheets.option(tools, R.drawable.ic_list, "日志", null, false) { logSheet() }
        sheets.option(tools, R.drawable.ic_terminal, "终端", null, false) { app.openTerminal() }
    }

    /** The logs, as text to select and copy. */
    fun logSheet() {
        val bridgeLog = Userland.tail(userland.file(Userland.HOME + "/.ccapp/bridge.log"), 16_000)
        val runLog = Userland.tail(userland.log, 8_000)
        val text = "== bridge.log ==\n" + bridgeLog.ifEmpty { "（空）\n" } + "\n== 启动 ==\n" + runLog.ifEmpty { "（空）\n" }
        val sheet = sheets.sheet("日志", null)
        val t = Ui.text(app, text, 11.5f, R.color.text)
        t.typeface = Ui.MONO
        t.setTextIsSelectable(true)
        sheets.content(sheet).addView(t, Ui.fill())
        val cp = sheets.button("复制全部", false) { app.copy(text) }
        sheets.content(sheet).addView(cp)
        Ui.margins(cp, app, 0, 12, 0, 0)
        sheet.show()
        val scroll = sheets.content(sheet).parent as ScrollView
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    companion object {
        const val UNKNOWN = -1
    }
}
