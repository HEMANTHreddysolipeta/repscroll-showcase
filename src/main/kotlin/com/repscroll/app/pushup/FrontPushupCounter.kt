package com.repscroll.app.pushup

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Counts pushups from a camera placed on the floor in front of the head, facing the body.
 *
 * From the front, elbow angles are foreshortened and the hips are hidden, so this uses how
 * high the shoulders are above the hands, divided by shoulder width (which doesn't change
 * as you move, so it cancels out distance to the camera). The height at the top of the
 * first reps sets the reference, so it adapts to arm length and camera distance.
 *
 * A rep counts when the shoulders drop to [Config.downFraction] of the top height and come
 * back to [Config.upFraction], the hands stay planted, and it takes at least [Config.minRepMs].
 * Dipping only to [Config.halfFraction] earns a half rep.
 * Body-line (sagging hips) can't be judged from the front; use the side view for strict form.
 *
 * Plank check: only the shoulders-over-hands height would also count bowing or bending the arms
 * while sitting (a tester earned minutes that way). In a real plank the hands are on the floor,
 * so they are the lowest point: hips and knees sit at or above them. Sitting or bowing with the
 * hands on the thighs puts the hips below the hands. Nothing counts while that check fails.
 */
class FrontPushupCounter(private val cfg: Config = Config()) : RepCounter {

    data class Config(
        val minLikelihood: Float = 0.7f,
        /** Bottom: shoulders at or below this fraction of the top height above the hands. */
        val downFraction: Float = 0.55f,
        /** Back at the top: shoulders at or above this fraction of the top height. */
        val upFraction: Float = 0.85f,
        /** Dipping below this but not reaching [downFraction] earns a half rep. */
        val halfFraction: Float = 0.75f,
        /** Hands may move at most this many shoulder-widths during a rep. */
        val maxHandDrift: Float = 0.35f,
        /** Arms roughly straight under the shoulders at the top (height / shoulder width). */
        val minTopHeight: Float = 0.8f,
        val minRepMs: Long = 600,
        /** How fast the top reference follows a taller position (fraction per frame). */
        val topRiseRate: Float = 0.1f,
        /** No real pushup stays at the bottom this long; recalibrate instead of staying stuck. */
        val stuckMs: Long = 10_000,
        /** Half reps are shorter below the top line, so they get a shorter (still anti-jitter) minimum. */
        val minHalfRepMs: Long = 400,
        val lostResetMs: Long = 2000,
        val emaAlpha: Float = 0.5f,
        /**
         * Hips may be at most this far below the hands (shoulder widths, median over [plankWindow]
         * frames). Measured: real front pushups -0.45 / -0.68 median (hips above the hands, p90
         * -0.13 / -0.26); seated bow +0.23 median, p10 +0.13 (front_cheat_seated_bow.csv).
         */
        val maxHipsBelowHands: Float = 0f,
        /** Same for the knees: real -0.27 / -0.26 median (p90 0.34 / 0.25), seated bow +0.61. */
        val maxKneesBelowHands: Float = 0.5f,
        /** Frames in the plank-check median, so one misread frame neither blocks nor allows a rep. */
        val plankWindow: Int = 15,
    )

    override var reps = 0
        private set
    override var halfReps = 0
        private set

    private var phase = Phase.NOT_READY
    private var smoothed: Float? = null
    private var topHeight = 0f
    private var minHeight = Float.MAX_VALUE
    private var repStartMs = 0L
    private var lastSeenMs = 0L
    private var handAnchor: Pt? = null
    private var handsMoved = false
    private var cue = "Put the phone in front of you, facing you 👋"
    private val hipsBelow = ArrayDeque<Float>()
    private val kneesBelow = ArrayDeque<Float>()

