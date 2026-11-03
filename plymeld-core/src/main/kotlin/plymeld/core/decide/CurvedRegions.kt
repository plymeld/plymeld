package plymeld.core.decide

import plymeld.mesh.TopSurface
import plymeld.mesh.TriangleSoup
import java.util.Locale

/**
 * The curved-top survey: where a curved band could cover a top of the posed model, region by region, and why not
 * elsewhere.
 *
 * On a grid of [Params.cellMm] over the footprint: the top's height t and slope (TopSurface, exact on the mesh, sampled at
 * cell centers). A **cap** is a set of cells sloping more than [FLAT_DEG] (flat tops need no curve) and no steeper than the
 * nozzle's angle, joined where the top is continuous between neighbors (a height change no steeper than the nozzle's
 * cone across one cell: a roof's edge over a deck is a cliff, not a join). For each cap, a **band**: a start zs; the piece
 * F of the top above zs that holds the cap (the cells whose top is above zs, 8-connected to the cap); anything in F rising
 * above the cap's own peak left out, flat (a chimney through a roof), its edge standing at the peak so the warp is
 * nothing there; over the rest the surface T the band's layers follow, the top dilated by the nozzle's cone
 * (T(p) = max over q of t(q) - tan(angle) x |p - q|): the top itself where it is gentle, staying above it where it
 * steepens (a rounded top's rim), so no layer is steeper than the nozzle allows. A band is possible when the top
 * layer's rise (max T - min T) is within the nozzle's height and the thinnest real layer, [Params.layerMm] x
 * (min T - zs) / (max T - zs), is at least the tool's thinnest. Of the starts scanned (from just under the cap's peak down
 * to [SCAN_BELOW_MM] under its lowest point, every [Params.stepMm]), the one whose layers follow the most of the cap is
 * kept (ties: the higher start, fewer layers curved). A cap with no possible start is rejected with the rule that
 * bound and how close the nearest start came.
 */
object CurvedRegions {
    /** A top flatter than this (deg) needs no curve. plymeld's choice: a flat top's facets are rarely exactly 0. */
    const val FLAT_DEG = 1.0
    /** How far under a cap's lowest point starts are scanned (mm). plymeld's choice: a search bound, not a print value. */
    const val SCAN_BELOW_MM = 10.0
    /** T within this of the top counts as following it (mm). plymeld's choice: under any layer. */
    const val FOLLOW_MM = 0.01
    /**
     * The chamfer kernel's largest overstatement of a distance (16 directions, steps (1,0), (1,1), (2,1)): about 1.4 %. The
     * cone's slope is divided by it, so the grid's T is never steeper than the nozzle allows.
     */
    const val CHAMFER_ERR = 1.014
    /**
     * The margin, as a share of slope, between the nozzle's angle and the angle the caps and the dilated surface are held to
     * ([Params.surveyDeg]: 41.0 deg on a 45 deg nozzle). Read bilinearly between the grid's cells, a surface held to a slope at
     * them is steeper where it is convex, by a share falling as one over the distance from a peak (a sampled cone: sqrt 2 at
     * its tip, 1.4 % over the 5 % margin six cells out), and [limitCorners] (at the nozzle's own angle) raises it there; the
     * raise comes back down to the surface only by the margin's slope per cell. Measured on a cone 20 cells out (
     * a replay of the rule): at 5 % the raise never came down (1 mm at the edge, however damped); at 15 %,
     * damped, up to 0.041 mm within a cell of the tip and none beyond eight; at 30 %, 0.02 mm within one cell. plymeld's
     * choice: 15 %, slopes from 41 to 45 deg printed stair-stepped against a top rounded over a millimetre at a sharp tip.
     */
    const val INTERP_ERR = 1.15

