package com.repscroll.app.pushup

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** A 2D landmark in upright image coordinates (y grows downward), with ML Kit's in-frame likelihood. */
data class Pt(val x: Float, val y: Float, val likelihood: Float = 1f)

/**
 * The five landmarks on one side of the body that a side-on pushup needs. The body line is
 * shoulder-hip-knee, which is straight in both full and knee pushups, so feet don't need to be visible.
 */
data class SideChain(val shoulder: Pt, val elbow: Pt, val wrist: Pt, val hip: Pt, val knee: Pt) {
    val minLikelihood: Float
        get() = minOf(shoulder.likelihood, elbow.likelihood, wrist.likelihood, hip.likelihood, knee.likelihood)
}

data class PoseFrame(val timeMs: Long, val left: SideChain?, val right: SideChain?)

enum class Phase { NOT_READY, UP, DOWN }

/** Which way the camera looks at the body. Each has its own counter. */
enum class CameraView { SIDE, FRONT }

interface RepCounter {
    val reps: Int
    val halfReps: Int
    fun update(frame: PoseFrame): CounterState
}

/** Upbeat feedback after a counted rep, with a bigger cheer on milestones. */
internal fun praise(reps: Int): String = when {
    reps % 25 == 0 -> "🏆 $reps! Beast mode"
    reps % 10 == 0 -> "🔥 $reps! You're on fire"
    reps % 5 == 0 -> "💪 $reps! Keep it rolling"
    else -> listOf("Nice!", "Strong!", "Clean rep!", "Yes!")[reps % 4] + " $reps"
}

/** Mean of points, used to average the left and right side of the body. */
internal fun mean(pts: List<Pt>) = Pt(pts.map { it.x }.average().toFloat(), pts.map { it.y }.average().toFloat(),
    pts.minOf { it.likelihood })

data class CounterState(
    val reps: Int,
    /** Partial reps (half depth, or full depth with sagging form). Each earns half credit. */
    val halfReps: Int = 0,
    val phase: Phase,
    val bodyInFrame: Boolean,
    val elbowAngle: Float?,
    val bodyAngle: Float?,
    val cue: String,
)

/**
 * Counts strict pushups from a side-on camera view.
 *
 * A full rep is counted only when all of these hold:
 *  - the body is in a plank (shoulder-to-knee line close to horizontal); full or knee pushups both count,
 *  - arms go from locked out (> [Config.upAngle]) to bent (< [Config.downAngle]) and back,
 *  - the shoulders actually travel toward the floor (not just bending the arms),
 *  - hips stay roughly in line with shoulders and knees for the whole rep,
 *  - the rep takes at least [Config.minRepMs].
 *
 * Partial effort still earns half credit ([halfReps]) so the app never feels too strict:
 * going part-way down with a real shoulder drop, or a full-depth rep where the hips sagged.
 * Too-fast reps earn nothing; those are usually jitter or cheating.
 *
 * ML Kit reports the hidden far side with high confidence too, so both sides are averaged
 * rather than picking one; picking flip-flopped between sides and made the shoulder jump.
 * Thresholds were tuned on recorded real sessions (see PushupReplayTest).
 */
class PushupCounter(private val cfg: Config = Config()) : RepCounter {

