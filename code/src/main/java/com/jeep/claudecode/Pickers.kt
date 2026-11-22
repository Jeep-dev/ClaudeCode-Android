package com.jeep.claudecode

import android.app.Dialog
import android.net.Uri
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import java.util.Locale
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** The sheets where the folder, the model and thinking level, the permission mode and CLAUDE.md are picked or written. */
class Pickers(app: MainActivity) : Part(app) {

    /** The model in use and its thinking level, as the box's pill shows them ("Opus 5.5 中等"). */
    fun modelLabel(): String {
        var name: String? = null
        val value = claude.model
        val models = claude.claudeModels
        if (value.isEmpty()) {
            // Claude Code's own choice: the model its "default" stands for, by that one's name.
            var resolved = ""
            for (i in 0 until models.length()) {
                val cm = models.optJSONObject(i)
                if (cm != null && cm.optString("value") == "default") resolved = cm.optString("resolvedModel")
            }
            for (i in 0 until models.length()) {
                if (name != null) break
                val cm = models.optJSONObject(i)
                if (cm != null && cm.optString("value") != "default" && resolved.isNotEmpty() &&
                    resolved == cm.optString("resolvedModel")) name = listedName(cm)
            }
            if (name == null) name = if (claude.defaultModel.isEmpty()) "默认模型" else shortModel(claude.defaultModel)
        } else {
            for (i in 0 until models.length()) {
                if (name != null) break
                val cm = models.optJSONObject(i)
                if (cm != null && cm.optString("value") == value) name = listedName(cm)
            }
            if (name == null) name = shortModel(value)
        }
        val level = effortName(claude.effortInUse())
        return if (level.isEmpty()) name else "$name $level"
    }

    fun folderSheet(path: String?) {
        val at = path ?: claude.cwd.ifEmpty { claude.defaultCwd }
        bridge.get("/dirs?path=" + Uri.encode(at)) { status, d ->
            if (status != 200) {
                toast("读取目录失败")
                return@get
            }
            val here = d.optString("path")
            val sheet = sheets.sheet("工作目录", claude.tilde(here))
            val card = sheets.card(sheet)
            val parent = if (d.isNull("parent")) "" else d.optString("parent")
            if (parent.isNotEmpty()) sheets.option(card, R.drawable.ic_up, "上一级", null, false) {
                sheet.dismiss()
                folderSheet(parent)
            }
            val dirs = d.optJSONArray("dirs")
            if (dirs != null) for (i in 0 until dirs.length()) {
                val name = dirs.optString(i)
                sheets.option(card, R.drawable.ic_folder, name, null, false) {
                    sheet.dismiss()
                    folderSheet("$here/$name")
                }
            }
            if (dirs == null || dirs.length() == 0) sheets.empty(card, "没有子目录")
            val field = Ui.row(app)
            val name = EditText(app)
            name.hint = "新建目录名"
            name.isSingleLine = true
            name.textSize = 15f
            name.setTextColor(col(R.color.text))
            name.setHintTextColor(col(R.color.text3))
            name.setPadding(dp(12), 0, dp(12), 0)
            name.background = Ui.round(col(R.color.surface), dp(10), dp(1), col(R.color.borderStrong))
            field.addView(name, LinearLayout.LayoutParams(0, dp(40), 1f))
            val make = sheets.button("新建", false) {
                val n = name.text.toString().trim()
                if (n.isEmpty() || n.contains("/")) {
                    toast("目录名不能为空，也不能带 /")
                    return@button
                }
                bridge.post("/mkdir", obj("path", "$here/$n")) { s2, _ ->
                    if (s2 == 200) {
                        sheet.dismiss()
                        folderSheet("$here/$n")
                    } else {
                        toast("新建失败")
                    }
                }
            }
            field.addView(make)
            Ui.margins(make, app, 8, 0, 0, 0)
            sheets.content(sheet).addView(field, Ui.fill())
            Ui.margins(field, app, 0, 10, 0, 0)
            val cancel = sheets.button("取消", false) { sheet.dismiss() }
            val use = sheets.button("使用这个目录", true) {
                claude.cwd = here
                prefs.edit().putString("cwd", claude.cwd).apply()
                if (app.current.sessionId == null) app.current.cwd = claude.cwd
                sheet.dismiss()
                app.renderNow()
                app.drawer.renderSessions() // the drawer may list only this folder's
                app.settings.refreshSettings()
            }
            use.background = Ui.press(app, Ui.round(col(R.color.text), dp(10)), dp(10))
            use.setTextColor(col(R.color.bg))
            sheets.actions(sheet, cancel, use)
            sheet.show()
        }
    }