    class Params(val angleDeg: Double, val nozzleHeightMm: Double, val layerMm: Double, val minLayerMm: Double,
                 val cellMm: Double = 0.25, val stepMm: Double = 0.05,
                 /**
                  * The lowest start scanned (mm above the model's bottom): the first layer's top, under which an island cannot
                  * start (its layers begin on a flat one). 0 = no bound (the survey alone). the scan reached 0.05 mm
                  * and a dome on an 8 mm cylinder got a start at 0.08, under its 0.28 mm first layer, which the slicer refused.
                  */
                 val minStartMm: Double = 0.0) {
        init {
            require(angleDeg > FLAT_DEG && angleDeg < 90.0) { "the nozzle's angle is between $FLAT_DEG and 90 deg: $angleDeg" }
            require(nozzleHeightMm > 0 && layerMm > 0 && minLayerMm > 0 && cellMm > 0 && stepMm > 0) { "the survey's lengths are positive" }
        }
        /** The angle the caps and the dilated surface are held to: the nozzle's, its slope less [INTERP_ERR]. */
        val surveyDeg: Double get() = Math.toDegrees(Math.atan(Math.tan(Math.toRadians(angleDeg)) / INTERP_ERR))
    }

    /** A cap: its cells (grid indices i * ny + j), area, and lowest and highest top above the model's bottom (mm). */
    class Cap(val id: Int, val cells: IntArray, val areaMm2: Double, val zLoMm: Double, val zHiMm: Double)

    /**
     * A cap's band: its start and top (the highest T) above the model's bottom, the top layer's rise, the thinnest layer, the
     * cap's area its layers follow exactly, the band's area where they do not (the fade: flat tops in it included), the
     * band's cells and T over them.
     */
    class Band(val cap: Cap, val startMm: Double, val topMm: Double, val riseMm: Double, val thinnestMm: Double,
               val followedMm2: Double, val fadedMm2: Double, val cells: IntArray, val surface: DoubleArray) {
        override fun toString() = "cap %d (%.1f mm2, z %.2f..%.2f): band from %.2f to %.2f mm, rise %.2f mm, thinnest %.3f mm, follows %.1f mm2, fades over %.1f mm2"
            .format(Locale.ROOT, cap.id, cap.areaMm2, cap.zLoMm, cap.zHiMm, startMm, topMm, riseMm, thinnestMm, followedMm2, fadedMm2)
    }

    /** A cap with no band, and why: the rule that bound, with the nearest the scan came to meeting it. */
    class Rejected(val cap: Cap, val why: String)

    class Result(val nx: Int, val ny: Int, val x0: Double, val y0: Double, val cellMm: Double, val zBottom: Double,
                 /** The top above the model's bottom per cell (NaN outside the footprint). */
                 val top: DoubleArray, val caps: List<Cap>, val bands: List<Band>, val rejected: List<Rejected>,
                 /** Pairs of bands whose cells overlap: one column takes one warp, so they must be merged or one dropped. */
                 val overlaps: List<Pair<Band, Band>>,
                 /** The top's slope per cell (degrees from horizontal; NaN outside the footprint). */
                 val slope: DoubleArray = DoubleArray(0))

    /**
     * [withBands]: also each cap's band by the proportional warp (a report); the islands read the caps alone (on a large
     * multi-part model the bands take most of a survey, and surveying them per candidate in the islands' decision would take
     * over 30 min).
     */
    fun survey(soup: TriangleSoup, p: Params, withBands: Boolean = true): Result {
        val b = soup.bounds()
        val z0 = b[2]
        val cell = p.cellMm
        val nx = Math.ceil((b[3] - b[0]) / cell).toInt() + 1; val ny = Math.ceil((b[4] - b[1]) / cell).toInt() + 1
        val ts = TopSurface.of(soup, plymeld.core.IncrementalSlicer.UP_NZ)
        val t = DoubleArray(nx * ny); val slope = DoubleArray(nx * ny)
        for (i in 0 until nx) for (j in 0 until ny) {
            val x = b[0] + (i + 0.5) * cell; val y = b[1] + (j + 0.5) * cell
            val h = ts.top(x, y)
            t[i * ny + j] = if (h.isNaN()) Double.NaN else h - z0
            slope[i * ny + j] = ts.slopeDeg(x, y)
        }
        val k = Math.tan(Math.toRadians(p.surveyDeg))
        val caps = caps(t, slope, nx, ny, cell, p.surveyDeg, k)
        var bands = ArrayList<Band>(); val rejected = ArrayList<Rejected>()
        if (!withBands) return Result(nx, ny, b[0], b[1], cell, z0, t, caps, bands, rejected, emptyList(), slope)
        val kGrid = k / CHAMFER_ERR
        for (c in caps) bandOf(c, t, nx, ny, cell, kGrid, p).let { r -> if (r is Band) bands += r else rejected += r as Rejected }
        // one column takes one warp: bands sharing cells are merged (their caps one region, its band worked out again);
        // where the merged region has no band, the one following most of its cap is kept and those it overlaps dropped
        var nextId = caps.size + 1
        while (true) {
            val groups = overlapGroups(bands)
            if (groups.all { it.size == 1 }) break
            val next = ArrayList<Band>()
            for (g in groups) {
                if (g.size == 1) { next += g[0]; continue }
                val cells = g.flatMap { it.cap.cells.asList() }.distinct().toIntArray()
                val merged = Cap(nextId++, cells, cells.size * cell * cell, g.minOf { it.cap.zLoMm }, g.maxOf { it.cap.zHiMm })
                val r = bandOf(merged, t, nx, ny, cell, kGrid, p)
                if (r is Band) next += r
                else {
                    val keep = g.maxByOrNull { it.followedMm2 }!!
                    next += keep
                    for (o in g) if (o !== keep) rejected += Rejected(o.cap, "its band overlaps the band of cap ${keep.cap.id} (which follows more of its cap), and the two merged have none: ${(r as Rejected).why}")
                }
            }
            if (next.size == bands.size) { bands = next; break }
            bands = next
        }
        return Result(nx, ny, b[0], b[1], cell, z0, t, caps, bands, rejected, overlapGroups(bands).filter { it.size > 1 }.flatMap { g -> g.zipWithNext() }, slope)
    }

