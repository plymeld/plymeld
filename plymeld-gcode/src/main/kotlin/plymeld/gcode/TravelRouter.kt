package plymeld.gcode

import plymeld.program.Extrude
import plymeld.program.LayerStart
import plymeld.program.Park
import plymeld.program.Pause
import plymeld.program.PrintProgram
import plymeld.program.Step

/**
 * Travels routed around the layer's OTHER islands ("avoid crossing walls"; `plan.mechanism.motion.avoid_crossing`;
 * strings from a purge tower to a part embedded themselves in another part standing between them, a
 * permanent artifact). A travel that would fly straight over an island it neither leaves nor enters goes around it
 * instead, outside its outer wall by a margin, so a string it draws lies over nothing.
 *
 * The islands are read from the program itself: every closed, counter-clockwise outer-wall loop on the layer is an
 * island, taken as its convex hull grown by [MARGIN_UM] plus half the wall's width (a hull, not the outline: the walk
 * around it is short and simple, and the travel stays outside the island either way). An island that holds the
 * travel's start or end cannot be avoided and is not an obstacle; crossing the island being printed is the retraction
 * rules' business (no_retract_in_island), not this. The detour walks the hull from the entry point to the exit point the
 * shorter way round, obstacle by obstacle along the straight line, then shortcuts what it can; a detour longer than
 * [MAX_DETOUR_RATIO] times the straight travel plus [MAX_DETOUR_EXTRA_UM] is not taken (the straight hop with its lift
 * stays). After a pause or a park the head's place is unknown and the next travel is not routed; after a tool change it
 * is (the head is where the purge ended).
 *
 * The margin and the cap are settings (`plan.mechanism.motion.detour.margin`, `ratio.max`, `extra.max`,
 * passed in by the writer); the constants here are the defaults for a caller that passes none, and the bundled values
 * (plymeld's choice) are placeholders.
 */
object TravelRouter {
    /** How far outside an island's outer wall the detour runs (beyond the wall's center line plus half its width), µm. */
    const val MARGIN_UM = 1000L
    /** The longest detour taken: this many times the straight travel ... */
    const val MAX_DETOUR_RATIO = 2.0
    /** ... plus this much, µm. */
    const val MAX_DETOUR_EXTRA_UM = 20_000L
    private const val MAX_ROUNDS = 8
    /** The interior test's shrink, µm: a travel along a hull's edge is not a crossing. */
    private const val EPS_UM = 20.0
    private const val OUTER_WALL = "Outer wall"

    /** A convex polygon (counter-clockwise), coordinates in µm as doubles. */
    class Hull(val xy: DoubleArray, /** the island's own hull before the margin: whether a travel's end is IN the island is judged on it */ val raw: DoubleArray = xy) {
        val n = xy.size / 2
        fun x(i: Int) = xy[2 * (i % n)]
        fun y(i: Int) = xy[2 * (i % n) + 1]
        /** Inside or on the boundary of the island itself (the ungrown hull): a travel starting within the margin of an island is not inside it. */
        fun contains(px: Double, py: Double): Boolean {
            val m = raw.size / 2
            for (i in 0 until m) {
                val x0 = raw[2 * i]; val y0 = raw[2 * i + 1]; val x1 = raw[2 * ((i + 1) % m)]; val y1 = raw[2 * ((i + 1) % m) + 1]
                if ((x1 - x0) * (py - y0) - (y1 - y0) * (px - x0) < -1e-6) return false
            }
            return true
        }
        /**
         * The segment a -> b clipped to the hull shrunk by [epsUm] (Cyrus-Beck): the parameters where it enters and leaves
         * and the edges it does so through (-1 when the end lies inside); null when it misses.
         */
        fun clip(ax: Double, ay: Double, bx: Double, by: Double, epsUm: Double): Crossing? {
            var tIn = 0.0; var tOut = 1.0; var eIn = -1; var eOut = -1
            val dx = bx - ax; val dy = by - ay
            for (i in 0 until n) {
                // inward unit normal of edge i (CCW polygon): (-ey, ex) / |e|; f(X) = n . (X - P_i) - eps >= 0 inside
                val ex = x(i + 1) - x(i); val ey = y(i + 1) - y(i); val l = Math.hypot(ex, ey)
                if (l < 1e-9) continue
                val nx = -ey / l; val ny = ex / l
                val num = nx * (ax - x(i)) + ny * (ay - y(i)) - epsUm
                val den = nx * dx + ny * dy
                if (Math.abs(den) < 1e-12) { if (num < 0) return null; continue }
                val t = -num / den
                if (den > 0) { if (t > tIn) { tIn = t; eIn = i } }   // f grows along the segment: it enters this half-plane at t
                else { if (t < tOut) { tOut = t; eOut = i } }        // f falls: it leaves at t
                if (tIn > tOut) return null
            }
            return Crossing(tIn, eIn, tOut, eOut)
        }
        /** A proper pass through the hull: entering and leaving within the segment, through known edges. */
        fun cross(ax: Double, ay: Double, bx: Double, by: Double): Crossing? {
            val c = clip(ax, ay, bx, by, 0.0) ?: return null
            return if (c.edgeIn < 0 || c.edgeOut < 0 || c.tIn >= c.tOut) null else c
        }
        /** Whether the segment passes through the hull's interior (its ends may lie on the boundary; a walk along an edge does not). */
        fun throughInterior(ax: Double, ay: Double, bx: Double, by: Double): Boolean {
            val c = clip(ax, ay, bx, by, EPS_UM) ?: return false
            return (c.tOut - c.tIn) * Math.hypot(bx - ax, by - ay) > EPS_UM
        }
        fun perimeterBetween(fromVertex: Int, toVertex: Int, forward: Boolean): Double {
            var d = 0.0; var i = fromVertex
            while (i % n != toVertex % n) { val j = if (forward) i + 1 else i - 1 + n; d += Math.hypot(x(j) - x(i), y(j) - y(i)); i = j % n }
            return d
        }
    }
    class Crossing(val tIn: Double, val edgeIn: Int, val tOut: Double, val edgeOut: Int)