    /** Picks a model: saved, and the box shows it. */
    private fun useModel(sheet: Dialog, m: String) {
        claude.model = m
        prefs.edit().putString("model", claude.model).apply()
        sheet.dismiss()
        app.screen.updateChrome()
    }

    fun modelSheet() {
        val sheet = sheets.sheet("模型", null)
        // Thinking: one segmented bar.
        val bar = Ui.row(app)
        bar.setPadding(dp(3), dp(3), dp(3), dp(3))
        bar.background = Ui.round(col(R.color.surface2), dp(12))
        val level = claude.effortInUse()
        for (e in claude.efforts) {
            val on = e == level
            val b = Ui.text(app, e, 13.5f, if (on) R.color.text else R.color.text2)
            b.gravity = Gravity.CENTER
            b.isSingleLine = true
            if (on) {
                b.typeface = Ui.MEDIUM
                b.background = Ui.round(col(R.color.bg), dp(9), dp(1), col(R.color.border))
            }
            b.setOnClickListener {
                claude.effort = e
                prefs.edit().putString("effort", claude.effort).apply()
                sheet.dismiss()
                app.screen.updateChrome()
            }
            bar.addView(b, LinearLayout.LayoutParams(0, dp(34), 1f))
        }
        if (claude.efforts.isNotEmpty()) {
            sheets.content(sheet).addView(bar, Ui.fill())
            Ui.margins(bar, app, 0, 0, 0, 12)
        }
        // Claude Code's own list first ("default": no --model, its own choice), then ones from the
        // settings and typed here; each once.
        var own: LinearLayout? = null
        val listed = ArrayList<String>()
        for (i in 0 until claude.claudeModels.length()) {
            val cm = claude.claudeModels.optJSONObject(i) ?: continue
            val value = cm.optString("value")
            val resolved = cm.optString("resolvedModel")
            listed.add(value)
            listed.add(resolved)
            // "default" is one of the others under another name: not listed twice.
            if (value == "default") continue
            val card = own ?: sheets.card(sheet).also { own = it }
            // Its full name with the version, as Claude Code describes it ("Opus 5.5 · …").
            val desc = cm.optString("description")
            val name = if (desc.contains(" · ")) desc.substring(0, desc.indexOf(" · ")) else cm.optString("displayName", value)
            sheets.option(card, 0, name, null, false) { useModel(sheet, value) }
            if (claude.model == value || (claude.model.isEmpty() && claude.defaultResolved(resolved))) sheets.check(card)
        }
        own?.let { Ui.margins(it, app, 0, 0, 0, 10) }
        val all = ArrayList<String>()
        if (claude.defaultModel.isNotEmpty()) all.add(claude.defaultModel)
        for (m in claude.models) if (!all.contains(m)) all.add(m)
        for (m in usedModels()) if (!all.contains(m)) all.add(m)
        all.removeAll(listed.toSet())
        val inUse = claude.model.ifEmpty { claude.defaultModel }
        val card = sheets.card(sheet)
        // Typed under 其他模型 and nowhere else (not the settings' list nor the default): those a long press may remove.
        val typed = usedModels()
        typed.removeAll(claude.models.toSet())
        typed.remove(claude.defaultModel)
        for (m in all) {
            val big = m.lowercase(Locale.ROOT).endsWith("[1m]")
            sheets.option(card, 0, shortModel(m), if (big) "1M" else null, false) { useModel(sheet, m) }
            // One typed under 其他模型 (not from the settings): a long press takes it off the list.
            if (typed.contains(m)) card.getChildAt(card.childCount - 1).setOnLongClickListener {
                removeModelAsk(sheet, m)
                true
            }
            if (m == inUse) sheets.check(card)
        }
        sheets.option(card, R.drawable.ic_plus, "其他模型", null, false) {
            sheet.dismiss()
            sheets.textSheet("其他模型", "", "模型名", false, { t, done ->
                val name = t.trim()
                if (name.isEmpty()) return@textSheet
                val used = usedModels()
                used.remove(name)
                used.add(0, name)
                prefs.edit().putString("usedModels", JSONArray(used.take(8)).toString()).apply()
                claude.model = name
                prefs.edit().putString("model", claude.model).apply()
                app.screen.updateChrome()
                done.run()
            }, null)
        }
        sheet.show()
    }

