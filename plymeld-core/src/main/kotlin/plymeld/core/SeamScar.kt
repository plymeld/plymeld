package plymeld.core

import plymeld.core.decide.Visibility
import plymeld.geom.Polygon
import plymeld.geom.Polygons
import java.util.Locale

/**
 * The seam measure, `seam_scar` (mm², quality): over every closed
 * outer-wall loop of the planned layers, the blemish its seam leaves, as seen. A point seam counts one line width
 * squared; a scarf seam `method.quality.seam.scarf_share` of that (the joint spread over the scarf's length instead of
 * piled at a point; a placeholder until measured — NOT proportional to the length: a longer scarf is not a longer
 * blemish, else a 10 mm scarf would read worse than the point seam it replaces); a point seam in a corner
 * `method.quality.seam.corner_share` of a smooth wall's (the corner hides it: what corner seams are for; a placeholder
 * too); a seam further than a line width from the nearest seam of the
 * layer below adds the jog's length x the line width (a step between layers reads as a line). Each is weighted by how
 * much the face at the seam is seen ([Visibility]): a seam on an edge that steps out over the layer below sits on an
 * underside leaning by that step (the direction term); a seam the layer below holds up sits on a wall or a sloped top
 * (1). The layer below's printed area is read from its own outer walls, so the measure needs no mesh and runs on any
 * planned layers (the slice, the finalists' slices, a seam candidate on the same walls).
 */
object SeamScar {
    class Result(val scarMm2: Double, val seams: Int, val pointSeams: Int, val scarfSeams: Int, val jogMm: Double, val perLayerMm2: DoubleArray) {
        fun words(): String = "seam scar %.1f mm² over %d seams (%d point, %d scarf; jogs %.1f mm), weighted by how much each is seen"
            .format(Locale.ROOT, scarMm2, seams, pointSeams, scarfSeams, jogMm)
        companion object { val NONE = Result(0.0, 0, 0, 0, 0.0, DoubleArray(0)) }
    }

    /** Several objects' results as one (a plate). */
    fun sum(rs: List<Result>): Result = Result(rs.sumOf { it.scarMm2 }, rs.sumOf { it.seams }, rs.sumOf { it.pointSeams }, rs.sumOf { it.scarfSeams }, rs.sumOf { it.jogMm },
        rs.flatMap { it.perLayerMm2.asList() }.toDoubleArray())

    /** The outer-wall loops of a layer: the seams, and the layer's printed area as the layer above sees it. */
    private fun outerLoops(l: LayerPlanOut): List<ToolPath> = l.paths.filter { it.feature == Feature.OUTER_WALL && it.closed && it.xy.size >= 6 }

    /** The seam measure's shares (method.quality.seam.*): a scarf seam's blemish and a point seam's in a corner, each against a point seam on a smooth wall. */
    class Shares(val scarf: Double, val corner: Double, val cornerDeg: Double?) {
        companion object { val DEFAULT = Shares(0.25, 0.25, 30.0) }
    }

