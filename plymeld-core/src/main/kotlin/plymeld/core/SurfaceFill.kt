package plymeld.core

import plymeld.geom.Offset
import plymeld.geom.Polygon
import plymeld.geom.Polygons

/**
 * Surface fill patterns beyond parallel lines, as 3MF projects set them per part (e.g. concentric
 * internal solid, Archimedean-chords top and bottom).
 *
 *  - [concentric]: closed loops following the region's edge inward, one line spacing apart;
 *    [concentricCut] centers them on each piece's outer edge and cuts them at its holes.
 *  - [archimedeanChords]: one Archimedean spiral (radius grows one spacing per turn) about the
 *    center of each connected piece's bounding box, drawn as straight chords and clipped to the
 *    region; what falls in holes is left out.
 */
object SurfaceFill {
    /** Line patterns: filled by parallel hatching (the planner's own). */
    val LINE_PATTERNS = setOf("monotonic", "monotonicline", "rectilinear", "zig-zag", "alignedrectilinear", "line")

    /** Chord tolerance of the spiral (sagitta), µm: a geometric tolerance, not a print parameter. */
    private const val CHORD_TOL_UM = 10.0

    /** Simplification of the concentric reference outline (µm): a geometric tolerance, 40x under a 0.4 mm line. */
    private const val CONCENTRIC_TOL_UM = 10L

    /** Re-anchor every this many rings: a speed/accuracy balance, measured in ConcentricDriftTest. */
    private const val ANCHOR_EVERY = 10

    /**
     * Concentric loops: the first half a spacing inside the edge, then one spacing apart. The
     * reference outline is simplified to within [CONCENTRIC_TOL_UM]; every [ANCHOR_EVERY]th ring is
     * offset from it directly, the rings between from the ring before. Measured on a 4000-vertex
     * ellipse (ConcentricDriftTest): worst error 8.7 um in 0.9 s. The alternatives:
     *  - every ring from the previous one: the offset's grid rounding compounds; rings collapsed to
     *    12-gons 9.8 mm off (radial "spokes" on a large oval);
     *  - every ring from the outline: accurate but 7 s (simplified) to ~30 s per ring (unsimplified,
     *    > 600 s on one layer of a large oval): the offset's work grows with vertices x distance.
     */
    fun concentric(region: List<Polygon>, spacing: Long): List<Polygon> {
        val out = ArrayList<Polygon>()
        val ref = region.map { it.douglasPeucker(CONCENTRIC_TOL_UM) }.filter { it.size >= 3 }
        var ring: List<Polygon> = emptyList()
        var k = 0
        while (true) {
            ring = (if (k % ANCHOR_EVERY == 0) Offset.offset(ref, -(spacing * k + spacing / 2)) else Offset.offset(ring, -spacing))
                .filter { it.size >= 3 && Math.abs(it.signedAreaMm2()) > 0.001 }
            if (ring.isEmpty()) return out
            out += ring
            k++
        }
    }

    /** A ring or a piece of one: [xy] flat x y ... in µm; [closed] when the ring crosses no hole. */
    class Piece(val xy: LongArray, val closed: Boolean)

    /**
     * Rings in the order laid ([pieces]), each closed ring after the first started at its point nearest where the path
     * so far ended and joined onto it when that is at most [maxStepUm] away (the rings beside it: one spacing apart), so a
     * run of rings is ONE path, one start and one end, the steps between rings laid as they go: a radial line of starts
     * and stops (each ring's own start at the back) was the mark a concentric top left. A run of one ring stays a closed
     * loop ([Piece.closed]); an open piece (cut by a hole) and a ring further away start a path of their own.
     */
    fun joined(pieces: List<Piece>, maxStepUm: Long): List<Piece> {
        val out = ArrayList<Piece>()
        var run = ArrayList<Long>(); var rings = 0; var first: LongArray? = null
        fun flush() {
            if (rings == 1) out += Piece(first!!, true) else if (rings > 1) out += Piece(run.toLongArray(), false)
            run = ArrayList(); rings = 0; first = null
        }
        for (p in pieces) {
            if (!p.closed) { flush(); out += p; continue }
            val n = p.xy.size / 2
            var start = 0
            if (rings > 0) {
                val ex = run[run.size - 2]; val ey = run[run.size - 1]
                var best = Double.MAX_VALUE
                for (i in 0 until n) { val d = Math.hypot((p.xy[2 * i] - ex).toDouble(), (p.xy[2 * i + 1] - ey).toDouble()); if (d < best) { best = d; start = i } }
                if (best > maxStepUm) { flush(); start = 0 }
            }
            // the ring from its start round to it again
            for (k in 0..n) { val i = (start + k) % n; run += p.xy[2 * i]; run += p.xy[2 * i + 1] }
            if (rings == 0) first = p.xy
            rings++
        }
        flush()
        return out
    }

