package com.jeep.claudecode

import java.io.File
import java.io.IOException
import org.json.JSONException
import org.json.JSONObject

/** Settings → 扩展: plugins, subagents and commands (Markdown files), the tools, and hooks. */
class ExtPage(app: MainActivity) : Part(app) {
    /** The 扩展 page's counts (skills, MCP servers, agents, commands, hooks), read off the main thread; null until read. */
    private var extCounts: IntArray? = null

    fun fillExt(box: android.widget.LinearLayout, launch: JSONObject?) {
        val n = extCounts
        extCounts = null // read again next time
        if (n == null) {
            work.execute {
                val hooks = claude.readJson(claude.settingsFile()).optJSONObject("hooks")
                val read = intArrayOf(app.skills.skillList().size, app.mcp.mcpList().size, fileItems(AGENTS).size,
                    fileItems(COMMANDS).size, hooks?.length() ?: 0)
                ui.post {
                    if (app.settings.settingsAt != "ext" || app.settings.settingsPage == null) return@post
                    extCounts = read
                    app.settings.fillSettings(if (app.link.linked) app.settings.launchCache else null)
                }
            }
        }
        val ext = app.settings.card(box)
        sheets.option(ext, R.drawable.ic_spark, "技能", count(n, 0), false, app.skills::skillsSheet)
        sheets.option(ext, R.drawable.ic_file_plus, "插件", null, false, ::pluginsSheet)
        sheets.option(ext, R.drawable.ic_globe, "MCP", count(n, 1), false, app.mcp::mcpSheet)
        sheets.option(ext, R.drawable.ic_bot, "子代理", count(n, 2), false) { itemsSheet(AGENTS) }
        sheets.option(ext, R.drawable.ic_edit_lines, "命令", count(n, 3), false) { itemsSheet(COMMANDS) }
        if (launch != null) {
            val off = disallowed(launch).size
            sheets.option(ext, R.drawable.ic_tool, "工具", if (off == 0) null else "关闭 $off", false) { toolsSheet(launch) }
        }
        sheets.option(ext, R.drawable.ic_refresh, "Hooks", count(n, 4), false, ::hooksSheet)
    }

    // ---- 插件: Claude Code's own `claude plugin` ----
    private fun pluginsSheet() {
        if (!app.link.linked) {
            toast("Claude Code 还没启动好")
            return
        }
        val sheet = sheets.sheet("插件", null)
        val card = sheets.card(sheet)
        sheets.empty(card, "…")
        val more = sheets.card(sheet)
        Ui.margins(more, app, 0, 10, 0, 0)
        sheets.option(more, R.drawable.ic_plus, "安装", null, false) {
            sheet.dismiss()
            sheets.textSheet("安装插件", "", "名称 或 名称@市场", false, { t, done -> pluginDo("install", t.trim(), done) }, ::pluginsSheet)
        }
        sheets.option(more, R.drawable.ic_globe, "市场", null, false) {
            sheet.dismiss()
            marketsSheet()
        }
        sheet.show()
        bridge.get("/plugins") { status, data ->
            card.removeAllViews()
            val list = if (status == 200) data.optJSONArray("plugins") else null
            if (list != null) for (i in 0 until list.length()) {
                val p = list.optJSONObject(i) ?: continue
                val id = p.optString("id")
                val name = if (id.contains("@")) id.substring(0, id.indexOf('@')) else id
                val on = p.optBoolean("enabled", true)
                sheets.option(card, R.drawable.ic_file_plus, name, if (on) p.optString("version") else "已停用", false) {
                    sheet.dismiss()
                    pluginActions(id, on)
                }
            }
            if (list == null || list.length() == 0) sheets.empty(card, if (status == 200) "还没有" else "读取失败")
        }
    }

