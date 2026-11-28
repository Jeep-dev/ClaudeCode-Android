package com.jeep.claudecode

import android.content.Context
import android.content.pm.PackageManager
import android.content.res.AssetManager
import android.net.ConnectivityManager
import android.os.Environment
import android.os.SystemClock
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern
import java.util.zip.ZipFile
import kotlin.math.max
import kotlin.math.min
import org.tukaani.xz.XZInputStream

/**
 * The Linux userland Claude Code runs in, inside this app: Alpine (with Claude Code, Python and
 * git). When the phone is rooted the userland runs as a native chroot (mounts made by the root
 * shell, then setpriv down to this app's uid, so the files and the bridge's socket stay the
 * app's own); otherwise it runs under Termux's proot, as before. Built by the scripts in
 * code/runtime in CI; proot comes in the APK, the userland (rootfs.tar.xz) is downloaded from
 * where CI published it (assets/runtime/rootfs.url), once per userland, unless the APK carries
 * it (a build of its own).
 */
class Userland(context: Context) {
    private val context: Context = context.applicationContext
    @JvmField val base: File = this.context.filesDir
    @JvmField val rootfs = File(base, "rootfs")
    @JvmField val bin = File(base, "proot")
    @JvmField val tmp = File(base, "tmp")
    @JvmField val log = File(base, "runtime.log")
    /** The script the root shell runs for a native chroot start (written before each use). */
    private val nativeScript = File(base, "native.sh")
    /** True once root was granted and the userland runs as a chroot; false for proot. */
    @JvmField @Volatile var nativeMode = false
    /** The su that gave root, once found. */
    @Volatile private var suPath: String? = null
    /** 0: not looked at, 1: root, 2: not (asked again after a while). */
    @Volatile private var rootState = 0
    private var rootFailedAt = 0L
    private val rootLock = Any()

    /** The id of the userland in this APK; null when the APK carries none (a local build). */
    fun bundled(): String? = try {
        read(context.assets.open("runtime/id")).trim()
    } catch (e: IOException) {
        null
    }

    private fun marker() = File(rootfs, ".ccapp-runtime")

    /** The userland in this APK is unpacked, and Claude Code is in it. */
    fun ready(): Boolean = unpacked() && hasClaude()

    private fun unpacked(): Boolean {
        val id = bundled() ?: return false
        return try {
            id == read(FileInputStream(marker())).trim()
        } catch (e: IOException) {
            false
        }
    }

    /** /usr/local/bin/claude is there (a link followed inside the userland). */
    private fun hasClaude(): Boolean {
        var f = File(rootfs, "usr/local/bin/claude")
        try {
            val to = Os.readlink(f.path)
            f = if (to.startsWith("/")) File(rootfs, to.substring(1)) else File(f.parentFile, to)
        } catch (notLink: ErrnoException) {
            // the file itself
        }
        return f.isFile
    }

    /**
     * Installs Claude Code from Anthropic's releases with the userland's claude-update (the
     * latest, checked against its manifest), telling how far the download is.
     */
    @Throws(IOException::class)
    private fun installClaude(status: (String) -> Unit) {
        status("下载 Claude Code")
        val pb = ProcessBuilder(command("/usr/local/bin/claude-update"))
        env(pb)
        pb.directory(base)
        pb.redirectErrorStream(true)
        val p = pb.start()
        val line = StringBuilder()
        val tail = StringBuilder()
        val percent = Pattern.compile("^\\s*(\\d{1,3})\\s+\\S+\\s+\\d")
        InputStreamReader(p.inputStream, Charsets.UTF_8).use { input ->
            while (true) {
                val c = input.read()
                if (c < 0) break
                if (c != '\r'.code && c != '\n'.code) {
                    line.append(c.toChar())
                    continue
                }
                // curl's progress: "  45  180M   45 81.2M ..." on one line, rewritten with \r
                val m = percent.matcher(line)
                if (m.find()) status("下载 Claude Code " + m.group(1) + "%")
                else if (line.isNotEmpty()) tail.append(line).append('\n')
                line.setLength(0)
            }
        }
        try {
            if (p.waitFor() != 0 || !hasClaude()) {
                val t = tail.toString().trim()
                throw IOException("没能装上 Claude Code" + if (t.isEmpty()) "" else "：" + t.substring(max(0, t.length - 300)))
            }
        } catch (e: InterruptedException) {
            p.destroy()
            throw IOException("被打断了")
        }
    }

    /**
     * Unpacks proot and the userland over what is there (files in /root and installed packages
     * stay), downloading the userland first when the APK does not carry it. Tells how far it is.
     */
    @Throws(IOException::class)
    fun install(status: (String) -> Unit) {
        val id = bundled() ?: throw IOException("这个安装包里没有运行环境")
        if (!unpacked()) unpack(id, status)
        if (!hasClaude()) installClaude(status)
    }

