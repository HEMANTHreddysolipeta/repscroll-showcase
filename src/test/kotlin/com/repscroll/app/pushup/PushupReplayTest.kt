package com.repscroll.app.pushup

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Replays pushup sessions recorded on a real phone (SessionRecorder CSVs) through the counter.
 * Each fixture's expected count comes from what the person actually did.
 */
class PushupReplayTest {

    private fun load(name: String): List<PoseFrame> {
        val file = File(javaClass.classLoader!!.getResource("sessions/$name")!!.toURI())
        return file.readLines().drop(1).map { line ->
            val c = line.split(',')
            fun side(offset: Int): SideChain? {
                val pts = (0 until 5).map { i ->
                    val x = c[offset + i * 3]
                    if (x.isEmpty()) return null
                    Pt(x.toFloat(), c[offset + i * 3 + 1].toFloat(), c[offset + i * 3 + 2].toFloat())
                }
                return SideChain(pts[0], pts[1], pts[2], pts[3], pts[4])
            }
            PoseFrame(c[0].toLong(), side(1), side(16))
        }
    }

    @Test fun sideView_twoFull_fourKnee_oneFull() {
        // Recorded 2026-09-30: 2 full pushups, 4 knee pushups, 1 full pushup, side-on.
        // Between the 2nd and 3rd knee pushup the person went down and sat back onto their heels
        // instead of pushing up: that must earn nothing.
        val frames = load("side_3full_4knee.csv")
        val counter = PushupCounter()
        val repTimes = mutableListOf<Double>()
        frames.forEach {
            val before = counter.reps
            counter.update(it)
            if (counter.reps > before) repTimes += 88 + (it.timeMs - frames.first().timeMs) / 1000.0
        }
        assertEquals(7, counter.reps)
        assertEquals(0, counter.halfReps)
        // Each rep lands where it happened in the recording (seconds into the session).
        val expected = listOf(99.8, 103.4, 107.0, 113.4, 129.8, 137.6, 157.1)
        expected.zip(repTimes).forEach { (want, got) -> assertEquals(want, got, 0.5) }
    }

    @Test fun frontView_tenFull_oneHalf() {
        // Recorded 2026-10-01, phone on the floor facing the person: 10 pushups, one of them
        // shallow (half credit), then getting up at the end, which must earn nothing.
        val counter = FrontPushupCounter()
        load("front_10full_1half.csv").forEach { counter.update(it) }
        assertEquals(10, counter.reps)
        assertEquals(1, counter.halfReps)
    }

    @Test fun frontView_glitchedFrameDoesNotFreezeTheCounter() {
        // Recorded 2026-10-01: at 15.6 s one frame read the arms as far longer than they were.
        // The top reference jumped to it and every later rep was ignored (0 counted).
        // It must now keep counting through the rest of the session.
        val counter = FrontPushupCounter()
        load("front_stuck_glitch.csv").forEach { counter.update(it) }
        assertEquals(6, counter.reps)
    }

    @Test fun frontView_seatedBowsEarnNothing() {
        // Recorded 2026-10-14: sitting on a chair, hands on the thighs, bowing up and down for
        // 2.5 minutes. Before the plank check this earned 2 full and 5 half reps.
        val counter = FrontPushupCounter()
        load("front_cheat_seated_bow.csv").forEach { counter.update(it) }
        assertEquals(0, counter.reps)
        assertEquals(0, counter.halfReps)
    }
}
