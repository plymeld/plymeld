package plymeld.check

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import plymeld.core.Planner
import plymeld.geom.Polygon
import plymeld.model.SliceSettings

/**
 * Shells on layers of varying height (Planner.shellCounts): a layer is solid exactly when less than
 * the shell's thickness of the part lies above it. Found in a comparison with Flash Studio: the
 * count took the layer's own height, and a top shell came out 0.75 of 0.8 mm.
 */
class ShellCountsTest : StringSpec({
    val cfg = arrayOf<Pair<String, Any>>("plan.layer.top.thickness" to "0.8 mm", "plan.layer.bottom.thickness" to "0 mm")
    // a 20 mm square, layer heights 0.2, six of 0.25, then two of 0.125: the top at 1.95 mm
    val tops = longArrayOf(200, 450, 700, 950, 1200, 1450, 1700, 1825, 1950)
    val outlines = List(tops.size) { listOf(Polygon(longArrayOf(0, 0, 20_000, 0, 20_000, 20_000, 0, 20_000))) }

    "0.75 mm of part above layer 4 (< 0.8): solid; 1.0 mm above layer 3: sparse (control)" {
        val s = SliceSettings.of(testSetup(*cfg))
        val w = Planner.walls(s, outlines, tops)
        val r = Planner.regions(s, w, Planner.shellCounts(s, tops))
        fun area(ps: List<Polygon>) = ps.sumOf { it.signedAreaMm2() }
        val inner4 = area(r[4].inner)
        (area(r[4].internalSolid) > 0.99 * inner4) shouldBe true
        area(r[4].sparse) shouldBe 0.0
        (area(r[3].sparse) > 0.99 * area(r[3].inner)) shouldBe true
    }
})
