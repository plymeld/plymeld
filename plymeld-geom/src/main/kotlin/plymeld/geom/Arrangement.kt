package plymeld.geom

/**
 * Step 1 of the boolean core (see DESIGN.md): split a set of directed edges at every
 * pairwise intersection so that afterwards edges meet only at endpoints.
 *
 * Input edges carry a source tag (0 = A, 1 = B). Output pieces keep the tag and the
 * direction of the edge they came from. Intersection points are rounded to the grid;
 * because a rounded point can land on a third edge, the split is repeated until a
 * pass finds no new crossing (bounded; in practice 1–2 passes).
 *
 * Coordinates must satisfy |c| <= 2^29 (about 0.5 km at 1 µm) — see [Booleans].
 */
object Arrangement {

    /** Flat edge list: x0 y0 x1 y1 per edge; `tag` per edge. */
    class Edges(val xy: LongArray, val tag: IntArray) {
        val size: Int get() = tag.size
        fun x0(i: Int) = xy[4 * i]; fun y0(i: Int) = xy[4 * i + 1]; fun x1(i: Int) = xy[4 * i + 2]; fun y1(i: Int) = xy[4 * i + 3]
        fun p(i: Int) = IntPoint(x0(i), y0(i)); fun q(i: Int) = IntPoint(x1(i), y1(i))
    }

    /** Directed edges of both sets; zero-length edges (repeated vertices) are dropped here. */
    fun edgesOf(a: List<Polygon>, b: List<Polygon>): Edges {
        val n = a.sumOf { it.size } + b.sumOf { it.size }
        val xy = LongArray(n * 4); val tag = IntArray(n)
        var e = 0
        for ((t, set) in listOf(a, b).withIndex()) for (poly in set) {
            val m = poly.size
            for (i in 0 until m) {
                val j = (i + 1) % m
                if (poly.x(i) == poly.x(j) && poly.y(i) == poly.y(j)) continue
                xy[4 * e] = poly.x(i); xy[4 * e + 1] = poly.y(i); xy[4 * e + 2] = poly.x(j); xy[4 * e + 3] = poly.y(j)
                tag[e] = t
                e++
            }
        }
        return Edges(xy.copyOf(e * 4), tag.copyOf(e))
    }

    /**
     * Snap rounding (Hobby): hot pixels = every input vertex and every grid-rounded proper
     * crossing point; each segment is then routed through the center of every hot pixel its
     * path touches, in order along the segment. One pass, no iteration: afterwards two
     * fragments share at most an endpoint or coincide entirely.
     *
     * (Replaced an iterate-until-stable split that cycled on offset sleeves.)
     */
    fun split(edges: Edges): Edges {
        val n = edges.size
        val hot = HashSet<Long>()
        val hotList = ArrayList<IntPoint>()
        fun addHot(p: IntPoint): Boolean { return if (hot.add(pack(p.x, p.y))) { hotList.add(p); true } else false }
        for (i in 0 until n) { addHot(edges.p(i)); addHot(edges.q(i)) }
        for (c in properCrossings(edges)) addHot(c)
        // Iterated snap rounding: single-pass SR can leave two fragments crossing at a pixel
        // CORNER (found on the rig base: 1 µm jaggies in both inputs, a missing piece,
        // an open chain). Any crossing left among the fragments becomes a hot pixel and the
        // ORIGINAL segments are re-routed, until the fragments are crossing-free.
        var frags = route(edges, hotList)
        repeat(16) {
            val crossings = properCrossings(frags)
            if (crossings.isEmpty()) return frags
            for (c in crossings) addHot(c)
            frags = route(frags, hotList)          // re-route the FRAGMENTS (iterated snap rounding)
        }
        error("iterated snap rounding did not converge in 16 rounds")
    }

    /** Grid-rounded crossing points of all properly crossing segment pairs (x-sweep). */
    private fun properCrossings(edges: Edges): List<IntPoint> {
        val n = edges.size
        val out = ArrayList<IntPoint>()
        val order = sweepOrder(edges)
        val active = ArrayList<Int>()
        for (i in order) {
            val minX = minOf(edges.x0(i), edges.x1(i))
            active.removeAll { maxOf(edges.x0(it), edges.x1(it)) < minX }
            val a = edges.p(i); val b = edges.q(i)
            for (j in active) {
                val c = edges.p(j); val d = edges.q(j)
                if (Segments.properCrossing(a, b, c, d)) out.add(Segments.lineIntersection(a, b, c, d))
            }
            active.add(i)
        }
        return out
    }

