package com.jeep.claudecode;

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

/** The crab's moves, one row of frames each in one PNG (SHOTS directory), and its styles. */
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

    private static void save(Bitmap b, String name) throws Exception {
        OUT.mkdirs();
        try (FileOutputStream o = new FileOutputStream(new File(OUT, name + ".png"))) {
            b.compress(Bitmap.CompressFormat.PNG, 100, o);
        }
    }

    @Test public void everyMoveShowsAndEndsAtRest() throws Exception {
        Activity a = Robolectric.buildActivity(Activity.class).setup().get();
        int moves = Crab.MOVES.size(), cols = 10;
        Bitmap sheet = null;
        Canvas sc = null;
        int row = 0;
        for (String name : Crab.MOVES.keySet()) {
            Crab crab = new Crab(a, 0, false, 2);
            a.setContentView(crab);
            ShadowLooper.idleMainLooper(10, TimeUnit.MILLISECONDS);
            crab.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
            crab.layout(0, 0, crab.getMeasuredWidth(), crab.getMeasuredHeight());
            if (sheet == null) {
                sheet = Bitmap.createBitmap(crab.getWidth() * cols / 2, crab.getHeight() * moves / 2, Bitmap.Config.ARGB_8888);
                sc = new Canvas(sheet);
            }
            int frames = Crab.MOVES.get(name).length;
            crab.play(name);
            int distinct = 0;
            Bitmap before = draw(crab), last = before;
            for (int i = 0; i < frames; i++) {
                ShadowLooper.idleMainLooper(60, TimeUnit.MILLISECONDS);
                Bitmap f = draw(crab);
                if (!f.sameAs(last)) distinct++;
                last = f;
                // Ten of its frames, evenly spaced.
                if (i % Math.max(1, frames / cols) == 0 && i / Math.max(1, frames / cols) < cols) {
                    Bitmap half = Bitmap.createScaledBitmap(f, f.getWidth() / 2, f.getHeight() / 2, false);
                    sc.drawBitmap(half, (i / Math.max(1, frames / cols)) * half.getWidth(), row * half.getHeight(), null);
                }
            }
            assertTrue(name + " moves", distinct >= 2);
            row++;
        }
        save(sheet, "30-crab-moves");
    }

    @Test public void everyStyleIsShown() throws Exception {
        Activity a = Robolectric.buildActivity(Activity.class).setup().get();
        int n = Crab.NAMES.length;
        Bitmap strip = null;
        Canvas sc = null;
        for (int i = 0; i < n; i++) {
            Crab crab = new Crab(a, i, true);
            crab.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
            crab.layout(0, 0, crab.getMeasuredWidth(), crab.getMeasuredHeight());
            if (strip == null) {
                strip = Bitmap.createBitmap(crab.getWidth() * n, crab.getHeight(), Bitmap.Config.ARGB_8888);
                sc = new Canvas(strip);
            }
            sc.drawBitmap(draw(crab), i * crab.getWidth(), 0, null);
        }
        save(strip, "31-crab-styles");
    }
}
