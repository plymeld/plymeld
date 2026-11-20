package plymeld.mesh

/**
 * A closed mesh cut to an axis-aligned box, each cut face closed with a cap, so what is left is a closed mesh again (a
 * section of a model printed on its own: `model.object.<key>.section`).
 *
 * One plane at a time. Triangles on the kept side stay; a triangle the plane crosses is cut, its two new points computed
 * from each mesh edge the same way whichever of the edge's two triangles is being cut (so the cut pieces still share
 * their edges exactly); the cut's edges, chained end to end, are the outlines of the cap, which is triangulated from
 * those points alone (no point added along an edge, so the cap shares its edges with the cut triangles exactly too).
 * A plane that would pass through a vertex is moved by the smallest step a float allows (about 0.00001 mm at 100 mm),
 * so every crossed triangle has a point strictly on each side.
 *
 * A mesh that is not closed where a plane cuts it (the cut's edges do not chain into loops) is refused.
 */
object SectionCut {
    /**
     * [soup] within [box] (x0, x1, y0, y1, z0, z1 in mm; an infinite side is not cut). The result may be empty (count 0)
     * when nothing of the soup lies in the box.
     */
    fun cut(soup: TriangleSoup, box: DoubleArray): TriangleSoup {
        require(box.size == 6 && box[0] < box[1] && box[2] < box[3] && box[4] < box[5]) { "a section's box needs min below max on every axis" }
        var coords = soup.coords; var count = soup.count
        for (axis in 0 until 3) for (side in 0 until 2) {
            val c = box[2 * axis + side]
            if (c.isInfinite() || count == 0) continue
            val out = clip(coords, count, axis, c.toFloat(), keepBelow = side == 1)
            coords = out; count = out.size / 9
        }
        return TriangleSoup(if (coords.size == count * 9) coords else coords.copyOf(count * 9), count)
    }

    /** A growing float array (no boxing). */
    private class Floats(capacity: Int) {
        var a = FloatArray(maxOf(capacity, 9)); var n = 0
        fun add(x: Float, y: Float, z: Float) { if (n + 3 > a.size) a = a.copyOf(maxOf(a.size * 2, n + 3)); a[n] = x; a[n + 1] = y; a[n + 2] = z; n += 3 }
    }