    /**
     * A cap's island printed in its own layers with the SHIFTED warp:
     * above [startMm] the island (the cells whose top is above it, 8-connected to the cap) is its own stack; its layers dip
     * by D = min(top of the band - T, the nozzle's height) (T the top dilated by the nozzle's cone), phased in over the
     * band's height, so on the cap they follow the top exactly and elsewhere stay above it. A layer is then at least
     * 1 - [dipMm] / (top - start) of its own height: [layerMm], the thinnest own layer height that keeps every layer at least
     * the tool's thinnest (at least the model's own layer height, at most the tool's thickest).
     */
    class Island(val cap: Cap, val startMm: Double, val topMm: Double, val dipMm: Double, val layerMm: Double,
                 val followedMm2: Double, val areaMm2: Double,
                 /** The island's cells (i * ny + j) and the surface T over them, in the same order. */
                 val cells: IntArray = IntArray(0), val surface: DoubleArray = DoubleArray(0),
                 /**
                  * The surface T over a window of the grid around the island ([FIELD_MARGIN] cells wider each way, past the grid where the island reaches its edge):
                  * its first cell (i, j) and size, and T per cell (i - i0) * wy + (j - j0). The dip is read over it, so the island's
                  * edge has T on both sides.
                  */
                 val window: IntArray = IntArray(4), val field: DoubleArray = DoubleArray(0),
                 /**
                  * The stair-step the island removes from its top, estimated on the grid (mm³: [stepGain]): each sloped cell's area x
                  * its lean (cos of its slope) x (the flat layers' height less its own there: none where it follows the cap, its own
                  * layer height elsewhere). Negative: its own, thicker layers step more than it saves.
                  */
                 val gainMm3: Double = 0.0,
                 /**
                  * It holds a part taller than the cap (stage 6): that part's column is printed level in the island's layers up to
                  * [topMm], which the flat layers then land on and go on from.
                  */
                 val shared: Boolean = false) {
        override fun toString() = "cap %d (%.1f mm2, z %.2f..%.2f): own layers from %.2f to %.2f mm, dip %.2f mm, layers %.3f mm, follows %.1f mm2 of an island of %.1f mm2, stair-step %+.3f mm3"
            .format(Locale.ROOT, cap.id, cap.areaMm2, cap.zLoMm, cap.zHiMm, startMm, topMm, dipMm, layerMm, followedMm2, areaMm2, -gainMm3)
    }

    /**
     * The shifted-warp survey of [r]'s caps ([soupLayerMm]: the model's own layer height; [maxLayerMm]: the tool's thickest):
     * per cap its best island (the start whose layers follow the most of the cap; ties, the thinnest own layers, then the higher start), or why none.
     * An island holding a part taller than the cap takes it in ([Island.shared], stage 6).
     */
    fun shifted(r: Result, p: Params, soupLayerMm: Double, maxLayerMm: Double): List<Pair<Cap, Any>> = r.caps.map { c -> c to bestIsland(r, p, c, soupLayerMm, maxLayerMm) }

