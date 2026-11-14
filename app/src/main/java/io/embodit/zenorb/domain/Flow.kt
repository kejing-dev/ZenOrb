package io.embodit.zenorb.domain

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.sin

/** Breath session (design sessions.breath B0..B4) and focus session (F0..F3); the two are independent. */
enum class BreathSt { NONE, READY, FOLLOW, PAUSED, SUMMARY }
enum class FocusSt { NONE, RUNNING, PAUSED, DONE }

enum class Sfx { INHALE, EXHALE, TICK, DONE, FOCUS_END }

/** What the volume draws this frame (read by the scene under [Flow.lock]). */
class OrbView {
    /** Orb lift 0..1 (height + the ~8 % swell). */
    var f = 0f
    /** The orb's own breath light 0..1 (brighter in, darker out, still in the holds) and whether a round is on. */
    var glow = 0f
    var inRound = false
    var bump = 0f
    var ringOn = false
    var fStar = 0f
    var inBand = false
    var warm = 0f
    var dim = 0f
    var tag: Hint? = null
    var caption: Hint? = null
    /** Remaining fraction of the focus block (null = no arc) and whether a breath is using the orb (arc faded). */
    var arc: Float? = null
    var arcFaded = false
}

/**
 * One finished round. [scored] = breaths you led (design v5: only those count); fewer than 3 = a follow round, shown as
 * 「跟了 N 口」 only.
 */
data class Summary(val matched: Int, val scored: Int, val breaths: Int, val seconds: Int, val pattern: PatternId) {
    val led: Boolean get() = scored >= 3
    val low: Boolean get() = led && matched < scored / 3f
}

/** Everything the control window shows (a snapshot, emitted on change). */
data class UiState(
    val loaded: Boolean = false,
    val welcome: Boolean = false,
    val breath: BreathSt = BreathSt.NONE,
    val focus: FocusSt = FocusSt.NONE,
    val pattern: PatternId = PatternId.CALM,
    /** The round is being led by a drag (S03) rather than followed (S03f). */
    val lead: Boolean = false,
    /** Seconds left in the breath round (READY: the whole round). */
    val breathLeftS: Int = 0,
    val focusLeftMin: Int = 0,
    val summary: Summary? = null,
    val today: Day = Day(""),
)

/**
 * The whole app logic (design v5 states S00..S11, interactions T1..T16). Ticked by the volume's ECS system ([tick]) and
 * by a 1 s main-thread clock ([clock], so a focus block ends on time even when the volume does not render, N8).
 * Panel buttons, 3D input and the debug broadcast all call the same methods; nothing writes state around them.
 *
 * v5 modes (design mainInteraction.modes): a tap on the orb (or [开始呼吸]) = 3 · 2 · 1 then FOLLOW: the orb breathes by
 * itself (rises / falls, swells ~8 %, brightens / dims; user 2026-11-13) and nothing is scored. Pinch-and-drag at any
 * time = LEAD: the guide ring appears and the breaths from the next one on are scored. A tap pauses / resumes.
 * Input is container-agnostic (D53): [dragBegin] / [dragMove] take the vertical hand travel in content metres.
 */
