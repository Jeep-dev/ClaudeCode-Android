package com.jeep.claudecode;

import android.content.Context;
import android.content.res.AssetManager;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.os.Environment;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;

import org.tukaani.xz.XZInputStream;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The Linux userland Claude Code runs in, inside this app: Alpine (with Claude Code, Python and
 * git) run under Termux's proot. Built by code/runtime/*.sh in CI; proot comes in the APK, the
 * userland (rootfs.tar.xz) is downloaded from where CI published it (assets/runtime/rootfs.url),
 * once per userland, unless the APK carries it (a build of its own).
 */
final class Userland {
    static final String HOME = "/root";
    private static final String[] PROOT_FILES = {"proot", "loader", "libtalloc.so.2", "libandroid-shmem.so"};

    final File base, rootfs, bin, tmp, log;
    private final Context context;

    Userland(Context context) {
        this.context = context.getApplicationContext();
        base = this.context.getFilesDir();
        rootfs = new File(base, "rootfs");
        bin = new File(base, "proot");
        tmp = new File(base, "tmp");
        log = new File(base, "runtime.log");
    }

    /** The id of the userland in this APK; null when the APK carries none (a local build). */
    String bundled() {
        try {
            return read(context.getAssets().open("runtime/id")).trim();
        } catch (IOException e) {
            return null;
        }
    }

    private File marker() { return new File(rootfs, ".ccapp-runtime"); }

    /** The userland in this APK is unpacked, and Claude Code is in it. */
    boolean ready() {
        return unpacked() && hasClaude();
    }

    private boolean unpacked() {
        String id = bundled();
        if (id == null) return false;
        try {
            return id.equals(read(new FileInputStream(marker())).trim());
        } catch (IOException e) {
            return false;
        }
    }

    /** /usr/local/bin/claude is there (a link followed inside the userland). */
    private boolean hasClaude() {
        File f = new File(rootfs, "usr/local/bin/claude");
        try {
            String to = Os.readlink(f.getPath());
            f = to.startsWith("/") ? new File(rootfs, to.substring(1)) : new File(f.getParentFile(), to);
        } catch (ErrnoException notLink) {
            // the file itself
        }
        return f.isFile();
    }

