package plymeld.core.calibrate

import plymeld.geom.Booleans
import plymeld.geom.Polygon

/**
 * A plain stroke font for text printed on a calibration plate as raised geometry: each glyph is a few straight strokes on a
 * grid 6 units tall, drawn as bars [stroke] wide with square ends and joined into polygons. Only what a plate's legend needs:
 * the digits, `.`, `%`, `°`, `=`, `/`, `m`, `s`, a space, and the plate's mark symbols (`|` a bar notch, `#` a square notch,
 * `v` a V notch, `u` a round-bottomed notch, `\` a slanted notch). Text printed this small is only legible with strokes of two lines (0.8 mm or more from a 0.4 mm nozzle) and
 * gaps the nozzle can leave open, so neighboring glyphs stand [GAP] units apart: at 5 mm glyphs and 0.8 mm strokes the
 * narrowest gap, between glyphs or inside one, is about 0.87 mm. A character not here is refused, so a legend never prints a
 * gap where a character was meant.
 */
object StrokeFont {
    private const val GRID_H = 6.0
    /** Grid units from one glyph's last stroke to the next one's first (stroke centers): the gap left open is this less a stroke. */
    private const val GAP = 2.0

    /** Strokes (polylines of grid points, x right, y up) and the width they span (grid units, from x = 0). */
    private class Glyph(val wide: Double, val strokes: List<DoubleArray>)

    private fun g(wide: Double, vararg strokes: DoubleArray) = Glyph(wide, strokes.toList())
    private fun p(vararg xy: Double) = xy

    private val glyphs: Map<Char, Glyph> = mapOf(
        '0' to g(4.0, p(0.0, 0.0, 4.0, 0.0, 4.0, 6.0, 0.0, 6.0, 0.0, 0.0)),
        '1' to g(4.0, p(2.0, 0.0, 2.0, 6.0), p(1.0, 5.0, 2.0, 6.0)),
        '2' to g(4.0, p(0.0, 6.0, 4.0, 6.0, 4.0, 3.0, 0.0, 3.0, 0.0, 0.0, 4.0, 0.0)),
        '3' to g(4.0, p(0.0, 6.0, 4.0, 6.0, 4.0, 0.0, 0.0, 0.0), p(1.0, 3.0, 4.0, 3.0)),
        '4' to g(4.0, p(0.0, 6.0, 0.0, 3.0, 4.0, 3.0), p(4.0, 6.0, 4.0, 0.0)),
        '5' to g(4.0, p(4.0, 6.0, 0.0, 6.0, 0.0, 3.0, 4.0, 3.0, 4.0, 0.0, 0.0, 0.0)),
        '6' to g(4.0, p(4.0, 6.0, 0.0, 6.0, 0.0, 0.0, 4.0, 0.0, 4.0, 3.0, 0.0, 3.0)),
        '7' to g(4.0, p(0.0, 6.0, 4.0, 6.0, 4.0, 0.0)),
        '8' to g(4.0, p(0.0, 0.0, 4.0, 0.0, 4.0, 6.0, 0.0, 6.0, 0.0, 0.0), p(0.0, 3.0, 4.0, 3.0)),
        '9' to g(4.0, p(4.0, 3.0, 0.0, 3.0, 0.0, 6.0, 4.0, 6.0, 4.0, 0.0, 0.0, 0.0)),
        '.' to g(0.0, p(0.0, 0.0, 0.0, 0.0)),
        // the dots in from the corners: at the corners they touched the glyphs either side
        '%' to g(4.0, p(0.0, 0.0, 4.0, 6.0), p(1.0, 5.0, 1.0, 5.0), p(3.0, 1.0, 3.0, 1.0)),
        '=' to g(4.0, p(0.0, 2.0, 4.0, 2.0), p(0.0, 4.0, 4.0, 4.0)),
        '/' to g(4.0, p(0.0, 0.0, 4.0, 6.0)),
        'm' to g(4.0, p(0.0, 0.0, 0.0, 4.0, 4.0, 4.0, 4.0, 0.0), p(2.0, 4.0, 2.0, 0.0)),
        's' to g(4.0, p(4.0, 4.0, 0.0, 4.0, 0.0, 2.0, 4.0, 2.0, 4.0, 0.0, 0.0, 0.0)),
        ' ' to g(1.0),
        '|' to g(0.0, p(0.0, 0.0, 0.0, 6.0)),
        '#' to g(3.0, p(0.0, 0.0, 3.0, 0.0, 3.0, 3.0, 0.0, 3.0, 0.0, 0.0)),
        'v' to g(3.0, p(0.0, 3.0, 1.5, 0.0, 3.0, 3.0)),
        // a round-bottomed notch and a slanted one, as the V: the notch's shape, half the glyph's height
        'u' to g(3.0, p(0.0, 3.0, 0.0, 1.5, 0.44, 0.44, 1.5, 0.0, 2.56, 0.44, 3.0, 1.5, 3.0, 3.0)),
        '\\' to g(3.0, p(0.0, 3.0, 3.0, 0.0)),
        '°' to g(2.0, p(0.0, 6.0, 2.0, 6.0, 2.0, 4.0, 0.0, 4.0, 0.0, 6.0)),
    )

