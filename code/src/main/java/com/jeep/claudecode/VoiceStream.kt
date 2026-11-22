package com.jeep.claudecode

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.math.max
import kotlin.math.sqrt
import org.json.JSONException
import org.json.JSONObject

/**
 * Dictation as Claude Code's own /voice does it: the microphone, 16 kHz mono 16-bit, streamed over a
 * WebSocket to Anthropic's speech-to-text (api/ws/speech_to_text/voice_stream), signed in with the
 * Claude.ai account Claude Code is logged in with (its ~/.claude/.credentials.json). The server sends
 * the sentence heard so far as it grows (TranscriptText), and says when it is done (TranscriptEndpoint).
 */
class VoiceStream(private val ui: Handler) {
    interface Listener {
        /** What is heard of the sentence so far (the whole of it each time). */
        fun text(sentence: String)
        /** The sentence is done: the next goes after it. */
        fun endpoint()
        fun level(rms: Float)
        /** It ended: error null when it ended as asked. */
        fun closed(error: String?)
    }

    /** Who hears it: none while it is only connected ahead (see connect). */
    @Volatile private var out: Listener? = null
    /** Why it ended, for a listener that comes after it did. */
    @Volatile private var endedWith: String? = null
    @Volatile private var recording = false
    @Volatile private var closing = false
    @Volatile private var done = false
    /**
     * The microphone starts at the press, not once connected (that takes up to a second or more, and the
     * first words would be lost): what it hears and anything else to send waits here until the server
     * takes it, then goes in order (an opcode and its bytes).
     */
    private val waiting = ArrayList<Pair<Int, ByteArray>>()
    private var open = false // guarded by waiting
    @Volatile private var socket: Socket? = null
    @Volatile private var os: OutputStream? = null
    /** Every write to the socket, and closing it, in order on one thread of its own: never on the main thread. */
    private val writer = Executors.newSingleThreadExecutor()
    private val random = SecureRandom()

    /**
     * Connects, on threads of its own, ahead of the press if it can be: the server takes about a second to
     * get ready to listen, and waits (kept alive) however long it is before anything is said.
     */
    fun connect(token: String, language: String, userAgent: String) {
        Thread({ run(token, language, userAgent) }, "voice-stream").start()
    }

    /** Not ended and not taken yet: a press can listen with it. */
    fun ready(): Boolean = !done && !closing && out == null

    /** Records into it from now (connected already, or what is said waits until it is). */
    fun begin(l: Listener) {
        out = l
        if (done) {
            val why = endedWith
            ui.post { l.closed(why) }
            return
        }
        recording = true
        Thread(::record, "voice-mic").start()
    }

    /** Stops recording; the last words still come, then it closes. */
    fun stop() {
        if (!recording) return
        recording = false
        closing = true
        sendText("{\"type\":\"CloseStream\"}")
        // The server closes once it has said all; a silent one is let go.
        ui.postDelayed(::cancel, 5000)
    }

    /** Ends at once. */
    fun cancel() {
        recording = false
        closing = true
        val s = socket
        try {
            writer.execute {
                try {
                    s?.close()
                } catch (ignored: IOException) {
                    // closing
                }
            }
            writer.shutdown()
        } catch (ignored: RejectedExecutionException) {
            // cancelled before
        }
        end(null)
    }

    private fun end(error: String?) {
        if (done) return
        endedWith = error
        done = true
        val l = out
        if (l != null) ui.post { l.closed(error) }
    }

