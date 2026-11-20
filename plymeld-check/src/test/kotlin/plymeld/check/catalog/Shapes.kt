package plymeld.check.catalog

import plymeld.check.testBox
import plymeld.mesh.TriangleSoup

/**
 * The solids the catalog's models are built from (mm): closed meshes made in code, each triangle's corners
 * counter-clockwise seen from outside.
 */
object Shapes {
    /** A soup of [tris] (each nine coordinates, three corners). */
    fun soup(tris: List<DoubleArray>) = TriangleSoup(FloatArray(tris.size * 9) { tris[it / 9][it % 9].toFloat() }, tris.size)

    /** A solid of [rows] (each a height and its counter-clockwise section, the same number of points in each), capped by fans from each end's middle (star-shaped ends). */
    fun loft(rows: List<Pair<Double, List<DoubleArray>>>): TriangleSoup {
        val tris = ArrayList<DoubleArray>()
        fun p(r: Int, i: Int) = rows[r].second[i].let { doubleArrayOf(it[0], it[1], rows[r].first) }
        val m = rows[0].second.size
        for (r in 0 until rows.size - 1) for (i in 0 until m) {
            val j = (i + 1) % m
            tris += p(r, i) + p(r, j) + p(r + 1, j); tris += p(r, i) + p(r + 1, j) + p(r + 1, i)
        }
        for ((r, up) in listOf(0 to false, rows.size - 1 to true)) {
            val sec = rows[r].second; val z = rows[r].first
            val c = doubleArrayOf(sec.sumOf { it[0] } / m, sec.sumOf { it[1] } / m, z)
            for (i in 0 until m) { val j = (i + 1) % m; tris += if (up) c + p(r, i) + p(r, j) else c + p(r, j) + p(r, i) }
        }
        return soup(tris)
    }

    /** Ear clipping of a simple counter-clockwise polygon: index triples. */
    fun ears(poly: List<DoubleArray>): List<IntArray> {
        val idx = poly.indices.toMutableList(); val out = ArrayList<IntArray>()
        fun cross(a: DoubleArray, b: DoubleArray, c: DoubleArray) = (b[0] - a[0]) * (c[1] - a[1]) - (b[1] - a[1]) * (c[0] - a[0])
        var guard = 0
        while (idx.size > 3 && guard++ < 100000) {
            var cut = false
            for (k in idx.indices) {
                val a = idx[(k + idx.size - 1) % idx.size]; val b = idx[k]; val c = idx[(k + 1) % idx.size]
                if (cross(poly[a], poly[b], poly[c]) <= 1e-12) continue
                if (idx.any { it != a && it != b && it != c && cross(poly[a], poly[b], poly[it]) >= 0 && cross(poly[b], poly[c], poly[it]) >= 0 && cross(poly[c], poly[a], poly[it]) >= 0 }) continue
                out += intArrayOf(a, b, c); idx.removeAt(k); cut = true; break
            }
            require(cut) { "not a simple counter-clockwise polygon" }
        }
        out += intArrayOf(idx[0], idx[1], idx[2])
        return out
    }

    /** An upright prism of the counter-clockwise polygon [poly] from [z0] to [z1]. */
    fun prism(poly: List<DoubleArray>, z0: Double, z1: Double): TriangleSoup {
        val tris = ArrayList<DoubleArray>(); val n = poly.size
        fun at(i: Int, z: Double) = doubleArrayOf(poly[i][0], poly[i][1], z)
        for (i in 0 until n) { val j = (i + 1) % n; tris += at(i, z0) + at(j, z0) + at(j, z1); tris += at(i, z0) + at(j, z1) + at(i, z1) }
        for (t in ears(poly)) { tris += at(t[0], z1) + at(t[1], z1) + at(t[2], z1); tris += at(t[0], z0) + at(t[2], z0) + at(t[1], z0) }
        return soup(tris)
    }

