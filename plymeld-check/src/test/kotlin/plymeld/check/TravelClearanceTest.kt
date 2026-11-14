package plymeld.check

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import plymeld.config.Config
import plymeld.config.ConfigFiles
import plymeld.config.ConfigSetup
import plymeld.config.ConfigStack
import plymeld.config.ModelObject
import plymeld.gcode.KlipperWriter
import plymeld.model.SliceSettings
import plymeld.program.Extrude
import plymeld.program.LayerStart
import plymeld.program.PrintProgram
import java.io.StringWriter

/**
 * Travels clear what is printed (Clearance: the bed may move down to get the head around
 * trickier spots). Synthetic programs: a tall line printed first (standing in for a finished object, a support's top, a
 * curved layer's high side), then two short lines at 0.4 mm, and the travel between those two is read from the g-code: the
 * lowest the nozzle is while it moves sideways. Expected heights worked from the rules: the tip over a line it crosses; the
 * cone (45 deg, from the bore's edge) over a line beside it; the head (4 mm above the tip, reaching 70 / 20 / 40 / 35 mm towards
 * -X / +X / -Y / +Y) over a line under it; plus the tool's z-hop (0.2 mm), and a rise straight up before moving. Each with a
 * control that must not lift: the same travel clear of the line, or without the head value that rule needs.
 */