    /** Cap [c]'s best island ([shifted]): the start whose layers follow the most of the cap, or why it has none. */
    fun bestIsland(r: Result, p: Params, c: Cap, soupLayerMm: Double, maxLayerMm: Double): Any {
        var best: Island? = null
        var bestLayer = Double.POSITIVE_INFINITY
        val stop = maxOf(p.stepMm, p.minStartMm, c.zLoMm - SCAN_BELOW_MM)
        // the starts, top down, each worked out on its own (in parallel: they share only the read-only survey; one
        // large model's caps took ~370 s on one thread) and then judged in this order, so the choice and its ties are as before
        val starts = ArrayList<Double>()
        run { var zs = c.zHiMm - p.stepMm; while (zs >= stop) { starts += zs; zs -= p.stepMm } }
        val islands = arrayOfNulls<Any>(starts.size)
        java.util.stream.IntStream.range(0, starts.size).parallel().forEach { k -> islands[k] = islandAt(r, p, c, starts[k], soupLayerMm, limit = false) }
        // the starts compared without limitCorners (its raise is within a cell or a few of a sharp tip), the best one with it
        for (s in islands) {
            when (s) {
                is Island -> {
                    if (s.layerMm < bestLayer) bestLayer = s.layerMm
                    // the most stair-step removed from its top (Island.gainMm3: what the decision weighs, estimated on the grid); of
                    // starts removing as much, the thinnest own layers. Chosen by the cap followed most, one model's caps took starts
                    // whose own layers (0.29 mm against flat 0.08) stepped more than they saved, and no island removed any
                    if (s.layerMm <= maxLayerMm + 1e-9 && (best == null || s.gainMm3 > best.gainMm3 + 1e-9 ||
                            (s.gainMm3 > best.gainMm3 - 1e-9 && s.layerMm < best.layerMm - 1e-9))) best = s
                }
            }
        }
        best?.let { b ->
            if (b.gainMm3 <= 0) return "no start removes stair-step from its top: its own layers step more than it follows (at best %+.3f mm³ at %.2f mm, %.3f mm layers against flat %.3f mm)"
                .format(Locale.ROOT, -b.gainMm3, b.startMm, b.layerMm, soupLayerMm)
            return islandAt(r, p, c, b.startMm, soupLayerMm)
        }
        return if (bestLayer.isInfinite()) "no start leaves the island under the nozzle's height of dip"
            else "every start needs own layers thicker than the tool's thickest (%.3f mm): at best %.3f mm".format(Locale.ROOT, maxLayerMm, bestLayer)
    }

