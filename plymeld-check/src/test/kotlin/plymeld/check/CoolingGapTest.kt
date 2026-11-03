package plymeld.check

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
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
 * The minimum layer time counts what other objects print in between. Two objects printed together with
 * layers of different heights: a base in 0.2 mm layers (a long path, minutes per layer) and a pin in 0.1 mm layers (5 mm
 * per layer). The pin's layer that lies alone between two base layers gets its cooling from the base's next layer, so
 * it is not slowed; the pin's path at the end of a base's layer, followed at once by the pin's next layer, is slowed to
 * the minimum layer time, and the base's own path in that layer is not slowed by it. Control: with the base's paths as
 * short as the pin's, the lone layer is slowed as before.
 */
class CoolingGapTest : StringSpec({
    val machine = listOf(
        "hardware.machine.m.model" to "flashforge-c5", "hardware.machine.m.build.x" to "250 mm", "hardware.machine.m.build.y" to "250 mm",
        "hardware.machine.m.build.z" to "250 mm", "hardware.machine.m.motion.travel.speed" to "300 mm/s", "hardware.machine.m.motion.print.speed" to "300 mm/s",
        "hardware.machine.m.motion.print.acceleration" to "5000 mm/s2", "hardware.machine.m.motion.travel.acceleration" to "= machine.motion.print.acceleration",
        "hardware.machine.m.firmware.flavor" to "klipper", "hardware.machine.m.firmware.arcs" to false, "hardware.machine.m.firmware.exclude_object" to false,
        "hardware.machine.m.gcode.start" to "M83\nT{initial_tool}\nM109 S{first_layer_nozzle_temperature} T{initial_tool}", "hardware.machine.m.gcode.end" to "M104 S0",
        "hardware.tool.t1.machine" to "m", "hardware.tool.t1.index" to 1L, "hardware.tool.t1.bore" to "0.4 mm",
        "hardware.tool.t1.layer.height.min" to "= tool.bore / 5", "hardware.tool.t1.layer.height.max" to "= tool.bore * 0.75",
        "hardware.machine.m.loadout.plate" to "pei", "hardware.machine.m.loadout.tool.t1.filament" to "pla-generic",
        "plan.mechanism.adhesion.prime.enabled" to false,
        "hardware.filament.pla-generic.cooling.layer.time.min" to "10 s")
    val base = ModelObject("base", listOf("base"), listOf(1), "base.3mf")
    val pin = ModelObject("pin", listOf("pin"), listOf(1), "pin.3mf")
    /** The pin's path: 5 mm at x 200, on layer [z] (mm), [h] mm thick. */
    fun pinPath(z: Double, h: Double) = Extrude("Outer wall", longArrayOf(200_000, 100_000, 205_000, 100_000), false, Math.round(z * 1000), 440, Math.round(h * 1000))
    /** The base's path: a zigzag of [segments] 100 mm legs between x 10 and 110 (long: minutes at any speed; short: 5 mm). */
    fun basePath(z: Double, segments: Int): Extrude {
        val xy = LongArray(2 * (segments + 1))
        for (i in 0..segments) { xy[2 * i] = if (i % 2 == 0) 10_000L else if (segments == 1) 15_000L else 110_000L; xy[2 * i + 1] = 50_000L + 400L * (i / 2) }
        return Extrude("Outer wall", xy, false, Math.round(z * 1000), 440, 200)
    }
    fun obj(name: String, vararg ps: Step): List<Step> = listOf(ObjectStart(name)) + ps + ObjectEnd(name)
    /** The layers as the Job merges them: the base at 0.2, 0.4, 0.6 with the pin's layer at each; the pin alone at 0.3, 0.5. */
    fun program(baseSegments: Int): List<Step> = listOf(
        listOf(LayerStart(0, 200, 200, 0)), obj("base", basePath(0.2, baseSegments)), obj("pin", pinPath(0.2, 0.2)),
        listOf(LayerStart(1, 300, 100, 1)), obj("pin", pinPath(0.3, 0.1)),
        listOf(LayerStart(2, 400, 200, 1)), obj("base", basePath(0.4, baseSegments)), obj("pin", pinPath(0.4, 0.1)),
        listOf(LayerStart(3, 500, 100, 3)), obj("pin", pinPath(0.5, 0.1)),
        listOf(LayerStart(4, 600, 200, 2)), obj("base", basePath(0.6, baseSegments)), obj("pin", pinPath(0.6, 0.1))).flatten()
    /** The feed rate (mm/min) of the extrusion at height [z] of the pin (x >= 150) or the base. */
    fun feeds(steps: List<Step>): Map<Pair<Double, String>, Double> {
        val all = ConfigFiles.bundled() + machine.map { (k, v) -> ConfigFiles.Entry(k.split('.'), v, "test") }
        val objects = listOf(base, pin)
        val setup = ConfigSetup.setup(Config(ConfigStack(all), objects)).setup
        val per = objects.indices.associate { objects[it].name to SliceSettings.of(setup, Context(it)) }
        val g = StringWriter().also { KlipperWriter(SliceSettings.of(setup), setup.foreignTemplateVars, "test", per).write(PrintProgram(steps, emptyList()), it) }.toString()
        val out = HashMap<Pair<Double, String>, Double>()
        var z = 0.0; var f = 0.0
        val word = Regex("([XYZEF])(-?[0-9.]+)")
        for (line in g.lines()) {
            if (!line.startsWith("G1 ")) continue
            val w = word.findAll(line).associate { it.groupValues[1] to it.groupValues[2].toDouble() }
            w["Z"]?.let { z = it }; w["F"]?.let { f = it }
            val x = w["X"] ?: continue
            if ((w["E"] ?: 0.0) <= 0.0) continue
            val key = Math.round(z * 10) / 10.0 to (if (x >= 150.0) "pin" else "base")
            // the first extrusion of each (layer, object): its feed rate
            if (key !in out) out[key] = f
        }
        return out
    }

    "the pin's lone layer between two base layers is not slowed (the base's next layer cools it); its path at the end of a base layer is slowed; the base's path is not" {
        val f = feeds(program(2000))
        val unslowed = f.getValue(0.4 to "base")
        f.getValue(0.3 to "pin") shouldBe (unslowed plusOrMinus 1.0)
        f.getValue(0.5 to "pin") shouldBe (unslowed plusOrMinus 1.0)
        // the pin's 5 mm at the end of the base's layer, its next layer following at once: slowed (never below the minimum speed)
        (f.getValue(0.4 to "pin") < unslowed * 0.6) shouldBe true
        f.getValue(0.6 to "base") shouldBe (unslowed plusOrMinus 1.0)
    }
    // a curved island's layers are a stack of their own: its layer above is the island's next, however many flat layers of the
    // object come between (read as the object's, each small island layer was slowed as if its next landed at once)
    "one object's island layers between its long flat layers are not slowed; control: the same layers unmarked, slowed" {
        fun islandProgram(marked: Boolean): List<Step> {
            val isl = if (marked) "cap" else null
            return listOf(
                listOf(LayerStart(0, 200, 200, 0)), obj("base", basePath(0.2, 2000)),
                listOf(LayerStart(1, 300, 100, 1, island = isl)), obj("base", pinPath(0.3, 0.1)),
                listOf(LayerStart(2, 400, 200, 2)), obj("base", basePath(0.4, 2000)),
                listOf(LayerStart(3, 500, 100, 3, island = isl)), obj("base", pinPath(0.5, 0.1)),
                listOf(LayerStart(4, 600, 200, 4)), obj("base", basePath(0.6, 2000))).flatten()
        }
        val marked = feeds(islandProgram(true))
        val unslowed = marked.getValue(0.4 to "base")
        marked.getValue(0.3 to "pin") shouldBe (unslowed plusOrMinus 1.0)
        val plain = feeds(islandProgram(false))
        (plain.getValue(0.3 to "pin") < unslowed * 0.6) shouldBe true
    }
    "control: with the base's paths as short as the pin's, the pin's lone layer is slowed as before" {
        val f = feeds(program(1))
        val c = feeds(program(2000))
        (f.getValue(0.3 to "pin") < c.getValue(0.3 to "pin") * 0.6) shouldBe true
    }
})
