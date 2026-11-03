package plymeld.core

import plymeld.geom.Polygon
import plymeld.geom.Polygons

/**
 * How far a wall bead hangs over air: for each point of a wall centerline on layer i, the
 * fraction of the bead width (0..1) that lies outside the layer below's outline. Measured as
 * (signed distance outside the lower outline + w/2) / w, clamped — 0 = fully supported,
 * 1 = entirely over air. plymeld's definition, used to grade the wall speed over an overhang.
 */
object Overhangs {
    fun fraction(px: Long, py: Long, below: List<Polygon>, belowGroups: List<Pair<Polygon, List<Polygon>>>, widthUm: Long): Double {
        val inside = belowGroups.any { (o, hs) -> Polygons.contains(o, px, py) && hs.none { Polygons.contains(it, px, py) } }
        var best = Double.POSITIVE_INFINITY
        for (p in below) for (i in 0 until p.size) {
            val j = (i + 1) % p.size
            val ax = p.x(i).toDouble(); val ay = p.y(i).toDouble(); val bx = p.x(j).toDouble(); val by = p.y(j).toDouble()
            val vx = bx - ax; val vy = by - ay; val l2 = vx * vx + vy * vy
            val t = if (l2 == 0.0) 0.0 else (((px - ax) * vx + (py - ay) * vy) / l2).coerceIn(0.0, 1.0)
            val d = Math.hypot(px - ax - t * vx, py - ay - t * vy)
            if (d < best) best = d
        }
        val signed = if (inside) -best else best
        return ((signed + widthUm / 2.0) / widthUm).coerceIn(0.0, 1.0)
    }

    /**
     * Overhang share per loop edge (edge k = vertex k -> k+1), at its midpoint, in thousandths;
     * null when no edge passes [plymeld.program.OverhangSpeed.from] for this bead (the wall is never
     * slowed; the writer grades the speed, see OverhangSpeed).
     */
    fun edgeShares(loop: LongArray, below: List<Polygon>, widthUm: Long, heightUm: Long): IntArray? {
        val groups = Polygons.group(below)
        val m = loop.size / 2
        val out = IntArray(m) { k ->
            val j = (k + 1) % m
            Math.round(1000 * fraction((loop[2 * k] + loop[2 * j]) / 2, (loop[2 * k + 1] + loop[2 * j + 1]) / 2, below, groups, widthUm)).toInt()
        }
        return out.takeIf { s -> s.any { it > 1000 * plymeld.program.OverhangSpeed.from(widthUm, heightUm) } }
    }

    /**
     * For a seam: which vertices of each wall loop of one layer lie on an edge of the layer that hangs
     * over the layer below. The edge is the layer's outer wall (`loops[0]`, line width [outerWidthUm]). "Overhangs" is the
     * overhang slow-down's own measure: the outer wall's share over air ([fraction]) above
     * [plymeld.program.OverhangSpeed.from] for that line, where the slow-down starts. An outer wall vertex: its own share.
     * An inner loop's vertex, d from the outer wall: overhanging when the outer wall overhangs at its nearest point on any
     * outer wall edge within d + [outerWidthUm] / 2 of it (the outer bead it presses against), so an inner loop's corner
     * behind an overhanging end counts as on that end even where a held-up side lies as near (a helix island's
     * inner corner lay 0.42 mm from its overhanging end and 0.43 mm from its held-up side). [loops]: the layer's wall
     * loops, wall by wall. Null when no outer wall vertex on the layer overhangs (or there is no outer wall): then every
     * point is taken as held up.
     */
    fun seamOverhangs(loops: List<List<Polygon>>, below: List<Polygon>, outerWidthUm: Long, heightUm: Long): List<List<BooleanArray>>? {
        val outer = loops.firstOrNull() ?: return null
        if (outer.isEmpty() || below.isEmpty()) return null
        val groups = Polygons.group(below)
        val from = plymeld.program.OverhangSpeed.from(outerWidthUm, heightUm)
        fun over(x: Long, y: Long) = fraction(x, y, below, groups, outerWidthUm) > from
        val outerFlags = outer.map { p -> BooleanArray(p.size) { over(p.x(it), p.y(it)) } }
        if (outerFlags.none { f -> f.any { it } }) return null
        val edgesN = outer.sumOf { it.size }
        /** Whether the outer wall overhangs at its nearest point on any edge within (nearest distance + w/2) of (x, y). */
        fun nearestOver(x: Long, y: Long): Boolean {
            val d = DoubleArray(edgesN); val qx = LongArray(edgesN); val qy = LongArray(edgesN)
            var k = 0; var best = Double.POSITIVE_INFINITY
            for (p in outer) for (e in 0 until p.size) {
                val j = (e + 1) % p.size
                val ax = p.x(e).toDouble(); val ay = p.y(e).toDouble(); val vx = p.x(j) - ax; val vy = p.y(j) - ay
                val l2 = vx * vx + vy * vy
                val t = if (l2 == 0.0) 0.0 else (((x - ax) * vx + (y - ay) * vy) / l2).coerceIn(0.0, 1.0)
                qx[k] = Math.round(ax + t * vx); qy[k] = Math.round(ay + t * vy)
                d[k] = Math.hypot((x - qx[k]).toDouble(), (y - qy[k]).toDouble())
                best = minOf(best, d[k]); k++
            }
            val reach = best + outerWidthUm / 2.0
            return (0 until edgesN).any { d[it] <= reach && over(qx[it], qy[it]) }
        }
        return loops.mapIndexed { k, wall ->
            if (k == 0) outerFlags else wall.map { p -> BooleanArray(p.size) { nearestOver(p.x(it), p.y(it)) } }
        }
    }
}
