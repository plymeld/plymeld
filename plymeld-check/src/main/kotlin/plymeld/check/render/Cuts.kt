package plymeld.check.render

import java.awt.BasicStroke
import java.awt.Color
import java.awt.RenderingHints
import java.awt.geom.Path2D
import java.awt.image.BufferedImage
import java.util.Locale

/**
 * A plane through the print, seen from the side its direction [n] points to: [u] runs across the picture (to the
 * viewer's right) and [v] up it. Its up is the world's Z where the plane is not level (a vertical cut: height up), the
 * bed's +Y where it is.
 */
internal class Plane(val px: Double, val py: Double, val pz: Double, nx0: Double, ny0: Double, nz0: Double) {
    val nx: Double; val ny: Double; val nz: Double
    val ux: Double; val uy: Double; val uz: Double
    val vx: Double; val vy: Double; val vz: Double
    init {
        val l = Math.sqrt(nx0 * nx0 + ny0 * ny0 + nz0 * nz0)
        require(l > 1e-12) { "--cut-plane: its direction is (0, 0, 0), which faces nowhere" }
        nx = nx0 / l; ny = ny0 / l; nz = nz0 / l
        // up: Z with its part along n taken out; where the plane is level, +Y
        var ax = -nz * nx; var ay = -nz * ny; var az = 1 - nz * nz
        var al = Math.sqrt(ax * ax + ay * ay + az * az)
        if (al < 1e-6) { ax = -ny * nx; ay = 1 - ny * ny; az = -ny * nz; al = Math.sqrt(ax * ax + ay * ay + az * az) }
        vx = ax / al; vy = ay / al; vz = az / al
        // across: up x n, the viewer's right when looking along -n
        ux = vy * nz - vz * ny; uy = vz * nx - vx * nz; uz = vx * ny - vy * nx
    }
    fun dist(x: Double, y: Double, z: Double) = (x - px) * nx + (y - py) * ny + (z - pz) * nz
    fun u(x: Double, y: Double, z: Double) = (x - px) * ux + (y - py) * uy + (z - pz) * uz
    fun v(x: Double, y: Double, z: Double) = (x - px) * vx + (y - py) * vy + (z - pz) * vz
    /** Whether up is height (the plane is not level). */
    val upIsZ: Boolean get() = Math.abs(nz) < 0.999999
}

/** A cut's picture: a vertical (or any) plane through the print, seen from one side. */
internal class CutView(val plane: Plane, override val fileName: String, override val title: String,
                       /** What the caption says it is, and from where it is seen and what runs across. */ val words: String, val axes: String,
                       /** The across coordinate's name in the caption's frame (X, Y, or the distance along). */ val acrossName: String) : View

/** A level cut at height [z], seen from above. */
internal class CutZView(val z: Double) : View {
    override val fileName = "cut-z-" + String.format(Locale.ROOT, "%.3f", z)
    override val title = "cut at z ${String.format(Locale.ROOT, "%.3f", z)} mm"
}

internal object Cuts {
    fun n3(v: Double) = String.format(Locale.ROOT, "%.3f", v)

