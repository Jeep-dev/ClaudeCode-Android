package com.jeep.claudecode

import android.app.Application
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Writes a crash, with where it happened, to the run log that 日志 shows; then crashes as before. */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        val before = Thread.getDefaultUncaughtExceptionHandler()
        val log = File(filesDir, "runtime.log")
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            try {
                FileOutputStream(log, true).use { out ->
                    val line = SimpleDateFormat("yyyy-MM-dd HH:mm:ss ", Locale.ROOT).format(Date()) +
                        "[crash] " + thread.name + ": " + Log.getStackTraceString(e) + "\n"
                    out.write(line.toByteArray())
                }
            } catch (ignored: Throwable) {
                // nothing written
            }
            before?.uncaughtException(thread, e)
        }
    }
}
