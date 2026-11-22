package com.jeep.claudecode

import java.time.OffsetDateTime
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** What 用量 reads from the bridge's /context and /usage, and the words it shows for them. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UsageTest {
    private fun json(s: String) = JSONObject(s)

    @Test
    fun window() {
        val w = Usage.window(json("""{"totalTokens": 50000, "maxTokens": 200000, "categories": [
            {"name": "System prompt", "tokens": 3000},
            {"name": "MCP tools", "tokens": 9000, "deferred": true},
            {"name": "Messages", "tokens": 40000},
            {"name": "Something new", "tokens": 7000},
            {"name": "Skills", "tokens": 0},
            {"name": "Autocompact buffer", "tokens": 33000},
            {"name": "Free space", "tokens": 117000}]}"""))
        // Deferred (not loaded), empty and free space are no part; the buffer is apart; an unknown kind takes the last slot.
        assertEquals(listOf(Usage.Part("System prompt", 3000, 0), Usage.Part("Messages", 40000, 6), Usage.Part("Something new", 7000, 7)), w.parts)
        assertEquals(33000L, w.buffer)
        assertEquals(25L, w.percent)
        assertEquals(117000L, w.free)
        assertEquals("消息", Usage.chinese("Messages"))
        assertEquals("Something new", Usage.chinese("Something new"))
        // No window size: never a division by zero.
        assertEquals(1L, Usage.window(json("{}")).max)
    }

    @Test
    fun planNotes() {
        assertEquals(Usage.Plan.Note("读取失败：Claude Code 还没连上"), Usage.plan(-1, null))
        assertEquals(Usage.Plan.Note("Claude Code 没有用 Claude.ai 账号登录（用 API Key 时没有这些额度）"), Usage.plan(200, json("""{"error": "login"}""")))
        assertEquals(Usage.Plan.Note("Claude Code 的登录已过期：发一条消息让它刷新，再打开"), Usage.plan(200, json("""{"error": "expired"}""")))
        assertEquals(Usage.Plan.Note("读取失败（bad reply）"), Usage.plan(200, json("""{"error": "bad reply"}""")))
    }

    @Test
    fun limits() {
        val now = OffsetDateTime.parse("2026-11-22T10:00:00Z")
        val session = Usage.limit(json("""{"kind": "session", "group": "session", "percent": 42.4, "resetsAt": "2026-11-22T12:05:00Z"}"""), now)
        assertEquals(Usage.Limit("5 小时额度", 42, Usage.Level.OK, "2 小时 5 分钟后重置"), session)
        assertEquals(Usage.Level.LOW, Usage.limit(json("""{"kind": "weekly_all", "percent": 80}"""), now).level)
        assertEquals(Usage.Level.LOW, Usage.limit(json("""{"kind": "weekly_all", "percent": 10, "severity": "warning"}"""), now).level)
        assertEquals(Usage.Level.OUT, Usage.limit(json("""{"kind": "weekly_all", "percent": 130}"""), now).let { assertEquals(100, it.percent); it.level })
        assertEquals(Usage.Level.OUT, Usage.limit(json("""{"kind": "weekly_all", "percent": 5, "severity": "exceeded"}"""), now).level)

        // A model's own limit: named from its scope, with its share of the week.
        val fable = Usage.limit(json("""{"kind": "weekly_scoped", "group": "weekly", "percent": 3, "model": "Fable", "shareOfWeekly": 40,
            "resetsAt": "2026-11-22T10:30:00Z"}"""), now)
        assertEquals("每周额度 · Fable", fable.name)
        assertEquals("30 分钟后重置 · 最多占每周额度的 40%", fable.note)
        assertEquals("5 小时额度 · Fable", Usage.limitName(json("""{"kind": "x", "group": "session", "model": "Fable"}""")))
        assertEquals("每周额度 · Opus", Usage.limitName(json("""{"kind": "weekly_opus", "model": null}""")))
        assertEquals("每周额度 · haiku", Usage.limitName(json("""{"kind": "weekly_haiku", "group": "weekly"}""")))
        assertEquals("odd", Usage.limitName(json("""{"kind": "odd"}""")))

        assertEquals("即将重置", Usage.resets("2026-11-22T09:00:00Z", now))
        assertEquals("", Usage.resets("null", now))
        assertEquals("", Usage.resets("not a time", now))
        assertTrue(Usage.resets("2026-11-25T10:00:00Z", now).endsWith("重置"))
    }

    @Test
    fun resets() {
        // No limits (said in the card) still shows the resets; one with none left is not shown.
        val plan = Usage.plan(200, json("""{"limits": [], "resets": [
            {"id": "a", "left": 1, "usableNow": true, "week": "all", "session": true, "models": []},
            {"id": "b", "left": 0, "usableNow": true, "week": "all"},
            {"id": "c", "left": 2, "usableNow": false, "week": "models", "models": ["Fable", ""], "endsAt": "null"}]}""")) as Usage.Plan.Limits
        assertTrue(plan.limits.isEmpty())
        assertEquals(listOf("a", "c"), plan.resets.map { it.id })
        val a = plan.resets[0]
        assertEquals("额度重置", a.title)
        assertEquals("剩 1 次", a.sub)
        assertEquals("每周额度回满，5 小时额度回满。不会提高上限，用了不能撤销。", a.gives)
        val c = plan.resets[1]
        assertEquals("Fable 重置", c.title)
        assertEquals(false, c.usable)
        assertEquals("退回本周花在 Fable 上的额度。不会提高上限，用了不能撤销。", c.gives)
        assertEquals("5 小时额度回满。", Usage.gives(json("""{"session": true}""")))
        assertEquals("", Usage.gives(json("{}")))
    }

    @Test
    fun resetResults() {
        assertEquals("已重置", Usage.resetResult(200, json("""{"result": "reset"}""")))
        assertEquals("这次重置现在用不了", Usage.resetResult(200, json("""{"result": "ineligible"}""")))
        assertEquals("Claude Code 的登录已过期：发一条消息让它刷新，再试", Usage.resetResult(200, json("""{"error": "expired"}""")))
        assertEquals("重置失败：network", Usage.resetResult(200, json("""{"error": "network"}""")))
        assertEquals("重置失败：HTTP -1", Usage.resetResult(-1, null))
    }

    @Test
    fun tokens() {
        assertEquals("999", Usage.tokens(999))
        assertEquals("1k", Usage.tokens(1000))
        assertEquals("1.2k", Usage.tokens(1234))
        assertEquals("200k", Usage.tokens(200_000))
        assertEquals("1m", Usage.tokens(1_000_000))
        assertEquals("1.5m", Usage.tokens(1_500_000))
    }
}
