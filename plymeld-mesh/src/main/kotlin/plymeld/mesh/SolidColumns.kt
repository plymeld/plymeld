package plymeld.mesh

/**
 * The model seen as vertical columns (Z anti-aliasing): along the vertical line through
 * (x, y), where the solid is, as runs of Z from where the line enters the model to where it leaves it, over the UNION of
 * the parts (parts may overlap: a part's face inside another is not a surface of the model, and a run goes on through it).
 *
 * The line meets each triangle that is not vertical at most once. A point exactly on an edge two triangles share belongs to
 * one of them only, by a fixed rule on the edge's direction (the "top-left" rule of rasterisers), worked with the edge's
 * ends in a fixed order so both triangles compute the same value: the line is never counted twice or missed at a shared
 * edge or corner. A down-facing triangle is where the line enters the solid going up, an up-facing one where it leaves;
 * the depth (entries less exits) above zero is solid. Two runs closer than [joinMm] are one (two parts that touch, or
 * meet within that).
 */
class SolidColumns(parts: List<TriangleSoup>, private val joinMm: Double) {
    // per triangle: x0 y0 z0 x1 y1 z1 x2 y2 z2, corners turned counter-clockwise seen from above
    private val tris: DoubleArray
    // +1: the line enters the solid there going up (a down-facing triangle); -1: it leaves (up-facing)
    private val sign: IntArray
    private val bx0: Double; private val by0: Double; private val cell: Double; private val nx: Int; private val ny: Int
    private val buckets: Array<IntArray>

    init {
        require(joinMm >= 0) { "the join distance is not negative: $joinMm" }
        val t = ArrayList<Double>(); val sg = ArrayList<Int>()
        var minX = Double.POSITIVE_INFINITY; var minY = minX; var maxX = Double.NEGATIVE_INFINITY; var maxY = maxX
        for (p in parts) for (k in 0 until p.count) {
            val ax = p.x(k, 0).toDouble(); val ay = p.y(k, 0).toDouble(); val az = p.z(k, 0).toDouble()
            var bx = p.x(k, 1).toDouble(); var by = p.y(k, 1).toDouble(); var bz = p.z(k, 1).toDouble()
            var cx = p.x(k, 2).toDouble(); var cy = p.y(k, 2).toDouble(); var cz = p.z(k, 2).toDouble()
            val area2 = (bx - ax) * (cy - ay) - (by - ay) * (cx - ax)
            if (area2 == 0.0) continue                                  // vertical (or degenerate): the line runs along it
            // seen from above, counter-clockwise = the outward normal points up = the solid's top there: the line leaves it
            val s = if (area2 > 0) -1 else +1
            if (area2 < 0) { val tx = bx; bx = cx; cx = tx; val ty = by; by = cy; cy = ty; val tz = bz; bz = cz; cz = tz }
            t += ax; t += ay; t += az; t += bx; t += by; t += bz; t += cx; t += cy; t += cz
            sg += s
            minX = minOf(minX, ax, bx, cx); maxX = maxOf(maxX, ax, bx, cx); minY = minOf(minY, ay, by, cy); maxY = maxOf(maxY, ay, by, cy)
        }
        tris = t.toDoubleArray(); sign = sg.toIntArray()
        val count = sign.size
        if (count == 0) { minX = 0.0; minY = 0.0; maxX = 1.0; maxY = 1.0 }
        // a few triangles per bucket: the cell from the footprint and the triangle count (speed only, never a result)
        val w = maxOf(maxX - minX, 1e-6); val h = maxOf(maxY - minY, 1e-6)
        cell = maxOf(Math.sqrt(w * h / maxOf(1, count)) * 2.0, 1e-3)
        bx0 = minX; by0 = minY
        nx = maxOf(1, Math.ceil(w / cell).toInt() + 1); ny = maxOf(1, Math.ceil(h / cell).toInt() + 1)
        val lists = Array(nx * ny) { ArrayList<Int>() }
        for (k in 0 until count) {
            val o = k * 9
            val i0 = ((minOf(tris[o], tris[o + 3], tris[o + 6]) - bx0) / cell).toInt().coerceIn(0, nx - 1)
            val i1 = ((maxOf(tris[o], tris[o + 3], tris[o + 6]) - bx0) / cell).toInt().coerceIn(0, nx - 1)
            val j0 = ((minOf(tris[o + 1], tris[o + 4], tris[o + 7]) - by0) / cell).toInt().coerceIn(0, ny - 1)
            val j1 = ((maxOf(tris[o + 1], tris[o + 4], tris[o + 7]) - by0) / cell).toInt().coerceIn(0, ny - 1)
            for (j in j0..j1) for (i in i0..i1) lists[j * nx + i].add(k)
        }
        buckets = Array(nx * ny) { lists[it].toIntArray() }
    }

