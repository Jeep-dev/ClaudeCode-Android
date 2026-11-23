package com.jeep.claudecode

import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import kotlin.math.max

/** A picture shown whole, on black over everything, read off the main thread; a tap closes it. */
object Viewer {
    private val main = Handler(Looper.getMainLooper())

    fun image(app: MainActivity, title: String, load: () -> Bitmap?) {
        val d = Dialog(app, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val root = FrameLayout(app)
        root.setBackgroundColor(Color.BLACK)
        val img = ImageView(app)
        img.scaleType = ImageView.ScaleType.FIT_CENTER
        root.addView(img, FrameLayout.LayoutParams(-1, -1))
        val wait = ProgressBar(app)
        root.addView(wait, FrameLayout.LayoutParams(Ui.dp(app, 40), Ui.dp(app, 40), Gravity.CENTER))
        val bar = Ui.row(app)
        bar.setPadding(Ui.dp(app, 8), Ui.dp(app, 8), Ui.dp(app, 16), Ui.dp(app, 8))
        bar.setBackgroundColor(0x66000000)
        val close = Ui.iconButton(app, R.drawable.ic_x, 44, 22, R.color.text)
        close.imageTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
        close.setOnClickListener { d.dismiss() }
        bar.addView(close, LinearLayout.LayoutParams(Ui.dp(app, 44), Ui.dp(app, 44)))
        val t = Ui.text(app, title, 16, R.color.text)
        t.setTextColor(Color.WHITE)
        t.isSingleLine = true
        t.ellipsize = TextUtils.TruncateAt.MIDDLE
        t.setPadding(Ui.dp(app, 8), 0, 0, 0)
        bar.addView(t, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(bar, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        root.setOnClickListener { d.dismiss() }
        d.setContentView(root)
        d.window?.let { w ->
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            w.setBackgroundDrawable(ColorDrawable(Color.BLACK))
        }
        root.alpha = 0f
        d.show()
        root.animate().alpha(1f).setDuration(180).start()
        Thread {
            val b = try {
                load()
            } catch (e: Exception) {
                null
            } catch (e: OutOfMemoryError) {
                null
            }
            main.post {
                wait.visibility = View.GONE
                if (b == null) {
                    app.toast("这张图片读不了")
                    d.dismiss()
                } else {
                    img.setImageBitmap(b)
                }
            }
        }.start()
    }

    /** A picture at [uri], decoded about as large as the screen. */
    fun decode(c: Context, uri: Uri): Bitmap? {
        val o = BitmapFactory.Options()
        o.inJustDecodeBounds = true
        c.contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, o) }
        if (o.outWidth <= 0) return null
        o.inSampleSize = sample(c, o.outWidth, o.outHeight)
        o.inJustDecodeBounds = false
        return c.contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, o) }
    }

    /** How much to sample a picture down by for it to be about as large as the screen. */
    fun sample(c: Context, w: Int, h: Int): Int {
        val dm = c.resources.displayMetrics
        val screen = max(dm.widthPixels, dm.heightPixels)
        var s = 1
        while (max(w, h) / (s * 2) >= screen) s *= 2
        return s
    }
}
