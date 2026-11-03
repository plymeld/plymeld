package plymeld.check

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import plymeld.core.Feature
import plymeld.core.Planner
import plymeld.geom.Polygon
import plymeld.model.SliceSettings
import plymeld.model.Sourced

/**
 * Narrow sparse pieces (keep them sparse by default, solid as an option; the width
 * threshold in cells of the pattern): a 6 mm bar beside a 30 mm block, 40 layers; layer 20 is all
 * sparse inside the walls. The bar's inside is narrower than one cell, the block's is not.
 */
class NarrowInfillTest : StringSpec({
    val n = 40
    val tops = LongArray(n) { 200L * (it + 1) }
    val block = Polygon(longArrayOf(0, 0, 30_000, 0, 30_000, 30_000, 0, 30_000))
    val bar = Polygon(longArrayOf(40_000, 0, 46_000, 0, 46_000, 30_000, 40_000, 30_000))
    val outline = List(n) { listOf(block, bar) }

    fun layer20(vararg values: Pair<String, Any>): List<plymeld.core.ToolPath> {
        val setup = testSetup()
        for (v in setup.intents.values) for ((k, x) in values) v[k] = Sourced(x, "test")
        return Planner.plan(SliceSettings.of(setup), outline, tops)[20].paths
    }
    fun inBar(p: plymeld.core.ToolPath) = (0 until p.xy.size / 2).all { p.xy[2 * it] in 40_000..46_000 }
    /** Length (mm) of the open paths among [ps] (infill links its lines into long runs: count length, not paths). */
    fun openMm(ps: List<plymeld.core.ToolPath>) = ps.filter { !it.closed }.sumOf { p -> (1 until p.xy.size / 2).sumOf { k ->
        Math.hypot((p.xy[2 * k] - p.xy[2 * k - 2]).toDouble(), (p.xy[2 * k + 1] - p.xy[2 * k - 1]).toDouble()) } } / 1000

    "default (1 cell, loop): the bar gets one closed sparse loop, the block the grid" {
        val ps = layer20()
        ps.filter { inBar(it) && it.feature == Feature.SPARSE }.let { b -> (b.size == 1 && b[0].closed) shouldBe true }
        ps.any { inBar(it) && it.feature == Feature.SOLID } shouldBe false
        (openMm(ps.filter { !inBar(it) && it.feature == Feature.SPARSE }) > 100.0) shouldBe true     // the block's grid
    }

    "solid: the bar's inside prints as internal solid lines, no sparse loop; the block keeps its grid" {
        val ps = layer20("plan.layer.infill.narrow.fill" to "solid")
        ps.any { inBar(it) && it.feature == Feature.SPARSE } shouldBe false
        (openMm(ps.filter { inBar(it) && it.feature == Feature.SOLID }) > 100.0) shouldBe true      // about 30 mm x 4 mm at bead spacing
        (openMm(ps.filter { !inBar(it) && it.feature == Feature.SPARSE }) > 100.0) shouldBe true
    }

    "control: a width threshold of 0.01 cell makes nothing narrow: the bar gets grid lines, no loop" {
        val ps = layer20("plan.layer.infill.narrow.cells" to 0.01)
        ps.none { inBar(it) && it.feature == Feature.SPARSE && it.closed } shouldBe true
        ps.any { inBar(it) && it.feature == Feature.SPARSE && !it.closed } shouldBe true
    }
})
