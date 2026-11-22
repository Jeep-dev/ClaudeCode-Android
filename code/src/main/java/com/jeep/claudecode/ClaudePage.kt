package com.jeep.claudecode

import android.net.Uri
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import java.util.Locale
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener

/** Settings → Claude Code and 模型: Claude Code's settings.json as its own schema describes it, its environment variables, the API. */
class ClaudePage(app: MainActivity) : Part(app) {
    /** The settings Claude Code knows (key, type, desc, choices), read from it by the bridge; null until read. */
    private var schema: JSONArray? = null

    fun loadSchema(then: Runnable) {
        if (schema != null || !app.link.linked) return
        bridge.get("/settings-schema") { status, data ->
            if (status != 200) return@get
            schema = data.optJSONArray("settings") ?: JSONArray()
            then.run()
        }
    }

    private fun schemaOf(key: String): JSONObject? {
        val s = schema ?: return null
        for (i in 0 until s.length()) {
            val o = s.optJSONObject(i)
            if (o != null && key == o.optString("key")) return o
        }
        return null
    }

    fun fillClaude(box: LinearLayout) {
        val json = claude.settingsJson()
        val env = json.optJSONObject("env")
        val tools = app.settings.card(box)
        sheets.option(tools, R.drawable.ic_plus, "添加设置", null, false, ::addSettingSheet)
        sheets.option(tools, R.drawable.ic_list, "环境变量", if (env == null || env.length() == 0) null else env.length().toString(), false, ::envSheet)
        sheets.option(tools, R.drawable.ic_file, "settings.json", null, false, ::rawSettingsSheet)
        val keys = json.keys().asSequence().filter { it != "env" }.toMutableList()
        if (keys.isEmpty()) return
        keys.sortWith(String.CASE_INSENSITIVE_ORDER)
        app.settings.section(box, "已设置")
        val set = app.settings.card(box)
        for (k in keys) sheets.option(set, 0, k, summary(json.opt(k)), false) { settingSheet(k) }
    }

