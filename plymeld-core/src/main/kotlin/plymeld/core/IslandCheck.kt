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
 * A line read so is narrower than the pre-print check's hole rule (HoleCheck) and the catalog's wall reading
 * (WallCoverage) read it, at its full width: a sliver only this measure reads bare is accepted for now, an open
 * inconsistency (decision 63).
 */
object IslandCheck {
    /** [cellUm]: the grid's cell it was read on (each figure is good to about a cell). */
    class Measure(val widestUm: Double, val wideUm2: Double, val outsideUm: Double, val doubledUm2: Double, val cellUm: Double = CELL_UM) {
        override fun toString() = String.format(java.util.Locale.ROOT, "widest uncovered %.0f um, %.0f um2 wider than the floor; outside %.0f um; doubled %.0f um2",
            widestUm, wideUm2, outsideUm, doubledUm2)
    }
    /** A line to measure: its points (µm, x y ...), its width at each, whether it closes. */
    class Line(val xy: LongArray, val widths: DoubleArray, val closed: Boolean)

    /** The grid's cell (µm): a geometric resolution, plymeld's choice. */
    const val CELL_UM = 15.0
    /**
     * The most cells measured: a larger island (a thin ring round a large part) is measured on a coarser grid, the cell
     * doubled until it fits, each figure then good to that cell. plymeld's choice (memory and time: a grid's arrays take
     * some tens of megabytes, one a thread measuring).
     */
    const val MAX_CELLS = 4_000_000L

