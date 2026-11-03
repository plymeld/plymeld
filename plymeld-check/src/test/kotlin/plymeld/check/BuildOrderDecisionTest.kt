package plymeld.check

import plymeld.model.Overhang
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.doubles.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import plymeld.core.decide.Arrange
import plymeld.core.decide.PrintTechnique
import plymeld.core.decide.TechniqueDecision
import plymeld.core.decide.JobFacts
import plymeld.core.decide.ObjectOrder
import plymeld.core.decide.ObjectOrderDecision
import plymeld.core.decide.PoseFacts
import plymeld.core.decide.Weights
import plymeld.config.Models


/**
 * Step 3, split: each object's TECHNIQUE, and the plate's OBJECT ORDER.
 * Every option is chosen, blocked on a named fact, not applicable / not possible, not fitting, or
 * not implemented — never skipped.
 */
class BuildOrderDecisionTest : StringSpec({
    val q2 = Models.bundled.forModel("qidi-q2")
    val bed = PoseFacts(0.0, 0.0, 270.0, 270.0, 256.0, Overhang.fromAngle(30.0, 0.2, 0.4), "test", layerHeightMm = 0.2)
    val jf = JobFacts(bed, 0.42, "test", 0.2, 0.4, brimReachMm = 5.0)
    val w = Weights.parse("quality=5,speed=2,material=0,reliability=1", "test")
    // a short block (20 x 20 x 10 mm) and a column taller than the Q2's 47 mm rod clearance (20 x 20 x 55 mm)
    val shapes = mapOf("block.stl" to { testBox(0f, 0f, 0f, 20f, 20f, 10f) }, "column.stl" to { testBox(0f, 0f, 0f, 20f, 20f, 55f) })
    fun plate(n: String, k: Int) = (1..k).map { "$n#$it" to listOf(shapes.getValue(n)()) }
    val uniform = { k: Int -> List(k) { plymeld.core.decide.TechniquePlan.whole(PrintTechnique.FLAT_LAYERS) } }

    "technique on the Q2: uniform; curved layers and strands blocked on the missing tip cone" {
        val d = TechniqueDecision.decide("block", q2, 0)
        d.value shouldBe PrintTechnique.FLAT_LAYERS
        d.alternatives.first { it.label == PrintTechnique.CURVED_LAYERS.label }.rejected!! shouldContain "blocked: no head.nozzle.angle and head.nozzle.height for toolhead 1"
    }

    "one object: printed together; one-at-a-time not applicable" {
        val d = ObjectOrderDecision.decide(q2, setOf(0), plate("block.stl", 1), uniform(1), jf, w)
        d.value.order shouldBe ObjectOrder.TOGETHER
        d.alternatives.first { it.label == ObjectOrder.ONE_AT_A_TIME.label }.rejected shouldBe "not applicable: one object"
    }

    // the trivial case: one object locked to one at a time is printed
    // so, alone; the control above (unlocked) still prints it together
    "one object locked to one at a time: printed so" {
        val d = ObjectOrderDecision.decide(q2, setOf(0), plate("block.stl", 1), uniform(1), jf, w, ObjectOrder.ONE_AT_A_TIME)
        d.value.order shouldBe ObjectOrder.ONE_AT_A_TIME
        d.value.placed.size shouldBe 1
    }

    "two blocks one at a time: spaced by head radius + brim reach" {
        val d = ObjectOrderDecision.decide(q2, setOf(0), plate("block.stl", 2), uniform(2), jf, w, ObjectOrder.ONE_AT_A_TIME)
        Arrange.minGap(d.value.placed) shouldBeGreaterThanOrEqual 80.0          // 75 (QIDI's profile) + 5 brim reach
    }

    // speed is time: travel does not stand in for it
    "without a time prediction the decision says speed is not measured, and travel does not decide" {
        val d = ObjectOrderDecision.decide(q2, setOf(0), plate("block.stl", 2), uniform(2), jf, w)
        d.facts.any { "goal 'speed' has weight but nothing this decision measures" in it } shouldBe true
        println("two blocks, no prediction: " + d.alternatives.joinToString(" | ") { "${it.label}: ${it.score}" })
    }

    "printed together, brims may merge: objects need only the gap that keeps their walls apart" {
        val d = ObjectOrderDecision.decide(q2, setOf(0), plate("block.stl", 2), uniform(2), jf, w, ObjectOrder.TOGETHER)
        Arrange.minGap(d.value.placed) shouldBe (0.42 plusOrMinus 1e-3)   // mesh coordinates are 32-bit floats
    }

    "two 55 mm columns: one at a time refused, the first column is taller than the 47 mm rod clearance" {
        val d = ObjectOrderDecision.decide(q2, setOf(0), plate("column.stl", 2), uniform(2), jf, w)
        d.value.order shouldBe ObjectOrder.TOGETHER
        d.alternatives.first { it.label == ObjectOrder.ONE_AT_A_TIME.label }.rejected!! shouldContain "nozzle-to-rod clearance"
    }

    "objects built other ways print together too (coordination phase 5: each object's own layers, in its own order, merged by height); one at a time still can" {
        val mixed = listOf(PrintTechnique.FLAT_LAYERS, PrintTechnique.CURVED_LAYERS).map { plymeld.core.decide.TechniquePlan.whole(it) }
        val d = ObjectOrderDecision.decide(q2, setOf(0), plate("block.stl", 2), mixed, jf, w)
        d.alternatives.first { it.label == ObjectOrder.TOGETHER.label }.rejected shouldBe null
        d.alternatives.first { it.label == ObjectOrder.ONE_AT_A_TIME.label }.rejected shouldBe null
    }

    "control: locking a blocked technique fails with the missing fact" {
        shouldThrow<IllegalStateException> { TechniqueDecision.decide("block", q2, 0, PrintTechnique.CURVED_LAYERS) }.message shouldContain "head.nozzle.angle"
    }
})