    fun y(y: Double) = CutView(Plane(0.0, y, 0.0, 0.0, -1.0, 0.0), "cut-y-${n3(y)}", "cut at y ${n3(y)} mm",
        "the vertical plane y = ${n3(y)} mm, seen from the front", "X across, height up", "X")
    fun x(x: Double) = CutView(Plane(x, 0.0, 0.0, 1.0, 0.0, 0.0), "cut-x-${n3(x)}", "cut at x ${n3(x)} mm",
        "the vertical plane x = ${n3(x)} mm, seen from the right", "Y across (the bed's front at the left), height up", "Y")
    fun line(x0: Double, y0: Double, x1: Double, y1: Double): CutView {
        val dx = x1 - x0; val dy = y1 - y0
        require(Math.hypot(dx, dy) > 1e-9) { "--cut-line $x0,$y0:$x1,$y1: its two points are one; a line needs two" }
        // n = u x up with u along the line: the first point at the left
        return CutView(Plane(x0, y0, 0.0, dy, -dx, 0.0), "cut-line-${n3(x0)},${n3(y0)}-${n3(x1)},${n3(y1)}", "cut along (${n3(x0)}, ${n3(y0)}) to (${n3(x1)}, ${n3(y1)})",
            "the vertical plane along the line from (${n3(x0)}, ${n3(y0)}) to (${n3(x1)}, ${n3(y1)}) on the bed, seen with the first point at the left", "along the line across (mm from the first point), height up", "along the line")
    }
    fun plane(p: DoubleArray, n: DoubleArray): CutView {
        val pl = Plane(p[0], p[1], p[2], n[0], n[1], n[2])
        val a = "(${n3(pl.ux)}, ${n3(pl.uy)}, ${n3(pl.uz)})"; val b = "(${n3(pl.vx)}, ${n3(pl.vy)}, ${n3(pl.vz)})"
        return CutView(pl, "cut-plane-${n3(p[0])},${n3(p[1])},${n3(p[2])}-${n3(n[0])},${n3(n[1])},${n3(n[2])}", "cut by the plane through (${n3(p[0])}, ${n3(p[1])}, ${n3(p[2])})",
            "the plane through (${n3(p[0])}, ${n3(p[1])}, ${n3(p[2])}) facing (${n3(n[0])}, ${n3(n[1])}, ${n3(n[2])}), seen from the side it faces",
            "across along $a, up along $b, both from that point", "across")
    }

    /** The half-width of a bead [w] wide and [h] thick, at [b] above its bottom (the bead model's rounded flanks). */
    fun halfWidthAt(w: Double, h: Double, b: Double): Double {
        val r = h / 2
        val t = b - r
        return maxOf(0.0, (w - h) / 2) + Math.sqrt(maxOf(0.0, r * r - t * t))
    }

    /** The bead's cross-section outline (a, b): a across its line, b up from the nozzle (0 at the nozzle, -h at its bottom). */
    fun stadium(w: Double, h: Double, k: Int = 10): Pair<DoubleArray, DoubleArray> {
        val r = minOf(h, w) / 2; val flat = maxOf(0.0, (w - 2 * r) / 2); val cy = -h / 2
        val a = DoubleArray(2 * (k + 1)); val b = DoubleArray(2 * (k + 1))
        for (i in 0..k) { val t = -Math.PI / 2 + Math.PI * i / k; a[i] = flat + r * Math.cos(t); b[i] = cy + (h / 2) * Math.sin(t) }
        for (i in 0..k) { val t = Math.PI / 2 + Math.PI * i / k; a[k + 1 + i] = -flat + r * Math.cos(t); b[k + 1 + i] = cy + (h / 2) * Math.sin(t) }
        return a to b
    }

    /** An ARGB raster of what [paint] draws without smoothing, each region's edge darker: flat regions with their outlines. */
    fun regions(dw: Int, dh: Int, paint: (java.awt.Graphics2D) -> Unit): IntArray {
        val img = BufferedImage(dw, dh, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF)
        paint(g)
        g.dispose()
        val a = IntArray(dw * dh); img.getRGB(0, 0, dw, dh, a, 0, dw)
        val out = a.copyOf()
        for (y in 0 until dh) for (x in 0 until dw) {
            val p = a[y * dw + x]
            if ((p ushr 24) == 0) continue
            val edge = x == 0 || y == 0 || x == dw - 1 || y == dh - 1 || a[y * dw + x - 1] != p || a[y * dw + x + 1] != p || a[(y - 1) * dw + x] != p || a[(y + 1) * dw + x] != p
            if (edge) out[y * dw + x] = (0xFF shl 24) or (Palette.darker(Color(p and 0xFFFFFF), 0.55).rgb and 0xFFFFFF)
        }
        return out
    }

    fun lighter(c: Color, k: Double = 0.5): Color = Color((c.red + (255 - c.red) * k).toInt(), (c.green + (255 - c.green) * k).toInt(), (c.blue + (255 - c.blue) * k).toInt())
}

