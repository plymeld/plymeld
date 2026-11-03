package plymeld.core

import plymeld.program.Progress
import plymeld.program.Counter
import plymeld.geom.Booleans
import plymeld.geom.Polygon

/**
 * What is inside the walls of each layer, by kind:
 *  - topSurface:    exposed from above (not covered by the next layer's outline)
 *  - bottomSurface: exposed from below (not covered by the previous layer's outline)
 *  - internalSolid: within `topLayers` / `bottomLayers` of an exposed surface, but not itself exposed
 *  - sparse:        everything else inside the walls
 *
 * The shell is every point within an ellipsoid of the part's surface ([Regions.classify]):
 * slopes are thickened as well as tops and bottoms.
 */
class LayerRegions(
    val inner: List<Polygon>,
    val topSurface: List<Polygon>,
    val bottomSurface: List<Polygon>,
    val internalSolid: List<Polygon>,
    val sparse: List<Polygon>,
)

object Regions {
    /**
     * The regions of every layer: top and bottom surfaces (exposed above / below), internal solid
     * (the shell around them and behind sloped faces), sparse (the rest). One geometric rule,
     * below; sloped faces always get their shell (turning it off only ever saves material at a
     * visible cost).
     * @param coverInners the inner regions the shell is measured from. By default [inners]; for one
     *   filament's part of a multi-color object, the WHOLE object's inners, so the boundary between
     *   two filaments does not count as an edge of the part.
     */
    fun classify(
        outlines: List<List<Polygon>>, inners: List<List<Polygon>>, topLayers: Int, bottomLayers: Int,
        coverInners: List<List<Polygon>> = inners,
        /** Per layer, in place of [topLayers] / [bottomLayers] (layers of varying height: Planner.shellCounts). */
        topAt: IntArray? = null, bottomAt: IntArray? = null,
        /**
         * A top surface runs under the OTHER parts standing on it ([underParts]). [selfCover]: per layer,
         * where a part of this region continues on the next layer (it is not "standing on" itself);
         * null = one part, nothing else can stand on it.
         */
        topUnderParts: Boolean = false, selfCover: List<List<Polygon>>? = null,
        /** The top surface's line width: a top narrower than one line (a hairline where parts meet) runs under nothing. */
        topLineUm: Long = 0,
        progress: Progress = Progress.NONE,
        /** The settings the caller's stage reads, as text: the layer cache's settings part. */
        settingsKey: String = "",
    ): List<LayerRegions> {
        require(inners.size == outlines.size)
        val r = classifyEllipsoid(outlines, inners, topLayers, bottomLayers, coverInners, topAt, bottomAt, progress, settingsKey)
        return if (topUnderParts && selfCover != null) underParts(r, topLayers, topAt, selfCover, topLineUm) else r
    }

    /**
     * A top surface printed as if nothing stood on it, in the same pattern as with nothing on top: the holes inside a top surface that are this layer's own
     * area and where ANOTHER part stands (not where a part of this region carries on: [selfCover]) become
     * top surface too, so its pattern runs through unbroken; under them the top shell's layers are solid,
     * as under the rest of the top, so the surface rests on solid and not on sparse cells. A top
     * surface's notches at its edge and real holes (no area of this layer) are left as they are, and so
     * is every layer without an exposed top (a color band stacked on another gets no hidden skin).
     * Counting the part's own continuation as "standing on it" filled the inside of a thin top sliver
     * on a multi-part model (446 mm of top surface over 0.8 mm of new solid), and so did a
     * hairline "top" where two parts sliced one by one meet (an inlay under a core part, the same layer):
     * only a top at least one top line wide ([topLineUm]: opened by half a line) has an inside to fill.
     */
    private fun underParts(r: List<LayerRegions>, topLayers: Int, topAt: IntArray?, selfCover: List<List<Polygon>>, topLineUm: Long): List<LayerRegions> {
        val top = r.map { it.topSurface }.toMutableList(); val solid = r.map { it.internalSolid }.toMutableList(); val sparse = r.map { it.sparse }.toMutableList()
        for (i in r.indices) {
            if (r[i].topSurface.isEmpty()) continue
            val real = if (topLineUm <= 0) r[i].topSurface
                else plymeld.geom.Offset.offset(plymeld.geom.Offset.offset(r[i].topSurface, -topLineUm / 2), topLineUm / 2)
            // each top piece on its own: only a piece mostly exposed runs under what stands on it: its
            // exposed area larger than the footprints it
            // would fill. A floor whose only exposed top is a rim round another part standing on it
            // (a shell part under its core part) stays as it is.
            val f = ArrayList<Polygon>()
            for (outer in real.filter { it.isCcw() }) {
                val piece = listOf(outer)
                var holes = Booleans.difference(Booleans.difference(Booleans.intersection(piece, r[i].inner), r[i].topSurface), r[i].bottomSurface)
                if (holes.isNotEmpty() && selfCover[i].isNotEmpty()) holes = Booleans.difference(holes, selfCover[i])
                if (holes.isEmpty()) continue
                val exposed = Booleans.intersection(piece, r[i].topSurface).sumOf { it.signedAreaMm2() }
                if (exposed > holes.sumOf { it.signedAreaMm2() }) f += holes
            }
            if (f.isEmpty()) continue
            top[i] = Booleans.union(top[i], f)
            solid[i] = Booleans.difference(solid[i], f); sparse[i] = Booleans.difference(sparse[i], f)
            // layer j is in the top shell of a surface up to topAt[j] layers above it (Planner.shellCounts)
            var j = i - 1
            while (j >= 0 && i - j <= (topAt?.get(j) ?: topLayers)) {
                val g = Booleans.difference(Booleans.intersection(f, r[j].inner), Booleans.union(top[j], r[j].bottomSurface))
                if (g.isNotEmpty()) { solid[j] = Booleans.union(solid[j], g); sparse[j] = Booleans.difference(sparse[j], g) }
                j--
            }
        }
        return r.indices.map { LayerRegions(r[it].inner, top[it], r[it].bottomSurface, solid[it], sparse[it]) }
    }

