package plymeld.core

import plymeld.geom.Polygon

/**
 * Gyroid sparse infill. The gyroid is the triply
 * periodic surface sin(kx) cos(ky) + sin(ky) cos(kz) + sin(kz) cos(kx) = 0 (k = 2 pi / period); a layer lays its
 * cross-section at the layer's middle height, so the layers stack into the surface's continuous, curved walls and no two
 * lines of one layer cross each other (grid's second direction passes over the first's fresh beads at every crossing).
 *
 * plymeld's own construction: the cross-section is traced by marching squares over the region's box, its
 * pieces joined into polylines, then clipped to the region ([SurfaceFill.clip]). The period follows from the density:
 * a layer's plastic is (curve length per area) x (bead spacing), so period = [lengthPerArea] x spacing / density, where
 * [lengthPerArea] is the cross-section's mean length per area over a whole period of height, measured from the function
 * itself (not a figure from elsewhere).
 */
object Gyroid {
    /**
     * Grid cells per period when tracing a layer. A tracing resolution, not a print parameter (plymeld's choice): at 24 the
     * chord between two traced points strays well under 0.02 mm from the true curve at periods of a few mm.
     */
    private const val CELLS_PER_PERIOD = 24

    /** Mean cross-section length per unit area at period 1, over a period of height (dimensionless; computed once). */
    val lengthPerArea: Double by lazy {
        val n = 96; val zs = 48
        var sum = 0.0
        for (iz in 0 until zs) {
            val z = (iz + 0.5) / zs
            sum += traceLength(n, z) // over the unit square: length per unit area
        }
        sum / zs
    }

    /** The period (µm) that lays [density] (0..1) of the volume with beads [spacingUm] apart. */
    fun periodUm(spacingUm: Double, density: Double): Long = Math.round(lengthPerArea * spacingUm / density)

    /**
     * The layer's curves at height [zUm] (the gyroid's own z, µm), clipped to [region]: polylines (flat x y in µm), each at
     * least [minLenUm] long. [center] (µm) is the pattern's origin, so the pattern sits the same way in a part wherever it
     * lies on the bed (Planner.patternCenter); null = the bed's origin.
     */
    fun curves(region: List<Polygon>, periodUm: Long, zUm: Double, center: DoubleArray?, minLenUm: Long,
               /** How far a laid curve may stray from the traced one between two vertices (µm, plan.layer.infill.chord_tolerance); 0 = every traced point kept. */
               chordUm: Long = 0): List<LongArray> {
        if (region.isEmpty() || periodUm <= 0) return emptyList()
        var minX = Long.MAX_VALUE; var minY = Long.MAX_VALUE; var maxX = Long.MIN_VALUE; var maxY = Long.MIN_VALUE
        for (p in region) for (k in 0 until p.size) { minX = minOf(minX, p.x(k)); maxX = maxOf(maxX, p.x(k)); minY = minOf(minY, p.y(k)); maxY = maxOf(maxY, p.y(k)) }
        val cell = periodUm.toDouble() / CELLS_PER_PERIOD
        val x0 = minX - cell; val y0 = minY - cell
        val nx = ((maxX - minX) / cell).toInt() + 3; val ny = ((maxY - minY) / cell).toInt() + 3
        val cx = center?.get(0) ?: 0.0; val cy = center?.get(1) ?: 0.0
        val k = 2 * Math.PI / periodUm
        // the trace is fine (CELLS_PER_PERIOD) so the curve is right; what is laid is the trace simplified to the chord
        // tolerance: fewer, longer segments, which the firmware's junction planner runs faster (traced at 24 cells per
        // period a gyroid's 1.17 mm segments cost 105 min on a 9 h print against a grid of the same density)
        val polylines = trace(nx, ny, { i -> x0 + i * cell }, { j -> y0 + j * cell }, { x -> k * (x - cx) }, { y -> k * (y - cy) }, k * zUm)
            .map { if (chordUm > 0) simplify(it, chordUm.toDouble()) else it }
        val out = ArrayList<LongArray>()
        for ((outer, holes) in plymeld.geom.Polygons.group(region)) {
            val piece = listOf(outer) + holes
            for (pl in polylines) out += SurfaceFill.clip(pl, piece, minLenUm)
        }
        return out
    }

