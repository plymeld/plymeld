package plymeld.geom

import java.math.BigInteger

/**
 * Delaunay triangulation of a point set (µm grid points), incremental Bowyer-Watson.
 *
 * Predicates: orientation is exact in doubles (coordinates below 2^26 µm, products below 2^53);
 * the in-circle test is evaluated in doubles and re-evaluated exactly (BigInteger) when the
 * result is within its rounding error of zero. Duplicate points are skipped.
 * Triangles are counter-clockwise; [nb] (3 per triangle) is the neighbor across the edge
 * OPPOSITE vertex i (-1 = none).
 */
class Delaunay(xs: LongArray, ys: LongArray) {
    val n = xs.size
    /** Point coordinates: the input points, then the 3 super-triangle vertices. */
    val px = LongArray(n + 3); val py = LongArray(n + 3)
    private var tv = IntArray(0); private var tn = IntArray(0); private var alive = BooleanArray(0)
    /** Slots of dead triangles, reused (declared before init, which uses it). */
    private val free = ArrayDeque<Int>()
    var triangleCount = 0; private set

    init {
        require(xs.size == ys.size)
        for (i in 0 until n) { px[i] = xs[i]; py[i] = ys[i] }
        var minX = Long.MAX_VALUE; var minY = Long.MAX_VALUE; var maxX = Long.MIN_VALUE; var maxY = Long.MIN_VALUE
        for (i in 0 until n) { minX = minOf(minX, xs[i]); maxX = maxOf(maxX, xs[i]); minY = minOf(minY, ys[i]); maxY = maxOf(maxY, ys[i]) }
        if (n == 0) { minX = 0; minY = 0; maxX = 1; maxY = 1 }
        val d = maxOf(maxX - minX, maxY - minY, 1L) * 20
        val cx = (minX + maxX) / 2; val cy = (minY + maxY) / 2
        px[n] = cx - 2 * d; py[n] = cy - d; px[n + 1] = cx + 2 * d; py[n + 1] = cy - d; px[n + 2] = cx; py[n + 2] = cy + 2 * d
        grow(2 * n + 16)
        addTri(n, n + 1, n + 2, -1, -1, -1)
        // Biased randomized insertion order: a seeded shuffle split into doubling rounds, spatial
        // (Morton) order within each round. Pure spatial order fed thousands of collinear samples of
        // one straight outline edge in sequence, the worst case of incremental Delaunay: quadratic
        // work, and out of memory on a test model's layer. Seeded: same output every run.
        val rnd = java.util.Random(0x5EED)
        val shuffled = (0 until n).toMutableList().also { java.util.Collections.shuffle(it, rnd) }
        val order = ArrayList<Int>(n)
        var lo = 0; var size = 1
        while (lo < n) {
            val hi = minOf(n, lo + size)
            order.addAll(shuffled.subList(lo, hi).sortedBy { morton(xs[it] - minX, ys[it] - minY) })
            lo = hi; size *= 2
        }
        var last = 0
        for (p in order) last = insert(p, last)
    }

    private fun morton(x: Long, y: Long): Long {
        var r = 0L
        val xi = (x shr 4).coerceIn(0, (1L shl 30) - 1); val yi = (y shr 4).coerceIn(0, (1L shl 30) - 1)
        for (b in 0 until 30) r = r or (((xi shr b) and 1L) shl (2 * b)) or (((yi shr b) and 1L) shl (2 * b + 1))
        return r
    }

    private fun grow(cap: Int) {
        if (tv.size >= 3 * cap) return
        tv = tv.copyOf(3 * cap); tn = tn.copyOf(3 * cap); alive = alive.copyOf(cap)
    }

    /** Slots of dead triangles, reused: memory stays proportional to the live triangulation. */

    private fun addTri(a: Int, b: Int, c: Int, na: Int, nb: Int, nc: Int): Int {
        val t = if (free.isNotEmpty()) free.removeLast() else {
            if (triangleCount + 1 > alive.size) grow(alive.size * 2 + 16)
            triangleCount++
        }
        tv[3 * t] = a; tv[3 * t + 1] = b; tv[3 * t + 2] = c
        tn[3 * t] = na; tn[3 * t + 1] = nb; tn[3 * t + 2] = nc
        alive[t] = true
        return t
    }

    fun isAlive(t: Int) = alive[t]
    fun v(t: Int, i: Int) = tv[3 * t + i]
    fun nb(t: Int, i: Int) = tn[3 * t + i]
    /** Whether triangle t uses a super-triangle vertex (not part of the real triangulation). */
    fun isSuper(t: Int) = tv[3 * t] >= n || tv[3 * t + 1] >= n || tv[3 * t + 2] >= n

    private fun orient(a: Int, b: Int, c: Int): Long =
        (px[b] - px[a]) * (py[c] - py[a]) - (py[b] - py[a]) * (px[c] - px[a])

