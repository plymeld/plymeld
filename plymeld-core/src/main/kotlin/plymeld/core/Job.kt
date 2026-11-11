package plymeld.core

import plymeld.program.Progress
import plymeld.core.decide.Layout
import plymeld.core.decide.ObjectOrder
import plymeld.gcode.KlipperWriter
import plymeld.model.Setup
import plymeld.model.pressureAdvanceByTool
import plymeld.model.SliceSettings
import plymeld.program.LayerStart
import plymeld.program.LiftTo
import plymeld.program.ObjectEnd
import plymeld.program.ObjectStart
import plymeld.program.PrintProgram
import plymeld.program.Step
import java.io.Writer

/**
 * A plate of objects. Each object is planned by its own techniques — flat
 * layers of one height or following the surfaces, curved bands, undersides, lifted bridges — by its own [IncrementalSlicer] with its settings resolved for it — and the plate's
 * object order decides how their steps are put together into one [PrintProgram]:
 *
 *   together        each object's own layers (its own heights, its own order: a lifted bridge's after what holds it up)
 *                   merged by height: at each step, the object whose next layer tops lowest
 *   one at a time   each object complete, in the layout's order; between objects the head lifts
 *                   clear of every finished object before moving on
 *
 * A plate with an object on several filaments (or part settings) is put together per filament instead ([perPartProgram]):
 * on each layer each filament lays its lines of every object, so the plate changes tool as often as one object would.
 */
class Job(val layout: Layout, setup: Setup, private val version: String) {
    private var setup: Setup = setup.also(::check)

    private fun check(s: Setup) {
        require(layout.placed.size == s.objects.size) { "${layout.placed.size} placed objects but ${s.objects.size} in the setup" }
        // one spool for the plate's objects: tool changes are written WITHIN an object (its parts'
        // filaments, MultiFilament), not yet between objects
        val spools = s.objects.map { it.spool }.distinct()
        require(spools.size == 1) { "objects on this plate use ${spools.joinToString()} (" + s.objects.joinToString { "${it.name}: ${it.spool}" } +
            "); changing spool or tool between objects on a plate is not written yet (within one object's parts it is)" }
    }

    /**
     * The same plate with changed settings (same layout): each object's slicer keeps its stages and
     * re-runs only those whose settings changed (incremental re-slicing). A changed layout needs a new Job.
     */
    fun update(newSetup: Setup) { check(newSetup); setup = newSetup }

    /** Stage timings of the last write, per object (stage name -> ms). */
    val stageMs: List<Map<String, Long>> get() = slicers.map { it.stageMs.toMap() }
    /** The seam measure of the plate's last write (SeamScar): every object's summed; null when an object could not be measured. */
    fun seamScar(): SeamScar.Result? = slicers.map { it.seamScar() ?: return null }.let { SeamScar.sum(it) }
    /** Where the stages report and look for a cancel; handed to every object's slicer and the writer. */
    var progress: Progress = Progress.NONE
        set(v) { field = v; slicers.forEach { it.progress = v } }

    /** Each placed object is sliced with ITS setup object's settings (print order is not setup order). */
    private val slicers = layout.placed.mapIndexed { k, p -> IncrementalSlicer(p.parts, version, p.name, layout.objectIndex[k], layout.techniques[k]) }

