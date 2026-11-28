package plymeld.core

import plymeld.geom.Polygon

/**
 * Two experimental rules for where an open wall line (a line where no loop fits: a thin area's center line) is entered
 * and when it is laid, each behind a setting of its own and off unless set:
 * - plan.layer.walls.open_start = hidden_end: a line with one end on an outside corner of the outline the viewer sees and
 *   the other on none is entered at the other ([seenCorners], [hiddenEnd]); Ordering never enters it at the seen one.
 * - plan.layer.walls.thin_skin_after_outer: an open inner wall line lying between the two sides of its contour's outer wall
 *   (a thin skin three lines thick: the outer wall's two passes and this line) is laid right after the outer wall, entered
 *   from its end nearer where the outer wall ended ([thinSkin]; the order is the Planner's).
 */
object WallLineEnds {
    /**
     * The outside corners of the layer's [outline] the viewer sees: turns of at least [cornerDeg] across [spanUm] (a corner
     * as the corner seam reads one, [Seams.outsideCorners]) that the visibility map [vis] does not count as turned away from
     * the viewer (plan.view_from: [plymeld.core.decide.Visibility.awayDot] of the way the corner faces is not above 0; looked
     * at from all around or from above, no corner is). None without a corner angle.
     */
    fun seenCorners(outline: List<Polygon>, cornerDeg: Double?, spanUm: Double, vis: plymeld.core.decide.Visibility): List<Seams.Corner> {
        if (cornerDeg == null) return emptyList()
        return outline.flatMap { p -> if (p.size < 3) emptyList() else Seams.outsideCorners(p, cornerDeg, spanUm).filter { c -> vis.awayDot(c.nx, c.ny, 0.0) <= 0.0 } }
    }

    /**
     * The end open path [p] must be entered at ([Ordering.order]'s entry): 0 = its first point, 1 = its last, when only the
     * other end lies within [reachUm] of one of the seen [corners]; -1 (either) when both ends do, or neither, or [p] is closed.
     */
    fun hiddenEnd(p: ToolPath, corners: List<Seams.Corner>, reachUm: Double): Int {
        if (p.closed || corners.isEmpty()) return -1
        val n = p.xy.size
        fun onSeen(x: Long, y: Long) = corners.any { c -> Math.hypot((c.x - x).toDouble(), (c.y - y).toDouble()) <= reachUm }
        val first = onSeen(p.xy[0], p.xy[1]); val last = onSeen(p.xy[n - 2], p.xy[n - 1])
        return if (first == last) -1 else if (first) 1 else 0
    }

    /**
     * Whether open inner wall line [p] is a thin skin's inner line: at each of its points (its vertices and the middles of its
     * segments), the contour's [outer] wall (a closed loop) lies within [reachUm] on both sides, across the line (along its
     * normal there, both ways). A line running into a wider area has, somewhere, no outer wall that close on one side.
     */
    fun thinSkin(p: ToolPath, outer: ToolPath, reachUm: Double): Boolean {
        if (p.closed || !outer.closed) return false
        val m = p.xy.size / 2
        if (m < 2) return false
        fun x(k: Int) = p.xy[2 * k].toDouble()
        fun y(k: Int) = p.xy[2 * k + 1].toDouble()
        /** The nearest crossing of the outer loop along (dx, dy) from (px, py), at most [reachUm] out; infinity when none. */
        fun hit(px: Double, py: Double, dx: Double, dy: Double): Double {
            val q = outer.xy; val k = q.size / 2
            var best = Double.POSITIVE_INFINITY
            for (e in 0 until k) {
                val ax = q[2 * e].toDouble(); val ay = q[2 * e + 1].toDouble()
                val bx = q[2 * ((e + 1) % k)].toDouble(); val by = q[2 * ((e + 1) % k) + 1].toDouble()
                val ex = bx - ax; val ey = by - ay
                val den = dx * ey - dy * ex
                if (Math.abs(den) < 1e-12) continue
                val t = ((ax - px) * ey - (ay - py) * ex) / den
                val u = ((ax - px) * dy - (ay - py) * dx) / den
                if (t > 0 && u >= 0 && u <= 1 && t < best) best = t
            }
            return best
        }
        fun sandwiched(px: Double, py: Double, tx: Double, ty: Double): Boolean {
            val l = Math.hypot(tx, ty)
            if (l == 0.0) return true
            val nx = -ty / l; val ny = tx / l
            return hit(px, py, nx, ny) <= reachUm && hit(px, py, -nx, -ny) <= reachUm
        }
        for (k in 0 until m) {
            val a = maxOf(0, k - 1); val b = minOf(m - 1, k + 1)
            if (!sandwiched(x(k), y(k), x(b) - x(a), y(b) - y(a))) return false
            if (k + 1 < m && !sandwiched((x(k) + x(k + 1)) / 2, (y(k) + y(k + 1)) / 2, x(k + 1) - x(k), y(k + 1) - y(k))) return false
        }
        return true
    }
}