    /** A prism along Y from [y0] to [y1] of the X-Z [profile] (convex, counter-clockwise seen from -Y), every face outward. */
    fun prismAlongY(profile: List<Pair<Double, Double>>, y0: Double, y1: Double): TriangleSoup {
        val tris = ArrayList<DoubleArray>()
        fun at(i: Int, y: Double) = doubleArrayOf(profile[i].first, y, profile[i].second)
        val n = profile.size
        for (i in 1 until n - 1) { tris += at(0, y0) + at(i, y0) + at(i + 1, y0); tris += at(0, y1) + at(i + 1, y1) + at(i, y1) }
        for (i in 0 until n) { val j = (i + 1) % n; tris += at(i, y0) + at(j, y1) + at(j, y0); tris += at(i, y0) + at(i, y1) + at(j, y1) }
        return soup(tris)
    }

    /** [s] moved by [dx], [dy] (mm). */
    fun moved(s: TriangleSoup, dx: Double, dy: Double) = TriangleSoup(FloatArray(s.coords.size) { k ->
        when (k % 3) { 0 -> (s.coords[k] + dx).toFloat(); 1 -> (s.coords[k] + dy).toFloat(); else -> s.coords[k] } }, s.count)
    fun join(vararg s: TriangleSoup) = TriangleSoup(s.flatMap { it.coords.asList() }.toFloatArray(), s.sumOf { it.count })
    fun box(x0: Double, y0: Double, z0: Double, x1: Double, y1: Double, z1: Double) = testBox(x0.toFloat(), y0.toFloat(), z0.toFloat(), x1.toFloat(), y1.toFloat(), z1.toFloat())

    /** The union of [shapes] (mm, counter-clockwise), which must be one polygon with no hole: its points. */
    fun union(shapes: List<List<DoubleArray>>): List<DoubleArray> {
        val ps = shapes.map { s -> plymeld.geom.Polygon(LongArray(2 * s.size) { k -> Math.round(s[k / 2][k % 2] * 1000) }) }
        val u = plymeld.geom.Booleans.unionPositive(ps).single()
        return (0 until u.size).map { doubleArrayOf(u.x(it) / 1000.0, u.y(it) / 1000.0) }
    }
    fun ellipse(x: Double, y: Double, a: Double, b: Double, n: Int = 72) = (0 until n).map { k -> val t = 2 * Math.PI * k / n; doubleArrayOf(x + a * Math.cos(t), y + b * Math.sin(t)) }
    fun rect(x0: Double, y0: Double, x1: Double, y1: Double) = listOf(doubleArrayOf(x0, y0), doubleArrayOf(x1, y0), doubleArrayOf(x1, y1), doubleArrayOf(x0, y1))

    /** A tube round ([cx], [cy]) from radius [rIn] to [rOut], [z0] to [z1], its circles of [n] points. */
    fun tube(cx: Double, cy: Double, rOut: Double, rIn: Double, z0: Double, z1: Double, n: Int = 120): TriangleSoup {
        val tris = ArrayList<DoubleArray>()
        fun pt(r: Double, k: Int, z: Double) = doubleArrayOf(cx + r * Math.cos(2 * Math.PI * k / n), cy + r * Math.sin(2 * Math.PI * k / n), z)
        for (k in 0 until n) { val j = (k + 1) % n
            tris += pt(rOut, k, z0) + pt(rOut, j, z0) + pt(rOut, j, z1); tris += pt(rOut, k, z0) + pt(rOut, j, z1) + pt(rOut, k, z1)
            tris += pt(rIn, k, z0) + pt(rIn, k, z1) + pt(rIn, j, z1); tris += pt(rIn, k, z0) + pt(rIn, j, z1) + pt(rIn, j, z0)
            tris += pt(rIn, k, z1) + pt(rOut, k, z1) + pt(rOut, j, z1); tris += pt(rIn, k, z1) + pt(rOut, j, z1) + pt(rIn, j, z1)
            tris += pt(rIn, k, z0) + pt(rOut, j, z0) + pt(rOut, k, z0); tris += pt(rIn, k, z0) + pt(rIn, j, z0) + pt(rOut, j, z0) }
        return soup(tris)
    }

