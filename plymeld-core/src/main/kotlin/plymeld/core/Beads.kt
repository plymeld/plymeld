package plymeld.core

import plymeld.geom.Delaunay
import plymeld.geom.Polygon
import plymeld.geom.Skeleton

/**
 * Walls of a narrow island laid by bead count from the local width: an area of a layer where the set walls nowhere all fit
 * (Walls.arachne hands over every such island) is filled edge to edge by beads whose number and widths follow how wide the
 * area is at each place. plymeld's own construction; the idea of choosing the beads from the local width over the medial
 * axis is the published one of Kuipers et al. (2020), "A framework for adaptive width control of dense contour-parallel
 * toolpaths in fused deposition modeling" (no slicer's code read).
 *
 * The construction:
 *  1. The island's outline is sampled every [Params.sampleUm] and triangulated (Delaunay). Every triangle whose
 *     circumcenter lies inside the island is a node of its medial axis: its center, its radius r (the distance to the
 *     outline), the local width W = 2r, and its three ribs (the center to the outline samples it touches). Nodes sharing a
 *     triangle edge are neighbors.
 *  2. The beads across a place W wide ([Rule]): n of them, every width within the narrowest and the widest a wall line may
 *     be, as near the set widths as possible. One bead is as wide as the place; two share it equally; from three on, the
 *     outer two keep the set outer width (they are what shows) and the inner ones share the rest equally. Neighboring beads
 *     overlap by the bead's rounded flank, h (1 - pi / 4). At most twice the walls' count.
 *  3. Corners: a dead-end branch of the axis shorter than the width where it joins the rest, narrowing fast, runs into a
 *     corner of the outline; it takes the beads of that junction, so they turn the corner as a loop does. Elsewhere the
 *     count follows the width, with a dead band of the slicing tolerance (the outline is known no closer); a stretch with a
 *     count of its own shorter than the transition length takes its neighbors' count (but a neck narrower somewhere than
 *     the narrowest line never a count of loops: the loop turns at its mouth), and a middle line too short to be a
 *     line takes the count below: the count changes once where an area's width passes a threshold, not back and forth.
 *  4. Every pair of beads is a loop: loop k runs wherever a place holds 2k + 2 beads, its bead on each rib at its share's
 *     distance from the outline, traced sample by sample round each ring of the outline. Where the ring passes to a place
 *     too narrow for it (a tip, a neck, a sliver) the loop turns back across that place's mouth and carries on along the
 *     other side (the run beginning on the same node's rib to the other end of the triangle edge the narrow part hangs
 *     from), so a loop is closed wherever its place is wide enough, whatever the count does inside it. A short tip the loop
 *     runs into instead: its two sides at the narrowest width at least, then out and back along the axis to its end;
 *     where that run reaches no further past the loop's bead beside it than an interior line's end reaches into the walls,
 *     and the loop goes on past the tip (it turns no corner there), the run is left out.
 *  5. Where 2k + 1 beads fit, loop k's middle bead runs along the axis, over nodes of its own count only. Its ends meeting
 *     a loop stop where they reach into the loop's line as an interior line's end reaches into the walls ([Params.endOverlap]).
 *     Where the count rises from even to odd the middle bead is new within the transition length: it starts with its round
 *     end and the beads beside it give way to it across their own track (the outer loop across the outline, on its inner
 *     side only, no narrower than the narrowest line, never cut); a loop inside another gives way to the one outside it near
 *     their turns. A cut where a bead gave way entirely is not made where its two ends would lie within half a line.
 *  6. Open pieces' ends (a middle bead's at a junction of the axis) are joined closest first within a short reach. A stub
 *     at a junction (a piece from it to a dead end, its bead within the junction's but for the end overlap, its side of the
 *     axis reaching no further past the junction's bead than the narrowest place a line is laid in) is left out, not
 *     joined: the line through the junction does not run out along it and back.
 */
object Beads {
    /** The beads across a place W wide (µm): set widths, the walls' count, the beads' overlap, the narrowest and the widest line. */
    class Rule(val outerUm: Double, val innerUm: Double, val loops: Int, val flankUm: Double, val minUm: Double, val maxUm: Double) {
        /** The widths of [n] beads across [w], from one side. */
        fun widths(n: Int, w: Double): DoubleArray = when (n) {
            1 -> doubleArrayOf(maxOf(w, minUm))
            2 -> DoubleArray(2) { (w + flankUm) / 2 }
            else -> {
                val inner = (w - flankUm - 2 * (outerUm - flankUm)) / (n - 2) + flankUm
                DoubleArray(n) { if (it == 0 || it == n - 1) outerUm else inner }
            }
        }
        /** The centers of beads [ws] across [w] from one side (each overlapping the next by the flank). */
        fun centers(ws: DoubleArray, w: Double): DoubleArray {
            if (ws.size == 1) return doubleArrayOf(w / 2)
            val c = DoubleArray(ws.size); var x = 0.0
            for (i in ws.indices) { c[i] = x + ws[i] / 2; x += ws[i] - flankUm }
            return c
        }
        /** How far [n] beads across [w] are from the set widths (the largest ratio, as a logarithm); infinite where one is out of bounds. */
        fun cost(n: Int, w: Double): Double {
            val ws = widths(n, w)
            var c = 0.0
            for ((i, x) in ws.withIndex()) {
                if ((n > 1 && x < minUm - 1e-6) || x > maxUm + 1e-6) return Double.POSITIVE_INFINITY
                val set = if (n <= 2 || i == 0 || i == n - 1) outerUm else innerUm
                c = maxOf(c, Math.abs(Math.log(x / set)))
            }
            return c
        }
        /** The count for a place [w] wide: the feasible one nearest the set widths (one where none is: a place narrower than a line). */
        fun count(w: Double): Int {
            var best = 1; var bc = Double.POSITIVE_INFINITY
            for (n in 1..maxOf(1, 2 * loops)) { val c = cost(n, w); if (c < bc - 1e-12) { bc = c; best = n } }
            return best
        }
    }

    /**
     * [rule]; [minFeatureUm]: the thinnest place a line is laid in (narrower: nothing); [transitionUm]: the shortest stretch
     * with a count of its own, and how far from a rise in count the beads that start there are new (others give way to them); [toleranceUm]: the slicing tolerance (the
     * count's dead band); [sampleUm]: the outline's sampling.
     */
    class Params(val rule: Rule, val minFeatureUm: Long, val transitionUm: Long, val toleranceUm: Long, val sampleUm: Long,
                 /** How far a center line's end reaches into the loop it meets, past bead contact, as a share of its width (the infill's wall overlap). */
                 val endOverlap: Double = 0.0,
                 /** A center line shorter than this times its width is a dot (plan.layer.walls.length_factor.min). */
                 val minLengthFactor: Double = 0.0,
                 /** False: a neck narrower than the narrowest line takes its neighbors' count of loops as any short stretch does (smoothCounts; a test's control). */
                 val necksNarrow: Boolean = true,
                 /** False: a loop runs out and back into a tip's end however little that reaches past its bead (Lay.shortRunIns; a test's control). */
                 val shortRunInsGo: Boolean = true,
                 /** False: a stub at a junction of open pieces is joined as any end is (Lay.joins; a test's control). */
                 val stubsGiveWay: Boolean = true)

    /**
     * A loop turning more than this (degrees) over a line width either side of a tip it runs into turns a corner there:
     * its run into the corner fills the outside of its turn and stays (Lay.shortRunIns). A geometric bound, Claude's choice
     * (about a right angle's turn, not going on straight: the catalog's square-end bound, decision 60).
     */
    private const val CORNER_TURN_DEG = 60.0
    /** A bead within this of a node's middle (µm) is the middle bead. A geometric threshold, plymeld's choice. */
    private const val MIDDLE_EPS_UM = 5.0

    /** A polyline being built (µm) with its widths; [wall]: its bead's place from the outside. */
    private class Piece(val xs: ArrayList<Double> = ArrayList(), val ys: ArrayList<Double> = ArrayList(), val ws: ArrayList<Double> = ArrayList(),
                        var wall: Int = 0, var closed: Boolean = false) {
        /** A bead coming in at a change of count lies along part of it: other beads give way to it. */
        var newBead = false
        /** Its first / last point is a middle bead's (it joins only another middle bead's end; it is shortened where it meets a loop). */
        var midStart = false; var midEnd = false
        /** Its first / last point is a new bead's start (the beads beside it give way to its round end). */
        var newStart = false; var newEnd = false
        /** Its first / last point is where it gave way entirely to a new bead, or where it meets the outer loop: never joined. */
        var cutStart = false; var cutEnd = false
        /** It is (part of) the outer loop: it gives way to a new bead no narrower than the narrowest line, and is never cut. */
        var loop = false
        /** Each point's node and outline sample (a middle bead's point: -1; a point of no rib: node -1, a corner's end the sample of the rib beside it). */
        val node = ArrayList<Int>(); val sample = ArrayList<Int>()
        /** Each point's bead is new (comes in at a change of count). */
        val newPt = ArrayList<Boolean>()
        fun add(x: Double, y: Double, w: Double, nd: Int = -1, s: Int = -1, nw: Boolean = false) {
            if (xs.isNotEmpty() && Math.abs(xs.last() - x) < 0.5 && Math.abs(ys.last() - y) < 0.5) { ws[ws.size - 1] = maxOf(ws.last(), w); return }
            xs.add(x); ys.add(y); ws.add(w); node.add(nd); sample.add(s); newPt.add(nw); if (nw) newBead = true
        }
        val size get() = xs.size
        /** Points added along every step longer than [step], the width blended between its ends' (each the outline sample of the point before it, no node). */
        fun resample(step: Double) {
            val m = xs.size
            if (m < 2) return
            val nx = ArrayList<Double>(); val ny = ArrayList<Double>(); val nw = ArrayList<Double>(); val nn = ArrayList<Int>(); val ns = ArrayList<Int>(); val nb = ArrayList<Boolean>()
            for (i in 0 until m) {
                nx.add(xs[i]); ny.add(ys[i]); nw.add(ws[i]); nn.add(node[i]); ns.add(sample[i]); nb.add(newPt[i])
                if (i == m - 1 && !closed) continue
                val j = (i + 1) % m
                val k = Math.ceil(Math.hypot(xs[j] - xs[i], ys[j] - ys[i]) / step).toInt()
                for (q in 1 until k) { val f = q.toDouble() / k
                    nx.add(xs[i] + (xs[j] - xs[i]) * f); ny.add(ys[i] + (ys[j] - ys[i]) * f); nw.add(ws[i] + (ws[j] - ws[i]) * f); nn.add(-1); ns.add(sample[i]); nb.add(newPt[i] && newPt[j]) }
            }
            xs.clear(); xs.addAll(nx); ys.clear(); ys.addAll(ny); ws.clear(); ws.addAll(nw); node.clear(); node.addAll(nn); sample.clear(); sample.addAll(ns); newPt.clear(); newPt.addAll(nb)
        }
        /** Only the points where [keep] is true. */
        fun keepOnly(keep: BooleanArray) {
            fun <T> ArrayList<T>.only() { val k = filterIndexed { i, _ -> keep[i] }; clear(); addAll(k) }
            xs.only(); ys.only(); ws.only(); node.only(); sample.only(); newPt.only()
        }
        fun length(): Double { var l = 0.0; for (i in 1 until xs.size) l += Math.hypot(xs[i] - xs[i - 1], ys[i] - ys[i - 1]); return l }
        fun reversed(): Piece = Piece(ArrayList(xs.reversed()), ArrayList(ys.reversed()), ArrayList(ws.reversed()), wall, closed).also {
            it.newBead = newBead; it.midStart = midEnd; it.midEnd = midStart; it.newStart = newEnd; it.newEnd = newStart; it.cutStart = cutEnd; it.cutEnd = cutStart
            it.node.addAll(node.reversed()); it.sample.addAll(sample.reversed()); it.newPt.addAll(newPt.reversed()) }
    }

