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

    @Test fun tapAloneFinishesAFollowRoundWithoutThePanel() {
        // T14 / M1 / M14: tap the idle orb (control window closed), never drag
        val f = flow()
        settings = settings.copy(lastPattern = PatternId.SLEEP)
        f.orbTap()
        assertEquals(BreathSt.READY, f.breath)
        f.run(0.1f); assertEquals(Hint.CapCountdown(0), f.view.caption)
        f.run(1.0f); assertEquals(Hint.CapCountdown(1), f.view.caption)
        assertFalse("no guide ring while counting down", f.view.ringOn)
        f.run(2.0f)
        assertEquals(BreathSt.FOLLOW, f.breath); assertFalse(f.lead)
        assertFalse("following: the orb is the target, no ring", f.view.ringOn)
        f.run(PatternId.SLEEP.total + 0.5f)
        assertEquals(BreathSt.SUMMARY, f.breath)
        val s = f.ui.value.summary!!
        assertFalse(s.led); assertEquals(4, s.breaths); assertEquals(0, s.scored)
        assertEquals(Hint.CapFollowResult(4), f.view.caption)
        assertTrue(Sfx.INHALE in sfx && Sfx.EXHALE in sfx && Sfx.DONE in sfx)
        assertFalse("no matched bell when nothing is scored", Sfx.TICK in sfx)
        f.run(8.5f)
        assertEquals(BreathSt.NONE, f.breath)
        assertEquals(0f, f.f, 0.01f)
        val d = f.todayStats()
        assertEquals(4, d.breaths); assertEquals(0, d.syncScored)
    }

    /**
     * User 2026-11-13 「怎么只有那个环在上下动」 (B31 / D51): in follow mode the orb itself rises / falls, swells ~8 % and
     * brightens on the in-breath, shrinks and dims on the out-breath, holds still with a steady glow, and never jumps.
     */
    @Test fun theOrbItselfBreathesHeightScaleAndLight() {
        val f = flow()
        f.startBreath(PatternId.BOX)          // 4 in, 4 hold, 4 out, 4 hold
        f.run(3.05f)
        assertEquals(BreathSt.FOLLOW, f.breath)
        val t0 = f.t
        data class S(val t: Float, val f: Float, val scale: Float, val light: Float, val glow: Float)
        val rows = ArrayList<S>()
        var maxStep = 0f
        var prev = f.f
        var tt = 0f
        while (tt < 16f) {
            f.run(1f / 60)
            tt += 1f / 60
            maxStep = maxOf(maxStep, kotlin.math.abs(f.view.f - prev)); prev = f.view.f
            rows += S(f.t - t0, f.view.f, Geo.scale(f.view.f), OrbMotion.brightness(f.view.glow), f.view.glow)
        }
        fun at(t: Float) = rows.minBy { kotlin.math.abs(it.t - t) }
        val start = at(0.2f); val top = at(4.0f); val holdA = at(5.0f); val holdB = at(7.5f); val bottom = at(12.2f); val holdC = at(14.5f)
        println("BREATH-SELF t  height  scale  light")
        for (r in listOf(start, at(2f), top, holdA, holdB, at(10f), bottom, holdC)) println("BREATH-SELF %4.1f  %.3f  %.3f  %.3f".format(r.t, Geo.orbY(r.f), r.scale, r.light))
        // height
        assertTrue(top.f > 0.9f && start.f < 0.1f && bottom.f < 0.1f)
        assertTrue(Geo.orbY(top.f) - Geo.orbY(start.f) > 0.12f)
        // swell ~8 %
        assertTrue(top.scale > 1.07f && top.scale <= 1.081f && start.scale < 1.01f)
        // light: brighter in, darker out
        assertTrue(top.light > 0.98f && bottom.light < 0.83f && start.light < 0.85f)
        // in-breath rises monotonically, out-breath falls monotonically
        val inRows = rows.filter { it.t in 0.3f..3.8f }; val outRows = rows.filter { it.t in 8.3f..11.8f }
        assertTrue(inRows.zipWithNext().all { (a, b) -> b.f >= a.f - 1e-4f && b.glow >= a.glow - 1e-4f })
        assertTrue(outRows.zipWithNext().all { (a, b) -> b.f <= a.f + 1e-4f && b.glow <= a.glow + 1e-4f })
        // the hold is still: same height and the same faint glow
        assertEquals(holdA.f, holdB.f, 0.01f); assertEquals(holdA.glow, holdB.glow, 0.02f)
        assertEquals(holdC.f, bottom.f, 0.02f)
        assertTrue("a round keeps a faint glow even at the bottom", OrbMotion.emission(holdC.glow, true) > 0.03f)
        // no jumps (D51): at 60 fps the orb never moves more than 2 % of its travel in a frame
        assertTrue("max step $maxStep", maxStep < 0.02f)
    }

    @Test fun tapPausesAndResumesInTheSameMode() {
        val f = flow()
        f.orbTap(); f.run(3.1f)
        f.run(2f)
        f.orbTap()
        assertEquals(BreathSt.PAUSED, f.breath)
        val at = f.f
        f.run(3f)
        assertEquals("paused orb stays where it is (D1)", at, f.f, 1e-4f)
        assertEquals(Hint.TagPaused, f.view.tag); assertEquals(Hint.CapPaused, f.view.caption)
        f.orbTap()
        assertEquals(BreathSt.FOLLOW, f.breath); assertFalse(f.lead)
    }

    @Test fun tapDuringTheCountdownCancels() {
        val f = flow()
        f.orbTap(); f.run(1f)
        f.orbTap()
        assertEquals(BreathSt.NONE, f.breath)
        assertEquals(Hint.CapIdle, f.view.caption.also { f.run(0.1f) }.let { f.view.caption })
    }

    @Test fun dragWhileFollowingLeadsAndScoresFromTheNextBreath() {
        // T5: follow -> lead; the running breath is not scored
        val f = flow()
        f.startBreath(PatternId.CALM); f.run(3.1f)
        f.run(5f)                                         // breath 0, out phase
        val f0 = f.f
        assertTrue(f.dragBegin())
        assertTrue(f.lead); assertEquals(1, f.scoreFrom)
        f.run(0.1f)
        assertEquals(Hint.CapYourTurn, f.view.caption); assertTrue(f.view.ringOn)
        f.run(PatternId.CALM.total) { Curve.at(it.pattern, it.t).fStar - f0 }      // hand travel from where it was taken
        assertEquals(BreathSt.SUMMARY, f.breath)
        val s = f.ui.value.summary!!
        assertTrue(s.led); assertEquals(17, s.scored); assertEquals(17, s.matched); assertEquals(18, s.breaths)
        assertEquals(Hint.CapResult(17, 17), f.view.caption)
        assertTrue(Sfx.TICK in sfx)
        assertEquals(17, f.todayStats().syncScored)
    }

    @Test fun pinchOnTheIdleOrbLeadsAtOnceAndTheFirstBreathIsFree() {
        val f = flow()
        settings = settings.copy(lastPattern = PatternId.SLEEP)
        assertTrue(f.dragBegin())                         // no countdown
        assertEquals(BreathSt.FOLLOW, f.breath); assertTrue(f.lead); assertEquals(1, f.scoreFrom)
        f.run(PatternId.SLEEP.total + 0.5f) { Curve.at(it.pattern, it.t).fStar }
        val s = f.ui.value.summary!!
        assertEquals(3, s.scored); assertEquals(3, s.matched)
        assertEquals(Hint.CapResult(3, 3), f.view.caption)
    }

    @Test fun firstRunIsTheOneMinuteBreakAndTheDemoMovesTheOrb() {
        settings = Settings(welcomed = false)
        val f = flow()
        assertTrue(f.ui.value.welcome)
        var maxF = 0f
        repeat(6 * 60) { f.run(1f / 60); maxF = maxOf(maxF, f.view.f); assertFalse("demo: no ring", f.view.ringOn) }
        assertTrue("the orb demonstrates a breath itself", maxF > 0.8f)
        f.orbTap()
        assertEquals(PatternId.BREAK, f.pattern)
        f.run(3.1f)
        assertTrue(settings.welcomed)
        f.run(10.5f)
        assertEquals("2nd breath of the first round invites a lead", Hint.CapLeadHint, f.view.caption)
    }

    @Test fun tagFollowsTheOrbAndCaptionCounts() {
        val f = flow()
        f.orbTap(); f.run(3.05f)
        f.run(10.5f)
        val tag = f.view.tag as Hint.Tag
        assertEquals(Phase.IN, tag.phase)
        assertEquals(Hint.CapCount(2, 18), f.view.caption)
        // T15: the tag sits at the orb centre height (OrbScene uses the same Geo.orbY(f) for both)
        assertEquals(Geo.orbY(f.view.f), Geo.orbY(f.f), 1e-6f)
    }

    @Test fun releasedOrbStaysPut() {
        val f = flow()
        f.dragBegin(); f.dragMove(0.084f); f.dragEnd()
        f.run(2f)
        assertEquals(0.6f, f.f, 1e-3f)
        f.run(1.5f)
        assertEquals(Hint.CapCorrection(Correction.PINCH_AGAIN), f.view.caption)
    }

    @Test fun pausedFiveMinutesEndsTheRound() {
        val f = flow()
        f.orbTap(); f.run(3.1f); f.run(12f)
        f.pause()
        f.run(299f); assertEquals(BreathSt.PAUSED, f.breath)
        f.run(1.5f); assertEquals(BreathSt.SUMMARY, f.breath)
        assertEquals(2, f.ui.value.summary!!.breaths)
    }

    @Test fun closingTheControlWindowPointsAtTheRecallStone() {
        val f = flow()
        f.panelClosed(); f.run(0.1f)
        assertEquals(Hint.CapControlsHint, f.view.caption)
        f.run(4f)
        assertEquals(Hint.CapIdle, f.view.caption)
    }

    @Test fun tapAndDragThresholds() {
        // design tapVsDrag (M17): <= 0.5 s and <= 1 cm = tap; > 1 cm = drag; held still > 0.5 s = nothing
        assertEquals(TapRule.Kind.TAP, TapRule.classify(120, 0.004f))
        assertEquals(TapRule.Kind.TAP, TapRule.classify(500, 0.010f))
        assertEquals(TapRule.Kind.DRAG, TapRule.classify(120, 0.0101f))
        assertEquals(TapRule.Kind.DRAG, TapRule.classify(2000, 0.05f))
        assertEquals(TapRule.Kind.NOTHING, TapRule.classify(501, 0.002f))
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
        settings = settings.copy(lastPattern = PatternId.BOX, ambient = false)
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
