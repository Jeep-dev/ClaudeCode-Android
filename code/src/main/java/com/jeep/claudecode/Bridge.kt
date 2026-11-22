package com.jeep.claudecode

import android.content.Context
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Base64
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min
import org.json.JSONException
import org.json.JSONObject

/**
 * The Claude Code bridge (assets/bridge.py), run by [BridgeService] inside this app's
 * userland and reached over HTTP on a Unix socket in this app's own files: no other app can
 * reach it, so nothing is signed. Results and events arrive on the main thread.
 */
class Bridge(context: Context, private val listener: Events) {
    fun interface Reply {
        fun done(status: Int, data: JSONObject)
    }

    interface Events {
        fun events(batch: List<JSONObject>)
        fun link(up: Boolean)
    }

    private fun interface Body {
        @Throws(IOException::class)
        fun write(out: OutputStream)
    }

    /** What an exchange got: its status (or one of the failures below 0) and its body. */
    private class Result(val status: Int, val body: String)

    private val context: Context = context.applicationContext
    private val userland = Userland(this.context)
    /** [SOCKET] as a path of this app's files. */
    private val socket = userland.file(SOCKET).path
    private val pool = Executors.newCachedThreadPool()
    private val ui = Handler(Looper.getMainLooper())
    /** The event stream is being read: set once, so two connects never start two readers. */
    private val reading = AtomicBoolean()
    /** Last event seen; -1 asks only for events from now on (nothing from before this launch). */
    @Volatile private var lastSeq = -1L
    /** The start of the bridge lastSeq counts in (its hello): a count of another bridge starts over. */
    @Volatile private var lastBridge: String? = null
    private val pending = ArrayList<JSONObject>()
    private var flushQueued = false
    /** When this app last asked for the bridge to start: a start still under way is not repeated. */
    @Volatile private var startedAt = 0L

    /** VERSION in the bridge.py this app carries: a running bridge of another version is replaced. */
    private var version = 0

    /** Read once, on the first check (off the main thread). */
    private fun version(): Int {
        if (version == 0) version = bundledVersion(context)
        return version
    }

    fun get(path: String, reply: Reply?) = request("GET", path, null, reply)

    fun post(path: String, body: JSONObject, reply: Reply?) = request("POST", path, body.toString(), reply)

    private fun request(method: String, path: String, body: String?, reply: Reply?) {
        pool.execute { answer(exchange(method, path, body), "$method ${path.substringBefore('?')}", reply) }
    }

    private fun answer(r: Result, route: String, reply: Reply?) {
        val data = try {
            JSONObject(r.body)
        } catch (e: JSONException) {
            JSONObject()
        }
        if (reply != null) ui.post { traced("cc bridge $route") { reply.done(r.status, data) } }
    }

    /**
     * Saves a file the user attached into the folder, its bytes streamed from where it is (never
     * held whole); the reply has the path it got.
     */
    fun upload(cwd: String, name: String, uri: Uri, reply: Reply?) {
        pool.execute {
            val resolver = context.contentResolver
            var spool: File? = null
            try {
                var length = -1L
                try {
                    resolver.openAssetFileDescriptor(uri, "r")?.use { fd -> length = fd.length }
                } catch (ignored: IOException) {
                    // not known: copied aside first
                } catch (ignored: RuntimeException) {
                    // not known: copied aside first
                }
                if (length < 0) {
                    // The bridge needs the length first: a stream of unknown length is copied aside.
                    val aside = File.createTempFile("upload", null, context.cacheDir)
                    spool = aside
                    (resolver.openInputStream(uri) ?: throw IOException("unreadable")).use { input ->
                        FileOutputStream(aside).use { out -> Userland.copy(input, out) }
                    }
                    length = aside.length()
                }
                val from = spool
                val path = "/upload?cwd=" + Uri.encode(cwd) + "&name=" + Uri.encode(name)
                answer(exchange("POST", path, "application/octet-stream", length) { out ->
                    val input = if (from != null) FileInputStream(from) else resolver.openInputStream(uri)
                    (input ?: throw IOException("unreadable")).use { Userland.copy(it, out) }
                }, "POST /upload", reply)
            } catch (e: IOException) {
                answer(Result(400, JSONObject().put("error", "读不了这个文件").toString()), "POST /upload", reply)
            } catch (e: RuntimeException) {
                answer(Result(400, JSONObject().put("error", "读不了这个文件").toString()), "POST /upload", reply)
            } finally {
                spool?.delete()
            }
        }
    }