    private fun run(token: String, language: String, userAgent: String) {
        try {
            val plain = Socket()
            socket = plain
            plain.connect(InetSocketAddress(HOST, 443), 10000)
            val s = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(plain, HOST, 443, true) as SSLSocket
            socket = s
            // The certificate must be api.anthropic.com's (the token goes over this socket).
            val params = s.sslParameters
            params.endpointIdentificationAlgorithm = "HTTPS"
            s.sslParameters = params
            s.startHandshake()
            val o = s.outputStream
            os = o
            val input = DataInputStream(s.inputStream)
            val path = "/api/ws/speech_to_text/voice_stream?encoding=linear16&sample_rate=" + RATE + "&channels=1" +
                "&endpointing_ms=300&utterance_end_ms=1000&language=" + language + "&use_conversation_engine=true"
            val nonce = ByteArray(16)
            random.nextBytes(nonce)
            val req = "GET " + path + " HTTP/1.1\r\nHost: " + HOST + "\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                "Sec-WebSocket-Key: " + Base64.encodeToString(nonce, Base64.NO_WRAP) + "\r\nSec-WebSocket-Version: 13\r\n" +
                "Authorization: Bearer " + token + "\r\nUser-Agent: " + userAgent + "\r\n\r\n"
            o.write(req.toByteArray())
            o.flush()
            val status = readHead(input)
            if (!status.contains(" 101 ")) {
                end(if (status.contains(" 401 ") || status.contains(" 403 "))
                    "Claude Code 的登录不能用来语音识别（" + status.trim() + "）：在终端里运行 claude 重新 /login"
                else "语音识别连不上：" + status.trim())
                recording = false
                return
            }
            // Connected: what was said meanwhile goes first, then all as it comes.
            synchronized(waiting) {
                open = true
                val keepAlive = "{\"type\":\"KeepAlive\"}".toByteArray()
                write(1, keepAlive, keepAlive.size)
                for ((op, bytes) in waiting) write(op, bytes, bytes.size)
                waiting.clear()
            }
            Thread({
                while (!done && !closing) {
                    try {
                        Thread.sleep(8000)
                    } catch (e: InterruptedException) {
                        return@Thread
                    }
                    if (!closing) sendText("{\"type\":\"KeepAlive\"}")
                }
            }, "voice-keepalive").start()
            read(input)
        } catch (e: IOException) {
            recording = false
            end(if (closing) null else "语音识别断开了：" + e.message)
        }
    }