    /** The triangles of [coords] on the kept side of the plane [axis] = [at] (below it when [keepBelow], else above), with the cap. */
    private fun clip(coords: FloatArray, count: Int, axis: Int, at: Float, keepBelow: Boolean): FloatArray {
        // no vertex on the plane: move it the smallest step until none is
        var c = at + 0.0f
        while (true) { var hit = false; var i = axis; val n = count * 9; while (i < n) { if (coords[i] == c) { hit = true; break }; i += 3 }; if (!hit) break; c = Math.nextUp(c) }
        fun inside(v: Float) = if (keepBelow) v < c else v > c
        // nothing crossed: all kept or all dropped
        var anyIn = false; var anyOut = false
        run { var i = axis; val n = count * 9; while (i < n) { if (inside(coords[i])) anyIn = true else anyOut = true; i += 3 } }
        if (!anyOut) return if (coords.size == count * 9) coords else coords.copyOf(count * 9)
        if (!anyIn) return FloatArray(0)

        val out = Floats(count * 9 / 2)
        val u = (axis + 1) % 3; val v = (axis + 2) % 3
        // the cap's edges: from (su, sv) to (eu, ev), in the plane's own two axes
        val segs = Floats(1024)
        val p = FloatArray(3); val q = FloatArray(3)
        /** Where the mesh edge between vertices at [i] and [j] crosses the plane, into [into]: computed from the edge's ends in one order whichever way it is asked. */
        fun cross(i: Int, j: Int, into: FloatArray) {
            var a = i; var b = j
            // the ends in one order: by x, then y, then z
            val swap = coords[a] > coords[b] || (coords[a] == coords[b] && (coords[a + 1] > coords[b + 1] || (coords[a + 1] == coords[b + 1] && coords[a + 2] > coords[b + 2])))
            if (swap) { a = j; b = i }
            val t = (c.toDouble() - coords[a + axis]) / (coords[b + axis].toDouble() - coords[a + axis])
            for (k in 0 until 3) into[k] = if (k == axis) c else (coords[a + k] + t * (coords[b + k].toDouble() - coords[a + k])).toFloat() + 0.0f
        }
        var tri = 0
        while (tri < count) {
            val o = tri * 9; tri++
            val in0 = inside(coords[o + axis]); val in1 = inside(coords[o + 3 + axis]); val in2 = inside(coords[o + 6 + axis])
            val nIn = (if (in0) 1 else 0) + (if (in1) 1 else 0) + (if (in2) 1 else 0)
            if (nIn == 0) continue
            if (nIn == 3) { for (k in 0 until 9 step 3) out.add(coords[o + k], coords[o + k + 1], coords[o + k + 2]); continue }
            // the triangle turned so its corners are a, b, c2 in order with a the odd one out (the one inside, or the one outside)
            val odd = if (nIn == 1) (if (in0) 0 else if (in1) 1 else 2) else (if (!in0) 0 else if (!in1) 1 else 2)
            val a = o + 3 * odd; val b = o + 3 * ((odd + 1) % 3); val c2 = o + 3 * ((odd + 2) % 3)
            cross(a, b, p); cross(c2, a, q)           // p on a-b, q on c2-a
            // the two new points at one place (the plane a hair from the odd corner): no edge on the plane, and the piece
            // with both of them has no area (its two other edges cancel each other), so it is left out
            val pinched = p[0] == q[0] && p[1] == q[1] && p[2] == q[2]
            if (nIn == 1) {
                // a inside: the triangle a, p, q is kept; the kept piece's edge on the plane runs p -> q, the cap's q -> p
                if (pinched) continue
                out.add(coords[a], coords[a + 1], coords[a + 2]); out.add(p[0], p[1], p[2]); out.add(q[0], q[1], q[2])
                segs.add(q[u], q[v], 0f); segs.add(p[u], p[v], 0f)
            } else {
                // a outside: the quad p, b, c2, q is kept (as p b c2 and p c2 q); its edge on the plane runs q -> p, the cap's p -> q
                out.add(p[0], p[1], p[2]); out.add(coords[b], coords[b + 1], coords[b + 2]); out.add(coords[c2], coords[c2 + 1], coords[c2 + 2])
                if (pinched) continue
                out.add(p[0], p[1], p[2]); out.add(coords[c2], coords[c2 + 1], coords[c2 + 2]); out.add(q[0], q[1], q[2])
                segs.add(p[u], p[v], 0f); segs.add(q[u], q[v], 0f)
            }
        }
        cap(segs, out, axis, c)
        return out.a.copyOf(out.n)
    }

    private fun key(a: Float, b: Float): Long = (java.lang.Float.floatToIntBits(a + 0.0f).toLong() shl 32) or (java.lang.Float.floatToIntBits(b + 0.0f).toLong() and 0xffffffffL)