    private fun pluginActions(id: String, on: Boolean) {
        val sheet = sheets.sheet(id, null)
        val card = sheets.card(sheet)
        sheets.option(card, if (on) R.drawable.ic_stop_circle else R.drawable.ic_check, if (on) "停用" else "启用", null, false) {
            sheet.dismiss()
            pluginDo(if (on) "disable" else "enable", id, null)
        }
        sheets.option(card, R.drawable.ic_refresh, "更新", null, false) {
            sheet.dismiss()
            pluginDo("update", id, null)
        }
        sheets.option(card, R.drawable.ic_trash, "卸载", null, true) {
            sheet.dismiss()
            pluginDo("uninstall", id, null)
        }
        sheet.show()
    }

    /** One `claude plugin` change; its own message on failure. */
    private fun pluginDo(action: String, name: String, done: Runnable?) {
        if (name.isEmpty()) return
        toast("正在处理…")
        bridge.post("/plugin", obj("action", action, "name", name)) { status, data ->
            if (status != 200) {
                sheets.textSheetMessage("没有成功", data.optString("error"))
                return@post
            }
            toast("完成")
            done?.run()
            app.settings.refreshSettings()
            if (action.startsWith("market")) marketsSheet() else pluginsSheet()
        }
    }

    private fun marketsSheet() {
        val sheet = sheets.sheet("市场", null)
        val card = sheets.card(sheet)
        sheets.empty(card, "…")
        val more = sheets.card(sheet)
        Ui.margins(more, app, 0, 10, 0, 0)
        sheets.option(more, R.drawable.ic_plus, "添加", null, false) {
            sheet.dismiss()
            sheets.textSheet("添加市场", "", "GitHub 仓库（owner/repo）或网址", false, { t, done -> pluginDo("market-add", t.trim(), done) }, ::marketsSheet)
        }
        sheet.show()
        bridge.get("/plugins") { status, data ->
            card.removeAllViews()
            val list = if (status == 200) data.optJSONArray("marketplaces") else null
            if (list != null) for (i in 0 until list.length()) {
                val name = list.optJSONObject(i)?.optString("name") ?: ""
                if (name.isEmpty()) continue
                sheets.option(card, R.drawable.ic_globe, name, null, false) {
                    sheet.dismiss()
                    val ask = sheets.sheet(name, null)
                    val c2 = sheets.card(ask)
                    sheets.option(c2, R.drawable.ic_trash, "删除", null, true) {
                        ask.dismiss()
                        pluginDo("market-remove", name, null)
                    }
                    ask.show()
                }
            }
            if (card.childCount == 0) sheets.empty(card, if (status == 200) "还没有" else "读取失败")
        }
    }

    // ---- 子代理 and 命令: Markdown files Claude Code reads, and those it reported ----

    /** A file extension: its name, its file when the user's or this folder's (editable), else where it comes from. */
    private class Item(val name: String, val file: File?, val from: String?)

    private fun itemsDir(kind: String): File = userland.file(Userland.HOME + "/.claude/" + kind)

    /**
     * The user's and this folder's files (commands in subfolders are dir:name), then those Claude
     * Code reported when a conversation last started: for subagents its own and plugins'; for
     * commands only plugins' (its own commands are not extensions; skills have their own page).
     */
    private fun fileItems(kind: String): List<Item> {
        val out = ArrayList<Item>()
        val seen = HashSet<String>()
        val here = userland.file(claude.mcpFolder() + "/.claude/" + kind)
        addItems(out, seen, itemsDir(kind), itemsDir(kind), null)
        if (here != itemsDir(kind)) addItems(out, seen, here, here, "本目录")
        val reported = savedArray(if (kind == AGENTS) "agents" else "slashCommands")
        val skills = HashSet<String>()
        val sk = savedArray("skills")
        for (i in 0 until sk.length()) skills.add(sk.optString(i))
        for (i in 0 until reported.length()) {
            val n = reported.optString(i)
            val colon = n.indexOf(':')
            if (n.isEmpty() || n in seen || n in skills) continue
            if (kind == COMMANDS && colon <= 0) continue
            seen.add(n)
            out.add(if (colon > 0) Item(n.substring(colon + 1), null, n.substring(0, colon)) else Item(n, null, "内置"))
        }
        return out
    }