    fun program(): PrintProgram {
        val planned = slicers.map { it.planned(setup) }
        val names = slicers.map { it.printObject.name }
        // each object prints with the tool of its spool (the loadout's tool for its filament)
        val tools = layout.objectIndex.map { setup.spool(setup.objects[it].spool).tool }
        val steps = ArrayList<Step>()
        // each object's head position (mm) for its curved layers' travel lifts
        val last = Array(slicers.size) { doubleArrayOf(Double.NaN, Double.NaN) }
        fun paths(k: Int, built: List<Step>) {
            steps.add(ObjectStart(names[k]))
            steps.addAll(built)
            steps.add(ObjectEnd(names[k]))
        }
        val zHopUm = SliceSettings.of(setup).zHopUm
        // support columns finished before the model's layers beside them (Coordination.Order.FIRST):
        // each column's support paths are taken out of the object's layers and printed, bottom to top, just before the
        // first layer of the object that reaches the column's height; then the head lifts clear of the column
        val columns: List<Column> = slicers.indices.flatMap { k -> slicers[k].firstColumns().map { Column(k, it) } }
        val stripped: List<List<LayerPlanOut>> = planned.mapIndexed { k, ls -> ls.map { l ->
            val mine = columns.filter { it.k == k }
            if (mine.isEmpty()) l else {
                val rest = ArrayList<ToolPath>(l.paths.size)
                for (p in l.paths) { val c = mine.firstOrNull { it.takes(p, l) }; if (c == null) rest += p else c.layers.getOrPut(l.index) { Pair(l, ArrayList()) }.second += p }
                slicers[k].sameLayer(LayerPlanOut(l.index, l.topZUm, l.heightUm, rest), l)
            }
        } }
        var idx = 0
        val pending = columns.filter { it.layers.isNotEmpty() }.toMutableList()
        /** Columns whose lowest support layer is layer [index] of object [k] (or below it): printed now, each bottom to top, then the head lifted clear. */
        fun columnsBefore(k: Int, index: Int) {
            val due = pending.filter { it.k == k && it.layers.keys.min() <= index }
            for (c in due) {
                pending.remove(c)
                var top = 0L
                for ((_, e) in c.layers.toSortedMap()) {
                    val (l, ps) = e
                    val (z, b) = slicers[k].layerSteps(LayerPlanOut(l.index, l.topZUm, l.heightUm, ps), tools[k], last[k])
                    steps.add(LayerStart(idx++, z, l.heightUm, objectLayer = l.index, island = slicers[k].islandKeyOf(l))); paths(k, b)
                    top = maxOf(top, z)
                }
                steps.add(LiftTo(top + zHopUm))
            }
        }
        when (layout.order) {
            ObjectOrder.TOGETHER -> {
                // Each object's layers in ITS order (print order: a lifted bridge's after what holds it up, each of several
                // after the layer it rests on), merged by height: at each step the object whose next
                // layer tops lowest (ties: the first object), so no object's layers are ever reordered, and when an object's
                // layer is laid its neighbors stand no higher than that object's own highest layer so far (each neighbor's
                // layer was laid while this object's next topped at least as high): the head's clearance beside them is
                // what its own technique already keeps over its own earlier layers. Layers topping at one height, as thick,
                // and alike in being a first layer share one layer start (one layer change in the file, as before).
                val next = IntArray(stripped.size)
                var topUm = 0L
                /** Objects [ks]' layers from next[k] up to (not including) [end][k], merged by height. */
                fun merge(ks: List<Int>, end: IntArray) {
                    while (true) {
                        val k0 = ks.filter { next[it] < end[it] }.minByOrNull { stripped[it][next[it]].topZUm } ?: break
                        val l0 = stripped[k0][next[k0]]
                        val group = ArrayList<Pair<Int, LayerPlanOut>>()
                        for (k in ks) {
                            if (next[k] >= end[k]) continue
                            val l = stripped[k][next[k]]
                            // a curved island's layer is a layer of its own (its mark names one island): never shared with another object's
                            val apart = k != k0 && (slicers[k].islandKeyOf(l) != null || slicers[k0].islandKeyOf(l0) != null)
                            if (!apart && l.topZUm == l0.topZUm && l.heightUm == l0.heightUm && (l.index == 0) == (l0.index == 0)) { group += k to l; next[k]++ }
                        }
                        for ((k, l) in group) columnsBefore(k, l.index)
                        // a curved layer's top is its highest Z, above the flat layer it was planned as
                        val built = group.map { (k, l) -> k to slicers[k].layerSteps(l, tools[k], last[k]) }
                        val z = built.maxOf { it.second.first }
                        steps.add(LayerStart(idx++, z, l0.heightUm, objectLayer = group.minOf { it.second.index },
                            archedBridge = group.any { (k, l) -> slicers[k].isArchedBridgeLayer(l) }, printTemp = group.any { (k, l) -> slicers[k].isBridgePrintTempLayer(l) },
                            island = slicers[k0].islandKeyOf(l0)))
                        for ((k, b) in built) paths(k, b.second)
                        topUm = maxOf(topUm, z)
                    }
                }
                val all = stripped.indices.toList()
                val plateSettings = SliceSettings.of(setup)
                if (plateSettings.bridgeOrderOneAtATime) {
                    // plan.bridge_order = one_at_a_time:
                    // every object's layers below its lifted bridge first, merged by height; then each object's bridge whole,
                    // the head lifted clear of everything printed before each, starting on the side the head reaches least (as
                    // BuildOrder places objects printed one at a time): the head then never passes over a bridge finished
                    // there. The reach is the bridged objects' tools' (hardware.tool.<key>.head.size and nozzle.x), else their
                    // machine model's toolhead; with neither, from +X
                    val bridgeAt = IntArray(stripped.size) { k -> stripped[k].indexOfFirst { slicers[k].isBridgeLayer(it) }.let { if (it < 0) stripped[k].size else it } }
                    merge(all, bridgeAt)
                    val bridged = all.filter { bridgeAt[it] < stripped[it].size }
                    /** The head's reach from the nozzle towards -X and +X (mm) for object [k], or null when not known. */
                    fun reachX(k: Int): Pair<Double, Double>? {
                        val s = SliceSettings.of(setup, plymeld.model.Context(layout.objectIndex[k]))
                        s.headReachUm?.let { return it[0] / 1000.0 to it[1] / 1000.0 }
                        val g = s.machineDescription.toolhead(tools[k])?.geometry ?: return null
                        val minus = g["head_minus_x"] ?: return null; val plus = g["head_plus_x"] ?: return null
                        return minus to plus
                    }
                    val reaches = bridged.map(::reachX)
                    val fromPlusX = if (reaches.any { it == null } || reaches.isEmpty()) true else reaches.maxOf { it!!.second } <= reaches.maxOf { it!!.first }
                    val maxX = all.associateWith { k -> layout.placed[k].parts.maxOf { it.bounds()[3] } }
                    val minX = all.associateWith { k -> layout.placed[k].parts.minOf { it.bounds()[0] } }
                    for (k in if (fromPlusX) bridged.sortedByDescending { maxX.getValue(it) } else bridged.sortedBy { minX.getValue(it) }) {
                        steps.add(LiftTo(topUm + zHopUm))
                        merge(listOf(k), IntArray(stripped.size) { stripped[it].size })
                    }
                } else if (!plateSettings.bridgeOrderHotToCold) merge(all, IntArray(stripped.size) { stripped[it].size })
                else {
                    // plan.bridge_order = hot_to_cold (the feet first at one standard temperature, then the bridges hot to cold):
                    // every object's
                    // layers below its lifted bridge first, merged by height; then the objects with a lifted bridge, grouped by their
                    // bridge temperature (unset: the print temperature), hottest first, each group's layers from its bridge up merged
                    // by height, the head lifted clear of everything printed before each group. Whether a bridge is arched is known
                    // only once its first layer is laid, so the groups go by the setting; a flat bridge prints at the print temperature
                    val bridgeAt = IntArray(stripped.size) { k -> stripped[k].indexOfFirst { slicers[k].isBridgeLayer(it) }.let { if (it < 0) stripped[k].size else it } }
                    merge(all, bridgeAt)
                    val tempOf = all.associateWith { k -> SliceSettings.of(setup, plymeld.model.Context(layout.objectIndex[k])).let { s -> s.bridgeTempC ?: s.nozzleTempC } }
                    for ((_, ks) in all.filter { bridgeAt[it] < stripped[it].size }.groupBy { tempOf.getValue(it) }.toSortedMap(compareByDescending { it })) {
                        steps.add(LiftTo(topUm + zHopUm))
                        merge(ks, IntArray(stripped.size) { stripped[it].size })
                    }
                }
            }
            ObjectOrder.ONE_AT_A_TIME -> {
                var finishedTopUm = 0L
                for ((k, ls) in stripped.withIndex()) {
                    if (k > 0) steps.add(LiftTo(finishedTopUm + zHopUm))
                    for ((j, l) in ls.withIndex()) {
                        columnsBefore(k, l.index)
                        val (z, b) = slicers[k].layerSteps(l, tools[k], last[k])
                        steps.add(LayerStart(idx++, z, l.heightUm, objectLayer = j, archedBridge = slicers[k].isArchedBridgeLayer(l), printTemp = slicers[k].isBridgePrintTempLayer(l),
                            island = slicers[k].islandKeyOf(l))); paths(k, b)
                        finishedTopUm = maxOf(finishedTopUm, z)
                    }
                }
            }
        }
        return PrintProgram(steps, slicers.map { it.printObject })
    }

