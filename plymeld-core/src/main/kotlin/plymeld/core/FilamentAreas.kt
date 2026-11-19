package plymeld.core

import plymeld.geom.Booleans
import plymeld.geom.Offset
import plymeld.geom.Polygon

/**
 * Multi-color step 1: one object's layer split by filament. Each part keeps its spool, and the
 * layer is divided into areas that do not overlap, so every place is printed once, by one filament.
 *
 * Where parts overlap, the precedence decides: parts earlier in it take the shared area first. It
 * comes from the setting `job.part_overlap` ([precedence]); by default the part that started
 * first, lowest, keeps it (simplify the print and stick with the filament already
 * printing there), so a part embedded in another prints only where it comes out of it.
 */
object FilamentAreas {
    /** A layer's areas per spool, disjoint; [overlapUm2] = area claimed by more than one part. */
    class Split(val bySpool: Map<String, List<Polygon>>, val overlapUm2: Double)

    /**
     * The part order for [split] from the setting `job.part_overlap` ([rule]) and each part's
     * 1-based filament [slots]: started_first (the default: the part whose first layer
     * is lowest, [firstLayers], then the lower filament), lower_filament, higher_filament, earlier_part, later_part. Ties keep file order.
     */
    fun precedence(rule: String, slots: List<Int>, firstLayers: List<Int>? = null): List<Int> = when (rule) {
        "started_first" -> {
            val first = requireNotNull(firstLayers) { "started_first needs each part's first layer" }
            require(first.size == slots.size) { "${slots.size} parts but ${first.size} first layers" }
            // a part with no layer at all starts nowhere: last
            slots.indices.sortedWith(compareBy({ if (first[it] < 0) Int.MAX_VALUE else first[it] }, { slots[it] }, { it }))
        }
        "lower_filament" -> slots.indices.sortedWith(compareBy({ slots[it] }, { it }))
        "higher_filament" -> slots.indices.sortedWith(compareBy({ -slots[it] }, { it }))
        "earlier_part" -> slots.indices.toList()
        "later_part" -> slots.indices.reversed().toList()
        else -> error("job.part_overlap '$rule' is not one of started_first, lower_filament, higher_filament, earlier_part, later_part")
    }

    /** Net area (µm²) of normalized loops: outers CCW positive, holes CW negative. */
    fun area(loops: List<Polygon>): Double = loops.sumOf { it.signedArea2().toDouble() } / 2

    /**
     * [partLoops]: each part's outline on this layer; [spools]: each part's spool (or settings group); [precedence]: part
     * indices, the first takes contested area first (every part exactly once). [minWidthUm]: the
     * narrowest line the walls lay; above 0, slivers narrower than it go to a neighbor ([handOverSlivers]). [keepOnSurface]:
     * a sliver lying along the object's outer surface stays its own (plan.layer.sliver.at_surface = own); [filamentOf]: the
     * filament of a key of [spools] (a settings group's spool), as [handOverSlivers] takes it.
     */
    fun split(partLoops: List<List<Polygon>>, spools: List<String>, precedence: List<Int>, minWidthUm: Long = 0,
              keepOnSurface: Boolean = false,
              /** A sliver handed over takes the gap between it and its neighbor along ([handOverSlivers]); false: not (a test's control). */
              bridgeGap: Boolean = true, filamentOf: (String) -> String = { it }): Split {
        require(partLoops.size == spools.size) { "${partLoops.size} parts but ${spools.size} spools" }
        require(precedence.sorted() == partLoops.indices.toList()) { "precedence must name every part once: $precedence" }
        val own = partLoops.map { Booleans.normalize(it) }
        var taken = emptyList<Polygon>()
        val pieces = LinkedHashMap<String, MutableList<Polygon>>()
        for (k in precedence) {
            if (own[k].isEmpty()) continue
            val mine = if (taken.isEmpty()) own[k] else Booleans.difference(own[k], taken)
            if (mine.isNotEmpty()) pieces.getOrPut(spools[k]) { ArrayList() } += mine
            taken = if (taken.isEmpty()) own[k] else Booleans.union(taken, own[k])
        }
        // a spool's pieces from several parts touch or not; one normalized set per spool
        val bySpool = LinkedHashMap<String, List<Polygon>>()
        for (sp in spools.distinct()) pieces[sp]?.let { bySpool[sp] = if (it.size > 1) Booleans.normalize(it) else it }
        return Split(if (minWidthUm > 0) handOverSlivers(bySpool, minWidthUm, if (keepOnSurface) taken else null, filamentOf, bridgeGap) else bySpool,
            own.sumOf { area(it) } - area(taken))
    }

