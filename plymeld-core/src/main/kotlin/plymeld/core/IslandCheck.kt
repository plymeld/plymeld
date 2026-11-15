package plymeld.core

import plymeld.geom.Polygon

/**
 * How well one island's walls lay it, measured on a grid: what Walls.arachne compares the beads of a narrow island (Beads)
 * against the walls of 26 by, before choosing either. Each line a bead of its width round its center line (narrowed by the
 * bead's rounded flank a side, the usual overlap of two beads in contact), a loop the same; the area the walls leave to
 * infill counts as covered.
 *  - uncovered: the widest spot inside the island no bead covers (twice the farthest such cell from a covered or outside
 *    one), and the uncovered area wider than [floorUm] (within reach of a disc that wide fitting in it);
 *  - outside: how far beyond the island the farthest plastic lies;
 *  - doubled: the area two different lines both cover, each narrowed by half the flank and a further [slackUm].
 */
object IslandCheck {
    class Measure(val widestUm: Double, val wideUm2: Double, val outsideUm: Double, val doubledUm2: Double) {
        override fun toString() = String.format(java.util.Locale.ROOT, "widest uncovered %.0f um, %.0f um2 wider than the floor; outside %.0f um; doubled %.0f um2",
            widestUm, wideUm2, outsideUm, doubledUm2)
    }
    /** A line to measure: its points (µm, x y ...), its width at each, whether it closes. */
    class Line(val xy: LongArray, val widths: DoubleArray, val closed: Boolean)

    /** The grid's cell (µm): a geometric resolution, plymeld's choice. */
    const val CELL_UM = 15.0
    /** The most cells measured; a larger island is not measured (null). plymeld's choice (memory and time). */
    const val MAX_CELLS = 4_000_000L

    fun measure(island: List<Polygon>, lines: List<Line>, covered: List<Polygon>, flankUm: Double, floorUm: Double, slackUm: Double): Measure? {
        var x0 = Long.MAX_VALUE; var y0 = Long.MAX_VALUE; var x1 = Long.MIN_VALUE; var y1 = Long.MIN_VALUE
        for (p in island) { val b = p.bounds(); x0 = minOf(x0, b[0]); y0 = minOf(y0, b[1]); x1 = maxOf(x1, b[2]); y1 = maxOf(y1, b[3]) }
        val margin = 4 * CELL_UM + lines.maxOfOrNull { it.widths.maxOrNull() ?: 0.0 }.let { it ?: 0.0 } / 2
        val ox = x0 - margin; val oy = y0 - margin
        val nx = Math.ceil((x1 - x0 + 2 * margin) / CELL_UM).toInt(); val ny = Math.ceil((y1 - y0 + 2 * margin) / CELL_UM).toInt()
        if (nx.toLong() * ny > MAX_CELLS) return null
        val n = nx * ny
        fun fill(polys: List<Polygon>, into: BooleanArray) {
            for (j in 0 until ny) {
                val yc = oy + (j + 0.5) * CELL_UM
                val xs = ArrayList<Double>()
                for (p in polys) for (i in 0 until p.size) {
                    val ay = p.y(i).toDouble(); val by = p.y((i + 1) % p.size).toDouble()
                    if ((ay > yc) != (by > yc)) { val ax = p.x(i).toDouble(); val bx = p.x((i + 1) % p.size).toDouble(); xs += ax + (yc - ay) / (by - ay) * (bx - ax) }
                }
                xs.sort()
                var k = 0
                while (k + 1 < xs.size) {
                    val i0 = maxOf(0, Math.ceil((xs[k] - ox) / CELL_UM - 0.5).toInt()); val i1 = minOf(nx - 1, Math.floor((xs[k + 1] - ox) / CELL_UM - 0.5).toInt())
                    for (i in i0..i1) into[j * nx + i] = !into[j * nx + i]
                    k += 2
                }
            }
        }
        val inside = BooleanArray(n); fill(island, inside)
        val cov = BooleanArray(n)
        if (covered.isNotEmpty()) { val c = BooleanArray(n); fill(covered, c); for (i in 0 until n) if (c[i]) cov[i] = true }
        val owner = IntArray(n) { -1 }; val doubled = BooleanArray(n)
        fun capsule(ax: Double, ay: Double, bx: Double, by: Double, r: Double, mark: (Int) -> Unit) {
            if (r <= 0) return
            val i0 = maxOf(0, Math.floor((minOf(ax, bx) - r - ox) / CELL_UM).toInt()); val i1 = minOf(nx - 1, Math.ceil((maxOf(ax, bx) + r - ox) / CELL_UM).toInt())
            val j0 = maxOf(0, Math.floor((minOf(ay, by) - r - oy) / CELL_UM).toInt()); val j1 = minOf(ny - 1, Math.ceil((maxOf(ay, by) + r - oy) / CELL_UM).toInt())
            val dx = bx - ax; val dy = by - ay; val l2 = dx * dx + dy * dy
            for (j in j0..j1) for (i in i0..i1) {
                val px = ox + (i + 0.5) * CELL_UM; val py = oy + (j + 0.5) * CELL_UM
                val t = if (l2 == 0.0) 0.0 else (((px - ax) * dx + (py - ay) * dy) / l2).coerceIn(0.0, 1.0)
                if (Math.hypot(px - ax - t * dx, py - ay - t * dy) <= r) mark(j * nx + i)
            }
        }
        for ((id, l) in lines.withIndex()) {
            val m = l.xy.size / 2
            val segs = if (l.closed) m else m - 1
            for (s in 0 until maxOf(segs, if (m == 1) 1 else 0)) {
                val a = s; val b = if (m == 1) 0 else (s + 1) % m
                val ax = l.xy[2 * a].toDouble(); val ay = l.xy[2 * a + 1].toDouble(); val bx = l.xy[2 * b].toDouble(); val by = l.xy[2 * b + 1].toDouble()
                val w = (l.widths[a] + l.widths[b]) / 2
                capsule(ax, ay, bx, by, w / 2 - flankUm / 2) { cov[it] = true }
                capsule(ax, ay, bx, by, w / 2 - flankUm / 2 - slackUm) { c -> val o = owner[c]; if (o < 0) owner[c] = id else if (o != id) doubled[c] = true }
            }
        }
        val cellA = CELL_UM * CELL_UM
        // the transforms run on the box round the cells they measure: the uncovered cells' box (a cell beyond it is no
        // uncovered one, so the nearest such lies in it); the plastic outside's box widened by the grid's margin (farther
        // than that from the island reads as that far)
        val uncovered = BooleanArray(n) { inside[it] && !cov[it] }
        var widest = 0.0; var wide = 0
        val ub = box(uncovered, nx, ny, 1)
        if (ub != null) {
            val bw = ub[1] - ub[0] + 1; val bh = ub[3] - ub[2] + 1
            val sub = BooleanArray(bw * bh) { k -> uncovered[(ub[2] + k / bw) * nx + ub[0] + k % bw] }
            val dU = edt(sub, bw, bh)
            for (c in sub.indices) if (sub[c]) widest = maxOf(widest, dU[c])
            val rr = floorUm / 2 / CELL_UM
            if (widest >= rr) {   // nothing uncovered as wide as the floor: no area wider than it (the second transform not needed)
                val dCore = edt(BooleanArray(sub.size) { !(sub[it] && dU[it] >= rr) }, bw, bh)
                for (c in sub.indices) if (sub[c] && dCore[c] <= rr) wide++
            }
        }
        val spill = BooleanArray(n) { cov[it] && !inside[it] }
        var farthest = 0.0
        val reach = Math.ceil(margin / CELL_UM).toInt()
        val ob = box(spill, nx, ny, reach)
        if (ob != null) {
            val bw = ob[1] - ob[0] + 1; val bh = ob[3] - ob[2] + 1
            val dOut = edt(BooleanArray(bw * bh) { k -> !inside[(ob[2] + k / bw) * nx + ob[0] + k % bw] }, bw, bh)
            for (k in dOut.indices) if (spill[(ob[2] + k / bw) * nx + ob[0] + k % bw]) farthest = maxOf(farthest, minOf(dOut[k], reach.toDouble()))
        }
        val dbl = doubled.count { it }
        return Measure(2 * widest * CELL_UM, wide * cellA, farthest * CELL_UM, dbl * cellA)
    }

