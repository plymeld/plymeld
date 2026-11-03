package plymeld.check

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import plymeld.gcode.KlipperWriter
import plymeld.model.SliceSettings
import plymeld.model.Sourced
import plymeld.model.pressureAdvanceByTool
import plymeld.program.Extrude
import plymeld.program.LayerStart
import plymeld.program.PrintProgram
import plymeld.program.ToolChange
import java.io.StringWriter

/**
 * Pressure advance (CONTRIBUTING.md): the firmware's by default, so with none set plymeld
 * writes none; set per tool, it is written when that tool starts printing — after the start template
 * for the first tool and after each tool change for the new one.
 */
class PressureAdvanceTest : StringSpec({
    val setup = testSetup()
    val s = SliceSettings.of(setup)
    val line = longArrayOf(100_000, 100_000, 120_000, 100_000)
    val program = PrintProgram(listOf(
        LayerStart(0, 200, 200), Extrude("Outer wall", line, false, 200, 440, 200, tool = 0),
        ToolChange(1, "b", 210, 0, 150), Extrude("Outer wall", line, false, 200, 440, 200, tool = 1),
    ), emptyList())
    fun gcode(pa: Map<Int, Double>) = StringWriter().also { KlipperWriter(s, setup.foreignTemplateVars, "test", pressureAdvance = pa).write(program, it) }.toString()

    "none set: none written (control)" {
        gcode(emptyMap()) shouldNotContain "SET_PRESSURE_ADVANCE"
    }

    "set per tool: the first tool's before its first path, the next tool's after the change" {
        val lines = gcode(mapOf(0 to 0.02, 1 to 0.045)).lines()
        val first = lines.indexOf("SET_PRESSURE_ADVANCE ADVANCE=0.02")
        val change = lines.indexOf("T1")
        val second = lines.indexOf("SET_PRESSURE_ADVANCE ADVANCE=0.045")
        val firstPath = lines.indexOfFirst { it.startsWith("G1 X") && it.contains(" E") }
        (first in 0 until firstPath) shouldBe true
        (second > change) shouldBe true
    }

    "a tool with none set gets none written" {
        gcode(mapOf(1 to 0.045)).lines().count { it.startsWith("SET_PRESSURE_ADVANCE") } shouldBe 1
    }

    "the machine's, unless the tool's, unless the filament's" {
        val st = testSetup()
        val tool = st.spools.first().tool
        st.pressureAdvanceByTool()[tool] shouldBe null                         // control: none set
        st.machine["hardware.machine.pressure_advance"] = Sourced(0.02, "test: machine")
        st.pressureAdvanceByTool()[tool] shouldBe 0.02
        st.tools[tool]["hardware.tool.pressure_advance"] = Sourced(0.03, "test: tool")
        st.pressureAdvanceByTool()[tool] shouldBe 0.03
        for (v in st.materialTool.values) v["hardware.filament.pressure_advance"] = Sourced(0.05, "test: filament")
        st.pressureAdvanceByTool()[tool] shouldBe 0.05
    }
})
