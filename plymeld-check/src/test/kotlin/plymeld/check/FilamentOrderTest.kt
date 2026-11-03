package plymeld.check

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import plymeld.core.MultiFilament
import plymeld.program.Extrude
import plymeld.program.ObjectEnd
import plymeld.program.ObjectStart
import plymeld.program.ToolChange
import plymeld.model.withObjectSetups

/**
 * Multi-color step 3: filament order within each layer. Reference: the fewest tool changes found
 * by trying EVERY order of every layer (dynamic program over the filament loaded at each layer
 * end) — independent of the continuity rule under test.
 */
private fun fewestChanges(present: List<Set<String>>, toolOf: (String) -> Int, startTool: Int): Int {
    fun perms(xs: List<String>): List<List<String>> = if (xs.size <= 1) listOf(xs) else xs.flatMap { x -> perms(xs - x).map { listOf(x) + it } }
    var best = mapOf(startTool to 0)                 // tool loaded -> fewest changes so far
    for (here in present) {
        if (here.isEmpty()) continue
        val next = HashMap<Int, Int>()
        for ((t0, c0) in best) for (p in perms(here.sorted())) {
            var t = t0; var c = c0
            for (sp in p) { val nt = toolOf(sp); if (nt != t) { c++; t = nt } }
            next[t] = minOf(next[t] ?: Int.MAX_VALUE, c)
        }
        best = next
    }
    return best.values.min()
}

