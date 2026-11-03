package plymeld.geom

/**
 * The center-line skeleton (approximate medial axis) of a polygon set, with the local
 * half-thickness at every point — what variable-width (Arachne-style) walls are laid along.
 *
 * Built from the Delaunay triangulation of the outline sampled every [sampleUm]: each triangle's
 * circumcenter is a Voronoi vertex, at distance = its circumradius from the nearest outline
 * samples; the Voronoi edge between two neighboring triangles separates the two outline samples
 * of their shared edge. An edge is part of the skeleton when both circumcenters lie inside the
 * region and the two samples it separates are at least [minFeatureUm] apart: samples closer than
 * that are neighbors along one side of the outline, and the edges between them are sampling
 * noise (short hairs toward the outline), not a center line.
 *
 * Accuracy: a point of the skeleton is within about sampleUm² / (8 · radius) of the true medial
 * axis, and its radius within the same of the true half-thickness (samples on a straight edge).
 */
class Skeleton(region: List<Polygon>, val sampleUm: Long, val minFeatureUm: Long) {
    /** Skeleton edges: x0 y0 r0 x1 y1 r1 per edge (µm; r = radius, half the local thickness). */
    val edges: DoubleArray

    init {
        // sample the outline
        val sx = ArrayList<Long>(); val sy = ArrayList<Long>()
        for (p in region) for (i in 0 until p.size) {
            val j = (i + 1) % p.size
            val ax = p.x(i); val ay = p.y(i); val bx = p.x(j); val by = p.y(j)
            val len = Math.hypot((bx - ax).toDouble(), (by - ay).toDouble())
            val k = maxOf(1, Math.ceil(len / sampleUm).toInt())
            for (s in 0 until k) { sx.add(ax + Math.round((bx - ax) * s.toDouble() / k)); sy.add(ay + Math.round((by - ay) * s.toDouble() / k)) }
        }
        val dt = Delaunay(sx.toLongArray(), sy.toLongArray())
        val inside = Inside(region)
        // circumcenter + radius of each real triangle whose circumcenter lies inside the region
        val cx = DoubleArray(dt.triangleCount); val cy = DoubleArray(dt.triangleCount); val cr = DoubleArray(dt.triangleCount)
        val ok = BooleanArray(dt.triangleCount)
        for (t in 0 until dt.triangleCount) {
            if (!dt.isAlive(t) || dt.isSuper(t)) continue
            val a = dt.v(t, 0); val b = dt.v(t, 1); val c = dt.v(t, 2)
            val ax = dt.px[a].toDouble(); val ay = dt.py[a].toDouble()
            val bx = dt.px[b] - ax; val by = dt.py[b] - ay; val qx = dt.px[c] - ax; val qy = dt.py[c] - ay
            val d = 2 * (bx * qy - by * qx)
            if (d == 0.0) continue
            val b2 = bx * bx + by * by; val c2 = qx * qx + qy * qy
            val ux = (qy * b2 - by * c2) / d; val uy = (bx * c2 - qx * b2) / d
            cx[t] = ax + ux; cy[t] = ay + uy; cr[t] = Math.hypot(ux, uy)
            ok[t] = inside.contains(cx[t], cy[t])
        }
        val out = ArrayList<Double>()
        val minSq = minFeatureUm.toDouble() * minFeatureUm
        for (t in 0 until dt.triangleCount) {
            if (!ok[t]) continue
            for (i in 0 until 3) {
                val o = dt.nb(t, i)
                if (o < 0 || o <= t || !ok[o]) continue          // each edge once
                val a = dt.v(t, (i + 1) % 3); val b = dt.v(t, (i + 2) % 3)
                val dx = (dt.px[a] - dt.px[b]).toDouble(); val dy = (dt.py[a] - dt.py[b]).toDouble()
                if (dx * dx + dy * dy < minSq) continue
                out.add(cx[t]); out.add(cy[t]); out.add(cr[t]); out.add(cx[o]); out.add(cy[o]); out.add(cr[o])
            }
        }
        edges = out.toDoubleArray()
    }

    val edgeCount: Int get() = edges.size / 6

    /** Point-in-region test with a row index of the outline's edges (crossing parity, CCW outers / CW holes). */
    private class Inside(region: List<Polygon>) {
        private val ex = ArrayList<DoubleArray>()
        private val y0: Double; private val rowH: Double; private val rows: Array<MutableList<Int>>
        init {
            var lo = Double.MAX_VALUE; var hi = -Double.MAX_VALUE
            for (p in region) for (i in 0 until p.size) {
                val j = (i + 1) % p.size
                ex.add(doubleArrayOf(p.x(i).toDouble(), p.y(i).toDouble(), p.x(j).toDouble(), p.y(j).toDouble()))
                lo = minOf(lo, p.y(i).toDouble()); hi = maxOf(hi, p.y(i).toDouble())
            }
            val nRows = maxOf(1, minOf(4096, ex.size / 4))
            y0 = lo; rowH = maxOf(1.0, (hi - lo) / nRows)
            rows = Array(nRows) { ArrayList() }
            for ((k, e) in ex.withIndex()) {
                val r0 = ((minOf(e[1], e[3]) - y0) / rowH).toInt().coerceIn(0, nRows - 1)
                val r1 = ((maxOf(e[1], e[3]) - y0) / rowH).toInt().coerceIn(0, nRows - 1)
                for (r in r0..r1) rows[r].add(k)
            }
        }
        fun contains(x: Double, y: Double): Boolean {
            val r = ((y - y0) / rowH).toInt()
            if (r < 0 || r >= rows.size) return false
            var inside = false
            for (k in rows[r]) {
                val e = ex[k]
                if ((e[1] > y) != (e[3] > y)) {
                    val xc = e[0] + (y - e[1]) / (e[3] - e[1]) * (e[2] - e[0])
                    if (xc > x) inside = !inside
                }
            }
            return inside
        }
    }
}
