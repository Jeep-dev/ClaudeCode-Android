package com.jeep.claudecode;

import android.content.Context;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.Handler;
import android.os.Looper;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * The Claude Code bridge (assets/bridge.py), run by {@link BridgeService} inside this app's
 * userland and reached over HTTP on a Unix socket in this app's own files: no other app can
 * reach it, so nothing is signed. Results and events arrive on the main thread.
 */
final class Bridge {
    /** The bridge's socket in the userland (SOCKET_FILE in bridge.py). */
    static final String SOCKET = Userland.HOME + "/.ccapp/bridge.sock";

    interface Reply { void done(int status, JSONObject data); }
    interface Events { void events(List<JSONObject> batch); void link(boolean up); }

    private final Context context;
    /** {@link #SOCKET} as a path of this app's files. */
    private final String socket;
    private final Events listener;
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private final Handler ui = new Handler(Looper.getMainLooper());
    /** The event stream is being read: set once, so two connects never start two readers. */
    private final java.util.concurrent.atomic.AtomicBoolean reading = new java.util.concurrent.atomic.AtomicBoolean();
    /** Last event seen; -1 asks only for events from now on (nothing from before this launch). */
    private volatile long lastSeq = -1;
    /** The start of the bridge lastSeq counts in (its hello): a count of another bridge starts over. */
    private volatile String lastBridge;
    private final List<JSONObject> pending = new ArrayList<>();
    private boolean flushQueued;
    /** When this app last asked for the bridge to start: a start still under way is not repeated. */
    private volatile long startedAt;

    Bridge(Context context, Events listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        socket = new Userland(this.context).file(SOCKET).getPath();
    }

    /** VERSION in the bridge.py this app carries: a running bridge of another version is replaced. */
    private int version;

    /** Read once, on the first check (off the main thread). */
    private int version() {
        if (version == 0) version = bundledVersion(context);
        return version;
    }

