package plymeld.core

import plymeld.geom.Booleans
import plymeld.geom.Offset
import plymeld.geom.Polygon
import plymeld.geom.Polygons

/**
 * Tree support — plymeld's own construction, ONE style (not several variants).
 *
 *  1. Overhangs ([Supports.overhangs]).
 *  2. Roofs: under an overhang, support_top_z_distance (whole layers) of gap, then
 *     support_interface_top_layers of interface covering the overhang.
 *  3. Tips, on the layer below the roof: points of a global lattice of tree_support_branch_distance
 *     that fall inside the overhang; a piece of overhang no lattice point hits gets one tip at an
 *     interior point. A tip is skipped where a branch already passes within half the lattice pitch.
 *  4. Branches grow down layer by layer. Radius: tree_support_tip_diameter / 2 at the tip, widening
 *     at tree_support_branch_diameter_angle, capped at tree_support_branch_diameter / 2. Per layer a
 *     branch shifts at most the filament's overhang step (or h * tan(tree_support_branch_angle), another
 *     slicer's lean, when set): first away from the object, to keep
 *     radius + support_object_xy_distance clear of it; then, with what is left, toward its nearest
 *     neighbor. Branches whose centers come within half their summed radii merge (area-weighted
 *     center, the larger radius).
 *  5. A branch whose center cannot get out of the object rests on it (the layer above is its last)
 *     or, with support_on_build_plate_only, is removed with everything merged into it.
 *  6. Each layer: the union of the branch circles minus the object grown by the XY gap and minus the
 *     roof, printed as a loop plus lines inside (a 0.8 mm tip has no room for lines alone); bottom
 *     interface = each branch's lowest support_interface_bottom_layers.
 *
 * NOT YET: looking ahead to steer around the object before reaching it (a branch only dodges what
 * is on its own layer), support_critical_regions_only, painted enforcers/blockers.
 */
object TreeSupports {
    /**
     * [branchAngleDeg]: another slicer's steepest lean (investigation); null = a branch shifts at most
     * [leanStepUm] per layer: a leaning branch is an overhang, so it may step out as far as the
     * filament holds one (its overhang step), whatever the layer's height.
     */
    class Params(val branchAngleDeg: Double?, val tipDiameterUm: Long, val branchDiameterUm: Long, val diameterAngleDeg: Double, val branchDistanceUm: Long,
                 val leanStepUm: Long = 0)

    /** Chord tolerance of a branch circle: a geometric tolerance, not a print parameter (Offset's own default). */
    private const val ARC_TOL_UM = 5L

    private class Node(var x: Double, var y: Double, var r: Double, var depthUm: Double) {
        var budget = 0.0                            // shift still allowed on the current layer, µm
        val circles = ArrayList<DoubleArray>()      // layer, x, y, r
        val absorbed = ArrayList<Node>()
        fun all(): Sequence<Node> = sequenceOf(this) + absorbed.asSequence().flatMap { it.all() }
    }

    /** The object's outline on one layer, for inside / nearest-boundary queries. */
    private class Obstacle(set: List<Polygon>) {
        private val groups = Polygons.group(set)
        private val polys = set
        private val boxes = set.map { it.bounds() }
        fun inside(x: Double, y: Double): Boolean {
            val xi = Math.round(x); val yi = Math.round(y)
            return groups.any { (o, hs) -> Polygons.contains(o, xi, yi) && hs.none { Polygons.contains(it, xi, yi) } }
        }
        /** Nearest boundary point within [reach] of (x, y), or null: returns x, y, distance. */
        fun nearest(x: Double, y: Double, reach: Double): DoubleArray? {
            var best: DoubleArray? = null
            for ((k, p) in polys.withIndex()) {
                val b = boxes[k]
                if (x < b[0] - reach || x > b[2] + reach || y < b[1] - reach || y > b[3] + reach) continue
                val n = p.size
                for (i in 0 until n) {
                    val ax = p.x(i).toDouble(); val ay = p.y(i).toDouble()
                    val bx = p.x((i + 1) % n).toDouble(); val by = p.y((i + 1) % n).toDouble()
                    val dx = bx - ax; val dy = by - ay; val l2 = dx * dx + dy * dy
                    val u = if (l2 == 0.0) 0.0 else (((x - ax) * dx + (y - ay) * dy) / l2).coerceIn(0.0, 1.0)
                    val px = ax + u * dx; val py = ay + u * dy
                    val d = Math.hypot(x - px, y - py)
                    if (d <= reach && (best == null || d < best[2])) best = doubleArrayOf(px, py, d)
                }
            }
            return best
        }
    }

