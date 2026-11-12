package io.embodit.zenorb.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The state machine through the same calls the panel, the 3D drag and the debug broadcast use. */
class FlowTest {
    private var settings = Settings(welcomed = true)
    private var clock = 1_000_000L
    private val sfx = ArrayList<Sfx>()
    private var focusDoneCalls = 0

    private fun flow(saved: Saved = Saved()): Flow = Flow({ settings }, { settings = it }, { sfx += it }, { focusDoneCalls++ }, { clock }, { "2026-11-12" }).also { it.restore(saved) }

    private fun Flow.run(seconds: Float, hand: ((Flow) -> Float)? = null) {
        var t = 0f
        while (t < seconds) {
            hand?.let { dragMove((it(this) - 0f) * Geo.LIFT) }
            tick(1f / 60); clock += 16; t += 1f / 60
        }
    }

    @Test fun pinchOnTheOrbStartsTheLastRhythmWithoutThePanel() {
        val f = flow()
        settings = settings.copy(lastPattern = PatternId.SLEEP)
        assertTrue(f.dragBegin())                           // T14: pinch on the idle orb, panel closed
        assertEquals(BreathSt.FOLLOW, f.breath)
        assertEquals(PatternId.SLEEP, f.pattern)
        f.run(PatternId.SLEEP.total + 0.5f) { Curve.at(it.pattern, it.t).fStar }
        assertEquals(BreathSt.SUMMARY, f.breath)
        val s = f.ui.value.summary!!
        assertEquals(3, s.scored); assertEquals(3, s.matched)
        assertEquals(Hint.CapResult(3, 3), f.view.caption)
        assertTrue(Sfx.DONE in sfx)
        f.run(8.5f)
        assertEquals(BreathSt.NONE, f.breath)            // 8 s without action -> idle
        assertEquals(0f, f.f, 0.01f)                      // the orb sat back down
        val d = f.todayStats()
        assertEquals(4, d.breaths); assertEquals(3, d.syncScored); assertEquals(3, d.syncMatched)
    }

    @Test fun readyWaitsForThePinchOrFiveSeconds() {
        val f = flow()
        f.startBreath(PatternId.CALM)
        assertEquals(BreathSt.READY, f.breath)
        assertEquals(Hint.CapReady, f.view.caption.also { f.run(0.1f) }.let { f.view.caption })
        assertTrue(f.view.ringOn); assertEquals(0f, f.view.fStar, 1e-4f)   // ring at the equator
        f.run(4.8f)
        assertEquals(BreathSt.READY, f.breath)
        f.run(0.4f)
        assertEquals(BreathSt.FOLLOW, f.breath)
    }

    @Test fun firstRunIsTheOneMinuteBreak() {
        settings = Settings(welcomed = false)
        val f = flow()
        assertTrue(f.ui.value.welcome)
        f.run(2f)
        assertTrue("demo ring", f.view.ringOn)
        f.startBreath()
        assertEquals(PatternId.BREAK, f.pattern)
        f.orbTap()
        assertTrue(settings.welcomed)
    }

    @Test fun tagFollowsTheOrbAndCaptionCounts() {
        val f = flow()
        f.dragBegin()
        f.run(10.5f) { Curve.at(it.pattern, it.t).fStar }
        val tag = f.view.tag as Hint.Tag
        assertEquals(Phase.IN, tag.phase)
        assertEquals(Hint.CapCount(2, 18), f.view.caption)
        // T15: the tag sits at the orb centre height (OrbScene uses the same Geo.orbY(f) for both)
        assertEquals(Geo.orbY(f.view.f), Geo.orbY(f.f), 1e-6f)
    }

    @Test fun pauseKeepsTheOrbWhereItIs() {
        val f = flow()
        f.dragBegin(); f.dragMove(0.07f)
        f.run(1f)
        val at = f.f
        f.pause(); f.run(3f)
        assertEquals(at, f.f, 1e-6f)
        assertEquals(Hint.TagPaused, f.view.tag)
        f.resume(); assertEquals(BreathSt.FOLLOW, f.breath)
        f.endBreath(); assertEquals(BreathSt.SUMMARY, f.breath)          // [结束] without confirm
    }

