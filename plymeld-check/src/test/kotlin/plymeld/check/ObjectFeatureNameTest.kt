package plymeld.check

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import plymeld.config.Config
import plymeld.config.ConfigFiles
import plymeld.config.ConfigSetup
import plymeld.config.ConfigStack
import plymeld.config.ModelObject
import plymeld.gcode.KlipperWriter
import plymeld.model.Context
import plymeld.model.SliceSettings
import plymeld.program.Extrude
import plymeld.program.LayerStart
import plymeld.program.ObjectEnd
import plymeld.program.ObjectStart
import plymeld.program.PrintProgram
import plymeld.program.Step
import java.io.StringWriter

/**
 * Every object's block names its feature before its first extrusion. The writer named a feature only when it
 * changed, so an object whose first feature was the one the object before it ended on went unnamed inside its own block,
 * and the plate check, which reads each block alone, read its wall as missing (a quad tube printed after an elephant
 * ending on an outer wall: "the model's outline has no wall along 141.4 mm"). Two objects, both laying an outer wall in
 * one layer, as the Job merges them; control: the same feature twice inside ONE object's block is named once.
 */
class ObjectFeatureNameTest : StringSpec({
    val machine = listOf(
        "hardware.machine.m.model" to "flashforge-c5", "hardware.machine.m.build.x" to "250 mm", "hardware.machine.m.build.y" to "250 mm",
        "hardware.machine.m.build.z" to "250 mm", "hardware.machine.m.motion.travel.speed" to "300 mm/s", "hardware.machine.m.motion.print.speed" to "300 mm/s",
        "hardware.machine.m.motion.print.acceleration" to "5000 mm/s2", "hardware.machine.m.motion.travel.acceleration" to "= machine.motion.print.acceleration",
        "hardware.machine.m.firmware.flavor" to "klipper", "hardware.machine.m.firmware.arcs" to false, "hardware.machine.m.firmware.exclude_object" to false,
        "hardware.machine.m.gcode.start" to "M83\nT{initial_tool}\nM109 S{first_layer_nozzle_temperature} T{initial_tool}", "hardware.machine.m.gcode.end" to "M104 S0",
        "hardware.tool.t1.machine" to "m", "hardware.tool.t1.index" to 1L, "hardware.tool.t1.bore" to "0.4 mm",
        "hardware.tool.t1.layer.height.min" to "= tool.bore / 5", "hardware.tool.t1.layer.height.max" to "= tool.bore * 0.75",
        "hardware.machine.m.loadout.plate" to "pei", "hardware.machine.m.loadout.tool.t1.filament" to "pla-generic",
        "plan.mechanism.adhesion.prime.enabled" to false)
    val objects = listOf(ModelObject("a", listOf("a"), listOf(1), "a.3mf"), ModelObject("b", listOf("b"), listOf(1), "b.3mf"))
    fun wall(x: Long) = Extrude("Outer wall", longArrayOf(x, 100_000, x + 5_000, 100_000), false, 200, 440, 200)
    fun gcode(steps: List<Step>): String {
        val all = ConfigFiles.bundled() + machine.map { (k, v) -> ConfigFiles.Entry(k.split('.'), v, "test") }
        val setup = ConfigSetup.setup(Config(ConfigStack(all), objects)).setup
        val per = objects.indices.associate { objects[it].name to SliceSettings.of(setup, Context(it)) }
        return StringWriter().also { KlipperWriter(SliceSettings.of(setup), setup.foreignTemplateVars, "test", per).write(PrintProgram(steps, emptyList()), it) }.toString()
    }
    /** Per object block, in file order: whether a feature name came before its first extrusion. */
    fun namedFirst(g: String): List<Boolean> {
        val out = ArrayList<Boolean>(); var inBlock = false; var named = false; var seen = false
        for (l in g.lines()) when {
            l.startsWith(KlipperWriter.OBJECT_START) -> { inBlock = true; named = false; seen = false }
            l.startsWith(KlipperWriter.OBJECT_END) -> inBlock = false
            inBlock && l.startsWith(";TYPE:") -> named = true
            inBlock && !seen && Regex("^G1 X.* E[0-9.]+").containsMatchIn(l) -> { out += named; seen = true }
        }
        return out
    }
    "each object's block names its first feature, even the one the object before it ended on" {
        val g = gcode(listOf(LayerStart(0, 200, 200, 0), ObjectStart("a"), wall(10_000), ObjectEnd("a"), ObjectStart("b"), wall(100_000), ObjectEnd("b")))
        namedFirst(g) shouldBe listOf(true, true)
    }
    "control: the same feature twice inside one object's block is named once" {
        val g = gcode(listOf(LayerStart(0, 200, 200, 0), ObjectStart("a"), wall(10_000), wall(100_000), ObjectEnd("a")))
        g.lines().count { it == ";TYPE:Outer wall" } shouldBe 1
    }
})