    /** One setting: its description, and a way to set it fitting its type; or to remove it. */
    private fun settingSheet(key: String) {
        val json = claude.settingsJson()
        val value: Any? = json.opt(key)
        val spec = schemaOf(key)
        val type = spec?.optString("type") ?: when (value) {
            is Boolean -> "bool"
            is Number -> "number"
            is String -> "string"
            else -> "json"
        }
        val sheet = sheets.sheet(key, spec?.optString("desc"))
        val save = { v: Any? ->
            val now = claude.settingsJson()
            if (v == null) now.remove(key) else now.put(key, v)
            if (claude.saveSettings(now)) {
                toast("已保存")
                sheet.dismiss()
                app.settings.refreshSettings()
            }
        }
        if (type == "bool" || type == "enum") {
            val card = sheets.card(sheet)
            val values = ArrayList<Any>()
            val names = ArrayList<String>()
            if (type == "bool") {
                values.add(true)
                names.add("开")
                values.add(false)
                names.add("关")
            } else {
                val choices = spec?.optJSONArray("choices")
                if (choices != null) for (i in 0 until choices.length()) {
                    values.add(choices.opt(i))
                    names.add(choices.opt(i).toString())
                }
                if (value != null && !values.contains(value)) {
                    values.add(value)
                    names.add(value.toString())
                }
            }
            for (i in values.indices) {
                val v = values[i]
                sheets.option(card, 0, names[i], null, false) { save(v) }
                if (v == value) sheets.check(card)
            }
            if (value != null) {
                val reset = sheets.card(sheet)
                Ui.margins(reset, app, 0, 10, 0, 0)
                sheets.option(reset, R.drawable.ic_trash, "移除", null, true) { save(null) }
            }
            sheet.show()
            return
        }
        val field = EditText(app)
        val multi = type == "json" || (value is String && value.contains("\n"))
        val text = when {
            value is JSONObject -> value.toString(2)
            value is JSONArray -> value.toString(2)
            value != null && value != JSONObject.NULL -> value.toString()
            else -> ""
        }
        field.setText(text)
        field.textSize = 14f
        field.typeface = Ui.MONO
        field.setTextColor(col(R.color.text))
        field.setHintTextColor(col(R.color.text3))
        field.gravity = Gravity.TOP or Gravity.START
        field.setPadding(dp(12), dp(10), dp(12), dp(10))
        field.background = Ui.round(col(R.color.surface), dp(10), dp(1), col(R.color.borderStrong))
        if (multi) {
            field.minLines = 5
            field.maxLines = 14
            field.hint = "JSON"
        } else {
            field.isSingleLine = true
            if (type == "number") field.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        sheets.content(sheet).addView(field, Ui.fill())
        val remove = sheets.button(if (value == null) "取消" else "移除", false) {
            if (value == null) sheet.dismiss() else save(null)
        }
        val ok = sheets.button("保存", true) {
            val t = field.text.toString().trim()
            if (t.isEmpty()) {
                save(null)
                return@button
            }
            try {
                when (type) {
                    "number" -> save(if (t.matches(Regex("-?\\d+"))) t.toLong() else t.toDouble())
                    "json" -> save(JSONTokener(t).nextValue())
                    else -> save(field.text.toString())
                }
            } catch (e: NumberFormatException) {
                toast("不是数字")
            } catch (e: JSONException) {
                toast(if (type == "number") "不是数字" else "不是有效的 JSON：" + e.message)
            }
        }
        ok.background = Ui.press(app, Ui.round(col(R.color.text), dp(10)), dp(10))
        ok.setTextColor(col(R.color.bg))
        sheets.actions(sheet, remove, ok)
        sheet.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        sheet.show()
    }

    /** Every setting Claude Code knows and settings.json lacks, found by name or description. */
    private fun addSettingSheet() {
        val all = schema
        if (all == null) {
            if (!app.link.linked) {
                toast("Claude Code 还没启动好")
                return
            }
            loadSchema(::addSettingSheet)
            return
        }
        val sheet = sheets.sheet("添加设置", null)
        val search = EditText(app)
        search.hint = "搜索"
        search.isSingleLine = true
        search.textSize = 15f
        search.setTextColor(col(R.color.text))
        search.setHintTextColor(col(R.color.text3))
        search.setPadding(dp(12), 0, dp(12), 0)
        search.background = Ui.round(col(R.color.surface2), dp(10))
        sheets.content(sheet).addView(search, LinearLayout.LayoutParams(-1, dp(42)))
        val list = sheets.card(sheet)
        Ui.margins(list, app, 0, 10, 0, 0)
        val json = claude.settingsJson()
        val rows = ArrayList<View>()
        val texts = ArrayList<String>()
        for (i in 0 until all.length()) {
            val s = all.optJSONObject(i)
            if (s == null || json.has(s.optString("key"))) continue
            val key = s.optString("key")
            val desc = s.optString("desc")
            val row = Ui.column(app)
            row.setPadding(dp(14), dp(10), dp(14), dp(10))
            row.background = Ui.press(app, null, dp(12))
            row.addView(Ui.text(app, key, 15, R.color.text))
            val d = Ui.text(app, desc, 12.5f, R.color.text3)
            d.maxLines = 2
            d.ellipsize = TextUtils.TruncateAt.END
            d.setPadding(0, dp(2), 0, 0)
            row.addView(d)
            row.setOnClickListener {
                sheet.dismiss()
                settingSheet(key)
            }
            list.addView(row, Ui.fill())
            rows.add(row)
            texts.add("$key $desc".lowercase(Locale.ROOT))
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable) {
                val q = s.toString().trim().lowercase(Locale.ROOT)
                for (i in rows.indices) rows[i].visibility = if (q.isEmpty() || texts[i].contains(q)) View.VISIBLE else View.GONE
            }
        })
        sheet.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        sheet.show()
    }

    /** settings.json's env: Claude Code's environment variables. */
    private fun envSheet() {
        val sheet = sheets.sheet("环境变量", null)
        val env = claude.settingsEnv()
        val keys = env.keys().asSequence().sorted().toList()
        if (keys.isNotEmpty()) {
            val card = sheets.card(sheet)
            for (k in keys) sheets.option(card, 0, k, env.optString(k), false) {
                sheet.dismiss()
                envEdit(k)
            }
            Ui.margins(card, app, 0, 0, 0, 10)
        }
        val add = sheets.card(sheet)
        sheets.option(add, R.drawable.ic_plus, "添加变量", null, false) {
            sheet.dismiss()
            envEdit(null)
        }
        sheet.show()
    }

    private fun envEdit(key: String?) {
        val sheet = sheets.sheet(key ?: "添加变量", null)
        val name = sheets.field(sheet, "名称", key ?: "", "NAME")
        val value = sheets.field(sheet, "值", if (key == null) "" else claude.settingsEnv().optString(key), "")
        val remove = sheets.button(if (key == null) "取消" else "删除", false) {
            if (key == null) {
                sheet.dismiss()
                envSheet()
                return@button
            }
            val json = claude.settingsJson()
            json.optJSONObject("env")?.remove(key)
            if (claude.saveSettings(json)) {
                toast("已删除")
                sheet.dismiss()
                envSheet()
                app.settings.refreshSettings()
            }
        }
        val ok = sheets.button("保存", true) {
            val n = name.text.toString().trim()
            if (n.isEmpty()) {
                toast("名称不能为空")
                return@button
            }
            val json = claude.settingsJson()
            val env = json.optJSONObject("env") ?: JSONObject()
            if (key != null) env.remove(key)
            env.put(n, value.text.toString())
            json.put("env", env)
            if (claude.saveSettings(json)) {
                toast("已保存")
                sheet.dismiss()
                envSheet()
                app.settings.refreshSettings()
            }
        }
        ok.background = Ui.press(app, Ui.round(col(R.color.text), dp(10)), dp(10))
        ok.setTextColor(col(R.color.bg))
        sheets.actions(sheet, remove, ok)
        sheet.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        sheet.show()
    }

    private fun rawSettingsSheet() {
        val now = claude.settingsJson()
        val text = try {
            if (claude.settingsBroken) claude.readFile(claude.settingsFile()) else now.toString(2)
        } catch (e: JSONException) {
            "{}"
        }
        sheets.textSheet("settings.json", text, "{ }", true, { t, done ->
            val json = try {
                JSONObject(t.trim().ifEmpty { "{}" })
            } catch (e: JSONException) {
                toast("不是有效的 JSON：" + e.message)
                return@textSheet
            }
            claude.settingsBroken = false // replaced whole
            if (claude.saveSettings(json)) {
                toast("已保存")
                done.run()
            }
        }, app.settings::refreshSettings)
    }

    fun apiSheet() {
        val sheet = sheets.sheet("API", null)
        val env = claude.settingsEnv()
        val key = env.optString("ANTHROPIC_AUTH_TOKEN", env.optString("ANTHROPIC_API_KEY"))
        val url = sheets.field(sheet, "地址", env.optString("ANTHROPIC_BASE_URL"), "https://api.anthropic.com")
        val token = sheets.field(sheet, "API Key", key, "sk-…")
        val name = sheets.field(sheet, "模型", env.optString("ANTHROPIC_MODEL"), "")
        val raw = sheets.button("settings.json", false) {
            sheet.dismiss()
            rawSettingsSheet()
        }
        val ok = sheets.button("保存", true) {
            val json = claude.settingsJson()
            val e = json.optJSONObject("env") ?: JSONObject()
            val address = url.text.toString().trim()
            put(e, "ANTHROPIC_BASE_URL", address)
            val k = token.text.toString().trim()
            // Anthropic's own API (no address, or its own written out) takes a key as an API key
            // (x-api-key); other providers take theirs as a bearer token.
            val host = Uri.parse(address).host
            val anthropic = address.isEmpty() || host == "anthropic.com" || (host != null && host.endsWith(".anthropic.com"))
            e.remove("ANTHROPIC_API_KEY")
            e.remove("ANTHROPIC_AUTH_TOKEN")
            put(e, if (anthropic) "ANTHROPIC_API_KEY" else "ANTHROPIC_AUTH_TOKEN", k)
            put(e, "ANTHROPIC_MODEL", name.text.toString().trim())
            json.put("env", e)
            if (claude.saveSettings(json)) {
                toast("已保存")
                sheet.dismiss()
                app.settings.refreshSettings()
            }
        }
        ok.background = Ui.press(app, Ui.round(col(R.color.text), dp(10)), dp(10))
        ok.setTextColor(col(R.color.bg))
        sheets.actions(sheet, raw, ok)
        sheet.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        sheet.show()
    }

    private companion object {
        fun summary(v: Any?): String = when (v) {
            is Boolean -> if (v) "开" else "关"
            is JSONObject -> if (v.length() == 0) "{ }" else "{ " + v.length() + " }"
            is JSONArray -> "[ " + v.length() + " ]"
            null, JSONObject.NULL -> ""
            else -> v.toString()
        }

        fun put(o: JSONObject, key: String, value: String) {
            if (value.isEmpty()) o.remove(key) else o.put(key, value)
        }
    }
}