    fun measure(island: List<Polygon>, lines: List<Line>, covered: List<Polygon>, flankUm: Double, floorUm: Double, slackUm: Double): Measure {
        var x0 = Long.MAX_VALUE; var y0 = Long.MAX_VALUE; var x1 = Long.MIN_VALUE; var y1 = Long.MIN_VALUE
        for (p in island) { val b = p.bounds(); x0 = minOf(x0, b[0]); y0 = minOf(y0, b[1]); x1 = maxOf(x1, b[2]); y1 = maxOf(y1, b[3]) }
        // the grid: [CELL_UM], or coarser (doubled until it holds no more than [MAX_CELLS]) for a large island
        var cell = CELL_UM
        val half = lines.maxOfOrNull { it.widths.maxOrNull() ?: 0.0 }.let { it ?: 0.0 } / 2
        fun cellsAt(c: Double) = Math.ceil((x1 - x0 + 2 * (4 * c + half)) / c) * Math.ceil((y1 - y0 + 2 * (4 * c + half)) / c)
        while (cellsAt(cell) > MAX_CELLS) cell *= 2
        val margin = 4 * cell + half
        val ox = x0 - margin; val oy = y0 - margin
        val nx = Math.ceil((x1 - x0 + 2 * margin) / cell).toInt(); val ny = Math.ceil((y1 - y0 + 2 * margin) / cell).toInt()
        val n = nx * ny
        // each row's crossings of the polygons' edges with its center line, found edge by edge over the rows each edge spans
        // (with a row to spare each side; the crossing rule decides), counted, then placed, then sorted row by row
        fun fill(polys: List<Polygon>, into: BooleanArray) {
            val start = IntArray(ny + 1)
            fun crossings(place: DoubleArray?) {
                for (p in polys) for (i in 0 until p.size) {
                    val ay = p.y(i).toDouble(); val by = p.y((i + 1) % p.size).toDouble()
                    if (ay == by) continue
                    val jLo = maxOf(0, Math.floor((minOf(ay, by) - oy) / cell - 0.5).toInt() - 1)
                    val jHi = minOf(ny - 1, Math.ceil((maxOf(ay, by) - oy) / cell - 0.5).toInt() + 1)
                    for (j in jLo..jHi) {
                        val yc = oy + (j + 0.5) * cell
                        if ((ay > yc) == (by > yc)) continue
                        if (place == null) { start[j + 1]++; continue }
                        val ax = p.x(i).toDouble(); val bx = p.x((i + 1) % p.size).toDouble()
                        place[start[j]++] = ax + (yc - ay) / (by - ay) * (bx - ax)
                    }
                }
            }
            crossings(null)
            for (j in 0 until ny) start[j + 1] += start[j]
            val xs = DoubleArray(start[ny])
            crossings(xs)
            // (placing moved each row's start to its end: the row before's end is its start again)
            for (j in ny downTo 1) start[j] = start[j - 1]
            start[0] = 0
            for (j in 0 until ny) {
                val s = start[j]; val e = start[j + 1]
                java.util.Arrays.sort(xs, s, e)
                var k = s
                while (k + 1 < e) {
                    val i0 = maxOf(0, Math.ceil((xs[k] - ox) / cell - 0.5).toInt()); val i1 = minOf(nx - 1, Math.floor((xs[k + 1] - ox) / cell - 0.5).toInt())
                    for (i in i0..i1) into[j * nx + i] = !into[j * nx + i]
                    k += 2
                }
            }
        }
        val inside = BooleanArray(n); fill(island, inside)
        // (the area handed to infill filled straight into the cover, still empty)
        val cov = BooleanArray(n)
        if (covered.isNotEmpty()) fill(covered, cov)
        // each cell's owner, the line's number + 1 (0: none); the cells two lines own, counted as each is first found
        val owner = IntArray(n); val doubled = BooleanArray(n); var dbl = 0
        /** The cells within [r] of segment a-b covered; those within [rCore] (<= r) owned by line [id] (a second line's: doubled). */
        // row by row, the span of cell centers the capsule holds (a capsule is convex: its cut by a row is one span)
        val span = DoubleArray(2)
        fun capsule(ax: Double, ay: Double, bx: Double, by: Double, r: Double, rCore: Double, id: Int) {
            if (r <= 0) return
            val j0 = maxOf(0, Math.floor((minOf(ay, by) - r - oy) / cell).toInt()); val j1 = minOf(ny - 1, Math.ceil((maxOf(ay, by) + r - oy) / cell).toInt())
            for (j in j0..j1) {
                val py = oy + (j + 0.5) * cell
                if (!capsuleSpan(ax, ay, bx, by, r, py, span)) continue
                val i0 = maxOf(0, Math.ceil((span[0] - ox) / cell - 0.5).toInt()); val i1 = minOf(nx - 1, Math.floor((span[1] - ox) / cell - 0.5).toInt())
                for (i in i0..i1) cov[j * nx + i] = true
                if (rCore <= 0 || !capsuleSpan(ax, ay, bx, by, rCore, py, span)) continue
                val k0 = maxOf(0, Math.ceil((span[0] - ox) / cell - 0.5).toInt()); val k1 = minOf(nx - 1, Math.floor((span[1] - ox) / cell - 0.5).toInt())
                for (i in k0..k1) { val c = j * nx + i; val o = owner[c]; if (o == 0) owner[c] = id + 1 else if (o != id + 1 && !doubled[c]) { doubled[c] = true; dbl++ } }
            }
        }
        for ((id, l) in lines.withIndex()) {
            val m = l.xy.size / 2
            fun stamp(a: Int, b: Int) {
                val ax = l.xy[2 * a].toDouble(); val ay = l.xy[2 * a + 1].toDouble(); val bx = l.xy[2 * b].toDouble(); val by = l.xy[2 * b + 1].toDouble()
                val w = (l.widths[a] + l.widths[b]) / 2
                capsule(ax, ay, bx, by, w / 2 - flankUm / 2, w / 2 - flankUm / 2 - slackUm, id)
            }
            if (m == 1) { stamp(0, 0); continue }
            // consecutive segments stamped as one where the points between lie within half a cell of it and their widths
            // within a cell of its own (finer than the grid reads; a line's points lie a few microns apart where its width
            // changes, and each short segment stamped its whole round end again)
            val segs = if (l.closed) m else m - 1
            /** Whether segment a-b (point indices round the line, b > a) holds the points between it. */
            fun fits(a: Int, b: Int): Boolean {
                val pa = a % m; val pb = b % m
                val ax = l.xy[2 * pa].toDouble(); val ay = l.xy[2 * pa + 1].toDouble()
                val dx = l.xy[2 * pb] - ax; val dy = l.xy[2 * pb + 1] - ay; val l2 = dx * dx + dy * dy
                val w = (l.widths[pa] + l.widths[pb]) / 2
                for (k in a + 1 until b) {
                    val pk = k % m
                    if (Math.abs(l.widths[pk] - w) > cell) return false
                    val px = l.xy[2 * pk] - ax; val py = l.xy[2 * pk + 1] - ay
                    val t = if (l2 > 0) ((px * dx + py * dy) / l2).coerceIn(0.0, 1.0) else 0.0
                    val ex = px - t * dx; val ey = py - t * dy
                    if (ex * ex + ey * ey > cell * cell / 4) return false
                }
                return true
            }
            var a = 0
            while (a < segs) {
                var b = a + 1
                while (b < segs && b - a < MERGE_MAX && fits(a, b + 1)) b++
                stamp(a % m, b % m)
                a = b
            }
        }
        val cellA = cell * cell
        // distances by searching round each cell measured (the cells measured are few and their distances short: a narrow
        // island's uncovered spots and the plastic just outside it); where an uncovered spot is wider than the search, the
        // distance transform of its box instead
        // (one pass over the grid: the cells uncovered inside the island, and the covered ones outside it, each in order)
        val uncovered = BooleanArray(n)
        var uncL = IntArray(256); var nu = 0
        var outL = IntArray(256); var no = 0
        for (c in 0 until n) {
            val ins = inside[c]
            if (ins == cov[c]) continue
            if (ins) { uncovered[c] = true; if (nu == uncL.size) uncL = uncL.copyOf(2 * nu); uncL[nu++] = c }
            else { if (no == outL.size) outL = outL.copyOf(2 * no); outL[no++] = c }
        }
        var widest = 0.0; var wide = 0
        val unc = uncL.copyOf(nu)
        if (unc.isNotEmpty()) {
            val dU = DoubleArray(unc.size) { nearest(uncovered, nx, ny, unc[it], RING_CELLS, true, invert = true) }
            val rr = floorUm / 2 / cell
            if (dU.any { it > RING_CELLS }) {
                val ub = box(uncovered, nx, ny, 1)!!
                val bw = ub[1] - ub[0] + 1; val bh = ub[3] - ub[2] + 1
                val sub = BooleanArray(bw * bh) { k -> uncovered[(ub[2] + k / bw) * nx + ub[0] + k % bw] }
                val d = edt(sub, bw, bh)
                for ((q, c) in unc.withIndex()) dU[q] = d[(c / nx - ub[2]) * bw + c % nx - ub[0]]
            }
            widest = dU.max()
            if (widest >= rr) {   // nothing uncovered as wide as the floor: no area wider than it
                val core = BooleanArray(n); for ((q, c) in unc.withIndex()) if (dU[q] >= rr) core[c] = true
                val reachCore = Math.ceil(rr).toInt()
                for (c in unc) if (nearest(core, nx, ny, c, reachCore, false) <= rr) wide++
            }
        }
        var farthest = 0.0
        val reach = Math.ceil(margin / cell).toInt()
        for (q in 0 until no) farthest = maxOf(farthest, minOf(nearest(inside, nx, ny, outL[q], reach, false), reach.toDouble()))
        return Measure(2 * widest * cell, wide * cellA, farthest * cell, dbl * cellA, cell)
    }