    /** The program with every travel that can go around the layer's other islands doing so. */
    /**
     * [program] with its travels routed around the layer's islands: [marginUm] outside each island's outer wall
     * (plan.mechanism.motion.detour.margin), a detour longer than [detourRatio] times the straight travel plus
     * [detourExtraUm] not taken (detour.ratio.max, detour.extra.max).
     */
    fun route(program: PrintProgram, marginUm: Long = MARGIN_UM, detourRatio: Double = MAX_DETOUR_RATIO, detourExtraUm: Long = MAX_DETOUR_EXTRA_UM,
              /** How far inside an island's outer wall a travel within it runs (plan.mechanism.motion.detour.inset); null = the layer's outer-wall width. */
              combInsetUm: Long? = null,
              /** A route along the wall to or from a speck ([alongWall]): its caps (detour.speck.ratio.max, speck.extra.max). */
              speckRatio: Double = SPECK_MAX_RATIO, speckExtraUm: Long = SPECK_MAX_EXTRA_UM): PrintProgram =
        routed(program, marginUm, detourRatio, detourExtraUm, combInsetUm, speckRatio, speckExtraUm).program

    /** A routed program with each layer's comb regions, in layer order (made when first needed), for a travel planned again later (a wipe's: KlipperWriter). */
    class Routed(val program: PrintProgram, val combs: List<Lazy<CombLayer>>)

    /** [route], with the comb regions it used. */
    fun routed(program: PrintProgram, marginUm: Long = MARGIN_UM, detourRatio: Double = MAX_DETOUR_RATIO, detourExtraUm: Long = MAX_DETOUR_EXTRA_UM,
               combInsetUm: Long? = null, speckRatio: Double = SPECK_MAX_RATIO, speckExtraUm: Long = SPECK_MAX_EXTRA_UM): Routed {
        val steps = program.steps
        val starts = steps.indices.filter { steps[it] is LayerStart }
        if (starts.isEmpty()) return Routed(program, emptyList())
        val layerCombs = ArrayList<Lazy<CombLayer>>(starts.size)
        val out = ArrayList<Step>(steps.size)
        out.addAll(steps.subList(0, starts[0]))
        // the head's place: the last path's end, kept across layers (the first travel of a layer, from the purge tower's base or
        // the last island of the layer below, is routed like any other; once missed), lost at a pause or a park
        var px = Double.NaN; var py = Double.NaN
        // whether the last path was an open outer-wall line (a speck's end may lie past the walls: CombRegion.edgeReach), and a speck
        var pOpenOuter = false; var pSpeck = false
        var below: Lazy<CombLayer>? = null
        for ((k, s0) in starts.withIndex()) {
            val s1 = if (k + 1 < starts.size) starts[k + 1] else steps.size
            val layer = steps.subList(s0, s1)
            val hulls = islands(layer, marginUm)
            val combs = lazy { combRegions(layer, combInsetUm) }
            layerCombs.add(combs)
            var first = true
            for (st in layer) {
                when (st) {
                    is Pause, is Park -> { px = Double.NaN; pOpenOuter = false; pSpeck = false; out.add(st); continue }
                    is Extrude -> {
                        var e = st
                        // within one island: stay inside it (combing), whether or not the travel retracts (a path laid exactly
                        // as written, a calibration plate's, is not touched)
                        val openOuter = st.feature == OUTER_WALL && !st.closed
                        var inside = if (px.isNaN() || st.exact != null) null
                            else combFrom(px, py, st, first, combs, below, detourRatio, detourExtraUm, pOpenOuter)
                        // to or from a speck, where combing found no way: along the outer wall the speck lines, a travel over the
                        // visible wall that retracts as usual (a speck's hop, lifted and not retracted, is a short step or a route
                        // kept inside the part: Extrude.hops)
                        // (a step short enough to be a speck's hop stays one: Extrude.hops, its blob without a retraction)
                        var along: LongArray? = null
                        if (inside == null && !px.isNaN() && st.exact == null && !first && (pSpeck || isSpeck(st)) &&
                            Math.hypot(st.xy[0] - px, st.xy[1] - py) > plymeld.program.HOP_WIDTHS * st.widthUm &&
                            (combs.value.crossesWall(px, py, st.xy[0].toDouble(), st.xy[1].toDouble()) ||
                                combs.value.overAir(px, py, st.xy[0].toDouble(), st.xy[1].toDouble())))
                            along = alongWall(px, py, st.xy[0].toDouble(), st.xy[1].toDouble(), combs.value, speckRatio, speckExtraUm)
                        first = false
                        if (inside != null) e = st.withVia(inside)
                        else if (along != null) e = st.withVia(along).withHopOnly(false).withRetract(true)
                        else if (!px.isNaN() && st.retractBefore && hulls.isNotEmpty()) {
                            val via = detour(px, py, st.xy[0].toDouble(), st.xy[1].toDouble(), hulls, detourRatio, detourExtraUm)
                            if (via != null) e = st.withVia(via)
                        }
                        val m = st.xy.size / 2; val end = if (st.closed) 0 else m - 1
                        px = st.xy[2 * end].toDouble(); py = st.xy[2 * end + 1].toDouble(); pOpenOuter = openOuter; pSpeck = isSpeck(st)
                        out.add(e); continue
                    }
                    else -> out.add(st)
                }
            }
            below = combs
        }
        return Routed(PrintProgram(out, program.objects, program.exact, program.selfPrimed), layerCombs)
    }

