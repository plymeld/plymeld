package plymeld.check

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldStartWith
import plymeld.core.Predict

class PredictTest : StringSpec({
    "short layers are grouped into 1-based ranges with the shortest time of each" {
        val p = Predict.Prediction(0.0, 0.0, "test", listOf(10.0, 3.0, 4.0, 10.0, 2.0))
        p.shortLayers(8.0) shouldBe "3 of 5 layers predicted under 8.0 s: 2-3 (shortest 3.0 s), 5 (shortest 2.0 s)"
    }

    "the prediction as a command prints it: the time and filament, then a line for each place its motion limits come from, then the tool changes; each limit once, every line within 200 characters" {
        val machine = plymeld.config.Models.bundled.forModel("flashforge-c5")
        val g = "M83\nT0\nG1 X10 Y10 F6000\nG1 X20 Y10 E1 F3000\nT1\nG1 X30 Y10 E1\nG1 Z5 F600\n"
        val p = Predict.gcode(g, machine, 2.0, mapOf("max_velocity" to 300.0, "max_accel" to 5000.0))
        val lines = p.lines(" (a note)")
        lines.first() shouldStartWith "predicted: "
        lines.first() shouldEndWith " (a note)"
        lines[1] shouldBe "predicted with the motion limits from the config: max_velocity 300.0, max_accel 5000.0"
        val fromMachine = lines.drop(2).dropLast(1)
        (fromMachine.isNotEmpty() && fromMachine.all { it.startsWith("predicted with the motion limits from the ${machine.name} (") }) shouldBe true
        for (k in listOf("max_velocity", "max_accel", "max_z_velocity", "max_z_accel", "square_corner_velocity")) withClue(k) { lines.count { Regex("""\b$k \d""").containsMatchIn(it) } shouldBe 1 }
        lines.last() shouldBe "predicted with 1 tool changes x 2.0 s (hardware.machine.<key>.tool_change.time)"
        lines.filter { it.length > 200 }.shouldBeEmpty()
        // control: the same facts in one line, as the prediction's own text has them, run over the limit
        (p.toString().length > 200) shouldBe true
        // a machine missing a limit: no time, and why, on its own line
        val none = Predict.gcode(g, machine.copy(motion = emptyMap()), null)
        none.lines() shouldBe listOf("predicted: time not predicted, filament 2 mm", "predicted without a time: ${none.why}")
    }

    "control: no layer under the minimum gives nothing to report" {
        Predict.Prediction(0.0, 0.0, "test", listOf(10.0, 9.0)).shortLayers(8.0) shouldBe null
        Predict.Prediction(null, 0.0, "time not predicted").shortLayers(8.0) shouldBe null
    }
})
