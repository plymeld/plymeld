package plymeld.check.render

import java.awt.Color
import java.util.Locale

/** One entry of a picture's color key: a swatch (or a gradient between two labels, or a mark) and its words. */
internal sealed class KeyItem {
    class Swatch(val color: Color, val label: String) : KeyItem()
    class Gradient(val lo: String, val hi: String) : KeyItem()
    class Mark(val draw: (Canvas, Double, Double) -> Unit, val label: String) : KeyItem()
}

/**
 * A drawing with what it needs to be read: [caption] (its first line in bold) says exactly what was drawn, [key] its
 * colors and marks, [barMm] / [pxPerMm] its scale bar, and [vBarMm] / [vPxPerMm] a vertical one where heights are
 * stretched. [draw] paints the drawing at an offset, [drawW] x [drawH] pixels.
 */
internal class Panel(
    val drawW: Int, val drawH: Int,
    val caption: List<String>, val key: List<KeyItem>,
    val pxPerMm: Double, val barMm: Double,
    val vPxPerMm: Double = Double.NaN, val vBarMm: Double = Double.NaN,
    /** Its frame and scale, for another file's picture to share (--compare-with); null where it has none to share. */
    val fit: Fit? = null,
    val draw: (Canvas, Double, Double) -> Unit,
)

/** Lays panels out as one picture: side by side, each with its caption above and its scale bar and key below. */
internal object Layout {
    const val M = 12
    private const val CAP = 18
    private const val KEY = 18
    private const val BAR = 26
    /** The narrowest a captioned panel is, so its words have room (plymeld's choice). */
    const val MIN_W = 520
    val BACKGROUND = Color(0xF7, 0xF7, 0xF7)
    val GROUND = Color(0xEC, 0xEC, 0xEC)

    private class Placed(val p: Panel, val w: Int, val caption: List<Pair<String, Boolean>>, val keyRows: List<List<KeyItem>>, val keyW: Map<KeyItem, Int>)

    private fun keyWidth(k: KeyItem): Int = when (k) {
        is KeyItem.Swatch -> 16 + Canvas.textWidth(k.label, 12) + 14
        is KeyItem.Mark -> 16 + Canvas.textWidth(k.label, 12) + 14
        is KeyItem.Gradient -> Canvas.textWidth(k.lo, 12) + 6 + 120 + 6 + Canvas.textWidth(k.hi, 12) + 14
    }

    private fun place(p: Panel): Placed {
        val w = maxOf(p.drawW, MIN_W)
        val cap = ArrayList<Pair<String, Boolean>>()
        for ((i, line) in p.caption.withIndex()) for (s in Canvas.wrap(if (i == 0) line else line.replaceFirstChar { it.uppercaseChar() }, w, if (i == 0) 14 else 13, i == 0)) cap += s to (i == 0)
        val widths = p.key.associateWith(::keyWidth)
        val rows = ArrayList<List<KeyItem>>(); var row = ArrayList<KeyItem>(); var used = 0
        for (k in p.key) { val kw = widths.getValue(k); if (row.isNotEmpty() && used + kw > w) { rows += row; row = ArrayList(); used = 0 }; row += k; used += kw }
        if (row.isNotEmpty()) rows += row
        return Placed(p, w, cap, rows, widths)
    }

    fun size(panels: List<Panel>, annotate: Boolean): Pair<Int, Int> {
        if (!annotate) return panels.sumOf { it.drawW } + (panels.size - 1) * M to panels.maxOf { it.drawH }
        val ps = panels.map(::place)
        val capH = ps.maxOf { it.caption.size } * CAP + 6
        val keyH = ps.maxOf { it.keyRows.size } * KEY
        return ps.sumOf { it.w } + (ps.size + 1) * M to M + capH + ps.maxOf { it.p.drawH } + BAR + keyH + M
    }