    /**
     * The route of the travel from (ax, ay) to [to]'s start, kept inside its island ([comb]); null: straight. A layer's first
     * travel starts where the layer below ended, with nothing of this layer printed yet: it is kept inside the layer below's
     * island ([below]), the head's own, and only its last hop may cross that island's edge to a start just past it (an
     * overhang's); combed in this layer's islands, whose outline the start lay outside, the hop in counted as a
     * wall crossing and 36 of the regression Benchy's travels over air were left straight. Where no route stays in the layer
     * below's island (the start's island is new on this layer), this layer's islands are tried, as before; one crossing none of the
     * layer below's walls stays straight.
     */
    internal fun combFrom(ax: Double, ay: Double, to: Extrude, first: Boolean, combs: Lazy<CombLayer>, below: Lazy<CombLayer>?,
                          detourRatio: Double, detourExtraUm: Long, fromOpenOuter: Boolean): LongArray? {
        val bx = to.xy[0].toDouble(); val by = to.xy[1].toDouble()
        if (first && below != null) {
            if (!below.value.crossesWall(ax, ay, bx, by)) return null
            comb(ax, ay, bx, by, below.value, detourRatio, detourExtraUm, edgeA = fromOpenOuter, edgeB = true)?.let { return it }
        }
        return comb(ax, ay, bx, by, combs.value, detourRatio, detourExtraUm, edgeA = fromOpenOuter, edgeB = to.feature == OUTER_WALL && !to.closed)
    }

    /** The length of a polyline given as flat x y ... (µm). */
    private fun polylineLength(c: List<Long>): Double {
        var len = 0.0
        for (k in 2 until c.size step 2) len += Math.hypot((c[k] - c[k - 2]).toDouble(), (c[k + 1] - c[k - 1]).toDouble())
        return len
    }

    /** An outer-wall speck: an open outer-wall line shorter than two of its widths (the planner's rule for ToolPath.speck). */
    fun isSpeck(e: Extrude): Boolean = e.feature == OUTER_WALL && !e.closed && polylineLength(e.xy.asList()) < 2.0 * e.widthUm

    /** How far (in the layer's widest outer walls) a travel's end may lie from an outer-wall loop and still be routed along it ([alongWall]): a search bound, not a print value. */
    const val SPECK_WALL_REACH = 3.0

    /** The bundled caps of a route along the wall to or from a speck (plan.mechanism.motion.detour.speck.ratio.max, speck.extra.max; the user's), for callers without settings. */
    const val SPECK_MAX_RATIO = 5.0
    const val SPECK_MAX_EXTRA_UM = 50_000L

    /**
     * The route of a travel to or from a speck along the outer wall it lines, when combing finds none (strings
     * across a raised name's letter gaps; at the letters' tips every inset region had thinned to nothing, and 37 of 42 travels
     * over 3 mm between specks on one raised name went straight across the gaps): both ends within [SPECK_WALL_REACH] of the
     * layer's widest outer walls from one outer-wall loop, the route goes in to the loop's nearest point, along the loop's
     * center line the shorter way round (over the wall just printed), and out to the other end. Null when no loop lies near
     * both ends, a hop in or out crosses another loop, or the route is longer than [detourRatio] x the straight travel +
     * [detourExtraUm]; of several loops, the shortest route.
     */
    fun alongWall(ax: Double, ay: Double, bx: Double, by: Double, layer: CombLayer, detourRatio: Double = SPECK_MAX_RATIO,
                  detourExtraUm: Long = SPECK_MAX_EXTRA_UM): LongArray? {
        if (layer.widestUm <= 0) return null
        val reach = SPECK_WALL_REACH * layer.widestUm
        val straight = Math.hypot(bx - ax, by - ay)
        var best: List<DoubleArray>? = null; var bestLen = straight * detourRatio + detourExtraUm
        // the nearest point of loop p to (x, y): edge, t along it, x, y, distance
        fun nearestOn(p: plymeld.geom.Polygon, x: Double, y: Double): DoubleArray {
            var r = doubleArrayOf(0.0, 0.0, 0.0, 0.0, Double.MAX_VALUE)
            for (e in 0 until p.size) {
                val x0 = p.x(e).toDouble(); val y0 = p.y(e).toDouble(); val x1 = p.x((e + 1) % p.size).toDouble(); val y1 = p.y((e + 1) % p.size).toDouble()
                val dx = x1 - x0; val dy = y1 - y0; val l2 = dx * dx + dy * dy
                val t = if (l2 == 0.0) 0.0 else (((x - x0) * dx + (y - y0) * dy) / l2).coerceIn(0.0, 1.0)
                val qx = x0 + t * dx; val qy = y0 + t * dy; val d = Math.hypot(qx - x, qy - y)
                if (d < r[4]) r = doubleArrayOf(e.toDouble(), t, qx, qy, d)
            }
            return r
        }
        for (p in layer.walls) {
            val n = p.size
            if (n < 3) continue
            val qa = nearestOn(p, ax, ay); if (qa[4] > reach) continue
            val qb = nearestOn(p, bx, by); if (qb[4] > reach) continue
            // the hops in and out: over the speck's own sliver of the model, crossing no other loop
            if (layer.crossesWall(ax, ay, qa[2], qa[3]) || layer.crossesWall(qb[2], qb[3], bx, by)) continue
            val ea = qa[0].toInt(); val eb = qb[0].toInt()
            fun vtx(i: Int) = doubleArrayOf(p.x(i).toDouble(), p.y(i).toDouble())
            // forward: on along edge ea to its end vertex, round to edge eb's start vertex; backward: the other way
            val fwd = ArrayList<DoubleArray>(); fwd.add(doubleArrayOf(qa[2], qa[3]))
            if (!(ea == eb && qa[1] <= qb[1])) { var i = (ea + 1) % n; while (true) { fwd.add(vtx(i)); if (i == eb) break; i = (i + 1) % n } }
            fwd.add(doubleArrayOf(qb[2], qb[3]))
            val bwd = ArrayList<DoubleArray>(); bwd.add(doubleArrayOf(qa[2], qa[3]))
            if (!(ea == eb && qa[1] >= qb[1])) { var i = ea; while (true) { bwd.add(vtx(i)); if (i == (eb + 1) % n) break; i = (i - 1 + n) % n } }
            bwd.add(doubleArrayOf(qb[2], qb[3]))
            for (walk in listOf(fwd, bwd)) {
                var len = qa[4] + qb[4]
                for (k in 1 until walk.size) len += Math.hypot(walk[k][0] - walk[k - 1][0], walk[k][1] - walk[k - 1][1])
                if (len < bestLen) { bestLen = len; best = walk }
            }
        }
        val w = best ?: return null
        // the route's points, less the ends where the travel's own ends lie on the loop
        val pts = w.filterIndexed { k, q -> !(k == 0 && Math.hypot(q[0] - ax, q[1] - ay) < 1.0) && !(k == w.size - 1 && Math.hypot(q[0] - bx, q[1] - by) < 1.0) }
        if (pts.isEmpty()) return null
        val via = LongArray(2 * pts.size)
        for ((k, q) in pts.withIndex()) { via[2 * k] = Math.round(q[0]); via[2 * k + 1] = Math.round(q[1]) }
        return via
    }