    /** Routes every segment through the center of each hot pixel it touches, in order along it. */
    private fun route(edges: Edges, hotList: List<IntPoint>): Edges {
        val n = edges.size
        // hot pixels bucketed in a uniform grid (cell ~ mean segment extent) for local lookup
        var gx0 = Long.MAX_VALUE; var gy0 = Long.MAX_VALUE; var gx1 = Long.MIN_VALUE; var gy1 = Long.MIN_VALUE
        for (h in hotList) { gx0 = minOf(gx0, h.x); gy0 = minOf(gy0, h.y); gx1 = maxOf(gx1, h.x); gy1 = maxOf(gy1, h.y) }
        var ext = 0L
        for (i in 0 until n) ext += Math.abs(edges.x1(i) - edges.x0(i)) + Math.abs(edges.y1(i) - edges.y0(i))
        val cell = Math.max(64L, if (n > 0) ext / n else 64L)
        val gw = ((gx1 - gx0) / cell + 1).toInt().coerceAtLeast(1); val gh = ((gy1 - gy0) / cell + 1).toInt().coerceAtLeast(1)
        val grid = HashMap<Long, MutableList<IntPoint>>()
        for (h in hotList) grid.getOrPut(((h.x - gx0) / cell) * 1_000_003L + (h.y - gy0) / cell) { ArrayList(2) }.add(h)
        val outXy = LongList(n * 4); val outTag = IntList(n)
        val through = ArrayList<IntPoint>()
        for (i in 0 until n) {
            val a = edges.p(i); val b = edges.q(i)
            through.clear()
            val cx0 = ((minOf(a.x, b.x) - 1 - gx0) / cell).coerceAtLeast(0); val cx1 = ((maxOf(a.x, b.x) + 1 - gx0) / cell).coerceAtMost(gw.toLong() - 1)
            val cy0 = ((minOf(a.y, b.y) - 1 - gy0) / cell).coerceAtLeast(0); val cy1 = ((maxOf(a.y, b.y) + 1 - gy0) / cell).coerceAtMost(gh.toLong() - 1)
            for (cx in cx0..cx1) for (cy in cy0..cy1) {
                val bucket = grid[cx * 1_000_003L + cy] ?: continue
                for (h in bucket) if (segmentTouchesPixel(a, b, h)) through.add(h)
            }
            val dir = b - a
            through.sortBy { (it - a) dot dir }
            var prev: IntPoint? = null
            for (h in through) {
                if (prev != null && h != prev) {
                    outXy.add(prev.x, prev.y); outXy.add(h.x, h.y); outTag.add(edges.tag[i])
                }
                prev = h
            }
        }
        return Edges(outXy.toLongArray(), outTag.toIntArray())
    }

    private fun pack(x: Long, y: Long) = (x shl 32) xor (y and 0xFFFF_FFFFL)

    /**
     * The edges by their smaller x, ascending, ties by index (what `sortedBy` gave, without boxing an Int and a Long per
     * edge): one packed long each, the min x shifted above 24 bits of index, sorted as primitives. Coordinates are µm
     * within ±2^39 and an arrangement holds under 2^24 edges; beyond either, the boxed sort.
     */
    private fun sweepOrder(edges: Edges): IntArray {
        val n = edges.size
        if (n >= 1 shl 24) return (0 until n).sortedBy { minOf(edges.x0(it), edges.x1(it)) }.toIntArray()
        val keys = LongArray(n)
        for (i in 0 until n) {
            val mx = minOf(edges.x0(i), edges.x1(i))
            if (mx >= 1L shl 39 || mx < -(1L shl 39)) return (0 until n).sortedBy { minOf(edges.x0(it), edges.x1(it)) }.toIntArray()
            keys[i] = (mx shl 24) or i.toLong()
        }
        java.util.Arrays.sort(keys)
        return IntArray(n) { (keys[it] and 0xFF_FFFFL).toInt() }
    }

    private fun lowerBound(a: LongArray, v: Long): Int {
        var lo = 0; var hi = a.size
        while (lo < hi) { val m = (lo + hi) ushr 1; if (a[m] < v) lo = m + 1 else hi = m }
        return lo
    }

