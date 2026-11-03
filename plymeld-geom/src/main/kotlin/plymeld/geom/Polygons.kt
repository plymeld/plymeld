package plymeld.geom

/** Helpers over flat polygon sets (outers CCW, holes CW, as the boolean core outputs them). */
object Polygons {
    /**
     * Groups a normalized set into (outer, holes-inside-it). Each hole goes to the smallest
     * outer containing one of its vertices (exact winding test on that outer's edges).
     */
    fun group(set: List<Polygon>): List<Pair<Polygon, List<Polygon>>> {
        val outers = set.filter { it.isCcw() }.sortedBy { Math.abs(it.signedArea2()) }
        val holes = set.filter { !it.isCcw() }
        val buckets = outers.associateWith { ArrayList<Polygon>() }
        for (h in holes) {
            val px = h.x(0); val py = h.y(0)
            val owner = outers.firstOrNull { contains(it, px, py) } ?: continue   // orphan hole: dropped
            buckets.getValue(owner).add(h)
        }
        return outers.map { it to buckets.getValue(it) }
    }

    /** Exact point-in-polygon (crossing test, half-open on y); points on the boundary count as inside. */
    fun contains(p: Polygon, x: Long, y: Long): Boolean {
        var inside = false
        val n = p.size
        for (i in 0 until n) {
            val j = (i + 1) % n
            val xi = p.x(i); val yi = p.y(i); val xj = p.x(j); val yj = p.y(j)
            // on-boundary: collinear and within the segment's box
            if (orientation(IntPoint(xi, yi), IntPoint(xj, yj), IntPoint(x, y)) == 0 &&
                x >= minOf(xi, xj) && x <= maxOf(xi, xj) && y >= minOf(yi, yj) && y <= maxOf(yi, yj)) return true
            if ((yi > y) != (yj > y)) {
                // x-coordinate of the crossing vs x, exactly: compare (x - xi)*(yj - yi) with (xj - xi)*(y - yi)
                val lhs = (x - xi) * (yj - yi); val rhs = (xj - xi) * (y - yi)
                if (if (yj > yi) lhs < rhs else lhs > rhs) inside = !inside
            }
        }
        return inside
    }
}
