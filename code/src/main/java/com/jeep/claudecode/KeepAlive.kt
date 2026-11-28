package com.jeep.claudecode

import android.os.Build
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Keeps Android from stopping Claude Code in the background, set through Shizuku's rish (shell
 * privileges) in the userland: the app on the battery whitelist, allowed to run in the background,
 * and Android 12+'s limit on child processes off. Checked, and set when it is not, by the app itself.
 */
object KeepAlive {
    /** All set; set now (it was not); not set though tried; rish did not run (no Shizuku, or not allowed). */
    const val ON = 0
    const val SET = 1
    const val OFF = 2
    const val NO_SHIZUKU = 3

    /** Checks it, and sets what is not set. Off the main thread: rish takes a second or more. */
    @JvmStatic
    fun ensure(userland: Userland, pkg: String): Int {
        val state = check(userland, pkg)
        if (state != OFF) return state
        rish(userland, "dumpsys deviceidle whitelist +" + pkg +
            "; cmd appops set " + pkg + " RUN_ANY_IN_BACKGROUND allow" +
            "; settings put global settings_enable_monitor_phantom_procs false" +
            "; device_config put activity_manager max_phantom_processes 2147483647")
        val now = check(userland, pkg)
        return if (now == ON) SET else now
    }

    private fun check(userland: Userland, pkg: String): Int {
        val out = rish(userland, "dumpsys deviceidle whitelist | grep -q ," + pkg + ", && echo idle=1" +
            "; cmd appops get " + pkg + " RUN_ANY_IN_BACKGROUND" +
            "; echo phantom=$(settings get global settings_enable_monitor_phantom_procs)" +
            "; echo max=$(device_config get activity_manager max_phantom_processes)" +
            "; echo ok=1")
        if (out == null || !out.contains("ok=1")) return NO_SHIZUKU
        val idle = out.contains("idle=1")
        val background = out.contains("RUN_ANY_IN_BACKGROUND: allow")
        // The limit is there from Android 12; turned off by the setting (12L and later) or set past reach.
        val children = Build.VERSION.SDK_INT < 31 || out.contains("phantom=false") || value(out, "max=") > 10_000
        return if (idle && background && children) ON else OFF
    }

    private fun value(out: String, key: String): Long {
        val at = out.indexOf(key)
        if (at < 0) return 0
        val end = out.indexOf('\n', at)
        return out.substring(at + key.length, if (end < 0) out.length else end).trim().toLongOrNull() ?: 0
    }

    /** What rish printed running the script, or null when it did not end within 20 s. */
    private fun rish(userland: Userland, script: String): String? {
        var out: File? = null
        return try {
            // As this app's uid, under proot: Shizuku knows the app by its uid, not root's.
            val pb = ProcessBuilder(userland.commandAsApp("/usr/local/bin/rish", "-c", script))
            userland.appEnv(pb)
            pb.directory(userland.base)
            pb.redirectErrorStream(true)
            out = File.createTempFile("rish", null, userland.tmp)
            pb.redirectOutput(out)
            val p = pb.start()
            if (!p.waitFor(20, TimeUnit.SECONDS)) {
                p.destroyForcibly()
                null
            } else {
                Userland.read(FileInputStream(out))
            }
        } catch (e: IOException) {
            null
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        } finally {
            out?.delete()
        }
    }
}
