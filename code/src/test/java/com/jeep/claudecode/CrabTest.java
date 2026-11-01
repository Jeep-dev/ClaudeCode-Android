package com.jeep.claudecode;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.view.View;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowLooper;

import java.io.File;
import java.io.FileOutputStream;
import java.util.concurrent.TimeUnit;

/** The crab dancing: eight beats side by side in one PNG (SHOTS directory),. */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 34, qualifiers = "w392dp-h850dp-xxhdpi")
public class CrabTest {
    private static final File OUT = new File(System.getProperty("shots", "build/shots"));

    private static Bitmap draw(View v) {
        Bitmap b = Bitmap.createBitmap(v.getWidth(), v.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        c.drawColor(Color.WHITE);
        v.draw(c);
        return b;
    }

    private static boolean same(Bitmap a, Bitmap b) {
        return a.sameAs(b);
    }

    @Test public void danceBeatsDiffer() throws Exception {
        Activity a = Robolectric.buildActivity(Activity.class).setup().get();
        Crab crab = new Crab(a);
        a.setContentView(crab);
        ShadowLooper.idleMainLooper(10, TimeUnit.MILLISECONDS);
        crab.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        crab.layout(0, 0, crab.getMeasuredWidth(), crab.getMeasuredHeight());

        Bitmap rest = draw(crab);
        crab.busy(true);
        int w = crab.getWidth();
        Bitmap strip = Bitmap.createBitmap(w * 8, crab.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas sc = new Canvas(strip);
        Bitmap previous = null;
        int moved = 0;
        for (int i = 0; i < 8; i++) {
            ShadowLooper.idleMainLooper(170, TimeUnit.MILLISECONDS);
            Bitmap f = draw(crab);
            sc.drawBitmap(f, i * w, 0, null);
            if (previous != null && !same(previous, f)) moved++;
            previous = f;
        }
        OUT.mkdirs();
        try (FileOutputStream o = new FileOutputStream(new File(OUT, "30-crab-dance.png"))) {
            strip.compress(Bitmap.CompressFormat.PNG, 100, o);
        }
        assertTrue("every beat of the dance looks different from the one before: " + moved, moved >= 6);
        assertFalse("dancing is not how it rests", same(rest, previous));
    }
}