    /**
     * A plate with an object laid per part (several filaments or part settings): every object's lines per filament
     * ([IncrementalSlicer.laidForPlate]), put together layer by layer, each filament in turn laying its lines of every
     * object (MultiFilament.plateSteps): one change per filament per layer however many objects, and one purge tower beside
     * them all. The objects' layers are merged by height as [program] merges them: layers topping at one height, as thick,
     * and alike in being a first layer are one layer of the plate; objects on other heights have layers of their own
     * between. They print together, by height.
     */
    private fun perPartProgram(): PrintProgram {
        require(layout.order == ObjectOrder.TOGETHER) { "a plate with an object on several filaments prints its objects together, by height; one at a time is not written yet for it" }
        val laid = slicers.map { it.laidForPlate(setup) }
        // the plate's layers: at each step the objects whose next layer tops lowest, those as thick and alike in being a first
        // layer together (the first such object's thickness); the rest at that height in a layer of their own after it
        class Group(val topUm: Long, val heightUm: Long, val members: Map<Int, Int>)
        val groups = ArrayList<Group>()
        run {
            val next = IntArray(laid.size)
            fun top(k: Int) = laid[k].second[next[k]]
            fun height(k: Int) = top(k) - (if (next[k] == 0) 0L else laid[k].second[next[k] - 1])
            while (true) {
                val k0 = laid.indices.filter { next[it] < laid[it].second.size }.minByOrNull { top(it) } ?: break
                val t0 = top(k0); val h0 = height(k0); val first0 = next[k0] == 0
                val members = LinkedHashMap<Int, Int>()
                for (k in laid.indices) if (next[k] < laid[k].second.size && top(k) == t0 && height(k) == h0 && (next[k] == 0) == first0) members[k] = next[k]
                for (k in members.keys) next[k]++
                groups += Group(t0, h0, members)
            }
        }
        val tops = LongArray(groups.size) { groups[it].topUm }
        // each object's lines by the plate's layers (none where it has no layer)
        fun <T> byPlate(k: Int, own: List<T>, none: (Int) -> T): List<T> = groups.mapIndexed { g, gr -> gr.members[k]?.let { own.getOrNull(it) } ?: none(g) }
        val objects = laid.mapIndexed { k, (l, _) ->
            MultiFilament.Laid(l.name, l.filaments.map { f -> MultiFilament.FilamentPlan(f.spool, emptyList(), byPlate(k, f.layers) { g -> LayerPlanOut(g, tops[g], groups[g].heightUm, emptyList()) }, f.unit) },
                byPlate(k, l.support) { emptyList() }, byPlate(k, l.supportInterface) { emptyList() }, l.supportSpool, l.interfaceSpool) }
        val first = layout.objectIndex[0]
        val order = MultiFilament.layerOrder(tops.indices.map { i -> objects.flatMap { it.present(i) }.toSet() }, setup.spools.map { it.id }, setup.objects[first].spool)
        val toolOf = { sp: String -> setup.spool(sp).tool }
        // as for one object (IncrementalSlicer.multiProgram): the print starts with the tool of the first filament printed, and
        // each tool holds the spool numbered as it
        val startTool = order.firstOrNull { it.isNotEmpty() }?.first()?.let(toolOf) ?: setup.spool(setup.objects[first].spool).tool
        val loadedOn = setup.spools.filter { it.id.removePrefix("spool ").trim().toIntOrNull() == it.tool + 1 }.associate { it.tool to it.id }
        val perLayer = MultiFilament.purgesPerLayer(order, toolOf, startTool, loadedOn)
        // the tower's settings are the plate's first object laid per part's; it stands beside every object
        val k0 = slicers.indices.first { slicers[it].isMultiFilament(setup) }
        // the tower has a layer per height: layers topping at one height share it, their purges together
        val towerOf = MultiFilament.towerLayers(tops)
        val towerTops = tops.filterIndexed { i, _ -> i == 0 || towerOf[i] != towerOf[i - 1] }.toLongArray()
        val perTower = IntArray(towerTops.size).also { a -> for (i in perLayer.indices) a[towerOf[i]] += perLayer[i] }
        val tower = if (perLayer.all { it == 0 }) null else slicers[k0].purgeTower(setup, perTower, towerTops, slicers.indices.filter { it != k0 }.map { slicers[it].printObject })
        // temperatures per spool: the material x tool values of a part on that spool, of the first object that has one
        val bySpool = setup.spools.associate { sp -> sp.id to layout.objectIndex.firstNotNullOfOrNull { oi ->
            setup.objects[oi].parts.indexOfFirst { it.spool == sp.id }.takeIf { it >= 0 }?.let { SliceSettings.of(setup, plymeld.model.Context(oi, it)) }
                ?: SliceSettings.of(setup, plymeld.model.Context(oi)).takeIf { setup.objects[oi].spool == sp.id && setup.objects[oi].parts.isEmpty() } } }
        val tempOf = { sp: String, layer: Int -> bySpool[sp]?.let { if (layer == 0) it.nozzleTempFirstC else it.nozzleTempC } }
        val ctx0 = plymeld.model.Context(layout.objectIndex[k0])
        val drop = if (perLayer.all { it == 0 }) null else (plymeld.model.Resolver.resolveOrNull(setup, "hardware.filament.temperature.idle_drop", ctx0)?.value as Number?)?.toInt()
        val idleOf = { sp: String, layer: Int -> tempOf(sp, layer)?.let { t -> drop?.takeIf { it > 0 }?.let { t - it } } }
        val steps = MultiFilament.plateSteps(objects, order, toolOf, tops, startTool, tower, tempOf, idleOf, loadedOn = loadedOn, purgeInto = SliceSettings.of(setup, ctx0).purgeInto,
            heightsUm = LongArray(groups.size) { groups[it].heightUm }, objectLayers = IntArray(groups.size) { groups[it].members.values.min() })
        return PrintProgram(steps, slicers.map { it.printObject })
    }