    /**
     * One island of a layer as the region a travel inside it keeps to (combing): the island's printed area (its outer walls,
     * holes taken out) shrunk by the inset, as an outer ring and its holes (µm). A travel kept inside it runs over the
     * island's own walls and fill, never out over an edge or across a notch in its outline.
     */
    class CombRegion(val outer: plymeld.geom.Polygon, val holes: List<plymeld.geom.Polygon>,
                     /** How far outside the region a travel's end may lie and still be in this island: an end on an outer wall's center line is the inset from it (µm). */
                     val reach: Double,
                     /**
                      * How far outside the region an end may lie and still be reached from it by one short hop that may cross the
                      * island's own outer wall (µm): [reach] and one more outer wall's width. A speck (an outer-wall line kept
                      * because it lines the outline, at a tip or notch) lies just past the outer wall's loop, so any route to it
                      * crosses that wall there; the rest of the route stays inside.
                      */
                     val edgeReach: Double = reach) {
        val rings: List<plymeld.geom.Polygon> = listOf(outer) + holes
        private val box: LongArray = run {
            var x0 = Long.MAX_VALUE; var y0 = Long.MAX_VALUE; var x1 = Long.MIN_VALUE; var y1 = Long.MIN_VALUE
            for (i in 0 until outer.size) { x0 = minOf(x0, outer.x(i)); x1 = maxOf(x1, outer.x(i)); y0 = minOf(y0, outer.y(i)); y1 = maxOf(y1, outer.y(i)) }
            longArrayOf(x0, y0, x1, y1)
        }
        fun near(x: Double, y: Double, reach: Double) = x >= box[0] - reach && x <= box[2] + reach && y >= box[1] - reach && y <= box[3] + reach
        fun inside(x: Double, y: Double): Boolean {
            val ix = Math.round(x); val iy = Math.round(y)
            return plymeld.geom.Polygons.contains(outer, ix, iy) && holes.none { h -> plymeld.geom.Polygons.contains(h, ix, iy) && !onBoundary(h, x, y) }
        }
        private fun onBoundary(p: plymeld.geom.Polygon, x: Double, y: Double): Boolean {
            for (i in 0 until p.size) if (segDist(x, y, p.x(i).toDouble(), p.y(i).toDouble(), p.x((i + 1) % p.size).toDouble(), p.y((i + 1) % p.size).toDouble()) < 1.0) return true
            return false
        }
        /** Every proper crossing of a -> b with a ring edge: (t along a -> b, ring, edge), by t; touching at an end or along an edge is not one. */
        fun crossings(ax: Double, ay: Double, bx: Double, by: Double): List<DoubleArray> = ringCrossings(rings, ax, ay, bx, by)
        /** Whether a -> b stays in the region: no proper crossing and its middle inside. */
        fun keeps(ax: Double, ay: Double, bx: Double, by: Double): Boolean =
            crossings(ax, ay, bx, by).isEmpty() && inside((ax + bx) / 2, (ay + by) / 2)
        /** The nearest point of the region's rings to (x, y), with its distance. */
        fun nearest(x: Double, y: Double): DoubleArray {
            var best = doubleArrayOf(Double.NaN, Double.NaN, Double.MAX_VALUE)
            for (p in rings) for (i in 0 until p.size) {
                val q = closest(x, y, p.x(i).toDouble(), p.y(i).toDouble(), p.x((i + 1) % p.size).toDouble(), p.y((i + 1) % p.size).toDouble())
                val d = Math.hypot(q[0] - x, q[1] - y)
                if (d < best[2]) best = doubleArrayOf(q[0], q[1], d)
            }
            return best
        }
    }

    /** Touching within this of a ring (µm) is not crossing it: a point on the comb region's edge is in it. */
    private const val TOUCH_UM = 2.0