    /**
     * Concentric rings centered on each connected piece (around its center, not the nearest hole): rings from the piece's OUTER edge only ([concentric]), cut
     * where they cross a hole grown by half a spacing, so a line's edge meets a hole's edge as it
     * meets the outer one. Rings that cross no hole stay closed; the rest are open pieces, each
     * longer than [minLenUm] (shorter ones are blobs, as for the spiral's chords).
     */
    fun concentricCut(region: List<Polygon>, spacing: Long, minLenUm: Long): List<Piece> {
        val out = ArrayList<Piece>()
        for ((outer, holes) in Polygons.group(region)) {
            val rings = concentric(listOf(outer), spacing)
            if (holes.isEmpty()) { rings.forEach { out += Piece(it.xy, true) }; continue }
            val keepOut = Offset.offset(holes.map { it.reversed() }, spacing / 2)    // holes as areas, half a line wider
            val boxes = keepOut.map { it.bounds() }
            fun inKeepOut(x: Double, y: Double): Boolean {
                var w = 0
                for ((j, k) in keepOut.withIndex()) {
                    val b = boxes[j]
                    if (x < b[0] || x > b[2] || y < b[1] || y > b[3]) continue
                    if (Polygons.contains(k, Math.round(x), Math.round(y))) w += if (k.isCcw()) 1 else -1
                }
                return w > 0
            }
            for (r in rings) cut(r, keepOut, boxes, ::inKeepOut, minLenUm, out)
        }
        return out
    }

    /** [ring] split where it crosses [keepOut]'s edges; the stretches outside go to [out] (see [concentricCut]). */
    private fun cut(ring: Polygon, keepOut: List<Polygon>, boxes: List<LongArray>, inside: (Double, Double) -> Boolean, minLenUm: Long, out: MutableList<Piece>) {
        val n = ring.size
        // the ring as points with, for each stretch to the next point, whether it lies outside
        val px = ArrayList<Double>(); val py = ArrayList<Double>(); val keep = ArrayList<Boolean>()
        var crosses = false
        for (i in 0 until n) {
            val ax = ring.x(i).toDouble(); val ay = ring.y(i).toDouble()
            val bx = ring.x((i + 1) % n).toDouble(); val by = ring.y((i + 1) % n).toDouble()
            val ts = ArrayList<Double>()
            val lox = minOf(ax, bx); val hix = maxOf(ax, bx); val loy = minOf(ay, by); val hiy = maxOf(ay, by)
            for ((j, k) in keepOut.withIndex()) {
                val b = boxes[j]
                if (hix < b[0] || lox > b[2] || hiy < b[1] || loy > b[3]) continue
                for (e in 0 until k.size) {
                    val cx = k.x(e).toDouble(); val cy = k.y(e).toDouble(); val dx = k.x((e + 1) % k.size).toDouble(); val dy = k.y((e + 1) % k.size).toDouble()
                    val den = (bx - ax) * (dy - cy) - (by - ay) * (dx - cx)
                    if (den == 0.0) continue
                    val t = ((cx - ax) * (dy - cy) - (cy - ay) * (dx - cx)) / den
                    val u = ((cx - ax) * (by - ay) - (cy - ay) * (bx - ax)) / den
                    if (t > 0 && t < 1 && u >= 0 && u <= 1) ts += t
                }
            }
            ts.sort()
            var t0 = 0.0
            for (t in ts + 1.0) {
                px += ax + (bx - ax) * t0; py += ay + (by - ay) * t0
                val tm = (t0 + t) / 2
                keep += !inside(ax + (bx - ax) * tm, ay + (by - ay) * tm)
                t0 = t
            }
            if (ts.isNotEmpty()) crosses = true
        }
        val m = px.size
        if (!crosses && keep.all { it }) { out += Piece(ring.xy, true); return }
        if (keep.none { it }) return
        // runs of kept stretches, starting just after a dropped one so no run wraps the start
        val start = (0 until m).first { !keep[it] }
        var cur: ArrayList<Long>? = null
        fun flush() {
            val c = cur ?: return
            var len = 0.0
            for (q in 2 until c.size step 2) len += Math.hypot((c[q] - c[q - 2]).toDouble(), (c[q + 1] - c[q - 1]).toDouble())
            if (c.size >= 4 && len > minLenUm) out += Piece(c.toLongArray(), false)
            cur = null
        }
        for (s in 1..m) {
            val i = (start + s) % m
            if (!keep[i]) { flush(); continue }
            val c = cur ?: ArrayList<Long>().also { it.add(Math.round(px[i])); it.add(Math.round(py[i])); cur = it }
            val nx = Math.round(px[(i + 1) % m]); val ny = Math.round(py[(i + 1) % m])
            c.add(nx); c.add(ny)
        }
        flush()
    }

