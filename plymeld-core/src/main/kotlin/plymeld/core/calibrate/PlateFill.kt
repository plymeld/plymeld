package plymeld.core.calibrate

import plymeld.core.Feature
import plymeld.core.Ordering
import plymeld.core.Planner
import plymeld.core.ToolPath
import plymeld.geom.Hatch
import plymeld.geom.Linker
import plymeld.geom.Offset
import plymeld.geom.Polygon

/**
 * Filling a calibration plate's solid outlines (a base, a floor, a label): [PlateGeometry.frameWalls] rings, then one
 * monotonic sweep of lines at [angle] degrees, every line one bead spacing from the next. Lengths in µm.
 */
object PlateFill {
    /** Center-to-center distance of two touching [w] µm lines [hUm] high (the bead model's). */
    fun spacing(w: Long, hUm: Long): Long = Math.round(w - hUm * (1 - Math.PI / 4))

    /** [outlines] filled in [w] µm lines [hUm] high: its rings, outermost first, then its lines in print order. */
    fun fill(outlines: List<Polygon>, w: Long, hUm: Long, angle: Double): Pair<List<LongArray>, List<LongArray>> {
        val sp = spacing(w, hUm)
        val rings = ArrayList<LongArray>()
        var cur = Offset.offset(outlines, -w / 2)
        var n = 0
        while (cur.isNotEmpty() && n < PlateGeometry.frameWalls) { for (r in cur) if (r.size >= 3) rings += r.xy; cur = Offset.offset(cur, -sp); n++ }
        if (rings.isEmpty() || cur.isEmpty()) return rings to emptyList()
        val (segs, idx) = Hatch.indexed(cur, angle, sp)
        if (segs.isEmpty()) return rings to emptyList()
        val chains = Linker.link(segs, idx, cur, Planner.ANCHOR_SPACINGS * sp, breakAtSplits = true, breakAtForks = true)
        val end = rings.last()
        return rings to Ordering.sweep(chains.map { ToolPath(Feature.TOP, it, false, w, hUm) }, end[0], end[1]).map { it.xy }
    }
}
