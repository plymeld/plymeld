package plymeld.check

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import plymeld.core.IncrementalSlicer
import plymeld.model.Context
import plymeld.model.SliceSettings

/**
 * How long the next tool is heated before a change (IncrementalSlicer.preheatS): a tool that sets no heat.time takes its
 * machine model's toolheads' (the bundled Creator 5's: 15 s); a tool's own wins. Control: a machine of a model whose
 * toolheads have no heat time (the bundled QIDI Q2) preheats not at all.
 */
class PreheatTimeTest : StringSpec({
    fun preheat(model: String, vararg extra: Pair<String, Any>): Double? {
        val setup = testSetup("hardware.machine.tm.model" to model, "hardware.machine.tm.build.x" to "256 mm", "hardware.machine.tm.build.y" to "256 mm",
            "hardware.machine.tm.build.z" to "256 mm", "hardware.machine.tm.gcode.start" to "G90", "hardware.machine.tm.firmware.exclude_object" to false, *extra)
        return IncrementalSlicer.preheatS(setup, Context(0), SliceSettings.of(setup))
    }

    "a Creator 5's tool with no heat.time of its own: the model's 15 s; a tool's own wins" {
        preheat("flashforge-c5") shouldBe 15.0
        preheat("flashforge-c5", "hardware.tool.t1.heat.time" to "30 s") shouldBe 30.0
    }
    "control: a model whose toolheads have no heat time, and a tool that sets none: no preheat" {
        preheat("qidi-q2") shouldBe null
    }
})
