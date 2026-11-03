package plymeld.geom

/** A polyline cut by a region (for wave overhangs: a wall loop's runs over air give way to the beads). */
object Clip {
    /**
     * The parts of the polyline [xy] (x y ... µm; a loop when [closed]) that lie OUTSIDE [region] (its outer loops less
     * their holes), each an open run, cut where the polyline crosses the region's edges. Returns the list holding [xy]
     * itself when none of it is inside; a loop whose runs meet at its first vertex comes back joined. A run inside is
     * judged by its middle, so a polyline vertex lying on the region's edge is read as a crossing, never a stray piece.
     */
    fun outside(xy: LongArray, closed: Boolean, region: List<Polygon>): List<LongArray> {
        if (region.isEmpty() || xy.size < 4) return listOf(xy)
        val groups = Polygons.group(region)
        fun inside(x: Long, y: Long) = groups.any { (o, hs) -> Polygons.contains(o, x, y) && hs.none { Polygons.contains(it, x, y) } }
        val edges = region.flatMap { p -> (0 until p.size).map { e -> IntPoint(p.x(e), p.y(e)) to IntPoint(p.x((e + 1) % p.size), p.y((e + 1) % p.size)) } }
        val n = xy.size / 2
        val m = if (closed) n else n - 1
        // the polyline with a vertex added at every crossing of the region's edges, in order along it
        val pts = ArrayList<IntPoint>(n + 8)
        for (k in 0 until m) {
            val a = IntPoint(xy[2 * k], xy[2 * k + 1]); val b = IntPoint(xy[2 * ((k + 1) % n)], xy[2 * ((k + 1) % n) + 1])
            pts.add(a)
            val dir = b - a
            val cuts = edges.filter { (c, d) -> Segments.properCrossing(a, b, c, d) }.map { (c, d) -> Segments.lineIntersection(a, b, c, d) }
                .distinct().sortedBy { (it - a) dot dir }
            pts.addAll(cuts)
        }
        if (!closed) pts.add(IntPoint(xy[2 * (n - 1)], xy[2 * (n - 1) + 1]))
        val total = if (closed) pts.size else pts.size - 1
        val out = ArrayList<LongList>()
        var run: LongList? = null
        var anyInside = false
        for (k in 0 until total) {
            val a = pts[k]; val b = pts[(k + 1) % pts.size]
            if (a == b) continue
            if (!inside((a.x + b.x) / 2, (a.y + b.y) / 2)) {
                if (run == null) { run = LongList(); run.add(a.x, a.y) }
                run.add(b.x, b.y)
            } else { anyInside = true; run?.let { out.add(it) }; run = null }
        }
        run?.let { out.add(it) }
        if (!anyInside) return listOf(xy)
        if (out.isEmpty()) return emptyList()
        // a loop: the run ending at the start vertex and the one beginning there are one run
        if (closed && out.size >= 2) {
            val first = out.first(); val last = out.last()
            if (last[last.size - 2] == first[0] && last[last.size - 1] == first[1]) {
                val joined = LongList(); for (i in 0 until last.size) joined.add(last[i]); for (i in 2 until first.size) joined.add(first[i])
                out.removeAt(out.size - 1); out[0] = joined
            }
        }
        return out.map { it.toLongArray() }
    }
}