    /**
     * Items (numbers) by grid cell, a cell named by its two indices: each cell's items in a list of its own, the newest
     * first. Primitive arrays (no boxed keys or lists); one per use, never shared.
     */
    private class CellLists {
        private var keys = LongArray(64); private var heads = IntArray(64) { -1 }; private var size = 0
        private var item = IntArray(64); private var next = IntArray(64); private var count = 0
        private fun slot(key: Long): Int {
            var h = (java.lang.Long.hashCode(key * -0x61c8864680b583ebL) and 0x7fffffff) and (keys.size - 1)
            while (heads[h] >= 0 && keys[h] != key) h = (h + 1) and (keys.size - 1)
            return h
        }
        fun add(i: Long, j: Long, it: Int) {
            if (2 * (size + 1) > keys.size) grow()
            val key = (i shl 32) xor (j and 0xffffffffL)
            val s = slot(key)
            if (heads[s] < 0) { keys[s] = key; size++ }
            if (count == item.size) { item = item.copyOf(2 * count); next = next.copyOf(2 * count) }
            item[count] = it; next[count] = heads[s]; heads[s] = count++
        }
        private fun grow() {
            val ok = keys; val oh = heads
            keys = LongArray(2 * ok.size); heads = IntArray(2 * ok.size) { -1 }
            for (s in ok.indices) if (oh[s] >= 0) { val t = slot(ok[s]); keys[t] = ok[s]; heads[t] = oh[s] }
        }
        /** The first entry of cell (i, j)'s list (-1: none); [itemOf] and [nextOf] walk it. */
        fun first(i: Long, j: Long): Int = heads[slot((i shl 32) xor (j and 0xffffffffL))]
        fun has(i: Long, j: Long): Boolean = first(i, j) >= 0
        fun itemOf(e: Int) = item[e]
        fun nextOf(e: Int) = next[e]
    }

    /** A step of the walk along a rib in giving way (µm): a geometric resolution, plymeld's choice. */
    private const val GIVE_WAY_STEP_UM = 5.0

    /**
     * Where the ray from (px, py) along the unit (dx, dy) first lies within [r] of segment a-b (0 where it starts within;
     * infinite where it never does): the earliest entry into the segment's two end discs and the band along it.
     */
    internal fun rayCapsule(px: Double, py: Double, dx: Double, dy: Double, ax: Double, ay: Double, bx: Double, by: Double, r: Double): Double {
        if (r <= 0) return Double.POSITIVE_INFINITY
        var best = Double.POSITIVE_INFINITY
        fun disc(cx: Double, cy: Double) {
            val qx = px - cx; val qy = py - cy
            val c = qx * qx + qy * qy - r * r
            if (c <= 0) { best = 0.0; return }
            val b = dx * qx + dy * qy
            val disc = b * b - c
            if (disc < 0) return
            val t = -b - Math.sqrt(disc)
            if (t >= 0 && t < best) best = t
        }
        disc(ax, ay)
        if (best == 0.0) return 0.0
        val sx = bx - ax; val sy = by - ay; val len = Math.sqrt(sx * sx + sy * sy)
        if (len > 0) {
            disc(bx, by)
            if (best == 0.0) return 0.0
            val ux = sx / len; val uy = sy / len
            // across the band: |(P + tD - A) . n| <= r; along it: 0 <= (P + tD - A) . u <= len
            val s0 = (px - ax) * -uy + (py - ay) * ux; val ds = dx * -uy + dy * ux
            val a0 = (px - ax) * ux + (py - ay) * uy; val da = dx * ux + dy * uy
            var lo = Double.NEGATIVE_INFINITY; var hi = Double.POSITIVE_INFINITY
            fun within(v0: Double, dv: Double, min: Double, max: Double) {
                if (Math.abs(dv) < 1e-12) { if (v0 < min || v0 > max) { lo = 1.0; hi = 0.0 }; return }
                val t1 = (min - v0) / dv; val t2 = (max - v0) / dv
                lo = maxOf(lo, minOf(t1, t2)); hi = minOf(hi, maxOf(t1, t2))
            }
            within(s0, ds, -r, r); within(a0, da, 0.0, len)
            if (lo <= hi && hi >= 0) best = minOf(best, maxOf(lo, 0.0))
        }
        return best
    }
    /**
     * A dead-end branch of the axis is a corner when its width falls at least this fast along it (the half-width lost per
     * length: the sine of half the outline's angle there; 0.6 is about 74 deg, a square's corner 0.71, a lens's tip about
     * 0.45). A geometric threshold, plymeld's choice.
     */
    private const val CORNER_SLOPE = 0.6
    /**
     * A stretch with a count of its own is at least this many transition lengths long (shorter, it takes its neighbors'
     * count where their lines fit it). plymeld's choice; twice as long left a test lens's single line in a stretch as wide
     * as two lines need, bare by 0.14 mm.
     */
    private const val MIN_STRETCH_TRANSITIONS = 1.0
    /** The beads of one narrow island ([island]: its outer polygon and its holes, µm), as center lines (a closed one ends where it starts). */
    fun lay(island: List<Polygon>, p: Params): List<CenterLine> = Lay(island, p).lay()

    /** A node's beads across it (corners: their junction's), the same from both sides: centers and widths from one side. */
    private class NodeBeads(val c: DoubleArray, val w: DoubleArray)
    /** A run of a loop's ribs along a ring: its piece, the ribs it starts and ends on, the nodes before and after it. */
    private class Run(val pc: Piece, val startNode: Int, val startSample: Int, val prevNode: Int, val endNode: Int, val endSample: Int, val nextNode: Int)
    /** A join two open ends may make: ends [a] and [b], [d] apart. */
    private class Cand(val a: Int, val b: Int, val d: Double)

    /**
     * One island's construction, step by step ([lay] runs them in order). Each step is a method of its own, so each is
     * compiled to machine code (the virtual machine leaves a method larger than 8000 bytes of bytecode interpreted).
     */
    private class Lay(val island: List<Polygon>, val p: Params) {
        val rule = p.rule
        // the outline's samples (µm), each ring's range of them
        var sx = LongArray(0); var sy = LongArray(0)
        val rings = ArrayList<IntRange>()
        lateinit var dt: Delaunay
        lateinit var triOf: ArrayList<Int>
        // the nodes: count, center, radius, neighbors (3 a node, -1: none)
        var nn = 0
        var cx = DoubleArray(0); var cy = DoubleArray(0); var r = DoubleArray(0); var adj = IntArray(0)
        var corner = BooleanArray(0); var source = IntArray(0)
        var n = IntArray(0)
        lateinit var beads: Array<NodeBeads>
        var levels = 0
        var vNode = BooleanArray(0)
        var zoneDist = DoubleArray(0)
        val pieces = ArrayList<Piece>()
        // the loops' turns (x0 y0 x1 y1 width), where a middle line meeting a loop's turn is shortened before anything gives way
        val turns = ArrayList<DoubleArray>()
        fun dist(u: Int, v: Int) = Math.hypot(cx[u] - cx[v], cy[u] - cy[v])

        fun lay(): List<CenterLine> {
            if (!samples() || !nodes()) return emptyList()
            corners(); counts(); smoothCounts(); shortInnermost()
            for (u in 0 until nn) if (corner[u]) n[u] = n[source[u]]
            beads = Array(nn) { u ->
                val wd = 2 * r[if (corner[u]) source[u] else u]
                val bw = rule.widths(n[u], wd)
                NodeBeads(rule.centers(bw, wd), bw)
            }
            // the levels: loop k (from the outside, 0 the outer loop) runs wherever a place holds 2k + 2 lines or more; where it
            // holds 2k + 1, its middle line lies there (a center line along the axis); where fewer, neither. So every pair of
            // lines across a place belongs to a loop, closed wherever the place is that wide, turning back where it narrows
            levels = (n.maxOrNull() ?: 1).let { (it + 1) / 2 }
            tips(); zones(); loops(); middles()
            // the middle lines meeting a loop's turn are shortened first, as the turns lie before anything gives way: a middle
            // line between two turns close together (an area narrowing fast, three lines across for a short stretch between
            // four and one) is gone before the loops give way to it
            run { val out = trimEnds(pieces, turns); pieces.clear(); pieces.addAll(out) }
            giveWay()
            val kept = cut()
            // the middle lines' ends meeting the loops, again, as the loops lie after giving way
            run {
                val segs = ArrayList<DoubleArray>()
                for (pc in kept) if (pc.loop) { val m = pc.size
                    for (i in 0 until (if (pc.closed) m else m - 1)) { val j = (i + 1) % m
                        segs.add(doubleArrayOf(pc.xs[i], pc.ys[i], pc.xs[j], pc.ys[j], (pc.ws[i] + pc.ws[j]) / 2)) } }
                val out = trimEnds(kept, segs); kept.clear(); kept.addAll(out)
            }
            return joins(kept)
        }