    /** A picture of a saved conversation by its reference (ccimg:…), read from the bridge; null when it cannot be. */
    fun image(ref: String): ByteArray? {
        val p = ref.split(":")
        if (p.size != 4) return null
        val r = exchange("GET", "/image?id=" + Uri.encode(p[1]) + "&at=" + p[2] + "&n=" + p[3], null)
        if (r.status != 200) return null
        return try {
            Base64.decode(JSONObject(r.body).optString("data"), Base64.DEFAULT)
        } catch (e: JSONException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    private fun exchange(method: String, path: String, body: String?): Result {
        if (body == null) return exchange(method, path, null, 0, null)
        val bytes = body.toByteArray()
        return exchange(method, path, "application/json", bytes.size.toLong()) { out -> out.write(bytes) }
    }

    /**
     * One HTTP exchange; status REFUSED when the bridge's socket could not be connected to (the
     * error's text as the body), -1 when the bridge cannot be reached
     * otherwise, -2 when it does not answer in time.
     */
    private fun exchange(method: String, path: String, type: String?, length: Long, body: Body?): Result {
        // Plugins and MCP servers are set up by Claude Code itself, and a file is written whole
        // before the answer: these may take minutes.
        val timeout = when {
            path.startsWith("/health") -> 3000
            path.startsWith("/plugin") || path.startsWith("/mcp") || path.startsWith("/upload") -> 310_000
            else -> 30000
        }
        val start = System.currentTimeMillis()
        return try {
            send(method, path, type, length, body, timeout).use { a -> Result(a.status, readBody(a)) }
        } catch (e: Refused) {
            Result(REFUSED, e.message.toString())
        } catch (e: IOException) {
            // A read that timed out is only an error on a LocalSocket: told by the time it took.
            Result(if (System.currentTimeMillis() - start >= timeout - 50) -2 else -1, "")
        }
    }

    /** Nothing listens on the bridge's socket (or there is none). */
    private class Refused(cause: IOException) : IOException(cause.message, cause)

    /** An answer whose head is read: its status and length; the body is read from in. */
    private class Answer(val socket: LocalSocket) : Closeable {
        val input: InputStream = BufferedInputStream(socket.inputStream, 16384)
        var status = 0
        var length = -1L

        override fun close() {
            try {
                socket.close()
            } catch (ignored: IOException) {
                // closed
            }
        }
    }

    /** Sends one request on a new connection (closed after the answer) and reads the answer's head. */
    @Throws(IOException::class)
    private fun send(method: String, path: String, type: String?, length: Long, body: Body?, timeout: Int): Answer {
        val s = LocalSocket()
        try {
            try {
                s.connect(LocalSocketAddress(socket, LocalSocketAddress.Namespace.FILESYSTEM))
            } catch (e: IOException) {
                throw Refused(e)
            }
            s.soTimeout = timeout
            val out = BufferedOutputStream(s.outputStream, 16384)
            val head = StringBuilder(method).append(' ').append(target(path))
                .append(" HTTP/1.1\r\nHost: bridge\r\nConnection: close\r\n")
            if (body != null) head.append("Content-Type: ").append(type).append("\r\nContent-Length: ").append(length).append("\r\n")
            out.write(head.append("\r\n").toString().toByteArray())
            body?.write(out)
            out.flush()
            val a = Answer(s)
            val status = line(a.input).split(" ") // HTTP/1.1 200 OK
            try {
                a.status = (if (status.size > 1) status[1] else "").toInt()
                while (true) {
                    val h = line(a.input)
                    if (h.isEmpty()) break
                    val colon = h.indexOf(':')
                    if (colon > 0 && h.substring(0, colon).trim().equals("Content-Length", ignoreCase = true)) {
                        a.length = h.substring(colon + 1).trim().toLong()
                    }
                }
            } catch (e: NumberFormatException) {
                throw IOException("not an HTTP answer", e)
            }
            return a
        } catch (e: IOException) {
            try {
                s.close()
            } catch (ignored: IOException) {
                // closed
            }
            throw e
        }
    }

    /**
     * Makes sure a bridge of this app's version answers, starting one if needed.
     * Result: "ok", "install" (the userland is not unpacked yet) or "timeout".
     */
    fun connect(restart: Boolean, done: (String) -> Unit) {
        pool.execute {
            val why = if (restart) "restart asked in the app" else isUp()
            val result = when {
                why == null -> "ok"
                !userland.ready() -> "install"
                System.currentTimeMillis() - startedAt < 30_000 && !restart -> waitUntilUp()
                else -> {
                    run(why)
                    waitUntilUp()
                }
            }
            if (result == "ok" && reading.compareAndSet(false, true)) pool.execute(::readEvents)
            ui.post { done(result) }
        }
    }

    private fun isCurrent(health: Result): Boolean {
        if (health.status != 200) return false
        return try {
            JSONObject(health.body).optInt("version") == version()
        } catch (e: JSONException) {
            false
        }
    }

    /**
     * Null when the bridge answers, else why it is to be started (for its log). Nothing listening
     * and no bridge process (the app was killed with it, its socket left) means no bridge: started
     * at once; one that does not answer gets three tries, so a busy bridge is not taken for a dead one.
     */
    private fun isUp(): String? {
        var status = 0
        var error = ""
        repeat(3) {
            val health = exchange("GET", "/health", null)
            if (isCurrent(health)) return null
            status = health.status
            if (status == 200) return "another version was running"
            // No socket, or no bridge process: no bridge. A socket that refused while its bridge runs
            // may be one too busy to take it: it gets the tries, as starting one would replace it.
            if (status == REFUSED && !File(socket).exists()) return "no bridge was running"
            if (status == REFUSED && userland.bridgePid() == 0) return "no bridge was running, its socket left"
            if (status == REFUSED) error = health.body
            try {
                Thread.sleep(300)
            } catch (e: InterruptedException) {
                return "interrupted"
            }
        }
        return when (status) {
            REFUSED -> "nothing took the connection on the socket, three times ($error)"
            -2 -> "the running bridge did not answer within 3 s, three times"
            -1 -> "the running bridge could not be reached, three times"
            else -> "the running bridge answered $status"
        }
    }

    /** Waits at most 30 s for the bridge just started (proot and Python take a while on a phone). */
    private fun waitUntilUp(): String {
        val end = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < end) {
            try {
                Thread.sleep(100)
            } catch (e: InterruptedException) {
                return "timeout"
            }
            if (isCurrent(exchange("GET", "/health", null))) return "ok"
        }
        return "timeout"
    }

    /** Has the service start the bridge; it replaces one already running. */
    private fun run(why: String) {
        lastSeq = -1
        lastBridge = null
        startedAt = System.currentTimeMillis()
        ui.post { BridgeService.start(context, why) }
    }

    /** Reads the event stream, reconnecting, and hands events over in batches. */
    private fun readEvents() {
        var up = false
        while (reading.get()) {
            var a: Answer? = null
            try {
                val bridge = lastBridge
                val path = "/events?since=" + lastSeq + if (lastSeq >= 0 && bridge != null) "&bridge=" + Uri.encode(bridge) else ""
                val answer = send("GET", path, null, 0, null, 15000) // three missed pings: the bridge is gone or frozen
                a = answer
                if (answer.status != 200) throw IOException("status " + answer.status)
                if (!up) {
                    up = true
                    ui.post { listener.link(true) }
                }
                val reader = answer.input.bufferedReader(Charsets.UTF_8)
                while (reading.get()) {
                    val line = reader.readLine() ?: break
                    if (line.startsWith(": hello ")) lastBridge = line.substring(8).trim()
                    if (!line.startsWith("data: ")) continue
                    try {
                        val event = JSONObject(line.substring(6))
                        lastSeq = event.optLong("seq", 0)
                        queue(event)
                    } catch (ignored: JSONException) {
                        // not an event
                    }
                }
            } catch (e: IOException) {
                if (up) {
                    up = false
                    ui.post { listener.link(false) }
                }
                try {
                    Thread.sleep(1500)
                } catch (ignored: InterruptedException) {
                    return
                }
            } finally {
                a?.close()
            }
        }
    }

    private fun queue(event: JSONObject) {
        synchronized(pending) {
            pending.add(event)
            if (flushQueued) return
            flushQueued = true
        }
        ui.postDelayed({
            val batch: List<JSONObject>
            synchronized(pending) {
                batch = ArrayList(pending)
                pending.clear()
                flushQueued = false
            }
            traced("cc bridge events ${batch.size}") { listener.events(batch) }
        }, 33)
    }

    fun close() {
        reading.set(false)
        pool.shutdownNow()
    }

    companion object {
        /** The bridge's socket in the userland (SOCKET_FILE in bridge.py). */
        const val SOCKET = Userland.HOME + "/.ccapp/bridge.sock"

        /** Status of an exchange when nothing listens on the bridge's socket. */
        const val REFUSED = -3

        private fun bundledVersion(context: Context): Int = try {
            val m = Regex("(?m)^VERSION = (\\d+)").find(Userland.read(context.assets.open("bridge.py")))
            m?.groupValues?.get(1)?.toInt() ?: -1
        } catch (e: IOException) {
            -1
        }

        /** The request target as sent: the path with what is not plain ASCII percent-encoded. */
        private fun target(path: String): String {
            val out = StringBuilder()
            for (b in path.toByteArray()) {
                val ch = b.toInt() and 0xff
                if (ch in 0x21..0x7e) out.append(ch.toChar())
                else out.append('%').append(Character.forDigit(ch shr 4, 16)).append(Character.forDigit(ch and 15, 16))
            }
            return out.toString()
        }

        /** A line of the answer's head, without its line end. */
        @Throws(IOException::class)
        private fun line(input: InputStream): String {
            val out = StringBuilder()
            while (true) {
                val ch = input.read()
                if (ch == '\n'.code) break
                if (ch < 0) throw EOFException()
                if (ch != '\r'.code) out.append(ch.toChar())
            }
            return out.toString()
        }

        /** The answer's body: its Content-Length, or all until the bridge closes the connection. */
        @Throws(IOException::class)
        private fun readBody(a: Answer): String {
            val out = ByteArrayOutputStream()
            val buf = ByteArray(16384)
            var left = a.length
            while (left != 0L) {
                val n = a.input.read(buf, 0, if (left < 0) buf.size else min(buf.size.toLong(), left).toInt())
                if (n <= 0) break
                out.write(buf, 0, n)
                if (left > 0) left -= n
            }
            if (left > 0) throw EOFException()
            return out.toString("UTF-8")
        }
    }
}