    /**
     * Installs Claude Code from Anthropic's releases with the userland's claude-update (the
     * latest, checked against its manifest), telling how far the download is.
     */
    private void installClaude(Consumer<String> status) throws IOException {
        status.accept("下载 Claude Code");
        ProcessBuilder pb = new ProcessBuilder(command("/usr/local/bin/claude-update"));
        env(pb);
        pb.directory(base);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        StringBuilder line = new StringBuilder(), tail = new StringBuilder();
        java.util.regex.Pattern percent = java.util.regex.Pattern.compile("^\\s*(\\d{1,3})\\s+\\S+\\s+\\d");
        try (java.io.Reader in = new java.io.InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8)) {
            for (int c; (c = in.read()) >= 0; ) {
                if (c != '\r' && c != '\n') { line.append((char) c); continue; }
                // curl's progress: "  45  180M   45 81.2M ..." on one line, rewritten with \r
                java.util.regex.Matcher m = percent.matcher(line);
                if (m.find()) status.accept("下载 Claude Code " + m.group(1) + "%");
                else if (line.length() > 0) tail.append(line).append('\n');
                line.setLength(0);
            }
        }
        try {
            if (p.waitFor() != 0 || !hasClaude()) {
                String t = tail.toString().trim();
                throw new IOException("没能装上 Claude Code" + (t.isEmpty() ? "" : "：" + t.substring(Math.max(0, t.length() - 300))));
            }
        } catch (InterruptedException e) {
            p.destroy();
            throw new IOException("被打断了");
        }
    }

    /**
     * Unpacks proot and the userland over what is there (files in /root and installed packages
     * stay), downloading the userland first when the APK does not carry it. Tells how far it is.
     */
    void install(Consumer<String> status) throws IOException {
        String id = bundled();
        if (id == null) throw new IOException("这个安装包里没有运行环境");
        if (!unpacked()) unpack(id, status);
        if (!hasClaude()) installClaude(status);
    }

    private void unpack(String id, Consumer<String> status) throws IOException {
        AssetManager assets = context.getAssets();
        boolean inApk;
        try (InputStream ignored = assets.open("runtime/rootfs.tar.xz")) {
            inApk = true;
        } catch (IOException e) {
            inApk = false;
        }
        File download = new File(base, "rootfs-" + id + ".tar.xz");
        if (!inApk) fetch(read(assets.open("runtime/rootfs.url")).trim(), read(assets.open("runtime/rootfs.sha256")).trim(), download, status);
        mkdirs(bin);
        mkdirs(tmp);
        for (String name : PROOT_FILES) {
            File f = new File(bin, name);
            f.delete();
            try (InputStream in = assets.open("runtime/" + name); OutputStream out = new FileOutputStream(f)) {
                copy(in, out);
            }
            chmod(f, 0700);
        }
        marker().delete();
        long total = inApk ? assetLength(assets, "runtime/rootfs.tar.xz") : download.length();
        long[] done = {0};
        int[] shown = {-1};
        InputStream source = inApk ? assets.open("runtime/rootfs.tar.xz", AssetManager.ACCESS_STREAMING) : new FileInputStream(download);
        InputStream raw = new FilterInputStream(source) {
            @Override public int read(byte[] b, int off, int len) throws IOException {
                int n = super.read(b, off, len);
                if (n > 0) {
                    done[0] += n;
                    int p = (int) (done[0] * 100 / Math.max(1, total));
                    if (p != shown[0]) { shown[0] = p; status.accept("解压 " + p + "%"); }
                }
                return n;
            }
        };
        try (InputStream in = new BufferedInputStream(new XZInputStream(new BufferedInputStream(raw, 1 << 16)), 1 << 16)) {
            untar(in, rootfs);
        }
        download.delete();
        mkdirs(new File(rootfs, "root/work"));
        mkdirs(new File(rootfs, "opt/ccapp"));
        try (OutputStream out = new FileOutputStream(marker())) {
            out.write(id.getBytes(StandardCharsets.UTF_8));
        }
    }

    /**
     * Downloads the userland to the file, going on from what an earlier try left there, and checks
     * it against its SHA-256; a file that does not match is deleted, to start over next time.
     */
    private void fetch(String url, String sha256, File to, Consumer<String> status) throws IOException {
        // Another userland's download, left from before, is of no use now.
        File[] old = base.listFiles((d, n) -> n.startsWith("rootfs-") && n.endsWith(".tar.xz") && !n.equals(to.getName()));
        if (old != null) for (File f : old) f.delete();
        // A connection that drops is taken up again where it stopped, a few times before giving up.
        for (int attempt = 1; ; attempt++) {
            try {
                if (fetchRest(url, to, status)) break;
                throw new IOException("下载中断");
            } catch (IOException e) {
                if (attempt >= 5) throw new IOException("下载运行环境失败：" + e.getMessage(), e);
                status.accept("网络断了，正在重连…");
                try { Thread.sleep(2000L * attempt); } catch (InterruptedException i) { throw new IOException("已取消"); }
            }
        }
        status.accept("校验…");
        if (!sha256.equalsIgnoreCase(sha256(to))) {
            to.delete();
            throw new IOException("下载的运行环境不完整，请重试");
        }
    }

    /** Downloads what the file lacks; true when it is all there. */
    private static boolean fetchRest(String url, File to, Consumer<String> status) throws IOException {
        for (int redirects = 0; ; redirects++) {
            long have = to.length();
            java.net.HttpURLConnection http = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            http.setConnectTimeout(20_000);
            http.setReadTimeout(30_000);
            http.setInstanceFollowRedirects(false);
            if (have > 0) http.setRequestProperty("Range", "bytes=" + have + "-");
            try {
                int code = http.getResponseCode();
                if (code / 100 == 3 && redirects < 5) {
                    // GitHub sends release files from another host; followed here, keeping the range.
                    url = new java.net.URL(new java.net.URL(url), http.getHeaderField("Location")).toString();
                    continue;
                }
                if (code == 416) return true; // all of it is here already
                if (code != 200 && code != 206) throw new IOException("HTTP " + code);
                boolean resumed = code == 206;
                long length = http.getContentLengthLong();
                long total = length < 0 ? -1 : (resumed ? have : 0) + length;
                long done = resumed ? have : 0;
                int shown = -1;
                try (InputStream in = http.getInputStream(); OutputStream out = new FileOutputStream(to, resumed)) {
                    byte[] buf = new byte[1 << 16];
                    for (int n; (n = in.read(buf)) > 0; ) {
                        out.write(buf, 0, n);
                        done += n;
                        int p = total > 0 ? (int) (done * 100 / total) : 0;
                        if (p != shown) {
                            shown = p;
                            status.accept(String.format(java.util.Locale.ROOT, "下载 %d%% · %.0f / %.0f MB", p, done / 1048576.0, total / 1048576.0));
                        }
                    }
                }
                return total < 0 || done >= total;
            } finally {
                http.disconnect();
            }
        }
    }

    private static String sha256(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[1 << 16];
            for (int n; (n = in.read(buf)) > 0; ) md.update(buf, 0, n);
            StringBuilder hex = new StringBuilder();
            for (byte b : md.digest()) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    // ---- tar ----

    private static void untar(InputStream in, File into) throws IOException {
        String root = into.getCanonicalPath();
        // Folders checked to be inside (a symbolic link made later may lead out: checked again).
        Set<String> inside = new java.util.HashSet<>();
        byte[] header = new byte[512];
        String longName = null, longLink = null;
        while (true) {
            if (!readFully(in, header)) return;
            if (header[0] == 0) return; // end of archive
            String name = field(header, 0, 100);
            String prefix = field(header, 345, 155);
            if (!prefix.isEmpty()) name = prefix + "/" + name;
            int mode = (int) octal(header, 100, 8);
            long size = octal(header, 124, 12);
            char type = (char) header[156];
            String link = field(header, 157, 100);
            if (type == 'L' || type == 'K' || type == 'x') {
                byte[] data = readData(in, size);
                if (type == 'L') longName = cstring(data);
                else if (type == 'K') longLink = cstring(data);
                else {
                    String p = pax(data, "path"), l = pax(data, "linkpath");
                    if (p != null) longName = p;
                    if (l != null) longLink = l;
                }
                continue;
            }
            if (longName != null) { name = longName; longName = null; }
            if (longLink != null) { link = longLink; longLink = null; }
            while (name.startsWith("./")) name = name.substring(2);
            while (name.endsWith("/")) name = name.substring(0, name.length() - 1);
            File target = new File(into, name);
            // Only inside the userland: no "..", and no folder that a symbolic link leads elsewhere.
            if (!relative(name) || !within(target.getParentFile(), root, inside)) { skip(in, size); continue; }
            switch (type) {
                case '5':
                    if (!target.isDirectory() || java.nio.file.Files.isSymbolicLink(target.toPath())) { remove(target); mkdirs(target); }
                    chmod(target, mode & 07777);
                    break;
                case '2':
                    remove(target);
                    mkdirs(target.getParentFile());
                    try {
                        Os.symlink(link, target.getPath());
                    } catch (ErrnoException e) {
                        throw new IOException("symlink " + name + ": " + e.getMessage());
                    }
                    String made = target.getPath();
                    inside.removeIf(d -> d.equals(made) || d.startsWith(made + "/"));
                    break;
                case '1': {
                    // Hard links become copies (the archive is made without them anyway), of a file inside.
                    String from = link.startsWith("./") ? link.substring(2) : link;
                    File source = new File(into, from);
                    if (!relative(from) || !within(source, root, inside)) break;
                    remove(target);
                    try (InputStream src = new FileInputStream(source); OutputStream out = new FileOutputStream(target)) {
                        copy(src, out);
                    }
                    chmod(target, mode & 07777);
                    break;
                }
                case '0': case 0: case '7': {
                    remove(target); // never write into a running binary or through an old symlink
                    mkdirs(target.getParentFile());
                    try (OutputStream out = new FileOutputStream(target)) {
                        byte[] buf = new byte[1 << 16];
                        long left = size;
                        while (left > 0) {
                            int n = in.read(buf, 0, (int) Math.min(buf.length, left));
                            if (n < 0) throw new IOException("archive ends early");
                            out.write(buf, 0, n);
                            left -= n;
                        }
                    }
                    skip(in, (512 - size % 512) % 512);
                    chmod(target, mode & 07777);
                    continue;
                }
                default:
                    break; // devices and fifos: not needed
            }
            skip(in, (size + 511) / 512 * 512);
        }
    }

    /** A path in the archive that stays where it is put: not absolute, no "..". */
    private static boolean relative(String name) {
        if (name.isEmpty() || name.startsWith("/")) return false;
        for (String part : name.split("/")) if (part.equals("..")) return false;
        return true;
    }

    /** f, with every link on its way followed, is in root; folders found inside are remembered. */
    private static boolean within(File f, String root, Set<String> inside) throws IOException {
        if (inside.contains(f.getPath())) return true;
        String real = f.getCanonicalPath();
        boolean in = real.equals(root) || real.startsWith(root + "/");
        if (in && f.isDirectory()) inside.add(f.getPath());
        return in;
    }

    private static boolean readFully(InputStream in, byte[] buf) throws IOException {
        int off = 0;
        while (off < buf.length) {
            int n = in.read(buf, off, buf.length - off);
            if (n < 0) { if (off == 0) return false; throw new IOException("archive ends early"); }
            off += n;
        }
        return true;
    }

    private static byte[] readData(InputStream in, long size) throws IOException {
        byte[] data = new byte[(int) size];
        if (size > 0 && !readFully(in, data)) throw new IOException("archive ends early");
        skip(in, (512 - size % 512) % 512);
        return data;
    }

    private static void skip(InputStream in, long n) throws IOException {
        byte[] buf = new byte[8192];
        while (n > 0) {
            int r = in.read(buf, 0, (int) Math.min(buf.length, n));
            if (r < 0) throw new IOException("archive ends early");
            n -= r;
        }
    }

    private static String field(byte[] h, int off, int len) {
        int end = off;
        while (end < off + len && h[end] != 0) end++;
        return new String(h, off, end - off, StandardCharsets.UTF_8);
    }

    private static String cstring(byte[] data) {
        int end = 0;
        while (end < data.length && data[end] != 0) end++;
        return new String(data, 0, end, StandardCharsets.UTF_8);
    }

    private static long octal(byte[] h, int off, int len) {
        long v = 0;
        for (int i = off; i < off + len; i++) {
            byte b = h[i];
            if (b == 0 || b == ' ') { if (v != 0) break; else continue; }
            if (b < '0' || b > '7') break;
            v = v * 8 + (b - '0');
        }
        return v;
    }

    /** A key from pax extended header records ("<len> key=value\n"). */
    private static String pax(byte[] data, String key) {
        int i = 0;
        while (i < data.length) {
            int sp = i;
            while (sp < data.length && data[sp] != ' ') sp++;
            int len;
            try {
                len = Integer.parseInt(new String(data, i, sp - i, StandardCharsets.US_ASCII));
            } catch (NumberFormatException e) {
                return null;
            }
            if (sp >= data.length || len <= sp - i + 1 || i + len > data.length) return null;
            String rec = new String(data, sp + 1, i + len - sp - 2, StandardCharsets.UTF_8);
            if (rec.startsWith(key + "=")) return rec.substring(key.length() + 1);
            i += len;
        }
        return null;
    }

    /** Removes what is there unless it is a real folder: a file, or a symbolic link (to a folder too). */
    private static void remove(File f) {
        try {
            int mode = Os.lstat(f.getPath()).st_mode;
            if (OsConstants.S_ISLNK(mode) || !OsConstants.S_ISDIR(mode)) Os.remove(f.getPath());
        } catch (ErrnoException ignored) {
            // nothing there
        }
    }

    /** Bytes under a file or folder, links not followed. */
    static long size(File f) {
        long[] total = {0};
        try {
            java.nio.file.Files.walkFileTree(f.toPath(), new java.nio.file.SimpleFileVisitor<java.nio.file.Path>() {
                @Override public java.nio.file.FileVisitResult visitFile(java.nio.file.Path p, java.nio.file.attribute.BasicFileAttributes a) {
                    total[0] += a.size();
                    return java.nio.file.FileVisitResult.CONTINUE;
                }

                @Override public java.nio.file.FileVisitResult visitFileFailed(java.nio.file.Path p, IOException e) {
                    return java.nio.file.FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ignored) {
            // what was counted
        }
        return total[0];
    }

    /** Deletes a file, or a folder and all in it; a link, not what it points to. */
    static void delete(File f) {
        try {
            if (OsConstants.S_ISDIR(Os.lstat(f.getPath()).st_mode)) {
                File[] in = f.listFiles();
                if (in != null) for (File x : in) delete(x);
                Os.remove(f.getPath());
            } else {
                Os.remove(f.getPath());
            }
        } catch (ErrnoException ignored) {
            // gone already, or not ours to delete
        }
    }

    private static void mkdirs(File dir) throws IOException {
        if (dir != null && !dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) throw new IOException("mkdir " + dir);
    }

    private static void chmod(File f, int mode) {
        try {
            Os.chmod(f.getPath(), mode);
        } catch (ErrnoException ignored) {
            // keeps the default mode
        }
    }

    // ---- running ----

    /** Phone storage, shared into the userland as /sdcard once the app may read it. */
    private static File storage() {
        File s = Environment.getExternalStorageDirectory();
        return s != null && s.canRead() ? s : null;
    }

    /**
     * The proot command running args (inside the userland, as root in /root); the environment
     * for it is in {@link #env(ProcessBuilder)}.
     */
    List<String> command(String... args) throws IOException {
        prepare();
        List<String> c = new ArrayList<>();
        c.add(new File(bin, "proot").getPath());
        c.add("--kill-on-exit");
        c.add("--link2symlink");
        c.add("-0");
        c.add("-r");
        c.add(rootfs.getPath());
        for (String b : new String[]{"/dev", "/proc", "/sys", "/proc/self/fd:/dev/fd"}) { c.add("-b"); c.add(b); }
        c.add("-b");
        c.add(new File(rootfs, "tmp").getPath() + ":/dev/shm");
        File procs = new File(base, "proc");
        for (String name : new String[]{"stat", "loadavg", "uptime", "version", "vmstat"}) {
            if (readable(new File("/proc/" + name))) continue;
            c.add("-b");
            c.add(new File(procs, name).getPath() + ":/proc/" + name);
        }
        // Android's own system, for its programs (app_process for rish): at the same paths.
        for (String d : ANDROID_DIRS) {
            if (!new File(d).exists()) continue;
            c.add("-b");
            c.add(d);
        }
        File sd = storage();
        if (sd != null) {
            c.add("-b");
            c.add(sd.getPath() + ":/sdcard");
            c.add("-b");
            c.add("/storage");
        }
        c.add("-w");
        c.add(HOME);
        c.add("/usr/bin/env");
        c.add("-i");
        c.add("HOME=" + HOME);
        c.add("USER=root");
        c.add("PATH=/usr/local/bin:/usr/bin:/bin:/usr/local/sbin:/usr/sbin:/sbin");
        c.add("SHELL=/bin/bash");
        c.add("LANG=C.UTF-8");
        c.add("TERM=xterm-256color");
        c.add("TMPDIR=/tmp");
        // Root in proot is this app's own user: let Claude Code skip permission prompts as root.
        c.add("IS_SANDBOX=1");
        // Claude Code changes only when claude-update is run.
        c.add("DISABLE_AUTOUPDATER=1");
        c.add("USE_BUILTIN_RIPGREP=0");
        for (String a : args) c.add(a);
        return c;
    }

    void env(ProcessBuilder pb) {
        pb.environment().clear();
        for (String kv : env()) pb.environment().put(kv.substring(0, kv.indexOf('=')), kv.substring(kv.indexOf('=') + 1));
    }

    /** proot's own environment, as KEY=value. */
    String[] env() {
        return new String[]{"PROOT_TMP_DIR=" + tmp.getPath(), "PROOT_LOADER=" + new File(bin, "loader").getPath(),
                "LD_LIBRARY_PATH=" + bin.getPath(), "PATH=/system/bin", "HOME=" + base.getPath()};
    }

    private static boolean readable(File f) {
        try (InputStream in = new FileInputStream(f)) {
            in.read();
            return true;
        } catch (IOException | SecurityException e) {
            return false;
        }
    }

    private static final String[] ANDROID_DIRS = {"/system", "/apex", "/vendor", "/product", "/system_ext", "/odm", "/data"};
    /** Shizuku's app: its APK carries the dex rish runs. */
    private static final String SHIZUKU = "moe.shizuku.privileged.api";

    /**
     * rish in the userland, as in Termux: Android's app_process running Shizuku's rish dex, taken
     * from the installed Shizuku (again only when Shizuku changes), with Android's environment.
     */
    private void writeRish() throws IOException {
        File dir = new File(rootfs, "opt/ccapp");
        File dex = new File(dir, "rish_shizuku.dex");
        try {
            android.content.pm.ApplicationInfo ai = context.getPackageManager().getApplicationInfo(SHIZUKU, 0);
            File stampFile = new File(dir, "rish.stamp");
            String stamp = ai.sourceDir + " " + new File(ai.sourceDir).lastModified();
            String old = stampFile.exists() ? read(new FileInputStream(stampFile)) : "";
            if (!stamp.equals(old) || !dex.exists()) {
                try (java.util.zip.ZipFile apk = new java.util.zip.ZipFile(ai.sourceDir)) {
                    java.util.zip.ZipEntry e = apk.getEntry("assets/rish_shizuku.dex");
                    if (e != null) {
                        mkdirs(dir);
                        dex.delete();
                        try (InputStream in = apk.getInputStream(e); OutputStream out = new FileOutputStream(dex)) {
                            copy(in, out);
                        }
                        Os.chmod(dex.getPath(), 0400); // Android 14+ loads only a dex nobody can write
                        write(stampFile, stamp);
                    }
                }
            }
        } catch (android.content.pm.PackageManager.NameNotFoundException | ErrnoException | IOException e) {
            // no Shizuku, or its APK could not be read: rish says so, and nothing else depends on it
        }
        StringBuilder env = new StringBuilder();
        for (java.util.Map.Entry<String, String> e : System.getenv().entrySet()) {
            String k = e.getKey();
            if (k.startsWith("ANDROID_") || k.endsWith("CLASSPATH") || k.equals("EXTERNAL_STORAGE")) {
                env.append("export ").append(k).append("='").append(e.getValue().replace("'", "'\\''")).append("'\n");
            }
        }
        write(new File(dir, "android.env"), env.toString());
        // Android's linker reads its namespaces from /linkerconfig, a directory proot may not
        // bind (apps can read the file, not look at the directory): a copy, made at each boot.
        File ld = new File("/linkerconfig/ld.config.txt");
        File copyLd = new File(rootfs, "linkerconfig/ld.config.txt");
        if (ld.canRead() && ld.length() != copyLd.length()) {
            try (InputStream in = new FileInputStream(ld)) {
                mkdirs(copyLd.getParentFile());
                try (OutputStream out = new FileOutputStream(copyLd)) {
                    copy(in, out);
                }
            } catch (IOException ignored) {
                // rish will say what it misses
            }
        }
        File rish = new File(rootfs, "usr/local/bin/rish");
        write(rish, "#!/bin/sh\n"
                + "dex=/opt/ccapp/rish_shizuku.dex\n"
                + "[ -f $dex ] || { echo 'rish: 没有安装 Shizuku' >&2; exit 1; }\n"
                + ". /opt/ccapp/android.env\n"
                + "export RISH_APPLICATION_ID=" + context.getPackageName() + "\n"
                + "exec /system/bin/app_process -Djava.class.path=$dex /system/bin --nice-name=rish "
                + "rikka.shizuku.shell.ShizukuShellLoader \"$@\"\n");
        try {
            Os.chmod(rish.getPath(), 0755);
        } catch (ErrnoException e) {
            throw new IOException(e);
        }
    }

    /** One at a time: the bridge's start and the terminal's may run together. */
    private static final Object PREPARING = new Object();

    /**
     * Per-start files: DNS servers of the current network, hosts, and stand-ins for /proc files
     * Android hides. Each is replaced whole: programs running meanwhile read them.
     */
    private void prepare() throws IOException {
        synchronized (PREPARING) {
            prepareFiles();
        }
    }

    private void prepareFiles() throws IOException {
        mkdirs(tmp);
        writeRish();
        Set<String> dns = new LinkedHashSet<>();
        try {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            Network n = cm.getActiveNetwork();
            LinkProperties lp = n == null ? null : cm.getLinkProperties(n);
            if (lp != null) for (InetAddress a : lp.getDnsServers()) dns.add(a.getHostAddress());
        } catch (RuntimeException ignored) {
            // fallbacks below
        }
        dns.add("223.5.5.5");
        dns.add("8.8.8.8");
        StringBuilder resolv = new StringBuilder();
        for (String d : dns) if (!d.contains("%")) resolv.append("nameserver ").append(d).append('\n');
        replace(new File(rootfs, "etc/resolv.conf"), resolv.toString());
        replace(new File(rootfs, "etc/hosts"), "127.0.0.1 localhost\n::1 localhost ip6-localhost ip6-loopback\n");
        File procs = new File(base, "proc");
        mkdirs(procs);
        int cpus = java.lang.Runtime.getRuntime().availableProcessors();
        StringBuilder stat = new StringBuilder("cpu  1000 0 1000 100000 0 0 0 0 0 0\n");
        for (int i = 0; i < cpus; i++) stat.append("cpu").append(i).append(" 100 0 100 10000 0 0 0 0 0 0\n");
        stat.append("intr 0\nctxt 0\nbtime 0\nprocesses 1\nprocs_running 1\nprocs_blocked 0\n");
        replace(new File(procs, "stat"), stat.toString());
        replace(new File(procs, "loadavg"), "0.10 0.10 0.10 1/100 1\n");
        long up = android.os.SystemClock.elapsedRealtime() / 1000;
        replace(new File(procs, "uptime"), up + ".00 " + up + ".00\n");
        replace(new File(procs, "version"), "Linux version " + System.getProperty("os.version") + " (android)\n");
        replace(new File(procs, "vmstat"), "nr_free_pages 100000\n");
    }

    void writeBridge() throws IOException {
        replace(new File(rootfs, "opt/ccapp/bridge.py"), read(context.getAssets().open("bridge.py")));
    }

    /**
     * The webview command (assets/webview.py, served by {@link Browser}), and once a skill telling
     * Claude Code of it: one deleted stays deleted.
     */
    void writeWebview() throws IOException {
        replace(new File(rootfs, "opt/ccapp/webview.py"), read(context.getAssets().open("webview.py")));
        File cmd = new File(rootfs, "usr/local/bin/webview");
        replace(cmd, "#!/bin/sh\nexec python3 /opt/ccapp/webview.py \"$@\"\n");
        File stamp = new File(rootfs, "opt/ccapp/webview-skill");
        File skill = file(HOME + "/.claude/skills/webview/SKILL.md");
        if (!stamp.exists()) {
            if (!skill.exists()) write(skill, read(context.getAssets().open("webview-skill.md")));
            write(stamp, "");
        }
        try {
            Os.chmod(cmd.getPath(), 0755);
        } catch (ErrnoException e) {
            throw new IOException(e);
        }
    }

    // ---- files ----

    static void write(File f, String text) throws IOException {
        mkdirs(f.getParentFile());
        try (OutputStream out = new FileOutputStream(f)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static long assetLength(AssetManager assets, String name) throws IOException {
        try (android.content.res.AssetFileDescriptor fd = assets.openFd(name)) {
            return fd.getLength();
        }
    }

    /**
     * Writes a file others read meanwhile (Claude Code's settings): aside, then moved in, so it is
     * never seen half written. A symbolic link is written through, as it was.
     */
    static void replace(File f, String text) throws IOException {
        if (java.nio.file.Files.isSymbolicLink(f.toPath())) { write(f, text); return; }
        File part = new File(f.getPath() + ".part");
        write(part, text);
        if (!part.renameTo(f)) {
            part.delete();
            throw new IOException("could not replace " + f);
        }
    }

    static String read(InputStream in) throws IOException {
        try (InputStream i = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            copy(i, out);
            return out.toString("UTF-8");
        }
    }

    static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[1 << 16];
        for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
    }

    /** A file inside the userland, by its path there. */
    File file(String path) { return new File(rootfs, path.startsWith("/") ? path.substring(1) : path); }

    /** The last bytes of a file, whole lines. */
    static String tail(File f, int bytes) {
        try (java.io.RandomAccessFile r = new java.io.RandomAccessFile(f, "r")) {
            long start = Math.max(0, r.length() - bytes);
            r.seek(start);
            byte[] b = new byte[(int) (r.length() - start)];
            r.readFully(b);
            String s = new String(b, StandardCharsets.UTF_8);
            return start > 0 && s.indexOf('\n') >= 0 ? s.substring(s.indexOf('\n') + 1) : s;
        } catch (IOException e) {
            return "";
        }
    }
}