        /** 1. The outline's samples, ring by ring, each ring with the island on its left; false: none. */
        fun samples(): Boolean {
        val sx = ArrayList<Long>(); val sy = ArrayList<Long>()
        val ringOf = ArrayList<Int>()
        for (poly in island) {
            if (poly.size < 3) continue
            val start = sx.size
            for (i in 0 until poly.size) {
                val j = (i + 1) % poly.size
                val ax = poly.x(i); val ay = poly.y(i); val bx = poly.x(j); val by = poly.y(j)
                val len = Math.hypot((bx - ax).toDouble(), (by - ay).toDouble())
                val k = maxOf(1, Math.ceil(len / p.sampleUm).toInt())
                for (s in 0 until k) {
                    val x = ax + Math.round((bx - ax) * s.toDouble() / k); val y = ay + Math.round((by - ay) * s.toDouble() / k)
                    if (sx.size > start && sx.last() == x && sy.last() == y) continue
                    sx.add(x); sy.add(y); ringOf.add(rings.size)
                }
            }
            while (sx.size - start > 1 && sx.last() == sx[start] && sy.last() == sy[start]) { sx.removeAt(sx.size - 1); sy.removeAt(sy.size - 1); ringOf.removeAt(ringOf.size - 1) }
            if (sx.size - start >= 3) rings.add(start until sx.size)
            else while (sx.size > start) { sx.removeAt(sx.size - 1); sy.removeAt(sy.size - 1); ringOf.removeAt(ringOf.size - 1) }
        }
        if (rings.isEmpty()) return false
        this.sx = sx.toLongArray(); this.sy = sy.toLongArray()
        return true
        }

        /** The nodes of the outline's medial axis: triangles of its samples whose circumcenter lies inside; false: none. */
        fun nodes(): Boolean {
        dt = Delaunay(sx, sy)
        val inside = Skeleton.Inside(island)
        // the nodes: triangles whose circumcenter lies inside
        val nodeOf = IntArray(dt.triangleCount) { -1 }
        val cxL = ArrayList<Double>(); val cyL = ArrayList<Double>(); val rL = ArrayList<Double>(); val triOf = ArrayList<Int>()
        this.triOf = triOf
        for (t in 0 until dt.triangleCount) {
            if (!dt.isAlive(t) || dt.isSuper(t)) continue
            val a = dt.v(t, 0); val b = dt.v(t, 1); val c = dt.v(t, 2)
            val ax = dt.px[a].toDouble(); val ay = dt.py[a].toDouble()
            val bx = dt.px[b] - ax; val by = dt.py[b] - ay; val qx = dt.px[c] - ax; val qy = dt.py[c] - ay
            val d = 2 * (bx * qy - by * qx)
            if (d == 0.0) continue
            val b2 = bx * bx + by * by; val c2 = qx * qx + qy * qy
            val ux = (qy * b2 - by * c2) / d; val uy = (bx * c2 - qx * b2) / d
            if (!inside.contains(ax + ux, ay + uy)) continue
            nodeOf[t] = cxL.size; cxL.add(ax + ux); cyL.add(ay + uy); rL.add(Math.hypot(ux, uy)); triOf.add(t)
        }
        nn = cxL.size
        if (nn == 0) return false
        cx = cxL.toDoubleArray(); cy = cyL.toDoubleArray(); r = rL.toDoubleArray()
        adj = IntArray(3 * nn) { k -> dt.nb(triOf[k / 3], k % 3).let { o -> if (o < 0) -1 else nodeOf[o] } }
        return true
        }

        /** 3. Corners: dead-end branches shorter than the width where they join, peeled round by round. */
        fun corners() {
        corner = BooleanArray(nn); source = IntArray(nn) { it }
        fun degree(u: Int) = (0 until 3).count { val v = adj[3 * u + it]; v >= 0 && !corner[v] }
        var changed = true; var rounds = 0
        while (changed && rounds++ < 16) {
            changed = false
            val deg = IntArray(nn) { if (corner[it]) 0 else degree(it) }
            val marks = ArrayList<Pair<List<Int>, Int>>()
            for (leaf in 0 until nn) {
                if (corner[leaf] || deg[leaf] != 1) continue
                val path = arrayListOf(leaf); var prev = -1; var cur = leaf; var len = 0.0; var junction = -1
                while (true) {
                    var next = -1
                    for (i in 0 until 3) { val v = adj[3 * cur + i]; if (v >= 0 && !corner[v] && v != prev) { next = v; break } }
                    if (next < 0) break
                    len += dist(cur, next); prev = cur; cur = next
                    if (deg[cur] >= 3) { junction = cur; break }
                    if (deg[cur] != 2) break
                    path.add(cur)
                }
                // a corner narrows fast toward its leaf (the outline turning through less than about 74 deg: a square's
                // corner); a tip narrowing more slowly (a lens's, a wedge's) is no corner: its count follows its width
                if (junction >= 0 && len < 2 * r[junction] && len > 0 && (r[junction] - r[leaf]) / len >= CORNER_SLOPE) marks.add(path to junction)
            }
            // never every branch of a junction in one round (one stays its trunk)
            for ((j, ms) in marks.groupBy { it.second }) {
                val keep = if (ms.size >= deg[j]) ms.sortedBy { it.first.size }.dropLast(1) else ms
                for ((path, _) in keep) for (u in path) { if (!corner[u]) { corner[u] = true; source[u] = j; changed = true } }
            }
        }
        for (u in 0 until nn) { var s = source[u]; var g = 0; while (corner[s] && s != source[s] && g++ < nn) s = source[s]; source[u] = s }
        }

        /** The count at each central node: dead band of the tolerance, filled from the decided neighbors. */
        fun counts() {
        n = IntArray(nn)
        val decided = BooleanArray(nn)
        for (u in 0 until nn) if (!corner[u]) {
            val w = 2 * r[u]
            val lo = rule.count(w - p.toleranceUm); val hi = rule.count(w + p.toleranceUm)
            n[u] = rule.count(w); decided[u] = lo == hi
        }
        run {
            val queue = ArrayDeque<Int>()
            for (u in 0 until nn) if (!corner[u] && decided[u]) queue.add(u)
            if (queue.isEmpty()) for (u in 0 until nn) if (!corner[u]) { decided[u] = true; queue.add(u) }
            while (queue.isNotEmpty()) {
                val u = queue.removeFirst()
                for (i in 0 until 3) { val v = adj[3 * u + i]; if (v < 0 || corner[v] || decided[v]) continue
                    val w = 2 * r[v]
                    n[v] = n[u].coerceIn(rule.count(w - p.toleranceUm), rule.count(w + p.toleranceUm)); decided[v] = true; queue.add(v) }
            }
        }
        }

        /** The stretches of one count (corners left out): each node's stretch, and each stretch's nodes. */
        fun components(): Pair<IntArray, List<List<Int>>> {
            val comp = IntArray(nn) { -1 }; val list = ArrayList<List<Int>>()
            for (s in 0 until nn) if (!corner[s] && comp[s] < 0) {
                val members = ArrayList<Int>(); val st = ArrayDeque<Int>(); st.add(s); comp[s] = list.size
                while (st.isNotEmpty()) { val u = st.removeLast(); members.add(u)
                    for (i in 0 until 3) { val v = adj[3 * u + i]; if (v >= 0 && !corner[v] && comp[v] < 0 && n[v] == n[u]) { comp[v] = list.size; st.add(v) } } }
                list.add(members)
            }
            return comp to list
        }
        fun compLength(members: List<Int>, comp: IntArray, id: Int): Double {
            var l = 0.0
            for (u in members) for (i in 0 until 3) { val v = adj[3 * u + i]; if (v > u && comp[v] == id) l += dist(u, v) }
            return l
        }
        /**
         * Stretches with a count of their own shorter than the transition length take the count of their longest neighbor:
         * in rounds, shortest first; a stretch beside one changed in this round waits for the next (its neighbors' lengths
         * changed). Not a count whose lines would be wider than the widest, nor a count of loops in a neck narrower somewhere
         * than the narrowest line.
         */
        fun smoothCounts() {
        run {
            var rounds = 0
            while (rounds++ < 4 * nn) {
                val (comp, list) = components()
                val lens = list.indices.map { compLength(list[it], comp, it) }
                val touched = BooleanArray(list.size)
                var any = false
                for (id in list.indices.sortedBy { lens[it] }) {
                    if (lens[id] >= MIN_STRETCH_TRANSITIONS * p.transitionUm) break
                    if (touched[id]) continue
                    var bestLen = -1.0; var bestN = -1; var bestComp = -1
                    for (u in list[id]) for (i in 0 until 3) { val v = adj[3 * u + i]; if (v >= 0 && !corner[v] && comp[v] != id && lens[comp[v]] > bestLen) { bestLen = lens[comp[v]]; bestN = n[v]; bestComp = comp[v] } }
                    if (bestN < 0 || touched[bestComp]) continue
                    // only a count whose lines are no wider than the widest there: a blob's wide middle, short, does not take
                    // the one line of the neck beside it (it was laid as one disc and nothing round it); a short narrow end
                    // takes the two lines beside it, a little narrower than the narrowest, so they turn round it
                    if (list[id].any { u -> rule.widths(bestN, 2 * r[u]).any { it > rule.maxUm + 1e-6 } }) continue
                    // nor, in a neck (a stretch the axis leaves at two places or more, not an end), a count of loops where the
                    // axis reads it narrower somewhere than the narrowest line: a loop's two sides cannot both lie there, each at
                    // its place from its own side, so its points on the neck's ribs were drawn onto the axis, a spike toward the
                    // outline (round the inner corner of a slit 40 to 60 microns wide cut into an island between two strokes, the
                    // outline sampled coarser than the slit, the axis passed 12 and 43 microns from the outline: its count of
                    // two raised from one, the outer loop dipped 0.1 mm onto it); the loop turns at the neck's mouth, as at any
                    // place too narrow for it
                    if (p.necksNarrow && bestN >= 2 && list[id].any { u -> 2 * r[u] < rule.minUm } &&
                        list[id].sumOf { u -> (0 until 3).count { val v = adj[3 * u + it]; v >= 0 && !corner[v] && comp[v] != id } } >= 2) continue
                    for (u in list[id]) n[u] = bestN
                    touched[id] = true; touched[bestComp] = true; any = true
                    break   // one a round: each stretch decided on the counts as they now stand (the cost: a round per stretch)
                }
                if (!any) break
            }
        }
        }

