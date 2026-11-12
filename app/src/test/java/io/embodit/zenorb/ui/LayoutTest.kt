package io.embodit.zenorb.ui

import io.embodit.zenorb.domain.Geo
import io.embodit.zenorb.domain.Hint
import io.embodit.zenorb.domain.Style
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Design containmentCheck / occlusionCheck / hitTargets / hintStrings._widths (M2, M3, D17, D35, D45).
 * Cradle sizes = tools/build_models.py output (bounds printed when the GLBs are built).
 */
class LayoutTest {
    data class Box(val x0: Float, val y0: Float, val z0: Float, val x1: Float, val y1: Float, val z1: Float)

    private val cradle = mapOf(
        Style.GLAZE to Box(-0.0675f, 0f, -0.0675f, 0.0675f, 0.032f, 0.0675f),
        Style.BRASS to Box(-0.070f, 0f, -0.070f, 0.070f, 0.0359f, 0.070f),
        Style.MOSS to Box(-0.078f, 0f, -0.078f, 0.078f, 0.0247f, 0.078f),
    )
    private val MOSS_DISP = 0.0025f

    private fun boxes(style: Style, f: Float, fStar: Float): Map<String, Box> {
        val r = Geo.radius(f) + if (style == Style.MOSS) MOSS_DISP * Geo.scale(f) else 0f
        val y = Geo.orbY(f)
        val rr = Geo.ringR(fStar) + 0.0035f
        val ry = Geo.orbY(fStar)
        val tx = Geo.radius(f) + Geo.TAG_GAP
        val tilt = Math.toRadians(Geo.CAP_TILT_DEG.toDouble())
        val dz = (Geo.CAP_H / 2 * sin(tilt)).toFloat(); val dy = (Geo.CAP_H / 2 * cos(tilt)).toFloat()
        val arc = style.rimR + 0.0018f
        return mapOf(
            "cradle" to cradle.getValue(style),
            "shadow" to Box(-Geo.SHADOW_R, 0f, -Geo.SHADOW_R, Geo.SHADOW_R, 0.001f, Geo.SHADOW_R),
            "orb" to Box(-r, y - r + 0.010f * 0f, -r, r, y + r + 0.010f, r),     // + 1 cm focus-end bump
            "ring" to Box(-rr, ry - 0.0035f, -rr, rr, ry + 0.0035f, rr),
            "tag" to Box(tx, y - Geo.TAG_H / 2, -0.001f, tx + Geo.TAG_W, y + Geo.TAG_H / 2, 0.001f),
            "caption" to Box(-Geo.CAP_W / 2, Geo.CAP_Y - dy, Geo.CAP_Z - dz, Geo.CAP_W / 2, Geo.CAP_Y + dy, Geo.CAP_Z + dz),
            "arc" to Box(-arc, style.rimY - 0.0018f, -arc, arc, style.rimY + 0.0018f, arc),
        )
    }

    @Test fun everythingStaysInsideTheVolume() {
        for (st in Style.entries) for (i in 0..20) {
            val f = i / 20f
            for ((name, b) in boxes(st, f, f)) {
                val ok = b.x0 >= -Geo.BOX_X && b.x1 <= Geo.BOX_X && b.y0 >= 0f && b.y1 <= Geo.BOX_Y && b.z0 >= -Geo.BOX_Z && b.z1 <= Geo.BOX_Z
                assertTrue("$st f=$f $name $b", ok)
            }
        }
        val top = boxes(Style.GLAZE, 1f, 1f)
        println("LAYOUT tag right edge %.3f (box 0.160), orb top %.3f (box 0.300), caption front %.3f (box 0.130)".format(top["tag"]!!.x1, top["orb"]!!.y1, top["caption"]!!.z1))
    }

    /** Ray [e] -> [p] hits a sphere (centre c, radius r) strictly before reaching p? */
    private fun hitsSphere(e: FloatArray, p: FloatArray, c: FloatArray, r: Float): Boolean {
        val d = FloatArray(3) { p[it] - e[it] }
        val len = sqrt(d.sumOf { (it * it).toDouble() }).toFloat()
        for (k in 0..2) d[k] /= len
        val oc = FloatArray(3) { e[it] - c[it] }
        val b = oc.indices.sumOf { (oc[it] * d[it]).toDouble() }.toFloat()
        val cc = oc.sumOf { (it * it).toDouble() }.toFloat() - r * r
        val disc = b * b - cc
        if (disc < 0) return false
        val t = -b - sqrt(disc)
        return t > 0 && t < len - 1e-4f
    }

    /** Ray hits the cradle's bounding cylinder (radius r, 0..h) before p? Sampled. */
    private fun hitsCylinder(e: FloatArray, p: FloatArray, r: Float, h: Float): Boolean {
        for (i in 1 until 400) {
            val s = i / 400f
            val x = e[0] + (p[0] - e[0]) * s; val y = e[1] + (p[1] - e[1]) * s; val z = e[2] + (p[2] - e[2]) * s
            if (y in 0f..h && sqrt(x * x + z * z) <= r) return true
        }
        return false
    }

    @Test fun wordsAreNotHiddenByTheOrbOrTheCradle() {
        // eyes in content-root metres: default system pose (1.75 m, ~4 deg down to the volume centre) and seated at a desk
        val eyes = mapOf("default 1.75 m" to floatArrayOf(0f, 0.15f + 1.75f * 0.07f, 1.75f), "seated 0.7 m" to floatArrayOf(0f, 0.35f, 0.7f))
        for (st in Style.entries) for ((eyeName, eye) in eyes) for (f in listOf(0f, 0.5f, 1f)) {
            val b = boxes(st, f, f)
            val corners = ArrayList<Pair<String, FloatArray>>()
            b["tag"]!!.let { t -> for (x in listOf(t.x0, t.x1)) for (y in listOf(t.y0, t.y1)) corners += "tag" to floatArrayOf(x, y, 0f) }
            b["caption"]!!.let { c -> for (x in listOf(c.x0, c.x1)) for ((y, z) in listOf(c.y0 to c.z1, c.y1 to c.z0)) corners += "caption" to floatArrayOf(x, y, z) }
            val orbC = floatArrayOf(0f, Geo.orbY(f), 0f)
            val cr = cradle.getValue(st)
            for ((what, p) in corners) {
                assertTrue("$st $eyeName f=$f $what orb", !hitsSphere(eye, p, orbC, Geo.radius(f)))
                assertTrue("$st $eyeName f=$f $what cradle", !hitsCylinder(eye, p, cr.x1, cr.y1))
            }
        }
    }

    @Test fun theOrbIsABigEnoughTarget() {
        // D17 (2026-11-11: >= 2 deg at the system's ~1.75 m)
        val deg = Math.toDegrees(2 * atan(Geo.HIT_R / 1.75)).toFloat()
        println("HIT orb collider %.1f deg at 1.75 m".format(deg))
        assertTrue(deg >= 2f)
    }

    /** Design hintStrings._widths: Chinese glyph = text height, Latin / digits 0.6 x text height. */
    private fun estimate(s: String): Float = s.sumOf { c -> (if (c.code > 0x2E80) Geo.TEXT_H else 0.6f * Geo.TEXT_H).toDouble() }.toFloat()

    @Test fun hintWordsFitWithoutShrinking() {
        for (h in Hint.all()) for (zh in listOf(true, false)) {
            val s = h.text(zh)
            val isTag = h is Hint.Tag || h == Hint.TagPaused
            val max = if (isTag) 0.070f else 0.160f
            assertTrue("\"$s\" ${estimate(s)} > $max", estimate(s) <= max)
        }
    }
}