    /** > 0: d inside the circumcircle of the CCW triangle (a, b, c). */
    private fun inCircle(a: Int, b: Int, c: Int, d: Int): Int {
        val adx = (px[a] - px[d]).toDouble(); val ady = (py[a] - py[d]).toDouble()
        val bdx = (px[b] - px[d]).toDouble(); val bdy = (py[b] - py[d]).toDouble()
        val cdx = (px[c] - px[d]).toDouble(); val cdy = (py[c] - py[d]).toDouble()
        val al = adx * adx + ady * ady; val bl = bdx * bdx + bdy * bdy; val cl = cdx * cdx + cdy * cdy
        val det = adx * (bdy * cl - bl * cdy) - ady * (bdx * cl - bl * cdx) + al * (bdx * cdy - bdy * cdx)
        val perm = Math.abs(adx) * (Math.abs(bdy) * cl + bl * Math.abs(cdy)) + Math.abs(ady) * (Math.abs(bdx) * cl + bl * Math.abs(cdx)) +
            al * (Math.abs(bdx) * Math.abs(cdy) + Math.abs(bdy) * Math.abs(cdx))
        if (Math.abs(det) > 1e-14 * perm) return if (det > 0) 1 else -1
        // exact
        fun bi(v: Long) = BigInteger.valueOf(v)
        val ax = bi(px[a] - px[d]); val ay = bi(py[a] - py[d]); val bx = bi(px[b] - px[d]); val by = bi(py[b] - py[d])
        val cx = bi(px[c] - px[d]); val cy = bi(py[c] - py[d])
        val aL = ax * ax + ay * ay; val bL = bx * bx + by * by; val cL = cx * cx + cy * cy
        val e = ax * (by * cL - bL * cy) - ay * (bx * cL - bL * cx) + aL * (bx * cy - by * cx)
        return e.signum()
    }

    /** Inserts point p; [start]: a triangle to start the walk from. Returns a new triangle. */
    private fun insert(p: Int, start: Int): Int {
        // walk to the triangle containing p
        var t = if (start < triangleCount && alive[start]) start else (0 until triangleCount).first { alive[it] }
        var guard = 0
        walk@ while (true) {
            if (++guard > 4 * triangleCount + 100) error("Delaunay walk did not terminate")
            for (i in 0 until 3) {
                val a = tv[3 * t + (i + 1) % 3]; val b = tv[3 * t + (i + 2) % 3]
                if (orient(a, b, p) < 0) { t = tn[3 * t + i]; continue@walk }
            }
            break
        }
        for (i in 0 until 3) { val q = tv[3 * t + i]; if (px[q] == px[p] && py[q] == py[p]) return t }   // duplicate
        // cavity: triangles whose circumcircle contains p, connected to t
        val cavity = ArrayList<Int>(); val inCav = HashSet<Int>()
        val stack = ArrayDeque<Int>(); stack.add(t); inCav.add(t)
        while (stack.isNotEmpty()) {
            val c = stack.removeLast(); cavity.add(c)
            for (i in 0 until 3) {
                val o = tn[3 * c + i]
                if (o < 0 || o in inCav) continue
                if (inCircle(tv[3 * o], tv[3 * o + 1], tv[3 * o + 2], p) > 0) { inCav.add(o); stack.add(o) }
            }
        }
        // boundary edges (a, b) of the cavity, CCW, with the outside neighbor
        val edgeA = ArrayList<Int>(); val edgeB = ArrayList<Int>(); val outer = ArrayList<Int>()
        for (c in cavity) for (i in 0 until 3) {
            val o = tn[3 * c + i]
            if (o >= 0 && o in inCav) continue
            edgeA.add(tv[3 * c + (i + 1) % 3]); edgeB.add(tv[3 * c + (i + 2) % 3]); outer.add(o)
        }
        for (c in cavity) { alive[c] = false; free.addLast(c) }
        // new triangles (a, b, p): neighbor across (a, b) = outer; across (b, p) / (p, a) = new triangles
        val created = IntArray(edgeA.size)
        val byStart = HashMap<Int, Int>(edgeA.size * 2)
        val byEnd = HashMap<Int, Int>(edgeA.size * 2)
        for (k in edgeA.indices) {
            val nt = addTri(edgeA[k], edgeB[k], p, -1, -1, outer[k])
            created[k] = nt
            byStart[edgeA[k]] = nt; byEnd[edgeB[k]] = nt
            val o = outer[k]
            if (o >= 0) for (i in 0 until 3) {
                // the old neighbor's edge (b, a) now faces nt
                if (tv[3 * o + (i + 1) % 3] == edgeB[k] && tv[3 * o + (i + 2) % 3] == edgeA[k]) tn[3 * o + i] = nt
            }
        }
        for (k in edgeA.indices) {
            val nt = created[k]
            // edge (b, p) is opposite vertex a (index 0): the new triangle starting at b
            tn[3 * nt] = byStart.getValue(edgeB[k])
            // edge (p, a) is opposite vertex b (index 1): the new triangle ending at a
            tn[3 * nt + 1] = byEnd.getValue(edgeA[k])
        }
        return created[0]
    }
}
