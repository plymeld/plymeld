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
     *     walls keep a loop wherever its center line exists, crowding two beads into a band.) The fit region is the outline
     *     shrunk mitred, so a loop keeps its corners sharp, but round where the miter would reach past its limit (a sharp
     *     notch's tip: no ground nearer it than the bead fits), and with the necks the mitre cuts at a turn into the area
     *     joined again where the exact shrink keeps them ([necksJoined]).
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
                maxBeadUm: Long = 2 * innerWidthUm,
                /**
                 * The object's surface on this layer where [outline] is one part of several (its whole cut): where a part's
                 * edge meets another part, a spot left bare is between walls, not on the surface (centerLines' forks). Null:
                 * [outline] is the surface.
                 */
                surface: List<Polygon>? = null,
                /** False: a narrow island whose beads are not taken keeps its walls with their slits unshared (a test's control). */
                keptWallsShared: Boolean = true,
                /** False: an island laid by its beads gives up what the walls hand to infill only by each piece's first point (a test's control). */
                innerTakenByArea: Boolean = true,
                /** False: a loop's fit keeps the necks its mitred shrink cut ([necksJoined]; a test's control). */
                joinNecks: Boolean = true): LayerWalls {
        val so = spacing(outerWidthUm, layerHeightUm)
        val si = spacing(innerWidthUm, layerHeightUm)
        // each loop's fit region (where its bead fits: its strict pieces, each an outer polygon and its holes) and its looser fit
        // (the hysteresis: [toleranceUm] further out), as the looser fit's pieces that hold some of the strict fit, each strict
        // piece by the looser piece it lies in ([of]); tiny looser pieces flagged
        // ([necks]: a neck the mitre cut was joined again in its strict or looser fit, [necksJoined])
        class Fit(val sk: Long, val inset: Long, val strict: List<List<Polygon>>, val of: IntArray, val pieces: List<List<Polygon>>, val tiny: BooleanArray,
                  val necks: Boolean) {
            /** How many strict pieces each looser piece holds. */
            val held = IntArray(pieces.size).also { n -> for (p in of) n[p]++ }
        }
        val fits = ArrayList<Fit>(loops)
        // the outline's edges in cells, for [necksJoined]: made when a fit first has a sharp turn into the area to read
        val outlineEdges = lazy(LazyThreadSafetyMode.NONE) { outlineEdgeGrid(outline, 2 * innerWidthUm) }
        run {
            var inset = outerWidthUm / 2
            for (k in 0 until loops) {
                if (k == 1) inset += (so + si) / 2 else if (k > 1) inset += si
                val sk = if (k == 0) so else si
                // the fit: the outline shrunk mitred (a loop's corners sharp), round past the miter limit (a bevel there kept
                // ground closer to a sharp notch's tip than the bead fits: the loop ran across the notch), with the necks the
                // mitre cut joined again ([necksJoined]), in the strict fit and in the looser one (in the looser one alone,
                // the hysteresis below made the strict pieces one loop, round the looser fit, which its mitred cut to the
                // inset less the tolerance cut again beside the neck where only the strict fit's mitre had cut it)
                var necks = false
                fun fit(d: Long): List<Polygon> {
                    val mitred = Offset.offset(outline, -d, Offset.Join.MITER, roundBeyondLimit = true)
                    val joined = if (joinNecks) necksJoined(outline, outlineEdges, mitred, d) else mitred
                    if (joined !== mitred) necks = true
                    return joined
                }
                val high = fit(inset + sk / 2)
                if (high.isEmpty()) break
                val strict = plymeld.geom.Polygons.group(high).map { (o, hs) -> listOf(o) + hs }
                val loose = if (toleranceUm <= 0) strict
                    else plymeld.geom.Polygons.group(fit(inset + sk / 2 - toleranceUm)).map { (o, hs) -> listOf(o) + hs }
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
                fits += Fit(sk, inset, strict, of, pieces, tiny, necks)
            }
        }
        val sample = maxOf(MIN_SAMPLE_UM, minFeatureUm / 2)
        // the thin pieces' lines, kept from the first build for the second (the pieces a kept tiny loop does not change)
        val pieceLines = HashMap<PieceKey, List<CenterLine>>()
        /** The walls with the tiny pieces left out except those [keep] names (loop index, piece index); and what their loops' bands leave. */
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
                // (kept within the inset less the tolerance exactly, round, where a neck was joined into the fit: a mitred
                // clip, deeper at the turn beside the neck, cut it again)
                val loose = if (joined.isEmpty()) emptyList() else Booleans.intersection(Offset.offset(joined.flatMap { f.pieces[it] }, f.sk / 2 - toleranceUm, Offset.Join.MITER),
                    Offset.offset(outline, -(f.inset - toleranceUm), if (f.necks) Offset.Join.ROUND else Offset.Join.MITER))
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
                interiorMinUm = minBeadUm - 2 * interiorOverlap, keepWiderUm = minFeatureUm + 2 * toleranceUm, endOverlap = endOverlap, surface = surface,
                pieceCache = pieceLines)
            return LayerWalls(walls, thick, if (covered.isEmpty()) center else center.map { reachInto(it, covered, endOverlap, sample) }) to left
        }
        val firstBuilt = build(emptySet()); val first = firstBuilt.first
        // a tiny piece left out where no center line took its place keeps its loop (a pin too small for a loop of length
        // keeps the one it has; it is no stretch of a band)
        val orphans = HashSet<Pair<Int, Int>>()
        for ((k, f) in fits.withIndex()) for (i in f.pieces.indices) if (f.tiny[i]) {
            val reach = Offset.offset(f.pieces[i], f.sk / 2 + toleranceUm + sample, Offset.Join.ROUND)
            val taken = first.center.any { c -> (0 until c.xy.size / 2).any { v -> reach.any { p -> p.isCcw() && plymeld.geom.Polygons.contains(p, c.xy[2 * v], c.xy[2 * v + 1]) } } }
            if (!taken) orphans += k to i
        }
        val (laid0, left0) = if (orphans.isEmpty()) firstBuilt else build(orphans)
        // a narrow island, where the set walls nowhere all fit (the innermost loop has no fit region in it), is laid by bead
        // count from its local width instead (Beads); every other island keeps what was laid above, the same object, its
        // slits between loops shared by the loops beside them (shareSlits, after the narrow islands are decided: a narrow
        // island's beads are judged against its walls as laid above, unshared)
        val interiorMin = minBeadUm - 2 * maxOf(innerWidthUm - si, Math.round(endOverlap * minBeadUm))
        if (loops <= 0) return shareSlits(outline, laid0, left0, outerWidthUm, innerWidthUm, so, si, interiorMin, maxBeadUm, toleranceUm, sample) { _, _ -> false }
        val last = fits.getOrNull(loops - 1)
        val islands = plymeld.geom.Polygons.group(outline)
        fun inIsland(o: Polygon, hs: List<Polygon>, x: Long, y: Long) = plymeld.geom.Polygons.contains(o, x, y) && hs.none { plymeld.geom.Polygons.contains(it, x, y) }
        val narrow = islands.filter { (o, hs) -> last == null || last.strict.none { piece -> inIsland(o, hs, piece[0].x(0), piece[0].y(0)) } }
        if (narrow.isEmpty()) return shareSlits(outline, laid0, left0, outerWidthUm, innerWidthUm, so, si, interiorMin, maxBeadUm, toleranceUm, sample) { _, _ -> false }
        val laid = laid0
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
            // (and the object's surface near it, where the outline is one part's: the walls the beads are judged against read it)
            val key = NarrowKey(isl.map { it.xy } + listOfNotNull(surface?.let { sf ->
                val b = o.bounds(); val m = maxBeadUm
                sf.flatMap { p -> (0 until p.size).filter { e -> val f = (e + 1) % p.size
                    !(maxOf(p.x(e), p.x(f)) < b[0] - m || minOf(p.x(e), p.x(f)) > b[2] + m || maxOf(p.y(e), p.y(f)) < b[1] - m || minOf(p.y(e), p.y(f)) > b[3] + m) }
                    .flatMap { e -> listOf(p.x(e), p.y(e)) } }.toLongArray() }), sig)
            val got = narrowCache[key] ?: narrowBeads(isl, laid, params, outerWidthUm, innerWidthUm, flank, floorUm, toleranceUm).also {
                if (narrowCache.size >= NARROW_CACHE_ENTRIES) narrowCache.clear()
                narrowCache[key] = it
            }
            if (got.isNotEmpty()) islandBeads[o] = got[0]
            got.isNotEmpty()
        }
        fun inTaken(x: Long, y: Long) = taken.any { (o, hs) -> inIsland(o, hs, x, y) }
        // the slits between loops shared everywhere but in the islands laid by their beads (a narrow island whose beads were
        // not taken keeps its walls, judged unshared above, and shares their slits as every other island does)
        val shared = shareSlits(outline, laid0, left0, outerWidthUm, innerWidthUm, so, si, interiorMin, maxBeadUm, toleranceUm, sample) { x, y ->
            if (!keptWallsShared) narrow.any { (o, hs) -> inIsland(o, hs, x, y) } else taken.isNotEmpty() && inTaken(x, y) }
        if (taken.isEmpty()) return shared
        // (each kept loop with its widths, where a slit is shared)
        val kept = shared.walls.mapIndexed { k, level -> level.indices.filter { !inTaken(level[it].x(0), level[it].y(0)) }.map { level[it] to shared.widthsOf(k, it) } }
            .dropLastWhile { it.isEmpty() }
        // what the walls hand to infill in an island laid by its beads is the beads': kept only within the other islands, by
        // area, not by a piece's first point alone. Where no loop of the walls is laid in such an island (its loop's fit
        // shorter than the transition length, a center line in its place), the walls hand most of the island to infill, a
        // piece whose first point, a mitred tip, can lie a few hundredths past the island's sharp corner, so read as outside
        // it: a bridge or solid infill was then laid across the island's own beads (a small tooth along an outline)
        val inner0 = shared.inner.filter { !inTaken(it.x(0), it.y(0)) }
        val takenSet = taken.map { it.first }.toHashSet()
        val restPolys = islands.filter { it.first !in takenSet }.flatMap { (o, hs) -> listOf(o) + hs }
        val takenBoxes = taken.map { it.first.bounds() }
        fun nearTaken(p: Polygon) = p.bounds().let { b -> takenBoxes.any { t -> b[0] <= t[2] && b[2] >= t[0] && b[1] <= t[3] && b[3] >= t[1] } }
        var cut = false
        val pieces = if (!innerTakenByArea || inner0.none { it.isCcw() && nearTaken(it) }) emptyList() else plymeld.geom.Polygons.group(inner0).flatMap { (o, hs) ->
            val piece = listOf(o) + hs
            if (!nearTaken(o)) piece else (if (restPolys.isEmpty()) emptyList() else Booleans.intersection(piece, restPolys)).let { left ->
                if (Math.abs(left.sumOf { it.signedAreaMm2() } - piece.sumOf { it.signedAreaMm2() }) < 1e-6) piece else { cut = true; left } }
        }
        // unchanged (the usual case): the pieces as they were, in their order
        val inner = if (cut) pieces else inner0
        val center = shared.center.filter { c -> val m = c.xy.size / 4 * 2; !inTaken(c.xy[m], c.xy[m + 1]) }
        // the beads' closed lines are loops of the walls, at their place from the outside, so they are laid as every loop is
        // (seams, scarf, order, travel); a loop at its level's set width throughout keeps no widths of its own; the open
        // lines stay lines
        val levels = ArrayList<ArrayList<Polygon>>(); val levelWidths = ArrayList<ArrayList<LongArray?>>()
        for (lv in kept) { levels.add(ArrayList(lv.map { it.first })); levelWidths.add(ArrayList(lv.map { it.second })) }
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
     * A loop's fit [mitred] (the outline shrunk by [d], mitred, round past the miter limit) with the necks the mitre cut
     * joined again; [mitred] itself where it cut none. Where the outline turns in (a notch, a dent, a hole's pointed end)
     * a mitred shrink cuts deeper than [d] (to the miter point, up to [Offset.MITER_LIMIT] times [d] from the turn), and a
     * band narrowing past such a turn can be cut through where the exact shrink (round there: every point at least [d] from
     * the outline) keeps a neck: the fit falls into two pieces, or two holes' grown outlines meet. The loops grown from it
     * then end in points facing each other across the cut, or cross the cut twice, closer than a line, and lie over each
     * other there. Each piece of the exact shrink that the mitre leaves out and that meets the mitred fit along two
     * separate stretches (a neck between two stretches of it, not the lune in one corner) is added back. Only such necks:
     * elsewhere a loop keeps its mitred corners (rounded, an inner loop's concave corner would pull away from the sharp
     * corner of the loop outside it, and their beads overlap there).
     *
     * The two shrinks differ only at a turn into the area within the miter limit, inside the miter's kite (the turn, the
     * moved edges' ends and the miter point). The exact shrink is made only where such a neck may be ([mayCutNeck]: a
     * turn whose miter reaches more than [NECK_TOUCH_UM] past [d], with another edge of the outline near it and the mitred
     * fit within its reach; alone, the kite's lune meets the fit along the two moved edges through the corner, one
     * stretch). Where a lune meets the fit is read off its own edges (an edge lying along the fit's, within the two
     * shrinks' grid rounding), not by which piece of the exact shrink holds which mitred piece: the two share their moved
     * edges, and a mitred piece's points lie on the exact piece's boundary as often as inside it.
     */
    private fun necksJoined(outline: List<Polygon>, edges: Lazy<EdgeGrid>, mitred: List<Polygon>, d: Long): List<Polygon> {
        if (mitred.isEmpty()) return mitred
        val mitredEdges = lazy(LazyThreadSafetyMode.NONE) { outlineEdgeGrid(mitred, NECK_CELL_UM) }
        if (!mayCutNeck(outline, edges, mitredEdges, d)) return mitred
        val added = neckPieces(outline, mitred, mitredEdges, d)
        return if (added.isEmpty()) mitred else Booleans.union(mitred, added)
    }

    /** The pieces of the exact shrink of [outline] by [d] that join necks of [mitred] (necksJoined). */
    private fun neckPieces(outline: List<Polygon>, mitred: List<Polygon>, mitredEdges: Lazy<EdgeGrid>, d: Long): List<Polygon> {
        val lunes = Booleans.difference(Offset.offset(outline, -d, Offset.Join.ROUND), mitred)
        if (lunes.isEmpty()) return emptyList()
        /** The stretches along which [rings] meets the mitred fit: runs of edges lying along it (an edge's middle within the two shrinks' grid rounding of it), a ring along it all the way one. */
        fun stretches(rings: List<Polygon>): Int = rings.sumOf { q ->
            val n = q.size
            val along = BooleanArray(n) { e -> val f = (e + 1) % n; mitredEdges.value.within((q.x(e) + q.x(f)) / 2.0, (q.y(e) + q.y(f)) / 2.0, NECK_TOUCH_UM.toDouble()) }
            if (along.all { it }) 1 else (0 until n).count { along[it] && !along[(it + n - 1) % n] }
        }
        val added = ArrayList<Polygon>()
        for ((o, hs) in plymeld.geom.Polygons.group(lunes)) if (stretches(listOf(o) + hs) >= 2) { added += o; added += hs }
        return added
    }

    /**
     * Whether the mitred shrink of [outline] by [d] may have cut a neck the exact one keeps (necksJoined): a turn into the
     * area within the miter limit whose miter reaches more than [NECK_TOUCH_UM] past [d], whose lune (in the triangle of
     * the moved edges' ends and the miter point) another edge of the outline than the turn's own two comes within [d] of,
     * or another turn's kite overlaps, and with the mitred fit ([mitredEdges]) within the miter's reach of the turn. A turn
     * on a smooth curve is none: its neighbors' edges stay more than [d] from its lune, their kites behind their own moved
     * edges.
     */
    private fun mayCutNeck(outline: List<Polygon>, edges: Lazy<EdgeGrid>, mitredEdges: Lazy<EdgeGrid>, d: Long): Boolean {
        // the kites: each turn's corner, its moved edges' ends and its miter point (x, y of the four in turn), its own two
        // edges' numbers, and its miter's reach
        val kx = ArrayList<DoubleArray>(); val ky = ArrayList<DoubleArray>(); val own = ArrayList<IntArray>(); val reaches = ArrayList<Double>()
        var base = 0
        for (p in outline) {
            val n = p.size
            for (i in 0 until if (n >= 3) n else 0) {
                val a = (i + n - 1) % n; val c = (i + 1) % n
                val e1x = (p.x(i) - p.x(a)).toDouble(); val e1y = (p.y(i) - p.y(a)).toDouble()
                val e2x = (p.x(c) - p.x(i)).toDouble(); val e2y = (p.y(c) - p.y(i)).toDouble()
                val l1 = Math.hypot(e1x, e1y); val l2 = Math.hypot(e2x, e2y)
                // the area on the left of each edge (the offset's rule): a turn to the right turns into it
                if (l1 == 0.0 || l2 == 0.0 || e1x * e2y - e1y * e2x >= 0) continue
                val cosHalf = Math.sqrt(maxOf(0.0, (1 + (e1x * e2x + e1y * e2y) / (l1 * l2)) / 2))
                // past the miter limit both shrinks are round there
                if (cosHalf * Offset.MITER_LIMIT < 1.0) continue
                val reach = d / cosHalf
                if (reach - d <= NECK_TOUCH_UM) continue
                val x = p.x(i).toDouble(); val y = p.y(i).toDouble()
                val n1x = -e1y / l1; val n1y = e1x / l1; val n2x = -e2y / l2; val n2y = e2x / l2
                val bl = Math.hypot(n1x + n2x, n1y + n2y)
                kx += doubleArrayOf(x, x + d * n1x, x + (n1x + n2x) / bl * reach, x + d * n2x)
                ky += doubleArrayOf(y, y + d * n1y, y + (n1y + n2y) / bl * reach, y + d * n2y)
                own += intArrayOf(base + a, base + i); reaches += reach
            }
            base += n
        }
        if (kx.isEmpty()) return false
        // the kites by their boxes, in cells
        val cell = maxOf(1L, d)
        val cells = HashMap<Long, MutableList<Int>>()
        fun key(cx: Long, cy: Long) = (cx shl 32) xor (cy and 0xffffffffL)
        fun cellsOf(xs: DoubleArray, ys: DoubleArray, f: (Long) -> Unit) {
            for (cx in Math.floorDiv(Math.floor(xs.min()).toLong(), cell)..Math.floorDiv(Math.ceil(xs.max()).toLong(), cell))
                for (cy in Math.floorDiv(Math.floor(ys.min()).toLong(), cell)..Math.floorDiv(Math.ceil(ys.max()).toLong(), cell)) f(key(cx, cy))
        }
        for (k in kx.indices) cellsOf(kx[k], ky[k]) { cells.getOrPut(it) { ArrayList() }.add(k) }
        val dd = d.toDouble()
        for (k in kx.indices) {
            val xs = kx[k]; val ys = ky[k]
            // the lune lies in the triangle of the moved edges' ends and the miter point (the rest of the kite is within d
            // of the corner): another edge within d of it (the exact shrink's reach and a mitred edge's), or another kite
            // over it (a mitred corner's), may part the lune's meeting with the fit; else it meets it along the two moved
            // edges through the corner, one stretch
            val tx = doubleArrayOf(xs[1], xs[2], xs[3]); val ty = doubleArrayOf(ys[1], ys[2], ys[3])
            val o = own[k]
            val near = edges.value.any(tx.min() - dd, ty.min() - dd, tx.max() + dd, ty.max() + dd) { e, idx ->
                idx != o[0] && idx != o[1] && segPolyDistance(e[0].toDouble(), e[1].toDouble(), e[2].toDouble(), e[3].toDouble(), tx, ty) < dd } ||
                run { var hit = false
                    cellsOf(tx, ty) { c -> if (!hit) for (j in cells[c] ?: emptyList()) if (j != k && convexOverlap(kx[j], ky[j], tx, ty)) { hit = true; break } }
                    hit }
            // and the mitred fit within the reach: a lune meets it within the kite
            if (near && mitredEdges.value.within(xs[0], ys[0], reaches[k] + NECK_TOUCH_UM)) return true
        }
        return false
    }

    /** The distance from segment a-b to the convex polygon [px], [py] (0 where they meet). */
    private fun segPolyDistance(ax: Double, ay: Double, bx: Double, by: Double, px: DoubleArray, py: DoubleArray): Double {
        if (convexOverlap(doubleArrayOf(ax, bx), doubleArrayOf(ay, by), px, py)) return 0.0
        var best = Double.MAX_VALUE
        for (i in px.indices) {
            val j = (i + 1) % px.size
            best = minOf(best, pointSeg(ax, ay, px[i], py[i], px[j], py[j]), pointSeg(bx, by, px[i], py[i], px[j], py[j]),
                pointSeg(px[i], py[i], ax, ay, bx, by))
        }
        return best
    }

    private fun pointSeg(x: Double, y: Double, ax: Double, ay: Double, bx: Double, by: Double): Double {
        val dx = bx - ax; val dy = by - ay; val l2 = dx * dx + dy * dy
        val t = if (l2 == 0.0) 0.0 else (((x - ax) * dx + (y - ay) * dy) / l2).coerceIn(0.0, 1.0)
        return Math.hypot(x - (ax + t * dx), y - (ay + t * dy))
    }

    /** Whether two convex polygons (a segment is one of two points) overlap or touch: no edge's direction separates them. */
    private fun convexOverlap(ax: DoubleArray, ay: DoubleArray, bx: DoubleArray, by: DoubleArray): Boolean {
        fun separated(sx: DoubleArray, sy: DoubleArray): Boolean {
            for (i in sx.indices) {
                val j = (i + 1) % sx.size
                if (sx.size == 2 && i == 1) break
                val nx = -(sy[j] - sy[i]); val ny = sx[j] - sx[i]
                if (nx == 0.0 && ny == 0.0) continue
                var aMin = Double.MAX_VALUE; var aMax = -Double.MAX_VALUE; var bMin = Double.MAX_VALUE; var bMax = -Double.MAX_VALUE
                for (q in ax.indices) { val v = ax[q] * nx + ay[q] * ny; aMin = minOf(aMin, v); aMax = maxOf(aMax, v) }
                for (q in bx.indices) { val v = bx[q] * nx + by[q] * ny; bMin = minOf(bMin, v); bMax = maxOf(bMax, v) }
                if (aMax < bMin || bMax < aMin) return true
            }
            return false
        }
        return !separated(ax, ay) && !separated(bx, by)
    }

    /** [outline]'s edges (ax, ay, bx, by), numbered polygon after polygon (edge i of a polygon from its vertex i), in cells of [cellUm]. */
    private fun outlineEdgeGrid(outline: List<Polygon>, cellUm: Long): EdgeGrid {
        val es = ArrayList<LongArray>()
        for (p in outline) for (e in 0 until p.size) { val f = (e + 1) % p.size; es += longArrayOf(p.x(e), p.y(e), p.x(f), p.y(f)) }
        return EdgeGrid(es, maxOf(1L, cellUm))
    }

    /** How near (µm) an edge of the exact shrink lies to the mitred one to run along it: the two shrinks' grid rounding. */
    private const val NECK_TOUCH_UM = 3L
    /** The cells (µm) of the mitred fit's edges: a lookup structure's size, not a dimension of the print. */
    private const val NECK_CELL_UM = 500L

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
     * [laid] with each loop widened where a slit too narrow for a line ([interiorMinUm], centerLines) is left between it and
     * another loop: the two lines beside the slit share its width, each a quarter of it nearer the slit and half of it wider
     * (its other edge stays where it was: the outer loop's on the outline), up to [maxBeadUm]. A web 1 mm wide between a hole
     * and the block's side, its two outer loops 0.15 mm apart over 2 mm, left that slit bare on every layer. The slit's width
     * at a point of a loop: the distance to the nearest other loop on that side, less the two loops' bands (half of each
     * one's spacing, [outerSpacingUm] for the outer loops, [innerSpacingUm] else): the leftover between them, which gets a
     * center line where it is at least [interiorMinUm] wide. A gap in a piece of [leftover] (what the loops' bands leave)
     * that holds some of the infill region is that region's corner, not a slit. A loop takes no more of a slit than its own
     * stretch facing it across the slit is away, measured straight (a lens-shaped loop's two sides converge toward its ends,
     * where the normal meets the other side at a slant). Read every [stepUm] along a loop's edges near another loop;
     * a loop unchanged anywhere (within [toleranceUm]) is the same polygon, with no widths.
     */
    private fun shareSlits(outline: List<Polygon>, laid: LayerWalls, leftover: List<Polygon>, outerWidthUm: Long, innerWidthUm: Long, outerSpacingUm: Long, innerSpacingUm: Long,
                           interiorMinUm: Long, maxBeadUm: Long, toleranceUm: Long, stepUm: Long, skip: (Long, Long) -> Boolean): LayerWalls {
        val walls = laid.walls
        if (walls.isEmpty() || interiorMinUm <= toleranceUm) return laid
        // every loop's edges: (level, loop, edge) and the loop's arc length at the edge's start
        // (and the center lines' edges, level -1: a leftover with a line in it is the line's, no slit; [sp]: the band's width,
        // the line's spacing)
        class Edge(val k: Int, val li: Int, val e: Int, val ax: Long, val ay: Long, val bx: Long, val by: Long, val arc: Double, val sp: Double)
        val edges = ArrayList<Edge>(); val loopLen = HashMap<Long, Double>(); val loopBase = HashMap<Long, Int>()
        fun loopKey(k: Int, li: Int) = (k.toLong() shl 32) or li.toLong()
        fun width(k: Int) = if (k == 0) outerWidthUm else innerWidthUm
        fun spacingOf(k: Int) = if (k == 0) outerSpacingUm else innerSpacingUm
        for ((k, lv) in walls.withIndex()) for ((li, p) in lv.withIndex()) {
            loopBase[loopKey(k, li)] = edges.size
            var arc = 0.0
            val ws = laid.widthsOf(k, li)
            for (e in 0 until p.size) {
                val f = (e + 1) % p.size
                val sp = if (ws == null) spacingOf(k).toDouble() else (ws[e] + ws[f]) / 2.0 - (width(k) - spacingOf(k))
                edges += Edge(k, li, e, p.x(e), p.y(e), p.x(f), p.y(f), arc, sp)
                arc += Math.hypot((p.x(f) - p.x(e)).toDouble(), (p.y(f) - p.y(e)).toDouble())
            }
            loopLen[loopKey(k, li)] = arc
        }
        val flank = (innerWidthUm - innerSpacingUm).toDouble()
        for ((ci, c) in laid.center.withIndex()) for (e in 0 until c.xy.size / 2 - 1)
            edges += Edge(-1, ci, e, c.xy[2 * e], c.xy[2 * e + 1], c.xy[2 * e + 2], c.xy[2 * e + 3], 0.0, (c.widths[e] + c.widths[e + 1]) / 2.0 - flank)
        val reach = (maxOf(outerSpacingUm, innerSpacingUm, maxBeadUm) + interiorMinUm).toDouble()
        val cell = maxOf(reach.toLong(), 1L)
        val grid = HashMap<Long, MutableList<Int>>()
        fun gkey(cx: Long, cy: Long) = (cx shl 32) xor (cy and 0xffffffffL)
        for ((i, e) in edges.withIndex())
            for (cx in Math.floorDiv(minOf(e.ax, e.bx), cell)..Math.floorDiv(maxOf(e.ax, e.bx), cell))
                for (cy in Math.floorDiv(minOf(e.ay, e.by), cell)..Math.floorDiv(maxOf(e.ay, e.by), cell)) grid.getOrPut(gkey(cx, cy)) { ArrayList() }.add(i)
        fun inModel(x: Double, y: Double) = outline.count { plymeld.geom.Polygons.contains(it, Math.round(x), Math.round(y)) } % 2 == 1
        // the pieces of what the loops leave ([leftover]) that hold some of the infill region: a gap in one, between a loop and
        // a line in its corner, is the corner of the area the walls hand to infill, not a slit between walls (a loop widened
        // into it narrowed that area below what infill is handed, 0.80 to 0.79 mm, and left it bare inside the walls)
        val infillPieces by lazy {
            if (laid.inner.isEmpty()) emptyList() else {
                plymeld.geom.Polygons.group(leftover).filter { (o, hs) -> laid.inner.any { p -> p.isCcw() &&
                    (0 until p.size).any { v -> plymeld.geom.Polygons.contains(o, p.x(v), p.y(v)) && hs.none { h -> plymeld.geom.Polygons.contains(h, p.x(v), p.y(v)) } } } }
            }
        }
        fun inInfill(x: Double, y: Double): Boolean { val px = Math.round(x); val py = Math.round(y)
            return infillPieces.any { (o, hs) -> plymeld.geom.Polygons.contains(o, px, py) && hs.none { plymeld.geom.Polygons.contains(it, px, py) } } }
        val nearMemo = HashMap<List<Long>, Set<Int>>()
        fun nearCells(x0: Double, y0: Double, x1: Double, y1: Double): Set<Int> {
            val cx0 = Math.floorDiv((x0 - reach).toLong(), cell); val cx1 = Math.floorDiv((x1 + reach).toLong(), cell)
            val cy0 = Math.floorDiv((y0 - reach).toLong(), cell); val cy1 = Math.floorDiv((y1 + reach).toLong(), cell)
            // a range of cells read once (each point of a long edge reads the few cells round it)
            return nearMemo.getOrPut(listOf(cx0, cx1, cy0, cy1)) {
                val out = HashSet<Int>()
                for (cx in cx0..cx1) for (cy in cy0..cy1) grid[gkey(cx, cy)]?.let { out.addAll(it) }
                out
            }
        }
        /** Whether [o] is of loop ([k], [li]) within [gap] of arc length [arc] along it (or of center line [li] where [k] is -1). */
        fun ownNear(o: Edge, k: Int, li: Int, arc: Double, gap: Double): Boolean {
            if (o.k != k || o.li != li) return false
            if (k < 0) return true
            val len = loopLen.getValue(loopKey(k, li)); val lo = Math.hypot((o.bx - o.ax).toDouble(), (o.by - o.ay).toDouble())
            if (arc >= o.arc && arc <= o.arc + lo) return true
            val da = Math.abs(o.arc - arc).let { minOf(it, len - it) }
            val db = Math.abs(o.arc + lo - arc).let { minOf(it, len - it) }
            return minOf(da, db) < gap
        }
        val noBand = doubleArrayOf(Double.POSITIVE_INFINITY, -1.0)
        /** Where the ray from (px, py) along (dx, dy) first enters a band of [cand] not [skipped]: the distance and the edge. */
        fun firstBand(px: Double, py: Double, dx: Double, dy: Double, cand: Collection<Int>, skipped: (Edge) -> Boolean): DoubleArray {
            var best = Double.POSITIVE_INFINITY; var bi = -1
            for (i in cand) {
                val o = edges[i]
                if (skipped(o)) continue
                val t = Beads.rayCapsule(px, py, dx, dy, o.ax.toDouble(), o.ay.toDouble(), o.bx.toDouble(), o.by.toDouble(), o.sp / 2)
                if (t < best) { best = t; bi = i }
            }
            return if (bi < 0) noBand else doubleArrayOf(best, bi.toDouble())
        }
        /**
         * The slit [o]'s line sees toward (px, py): from the point of its center line nearest (hx, hy) (where a ray from
         * (px, py) entered its band), along its normal toward (px, py), to the first other band; less half its band.
         */
        fun backGap(o: Edge, hx: Double, hy: Double, px: Double, py: Double): Double {
            val ax = o.ax.toDouble(); val ay = o.ay.toDouble(); val dx = o.bx - ax; val dy = o.by - ay; val l2 = dx * dx + dy * dy
            if (l2 == 0.0) return 0.0
            val u = (((hx - ax) * dx + (hy - ay) * dy) / l2).coerceIn(0.0, 1.0)
            val qx = ax + u * dx; val qy = ay + u * dy; val l = Math.sqrt(l2)
            var nx = -dy / l; var ny = dx / l
            if (nx * (px - qx) + ny * (py - qy) < 0) { nx = -nx; ny = -ny }
            val arcQ = o.arc + u * l
            val gap = if (o.k < 0) 0.0 else 2.0 * width(o.k) + interiorMinUm
            val hit = firstBand(qx, qy, nx, ny, nearCells(qx, qy, qx, qy)) { e -> ownNear(e, o.k, o.li, arcQ, gap) }
            return hit[0] - o.sp / 2
        }
        val newWalls = walls.map { ArrayList(it) }
        val newWidths = walls.mapIndexed { k, lv -> ArrayList<LongArray?>(lv.indices.map { laid.widthsOf(k, it) }) }
        var changed = false
        for ((k, lv) in walls.withIndex()) for ((li, p) in lv.withIndex()) {
            if (laid.widthsOf(k, li) != null || skip(p.x(0), p.y(0))) continue      // laid by its width already, or a narrow island's
            val w = width(k).toDouble(); val s = spacingOf(k).toDouble()
            // the loop's own edges farther along it than this count as another loop (its far side across a narrow middle)
            val ownGap = 2 * w + interiorMinUm
            val xs = ArrayList<Long>(); val ws = ArrayList<Long>()
            var any = false
            for (e in 0 until p.size) {
                val f = (e + 1) % p.size
                val ax = p.x(e).toDouble(); val ay = p.y(e).toDouble(); val bx = p.x(f).toDouble(); val by = p.y(f).toDouble()
                val el = Math.hypot(bx - ax, by - ay)
                val arc0 = edges[loopBase.getValue(loopKey(k, li)) + e].arc
                // the other edges near this one (within reach of its box; along a long edge, of each point read)
                val long = el > 2.0 * cell
                val near = if (long) emptySet() else nearCells(minOf(ax, bx), minOf(ay, by), maxOf(ax, bx), maxOf(ay, by))
                val steps = if (el == 0.0) 1 else maxOf(1, Math.ceil(el / stepUm).toInt())
                // the edge's normal, to its left (the way the loop runs)
                val nx = if (el == 0.0) 0.0 else -(by - ay) / el; val ny = if (el == 0.0) 0.0 else (bx - ax) / el
                for (q in 0 until steps) {
                    val t = q.toDouble() / steps
                    val px = ax + (bx - ax) * t; val py = ay + (by - ay) * t; val arc = arc0 + el * t
                    val cand = if (long) nearCells(px, py, px, py) else near
                    // each side, how far along the normal the first other band begins: the leftover from this band's edge to it
                    val left = if (el > 0) firstBand(px, py, nx, ny, cand) { o -> ownNear(o, k, li, arc, ownGap) } else noBand
                    val right = if (el > 0) firstBand(px, py, -nx, -ny, cand) { o -> ownNear(o, k, li, arc, ownGap) } else noBand
                    // the slit each side, and the share of it this line takes (half its width, a quarter nearer): only where the
                    // line across sees it too (along its own normal back to this band: a loop's sharp inner corner and the next
                    // loop's round turn leave a void only the first sees, there by any loops' corners, no slit), the narrower
                    // of the two; only in the model (between two islands' outer loops the gap is outside it)
                    fun slit(hit: DoubleArray, sx: Double): Double {
                        val g = hit[0] - s / 2
                        if (!(g > toleranceUm && g < interiorMinUm)) return 0.0
                        if (!inModel(px + sx * (s / 2 + g / 2) * nx, py + sx * (s / 2 + g / 2) * ny)) return 0.0
                        if (inInfill(px + sx * (s / 2 + g / 2) * nx, py + sx * (s / 2 + g / 2) * ny)) return 0.0
                        val o = edges[hit[1].toInt()]
                        val back = backGap(o, px + sx * hit[0] * nx, py + sx * hit[0] * ny, px, py)
                        if (back <= toleranceUm) return 0.0
                        // the loop's own other side, measured straight: where its two sides converge (toward a lens's tip) the
                        // part of it nearer than the slit is within [ownGap] of this point along the loop, which the rays pass
                        // over; a bead widened by the slit seen along the normal there reached over that side
                        var d = Double.POSITIVE_INFINITY
                        for (i in cand) { val e = edges[i]
                            if (e.k != k || e.li != li || ownNear(e, k, li, arc, w)) continue
                            val ax = e.ax.toDouble(); val ay = e.ay.toDouble(); val ex = e.bx - ax; val ey = e.by - ay; val l2 = ex * ex + ey * ey
                            // only a stretch facing this one (its side turned more than a right angle from this edge's)
                            if (-ey * nx + ex * ny >= 0) continue
                            val u = if (l2 == 0.0) 0.0 else (((px - ax) * ex + (py - ay) * ey) / l2).coerceIn(0.0, 1.0)
                            val qx = ax + u * ex - px; val qy = ay + u * ey - py
                            if (qx * sx * nx + qy * sx * ny <= 0) continue
                            d = minOf(d, Math.hypot(qx, qy) - e.sp / 2 - s / 2)
                        }
                        val share = minOf(g, back, d)
                        return if (share > toleranceUm) share else 0.0
                    }
                    val gl = slit(left, 1.0); val gr = slit(right, -1.0)
                    val mx = px + nx * (gl - gr) / 4; val my = py + ny * (gl - gr) / 4
                    val nw = minOf(w + (gl + gr) / 2, maxBeadUm.toDouble())
                    if (gl > 0 || gr > 0) any = true
                    // an edge with no slit keeps its own vertex only
                    if (q > 0 && gl == 0.0 && gr == 0.0 && (ws.lastOrNull() ?: 0L) == width(k)) continue
                    xs.add(Math.round(mx)); xs.add(Math.round(my)); ws.add(Math.round(nw))
                }
            }
            if (!any) continue
            // simplified to the tolerance as any line, as a closed path (first point repeated)
            val c = simplified(CenterLine((xs + listOf(xs[0], xs[1])).toLongArray(), (ws + listOf(ws[0])).toLongArray()), toleranceUm)
            val m = c.xy.size / 2 - 1
            if (m < 3) continue
            newWalls[k][li] = Polygon(c.xy.copyOf(2 * m))
            val cw = c.widths.copyOf(m)
            newWidths[k][li] = if (cw.all { Math.abs(it - width(k)) <= toleranceUm }) null else cw
            changed = true
        }
        if (!changed) return laid
        return LayerWalls(newWalls, laid.inner, laid.center, newWidths)
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
        if (mb.wideUm2 == 0.0 && mb.widestUm <= NARROW_WIDEST_UM + mb.cellUm && mb.outsideUm <= NARROW_OUTSIDE_UM + mb.cellUm &&
            mb.doubledUm2 <= NARROW_DOUBLED_SLACK_UM2) return listOf(beads)
        val oldLines = ArrayList<IslandCheck.Line>()
        for ((k, level) in laid.walls.withIndex()) for ((li, p) in level.withIndex()) if (mine(p.x(0), p.y(0))) {
            val ws = laid.widthsOf(k, li)
            oldLines += IslandCheck.Line(p.xy, DoubleArray(p.size) { (ws?.get(it) ?: if (k == 0) outerWidthUm else innerWidthUm).toDouble() }, true)
        }
        for (c in laid.center) { val m = c.xy.size / 4 * 2; if (mine(c.xy[m], c.xy[m + 1])) oldLines += IslandCheck.Line(c.xy, DoubleArray(c.widths.size) { c.widths[it].toDouble() }, false) }
        val oldCovered = laid.inner.filter { mine(it.x(0), it.y(0)) }
        val mo = IslandCheck.measure(isl, oldLines, oldCovered, flank, floorUm, toleranceUm.toDouble())
        val ok = mb.wideUm2 <= mo.wideUm2 + NARROW_WIDE_SLACK_UM2 && mb.widestUm <= maxOf(mo.widestUm, NARROW_WIDEST_UM) + mb.cellUm &&
            mb.outsideUm <= maxOf(mo.outsideUm, NARROW_OUTSIDE_UM) + mb.cellUm &&
            (mb.doubledUm2 <= mo.doubledUm2 + NARROW_DOUBLED_SLACK_UM2 || (mo.wideUm2 - mb.wideUm2 > NARROW_HOLE_UM2 && mb.doubledUm2 <= NARROW_DOUBLED_MAX_UM2))
        return if (ok) listOf(beads) else emptyList()
    }

    /** A thin piece's points (each polygon's), compared by content: centerLines' piece cache key. */
    class PieceKey(private val xys: List<LongArray>) {
        private val hash = xys.fold(1) { h, a -> 31 * h + a.contentHashCode() }
        override fun hashCode() = hash
        override fun equals(other: Any?) = other is PieceKey && other.hash == hash && other.xys.size == xys.size && xys.indices.all { other.xys[it].contentEquals(xys[it]) }
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
            // each edge in the cells it passes through: column by column, the rows its stretch in the column spans (a long
            // slanted edge is not entered in every cell of its box)
            for ((i, e) in edges.withIndex()) {
                val x0 = minOf(e[0], e[2]); val x1 = maxOf(e[0], e[2])
                val dx = (e[2] - e[0]).toDouble(); val dy = (e[3] - e[1]).toDouble()
                fun yAt(x: Long) = if (dx == 0.0) e[1].toDouble() else e[1] + (x - e[0]) * dy / dx
                for (cx in Math.floorDiv(x0, cellUm)..Math.floorDiv(x1, cellUm)) {
                    val xa = maxOf(x0, cx * cellUm); val xb = minOf(x1, cx * cellUm + cellUm)
                    val ya = if (dx == 0.0) minOf(e[1], e[3]).toDouble() else minOf(yAt(xa), yAt(xb))
                    val yb = if (dx == 0.0) maxOf(e[1], e[3]).toDouble() else maxOf(yAt(xa), yAt(xb))
                    for (cy in Math.floorDiv(Math.floor(ya).toLong(), cellUm)..Math.floorDiv(Math.ceil(yb).toLong(), cellUm))
                        cells.getOrPut(key(cx, cy)) { ArrayList() }.add(i)
                }
            }
        }
        /** Whether [test] holds for an edge (its points, its number) in a cell the box x0..x1, y0..y1 meets. */
        fun any(x0: Double, y0: Double, x1: Double, y1: Double, test: (LongArray, Int) -> Boolean): Boolean {
            for (i in Math.floorDiv(Math.floor(x0).toLong(), cellUm)..Math.floorDiv(Math.ceil(x1).toLong(), cellUm))
                for (j in Math.floorDiv(Math.floor(y0).toLong(), cellUm)..Math.floorDiv(Math.ceil(y1).toLong(), cellUm))
                    for (k in cells[key(i, j)] ?: continue) if (test(edges[k], k)) return true
            return false
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
                    interiorMinUm: Long = 0L,
                    /**
                     * A short line is kept where its area is wider than this somewhere (a spot that wide left bare is a hole,
                     * not a corner void: the pre-print check refuses it); [Long.MAX_VALUE]: no such exception.
                     */
                    keepWiderUm: Long = Long.MAX_VALUE,
                    /** How far past bead contact a line springing from another reaches into it, a fraction of its width (giveWay). */
                    endOverlap: Double = 0.0,
                    /** The object's surface where [outline] is one part of several (Walls.arachne); null: [outline]. */ surface: List<Polygon>? = null,
                    /**
                     * Each thin piece's lines by its points, for calls with the same other arguments (Walls.arachne's second
                     * build): a piece's lines are a function of the piece and those arguments alone. Null: none kept.
                     */
                    pieceCache: MutableMap<PieceKey, List<CenterLine>>? = null): List<CenterLine> {
        // one triangulation per connected thin piece: one for a whole layer's thin areas ran out of
        // memory on a large test model's layer
        val lines = plymeld.geom.Polygons.group(thin).flatMap { (o, hs) -> val piece = listOf(o) + hs
            fun lay() = centerLinesOfPiece(piece, minBeadUm, minFeatureUm, maxWidthUm, minLengthFactor, transitionUm, outline,
                maxOf(minFeatureUm, interiorMinUm), keepWiderUm, surface)
            if (pieceCache == null) lay() else pieceCache.getOrPut(PieceKey(piece.map { it.xy })) { lay() } }
        // two ends are not joined across a place the model is narrower than min_feature: the walls lay no line there
        val outlineGrid by lazy { EdgeGrid(outline.flatMap { p -> (0 until p.size).map { e -> longArrayOf(p.x(e), p.y(e), p.x((e + 1) % p.size), p.y((e + 1) % p.size)) } }, maxOf(maxWidthUm, 4 * minFeatureUm)) }
        val joined = joinEnds(lines, transitionUm, maxOf(MIN_SAMPLE_UM, minFeatureUm / 2)) { ax, ay, bx, by -> outline.isEmpty() ||
            (0..8).none { k -> outlineGrid.within(ax + (bx - ax) * k / 8.0, ay + (by - ay) * k / 8.0, minFeatureUm / 2.0) } }
        return giveWay(joined, endOverlap, maxOf(MIN_SAMPLE_UM, minFeatureUm / 2))
    }

    /**
     * Each open line's end that springs from another line (lies on its center line, within [slackUm]: a third branch of a
     * junction the other two run through) cut back to where its bead reaches into the other's by [overlap] of its width past
     * contact, as an end against a wall's bead does (reachInto); laid from the junction, the two lines laid the same plastic
     * twice across the other's width. A line within that reach of the other throughout is dropped. Ends are cut one at a
     * time against the lines as they then are, so two ends on one point are not both cut back.
     */
    private fun giveWay(lines: List<CenterLine>, overlap: Double, slackUm: Long): List<CenterLine> {
        if (lines.size < 2) return lines
        val out = lines.toMutableList<CenterLine?>()
        fun box(c: CenterLine): LongArray {
            var x0 = Long.MAX_VALUE; var y0 = Long.MAX_VALUE; var x1 = Long.MIN_VALUE; var y1 = Long.MIN_VALUE
            for (k in 0 until c.xy.size / 2) { x0 = minOf(x0, c.xy[2 * k]); x1 = maxOf(x1, c.xy[2 * k]); y0 = minOf(y0, c.xy[2 * k + 1]); y1 = maxOf(y1, c.xy[2 * k + 1]) }
            return longArrayOf(x0, y0, x1, y1)
        }
        val boxes = out.map { box(it!!) }.toMutableList()
        /** The nearest point of [c]'s center line to (px, py): its distance and the line's half width there. */
        fun nearest(c: CenterLine, px: Double, py: Double): DoubleArray {
            var best = Double.MAX_VALUE; var half = 0.0
            for (m in 0 until c.xy.size / 2 - 1) {
                val ax = c.xy[2 * m].toDouble(); val ay = c.xy[2 * m + 1].toDouble(); val dx = c.xy[2 * m + 2] - ax; val dy = c.xy[2 * m + 3] - ay
                val l2 = dx * dx + dy * dy; val t = if (l2 == 0.0) 0.0 else (((px - ax) * dx + (py - ay) * dy) / l2).coerceIn(0.0, 1.0)
                val d = Math.hypot(px - ax - t * dx, py - ay - t * dy)
                if (d < best) { best = d; half = (c.widths[m] + t * (c.widths[m + 1] - c.widths[m])) / 2 }
            }
            return doubleArrayOf(best, half)
        }
        fun isClosed(c: CenterLine): Boolean { val n = c.xy.size / 2; return n >= 2 && c.xy[0] == c.xy[2 * n - 2] && c.xy[1] == c.xy[2 * n - 1] }
        for (i in out.indices) for (last in listOf(false, true)) {
            val c = out[i] ?: continue
            val n = c.xy.size / 2
            if (n < 2 || isClosed(c)) continue
            fun at(k: Int) = if (last) n - 1 - k else k
            val px = c.xy[2 * at(0)].toDouble(); val py = c.xy[2 * at(0) + 1].toDouble()
            // the line it springs from: another line's center line within the slack of this end
            var from = -1; var fromHalf = 0.0; var fromD = Double.MAX_VALUE
            for (j in out.indices) {
                if (j == i) continue
                val o = out[j] ?: continue; val b = boxes[j]
                if (px < b[0] - slackUm || px > b[2] + slackUm || py < b[1] - slackUm || py > b[3] + slackUm) continue
                val (d, h) = nearest(o, px, py).let { it[0] to it[1] }
                if (d <= slackUm && d < fromD) { from = j; fromHalf = h; fromD = d }
            }
            if (from < 0) continue
            val o = out[from]!!
            // walk in from the end to where this line's bead reaches no further into the other's than the overlap allows
            fun need(k: Int, d: Double, h: Double) = d >= h + c.widths[at(k)] / 2.0 - overlap * c.widths[at(k)]
            var cut = -1; var cx = 0.0; var cy = 0.0; var cw = 0.0
            var prevX = px; var prevY = py
            for (k in 1 until n) {
                val qx = c.xy[2 * at(k)].toDouble(); val qy = c.xy[2 * at(k) + 1].toDouble()
                val nq = nearest(o, qx, qy)
                if (!need(k, nq[0], nq[1])) { prevX = qx; prevY = qy; continue }
                // the crossing within the step from k - 1 to k, by halving
                var lo = 0.0; var hi = 1.0
                val w0 = c.widths[at(k - 1)].toDouble(); val w1 = c.widths[at(k)].toDouble()
                repeat(20) {
                    val t = (lo + hi) / 2; val x = prevX + (qx - prevX) * t; val y = prevY + (qy - prevY) * t; val w = w0 + (w1 - w0) * t
                    val nm = nearest(o, x, y)
                    if (nm[0] >= nm[1] + w / 2 - overlap * w) hi = t else lo = t
                }
                cut = k; cx = prevX + (qx - prevX) * hi; cy = prevY + (qy - prevY) * hi; cw = w0 + (w1 - w0) * hi
                break
            }
            if (cut < 0) { out[i] = null; continue }
            // the line from the cut on (in its own direction)
            val keep = (cut until n).map { at(it) }.let { if (last) it.reversed() else it }
            val xs = ArrayList<Long>(); val ws = ArrayList<Long>()
            fun add(x: Long, y: Long, w: Long) { xs.add(x); xs.add(y); ws.add(w) }
            if (!last) add(Math.round(cx), Math.round(cy), Math.round(cw))
            for (k in keep) add(c.xy[2 * k], c.xy[2 * k + 1], c.widths[k])
            if (last) add(Math.round(cx), Math.round(cy), Math.round(cw))
            val cutLine = CenterLine(xs.toLongArray(), ws.toLongArray(), c.reachUm, c.wall)
            out[i] = cutLine; boxes[i] = box(cutLine)
        }
        return out.filterNotNull()
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
    private fun joinEnds(lines: List<CenterLine>, joinUm: Long, slackUm: Long, crossable: (Double, Double, Double, Double) -> Boolean): List<CenterLine> {
        val open = lines.filter { l -> val n = l.xy.size / 2; !(l.xy[0] == l.xy[2 * n - 2] && l.xy[1] == l.xy[2 * n - 1]) }.toMutableList()
        val closed = lines - open.toSet()
        fun end(l: CenterLine, last: Boolean): LongArray { val k = if (last) l.xy.size / 2 - 1 else 0; return longArrayOf(l.xy[2 * k], l.xy[2 * k + 1]) }
        fun reversed(l: CenterLine): CenterLine {
            val n = l.xy.size / 2
            return CenterLine(LongArray(2 * n) { k -> l.xy[2 * (n - 1 - k / 2) + k % 2] }, LongArray(n) { l.widths[n - 1 - it] })
        }
        val lim = joinUm.toDouble() * joinUm
        // the way a line runs out at an end (unit vector), for ends on one point: the pair that runs on straightest is joined
        // (three lines from one junction: the two in line make one path, the third springs from it and gives way)
        fun outward(l: CenterLine, last: Boolean): DoubleArray {
            val n = l.xy.size / 2; val k = if (last) n - 1 else 0
            var j = if (last) n - 2 else 1
            while (j in 0 until n && l.xy[2 * j] == l.xy[2 * k] && l.xy[2 * j + 1] == l.xy[2 * k + 1]) j += if (last) -1 else 1
            if (j !in 0 until n) return doubleArrayOf(0.0, 0.0)
            val dx = (l.xy[2 * k] - l.xy[2 * j]).toDouble(); val dy = (l.xy[2 * k + 1] - l.xy[2 * j + 1]).toDouble(); val d = Math.hypot(dx, dy)
            return doubleArrayOf(dx / d, dy / d)
        }
        // An end that springs from another line (lies on its center line, within [slackUm], away from that line's ends) is
        // a branch of a junction the other runs through: it joins nothing across a gap (giveWay cuts it back). Joined to an
        // end nearby, the joining segment ran back over the other line's branch (a junction's three forks: one laid out and
        // back over another).
        val s = slackUm.toDouble()
        // each line's box, to pass over the lines an end is nowhere near
        val boxes = java.util.IdentityHashMap<CenterLine, LongArray>()
        fun boxOf(m: CenterLine): LongArray = boxes.getOrPut(m) {
            var x0 = Long.MAX_VALUE; var y0 = Long.MAX_VALUE; var x1 = Long.MIN_VALUE; var y1 = Long.MIN_VALUE
            for (k in 0 until m.xy.size / 2) { x0 = minOf(x0, m.xy[2 * k]); x1 = maxOf(x1, m.xy[2 * k]); y0 = minOf(y0, m.xy[2 * k + 1]); y1 = maxOf(y1, m.xy[2 * k + 1]) }
            longArrayOf(x0, y0, x1, y1)
        }
        fun springs(l: CenterLine, last: Boolean): Boolean {
            val p = end(l, last); val px = p[0].toDouble(); val py = p[1].toDouble()
            for (m in open.asSequence().filter { it !== l } + closed.asSequence()) {
                val b = boxOf(m)
                if (px < b[0] - s || px > b[2] + s || py < b[1] - s || py > b[3] + s) continue
                val n = m.xy.size / 2
                if (Math.hypot(px - m.xy[0], py - m.xy[1]) <= s || Math.hypot(px - m.xy[2 * n - 2], py - m.xy[2 * n - 1]) <= s) continue
                for (k in 0 until n - 1) if (pointSegUm(px, py, longArrayOf(m.xy[2 * k], m.xy[2 * k + 1], m.xy[2 * k + 2], m.xy[2 * k + 3])) <= s) return true
            }
            return false
        }
        // whether each end springs, kept from one join to the next: a join changes it only for an end within the slack of the
        // joined line's ends or of the segment joining the two (elsewhere the joined line is what the two were, end for end)
        val springing = java.util.IdentityHashMap<CenterLine, Array<Boolean?>>()
        fun springsMemo(i: Int, last: Boolean): Boolean {
            val l = open[i]; val m = springing.getOrPut(l) { arrayOfNulls(2) }; val k = if (last) 1 else 0
            return m[k] ?: springs(l, last).also { m[k] = it }
        }
        while (true) {
            var best = Double.MAX_VALUE; var bi = -1; var bj = -1; var biLast = false; var bjLast = false
            for (i in open.indices) for (j in i + 1 until open.size) for (il in listOf(false, true)) for (jl in listOf(false, true)) {
                val a = end(open[i], il); val b = end(open[j], jl)
                val dx = (a[0] - b[0]).toDouble(); val dy = (a[1] - b[1]).toDouble(); val d = dx * dx + dy * dy
                if (d > lim) continue
                if (d > 1.0 && (springsMemo(i, il) || springsMemo(j, jl))) continue
                // closest first; ends on one point (within a micron) before any other, the straighter pair first (the dot of
                // the ways they run out: -1 runs straight on)
                val score = if (d <= 1.0) { val u = outward(open[i], il); val v = outward(open[j], jl); -2.0 + u[0] * v[0] + u[1] * v[1] } else d
                if (score < best && crossable(a[0].toDouble(), a[1].toDouble(), b[0].toDouble(), b[1].toDouble())) { best = score; bi = i; bj = j; biLast = il; bjLast = jl }
            }
            if (bi < 0) break
            // orient: open[bi] ends at the joined end, open[bj] starts there
            val a = if (biLast) open[bi] else reversed(open[bi])
            val b = if (bjLast) reversed(open[bj]) else open[bj]
            val joined = CenterLine(a.xy + b.xy, a.widths + b.widths)
            springing.remove(open[bi]); springing.remove(open[bj])
            open.removeAt(bj); open.removeAt(bi); open.add(joined)
            val na = a.xy.size / 2
            val seg = longArrayOf(a.xy[2 * na - 2], a.xy[2 * na - 1], b.xy[0], b.xy[1])
            val f = end(joined, false); val g = end(joined, true)
            for ((l, m) in springing) for (k in 0..1) if (m[k] != null) {
                val p = end(l, k == 1); val px = p[0].toDouble(); val py = p[1].toDouble()
                if (pointSegUm(px, py, seg) <= s || Math.hypot(px - f[0], py - f[1]) <= s || Math.hypot(px - g[0], py - g[1]) <= s) m[k] = null
            }
        }
        return closed + open
    }

    private fun centerLinesOfPiece(thin: List<Polygon>, minBeadUm: Long, minFeatureUm: Long, maxWidthUm: Long, minLengthFactor: Double, transitionUm: Long, outline: List<Polygon>,
                                   interiorMinUm: Long, keepWiderUm: Long, surface: List<Polygon>?): List<CenterLine> {
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
        // A fork is no corner void where its area reaches out past the line at its junction by more than [keepWiderUm]: left
        // bare, that is a hole inside the walls (a strip's line ending in a head 1 mm across, both of the head's forks
        // shorter than their width, left a pocket 0.34 mm wide in the head's point). The reach of a node: how far its disc
        // lies beyond the junction's line end (its width there), no more than the disc's own width; a node also stands for
        // the forks cut at it before ([heldBy]), so a trunk whose forks went is judged by what they reached too. Only between
        // walls: a node at the model's surface reaches the outline, where what is bare is the outline's, not a hole (the forks
        // round a loop's turn at a ridge's tip, kept, lay a line wider than the strip between the loop and the outline, over
        // the loop). The model's surface: [surface] where the outline is one part's of several (where the part's edge meets
        // another part, the spot is closed in by that part's walls: a hole all the same).
        val modelGrid by lazy { if (surface == null) surfaceGrid else {
            var x0 = Double.MAX_VALUE; var y0 = Double.MAX_VALUE; var x1 = -Double.MAX_VALUE; var y1 = -Double.MAX_VALUE
            for (i in nx.indices) { x0 = minOf(x0, nx[i] - nr[i]); x1 = maxOf(x1, nx[i] + nr[i]); y0 = minOf(y0, ny[i] - nr[i]); y1 = maxOf(y1, ny[i] + nr[i]) }
            val m = sample.toDouble()
            val es = ArrayList<LongArray>()
            for (p in surface) for (e in 0 until p.size) {
                val ax = p.x(e); val ay = p.y(e); val bx = p.x((e + 1) % p.size); val by = p.y((e + 1) % p.size)
                if (maxOf(ax, bx) < x0 - m || minOf(ax, bx) > x1 + m || maxOf(ay, by) < y0 - m || minOf(ay, by) > y1 + m) continue
                es += longArrayOf(ax, ay, bx, by)
            }
            if (es.isEmpty()) null else EdgeGrid(es, maxOf(maxWidthUm, 4 * sample))
        } }
        fun atModelSurface(i: Int): Boolean = outline.isEmpty() || modelGrid?.within(nx[i], ny[i], nr[i] + sample) == true
        val heldBy = HashMap<Int, MutableList<Int>>()
        fun pokesOut(fromLeaf: List<Int>): Boolean {
            if (keepWiderUm == Long.MAX_VALUE) return false
            val j = fromLeaf.last(); val half = width(j) / 2
            fun reach(i: Int) = if (atModelSurface(i)) 0.0 else minOf(2 * nr[i], Math.hypot(nx[i] - nx[j], ny[i] - ny[j]) + nr[i] - half)
            return fromLeaf.any { i -> reach(i) > keepWiderUm || heldBy[i]?.any { reach(it) > keepWiderUm } == true }
        }
        fun prune(eligible: (List<Int>) -> Boolean) {
            var pruned = true
            while (pruned) {
                pruned = false
                val degree = adj.mapValues { it.value.size }
                val all = chains()
                val cut = all.mapNotNull { c ->
                    val leafA = (degree[c.first()] ?: 0) == 1; val leafB = (degree[c.last()] ?: 0) == 1
                    val fromLeaf = if (leafA) c else c.reversed()                                    // the leaf end first
                    fromLeaf.takeIf { leafA != leafB && length(c) < c.maxOf { width(it) } && eligible(fromLeaf) && !pokesOut(fromLeaf) }
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
                for (c in cut) {
                    for (j in 1 until c.size) { adj[c[j - 1]]?.remove(c[j]); adj[c[j]]?.remove(c[j - 1]) }; pruned = true
                    val held = heldBy.getOrPut(c.last()) { ArrayList() }
                    for (i in c.dropLast(1)) { held.add(i); heldBy[i]?.let { held.addAll(it) } }
                }
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
        // Nor is an area wider somewhere than [keepWiderUm] (the narrowest place a line is laid in, and the slicing tolerance
        // a side) a corner void: left empty it is a hole inside the walls. A web 1.6 mm wide between a hole and the side,
        // where the next loops of both end, left 0.72 mm bare between the two outer loops over 1.4 mm: its line, 0.68 mm
        // long, was dropped as short
        return chains().flatMap { split(it) }.filter { c ->
            if (c.size < 2) return@filter false
            val len = length(c)
            if (len < 1.0) return@filter false
            val closed = c.first() == c.last()
            if (closed) return@filter len >= c.maxOf { width(it) }
            val along by lazy { outline.isNotEmpty() &&
                alongOutline(LongArray(2 * c.size) { k -> Math.round(if (k % 2 == 0) nx[c[k / 2]] else ny[c[k / 2]]) }, LongArray(c.size) { Math.round(width(c[it])) }, outline) }
            val wide = c.any { 2 * nr[it] > keepWiderUm }
            // (a band's piece: kept when at least as long as it is narrow, not a dot)
            if (bandPieces.any { it === c }) return@filter along || wide || len >= c.minOf { width(it) }
            if (len < minLengthFactor * c.minOf { width(it) } && !along && !wide) return@filter false
            len >= 2 * c.maxOf { width(it) } || along || wide
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