    /** Every proper crossing of a -> b with an edge of [rings]: (t along a -> b, ring, edge), by t; touching at an end or along an edge is not one. */
    private fun ringCrossings(rings: List<plymeld.geom.Polygon>, ax: Double, ay: Double, bx: Double, by: Double): List<DoubleArray> {
        val out = ArrayList<DoubleArray>()
        val dx = bx - ax; val dy = by - ay
        for ((r, p) in rings.withIndex()) for (e in 0 until p.size) {
            val cx = p.x(e).toDouble(); val cy = p.y(e).toDouble(); val ex = p.x((e + 1) % p.size).toDouble(); val ey = p.y((e + 1) % p.size).toDouble()
            val fx = ex - cx; val fy = ey - cy
            val den = dx * fy - dy * fx
            if (Math.abs(den) < 1e-9) continue
            val t = ((cx - ax) * fy - (cy - ay) * fx) / den
            val u = ((cx - ax) * dy - (cy - ay) * dx) / den
            val tEps = TOUCH_UM / Math.max(1.0, Math.hypot(dx, dy)); val uEps = TOUCH_UM / Math.max(1.0, Math.hypot(fx, fy))
            if (t > tEps && t < 1 - tEps && u > -uEps && u < 1 + uEps) out.add(doubleArrayOf(t, r.toDouble(), e.toDouble()))
        }
        out.sortBy { it[0] }
        return out
    }

    /**
     * A layer's comb regions with the outer walls they were made from: a travel is combed only when its straight line crosses
     * one of [walls] (it leaves its island or crosses a notch: what strings over a seen face), and a route is kept only when
     * none of its legs crosses one (rerouting a travel that ran along a wall without crossing it sent a step in
     * across another wall, and the pre-print check refused it).
     */
    class CombLayer(
        /**
         * The regions at each inset of the ladder ([COMB_INSET_SHARES] of the inset, the largest first), each made when a
         * travel first needs it: a thin shell whose region vanishes at the full inset keeps one at a smaller inset, a strip
         * down the middle of its material (an engraved name's notched shell, thinner than two
         * insets, left its travels straight across its face).
         */
        val levels: List<Lazy<List<CombRegion>>>, val walls: List<plymeld.geom.Polygon>,
        /** The widest outer wall on the layer (µm): how far a speck may lie from the wall it lines ([alongWall]). */
        val widestUm: Long = 0) {
        /** The regions at the full inset. */
        val regions: List<CombRegion> get() = levels.firstOrNull()?.value ?: emptyList()
        fun crossesWall(ax: Double, ay: Double, bx: Double, by: Double): Boolean = ringCrossings(walls, ax, ay, bx, by).isNotEmpty()
        /**
         * Whether a -> b runs over air: a point along it, sampled every [stepUm] between its ends, lies outside every outer-wall
         * loop (even-odd: inside a hole is outside). A travel between two specks just past one loop crosses no wall yet runs
         * outside the part all the way (in front of a raised name's letters, 12.7 mm, 0.29 mm out).
         */
        fun overAir(ax: Double, ay: Double, bx: Double, by: Double, stepUm: Double = 250.0): Boolean {
            val n = Math.ceil(Math.hypot(bx - ax, by - ay) / stepUm).toInt()
            for (k in 1 until n) {
                val x = Math.round(ax + (bx - ax) * k / n); val y = Math.round(ay + (by - ay) * k / n)
                if (walls.count { plymeld.geom.Polygons.contains(it, x, y) } % 2 == 0) return true
            }
            return false
        }
        companion object { val NONE = CombLayer(emptyList(), emptyList()) }
    }

    /**
     * The insets a travel is combed at, as shares of the comb inset, tried largest first: the route stays as deep inside the
     * island as the ends and the material between them allow, and in material thinner than two insets runs down its middle.
     * A search ladder (how finely to look for a region that survives), not a print value.
     */
    val COMB_INSET_SHARES = doubleArrayOf(1.0, 0.5, 0.25, 0.125)

    private fun closest(x: Double, y: Double, ax: Double, ay: Double, bx: Double, by: Double): DoubleArray {
        val dx = bx - ax; val dy = by - ay; val l2 = dx * dx + dy * dy
        val t = if (l2 == 0.0) 0.0 else (((x - ax) * dx + (y - ay) * dy) / l2).coerceIn(0.0, 1.0)
        return doubleArrayOf(ax + t * dx, ay + t * dy)
    }
    private fun segDist(x: Double, y: Double, ax: Double, ay: Double, bx: Double, by: Double): Double { val q = closest(x, y, ax, ay, bx, by); return Math.hypot(q[0] - x, q[1] - y) }

    /**
     * The layer's islands as comb regions: every outer-wall ring (closed loops, and chains of open pieces laid end to end, a
     * scarfed loop), counter-clockwise ones the islands, clockwise ones their holes; their printed area (nonzero union)
     * shrunk by [insetUm] (null: the widest outer wall's width on the layer).
     */
    internal fun combRegions(layer: List<Step>, insetUm: Long?): CombLayer {
        val rings = ArrayList<plymeld.geom.Polygon>()
        var widest = 0L
        var chain: ArrayList<Long>? = null
        var chainWidth = 0L
        fun flush() {
            // a chain shorter than two of its widths is a speck (an outer-wall line kept because it lines the outline, as the
            // planner marks them), not a loop: neither a region of its own nor a wall a travel crosses
            chain?.let { c -> if (c.size >= 6 && polylineLength(c) >= 2.0 * chainWidth) rings.add(plymeld.geom.Polygon(c.toLongArray())) }
            chain = null; chainWidth = 0L
        }
        for (st in layer) {
            if (st !is Extrude) continue
            if (st.feature != OUTER_WALL || st.exact != null) { flush(); continue }
            widest = maxOf(widest, st.widthUm)
            if (st.closed) { flush(); if (st.xy.size >= 6) rings.add(plymeld.geom.Polygon(st.xy)); continue }
            val c = chain
            val continues = c != null && c.size >= 2 && c[c.size - 2] == st.xy[0] && c[c.size - 1] == st.xy[1]
            if (!continues) { flush(); chain = ArrayList() }
            val cc = chain!!
            // a piece continuing the chain repeats its end point
            val from = if (continues) 2 else 0
            for (k in from until st.xy.size) cc.add(st.xy[k])
            chainWidth = maxOf(chainWidth, st.widthUm)
        }
        flush()
        if (rings.isEmpty()) return CombLayer.NONE
        val full = insetUm ?: widest
        val area by lazy { plymeld.geom.Booleans.normalize(rings) }
        val insets = if (full > 0) COMB_INSET_SHARES.map { Math.round(full * it) }.filter { it > 0 }.distinct() else listOf(0L)
        val levels = insets.map { inset ->
            lazy {
                val shrunk = if (inset > 0) plymeld.geom.Offset.offset(area, -inset) else area
                // an end on an outer wall's center line lies the inset from the region; half a wall more covers an end on the wall's edge
                val reach = inset + widest / 2.0 + TOUCH_UM
                plymeld.geom.Polygons.group(shrunk).map { (o, hs) -> CombRegion(o, hs, reach, reach + widest) }
            }
        }
        return CombLayer(levels, rings, widest)
    }