    /** The box (i0, i1, j0, j1, inclusive) round the cells where [mask] is true, widened by [pad] cells within the grid; null = none. */
    private fun box(mask: BooleanArray, nx: Int, ny: Int, pad: Int): IntArray? {
        var i0 = Int.MAX_VALUE; var i1 = -1; var j0 = Int.MAX_VALUE; var j1 = -1
        for (j in 0 until ny) for (i in 0 until nx) if (mask[j * nx + i]) { if (i < i0) i0 = i; if (i > i1) i1 = i; if (j < j0) j0 = j; if (j > j1) j1 = j }
        if (i1 < 0) return null
        return intArrayOf(maxOf(0, i0 - pad), minOf(nx - 1, i1 + pad), maxOf(0, j0 - pad), minOf(ny - 1, j1 + pad))
    }

    /** The Euclidean distance (cells) from each cell where [mask] is true to the nearest where it is false (0 there). */
    private fun edt(mask: BooleanArray, nx: Int, ny: Int): DoubleArray {
        val inf = 1e20
        val len = maxOf(nx, ny)
        val f = DoubleArray(len); val d = DoubleArray(len); val v = IntArray(len); val z = DoubleArray(len + 1)
        fun pass(n: Int) {
            var k = 0; v[0] = 0; z[0] = -Double.MAX_VALUE; z[1] = Double.MAX_VALUE
            for (q in 1 until n) {
                fun cross(p: Int) = ((f[q] + q.toDouble() * q) - (f[p] + p.toDouble() * p)) / (2.0 * q - 2.0 * p)
                var s = cross(v[k])
                while (s <= z[k]) { k--; s = cross(v[k]) }
                k++; v[k] = q; z[k] = s; z[k + 1] = Double.MAX_VALUE
            }
            k = 0
            for (q in 0 until n) { while (z[k + 1] < q) k++; d[q] = (q - v[k]).toDouble() * (q - v[k]) + f[v[k]] }
        }
        val g = DoubleArray(nx * ny)
        for (j in 0 until ny) { for (i in 0 until nx) f[i] = if (mask[j * nx + i]) inf else 0.0; pass(nx); for (i in 0 until nx) g[j * nx + i] = d[i] }
        for (i in 0 until nx) { for (j in 0 until ny) f[j] = g[j * nx + i]; pass(ny); for (j in 0 until ny) g[j * nx + i] = Math.sqrt(d[j]) }
        return g
    }
}
