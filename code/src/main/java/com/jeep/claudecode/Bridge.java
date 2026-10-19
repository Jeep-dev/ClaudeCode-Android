package com.jeep.claudecode;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * The Claude Code bridge (assets/bridge.py), run by {@link BridgeService} inside this app's
 * userland with a per-install token and reached on 127.0.0.1. Requests are signed with the token
 * and answers must prove the bridge knows it, so another app listening on the port learns
 * nothing and is not believed. Results and events arrive on the main thread.
 */
final class Bridge {
    /** Not Termux's 8765: a bridge left running there must not answer for this one. */
    static final int PORT = 8766;
    private static final String BASE = "http://127.0.0.1:" + PORT;

    interface Reply { void done(int status, JSONObject data); }
    interface Events { void events(List<JSONObject> batch); void link(boolean up); }

    private final Context context;
    private final String token;
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
        token = token(context);
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

    /** This install's token, made on first use. */
    static synchronized String token(Context context) {
        SharedPreferences prefs = context.getSharedPreferences("bridge", Context.MODE_PRIVATE);
        String saved = prefs.getString("token", null);
        if (saved == null) {
            byte[] bytes = new byte[24];
            new SecureRandom().nextBytes(bytes);
            StringBuilder hex = new StringBuilder();
            for (byte b : bytes) hex.append(String.format("%02x", b));
            saved = hex.toString();
            prefs.edit().putString("token", saved).commit();
        }
        return saved;
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

    /** Status of an exchange when nothing listens on the bridge's port. */
    static final int REFUSED = -3;

    interface Body { void write(OutputStream out) throws IOException; }

    private Object[] exchange(String method, String path, String body) {
        if (body == null) return exchange(method, path, null, 0, null);
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        return exchange(method, path, "application/json", bytes.length, out -> out.write(bytes));
    }

    /**
     * One HTTP exchange; status REFUSED when nothing listens, -1 when the bridge cannot be reached
     * otherwise, -2 when it does not answer in time, 401 when what answers is not this app's bridge.
     */
    private Object[] exchange(String method, String path, String type, long length, Body body) {
        HttpURLConnection c = null;
        try {
            // Never through a proxy (a VPN app's proxy would hold requests to this phone).
            c = (HttpURLConnection) new URL(BASE + path).openConnection(Proxy.NO_PROXY);
            c.setConnectTimeout(3000);
            // Plugins and MCP servers are set up by Claude Code itself, and a file is written whole
            // before the answer: these may take minutes.
            c.setReadTimeout(path.startsWith("/health") ? 3000
                    : path.startsWith("/plugin") || path.startsWith("/mcp") || path.startsWith("/upload") ? 310_000 : 30000);
            c.setRequestMethod(method);
            String nonce = sign(c, method, path);
            // A fresh connection each time: a reused one the bridge has closed loses the answer.
            c.setRequestProperty("Connection", "close");
            if (body != null) {
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", type);
                c.setFixedLengthStreamingMode(length);
                try (OutputStream out = c.getOutputStream()) { body.write(out); }
            }
            int status = c.getResponseCode();
            if (status != 401 && !proven(c, nonce)) return new Object[]{401, ""};
            InputStream in = status >= 400 ? c.getErrorStream() : c.getInputStream();
            return new Object[]{status, in == null ? "" : read(in)};
        } catch (java.net.SocketTimeoutException e) {
            return new Object[]{-2, ""};
        } catch (java.net.ConnectException e) {
            return new Object[]{REFUSED, ""};
        } catch (IOException e) {
            return new Object[]{-1, ""};
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /**
     * Signs the request (X-Auth: time.nonce.HMAC of time.nonce.method.path, the path as decoded, so
     * what the connection re-encodes on the way does not matter); returns the nonce its answer signs.
     */
    private String sign(HttpURLConnection c, String method, String path) {
        String ms = String.valueOf(System.currentTimeMillis());
        byte[] bytes = new byte[12];
        random.nextBytes(bytes);
        String nonce = hex(bytes);
        c.setRequestProperty("X-Auth", ms + "." + nonce + "." + hmac(ms + "." + nonce + "." + method + "." + android.net.Uri.decode(path)));
        return nonce;
    }

    /** The answer is from this app's bridge: it signed the request's nonce with the token. */
    private boolean proven(HttpURLConnection c, String nonce) {
        String proof = c.getHeaderField("X-Proof");
        return proof != null && java.security.MessageDigest.isEqual(
                proof.getBytes(StandardCharsets.US_ASCII), hmac("reply." + nonce).getBytes(StandardCharsets.US_ASCII));
    }

    private final SecureRandom random = new SecureRandom();

    private String hmac(String text) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(token.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return hex(mac.doFinal(text.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e); // HmacSHA256 is always there
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) out.append(Character.forDigit((b >> 4) & 15, 16)).append(Character.forDigit(b & 15, 16));
        return out.toString();
    }

    private static String read(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
        in.close();
        return out.toString("UTF-8");
    }

    /**
     * Makes sure a bridge with this app's token and version answers, starting one if needed.
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
        for (int i = 0; i < 3; i++) {
            Object[] health = exchange("GET", "/health", null);
            if (isCurrent(health)) return null;
            status = (int) health[0];
            if (status == 200) return "another version was running";
            if (status == 401) return "something that is not this app's bridge answered (another token, or another program)";
            if (status == REFUSED) return "no bridge was running";
            try { Thread.sleep(500); } catch (InterruptedException e) { return "interrupted"; }
        }
        return status == -2 ? "the running bridge did not answer within 3 s, three times"
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
            HttpURLConnection c = null;
            try {
                String bridge = lastBridge;
                String path = "/events?since=" + lastSeq + (lastSeq >= 0 && bridge != null ? "&bridge=" + android.net.Uri.encode(bridge) : "");
                c = (HttpURLConnection) new URL(BASE + path).openConnection(Proxy.NO_PROXY);
                c.setConnectTimeout(3000);
                c.setReadTimeout(15000); // three missed pings: the bridge is gone or frozen
                String nonce = sign(c, "GET", path);
                if (c.getResponseCode() != 200) throw new IOException("status " + c.getResponseCode());
                if (!proven(c, nonce)) throw new IOException("not this app's bridge");
                if (!up) {
                    up = true;
                    ui.post(() -> listener.link(true));
                }
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8));
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
                if (c != null) c.disconnect();
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
