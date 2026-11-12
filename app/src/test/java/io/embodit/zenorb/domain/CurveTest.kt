package io.embodit.zenorb.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CurveTest {
    @Test fun rhythmsHaveTheDesignLengths() {
        assertEquals(180f, PatternId.CALM.total, 0.01f)
        assertEquals(176f, PatternId.BOX.total, 0.01f)
        assertEquals(76f, PatternId.SLEEP.total, 0.01f)
        assertEquals(60f, PatternId.BREAK.total, 0.01f)
    }

    @Test fun phasesAndCountdown() {
        val b = Curve.at(PatternId.SLEEP, 5.5f)
        assertEquals(Phase.HOLD_IN, b.phase); assertEquals(1f, b.fStar, 1e-4f); assertEquals(6, b.countdown)
        val o = Curve.at(PatternId.SLEEP, 11f + 4f)
        assertEquals(Phase.OUT, o.phase); assertEquals(4, o.countdown)
        assertEquals(0f, Curve.at(PatternId.CALM, 0f).fStar, 1e-4f)
        assertEquals(1f, Curve.at(PatternId.CALM, 3.999f).fStar, 1e-3f)
        assertEquals(4, Curve.at(PatternId.CALM, 0.01f).countdown)
        assertTrue(Curve.at(PatternId.CALM, 180f).done)
        assertEquals(17, Curve.at(PatternId.CALM, 179f).breath)
    }
}