    /**
     * The span of x where the row y = [py] lies within [r] of segment a-b, into [out] (x0, x1); false where it misses: the
     * union of the end discs' and the band's spans (each convex, their union the capsule).
     */
    internal fun capsuleSpan(ax: Double, ay: Double, bx: Double, by: Double, r: Double, py: Double, out: DoubleArray): Boolean {
        var lo = Double.POSITIVE_INFINITY; var hi = Double.NEGATIVE_INFINITY
        for (e in 0..1) {
            val cx = if (e == 0) ax else bx; val cy = if (e == 0) ay else by
            val q = r * r - (py - cy) * (py - cy)
            if (q < 0) continue
            val h = Math.sqrt(q); lo = minOf(lo, cx - h); hi = maxOf(hi, cx + h)
        }
        val dx = bx - ax; val dy = by - ay; val len = Math.sqrt(dx * dx + dy * dy)
        if (len > 0) {
            val ux = dx / len; val uy = dy / len
            // along: 0 <= (x - ax) ux + (py - ay) uy <= len; across: |(x - ax) (-uy) + (py - ay) ux| <= r
            var a0 = Double.NEGATIVE_INFINITY; var a1 = Double.POSITIVE_INFINITY; var ok = true
            fun within(k: Double, c: Double, min: Double, max: Double) {   // min <= k x + c <= max
                if (Math.abs(k) < 1e-12) { if (c < min || c > max) ok = false; return }
                val t1 = (min - c) / k; val t2 = (max - c) / k
                a0 = maxOf(a0, minOf(t1, t2)); a1 = minOf(a1, maxOf(t1, t2))
            }
            within(ux, -ax * ux + (py - ay) * uy, 0.0, len)
            within(-uy, ax * uy + (py - ay) * ux, -r, r)
            if (ok && a0 <= a1) { lo = minOf(lo, a0); hi = maxOf(hi, a1) }
        }
        if (lo > hi) return false
        out[0] = lo; out[1] = hi
        return true
    }

