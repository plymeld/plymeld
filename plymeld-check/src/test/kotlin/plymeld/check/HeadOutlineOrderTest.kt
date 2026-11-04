package plymeld.check

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeGreaterThanOrEqual
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import plymeld.core.decide.Arrange
import plymeld.core.decide.PrintTechnique
import plymeld.core.decide.JobFacts
import plymeld.core.decide.ObjectOrder
import plymeld.core.decide.ObjectOrderDecision
import plymeld.core.decide.PoseFacts
import plymeld.core.decide.TechniquePlan
import plymeld.core.decide.Weights
import plymeld.config.Models
import plymeld.mesh.TriangleSoup
import plymeld.model.Overhang

/** A closed box as 12 outward triangles. */
private fun box(x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float): TriangleSoup {
    val v = arrayOf(floatArrayOf(x0, y0, z0), floatArrayOf(x1, y0, z0), floatArrayOf(x1, y1, z0), floatArrayOf(x0, y1, z0),
        floatArrayOf(x0, y0, z1), floatArrayOf(x1, y0, z1), floatArrayOf(x1, y1, z1), floatArrayOf(x0, y1, z1))
    val f = intArrayOf(0, 2, 1, 0, 3, 2, 4, 5, 6, 4, 6, 7, 0, 1, 5, 0, 5, 4, 1, 2, 6, 1, 6, 5, 2, 3, 7, 2, 7, 6, 3, 0, 4, 3, 4, 7)
    val out = FloatArray(12 * 9)
    for (t in 0 until 12) for (k in 0 until 3) for (d in 0 until 3) out[9 * t + 3 * k + d] = v[f[3 * t + k]][d]
    return TriangleSoup(out, 12)
}

/**
 * One at a time with the print head's outline: a head reaching 50 mm towards -X, 20 towards +X and 30
 * each way in Y (a synthetic head, 70 x 60 mm, the nozzle 50 mm from
 * the left, 30 from the front),
 * a 5 mm brim reach. The objects printed first go on the head's short side: 25 mm apart in X (20 + 5), where the
 * description's clearance circle (92 mm, Flash Studio's profile) wants 97.
 */
