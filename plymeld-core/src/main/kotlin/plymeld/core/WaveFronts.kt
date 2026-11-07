package plymeld.core

import plymeld.geom.Booleans
import plymeld.geom.Offset
import plymeld.geom.Polygon
import plymeld.geom.Polygons

/**
 * Wave overhangs' geometry (a surface technique):
 * an underside over air filled from the supported material beside it by fronts, each the previous front offset
 * outward by one bead spacing and clipped to the overhang, so every bead leans on the one before it sideways. Fronts round
 * corners and holes on their own (an offset is Huygens' union of circles); a neck narrower than [Params.minNeckUm] is cut
 * so a front does not bridge it; growth stops when a front adds less than [Params.minAreaMm2] or the overhang is full.
 *
 * plymeld's own from the sources' behavior; none of their code was read.
 */
object WaveFronts {
    class Params(
        /** Bead spacing (µm): how far each front lies from the last; the sources use about 0.35 mm for a 0.4 mm nozzle. */
        val spacingUm: Long,
        /** How far the first bead reaches back over the supported material (µm): its overlap with the anchor. */
        val overlapUm: Long,
        /** A neck narrower than this (µm) is cut: a front does not span it. */
        val minNeckUm: Long,
        /** Growth stops when a front covers less than this (mm²). */
        val minAreaMm2: Double = 0.01,
        /** At most this many fronts (a guard). */
        val maxFronts: Int = 2000,
        /** The bead's width (µm); 0 = not known (the exploration: the first front reaches overlap + spacing). */
        val beadUm: Long = 0,
    ) {
        /** How far the first front reaches from the anchor's edge: one bead less the overlap, so the first bead's inner edge lies [overlapUm] over the anchor. */
        val firstUm: Long get() = if (beadUm > 0) beadUm - overlapUm else overlapUm + spacingUm
    }

    /** One front: its polygons (the area covered so far: the anchor's side is its seam, the far side the advancing edge), and what it adds (mm²). */
    class Front(val loops: List<Polygon>, val addedMm2: Double)

    /**
     * The fronts filling [overhang] (the overhang region of one layer) from [anchor] (the supported material it touches):
     * front 0 is the anchor's edge grown into the overhang by [Params.firstUm], each next front one spacing further.
     */
    fun grow(overhang: List<Polygon>, anchor: List<Polygon>, p: Params): List<Front> {
        if (overhang.isEmpty() || anchor.isEmpty()) return emptyList()
        // a neck of the overhang narrower than the limit is cut once, up front: the region is opened by half the neck and
        // closed back (what lies beyond a cut neck is then out of the fronts' reach, below); a front itself is one spacing
        // wide, narrower than any neck, so the cut cannot be applied to the fronts
        val region = if (p.minNeckUm <= 0) overhang else
            Booleans.intersection(Offset.offset(Offset.offset(overhang, -p.minNeckUm / 2, Offset.Join.MITER), p.minNeckUm / 2, Offset.Join.MITER), overhang)
        val out = ArrayList<Front>()
        var covered: List<Polygon> = emptyList()
        var coveredMm2 = 0.0
        while (out.size < p.maxFronts) {
            // the next front: the last front (the anchor at first) offset one spacing outward, inside the overhang. Offsetting
            // the FRONT, not the anchor, is Huygens' rule: the wave goes round a corner or a hole from the beads already
            // laid, and never reaches a place its neighbor was clipped away from (an offset of the anchor alone would
            // reach straight across a hole to points no bead supports)
            val next = Booleans.intersection(Offset.offset(if (covered.isEmpty()) anchor else covered,
                if (covered.isEmpty()) p.firstUm else p.spacingUm, Offset.Join.ROUND), region)
            val area = next.sumOf { it.signedAreaMm2() }
            val added = area - coveredMm2
            if (added < p.minAreaMm2) break
            out += Front(next, added)
            covered = next; coveredMm2 = area
        }
        return out
    }

