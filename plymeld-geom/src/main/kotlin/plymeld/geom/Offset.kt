package plymeld.geom

/**
 * Polygon offsetting: grow (delta > 0) or shrink (delta < 0) a polygon set by |delta|
 * grid units.
 *
 * [offset] — raw-ring method (the fast one):
 *  Every ring has material on the LEFT of each directed edge (CCW outers, CW holes), so
 *  shrinking moves every edge to its left by |delta| and growing to its right. The moved
 *  edges are joined into one raw ring per input ring:
 *   - at a corner turning toward the offset side, the two moved edges are connected
 *     through the original vertex (the little loop this makes is wound the wrong way);
 *   - at a corner turning away, a join is inserted: miter point (within [miterLimit]),
 *     bevel beyond it, or a round arc with chord sag <= [arcTolerance].
 *  The raw rings are then unioned under the POSITIVE rule (winding > 0), which removes
 *  every collapsed or wrong-way loop. One edge per input edge (+ joins), so the boolean
 *  sees ~10x fewer edges than the sleeve method.
 *
 * [offsetBySleeves] — the slow, obviously-correct method kept as an independent second
 * implementation for tests: edge quads + vertex join pieces, subtracted/unioned.
 */
object Offset {
    enum class Join { ROUND, MITER }

    /** Vertices closer than this to the line through their neighbors are grid-rounding jitter (µm): a geometric tolerance. */
    private const val JITTER_UM = 1L