/**
 * The picture of a cut seen from the side: each chosen line crossing the plane drawn as its bead's cross-section where
 * the plane meets it (the bead model's section, swept along the line and cut by the plane), and, with --cut-depth, the
 * lines within that distance of the plane drawn from the side behind the sections, lighter, the farthest first.
 */
internal class SectionDrawing(private val ctx: Context, private val m: MoveSet, private val view: CutView, private val depth: Double?) {
    private val pl = view.plane
    /** Each crossing bead's outline in (u, v), mm, its move and its spacing across the picture. */
    val sections = ArrayList<Triple<Int, DoubleArray, DoubleArray>>()
    private val spacings = ArrayList<Double>(); private val lens = ArrayList<Double>()
    /** The lines along the plane within the depth: move, (u0, v0, u1, v1), width along u, height, distance. */
    private val along = ArrayList<DoubleArray>()
    var heightMin = Double.NaN; private set

    init {
        val win = ctx.req.bedWindow ?: ctx.req.around?.let { (x, y) -> val s = ctx.req.span!! / 2; doubleArrayOf(x - s, y - s, x + s, y + s) }
        val zr = ctx.req.zRange
        for (i in 0 until m.n) {
            if (m.kind[i] != MoveSet.EXTRUDE || !ctx.chosen(m, i)) continue
            val w = ctx.beadWidth(m, i).takeIf { !it.isNaN() && it > 0 } ?: continue
            val h = ctx.heightOf(m, i).takeIf { !it.isNaN() && it > 0 } ?: continue
            val d0 = pl.dist(m.x0[i], m.y0[i], m.z0[i]); val d1 = pl.dist(m.x1[i], m.y1[i], m.z1[i])
            val dx = m.x1[i] - m.x0[i]; val dy = m.y1[i] - m.y0[i]; val dz = m.z1[i] - m.z0[i]
            val len = Math.sqrt(dx * dx + dy * dy + dz * dz); if (len < 1e-9) continue
            val ex = dx / len; val ey = dy / len; val ez = dz / len
            val cosA = Math.abs(ex * pl.nx + ey * pl.ny + ez * pl.nz)
            if ((d0 <= 0 && d1 >= 0 || d0 >= 0 && d1 <= 0) && d0 != d1 && cosA >= ALONG_COS) {
                val t = d0 / (d0 - d1)
                val cx = m.x0[i] + t * dx; val cy = m.y0[i] + t * dy; val cz = m.z0[i] + t * dz
                if (win != null && (cx < win[0] || cx > win[2] || cy < win[1] || cy > win[3])) continue
                if (zr != null && (cz < zr.start || cz - h > zr.endInclusive)) continue
                // across the line, level: (-ey, ex, 0) normalized
                val hl = Math.hypot(ex, ey); if (hl < 1e-9) continue
                val sx = -ey / hl; val sy = ex / hl
                val (a, b) = Cuts.stadium(w, h)
                val us = DoubleArray(a.size); val vs = DoubleArray(a.size)
                val dn = ex * pl.nx + ey * pl.ny + ez * pl.nz
                for (k in a.indices) {
                    val qx = cx + a[k] * sx; val qy = cy + a[k] * sy; val qz = cz + b[k]
                    val tt = -pl.dist(qx, qy, qz) / dn
                    val rx = qx + tt * ex; val ry = qy + tt * ey; val rz = qz + tt * ez
                    us[k] = pl.u(rx, ry, rz); vs[k] = pl.v(rx, ry, rz)
                }
                sections += Triple(i, us, vs)
                spacings += plymeld.program.Bead.spacing(w, h) / cosA; lens += w / cosA
                if (heightMin.isNaN() || h < heightMin) heightMin = h
            } else if (depth != null) {
                // the part of the line within the depth of the plane
                var t0 = 0.0; var t1 = 1.0
                if (Math.abs(d1 - d0) < 1e-12) { if (Math.abs(d0) > depth) continue } else {
                    val ta = (-depth - d0) / (d1 - d0); val tb = (depth - d0) / (d1 - d0)
                    t0 = maxOf(0.0, minOf(ta, tb)); t1 = minOf(1.0, maxOf(ta, tb)); if (t0 >= t1) continue
                }
                fun at(t: Double) = doubleArrayOf(m.x0[i] + t * dx, m.y0[i] + t * dy, m.z0[i] + t * dz)
                val p = at(t0); val q = at(t1)
                if (win != null && (maxOf(p[0], q[0]) < win[0] || minOf(p[0], q[0]) > win[2] || maxOf(p[1], q[1]) < win[1] || minOf(p[1], q[1]) > win[3])) continue
                if (zr != null && (maxOf(p[2], q[2]) < zr.start || minOf(p[2], q[2]) - h > zr.endInclusive)) continue
                along += doubleArrayOf(i.toDouble(), pl.u(p[0], p[1], p[2]), pl.v(p[0], p[1], p[2]), pl.u(q[0], q[1], q[2]), pl.v(q[0], q[1], q[2]), w, h, pl.dist((p[0] + q[0]) / 2, (p[1] + q[1]) / 2, (p[2] + q[2]) / 2))
                if (heightMin.isNaN() || h < heightMin) heightMin = h
            }
        }
        along.sortBy { it[7] }     // farthest from the viewer (on -n) first
    }

