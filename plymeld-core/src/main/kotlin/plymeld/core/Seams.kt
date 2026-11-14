package plymeld.core

import plymeld.geom.Polygon

/**
 * Where a wall loop starts (plan.seam = "corners"): at a corner or other sharp edge where there is one, the seams
 * aligned. A corner is a turn of the loop of at least plan.technique.corner_seam.corner_angle,
 * measured between the loop's direction a span back and a span ahead of the point (two line widths: a finely faceted
 * curve turns a little at every vertex and is no corner; a rounded inside corner, the wall's offset of a sharp one,
 * still turns its whole angle across it). Wall loops have the material on their left (outer outlines
 * counter-clockwise, holes clockwise), so a right turn is an inside (concave) corner.
 *
 * First of all, a seam goes where the layer below holds the loop up: points on an edge of the layer that hangs over the
 * layer below (the overhang slow-down's measure, [Overhangs.seamOverhangs]) are candidates only when the loop has no other
 * (plan.seam = "back" too: the back-most held-up point). Among the rest:
 * the candidates: the loop's inside corners if it has any, else its outside corners, else every point of it. Seams are
 * aligned layer to layer: the candidate nearest a seam of the same wall on the layer below. With none below (the first
 * layer, a new island), the sharpest corner (turns within [TIE_DEG] counting as equal), ties to the back-most; a loop
 * with no corner then starts at its back-most point ([Planner.seamAtBack]).
 */
object Seams {
    /** The loop rotated to start at its seam, and whether the seam is at a corner. */
    class Placed(val loop: Polygon, val atCorner: Boolean)

    /** Turns within this many degrees count as equally sharp. plymeld's choice. */
    const val TIE_DEG = 10.0
    /**
     * Seam costs within this share of the loop's largest cost count as equal for the least-visible seam (then the point the
     * viewer sees least, a corner, the seam below, the back decide; the same share, of 1, bands the visibility). A share, not an amount: the costs scale with the map (every point of an underside counts 0.3 of
     * itself), and an amount swallowed the gap between a hidden back and a front corner there. plymeld's choice.
     */
    const val TIE_V = 0.05

    /** The loop's turn at each vertex in degrees (+ left, - right), between the points [spanUm] back and ahead along it. */
    fun turns(p: Polygon, spanUm: Double): DoubleArray {
        val n = p.size
        val len = DoubleArray(n) { i -> Math.hypot((p.x((i + 1) % n) - p.x(i)).toDouble(), (p.y((i + 1) % n) - p.y(i)).toDouble()) }
        val span = minOf(spanUm, len.sum() / 4)
        /** The point [d] along the loop from vertex [i] (forward when [dir] is 1, back when -1). */
        fun along(i: Int, d: Double, dir: Int): DoubleArray {
            var k = i; var left = d
            while (true) {
                val e = if (dir > 0) k else (k - 1 + n) % n           // the edge walked next
                val next = if (dir > 0) (k + 1) % n else (k - 1 + n) % n
                if (len[e] >= left) {                                    // ends: the span is at most a quarter of the loop
                    val f = if (len[e] == 0.0) 0.0 else left / len[e]
                    return doubleArrayOf(p.x(k) + (p.x(next) - p.x(k)) * f, p.y(k) + (p.y(next) - p.y(k)) * f)
                }
                left -= len[e]; k = next
            }
        }
        return DoubleArray(n) { i ->
            val b = along(i, span, -1); val a = along(i, span, 1)
            val ix = p.x(i) - b[0]; val iy = p.y(i) - b[1]; val ox = a[0] - p.x(i); val oy = a[1] - p.y(i)
            Math.toDegrees(Math.atan2(ix * oy - iy * ox, ix * ox + iy * oy))
        }
    }

