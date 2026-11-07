package plymeld.gcode

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe

/**
 * A travel's length for the minimum-layer-time estimate (KlipperWriter.travelMm): through its detour's points where it has
 * one, as the head goes. A travel from (0, 0) to (10, 0) round a point at (5, 5) is 2 × √50 mm; control: the same travel
 * straight is 10 mm.
 */
class TravelLengthTest : StringSpec({
    "a detour's length through its points; control: straight" {
        KlipperWriter.travelMm(0.0, 0.0, longArrayOf(5000, 5000), 10.0, 0.0) shouldBe (2 * Math.sqrt(50.0) plusOrMinus 1e-9)
        KlipperWriter.travelMm(0.0, 0.0, null, 10.0, 0.0) shouldBe (10.0 plusOrMinus 1e-9)
    }
})