    /**
     * Vertical shells as ONE geometric rule: the shell is every point within
     * an ellipsoid of the part's surface - the wall thickness W across, the top / bottom shell
     * thickness (in layers, T) up and down. The core (what may be sparse) is where the part, eroded
     * by that ellipsoid, still reaches: this layer's inner region (the walls are its W), and every
     * layer k steps above (below) within T eroded by W x sqrt(1 - (k / (T + 1))^2). A vertical face
     * adds nothing (the walls are the shell), a flat top gets its T layers, and a face leaning theta
     * a band of about sqrt(W^2 + (T tan theta)^2) - W: the least plastic that meets every stated
     * thickness in every direction, no margin and no constant taken from another slicer. W is this
     * layer's measured wall depth (outline minus inner region, over the outline's length).
     * Layers are sampled at up to [ELLIPSOID_SAMPLES] steps each way (the ellipsoid is smooth; every
     * step would be one offset per layer per step).
     */
    private fun classifyEllipsoid(outlines: List<List<Polygon>>, inners: List<List<Polygon>>, topLayers: Int, bottomLayers: Int,
                                  coverInners: List<List<Polygon>>, topAt: IntArray?, bottomAt: IntArray?, progress: Progress = Progress.NONE,
                                  settingsKey: String = ""): List<LayerRegions> {
        val n = outlines.size
        // wall depth per layer: area between the outline and the inner region, over the outline's length
        val wallDepth = LongArray(n) { i ->
            val o = outlines[i]; if (o.isEmpty()) return@LongArray 0L
            val band = o.sumOf { it.signedAreaMm2() } - coverInners[i].sumOf { it.signedAreaMm2() }
            val len = o.sumOf { it.perimeterMm() }
            if (len <= 0) 0L else Math.round(band / len * 1000)
        }
        // steps 1 and 2 always (a void only a layer or two up must not slip between samples), then samples
        fun steps(t: Int): List<Int> = ((1..minOf(t, 2)).toList() +
            (1..minOf(t, ELLIPSOID_SAMPLES)).map { Math.round(it.toDouble() * t / minOf(t, ELLIPSOID_SAMPLES)).toInt() }).distinct().sorted()
        return Parallel.map(n, progress.Counter("regions", n)) { i ->
            val inner = inners[i]
            if (inner.isEmpty()) return@map LayerRegions(inner, emptyList(), emptyList(), emptyList(), emptyList())
            val above = if (i + 1 < n) outlines[i + 1] else emptyList()
            val below = if (i > 0) outlines[i - 1] else emptyList()
            // what this layer's regions are read from (LayerCache): its inner, cover and outline, the outlines above and below,
            // and every outline the shell rule samples each way
            val tUp = topAt?.get(i) ?: topLayers; val tDown = bottomAt?.get(i) ?: bottomLayers
            val kb = LayerCache.Builder("regions").text(settingsKey).text(tUp).text(tDown).text(wallDepth[i]).polys(inner).polys(coverInners[i]).polys(outlines[i]).polys(above).polys(below)
            for (k in steps(tUp)) { val j = i + k; if (j < n) kb.polys(outlines[j]) else kb.text("end") }
            for (k in steps(tDown)) { val j = i - k; if (j >= 0) kb.polys(outlines[j]) else kb.text("end") }
            LayerCache.get(kb.build(), { r: LayerRegions -> LayerCache.bytesOfPolys(r.topSurface) + LayerCache.bytesOfPolys(r.bottomSurface) + LayerCache.bytesOfPolys(r.internalSolid) + LayerCache.bytesOfPolys(r.sparse) }) {
            // what is both (one layer thick): top on the first layer (it lies on the bed); above it, over air, a bottom, which the
            // planner bridges (a one-layer arch strip was given a top surface's diagonal lines over air)
            val topRaw = Booleans.difference(inner, above)
            val bottomSurface = if (i > 0) Booleans.difference(inner, below) else Booleans.difference(Booleans.difference(inner, below), topRaw)
            val topSurface = if (i > 0) Booleans.difference(topRaw, bottomSurface) else topRaw
            val w = wallDepth[i]
            var core: List<Polygon> = coverInners[i]
            fun reach(t: Int, dir: Int) {
                for (k in steps(t)) {
                    if (core.isEmpty()) return
                    val j = i + dir * k
                    if (j < 0 || j >= n || outlines[j].isEmpty()) { core = emptyList(); return }
                    val f = k.toDouble() / (t + 1)
                    val r = Math.round(w * Math.sqrt(Math.max(0.0, 1 - f * f)))
                    val eroded = if (r > 0) plymeld.geom.Offset.offset(outlines[j], -r) else outlines[j]
                    core = if (eroded.isEmpty()) emptyList() else Booleans.intersection(core, eroded)
                }
            }
            reach(topAt?.get(i) ?: topLayers, +1); reach(bottomAt?.get(i) ?: bottomLayers, -1)
            val solid = if (core.isEmpty()) inner else Booleans.difference(inner, core)
            val exposed = Booleans.union(topSurface, bottomSurface)
            val internalSolid = Booleans.difference(solid, exposed)
            val sparse = if (core.isEmpty()) emptyList() else Booleans.intersection(inner, core)
            LayerRegions(inner, topSurface, bottomSurface, internalSolid, sparse)
            }
        }
    }

    /** Steps sampled each way by the ellipsoid rule. */
    const val ELLIPSOID_SAMPLES = 4
}
