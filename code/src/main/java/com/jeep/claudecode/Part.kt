package com.jeep.claudecode

import android.content.SharedPreferences
import android.os.Handler
import java.util.concurrent.ExecutorService
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * What the parts of the screen share. MainActivity keeps the state of the app (the link to the bridge, the
 * conversations) and is made of parts, each a piece of the interface or of what it does. A part is built for
 * the activity (app), reaches the others through it, and has by name what nearly all of them use.
 */
abstract class Part(@JvmField val app: MainActivity) {
    @JvmField val bridge: Bridge = app.bridge
    @JvmField val userland: Userland = app.userland
    @JvmField val prefs: SharedPreferences = app.prefs
    @JvmField val ui: Handler = app.ui
    @JvmField val work: ExecutorService = app.work
    /** What Claude Code is here, and the sheets' building blocks. Made first: Claude has neither of them, Sheets has no sheets. */
    val claude: Claude get() = app.claude
    val sheets: Sheets get() = app.sheets

    init {
        // They are made first (MainActivity.onCreate): a part made before them would find none.
        if ((!app.hasClaude && this !is Claude) || (!app.hasSheets && this !is Claude && this !is Sheets)) {
            throw IllegalStateException(javaClass.simpleName + " is made before Claude and Sheets")
        }
    }

    fun dp(v: Float): Int = Ui.dp(app, v)

    fun dp(v: Int): Int = Ui.dp(app, v.toFloat())

    fun col(res: Int): Int = Ui.color(app, res)

    fun toast(text: String) = app.toast(text)

    /** A list kept in the preferences as JSON (the tools Claude Code reported, the pinned conversations). */
    fun savedArray(key: String): JSONArray = try {
        JSONArray(prefs.getString(key, "[]"))
    } catch (e: JSONException) {
        JSONArray()
    }

    companion object {
        /** A JSON object from keys and values (a null value as JSON null). */
        @JvmStatic
        fun obj(vararg kv: Any?): JSONObject {
            val o = JSONObject()
            try {
                var i = 0
                while (i < kv.size) {
                    o.put(kv[i] as String, kv[i + 1] ?: JSONObject.NULL)
                    i += 2
                }
            } catch (ignored: JSONException) {
                // keys are strings
            }
            return o
        }
    }
}