    /** Asks, then takes a model typed under 其他模型 off the list; if it was the one in use, Claude Code's default is. */
    private fun removeModelAsk(picker: Dialog, m: String) {
        val ask = sheets.sheet("移除 $m？", "只是从这个列表里去掉，需要时可以在「其他模型」里再加。")
        val cancel = sheets.button("取消", false) { ask.dismiss() }
        val ok = sheets.button("移除", true) {
            ask.dismiss()
            val used = usedModels()
            used.remove(m)
            prefs.edit().putString("usedModels", JSONArray(used).toString()).apply()
            if (claude.model == m) {
                claude.model = ""
                prefs.edit().putString("model", "").apply()
                app.screen.updateChrome()
            }
            picker.dismiss()
            modelSheet()
        }
        sheets.actions(ask, cancel, ok)
        ask.show()
    }

    private fun usedModels(): MutableList<String> {
        val out = ArrayList<String>()
        try {
            val a = JSONArray(prefs.getString("usedModels", "[]"))
            for (i in 0 until a.length()) out.add(a.optString(i))
        } catch (ignored: JSONException) {
            // none
        }
        return out
    }

    fun permissionSheet(mode: String) {
        val sheet = sheets.sheet("权限模式", null)
        val card = sheets.card(sheet)
        val modes = ArrayList(claude.permissionModes)
        if (!modes.contains(mode)) modes.add(0, mode)
        for (m in modes) {
            sheets.option(card, 0, m, null, false) {
                claude.saveLaunch("permissionMode", m) {
                    sheet.dismiss()
                    app.settings.refreshSettings()
                }
            }
            if (m == mode) sheets.check(card)
        }
        sheet.show()
    }

    fun memorySheet(scope: String, folder: String?, title: String) {
        val q = "/memory?scope=" + scope + if (folder == null) "" else "&cwd=" + Uri.encode(folder)
        bridge.get(q) { status, data ->
            if (status != 200) {
                toast("读取 CLAUDE.md 失败")
                return@get
            }
            sheets.textSheet(title, data.optString("text"), "", true, { t, done ->
                val body = if (folder != null) obj("scope", scope, "cwd", folder, "text", t) else obj("scope", scope, "text", t)
                bridge.post("/memory", body) { s2, _ ->
                    if (s2 == 200) {
                        toast("已保存")
                        done.run()
                    } else {
                        toast("保存失败")
                    }
                }
            }, app.settings::refreshSettings)
        }
    }

    private companion object {
        fun shortModel(m: String?): String = if (m.isNullOrEmpty()) "默认模型" else m.replace(Regex("(?i)\\[1m\\]$"), "")

        /** A model of Claude Code's list by its name with the version ("Opus 5.5"), as the model menu lists it. */
        fun listedName(cm: JSONObject): String {
            val desc = cm.optString("description")
            return if (desc.contains(" · ")) desc.substring(0, desc.indexOf(" · ")) else cm.optString("displayName", cm.optString("value"))
        }

        fun effortName(e: String?): String = when (e?.lowercase(Locale.ROOT) ?: "") {
            "low" -> "低"
            "medium" -> "中等"
            "high" -> "高"
            "xhigh" -> "超高"
            "max" -> "最高"
            else -> e ?: ""
        }
    }
}