        fun shortInnermost() {
        // an innermost line too short to be a line (a stretch of three lines or more with fewer lines all round it, shorter
        // than [Params.minLengthFactor] of its innermost line's width: a middle line that short is a dot, an inner loop that
        // short a ring round nothing) takes the count below where those lines fit: the lines round it widen to fill it,
        // instead of a dot with the loop giving way round both its round ends (a lens just deep enough for three lines in
        // its middle), or a ring at each corner of an L-shaped post where its width reaches four lines (a test boat's cabin
        // posts). Highest counts first, so a short four within a short three goes down to two
        run {
            for (round in 0 until 2) {
                val (comp, list) = components()
                for ((id, members) in list.withIndex().sortedByDescending { n[it.value[0]] }) {
                    val c = n[members[0]]
                    if (c < 3 || members.any { n[it] != c }) continue
                    if (members.any { u -> (0 until 3).any { val v = adj[3 * u + it]; v >= 0 && !corner[v] && comp[v] != id && n[v] > c } }) continue
                    val w = members.maxOf { rule.widths(c, 2 * r[it])[c / 2] }
                    if (compLength(members, comp, id) >= p.minLengthFactor * w) continue
                    if (members.any { u -> rule.widths(c - 1, 2 * r[u]).any { it > rule.maxUm + 1e-6 } }) continue
                    for (u in members) n[u] = c - 1
                }
            }
        }
        }

        /** The width level [k]'s middle line takes at a node holding 2k + 1 lines (the place's width for the outer level). */
        fun middleWidth(u: Int, k: Int) = if (k == 0) 2 * r[u] else beads[u].w[k]
        fun tips() {
        // a tip a loop runs into: a part where level k has its middle line (2k + 1 lines across) hanging off the loop at one
        // mouth, no longer than twice its middle's width at the mouth, where two lines of level k at the narrowest width
        // overlap, and where narrower than the narrowest line the line laid out and back is laid twice, by no more in all than
        // half the disc of the mouth's width (a separate line's round start over the loop's turn): the loop runs on into it,
        // its two sides at the narrowest width (no narrower), until the place left is one narrowest line wide, then out and
        // back along the axis to where it is the thinnest feature wide (a lens's tip: a separate line there was shorter than
        // it is wide, dropped beside another part, and its start laid as much twice over the loop's turn)
        vNode = BooleanArray(nn)
        run {
            val seen = BooleanArray(nn); val inComp = BooleanArray(nn); val d = DoubleArray(nn)
            for (s0 in 0 until nn) {
                if (n[s0] % 2 == 0 || seen[s0]) continue
                val k = n[s0] / 2
                val comp = ArrayList<Int>(); val st = ArrayDeque<Int>(); st.add(s0); seen[s0] = true
                var mouths = 0; var mouth = -1
                while (st.isNotEmpty()) { val u = st.removeLast(); comp.add(u); inComp[u] = true
                    for (i in 0 until 3) { val v = adj[3 * u + i]; if (v < 0) continue
                        if (n[v] > n[s0]) { mouths++; mouth = u } else if (n[v] == n[s0] && !seen[v]) { seen[v] = true; st.add(v) } } }
                if (mouths == 1) {
                    for (u in comp) d[u] = Double.POSITIVE_INFINITY
                    d[mouth] = 0.0
                    val pq = java.util.PriorityQueue<Pair<Double, Int>>(compareBy { it.first }); pq.add(0.0 to mouth)
                    while (pq.isNotEmpty()) { val (du, u) = pq.poll(); if (du > d[u]) continue
                        for (i in 0 until 3) { val v = adj[3 * u + i]; if (v < 0 || !inComp[v]) continue
                            val nd = du + dist(u, v); if (nd < d[v]) { d[v] = nd; pq.add(nd to v) } } }
                    var far = 0.0; var twice = 0.0
                    for (u in comp) { val w = middleWidth(u, k); if (w < p.minFeatureUm) continue
                        far = maxOf(far, d[u])
                        var len = 0.0
                        for (i in 0 until 3) { val v = adj[3 * u + i]; if (v >= 0 && (inComp[v] || n[v] > n[s0])) len += dist(u, v) / 2 }
                        twice += len * (if (w >= rule.minUm) maxOf(0.0, 2 * rule.minUm - rule.flankUm - w) else w)
                    }
                    val wm = middleWidth(mouth, k)
                    if (far <= 2 * wm && twice <= Math.PI * wm * wm / 8)
                        for (u in comp) if (middleWidth(u, k) >= p.minFeatureUm) vNode[u] = true
                }
                for (u in comp) inComp[u] = false
            }
        }
        }
        /** Whether loop [k] runs over node [u]. */
        fun loopAt(u: Int, k: Int) = n[u] >= 2 * k + 2 || (vNode[u] && n[u] / 2 == k)

        fun zones() {
        // 4. changes of count: each central node's distance along the axis from where its count rose (within its stretch), and
        // the count below. A middle line coming in where the count rises from even to odd (two lines to three: the outer
        // loop's two sides part and the middle starts between them) is new there, and the beads beside it give way to it. A
        // rise from odd to even is no change for any line: the loop of that level turns there, the middle line ends at it
        // a sliver: a place narrower than the narrowest line holding one line at most (a hair-thin strip off the island's
        // side, a tip): no count rises from it
        val sliver = BooleanArray(nn) { !corner[it] && 2 * r[it] < rule.minUm && n[it] <= 1 }
        zoneDist = DoubleArray(nn) { Double.POSITIVE_INFINITY }
        run {
            val pq = java.util.PriorityQueue<Pair<Double, Int>>(compareBy { it.first })
            for (u in 0 until nn) if (!corner[u] && n[u] % 2 == 1 && n[u] >= 3) for (i in 0 until 3) { val v = adj[3 * u + i]
                if (v >= 0 && !corner[v] && !sliver[v] && n[v] < n[u] && n[v] % 2 == 0) zoneDist[u] = minOf(zoneDist[u], dist(u, v) / 2) }
            for (u in 0 until nn) if (zoneDist[u].isFinite()) pq.add(zoneDist[u] to u)
            while (pq.isNotEmpty()) {
                val (d, u) = pq.poll()
                if (d > zoneDist[u]) continue
                for (i in 0 until 3) { val v = adj[3 * u + i]
                    if (v < 0 || corner[v] || n[v] != n[u]) continue
                    val nd = d + dist(u, v)
                    if (nd < zoneDist[v]) { zoneDist[v] = nd; pq.add(nd to v) } }
            }
        }
        }
        // the middle line on a node: its width (NaN: none), 1 if new
        fun middle(u: Int): DoubleArray {
            val none = doubleArrayOf(Double.NaN, 0.0)
            if (corner[u] || vNode[u] || 2 * r[u] < p.minFeatureUm || n[u] % 2 == 0) return none
            return doubleArrayOf(beads[u].w[n[u] / 2], if (zoneDist[u] <= p.transitionUm) 1.0 else 0.0)
        }
        /** Where a bead [pos] from the outline at node [u] meets the axis toward node [v] at a corner (null: not a corner's end). */
        fun cornerEnd(u: Int, v: Int, pos: Double): DoubleArray? {
            if (u == v || !(corner[u] || corner[v]) || r[v] >= r[u]) return null
            val f = ((r[u] - pos) / (r[u] - r[v])).coerceIn(0.0, 1.0)
            return doubleArrayOf(cx[u] + (cx[v] - cx[u]) * f, cy[u] + (cy[v] - cy[u]) * f)
        }
        /**
         * Loop [k]'s bead on node [u]'s ribs: its distance from the outline and its width (null: none, a corner's end narrower
         * than the bead). Where its pair is the innermost (2k + 2 lines): each its share, no narrower than the narrowest line
         * (its outer edge kept); a tip it runs into: the middle's place split in two at the narrowest width at least, a line out
         * and back along the axis where that place is narrower; elsewhere its set width.
         */
        fun loopBead(u: Int, k: Int): DoubleArray? {
            val cornerOf = corner[u] && !vNode[u]
            val b = beads[u]
            val x: Double; val w: Double
            if (vNode[u]) {
                val pw = middleWidth(u, k)
                val lo = if (k == 0) 0.0 else b.c[k] - b.w[k] / 2
                w = if (pw >= rule.minUm) maxOf(rule.minUm, (pw + rule.flankUm) / 2) else pw
                x = lo + w / 2
            } else if (n[u] == 2 * k + 2) {
                w = maxOf(rule.minUm, b.w[k]); x = b.c[k] - b.w[k] / 2 + w / 2
            } else { w = b.w[k]; x = b.c[k] }
            if (cornerOf) return if (x < minOf(r[source[u]] - MIDDLE_EPS_UM, r[u])) doubleArrayOf(x, w) else null
            val xx = minOf(x, r[u] - MIDDLE_EPS_UM)
            return if (xx > 0) doubleArrayOf(xx, w) else null
        }
        /**
         * A loop's points [pc] with each short run into a tip's end left out: a run of its points on the axis of a tip it runs
         * into (a tip's node, its place narrower than the narrowest line: the line out and back along the axis), between two
         * points of the loop, every point of it within the bead of the nearer of the two but for [Params.endOverlap] of its own
         * width (no further past it than an interior line's end reaches into the walls), where the loop goes on past the tip
         * (turning no more than [CORNER_TURN_DEG] from the way it runs in to the way it runs out, over a line width each).
         * Such a run laid the line 35 to 90 microns out and back where a short tip ends within the loop's bead at its mouth
         * (tips of narrow islands laid by their beads): a zigzag in the outer line that covers nothing the loop's
         * bead does not. Where the loop turns a corner (a square end two lines wide: a right angle within a line width), its
         * run into the corner fills the outside of the turn (decision 28's corners) and stays.
         */
        fun shortRunIns(pc: Piece) {
            val m = pc.size
            if (m < 3) return
            fun runIn(k: Int) = pc.node[k] >= 0 && vNode[pc.node[k]] && pc.ws[k] < rule.minUm
            val keep = BooleanArray(m) { true }
            var i = 1
            while (i < m - 1) {
                if (!runIn(i)) { i++; continue }
                var j = i
                while (j + 1 < m - 1 && runIn(j + 1)) j++
                val a = i - 1; val b = j + 1
                // the way the loop runs into a and out of b, over a line width each: a corner it turns, not a tip it passes
                fun at(from: Int, step: Int, d: Double): DoubleArray {
                    var left = d; var k = from
                    while (k + step in 0 until m) { val l = Math.hypot(pc.xs[k + step] - pc.xs[k], pc.ys[k + step] - pc.ys[k])
                        if (l >= left) return doubleArrayOf(pc.xs[k] + (pc.xs[k + step] - pc.xs[k]) * left / l, pc.ys[k] + (pc.ys[k + step] - pc.ys[k]) * left / l)
                        left -= l; k += step }
                    return doubleArrayOf(pc.xs[k], pc.ys[k])
                }
                val pa = at(a, -1, pc.ws[a]); val pb = at(b, 1, pc.ws[b])
                val ux = pc.xs[a] - pa[0]; val uy = pc.ys[a] - pa[1]; val vx = pb[0] - pc.xs[b]; val vy = pb[1] - pc.ys[b]
                val lu = Math.hypot(ux, uy); val lv = Math.hypot(vx, vy)
                val passes = lu > 0 && lv > 0 && (ux * vx + uy * vy) / (lu * lv) > Math.cos(Math.toRadians(CORNER_TURN_DEG))
                if (passes && !runIn(a) && !runIn(b) && (i..j).all { q ->
                        fun past(s: Int) = Math.hypot(pc.xs[q] - pc.xs[s], pc.ys[q] - pc.ys[s]) + pc.ws[q] / 2 - pc.ws[s] / 2
                        minOf(past(a), past(b)) <= p.endOverlap * pc.ws[q] })
                    for (q in i..j) keep[q] = false
                i = j + 1
            }
            if (!keep.all { it }) pc.keepOnly(keep)
        }
        // 5. the loops, each traced along the rings: the runs of ribs of its nodes, its bead on each (a corner's end where it
        // stops short of a corner). A run ends where the ring passes to a part too narrow for the loop (a tip, a neck, a
        // sliver); the loop turns there, carrying on into the run that begins across the same mouth: the run starting on the
        // same node's rib to the other end of the triangle edge the narrow part hangs from. So each loop is one path built from
        // the outline, closed wherever the place is wide enough for it, whatever the count does inside it
        fun loops() {
        val nodesAt = HashMap<Int, ArrayList<Int>>()
        for (u in 0 until nn) { val t = triOf[u]; for (i in 0 until 3) nodesAt.getOrPut(dt.v(t, i)) { ArrayList() }.add(u) }
        val ringRibs = ArrayList<Pair<IntArray, IntArray>>()
        for (ring in rings) {
            val m = ring.last - ring.first + 1
            // the ribs in order: sample by sample, each sample's nodes from the side of the sample before to the side of the next
            val ribNode = ArrayList<Int>(); val ribSample = ArrayList<Int>()
            for (k in 0 until m) {
                val s = ring.first + k; val prev = ring.first + (k - 1 + m) % m
                val list = nodesAt[s] ?: continue
                val tp = Math.atan2((sy[prev] - sy[s]).toDouble(), (sx[prev] - sx[s]).toDouble())
                val sorted = list.sortedBy { u -> var a = tp - Math.atan2(cy[u] - sy[s], cx[u] - sx[s]); while (a < 0) a += 2 * Math.PI; while (a >= 2 * Math.PI) a -= 2 * Math.PI; a }
                for (u in sorted) { ribNode.add(u); ribSample.add(s) }
            }
            if (ribNode.isNotEmpty()) ringRibs.add(ribNode.toIntArray() to ribSample.toIntArray())
        }
        for (level in 0 until levels) loopsOf(level, ringRibs)
        }