    /**
     * [p] rotated to start at its seam ([cornerDeg] null: no corners, only alignment, else the back). [below]: the seams
     * (x, y in µm, pairs) of the same wall on the layer below; empty: none to align with. [overhangs]: per vertex, whether
     * it lies on an edge that hangs over the layer below ([Overhangs.seamOverhangs]); null: all held up. Held-up points are
     * preferred over overhanging ones before every other preference (a helix whose every loop started at
     * its overhanging end printed a row of beads there); among them the rules above. A loop with no held-up point at all
     * keeps them as if none overhung.
     */
    fun place(p: Polygon, cornerDeg: Double?, spanUm: Double, below: LongArray = LongArray(0), overhangs: BooleanArray? = null, back: Boolean = false,
              /**
               * plan.seam = least_visible: the held-up point the seam measure counts least: its visibility by
               * the map [vis] times [cornerShare] where it is a corner (a turn of at least [cornerDeg] across [spanUm]: seam_scar's corner
               * credit, so the placement and the measure agree; a 30-part model's QR cells, small squares on one face,
               * took mid-edge seams by visibility alone and the smooth rule then scarfed 917 of them); ties (within TIE_V) to the point
               * the viewer sees least first, then to a corner, then to the seam below, then the back.
               */
              leastVisible: Boolean = false, vis: plymeld.core.decide.Visibility? = null, cornerShare: Double = 1.0,
              /**
               * What hides a point nearby and how much the directions it faces count by their quadrant around
               * the view: each vertex's exposure on this loop, layer [layer] of [exposure]; null = every wall open and
               * every way it faces alike (the map's underside term alone).
               */
              exposure: plymeld.core.decide.Exposure? = null, layer: Int = 0,
              /** The shelf (mm) a point of this loop sits on ([SeamScar.shelfMm]); null = none (every point on a wall). */
              shelf: ((Long, Long) -> Double)? = null,
              /** A scarf seam's ramp length (µm) when this loop may get one: a start is seen as its ramp's most-seen point; 0 = point seams. */
              rampUm: Long = 0,
              /** Scarf seams go off corners only (plan.technique.scarf_seam.where = smooth): a corner start keeps a point seam. */
              rampOffCorners: Boolean = false,
              /** A scarf seam's blemish as a share of a point seam's (method.quality.seam.scarf_share), where a start gets a scarf. */
              scarfShare: Double = 1.0,
              /**
               * Whether another part of the object covers a point (x, y in µm) of this loop, where a start gets a point seam
               * however [rampUm] reads (plan.technique.scarf_seam.covered = point; [Planner.near]); null = none is.
               */
              covered: ((Long, Long) -> Boolean)? = null): Placed {
        if (p.size < 3) return Placed(Planner.seamAtBack(p), false)
        val held: (Int) -> Boolean = if (overhangs == null || overhangs.all { it }) { _ -> true } else { i -> !overhangs[i] }
        if (leastVisible && vis != null) {
            val lc = costsOf(p, cornerDeg, spanUm, overhangs, vis, cornerShare, exposure, layer, shelf, rampUm, rampOffCorners, scarfShare, covered)
            val n = p.size
            val seen = lc.seen; val blem = lc.blem
            val pts = lc.points
            fun v(i: Int): Double = seen[i]
            fun corner(i: Int) = lc.corner[i]
            // the cost seam_scar charges this seam (SeamScar.measure): its blemish ([costsOf]) plus a jog from the nearest seam below,
            // its length x the line width when further than a line, all times how much it is seen. Without the jog the placement
            // chased the least-seen point layer by layer and the seams wandered (with what hides a point nearby, the dual Benchy's
            // least-visible seams jogged 1586 mm against the corners' 431, and the measure scored the placement 33 % worse than the
            // back seam)
            val w = spanUm / 2 / 1000.0
            fun jog(i: Int): Double {
                if (below.isEmpty()) return 0.0
                var d = Double.MAX_VALUE
                for (k in 0 until below.size / 2) d = minOf(d, Math.hypot((p.x(i) - below[2 * k]) / 1000.0, (p.y(i) - below[2 * k + 1]) / 1000.0))
                return if (d > w) d * w else 0.0
            }
            val costs = DoubleArray(n) { i -> v(i) * (blem[i] + jog(i)) }
            fun cost(i: Int) = costs[i]
            val least = pts.minOf { cost(it) }
            // the band is a share of the loop's largest seam blemish as seen, not of its largest cost: a far point's jog would
            // widen it to the whole loop and hand every seam to the corner / alignment tie-breaks
            val top = pts.maxOf { v(it) * blem[it] }
            val tied = pts.filter { cost(it) <= least + TIE_V * top }
            // among near-equal costs, the points the viewer sees least first (within TIE_V of the band's most-seen point, so
            // points seen alike stay together for the corner and alignment rules), then a corner, then the alignment: a front
            // corner hides a seam about as well as an open back does (with the quadrant weights 0.80 x the corner share 0.25 =
            // 0.20 against the back's 0.21), and the back takes it (exact ties
            // only: equal shares had put a base's seams on its front recess corner)
            val leastV = tied.minOf { v(it) }
            val vTop = tied.maxOf { v(it) }
            val seenLeast = tied.filter { v(it) <= leastV + TIE_V * vTop }
            val band = seenLeast.filter(::corner).ifEmpty { seenLeast }
            val best = if (below.isEmpty()) band.maxWith(compareBy<Int> { p.y(it) }.thenByDescending { p.x(it) }) else band.minBy { i ->
                var d = Double.MAX_VALUE
                for (k in 0 until below.size / 2) d = minOf(d, Math.hypot((p.x(i) - below[2 * k]).toDouble(), (p.y(i) - below[2 * k + 1]).toDouble()))
                d
            }
            return Placed(rotated(p, best), false)
        }
        if (back) {
            // plan.seam = back: the back-most point, in line with the layer below (a gap found
            // reviewing a slice: placed loop by loop, a gently curved back's seams scattered over ~150 mm): the held-up points
            // within [spanUm] (two line widths) of the loop's back-most one, the one nearest a seam below; none below: the
            // back-most (ties to the left, as Planner.seamAtBack)
            val pts = (0 until p.size).filter(held).ifEmpty { (0 until p.size).toList() }
            val maxY = pts.maxOf { p.y(it) }
            val band = pts.filter { p.y(it) >= maxY - spanUm }
            val best = if (below.isEmpty()) band.maxWith(compareBy<Int> { p.y(it) }.thenByDescending { p.x(it) }) else band.minBy { i ->
                var d = Double.MAX_VALUE
                for (k in 0 until below.size / 2) d = minOf(d, Math.hypot((p.x(i) - below[2 * k]).toDouble(), (p.y(i) - below[2 * k + 1]).toDouble()))
                d
            }
            return Placed(rotated(p, best), false)
        }
        val t = if (cornerDeg == null) null else turns(p, spanUm)
        val corners = if (t == null) emptyList() else t.indices.filter { Math.abs(t[it]) >= cornerDeg!! && held(it) }
        val inside = corners.filter { t!![it] < 0 }
        val pool = inside.ifEmpty { corners }
        val anyOver = overhangs != null && (0 until p.size).any { !held(it) }
        val cands = pool.ifEmpty { (0 until p.size).filter(held) }
        if (below.isNotEmpty()) {
            // aligned: the candidate nearest a seam below
            val best = cands.minBy { i ->
                var d = Double.MAX_VALUE
                for (k in 0 until below.size / 2) d = minOf(d, Math.hypot((p.x(i) - below[2 * k]).toDouble(), (p.y(i) - below[2 * k + 1]).toDouble()))
                d
            }
            return Placed(rotated(p, best), pool.isNotEmpty())
        }
        if (pool.isEmpty()) return Placed(if (anyOver) rotated(p, cands.maxWith(compareBy<Int> { p.y(it) }.thenByDescending { p.x(it) })) else Planner.seamAtBack(p), false)
        val sharpest = pool.maxOf { Math.abs(t!![it]) }
        val best = pool.filter { Math.abs(t!![it]) >= sharpest - TIE_DEG }
            .maxWith(compareBy<Int> { p.y(it) }.thenByDescending { p.x(it) })
        return Placed(rotated(p, best), true)
    }