    private fun record() {
        val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = try {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, max(min, CHUNK * 4))
        } catch (e: SecurityException) {
            end("没有麦克风权限")
            cancel()
            return
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            end("录不到声音：麦克风被占用了？")
            cancel()
            return
        }
        val buf = ByteArray(CHUNK)
        try {
            rec.startRecording()
            while (recording) {
                var n = 0
                while (n < CHUNK && recording) {
                    val r = rec.read(buf, n, CHUNK - n)
                    if (r <= 0) break
                    n += r
                }
                if (n <= 0) continue
                var sum = 0L
                var i = 0
                while (i + 1 < n) {
                    val v = ((buf[i].toInt() and 0xff) or (buf[i + 1].toInt() shl 8)).toShort().toInt()
                    sum += v.toLong() * v
                    i += 2
                }
                val rms = sqrt(sum / max(1.0, n / 2.0)).toFloat() / 32768f
                val l = out
                if (l != null) ui.post { l.level(rms) }
                if (recording) send(2, buf, n)
            }
        } finally {
            rec.stop()
            rec.release()
        }
    }

    /** Frames from the server until it closes. */
    @Throws(IOException::class)
    private fun read(input: DataInputStream) {
        val message = ByteArrayOutputStream()
        while (true) {
            val b0 = input.read()
            if (b0 < 0) break
            val b1 = input.readUnsignedByte()
            var len = (b1 and 0x7f).toLong()
            if (len == 126L) len = input.readUnsignedShort().toLong()
            else if (len == 127L) len = input.readLong()
            var mask: ByteArray? = null
            if ((b1 and 0x80) != 0) {
                mask = ByteArray(4)
                input.readFully(mask)
            }
            val data = ByteArray(len.toInt())
            input.readFully(data)
            if (mask != null) for (i in data.indices) data[i] = (data[i].toInt() xor mask[i % 4].toInt()).toByte()
            val op = b0 and 0x0f
            if (op == 8) break // close
            if (op == 9) { // ping: pong
                send(10, data, data.size)
                continue
            }
            if (op == 1 || op == 0) {
                message.write(data)
                if ((b0 and 0x80) == 0) continue // more to come
                handle(String(message.toByteArray(), Charsets.UTF_8))
                message.reset()
            }
        }
        end(null)
    }

    private fun handle(json: String) {
        try {
            val m = JSONObject(json)
            when (m.optString("type")) {
                "TranscriptText", "TranscriptInterim" -> {
                    val t = m.optString("data")
                    val l = out
                    if (t.isNotEmpty() && l != null) ui.post { l.text(t) }
                }
                "TranscriptEndpoint" -> {
                    val l = out
                    if (l != null) ui.post { l.endpoint() }
                }
                "TranscriptError", "error" -> {
                    val why = m.optString("description", m.optString("message", m.optString("error_code", "识别出错")))
                    end("语音识别出错：$why")
                    cancel()
                }
            }
        } catch (ignored: JSONException) {
            // not JSON: nothing to show
        }
    }

    private fun sendText(s: String) {
        val b = s.toByteArray()
        send(1, b, b.size)
    }

    /** One masked frame (a client's frames are masked). */
    private fun send(op: Int, data: ByteArray, n: Int) {
        synchronized(waiting) {
            if (!open) {
                if (!done) waiting.add(op to data.copyOf(n))
                return
            }
            write(op, data, n)
        }
    }

    private fun write(op: Int, data: ByteArray, n: Int) {
        val o = os ?: return
        val mask = ByteArray(4)
        random.nextBytes(mask)
        val frame: ByteArray
        val head: Int
        if (n < 126) {
            frame = ByteArray(2 + 4 + n)
            frame[1] = (0x80 or n).toByte()
            head = 2
        } else if (n < 65536) {
            frame = ByteArray(4 + 4 + n)
            frame[1] = (0x80 or 126).toByte()
            frame[2] = (n shr 8).toByte()
            frame[3] = n.toByte()
            head = 4
        } else {
            frame = ByteArray(10 + 4 + n)
            frame[1] = (0x80 or 127).toByte()
            for (i in 0 until 8) frame[2 + i] = (n.toLong() shr (8 * (7 - i))).toByte()
            head = 10
        }
        frame[0] = (0x80 or op).toByte()
        System.arraycopy(mask, 0, frame, head, 4)
        for (i in 0 until n) frame[head + 4 + i] = (data[i].toInt() xor mask[i % 4].toInt()).toByte()
        try {
            writer.execute {
                try {
                    o.write(frame)
                    o.flush()
                } catch (e: IOException) {
                    if (!closing) {
                        end("语音识别断开了：" + e.message)
                        cancel()
                    }
                }
            }
        } catch (ignored: RejectedExecutionException) {
            // closed: nothing more is sent
        }
    }

    companion object {
        private const val HOST = "api.anthropic.com"
        private const val RATE = 16000
        private const val CHUNK = RATE * 2 / 10 // 100 ms of audio
        private const val NOT_LOGGED_IN = "Claude Code 没有用 Claude.ai 账号登录：在终端里运行 claude，用 /login 登录"

        /** The OAuth token Claude Code is signed in with; failing, why there is none (its message). */
        @JvmStatic
        fun token(rootfs: File): Result<String> {
            return try {
                val creds = JSONObject(Userland.read(FileInputStream(File(rootfs, "root/.claude/.credentials.json"))))
                val o = creds.optJSONObject("claudeAiOauth")
                val t = o?.optString("accessToken") ?: ""
                when {
                    o == null || t.isEmpty() -> Result.failure(IllegalStateException(NOT_LOGGED_IN))
                    o.optLong("expiresAt") > 0 && o.optLong("expiresAt") < System.currentTimeMillis() ->
                        Result.failure(IllegalStateException("Claude Code 的登录已过期：发一条消息让 Claude Code 刷新登录，再试"))
                    else -> Result.success(t)
                }
            } catch (e: Exception) {
                Result.failure(IllegalStateException(NOT_LOGGED_IN))
            }
        }

        @Throws(IOException::class)
        private fun readHead(input: DataInputStream): String {
            val b = StringBuilder()
            while (true) {
                val c = input.read()
                if (c == -1) break
                b.append(c.toChar())
                if (b.length >= 4 && b.substring(b.length - 4) == "\r\n\r\n") break
            }
            val head = b.toString()
            val nl = head.indexOf("\r\n")
            return if (nl < 0) head else head.substring(0, nl)
        }
    }
}