    /**
     * The points a travel a -> b within one island goes through to stay inside it (combing; a quality-weighted
     * print's travels left its base across the notches of an engraved name, several hundred over one face, every one retracted
     * and still stringing): both ends taken into the island's comb region (an end on an outer wall is within an inset of it),
     * then, where the straight line leaves the region, the region's edge walked the shorter way round to where the line comes
     * back, and the walk shortcut wherever a straight hop stays inside. Null when the ends are not in one island, the straight
     * line already stays inside, or the route would be longer than [detourRatio] x the straight travel + [detourExtraUm]. Tried at
     * each inset of [CombLayer.levels], the largest first; the first route found is taken.
     */
    fun comb(ax: Double, ay: Double, bx: Double, by: Double, layer: CombLayer, detourRatio: Double = MAX_DETOUR_RATIO,
             detourExtraUm: Long = MAX_DETOUR_EXTRA_UM,
             /** whether the travel's start / end is at an open outer-wall line (a speck), which may lie past the walls ([CombRegion.edgeReach]) */
             edgeA: Boolean = false, edgeB: Boolean = false): LongArray? {
        if (layer.levels.isEmpty() || !layer.crossesWall(ax, ay, bx, by)) return null
        for (level in layer.levels) combIn(ax, ay, bx, by, level.value, layer, detourRatio, detourExtraUm, edgeA, edgeB)?.let { return it }
        return null
    }

    /** [comb] at one inset of the ladder: [regions] that inset's. */
    private fun combIn(ax: Double, ay: Double, bx: Double, by: Double, regions: List<CombRegion>, layer: CombLayer, detourRatio: Double,
                       detourExtraUm: Long, edgeOkA: Boolean, edgeOkB: Boolean): LongArray? {
        if (regions.isEmpty()) return null
        // an end entered from past [CombRegion.reach] (within edgeReach): its hop in is the one leg that may cross a wall
        var edgeA = false; var edgeB = false
        fun enter(x: Double, y: Double, edgeOk: Boolean): Pair<CombRegion, DoubleArray>? {
            var best: Pair<CombRegion, DoubleArray>? = null; var bd = Double.MAX_VALUE
            for (r in regions) {
                val reach = if (edgeOk) r.edgeReach else r.reach
                if (!r.near(x, y, reach)) continue
                if (r.inside(x, y)) return r to doubleArrayOf(x, y)
                val q = r.nearest(x, y)
                if (q[2] <= reach && q[2] < bd) {
                    // a little past the region's edge, into it (on from the end through its nearest edge point): a point exactly
                    // on the edge leaves the walk no crossing to come back through, and the travel was left straight
                    val ux = (q[0] - x) / q[2]; val uy = (q[1] - y) / q[2]
                    val ix = q[0] + ux * 3 * TOUCH_UM; val iy = q[1] + uy * 3 * TOUCH_UM
                    bd = q[2]; best = r to (if (q[2] > 0 && r.inside(ix, iy)) doubleArrayOf(ix, iy) else doubleArrayOf(q[0], q[1]))
                }
            }
            return best
        }
        val (ra, pa) = enter(ax, ay, edgeOkA) ?: return null
        edgeA = !ra.inside(ax, ay) && Math.hypot(pa[0] - ax, pa[1] - ay) > ra.reach
        val (rb, pb) = enter(bx, by, edgeOkB) ?: return null
        edgeB = !rb.inside(bx, by) && Math.hypot(pb[0] - bx, pb[1] - by) > rb.reach
        if (ra !== rb) return null
        val r = ra
        val walk = ArrayList<DoubleArray>(); walk.add(pa)
        var cx = pa[0]; var cy = pa[1]
        var rounds = 0
        while (true) {
            if (rounds++ > COMB_MAX_ROUNDS) return null
            val cs = r.crossings(cx, cy, pb[0], pb[1])
            if (cs.isEmpty()) break
            val first = cs[0]
            val ring = first[1].toInt()
            val next = cs.firstOrNull { it[0] > first[0] && it[1].toInt() == ring } ?: return null
            val p = r.rings[ring]; val n = p.size
            fun vx(i: Int) = p.x(((i % n) + n) % n).toDouble()
            fun vy(i: Int) = p.y(((i % n) + n) % n).toDouble()
            val e0 = first[2].toInt(); val e1 = next[2].toInt()
            val xx = cx + (pb[0] - cx) * first[0]; val xy = cy + (pb[1] - cy) * first[0]
            val yx = cx + (pb[0] - cx) * next[0]; val yy = cy + (pb[1] - cy) * next[0]
            // forward: from edge e0's end vertex round to edge e1's start vertex; backward: e0's start back to e1's end
            var fwd = Math.hypot(vx(e0 + 1) - xx, vy(e0 + 1) - xy); run { var i = e0 + 1; while (((i - e1) % n + n) % n != 0) { fwd += Math.hypot(vx(i + 1) - vx(i), vy(i + 1) - vy(i)); i++ } }
            fwd += Math.hypot(yx - vx(e1), yy - vy(e1))
            var bwd = Math.hypot(vx(e0) - xx, vy(e0) - xy); run { var i = e0; while (((i - (e1 + 1)) % n + n) % n != 0) { bwd += Math.hypot(vx(i - 1) - vx(i), vy(i - 1) - vy(i)); i-- } }
            bwd += Math.hypot(yx - vx(e1 + 1), yy - vy(e1 + 1))
            walk.add(doubleArrayOf(xx, xy))
            if (fwd <= bwd) { var i = e0 + 1; while (true) { walk.add(doubleArrayOf(vx(i), vy(i))); if (((i - e1) % n + n) % n == 0) break; i++ } }
            else { var i = e0; while (true) { walk.add(doubleArrayOf(vx(i), vy(i))); if (((i - (e1 + 1)) % n + n) % n == 0) break; i-- } }
            walk.add(doubleArrayOf(yx, yy))
            cx = yx; cy = yy
        }
        walk.add(pb)
        // shortcuts: from each point, the farthest later point reachable without leaving the region
        val short = ArrayList<DoubleArray>(); short.add(walk[0])
        var i = 0
        while (i < walk.size - 1) {
            var j = walk.size - 1
            while (j > i + 1 && !r.keeps(walk[i][0], walk[i][1], walk[j][0], walk[j][1])) j--
            short.add(walk[j]); i = j
        }
        val pts = ArrayList<DoubleArray>()
        if (Math.hypot(pa[0] - ax, pa[1] - ay) > 1.0) pts.add(pa)
        for (k in 1 until short.size - 1) pts.add(short[k])
        if (Math.hypot(pb[0] - bx, pb[1] - by) > 1.0) pts.add(pb)
        if (pts.isEmpty()) return null
        var len = 0.0; var qx = ax; var qy = ay
        for ((k, q) in pts.withIndex()) {
            // no leg may cross an outer wall (a step in or out across a thin wall would): such a route is not taken; only the
            // hop in from an end past the walls (a speck's) may, being the crossing every route to that end makes
            if (!(k == 0 && edgeA) && layer.crossesWall(qx, qy, q[0], q[1])) return null
            len += Math.hypot(q[0] - qx, q[1] - qy); qx = q[0]; qy = q[1]
        }
        if (!edgeB && layer.crossesWall(qx, qy, bx, by)) return null
        len += Math.hypot(bx - qx, by - qy)
        if (len > Math.hypot(bx - ax, by - ay) * detourRatio + detourExtraUm) return null
        val via = LongArray(2 * pts.size)
        for ((k, q) in pts.withIndex()) { via[2 * k] = Math.round(q[0]); via[2 * k + 1] = Math.round(q[1]) }
        return via
    }

