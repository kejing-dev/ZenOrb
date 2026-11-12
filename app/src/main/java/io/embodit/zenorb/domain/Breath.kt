package io.embodit.zenorb.domain

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos

/** Geometry of the orb in the content-root frame (design spatialLayout, metres, origin = volume floor centre). */
object Geo {
    const val ORB_R = 0.050f
    const val REST_Y = 0.070f
    const val LIFT = 0.140f
    const val HIT_R = 0.060f
    const val RING_GAP = 0.012f
    const val TAG_GAP = 0.016f
    const val TAG_W = 0.080f
    const val TAG_H = 0.026f
    const val CAP_W = 0.170f
    const val CAP_H = 0.026f
    const val CAP_Y = 0.013f
    const val CAP_Z = 0.104f
    const val CAP_TILT_DEG = 20f
    /** Text height (design: 0.018 m) inside the quads above. */
    const val TEXT_H = 0.018f
    /** Volume box (design containment check): x +-0.16, y 0..0.30, z +-0.13. */
    const val BOX_X = 0.16f
    const val BOX_Y = 0.30f
    const val BOX_Z = 0.13f
    const val SHADOW_R = 0.085f

    fun scale(f: Float) = 1f + 0.2f * f
    fun radius(f: Float) = ORB_R * scale(f)
    fun orbY(f: Float) = REST_Y + LIFT * f
    fun ringR(fStar: Float) = radius(fStar) + RING_GAP
}

enum class Phase { IN, HOLD_IN, OUT, HOLD_OUT }

/** The four rhythms (design breathPatterns). BREAK is only reached from focus-done and the very first round. */
enum class PatternId(val inS: Float, val holdInS: Float, val outS: Float, val holdOutS: Float, val breaths: Int) {
    CALM(4f, 0f, 6f, 0f, 18),
    BOX(4f, 4f, 4f, 4f, 11),
    SLEEP(4f, 7f, 8f, 0f, 4),
    BREAK(4f, 0f, 6f, 0f, 6);

    val cycle: Float get() = inS + holdInS + outS + holdOutS
    val total: Float get() = cycle * breaths

    companion object {
        val SELECTABLE = listOf(CALM, BOX, SLEEP)
        fun parse(s: String?): PatternId? = entries.firstOrNull { it.name.equals(s, ignoreCase = true) }
    }
}

/** Where the guide is at time [t] (s since the first breath began). */
data class Beat(val breath: Int, val phase: Phase, val fStar: Float, val countdown: Int, val done: Boolean)

object Curve {
    fun ease(x: Float): Float = (0.5 - 0.5 * cos(PI * x.coerceIn(0f, 1f))).toFloat()

    fun at(p: PatternId, t: Float): Beat {
        if (t >= p.total) return Beat(p.breaths - 1, Phase.OUT, 0f, 0, true)
        val tt = t.coerceAtLeast(0f)
        val breath = (tt / p.cycle).toInt().coerceAtMost(p.breaths - 1)
        var u = tt - breath * p.cycle
        fun cd(len: Float, at: Float) = kotlin.math.ceil((len - at).toDouble()).toInt().coerceAtLeast(1)
        if (u < p.inS) return Beat(breath, Phase.IN, ease(u / p.inS), cd(p.inS, u), false)
        u -= p.inS
        if (u < p.holdInS) return Beat(breath, Phase.HOLD_IN, 1f, cd(p.holdInS, u), false)
        u -= p.holdInS
        if (u < p.outS) return Beat(breath, Phase.OUT, 1f - ease(u / p.outS), cd(p.outS, u), false)
        u -= p.outS
        return Beat(breath, Phase.HOLD_OUT, 0f, cd(p.holdOutS, u), false)
    }
}

enum class Correction { NONE, PINCH_AGAIN, SLOWER, HIGHER, LOWER, LOWER_SLOWLY }

/**
 * The forgiving sync judge (design followSpec.sync, D27): 30 Hz samples; velocity = least-squares slope of f over the
 * last 0.3 s; a sample is right while held and in: v > +0.05 or |f - f*| <= 0.25; out: v < -0.05 or |f - f*| <= 0.25;
 * hold: |v| <= 0.08 and (|f - f*| <= 0.25 or on the right half). Released samples during a hold are left out of the denominator. A breath with
 * >= 60 % right samples is matched. The band display has time hysteresis (0.3 s right to enter, 0.6 s wrong to leave);
 * corrections appear after 1.2 s out (or 1.2 s released) and go 0.5 s after coming back.
 */