    /**
     * A least-visible seam's costs at each vertex of [p], before any jog: [seen], how much the point is seen (its exposure,
     * the sampled viewing directions it faces by its outward normal, each weighed by the quadrant it looks from around the
     * view, less what hides it nearby; an overhanging point as an underside; a scarf seam's start as its ramp's most-seen
     * point), and [blem], the blemish seam_scar charges there (a point seam a line width squared, or a band across the shelf
     * it sits on; the corner share in a corner; a scarf seam, where its start gets one and it pays ([SeamScar.scarfPays]),
     * [scarfShare] of a line width squared plus its ramp on the shelf). A start another part of the object covers ([covered])
     * gets a point seam (plan.technique.scarf_seam.covered = point), so it is costed as one: seen at itself, a point's
     * blemish. [points]: the held-up vertices (all where none is).
     */
    class LoopCosts(val seen: DoubleArray, val blem: DoubleArray, val corner: BooleanArray, val points: List<Int>)

    fun costsOf(p: Polygon, cornerDeg: Double?, spanUm: Double, overhangs: BooleanArray?, vis: plymeld.core.decide.Visibility, cornerShare: Double,
                exposure: plymeld.core.decide.Exposure?, layer: Int, shelf: ((Long, Long) -> Double)?, rampUm: Long, rampOffCorners: Boolean,
                scarfShare: Double = 1.0, covered: ((Long, Long) -> Boolean)? = null): LoopCosts {
        val n = p.size
        val held: (Int) -> Boolean = if (overhangs == null || overhangs.all { it }) { _ -> true } else { i -> !overhangs[i] }
        val ex = exposure?.ofLoop(layer, p)
        val vs = DoubleArray(n) { i ->
            val over = overhangs != null && overhangs[i]
            (ex?.get(i) ?: 1.0) * (if (over) vis.underside else 1.0)
        }
        val pts = (0 until n).filter(held).ifEmpty { (0 until n).toList() }
        val tv = if (cornerDeg == null) null else turns(p, spanUm)
        val corner = BooleanArray(n) { i -> tv != null && Math.abs(tv[i]) >= cornerDeg!! }
        // where a start gets a point seam whatever the ramp: a corner where scarf seams go off corners only, and a point
        // another part covers (the Planner lays a point seam there: a scarf ran its ramp and closing pass out onto the faces beside
        // it; costed as a scarf, a covered point read as seen as the ramp's most-seen point and the placement avoided it)
        val point = BooleanArray(n) { i -> rampUm <= 0 || (rampOffCorners && corner[i]) || covered?.invoke(p.x(i), p.y(i)) == true }
        // a scarf seam is seen along its ramp, not at its start alone: the most-seen point of the [rampUm] the loop runs on
        // from the start. Judged at the start alone, a lettering's seams started hidden in a letter's pocket and their 10 mm
        // ramps ran out onto the face beside it
        val seen = if (rampUm <= 0) vs else DoubleArray(n) { i ->
            if (point[i]) vs[i] else {
                var worst = vs[i]; var run = 0.0; var j = i
                while (run < rampUm) {
                    val k = (j + 1) % n
                    run += Math.hypot((p.x(k) - p.x(j)).toDouble(), (p.y(k) - p.y(j)).toDouble())
                    worst = maxOf(worst, vs[k]); j = k
                    if (j == i) break
                }
                worst
            }
        }
        val w = spanUm / 2 / 1000.0
        // the blemish as seam_scar counts it: a band as wide as the shelf the point sits on (SeamScar.pointBlemish), a scarf's
        // where it gets one (SeamScar.scarfBlemish). Charged a point seam's at a scarf's start, the plan took a seam's blemish
        // for several times what the measure counts against the move to a less-seen point, and moved too readily: on a
        // two-color nameplate, 2,284 of its 2,420 seams scarfs, the plan's jogs added more to the measure than its moves saved
        val rampMm = rampUm / 1000.0
        val blem = DoubleArray(n) { i ->
            val sh = shelf?.invoke(p.x(i), p.y(i)) ?: 0.0
            if (!point[i] && plymeld.core.SeamScar.scarfPays(w, sh, scarfShare, rampMm))
                plymeld.core.SeamScar.scarfBlemish(w, sh, scarfShare, rampMm)
            else plymeld.core.SeamScar.pointBlemish(w, sh, if (corner[i]) cornerShare else 1.0)
        }
        return LoopCosts(seen, blem, corner, pts)
    }

