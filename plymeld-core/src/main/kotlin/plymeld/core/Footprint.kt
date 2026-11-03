package plymeld.core

import plymeld.mesh.TriangleSoup

/** Convex hull of a mesh's XY projection (Andrew's monotone chain), mm, CCW, flat x y ... */
object Footprint {
    /** y (µm) offset to unsigned in a point's low 32 bits. */
    private const val Y_OFF = 1L shl 31

    /**
     * The hull of every vertex, rounded to 1 µm and deduplicated so it is stable. Primitive arrays throughout (
     * a pair of boxed doubles per triangle corner, then a rounded copy and a set, ran a 4.7 M-triangle model's plan out of a
     * 3 GB heap): each point is one long, x in the high 32 bits and y offset to unsigned in the low ones, so sorting the
     * longs sorts by x, then y, as before.
     */
    fun hull(soup: TriangleSoup): DoubleArray {
        val n = soup.count * 3
        val keys = LongArray(n)
        for (i in 0 until n) {
            val x = Math.round(soup.coords[3 * i].toDouble() * 1000)
            val y = Math.round(soup.coords[3 * i + 1].toDouble() * 1000)
            keys[i] = (x shl 32) or ((y + Y_OFF) and 0xffffffffL)
        }
        keys.sort()
        var m = 0
        for (i in 0 until n) if (m == 0 || keys[i] != keys[m - 1]) keys[m++] = keys[i]
        val xs = DoubleArray(m) { (keys[it] shr 32) / 1000.0 }
        val ys = DoubleArray(m) { ((keys[it] and 0xffffffffL) - Y_OFF) / 1000.0 }
        if (m < 3) return DoubleArray(2 * m) { if (it % 2 == 0) xs[it / 2] else ys[it / 2] }
        fun cross(o: Int, a: Int, b: Int) = (xs[a] - xs[o]) * (ys[b] - ys[o]) - (ys[a] - ys[o]) * (xs[b] - xs[o])
        val lower = IntArray(m); var nl = 0
        for (p in 0 until m) { while (nl >= 2 && cross(lower[nl - 2], lower[nl - 1], p) <= 0) nl--; lower[nl++] = p }
        val upper = IntArray(m); var nu = 0
        for (p in m - 1 downTo 0) { while (nu >= 2 && cross(upper[nu - 2], upper[nu - 1], p) <= 0) nu--; upper[nu++] = p }
        val count = (nl - 1) + (nu - 1)
        val out = DoubleArray(2 * count)
        var k = 0
        for (j in 0 until nl - 1) { out[k++] = xs[lower[j]]; out[k++] = ys[lower[j]] }
        for (j in 0 until nu - 1) { out[k++] = xs[upper[j]]; out[k++] = ys[upper[j]] }
        return out
    }
}