    /** The most segments stamped as one (the cost of checking a run grows with its length): plymeld's choice (time). */
    private const val MERGE_MAX = 32
    /** How far (cells) a search round a cell looks before the distance transform takes over: plymeld's choice (time). */
    private const val RING_CELLS = 24

    /**
     * The distance (cells, between centers) from cell [c] to the nearest cell where [target] is true, searched out to
     * [limit] cells ([limit] + 1 where none lies within it); a cell beyond the grid counts as a target where [edge] says so;
     * [invert]: the cells where [target] is false are the targets.
     */
    private fun nearest(target: BooleanArray, nx: Int, ny: Int, c: Int, limit: Int, edge: Boolean, invert: Boolean = false): Double {
        val ci = c % nx; val cj = c / nx
        if (target[c] != invert) return 0.0
        var best2 = Long.MAX_VALUE
        var k = 1
        while (k <= limit) {
            // ring k: the cells at Chebyshev distance k
            for (dj in -k..k) {
                val j = cj + dj
                val step = if (dj == -k || dj == k) 1 else 2 * k
                var di = -k
                while (di <= k) {
                    val i = ci + di
                    val hit = if (i < 0 || j < 0 || i >= nx || j >= ny) edge else target[j * nx + i] != invert
                    if (hit) { val d2 = di.toLong() * di + dj.toLong() * dj; if (d2 < best2) best2 = d2 }
                    di += step
                }
            }
            // a nearer target can lie no farther out than ring ceil(sqrt(best)): stop once past it
            if (best2 != Long.MAX_VALUE && k.toLong() * k >= best2) break
            k++
        }
        return if (best2 == Long.MAX_VALUE) (limit + 1).toDouble() else Math.sqrt(best2.toDouble())
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
        // a row with no cell of the mask is all 0 (as is a column after the rows' pass)
        for (j in 0 until ny) {
            var any = false
            for (i in 0 until nx) { val m = mask[j * nx + i]; f[i] = if (m) inf else 0.0; if (m) any = true }
            if (!any) continue
            pass(nx); for (i in 0 until nx) g[j * nx + i] = d[i]
        }
        for (i in 0 until nx) {
            var any = false
            for (j in 0 until ny) { val x = g[j * nx + i]; f[j] = x; if (x != 0.0) any = true }
            if (!any) continue
            pass(ny); for (j in 0 until ny) { val x = d[j]; g[j * nx + i] = if (x == 0.0) 0.0 else Math.sqrt(x) }
        }
        return g
    }
}