    /**
     * [layers] in print order (one object); [visibility] null = every seam counts alike; [shares]: a scarf seam's blemish as a
     * share of a point seam's, a point seam in a corner's (a turn of at least [Shares.cornerDeg] across two line widths,
     * Seams.turns; the corner hides it: why corner seams exist) as a share of one on a smooth wall.
     */
    fun measure(layers: List<LayerPlanOut>, visibility: Visibility?, shares: Shares,
                /** The layers' outer walls prepared as [Below], by layer index, when the caller measures several candidates on the same walls (SeamDecision); null = prepared here. */
                belows: List<Below?>? = null,
                /**
                 * What hides a seam nearby, from the same outer walls the seams were placed on, when the caller
                 * has one (SeamDecision: shared by its candidates); null = made here from [layers] when [visibility] samples it.
                 */
                exposure: plymeld.core.decide.Exposure? = null,
                /**
                 * Which stack each of [layers] is in (curved islands, each its own stack among the flat layers in print order;
                 * -1 the flat layers), so a layer's seams are read against the layer below in its own stack; null = one stack.
                 * Read against the layer printed before it, a flat layer's seams were measured from an island's on another part
                 * of the model (the 3DBenchy with an island on its bow, seam scar 45.6 -> 183 mm2).
                 */
                stackOf: ((Int) -> Int)? = null): Result {
        val ex = exposure ?: visibility?.exposure(layers.map { l -> outerLoops(l).map { Polygon(it.xy) } }, LongArray(layers.size) { layers[it].topZUm })
        val scarfShare = shares.scarf
        var total = 0.0; var seams = 0; var points = 0; var scarfs = 0; var jog = 0.0
        val per = DoubleArray(layers.size)
        // each stack's layer below: its own last one (an island's first layer: the flat layers' last, what it starts on)
        val lastLoops = HashMap<Int, List<ToolPath>>(); val lastPolys = HashMap<Int, Below?>()
        for ((i, l) in layers.withIndex()) {
            val st = stackOf?.invoke(i) ?: -1
            val belowLoops: List<ToolPath> = lastLoops[st] ?: lastLoops[-1] ?: emptyList()
            val belowPolys: Below? = if (st in lastPolys) lastPolys[st] else lastPolys[-1]
            val loops = outerLoops(l)
            val h = l.heightUm / 1000.0
            var here = 0.0
            for (p in loops) {
                val sx = p.xy[0]; val sy = p.xy[1]
                val w = p.widthUm / 1000.0
                // the shelf the seam sits on: the layer below's top left exposed where this wall steps in, read within the
                // visibility sampling's reach (none without a map: as before)
                val shelf = belowPolys?.takeIf { i > 0 && it.polys.isNotEmpty() }?.let { b -> shelfMm(sx, sy, b, w / 2, shelfReachMm(visibility)) } ?: 0.0
                var blemish = if (p.scarfUm > 0) { scarfs++; scarfBlemish(w, shelf, scarfShare, p.scarfUm / 1000.0) } else {
                    points++
                    // a point seam in a corner: the corner hides it (the turn at the seam vertex, as Seams judges a corner)
                    val atCorner = shares.cornerDeg != null && Math.abs(Seams.turns(Polygon(p.xy), 2.0 * p.widthUm)[0]) >= shares.cornerDeg
                    pointBlemish(w, shelf, if (atCorner) shares.corner else 1.0)
                }
                // the jog from the nearest seam below (none below: a new island, no jog)
                if (belowLoops.isNotEmpty()) {
                    var d = Double.MAX_VALUE
                    for (b in belowLoops) d = minOf(d, Math.hypot((sx - b.xy[0]) / 1000.0, (sy - b.xy[1]) / 1000.0))
                    if (d > w) { blemish += d * w; jog += d }
                }
                // how much the face at the seam is seen: held up by the layer below (within half a line of its walls, or inside
                // them) = a wall or a sloped top; else an underside leaning by the step over the layer below
                val bp = belowPolys
                var v = if (visibility == null || i == 0 || bp == null || bp.polys.isEmpty()) 1.0 else {
                    val out = stepOutMm(sx, sy, bp, w / 2)
                    if (out <= 0) 1.0 else visibility.direction(out / Math.hypot(out, h))
                }
                // the directions it faces, weighed by the quadrant they look from around the view, less what hides it
                // nearby, read at the seam as the placement reads a candidate (Exposure.outward: the wall's outward normal, to the
                // right of the loop's direction, the material on a wall loop's left)
                if (ex != null) { val nrm = plymeld.core.decide.Exposure.outward(p.xy, 0); v *= ex.at(i, sx, sy, nrm[0], nrm[1]) }
                here += blemish * v; seams++
            }
            per[i] = here; total += here
            lastLoops[st] = loops; lastPolys[st] = if (visibility == null) null else belows?.getOrNull(i) ?: Below(loops.map { Polygon(it.xy) })
        }
        return Result(total, seams, points, scarfs, jog, per)
    }

    /**
     * The layer below's outer-wall loops prepared once for many seam points: grouped (each outer with its holes, by index)
     * and boxed, so a point is tested only against the loops whose box can matter (grouping and scanning every
     * vertex per seam took a 30-part model's plan from 3 min to over 15).
     */
    /**
     * The blemish a point seam leaves (mm²), before how much it is seen and its jog: one line width [wMm] across, as long as
     * the shelf it sits on is wide ([shelfMm], the layer below's top left exposed where this layer's wall steps in: on a
     * nearly flat surface each layer's seam shows from above as a band across it; a lying pin's top printed its
     * seams as a trail), at least a line; [cornerShare] in a corner, else 1. THE definition: the measure, the least-visible
     * placement, and the per-loop scarf rule all read it.
     */
    fun pointBlemish(wMm: Double, shelfMm: Double, cornerShare: Double = 1.0): Double = wMm * maxOf(wMm, shelfMm) * cornerShare

