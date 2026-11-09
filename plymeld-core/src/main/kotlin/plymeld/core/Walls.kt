package plymeld.core

import plymeld.geom.Booleans
import plymeld.geom.Offset
import plymeld.geom.Polygon
import plymeld.geom.Polygons

/**
 * Wall (perimeter) generation for one layer: `loops` nested centerlines.
 *
 * Bead model: an extruded line of width w and height h is a rectangle with semicircular
 * ends, so adjacent beads overlap and their centers sit one *spacing* apart:
 *     spacing(w, h) = w − h·(1 − π/4)
 * (the flow model of the Slic3r family, from recollection; confirmed by
 * measuring QIDIStudio's inner-wall positions on a test model).
 *
 * Centerline insets from the outline:
 *     outer wall        wo/2
 *     first inner wall  wo/2 + (so + si)/2
 *     each next wall    + si
 * The region left for infill is the last centerline inset by a further si/2.
 */
/** An open variable-width wall line: polyline (µm, x y ...) and the line width at each vertex. */
class CenterLine(val xy: LongArray, val widths: LongArray) {
    val maxWidthUm: Long get() = widths.max()
}

/** [center]: variable-width center lines in areas too thin for another pair of loops (Arachne-style walls; empty for classic). */
class LayerWalls(val walls: List<List<Polygon>>, val inner: List<Polygon>, val center: List<CenterLine> = emptyList())

object Walls {
    fun spacing(widthUm: Long, heightUm: Long): Long = Math.round(widthUm - heightUm * (1 - Math.PI / 4))

