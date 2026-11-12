package io.embodit.zenorb.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.sin

/**
 * D27 / design followSpec.syncCheck: >= 12 sloppy hand scripts x every rhythm must match >= 80 % of the scored breaths;
 * not moving and moving the wrong way must stay <= 20 %. Prints the table for ui-audit.
 */
class SyncTest {
    private fun run(p: PatternId, hand: (t: Float, fStar: (Float) -> Float, rnd: Random) -> Float, held: (Float) -> Boolean = { true }): Pair<Int, Int> {
        val j = SyncJudge()
        val rnd = Random(3)
        val dt = 1f / 30f
        var t = 0f
        val star = { x: Float -> Curve.at(p, x.coerceIn(0f, p.total - 1e-3f)).fStar }
        while (t < p.total) {
            val b = Curve.at(p, t)
            j.sample(t, dt, b, hand(t, star, rnd).coerceIn(0f, 1f), held(t))
            t += dt
        }
        j.closeBreath()
        return j.matched() to j.scored()
    }

    private val sloppy: List<Pair<String, (Float, (Float) -> Float, Random) -> Float>> = listOf(
        "lag 0.5 s" to { t, s, _ -> s(t - 0.5f) },
        "lag 0.8 s" to { t, s, _ -> s(t - 0.8f) },
        "lead 0.5 s" to { t, s, _ -> s(t + 0.5f) },
        "amplitude 70 %" to { t, s, _ -> 0.7f * s(t) },
        "amplitude 60 %" to { t, s, _ -> 0.6f * s(t) },
        "jitter sigma 0.03" to { t, s, r -> s(t) + 0.03f * r.nextGaussian().toFloat() },
        "only 0.3..0.7" to { t, s, _ -> 0.3f + 0.4f * s(t) },
        "hold drift 0.05" to { t, s, _ -> s(t) + 0.05f * sin(t * 0.8f) },
        "lag 0.5 + amp 60 %" to { t, s, _ -> 0.6f * s(t - 0.5f) },
        "offset +0.1" to { t, s, _ -> s(t) + 0.1f },
        "lag 0.6 + jitter 0.03" to { t, s, r -> s(t - 0.6f) + 0.03f * r.nextGaussian().toFloat() },
        "half speed hand (lag 1.0, amp 70 %)" to { t, s, _ -> 0.7f * s(t - 1.0f) },
        "small moves 10 cm (amp 0.5) + lag 0.4" to { t, s, _ -> 0.5f * s(t - 0.4f) },
    )

    @Test fun sloppyScriptsMatchAtLeast80Percent() {
        for (p in listOf(PatternId.CALM, PatternId.BOX, PatternId.SLEEP, PatternId.BREAK)) {
            for ((name, hand) in sloppy) {
                val (m, n) = run(p, hand)
                val pct = 100f * m / n
                println("SYNC %-6s %-38s %2d/%2d %5.1f%%".format(p, name, m, n, pct))
                assertTrue("$p $name $m/$n", pct >= 80f)
            }
        }
    }

    @Test fun stillAndReverseStayLow() {
        for (p in listOf(PatternId.CALM, PatternId.BOX, PatternId.SLEEP, PatternId.BREAK)) {
            val (m1, n1) = run(p, { _, _, _ -> 0f })
            val (m2, n2) = run(p, { t, s, _ -> 1f - s(t) })
            val (m3, n3) = run(p, { t, s, _ -> s(t) }, held = { false })
            println("SYNC %-6s still %d/%d · reverse %d/%d · released %d/%d".format(p, m1, n1, m2, n2, m3, n3))
            assertTrue("$p still", m1 <= 0.2f * n1)
            assertTrue("$p reverse", m2 <= 0.2f * n2)
            assertEquals("$p released", 0, m3)
        }
    }

    @Test fun firstBreathIsNotScored() {
        val (_, n) = run(PatternId.CALM, { t, s, _ -> s(t) })
        assertEquals(17, n)
        val (_, n2) = run(PatternId.SLEEP, { t, s, _ -> s(t) })
        assertEquals(3, n2)
    }

    @Test fun releasedDuringHoldIsLeftOut() {
        // 4-7-8: let go for the whole 7 s hold, follow the rest -> still matched (design followSpec.fatigue)
        val p = PatternId.SLEEP
        val (m, n) = run(p, { t, s, _ -> s(t) }, held = { t -> Curve.at(p, t).phase != Phase.HOLD_IN })
        assertEquals(n, m)
    }

    @Test fun correctionsAppearAfterAWhileOutOfBand() {
        val j = SyncJudge()
        val p = PatternId.CALM
        var t = 0f
        var seen = HashSet<Correction>()
        while (t < p.cycle * 2) { val b = Curve.at(p, t); j.sample(t, 1f / 30, b, 0f, true); seen += j.correction; t += 1f / 30 }
        assertTrue("higher shown when the orb stays down: $seen", Correction.HIGHER in seen)
        val j2 = SyncJudge(); t = 0f; seen = HashSet()
        while (t < 3f) { val b = Curve.at(p, t); j2.sample(t, 1f / 30, b, b.fStar, false); seen += j2.correction; t += 1f / 30 }
        assertTrue(Correction.PINCH_AGAIN in seen)
    }
}