    /**
     * The blemish a scarf seam leaves (mm²): [scarfShare] of a point seam's line width squared, plus its ramp lying on the
     * exposed shelf beyond a line ([scarfLenMm] x ([shelfMm] - [wMm])): the ramp starts thin and low, and on a shelf it is seen
     * from above along its length (the form is plymeld's choice, from the lying pin's scarf trail; not measured).
     */
    fun scarfBlemish(wMm: Double, shelfMm: Double, scarfShare: Double, scarfLenMm: Double): Double =
        wMm * wMm * scarfShare + scarfLenMm * maxOf(0.0, shelfMm - wMm)

    /** Whether a scarf leaves less blemish than a point seam where a loop's seam sits on a shelf [shelfMm] wide: always on a wall (a shelf within a line). */
    fun scarfPays(wMm: Double, shelfMm: Double, scarfShare: Double, scarfLenMm: Double): Boolean =
        shelfMm <= wMm || scarfBlemish(wMm, shelfMm, scarfShare, scarfLenMm) < pointBlemish(wMm, shelfMm)

    class Below(val polys: List<Polygon>) {
        /** The loops' edges by 1 mm cell (packed polygon index << 32 | edge index), for [insideDepthUm]; built when first read. */
        private val grid: HashMap<Long, LongArray> by lazy {
            val acc = HashMap<Long, java.util.ArrayList<Long>>()
            for ((k, p) in polys.withIndex()) for (e in 0 until p.size) {
                val ax = p.x(e); val ay = p.y(e); val bx = p.x((e + 1) % p.size); val by = p.y((e + 1) % p.size)
                for (cx in Math.floorDiv(minOf(ax, bx), CELL_UM)..Math.floorDiv(maxOf(ax, bx), CELL_UM))
                    for (cy in Math.floorDiv(minOf(ay, by), CELL_UM)..Math.floorDiv(maxOf(ay, by), CELL_UM))
                        acc.getOrPut((cx shl 32) xor (cy and 0xFFFFFFFFL)) { java.util.ArrayList() }.add((k.toLong() shl 32) or e.toLong())
            }
            HashMap<Long, LongArray>(acc.size * 2).also { m -> for ((key, v) in acc) m[key] = v.toLongArray() }
        }
        /** Whether (x, y) lies in the printed area the loops enclose (inside an outer and in none of its holes). */
        fun inside(x: Long, y: Long): Boolean =
            groups.any { (o, hs) -> inBox(o, x, y) && Polygons.contains(polys[o], x, y) && hs.none { inBox(it, x, y) && Polygons.contains(polys[it], x, y) } }
        /** How far inside the area (µm) the point lies, from the nearest loop: 0 outside; read up to [maxUm] (beyond it, [maxUm]). */
        fun insideDepthUm(x: Long, y: Long, maxUm: Long): Double {
            if (!inside(x, y)) return 0.0
            val cx0 = Math.floorDiv(x, CELL_UM); val cy0 = Math.floorDiv(y, CELL_UM)
            var best = maxUm.toDouble()
            val rings = (maxUm / CELL_UM + 1).toInt()
            for (r in 0..rings) {
                if (best <= (r - 1) * CELL_UM.toDouble()) break
                for (cx in cx0 - r..cx0 + r) for (cy in cy0 - r..cy0 + r) {
                    if (maxOf(Math.abs(cx - cx0), Math.abs(cy - cy0)) != r.toLong()) continue
                    val edges = grid[(cx shl 32) xor (cy and 0xFFFFFFFFL)] ?: continue
                    for (pe in edges) {
                        val p = polys[(pe ushr 32).toInt()]; val e = (pe and 0xFFFFFFFFL).toInt()
                        val ax = p.x(e).toDouble(); val ay = p.y(e).toDouble(); val bx = p.x((e + 1) % p.size).toDouble(); val by = p.y((e + 1) % p.size).toDouble()
                        val dx = bx - ax; val dy = by - ay; val l2 = dx * dx + dy * dy
                        val t = if (l2 == 0.0) 0.0 else (((x - ax) * dx + (y - ay) * dy) / l2).coerceIn(0.0, 1.0)
                        best = minOf(best, Math.hypot(ax + t * dx - x, ay + t * dy - y))
                    }
                }
            }
            return best
        }
        val boxes: List<LongArray> = polys.map { p ->
            var x0 = Long.MAX_VALUE; var y0 = Long.MAX_VALUE; var x1 = Long.MIN_VALUE; var y1 = Long.MIN_VALUE
            for (i in 0 until p.size) { val px = p.x(i); val py = p.y(i); if (px < x0) x0 = px; if (px > x1) x1 = px; if (py < y0) y0 = py; if (py > y1) y1 = py }
            longArrayOf(x0, y0, x1, y1)
        }
        /** Each outer's index with its holes' indices (Polygons.group, by identity). */
        val groups: List<Pair<Int, List<Int>>> = Polygons.group(polys).map { (o, hs) -> polys.indexOfFirst { it === o } to hs.map { h -> polys.indexOfFirst { it === h } } }
        fun inBox(k: Int, x: Long, y: Long): Boolean { val b = boxes[k]; return x >= b[0] && x <= b[2] && y >= b[1] && y <= b[3] }
        /** Squared distance from the point to the box (0 inside it). */
        fun boxDist2(k: Int, x: Long, y: Long): Double {
            val b = boxes[k]
            val dx = if (x < b[0]) (b[0] - x).toDouble() else if (x > b[2]) (x - b[2]).toDouble() else 0.0
            val dy = if (y < b[1]) (b[1] - y).toDouble() else if (y > b[3]) (y - b[3]).toDouble() else 0.0
            return dx * dx + dy * dy
        }
    }