    /**
     * Arachne-style walls (wall_generator = arachne), plymeld's own construction:
     *  1. Loop k is laid only where its whole bead fits without meeting its twin from the opposite
     *     side: along the opening of the region at least (inset_k + spacing_k / 2) deep, i.e. the
     *     classic loop where the region is thick, nothing where a band is too thin for it. (Classic
     *     walls keep a loop wherever its center line exists, crowding two beads into a band.)
     *  2. What the loops leave uncovered is split by width: pieces at least two inner lines wide
     *     are the infill region; narrower pieces get ONE center line along their skeleton, as wide
     *     as the piece is thick there, clamped to [minBeadUm, 2 x inner width]. Pieces thinner than
     *     [minFeatureUm] are left empty (min_feature_size), except where [closeCompact] closes them.
     * NOT YET (full Arachne): redistributing width over several beads (wall_distribution_count
     * > 1), bead-count transitions along a band (wall_transition_*), thin walls of the outer loop.
     */
    fun arachne(outline: List<Polygon>, loops: Int, outerWidthUm: Long, innerWidthUm: Long, layerHeightUm: Long,
                minBeadUm: Long, minFeatureUm: Long, minLengthFactor: Double, transitionUm: Long,
                /**
                 * Close what the walls leave (the first layer, the bottom a viewer may see): a piece too thin for infill whose
                 * skeleton gives no center line (a compact piece, a small square: its skeleton is a point) gets lines of its own
                 * ([compactLines]), and a gap between the loops thinner than [minFeatureUm] gets a line as wide as the gap
                 * ([gapLines]). Left empty, every small square's middle was a hole in the bottom (a QR code laid on the bed:
                 * 283 holes, 60 mm²), and its 1.3 mm strokes a slit along their middle.
                 */
                closeCompact: Boolean = false): LayerWalls {
        val so = spacing(outerWidthUm, layerHeightUm)
        val si = spacing(innerWidthUm, layerHeightUm)
        val walls = ArrayList<List<Polygon>>(loops)
        var covered: List<Polygon> = emptyList()
        var inset = outerWidthUm / 2
        for (k in 0 until loops) {
            if (k == 1) inset += (so + si) / 2 else if (k > 1) inset += si
            val sk = if (k == 0) so else si
            val fit = Offset.offset(outline, -(inset + sk / 2), Offset.Join.MITER)
            if (fit.isEmpty()) break
            val loop = Offset.offset(fit, sk / 2, Offset.Join.MITER)
            if (loop.isEmpty()) break
            walls.add(loop)
            // this bead's band: from the loop's center line half a spacing each way (the outer bead
            // out to the outline). Only where the loop IS: "the outline minus the loops' insides" would
            // claim every area no loop fits in as covered: on any layer with a loop anywhere, each
            // thinner feature would print nothing (a 0.5 mm fin), and a fin tapering 800 -> 791 um
            // only half (its loop fits the wider half).
            val outerEdge = Offset.offset(loop, if (k == 0) inset else sk / 2, Offset.Join.MITER)
            val band = Booleans.difference(Booleans.intersection(outerEdge, outline), Offset.offset(loop, -sk / 2, Offset.Join.MITER))
            covered = if (covered.isEmpty()) band else Booleans.union(covered, band)
        }
        val left = if (covered.isEmpty()) outline else Booleans.difference(outline, covered)
        // thick leftovers are infill; the rest, if not too thin, gets center lines
        val thick = if (left.isEmpty()) left else Offset.offset(Offset.offset(left, -innerWidthUm, Offset.Join.MITER), innerWidthUm, Offset.Join.MITER)
        val thin0 = if (left.isEmpty()) left else if (thick.isEmpty()) left else Booleans.difference(left, thick)
        // corner slivers: cutting the infill region out of what is left leaves a sliver in every
        // corner of it, and each became a short center line. Only what is
        // more than one line from the infill region is a thin area of its own; the slivers stay
        // unfilled, as classic walls leave them (infill anchors reach into them).
        val thin = if (thin0.isEmpty() || thick.isEmpty()) thin0 else Booleans.difference(thin0, Offset.offset(thick, innerWidthUm, Offset.Join.ROUND))
        val center = if (thin.isEmpty()) emptyList() else centerLines(thin, minBeadUm, minFeatureUm, 2 * innerWidthUm, minLengthFactor, transitionUm, outline)
        if (!closeCompact || thin.isEmpty()) return LayerWalls(walls, thick, center)
        // the pieces of the thin area no center line reaches: lines of their own
        val extra = ArrayList<CenterLine>()
        for ((outer, holes) in Polygons.group(thin)) {
            if (outer.signedAreaMm2() <= 0.0) continue
            val reached = center.any { c -> (0 until c.xy.size / 2).any { k -> Polygons.contains(outer, c.xy[2 * k], c.xy[2 * k + 1]) } }
            if (reached) continue
            // a piece with a hole is a ring, not compact: a line across it crossed the hole (outside the model)
            val compact = if (holes.isEmpty()) compactLines(outer, innerWidthUm, minBeadUm, minFeatureUm) else emptyList()
            extra += compact.ifEmpty { gapLines(listOf(outer) + holes, minBeadUm, minFeatureUm, innerWidthUm, minLengthFactor, transitionUm) }
        }
        return LayerWalls(walls, thick, center + extra)
    }