    data class Config(
        val minLikelihood: Float = 0.8f,
        val upAngle: Float = 155f,
        val downAngle: Float = 105f,
        /** Going below this but not reaching [downAngle] earns a half rep. */
        val halfRepAngle: Float = 135f,
        /** Minimum shoulder drop (fraction of torso length) for a half rep, so arm-only bends earn nothing. */
        val minHalfDescent: Float = 0.15f,
        /** Half reps are shorter below the top line, so they get a shorter (still anti-jitter) minimum. */
        val minHalfRepMs: Long = 400,
        /** Knee pushups naturally keep a slight hip bend (measured ~140-170 degrees). */
        val minBodyAngle: Float = 135f,
        val maxBodyTilt: Float = 55f,
        /** Minimum shoulder drop, as a fraction of torso length. Real reps measured 0.5-1.0. */
        val minDescent: Float = 0.3f,
        /**
         * A clearly deep shoulder drop also reaches the bottom with a slightly less bent elbow.
         * A real knee pushup bottomed at ~108 degrees with a 0.48 drop; half reps drop ~0.2.
         */
        val deepElbowAngle: Float = 115f,
        val deepDescent: Float = 0.4f,
        val minRepMs: Long = 600,
        /** Consecutive frames (~0.3 s) of bad form before a rep is rejected; single frames glitch. */
        val badFormFrames: Int = 6,
        /** To finish a rep, shoulders must come back within this fraction of the drop from the top. */
        val returnFraction: Float = 0.4f,
        /**
         * Torso (shoulder-hip) shorter than this fraction of its length at the top means the person
         * sat back or knelt up to rest, not a rep. Seen in a real session as torso halving.
         */
        val collapseFraction: Float = 0.65f,
        val collapseFrames: Int = 3,
        /** Consecutive frames (~0.4 s) out of plank before the counter resets. */
        val outOfPlankFrames: Int = 8,
        val lostResetMs: Long = 2000,
        val emaAlpha: Float = 0.5f,
    )

    override var reps = 0
        private set
    override var halfReps = 0
        private set

    private var phase = Phase.NOT_READY
    private var smoothedElbow: Float? = null
    private var smoothedBody: Float? = null
    private var outOfPlankStreak = 0
    private var lastSeenMs = 0L
    private var repStartMs = 0L
    private var topShoulderY = 0f
    private var lowestShoulderY = 0f
    private var minElbow = 180f
    private var badFormStreak = 0
    private var topTorso = 0f
    private var collapseStreak = 0
    private var formBroken = false
    private var cue = "Get into a plank, side-on to the camera 👋"

    override fun update(frame: PoseFrame): CounterState {
        val sides = listOfNotNull(frame.left, frame.right).filter { it.minLikelihood >= cfg.minLikelihood }
        if (sides.isEmpty()) {
            if (phase != Phase.NOT_READY && frame.timeMs - lastSeenMs > cfg.lostResetMs) {
                phase = Phase.NOT_READY
                smoothedElbow = null
                smoothedBody = null
            }
            return state(false, null, null, "Scoot back a little so I can see all of you")
        }
        lastSeenMs = frame.timeMs

        val rawElbow = sides.map { angle(it.shoulder, it.elbow, it.wrist) }.average().toFloat()
        val elbow = smoothedElbow?.let { it + cfg.emaAlpha * (rawElbow - it) } ?: rawElbow
        smoothedElbow = elbow
        val rawBody = sides.map { angle(it.shoulder, it.hip, it.knee) }.average().toFloat()
        val body = smoothedBody?.let { it + cfg.emaAlpha * (rawBody - it) } ?: rawBody
        smoothedBody = body
        val shoulder = mean(sides.map { it.shoulder })
        val hip = mean(sides.map { it.hip })
        val knee = mean(sides.map { it.knee })
        val torso = max(dist(shoulder, hip), 1f)
        val shoulderY = shoulder.y
        val t = frame.timeMs

        // Resting (sitting back onto the heels) shrinks the torso sharply: cancel the rep, no credit.
        // Checked before the plank tilt, because sitting back also tilts the body.
        if (phase != Phase.NOT_READY && topTorso > 0f && torso < topTorso * cfg.collapseFraction) {
            if (++collapseStreak >= cfg.collapseFrames) {
                phase = Phase.NOT_READY
                cue = "Take a breather. Get back into a plank when ready"
            }
            return state(true, elbow, body)
        }
        collapseStreak = 0

        if (tilt(shoulder, knee) > cfg.maxBodyTilt) {
            if (++outOfPlankStreak >= cfg.outOfPlankFrames) {
                phase = Phase.NOT_READY
                cue = "Get into a plank when you're ready"
            }
            return state(true, elbow, body)
        }
        outOfPlankStreak = 0


        when (phase) {
            Phase.NOT_READY -> {
                if (elbow >= cfg.upAngle && body >= cfg.minBodyAngle) {
                    atTop(t, shoulderY, torso, fresh = true)
                    cue = "Ready! Go down 👇"
                } else {
                    cue = if (body < cfg.minBodyAngle) "Nice plank, just flatten your back a bit" else "Straighten your arms to start"
                }
            }

            Phase.UP -> {
                if (elbow >= cfg.upAngle) {
                    val descent = (lowestShoulderY - topShoulderY) / torso
                    if (minElbow < cfg.halfRepAngle && descent >= cfg.minHalfDescent &&
                        t - repStartMs >= cfg.minHalfRepMs
                    ) {
                        halfReps++
                        cue = "Half rep, +½! Go a little lower for full credit 👇"
                    }
                    atTop(t, shoulderY, torso, fresh = false)
                } else {
                    trackRep(elbow, body, shoulderY)
                    val descent = (lowestShoulderY - topShoulderY) / torso
                    val deep = elbow <= cfg.deepElbowAngle && descent >= cfg.deepDescent
                    if (elbow <= cfg.downAngle || deep) {
                        if (descent >= cfg.minDescent) {
                            phase = Phase.DOWN
                            cue = "Push!"
                        } else {
                            cue = "Bring your chest closer to the floor"
                        }
                    }
                }
            }

            Phase.DOWN -> {
                trackRep(elbow, body, shoulderY)
                val backUp = shoulderY <= topShoulderY + cfg.returnFraction * (lowestShoulderY - topShoulderY)
                if (elbow >= cfg.upAngle && backUp) {
                    cue = when {
                        t - repStartMs < cfg.minRepMs -> "Whoa, speedy! Slow it down a touch"
                        formBroken -> {
                            halfReps++
                            "+½! Keep your hips in line for full credit"
                        }
                        else -> {
                            reps++
                            praise(reps)
                        }
                    }
                    phase = Phase.UP
                    atTop(t, shoulderY, torso, fresh = true)
                    minElbow = 180f // this rep is done; don't also flag it as a half rep
                }
            }
        }
        return state(true, elbow, body)
    }

