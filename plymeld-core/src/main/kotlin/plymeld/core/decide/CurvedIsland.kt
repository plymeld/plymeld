package plymeld.core.decide

import plymeld.mesh.ShiftWarp

/**
 * A curved island: a cap's island printed in its own layers with the shifted warp. Above [startMm] (above the object's
 * bottom) the island's area ([cells] of the grid) is taken out of the flat layers and printed in its own layers,
 * [layerMm] thick in warped space, up to [topMm]; its layers dip by [dip] (per grid cell, mm), phased in over the band,
 * so on the cap they follow the top.
 * The grid: [nx] x [ny] cells of [cellMm] from ([x0], [y0]), from the posed object's corner (its least x and y, mm), the
 * frame a plan locks it in; the slicer [placed] it on the bed.
 */
class CurvedIsland(val capId: Int, val startMm: Double, val topMm: Double, val layerMm: Double,
                   val x0: Double, val y0: Double, val cellMm: Double, val nx: Int, val ny: Int,
                   /** The island's cells (i * ny + j). */
                   val cells: IntArray,
                   /** The dip per island cell, in [cells]' order (mm). */
                   val dip: DoubleArray,
                   /**
                    * The dip over a window of the grid round the island (CurvedRegions.Island.window: first cell [fi0], [fj0], [fwx] x
                    * [fwy] cells; [fdip] per cell (i - fi0) * fwy + (j - fj0)): what [dipAt] reads, so the island's edge has a dip on
                    * both sides.
                    */
                   val fi0: Int, val fj0: Int, val fwx: Int, val fwy: Int, val fdip: DoubleArray,
                   /** What a plan calls it (plan.object.<key>.island.<key>) and the point on its cap it names it by (from the corner, mm). */
                   val key: String = "", val seedXMm: Double = Double.NaN, val seedYMm: Double = Double.NaN,
                   /**
                    * It holds a part taller than its cap (CurvedRegions.Island.shared, stage 6): its layers print that part's column level up
                    * to [topMm], where the flat layers land a layer and go on; its loops leave the flat layers up to there only.
                    */
                   val shared: Boolean = false) {
    /**
     * For each cell of the grid grown by one all round ((i + 1) * (ny + 2) + j + 1), whether it or a cell next to it is the
     * island's: what [covers] reads, one lookup a point (a pose search asks it of every sloped face under every plan). It
     * depends on the cells alone, so a [placed] copy shares it; built on first use.
     */
    private var nearCache: BooleanArray? = null
    private fun near(): BooleanArray = nearCache ?: BooleanArray((nx + 2) * (ny + 2)).also { m ->
        for (a in cells) { val i = a / ny; val j = a % ny; for (di in 0..2) for (dj in 0..2) m[(i + di) * (ny + 2) + j + dj] = true }
        nearCache = m
    }

    /** Whether (x, y) lies in the island's area (its cell, or one next to it: the grid's cells are coarser than a wall). */
    fun covers(x: Double, y: Double): Boolean {
        val i = Math.floor((x - x0) / cellMm).toInt(); val j = Math.floor((y - y0) / cellMm).toInt()
        if (i < -1 || i > nx || j < -1 || j > ny) return false
        return near()[(i + 1) * (ny + 2) + j + 1]
    }

    /**
     * Whether loop [lp] (µm) is the island's: most of its points (up to 16 of them, spread along it) in its area. The slicer
     * takes such loops out of the flat layers above the start and prints them in the island's own; the check judges each
     * layer against its own (the flat layers without them, the island's with them only).
     */
    fun holds(lp: plymeld.geom.Polygon): Boolean {
        val n = lp.size
        if (n == 0) return false
        val step = maxOf(1, n / 16)
        var inside = 0; var all = 0
        var i = 0
        while (i < n) { all++; if (covers(lp.x(i) / 1000.0, lp.y(i) / 1000.0)) inside++; i += step }
        return inside * 2 > all
    }

    /**
     * The dip at (x, y): bilinear over the cell centers of the dip's window (0 beyond it, which no point of the island's area
     * reaches). Over the island's cells alone, blended with 0 beyond its edge, its layers tilted there as steeply as the dip
     * over half a cell (52-62 deg at a 3 mm cap's rim: the check's slope rule).
     */
    fun dipAt(x: Double, y: Double): Double {
        val fx = (x - x0) / cellMm - 0.5 - fi0; val fy = (y - y0) / cellMm - 0.5 - fj0
        val i = Math.floor(fx).toInt(); val j = Math.floor(fy).toInt()
        val u = fx - i; val v = fy - j
        fun at(ii: Int, jj: Int): Double = if (ii in 0 until fwx && jj in 0 until fwy) fdip[ii * fwy + jj] else 0.0
        return (1 - u) * (1 - v) * at(i, j) + u * (1 - v) * at(i + 1, j) + (1 - u) * v * at(i, j + 1) + u * v * at(i + 1, j + 1)
    }

    /**
     * The steepest slope of the dip as [dipAt] reads it (degrees): the slope of the island's top layer, where it follows the dip
     * whole (a layer lower down follows a share of it, less steep). Exact: in a cell the bilinear dip's slope in x varies only
     * with y and its slope in y only with x, so it is steepest at one of the cell's corners, where it is the differences along
     * the cell's two edges meeting there.
     */
    fun maxSlopeDeg(): Double {
        var g = 0.0
        for (i in 0 until fwx - 1) for (j in 0 until fwy - 1) {
            val a = fdip[i * fwy + j]; val b = fdip[(i + 1) * fwy + j]; val c = fdip[i * fwy + j + 1]; val d = fdip[(i + 1) * fwy + j + 1]
            g = maxOf(g, Math.hypot(b - a, c - a), Math.hypot(b - a, d - b), Math.hypot(d - c, c - a), Math.hypot(d - c, d - b))
        }
        return Math.toDegrees(Math.atan(g / cellMm))
    }

    /**
     * The island's own layer height in warped space over [spanMm] (its start as printed, the flat top under the plan's start,
     * to its top): the span in equal layers, as near [layerMm] as keeps every layer at least [minLayerMm] where it dips most
     * (1 - dip / span of its height) and at most [maxLayerMm] where it does not dip; null when no whole number of layers does.
     * (Layers of [layerMm] with what is left on top: the remainder layer, dipped, came out 0.06 mm thick.)
     */
    fun ownLayerMm(spanMm: Double, minLayerMm: Double, maxLayerMm: Double): Double? {
        val factor = 1.0 - (dip.maxOrNull() ?: 0.0) / spanMm
        if (factor <= 0) return null
        val lo = maxOf(1, Math.ceil(spanMm / maxLayerMm - 1e-9).toInt())
        val hi = Math.floor(spanMm * factor / minLayerMm + 1e-9).toInt()
        if (lo > hi) return null
        return spanMm / Math.round(spanMm / layerMm).toInt().coerceIn(lo, hi)
    }

    /** The island with its grid moved by ([dx], [dy]): from the object's corner to where the object's corner is placed. */
    fun placed(dx: Double, dy: Double) = CurvedIsland(capId, startMm, topMm, layerMm, x0 + dx, y0 + dy, cellMm, nx, ny, cells, dip, fi0, fj0, fwx, fwy, fdip, key, seedXMm, seedYMm, shared)
        .also { it.nearCache = near() }

    override fun toString() = "island '%s' at (%.2f, %.2f): own layers from %.2f to %.2f mm, %.3f mm thick".format(java.util.Locale.ROOT, key, seedXMm, seedYMm, startMm, topMm, layerMm)

    /** Its warp, with the object's bottom at [bottomMm]. */
    fun warp(bottomMm: Double): ShiftWarp = ShiftWarp(bottomMm + startMm, bottomMm + topMm, cellMm, window()) { x, y -> dipAt(x, y) }

    /** The box the dip is read over (x0, y0, x1, y1, mm), with a cell round it: beyond it the dip is 0. */
    fun window(): DoubleArray = doubleArrayOf(x0 + (fi0 - 1) * cellMm, y0 + (fj0 - 1) * cellMm, x0 + (fi0 + fwx + 1) * cellMm, y0 + (fj0 + fwy + 1) * cellMm)

    /**
     * A plan's lock on an island (plan.object.<key>.island.<key>): a point on its cap ([xMm], [yMm], from the posed object's
     * corner), and optionally its start (from the object's bottom) and its own layer height; unset, the survey's best.
     */
    class Lock(val key: String, val xMm: Double, val yMm: Double, val startMm: Double? = null, val layerMm: Double? = null)

    companion object {
        /** A plan file writes lengths to 1e-6 mm: an island read back may miss its bounds by that (an island at the tool's thickest layer did). */
        const val PLAN_PRECISION_MM = 1e-6

        /** The island the shifted survey found for a cap ([r]: the survey; [island]: its result; [p]: its head). */
        fun of(r: CurvedRegions.Result, island: CurvedRegions.Island, p: CurvedRegions.Params, layerMm: Double = island.layerMm,
               key: String = "", seedXMm: Double = Double.NaN, seedYMm: Double = Double.NaN): CurvedIsland {
            val cells = island.cells
            val dip = DoubleArray(cells.size) { q -> minOf(island.topMm - island.surface[q], p.nozzleHeightMm).coerceAtLeast(0.0) }
            // round the island the dilated surface keeps falling: its dip there held to the island's own deepest (a band shorter
            // than the window's dip was refused by the warp; a minimum keeps the slope within its bound)
            val fdip = DoubleArray(island.field.size) { q -> minOf(island.topMm - island.field[q], p.nozzleHeightMm, island.dipMm).coerceAtLeast(0.0) }
            // the survey's grid starts at the surveyed object's corner, so from the corner it starts at 0
            return CurvedIsland(island.cap.id, island.startMm, island.topMm, layerMm, 0.0, 0.0, r.cellMm, r.nx, r.ny, cells, dip,
                island.window[0], island.window[1], island.window[2], island.window[3], fdip, key, seedXMm, seedYMm, island.shared)
        }

        /**
         * The islands [locks] name on the object of [parts] (as posed): each the cap holding its point (or a cell next to it),
         * from its start (the survey's best unless locked), in its own layer height (the thinnest that keeps every layer at
         * least the tool's thinnest unless locked, within it and [maxLayerMm]). Refused (IllegalArgumentException, saying why)
         * when the point is on no cap, the cap has no island, or two islands share ground.
         */
        fun resolve(parts: List<plymeld.mesh.TriangleSoup>, locks: List<Lock>, p: CurvedRegions.Params, soupLayerMm: Double, maxLayerMm: Double,
                    /** The survey of [parts] with [p], when the caller has made it (the decision, one for every candidate); null = made here. */
                    survey: CurvedRegions.Result? = null): List<CurvedIsland> {
            if (locks.isEmpty()) return emptyList()
            fun f(v: Double) = "%.2f".format(java.util.Locale.ROOT, v)
            val r = survey ?: CurvedRegions.survey(CurvedDepth.merged(parts), p, withBands = false)
            val out = locks.map { l ->
                val at = "island '${l.key}' at (${f(l.xMm)}, ${f(l.yMm)})"
                val cap = capOf(r, l) ?: throw IllegalArgumentException("$at: no cap holds that point (a top sloping %.0f to %.1f deg, under the nozzle angle by the survey's margin, joined where the top is continuous): ".format(java.util.Locale.ROOT, CurvedRegions.FLAT_DEG, p.surveyDeg) +
                    (nearCells(r, l).firstOrNull()?.let { a -> r.top[a].takeIf { !it.isNaN() }?.let { "the top there is at ${f(it)} mm, flat or steeper" } } ?: "it is outside the object's footprint") +
                    "; the points are from the posed object's corner")
                val s = if (l.startMm != null) CurvedRegions.islandAt(r, p, cap, l.startMm, soupLayerMm) else CurvedRegions.bestIsland(r, p, cap, soupLayerMm, maxLayerMm)
                if (s !is CurvedRegions.Island) throw IllegalArgumentException("$at: its cap (z ${f(cap.zLoMm)} to ${f(cap.zHiMm)} mm) has no island" + (if (l.startMm != null) " from ${f(l.startMm)} mm" else "") + ": $s")
                require(!s.layerMm.isInfinite()) { "$at: from ${f(s.startMm)} mm its layers would dip by ${f(s.dipMm)} mm, more than the ${f(s.topMm - s.startMm)} mm to its top: start it lower" }
                // the dilated surface is held within the nozzle angle at every cell's corners (CurvedRegions.limitCorners), so
                // read bilinearly nowhere steeper: refused only when that did not settle
                val steepest = of(r, s, p).maxSlopeDeg()
                require(steepest <= p.angleDeg + 1e-6) { "$at: its top layer would be %.2f deg steep, over the nozzle angle (%.0f deg): its surface could not be held within it".format(java.util.Locale.ROOT, steepest, p.angleDeg) }
                withLayer(r, s, p, l, at, maxLayerMm)
            }
            for (a in out.indices) for (b in a + 1 until out.size) {
                require(out[a].capId != out[b].capId) { "islands '${out[a].key}' and '${out[b].key}' name the same cap" }
                val sa = out[a].cells.toHashSet()
                require(out[b].cells.none { it in sa }) { "islands '${out[a].key}' and '${out[b].key}' share ground (their areas overlap): one column takes one warp; islands sharing ground are not planned yet" }
            }
            return out
        }

        /** The cells round lock [l]'s point in survey [r], its own first, then the four beside it, then the corners. */
        private fun nearCells(r: CurvedRegions.Result, l: Lock): List<Int> {
            val i = Math.floor(l.xMm / r.cellMm).toInt(); val j = Math.floor(l.yMm / r.cellMm).toInt()
            return (-1..1).flatMap { di -> (-1..1).map { dj -> i + di to j + dj } }.sortedBy { (a, b) -> Math.abs(a - i) + Math.abs(b - j) }
                .filter { (a, b) -> a in 0 until r.nx && b in 0 until r.ny }.map { (a, b) -> a * r.ny + b }
        }

        /** The cap of survey [r] holding lock [l]'s point (or a cell next to it), or null. */
        private fun capOf(r: CurvedRegions.Result, l: Lock): CurvedRegions.Cap? = nearCells(r, l).firstNotNullOfOrNull { a -> r.caps.firstOrNull { c -> a in c.cells } }

        /**
         * Survey island [s] (of [r], made with [p]) as lock [l]'s, in its own layer height: the lock's, else the island's (the
         * thinnest that keeps every layer at least the tool's thinnest); refused when thinner than that where it dips most or
         * thicker than [maxLayerMm].
         */
        private fun withLayer(r: CurvedRegions.Result, s: CurvedRegions.Island, p: CurvedRegions.Params, l: Lock, at: String, maxLayerMm: Double): CurvedIsland {
            fun f(v: Double) = "%.2f".format(java.util.Locale.ROOT, v)
            val layer = l.layerMm ?: s.layerMm
            // where they dip most its layers are 1 - dip / (top - start) of their height: at least the tool's thinnest there (a
            // locked height may be finer than the flat layers', the survey's default floor)
            val need = p.minLayerMm / (1.0 - s.dipMm / (s.topMm - s.startMm))
            require(layer >= need - PLAN_PRECISION_MM) { "$at: its own layers %.3f mm thick would be thinner than the tool's thinnest (%.3f mm) where they dip most (%s mm): at least %.3f mm from %s mm"
                .format(java.util.Locale.ROOT, layer, p.minLayerMm, f(s.dipMm), need, f(s.startMm)) }
            require(layer <= maxLayerMm + PLAN_PRECISION_MM) { "$at: its own layers %.3f mm thick are thicker than the tool's thickest (%.3f mm)".format(java.util.Locale.ROOT, layer, maxLayerMm) +
                (if (l.layerMm == null) " (the thinnest that keeps every layer at least the tool's thinnest from ${f(s.startMm)} mm): lock a lower start" else "") }
            return of(r, s, p, layer, l.key, l.xMm, l.yMm)
        }
    }
}