    /**
     * The solid's runs along the vertical line through (x, y), bottom to top, as (from, to) pairs in one array; runs
     * closer than [joinMm] joined. Empty outside the model.
     */
    fun runs(x: Double, y: Double): DoubleArray {
        val i = Math.floor((x - bx0) / cell).toInt(); val j = Math.floor((y - by0) / cell).toInt()
        if (i < 0 || j < 0 || i >= nx || j >= ny) return DoubleArray(0)
        val hits = ArrayList<Int>()
        val zs = ArrayList<Double>()
        for (k in buckets[j * nx + i]) {
            val z = hit(k, x, y)
            if (z.isNaN()) continue
            zs += z; hits += k
        }
        if (zs.isEmpty()) return DoubleArray(0)
        // by height; where an entry and an exit are at one height, the entry first (two parts touching: one run)
        val order = zs.indices.sortedWith(compareBy<Int>({ zs[it] }, { -sign[hits[it]] }))
        val out = ArrayList<Double>()
        var depth = 0
        for (o in order) {
            val before = depth
            depth += sign[hits[o]]
            if (before <= 0 && depth > 0) {
                // a run starts; within joinMm of the last one's end: that run goes on
                if (out.size >= 2 && zs[o] - out[out.size - 1] <= joinMm) out.removeAt(out.size - 1) else out += zs[o]
            } else if (before > 0 && depth <= 0) out += zs[o]
        }
        if (out.size % 2 == 1) out.removeAt(out.size - 1)               // an open mesh: a run with no end is no run
        return out.toDoubleArray()
    }

    /** The top of the run of solid holding (x, y, z) (where the vertical line through it leaves the model above it), or NaN when (x, y, z) is not in the model. */
    fun topAbove(x: Double, y: Double, z: Double): Double {
        val r = runs(x, y)
        var k = 0
        while (k + 1 < r.size) { if (z >= r[k] && z < r[k + 1]) return r[k + 1]; k += 2 }
        return Double.NaN
    }

    /** Z of triangle [k]'s plane at (x, y) when the vertical line there meets it (by the edge rule above), else NaN. */
    private fun hit(k: Int, x: Double, y: Double): Double {
        val o = k * 9
        val ax = tris[o]; val ay = tris[o + 1]; val bx = tris[o + 3]; val by = tris[o + 4]; val cx = tris[o + 6]; val cy = tris[o + 7]
        val e0 = edge(ax, ay, bx, by, x, y); if (!owns(e0, ax, ay, bx, by)) return Double.NaN
        val e1 = edge(bx, by, cx, cy, x, y); if (!owns(e1, bx, by, cx, cy)) return Double.NaN
        val e2 = edge(cx, cy, ax, ay, x, y); if (!owns(e2, cx, cy, ax, ay)) return Double.NaN
        val sum = e0 + e1 + e2
        if (sum == 0.0) return Double.NaN
        // barycentric weights: each corner's from the edge opposite it
        return (e1 * tris[o + 2] + e2 * tris[o + 5] + e0 * tris[o + 8]) / sum
    }

    private companion object {
        /**
         * Which side of the directed edge (ax, ay) -> (bx, by) the point is on (positive: left, inside a counter-clockwise
         * triangle), worked from the edge's ends in a fixed order (the smaller first, by x then y), so the two triangles that
         * share an edge get exactly opposite values.
         */
        fun edge(ax: Double, ay: Double, bx: Double, by: Double, px: Double, py: Double): Double =
            if (ax < bx || (ax == bx && ay < by)) (bx - ax) * (py - ay) - (by - ay) * (px - ax)
            else -((ax - bx) * (py - by) - (ay - by) * (px - bx))

        /** A point on the edge (value 0) belongs to the triangle on one side only: the one whose edge runs up, or runs left when level. */
        fun owns(e: Double, ax: Double, ay: Double, bx: Double, by: Double): Boolean =
            e > 0 || (e == 0.0 && (by > ay || (by == ay && bx < ax)))
    }
}