    /**
     * Cap [c]'s island from start [zs] (above the object's bottom; [soupLayerMm]: the model's own layer height): the
     * [Island], with its own layer height (infinite when its dip leaves no layer: the start is too close under the top), or
     * a reason it has none (the cap is all below the start). [limit]: its surface held within the nozzle's angle
     * read between the cells ([limitCorners]); false only to compare starts.
     */
    fun islandAt(r: Result, p: Params, c: Cap, zs: Double, soupLayerMm: Double, limit: Boolean = true): Any {
        val t = r.top; val nx = r.nx; val ny = r.ny; val cell = r.cellMm
        val kGrid = Math.tan(Math.toRadians(p.surveyDeg)) / CHAMFER_ERR
        val inF = BooleanArray(nx * ny); val stack = ArrayDeque<Int>(); val fCells = ArrayList<Int>()
        for (a in c.cells) if (t[a] > zs && !inF[a]) { inF[a] = true; stack.addLast(a) }
        var i0 = nx; var i1 = -1; var j0 = ny; var j1 = -1
        // a part taller than the cap within the island (the 3DBenchy's chimney through its cabin roof): its column is the
        // island's up to the cap's top, printed level there (no dip), and goes on in flat layers above it (Island.shared)
        var shared = false
        while (stack.isNotEmpty()) {
            val a = stack.removeLast(); fCells += a
            if (t[a] > c.zHiMm + 1e-6) shared = true
            val i = a / ny; val j = a % ny
            i0 = minOf(i0, i); i1 = maxOf(i1, i); j0 = minOf(j0, j); j1 = maxOf(j1, j)
            for (di in -1..1) for (dj in -1..1) {
                if (di == 0 && dj == 0) continue
                val ii = i + di; val jj = j + dj
                if (ii < 0 || jj < 0 || ii >= nx || jj >= ny) continue
                val o = ii * ny + jj
                if (!inF[o] && !t[o].isNaN() && t[o] > zs) { inF[o] = true; stack.addLast(o) }
            }
        }
        if (fCells.isEmpty()) return "the cap lies wholly below the start (%.2f mm)".format(Locale.ROOT, zs)
        val capSet = BooleanArray(nx * ny).also { m -> c.cells.forEach { m[it] = true } }
        // the window the surface is dilated over: the island's box and FIELD_MARGIN cells round it, past the grid where the island
        // reaches the object's edge (cells there hold nothing; the dip fades over them). Held to the grid, an island out to the
        // object's edge (a pillow's whole top, taken in since stage 6) had its dip drop to nothing within half a cell of the
        // object's side, and its walls there were warped as steeply (47 deg on a 45 deg nozzle, the check refused it)
        i0 -= FIELD_MARGIN; j0 -= FIELD_MARGIN; i1 += FIELD_MARGIN; j1 += FIELD_MARGIN
        val wx = i1 - i0 + 1; val wy = j1 - j0 + 1
        val src = DoubleArray(wx * wy) { NEG }
        val zTop = c.zHiMm
        // a taller part held at the cap's top: no dip over it, and the dip fades in from its edge no steeper than the nozzle allows
        for (a in fCells) src[(a / ny - i0) * wy + (a % ny - j0)] = minOf(t[a], zTop)
        val tt = dilate(src, wx, wy, kGrid * cell)
        // read between the cells (the dip is bilinear) a sharp peak of the dilated surface is up to sqrt 2 steeper than at them
        // (the Benchy's bow, 49.5 deg on a 45 deg nozzle): raised until every cell's corners are within the angle
        if (limit) limitCorners(tt, wx, wy, Math.tan(Math.toRadians(p.angleDeg)) * cell)
        var dip = 0.0; var followed = 0
        for (a in fCells) {
            val w = (a / ny - i0) * wy + (a % ny - j0)
            val d = minOf(zTop - tt[w], p.nozzleHeightMm)
            dip = maxOf(dip, d)
            if (capSet[a] && tt[w] - t[a] < FOLLOW_MM && zTop - t[a] <= p.nozzleHeightMm + 1e-9) followed++
        }
        val factor = 1.0 - dip / (zTop - zs)
        val layer = if (factor <= 0) Double.POSITIVE_INFINITY else maxOf(soupLayerMm, p.minLayerMm / factor)
        val cellsArr = fCells.toIntArray()
        val surf = DoubleArray(cellsArr.size) { q -> val a = cellsArr[q]; tt[(a / ny - i0) * wy + (a % ny - j0)] }
        // the stair-step it removes from its top (Island.gainMm3): its sloped cells' area x lean x (flat layer height less its own
        // there: none where it follows the cap, its own layer height thinned by the dip elsewhere)
        var gain = 0.0
        if (layer.isFinite() && r.slope.isNotEmpty()) for (a in fCells) {
            val sl = r.slope[a]
            // a taller part's top is above the island, printed in flat layers
            if (sl.isNaN() || sl <= FLAT_DEG || t[a] > zTop + 1e-6) continue
            val w = (a / ny - i0) * wy + (a % ny - j0)
            val follows = capSet[a] && tt[w] - t[a] < FOLLOW_MM && zTop - t[a] <= p.nozzleHeightMm + 1e-9
            val own = if (follows) 0.0 else layer * (1.0 - minOf(zTop - tt[w], p.nozzleHeightMm).coerceAtLeast(0.0) / (zTop - zs))
            gain += cell * cell * Math.cos(Math.toRadians(sl)) * (soupLayerMm - own)
        } else if (!layer.isFinite()) gain = Double.NEGATIVE_INFINITY
        return Island(c, zs, zTop, dip, layer, followed * cell * cell, fCells.size * cell * cell, cellsArr, surf, intArrayOf(i0, j0, wx, wy), tt, gain, shared)
    }

