package com.jeep.claudecode

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowLooper

/** Renders the main screens to PNG files (SHOTS directory) for a visual check. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w392dp-h850dp-xxhdpi")
class ScreensTest {
    private val out = File(System.getProperty("shots", "build/shots"))

    private fun shot(v: View, name: String) {
        out.mkdirs()
        val b = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(b))
        FileOutputStream(File(out, "$name.png")).use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** The latest sheet's window, as a picture. */
    private fun shotSheet(name: String) = shot(ShadowDialog.getLatestDialog().window!!.decorView, name)

    @Suppress("unused") // for looking into a screen while writing a test
    private fun dump(v: View, depth: Int) {
        if (depth > 7) return
        println("DUMP " + "  ".repeat(depth) + v.javaClass.simpleName + " " + v.width + "x" + v.height +
            " vis=" + v.visibility + (if (v is TextView) " '" + v.text + "'" else ""))
        if (v is ViewGroup) for (i in 0 until v.childCount) dump(v.getChildAt(i), depth + 1)
    }

    // The parts' private fields and methods.
    private fun set(o: Any, field: String, value: Any?) =
        o.javaClass.getDeclaredField(field).apply { isAccessible = true }.set(o, value)

    private fun call(o: Any, method: String, vararg args: Any?) {
        val m = o.javaClass.declaredMethods.single { it.name == method && it.parameterCount == args.size }
        m.isAccessible = true
        m.invoke(o, *args)
    }

    private fun idle() = repeat(30) { ShadowLooper.idleMainLooper(50, TimeUnit.MILLISECONDS) }

    private fun ev(chat: String, kind: String, data: String?): JSONObject =
        JSONObject().put("chat", chat).put("kind", kind).put("data", if (data == null) JSONObject.NULL else JSONObject(data))

    private fun se(event: String) = "{\"type\":\"stream_event\",\"event\":$event}"

    private fun top(a: MainActivity) {
        a.screen.list.scrollToPosition(0)
        a.screen.stick = false
    }

    /** Opening the app with the immersive layout and the title chosen (a cold start, not a switch). */
    @Test fun startsImmersive() {
        val c = RuntimeEnvironment.getApplication()
        Themes.setImmersive(c, true)
        Themes.setShowTitle(c, true)
        try {
            val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
            idle()
            shot(a.window.decorView, "00-start-immersive")
        } finally {
            Themes.setImmersive(c, false)
            Themes.setShowTitle(c, false)
        }
    }

    @Test fun screens() {
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val root = a.window.decorView
        idle()
        shot(root, "01-setup")

        a.link.linked = true
        a.link.setup = null
        a.claude.home = "/data/data/com.termux/files/home"
        a.claude.defaultCwd = "/data/data/com.termux/files/home/work"
        a.claude.defaultModel = "deepseek-flash[1m]"
        val chat = a.current
        chat.cwd = "/data/data/com.termux/files/home/work"
        a.renderNow()
        idle()
        shot(root, "02-hero")

        // A conversation, through the same events the bridge sends.
        val user = Chat.Msg(Chat.Msg.USER)
        user.text = "帮我看看 work 里有什么，再写个统计行数的脚本"
        chat.messages.add(user)
        chat.title = "帮我看看 work 里有什么"
        chat.running = true
        chat.startedAt = System.currentTimeMillis() - 7000
        val k = chat.key
        val md = "文件夹里有 **2 个文件**：\\n\\n| 文件 | 大小 |\\n|---|---|\\n| notes.txt | 210 B |\\n| pelican-bike.html | 1.8 KB |\\n\\n统计行数的脚本已经写好，保存为 `count.py`：\\n\\n```python\\nimport sys\\n\\nfor path in sys.argv[1:]:\\n    with open(path) as f:\\n        print(path, sum(1 for _ in f))\\n```\\n\\n运行：`python count.py *.txt`。"
        val evs = ArrayList<JSONObject>()
        evs.add(ev(k, "cc", "{\"type\":\"system\",\"subtype\":\"init\",\"session_id\":\"s1\",\"model\":\"claude-opus-5-5[1m]\",\"slash_commands\":[\"compact\",\"context\",\"cost\",\"init\",\"simplify\",\"debug\"]}"))
        evs.add(ev(k, "cc", se("{\"type\":\"message_start\",\"message\":{\"id\":\"m1\",\"usage\":{\"input_tokens\":1200,\"cache_read_input_tokens\":18000}}}")))
        evs.add(ev(k, "cc", se("{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"thinking\"}}")))
        evs.add(ev(k, "cc", se("{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"thinking_delta\",\"thinking\":\"用户想看目录内容，然后写脚本。先 ls。\"}}")))
        evs.add(ev(k, "cc", se("{\"type\":\"content_block_stop\",\"index\":0}")))
        evs.add(ev(k, "cc", se("{\"type\":\"content_block_start\",\"index\":1,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}")))
        evs.add(ev(k, "cc", se("{\"type\":\"content_block_delta\",\"index\":1,\"delta\":{\"type\":\"text_delta\",\"text\":\"先看一下文件夹里的内容。\"}}")))
        evs.add(ev(k, "cc", se("{\"type\":\"content_block_start\",\"index\":2,\"content_block\":{\"type\":\"tool_use\",\"id\":\"t1\",\"name\":\"Bash\",\"input\":{}}}")))
        evs.add(ev(k, "cc", se("{\"type\":\"content_block_delta\",\"index\":2,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"command\\\":\\\"ls -la\\\",\\\"description\\\":\\\"列出 work 文件夹\\\"}\"}}")))
        evs.add(ev(k, "cc", "{\"type\":\"user\",\"message\":{\"content\":[{\"type\":\"tool_result\",\"tool_use_id\":\"t1\",\"content\":\"total 12\\ndrwx------ 3 u0_a1 u0_a1 4096 .\\n-rw------- 1 u0_a1 u0_a1  210 notes.txt\\n-rw------- 1 u0_a1 u0_a1 1830 pelican-bike.html\"}]}}"))
        evs.add(ev(k, "cc", se("{\"type\":\"message_start\",\"message\":{\"id\":\"m2\",\"usage\":{\"input_tokens\":900,\"cache_read_input_tokens\":19800}}}")))
        evs.add(ev(k, "cc", se("{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"tool_use\",\"id\":\"t2\",\"name\":\"Write\",\"input\":{}}}")))
        evs.add(ev(k, "cc", se("{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"file_path\\\":\\\"/data/data/com.termux/files/home/work/count.py\\\",\\\"content\\\":\\\"import sys\\\"}\"}}")))
        evs.add(ev(k, "cc", "{\"type\":\"user\",\"message\":{\"content\":[{\"type\":\"tool_result\",\"tool_use_id\":\"t2\",\"content\":\"File created\"}]}}"))
        evs.add(ev(k, "cc", se("{\"type\":\"content_block_start\",\"index\":1,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}")))
        evs.add(ev(k, "cc", se("{\"type\":\"content_block_delta\",\"index\":1,\"delta\":{\"type\":\"text_delta\",\"text\":\"" + md + "\"}}")))
        a.events(evs)
        chat.queue.add(Chat.Queued("顺便把 notes.txt 也统计一下", ArrayList()))
        a.renderNow()
        idle()
        shot(root, "03-running")
        chat.queue.clear()

        a.events(listOf(ev(k, "cc", "{\"type\":\"result\",\"subtype\":\"success\",\"session_id\":\"s1\",\"usage\":{\"output_tokens\":406},\"modelUsage\":{\"claude-opus-5-5[1m]\":{\"inputTokens\":2100,\"cacheReadInputTokens\":37800,\"contextWindow\":1000000}}}")))
        idle()
        shot(root, "04-done")

        // Expand the tool and the thinking
        for (m in chat.messages) for (b in m.blocks) if (b.kind != Chat.Block.TEXT) b.open = true
        a.renderNow()
        idle()
        top(a)
        idle()
        shot(root, "05-expanded")

        // Drawer
        val now = System.currentTimeMillis() / 1000
        a.drawer.sessions = JSONArray("[{\"id\":\"s1\",\"title\":\"帮我看看 work 里有什么\",\"cwd\":\"/data/data/com.termux/files/home/work\",\"mtime\":" + now + "}," +
            "{\"id\":\"s2\",\"title\":\"写一个鹈鹕骑自行车的 SVG 动画\",\"cwd\":\"/data/data/com.termux/files/home/work\",\"mtime\":" + (now - 3600) + "}," +
            "{\"id\":\"s3\",\"title\":\"Xed-Editor 中文翻译\",\"cwd\":\"/data/data/com.termux/files/home/Xed-Editor\",\"mtime\":" + (now - 90000) + "}," +
            "{\"id\":\"s4\",\"title\":\"termux 里装 claude code\",\"cwd\":\"/data/data/com.termux/files/home\",\"mtime\":" + (now - 400000) + "}]")
        a.drawer.renderSessions()
        a.frame.open()
        idle()
        shot(root, "06-drawer")
        a.frame.close()
        idle()

        // Slash menu and a note
        val input = a.box.input
        input.setText("/")
        idle()
        shot(root, "07-slash")
        input.setText("/effort")
        a.sender.send()
        idle()
        shot(root, "08-note")

        // Every tool's row
        val tools = Chat("tools")
        val tm = Chat.Msg(Chat.Msg.ASSISTANT)
        val names = listOf(
            "Bash" to "{\"command\":\"ls\",\"description\":\"List files\"}",
            "Read" to "{\"file_path\":\"/w/a.py\"}",
            "Write" to "{\"file_path\":\"/w/b.py\"}",
            "Edit" to "{\"file_path\":\"/w/c.py\"}",
            "MultiEdit" to "{\"file_path\":\"/w/d.py\"}",
            "NotebookEdit" to "{\"notebook_path\":\"/w/e.ipynb\"}",
            "Glob" to "{\"pattern\":\"**/*.py\"}",
            "Grep" to "{\"pattern\":\"TODO\"}",
            "WebFetch" to "{\"url\":\"https://example.com\"}",
            "WebSearch" to "{\"query\":\"termux python\"}",
            "Task" to "{\"description\":\"Explore repo\"}",
            "TodoWrite" to "{}",
            "Skill" to "{\"skill\":\"simplify\"}",
            "mcp__exa__web_fetch_exa" to "{\"urls\":[\"https://api-docs.deepseek.com/quick_start/pricing\"],\"maxCharacters\":1500}")
        for ((tool, json) in names) {
            tm.blocks.add(Chat.Block(Chat.Block.THINKING).apply { text = "想一下" })
            tm.blocks.add(Chat.Block(Chat.Block.TOOL).apply { name = tool; this.input = JSONObject(json) })
        }
        val png = "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAUAAAADICAIAAAAWZq/8AAAD2klEQVR4nO3ZMW5UWRBAUUDeCItgLeQTkDp0NsHsgJRgNsQKiNgKgSWL0TCMZZr/6r53TuzurpL6qn7Lr788vH8FNL1ZPQDwcgKGMAFDmIAhTMAQJmAIEzCECRjCBAxhAoYwAUOYgCFMwBAmYAgTMIQJGMIEDGEChjABQ9jdr7z47V9/32oOONnXP/942QtdYAgTMIQJGMIEDGEChjABQ5iAIUzAECZgCBMwhAkYwgQMYQKGMAFDmIAhTMAQJmAIEzCECRjCBAxhAoYwAUOYgCFMwBAmYAgTMIQJGMIEDGEChjABQ5iAIUzAECZgCBMwhAkYwgQMYQKGMAFDmIAh7G71ANzSu48fnvNnn+8//e5JuIaA854Z7X+9RMxpAq56Qbc/fx8lFwk45lbd/uSdlRwi4Izfl+4PP0jGCQIOuCzdf3+ojIfzb6TpltQ75NP5Xy7wXEPicYonc4GHGlLvk2nz8EjAE82sZeZUh/MIPcvwSDxOT+MCDzK83ieVOU8g4ClaVbSm3ZiARyj2UJx5PwKGMAGv1z1l3cm3IeDF6g3U568T8Ep7fPv32CJKwBAm4GV2Olw77dIi4DX2+8bvt1GCgCFMwAvseqx23WsyAUOYgK+295nae7uBBAxhAoYwAV/qhCfME3acQ8AQJmAIE/B1znm2PGfT5QQMYQKGMAFDmIAvctrPwtP2XUXAECZgCBMwhAkYwgQMYQKGMAFDmIAhTMAQJmAIEzCECRjCBHyRz/efVo9wqdP2XUXAECZgCBMwhAn4Ouf8LDxn0+UEDGEChjABX+qEZ8sTdpxDwBAmYAgT8NX2fsLce7uBBAxhAl5g1zO1616TCRjCBLzGfsdqv40SBLzMTt/4nXZpETCECXilPQ7XHltECXix+re/Pn+dgNfrNtCdfBsChjABj1A8ZcWZ9yPgKVo9tKbdmIAHqVRRmfMEd6sH4B8e23j38cPqQX5MutO4wBPN7GTmVIcT8FDTapk2D488Qs815HFaupO5wNOt7Ue9w7nAAUtOsXQTBJxxWcbSDRFwzFNdNy9Zt0UCrrpVybpNE3De9wU+M2bRbkPAW1HmafwbCcIEDGEChjABQ5iAIUzAECZgCBMwhAkYwgQMYQKGMAFDmIAhTMAQJmAIEzCECRjCBAxhAoYwAUOYgCFMwBAmYAgTMIQJGMIEDGEChjABQ5iAIUzAECZgCBMwhAkYwgQMYQKGMAFDmIAhTMAQJmAIe/3l4f3qGYAXcoEhTMAQJmAIEzCECRjCBAxhAoYwAUOYgCFMwBAmYAgTMIQJGMIEDGEChjABQ5iAIUzAEPYNbUmr08oXlSsAAAAASUVORK5CYII="
        fun picture(open: Boolean) = Chat.Block(Chat.Block.TOOL).apply {
            name = "Read"
            this.input = JSONObject("{\"file_path\":\"/root/work/shot.png\"}")
            result = "[图片]"
            images.add(png)
            this.open = open
        }
        tm.blocks.add(0, picture(false))
        tm.blocks.add(1, picture(true))
        tm.blocks.add(2, Chat.Block(Chat.Block.TEXT).apply {
            text = "画好了：\n\n![图](" + png + ")\n\n![没有的](/root/work/none.png)\n\n![网上的](https://example.com/a.png)"
        })
        tm.blocks.add(3, Chat.Block(Chat.Block.TOOL).apply { // Claude Code asks whether it may run
            name = "Bash"
            this.input = JSONObject("{\"command\":\"rm -rf build\",\"description\":\"Clean the build\"}")
            running = true
            ask = "request-1"
        })
        tm.done = true
        tools.messages.add(tm)
        a.switchTo(tools)
        idle()
        shot(root, "10-tools")
        top(a)
        idle()
        shot(root, "10-tools-top")
        Themes.setShowThinking(a, false)
        a.renderNow()
        idle()
        top(a)
        idle()
        shot(root, "10-tools-nothinking")
        Themes.setShowThinking(a, true)
        a.renderNow()
        idle()
        a.switchTo(chat)
        idle()

        a.settings.settingsSheet()
        idle()
        val d = ShadowDialog.getLatestDialog()
        val sheet = d.window!!.decorView
        shot(sheet, "09-settings")
        val settingsFile = a.claude.settingsFile()
        settingsFile.parentFile!!.mkdirs()
        Userland.write(settingsFile, "{\"env\":{\"ANTHROPIC_BASE_URL\":\"https://api.deepseek.com/anthropic\",\"ANTHROPIC_AUTH_TOKEN\":\"sk-1234567890abcdef\",\"DISABLE_TELEMETRY\":\"1\"}," +
            "\"alwaysThinkingEnabled\":true,\"cleanupPeriodDays\":90,\"permissions\":{\"allow\":[\"Bash\"]},\"effortLevel\":\"high\"}")
        set(a.claudePage, "schema", JSONArray("[{\"key\":\"verbose\",\"type\":\"bool\",\"desc\":\"Show full tool output instead of truncated summaries\"}," +
            "{\"key\":\"effortLevel\",\"type\":\"enum\",\"choices\":[\"low\",\"medium\",\"high\",\"xhigh\"],\"desc\":\"Persisted effort level for supported models.\"}," +
            "{\"key\":\"cleanupPeriodDays\",\"type\":\"number\",\"desc\":\"Number of days to retain chat transcripts before automatic cleanup (default: 30).\"}]"))
        for (page in listOf("look", "talk", "model", "claude", "storage", "about")) {
            set(a.settings, "settingsStill", 0L) // the clock stands still here: no move is ever over
            call(a.settings, "settingsOpen", page)
            idle()
            shot(sheet, "09-settings-$page")
        }
        call(a.claudePage, "settingSheet", "effortLevel")
        idle()
        shotSheet("09-setting-enum")
        ShadowDialog.getLatestDialog().dismiss()
        call(a.claudePage, "addSettingSheet")
        idle()
        shotSheet("09-setting-add")
        ShadowDialog.getLatestDialog().dismiss()
        val land = a.userland
        val claudeJson = land.file(Userland.HOME + "/.claude.json")
        claudeJson.parentFile!!.mkdirs()
        Userland.write(claudeJson, "{\"mcpServers\":{\"exa\":{\"type\":\"http\",\"url\":\"https://mcp.exa.ai/mcp\"}},\"projects\":{}}")
        Userland.write(land.file(Userland.HOME + "/.claude/skills/pdf-tools/SKILL.md"), "---\nname: pdf-tools\n---\n")
        a.getSharedPreferences("app", 0).edit().putString("skills",
            "[\"pdf-tools\",\"simplify\",\"code-review\",\"security-review\",\"document-skills:xlsx\"]").apply()
        a.skills.skillsSheet()
        idle()
        shotSheet("09-setting-skills")
        ShadowDialog.getLatestDialog().dismiss()
        Userland.write(land.file(Userland.HOME + "/.claude/agents/reviewer.md"), "---\nname: reviewer\n---\n")
        Userland.write(land.file(Userland.HOME + "/.claude/commands/commit.md"), "提交\n")
        Userland.write(land.file(Userland.HOME + "/.claude/commands/git/push.md"), "推送\n")
        a.getSharedPreferences("app", 0).edit()
            .putString("agents", "[\"general-purpose\",\"Explore\",\"Plan\",\"reviewer\",\"document-skills:writer\"]")
            .putString("slashCommands", "[\"help\",\"clear\",\"compact\",\"commit\",\"git:push\",\"simplify\",\"document-skills:export\"]").apply()
        val withHooks = JSONObject(Userland.read(FileInputStream(settingsFile)))
        withHooks.put("hooks", JSONObject("{\"PostToolUse\":[{\"matcher\":\"Edit|Write\",\"hooks\":[{\"type\":\"command\",\"command\":\"prettier --write\"}]}],\"Stop\":[{\"hooks\":[{\"type\":\"command\",\"command\":\"notify-send done\"}]}]}"))
        Userland.write(settingsFile, withHooks.toString())
        for (kind in listOf("agents", "commands")) {
            call(a.ext, "itemsSheet", kind)
            idle()
            shotSheet("09-setting-$kind")
            ShadowDialog.getLatestDialog().dismiss()
        }
        call(a.ext, "hooksSheet")
        idle()
        shotSheet("09-setting-hooks")
        ShadowDialog.getLatestDialog().dismiss()
        call(a.settings, "settingsOpen", "ext")
        idle()
        shot(sheet, "09-settings-ext")
        a.mcp.mcpSheet()
        idle()
        shotSheet("09-setting-mcp")
        ShadowDialog.getLatestDialog().dismiss()
        call(a.claudePage, "envSheet")
        idle()
        shotSheet("09-setting-env")
        ShadowDialog.getLatestDialog().dismiss()
        call(a.settings, "settingsOpen", null)
        idle()
        d.dismiss()

        a.claude.applyConfig(JSONObject("{\"models\":[\"deepseek-flash[1m]\",\"deepseek-flash\"],\"defaultModel\":\"deepseek-flash[1m]\"," +
            "\"efforts\":[\"low\",\"medium\",\"high\",\"xhigh\",\"max\"],\"effort\":\"max\"," +
            "\"permissionModes\":[\"acceptEdits\",\"auto\",\"bypassPermissions\",\"manual\",\"dontAsk\",\"plan\"]," +
            "\"claudeModels\":[{\"value\":\"default\",\"displayName\":\"Default (recommended)\",\"resolvedModel\":\"claude-opus-5-5\"}," +
            "{\"value\":\"opus\",\"displayName\":\"Opus\",\"resolvedModel\":\"claude-opus-5-5\",\"description\":\"Opus 5.5 · Best for everyday\"},{\"value\":\"fable\",\"displayName\":\"Fable\"}," +
            "{\"value\":\"sonnet\",\"displayName\":\"Sonnet\",\"description\":\"Sonnet 5.5 · Efficient\"},{\"value\":\"haiku\",\"displayName\":\"Haiku\"}]}"))
        a.pickers.modelSheet()
        idle()
        shotSheet("11-model")
        ShadowDialog.getLatestDialog().dismiss()

        // Each palette, light then dark; Claude's dark on pure black too.
        for (t in Themes.ALL) {
            Themes.set(a, t.id)
            for (mode in 1..2) {
                Themes.setMode(a, mode)
                a.restyle()
                idle()
                shot(a.window.decorView, "12-" + t.id + if (mode == 2) "-dark" else "")
            }
        }
        Themes.setPureBlack(a, true)
        a.restyle()
        idle()
        shot(a.window.decorView, "12-claude-black")
        Themes.setPureBlack(a, false)
        Fonts.setSize(a, 2)
        a.restyle()
        idle()
        shot(a.window.decorView, "13-large")
        a.screen.list.scrollBy(0, -400)
        a.screen.showJump()
        ShadowLooper.idleMainLooper(300, TimeUnit.MILLISECONDS)
        shot(a.window.decorView, "14-jump")
        Fonts.setSize(a, 1)
        Themes.setMode(a, 0)
        Themes.setImmersive(a, true)
        a.restyle()
        idle()
        shot(a.window.decorView, "15-immersive")
        Themes.setMode(a, 2)
        a.restyle()
        idle()
        shot(a.window.decorView, "15-immersive-dark")
        Themes.setShowTitle(a, true)
        a.restyle()
        idle()
        shot(a.window.decorView, "15-immersive-title")
        Themes.setShowTitle(a, false)
        Themes.setMode(a, 0)
        Themes.setImmersive(a, false)
    }
}
