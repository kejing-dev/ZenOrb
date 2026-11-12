package io.embodit.zenorb.domain

/**
 * The words beside the orb (design hintStrings): the tag right of the orb and the caption in front of the cradle.
 * Kept in code (not strings.xml) because the volume draws them into textures and the width test runs on the JVM.
 * Every line is one of these keys; the scene renders [text] for the current language.
 */
sealed class Hint {
    data class Tag(val phase: Phase, val n: Int) : Hint()
    data object TagPaused : Hint()
    data object CapIdle : Hint()
    data object CapReady : Hint()
    data class CapCountdown(val shown: Int) : Hint()
    data class CapCount(val n: Int, val of: Int) : Hint()
    data class CapCorrection(val c: Correction) : Hint()
    data object CapYourTurn : Hint()
    data class CapResult(val matched: Int, val of: Int) : Hint()
    data class CapHandsFreeResult(val breaths: Int) : Hint()
    data class CapFocusLeft(val min: Int) : Hint()
    data class CapFocusPaused(val min: Int) : Hint()
    data object CapFocusPlus : Hint()

    fun text(zh: Boolean): String = when (this) {
        is Tag -> when (phase) {
            Phase.IN -> if (zh) "吸 $n" else "In $n"
            Phase.OUT -> if (zh) "呼 $n" else "Out $n"
            else -> if (zh) "停 $n" else "Hold $n"
        }
        TagPaused -> if (zh) "暂停" else "Paused"
        CapIdle -> if (zh) "捏住提起来吸气" else "Pinch & lift"
        CapReady -> if (zh) "捏住光球" else "Pinch the orb"
        is CapCountdown -> listOf("3", "3 · 2", "3 · 2 · 1").getOrElse(shown.coerceIn(0, 2)) { "3 · 2 · 1" }
        is CapCount -> "$n / $of"
        is CapCorrection -> when (c) {
            Correction.PINCH_AGAIN -> if (zh) "捏住接着来" else "Pinch again"
            Correction.SLOWER -> if (zh) "慢一点" else "Slower"
            Correction.HIGHER -> if (zh) "再提一点" else "Higher"
            Correction.LOWER -> if (zh) "再放低一点" else "Lower"
            Correction.LOWER_SLOWLY -> if (zh) "慢一点放" else "Ease down"
            Correction.NONE -> ""
        }
        CapYourTurn -> if (zh) "换你来" else "Your turn"
        is CapResult -> if (zh) "$matched/$of 合上" else "$matched/$of matched"
        is CapHandsFreeResult -> if (zh) "跟了 $breaths 口" else "$breaths breaths"
        is CapFocusLeft -> if (zh) "还剩 $min 分" else "$min min left"
        is CapFocusPaused -> if (zh) "停着 · $min 分" else "Paused $min min"
        CapFocusPlus -> if (zh) "专注 +1" else "Focus +1"
    }

    companion object {
        /** Every string the volume can show, worst cases included (two-digit counts, 50 min): the width test and the texture pre-warm use it. */
        fun all(): List<Hint> = buildList {
            for (p in Phase.entries) for (n in 1..8) add(Tag(p, n))
            add(TagPaused); add(CapIdle); add(CapReady); for (i in 0..2) add(CapCountdown(i))
            for (c in Correction.entries) if (c != Correction.NONE) add(CapCorrection(c))
            add(CapYourTurn); add(CapFocusPlus)
            add(CapCount(18, 18)); add(CapResult(17, 17)); add(CapHandsFreeResult(18))
            add(CapFocusLeft(50)); add(CapFocusPaused(50))
        }
    }
}
