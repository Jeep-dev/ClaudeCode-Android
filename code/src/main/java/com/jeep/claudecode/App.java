package com.jeep.claudecode;

import android.app.Application;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Writes a crash, with where it happened, to the run log that 日志 shows; then crashes as before. */
public final class App extends Application {
    @Override public void onCreate() {
        super.onCreate();
        Thread.UncaughtExceptionHandler before = Thread.getDefaultUncaughtExceptionHandler();
        File log = new File(getFilesDir(), "runtime.log");
        Thread.setDefaultUncaughtExceptionHandler((thread, e) -> {
            try (OutputStream out = new FileOutputStream(log, true)) {
                String line = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss ", Locale.ROOT).format(new Date())
                        + "[crash] " + thread.getName() + ": " + Log.getStackTraceString(e) + "\n";
                out.write(line.getBytes(StandardCharsets.UTF_8));
            } catch (Throwable ignored) {
                // nothing written
            }
            if (before != null) before.uncaughtException(thread, e);
        });
    }
}