class FilamentOrderTest : StringSpec({

    val tool = { sp: String -> sp.removePrefix("spool ").toInt() - 1 }

    "each layer starts with the filament the last one ended on; changes equal the exhaustive minimum" {
        val cases = listOf(
            listOf(setOf("spool 1", "spool 2"), setOf("spool 1", "spool 2"), setOf("spool 2"), setOf("spool 1", "spool 2"), setOf("spool 1")),
            listOf(setOf("spool 3"), setOf("spool 1", "spool 2", "spool 3"), setOf("spool 2", "spool 4"), setOf("spool 1", "spool 3", "spool 4"), setOf("spool 2")),
            listOf(setOf("spool 1"), setOf("spool 2"), setOf("spool 1"), setOf("spool 2", "spool 3")),
        )
        for (present in cases) {
            val order = MultiFilament.layerOrder(present, listOf("spool 1", "spool 2", "spool 3", "spool 4"), "spool 1")
            order.map { it.toSet() } shouldBe present                       // every filament of a layer, once
            for (i in 1 until order.size) if (order[i - 1].isNotEmpty() && order[i - 1].last() in present[i]) order[i].first() shouldBe order[i - 1].last()
            MultiFilament.changes(order, tool, 0) shouldBe fewestChanges(present, tool, 0)
        }
    }

    "control: always starting each layer with the lowest filament costs more changes" {
        val present = List(10) { setOf("spool 1", "spool 2") }
        val naive = present.map { it.sorted() }
        val ours = MultiFilament.layerOrder(present, listOf("spool 1", "spool 2"), "spool 1")
        MultiFilament.changes(naive, tool, 0) shouldBe 19                    // 1 per layer + 1 back per boundary
        MultiFilament.changes(ours, tool, 0) shouldBe 10                     // 1 per layer
        fewestChanges(present, tool, 0) shouldBe 10
    }

    "two-color Benchy through the slicer's own stages: tool changes equal the exhaustive minimum" {
        val base = testSetup(slots = 2)
        (base.spools.size >= 2) shouldBe true
        val o = base.objects[0]
        val setup = base.withObjectSetups(listOf(plymeld.model.ObjectSetup(o.name, o.intent, "spool 1", o.overrides, listOf(
            plymeld.model.PartSetup("A", "spool 1", plymeld.model.Values(plymeld.model.Entity.INTENT, "A")),
            plymeld.model.PartSetup("B", "spool 2", plymeld.model.Values(plymeld.model.Entity.INTENT, "B"))))))
        val parts = benchyDualParts()
        val inc = plymeld.core.IncrementalSlicer(parts, "test", "benchy-dual")
        val (r, tops) = inc.plannedFilaments(setup)
        val present = tops.indices.map { i -> r.filaments.filter { it.layers[i].paths.isNotEmpty() }.map { it.spool }.toSet() }
        val order = MultiFilament.layerOrder(present, setup.spools.map { it.id }, "spool 1")
        val both = present.count { it.size == 2 }
        val n = MultiFilament.changes(order, tool, 0)
        println("  two-color Benchy: ${tops.size} layers, $both with both filaments; $n tool changes (exhaustive minimum ${fewestChanges(present, tool, 0)})")
        n shouldBe fewestChanges(present, tool, 0)
    }

    // Two filaments on one tool and no filament changer: without a pause to swap them by hand the object would print
    // in one filament, silently. It must be refused, naming the pause that would allow it.
    "two filaments in ONE tool are refused, not printed in one filament" {
        val base = testSetup(slots = 2)
        base.spools.map { it.tool }.distinct() shouldBe listOf(0)                 // both spools on one tool
        val o = base.objects[0]
        val setup = base.withObjectSetups(listOf(plymeld.model.ObjectSetup(o.name, o.intent, "spool 1", o.overrides, listOf(
            plymeld.model.PartSetup("A", "spool 1", plymeld.model.Values(plymeld.model.Entity.INTENT, "A")),
            plymeld.model.PartSetup("B", "spool 2", plymeld.model.Values(plymeld.model.Entity.INTENT, "B"))))))
        val parts = benchyDualParts()
        val e = io.kotest.assertions.throwables.shouldThrow<IllegalArgumentException> {
            plymeld.core.IncrementalSlicer(parts, "test", "benchy-dual").slice(setup, java.io.StringWriter())
        }
        (e.message!!.contains("tool 1 prints spool 1 and then spool 2")) shouldBe true
        (e.message!!.contains("with no pause between")) shouldBe true
    }

    "steps: a tool change never falls inside an object block, and each extrusion uses the tool loaded" {
        // a small synthetic two-filament plan: layer outlines as rectangles
        val sq = { x: Long -> listOf(plymeld.geom.Polygon(longArrayOf(x, 0, x + 10_000, 0, x + 10_000, 10_000, x, 10_000))) }
        val s = plymeld.model.SliceSettings.of(testSetup())
        val tops = LongArray(6) { 200L * (it + 1) }
        val parts = listOf(List(6) { sq(0) }, List(6) { if (it < 3) sq(10_000) else emptyList() })
        val r = MultiFilament.plan(s, parts, listOf("spool 1", "spool 2"), listOf(0, 1), tops, "spool 1")
        val present = tops.indices.map { i -> r.filaments.filter { it.layers[i].paths.isNotEmpty() }.map { it.spool }.toSet() }
        val order = MultiFilament.layerOrder(present, listOf("spool 1", "spool 2"), "spool 1")
        val steps = MultiFilament.steps(r, order, tool, "obj", tops, 0)
        var inObject = false; var loaded = 0; var changesSeen = 0
        for (st in steps) when (st) {
            is ObjectStart -> inObject = true
            is ObjectEnd -> inObject = false
            is ToolChange -> { inObject shouldBe false; loaded = st.tool; changesSeen++ }
            is Extrude -> st.tool shouldBe loaded
            else -> Unit
        }
        changesSeen shouldBe MultiFilament.changes(order, tool, 0)
        changesSeen shouldBe fewestChanges(present, tool, 0)
        println("  synthetic two-filament plan: layers ${order.joinToString(" | ") { it.joinToString(",") }}; $changesSeen tool changes")

        // with a purge tower: every change is followed, before anything else, by its purge with the NEW tool
        val perLayer = MultiFilament.changesPerLayer(order, tool, 0)
        val tower = plymeld.core.PurgeTower(40_000, 40_000, 20_000, 30.0, 400, 4_000, perLayer, tops)
        val withTower = MultiFilament.steps(r, order, tool, "obj", tops, 0, tower)
        var purgesAfterChange = 0; var towerOutsideObject = 0; var inObj = false
        for ((k, st) in withTower.withIndex()) {
            if (st is ObjectStart) inObj = true
            if (st is ObjectEnd) inObj = false
            if (st is Extrude && st.feature == "Prime tower" && inObj) towerOutsideObject++
            if (st is ToolChange) {
                val next = withTower[k + 1]
                (next is Extrude && next.feature == "Prime tower" && next.tool == st.tool) shouldBe true
                purgesAfterChange++
            }
        }
        purgesAfterChange shouldBe changesSeen
        towerOutsideObject shouldBe 0                                  // tower paths are never inside an object block
        // the tower is printed on every layer up to its top, and not above
        val towerLayers = withTower.fold(Pair(-1, HashSet<Int>())) { (layer, set), st ->
            when (st) { is plymeld.program.LayerStart -> Pair(st.index, set); is Extrude -> { if (st.feature == "Prime tower") set.add(layer); Pair(layer, set) }; else -> Pair(layer, set) }
        }.second
        towerLayers shouldBe (0..tower.top).toSet()
    }
})