    fun generate(outlines: List<List<Polygon>>, heightsUm: LongArray, p: Supports.Params, t: Params, layerTopsUm: LongArray? = null): List<LayerSupport> {
        val n = outlines.size
        val empty = LayerSupport(emptyList(), emptyList(), emptyList())
        if (n < 2) return List(n) { empty }
        val overhang = Supports.overhangs(outlines, p, layerTopsUm)
        val grown = Array<List<Polygon>?>(n) { null }
        fun grownAt(j: Int) = grown[j] ?: Offset.offset(outlines[j], p.xyGapUm).also { grown[j] = it }

        // 2. roofs, and where each overhang's tips start
        val roof = Array<List<Polygon>>(n) { emptyList() }
        val tipsAt = Array<MutableList<List<Polygon>>>(n) { ArrayList() }
        for (k in 1 until n) {
            if (overhang[k].isEmpty()) continue
            val top = k - 1 - p.topGapLayers
            for (j in maxOf(0, top - p.interfaceTopLayers + 1)..top) if (j >= 0) roof[j] = if (roof[j].isEmpty()) overhang[k] else Booleans.union(roof[j], overhang[k])
            val tipLayer = top - p.interfaceTopLayers
            if (tipLayer >= 0) tipsAt[tipLayer].add(overhang[k])
        }

        val tanMove = t.branchAngleDeg?.let { Math.tan(Math.toRadians(it)) }
        val tanGrow = Math.tan(Math.toRadians(t.diameterAngleDeg))
        val tipR = t.tipDiameterUm / 2.0; val maxR = maxOf(tipR, t.branchDiameterUm / 2.0)
        val pitch = t.branchDistanceUm.toDouble()
        val roots = ArrayList<Node>()                 // every branch that survives (landed or reached the plate)
        var active = ArrayList<Node>()
        for (j in n - 1 downTo 0) {
            val h = heightsUm[j].toDouble()
            val obstacle = Obstacle(outlines[j])
            val maxMove = if (tanMove != null) h * tanMove else t.leanStepUm.toDouble()
            // carried branches: grow, then dodge the object, then lean toward a neighbor
            val next = ArrayList<Node>(active.size)
            for (nd in active) {
                nd.depthUm += h
                nd.r = minOf(maxR, tipR + nd.depthUm * tanGrow)
                val need = nd.r + p.xyGapUm
                var budget = maxMove
                if (obstacle.inside(nd.x, nd.y)) {
                    val q = obstacle.nearest(nd.x, nd.y, maxMove)
                    if (q != null && q[2] + need <= maxMove) {
                        val ux = (q[0] - nd.x) / q[2]; val uy = (q[1] - nd.y) / q[2]
                        nd.x = q[0] + ux * need; nd.y = q[1] + uy * need; budget = 0.0
                    } else {
                        if (!p.onPlateOnly) roots.add(nd)   // rests on the object: its last circle is on the layer above
                        continue
                    }
                } else {
                    val q = obstacle.nearest(nd.x, nd.y, need)
                    if (q != null && q[2] > 0) {
                        val s = minOf(need - q[2], budget)
                        nd.x += (nd.x - q[0]) / q[2] * s; nd.y += (nd.y - q[1]) / q[2] * s; budget -= s
                    }
                }
                nd.budget = budget
                next.add(nd)
            }
            // lean toward the nearest neighbor with what is left, unless that would touch the object
            for (nd in next) {
                if (nd.budget <= 0.0) continue
                var best: Node? = null; var bd = Double.MAX_VALUE
                for (o in next) if (o !== nd) { val d = Math.hypot(o.x - nd.x, o.y - nd.y); if (d < bd) { bd = d; best = o } }
                if (best == null || bd == 0.0) continue
                val s = minOf(nd.budget, bd / 2)
                val nx = nd.x + (best.x - nd.x) / bd * s; val ny = nd.y + (best.y - nd.y) / bd * s
                if (!obstacle.inside(nx, ny) && obstacle.nearest(nx, ny, nd.r + p.xyGapUm) == null) { nd.x = nx; nd.y = ny }
            }
            // merge
            var merged = true
            while (merged) {
                merged = false
                loop@ for (a in next.indices) for (b in a + 1 until next.size) {
                    val na = next[a]; val nb = next[b]
                    if (Math.hypot(na.x - nb.x, na.y - nb.y) < (na.r + nb.r) / 2) {
                        val wa = na.r * na.r; val wb = nb.r * nb.r
                        na.x = (na.x * wa + nb.x * wb) / (wa + wb); na.y = (na.y * wa + nb.y * wb) / (wa + wb)
                        na.r = maxOf(na.r, nb.r); na.depthUm = maxOf(na.depthUm, nb.depthUm)
                        na.absorbed.add(nb); next.removeAt(b); merged = true; break@loop
                    }
                }
            }
            // new tips on this layer
            for (oh in tipsAt[j]) {
                val pts = ArrayList<DoubleArray>()
                for ((outer, holes) in Polygons.group(oh)) {
                    val b = outer.bounds()
                    var hit = false
                    var ix = Math.ceil(b[0] / pitch).toLong()
                    while (ix * pitch <= b[2]) {
                        var iy = Math.ceil(b[1] / pitch).toLong()
                        while (iy * pitch <= b[3]) {
                            val x = ix * pitch; val y = iy * pitch
                            val xi = Math.round(x); val yi = Math.round(y)
                            if (Polygons.contains(outer, xi, yi) && holes.none { Polygons.contains(it, xi, yi) }) { pts.add(doubleArrayOf(x, y)); hit = true }
                            iy++
                        }
                        ix++
                    }
                    if (!hit) {
                        // a piece the lattice misses: one tip at a point inside it (half a line in)
                        val inner = Offset.offset(listOf(outer) + holes, -p.lineWidthUm / 2).firstOrNull { it.isCcw() } ?: continue
                        pts.add(doubleArrayOf(inner.x(0).toDouble(), inner.y(0).toDouble()))
                    }
                }
                for (q in pts) {
                    if (next.any { Math.hypot(it.x - q[0], it.y - q[1]) < pitch / 2 }) continue
                    if (obstacle.inside(q[0], q[1])) continue
                    next.add(Node(q[0], q[1], tipR, 0.0))
                }
            }
            for (nd in next) nd.circles.add(doubleArrayOf(j.toDouble(), nd.x, nd.y, nd.r))
            active = next
        }
        roots.addAll(active)                          // reached the plate

        // 6. per layer: circles -> areas
        val circlesAt = Array<MutableList<Polygon>>(n) { ArrayList() }
        val bottomAt = Array<MutableList<Polygon>>(n) { ArrayList() }
        for (root in roots) for (nd in root.all()) {
            val lowest = nd.circles.minOfOrNull { it[0].toInt() } ?: continue
            for (c in nd.circles) {
                val j = c[0].toInt()
                val disc = Offset.disc(Math.round(c[1]), Math.round(c[2]), Math.round(c[3]), ARC_TOL_UM)
                if (j - lowest < p.interfaceBottomLayers) bottomAt[j].add(disc) else circlesAt[j].add(disc)
            }
        }
        return List(n) { j ->
            if (circlesAt[j].isEmpty() && bottomAt[j].isEmpty() && roof[j].isEmpty()) return@List empty
            val block = grownAt(j)
            val top = if (roof[j].isEmpty()) emptyList() else Booleans.difference(roof[j], block)
            val area = if (circlesAt[j].isEmpty()) emptyList() else Booleans.difference(Booleans.difference(Booleans.unionPositive(circlesAt[j]), block), top)
            val bottom = if (bottomAt[j].isEmpty()) emptyList() else Booleans.difference(Booleans.difference(Booleans.unionPositive(bottomAt[j]), block), top)
            val w = p.lineWidthUm
            LayerSupport(Offset.offset(area, -w), top, bottom, Offset.offset(area, -w / 2))
        }
    }
}