    private static int bundledVersion(Context context) {
        try {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?m)^VERSION = (\\d+)")
                    .matcher(Userland.read(context.getAssets().open("bridge.py")));
            return m.find() ? Integer.parseInt(m.group(1)) : -1;
        } catch (java.io.IOException e) {
            return -1;
        }
    }

    void get(String path, Reply reply) { request("GET", path, null, reply); }

    void post(String path, JSONObject body, Reply reply) { request("POST", path, body.toString(), reply); }

    private void request(String method, String path, String body, Reply reply) {
        pool.execute(() -> answer(exchange(method, path, body), reply));
    }

    private void answer(Object[] r, Reply reply) {
        JSONObject data;
        try {
            data = new JSONObject((String) r[1]);
        } catch (JSONException e) {
            data = new JSONObject();
        }
        JSONObject result = data;
        if (reply != null) ui.post(() -> reply.done((int) r[0], result));
    }

    /**
     * Saves a file the user attached into the folder, its bytes streamed from where it is (never
     * held whole); the reply has the path it got.
     */
    void upload(String cwd, String name, android.net.Uri uri, Reply reply) {
        pool.execute(() -> {
            android.content.ContentResolver resolver = context.getContentResolver();
            java.io.File spool = null;
            try {
                long length = -1;
                try (android.content.res.AssetFileDescriptor fd = resolver.openAssetFileDescriptor(uri, "r")) {
                    if (fd != null) length = fd.getLength();
                } catch (IOException | RuntimeException ignored) {
                    // not known: copied aside first
                }
                if (length < 0) {
                    // The bridge needs the length first: a stream of unknown length is copied aside.
                    spool = java.io.File.createTempFile("upload", null, context.getCacheDir());
                    try (InputStream in = resolver.openInputStream(uri); OutputStream out = new java.io.FileOutputStream(spool)) {
                        if (in == null) throw new IOException("unreadable");
                        Userland.copy(in, out);
                    }
                    length = spool.length();
                }
                java.io.File from = spool;
                String path = "/upload?cwd=" + android.net.Uri.encode(cwd) + "&name=" + android.net.Uri.encode(name);
                answer(exchange("POST", path, "application/octet-stream", length, out -> {
                    try (InputStream in = from != null ? new java.io.FileInputStream(from) : resolver.openInputStream(uri)) {
                        if (in == null) throw new IOException("unreadable");
                        Userland.copy(in, out);
                    }
                }), reply);
            } catch (IOException | RuntimeException e) {
                answer(new Object[]{400, String.valueOf(new JSONObject(java.util.Collections.singletonMap("error", "读不了这个文件")))}, reply);
            } finally {
                if (spool != null) spool.delete();
            }
        });
    }

    /** A picture of a saved conversation by its reference (ccimg:…), read from the bridge; null when it cannot be. */
    byte[] image(String ref) {
        String[] p = ref.split(":");
        if (p.length != 4) return null;
        Object[] r = exchange("GET", "/image?id=" + android.net.Uri.encode(p[1]) + "&at=" + p[2] + "&n=" + p[3], null);
        if ((int) r[0] != 200) return null;
        try {
            return android.util.Base64.decode(new JSONObject((String) r[1]).optString("data"), android.util.Base64.DEFAULT);
        } catch (JSONException | IllegalArgumentException e) {
            return null;
        }
    }

    /** Status of an exchange when nothing listens on the bridge's socket. */
    static final int REFUSED = -3;

    interface Body { void write(OutputStream out) throws IOException; }

    private Object[] exchange(String method, String path, String body) {
        if (body == null) return exchange(method, path, null, 0, null);
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        return exchange(method, path, "application/json", bytes.length, out -> out.write(bytes));
    }

    /**
     * One HTTP exchange; status REFUSED when the bridge's socket could not be connected to (the
     * error's text as the body), -1 when the bridge cannot be reached
     * otherwise, -2 when it does not answer in time.
     */
    private Object[] exchange(String method, String path, String type, long length, Body body) {
        // Plugins and MCP servers are set up by Claude Code itself, and a file is written whole
        // before the answer: these may take minutes.
        int timeout = path.startsWith("/health") ? 3000
                : path.startsWith("/plugin") || path.startsWith("/mcp") || path.startsWith("/upload") ? 310_000 : 30000;
        long start = System.currentTimeMillis();
        try (Answer a = send(method, path, type, length, body, timeout)) {
            return new Object[]{a.status, readBody(a)};
        } catch (Refused e) {
            return new Object[]{REFUSED, String.valueOf(e.getMessage())};
        } catch (IOException e) {
            // A read that timed out is only an error on a LocalSocket: told by the time it took.
            return new Object[]{System.currentTimeMillis() - start >= timeout - 50 ? -2 : -1, ""};
        }
    }

    /** Nothing listens on the bridge's socket (or there is none). */
    private static final class Refused extends IOException {
        Refused(IOException cause) { super(cause.getMessage(), cause); }
    }

    /** An answer whose head is read: its status and length; the body is read from in. */
    private static final class Answer implements java.io.Closeable {
        final LocalSocket socket;
        final InputStream in;
        int status;
        long length = -1;

        Answer(LocalSocket socket) throws IOException {
            this.socket = socket;
            in = new BufferedInputStream(socket.getInputStream(), 16384);
        }

        @Override public void close() {
            try { socket.close(); } catch (IOException ignored) { /* closed */ }
        }
    }

    /** Sends one request on a new connection (closed after the answer) and reads the answer's head. */
    private Answer send(String method, String path, String type, long length, Body body, int timeout) throws IOException {
        LocalSocket s = new LocalSocket();
        try {
            try {
                s.connect(new LocalSocketAddress(socket, LocalSocketAddress.Namespace.FILESYSTEM));
            } catch (IOException e) {
                throw new Refused(e);
            }
            s.setSoTimeout(timeout);
            OutputStream out = new BufferedOutputStream(s.getOutputStream(), 16384);
            StringBuilder head = new StringBuilder(method).append(' ').append(target(path))
                    .append(" HTTP/1.1\r\nHost: bridge\r\nConnection: close\r\n");
            if (body != null) head.append("Content-Type: ").append(type).append("\r\nContent-Length: ").append(length).append("\r\n");
            out.write(head.append("\r\n").toString().getBytes(StandardCharsets.UTF_8));
            if (body != null) body.write(out);
            out.flush();
            Answer a = new Answer(s);
            String[] status = line(a.in).split(" "); // HTTP/1.1 200 OK
            try {
                a.status = Integer.parseInt(status.length > 1 ? status[1] : "");
                for (String h; !(h = line(a.in)).isEmpty(); ) {
                    int colon = h.indexOf(':');
                    if (colon > 0 && h.substring(0, colon).trim().equalsIgnoreCase("Content-Length")) {
                        a.length = Long.parseLong(h.substring(colon + 1).trim());
                    }
                }
            } catch (NumberFormatException e) {
                throw new IOException("not an HTTP answer", e);
            }
            return a;
        } catch (IOException e) {
            try { s.close(); } catch (IOException ignored) { /* closed */ }
            throw e;
        }
    }

    /** The request target as sent: the path with what is not plain ASCII percent-encoded. */
    private static String target(String path) {
        StringBuilder out = new StringBuilder();
        for (byte b : path.getBytes(StandardCharsets.UTF_8)) {
            int ch = b & 0xff;
            if (ch > 0x20 && ch < 0x7f) out.append((char) ch);
            else out.append('%').append(Character.forDigit(ch >> 4, 16)).append(Character.forDigit(ch & 15, 16));
        }
        return out.toString();
    }

    /** A line of the answer's head, without its line end. */
    private static String line(InputStream in) throws IOException {
        StringBuilder out = new StringBuilder();
        for (int ch; (ch = in.read()) != '\n'; ) {
            if (ch < 0) throw new EOFException();
            if (ch != '\r') out.append((char) ch);
        }
        return out.toString();
    }

    /** The answer's body: its Content-Length, or all until the bridge closes the connection. */
    private static String readBody(Answer a) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        long left = a.length;
        for (int n; left != 0 && (n = a.in.read(buf, 0, left < 0 ? buf.length : (int) Math.min(buf.length, left))) > 0; ) {
            out.write(buf, 0, n);
            if (left > 0) left -= n;
        }
        if (left > 0) throw new EOFException();
        return out.toString("UTF-8");
    }

    /**
     * Makes sure a bridge of this app's version answers, starting one if needed.
     * Result: "ok", "install" (the userland is not unpacked yet) or "timeout".
     */
    void connect(boolean restart, java.util.function.Consumer<String> done) {
        pool.execute(() -> {
            String result;
            String why = restart ? "restart asked in the app" : isUp();
            if (why == null) result = "ok";
            else if (!new Userland(context).ready()) result = "install";
            else if (System.currentTimeMillis() - startedAt < 30_000 && !restart) result = waitUntilUp();
            else {
                run(why);
                result = waitUntilUp();
            }
            if ("ok".equals(result) && reading.compareAndSet(false, true)) pool.execute(this::readEvents);
            String r = result;
            ui.post(() -> done.accept(r));
        });
    }

    private boolean isCurrent(Object[] health) {
        if ((int) health[0] != 200) return false;
        try {
            return new JSONObject((String) health[1]).optInt("version") == version();
        } catch (JSONException e) {
            return false;
        }
    }

    /**
     * Null when the bridge answers, else why it is to be started (for its log). Nothing listening
     * means no bridge: started at once; one that does not answer gets three tries, so a busy
     * bridge is not taken for a dead one.
     */
    private String isUp() {
        int status = 0;
        String error = "";
        for (int i = 0; i < 3; i++) {
            Object[] health = exchange("GET", "/health", null);
            if (isCurrent(health)) return null;
            status = (int) health[0];
            if (status == 200) return "another version was running";
            // No socket: no bridge. A socket that refused may be a bridge too busy to take it (or one
            // killed, its socket left): it gets the tries too, as starting one would replace it.
            if (status == REFUSED && !new java.io.File(socket).exists()) return "no bridge was running";
            if (status == REFUSED) error = (String) health[1];
            try { Thread.sleep(300); } catch (InterruptedException e) { return "interrupted"; }
        }
        return status == REFUSED ? "nothing took the connection on the socket, three times (" + error + ")"
                : status == -2 ? "the running bridge did not answer within 3 s, three times"
                : status == -1 ? "the running bridge could not be reached, three times" : "the running bridge answered " + status;
    }

    /** Waits at most 30 s for the bridge just started (proot and Python take a while on a phone). */
    private String waitUntilUp() {
        long end = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < end) {
            try { Thread.sleep(100); } catch (InterruptedException e) { return "timeout"; }
            if (isCurrent(exchange("GET", "/health", null))) return "ok";
        }
        return "timeout";
    }

    /** Has the service start the bridge; it replaces one already running. */
    private void run(String why) {
        lastSeq = -1;
        lastBridge = null;
        startedAt = System.currentTimeMillis();
        ui.post(() -> BridgeService.start(context, why));
    }

    /** Reads the event stream, reconnecting, and hands events over in batches. */
    private void readEvents() {
        boolean up = false;
        while (reading.get()) {
            Answer a = null;
            try {
                String bridge = lastBridge;
                String path = "/events?since=" + lastSeq + (lastSeq >= 0 && bridge != null ? "&bridge=" + android.net.Uri.encode(bridge) : "");
                a = send("GET", path, null, 0, null, 15000); // three missed pings: the bridge is gone or frozen
                if (a.status != 200) throw new IOException("status " + a.status);
                if (!up) {
                    up = true;
                    ui.post(() -> listener.link(true));
                }
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(a.in, StandardCharsets.UTF_8));
                for (String line; reading.get() && (line = reader.readLine()) != null; ) {
                    if (line.startsWith(": hello ")) lastBridge = line.substring(8).trim();
                    if (!line.startsWith("data: ")) continue;
                    try {
                        JSONObject event = new JSONObject(line.substring(6));
                        lastSeq = event.optLong("seq", 0);
                        queue(event);
                    } catch (JSONException ignored) {
                        // not an event
                    }
                }
            } catch (IOException e) {
                if (up) {
                    up = false;
                    ui.post(() -> listener.link(false));
                }
                try { Thread.sleep(1500); } catch (InterruptedException ignored) { return; }
            } finally {
                if (a != null) a.close();
            }
        }
    }

    private void queue(JSONObject event) {
        synchronized (pending) {
            pending.add(event);
            if (flushQueued) return;
            flushQueued = true;
        }
        ui.postDelayed(() -> {
            List<JSONObject> batch;
            synchronized (pending) {
                batch = new ArrayList<>(pending);
                pending.clear();
                flushQueued = false;
            }
            listener.events(batch);
        }, 33);
    }

    void close() {
        reading.set(false);
        pool.shutdownNow();
    }
}