    /** How many times a travel may leave and re-enter its region before it is left straight (a bound on the walk, not a print value). */
    private const val COMB_MAX_ROUNDS = 64

    /** The points the travel a -> b goes through to avoid the hulls it would cross (neither end inside them); null = straight. */
    fun detour(ax: Double, ay: Double, bx: Double, by: Double, hulls: List<Hull>, detourRatio: Double = MAX_DETOUR_RATIO, detourExtraUm: Long = MAX_DETOUR_EXTRA_UM): LongArray? {
        val obstacles = hulls.filter { !it.contains(ax, ay) && !it.contains(bx, by) }
        if (obstacles.none { it.throughInterior(ax, ay, bx, by) }) return null
        val path = ArrayList<DoubleArray>()
        path.add(doubleArrayOf(ax, ay))
        var cx = ax; var cy = ay
        var rounds = 0
        while (rounds++ < MAX_ROUNDS) {
            // the first obstacle the rest of the straight line crosses
            var best: Hull? = null; var bc: Crossing? = null
            for (h in obstacles) { val c = h.cross(cx, cy, bx, by) ?: continue; if (bc == null || c.tIn < bc.tIn) { best = h; bc = c } }
            if (best == null) break
            val h = best; val c = bc!!
            val ex = cx + (bx - cx) * c.tIn; val ey = cy + (by - cy) * c.tIn
            val xx = cx + (bx - cx) * c.tOut; val xy = cy + (by - cy) * c.tOut
            // forward (CCW): from the entry edge's end vertex to the exit edge's start vertex; backward: the other way
            val fwdLen = Math.hypot(h.x(c.edgeIn + 1) - ex, h.y(c.edgeIn + 1) - ey) + h.perimeterBetween(c.edgeIn + 1, c.edgeOut, true) + Math.hypot(xx - h.x(c.edgeOut), xy - h.y(c.edgeOut))
            val bwdLen = Math.hypot(h.x(c.edgeIn) - ex, h.y(c.edgeIn) - ey) + h.perimeterBetween(c.edgeIn, c.edgeOut + 1, false) + Math.hypot(xx - h.x(c.edgeOut + 1), xy - h.y(c.edgeOut + 1))
            path.add(doubleArrayOf(ex, ey))
            if (fwdLen <= bwdLen) {
                var i = (c.edgeIn + 1) % h.n
                while (i != c.edgeOut % h.n) { path.add(doubleArrayOf(h.x(i), h.y(i))); i = (i + 1) % h.n }
                path.add(doubleArrayOf(h.x(c.edgeOut), h.y(c.edgeOut)))
            } else {
                var i = c.edgeIn % h.n
                while (i != (c.edgeOut + 1) % h.n) { path.add(doubleArrayOf(h.x(i), h.y(i))); i = (i - 1 + h.n) % h.n }
                path.add(doubleArrayOf(h.x(c.edgeOut + 1), h.y(c.edgeOut + 1)))
            }
            path.add(doubleArrayOf(xx, xy))
            cx = xx; cy = xy
        }
        path.add(doubleArrayOf(bx, by))
        // shortcuts: from each point, the farthest later point reachable without crossing an obstacle
        val short = ArrayList<DoubleArray>(); short.add(path[0])
        var i = 0
        while (i < path.size - 1) {
            var j = path.size - 1
            while (j > i + 1 && obstacles.any { it.throughInterior(path[i][0], path[i][1], path[j][0], path[j][1]) }) j--
            short.add(path[j]); i = j
        }
        // still crossing somewhere (hulls overlapping each other): not routed
        for (k in 0 until short.size - 1) if (obstacles.any { it.throughInterior(short[k][0], short[k][1], short[k + 1][0], short[k + 1][1]) }) return null
        val straight = Math.hypot(bx - ax, by - ay)
        var len = 0.0
        for (k in 0 until short.size - 1) len += Math.hypot(short[k + 1][0] - short[k][0], short[k + 1][1] - short[k][1])
        if (len > straight * detourRatio + detourExtraUm) return null
        if (short.size <= 2) return null
        val via = LongArray(2 * (short.size - 2))
        for (k in 1 until short.size - 1) { via[2 * (k - 1)] = Math.round(short[k][0]); via[2 * (k - 1) + 1] = Math.round(short[k][1]) }
        return via
    }