        /** Loop [level], traced along each ring's ribs [ringRibs] (each rib's node and sample, in order round the ring). */
        fun loopsOf(level: Int, ringRibs: List<Pair<IntArray, IntArray>>) {
        run {
            val runs = ArrayList<Run>()
            for ((ribNode, ribSample) in ringRibs) {
                val q = ribNode.size
                val isLoop = BooleanArray(q) { loopAt(ribNode[it], level) }
                if (isLoop.none { it }) continue
                val ob = Array(q) { if (isLoop[it]) loopBead(ribNode[it], level) else null }
                fun point(i: Int): DoubleArray {
                    val u = ribNode[i]; val s = ribSample[i]; val b = ob[i]!!; val f = b[0] / r[u]
                    return doubleArrayOf(sx[s] + (cx[u] - sx[s]) * f, sy[s] + (cy[u] - sy[s]) * f, b[1])
                }
                /**
                 * The loop's points on [count] ribs from [from] (round the ring), a corner's end where the bead stops short of a
                 * corner. A corner's end lies on no rib (node -1) but takes the outline sample of the rib beside it: the loop
                 * gives way across the outline there as at its other points (giveWay).
                 */
                fun trace(from: Int, count: Int, cyclic: Boolean): Piece {
                    val pc = Piece(wall = level).also { it.loop = true }
                    for (step in 0 until count) {
                        val i = (from + step) % q; val before = (i - 1 + q) % q
                        val hasBefore = step > 0 || cyclic
                        val b = ob[i]; val bb = ob[before]
                        if (b != null) {
                            if (hasBefore && bb == null && isLoop[before]) cornerEnd(ribNode[i], ribNode[before], b[0])?.let { e -> pc.add(e[0], e[1], b[1], s = ribSample[i]) }
                            val pt = point(i); pc.add(pt[0], pt[1], pt[2], ribNode[i], ribSample[i], level > 0 && vNode[ribNode[i]] && zoneDist[ribNode[i]] <= p.transitionUm)
                        } else if (hasBefore && bb != null) cornerEnd(ribNode[before], ribNode[i], bb[0])?.let { e -> pc.add(e[0], e[1], bb[1], s = ribSample[before]) }
                    }
                    return pc
                }
                if (isLoop.all { it }) {
                    val pc = trace(0, q, true)
                    if (p.shortRunInsGo) shortRunIns(pc)
                    if (pc.size >= 3) { pc.closed = true; pieces.add(pc) }
                    continue
                }
                val start = (0 until q).first { !isLoop[it] }
                var step = 1
                while (step <= q) {
                    val i = (start + step) % q
                    if (!isLoop[i]) { step++; continue }
                    var len = 0
                    while (isLoop[(i + len) % q]) len++
                    val last = (i + len - 1) % q
                    runs.add(Run(trace(i, len, false), ribNode[i], ribSample[i], ribNode[(i - 1 + q) % q], ribNode[last], ribSample[last], ribNode[(last + 1) % q]))
                    step += len
                }
            }
            // the turns: each run's end carries on into the run beginning across the same mouth (the edge of the end's node
            // toward the narrow part the ring walks into next, the run beginning on that edge's other end, after the same narrow
            // part); failing that (a mouth not on one triangle edge: rare), any such edge of the node; failing that, the nearest
            // run beginning left over within the widest line and the narrowest
            fun ribKey(u: Int, s: Int) = (u.toLong() shl 32) or s.toLong()
            val startOf = HashMap<Long, Int>()
            for ((k, rn) in runs.withIndex()) startOf[ribKey(rn.startNode, rn.startSample)] = k
            val next = IntArray(runs.size) { -1 }; val taken = BooleanArray(runs.size)
            for (strict in listOf(true, false)) for ((k, rn) in runs.withIndex()) {
                if (next[k] >= 0) continue
                val u = rn.endNode; val t = triOf[u]; val a = rn.endSample
                val ia = (0 until 3).firstOrNull { dt.v(t, it) == a } ?: continue
                for (j in 0 until 3) {
                    if (j == ia) continue
                    val across = adj[3 * u + (3 - ia - j)]
                    if (across >= 0 && loopAt(across, level)) continue
                    val c = startOf[ribKey(u, dt.v(t, j))] ?: continue
                    if (taken[c] || (strict && (across != rn.nextNode || runs[c].prevNode != across))) continue
                    next[k] = c; taken[c] = true; break
                }
            }
            val reachUm = rule.maxUm + rule.minUm
            for ((k, rn) in runs.withIndex()) {
                if (next[k] >= 0 || rn.pc.size == 0) continue
                val ex = rn.pc.xs.last(); val ey = rn.pc.ys.last()
                var best = -1; var bd = reachUm
                for ((c, rc) in runs.withIndex()) if (!taken[c] && rc.pc.size > 0) {
                    val d = Math.hypot(rc.pc.xs[0] - ex, rc.pc.ys[0] - ey); if (d < bd) { bd = d; best = c } }
                if (best >= 0) { next[k] = best; taken[best] = true }
            }
            val inChain = BooleanArray(runs.size)
            fun chain(from: Int): Piece {
                val pc = Piece(wall = level).also { it.loop = true }
                var k = from
                while (k >= 0 && !inChain[k]) {
                    inChain[k] = true
                    val rp = runs[k].pc
                    if (pc.size > 0 && rp.size > 0) turns.add(doubleArrayOf(pc.xs.last(), pc.ys.last(), rp.xs[0], rp.ys[0], (pc.ws.last() + rp.ws[0]) / 2))
                    for (i in 0 until rp.size) pc.add(rp.xs[i], rp.ys[i], rp.ws[i], rp.node[i], rp.sample[i], rp.newPt[i])
                    k = next[k]
                }
                if (k == from && pc.size >= 3) { pc.closed = true; turns.add(doubleArrayOf(pc.xs.last(), pc.ys.last(), pc.xs[0], pc.ys[0], (pc.ws.last() + pc.ws[0]) / 2)) }
                // (across the runs' joins: a tip's end is where one run ends and the next begins)
                if (p.shortRunInsGo) shortRunIns(pc)
                return pc
            }
            // open chains first (from a run nothing carries on into), then the loops
            for (k in runs.indices) if (!taken[k] && !inChain[k]) chain(k).let { if (it.size > 0) pieces.add(it) }
            for (k in runs.indices) if (!inChain[k]) chain(k).let { if (it.size > 0) pieces.add(it) }
        }
        }

