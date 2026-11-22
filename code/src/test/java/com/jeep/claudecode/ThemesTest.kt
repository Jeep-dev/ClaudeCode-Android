package com.jeep.claudecode

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper

/** One conversation and the drawer in each palette, light or dark (SHOTS directory), to see the palettes side by side. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w392dp-h850dp-xxhdpi")
class ThemesTest {
    private val out = File(System.getProperty("shots", "build/shots"))

    private fun shot(v: View, name: String) {
        out.mkdirs()
        val b = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(b))
        FileOutputStream(File(out, "$name.png")).use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun idle() = repeat(30) { ShadowLooper.idleMainLooper(50, TimeUnit.MILLISECONDS) }

    private fun ev(chat: String, data: String): JSONObject =
        JSONObject().put("chat", chat).put("kind", "cc").put("data", JSONObject(data))

    private fun se(event: String) = "{\"type\":\"stream_event\",\"event\":$event}"

    @Test fun palettes() {
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val root = a.window.decorView
        idle()
        a.link.linked = true
        a.link.setup = null
        a.claude.home = "/root"
        a.claude.defaultCwd = "/root/photos"
        val chat = a.current
        chat.cwd = "/root/photos"

        val user = Chat.Msg(Chat.Msg.USER)
        user.text = "把 photos 里的照片按拍摄月份整理到子文件夹里，先告诉我会怎么分"
        chat.messages.add(user)
        chat.title = "按月份整理照片"
        val k = chat.key
        val evs = ArrayList<JSONObject>()
        evs.add(ev(k, "{\"type\":\"system\",\"subtype\":\"init\",\"session_id\":\"p1\",\"model\":\"claude-opus-5-5\"}"))
        evs.add(ev(k, se("{\"type\":\"message_start\",\"message\":{\"id\":\"m1\",\"usage\":{\"input_tokens\":800,\"cache_read_input_tokens\":12000}}}")))
        evs.add(ev(k, se("{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"thinking\"}}")))
        evs.add(ev(k, se("{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"thinking_delta\",\"thinking\":\"先看有多少照片、有没有 EXIF 时间。\"}}")))
        evs.add(ev(k, se("{\"type\":\"content_block_stop\",\"index\":0}")))
        evs.add(ev(k, se("{\"type\":\"content_block_start\",\"index\":1,\"content_block\":{\"type\":\"tool_use\",\"id\":\"t1\",\"name\":\"Bash\",\"input\":{}}}")))
        evs.add(ev(k, se("{\"type\":\"content_block_delta\",\"index\":1,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"command\\\":\\\"exiftool -T -DateTimeOriginal *.jpg | cut -c1-7 | sort | uniq -c\\\",\\\"description\\\":\\\"按月份数照片\\\"}\"}}")))
        evs.add(ev(k, se("{\"type\":\"content_block_stop\",\"index\":1}")))
        evs.add(ev(k, "{\"type\":\"user\",\"message\":{\"content\":[{\"type\":\"tool_result\",\"tool_use_id\":\"t1\",\"content\":\"  41 2026:07\\n 128 2026:08\\n  63 2026:09\\n  17 2026:10\"}]}}"))
        evs.add(ev(k, se("{\"type\":\"message_start\",\"message\":{\"id\":\"m2\",\"usage\":{\"input_tokens\":600,\"cache_read_input_tokens\":12800}}}")))
        evs.add(ev(k, se("{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}")))
        val md = "一共 **249 张**，都有拍摄时间，会这样分：\\n\\n| 文件夹 | 张数 |\\n|---|---|\\n| 2026-07 | 41 |\\n| 2026-08 | 128 |\\n| 2026-09 | 63 |\\n| 2026-10 | 17 |\\n\\n" +
            "8 月最多，大概是暑假出去玩的那批。用这条命令移动，文件名不变：\\n\\n```bash\\nexiftool '-Directory<DateTimeOriginal' \\\\\\n  -d %Y-%m *.jpg\\n```\\n\\n要我现在执行吗？`-d` 后面的格式也可以改成 `%Y/%m`，按年再分一层。"
        evs.add(ev(k, se("{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"" + md + "\"}}")))
        evs.add(ev(k, "{\"type\":\"result\",\"subtype\":\"success\",\"session_id\":\"p1\",\"usage\":{\"output_tokens\":312},\"modelUsage\":{\"claude-opus-5-5\":{\"inputTokens\":1400,\"cacheReadInputTokens\":24800,\"contextWindow\":1000000}}}"))
        a.events(evs)
        idle()

        val now = System.currentTimeMillis() / 1000
        a.drawer.sessions = JSONArray("[{\"id\":\"p1\",\"title\":\"按月份整理照片\",\"cwd\":\"/root/photos\",\"mtime\":" + now + "}," +
            "{\"id\":\"p2\",\"title\":\"给番茄钟加一个白噪音开关\",\"cwd\":\"/root/work/pomodoro\",\"mtime\":" + (now - 5400) + "}," +
            "{\"id\":\"p3\",\"title\":\"这个月的电费为什么这么高\",\"cwd\":\"/root\",\"mtime\":" + (now - 90000) + "}," +
            "{\"id\":\"p4\",\"title\":\"把读书笔记导出成 Markdown\",\"cwd\":\"/root/notes\",\"mtime\":" + (now - 200000) + "}," +
            "{\"id\":\"p5\",\"title\":\"日语 N3 单词卡片\",\"cwd\":\"/root/notes\",\"mtime\":" + (now - 900000) + "}]")

        // Each palette in the light or dark it is shown in; Claude's dark also on pure black.
        val looks = listOf("sakura" to 1, "ocean" to 2, "spring" to 1, "autumn" to 1,
            "black" to 2, "minimal" to 1, "claude" to 1, "claude" to 2, "sakura" to 2)
        for ((id, mode) in looks) {
            Themes.set(a, id)
            Themes.setMode(a, mode)
            a.restyle()
            idle()
            val name = id + if (mode == 2) "-dark" else ""
            shot(root, "$name-chat")
            a.drawer.renderSessions()
            a.frame.open()
            idle()
            shot(root, "$name-drawer")
            a.frame.close()
            idle()
        }
        Themes.setPureBlack(a, true)
        a.restyle()
        idle()
        shot(root, "claude-black-chat")
        Themes.setPureBlack(a, false)
        Themes.set(a, "claude")
        Themes.setMode(a, 0)
    }
}
