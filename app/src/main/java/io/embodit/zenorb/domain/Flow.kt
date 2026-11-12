package io.embodit.zenorb.domain

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.sin

/** Breath session (design sessions.breath B0..B4) and focus session (F0..F3); the two are independent. */
enum class BreathSt { NONE, READY, FOLLOW, PAUSED, SUMMARY }
enum class FocusSt { NONE, RUNNING, PAUSED, DONE }

enum class Sfx { INHALE, EXHALE, TICK, DONE, FOCUS_END }

/** What the volume draws this frame (read by the scene under [Flow.lock]). */
class OrbView {
    var f = 0f
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

data class Summary(val matched: Int, val scored: Int, val breaths: Int, val seconds: Int, val handsFree: Boolean, val pattern: PatternId) {
    val low: Boolean get() = !handsFree && scored > 0 && matched < scored / 3f
}

/** Everything the control window shows (a snapshot, emitted on change). */
data class UiState(
    val loaded: Boolean = false,
    val welcome: Boolean = false,
    val breath: BreathSt = BreathSt.NONE,
    val focus: FocusSt = FocusSt.NONE,
    val pattern: PatternId = PatternId.CALM,
    val handsFree: Boolean = false,
    /** Seconds left in the breath round (READY: the whole round). */
    val breathLeftS: Int = 0,
    val focusLeftMin: Int = 0,
    val summary: Summary? = null,
    val today: Day = Day(""),
)

/**
 * The whole app logic (design states S00..S11, interactions T1..T15). Ticked by the volume's ECS system ([tick]) and by
 * a 1 s main-thread clock ([clock], so a focus block ends on time even when the volume does not render, N8).
 * Panel buttons, 3D input and the debug broadcast all call the same methods; nothing writes state around them.
 * Input is container-agnostic (D53): [dragBegin] / [dragMove] take the vertical hand travel in content metres, whatever
 * produced it (system drag in a Shared Space volume today, a Full Space hand later).
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

    /** Session clock (s since the first breath began). */
    var t = 0f; private set
    var f = 0f; private set
    var held = false; private set
    private var handsFree = false
    private var readyT = 0f
    private var summaryT = 0f
    private var summary: Summary? = null
    private var yourTurnT = 0f
    private var sampleAcc = 0f
    private var lastBeat: Beat? = null
    private var focusDonePending = false
    private var focusDoneShownT = -1f
    private var bumpT = -1f
    private var dragF0 = 0f
    private var demoT = -1f
    private var welcome = false
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
            else -> if (fa.remaining(now()) <= 0L) { finishFocusLocked(sound = false); FocusSt.DONE } else if (fa.pausedAt != null) FocusSt.PAUSED else FocusSt.RUNNING
        }
        if (focusSt == FocusSt.PAUSED && focus!!.pausedTotal(now()) > PAUSE_LIMIT_MS) { focus = null; focusSt = FocusSt.NONE }
        if (welcome) demoT = 0f
        loaded = true
        log("LOAD welcome=$welcome focus=$focusSt days=${days.size}")
        publish()
    }

    fun snapshot(): Saved = synchronized(lock) { Saved(days, focus) }

    // ------------------------------------------------------------------ breath

    /** [开始呼吸] / tap on the orb / [再来一轮]: go to READY (S03r) with [p] (null = last rhythm). */
    fun startBreath(p: PatternId? = null) = synchronized(lock) {
        if (!loaded) return@synchronized
        val first = welcome
        pattern = when {
            p != null -> p
            first -> PatternId.BREAK
            focusSt == FocusSt.DONE -> PatternId.BREAK
            else -> settings().lastPattern
        }
        if (focusSt == FocusSt.DONE && breath == BreathSt.NONE) { focusSt = FocusSt.NONE; focusDoneShownT = -1f }
        if (p != null && p != PatternId.BREAK && p != settings().lastPattern) onSettings(settings().copy(lastPattern = p))
        demoT = -1f
        breath = BreathSt.READY
        readyT = 0f; t = 0f; summary = null; summaryT = 0f; judge.reset(); lastBeat = null
        handsFree = settings().handsFree
        log("BREATH ready pattern=$pattern handsFree=$handsFree")
        publish()
    }

    private fun beginFollow() {
        breath = BreathSt.FOLLOW
        t = 0f; judge.reset(); lastBeat = null; sampleAcc = 0f
        if (welcome) { welcome = false; onSettings(settings().copy(welcomed = true)) }
        log("BREATH follow pattern=$pattern held=$held")
        if (handsFree || TEACH_BREATHS > 0) onSfx(Sfx.INHALE)
        publish()
    }

    fun pause() = synchronized(lock) {
        if (breath == BreathSt.FOLLOW || breath == BreathSt.READY) { breath = BreathSt.PAUSED; held = false; log("BREATH paused"); publish() }
    }

    fun resume() = synchronized(lock) {
        if (breath == BreathSt.PAUSED) { breath = if (t <= 0f) BreathSt.READY else BreathSt.FOLLOW; log("BREATH resume"); publish() }
    }

    /** [结束] (no confirm: the finished breaths are kept). From READY nothing was done, so back to idle. */
    fun endBreath() = synchronized(lock) {
        when (breath) {
            BreathSt.READY -> { breath = BreathSt.NONE; afterBreathLocked() }
            BreathSt.FOLLOW, BreathSt.PAUSED -> finishBreathLocked()
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
        val hf = handsFree
        val s = Summary(if (hf) 0 else judge.matched(), judge.scored(), judge.results.size, secs, hf, pattern)
        summary = s
        breath = BreathSt.SUMMARY
        summaryT = 0f; held = false
        val d = todayLocked()
        days = days.filter { it.date != d.date } + d.copy(
            breathSeconds = d.breathSeconds + secs, breaths = d.breaths + s.breaths,
            syncScored = d.syncScored + if (hf) 0 else s.scored, syncMatched = d.syncMatched + if (hf) 0 else s.matched,
        )
        days = days.takeLast(30)
        onSfx(Sfx.DONE)
        log("BREATH summary matched=${s.matched}/${s.scored} breaths=${s.breaths} secs=$secs handsFree=$hf results=${judge.results}")
    }

    private fun afterBreathLocked() {
        if (focusDonePending) { focusDonePending = false; focusSt = FocusSt.DONE; announceFocusDoneLocked() }
    }

    // ------------------------------------------------------------------ 3D input (T1 / T4 / T14)

    /** A tap on the orb (no drag): idle / summary / focus done -> READY with the last rhythm; ready -> start now. */
    fun orbTap() = synchronized(lock) {
        when (breath) {
            BreathSt.NONE, BreathSt.SUMMARY -> { startBreath(); }
            BreathSt.READY -> beginFollow()
            BreathSt.PAUSED -> resume()
            BreathSt.FOLLOW -> Unit
        }
    }

    /** Pinch-and-drag starts on the orb. Returns true (the drag is taken). */
    fun dragBegin(): Boolean = synchronized(lock) {
        if (!loaded) return@synchronized false
        when (breath) {
            BreathSt.NONE, BreathSt.SUMMARY -> { startBreath(); held = true; beginFollow() }
            BreathSt.READY -> { held = true; beginFollow() }
            BreathSt.PAUSED -> { held = true; breath = if (t <= 0f) BreathSt.READY else BreathSt.FOLLOW; if (breath == BreathSt.READY) beginFollow() }
            BreathSt.FOLLOW -> held = true
        }
        if (handsFree) { handsFree = false; yourTurnT = 1.5f; log("BREATH hands-free -> your turn") }
        dragF0 = f
        log("DRAG begin f0=${"%.2f".format(f)} st=$breath")
        publish()
        true
    }

    /** [dyM] = vertical travel since the press in content metres (already divided by the volume's world scale). */
    fun dragMove(dyM: Float) = synchronized(lock) {
        if (!held) return@synchronized
        f = (dragF0 + GAIN * dyM / Geo.LIFT).coerceIn(0f, 1f)
    }

    fun dragEnd() = synchronized(lock) { if (held) { held = false; log("DRAG end f=${"%.2f".format(f)}") } }

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

    private fun finishFocusLocked(sound: Boolean) {
        focus = null
        val d = todayLocked()
        days = (days.filter { it.date != d.date } + d.copy(focusDone = d.focusDone + 1)).takeLast(30)
        log("FOCUS done (+1 today)")
        if (sound) Unit
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
            finishFocusLocked(sound = true)
            // design R2-6: a breath in progress is not interrupted; S07 + its bell come after the breath summary
            if (breath == BreathSt.NONE) { focusSt = FocusSt.DONE; announceFocusDoneLocked() } else { focusSt = FocusSt.NONE; focusDonePending = true }
            publish()
        }
    }

    // ------------------------------------------------------------------ other

    fun clearHistory() = synchronized(lock) { days = emptyList(); log("HISTORY cleared"); publish() }

    /** onStop / volume closed (design lifecycle: only a real stop pauses). */
    fun onBackground() = synchronized(lock) { held = false; if (breath == BreathSt.FOLLOW || breath == BreathSt.READY) { breath = BreathSt.PAUSED; publish() } }

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
        when (breath) {
            BreathSt.READY -> {
                if (!frozen) readyT += dt
                if (readyT >= (if (handsFree) 3f else 5f)) beginFollow()
            }
            BreathSt.FOLLOW -> {
                if (!frozen) t += dt
                val beat = Curve.at(pattern, t)
                if (handsFree) f = beat.fStar
                if (beat.done) finishBreathLocked()
                else {
                    val prev = lastBeat
                    if (prev != null && prev.breath != beat.breath) {
                        // the breath that just ended: tick if matched (design feedback: 「每合上一口，这口结束时轻嗒」)
                        sampleAcc = SAMPLE
                        judgeSample(beat)
                        if (!handsFree && judge.results.lastOrNull() == true && prev.breath > 0) onSfx(Sfx.TICK)
                    }
                    if (prev != null && prev.phase != beat.phase && (handsFree || beat.breath < TEACH_BREATHS)) {
                        if (beat.phase == Phase.IN) onSfx(Sfx.INHALE) else if (beat.phase == Phase.OUT) onSfx(Sfx.EXHALE)
                    }
                    if (!frozen) { sampleAcc += dt; while (sampleAcc >= SAMPLE) { sampleAcc -= SAMPLE; judgeSample(beat) } }
                    lastBeat = beat
                }
            }
            BreathSt.SUMMARY -> {
                summaryT += dt
                f = approach(f, 0f, dt / 1.2f)
                if (summaryT >= 8f) { breath = BreathSt.NONE; summary = null; afterBreathLocked() }
            }
            BreathSt.NONE -> {
                if (demoT >= 0f) {
                    demoT += dt
                    f = if (demoT < DEMO_DELAY) 0f else Curve.at(PatternId.BREAK, demoT - DEMO_DELAY).let { b -> if (b.breath > 0) 0f else b.fStar }
                    if (demoT > DEMO_DELAY + PatternId.BREAK.cycle + 4f) demoT = 0f      // loop the demo while welcome is up
                } else f = approach(f, 0f, dt / 1.2f)
            }
            BreathSt.PAUSED -> Unit
        }
        if (focusDoneShownT >= 0f) focusDoneShownT += dt
        if (bumpT >= 0f) { bumpT += dt; if (bumpT > 0.8f) bumpT = -1f }
        // ---------------------------------------------------------- view
        val beatNow = if (breath == BreathSt.FOLLOW || breath == BreathSt.PAUSED) Curve.at(pattern, t) else null
        v.f = f
        v.bump = if (bumpT >= 0f) 0.010f * sin(PI * bumpT / 0.8f).toFloat() else 0f
        val demo = breath == BreathSt.NONE && demoT >= DEMO_DELAY
        v.ringOn = breath == BreathSt.READY || breath == BreathSt.FOLLOW || breath == BreathSt.PAUSED || demo
        v.fStar = when {
            beatNow != null -> beatNow.fStar
            demo -> f
            else -> 0f
        }
        v.inBand = frozenBand ?: (breath == BreathSt.FOLLOW && !handsFree && judge.inBand)
        v.warm = approach(v.warm, if (v.inBand) 1f else 0f, dt / 0.3f)
        val breathing = breath != BreathSt.NONE
        v.dim = approach(v.dim, if (!breathing && (focusSt == FocusSt.RUNNING || focusSt == FocusSt.PAUSED)) 1f else 0f, dt / 0.5f)
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
            BreathSt.READY -> if (handsFree) Hint.CapCountdown(readyT.toInt()) else Hint.CapReady
            BreathSt.FOLLOW, BreathSt.PAUSED -> when {
                yourTurnT > 0f -> Hint.CapYourTurn
                !handsFree && breath == BreathSt.FOLLOW && judge.correction != Correction.NONE -> Hint.CapCorrection(judge.correction)
                else -> Hint.CapCount((beat?.breath ?: 0) + 1, n)
            }
            BreathSt.SUMMARY -> summary?.let { s -> if (s.handsFree) Hint.CapHandsFreeResult(s.breaths) else Hint.CapResult(s.matched, s.scored) }
            BreathSt.NONE -> when (focusSt) {
                FocusSt.RUNNING -> Hint.CapFocusLeft(focusLeftMin())
                FocusSt.PAUSED -> Hint.CapFocusPaused(focusLeftMin())
                FocusSt.DONE -> Hint.CapFocusPlus
                FocusSt.NONE -> Hint.CapIdle
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
        handsFree = handsFree,
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
        if (fv != null) f = fv.coerceIn(0f, 1f)
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
        /** Phase bells only in hands-free and the first two (teaching) breaths (design sound.list inhale / exhale). */
        const val TEACH_BREATHS = 2
        const val DEMO_DELAY = 1.0f

        fun approach(cur: Float, target: Float, step: Float): Float =
            if (cur < target) minOf(target, cur + step) else maxOf(target, cur - step)

        @Suppress("unused") private fun unused() = exp(0.0)
    }
}