    /** Whether every character of [text] is in the font. */
    fun covers(text: String) = text.all { it in glyphs }

    /** [text]'s width (µm, stroke center to stroke center) at a glyph height of [heightUm]. */
    fun width(text: String, heightUm: Long): Long {
        require(covers(text)) { "the stroke font has no '${text.first { it !in glyphs }}' (in \"$text\")" }
        val u = heightUm / GRID_H
        return Math.round((text.sumOf { glyphs.getValue(it).wide } + GAP * (text.length - 1)) * u)
    }

    /**
     * [text] as polygons (µm, the union of its strokes), its baseline's left end at ([x0], [y0]), glyphs [heightUm] tall
     * (the grid's 6 units), strokes [strokeUm] wide with square ends.
     */
    fun polygons(text: String, x0: Long, y0: Long, heightUm: Long, strokeUm: Long): List<Polygon> {
        require(covers(text)) { "the stroke font has no '${text.first { it !in glyphs }}' (in \"$text\")" }
        val u = heightUm / GRID_H
        val h = strokeUm / 2.0
        val bars = ArrayList<Polygon>()
        var x = x0.toDouble()
        for (c in text) {
            val gl = glyphs.getValue(c)
            for (s in gl.strokes) {
                val n = s.size / 2
                if (n == 1) { bars += bar(x + s[0] * u, y0 + s[1] * u, x + s[0] * u, y0 + s[1] * u, h); continue }
                for (i in 0 until n - 1) bars += bar(x + s[2 * i] * u, y0 + s[2 * i + 1] * u, x + s[2 * i + 2] * u, y0 + s[2 * i + 3] * u, h)
            }
            x += (gl.wide + GAP) * u
        }
        return if (bars.isEmpty()) emptyList() else Booleans.unionPositive(bars)
    }

    /** A bar from (ax, ay) to (bx, by), [h] either side of the line and past each end (a square around a point). */
    private fun bar(ax: Double, ay: Double, bx: Double, by: Double, h: Double): Polygon {
        val dx = bx - ax; val dy = by - ay; val len = Math.hypot(dx, dy)
        val ux = if (len > 0) dx / len else 1.0; val uy = if (len > 0) dy / len else 0.0
        val nx = -uy * h; val ny = ux * h
        val sx = ax - ux * h; val sy = ay - uy * h; val ex = bx + ux * h; val ey = by + uy * h
        fun r(v: Double) = Math.round(v)
        return Polygon(longArrayOf(r(sx - nx), r(sy - ny), r(ex - nx), r(ey - ny), r(ex + nx), r(ey + ny), r(sx + nx), r(sy + ny)))
    }
}
