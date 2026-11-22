package com.jeep.claudecode

import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.LruCache
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import android.widget.Toast
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

/**
 * Images in a conversation: a data: URL (what Claude Code passes), a picture of a saved
 * conversation (ccimg:, read from the bridge when shown), a web address, or a file in the
 * userland. Read off the main thread, scaled down to what is shown, and kept for a while.
 */
object Images {
    private const val MAX_BYTES = 20 shl 20
    private val cache = object : LruCache<String, Bitmap>(24 shl 20) {
        override fun sizeOf(key: String, b: Bitmap): Int = b.byteCount
    }
    private val pool = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())
    /** Reads a saved conversation's picture by its reference (ccimg:…), from the bridge. */
    @JvmField @Volatile var saved: ((String) -> ByteArray?)? = null

    /** The image's bytes, or null when it cannot be read. */
    @JvmStatic
    fun bytes(src: String?, file: File?): ByteArray? {
        try {
            if (src != null && src.startsWith("data:")) {
                return Base64.decode(src.substring(src.indexOf(',') + 1), Base64.DEFAULT)
            }
            if (src != null && src.startsWith("ccimg:")) return saved?.invoke(src)
            if (src != null && (src.startsWith("http://") || src.startsWith("https://"))) {
                val http = URL(src).openConnection() as HttpURLConnection
                http.connectTimeout = 10_000
                http.readTimeout = 20_000
                try {
                    return http.inputStream.use(::readAll)
                } finally {
                    http.disconnect()
                }
            }
            if (file != null && file.isFile && file.length() <= MAX_BYTES) return FileInputStream(file).use(::readAll)
        } catch (e: Exception) {
            // unreadable: nothing shown
        }
        return null
    }

    @Throws(IOException::class)
    private fun readAll(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(16384)
        while (true) {
            val n = input.read(buf)
            if (n <= 0) break
            out.write(buf, 0, n)
            if (out.size() > MAX_BYTES) throw IOException("too large")
        }
        return out.toByteArray()
    }

    /** Decodes, scaled down so its longer side is about maxPx at most. */
    @JvmStatic
    fun decode(bytes: ByteArray?, maxPx: Int): Bitmap? {
        if (bytes == null) return null
        val o = BitmapFactory.Options()
        o.inJustDecodeBounds = true
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        if (o.outWidth <= 0 || o.outHeight <= 0) return null
        var sample = 1
        while (max(o.outWidth, o.outHeight) / sample > maxPx) sample *= 2
        o.inJustDecodeBounds = false
        // Too large for the memory left: at half the size, then a quarter.
        repeat(3) {
            o.inSampleSize = sample
            try {
                return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
            } catch (e: OutOfMemoryError) {
                // smaller next
            }
            sample *= 2
        }
        return null
    }

    /** Forgets the images read: a transcript changed (a message deleted) names its pictures anew. */
    @JvmStatic
    fun forget() = cache.evictAll()

    /** Shows the image in the view when read; a view reused for another image meanwhile is left alone. */
    @JvmStatic
    fun load(into: ImageView, src: String?, file: File?, maxPx: Int, failed: Runnable?) {
        val key = (if (src != null) "${src.hashCode()}:${src.length}" else file.toString()) + "@" + maxPx
        into.setTag(R.id.image, key)
        val hit = cache.get(key)
        if (hit != null) {
            into.setImageBitmap(hit)
            return
        }
        pool.execute {
            val b = decode(bytes(src, file), maxPx)
            main.post {
                // One that would empty the cache (a picture opened full screen) is not kept in it.
                if (b != null && b.byteCount <= cache.maxSize() / 4) cache.put(key, b)
                if (key != into.getTag(R.id.image)) return@post
                if (b != null) into.setImageBitmap(b) else failed?.run()
            }
        }
    }

    /** The image on a black screen, to zoom with two fingers or a double tap; a tap or back closes it. */
    @JvmStatic
    fun show(c: Context, src: String?, file: File?) {
        // The status bar stays, over the black. Hidden, it changed the insets of the conversation
        // behind, whose list then moved; and the window, kept clear of the camera's cutout, left a
        // strip where the conversation showed. Drawn behind the bars and the cutout instead.
        val d = Dialog(c, android.R.style.Theme_Black_NoTitleBar)
        val view = Zoom(c) { d.dismiss() }
        view.setBackgroundColor(Color.BLACK)
        // The picture fitted between the bars; zoomed, it goes under them.
        view.setOnApplyWindowInsetsListener { _, insets ->
            view.bars.set(insets.systemWindowInsetLeft.toFloat(), insets.systemWindowInsetTop.toFloat(),
                insets.systemWindowInsetRight.toFloat(), insets.systemWindowInsetBottom.toFloat())
            view.fit()
            insets
        }
        d.setContentView(view, ViewGroup.LayoutParams(-1, -1))
        d.window?.let { w ->
            w.setBackgroundDrawable(ColorDrawable(Color.BLACK))
            w.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
            w.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            w.statusBarColor = Color.TRANSPARENT
            w.navigationBarColor = Color.TRANSPARENT
            w.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            if (Build.VERSION.SDK_INT >= 28) {
                val a = w.attributes
                a.layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= 30)
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                else WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                w.attributes = a
            }
        }
        d.show()
        val dm = c.resources.displayMetrics
        val px = max(dm.widthPixels, dm.heightPixels)
        load(view, src, file, px * 2) {
            if (!d.isShowing) return@load // closed before it was read
            Toast.makeText(c, "打不开这张图片", Toast.LENGTH_SHORT).show()
            d.dismiss()
        }
    }

    /** An image fitted to the screen, zoomed and moved by touch. */
    class Zoom(c: Context, close: Runnable) : ImageView(c) {
        private val m = Matrix()
        private var scale = 1f
        /** How far the bars come in from each edge (not padding: an ImageView draws inside its padding). */
        @JvmField val bars = RectF()
        private val pinch = ScaleGestureDetector(c, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(g: ScaleGestureDetector): Boolean {
                zoom(g.scaleFactor, g.focusX, g.focusY)
                return true
            }
        })
        private val gestures = GestureDetector(c, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                close.run()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (scale > 1.05f) fit() else zoom(2.5f, e.x, e.y)
                return true
            }

            override fun onScroll(a: MotionEvent?, b: MotionEvent, dx: Float, dy: Float): Boolean {
                if (scale <= 1.01f) return false
                m.postTranslate(-dx, -dy)
                keepInside()
                return true
            }
        })

        init {
            scaleType = ScaleType.MATRIX
        }

        override fun setImageBitmap(b: Bitmap?) {
            super.setImageBitmap(b)
            post { fit() }
        }

        override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
            super.onSizeChanged(w, h, ow, oh)
            fit()
        }

        /** Where the picture is fitted and centred: the screen less the bars. */
        private fun area() = RectF(bars.left, bars.top, width - bars.right, height - bars.bottom)

        fun fit() {
            val d = drawable
            if (d == null || width == 0) return
            m.setRectToRect(RectF(0f, 0f, d.intrinsicWidth.toFloat(), d.intrinsicHeight.toFloat()), area(), Matrix.ScaleToFit.CENTER)
            scale = 1f
            imageMatrix = m
        }

        private fun zoom(factor: Float, x: Float, y: Float) {
            val to = max(1f, min(6f, scale * factor))
            val by = to / scale
            scale = to
            m.postScale(by, by, x, y)
            keepInside()
        }

        /** The image covers the screen where it is larger, and is centred where it is smaller. */
        private fun keepInside() {
            val d = drawable ?: return
            val r = RectF(0f, 0f, d.intrinsicWidth.toFloat(), d.intrinsicHeight.toFloat())
            m.mapRect(r)
            val a = area()
            val dx = if (r.width() <= width) a.centerX() - r.centerX()
                else if (r.left > 0) -r.left else if (r.right < width) width - r.right else 0f
            val dy = if (r.height() <= height) a.centerY() - r.centerY()
                else if (r.top > 0) -r.top else if (r.bottom < height) height - r.bottom else 0f
            m.postTranslate(dx, dy)
            imageMatrix = m
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            pinch.onTouchEvent(e)
            gestures.onTouchEvent(e)
            return true
        }
    }
}
