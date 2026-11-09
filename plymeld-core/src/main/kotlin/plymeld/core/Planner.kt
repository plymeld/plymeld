package plymeld.core

import plymeld.program.Progress
import plymeld.program.Counter
import plymeld.model.SliceSettings
import plymeld.geom.Booleans
import plymeld.geom.Hatch
import plymeld.geom.Offset
import plymeld.geom.Polygon
import plymeld.geom.Polygons

/** Toolpath feature kinds, labeled with the `; FEATURE:` names QIDIStudio/Orca use. */
enum class Feature(val label: String) {
    OUTER_WALL("Outer wall"), INNER_WALL("Inner wall"), SPARSE("Sparse infill"),
    SOLID("Internal solid infill"), TOP("Top surface"), BOTTOM("Bottom surface"), BRIDGE("Bridge"), INTERNAL_BRIDGE("Internal Bridge"), BRIM("Brim"),
    SUPPORT("Support"), SUPPORT_INTERFACE("Support interface"), IRONING("Ironing"), TOWER("Prime tower"),
    /** An underside over air grown outward from its anchor, bead by bead (WaveFronts). */
    WAVE("Wave overhang"),
}

/** One extrusion path: polyline in grid units (closed = loop), with its bead geometry. */
class ToolPath(val feature: Feature, val xy: LongArray, val closed: Boolean, val widthUm: Long, val heightUm: Long, val overhangShare: IntArray? = null,
               /** Filament mm per mm of path, when not the bead model (ironing). */ val ePerMmOverride: Double? = null,
               /** Retract (and lift) for the travel INTO this path; false when that travel stays hidden inside the part, or inside its island crossing no wall ([Planner.keepInIsland]). */ val retractBefore: Boolean = true,
               /** Line width at each vertex (variable-width walls); null = [widthUm] throughout ([widthUm] is then the widest). */ val widths: LongArray? = null,
               /** A closed outer wall printed with a scarf seam this long (µm; [Scarf]); 0 = none. */ val scarfUm: Long = 0,
               /** The scarf ramp's starting thickness, a share of the layer. */ val scarfStart: Double = 0.0,
               /**
                * How far above its own layer this path is laid (µm): a lifted bridge's second layer laid as waves with its first
                * (plan.technique.bridge.wave.layers = "2"), its beads in the first layer's list one layer up; 0 = its own layer.
                */
               val raiseUm: Long = 0,
               /** A waved bridge's fill (plan.technique.bridge.wave.start): its beads need not run across the span, so no crossing beside it. */
               val waveFill: Boolean = false,
               /**
                * A curved band's top laid uphill (plan.technique.curved.top_order = "uphill"): the surface's height (mm) where this
                * chain starts, its low end; chains are laid from the lowest start up, each as given. NaN = not so laid.
                */
               val climbFrom: Double = Double.NaN,
               /**
                * A waved bridge's line laid over its first layer after the whole layer ([AfterLayer]), at its own speed, its travel
                * straight; not a bead the crossings or waits apply to. null = laid with its layer.
                */
               val afterLayer: AfterLayer? = null,
               /** [retractBefore] was cleared by [Planner.keepInIsland] (a wall-run travel inside its island: plan.mechanism.motion.no_retract_in_island), not by the infill rules: what the retraction decision may restore. */
               val islandHidden: Boolean = false,
               /**
                * Laid in this order among its feature's paths on the layer, each as generated, never reversed; -1 = ordered for
                * travel. A ring pattern's rings (concentric-inside-out, concentric-outside-in): every ring in one direction, the
                * rings in order (ordered for travel, half of a top's circle arcs were reversed and the rings laid in
                * bands of opposite direction and order, which printed as "two types" of arcs).
                */
               val sequence: Int = -1,
               /**
                * An outer-wall speck (an Arachne line kept only because it lines the outline, shorter than two of its widths): its
                * travel in skips the retraction wherever it crosses no wall, whatever the in-island switch ([Planner.keepInIsland]).
                */
               val speck: Boolean = false,
               /**
                * The travel into this speck lifts over the wall it crosses but does not retract ([Planner.keepInIsland]):
                * reached from its own outer wall, its last hop crosses that wall, which no unlifted travel may.
                */
               val hopOnly: Boolean = false) {
    /**
     * The same open path run end to start, every per-vertex field reversed with it. Rebuilding a
     * path field by field once dropped [widths]: a reversed center line printed at its widest
     * throughout. Paths with overhang tiers are not reversed.
     */
    fun reversed(): ToolPath {
        check(!closed) { "a closed loop keeps its direction and seam" }
        check(overhangShare == null) { "reversing a path with overhang shares is not supported" }
        val m = xy.size / 2
        val r = LongArray(xy.size)
        for (k in 0 until m) { r[2 * k] = xy[2 * (m - 1 - k)]; r[2 * k + 1] = xy[2 * (m - 1 - k) + 1] }
        return ToolPath(feature, r, false, widthUm, heightUm, null, ePerMmOverride, retractBefore, widths?.reversedArray(), raiseUm = raiseUm, waveFill = waveFill, climbFrom = climbFrom, afterLayer = afterLayer, sequence = sequence, speck = speck, hopOnly = hopOnly)
    }

    /** The same path with [retractBefore] set ([island]: cleared by [Planner.keepInIsland]). */
    fun withRetract(retract: Boolean, island: Boolean = false) = ToolPath(feature, xy, closed, widthUm, heightUm, overhangShare, ePerMmOverride, retract, widths, scarfUm, scarfStart, raiseUm, waveFill, climbFrom, afterLayer, islandHidden = island, sequence = sequence, speck = speck)

    /** The same path entered by a lifted travel without a retraction ([hopOnly]). */
    fun withHop() = ToolPath(feature, xy, closed, widthUm, heightUm, overhangShare, ePerMmOverride, true, widths, scarfUm, scarfStart, raiseUm, waveFill, climbFrom, afterLayer, islandHidden, sequence, speck, hopOnly = true)
}

/**
 * The lines laid over a waved bridge's first layer after the whole layer, in this order, each named so in the file:
 * its second pass (plan.technique.bridge.wave.second_pass.zones; the head coming straight down onto each line's start from
 * wave.second_pass.approach above), its Xs (wave.x_layer), and its layer of lines along the span (wave.span_layer).
 */
enum class AfterLayer(val label: String) {
    SECOND_PASS("Wave overhang, second pass"), X_LAYER("Wave overhang, X layer"), SPAN_LAYER("Wave overhang, span layer")
}

class LayerPlanOut(val index: Int, val topZUm: Long, val heightUm: Long, val paths: List<ToolPath>)

/**
 * Turns per-layer outlines into ordered toolpaths using the imported settings:
 *  - supports (normal or tree: [Supports], [TreeSupports]) and the brim;
 *  - walls, seam per plan.seam (corners, in line with the layer below, or the back: Seams; held-up points before ones on
 *    an edge hanging over the layer below), outer walls scarfed per plan.technique.scarf_seam.where (Scarf), fuzzy skin when set; areas too thin for
 *    another pair of loops get a variable-width center line ([Walls]);
 *  - the regions inside the walls ([Regions]):
 *    - sparse: rectilinear, grid, or cubic, each family spaced so the pattern keeps its density;
 *      a strip narrower than one cell gets a single loop at bead contact with the wall;
 *    - internal solid, top, bottom: their pattern (line patterns, concentric outside-in or inside-out, archimedean chords);
 *      ring bands and narrow regions get loops along their outline instead of a hatch;
 *    - bridges over air, and internal bridges over sparse infill, each with its own direction
 *      rule ([Bridges]); ironing on top surfaces;
 *  where s = bead spacing(width, height). Infill lines end where their bead touches the wall
 *  bead (contact), then reach on by infill_wall_overlap.
 * Lines are joined into continuous runs along the region's edge ([plymeld.geom.Linker]), and each
 * group is ordered to cut travel ([Ordering]; top, bottom, and ironing as one-way sweeps).
 */
object Planner {
    /**
     * The longest connector joining two infill lines along the region's boundary, in line spacings
     * (the rule top and bottom surfaces had): between ADJACENT lines the
     * walk is spacing / sin(angle between boundary and lines), so 3 joins them along boundaries down
     * to asin(1/3) = 19.5 deg from the lines; flatter boundaries' walks would run along the wall.
     */
    const val ANCHOR_SPACINGS = 3
    /** A curved top whose lines' heights differ by no more than this (mm, rounding) is flat: swept one way as any top. */
    private const val CLIMB_MIN_RISE_MM = 0.001
    /** How near the span's middle a line across lies to be the middle line of a second pass (µm): the kernel's snapping is 1 µm. */
    private const val MIDDLE_TOL_UM = 5.0

    /** Ring bands up to this many line spacings wide are filled concentrically. plymeld's choice, not tuned. */
    const val MAX_CONCENTRIC_LINES = 5

    /**
     * How far inside a wall bead's edge a neighboring bead of width [widthUm] and height [hUm]
     * has its center when the two just touch as bead-to-bead neighbors: w/2 - h(1 - pi/4), the
     * stadium model's overlap between adjacent beads subtracted (Walls.spacing uses the same).
     */
    fun contactInset(widthUm: Long, hUm: Long): Long = widthUm / 2 - Math.round(hUm * (1 - Math.PI / 4))

    /**
     * The sparse line families printed on layer [i] (top [zUm], height [hUm]): the same rules as the sparse fill. [rectAngle]:
     * a rectilinear layer's own angle when it is set otherwise than by parity (a bridge layer's, fill_angles = "span"); [base]:
     * the base angle of a grid's or cubic's families (the bridge's span there; else the object's infill angle).
     */
    fun sparseFamilies(s: SliceSettings, i: Int, zUm: Long, hUm: Long, rectAngle: Double? = null, base: Double = s.infillAngleDeg,
                       center: DoubleArray? = null): List<Bridges.Family> {
        val n = when (s.sparsePattern) { "rectilinear" -> 1; "grid" -> 2; "cubic" -> 3; else -> return emptyList() }
        if (s.sparseDensity <= 0.0 || s.sparseDensity >= 1.0) return emptyList()
        val sp = Math.round(Walls.spacing(s.sparseWidthUm, hUm) / s.sparseDensity * n)
        return when (s.sparsePattern) {
            "rectilinear" -> (rectAngle ?: (base + 90.0 * (i % 2))).let { a -> listOf(Bridges.Family(a, sp, centered(a, sp, center))) }
            "grid" -> listOf(Bridges.Family(base, sp, centered(base, sp, center)), Bridges.Family(base + 90, sp, centered(base + 90, sp, center)))
            else -> List(3) { Bridges.Family(base + 120.0 * it, sp, centered(base + 120.0 * it, sp, center, -zUm / Math.sqrt(2.0))) }
        }
    }

    /**
     * The point an object's sparse patterns are laid out from (µm): the middle of its outline seen from above, over all its
     * layers, so a symmetric part gets a symmetric pattern (on a grid from the bed's origin, where the lines fell in a part was
     * chance). The same point on every layer, so the pattern stays in line layer to layer; null without an outline.
     */
    fun patternCenter(outlines: List<List<Polygon>>): DoubleArray? {
        var x0 = Long.MAX_VALUE; var y0 = Long.MAX_VALUE; var x1 = Long.MIN_VALUE; var y1 = Long.MIN_VALUE
        for (layer in outlines) for (p in layer) for (k in 0 until p.size) {
            x0 = minOf(x0, p.x(k)); y0 = minOf(y0, p.y(k)); x1 = maxOf(x1, p.x(k)); y1 = maxOf(y1, p.y(k))
        }
        return if (x0 > x1) null else doubleArrayOf((x0 + x1) / 2.0, (y0 + y1) / 2.0)
    }

    /**
     * A family's offset across its lines (Hatch's) so that one of its lines passes through [center] ([shift] further across:
     * cubic's move with height); [shift] alone, from the bed's origin, without a center.
     */
    fun centered(angleDeg: Double, spacingUm: Long, center: DoubleArray?, shift: Double = 0.0): Double {
        val a = Math.toRadians(angleDeg)
        val v = (if (center == null) 0.0 else -Math.sin(a) * center[0] + Math.cos(a) * center[1]) + shift
        return ((v % spacingUm) + spacingUm) % spacingUm
    }
    /** The fills ordered together, nearest-first, inside an island (see the ordering stage). */
    private val MIXED_FILLS = setOf(Feature.BRIDGE, Feature.INTERNAL_BRIDGE, Feature.SOLID, Feature.SPARSE)
    /** Surfaces printed as a sweep (monotonic surfaces): chains break at splits, blocks go in sweep order. */
    private val monotonic = setOf(Feature.TOP, Feature.BOTTOM, Feature.IRONING)

    /** Area removed as unprintable slivers by the last plan() call, mm² (so it is never silent). */
    var droppedSliverMm2 = 0.0
        private set

    /** Stage output: outlines after elephant-foot compensation, and each layer's walls. */
    class WallsOut(val effOutlines: List<List<Polygon>>, val walls: List<LayerWalls>)

    /**
     * Stage 1 — walls. Reads: elefant_foot_compensation, wall_loops, wall/first-layer widths, layer heights.
     * [wholeFirst]: for one filament's areas of a multi-color object, the WHOLE object's first-layer
     * outline: the squish compensation shrinks the object's outer edge only, never a boundary
     * between two filaments (shrinking both sides would open a gap between the colors).
     * [reference]: walls made only as the whole object's reference for surface classification
     * (multi-color): classic loops, whatever the generator; arachne's center lines are not used there.
     * [onBed]: layers that follow the underside: where the first layer lies on the bed; over
     * the hollow it is in the air, nothing spreads it, so the squish compensation shrinks only the edge on the bed.
     */
    fun walls(s: SliceSettings, outlines: List<List<Polygon>>, layerTopsUm: LongArray, wholeFirst: List<Polygon>? = null, reference: Boolean = false,
              onBed: List<Polygon>? = null, progress: Progress = Progress.NONE,
              /** The settings this stage reads, as text (IncrementalSlicer's WALL_KEYS): the layer cache's settings part; every setting read so far by default. */
              settingsKey: String = s.key()): WallsOut {
        val hs = heights(layerTopsUm)
        val sk = settingsKey
        // layers are independent here: all cores, results in layer order; each layer's walls kept (LayerCache) by what they read
        val per = Parallel.map(outlines.size, progress.Counter("walls", outlines.size)) { i ->
            val first = i == 0
            val kb = LayerCache.Builder("walls").text(sk).text(first).text(reference).text(hs[i]).polys(outlines[i])
            if (first) kb.text(wholeFirst != null).polys(wholeFirst ?: emptyList()).text(onBed != null).polys(onBed ?: emptyList())
            LayerCache.get(kb.build(), { r: Pair<List<Polygon>, LayerWalls> -> LayerCache.bytesOfPolys(r.first) + r.second.walls.sumOf { LayerCache.bytesOfPolys(it) } + LayerCache.bytesOfPolys(r.second.inner) + r.second.center.sumOf { it.xy.size * 16L } }, s) {
            val o = if (first && s.elephantFootUm > 0) {
                if (onBed != null) Booleans.union(Offset.offset(outlines[i], -s.elephantFootUm), Booleans.difference(outlines[i], onBed))
                else if (wholeFirst == null) Offset.offset(outlines[i], -s.elephantFootUm)
                else if (outlines[i].isEmpty()) outlines[i]
                else Booleans.intersection(outlines[i], Offset.offset(wholeFirst, -s.elephantFootUm))
            } else outlines[i]
            val h = hs[i]
            val (wo, wi) = if (first) s.firstLayerWidthUm to s.firstLayerWidthUm else s.outerWallWidthUm to s.innerWallWidthUm
            o to when (if (reference) "classic" else s.wallGenerator) {
                "classic" -> Walls.generate(o, s.wallLoops, wo, wi, h)
                "arachne" -> Walls.arachne(o, s.wallLoops, wo, wi, h, s.minBeadUm, s.minFeatureUm, s.minLengthFactor, s.transitionLengthUm)
                else -> error("wall style '${s.wallGenerator}' is not known (fixed_width or variable_width; classic or arachne in another slicer's file)")
            }
            }
        }
        return WallsOut(per.map { it.first }, per.map { it.second })
    }

    /** Stage 2 — regions. Reads: top/bottom shell layers (+ stage 1). */
    fun regions(s: SliceSettings, w: WallsOut, shells: ShellCounts? = null, progress: Progress = Progress.NONE, settingsKey: String = s.key()): List<LayerRegions> =
        solidAtFull(s, Regions.classify(w.effOutlines, w.walls.map { it.inner }, s.topLayers, s.bottomLayers,
            topAt = shells?.top, bottomAt = shells?.bottom, progress = progress, settingsKey = settingsKey))

    /** Top / bottom shell layers counted layer by layer, for layers of varying height. */
    class ShellCounts(val top: IntArray, val bottom: IntArray)

    /**
     * Shell layers per layer when layer heights vary (adaptive layers): layer i is in the top shell
     * of a surface up to top[i] layers above it — the fewest layers ABOVE i that are together
     * plan.layer.top.thickness thick, so layer i is solid exactly when less than that thickness of the
     * part lies above it (the surface layer counts toward the shell, layer i's own height does not:
     * counting it left the shell up to a layer short, a top of 0.75 mm where 0.8 mm was asked for).
     * The bottom likewise downward. With equal heights this is
     * [SliceSettings.topLayers] / [SliceSettings.bottomLayers] everywhere.
     */
    fun shellCounts(s: SliceSettings, layerTopsUm: LongArray): ShellCounts {
        val hs = heights(layerTopsUm); val n = hs.size
        fun count(i: Int, dir: Int, thick: Long): Int {
            var k = 0; var sum = 0L; var j = i + dir
            while (j in 0 until n && sum < thick) { sum += hs[j]; k++; j += dir }
            // the stack ends before the thickness is reached: reach one past it (nothing there = exposed)
            return if (sum < thick) k + 1 else k
        }
        return ShellCounts(IntArray(n) { count(it, +1, s.topShellThicknessUm) }, IntArray(n) { count(it, -1, s.bottomShellThicknessUm) })
    }