class SyncJudge {
    private val ts = FloatArray(32)
    private val fs = FloatArray(32)
    private var n = 0
    private var head = 0
    var v = 0f; private set
    var inBand = false; private set
    var correction = Correction.NONE; private set
    private var rightRun = 0f
    private var wrongRun = 0f
    private var releasedRun = 0f
    private var backRun = 0f
    private var curBreath = -1
    private var good = 0
    private var counted = 0
    /** Breath index -> matched, for every finished breath. */
    val results = ArrayList<Boolean>()
    var lastSampleRight = false; private set

    fun reset() {
        n = 0; head = 0; v = 0f; inBand = false; correction = Correction.NONE
        rightRun = 0f; wrongRun = 0f; releasedRun = 0f; backRun = 0f; curBreath = -1; good = 0; counted = 0; results.clear()
    }

    private fun slope(): Float {
        if (n < 3) return 0f
        val tNow = ts[(head - 1 + 32) % 32]
        var sx = 0.0; var sy = 0.0; var sxx = 0.0; var sxy = 0.0; var k = 0
        for (i in 0 until n) {
            val j = (head - 1 - i + 64) % 32
            if (tNow - ts[j] > 0.3001f) break
            val x = (ts[j] - tNow).toDouble(); val y = fs[j].toDouble()
            sx += x; sy += y; sxx += x * x; sxy += x * y; k++
        }
        if (k < 3) return 0f
        val d = k * sxx - sx * sx
        return if (d < 1e-12) 0f else ((k * sxy - sx * sy) / d).toFloat()
    }

    fun isRight(phase: Phase, f: Float, fStar: Float, v: Float): Boolean = when (phase) {
        Phase.IN -> v > 0.05f || abs(f - fStar) <= 0.25f
        Phase.OUT -> v < -0.05f || abs(f - fStar) <= 0.25f
        // still, and in the band or at least on the right half (deviation d1: the design's ±0.25-only hold rule fails its own
        // 「只在 0.3–0.7 来回 ≥ 90 %」 row on Box; the right-half rule keeps still / reverse <= 20 %, see SyncTest)
        Phase.HOLD_IN -> abs(v) <= 0.08f && (abs(f - fStar) <= 0.25f || f >= 0.5f)
        Phase.HOLD_OUT -> abs(v) <= 0.08f && (abs(f - fStar) <= 0.25f || f <= 0.5f)
    }

    /** Closes the running breath (if any) into [results]. */
    fun closeBreath() {
        if (curBreath >= 0) results += counted > 0 && good.toFloat() / counted >= 0.6f
        curBreath = -1; good = 0; counted = 0
    }

    /** One sample at time [t] (s). [dt] = time since the previous sample. */
    fun sample(t: Float, dt: Float, beat: Beat, f: Float, held: Boolean) {
        if (beat.breath != curBreath) { closeBreath(); curBreath = beat.breath }
        ts[head] = t; fs[head] = f; head = (head + 1) % 32; if (n < 32) n++
        v = slope()
        val hold = beat.phase == Phase.HOLD_IN || beat.phase == Phase.HOLD_OUT
        val right = held && isRight(beat.phase, f, beat.fStar, v)
        lastSampleRight = right
        if (!(hold && !held)) { counted++; if (right) good++ }
        // display band (hysteresis)
        if (right) { rightRun += dt; wrongRun = 0f } else { wrongRun += dt; rightRun = 0f }
        if (!inBand && rightRun >= 0.3f) inBand = true
        if (inBand && wrongRun >= 0.6f) inBand = false
        // corrections
        releasedRun = if (held) 0f else releasedRun + dt
        val want: Correction = when {
            !held -> if (releasedRun >= 1.2f) Correction.PINCH_AGAIN else Correction.NONE
            !inBand && wrongRun >= 1.2f -> when (beat.phase) {
                Phase.IN, Phase.HOLD_IN -> if (f > beat.fStar) Correction.SLOWER else Correction.HIGHER
                Phase.OUT, Phase.HOLD_OUT -> if (f > beat.fStar) Correction.LOWER else Correction.LOWER_SLOWLY
            }
            else -> Correction.NONE
        }
        if (want != Correction.NONE) { correction = want; backRun = 0f }
        else if (correction != Correction.NONE) {
            if (correction == Correction.PINCH_AGAIN && held) correction = Correction.NONE
            else { backRun += dt; if (backRun >= 0.5f) correction = Correction.NONE }
        }
    }

    fun matched(skipFirst: Boolean = true): Int = results.withIndex().count { (i, m) -> m && !(skipFirst && i == 0) }
    fun scored(skipFirst: Boolean = true): Int = (results.size - if (skipFirst && results.isNotEmpty()) 1 else 0).coerceAtLeast(0)
}