    fun offset(
        polys: List<Polygon>, delta: Long, join: Join = Join.MITER,
        miterLimit: Double = 2.0, arcTolerance: Long = 5,
    ): List<Polygon> {
        if (delta == 0L || polys.isEmpty()) return Booleans.normalize(polys)
        val d = Math.abs(delta).toDouble()
        val sigma = if (delta < 0) 1.0 else -1.0          // +1: move edges left (shrink), -1: right (grow)
        val raw = ArrayList<Polygon>(polys.size)
        for (p0 in polys) {
            // A ring whose own side shrinks (an outer when shrinking, a hole when growing) and is
            // narrower than 2|delta| across, in x or in y, vanishes EXACTLY: every point inside it
            // is within |delta| of its boundary along that axis. Dropped outright: slivers the
            // slicer leaves (the Benchy's stern hole came through as dozens of 0.1-0.5 mm
            // fragments) otherwise came back as stray pieces (OffsetVsSleeves).
            val ownSideShrinks = (p0.signedArea2() > 0) == (delta < 0)
            if (ownSideShrinks && p0.size >= 3) {
                val bb = p0.bounds()
                if (bb[2] - bb[0] < 2 * d || bb[3] - bb[1] < 2 * d) continue
            }
            // no repeated or collinear vertices (every edge has a length), and no grid-rounding
            // jitter: a vertex within 1 um of the line through its neighbors is dropped. Rounding
            // a dense curve to the 1 um grid bends some of its corners the wrong way by a hair;
            // each such corner then took a join instead of a trim (OffsetRepeatTest).
            val p = p0.douglasPeucker(JITTER_UM).simplified().let { q ->
                // no zero-length edge may survive, wrap-around included: a 70 um sliver ring on the
                // Benchy's stern came through with its first vertex repeated at the end, and the
                // division by that edge's length put a vertex at (0, 0) (OffsetVsSleeves)
                val keep = ArrayList<Long>(q.xy.size)
                for (i in 0 until q.size) {
                    val lx = if (keep.isEmpty()) Long.MIN_VALUE else keep[keep.size - 2]; val ly = if (keep.isEmpty()) Long.MIN_VALUE else keep[keep.size - 1]
                    if (q.x(i) != lx || q.y(i) != ly) { keep.add(q.x(i)); keep.add(q.y(i)) }
                }
                while (keep.size >= 4 && keep[0] == keep[keep.size - 2] && keep[1] == keep[keep.size - 1]) { keep.removeAt(keep.size - 1); keep.removeAt(keep.size - 1) }
                Polygon(keep.toLongArray())
            }
            val n = p.size
            if (n < 3) continue
            val out = ArrayList<Long>(n * 4)
            fun add(x: Double, y: Double) {
                check(x.isFinite() && y.isFinite()) { "non-finite offset point" }
                out.add(Math.round(x)); out.add(Math.round(y))
            }
            // Moved edges, trimmed where they meet. Edge j (vertex j -> j+1) moves to the line
            // through (mx[j], my[j]) along (ex[j], ey[j]); a position s along it is 0 at its moved
            // start and 1 at its moved end. At a corner turning toward the offset side the two
            // moved edges meet at one point: the corner of the offset. An edge whose start meeting
            // comes at or after its end meeting has VANISHED; when both of its corners turn toward
            // the offset side it is removed and its neighbors are made to meet directly, repeated
            // until every remaining edge survives (local trimming of the offset curve).
            //
            // The former raw-ring rule connected EVERY such corner through the original vertex: on a
            // dense curve each corner became a spike |delta| long for the union to untangle, and
            // the union's grid rounding lost shape at every pass. Measured on a 4000-vertex ellipse
            // (OffsetRepeatTest): 24 s for one 30 mm offset, and 50 offsets of 0.55 mm
            // ended 9.3 mm off. Where an edge vanishes next to a join (a corner turning away) it
            // keeps that rule and the union removes the loop.
            val ex = DoubleArray(n); val ey = DoubleArray(n); val mx = DoubleArray(n); val my = DoubleArray(n)
            for (j in 0 until n) {
                val j1 = (j + 1) % n
                ex[j] = (p.x(j1) - p.x(j)).toDouble(); ey[j] = (p.y(j1) - p.y(j)).toDouble()
                val l = Math.hypot(ex[j], ey[j])
                mx[j] = p.x(j) + sigma * d * (-ey[j] / l); my[j] = p.y(j) + sigma * d * (ex[j] / l)
            }
            val prev = IntArray(n) { (it - 1 + n) % n }; val next = IntArray(n) { (it + 1) % n }
            val alive = BooleanArray(n) { true }
            val sStart = DoubleArray(n) { 0.0 }; val sEnd = DoubleArray(n) { 1.0 }
            val cx = DoubleArray(n); val cy = DoubleArray(n); val met = BooleanArray(n)   // corner before edge j
            /** The corner between prev[b] and b: meeting point, if it turns toward the offset side. */
            fun corner(b: Int) {
                val a = prev[b]
                val turn = ex[a] * ey[b] - ey[a] * ex[b]
                met[b] = false; sEnd[a] = 1.0; sStart[b] = 0.0
                if (sigma * turn <= 0) return
                // mx[a] + t*ex[a] = mx[b] + u*ex[b]
                val wx = mx[b] - mx[a]; val wy = my[b] - my[a]
                val t = (wx * ey[b] - wy * ex[b]) / turn
                val u = (wx * ey[a] - wy * ex[a]) / turn
                // a true corner of the offset lies before edge a's moved end and after edge b's
                // moved start; after trimming, two far-apart neighbors can meet far out along their
                // extensions instead (a spike the union would keep): not a corner, keep the loop
                if (t > 1.0 || u < 0.0) return
                sEnd[a] = t; sStart[b] = u
                cx[b] = mx[a] + t * ex[a]; cy[b] = my[a] + t * ey[a]
                met[b] = true
            }
            for (b in 0 until n) corner(b)
            // vanished: its trimmed span is empty, or lies outside the edge itself (a corner of the
            // offset never lengthens an edge; a span pushed past it made a 30 mm spike on the
            // Benchy's stern, OffsetVsSleeves)
            fun vanished(j: Int) = alive[j] && (sStart[j] >= sEnd[j] || sStart[j] > 1.0 || sEnd[j] < 0.0)
            fun removable(j: Int) = met[j] && met[next[j]]
            var left = n
            val queue = ArrayDeque<Int>()
            for (j in 0 until n) if (vanished(j) && removable(j)) queue.add(j)
            while (queue.isNotEmpty() && left > 3) {
                val j = queue.removeFirst()
                if (!vanished(j) || !removable(j)) continue
                val a = prev[j]; val b = next[j]
                alive[j] = false; left--
                next[a] = b; prev[b] = a
                corner(b)
                for (k in intArrayOf(a, b)) if (vanished(k) && removable(k)) queue.add(k)
            }
            // A CLEAN ring: after trimming, every edge left survives and every corner turning toward
            // the offset side has its meeting point; its offset is exactly those meeting points and
            // joins. Anything else (a ring collapsing, as a small triangle shrunk past its inradius)
            // takes the raw-ring rule for the WHOLE ring: a mix of trimmed corners and loops is not
            // an offset outline, and the union kept stray pieces of it (Benchy part B layer 136,
            // OffsetVsSleeves).
            var useMeets = true
            run {
                val s0 = (0 until n).first { alive[it] }
                var j = s0
                do {
                    val a = prev[j]
                    val merged = a != (j - 1 + n) % n          // a corner that trimming created
                    if (vanished(j) || (sigma * (ex[a] * ey[j] - ey[a] * ex[j]) > 0 && !met[j]) || (merged && !met[j])) useMeets = false
                    j = next[j]
                } while (j != s0 && useMeets)
            }
            if (!useMeets) for (j in 0 until n) { prev[j] = (j - 1 + n) % n; next[j] = (j + 1) % n; alive[j] = true }
            val start = (0 until n).first { alive[it] }
            var b = start
            do {
                val a = prev[b]
                val vx = p.x(b).toDouble(); val vy = p.y(b).toDouble()
                val e1x = ex[a]; val e1y = ey[a]; val e2x = ex[b]; val e2y = ey[b]
                val l1 = Math.hypot(e1x, e1y); val l2 = Math.hypot(e2x, e2y)
                // left unit normals
                val n1x = -e1y / l1; val n1y = e1x / l1
                val n2x = -e2y / l2; val n2y = e2x / l2
                val p1x = mx[a] + ex[a]; val p1y = my[a] + ey[a]                    // edge a's moved end
                val p2x = mx[b]; val p2y = my[b]                                      // edge b's moved start
                val turn = e1x * e2y - e1y * e2x                  // > 0: left turn
                val dot = e1x * e2x + e1y * e2y
                if (sigma * turn > 0 || (turn == 0.0 && dot > 0)) {
                    // corner turns toward the offset side (or straight)
                    if (useMeets && met[b]) add(cx[b], cy[b])
                    else {
                        add(p1x, p1y)
                        if (turn != 0.0) add(vx, vy)
                        add(p2x, p2y)
                    }
                } else {
                    // corner turns away from the offset side: insert a join
                    when (join) {
                        Join.MITER -> {
                            var mx = n1x + n2x; var my = n1y + n2y
                            val ml = Math.hypot(mx, my)
                            if (ml < 1e-9) { add(p1x, p1y); add(p2x, p2y) }   // reversal: bevel cap
                            else {
                                mx /= ml; my /= ml
                                val cosHalf = mx * n1x + my * n1y
                                val reach = d / cosHalf
                                if (reach <= miterLimit * d) add(vx + sigma * reach * mx, vy + sigma * reach * my)
                                else { add(p1x, p1y); add(p2x, p2y) }
                            }
                        }
                        Join.ROUND -> {
                            val a0 = Math.atan2(sigma * n1y, sigma * n1x)
                            var sweep = Math.atan2(sigma * n2y, sigma * n2x) - a0
                            // sweep the short way in the direction of the turn
                            if (turn > 0 && sweep < 0) sweep += 2 * Math.PI
                            if (turn < 0 && sweep > 0) sweep -= 2 * Math.PI
                            if (turn == 0.0) sweep = if (sigma > 0) -Math.PI else Math.PI   // 180° reversal: half circle
                            val sag = arcTolerance.coerceAtLeast(1).toDouble()
                            val step = 2 * Math.acos(1 - Math.min(sag / d, 1.0))
                            val steps = Math.max(1, Math.ceil(Math.abs(sweep) / step).toInt())
                            for (i in 0..steps) {
                                val a = a0 + sweep * i / steps
                                add(vx + d * Math.cos(a), vy + d * Math.sin(a))
                            }
                        }
                    }
                }
                b = next[b]
            } while (b != start)
            if (out.size >= 6) raw.add(Polygon(out.toLongArray()))
        }
        return Booleans.unionPositive(raw)
    }

