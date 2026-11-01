package com.jeep.claudecode;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Claude Code's own mascot, Clawd, in pixels, in the clothes of one of its styles. Its moves are the ones the
 * terminal version plays (the same sequences, 60 ms to a frame): it looks around while it waits, plays one when
 * the greeting comes up, and another each time it is tapped.
 */
final class Crab extends View {
    /** Where the crab's own 13×8 grid starts: room above it for a jump and a hat, and at the sides for walking in. */
    private static final int X0 = 14, Y0 = 10, COLS = X0 + 13 + X0, ROWS = Y0 + 9;
    private static final int BODY = 0xFFD97757, EYE = 0xFF1F1E1D, SHADOW = 0x26808080, DUST = 0xFF9A948C;

    /** The body without arms and legs: x, y, width, height. */
    private static final int[][] BODY_RECTS = {{2, 0, 9, 2}, {2, 2, 9, 2}, {2, 4, 9, 2}};
    /** The left arm, down and raised (the right one is its mirror): x, y pairs. */
    private static final int[][] ARM = {
            {0, 2, 1, 2, 0, 3, 1, 3},
            {},
            {0, 0, 0, 1, 0, 2, 1, 2, 1, 3}};
    /** The legs' x: both feet, the left ones only, the right ones only. */
    private static final int[][] LEGS = {{2, 5, 7, 10}, {2, 5}, {7, 10}};
    /** Where the eyes are (x of the left, of the right) as they look ahead, left, right. */
    private static final int[][] EYES = {{3, 9}, {2, 8}, {4, 10}};
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

    // A frame: eyes, left arm, right arm, feet, how far down (the terminal's rows), how far right (its columns), dust.
    private static final int OPEN = 0, LEFT = 1, RIGHT = 2, CLOSED = 3, WINK = 4;
    private static final int DOWN = 0, UP = 2;
    private static final int BOTH = 0, LEFT_FOOT = 1, RIGHT_FOOT = 2;
    private static final int DOT = 1, WAVE = 2;
    private static final int[] DEFAULT = {OPEN, DOWN, DOWN, BOTH}, ARMS_UP = {OPEN, UP, UP, BOTH},
            LOOK_LEFT = {LEFT, DOWN, DOWN, BOTH}, LOOK_RIGHT = {RIGHT, DOWN, DOWN, BOTH},
            SHUT = {CLOSED, DOWN, DOWN, BOTH};
    private static final int FRAME = 60;

    /** The sequences the terminal's Clawd plays, by name. */
    static final Map<String, int[][]> MOVES = new LinkedHashMap<>();
    /** The ones that start where it stands (a tap does not start one from out of sight). */
    private static final List<String> FROM_HERE = new ArrayList<>();
    /** What it does while it waits: it looks around. */
    private static final int[][] IDLE = seq().add(DEFAULT, 12).add(LOOK_RIGHT, 5).add(LOOK_LEFT, 5).frames();

    private static final class Seq {
        private final List<int[]> frames = new ArrayList<>();

        Seq add(int[] pose, int n, int down, int x, int dust) {
            for (int i = 0; i < n; i++) frames.add(new int[]{pose[0], pose[1], pose[2], pose[3], down, x, dust});
            return this;
        }

        Seq add(int[] pose, int n) { return add(pose, n, 0, 0, 0); }

        Seq add(int[] pose, int n, int down) { return add(pose, n, down, 0, 0); }

        Seq at(int[] pose, int n, int x) { return add(pose, n, 0, x, 0); }

        /** Down on its feet, dust at its sides, and up again. */
        Seq crouch() { return add(DEFAULT, 1, 1, 0, DOT).add(DEFAULT, 1, 1, 0, WAVE); }

        /** Eyes shut for a moment. */
        Seq blink() { return add(SHUT, 1).add(DEFAULT, 1); }

        int[][] frames() { return frames.toArray(new int[0][]); }
    }

    private static Seq seq() { return new Seq(); }

    private static int[] pose(int eyes, int arms, int feet) { return new int[]{eyes, arms, arms, feet}; }

