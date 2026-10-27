package com.jeep.claudecode;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.LruCache;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Images in a conversation: a data: URL (what Claude Code passes), a picture of a saved
 * conversation (ccimg:, read from the bridge when shown), a web address, or a file in the
 * userland. Read off the main thread, scaled down to what is shown, and kept for a while.
 */
final class Images {
    private static final int MAX_BYTES = 20 << 20;
    private static final LruCache<String, Bitmap> cache = new LruCache<String, Bitmap>(24 << 20) {
        @Override protected int sizeOf(String key, Bitmap b) { return b.getByteCount(); }
    };
    private static final ExecutorService pool = Executors.newFixedThreadPool(2);
    private static final Handler main = new Handler(Looper.getMainLooper());
    /** Reads a saved conversation's picture by its reference (ccimg:…), from the bridge. */
    static volatile java.util.function.Function<String, byte[]> saved;

    private Images() {}

    /** The image's bytes, or null when it cannot be read. */
    static byte[] bytes(String src, File file) {
        try {
            if (src != null && src.startsWith("data:")) {
                return Base64.decode(src.substring(src.indexOf(',') + 1), Base64.DEFAULT);
            }
            if (src != null && src.startsWith("ccimg:")) {
                java.util.function.Function<String, byte[]> read = saved;
                return read == null ? null : read.apply(src);
            }
            if (src != null && (src.startsWith("http://") || src.startsWith("https://"))) {
                HttpURLConnection http = (HttpURLConnection) new URL(src).openConnection();
                http.setConnectTimeout(10_000);
                http.setReadTimeout(20_000);
                try (InputStream in = http.getInputStream()) {
                    return readAll(in);
                } finally {
                    http.disconnect();
                }
            }
            if (file != null && file.isFile() && file.length() <= MAX_BYTES) {
                try (InputStream in = new FileInputStream(file)) {
                    return readAll(in);
                }
            }
        } catch (Exception e) {
            // unreadable: nothing shown
        }
        return null;
    }

    private static byte[] readAll(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        for (int n; (n = in.read(buf)) > 0; ) {
            out.write(buf, 0, n);
            if (out.size() > MAX_BYTES) throw new java.io.IOException("too large");
        }
        return out.toByteArray();
    }