    /** Slow reference implementation (edge sleeves + joins). Kept for cross-checking [offset]. */
    fun offsetBySleeves(
        polys: List<Polygon>, delta: Long, join: Join = Join.MITER,
        miterLimit: Double = 2.0, arcTolerance: Long = 5,
    ): List<Polygon> {
        if (delta == 0L || polys.isEmpty()) return Booleans.normalize(polys)
        val d = Math.abs(delta)
        val sleeves = ArrayList<Polygon>()
        for (p in polys) {
            val n = p.size
            if (n < 3) continue
            for (i in 0 until n) {
                val j = (i + 1) % n
                val ax = p.x(i); val ay = p.y(i); val bx = p.x(j); val by = p.y(j)
                val ex = (bx - ax).toDouble(); val ey = (by - ay).toDouble()
                val len = Math.hypot(ex, ey)
                if (len == 0.0) continue
                val nx = Math.round(ey / len * d); val ny = Math.round(-ex / len * d)
                sleeves.add(ccw(Polygon(longArrayOf(ax - nx, ay - ny, bx - nx, by - ny, bx + nx, by + ny, ax + nx, ay + ny))))
                sleeves.add(joinPiece(p, j, d, join, miterLimit, arcTolerance))
            }
        }
        return if (delta > 0) Booleans.union(polys, sleeves) else Booleans.difference(polys, sleeves)
    }

