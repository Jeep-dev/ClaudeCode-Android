package com.jeep.claudecode

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import java.util.Random
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Claude Code's own mascot, Clawd, in pixels, in the clothes of one of its styles. Its moves are the ones the
 * terminal version plays (the same sequences, 60 ms to a frame): it looks around while it waits, plays one when
 * the greeting comes up, and another each time it is tapped. Each of its pixels is unit dp wide.
 */
class Crab @JvmOverloads constructor(c: Context, style: Int, private val still: Boolean, unit: Int = 3) : View(c) {
    private val paint = Paint()
    private val random = Random()
    private val px = max(1, (unit * c.resources.displayMetrics.density).roundToInt())
    private val style = style.coerceIn(0, CLOTHES.size - 1)
    /** Only shown (in Settings): standing still, cut close, no tap. */
    private var playing: Array<IntArray>? = null
    private var at = 0
    private var idleAt = 0
    private var shown = IDLE[0]

    private val tick = object : Runnable {
        override fun run() {
            if (!isShown) {
                postDelayed(this, 1000)
                return
            }
            val p = playing
            val next = if (p != null) {
                p[at++].also { if (at >= p.size) playing = null }
            } else {
                IDLE[idleAt++ % IDLE.size]
            }
            if (!next.contentEquals(shown)) {
                shown = next
                invalidate()
            }
            postDelayed(this, FRAME.toLong())
        }
    }

    init {
        contentDescription = if (still) NAMES[this.style] else "螃蟹，点一下它会动"
        if (!still) setOnClickListener { play(FROM_HERE[random.nextInt(FROM_HERE.size)]) }
    }

    /** The one it plays as the greeting comes up: any of them. */
    fun enter() {
        val all = MOVES.keys.toList()
        play(all[random.nextInt(all.size)])
    }

    /** Plays a sequence, unless one is playing. */
    fun play(name: String) {
        if (still || playing != null || !MOVES.containsKey(name)) return
        playing = MOVES[name]
        at = 0
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        removeCallbacks(tick)
        if (!still) post(tick)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(tick)
        super.onDetachedFromWindow()
    }

    override fun onMeasure(w: Int, h: Int) {
        if (still) setMeasuredDimension((13 + 4) * px, (3 + 8 + 2) * px)
        else setMeasuredDimension(COLS * px, ROWS * px)
    }

    private fun cell(c: Canvas, x: Int, y: Int, color: Int) {
        paint.color = color
        c.drawRect((x * px).toFloat(), (y * px).toFloat(), ((x + 1) * px).toFloat(), ((y + 1) * px).toFloat(), paint)
    }

    private fun rect(c: Canvas, x: Int, y: Int, w: Int, h: Int, color: Int) {
        paint.color = color
        c.drawRect((x * px).toFloat(), (y * px).toFloat(), ((x + w) * px).toFloat(), ((y + h) * px).toFloat(), paint)
    }

    override fun onDraw(c: Canvas) {
        if (still) c.translate((-(X0 - 2) * px).toFloat(), (-(Y0 - 3) * px).toFloat())
        val f = if (still) DEFAULT_FRAME else shown
        // The terminal's rows and columns are a little bigger than these pixels: 3 rows are the crab's 8, 9 columns its 13.
        val down = (f[4] * 8 / 3f).roundToInt()
        val dx = X0 + (f[5] * 13 / 9f).roundToInt()
        val dy = Y0 + down

        if (down < 3) {
            val half = 4 + min(0, down / 3)
            rect(c, dx + 6 - half, Y0 + 8, 2 * half + 1, 1, SHADOW)
        }
        if (down > 0 && f[6] != 0) {
            // Dust at its feet's sides as it crouches: a dot, or a wave.
            val y = Y0 + 7
            for (side in intArrayOf(dx - 2, dx + 14)) {
                cell(c, side, y, DUST)
                if (f[6] != DOT) {
                    cell(c, side + 1, y - 1, DUST)
                    cell(c, side + 2, y, DUST)
                }
            }
        }

        // It goes down behind the ground it stands on.
        c.save()
        c.clipRect(0, 0, COLS * px, (Y0 + 8) * px)
        for (r in BODY_RECTS) rect(c, dx + r[0], dy + r[1], r[2], r[3], BODY)
        val left = ARM[f[1]]
        val right = ARM[f[2]]
        for (i in left.indices step 2) cell(c, dx + left[i], dy + left[i + 1], BODY)
        for (i in right.indices step 2) cell(c, dx + 12 - right[i], dy + right[i + 1], BODY)
        for (x in LEGS[f[3]]) rect(c, dx + x, dy + 6, 1, 2, BODY)

        val eyes = EYES[if (f[0] == LEFT) 1 else if (f[0] == RIGHT) 2 else 0]
        for (side in 0..1) {
            val shut = f[0] == CLOSED || (f[0] == WINK && side == 1)
            val x = dx + eyes[side]
            if (shut) { // a slit, the lower half of the eye
                paint.color = EYE
                c.drawRect((x * px).toFloat(), ((dy + 1) * px + px / 2).toFloat(), ((x + 1) * px).toFloat(), ((dy + 2) * px).toFloat(), paint)
            } else {
                cell(c, x, dy + 1, EYE)
            }
        }
        for (k in CLOTHES[style]) rect(c, dx + k[0], dy + k[1], k[2], k[3], k[4])
        c.restore()
    }

