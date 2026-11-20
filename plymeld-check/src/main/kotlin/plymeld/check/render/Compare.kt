package plymeld.check.render

import java.awt.Color

/**
 * `--compare-with`: the third panel of a picture, where the two files' drawings differ. Both are drawn in one frame at one
 * scale, then compared pixel by pixel: drawn in the first only (orange), in the second only (blue), in both but not alike
 * (red), and alike (light gray, the drawing's shape kept for orientation).
 */
internal object Compare {
    /** Two pixels are alike when no channel differs by more than this (plymeld's choice: the smoothing of an edge drawn alike). */
    const val TOLERANCE = 24
    val ONLY_A = Color(0xE0, 0x70, 0x00)
    val ONLY_B = Color(0x20, 0x60, 0xD0)
    val BOTH = Color(0xD0, 0x10, 0x10)
    val SAME = Color(0xCC, 0xCC, 0xCC)

    /** The drawing of [p] alone, as pixels on the ground color. */
    fun pixels(p: Panel): IntArray {
        val r = Raster(p.drawW, p.drawH)
        r.rect(0.0, 0.0, p.drawW.toDouble(), p.drawH.toDouble(), Layout.GROUND)
        r.clip(0.0, 0.0, p.drawW.toDouble(), p.drawH.toDouble())
        p.draw(r, 0.0, 0.0)
        r.unclip()
        val a = IntArray(p.drawW * p.drawH)
        r.img.getRGB(0, 0, p.drawW, p.drawH, a, 0, p.drawW)
        return a
    }

    fun difference(a: Panel, b: Panel, nameA: String, nameB: String): Panel {
        require(a.drawW == b.drawW && a.drawH == b.drawH) { "the two files' pictures came out at different sizes (${a.drawW} x ${a.drawH}, ${b.drawW} x ${b.drawH})" }
        val pa = pixels(a); val pb = pixels(b)
        val g = Layout.GROUND.rgb and 0xFFFFFF
        val out = IntArray(pa.size)
        var onlyA = 0; var onlyB = 0; var both = 0
        for (i in pa.indices) {
            val x = pa[i] and 0xFFFFFF; val y = pb[i] and 0xFFFFFF
            val inA = x != g; val inB = y != g
            val alike = (0..2).all { s -> Math.abs(((x shr (8 * s)) and 0xFF) - ((y shr (8 * s)) and 0xFF)) <= TOLERANCE }
            out[i] = (0xFF shl 24) or when {
                alike -> if (inA) SAME.rgb and 0xFFFFFF else g
                inA && !inB -> { onlyA++; ONLY_A.rgb and 0xFFFFFF }
                inB && !inA -> { onlyB++; ONLY_B.rgb and 0xFFFFFF }
                else -> { both++; BOTH.rgb and 0xFFFFFF }
            }
        }
        val caption = listOf("Where $nameA and $nameB differ",
            "Pixel by pixel, both drawn in one frame at one scale: $onlyA px drawn only in $nameA, $onlyB only in $nameB, $both in both but not alike" +
                (if (onlyA + onlyB + both == 0) " (the two draw alike)" else "") + ".",
            "Alike: no color channel more than $TOLERANCE apart.")
        val key = listOf(KeyItem.Swatch(ONLY_A, "only in $nameA"), KeyItem.Swatch(ONLY_B, "only in $nameB"), KeyItem.Swatch(BOTH, "in both, not alike"), KeyItem.Swatch(SAME, "alike"))
        return Panel(a.drawW, a.drawH, caption, key, a.pxPerMm, a.barMm, a.vPxPerMm, a.vBarMm, a.fit,
            draw = { c, ox, oy -> c.image(out, a.drawW, a.drawH, Math.round(ox).toInt(), Math.round(oy).toInt()) })
    }
}
