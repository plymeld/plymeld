package plymeld.core

import plymeld.geom.Booleans
import plymeld.geom.Offset
import plymeld.geom.Polygon

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
/**
 * An open variable-width wall line: polyline (µm, x y ...) and the line width at each vertex. [reachUm]: how much of its
 * length its ends reach into the walls they meet (Walls.arachne): not the thin area's own length, which is what makes a
 * line a speck (Planner).
 */
class CenterLine(val xy: LongArray, val widths: LongArray, val reachUm: Double = 0.0,
                 /** The wall it lays, counted from the outside (a narrow island's beads, [Beads]); -1 = the innermost, past the loops. */
                 val wall: Int = -1) {
    val maxWidthUm: Long get() = widths.max()
}

/** [center]: variable-width center lines in areas too thin for another pair of loops (Arachne-style walls; empty for classic). */
class LayerWalls(val walls: List<List<Polygon>>, val inner: List<Polygon>, val center: List<CenterLine> = emptyList(),
                 /**
                  * Per loop of [walls] (same nesting), its line width at each vertex where it varies (a narrow island's bead
                  * round it, Beads); null, or a missing entry: the wall's own width throughout.
                  */
                 val loopWidths: List<List<LongArray?>> = emptyList()) {
    fun widthsOf(k: Int, li: Int): LongArray? = loopWidths.getOrNull(k)?.getOrNull(li)
}

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
     *     as the piece is thick there, clamped to [minBeadUm, maxBeadUm]. Where a piece is thinner than
     *     [minFeatureUm] no line is laid, however short the stretch (min_feature_size); between walls, pieces thinner than the
     *     narrowest line less what an interior line may overlap the walls a side ([endOverlap] of its width, the infill's
     *     wall overlap, or at least the bead's rounded flank, h (1 - pi / 4)) are left empty too: the narrowest line there
     *     would overlap the walls on both sides by more (centerLines' interiorMinUm).
     *  3. Where an area's width hovers at the width a loop needs (a ridge narrowing to one line, its outline a little uneven
     *     as any sliced surface is), a loop laid wherever it fits came and went along the area: a string of tiny closed
     *     loops overlapping each other, the pieces between them fragments, a gap where a loop ended. So the change in the
     *     number of lines is filtered, as a bead-count transition along a band is ([toleranceUm], [transitionUm]):
     *      - hysteresis: pieces of the fit region joined by the looser fit [toleranceUm] (the slicing tolerance: the outline
     *        is known no closer) a side further out are one loop, round the looser fit there, its beads that much closer;
     *        a piece of the fit region alone keeps its loop as it was;
     *      - a loop piece shorter than [transitionUm] (its fit region's extent) is no loop: the area there is laid by the
     *        center line running through it, unless no center line reaches it (an isolated pin: then it keeps its loop);
     *      - a center line's end against a loop's bead reaches into it by [endOverlap] of its width past bead contact, as an
     *        interior line's end does into the walls (plan.layer.infill.wall_overlap): where two lines become one, the
     *        single line meets the loop's turn with no gap.
     *  4. A narrow island, an island (an outer polygon and its holes) where the set walls nowhere all fit (the innermost loop
     *     has no fit region in it), is laid instead by bead count from its local width ([Beads]: as many lines across each
     *     place as its width holds, each between [minBeadUm] and [maxBeadUm], the width shared among them); steps 1 to 3 are
     *     computed for the whole outline as before and what they laid in such an island dropped, so every other island's
     *     walls are the same as they were. An island wide enough for its walls somewhere keeps steps 1 to 3 throughout,
     *     its narrow parts too (a fin off a block, a leftover between two loops).
     */
    fun arachne(outline: List<Polygon>, loops: Int, outerWidthUm: Long, innerWidthUm: Long, layerHeightUm: Long,
                minBeadUm: Long, minFeatureUm: Long, minLengthFactor: Double, transitionUm: Long,
                toleranceUm: Long = 0L, endOverlap: Double = 0.0,
                /** The widest a wall line may be (plan.layer.walls.line.width.max): a center line's cap, a narrow island's beads' bound. */
                maxBeadUm: Long = 2 * innerWidthUm): LayerWalls {
        val so = spacing(outerWidthUm, layerHeightUm)
        val si = spacing(innerWidthUm, layerHeightUm)
        // each loop's fit region (where its bead fits: its strict pieces, each an outer polygon and its holes) and its looser fit
        // (the hysteresis: [toleranceUm] further out), as the looser fit's pieces that hold some of the strict fit, each strict
        // piece by the looser piece it lies in ([of]); tiny looser pieces flagged
        class Fit(val sk: Long, val inset: Long, val strict: List<List<Polygon>>, val of: IntArray, val pieces: List<List<Polygon>>, val tiny: BooleanArray) {
            /** How many strict pieces each looser piece holds. */
            val held = IntArray(pieces.size).also { n -> for (p in of) n[p]++ }
        }
        val fits = ArrayList<Fit>(loops)
        run {
            var inset = outerWidthUm / 2
            for (k in 0 until loops) {
                if (k == 1) inset += (so + si) / 2 else if (k > 1) inset += si
                val sk = if (k == 0) so else si
                val high = Offset.offset(outline, -(inset + sk / 2), Offset.Join.MITER)
                if (high.isEmpty()) break
                val strict = plymeld.geom.Polygons.group(high).map { (o, hs) -> listOf(o) + hs }
                val loose = if (toleranceUm <= 0) strict
                    else plymeld.geom.Polygons.group(Offset.offset(outline, -(inset + sk / 2 - toleranceUm), Offset.Join.MITER)).map { (o, hs) -> listOf(o) + hs }
                // each strict piece's looser piece: the one holding its first point (the strict fit lies inside the looser one)
                val bounds = loose.map { it[0].bounds() }
                val ofLoose = IntArray(strict.size) { j ->
                    val x = strict[j][0].x(0); val y = strict[j][0].y(0)
                    if (toleranceUm <= 0) j else loose.indices.firstOrNull { i -> val b = bounds[i]
                        x >= b[0] && x <= b[2] && y >= b[1] && y <= b[3] && plymeld.geom.Polygons.contains(loose[i][0], x, y) &&
                            loose[i].drop(1).none { h -> plymeld.geom.Polygons.contains(h, x, y) } } ?: -1
                }
                // the looser pieces that hold a strict one, renumbered; a strict piece found in none (rounding at a sharp
                // corner) a piece of its own
                val used = ofLoose.filter { it >= 0 }.distinct().sorted()
                val index = HashMap<Int, Int>().also { m -> used.forEachIndexed { n, i -> m[i] = n } }
                val pieces = used.map { loose[it] } + strict.indices.filter { ofLoose[it] < 0 }.map { strict[it] }
                var extra = used.size
                val of = IntArray(strict.size) { j -> if (ofLoose[j] >= 0) index.getValue(ofLoose[j]) else extra++ }
                val tiny = BooleanArray(pieces.size) { val b = pieces[it][0].bounds(); Math.hypot((b[2] - b[0]).toDouble(), (b[3] - b[1]).toDouble()) < transitionUm }
                fits += Fit(sk, inset, strict, of, pieces, tiny)
            }
        }
        val sample = maxOf(MIN_SAMPLE_UM, minFeatureUm / 2)
        /** The walls with the tiny pieces left out except those [keep] names (loop index, piece index). */
        fun build(keep: Set<Pair<Int, Int>>): Pair<LayerWalls, List<Polygon>> {
            val walls = ArrayList<List<Polygon>>(fits.size)
            var covered: List<Polygon> = emptyList()
            for ((k, f) in fits.withIndex()) {
                val kept = f.pieces.indices.filter { !f.tiny[it] || (k to it) in keep }
                if (kept.isEmpty()) break
                // the loop's center line, [inset] in from the outline: round the strict fit, as it always was, in a piece that
                // holds one piece of it; round the looser fit in a piece that joins several (the area between them up to the
                // tolerance a side narrower than the loop needs: one loop, its beads that much closer there, kept within the
                // inset less the tolerance, a sliver's corners spiking out). Grown from the looser fit everywhere, a loop came
                // out a few microns off its old self at every corner, and what the walls leave to center lines and infill
                // changed by far more (a curved layer's whole infill became center lines)
                val joined = if (toleranceUm <= 0) emptyList() else kept.filter { f.held[it] > 1 }
                val alone = kept.toHashSet().apply { removeAll(joined.toSet()) }
                val high = f.strict.indices.filter { f.of[it] in alone }.flatMap { f.strict[it] }
                val strict = if (high.isEmpty()) high else Offset.offset(high, f.sk / 2, Offset.Join.MITER)
                val loose = if (joined.isEmpty()) emptyList() else Booleans.intersection(Offset.offset(joined.flatMap { f.pieces[it] }, f.sk / 2 - toleranceUm, Offset.Join.MITER),
                    Offset.offset(outline, -(f.inset - toleranceUm), Offset.Join.MITER))
                // a loop round any piece of fit region is at least a circle of the bead's spacing round (pi x spacing); one
                // shorter is a degenerate scrap of the offset (a test plate's pins: closed loops 0.08 mm round, a dot each,
                // a restart and a retraction for a blob)
                val loop = (if (loose.isEmpty()) strict else if (strict.isEmpty()) loose else Booleans.union(strict, loose))
                    .filter { it.perimeter() >= Math.PI * f.sk }
                if (loop.isEmpty()) break
                walls.add(loop)
                // this bead's band: from the loop's center line half a spacing each way (the outer bead
                // out to the outline). Only where the loop IS: "the outline minus the loops' insides" would
                // claim every area no loop fits in as covered: on any layer with a loop anywhere, each
                // thinner feature would print nothing (a 0.5 mm fin), and a fin tapering 800 -> 791 um
                // only half (its loop fits the wider half).
                val outerEdge = Offset.offset(loop, if (k == 0) f.inset else f.sk / 2, Offset.Join.MITER)
                val band = Booleans.difference(Booleans.intersection(outerEdge, outline), Offset.offset(loop, -f.sk / 2, Offset.Join.MITER))
                covered = if (covered.isEmpty()) band else Booleans.union(covered, band)
            }
            val left = if (covered.isEmpty()) outline else Booleans.difference(outline, covered)
            // thick leftovers are infill; the rest, if not too thin, gets center lines
            val thick = if (left.isEmpty()) left else Offset.offset(Offset.offset(left, -innerWidthUm, Offset.Join.MITER), innerWidthUm, Offset.Join.MITER)
            val thin0 = if (left.isEmpty()) left else if (thick.isEmpty()) left else Booleans.difference(left, thick)
            // corner slivers: cutting the infill region out of what is left leaves a sliver in every
            // corner of it, and each became a short center line. Only what is
            // more than one line from the infill region is a thin area of its own; the slivers stay
            // unfilled, as classic walls leave them (infill anchors reach into them). Not at the model's surface: there the
            // sliver is the outline's, in an acute corner the outer loop is cut short of, and no infill reaches it (a part's
            // corner 0.4 mm wide at its base, beside another part's tip, left its face bare along 0.5 mm).
            val thin = if (thin0.isEmpty() || thick.isEmpty()) thin0
                else Booleans.difference(thin0, Booleans.intersection(Offset.offset(thick, innerWidthUm, Offset.Join.ROUND), Offset.offset(outline, -innerWidthUm, Offset.Join.MITER)))
            // between walls, a thin area gets a line where the narrowest line overlaps the walls beside it by no more than an
            // interior line may: the infill's overlap with the walls ([endOverlap], plan.layer.infill.wall_overlap) of its
            // width a side, or at least its own rounded flanks (the bead model's overlap of two touching beads,
            // h (1 - pi / 4)). The flanks alone (0.02 to 0.03 mm a side) left a leftover 0.29 mm wide between two inner walls
            // empty along 9 mm, where the narrowest line (0.36 mm) reached 0.035 mm into each
            val interiorOverlap = maxOf(innerWidthUm - si, Math.round(endOverlap * minBeadUm))
            val center = if (thin.isEmpty()) emptyList() else centerLines(thin, minBeadUm, minFeatureUm, maxBeadUm, minLengthFactor, transitionUm, outline,
                interiorMinUm = minBeadUm - 2 * interiorOverlap)
            return LayerWalls(walls, thick, if (covered.isEmpty()) center else center.map { reachInto(it, covered, endOverlap, sample) }) to covered
        }
        val first = build(emptySet()).first
        // a tiny piece left out where no center line took its place keeps its loop (a pin too small for a loop of length
        // keeps the one it has; it is no stretch of a band)
        val orphans = HashSet<Pair<Int, Int>>()
        for ((k, f) in fits.withIndex()) for (i in f.pieces.indices) if (f.tiny[i]) {
            val reach = Offset.offset(f.pieces[i], f.sk / 2 + toleranceUm + sample, Offset.Join.ROUND)
            val taken = first.center.any { c -> (0 until c.xy.size / 2).any { v -> reach.any { p -> p.isCcw() && plymeld.geom.Polygons.contains(p, c.xy[2 * v], c.xy[2 * v + 1]) } } }
            if (!taken) orphans += k to i
        }
        val laid = if (orphans.isEmpty()) first else build(orphans).first
        // a narrow island, where the set walls nowhere all fit (the innermost loop has no fit region in it), is laid by bead
        // count from its local width instead (Beads); every other island keeps what was laid above, the same object
        if (loops <= 0) return laid
        val last = fits.getOrNull(loops - 1)
        val islands = plymeld.geom.Polygons.group(outline)
        fun inIsland(o: Polygon, hs: List<Polygon>, x: Long, y: Long) = plymeld.geom.Polygons.contains(o, x, y) && hs.none { plymeld.geom.Polygons.contains(it, x, y) }
        val narrow = islands.filter { (o, hs) -> last == null || last.strict.none { piece -> inIsland(o, hs, piece[0].x(0), piece[0].y(0)) } }
        if (narrow.isEmpty()) return laid
        val islandBeads = HashMap<Polygon, List<CenterLine>>()
        val params = Beads.Params(Beads.Rule(outerWidthUm.toDouble(), innerWidthUm.toDouble(), loops, layerHeightUm * (1 - Math.PI / 4),
            minBeadUm.toDouble(), maxBeadUm.toDouble()), minFeatureUm, transitionUm, toleranceUm, sample, endOverlap, minLengthFactor)
        // each narrow island's beads, kept only where they lay it no worse than the walls above by IslandCheck's measures
        // (coverage first): an island whose beads fail that keeps its walls
        val flank = layerHeightUm * (1 - Math.PI / 4)
        val floorUm = (minFeatureUm + 2 * toleranceUm).toDouble()
        // the parameters the decision reads, for the cache below
        val sig = longArrayOf(loops.toLong(), outerWidthUm, innerWidthUm, layerHeightUm, minBeadUm, minFeatureUm, transitionUm, toleranceUm,
            maxBeadUm, sample, java.lang.Double.doubleToLongBits(endOverlap), java.lang.Double.doubleToLongBits(minLengthFactor))
        val taken = narrow.filter { (o, hs) ->
            val isl = listOf(o) + hs
            // the same island (an outline repeated exactly, as a prism's is on layer after layer) is decided once ([narrowCache])
            val key = NarrowKey(isl.map { it.xy }, sig)
            val got = narrowCache[key] ?: narrowBeads(isl, laid, params, outerWidthUm, innerWidthUm, flank, floorUm, toleranceUm).also {
                if (narrowCache.size >= NARROW_CACHE_ENTRIES) narrowCache.clear()
                narrowCache[key] = it
            }
            if (got.isNotEmpty()) islandBeads[o] = got[0]
            got.isNotEmpty()
        }
        if (taken.isEmpty()) return laid
        fun inTaken(x: Long, y: Long) = taken.any { (o, hs) -> inIsland(o, hs, x, y) }
        val walls = laid.walls.map { level -> level.filter { !inTaken(it.x(0), it.y(0)) } }.dropLastWhile { it.isEmpty() }
        val inner = laid.inner.filter { !inTaken(it.x(0), it.y(0)) }
        val center = laid.center.filter { c -> val m = c.xy.size / 4 * 2; !inTaken(c.xy[m], c.xy[m + 1]) }
        // the beads' closed lines are loops of the walls, at their place from the outside, so they are laid as every loop is
        // (seams, scarf, order, travel); a loop at its level's set width throughout keeps no widths of its own; the open
        // lines stay lines
        val levels = ArrayList<ArrayList<Polygon>>(); val levelWidths = ArrayList<ArrayList<LongArray?>>()
        for (lv in walls) { levels.add(ArrayList(lv)); levelWidths.add(ArrayList(lv.map { null })) }
        val open = ArrayList<CenterLine>()
        for ((o, _) in taken) for (c in islandBeads.getValue(o)) {
            val m = c.xy.size / 2
            if (m < 4 || c.xy[0] != c.xy[2 * m - 2] || c.xy[1] != c.xy[2 * m - 1]) { open.add(c); continue }
            val k = maxOf(0, c.wall)
            while (levels.size <= k) { levels.add(ArrayList()); levelWidths.add(ArrayList()) }
            val ws = c.widths.copyOf(m - 1)
            levels[k].add(Polygon(c.xy.copyOf(2 * (m - 1))))
            val set = if (k == 0) outerWidthUm else innerWidthUm
            levelWidths[k].add(if (ws.all { Math.abs(it - set) <= toleranceUm }) null else ws)
        }
        return LayerWalls(levels, inner, center + open, levelWidths)
    }

    /**
     * [c] with each open end that stops against the walls' beads ([covered]: their bands, out to bead contact) reaching into
     * them by [overlap] of its width past contact, toward the nearest point of the band: the end within [slackUm] (the
     * skeleton's sampling) of half its width from a band is one such. Ends at the outline, and closed lines, stay as they are.
     */
    private fun reachInto(c: CenterLine, covered: List<Polygon>, overlap: Double, slackUm: Long): CenterLine {
        val n = c.xy.size / 2
        if (n < 2 || (c.xy[0] == c.xy[2 * n - 2] && c.xy[1] == c.xy[2 * n - 1])) return c
        fun target(k: Int): LongArray? {
            val ex = c.xy[2 * k].toDouble(); val ey = c.xy[2 * k + 1].toDouble(); val half = c.widths[k] / 2.0
            // the way the line runs out at this end
            val j = if (k == 0) 1 else n - 2
            val tx = ex - c.xy[2 * j]; val ty = ey - c.xy[2 * j + 1]; val tl = Math.hypot(tx, ty)
            if (tl == 0.0) return null
            val reach = half + slackUm
            var best = Double.MAX_VALUE; var bx = 0.0; var by = 0.0
            for (p in covered) for (e in 0 until p.size) {
                val ax = p.x(e).toDouble(); val ay = p.y(e).toDouble(); val qx = p.x((e + 1) % p.size).toDouble(); val qy = p.y((e + 1) % p.size).toDouble()
                if (maxOf(ax, qx) < ex - reach || minOf(ax, qx) > ex + reach || maxOf(ay, qy) < ey - reach || minOf(ay, qy) > ey + reach) continue
                val dx = qx - ax; val dy = qy - ay; val l2 = dx * dx + dy * dy
                val t = if (l2 == 0.0) 0.0 else (((ex - ax) * dx + (ey - ay) * dy) / l2).coerceIn(0.0, 1.0)
                val d = Math.hypot(ex - ax - t * dx, ey - ay - t * dy)
                if (d < best) { best = d; bx = ax + t * dx; by = ay + t * dy }
            }
            if (best > reach || best <= 0.0) return null
            // the band ahead of the end, not beside it (a line thinning out between two walls ends with them at its sides)
            if (((bx - ex) * tx + (by - ey) * ty) / (best * tl) < END_AHEAD_COS) return null
            val ext = best - half + overlap * c.widths[k]
            if (ext <= 0.0) return null
            return longArrayOf(Math.round(ex + (bx - ex) / best * ext), Math.round(ey + (by - ey) / best * ext))
        }
        val a = target(0); val b = target(n - 1)
        if (a == null && b == null) return c
        val xy = (a ?: LongArray(0)) + c.xy + (b ?: LongArray(0))
        val ws = (if (a != null) longArrayOf(c.widths[0]) else LongArray(0)) + c.widths + (if (b != null) longArrayOf(c.widths[n - 1]) else LongArray(0))
        fun added(p: LongArray?, k: Int) = if (p == null) 0.0 else Math.hypot((p[0] - c.xy[2 * k]).toDouble(), (p[1] - c.xy[2 * k + 1]).toDouble())
        return CenterLine(xy, ws, c.reachUm + added(a, 0) + added(b, n - 1), c.wall)
    }

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
    /**
     * A center line's end stops against a wall's bead "ahead" of it when the nearest point of the bead's band lies within
     * 60 deg of the way the line runs out (cos 60 deg): the skeleton's last step at a pruned fork can lean that far. A
     * geometric threshold, plymeld's choice, not a print value.
     */
    private const val END_AHEAD_COS = 0.5

    /**
     * A narrow island's beads ([isl]: its outer polygon and its holes), or none where they lay it worse than the walls
     * [laid] there by IslandCheck's measures (coverage first): a list of one entry, the beads, or empty (keep the walls).
     * A function of the island and the parameters alone (the walls of one island do not reach another), so [narrowCache]
     * may hold it.
     */
    private fun narrowBeads(isl: List<Polygon>, laid: LayerWalls, params: Beads.Params, outerWidthUm: Long, innerWidthUm: Long,
                            flank: Double, floorUm: Double, toleranceUm: Long): List<List<CenterLine>> {
        val o = isl[0]; val hs = isl.drop(1)
        fun mine(x: Long, y: Long) = plymeld.geom.Polygons.contains(o, x, y) && hs.none { plymeld.geom.Polygons.contains(it, x, y) }
        // simplified to the tolerance as laid, and measured so (the construction's points lie a few microns apart)
        val beads = Beads.lay(isl, params).map { simplified(it, toleranceUm) }
        val mb = IslandCheck.measure(isl, beads.map { IslandCheck.Line(it.xy, DoubleArray(it.widths.size) { k -> it.widths[k].toDouble() }, false) }, emptyList(), flank, floorUm, toleranceUm.toDouble())
        // beads within the limits on their own need no comparison with the walls (the walls are then measured only to judge
        // beads that are not)
        if (mb != null && mb.wideUm2 == 0.0 && mb.widestUm <= NARROW_WIDEST_UM + IslandCheck.CELL_UM && mb.outsideUm <= NARROW_OUTSIDE_UM + IslandCheck.CELL_UM &&
            mb.doubledUm2 <= NARROW_DOUBLED_SLACK_UM2) return listOf(beads)
        val oldLines = ArrayList<IslandCheck.Line>()
        for ((k, level) in laid.walls.withIndex()) for (p in level) if (mine(p.x(0), p.y(0)))
            oldLines += IslandCheck.Line(p.xy, DoubleArray(p.size) { (if (k == 0) outerWidthUm else innerWidthUm).toDouble() }, true)
        for (c in laid.center) { val m = c.xy.size / 4 * 2; if (mine(c.xy[m], c.xy[m + 1])) oldLines += IslandCheck.Line(c.xy, DoubleArray(c.widths.size) { c.widths[it].toDouble() }, false) }
        val oldCovered = laid.inner.filter { mine(it.x(0), it.y(0)) }
        val mo = IslandCheck.measure(isl, oldLines, oldCovered, flank, floorUm, toleranceUm.toDouble())
        val ok = mo != null && mb != null && mb.wideUm2 <= mo.wideUm2 + NARROW_WIDE_SLACK_UM2 && mb.widestUm <= maxOf(mo.widestUm, NARROW_WIDEST_UM) + IslandCheck.CELL_UM &&
            mb.outsideUm <= maxOf(mo.outsideUm, NARROW_OUTSIDE_UM) + IslandCheck.CELL_UM &&
            (mb.doubledUm2 <= mo.doubledUm2 + NARROW_DOUBLED_SLACK_UM2 || (mo.wideUm2 - mb.wideUm2 > NARROW_HOLE_UM2 && mb.doubledUm2 <= NARROW_DOUBLED_MAX_UM2))
        return if (ok) listOf(beads) else emptyList()
    }

    /** A narrow island's outline (each polygon's points) and the parameters its decision reads: [narrowCache]'s key. */
    private class NarrowKey(val polys: List<LongArray>, val sig: LongArray) {
        private val hash = polys.fold(sig.contentHashCode()) { h, p -> 31 * h + p.contentHashCode() }
        override fun hashCode() = hash
        override fun equals(other: Any?) = other is NarrowKey && other.hash == hash && other.sig.contentEquals(sig) &&
            other.polys.size == polys.size && polys.indices.all { other.polys[it].contentEquals(polys[it]) }
    }
    /**
     * Narrow islands decided, by shape: an extruded shape repeats its outline exactly on layer after layer, and is decided
     * once. At most [NARROW_CACHE_ENTRIES], emptied when full (memory). A pure function's results: the output is the same
     * with it or without it.
     */
    private val narrowCache = java.util.concurrent.ConcurrentHashMap<NarrowKey, List<List<CenterLine>>>()
    /** plymeld's choice (memory: an island's beads are a few kilobytes). */
    private const val NARROW_CACHE_ENTRIES = 4096

    /*
     * A narrow island's beads replace its walls only where they are no worse by IslandCheck (plymeld's choices, geometric
     * allowances, not print values): uncovered area wider than the floor no more than 0.002 mm² over the walls'; the widest
     * uncovered spot no wider than the walls' or 0.105 mm (what bead ends and corners leave), the larger; plastic no farther
     * outside than the walls' or 0.09 mm (the narrowest line in the narrowest place laid, (0.3 - 0.15) / 2, and the
     * tolerance); plastic laid twice no more than 0.06 mm² over the walls' (a new line's round start where the count rises,
     * about 0.02 to 0.04 mm² each, two to an island), or, where the walls leave a hole wider than the floor of at least
     * 0.01 mm² that the beads close, no more than 0.2 mm² in all (coverage first). Beads that leave nothing wider than the
     * floor bare, no spot wider than 0.105 mm, nothing farther outside than 0.09 mm and no more than 0.06 mm² laid twice are
     * taken without measuring the walls (each limit read a grid cell loose).
     */
    private const val NARROW_HOLE_UM2 = 10_000.0
    private const val NARROW_DOUBLED_MAX_UM2 = 200_000.0
    private const val NARROW_WIDE_SLACK_UM2 = 2_000.0
    private const val NARROW_WIDEST_UM = 105.0
    private const val NARROW_OUTSIDE_UM = 90.0
    private const val NARROW_DOUBLED_SLACK_UM2 = 60_000.0

    /** Segments (ax, ay, bx, by, µm) bucketed in square cells of [cellUm], for "is any within this distance of a point". */
    private class EdgeGrid(private val edges: List<LongArray>, private val cellUm: Long) {
        private val cells = HashMap<Long, MutableList<Int>>()
        private fun key(cx: Long, cy: Long) = (cx shl 32) xor (cy and 0xffffffffL)
        init {
            for ((i, e) in edges.withIndex()) {
                for (cx in Math.floorDiv(minOf(e[0], e[2]), cellUm)..Math.floorDiv(maxOf(e[0], e[2]), cellUm))
                    for (cy in Math.floorDiv(minOf(e[1], e[3]), cellUm)..Math.floorDiv(maxOf(e[1], e[3]), cellUm))
                        cells.getOrPut(key(cx, cy)) { ArrayList() }.add(i)
            }
        }
        fun within(x: Double, y: Double, d: Double): Boolean {
            val r = Math.ceil(d / cellUm).toLong()
            val cx = Math.floorDiv(Math.floor(x).toLong(), cellUm); val cy = Math.floorDiv(Math.floor(y).toLong(), cellUm)
            for (i in cx - r..cx + r) for (j in cy - r..cy + r) for (k in cells[key(i, j)] ?: continue) if (pointSegUm(x, y, edges[k]) <= d) return true
            return false
        }
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
                    /** The layer's outline: a short line along it is the outer surface, kept (empty: no such exception). */ outline: List<Polygon> = emptyList(),
                    /**
                     * The thinnest area between walls (away from [outline]) that gets a line: there the narrowest line, wider than
                     * the area, overlaps the walls on both sides. 0: [minFeatureUm], as at the surface.
                     */
                    interiorMinUm: Long = 0L): List<CenterLine> {
        // one triangulation per connected thin piece: one for a whole layer's thin areas ran out of
        // memory on a large test model's layer
        val lines = plymeld.geom.Polygons.group(thin).flatMap { (o, hs) -> centerLinesOfPiece(listOf(o) + hs, minBeadUm, minFeatureUm, maxWidthUm, minLengthFactor, transitionUm, outline,
            maxOf(minFeatureUm, interiorMinUm)) }
        // two ends are not joined across a place the model is narrower than min_feature: the walls lay no line there
        val surface by lazy { EdgeGrid(outline.flatMap { p -> (0 until p.size).map { e -> longArrayOf(p.x(e), p.y(e), p.x((e + 1) % p.size), p.y((e + 1) % p.size)) } }, maxOf(maxWidthUm, 4 * minFeatureUm)) }
        return joinEnds(lines, transitionUm) { ax, ay, bx, by -> outline.isEmpty() ||
            (0..8).none { k -> surface.within(ax + (bx - ax) * k / 8.0, ay + (by - ay) * k / 8.0, minFeatureUm / 2.0) } }
    }

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
        return CenterLine(LongArray(2 * idx.size) { c.xy[2 * idx[it / 2] + it % 2] }, LongArray(idx.size) { c.widths[idx[it]] }, c.reachUm, c.wall)
    }

    /**
     * Joins open center lines whose ends lie within [joinUm] of each other, closest pair first, into
     * one path through the gap (the joining segment takes the widths of the ends it links). A thin
     * area pinched to nothing splits into pieces, each with its own line; Arachne keeps a bead
     * continuous through such transitions (wall_transition_length), so plymeld does too, but only where the gap may be
     * crossed ([crossable]: not past the model's surface closer than half of min_feature, a neck no line is laid in).
     */
    private fun joinEnds(lines: List<CenterLine>, joinUm: Long, crossable: (Double, Double, Double, Double) -> Boolean): List<CenterLine> {
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
                if (d <= lim && d < best && crossable(a[0].toDouble(), a[1].toDouble(), b[0].toDouble(), b[1].toDouble())) { best = d; bi = i; bj = j; biLast = il; bjLast = jl }
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

    private fun centerLinesOfPiece(thin: List<Polygon>, minBeadUm: Long, minFeatureUm: Long, maxWidthUm: Long, minLengthFactor: Double, transitionUm: Long, outline: List<Polygon>,
                                   interiorMinUm: Long): List<CenterLine> {
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
        // Prune dead-end spurs shorter than their width: the forks into a thin area's corners. Each round decides on the graph
        // as it stood at the round's start, and a fork is one that ends where the skeleton itself ended (at the area's edge).
        // Decided as the graph changed under the loop, a short band's two forks went first, its trunk then read as a dead-end
        // spur of its own and went too, and one corner fork was left as the band's line: a ridge 0.75 mm wide between two wall
        // loops, 1.2 mm of it, laid as a 0.4 mm stub across it. Then, as a second step, a dead end the first step left (the
        // trunk of a fork whose own forks were pruned) goes too where it is a side branch of a line running on through its
        // junction (two other branches there), not where it is the line itself.
        // Where the line lies against the model's surface ([outline]: its circle reaches the outline, within the sampling):
        // there the area's edge is the outer surface, and an area down to min_feature gets the narrowest line (a ledge, a
        // frame's edge, else unwalled). Elsewhere it lies between walls: a line wider than the area there overlaps them on
        // both sides (a 0.3 mm line in a 0.15 mm leftover inside a loop: 0.075 mm into each wall, the crest of a thin ridge
        // over-full), so it needs [interiorMinUm]. Without the outline (a test of the thin area alone): the surface everywhere.
        val nearEdges: List<LongArray> = if (outline.isEmpty()) emptyList() else {
            var x0 = Double.MAX_VALUE; var y0 = Double.MAX_VALUE; var x1 = -Double.MAX_VALUE; var y1 = -Double.MAX_VALUE
            for (i in nx.indices) { x0 = minOf(x0, nx[i] - nr[i]); x1 = maxOf(x1, nx[i] + nr[i]); y0 = minOf(y0, ny[i] - nr[i]); y1 = maxOf(y1, ny[i] + nr[i]) }
            val m = sample.toDouble()
            val out = ArrayList<LongArray>()
            for (p in outline) for (e in 0 until p.size) {
                val ax = p.x(e); val ay = p.y(e); val bx = p.x((e + 1) % p.size); val by = p.y((e + 1) % p.size)
                if (maxOf(ax, bx) < x0 - m || minOf(ax, bx) > x1 + m || maxOf(ay, by) < y0 - m || minOf(ay, by) > y1 + m) continue
                out += longArrayOf(ax, ay, bx, by)
            }
            out
        }
        val surfaceGrid = if (nearEdges.isEmpty()) null else EdgeGrid(nearEdges, maxOf(maxWidthUm, 4 * sample))
        fun atSurface(i: Int): Boolean = outline.isEmpty() || surfaceGrid?.within(nx[i], ny[i], nr[i] + sample) == true
        fun isThin(i: Int): Boolean = 2 * nr[i] < (if (atSurface(i)) minFeatureUm else interiorMinUm)
        val origLeaf = adj.filterValues { it.size == 1 }.keys.toHashSet()
        fun prune(eligible: (List<Int>) -> Boolean) {
            var pruned = true
            while (pruned) {
                pruned = false
                val degree = adj.mapValues { it.value.size }
                val all = chains()
                val cut = all.mapNotNull { c ->
                    val leafA = (degree[c.first()] ?: 0) == 1; val leafB = (degree[c.last()] ?: 0) == 1
                    val fromLeaf = if (leafA) c else c.reversed()                                    // the leaf end first
                    fromLeaf.takeIf { leafA != leafB && length(c) < c.maxOf { width(it) } && eligible(fromLeaf) }
                }.groupBy { it.last() }.flatMap { (junction, cs) ->
                    // never every branch of a junction: the two longest of them stay, one line through it (a small Y-shaped
                    // area keeps a line; the longest alone left a hook into one corner of a wedge, its other faces bare)
                    if (cs.size >= (degree[junction] ?: 0)) cs.sortedBy { length(it) }.dropLast(2) else cs
                }.toMutableList()
                // Nor every branch of a small area whose junction is several skeleton nodes a few microns apart: where the
                // round's cuts would leave less of its skeleton than its own widest line, its two longest forks stay (a
                // dome 1.3 x 0.8 mm kept a line 23 um long between two of its junction's nodes)
                if (cut.isNotEmpty()) {
                    val comp = HashMap<Int, Int>()
                    for (start in adj.keys) if (start !in comp) {
                        val stack = ArrayDeque<Int>(); stack.add(start); comp[start] = start
                        while (stack.isNotEmpty()) { val v = stack.removeLast(); for (u in adj[v].orEmpty()) if (u !in comp) { comp[u] = start; stack.add(u) } }
                    }
                    for ((id, cs) in cut.groupBy { comp[it.first()] }) {
                        val inComp = all.filter { comp[it.first()] == id }
                        val left = inComp.sumOf { length(it) } - cs.sumOf { length(it) }
                        if (left < inComp.maxOf { c -> c.maxOf { width(it) } }) cut.removeAll(cs.sortedBy { length(it) }.takeLast(2).toSet())
                    }
                }
                for (c in cut) { for (j in 1 until c.size) { adj[c[j - 1]]?.remove(c[j]); adj[c[j]]?.remove(c[j - 1]) }; pruned = true }
                adj.entries.removeIf { it.value.isEmpty() }
            }
        }
        prune { c -> c.first() in origLeaf }
        prune { c -> (adj[c.last()]?.size ?: 0) >= 3 }
        // Thin stretches: a run of the line thinner than min_feature (between walls: than [interiorMinUm]) is cut out
        // when it is longer than the transition length; a shorter dip between walls, still at least min_feature, is printed
        // through at the narrowest line (min_bead): cutting every such dip broke the center line of a letter stroke whose
        // leftover hovers around the threshold into dashes. Where the area is narrower than min_feature the line is never
        // laid, however short the stretch: printed through, a 0.3 mm line crossed a gap 0.1 mm wide between the model's
        // face and another part's tip, into that part's place.
        val bandPieces = ArrayList<List<Int>>()
        fun split(c: List<Int>): List<List<Int>> {
            val thinAt = BooleanArray(c.size) { isThin(c[it]) }
            // a step of the skeleton across a neck narrower than min_feature between two of its points (the skeleton's
            // points sit where three samples of the outline meet, and a step can pass a corner of the outline closer than
            // either end is): the line breaks there, as at a point that thin. A neck is a place much closer to the outline
            // than both ends (within half of either's half-width): along a thin wedge the skeleton zigzags a little, its
            // steps' middles a few microns nearer one side than its points, and those are no neck (a star's tips lost
            // their lines a millimetre short of where they narrow under min_feature)
            val neck = BooleanArray(c.size) { k -> k > 0 && surfaceGrid != null && run {
                val a = c[k - 1]; val b = c[k]
                val d = minOf(minFeatureUm / 2.0, minOf(nr[a], nr[b]) / 2)
                listOf(0.25, 0.5, 0.75).any { t -> surfaceGrid.within(nx[a] + (nx[b] - nx[a]) * t, ny[a] + (ny[b] - ny[a]) * t, d) } } }
            if (thinAt.none { it } && neck.none { it }) return listOf(c)
            val keep = BooleanArray(c.size) { true }
            // where a stretch was cut out of the line's middle: the pieces beside it are a band's, cut by a thin dip
            val dipBeside = BooleanArray(c.size)
            var i = 0
            while (i < c.size) {
                if (!thinAt[i]) { i++; continue }
                var j = i
                while (j + 1 < c.size && thinAt[j + 1]) j++
                val runLen = (i + 1..j).sumOf { Math.hypot(nx[c[it]] - nx[c[it - 1]], ny[c[it]] - ny[c[it - 1]]) }
                val belowFloor = (i..j).any { 2 * nr[c[it]] < minFeatureUm }
                if (runLen > transitionUm || i == 0 || j == c.size - 1) for (k in i..j) keep[k] = false   // long, or a thin end
                else if (belowFloor) for (k in i..j) if (2 * nr[c[k]] < minFeatureUm) keep[k] = false   // short, but too thin for any line
                if ((runLen > transitionUm || belowFloor) && i > 0 && j < c.size - 1) { dipBeside[i - 1] = true; dipBeside[j + 1] = true }
                i = j + 1
            }
            val out = ArrayList<List<Int>>(); var cur = ArrayList<Int>(); var dip = false
            fun close() { if (cur.size >= 2) { out.add(cur); if (dip) bandPieces.add(cur) }; cur = ArrayList(); dip = false }
            for (k in c.indices) {
                if (!keep[k]) { close(); continue }
                if (neck[k] && cur.isNotEmpty()) { dip = true; close(); dip = true }
                cur.add(c[k]); if (dipBeside[k]) dip = true
            }
            close()
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
        // A line of no length (its ends on one point: a chain round a sampling cycle collapsed) lays nothing, yet cost a
        // restart and a retraction; it is dropped (under a micron: below the file's coordinates).
        // Nor is a piece of a band cut by a thin dip a corner void or a dot: between two dips, or a dip and the band's end, it
        // is the band, as wide as it is there, kept when at least as long as it is narrow (on a ridge lowest in its middle,
        // the leftover between a loop's sides, 0.3 to 0.6 mm wide over 0.5 to 0.9 mm each side of its thin middle, went
        // empty; on a test model's hull, a band 0.24 to 0.39 mm wide along 26 mm, cut where it dipped, lost every piece)
        return chains().flatMap { split(it) }.filter { c ->
            if (c.size < 2) return@filter false
            val len = length(c)
            if (len < 1.0) return@filter false
            val closed = c.first() == c.last()
            if (closed) return@filter len >= c.maxOf { width(it) }
            val along by lazy { outline.isNotEmpty() &&
                alongOutline(LongArray(2 * c.size) { k -> Math.round(if (k % 2 == 0) nx[c[k / 2]] else ny[c[k / 2]]) }, LongArray(c.size) { Math.round(width(c[it])) }, outline) }
            // (a band's piece: kept when at least as long as it is narrow, not a dot)
            if (bandPieces.any { it === c }) return@filter along || len >= c.minOf { width(it) }
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
