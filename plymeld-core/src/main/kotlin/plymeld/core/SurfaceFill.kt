package plymeld.core

import plymeld.geom.Booleans
import plymeld.geom.Offset
import plymeld.geom.Polygon
import plymeld.geom.Polygons

/**
 * Surface fill patterns beyond parallel lines, as 3MF projects set them per part (e.g. concentric
 * internal solid, Archimedean-chords top and bottom).
 *
 *  - [concentric]: closed loops following the region's edge inward, one line spacing apart;
 *    [concentricCut] centers them on each piece's outer edge and cuts them at its holes.
 *  - [circles]: round rings around each piece's middle, arcs where they do not fit, each running on past its arc
 *    along a wall, narrowed to the ground left there.
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
     *  - every ring from the previous one: the offset's grid rounding compounds; rings collapse to
     *    coarse polygons millimeters off (radial "spokes");
     *  - every ring from the outline: accurate but seconds per ring (simplified) to minutes per layer
     *    (unsimplified): the offset's work grows with vertices x distance.
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

    /**
     * A ring or a piece of one: [xy] flat x y ... in µm; [closed] when the ring crosses no hole; [widths], the line's width
     * at each point (µm) where it narrows somewhere ([circles] beside a wall), null = the full width throughout.
     */
    class Piece(val xy: LongArray, val closed: Boolean, val widths: LongArray? = null)

    /**
     * Rings in the order laid ([pieces]), each closed ring after the first started at its point nearest where the path
     * so far ended and joined onto it when that is at most [maxStepUm] away (the rings beside it: one spacing apart), so a
     * run of rings is ONE path, one start and one end, the steps between rings laid as they go: a radial line of starts
     * and stops (each ring's own start at the back) was the mark a concentric top left. A run of one ring stays a closed
     * loop ([Piece.closed]); an open piece (cut by a hole) and a ring further away start a path of their own. A run in
     * which a ring narrows carries the widths, [widthUm] at the points of the rings that do not.
     */
    fun joined(pieces: List<Piece>, maxStepUm: Long, widthUm: Long = 0L): List<Piece> {
        val out = ArrayList<Piece>()
        var run = ArrayList<Long>(); var runW = ArrayList<Long>(); var narrows = false; var rings = 0; var first: Piece? = null
        fun flush() {
            if (rings == 1) out += first!! else if (rings > 1) out += Piece(run.toLongArray(), false, if (narrows) runW.toLongArray() else null)
            run = ArrayList(); runW = ArrayList(); narrows = false; rings = 0; first = null
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
            for (k in 0..n) { val i = (start + k) % n; run += p.xy[2 * i]; run += p.xy[2 * i + 1]; runW += p.widths?.get(i) ?: widthUm }
            if (p.widths != null) narrows = true
            if (rings == 0) first = p
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
            out += outOfHoles(rings, holes, spacing / 2, minLenUm)    // holes as areas, half a line wider
        }
        return out
    }

    /**
     * [rings] (each laid round from an outer edge) cut where they enter [holes] (a region's holes, clockwise), each hole
     * taken as an area grown by [growUm]: a ring crossing none stays closed, the rest are open pieces longer than [minLenUm].
     */
    fun outOfHoles(rings: List<Polygon>, holes: List<Polygon>, growUm: Long, minLenUm: Long): List<Piece> {
        val out = ArrayList<Piece>()
        val areas = holes.map { it.reversed() }
        val keepOut = if (growUm == 0L) areas else Offset.offset(areas, growUm)
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
     * from the middle, clipped to [region] (where a line's center may lie: its bead then reaches to the walls by plan): a
     * full circle where it fits, arcs where it does not. The middle is that of each piece's outline's box (a top with parts
     * standing on it is centered on its outline, not on them). Each circle starts at its back (the seams in one line) and
     * runs counter-clockwise; pieces (a whole circle closed, without its first point repeated), each longer than [minLenUm].
     * The surface pattern "concentric-inside-out"; unlike "concentric-outside-in" ([concentricCut]: each loop the one outside
     * it moved in, so an oval's rings sharpen at its ends) the rings stay round.
     *
     * Beside a wall the rings run along (an oval's sides, its ends, a cylinder's edge), the last arc that fits ends where
     * its center meets [region]'s edge, and between the arc inside it and the wall lay a wedge as wide as nearly a spacing
     * that no arc covered: a dashed slit along the top's edge. With [widthUm] and [reach] (where a line of that width may lay
     * plastic: [Planner.reachUm] past the walls, less the slicing tolerance), a circle runs on past where its arc ends, its
     * line narrowed to its bead's cross-section within [reach] and its center moved to that part's middle: the circle's own
     * bead with what lies past the walls' reach shaved off, so it lays nothing over the arc inside it beyond what two
     * neighboring lines share. A narrower line's end overlap is its own width's share ([overlap], the line-end overlap), so
     * it reaches that share of the width it lacks less far. It runs on while that is at least half a line ([MIN_SHARE] of
     * [widthUm]: the hatch's own bound, narrower slivers left), and where the ground left is narrower than half a line by
     * no more than half a line's end overlap, at half a line reaching over the ring inside it by the difference, as a line
     * end reaches into what it meets. Where it runs on round to its next arc, the two are one; where it runs round the
     * whole circle, the circle is whole; where its band reaches the ground with no arc of its circle beside it (past an
     * oval's tip), it is a piece of its own. A free end is narrowed until its round end reaches past [reach] by no more
     * than [tolUm] (the slicing tolerance), the points before it no wider than the next by more than twice that (the file
     * lays a move at its ends' mean width): where the circle crosses the wall steeply, the arc ends where it did, its round
     * end against the wall, as a hatch line's.
     */
    fun circles(region: List<Polygon>, spacing: Long, minLenUm: Long, widthUm: Long = 0L, reach: List<Polygon> = emptyList(), overlap: Double = 0.0,
                tolUm: Long = 0L): List<Piece> {
        val out = ArrayList<Piece>()
        for ((outer, holes) in Polygons.group(region)) {
            val piece = listOf(outer) + holes
            val b = outer.bounds()
            val cx = (b[0] + b[2]) / 2.0; val cy = (b[1] + b[3]) / 2.0
            val rMax = Math.hypot((b[2] - b[0]) / 2.0, (b[3] - b[1]) / 2.0)
            // this piece's reach: the plastic of its lines (another piece's lies elsewhere)
            val band = if (widthUm <= 0 || reach.isEmpty()) null else Booleans.intersection(reach, Offset.offset(piece, widthUm / 2 + 1)).takeIf { it.isNotEmpty() }?.let { Band(it, widthUm, overlap, tolUm) }
            var k = 0
            while (true) {
                val r = spacing * (k + 0.5)
                if (r - (if (band == null) 0 else widthUm / 2) > rMax) break
                // vertices so a chord strays at most CHORD_TOL_UM from the circle
                val step = if (r <= CHORD_TOL_UM) Math.PI / 4 else minOf(Math.PI / 4, 2 * Math.sqrt(2 * CHORD_TOL_UM / r))
                val n = Math.ceil(2 * Math.PI / step).toInt()
                val pts = ArrayList<Double>(2 * n + 2)
                for (i in 0..n) { val a = Math.PI / 2 + 2 * Math.PI * i / n; pts.add(cx + r * Math.cos(a)); pts.add(cy + r * Math.sin(a)) }
                // a circle cut by a hole starts inside at its first point and ends there: its last arc and its first are
                // one arc through the start (they were two paths, a stop and a start at the start point)
                val arcs = (if (r > rMax) mutableListOf() else clip(pts, piece, 0).toMutableList())
                if (arcs.size >= 2) {
                    val first = arcs.first(); val last = arcs.last()
                    if (first[0] == last[last.size - 2] && first[1] == last[last.size - 1]) {
                        arcs[arcs.size - 1] = last + first.copyOfRange(2, first.size)
                        arcs.removeAt(0)
                    }
                }
                val whole = arcs.size == 1 && arcs[0].let { a -> a.size >= 8 && a[0] == a[a.size - 2] && a[1] == a[a.size - 1] }
                val pieces = if (band == null || whole) null else band.runOn(cx, cy, r, arcs)
                for (pc in pieces ?: arcs.map { a ->
                    val m = a.size / 2
                    if (m > 3 && a[0] == a[2 * m - 2] && a[1] == a[2 * m - 1]) Piece(a.copyOf(2 * m - 2), true) else Piece(a, false) }) {
                    val a = pc.xy
                    var len = 0.0
                    for (q in 2 until a.size step 2) len += Math.hypot((a[q] - a[q - 2]).toDouble(), (a[q + 1] - a[q - 1]).toDouble())
                    if (pc.closed) len += Math.hypot((a[0] - a[a.size - 2]).toDouble(), (a[1] - a[a.size - 1]).toDouble())
                    if (len >= minLenUm) out += pc
                }
                k++
            }
        }
        return out
    }

    /** The narrowest a ring's line is laid beside a wall, a share of its width: half a line, as the hatch's slivers (Planner). */
    private const val MIN_SHARE = 0.5

    /** The most points taken between two samples where a ring's line narrows fast: a work bound (plymeld's choice), not a print value. */
    private const val MAX_SPLIT = 16

    /**
     * Where a circle's line may run on past its arcs ([circles]): [reach] (one piece's, where its lines' plastic may go), the
     * line's [widthUm], the line-end [overlap] (a share of a line's own width), and the slicing tolerance [tolUm] a narrowed
     * end's round end may reach past it.
     */
    private class Band(reach: List<Polygon>, private val widthUm: Long, private val overlap: Double, private val tolUm: Long) {
        private val edges = EdgeGrid(reach)
        private val groups = Polygons.group(reach)
        private fun inside(x: Double, y: Double): Boolean {
            val xi = Math.round(x); val yi = Math.round(y)
            return groups.any { (o, hs) -> Polygons.contains(o, xi, yi) && hs.none { Polygons.contains(it, xi, yi) } }
        }

        /**
         * A point where the circle runs on: its cross-section's end away from the reach's edge ([ax], [ay], µm) and the
         * direction across the circle from it toward that edge ([ux], [uy]), its line's width [w] along it (the line's center
         * half of that across), and whether it may be laid there.
         */
        private class Sample(val ax: Double, val ay: Double, val ux: Double, val uy: Double, val w: Double, val ok: Boolean) {
            val x get() = ax + ux * w / 2
            val y get() = ay + uy * w / 2
            /** The same cross-section narrowed to [nw] from its end away from the edge. */
            fun narrowed(nw: Double) = Sample(ax, ay, ux, uy, nw, ok)
        }

        /**
         * The circle of radius [r] about ([cx], [cy]) with its [arcs] (clipped, each a polyline counter-clockwise, the one
         * through the circle's start last), as pieces with the stretches where it runs on past them; null when it runs on
         * nowhere (the arcs as they are).
         */
        fun runOn(cx: Double, cy: Double, r: Double, arcs: List<LongArray>): List<Piece>? {
            val a0 = Math.PI / 2
            fun angle(x: Long, y: Long): Double { var t = Math.atan2(y - cy, x - cx); while (t < a0) t += 2 * Math.PI; while (t >= a0 + 2 * Math.PI) t -= 2 * Math.PI; return t }
            // the arcs as stretches of angle, from their start counter-clockwise
            class Arc(val xy: LongArray, val from: Double, val to: Double)
            val stretches = arcs.map { a ->
                val from = angle(a[0], a[1]); var to = angle(a[a.size - 2], a[a.size - 1])
                while (to <= from) to += 2 * Math.PI
                Arc(a, from, to)
            }.sortedBy { it.from }
            // the samples, a quarter of a line apart along the circle (the width taken as straight between them), over each
            // stretch between one arc's end and the next one's start (round the whole circle when it has none)
            val step = maxOf(1.0, widthUm / 4.0) / r
            val gaps = ArrayList<List<Sample>>()
            var any = false
            if (stretches.isEmpty()) {
                val m = Math.ceil(2 * Math.PI / step).toInt()
                gaps += samples(cx, cy, r, a0, a0 + 2 * Math.PI, m, inside(cx + r * Math.cos(a0), cy + r * Math.sin(a0)))
            } else for ((i, s) in stretches.withIndex()) {
                val next = stretches[(i + 1) % stretches.size]
                var to = next.from; while (to <= s.to) to += 2 * Math.PI
                // at least one sample between: two arcs a hair apart (a hole's corner grazed) are joined only where the line fits
                val m = maxOf(2, Math.ceil((to - s.to) / step).toInt())
                // an arc's end lies where its center meets the region's edge: inside the reach
                gaps += samples(cx, cy, r, s.to, to, m, true)
            }
            for (g in gaps) if (g.any { it.ok }) any = true
            if (!any) return null
            val full = widthUm.toDouble()
            /** A piece's points and widths as they are built. */
            class Run(val xy: ArrayList<Long> = ArrayList(), val w: ArrayList<Long> = ArrayList()) {
                fun add(x: Double, y: Double, wd: Double) { xy += Math.round(x); xy += Math.round(y); w += Math.round(wd) }
                fun addArc(a: LongArray) {
                    for (q in 0 until a.size / 2) {
                        if (q == 0 && xy.size >= 2 && xy[xy.size - 2] == a[0] && xy[xy.size - 1] == a[1]) continue
                        xy += a[2 * q]; xy += a[2 * q + 1]; w += Math.round(full)
                    }
                }
            }
            /**
             * How far a line's round end at [sm] reaches past where a line of its width may (µm): half its width less its
             * distance to the reach's edge, which a narrower line keeps the end overlap of the width it lacks short of.
             */
            fun over(sm: Sample) = sm.w / 2 + overlap * (widthUm - sm.w) - edges.distance(sm.x, sm.y, sm.w / 2 + overlap * widthUm)
            /**
             * [sm] as a line's end: narrowed (from its end away from the edge) until its round end reaches past the reach by
             * no more than the tolerance; null when that leaves it narrower than [MIN_SHARE] of the line.
             */
            fun asEnd(sm: Sample): Sample? {
                var e = sm
                // each narrowing pulls the round end in by about as much (half from its radius, half from its center), less
                // the end overlap it gives up
                repeat(4) {
                    val o = over(e)
                    if (o <= tolUm) return e
                    e = e.narrowed(e.w - (o - tolUm) / (1 - overlap) - 1)
                    if (e.w < MIN_SHARE * widthUm) return null
                }
                return if (over(e) <= tolUm) e else null
            }
            /** The [ok] samples of [run] (in order) kept from its free end back, its last narrowed to end there ([asEnd]). */
            fun trimmed(run: List<Sample>, freeAtEnd: Boolean): List<Sample> {
                val l = if (freeAtEnd) run else run.reversed()
                var e = l.size
                var end: Sample? = null
                while (e > 0) { end = asEnd(l[e - 1]); if (end != null) break; e-- }
                val kept = if (e == 0) ArrayList() else ArrayList(l.subList(0, e - 1) + end!!)
                // toward a narrowed end, no point wider than the next by more than twice the tolerance: a move is laid at its
                // ends' mean width, and its round end at the narrow end reaches no further than half the tolerance past it
                for (k in kept.size - 2 downTo 0) { val cap = kept[k + 1].w + 2.0 * tolUm; if (kept[k].w > cap) kept[k] = kept[k].narrowed(cap) }
                return if (freeAtEnd) kept else kept.reversed()
            }
            val out = ArrayList<Piece>()
            fun emit(run: Run, closed: Boolean) {
                if (run.xy.size < 4) return
                var xy = run.xy.toLongArray(); var w = run.w.toLongArray()
                if (closed && xy[0] == xy[xy.size - 2] && xy[1] == xy[xy.size - 1]) { xy = xy.copyOf(xy.size - 2); w = w.copyOf(w.size - 1) }
                if (closed) {
                    // a whole circle starts at its back, as every circle
                    val m = xy.size / 2
                    val s = (0 until m).minBy { angle(xy[2 * it], xy[2 * it + 1]) }
                    val x0 = xy; val w0 = w
                    xy = LongArray(2 * m) { x0[(2 * s + it) % (2 * m)] }; w = LongArray(m) { w0[(s + it) % m] }
                }
                out += Piece(xy, closed, if (w.all { it == widthUm }) null else w)
            }
            if (stretches.isEmpty()) {
                // no arc: the stretches where the band reaches in, each a piece of its own, both ends free
                val g = gaps[0]
                if (g.all { it.ok }) { val run = Run(); for (sm in g) run.add(sm.x, sm.y, sm.w); emit(run, true); return out }
                // round from a sample that may not be laid, so no stretch wraps the start
                val s0 = g.indexOfFirst { !it.ok }
                var cur = ArrayList<Sample>()
                fun flush() { val t = trimmed(trimmed(cur, true), false); if (t.isNotEmpty()) { val run = Run(); for (sm in t) run.add(sm.x, sm.y, sm.w); emit(run, false) }; cur = ArrayList() }
                for (j in 1..g.size) { val sm = g[(s0 + j) % g.size]; if (sm.ok) cur += sm else flush() }
                flush()
                return out
            }
            // each arc with what runs on before and after it; an arc whose stretch to the next runs on whole joins it
            val joinsNext = gaps.map { g -> g.all { it.ok } }
            val n = stretches.size
            if (joinsNext.all { it }) {
                // the whole circle: from the arc through its start (the last) round
                val run = Run()
                for (q in 0 until n) { val i = (n - 1 + q) % n; run.addArc(stretches[i].xy); for (sm in gaps[i]) run.add(sm.x, sm.y, sm.w) }
                emit(run, true)
                return out
            }
            // start from an arc whose stretch before it does not run on whole: pieces then never wrap
            val firstArc = (0 until n).first { !joinsNext[(it - 1 + n) % n] }
            val made = ArrayList<Pair<Int, Piece>>()      // (the arc it starts at, the piece)
            var q = 0
            while (q < n) {
                val i0 = (firstArc + q) % n
                val run = Run()
                // what runs on before the arc (the end of the stretch before it), its free end at its start
                val before = gaps[(i0 - 1 + n) % n]
                val head = trimmed(before.takeLastWhile { it.ok }, false)
                for (sm in head) run.add(sm.x, sm.y, sm.w)
                var i = i0
                while (true) {
                    run.addArc(stretches[i].xy)
                    q++
                    if (joinsNext[i] && q < n) { for (sm in gaps[i]) run.add(sm.x, sm.y, sm.w); i = (i + 1) % n; continue }
                    // what runs on after it, its free end at its end
                    for (sm in trimmed(gaps[i].takeWhile { it.ok }, true)) run.add(sm.x, sm.y, sm.w)
                    break
                }
                val before0 = out.size
                emit(run, false)
                if (out.size > before0) made += i0 to out.removeAt(out.size - 1)
                // stretches in the middle of a stretch between arcs: pieces of their own, both ends free
                val g = gaps[i]
                var cur = ArrayList<Sample>(); var started = false
                val lead = g.takeWhile { it.ok }.size; val tail = g.takeLastWhile { it.ok }.size
                for ((j, sm) in g.withIndex()) {
                    if (j < lead || j >= g.size - tail) continue
                    if (sm.ok) { cur += sm; started = true } else if (started) {
                        val t = trimmed(trimmed(cur, true), false)
                        if (t.isNotEmpty()) { val rr = Run(); for (x in t) rr.add(x.x, x.y, x.w); emit(rr, false); made += i to out.removeAt(out.size - 1) }
                        cur = ArrayList(); started = false
                    }
                }
            }
            // in the arcs' order round from the start, the piece through the start last
            val wrap = stretches.indexOfLast { it.to > a0 + 2 * Math.PI }
            return made.sortedWith(compareBy({ it.first == wrap }, { it.first })).map { it.second }
        }

        /**
         * [m] samples over the circle of radius [r] from angle [from] to [to] (neither end itself), [inAt] whether the
         * circle's point at [from] lies inside the reach: at each, the longest part of the line's cross-section (the circle's
         * radius from r - w / 2 to r + w / 2) inside the reach, its width and middle.
         */
        private fun samples(cx: Double, cy: Double, r: Double, from: Double, to: Double, m: Int, inAt: Boolean): List<Sample> {
            val out = ArrayList<Sample>(m)
            var px = cx + r * Math.cos(from); var py = cy + r * Math.sin(from)
            var inside = inAt
            var pa = from; var pw = if (inAt) widthUm.toDouble() else 0.0
            /** The sample at angle [a], the circle's point walked to from the last one. */
            fun walk(a: Double): Sample {
                val x = cx + r * Math.cos(a); val y = cy + r * Math.sin(a)
                // the circle's point crosses the reach's edge as often as the chord to it does
                if (edges.crossings(px, py, x, y).size % 2 == 1) inside = !inside
                px = x; py = y
                return at(cx, cy, r, a, inside)
            }
            for (j in 1 until m) {
                val a = from + (to - from) * j / m
                // the file lays a move at the mean of its ends' widths, so where the width changes by more than twice the
                // tolerance between two points (the line narrowing fast, where it crosses a wall steeply) points between
                // are taken, each move's round end then off where its width may reach by no more than half the tolerance
                val x0 = px; val y0 = py; val in0 = inside
                var sm = walk(a)
                val k = minOf(MAX_SPLIT, Math.ceil(Math.abs(sm.w - pw) / (2.0 * maxOf(1L, tolUm))).toInt())
                if (k > 1) {
                    px = x0; py = y0; inside = in0
                    for (q in 1 until k) out += walk(pa + (a - pa) * q / k)
                    sm = walk(a)
                }
                out += sm
                pa = a; pw = sm.w
            }
            return out
        }

        /** The sample at angle [a] of the circle of radius [r], [inside]: whether the circle's point there lies inside the reach. */
        private fun at(cx: Double, cy: Double, r: Double, a: Double, inside: Boolean): Sample {
            val h = widthUm / 2.0
            val c = Math.cos(a); val s = Math.sin(a)
            // the cross-section, inner end to outer end; its parts inside, from the point's side outward and inward
            val ts = edges.crossings(cx + (r - h) * c, cy + (r - h) * s, cx + (r + h) * c, cy + (r + h) * s)
            val below = ts.count { it < 0.5 }
            var inNow = if (below % 2 == 0) inside else !inside           // at the inner end
            var t0 = 0.0; var best = 0.0; var lo = 0.0; var hi = 0.0
            for (t in ts + 1.0) {
                if (inNow && t - t0 > best) { best = t - t0; lo = t0; hi = t }
                inNow = !inNow; t0 = t
            }
            val w = best * widthUm
            val rIn = r - h + lo * widthUm; val rOut = r - h + hi * widthUm
            // the end away from the reach's edge: the inner one where the part inside reaches the inner end and the edge
            // lies outward (beside a wall round the circles), the outer one where it is the other way round (beside a
            // hole); a whole cross-section, away from the nearer edge
            val fromInner = when {
                lo == 0.0 && hi < 1.0 -> true
                hi == 1.0 && lo > 0.0 -> false
                else -> edges.distance(cx + rIn * c, cy + rIn * s, w) >= edges.distance(cx + rOut * c, cy + rOut * s, w)
            }
            // the reach is the full line's: a narrower line's end overlap is its own width's share, so it reaches
            // overlap x (full - its width) less far (taken across the circle; where the circle runs along the edge, as
            // it does where it narrows over a length, that is across the edge)
            val own = if (best >= 1.0) w else maxOf(0.0, (w - overlap * widthUm) / (1 - overlap))
            // ground narrower than the narrowest line by no more than that line's end overlap: the narrowest line, reaching
            // over the ring beside it by the difference, as a line's end reaches into what it meets
            val narrowest = MIN_SHARE * widthUm
            val (lay, back) = if (own < narrowest && own >= (1 - overlap) * narrowest) narrowest to narrowest - own else own to 0.0
            return if (fromInner) Sample(cx + (rIn - back) * c, cy + (rIn - back) * s, c, s, lay, lay >= narrowest)
                else Sample(cx + (rOut + back) * c, cy + (rOut + back) * s, -c, -s, lay, lay >= narrowest)
        }
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
        /** The distance from (x, y) to the nearest edge, or [within] when none lies nearer than that. */
        fun distance(x: Double, y: Double, within: Double): Double {
            var best = within
            val seen = HashSet<Int>()
            forCells(x - within, y - within, x + within, y + within) { c ->
                for (k in bins[c]) if (seen.add(k)) {
                    val e = ex[k]
                    val dx = e[2] - e[0]; val dy = e[3] - e[1]; val l2 = dx * dx + dy * dy
                    val t = if (l2 == 0.0) 0.0 else (((x - e[0]) * dx + (y - e[1]) * dy) / l2).coerceIn(0.0, 1.0)
                    best = minOf(best, Math.hypot(e[0] + t * dx - x, e[1] + t * dy - y))
                }
            }
            return best
        }
    }
}
