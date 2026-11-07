package plymeld.mesh

import plymeld.geom.Polygon
import plymeld.geom.Units

/**
 * One sliced layer: closed loops (outer CCW, holes CW) plus any open chains that
 * could not be closed — reported, never silently dropped or force-closed.
 */
class Layer(val z: Double, val loops: List<Polygon>, val openChains: List<LongArray>) {
    /** Net area in mm² (outers positive, holes negative). */
    fun netAreaMm2(): Double = loops.sumOf { it.signedAreaMm2() }
    fun perimeterMm(): Double = loops.sumOf { it.perimeterMm() }
}

/**
 * Plane slicer: triangle soup → per-layer closed loops on the integer grid.
 *
 * Method: vertices are snapped to the grid once; for each slice plane the
 * triangles spanning it are cut into segments; segments are stitched end-to-end
 * into loops. Two properties make the stitching exact rather than tolerance-based:
 *
 *  - A vertex exactly on the plane counts as *above* it, so a plane through a
 *    vertex never yields zero-length or duplicated segments.
 *  - Each edge's crossing point is computed from the edge's endpoints in a
 *    canonical order, so the two triangles sharing an edge produce the identical
 *    grid point and the segments meet bit-for-bit.
 *
 * Segment direction puts the solid on the left (outer loops end up CCW).
 */
object Slicer {

    /** Snap the soup's vertices to the integer grid: 9 Longs per triangle. */
    fun snap(soup: TriangleSoup): LongArray {
        val out = LongArray(soup.count * 9)
        for (i in out.indices) out[i] = Units.mm(soup.coords[i].toDouble())
        return out
    }

    /**
     * The triangles' indices by their lowest z, ties in index order (the order a stable sort gives), sorted as primitive
     * longs: each one's lowest z above the soup's lowest in the high 32 bits, its index in the low 32. A boxed sort of the
     * indices by key spent more of a pose search than the slicing it ordered. A soup taller than 2^31 grid units (over
     * 2 km) is sorted the boxed way.
     */
    internal fun byZmin(zmin: LongArray): IntArray {
        val n = zmin.size
        if (n == 0) return IntArray(0)
        var lo = zmin[0]; var hi = zmin[0]
        for (z in zmin) { if (z < lo) lo = z; if (z > hi) hi = z }
        if (hi - lo >= Int.MAX_VALUE.toLong()) return (0 until n).sortedBy { zmin[it] }.toIntArray()
        val keys = LongArray(n) { ((zmin[it] - lo) shl 32) or it.toLong() }
        keys.sort()
        return IntArray(n) { (keys[it] and 0xFFFFFFFFL).toInt() }
    }

    /** A soup made ready to slice: its vertices on the grid, each triangle's lowest and highest z, the triangles by lowest z. */
    private class Prepared(val v: LongArray, val zmin: LongArray, val zmax: LongArray, val order: IntArray)

    /**
     * Soups' [Prepared] forms ([SoupCache]: by identity, bounded in bytes): one posed model is sliced again and again (a
     * pose search's surveys cut it at a few heights per candidate plan, and snapping and sorting every triangle for each was
     * the larger part of that).
     */
    private val prepared = SoupCache<Prepared>(SoupCache.heapShare()) { p -> 8L * (p.v.size + p.zmin.size + p.zmax.size) + 4L * p.order.size }

    private fun prepare(soup: TriangleSoup): Prepared = prepared.get(soup) {
        val v = snap(soup)
        val n = soup.count
        // Bucket: for each layer, which triangles span it. Triangles are sorted by zmin
        // and swept; a triangle is "active" at plane z when zmin < z <= zmax... using the
        // on-plane-is-above rule: it crosses iff exists vertex with z < plane and one with z >= plane.
        val zmin = LongArray(n); val zmax = LongArray(n)
        for (t in 0 until n) {
            val b = t * 9
            var lo = v[b + 2]; var hi = lo
            val z1 = v[b + 5]; val z2 = v[b + 8]
            if (z1 < lo) lo = z1; if (z1 > hi) hi = z1
            if (z2 < lo) lo = z2; if (z2 > hi) hi = z2
            zmin[t] = lo; zmax[t] = hi
        }
        Prepared(v, zmin, zmax, byZmin(zmin))
    }

    fun slice(soup: TriangleSoup, zsMm: DoubleArray): List<Layer> {
        val pr = prepare(soup)
        val v = pr.v; val zmin = pr.zmin; val zmax = pr.zmax; val order = pr.order
        val n = soup.count
        val zs = LongArray(zsMm.size) { Units.mm(zsMm[it]) }
        val layers = ArrayList<Layer>(zs.size)
        val active = IntArrayList()
        var next = 0
        val segs = LongArrayList()
        for (li in zs.indices) {
            val z = zs[li]
            while (next < n && zmin[order[next]] < z) { active.add(order[next]); next++ }
            // drop triangles entirely below the plane (zmax < z means no vertex >= z)
            active.retain { zmax[it] >= z }
            segs.clear()
            for (k in 0 until active.size) cutTriangle(v, active[k] * 9, z, segs)
            layers.add(stitch(zsMm[li], segs))
        }
        return layers
    }

    /** All crossing segments (x0 y0 x1 y1 ...) of the soup with one plane, unstitched. For tests and debug output. */
    fun segments(soup: TriangleSoup, zMm: Double): LongArray {
        val v = snap(soup)
        val z = Units.mm(zMm)
        val out = LongArrayList()
        for (t in 0 until soup.count) cutTriangle(v, t * 9, z, out)
        return out.toArray()
    }