        /** The middle beads, along the axis. */
        fun middles() {
        run {
            val mids = Array(nn) { middle(it) }
            val mid = DoubleArray(nn) { mids[it][0] }
            val has = BooleanArray(nn) { !mid[it].isNaN() }
            // a middle line runs along nodes of its own count: never across a loop's turn (one line across to three)
            fun linked(u: Int, v: Int) = v >= 0 && has[v] && n[u] == n[v]
            fun mdeg(u: Int) = (0 until 3).count { linked(u, adj[3 * u + it]) }
            val used = HashSet<Long>()
            fun key(a: Int, b: Int) = minOf(a, b).toLong() shl 32 or maxOf(a, b).toLong()
            // a middle bead's place from the outside: the side beads beside it
            fun walk(start: Int, next: Int) {
                val pc = Piece(wall = n[start] / 2).also { it.midStart = true; it.midEnd = true }
                pc.add(cx[start], cy[start], mid[start], start, nw = mids[start][1] > 0)
                var prev = start; var cur = next; used.add(key(start, next))
                while (true) {
                    pc.add(cx[cur], cy[cur], mid[cur], cur, nw = mids[cur][1] > 0)
                    if (mdeg(cur) != 2) break
                    var nx = -1
                    for (i in 0 until 3) { val v = adj[3 * cur + i]; if (linked(cur, v) && v != prev) { nx = v; break } }
                    if (nx < 0 || !used.add(key(cur, nx))) break
                    prev = cur; cur = nx
                }
                pc.newStart = mids[start][1] > 0; pc.newEnd = mids[cur][1] > 0
                pieces.add(pc)
            }
            for (u in 0 until nn) if (has[u] && mdeg(u) != 2) {
                if (mdeg(u) == 0) { val pc = Piece(wall = n[u] / 2).also { it.midStart = true; it.midEnd = true }; pc.add(cx[u], cy[u], mid[u], u, nw = mids[u][1] > 0); pieces.add(pc); continue }
                for (i in 0 until 3) { val v = adj[3 * u + i]; if (linked(u, v) && key(u, v) !in used) walk(u, v) }
            }
            for (u in 0 until nn) if (has[u]) for (i in 0 until 3) { val v = adj[3 * u + i]; if (linked(u, v) && key(u, v) !in used) walk(u, v) }
        }
        }
        /**
         * [list] with each middle line's ends shortened where they meet the line segments [segs] (x0 y0 x1 y1 width): a center
         * line's end meeting a loop (a tip's, a neck's or a sliver's line where the loop turns, an inner middle line where a
         * loop turns beside it) starts where its round end reaches into the loop's line by the share of its width an interior
         * line's end reaches into the walls past bead contact ([Params.endOverlap]): not over the loop's turn, and laid twice no
         * more than that. Only its ends are shortened (contiguously), so a line in contact with a loop along its side keeps all
         * of it; one shortened to nothing is dropped.
         */
        fun trimEnds(list: List<Piece>, segs: List<DoubleArray>): List<Piece> {
            if (segs.isEmpty() || list.none { !it.loop && !it.closed && ((it.midStart && !it.cutStart) || (it.midEnd && !it.cutEnd)) }) return ArrayList(list)
            val f = rule.flankUm
            // the segments by cell, each in every cell its box widened by half its width touches; a point looks in the cells
            // within half its own width
            val cellUm = 200.0
            val grid = CellLists()
            for ((k, g) in segs.withIndex()) { val rr = g[4] / 2 + 1.0
                for (i in Math.floor((minOf(g[0], g[2]) - rr) / cellUm).toLong()..Math.floor((maxOf(g[0], g[2]) + rr) / cellUm).toLong())
                    for (j in Math.floor((minOf(g[1], g[3]) - rr) / cellUm).toLong()..Math.floor((maxOf(g[1], g[3]) + rr) / cellUm).toLong())
                        grid.add(i, j, k) }
            fun clear(pc: Piece, i: Int): Boolean {
                val x = pc.xs[i]; val y = pc.ys[i]; val w = pc.ws[i]
                val rq = w / 2 + 1.0
                for (ci in Math.floor((x - rq) / cellUm).toLong()..Math.floor((x + rq) / cellUm).toLong())
                    for (cj in Math.floor((y - rq) / cellUm).toLong()..Math.floor((y + rq) / cellUm).toLong()) {
                        var e = grid.first(ci, cj)
                        while (e >= 0) {
                            val g = segs[grid.itemOf(e)]; e = grid.nextOf(e)
                            val need = w / 2 + g[4] / 2 - f - p.endOverlap * w
                            if (need <= 0 || x < minOf(g[0], g[2]) - need || x > maxOf(g[0], g[2]) + need || y < minOf(g[1], g[3]) - need || y > maxOf(g[1], g[3]) + need) continue
                            val dx = g[2] - g[0]; val dy = g[3] - g[1]; val l2 = dx * dx + dy * dy
                            val t = if (l2 == 0.0) 0.0 else (((x - g[0]) * dx + (y - g[1]) * dy) / l2).coerceIn(0.0, 1.0)
                            val ex = x - g[0] - t * dx; val ey = y - g[1] - t * dy
                            if (ex * ex + ey * ey < need * need) return false
                        }
                    }
                return true
            }
            val out = ArrayList<Piece>()
            for (pc in list) {
                if (pc.loop || pc.closed) { out.add(pc); continue }
                var a = 0; var b = pc.size - 1
                if (pc.midStart && !pc.cutStart) while (a <= b && !clear(pc, a)) a++
                if (pc.midEnd && !pc.cutEnd) while (b >= a && !clear(pc, b)) b--
                if (a > b) continue
                if (a == 0 && b == pc.size - 1) { out.add(pc); continue }
                val t = Piece(wall = pc.wall)
                for (i in a..b) t.add(pc.xs[i], pc.ys[i], pc.ws[i], pc.node[i], pc.sample[i], pc.newPt[i])
                t.newBead = t.newPt.any { it }
                if (a > 0) t.cutStart = true else { t.midStart = pc.midStart; t.newStart = pc.newStart; t.cutStart = pc.cutStart }
                if (b < pc.size - 1) t.cutEnd = true else { t.midEnd = pc.midEnd; t.newEnd = pc.newEnd; t.cutEnd = pc.cutEnd }
                out.add(t)
            }
            return out
        }
        // every other bead gives way to the beads coming in at a change of count, along its ribs (a node's ribs to the two
        // sides of an area meet the outline at different places along it where the sides are not parallel, a lens: a new
        // bead on one side starts beside nodes that did not change; and its round start reaches back along it): where a new
        // bead's plastic (each bead narrowed by half the flank, the usual overlap of two beads in contact) lies within a
        // point's reach along a rib, that side of the bead stops at it, keeping the usual overlap, its other side where it
        // was. A middle bead (on a node) walks each of the node's ribs from the node outward, a side bead its own rib from
        // its middle toward the node.
        fun giveWay() {
        run {
            val f = rule.flankUm
            val ringOf = arrayOfNulls<IntRange>(sx.size).also { a -> for (ring in rings) for (s in ring) a[s] = ring }.requireNoNulls()
            // what is given way to: the new beads' plastic (each narrowed by half the flank, the usual overlap of two beads in
            // contact), and, for a loop inside another, the loops outside it (where an area narrows fast, a loop's turn lies
            // close behind the turn of the loop outside it: it gives way to it)
            val segs = ArrayList<DoubleArray>()   // x0 y0 x1 y1, the half-width (narrowed), the piece, the loop's level (-1: a new bead)
            val turnReach = 2 * rule.maxUm + rule.minUm
            // the turns' ends by cell of [turnReach], for "is a turn's end within reach"
            val turnCells = CellLists()
            fun cellOf(v: Double) = Math.floor(v / turnReach).toLong()
            for ((k, t) in turns.withIndex()) for (e in 0..1) turnCells.add(cellOf(t[2 * e]), cellOf(t[2 * e + 1]), 2 * k + e)
            fun nearTurn(x: Double, y: Double): Boolean {
                val ci = cellOf(x); val cj = cellOf(y)
                for (di in -1L..1L) for (dj in -1L..1L) {
                    var q = turnCells.first(ci + di, cj + dj)
                    while (q >= 0) {
                        val te = turnCells.itemOf(q); q = turnCells.nextOf(q)
                        val t = turns[te / 2]; val e = te % 2
                        if (Math.hypot(t[2 * e] - x, t[2 * e + 1] - y) < turnReach) return true
                    }
                }
                return false
            }
            for ((pi, pc) in pieces.withIndex()) {
                val m = pc.size
                if (pc.newBead) {
                    if (m == 1 && pc.newPt[0]) segs.add(doubleArrayOf(pc.xs[0], pc.ys[0], pc.xs[0], pc.ys[0], pc.ws[0] / 2 - f / 2, pi.toDouble(), -1.0))
                    for (i in 0 until (if (pc.closed) m else m - 1)) { val j = (i + 1) % m
                        if (pc.newPt[i] || pc.newPt[j]) segs.add(doubleArrayOf(pc.xs[i], pc.ys[i], pc.xs[j], pc.ys[j], (pc.ws[i] + pc.ws[j]) / 4 - f / 2, pi.toDouble(), -1.0)) }
                } else if (pc.loop && levels > 1) for (i in 0 until (if (pc.closed) m else m - 1)) { val j = (i + 1) % m
                    // only near a turn: elsewhere loops lie side by side in contact by construction
                    val mx = (pc.xs[i] + pc.xs[j]) / 2; val my = (pc.ys[i] + pc.ys[j]) / 2
                    if (!nearTurn(mx, my)) continue
                    segs.add(doubleArrayOf(pc.xs[i], pc.ys[i], pc.xs[j], pc.ys[j], (pc.ws[i] + pc.ws[j]) / 4 - f / 2, pi.toDouble(), pc.wall.toDouble())) }
            }
            if (segs.isEmpty()) return@run
            val cell = 200.0
            val grid = CellLists()
            for ((k, g) in segs.withIndex()) {
                val rr = g[4]
                for (i in Math.floor((minOf(g[0], g[2]) - rr) / cell).toLong()..Math.floor((maxOf(g[0], g[2]) + rr) / cell).toLong())
                    for (j in Math.floor((minOf(g[1], g[3]) - rr) / cell).toLong()..Math.floor((maxOf(g[1], g[3]) + rr) / cell).toLong())
                        grid.add(i, j, k)
            }
            /**
             * How far from (x, y) along the unit (dx, dy) plastic piece [self] gives way to starts (a new bead's, or a loop's
             * outside level [level], -1: none), below [limit] (NaN: not within it).
             */
            fun reachTo(x: Double, y: Double, dx: Double, dy: Double, limit: Double, self: Int, level: Int): Double {
                var best = Double.POSITIVE_INFINITY
                val ex = x + dx * limit; val ey = y + dy * limit
                for (ci in Math.floor(minOf(x, ex) / cell).toLong()..Math.floor(maxOf(x, ex) / cell).toLong())
                    for (cj in Math.floor(minOf(y, ey) / cell).toLong()..Math.floor(maxOf(y, ey) / cell).toLong()) {
                        var q = grid.first(ci, cj)
                        while (q >= 0) {
                            val g = segs[grid.itemOf(q)]; q = grid.nextOf(q)
                            if (g[5].toInt() == self || (g[6] >= 0 && g[6] >= level)) continue
                            val t = rayCapsule(x, y, dx, dy, g[0], g[1], g[2], g[3], g[4])
                            if (t < best) best = t
                        }
                    }
                return if (best < limit) best else Double.NaN
            }
            fun box(sel: (DoubleArray) -> Boolean): DoubleArray? { val s = segs.filter(sel); if (s.isEmpty()) return null
                return doubleArrayOf(s.minOf { minOf(it[0], it[2]) - it[4] }, s.minOf { minOf(it[1], it[3]) - it[4] }, s.maxOf { maxOf(it[0], it[2]) + it[4] }, s.maxOf { maxOf(it[1], it[3]) + it[4] }) }
            val newBox = box { it[6] < 0 }
            for ((pi, pc) in pieces.withIndex()) {
                if (pc.newPt.all { it }) continue
                // an inner loop looks at the loops outside it as well as at new beads, on both its sides (its outer side meets
                // the loop outside it in contact, the usual overlap, and only gives way where it reaches farther)
                val level = if (pc.loop && pc.wall > 0) pc.wall else -1
                val wMax = pc.ws.max()
                val nb = newBox
                if (level < 0 && (nb == null || pc.xs.min() - wMax > nb[2] || pc.xs.max() + wMax < nb[0] || pc.ys.min() - wMax > nb[3] || pc.ys.max() + wMax < nb[1])) continue
                // nothing to give way to near any of its points: as it is
                if ((0 until pc.size).none { i -> val ci = Math.floor(pc.xs[i] / cell).toLong(); val cj = Math.floor(pc.ys[i] / cell).toLong()
                        (-3L..3L).any { di -> (-3L..3L).any { dj -> grid.has(ci + di, cj + dj) } } }) continue
                // across the track at points a few steps apart (each part of it gives way, not only where its ribs are). The
                // outer loop's way across is the outline's at the point's sample (from the sample before to the one after): it
                // runs along the outline, and where its place's count rises from two lines to three its points step across the
                // track within one sample's ribs (from half the place to half the outer line's width from the outline), so
                // the way from point to point there runs along the track: those points moved along it, not across, and the
                // line went back and forth over itself. Another line's way across is from the points beside it (more than
                // a micron away: a middle bead's nodes come in pairs at one place); an inner loop gives way on both sides to
                // the loop outside it, which its own track follows where the outline's does not (a tip, a corner)
                pc.resample(4 * GIVE_WAY_STEP_UM)
                val middle = pc.midStart && pc.midEnd
                val m = pc.size
                val nx = DoubleArray(m); val ny = DoubleArray(m)
                for (i in 0 until m) {
                    val s = pc.sample[i]
                    if (pc.loop && pc.wall == 0 && s >= 0) {
                        val ring = ringOf[s]; val q = ring.last - ring.first + 1
                        val sa = ring.first + (s - ring.first - 1 + q) % q; val sb = ring.first + (s - ring.first + 1) % q
                        val dx = (sx[sb] - sx[sa]).toDouble(); val dy = (sy[sb] - sy[sa]).toDouble(); val l = Math.hypot(dx, dy)
                        if (l > 0) { nx[i] = -dy / l; ny[i] = dx / l; continue }
                    }
                    var a = i; var b = i
                    while (a > 0 && Math.hypot(pc.xs[i] - pc.xs[a], pc.ys[i] - pc.ys[a]) < 1.0) a--
                    while (b < m - 1 && Math.hypot(pc.xs[b] - pc.xs[i], pc.ys[b] - pc.ys[i]) < 1.0) b++
                    val dx = pc.xs[b] - pc.xs[a]; val dy = pc.ys[b] - pc.ys[a]; val l = Math.hypot(dx, dy)
                    if (l > 0) { nx[i] = -dy / l; ny[i] = dx / l }
                }
                val xs0 = DoubleArray(m) { pc.xs[it] }; val ys0 = DoubleArray(m) { pc.ys[it] }
                val moved = BooleanArray(m)
                for (i in 0 until m) {
                    if (nx[i] == 0.0 && ny[i] == 0.0 || pc.newPt[i]) continue
                    val w = pc.ws[i]
                    val limit = w / 2 - f / 2
                    if (limit <= 0) continue
                    // the sides to look: both for a middle bead and an inner loop; the outer loop's inner side only (its
                    // outer edge is the outline's)
                    var sides = doubleArrayOf(1.0, -1.0)
                    if (!middle && level < 0) {
                        val s = pc.sample[i]
                        if (s < 0) continue
                        val toward = (xs0[i] - sx[s]) * nx[i] + (ys0[i] - sy[s]) * ny[i]
                        sides = doubleArrayOf(if (toward >= 0) 1.0 else -1.0)
                    }
                    val reach = DoubleArray(2) { w / 2 }
                    for (side in sides) {
                        val t = reachTo(xs0[i], ys0[i], side * nx[i], side * ny[i], limit, pi, level)
                        if (!t.isNaN()) reach[if (side > 0) 0 else 1] = t + f / 2
                    }
                    if (reach[0] == w / 2 && reach[1] == w / 2) continue
                    // the outer loop gives way on its inner side no narrower than the narrowest line (it is never cut)
                    if (pc.loop && pc.wall == 0 && reach[0] + reach[1] < rule.minUm) { val inner = if (sides[0] > 0) 0 else 1; reach[inner] = rule.minUm - reach[1 - inner] }
                    val shift = (reach[0] - reach[1]) / 2
                    pc.xs[i] = xs0[i] + nx[i] * shift; pc.ys[i] = ys0[i] + ny[i] * shift; pc.ws[i] = reach[0] + reach[1]
                    moved[i] = true
                }
                // each point moves across the track on its own: where the plastic given way to begins (a new bead's round
                // start), a point moved out farther than the one before it can land behind it along the track, and the line
                // stepped back and out again, a spike of 15 to 30 µm turning 155 to 160 degrees on a wedge's outer loop, on
                // every layer. Such a point is left out, and so is one moved to within the slicing tolerance of the point
                // before it (a move of a few µm): the line goes on from the point before it to the next one ahead.
                val keep = BooleanArray(m) { true }
                var last = -1
                for (i in 0 until m) {
                    if (last >= 0 && moved[i] && (nx[i] != 0.0 || ny[i] != 0.0)) {
                        // the track's direction at i (its normal turned back a quarter)
                        val ahead = (pc.xs[i] - pc.xs[last]) * ny[i] - (pc.ys[i] - pc.ys[last]) * nx[i]
                        if (ahead <= 0 || Math.hypot(pc.xs[i] - pc.xs[last], pc.ys[i] - pc.ys[last]) < p.toleranceUm) { keep[i] = false; continue }
                    }
                    last = i
                }
                if (!keep.all { it }) pc.keepOnly(keep)
            }
        }
        }
        /**
         * The pieces as laid: where a bead gave way entirely (narrower than half the narrowest line) it is not laid: the piece
         * is cut there, its ends at the cut never joined again (across the new bead). A cut whose two ends would lie within
         * half the narrower one's width of each other is not made: each end's round reaches over the other end, the two lay
         * each other's plastic twice and cover the gap anyway, so the points given way are left out and the line goes on
         * across. (An inner loop's single point on a spur of the axis, a node whose triangle has all three corners on one
         * side, can lie on the loop outside it and give way entirely between neighbors that give way nothing.)
         */
        fun cut(): ArrayList<Piece> {
        val kept = ArrayList<Piece>()
        for (pc in pieces) {
            if (pc.loop && pc.wall == 0) { kept.add(pc); continue }
            val keep = BooleanArray(pc.size) { pc.ws[it] >= rule.minUm / 2 }
            if (keep.all { it }) { kept.add(pc); continue }
            if (keep.none { it }) continue
            // the runs of points given way whose kept neighbors lie within half a line of each other: left out, not cut
            val skip = BooleanArray(pc.size)
            run {
                val m = pc.size
                var i = 0
                while (i < m) {
                    if (keep[i] || (i > 0 && !keep[i - 1]) || (i == 0 && pc.closed && !keep[m - 1])) { i++; continue }
                    // a run of points given way starting at i: its kept neighbors
                    var j = i; var len = 0
                    while (len < m && !keep[(j + m) % m]) { j++; len++ }
                    val a = i - 1; val b = j
                    if ((pc.closed || (a >= 0 && b < m)) && len < m) {
                        val ia = (a + m) % m; val ib = b % m
                        if (Math.hypot(pc.xs[ia] - pc.xs[ib], pc.ys[ia] - pc.ys[ib]) <= minOf(pc.ws[ia], pc.ws[ib]) / 2)
                            for (k in i until j) { skip[k % m] = true }
                    }
                    i = j
                }
            }
            if (skip.any { it }) {
                pc.keepOnly(BooleanArray(pc.size) { !skip[it] })
                for (k in 0 until pc.size) keep[k] = pc.ws[k] >= rule.minUm / 2
                if ((0 until pc.size).all { keep[it] }) { kept.add(pc); continue }
            }
            // an open piece's ends given way are only shortened (still its ends: a middle bead's end still joins at a junction)
            val first = if (pc.closed) 0 else (0 until pc.size).first { keep[it] }
            val last = if (pc.closed) pc.size - 1 else (0 until pc.size).last { keep[it] }
            val start = if (pc.closed) (0 until pc.size).first { !keep[it] } + 1 else first
            val count = if (pc.closed) pc.size else last - first + 1
            var cur: Piece? = null
            for (step in 0 until count) {
                val i = (start + step) % pc.size
                if (keep[i]) {
                    if (cur == null) cur = Piece(wall = pc.wall).also { it.loop = pc.loop; it.cutStart = pc.closed || i != first; if (!it.cutStart) { it.midStart = pc.midStart; it.newStart = pc.newStart } }
                    cur.add(pc.xs[i], pc.ys[i], pc.ws[i], pc.node[i], pc.sample[i], pc.newPt[i])
                    if (!pc.closed && i == last) { cur.midEnd = pc.midEnd; cur.newEnd = pc.newEnd }
                } else if (cur != null) { cur.cutEnd = true; kept.add(cur); cur = null }
            }
            if (cur != null) { if (pc.closed) cur.cutEnd = true; kept.add(cur) }
        }
        return kept
        }