    /** The closest sections across the picture, by the same rule as a layer's lines ([Context.THIN_SHARE]). */
    fun closest(): Double {
        if (spacings.isEmpty()) return Double.NaN
        val order = spacings.indices.sortedWith(compareBy<Int> { spacings[it] }.thenBy { it })
        val total = lens.sum(); var acc = 0.0
        for (k in order) { acc += lens[k]; if (acc > Context.THIN_SHARE * total) return spacings[k] }
        return spacings[order.last()]
    }

    /** The extent drawn (u0, v0, u1, v1), mm; null when nothing is. */
    fun extent(): DoubleArray? {
        var u0 = Double.MAX_VALUE; var v0 = Double.MAX_VALUE; var u1 = -Double.MAX_VALUE; var v1 = -Double.MAX_VALUE
        for ((_, us, vs) in sections) for (k in us.indices) { u0 = minOf(u0, us[k]); u1 = maxOf(u1, us[k]); v0 = minOf(v0, vs[k]); v1 = maxOf(v1, vs[k]) }
        for (a in along) { val e = a[5] / 2
            u0 = minOf(u0, a[1] - e, a[3] - e); u1 = maxOf(u1, a[1] + e, a[3] + e); v0 = minOf(v0, a[2] - a[6], a[4] - a[6]); v1 = maxOf(v1, a[2], a[4]) }
        return if (u0 > u1) null else doubleArrayOf(u0, v0, u1, v1)
    }

    fun features(): List<Int> = sections.map { m.feature[it.first] }.distinct().sorted()
    fun tools(): List<Int> = sections.map { m.tool[it.first] }.distinct().sorted()
    val moveIds: List<Int> get() = sections.map { it.first }