    /** Draws [panels] on [c] at (ox, oy) (a sheet places pictures this way too). */
    fun draw(c: Canvas, panels: List<Panel>, annotate: Boolean, ox: Double = 0.0, oy: Double = 0.0) {
        val (w, h) = size(panels, annotate)
        c.rect(ox, oy, w.toDouble(), h.toDouble(), BACKGROUND)
        if (!annotate) {
            var x = ox
            for (p in panels) { c.rect(x, oy, p.drawW.toDouble(), p.drawH.toDouble(), GROUND); c.clip(x, oy, p.drawW.toDouble(), p.drawH.toDouble()); p.draw(c, x, oy); c.unclip(); x += p.drawW + M }
            return
        }
        val ps = panels.map(::place)
        val capH = ps.maxOf { it.caption.size } * CAP + 6
        val drawH = ps.maxOf { it.p.drawH }
        var x = ox + M
        for (pl in ps) {
            val p = pl.p
            for ((i, line) in pl.caption.withIndex()) c.text(line.first, x, oy + M + 13 + i * CAP, if (line.second) 14 else 13, line.second)
            val dx = x + (pl.w - p.drawW) / 2.0; val dy = oy + M + capH
            c.rect(dx, dy, p.drawW.toDouble(), p.drawH.toDouble(), GROUND)
            c.clip(dx, dy, p.drawW.toDouble(), p.drawH.toDouble())
            p.draw(c, dx, dy)
            c.unclip()
            c.rectOutline(dx - 0.5, dy - 0.5, p.drawW + 1.0, p.drawH + 1.0, Color(0xB4, 0xB4, 0xB4), 1.0)
            // the scale bar
            val by = oy + M + capH + drawH + 17.0
            val len = p.barMm * p.pxPerMm
            c.line(dx, by, dx + len, by, 2.0, Color.BLACK, false)
            c.line(dx, by - 4, dx, by + 4, 1.0, Color.BLACK, false); c.line(dx + len, by - 4, dx + len, by + 4, 1.0, Color.BLACK, false)
            var label = "${mm(p.barMm)} mm"
            if (!p.vBarMm.isNaN()) label += "  (heights: ${mm(p.vBarMm)} mm = ${"%.0f".format(Locale.ROOT, p.vBarMm * p.vPxPerMm)} px)"
            c.text(label, dx + len + 6, by + 4, 12)
            // the key
            var ky = by + 9 + KEY - 4
            for (row in pl.keyRows) {
                var kx = x
                for (k in row) {
                    when (k) {
                        is KeyItem.Swatch -> { c.rect(kx, ky - 11, 12.0, 12.0, k.color); c.rectOutline(kx, ky - 11, 12.0, 12.0, Color(0x80, 0x80, 0x80), 0.5); c.text(k.label, kx + 16, ky, 12) }
                        is KeyItem.Mark -> { k.draw(c, kx + 6, ky - 5); c.text(k.label, kx + 16, ky, 12) }
                        is KeyItem.Gradient -> {
                            c.text(k.lo, kx, ky, 12); var gx = kx + Canvas.textWidth(k.lo, 12) + 6
                            for (i in 0 until 120) c.rect(gx + i, ky - 11, 1.0, 12.0, Palette.ramp(i / 119.0))
                            gx += 126; c.text(k.hi, gx, ky, 12)
                        }
                    }
                    kx += pl.keyW.getValue(k)
                }
                ky += KEY
            }
            x += pl.w + M
        }
    }

    fun mm(v: Double): String = plymeld.check.TechniqueRender.fmt(v)
}

/** The colors: each feature's (shared with the techniques' sample pictures), the tools', and the gradient. */
internal object Palette {
    fun ramp(t: Double): Color = plymeld.check.TechniqueRender.ramp(t)
    val CONTEXT = Color(0xC4, 0xC4, 0xC4)
    val BELOW = Color(0xD6, 0xD6, 0xD6)
    val UNNAMED = Color(0x5A, 0x5A, 0x5A)

    /** A feature's color by its name; one the shared table does not know gets the next of a fixed list, by its place among the file's names. */
    fun feature(name: String?, id: Int, unknownRank: (Int) -> Int): Color {
        if (name == null) return UNNAMED
        plymeld.check.GcodeRender.colors[name]?.let { return it }
        plymeld.check.TechniqueRender.EXTRA[name]?.let { return it }
        val f = plymeld.check.TechniqueRender.FALLBACK
        return f[Math.floorMod(unknownRank(id), f.size)]
    }

    fun tool(t: Int, stated: Map<Int, String>): Color =
        stated[t]?.let { rgb(it) } ?: plymeld.check.TechniqueRender.TOOL_COLORS[Math.floorMod(t, plymeld.check.TechniqueRender.TOOL_COLORS.size)]

    fun rgb(hex: String): Color? = Regex("#?([0-9A-Fa-f]{6})").matchEntire(hex.trim())?.let { Color(it.groupValues[1].toInt(16)) }

    fun darker(c: Color, k: Double = 0.62): Color = Color((c.red * k).toInt(), (c.green * k).toInt(), (c.blue * k).toInt())
}
