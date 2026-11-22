package com.jeep.claudecode

import java.io.File
import org.json.JSONException
import org.json.JSONObject

/** Settings → 扩展 → MCP: the servers Claude Code has for the folder, and those added here before. */
class McpServers(app: MainActivity) : Part(app) {

    private fun mcpFile(): File = userland.file(Userland.HOME + "/.ccapp/mcp.json")

    /** The MCP servers added here, by name. */
    private fun mcpServers(): JSONObject = try {
        JSONObject(claude.readFile(mcpFile())).optJSONObject("mcpServers") ?: JSONObject()
    } catch (e: JSONException) {
        JSONObject()
    }

    private fun saveMcp(servers: JSONObject): Boolean {
        try {
            Userland.replace(mcpFile(), JSONObject().put("mcpServers", servers).toString(2) + "\n")
        } catch (e: Exception) {
            toast("保存失败：" + e.message)
            return false
        }
        claude.relaunch()
        app.settings.refreshSettings()
        return true
    }

    /**
     * An MCP server: its name, where it is kept (user, local (this folder), project (the folder's .mcp.json):
     * Claude Code's; app: added here before), and its settings.
     */
    class Mcp(val name: String, val scope: String, val config: JSONObject)

    /** Every MCP server Claude Code has for this folder (as `claude mcp` keeps them), and those added here before. */
    fun mcpList(): List<Mcp> {
        val out = ArrayList<Mcp>()
        val folder = claude.mcpFolder()
        val cc = claude.readJson(userland.file(Userland.HOME + "/.claude.json"))
        val here = cc.optJSONObject("projects")?.optJSONObject(folder)
        addMcp(out, cc.optJSONObject("mcpServers"), "user")
        addMcp(out, here?.optJSONObject("mcpServers"), "local")
        addMcp(out, claude.readJson(userland.file("$folder/.mcp.json")).optJSONObject("mcpServers"), "project")
        addMcp(out, mcpServers(), "app")
        return out
    }

    /** Has Claude Code itself add, replace or remove one of its servers; then shows the list again. */
    private fun mcpChange(action: String, m: Mcp, config: JSONObject?, done: Runnable?) {
        if (!app.link.linked) {
            toast("Claude Code 还没启动好")
            return
        }
        toast("正在保存…")
        val body = obj("action", action, "name", m.name, "scope", m.scope, "cwd", claude.mcpFolder())
        if (config != null) body.put("config", config)
        bridge.post("/mcp", body) { status, data ->
            if (status != 200) {
                toast("失败：" + data.optString("error"))
                return@post
            }
            toast(if (action == "remove") "已删除" else "已保存")
            done?.run()
            app.settings.refreshSettings()
            mcpSheet()
        }
    }

    fun mcpSheet() {
        val sheet = sheets.sheet("MCP", null)
        val card = sheets.card(sheet)
        // Their state, from when a conversation last started.
        val state = HashMap<String, String>()
        val st = savedArray("mcpServers")
        for (i in 0 until st.length()) {
            val o = st.optJSONObject(i) ?: continue
            state[o.optString("name")] = o.optString("status")
        }
        val servers = mcpList()
        val shown = HashSet<String>()
        for (m in servers) {
            val s = state[m.name]
            shown.add(m.name)
            sheets.option(card, R.drawable.ic_globe, m.name, s?.let(::mcpState), false) { sheet.dismiss(); mcpActions(m) }
        }
        // Servers Claude Code reported but keeps elsewhere (plugins, claude.ai): shown, not changed here.
        for ((name, s) in state) {
            if (name in shown) continue
            sheets.option(card, R.drawable.ic_globe, name, mcpState(s), false, null)
        }
        if (servers.isEmpty() && state.isEmpty()) sheets.empty(card, "还没有")
        val add = sheets.card(sheet)
        Ui.margins(add, app, 0, 10, 0, 0)
        sheets.option(add, R.drawable.ic_plus, "添加", null, false) {
            sheet.dismiss()
            sheets.textSheet("添加 MCP", "", "{\n  \"名称\": {\n    \"type\": \"http\",\n    \"url\": \"https://…\"\n  }\n}", true, { t, done ->
                val input = try {
                    JSONObject(t.trim())
                } catch (e: JSONException) {
                    toast("不是有效的 JSON：" + e.message)
                    return@textSheet
                }
                // Docs give {"mcpServers": {...}}, or just the servers.
                val given = input.optJSONObject("mcpServers") ?: input
                val adding = ArrayList<Mcp>()
                for (k in given.keys()) {
                    val cfg = given.optJSONObject(k)
                    if (cfg == null) {
                        toast("$k 的配置不是一个对象")
                        return@textSheet
                    }
                    adding.add(Mcp(k, "user", cfg))
                }
                if (adding.isEmpty()) {
                    toast("没有找到服务器配置")
                    return@textSheet
                }
                // Into Claude Code's own list (user scope), one after another.
                var at = 0
                lateinit var next: Runnable
                next = Runnable {
                    if (at >= adding.size) {
                        done.run()
                        return@Runnable
                    }
                    val m = adding[at++]
                    mcpChange("set", m, m.config, next)
                }
                next.run()
            }, ::mcpSheet)
        }
        sheet.show()
    }

    private fun mcpActions(m: Mcp) {
        val sheet = sheets.sheet(m.name, null)
        val card = sheets.card(sheet)
        sheets.option(card, R.drawable.ic_pencil, "编辑", null, false) {
            sheet.dismiss()
            val text = try {
                m.config.toString(2).replace("\\/", "/")
            } catch (e: JSONException) {
                "{}"
            }
            sheets.textSheet("MCP · " + m.name, text, "{ }", true, { t, done ->
                val cfg = try {
                    JSONObject(t.trim())
                } catch (e: JSONException) {
                    toast("不是有效的 JSON：" + e.message)
                    return@textSheet
                }
                if (m.scope != "app") {
                    mcpChange("set", m, cfg, done)
                    return@textSheet
                }
                val all = mcpServers()
                all.put(m.name, cfg)
                if (saveMcp(all)) {
                    toast("已保存")
                    done.run()
                }
            }, ::mcpSheet)
        }
        sheets.option(card, R.drawable.ic_trash, "删除", null, true) {
            sheet.dismiss()
            if (m.scope != "app") {
                mcpChange("remove", m, null, null)
                return@option
            }
            val all = mcpServers()
            all.remove(m.name)
            if (saveMcp(all)) toast("已删除")
            mcpSheet()
        }
        sheet.show()
    }

    private companion object {
        fun addMcp(out: MutableList<Mcp>, servers: JSONObject?, scope: String) {
            if (servers == null) return
            for (n in servers.keys()) {
                val cfg = servers.optJSONObject(n) ?: continue
                out.add(Mcp(n, scope, cfg))
            }
        }

        fun mcpState(s: String): String = when (s) {
            "connected" -> "已连接"
            "failed" -> "连接失败"
            "pending" -> "连接中"
            "needs-auth" -> "需要授权"
            else -> s
        }
    }
}
