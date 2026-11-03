package plymeld.geom

/**
 * Parallel-line fill ("hatching") of a polygon set: lines at `angleDeg`, `spacingUm`
 * apart, clipped exactly to the region (even-odd along each scanline, which equals the
 * nonzero result for the normalized, non-overlapping regions the pipeline produces).
 *
 * Lines sit on a GLOBAL grid — offsets are multiples of the spacing measured from the
 * origin in the rotated frame — so every island of a surface, and every layer using the
 * same angle, shares one set of lines (no fragmented toolpaths: no half-pitch-shifted sub-grids).
 *
 * Output: flat segments x0 y0 x1 y1 in grid units, grouped by scanline in increasing
 * offset order, each scanline's segments in increasing position along the line.
 */
object Hatch {
    fun lines(region: List<Polygon>, angleDeg: Double, spacingUm: Long, offsetUm: Double = 0.0): LongArray = indexed(region, angleDeg, spacingUm, offsetUm).first

    /**
     * Segments plus, per segment, its global scanline index k (line offset = k·spacing + [offsetUm]).
     * [offsetUm] shifts the whole grid across the lines (cubic infill moves its lines with height).
     */
    fun indexed(region: List<Polygon>, angleDeg: Double, spacingUm: Long, offsetUm: Double = 0.0): Pair<LongArray, IntArray> {
        if (region.isEmpty() || spacingUm <= 0) return LongArray(0) to IntArray(0)
        val a = Math.toRadians(angleDeg)
        val c = Math.cos(a); val s = Math.sin(a)
        // rotate into the frame where hatch lines are horizontal: (u, v) = R(-a)·(x, y)
        var vMin = Double.POSITIVE_INFINITY; var vMax = Double.NEGATIVE_INFINITY
        for (p in region) for (i in 0 until p.size) {
            val v = -s * p.x(i) + c * p.y(i)
            if (v < vMin) vMin = v; if (v > vMax) vMax = v
        }
        val first = Math.ceil((vMin - offsetUm) / spacingUm).toLong()
        val last = Math.floor((vMax - offsetUm) / spacingUm).toLong()
        val out = ArrayList<Long>()
        val lineOf = ArrayList<Int>()
        val xs = ArrayList<Double>()
        for (k in first..last) {
            // Lines exactly through a vertex are nudged by a quarter micron so every crossing is proper.
            val v = k * spacingUm.toDouble() + offsetUm + 0.25
            xs.clear()
            for (p in region) {
                val n = p.size
                for (i in 0 until n) {
                    val j = (i + 1) % n
                    val xi = p.x(i).toDouble(); val yi = p.y(i).toDouble(); val xj = p.x(j).toDouble(); val yj = p.y(j).toDouble()
                    val vi = -s * xi + c * yi; val vj = -s * xj + c * yj
                    if ((vi < v) == (vj < v)) continue
                    val t = (v - vi) / (vj - vi)
                    val ui = c * xi + s * yi; val uj = c * xj + s * yj
                    xs.add(ui + t * (uj - ui))
                }
            }
            if (xs.size < 2) continue
            xs.sort()
            var i = 0
            while (i + 1 < xs.size) {
                val u0 = xs[i]; val u1 = xs[i + 1]
                if (u1 - u0 >= 1.0) {
                    // back to world: (x, y) = R(a)·(u, v)
                    out.add(Math.round(c * u0 - s * v)); out.add(Math.round(s * u0 + c * v))
                    out.add(Math.round(c * u1 - s * v)); out.add(Math.round(s * u1 + c * v))
                    lineOf.add(k.toInt())
                }
                i += 2
            }
        }
        return out.toLongArray() to lineOf.toIntArray()
    }

    /** Total length of hatch segments, mm. */
    fun lengthMm(segs: LongArray): Double {
        var l = 0.0
        for (i in segs.indices step 4) l += Math.hypot((segs[i + 2] - segs[i]).toDouble(), (segs[i + 3] - segs[i + 1]).toDouble())
        return l / Units.PER_MM
    }
}