    /**
     * Sparse infill at 100 % density is solid infill (as Orca-lineage slicers treat it): the region is
     * printed as internal solid, with the solid line width and pattern.
     */
    /**
     * Sparse infill that becomes internal solid: all of it at 100 % density, else each connected
     * piece smaller than minimum_sparse_infill_area. Flash Studio fills the interiors of
     * raised letters and small islands solid; plymeld printed sparse stubs there.
     */
    private fun solidAtFull(s: SliceSettings, r: List<LayerRegions>): List<LayerRegions> = r.map { l ->
        if (l.sparse.isEmpty()) l
        else if (s.sparseDensity >= 1.0) LayerRegions(l.inner, l.topSurface, l.bottomSurface, Booleans.union(l.internalSolid, l.sparse), emptyList())
        else if (s.minSparseAreaMm2 <= 0.0) l
        else {
            val small = Polygons.group(l.sparse).filter { (o, hs) -> o.signedAreaMm2() + hs.sumOf { it.signedAreaMm2() } < s.minSparseAreaMm2 }
                .flatMap { (o, hs) -> listOf(o) + hs }
            if (small.isEmpty()) l
            else LayerRegions(l.inner, l.topSurface, l.bottomSurface,
                if (l.internalSolid.isEmpty()) Booleans.normalize(small) else Booleans.union(l.internalSolid, small),
                Booleans.difference(l.sparse, small))
        }
    }

    /**
     * The whole object's layers, for one filament's share of a multi-color object: what the
     * filament's paths rest on, are covered by, and bridge over is the WHOLE object, not the
     * filament alone (a deck of one color on a hull of another is not an overhang).
     * [sparse]: per layer, the sparse infill ACTUALLY printed there — every filament's sparse
     * region (they do not overlap) — for "is this solid laid over sparse?" (internal bridges).
     * Known only once the filaments' regions exist; [paths] requires it.
     */
    class Whole(val outlines: List<List<Polygon>>, val w: WallsOut, val sparse: List<List<Polygon>>? = null,
                /** Per layer: the sparse line families printed there by any part (internal bridges are laid across them). */
                val sparseFamilies: List<List<Bridges.Family>>? = null) {
        /** As in [paths]: layer 0 supports its true outline. */
        val asPrinted: List<List<Polygon>> = w.effOutlines.mapIndexed { k, o -> if (k == 0) outlines[0] else o }
    }

    /**
     * Stage 2 for one filament's areas ([w] = its walls): surfaces judged against the [whole] object.
     * [selfCover]: per layer, where one of these areas' own parts carries on to the next layer (a top
     * surface runs under the OTHER parts standing on it: plan.layer.top.under_parts).
     */
    fun regions(s: SliceSettings, w: WallsOut, whole: Whole, shells: ShellCounts? = null, selfCover: List<List<Polygon>>? = null, progress: Progress = Progress.NONE,
                settingsKey: String = s.key()): List<LayerRegions> =
        solidAtFull(s, Regions.classify(whole.w.effOutlines, w.walls.map { it.inner }, s.topLayers, s.bottomLayers,
            coverInners = whole.w.walls.map { it.inner }, topAt = shells?.top, bottomAt = shells?.bottom, topUnderParts = s.topUnderParts, selfCover = selfCover,
            topLineUm = s.topWidthUm, progress = progress, settingsKey = settingsKey))

    fun plan(s: SliceSettings, outlines: List<List<Polygon>>, layerTopsUm: LongArray): List<LayerPlanOut> {
        val w = walls(s, outlines, layerTopsUm)
        return paths(s, outlines, w, regions(s, w), layerTopsUm)
    }

