package plymeld.gcode

/**
 * What the head must clear on a move that prints nothing (the bed may move down, or the head up, to get around
 * trickier spots). Z is free on such a move, up to the machine's height: the
 * writer raises the head as far as it takes. This keeps the highest plastic printed so far per cell of [CELL_MM] over the bed
 * (every line the writer emits, its own Z at every point, curved layers included) and answers how high the nozzle tip must
 * be for a straight travel so that, all along it:
 *  - the tip passes over every line within the bore's radius [boreMm] / 2 of it;
 *  - the nozzle's cone ([angleDeg] from horizontal, from the bore's edge up to [nozzleHeightMm]) passes over every line it
 *    reaches: one d further out may stand d x tan(angle) higher than the tip (the model the curved check uses; the nozzle's
 *    flat face is not modeled, so the travel's z-hop is the only margin for it);
 *  - the rest of the head, [nozzleHeightMm] above the tip over the rectangle [reachMm] (towards -X, +X, -Y, +Y), passes over
 *    every line under it.
 * Without an angle, the cone is not counted; without a nozzle height or an outline, the head's body is not.
 * A printing move gets none of this: it is at its own height. Constraints on printing moves (a curved band's rise, one object
 * at a time spacing) stay with the planner and its checks.
 */
class Clearance(bedMm: DoubleArray?, private val boreMm: Double, private val angleDeg: Double?, private val nozzleHeightMm: Double?,
                private val reachMm: DoubleArray?) {
    companion object {
        /** The grid the plastic's height is kept on (mm). plymeld's choice: the check's head grid (CurvedCheck). */
        const val CELL_MM = 0.5
        /** Cells per side of a coarse block, whose maximum answers a rectangle's middle at once. */
        private const val BLOCK = 8
        /** A travel is sampled for the head's body every this far (mm); the rectangle is grown by half of it. */
        private const val BODY_STEP_MM = 2.0
        /** Without the bed's size: a square this far each way from 0 (mm). */
        private const val NO_BED_MM = 500.0
    }

    private val x0 = bedMm?.get(0) ?: -NO_BED_MM
    private val y0 = bedMm?.get(1) ?: -NO_BED_MM
    private val nx = (((bedMm?.get(2) ?: NO_BED_MM) - x0) / CELL_MM).toInt() + 1
    private val ny = (((bedMm?.get(3) ?: NO_BED_MM) - y0) / CELL_MM).toInt() + 1
    private val cells = DoubleArray(nx * ny) { Double.NEGATIVE_INFINITY }
    private val bnx = (nx + BLOCK - 1) / BLOCK
    private val bny = (ny + BLOCK - 1) / BLOCK
    private val blocks = DoubleArray(bnx * bny) { Double.NEGATIVE_INFINITY }

    /** The highest plastic printed anywhere so far (mm); -infinity before the first line. */
    var highest = Double.NEGATIVE_INFINITY
        private set

    private val tan = angleDeg?.let { Math.tan(Math.toRadians(it)) }
    /** How far out from the tip the cone reaches before the head's body takes over (mm). */
    private val coneReach = boreMm / 2 + if (tan != null && nozzleHeightMm != null && tan > 0) nozzleHeightMm / tan else 0.0
    /** The cone's cells about the tip: (dx, dy, how much higher plastic there may stand than the tip). */
    private val stencil: List<Triple<Int, Int, Double>> = run {
        val r = Math.ceil(coneReach / CELL_MM).toInt()
        val out = ArrayList<Triple<Int, Int, Double>>()
        for (j in -r..r) for (i in -r..r) {
            // the cell's nearest point to the tip, so a line anywhere in it is counted at its closest
            val d = Math.max(0.0, Math.hypot(i.toDouble(), j.toDouble()) * CELL_MM - CELL_MM * Math.sqrt(0.5))
            if (d > coneReach) continue
            val over = if (d <= boreMm / 2 || tan == null) 0.0 else (d - boreMm / 2) * tan
            if (tan == null && d > boreMm / 2) continue
            out += Triple(i, j, over)
        }
        out
    }

    private fun cx(x: Double) = ((x - x0) / CELL_MM).toInt()
    private fun cy(y: Double) = ((y - y0) / CELL_MM).toInt()

    private fun mark(x: Double, y: Double, z: Double) {
        val i = cx(x); val j = cy(y)
        if (i < 0 || j < 0 || i >= nx || j >= ny) return
        val k = i + nx * j
        if (z > cells[k]) {
            cells[k] = z
            val b = i / BLOCK + bnx * (j / BLOCK)
            if (z > blocks[b]) blocks[b] = z
            if (z > highest) highest = z
        }
    }

    /** A printed line (µm), at [zUm] or at its own Z per vertex ([zs]). */
    fun add(xy: LongArray, closed: Boolean, zUm: Long, zs: LongArray?) {
        val m = xy.size / 2
        val segs = if (closed) m else m - 1
        for (s in 0 until segs) {
            val t = (s + 1) % m
            val ax = xy[2 * s] / 1000.0; val ay = xy[2 * s + 1] / 1000.0; val bx = xy[2 * t] / 1000.0; val by = xy[2 * t + 1] / 1000.0
            val az = (zs?.get(s) ?: zUm) / 1000.0; val bz = (zs?.get(t) ?: zUm) / 1000.0
            val n = Math.max(1, Math.ceil(Math.hypot(bx - ax, by - ay) / (CELL_MM / 2)).toInt())
            for (k in 0..n) { val f = k.toDouble() / n; mark(ax + (bx - ax) * f, ay + (by - ay) * f, az + (bz - az) * f) }
        }
        if (m == 1) mark(xy[0] / 1000.0, xy[1] / 1000.0, zUm / 1000.0)
    }

    /** The highest plastic in cells [i0..i1] x [j0..j1] (clipped to the bed): whole blocks at once, cells at the edges. */
    private fun rectMax(i0: Int, j0: Int, i1: Int, j1: Int): Double {
        val a = Math.max(0, i0); val b = Math.max(0, j0); val c = Math.min(nx - 1, i1); val d = Math.min(ny - 1, j1)
        if (a > c || b > d) return Double.NEGATIVE_INFINITY
        var best = Double.NEGATIVE_INFINITY
        for (bj in b / BLOCK..d / BLOCK) for (bi in a / BLOCK..c / BLOCK) {
            val bv = blocks[bi + bnx * bj]
            if (bv <= best) continue
            val ci0 = bi * BLOCK; val cj0 = bj * BLOCK
            if (ci0 >= a && cj0 >= b && ci0 + BLOCK - 1 <= c && cj0 + BLOCK - 1 <= d) { best = bv; continue }
            for (j in Math.max(b, cj0)..Math.min(d, cj0 + BLOCK - 1)) for (i in Math.max(a, ci0)..Math.min(c, ci0 + BLOCK - 1)) {
                val v = cells[i + nx * j]
                if (v > best) best = v
            }
        }
        return best
    }

    private fun rectMaxMm(xa: Double, ya: Double, xb: Double, yb: Double) = rectMax(cx(Math.min(xa, xb)), cy(Math.min(ya, yb)), cx(Math.max(xa, xb)), cy(Math.max(ya, yb)))

    /**
     * The lowest the nozzle tip may be for a straight travel from (ax, ay) to (bx, by) (mm) over what is printed so far, or
     * [floor] when that is higher (the height the caller travels at anyway, less its z-hop). Rectangles about the whole travel
     * answer first: when nothing near it stands above the floor (every layer of a planar print), nothing is walked.
     */
    fun need(ax: Double, ay: Double, bx: Double, by: Double, floor: Double): Double {
        var needed = floor
        if (highest <= floor) return needed
        val len = Math.hypot(bx - ax, by - ay)
        // the tip and its cone
        if (rectMaxMm(Math.min(ax, bx) - coneReach, Math.min(ay, by) - coneReach, Math.max(ax, bx) + coneReach, Math.max(ay, by) + coneReach) > floor) {
            val n = Math.max(1, Math.ceil(len / CELL_MM).toInt())
            for (k in 0..n) {
                val f = k.toDouble() / n
                val i = cx(ax + (bx - ax) * f); val j = cy(ay + (by - ay) * f)
                for ((di, dj, over) in stencil) {
                    val ii = i + di; val jj = j + dj
                    if (ii < 0 || jj < 0 || ii >= nx || jj >= ny) continue
                    val v = cells[ii + nx * jj] - over
                    if (v > needed) needed = v
                }
            }
        }
        // the head's body
        val h = nozzleHeightMm; val r = reachMm
        if (h != null && r != null && highest - h > needed) {
            val g = BODY_STEP_MM / 2
            if (rectMaxMm(Math.min(ax, bx) - r[0] - g, Math.min(ay, by) - r[2] - g, Math.max(ax, bx) + r[1] + g, Math.max(ay, by) + r[3] + g) - h > needed) {
                val n = Math.max(1, Math.ceil(len / BODY_STEP_MM).toInt())
                for (k in 0..n) {
                    val f = k.toDouble() / n
                    val px = ax + (bx - ax) * f; val py = ay + (by - ay) * f
                    val v = rectMaxMm(px - r[0] - g, py - r[2] - g, px + r[1] + g, py + r[3] + g) - h
                    if (v > needed) needed = v
                }
            }
        }
        return needed
    }
}
