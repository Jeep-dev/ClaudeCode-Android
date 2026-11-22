package com.jeep.claudecode

import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.util.Locale
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** What this app knows of Claude Code: what the bridge reported of it, what is picked here (model, thinking level, folder), and its settings.json. */
class Claude(app: MainActivity) : Part(app) {
    /** Claude Code's version, as the bridge reported it. */
    @JvmField var claudeVersion = ""
    /** From Claude Code itself (its --help and settings.json): thinking levels, permission modes, the level set. */
    @JvmField val efforts = ArrayList<String>()
    @JvmField val permissionModes = ArrayList<String>()
    /** The thinking level in Claude Code's own settings. */
    private var configEffort = ""
    /**
     * The userland's home and the folder Claude Code starts in (from the bridge); what is picked here: the folder
     * for new conversations, the model and the thinking level (empty: Claude Code's own); its default model.
     */
    @JvmField var home = ""
    @JvmField var defaultCwd = ""
    @JvmField var cwd: String = prefs.getString("cwd", "") ?: ""
    @JvmField var model: String = prefs.getString("model", "") ?: ""
    @JvmField var effort: String = prefs.getString("effort", "") ?: ""
    @JvmField var defaultModel = ""
    /** The models Claude Code's settings name. */
    @JvmField val models = ArrayList<String>()
    /** The / commands Claude Code has (kept for the next start, when the box offers them before it has reported). */
    @JvmField val commands = ArrayList<String>()
    /** Claude Code's own model list (as its /model shows it): value, name. */
    @JvmField var claudeModels = JSONArray()
    /** settings.json is there but did not read as JSON: a change saved over it would lose it. */
    @JvmField var settingsBroken = false

    init {
        try {
            fill(efforts, JSONArray(prefs.getString("efforts", "[]")))
            fill(permissionModes, JSONArray(prefs.getString("permissionModes", "[]")))
            claudeModels = JSONArray(prefs.getString("claudeModels", "[]"))
        } catch (ignored: JSONException) {
            // none saved
        }
        try {
            val saved = JSONArray(prefs.getString("commands", "[]"))
            for (i in 0 until saved.length()) commands.add(saved.optString(i))
        } catch (ignored: JSONException) {
            // none saved
        }
    }

    /** Whether Claude Code's default (no model chosen) is this model. */
    fun defaultResolved(resolved: String): Boolean {
        for (i in 0 until claudeModels.length()) {
            val cm = claudeModels.optJSONObject(i)
            if (cm != null && cm.optString("value") == "default") return resolved.isNotEmpty() && resolved == cm.optString("resolvedModel")
        }
        return false
    }

    /** Takes what the bridge read from Claude Code and its settings. */
    fun applyConfig(data: JSONObject) {
        fill(models, data.optJSONArray("models"))
        val cm = data.optJSONArray("claudeModels")
        if (cm != null && cm.length() > 0) {
            claudeModels = cm
            prefs.edit().putString("claudeModels", cm.toString()).apply()
        }
        defaultModel = data.optString("defaultModel")
        claudeVersion = data.optString("claudeVersion")
        configEffort = data.optString("effort").lowercase(Locale.ROOT)
        // Found a moment after the bridge starts: until then, the last ones it gave.
        val found = data.optJSONArray("efforts")
        if (found != null && found.length() > 0) {
            fill(efforts, found)
            fill(permissionModes, data.optJSONArray("permissionModes"))
            prefs.edit().putString("efforts", found.toString())
                .putString("permissionModes", data.optJSONArray("permissionModes").toString()).apply()
        }
        app.screen.updateChrome()
    }

    /** The folder new conversations start in (Settings → 对话), Claude Code's default until one is picked. */
    fun workFolder(): String = cwd.ifEmpty { defaultCwd }

    fun tilde(p: String?): String {
        if (p == null) return ""
        return if (home.isNotEmpty() && p.startsWith(home)) "~" + p.substring(home.length) else p
    }

    /** The thinking level in use: picked here, else Claude Code's own setting. */
    fun effortInUse(): String = effort.ifEmpty { configEffort }

    fun readFile(f: File): String = try {
        Userland.read(FileInputStream(f))
    } catch (e: IOException) {
        ""
    }

    fun readJson(f: File): JSONObject = try {
        JSONObject(readFile(f))
    } catch (e: JSONException) {
        JSONObject()
    }

    /** A change Claude Code reads when it starts: new conversations start with it. */
    fun relaunch() {
        if (app.link.linked) bridge.post("/launch", JSONObject(), null)
    }

    fun mcpFolder(): String {
        val f = if (app.hasCurrent && app.current.cwd.isNotEmpty()) app.current.cwd else cwd
        return f.ifEmpty { defaultCwd }
    }

    // ---- settings.json, read and written here directly ----

    fun settingsFile(): File = userland.file(Userland.HOME + "/.claude/settings.json")

    fun settingsJson(): JSONObject {
        settingsBroken = false
        val f = settingsFile()
        if (!f.exists()) return JSONObject()
        return try {
            JSONObject(Userland.read(FileInputStream(f)))
        } catch (e: Exception) {
            settingsBroken = true
            JSONObject()
        }
    }

    fun settingsEnv(): JSONObject = settingsJson().optJSONObject("env") ?: JSONObject()

    /** Writes settings.json; new conversations start with it. */
    fun saveSettings(json: JSONObject): Boolean {
        if (settingsBroken) {
            toast("settings.json 格式有错，没有保存")
            return false
        }
        try {
            Userland.replace(settingsFile(), json.toString(2) + "\n")
        } catch (e: Exception) {
            toast("保存失败：" + e.message)
            return false
        }
        if (app.link.linked) {
            bridge.post("/launch", JSONObject(), null) // the spare is started again with it
            bridge.get("/config") { status, data ->
                if (status != 200) return@get
                applyConfig(data)
                app.renderNow()
            }
        }
        return true
    }

    fun saveLaunch(key: String, value: String, done: Runnable) {
        bridge.post("/launch", obj(key, value)) { status, data ->
            if (status == 200) {
                toast("已保存")
                done.run()
            } else {
                toast(if (status == 400) "参数有误：" + data.optString("error") else "保存失败")
            }
        }
    }

    private companion object {
        fun fill(to: MutableList<String>, from: JSONArray?) {
            to.clear()
            if (from != null) for (i in 0 until from.length()) to.add(from.optString(i))
        }
    }
}
