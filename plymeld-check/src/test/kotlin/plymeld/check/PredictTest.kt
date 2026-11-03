package plymeld.check

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import plymeld.core.Predict

class PredictTest : StringSpec({
    "short layers are grouped into 1-based ranges with the shortest time of each" {
        val p = Predict.Prediction(0.0, 0.0, "test", listOf(10.0, 3.0, 4.0, 10.0, 2.0))
        p.shortLayers(8.0) shouldBe "3 of 5 layers predicted under 8.0 s: 2-3 (shortest 3.0 s), 5 (shortest 2.0 s)"
    }

    "control: no layer under the minimum gives nothing to report" {
        Predict.Prediction(0.0, 0.0, "test", listOf(10.0, 9.0)).shortLayers(8.0) shouldBe null
        Predict.Prediction(null, 0.0, "time not predicted").shortLayers(8.0) shouldBe null
    }
})