    /**
     * An area too thin for its filament's walls to lay a line in ([minWidthUm] across: it vanishes when
     * shrunk by half that and grown back), where it touches another filament's area, goes to that
     * neighbor (the one it shares the most border with), with the gap between them ([withGap]; [bridgeGap] false: without,
     * a test's control). Printed in its own filament it would print as
     * nothing, and the model there would be missing (seen on a multi-color model over 0.7 mm of height: a 0.32 mm tongue
     * of one part under another's edge left a strip 2.8 mm long unprinted, found by the g-code read-back
     * check); the neighbor's walls now run along the true outline. Thin pieces with no
     * neighbor (a part's own sharp edge in the air) stay; so do crumbs smaller than a line's square.
     *
     * [objectArea]: the object's whole area on the layer (every part's), given when a sliver lying along the object's outer
     * surface stays its own (plan.layer.sliver.at_surface = own): the part of a sliver along the surface ([alongSurface])
     * stays, laid by its own filament where a line fits and left empty where none does, and only the rest goes over.
     * Handed over, its neighbor's plastic showed in the wrong color on the surface, and the neighbor's outline stepped out
     * over the layer below where nothing was modeled to (one part's wall laid over the first 0.14 mm of a wedge of another
     * filament leaning out over its face). Null: every sliver goes over. [filamentOf]: the filament of a key of [bySpool] (a settings
     * group's spool): a sliver beside another group of its own filament goes over wherever it lies, the same color either way.
     */
    fun handOverSlivers(bySpool: Map<String, List<Polygon>>, minWidthUm: Long, objectArea: List<Polygon>? = null,
                        filamentOf: (String) -> String = { it }, bridgeGap: Boolean = true): Map<String, List<Polygon>> {
        if (bySpool.size < 2) return bySpool
        val r = minWidthUm / 2
        val out = LinkedHashMap(bySpool)
        val minArea = minWidthUm.toDouble() * minWidthUm
        /** The area of another key [piece] shares the most border with (within [TOUCH_UM]); null = none. */
        fun neighbor(piece: List<Polygon>, sp: String): String? {
            val touch = Offset.offset(piece, TOUCH_UM)
            return out.keys.filter { it != sp }
                .map { other -> other to area(Booleans.intersection(touch, out.getValue(other))) }
                .filter { it.second > 0 }.maxByOrNull { it.second }?.first
        }
        for (sp in bySpool.keys) {
            val region = out.getValue(sp)
            if (region.isEmpty()) continue
            // where a line fits, grown back (and a few µm more, so the offsets' rounding leaves no hairlines)
            val fits = Offset.offset(Offset.offset(region, -r), r + HAIRLINE_UM)
            val thin = if (fits.isEmpty()) region else Booleans.difference(region, fits)
            for (outer in thin.filter { it.signedArea2() > 0 }) {
                // the piece with its own holes (a thin ring keeps its hole: handed over as an outline alone it
                // filled the two-color Benchy's chimney, 7 mm2 a layer)
                val piece = Booleans.intersection(thin, listOf(outer))
                if (area(piece) < minArea) continue
                val to = neighbor(piece, sp) ?: continue
                // along the object's surface, beside another filament: that stretch stays, the rest goes over
                val kept = if (objectArea == null || filamentOf(to) == filamentOf(sp)) emptyList() else alongSurface(piece, objectArea, minWidthUm)
                val give = if (kept.isEmpty()) piece else Booleans.difference(piece, kept)
                if (kept.isNotEmpty() && area(give) < minArea) continue
                val dest = if (kept.isEmpty()) to else neighbor(give, sp) ?: continue
                out[sp] = Booleans.difference(out.getValue(sp), give)
                val merged = Booleans.union(out.getValue(dest), give)
                out[dest] = if (bridgeGap) withGap(merged, give, out.filterKeys { it != dest }.values.flatten()) else merged
            }
        }
        return out
    }

