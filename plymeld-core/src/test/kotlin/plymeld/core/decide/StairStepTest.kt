package plymeld.core.decide

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.shouldBe

/**
 * The stair-step one face shows (PoseDecision.stairStep): terrace edges per mm x each edge's depth to the
 * depth power. The references are worked by hand from the terrace's geometry: on a face at angle a to its layers of height h,
 * the edges lie h / sin(a) apart along it and each is h x |nz| deep.
 */
class StairStepTest : StringSpec({
    fun rad(deg: Double) = Math.toRadians(deg)
    // flat layers: a face at angle a to level leans cos(a) (|nz|) and its angle to its layer is a
    fun flat(h: Double, deg: Double, power: Double = 2.0) = PoseDecision.stairStep(h, Math.cos(rad(deg)), Math.cos(rad(deg)), power)

    "flat layers on a 6 degree slope: h x sin x cos squared (0.08 mm: 0.00827 mm); level and vertical faces show none" {
        flat(0.08, 6.0) shouldBe (0.08 * Math.sin(rad(6.0)) * Math.cos(rad(6.0)) * Math.cos(rad(6.0)) plusOrMinus 1e-12)
        flat(0.08, 6.0) shouldBe (0.00827 plusOrMinus 0.00001)
        flat(0.08, 0.0) shouldBe (0.0 plusOrMinus 1e-12)
        flat(0.08, 90.0) shouldBe (0.0 plusOrMinus 1e-12)
    }

    "thinner layers score better on any slope at the bundled power; at power 1 the layer height drops out" {
        for (deg in listOf(5.0, 20.0, 45.0, 70.0)) {
            flat(0.08, deg) shouldBeLessThan flat(0.2, deg)
            flat(0.08, deg, 1.0) shouldBe (flat(0.2, deg, 1.0) plusOrMinus 1e-12)
        }
    }

    "a face 1 degree off its curved layer, leaning as a 6 degree slope, shows under a fifth of flat layers' stair-step there; the depth alone (the earlier measure, the control) cannot tell them apart" {
        val lean = Math.cos(rad(6.0))
        val curved = PoseDecision.stairStep(0.08, lean, Math.cos(rad(1.0)), 2.0)
        curved shouldBeLessThan flat(0.08, 6.0) / 5
        // the control: depth alone, h x |nz|, the same for both faces whatever their angle to their layers
        val depthCurved = 0.08 * lean; val depthFlat = 0.08 * lean
        (depthCurved < depthFlat / 5) shouldBe false
        curved shouldBeGreaterThan 0.0
    }
})