    override fun update(frame: PoseFrame): CounterState {
        val l = frame.left
        val r = frame.right
        val ok = l != null && r != null && listOf(l.shoulder, r.shoulder, l.wrist, r.wrist)
            .all { it.likelihood >= cfg.minLikelihood }
        if (!ok) {
            if (phase != Phase.NOT_READY && frame.timeMs - lastSeenMs > cfg.lostResetMs) reset()
            return state(false, null, "Move so I can see your shoulders and both hands")
        }
        l!!; r!!
        lastSeenMs = frame.timeMs
        val t = frame.timeMs

        val shoulderWidth = max(PushupCounter.dist(l.shoulder, r.shoulder), 1f)
        val shoulders = mean(listOf(l.shoulder, r.shoulder))
        val hands = mean(listOf(l.wrist, r.wrist))
        val raw = (hands.y - shoulders.y) / shoulderWidth
        val h = smoothed?.let { it + cfg.emaAlpha * (raw - it) } ?: raw
        smoothed = h

        if (!inPlank(l, r, hands.y, shoulderWidth)) {
            // Drop any rep in progress; it starts again once they're in a plank.
            if (phase != Phase.NOT_READY) reset()
            return state(true, h * 100, "Get into a plank, hands on the floor 🙌")
        }

        when (phase) {
            Phase.NOT_READY -> {
                if (h >= cfg.minTopHeight) {
                    topHeight = h
                    atTop(t, hands)
                    cue = "Ready! Go down 👇"
                } else {
                    cue = "Hands under your shoulders, arms straight to start"
                }
            }

            Phase.UP -> {
                trackHands(hands, shoulderWidth)
                if (h >= topHeight * cfg.upFraction) {
                    if (minHeight < topHeight * cfg.halfFraction && !handsMoved && t - repStartMs >= cfg.minHalfRepMs) {
                        halfReps++
                        cue = "Half rep, +½! Go a little lower for full credit 👇"
                    }
                    // Rise gradually (a single glitched frame read 201 vs a real top of ~165 and, when
                    // followed instantly, locked the counter at the bottom), and decay very slowly so
                    // the start of a descent can't drag the reference down either.
                    topHeight = if (h > topHeight) topHeight + cfg.topRiseRate * (h - topHeight) else topHeight * 0.998f
                    atTop(t, hands)
                } else {
                    minHeight = min(minHeight, h)
                    if (h <= topHeight * cfg.downFraction) {
                        phase = Phase.DOWN
                        cue = "Push!"
                    }
                }
            }

            Phase.DOWN -> {
                if (t - repStartMs > cfg.stuckMs) {
                    reset()
                    return state(true, h * 100, "Let's reset. Arms straight to start again")
                }
                trackHands(hands, shoulderWidth)
                minHeight = min(minHeight, h)
                if (h >= topHeight * cfg.upFraction) {
                    cue = when {
                        handsMoved -> "Keep your hands planted and go again"
                        t - repStartMs < cfg.minRepMs -> "Whoa, speedy! Slow it down a touch"
                        else -> {
                            reps++
                            praise(reps)
                        }
                    }
                    atTop(t, hands)
                }
            }
        }
        return state(true, h * 100, cue)
    }

    private fun atTop(t: Long, hands: Pt) {
        phase = Phase.UP
        repStartMs = t
        minHeight = Float.MAX_VALUE
        handAnchor = hands
        handsMoved = false
    }

    private fun trackHands(hands: Pt, shoulderWidth: Float) {
        val a = handAnchor ?: return
        if (abs(hands.y - a.y) / shoulderWidth > cfg.maxHandDrift ||
            abs(hands.x - a.x) / shoulderWidth > cfg.maxHandDrift) handsMoved = true
    }

    /** Hips and knees at or above the hands, as in a plank (see the class comment). */
    private fun inPlank(l: SideChain, r: SideChain, handsY: Float, shoulderWidth: Float): Boolean {
        fun push(q: ArrayDeque<Float>, v: Float) { q.addLast(v); if (q.size > cfg.plankWindow) q.removeFirst() }
        push(hipsBelow, ((l.hip.y + r.hip.y) / 2 - handsY) / shoulderWidth)
        push(kneesBelow, ((l.knee.y + r.knee.y) / 2 - handsY) / shoulderWidth)
        return median(hipsBelow) <= cfg.maxHipsBelowHands && median(kneesBelow) <= cfg.maxKneesBelowHands
    }

    private fun median(q: ArrayDeque<Float>) = q.sorted()[q.size / 2]

    private fun reset() {
        phase = Phase.NOT_READY
        smoothed = null
    }

    /** elbowAngle carries the shoulder height (x100) so the session log shows the signal used. */
    private fun state(inFrame: Boolean, signal: Float?, c: String) =
        CounterState(reps, halfReps, phase, inFrame, signal, null, c)
}
