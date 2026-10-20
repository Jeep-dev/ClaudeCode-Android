package com.jeep.claudecode;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import android.os.PowerManager;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Keeps the bridge (and so Claude Code) running in this app's own process tree: a foreground
 * service holding a wake lock that is never released, which starts the bridge under proot when
 * asked and again when it dies on its own.
 */
public final class BridgeService extends Service {
    static final String START = "start";
    static final String EXTRA_REASON = "reason";
    private static final String CHANNEL = "bridge";

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private Userland userland;
    private PowerManager.WakeLock wake;
    private Process proc;
    /** Restarts after the bridge died on its own, in the last minute: a crash loop stops at three. */
    private final long[] deaths = new long[3];
    private int deathCount;

    /** Asks the service to (re)start the bridge. */
    static void start(Context context, String reason) {
        Intent i = new Intent(context, BridgeService.class).setAction(START).putExtra(EXTRA_REASON, reason);
        context.startForegroundService(i);
    }

    @Override public void onCreate() {
        super.onCreate();
        userland = new Userland(this);
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "后台运行", NotificationManager.IMPORTANCE_LOW));
        Intent open = new Intent(this, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        Notification n = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_terminal)
                .setContentTitle("Claude Code 正在运行")
                .setContentText("保持后台运行，切出去也不会断")
                .setContentIntent(PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE))
                .setOngoing(true)
                .build();
        startForeground(1, n);
        wake = getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "claudecode:bridge");
        wake.setReferenceCounted(false);
        wake.acquire();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String reason = intent == null ? "service restarted by the system" : intent.getStringExtra(EXTRA_REASON);
        worker.execute(() -> run(reason == null ? "" : reason));
        return START_STICKY;
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onDestroy() {
        worker.shutdownNow();
        Process p = proc;
        proc = null; // its watcher must not start another on the stopped worker
        int pid = bridgePid();
        if (pid > 0) android.os.Process.sendSignal(pid, 15); // proot ignores SIGTERM: the bridge is told
        if (p != null) p.destroy();
        if (wake != null && wake.isHeld()) wake.release();
        super.onDestroy();
    }

    /** Stops the running bridge, if any, and starts a new one. On the worker thread. */
    private void run(String reason) {
        stopBridge();
        if (!userland.ready()) {
            log("not started (" + reason + "): the userland is not unpacked");
            return;
        }
        try {
            userland.writeBridge();
            ProcessBuilder pb = new ProcessBuilder(userland.command("python3", "/opt/ccapp/bridge.py",
                    "--socket", Bridge.SOCKET, "--reason", "app: " + reason));
            userland.env(pb);
            pb.directory(userland.base);
            pb.redirectErrorStream(true);
            if (userland.log.length() > 300_000) userland.log.delete();
            pb.redirectOutput(ProcessBuilder.Redirect.appendTo(userland.log));
            log("starting the bridge (" + reason + ")");
            Process p = pb.start();
            proc = p;
            Thread watch = new Thread(() -> watch(p), "bridge-watch");
            watch.setDaemon(true);
            watch.start();
        } catch (IOException e) {
            log("could not start the bridge: " + e);
        }
    }

    /**
     * Stops the running bridge. proot ignores SIGTERM, so the bridge itself is told: it ends its
     * conversations and exits, and proot then ends whatever is left under it and exits too.
     */
    private void stopBridge() {
        Process p = proc;
        proc = null;
        int pid = bridgePid();
        if (pid > 0) android.os.Process.sendSignal(pid, 15);
        if (p == null) return;
        try {
            if (p.waitFor(6, TimeUnit.SECONDS)) return;
            if (pid > 0) android.os.Process.sendSignal(pid, 9);
            if (!p.waitFor(2, TimeUnit.SECONDS)) p.destroyForcibly();
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    /** The running bridge's pid (it writes it), or 0. */
    private int bridgePid() {
        try {
            int pid = Integer.parseInt(Userland.read(new FileInputStream(userland.file(Userland.HOME + "/.ccapp/bridge.pid"))).trim());
            String cmd = Userland.read(new FileInputStream("/proc/" + pid + "/cmdline"));
            return cmd.contains("bridge.py") ? pid : 0;
        } catch (IOException | NumberFormatException e) {
            return 0;
        }
    }

    /** Waits for a bridge to end; one that died on its own (not replaced) is started again. */
    private void watch(Process p) {
        int code;
        try {
            code = p.waitFor();
        } catch (InterruptedException e) {
            return;
        }
        log("the bridge exited with " + code);
        if (proc != p) return; // replaced or stopped on purpose
        long now = System.currentTimeMillis();
        int recent = 0;
        for (long t : deaths) if (now - t < 60_000) recent++;
        if (recent >= deaths.length) {
            log("not restarting: it died " + recent + " times in a minute");
            return;
        }
        deaths[deathCount++ % deaths.length] = now;
        try {
            Thread.sleep(1500);
        } catch (InterruptedException e) {
            return;
        }
        if (proc != p) return;
        try {
            worker.execute(() -> run("it exited with " + code));
        } catch (java.util.concurrent.RejectedExecutionException e) {
            // the service was stopped meanwhile
        }
    }

    private void log(String text) {
        synchronized (BridgeService.class) {
            File f = userland.log;
            try (OutputStream out = new FileOutputStream(f, true)) {
                String line = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss ", Locale.ROOT).format(new Date()) + "[app] " + text + "\n";
                out.write(line.getBytes(StandardCharsets.UTF_8));
            } catch (IOException ignored) {
                // no log
            }
        }
    }
}