    /** A support column of object [k] printed first ([box]: the placed frame, µm): the support paths taken from its layers, by layer index. */
    private class Column(val k: Int, val box: Supports.Box) {
        val layers = HashMap<Int, Pair<LayerPlanOut, ArrayList<ToolPath>>>()
        /** Whether [p] of layer [l] is this column's: a support path whose every point lies in the box, at a layer within its height range. */
        fun takes(p: ToolPath, l: LayerPlanOut): Boolean {
            if (p.feature != Feature.SUPPORT && p.feature != Feature.SUPPORT_INTERFACE) return false
            if (!box.spans(l.topZUm - l.heightUm, l.topZUm)) return false
            for (i in 0 until p.xy.size / 2) if (p.xy[2 * i] < box.x0 || p.xy[2 * i] > box.x1 || p.xy[2 * i + 1] < box.y0 || p.xy[2 * i + 1] > box.y1) return false
            return true
        }
    }

    /** Pauses to insert ([Pauses]); on a plate, names are the objects' (parts: not yet). */
    var pauses: List<PauseLock> = emptyList()
    /** Where each of [pauses] landed, after the last [write]. */
    var pausesLanded: List<String> = emptyList(); private set
    /** Where each filament swapped by hand was placed (Pauses.placeSwaps), after the last [write]. */
    var swapsLanded: List<String> = emptyList(); private set