    /**
     * Every layer's wall loops placed, layer by layer, each wall's seams aligned with that wall's on the layer below (the
     * outer walls' least-visible seams with the point [planColumn] planned for them as the seam below, over the [runsOf]):
     * [loops][layer][wall][loop] -> the same shape. [spanUm] (layer, wall): the corner span, two of that wall's lines.
     */
    fun alignAll(loops: List<List<List<Polygon>>>, cornerDeg: Double?, overhangs: List<List<List<BooleanArray>>?>? = null,
                 back: Boolean = false, spanUm: (Int, Int) -> Double, leastVisible: Boolean = false, vis: plymeld.core.decide.Visibility? = null,
                 cornerShare: Double = 1.0, exposure: plymeld.core.decide.Exposure? = null,
                 /** Each layer's shelf function for its outer walls ([place]'s shelf); null = none. */
                 shelfOf: ((Int) -> ((Long, Long) -> Double)?)? = null,
                 /** The outer walls' scarf ramp length (µm), when scarf seams may be laid; 0 = none ([place]). */
                 rampUm: Long = 0, rampOffCorners: Boolean = false,
                 /** A scarf seam's blemish as a share of a point seam's (method.quality.seam.scarf_share; [place]). */
                 scarfShare: Double = 1.0,
                 /** Each layer's test of whether another part covers an outer wall's point ([place]'s covered); null = none. */
                 coveredOf: ((Int) -> ((Long, Long) -> Boolean)?)? = null): List<List<List<Placed>>> {
        val out = ArrayList<List<List<Placed>>>(loops.size)
        var below: List<LongArray> = emptyList()
        // the outer walls' exposures, the layers in parallel (the placement itself runs layer by layer, each from the one below)
        if (leastVisible && exposure != null) java.util.stream.IntStream.range(0, loops.size).parallel()
            .forEach { i -> loops[i].getOrNull(0)?.forEach { lp -> if (lp.size >= 3) exposure.ofLoop(i, lp) } }
        // the outer walls' seams planned over each run of layers a loop continues through ([runsOf], [planColumn]), each run when the
        // placement reaches its first layer, from the seams placed below it: each loop is then placed with its planned point
        // as the seam below, so the jog it pays is to that point
        val runs = if (leastVisible && vis != null) runsOf(loops).groupBy { it[0].first } else emptyMap()
        val planned = List(loops.size) { i -> arrayOfNulls<LongArray>(loops[i].getOrNull(0)?.size ?: 0) }
        for ((i, layer) in loops.withIndex()) {
            runs[i]?.forEach { run ->
                planColumn(run, loops, below.getOrElse(0) { LongArray(0) }, cornerDeg, overhangs, spanUm, vis!!, cornerShare, exposure, shelfOf, rampUm, rampOffCorners, scarfShare, coveredOf)
                    .forEachIndexed { r, xy -> planned[run[r].first][run[r].second] = xy }
            }
            val oh = overhangs?.get(i)
            // what hides a point nearby is read for the outer walls only (an inner wall's seam is not seen; its rays start in material)
            val placed = layer.mapIndexed { k, wall -> wall.mapIndexed { li, lp ->
                val ref = if (k == 0) planned[i].getOrNull(li) else null
                place(lp, cornerDeg, spanUm(i, k), ref ?: below.getOrElse(k) { LongArray(0) }, oh?.get(k)?.get(li), back, leastVisible, vis, cornerShare,
                if (k == 0) exposure else null, i, if (k == 0 && leastVisible) shelfOf?.invoke(i) else null,
                if (k == 0) rampUm else 0, rampOffCorners, scarfShare, if (k == 0 && leastVisible) coveredOf?.invoke(i) else null) } }
            out += placed
            below = placed.map { wall -> LongArray(2 * wall.size) { j -> val q = wall[j / 2].loop; if (j % 2 == 0) q.x(0) else q.y(0) } }
        }
        return out
    }