    /**
     * Stage 3 — toolpaths: brim, wall paths, bridges, infill, ordering. Reads the infill/bridge/brim settings.
     * [whole]: set when these are one filament's areas of a multi-color object; judgements about
     * the layer below/above then read the whole object. [objectWide]: whether this call also makes
     * the object's brim and supports (once per object, not per filament). [onBed]: as for [walls]; the first layer's
     * bottom over the hollow is bridged, anchored on the bed.
     */
    fun paths(s: SliceSettings, outlines: List<List<Polygon>>, w: WallsOut, regions: List<LayerRegions>, layerTopsUm: LongArray,
              whole: Whole? = null, objectWide: Boolean = true, onBed: List<Polygon>? = null,
              /** By layer, what a bridge in it is anchored on, where that is not the layer below (a lifted bridge's first layer). */
              bridgeAnchors: Map<Int, List<Polygon>> = emptyMap(),
              /**
               * A lifted bridge's layers all along its bridge, no lines crossing it: from its first layer up, every fill (bottom,
               * solid) at that layer's bridge angle; its top surface keeps its usual angle.
               */
              alongBridge: Boolean = false,
              /**
               * Where support goes when it is decided per spot (the plan's SupportPlan): the chosen boxes and
               * the locked ones; null = the switch alone decides (plan.support.enabled), as before.
               */
              support: Supports.Regions? = null,
              /**
               * Planned inserts: boxes in the placed frame whose z0 is the insert's top. The first layer whose bottom is at
               * or above z0 is laid onto the insert within the box: its bottom surface there as a bottom surface, not a bridge,
               * and the box counts as printed under it (no support in the socket, no overhang over it).
               */
              covers: List<Supports.Box> = emptyList(), progress: Progress = Progress.NONE,
              /**
               * By layer, what else it is laid onto, as [covers]: printed material that is not one of these layers (several
               * lifted bridges' strips, printed in their own bands, under the flat layers above their ends).
               */
              standsOn: Map<Int, List<Polygon>> = emptyMap(),
              /**
               * A curved band's top surface: the model's height (mm) at (x, y) in mm, to lay its top uphill
               * (plan.technique.curved.top_order = "uphill"); null without a curved band.
               */
              surfaceTop: ((Double, Double) -> Double)? = null,
              /**
               * A lifted bridge's first layer as laid: its height (mm) at (x, y) in mm, to space its waved lines evenly along it
               * (plan.technique.bridge.wave.spacing = "even"); null without a lifted bridge (the lines are spaced from above).
               */
              bridgeTop: ((Double, Double) -> Double)? = null,
              /**
               * Per layer, areas planned for what the layers round them read (shells, bridges, overhangs) but not laid here: a curved
               * island's loops above its start, printed in its own layers (IncrementalSlicer); a path starting inside one is left out
               * before the layer is ordered. Null or empty: every path is laid.
               */
              omit: List<List<Polygon>>? = null,
              /** The settings this stage reads, as text (IncrementalSlicer's PATH_KEYS): the layer cache's settings part. */
              settingsKey: String = s.key()): List<LayerPlanOut> {
        val n = outlines.size
        // the bridge's direction, as its first layer's bridge lines choose it (Bridges.bestAngle over the largest piece), for every
        // layer from there up
        val fillAngle: Map<Int, Double> = if (!alongBridge || bridgeAnchors.isEmpty()) emptyMap() else {
            val i0 = bridgeAnchors.keys.min()
            val bw = s.nozzleDiameterUm
            val h0 = layerTopsUm[i0] - (if (i0 > 0) layerTopsUm[i0 - 1] else 0L)
            val region = Booleans.intersection(Offset.offset(regions[i0].bottomSurface, 2 * bw), regions[i0].inner)
            val piece = Polygons.group(region).maxByOrNull { (o, _) -> Math.abs(o.signedAreaMm2()) }
            if (piece == null) emptyMap() else {
                val a = Bridges.bestAngle(listOf(piece.first) + piece.second, bridgeAnchors.getValue(i0), Walls.spacing(bw, h0), bw)
                (i0 until n).associateWith { a }
            }
        }
        // a lifted bridge's fill aligned with its span (plan.technique.bridge.fill_angles = "span", the default; bridges only, sparse
        // infill included, ending with lines along the span on top): from its first layer up, the solid, sparse, and top fill at its span's
        // direction (as its first layer's bridge lines choose it, fill_along's) and at right angles to it, layer by layer,
        // counted from the top so its top layer runs along the span; the base angle of a grid's or cubic's families, the span
        val spanBase: Map<Int, Double> = if (alongBridge || bridgeAnchors.isEmpty() || !s.bridgeFillSpan) emptyMap() else {
            val i0 = bridgeAnchors.keys.min()
            val bw = s.nozzleDiameterUm
            val h0 = layerTopsUm[i0] - (if (i0 > 0) layerTopsUm[i0 - 1] else 0L)
            val region = Booleans.intersection(Offset.offset(regions[i0].bottomSurface, 2 * bw), regions[i0].inner)
            val piece = Polygons.group(region).maxByOrNull { (o, _) -> Math.abs(o.signedAreaMm2()) }
            if (piece == null) emptyMap() else {
                val a = Bridges.bestAngle(listOf(piece.first) + piece.second, bridgeAnchors.getValue(i0), Walls.spacing(bw, h0), bw)
                (i0 until n).associateWith { a }
            }
        }
        /** A bridge layer's own fill angle under fill_angles = "span": the span, or across it, so the top layer (n - 1) is along it. */
        fun spanAngle(k: Int): Double? = spanBase[k]?.let { it + 90.0 * ((n - 1 - k) % 2) }
        val walls = w.walls
        val effOutlines = w.effOutlines
        // What the NEXT layer rests on: the first layer is printed shrunk by the squish compensation
        // precisely so that the bed spreads it back to the true outline, so layer 0 supports its true
        // outline (Benchy: judging layer 1 against the shrunk outline marked its whole
        // outer wall as overhang).
        val asPrinted: List<List<Polygon>> = effOutlines.mapIndexed { k, o -> if (k == 0) outlines[0] else o }
        // planned inserts: by the layer laid onto each insert (the first whose bottom is at or above the insert's top), the
        // insert's cover: what that layer stands on there (its bottom a surface, not a bridge; no overhang for support). Not
        // merged into the printed layers: the cover is under the roof, not material of the layer below (which would then
        // overhang the socket itself)
        val coverAt: Map<Int, List<Polygon>> = if (covers.isEmpty() && standsOn.isEmpty()) emptyMap() else {
            val hs0 = heights(layerTopsUm)
            (covers.mapNotNull { c -> (0 until n).firstOrNull { i -> layerTopsUm[i] - hs0[i] >= c.z0 - 1 }?.let { it to c.polygon() } } +
                standsOn.entries.flatMap { (i, ps) -> ps.map { i to it } })
                .groupBy({ it.first }, { it.second }).mapValues { (_, ps) -> Booleans.union(ps, emptyList()) }
        }
        // the object as a whole: what is below / above (== this object's own layers with one filament)
        val allPrinted = whole?.asPrinted ?: asPrinted
        val allOutlines = whole?.outlines ?: outlines
        val allEff = whole?.w?.effOutlines ?: effOutlines
        val allSparse: List<List<Polygon>> = if (whole == null) regions.map { it.sparse } else requireNotNull(whole.sparse) { "the whole object's printed sparse infill is needed" }
        val out = ArrayList<LayerPlanOut>(n)
        droppedSliverMm2 = 0.0
        // the support switch is a lock: on = everywhere (and every downward face of a locked
        // box), off = nowhere, unset = the plan's spots: the chosen boxes, and the locked ones; never in a blocked box (a
        // region's support = "never"); tree support, the one style
        val supportOn = s.enableSupport ?: (support?.any == true)
        val supportRegions: Supports.Regions? = when {
            support == null -> null
            s.enableSupport == true -> if (support.locked.isEmpty() && support.blocked.isEmpty()) null else Supports.Regions(null, support.locked, support.blocked)
            else -> support
        }
        // the layers between support's top and the object (one, the least that leaves a parting plane, unless set)
        // unset: one layer, but never less than plan.support.gap.top.min (at 0.08 mm layers one
        // layer is 0.08 mm, and support fused onto parts at 0.1 mm in another slicer's print; 0.2 mm held)
        val supportTopGap = s.supportTopZUm?.let { Math.round(it.toDouble() / s.layerHeightUm).toInt().coerceAtLeast(0) }
            ?: maxOf(1, Math.ceil(s.supportTopZMinUm.toDouble() / s.layerHeightUm - 1e-9).toInt())
        val supports: List<LayerSupport>? = if (!supportOn || !objectWide) null else {
            val tipUm = s.treeTipDiameterUm ?: 2 * s.supportWidthUm
            val sp = Supports.Params(
                // plymeld's rules: one support line clear of the object to the
                // side, so a line of support never fuses to the wall it runs along; one layer under the
                // object, the least that leaves a parting plane (plan.support.gap.side / top_z_gap when set)
                Math.round(s.overhang.stepMm * 1000), s.supportXyUm ?: s.supportWidthUm,
                supportTopGap,
                s.supportInterfaceTop, s.supportInterfaceBottom, s.supportOnPlateOnly, s.supportWidthUm, supportRegions, coverAt,
                // plymeld's rule (Supports.dropSpecks): nothing narrower than a column, a tree tip wide (two support
                // lines), is supported; plan.support.column.width when set
                minWidthUm = s.supportMinWidthUm ?: tipUm)
            run {
                // tree support, plymeld's only style. plymeld's rules: a branch leans by
                // the filament's overhang step per layer (a leaning branch is an overhang); a tip is two support lines
                // across, the smallest loop
                // that closes (another slicer's lean and tip when set: investigation)
                TreeSupports.generate(allPrinted, heights(layerTopsUm), sp, TreeSupports.Params(
                    s.treeBranchAngleDeg, tipUm, s.treeBranchDiameterUm, s.treeDiameterAngleDeg, s.treeBranchDistanceUm,
                    leanStepUm = Math.round(s.overhang.stepMm * 1000)), layerTopsUm)
            // support on the model kept off it: bottom_z_gap, unset = one layer (1 µm leaves out exactly the layer on the
            // model's top, Supports.clearAboveModel); "0 mm" = laid onto it
            }.let { gen -> (s.supportBottomZUm ?: 1L).takeIf { it > 0 }?.let { gap -> Supports.clearAboveModel(gen, allPrinted, layerTopsUm, gap, s.supportInterfaceBottom) } ?: gen }
        }
        var endX = 0L; var endY = 0L
        val hs = heights(layerTopsUm)
        // the sparse patterns laid out from the object's middle (the whole object's for one filament's areas)
        val sparseCenter = patternCenter(whole?.outlines ?: outlines)
        val sk = settingsKey
        // The hidden core in thicker layers (layer heights per region in their simplest case; plan.technique.flat.core.layers): from the second layer
        // up, layers in groups of that many (fewer where their heights would pass the tool's thickest layer); the sparse
        // infill a group's layers all share is laid ONCE, on the group's last layer, the group's height thick; each layer's
        // sparse outside the shared region is laid on its own layer as before. Nothing else is moved: within the shared
        // region every layer of the group prints sparse infill only (regions do not overlap), so no wall or solid stands on
        // what is skipped, and the solid above the core stands on its top at a layer top. The first layer stands alone.
        val coreLast = IntArray(n) { -1 }                 // by layer: the last layer of its core group (-1: none)
        val coreRegion = HashMap<Int, List<Polygon>>()    // by a group's last layer: the shared sparse region
        val coreHeight = HashMap<Int, Long>()             // by a group's last layer: the group's height (um)
        if (s.coreLayers > 1) {
            val maxH = requireNotNull(s.maxLayerHeightUm) { "the hidden core in thicker layers (plan.technique.flat.core.layers > 1) needs hardware.tool.<key>.layer.height.max" }
            var i = 1
            while (i < n) {
                var j = i; var sum = 0L
                while (j < n && j - i < s.coreLayers && sum + hs[j] <= maxH) { sum += hs[j]; j++ }
                if (j == i) { i++; continue }
                if (j - i >= 2) {
                    var shared = regions[i].sparse
                    for (k in i + 1 until j) if (shared.isNotEmpty()) shared = Booleans.intersection(shared, regions[k].sparse)
                    // slivers narrower than two sparse lines would be stubs: left to the layers
                    if (shared.isNotEmpty()) shared = Offset.offset(Offset.offset(shared, -s.sparseWidthUm), s.sparseWidthUm)
                    if (shared.isNotEmpty()) { for (k in i until j) coreLast[k] = j - 1; coreRegion[j - 1] = shared; coreHeight[j - 1] = sum }
                }
                i = j
            }
        }
        // Paths of each layer depend only on inputs: built on all cores. Ordering is sequential
        // (each layer starts where the previous one ended).
        class Built(val paths: List<ToolPath>, val dropped: Double)
        // which wall loop points lie on an edge hanging over the layer below: no seam there when the loop has another place
        // (Overhangs.seamOverhangs); layers are independent here
        val seamOver: List<List<List<BooleanArray>>?> = Parallel.map(n, progress.Counter("seams", n)) { i ->
            if (i == 0) null else Overhangs.seamOverhangs(walls[i].walls, allPrinted[i - 1], s.outerWallWidthUm, hs[i]) }
        // seams at corners, or at the back (plan.seam = back), in line with the layer below (Seams): placed in layer order,
        // each from the layer below, before the layers' paths are built in parallel
        val seamVis = if (!s.seamLeastVisible) null else plymeld.core.decide.Visibility.of(s)
        // the shelf a seam sits on (SeamScar.shelfMm), from the layer below's outer walls as seam_scar reads it: for the
        // least-visible placement and the per-loop scarf rule
        val shelfReach = plymeld.core.SeamScar.shelfReachMm(plymeld.core.decide.Visibility.of(s))
        val belowWalls = List(n) { i -> lazy { SeamScar.Below(walls[i].walls.getOrNull(0).orEmpty().filter { it.size >= 3 }) } }
        val outerHalfMm = s.outerWallWidthUm / 2000.0
        fun shelfAt(i: Int): ((Long, Long) -> Double)? =
            if (i == 0 || shelfReach <= 0) null else { val b = belowWalls[i - 1].value; { x, y -> SeamScar.shelfMm(x, y, b, outerHalfMm, shelfReach) } }
        val seams = Seams.alignAll(walls.map { it.walls }, if (s.seamCorners || s.seamLeastVisible) s.seamCornerDeg else null, seamOver, back = s.seamBack,
            spanUm = { i, k -> 2.0 * (if (i == 0) s.firstLayerWidthUm else if (k == 0) s.outerWallWidthUm else s.innerWallWidthUm) },
            leastVisible = s.seamLeastVisible,
            vis = seamVis,
            cornerShare = if (s.seamLeastVisible) s.seamCornerShare else 1.0,
            // what hides a point nearby, from the layers' outer walls, its viewing directions by quadrant: the WHOLE object's for
            // one part of several ([whole]): a part's edge against another part (a letter's inlay against the base it sits in)
            // is covered by it, not an open wall; read as open, the seams went to corners on the visible face instead
            exposure = seamVis?.exposure((whole?.w?.walls ?: walls).map { it.walls.getOrNull(0).orEmpty() }, layerTopsUm),
            shelfOf = { i -> shelfAt(i) })
        val builtAll = Parallel.map(n, progress.Counter("paths", n)) { i ->
            // what this layer's paths are built from (LayerCache): its own walls, regions and outline, the layer below's printed
            // and sparse areas, the layer above's outline (ironing), its seams, support, cover, anchors and core, the settings, and
            // the object's middle its sparse lines are laid out from (patternCenter: the whole object's, not this layer's)
            val kb = LayerCache.Builder("paths").text(sk).text(i).text(hs[i]).text(layerTopsUm[i]).text(objectWide).text(alongBridge)
                .text(sparseCenter?.joinToString(","))
                .text(coreLast[i]).text(coreLast[i].takeIf { it >= 0 }?.let { coreHeight[it] }).text(fillAngle[i])
                .polys(effOutlines[i]).text(walls[i].walls.map { it.size })
            for (ws0 in walls[i].walls) kb.polys(ws0)
            for (c in walls[i].center) kb.longs(c.xy).longs(c.widths)
            regions[i].let { r -> kb.polys(r.inner).polys(r.topSurface).polys(r.bottomSurface).polys(r.internalSolid).polys(r.sparse) }
            seams.getOrNull(i)?.let { ls -> kb.text("seams"); for (k in ls) { kb.text(k.size); for (p in k) kb.text(p.atCorner).longs(p.loop.xy) } } ?: kb.text("noseams")
            seamOver[i]?.let { so -> kb.text("over"); for (k in so) { kb.text(k.size); for (b in k) kb.flags(b) } } ?: kb.text("noover")
            if (i > 0) {
                kb.polys(allPrinted[i - 1]).polys(allSparse[i - 1]).text(layerTopsUm[i - 1]).text(hs[i - 1])
                kb.text(whole?.sparseFamilies?.get(i - 1)?.joinToString(",") { "${it.angleDeg}/${it.spacingUm}/${it.offsetUm}" })
            }
            if (i + 1 < n) kb.polys(allEff[i + 1])
            supports?.get(i)?.let { sup -> kb.text("sup").polys(sup.base).polys(sup.interfaceTop).polys(sup.interfaceBottom).polys(sup.walls) } ?: kb.text("nosup")
            coverAt[i]?.let { kb.text("cover").polys(it) }
            bridgeAnchors[i]?.let { kb.text("anchor").polys(it) }
            if (coreLast[i] >= 0) kb.polys(coreRegion.getValue(coreLast[i]))
            if (i == 0) { kb.text(onBed != null).polys(onBed ?: emptyList()).polys(allOutlines[0]) }
            LayerCache.get(kb.build(), { b: Built -> LayerCache.bytesOfPaths(b.paths) }, s) {
            var droppedHere = 0.0
            val first = i == 0
            val h = hs[i]
            val paths = ArrayList<ToolPath>()
            if (first && objectWide && s.brimType == "outer_only" && s.brimWidthUm > 0) {
                // Brim around the OUTER boundaries only (holes excluded): loops from the gap out to
                // the brim width, one first-layer spacing apart; printed before anything else.
                val outers = allOutlines[0].filter { it.isCcw() }
                val w = s.firstLayerWidthUm; val sp = Walls.spacing(w, h)
                var d = s.brimGapUm + w / 2
                while (d <= s.brimGapUm + s.brimWidthUm - w / 2) {
                    for (lp in Offset.offset(outers, d, Offset.Join.ROUND)) if (lp.isCcw()) paths.add(ToolPath(Feature.BRIM, seamAtBack(lp).xy, true, w, h))
                    d += sp
                }
            }
            supports?.get(i)?.let { sup ->
                // support lines: base at support_angle, interfaces perpendicular; pitch = gap + width
                val w = if (first) s.firstLayerWidthUm else s.supportWidthUm
                fun lines(region: List<Polygon>, feature: Feature, angle: Double, gapUm: Long) {
                    if (region.isEmpty()) return
                    val (segs, idx) = Hatch.indexed(region, angle, gapUm + w)
                    // every line is kept, however short (a filter dropping lines under two widths, meant for 26 dabs
                    // in an engraved name, also dropped the interface of every tree tip (0.8 mm across by design) and the columns
                    // under small faces: a full-scale model's ball went from 97 % to 0 % supported, a pin from 98 % to 30 %;
                    // reverted the same day. The dabs want a filter on the overhang REGIONS, never per line; not built yet)
                    for (chain in plymeld.geom.Linker.link(segs, idx, region, 3 * (gapUm + w))) paths.add(ToolPath(feature, chain, false, w, h))
                }
                for (loop in sup.walls) paths.add(ToolPath(Feature.SUPPORT, loop.xy, true, w, h))   // tree branches: outline first
                lines(sup.base, Feature.SUPPORT, s.supportAngleDeg, s.supportBaseSpacingUm)
                // interface: a solid layer for the object to rest on, lines at bead contact like any solid
                // fill; plan.support.interface.spacing when set
                val itfGap = s.supportInterfaceSpacingUm ?: (Walls.spacing(w, h) - w)
                lines(sup.interfaceTop, Feature.SUPPORT_INTERFACE, s.supportAngleDeg + 90, itfGap)
                lines(sup.interfaceBottom, Feature.SUPPORT_INTERFACE, s.supportAngleDeg + 90, itfGap)
            }
            // Wave overhangs (plan.faces.under.technique = "wave_overhang"): the layer's area over air, the walls' band
            // included, grown from the material under the layer beside it front by front (WaveFronts), each bead leaning on
            // the one before; the wall loops over that area give way to the beads (a wall in the air has nothing under it),
            // and what no front reaches (an island with no anchor, beyond a cut neck) is bridged as before
            // limited by height where the job says (plan.faces.under.from / .to, from the object's bottom): a layer gets the
            // surface technique when its top is above the start and its bottom below the end (the quad tube's top ring
            // alone, not its helices' lower edges on every layer)
            val waveFrom = s.undersideFromUm; val waveTo = s.undersideToUm
            // a lifted bridge's own layers (from its first, the one bridgeAnchors names) are the technique's, left alone, except
            // its first layer when the bridge asks for waves there (plan.technique.bridge.first_layer = "wave_overhang": climbing
            // only, meeting in the middle): grown from every anchor at once over the whole
            // span, in the bridge's warped (flat) frame, so each bead climbs from its anchor once lifted onto the arch
            val waveBridge = s.bridgeFirstLayerWave && bridgeAnchors.isNotEmpty() && i == bridgeAnchors.keys.min()
            // the bridge's first two layers as waves, laid together (plan.technique.bridge.wave.layers = "2"): the second
            // layer's beads are the first's fronts one layer up, laid in the first layer's
            // list one front behind it, so the second layer only lands where the first already reaches one front further out
            val pairedWave = waveBridge && s.bridgeWaveLayers == 2
            require(!pairedWave || s.bridgeWaveAlternate) { "plan.technique.bridge.wave.layers = \"2\" needs wave.sides = \"alternate\" (the order it is defined for)" }
            // ... and that second layer's own area over the gap is left to them (its walls and fill give way, as the first layer's)
            val pairedSecond = s.bridgeFirstLayerWave && s.bridgeWaveLayers == 2 && bridgeAnchors.isNotEmpty() && i == bridgeAnchors.keys.min() + 1
            val waveHere = waveBridge || s.waveOverhang && !first && (waveFrom == null || layerTopsUm[i] > waveFrom + 1) &&
                (waveTo == null || layerTopsUm[i] - h < waveTo - 1) && (bridgeAnchors.isEmpty() || i < bridgeAnchors.keys.min())
            val waveCovered: List<Polygon> = if (pairedSecond) run {
                val k = bridgeAnchors.keys.min()
                val anchor = bridgeAnchors.getValue(k)
                var air = Booleans.difference(effOutlines[k], anchor)
                coverAt[k]?.let { air = Booleans.difference(air, it) }
                if (air.isEmpty()) emptyList() else {
                    val wp = WaveFronts.Params(s.waveSpacingUm, s.waveOverlapUm, s.waveMinNeckUm, s.waveMinAreaMm2, beadUm = s.nozzleDiameterUm)
                    WaveFronts.grow(air, anchor, wp).lastOrNull()?.loops ?: emptyList()
                }
            } else if (!waveHere) emptyList() else run {
                val anchor = bridgeAnchors[i] ?: allPrinted[i - 1]
                var overAirAll = Booleans.difference(effOutlines[i], if (waveBridge) anchor else allPrinted[i - 1])
                coverAt[i]?.let { overAirAll = Booleans.difference(overAirAll, it) }
                if (overAirAll.isEmpty()) return@run emptyList()
                // wave overhang, support, or nothing: an area over air with support under it (in the support's
                // top gap, or the layer below) is the support's; a span (every bridge line landing on what is printed at both
                // ends: between two pillars, a hole's roof) stays a bridge; only what hangs off one side is grown as waves.
                // A lifted bridge's waved first layer is a span by design: grown from both ends
                if (!waveBridge) {
                    val held = (maxOf(0, i - 1 - supportTopGap)..(i - 1)).flatMap { j -> supports?.get(j)?.let { it.base + it.interfaceTop + it.walls }.orEmpty() }
                    val bw0 = s.nozzleDiameterUm
                    overAirAll = Polygons.group(overAirAll).filter { (o, hs) ->
                        val piece = listOf(o) + hs
                        (held.isEmpty() || Booleans.intersection(piece, held).isEmpty()) && !Bridges.spanned(piece, anchor, Walls.spacing(bw0, h), bw0)
                    }.flatMap { (o, hs) -> listOf(o) + hs }
                    if (overAirAll.isEmpty()) return@run emptyList()
                }
                val wp = WaveFronts.Params(s.waveSpacingUm, s.waveOverlapUm, s.waveMinNeckUm, s.waveMinAreaMm2, beadUm = s.nozzleDiameterUm)
                // from one support only (plan.technique.bridge.wave.sides = "one_support", climbing and
                // descending): the fronts grow from the first support (in order of x then y) across the whole span, climbing to
                // the peak and descending to the other support, which they reach last
                val oneSupport = waveBridge && s.bridgeWaveOneSupport
                val growFrom = if (!oneSupport) anchor else Polygons.group(anchor).map { (o, hs) -> listOf(o) + hs }
                    .minWith(compareBy({ g -> g[0].bounds().let { it[0] + it[2] } }, { g -> g[0].bounds().let { it[1] + it[3] } }))
                // a first region laid on its own (plan.technique.bridge.wave.start: edge strips, or a triangle
                // on each support narrowing to the middle), as the waved bridge lays its whole span; the rest after (below)
                val staged = waveBridge && !oneSupport && s.bridgeWaveStart != "whole"
                require(!staged || !pairedWave) { "plan.technique.bridge.wave.start needs wave.layers = \"1\"" }
                // chevron fronts (plan.technique.bridge.wave.front = "chevron"): one part leads by
                // wave.chevron.lead: the edges (unset: two triangles along the edges from each support, a diamond in the middle
                // last) or the middle (wave.chevron.leading = "middle": a triangle out of each support's middle, the tips meeting
                // at the center first, the edges last); lines along the fronts (WaveStart.chevronFronts) or across, rows parallel
                // to the supports' edges (wave.chevron.lines = "across", WaveStart.chevronRows)
                val chevron = waveBridge && !oneSupport && s.bridgeWaveChevron
                require(!chevron || (!pairedWave && !staged)) { "plan.technique.bridge.wave.front = \"chevron\" needs wave.layers = \"1\" and wave.start = \"whole\"" }
                // lines evenly spaced along the bridge's surface, a middle line last (plan.technique.bridge.wave.spacing = "even"):
                // rows across the span (WaveStart.evenRows), each side's and the middle's known as laid out
                val even = waveBridge && !oneSupport && s.bridgeWaveEvenSpacing
                require(!even || (!pairedWave && !staged && !chevron)) { "plan.technique.bridge.wave.spacing = \"even\" needs wave.layers = \"1\", wave.start = \"whole\" and wave.front = \"straight\"" }
                val supports = if (!staged && !chevron && !even) emptyList() else Polygons.group(anchor).map { (o, hs) -> listOf(o) + hs }
                    .sortedWith(compareBy({ g -> g[0].bounds().let { it[0] + it[2] } }, { g -> g[0].bounds().let { it[1] + it[3] } }))
                    .also { require(it.size == 2) { "plan.technique.bridge.wave.start, wave.front = \"chevron\" and wave.spacing = \"even\" need a span between two supports (found ${it.size})" } }
                val evenLines = if (even) WaveStart.evenRows(overAirAll, supports[0], supports[1], wp, bridgeTop) else emptyList()
                val firstRegion = if (!staged) overAirAll else WaveStart.region(overAirAll, supports[0], supports[1], s.bridgeWaveStart, s.bridgeWaveStartWidthUm)
                // a chevron's lines across (wave.chevron.lines = "across"): rows parallel to the supports' edges, not fronts grown
                val across = chevron && s.bridgeWaveChevronAcross
                // the rows whose direction is their own (from the leading region out): the line direction setting leaves them be
                val ownDirection = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<WaveFronts.Bead, Boolean>())
                // what the first region's lines cover (the rows: the whole span; fronts: the last front's area)
                var lastCovered: List<Polygon> = emptyList()
                val perFront: List<List<WaveFronts.Bead>> = if (even) {
                    lastCovered = overAirAll
                    evenLines.groupBy { it.front }.toSortedMap().values.map { ls -> ls.map { WaveFronts.Bead(it.xy, false) } }
                } else if (across) {
                    lastCovered = overAirAll
                    WaveStart.chevronRows(overAirAll, supports[0], supports[1], s.bridgeWaveChevronLeadUm, wp, s.bridgeWaveChevronMiddle)
                        .map { rows -> rows.map { r -> WaveFronts.Bead(r.xy, false).also { if (r.ownDirection) ownDirection += it } } }
                } else {
                    val fronts = if (chevron) WaveStart.chevronFronts(overAirAll, supports[0], supports[1],
                        s.bridgeWaveChevronLeadUm, wp, s.bridgeWaveChevronMiddle)   // unset: half the span
                        else WaveFronts.grow(firstRegion, growFrom, wp)
                    lastCovered = fronts.lastOrNull()?.loops ?: emptyList()
                    // chevron fronts close in on themselves where the sides meet (the diamond): given the span, those rings are laid
                    WaveFronts.beads(fronts, growFrom, s.nozzleDiameterUm, overhang = if (chevron) overAirAll else null,
                        // where fronts close in on themselves, a bead along the middle (a wave overhang's; a waved bridge orders its own beads by side)
                        meetMinAreaMm2 = if (waveBridge) null else wp.minAreaMm2)
                }
                if (perFront.isEmpty()) return@run emptyList()
                val fd = s.filamentDiameterMm
                val ePerMm = s.waveLineAreaMm2 / (Math.PI * fd * fd / 4)   // the bead's own section: nothing to squash it against
                // the second layer's beads (pairedWave): copies of the first layer's, marked by identity to be laid one layer up
                val raisedBeads = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<WaveFronts.Bead, Boolean>())
                fun raised(b: WaveFronts.Bead) = WaveFronts.Bead(b.xy, b.closed).also { raisedBeads += it }
                // a waved bridge: one side whole, then the other (up to the peak from one anchor, then the rest from
                // the other anchor): each bead goes with the anchor it is nearest, the
                // anchors in order of x then y; the fronts meet where they are equally far from both (the middle: the peak of
                // a symmetric arch, not of a lopsided one)
                val ordered = if (!waveBridge || oneSupport) perFront.flatten() else {
                    val groups = Polygons.group(anchor).map { (o, hs) -> listOf(o) + hs }
                    val center = groups.map { g -> val b = g[0].bounds(); doubleArrayOf((b[0] + b[2]) / 2.0, (b[1] + b[3]) / 2.0) }
                    fun dist(g: Int, x: Long, y: Long) = Math.hypot(center[g][0] - x, center[g][1] - y)
                    fun sideOf(x: Long, y: Long) = center.indices.minBy { dist(it, x, y) }
                    // a bead along the span's edge reaches from one anchor to the other (the fronts' sides): cut where it
                    // crosses to the other side, each piece laid from its anchor up (never down), and laid after its side's
                    // beads across the span, along their ends, not over air before them (the first such bead
                    // ran foot to foot before the far side's beads existed)
                    class Piece(val side: Int, val bead: WaveFronts.Bead, val edge: Boolean, val front: Int)
                    val pieces = ArrayList<Piece>()
                    // even rows (wave.spacing = "even"): each line's side known as laid out, the middle line the second side's,
                    // after its lines (it is laid after both sides' either way: its front is the last); the edge pieces as such
                    if (even) {
                        val bySort = center.indices.sortedWith(compareBy({ center[it][0] }, { center[it][1] }))
                        for (l in evenLines.filter { !it.edge }.sortedBy { if (it.side == 2) 1 else 0 })
                            pieces += Piece(bySort[minOf(l.side, 1)], WaveFronts.Bead(l.xy, false), false, l.front)
                        for (l in evenLines.filter { it.edge }) pieces += Piece(bySort[l.side], WaveFronts.Bead(l.xy, false), true, l.front)
                    }
                    for ((fi, f) in (if (even) emptyList() else perFront).withIndex()) for (b in f) {
                        val n = b.xy.size / 2
                        val sd = IntArray(n) { k -> sideOf(b.xy[2 * k], b.xy[2 * k + 1]) }
                        if (b.closed || sd.all { it == sd[0] }) {
                            pieces += Piece(sd[n / 2], b, false, fi); continue
                        }
                        // the bead's points, with the point where it crosses from one side to the other put in (found by halving:
                        // where it is as far from one anchor as from the other); pieces run from crossing to crossing
                        val runs = ArrayList<ArrayList<Long>>(); var cur = arrayListOf(b.xy[0], b.xy[1]); var curSide = sd[0]
                        for (k in 1 until n) {
                            if (sd[k] != curSide) {
                                var lo = 0.0; var hi = 1.0
                                val x0 = b.xy[2 * k - 2].toDouble(); val y0 = b.xy[2 * k - 1].toDouble(); val x1 = b.xy[2 * k].toDouble(); val y1 = b.xy[2 * k + 1].toDouble()
                                repeat(30) { val t = (lo + hi) / 2; if (sideOf(Math.round(x0 + t * (x1 - x0)), Math.round(y0 + t * (y1 - y0))) == curSide) lo = t else hi = t }
                                val cx = Math.round(x0 + lo * (x1 - x0)); val cy = Math.round(y0 + lo * (y1 - y0))
                                cur.add(cx); cur.add(cy); runs += cur.also { it.add(0, curSide.toLong()) }
                                cur = arrayListOf(cx, cy); curSide = sd[k]
                            }
                            cur.add(b.xy[2 * k]); cur.add(b.xy[2 * k + 1])
                        }
                        runs += cur.also { it.add(0, curSide.toLong()) }
                        for (r in runs) {
                            val g = r[0].toInt()
                            var xy = LongArray(r.size - 1) { r[it + 1] }
                            // from its anchor up: the end nearer the anchor first
                            if (dist(g, xy[0], xy[1]) > dist(g, xy[xy.size - 2], xy[xy.size - 1]))
                                xy = LongArray(xy.size) { k -> xy[(xy.size / 2 - 1 - k / 2) * 2 + k % 2] }
                            // a middle-out chevron's fronts cross the middle along their whole length (the V's through the
                            // regions along the edges): each piece is one of its side's lines, laid in front order, its
                            // direction its own (from the anchor up), not an edge piece (taken for edge pieces,
                            // they vanished with wave.edges.enabled = false and the check found the strip's edge unlaid)
                            if (xy.size >= 4) {
                                val frontLine = chevron && s.bridgeWaveChevronMiddle
                                val bead = WaveFronts.Bead(xy, false).also { if (frontLine) ownDirection += it }
                                pieces += Piece(g, bead, !frontLine, fi)
                            }
                        }
                    }
                    val sides = center.indices.sortedWith(compareBy({ center[it][0] }, { center[it][1] }))
                    // which edge each bead across the span starts from (plan.technique.bridge.wave.line.direction):
                    // mirrored (unset) = as grown; matched = every bead from the same edge
                    // (the starts and ends do not mirror between the sides); zigzag = matched, every other front reversed
                    if (s.bridgeWaveDirection != "mirrored" && sides.size >= 2) {
                        val ax = center[sides[1]][0] - center[sides[0]][0]; val ay = center[sides[1]][1] - center[sides[0]][1]
                        fun acr(x: Long, y: Long) = -ay * x + ax * y
                        fun rev(xy: LongArray) = LongArray(xy.size) { k -> xy[(xy.size / 2 - 1 - k / 2) * 2 + k % 2] }
                        for (k in pieces.indices) {
                            val p = pieces[k]
                            if (p.edge || p.bead.closed || p.bead.xy.size < 4 || p.bead in ownDirection) continue
                            val xy = p.bead.xy; val n = xy.size / 2
                            val forward = acr(xy[0], xy[1]) <= acr(xy[2 * n - 2], xy[2 * n - 1])
                            val want = if (s.bridgeWaveDirection == "zigzag") p.front % 2 == 0 else true
                            if (forward != want) pieces[k] = Piece(p.side, WaveFronts.Bead(rev(xy), false), false, p.front)
                        }
                    }
                    // none at all with plan.technique.bridge.wave.edges.enabled = false
                    fun edges(g: Int) = if (s.bridgeWaveEdgesNone) emptyList() else pieces.filter { it.side == g && it.edge }.map { it.bead }
                    fun across(g: Int) = pieces.filter { it.side == g && !it.edge }.map { it.bead }
                    // the edge pieces after both sides' beads (plan.technique.bridge.wave.edges.order = "last", to tell
                    // the order of the sides from when the edges are laid; alternating lays them last either way)
                    if (!s.bridgeWaveAlternate && s.bridgeWaveEdgesLast) sides.flatMap { across(it) } + sides.flatMap { edges(it) }
                    else if (!s.bridgeWaveAlternate) sides.flatMap { g -> across(g) + edges(g) }
                    // the sides alternating front by front (plan.technique.bridge.wave.sides = "alternate", so the first
                    // side does not droop as a cantilever while the second is laid): each front's beads on one side, then on the other,
                    // each still climbing from its own anchor; then each side's edge pieces, along the beads' ends
                    else if (!pairedWave) pieces.filter { !it.edge }.sortedWith(compareBy({ it.front }, { sides.indexOf(it.side) })).map { it.bead } + sides.flatMap { edges(it) }
                    else if (s.bridgeWaveSecondX) {
                        // the second layer only Xs (plan.technique.bridge.wave.second_layer = "x", a crosshatch for stability:
                        // only whole Xs, one over the middle): the first layer's sides alternate front by front; as soon as both reach the end of a section
                        // (wave.x_length, whole fronts), an X over it on each side in turn, corner to corner between the section's
                        // first and last fronts' ends; sections from each support, the one over the middle taking what is left
                        require(sides.size == 2) { "plan.technique.bridge.wave.second_layer = \"x\" needs a span between two supports (found ${sides.size})" }
                        val lenUm = s.bridgeWaveXLengthUm ?: error("plan.technique.bridge.wave.second_layer = \"x\" needs plan.technique.bridge.wave.x_length (each X's reach along the span)")
                        val k = maxOf(2, Math.round(lenUm.toDouble() / s.waveSpacingUm).toInt())
                        val bySide = sides.associateWith { g -> pieces.filter { !it.edge && it.side == g }.groupBy { it.front } }
                        for ((g, m) in bySide) require(m.values.all { it.size == 1 }) { "plan.technique.bridge.wave.second_layer = \"x\" needs each front to be one line across the span (support ${sides.indexOf(g) + 1} has a front of several)" }
                        val fsOf = sides.associateWith { g -> bySide.getValue(g).keys.sorted() }
                        val nOf = sides.associateWith { g -> maxOf(0, Math.floor((fsOf.getValue(g).size - k / 2.0) / k).toInt()) }
                        val ax = center[sides[1]][0] - center[sides[0]][0]; val ay = center[sides[1]][1] - center[sides[0]][1]
                        fun acr(x: Long, y: Long) = -ay * x + ax * y
                        // a front's two ends, the one at the lower across-the-span position first
                        fun ends(b: WaveFronts.Bead): LongArray {
                            val xy = b.xy; val n = xy.size / 2
                            val e = longArrayOf(xy[0], xy[1], xy[2 * n - 2], xy[2 * n - 1])
                            return if (acr(e[0], e[1]) <= acr(e[2], e[3])) e else longArrayOf(e[2], e[3], e[0], e[1])
                        }
                        fun front(g: Int, pos: Int) = bySide.getValue(g).getValue(fsOf.getValue(g)[pos])[0].bead
                        // an X between two fronts: one diagonal from the first's low end to the last's high end, then the other from
                        // the first's high end (a section's length from where the first ended, not the span's width) to the last's low end
                        fun x(a: WaveFronts.Bead, b: WaveFronts.Bead): List<WaveFronts.Bead> {
                            val p = ends(a); val q = ends(b)
                            return listOf(raised(WaveFronts.Bead(longArrayOf(p[0], p[1], q[2], q[3]), false)), raised(WaveFronts.Bead(longArrayOf(p[2], p[3], q[0], q[1]), false)))
                        }
                        val seq = ArrayList<WaveFronts.Bead>()
                        val fs = pieces.filter { !it.edge }.map { it.front }.distinct().sorted()
                        for (f in fs) {
                            for (g in sides) bySide.getValue(g)[f]?.let { seq += it[0].bead }
                            for (g in sides) {
                                val pos = fsOf.getValue(g).indexOf(f)
                                if (pos > 0 && pos % k == 0 && pos / k <= nOf.getValue(g)) seq += x(front(g, pos - k), front(g, pos))
                            }
                        }
                        // the X over the middle: between each side's last section end
                        seq += x(front(sides[0], nOf.getValue(sides[0]) * k), front(sides[1], nOf.getValue(sides[1]) * k))
                        // the edge pieces, the first layer's and then, as with a full second layer, the same one layer up:
                        // the strip's outline over the gap keeps its wall on the X layer
                        seq + sides.flatMap { edges(it) } + sides.flatMap { g -> edges(g).map(::raised) }
                    }
                    else {
                        // the two layers together: first-layer fronts 0 and 1, then each next first-layer front followed by the
                        // second-layer front two behind it, every front's beads side by side in turn; the second layer's last front
                        // last; then the edge pieces, the first layer's and then the second's
                        val byFront = pieces.filter { !it.edge }.groupBy { it.front }
                        val fs = byFront.keys.sorted()
                        fun step(f: Int, up: Boolean) = byFront.getValue(f).sortedBy { sides.indexOf(it.side) }.map { if (up) raised(it.bead) else it.bead }
                        val seq = ArrayList<WaveFronts.Bead>()
                        for ((k, f) in fs.withIndex()) { seq += step(f, false); if (k >= 1) seq += step(fs[k - 1], true) }
                        if (fs.isNotEmpty()) seq += step(fs.last(), true)
                        seq + sides.flatMap { edges(it) } + sides.flatMap { g -> edges(g).map(::raised) }
                    }
                }
                for (b in ordered) if (b.xy.size >= 4) paths.add(ToolPath(Feature.WAVE, b.xy, b.closed, s.nozzleDiameterUm, h, null, ePerMm, raiseUm = if (b in raisedBeads) h else 0L))
                // the lines across laid a second time over the first layer where its zones say (plan.technique.bridge.
                // wave.second_pass.zones): each the other way to
                // the line under it, from the zones' outer ends inward, the two sides in turn, a line at the middle last; laid
                // after the whole layer (the ordering below), no edge pieces
                val overLayer = s.bridgeWaveSecondPass.isNotEmpty() || s.bridgeWaveXLayer || s.bridgeWaveSpanLayer
                if (waveBridge && !oneSupport && overLayer) {
                    val sp = supports.ifEmpty {
                        Polygons.group(anchor).map { (o, hs) -> listOf(o) + hs }
                            .sortedWith(compareBy({ g -> g[0].bounds().let { it[0] + it[2] } }, { g -> g[0].bounds().let { it[1] + it[3] } }))
                            .also { require(it.size == 2) { "plan.technique.bridge.wave.second_pass, wave.x_layer and wave.span_layer need a span between two supports (found ${it.size})" } }
                    }
                    val fr = WaveStart.Frame(overAirAll, sp[0], sp[1])
                    class Line(val xy: LongArray, val t: Double)
                    val lines = ordered.filter { b -> b !in raisedBeads && !b.closed && b.xy.size >= 4 }.mapNotNull { b ->
                        val n = b.xy.size / 2
                        val ta = fr.t(b.xy[0], b.xy[1]); val tb = fr.t(b.xy[2 * n - 2], b.xy[2 * n - 1])
                        val len = Math.hypot((b.xy[2 * n - 2] - b.xy[0]).toDouble(), (b.xy[2 * n - 1] - b.xy[1]).toDouble())
                        if (len == 0.0 || Math.abs(tb - ta) > 0.5 * len) null else Line(b.xy, (ta + tb) / 2)   // along the span: an edge piece
                    }
                    if (s.bridgeWaveSecondPass.isNotEmpty()) {
                        fun zone(t: Double) = ((t - fr.t0) / (fr.t1 - fr.t0)).let { f -> s.bridgeWaveSecondPass.firstOrNull { f >= it.from && (f < it.to || it.to >= 1.0) } }
                        val inZone = lines.filter { zone(it.t) != null }
                        val middle = inZone.filter { Math.abs(it.t - fr.tm) <= MIDDLE_TOL_UM }
                        val sideA = inZone.filter { it.t < fr.tm - MIDDLE_TOL_UM }.sortedBy { it.t }
                        val sideB = inZone.filter { it.t > fr.tm + MIDDLE_TOL_UM }.sortedByDescending { it.t }
                        val seq = (0 until maxOf(sideA.size, sideB.size)).flatMap { k -> listOfNotNull(sideA.getOrNull(k), sideB.getOrNull(k)) } + middle
                        for (l in seq) {
                            val z = zone(l.t)!!
                            val r = LongArray(l.xy.size) { k -> l.xy[(l.xy.size / 2 - 1 - k / 2) * 2 + k % 2] }
                            paths.add(ToolPath(Feature.WAVE, r, false, s.nozzleDiameterUm, h, null, ePerMm * z.share, raiseUm = z.raiseUm, afterLayer = AfterLayer.SECOND_PASS))
                        }
                    }
                    if (s.bridgeWaveXLayer || s.bridgeWaveSpanLayer) require(even) {
                        "plan.technique.bridge.wave.x_layer and wave.span_layer need plan.technique.bridge.wave.spacing = \"even\" (each line straight across the span)" }
                    // Xs over the layer (wave.x_layer: after the first layer, the sides alternating from the feet to
                    // the middle): each side's lines from its support, in sections of wave.x_length
                    // (whole lines, as wave.second_layer = x counts them), the middle X between each side's last section end
                    if (s.bridgeWaveXLayer) {
                        val raise = s.bridgeWaveXLayerRaiseUm ?: error("plan.technique.bridge.wave.x_layer.enabled needs plan.technique.bridge.wave.x_layer.raise")
                        val share = s.bridgeWaveXLayerShare ?: error("plan.technique.bridge.wave.x_layer.enabled needs plan.technique.bridge.wave.x_layer.share")
                        val inset = s.bridgeWaveXLayerInsetUm ?: error("plan.technique.bridge.wave.x_layer.enabled needs plan.technique.bridge.wave.x_layer.inset")
                        val lenUm = s.bridgeWaveXLengthUm ?: error("plan.technique.bridge.wave.x_layer.enabled needs plan.technique.bridge.wave.x_length (each X's reach along the span)")
                        val k = maxOf(2, Math.round(lenUm.toDouble() / s.waveSpacingUm).toInt())
                        // the side the layer began on first (its first line across)
                        val low = lines.filter { it.t < fr.tm - MIDDLE_TOL_UM }.sortedBy { it.t }
                        val high = lines.filter { it.t > fr.tm + MIDDLE_TOL_UM }.sortedByDescending { it.t }
                        require(low.isNotEmpty() && high.isNotEmpty()) { "plan.technique.bridge.wave.x_layer needs lines across the span on both sides of its middle" }
                        val (sideA, sideB) = if (lines.first().t < fr.tm) low to high else high to low
                        require(fr.c1 - fr.c0 > 2.0 * inset) { "plan.technique.bridge.wave.x_layer.inset (${inset / 1000.0} mm) leaves nothing of the gap ${(fr.c1 - fr.c0) / 1000.0} mm across" }
                        // a line's two ends for an X, the inset in from the gap's edges (each line runs straight across the span:
                        // wave.spacing = even), the one at the lower across-the-span position first
                        fun ends(l: Line): DoubleArray {
                            val e = fr.line(l.t, fr.c0 + inset, l.t, fr.c1 - inset)
                            return DoubleArray(4) { e[it].toDouble() }
                        }
                        // an X from line [a] (nearer the support) to line [b]: low end to high end, then high end to low end
                        fun x(a: Line, b: Line) {
                            val p = ends(a); val q = ends(b)
                            for (d in listOf(doubleArrayOf(p[0], p[1], q[2], q[3]), doubleArrayOf(p[2], p[3], q[0], q[1])))
                                paths.add(ToolPath(Feature.WAVE, LongArray(4) { Math.round(d[it]) }, false, s.nozzleDiameterUm, h, null, ePerMm * share,
                                    raiseUm = raise, afterLayer = AfterLayer.X_LAYER))
                        }
                        // each side's sections, at least half a section left to the middle X
                        fun sections(n: Int) = maxOf(0, Math.floor((n - k / 2.0) / k).toInt())
                        val nA = sections(sideA.size); val nB = sections(sideB.size)
                        // the middle X first with wave.x_layer.middle = first (then the sides alternating, outward
                        // in), else last
                        if (s.bridgeWaveXLayerMiddleFirst) x(sideA[nA * k], sideB[nB * k])
                        for (j in 0 until maxOf(nA, nB)) {
                            if (j < nA) x(sideA[j * k], sideA[(j + 1) * k])
                            if (j < nB) x(sideB[j * k], sideB[(j + 1) * k])
                        }
                        if (!s.bridgeWaveXLayerMiddleFirst) x(sideA[nA * k], sideB[nB * k])
                    }
                    // a full layer along the span over it (wave.span_layer: a full layer one layer above the Xs):
                    // support to support, the solid fill's width and spacing across the gap,
                    // each line the other way to the one before, at the solid fill's plastic
                    if (s.bridgeWaveSpanLayer) {
                        val raise = s.bridgeWaveSpanLayerRaiseUm ?: error("plan.technique.bridge.wave.span_layer.enabled needs plan.technique.bridge.wave.span_layer.raise")
                        val w = s.solidWidthUm
                        val step = Walls.spacing(w, h)
                        val across = fr.c1 - fr.c0 - w
                        require(across >= 0) { "plan.technique.bridge.wave.span_layer: the gap (${(fr.c1 - fr.c0) / 1000.0} mm across) is narrower than one line" }
                        val n = Math.floor(across / step).toInt() + 1
                        val first = (fr.c0 + fr.c1) / 2 - (n - 1) * step / 2.0
                        for (j in 0 until n) {
                            val c = first + j * step
                            val xy = if (j % 2 == 0) fr.line(fr.t0, c, fr.t1, c) else fr.line(fr.t1, c, fr.t0, c)
                            paths.add(ToolPath(Feature.WAVE, xy, false, w, h, raiseUm = raise, afterLayer = AfterLayer.SPAN_LAYER))
                        }
                    }
                }
                if (!staged) lastCovered else {
                    // the rest of the span: waves grown from the supports and the first region together, front by front
                    //
                    val covered = lastCovered
                    val rest = Booleans.difference(overAirAll, covered)
                    val from = Booleans.union(anchor, covered)
                    val fill = if (rest.isEmpty()) emptyList() else WaveFronts.grow(rest, from, wp)
                    for (b in WaveFronts.beads(fill, from, s.nozzleDiameterUm, overhang = rest).flatten())
                        if (b.xy.size >= 4) paths.add(ToolPath(Feature.WAVE, b.xy, b.closed, s.nozzleDiameterUm, h, null, ePerMm, waveFill = true))
                    if (fill.isEmpty()) covered else Booleans.union(covered, fill.last().loops)
                }
            }
            /** The object's other parts on this layer, for one part of several ([whole]): the whole object less this part. */
            val otherParts by lazy { if (whole == null) emptyList() else Booleans.difference(whole.outlines[i], outlines[i]) }
            /** Whether another part lies within one of [c]'s widths of its middle point. */
            fun otherPartNear(c: CenterLine): Boolean {
                val others = otherParts
                if (others.isEmpty()) return false
                val m = c.xy.size / 4 * 2
                val grown = plymeld.geom.Offset.offset(others, c.maxWidthUm, plymeld.geom.Offset.Join.ROUND)
                return grown.count { plymeld.geom.Polygons.contains(it, c.xy[m], c.xy[m + 1]) } % 2 == 1
            }
            /** A wall's runs outside what the waves cover (the whole loop, closed, when none of it is over the waves). */
            fun overWaves(xy: LongArray, closed: Boolean): List<LongArray>? = if (waveCovered.isEmpty()) null else plymeld.geom.Clip.outside(xy, closed, waveCovered).takeIf { it.size != 1 || it[0] !== xy }
            // walls: inner walls first (last loop inward-most) then outer, per wall_sequence
            val ws = walls[i].walls
            val order = if (s.innerWallsFirst) ws.indices.reversed() else ws.indices
            // arachne center lines are the innermost walls: first with inner walls first, else last. One that runs along the
            // model's edge (where the part is too thin for an outer loop: a fin, a wedge's narrow end) IS the outer wall
            // there, the visible surface: it is named so, and prints as one (a wedge's narrow end printed
            // as an inner wall, at hidden speed, and the check found no wall along its outline)
            fun center() { for (c0 in walls[i].center) { val c = Walls.simplified(c0, s.resolutionUm)
                val outer = Walls.alongOutline(c.xy, c.widths, effOutlines[i])
                val f = if (outer) Feature.OUTER_WALL else Feature.INNER_WALL
                val runs = overWaves(c.xy, false)
                if (runs != null) { for (rn in runs) if (rn.size >= 4) paths.add(ToolPath(f, rn, false, c.maxWidthUm, h)); continue }
                // a speck: an outer-wall line kept only because it lines the outline (shorter than two of its widths, the
                // threshold Walls keeps it under) prints without a retraction and prime of its own wherever the travel to it
                // stays in its island and crosses no wall, whatever the in-island switch (keepInIsland): a restart for a dot
                // left a blob on a show face (kept, not dropped, so the outline stays walled)
                val speck = outer && Planner.polylineLength(c.xy) < 2.0 * c.maxWidthUm
                // a speck shorter than one of its widths where another part of the object lies within a width of it (this part a
                // sliver in front of the other there) is left out: a dot of one filament, stopped and restarted on the visible
                // face every layer, made a column of blobs (on a two-color nameplate, 142 such starts on the lettering's face),
                // and the part behind fills the outline there within less than a line
                if (speck && whole != null && Planner.polylineLength(c.xy) < c.maxWidthUm && otherPartNear(c)) continue
                paths.add(ToolPath(f, c.xy, false, c.maxWidthUm, h, widths = c.widths, speck = speck)) } }
            if (s.innerWallsFirst) center()
            if (s.fuzzySkin != "none") require(s.fuzzyMode == "displacement" && s.fuzzyNoise == "classic") {
                "fuzzy skin mode '${s.fuzzyMode}' / noise '${s.fuzzyNoise}' is not implemented yet (only displacement + classic)" }
            for (k in order) {
                val feature = if (k == 0) Feature.OUTER_WALL else Feature.INNER_WALL
                val w = if (first) s.firstLayerWidthUm else if (k == 0) s.outerWallWidthUm else s.innerWallWidthUm
                for ((li, loop) in ws[k].withIndex()) {
                    // fuzzy skin, per fuzzy_skin (measured in QIDIStudio on a block with a hole):
                    // external = outside outlines of the outer wall, all = + hole walls, allwalls = every wall
                    val fuzz = (!first || s.fuzzyFirstLayer) && when (s.fuzzySkin) {
                        "none" -> false
                        "external" -> k == 0 && loop.isCcw()
                        "all" -> k == 0
                        "allwalls" -> true
                        else -> error("fuzzy_skin '${s.fuzzySkin}' is not a known value")
                    }
                    // the seam: at a corner when the loop has one, in line with the layer below (Seams), or at the back; never on
                    // an edge hanging over the layer below when the loop has another place
                    val placed = seams.getOrNull(i)?.getOrNull(k)?.getOrNull(li) ?: Seams.place(loop, null, 0.0, overhangs = seamOver[i]?.get(k)?.get(li))
                    val smooth = placed.loop.xy
                    // a scarf seam on an outer wall above the first layer (not with fuzzy skin): every one, or those with no corner
                    val scarfWanted = if (k == 0 && !first && !fuzz && s.scarfStart != null &&
                        (s.scarf == "all" || (s.scarf == "smooth" && !placed.atCorner))) s.scarfLengthUm ?: 0L else 0L
                    // ... and only where it leaves less blemish than a point seam: not on a shelf wider than a line, where its ramp
                    // lies seen from above (SeamScar.scarfPays; a lying pin's top printed its scarfs as a trail)
                    val scarfUm = if (scarfWanted > 0 && shelfAt(i)?.let { sh ->
                            !SeamScar.scarfPays(w / 1000.0, sh(placed.loop.x(0), placed.loop.y(0)), s.seamScarfShare, scarfWanted / 1000.0) } == true) 0L else scarfWanted
                    // overhang share per segment against the layer below (walls only, above layer 0),
                    // judged on the SMOOTH loop: fuzz pushing a point 0.3 mm out is not an overhang
                    val smoothShares = if (!first) Overhangs.edgeShares(smooth, allPrinted[i - 1], w, h) else null
                    var xy = smooth
                    var shares = smoothShares
                    if (fuzz) {
                        val f = Fuzzy.loop(smooth, s.fuzzyThicknessUm, s.fuzzyPointDistUm, (i.toLong() shl 32) or (k.toLong() shl 20) or li.toLong())
                        xy = f.xy
                        shares = smoothShares?.let { st -> IntArray(f.sourceEdge.size) { st[f.sourceEdge[it]] } }
                    }
                    // over the waves the loop is open runs on supported material, no seam or scarf (the beads carry the edge over air)
                    val runs = overWaves(xy, true)
                    if (runs != null) { for (rn in runs) if (rn.size >= 4) paths.add(ToolPath(feature, rn, false, w, h)); continue }
                    paths.add(ToolPath(feature, xy, true, w, h, shares, scarfUm = scarfUm, scarfStart = s.scarfStart ?: 0.0))
                }
            }
            if (!s.innerWallsFirst) center()
            val r = if (!pairedSecond || waveCovered.isEmpty()) regions[i] else regions[i].let { g ->
                fun off(p: List<Polygon>) = if (p.isEmpty()) p else Booleans.difference(p, waveCovered)
                LayerRegions(off(g.inner), off(g.topSurface), off(g.bottomSurface), off(g.internalSolid), off(g.sparse)) }
            val parity = i % 2
            fun hatch(region: List<Polygon>, feature: Feature, width: Long, spacing: Long, angles: List<Double>, height: Long = h, phases: List<Double>? = null) {
                if (region.isEmpty()) return
                var hatchRegion = region
                if (feature != Feature.SPARSE) {
                    // 1. Ring-shaped bands up to MAX_CONCENTRIC_LINES wide get loops along the band
                    //    (no fragmented toolpaths: hatching a ring gives a saw-tooth of micro-segments, seen
                    //    on a domed test model). Detected on the RAW region: a sliver filter first would pinch a
                    //    thin ring into crescents that then get hatched.
                    //    A NARROW region without holes (under MAX_CONCENTRIC_LINES across) gets loops too,
                    //    each covering both sides: crossed by the hatch, it is mostly line ends - a wedge
                    //    of air or a connector laid on the wall at every one - while loops along its
                    //    outline have no ends at the walls at all (testplate pillars and slot strips,
                    //    a deposition check against the model). Loop count from its depth (the largest
                    //    inward offset that leaves anything), widths adjusted so the loops fill it exactly.
                    val leftovers = ArrayList<Polygon>()
                    for ((outer, holes) in Polygons.group(region)) {
                        val area = outer.signedAreaMm2() + holes.sumOf { it.signedAreaMm2() }
                        val perim = outer.perimeterMm() + holes.sumOf { it.perimeterMm() }
                        val maxDepth = MAX_CONCENTRIC_LINES * spacing / 2
                        val narrow = holes.isEmpty() && area > 0.0 && Offset.offset(listOf(outer), -maxDepth).isEmpty()
                        val bandUm = when {
                            narrow -> { var lo = 0L; var hi = maxDepth
                                repeat(8) { val mid = (lo + hi) / 2; if (Offset.offset(listOf(outer), -mid).isEmpty()) hi = mid else lo = mid }
                                lo.toDouble() }
                            perim > 0 -> 2 * area / perim * 1000
                            else -> 0.0
                        }
                        if (!narrow && (holes.isEmpty() || area <= 0.0 || bandUm > MAX_CONCENTRIC_LINES * spacing)) { leftovers.add(outer); leftovers.addAll(holes); continue }
                        if (bandUm < width / (if (narrow) 4 else 2)) { droppedHere += area; continue }   // an unprintable hairline
                        val loopsN = Math.max(1, Math.round(bandUm / spacing).toInt())
                        val share = bandUm / loopsN                              // spacing each loop fills
                        // a narrow region too thin for a loop of at least half a line (testplate slot
                        // strip, 0.24 mm: a 0.16 mm "bead", thinner than the layer) is left to the hatch
                        if (narrow && share < spacing / 2) { leftovers.add(outer); continue }
                        val widthK = Math.round(share + h * (1 - Math.PI / 4))   // extrusion width with that spacing
                        var placed = false
                        for (k in 0 until loopsN) {
                            val d = Math.round(share * (k + 0.5))
                            for (lp in Offset.offset(listOf(outer), -d)) if (lp.isCcw() && lp.signedAreaMm2() > 0.01) {
                                paths.add(ToolPath(feature, seamAtBack(lp).xy, true, widthK, h)); placed = true
                            }
                        }
                        if (!placed) { leftovers.add(outer); leftovers.addAll(holes) }
                    }
                    if (leftovers.isEmpty()) return
                    // 2. What is left gets hatched, minus slivers narrower than half a line (an opening
                    //    by w/4). Without it, a domed test model had hundreds of solid dashes along
                    //    the wall where QIDIStudio printed no solid at all.
                    val q = width / 4
                    val cleaned = Offset.offset(Offset.offset(leftovers, -q), q)
                    droppedHere += leftovers.sumOf { it.signedAreaMm2() } - cleaned.sumOf { it.signedAreaMm2() }
                    hatchRegion = cleaned
                    if (hatchRegion.isEmpty()) return
                }
                // Lines are clipped with their CENTER on the region's edge, so the region is first
                // pulled in to where a bead of this width merely touches the wall bead beside it -
                // the same center distance as between any two neighboring beads, w/2 - h(1 - pi/4)
                // from the wall's edge - and infill_wall_overlap then adds its bonding overlap on
                // top. Clipping on the wall's edge itself laid half of every line end and every
                // linking run on the wall: testplate overfill near the surface 175 mm2 on three
                // layers, missing + excess plastic 16.1 mm3 against 6.1 with contact placement
                // (a deposition sweep against the model).
                val grown = Offset.offset(hatchRegion, Math.round(s.infillWallOverlap * width) - contactInset(width, height))
                if (feature == Feature.SPARSE && s.sparsePattern == "gyroid") {
                    // gyroid: the surface's cross-section at the layer's middle height, [spacing] its period (Gyroid)
                    for (pl in Gyroid.curves(grown, spacing, layerTopsUm[i] - height / 2.0, sparseCenter, 2 * width, s.sparseChordUm ?: 0L))
                        paths.add(ToolPath(feature, pl, false, width, height))
                    return
                }
                if (feature == Feature.SPARSE) {
                    // all directions linked together: ends of crossing lines meet near each other at
                    // the wall (see Linker.linkAll)
                    val segs = angles.mapIndexed { j, a -> Hatch.indexed(grown, a, spacing, phases?.get(j) ?: 0.0).first }.let { parts ->
                        val all = LongArray(parts.sumOf { it.size }); var o = 0
                        for (p in parts) { p.copyInto(all, o); o += p.size }
                        all
                    }
                    for (chain in plymeld.geom.Linker.linkAll(segs, grown, s.anchorMaxUm ?: ANCHOR_SPACINGS * spacing)) {
                        // a sparse stub shorter than two line widths is a blob, not infill: it carries
                        // nothing and costs a travel (grid lines clipped at a region's corner)
                        if (polylineLength(chain) < 2 * width) { droppedHere += polylineLength(chain) * width / 1e6; continue }
                        paths.add(ToolPath(feature, chain, false, width, height))
                    }
                    return
                }
                for (a in angles) {
                    val (segs, lineIdx) = Hatch.indexed(grown, a, spacing)
                    // Link neighboring lines along the region boundary: one continuous run instead of a
                    // retract + travel per line, the connector at most ANCHOR_SPACINGS line spacings
                    // (sparse / internal solid: another slicer's anchor limit instead, when one is set).
                    // Top / bottom (monotonic): only to the ADJACENT line, so the sweep stays monotonic.
                    val limit = if (feature == Feature.SPARSE || feature == Feature.SOLID) s.anchorMaxUm ?: ANCHOR_SPACINGS * spacing else ANCHOR_SPACINGS * spacing
                    // a curved band's top laid uphill (plan.technique.curved.top_order = "uphill"): laid
                    // one way across the peak, the half the sweep went down had each new line below the last, beside it, where the
                    // nozzle's flat tip can drag through it (plate 8's solid arch came out rough on that half, the g-code alike on
                    // both halves otherwise). The lines split at the scanline where the surface is highest, each side linked on its
                    // own, each chain turned to start at its low end, the chains laid from the lowest start up ([ToolPath.climbFrom])
                    if (feature == Feature.TOP && s.curvedTopUphill && surfaceTop != null && segs.isNotEmpty()) {
                        val n = segs.size / 4
                        fun zAt(x: Long, y: Long) = surfaceTop(x / 1000.0, y / 1000.0)
                        val sum = HashMap<Int, Double>(); val cnt = HashMap<Int, Int>()
                        for (k in 0 until n) {
                            val z = zAt((segs[4 * k] + segs[4 * k + 2]) / 2, (segs[4 * k + 1] + segs[4 * k + 3]) / 2)
                            if (z.isNaN()) continue
                            sum.merge(lineIdx[k], z, Double::plus); cnt.merge(lineIdx[k], 1, Int::plus)
                        }
                        val mean = sum.mapValues { (j, z) -> z / cnt.getValue(j) }
                        if (mean.isNotEmpty() && mean.values.max() - mean.values.min() > CLIMB_MIN_RISE_MM) {
                            val ridge = mean.maxBy { it.value }.key
                            // which scanline each segment end lies on: a chain is turned by the lines it starts and ends on, so
                            // it runs towards the ridge (not by the surface at its two ends: a chain over a foot's top has both
                            // ends flat and its middle lines rising onto the slope, and was left running down them)
                            val lineAt = HashMap<Pair<Long, Long>, Int>()
                            for (k in 0 until n) { lineAt[segs[4 * k] to segs[4 * k + 1]] = lineIdx[k]; lineAt[segs[4 * k + 2] to segs[4 * k + 3]] = lineIdx[k] }
                            for (low in listOf(true, false)) {
                                val ks = (0 until n).filter { (lineIdx[it] <= ridge) == low }
                                if (ks.isEmpty()) continue
                                val sg = LongArray(ks.size * 4) { segs[4 * ks[it / 4] + it % 4] }
                                val ix = IntArray(ks.size) { lineIdx[ks[it]] }
                                for (chain in plymeld.geom.Linker.link(sg, ix, grown, limit, breakAtSplits = true)) {
                                    val m = chain.size / 2
                                    val a = lineAt[chain[0] to chain[1]] ?: ridge; val b = lineAt[chain[2 * m - 2] to chain[2 * m - 1]] ?: ridge
                                    // towards the ridge: ascending scanlines below it, descending above it
                                    val turn = if (low) a > b else a < b
                                    val xy = if (!turn) chain else LongArray(chain.size) { chain[(m - 1 - it / 2) * 2 + it % 2] }
                                    val start = if (turn) b else a
                                    // laid from the lowest start up; at one height, the start farther from the ridge first
                                    val key = (mean[start] ?: 0.0) - 1e-6 * Math.abs(start - ridge)
                                    paths.add(ToolPath(feature, xy, false, width, height, climbFrom = key))
                                }
                            }
                            continue
                        }
                    }
                    for (chain in plymeld.geom.Linker.link(segs, lineIdx, grown, limit, breakAtSplits = feature in monotonic)) {
                        // a sparse stub shorter than two line widths is a blob, not infill: it carries
                        // nothing and costs a travel (grid lines clipped at a region's corner)
                        if (feature == Feature.SPARSE && polylineLength(chain) < 2 * width) { droppedHere += polylineLength(chain) * width / 1e6; continue }
                        paths.add(ToolPath(feature, chain, false, width, height))
                    }
                }
            }
            val solidW = if (first) s.firstLayerWidthUm else s.solidWidthUm
            /** A solid surface in its pattern: line patterns hatch; concentric-outside-in, concentric-inside-out, and archimedeanchords per [SurfaceFill]. */
            var ringSeq = 0
            fun surface(region: List<Polygon>, feature: Feature, width: Long, spacing: Long, angles: List<Double>, pattern: String) {
                if (region.isEmpty()) return
                val grown = { Offset.offset(region, Math.round(s.infillWallOverlap * width)) }
                // archimedean chords are clipped on the region's edge like hatch lines: contact placement (see hatch)
                val clipped = { Offset.offset(region, Math.round(s.infillWallOverlap * width) - contactInset(width, h)) }
                when (pattern) {
                    in SurfaceFill.LINE_PATTERNS -> hatch(region, feature, width, spacing, angles)
                    // the ring patterns are laid as generated, ring after ring in one direction ([ToolPath.sequence])
                    // the rings joined into one path where they lie side by side (SurfaceFill.joined: one start, not one per ring);
                    // the first starts at the back
                    "concentric-outside-in" -> for (pc in SurfaceFill.joined(SurfaceFill.concentricCut(grown(), spacing, 2 * width)
                            .map { if (it.closed) SurfaceFill.Piece(seamAtBack(Polygon(it.xy)).xy, true) else it }, 2 * spacing))
                        paths.add(ToolPath(feature, pc.xy, pc.closed, width, h, sequence = ringSeq++))
                    "archimedeanchords" -> for (pl in SurfaceFill.archimedeanChords(clipped(), spacing, 2 * width)) paths.add(ToolPath(feature, pl, false, width, h))
                    // circles around the piece's middle, arcs where they do not fit (contact placement, as the chords; SurfaceFill.circles)
                    // a whole circle (its last point its first) joins the circles beside it, as the outside-in rings do
                    "concentric-inside-out" -> for (pc in SurfaceFill.joined(SurfaceFill.circles(clipped(), spacing, 2 * width).map { pl ->
                            val n = pl.size / 2
                            if (n > 3 && pl[0] == pl[2 * n - 2] && pl[1] == pl[2 * n - 1]) SurfaceFill.Piece(pl.copyOf(2 * n - 2), true) else SurfaceFill.Piece(pl, false) }, 2 * spacing))
                        paths.add(ToolPath(feature, pc.xy, if (pc.closed) false else pc.closed, width, h, sequence = ringSeq++).let { tp ->
                            if (pc.closed) ToolPath(feature, pc.xy + longArrayOf(pc.xy[0], pc.xy[1]), false, width, h, sequence = tp.sequence) else tp })
                    else -> error("${feature.label} pattern '$pattern' is not implemented yet (line patterns, concentric-outside-in, concentric-inside-out, archimedeanchords)")
                }
            }
            // Bridges: direction chosen PER connected region. One angle for the whole layer let the
            // largest region decide for all: a test model's layer laid 2.4 mm strips 4 deg off
            // their length, broken into short groups, 153 retractions in the layer vs QIDIStudio's 10.
            fun bridgeEach(region: List<Polygon>, below: List<Polygon>, bw: Long, h: Long, out: MutableList<ToolPath>, grow: Long = 0,
                           feature: Feature = Feature.BRIDGE,
                           families: List<Bridges.Family>? = null) {
                val sp = Walls.spacing(bw, h)
                for ((o, hs) in Polygons.group(region)) {
                    val piece = listOf(o) + hs
                    val angle = families?.let { Bridges.internalAngle(piece, below, it, sp, bw) } ?: Bridges.bestAngle(piece, below, sp, bw)
                    val hatchIn = if (grow != 0L) Offset.offset(piece, grow) else piece
                    val (segs, idx) = Hatch.indexed(hatchIn, angle, sp)
                    for (chain in plymeld.geom.Linker.link(segs, idx, hatchIn, 3 * sp)) out.add(ToolPath(feature, chain, false, bw, h, null))
                }
            }
            val usualAngle = spanAngle(i) ?: (s.infillAngleDeg + 90.0 * parity)
            val solidAngle = fillAngle[i] ?: usualAngle
            // the base angle of a grid's or cubic's sparse families: a bridge layer's span (fill_angles = "span"), else the object's
            val sparseBase = spanBase[i] ?: s.infillAngleDeg
            var internalSolid = r.internalSolid
            var sparseRegion = r.sparse
            // the sparse region a core group shares is laid by the group's last layer (the core, below)
            if (coreLast[i] >= 0) sparseRegion = Booleans.difference(sparseRegion, coreRegion.getValue(coreLast[i]))
            if (first && onBed != null) {
                // layers that follow the underside: the bottom over the hollow is in
                // the air, a bridge at the nozzle's width whose direction is chosen by where its lines land on the bed
                // (Bridges.bestAngle: anchored at both ends); its lines reach two widths onto the bed, as a bridge's reach into
                // the solid around it, and the bed's bottom gives that area up
                val q = s.nozzleDiameterUm / 2
                val air = Offset.offset(Offset.offset(Booleans.difference(r.bottomSurface, onBed), -q), q)  // drop hairline slivers
                val bw = s.nozzleDiameterUm
                val anchored = if (air.isEmpty()) emptyList() else Booleans.intersection(Offset.offset(air, 2 * bw), r.bottomSurface)
                surface(Booleans.difference(r.bottomSurface, anchored), Feature.BOTTOM, solidW, Walls.spacing(solidW, h), listOf(solidAngle), s.bottomPattern)
                if (anchored.isNotEmpty()) bridgeEach(anchored, onBed, bw, h, paths)
            } else if (first) {
                surface(r.bottomSurface, Feature.BOTTOM, solidW, Walls.spacing(solidW, h), listOf(solidAngle), s.bottomPattern)
            } else if (r.bottomSurface.isNotEmpty()) {
                // a planned insert: the bottom over its socket is laid onto the insert, a bottom surface, not a bridge
                val onInsert = coverAt[i]?.let { Booleans.intersection(r.bottomSurface, it) }.orEmpty()
                if (onInsert.isNotEmpty()) surface(onInsert, Feature.BOTTOM, solidW, Walls.spacing(solidW, h), listOf(solidAngle), s.bottomPattern)
                var overAir = if (onInsert.isEmpty()) r.bottomSurface else Booleans.difference(r.bottomSurface, onInsert)
                // the waves' area is theirs (above); only what they do not reach is bridged
                if (waveCovered.isNotEmpty() && overAir.isNotEmpty()) overAir = Booleans.difference(overAir, waveCovered)
                // Above the first layer, anything exposed from below spans air: a bridge. Lines
                // reach two widths into the surrounding solid/sparse to anchor; those regions give
                // the anchor area up so nothing is printed twice.
                if (overAir.isNotEmpty()) {
                    val bw = s.nozzleDiameterUm
                    val anchored = Booleans.intersection(Offset.offset(overAir, 2 * bw), r.inner).let { if (onInsert.isEmpty()) it else Booleans.difference(it, onInsert) }
                    internalSolid = Booleans.difference(internalSolid, anchored)
                    sparseRegion = Booleans.difference(sparseRegion, anchored)
                    bridgeEach(anchored, bridgeAnchors[i] ?: allPrinted[i - 1], bw, h, paths)
                }
            }
            if (!first) {
                // Internal bridge: solid laid directly over the layer below's SPARSE infill spans the
                // gaps between infill lines, so it is printed as a bridge (QIDIStudio does the same:
                // most of the card-holder base's 90 m of "Bridge" is this). Support for the angle
                // search = the layer below's material that is not sparse infill.
                val overSparse = Booleans.intersection(internalSolid, allSparse[i - 1])
                val q = s.nozzleDiameterUm / 2
                // Ring bands (<= MAX_CONCENTRIC_LINES wide, anchored by walls and shell) are NOT
                // bridged: they keep their concentric loops. Found on a test model's dome:
                // bridging them made 248 micro-runs where QIDIStudio prints plain shell loops.
                val ringLimit = MAX_CONCENTRIC_LINES * Walls.spacing(solidW, h)
                val wideOverSparse = Polygons.group(overSparse).filter { (o, hs) ->
                    val area = o.signedAreaMm2() + hs.sumOf { it.signedAreaMm2() }
                    val perim = o.perimeterMm() + hs.sumOf { it.perimeterMm() }
                    !(hs.isNotEmpty() && perim > 0 && 2 * area / perim * 1000 <= ringLimit)
                }.flatMap { (o, hs) -> listOf(o) + hs }
                val ib = Offset.offset(Offset.offset(wideOverSparse, -q), q)  // drop hairline slivers
                if (ib.isNotEmpty()) {
                    internalSolid = Booleans.difference(internalSolid, ib)
                    val bw = s.nozzleDiameterUm
                    val support = Booleans.difference(allPrinted[i - 1], allSparse[i - 1])
                    val across = whole?.sparseFamilies?.get(i - 1) ?: sparseFamilies(s, i - 1, layerTopsUm[i - 1], hs[i - 1], spanAngle(i - 1), spanBase[i - 1] ?: s.infillAngleDeg, sparseCenter)
                    bridgeEach(ib, support, bw, h, paths, grow = Math.round(s.infillWallOverlap * bw), feature = Feature.INTERNAL_BRIDGE, families = across)
                }
            }
            // internal solid is hidden: plain lines, the fastest fill
            hatch(internalSolid, Feature.SOLID, solidW, Walls.spacing(solidW, h), listOf(solidAngle))
            val sparseW = if (first) s.firstLayerWidthUm else s.sparseWidthUm
            // Sparse patterns. Each family of lines is spaced (families x bead spacing / density) so
            // the pattern holds the density whatever its family count.
            //  - rectilinear: one direction, turned 90 deg every layer
            //  - grid: two directions, 90 deg apart, on every layer
            //  - cubic: a lattice of cubes standing on a corner (body diagonal vertical), cut by the
            //    layer: three families 120 deg apart; each face plane crosses the layer on a line that
            //    moves z/sqrt(2) along its normal per unit of height, so the layers stack into tilted
            //    faces (plymeld's derivation from the geometry).
            //  - gyroid: curves, the gyroid surface's cross-section (Gyroid); no line of a layer crosses another; the
            //    "spacing" is its period, and the infill angle does not apply
            val (sparseFamilies, sparseAngles) = when (s.sparsePattern) {
                "rectilinear" -> 1 to listOf(solidAngle)
                "grid" -> 2 to listOf(sparseBase, sparseBase + 90)
                "cubic" -> 3 to List(3) { sparseBase + 120.0 * it }
                "gyroid" -> 1 to emptyList()
                else -> error("sparse infill pattern '${s.sparsePattern}' is not implemented yet (rectilinear, grid, cubic, gyroid)")
            }
            /** The pattern's spacing for beads [hh] high: line families' spacing, or the gyroid's period. */
            fun sparseStep(hh: Long): Long = if (s.sparsePattern == "gyroid") Gyroid.periodUm(Walls.spacing(sparseW, hh).toDouble(), s.sparseDensity)
                else Math.round(Walls.spacing(sparseW, hh) / s.sparseDensity * sparseFamilies)
            val sparseSpacing = sparseStep(h)
            // each family laid out from the object's center (patternCenter), cubic's also moving with height
            fun sparsePhasesAt(zUm: Long, spacing: Long): List<Double> =
                sparseAngles.map { a -> centered(a, spacing, sparseCenter, if (s.sparsePattern == "cubic") -zUm / Math.sqrt(2.0) else 0.0) }
            // A sparse pattern needs about one of its own cells to form; a piece narrower than that
            // only gets fragments (no fragmented toolpaths; Benchy hull sides: 577 sparse runs under 1 mm). Such a piece
            // gets ONE continuous loop half a line inside its edge: 2 line widths of plastic per mm,
            // which equals the pattern's own d x width exactly at one cell wide and is less than solid
            // below it. Too thin for even one loop: left as a gap between walls.
            // How narrow (an option): narrower than plan.layer.infill.narrow.cells cells (default 1).
            // (a gyroid's cell: half its period, the distance between its neighboring curves, as a family's line spacing)
            val narrowUm = Math.round((if (s.sparsePattern == "gyroid") sparseSpacing / 2.0 else sparseSpacing.toDouble()) * s.narrowCells)
            // Narrowness is judged LOCALLY: an opening by half that width keeps what is wide enough for
            // the pattern; only what it removes is narrow. Judging whole connected pieces instead
            // left the Benchy's bow (10 mm wide, joined to the thin strip around the deck) with
            // nothing but an edge loop at z 8.0-8.2 (found in a two-color render).
            val core = if (sparseRegion.isEmpty()) sparseRegion
                else Offset.offset(Offset.offset(sparseRegion, -narrowUm / 2, Offset.Join.MITER), narrowUm / 2, Offset.Join.MITER)
            val narrowParts = (if (core.isEmpty()) sparseRegion else Booleans.difference(sparseRegion, core))
                .let { Polygons.group(it) }.filter { (o, hs) -> o.signedAreaMm2() + hs.sumOf { it.signedAreaMm2() } > 0.01 }
            val wideRegion = if (narrowParts.isEmpty()) sparseRegion else Booleans.difference(sparseRegion, narrowParts.flatMap { (o, hs) -> listOf(o) + hs })
            val (narrowSparse, wideSparse) = Polygons.group(wideRegion).partition { (o, hs) ->
                val area = o.signedAreaMm2() + hs.sumOf { it.signedAreaMm2() }
                val perim = o.perimeterMm() + hs.sumOf { it.perimeterMm() }
                perim > 0 && 2 * area / perim * 1000 < narrowUm
            }.let { (n, w) -> (narrowParts + n) to w }
            // The loop runs ALONGSIDE the wall, so it sits as a neighboring wall does: at bead contact.
            // (infill_wall_overlap is for line ENDS, which touch a wall only with their cap; along a
            // whole loop it laid a double band the full length of a test model's walls: overfill 0 -> 105
            // mm2.) Half a line in (the first version) left a crack on both sides: Benchy cabin walls,
            // z 12, the whole length (a deposition check against the model). A region too thin for a
            // loop half a line in still gets none.
            val loopInset = contactInset(sparseW, h)
            // or, with plan.layer.infill.narrow.fill = solid (an option), solid like internal solid infill
            if (s.narrowFill == "solid") hatch(narrowSparse.flatMap { (o, hs) -> listOf(o) + hs }, Feature.SOLID, solidW, Walls.spacing(solidW, h), listOf(solidAngle))
            else for ((o, hs) in narrowSparse) if (Offset.offset(listOf(o) + hs, -sparseW / 2).isNotEmpty()) for (lp in Offset.offset(listOf(o) + hs, -loopInset))
                if (lp.size >= 3 && Math.abs(lp.signedAreaMm2()) > 0.01 && lp.perimeterMm() * 1000 >= 2 * sparseW) paths.add(ToolPath(Feature.SPARSE, seamAtBack(lp).xy, true, sparseW, h))
            hatch(wideSparse.flatMap { (o, hs) -> listOf(o) + hs }, Feature.SPARSE, sparseW, sparseSpacing, sparseAngles, phases = sparsePhasesAt(layerTopsUm[i], sparseSpacing))
            // the core: the group's shared sparse infill, the group's height thick, spaced for that bead
            if (coreLast[i] == i) {
                val hc = coreHeight.getValue(i)
                val coreSpacing = sparseStep(hc)
                hatch(coreRegion.getValue(i), Feature.SPARSE, sparseW, coreSpacing, sparseAngles, height = hc, phases = sparsePhasesAt(layerTopsUm[i], coreSpacing))
            }
            val topW = if (first) s.firstLayerWidthUm else s.topWidthUm
            // the top surface at its usual angle even on a bridge laid all along (lines across the bridge allowed on its
            // finishing layers)
            surface(r.topSurface, Feature.TOP, topW, Walls.spacing(topW, h), listOf(usualAngle), s.topPattern)
            // Ironing (ironing_type "top"): the layer's whole exposed top — this layer minus the next,
            // walls included — shrunk by half a line (+ ironing_inset) so the nozzle stays inside the
            // edges. Measured against QIDIStudio: area within 0.4 % on 7 of 10 ironed layers of
            // two test models. Other ironing types are not measured yet.
            if (!first && s.ironingType != "none") {
                require(s.ironingType == "top") { "ironing_type '${s.ironingType}' is not implemented yet (only 'top')" }
                val above = if (i + 1 < n) allEff[i + 1] else emptyList()
                val exposed = if (above.isEmpty()) effOutlines[i] else Booleans.difference(effOutlines[i], above)
                val region = if (exposed.isEmpty()) exposed else Offset.offset(exposed, -(s.nozzleDiameterUm / 2 + s.ironingInsetUm))
                if (region.isNotEmpty()) {
                    val fd = s.filamentDiameterMm
                    val ePerMm = s.ironingFlow * (s.ironingSpacingUm / 1000.0) * (h / 1000.0) / (Math.PI * fd * fd / 4) * s.flowRatio
                    val (segs, idx) = Hatch.indexed(region, s.ironingAngleDeg, s.ironingSpacingUm)
                    for (chain in plymeld.geom.Linker.link(segs, idx, region, 3 * s.ironingSpacingUm, breakAtSplits = true))
                        paths.add(ToolPath(Feature.IRONING, chain, false, s.nozzleDiameterUm, h, null, ePerMm))
                }
            }
            Built(paths, droppedHere)
            }
        }
        // the omitted areas' paths left out ([omit]), before the order is made (a path after one left out would be entered from
        // where that one ended)
        val built = if (omit == null) builtAll else builtAll.mapIndexed { i, b ->
            val areas = omit.getOrNull(i).orEmpty()
            if (areas.isEmpty()) b else {
                val groups = Polygons.group(areas)
                Built(b.paths.filter { p -> groups.none { (o, hs) -> Polygons.contains(o, p.xy[0], p.xy[1]) && hs.none { Polygons.contains(it, p.xy[0], p.xy[1]) } } }, b.dropped)
            }
        }
        droppedSliverMm2 = built.sumOf { it.dropped }
        for (i in 0 until n) {
            // the layer's print order from its built paths and where the head is (LayerCache): a layer under an unchanged one
            // starts where it did, so the whole stack below a change comes back as it was
            val kb = LayerCache.Builder("order").text(sk).text(i).text(hs[i]).text(layerTopsUm[i]).text(endX).text(endY).polys(effOutlines[i]).polys(asPrinted[i])
            for (p in built[i].paths) {
                kb.text(p.feature.ordinal).text(p.closed).text(p.widthUm).text(p.heightUm).text(p.ePerMmOverride).text(p.retractBefore).text(p.scarfUm).text(p.scarfStart).longs(p.xy)
                p.widths?.let { kb.longs(it) }; p.overhangShare?.let { kb.longs(LongArray(it.size) { k -> it[k].toLong() }) }
            }
            val (orderedPaths, ex, ey) = LayerCache.get(kb.build(), { t: Triple<List<ToolPath>, Long, Long> -> LayerCache.bytesOfPaths(t.first) }, s) {
            var lastX = endX; var lastY = endY
            val first = i == 0
            val h = hs[i]
            val paths = built[i].paths
            // Order. Brim and support first, across the layer. Then ISLAND BY ISLAND (each separate
            // piece of the layer's outline): the nearest unvisited island next, and inside it the
            // feature groups in sequence, each nearest-first from where the last path ended. Printing
            // feature by feature across all islands criss-crossed every layer (Benchy layer 60: 312 mm
            // of travel vs QIDIStudio's 174; test plate 1.74x).
            val first3 = listOf(Feature.BRIM, Feature.SUPPORT, Feature.SUPPORT_INTERFACE)
            // a bridge's first two layers laid as waves together (wave.layers = "2"): its beads last, after the fill over the
            // supports, so nothing low near a support is laid once the beads stand at the peak (the fill over the
            // feet came after them, the head 4.18 mm under the second layer's peak, over its 4 mm nozzle height)
            // (and chevron fronts, wave.front = "chevron": their last ring lies right over the peak, 4.000 mm over the strip on the
            // supports printed after it)
            // (and on request, plan.technique.bridge.wave.after_supported: the first lines attach to the strip already laid over
            // the supports, not to the supports' bare edge)
            val wavesLast = s.bridgeFirstLayerWave && (s.bridgeWaveLayers == 2 || s.bridgeWaveChevron || s.bridgeWaveAfterSupported) && bridgeAnchors.isNotEmpty() && i == bridgeAnchors.keys.min()
            val fills = listOf(Feature.WAVE, Feature.BOTTOM, Feature.BRIDGE, Feature.INTERNAL_BRIDGE, Feature.SOLID, Feature.SPARSE, Feature.TOP, Feature.IRONING)
            val inIsland = (if (s.innerWallsFirst) listOf(Feature.INNER_WALL, Feature.OUTER_WALL) else listOf(Feature.OUTER_WALL, Feature.INNER_WALL)) +
                (if (wavesLast) fills - Feature.WAVE + Feature.WAVE else fills)
            // A travel between two INTERNAL fill paths that stays inside the part — within the inner
            // edge of the outer wall — can only ooze where nothing is seen: no retraction, no lift
            // (Benchy: plymeld retracted 93 % of 13,832 travels, QIDIStudio 81 % of 10,227).
            val hidden = if (first) emptyList() else Offset.offset(effOutlines[i], -s.outerWallWidthUm)
            val hiddenGroups = Polygons.group(hidden)
            val edges = hidden.flatMap { p -> (0 until p.size).map { e -> val j = (e + 1) % p.size; plymeld.geom.IntPoint(p.x(e), p.y(e)) to plymeld.geom.IntPoint(p.x(j), p.y(j)) } }
            fun insideHidden(x: Long, y: Long) = hiddenGroups.any { (o, hs) -> Polygons.contains(o, x, y) && hs.none { Polygons.contains(it, x, y) } }
            fun staysHidden(x0: Long, y0: Long, x1: Long, y1: Long): Boolean {
                if (!insideHidden(x0, y0) || !insideHidden(x1, y1)) return false
                val p0 = plymeld.geom.IntPoint(x0, y0); val p1 = plymeld.geom.IntPoint(x1, y1)
                return edges.none { (c, d) -> plymeld.geom.Segments.intersects(p0, p1, c, d) }
            }
            val interior = setOf(Feature.SPARSE, Feature.SOLID)
            // Ordering fills: a travel that will need a retraction costs, on top of its length, the
            // distance travel covers in the time of the retract and the de-retract (from the settings;
            // Q2 preset: 500 mm/s x 2 x 0.8 mm / 30 mm/s = 26.7 mm). Plain nearest-first hopped across
            // holes (hole block: 16 sparse-to-sparse retractions vs QIDIStudio's 11).
            val retractCostUm = s.travelSpeed * (s.retractMm / s.retractSpeed + s.retractMm / s.deretractSpeed) * 1000
            val interiorExtra: ((Long, Long, Long, Long) -> Double)? =
                if (hidden.isEmpty()) null else { x0, y0, x1, y1 -> if (staysHidden(x0, y0, x1, y1)) 0.0 else retractCostUm }
            // Ordering walls (plan.mechanism.motion.no_retract_in_island): a travel between an island's wall runs that stays inside it and
            // crosses none of the layer's wall lines needs no retraction; one that would costs the same retraction-equivalent
            // distance as for fills, so the runs chain inside the island (nearest-first took a helix island's
            // center line, 1.08 mm away, before its inner loop, 1.21 mm away, and the travel back to the loop crossed the line)
            // unset (decided when sliced, RetractionDecision): the travels are marked as if on; the decision keeps or clears the marks
            val wallExtra: ((Long, Long, Long, Long) -> Double)? = if (first || s.noRetractInIsland == false) null else {
                val isl = Islands(effOutlines[i], paths)
                val f: (Long, Long, Long, Long) -> Double = { x0, y0, x1, y1 -> if (isl.clear(x0, y0, x1, y1) { true }) 0.0 else retractCostUm }
                f
            }
            val ordered = ArrayList<ToolPath>(paths.size)
            fun emit(g: List<ToolPath>) {
                if (g.isEmpty()) return
                // a waved bridge's beads in the order grown (one side from its anchor up to the middle, then the other): the
                // order is the technique (nearest-first laid the second side from the middle down)
                val o = if (s.bridgeFirstLayerWave && bridgeAnchors.isNotEmpty() && i == bridgeAnchors.keys.min() && g.all { it.feature == Feature.WAVE }) g
                    // a curved band's top laid uphill: its chains from the lowest start up, each as turned (low end first)
                    else if (g.all { !it.climbFrom.isNaN() }) g.sortedBy { it.climbFrom }
                    // a ring pattern's rings as generated, then the rest of the group ordered for travel from where they end
                    else if (g.any { it.sequence >= 0 }) {
                        val rings = g.filter { it.sequence >= 0 }.sortedBy { it.sequence }
                        val rest = g.filter { it.sequence < 0 }
                        if (rest.isEmpty()) rings else {
                            val t = rings.last(); val ex = if (t.closed) t.xy[0] else t.xy[t.xy.size - 2]; val ey = if (t.closed) t.xy[1] else t.xy[t.xy.size - 1]
                            rings + Ordering.order(rest, ex, ey)
                        }
                    }
                    else if (g[0].feature in monotonic) Ordering.sweep(g, lastX, lastY)
                    else Ordering.order(g, lastX, lastY, if (g.any { it.feature in interior }) interiorExtra
                        else if (g.all { it.feature == Feature.OUTER_WALL || it.feature == Feature.INNER_WALL }) wallExtra else null)
                ordered.addAll(o)
                val tail = o.last()
                if (tail.closed) { lastX = tail.xy[0]; lastY = tail.xy[1] } else { lastX = tail.xy[tail.xy.size - 2]; lastY = tail.xy[tail.xy.size - 1] }
            }
            for (f in first3) emit(paths.filter { it.feature == f })
            // islands: outers of the layer (smallest first, so a piece inside another's hole is found first)
            val islands = Polygons.group(asPrinted[i]).map { it.first }
            val boxes = islands.map { o -> LongArray(4).also { b -> b[0] = Long.MAX_VALUE; b[1] = Long.MAX_VALUE; b[2] = Long.MIN_VALUE; b[3] = Long.MIN_VALUE
                for (k in 0 until o.size) { b[0] = minOf(b[0], o.x(k)); b[1] = minOf(b[1], o.y(k)); b[2] = maxOf(b[2], o.x(k)); b[3] = maxOf(b[3], o.y(k)) } } }
            fun boxDist(b: LongArray, x: Long, y: Long) = Math.hypot(maxOf(b[0] - x, 0L, x - b[2]).toDouble(), maxOf(b[1] - y, 0L, y - b[3]).toDouble())
            val byIsland = HashMap<Int, MutableList<ToolPath>>()
            for (p in paths) {
                if (p.feature in first3 || p.afterLayer != null) continue
                val px = p.xy[0]; val py = p.xy[1]
                val k = islands.indices.firstOrNull { Polygons.contains(islands[it], px, py) }
                    ?: islands.indices.minByOrNull { boxDist(boxes[it], px, py) } ?: -1
                byIsland.getOrPut(k) { ArrayList() }.add(p)
            }
            // Walls CONTOUR BY CONTOUR: in an island with holes, each contour's walls (its outer wall
            // and the inner walls nearest it) are finished before the next contour's. Printing all
            // inner walls and then all outer walls crossed between contours twice per layer, each
            // time with a retraction (hole block: 2 per layer vs QIDIStudio's 1).
            // One contour's walls. With inner walls first, the inner walls must END near the outer
            // wall's seam, where the outer wall starts: nearest-first from the nozzle ignores that, and
            // each arachne center line (an open path, not a loop at the seam) left a travel back across
            // the contour (testplate: inner->inner + inner->outer travel 351 -> 2502 mm with arachne).
            // So the order is also built BACKWARDS from the seam (nearest-first, then
            // run in reverse), and the cheaper of the two is kept.
            // [specks]: the outer-wall specks lining this contour's outer wall (ToolPath.speck), laid right after it in the order
            // they lie along it from its seam, the way it printed, each entered from its nearer end (as
            // contours of their own, they were reached whenever nearest the nozzle, and on an engraved name's base 391 of 414
            // travels across its front face started or ended at one; laid nearest-first instead, the hops between neighboring
            // specks cut across the notches between them, outside the part)
            fun emitSpecks(outer: List<ToolPath>, specks: List<ToolPath>) {
                if (specks.isEmpty()) return
                val wall = outer.singleOrNull() ?: run { emit(specks); return }
                for (sp in specks.sortedBy { alongPath(wall, it.xy[0], it.xy[1]) }) {
                    val n = sp.xy.size
                    val fromEnd = !sp.closed && Math.hypot((sp.xy[n - 2] - lastX).toDouble(), (sp.xy[n - 1] - lastY).toDouble()) <
                        Math.hypot((sp.xy[0] - lastX).toDouble(), (sp.xy[1] - lastY).toDouble())
                    val q = if (fromEnd) sp.reversed() else sp
                    ordered.add(q)
                    if (q.closed) { lastX = q.xy[0]; lastY = q.xy[1] } else { lastX = q.xy[q.xy.size - 2]; lastY = q.xy[q.xy.size - 1] }
                }
            }
            fun emitContour(inner: List<ToolPath>, outer: List<ToolPath>, specks: List<ToolPath> = emptyList()) {
                if (!s.innerWallsFirst || outer.size != 1 || inner.size < 1 || inner.any { !it.closed && it.overhangShare != null }) {
                    for (f in inIsland.take(2)) { emit(if (f == Feature.OUTER_WALL) outer else inner); if (f == Feature.OUTER_WALL) emitSpecks(outer, specks) }
                    return
                }
                val sx = outer[0].xy[0]; val sy = outer[0].xy[1]
                fun endX(p: ToolPath) = if (p.closed) p.xy[0] else p.xy[p.xy.size - 2]
                fun endY(p: ToolPath) = if (p.closed) p.xy[1] else p.xy[p.xy.size - 1]
                fun cost(seq: List<ToolPath>): Double {
                    var x = lastX; var y = lastY; var t = 0.0
                    for (p in seq) { t += Math.hypot((p.xy[0] - x).toDouble(), (p.xy[1] - y).toDouble()); x = endX(p); y = endY(p) }
                    return t + Math.hypot((sx - x).toDouble(), (sy - y).toDouble())
                }
                val forward = Ordering.order(inner, lastX, lastY, wallExtra)
                val backward = Ordering.order(inner, sx, sy, wallExtra).asReversed().map { if (it.closed) it else it.reversed() }
                val seq = if (cost(backward) < cost(forward)) backward else forward
                ordered.addAll(seq)
                lastX = endX(seq.last()); lastY = endY(seq.last())
                emit(outer)
                emitSpecks(outer, specks)
            }
            fun emitWalls(ws: List<ToolPath>) {
                val allOuters = ws.filter { it.feature == Feature.OUTER_WALL }
                // a speck is not a contour of its own: it goes with the outer wall it lines (the nearest one that is not a speck),
                // and inner walls are grouped by the real outer walls only; an island of specks alone keeps them as its outers
                val outers = allOuters.filter { !it.speck }.ifEmpty { allOuters }
                val specks = allOuters.filter { it.speck && it !in outers }
                if (outers.size <= 1) { emitContour(ws.filter { it.feature == Feature.INNER_WALL }, outers, specks); return }
                val family = List(outers.size) { arrayListOf(outers[it]) }
                for (p in ws) if (p.feature == Feature.INNER_WALL || p in specks)
                    family[outers.indices.minBy { distToPath(outers[it], p.xy[0], p.xy[1]) }].add(p)
                val left = outers.indices.toMutableSet()
                while (left.isNotEmpty()) {
                    val f = left.minWith(compareBy<Int>({ family[it].minOf { p -> Math.hypot((p.xy[0] - lastX).toDouble(), (p.xy[1] - lastY).toDouble()) } }, { it }))
                    left.remove(f)
                    emitContour(family[f].filter { it.feature == Feature.INNER_WALL }, family[f].filter { it.feature == Feature.OUTER_WALL && it !in specks },
                        family[f].filter { it in specks })
                }
            }
            val todo = byIsland.keys.toMutableSet()
            while (todo.isNotEmpty()) {
                val k = todo.minWith(compareBy<Int>({ if (it < 0) 0.0 else boxDist(boxes[it], lastX, lastY) }, { it }))
                todo.remove(k)
                val mine = byIsland.getValue(k)
                emitWalls(mine.filter { it.feature == Feature.OUTER_WALL || it.feature == Feature.INNER_WALL })
                // Fills: the monotonic surfaces are sweeps of their own; bridges, internal solid
                // and sparse infill are ONE nearest-first group. Feature by feature, each group started
                // wherever the last one ended and crossed the island to reach its nearest path
                // (testplate: outer wall -> bridge travel 920 mm vs QIDIStudio's 36, which chains its
                // fills by proximity whatever their kind).
                for (f in inIsland.drop(2)) when (f) {
                    Feature.BRIDGE, Feature.INTERNAL_BRIDGE, Feature.SPARSE -> {}
                    Feature.SOLID -> emit(mine.filter { it.feature in MIXED_FILLS })
                    else -> emit(mine.filter { it.feature == f })
                }
            }
            // a waved bridge's lines over its first layer (AfterLayer: second pass, Xs, span layer): after the whole layer, in their own order
            emit(paths.filter { it.afterLayer != null })
            // Mark hidden travels (see `hidden` above)
            if (hidden.isNotEmpty()) {
                // and it must not cross any outer wall actually planned on this layer (in thin features
                // the walls do not sit where the shrunk outline assumes: Benchy layer 160)
                class Loop(val pts: List<plymeld.geom.IntPoint>, val minX: Long, val minY: Long, val maxX: Long, val maxY: Long)
                val outerLoops = ordered.filter { it.feature == Feature.OUTER_WALL }.map { w ->
                    val pts = (0 until w.xy.size / 2).map { plymeld.geom.IntPoint(w.xy[2 * it], w.xy[2 * it + 1]) } + (if (w.closed) listOf(plymeld.geom.IntPoint(w.xy[0], w.xy[1])) else emptyList())
                    Loop(pts, pts.minOf { it.x }, pts.minOf { it.y }, pts.maxOf { it.x }, pts.maxOf { it.y })
                }
                fun crossesOuterWall(p0: plymeld.geom.IntPoint, p1: plymeld.geom.IntPoint): Boolean = outerLoops.any { l ->
                    maxOf(p0.x, p1.x) >= l.minX && minOf(p0.x, p1.x) <= l.maxX && maxOf(p0.y, p1.y) >= l.minY && minOf(p0.y, p1.y) <= l.maxY &&
                        (0 until l.pts.size - 1).any { e -> plymeld.geom.Segments.intersects(p0, p1, l.pts[e], l.pts[e + 1]) }
                }
                // Inner wall -> outer wall inside the part: the step from one seam onto the next. Its
                // ooze lands at the outer wall's own seam. QIDIStudio does not retract here (a test model with
                // fuzzy skin: 1 of 86 retracted; plymeld retracted every one over 1 mm, 26).
                val outlineGroups = Polygons.group(effOutlines[i])
                val outlineEdges = effOutlines[i].flatMap { p -> (0 until p.size).map { e -> val j = (e + 1) % p.size; plymeld.geom.IntPoint(p.x(e), p.y(e)) to plymeld.geom.IntPoint(p.x(j), p.y(j)) } }
                fun insideOutline(x: Long, y: Long) = outlineGroups.any { (o, hs) -> Polygons.contains(o, x, y) && hs.none { Polygons.contains(it, x, y) } }
                fun ontoOuterWallInside(p0: plymeld.geom.IntPoint, p1: plymeld.geom.IntPoint): Boolean =
                    insideOutline(p0.x, p0.y) && insideOutline(p1.x, p1.y) &&
                        outlineEdges.none { (c, d) -> plymeld.geom.Segments.properCrossing(p0, p1, c, d) } &&
                        outerLoops.none { l -> (0 until l.pts.size - 1).any { e -> plymeld.geom.Segments.properCrossing(p0, p1, l.pts[e], l.pts[e + 1]) } }
                for (k in 1 until ordered.size) {
                    val a = ordered[k - 1]; val b = ordered[k]
                    val seamStep = a.feature == Feature.INNER_WALL && b.feature == Feature.OUTER_WALL
                    if (!seamStep && (a.feature !in interior || b.feature !in interior)) continue
                    val ax = if (a.closed) a.xy[0] else a.xy[a.xy.size - 2]; val ay = if (a.closed) a.xy[1] else a.xy[a.xy.size - 1]
                    val bx = b.xy[0]; val by = b.xy[1]
                    val p0 = plymeld.geom.IntPoint(ax, ay); val p1 = plymeld.geom.IntPoint(bx, by)
                    if (seamStep) { if (!ontoOuterWallInside(p0, p1)) continue }
                    else if (!staysHidden(ax, ay, bx, by) || crossesOuterWall(p0, p1)) continue
                    ordered[k] = b.withRetract(false)
                }
            }
            if (!first) { if (s.noRetractInIsland != false) keepInIsland(ordered, effOutlines[i]) else keepInIsland(ordered, effOutlines[i]) { it.speck } }
            Triple(ordered.toList(), lastX, lastY)
            }
            endX = ex; endY = ey
            out.add(LayerPlanOut(i, layerTopsUm[i], hs[i], orderedPaths))
        }
        return out
    }

    /** Paths that belong to no island of the layer: a travel to or from them leaves the island. */
    private val OUTSIDE_ISLANDS = setOf(Feature.BRIM, Feature.SUPPORT, Feature.SUPPORT_INTERFACE, Feature.TOWER)
    /** Surfaces seen from above: ooze from a travel leaving or reaching them would show (plymeld's choice). */
    private val SEEN_FROM_ABOVE = setOf(Feature.TOP, Feature.IRONING)

    /**
     * plan.mechanism.motion.no_retract_in_island: a travel that stays inside the island being printed needs no
     * retraction and no lift. A helix island (7.9 x 2 mm: an outer loop, an inner loop, and a center line) retracted, lifted
     * 0.2 mm and restored before each of its three runs, for 1-2 mm moves that crossed nothing; the print showed a row of
     * beads where they started. [ordered]: one layer's paths in print order; [outline]: the layer's outline (its islands
     * are its outer contours with their holes). A travel keeps its retraction when it: starts or ends outside an island,
     * or in two different ones; crosses the island's edge (a concave island, a hole); crosses a wall line already laid
     * on this layer, or an outer wall of the layer laid or not (the promise of every unretracted travel); or
     * leaves or reaches a top surface or ironing ([SEEN_FROM_ABOVE]). [only]: the paths it may clear (all of them where the
     * switch is on or unset; only outer-wall specks where it is off).
     *
     * An outer-wall speck ([ToolPath.speck]) is entered by such a travel without a retraction WHATEVER the switch: a restart
     * for a 0.2 mm dot left a blob on a show face. So a cleared speck is not marked
     * [ToolPath.islandHidden]: the in-island decision's "off" side (IncrementalSlicer.decideRetraction) restores the
     * retraction of the marked paths only, and leaves the speck as it is. A real speck lies past its wall's loop, so the travel
     * to it crosses that wall and is rarely clear: reached from its own outer wall in its island, it is lifted over the wall
     * but not retracted ([ToolPath.hopOnly]; without the lift only 0 µm point-specks would be clear).
     * Elsewhere a speck retracts as any path (entered unretracted everywhere, travels crossed outer walls at
     * print height and the pre-print check refused them).
     */
    fun keepInIsland(ordered: MutableList<ToolPath>, outline: List<Polygon>, only: (ToolPath) -> Boolean = { true }) {
        if (ordered.size < 2 || outline.isEmpty()) return
        val isl = Islands(outline, ordered)
        for (k in 1 until ordered.size) {
            val b = ordered[k]
            if (!b.retractBefore || !only(b)) continue
            val a = ordered[k - 1]
            if (a.feature in OUTSIDE_ISLANDS || b.feature in OUTSIDE_ISLANDS || a.feature in SEEN_FROM_ABOVE || b.feature in SEEN_FROM_ABOVE) continue
            // where [a] ends: a loop at its seam, a scarfed loop at its ramp's end (a ring's scarfed outer wall
            // ends 10 mm past its seam, and the travel from there crossed it); a scarfed loop printed whole (a curved or
            // lowered layer) at its seam, so both must qualify
            val ends = listOf(Scarf.endOf(a), if (a.closed) longArrayOf(a.xy[0], a.xy[1]) else longArrayOf(a.xy[a.xy.size - 2], a.xy[a.xy.size - 1]))
            if (ends.all { e -> isl.clear(e[0], e[1], b.xy[0], b.xy[1]) { it.k < k || it.outer } }) ordered[k] = b.withRetract(false, island = !b.speck)
            // a speck reached from its own outer wall (or another speck of it: laid right after it) in the same island, its travel
            // crossing a wall: its last hop crosses that wall, as every route to it does (a speck lies past its wall's loop), so it
            // is lifted over it, but not retracted (the rule above served only 0 µm points)
            else if (b.speck && a.feature == Feature.OUTER_WALL && ends.all { e -> isl.sameIsland(e[0], e[1], b.xy[0], b.xy[1]) }) ordered[k] = b.withHop()
        }
    }

    /**
     * One layer's islands (its outline's outer contours with their holes) and wall lines ([paths]' outer and inner walls,
     * each with its index in [paths]), for travels that stay inside an island (plan.mechanism.motion.no_retract_in_island).
     */
    class Islands(outline: List<Polygon>, paths: List<ToolPath>) {
        /** A polyline (closed: back to its start) with its bounding box; [k]: its path's index, -1 for an island edge. */
        class Line(val k: Int, val outer: Boolean, xy: LongArray, closed: Boolean, /** half the line's width (µm): the bead the nozzle must not pass over; 0 for an island edge */ val halfWidthUm: Long = 0) {
            val pts = (0 until xy.size / 2).map { plymeld.geom.IntPoint(xy[2 * it], xy[2 * it + 1]) } + (if (closed) listOf(plymeld.geom.IntPoint(xy[0], xy[1])) else emptyList())
            val minX = pts.minOf { it.x }; val minY = pts.minOf { it.y }; val maxX = pts.maxOf { it.x }; val maxY = pts.maxOf { it.y }
            /**
             * Whether the travel p0 -> p1, less its first and last [LEAVE_UM] (leaving a line and reaching one is not passing over
             * it), comes within half the line's width of this line anywhere: the nozzle passing over the bead at print height
             * (a 12 mm travel from a hole's outer wall to its inner wall ran 0.02 mm off the wall it had just laid,
             * no proper crossing in the planner's coordinates, a crossing once written to a micron, and the check refused it).
             */
            fun grazedBy(p0: plymeld.geom.IntPoint, p1: plymeld.geom.IntPoint): Boolean {
                if (halfWidthUm <= 0) return false
                val dx = (p1.x - p0.x).toDouble(); val dy = (p1.y - p0.y).toDouble(); val len = Math.hypot(dx, dy)
                if (len <= 2 * LEAVE_UM) return false
                val ux = dx / len; val uy = dy / len
                val ax = p0.x + ux * LEAVE_UM; val ay = p0.y + uy * LEAVE_UM; val bx = p1.x - ux * LEAVE_UM; val by = p1.y - uy * LEAVE_UM
                val r = halfWidthUm.toDouble()
                if (maxOf(ax, bx) + r < minX || minOf(ax, bx) - r > maxX || maxOf(ay, by) + r < minY || minOf(ay, by) - r > maxY) return false
                fun d(px: Double, py: Double, qx: Double, qy: Double, x: Double, y: Double): Double {
                    val vx = qx - px; val vy = qy - py; val l2 = vx * vx + vy * vy
                    val t = if (l2 == 0.0) 0.0 else (((x - px) * vx + (y - py) * vy) / l2).coerceIn(0.0, 1.0)
                    return Math.hypot(x - px - t * vx, y - py - t * vy)
                }
                for (e in 0 until pts.size - 1) {
                    val sx = pts[e].x.toDouble(); val sy = pts[e].y.toDouble(); val tx = pts[e + 1].x.toDouble(); val ty = pts[e + 1].y.toDouble()
                    if (minOf(d(ax, ay, bx, by, sx, sy), d(ax, ay, bx, by, tx, ty), d(sx, sy, tx, ty, ax, ay), d(sx, sy, tx, ty, bx, by)) < r) return true
                }
                return false
            }
            /** Whether p0 -> p1 crosses this line at a point inside both (touching an end or a vertex is not crossing). */
            fun crossedBy(p0: plymeld.geom.IntPoint, p1: plymeld.geom.IntPoint) =
                maxOf(p0.x, p1.x) >= minX && minOf(p0.x, p1.x) <= maxX && maxOf(p0.y, p1.y) >= minY && minOf(p0.y, p1.y) <= maxY &&
                    (0 until pts.size - 1).any { e -> plymeld.geom.Segments.properCrossing(p0, p1, pts[e], pts[e + 1]) }
        }
        private val groups = Polygons.group(outline)
        private val edges = groups.map { (o, hs) -> (listOf(o) + hs).map { Line(-1, false, it.xy, true) } }
        val walls = paths.withIndex().filter { (_, p) -> p.feature == Feature.OUTER_WALL || p.feature == Feature.INNER_WALL }
            .map { (k, p) -> Line(k, p.feature == Feature.OUTER_WALL, p.xy, p.closed, p.widthUm / 2) }
        private fun groupOf(x: Long, y: Long) = groups.indexOfFirst { (o, hs) -> Polygons.contains(o, x, y) && hs.none { Polygons.contains(it, x, y) } }
        /** Whether (x0, y0) and (x1, y1) lie in one island. */
        fun sameIsland(x0: Long, y0: Long, x1: Long, y1: Long): Boolean = groupOf(x1, y1).let { g -> g >= 0 && groupOf(x0, y0) == g }

        /** Whether the straight travel (x0, y0) -> (x1, y1) stays inside one island, crossing none of its edges and none of the [walls] that [counts]. */
        fun clear(x0: Long, y0: Long, x1: Long, y1: Long, counts: (Line) -> Boolean): Boolean {
            val g = groupOf(x1, y1)
            if (g < 0 || groupOf(x0, y0) != g) return false
            val p0 = plymeld.geom.IntPoint(x0, y0); val p1 = plymeld.geom.IntPoint(x1, y1)
            return edges[g].none { it.crossedBy(p0, p1) } && walls.none { counts(it) && (it.crossedBy(p0, p1) || it.grazedBy(p0, p1)) }
        }
        companion object {
            /** The travel's first and last stretch that leaving a line and reaching one take (µm): what the pre-print check allows too (GcodeCheck). */
            const val LEAVE_UM = 750L
        }
    }

    /** Shortest distance (µm) from (x, y) to a path's polyline (closing edge included when closed). */
    fun distToPath(p: ToolPath, x: Long, y: Long): Double {
        val xy = p.xy; val m = xy.size / 2
        var best = Double.POSITIVE_INFINITY
        val last = if (p.closed) m else m - 1
        for (k in 0 until last) {
            val j = (k + 1) % m
            val ax = xy[2 * k].toDouble(); val ay = xy[2 * k + 1].toDouble(); val vx = xy[2 * j] - ax; val vy = xy[2 * j + 1] - ay
            val l2 = vx * vx + vy * vy
            val t = if (l2 == 0.0) 0.0 else (((x - ax) * vx + (y - ay) * vy) / l2).coerceIn(0.0, 1.0)
            best = minOf(best, Math.hypot(x - ax - t * vx, y - ay - t * vy))
        }
        return best
    }

    /** How far along a path (µm from its first point, in print order; closing edge included when closed) the point of it nearest (x, y) lies. */
    fun alongPath(p: ToolPath, x: Long, y: Long): Double {
        val xy = p.xy; val m = xy.size / 2
        var best = Double.POSITIVE_INFINITY; var at = 0.0; var run = 0.0
        val last = if (p.closed) m else m - 1
        for (k in 0 until last) {
            val j = (k + 1) % m
            val ax = xy[2 * k].toDouble(); val ay = xy[2 * k + 1].toDouble(); val vx = xy[2 * j] - ax; val vy = xy[2 * j + 1] - ay
            val l2 = vx * vx + vy * vy
            val t = if (l2 == 0.0) 0.0 else (((x - ax) * vx + (y - ay) * vy) / l2).coerceIn(0.0, 1.0)
            val d = Math.hypot(x - ax - t * vx, y - ay - t * vy)
            if (d < best) { best = d; at = run + t * Math.sqrt(l2) }
            run += Math.sqrt(l2)
        }
        return at
    }

    /** Each layer's own height, from consecutive tops (tops are measured from the bed). */
    /** Length (µm) of an open polyline (flat x y ...). */
    fun polylineLength(xy: LongArray): Double { var l = 0.0; for (k in 1 until xy.size / 2) l += Math.hypot((xy[2 * k] - xy[2 * k - 2]).toDouble(), (xy[2 * k + 1] - xy[2 * k - 1]).toDouble()); return l }

    fun heights(layerTopsUm: LongArray): LongArray = LongArray(layerTopsUm.size) { if (it == 0) layerTopsUm[0] else layerTopsUm[it] - layerTopsUm[it - 1] }

    /** Rotates a loop so it starts at its back-most vertex (max y; ties by min x). */
    fun seamAtBack(p: Polygon): Polygon {
        var best = 0
        for (i in 1 until p.size) if (p.y(i) > p.y(best) || (p.y(i) == p.y(best) && p.x(i) < p.x(best))) best = i
        if (best == 0) return p
        val xy = LongArray(p.xy.size)
        for (i in 0 until p.size) { val k = (best + i) % p.size; xy[2 * i] = p.x(k); xy[2 * i + 1] = p.y(k) }
        return Polygon(xy)
    }
}
