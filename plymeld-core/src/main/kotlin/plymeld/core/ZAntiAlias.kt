package plymeld.core

import plymeld.mesh.SolidColumns
import plymeld.program.Extrude

/**
 * Z anti-aliasing of sloped tops (plan.faces.sloped_top.technique = "z_antialiasing", opt-in). The idea is published
 * work on fused deposition (Song, Lefebvre et al. 2017, "Anti-aliasing for fused filament deposition"), named here as
 * its origin; neither it nor any slicer's code was read for this. plymeld's own rules, on its flat layers:
 *
 *  - a line of a flat layer (bottom .. top) is printed at the height of the model's top under it when that top lies within
 *    the layer: the top of the run of solid the line stands in, looked up [PROBE_MM] above the layer's bottom (SolidColumns,
 *    over the union of the parts), never lower than the layer's bottom plus the tool's thinnest layer, never above the
 *    layer's top. Its thickness is from the layer's bottom up to there, so the writer lays as much less plastic (the bead
 *    model of that thickness);
 *  - each segment is split where the top bends, until the straight move stays within the slicing resolution of it
 *    (CurvedLayers.follow, the curved layers' own rule);
 *  - a path lowered nowhere by more than the slicing resolution stays as it was (flat, its scarf seam kept); one lowered
 *    somewhere is printed as one plain run (an outer wall's scarf seam is left out there: plymeld's choice, the
 *    scarf's two wedge passes assume a flat layer top);
 *  - the layer itself reaches out to where the top is the thinnest layer above its bottom (IncrementalSlicer, the slice
 *    at that height added to the one at the layer's middle), so the half layer a mid-height slice leaves under a slope is
 *    printed too.
 *
 * Which lines: the model's own lines on what is printed below (walls, top, bottom, solid and sparse fill, ironing), not
 * bridges (a strand in the air: its height is not a top), support, brim, or the purge tower. The first layer is never
 * lowered (it is the bed's). Curved and lifted layers already follow their own surfaces and are left alone.
 */
object ZAntiAlias {
    /**
     * How far above a layer's bottom the solid under a line is looked up (mm): the file's µm, so a top exactly on the
     * layer's bottom (the layer below's) does not count as this layer's. plymeld's choice.
     */
    const val PROBE_MM = 0.001

    /** The features that are lowered onto a top (above). */
    val FEATURES = setOf(Feature.OUTER_WALL, Feature.INNER_WALL, Feature.TOP, Feature.BOTTOM, Feature.SOLID, Feature.SPARSE, Feature.IRONING)

    /**
     * The height (mm, in the frame of [cols] less [base]) at which a line at (x, y) of the flat layer from [bottom] to [top]
     * (mm above [base]) is printed: the model's top there when it lies within the layer, at least [minMm] above its bottom;
     * the layer's top otherwise (no solid at the layer's bottom there, or the solid going on above the layer).
     */
    fun z(cols: SolidColumns, base: Double, x: Double, y: Double, bottom: Double, top: Double, minMm: Double): Double {
        val t = cols.topAbove(x, y, base + bottom + PROBE_MM)
        if (t.isNaN()) return top
        val rel = t - base
        return if (rel >= top) top else minOf(top, maxOf(rel, bottom + minMm))
    }

    /**
     * Path [p] of the flat layer whose top is [topUm] and thickness [heightUm] (µm above the object's bottom [base], mm, in
     * the frame of [cols]) lowered onto the model's top, printed with [tool]; null when it is not lowered (its feature is not
     * one of [FEATURES], or it is lowered nowhere by more than [tolMm]).
     */
    fun lower(p: ToolPath, topUm: Long, heightUm: Long, tool: Int, cols: SolidColumns, base: Double, minMm: Double, tolMm: Double): Extrude? {
        if (p.feature !in FEATURES) return null
        val top = topUm / 1000.0; val bottom = (topUm - heightUm) / 1000.0
        val u = dedupe(CurvedLayers.follow(p.xy, p.closed, p.widths, p.overhangShare, { x, y -> z(cols, base, x, y, bottom, top, minMm) }, { _, _ -> bottom },
            tolMm, atSplit = true), p.closed)
        if (u.zs.all { topUm - it <= Math.round(tolMm * 1000) }) return null
        return Extrude(p.feature.label, u.xy, p.closed, u.zs.max(), p.widthUm, u.heights.max(), tool, u.overhangShare, p.ePerMmOverride, p.retractBefore,
            u.widths, zs = u.zs, heights = u.heights, hopOnly = p.hopOnly)
    }

    /**
     * [u] without a vertex that repeats the one before it (the same µm position and Z): splitting at a step leaves several
     * points there, which round to one. Each dropped vertex takes the edge into it (of no length) with it.
     */
    private fun dedupe(u: CurvedLayers.Unwarped, closed: Boolean): CurvedLayers.Unwarped {
        val n = u.zs.size
        fun same(i: Int, j: Int) = u.xy[2 * i] == u.xy[2 * j] && u.xy[2 * i + 1] == u.xy[2 * j + 1] && u.zs[i] == u.zs[j]
        val keep = BooleanArray(n) { it == 0 || !same(it, it - 1) }
        // a closed loop's last vertex repeating its first: the closing edge has no length
        if (closed && n > 1 && keep[n - 1] && same(n - 1, 0)) keep[n - 1] = false
        if (keep.all { it }) return u
        val idx = (0 until n).filter { keep[it] }
        val m = idx.size
        // per-edge shares: the edge out of each kept vertex is the last edge before the next kept one starts (for an open
        // path); the edges dropped had no length
        val shares = u.overhangShare?.let { s -> IntArray(if (closed) m else m - 1) { k -> s[minOf(s.size - 1, (if (k + 1 < m) idx[k + 1] else n) - 1)] } }
        return CurvedLayers.Unwarped(LongArray(2 * m) { u.xy[2 * idx[it / 2] + it % 2] }, LongArray(m) { u.zs[idx[it]] }, LongArray(m) { u.heights[idx[it]] },
            u.widths?.let { w -> LongArray(m) { w[idx[it]] } }, shares)
    }
}