    /** At most this many candidate points per loop in [planColumn] (every n-th vertex, and the cheapest): a speed bound. */
    private const val PLAN_CANDIDATES = 160

    /** The tie-break terms' scale in [planColumn], a share of a loop's largest seam cost: far under [TIE_V], the placement's tie band. */
    private const val TIE_PLAN = 1e-3

    /**
     * The runs of layers the outer walls' least-visible seams are planned over ([planColumn]): each a list of (layer, outer
     * loop), bottom up. Each loop is linked to the loop below it whose bounding box matches its own best (the most overlap
     * over the two boxes' union: a letter's pocket links to the pocket below, not to the outside loop round it, which
     * overlaps it as much); a run goes up while the loop above links one to one (one loop over one loop). A run of one
     * layer is left out: placed on its own, it is placed as planned.
     */
    private fun runsOf(loops: List<List<List<Polygon>>>): List<List<Pair<Int, Int>>> {
        val n = loops.size
        val outer = List(n) { i -> loops[i].getOrNull(0).orEmpty() }
        val boxes = List(n) { i -> outer[i].map { it.bounds() } }
        fun match(a: LongArray, b: LongArray): Double {
            val w = minOf(a[2], b[2]) - maxOf(a[0], b[0]); val h = minOf(a[3], b[3]) - maxOf(a[1], b[1])
            if (w <= 0 || h <= 0) return 0.0
            val both = w.toDouble() * h
            return both / ((a[2] - a[0]).toDouble() * (a[3] - a[1]) + (b[2] - b[0]).toDouble() * (b[3] - b[1]) - both)
        }
        val pred = List(n) { i -> IntArray(outer[i].size) { j ->
            if (i == 0) -1 else boxes[i - 1].indices.maxByOrNull { match(boxes[i][j], boxes[i - 1][it]) }?.takeIf { match(boxes[i][j], boxes[i - 1][it]) > 0 } ?: -1 } }
        val ups = List(n) { i -> IntArray(outer[i].size) }
        for (i in 1 until n) for (j in outer[i].indices) pred[i][j].takeIf { it >= 0 }?.let { ups[i - 1][it]++ }
        fun linked(i: Int, j: Int) = i > 0 && pred[i][j] >= 0 && ups[i - 1][pred[i][j]] == 1
        val runs = ArrayList<List<Pair<Int, Int>>>()
        // from each loop not linked below, up while the loop above links to it one to one
        for (i0 in 0 until n) for (j0 in outer[i0].indices) {
            if (linked(i0, j0)) continue
            val run = arrayListOf(i0 to j0)
            while (true) {
                val (i, j) = run.last()
                if (i + 1 >= n) break
                val up = outer[i + 1].indices.filter { pred[i + 1][it] == j && linked(i + 1, it) }
                if (up.size != 1) break
                run += (i + 1) to up.single()
            }
            if (run.size > 1 && run.all { (i, j) -> outer[i][j].size >= 3 }) runs += run
        }
        return runs
    }