    /** Draws the cut: [sx], [sy] map (u, v) mm to the canvas; [asLines]: each section outlined, else flat regions. */
    fun draw(c: Canvas, color: (Int) -> Color, sx: (Double) -> Double, sy: (Double) -> Double, asLines: Boolean, dw: Int, dh: Int, ox: Double, oy: Double) {
        fun alongPoly(a: DoubleArray): Pair<DoubleArray, DoubleArray> {
            val e = a[5] / 2; val h = a[6]
            val (ua, va, ub, vb) = if (a[1] <= a[3]) listOf(a[1], a[2], a[3], a[4]) else listOf(a[3], a[4], a[1], a[2])
            return doubleArrayOf(sx(ua - e), sx(ub + e), sx(ub + e), sx(ua - e)) to doubleArrayOf(sy(va), sy(vb), sy(vb - h), sy(va - h))
        }
        if (asLines) {
            for (a in along) { val (xs, ys) = alongPoly(a); val col = Cuts.lighter(color(a[0].toInt())); c.polygon(xs, ys, col, Palette.darker(col, 0.8), 0.6) }
            for ((i, us, vs) in sections) {
                val col = color(i)
                c.polygon(DoubleArray(us.size) { sx(us[it]) }, DoubleArray(vs.size) { sy(vs[it]) }, col, Palette.darker(col), 1.0)
            }
        } else {
            val img = Cuts.regions(dw, dh) { g ->
                for (a in along) { val (xs, ys) = alongPoly(a); g.color = Cuts.lighter(color(a[0].toInt())); g.fill(poly(DoubleArray(4) { xs[it] - ox }, DoubleArray(4) { ys[it] - oy })) }
                for ((i, us, vs) in sections) { g.color = color(i); g.fill(poly(DoubleArray(us.size) { sx(us[it]) - ox }, DoubleArray(vs.size) { sy(vs[it]) - oy })) }
            }
            c.image(img, dw, dh, Math.round(ox).toInt(), Math.round(oy).toInt())
        }
    }

    private fun poly(xs: DoubleArray, ys: DoubleArray): Path2D.Double { val p = Path2D.Double(); p.moveTo(xs[0], ys[0]); for (k in 1 until xs.size) p.lineTo(xs[k], ys[k]); p.closePath(); return p }

    val alongCount: Int get() = along.size

    companion object {
        /** A line meeting the plane at less than this cosine (about 3 degrees) runs along it: no section is drawn for it. */
        const val ALONG_COS = 0.05
    }
}

/** The colors of moves [ids] of [m] by the run's --color-by: a gradient spread over their own values, and its key. */
internal class Coloring(private val ctx: Context, private val m: MoveSet, private val ids: List<Int>) {
    private var lo = Double.NaN; private var hi = Double.NaN
    private val orderOf = HashMap<Int, Int>()
    init {
        for ((k, i) in ids.sorted().withIndex()) orderOf[i] = k
        if (ctx.colorBy != ColorBy.FEATURE && ctx.colorBy != ColorBy.TOOL) for (i in ids) {
            val v = ctx.value(m, i, orderOf.getValue(i)); if (v.isNaN()) continue
            if (lo.isNaN() || v < lo) lo = v; if (hi.isNaN() || v > hi) hi = v
        }
    }
    fun color(i: Int): Color = when (ctx.colorBy) {
        ColorBy.FEATURE -> if (ctx.index.namesFeatures) ctx.featureColor(m.feature[i]) else Palette.UNNAMED
        ColorBy.TOOL -> ctx.toolColor(m.tool[i])
        else -> { val v = ctx.value(m, i, orderOf[i] ?: 0); if (v.isNaN()) Palette.UNNAMED else Palette.ramp(if (hi > lo) (v - lo) / (hi - lo) else 0.5) }
    }
    fun key(): List<KeyItem> {
        val k = ArrayList<KeyItem>()
        when (ctx.colorBy) {
            ColorBy.FEATURE -> if (ctx.index.namesFeatures) ids.map { m.feature[it] }.distinct().sorted().forEach {
                k += KeyItem.Swatch(ctx.featureColor(it), if (it < 0) "(no feature named)" else ctx.index.features[it]) }
            ColorBy.TOOL -> ids.map { m.tool[it] }.distinct().sorted().forEach { k += KeyItem.Swatch(ctx.toolColor(it), "T$it") }
            ColorBy.ORDER -> if (!lo.isNaN()) k += KeyItem.Gradient("printed first", "printed last")
            else -> if (!lo.isNaN()) { val u = ctx.unit(); k += KeyItem.Gradient("${num(lo)} $u", "${num(hi)} $u") } else k += KeyItem.Swatch(Palette.UNNAMED, "the file does not say")
        }
        return k
    }
    private fun num(v: Double): String = if (Math.abs(v) >= 100) Render.f(v, 0) else if (Math.abs(v) >= 1) Render.f(v, 1) else Render.f(v, 3)
}