    private class Seq {
        private val frames = ArrayList<IntArray>()

        fun add(pose: IntArray, n: Int, down: Int = 0, x: Int = 0, dust: Int = 0): Seq {
            repeat(n) { frames.add(intArrayOf(pose[0], pose[1], pose[2], pose[3], down, x, dust)) }
            return this
        }

        fun at(pose: IntArray, n: Int, x: Int): Seq = add(pose, n, 0, x, 0)

        /** Down on its feet, dust at its sides, and up again. */
        fun crouch(): Seq = add(DEFAULT, 1, 1, 0, DOT).add(DEFAULT, 1, 1, 0, WAVE)

        /** Eyes shut for a moment. */
        fun blink(): Seq = add(SHUT, 1).add(DEFAULT, 1)

        fun frames(): Array<IntArray> = frames.toTypedArray()
    }

    companion object {
        /** Where the crab's own 13×8 grid starts: room above it for a jump and a hat, and at the sides for walking in. */
        private const val X0 = 14
        private const val Y0 = 10
        private const val COLS = X0 + 13 + X0
        private const val ROWS = Y0 + 9
        private const val BODY = 0xFFD97757.toInt()
        private const val EYE = 0xFF1F1E1D.toInt()
        private const val SHADOW = 0x26808080
        private const val DUST = 0xFF9A948C.toInt()

        /** The body without arms and legs: x, y, width, height. */
        private val BODY_RECTS = arrayOf(intArrayOf(2, 0, 9, 2), intArrayOf(2, 2, 9, 2), intArrayOf(2, 4, 9, 2))
        /** The left arm, down and raised (the right one is its mirror): x, y pairs. */
        private val ARM = arrayOf(
            intArrayOf(0, 2, 1, 2, 0, 3, 1, 3),
            intArrayOf(),
            intArrayOf(0, 0, 0, 1, 0, 2, 1, 2, 1, 3))
        /** The legs' x: both feet, the left ones only, the right ones only. */
        private val LEGS = arrayOf(intArrayOf(2, 5, 7, 10), intArrayOf(2, 5), intArrayOf(7, 10))
        /** Where the eyes are (x of the left, of the right) as they look ahead, left, right. */
        private val EYES = arrayOf(intArrayOf(3, 9), intArrayOf(2, 8), intArrayOf(4, 10))
        private const val INK = 0xFF1F1E1D.toInt()
        private const val GOLD = 0xFFF2C14E.toInt()
        private const val RED = 0xFFD63B3B.toInt()
        private const val PURPLE = 0xFF6B4FBB.toInt()
        private const val BONE = 0xFFF2F0E8.toInt()
        @JvmField val NAMES = arrayOf("经典", "墨镜", "巫师", "海盗", "国王", "忍者")
        /** What each style puts on: x, y, width, height, colour, on the crab's grid (y above 0 is over its head). */
        private val CLOTHES = arrayOf(
            arrayOf(),
            arrayOf(intArrayOf(2, 1, 3, 2, INK), intArrayOf(8, 1, 3, 2, INK), intArrayOf(5, 1, 3, 1, INK),
                intArrayOf(3, 1, 1, 1, 0xFF7A8594.toInt()), intArrayOf(9, 1, 1, 1, 0xFF7A8594.toInt())),
            arrayOf(intArrayOf(6, -2, 1, 1, PURPLE), intArrayOf(5, -1, 3, 1, PURPLE), intArrayOf(3, 0, 7, 1, PURPLE), intArrayOf(6, -1, 1, 1, GOLD)),
            arrayOf(intArrayOf(4, -1, 5, 1, INK), intArrayOf(2, 0, 9, 1, INK), intArrayOf(6, 0, 1, 1, BONE)),
            arrayOf(intArrayOf(3, -2, 1, 1, GOLD), intArrayOf(6, -2, 1, 1, GOLD), intArrayOf(9, -2, 1, 1, GOLD),
                intArrayOf(3, -1, 7, 1, GOLD), intArrayOf(6, -1, 1, 1, RED)),
            arrayOf(intArrayOf(2, 0, 9, 1, RED), intArrayOf(11, 0, 2, 1, RED), intArrayOf(12, 1, 1, 1, RED)))

        // A frame: eyes, left arm, right arm, feet, how far down (the terminal's rows), how far right (its columns), dust.
        private const val OPEN = 0
        private const val LEFT = 1
        private const val RIGHT = 2
        private const val CLOSED = 3
        private const val WINK = 4
        private const val DOWN = 0
        private const val UP = 2
        private const val BOTH = 0
        private const val LEFT_FOOT = 1
        private const val RIGHT_FOOT = 2
        private const val DOT = 1
        private const val WAVE = 2
        private val DEFAULT = intArrayOf(OPEN, DOWN, DOWN, BOTH)
        private val ARMS_UP = intArrayOf(OPEN, UP, UP, BOTH)
        private val LOOK_LEFT = intArrayOf(LEFT, DOWN, DOWN, BOTH)
        private val LOOK_RIGHT = intArrayOf(RIGHT, DOWN, DOWN, BOTH)
        private val SHUT = intArrayOf(CLOSED, DOWN, DOWN, BOTH)
        private val DEFAULT_FRAME = intArrayOf(OPEN, DOWN, DOWN, BOTH, 0, 0, 0)
        private const val FRAME = 60

        private fun seq() = Seq()

        private fun pose(eyes: Int, arms: Int, feet: Int) = intArrayOf(eyes, arms, arms, feet)

        /** What it does while it waits: it looks around. */
        private val IDLE = seq().add(DEFAULT, 12).add(LOOK_RIGHT, 5).add(LOOK_LEFT, 5).frames()

        /** The sequences the terminal's Clawd plays, by name. */
        @JvmField val MOVES = LinkedHashMap<String, Array<IntArray>>().apply {
            put("skip", seq().add(DEFAULT, 1, 1, -9, 0).at(ARMS_UP, 2, -6).at(DEFAULT, 1, -6).add(DEFAULT, 1, 1, -6, 0)
                .at(ARMS_UP, 2, -3).at(DEFAULT, 1, -3).add(DEFAULT, 1, 1, -3, 0).add(ARMS_UP, 2).crouch().add(DEFAULT, 1).frames())
            put("jump", seq().crouch().add(ARMS_UP, 3).add(DEFAULT, 1).crouch().add(ARMS_UP, 3).add(DEFAULT, 1).frames())
            put("look", seq().add(LOOK_RIGHT, 5).add(LOOK_LEFT, 5).add(DEFAULT, 1).frames())
            put("spin", seq().add(LOOK_LEFT, 2).add(LOOK_RIGHT, 2).add(LOOK_LEFT, 2).add(ARMS_UP, 3).add(DEFAULT, 1).frames())
            put("peekaboo", seq().add(DEFAULT, 1, 3).add(DEFAULT, 3, 2).add(LOOK_RIGHT, 3, 2).add(LOOK_LEFT, 3, 2)
                .add(DEFAULT, 2, 2).add(DEFAULT, 1, 1).add(ARMS_UP, 4).add(DEFAULT, 2).blink().frames())
            put("drop", seq().add(ARMS_UP, 1, -3).add(ARMS_UP, 2, -2).add(ARMS_UP, 2, -1).add(ARMS_UP, 1).crouch()
                .add(DEFAULT, 3).blink().frames())
            val waddle = seq().at(LOOK_RIGHT, 1, -9)
            for (x in -6..-1) waddle.at(pose(RIGHT, DOWN, if (x % 2 == 0) LEFT_FOOT else RIGHT_FOOT), 2, x)
            put("waddle", waddle.add(LOOK_RIGHT, 2).add(DEFAULT, 2).blink().frames())
            put("peek", seq().at(LOOK_RIGHT, 1, -9).at(LOOK_RIGHT, 5, -6).at(LOOK_RIGHT, 3, -9).at(LOOK_RIGHT, 4, -5)
                .at(ARMS_UP, 2, -3).add(ARMS_UP, 2).crouch().add(DEFAULT, 1).frames())
            put("wink", seq().add(pose(WINK, DOWN, BOTH), 5).add(DEFAULT, 1).frames())
            put("boop", seq().add(SHUT, 1, 1, 0, DOT).add(SHUT, 2, 1, 0, WAVE).add(LOOK_LEFT, 4).add(DEFAULT, 2).blink().frames())
            put("tap", seq().add(pose(OPEN, DOWN, LEFT_FOOT), 2).add(pose(OPEN, DOWN, RIGHT_FOOT), 2)
                .add(pose(OPEN, DOWN, LEFT_FOOT), 2).add(pose(OPEN, DOWN, RIGHT_FOOT), 2)
                .add(pose(OPEN, DOWN, LEFT_FOOT), 1).add(pose(OPEN, DOWN, RIGHT_FOOT), 1).add(ARMS_UP, 3).add(DEFAULT, 1).frames())
            put("sneeze", seq().add(pose(CLOSED, UP, BOTH), 4).add(SHUT, 2, 1, 0, WAVE).add(SHUT, 2).add(DEFAULT, 2).blink().frames())
        }

        /** The ones that start where it stands (a tap does not start one from out of sight). */
        private val FROM_HERE = MOVES.filterValues { it[0][5] == 0 }.keys.toList()
    }
}