    /**
     * Arms locked out: this is where the next rep starts. While holding the top, keep the
     * highest shoulder position and longest torso seen ([fresh] starts over), so slowly
     * sagging before a rep can't lower the bar for "back at the top".
     */
    private fun atTop(t: Long, shoulderY: Float, torso: Float, fresh: Boolean) {
        phase = Phase.UP
        topTorso = if (fresh) torso else max(topTorso, torso)
        repStartMs = t
        topShoulderY = if (fresh) shoulderY else min(topShoulderY, shoulderY)
        lowestShoulderY = shoulderY
        minElbow = 180f
        badFormStreak = 0
        formBroken = false
    }

    private fun trackRep(elbow: Float, body: Float, shoulderY: Float) {
        minElbow = min(minElbow, elbow)
        lowestShoulderY = max(lowestShoulderY, shoulderY)
        badFormStreak = if (body < cfg.minBodyAngle) badFormStreak + 1 else 0
        if (badFormStreak >= cfg.badFormFrames) formBroken = true
    }

    private fun state(inFrame: Boolean, elbow: Float?, body: Float?, overrideCue: String? = null) =
        CounterState(reps, halfReps, phase, inFrame, elbow, body, overrideCue ?: cue)

    companion object {
        /** Angle at [b] formed by a-b-c, in degrees (0..180). */
        fun angle(a: Pt, b: Pt, c: Pt): Float {
            val v1x = a.x - b.x; val v1y = a.y - b.y
            val v2x = c.x - b.x; val v2y = c.y - b.y
            val denom = hypot(v1x, v1y) * hypot(v2x, v2y)
            if (denom == 0f) return 180f
            val cos = ((v1x * v2x + v1y * v2y) / denom).coerceIn(-1f, 1f)
            return Math.toDegrees(acos(cos).toDouble()).toFloat()
        }

        /** How far the a-b line is from horizontal, in degrees (0..90). */
        fun tilt(a: Pt, b: Pt): Float =
            Math.toDegrees(atan2(abs(b.y - a.y), abs(b.x - a.x)).toDouble()).toFloat()

        fun dist(a: Pt, b: Pt): Float = hypot(a.x - b.x, a.y - b.y)
    }
}