    @Throws(IOException::class)
    private fun unpack(id: String, status: (String) -> Unit) {
        val assets = context.assets
        val inApk = try {
            assets.open("runtime/rootfs.tar.xz").close()
            true
        } catch (e: IOException) {
            false
        }
        val download = File(base, "rootfs-$id.tar.xz")
        if (!inApk) fetch(read(assets.open("runtime/rootfs.url")).trim(), read(assets.open("runtime/rootfs.sha256")).trim(), download, status)
        mkdirs(bin)
        mkdirs(tmp)
        for (name in PROOT_FILES) {
            val f = File(bin, name)
            f.delete()
            assets.open("runtime/$name").use { input -> FileOutputStream(f).use { out -> copy(input, out) } }
            chmod(f, "700".toInt(8))
        }
        marker().delete()
        val total = if (inApk) assetLength(assets, "runtime/rootfs.tar.xz") else download.length()
        val source = if (inApk) assets.open("runtime/rootfs.tar.xz", AssetManager.ACCESS_STREAMING) else FileInputStream(download)
        val raw = object : FilterInputStream(source) {
            private var done = 0L
            private var shown = -1

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                val n = super.read(b, off, len)
                if (n > 0) {
                    done += n
                    val p = (done * 100 / max(1, total)).toInt()
                    if (p != shown) {
                        shown = p
                        status("解压 $p%")
                    }
                }
                return n
            }
        }
        BufferedInputStream(XZInputStream(BufferedInputStream(raw, 1 shl 16)), 1 shl 16).use { untar(it, rootfs) }
        download.delete()
        mkdirs(File(rootfs, "root/work"))
        mkdirs(File(rootfs, "opt/ccapp"))
        FileOutputStream(marker()).use { it.write(id.toByteArray()) }
    }

    /**
     * Downloads the userland to the file, going on from what an earlier try left there, and checks
     * it against its SHA-256; a file that does not match is deleted, to start over next time.
     */
    @Throws(IOException::class)
    private fun fetch(url: String, sha256: String, to: File, status: (String) -> Unit) {
        // Another userland's download, left from before, is of no use now.
        base.listFiles { _, n -> n.startsWith("rootfs-") && n.endsWith(".tar.xz") && n != to.name }?.forEach { it.delete() }
        // A connection that drops is taken up again where it stopped, a few times before giving up.
        var attempt = 1
        while (true) {
            try {
                if (fetchRest(url, to, status)) break
                throw IOException("下载中断")
            } catch (e: IOException) {
                if (attempt >= 5) throw IOException("下载运行环境失败：" + e.message, e)
                status("网络断了，正在重连…")
                try {
                    Thread.sleep(2000L * attempt)
                } catch (i: InterruptedException) {
                    throw IOException("已取消")
                }
            }
            attempt++
        }
        status("校验…")
        if (!sha256.equals(sha256(to), ignoreCase = true)) {
            to.delete()
            throw IOException("下载的运行环境不完整，请重试")
        }
    }

    // ---- running ----

    /**
     * The command running args in the userland (as the app's uid, in /root): the root shell and
     * a chroot when root is given, proot otherwise. The environment for it is in [env].
     */
    @Throws(IOException::class)
    fun command(vararg args: String): List<String> {
        prepare()
        return if (useChroot()) nativeCommand(args) else prootCommand(args)
    }

    /**
     * Answers whether root is there, asking for it the first time (the manager shows its prompt
     * then) and again after a refusal, not within the minute.
     */
    private fun useChroot(): Boolean {
        if (rootState == 1) return true
        synchronized(rootLock) {
            if (rootState == 1) return true
            if (rootState == 2 && SystemClock.elapsedRealtime() - rootFailedAt < 60_000) return false
            val version = try {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName
            } catch (e: Exception) {
                "?"
            }
            appendLog("app $version (uid " + Os.getuid() + "): asking for root")
            val su = probeRoot()
            if (su != null) {
                suPath = su
                val (works, why) = chrootWorks(su)
                if (works) {
                    rootState = 1
                    nativeMode = true
                    appendLog("root: granted, the userland runs as a chroot")
                    return true
                }
                appendLog("root: granted but the chroot did not start; using proot" + if (why.isEmpty()) "" else ": $why")
            }
            rootState = 2
            rootFailedAt = SystemClock.elapsedRealtime()
            nativeMode = false
            if (su == null) appendLog("root: not given, the userland runs under proot")
            return false
        }
    }

    /**
     * The su path that gave root, trying the usual ones; null when none does. Each try is written
     * to the run log with its exit code and what it printed: which path failed, and how, is what
     * tells a missing su from one the manager refused.
     */
    private fun probeRoot(): String? {
        for (path in SU_PATHS) {
            try {
                val pb = ProcessBuilder(path, "-c", "id")
                pb.redirectErrorStream(true)
                // The app's own environment, with a PATH su's `id` is found through.
                pb.environment()["PATH"] = "/system/bin:/system/xbin"
                val p = pb.start()
                val out = StringBuilder()
                val reader = drain(p.inputStream, out)
                if (!p.waitFor(20, TimeUnit.SECONDS)) {
                    p.destroy()
                    appendLog("su $path: no answer in 20 s")
                    return null // asked, and left unanswered: not root this time
                }
                reader.join(1000)
                val text = out.toString().trim().replace('\n', ' ')
                val code = p.exitValue()
                if (text.contains("uid=0")) {
                    appendLog("su $path: root (exit $code)")
                    return path
                }
                appendLog("su $path: exit $code" + if (text.isEmpty()) "" else ": " + text.takeLast(240))
            } catch (e: IOException) {
                appendLog("su $path: " + (e.message ?: e.toString()))
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return null
            }
        }
        return null
    }

    /**
     * Runs `/bin/busybox true` through the whole native start (unshare, the mounts, chroot,
     * setpriv) once: root being there says nothing of whether this Android lets a chroot be made.
     * False, with what it printed, when it does not, so proot is used instead of a bridge that
     * never starts.
     */
    private fun chrootWorks(su: String): Pair<Boolean, String> {
        val line = shQuote(nativeScript.path) + " " + shQuote("/bin/busybox") + " " + shQuote("true")
        return try {
            val pb = ProcessBuilder(su, "-c", line)
            pb.redirectErrorStream(true)
            val env = pb.environment()
            env.clear()
            env["PATH"] = "/system/bin:/system/xbin"
            val p = pb.start()
            val out = StringBuilder()
            val reader = drain(p.inputStream, out)
            val ended = p.waitFor(30, TimeUnit.SECONDS)
            if (!ended) p.destroy()
            reader.join(1000)
            val text = out.toString().trim()
            val ok = ended && p.exitValue() == 0
            Pair(ok, if (ok) "" else text.substring(max(0, text.length - 300)))
        } catch (e: IOException) {
            Pair(false, e.message ?: e.toString())
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            Pair(false, "interrupted")
        }
    }

    /** Reads a process's output on a thread of its own, into out; joined by the caller. */
    private fun drain(input: InputStream, out: StringBuilder): Thread {
        val reader = Thread {
            try {
                InputStreamReader(input, Charsets.UTF_8).use { source ->
                    val buf = CharArray(4096)
                    while (true) {
                        val n = source.read(buf)
                        if (n <= 0) break
                        out.append(buf, 0, n)
                    }
                }
            } catch (ignored: IOException) {
                // what was read
            }
        }
        reader.isDaemon = true
        reader.start()
        return reader
    }

    /** The root shell running the native chroot script with args. */
    private fun nativeCommand(args: Array<out String>): List<String> {
        val line = StringBuilder(shQuote(nativeScript.path))
        for (a in args) line.append(' ').append(shQuote(a))
        return listOf(suPath ?: SU_PATHS[0], "-c", line.toString())
    }

    /** The proot command running args (inside the userland, as root in /root). */
    private fun prootCommand(args: Array<out String>): List<String> {
        val c = ArrayList<String>()
        c += File(bin, "proot").path
        c += listOf("--kill-on-exit", "--link2symlink", "-0", "-r", rootfs.path)
        for (b in arrayOf("/dev", "/proc", "/sys", "/proc/self/fd:/dev/fd")) c += listOf("-b", b)
        c += listOf("-b", File(rootfs, "tmp").path + ":/dev/shm")
        val procs = File(base, "proc")
        for (name in arrayOf("stat", "loadavg", "uptime", "version", "vmstat")) {
            if (readable(File("/proc/$name"))) continue
            c += listOf("-b", File(procs, name).path + ":/proc/" + name)
        }
        // Android's own system, for its programs (app_process for rish): at the same paths.
        for (d in ANDROID_DIRS) if (File(d).exists()) c += listOf("-b", d)
        val sd = storage()
        if (sd != null) c += listOf("-b", sd.path + ":/sdcard", "-b", "/storage")
        c += listOf("-w", HOME, "/usr/bin/env", "-i", "HOME=$HOME", "USER=root",
            "PATH=/usr/local/bin:/usr/bin:/bin:/usr/local/sbin:/usr/sbin:/sbin",
            "SHELL=/bin/bash", "LANG=C.UTF-8", "TERM=xterm-256color", "TMPDIR=/tmp",
            // Root in proot is this app's own user: let Claude Code skip permission prompts as root.
            "IS_SANDBOX=1",
            // Claude Code changes only when claude-update is run.
            "DISABLE_AUTOUPDATER=1",
            "USE_BUILTIN_RIPGREP=0")
        c.addAll(args)
        return c
    }

    fun env(pb: ProcessBuilder) {
        pb.environment().clear()
        for (kv in env()) pb.environment()[kv.substring(0, kv.indexOf('='))] = kv.substring(kv.indexOf('=') + 1)
    }

    /** The environment the command runs with, as KEY=value: proot's own, or the root shell's. */
    fun env(): Array<String> = if (nativeMode) {
        arrayOf("PATH=/system/bin:/system/xbin", "HOME=" + base.path)
    } else {
        arrayOf("PROOT_TMP_DIR=" + tmp.path, "PROOT_LOADER=" + File(bin, "loader").path,
            "LD_LIBRARY_PATH=" + bin.path, "PATH=/system/bin", "HOME=" + base.path)
    }

    /**
     * rish in the userland, as in Termux: Android's app_process running Shizuku's rish dex, taken
     * from the installed Shizuku (again only when Shizuku changes), with Android's environment.
     */
    @Throws(IOException::class)
    private fun writeRish() {
        val dir = File(rootfs, "opt/ccapp")
        val dex = File(dir, "rish_shizuku.dex")
        try {
            val ai = context.packageManager.getApplicationInfo(SHIZUKU, 0)
            val stampFile = File(dir, "rish.stamp")
            val stamp = ai.sourceDir + " " + File(ai.sourceDir).lastModified()
            val old = if (stampFile.exists()) read(FileInputStream(stampFile)) else ""
            if (stamp != old || !dex.exists()) {
                ZipFile(ai.sourceDir).use { apk ->
                    val e = apk.getEntry("assets/rish_shizuku.dex")
                    if (e != null) {
                        mkdirs(dir)
                        dex.delete()
                        apk.getInputStream(e).use { input -> FileOutputStream(dex).use { out -> copy(input, out) } }
                        Os.chmod(dex.path, "400".toInt(8)) // Android 14+ loads only a dex nobody can write
                        write(stampFile, stamp)
                    }
                }
            }
        } catch (e: PackageManager.NameNotFoundException) {
            // no Shizuku: rish says so, and nothing else depends on it
        } catch (e: ErrnoException) {
            // its APK could not be read
        } catch (e: IOException) {
            // its APK could not be read
        }
        val env = StringBuilder()
        for ((k, v) in System.getenv()) {
            if (k.startsWith("ANDROID_") || k.endsWith("CLASSPATH") || k == "EXTERNAL_STORAGE") {
                env.append("export ").append(k).append("='").append(v.replace("'", "'\\''")).append("'\n")
            }
        }
        write(File(dir, "android.env"), env.toString())
        // Android's linker reads its namespaces from /linkerconfig, a directory proot may not
        // bind (apps can read the file, not look at the directory): a copy, made at each boot.
        val ld = File("/linkerconfig/ld.config.txt")
        val copyLd = File(rootfs, "linkerconfig/ld.config.txt")
        if (ld.canRead() && ld.length() != copyLd.length()) {
            try {
                FileInputStream(ld).use { input ->
                    mkdirs(copyLd.parentFile)
                    FileOutputStream(copyLd).use { out -> copy(input, out) }
                }
            } catch (ignored: IOException) {
                // rish will say what it misses
            }
        }
        val rish = File(rootfs, "usr/local/bin/rish")
        write(rish, "#!/bin/sh\n" +
            "dex=/opt/ccapp/rish_shizuku.dex\n" +
            "[ -f \$dex ] || { echo 'rish: 没有安装 Shizuku' >&2; exit 1; }\n" +
            ". /opt/ccapp/android.env\n" +
            "export RISH_APPLICATION_ID=" + context.packageName + "\n" +
            "exec /system/bin/app_process -Djava.class.path=\$dex /system/bin --nice-name=rish " +
            "rikka.shizuku.shell.ShizukuShellLoader \"\$@\"\n")
        try {
            Os.chmod(rish.path, "755".toInt(8))
        } catch (e: ErrnoException) {
            throw IOException(e)
        }
    }

    /**
     * Per-start files: DNS servers of the current network, hosts, and stand-ins for /proc files
     * Android hides. Each is replaced whole: programs running meanwhile read them.
     */
    @Throws(IOException::class)
    private fun prepare() {
        synchronized(PREPARING) { prepareFiles() }
    }

    @Throws(IOException::class)
    private fun prepareFiles() {
        mkdirs(tmp)
        writeRish()
        prepareMounts()
        writeNativeScript()
        val dns = LinkedHashSet<String>()
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val n = cm.activeNetwork
            val lp = if (n == null) null else cm.getLinkProperties(n)
            lp?.dnsServers?.forEach { a -> a.hostAddress?.let { dns.add(it) } }
        } catch (ignored: RuntimeException) {
            // fallbacks below
        }
        dns.add("8.8.8.8")
        val resolv = StringBuilder()
        for (d in dns) if (!d.contains("%")) resolv.append("nameserver ").append(d).append('\n')
        replace(File(rootfs, "etc/resolv.conf"), resolv.toString())
        replace(File(rootfs, "etc/hosts"), "127.0.0.1 localhost\n::1 localhost ip6-localhost ip6-loopback\n")
        val procs = File(base, "proc")
        mkdirs(procs)
        val cpus = Runtime.getRuntime().availableProcessors()
        val stat = StringBuilder("cpu  1000 0 1000 100000 0 0 0 0 0 0\n")
        for (i in 0 until cpus) stat.append("cpu").append(i).append(" 100 0 100 10000 0 0 0 0 0 0\n")
        stat.append("intr 0\nctxt 0\nbtime 0\nprocesses 1\nprocs_running 1\nprocs_blocked 0\n")
        replace(File(procs, "stat"), stat.toString())
        replace(File(procs, "loadavg"), "0.10 0.10 0.10 1/100 1\n")
        val up = SystemClock.elapsedRealtime() / 1000
        replace(File(procs, "uptime"), "$up.00 $up.00\n")
        replace(File(procs, "version"), "Linux version " + System.getProperty("os.version") + " (android)\n")
        replace(File(procs, "vmstat"), "nr_free_pages 100000\n")
    }

    /**
     * The folders a native chroot mounts over (Android's own directories, storage), and a passwd
     * and group entry for the app's uid, which the userland otherwise does not know.
     */
    @Throws(IOException::class)
    private fun prepareMounts() {
        for (d in MOUNT_DIRS) mkdirs(File(rootfs, d))
        val uid = Os.getuid()
        val gid = Os.getgid()
        val passwd = File(rootfs, "etc/passwd")
        val users = try {
            read(FileInputStream(passwd))
        } catch (e: IOException) {
            ""
        }
        if (users.isNotEmpty() && users.lineSequence().none { it.split(':').getOrNull(2) == uid.toString() }) {
            replace(passwd, (if (users.endsWith("\n")) users else users + "\n") + "ccapp:x:$uid:$gid:Claude Code:/root:/bin/bash\n")
        }
        val group = File(rootfs, "etc/group")
        val groups = try {
            read(FileInputStream(group))
        } catch (e: IOException) {
            ""
        }
        if (groups.isNotEmpty() && groups.lineSequence().none { it.split(':').getOrNull(2) == gid.toString() }) {
            replace(group, (if (groups.endsWith("\n")) groups else groups + "\n") + "ccapp:x:$gid:\n")
        }
    }

    /** This process's supplementary groups, as /proc/self/status lists them (none when unread). */
    private fun processGroups(): IntArray = try {
        val status = read(FileInputStream("/proc/self/status"))
        val line = status.lineSequence().firstOrNull { it.startsWith("Groups:") }
        if (line == null) IntArray(0) else line.removePrefix("Groups:").trim().split(Regex("\\s+"))
            .mapNotNull { it.toIntOrNull() }.toIntArray()
    } catch (e: IOException) {
        IntArray(0)
    }

    /**
     * Writes the script the root shell runs: a private mount namespace (when unshare works), the
     * Android side mounted in (/proc, /sys, /dev, /system and the rest, storage), the chroot, then
     * setpriv down to the app's own uid and gid so what the userland writes stays the app's (the
     * bridge's socket is 0600 and must be this app's to connect to). Generated before each start:
     * the uid, the groups and whether storage is shared may have changed.
     */
    private fun writeNativeScript() {
        val uid = Os.getuid()
        val gid = Os.getgid()
        val groups = processGroups()
        val sb = StringBuilder()
        sb.append("#!/system/bin/sh\n")
        sb.append("# Written by ClaudeCode-Android: the userland as a native chroot (the phone is rooted).\n")
        sb.append("R=").append(shQuote(rootfs.path)).append('\n')
        sb.append("PATH=/system/bin:/system/xbin\n").append("export PATH\n")
        sb.append("if [ \"\$CCAPP_NS\" != 1 ]; then\n")
        sb.append("  if /system/bin/toybox unshare -m /system/bin/toybox true 2>/dev/null; then\n")
        sb.append("    CCAPP_NS=1; export CCAPP_NS\n")
        sb.append("    exec /system/bin/toybox unshare -m /system/bin/sh \"\$0\" \"\$@\"\n")
        sb.append("  fi\n")
        sb.append("fi\n")
        sb.append("T=/system/bin/toybox\n")
        // The app's own mount namespace is a slave of init's (mountinfo: master:), so mounts
        // made here go no further even when unshare is not available.
        sb.append("\$T mount --bind /proc \"\$R/proc\" || { echo 'ccapp: cannot mount /proc' >&2; exit 1; }\n")
        sb.append("\$T mount --bind /sys \"\$R/sys\" 2>/dev/null\n")
        sb.append("\$T mount --bind /dev \"\$R/dev\" || { echo 'ccapp: cannot mount /dev' >&2; exit 1; }\n")
        sb.append("[ -d /dev/pts ] && \$T mount --bind /dev/pts \"\$R/dev/pts\" 2>/dev/null\n")
        sb.append("if [ \"\$CCAPP_NS\" = 1 ]; then\n")
        sb.append("  mkdir -p /dev/shm 2>/dev/null\n")
        sb.append("  \$T mount -t tmpfs tmpfs \"\$R/dev/shm\" 2>/dev/null\n")
        sb.append("  chmod 1777 \"\$R/dev/shm\" 2>/dev/null\n")
        sb.append("fi\n")
        for (d in ANDROID_DIRS) {
            val name = d.substring(1)
            sb.append("[ -d ").append(d).append(" ] && \$T mount --bind ").append(d).append(" \"\$R/").append(name).append("\" 2>/dev/null\n")
        }
        if (storage() != null) {
            sb.append("[ -d /sdcard ] && \$T mount --bind /sdcard \"\$R/sdcard\" 2>/dev/null\n")
            sb.append("[ -d /storage ] && \$T mount --bind /storage \"\$R/storage\" 2>/dev/null\n")
        }
        sb.append("cd \"\$R/root\" || cd \"\$R\" || exit 1\n")
        sb.append("exec /system/bin/toybox chroot \"\$R\" /bin/setpriv")
        sb.append(" --reuid=").append(uid).append(" --regid=").append(gid)
        val extra = groups.filter { it != gid }
        if (extra.isEmpty()) sb.append(" --clear-groups")
        else sb.append(" --groups=").append(extra.joinToString(","))
        // No capability flags: util-linux may drop root before touching them, and a non-setuid
        // exec at a nonzero uid clears them anyway.
        sb.append(" /usr/bin/env -i")
        for (kv in CHROOT_ENV) sb.append(' ').append(shQuote(kv))
        sb.append(" \"\$@\"\n")
        replace(nativeScript, sb.toString())
        try {
            Os.chmod(nativeScript.path, 448) // 0700
        } catch (ignored: ErrnoException) {
            // readable by its owner anyway
        }
    }

    /** A word as the root shell's sh reads it back. */
    private fun shQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    /** A line for the run log (what root was, or was not, given), appended on the calling thread. */
    private fun appendLog(text: String) {
        try {
            val stamp = SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(Date())
            FileOutputStream(log, true).use { it.write(("[app] $stamp $text\n").toByteArray()) }
        } catch (ignored: IOException) {
            // no log
        }
    }

    @Throws(IOException::class)
    fun writeBridge() {
        replace(File(rootfs, "opt/ccapp/bridge.py"), read(context.assets.open("bridge.py")))
    }

    /** A file inside the userland, by its path there. */
    fun file(path: String): File = File(rootfs, if (path.startsWith("/")) path.substring(1) else path)

    /** The running bridge's pid (it writes it), or 0: none, or a process that got its pid since. */
    fun bridgePid(): Int = try {
        val pid = read(FileInputStream(file("$HOME/.ccapp/bridge.pid"))).trim().toInt()
        val cmd = read(FileInputStream("/proc/$pid/cmdline"))
        if (cmd.contains("bridge.py")) pid else 0
    } catch (e: IOException) {
        0
    } catch (e: NumberFormatException) {
        0
    }

    companion object {
        const val HOME = "/root"
        private val PROOT_FILES = arrayOf("proot", "loader", "libtalloc.so.2", "libandroid-shmem.so")
        /** Where a rooted phone's su usually is; /system/bin/su is KernelSU's, made by its hook. */
        private val SU_PATHS = arrayOf("/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su",
            "/debug_ramdisk/su", "/data/adb/ksu/bin/su", "/data/adb/magisk/su", "/data/adb/ap/bin/su")
        private val ANDROID_DIRS = arrayOf("/system", "/apex", "/vendor", "/product", "/system_ext", "/odm", "/data")
        /** Folders inside the userland that mounts go over (made before each chroot). */
        private val MOUNT_DIRS = arrayOf("proc", "sys", "dev", "dev/pts", "tmp", "sdcard", "storage",
            "system", "apex", "vendor", "product", "system_ext", "odm", "data", "linkerconfig", "opt/ccapp")
        /** What the chroot runs with: the same as under proot. */
        private val CHROOT_ENV = arrayOf(
            "HOME=/root", "USER=root", "PATH=/usr/local/bin:/usr/bin:/bin:/usr/local/sbin:/usr/sbin:/sbin",
            "SHELL=/bin/bash", "LANG=C.UTF-8", "TERM=xterm-256color", "TMPDIR=/tmp",
            // The userland's own uid is the app's, not root: let Claude Code skip prompts as it did.
            "IS_SANDBOX=1",
            // Claude Code changes only when claude-update is run.
            "DISABLE_AUTOUPDATER=1", "USE_BUILTIN_RIPGREP=0")
        /** Shizuku's app: its APK carries the dex rish runs. */
        private const val SHIZUKU = "moe.shizuku.privileged.api"
        /** One at a time: the bridge's start and the terminal's may run together. */
        private val PREPARING = Any()

        /** Downloads what the file lacks; true when it is all there. */
        @Throws(IOException::class)
        private fun fetchRest(from: String, to: File, status: (String) -> Unit): Boolean {
            var url = from
            var redirects = 0
            while (true) {
                val have = to.length()
                val http = URL(url).openConnection() as HttpURLConnection
                http.connectTimeout = 20_000
                http.readTimeout = 30_000
                http.instanceFollowRedirects = false
                if (have > 0) http.setRequestProperty("Range", "bytes=$have-")
                try {
                    val code = http.responseCode
                    if (code / 100 == 3 && redirects < 5) {
                        // GitHub sends release files from another host; followed here, keeping the range.
                        url = URL(URL(url), http.getHeaderField("Location")).toString()
                        redirects++
                        continue
                    }
                    if (code == 416) return true // all of it is here already
                    if (code != 200 && code != 206) throw IOException("HTTP $code")
                    val resumed = code == 206
                    val length = http.contentLengthLong
                    val total = if (length < 0) -1 else (if (resumed) have else 0) + length
                    var done = if (resumed) have else 0
                    var shown = -1
                    http.inputStream.use { input ->
                        FileOutputStream(to, resumed).use { out ->
                            val buf = ByteArray(1 shl 16)
                            while (true) {
                                val n = input.read(buf)
                                if (n <= 0) break
                                out.write(buf, 0, n)
                                done += n
                                val p = if (total > 0) (done * 100 / total).toInt() else 0
                                if (p != shown) {
                                    shown = p
                                    status(String.format(Locale.ROOT, "下载 %d%% · %.0f / %.0f MB", p, done / 1048576.0, total / 1048576.0))
                                }
                            }
                        }
                    }
                    return total < 0 || done >= total
                } finally {
                    http.disconnect()
                }
            }
        }

        @Throws(IOException::class)
        private fun sha256(f: File): String = try {
            FileInputStream(f).use { input ->
                val md = MessageDigest.getInstance("SHA-256")
                val buf = ByteArray(1 shl 16)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    md.update(buf, 0, n)
                }
                md.digest().joinToString("") { String.format("%02x", it) }
            }
        } catch (e: NoSuchAlgorithmException) {
            throw IOException(e)
        }

        // ---- tar ----

        @Throws(IOException::class)
        private fun untar(input: InputStream, into: File) {
            val root = into.canonicalPath
            // Folders checked to be inside (a symbolic link made later may lead out: checked again).
            val inside = HashSet<String>()
            val header = ByteArray(512)
            var longName: String? = null
            var longLink: String? = null
            while (true) {
                if (!readFully(input, header)) return
                if (header[0].toInt() == 0) return // end of archive
                var name = field(header, 0, 100)
                val prefix = field(header, 345, 155)
                if (prefix.isNotEmpty()) name = "$prefix/$name"
                val mode = octal(header, 100, 8).toInt()
                val size = octal(header, 124, 12)
                val type = (header[156].toInt() and 0xFF).toChar()
                var link = field(header, 157, 100)
                if (type == 'L' || type == 'K' || type == 'x') {
                    val data = readData(input, size)
                    when (type) {
                        'L' -> longName = cstring(data)
                        'K' -> longLink = cstring(data)
                        else -> {
                            pax(data, "path")?.let { longName = it }
                            pax(data, "linkpath")?.let { longLink = it }
                        }
                    }
                    continue
                }
                longName?.let { name = it; longName = null }
                longLink?.let { link = it; longLink = null }
                while (name.startsWith("./")) name = name.substring(2)
                while (name.endsWith("/")) name = name.substring(0, name.length - 1)
                val target = File(into, name)
                // Only inside the userland: no "..", and no folder that a symbolic link leads elsewhere.
                if (!relative(name) || !within(target.parentFile!!, root, inside)) {
                    skip(input, (size + 511) / 512 * 512)
                    continue
                }
                val perm = mode and "7777".toInt(8)
                when (type) {
                    '5' -> {
                        if (!target.isDirectory || Files.isSymbolicLink(target.toPath())) {
                            remove(target)
                            mkdirs(target)
                        }
                        chmod(target, perm)
                    }
                    '2' -> {
                        remove(target)
                        mkdirs(target.parentFile)
                        try {
                            Os.symlink(link, target.path)
                        } catch (e: ErrnoException) {
                            throw IOException("symlink " + name + ": " + e.message)
                        }
                        val made = target.path
                        inside.removeIf { d -> d == made || d.startsWith("$made/") }
                    }
                    '1' -> {
                        // Hard links become copies (the archive is made without them anyway), of a file inside.
                        val from = if (link.startsWith("./")) link.substring(2) else link
                        val source = File(into, from)
                        if (relative(from) && within(source, root, inside)) {
                            remove(target)
                            FileInputStream(source).use { src -> FileOutputStream(target).use { out -> copy(src, out) } }
                            chmod(target, perm)
                        }
                    }
                    '0', '\u0000', '7' -> {
                        remove(target) // never write into a running binary or through an old symlink
                        mkdirs(target.parentFile)
                        FileOutputStream(target).use { out ->
                            val buf = ByteArray(1 shl 16)
                            var left = size
                            while (left > 0) {
                                val n = input.read(buf, 0, min(buf.size.toLong(), left).toInt())
                                if (n < 0) throw IOException("archive ends early")
                                out.write(buf, 0, n)
                                left -= n
                            }
                        }
                        skip(input, (512 - size % 512) % 512)
                        chmod(target, perm)
                        continue
                    }
                    else -> {} // devices and fifos: not needed
                }
                skip(input, (size + 511) / 512 * 512)
            }
        }

        /** A path in the archive that stays where it is put: not absolute, no "..". */
        private fun relative(name: String): Boolean =
            name.isNotEmpty() && !name.startsWith("/") && name.split("/").none { it == ".." }

        /** f, with every link on its way followed, is in root; folders found inside are remembered. */
        @Throws(IOException::class)
        private fun within(f: File, root: String, inside: MutableSet<String>): Boolean {
            if (inside.contains(f.path)) return true
            val real = f.canonicalPath
            val isIn = real == root || real.startsWith("$root/")
            if (isIn && f.isDirectory) inside.add(f.path)
            return isIn
        }

        @Throws(IOException::class)
        private fun readFully(input: InputStream, buf: ByteArray): Boolean {
            var off = 0
            while (off < buf.size) {
                val n = input.read(buf, off, buf.size - off)
                if (n < 0) {
                    if (off == 0) return false
                    throw IOException("archive ends early")
                }
                off += n
            }
            return true
        }

        @Throws(IOException::class)
        private fun readData(input: InputStream, size: Long): ByteArray {
            val data = ByteArray(size.toInt())
            if (size > 0 && !readFully(input, data)) throw IOException("archive ends early")
            skip(input, (512 - size % 512) % 512)
            return data
        }

        @Throws(IOException::class)
        private fun skip(input: InputStream, count: Long) {
            val buf = ByteArray(8192)
            var n = count
            while (n > 0) {
                val r = input.read(buf, 0, min(buf.size.toLong(), n).toInt())
                if (r < 0) throw IOException("archive ends early")
                n -= r
            }
        }

        private fun field(h: ByteArray, off: Int, len: Int): String {
            var end = off
            while (end < off + len && h[end].toInt() != 0) end++
            return String(h, off, end - off, Charsets.UTF_8)
        }

        private fun cstring(data: ByteArray): String {
            var end = 0
            while (end < data.size && data[end].toInt() != 0) end++
            return String(data, 0, end, Charsets.UTF_8)
        }

        private fun octal(h: ByteArray, off: Int, len: Int): Long {
            var v = 0L
            for (i in off until off + len) {
                val b = h[i].toInt()
                if (b == 0 || b == ' '.code) {
                    if (v != 0L) break else continue
                }
                if (b < '0'.code || b > '7'.code) break
                v = v * 8 + (b - '0'.code)
            }
            return v
        }

        /** A key from pax extended header records ("<len> key=value\n"). */
        private fun pax(data: ByteArray, key: String): String? {
            var i = 0
            while (i < data.size) {
                var sp = i
                while (sp < data.size && data[sp] != ' '.code.toByte()) sp++
                val len = String(data, i, sp - i, Charsets.US_ASCII).toIntOrNull() ?: return null
                if (sp >= data.size || len <= sp - i + 1 || i + len > data.size) return null
                val rec = String(data, sp + 1, i + len - sp - 2, Charsets.UTF_8)
                if (rec.startsWith("$key=")) return rec.substring(key.length + 1)
                i += len
            }
            return null
        }

        /** Removes what is there unless it is a real folder: a file, or a symbolic link (to a folder too). */
        private fun remove(f: File) {
            try {
                val mode = Os.lstat(f.path).st_mode
                if (OsConstants.S_ISLNK(mode) || !OsConstants.S_ISDIR(mode)) Os.remove(f.path)
            } catch (ignored: ErrnoException) {
                // nothing there
            }
        }

        /** Bytes under a file or folder, links not followed. */
        @JvmStatic
        fun size(f: File): Long {
            var total = 0L
            try {
                Files.walkFileTree(f.toPath(), object : SimpleFileVisitor<Path>() {
                    override fun visitFile(p: Path, a: BasicFileAttributes): FileVisitResult {
                        total += a.size()
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFileFailed(p: Path, e: IOException): FileVisitResult = FileVisitResult.CONTINUE
                })
            } catch (ignored: IOException) {
                // what was counted
            }
            return total
        }

        /** Deletes a file, or a folder and all in it; a link, not what it points to. */
        @JvmStatic
        fun delete(f: File) {
            try {
                if (OsConstants.S_ISDIR(Os.lstat(f.path).st_mode)) f.listFiles()?.forEach { delete(it) }
                Os.remove(f.path)
            } catch (ignored: ErrnoException) {
                // gone already, or not ours to delete
            }
        }

        @Throws(IOException::class)
        private fun mkdirs(dir: File?) {
            if (dir != null && !dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) throw IOException("mkdir $dir")
        }

        private fun chmod(f: File, mode: Int) {
            try {
                Os.chmod(f.path, mode)
            } catch (ignored: ErrnoException) {
                // keeps the default mode
            }
        }

        /** Phone storage, shared into the userland as /sdcard once the app may read it. */
        private fun storage(): File? = Environment.getExternalStorageDirectory()?.takeIf { it.canRead() }

        private fun readable(f: File): Boolean = try {
            FileInputStream(f).use { it.read() }
            true
        } catch (e: IOException) {
            false
        } catch (e: SecurityException) {
            false
        }

        // ---- files ----

        @JvmStatic
        @Throws(IOException::class)
        fun write(f: File, text: String) {
            mkdirs(f.parentFile)
            FileOutputStream(f).use { it.write(text.toByteArray()) }
        }

        @Throws(IOException::class)
        private fun assetLength(assets: AssetManager, name: String): Long = assets.openFd(name).use { it.length }

        /**
         * Writes a file others read meanwhile (Claude Code's settings): aside, then moved in, so it is
         * never seen half written. A symbolic link is written through, as it was.
         */
        @JvmStatic
        @Throws(IOException::class)
        fun replace(f: File, text: String) {
            if (Files.isSymbolicLink(f.toPath())) {
                write(f, text)
                return
            }
            val part = File(f.path + ".part")
            write(part, text)
            if (!part.renameTo(f)) {
                part.delete()
                throw IOException("could not replace $f")
            }
        }

        @JvmStatic
        @Throws(IOException::class)
        fun read(input: InputStream): String = input.use {
            val out = ByteArrayOutputStream()
            copy(it, out)
            out.toString("UTF-8")
        }

        @JvmStatic
        @Throws(IOException::class)
        fun copy(input: InputStream, out: OutputStream) {
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                out.write(buf, 0, n)
            }
        }

        /** The last bytes of a file, whole lines. */
        @JvmStatic
        fun tail(f: File, bytes: Int): String = try {
            RandomAccessFile(f, "r").use { r ->
                val start = max(0, r.length() - bytes)
                r.seek(start)
                val b = ByteArray((r.length() - start).toInt())
                r.readFully(b)
                val s = String(b, Charsets.UTF_8)
                if (start > 0 && s.indexOf('\n') >= 0) s.substring(s.indexOf('\n') + 1) else s
            }
        } catch (e: IOException) {
            ""
        }
    }
}
