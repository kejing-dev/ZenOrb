package io.embodit.zenorb.ui.orb

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import io.embodit.zenorb.domain.Geo

/**
 * The words beside the orb drawn into textures (design orbHints: light text #F6F1E7 with a solid dark outline ~16 % of
 * the text height at 0.85 opacity and a soft shadow, no plate). Text height = 0.70 of the quad height (0.018 m of 0.026).
 * Image up = quad +Y (F-test: `debug testf`).
 */
internal object HintTex {
    const val PX_PER_M = 8000f
    val FILL = 0xFFF6F1E7.toInt()
    val OUTLINE = 0xD91C1814.toInt()

    fun draw(text: String, wM: Float, hM: Float, left: Boolean): Bitmap {
        val w = (wM * PX_PER_M).toInt(); val h = (hM * PX_PER_M).toInt()
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        if (text.isEmpty()) return bmp
        val c = Canvas(bmp)
        val size = h * (Geo.TEXT_H / Geo.CAP_H)
        val tf = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        val x = if (left) h * 0.12f else w / 2f
        val align = if (left) Paint.Align.LEFT else Paint.Align.CENTER
        val base = h / 2f + size * 0.36f
        val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = tf; textSize = size; textAlign = align; color = 0x80000000.toInt()
            style = Paint.Style.STROKE; strokeWidth = size * 0.30f; strokeJoin = Paint.Join.ROUND
            maskFilter = BlurMaskFilter(size * 0.10f, BlurMaskFilter.Blur.NORMAL)
        }
        c.drawText(text, x, base + size * 0.04f, shadow)
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = tf; textSize = size; textAlign = align; color = OUTLINE
            style = Paint.Style.STROKE; strokeWidth = size * 0.23f; strokeJoin = Paint.Join.ROUND
        }
        c.drawText(text, x, base, stroke)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = tf; textSize = size; textAlign = align; color = FILL }
        c.drawText(text, x, base, fill)
        return bmp
    }

    /** Measured text width in metres at the design text height (the JVM width test uses the design's estimate instead). */
    fun widthM(text: String): Float {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); textSize = Geo.TEXT_H * PX_PER_M }
        return p.measureText(text) / PX_PER_M
    }
}