        /**
         * The stubs at junctions among the open pieces [fs] (their indices): a piece with one end on a point where at least
         * two other pieces' ends lie and its other end on no other piece's end, within [reachUm] of the first (the joins'
         * reach: an end at the junction may join it), whose bead lies within a bead of the junction's width there (the
         * widest of the others' ends) but for [Params.endOverlap] of its own width at every point (no further past it than an
         * interior line's end reaches into the walls), and whose side of the axis reaches no further past the junction's bead
         * than the narrowest place a line is laid in ([pokesOut]). At each junction the shortest go while two of its ends stay
         * (a small Y keeps its two longest). Joined as an end, a stub was the closest pair with one of the others there and
         * the third end then joined its dead end: the line ran out along it and back, 40 to 150 microns (on a test slice,
         * nine such zigzags in the outer line; four stay, where the pocket beyond the stub reaches further).
         */
        fun stubs(fs: List<Piece>, reachUm: Double): Set<Int> {
            fun key(x: Double, y: Double) = x.toRawBits() to y.toRawBits()
            val at = HashMap<Pair<Long, Long>, ArrayList<Pair<Int, Boolean>>>()
            for ((i, pc) in fs.withIndex()) for (last in listOf(false, true)) { val k = if (last) pc.size - 1 else 0
                at.getOrPut(key(pc.xs[k], pc.ys[k])) { ArrayList() }.add(i to last) }
            val floor = (p.minFeatureUm + 2 * p.toleranceUm).toDouble()
            val cand = LinkedHashMap<Int, Pair<Long, Long>>()
            for ((i, pc) in fs.withIndex()) for (last in listOf(false, true)) {
                if (pc.loop || pc.size < 2) continue
                val k0 = if (last) pc.size - 1 else 0; val k1 = if (last) 0 else pc.size - 1
                val j = key(pc.xs[k0], pc.ys[k0])
                val others = at.getValue(j).filter { it.first != i }
                if (others.size < 2 || at.getValue(key(pc.xs[k1], pc.ys[k1])).any { it.first != i }) continue
                if (Math.hypot(pc.xs[k1] - pc.xs[k0], pc.ys[k1] - pc.ys[k0]) > reachUm) continue
                val half = others.maxOf { (o, ol) -> fs[o].ws[if (ol) fs[o].size - 1 else 0] } / 2
                val inside = (0 until pc.size).all { k -> val d = Math.hypot(pc.xs[k] - pc.xs[k0], pc.ys[k] - pc.ys[k0]); val w = pc.ws[k]
                    d + w / 2 - half <= p.endOverlap * w }
                if (inside && !pokesOut(pc.node[k0], pc.node[k1], half, floor)) cand[i] = j
            }
            return cand.entries.groupBy { it.value }.flatMap { (j, es) -> es.map { it.key }.sortedBy { fs[it].length() }.take(maxOf(0, at.getValue(j).size - 2)) }.toSet()
        }