    /** The cap over the plane [axis] = [c]: [segs] (each two points, 6 floats) chained into loops, grouped (outlines with their holes), triangulated into [out]. */
    private fun cap(segs: Floats, out: Floats, axis: Int, c: Float) {
        val m = segs.n / 6
        if (m == 0) return
        // chain: each edge's end is another's start
        val starts = HashMap<Long, IntArray>(m * 2)          // start point -> the edges starting there (most often one)
        for (i in 0 until m) { val k = key(segs.a[6 * i], segs.a[6 * i + 1]); starts[k] = starts[k]?.let { it + i } ?: intArrayOf(i) }
        val used = BooleanArray(m)
        val loops = ArrayList<DoubleArray>()                  // each u v u v ...
        for (first in 0 until m) {
            if (used[first]) continue
            val pts = ArrayList<Double>()
            var i = first
            while (true) {
                used[i] = true
                pts.add(segs.a[6 * i].toDouble()); pts.add(segs.a[6 * i + 1].toDouble())
                val next = starts[key(segs.a[6 * i + 3], segs.a[6 * i + 4])]?.firstOrNull { !used[it] }
                if (next == null) {
                    require(segs.a[6 * i + 3] == segs.a[6 * first] && segs.a[6 * i + 4] == segs.a[6 * first + 1]) {
                        "the model is not a closed surface where the section cuts it (its ${"xyz"[axis]} = $c mm side): only a closed model can be cut to a section" }
                    break
                }
                i = next
            }
            // a point where the edges before and after it are one zero-length edge apart is kept: the cut triangles hold it
            if (pts.size >= 6) loops.add(pts.toDoubleArray())
        }
        fun area2(l: DoubleArray): Double { var s = 0.0; val n = l.size / 2; for (k in 0 until n) { val j = (k + 1) % n; s += l[2 * k] * l[2 * j + 1] - l[2 * j] * l[2 * k + 1] }; return s }
        val areas = DoubleArray(loops.size) { area2(loops[it]) }
        // which way round outlines run in these two axes (the cap's edges were turned to fit the cut triangles): the sign of the whole
        val sign = if (areas.sum() >= 0) 1.0 else -1.0
        fun contains(l: DoubleArray, x: Double, y: Double): Boolean {
            var inside = false; val n = l.size / 2
            for (k in 0 until n) { val j = (k + 1) % n
                val ay = l[2 * k + 1]; val by = l[2 * j + 1]
                if ((ay > y) != (by > y) && x < (l[2 * j] - l[2 * k]) * (y - ay) / (by - ay) + l[2 * k]) inside = !inside }
            return inside
        }
        val outers = loops.indices.filter { areas[it] * sign > 0 }
        val holesOf = HashMap<Int, MutableList<Int>>()
        for (h in loops.indices) {
            if (areas[h] * sign >= 0) continue
            val owner = outers.filter { contains(loops[it], loops[h][0], loops[h][1]) }.minByOrNull { Math.abs(areas[it]) }
                ?: throw IllegalArgumentException("the section's cut (${"xyz"[axis]} = $c mm) has a hole outside every outline: the model's surface is not consistently closed there")
            holesOf.getOrPut(owner) { ArrayList() }.add(h)
        }
        val uAx = (axis + 1) % 3; val vAx = (axis + 2) % 3
        val p3 = FloatArray(3)
        fun emit(x: Double, y: Double) { p3[axis] = c; p3[uAx] = x.toFloat(); p3[vAx] = y.toFloat(); out.add(p3[0], p3[1], p3[2]) }
        for (o in outers) triangulate(loops[o], holesOf[o].orEmpty().map { loops[it] }, sign) { ax, ay, bx, by, cx, cy -> emit(ax, ay); emit(bx, by); emit(cx, cy) }
    }