class HeadOutlineOrderTest : StringSpec({
    val bundled = Models.bundled.forModel("flashforge-c5")
    // the bundled C5 with its heads' measured underside taken out: one at a time then keeps to the clearance circle
    val plain = bundled.copy(toolheads = bundled.toolheads.map { t -> t.copy(geometry = t.geometry - plymeld.machine.Toolhead.Tier.UNDERSIDE.keys.toSet()) })
    val reach = Arrange.Reach(50.0, 20.0, 30.0, 30.0)
    val c5 = plain.withHead(0, mapOf("head_minus_x" to reach.minusX, "head_plus_x" to reach.plusX, "head_minus_y" to reach.minusY, "head_plus_y" to reach.plusY), "test head", "test")
    val bed = PoseFacts(0.0, 0.0, 250.0, 250.0, 250.0, Overhang.fromAngle(30.0, 0.2, 0.4), "test", layerHeightMm = 0.2)
    val jf = JobFacts(bed, 0.42, "test", 0.2, 0.4, brimReachMm = 5.0)
    val w = Weights.parse("quality=5,speed=2,material=0,reliability=1", "test")
    // four 20 mm cubes of rising height (the print order is shortest first), all under the C5's 60 mm rod
    val cubes = (1..4).map { "cube$it" to listOf(box(0f, 0f, 0f, 20f, 20f, 5f * it)) }
    val techniques = List(4) { TechniquePlan.whole(PrintTechnique.FLAT_LAYERS) }

    "with the outline: 25 mm apart in X, the first printed on the right, and no head reaches an earlier object" {
        val d = ObjectOrderDecision.decide(c5, setOf(0), cubes, techniques, jf, w, ObjectOrder.ONE_AT_A_TIME)
        val p = d.value.placed
        p.map { it.name } shouldBe listOf("cube1", "cube2", "cube3", "cube4")
        Arrange.headClear(p, reach, 5.0).shouldBeNull()
        for (k in 1 until p.size) (p[k - 1].cx - p[k].cx) shouldBe (45.0 plusOrMinus 1e-3)   // 20 mm cube + 25 mm gap, leftwards
        Arrange.minGap(p) shouldBe (25.0 plusOrMinus 1e-3)
    }
    "control: the same placement printed in the reverse order is refused (the head's long side over the earlier objects)" {
        val p = ObjectOrderDecision.decide(c5, setOf(0), cubes, techniques, jf, w, ObjectOrder.ONE_AT_A_TIME).value.placed
        Arrange.headClear(p.reversed(), reach, 5.0).shouldNotBeNull() shouldContain "would reach"
    }
    "without the outline: the description's clearance circle, 92 + 5 mm apart" {
        val d = ObjectOrderDecision.decide(plain, setOf(0), cubes, techniques, jf, w, ObjectOrder.ONE_AT_A_TIME)
        Arrange.minGap(d.value.placed) shouldBeGreaterThanOrEqual 97.0 - 1e-3
        // control: the bundled C5 has its heads' underside (measured), so `plain` really is the one without it
        bundled.toolhead(0)!!.has(plymeld.machine.Toolhead.Tier.UNDERSIDE) shouldBe true
        plain.toolhead(0)!!.has(plymeld.machine.Toolhead.Tier.UNDERSIDE) shouldBe false
    }
    "locked: an earlier object 30 mm to the right of a later one passes (over 20 + 5)" {
        val pair = listOf("low" to listOf(box(150f, 100f, 0f, 170f, 120f, 5f)), "high" to listOf(box(100f, 100f, 0f, 120f, 120f, 10f)))
        val d = ObjectOrderDecision.decide(c5, setOf(0), pair, techniques.take(2), jf, w, ObjectOrder.ONE_AT_A_TIME, keepPlacement = true)
        d.value.placed.first().cx shouldBeGreaterThan d.value.placed.last().cx
    }
    "in the order given (plan.objects.order = as_given): the taller first when given first, on the right where its head clears" {
        val pair = listOf("high" to listOf(box(150f, 100f, 0f, 170f, 120f, 10f)), "low" to listOf(box(100f, 100f, 0f, 120f, 120f, 5f)))
        val d = ObjectOrderDecision.decide(c5, setOf(0), pair, techniques.take(2), jf, w, ObjectOrder.ONE_AT_A_TIME, keepPlacement = true, sequenceAsGiven = true)
        d.value.placed.map { it.name } shouldBe listOf("high", "low")
    }
    "control: the same pair, shortest first (unset), prints the low one first, and so is refused (its head over the taller one)" {
        val pair = listOf("high" to listOf(box(150f, 100f, 0f, 170f, 120f, 10f)), "low" to listOf(box(100f, 100f, 0f, 120f, 120f, 5f)))
        val d = ObjectOrderDecision.decide(c5, setOf(0), pair, techniques.take(2), jf, w, keepPlacement = true)
        d.alternatives.first { it.label == ObjectOrder.ONE_AT_A_TIME.label }.rejected.shouldNotBeNull() shouldContain "would reach 'low'"
    }
    "control: in the order given, the head's long side over an earlier object is still refused" {
        val pair = listOf("low" to listOf(box(100f, 100f, 0f, 120f, 120f, 5f)), "high" to listOf(box(150f, 100f, 0f, 170f, 120f, 10f)))
        val d = ObjectOrderDecision.decide(c5, setOf(0), pair, techniques.take(2), jf, w, keepPlacement = true, sequenceAsGiven = true)
        d.alternatives.first { it.label == ObjectOrder.ONE_AT_A_TIME.label }.rejected.shouldNotBeNull() shouldContain "would reach 'low'"
    }
    "control: locked with the earlier object 30 mm to the LEFT, under the head's 50 + 5, is refused" {
        val pair = listOf("low" to listOf(box(100f, 100f, 0f, 120f, 120f, 5f)), "high" to listOf(box(150f, 100f, 0f, 170f, 120f, 10f)))
        val d = ObjectOrderDecision.decide(c5, setOf(0), pair, techniques.take(2), jf, w, keepPlacement = true)
        d.alternatives.first { it.label == ObjectOrder.ONE_AT_A_TIME.label }.rejected.shouldNotBeNull() shouldContain "would reach 'low'"
    }
})