    /** Decodes, scaled down so its longer side is about maxPx at most. */
    static Bitmap decode(byte[] bytes, int maxPx) {
        if (bytes == null) return null;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o);
        if (o.outWidth <= 0 || o.outHeight <= 0) return null;
        int sample = 1;
        while (Math.max(o.outWidth, o.outHeight) / sample > maxPx) sample *= 2;
        o.inJustDecodeBounds = false;
        // Too large for the memory left: at half the size, then a quarter.
        for (int i = 0; i < 3; i++, sample *= 2) {
            o.inSampleSize = sample;
            try {
                return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o);
            } catch (OutOfMemoryError e) {
                // smaller next
            }
        }
        return null;
    }

    /** Forgets the images read: a transcript changed (a message deleted) names its pictures anew. */
    static void forget() { cache.evictAll(); }

    /** Shows the image in the view when read; a view reused for another image meanwhile is left alone. */
    static void load(ImageView into, String src, File file, int maxPx, Runnable failed) {
        String key = (src != null ? src.hashCode() + ":" + src.length() : String.valueOf(file)) + "@" + maxPx;
        into.setTag(R.id.image, key);
        Bitmap hit = cache.get(key);
        if (hit != null) { into.setImageBitmap(hit); return; }
        pool.execute(() -> {
            Bitmap b = decode(bytes(src, file), maxPx);
            main.post(() -> {
                // One that would empty the cache (a picture opened full screen) is not kept in it.
                if (b != null && b.getByteCount() <= cache.maxSize() / 4) cache.put(key, b);
                if (!key.equals(into.getTag(R.id.image))) return;
                if (b != null) into.setImageBitmap(b);
                else if (failed != null) failed.run();
            });
        });
    }

    /** The image on a black screen, to zoom with two fingers or a double tap; a tap or back closes it. */
    static void show(Context c, String src, File file) {
        // The status bar stays, over the black. Hidden, it changed the insets of the conversation
        // behind, whose list then moved; and the window, kept clear of the camera's cutout, left a
        // strip where the conversation showed. Drawn behind the bars and the cutout instead.
        Dialog d = new Dialog(c, android.R.style.Theme_Black_NoTitleBar);
        Zoom view = new Zoom(c, d::dismiss);
        view.setBackgroundColor(Color.BLACK);
        // The picture fitted between the bars; zoomed, it goes under them.
        view.setOnApplyWindowInsetsListener((v, insets) -> {
            view.bars.set(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            view.fit();
            return insets;
        });
        d.setContentView(view, new ViewGroup.LayoutParams(-1, -1));
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.BLACK));
            w.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);
            w.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
            w.setStatusBarColor(Color.TRANSPARENT);
            w.setNavigationBarColor(Color.TRANSPARENT);
            w.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
            if (Build.VERSION.SDK_INT >= 28) {
                WindowManager.LayoutParams a = w.getAttributes();
                a.layoutInDisplayCutoutMode = Build.VERSION.SDK_INT >= 30
                        ? WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                        : WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
                w.setAttributes(a);
            }
        }
        d.show();
        int px = Math.max(c.getResources().getDisplayMetrics().widthPixels, c.getResources().getDisplayMetrics().heightPixels);
        load(view, src, file, px * 2, () -> {
            if (!d.isShowing()) return; // closed before it was read
            android.widget.Toast.makeText(c, "打不开这张图片", android.widget.Toast.LENGTH_SHORT).show();
            d.dismiss();
        });
    }

    /** An image fitted to the screen, zoomed and moved by touch. */
    static final class Zoom extends ImageView {
        private final Matrix m = new Matrix();
        private float scale = 1f;
        private final ScaleGestureDetector pinch;
        private final GestureDetector gestures;

        Zoom(Context c, Runnable close) {
            super(c);
            setScaleType(ScaleType.MATRIX);
            pinch = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                @Override public boolean onScale(ScaleGestureDetector g) {
                    zoom(g.getScaleFactor(), g.getFocusX(), g.getFocusY());
                    return true;
                }
            });
            gestures = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
                @Override public boolean onSingleTapConfirmed(MotionEvent e) { close.run(); return true; }

                @Override public boolean onDoubleTap(MotionEvent e) {
                    if (scale > 1.05f) fit(); else zoom(2.5f, e.getX(), e.getY());
                    return true;
                }

                @Override public boolean onScroll(MotionEvent a, MotionEvent b, float dx, float dy) {
                    if (scale <= 1.01f) return false;
                    m.postTranslate(-dx, -dy);
                    keepInside();
                    return true;
                }
            });
        }

        @Override public void setImageBitmap(Bitmap b) {
            super.setImageBitmap(b);
            post(this::fit);
        }

        @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
            super.onSizeChanged(w, h, ow, oh);
            fit();
        }

        /** How far the bars come in from each edge (not padding: an ImageView draws inside its padding). */
        final RectF bars = new RectF();

        /** Where the picture is fitted and centred: the screen less the bars. */
        private RectF area() {
            return new RectF(bars.left, bars.top, getWidth() - bars.right, getHeight() - bars.bottom);
        }

        void fit() {
            if (getDrawable() == null || getWidth() == 0) return;
            m.setRectToRect(new RectF(0, 0, getDrawable().getIntrinsicWidth(), getDrawable().getIntrinsicHeight()),
                    area(), Matrix.ScaleToFit.CENTER);
            scale = 1f;
            setImageMatrix(m);
        }

        private void zoom(float by, float x, float y) {
            float to = Math.max(1f, Math.min(6f, scale * by));
            by = to / scale;
            scale = to;
            m.postScale(by, by, x, y);
            keepInside();
        }

        /** The image covers the screen where it is larger, and is centred where it is smaller. */
        private void keepInside() {
            if (getDrawable() == null) return;
            RectF r = new RectF(0, 0, getDrawable().getIntrinsicWidth(), getDrawable().getIntrinsicHeight());
            m.mapRect(r);
            RectF a = area();
            float dx = r.width() <= getWidth() ? a.centerX() - r.centerX() : r.left > 0 ? -r.left : r.right < getWidth() ? getWidth() - r.right : 0;
            float dy = r.height() <= getHeight() ? a.centerY() - r.centerY() : r.top > 0 ? -r.top : r.bottom < getHeight() ? getHeight() - r.bottom : 0;
            m.postTranslate(dx, dy);
            setImageMatrix(m);
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            pinch.onTouchEvent(e);
            gestures.onTouchEvent(e);
            return true;
        }
    }
}
