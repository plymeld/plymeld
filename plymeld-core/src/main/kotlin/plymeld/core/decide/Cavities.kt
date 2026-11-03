package plymeld.core.decide

import plymeld.mesh.TriangleSoup

/**
 * Sealed internal cavities of a mesh: a closed shell whose surface faces inward (negative signed
 * volume) is a void inside the solid. Downward area on such a shell can be bridged, or given support
 * that stays trapped inside, but support there can never be removed. Shells are triangles joined by
 * shared edges (vertices matched exactly as the file wrote them; meshes are triangle soups).
 */
object Cavities {
    /** Number of shells (triangles joined by shared edges). */
    fun shellCount(s: TriangleSoup): Int = shells(s).toSet().size

    /**
     * Whether the mesh is ONE closed shell with every edge shared by exactly two triangles. When it
     * is not (several shells, or parts touching along an edge so it is used 3+ times), faces of one
     * part can rest on another, and only a point-in-solid test says what is over air.
     */
    fun isSingleClosedShell(s: TriangleSoup): Boolean = manifoldShellCount(s) == 1

    fun manifoldShellCount(s: TriangleSoup): Int = manifoldShells(s).toSet().size

    /**
     * Shell id per triangle, shells joined only across edges used by exactly two triangles: parts
     * that merely touch along an edge (used 3+ times) stay separate shells.
     */
    fun manifoldShells(s: TriangleSoup): IntArray {
        val c = s.coords
        val ids = HashMap<Triple<Int, Int, Int>, Int>()
        fun vid(o: Int) = ids.getOrPut(Triple(c[o].toRawBits(), c[o + 1].toRawBits(), c[o + 2].toRawBits())) { ids.size }
        val tv = IntArray(3 * s.count) { vid(9 * (it / 3) + 3 * (it % 3)) }
        val byEdge = HashMap<Long, IntArray>()                 // edge -> [uses, first triangle, second triangle]
        for (t in 0 until s.count) for (k in 0 until 3) {
            val a = tv[3 * t + k]; val b = tv[3 * t + (k + 1) % 3]
            val e = byEdge.getOrPut(minOf(a, b).toLong() shl 32 or maxOf(a, b).toLong()) { intArrayOf(0, -1, -1) }
            if (e[0] < 2) e[1 + e[0]] = t
            e[0]++
        }
        val parent = IntArray(s.count) { it }
        fun find(x: Int): Int { var r = x; while (parent[r] != r) { parent[r] = parent[parent[r]]; r = parent[r] }; return r }
        for (e in byEdge.values) if (e[0] == 2) { val ra = find(e[1]); val rb = find(e[2]); if (ra != rb) parent[ra] = rb }
        return IntArray(s.count) { find(it) }
    }

    /** How far outside a face its "just outside" probe sits: a geometric tolerance, not a print parameter. */
    private const val OUTSIDE_MM = 0.01

    /**
     * Per triangle, the share of it that ANOTHER shell covers (0 = open to air, 1 = buried in or
     * pressed against another part). Probed a hair outside the face along its own outward normal,
     * so the answer does not depend on the pose: it is computed once and every candidate reuses it.
     * A face is split into k x k equal sub-triangles (about 1 mm² each, k <= 32: faces up to ~1000 mm²
     * keep the 1 mm² resolution; at 8 a 200 mm² face read a 2 mm boundary 20 % off), each probed at
     * its center. The XY grid is sized to the mesh (about 16 triangles per cell on average) so dense
     * meshes stay fast: a search budget, not a print parameter.
     */
    fun coveredShare(s: TriangleSoup, shells: IntArray): FloatArray {
        val c = s.coords
        val b = s.bounds()
        val footprint = maxOf((b[3] - b[0]) * (b[4] - b[1]), 1e-6)
        val cell = Math.sqrt(footprint * 16 / maxOf(s.count, 1)).coerceIn(0.1, 2.0)
        val solid = Solid(s, shells, cell)
        val out = FloatArray(s.count)
        java.util.stream.IntStream.range(0, s.count).parallel().forEach { t ->
            val o = 9 * t
            val ux = (c[o + 3] - c[o]).toDouble(); val uy = (c[o + 4] - c[o + 1]).toDouble(); val uz = (c[o + 5] - c[o + 2]).toDouble()
            val vx = (c[o + 6] - c[o]).toDouble(); val vy = (c[o + 7] - c[o + 1]).toDouble(); val vz = (c[o + 8] - c[o + 2]).toDouble()
            val nx = uy * vz - uz * vy; val ny = uz * vx - ux * vz; val nz = ux * vy - uy * vx
            val len = Math.sqrt(nx * nx + ny * ny + nz * nz)
            if (len == 0.0) return@forEach
            val ex = nx / len * OUTSIDE_MM; val ey = ny / len * OUTSIDE_MM; val ez = nz / len * OUTSIDE_MM
            val k = Math.ceil(Math.sqrt(len / 2)).toInt().coerceIn(1, 32)
            var hit = 0; var n = 0
            fun probe(u: Double, v: Double) {   // barycentric (1-u-v, u, v) of vertices 0, 1, 2
                val w = 1 - u - v
                val x = w * c[o] + u * c[o + 3] + v * c[o + 6] + ex
                val y = w * c[o + 1] + u * c[o + 4] + v * c[o + 7] + ey
                val z = w * c[o + 2] + u * c[o + 5] + v * c[o + 8] + ez
                n++; if (solid.insideOther(x, y, z, t)) hit++
            }
            for (i in 0 until k) for (j in 0 until k - i) {
                probe((i + 1.0 / 3) / k, (j + 1.0 / 3) / k)
                if (i + j < k - 1) probe((i + 2.0 / 3) / k, (j + 2.0 / 3) / k)
            }
            out[t] = hit.toFloat() / n
        }
        return out
    }

