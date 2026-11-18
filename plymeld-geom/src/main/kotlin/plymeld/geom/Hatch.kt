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

    /**
     * [indexed]'s segments [segs] of [lineOf]'s scanlines with two neighbors on one scanline made one where the line only
     * grazes what lies between them (a corner of the area outside [region] reaching a few microns across the line): their
     * gap is shorter than [beadUm], the line's width, so their round ends, both laid against that corner, would lie over
     * each other by beadUm less the gap; laid through, the line's center comes off [region] by its farthest point's
     * distance from the region's edge, and the two are made one where that is less than the ends' overlap (each the
     * thickness of plastic laid twice: the lesser is laid). Found on a nameplate's first layer: a bottom surface's diagonal
     * line passing a wall loop's corner was cut 34 microns short of it, and its two ends lay over each other 0.25 mm wide.
     * The region's edges near a gap are read once for it; the line's distance from them is sampled every grid unit along
     * the gap (a distance changes by at most what the point moves, so the farthest is read to within a unit).
     */
    fun throughGrazes(segs: LongArray, lineOf: IntArray, region: List<Polygon>, beadUm: Long): Pair<LongArray, IntArray> {
        val n = lineOf.size
        if (n < 2 || beadUm <= 0) return segs to lineOf
        val out = LongArray(segs.size); val lines = IntArray(n); var m = 0
        val near = ArrayList<DoubleArray>()
        // the farthest the gap from (ax, ay) to (bx, by) lies from the region's edge
        fun depth(ax: Double, ay: Double, bx: Double, by: Double): Double {
            val x0 = minOf(ax, bx) - beadUm; val x1 = maxOf(ax, bx) + beadUm; val y0 = minOf(ay, by) - beadUm; val y1 = maxOf(ay, by) + beadUm
            near.clear()
            for (p in region) for (i in 0 until p.size) {
                val j = (i + 1) % p.size
                val px = p.x(i).toDouble(); val py = p.y(i).toDouble(); val qx = p.x(j).toDouble(); val qy = p.y(j).toDouble()
                if (maxOf(px, qx) < x0 || minOf(px, qx) > x1 || maxOf(py, qy) < y0 || minOf(py, qy) > y1) continue
                near.add(doubleArrayOf(px, py, qx, qy))
            }
            val steps = Math.max(2, Math.ceil(Math.hypot(bx - ax, by - ay)).toInt())
            var far = 0.0
            for (k in 1 until steps) {
                val x = ax + (bx - ax) * k / steps; val y = ay + (by - ay) * k / steps
                var d = Double.POSITIVE_INFINITY
                for (e in near) {
                    val vx = e[2] - e[0]; val vy = e[3] - e[1]; val l2 = vx * vx + vy * vy
                    val t = if (l2 == 0.0) 0.0 else (((x - e[0]) * vx + (y - e[1]) * vy) / l2).coerceIn(0.0, 1.0)
                    d = minOf(d, Math.hypot(x - e[0] - t * vx, y - e[1] - t * vy))
                }
                far = maxOf(far, d)
            }
            return far
        }
        var k = 0
        while (k < n) {
            out[4 * m] = segs[4 * k]; out[4 * m + 1] = segs[4 * k + 1]; out[4 * m + 2] = segs[4 * k + 2]; out[4 * m + 3] = segs[4 * k + 3]
            lines[m] = lineOf[k]
            while (k + 1 < n && lineOf[k + 1] == lineOf[k]) {
                val ax = out[4 * m + 2].toDouble(); val ay = out[4 * m + 3].toDouble()
                val bx = segs[4 * k + 4].toDouble(); val by = segs[4 * k + 5].toDouble()
                val gap = Math.hypot(bx - ax, by - ay)
                if (gap >= beadUm || depth(ax, ay, bx, by) >= beadUm - gap) break
                k++
                out[4 * m + 2] = segs[4 * k + 2]; out[4 * m + 3] = segs[4 * k + 3]
            }
            m++; k++
        }
        return out.copyOf(4 * m) to lines.copyOf(m)
    }

    /** Total length of hatch segments, mm. */
    fun lengthMm(segs: LongArray): Double {
        var l = 0.0
        for (i in segs.indices step 4) l += Math.hypot((segs[i + 2] - segs[i]).toDouble(), (segs[i + 3] - segs[i + 1]).toDouble())
        return l / Units.PER_MM
    }
}