    /** Symmetric join piece at vertex k for the sleeve method (disc, miter hexagon, or bevel quad). */
    private fun joinPiece(p: Polygon, k: Int, d: Long, join: Join, miterLimit: Double, tol: Long): Polygon {
        val cx = p.x(k); val cy = p.y(k)
        if (join == Join.ROUND) return disc(cx, cy, d, tol)
        val n = p.size
        val px = p.x((k - 1 + n) % n); val py = p.y((k - 1 + n) % n)
        val qx = p.x((k + 1) % n); val qy = p.y((k + 1) % n)
        val e1x = (cx - px).toDouble(); val e1y = (cy - py).toDouble()
        val e2x = (qx - cx).toDouble(); val e2y = (qy - cy).toDouble()
        val l1 = Math.hypot(e1x, e1y); val l2 = Math.hypot(e2x, e2y)
        if (l1 == 0.0 || l2 == 0.0) return disc(cx, cy, d, tol)
        val n1x = e1y / l1; val n1y = -e1x / l1
        val n2x = e2y / l2; val n2y = -e2x / l2
        var mx = n1x + n2x; var my = n1y + n2y
        val ml = Math.hypot(mx, my)
        if (ml < 1e-9) return disc(cx, cy, d, tol)
        mx /= ml; my /= ml
        val cosHalf = mx * n1x + my * n1y
        val reach = d / cosHalf
        val pts = ArrayList<Long>(12)
        fun add(x: Double, y: Double) { pts.add(cx + Math.round(x)); pts.add(cy + Math.round(y)) }
        if (reach <= miterLimit * d) {
            add(n1x * d, n1y * d); add(mx * reach, my * reach); add(n2x * d, n2y * d)
            add(-n1x * d, -n1y * d); add(-mx * reach, -my * reach); add(-n2x * d, -n2y * d)
        } else {
            add(n1x * d, n1y * d); add(n2x * d, n2y * d); add(-n1x * d, -n1y * d); add(-n2x * d, -n2y * d)
        }
        val poly = ccw(Polygon(pts.toLongArray()))
        return if (poly.signedArea2() == 0L) disc(cx, cy, d, tol) else poly
    }

    /** Regular polygon around (cx, cy) with vertices on radius r and chord sag <= tol. */
    fun disc(cx: Long, cy: Long, r: Long, tol: Long): Polygon {
        val sag = tol.coerceAtLeast(1).toDouble()
        val steps = Math.max(8, Math.ceil(Math.PI / Math.acos(1 - Math.min(sag / r, 1.0))).toInt())
        val xy = LongArray(2 * steps)
        for (i in 0 until steps) {
            val a = 2 * Math.PI * i / steps
            xy[2 * i] = cx + Math.round(r * Math.cos(a)); xy[2 * i + 1] = cy + Math.round(r * Math.sin(a))
        }
        return Polygon(xy)
    }

    private fun ccw(p: Polygon) = if (p.signedArea2() >= 0) p else p.reversed()
}