    /**
     * Raises [f] ([wx] x [wy] cells, (i, j) at i * wy + j) until, read bilinearly, it is nowhere steeper than [maxStep] per cell:
     * a bilinear cell is steepest at a corner, where its slope is the differences along the two edges meeting there, so every
     * grid point's four corners (its x and y neighbor each way) are held to hypot(dx, dy) <= [maxStep]. A corner over it has
     * its lowest point raised to meet it (the others as they are); sweeps until none is over (1e-9) or [LIMIT_SWEEPS]. Only
     * raising, so the surface stays at or above the top it was dilated from (the layers never cut into the model); a sharp peak
     * comes out rounded. Its own slope at the cells within [maxStep] (the dilation's), no raise is needed but at such corners.
     */
    internal fun limitCorners(f: DoubleArray, wx: Int, wy: Int, maxStep: Double) {
        val m2 = maxStep * maxStep
        for (sweep in 0 until LIMIT_SWEEPS + LIMIT_EXACT_SWEEPS) {
            // each corner fixed by LIMIT_DAMP of what it needs per sweep, so the corners round a peak share the raise (fixed whole,
            // each corner as if alone, the raise grew outwards: 0.64 mm twenty cells out on a cone at a 15 % margin, against
            // none beyond eight damped); whole in the last sweeps, so it ends within the limit
            val damp = if (sweep < LIMIT_SWEEPS) LIMIT_DAMP else 1.0
            fun raise(k: Int, to: Double) { if (to > f[k]) f[k] += (to - f[k]) * damp }
            var changed = false
            for (i in 0 until wx) for (j in 0 until wy) for (sx in intArrayOf(-1, 1)) for (sy in intArrayOf(-1, 1)) {
                val ib = i + sx; val jd = j + sy
                if (ib !in 0 until wx || jd !in 0 until wy) continue
                val ia = i * wy + j; val ibk = ib * wy + j; val idk = i * wy + jd
                val a = f[ia]; val b = f[ibk]; val d = f[idk]
                val h2 = (b - a) * (b - a) + (d - a) * (d - a)
                if (h2 <= m2 * (1 + 1e-9)) continue
                when {
                    // a peak's corner: both edges fall from it, and both are shortened alike (raising one alone to the limit, with
                    // the other still steep, lifted it most of a step and the whole cone with it, 2.2 mm twelve cells out)
                    b <= a && d <= a -> { val s = maxStep / Math.sqrt(h2); raise(ibk, a - (a - b) * s); raise(idk, a - (a - d) * s) }
                    // a pit's corner: both rise from it, and it is raised to where both meet the limit
                    b >= a && d >= a -> {
                        val disc = 2 * m2 - (b - d) * (b - d)
                        raise(ia, if (disc >= 0) ((b + d) - Math.sqrt(disc)) / 2 else maxOf(b, d) - maxStep)
                    }
                    // one rises, one falls: the falling one raised to meet the limit (to the corner's own height, and the corner up
                    // to a step under the rising one, if the rise alone is over it)
                    else -> {
                        val hiK = if (b > a) ibk else idk; val loK = if (b > a) idk else ibk
                        val rise = f[hiK] - a
                        if (rise < maxStep) raise(loK, a - Math.sqrt(m2 - rise * rise))
                        else { raise(loK, a); raise(ia, f[hiK] - maxStep) }
                    }
                }
                changed = true
            }
            if (!changed) return
        }
    }
    /**
     * [limitCorners]' damped sweeps at most, the share of a corner's need each fixes, and the whole sweeps after them. plymeld's
     * choice: a cone's tip settled in 117 sweeps at 0.2 (0.05: the same raise in 496).
     */
    const val LIMIT_SWEEPS = 2000
    const val LIMIT_DAMP = 0.2
    const val LIMIT_EXACT_SWEEPS = 200

    /** The point a plan names cap [c] of survey [r] by (CurvedIsland.Lock): the center of its highest cell, from the surveyed object's corner (mm). */
    fun seedOf(r: Result, c: Cap): Pair<Double, Double> {
        val a = c.cells.maxByOrNull { r.top[it] } ?: error("cap ${c.id} has no cells")
        return (a / r.ny + 0.5) * r.cellMm to (a % r.ny + 0.5) * r.cellMm
    }

    /** How many cells the surface an island's dip is read over reaches beyond the island each way: the island's area takes one more ring (CurvedIsland.covers), and one for the bilinear read beyond it. */
    const val FIELD_MARGIN = 2