    /** The bead paths of the fronts as loops: each front's boundary inset by half a bead (the exploration's view). */
    fun paths(fronts: List<Front>, overhang: List<Polygon>, beadUm: Long): List<List<Polygon>> =
        fronts.map { f -> Offset.offset(f.loops, -beadUm / 2, Offset.Join.ROUND).filter { it.signedAreaMm2() > 0 } }

    /** The share of [overhang] the fronts cover (1 = full). */
    fun coverage(fronts: List<Front>, overhang: List<Polygon>): Double {
        val total = overhang.sumOf { it.signedAreaMm2() }
        if (total <= 0 || fronts.isEmpty()) return 0.0
        return fronts.last().loops.sumOf { it.signedAreaMm2() } / total
    }

    /** One bead: its center line (x y ... µm), closed when it runs all the way round (a cap over a pillar). */
    class Bead(val xy: LongArray, val closed: Boolean)

    /**
     * The beads the fronts are laid as: each front's new edge, the part of its boundary that is not on the front before
     * it (the anchor for the first: the seam), moved half a bead inward so the bead's outer edge lies on the front. Where
     * a front is clipped by the overhang's outline its edge there is new too: the bead runs along the outline, half a
     * bead in, so a ledge's far edge and sides get their beads. A boundary new all the way round is one closed bead;
     * otherwise each run of new edges is an open bead, in the loop's order.
     */
    fun beads(fronts: List<Front>, anchor: List<Polygon>, beadUm: Long,
              /**
               * The overhang the fronts fill, when they may close in on themselves (a region the anchor surrounds: a waved
               * bridge's fill, plan.technique.bridge.wave.start): a hole in the covered region is then the front advancing
               * inward, except where it runs along the overhang's own boundary (a hole in the part). Null: every hole is the
               * overhang's (the fronts grow out from one side).
               */
              overhang: List<Polygon>? = null,
              /**
               * Where fronts close in on themselves, the most of a piece left unlaid without a bead along its middle (mm²): the
               * fronts' own least area (Params.minAreaMm2). Null: no such beads.
               */
              meetMinAreaMm2: Double? = null): List<List<Bead>> {
        val out = ArrayList<List<Bead>>(fronts.size)
        // with the overhang given, a bead must lie on it or on the anchor: a front narrower than a bead (a chevron's first
        // fronts, slivers in the corners) moved half a bead in pokes past the overhang's edge, where it has no surface under
        // it (such a bead lifted to the curved layer's flat height, 12.28 mm, beside a foot at 8.28)
        val within = overhang?.let { o -> Polygons.group(Offset.offset(Booleans.union(o, anchor), TOL_UM.toLong(), Offset.Join.MITER)) }
        fun inside(b: Bead): Boolean = within == null || (0 until b.xy.size / 2).all { k ->
            within.any { (o, hs) -> Polygons.contains(o, b.xy[2 * k], b.xy[2 * k + 1]) && hs.none { Polygons.contains(it, b.xy[2 * k], b.xy[2 * k + 1]) } } }
        var prev = anchor
        for (f in fronts) {
            val beads = ArrayList<Bead>()
            for (loop in f.loops) {
                val hole = loop.signedAreaMm2() <= 0
                if (hole && overhang == null) continue   // a hole in the covered region: its edge is the overhang's hole, not a front
                val n = loop.size
                val advancing = BooleanArray(n) { e ->
                    val j = (e + 1) % n
                    val mx = (loop.x(e) + loop.x(j)) / 2; val my = (loop.y(e) + loop.y(j)) / 2
                    distToEdges(mx, my, prev) > TOL_UM && (!hole || distToEdges(mx, my, overhang!!) > TOL_UM)
                }
                if (advancing.all { it }) { beads += Bead(inward(loop, 0, n, true, beadUm / 2), true); continue }
                if (advancing.none { it }) continue
                // runs of advancing edges, starting after a non-advancing one so no run is split at index 0
                val start = (0 until n).first { !advancing[it] }
                var e = start
                repeat(n) { _ ->
                    val k = (e + 1) % n
                    if (advancing[k] && !advancing[e]) {
                        var len = 0; while (advancing[(k + len) % n]) len++
                        beads += Bead(inward(loop, k, len, false, beadUm / 2), false)
                    }
                    e = k
                }
            }
            val kept = beads.filter(::inside)
            // where fronts close in on themselves (a slot between two sides of the anchor, fronts from both meeting), the
            // front filling the middle has no new edge there: its boundary lies on the front before and the outline. What
            // no bead lays there gets one bead along its middle (beadUm known)
            val meet = if (beadUm > 0 && meetMinAreaMm2 != null)
                meeting(Booleans.difference(f.loops, prev), kept + (out.lastOrNull() ?: emptyList()), beadUm, meetMinAreaMm2).filter(::inside) else emptyList()
            out += kept + meet
            prev = f.loops
        }
        return out
    }