    /**
     * How far (mm) the point lies outside the printed area the [below] outer-wall loops enclose, beyond [marginMm] (half a
     * line: the wall's bead reaches that far past its center line); 0 inside or within the margin. The loops are read with
     * the kernel's winding (outers CCW, holes CW): inside an outer and in none of its holes.
     */
    fun stepOutMm(x: Long, y: Long, below: List<Polygon>, marginMm: Double): Double = stepOutMm(x, y, Below(below), marginMm)

    private const val CELL_UM = 1000L

    /** How far a shelf is read (mm): the visibility sampling's reach (the same "nearby"); 0 without sampling (no shelf, as before). */
    fun shelfReachMm(visibility: Visibility?): Double = (visibility?.sample?.reachUm ?: 0L) / 1000.0

    /**
     * The shelf a seam at (x, y) sits on (mm): how far inside the printed area the [below] outer-wall loops enclose the point
     * lies, beyond [marginMm] (half a line: the bead below reaches that far past its center line), read up to [maxMm]; 0
     * outside or within the margin (a wall: no shelf).
     */
    fun shelfMm(x: Long, y: Long, below: Below, marginMm: Double, maxMm: Double): Double =
        maxOf(0.0, below.insideDepthUm(x, y, Math.round((maxMm + marginMm) * 1000)) / 1000.0 - marginMm)

    fun stepOutMm(x: Long, y: Long, below: Below, marginMm: Double): Double {
        if (below.groups.any { (o, hs) -> below.inBox(o, x, y) && Polygons.contains(below.polys[o], x, y) && hs.none { below.inBox(it, x, y) && Polygons.contains(below.polys[it], x, y) } }) return 0.0
        var d2 = Double.MAX_VALUE
        // the margin in µm² as the first bound: a point within the margin reads 0 whatever the exact distance
        val marginUm = marginMm * 1000
        for ((k, p) in below.polys.withIndex()) {
            if (below.boxDist2(k, x, y) >= d2) continue
            val n = p.size
            for (i in 0 until n) {
                val ax = p.x(i).toDouble(); val ay = p.y(i).toDouble(); val bx = p.x((i + 1) % n).toDouble(); val by = p.y((i + 1) % n).toDouble()
                val dx = bx - ax; val dy = by - ay; val l2 = dx * dx + dy * dy
                val t = if (l2 == 0.0) 0.0 else (((x - ax) * dx + (y - ay) * dy) / l2).coerceIn(0.0, 1.0)
                val ex = ax + t * dx - x; val ey = ay + t * dy - y
                val e2 = ex * ex + ey * ey
                if (e2 < d2) { d2 = e2; if (d2 <= marginUm * marginUm) return 0.0 }
            }
        }
        if (d2 == Double.MAX_VALUE) return 0.0
        return maxOf(0.0, Math.sqrt(d2) / 1000.0 - marginMm)
    }
}
