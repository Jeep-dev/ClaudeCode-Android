package com.jeep.claudecode;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.system.ErrnoException;
import android.system.Os;
import android.util.Base64;
import android.util.DisplayMetrics;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The phone's own WebView for the userland's `webview` command (assets/webview.py): one page,
 * kept between requests with its cookies, loaded and read off screen, let go after IDLE_MS
 * unused. A request is a line of JSON on a Unix socket in the userland ({@link #SOCKET}, in this
 * app's own files as the bridge's is), its answer another; one at a time.
 */
final class Browser {
    static final String SOCKET = Userland.HOME + "/.ccapp/webview.sock";
    private static final long IDLE_MS = 10 * 60_000;
    private static final int MAX_CSS_HEIGHT = 8000; // a whole page's shot stops there
    private static final long MAX_PIXELS = 16_000_000; // a shot larger is made at a smaller scale

    private final Context context;
    private final File path;
    private final Consumer<String> log;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Object turn = new Object();
    private LocalSocket bound; // keeps the server's descriptor from being collected
    private LocalServerSocket server;
    private volatile boolean stopped;

    // On the main thread only.
    private static boolean wholeDocument;
    private WebView view;
    private String mobileAgent, desktopAgent;
    private volatile boolean desktop = true; // the page's site is the desktop one
    private int widthCss, heightCss;
    // Written on the main thread, read by the request's.
    private volatile boolean loading;
    private volatile long settledAt; // when the last load finished
    private volatile int navigations; // loads started
    private volatile String loadError; // the main frame's, of the last load
    private volatile int httpStatus;
    private final Runnable idle = this::release;

    Browser(Context context, File path, Consumer<String> log) {
        this.context = context;
        this.path = path;
        this.log = log;
    }

    /** Listens, unless it already does. */
    synchronized void start() {
        if (server != null || stopped) return;
        try {
            File dir = path.getParentFile();
            if (dir != null) dir.mkdirs();
            path.delete();
            bound = new LocalSocket();
            bound.bind(new LocalSocketAddress(path.getPath(), LocalSocketAddress.Namespace.FILESYSTEM));
            Os.chmod(path.getPath(), 0600);
            server = new LocalServerSocket(bound.getFileDescriptor());
        } catch (IOException | ErrnoException e) {
            log.accept("webview: could not listen: " + e);
            return;
        }
        Thread t = new Thread(this::serve, "webview");
        t.setDaemon(true);
        t.start();
    }

    synchronized void stop() {
        if (stopped) return;
        stopped = true;
        if (server != null) {
            // Closing alone may leave accept() waiting: a connection wakes it.
            try (LocalSocket s = new LocalSocket()) {
                s.connect(new LocalSocketAddress(path.getPath(), LocalSocketAddress.Namespace.FILESYSTEM));
            } catch (IOException ignored) {
                // not listening any more
            }
            try {
                server.close();
            } catch (IOException ignored) {
                // closed
            }
        }
        path.delete();
        main.post(this::release);
    }

    private void serve() {
        while (!stopped) {
            LocalSocket s;
            try {
                s = server.accept();
            } catch (IOException e) {
                if (!stopped) log.accept("webview: stopped listening: " + e);
                return;
            }
            if (stopped) {
                try { s.close(); } catch (IOException ignored) { /* closed */ }
                return;
            }
            Thread t = new Thread(() -> answer(s), "webview-request");
            t.setDaemon(true);
            t.start();
        }
    }

    private void answer(LocalSocket s) {
        try (LocalSocket sock = s) {
            sock.setSoTimeout(10_000);
            String line = new BufferedReader(new InputStreamReader(sock.getInputStream(), StandardCharsets.UTF_8)).readLine();
            if (line == null) return;
            JSONObject out;
            synchronized (turn) {
                main.removeCallbacks(idle);
                try {
                    out = handle(new JSONObject(line));
                    out.put("ok", true);
                } catch (Exception e) {
                    out = new JSONObject().put("ok", false).put("error", message(e));
                } finally {
                    main.postDelayed(idle, IDLE_MS);
                }
            }
            OutputStream o = sock.getOutputStream();
            o.write((out + "\n").getBytes(StandardCharsets.UTF_8));
            o.flush();
        } catch (IOException | JSONException e) {
            // the command went away, or sent no JSON
        }
    }

    private static String message(Throwable e) {
        String m = e.getMessage();
        return m == null || m.isEmpty() ? e.getClass().getSimpleName() : m;
    }

    private JSONObject handle(JSONObject req) throws Exception {
        String cmd = req.optString("cmd");
        String url = req.optString("url", "");
        long deadline = SystemClock.uptimeMillis() + (long) (req.optDouble("timeout", 30) * 1000);
        long waitMs = (long) (req.optDouble("wait", 1) * 1000);
        boolean load = !url.isEmpty();
        boolean desktop = req.optBoolean("desktop", true);
        int width = req.optInt("width", 0), height = req.optInt("height", 0);
        JSONObject out = new JSONObject();
        boolean open = onMain(() -> {
            if (view == null) create();
            if (load) {
                this.desktop = desktop;
                view.getSettings().setUserAgentString(desktop ? desktopAgent : mobileAgent);
                DisplayMetrics dm = context.getResources().getDisplayMetrics();
                widthCss = width > 0 ? width : desktop ? 1280 : Math.round(dm.widthPixels / dm.density);
                heightCss = height > 0 ? height : desktop ? 800 : Math.round(dm.heightPixels / dm.density);
            } else {
                if (width > 0) widthCss = width;
                if (height > 0) heightCss = height;
            }
            layout(widthCss, heightCss);
            if (load) {
                loading = true;
                loadError = null;
                httpStatus = 0;
                view.loadUrl(url);
            }
            return load || view.getUrl() != null;
        });
        if (!open) throw new IOException("还没有打开的页面：先用 webview open <网址>");
        if (!settle(deadline, waitMs)) out.put("warning", "页面在时限内没有加载完，下面是当时的样子");
        if (load && loadError != null) throw new IOException("打不开 " + url + "：" + loadError);
        switch (cmd) {
            case "open":
                break;
            case "text":
                out.put("result", script("return document.body ? document.body.innerText : ''", deadline));
                break;
            case "html":
                out.put("result", script("return document.documentElement.outerHTML", deadline));
                break;
            case "js": {
                int before = navigations;
                out.put("result", script(req.optString("code"), deadline));
                // It went elsewhere (a link, a form): the new page is read when it is there.
                if (navigations != before && !settle(deadline, waitMs)) {
                    out.put("warning", "跳转后的页面在时限内没有加载完");
                }
                break;
            }
            case "shot":
                out.put("png", shot(req.optBoolean("full"), req.optDouble("scale", this.desktop ? 1 : 2), deadline));
                break;
            default:
                throw new IllegalArgumentException("不认识的命令 " + cmd);
        }
        onMain(() -> {
            out.put("url", view == null ? "" : view.getUrl());
            out.put("title", view == null ? "" : view.getTitle());
            return null;
        });
        if (httpStatus >= 400) out.put("status", httpStatus);
        return out;
    }

    /** Waits until the page has loaded and waitMs more passed; false when the deadline came first. */
    private boolean settle(long deadline, long waitMs) throws InterruptedException {
        while (true) {
            long now = SystemClock.uptimeMillis();
            if (!loading && now - settledAt >= waitMs) return true;
            if (now >= deadline) return !loading;
            Thread.sleep(100);
        }
    }

    /**
     * Runs code in the page as the body of an async function, and gives what it returned (JSON's
     * kinds; JSONObject.NULL for nothing). The result is kept in the page under a name of its own
     * and read until it is there; a page that went elsewhere meanwhile took it along: null.
     */
    private Object script(String code, long deadline) throws Exception {
        String key = "__ccapp" + Long.toHexString(System.nanoTime());
        int nav = navigations;
        // The last statement says the code parsed: a syntax error gives no value at all.
        String run = "(function(){var k='" + key + "';window[k]=null;(async function(){\n" + code + "\n})().then("
                + "function(v){window[k]={ok:v===undefined?null:v}},"
                + "function(e){window[k]={error:String(e&&e.stack||e)}});return 1})()";
        if (!"1".equals(eval(run, deadline))) throw new IOException("脚本有语法错误");
        String read = "(function(){var r=window['" + key + "'];if(r==null)return null;"
                + "try{return JSON.stringify(r)}catch(e){return JSON.stringify({error:'结果转不成 JSON：'+e})}})()";
        while (true) {
            Object v = new JSONTokener(eval(read, deadline)).nextValue();
            if (v instanceof String) {
                JSONObject r = new JSONObject((String) v);
                if (r.has("error")) throw new IOException("脚本出错：" + r.getString("error"));
                return r.opt("ok");
            }
            if (navigations != nav) return JSONObject.NULL;
            if (SystemClock.uptimeMillis() >= deadline) throw new TimeoutException("脚本在时限内没有结束");
            Thread.sleep(50);
        }
    }

    /** evaluateJavascript's answer: the value as JSON ("null" for none). */
    private String eval(String js, long deadline) throws Exception {
        BlockingQueue<String> answer = new ArrayBlockingQueue<>(1);
        onMain(() -> {
            if (view == null) throw new IOException("页面已经关闭");
            view.evaluateJavascript(js, v -> answer.offer(v == null ? "null" : v));
            return null;
        });
        String v = answer.poll(Math.max(1, deadline - SystemClock.uptimeMillis()), TimeUnit.MILLISECONDS);
        if (v == null) throw new TimeoutException("页面在时限内没有回应");
        return v;
    }

    /** The page as a PNG, base64: what shows (or all of it, up to MAX_CSS_HEIGHT), scale pixels to a CSS pixel. */
    private String shot(boolean full, double scale, long deadline) throws Exception {
        int height = heightCss;
        if (full) {
            Object h = script("return Math.max(document.documentElement.scrollHeight, document.body ? document.body.scrollHeight : 0)", deadline);
            if (h instanceof Number) height = Math.max(heightCss, Math.min(MAX_CSS_HEIGHT, ((Number) h).intValue()));
        }
        int h = height;
        double s = Math.max(0.5, Math.min(scale, context.getResources().getDisplayMetrics().density));
        s = Math.min(s, Math.sqrt((double) MAX_PIXELS / ((double) widthCss * h)));
        double sc = s;
        try {
            if (h != heightCss) {
                onMain(() -> { layout(widthCss, h); return null; });
                Thread.sleep(800); // drawn again at its new size
            }
            return onMain(() -> {
                if (view == null) throw new IOException("页面已经关闭");
                Bitmap b = Bitmap.createBitmap((int) Math.round(widthCss * sc), (int) Math.round(h * sc), Bitmap.Config.ARGB_8888);
                try {
                    Canvas c = new Canvas(b);
                    c.drawColor(Color.WHITE);
                    float k = (float) (sc / context.getResources().getDisplayMetrics().density);
                    c.scale(k, k);
                    view.draw(c);
                    ByteArrayOutputStream png = new ByteArrayOutputStream();
                    b.compress(Bitmap.CompressFormat.PNG, 100, png);
                    return Base64.encodeToString(png.toByteArray(), Base64.NO_WRAP);
                } finally {
                    b.recycle();
                }
            });
        } finally {
            if (h != heightCss) onMain(() -> { if (view != null) layout(widthCss, heightCss); return null; });
        }
    }

    // ---- on the main thread ----

    @SuppressLint("SetJavaScriptEnabled")
    private void create() {
        if (!wholeDocument) {
            WebView.enableSlowWholeDocumentDraw(); // a whole page's shot draws what is off screen too
            wholeDocument = true;
        }
        view = new WebView(context);
        WebSettings s = view.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setSupportMultipleWindows(false);
        s.setMediaPlaybackRequiresUserGesture(true);
        // Without what marks it as a WebView (some sites turn those away), as Chrome.
        mobileAgent = s.getUserAgentString().replace("; wv", "").replace("Version/4.0 ", "");
        Matcher chrome = Pattern.compile("Chrome/[\\d.]+").matcher(mobileAgent);
        desktopAgent = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) "
                + (chrome.find() ? chrome.group() : "Chrome/130.0.0.0") + " Safari/537.36";
        view.setBackgroundColor(Color.WHITE);
        view.setWebViewClient(client);
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, true);
        loading = false;
        loadError = null;
    }

    private void layout(int widthCss, int heightCss) {
        float d = context.getResources().getDisplayMetrics().density;
        int w = Math.round(widthCss * d), h = Math.round(heightCss * d);
        view.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, w, h);
    }

    private void release() {
        if (view == null) return;
        view.stopLoading();
        view.destroy();
        view = null;
        loading = false;
        CookieManager.getInstance().flush();
    }

    private final WebViewClient client = new WebViewClient() {
        @Override public void onPageStarted(WebView v, String url, Bitmap favicon) {
            if (v != view) return;
            loading = true;
            navigations++;
        }

        @Override public void onPageFinished(WebView v, String url) {
            if (v != view) return;
            loading = false;
            settledAt = SystemClock.uptimeMillis();
        }

        @Override public void onReceivedError(WebView v, WebResourceRequest request, WebResourceError error) {
            if (v == view && request.isForMainFrame()) loadError = String.valueOf(error.getDescription());
        }

        @Override public void onReceivedHttpError(WebView v, WebResourceRequest request, WebResourceResponse response) {
            if (v == view && request.isForMainFrame()) httpStatus = response.getStatusCode();
        }

        /** Only web pages: an address for another app (intent:, market:, …) opens nothing. */
        @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest request) {
            String scheme = request.getUrl().getScheme();
            return scheme == null || !scheme.matches("https?|about|data|blob");
        }

        @Override public boolean onRenderProcessGone(WebView v, RenderProcessGoneDetail detail) {
            if (v == view) {
                view = null;
                loading = false;
                loadError = "WebView 的渲染进程崩溃了";
            }
            v.destroy();
            return true;
        }
    };

    private <T> T onMain(Callable<T> work) throws Exception {
        FutureTask<T> task = new FutureTask<>(work);
        main.post(task);
        try {
            return task.get(30, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            throw cause instanceof Exception ? (Exception) cause : e;
        }
    }
}