    private fun itemsSheet(kind: String) {
        val agents = kind == AGENTS
        val sheet = sheets.sheet(if (agents) "子代理" else "命令", null)
        val card = sheets.card(sheet)
        val items = fileItems(kind)
        for (it in items) {
            val file = it.file
            sheets.option(card, if (agents) R.drawable.ic_bot else R.drawable.ic_edit_lines, if (agents) it.name else "/" + it.name, it.from, false,
                if (file == null) null else Runnable { sheet.dismiss(); itemActions(kind, file) })
        }
        if (items.isEmpty()) sheets.empty(card, "还没有")
        val add = sheets.card(sheet)
        Ui.margins(add, app, 0, 10, 0, 0)
        sheets.option(add, R.drawable.ic_plus, "新建", null, false) {
            sheet.dismiss()
            sheets.textSheet(if (agents) "新建子代理" else "新建命令", "", "名称：小写字母、数字和 -", false, { t, done ->
                val n = t.trim()
                if (!n.matches(Regex("[a-z0-9][a-z0-9-]*"))) {
                    toast("名称只能用小写字母、数字和 -")
                    return@textSheet
                }
                val f = File(itemsDir(kind), "$n.md")
                if (f.exists()) {
                    toast("已经有了")
                    return@textSheet
                }
                val body = if (agents) "---\nname: $n\ndescription: 一句话写清楚什么时候该交给这个子代理\n---\n\n在这里写给它的指示。\n"
                    else "在这里写 /$n 要 Claude 做的事。\$ARGUMENTS 是命令后面跟的内容。\n"
                try {
                    Userland.write(f, body)
                } catch (e: IOException) {
                    toast("新建失败：" + e.message)
                    return@textSheet
                }
                done.run()
                editItem(kind, f)
            }, null)
        }
        sheet.show()
    }

    private fun itemActions(kind: String, f: File) {
        val sheet = sheets.sheet(f.name.replace(Regex("\\.md$"), ""), null)
        val card = sheets.card(sheet)
        sheets.option(card, R.drawable.ic_pencil, "编辑", null, false) {
            sheet.dismiss()
            editItem(kind, f)
        }
        sheets.option(card, R.drawable.ic_trash, "删除", null, true) {
            sheet.dismiss()
            f.delete()
            claude.relaunch()
            toast("已删除")
            app.settings.refreshSettings()
            itemsSheet(kind)
        }
        sheet.show()
    }

    private fun editItem(kind: String, f: File) {
        sheets.textSheet(f.name, claude.readFile(f), "", true, { t, done ->
            try {
                Userland.write(f, t)
            } catch (e: IOException) {
                toast("保存失败：" + e.message)
                return@textSheet
            }
            claude.relaunch()
            toast("已保存")
            done.run()
            app.settings.refreshSettings()
        }) { itemsSheet(kind) }
    }

    // ---- Hooks: settings.json's hooks ----

    /** settings.json's hooks as JSON; an example rule to start from when there are none. */
    private fun hooksEdit() {
        val hooks = claude.settingsJson().optJSONObject("hooks")
        val text = try {
            if (hooks != null && hooks.length() > 0) hooks.toString(2).replace("\\/", "/")
            else "{\n  \"PostToolUse\": [\n    {\n      \"matcher\": \"Edit|Write\",\n      \"hooks\": [\n        { \"type\": \"command\", \"command\": \"要运行的命令\" }\n      ]\n    }\n  ]\n}"
        } catch (e: JSONException) {
            "{}"
        }
        sheets.textSheet("Hooks", text, "{ }", true, { t, done ->
            val now = claude.settingsJson()
            try {
                val h = if (t.trim().isEmpty()) JSONObject() else JSONObject(t)
                if (h.length() == 0) now.remove("hooks") else now.put("hooks", h)
            } catch (e: JSONException) {
                toast("不是有效的 JSON：" + e.message)
                return@textSheet
            }
            if (claude.saveSettings(now)) {
                toast("已保存")
                done.run()
                app.settings.refreshSettings()
            }
        }, ::hooksSheet)
    }

