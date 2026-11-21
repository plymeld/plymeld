package plymeld.check

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
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
 * for the first tool and after each tool change for the new one — with the machine model's own command in its unit; a
 * value with no command to write it is refused; a machine that sets its own gets none; the g-code check holds every command
 * to the config's value, but on a plate that sets its own.
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

    "written with the machine model's own command, in its unit (hardware.model.<key>.pressure_advance)" {
        val ms = testSetup("hardware.model.qidi-q2.pressure_advance.unit" to "ms")
        fun with(st: plymeld.model.Setup) = StringWriter().also { KlipperWriter(SliceSettings.of(st), st.foreignTemplateVars, "test", pressureAdvance = mapOf(0 to 0.02)).write(program, it) }.toString()
        with(ms).lines() shouldContain "SET_PRESSURE_ADVANCE ADVANCE=20"
        with(setup).lines() shouldContain "SET_PRESSURE_ADVANCE ADVANCE=0.02"      // control: the bundled unit, seconds
        val own = testSetup("hardware.model.qidi-q2.pressure_advance.command" to "M900 K{pressure_advance}", "hardware.model.qidi-q2.command.M900" to "purpose: test")
        with(own).lines() shouldContain "M900 K0.02"
    }

    "a model with no command to set it: a value set is refused, nothing written; with none set the print is written as before" {
        val none = testSetup("hardware.model.qidi-q2.pressure_advance.command" to "")
        fun with(pa: Map<Int, Double>) = StringWriter().also { KlipperWriter(SliceSettings.of(none), none.foreignTemplateVars, "test", pressureAdvance = pa).write(program, it) }.toString()
        val e = shouldThrow<IllegalStateException> { with(mapOf(0 to 0.02)) }
        e.message!! shouldContain "pressure_advance.command"
        with(emptyMap()) shouldNotContain "SET_PRESSURE_ADVANCE"
    }

    "a machine that sets its own (firmware.own_pressure_advance): none, whatever the machine, the tool, or the filament sets" {
        val vals = arrayOf<Pair<String, Any>>("hardware.machine.tm.pressure_advance" to "0.02 s", "hardware.tool.t1.pressure_advance" to "0.03 s", "hardware.filament.test-pla.pressure_advance" to "0.05 s")
        testSetup(*vals).pressureAdvanceByTool() shouldBe mapOf(0 to 0.05)                                   // control: not its own
        testSetup(*vals, "hardware.machine.tm.firmware.own_pressure_advance" to true).pressureAdvanceByTool() shouldBe emptyMap()
    }

    "the g-code check: the config's value at each tool's start passes; another value, one where none is set, or a change mid-print is caught; a plate that sets its own passes" {
        val st = testSetup("hardware.tool.t1.pressure_advance" to "0.03 s")
        val one = PrintProgram(listOf(LayerStart(0, 200, 200), Extrude("Outer wall", line, false, 200, 440, 200, tool = 0),
            Extrude("Outer wall", longArrayOf(100_000, 110_000, 120_000, 110_000), false, 200, 440, 200, tool = 0)), emptyList())
        val text = StringWriter().also { KlipperWriter(SliceSettings.of(st), st.foreignTemplateVars, "test", pressureAdvance = st.pressureAdvanceByTool()).write(one, it) }.toString()
        fun says(t: String, plate: Boolean = false) = plymeld.core.check.GcodeCheck.check(t, plymeld.core.check.Expectations.forSetup(st, pressureAdvanceSetByPlate = plate)).filter { "pressure advance" in it }
        text shouldContain "SET_PRESSURE_ADVANCE ADVANCE=0.03"
        says(text) shouldBe emptyList()
        says(text.replace("ADVANCE=0.03", "ADVANCE=0.04")).single() shouldContain "the config's is 0.03 s"
        val midPrint = text.lines().toMutableList().also { val k = it.indexOfLast { l -> l.startsWith("G1 X") && " E" in l }; it.add(k, "SET_PRESSURE_ADVANCE ADVANCE=0.1") }.joinToString("\n")
        says(midPrint).single() shouldContain "set to 0.1"
        says(midPrint, plate = true) shouldBe emptyList()
        val unset = testSetup()
        plymeld.core.check.GcodeCheck.check(text, plymeld.core.check.Expectations.forSetup(unset)).filter { "pressure advance" in it }.single() shouldContain "where the config sets none"
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
