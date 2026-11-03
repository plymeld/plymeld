package plymeld.check

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import plymeld.core.Feature
import plymeld.core.LayerPlanOut
import plymeld.core.MultiFilament
import plymeld.mesh.LayerPlan
import plymeld.mesh.Slicer
import plymeld.model.SliceSettings
import java.util.Locale

/**
 * Per-part settings: two parts on ONE
 * spool, the second with its own sparse density, are planned as two settings groups. Its sparse
 * infill must scale with its own density; the control plans it with the object's settings.
 */
class PartSettingsTest : StringSpec({
    val s = SliceSettings.of(testSetup())
    val dense = SliceSettings.of(testSetup("plan.layer.infill.density" to "45%"))
    val parts = benchyDualParts()
    val all = parts.fold(FloatArray(0)) { acc, p -> acc + p.coords }.let { plymeld.mesh.TriangleSoup(it, parts.sumOf { p -> p.count }) }
    val b = all.bounds()
    val plan = LayerPlan.fixed(b[2], b[5], s.firstLayerHeightUm / 1000.0, s.layerHeightUm / 1000.0)
    val tops = LongArray(plan.count) { Math.round((plan.tops[it] - b[2]) * 1000) }
    val partLayers = parts.map { p -> Slicer.slice(p, plan.slices).map { it.loops } }
    val spools = listOf("spool 1", "spool 1")
    val units = listOf("spool 1", "spool 1 / part B")

    fun sparseMm(layers: List<LayerPlanOut>) = layers.sumOf { l -> l.paths.filter { it.feature == Feature.SPARSE }.sumOf { p ->
        val m = p.xy.size / 2
        (0 until m - 1).sumOf { k -> Math.hypot((p.xy[2 * k + 2] - p.xy[2 * k]).toDouble(), (p.xy[2 * k + 3] - p.xy[2 * k + 1]).toDouble()) } } } / 1000.0

    "a part with its own sparse density is planned with it; the other part and the tool are unchanged" {
        val own = MultiFilament.plan(s, partLayers, spools, listOf(0, 1), tops, "spool 1", units, mapOf("spool 1 / part B" to dense))
        val control = MultiFilament.plan(s, partLayers, spools, listOf(0, 1), tops, "spool 1", units, emptyMap())
        own.filaments.map { it.unit } shouldBe listOf("spool 1", "spool 1 / part B")
        own.filaments.all { it.spool == "spool 1" } shouldBe true                      // one spool: no tool change between them
        // reference: the same part when the WHOLE object is at 45 % (no per-part machinery involved).
        // (Line length does not scale as 1 / density on thin regions: clipped lines and anchors.)
        val allDense = MultiFilament.plan(dense, partLayers, spools, listOf(0, 1), tops, "spool 1", units, emptyMap())
        val bOwn = sparseMm(own.filaments[1].layers); val bControl = sparseMm(control.filaments[1].layers); val bRef = sparseMm(allDense.filaments[1].layers)
        println("part B sparse: %.0f mm with its own 45%%, %.0f mm when the whole object is 45%%, %.0f mm with the object's %.0f%%".format(
            Locale.ROOT, bOwn, bRef, bControl, s.sparseDensity * 100))
        bOwn shouldBe (bRef plusOrMinus bRef * 0.01)
        // control: the object's own density gives clearly less (so the check above can fail)
        (bControl < bRef * 0.8) shouldBe true
        // part A keeps the object's settings in both runs
        sparseMm(own.filaments[0].layers) shouldBe (sparseMm(control.filaments[0].layers) plusOrMinus 1.0)
    }
})