class Flow(
    private val settings: () -> Settings,
    private val onSettings: (Settings) -> Unit,
    private val onSfx: (Sfx) -> Unit,
    private val onFocusDone: () -> Unit = {},
    private val now: () -> Long = { System.currentTimeMillis() },
    private val today: () -> String = { java.time.LocalDate.now().toString() },
    private val log: (String) -> Unit = {},
) {
    val lock = Any()
    val view = OrbView()
    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    var loaded = false; private set
    var breath = BreathSt.NONE; private set
    var focusSt = FocusSt.NONE; private set
    var pattern = PatternId.CALM; private set
    var focus: FocusActive? = null; private set
    var days: List<Day> = emptyList(); private set
    val judge = SyncJudge()
    val motion = OrbMotion()

    /** Session clock (s since the first breath began). */
    var t = 0f; private set
    /** Orb lift 0..1 (what is drawn). */
    var f = 0f; private set
    var held = false; private set
    /** This round is led by a drag (S03); false = followed (S03f). */
    var lead = false; private set
    /** First scored breath index (-1 = nothing scored: a follow round). */
    var scoreFrom = -1; private set
    private var readyT = 0f
    private var pausedT = 0f
    private var summaryT = 0f
    private var summary: Summary? = null
    private var yourTurnT = 0f
    private var controlsHintT = 0f
    private var sampleAcc = 0f
    private var lastBeat: Beat? = null
    private var focusDonePending = false
    private var focusDoneShownT = -1f
    private var bumpT = -1f
    private var dragF0 = 0f
    private var demoT = -1f
    private var welcome = false
    /** The round right after the welcome: its 2nd breath says 「想自己来就捏住它」. */
    private var teachRound = false
    /** Debug only (QA stills, D36): stop the session clock and pin view values. */
    var frozen = false
    var frozenBand: Boolean? = null
    /** Debug focus-fast: one minute = one second. */
    var focusFast = false

    // ------------------------------------------------------------------ load / save

    fun restore(saved: Saved) = synchronized(lock) {
        days = saved.days.takeLast(30)
        focus = saved.focus
        val s = settings()
        pattern = s.lastPattern
        welcome = !s.welcomed
        focusSt = when (val fa = focus) {
            null -> FocusSt.NONE
            else -> if (fa.remaining(now()) <= 0L) { finishFocusLocked(); FocusSt.DONE } else if (fa.pausedAt != null) FocusSt.PAUSED else FocusSt.RUNNING
        }
        if (focusSt == FocusSt.PAUSED && focus!!.pausedTotal(now()) > PAUSE_LIMIT_MS) { focus = null; focusSt = FocusSt.NONE }
        if (welcome) demoT = 0f
        loaded = true
        log("LOAD welcome=$welcome focus=$focusSt days=${days.size}")
        publish()
    }

    fun snapshot(): Saved = synchronized(lock) { Saved(days, focus) }

    // ------------------------------------------------------------------ breath

    /** [开始呼吸] / tap on the orb / [再来一轮]: the 3 · 2 · 1 countdown (S03r) with [p] (null = last rhythm). */
    fun startBreath(p: PatternId? = null) = synchronized(lock) {
        if (!loaded) return@synchronized
        pattern = choosePattern(p)
        if (focusSt == FocusSt.DONE && breath == BreathSt.NONE) { focusSt = FocusSt.NONE; focusDoneShownT = -1f }
        if (p != null && p != PatternId.BREAK && p != settings().lastPattern) onSettings(settings().copy(lastPattern = p))
        demoT = -1f
        breath = BreathSt.READY
        readyT = 0f; t = 0f; summary = null; summaryT = 0f; judge.reset(); lastBeat = null; lead = false; scoreFrom = -1
        log("BREATH ready pattern=$pattern")
        publish()
    }

    private fun choosePattern(p: PatternId?): PatternId = when {
        p != null -> p
        welcome -> PatternId.BREAK
        focusSt == FocusSt.DONE -> PatternId.BREAK
        else -> settings().lastPattern
    }

    /** The round begins: follow (from the countdown) or lead (a drag from idle / the countdown: its first breath is free). */
    private fun beginRound(asLead: Boolean) {
        breath = BreathSt.FOLLOW
        t = 0f; judge.reset(); lastBeat = null; sampleAcc = 0f; pausedT = 0f
        lead = asLead; scoreFrom = if (asLead) 1 else -1
        teachRound = welcome
        if (welcome) { welcome = false; onSettings(settings().copy(welcomed = true)) }
        log("BREATH round ${if (asLead) "lead" else "follow"} pattern=$pattern")
        onSfx(Sfx.INHALE)
        publish()
    }

    fun pause() = synchronized(lock) {
        if (breath == BreathSt.FOLLOW || breath == BreathSt.READY) { breath = BreathSt.PAUSED; held = false; pausedT = 0f; log("BREATH paused lead=$lead"); publish() }
    }

    /** Back to the mode it was paused in (follow / lead); paused during the countdown = count down again. */
    fun resume() = synchronized(lock) {
        if (breath == BreathSt.PAUSED) { breath = if (t <= 0f && lastBeat == null) BreathSt.READY else BreathSt.FOLLOW; readyT = 0f; log("BREATH resume lead=$lead"); publish() }
    }

    /** [结束] (no confirm: the finished breaths are kept). From READY nothing was done, so back to idle. */
    fun endBreath() = synchronized(lock) {
        when (breath) {
            BreathSt.READY -> { breath = BreathSt.NONE; afterBreathLocked() }
            BreathSt.FOLLOW -> finishBreathLocked()
            BreathSt.PAUSED -> if (lastBeat == null) { breath = BreathSt.NONE; afterBreathLocked() } else finishBreathLocked()
            else -> Unit
        }
        publish()
    }

    /** [好了] on the summary, or 8 s without action. */
    fun summaryDone() = synchronized(lock) {
        if (breath == BreathSt.SUMMARY) { breath = BreathSt.NONE; summary = null; afterBreathLocked(); publish() }
    }

    private fun finishBreathLocked() {
        judge.closeBreath()
        val secs = t.coerceAtMost(pattern.total).toInt()
        val s = Summary(judge.matched(scoreFrom), judge.scored(scoreFrom), judge.results.size, secs, pattern)
        summary = s
        breath = BreathSt.SUMMARY
        summaryT = 0f; held = false
        val d = todayLocked()
        days = days.filter { it.date != d.date } + d.copy(
            breathSeconds = d.breathSeconds + secs, breaths = d.breaths + s.breaths,
            syncScored = d.syncScored + if (s.led) s.scored else 0, syncMatched = d.syncMatched + if (s.led) s.matched else 0,
        )
        days = days.takeLast(30)
        onSfx(Sfx.DONE)
        log("BREATH summary matched=${s.matched}/${s.scored} breaths=${s.breaths} secs=$secs lead=$lead scoreFrom=$scoreFrom results=${judge.results}")
    }

    private fun afterBreathLocked() {
        if (focusDonePending) { focusDonePending = false; focusSt = FocusSt.DONE; announceFocusDoneLocked() }
    }

    // ------------------------------------------------------------------ 3D input (T1 / T4 / T5 / T14 / T16)

    /** A tap on the orb (design tapTable): idle / summary / focus -> countdown; countdown -> cancel; round -> pause; paused -> resume. */
    fun orbTap() = synchronized(lock) {
        if (!loaded) return@synchronized
        log("TAP orb st=$breath lead=$lead")
        when (breath) {
            BreathSt.NONE, BreathSt.SUMMARY -> startBreath()
            BreathSt.READY -> { breath = BreathSt.NONE; afterBreathLocked(); publish() }
            BreathSt.FOLLOW -> pause()
            BreathSt.PAUSED -> resume()
        }
    }

    /** Pinch-and-drag starts on the orb (> 1 cm): lead mode. Returns true (the drag is taken). */
    fun dragBegin(): Boolean = synchronized(lock) {
        if (!loaded) return@synchronized false
        when (breath) {
            BreathSt.NONE, BreathSt.SUMMARY -> { startBreath(); beginRound(asLead = true) }
            BreathSt.READY -> beginRound(asLead = true)
            BreathSt.PAUSED -> { if (lastBeat == null && t <= 0f) beginRound(asLead = true) else { breath = BreathSt.FOLLOW; takeLeadLocked() } }
            BreathSt.FOLLOW -> takeLeadLocked()
        }
        held = true
        dragF0 = f
        log("DRAG begin f0=${"%.2f".format(f)} st=$breath lead=$lead scoreFrom=$scoreFrom")
        publish()
        true
    }

    /** Follow -> lead in the middle of a round: 「换你来」 1.5 s, scored from the next breath (T5). */
    private fun takeLeadLocked() {
        if (lead) return
        lead = true
        val cur = Curve.at(pattern, t).breath
        scoreFrom = cur + 1
        yourTurnT = 1.5f
        log("BREATH follow -> lead at breath $cur (scored from ${cur + 1})")
    }

    /** [dyM] = vertical travel since the press in content metres (already divided by the volume's world scale). */
    fun dragMove(dyM: Float) = synchronized(lock) {
        if (!held) return@synchronized
        f = (dragF0 + GAIN * dyM / Geo.LIFT).coerceIn(0f, 1f)
        motion.snap(f)
    }

    fun dragEnd() = synchronized(lock) { if (held) { held = false; log("DRAG end f=${"%.2f".format(f)}") } }

    /** The control window was closed with the system [×]: 「点 ⋯ 叫回控制窗」 for 4 s on the caption (design S02). */
    fun panelClosed() = synchronized(lock) { controlsHintT = 4f; log("HINT controls 4 s") }

    // ------------------------------------------------------------------ focus

    fun startFocus(min: Int) = synchronized(lock) {
        if (focusSt == FocusSt.RUNNING || focusSt == FocusSt.PAUSED) return@synchronized
        val len = if (focusFast) min * 1000L else min * 60_000L
        focus = FocusActive(now(), len)
        focusSt = FocusSt.RUNNING; focusDonePending = false
        if (min != settings().focusMin) onSettings(settings().copy(focusMin = min))
        log("FOCUS start ${min}min fast=$focusFast")
        publish()
    }

    fun focusPause() = synchronized(lock) {
        val fa = focus ?: return@synchronized
        if (focusSt == FocusSt.RUNNING) { focus = fa.copy(pausedAt = now()); focusSt = FocusSt.PAUSED; publish() }
    }

    fun focusResume() = synchronized(lock) {
        val fa = focus ?: return@synchronized
        if (focusSt == FocusSt.PAUSED) { val n = now(); focus = fa.copy(pausedMs = fa.pausedMs + (n - (fa.pausedAt ?: n)), pausedAt = null); focusSt = FocusSt.RUNNING; publish() }
    }

    /** After the [放弃] confirm (or the quit confirm while focusing): not counted. */
    fun focusGiveUp() = synchronized(lock) {
        focus = null; focusSt = FocusSt.NONE; focusDonePending = false; log("FOCUS given up"); publish()
    }

    /** S07 [先不了]. */
    fun focusDoneLater() = synchronized(lock) { if (focusSt == FocusSt.DONE) { focusSt = FocusSt.NONE; focusDoneShownT = -1f; publish() } }

    /** S07 [呼吸一分钟]. */
    fun focusDoneBreathe() = synchronized(lock) { startBreath(PatternId.BREAK) }

    private fun finishFocusLocked() {
        focus = null
        val d = todayLocked()
        days = (days.filter { it.date != d.date } + d.copy(focusDone = d.focusDone + 1)).takeLast(30)
        log("FOCUS done (+1 today)")
    }

    private fun announceFocusDoneLocked() {
        onSfx(Sfx.FOCUS_END)
        bumpT = 0f
        focusDoneShownT = 0f
        onFocusDone()
    }

    private fun checkFocusLocked() {
        val fa = focus ?: return
        val n = now()
        if (focusSt == FocusSt.PAUSED && fa.pausedTotal(n) > PAUSE_LIMIT_MS) { focus = null; focusSt = FocusSt.NONE; log("FOCUS paused > 10 min: dropped"); publish(); return }
        if (focusSt == FocusSt.RUNNING && fa.remaining(n) <= 0L) {
            finishFocusLocked()
            // design R2-6: a breath in progress is not interrupted; S07 + its bell come after the breath summary
            if (breath == BreathSt.NONE) { focusSt = FocusSt.DONE; announceFocusDoneLocked() } else { focusSt = FocusSt.NONE; focusDonePending = true }
            publish()
        }
    }

    // ------------------------------------------------------------------ other

    fun clearHistory() = synchronized(lock) { days = emptyList(); log("HISTORY cleared"); publish() }

    /** onStop / volume closed (design lifecycle: only a real stop pauses). */
    fun onBackground() = synchronized(lock) { held = false; if (breath == BreathSt.FOLLOW || breath == BreathSt.READY) { breath = BreathSt.PAUSED; pausedT = 0f; publish() } }

    private fun todayLocked(): Day = days.firstOrNull { it.date == today() } ?: Day(today())

    fun todayStats(): Day = synchronized(lock) { todayLocked() }

    // ------------------------------------------------------------------ per frame

    /** 1 s main-thread clock (focus end without frames). */
    fun clock() = synchronized(lock) { if (loaded) { checkFocusLocked(); publishIfChanged() } }

    fun tick(dt0: Float) = synchronized(lock) {
        if (!loaded) return@synchronized
        val dt = dt0.coerceIn(0f, 0.1f)
        checkFocusLocked()
        val v = view
        if (yourTurnT > 0f) yourTurnT -= dt
        if (controlsHintT > 0f) controlsHintT -= dt
        var liftTarget = f
        var glowTarget = 0f
        when (breath) {
            BreathSt.READY -> {
                if (!frozen) readyT += dt
                liftTarget = 0f
                if (readyT >= READY_S) beginRound(asLead = false)
            }
            BreathSt.FOLLOW -> {
                if (!frozen) t += dt
                val beat = Curve.at(pattern, t)
                glowTarget = beat.fStar
                if (!lead) liftTarget = beat.fStar
                if (beat.done) finishBreathLocked()
                else {
                    val prev = lastBeat
                    if (prev != null && prev.breath != beat.breath) {
                        sampleAcc = SAMPLE
                        judgeSample(beat)
                        // the breath that just ended was scored and matched: one soft bell (no wood tick, user 2026-11-13)
                        if (lead && prev.breath >= scoreFrom && scoreFrom >= 0 && judge.results.lastOrNull() == true) onSfx(Sfx.TICK)
                    }
                    if (prev != null && prev.phase != beat.phase && (!lead || beat.breath < TEACH_BREATHS)) {
                        if (beat.phase == Phase.IN) onSfx(Sfx.INHALE) else if (beat.phase == Phase.OUT) onSfx(Sfx.EXHALE)
                    }
                    if (!frozen) { sampleAcc += dt; while (sampleAcc >= SAMPLE) { sampleAcc -= SAMPLE; judgeSample(beat) } }
                    lastBeat = beat
                }
            }
            BreathSt.SUMMARY -> {
                summaryT += dt
                liftTarget = 0f
                if (summaryT >= 8f) { breath = BreathSt.NONE; summary = null; afterBreathLocked() }
            }
            BreathSt.NONE -> {
                liftTarget = 0f
                if (demoT >= 0f) {
                    demoT += dt
                    val d = demoT - DEMO_DELAY
                    if (d >= 0f) Curve.at(PatternId.BREAK, d).let { b -> if (b.breath == 0) { liftTarget = b.fStar; glowTarget = b.fStar } }
                    if (demoT > DEMO_DELAY + PatternId.BREAK.cycle + 4f) demoT = 0f      // loop the demo while welcome is up
                }
            }
            BreathSt.PAUSED -> {
                pausedT += dt
                glowTarget = view.glow
                if (pausedT >= PAUSED_END_S) { log("BREATH paused 5 min: summary"); finishBreathLocked() }
            }
        }
        // the orb moves itself unless a hand holds it (D1: a released orb stays where it was let go)
        val handOn = lead && breath == BreathSt.FOLLOW || breath == BreathSt.PAUSED
        // settling back onto the cradle (summary / idle) is slower: design S04 「球慢慢落回底座（1.2 s）」
        val tau = if (breath == BreathSt.SUMMARY || breath == BreathSt.NONE && demoT < 0f) 0.40f else 0.18f
        if (!held && !handOn) { motion.step(dt, liftTarget, glowTarget, tau); f = motion.lift }
        else motion.step(dt, f, glowTarget)
        if (frozen && breath == BreathSt.FOLLOW && !lead) { f = Curve.at(pattern, t).fStar; motion.snap(f) }
        if (focusDoneShownT >= 0f) focusDoneShownT += dt
        if (bumpT >= 0f) { bumpT += dt; if (bumpT > 0.8f) bumpT = -1f }
        // ---------------------------------------------------------- view
        val beatNow = if (breath == BreathSt.FOLLOW || breath == BreathSt.PAUSED) Curve.at(pattern, t) else null
        v.f = f
        v.glow = motion.glow
        v.inRound = breath == BreathSt.FOLLOW || breath == BreathSt.PAUSED || (breath == BreathSt.NONE && demoT >= DEMO_DELAY)
        v.bump = if (bumpT >= 0f) 0.010f * sin(PI * bumpT / 0.8f).toFloat() else 0f
        // the guide ring only while leading: when following, the orb itself is the target (design orbHints.guideRing)
        v.ringOn = lead && (breath == BreathSt.FOLLOW || breath == BreathSt.PAUSED)
        v.fStar = beatNow?.fStar ?: 0f
        v.inBand = frozenBand ?: (breath == BreathSt.FOLLOW && lead && judge.inBand)
        v.warm = approach(v.warm, if (v.inBand) 1f else 0f, dt / 0.3f)
        val breathing = breath != BreathSt.NONE
        v.dim = approach(v.dim, if (!breathing && (focusSt == FocusSt.RUNNING || focusSt == FocusSt.PAUSED)) 1f else 0f, dt / 0.5f)
        val demo = breath == BreathSt.NONE && demoT >= DEMO_DELAY
        v.tag = when {
            breath == BreathSt.PAUSED -> Hint.TagPaused
            beatNow != null -> Hint.Tag(beatNow.phase, beatNow.countdown)
            demo -> Curve.at(PatternId.BREAK, demoT - DEMO_DELAY).let { b -> if (b.breath == 0) Hint.Tag(b.phase, b.countdown) else null }
            else -> null
        }
        v.caption = captionLocked(beatNow)
        val fa = focus
        v.arc = if (fa != null && (focusSt == FocusSt.RUNNING || focusSt == FocusSt.PAUSED)) (fa.remaining(now()).toFloat() / fa.lengthMs).coerceIn(0f, 1f) else null
        v.arcFaded = breathing
        publishIfChanged()
    }

    private fun judgeSample(beat: Beat) {
        judge.sample(t, SAMPLE, beat, f, held)
    }

    private fun captionLocked(beat: Beat?): Hint? {
        val n = pattern.breaths
        return when (breath) {
            BreathSt.READY -> Hint.CapCountdown(readyT.toInt())
            BreathSt.FOLLOW -> when {
                yourTurnT > 0f -> Hint.CapYourTurn
                lead && judge.correction != Correction.NONE -> Hint.CapCorrection(judge.correction)
                !lead && teachRound && beat?.breath == 1 -> Hint.CapLeadHint
                else -> Hint.CapCount((beat?.breath ?: 0) + 1, n)
            }
            BreathSt.PAUSED -> Hint.CapPaused
            BreathSt.SUMMARY -> summary?.let { s -> if (s.led) Hint.CapResult(s.matched, s.scored) else Hint.CapFollowResult(s.breaths) }
            BreathSt.NONE -> when {
                focusSt == FocusSt.RUNNING -> Hint.CapFocusLeft(focusLeftMin())
                focusSt == FocusSt.PAUSED -> Hint.CapFocusPaused(focusLeftMin())
                focusSt == FocusSt.DONE -> Hint.CapFocusPlus
                controlsHintT > 0f -> Hint.CapControlsHint
                else -> Hint.CapIdle
            }
        }
    }

    private fun focusLeftMin(): Int {
        val fa = focus ?: return 0
        val unit = if (focusFast) 1000.0 else 60_000.0
        return ceil(fa.remaining(now()) / unit).toInt()
    }

    private var lastUi: UiState? = null

    private fun build(): UiState = UiState(
        loaded = loaded,
        welcome = welcome && breath == BreathSt.NONE,
        breath = breath,
        focus = focusSt,
        pattern = pattern,
        lead = lead,
        breathLeftS = ceil(pattern.total - t).toInt().coerceAtLeast(0),
        focusLeftMin = focusLeftMin(),
        summary = summary,
        today = todayLocked(),
    )

    private fun publish() { val u = build(); lastUi = u; _ui.value = u }

    private fun publishIfChanged() { val u = build(); if (u != lastUi) { lastUi = u; _ui.value = u } }

    fun republish() = synchronized(lock) { publish() }

    /** Debug (QA stills): pin the session at [phase] with the orb at [fv]. Goes through startBreath / dragBegin first. */
    fun debugPin(phase: Phase?, fv: Float?, band: Boolean?) = synchronized(lock) {
        if (phase != null && (breath == BreathSt.FOLLOW || breath == BreathSt.PAUSED)) {
            // the middle of that phase in the third breath (so the count reads 3 / N and the tag counts down)
            val c = pattern.cycle * 2
            t = when (phase) {
                Phase.IN -> c + pattern.inS * 0.5f
                Phase.HOLD_IN -> c + pattern.inS + pattern.holdInS * 0.5f
                Phase.OUT -> c + pattern.inS + pattern.holdInS + pattern.outS * 0.4f
                Phase.HOLD_OUT -> c + pattern.inS + pattern.holdInS + pattern.outS + pattern.holdOutS * 0.5f
            }.coerceAtMost(pattern.total - 0.1f)
        }
        if (fv != null) { f = fv.coerceIn(0f, 1f); motion.snap(f) }
        frozenBand = band
        frozen = true
        publish()
    }

    fun debugRelease() = synchronized(lock) { frozen = false; frozenBand = null }

    /** Debug: jump the session clock (s). */
    fun debugSeek(sec: Float) = synchronized(lock) { t = sec.coerceIn(0f, pattern.total) }

    /** Debug: restart the first-run welcome. */
    fun debugWelcome() = synchronized(lock) { breath = BreathSt.NONE; welcome = true; demoT = 0f; onSettings(settings().copy(welcomed = false)); publish() }

    companion object {
        const val SAMPLE = 1f / 30f
        const val GAIN = 1.0f
        const val PAUSE_LIMIT_MS = 10 * 60_000L
        /** Lead rounds ring the phase bells only for their first two (teaching) breaths (design sound.list). */
        const val TEACH_BREATHS = 2
        const val DEMO_DELAY = 1.0f
        const val READY_S = 3f
        /** S05: paused 5 minutes without a touch -> summary (the breaths so far are kept). */
        const val PAUSED_END_S = 300f

        fun approach(cur: Float, target: Float, step: Float): Float =
            if (cur < target) minOf(target, cur + step) else maxOf(target, cur - step)
    }
}
