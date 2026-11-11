package com.repscroll.app.pushup

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Synthesizes side-on pushup poses from an elbow angle, so the counter can be
 * tested against known-good and known-bad movement without a camera.
 */
class PushupCounterTest {

    private val fps = 30
    private val frameMs = 1000L / fps
    private val seg = 80f // upper arm and forearm length, px

    /**
     * Wrist stays on the floor; shoulder height follows from the elbow angle. The support point
     * (toes for full pushups, knees for knee pushups) is also on the floor.
     */
    private fun pose(
        elbowDeg: Float,
        hipSag: Float = 0f,
        standing: Boolean = false,
        onKnees: Boolean = false,
        likelihood: Float = 0.95f,
    ): SideChain {
        val theta = elbowDeg * PI.toFloat() / 180f
        val reach = 2 * seg * sin(theta / 2) // shoulder-to-wrist distance
        val half = reach / 2
        val elbowOffset = sqrt((seg * seg - half * half).coerceAtLeast(0f))
        return if (!standing) {
            val wrist = Pt(100f, 400f, likelihood)
            val shoulder = Pt(100f, 400f - reach, likelihood)
            val elbow = Pt(100f + elbowOffset, 400f - half, likelihood)
            // Full pushup: toes at (400, 395), knee 3/4 of the way along the straight body.
            // Knee pushup: knee on the floor at (300, 398).
            val support = if (onKnees) Pt(300f, 398f) else Pt(400f, 395f)
            val hipT = if (onKnees) 0.5f else 0.45f
            val hip = Pt(shoulder.x + (support.x - shoulder.x) * hipT,
                shoulder.y + (support.y - shoulder.y) * hipT + hipSag, likelihood)
            val knee = if (onKnees) Pt(support.x, support.y, likelihood)
                else Pt(shoulder.x + (support.x - shoulder.x) * 0.75f,
                    shoulder.y + (support.y - shoulder.y) * 0.75f, likelihood)
            SideChain(shoulder, elbow, wrist, hip, knee)
        } else {
            // Upright body doing arm bends in the air.
            val shoulder = Pt(200f, 100f, likelihood)
            val wrist = Pt(200f + reach, 100f, likelihood)
            val elbow = Pt(200f + half, 100f + elbowOffset, likelihood)
            SideChain(shoulder, elbow, wrist, Pt(200f, 250f, likelihood), Pt(200f, 330f, likelihood))
        }
    }

    private class Sim(val counter: PushupCounter = PushupCounter()) {
        var t = 0L
        fun feed(chain: SideChain?, step: Long): CounterState {
            t += step
            return counter.update(PoseFrame(t, null, chain))
        }
    }

    /** Hold at the top, then run [reps] smooth reps of [durationMs] each between [top] and [bottom] degrees. */
    private fun runReps(
        sim: Sim,
        reps: Int,
        top: Float = 165f,
        bottom: Float = 75f,
        durationMs: Long = 1200,
        hipSag: Float = 0f,
        standing: Boolean = false,
        onKnees: Boolean = false,
        noiseDeg: Float = 0f,
        rng: Random = Random(7),
    ) {
        repeat(fps / 2) { sim.feed(pose(top, standing = standing, onKnees = onKnees), frameMs) }
        val frames = (durationMs / frameMs).toInt()
        repeat(reps) {
            for (i in 0..frames) {
                val phase = i.toFloat() / frames
                val a = top - (top - bottom) * (1 - cos(2 * PI.toFloat() * phase)) / 2
                val noisy = a + (rng.nextFloat() * 2 - 1) * noiseDeg
                sim.feed(pose(noisy, hipSag = hipSag, standing = standing, onKnees = onKnees), frameMs)
            }
            repeat(4) { sim.feed(pose(top, standing = standing, onKnees = onKnees), frameMs) }
        }
    }

    @Test fun countsFullReps() {
        val sim = Sim()
        runReps(sim, 10)
        assertEquals(10, sim.counter.reps)
        assertEquals(0, sim.counter.halfReps)
    }

    @Test fun countsFullRepsWithLandmarkNoise() {
        val sim = Sim()
        runReps(sim, 10, noiseDeg = 6f)
        assertEquals(10, sim.counter.reps)
    }

    @Test fun countsKneePushups() {
        val sim = Sim()
        runReps(sim, 10, onKnees = true, noiseDeg = 4f)
        assertEquals(10, sim.counter.reps)
    }