    /**
     * [merged] (a neighbor's area with a sliver [give] handed to it) with the gaps between the sliver and the area it joined
     * closed: a gap narrower than twice [TOUCH_UM] within twice that of the sliver, where no [others] area lies. The sliver
     * counted as touching its neighbor across such a gap ([neighbor]), and the gap (the parts sliced one by one meet a few
     * microns apart) went over with it; left out, it was a crack in the neighbor's own area, a few microns wide and a few
     * tenths of a millimetre long, a hole of its own or opening onto the neighbor's edge, and the neighbor's walls laid a
     * loop round it, three or four lines over each other at its end (on a test slice: up to 0.8 mm² laid twice
     * at one crack).
     */
    private fun withGap(merged: List<Polygon>, give: List<Polygon>, others: List<Polygon>): List<Polygon> {
        var x0 = Long.MAX_VALUE; var y0 = Long.MAX_VALUE; var x1 = Long.MIN_VALUE; var y1 = Long.MIN_VALUE
        for (p in give) { val b = p.bounds(); x0 = minOf(x0, b[0]); y0 = minOf(y0, b[1]); x1 = maxOf(x1, b[2]); y1 = maxOf(y1, b[3]) }
        // the neighbor round the sliver, in a box far enough out that the box's own sides do not reach the closing near it
        val m = 4 * TOUCH_UM
        val box = listOf(Polygon(longArrayOf(x0 - m, y0 - m, x1 + m, y0 - m, x1 + m, y1 + m, x0 - m, y1 + m)))
        val crop = Booleans.intersection(merged, box)
        if (crop.isEmpty()) return merged
        val closed = Offset.offset(Offset.offset(crop, TOUCH_UM, Offset.Join.ROUND), -TOUCH_UM, Offset.Join.ROUND)
        var fill = Booleans.difference(Booleans.intersection(closed, Offset.offset(give, 2 * TOUCH_UM, Offset.Join.ROUND)), merged)
        if (fill.isEmpty()) return merged
        val near = plymeld.geom.Polygons.group(others).filter { (o, _) -> val c = o.bounds()
            c[2] >= x0 - m && c[0] <= x1 + m && c[3] >= y0 - m && c[1] <= y1 + m }.flatMap { (o, hs) -> listOf(o) + hs }
        if (near.isNotEmpty()) fill = Booleans.difference(fill, near)
        return if (fill.isEmpty() || area(fill) <= 0) merged else Booleans.union(merged, fill)
    }

    /**
     * The part of a sliver [piece] that lies along the object's outer surface ([objectArea], every part's area on the layer;
     * gaps between parts narrower than twice [TOUCH_UM], the seams of parts sliced one by one, are not surface): its pieces
     * within [widthUm] of the object's outline (a sliver is at most that wide, so one lying along the outline is there
     * whole) whose stretch of the outline is longer than [widthUm]. A sliver meeting the outline end on, a tongue between
     * two parts reaching the surface, shares only its own width of it, under [widthUm]: none of it is kept, and the
     * neighbor's walls run across its end along the true outline.
     */
    internal fun alongSurface(piece: List<Polygon>, objectArea: List<Polygon>, widthUm: Long): List<Polygon> {
        var x0 = Long.MAX_VALUE; var y0 = Long.MAX_VALUE; var x1 = Long.MIN_VALUE; var y1 = Long.MIN_VALUE
        for (p in piece) { val b = p.bounds(); x0 = minOf(x0, b[0]); y0 = minOf(y0, b[1]); x1 = maxOf(x1, b[2]); y1 = maxOf(y1, b[3]) }
        // the object around the piece, in a box far enough out that the box's own sides are no outline the piece is near
        val m = 2 * widthUm + 2 * TOUCH_UM
        val bx0 = x0 - m; val by0 = y0 - m; val bx1 = x1 + m; val by1 = y1 + m
        val box = listOf(Polygon(longArrayOf(bx0, by0, bx1, by0, bx1, by1, bx0, by1)))
        val near = objectArea.filter { p -> val b = p.bounds(); b[2] >= bx0 && b[0] <= bx1 && b[3] >= by0 && b[1] <= by1 }
        if (near.isEmpty()) return emptyList()
        val closed = Offset.offset(Offset.offset(Booleans.intersection(near, box), TOUCH_UM), -TOUCH_UM)
        val outside = Booleans.difference(box, closed)
        if (outside.isEmpty()) return emptyList()
        val within = Booleans.intersection(piece, Offset.offset(outside, widthUm, Offset.Join.ROUND))
        if (within.isEmpty()) return emptyList()
        val kept = ArrayList<Polygon>()
        for ((o, holes) in plymeld.geom.Polygons.group(within)) {
            val c = listOf(o) + holes
            // its stretch of the outline: the band TOUCH_UM wide round it that lies outside the object, by its width
            val alongUm = area(Booleans.intersection(Offset.offset(c, TOUCH_UM), outside)) / TOUCH_UM
            if (alongUm > widthUm) kept += c
        }
        return kept
    }

    /** Offsets' rounding (µm): the "grown back" area is grown this much more, so no hairline is read as thin. */
    private const val HAIRLINE_UM = 5L
    /** How close (µm) a thin piece must come to another filament's area to count as touching it. */
    private const val TOUCH_UM = 20L
}
