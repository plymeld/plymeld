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
     * [partLoops]: each part's outline on this layer; [spools]: each part's spool; [precedence]: part
     * indices, the first takes contested area first (every part exactly once). [minWidthUm]: the
     * narrowest line the walls lay; above 0, slivers narrower than it go to a neighbor ([handOverSlivers]).
     */
    fun split(partLoops: List<List<Polygon>>, spools: List<String>, precedence: List<Int>, minWidthUm: Long = 0): Split {
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
        return Split(if (minWidthUm > 0) handOverSlivers(bySpool, minWidthUm) else bySpool, own.sumOf { area(it) } - area(taken))
    }

    /**
     * An area too thin for its filament's walls to lay a line in ([minWidthUm] across: it vanishes when
     * shrunk by half that and grown back), where it touches another filament's area, goes to that
     * neighbor (the one it shares the most border with). Printed in its own filament it would print as
     * nothing, and the model's surface there would be missing (seen on a multi-color model over 0.7 mm of height: a 0.32 mm tongue
     * of one part under another's edge left a strip 2.8 mm long unprinted, found by the g-code read-back
     * check); the neighbor's walls now run along the true outline. Thin pieces with no
     * neighbor (a part's own sharp edge in the air) stay; so do crumbs smaller than a line's square.
     */
    fun handOverSlivers(bySpool: Map<String, List<Polygon>>, minWidthUm: Long): Map<String, List<Polygon>> {
        if (bySpool.size < 2) return bySpool
        val r = minWidthUm / 2
        val out = LinkedHashMap(bySpool)
        val minArea = minWidthUm.toDouble() * minWidthUm
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
                val touch = Offset.offset(piece, TOUCH_UM)
                val to = out.keys.filter { it != sp }
                    .map { other -> other to area(Booleans.intersection(touch, out.getValue(other))) }
                    .filter { it.second > 0 }.maxByOrNull { it.second }?.first ?: continue
                out[sp] = Booleans.difference(out.getValue(sp), piece)
                out[to] = Booleans.union(out.getValue(to), piece)
            }
        }
        return out
    }

    /** Offsets' rounding (µm): the "grown back" area is grown this much more, so no hairline is read as thin. */
    private const val HAIRLINE_UM = 5L
    /** How close (µm) a thin piece must come to another filament's area to count as touching it. */
    private const val TOUCH_UM = 20L
}
