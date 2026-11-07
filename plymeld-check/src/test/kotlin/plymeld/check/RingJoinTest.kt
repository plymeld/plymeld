package plymeld.check

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import plymeld.core.Feature
import plymeld.core.Planner
import plymeld.core.SurfaceFill
import plymeld.geom.Polygon

/**
 * A concentric top's rings joined into one path where they lie side by side (SurfaceFill.joined): one start and one end
 * for the run, each ring after the first started at its point nearest where the last ended, so the rings no longer leave
 * a radial line of starts and stops. Three squares 1 mm apart; control: a step limit under the spacing keeps three closed
 * rings, each its own start.
 */
class RingJoinTest : StringSpec({
    fun square(h: Long) = SurfaceFill.Piece(longArrayOf(10_000 - h, 10_000 - h, 10_000 + h, 10_000 - h, 10_000 + h, 10_000 + h, 10_000 - h, 10_000 + h), true)
    val rings = listOf(square(5_000), square(4_000), square(3_000))

    "three rings a spacing apart are one path, each ring closed before the step to the next; control: a shorter step limit, three rings" {
        val one = SurfaceFill.joined(rings, 2_000).single()
        one.closed shouldBe false
        one.xy.size / 2 shouldBe 3 * 5                                                         // each ring's 4 corners and back to its start
        (one.xy[0] to one.xy[1]) shouldBe (5_000L to 5_000L)                                    // the first ring's own start
        // the step from the first ring's end (its start again) to the second's nearest corner: one spacing on each axis
        Math.hypot((one.xy[10] - one.xy[8]).toDouble(), (one.xy[11] - one.xy[9]).toDouble()) shouldBe Math.hypot(1_000.0, 1_000.0)
        SurfaceFill.joined(rings, 500).map { it.closed } shouldBe listOf(true, true, true)
    }

    "a concentric-outside-in top on a block is one path; control: as rings, it was one per ring" {
        val n = 20
        val tops = LongArray(n) { 200L * (it + 1) }
        val outline = List(n) { listOf(Polygon(longArrayOf(0, 0, 20_000, 0, 20_000, 20_000, 0, 20_000))) }
        val setup = testSetup("plan.layer.top.pattern" to "concentric-outside-in")
        val top = Planner.plan(plymeld.model.SliceSettings.of(setup), outline, tops).last().paths.filter { it.feature == Feature.TOP }
        top.size shouldBe 1
        // the rings it holds: about half the block's width over the spacing
        (top.single().xy.size / 2 > 4 * 10) shouldBe true
    }
})
