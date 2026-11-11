package plymeld.core

import plymeld.program.Progress
import plymeld.gcode.KlipperWriter
import plymeld.mesh.Layer
import plymeld.mesh.LayerPlan
import plymeld.mesh.Slicer
import plymeld.mesh.TriangleSoup
import plymeld.model.Resolver
import plymeld.model.Setup
import plymeld.model.pressureAdvanceByTool
import plymeld.model.SliceSettings
import plymeld.program.Extrude
import plymeld.program.LayerStart
import plymeld.program.LiftTo
import plymeld.program.ObjectEnd
import plymeld.program.ObjectStart
import plymeld.program.PrintObject
import plymeld.program.PrintProgram
import plymeld.program.Step
import java.io.Writer

/**
 * Incremental re-slicing: a settings change re-computes only what it affects. The pipeline is a chain of
 * cached [Stage]s, each keyed on the resolved values of the plymeld settings it READS
 * plus the key of the stage before it:
 *
 *   slice   <- mesh, layer heights, Z anti-aliasing (a flat layer reaching out over a slope)
 *   walls   <- slice + elephant foot, wall loops, wall widths
 *   regions <- walls + shell layers, vertical shells
 *   paths   <- regions + infill / bridge / brim / seam settings
 *   g-code  <- always (speeds, temperatures, templates; cheap)
 *
 * A setting read by a stage but missing from its key would serve stale results; the test
 * that guards this compares incremental output with a from-scratch slice, byte for byte.
 */
