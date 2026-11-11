package com.repscroll.app.pushup

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** Synthesizes a front-on view: hands planted on the floor, shoulders rising and falling above them. */
class FrontPushupCounterTest {

    private val fps = 30
    private val frameMs = 1000L / fps
    private val shoulderWidth = 120f
    private val armLength = 160f

    /** [depth] 0 = arms locked out, 1 = chest at the floor. */
    private fun frame(t: Long, depth: Float, handShift: Float = 0f, likelihood: Float = 0.95f, seated: Boolean = false): PoseFrame {
        val handY = 400f - handShift
        val shoulderY = handY - armLength * (1 - 0.8f * depth)
        fun side(sign: Float): SideChain {
            val sh = Pt(200f + sign * shoulderWidth / 2, shoulderY, likelihood)
            val wr = Pt(200f + sign * shoulderWidth * 0.6f, handY, likelihood)
            val el = Pt(200f + sign * shoulderWidth * (0.6f + 0.4f * depth), (shoulderY + handY) / 2, likelihood)
            // In a plank the hips and knees hide behind the torso, above the hands. Seated with the
            // hands on the thighs, they are below the hands.
            return if (seated) SideChain(sh, el, wr, Pt(sh.x, handY + 40f, 0.9f), Pt(sh.x, handY + 100f, 0.9f))
            else SideChain(sh, el, wr, Pt(sh.x, sh.y + 10f, 0.3f), Pt(sh.x, sh.y + 20f, 0.3f))
        }
        return PoseFrame(t, side(-1f), side(1f))
    }

    private fun run(
        reps: Int,
        bottom: Float = 0.9f,
        durationMs: Long = 1200,
        handMove: Float = 0f,
        noise: Float = 0f,
    ): Int = runCounter(reps, bottom, durationMs, handMove, noise).reps

    private fun runCounter(
        reps: Int,
        bottom: Float = 0.9f,
        durationMs: Long = 1200,
        handMove: Float = 0f,
        noise: Float = 0f,
        seated: Boolean = false,
    ): FrontPushupCounter {
        val c = FrontPushupCounter()
        val rng = Random(3)
        var t = 0L
        repeat(15) { t += frameMs; c.update(frame(t, 0f, seated = seated)) }
        val frames = (durationMs / frameMs).toInt()
        repeat(reps) {
            for (i in 0..frames) {
                val p = i.toFloat() / frames
                val d = bottom * (1 - cos(2 * PI.toFloat() * p)) / 2 + (rng.nextFloat() * 2 - 1) * noise
                t += frameMs
                c.update(frame(t, d.coerceIn(0f, 1f), handShift = handMove * sin(PI.toFloat() * p), seated = seated))
            }
            repeat(4) { t += frameMs; c.update(frame(t, 0f, seated = seated)) }
        }
        return c
    }

    @Test fun countsFullReps() = assertEquals(10, run(10))
    @Test fun countsWithNoise() = assertEquals(10, run(10, noise = 0.05f))
    @Test fun halfRepsEarnHalfCredit() {
        val c = runCounter(8, bottom = 0.35f)
        assertEquals(0, c.reps)
        assertEquals(8, c.halfReps)
    }

    @Test fun tinyDipsEarnNothing() {
        val c = runCounter(8, bottom = 0.15f)
        assertEquals(0, c.reps)
        assertEquals(0, c.halfReps)
    }

    @Test fun fastRepsEarnNothing() {
        val c = runCounter(5, durationMs = 330)
        assertEquals(0, c.reps + c.halfReps)
    }

    @Test fun movingHandsEarnNothing() {
        val c = runCounter(5, handMove = 100f)
        assertEquals(0, c.reps + c.halfReps)
    }

    @Test fun sittingEarnsNothing() {
        // Same shoulder movement as a full pushup, but hips and knees below the hands.
        val c = runCounter(8, seated = true)
        assertEquals(0, c.reps + c.halfReps)
        assertEquals("Get into a plank, hands on the floor 🙌", c.update(frame(99_000, 0f, seated = true)).cue)
    }
}