    static {
        MOVES.put("skip", seq().add(DEFAULT, 1, 1, -9, 0).at(ARMS_UP, 2, -6).at(DEFAULT, 1, -6).add(DEFAULT, 1, 1, -6, 0)
                .at(ARMS_UP, 2, -3).at(DEFAULT, 1, -3).add(DEFAULT, 1, 1, -3, 0).add(ARMS_UP, 2).crouch().add(DEFAULT, 1).frames());
        MOVES.put("jump", seq().crouch().add(ARMS_UP, 3).add(DEFAULT, 1).crouch().add(ARMS_UP, 3).add(DEFAULT, 1).frames());
        MOVES.put("look", seq().add(LOOK_RIGHT, 5).add(LOOK_LEFT, 5).add(DEFAULT, 1).frames());
        MOVES.put("spin", seq().add(LOOK_LEFT, 2).add(LOOK_RIGHT, 2).add(LOOK_LEFT, 2).add(ARMS_UP, 3).add(DEFAULT, 1).frames());
        MOVES.put("peekaboo", seq().add(DEFAULT, 1, 3).add(DEFAULT, 3, 2).add(LOOK_RIGHT, 3, 2).add(LOOK_LEFT, 3, 2)
                .add(DEFAULT, 2, 2).add(DEFAULT, 1, 1).add(ARMS_UP, 4).add(DEFAULT, 2).blink().frames());
        MOVES.put("drop", seq().add(ARMS_UP, 1, -3).add(ARMS_UP, 2, -2).add(ARMS_UP, 2, -1).add(ARMS_UP, 1).crouch()
                .add(DEFAULT, 3).blink().frames());
        Seq waddle = seq().at(LOOK_RIGHT, 1, -9);
        for (int x = -6; x <= -1; x++) waddle.at(pose(RIGHT, DOWN, x % 2 == 0 ? LEFT_FOOT : RIGHT_FOOT), 2, x);
        MOVES.put("waddle", waddle.add(LOOK_RIGHT, 2).add(DEFAULT, 2).blink().frames());
        MOVES.put("peek", seq().at(LOOK_RIGHT, 1, -9).at(LOOK_RIGHT, 5, -6).at(LOOK_RIGHT, 3, -9).at(LOOK_RIGHT, 4, -5)
                .at(ARMS_UP, 2, -3).add(ARMS_UP, 2).crouch().add(DEFAULT, 1).frames());
        MOVES.put("wink", seq().add(pose(WINK, DOWN, BOTH), 5).add(DEFAULT, 1).frames());
        MOVES.put("boop", seq().add(SHUT, 1, 1, 0, DOT).add(SHUT, 2, 1, 0, WAVE).add(LOOK_LEFT, 4).add(DEFAULT, 2).blink().frames());
        MOVES.put("tap", seq().add(pose(OPEN, DOWN, LEFT_FOOT), 2).add(pose(OPEN, DOWN, RIGHT_FOOT), 2)
                .add(pose(OPEN, DOWN, LEFT_FOOT), 2).add(pose(OPEN, DOWN, RIGHT_FOOT), 2)
                .add(pose(OPEN, DOWN, LEFT_FOOT), 1).add(pose(OPEN, DOWN, RIGHT_FOOT), 1).add(ARMS_UP, 3).add(DEFAULT, 1).frames());
        MOVES.put("sneeze", seq().add(pose(CLOSED, UP, BOTH), 4).add(SHUT, 2, 1, 0, WAVE).add(SHUT, 2).add(DEFAULT, 2).blink().frames());
        for (Map.Entry<String, int[][]> e : MOVES.entrySet()) if (e.getValue()[0][5] == 0) FROM_HERE.add(e.getKey());
    }