class TravelClearanceTest : StringSpec({
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
    val head = listOf("hardware.tool.t1.head.nozzle.angle" to "45 deg", "hardware.tool.t1.head.nozzle.height" to "4 mm",
        "hardware.tool.t1.head.size.x" to "90 mm", "hardware.tool.t1.head.size.y" to "75 mm", "hardware.tool.t1.head.nozzle.x" to "70 mm", "hardware.tool.t1.head.nozzle.y" to "40 mm")
    /** The tip and its cone alone (no outline: the head's body not counted), to judge those rules apart from it. */
    val cone = head.filter { !it.first.contains("head.size") && !it.first.contains("head.nozzle.x") && !it.first.contains("head.nozzle.y") }
    val box = ModelObject("box", listOf("box"), listOf(1), "box.3mf")
    fun write(entries: List<Pair<String, Any>>, steps: List<plymeld.program.Step>): String {
        val all = ConfigFiles.bundled() + entries.map { (k, v) -> ConfigFiles.Entry(k.split('.'), v, "test") }
        val setup = ConfigSetup.setup(Config(ConfigStack(all), listOf(box))).setup
        val s = SliceSettings.of(setup)
        return StringWriter().also { KlipperWriter(s, setup.foreignTemplateVars, "test").write(PrintProgram(steps, emptyList()), it) }.toString()
    }
    fun line(x0: Double, y0: Double, x1: Double, y1: Double, zMm: Double) =
        Extrude("Outer wall", longArrayOf(Math.round(x0 * 1000), Math.round(y0 * 1000), Math.round(x1 * 1000), Math.round(y1 * 1000)), false, Math.round(zMm * 1000), 440, 200)
    /** A tall line, then a short line ending at [from], then one starting at [to]: the program for one travel, [from] -> [to]. */
    fun program(tall: Extrude, from: Pair<Double, Double>, to: Pair<Double, Double>) = listOf(
        LayerStart(0, 400, 200), tall,
        line(from.first - 1, from.second, from.first, from.second, 0.4), line(to.first, to.second, to.first + 1, to.second, 0.4))
    /** The lowest Z of any sideways move from the last print at [from] to [to] (the travel), and whether it rose before moving. */
    fun travelLow(g: String, from: Pair<Double, Double>, to: Pair<Double, Double>): Double {
        var x = Double.NaN; var y = Double.NaN; var z = Double.NaN
        var inTravel = false; var low = Double.POSITIVE_INFINITY
        val w = Regex("([XYZE])(-?[0-9.]+)")
        for (raw in g.lines()) {
            val c = raw.substringBefore(';').trim()
            if (!c.startsWith("G1 ")) continue
            val m = w.findAll(c).associate { it.groupValues[1] to it.groupValues[2].toDouble() }
            val nx = m["X"] ?: x; val ny = m["Y"] ?: y; val nz = m["Z"] ?: z; val e = m["E"] ?: 0.0
            if (e > 0 && Math.abs(nx - from.first) < 1e-3 && Math.abs(ny - from.second) < 1e-3) { inTravel = true; low = Double.POSITIVE_INFINITY }
            // a wipe (a move that retracts, E < 0) retraces the line just laid at its height: not the travel
            else if (inTravel && e >= 0 && (nx != x || ny != y)) {
                low = minOf(low, z, nz)                                       // the move starts at z and ends at nz
                if (Math.abs(nx - to.first) < 1e-3 && Math.abs(ny - to.second) < 1e-3) return low
            }
            x = nx; y = ny; z = nz
        }
        error("no travel from $from to $to")
    }

    "the tip: a travel crossing a 5 mm line rises over it first (5 + 0.2 hop); control: 10 mm clear of it, no lift (it rises as it goes, from the print height)" {
        val tall = line(100.0, 110.0, 120.0, 110.0, 5.0)
        val g = write(machine + cone, program(tall, 110.0 to 100.0, 110.0 to 120.0))
        travelLow(g, 110.0 to 100.0, 110.0 to 120.0) shouldBe (5.2 plusOrMinus 1e-6)
        val c = write(machine + cone, program(tall, 110.0 to 100.0, 130.0 to 100.0))
        travelLow(c, 110.0 to 100.0, 130.0 to 100.0) shouldBe (0.4 plusOrMinus 1e-6)
    }
    "the cone: a travel 4 mm beside a 5 mm line rises to at least 5 - (4 - 0.2) + 0.2 = 1.4; control: without a nozzle angle, no lift" {
        val tall = line(100.0, 110.0, 120.0, 110.0, 5.0)
        val g = write(machine + cone, program(tall, 90.0 to 106.0, 130.0 to 106.0))
        val low = travelLow(g, 90.0 to 106.0, 130.0 to 106.0)
        // at least the cone's own height there; at most a cell's diagonal (0.71 mm) higher, the grid counting a cell at its closest
        withClue("travel at $low") { (low >= 1.4 - 1e-6 && low <= 1.4 + 0.71 + 1e-6) shouldBe true }
        val noAngle = cone.filter { it.first != "hardware.tool.t1.head.nozzle.angle" }
        travelLow(write(machine + noAngle, program(tall, 90.0 to 106.0, 130.0 to 106.0)), 90.0 to 106.0, 130.0 to 106.0) shouldBe (0.4 plusOrMinus 1e-6)
    }
    "the head: a 10 mm line 20 mm towards +Y (the head reaches 35) lifts the travel to 10 - 4 + 0.2 = 6.2; control: 40 mm away, no lift" {
        val near = line(100.0, 110.0, 120.0, 110.0, 10.0)
        travelLow(write(machine + head, program(near, 90.0 to 90.0, 130.0 to 90.0)), 90.0 to 90.0, 130.0 to 90.0) shouldBe (6.2 plusOrMinus 1e-6)
        val far = line(100.0, 130.0, 120.0, 130.0, 10.0)
        travelLow(write(machine + head, program(far, 90.0 to 90.0, 130.0 to 90.0)), 90.0 to 90.0, 130.0 to 90.0) shouldBe (0.4 plusOrMinus 1e-6)
    }
    "control: without the head's outline the body is not counted (the 10 mm line 20 mm away: no lift)" {
        val near = line(100.0, 110.0, 120.0, 110.0, 10.0)
        val noOutline = head.filter { !it.first.contains("head.size") && !it.first.contains("head.nozzle.x") && !it.first.contains("head.nozzle.y") }
        travelLow(write(machine + noOutline, program(near, 90.0 to 90.0, 130.0 to 90.0)), 90.0 to 90.0, 130.0 to 90.0) shouldBe (0.4 plusOrMinus 1e-6)
    }
})

