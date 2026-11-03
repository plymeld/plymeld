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
    "an unknown value is refused" {
        shouldThrow<IllegalArgumentException> { steps("object", 40.0) }
    }
})
