package com.jeep.claudecode

import java.time.Duration
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/**
 * What 用量 shows, read from the bridge's /context and /usage: plain values and the words for them, apart
 * from how they are drawn (UsageSheet), so they can be checked without a screen.
 */
internal object Usage {
    /** Claude Code's kinds, each its own colour slot whatever else is there: a colour follows its kind. */
    private val KINDS = listOf(
        "System prompt" to "系统提示", "System tools" to "系统工具", "MCP tools" to "MCP 工具",
        "Custom agents" to "自定义代理", "Memory files" to "记忆文件", "Skills" to "技能",
        "Messages" to "消息", "MCP server instructions" to "MCP 服务说明",
    )

    /** One kind's part of the context window: its slot in the palette (7 for anything else Claude Code adds). */
    data class Part(val name: String, val tokens: Long, val slot: Int)

    /** The context window: what is in use, by kind, and the buffer kept for compacting. */
    data class Window(val total: Long, val max: Long, val parts: List<Part>, val buffer: Long) {
        val percent: Long get() = Math.round(100.0 * total / max)
        val free: Long get() = maxOf(0, max - total - buffer)
    }

    /** How near a limit is to running out: the meter's colour and the words beside it. */
    enum class Level { OK, LOW, OUT }

    data class Limit(val name: String, val percent: Int, val level: Level, val note: String)

    /** One of the account's limit resets (a cedar_ember grant). */
    data class Reset(
        val id: String, val title: String, val left: Int, val usable: Boolean,
        /** How many are left and until when. */
        val sub: String,
        /** What it gives back; "" when the bridge could not tell. */
        val gives: String,
    )

    sealed interface Plan {
        data class Note(val text: String) : Plan
        data class Limits(val limits: List<Limit>, val resets: List<Reset>) : Plan
    }

    fun window(c: JSONObject): Window {
        val max = maxOf(1, c.optLong("maxTokens"))
        val parts = ArrayList<Part>()
        var buffer = 0L
        val cats = c.optJSONArray("categories")
        for (i in 0 until (cats?.length() ?: 0)) {
            val k = cats!!.optJSONObject(i)
            if (k == null || k.optBoolean("deferred")) continue // not loaded: in no part of the window
            val name = k.optString("name")
            val t = k.optLong("tokens")
            when {
                name == "Free space" -> {}
                name == "Autocompact buffer" -> buffer = t
                t > 0 -> parts.add(Part(name, t, slot(name)))
            }
        }
        return Window(c.optLong("totalTokens"), max, parts, buffer)
    }

    fun slot(name: String): Int = KINDS.indexOfFirst { it.first == name }.let { if (it < 0) 7 else it }

    fun chinese(name: String): String = KINDS.firstOrNull { it.first == name }?.second ?: name

    /** The bridge's /usage, or what to say instead of it. */
    fun plan(status: Int, data: JSONObject?): Plan {
        if (status != 200 || data == null) return Plan.Note("读取失败：Claude Code 还没连上")
        return when (val error = data.optString("error")) {
            "login" -> Plan.Note("Claude Code 没有用 Claude.ai 账号登录（用 API Key 时没有这些额度）")
            "expired" -> Plan.Note("Claude Code 的登录已过期：发一条消息让它刷新，再打开")
            // No limits (said in the card) and still the resets held; only those with one left are shown.
            "" -> Plan.Limits(objects(data.optJSONArray("limits")).map { limit(it) },
                objects(data.optJSONArray("resets")).filter { it.optInt("left") > 0 }.map(::reset))
            else -> Plan.Note("读取失败（$error）")
        }
    }

    private fun objects(a: JSONArray?): List<JSONObject> = (0 until (a?.length() ?: 0)).mapNotNull { a!!.optJSONObject(it) }

    fun limit(l: JSONObject, now: OffsetDateTime = OffsetDateTime.now()): Limit {
        val pct = Math.round(l.optDouble("percent", 0.0)).coerceIn(0L, 100L).toInt()
        val severity = l.optString("severity")
        // The fill carries how near it is to running out; words say it too.
        val level = when {
            pct >= 100 || severity == "critical" || severity == "exceeded" -> Level.OUT
            pct >= 80 || severity == "warning" -> Level.LOW
            else -> Level.OK
        }
        var note = resets(l.optString("resetsAt"), now)
        if (!l.isNull("shareOfWeekly")) { // a model's own limit: at most this share of the whole week
            val share = "最多占每周额度的 " + Math.round(l.optDouble("shareOfWeekly")) + "%"
            note = if (note.isEmpty()) share else "$note · $share"
        }
        return Limit(limitName(l), pct, level, note)
    }