    /**
     * Circles around one middle (where a full circle does not fit, as toward an oval's ends, arcs), [spacing] apart, the first [spacing] / 2
     * from the middle, clipped to [region]: a full circle where it fits, arcs where it does not. The middle is that of each
     * piece's outline's box (a top with parts standing on it is centered on its outline, not on them). Each circle starts at
     * its back (the seams in one line) and runs counter-clockwise; polylines (flat x y ... in µm), each longer than
     * [minLenUm]. The surface pattern "concentric-inside-out"; unlike
     * "concentric-outside-in" ([concentricCut]: each loop the one outside it moved in, so an oval's rings sharpen at its
     * ends) the rings stay round.
     */
    fun circles(region: List<Polygon>, spacing: Long, minLenUm: Long): List<LongArray> {
        val out = ArrayList<LongArray>()
        for ((outer, holes) in Polygons.group(region)) {
            val piece = listOf(outer) + holes
            val b = outer.bounds()
            val cx = (b[0] + b[2]) / 2.0; val cy = (b[1] + b[3]) / 2.0
            val rMax = Math.hypot((b[2] - b[0]) / 2.0, (b[3] - b[1]) / 2.0)
            var k = 0
            while (true) {
                val r = spacing * (k + 0.5)
                if (r > rMax) break
                // vertices so a chord strays at most CHORD_TOL_UM from the circle
                val step = if (r <= CHORD_TOL_UM) Math.PI / 4 else minOf(Math.PI / 4, 2 * Math.sqrt(2 * CHORD_TOL_UM / r))
                val n = Math.ceil(2 * Math.PI / step).toInt()
                val pts = ArrayList<Double>(2 * n + 2)
                for (i in 0..n) { val a = Math.PI / 2 + 2 * Math.PI * i / n; pts.add(cx + r * Math.cos(a)); pts.add(cy + r * Math.sin(a)) }
                // a circle cut by a hole starts inside at its first point and ends there: its last arc and its first are
                // one arc through the start (they were two paths, a stop and a start at the start point)
                val arcs = clip(pts, piece, 0).toMutableList()
                if (arcs.size >= 2) {
                    val first = arcs.first(); val last = arcs.last()
                    if (first[0] == last[last.size - 2] && first[1] == last[last.size - 1]) {
                        arcs[arcs.size - 1] = last + first.copyOfRange(2, first.size)
                        arcs.removeAt(0)
                    }
                }
                for (a in arcs) {
                    var len = 0.0
                    for (q in 2 until a.size step 2) len += Math.hypot((a[q] - a[q - 2]).toDouble(), (a[q + 1] - a[q - 1]).toDouble())
                    if (len >= minLenUm) out += a
                }
                k++
            }
        }
        return out
    }

    /** The spiral clipped to [region], as polylines (flat x y ... in µm), each longer than [minLenUm]. */
    fun archimedeanChords(region: List<Polygon>, spacing: Long, minLenUm: Long): List<LongArray> {
        val out = ArrayList<LongArray>()
        for ((outer, holes) in Polygons.group(region)) {
            val piece = listOf(outer) + holes
            val b = outer.bounds()
            val cx = (b[0] + b[2]) / 2.0; val cy = (b[1] + b[3]) / 2.0
            val rMax = Math.hypot((b[2] - b[0]) / 2.0, (b[3] - b[1]) / 2.0) + spacing
            val a = spacing / (2 * Math.PI)                     // r = a * theta
            val pts = ArrayList<Double>()
            var th = 0.0
            while (a * th <= rMax) {
                val r = a * th
                pts.add(cx + r * Math.cos(th)); pts.add(cy + r * Math.sin(th))
                // angle step so the chord's sagitta stays within the tolerance (large near the center)
                th += if (r < CHORD_TOL_UM) 0.5 else minOf(0.5, 2 * Math.sqrt(2 * CHORD_TOL_UM / r))
            }
            out += clip(pts, piece, minLenUm)
        }
        return out
    }

