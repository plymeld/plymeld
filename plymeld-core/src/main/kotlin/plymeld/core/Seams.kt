package plymeld.core

import plymeld.geom.Polygon

/**
 * Where a wall loop starts (plan.seam = "corner_seam"): at a corner or other sharp edge where there is one, the seams
 * aligned. A corner is a turn of the loop of at least plan.technique.corner_seam.corner_angle,
 * measured between the loop's direction a span back and a span ahead of the point (two line widths: a finely faceted
 * curve turns a little at every vertex and is no corner; a rounded inside corner, the wall's offset of a sharp one,
 * still turns its whole angle across it). Wall loops have the material on their left (outer outlines
 * counter-clockwise, holes clockwise), so a right turn is an inside (concave) corner.
 *
 * First of all, a seam goes where the layer below holds the loop up: points on an edge of the layer that hangs over the
 * layer below (the overhang slow-down's measure, [Overhangs.seamOverhangs]) are candidates only when the loop has no other
 * (plan.seam = "back_seam" too: the back-most held-up point). Among the rest:
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
               * plan.seam_start = least_visible: the held-up point the seam measure counts least: its visibility by
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
              shelf: ((Long, Long) -> Double)? = null): Placed {
        if (p.size < 3) return Placed(Planner.seamAtBack(p), false)
        val held: (Int) -> Boolean = if (overhangs == null || overhangs.all { it }) { _ -> true } else { i -> !overhangs[i] }
        if (leastVisible && vis != null) {
            // how much each vertex's bit of wall is seen: its exposure (the sampled viewing directions it faces, by its outward
            // normal, each weighed by the quadrant it looks from around the view, less what hides it nearby), and an
            // overhanging point as an underside (the direction term's weight: a stand-in for its lean, which the loop alone does
            // not give)
            val n = p.size
            val ex = exposure?.ofLoop(layer, p)
            val vs = DoubleArray(n) { i ->
                val over = overhangs != null && overhangs[i]
                (ex?.get(i) ?: 1.0) * (if (over) vis.underside else 1.0)
            }
            fun v(i: Int): Double = vs[i]
            val pts = (0 until n).filter(held).ifEmpty { (0 until n).toList() }
            val tv = if (cornerDeg == null) null else turns(p, spanUm)
            fun corner(i: Int) = tv != null && Math.abs(tv[i]) >= cornerDeg!!
            // the cost seam_scar charges this seam (SeamScar.measure): a point seam one line width squared, or a band across the shelf it sits on (the corner share in a
            // corner), plus a jog from the nearest seam below its length x the line width when further than a line, all times how
            // much it is seen. Without the jog the placement chased the least-seen point layer by layer and the seams wandered
            // (with what hides a point nearby, the dual Benchy's least-visible seams jogged 1586 mm against the
            // corners' 431, and the measure scored the placement 33 % worse than the back seam)
            val w = spanUm / 2 / 1000.0
            fun jog(i: Int): Double {
                if (below.isEmpty()) return 0.0
                var d = Double.MAX_VALUE
                for (k in 0 until below.size / 2) d = minOf(d, Math.hypot((p.x(i) - below[2 * k]) / 1000.0, (p.y(i) - below[2 * k + 1]) / 1000.0))
                return if (d > w) d * w else 0.0
            }
            // the blemish as seam_scar counts it: a band as wide as the shelf the point sits on (SeamScar.pointBlemish)
            val blem = DoubleArray(n) { i -> plymeld.core.SeamScar.pointBlemish(w, shelf?.invoke(p.x(i), p.y(i)) ?: 0.0, if (corner(i)) cornerShare else 1.0) }
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
            // plan.seam_start = back (back_seam): the back-most point, in line with the layer below (a gap found
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
     * Every layer's wall loops placed, layer by layer, each wall's seams aligned with that wall's on the layer below:
     * [loops][layer][wall][loop] -> the same shape. [spanUm] (layer, wall): the corner span, two of that wall's lines.
     */
    fun alignAll(loops: List<List<List<Polygon>>>, cornerDeg: Double?, overhangs: List<List<List<BooleanArray>>?>? = null,
                 back: Boolean = false, spanUm: (Int, Int) -> Double, leastVisible: Boolean = false, vis: plymeld.core.decide.Visibility? = null,
                 cornerShare: Double = 1.0, exposure: plymeld.core.decide.Exposure? = null,
                 /** Each layer's shelf function for its outer walls ([place]'s shelf); null = none. */
                 shelfOf: ((Int) -> ((Long, Long) -> Double)?)? = null): List<List<List<Placed>>> {
        val out = ArrayList<List<List<Placed>>>(loops.size)
        var below: List<LongArray> = emptyList()
        // the outer walls' exposures, the layers in parallel (the placement itself runs layer by layer, each from the one below)
        if (leastVisible && exposure != null) java.util.stream.IntStream.range(0, loops.size).parallel()
            .forEach { i -> loops[i].getOrNull(0)?.forEach { lp -> if (lp.size >= 3) exposure.ofLoop(i, lp) } }
        for ((i, layer) in loops.withIndex()) {
            val oh = overhangs?.get(i)
            // what hides a point nearby is read for the outer walls only (an inner wall's seam is not seen; its rays start in material)
            val placed = layer.mapIndexed { k, wall -> wall.mapIndexed { li, lp -> place(lp, cornerDeg, spanUm(i, k), below.getOrElse(k) { LongArray(0) }, oh?.get(k)?.get(li), back, leastVisible, vis, cornerShare,
                if (k == 0) exposure else null, i, if (k == 0 && leastVisible) shelfOf?.invoke(i) else null) } }
            out += placed
            below = placed.map { wall -> LongArray(2 * wall.size) { j -> val q = wall[j / 2].loop; if (j % 2 == 0) q.x(0) else q.y(0) } }
        }
        return out
    }

    /** [p] starting at vertex [i]. */
    fun rotated(p: Polygon, i: Int): Polygon {
        if (i == 0) return p
        val xy = LongArray(p.xy.size)
        for (k in 0 until p.size) { val j = (i + k) % p.size; xy[2 * k] = p.x(j); xy[2 * k + 1] = p.y(j) }
        return Polygon(xy)
    }
}
