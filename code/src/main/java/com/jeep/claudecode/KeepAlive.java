package com.jeep.claudecode;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * Keeps Android from stopping Claude Code in the background, set through Shizuku's rish (shell
 * privileges) in the userland: the app on the battery whitelist, allowed to run in the background,
 * and Android 12+'s limit on child processes off. Checked, and set when it is not, by the app itself.
 */
final class KeepAlive {
    /** All set; set now (it was not); not set though tried; rish did not run (no Shizuku, or not allowed). */
    static final int ON = 0, SET = 1, OFF = 2, NO_SHIZUKU = 3;

    private KeepAlive() {}

    /** Checks it, and sets what is not set. Off the main thread: rish takes a second or more. */
    static int ensure(Userland userland, String pkg) {
        int state = check(userland, pkg);
        if (state != OFF) return state;
        rish(userland, "dumpsys deviceidle whitelist +" + pkg
                + "; cmd appops set " + pkg + " RUN_ANY_IN_BACKGROUND allow"
                + "; settings put global settings_enable_monitor_phantom_procs false"
                + "; device_config put activity_manager max_phantom_processes 2147483647");
        state = check(userland, pkg);
        return state == ON ? SET : state;
    }

    private static int check(Userland userland, String pkg) {
        String out = rish(userland, "dumpsys deviceidle whitelist | grep -q ," + pkg + ", && echo idle=1"
                + "; cmd appops get " + pkg + " RUN_ANY_IN_BACKGROUND"
                + "; echo phantom=$(settings get global settings_enable_monitor_phantom_procs)"
                + "; echo max=$(device_config get activity_manager max_phantom_processes)"
                + "; echo ok=1");
        if (out == null || !out.contains("ok=1")) return NO_SHIZUKU;
        boolean idle = out.contains("idle=1");
        boolean background = out.contains("RUN_ANY_IN_BACKGROUND: allow");
        // The limit is there from Android 12; turned off by the setting (12L and later) or set past reach.
        boolean children = android.os.Build.VERSION.SDK_INT < 31 || out.contains("phantom=false")
                || value(out, "max=") > 10_000;
        return idle && background && children ? ON : OFF;
    }

    private static long value(String out, String key) {
        int at = out.indexOf(key);
        if (at < 0) return 0;
        int end = out.indexOf('\n', at);
        try {
            return Long.parseLong(out.substring(at + key.length(), end < 0 ? out.length() : end).trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** What rish printed running the script, or null when it did not end within 20 s. */
    private static String rish(Userland userland, String script) {
        File out = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(userland.command("/usr/local/bin/rish", "-c", script));
            userland.env(pb);
            pb.directory(userland.base);
            pb.redirectErrorStream(true);
            out = File.createTempFile("rish", null, userland.tmp);
            pb.redirectOutput(out);
            Process p = pb.start();
            if (!p.waitFor(20, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return null;
            }
            return Userland.read(new FileInputStream(out));
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } finally {
            if (out != null) out.delete();
        }
    }
}