    /**
     * Beads along the middle of the pieces of [added] (what a front adds) that [laid] (the beads of this front and the one
     * before) leaves unlaid: a piece wider than a bead (a front's band is a step wide, under a bead), with more than
     * [minAreaMm2] of it farther than half a bead (and [TOL_UM]) from every bead's line. The middle: the piece shrunk until one more step would leave
     * nothing, read between its two points farthest apart. A piece too round to have a middle line (shorter than a bead)
     * gets none.
     */
    private fun meeting(added: List<Polygon>, laid: List<Bead>, beadUm: Long, minAreaMm2: Double): List<Bead> {
        if (added.isEmpty()) return emptyList()
        val out = ArrayList<Bead>()
        val segs = laid.flatMap { b -> val n = b.xy.size / 2
            (0 until (if (b.closed) n else n - 1)).map { k -> val j = (k + 1) % n; longArrayOf(b.xy[2 * k], b.xy[2 * k + 1], b.xy[2 * j], b.xy[2 * j + 1]) } }
        val far = beadUm / 2.0 + TOL_UM
        for ((o, hs) in Polygons.group(added)) {
            val area = o.signedAreaMm2() + hs.sumOf { it.signedAreaMm2() }
            val perimeterMm = (listOf(o) + hs).sumOf { p -> (0 until p.size).sumOf { i -> val j = (i + 1) % p.size; Math.hypot((p.x(j) - p.x(i)).toDouble(), (p.y(j) - p.y(i)).toDouble()) } } / 1000.0
            // a band a front adds is about a step wide (area over half the perimeter); only a wider piece can hold a gap
            if (area <= minAreaMm2 || area / (perimeterMm / 2) < beadUm / 1000.0) continue
            val b = o.bounds()
            val step = beadUm / 4
            val near = segs.filter { s -> maxOf(s[0], s[2]) >= b[0] - beadUm && minOf(s[0], s[2]) <= b[2] + beadUm && maxOf(s[1], s[3]) >= b[1] - beadUm && minOf(s[1], s[3]) <= b[3] + beadUm }
            var unlaid = 0
            var y = b[1] + step / 2
            while (y < b[3]) {
                var x = b[0] + step / 2
                while (x < b[2]) {
                    if (Polygons.contains(o, x, y) && hs.none { Polygons.contains(it, x, y) } && near.none { s -> segDist(x, y, s) <= far }) unlaid++
                    x += step
                }
                y += step
            }
            if (unlaid * step.toDouble() * step / 1e6 <= minAreaMm2) continue
            middle(listOf(o) + hs, beadUm)?.let { out += it }
        }
        return out
    }

    /** The middle line of a narrow piece: shrunk in steps of a sixteenth of a bead (a resolution) while something is left, then read between its farthest points. */
    private fun middle(piece: List<Polygon>, beadUm: Long): Bead? {
        var last = piece
        var d = beadUm / 16
        while (true) {
            val s = Offset.offset(piece, -d, Offset.Join.ROUND).filter { it.signedArea() > 0 }
            if (s.isEmpty()) break
            last = s; d += beadUm / 16
        }
        val loop = last.maxBy { it.signedArea() }
        val n = loop.size
        if (n < 2) return null
        var bi = 0; var bj = 0; var best = -1.0
        for (i in 0 until n) for (j in i + 1 until n) {
            val dd = Math.hypot((loop.x(i) - loop.x(j)).toDouble(), (loop.y(i) - loop.y(j)).toDouble())
            if (dd > best) { best = dd; bi = i; bj = j }
        }
        if (best < beadUm) return null
        val xy = LongArray(2 * (bj - bi + 1))
        for (k in bi..bj) { xy[2 * (k - bi)] = loop.x(k); xy[2 * (k - bi) + 1] = loop.y(k) }
        return Bead(xy, false)
    }