    /**
     * Lines covering a compact piece [p] (no center line of its own): along its bounding box's longer side, as many as fit
     * across its shorter side at most [maxWidthUm] wide each, their width the shorter side shared among them, each ending
     * half its width from the box's ends (at least a quarter of its width long). Only for a piece that fills most of its box
     * ([COMPACT_SHARE]: a square, a short bar), and only lines whose ends and middle lie at least half their width inside
     * it: a thin curved piece's box reaches outside it, and lines from it ran outside the model (the pre-print check
     * refused them). None for a piece narrower than [minFeatureUm], or whose lines would be narrower than [minBeadUm].
     */
    fun compactLines(p: Polygon, maxWidthUm: Long, minBeadUm: Long, minFeatureUm: Long): List<CenterLine> {
        val b = p.bounds()
        val wx = b[2] - b[0]; val wy = b[3] - b[1]
        val short = minOf(wx, wy); val long = maxOf(wx, wy)
        if (short < minFeatureUm || short <= 0) return emptyList()
        if (p.signedArea2() / 2.0 < COMPACT_SHARE * wx.toDouble() * wy) return emptyList()
        val n = Math.max(1, Math.ceil(short.toDouble() / maxWidthUm).toInt())
        val w = short / n
        if (w < minBeadUm) return emptyList()
        val len = maxOf(long - w, w / 4)
        val mid = (if (wx >= wy) (b[0] + b[2]) else (b[1] + b[3])) / 2
        val out = ArrayList<CenterLine>(n)
        // where a line's ends and middle may lie: half its width inside the piece (a little slack for the box's rounding)
        val room = Offset.offset(listOf(p), -(w / 2 - minOf(w / 4, 20L)), Offset.Join.MITER)
        fun fits(x: Long, y: Long) = room.count { Polygons.contains(it, x, y) } % 2 == 1
        for (k in 0 until n) {
            val across = (if (wx >= wy) b[1] else b[0]) + w / 2 + k * w
            val a = mid - len / 2; val z = mid + len / 2
            val xy = if (wx >= wy) longArrayOf(a, across, z, across) else longArrayOf(across, a, across, z)
            if (fits(xy[0], xy[1]) && fits(xy[2], xy[3]) && fits((xy[0] + xy[2]) / 2, (xy[1] + xy[3]) / 2)) out += CenterLine(xy, longArrayOf(w, w))
        }
        return out
    }

    /**
     * Lines along a gap [piece] thinner than [minFeatureUm] that the loops leave between them (a band a little wider than one
     * loop: a QR code's 1.3 mm strokes left a 0.1 mm slit along their middle on the bottom). Each is as wide as the gap, not
     * widened to [minBeadUm]: the loops beside it already lay most of that width, and a wider line there is excess plastic. As
     * thin as the skeleton resolves; only lines at least two of [lineWidthUm] long (the void a round bead leaves in a corner
     * between loops is shorter, and stays empty as on every layer).
     */
    private fun gapLines(piece: List<Polygon>, minBeadUm: Long, minFeatureUm: Long, lineWidthUm: Long, minLengthFactor: Double, transitionUm: Long): List<CenterLine> {
        val feature = minOf(minFeatureUm, 2 * MIN_SAMPLE_UM)
        return centerLines(piece, minOf(minBeadUm, feature), feature, 2 * lineWidthUm, minLengthFactor, transitionUm).filter { c ->
            (1 until c.xy.size / 2).sumOf { k -> Math.hypot((c.xy[2 * k] - c.xy[2 * k - 2]).toDouble(), (c.xy[2 * k + 1] - c.xy[2 * k - 1]).toDouble()) } >= 2.0 * lineWidthUm
        }
    }

    /** A piece filling at least this share of its bounding box is compact enough for [compactLines]: a square fills all of it, a disc 79 %. */
    private const val COMPACT_SHARE = 0.6