    /**
     * The layer's islands as grown hulls: every closed counter-clockwise outer wall, and every chain of open outer-wall pieces
     * laid end to end (a scarfed loop is three open pieces, Scarf.extrudes; once missed) that runs counter-clockwise.
     */
    internal fun islands(layer: List<Step>, marginUm: Long = MARGIN_UM): List<Hull> {
        val out = ArrayList<Hull>()
        var chain: ArrayList<Long>? = null; var chainW = 0L
        fun flush() {
            chain?.let { c -> val xy = c.toLongArray(); if (xy.size >= 6 && signedArea2(xy) > 0) hull(xy, marginUm + chainW / 2)?.let(out::add) }
            chain = null
        }
        for (st in layer) {
            if (st !is Extrude) continue
            if (st.feature != OUTER_WALL || st.exact != null) { flush(); continue }
            if (st.closed) { flush(); if (signedArea2(st.xy) > 0) hull(st.xy, marginUm + st.widthUm / 2)?.let(out::add); continue }
            val c = chain
            val continues = c != null && c.size >= 2 && c[c.size - 2] == st.xy[0] && c[c.size - 1] == st.xy[1]
            if (!continues) { flush(); chain = ArrayList() }
            for (v in st.xy) chain!!.add(v)
            chainW = maxOf(chainW, st.widthUm)
        }
        flush()
        return out
    }

    private fun signedArea2(xy: LongArray): Long {
        val n = xy.size / 2; var a = 0L
        for (i in 0 until n) { val j = (i + 1) % n; a += xy[2 * i] * xy[2 * j + 1] - xy[2 * j] * xy[2 * i + 1] }
        return a
    }

    /** The convex hull of the points (monotone chain), grown outward by [growUm] along each vertex's bisector; null for fewer than 3 distinct points. */
    fun hull(xy: LongArray, growUm: Long): Hull? {
        val n = xy.size / 2
        val pts = (0 until n).map { doubleArrayOf(xy[2 * it].toDouble(), xy[2 * it + 1].toDouble()) }.distinctBy { it[0] to it[1] }.sortedWith(compareBy({ it[0] }, { it[1] }))
        if (pts.size < 3) return null
        fun cross(o: DoubleArray, a: DoubleArray, b: DoubleArray) = (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0])
        val lower = ArrayList<DoubleArray>(); for (p in pts) { while (lower.size >= 2 && cross(lower[lower.size - 2], lower[lower.size - 1], p) <= 0) lower.removeAt(lower.size - 1); lower.add(p) }
        val upper = ArrayList<DoubleArray>(); for (p in pts.asReversed()) { while (upper.size >= 2 && cross(upper[upper.size - 2], upper[upper.size - 1], p) <= 0) upper.removeAt(upper.size - 1); upper.add(p) }
        val h = lower.dropLast(1) + upper.dropLast(1)
        if (h.size < 3) return null
        val m = h.size; val out = DoubleArray(2 * m)
        val raw = DoubleArray(2 * m); for (i in 0 until m) { raw[2 * i] = h[i][0]; raw[2 * i + 1] = h[i][1] }
        for (i in 0 until m) {
            val p = h[(i - 1 + m) % m]; val c = h[i]; val q = h[(i + 1) % m]
            // outward normals of the two edges at c (CCW: outward = (ey, -ex))
            var n1x = c[1] - p[1]; var n1y = -(c[0] - p[0]); var l1 = Math.hypot(n1x, n1y); n1x /= l1; n1y /= l1
            var n2x = q[1] - c[1]; var n2y = -(q[0] - c[0]); var l2 = Math.hypot(n2x, n2y); n2x /= l2; n2y /= l2
            var bx = n1x + n2x; var by = n1y + n2y; val bl = Math.hypot(bx, by)
            if (bl < 1e-9) { bx = n1x; by = n1y } else { bx /= bl; by /= bl }
            val cosHalf = (bx * n1x + by * n1y).coerceAtLeast(1.0 / 3)        // the miter, capped at 3x at a needle corner
            out[2 * i] = c[0] + bx * growUm / cosHalf; out[2 * i + 1] = c[1] + by * growUm / cosHalf
        }
        return Hull(out, raw)
    }
}
