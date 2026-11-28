package com.jeep.claudecode

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.IBinder
import android.os.PowerManager
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

/**
 * Keeps the bridge (and so Claude Code) running in this app's own process tree: a foreground
 * service holding a wake lock that is never released, which starts the bridge under proot when
 * asked and again when it dies on its own.
 */
class BridgeService : Service() {
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var userland: Userland
    private var wake: PowerManager.WakeLock? = null
    /** The bridge's process: set and cleared on the worker, read by the watcher and onDestroy (a watcher must see it replaced or stopped). */
    @Volatile private var proc: Process? = null
    /** Restarts after the bridge died on its own, in the last minute: a crash loop stops at three. */
    private val deaths = LongArray(3)
    private var deathCount = 0
    /** The phone's time zone changed: the next Claude Code started gets the new one. */
    private val zone = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val id = intent.getStringExtra(Intent.EXTRA_TIMEZONE)
            try {
                worker.execute { writeTimeZone(id) }
            } catch (e: RejectedExecutionException) {
                // the service was stopped meanwhile
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        userland = Userland(this)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "后台运行", NotificationManager.IMPORTANCE_LOW))
        val open = Intent(this, MainActivity::class.java).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_terminal)
            .setContentTitle("Claude Code 正在运行")
            .setContentText("保持后台运行，切出去也不会断")
            .setContentIntent(PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE))
            .setOngoing(true)
            .build()
        startForeground(1, n)
        wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "claudecode:bridge").apply {
            setReferenceCounted(false)
            acquire()
        }
        registerReceiver(zone, IntentFilter(Intent.ACTION_TIMEZONE_CHANGED))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val reason = if (intent == null) "service restarted by the system" else intent.getStringExtra(EXTRA_REASON)
        worker.execute { run(reason ?: "") }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        unregisterReceiver(zone)
        worker.shutdownNow()
        val p = proc
        proc = null // its watcher must not start another on the stopped worker
        val pid = userland.bridgePid()
        if (pid > 0) android.os.Process.sendSignal(pid, 15) // proot ignores SIGTERM: the bridge is told
        p?.destroy()
        wake?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    /** Stops the running bridge, if any, and starts a new one. On the worker thread. */
    private fun run(reason: String) {
        stopBridge()
        if (!userland.ready()) {
            log("not started ($reason): the userland is not unpacked")
            return
        }
        try {
            userland.writeBridge()
            writeTimeZone(null)
            val pb = ProcessBuilder(userland.command("python3", "/opt/ccapp/bridge.py",
                "--socket", Bridge.SOCKET, "--reason", "app: $reason"))
            userland.env(pb)
            pb.directory(userland.base)
            pb.redirectErrorStream(true)
            if (userland.log.length() > 300_000) userland.log.delete()
            pb.redirectOutput(ProcessBuilder.Redirect.appendTo(userland.log))
            log("starting the bridge ($reason, " + (if (userland.nativeMode) "native chroot" else "proot") + ")")
            val p = pb.start()
            proc = p
            Thread({ watch(p) }, "bridge-watch").apply {
                isDaemon = true
                start()
            }
        } catch (e: IOException) {
            log("could not start the bridge: $e")
        }
    }

    /**
     * Stops the running bridge. proot ignores SIGTERM, so the bridge itself is told: it ends its
     * conversations and exits, and proot then ends whatever is left under it and exits too.
     */
    private fun stopBridge() {
        val p = proc
        proc = null
        val pid = userland.bridgePid()
        if (pid > 0) android.os.Process.sendSignal(pid, 15)
        if (p == null) return
        try {
            if (p.waitFor(6, TimeUnit.SECONDS)) return
            if (pid > 0) android.os.Process.sendSignal(pid, 9)
            if (!p.waitFor(2, TimeUnit.SECONDS)) p.destroyForcibly()
        } catch (ignored: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    /**
     * Writes the phone's time zone (id, or the default one) where the bridge reads it for each
     * Claude Code it starts (TZ_FILE in bridge.py): the userland has no /etc/localtime. Written
     * aside and moved in. On the worker thread.
     */
    private fun writeTimeZone(id: String?) {
        val f = userland.file(Userland.HOME + "/.ccapp/timezone")
        val aside = File(f.path + ".new")
        try {
            f.parentFile?.mkdirs()
            FileOutputStream(aside).use { it.write((id ?: TimeZone.getDefault().id).toByteArray()) }
            if (!aside.renameTo(f)) throw IOException("could not move $aside in")
        } catch (e: IOException) {
            log("could not write the time zone: $e")
        }
    }

    /** Waits for a bridge to end; one that died on its own (not replaced) is started again. */
    private fun watch(p: Process) {
        val code = try {
            p.waitFor()
        } catch (e: InterruptedException) {
            return
        }
        log("the bridge exited with $code")
        if (proc !== p) return // replaced or stopped on purpose
        val now = System.currentTimeMillis()
        val recent = deaths.count { now - it < 60_000 }
        if (recent >= deaths.size) {
            log("not restarting: it died $recent times in a minute")
            return
        }
        deaths[deathCount++ % deaths.size] = now
        try {
            Thread.sleep(1500)
        } catch (e: InterruptedException) {
            return
        }
        if (proc !== p) return
        try {
            worker.execute { run("it exited with $code") }
        } catch (e: RejectedExecutionException) {
            // the service was stopped meanwhile
        }
    }

    private fun log(text: String) {
        synchronized(BridgeService::class.java) {
            try {
                FileOutputStream(userland.log, true).use { out ->
                    val line = SimpleDateFormat("yyyy-MM-dd HH:mm:ss ", Locale.ROOT).format(Date()) + "[app] " + text + "\n"
                    out.write(line.toByteArray())
                }
            } catch (ignored: IOException) {
                // no log
            }
        }
    }

    companion object {
        const val START = "start"
        const val EXTRA_REASON = "reason"
        private const val CHANNEL = "bridge"

        /** Asks the service to (re)start the bridge. */
        @JvmStatic
        fun start(context: Context, reason: String) {
            val i = Intent(context, BridgeService::class.java).setAction(START).putExtra(EXTRA_REASON, reason)
            context.startForegroundService(i)
        }
    }
}