    /** For each triangle: whether it belongs to a sealed cavity shell. */
    fun mask(s: TriangleSoup): BooleanArray {
        val shell = shells(s)
        val c = s.coords
        val vol = HashMap<Int, Double>()
        for (t in 0 until s.count) {
            val o = 9 * t
            val v6 = c[o].toDouble() * (c[o + 4].toDouble() * c[o + 8] - c[o + 5].toDouble() * c[o + 7]) -
                c[o + 1].toDouble() * (c[o + 3].toDouble() * c[o + 8] - c[o + 5].toDouble() * c[o + 6]) +
                c[o + 2].toDouble() * (c[o + 3].toDouble() * c[o + 7] - c[o + 4].toDouble() * c[o + 6])
            vol.merge(shell[t], v6 / 6, Double::plus)
        }
        val total = vol.values.sum()
        // a cavity: an inward shell whose volume is not rounding noise (> 1e-6 of the solid's)
        val cavity = vol.filter { it.value < 0 && -it.value > 1e-6 * Math.abs(total) }.keys
        return BooleanArray(s.count) { shell[it] in cavity }
    }

    /** Shell id of each triangle. */
    fun shells(s: TriangleSoup): IntArray {
        val c = s.coords
        val ids = HashMap<Triple<Int, Int, Int>, Int>()
        fun vid(o: Int) = ids.getOrPut(Triple(c[o].toRawBits(), c[o + 1].toRawBits(), c[o + 2].toRawBits())) { ids.size }
        val tv = IntArray(3 * s.count) { vid(9 * (it / 3) + 3 * (it % 3)) }
        val parent = IntArray(s.count) { it }
        fun find(x: Int): Int { var r = x; while (parent[r] != r) { parent[r] = parent[parent[r]]; r = parent[r] }; return r }
        val byEdge = HashMap<Long, Int>()
        for (t in 0 until s.count) for (k in 0 until 3) {
            val a = tv[3 * t + k]; val b = tv[3 * t + (k + 1) % 3]
            val key = minOf(a, b).toLong() shl 32 or maxOf(a, b).toLong()
            val other = byEdge.putIfAbsent(key, t)
            if (other != null) { val ra = find(t); val rb = find(other); if (ra != rb) parent[ra] = rb }
        }
        return IntArray(s.count) { find(it) }
    }
}

/**
 * Point-in-solid for a mesh made of several shells (e.g. parts that touch or overlap): the
 * winding number of a vertical ray up from the point — each crossing adds the sign of the
 * triangle's facing (up +1, down −1), so it counts the shells the point is inside. Triangles are
 * binned in an XY grid of [cellMm]. The query is nudged by a tiny fixed XY offset so it never lies
 * exactly on a triangle edge (a geometric tie-break, not a print parameter).
 */
class Solid(private val s: TriangleSoup, private val shell: IntArray, private val cellMm: Double = 2.0) {
    /** Shell ids (dense 0..n-1) and each shell's box, flat: x0 y0 z0 x1 y1 z1 per shell. */
    private val dense: IntArray
    private val boxes: DoubleArray
    init {
        val index = HashMap<Int, Int>()
        dense = IntArray(s.count) { t -> index.getOrPut(shell[t]) { index.size } }
        boxes = DoubleArray(6 * index.size) { if (it % 6 < 3) Double.MAX_VALUE else -Double.MAX_VALUE }
        val c = s.coords
        for (t in 0 until s.count) {
            val b = 6 * dense[t]
            for (k in 0 until 3) for (d in 0 until 3) { val v = c[9 * t + 3 * k + d].toDouble(); if (v < boxes[b + d]) boxes[b + d] = v; if (v > boxes[b + 3 + d]) boxes[b + 3 + d] = v }
        }
    }

