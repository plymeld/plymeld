package plymeld.check

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import plymeld.core.Feature
import plymeld.core.MultiFilament
import plymeld.core.PurgeTower
import plymeld.program.Extrude
import plymeld.program.ObjectEnd
import plymeld.program.ObjectStart
import plymeld.program.Step
import plymeld.program.ToolChange

/**
 * The purge into the object's own infill (plan.mechanism.purge.into) on a synthetic two-filament plan: part A
 * (spool 1, tool 0) a 10 mm square, part B (spool 2, tool 1) a 30 mm square beside it, 12 layers of 0.2 mm, so every layer
 * changes tools once (to spool 2) and B's sparse infill is some 20 mm³ a layer. With "infill", B's sparse infill is printed
 * first after the change, inside an object block, and the tower's purge for the change is only what is left (a 40 mm³
 * purge: about half; a 10 mm³ purge: none, the band laid at the sparse spacing instead). Every sparse path is still laid
 * once. Control: with "tower" the tower's lines follow the change at once and take the whole purge.
 */
class PurgeIntoInfillTest : StringSpec({
    val tool = { sp: String -> if (sp == "spool 1") 0 else 1 }
    val sq = { x: Long, w: Long -> listOf(plymeld.geom.Polygon(longArrayOf(x, 0, x + w, 0, x + w, w, x, w))) }
    val s = plymeld.model.SliceSettings.of(testSetup())
    val n = 30
    val tops = LongArray(n) { 200L * (it + 1) }
    val parts = listOf(List(n) { sq(0, 10_000) }, List(n) { sq(15_000, 30_000) })
    val r = MultiFilament.plan(s, parts, listOf("spool 1", "spool 2"), listOf(0, 1), tops, "spool 1")
    val present = tops.indices.map { i -> r.filaments.filter { it.layers[i].paths.isNotEmpty() }.map { it.spool }.toSet() }
    val order = MultiFilament.layerOrder(present, listOf("spool 1", "spool 2"), "spool 1")
    val perLayer = MultiFilament.changesPerLayer(order, tool, 0)
    fun tower(purgeMm3: Double) = PurgeTower.square(60_000, 0, purgeMm3, 440, 2_000, perLayer, tops, compact = false, maxGapUm = 300)
    fun steps(into: String, purgeMm3: Double) = MultiFilament.steps(r, order, tool, "obj", tops, 0, tower(purgeMm3), purgeInto = into)
    fun mm3(p: Extrude): Double {
        val m = p.xy.size / 2; val segs = if (p.closed) m else m - 1
        val len = (0 until segs).sumOf { k -> val j = (k + 1) % m; Math.hypot((p.xy[2 * j] - p.xy[2 * k]).toDouble(), (p.xy[2 * j + 1] - p.xy[2 * k + 1]).toDouble()) } / 1000.0
        val h = p.heightUm / 1000.0; val w = p.widthUm / 1000.0
        return len * h * (w - h * (1 - Math.PI / 4))
    }
    /** The feature of the first extrusion after each tool change, and whether it was inside an object block. */
    fun afterChanges(steps: List<Step>): List<Pair<String, Boolean>> {
        val out = ArrayList<Pair<String, Boolean>>(); var pending = false; var inObject = false
        for (st in steps) when (st) {
            is ToolChange -> pending = true
            is ObjectStart -> inObject = true
            is ObjectEnd -> inObject = false
            is Extrude -> if (pending) { out += st.feature to inObject; pending = false }
            else -> Unit
        }
        return out
    }
    /** Plastic (mm³) of the tower's lines laid between a change and the next object block that is not the purge into infill: the purge the tower took. */
    fun towerPurgeMm3(steps: List<Step>): Double {
        var sum = 0.0; var afterChange = false; var inObject = false
        for (st in steps) when (st) {
            is ToolChange -> afterChange = true
            is ObjectStart -> inObject = true
            is ObjectEnd -> inObject = false
            is Extrude -> if (afterChange && !inObject && st.feature == Feature.TOWER.label) sum += mm3(st) else if (afterChange && inObject && st.feature != Feature.SPARSE.label && st.feature != Feature.SOLID.label) afterChange = false
            else -> Unit
        }
        return sum
    }
    fun sparseCount(steps: List<Step>) = steps.count { it is Extrude && it.feature == Feature.SPARSE.label }

    /** Whether spool 2 lays sparse infill on each layer (its shells, top and bottom, have none). */
    val hasSparse = tops.indices.map { i -> r.filaments.single { it.spool == "spool 2" }.layers[i].paths.any { it.feature == Feature.SPARSE } }

    "infill: the new spool's sparse infill comes first after the change, in its object block (the tower first where the layer has none); control: the tower's lines come first" {
        (perLayer.count { it > 0 } >= 25) shouldBe true
        (hasSparse.count { it } >= 15) shouldBe true
        val changeLayers = tops.indices.filter { perLayer[it] > 0 }
        val a = afterChanges(steps("infill", 30.0))
        a.size shouldBe changeLayers.size
        for ((k, i) in changeLayers.withIndex()) a[k] shouldBe (if (hasSparse[i]) Feature.SPARSE.label to true else Feature.TOWER.label to false)
        val c = afterChanges(steps("tower", 30.0))
        c.all { (f, inObj) -> f == Feature.TOWER.label && !inObj } shouldBe true
        sparseCount(steps("infill", 30.0)) shouldBe sparseCount(steps("tower", 30.0))
    }
    "the tower takes only what the infill left: less than with the tower alone, and none when the infill holds the whole purge" {
        val full = towerPurgeMm3(steps("tower", 30.0)); val part = towerPurgeMm3(steps("infill", 30.0))
        println("tower purge over the print: tower alone %.1f mm3, after the infill %.1f mm3".format(java.util.Locale.ROOT, full, part))
        (part < full * 0.7) shouldBe true
        (towerPurgeMm3(steps("infill", 8.0)) < towerPurgeMm3(steps("tower", 8.0)) * 0.7) shouldBe true
    }
    "with the tower alone, the new filament's first line on the model after a change is its infill where it has any; its inner wall where it has none, and the same lines laid" {
        /** Per change, the feature of the first extrusion in an object block after it. */
        fun firstOnModel(steps: List<Step>): List<String> {
            val out = ArrayList<String>(); var pending = false; var inObject = false
            for (st in steps) when (st) {
                is ToolChange -> pending = true
                is ObjectStart -> inObject = true
                is ObjectEnd -> inObject = false
                is Extrude -> if (pending && inObject) { out += st.feature; pending = false }
                else -> Unit
            }
            return out
        }
        val changeLayers = tops.indices.filter { perLayer[it] > 0 }
        val first = firstOnModel(steps("tower", 30.0))
        first.size shouldBe changeLayers.size
        val hasInfill = tops.indices.map { i -> r.filaments.single { it.spool == "spool 2" }.layers[i].paths.any { it.feature == Feature.SPARSE || it.feature == Feature.SOLID } }
        for ((k, i) in changeLayers.withIndex())
            if (hasInfill[i]) (first[k] == Feature.SPARSE.label || first[k] == Feature.SOLID.label) shouldBe true
            else first[k] shouldBe (if (r.filaments.single { it.spool == "spool 2" }.layers[i].paths.any { it.feature == Feature.INNER_WALL }) Feature.INNER_WALL.label else first[k])
        (changeLayers.count { !hasInfill[it] } >= 1) shouldBe true           // the control case is there: a layer of B without infill
        // the planned order alone would have begun with a wall: what the change moved
        val plannedFirst = changeLayers.map { i -> r.filaments.single { it.spool == "spool 2" }.layers[i].paths.first().feature.label }
        (plannedFirst.count { it == Feature.OUTER_WALL.label || it == Feature.INNER_WALL.label } >= 15) shouldBe true
        // every line still laid once
        steps("tower", 30.0).filterIsInstance<Extrude>().count { it.feature == Feature.SPARSE.label || it.feature == Feature.SOLID.label } shouldBe
            r.filaments.sumOf { f -> f.layers.sumOf { l -> l.paths.count { it.feature == Feature.SPARSE || it.feature == Feature.SOLID } } }
    }
    "a line laid after another than it was planned after retracts on the travel into it; control: lines kept in order keep their own decision" {
        // travels inside an island without a retraction (plan.mechanism.motion.no_retract_in_island), as on the nameplate
        // whose strings this was found on: lines planned to follow their neighbour unretracted
        val sIsland = plymeld.model.SliceSettings.of(testSetup("plan.mechanism.motion.no_retract_in_island" to true))
        // and B two islands, 20 mm squares 5 mm apart: the second island's infill is planned after its own walls, unretracted,
        // and laid first after the first island's infill, across the gap
        val twoIslands = listOf(List(n) { sq(0, 10_000) }, List(n) { sq(15_000, 20_000) + sq(40_000, 20_000) })
        val planned0 = MultiFilament.plan(sIsland, twoIslands, listOf("spool 1", "spool 2"), listOf(0, 1), tops, "spool 1")
        // B's sparse infill entered without a retraction, as the planner leaves a travel hidden inside the part (on these small
        // squares it retracts): what the reorder must not carry to a travel from somewhere else
        val rIsland = MultiFilament.Result(planned0.filaments.map { f -> if (f.spool != "spool 2") f else MultiFilament.FilamentPlan(f.spool, f.areas,
            f.layers.map { l -> plymeld.core.LayerPlanOut(l.index, l.topZUm, l.heightUm, l.paths.map { p -> if (p.feature == Feature.SPARSE) p.withRetract(false) else p }) }, f.unit) },
            planned0.overlapUm2PerLayer, planned0.whole, support = planned0.support, supportInterface = planned0.supportInterface)
        fun steps(@Suppress("UNUSED_PARAMETER") into: String, purgeMm3: Double) = MultiFilament.steps(rIsland, order, tool, "obj", tops, 0, tower(purgeMm3), purgeInto = "tower")
        // B's planned order per layer, and what each line was planned after
        val bLayers = rIsland.filaments.single { it.spool == "spool 2" }.layers
        val plannedAfter = HashMap<List<Long>, List<Long>?>()
        val plannedRetract = HashMap<List<Long>, Boolean>()
        for (l in bLayers) for ((k, p) in l.paths.withIndex()) { plannedAfter[p.xy.toList()] = l.paths.getOrNull(k - 1)?.xy?.toList(); plannedRetract[p.xy.toList()] = p.retractBefore }
        var moved = 0; var kept = 0; var unretractedMoved = 0; var changedDecision = 0; var movedPlannedNot = 0
        var prev: List<Long>? = null; var inB = false
        for (st in steps("tower", 30.0)) when (st) {
            is ObjectStart -> prev = null
            is ToolChange -> inB = st.tool == 1
            is Extrude -> if (inB && st.feature != Feature.TOWER.label) {
                val key = st.xy.toList()
                if (key in plannedAfter) {
                    if (plannedAfter[key] != prev) { moved++; if (!st.retractBefore) unretractedMoved++; if (plannedRetract[key] == false) movedPlannedNot++ }
                    else { kept++; if (st.retractBefore != plannedRetract[key]) changedDecision++ }
                }
                prev = key
            }
            else -> Unit
        }
        println("B lines after a change: $moved laid after another line than planned ($movedPlannedNot of them planned without a retraction), $kept in their planned order; " +
            "planned unretracted by feature: " + bLayers.flatMap { l -> l.paths.withIndex().filter { !it.value.retractBefore }.map { "${it.value.feature.label}@${it.index}" } }.groupingBy { it }.eachCount().entries.take(8))
        (moved > 0) shouldBe true
        (movedPlannedNot > 0) shouldBe true                               // the case is there: a moved line planned unretracted
        unretractedMoved shouldBe 0
        (kept > 0) shouldBe true
        changedDecision shouldBe 0
        // control: the planned order has lines that do not retract, which the reorder would otherwise have carried along
        (bLayers.sumOf { l -> l.paths.count { !it.retractBefore } } > 0) shouldBe true
    }
    "on a layer where the new filament lays no infill, its walls keep their planned order (the wall order holds); control: with infill, the infill comes first" {
        // outer walls planned first (plan.layer.walls.order = outer_first): after a change they are still first where there is no
        // infill to lay ahead of them (all the inner walls had been moved ahead, which printed every wall order inner-first there)
        val sOuter = plymeld.model.SliceSettings.of(testSetup("plan.layer.walls.order" to "outer_first"))
        val rOuter = MultiFilament.plan(sOuter, parts, listOf("spool 1", "spool 2"), listOf(0, 1), tops, "spool 1")
        val bLayers = rOuter.filaments.single { it.spool == "spool 2" }.layers
        val firsts = ArrayList<String>(); var pending = false; var inObject = false
        for (st in MultiFilament.steps(rOuter, order, tool, "obj", tops, 0, tower(30.0))) when (st) {
            is ToolChange -> pending = true
            is ObjectStart -> inObject = true
            is ObjectEnd -> inObject = false
            is Extrude -> if (pending && inObject) { firsts += st.feature; pending = false }
            else -> Unit
        }
        val changeLayers = tops.indices.filter { perLayer[it] > 0 }
        val noInfill = changeLayers.withIndex().filter { (_, i) -> bLayers[i].paths.none { it.feature == Feature.SPARSE || it.feature == Feature.SOLID } && bLayers[i].paths.any { it.feature == Feature.INNER_WALL } }
        (noInfill.size >= 1) shouldBe true
        noInfill.count { (_, i) -> bLayers[i].paths.first().feature == Feature.OUTER_WALL } shouldBe noInfill.size
        for ((k, _) in noInfill) firsts[k] shouldBe Feature.OUTER_WALL.label
        // control: on a change layer where it does lay infill, planned behind its outer wall, the infill is its first line
        val withInfill = changeLayers.withIndex().filter { (_, i) -> bLayers[i].paths.any { it.feature == Feature.SPARSE || it.feature == Feature.SOLID } }
        (withInfill.isNotEmpty() && withInfill.all { (_, i) -> bLayers[i].paths.first().feature == Feature.OUTER_WALL }) shouldBe true
        for ((k, _) in withInfill) (firsts[k] == Feature.SPARSE.label || firsts[k] == Feature.SOLID.label) shouldBe true
    }
    "an unknown value is refused" {
        shouldThrow<IllegalArgumentException> { steps("object", 40.0) }
    }
})
