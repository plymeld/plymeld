package plymeld.core.decide

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import plymeld.model.Overhang

/**
 * support's measures are scored from one pad, ln(1 + value / unit) against the baseline's; droop counts its
 * area times its severity, 1 at the allowed hang, the cap for a horizontal face over nothing.
 */
class ScoringRulesTest : StringSpec({
    val w = Weights.parse("quality=1", "test")

    "a unit log: the first pad costs ln 2, 25 pads ln 26, and the baseline's own value scores 0 (control: linear against the surface read 25 pads as 3.6 %)" {
        val values = mapOf<Metric, (Double) -> Double>(Metric.SUPPORT_INTERFACE to { it })
        val r = Scoring.rank(listOf(0.0, 4.0, 100.0), values, w, mapOf(Metric.SUPPORT_INTERFACE to 4.0), mapOf(Metric.SUPPORT_INTERFACE to 0.0))
        r.first { it.item == 0.0 }.total shouldBe (0.0 plusOrMinus 1e-12)
        r.first { it.item == 4.0 }.total shouldBe (Math.log(2.0) plusOrMinus 1e-12)
        r.first { it.item == 100.0 }.total shouldBe (Math.log(26.0) plusOrMinus 1e-12)
        r.first { it.item == 100.0 }.plain[Metric.SUPPORT_INTERFACE]!!.contains("in units of 4") shouldBe true
        // control: the old scale, 2800 mm² of surface, linear
        (100.0 / 2800.0 < 0.04) shouldBe true
    }
    "the joint search's support cost follows the same curve exactly: 25 pads of interface cost ln 26 from a baseline of none" {
        Scoring.linearised(mapOf(Metric.SUPPORT_INTERFACE to 100.0), w, listOf(Metric.SUPPORT_INTERFACE), mapOf(Metric.SUPPORT_INTERFACE to 4.0), mapOf(Metric.SUPPORT_INTERFACE to 0.0)) shouldBe (Math.log(26.0) plusOrMinus 1e-12)
    }
    "droop severity: a face at the allowed hang counts 1, twice the hang 2, a horizontal face the cap; a face that holds itself up is never asked" {
        val oh = Overhang(0.5, 0.44)                       // a line may hang 0.22 mm past the one below
        // the tilt from horizontal whose step per 0.2 mm layer is exactly the hang: down = cos(tilt)
        val tiltAtLimit = Math.atan2(0.2, 0.22)
        PoseDecision.severity(Math.cos(tiltAtLimit), 0.2, oh) shouldBe (1.0 plusOrMinus 1e-9)
        val tiltAtTwice = Math.atan2(0.2, 0.44)
        PoseDecision.severity(Math.cos(tiltAtTwice), 0.2, oh) shouldBe (2.0 plusOrMinus 1e-9)
        PoseDecision.severity(1.0, 0.2, oh) shouldBe (PoseDecision.SEVERITY_CAP plusOrMinus 1e-12)
        oh.needsSupport(Math.cos(Math.atan2(0.2, 0.1)), 0.2) shouldBe false
    }
})
