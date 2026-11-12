package io.embodit.zenorb.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.pow

/** D13: every text colour on its background (window glass measured on the emulator, cards, buttons, the 3D words). */
class LookTest {
    private fun lum(c: Long): Double {
        fun ch(v: Long): Double { val s = v / 255.0; return if (s <= 0.03928) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4) }
        return 0.2126 * ch((c shr 16) and 255) + 0.7152 * ch((c shr 8) and 255) + 0.0722 * ch(c and 255)
    }

    private fun ratio(a: Long, b: Long): Double { val x = lum(a); val y = lum(b); return (max(x, y) + 0.05) / (minOf(x, y) + 0.05) }

    @Test fun textColoursPassOnTheirBackgrounds() {
        val ink = 0x1B1F23L; val ink2 = 0x3A3F44L; val primary = 0x2F3B36L; val card = 0xEFE6D6L; val glassDark = 0xB4B3B1L
        val pairs = mapOf(
            "ink on status card" to ratio(ink, card), "ink2 on status card" to ratio(ink2, card),
            "white on primary button / selected segment" to ratio(0xFFFFFFL, primary), "ink on darkest glass" to ratio(ink, glassDark),
            "ink2 on darkest glass" to ratio(ink2, glassDark), "white on danger" to ratio(0xFFFFFFL, 0xB3261EL),
            "3D words: fill on their outline" to ratio(0xF6F1E7L, 0x1C1814L),
        )
        for ((k, v) in pairs) { println("CONTRAST %-44s %.2f:1".format(k, v)); assertTrue("$k $v", v >= 4.5) }
    }
}