    @Test fun saggingKneePushupsEarnHalfCredit() {
        val sim = Sim()
        runReps(sim, 5, onKnees = true, hipSag = 70f)
        assertEquals(0, sim.counter.reps)
        assertEquals(5, sim.counter.halfReps)
    }

    @Test fun halfKneePushupsEarnHalfCredit() {
        val sim = Sim()
        runReps(sim, 5, onKnees = true, bottom = 112f)
        assertEquals(0, sim.counter.reps)
        assertEquals(5, sim.counter.halfReps)
    }

    @Test fun halfRepsEarnHalfCredit() {
        val sim = Sim()
        runReps(sim, 10, bottom = 112f)
        assertEquals(0, sim.counter.reps)
        assertEquals(10, sim.counter.halfReps)
    }

    @Test fun barelyBendingEarnsNothing() {
        val sim = Sim()
        runReps(sim, 5, bottom = 145f)
        assertEquals(0, sim.counter.reps)
        assertEquals(0, sim.counter.halfReps)
    }

    @Test fun saggingHipsEarnHalfCredit() {
        val sim = Sim()
        runReps(sim, 5, hipSag = 70f)
        assertEquals(0, sim.counter.reps)
        assertEquals(5, sim.counter.halfReps)
    }

    @Test fun repsThatAreTooFastEarnNothing() {
        val sim = Sim()
        runReps(sim, 5, durationMs = 330)
        assertEquals(0, sim.counter.reps)
        assertEquals(0, sim.counter.halfReps)
    }

    @Test fun armBendsWhileStandingEarnNothing() {
        val sim = Sim()
        runReps(sim, 5, standing = true)
        assertEquals(0, sim.counter.reps)
        assertEquals(0, sim.counter.halfReps)
    }

    @Test fun jitterAtTheTopDoesNotCount() {
        val sim = Sim()
        val rng = Random(1)
        repeat(fps * 5) { sim.feed(pose(160f + (rng.nextFloat() * 2 - 1) * 8f), frameMs) }
        assertEquals(0, sim.counter.reps)
        assertEquals(0, sim.counter.halfReps)
    }

    @Test fun bouncingAtTheBottomCountsOnce() {
        val sim = Sim()
        repeat(15) { sim.feed(pose(165f), frameMs) }
        for (a in 160 downTo 80 step 5) sim.feed(pose(a.toFloat()), frameMs)
        repeat(3) {
            for (a in listOf(80f, 100f, 110f, 100f, 85f)) sim.feed(pose(a), frameMs)
        }
        for (a in 80..170 step 5) sim.feed(pose(a.toFloat()), frameMs)
        repeat(5) { sim.feed(pose(165f), frameMs) }
        assertEquals(1, sim.counter.reps)
    }

    @Test fun lowConfidenceFramesAreIgnored() {
        val sim = Sim()
        repeat(15) { sim.feed(pose(165f, likelihood = 0.5f), frameMs) }
        for (a in 165 downTo 75 step 3) sim.feed(pose(a.toFloat(), likelihood = 0.5f), frameMs)
        for (a in 75..165 step 3) sim.feed(pose(a.toFloat(), likelihood = 0.5f), frameMs)
        assertEquals(0, sim.counter.reps)
        assertEquals(0, sim.counter.halfReps)
    }

    @Test fun briefTrackingLossMidRepStillCounts() {
        val sim = Sim()
        repeat(15) { sim.feed(pose(165f), frameMs) }
        for (a in 165 downTo 75 step 3) sim.feed(pose(a.toFloat()), frameMs)
        repeat(10) { sim.feed(null, frameMs) } // ~330 ms dropout at the bottom
        for (a in 75..165 step 3) sim.feed(pose(a.toFloat()), frameMs)
        assertEquals(1, sim.counter.reps)
    }

    @Test fun geometryHelpers() {
        assertEquals(90f, PushupCounter.angle(Pt(0f, 1f), Pt(0f, 0f), Pt(1f, 0f)), 0.01f)
        assertEquals(180f, PushupCounter.angle(Pt(-1f, 0f), Pt(0f, 0f), Pt(1f, 0f)), 0.01f)
        assertEquals(45f, PushupCounter.tilt(Pt(0f, 0f), Pt(1f, 1f)), 0.01f)
    }
}