    /** [bands] grouped where their cells overlap (transitively). */
    private fun overlapGroups(bands: List<Band>): List<List<Band>> {
        val owner = HashMap<Int, Int>()
        val parent = IntArray(bands.size) { it }
        fun find(a: Int): Int { var x = a; while (parent[x] != x) { parent[x] = parent[parent[x]]; x = parent[x] }; return x }
        for ((n, b) in bands.withIndex()) for (a in b.cells) {
            val o = owner.putIfAbsent(a, n)
            if (o != null) { val ra = find(o); val rb = find(n); if (ra != rb) parent[ra] = rb }
        }
        return bands.indices.groupBy { find(it) }.values.map { ix -> ix.map { bands[it] } }
    }

    /** The caps: cells sloping FLAT_DEG..angle, 4-connected where the top is continuous between them. */
    private fun caps(t: DoubleArray, slope: DoubleArray, nx: Int, ny: Int, cell: Double, angleDeg: Double, k: Double): List<Cap> {
        val isCap = BooleanArray(nx * ny) { !t[it].isNaN() && slope[it] > FLAT_DEG && slope[it] <= angleDeg }
        val parent = IntArray(nx * ny) { it }
        fun find(a: Int): Int { var x = a; while (parent[x] != x) { parent[x] = parent[parent[x]]; x = parent[x] }; return x }
        val lim = k * cell * 1.01
        for (i in 0 until nx) for (j in 0 until ny) {
            val a = i * ny + j
            if (!isCap[a]) continue
            if (i + 1 < nx) { val o = a + ny; if (isCap[o] && Math.abs(t[a] - t[o]) <= lim) { val ra = find(a); val ro = find(o); if (ra != ro) parent[ra] = ro } }
            if (j + 1 < ny) { val o = a + 1; if (isCap[o] && Math.abs(t[a] - t[o]) <= lim) { val ra = find(a); val ro = find(o); if (ra != ro) parent[ra] = ro } }
        }
        val groups = LinkedHashMap<Int, ArrayList<Int>>()
        for (a in 0 until nx * ny) if (isCap[a]) groups.getOrPut(find(a)) { ArrayList() }.add(a)
        return groups.values.mapIndexed { n, cs ->
            var lo = Double.POSITIVE_INFINITY; var hi = Double.NEGATIVE_INFINITY
            for (a in cs) { lo = minOf(lo, t[a]); hi = maxOf(hi, t[a]) }
            Cap(n + 1, cs.toIntArray(), cs.size * cell * cell, lo, hi)
        }.sortedByDescending { it.areaMm2 }
    }

