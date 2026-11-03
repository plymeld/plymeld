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
 * The nozzle temperature per layer and per object (the print temperature was never set after the first layer, and
 * an object could not have its own). pla-generic: 210 C to print, its first layer 215 C (bundled). The start template waits for
 * the first layer's; after it the writer sets the print temperature; an object with its own (plan.object.<key>.mechanism.
 * temperature.print) gets its own; printed one after the other, each object's first layer is waited for at its first-layer
 * temperature again. Controls: a first layer and print temperature the same set nothing; an object without its own takes the
 * filament's.
 */
class NozzleTemperatureTest : StringSpec({
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
    val a = ModelObject("a", listOf("a"), listOf(1), "a.3mf")
    val b = ModelObject("b", listOf("b"), listOf(1), "b.3mf")
    fun line(x: Double, z: Double) = Extrude("Outer wall", longArrayOf(Math.round(x * 1000), 100_000, Math.round(x * 1000) + 5_000, 100_000), false, Math.round(z * 1000), 440, 200)
    /** Each object in turn, two layers each (one after the other). */
    fun program(vararg names: String): List<Step> = names.flatMapIndexed { k, n ->
        listOf(ObjectStart(n), LayerStart(2 * k, 200, 200, 0), line(100.0 + 20 * k, 0.2), LayerStart(2 * k + 1, 400, 200, 1), line(100.0 + 20 * k, 0.4), ObjectEnd(n))
    }
    /** The g-code's nozzle temperature commands, in order (the start template's first). */
    fun temps(extra: List<Pair<String, Any>>, objects: List<ModelObject>, steps: List<Step>): List<String> {
        val all = ConfigFiles.bundled() + (machine + extra).map { (k, v) -> ConfigFiles.Entry(k.split('.'), v, "test") }
        val setup = ConfigSetup.setup(Config(ConfigStack(all), objects)).setup
        val per = objects.indices.associate { objects[it].name to SliceSettings.of(setup, Context(it)) }
        val g = StringWriter().also { KlipperWriter(SliceSettings.of(setup), setup.foreignTemplateVars, "test", per).write(PrintProgram(steps, emptyList()), it) }.toString()
        return g.lines().filter { Regex("^M10[49] S[1-9]").containsMatchIn(it) }
    }

    "after the first layer, the print temperature (the first layer's waited for by the start template)" {
        temps(emptyList(), listOf(a), program("a")) shouldBe listOf("M109 S215 T0", "M104 S210 T0")
    }
    "control: a first layer at the print temperature sets nothing more" {
        temps(listOf("hardware.filament.pla-generic.temperature.first_layer" to "210 C"), listOf(a), program("a")) shouldBe listOf("M109 S210 T0")
    }
    "an object's own print temperature; one after the other, each object's first layer waited for again; control: an object without its own takes the filament's" {
        temps(listOf("plan.object.b.mechanism.temperature.print" to "195 C"), listOf(a, b), program("a", "b")) shouldBe
            listOf("M109 S215 T0", "M104 S210 T0", "M109 S215 T0", "M104 S195 T0")
    }
})