    /**
     * Whether a center line ([xy], its width per vertex [widths]) runs along the outline [outline]: more than half its
     * vertices within half their bead and [EDGE_TOL_UM] of an outline edge, i.e. the bead's side IS the part's outer
     * surface there (no outer loop beside it). Such a line is the outer wall where it runs.
     */
    fun alongOutline(xy: LongArray, widths: LongArray?, outline: List<Polygon>): Boolean {
        val n = xy.size / 2
        if (n == 0 || outline.isEmpty()) return false
        var minX = Long.MAX_VALUE; var minY = Long.MAX_VALUE; var maxX = Long.MIN_VALUE; var maxY = Long.MIN_VALUE
        for (k in 0 until n) { minX = minOf(minX, xy[2 * k]); maxX = maxOf(maxX, xy[2 * k]); minY = minOf(minY, xy[2 * k + 1]); maxY = maxOf(maxY, xy[2 * k + 1]) }
        val reachMax = (widths?.max() ?: 0L) / 2 + EDGE_TOL_UM + 1
        // the outline's edges near the line only
        val edges = ArrayList<LongArray>()
        for (p in outline) for (e in 0 until p.size) {
            val ax = p.x(e); val ay = p.y(e); val bx = p.x((e + 1) % p.size); val by = p.y((e + 1) % p.size)
            if (maxOf(ax, bx) < minX - reachMax || minOf(ax, bx) > maxX + reachMax || maxOf(ay, by) < minY - reachMax || minOf(ay, by) > maxY + reachMax) continue
            edges += longArrayOf(ax, ay, bx, by)
        }
        if (edges.isEmpty()) return false
        var near = 0
        for (k in 0 until n) {
            val px = xy[2 * k].toDouble(); val py = xy[2 * k + 1].toDouble()
            val reach = (widths?.get(k) ?: 0L) / 2.0 + EDGE_TOL_UM
            if (edges.any { e -> pointSegUm(px, py, e) <= reach }) near++
        }
        return near * 2 > n
    }
    /** How much farther than half its bead a center line may be from the outline and still run along it (µm): rounding. plymeld's choice. */
    const val EDGE_TOL_UM = 50L
    private fun pointSegUm(px: Double, py: Double, e: LongArray): Double {
        val ax = e[0].toDouble(); val ay = e[1].toDouble(); val dx = e[2] - ax; val dy = e[3] - ay; val l2 = dx * dx + dy * dy
        val t = if (l2 == 0.0) 0.0 else (((px - ax) * dx + (py - ay) * dy) / l2).coerceIn(0.0, 1.0)
        return Math.hypot(px - (ax + t * dx), py - (ay + t * dy))
    }

    /**
     * Sampling of the skeleton: half the smallest feature, so that two samples at least
     * min_feature apart are never neighbors along one side of the outline. At a step EQUAL to
     * min_feature, neighboring samples passed the skeleton's noise filter and every sampling hair
     * became a path (117,888 inner-wall paths under 1 mm in one model). Floor: a geometric
     * resolution (µm), well under a line width.
     */
    private const val MIN_SAMPLE_UM = 25L

    /**
     * Center lines of thin areas: the skeleton chained into one polyline per branch, with the line
     * width at each vertex (the local thickness, clamped to [minBeadUm, maxWidthUm]). Dead-end
     * branches shorter than their own width are dropped: they are the forks into a thin area's
     * corners, where a line would only add a start and a stop.
     */
    fun centerLines(thin: List<Polygon>, minBeadUm: Long, minFeatureUm: Long, maxWidthUm: Long, minLengthFactor: Double, transitionUm: Long,
                    /** The layer's outline: a short line along it is the outer surface, kept (empty: no such exception). */ outline: List<Polygon> = emptyList()): List<CenterLine> =
        // one triangulation per connected thin piece: one for a whole layer's thin areas ran out of
        // memory on a large test model's layer
        joinEnds(plymeld.geom.Polygons.group(thin).flatMap { (o, hs) -> centerLinesOfPiece(listOf(o) + hs, minBeadUm, minFeatureUm, maxWidthUm, minLengthFactor, transitionUm, outline) },
            transitionUm)

