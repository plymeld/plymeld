package plymeld.geom

/** Helpers over flat polygon sets (outers CCW, holes CW, as the boolean core outputs them). */
object Polygons {
    /**
     * Groups a normalized set into (outer, holes-inside-it). Each hole goes to the smallest outer that holds it: an outer
     * at least as large, inside which the hole lies at its first vertex off that outer's boundary (exact test on the
     * outer's edges). A vertex on an outer's boundary says nothing about which side of it the hole lies on (polygons of a
     * set may touch): decided by the first vertex alone, a hole starting at a corner of a smaller outer it merely touches
     * (a sliver a micron wide beside it) would go to that outer, and the outer that does hold it would be left without it.
     */
    fun group(set: List<Polygon>): List<Pair<Polygon, List<Polygon>>> {
        val sized = set.filter { it.isCcw() }.map { it to Math.abs(it.signedArea2()) }.sortedBy { it.second }
        val outers = sized.map { it.first }
        val holes = set.filter { !it.isCcw() }
        val buckets = outers.associateWith { ArrayList<Polygon>() }
        for (h in holes) {
            val area = Math.abs(h.signedArea2())
            val owner = sized.firstOrNull { (o, a) -> a >= area && holds(o, h) }?.first ?: continue   // orphan hole: dropped
            buckets.getValue(owner).add(h)
        }
        return outers.map { it to buckets.getValue(it) }
    }

    /** Whether [hole] lies inside [outer]: at its first vertex off [outer]'s boundary; a hole wholly on that boundary is in it. */
    private fun holds(outer: Polygon, hole: Polygon): Boolean {
        for (i in 0 until hole.size) {
            val w = side(outer, hole.x(i), hole.y(i))
            if (w != 0) return w > 0
        }
        return true
    }

    /** Exact point-in-polygon (crossing test, half-open on y); points on the boundary count as inside. */
    fun contains(p: Polygon, x: Long, y: Long): Boolean = side(p, x, y) >= 0

    /** Where (x, y) lies against [p], exactly: 1 inside, 0 on its boundary, -1 outside (crossing test, half-open on y). */
    private fun side(p: Polygon, x: Long, y: Long): Int {
        var inside = false
        val n = p.size
        for (i in 0 until n) {
            val j = (i + 1) % n
            val xi = p.x(i); val yi = p.y(i); val xj = p.x(j); val yj = p.y(j)
            // on-boundary: collinear and within the segment's box
            if (orientation(IntPoint(xi, yi), IntPoint(xj, yj), IntPoint(x, y)) == 0 &&
                x >= minOf(xi, xj) && x <= maxOf(xi, xj) && y >= minOf(yi, yj) && y <= maxOf(yi, yj)) return 0
            if ((yi > y) != (yj > y)) {
                // x-coordinate of the crossing vs x, exactly: compare (x - xi)*(yj - yi) with (xj - xi)*(y - yi)
                val lhs = (x - xi) * (yj - yi); val rhs = (xj - xi) * (y - yi)
                if (if (yj > yi) lhs < rhs else lhs > rhs) inside = !inside
            }
        }
        return if (inside) 1 else -1
    }
}