    /** Dense XY grid over the mesh: cell (ix, iy) holds triangles start[i]..start[i+1] of [cellTris]. */
    private class Grid(val x0: Double, val y0: Double, val nx: Int, val ny: Int, val start: IntArray, val cellTris: IntArray)
    private val grid by lazy {

        val c = s.coords
        var gx0 = Double.MAX_VALUE; var gy0 = Double.MAX_VALUE; var gx1 = -Double.MAX_VALUE; var gy1 = -Double.MAX_VALUE
        for (i in 0 until s.count * 3) { val x = c[3 * i].toDouble(); val y = c[3 * i + 1].toDouble(); if (x < gx0) gx0 = x; if (x > gx1) gx1 = x; if (y < gy0) gy0 = y; if (y > gy1) gy1 = y }
        val nx = Math.floor((gx1 - gx0) / cellMm).toInt() + 1; val ny = Math.floor((gy1 - gy0) / cellMm).toInt() + 1
        fun span(t: Int, pass: (Int) -> Unit) {
            val o = 9 * t
            val ax = ((minOf(c[o], c[o + 3], c[o + 6]) - gx0) / cellMm).toInt(); val bx = ((maxOf(c[o], c[o + 3], c[o + 6]) - gx0) / cellMm).toInt()
            val ay = ((minOf(c[o + 1], c[o + 4], c[o + 7]) - gy0) / cellMm).toInt(); val by = ((maxOf(c[o + 1], c[o + 4], c[o + 7]) - gy0) / cellMm).toInt()
            for (ix in ax..minOf(bx, nx - 1)) for (iy in ay..minOf(by, ny - 1)) pass(ix * ny + iy)
        }
        val count = IntArray(nx * ny + 1)
        for (t in 0 until s.count) span(t) { count[it + 1]++ }
        for (i in 1..nx * ny) count[i] += count[i - 1]
        val fill = count.copyOf(); val tris = IntArray(count[nx * ny])
        for (t in 0 until s.count) span(t) { tris[fill[it]++] = t }
        Grid(gx0, gy0, nx, ny, count, tris)
    }

    /**
     * Whether the point is inside the solid, asked for a point just below triangle [ownTriangle]:
     * one closed shell has air just below its own downward faces, so unless ANOTHER shell's box
     * contains the point the answer is no without a ray. The ray itself counts every shell
     * (a cavity's own inward shell must cancel the solid around it).
     */
    fun insideOther(x0: Double, y0: Double, z: Double, ownTriangle: Int): Boolean {

        val ownD = dense[ownTriangle]
        var any = false
        var b = 0
        while (b < boxes.size) {
            if (b / 6 != ownD && x0 >= boxes[b] && x0 <= boxes[b + 3] && y0 >= boxes[b + 1] && y0 <= boxes[b + 4] && z >= boxes[b + 2] && z <= boxes[b + 5]) { any = true; break }
            b += 6
        }
        if (!any) return false

        return inside(x0, y0, z)
    }

    fun inside(x0: Double, y0: Double, z: Double): Boolean {
        val x = x0 + 1.37e-5; val y = y0 + 2.91e-5
        val g = grid
        val ix = Math.floor((x - g.x0) / cellMm).toInt(); val iy = Math.floor((y - g.y0) / cellMm).toInt()
        if (ix < 0 || iy < 0 || ix >= g.nx || iy >= g.ny) return false
        val cell = ix * g.ny + iy
        val c = s.coords
        var w = 0
        for (k in g.start[cell] until g.start[cell + 1]) {
            val o = 9 * g.cellTris[k]
            val ax = c[o].toDouble(); val ay = c[o + 1].toDouble(); val bx = c[o + 3].toDouble(); val by = c[o + 4].toDouble()
            val cx = c[o + 6].toDouble(); val cy = c[o + 7].toDouble()
            val d = (by - cy) * (ax - cx) + (cx - bx) * (ay - cy)
            if (d == 0.0) continue                                   // vertical triangle: the ray does not cross it
            val l1 = ((by - cy) * (x - cx) + (cx - bx) * (y - cy)) / d
            val l2 = ((cy - ay) * (x - cx) + (ax - cx) * (y - cy)) / d
            val l3 = 1 - l1 - l2
            if (l1 < 0 || l2 < 0 || l3 < 0) continue
            val zt = l1 * c[o + 2] + l2 * c[o + 5] + l3 * c[o + 8]
            if (zt <= z) continue
            w += if (d > 0) 1 else -1                                // d > 0: counter-clockwise from above = facing up
        }
        return w > 0
    }
}
