package com.jeep.claudecode;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.SystemClock;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/** The pixel crab (the mascot) of the greeting, in the clothes of one of its styles: it breathes and blinks, and dances when it comes up and when tapped. */
final class Crab extends View {
    private static final int COLS = 25, ROWS = 13;
    /** Where the crab's own 13×8 grid starts: in the middle, with room above for hopping and a hat and to the sides for swaying and what floats up. */
    private static final int X0 = 6, Y0 = 4;
    private static final int BODY = 0xFFD97757, EYE = 0xFF1F1E1D, HEART = 0xFFE8708F, SHADOW = 0x26808080;

    /** The body without arms and legs: x, y, width, height. */
    private static final int[][] BODY_RECTS = {{2, 0, 9, 2}, {2, 2, 9, 2}, {2, 4, 9, 2}};
    /** The left arm, down, half raised, raised (the right one is its mirror): x, y pairs. */
    private static final int[][] ARM = {
            {0, 2, 1, 2, 0, 3, 1, 3},
            {0, 1, 0, 2, 1, 2, 1, 3},
            {0, 0, 0, 1, 0, 2, 1, 2, 1, 3}};
    /** The legs' x, on the ground and stepping. */
    private static final int[][] LEGS = {{2, 5, 7, 10}, {3, 5, 7, 9}};
    /** One beat of the dance: hop (cells off the ground), left arm, right arm, legs, happy eyes, sway (cells). */
    private static final int[][] DANCE = {
            {0, 2, 0, 0, 0, -1}, {1, 2, 0, 1, 0, -1}, {0, 0, 2, 0, 0, 1}, {1, 0, 2, 1, 0, 1},
            {0, 2, 2, 0, 1, 0}, {2, 2, 2, 1, 1, 0}, {0, 1, 1, 0, 1, 0}, {2, 1, 1, 1, 1, 0}};
    private static final int INK = 0xFF1F1E1D, GOLD = 0xFFF2C14E, RED = 0xFFD63B3B, PURPLE = 0xFF6B4FBB, BONE = 0xFFF2F0E8;
    static final String[] NAMES = {"经典", "墨镜", "巫师", "海盗", "国王", "忍者"};
    /** What each style puts on: x, y, width, height, colour, on the crab's grid (y above 0 is over its head). */
    private static final int[][][] CLOTHES = {
            {},
            {{2, 1, 3, 2, INK}, {8, 1, 3, 2, INK}, {5, 1, 3, 1, INK}, {3, 1, 1, 1, 0xFF7A8594}, {9, 1, 1, 1, 0xFF7A8594}},
            {{6, -2, 1, 1, PURPLE}, {5, -1, 3, 1, PURPLE}, {3, 0, 7, 1, PURPLE}, {6, -1, 1, 1, GOLD}},
            {{4, -1, 5, 1, INK}, {2, 0, 9, 1, INK}, {6, 0, 1, 1, BONE}},
            {{3, -2, 1, 1, GOLD}, {6, -2, 1, 1, GOLD}, {9, -2, 1, 1, GOLD}, {3, -1, 7, 1, GOLD}, {6, -1, 1, 1, RED}},
            {{2, 0, 9, 1, RED}, {11, 0, 2, 1, RED}, {12, 1, 1, 1, RED}}};
    private static final String[] HEART_ROWS = {".X.X.", "XXXXX", ".XXX.", "..X.."};
    private static final String[] NOTE_ROWS = {".XX", ".X.", ".X.", "XX.", "XX."};
    private static final int BEAT = 170, IDLE_BEAT = 450, TAP_DANCE = 3400;

