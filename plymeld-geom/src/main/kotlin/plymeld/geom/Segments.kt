package plymeld.geom

/**
 * Exact segment predicates on the integer grid, and the one rounded operation:
 * the intersection point of two segments.
 *
 * Coordinates must satisfy |c| <= 2^30 (about 1 km at 1 µm) so that differences stay
 * below 2^31 and every cross product below 2^62. (±2^31 overflowed in the fuzz test.)
 */
object Segments {

    /** Exact: do closed segments (a,b) and (c,d) share at least one point? */
    fun intersects(a: IntPoint, b: IntPoint, c: IntPoint, d: IntPoint): Boolean {
        val o1 = orientation(a, b, c); val o2 = orientation(a, b, d)
        val o3 = orientation(c, d, a); val o4 = orientation(c, d, b)
        if (o1 != o2 && o3 != o4 && o1 != 0 && o2 != 0 && o3 != 0 && o4 != 0) return true
        // collinear / touching cases
        if (o1 == 0 && onSegment(a, b, c)) return true
        if (o2 == 0 && onSegment(a, b, d)) return true
        if (o3 == 0 && onSegment(c, d, a)) return true
        if (o4 == 0 && onSegment(c, d, b)) return true
        return o1 != o2 && o3 != o4
    }

    /** Exact: is p (already known collinear with a-b) within the segment's bounding box? */
    fun onSegment(a: IntPoint, b: IntPoint, p: IntPoint): Boolean =
        p.x >= minOf(a.x, b.x) && p.x <= maxOf(a.x, b.x) && p.y >= minOf(a.y, b.y) && p.y <= maxOf(a.y, b.y)

    /**
     * Proper crossing: the segments cross at a single interior point of both
     * (no endpoint touching, not collinear). Exact.
     */
    fun properCrossing(a: IntPoint, b: IntPoint, c: IntPoint, d: IntPoint): Boolean {
        val o1 = orientation(a, b, c); val o2 = orientation(a, b, d)
        val o3 = orientation(c, d, a); val o4 = orientation(c, d, b)
        return o1 * o2 < 0 && o3 * o4 < 0
    }

    /**
     * Intersection point of the two supporting LINES, rounded to the grid (half away
     * from zero). Caller guarantees the lines are not parallel (cross != 0). Computed
     * with exact 128-bit-free arithmetic by splitting the division: the numerators can
     * exceed 63 bits for far-apart coordinates, so the parameter t is formed from
     * differences, which keeps magnitudes at ~2^62 for |c| < 2^31.
     */
    fun lineIntersection(a: IntPoint, b: IntPoint, c: IntPoint, d: IntPoint): IntPoint {
        val r = b - a
        val s = d - c
        val denom = r cross s
        require(denom != 0L) { "parallel lines" }
        val qp = c - a
        val tNum = qp cross s               // t = tNum / denom along a->b
        // point = a + r * t ; do the multiply-then-divide per coordinate with rounding
        val x = a.x + mulDivRound(r.x, tNum, denom)
        val y = a.y + mulDivRound(r.y, tNum, denom)
        return IntPoint(x, y)
    }

    /**
     * floor(m * n / d + 1/2) — rounding HALF UP, i.e. to the half-open pixel [v-1/2, v+1/2) that
     * contains the exact point. (Half away from zero disagreed with the pixels for negative
     * coordinates and stalled iterated snap rounding — rig base.)
     */
    private fun mulDivRound(m: Long, n: Long, d: Long): Long {
        val hi = Math.multiplyHigh(m, n)
        val lo = m * n
        if ((hi == 0L && lo >= 0) || (hi == -1L && lo < 0)) return roundHalfUp(lo, d)
        // exact slow path via BigInteger (only for very large coordinates)
        var p = java.math.BigInteger.valueOf(m).multiply(java.math.BigInteger.valueOf(n))
        var dd = java.math.BigInteger.valueOf(d)
        if (dd.signum() < 0) { p = p.negate(); dd = dd.negate() }
        val two = java.math.BigInteger.TWO
        return p.multiply(two).add(dd).divide(dd.multiply(two)).let { q ->
            // BigInteger.divide truncates toward zero; correct to floor for negatives
            val prod = q.multiply(dd.multiply(two))
            if (prod > p.multiply(two).add(dd)) q.subtract(java.math.BigInteger.ONE) else q
        }.toLong()
    }

    /** floor(num / den + 1/2), exact. */
    fun roundHalfUp(num: Long, den: Long): Long {
        var n = num; var d = den
        if (d < 0) { n = -n; d = -d }
        val q = Math.floorDiv(n, d)
        val r = n - q * d                 // 0 <= r < d
        return if (r >= d - r) q + 1 else q
    }

    fun divRound(num: Long, den: Long): Long {
        val q = num / den
        val r = num % den
        return if (Math.abs(r) * 2 >= Math.abs(den)) q + (if ((num < 0) != (den < 0)) -1 else 1) else q
    }
}