    /**
     * Douglas-Peucker on an open polyline (x y ... doubles): the vertices more than [tol] from the chord between the kept
     * vertices around them stay, the rest go; the ends stay. A closed trace (its last point its first) keeps the vertex
     * farthest from its start as the second anchor, so the loop keeps its shape.
     */
    fun simplify(pl: List<Double>, tol: Double): List<Double> {
        val n = pl.size / 2
        if (n <= 2 || tol <= 0) return pl
        fun x(i: Int) = pl[2 * i]
        fun y(i: Int) = pl[2 * i + 1]
        val keep = BooleanArray(n); keep[0] = true; keep[n - 1] = true
        val closed = x(0) == x(n - 1) && y(0) == y(n - 1)
        if (closed) {
            var far = 0; var best = -1.0
            for (i in 1 until n - 1) { val d = (x(i) - x(0)) * (x(i) - x(0)) + (y(i) - y(0)) * (y(i) - y(0)); if (d > best) { best = d; far = i } }
            keep[far] = true
        }
        val tol2 = tol * tol
        val stack = ArrayDeque<IntArray>()
        var last = 0
        for (i in 1 until n) if (keep[i]) { stack.addLast(intArrayOf(last, i)); last = i }
        while (stack.isNotEmpty()) {
            val (a, b) = stack.removeLast()
            if (b - a < 2) continue
            val ax = x(a); val ay = y(a); val vx = x(b) - ax; val vy = y(b) - ay; val l2 = vx * vx + vy * vy
            var idx = -1; var md = -1.0
            for (k in a + 1 until b) {
                val px = x(k) - ax; val py = y(k) - ay
                val d = if (l2 == 0.0) px * px + py * py else { val c = px * vy - py * vx; c * c / l2 }
                if (d > md) { md = d; idx = k }
            }
            if (md > tol2) { keep[idx] = true; stack.addLast(intArrayOf(a, idx)); stack.addLast(intArrayOf(idx, b)) }
        }
        val out = ArrayList<Double>(2 * n)
        for (i in 0 until n) if (keep[i]) { out += x(i); out += y(i) }
        return out
    }

    /** The total cross-section length in the unit square at height [z] (period 1), traced on an [n] x [n] grid. */
    private fun traceLength(n: Int, z: Double): Double {
        val c = 1.0 / n
        val k = 2 * Math.PI
        var len = 0.0
        for (pl in trace(n + 1, n + 1, { it * c }, { it * c }, { k * it }, { k * it }, k * z)) {
            for (q in 2 until pl.size step 2) len += Math.hypot(pl[q] - pl[q - 2], pl[q + 1] - pl[q - 1])
        }
        return len
    }

