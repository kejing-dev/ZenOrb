package io.embodit.zenorb.domain

enum class Language { SYSTEM, ZH, EN }

/** Gate B: oat glaze & walnut by default; brass ring & basalt and moss stone & slate switchable (design styles.recommended). */
/** [stone] = the recall stone's colour, the same material family as the cradle (walnut / basalt / slate, design recallStone). */
enum class Style(val accent: Int, val ring: Int, val rimR: Float, val rimY: Float, val warm: Int, val shadow: Int, val stone: Int) {
    GLAZE(0xA88A5E, 0x9C8461, 0.053f, 0.0355f, 0xE2B988, 0x2E2620, 0x5A4030),
    BRASS(0xB08D57, 0x6E6A64, 0.058f, 0.0135f, 0x7A5A3A, 0x1E1D1C, 0x34312E),
    MOSS(0x7F876A, 0x7F876A, 0.067f, 0.0215f, 0x8A8A50, 0x24251F, 0x55595A);
}

/** App settings (design dataModel.settings). */
data class Settings(
    val language: Language = Language.SYSTEM,
    /** 0 low, 1 mid (default), 2 high. */
    val volume: Int = 1,
    val muted: Boolean = false,
    val style: Style = Style.GLAZE,
    /** A very quiet creek bed under the bells, off by default (user 2026-11-13, B24 exception: settings only). */
    val ambient: Boolean = false,
    val lastPattern: PatternId = PatternId.CALM,
    val focusMin: Int = 25,
    /** The first-run welcome (S01) has been shown and its first round done. */
    val welcomed: Boolean = false,
) {
    companion object {
        val VOLUME_PCT = intArrayOf(45, 75, 100)
        val FOCUS_LENGTHS = listOf(15, 25, 50)
    }
}

/** One day of the 30-day log (design dataModel.day). Sync is counted only for scored, non-hands-free breaths. */
data class Day(
    val date: String,
    val breathSeconds: Int = 0,
    val breaths: Int = 0,
    val syncScored: Int = 0,
    val syncMatched: Int = 0,
    val focusDone: Int = 0,
)

/** A focus block in progress, by the wall clock (design focusSpec.background). */
data class FocusActive(val startMs: Long, val lengthMs: Long, val pausedMs: Long = 0L, val pausedAt: Long? = null) {
    fun elapsed(now: Long): Long = now - startMs - pausedMs - (pausedAt?.let { now - it } ?: 0L)
    fun remaining(now: Long): Long = (lengthMs - elapsed(now)).coerceAtLeast(0L)
    fun pausedTotal(now: Long): Long = pausedMs + (pausedAt?.let { now - it } ?: 0L)
}

data class Saved(val days: List<Day> = emptyList(), val focus: FocusActive? = null)
