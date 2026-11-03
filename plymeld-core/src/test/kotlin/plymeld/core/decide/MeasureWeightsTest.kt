package plymeld.core.decide

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe

/**
 * A goal's weight is split over its measures in proportion to the measures' own weights (job.priorities.measure.<measure>,
 * default 1): layer lines at 4 take half of quality among five measures; a measure at 0 takes nothing; with
 * no measure weights the split is equal (the control, and the rule earlier apart from the addition on top).
 */
class MeasureWeightsTest : StringSpec({
    val quality = listOf(Metric.UNSUPPORTED_AREA, Metric.SUPPORT_INTERFACE, Metric.SURFACE_STEP, Metric.LAYER_LINES, Metric.ROUGH_TOP_AREA)

    "layer lines at 4 take half of quality's weight among five measures; the goal's weight is split, not added to" {
        val e = Weights.parse("quality=1,speed=2,layer_lines=4", "test").effective(quality + Metric.TIME_ESTIMATE)
        e[Metric.LAYER_LINES]!! shouldBe (0.5 plusOrMinus 1e-9)
        e[Metric.UNSUPPORTED_AREA]!! shouldBe (0.125 plusOrMinus 1e-9)
        quality.sumOf { e.getValue(it) } shouldBe (1.0 plusOrMinus 1e-9)
        e[Metric.TIME_ESTIMATE]!! shouldBe (2.0 plusOrMinus 1e-9)
    }
    "a measure at 0 takes nothing and the rest share the goal; control: without measure weights the split is equal" {
        val e = Weights.parse("quality=1,layer_lines=0", "test").effective(quality)
        e[Metric.LAYER_LINES]!! shouldBe (0.0 plusOrMinus 1e-9)
        e[Metric.SURFACE_STEP]!! shouldBe (0.25 plusOrMinus 1e-9)
        val equal = Weights.parse("quality=1", "test").effective(quality)
        quality.all { Math.abs(equal.getValue(it) - 0.2) < 1e-9 } shouldBe true
    }
})