    fun limitName(l: JSONObject): String {
        val kind = l.optString("kind")
        val group = l.optString("group")
        // A row for one model (weekly_scoped): the bridge names it from its scope.
        val only = if (l.isNull("model")) "" else l.optString("model")
        if (only.isNotEmpty()) return if (group == "session") "5 小时额度 · $only" else "每周额度 · $only"
        return when (kind) {
            "session" -> "5 小时额度"
            "weekly_all" -> "每周额度 · 全部模型"
            "weekly_opus" -> "每周额度 · Opus"
            "weekly_sonnet" -> "每周额度 · Sonnet"
            else -> when (group) {
                "weekly" -> "每周额度 · " + kind.replace("weekly_", "")
                "session" -> "5 小时额度 · $kind"
                else -> kind
            }
        }
    }

    fun reset(g: JSONObject): Reset {
        val models = models(g)
        val left = g.optInt("left")
        val ends = expires(g.optString("endsAt"))
        return Reset(
            id = g.optString("id"),
            title = if (models.isEmpty()) "额度重置" else "$models 重置",
            left = left,
            usable = g.optBoolean("usableNow"),
            sub = if (ends.isEmpty()) "剩 $left 次" else "剩 $left 次 · $ends",
            gives = gives(g),
        )
    }

    /** The models a reset gives back the week of (the bridge's, from the limits' scopes), joined; "" for all. */
    private fun models(g: JSONObject): String {
        val m = g.optJSONArray("models")
        return (0 until (m?.length() ?: 0)).map { m!!.optString(it) }.filter { it.isNotEmpty() }.joinToString(" / ")
    }

    /** What a reset gives back, as the bridge read it from what the server says it clears. */
    fun gives(g: JSONObject): String {
        val week = when (g.optString("week")) {
            "all" -> "每周额度回满"
            "models" -> "退回本周花在 " + models(g) + " 上的额度"
            "part" -> "退回部分每周额度"
            else -> ""
        }
        val five = if (g.optBoolean("session")) "5 小时额度回满" else ""
        if (week.isEmpty()) return if (five.isEmpty()) "" else "$five。"
        return week + (if (five.isEmpty()) "" else "，$five") + "。不会提高上限，用了不能撤销。"
    }

    /** What spending a reset came to, as a toast says it. */
    fun resetResult(status: Int, data: JSONObject?): String {
        val result = if (status == 200 && data != null) data.optString("result") else ""
        val error = data?.optString("error") ?: ""
        return when (result) {
            "reset" -> "已重置"
            "already_used" -> "这次重置已经用过了"
            "not_limited" -> "现在还没用到额度，不需要重置"
            "cooldown" -> "刚重置过，稍后再试"
            "ineligible", "unavailable" -> "这次重置现在用不了"
            else -> if (error == "expired") "Claude Code 的登录已过期：发一条消息让它刷新，再试"
            else "重置失败：" + error.ifEmpty { "HTTP $status" }
        }
    }

    private fun time(iso: String?): OffsetDateTime? =
        if (iso.isNullOrEmpty() || iso == "null") null else try { OffsetDateTime.parse(iso) } catch (e: RuntimeException) { null }

    /** When an offer ends: the day and time it lapses. */
    fun expires(iso: String?): String {
        val local = time(iso)?.atZoneSameInstant(ZoneId.systemDefault()) ?: return ""
        return String.format(Locale.ROOT, "%d月%d日 %02d:%02d 过期", local.monthValue, local.dayOfMonth, local.hour, local.minute)
    }

    private val WEEK = arrayOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

    /** When it starts again: in how long, if within a day; else the day and time. */
    fun resets(iso: String?, now: OffsetDateTime = OffsetDateTime.now()): String {
        val at = time(iso) ?: return ""
        val mins = Duration.between(now, at).toMinutes()
        if (mins <= 0) return "即将重置"
        if (mins < 60) return "$mins 分钟后重置"
        if (mins < 24 * 60) return "${mins / 60} 小时 ${mins % 60} 分钟后重置"
        val local = at.atZoneSameInstant(ZoneId.systemDefault())
        return String.format(Locale.ROOT, "%d月%d日 %s %02d:%02d 重置", local.monthValue, local.dayOfMonth,
            WEEK[local.dayOfWeek.value - 1], local.hour, local.minute)
    }

    /** 1,234 → 1.2k, 1,000,000 → 1m, as Claude Code writes them. */
    @JvmStatic
    fun tokens(n: Long): String = when {
        n < 1000 -> n.toString()
        n < 1_000_000 -> trim(n / 1000.0) + "k"
        else -> trim(n / 1_000_000.0) + "m"
    }

    private fun trim(v: Double): String = String.format(Locale.ROOT, "%.1f", v).removeSuffix(".0")
}