    /**
     * Exact: does segment a-b touch the HALF-OPEN unit pixel centered at h, [h-1/2, h+1/2)²?
     * Half-open so that a line through a pixel corner belongs to exactly one pixel (with closed
     * pixels it touched up to four and iterated snap rounding could not settle — rig base).
     * Doubled coordinates make the corners integers; the open upper edges are the
     * closed box shrunk by an infinitesimal δ, resolved symbolically.
     */
    internal fun segmentTouchesPixel(a: IntPoint, b: IntPoint, h: IntPoint): Boolean {
        val ax = 2 * a.x; val ay = 2 * a.y; val bx = 2 * b.x; val by = 2 * b.y
        val x0 = 2 * h.x - 1; val x1 = 2 * h.x + 1; val y0 = 2 * h.y - 1; val y1 = 2 * h.y + 1
        // bounding boxes: [x0, x1-δ] x [y0, y1-δ]
        if (maxOf(ax, bx) < x0 || minOf(ax, bx) >= x1 || maxOf(ay, by) < y0 || minOf(ay, by) >= y1) return false
        val ex = bx - ax; val ey = by - ay
        // side of corner (px - dxδ, py - dyδ): exact value, ties broken by the δ derivative
        fun side(px: Long, py: Long, dx: Int, dy: Int): Int {
            val c = ex * (py - ay) - ey * (px - ax)
            if (c != 0L) return if (c > 0) 1 else -1
            val d = -dy * ex + dx * ey        // d/dδ of cross(e, (px - dxδ, py - dyδ) - a)
            return if (d > 0) 1 else if (d < 0) -1 else 0
        }
        val s1 = side(x0, y0, 0, 0); val s2 = side(x1, y0, 1, 0); val s3 = side(x1, y1, 1, 1); val s4 = side(x0, y1, 0, 1)
        return !((s1 > 0 && s2 > 0 && s3 > 0 && s4 > 0) || (s1 < 0 && s2 < 0 && s3 < 0 && s4 < 0))
    }

    /**
     * One pass: for every edge, collect the points where other edges cross or touch it
     * (interior points only), sort them along the edge, and cut. Sweep on x to avoid
     * the full n² pair test.
     */
    private fun splitOnce(edges: Edges): Pair<Edges, Boolean> {
        val n = edges.size
        // per-edge cut points, packed as (x, y) pairs
        val cuts = arrayOfNulls<MutableList<Long>>(n)
        fun addCut(i: Int, p: IntPoint) {
            val e = edges
            if ((p.x == e.x0(i) && p.y == e.y0(i)) || (p.x == e.x1(i) && p.y == e.y1(i))) return
            val l = cuts[i] ?: ArrayList<Long>().also { cuts[i] = it }
            l.add(p.x); l.add(p.y)
        }
        // sweep: sort by min x, active list pruned by max x
        val order = (0 until n).sortedBy { minOf(edges.x0(it), edges.x1(it)) }
        val active = ArrayList<Int>()
        var changed = false
        for (i in order) {
            val minX = minOf(edges.x0(i), edges.x1(i))
            active.removeAll { maxOf(edges.x0(it), edges.x1(it)) < minX }
            val a = edges.p(i); val b = edges.q(i)
            for (j in active) {
                val c = edges.p(j); val d = edges.q(j)
                if (!Segments.intersects(a, b, c, d)) continue
                val o1 = orientation(a, b, c); val o2 = orientation(a, b, d)
                val o3 = orientation(c, d, a); val o4 = orientation(c, d, b)
                if (o1 == 0 && o2 == 0) {
                    // collinear overlap: each endpoint of one lying strictly inside the other cuts it
                    if (Segments.onSegment(a, b, c)) addCut(i, c)
                    if (Segments.onSegment(a, b, d)) addCut(i, d)
                    if (Segments.onSegment(c, d, a)) addCut(j, a)
                    if (Segments.onSegment(c, d, b)) addCut(j, b)
                } else if (o1 * o2 < 0 && o3 * o4 < 0) {
                    val p = Segments.lineIntersection(a, b, c, d)
                    addCut(i, p); addCut(j, p)
                } else {
                    // touching: an endpoint of one lies on the other's interior
                    if (o1 == 0) addCut(i, c)
                    if (o2 == 0) addCut(i, d)
                    if (o3 == 0) addCut(j, a)
                    if (o4 == 0) addCut(j, b)
                }
            }
            active.add(i)
        }
        // rebuild
        val outXy = LongList(edges.xy.size); val outTag = IntList(n)
        for (i in 0 until n) {
            val l = cuts[i]
            val a = edges.p(i); val b = edges.q(i)
            if (l == null) { outXy.add(a.x, a.y); outXy.add(b.x, b.y); outTag.add(edges.tag[i]); continue }
            changed = true
            val pts = ArrayList<IntPoint>(l.size / 2 + 2)
            for (k in 0 until l.size / 2) pts.add(IntPoint(l[2 * k], l[2 * k + 1]))
            // order along the edge by projection on the direction (exact: dot with (b-a))
            val dir = b - a
            pts.sortBy { (it - a) dot dir }
            var prev = a
            for (p in pts) {
                if (p == prev) continue
                outXy.add(prev.x, prev.y); outXy.add(p.x, p.y); outTag.add(edges.tag[i])
                prev = p
            }
            if (prev != b) { outXy.add(prev.x, prev.y); outXy.add(b.x, b.y); outTag.add(edges.tag[i]) }
        }
        return Edges(outXy.toLongArray(), outTag.toIntArray()) to changed
    }
}