    /**
     * An outline with its holes ([outer], [holes]: u v u v ..., the outline running with [sign] (+1 counter-clockwise), holes
     * the other way) as triangles, each given in the outline's own direction: every hole joined to the outline by a bridge
     * (two edges, there and back, between a hole's point furthest along u and a point of the outline it sees), then ears
     * cut off one by one. Only the given points are used.
     */
    internal fun triangulate(outer: DoubleArray, holes: List<DoubleArray>, sign: Double, tri: (Double, Double, Double, Double, Double, Double) -> Unit) {
        val total = outer.size / 2 + holes.sumOf { it.size / 2 + 2 }
        val x = DoubleArray(total); val y = DoubleArray(total); val prev = IntArray(total); val next = IntArray(total)
        var n = 0
        fun ring(l: DoubleArray): Int { val s = n; val k = l.size / 2
            for (i in 0 until k) { x[n] = l[2 * i]; y[n] = sign * l[2 * i + 1]; prev[n] = s + (i + k - 1) % k; next[n] = s + (i + 1) % k; n++ }   // mirrored when sign is -1: the outline counter-clockwise here
            return s }
        val start = ring(outer)
        fun cross(a: Int, b: Int, c: Int) = (x[b] - x[a]) * (y[c] - y[a]) - (y[b] - y[a]) * (x[c] - x[a])
        fun inTri(ax: Double, ay: Double, bx: Double, by: Double, cx: Double, cy: Double, px: Double, py: Double): Boolean {
            val d1 = (bx - ax) * (py - ay) - (by - ay) * (px - ax); val d2 = (cx - bx) * (py - by) - (cy - by) * (px - bx); val d3 = (ax - cx) * (py - cy) - (ay - cy) * (px - cx)
            return d1 >= 0 && d2 >= 0 && d3 >= 0
        }
        // holes, the one reaching furthest along u first: each is joined to the outline as it stands (earlier holes already in it)
        val holeRings = holes.map { ring(it) }
        fun out(a: Int, b: Int, c: Int) = tri(x[a], sign * y[a], x[b], sign * y[b], x[c], sign * y[c])
        val holeStarts = holeRings.map { s -> var best = s; var i = next[s]; while (i != s) { if (x[i] > x[best]) best = i; i = next[i] }; best }.sortedByDescending { x[it] }
        for (mNode in holeStarts) {
            val mx = x[mNode]; val my = y[mNode]
            // the outline's edge the ray from the hole's point along +u meets first
            var bestX = Double.POSITIVE_INFINITY; var cand = -1
            var i = start
            do {
                val j = next[i]
                if (y[i] == my && y[j] == my) {
                    // an edge along the ray: its nearer end ahead of the hole's point
                    for (e in intArrayOf(i, j)) if (x[e] >= mx && x[e] < bestX) { bestX = x[e]; cand = e }
                } else if ((y[i] > my) != (y[j] > my)) {
                    val ix = x[i] + (my - y[i]) * (x[j] - x[i]) / (y[j] - y[i])
                    if (ix >= mx && ix < bestX) { bestX = ix; cand = if (x[i] > x[j]) i else j }
                }
                i = j
            } while (i != start)
            require(cand >= 0) { "a hole of the section's cut lies outside its outline" }
            // a point of the outline inside the triangle (hole point, where the ray landed, the edge's end) hides that end: the one nearest the ray's direction is seen
            var bridge = cand; var bestTan = Double.POSITIVE_INFINITY
            val cx0 = x[cand]; val cy0 = y[cand]
            i = start
            do {
                if (i != cand && x[i] >= mx && (x[i] != cx0 || y[i] != cy0)) {
                    val hit = if (cy0 >= my) inTri(mx, my, bestX, my, cx0, cy0, x[i], y[i]) else inTri(mx, my, cx0, cy0, bestX, my, x[i], y[i])
                    if (hit) { val t = Math.abs(y[i] - my) / (x[i] - mx).coerceAtLeast(1e-300)
                        if (t < bestTan || (t == bestTan && x[i] < x[bridge])) { bestTan = t; bridge = i } }
                }
                i = next[i]
            } while (i != start)
            // several points of the outline may lie at that place (the ends of an earlier bridge): the one whose corner opens
            // toward the hole's point (the outline's inside is on its left: between the way on and the way back)
            run {
                val bx = x[bridge]; val by = y[bridge]
                fun opensToward(k: Int): Boolean {
                    val ox = x[next[k]] - bx; val oy = y[next[k]] - by; val ix = x[prev[k]] - bx; val iy = y[prev[k]] - by; val dx = mx - bx; val dy = my - by
                    val afterOut = ox * dy - oy * dx >= 0; val beforeIn = dx * iy - dy * ix >= 0
                    return if (ox * iy - oy * ix > 0) afterOut && beforeIn else afterOut || beforeIn
                }
                if (!opensToward(bridge)) { var k = start; do { if (x[k] == bx && y[k] == by && opensToward(k)) { bridge = k; break }; k = next[k] } while (k != start) }
            }
            // splice: ... bridge -> hole point -> round the hole -> hole point again -> bridge again -> ...
            val m2 = n; x[n] = mx; y[n] = my; n++
            val b2 = n; x[n] = x[bridge]; y[n] = y[bridge]; n++
            val afterBridge = next[bridge]; val beforeM = prev[mNode]
            next[bridge] = mNode; prev[mNode] = bridge
            next[beforeM] = m2; prev[m2] = beforeM
            next[m2] = b2; prev[b2] = m2
            next[b2] = afterBridge; prev[afterBridge] = b2
        }
        // ears: a corner turning the outline's way with no other point of the outline inside it
        var left = 0; run { var i = start; do { left++; i = next[i] } while (i != start) }
        // strict first (a point on the corner's far edge blocks it); with none left, points in a line may go (a triangle of
        // no area, which keeps the edges shared) and only a point strictly inside blocks; with none then, it is refused
        fun strictlyIn(ax: Double, ay: Double, bx: Double, by: Double, cx: Double, cy: Double, px: Double, py: Double): Boolean {
            val d1 = (bx - ax) * (py - ay) - (by - ay) * (px - ax); val d2 = (cx - bx) * (py - by) - (cy - by) * (px - bx); val d3 = (ax - cx) * (py - cy) - (ay - cy) * (px - cx)
            return d1 > 0 && d2 > 0 && d3 > 0
        }
        // the area a float's step makes over the outline's size: a few steps at its largest coordinate times its extent
        val sliver = run {
            var lo = Double.MAX_VALUE; var hi = -Double.MAX_VALUE; var big = 0.0
            for (i in 0 until n) { lo = minOf(lo, x[i], y[i]); hi = maxOf(hi, x[i], y[i]); big = maxOf(big, Math.abs(x[i]), Math.abs(y[i])) }
            8.0 * Math.ulp(big.toFloat()) * (hi - lo)
        }
        var at = start; var sinceCut = 0; var loose = false
        while (left > 3) {
            val a = prev[at]; val b = at; val c = next[at]
            val turn = cross(a, b, c)
            var ear = turn > 0 || (loose && turn == 0.0)
            if (ear && turn > 0) {
                var k = next[c]
                while (k != a) {
                    val same = (x[k] == x[a] && y[k] == y[a]) || (x[k] == x[b] && y[k] == y[b]) || (x[k] == x[c] && y[k] == y[c])
                    if (!same && cross(prev[k], k, next[k]) <= 0 &&
                        (if (loose) strictlyIn(x[a], y[a], x[b], y[b], x[c], y[c], x[k], y[k]) else inTri(x[a], y[a], x[b], y[b], x[c], y[c], x[k], y[k]))) { ear = false; break }
                    k = next[k]
                }
            }
            if (ear) {
                out(a, b, c)
                next[a] = c; prev[c] = a; left--; at = c; sinceCut = 0; loose = false
            } else {
                at = c; sinceCut++
                if (sinceCut > left) {
                    if (!loose) { loose = true; sinceCut = 0; continue }
                    // still none: two of the cut's points a float's step apart can cross the outline over itself by that much
                    // (a needle whose two sides swap at its tip). The corner of least area, when that area is within what
                    // such a step makes over the outline's size, is cut off as it is (a sliver, either way round); a corner
                    // of real area means the outline truly crosses itself, which is refused
                    var k = at; var least = at; var leastTurn = Double.MAX_VALUE
                    do { val t = Math.abs(cross(prev[k], k, next[k])); if (t < leastTurn) { leastTurn = t; least = k }; k = next[k] } while (k != at)
                    require(leastTurn <= sliver) { "the section's cut could not be closed (its outline crosses itself): the model's surface is not a clean closed surface there" }
                    val a2 = prev[least]; val c3 = next[least]
                    out(a2, least, c3)
                    next[a2] = c3; prev[c3] = a2; left--; at = c3; sinceCut = 0; loose = false
                }
            }
        }
        val a = prev[at]; val c = next[at]
        out(a, at, c)
    }
}
