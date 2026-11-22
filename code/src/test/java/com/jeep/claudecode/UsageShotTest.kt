package com.jeep.claudecode

import android.graphics.Bitmap
import android.graphics.Canvas
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper

/**
 * 用量 (drawn with Compose) as a picture, light and dark, to the SHOTS directory: by hand on CI
 * (gh workflow run screens.yml -f tests=UsageShotTest); Robolectric's native graphics are not on every machine.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w392dp-h850dp-xxhdpi")
class UsageShotTest {
    private val out = File(System.getProperty("shots", "build/shots"))

    private fun idle() = repeat(30) { ShadowLooper.idleMainLooper(50, TimeUnit.MILLISECONDS) }

    private fun shoot(name: String) {
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        idle()
        val cards = a.usage.open()
        cards.window.value = UsageSheet.Shown.Ready(Usage.window(JSONObject("""{"totalTokens": 61000, "maxTokens": 200000, "categories": [
            {"name": "System prompt", "tokens": 3100}, {"name": "System tools", "tokens": 17800}, {"name": "MCP tools", "tokens": 2400},
            {"name": "Memory files", "tokens": 1900}, {"name": "Skills", "tokens": 1200}, {"name": "Messages", "tokens": 34600},
            {"name": "Autocompact buffer", "tokens": 33000}, {"name": "Free space", "tokens": 106000}]}""")))
        cards.plan.value = UsageSheet.Shown.Ready(Usage.plan(200, JSONObject("""{"limits": [
            {"kind": "session", "group": "session", "percent": 37, "resetsAt": "2099-01-01T00:00:00Z"},
            {"kind": "weekly_all", "group": "weekly", "percent": 84, "severity": "warning"},
            {"kind": "weekly_scoped", "group": "weekly", "percent": 100, "model": "Fable", "shareOfWeekly": 40}],
            "resets": [{"id": "a", "left": 2, "total": 3, "usableNow": true, "week": "models", "models": ["Fable"], "session": true,
                        "endsAt": "2099-01-31T15:00:00Z"},
                       {"id": "b", "left": 1, "usableNow": false, "week": "all"}]}""")))
        idle()
        val root = cards.sheet.window!!.decorView
        out.mkdirs()
        val b = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(b))
        FileOutputStream(File(out, "$name.png")).use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun light() = shoot("usage-light")

    @Test @Config(qualifiers = "+night") fun dark() = shoot("usage-dark")
}