    /**
     * Marching squares over an [nx] x [ny] node grid for f = sin(u) cos(v) + sin(v) cos(w) + sin(w) cos(u) = 0, u and v
     * the scaled node coordinates, w the scaled height; the pieces joined end to end into polylines (doubles, x y ...).
     * Saddle cells are resolved by the value at the cell's middle.
     */
    private fun trace(nx: Int, ny: Int, xAt: (Int) -> Double, yAt: (Int) -> Double, u: (Double) -> Double, v: (Double) -> Double, w: Double): List<List<Double>> {
        val sw = Math.sin(w); val cw = Math.cos(w)
        val su = DoubleArray(nx); val cu = DoubleArray(nx); val sv = DoubleArray(ny); val cv = DoubleArray(ny)
        for (i in 0 until nx) { val a = u(xAt(i)); su[i] = Math.sin(a); cu[i] = Math.cos(a) }
        for (j in 0 until ny) { val a = v(yAt(j)); sv[j] = Math.sin(a); cv[j] = Math.cos(a) }
        fun f(i: Int, j: Int) = su[i] * cv[j] + sv[j] * cw + sw * cu[i]
        val vals = DoubleArray(nx * ny) { f(it % nx, it / nx) }
        fun at(i: Int, j: Int) = vals[j * nx + i]
        // edge ids: horizontal (i,j)-(i+1,j) = 2 (j nx + i); vertical (i,j)-(i,j+1) = 2 (j nx + i) + 1
        fun hEdge(i: Int, j: Int) = 2L * (j.toLong() * nx + i)
        fun vEdge(i: Int, j: Int) = 2L * (j.toLong() * nx + i) + 1
        fun point(e: Long): DoubleArray {
            val idx = (e / 2).toInt(); val i = idx % nx; val j = idx / nx
            val (i2, j2) = if (e % 2 == 0L) (i + 1) to j else i to (j + 1)
            val a = at(i, j); val b = at(i2, j2)
            val t = if (a == b) 0.5 else (a / (a - b)).coerceIn(0.0, 1.0)
            return doubleArrayOf(xAt(i) + t * (xAt(i2) - xAt(i)), yAt(j) + t * (yAt(j2) - yAt(j)))
        }
        val segA = ArrayList<Long>(); val segB = ArrayList<Long>()
        for (j in 0 until ny - 1) for (i in 0 until nx - 1) {
            val a = at(i, j) > 0; val b = at(i + 1, j) > 0; val c = at(i + 1, j + 1) > 0; val d = at(i, j + 1) > 0
            val bottom = hEdge(i, j); val right = vEdge(i + 1, j); val top = hEdge(i, j + 1); val left = vEdge(i, j)
            val crossed = ArrayList<Long>(4)
            if (a != b) crossed += bottom
            if (b != c) crossed += right
            if (c != d) crossed += top
            if (d != a) crossed += left
            when (crossed.size) {
                2 -> { segA += crossed[0]; segB += crossed[1] }
                4 -> {
                    // a saddle: the middle's sign says which corners connect
                    val mid = (at(i, j) + at(i + 1, j) + at(i + 1, j + 1) + at(i, j + 1)) / 4 > 0
                    if (mid == a) { segA += bottom; segB += right; segA += top; segB += left }
                    else { segA += bottom; segB += left; segA += top; segB += right }
                }
            }
        }
        // join the pieces: each crossed edge is shared by at most two cells
        val byEdge = HashMap<Long, IntArray>()
        for (s in segA.indices) for (e in longArrayOf(segA[s], segB[s])) {
            val cur = byEdge[e]
            byEdge[e] = if (cur == null) intArrayOf(s, -1) else intArrayOf(cur[0], s)
        }
        val used = BooleanArray(segA.size)
        val out = ArrayList<List<Double>>()
        fun other(e: Long, s: Int): Int { val p = byEdge.getValue(e); return if (p[0] == s) p[1] else p[0] }
        fun walk(start: Int, fromEdge: Long): List<Double> {
            val pts = ArrayList<Double>()
            var s = start; var e = fromEdge
            point(e).let { pts += it[0]; pts += it[1] }
            while (s >= 0 && !used[s]) {
                used[s] = true
                val next = if (segA[s] == e) segB[s] else segA[s]
                point(next).let { pts += it[0]; pts += it[1] }
                e = next; s = other(e, s)
            }
            return pts
        }
        // open curves first (from an edge used once), then closed loops
        for (s in segA.indices) if (!used[s]) {
            val ea = segA[s]; val eb = segB[s]
            val start = when { byEdge.getValue(ea)[1] == -1 -> ea; byEdge.getValue(eb)[1] == -1 -> eb; else -> null } ?: continue
            out += walk(s, start)
        }
        for (s in segA.indices) if (!used[s]) out += walk(s, segA[s])
        return out
    }
}
