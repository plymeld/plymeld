package plymeld.check.render

import java.awt.BasicStroke
import java.awt.Color
import java.awt.RenderingHints
import java.awt.geom.Line2D
import java.awt.image.BufferedImage

/**
 * A layer drawn from above: its chosen lines in print order (each as wide as the run draws them, its edges darker so
 * neighbors read apart), the lines not chosen in gray where --others says so, the layers below in a lighter gray, and the
 * marks --show asks for. Where the lines cannot be resolved ([asLines] false), each feature's area is a flat region with a
 * darker outline instead.
 */
internal class TopDrawing(
    private val ctx: Context, private val m: MoveSet, private val marks: MarkSet, private val layer: Int,
    private val f: Frame, private val s: Double, private val asLines: Boolean,
) {
    private val req = ctx.req
    private val main = ArrayList<Int>()
    private val faint = ArrayList<Int>()
    private val below = ArrayList<Int>()
    val empty: Boolean
    private var lo = Double.NaN
    private var hi = Double.NaN
    private val orderOf = HashMap<Int, Int>()
    /** Each extruding run's first move and its last (consecutive chosen moves of one feature, joined end to end). */
    private val runs = ArrayList<IntArray>()

    init {
        for (i in 0 until m.n) {
            if (m.kind[i] != MoveSet.EXTRUDE) continue
            val ch = ctx.chosen(m, i)
            when {
                m.layer[i] == layer && ch -> main += i
                m.layer[i] == layer && req.others == Others.FAINT -> faint += i
                m.layer[i] < layer && ch -> below += i
            }
        }
        empty = main.isEmpty()
        for ((k, i) in main.withIndex()) orderOf[i] = k
        if (ctx.colorBy != ColorBy.FEATURE && ctx.colorBy != ColorBy.TOOL) for (i in main) {
            val v = ctx.value(m, i, orderOf.getValue(i))
            if (v.isNaN()) continue
            if (lo.isNaN() || v < lo) lo = v
            if (hi.isNaN() || v > hi) hi = v
        }
        var start = -1; var prev = -1
        for (i in main) {
            val joined = prev >= 0 && i == prev + 1 && m.feature[i] == m.feature[prev] && Math.abs(m.x0[i] - m.x1[prev]) < 1e-6 && Math.abs(m.y0[i] - m.y1[prev]) < 1e-6
            if (!joined) { if (start >= 0) runs += intArrayOf(start, prev); start = i }
            prev = i
        }
        if (start >= 0) runs += intArrayOf(start, prev)
    }

    fun color(i: Int): Color = when (ctx.colorBy) {
        ColorBy.FEATURE -> if (ctx.index.namesFeatures) ctx.featureColor(m.feature[i]) else Palette.UNNAMED
        ColorBy.TOOL -> ctx.toolColor(m.tool[i])
        else -> { val v = ctx.value(m, i, orderOf[i] ?: 0); if (v.isNaN()) Palette.UNNAMED else Palette.ramp(if (hi > lo) (v - lo) / (hi - lo) else 0.5) }
    }

    private fun px(x: Double, ox: Double) = ox + (x - f.x0) * s
    private fun py(y: Double, oy: Double) = oy + (f.y1 - y) * s
    private fun visible(i: Int, margin: Double) =
        !(maxOf(m.x0[i], m.x1[i]) < f.x0 - margin || minOf(m.x0[i], m.x1[i]) > f.x1 + margin || maxOf(m.y0[i], m.y1[i]) < f.y0 - margin || minOf(m.y0[i], m.y1[i]) > f.y1 + margin)
    private fun widthPx(i: Int): Double { val w = ctx.drawWidth(m, i); return if (w.isNaN() || w <= 0) 1.0 else maxOf(1.0, w * s) }

    fun draw(c: Canvas, ox: Double, oy: Double, dw: Int, dh: Int) {
        if (asLines) {
            strokes(c, below, ox, oy) { Palette.BELOW }
            strokes(c, faint, ox, oy) { Palette.CONTEXT }
            strokes(c, main, ox, oy, ::color)
        } else regions(c, ox, oy, dw, dh)
        drawMarks(c, ox, oy)
    }

    /**
     * Draws [ids] in print order, a stroke at a time: consecutive moves joined end to end and of one color, turning no
     * sharper than 60 degrees, are one stroke, all its moves' darker edges drawn first and their bodies over them, so the
     * edge runs round the stroke as a whole (a line whose width changes from move to move reads as one line), while a
     * fill's pass laid beside the last, a stroke of its own, shows its edge against it.
     */
    private fun strokes(c: Canvas, ids: List<Int>, ox: Double, oy: Double, colorOf: (Int) -> Color) {
        val edged = ctx.lineWidth != LineWidth.CENTER
        var k = 0
        while (k < ids.size) {
            val col = colorOf(ids[k])
            var e = k + 1
            while (e < ids.size) {
                val i = ids[e]; val p = ids[e - 1]
                if (i != p + 1 || Math.abs(m.x0[i] - m.x1[p]) > 1e-6 || Math.abs(m.y0[i] - m.y1[p]) > 1e-6 || colorOf(i) != col) break
                val ax = m.x1[p] - m.x0[p]; val ay = m.y1[p] - m.y0[p]; val bx = m.x1[i] - m.x0[i]; val by = m.y1[i] - m.y0[i]
                val la = Math.hypot(ax, ay); val lb = Math.hypot(bx, by)
                if (la > 0 && lb > 0 && (ax * bx + ay * by) / (la * lb) < 0.5) break
                e++
            }
            if (ids.subList(k, e).any { visible(it, 1.0) }) {
                // the stroke's pieces of nearly one width, each one polyline (smooth where a curve is many short moves)
                val pieces = ArrayList<IntRange>()
                var a = k
                for (j in k + 1..e) if (j == e || Math.abs(widthPx(ids[j]) - widthPx(ids[a])) > 0.25 + 0.02 * widthPx(ids[a])) { pieces += a until j; a = j }
                fun poly(r: IntRange, w: Double, col2: Color) {
                    val xs = DoubleArray(r.count() + 1); val ys = DoubleArray(r.count() + 1)
                    xs[0] = px(m.x0[ids[r.first]], ox); ys[0] = py(m.y0[ids[r.first]], oy)
                    for (j in r) { xs[j - r.first + 1] = px(m.x1[ids[j]], ox); ys[j - r.first + 1] = py(m.y1[ids[j]], oy) }
                    c.polyline(xs, ys, w, col2)
                }
                val dark = Palette.darker(col)
                if (edged) for (r in pieces) { val w = widthPx(ids[r.first]); if (w >= 2.5) poly(r, w, dark) }
                for (r in pieces) { val w = widthPx(ids[r.first]); poly(r, if (edged && w >= 2.5) w - 1.0 else w, col) }
            }
            k = e
        }
    }

    /** Each feature's area as a flat region: the beads at their true width (at least a pixel), the region's edge darker. */
    private fun regions(c: Canvas, ox: Double, oy: Double, dw: Int, dh: Int) {
        val img = BufferedImage(dw, dh, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF)
        fun bead(i: Int, col: Color) {
            if (!visible(i, 1.0)) return
            g.color = col
            // a pixel and a half wider than the bead: neighbors closer than the pixels can show close up into one area,
            // not a pattern of gaps
            g.stroke = BasicStroke((widthPx(i) + 1.5).toFloat(), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            g.draw(Line2D.Double(px(m.x0[i], 0.0), py(m.y0[i], 0.0), px(m.x1[i], 0.0), py(m.y1[i], 0.0)))
        }
        for (i in below) bead(i, Palette.BELOW)
        for (i in faint) bead(i, Palette.CONTEXT)
        for (i in main) bead(i, color(i))
        g.dispose()
        val a = IntArray(dw * dh); img.getRGB(0, 0, dw, dh, a, 0, dw)
        val out = a.copyOf()
        for (y in 0 until dh) for (x in 0 until dw) {
            val p = a[y * dw + x]
            if ((p ushr 24) == 0) continue
            val edge = x == 0 || y == 0 || x == dw - 1 || y == dh - 1 || a[y * dw + x - 1] != p || a[y * dw + x + 1] != p || a[(y - 1) * dw + x] != p || a[(y + 1) * dw + x] != p
            if (edge) out[y * dw + x] = (0xFF shl 24) or (Palette.darker(Color(p and 0xFFFFFF), 0.55).rgb and 0xFFFFFF)
        }
        c.image(out, dw, dh, Math.round(ox).toInt(), Math.round(oy).toInt())
    }

    private fun isOuterWall(feature: Int): Boolean {
        if (!ctx.index.namesFeatures) return true
        if (feature < 0) return false
        val n = ctx.index.features[feature].lowercase()
        return "outer wall" in n || "wall-outer" in n || "external perimeter" in n
    }

    private fun drawMarks(c: Canvas, ox: Double, oy: Double) {
        if (Show.TRAVELS in req.show) for (i in 0 until m.n) {
            if (m.kind[i] == MoveSet.EXTRUDE || m.layer[i] != layer || !ctx.chosenPlace(m.obj[i], m.tool[i]) || !visible(i, 1.0)) continue
            c.line(px(m.x0[i], ox), py(m.y0[i], oy), px(m.x1[i], ox), py(m.y1[i], oy), 1.0, TRAVEL)
        }
        if (Show.STARTS in req.show) for (r in runs) startMark(c, px(m.x0[r[0]], ox), py(m.y0[r[0]], oy))
        if (Show.SEAMS in req.show) for (r in runs) {
            val a = r[0]; val b = r[1]
            if (!isOuterWall(m.feature[a])) continue
            var len = 0.0; for (i in a..b) len += m.lengthXY(i)
            val w = ctx.beadWidth(m, a).takeIf { !it.isNaN() } ?: 0.4
            if (Math.hypot(m.x1[b] - m.x0[a], m.y1[b] - m.y0[a]) <= maxOf(w, 0.2) && len >= 4 * w) seamMark(c, px(m.x0[a], ox), py(m.y0[a], oy))
        }
        for (k in 0 until marks.n) {
            if (marks.layer[k] != layer || !ctx.chosenPlace(marks.obj[k], marks.tool[k])) continue
            val x = px(marks.x[k], ox); val y = py(marks.y[k], oy)
            if (marks.kind[k] == MarkSet.RETRACT && Show.RETRACTIONS in req.show) retractMark(c, x, y)
            if (marks.kind[k] == MarkSet.TOOL_CHANGE && Show.TOOL_CHANGES in req.show) { toolMark(c, x, y); c.text("T${marks.tool[k]}", x + 7, y - 6, 12, true, TOOL) }
        }
    }

    fun extraWords(): String {
        val w = StringBuilder()
        w.append(ctx.onlyWords())
        if (below.isNotEmpty() || (req.layersBelow ?: 0) > 0) w.append("; the ${req.layersBelow} layer(s) below in light gray")
        if (req.show.isNotEmpty()) w.append("; marked: " + req.show.joinToString(", ") { markWords(it) })
        return w.toString()
    }

    private fun markWords(sh: Show) = when (sh) {
        Show.TRAVELS -> "travels (thin dark lines)"
        Show.SEAMS -> if (ctx.index.namesFeatures) "seams (where each closed outer-wall loop starts)" else "seams (where each closed loop starts: the file names no features)"
        Show.RETRACTIONS -> "retractions"
        Show.STARTS -> "starts (where each line begins after a travel)"
        Show.TOOL_CHANGES -> "tool changes"
    }

    fun key(): List<KeyItem> {
        val k = ArrayList<KeyItem>()
        val seen = main.filter { visible(it, 0.0) }
        when (ctx.colorBy) {
            ColorBy.FEATURE -> if (ctx.index.namesFeatures) seen.map { m.feature[it] }.distinct().sorted().forEach {
                k += KeyItem.Swatch(ctx.featureColor(it), if (it < 0) "(no feature named)" else ctx.index.features[it]) }
            ColorBy.TOOL -> seen.map { m.tool[it] }.distinct().sorted().forEach { k += KeyItem.Swatch(ctx.toolColor(it), "T$it") }
            ColorBy.ORDER -> if (!lo.isNaN()) k += KeyItem.Gradient("printed first", "printed last")
            else -> if (!lo.isNaN()) { val u = ctx.unit(); k += KeyItem.Gradient("${num(lo)} $u", "${num(hi)} $u") }
                else k += KeyItem.Swatch(Palette.UNNAMED, "the file does not say")
        }
        if (faint.isNotEmpty()) k += KeyItem.Swatch(Palette.CONTEXT, "other lines")
        if (below.isNotEmpty()) k += KeyItem.Swatch(Palette.BELOW, "layers below")
        for (sh in req.show) k += when (sh) {
            Show.TRAVELS -> KeyItem.Mark({ c, x, y -> c.line(x - 6, y, x + 6, y, 1.0, TRAVEL) }, "travel")
            Show.SEAMS -> KeyItem.Mark(::seamMark, "seam")
            Show.RETRACTIONS -> KeyItem.Mark(::retractMark, "retraction")
            Show.STARTS -> KeyItem.Mark(::startMark, "start")
            Show.TOOL_CHANGES -> KeyItem.Mark(::toolMark, "tool change")
        }
        return k
    }

    private fun num(v: Double): String = if (Math.abs(v) >= 100) Render.f(v, 0) else if (Math.abs(v) >= 1) Render.f(v, 1) else Render.f(v, 3)

    companion object {
        val TRAVEL = Color(0x40, 0x40, 0x40)
        val TOOL = Color(0x6A, 0x1B, 0x9A)
        fun seamMark(c: Canvas, x: Double, y: Double) { c.circle(x, y, 4.5, null, Color.BLACK, 1.6); c.circle(x, y, 1.5, Color.BLACK) }
        fun retractMark(c: Canvas, x: Double, y: Double) { c.circle(x, y, 3.5, null, Color(0xD0, 0x10, 0x10), 1.4) }
        fun startMark(c: Canvas, x: Double, y: Double) { c.circle(x, y, 2.8, Color(0x10, 0x90, 0x30), Color.WHITE, 0.8) }
        fun toolMark(c: Canvas, x: Double, y: Double) { c.polygon(doubleArrayOf(x, x + 5, x, x - 5), doubleArrayOf(y - 5, y, y + 5, y), TOOL, Color.WHITE, 0.8) }
    }
}