    /** How far from ([cx], [cy]) along direction [a] (rad) the convex counter-clockwise polygon [poly], which holds that point, ends. */
    fun convexReach(poly: List<DoubleArray>, cx: Double, cy: Double, a: Double): Double {
        val dx = Math.cos(a); val dy = Math.sin(a)
        var t = Double.MAX_VALUE
        for (i in poly.indices) {
            val p = poly[i]; val q = poly[(i + 1) % poly.size]
            // the edge's outward normal (the polygon counter-clockwise)
            val nx = q[1] - p[1]; val ny = -(q[0] - p[0])
            val dn = dx * nx + dy * ny
            if (dn > 1e-12) t = minOf(t, ((p[0] - cx) * nx + (p[1] - cy) * ny) / dn)
        }
        return t
    }

    /**
     * An upright solid from [z0] to [z1] of the convex counter-clockwise polygon [outer] with the convex polygon [hole] taken
     * out of it, both holding ([cx], [cy]): the two sampled on the same rays from that point ([n] even ones and every corner of
     * either), joined by quads.
     */
    fun frame(outer: List<DoubleArray>, hole: List<DoubleArray>, cx: Double, cy: Double, z0: Double, z1: Double, n: Int = 96): TriangleSoup {
        fun angle(q: DoubleArray) = Math.atan2(q[1] - cy, q[0] - cx).let { a -> if (a < 0) a + 2 * Math.PI else a }
        val angles = ((0 until n).map { 2 * Math.PI * it / n } + outer.map { angle(it) } + hole.map { angle(it) }).sorted()
            .fold(ArrayList<Double>()) { acc, a -> if (acc.isEmpty() || a - acc.last() > 1e-9) acc += a; acc }
        val o = angles.map { a -> val t = convexReach(outer, cx, cy, a); doubleArrayOf(cx + t * Math.cos(a), cy + t * Math.sin(a)) }
        val h = angles.map { a -> val t = convexReach(hole, cx, cy, a); doubleArrayOf(cx + t * Math.cos(a), cy + t * Math.sin(a)) }
        val tris = ArrayList<DoubleArray>(); val m = angles.size
        fun p(q: DoubleArray, z: Double) = doubleArrayOf(q[0], q[1], z)
        for (k in 0 until m) { val j = (k + 1) % m
            tris += p(o[k], z0) + p(o[j], z0) + p(o[j], z1); tris += p(o[k], z0) + p(o[j], z1) + p(o[k], z1)
            tris += p(h[k], z0) + p(h[k], z1) + p(h[j], z1); tris += p(h[k], z0) + p(h[j], z1) + p(h[j], z0)
            tris += p(h[k], z1) + p(o[k], z1) + p(o[j], z1); tris += p(h[k], z1) + p(o[j], z1) + p(h[j], z1)
            tris += p(h[k], z0) + p(o[j], z0) + p(o[k], z0); tris += p(h[k], z0) + p(h[j], z0) + p(o[j], z0) }
        return soup(tris)
    }

    /**
     * The section at height [z] of the convex solid that is the intersection of the half-spaces [hs] (each a, b, c, d:
     * a x + b y + c z <= d), as a counter-clockwise polygon (mm), or null where it is empty: the square [lim] mm about the
     * origin clipped by each half-space's half-plane at [z].
     */
    fun convexSection(hs: List<DoubleArray>, z: Double, lim: Double = 1000.0): List<DoubleArray>? {
        var poly = listOf(doubleArrayOf(-lim, -lim), doubleArrayOf(lim, -lim), doubleArrayOf(lim, lim), doubleArrayOf(-lim, lim))
        for (h in hs) {
            val a = h[0]; val b = h[1]; val d = h[3] - h[2] * z
            fun f(p: DoubleArray) = a * p[0] + b * p[1] - d
            val out = ArrayList<DoubleArray>()
            for (i in poly.indices) {
                val p = poly[i]; val q = poly[(i + 1) % poly.size]
                val fp = f(p); val fq = f(q)
                if (fp <= 0) out += p
                if ((fp < 0 && fq > 0) || (fp > 0 && fq < 0)) { val t = fp / (fp - fq); out += doubleArrayOf(p[0] + t * (q[0] - p[0]), p[1] + t * (q[1] - p[1])) }
            }
            if (out.size < 3) return null
            poly = out
        }
        return poly
    }