    /**
     * One run's outer-wall seams ([runsOf]) planned as a whole: per layer of the run, the point (x, y) its seam goes to, on
     * the path through the layers' points that costs least in all: each point its cost as seam_scar charges it (how much it
     * is seen x its blemish, [costsOf]), each move from the point below its jog (its length x the line width, times how much
     * the new point is seen; none within a line), the run's first move from the nearest of the seams placed below it
     * ([below]: x, y pairs; none = no move). Placed layer by layer instead, a seam stayed in line with the one below on a
     * face fully seen rather than pay the move once: on a nameplate's plain round wall, 70 layers on its front face for one
     * move of 90 mm to the back.
     */
    private fun planColumn(run: List<Pair<Int, Int>>, loops: List<List<List<Polygon>>>, below: LongArray, cornerDeg: Double?,
                           overhangs: List<List<List<BooleanArray>>?>?, spanUm: (Int, Int) -> Double, vis: plymeld.core.decide.Visibility,
                           cornerShare: Double, exposure: plymeld.core.decide.Exposure?, shelfOf: ((Int) -> ((Long, Long) -> Double)?)?,
                           rampUm: Long, rampOffCorners: Boolean, scarfShare: Double,
                           coveredOf: ((Int) -> ((Long, Long) -> Boolean)?)?): List<LongArray> {
            val outer = { i: Int -> loops[i][0] }
            // per layer: its candidates (every n-th held-up vertex and the cheapest), their own costs and how much each is seen
            class Layer(val p: Polygon, val idx: IntArray, val own: DoubleArray, val seen: DoubleArray, val w: Double)
            val layers = run.map { (i, j) ->
                val p = outer(i)[j]
                val c = costsOf(p, cornerDeg, spanUm(i, 0), overhangs?.get(i)?.getOrNull(0)?.getOrNull(j), vis, cornerShare, exposure, i,
                    shelfOf?.invoke(i), rampUm, rampOffCorners, scarfShare, coveredOf?.invoke(i))
                val pts = c.points
                val step = maxOf(1, pts.size / PLAN_CANDIDATES)
                val cheapest = pts.sortedBy { c.seen[it] * c.blem[it] }.take(PLAN_CANDIDATES / 8)
                val idx = (pts.filterIndexed { k, _ -> k % step == 0 } + cheapest).distinct().toIntArray()
                // [place]'s tie-breaks as costs too small to outweigh a real difference: the point seen least, then a corner,
                // then the back (else near-equal points went to whichever came first, and the plan overrode the tie-breaks)
                val top = idx.maxOf { c.seen[it] * c.blem[it] }.coerceAtLeast(1e-9)
                val yMin = idx.minOf { p.y(it) }; val ySpan = (idx.maxOf { p.y(it) } - yMin).coerceAtLeast(1).toDouble()
                Layer(p, idx, DoubleArray(idx.size) { k -> val v = idx[k]
                    c.seen[v] * c.blem[v] + top * (TIE_PLAN * c.seen[v] + TIE_PLAN / 10 * (if (c.corner[v]) 0.0 else 1.0) + TIE_PLAN / 100 * (1 - (p.y(v) - yMin) / ySpan)) },
                    DoubleArray(idx.size) { c.seen[idx[it]] }, spanUm(i, 0) / 2 / 1000.0)
            }
            // the cheapest path: cost so far at each candidate, and where it came from
            val first = layers[0]
            var cost = DoubleArray(first.idx.size) { v ->
                var d = Double.MAX_VALUE
                for (k in 0 until below.size / 2) d = minOf(d, Math.hypot((first.p.x(first.idx[v]) - below[2 * k]) / 1000.0, (first.p.y(first.idx[v]) - below[2 * k + 1]) / 1000.0))
                first.own[v] + (if (below.isNotEmpty() && d > first.w) first.seen[v] * d * first.w else 0.0)
            }
            val from = ArrayList<IntArray>()
            for (r in 1 until layers.size) {
                val a = layers[r - 1]; val b = layers[r]
                val next = DoubleArray(b.idx.size); val back = IntArray(b.idx.size)
                for (v in b.idx.indices) {
                    val bx = b.p.x(b.idx[v]); val by = b.p.y(b.idx[v])
                    var best = Double.MAX_VALUE; var arg = 0
                    for (u in a.idx.indices) {
                        val d = Math.hypot((bx - a.p.x(a.idx[u])) / 1000.0, (by - a.p.y(a.idx[u])) / 1000.0)
                        val c = cost[u] + (if (d > b.w) b.seen[v] * d * b.w else 0.0)
                        if (c < best) { best = c; arg = u }
                    }
                    next[v] = best + b.own[v]; back[v] = arg
                }
                from += back; cost = next
            }
            var v = cost.indices.minByOrNull { cost[it] } ?: return emptyList()
            val out = arrayOfNulls<LongArray>(run.size)
            for (r in layers.indices.reversed()) {
                val l = layers[r]
                out[r] = longArrayOf(l.p.x(l.idx[v]), l.p.y(l.idx[v]))
                if (r > 0) v = from[r - 1][v]
            }
            return out.map { it!! }
    }

    /** [p] starting at vertex [i]. */
    fun rotated(p: Polygon, i: Int): Polygon {
        if (i == 0) return p
        val xy = LongArray(p.xy.size)
        for (k in 0 until p.size) { val j = (i + k) % p.size; xy[2 * k] = p.x(j); xy[2 * k + 1] = p.y(j) }
        return Polygon(xy)
    }
}
