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
 * plan.mechanism.cooling.slowdown.style (a gap found reviewing a slice: the outer wall slowed to a different speed
 * every layer while the inner walls kept theirs). One object, each layer an inner wall (hidden, at y < 100) and an outer
 * wall (visible, at y >= 150), a 10 s minimum layer time. hidden_first: while the inner wall can give the time, the outer
 * wall keeps its speed; when it cannot, the inner wall is at the minimum speed and the outer wall gives the rest.
 * Controls: even slows the outer wall in the first case; with no minimum layer time to meet nothing is slowed.
 */
class CoolingSlowdownTest : StringSpec({
    val machine = listOf(
        "hardware.machine.m.model" to "flashforge-c5", "hardware.machine.m.build.x" to "250 mm", "hardware.machine.m.build.y" to "250 mm",
        "hardware.machine.m.build.z" to "250 mm", "hardware.machine.m.motion.travel.speed" to "300 mm/s", "hardware.machine.m.motion.print.speed" to "300 mm/s",
        "hardware.machine.m.motion.print.acceleration" to "5000 mm/s2", "hardware.machine.m.motion.travel.acceleration" to "= machine.motion.print.acceleration",
        "hardware.machine.m.firmware.flavor" to "klipper", "hardware.machine.m.firmware.arcs" to false, "hardware.machine.m.firmware.exclude_object" to false,
        "hardware.machine.m.gcode.start" to "M83\nT{initial_tool}\nM109 S{first_layer_nozzle_temperature} T{initial_tool}", "hardware.machine.m.gcode.end" to "M104 S0",
        "hardware.tool.t1.machine" to "m", "hardware.tool.t1.index" to 1L, "hardware.tool.t1.bore" to "0.4 mm",
        "hardware.tool.t1.layer.height.min" to "= tool.bore / 5", "hardware.tool.t1.layer.height.max" to "= tool.bore * 0.75",
        "hardware.machine.m.loadout.plate" to "pei", "hardware.machine.m.loadout.tool.t1.filament" to "pla-generic",
        "plan.mechanism.adhesion.prime.enabled" to false, "hardware.filament.pla-generic.cooling.speed.min" to "20 mm/s")
    val o = ModelObject("o", listOf("o"), listOf(1), "o.3mf")
    /** A zigzag path [len] mm long in legs of at most 50 mm along x from x 10, starting at y [y0] (rising 1 mm a leg). */
    fun path(feature: String, y0: Double, len: Double, z: Double): Extrude {
        val pts = ArrayList<Long>(); var left = len; var x = 10.0; var y = y0; var dir = 1
        pts += Math.round(x * 1000); pts += Math.round(y * 1000)
        while (left > 1e-9) {
            val leg = minOf(50.0, left); x += dir * leg; left -= leg
            pts += Math.round(x * 1000); pts += Math.round(y * 1000)
            if (left > 1e-9) { y += 1.0; left -= 1.0; pts += Math.round(x * 1000); pts += Math.round(y * 1000); dir = -dir }
        }
        return Extrude(feature, pts.toLongArray(), false, Math.round(z * 1000), 440, 200)
    }
    // the inner wall in front (y < 100), the outer wall behind (y >= 150)
    fun program(innerMm: Double, outerMm: Double): List<Step> = (0 until 4).flatMap { i ->
        val z = 0.2 * (i + 1)
        listOf(LayerStart(i, Math.round(z * 1000), 200, i), ObjectStart("o"), path("Inner wall", 20.0, innerMm, z), path("Outer wall", 150.0, outerMm, z), ObjectEnd("o"))
    }
    /** Feed rates (mm/min) of the inner and outer walls on layer 3 (z 0.6), with [extra] config values. */
    fun feeds(steps: List<Step>, vararg extra: Pair<String, Any>): Pair<Double, Double> {
        val all = ConfigFiles.bundled() + (machine + extra).map { (k, v) -> ConfigFiles.Entry(k.split('.'), v, "test") }
        val setup = ConfigSetup.setup(Config(ConfigStack(all), listOf(o))).setup
        val g = StringWriter().also { KlipperWriter(SliceSettings.of(setup), setup.foreignTemplateVars, "test", mapOf("o" to SliceSettings.of(setup, Context(0)))).write(PrintProgram(steps, emptyList()), it) }.toString()
        var z = 0.0; var f = 0.0; var inner = Double.NaN; var outer = Double.NaN
        val word = Regex("([XYZEF])(-?[0-9.]+)")
        for (line in g.lines()) {
            if (!line.startsWith("G1 ")) continue
            val w = word.findAll(line).associate { it.groupValues[1] to it.groupValues[2].toDouble() }
            w["Z"]?.let { z = it }; w["F"]?.let { f = it }
            w["X"] ?: continue
            val y = w["Y"] ?: continue
            if ((w["E"] ?: 0.0) <= 0.0 || Math.abs(z - 0.6) > 1e-6) continue
            if (y < 100) { if (inner.isNaN()) inner = f } else if (outer.isNaN()) outer = f
        }
        return inner to outer
    }
    val noMin = arrayOf<Pair<String, Any>>("hardware.filament.pla-generic.cooling.layer.time.min" to "0 s")
    val min10 = arrayOf<Pair<String, Any>>("hardware.filament.pla-generic.cooling.layer.time.min" to "10 s")

    "hidden_first: a long inner wall gives the time, the outer wall keeps its speed; control: even slows the outer wall too" {
        val steps = program(innerMm = 300.0, outerMm = 50.0)
        val free = feeds(steps, *noMin)
        val hf = feeds(steps, *min10, "plan.mechanism.cooling.slowdown.style" to "hidden_first")
        val ev = feeds(steps, *min10, "plan.mechanism.cooling.slowdown.style" to "even")
        println("inner / outer F, no minimum: $free; hidden_first: $hf; even: $ev")
        hf.second shouldBe (free.second plusOrMinus 1.0)               // the visible line untouched
        (hf.first < free.first * 0.5) shouldBe true                   // the hidden line gave the time
        (ev.second < free.second * 0.5) shouldBe true                 // control: even slows the visible line
    }

    // plan.mechanism.cooling.dwell.enabled: a layer at the floor still short waits the rest, parked where the head (60 x 60 mm,
    // the nozzle in its middle: 30 mm each way) clears the layer's printed area (x 10..60, y 20..151), within the 250 mm bed
    "dwell: the short layer waits the rest beside the layer, the head clear of it; controls: none, and no head size" {
        val steps = program(innerMm = 60.0, outerMm = 100.0)
        val head = arrayOf<Pair<String, Any>>("hardware.tool.t1.head.size.x" to "60 mm", "hardware.tool.t1.head.size.y" to "60 mm",
            "hardware.tool.t1.head.nozzle.x" to "30 mm", "hardware.tool.t1.head.nozzle.y" to "30 mm")
        fun gcode(vararg extra: Pair<String, Any>): String {
            val all = ConfigFiles.bundled() + (machine + extra).map { (k, v) -> ConfigFiles.Entry(k.split('.'), v, "test") }
            val setup = ConfigSetup.setup(Config(ConfigStack(all), listOf(o))).setup
            return StringWriter().also { KlipperWriter(SliceSettings.of(setup), setup.foreignTemplateVars, "test", mapOf("o" to SliceSettings.of(setup, Context(0)))).write(PrintProgram(steps, emptyList()), it) }.toString()
        }
        val parked = gcode(*min10, *head, "plan.mechanism.cooling.dwell.enabled" to true, "plan.mechanism.cooling.dwell.style" to "park").lines()
        val waits = parked.withIndex().filter { it.value.startsWith("G4 P") }
        println("waits: " + waits.map { it.value } + "; park moves: " + waits.map { parked[it.index - 1] })
        waits.size shouldBe 3                                         // after layers 0-2 (the last layer has none after it)
        for ((k, line) in waits) {
            val s = line.removePrefix("G4 P").toDouble() / 1000
            (s > 0.5 && s < 2.5) shouldBe true                        // 10 s asked, 8 s of lines at 20 mm/s, the travel
            val move = Regex("X(-?[0-9.]+) Y(-?[0-9.]+)").find(parked[k - 1])!!.groupValues
            val px = move[1].toDouble(); val py = move[2].toDouble()
            // the head's edge clear of the printed area: the nozzle 30 mm beyond its right side or behind its back
            (px >= 60 + 30 - 0.01 || py >= 151 + 30 - 0.01) shouldBe true
            (px in 0.0..250.0 && py in 0.0..250.0) shouldBe true
        }
        gcode(*min10, *head, "plan.mechanism.cooling.dwell.enabled" to false).lines().count { it.startsWith("G4 P") } shouldBe 0   // control
        val noHead = gcode(*min10, "plan.mechanism.cooling.dwell.enabled" to true, "plan.mechanism.cooling.dwell.style" to "park")
        noHead.lines().count { it.startsWith("G4 P") } shouldBe 0     // control: no head size, no wait
        noHead.contains("no wait: the head's size or the bed is not set") shouldBe true
    }

    // the bundled dwell: on, lift: retracted, straight up dwell.lift where the layer ended, the wait, then
    // down again for the next layer; no head size needed
    "dwell, bundled: lift in place, the wait, back down; control: dwell off" {
        val steps = program(innerMm = 60.0, outerMm = 100.0)
        fun gcode(vararg extra: Pair<String, Any>): List<String> {
            val all = ConfigFiles.bundled() + (machine + extra).map { (k, v) -> ConfigFiles.Entry(k.split('.'), v, "test") }
            val setup = ConfigSetup.setup(Config(ConfigStack(all), listOf(o))).setup
            return StringWriter().also { KlipperWriter(SliceSettings.of(setup), setup.foreignTemplateVars, "test", mapOf("o" to SliceSettings.of(setup, Context(0)))).write(PrintProgram(steps, emptyList()), it) }.toString().lines()
        }
        val g = gcode(*min10)
        val waits = g.withIndex().filter { it.value.startsWith("G4 P") }
        println("lift dwells: " + waits.map { (k, w) -> "${g[k - 1]} / $w" })
        waits.size shouldBe 3
        for ((k, w) in waits) {
            val s = w.removePrefix("G4 P").toDouble() / 1000
            (s > 0.5 && s < 2.5) shouldBe true
            // the move before the wait: Z only, 5 mm (the bundled lift) above the layer just printed
            val lift = g[k - 1]
            lift.startsWith("G1 Z") shouldBe true
            lift.contains("X") shouldBe false
            val zUp = Regex("Z(-?[0-9.]+)").find(lift)!!.groupValues[1].toDouble()
            val layerZ = g.subList(0, k).last { it.startsWith(";Z:") || it.startsWith("; Z_HEIGHT:") }.substringAfter(":").trim().toDouble()
            zUp shouldBe (layerZ + 5.0 plusOrMinus 0.25)                   // within the travel lift above the layer
            g[k - 2].startsWith("G1 E-") || g.subList(k - 6, k).any { it.startsWith("G1 E-") } shouldBe true   // retracted first
        }
        gcode(*min10, "plan.mechanism.cooling.dwell.enabled" to false).count { it.startsWith("G4 P") } shouldBe 0   // control
    }

    "hidden_first: a short inner wall cannot give enough: it is at the minimum speed and the outer wall slows for the rest" {
        val steps = program(innerMm = 60.0, outerMm = 100.0)
        val free = feeds(steps, *noMin)
        val hf = feeds(steps, *min10, "plan.mechanism.cooling.slowdown.style" to "hidden_first")
        println("inner / outer F, no minimum: $free; hidden_first: $hf")
        hf.first shouldBe (20.0 * 60 plusOrMinus 1.0)                 // the floor, 20 mm/s
        (hf.second < free.second * 0.5) shouldBe true
    }
})