    private final Paint paint = new Paint();
    private final int px;
    /** What floats up from it: kind (0 heart, 1 note) and the beats it has risen. */
    private final List<int[]> floating = new ArrayList<>();
    private final int style;
    /** Only shown (in Settings): not moving, no tap. */
    private final boolean still;
    private long tapUntil;
    private int step;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!isShown()) { postDelayed(this, 1000); return; }
            boolean dance = SystemClock.uptimeMillis() < tapUntil;
            step++;
            for (int i = floating.size() - 1; i >= 0; i--) if (++floating.get(i)[1] > 6) floating.remove(i);
            if (dance && step % 4 == 0) floating.add(new int[]{(step / 4) % 2, 0});
            invalidate();
            postDelayed(this, dance || !floating.isEmpty() ? BEAT : IDLE_BEAT);
        }
    };

    Crab(Context c, int style, boolean still) {
        this(c, style, still, 3);
    }

    /** Each of its pixels unit dp wide. */
    Crab(Context c, int style, boolean still, int unit) {
        super(c);
        this.style = Math.max(0, Math.min(style, CLOTHES.length - 1));
        this.still = still;
        px = Math.max(1, Math.round(unit * c.getResources().getDisplayMetrics().density));
        setContentDescription(still ? NAMES[this.style] : "螃蟹，点一下它会跳舞");
        if (!still) setOnClickListener(v -> dance());
    }

    /** It dances for a few seconds. */
    void dance() {
        tapUntil = SystemClock.uptimeMillis() + TAP_DANCE;
        floating.add(new int[]{0, 0});
        removeCallbacks(tick);
        post(tick);
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        removeCallbacks(tick);
        if (!still) post(tick);
    }

    @Override protected void onDetachedFromWindow() {
        removeCallbacks(tick);
        super.onDetachedFromWindow();
    }

    @Override protected void onMeasure(int w, int h) {
        setMeasuredDimension(COLS * px, ROWS * px);
    }

    private void cell(Canvas c, int x, int y, int color) {
        paint.setColor(color);
        c.drawRect(x * px, y * px, (x + 1) * px, (y + 1) * px, paint);
    }

    @Override protected void onDraw(Canvas c) {
        boolean dance = !still && SystemClock.uptimeMillis() < tapUntil;
        int[] f;
        if (dance) f = DANCE[step % DANCE.length];
        else f = new int[]{0, (step / 2) % 2, (step / 2) % 2, 0, 0, 0}; // breathing: the arms rise a little and fall
        boolean closed = f[4] == 1 || (!still && !dance && step % 7 == 0);
        int dx = X0 + f[5], dy = Y0 - f[0];

        // The shadow on the box shrinks as it hops.
        int half = 4 - f[0];
        paint.setColor(SHADOW);
        c.drawRect((X0 + 6 - half) * px, (Y0 + 8) * px, (X0 + 7 + half) * px, (Y0 + 9) * px, paint);

        paint.setColor(BODY);
        for (int[] r : BODY_RECTS) c.drawRect((dx + r[0]) * px, (dy + r[1]) * px, (dx + r[0] + r[2]) * px, (dy + r[1] + r[3]) * px, paint);
        int[] left = ARM[f[1]], right = ARM[f[2]];
        for (int i = 0; i < left.length; i += 2) cell(c, dx + left[i], dy + left[i + 1], BODY);
        for (int i = 0; i < right.length; i += 2) cell(c, dx + 12 - right[i], dy + right[i + 1], BODY);
        for (int x : LEGS[f[3]]) c.drawRect((dx + x) * px, (dy + 6) * px, (dx + x + 1) * px, (dy + 8) * px, paint);

        paint.setColor(EYE);
        for (int x : new int[]{3, 9}) {
            if (closed) c.drawRect((dx + x) * px, (dy + 1) * px + px / 2, (dx + x + 1) * px, (dy + 2) * px, paint);
            else cell(c, dx + x, dy + 1, EYE);
        }

        for (int[] k : CLOTHES[style]) {
            paint.setColor(k[4]);
            c.drawRect((dx + k[0]) * px, (dy + k[1]) * px, (dx + k[0] + k[2]) * px, (dy + k[1] + k[3]) * px, paint);
        }

        for (int[] p : floating) {
            String[] rows = p[0] == 0 ? HEART_ROWS : NOTE_ROWS;
            int color = p[0] == 0 ? HEART : BODY;
            int x = X0 + 13 + (p[1] % 2), y = 8 - p[1] - (p[0] == 0 ? 0 : 1);
            paint.setColor(color);
            paint.setAlpha(255 - p[1] * 34);
            for (int r = 0; r < rows.length; r++) {
                for (int k = 0; k < rows[r].length(); k++) {
                    if (rows[r].charAt(k) == 'X' && y + r >= 0) c.drawRect((x + k) * px, (y + r) * px, (x + k + 1) * px, (y + r + 1) * px, paint);
                }
            }
        }
        paint.setAlpha(255);
    }
}
