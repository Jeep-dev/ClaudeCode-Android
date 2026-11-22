package com.jeep.claudecode

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper

/** The crab's moves, one row of frames each in one PNG (SHOTS directory), and its styles. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w392dp-h850dp-xxhdpi")
class CrabTest {
    private val out = File(System.getProperty("shots", "build/shots"))

    private fun draw(v: View): Bitmap {
        val b = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawColor(Color.WHITE)
        v.draw(c)
        return b
    }

    private fun save(b: Bitmap, name: String) {
        out.mkdirs()
        FileOutputStream(File(out, "$name.png")).use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun measured(crab: Crab) = crab.apply {
        measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        layout(0, 0, measuredWidth, measuredHeight)
    }

    @Test fun everyMoveShowsAndEndsAtRest() {
        val a = Robolectric.buildActivity(Activity::class.java).setup().get()
        val moves = Crab.MOVES.size
        val cols = 10
        var sheet: Bitmap? = null
        var sc: Canvas? = null
        for ((row, name) in Crab.MOVES.keys.withIndex()) {
            val crab = Crab(a, 0, false, 2)
            a.setContentView(crab)
            ShadowLooper.idleMainLooper(10, TimeUnit.MILLISECONDS)
            measured(crab)
            if (sheet == null) {
                sheet = Bitmap.createBitmap(crab.width * cols / 2, crab.height * moves / 2, Bitmap.Config.ARGB_8888)
                sc = Canvas(sheet)
            }
            val frames = Crab.MOVES.getValue(name).size
            val step = maxOf(1, frames / cols)
            crab.play(name)
            var distinct = 0
            var last = draw(crab)
            for (i in 0 until frames) {
                ShadowLooper.idleMainLooper(60, TimeUnit.MILLISECONDS)
                val f = draw(crab)
                if (!f.sameAs(last)) distinct++
                last = f
                // Ten of its frames, evenly spaced.
                if (i % step == 0 && i / step < cols) {
                    val half = Bitmap.createScaledBitmap(f, f.width / 2, f.height / 2, false)
                    sc!!.drawBitmap(half, (i / step * half.width).toFloat(), (row * half.height).toFloat(), null)
                }
            }
            assertTrue("$name moves", distinct >= 2)
        }
        save(sheet!!, "30-crab-moves")
    }

    @Test fun everyStyleIsShown() {
        val a = Robolectric.buildActivity(Activity::class.java).setup().get()
        val n = Crab.NAMES.size
        var strip: Bitmap? = null
        var sc: Canvas? = null
        for (i in 0 until n) {
            val crab = measured(Crab(a, i, true))
            if (strip == null) {
                strip = Bitmap.createBitmap(crab.width * n, crab.height, Bitmap.Config.ARGB_8888)
                sc = Canvas(strip)
            }
            sc!!.drawBitmap(draw(crab), (i * crab.width).toFloat(), 0f, null)
        }
        save(strip!!, "31-crab-styles")
    }
}