    /**
     * [c] with every vertex dropped that both of its bead EDGES can spare, so neither edge moves more
     * than [tolUm] (the `resolution` setting): each move is extended as far as every vertex it spans
     * stays within [tolUm] of its chord AND within 2 x [tolUm] of the ONE width the move is extruded
     * at (the writer's mean of its ends). Greedy, not Douglas-Peucker: on a width taper the vertex
     * farthest from a move's mean width is always the one next to an end, so splitting at the worst
     * vertex peeled the taper off 25 um at a time (Benchy hull, z 12). Letting the width taper within
     * a move merged tapers into single moves (Benchy shell gaps 9.0 -> 10.1 mm2). The skeleton is
     * sampled every few tens of microns, and a constant-width line came out as 50 um segments with
     * 1-7 um slivers between them - thousands of moves a second for the motion planner, for nothing.
     */
    fun simplified(c: CenterLine, tolUm: Long): CenterLine {
        val n = c.xy.size / 2
        if (n <= 2 || tolUm <= 0) return c
        // Half the tolerance: the outline this line was derived from is already simplified to [tolUm],
        // and the full [tolUm] again opened cracks at bead contact (Benchy z 24 bow: shell gap volume
        // 0.165 -> 0.231 mm3).
        val tol = tolUm / 2.0
        fun fits(a: Int, b: Int): Boolean {
            val ax = c.xy[2 * a].toDouble(); val ay = c.xy[2 * a + 1].toDouble()
            val dx = c.xy[2 * b] - ax; val dy = c.xy[2 * b + 1] - ay
            val len2 = dx * dx + dy * dy
            val mean = (c.widths[a] + c.widths[b]) / 2.0
            for (k in a..b) {
                if (Math.abs(c.widths[k] - mean) > 2 * tol) return false
                if (k == a || k == b) continue
                val px = c.xy[2 * k] - ax; val py = c.xy[2 * k + 1] - ay
                val t = if (len2 > 0) ((px * dx + py * dy) / len2).coerceIn(0.0, 1.0) else 0.0
                val ex = px - t * dx; val ey = py - t * dy
                if (ex * ex + ey * ey > tol * tol) return false
            }
            return true
        }
        val idx = arrayListOf(0)
        var a = 0
        while (a < n - 1) {
            var b = a + 1
            while (b + 1 < n && fits(a, b + 1)) b++
            idx.add(b); a = b
        }
        return CenterLine(LongArray(2 * idx.size) { c.xy[2 * idx[it / 2] + it % 2] }, LongArray(idx.size) { c.widths[idx[it]] })
    }

    /**
     * Joins open center lines whose ends lie within [joinUm] of each other, closest pair first, into
     * one path through the gap (the joining segment takes the widths of the ends it links). A thin
     * area pinched to nothing splits into pieces, each with its own line; Arachne keeps a bead
     * continuous through such transitions (wall_transition_length), so plymeld does too.
     */
    private fun joinEnds(lines: List<CenterLine>, joinUm: Long): List<CenterLine> {
        val open = lines.filter { l -> val n = l.xy.size / 2; !(l.xy[0] == l.xy[2 * n - 2] && l.xy[1] == l.xy[2 * n - 1]) }.toMutableList()
        val closed = lines - open.toSet()
        fun end(l: CenterLine, last: Boolean): LongArray { val k = if (last) l.xy.size / 2 - 1 else 0; return longArrayOf(l.xy[2 * k], l.xy[2 * k + 1]) }
        fun reversed(l: CenterLine): CenterLine {
            val n = l.xy.size / 2
            return CenterLine(LongArray(2 * n) { k -> l.xy[2 * (n - 1 - k / 2) + k % 2] }, LongArray(n) { l.widths[n - 1 - it] })
        }
        val lim = joinUm.toDouble() * joinUm
        while (true) {
            var best = Double.MAX_VALUE; var bi = -1; var bj = -1; var biLast = false; var bjLast = false
            for (i in open.indices) for (j in i + 1 until open.size) for (il in listOf(false, true)) for (jl in listOf(false, true)) {
                val a = end(open[i], il); val b = end(open[j], jl)
                val dx = (a[0] - b[0]).toDouble(); val dy = (a[1] - b[1]).toDouble(); val d = dx * dx + dy * dy
                if (d <= lim && d < best) { best = d; bi = i; bj = j; biLast = il; bjLast = jl }
            }
            if (bi < 0) break
            // orient: open[bi] ends at the joined end, open[bj] starts there
            val a = if (biLast) open[bi] else reversed(open[bi])
            val b = if (bjLast) reversed(open[bj]) else open[bj]
            val joined = CenterLine(a.xy + b.xy, a.widths + b.widths)
            open.removeAt(bj); open.removeAt(bi); open.add(joined)
        }
        return closed + open
    }