    /** [poly] (mm) as a polygon of the geometry kernel (µm), counter-clockwise. */
    fun um(poly: List<DoubleArray>): plymeld.geom.Polygon =
        plymeld.geom.Polygon(LongArray(2 * poly.size) { k -> Math.round(poly[k / 2][k % 2] * 1000) }).let { if (it.isCcw()) it else it.reversed() }

    /**
     * A solid of horizontal slabs: slab k from [zs] k to k + 1, its outline [section] at the slab's middle (µm, as the kernel's
     * booleans give it: outer outlines counter-clockwise, holes clockwise). A model sliced at each layer's middle, with a slab a
     * layer, is cut there exactly as the solid the sections describe. Each slab is cut across X through every hole's middle into
     * pieces with no hole; each piece's top and bottom are ear-clipped, and every edge of a piece but those on a cut is a
     * wall, so the walls and the caps share every edge (no T-junction).
     */
    fun slabs(zs: List<Double>, section: (Double) -> List<plymeld.geom.Polygon>): TriangleSoup {
        val tris = ArrayList<DoubleArray>()
        for (k in 0 until zs.size - 1) {
            val z0 = zs[k]; val z1 = zs[k + 1]
            val sec = section((z0 + z1) / 2).filter { it.size >= 3 }
            if (sec.isEmpty()) continue
            var bx0 = Long.MAX_VALUE; var by0 = Long.MAX_VALUE; var bx1 = Long.MIN_VALUE; var by1 = Long.MIN_VALUE
            for (p in sec) { val b = p.bounds(); bx0 = minOf(bx0, b[0]); by0 = minOf(by0, b[1]); bx1 = maxOf(bx1, b[2]); by1 = maxOf(by1, b[3]) }
            val cuts = sec.filter { !it.isCcw() }.map { val b = it.bounds(); (b[0] + b[2]) / 2 }.distinct().sorted()
            val edges = listOf(bx0 - 1000) + cuts + listOf(bx1 + 1000)
            for (bnd in 0 until edges.size - 1) {
                val rect = plymeld.geom.Polygon(longArrayOf(edges[bnd], by0 - 1000, edges[bnd + 1], by0 - 1000, edges[bnd + 1], by1 + 1000, edges[bnd], by1 + 1000))
                for (piece in plymeld.geom.Booleans.intersection(sec, listOf(rect))) {
                    require(piece.isCcw()) { "a slab's piece with a hole at z $z0: cut it" }
                    // the piece without points where it runs straight on (exact, in µm)
                    val n = piece.size
                    val keep = (0 until n).filter { i ->
                        val a = (i + n - 1) % n; val c = (i + 1) % n
                        (piece.x(i) - piece.x(a)) * (piece.y(c) - piece.y(a)) - (piece.y(i) - piece.y(a)) * (piece.x(c) - piece.x(a)) != 0L }
                    val pts = keep.map { doubleArrayOf(piece.x(it) / 1000.0, piece.y(it) / 1000.0) }
                    val xs = keep.map { piece.x(it) }
                    if (pts.size < 3) continue
                    fun at(i: Int, z: Double) = doubleArrayOf(pts[i][0], pts[i][1], z)
                    val m = pts.size
                    for (i in 0 until m) { val j = (i + 1) % m
                        val onCut = xs[i] == xs[j] && (xs[i] == edges[bnd] || xs[i] == edges[bnd + 1]) && xs[i] in cuts
                        if (!onCut) { tris += at(i, z0) + at(j, z0) + at(j, z1); tris += at(i, z0) + at(j, z1) + at(i, z1) } }
                    for (t in ears(pts)) { tris += at(t[0], z1) + at(t[1], z1) + at(t[2], z1); tris += at(t[0], z0) + at(t[2], z0) + at(t[1], z0) }
                }
            }
        }
        return soup(tris)
    }

    /** A cylinder round ([cx], [cy]) of radius [r] from [z0] to [z1], its circle of [n] points. */
    fun cylinder(cx: Double, cy: Double, r: Double, z0: Double, z1: Double, n: Int = 96) =
        prism((0 until n).map { k -> val a = 2 * Math.PI * k / n; doubleArrayOf(cx + r * Math.cos(a), cy + r * Math.sin(a)) }, z0, z1)

