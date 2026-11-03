package plymeld.check

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import plymeld.core.MultiFilament
import plymeld.mesh.LayerPlan
import plymeld.mesh.Slicer
import plymeld.model.SliceSettings
import plymeld.program.Extrude
import plymeld.program.LayerStart
import plymeld.program.Step

/**
 * Support filament: by default support is printed with whichever filament is
 * loaded (no tool change of its own); a named filament prints all of it.
 * Two-color Benchy (A on spool 1 / tool 0, B on spool 2 / tool 1), support on.
 */
class SupportFilamentTest : StringSpec({
    // not from the bed only: from the bed, the Benchy's overhangs (all over its hull) get no tree; where support stands is not
    // what is tested
    val s = SliceSettings.of(testSetup("plan.support.enabled" to true, "plan.support.from_bed_only" to false, slots = 2))
    val parts = benchyDualParts()
    val all = parts.fold(FloatArray(0)) { acc, p -> acc + p.coords }.let { plymeld.mesh.TriangleSoup(it, parts.sumOf { p -> p.count }) }
    val b = all.bounds()
    val plan = LayerPlan.fixed(b[2], b[5], s.firstLayerHeightUm / 1000.0, s.layerHeightUm / 1000.0)
    val tops = LongArray(plan.count) { Math.round((plan.tops[it] - b[2]) * 1000) }
    val partLayers = parts.map { p -> Slicer.slice(p, plan.slices).map { it.loops } }
    val r = MultiFilament.plan(s, partLayers, listOf("spool 1", "spool 2"), listOf(0, 1), tops, "spool 1")
    val toolOf = { sp: String -> if (sp == "spool 1") 0 else 1 }
    val order0 = listOf("spool 1", "spool 2")

    /** Per support path: (layer, tool it was printed with, tool that printed the layer's first path). */
    fun supportTools(steps: List<Step>): List<Triple<Int, Int, Int>> {
        val out = ArrayList<Triple<Int, Int, Int>>(); var layer = -1; var firstTool = -1
        for (st in steps) when (st) {
            is LayerStart -> { layer = st.index; firstTool = -1 }
            is Extrude -> {
                if (firstTool < 0 && st.feature != "Prime tower") firstTool = st.tool
                if (st.feature.startsWith("Support")) out.add(Triple(layer, st.tool, firstTool))
            }
            else -> {}
        }
        return out
    }

    "default: support prints with the filament already printing the layer; named: all on that filament" {
        r.support.count { it.isNotEmpty() } shouldBeGreaterThan 0              // the Benchy does get support
        val loaded = MultiFilament.present(r, null, null).let { MultiFilament.layerOrder(it, order0, "spool 1") }
        val a = supportTools(MultiFilament.steps(r, loaded, toolOf, "benchy", tops, 0))
        a.size shouldBeGreaterThan 0
        a.all { (_, tool, first) -> tool == first } shouldBe true              // never a change for support
        // named filament 2: every support path on tool 1, and spool 2 is on those layers' order
        val named = MultiFilament.present(r, "spool 2", "spool 2").let { MultiFilament.layerOrder(it, order0, "spool 1") }
        val c = supportTools(MultiFilament.steps(r, named, toolOf, "benchy", tops, 0, supportSpool = "spool 2", interfaceSpool = "spool 2"))
        c.size shouldBe a.size
        c.all { it.second == 1 } shouldBe true
        // control: the default does put some support on tool 0 (so "all on tool 1" above can fail)
        a.any { it.second == 0 } shouldBe true
    }
})