    private fun segDist(x: Long, y: Long, s: LongArray): Double {
        val vx = (s[2] - s[0]).toDouble(); val vy = (s[3] - s[1]).toDouble(); val l2 = vx * vx + vy * vy
        val t = if (l2 == 0.0) 0.0 else (((x - s[0]) * vx + (y - s[1]) * vy) / l2).coerceIn(0.0, 1.0)
        return Math.hypot(x - s[0] - t * vx, y - s[1] - t * vy)
    }


    /**
     * The polyline of [count] edges of [loop] from edge [from] (a closed loop when [closed]: all of it), each vertex moved
     * [byUm] toward the loop's inside along the mean of its edges' inward normals (the loop is counter-clockwise: inside is
     * to the left of each edge).
     */
    private fun inward(loop: Polygon, from: Int, count: Int, closed: Boolean, byUm: Long): LongArray {
        val n = loop.size
        val m = if (closed) n else count + 1   // vertices: an open run of k edges has k + 1
        val xy = LongArray(2 * m)
        for (v in 0 until m) {
            val i = (from + v) % n
            val ip = (i - 1 + n) % n; val inx = (i + 1) % n
            // the inward normals of the edges before and after vertex i, the loop's both (an open run's end vertex lies on the seam or the outline: it moves in from that edge too, so the bead's end stays half a bead inside)
            fun normal(a: Int, b: Int): DoubleArray {
                val dx = (loop.x(b) - loop.x(a)).toDouble(); val dy = (loop.y(b) - loop.y(a)).toDouble()
                val l = Math.hypot(dx, dy); return if (l == 0.0) doubleArrayOf(0.0, 0.0) else doubleArrayOf(-dy / l, dx / l)
            }
            val n1: DoubleArray? = normal(ip, i)
            val n2: DoubleArray? = normal(i, inx)
            var nx = (n1?.get(0) ?: 0.0) + (n2?.get(0) ?: 0.0); var ny = (n1?.get(1) ?: 0.0) + (n2?.get(1) ?: 0.0)
            // along the bisector, far enough that each edge is byUm away (a miter: 2 / |n1 + n2| times byUm), capped at a sharp corner
            val sum = Math.hypot(nx, ny)
            val d = if (sum > 1e-9) minOf(2.0 * byUm / sum, 2.0 * byUm) else 0.0
            if (sum > 1e-9) { nx /= sum; ny /= sum }
            xy[2 * v] = loop.x(i) + Math.round(nx * d); xy[2 * v + 1] = loop.y(i) + Math.round(ny * d)
        }
        return xy
    }

    /** The distance (µm) from (x, y) to the nearest edge of [polys]. */
    fun distToEdges(x: Long, y: Long, polys: List<Polygon>): Double {
        var best = Double.POSITIVE_INFINITY
        for (p in polys) for (i in 0 until p.size) {
            val j = (i + 1) % p.size
            val ax = p.x(i).toDouble(); val ay = p.y(i).toDouble(); val bx = p.x(j).toDouble(); val by = p.y(j).toDouble()
            val vx = bx - ax; val vy = by - ay; val l2 = vx * vx + vy * vy
            val t = if (l2 == 0.0) 0.0 else (((x - ax) * vx + (y - ay) * vy) / l2).coerceIn(0.0, 1.0)
            val d = Math.hypot(x - ax - t * vx, y - ay - t * vy)
            if (d < best) best = d
        }
        return best
    }

    /** How near an edge's middle may lie to the front before (or the outline) and still be a new edge (µm): the kernel's snapping is 1 µm. */
    const val TOL_UM = 25.0
}