class IncrementalSlicer(private val parts: List<TriangleSoup>, private val version: String, private val objectName: String = "model", private val objectId: Int = 0,
                        /** How the object is built, by height band (decide.Plan): flat layers of one height, flat layers following the surfaces, or both stacked; curved, underside, or lifted. */
                        private val technique: plymeld.core.decide.TechniquePlan = plymeld.core.decide.TechniquePlan.whole(plymeld.core.decide.PrintTechnique.FLAT_LAYERS)) {
    constructor(soup: TriangleSoup, version: String, objectName: String = "model") : this(listOf(soup), version, objectName)
    init {
        technique.techniques.firstOrNull { it != plymeld.core.decide.PrintTechnique.FLAT_LAYERS &&
            it != plymeld.core.decide.PrintTechnique.CURVED_LAYERS && it != plymeld.core.decide.PrintTechnique.CURVED_UNDERSIDE &&
            it != plymeld.core.decide.PrintTechnique.LIFTED_BRIDGE }?.let { error("'$objectName': technique '${it.label}' has no planner yet") }
        require(plymeld.core.decide.PrintTechnique.CURVED_UNDERSIDE !in technique.techniques || technique.bands.size == 1) {
            "'$objectName': layers that follow the underside are the whole object, one band" }
        require(plymeld.core.decide.PrintTechnique.LIFTED_BRIDGE !in technique.techniques || technique.bands.size == 1) {
            "'$objectName': a lifted bridge is the whole object, one band" }
        val curvedBands = technique.bands.filter { it.technique == plymeld.core.decide.PrintTechnique.CURVED_LAYERS }
        require(curvedBands.isEmpty() || (curvedBands.size == 1 && curvedBands[0] === technique.bands.last())) {
            "'$objectName': curved layers are the top band, one band" }
        require(curvedBands.isEmpty() || !technique.follows) {
            "'$objectName': curved layers with flat layers following the surfaces below them are not planned yet" }
        require(technique.islands.isEmpty() || technique.techniques == listOf(plymeld.core.decide.PrintTechnique.FLAT_LAYERS)) {
            "'$objectName': curved islands sit on flat layers; with a curved band, layers that follow the underside or a lifted bridge they are not planned yet" }
    }
    /**
     * Layer heights vary (bands following the surfaces, or bands of one height evened out to their boundaries, or to a curved
     * island's top the flat layers land on: [landingsMm]): shells are counted by thickness.
     */
    private val adaptive = technique.follows ||
        (technique.single == null && plymeld.core.decide.PrintTechnique.CURVED_LAYERS !in technique.techniques) || technique.islands.any { it.shared }
    /** The curved band: from its start (mm above the object's bottom) to the top, or null. */
    private val curvedFrom: Double? = technique.bands.lastOrNull { it.technique == plymeld.core.decide.PrintTechnique.CURVED_LAYERS }?.lo
    /** Layers that follow the underside, the whole object. */
    private val underside = technique.single == plymeld.core.decide.PrintTechnique.CURVED_UNDERSIDE
    /** A lifted bridge, the whole object: its parts that do not reach the bed are the bridge, the rest hold it up. */
    private val lifted = technique.single == plymeld.core.decide.PrintTechnique.LIFTED_BRIDGE
    /** For a lifted bridge, which parts are the bridge (their lowest point above the object's bottom). */
    private val bridgePart: BooleanArray by lazy {
        val z0 = soup.bounds()[2]
        BooleanArray(parts.size) { parts[it].bounds()[2] > z0 + LIFT_PART_MM }.also {
            if (lifted) require(it.any { b -> b } && it.any { b -> !b }) {
                "'$objectName': a lifted bridge needs a part that stands on the bed and a part that does not (the bridge): parts' lowest points ${parts.map { p -> p.bounds()[2] }}" }
        }
    }
    /**
     * A lifted bridge's bridges: its bridge parts grouped where their heights overlap (a tube with an arched
     * strip every 5 mm up, the wall carrying on above each). One group is one bridge laid on what holds it up. With several,
     * the object is [banded]: what stands on the bed is sliced flat, and each bridge, lifted along its own underside, is
     * printed straight after the layer of it that the bridge rests on ([bandsStage]).
     */
    private val bridgeGroups: List<IntArray> by lazy {
        if (!lifted) emptyList() else {
            val out = ArrayList<MutableList<Int>>(); var hi = Double.NEGATIVE_INFINITY
            for (i in parts.indices.filter { bridgePart[it] }.sortedBy { parts[it].bounds()[2] }) {
                val b = parts[i].bounds()
                if (out.isEmpty() || b[2] > hi) { out += mutableListOf(i); hi = b[5] } else { out.last() += i; hi = maxOf(hi, b[5]) }
            }
            out.map { it.toIntArray() }
        }
    }
    /** Several lifted bridges at different heights in this object ([bridgeGroups]). */
    val banded: Boolean get() = bridgeGroups.size > 1
    /**
     * Curved islands (TechniquePlan.islands): each cap's island, above
     * its start, printed in its own layers with the shifted warp ([islandsStage]), its loops taken out of the flat layers there.
     */
    private val islands: List<plymeld.core.decide.CurvedIsland> by lazy { soup.bounds().let { b -> technique.islands.map { it.placed(b[0], b[1]) } } }
    /** For curved islands: which island each of [plannedLayers]' island layers is (none for the flat ones). */
    private val islandOf = java.util.IdentityHashMap<LayerPlanOut, Int>()
    /** Each of [bridgeGroups]' lift, against everything that stands on the bed (cached per layer grid). */
    private fun bandWarps(st: SliceSettings): List<plymeld.mesh.LiftWarp> {
        val hold = zaaHold(st)
        val key = "${st.firstLayerHeightUm}/${st.layerHeightUm}/$hold"
        bandWarpCache?.let { (k, w) -> if (k == key) return w }
        val sup = merged(parts.filterIndexed { i, _ -> !bridgePart[i] })
        return bridgeGroups.map { g -> plymeld.mesh.LiftWarp.of(merged(g.map { parts[it] }), sup, soup.bounds()[2], st.firstLayerHeightUm / 1000.0, st.layerHeightUm / 1000.0, UP_NZ, hold) }
            .also { bandWarpCache = key to it }
    }

    /** The tool's thinnest layer (mm): where flat layers of one height land on the top, and the thinnest a line Z anti-aliasing lowers may be. */
    private fun minLayerMm(): Double = (Resolver.resolveOrNull(setup!!, "hardware.tool.layer.height.min", ctx)?.value as Number?)?.toDouble()
        ?: error("layers need hardware.tool.<key>.layer.height.min (flat layers of one height land their last layer on the top within it; flat layers following the surfaces stay above it)")

    /** With Z anti-aliasing, how far above its bottom a layer holds a support's top (a lifted bridge's rest): the thinnest layer; else null, the middle. */
    private fun zaaHold(st: SliceSettings): Double? = if (st.zAntialias) minLayerMm() else null

    /**
     * The model's solid by columns, over all its parts as placed (Z anti-aliasing: the top a line is lowered onto). Runs closer
     * than the slicing resolution are one; for a lifted bridge, closer than where it counts as resting on a support
     * (LiftWarp.CONTACT_MM), so a support is never lowered under the bridge resting on it.
     */
    private fun columns(st: SliceSettings): plymeld.mesh.SolidColumns {
        val join = if (lifted) maxOf(st.resolutionUm / 1000.0, plymeld.mesh.LiftWarp.CONTACT_MM) else st.resolutionUm / 1000.0
        columnsCache?.let { (j, c) -> if (j == join) return c }
        return plymeld.mesh.SolidColumns(parts, join).also { columnsCache = join to it }
    }
    private var columnsCache: Pair<Double, plymeld.mesh.SolidColumns>? = null

    /**
     * Z anti-aliasing (ZAntiAlias): each flat layer above the first ([flat], by its absolute top) also cut where the top is the
     * tool's thinnest layer above its bottom, when that is below the layer's middle, and the two cuts joined, so the layer
     * reaches out over a slope to where its lines can be lowered to. A cut lower than the middle is smaller where the model
     * grows upward (an overhang), so only a top that ends within the layer adds anything.
     */
    private fun zaaExtend(layers: List<Layer>, sliced: List<TriangleSoup>, plan: LayerPlan, flat: (Double) -> Boolean): List<Layer> {
        val min = minLayerMm()
        val idx = (1 until plan.count).filter { i -> flat(plan.tops[i]) && plan.tops[i - 1] + min < plan.slices[i] - 1e-9 }
        if (idx.isEmpty()) return layers
        val hs = DoubleArray(idx.size) { plan.tops[idx[it] - 1] + min }
        val cuts = sliced.map { Slicer.slice(it, hs) }
        val out = layers.toMutableList()
        val joined = Parallel.map(idx.size) { k ->
            val extra = cuts.flatMap { it[k].loops }
            if (extra.isEmpty()) layers[idx[k]].loops else plymeld.geom.Booleans.union(layers[idx[k]].loops, extra)
        }
        for ((k, i) in idx.withIndex()) out[i] = Layer(layers[i].z, joined[k], layers[i].openChains)
        return out
    }
    private var bandWarpCache: Pair<String, List<plymeld.mesh.LiftWarp>>? = null

    /** Which of the setup's objects this is: its settings are resolved for it. */
    private val ctx = plymeld.model.Context(objectId)

    /** Where the layers go: the techniques' layer heights (stage "slice" / "slice parts"). */
    private fun layerPlan(st: SliceSettings): LayerPlan {
        // flat layers held to the machine's Z step (hardware.machine.motion.z.step): their height a whole number of steps
        // (the nearest, at least one), so every layer is the same thickness (a height between two multiples would print
        // alternating between them, in bands), and every top on a step (a remainder at the top, layers following the
        // surfaces). A curved band, layers that follow the underside, and lifted bridges print at heights of their own and
        // keep them
        val step = st.zStepMm?.takeIf { curvedFrom == null && !underside && !lifted && !banded }
        val onStep = { h: Double -> if (step == null) h else maxOf(1L, Math.round(h / step)) * step }
        val plan = plannedLayers(st, onStep)
        return if (step == null) plan else plan.snapped(soup.bounds()[2], step)
    }

    private fun plannedLayers(st: SliceSettings, onStep: (Double) -> Double = { it }): LayerPlan {
        val b = soup.bounds()
        fun mm(id: String) = (Resolver.resolveOrNull(setup!!, id, ctx)?.value as Number?)?.toDouble()
        val min = mm("hardware.tool.layer.height.min") ?: error("layers need hardware.tool.<key>.layer.height.min (flat layers of one height land their last layer on the top within it; flat layers following the surfaces stay above it)")
        // layers that follow the underside: planned over the shifted model, the hollow under it taken out of its height
        val top = if (underside) warpFor(st)!!.warpSoup(soup).bounds()[5]
            // several lifted bridges: the layers of what stands on the bed (each bridge plans its own above it)
            else if (banded) parts.indices.filter { !bridgePart[it] }.maxOf { parts[it].bounds()[5] }
            else if (lifted) warpFor(st)!!.let { w -> parts.indices.maxOf { i -> (if (bridgePart[i]) w.warpSoup(parts[i]) else parts[i]).bounds()[5] } }
            else b[5]
        if (!adaptive) {
            val plan = LayerPlan.uniform(b[2], top, onStep(st.firstLayerHeightUm / 1000.0), onStep(st.layerHeightUm / 1000.0), min)
            // a curved top band: its layers all one thickness (no thin remainder at the top, squeezed further where the top is low)
            return curvedFrom?.let { plan.evenAbove(b[2] + maxOf(it, st.firstLayerHeightUm / 1000.0), st.layerHeightUm / 1000.0) } ?: plan
        }
        val max = mm("hardware.tool.layer.height.max") ?: error("flat layers following the surfaces need hardware.tool.<key>.layer.height.max")
        return LayerPlan.composite(soup, onStep(st.firstLayerHeightUm / 1000.0), technique.absolute(b[2], b[5]), onStep(st.layerHeightUm / 1000.0), min, max,
            mm("plan.technique.flat.step.max") ?: st.layerHeightUm / 1000.0, landingsMm().map { b[2] + it }, heightRatio = mm("plan.technique.flat.height_ratio") ?: 0.0)
    }

    /**
     * Heights above the object's bottom (mm) that layers following the surfaces land on: each pause at a height, so
     * it comes exactly where asked ([pauses], and a plate's pauses through [landingHeightsMm]); the top of each curved island
     * holding a part taller than its cap, where that part goes on in flat layers (CurvedIsland.shared).
     * Planar layers fall where they fall, as before.
     */
    private fun landingsMm(): List<Double> = (pauses.filterIsInstance<PauseLock.AtZ>().map { it.zMm } + pauses.filterIsInstance<PauseLock.Above>().map { it.zMm } +
        technique.inserts.map { it.topMm } + technique.islands.filter { it.shared }.map { it.topMm } + landingHeightsMm).distinct().sorted()
    var landingHeightsMm: List<Double> = emptyList()
    private fun landingKey() = if (adaptive) "|land=" + landingsMm().joinToString(",") else ""

    /** All parts as one soup (bounds, footprint). Parts are sliced separately and unioned per layer. */
    private val soup: TriangleSoup = if (parts.size == 1) parts[0] else {
        val total = parts.sumOf { it.count }
        val c = FloatArray(total * 9); var w = 0
        for (p in parts) { System.arraycopy(p.coords, 0, c, w, p.count * 9); w += p.count * 9 }
        TriangleSoup(c, total)
    }
    /**
     * Exclude-object footprint: convex hull of the mesh's XY projection; center = bbox center. Of a section left open
     * ([sectionBox]), the section's box: what prints of the object, which the purge tower stands beside.
     */
    private val obj: PrintObject by lazy {
        val b = soup.bounds()
        val sb = sectionBox
        if (sb == null) PrintObject(programName(objectName, objectId), Footprint.hull(soup), (b[0] + b[3]) / 2, (b[1] + b[4]) / 2)
        else { val x0 = sb[0] / 1000.0; val x1 = sb[1] / 1000.0; val y0 = sb[2] / 1000.0; val y1 = sb[3] / 1000.0
            PrintObject(programName(objectName, objectId), doubleArrayOf(x0, y0, x1, y0, x1, y1, x0, y1), (x0 + x1) / 2, (y0 + y1) / 2) }
    }
    val printObject: PrintObject get() = obj
    private val meshKey: String = "mesh:" + parts.joinToString("+") { "${it.count}:${it.coords.contentHashCode()}" }
    private var setup: Setup? = null
    private var s: SliceSettings? = null
    private val ran = ArrayList<String>()
    /** Wall time (ms) of each stage that ran in the last slice(), own work only (inputs excluded). */
    val stageMs = LinkedHashMap<String, Long>()
    /** Where the stages report their layers and look for a cancel ([Progress]); the window's bar. */
    var progress: Progress = Progress.NONE
    private inline fun <T> timed(name: String, body: () -> T): T { val t = System.nanoTime(); val r = body(); stageMs[name] = (System.nanoTime() - t) / 1_000_000; ran.add(name); return r }

    // an optional setting with no value (e.g. tree support keys from a QIDIStudio config) keys as unset;
    // a required one still fails where it is read
    // a stage's key reads every setting it may depend on; reading for the key is not the print using them (record = false)
    private fun key(vararg ids: String) = ids.joinToString(";") { "$it=${Resolver.resolveOrNull(setup!!, it, plymeld.model.Context(ctx.obj, ctx.part, record = false))?.value ?: "(unset)"}" }

    /**
     * The curved band's warp: from the band's start, or the first layer's
     * top if higher (the first layer stays flat), to the model's top, over the model's top surface; null
     * without a curved band.
     */
    private fun warpFor(st: SliceSettings): plymeld.mesh.ZWarp? {
        val b = soup.bounds()
        if (underside) return plymeld.mesh.UndersideShift(b[2], plymeld.mesh.TopSurface.underside(soup, UP_NZ)::top)
        // several lifted bridges: what stands on the bed is flat; each bridge has its own lift ([bandWarps])
        if (banded) return null
        if (lifted) return plymeld.mesh.LiftWarp.of(merged(parts.filterIndexed { i, _ -> bridgePart[i] }), merged(parts.filterIndexed { i, _ -> !bridgePart[i] }),
            b[2], st.firstLayerHeightUm / 1000.0, st.layerHeightUm / 1000.0, UP_NZ, zaaHold(st))
        val from = curvedFrom ?: return null
        val zs = b[2] + maxOf(from, st.firstLayerHeightUm / 1000.0)
        val top = plymeld.mesh.TopSurface.of(soup, UP_NZ)
        return plymeld.mesh.Warp(zs, b[5], top::top)
    }

    private val sliceStage = Stage("slice", { meshKey + "|" + key(*SLICE_KEYS) + landingKey() }) {
        timed("slice") {
            progress.at("slice", 0, 0)
            val st = s!!
            val b = soup.bounds()
            val plan = layerPlan(st)
            // a curved band slices the warped model (its top flat); its paths are mapped back in program()
            val warp = warpFor(st)
            // a lifted bridge: only the bridge's parts are raised; what holds them up stays where it is
            val sliced = if (banded) parts.filterIndexed { i, _ -> !bridgePart[i] } else if (warp == null) parts else parts.mapIndexed { i, p -> if (warp is plymeld.mesh.LiftWarp && !bridgePart[i]) p else warp.warpSoup(p) }
            // Each part is sliced on its own (a part's loops close within the part), then the parts'
            // loops are unioned per layer so overlapping or touching parts print as one object.
            val layers: List<Layer> = if (sliced.size == 1) Slicer.slice(sliced[0], plan.slices) else {
                val perPart = sliced.map { Slicer.slice(it, plan.slices) }
                // the layers' unions side by side (one after another, a 29-part model's union was a seventh of its plan)
                Parallel.map(plan.slices.size) { li ->
                    val loops = perPart.flatMap { it[li].loops }
                    Layer(plan.slices[li], plymeld.geom.Booleans.normalize(loops), perPart.flatMap { it[li].openChains })
                }
            }
            val tops = LongArray(plan.count) { Math.round((plan.tops[it] - b[2]) * 1000) }
            // Z anti-aliasing: flat layers only (a curved band's and a lifted bridge's follow their own surface)
            val zaa = if (!st.zAntialias || underside) layers else zaaExtend(layers, sliced, plan) { t -> warp == null || t <= warp.zStart + 1e-9 }
            // a curved island's loops stay: the flat layers are planned with them, so what lies under, over, and beside the island
            // reads its material there; its paths are left out ([islandOmit])
            cleanUp(zaa, st) to tops
        }
    }

    /**
     * Per flat layer, each curved island's loops above its start ([islandStartIndex]) and, for an island holding a taller part,
     * up to its top ([islandTopIndex]): there the island is printed in its own layers ([islandsStage]), so the flat layers'
     * paths in them are left out (Planner.paths' omit). A loop is the island's when most of its points lie in the island's
     * area (above its start the island is apart from the rest of the layer, by how its area was found). Planned without those
     * loops, the flat layers read the island's ground as empty: a hidden top surface under its start (the pin's
     * column got a bridge, solid infill, and a top surface under its island), and a taller part's first flat layer over its top
     * read as laid on air.
     */
    private fun islandOmit(layers: List<Layer>, tops: LongArray): List<List<plymeld.geom.Polygon>>? {
        if (islands.isEmpty()) return null
        val after = islands.map { islandStartIndex(it, tops) }
        val upto = islands.map { islandTopIndex(it, tops) }
        return layers.mapIndexed { li, l ->
            val here = islands.indices.filter { li > after[it] && li <= upto[it] }
            if (here.isEmpty()) emptyList() else l.loops.filter { lp -> here.any { k -> islandLoop(islands[k], lp) } }
        }
    }

    /** The last flat layer at or under curved island [isl]'s start ([tops]: µm above the object's bottom); its top is the island's start as printed. */
    private fun islandStartIndex(isl: plymeld.core.decide.CurvedIsland, tops: LongArray): Int {
        val i = tops.indexOfLast { it <= Math.round(isl.startMm * 1000) + 1 }
        require(i >= 0) { "'$objectName': curved island ${isl.capId} starts at %.2f mm, under the first layer".format(java.util.Locale.ROOT, isl.startMm) }
        return i
    }

    /**
     * The flat layer whose top is curved island [isl]'s top, for an island holding a taller part (CurvedIsland.shared: the flat
     * layers land one there, [landingsMm], and go on from it); for any other no bound (nothing of it is above its top).
     */
    private fun islandTopIndex(isl: plymeld.core.decide.CurvedIsland, tops: LongArray): Int {
        if (!isl.shared) return Int.MAX_VALUE
        val t = Math.round(isl.topMm * 1000)
        val i = tops.indexOfFirst { Math.abs(it - t) <= 2 }
        require(i >= 0) { "'$objectName': curved island '${isl.key}' holds a part taller than its cap, but no flat layer lands on its top (%.3f mm)".format(java.util.Locale.ROOT, isl.topMm) }
        return i
    }

    /** Whether loop [lp] (µm) is curved island [isl]'s: most of its points (up to 16 of them, spread along it) in its area. */
    private fun islandLoop(isl: plymeld.core.decide.CurvedIsland, lp: plymeld.geom.Polygon): Boolean = isl.holds(lp)

    /**
     * Clean-up: simplify every outline to `resolution`, close gaps narrower than 2 x closing radius,
     * and simplify again (the closing's round joins add small arcs). Simplifying FIRST makes the
     * closing ~6x cheaper — on the Benchy the raw slices carry 231,786 points, 30,209 after
     * simplifying; closing them took 919 ms raw vs 153 ms (SliceTimingTest).
     */
    private fun cleanUp(layers: List<Layer>, st: SliceSettings): List<Layer> {
        fun simplify(ls: List<plymeld.geom.Polygon>) = if (st.resolutionUm <= 0) ls else ls.map { it.douglasPeucker(st.resolutionUm) }.filter { it.size >= 3 && it.signedArea2() != 0L }
        return Parallel.map(layers.size) { li -> val l = layers[li]
            var loops = simplify(l.loops)
            if (st.closingRadiusUm > 0 && loops.isNotEmpty()) {
                loops = plymeld.geom.Offset.offset(plymeld.geom.Offset.offset(loops, st.closingRadiusUm, plymeld.geom.Offset.Join.ROUND), -st.closingRadiusUm, plymeld.geom.Offset.Join.ROUND)
                loops = simplify(loops)
            }
            Layer(l.z, loops, l.openChains)
        }
    }
    /**
     * Layers that follow the underside: where the object stands on the bed, its section [AIR_MM] above its bottom (the
     * first layer over the rest is in the air, over the hollow); null for any other technique.
     */
    private val onBed: List<plymeld.geom.Polygon>? by lazy {
        if (!underside) null else {
            val z = doubleArrayOf(soup.bounds()[2] + AIR_MM)
            plymeld.geom.Booleans.normalize(parts.flatMap { Slicer.slice(it, z)[0].loops })
        }
    }
    private val wallsStage = Stage("walls", { sliceStage.key() + "|" + key(*WALL_KEYS) }) {
        val sl = sliceStage.get(); progress.check(); timed("walls") { Planner.walls(s!!, sl.first.map { it.loops }, sl.second, onBed = onBed, progress = progress, settingsKey = key(*WALL_KEYS)) }
    }
    private val regionsStage = Stage("regions", { wallsStage.key() + "|" + key(*REGION_KEYS) }) {
        val w = wallsStage.get(); progress.check(); timed("regions") { Planner.regions(s!!, w, if (adaptive) Planner.shellCounts(s!!, sliceStage.get().second) else null, progress, settingsKey = key(*REGION_KEYS)) }
    }
    private val pathsStage = Stage("paths", { regionsStage.key() + "|" + key(*PATH_KEYS) }) {
        val (layers, tops) = sliceStage.get()
        val w = wallsStage.get(); val r = regionsStage.get()
        // a lifted bridge's first layer rests, in fact, on the printed tops under it that reach its underside, whatever layer
        // they end on (a support's top several layers lower in the lifted frame): its bridge lines are anchored on those
        // (restingTops), not on the layer before, and not on what lies further down (a floor under the span)
        val anchors: Map<Int, List<plymeld.geom.Polygon>> = (warpFor(s!!) as? plymeld.mesh.LiftWarp)?.let { lw ->
            val zs = Math.round((lw.zStart - soup.bounds()[2]) * 1000)
            val i0 = tops.indexOfFirst { it > zs + 1 }
            if (i0 <= 0) emptyMap() else mapOf(i0 to restingTops(layers.subList(0, i0).map { it.loops },
                DoubleArray(i0) { soup.bounds()[2] + tops[it] / 1000.0 }, lw, s!!.layerHeightUm / 1000.0))
        } ?: emptyMap()
        supportArea = anchors.values.firstOrNull() ?: emptyList()
        progress.check()
        timed("paths") { Planner.paths(s!!, layers.map { it.loops }, w, r, tops, onBed = onBed, bridgeAnchors = anchors, alongBridge = s!!.bridgeFillAlong, support = supportRegions(), covers = coverBoxes(), standsOn = stripCovers(tops), progress = progress, settingsKey = key(*PATH_KEYS),
            omit = islandOmit(layers, tops),
            // a curved band's top surface height (plan.technique.curved.top_order): the warp's own surface, in mm
            surfaceTop = (warpFor(s!!) as? plymeld.mesh.Warp)?.let { wp -> { x: Double, y: Double -> wp.unwarp(x, y, wp.zTop) } },
            // a lifted bridge's first layer as laid (plan.technique.bridge.wave.spacing = "even"): its top unwarped, in mm
            bridgeTop = (warpFor(s!!) as? plymeld.mesh.LiftWarp)?.let { lw ->
                val zs = Math.round((lw.zStart - soup.bounds()[2]) * 1000)
                val i0 = tops.indexOfFirst { it > zs + 1 }
                if (i0 < 0) null else { val topW = soup.bounds()[2] + tops[i0] / 1000.0; { x: Double, y: Double -> lw.unwarp(x, y, topW) } }
            }) }
    }

    /**
     * The plan's support spots (SupportPlan) as the boxes the Planner confines support to: x and y
     * from the posed object's corner become the placed frame (the object's bounds here), z stays from its bottom; µm.
     * Null = the plan has no support of its own (the switch decides).
     */
    private fun supportRegions(): Supports.Regions? {
        val b = soup.bounds()
        fun box(r: plymeld.core.decide.Coordination.Volume.Region) = Supports.Box(Math.round((b[0] + r.x0) * 1000), Math.round((b[0] + r.x1) * 1000),
            Math.round((b[1] + r.y0) * 1000), Math.round((b[1] + r.y1) * 1000), Math.round(r.fromMm * 1000), Math.round(r.toMm * 1000))
        // the regions support is kept off (support = "never"), whatever the plan or the switch says
        val blocked = technique.regions.filter { it.support == plymeld.core.decide.Region.NEVER }.map { box(it.region) }
        val sp = technique.support ?: return if (blocked.isNotEmpty() && s!!.enableSupport == true) Supports.Regions(null, emptyList(), blocked) else null
        return Supports.Regions(sp.chosen.map { box(it.region) }, sp.locked.map { box(it.region) }, blocked)
    }

    /**
     * The plan's inserts as the boxes the Planner lays the layer above onto: x and y from the posed object's corner
     * become the placed frame, z0 is the insert's top from the object's bottom, z1 the socket's top; µm.
     */
    /**
     * Several lifted bridges ([banded]): by flat layer, the strips' material under it, which it is laid onto. The flat
     * layers are sliced without the strips (each is printed in its own band, after them), so a pillar's layer over a
     * strip's end would read as over air (an underside technique laid onto solid strip). A strip printed along its own
     * underside fills its part's volume, so the strip under a layer is the bridge parts' cut at the middle of the layer
     * below. Empty otherwise.
     */
    private fun stripCovers(tops: LongArray): Map<Int, List<plymeld.geom.Polygon>> {
        if (!banded || tops.size < 2) return emptyMap()
        val z0 = soup.bounds()[2]
        val zs = DoubleArray(tops.size - 1) { k -> z0 + (tops[k] - (tops[k] - (if (k == 0) 0L else tops[k - 1])) / 2.0) / 1000.0 }
        val cuts = parts.indices.filter { bridgePart[it] }.map { Slicer.slice(parts[it], zs) }
        val out = HashMap<Int, List<plymeld.geom.Polygon>>()
        for (k in zs.indices) {
            val loops = cuts.flatMap { it[k].loops }
            if (loops.isNotEmpty()) out[k + 1] = plymeld.geom.Booleans.normalize(loops)
        }
        return out
    }

    private fun coverBoxes(): List<Supports.Box> {
        val b = soup.bounds()
        return technique.inserts.map { ins -> val r = ins.region
            Supports.Box(Math.round((b[0] + r.x0) * 1000), Math.round((b[0] + r.x1) * 1000), Math.round((b[1] + r.y0) * 1000), Math.round((b[1] + r.y1) * 1000),
                Math.round(ins.topMm * 1000), Math.round(r.toMm * 1000)) }
    }

    /**
     * The support columns the plan finishes before the model's layers beside them (Coordination.Order.FIRST):
     * each as its box in the placed frame grown by the widest branch and the gap to the object (a tree leans out from the
     * overhang), with its height range; Job prints their support paths first.
     */
    fun firstColumns(): List<Supports.Box> {
        val sp = technique.support ?: return emptyList()
        val st = s ?: return emptyList()
        val grow = st.treeBranchDiameterUm / 2 + (st.supportXyUm ?: st.supportWidthUm)
        val b = soup.bounds()
        return sp.spots.filter { it.order == plymeld.core.decide.Coordination.Order.FIRST }.map { sp1 ->
            val r = sp1.region
            Supports.Box(Math.round((b[0] + r.x0) * 1000) - grow, Math.round((b[0] + r.x1) * 1000) + grow, Math.round((b[1] + r.y0) * 1000) - grow, Math.round((b[1] + r.y1) * 1000) + grow,
                Math.round((sp1.standsOnMm ?: 0.0) * 1000), Math.round(r.fromMm * 1000))
        }
    }

    /**
     * What a lifted bridge [lw] rests on, seen from above: of the layers printed under it ([loops], their tops [topsMm],
     * absolute mm, bottom up), each layer's top left uncovered by the layers above it, where it reaches the bridge's underside
     * (within a layer, [layerMm], plus LiftWarp.CONTACT_MM: a support meets the bridge up to half a layer off its grid).
     * Everything printed under the bridge counted earlier, at any height: a floor joining an arch's feet 1 mm off the
     * bed, 7 mm under the strip, was taken for one support holding the whole span (feet joined by a floor so they
     * cannot move apart after removal). Unioned two by two (a tube's hundreds of identical rings at once: minutes in one
     * arrangement of coincident edges).
     */
    private fun restingTops(loops: List<List<plymeld.geom.Polygon>>, topsMm: DoubleArray, lw: plymeld.mesh.LiftWarp, layerMm: Double): List<plymeld.geom.Polygon> {
        val tol = layerMm + plymeld.mesh.LiftWarp.CONTACT_MM
        val n = loops.size
        // what covers each layer: the union of the layers above it (built top down, two by two at each step's end)
        val covers = arrayOfNulls<List<plymeld.geom.Polygon>>(n)
        var above: List<plymeld.geom.Polygon> = emptyList()
        for (k in n - 1 downTo 0) { covers[k] = above; above = if (above.isEmpty()) loops[k] else plymeld.geom.Booleans.normalize(above + loops[k]) }
        val kept = Parallel.map(n) { k ->
            val exposed = if (covers[k]!!.isEmpty()) loops[k] else plymeld.geom.Booleans.difference(loops[k], covers[k]!!)
            plymeld.geom.Polygons.group(exposed).filter { (o, hs) -> reachesUnderside(o, hs, topsMm[k], lw, tol) }.flatMap { (o, hs) -> listOf(o) + hs }
        }
        var level = kept.filter { it.isNotEmpty() }
        if (level.isEmpty()) return emptyList()
        while (level.size > 1) level = Parallel.map((level.size + 1) / 2) { j ->
            if (2 * j + 1 < level.size) plymeld.geom.Booleans.normalize(level[2 * j] + level[2 * j + 1]) else level[2 * j] }
        return plymeld.geom.Booleans.normalize(level.single())
    }

    /**
     * Whether the region [o] (holes [hs], µm) at height [topMm] reaches the bridge's underside: a point of it, on a grid over
     * it (at most RESTS_SAMPLES a side, no finer than RESTS_STEP_UM), under the bridge where the underside is within [tol].
     */
    private fun reachesUnderside(o: plymeld.geom.Polygon, hs: List<plymeld.geom.Polygon>, topMm: Double, lw: plymeld.mesh.LiftWarp, tol: Double): Boolean {
        val bb = o.bounds()
        val sx = maxOf(RESTS_STEP_UM, (bb[2] - bb[0]) / RESTS_SAMPLES); val sy = maxOf(RESTS_STEP_UM, (bb[3] - bb[1]) / RESTS_SAMPLES)
        var y = bb[1] + sy / 2
        while (y <= bb[3]) {
            var x = bb[0] + sx / 2
            while (x <= bb[2]) {
                val xl = Math.round(x); val yl = Math.round(y)
                if (plymeld.geom.Polygons.contains(o, xl, yl) && hs.none { plymeld.geom.Polygons.contains(it, xl, yl) }) {
                    val xm = x / 1000.0; val ym = y / 1000.0
                    if (lw.covers(xm, ym) && Math.abs(lw.unwarp(xm, ym, lw.zStart) - topMm) <= tol) return true
                }
                x += sx
            }
            y += sy
        }
        return false
    }

    /**
     * One of several lifted bridges, planned: printed after layer [after] of what stands on the bed, its own layers (in its
     * lifted space, [bandWarps]), and what its first layer is anchored on (the printed tops under it that reach its underside,
     * seen from above: [restingTops]).
     */
    private class Band(val after: Int, val layers: List<LayerPlanOut>, val anchors: List<plymeld.geom.Polygon>)

    /**
     * Several lifted bridges ([banded]): each planned as the layers above the one it rests on, over what stands on the bed up
     * to there (so its regions and its anchors are read as for a lone lifted bridge, whose supports end under it), then
     * printed straight after that layer, before anything stands on it. Its layers are in its own lifted space ([layerSteps]
     * lays them back along its underside).
     */
    private val bandsStage = Stage("bridges", { pathsStage.key() + "|" + key("plan.technique.bridge.layer.height") }) {
        val (layers, tops) = sliceStage.get()
        pathsStage.get()
        timed("bridges") {
            val st = s!!
            val b = soup.bounds()
            val min = (Resolver.resolveOrNull(setup!!, "hardware.tool.layer.height.min", ctx)?.value as Number?)?.toDouble() ?: error("layers need hardware.tool.<key>.layer.height.min")
            bandWarps(st).mapIndexed { k, lw ->
                val rests = Math.round((lw.restsOn - b[2]) * 1000)
                val after = tops.indexOfLast { it <= rests + 1 }
                require(after >= 0) { "'$objectName': lifted bridge ${k + 1} rests at %.2f mm, under the first layer".format(java.util.Locale.ROOT, lw.restsOn - b[2]) }
                val warped = lw.warpSoup(merged(bridgeGroups[k].map { parts[it] }))
                // the layers under it (what holds it up, on the object's grid), then its own, plan.technique.bridge.layer.height thick (the
                // object's when unset, so a wall at 0.1 mm can carry arches at 0.2): from where it
                // rests to its lifted start (empty: it is lifted above them), then the bridge, its last layer landing on its top
                val hb = (st.bridgeLayerHeightUm ?: st.layerHeightUm) / 1000.0
                val absTops = ArrayList<Double>()
                for (i in 0..after) absTops += b[2] + tops[i] / 1000.0
                var t = absTops.last()
                while (t < lw.zStart - 1e-6) { t = minOf(t + hb, lw.zStart); absTops += t }
                LayerPlan.uniform(lw.zStart, warped.bounds()[5], hb, hb, min).tops.forEach { absTops += it }
                val plan = LayerPlan(absTops.toDoubleArray(), DoubleArray(absTops.size) { i -> if (i == 0) (b[2] + absTops[0]) / 2 else (absTops[i - 1] + absTops[i]) / 2 })
                val pTops = LongArray(plan.count) { Math.round((plan.tops[it] - b[2]) * 1000) }
                val above = cleanUp(Slicer.slice(warped, plan.slices.copyOfRange(after + 1, plan.count)), st)
                val loops = layers.subList(0, after + 1).map { it.loops } + above.map { it.loops }
                val w = Planner.walls(st, loops, pTops)
                val r = Planner.regions(st, w, if (adaptive) Planner.shellCounts(st, pTops) else null)
                val zs = Math.round((lw.zStart - b[2]) * 1000)
                val first = pTops.indexOfFirst { it > zs + 1 }
                // what it rests on: the printed tops under it that reach its underside (restingTops)
                val anchors = restingTops(layers.subList(0, after + 1).map { it.loops }, DoubleArray(after + 1) { absTops[it] }, lw, st.layerHeightUm / 1000.0)
                val planned = Planner.paths(st, loops, w, r, pTops, bridgeAnchors = mapOf(first to anchors), alongBridge = st.bridgeFillAlong)
                Band(after, planned.drop(after + 1).filter { it.paths.isNotEmpty() }, anchors)
            }
        }
    }

    /** One curved island's own layers, planned ([islandsStage]): in its shifted space, each [LayerPlanOut.topZUm] its warped top. */
    private class IslandLayers(val k: Int, val warp: plymeld.mesh.ShiftWarp, val layers: List<LayerPlanOut>,
                               /** For an object laid per part: a part of the island's (its unit's filament and settings); null = the object's. */
                               val part: Int? = null)

    /**
     * Curved islands ([islands]): each sliced in its own layers above its start (the top of the last flat layer under it), the
     * model warped by its shifted warp (its start snapped down to that flat top, so no slab is printed twice), its layers
     * [plymeld.core.decide.CurvedIsland.layerMm] thick in warped space up to its top, its loops only; planned (walls, regions,
     * paths) over the flat layers under it from the first (what its shells and bottom read), those dropped after.
     */
    private val islandsStage = Stage("islands", { (if (multiFilament) filamentsStage.key() else pathsStage.key()) + "|islands=" + islands.size }) {
        // the flat layers' loops and tops: the object's, or for an object laid per part (multiProgram) every part's together
        val (layers, tops) = if (!multiFilament) sliceStage.get().also { pathsStage.get() } else partSliceStage.get().let { (pl, t) ->
            pl[0].indices.map { li -> Layer(0.0, plymeld.geom.Booleans.unionPositive(pl.flatMap { it[li] }), emptyList()) } to t }
        timed("islands") {
            val b = soup.bounds()
            val min = (Resolver.resolveOrNull(setup!!, "hardware.tool.layer.height.min", ctx)?.value as Number?)?.toDouble() ?: error("layers need hardware.tool.<key>.layer.height.min")
            val max = (Resolver.resolveOrNull(setup!!, "hardware.tool.layer.height.max", ctx)?.value as Number?)?.toDouble() ?: error("curved islands need hardware.tool.<key>.layer.height.max")
            islands.mapIndexed { k, isl ->
                val after = islandStartIndex(isl, tops)
                val startAbs = b[2] + tops[after] / 1000.0
                val w = plymeld.mesh.ShiftWarp(startAbs, b[2] + isl.topMm, isl.cellMm, isl.window()) { x, y -> isl.dipAt(x, y) }
                // its height from its start as printed in equal layers near its own layer height, each within the tool's limits
                val h = isl.ownLayerMm(w.zTop - startAbs, min, max) ?: error("'$objectName': curved island '${isl.key}' from %.3f to %.3f mm: no whole number of layers keeps every one within the tool's thinnest (%.3f mm, where it dips most) and thickest (%.3f mm)"
                    .format(java.util.Locale.ROOT, startAbs - b[2], isl.topMm, min, max))
                val own = LayerPlan.uniform(startAbs, w.zTop, h, h, min)
                val sliced = parts.map { w.warpSoup(it) }
                val perPart = if (sliced.size == 1) null else sliced.map { Slicer.slice(it, own.slices) }
                val raw: List<Layer> = if (perPart == null) Slicer.slice(sliced[0], own.slices) else
                    own.slices.indices.map { li -> Layer(own.slices[li], plymeld.geom.Booleans.normalize(perPart.flatMap { it[li].loops }), emptyList()) }
                // the island's parts (those with a loop it holds) and the settings it is laid with: for an object laid per part,
                // its parts must share one unit (filament and settings of their own); the object's own settings otherwise
                val mine = perPart?.indices?.filter { p -> perPart[p].any { l -> l.loops.any { islandLoop(isl, it) } } }.orEmpty()
                val unitPart = if (!multiFilament) null else {
                    val units = partUnits()
                    val us = mine.map { units[it] }.distinct()
                    require(us.size <= 1) { "'$objectName': curved island '${isl.key}' covers parts laid with different filaments or settings (" +
                        mine.joinToString { "part ${it + 1}: ${units[it]}" } + "): an island is laid as one, so it is not built over them yet" }
                    mine.firstOrNull()
                }
                val st = unitPart?.let { p -> if (partUnits()[p] == setup!!.objects[objectId].spool) s!! else SliceSettings.of(setup!!, plymeld.model.Context(objectId, p)) } ?: s!!
                val ownLayers = cleanUp(raw, st).map { l -> Layer(l.z, l.loops.filter { islandLoop(isl, it) }, emptyList()) }
                // the flat layers under it, from the first (the planner reads its first layer as the one on the bed: brim, first-layer
                // widths; a context starting part-way up got a brim round the island's column), for what its shells and bottom read;
                // the layers the flat plan already made come from the layer cache
                val ctxFrom = 0
                // and over it, for an island holding a taller part (CurvedIsland.shared), that part's flat layers above the island's
                // top as long as it goes on, so its last layers read the part as covered, not as a top surface
                val upto = islandTopIndex(isl, tops)
                val above = if (upto == Int.MAX_VALUE) emptyList() else
                    (upto + 1 until layers.size).asSequence().map { li -> li to layers[li].loops.filter { islandLoop(isl, it) } }.takeWhile { it.second.isNotEmpty() }.toList()
                val loops = layers.subList(ctxFrom, after + 1).map { it.loops } + ownLayers.map { it.loops } + above.map { it.second }
                val nBelow = after + 1 - ctxFrom
                val pTops = LongArray(nBelow + own.count + above.size) { i -> when {
                    i < nBelow -> tops[ctxFrom + i]
                    i < nBelow + own.count -> Math.round((own.tops[i - nBelow] - b[2]) * 1000)
                    else -> tops[above[i - nBelow - own.count].first]
                } }
                val wl = Planner.walls(st, loops, pTops)
                val r = Planner.regions(st, wl, if (adaptive) Planner.shellCounts(st, pTops) else null)
                val planned = Planner.paths(st, loops, wl, r, pTops)
                IslandLayers(k, w, planned.drop(nBelow).take(own.count).filter { it.paths.isNotEmpty() }, unitPart)
            }
        }
    }

    /** The planned layers in print order: [pathsStage]'s, and each of several lifted bridges' ([bandsStage]) after the layer it rests on. */
    private fun plannedLayers(): List<LayerPlanOut> {
        val flat = pathsStage.get()
        bridgeTops.clear()
        if (islands.isNotEmpty()) {
            // each island layer after the last flat layer whose top is under its own (warped) top: by height
            val queue = islandsStage.get().flatMap { il -> il.layers.map { it to il.k } }.sortedBy { it.first.topZUm }
            islandOf.clear()
            val out = ArrayList<LayerPlanOut>()
            fun add(l: LayerPlanOut, k: Int?) { val n = LayerPlanOut(out.size, l.topZUm, l.heightUm, l.paths); if (k != null) islandOf[n] = k; out += n }
            var q = 0
            for ((i, l) in flat.withIndex()) {
                add(l, null)
                val next = flat.getOrNull(i + 1)?.topZUm ?: Long.MAX_VALUE
                while (q < queue.size && queue[q].first.topZUm < next) { add(queue[q].first, queue[q].second); q++ }
            }
            while (q < queue.size) { add(queue[q].first, queue[q].second); q++ }
            return out
        }
        if (!banded) {
            if (lifted) require(s!!.bridgeLayerHeightUm == null || s!!.bridgeLayerHeightUm == s!!.layerHeightUm) {
                "'$objectName': a lone lifted bridge shares the object's layers; plan.technique.bridge.layer.height (${s!!.bridgeLayerHeightUm!! / 1000.0} mm) is for an object with several lifted bridges" }
            if (lifted) flat.filter { isBridgeLayer(it) && it.paths.isNotEmpty() }.takeIf { it.size > 1 }?.let { bridgeTops[it.last()] = true }
            // a section left open: the whole object's lines, laid inside its box only
            val box = sectionBox ?: return flat
            require(!lifted && warpFor(s!!) == null) { "'$objectName': a section left open is cut from flat layers; with a curved band or a lifted bridge it is not written yet (a section with faces = \"closed\" is)" }
            return openSection.getOrPut(flat) { SectionClip.layers(flat, box[0], box[1], box[2], box[3], box[4], ::sameLayer) }
        }
        val bands = bandsStage.get()
        bandOf.clear()
        val out = ArrayList<LayerPlanOut>()
        fun add(l: LayerPlanOut, band: Int?) { val n = LayerPlanOut(out.size, l.topZUm, l.heightUm, l.paths); if (band != null) bandOf[n] = band; out += n }
        for ((i, l) in flat.withIndex()) {
            add(l, null)
            for ((k, bd) in bands.withIndex()) if (bd.after == i) {
                bd.layers.forEach { add(it, k) }
                if (bd.layers.size > 1) bridgeTops[out.last()] = true
            }
        }
        return out
    }
    /**
     * The object's section left open (TechniquePlan.regions' section) in the frame its paths are in: x0, x1, y0, y1 placed
     * and its top from the object's bottom, µm; null = the whole object prints.
     */
    private val sectionBox: LongArray? by lazy {
        technique.regions.firstOrNull { it.section }?.let { r -> val g = r.region; val b = soup.bounds()
            require(islands.isEmpty() && !banded) { "'$objectName': a section left open is cut from flat layers; with a curved island or several lifted bridges it is not written yet (a section with faces = \"closed\" is)" }
            longArrayOf(Math.round((b[0] + g.x0) * 1000), Math.round((b[0] + g.x1) * 1000), Math.round((b[1] + g.y0) * 1000), Math.round((b[1] + g.y1) * 1000), Math.round(g.toMm * 1000)) }
    }
    /** The planned layers cut to [sectionBox], by the layers they were cut from (the stage's own list: cut once). */
    private val openSection = java.util.IdentityHashMap<List<LayerPlanOut>, List<LayerPlanOut>>()
    /** A per-filament plan cut to [sectionBox] (its layers above the box's top left out, every filament's and the supports' lines cut), by the plan it was cut from. */
    private val openFilaments = java.util.IdentityHashMap<MultiFilament.Result, Pair<MultiFilament.Result, LongArray>>()
    private fun sectioned(planned: Pair<MultiFilament.Result, LongArray>): Pair<MultiFilament.Result, LongArray> {
        val box = sectionBox ?: return planned
        return openFilaments.getOrPut(planned.first) {
            val (r, tops) = planned
            val n = tops.count { it <= box[4] + 1 }
            fun cut(ps: List<ToolPath>) = SectionClip.paths(ps, box[0], box[1], box[2], box[3])
            MultiFilament.Result(r.filaments.map { f -> MultiFilament.FilamentPlan(f.spool, f.areas.take(n), SectionClip.layers(f.layers, box[0], box[1], box[2], box[3], box[4]), f.unit) },
                r.overlapUm2PerLayer.copyOf(n), r.whole, r.timingMs, r.support.take(n).map(::cut), r.supportInterface.take(n).map(::cut)) to tops.copyOf(n)
        }
    }

    /** For several lifted bridges: which bridge each of [plannedLayers]' bridge layers is (none for the rest). */
    private val bandOf = java.util.IdentityHashMap<LayerPlanOut, Int>()
    /** The warp planned layer [l] is laid back through: its own bridge's for several lifted bridges. */
    private fun warpOf(l: LayerPlanOut): plymeld.mesh.ZWarp? = islandOf[l]?.let { islandsStage.get()[it].warp }
        ?: if (banded) bandOf[l]?.let { bandWarps(s!!)[it] } else warpFor(s!!)

    /**
     * [copy], a copy of planned layer [of] with some of its paths (Job: support columns printed first), made the same layer
     * here: its curved island, lifted bridge, and bridge top (each known by the layer itself, not by its height).
     */
    fun sameLayer(copy: LayerPlanOut, of: LayerPlanOut): LayerPlanOut {
        islandOf[of]?.let { islandOf[copy] = it }
        bandOf[of]?.let { bandOf[copy] = it }
        bridgeTops[of]?.let { bridgeTops[copy] = it }
        return copy
    }

    /** The key of the curved island [l] is a layer of (its plan's key, else "island <n>"), or null for any other layer. */
    fun islandKeyOf(l: LayerPlanOut): String? = islandOf[l]?.let { k -> islands[k].key.ifEmpty { "island ${k + 1}" } }

    /** Each part's spool (the object's own when the setup lists no parts for it). */
    private fun partSpools(): List<String> {
        val o = setup!!.objects[objectId]
        return if (o.parts.size == parts.size) o.parts.map { it.spool } else List(parts.size) { o.spool }
    }

    /**
     * Each part's settings group: its spool, or, for a part with settings of its own that change what it is laid with, its
     * spool plus the first part laid with those same values. A part's own value equal to the one it would have anyway (the
     * object's, its filament's) changes nothing, so parts whose settings agree share a group: touching, they are one area,
     * with no walls or seams where they meet.
     */
    private fun partUnits(): List<String> {
        val o = setup!!.objects[objectId]
        val spools = partSpools()
        if (o.parts.size != parts.size) return spools
        val firstWith = HashMap<String, String>()
        return o.parts.mapIndexed { k, p ->
            val ctxK = plymeld.model.Context(objectId, k, record = false)
            val differ = p.overrides.ids.sorted().filter { id -> Resolver.inherited(setup!!, id, ctxK)?.value != p.overrides[id]?.value }
            if (differ.isEmpty()) spools[k] else {
                val sig = spools[k] + "|" + differ.joinToString(";") { "$it=${p.overrides[it]?.value}" }
                spools[k] + " / " + firstWith.getOrPut(sig) { "part '${p.name}' settings" }
            }
        }
    }

    /**
     * The settings each part group is planned with (its first part's): a group with overrides, or on another spool than the
     * object's own (a group differing only by its spool was planned with the object's settings, so its filament's
     * overhang share and its tool's bore were the first part's).
     */
    private fun unitSettings(units: List<String>): Map<String, SliceSettings> =
        units.withIndex().filter { it.value != setup!!.objects[objectId].spool }.distinctBy { it.value }
            .associate { (k, u) -> u to SliceSettings.of(setup!!, plymeld.model.Context(objectId, k)) }

    /**
     * Whether this object is planned part group by part group: its parts are on more than one spool
     * (multi-color), or some part has settings of its own that change what it is laid with ([partUnits]).
     */
    val multiFilament: Boolean get() = partUnits().distinct().size > 1 ||
        supportSpools().toList().any { it != null && it != setup!!.objects[objectId].spool }

    /** [multiFilament] with [setup] (before any slice of it: Job.write chooses how to write the object by it). */
    fun isMultiFilament(setup: Setup): Boolean { this.setup = setup; return multiFilament }

    /**
     * The spools that print support and support interface (plan.support.slot /
     * plan.support.interface.slot; 0 = null = whichever filament is loaded). Both null when support is off.
     */
    private fun supportSpools(): Pair<String?, String?> {
        val st = setup!!
        // the switch locked on, or unset with the plan's own support spots
        val on = Resolver.resolveOrNull(st, "plan.support.enabled", ctx)?.value as Boolean? ?: (technique.support?.any == true)
        if (!on) return null to null
        fun spool(id: String): String? {
            val n = (Resolver.resolve(st, id, ctx).value as Number).toInt()
            if (n <= 0) return null
            require(n <= st.spools.size) { "$id = $n, but there are ${st.spools.size} filament slot(s)" }
            return st.spools[n - 1].id
        }
        return spool("plan.support.slot") to spool("plan.support.interface.slot")
    }

    // Multi-color: each part sliced and cleaned on its own, then planned per filament. The plan
    // stage reads every wall/region/path setting, the parts' spools, and the overlap rule.
    private val partSliceStage = Stage("slice parts", { meshKey + "|" + key(*SLICE_KEYS) + landingKey() }) {
        timed("slice parts") {
            progress.at("slice", 0, 0)
            val st = s!!
            val b = soup.bounds()
            val plan = layerPlan(st)
            val tops = LongArray(plan.count) { Math.round((plan.tops[it] - b[2]) * 1000) }
            // curved islands: each part's loops an island holds stay, so the flat layers are planned with them (what lies under,
            // over and beside the island reads its material there); their paths are left out (filamentsStage, islandOmit), as for
            // an object laid whole. taken out here, the layers under an island's start read its ground as empty and
            // laid a hidden top surface there (a dome laid per part: 1563 s against 1400 laid whole)
            parts.map { p -> cleanUp(Slicer.slice(p, plan.slices), st).map { it.loops } } to tops
        }
    }
    private val filamentsStage = Stage("filaments", {
        partSliceStage.key() + "|" + key(*WALL_KEYS, *REGION_KEYS, *PATH_KEYS, "job.part_overlap") + "|spools=" + partSpools().joinToString(",") +
            "|islands=" + islands.joinToString(",") { "${it.key}@${it.startMm}" } +
            "|parts=" + setup!!.objects[objectId].parts.joinToString(",") { p -> p.overrides.ids.sorted().joinToString(";") { "$it=${p.overrides[it]?.value}" } }
    }) {
        val (partLayers, tops) = partSliceStage.get()
        timed("filaments") {
            val spools = partSpools()
            val units = partUnits()
            val slots = spools.map { sp -> setup!!.spools.indexOfFirst { it.id == sp } + 1 }
            val rule = Resolver.resolve(setup!!, "job.part_overlap", ctx).value as String
            val firstLayers = partLayers.map { layers -> layers.indexOfFirst { it.isNotEmpty() } }
            // the islands' loops of every part, per flat layer: their paths left out (islandOmit)
            val omit = islandOmit(partLayers[0].indices.map { li -> Layer(0.0, partLayers.flatMap { it[li] }, emptyList()) }, tops)
            MultiFilament.plan(s!!, partLayers, spools, FilamentAreas.precedence(rule, slots, firstLayers), tops, setup!!.objects[objectId].spool, units, unitSettings(units), varyingLayers = adaptive, supportSpots = supportRegions(), covers = coverBoxes(), standsOn = stripCovers(tops), progress = progress, omit = omit)
                .also { res -> res.timingMs.forEach { (k, v) -> stageMs["filaments/$k"] = v } } to tops
        }
    }

    /** The per-filament plan and its layer tops, for a multi-filament object. */
    fun plannedFilaments(setup: Setup): Pair<MultiFilament.Result, LongArray> {
        this.setup = setup
        s = SliceSettings.of(setup, ctx)
        ran.clear(); stageMs.clear()
        require(multiFilament) { "object '$objectName' is on one filament with no part settings" }
        return sectioned(filamentsStage.get())
    }

    /**
     * This object's lines for a plate of several objects (Job): laid per part ([plannedFilaments]) when it is on several
     * filaments or part settings, else its planned layers under its one spool; with its layers' tops. Flat layers only so
     * far: a curved island, a curved band, or a lifted bridge on such a plate is refused.
     */
    fun laidForPlate(setup: Setup): Pair<MultiFilament.Laid, LongArray> {
        if (isMultiFilament(setup)) {
            val (r, tops) = plannedFilaments(setup)
            require(islands.isEmpty()) { "'$objectName' has a curved island and is laid per part: on a plate of several objects that is not written yet; slice it on its own" }
            val (sup, itf) = supportSpools()
            return MultiFilament.Laid(obj.name, r.filaments, r.support, r.supportInterface, sup, itf) to tops
        }
        val planned = planned(setup)
        require(planned.none { islandKeyOf(it) != null || isBridgeLayer(it) } && planned.withIndex().all { (i, l) -> l.index == i }) {
            "'$objectName' has layers of its own (a curved island or band, or a lifted bridge): on a plate with an object laid per part that is not written yet; slice it on its own" }
        return MultiFilament.Laid(obj.name, listOf(MultiFilament.FilamentPlan(setup.objects[objectId].spool, emptyList(), planned))) to LongArray(planned.size) { planned[it].topZUm }
    }

    /** A multi-filament object as a print program: filament order per layer, tool changes between object blocks. */
    private fun multiProgram(setup: Setup): PrintProgram {
        val (r, tops) = plannedFilaments(setup)
        val spoolOrder = setup.spools.map { it.id }
        val (supSpool, itfSpool) = supportSpools()
        val present = MultiFilament.present(r, supSpool, itfSpool)
        val order = MultiFilament.layerOrder(present, spoolOrder, setup.objects[objectId].spool)
        val toolOf = { sp: String -> setup.spool(sp).tool }
        // two spools in ONE tool: a filament swapped by hand at a pause (MultiFilament.steps marks the swap, Pauses.insert
        // requires the pause; no automatic changer is written yet)
        // the print starts with the tool of the first filament printed: the writer's start template selects and heats it
        // (initial_tool, the tool of the program's first extrusion), so the first layer begins without a change
        val startTool = order.firstOrNull { it.isNotEmpty() }?.first()?.let(toolOf) ?: setup.spool(setup.objects[objectId].spool).tool
        // what each tool holds at the start: the spool numbered as the tool (the loadout's own filament), so a part on a slot
        // spool printed first on that tool swaps before its first line (Pauses.placeSwaps says so)
        val loadedOn = setup.spools.filter { it.id.removePrefix("spool ").trim().toIntOrNull() == it.tool + 1 }.associate { it.tool to it.id }
        // the tower's bands: one per purge, a tool change or a spool swapped by hand on a tool
        val perLayer = MultiFilament.purgesPerLayer(order, toolOf, startTool, loadedOn)
        val tower = if (perLayer.all { it == 0 }) null else purgeTower(setup, perLayer, tops)
        // temperatures per spool: the material x tool values of a part on that spool
        val partsOf = setup.objects[objectId].parts
        val bySpool = setup.spools.associate { sp -> sp.id to partsOf.indexOfFirst { it.spool == sp.id }.takeIf { it >= 0 }?.let { SliceSettings.of(setup, plymeld.model.Context(objectId, it)) } }
        val tempOf = { sp: String, layer: Int -> bySpool[sp]?.let { if (layer == 0) it.nozzleTempFirstC else it.nozzleTempC } }
        val drop = if (perLayer.all { it == 0 }) null else (Resolver.resolveOrNull(setup, "hardware.filament.temperature.idle_drop", ctx)?.value as Number?)?.toInt()
        val idleOf = { sp: String, layer: Int -> tempOf(sp, layer)?.let { t -> drop?.takeIf { it > 0 }?.let { t - it } } }
        val steps = MultiFilament.steps(r, order, toolOf, obj.name, tops, startTool, tower, tempOf, idleOf, supSpool, itfSpool, loadedOn = loadedOn, purgeInto = s!!.purgeInto)
        return PrintProgram(if (islands.isEmpty()) steps else withIslandLayers(steps, setup), listOf(obj))
    }

    /**
     * [steps] (an object laid per part) with its curved islands' own layers in, by height as [plannedLayers] puts them: each
     * before the first layer topping above it, a layer of its own (its own layer start, marked), laid with the tool of its
     * parts' filament, which must be the tool printing there (a tool change for an island is not written yet). Layer starts
     * renumbered in print order.
     */
    private fun withIslandLayers(steps: List<Step>, setup: Setup): List<Step> {
        val stage = islandsStage.get()
        val spools = partSpools()
        val queue = stage.flatMap { il -> il.layers.map { it to il } }.sortedBy { it.first.topZUm }
        val out = ArrayList<Step>(steps.size + queue.size * 8)
        var q = 0; var idx = 0; var tool: Int? = null
        val last = doubleArrayOf(Double.NaN, Double.NaN)
        fun emit(l: LayerPlanOut, il: IslandLayers) {
            islandOf[l] = il.k
            val t = setup.spool(il.part?.let { spools[it] } ?: setup.objects[objectId].spool).tool
            require(tool == null || tool == t) { "'$objectName': curved island '${islandKeyOf(l)}' is on tool T$t, where T$tool prints: a tool change for an island is not written yet" }
            val (z, ls) = layerSteps(l, t, last)
            out += LayerStart(idx++, z, l.heightUm, objectLayer = l.index, island = islandKeyOf(l))
            out += ObjectStart(obj.name); out.addAll(ls); out += ObjectEnd(obj.name)
            tool = t
        }
        for (st in steps) {
            if (st is LayerStart) {
                while (q < queue.size && queue[q].first.topZUm < st.zUm) { emit(queue[q].first, queue[q].second); q++ }
                out += LayerStart(idx++, st.zUm, st.heightUm, st.objectLayer, st.archedBridge, st.printTemp, st.island)
                continue
            }
            if (st is Extrude) tool = st.tool
            if (st is plymeld.program.ToolChange) tool = st.tool
            out += st
        }
        while (q < queue.size) { emit(queue[q].first, queue[q].second); q++ }
        return out
    }

    /**
     * The purge tower from the intent's tower settings (read only here: prints without filament
     * changes never need them). Purge lines are the nozzle's width (as bridges); spare bands use the
     * intent's own sparse spacing. The tower must stand on the bed clear of the object.
     */
    fun purgeTower(setup: Setup, perLayer: IntArray, tops: LongArray,
                   /** The plate's other objects (Job): the tower stands beside all of them, clear of each. */
                   others: List<plymeld.program.PrintObject> = emptyList()): PurgeTower {
        fun r(id: String) = Resolver.resolve(setup, id, ctx).value
        require(r("plan.mechanism.purge.tower.enabled") as Boolean) { "this print changes filament ${perLayer.sum()} times but the purge tower is off (plan.mechanism.purge.tower.enabled); purging elsewhere is not implemented" }
        val st = s!!
        val um = { id: String -> Math.round((r(id) as Number).toDouble() * 1000) }
        val sparseSpacing = Math.round(Walls.spacing(st.sparseWidthUm, st.layerHeightUm) / st.sparseDensity * (if (st.sparsePattern == "grid") 2 else 1))
        val layout = r("plan.mechanism.purge.tower.layout") as String
        require(layout == "compact" || layout == "banded") { "plan.mechanism.purge.tower.layout '$layout' is not known (compact, banded)" }
        // the tower's gap between visits is bounded by the thickest layer the tools in use print
        val maxGap = setup.objects[objectId].parts.indices.minOf { p ->
            Math.round((Resolver.resolve(setup, "hardware.tool.layer.height.max", plymeld.model.Context(objectId, p)).value as Number).toDouble() * 1000) }
        // the width: another slicer's when set (investigation), else plymeld's rule, a square tower
        val towerW = Resolver.resolveOrNull(setup, "plan.mechanism.purge.tower.width", ctx)?.let { Math.round((it.value as Number).toDouble() * 1000) }
        val purge = (r("hardware.filament.purge.volume") as Number).toDouble()
        // each side at least a share of its height, and a brim on the first layer
        val minSide = (Resolver.resolveOrNull(setup, "plan.mechanism.purge.tower.side_share.min", ctx)?.value as Number?)?.toDouble() ?: 0.0
        val brim = if (Resolver.resolveOrNull(setup, "plan.mechanism.purge.tower.brim.enabled", ctx)?.value == true)
            Resolver.resolveOrNull(setup, "plan.mechanism.purge.tower.brim.width", ctx)?.let { Math.round((it.value as Number).toDouble() * 1000) } ?: 0L else 0L
        val brimOverlap = if (brim <= 0) 0L else
            Resolver.resolveOrNull(setup, "plan.mechanism.purge.tower.brim.overlap", ctx)?.let { Math.round((it.value as Number).toDouble() * 1000) } ?: 0L
        // the tower's first layer, its brim included, no faster than its own first-layer speed
        val firstSpeed = (Resolver.resolveOrNull(setup, "plan.mechanism.purge.tower.first_layer.speed", ctx)?.value as Number?)?.toDouble()
        fun build(x: Long, y: Long) = if (towerW != null) PurgeTower(x, y, towerW, purge, st.nozzleDiameterUm, sparseSpacing, perLayer, tops,
                compact = layout == "compact", maxGapUm = maxGap, minSideShare = minSide, brimUm = brim, brimLineUm = st.firstLayerWidthUm, brimOverlapUm = brimOverlap,
                firstLineUm = st.firstLayerWidthUm, firstLayerSpeed = firstSpeed)
            else PurgeTower.square(x, y, purge, st.nozzleDiameterUm, sparseSpacing, perLayer, tops,
                compact = layout == "compact", maxGapUm = maxGap, minSideShare = minSide, brimUm = brim, brimLineUm = st.firstLayerWidthUm, brimOverlapUm = brimOverlap,
                firstLineUm = st.firstLayerWidthUm, firstLayerSpeed = firstSpeed)
        val bx = listOf("hardware.machine.build.left", "hardware.machine.build.front", "hardware.machine.build.right", "hardware.machine.build.back").map { um(it) }
        val hulls = (listOf(obj) + others).map { o -> plymeld.geom.Polygon(LongArray(o.footprint.size) { Math.round(o.footprint[it] * 1000) }).let { if (it.isCcw()) it else it.reversed() } }
        // beside the object; on a plate, beside the box round every object
        val hull = if (others.isEmpty()) hulls[0] else hulls.map { it.bounds() }.let { bs ->
            val x0 = bs.minOf { it[0] }; val y0 = bs.minOf { it[1] }; val x1 = bs.maxOf { it[2] }; val y1 = bs.maxOf { it[3] }
            plymeld.geom.Polygon(longArrayOf(x0, y0, x1, y0, x1, y1, x0, y1)) }
        val setX = Resolver.resolveOrNull(setup, "plan.mechanism.purge.tower.x", ctx)
        val setY = Resolver.resolveOrNull(setup, "plan.mechanism.purge.tower.y", ctx)
        require((setX == null) == (setY == null)) {
            "plan.mechanism.purge.tower.${if (setX == null) "y" else "x"} is set without plan.mechanism.purge.tower.${if (setX == null) "x" else "y"}: set both, or neither for a tower beside the object" }
        val t = if (setX != null) build(um("plan.mechanism.purge.tower.x"), um("plan.mechanism.purge.tower.y")) else {
            // beside the object, where its first layer holds as the object's does: a tower placed at the bed's corner, far from
            // the object, peeled there twice (the bed's height off its middle). Its right, back, left, then front, centered
            // on the object along that side (moved along it to stay on the bed), [gap] clear of the object and its brim
            val gap = um("plan.mechanism.purge.tower.gap")
            // the object's brim where the planner lays one (Planner: outer_only, a width above 0)
            val objBrim = if (st.brimType == "outer_only" && st.brimWidthUm > 0) st.brimGapUm + st.brimWidthUm else 0L
            val probe = build(0, 0).footprint.bounds()                    // the tower at (0, 0): its footprint around it, brim included
            val w = probe[2] - probe[0]; val d = probe[3] - probe[1]
            val ob = hull.bounds(); val clear = gap + objBrim
            val cx = (ob[0] + ob[2]) / 2; val cy = (ob[1] + ob[3]) / 2
            fun along(c: Long, size: Long, lo: Long, hi: Long) = (c - size / 2).coerceIn(lo, maxOf(lo, hi - size))
            val sides = listOf(
                "right" to (ob[2] + clear to along(cy, d, bx[1], bx[3])),
                "back" to (along(cx, w, bx[0], bx[2]) to ob[3] + clear),
                "left" to (ob[0] - clear - w to along(cy, d, bx[1], bx[3])),
                "front" to (along(cx, w, bx[0], bx[2]) to ob[1] - clear - d))
            val fits = sides.firstOrNull { (_, at) -> at.first >= bx[0] && at.first + w <= bx[2] && at.second >= bx[1] && at.second + d <= bx[3] }
                ?: error("no room for the purge tower (%.1f x %.1f mm, its brim included) %.1f mm beside '%s' on the bed: set its place (plan.mechanism.purge.tower.x / .y) or a smaller gap (plan.mechanism.purge.tower.gap)"
                    .format(java.util.Locale.ROOT, w / 1e3, d / 1e3, clear / 1e3, objectName))
            build(fits.second.first - probe[0], fits.second.second - probe[1])
        }
        val f = t.footprint
        require(f.xy.withIndex().all { (k, v) -> if (k % 2 == 0) v in bx[0]..bx[2] else v in bx[1]..bx[3] }) {
            f.bounds().let { fb -> "purge tower (%.1f..%.1f, %.1f..%.1f mm, its brim included) is off the bed".format(java.util.Locale.ROOT, fb[0] / 1e3, fb[2] / 1e3, fb[1] / 1e3, fb[3] / 1e3) } }
        for ((k, h) in hulls.withIndex()) require(plymeld.geom.Booleans.intersection(listOf(f), listOf(h)).isEmpty()) {
            f.bounds().let { fb -> "purge tower at (%.1f, %.1f), %.1f x %.1f mm (its brim included), overlaps '%s': move the tower (plan.mechanism.purge.tower.x / plan.mechanism.purge.tower.y)".format(java.util.Locale.ROOT,
                fb[0] / 1e3, fb[1] / 1e3, (fb[2] - fb[0]) / 1e3, (fb[3] - fb[1]) / 1e3, if (k == 0) objectName else others[k - 1].name) } }
        return t
    }

    /**
     * The planar-layer planner's output as a [PrintProgram]: per layer, the object ends
     * (from the second layer on), the layer starts, the object starts, then the layer's extrusions, all with the
     * tool of the object's one spool (the loadout's tool for its filament).
     */
    private fun program(planned: List<LayerPlanOut>, setup: Setup): PrintProgram {
        val tool = setup.spool(partSpools().first()).tool
        val steps = ArrayList<Step>()
        val last = doubleArrayOf(Double.NaN, Double.NaN)
        for (l in planned) {
            if (l.index > 0) steps.add(ObjectEnd(obj.name))
            val (z, ls) = layerSteps(l, tool, last)
            steps.add(LayerStart(l.index, z, l.heightUm, archedBridge = isArchedBridgeLayer(l), printTemp = isBridgePrintTempLayer(l), island = islandKeyOf(l)))
            steps.add(ObjectStart(obj.name))
            steps.addAll(ls)
        }
        if (planned.isNotEmpty()) steps.add(ObjectEnd(obj.name))
        return PrintProgram(steps, listOf(obj))
    }

    /**
     * One planned layer [l] as steps printed with [tool], and the layer's top for its [LayerStart] (µm above
     * the object's bottom). A flat layer: its extrusions at its top. A curved layer:
     * each path back from warped space with its own Z and thickness per vertex, each travel into a path lifted
     * above the layer surface it crosses (plus the tool's z-hop); its top, the highest Z in it. [last]: the
     * head's last XY (mm), read and updated.
     */
    fun layerSteps(l: LayerPlanOut, tool: Int, last: DoubleArray): Pair<Long, List<Step>> {
        val (z, steps) = layerStepsAsPlanned(l, tool, last)
        return z to MotionRegions.apply(steps, regionBoxes)
    }

    /**
     * The object's regions (TechniquePlan.regions) in the frame its paths are in: x and y placed, z from the object's bottom,
     * µm; the lines each holds by their feature, and its caps.
     */
    private val regionBoxes: List<MotionRegions.Box> by lazy {
        val b = soup.bounds()
        technique.regions.filter { it.speedMax != null || it.accelMax != null }.map { r -> val g = r.region
            MotionRegions.Box(r.key, (b[0] + g.x0) * 1000, (b[0] + g.x1) * 1000, (b[1] + g.y0) * 1000, (b[1] + g.y1) * 1000, g.fromMm * 1000, g.toMm * 1000,
                r.facing?.let { MotionRegions.features(it) }, r.speedMax, r.accelMax)
        }
    }

    /** [layerSteps] before the regions' values: the layer's steps as planned. */
    private fun layerStepsAsPlanned(l: LayerPlanOut, tool: Int, last: DoubleArray): Pair<Long, List<Step>> {
        val out = ArrayList<Step>()
        fun endOf(xy: LongArray, closed: Boolean) { val m = xy.size / 2; val i = if (closed) 0 else m - 1; last[0] = xy[2 * i] / 1000.0; last[1] = xy[2 * i + 1] / 1000.0 }
        val warp = warpOf(l)
        // several lifted bridges: this one's anchors are what it is judged arched against
        if (banded) bandOf[l]?.let { supportArea = bandsStage.get()[it].anchors }
        val bottom = soup.bounds()[2]
        val topW = bottom + l.topZUm / 1000.0
        if (warp == null || topW <= warp.zStart + 1e-9) {
            // Z anti-aliasing (ZAntiAlias): a flat layer above the first, its lines lowered onto the model's top within it
            val st = s!!
            val zaa = st.zAntialias && !underside && l.topZUm - l.heightUm > 0
            val cols = if (zaa) columns(st) else null
            val min = if (zaa) minLayerMm() else 0.0
            var below = false                       // the head left a lowered path below the layer's top
            for (p in l.paths) {
                val low = cols?.let { ZAntiAlias.lower(p, l.topZUm, l.heightUm, tool, it, bottom, min, st.resolutionUm / 1000.0) }
                // from a lowered end, back up to the layer's top before moving on (a short or hidden travel would otherwise
                // run sideways through the layer's lines at the lowered height)
                if (below && (p.xy[0] / 1000.0 != last[0] || p.xy[1] / 1000.0 != last[1])) out.add(LiftTo(l.topZUm))
                if (low != null) {
                    out.add(low); endOf(low.xy, p.closed)
                    val lz = low.zs!!
                    below = lz[if (p.closed) 0 else lz.size - 1] < l.topZUm
                } else { out.addAll(Scarf.extrudes(p, l.topZUm + p.raiseUm, tool)); endOf(p.xy, p.closed); below = false }
            }
            waveRamp(l, out)
            return l.topZUm to out
        }
        val bottomW = topW - l.heightUm / 1000.0
        val tol = s!!.resolutionUm / 1000.0
        val hop = s!!.zHopUm / 1000.0
        val bottomUm = Math.round(bottom * 1000)
        var layerTop = l.topZUm
        // a lifted bridge's layer: which one of the bridge's (1 = its first), and its tacks to the first (tacks)
        val lift = warp as? plymeld.mesh.LiftWarp
        val bridgeLayer = if (lift == null) 0 else Math.round((topW - lift.zStart) / (l.heightUm / 1000.0)).toInt()
        if (bridgeLayer == 1) { tackRef.clear(); arched = false }
        // a waved first layer (plan.technique.bridge.first_layer = "wave_overhang"): its beads run across the span, and the walls
        // over the gap give way to them, so no strand is left to say it arches; the beads' own heights do (an arch's climb
        // from its anchors, a flat bridge's none)
        var waveLow = Long.MAX_VALUE; var waveHigh = Long.MIN_VALUE
        if (lift != null && bridgeLayer >= 2 && bridgeLayer <= 1 + s!!.tackLayers) {
            for (t in tacks(lift, bridgeLayer, l.heightUm, topW, bottom, bottomUm, tool)) { out.add(t); layerTop = maxOf(layerTop, t.zUm); endOf(t.xy, false) }
        }
        // the waved layer's last bead laid (its xy, as printed): the start of the next crossing (wave.crossing, wave.wait)
        var prevBead: LongArray? = null
        // the span between the bridge's two supports: which beads run across it, and where along it (wave.crossing = "away",
        // wave.wait_zones); null without two supports
        val span = if (bridgeLayer == 1) Span.of(supportArea) else null
        val awayUm = if (bridgeLayer == 1 && s!!.bridgeWaveCrossAway)
            s!!.bridgeWaveAwayUm ?: error("plan.technique.bridge.wave.crossing = \"away\" needs plan.technique.bridge.wave.away.distance (how far from the object the head goes)") else 0L
        // the object's outline seen from above, as a box (µm): what the head stays wave.away.distance from
        val box = soup.bounds().let { b -> longArrayOf(Math.round(b[0] * 1000), Math.round(b[1] * 1000), Math.round(b[3] * 1000), Math.round(b[4] * 1000)) }
        /** The wait after bead [xy] (wave.wait_zones by where its middle lies along the span, else wave.wait). */
        fun waitAfter(xy: LongArray): Double {
            val zones = s!!.bridgeWaveWaitZones
            if (zones.isEmpty() || span == null) return s!!.bridgeWaveWaitS
            val f = span.share(xy)
            return zones.firstOrNull { (a, b, _) -> f >= a && f < b || b >= 1.0 && f >= 1.0 }?.third ?: zones.minBy { (a, b, _) -> minOf(Math.abs(f - a), Math.abs(f - b)) }.third
        }
        for (p in l.paths) {
            // a path laid above its own layer (ToolPath.raiseUm: a second waved layer laid with the first) unwarps at that layer
            val tW = topW + p.raiseUm / 1000.0
            val u = CurvedLayers.unwarp(p.xy, p.closed, p.widths, p.overhangShare, warp, tW - l.heightUm / 1000.0, tW, tol)
            val shift = warp as? plymeld.mesh.UndersideShift
            val bead = bridgeLayer == 1 && p.feature == Feature.WAVE && !p.closed && p.afterLayer == null
            val pb = prevBead
            // a crossing between the sides beside the bridge (plan.technique.bridge.wave.crossing = "beside"): out past the
            // span's edge from the last bead's end, along it, and in to this bead's start, each step the edge's way out of
            // that bead (a bead runs across the span, so its ends are at the edges)
            // away from the object between two beads across the span (plan.technique.bridge.wave.crossing = "away"):
            // out along the last bead to the far point, its wait there, round the object, in along this bead
            val awayRoute = if (awayUm <= 0 || !bead || p.waveFill || pb == null || span == null || !span.across(pb) || !span.across(u.xy)) null
                else crossingAway(pb, u.xy, awayUm, box)
            val via = awayRoute?.second ?: if (shift != null && l.index == 0 || !bead || p.waveFill || pb == null || !s!!.bridgeWaveCrossBeside) null
                else crossingBeside(pb, u.xy, s!!.bridgeWaveCrossClearanceUm)
            if (!last[0].isNaN()) {
                // over the whole way the head goes: through the crossing's points when it takes them (a crossing
                // between two edge strips ran 6 mm into the span at the foot's height, under the strips already laid)
                val route = ArrayList<Double>().apply { add(last[0]); add(last[1]); awayRoute?.first?.forEach { add(it / 1000.0) }; via?.forEach { add(it / 1000.0) }; add(u.xy[0] / 1000.0); add(u.xy[1] / 1000.0) }
                var over = Double.NEGATIVE_INFINITY
                for (k in 0 until route.size / 2 - 1)
                    over = maxOf(over, CurvedLayers.highestAlong(warp, route[2 * k], route[2 * k + 1], route[2 * k + 2], route[2 * k + 3], topW, TRAVEL_SAMPLE_MM))
                // a second pass (wave.second_pass.zones): lifted by its approach, so the head comes straight down onto each line's
                // start from that far above, never from a side
                val liftMm = if (p.afterLayer != AfterLayer.SECOND_PASS) over + hop else maxOf(over, u.zs.max() / 1000.0) +
                    (s!!.bridgeWaveSecondPassApproachUm ?: error("plan.technique.bridge.wave.second_pass.zones needs plan.technique.bridge.wave.second_pass.approach")) / 1000.0
                out.add(LiftTo(Math.round(liftMm * 1000) - bottomUm))
            }
            val zs = LongArray(u.zs.size) { u.zs[it] - bottomUm }
            if (shift != null && l.index == 0) out.addAll(overAir(p, u, zs, shift, tool))
            // a path laid above its own layer is named so in the file (its checks judge it against that layer)
            else {
                // a wait before every bead but the first (plan.technique.bridge.wave.wait, or by zone: wave.wait_zones): at the
                // far point when the head goes away between them (wave.crossing = "away"), else lifted over the bead's start;
                // by zone only between two beads across the span (not before an edge piece)
                val wait = if (!bead || pb == null) 0.0 else if (s!!.bridgeWaveWaitZones.isNotEmpty() && (span == null || !span.across(pb) || !span.across(u.xy))) 0.0 else waitAfter(pb)
                if (awayRoute != null) out.add(plymeld.program.Park(awayRoute.first[0], awayRoute.first[1], wait))
                else if (wait > 0) out.add(plymeld.program.Park(u.xy[0], u.xy[1], wait))
                val e0 = Extrude(if (p.afterLayer != null) p.afterLayer.label else if (p.raiseUm > 0 && p.feature == Feature.WAVE) WAVE_ABOVE else p.feature.label, u.xy, p.closed, zs.max(), p.widthUm, u.heights.max(), tool, u.overhangShare, p.ePerMmOverride, p.retractBefore,
                    u.widths, zs = zs, heights = u.heights, hopOnly = p.hopOnly)
                // a line over the waved layer at its own speed (wave.second_pass.speed, wave.x_layer.speed, wave.span_layer.speed), every segment
                val passSpeed = when (p.afterLayer) {
                    null -> null
                    AfterLayer.SECOND_PASS -> s!!.bridgeWaveSecondPassSpeed ?: error("plan.technique.bridge.wave.second_pass.zones needs plan.technique.bridge.wave.second_pass.speed")
                    AfterLayer.X_LAYER -> s!!.bridgeWaveXLayerSpeed ?: error("plan.technique.bridge.wave.x_layer.enabled needs plan.technique.bridge.wave.x_layer.speed")
                    AfterLayer.SPAN_LAYER -> s!!.bridgeWaveSpanLayerSpeed ?: error("plan.technique.bridge.wave.span_layer.enabled needs plan.technique.bridge.wave.span_layer.speed")
                }
                val e = if (passSpeed == null) e0 else e0.withSegmentSpeeds(DoubleArray(u.xy.size / 2 - 1) { passSpeed })
                out.add(if (via != null) e.withVia(via) else e)
                if (bead) prevBead = u.xy
            }
            // the bridge's first layer's bridge lines: what the tacks of the layers above are laid against
            // (and its walls: a wall loop's sides across the gap are strands too, the bridge's outer edges)
            if (bridgeLayer == 1 && (p.feature == Feature.BRIDGE || p.feature == Feature.OUTER_WALL || p.feature == Feature.INNER_WALL)) {
                val m = u.xy.size / 2
                if (p.closed && m > 2) tackRef += strands(LongArray(2 * m + 2) { u.xy[it % (2 * m)] }, LongArray(m + 1) { zs[it % m] }, p.widthUm)
                else tackRef += strands(u.xy, zs, p.widthUm)
            }
            if (bridgeLayer == 1 && p.feature == Feature.WAVE) { waveLow = minOf(waveLow, zs.min()); waveHigh = maxOf(waveHigh, zs.max()) }
            layerTop = maxOf(layerTop, zs.max())
            endOf(u.xy, p.closed)
        }
        // the bridge's first layer laid: whether it arches (its own speeds and temperature apply only then)
        if (bridgeLayer == 1) arched = tackRef.any { archedStrand(it, l.heightUm) } || (waveHigh > waveLow && waveHigh - waveLow > l.heightUm / 2)
        // an arched bridge's speed over air: known once the first layer has said it arches
        if (lift != null && bridgeLayer >= 1 && arched && !(s!!.bridgeTopNormal && bridgeTops.containsKey(l))) {
            // past the ramp every segment says "usual" (NaN): left without speeds, the writer would lay the whole layer at the
            // bridge's print speed, its earlier rule (seen in the test: the strip's fifth layer all at 5 mm/s)
            val v = bridgeAirSpeed(bridgeLayer) ?: Double.NaN
            val groups = plymeld.geom.Polygons.group(supportArea)
            // wave beads keep the technique's own speed (plan.technique.wave_overhang.speed), not the bridge's over air
            // (and the lines over the waved layer theirs: AfterLayer)
            for (i in out.indices) (out[i] as? Extrude)?.takeIf { it.feature != Feature.WAVE.label && it.feature != WAVE_ABOVE && AfterLayer.entries.none { a -> a.label == it.feature } }?.let { e -> out[i] = e.withSegmentSpeeds(segmentSpeeds(e, v, groups)) }
        }
        return layerTop to out
    }

    /**
     * Each flat layer's wave beads (Feature.WAVE paths), by planned layer index, from the paths stage: worked out again
     * whenever that stage's result changes (the slicer is kept across edits: SliceSession).
     */
    private var waveBeadsOf: List<LayerPlanOut>? = null
    private var waveBeadsMap: Map<Int, List<ToolPath>> = emptyMap()
    private val waveBeads: Map<Int, List<ToolPath>> get() {
        if (!s!!.waveOverhang) return emptyMap()
        val ls = pathsStage.get()
        if (ls !== waveBeadsOf) {
            waveBeadsMap = ls.associate { l -> l.index to l.paths.filter { it.feature == Feature.WAVE } }.filterValues { it.isNotEmpty() }
            waveBeadsOf = ls
        }
        return waveBeadsMap
    }

    /**
     * The ramp over a wave overhang, back to the usual speed over a few layers, the same ramp as an arched bridge's: the
     * beads at their own speed
     * (plan.technique.wave_overhang.speed), then, in the flat layers above, each segment over the beads (its middle within a
     * bead width of one) at an arched bridge's whole sequence over air: plan.technique.bridge.print.speed, then its ramp
     * (ramp_speeds, ramp_growth; from the beads' speed when print.speed is unset), the nearest wave layer below deciding; the
     * rest at the usual speeds. Applied to [out], layer [l]'s steps.
     */
    private fun waveRamp(l: LayerPlanOut, out: ArrayList<Step>) {
        val st = s!!
        // no beads (the surface technique off): nothing to ramp over, and its values are not read (a setup without them has none)
        if (waveBeads.isEmpty()) return
        val ramp = st.bridgePrintSpeed?.let { listOf(minOf(it, st.bridgeSpeed)) + st.bridgeRamp(it) } ?: st.bridgeRamp(st.waveSpeed)
        val r = ramp.size
        if (r == 0) return
        val below = (1..r).mapNotNull { d -> waveBeads[l.index - d]?.let { d to it } }
        if (below.isEmpty()) return
        val reach = st.nozzleDiameterUm
        val grids = below.map { (d, beads) -> d to SegmentGrid(beads, reach) }
        for (i in out.indices) {
            val e = out[i] as? Extrude ?: continue
            val m = e.xy.size / 2
            val count = if (e.closed) m else m - 1
            var any = false
            val v = DoubleArray(count) { k ->
                val j = (k + 1) % m
                val mx = (e.xy[2 * k] + e.xy[2 * j]) / 2; val my = (e.xy[2 * k + 1] + e.xy[2 * j + 1]) / 2
                val d = grids.firstOrNull { it.second.near(mx, my) }?.first
                if (d == null) Double.NaN else { any = true; ramp[d - 1] }
            }
            if (any) out[i] = e.withSegmentSpeeds(v)
        }
    }

    /** The segments of [paths], bucketed on a grid of [reachUm] cells: whether a point lies within [reachUm] of one. */
    private class SegmentGrid(paths: List<ToolPath>, private val reachUm: Long) {
        private val cells = HashMap<Long, MutableList<LongArray>>()
        private fun key(cx: Long, cy: Long) = cx * 1_000_003L + cy
        init {
            for (p in paths) {
                val m = p.xy.size / 2
                val count = if (p.closed) m else m - 1
                for (k in 0 until count) {
                    val j = (k + 1) % m
                    val seg = longArrayOf(p.xy[2 * k], p.xy[2 * k + 1], p.xy[2 * j], p.xy[2 * j + 1])
                    val x0 = Math.floorDiv(minOf(seg[0], seg[2]) - reachUm, reachUm); val x1 = Math.floorDiv(maxOf(seg[0], seg[2]) + reachUm, reachUm)
                    val y0 = Math.floorDiv(minOf(seg[1], seg[3]) - reachUm, reachUm); val y1 = Math.floorDiv(maxOf(seg[1], seg[3]) + reachUm, reachUm)
                    for (cx in x0..x1) for (cy in y0..y1) cells.getOrPut(key(cx, cy)) { ArrayList() } += seg
                }
            }
        }
        fun near(x: Long, y: Long): Boolean {
            val list = cells[key(Math.floorDiv(x, reachUm), Math.floorDiv(y, reachUm))] ?: return false
            val r2 = reachUm.toDouble() * reachUm
            return list.any { s ->
                val dx = (s[2] - s[0]).toDouble(); val dy = (s[3] - s[1]).toDouble()
                val len2 = dx * dx + dy * dy
                val t = if (len2 == 0.0) 0.0 else (((x - s[0]) * dx + (y - s[1]) * dy) / len2).coerceIn(0.0, 1.0)
                val px = s[0] + t * dx - x; val py = s[1] + t * dy - y
                px * px + py * py <= r2
            }
        }
    }

    /**
     * An arched bridge's speed over air in its layer [j] (1 = its first; e.g. 4, 6, 8, 12, 24, then
     * doubling up to the bridge speed, then the usual): plan.technique.bridge.print.speed on the first, then the ramp
     * (plan.technique.bridge.ramp_speeds, ramp_growth: [SliceSettings.bridgeRamp]); null = the usual speeds (after the ramp, or no
     * print.speed set).
     */
    private fun bridgeAirSpeed(j: Int): Double? {
        val st = s!!
        val slow = st.bridgePrintSpeed ?: return null
        return if (j == 1) slow else st.bridgeRamp(slow).getOrNull(j - 2)
    }

    /**
     * Each segment of [e] at [v] where it is over air, the usual speed (NaN) where it is over what holds the bridge up
     * ([supportArea], seen from above; [groups] its outer loops with their holes), judged at the segment's middle (a
     * strip's ends on what holds it up at their usual speeds).
     */
    private fun segmentSpeeds(e: Extrude, v: Double, groups: List<Pair<plymeld.geom.Polygon, List<plymeld.geom.Polygon>>>): DoubleArray {
        val m = e.xy.size / 2
        val count = if (e.closed) m else m - 1
        return DoubleArray(count) { k ->
            val j = (k + 1) % m
            val mx = (e.xy[2 * k] + e.xy[2 * j]) / 2; val my = (e.xy[2 * k + 1] + e.xy[2 * j + 1]) / 2
            val held = groups.any { (o, hs) -> plymeld.geom.Polygons.contains(o, mx, my) && hs.none { plymeld.geom.Polygons.contains(it, mx, my) } }
            if (held) Double.NaN else v
        }
    }

    /** Whether planned layer [l] is one of a lifted bridge's (above what holds it up, in its lifted band). */
    fun isBridgeLayer(l: LayerPlanOut): Boolean {
        if (banded) return bandOf.containsKey(l)
        val lift = warpFor(s!!) as? plymeld.mesh.LiftWarp ?: return false
        return soup.bounds()[2] + l.topZUm / 1000.0 > lift.zStart + 1e-9
    }

    /**
     * Whether planned layer [l] is one of an ARCHED lifted bridge's (the bridge's own speeds and temperature
     * for arched bridges, concave up or down; the straight ones were good before). Known once the bridge's
     * first layer has been through [layerSteps] (which comes first for every layer).
     */
    fun isArchedBridgeLayer(l: LayerPlanOut): Boolean = arched && isBridgeLayer(l) && !(s!!.bridgeTopNormal && bridgeTops.containsKey(l))

    /**
     * Whether planned layer [l] is an ARCHED lifted bridge's top layer printed as usual (plan.technique.bridge.top_layer = normal): the
     * usual speeds and the print temperature. Known as [isArchedBridgeLayer].
     */
    fun isBridgePrintTempLayer(l: LayerPlanOut): Boolean = arched && s!!.bridgeTopNormal && bridgeTops.containsKey(l)

    /** Each lifted bridge's top layer (its last, when it has more than one), from [plannedLayers]. */
    private val bridgeTops = java.util.IdentityHashMap<LayerPlanOut, Boolean>()

    /** What a lifted bridge rests on, seen from above (its supports' outline), from the paths stage. */
    private var supportArea: List<plymeld.geom.Polygon> = emptyList()
    /** Whether this object's lifted bridge arches (judged on its first layer, [archedStrand]). */
    private var arched = false

    /**
     * Whether strand [r] of a lifted bridge's first layer arches: between each two of its points over a support (where it rests),
     * somewhere more than half a layer ([hUm] / 2) above or below the straight line between them. A span that is straight from
     * support to support (flat, a ramp, a valley's spans onto its post) does not; an arch over a gap does, and so does a sag.
     */
    private fun archedStrand(r: TackRef, hUm: Long): Boolean {
        // grown by half a line, so the point where the line leaves a support's edge counts as resting on it
        val groups = plymeld.geom.Polygons.group(plymeld.geom.Offset.offset(supportArea, r.widthUm / 2))
        val m = r.xy.size / 2
        val over = BooleanArray(m) { i -> groups.any { (o, hs) -> plymeld.geom.Polygons.contains(o, r.xy[2 * i], r.xy[2 * i + 1]) && hs.none { plymeld.geom.Polygons.contains(it, r.xy[2 * i], r.xy[2 * i + 1]) } } }
        var a = (0 until m).firstOrNull { over[it] } ?: return false
        while (true) {
            val b = (a + 1 until m).firstOrNull { over[it] } ?: return false
            if (b > a + 1) {
                val ax = r.xy[2 * a].toDouble(); val ay = r.xy[2 * a + 1].toDouble()
                val len = Math.hypot(r.xy[2 * b] - ax, r.xy[2 * b + 1] - ay)
                for (i in a + 1 until b) {
                    val t = if (len == 0.0) 0.0 else Math.hypot(r.xy[2 * i] - ax, r.xy[2 * i + 1] - ay) / len
                    if (Math.abs(r.zs[i] - (r.zs[a] + (r.zs[b] - r.zs[a]) * t)) > hUm / 2.0) return true
                }
            }
            a = b
        }
    }

    /** A bridge line of a lifted bridge's first layer: its points (µm), its Z at each (µm above the object's bottom), its width. */
    private class TackRef(val xy: LongArray, val zs: LongArray, val widthUm: Long)
    private val tackRef = ArrayList<TackRef>()

    /**
     * The strands of a bridge path ([xy], [zs]): its runs one way, split where it turns by more than 45 degrees (a bridge fill
     * zig-zags: long lines across the gap joined by short steps); runs shorter than four line widths (the steps) left out.
     */
    private fun strands(xy: LongArray, zs: LongArray, widthUm: Long): List<TackRef> {
        val m = xy.size / 2
        val out = ArrayList<TackRef>()
        var a = 0
        while (a < m - 1) {
            val dx0 = (xy[2 * a + 2] - xy[2 * a]).toDouble(); val dy0 = (xy[2 * a + 3] - xy[2 * a + 1]).toDouble()
            val l0 = Math.hypot(dx0, dy0)
            var b = a + 1
            while (b < m - 1 && l0 > 0) {
                val dx = (xy[2 * b + 2] - xy[2 * b]).toDouble(); val dy = (xy[2 * b + 3] - xy[2 * b + 1]).toDouble()
                val l = Math.hypot(dx, dy)
                if (l > 0 && (dx * dx0 + dy * dy0) / (l * l0) < Math.cos(Math.PI / 4)) break
                b++
            }
            val len = Math.hypot((xy[2 * b] - xy[2 * a]).toDouble(), (xy[2 * b + 1] - xy[2 * a + 1]).toDouble())
            if (len >= 4.0 * widthUm) out += TackRef(LongArray(2 * (b - a + 1)) { xy[2 * a + it] }, LongArray(b - a + 1) { zs[a + it] }, widthUm)
            a = b
        }
        return out
    }

    /**
     * The tacks of a lifted bridge's layer [k] (2 or more) to its first: started lower than the layer would
     * start, a little plastic laid to fuse with the first layer, then up to the layer's height. The first layer's bridge
     * lines are tacked, its two outermost across the bridge first, then lines `plan.technique.bridge.tack.spacing` apart inward. On each, at
     * the point where it stands furthest above the straight line between its ends (where it would lie pulled taut between its
     * anchors), a path `plan.technique.bridge.tack.length` long along it at the height layer [k] would have over a first layer pulled straight
     * (that straight line plus k - 1 layers, never above the layer itself), then rising over `plan.technique.bridge.tack.rise` to the layer's
     * own height. A line standing less than half a layer above straight gets none. Printed before the layer's own paths, as
     * "Bridge tack" (the bridge's speed and flow).
     */
    private fun tacks(lift: plymeld.mesh.LiftWarp, k: Int, hUm: Long, topW: Double, bottom: Double, bottomUm: Long, tool: Int): List<Extrude> {
        val st = s!!
        if (tackRef.isEmpty() || st.tackLengthUm <= 0) return emptyList()
        fun ends(r: TackRef): DoubleArray { val m = r.xy.size / 2; return doubleArrayOf(r.xy[0].toDouble(), r.xy[1].toDouble(), r.xy[2 * (m - 1)].toDouble(), r.xy[2 * (m - 1) + 1].toDouble()) }
        // the lines' mean direction (each turned to agree with the first), and across it
        var dx = 0.0; var dy = 0.0
        for (r in tackRef) { val e = ends(r); var ex = e[2] - e[0]; var ey = e[3] - e[1]; if (ex * dx + ey * dy < 0) { ex = -ex; ey = -ey }; dx += ex; dy += ey }
        val dl = Math.hypot(dx, dy)
        if (dl == 0.0) return emptyList()
        dx /= dl; dy /= dl
        fun across(r: TackRef): Double { val e = ends(r); return -(e[0] + e[2]) / 2 * dy + (e[1] + e[3]) / 2 * dx }
        val sorted = tackRef.sortedBy { across(it) }
        val picked = ArrayList<TackRef>()
        picked += sorted.first()
        if (sorted.size > 1) picked += sorted.last()
        var lastA = across(sorted.first()); val endA = across(sorted.last())
        for (r in sorted.drop(1).dropLast(1)) { val a = across(r); if (a - lastA >= st.tackSpacingUm && endA - a >= st.tackSpacingUm) { picked += r; lastA = a } }
        val out = ArrayList<Extrude>()
        for (r in picked) {
            val m = r.xy.size / 2
            val e = ends(r)
            val len = Math.hypot(e[2] - e[0], e[3] - e[1])
            if (len == 0.0) continue
            val ux = (e[2] - e[0]) / len; val uy = (e[3] - e[1]) / len
            fun along(i: Int) = (r.xy[2 * i] - e[0]) * ux + (r.xy[2 * i + 1] - e[1]) * uy
            fun straight(t: Double) = r.zs[0] + (r.zs[m - 1] - r.zs[0]) * (t / len)
            var best = -1; var dev = 0.0
            for (i in 0 until m) { val d = r.zs[i] - straight(along(i)); if (d > dev) { dev = d; best = i } }
            if (best < 0 || dev < hUm / 2.0) continue
            val tm = along(best)
            val t0 = (tm - st.tackLengthUm / 2.0).coerceIn(0.0, len); val t1 = (tm + st.tackLengthUm / 2.0).coerceIn(0.0, len)
            val t2 = (t1 + st.tackRiseUm).coerceIn(0.0, len)
            if (t1 - t0 < 1.0 || t2 - t1 < 1.0) continue
            val ts = doubleArrayOf(t0, t1, t2)
            val xy = LongArray(6) { val t = ts[it / 2]; Math.round(if (it % 2 == 0) e[0] + ux * t else e[1] + uy * t) }
            // the layer's own height at each point
            val layerZ = LongArray(3) { Math.round((lift.unwarp(xy[2 * it] / 1000.0, xy[2 * it + 1] / 1000.0, topW) - bottom) * 1000) }
            val zs = longArrayOf(minOf(Math.round(straight(t0)) + (k - 1) * hUm, layerZ[0]), minOf(Math.round(straight(t1)) + (k - 1) * hUm, layerZ[1]), layerZ[2])
            out += Extrude("Bridge tack", xy, false, zs.max(), r.widthUm, hUm, tool, zs = zs, heights = LongArray(3) { hUm })
        }
        return out
    }

    /**
     * Layers that follow the underside: the first layer [p] (back from warped space as [u], its Z [zs]) split where it
     * leaves the bed. Its stretches over a hollow (the underside above the bed by more than [AIR_MM] under their middle)
     * print in the air along the underside: bridges, every segment fully over
     * air, so the writer gives them the bridge's speed and flow and the overhang fan; the stretches on the bed stay as
     * planned. The stretches follow each other with no travel between them.
     */
    private fun overAir(p: ToolPath, u: CurvedLayers.Unwarped, zs: LongArray, shift: plymeld.mesh.UndersideShift, tool: Int): List<Step> {
        val n = u.xy.size / 2
        val segs = if (p.closed) n else n - 1
        val air = BooleanArray(segs) { k -> val j = (k + 1) % n
            shift.lift((u.xy[2 * k] + u.xy[2 * j]) / 2000.0, (u.xy[2 * k + 1] + u.xy[2 * j + 1]) / 2000.0) > AIR_MM }
        fun piece(idx: IntArray, isAir: Boolean, closed: Boolean, retract: Boolean): Extrude {
            val xy = LongArray(2 * idx.size) { val v = idx[it / 2]; u.xy[2 * v + it % 2] }
            val z = LongArray(idx.size) { zs[idx[it]] }; val h = LongArray(idx.size) { u.heights[idx[it]] }
            val w = u.widths?.let { ws -> LongArray(idx.size) { ws[idx[it]] } }
            val sh = if (isAir) IntArray(idx.size) { 1000 } else u.overhangShare?.let { a -> IntArray(idx.size) { a[idx[it]] } }
            // walls keep their name (they are still the outline's walls, slowed and cooled by their over-air shares); the
            // fill over air is a bridge
            val wall = p.feature == Feature.OUTER_WALL || p.feature == Feature.INNER_WALL
            return Extrude(if (isAir && !wall) Feature.BRIDGE.label else p.feature.label, xy, closed, z.max(), p.widthUm, h.max(), tool, sh, p.ePerMmOverride, retract, w, zs = z, heights = h, hopOnly = retract && p.hopOnly)
        }
        if (air.all { it } || air.none { it }) return listOf(piece(IntArray(n) { it }, air.all { it }, p.closed, p.retractBefore))
        // runs of segments of one kind, a closed loop's starting where the kind changes
        val start = if (p.closed) (0 until segs).first { air[it] != air[(it - 1 + segs) % segs] } else 0
        val out = ArrayList<Step>()
        var k = 0
        while (k < segs) {
            val s0 = (start + k) % segs; val kind = air[s0]
            var len = 1
            while (k + len < segs && air[(start + k + len) % segs] == kind) len++
            out += piece(IntArray(len + 1) { (s0 + it) % n }, kind, false, if (out.isEmpty()) p.retractBefore else false)
            k += len
        }
        return out
    }

    /** Layers in the last slice. */
    val layerCount: Int get() = sliceStage.get().first.size

    /** This object's planned layers for `setup` (cached stages re-run only as needed). */
    /**
     * The slice and walls stages alone, for a decision made before the slice (the seam decision): the same stages
     * with the same cache keys, so the slice that follows finds its walls made (the decision sliced the
     * concatenated soup, uncleaned, and walled it on its own: 436 s of a 30-part model's 757 s plan, against the slice's
     * own walls in under 150 s). Returns each layer's outline as cut, the walls (with their effective outlines), and the
     * layer tops (µm above the object's bottom); a banded plan's flat layers only (its bands are built with the slice).
     */
    fun wallsForDecision(setup: Setup): Triple<List<List<plymeld.geom.Polygon>>, Planner.WallsOut, LongArray> {
        this.setup = setup
        s = SliceSettings.of(setup, ctx)
        ran.clear(); stageMs.clear()
        val sl = sliceStage.get()
        return Triple(sl.first.map { it.loops }, wallsStage.get(), sl.second)
    }

    fun planned(setup: Setup): List<LayerPlanOut> {
        this.setup = setup
        s = SliceSettings.of(setup, ctx)
        ran.clear(); stageMs.clear()
        require(!multiFilament) { "object '$objectName' uses several filaments or part settings (${partUnits().distinct().joinToString()}): it is laid per part (plannedFilaments, laidForPlate), not as one object's layers" }
        return decideRetraction(plannedLayers(), setup).also { lastPlanned = it }
    }

    /** The in-island retraction decision of the last [slice] or [planned] (RetractionDecision); null before one, or for a multi-filament object. */
    var retractionDecision: plymeld.core.decide.Decision<Boolean>? = null; private set

    /**
     * The in-island retraction decided on [planned]: where plan.mechanism.motion.no_retract_in_island is
     * unset, both sides are measured on this object's own program (StringRisk: the planner's hidden marks kept, or cleared so
     * those travels retract like any other; the infill rules' marks stay either way) and weighed by the object's weights; the
     * layers come back as decided. Set: a lock, recorded, the layers as the planner marked them.
     */
    private fun decideRetraction(planned: List<LayerPlanOut>, setup: Setup): List<LayerPlanOut> {
        val st = s!!
        val lockE = Resolver.resolveOrNull(setup, "plan.mechanism.motion.no_retract_in_island", ctx)
        val weights = Resolver.resolve(setup, "job.priorities", ctx).let { plymeld.core.decide.Weights.parse(it.value as String, it.from) }
        val off = planned.map { l -> LayerPlanOut(l.index, l.topZUm, l.heightUm, l.paths.map { if (it.islandHidden) it.withRetract(true) else it }) }
        val margin = st.detourMarginUm ?: plymeld.gcode.TravelRouter.MARGIN_UM
        // each side's program made here, one after the other; its routing and measure (the slow part, the program not
        // changed by them) both sides at once
        fun measured(p: plymeld.program.PrintProgram): plymeld.gcode.StringRisk.Result {
            val routed = if (st.avoidCrossing) plymeld.gcode.TravelRouter.route(p, margin, st.detourMaxRatio ?: plymeld.gcode.TravelRouter.MAX_DETOUR_RATIO, st.detourMaxExtraUm ?: plymeld.gcode.TravelRouter.MAX_DETOUR_EXTRA_UM, st.detourInsetUm,
                st.detourSpeckMaxRatio ?: plymeld.gcode.TravelRouter.SPECK_MAX_RATIO, st.detourSpeckMaxExtraUm ?: plymeld.gcode.TravelRouter.SPECK_MAX_EXTRA_UM) else p
            return plymeld.gcode.StringRisk.measure(routed, st.retractMinTravelMm, st.restartLagMm, margin)
        }
        val retractS = plymeld.core.decide.RetractionDecision.retractS(st.retractMm, st.retractSpeed, st.deretractSpeed, st.zHopUm / 1000.0, st.travelSpeed)
        // a quick print time the retractions' seconds are scored against: the paths' lengths at their speeds (visible lines at
        // the visible speed, the rest at the hidden), no minimum layer time, no travel
        val visible = setOf(Feature.OUTER_WALL, Feature.TOP, Feature.BOTTOM)
        var timeS = 0.0
        for (l in planned) for (p in l.paths) {
            val lenMm = (Planner.polylineLength(p.xy) + if (p.closed && p.xy.size >= 4) Math.hypot((p.xy[0] - p.xy[p.xy.size - 2]).toDouble(), (p.xy[1] - p.xy[p.xy.size - 1]).toDouble()) else 0.0) / 1000.0
            val v = if (p.feature in visible || (p.feature == Feature.INNER_WALL && st.innerWallsAsVisible)) st.visibleSpeed else st.hiddenSpeed
            if (v > 0) timeS += lenMm / v
        }
        val d = if (lockE != null) locked(lockE, weights, retractS)
            else timed("retraction") {
                val programs = listOf(program(planned, setup), program(off, setup))
                val (kept, retracting) = Parallel.map(2) { measured(programs[it]) }
                plymeld.core.decide.RetractionDecision.decide(objectName, kept, retracting, retractS, timeS,
                    "a quick estimate from the paths' lengths and speeds", weights, null, null) }
        retractionDecision = d
        if (d.value) return planned
        // the alternative is new layer objects: the lifted bridges' bookkeeping keys on the layer (bandOf, bridgeTops), so it
        // follows them (an arched bridge's top lost its print-temperature rise when the retracting side won)
        for ((o, n) in planned.zip(off)) { bandOf[o]?.let { bandOf[n] = it }; bridgeTops[o]?.let { bridgeTops[n] = it }; islandOf[o]?.let { islandOf[n] = it } }
        return off
    }

    private fun locked(lockE: plymeld.model.Resolved, weights: plymeld.core.decide.Weights, retractS: Double): plymeld.core.decide.Decision<Boolean> =
        plymeld.core.decide.RetractionDecision.decide(objectName, plymeld.gcode.StringRisk.Result.NONE, plymeld.gcode.StringRisk.Result.NONE, retractS, null, "",
            weights, lockE.value as Boolean, lockE.from)

    /**
     * The in-island retraction decision for [setup] where plan.mechanism.motion.no_retract_in_island locks it (a plan file
     * does), without planning the layers: the decision [planned] would record, which measures nothing when locked. Null
     * where it is unset (the decision is weighed on the planned layers).
     */
    fun lockedRetraction(setup: Setup): plymeld.core.decide.Decision<Boolean>? {
        val lockE = Resolver.resolveOrNull(setup, "plan.mechanism.motion.no_retract_in_island", ctx) ?: return null
        val st = SliceSettings.of(setup, ctx)
        val weights = Resolver.resolve(setup, "job.priorities", ctx).let { plymeld.core.decide.Weights.parse(it.value as String, it.from) }
        val retractS = plymeld.core.decide.RetractionDecision.retractS(st.retractMm, st.retractSpeed, st.deretractSpeed, st.zHopUm / 1000.0, st.travelSpeed)
        return locked(lockE, weights, retractS).also { retractionDecision = it }
    }

    /** The layers the last [slice] or [planned] laid out (empty for a multi-filament object: its layers are per filament). */
    var lastPlanned: List<LayerPlanOut> = emptyList(); private set
    /** string_risk and restart_starved of the last [slice]'s program as written (StringRisk); null before one. */
    var lastStrings: plymeld.gcode.StringRisk.Result? = null; private set

    /**
     * The seam measure of [lastPlanned] (SeamScar): seam_scar as seen, with the config's visibility map and scarf
     * share for this object. An object laid per part: every filament's paths of a layer as one layer, its curved islands'
     * layers among them by height as [plannedLayers] puts them (not measured there before, so a plan's full
     * prediction of such an object had no seam measure, and the curved border, scored on its scale, dropped out unsaid).
     */
    fun seamScar(): SeamScar.Result? = setup?.let { st ->
        val (ls, stack) = if (multiFilament) multiLayers() else lastPlanned to islandOf
        SeamScar.measure(ls, plymeld.core.decide.Visibility.from(st, ctx), plymeld.core.decide.Visibility.seamShares(st, ctx),
            stackOf = if (islands.isEmpty()) null else { i -> stack[ls[i]] ?: -1 })
    }

    /**
     * An object laid per part as one list of layers (each layer's paths of every filament, its islands' layers in by height)
     * and each island layer's island; the slicer's own island marks ([islandOf], the writer's) are left as they are.
     */
    private fun multiLayers(): Pair<List<LayerPlanOut>, Map<LayerPlanOut, Int>> {
        val (r, tops) = sectioned(filamentsStage.get())
        val flat = tops.indices.map { i -> LayerPlanOut(i, tops[i], r.filaments.firstOrNull()?.layers?.get(i)?.heightUm ?: 0L, r.filaments.flatMap { it.layers[i].paths }) }
        val stack = HashMap<LayerPlanOut, Int>()
        if (islands.isEmpty()) return flat to stack
        val queue = islandsStage.get().flatMap { il -> il.layers.map { it to il.k } }.sortedBy { it.first.topZUm }
        val out = ArrayList<LayerPlanOut>()
        fun add(l: LayerPlanOut, k: Int?) { val n = LayerPlanOut(out.size, l.topZUm, l.heightUm, l.paths); if (k != null) stack[n] = k; out += n }
        var q = 0
        for ((i, l) in flat.withIndex()) {
            add(l, null)
            val next = flat.getOrNull(i + 1)?.topZUm ?: Long.MAX_VALUE
            while (q < queue.size && queue[q].first.topZUm < next) { add(queue[q].first, queue[q].second); q++ }
        }
        while (q < queue.size) { add(queue[q].first, queue[q].second); q++ }
        return out to stack
    }

    /** Pauses to insert ([Pauses]); part names resolve through [partRangesMm] (each part's placed Z range). */
    var pauses: List<PauseLock> = emptyList()
    var partRangesMm: Map<String, DoubleArray> = emptyMap()
    /** Where each of [pauses] landed, after the last [slice]. */
    var pausesLanded: List<String> = emptyList(); private set

    /** Where each filament swapped by hand was placed (Pauses.placeSwaps), after the last [slice]. */
    var swapsLanded: List<String> = emptyList(); private set

    private fun withPauses(p: PrintProgram): PrintProgram = Pauses.insert(p, pauses, partRangesMm).let { r -> pausesLanded = r.landed; swapsLanded = r.swaps; r.program }

    /** Re-slices with `setup`; returns the stages that actually ran (g-code writing always does). */
    fun slice(setup: Setup, out: Writer): List<String> {
        this.setup = setup
        s = SliceSettings.of(setup, ctx)
        ran.clear(); stageMs.clear()
        if (multiFilament) {
            val program = withPauses(multiProgram(setup))
            val preheat = preheatS(setup, ctx, s!!)
            timed("gcode") { val w = KlipperWriter(s!!, setup.foreignTemplateVars, version, preheatS = preheat, pressureAdvance = setup.pressureAdvanceByTool(), progress = progress,
                perObjectTool = toolSettings(setup, objectId, printObject.name)); w.write(program, out); out.flush(); lastStrings = w.strings }
            return ran.toList()
        }
        val planned = decideRetraction(plannedLayers(), setup)
        lastPlanned = planned
        timed("gcode") { val w = KlipperWriter(s!!, setup.foreignTemplateVars, version, pressureAdvance = setup.pressureAdvanceByTool(), progress = progress); w.write(withPauses(program(planned, setup)), out); out.flush(); lastStrings = w.strings }
        return ran.toList()
    }

    /**
     * The detour of a crossing beside the bridge from the bead [from] (its xy, as laid) to the bead [to]: [clearUm] past the
     * last bead's end along its own direction, then the same past this bead's start against its direction; null when the
     * two ends are close (a hop within one side, not a crossing: under 5 mm).
     */
    private fun crossingBeside(from: LongArray, to: LongArray, clearUm: Long): LongArray? {
        val n = from.size / 2
        val ex = from[2 * n - 2]; val ey = from[2 * n - 1]
        val sx = to[0]; val sy = to[1]
        if (Math.hypot((sx - ex).toDouble(), (sy - ey).toDouble()) < 5000.0 || clearUm <= 0) return null
        fun unit(ax: Long, ay: Long, bx: Long, by: Long): DoubleArray { val l = Math.hypot((bx - ax).toDouble(), (by - ay).toDouble()); return if (l == 0.0) doubleArrayOf(0.0, 0.0) else doubleArrayOf((bx - ax) / l, (by - ay) / l) }
        val d0 = unit(from[2 * n - 4], from[2 * n - 3], ex, ey)
        val m = to.size / 2
        val d1 = unit(sx, sy, to[2], to[3])
        return longArrayOf(ex + Math.round(d0[0] * clearUm), ey + Math.round(d0[1] * clearUm), sx - Math.round(d1[0] * clearUm), sy - Math.round(d1[1] * clearUm)).also { check(m >= 2) }
    }

    /**
     * The way away from the object between the bead [from] (its xy, as laid) and the bead [to] (plan.technique.bridge.wave.crossing
     * = "away"): the far point, out from the last bead's end along its own direction to [awayUm] from the object's box [box]
     * (min x, min y, max x, max y, µm); then the corners of that box grown by [awayUm], the shorter way round, and the point
     * on it from which [to]'s start lies straight ahead along its direction. First: the far point (x y); second: the rest.
     */
    private fun crossingAway(from: LongArray, to: LongArray, awayUm: Long, box: LongArray): Pair<LongArray, LongArray> {
        val x0 = (box[0] - awayUm).toDouble(); val y0 = (box[1] - awayUm).toDouble(); val x1 = (box[2] + awayUm).toDouble(); val y1 = (box[3] + awayUm).toDouble()
        fun unit(ax: Long, ay: Long, bx: Long, by: Long): DoubleArray { val l = Math.hypot((bx - ax).toDouble(), (by - ay).toDouble()); return if (l == 0.0) doubleArrayOf(0.0, 0.0) else doubleArrayOf((bx - ax) / l, (by - ay) / l) }
        // from (px, py), inside the grown box, along (dx, dy) to its edge
        fun toEdge(px: Double, py: Double, dx: Double, dy: Double): DoubleArray {
            var t = Double.POSITIVE_INFINITY
            if (dx > 1e-9) t = minOf(t, (x1 - px) / dx); if (dx < -1e-9) t = minOf(t, (x0 - px) / dx)
            if (dy > 1e-9) t = minOf(t, (y1 - py) / dy); if (dy < -1e-9) t = minOf(t, (y0 - py) / dy)
            require(t.isFinite() && t >= 0) { "plan.technique.bridge.wave.crossing = \"away\": a bead with no direction" }
            return doubleArrayOf(px + t * dx, py + t * dy)
        }
        val n = from.size / 2
        val d0 = unit(from[2 * n - 4], from[2 * n - 3], from[2 * n - 2], from[2 * n - 1])
        val out = toEdge(from[2 * n - 2].toDouble(), from[2 * n - 1].toDouble(), d0[0], d0[1])
        val d1 = unit(to[0], to[1], to[2], to[3])
        val inn = toEdge(to[0].toDouble(), to[1].toDouble(), -d1[0], -d1[1])
        // round the grown box: each point's place along its edge, counter-clockwise from (x0, y0)
        val w = x1 - x0; val h = y1 - y0; val per = 2 * (w + h)
        fun place(p: DoubleArray): Double {
            val dist = doubleArrayOf(Math.abs(p[1] - y0), Math.abs(p[0] - x1), Math.abs(p[1] - y1), Math.abs(p[0] - x0))
            return when (dist.indices.minBy { dist[it] }) { 0 -> p[0] - x0; 1 -> w + (p[1] - y0); 2 -> w + h + (x1 - p[0]); else -> 2 * w + h + (y1 - p[1]) }
        }
        val corners = listOf(0.0 to doubleArrayOf(x0, y0), w to doubleArrayOf(x1, y0), w + h to doubleArrayOf(x1, y1), 2 * w + h to doubleArrayOf(x0, y1))
        val a = place(out); val b = place(inn)
        val ccw = ((b - a) % per + per) % per
        val way = if (ccw <= per - ccw) corners.filter { (c, _) -> ((c - a) % per + per) % per in 1e-6..ccw - 1e-6 }.sortedBy { (c, _) -> ((c - a) % per + per) % per }
            else corners.filter { (c, _) -> ((a - c) % per + per) % per in 1e-6..(per - ccw) - 1e-6 }.sortedBy { (c, _) -> ((a - c) % per + per) % per }
        val rest = way.flatMap { (_, p) -> listOf(Math.round(p[0]), Math.round(p[1])) } + listOf(Math.round(inn[0]), Math.round(inn[1]))
        return longArrayOf(Math.round(out[0]), Math.round(out[1])) to rest.toLongArray()
    }

    /**
     * A waved bridge's span between its two supports (the anchors seen from above): the axis from the first support's center
     * (in order of x then y) to the second's, and where along it the gap runs (from the first support's inner edge, [t0], to
     * the second's, [t1]), to tell the beads across the span and where along it they lie.
     */
    private class Span(private val cx: Double, private val cy: Double, private val ax: Double, private val ay: Double, private val t0: Double, private val t1: Double) {
        fun t(x: Double, y: Double) = (x - cx) * ax + (y - cy) * ay
        /** Where bead [xy]'s middle (between its ends) lies along the gap: 0 at the first support's inner edge, 1 at the second's. */
        fun share(xy: LongArray): Double { val n = xy.size / 2; return (t((xy[0] + xy[2 * n - 2]) / 2.0, (xy[1] + xy[2 * n - 1]) / 2.0) - t0) / (t1 - t0) }
        /** Whether bead [xy] runs across the span (end to end nearer across than along). */
        fun across(xy: LongArray): Boolean {
            val n = xy.size / 2; val dx = (xy[2 * n - 2] - xy[0]).toDouble(); val dy = (xy[2 * n - 1] - xy[1]).toDouble(); val l = Math.hypot(dx, dy)
            return l > 0 && Math.abs(dx * ax + dy * ay) / l < 0.5
        }
        companion object {
            fun of(area: List<plymeld.geom.Polygon>): Span? {
                val g = plymeld.geom.Polygons.group(area).map { it.first }
                    .sortedWith(compareBy({ p -> p.bounds().let { it[0] + it[2] } }, { p -> p.bounds().let { it[1] + it[3] } }))
                if (g.size != 2) return null
                fun c(p: plymeld.geom.Polygon) = p.bounds().let { doubleArrayOf((it[0] + it[2]) / 2.0, (it[1] + it[3]) / 2.0) }
                val a = c(g[0]); val b = c(g[1]); val l = Math.hypot(b[0] - a[0], b[1] - a[1])
                if (l <= 0) return null
                val ax = (b[0] - a[0]) / l; val ay = (b[1] - a[1]) / l
                fun t(x: Long, y: Long) = (x - a[0]) * ax + (y - a[1]) * ay
                val t0 = (0 until g[0].size).maxOf { t(g[0].x(it), g[0].y(it)) }
                val t1 = (0 until g[1].size).minOf { t(g[1].x(it), g[1].y(it)) }
                return if (t1 > t0) Span(a[0], a[1], ax, ay, t0, t1) else null
            }
        }
    }

    companion object {
        /**
         * The file's name for a wave bead laid one layer above its own (a lifted bridge's second waved layer laid with its first,
         * plan.technique.bridge.wave.layers = "2"): its checks judge it against the layer above, and it prints as a wave bead.
         */
        const val WAVE_ABOVE = "Wave overhang, layer above"

        /**
         * Object [objectIndex]'s settings for the paths of each tool its parts print with other than the object's own (the
         * writer's perObjectTool, keyed by the object's program [name] and the 0-based tool): the first such part's settings,
         * so its filament's flow, melt rate, retraction, and the rest (every part printed with the first part's).
         */
        /**
         * How long (s, print time) the next tool is heated before a change: the tool's hardware.tool.<key>.heat.time, else its
         * machine model's toolheads' (the longest of them: heated early rather than late); null = no preheat.
         */
        fun preheatS(setup: Setup, ctx: plymeld.model.Context, s: SliceSettings): Double? =
            (Resolver.resolveOrNull(setup, "hardware.tool.heat.time", ctx)?.value as Number?)?.toDouble()
                ?: s.machineDescription.toolheads.mapNotNull { it.heatS }.maxOrNull()

        fun toolSettings(setup: Setup, objectIndex: Int, name: String): Map<Pair<String, Int>, SliceSettings> {
            val o = setup.objects[objectIndex]; val own = setup.spool(o.spool).tool
            return o.parts.withIndex().map { (pi, p) -> setup.spool(p.spool).tool to pi }.filter { it.first != own }.distinctBy { it.first }
                .associate { (tool, pi) -> (name to tool) to SliceSettings.of(setup, plymeld.model.Context(objectIndex, pi)) }
        }
        /**
         * The program's name for object [objectName] of setup index [objectId], as the g-code's object markers carry it
         * (Klipper's exclude-object naming, `<name>_id_<n>_copy_0`, spaces as underscores).
         */
        fun programName(objectName: String, objectId: Int) = "${objectName.replace(Regex("\\s+"), "_")}_id_${objectId}_copy_0"
        /** Whether a program name ([programName]) names object [objectName], at any setup index. */
        fun namesObject(programName: String, objectName: String) =
            Regex(Regex.escape(objectName.replace(Regex("\\s+"), "_")) + "_id_\\d+_copy_\\d+").matches(programName)
        /**
         * A triangle belongs to the top surface when its normal's Z share is above this: a numerical guard that
         * keeps vertical walls (share 0) out, not a physical value; any slope a curved layer prints is far above it.
         */
        const val UP_NZ = 1e-3
        /**
         * How often a travel inside a curved layer samples the layer surface under it for its lift (mm). plymeld's
         * choice: fine against the curves a curved layer allows (a slope of a few degrees changes Z by
         * a few hundredths of a mm over 0.5 mm); the lift adds the tool's z-hop on top.
         */
        const val TRAVEL_SAMPLE_MM = 0.5
        /** How many grid points a side [reachesUnderside] samples a region with, at most: a numerical sampling, not a physical value. */
        const val RESTS_SAMPLES = 40.0
        /** The finest step it samples with (µm): finer than a line, so a support narrower than one is still found. */
        const val RESTS_STEP_UM = 200.0
        /**
         * Layers that follow the underside: a first-layer stretch whose underside is this far above the bed (mm) is over
         * air, a bridge. plymeld's choice: anything clear of the bed by more than the file's rounding.
         */
        const val AIR_MM = 0.01
        /** A part of a lifted bridge whose lowest point is this far above the object's bottom is the bridge (mm). */
        const val LIFT_PART_MM = 0.01

        /** Several parts as one soup. */
        fun merged(ps: List<TriangleSoup>): TriangleSoup = if (ps.size == 1) ps[0] else {
            val c = FloatArray(ps.sumOf { it.count } * 9); var w = 0
            for (p in ps) { System.arraycopy(p.coords, 0, c, w, p.count * 9); w += p.count * 9 }
            TriangleSoup(c, ps.sumOf { it.count })
        }
        val SLICE_KEYS = arrayOf("plan.technique.flat.layer.height", "plan.mechanism.adhesion.first_layer.height", "method.slicing.tolerance", "method.slicing.close_gaps",
            "hardware.tool.layer.height.min", "hardware.tool.layer.height.max", "plan.technique.flat.step.max", "plan.technique.flat.height_ratio", "plan.faces.sloped_top.z_antialiasing", "hardware.machine.motion.z.step")
        val WALL_KEYS = arrayOf("hardware.plate.first_layer.squish", "plan.layer.walls.count", "plan.layer.walls.outer.line.width", "plan.layer.walls.inner.line.width", "plan.mechanism.adhesion.first_layer.line.width",
            "plan.layer.walls.style", "plan.layer.walls.line.width.min", "plan.layer.walls.feature.min", "plan.layer.walls.length_factor.min", "plan.layer.walls.transition_length",
            "plan.layer.sliver.width")
        val REGION_KEYS = arrayOf("plan.layer.top.thickness", "plan.layer.bottom.thickness",
            "plan.layer.infill.density", "plan.layer.infill.area.min", "plan.layer.top.under_parts")
        val PATH_KEYS = arrayOf(
            "plan.mechanism.adhesion.brim.where", "plan.mechanism.adhesion.brim.width", "plan.mechanism.adhesion.brim.gap",
            "plan.layer.walls.order", "plan.seam", "plan.technique.corner_seam.corner_angle",
            "plan.view_from", "method.quality.visibility.under", "method.quality.visibility.bed", "method.quality.visibility.away", "method.quality.seam.corner_share",
            "method.quality.seam.scarf_share",
            "method.quality.visibility.sample.azimuths", "method.quality.visibility.sample.elevations", "method.quality.visibility.sample.elevation.max",
            "method.quality.visibility.sample.reach", "method.quality.visibility.sample.cell", "method.quality.visibility.show_through",
            "method.quality.visibility.quadrant.front", "method.quality.visibility.quadrant.left", "method.quality.visibility.quadrant.right", "method.quality.visibility.quadrant.back",
            "plan.technique.scarf_seam.where", "plan.technique.scarf_seam.length", "plan.technique.scarf_seam.start",
            "plan.layer.infill.density", "plan.layer.infill.pattern", "plan.layer.infill.chord_tolerance", "plan.layer.top.pattern", "plan.layer.bottom.pattern",
            "plan.technique.flat.core.layers", "hardware.tool.layer.height.max",
            "plan.layer.infill.line.width", "plan.layer.solid.line.width",
            "plan.layer.top.line.width", "plan.layer.infill.narrow.cells", "plan.layer.infill.narrow.fill", "plan.layer.infill.angle", "plan.layer.infill.wall_overlap", "plan.layer.infill.anchor_length.max",
            "hardware.tool.bore",
            "plan.support.enabled", "hardware.filament.overhang.share", "plan.support.gap.side", "plan.support.gap.top.value", "plan.support.gap.top.min", "plan.support.gap.bottom", "plan.support.column.width",
            "plan.support.interface.top_layers", "plan.support.interface.bottom_layers", "plan.support.spacing",
            "plan.support.interface.spacing", "plan.support.from_bed_only", "plan.support.line.width", "plan.support.angle",
            "plan.support.tree.branch_angle", "plan.support.tree.tip_diameter", "plan.support.tree.branch_diameter", "plan.support.tree.widening", "plan.support.tree.tip_spacing",
            "plan.technique.fuzzy_skin.where", "plan.technique.fuzzy_skin.thickness", "plan.technique.fuzzy_skin.point_distance", "plan.technique.fuzzy_skin.first_layer", "plan.technique.fuzzy_skin.mode", "plan.technique.fuzzy_skin.noise",
            "plan.technique.ironing.type", "plan.technique.ironing.spacing", "plan.technique.ironing.flow", "plan.technique.ironing.inset", "plan.technique.ironing.angle", "hardware.filament.diameter", "hardware.filament.flow",
            "plan.mechanism.motion.no_retract_in_island",
            // wave overhangs: the undersides' surface technique and the technique's own values the paths read
            "plan.faces.under.wave_overhang", "plan.technique.wave_overhang.spacing", "plan.technique.wave_overhang.overlap", "plan.technique.wave_overhang.neck.min",
            "plan.technique.wave_overhang.area.min", "plan.technique.wave_overhang.line.area",
            // its height limit: left out, a slice with the limit reused another's waved layers (seen in the test)
            "plan.faces.under.from", "plan.faces.under.to",
            // a lifted bridge's first layer as waves: left out, a slice with lines reused the waved one's paths (seen in the test)
            "plan.technique.bridge.first_layer", "plan.technique.bridge.wave.sides", "plan.technique.bridge.wave.edges.order", "plan.technique.bridge.wave.edges.enabled", "plan.technique.bridge.wave.layers", "plan.technique.bridge.wave.second_layer", "plan.technique.bridge.wave.x_length", "plan.technique.bridge.wave.start", "plan.technique.bridge.wave.strip.width", "plan.technique.bridge.wave.front", "plan.technique.curved.top_order", "plan.technique.bridge.wave.chevron.lead", "plan.technique.bridge.wave.chevron.leading", "plan.technique.bridge.wave.chevron.lines", "plan.technique.bridge.wave.after_supported", "plan.technique.bridge.wave.line.direction", "plan.technique.bridge.wave.spacing", "plan.technique.bridge.wave.second_pass.zones", "plan.technique.bridge.wave.x_layer.enabled", "plan.technique.bridge.wave.x_layer.raise", "plan.technique.bridge.wave.x_layer.share", "plan.technique.bridge.wave.x_layer.inset", "plan.technique.bridge.wave.x_layer.middle", "plan.technique.bridge.wave.span_layer.enabled", "plan.technique.bridge.wave.span_layer.raise",
            // a lifted bridge's fill all along it: read by the paths stage (bridgeAlong), left out earlier, so a re-slice
            // after changing it reused the other value's paths (ConfiguredValueReachesTest; the layer cache keyed it itself)
            "plan.technique.bridge.fill_along", "plan.technique.bridge.fill_angles",
        )
    }
}