        /**
         * Whether the area on a stub's side reaches out past the junction's bead (half-width [half], at node [jn]) by more
         * than [floor]: each node of the axis reached from the stub's dead end (node [en]) without passing the junction (the
         * stub's own and the corners beyond it), by its distance from the junction and its own half-width, no more than its
         * width (the measure Walls' pruning reads a fork's pocket by). Left out, such a stub leaves that much bare: its bead
         * reached into a corner the junction's does not (a narrow island's third arm, 0.5 mm long, run into 90 microns).
         * An end on no node: true (kept).
         */
        fun pokesOut(jn: Int, en: Int, half: Double, floor: Double): Boolean {
            if (jn < 0 || en < 0) return true
            val seen = HashSet<Int>(); val stack = ArrayDeque<Int>()
            seen.add(jn); seen.add(en); stack.add(en)
            while (stack.isNotEmpty()) {
                val u = stack.removeLast()
                if (minOf(2 * r[u], dist(u, jn) + r[u] - half) > floor) return true
                for (q in 0 until 3) { val v = adj[3 * u + q]; if (v >= 0 && seen.add(v)) stack.add(v) }
            }
            return false
        }

        /**
         * 6. Open pieces' ends joined, closest first, a junction's stubs left out ([stubs]); the beads as center lines, each
         * with the line it was joined as with the stubs ([CenterLine.withStubs]) where some were left out.
         */
        fun joins(kept: List<Piece>): List<CenterLine> {
            val joinUm = 2.0 * p.sampleUm + 2 * MIDDLE_EPS_UM
            val open = kept.filter { !it.closed && it.size >= 1 }
            val left = if (!p.stubsGiveWay) emptySet() else stubs(open, joinUm)
            val laid = joined(kept, open.filterIndexed { i, _ -> i !in left }, joinUm)
            return if (left.isEmpty()) laid else Walls.withStubs(laid, joined(kept, open, joinUm))
        }

        /** The closed pieces of [kept] and the open pieces [first] joined, closest first (within [joinUm]); the beads as center lines. */
        fun joined(kept: List<Piece>, first: List<Piece>, joinUm: Double): List<CenterLine> {
        val done = kept.filter { it.closed }.toMutableList()
        // (a piece joined round on itself: closed, the piece itself left as it is for another joining)
        val closedHere = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Piece, Boolean>())
        // each open piece's ends: 2 i its first point, 2 i + 1 its last; what an end allows (a cut end nothing, a middle line's
        // end another middle line's, a loop's piece another's) stays with the end as pieces join
        val ne = 2 * first.size
        val ex = DoubleArray(ne); val ey = DoubleArray(ne); val eMid = BooleanArray(ne); val eNew = BooleanArray(ne); val eCut = BooleanArray(ne)
        for ((i, pc) in first.withIndex()) for (end in 0..1) {
            val e = 2 * i + end; val last = end == 1
            val k0 = if (last) pc.size - 1 else 0
            ex[e] = pc.xs[k0]; ey[e] = pc.ys[k0]
            eMid[e] = if (last) pc.midEnd else pc.midStart; eNew[e] = if (last) pc.newEnd else pc.newStart; eCut[e] = if (last) pc.cutEnd else pc.cutStart
        }
        // the joins that may be made, found once (ends do not move as pieces join), made closest first (within a micron of
        // the closest, the straightest), each when both its ends are still free
        val reach = joinUm
        val grid = HashMap<Long, ArrayList<Int>>()
        fun cellKey(i: Long, j: Long) = (i shl 32) xor (j and 0xffffffffL)
        for (e in 0 until ne) if (!eCut[e]) grid.getOrPut(cellKey(Math.floor(ex[e] / reach).toLong(), Math.floor(ey[e] / reach).toLong())) { ArrayList() }.add(e)
        val cands = ArrayList<Cand>()
        for (a in 0 until ne) {
            if (eCut[a]) continue
            val gi = Math.floor(ex[a] / reach).toLong(); val gj = Math.floor(ey[a] / reach).toLong()
            for (di in -1L..1L) for (dj in -1L..1L) for (b in grid[cellKey(gi + di, gj + dj)] ?: continue) {
                if (b <= a) continue
                val d = Math.hypot(ex[a] - ex[b], ey[a] - ey[b])
                if (eMid[a] != eMid[b] || d > joinUm) continue
                cands.add(Cand(a, b, d))
            }
        }
        cands.sortWith(compareBy<Cand>({ it.d }, { it.a }, { it.b }))
        val owner = arrayOfNulls<Piece>(ne); val atEnd = BooleanArray(ne); val free = BooleanArray(ne) { true }
        val endsOf = java.util.IdentityHashMap<Piece, IntArray>()
        for ((i, pc) in first.withIndex()) { owner[2 * i] = pc; owner[2 * i + 1] = pc; atEnd[2 * i + 1] = true; endsOf[pc] = intArrayOf(2 * i, 2 * i + 1) }
        // a piece closes on itself only when it runs round something (a closed line shorter is a scrap)
        fun valid(c: Cand): Boolean {
            if (!free[c.a] || !free[c.b]) return false
            val pa = owner[c.a]!!
            return pa !== owner[c.b] || (pa.size >= 3 && pa.length() >= 3 * joinUm + Math.PI * pa.ws.max())
        }
        // the turn a join makes: the way one piece runs out of its end against the way the other runs in, as they now stand
        fun dirOut(pc: Piece, end: Boolean): DoubleArray {
            val k0 = if (end) pc.size - 1 else 0; var k1 = if (end) pc.size - 2 else 1
            while (k1 in 0 until pc.size && Math.hypot(pc.xs[k1] - pc.xs[k0], pc.ys[k1] - pc.ys[k0]) < p.sampleUm) k1 += if (end) -1 else 1
            k1 = k1.coerceIn(0, pc.size - 1)
            val dx = pc.xs[k0] - pc.xs[k1]; val dy = pc.ys[k0] - pc.ys[k1]; val l = Math.hypot(dx, dy)
            return if (l == 0.0) doubleArrayOf(0.0, 0.0) else doubleArrayOf(dx / l, dy / l)
        }
        fun turnOf(c: Cand): Double { val da = dirOut(owner[c.a]!!, atEnd[c.a]); val db = dirOut(owner[c.b]!!, atEnd[c.b]); return 1 + (da[0] * db[0] + da[1] * db[1]) }
        val gone = BooleanArray(cands.size)
        var next = 0
        while (true) {
            while (next < cands.size && (gone[next] || !valid(cands[next]))) { gone[next] = true; next++ }
            if (next >= cands.size) break
            // the straightest of those within a micron of the closest
            var pick = next; var pickTurn = turnOf(cands[next])
            var k = next + 1
            while (k < cands.size && cands[k].d < cands[next].d + 1.0) {
                if (!gone[k] && valid(cands[k])) { val t = turnOf(cands[k]); if (t < pickTurn) { pick = k; pickTurn = t } }
                k++
            }
            val c = cands[pick]; gone[pick] = true
            val pa = owner[c.a]!!; val pb = owner[c.b]!!
            if (pa === pb) { closedHere.add(pa); done.add(pa); free[c.a] = false; free[c.b] = false; continue }
            val a = if (atEnd[c.a]) pa else pa.reversed()
            val b = if (atEnd[c.b]) pb.reversed() else pb
            val j = Piece(ArrayList(a.xs), ArrayList(a.ys), ArrayList(a.ws), minOf(a.wall, b.wall))
            for (k in 0 until b.size) j.add(b.xs[k], b.ys[k], b.ws[k])
            j.newBead = a.newBead || b.newBead; j.midStart = a.midStart; j.midEnd = b.midEnd; j.newStart = a.newStart; j.newEnd = b.newEnd; j.cutStart = a.cutStart; j.cutEnd = b.cutEnd
            val oa = endsOf.getValue(pa).first { it != c.a }; val ob = endsOf.getValue(pb).first { it != c.b }
            free[c.a] = false; free[c.b] = false
            owner[oa] = j; atEnd[oa] = false; owner[ob] = j; atEnd[ob] = true; endsOf[j] = intArrayOf(oa, ob)
            endsOf.remove(pa); endsOf.remove(pb)
        }
        val open = ArrayList<Piece>()
        val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Piece, Boolean>())
        for (e in 0 until ne) if (free[e]) { val pc = owner[e]!!; if (seen.add(pc)) open.add(pc) }
        val out = ArrayList<CenterLine>()
        for (pc in done + open) {
            // a closed piece's line ends on its first point
            val closed = pc.closed || pc in closedHere
            if (closed && pc.size < 3) continue
            val shut = closed && (pc.xs.last() != pc.xs[0] || pc.ys.last() != pc.ys[0])
            val m = pc.size + (if (shut) 1 else 0)
            if (m < 2 || pc.length() + (if (shut) Math.hypot(pc.xs[0] - pc.xs.last(), pc.ys[0] - pc.ys.last()) else 0.0) < 1.0) continue
            out.add(CenterLine(LongArray(2 * m) { k -> val q = (k / 2) % pc.size; Math.round(if (k % 2 == 0) pc.xs[q] else pc.ys[q]) },
                LongArray(m) { Math.round(pc.ws[it % pc.size]) }, wall = pc.wall))
        }
        return out
        }
    }
}

