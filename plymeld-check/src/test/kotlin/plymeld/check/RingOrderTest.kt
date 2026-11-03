package plymeld.check

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import plymeld.core.Feature
import plymeld.core.Ordering
import plymeld.core.Planner
import plymeld.core.ToolPath
import plymeld.geom.Polygon

/**
 * A ring pattern's rings are laid as generated (ToolPath.sequence; ordered for travel, half of a top's circle arcs
 * were reversed and the rings laid in bands of opposite direction and order, printed as "two types" of arcs). A 40 x 20 mm
 * block, 30 layers of 0.2 mm, its top in concentric-inside-out: circles round (20, 10), full up to r 10, arcs at the ends.
 */
class RingOrderTest : StringSpec({
    val n = 30
    val tops = LongArray(n) { 200L * (it + 1) }
    val outline = List(n) { listOf(Polygon(longArrayOf(0, 0, 40_000, 0, 40_000, 20_000, 0, 20_000))) }
    val top = Planner.plan(SliceSettings(), outline, tops).last().paths.filter { it.feature == Feature.TOP }
    /** How a path turns about the block's middle: > 0 counter-clockwise. */
    fun turn(p: ToolPath): Double {
        var s = 0.0
        for (k in 0 until p.xy.size / 2 - 1) {
            val ax = p.xy[2 * k] - 20_000.0; val ay = p.xy[2 * k + 1] - 10_000.0
            s += ax * (p.xy[2 * k + 3] - p.xy[2 * k + 1]) - ay * (p.xy[2 * k + 2] - p.xy[2 * k])
        }
        return s
    }
    fun radius(p: ToolPath) = (0 until p.xy.size / 2).map { Math.hypot(p.xy[2 * it] - 20_000.0, p.xy[2 * it + 1] - 10_000.0) }.average()

    "every ring and arc of the top runs counter-clockwise, laid from the middle out; ordered for travel, some ran the other way (the control)" {
        val open = top.filter { !it.closed && it.xy.size >= 6 }
        (open.size > 10) shouldBe true
        open.all { turn(it) > 0 } shouldBe true
        top.map { radius(it) }.zipWithNext().all { (a, b) -> b >= a - 1_000 } shouldBe true
        val travelOrdered = Ordering.order(top.map { ToolPath(it.feature, it.xy, it.closed, it.widthUm, it.heightUm) }, 0, 0)
        travelOrdered.filter { !it.closed && it.xy.size >= 6 }.any { turn(it) < 0 } shouldBe true
    }
})

private fun SliceSettings() = plymeld.model.SliceSettings.of(testSetup("plan.layer.top.pattern" to "concentric-inside-out"))