    fun write(out: Writer) {
        // one object laid part group by part group (several filaments or part settings): written as `slice` writes it, by its
        // own slicer (its parts' tool changes, MultiFilament), so the plan's full prediction reads the same g-code (
        // the plan refused such objects here, and the pose, technique, and curved-island decisions fell back to quick estimates
        // or were skipped on them). On a plate of several objects: perPartProgram
        if (slicers.size == 1 && slicers[0].isMultiFilament(setup)) {
            val sl = slicers[0]
            sl.pauses = pauses
            sl.slice(setup, out)
            pausesLanded = sl.pausesLanded; swapsLanded = sl.swapsLanded; strings = sl.lastStrings
            return
        }
        val perObject =slicers.mapIndexed { k, sl -> sl.printObject.name to SliceSettings.of(setup, plymeld.model.Context(layout.objectIndex[k])) }.toMap()
        // layers following the surfaces land on each pause height (layers of one height ignore this)
        slicers.forEach { it.landingHeightsMm = pauses.filterIsInstance<PauseLock.AtZ>().map { p -> p.zMm } }
        val (program, landed, swaps) = Pauses.insert(if (slicers.any { it.isMultiFilament(setup) }) perPartProgram() else program(), pauses)
        pausesLanded = landed; swapsLanded = swaps
        // a part printing with another tool than its object's own: that part's settings for the paths of that tool
        val perObjectTool = slicers.mapIndexed { k, sl -> IncrementalSlicer.toolSettings(setup, layout.objectIndex[k], sl.printObject.name) }
            .fold(emptyMap<Pair<String, Int>, SliceSettings>()) { a, b -> a + b }
        // the next tool heated ahead of each change, as for one object (it was left out here: a plate of several objects
        // waited at every change)
        val plateSettings = SliceSettings.of(setup)
        val preheat = layout.objectIndex.firstOrNull()?.let { IncrementalSlicer.preheatS(setup, plymeld.model.Context(it), plateSettings) }
        val w = KlipperWriter(plateSettings, setup.foreignTemplateVars, version, perObject, perObjectTool = perObjectTool,
            preheatS = preheat, pressureAdvance = setup.pressureAdvanceByTool(), progress = progress)
        w.write(program, out)
        out.flush()
        strings = w.strings
    }

    /** string_risk and restart_starved of the plate's last write (StringRisk); null before one. */
    var strings: plymeld.gcode.StringRisk.Result? = null; private set
    /** Each object's in-island retraction decision from the last write (RetractionDecision). */
    val retractionDecisions: List<plymeld.core.decide.Decision<Boolean>> get() = slicers.mapNotNull { it.retractionDecision }
}