    private fun hooksSheet() {
        val sheet = sheets.sheet("Hooks", null)
        val json = claude.settingsJson()
        val hooks = json.optJSONObject("hooks")
        val card = sheets.card(sheet)
        // Each event's rules: what they match, and the command they run.
        if (hooks != null) for (event in hooks.keys()) {
            val rules = hooks.optJSONArray(event) ?: continue
            for (i in 0 until rules.length()) {
                val rule = rules.optJSONObject(i) ?: continue
                val hs = rule.optJSONArray("hooks") ?: continue
                for (k in 0 until hs.length()) {
                    val h = hs.optJSONObject(k)
                    val what = h?.optString("command", h.optString("url", h.optString("prompt", h.optString("type")))) ?: ""
                    val matcher = rule.optString("matcher")
                    sheets.option(card, R.drawable.ic_refresh, if (matcher.isEmpty()) event else "$event · $matcher", what, false) {
                        sheet.dismiss()
                        hooksEdit()
                    }
                }
            }
        }
        if (card.childCount == 0) sheets.empty(card, "还没有")
        val more = sheets.card(sheet)
        Ui.margins(more, app, 0, 10, 0, 0)
        sheets.option(more, R.drawable.ic_pencil, "编辑", null, false) {
            sheet.dismiss()
            hooksEdit()
        }
        val off = json.optBoolean("disableAllHooks")
        sheets.option(more, if (off) R.drawable.ic_check else R.drawable.ic_stop_circle, if (off) "全部启用" else "全部停用", null, false) {
            val now = claude.settingsJson()
            if (off) now.remove("disableAllHooks") else now.put("disableAllHooks", true)
            if (claude.saveSettings(now)) {
                toast("已保存")
                sheet.dismiss()
                app.settings.refreshSettings()
                hooksSheet()
            }
        }
        sheet.show()
    }

    private fun toolsSheet(launch: JSONObject) {
        val sheet = sheets.sheet("工具", null)
        val card = sheets.card(sheet)
        val tools = savedArray("tools")
        val off = disallowed(launch)
        val all = ArrayList<String>()
        for (i in 0 until tools.length()) all.add(tools.optString(i))
        for (t in off) if (!all.contains(t)) all.add(t)
        for (t in all) {
            val on = !off.contains(t)
            // As Java's split had it: no empty parts at the end.
            val parts = t.split("__").dropLastWhile { it.isEmpty() }
            val sub = if (t.startsWith("mcp__")) parts[1] else null
            val label = if (t.startsWith("mcp__") && parts.size > 2) t.substring(t.indexOf("__", 5) + 2) else t
            sheets.option(card, 0, label, sub, false) {
                val next = ArrayList(off)
                if (on) next.add(t) else next.remove(t)
                claude.saveLaunch("disallowedTools", next.joinToString(" ")) {
                    sheet.dismiss()
                    app.settings.refreshSettings()
                    bridge.get("/launch") { status, l ->
                        if (status == 200) {
                            app.settings.launchCache = l
                            toolsSheet(l)
                        }
                    }
                }
            }
            if (on) sheets.check(card)
        }
        if (all.isEmpty()) sheets.empty(card, "发一条消息后显示")
        sheet.show()
    }

    private companion object {
        /** agents: subagents; commands: slash commands. Each a .md file under .claude/<kind>. */
        const val AGENTS = "agents"
        const val COMMANDS = "commands"

        fun count(n: IntArray?, i: Int): String? = if (n == null || n[i] == 0) null else n[i].toString()

        fun addItems(out: MutableList<Item>, seen: MutableSet<String>, root: File, dir: File, from: String?) {
            val files = dir.listFiles() ?: return
            files.sort()
            for (f in files) {
                if (f.isDirectory) {
                    addItems(out, seen, root, f, from)
                    continue
                }
                if (!f.name.endsWith(".md")) continue
                val rel = root.toURI().relativize(f.toURI()).path
                val n = rel.substring(0, rel.length - 3).replace('/', ':')
                if (seen.add(n)) out.add(Item(n, f, from))
            }
        }

        fun disallowed(launch: JSONObject): List<String> =
            launch.optString("disallowedTools").replace(',', ' ').trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    }
}