    @Test fun releasedOrbStaysPut() {
        val f = flow()
        f.dragBegin(); f.dragMove(0.084f); f.dragEnd()
        f.run(2f)
        assertEquals(0.6f, f.f, 1e-3f)
        f.run(1.5f)
        assertEquals(Hint.CapCorrection(Correction.PINCH_AGAIN), f.view.caption)
    }

    @Test fun handsFreeMovesTheOrbAndADragTakesOver() {
        settings = settings.copy(handsFree = true)
        val f = flow()
        f.startBreath(PatternId.CALM)
        f.run(3.2f)
        assertEquals(BreathSt.FOLLOW, f.breath)              // 3-2-1 then go
        f.run(2f)
        assertTrue(f.f > 0.3f)
        f.dragBegin()
        assertEquals(Hint.CapYourTurn, f.view.caption.also { f.run(0.1f) }.let { f.view.caption })
    }

    @Test fun focusEndsByTheWallClockAndInvitesABreath() {
        val f = flow()
        f.startFocus(15)
        assertEquals(FocusSt.RUNNING, f.focusSt)
        assertEquals(Hint.CapFocusLeft(15), f.view.caption.also { f.run(0.1f) }.let { f.view.caption })
        clock += 15 * 60_000L
        f.clock()
        assertEquals(FocusSt.DONE, f.focusSt)
        assertTrue(Sfx.FOCUS_END in sfx); assertEquals(1, focusDoneCalls)
        assertEquals(1, f.todayStats().focusDone)
        f.focusDoneBreathe()
        assertEquals(PatternId.BREAK, f.pattern)
    }

    @Test fun focusDoneDuringABreathWaitsForTheSummary() {
        val f = flow()
        f.startFocus(15)
        f.dragBegin()
        clock += 15 * 60_000L
        f.run(1f)
        assertEquals(BreathSt.FOLLOW, f.breath)
        assertFalse(Sfx.FOCUS_END in sfx)
        f.endBreath(); f.summaryDone()
        assertEquals(FocusSt.DONE, f.focusSt)
        assertTrue(Sfx.FOCUS_END in sfx)
    }

    @Test fun killedDuringFocusResumesByTheWallClock() {
        // T8: a block that is still running comes back running; one past its end comes back as S07 and counts once
        val running = flow(Saved(focus = FocusActive(clock - 5 * 60_000L, 25 * 60_000L)))
        assertEquals(FocusSt.RUNNING, running.focusSt)
        assertEquals(20, running.ui.value.focusLeftMin)
        val over = flow(Saved(focus = FocusActive(clock - 30 * 60_000L, 25 * 60_000L)))
        assertEquals(FocusSt.DONE, over.focusSt)
        assertEquals(1, over.todayStats().focusDone)
        assertNull(over.focus)
    }

    @Test fun pausedFocusOverTenMinutesIsDropped() {
        val f = flow()
        f.startFocus(25); f.focusPause()
        clock += 11 * 60_000L
        f.clock()
        assertEquals(FocusSt.NONE, f.focusSt)
        assertEquals(0, f.todayStats().focusDone)
    }

    @Test fun giveUpDoesNotCount() {
        val f = flow()
        f.startFocus(25); f.focusGiveUp()
        assertEquals(FocusSt.NONE, f.focusSt); assertEquals(0, f.todayStats().focusDone)
    }

    @Test fun breathingDuringFocusFadesTheArc() {
        val f = flow()
        f.startFocus(25)
        f.run(0.1f)
        assertNotNull(f.view.arc); assertFalse(f.view.arcFaded)
        f.dragBegin(); f.run(0.1f)
        assertTrue(f.view.arcFaded)
        assertTrue(f.view.caption is Hint.CapCount)               // breath words win the caption
    }

    @Test fun settingsAreNotChangedByASession() {
        settings = settings.copy(lastPattern = PatternId.BOX, handsFree = false)
        val before = settings
        val f = flow()
        f.dragBegin(); f.run(5f); f.endBreath(); f.summaryDone()
        assertEquals(before, settings)
    }

    @Test fun clearEmptiesHistory() {
        val f = flow(Saved(days = listOf(Day("2026-11-12", 60, 6, 5, 4, 1))))
        assertEquals(1, f.todayStats().focusDone)
        f.clearHistory()
        assertEquals(0, f.todayStats().focusDone)
    }
}