    /** The best band of [c], or why it has none. */
    private fun bandOf(c: Cap, t: DoubleArray, nx: Int, ny: Int, cell: Double, kGrid: Double, p: Params): Any {
        val capSet = BooleanArray(nx * ny).also { m -> c.cells.forEach { m[it] = true } }
        var best: Band? = null
        var bestRise = Double.POSITIVE_INFINITY; var bestThin = 0.0
        var zs = c.zHiMm - p.stepMm
        val stop = maxOf(p.stepMm, p.minStartMm, c.zLoMm - SCAN_BELOW_MM)
        while (zs >= stop) {
            // F: the cells whose top is above zs, 8-connected to the cap's cells above zs
            val inF = BooleanArray(nx * ny)
            val stack = ArrayDeque<Int>()
            for (a in c.cells) if (t[a] > zs && !inF[a]) { inF[a] = true; stack.addLast(a) }
            var i0 = nx; var i1 = -1; var j0 = ny; var j1 = -1
            val fCells = ArrayList<Int>()
            while (stack.isNotEmpty()) {
                val a = stack.removeLast(); fCells += a
                val i = a / ny; val j = a % ny
                i0 = minOf(i0, i); i1 = maxOf(i1, i); j0 = minOf(j0, j); j1 = maxOf(j1, j)
                for (di in -1..1) for (dj in -1..1) {
                    if (di == 0 && dj == 0) continue
                    val ii = i + di; val jj = j + dj
                    if (ii < 0 || jj < 0 || ii >= nx || jj >= ny) continue
                    val o = ii * ny + jj
                    if (!inF[o] && !t[o].isNaN() && t[o] > zs) { inF[o] = true; stack.addLast(o) }
                }
            }
            if (fCells.isEmpty()) { zs -= p.stepMm; continue }
            // the window, one kernel reach wider (the dilation reads neighbors two cells away)
            val wx = i1 - i0 + 1; val wy = j1 - j0 + 1
            val src = DoubleArray(wx * wy) { NEG }
            val band = BooleanArray(wx * wy)
            for (a in fCells) {
                val i = a / ny - i0; val j = a % ny - j0
                // taller than the cap's peak: left out, flat; its edge stands at the peak (the warp is nothing there)
                if (t[a] > c.zHiMm + 1e-6) src[i * wy + j] = c.zHiMm
                else { src[i * wy + j] = t[a]; band[i * wy + j] = true }
            }
            val tt = dilate(src, wx, wy, kGrid * cell)
            var maxT = NEG; var minT = Double.POSITIVE_INFINITY; var n = 0
            for (w in band.indices) if (band[w]) { maxT = maxOf(maxT, tt[w]); minT = minOf(minT, tt[w]); n++ }
            if (n == 0) { zs -= p.stepMm; continue }
            val rise = maxT - minT
            val thin = p.layerMm * (minT - zs) / (maxT - zs)
            if (rise < bestRise) bestRise = rise
            if (rise <= p.nozzleHeightMm + 1e-9 && thin > bestThin) bestThin = thin
            if (rise <= p.nozzleHeightMm + 1e-9 && thin >= p.minLayerMm - 1e-9) {
                var followed = 0; val cells = IntArray(n); val surf = DoubleArray(n); var q = 0
                for (w in band.indices) if (band[w]) {
                    val a = (w / wy + i0) * ny + (w % wy + j0)
                    cells[q] = a; surf[q] = tt[w]; q++
                    if (capSet[a] && tt[w] - t[a] < FOLLOW_MM) followed++
                }
                val fol = followed * cell * cell
                if (best == null || fol > best.followedMm2 + 1e-9)
                    best = Band(c, zs, maxT, rise, thin, fol, n * cell * cell - fol, cells, surf)
            }
            zs -= p.stepMm
        }
        return best ?: Rejected(c, when {
            bestRise > p.nozzleHeightMm -> "every start's top layer rises more than the nozzle's height (%.2f mm): at best %.2f mm".format(Locale.ROOT, p.nozzleHeightMm, bestRise)
            else -> "every start that keeps the rise within the nozzle's height leaves a layer thinner than the tool's thinnest (%.3f mm): at best %.3f mm".format(Locale.ROOT, p.minLayerMm, bestThin)
        })
    }

    private const val NEG = -1e18

    /**
     * T = max over q of src(q) - k |p - q| over a [wx] x [wy] window ([kStep]: k x one cell), by a forward and a backward
     * raster pass with the 16-direction chamfer steps (1,0), (1,1), (2,1); NEG stays NEG where nothing reaches.
     */
    fun dilate(src: DoubleArray, wx: Int, wy: Int, kStep: Double): DoubleArray {
        val tt = src.copyOf()
        val d1 = kStep; val d2 = kStep * Math.sqrt(2.0); val d3 = kStep * Math.sqrt(5.0)
        val fwd = arrayOf(intArrayOf(-1, 0), intArrayOf(0, -1), intArrayOf(-1, -1), intArrayOf(-1, 1),
            intArrayOf(-2, -1), intArrayOf(-2, 1), intArrayOf(-1, -2), intArrayOf(-1, 2))
        fun cost(d: IntArray) = when (Math.abs(d[0]) + Math.abs(d[1])) { 1 -> d1; 2 -> d2; else -> d3 }
        val fc = DoubleArray(fwd.size) { cost(fwd[it]) }
        for (i in 0 until wx) for (j in 0 until wy) {
            var v = tt[i * wy + j]
            for (m in fwd.indices) { val ii = i + fwd[m][0]; val jj = j + fwd[m][1]; if (ii in 0 until wx && jj in 0 until wy) v = maxOf(v, tt[ii * wy + jj] - fc[m]) }
            tt[i * wy + j] = v
        }
        for (i in wx - 1 downTo 0) for (j in wy - 1 downTo 0) {
            var v = tt[i * wy + j]
            for (m in fwd.indices) { val ii = i - fwd[m][0]; val jj = j - fwd[m][1]; if (ii in 0 until wx && jj in 0 until wy) v = maxOf(v, tt[ii * wy + jj] - fc[m]) }
            tt[i * wy + j] = v
        }
        return tt
    }
}
