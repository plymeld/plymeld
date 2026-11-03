package plymeld.gcode

/**
 * Arc fitting: greedily replaces runs of polyline vertices that lie on one circle (within
 * `tolMm`) with a single arc. plymeld's own method: grow a run from a start vertex, fit the
 * circle through its first, middle, and last points, accept while every vertex and every chord
 * midpoint of the run stays within tolerance of that circle, the run turns consistently one way,
 * and the radius stays within sane bounds.
 *
 * Output: a list of moves; each is a line to (x, y) or an arc to (x, y) around (cx, cy), cw or ccw.
 */
object ArcFit {
    sealed interface Move { val x: Double; val y: Double }
    data class Line(override val x: Double, override val y: Double) : Move
    data class Arc(override val x: Double, override val y: Double, val cx: Double, val cy: Double, val cw: Boolean, val length: Double) : Move

    fun fit(pts: DoubleArray, tolMm: Double, minPoints: Int = 4, maxRadiusMm: Double = 1000.0, minRadiusMm: Double = 0.5): List<Move> {
        val n = pts.size / 2
        val out = ArrayList<Move>()
        var i = 0
        while (i < n - 1) {
            var best = -1; var bestArc: Arc? = null
            var j = i + minPoints - 1
            while (j < n) {
                val arc = tryArc(pts, i, j, tolMm, maxRadiusMm, minRadiusMm) ?: break
                best = j; bestArc = arc
                j++
            }
            if (bestArc != null) { out.add(bestArc); i = best }
            else { out.add(Line(pts[2 * (i + 1)], pts[2 * (i + 1) + 1])); i++ }
        }
        return out
    }

    private fun tryArc(p: DoubleArray, a: Int, b: Int, tol: Double, maxR: Double, minR: Double): Arc? {
        val m = (a + b) / 2
        val x1 = p[2 * a]; val y1 = p[2 * a + 1]; val x2 = p[2 * m]; val y2 = p[2 * m + 1]; val x3 = p[2 * b]; val y3 = p[2 * b + 1]
        val d = 2 * (x1 * (y2 - y3) + x2 * (y3 - y1) + x3 * (y1 - y2))
        if (Math.abs(d) < 1e-12) return null
        val s1 = x1 * x1 + y1 * y1; val s2 = x2 * x2 + y2 * y2; val s3 = x3 * x3 + y3 * y3
        val cx = (s1 * (y2 - y3) + s2 * (y3 - y1) + s3 * (y1 - y2)) / d
        val cy = (s1 * (x3 - x2) + s2 * (x1 - x3) + s3 * (x2 - x1)) / d
        val r = Math.hypot(x1 - cx, y1 - cy)
        if (r > maxR || r < minR) return null
        // consistent turning direction and every vertex / chord midpoint within tolerance
        var sign = 0
        var sweep = 0.0
        for (k in a until b) {
            val ax = p[2 * k]; val ay = p[2 * k + 1]; val bx = p[2 * (k + 1)]; val by = p[2 * (k + 1) + 1]
            if (Math.abs(Math.hypot(bx - cx, by - cy) - r) > tol) return null
            val mx = (ax + bx) / 2; val my = (ay + by) / 2
            if (Math.abs(Math.hypot(mx - cx, my - cy) - r) > tol) return null
            val cross = (ax - cx) * (by - cy) - (ay - cy) * (bx - cx)
            val sg = if (cross > 0) 1 else if (cross < 0) -1 else 0
            if (sg != 0) { if (sign == 0) sign = sg else if (sg != sign) return null }
            val dot = (ax - cx) * (bx - cx) + (ay - cy) * (by - cy)
            sweep += Math.atan2(Math.abs(cross), dot)
        }
        if (sign == 0 || sweep >= 2 * Math.PI - 1e-6) return null   // no full circles in one move
        return Arc(x3, y3, cx, cy, cw = sign < 0, length = sweep * r)
    }
}