    /** Clips a polyline (flat x y ... doubles) to a polygon set: inside pieces, crossing parity along it (also Gyroid's). */
    internal fun clip(pts: List<Double>, piece: List<Polygon>, minLenUm: Long): List<LongArray> {
        val edges = EdgeGrid(piece)
        val result = ArrayList<LongArray>()
        var cur = ArrayList<Long>()
        fun flush() {
            if (cur.size >= 4) {
                var len = 0.0
                for (k in 2 until cur.size step 2) len += Math.hypot((cur[k] - cur[k - 2]).toDouble(), (cur[k + 1] - cur[k - 1]).toDouble())
                if (len >= minLenUm) result.add(cur.toLongArray())
            }
            cur = ArrayList()
        }
        fun add(x: Double, y: Double) { cur.add(Math.round(x)); cur.add(Math.round(y)) }
        val m = pts.size / 2
        if (m < 2) return result
        val groups = Polygons.group(piece)
        fun insideAt(x: Double, y: Double): Boolean {
            val xi = Math.round(x); val yi = Math.round(y)
            return groups.any { (o, hs) -> Polygons.contains(o, xi, yi) && hs.none { Polygons.contains(it, xi, yi) } }
        }
        var inside = insideAt(pts[0], pts[1])
        if (inside) add(pts[0], pts[1])
        for (k in 1 until m) {
            val x0 = pts[2 * k - 2]; val y0 = pts[2 * k - 1]; val x1 = pts[2 * k]; val y1 = pts[2 * k + 1]
            for (t in edges.crossings(x0, y0, x1, y1)) {
                val x = x0 + t * (x1 - x0); val y = y0 + t * (y1 - y0)
                if (inside) { add(x, y); flush() } else add(x, y)
                inside = !inside
            }
            if (inside) add(x1, y1)
        }
        flush()
        return result
    }

    /** Region edges binned on a grid, for segment crossings (parameters along the segment, sorted). */
    private class EdgeGrid(piece: List<Polygon>) {
        private val ex = ArrayList<DoubleArray>()           // x0 y0 x1 y1
        private val cell: Double
        private val x0: Double; private val y0: Double; private val nx: Int; private val ny: Int
        private val bins: Array<MutableList<Int>>
        init {
            var minX = Double.MAX_VALUE; var minY = Double.MAX_VALUE; var maxX = -Double.MAX_VALUE; var maxY = -Double.MAX_VALUE
            for (p in piece) for (i in 0 until p.size) {
                val j = (i + 1) % p.size
                val e = doubleArrayOf(p.x(i).toDouble(), p.y(i).toDouble(), p.x(j).toDouble(), p.y(j).toDouble())
                ex.add(e)
                minX = minOf(minX, e[0]); maxX = maxOf(maxX, e[0]); minY = minOf(minY, e[1]); maxY = maxOf(maxY, e[1])
            }
            // about 4 edges per cell on average: a search budget, not a print parameter
            cell = maxOf(100.0, Math.sqrt((maxX - minX + 1) * (maxY - minY + 1) * 4 / maxOf(1, ex.size)))
            x0 = minX; y0 = minY
            nx = ((maxX - minX) / cell).toInt() + 1; ny = ((maxY - minY) / cell).toInt() + 1
            bins = Array(nx * ny) { ArrayList() }
            for ((k, e) in ex.withIndex()) forCells(e[0], e[1], e[2], e[3]) { bins[it].add(k) }
        }
        private inline fun forCells(ax: Double, ay: Double, bx: Double, by: Double, f: (Int) -> Unit) {
            val i0 = ((minOf(ax, bx) - x0) / cell).toInt().coerceIn(0, nx - 1); val i1 = ((maxOf(ax, bx) - x0) / cell).toInt().coerceIn(0, nx - 1)
            val j0 = ((minOf(ay, by) - y0) / cell).toInt().coerceIn(0, ny - 1); val j1 = ((maxOf(ay, by) - y0) / cell).toInt().coerceIn(0, ny - 1)
            for (i in i0..i1) for (j in j0..j1) f(i * ny + j)
        }
        /** Parameters t in [0, 1) where segment a->b crosses an edge (edge parameter half-open, so a shared vertex counts once). */
        fun crossings(ax: Double, ay: Double, bx: Double, by: Double): List<Double> {
            val seen = HashSet<Int>(); val ts = ArrayList<Double>()
            forCells(ax, ay, bx, by) { c ->
                for (k in bins[c]) if (seen.add(k)) {
                    val e = ex[k]
                    val dx = bx - ax; val dy = by - ay; val fx = e[2] - e[0]; val fy = e[3] - e[1]
                    val den = dx * fy - dy * fx
                    if (den == 0.0) continue
                    val t = ((e[0] - ax) * fy - (e[1] - ay) * fx) / den
                    val u = ((e[0] - ax) * dy - (e[1] - ay) * dx) / den
                    if (t >= 0 && t < 1 && u >= 0 && u < 1) ts.add(t)
                }
            }
            ts.sort()
            return ts
        }
    }
}