    /** Emits the crossing segment (x0 y0 x1 y1) of triangle at `b` with plane `z`, if any. */
    private fun cutTriangle(v: LongArray, b: Int, z: Long, out: LongArrayList) {
        val z0 = v[b + 2]; val z1 = v[b + 5]; val z2 = v[b + 8]
        val a0 = z0 >= z; val a1 = z1 >= z; val a2 = z2 >= z
        if (a0 == a1 && a1 == a2) return
        // Walk edges 0->1, 1->2, 2->0. A below->above crossing is the segment END,
        // an above->below crossing is its START (solid on the left; see cube check in tests).
        var sx = 0L; var sy = 0L; var ex = 0L; var ey = 0L
        var haveS = false; var haveE = false
        val p = scratch
        for (e in 0 until 3) {
            val i = b + e * 3
            val j = b + ((e + 1) % 3) * 3
            val ai = v[i + 2] >= z; val aj = v[j + 2] >= z
            if (ai == aj) continue
            crossing(v, i, j, z, p)
            if (!ai && aj) { ex = p[0]; ey = p[1]; haveE = true }
            else { sx = p[0]; sy = p[1]; haveS = true }
        }
        if (haveS && haveE) { out.add(sx); out.add(sy); out.add(ex); out.add(ey) }
    }

    // Scratch output of crossing(); slice() is single-threaded per Slicer instance.
    private val scratch = LongArray(2)

    /**
     * Intersection of edge (i, j) with plane z, into out[0..1]. Endpoints are ordered
     * canonically (lower z first; ties by x then y) so both triangles sharing the edge
     * compute the identical point. Uses exact integer arithmetic with round-half-up.
     */
    private fun crossing(v: LongArray, i: Int, j: Int, z: Long, out: LongArray) {
        var a = i; var b = j
        val za = v[a + 2]; val zb = v[b + 2]
        val swap = when {
            za != zb -> za > zb
            v[a] != v[b] -> v[a] > v[b]
            else -> v[a + 1] > v[b + 1]
        }
        if (swap) { val t = a; a = b; b = t }
        val x0 = v[a]; val y0 = v[a + 1]; val z0 = v[a + 2]
        val x1 = v[b]; val y1 = v[b + 1]; val z1 = v[b + 2]
        val dz = z1 - z0            // never 0: endpoints are on different sides
        val t = z - z0
        out[0] = x0 + divRound((x1 - x0) * t, dz)
        out[1] = y0 + divRound((y1 - y0) * t, dz)
    }

    private fun divRound(num: Long, den: Long): Long {
        // round half away from zero, exact
        val q = num / den
        val r = num % den
        return if (Math.abs(r) * 2 >= Math.abs(den)) q + (if ((num < 0) != (den < 0)) -1 else 1) else q
    }

    /** Stitches segments (x0 y0 x1 y1 ...) into closed loops; leftovers become open chains. */
    fun stitch(zMm: Double, segs: LongArrayList): Layer {
        val m = segs.size / 4
        if (m == 0) return Layer(zMm, emptyList(), emptyList())
        // Index: start point -> segment indices (small multimap in flat arrays).
        val startIndex = HashMap<Long, IntArrayList>(m * 2)
        fun key(x: Long, y: Long): Long = x * 0x9E3779B97F4A7C15uL.toLong() xor (y + 0x632BE59BD9B4E019L)
        for (s in 0 until m) {
            val k = key(segs[s * 4], segs[s * 4 + 1])
            startIndex.getOrPut(k) { IntArrayList() }.add(s)
        }
        val used = BooleanArray(m)
        val loops = ArrayList<Polygon>()
        val open = ArrayList<LongArray>()
        val chain = LongArrayList()
        for (s0 in 0 until m) {
            if (used[s0]) continue
            chain.clear()
            var s = s0
            used[s] = true
            chain.add(segs[s * 4]); chain.add(segs[s * 4 + 1])
            var closed = false
            while (true) {
                val ex = segs[s * 4 + 2]; val ey = segs[s * 4 + 3]
                if (ex == segs[s0 * 4] && ey == segs[s0 * 4 + 1]) { closed = true; break }
                chain.add(ex); chain.add(ey)
                val cands = startIndex[key(ex, ey)]
                var nxt = -1
                if (cands != null) for (c in 0 until cands.size) {
                    val t = cands[c]
                    if (!used[t] && segs[t * 4] == ex && segs[t * 4 + 1] == ey) { nxt = t; break }
                }
                if (nxt < 0) break
                used[nxt] = true
                s = nxt
            }
            if (closed) {
                val poly = Polygon(chain.toArray()).simplified()
                if (poly.size >= 3) loops.add(poly)
            } else {
                open.add(chain.toArray())
            }
        }
        return Layer(zMm, loops, open)
    }
}

/** Minimal growable primitive lists (no boxing). */
class IntArrayList(cap: Int = 16) {
    private var a = IntArray(cap)
    var size = 0; private set
    operator fun get(i: Int) = a[i]
    fun add(v: Int) { if (size == a.size) a = a.copyOf(a.size * 2); a[size++] = v }
    fun clear() { size = 0 }
    fun retain(keep: (Int) -> Boolean) {
        var w = 0
        for (r in 0 until size) { val v = a[r]; if (keep(v)) a[w++] = v }
        size = w
    }
    operator fun set(i: Int, v: Int) { a[i] = v }
}

class LongArrayList(cap: Int = 64) {
    private var a = LongArray(cap)
    var size = 0; private set
    operator fun get(i: Int) = a[i]
    fun add(v: Long) { if (size == a.size) a = a.copyOf(a.size * 2); a[size++] = v }
    fun clear() { size = 0 }
    fun toArray(): LongArray = a.copyOf(size)
}