    private fun centerLinesOfPiece(thin: List<Polygon>, minBeadUm: Long, minFeatureUm: Long, maxWidthUm: Long, minLengthFactor: Double, transitionUm: Long, outline: List<Polygon>): List<CenterLine> {
        val sample = maxOf(MIN_SAMPLE_UM, minFeatureUm / 2)
        val sk = plymeld.geom.Skeleton(thin, sample, maxOf(minFeatureUm, 2 * sample))
        // graph: node = circumcenter (exact doubles, shared by the edges of one triangle)
        val index = HashMap<Pair<Long, Long>, Int>()
        val nx = ArrayList<Double>(); val ny = ArrayList<Double>(); val nr = ArrayList<Double>()
        fun node(x: Double, y: Double, r: Double): Int = index.getOrPut(x.toRawBits() to y.toRawBits()) { nx.add(x); ny.add(y); nr.add(r); nx.size - 1 }
        val adj = HashMap<Int, MutableList<Int>>()
        for (k in 0 until sk.edgeCount) {
            val e = sk.edges
            // every skeleton edge: stretches thinner than min_feature are dropped per line below, only when long
            val a = node(e[6 * k], e[6 * k + 1], e[6 * k + 2]); val b = node(e[6 * k + 3], e[6 * k + 4], e[6 * k + 5])
            if (a == b) continue
            adj.getOrPut(a) { ArrayList() }.add(b); adj.getOrPut(b) { ArrayList() }.add(a)
        }
        fun width(i: Int) = (2 * nr[i]).coerceIn(minBeadUm.toDouble(), maxWidthUm.toDouble())
        fun key(a: Int, b: Int) = minOf(a, b).toLong() shl 32 or maxOf(a, b).toLong()
        fun chains(): List<List<Int>> {
            val used = HashSet<Long>()
            val out = ArrayList<List<Int>>()
            fun walk(start: Int, next: Int) {
                val chain = arrayListOf(start); var prev = start; var cur = next
                used.add(key(start, next))
                while (true) {
                    chain.add(cur)
                    val nbs = adj[cur] ?: break
                    if (nbs.size != 2) break
                    val nn = if (nbs[0] == prev) nbs[1] else nbs[0]
                    if (!used.add(key(cur, nn))) break
                    prev = cur; cur = nn
                }
                out.add(chain)
            }
            for ((n, nbs) in adj) if (nbs.size != 2) for (m in nbs) if (key(n, m) !in used) walk(n, m)
            for ((n, nbs) in adj) for (m in nbs) if (key(n, m) !in used) walk(n, m)       // pure cycles
            return out
        }
        fun length(c: List<Int>) = (1 until c.size).sumOf { Math.hypot(nx[c[it]] - nx[c[it - 1]], ny[c[it]] - ny[c[it - 1]]) }
        // prune dead-end spurs shorter than their width, until none is left
        var pruned = true
        while (pruned) {
            pruned = false
            for (c in chains()) {
                val leafA = (adj[c.first()]?.size ?: 0) == 1; val leafB = (adj[c.last()]?.size ?: 0) == 1
                if (leafA == leafB) continue                          // a free-standing line or a link between junctions
                if (length(c) >= c.maxOf { width(it) }) continue
                for (j in 1 until c.size) { adj[c[j - 1]]?.remove(c[j]); adj[c[j]]?.remove(c[j - 1]) }
                pruned = true
            }
            adj.entries.removeIf { it.value.isEmpty() }
        }
        // Thin stretches: a run of the line thinner than min_feature is cut out only when it is longer
        // than the transition length; a shorter dip is printed through at the narrowest line
        // (min_bead). Cutting every dip broke the center line of a letter stroke whose leftover
        // hovers around the threshold into dashes.
        fun split(c: List<Int>): List<List<Int>> {
            val thinAt = BooleanArray(c.size) { 2 * nr[c[it]] < minFeatureUm }
            if (thinAt.none { it }) return listOf(c)
            val keep = BooleanArray(c.size) { true }
            var i = 0
            while (i < c.size) {
                if (!thinAt[i]) { i++; continue }
                var j = i
                while (j + 1 < c.size && thinAt[j + 1]) j++
                val runLen = (i + 1..j).sumOf { Math.hypot(nx[c[it]] - nx[c[it - 1]], ny[c[it]] - ny[c[it - 1]]) }
                if (runLen > transitionUm || i == 0 || j == c.size - 1) for (k in i..j) keep[k] = false   // long, or a thin end
                i = j + 1
            }
            val out = ArrayList<List<Int>>(); var cur = ArrayList<Int>()
            for (k in c.indices) { if (keep[k]) cur.add(c[k]) else { if (cur.size >= 2) out.add(cur); cur = ArrayList() } }
            if (cur.size >= 2) out.add(cur)
            return out
        }
        // Small lines: an open line shorter than min_length_factor x its narrowest width is dropped
        // (min_length_factor, as the Orca-lineage Arachne is understood to use it: plymeld's reading,
        // not verified against its source); a closed one (a skeleton cycle) shorter than its own
        // widest width is a blob, not a wall (one model: 4,266 closed center lines under 0.2 mm).
        // Corner voids: an open line less than twice as long as its widest point is not a thin band
        // but the void a round bead leaves in a sharp corner between loops (on one layer seen: most of
        // the short lines). That is gap fill, which the Orca lineage prints only where
        // gap_fill_target says (commonly topbottom), so it is left empty here.
        // The factor 2 is plymeld's rule, not an Orca setting. It is for voids INSIDE the part: a short line along the
        // model's outline (a wedge tip, a frame's cut end) is the part's outer surface there, and is kept (
        // a two-color Benchy's window-frame ends printed nothing, their outline with no wall).
        // The outline exception covers both rules (a double helix's wall wedge beside a strip's end, 0.5 to 0.97 mm
        // wide, kept a loop at its wide end and lost its 0.57 mm center line to min_length_factor alone: its outline had no wall)
        return chains().flatMap { split(it) }.filter { c ->
            if (c.size < 2) return@filter false
            val len = length(c)
            val closed = c.first() == c.last()
            if (closed) return@filter len >= c.maxOf { width(it) }
            val along by lazy { outline.isNotEmpty() &&
                alongOutline(LongArray(2 * c.size) { k -> Math.round(if (k % 2 == 0) nx[c[k / 2]] else ny[c[k / 2]]) }, LongArray(c.size) { Math.round(width(c[it])) }, outline) }
            if (len < minLengthFactor * c.minOf { width(it) } && !along) return@filter false
            len >= 2 * c.maxOf { width(it) } || along
        }.map { c ->
            CenterLine(LongArray(2 * c.size) { k -> Math.round(if (k % 2 == 0) nx[c[k / 2]] else ny[c[k / 2]]) },
                LongArray(c.size) { Math.round(width(c[it])) })
        }
    }

    fun generate(outline: List<Polygon>, loops: Int, outerWidthUm: Long, innerWidthUm: Long, layerHeightUm: Long): LayerWalls {
        val so = spacing(outerWidthUm, layerHeightUm)
        val si = spacing(innerWidthUm, layerHeightUm)
        val walls = ArrayList<List<Polygon>>(loops)
        var inset = outerWidthUm / 2
        for (k in 0 until loops) {
            if (k == 1) inset += (so + si) / 2 else if (k > 1) inset += si
            val c = Offset.offset(outline, -inset, Offset.Join.MITER)
            if (c.isEmpty()) break
            walls.add(c)
        }
        val inner = if (walls.size == loops) Offset.offset(outline, -(inset + (if (loops > 1) si else so) / 2), Offset.Join.MITER) else emptyList()
        return LayerWalls(walls, inner)
    }
}
