package plymeld.check

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * A machine model's pre-heat for its tools (hardware.model.<key>.tool.heat.time): the bundled Creator 5's is 15 s, a
 * stand-in until measured. Control: a model without one has none.
 */
class ModelPreheatTest : StringSpec({
    "the bundled Creator 5 pre-heats its next tool 15 s, a stand-in; control: the generic Klipper model has no pre-heat" {
        val c5 = plymeld.config.Models.bundled.forModel("flashforge-c5").toolHeat!!
        c5.value shouldBe 15.0
        c5.evidence.kind shouldBe "stand_in"
        plymeld.config.Models.bundled.forModel("generic-klipper").toolHeat shouldBe null
    }
})
