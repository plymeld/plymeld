package plymeld.check

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
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
import plymeld.program.ToolChange
import java.io.StringWriter

/**
 * The feed rates the writer writes (KlipperWriter), read from its g-code: a printer stops the print at F0 (a line of no
 * length under the steady share's cap was written `G1 F0`, beside a preheat comment of "NaN s of printing", and a print
 * died there). One object of two tools, four layers, each an outer wall square, an outer wall of no length (its two points
 * one), and an inner wall; a tool change before the third layer, with its preheat walked back over the line of no length.
 *  - with the steady share set: no feed of 0 or below and no NaN or Infinity anywhere, the line of no length still written
 *    (its feed line), and the preheat's time a number; the minimum layer time on;
 *  - a speed setting of 0: the write fails, naming the line; control: the same program at a speed above 0 is written;
 *  - a speed above 0 that rounds to F0 (0.005 mm/s, 0.3 mm/min) is written F1; control: 0.05 mm/s is F3, as rounded.
 * The bounds are the test's own reading of each move's F word, independent of the writer.
 */
class FeedRateTest : StringSpec({
    val machine = listOf(
        "hardware.machine.m.model" to "flashforge-c5", "hardware.machine.m.build.x" to "250 mm", "hardware.machine.m.build.y" to "250 mm",
        "hardware.machine.m.build.z" to "250 mm", "hardware.machine.m.motion.travel.speed" to "300 mm/s", "hardware.machine.m.motion.print.speed" to "300 mm/s",
        "hardware.machine.m.motion.print.acceleration" to "5000 mm/s2", "hardware.machine.m.motion.travel.acceleration" to "= machine.motion.print.acceleration",
        "hardware.machine.m.firmware.flavor" to "klipper", "hardware.machine.m.firmware.arcs" to false, "hardware.machine.m.firmware.exclude_object" to false,
        "hardware.machine.m.gcode.start" to "M83\nT{initial_tool}\nM109 S{first_layer_nozzle_temperature} T{initial_tool}", "hardware.machine.m.gcode.end" to "M104 S0",
        "hardware.tool.t1.machine" to "m", "hardware.tool.t1.index" to 1L, "hardware.tool.t1.bore" to "0.4 mm",
        "hardware.tool.t1.layer.height.min" to "= tool.bore / 5", "hardware.tool.t1.layer.height.max" to "= tool.bore * 0.75",
        "hardware.tool.t2.like" to "t1", "hardware.tool.t2.index" to 2L,
        "hardware.machine.m.loadout.plate" to "pei", "hardware.machine.m.loadout.tool.t1.filament" to "pla-generic",
        "hardware.machine.m.loadout.tool.t2.filament" to "pla-generic",
        "plan.mechanism.adhesion.prime.enabled" to false,
        "hardware.filament.pla-generic.cooling.slowdown.enabled" to true, "hardware.filament.pla-generic.cooling.layer.time.min" to "8 s")
    val o = ModelObject("o", listOf("o"), listOf(1), "o.3mf")
    fun um(mm: Double) = Math.round(mm * 1000)
    /** An outer wall square of side [side] mm at (x0, y0), as a loop. */
    fun square(x0: Double, y0: Double, side: Double, z: Double, tool: Int) =
        Extrude("Outer wall", longArrayOf(um(x0), um(y0), um(x0 + side), um(y0), um(x0 + side), um(y0 + side), um(x0), um(y0 + side)), true, um(z), 440, 200, tool)
    /** An outer wall of no length at (x, y): its two points one (as a thin area's center line can come out). */
    fun none(x: Double, y: Double, z: Double, tool: Int) = Extrude("Outer wall", longArrayOf(um(x), um(y), um(x), um(y)), false, um(z), 300, 200, tool)
    fun inner(y: Double, z: Double, tool: Int) = Extrude("Inner wall", longArrayOf(um(60.0), um(y), um(90.0), um(y)), false, um(z), 450, 200, tool)
    val steps: List<Step> = (0 until 4).flatMap { i ->
        val z = 0.2 * (i + 1); val tool = if (i < 2) 0 else 1
        val change = if (i == 2) listOf<Step>(ToolChange(1, "spool 2", tempC = 210, fromTool = 0, fromIdleC = 170)) else emptyList()
        listOf<Step>(LayerStart(i, um(z), 200, i), ObjectStart("o"), inner(70.0, z, tool), square(60.0, 80.0, 5.0, z, tool), none(85.0, 85.0, z, tool), ObjectEnd("o")).let {
            if (i == 2) change + it else it }
    }
    fun write(program: List<Step>, preheatS: Double?, vararg extra: Pair<String, Any>): List<String> {
        val all = ConfigFiles.bundled() + (machine + extra).map { (k, v) -> ConfigFiles.Entry(k.split('.'), v, "test") }
        val setup = ConfigSetup.setup(Config(ConfigStack(all), listOf(o))).setup
        return StringWriter().also { KlipperWriter(SliceSettings.of(setup), setup.foreignTemplateVars, "test", mapOf("o" to SliceSettings.of(setup, Context(0))), preheatS = preheatS)
            .write(PrintProgram(program, emptyList()), it) }.toString().lines()
    }
    val fWord = Regex("(?<=\\s)F(\\S*)")
    /** Every move (G0-G3) whose F word is not a number above 0, with its line number. */
    fun deadFeeds(g: List<String>) = g.withIndex().filter { (_, l) ->
        val c = l.substringBefore(';').trim()
        Regex("^G[0-3]\\s").containsMatchIn(c) && fWord.findAll(c).any { m -> !((m.groupValues[1].toDoubleOrNull() ?: Double.NaN) > 0) }
    }.map { (i, l) -> "line ${i + 1}: $l" }
    val nonFinite = Regex("NaN|Infinity")
    /** The feed lines (`G1 F<n>`) whose next command is a retraction: a line that laid nothing. */
    fun emptyLines(g: List<String>) = g.indices.filter { i ->
        Regex("^G1 F\\S+$").matches(g[i].trim()) && g.drop(i + 1).firstOrNull { it.isNotBlank() && !it.trim().startsWith(";") }?.trim()?.startsWith("G1 E-") == true
    }

    "a line of no length with the steady share set: no feed of 0 or below and no NaN or Infinity; the line is there, the preheat's time a number" {
        val g = write(steps, 30.0, "plan.mechanism.motion.steady.share" to "90%")
        val empty = emptyLines(g)
        println("steady share 90 %: ${g.size} lines, ${empty.size} lines of no length written as a feed alone (${empty.take(3).joinToString { g[it] }}); " +
            "preheat: ${g.firstOrNull { "preheat" in it }}")
        deadFeeds(g) shouldBe emptyList()
        g.filter { nonFinite.containsMatchIn(it) } shouldBe emptyList()
        (empty.size >= 4) shouldBe true                                              // the case holds the lines it is about, one a layer
        g.single { "preheat" in it } shouldContain Regex("preheat, \\d+ s of printing")
        // the share is in force: the square's last layer is laid slower than with it unset (the minimum layer time off for
        // this, as it slows both)
        val off = "hardware.filament.pla-generic.cooling.slowdown.enabled" to false
        val capped = write(steps, 30.0, "plan.mechanism.motion.steady.share" to "90%", off)
        val free = write(steps, 30.0, off)
        // the feed line last set before the square's first side on the last layer
        fun squareFeed(g: List<String>) = g.subList(0, g.indexOfLast { it.startsWith("G1 X65 Y80 E") }).last { Regex("^G1 F\\S+$").matches(it.trim()) }
        println("the square's feed on the last layer: ${squareFeed(capped)} with the share, ${squareFeed(free)} without")
        (squareFeed(capped) != squareFeed(free)) shouldBe true
        deadFeeds(capped) shouldBe emptyList()
        capped.filter { nonFinite.containsMatchIn(it) } shouldBe emptyList()
    }

    "a speed setting of 0: the write fails, naming the line; control: above 0, written" {
        write(steps, null, "plan.mechanism.motion.visible.speed" to "100 mm/s").let { deadFeeds(it) shouldBe emptyList() }
        val x = shouldThrow<IllegalStateException> { write(steps, null, "plan.mechanism.motion.visible.speed" to "0 mm/s") }
        println(x.message)
        // (the first layer's lines at the first layer's speed: the first outer wall at the visible speed is the second layer's)
        x.message!! shouldContain "a feed rate of 0.0 mm/s at the 'Outer wall' line starting at X60 Y80 on the layer at Z0.4"
        x.message!! shouldContain "a printer stops the print at F0"
    }

    // Every kind of number the writer writes, a non-finite one injected where the program or the writer's inputs take it:
    // the write fails, naming it, and nothing at or after the bad line reaches the output. Control: the same program with
    // the value finite is written.
    "a number that is not one, of every kind (fan, wait, acceleration, pressure advance, a template's), fails the write; control: finite, written" {
        val all = ConfigFiles.bundled() + machine.map { (k, v) -> ConfigFiles.Entry(k.split('.'), v, "test") }
        val setup = ConfigSetup.setup(Config(ConfigStack(all), listOf(o))).setup
        val s = SliceSettings.of(setup)
        fun go(program: PrintProgram, pa: Map<Int, Double> = emptyMap()): String {
            val w = StringWriter()
            KlipperWriter(s, setup.foreignTemplateVars, "test", mapOf("o" to SliceSettings.of(setup, Context(0))), pressureAdvance = pa).write(program, w)
            return w.toString()
        }
        fun refused(what: String, block: () -> String): String {
            val x = shouldThrow<IllegalStateException> { block() }
            println("$what: ${x.message}")
            return x.message!!
        }
        val layer = listOf<Step>(LayerStart(0, 200, 200, 0), ObjectStart("o"), square(60.0, 80.0, 5.0, 0.2, 0), ObjectEnd("o"))
        // the part fan (SetFan): rounded, NaN would have been written M106 S0, the fan off
        go(PrintProgram(layer + plymeld.program.SetFan(50.0), emptyList())) shouldContain "M106 S128"
        refused("fan") { go(PrintProgram(layer + plymeld.program.SetFan(Double.NaN), emptyList())) } shouldContain "a part fan speed of NaN at the 'Outer wall' line"
        // a wait (Park): Infinity would have been written as the largest whole number
        go(PrintProgram(layer + plymeld.program.Park(100_000, 100_000, 2.0), emptyList())) shouldContain "G4 P2000"
        refused("wait") { go(PrintProgram(layer + plymeld.program.Park(100_000, 100_000, Double.POSITIVE_INFINITY), emptyList())) } shouldContain "a wait (ms) of Infinity"
        // an acceleration (a calibration cell's own)
        fun cell(accel: Double) = PrintProgram(listOf(LayerStart(0, 200, 200, 0), Extrude("Outer wall", longArrayOf(60_000, 80_000, 70_000, 80_000), false, 200, 440, 200,
            exact = plymeld.program.Exact("cell", doubleArrayOf(20.0), accel, 1.0, 0.8))), emptyList(), exact = true)
        go(cell(2000.0)) shouldContain Regex("(M204 S|ACCEL=)2000")
        refused("acceleration") { go(cell(Double.NaN)) } shouldContain "an acceleration of NaN"
        // a number written with decimals (pressure advance, e()): written "NaN", refused at its line by CommandCheck, which names it
        go(PrintProgram(layer, emptyList()), pa = mapOf(0 to 0.04)) shouldContain "ADVANCE=0.04"
        refused("pressure advance") { go(PrintProgram(layer, emptyList()), pa = mapOf(0 to Double.NaN)) } shouldContain "'ADVANCE=NaN' is not a finite number"
        // (a template's placeholder: BadNumbersTest)
        // the guard on every line written (CommandCheck): the line refused before it is passed on, the feature, object and layer named
        fun through(text: String): Pair<String, Throwable?> {
            val w = StringWriter(); val c = plymeld.gcode.CommandCheck(w, s.machineDescription)
            val x = runCatching { c.write(text); c.finish() }.exceptionOrNull()
            return w.toString() to x
        }
        val head = "; FEATURE: Outer wall\n${KlipperWriter.OBJECT_START}o\n; Z_HEIGHT: 0.4\n"
        through(head + "G1 X1 Y1 F600\nG1 X2 Y2 E0.1\n").let { (out, x) -> x shouldBe null; out shouldBe head + "G1 X1 Y1 F600\nG1 X2 Y2 E0.1\n" }
        through(head + "G1 X1 Y1 F0\nG1 X2 Y2 E0.1\n").let { (out, x) ->
            println("CommandCheck: ${x?.message}")
            out shouldBe head
            x!!.message!! shouldContain "output line 4, 'G1 X1 Y1 F0': a move at feed rate F0, no speed"
            x.message!! shouldContain "Written for the Outer wall of object 'o' on the layer at Z0.4"
        }
    }

    "a speed above 0 that rounds to F0 is written F1; control: 0.05 mm/s is F3" {
        fun feeds(g: List<String>) = g.filter { Regex("^G1 F\\S+$").matches(it.trim()) }.map { it.trim().removePrefix("G1 F") }.toSet()
        val slow = write(steps, null, "plan.mechanism.motion.speed.max" to "0.005 mm/s")
        deadFeeds(slow) shouldBe emptyList()
        ("1" in feeds(slow)) shouldBe true
        val less = write(steps, null, "plan.mechanism.motion.speed.max" to "0.05 mm/s")
        ("3" in feeds(less)) shouldBe true
        ("1" in feeds(less)) shouldBe false
    }
})