    private final Paint paint = new Paint();
    private final Random random = new Random();
    private final int px;
    private final int style;
    /** Only shown (in Settings): standing still, cut close, no tap. */
    private final boolean still;
    private int[][] playing;
    private int at, idleAt;
    private int[] shown = IDLE[0];

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!isShown()) { postDelayed(this, 1000); return; }
            int[] next;
            if (playing != null) {
                next = playing[at++];
                if (at >= playing.length) playing = null;
            } else {
                next = IDLE[idleAt++ % IDLE.length];
            }
            if (!Arrays.equals(next, shown)) {
                shown = next;
                invalidate();
            }
            postDelayed(this, FRAME);
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
        setContentDescription(still ? NAMES[this.style] : "螃蟹，点一下它会动");
        if (!still) setOnClickListener(v -> play(FROM_HERE.get(random.nextInt(FROM_HERE.size()))));
    }

    /** The one it plays as the greeting comes up: any of them. */
    void enter() {
        List<String> all = new ArrayList<>(MOVES.keySet());
        play(all.get(random.nextInt(all.size())));
    }

    /** Plays a sequence, unless one is playing. */
    void play(String name) {
        if (still || playing != null || !MOVES.containsKey(name)) return;
        playing = MOVES.get(name);
        at = 0;
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
        if (still) setMeasuredDimension((13 + 4) * px, (3 + 8 + 2) * px);
        else setMeasuredDimension(COLS * px, ROWS * px);
    }

    private void cell(Canvas c, int x, int y, int color) {
        paint.setColor(color);
        c.drawRect(x * px, y * px, (x + 1) * px, (y + 1) * px, paint);
    }

    private void rect(Canvas c, int x, int y, int w, int h, int color) {
        paint.setColor(color);
        c.drawRect(x * px, y * px, (x + w) * px, (y + h) * px, paint);
    }

    @Override protected void onDraw(Canvas c) {
        if (still) c.translate(-(X0 - 2) * px, -(Y0 - 3) * px);
        int[] f = still ? DEFAULT_FRAME : shown;
        // The terminal's rows and columns are a little bigger than these pixels: 3 rows are the crab's 8, 9 columns its 13.
        int down = Math.round(f[4] * 8 / 3f), dx = X0 + Math.round(f[5] * 13 / 9f), dy = Y0 + down;

        if (down < 3) {
            int half = 4 + Math.min(0, down / 3);
            rect(c, dx + 6 - half, Y0 + 8, 2 * half + 1, 1, SHADOW);
        }
        if (down > 0 && f[6] != 0) {
            // Dust at its feet's sides as it crouches: a dot, or a wave.
            int y = Y0 + 7;
            for (int side : new int[]{dx - 2, dx + 14}) {
                if (f[6] == DOT) cell(c, side, y, DUST);
                else { cell(c, side, y, DUST); cell(c, side + 1, y - 1, DUST); cell(c, side + 2, y, DUST); }
            }
        }

        // It goes down behind the ground it stands on.
        c.save();
        c.clipRect(0, 0, COLS * px, (Y0 + 8) * px);
        paint.setColor(BODY);
        for (int[] r : BODY_RECTS) c.drawRect((dx + r[0]) * px, (dy + r[1]) * px, (dx + r[0] + r[2]) * px, (dy + r[1] + r[3]) * px, paint);
        int[] left = ARM[f[1]], right = ARM[f[2]];
        for (int i = 0; i < left.length; i += 2) cell(c, dx + left[i], dy + left[i + 1], BODY);
        for (int i = 0; i < right.length; i += 2) cell(c, dx + 12 - right[i], dy + right[i + 1], BODY);
        for (int x : LEGS[f[3]]) rect(c, dx + x, dy + 6, 1, 2, BODY);

        int[] eyes = EYES[f[0] == LEFT ? 1 : f[0] == RIGHT ? 2 : 0];
        for (int side = 0; side < 2; side++) {
            boolean shut = f[0] == CLOSED || (f[0] == WINK && side == 1);
            int x = dx + eyes[side];
            if (shut) { // a slit, the lower half of the eye
                paint.setColor(EYE);
                c.drawRect(x * px, (dy + 1) * px + px / 2, (x + 1) * px, (dy + 2) * px, paint);
            }
            else cell(c, x, dy + 1, EYE);
        }
        for (int[] k : CLOTHES[style]) rect(c, dx + k[0], dy + k[1], k[2], k[3], k[4]);
        c.restore();
    }

    private static final int[] DEFAULT_FRAME = {OPEN, DOWN, DOWN, BOTH, 0, 0, 0};
}