    /**
     * A block from ([x0], [y0]) to ([x1], [y1]), [z0] to [z1], with round holes through it in one row along X (each x, y,
     * radius, left to right). The block is cut into strips across X, one round each hole (the cuts halfway between neighboring
     * holes' edges); each strip's top and bottom are fans of quads between its hole and its sides on rays from the hole's
     * center (a strip is star-shaped about it), the block's corners and the cuts' points shared by both strips beside a cut
     * among them, so the caps meet with no T-junction; the cuts themselves have no wall.
     */
    fun blockWithHoles(x0: Double, y0: Double, x1: Double, y1: Double, z0: Double, z1: Double, holes: List<DoubleArray>, n: Int = 96): TriangleSoup {
        require(holes.zipWithNext().all { (a, b) -> a[0] < b[0] }) { "holes along X, left to right" }
        val xs = listOf(x0) + holes.zipWithNext().map { (a, b) -> (a[0] + a[2] + b[0] - b[2]) / 2 } + listOf(x1)
        // the points on every cut and on the block's ends (12 steps from front to back), both strips beside a cut using them
        val ys = (0..12).map { y0 + (y1 - y0) * it / 12 }
        val tris = ArrayList<DoubleArray>()
        fun p(q: DoubleArray, z: Double) = doubleArrayOf(q[0], q[1], z)
        for ((k, h) in holes.withIndex()) {
            val sx0 = xs[k]; val sx1 = xs[k + 1]; val hx = h[0]; val hy = h[1]; val r = h[2]
            require(hx - r > sx0 && hx + r < sx1 && hy - r > y0 && hy + r < y1) { "hole $k does not fit its strip" }
            fun angle(q: DoubleArray) = Math.atan2(q[1] - hy, q[0] - hx).let { a -> if (a < 0) a + 2 * Math.PI else a }
            // the strip's outline: its two cut / end sides at the shared points, its front and back sampled on rays
            val anchors = ys.map { doubleArrayOf(sx0, it) } + ys.map { doubleArrayOf(sx1, it) }
            val rays = (0 until n).map { 2 * Math.PI * it / n }.mapNotNull { a ->
                val dx = Math.cos(a); val dy = Math.sin(a)
                val tx = if (dx > 1e-12) (sx1 - hx) / dx else if (dx < -1e-12) (sx0 - hx) / dx else Double.MAX_VALUE
                val ty = if (dy > 1e-12) (y1 - hy) / dy else if (dy < -1e-12) (y0 - hy) / dy else Double.MAX_VALUE
                if (ty < tx - 1e-9) doubleArrayOf(hx + ty * dx, if (dy > 0) y1 else y0) else null
            }
            val outer = (anchors + rays).sortedBy { angle(it) }
            val inner = outer.map { q -> val a = angle(q); doubleArrayOf(hx + r * Math.cos(a), hy + r * Math.sin(a)) }
            val m = outer.size
            for (i in 0 until m) { val j = (i + 1) % m
                val oi = outer[i]; val oj = outer[j]
                // a wall on the block's own sides, none on a cut between two strips
                val onCut = (k > 0 && oi[0] == sx0 && oj[0] == sx0) || (k < holes.size - 1 && oi[0] == sx1 && oj[0] == sx1)
                if (!onCut) { tris += p(oi, z0) + p(oj, z0) + p(oj, z1); tris += p(oi, z0) + p(oj, z1) + p(oi, z1) }
                tris += p(inner[i], z0) + p(inner[i], z1) + p(inner[j], z1); tris += p(inner[i], z0) + p(inner[j], z1) + p(inner[j], z0)
                tris += p(inner[i], z1) + p(oi, z1) + p(oj, z1); tris += p(inner[i], z1) + p(oj, z1) + p(inner[j], z1)
                tris += p(inner[i], z0) + p(oj, z0) + p(oi, z0); tris += p(inner[i], z0) + p(inner[j], z0) + p(oj, z0) }
        }
        return soup(tris)
    }
}
