package com.jeep.claudecode

import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Writes to the run log (日志 → 启动) each time the main thread is stopped a second or more: in which traced
 * stretch, where it stood (read again every half second while it stays stopped, each new place once), and
 * for how long. Stops that come only now and then are not caught by looking: this way the next one is
 * written down as it happens, the first place at once, in case the app is closed while it is stopped.
 */
object Watchdog {
    /** The traced stretch the main thread is in now (traced sets it), or null. */
    @Volatile @JvmField var section: String? = null

    private const val STOPPED = 1000L
    private const val LOOK = 100L
    private const val AGAIN = 500L
    private const val FRAMES = 40
    /** The run log is the userland's too: past this, stops are no longer written to it. */
    private const val MOST = 2L shl 20
    private var started = false

    fun start(log: File) {
        // Robolectric's main thread runs what is posted only when a test lets it: always "stopped".
        if (started || Build.FINGERPRINT == "robolectric") return
        started = true
        val looper = Looper.getMainLooper()
        val main = Handler(looper)
        val t = Thread({ watch(main, looper.thread, log) }, "cc watchdog")
        t.isDaemon = true
        t.priority = Thread.MIN_PRIORITY
        t.start()
    }

    private fun watch(main: Handler, thread: Thread, log: File) {
        while (true) {
            val answered = AtomicBoolean(false)
            val sent = SystemClock.uptimeMillis()
            main.postAtFrontOfQueue { answered.set(true) }
            var last = sent
            var stopped = false
            var looked = 0L
            val seen = HashSet<String>()
            while (!answered.get()) {
                Thread.sleep(LOOK)
                val now = SystemClock.uptimeMillis()
                // This thread was held too (the whole app frozen by the system): not the main thread's stop.
                if (now - last > STOPPED && !stopped) break
                last = now
                if (now - sent < STOPPED || now - looked < AGAIN || answered.get()) continue
                looked = now
                val frames = thread.stackTrace
                // Waiting for work is not being stopped (the app thawed with the answer still to run).
                if (frames.isNotEmpty() && frames[0].methodName == "nativePollOnce") continue
                val place = frames.take(FRAMES).joinToString("\n") { "    at $it" }
                if (!seen.add(place)) continue
                write(log, (if (stopped) "  still stopped, ${now - sent} ms" else "main thread stopped") +
                    " in " + (section ?: "(untraced)") + ":\n" + place)
                stopped = true
            }
            if (stopped) write(log, "main thread went on after ${SystemClock.uptimeMillis() - sent} ms")
            // A few looks a second.
            val rest = 250 - (SystemClock.uptimeMillis() - sent)
            if (rest > 0) Thread.sleep(rest)
        }
    }

    private fun write(log: File, text: String) {
        try {
            if (log.length() > MOST) return
            FileOutputStream(log, true).use { out ->
                val line = SimpleDateFormat("yyyy-MM-dd HH:mm:ss ", Locale.ROOT).format(Date()) + "[stall] " + text + "\n"
                out.write(line.toByteArray())
            }
        } catch (ignored: Exception) {
            // nothing written
        }
    }
}
