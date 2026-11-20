package plymeld.check

import io.kotest.core.spec.style.StringSpec
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
 * Where an outer wall's ends leave their ooze: two closed outer-wall squares 50 mm apart, counterclockwise (their material
 * on the left), so the travel between them retracts.
 * - The restore after the travel (hardware.filament.retract.return.inside): on, the head comes down one line width inside the
 *   second square's start, restores there, and moves onto the start with nothing pushed. Control: off, it restores at the
 *   start itself.
 * - The wipe as the first square's retraction starts (hardware.filament.retract.wipe.inward): on, every wipe point lies inside
 *   the square, at least most of a line width in from its edges. Control: off, the wipe runs along the edge itself.
 */
class OuterWallEndsTest : StringSpec({
    val machine = listOf(
        "hardware.machine.m.model" to "flashforge-c5", "hardware.machine.m.build.x" to "250 mm", "hardware.machine.m.build.y" to "250 mm",
        "hardware.machine.m.build.z" to "250 mm", "hardware.machine.m.motion.travel.speed" to "300 mm/s", "hardware.machine.m.motion.print.speed" to "120 mm/s",
        "hardware.machine.m.motion.print.acceleration" to "5000 mm/s2", "hardware.machine.m.motion.travel.acceleration" to "= machine.motion.print.acceleration",
        "hardware.machine.m.firmware.flavor" to "klipper", "hardware.machine.m.firmware.arcs" to false, "hardware.machine.m.firmware.exclude_object" to false,
        "hardware.machine.m.gcode.start" to "M83", "hardware.machine.m.gcode.end" to "M104 S0",
        "hardware.tool.t1.machine" to "m", "hardware.tool.t1.index" to 1L, "hardware.tool.t1.bore" to "0.4 mm",
        "hardware.tool.t1.layer.height.min" to "= tool.bore / 5", "hardware.tool.t1.layer.height.max" to "= tool.bore * 0.75",
        "hardware.machine.m.loadout.plate" to "pei", "hardware.machine.m.loadout.tool.t1.filament" to "pla-generic",
        "plan.mechanism.adhesion.prime.enabled" to false)
    val box = ModelObject("box", listOf("box"), listOf(1), "box.3mf")
    /** A closed counterclockwise square, 10 mm a side, from (x, y) mm, at 0.4 mm. */
    fun square(x: Double, y: Double): Extrude {
        val a = Math.round(x * 1000); val b = Math.round(y * 1000)
        return Extrude("Outer wall", longArrayOf(a, b, a + 10_000, b, a + 10_000, b + 10_000, a, b + 10_000), true, 400, 440, 200)
    }
    /** The program's G1 lines (comments cut), with [extra] settings over the machine's. */
    fun lines(vararg extra: Pair<String, Any>): List<String> {
        val all = ConfigFiles.bundled() + (machine + extra).map { (k, v) -> ConfigFiles.Entry(k.split('.'), v, "test") }
        val setup = ConfigSetup.setup(Config(ConfigStack(all), listOf(box))).setup
        val g = StringWriter().also { KlipperWriter(SliceSettings.of(setup), setup.foreignTemplateVars, "test")
            .write(PrintProgram(listOf(LayerStart(0, 200, 200, 0), LayerStart(1, 400, 200, 1), square(100.0, 100.0), square(150.0, 100.0)), emptyList()), it) }.toString()
        return g.lines().map { it.substringBefore(';').trim() }.filter { it.startsWith("G1 ") }
    }
    fun words(c: String) = c.split(' ').associate { it.take(1) to it.drop(1) }

    /** Where the head is (x, y) when the second square's retraction is restored, and the move after it. */
    fun restore(inside: Boolean): Pair<Pair<Double, Double>, String> {
        val ls = lines("hardware.filament.pla-generic.retract.return.inside" to inside, "hardware.filament.pla-generic.retract.wipe.enabled" to false)
        var x = Double.NaN; var y = Double.NaN
        for ((k, c) in ls.withIndex()) {
            val w = words(c)
            w["X"]?.let { x = it.toDouble() }; w["Y"]?.let { y = it.toDouble() }
            val e = w["E"]?.toDoubleOrNull()
            // a restore at the second square (x past 140 mm): plastic pushed standing still
            if (e != null && e > 0 && !w.containsKey("X") && !w.containsKey("Y") && x > 140) return (x to y) to ls.drop(k + 1).first { it.contains('X') || it.contains('Y') }
        }
        error("no restore at the second square")
    }
    /** The wipe's points (x, y) after the first square: the moves that retract while moving. */
    fun wipe(inward: Boolean): List<Pair<Double, Double>> =
        lines("hardware.filament.pla-generic.retract.wipe.enabled" to true, "hardware.filament.pla-generic.retract.wipe.inward" to inward)
            .map { words(it) }.filter { w -> (w["E"]?.toDoubleOrNull() ?: 0.0) < 0 && w.containsKey("X") && w.containsKey("Y") }
            .map { w -> w["X"]!!.toDouble() to w["Y"]!!.toDouble() }.filter { it.first < 140 }

    "restored a line inside the second square's start, then onto it with nothing pushed; control: off, at the start" {
        val (at, next) = restore(true)
        val (atOff, _) = restore(false)
        println("on: restored at $at, then '$next'; off: restored at $atOff")
        // the start is (150, 100); counterclockwise its material is to the left, inside the square: x and y both above
        (at.first > 150.0 && at.second > 100.0) shouldBe true
        (Math.abs(Math.hypot(at.first - 150.0, at.second - 100.0) - 0.44) < 1e-3) shouldBe true
        next.startsWith("G1 X150 Y100") shouldBe true
        next.contains('E') shouldBe false
        atOff shouldBe (150.0 to 100.0)
    }
    "an inner wall loop's wipe steps inward too, with no part of the retraction standing where the loop ended; control: off, along it, part standing" {
        /** The g-code after an inner-wall square then a far outer-wall square: the moves between the inner wall's last line and the travel. */
        fun after(inward: Boolean): List<Map<String, String>> {
            val all = ConfigFiles.bundled() + (machine + listOf("hardware.filament.pla-generic.retract.wipe.enabled" to true,
                "hardware.filament.pla-generic.retract.wipe.inward" to inward)).map { (k, v) -> ConfigFiles.Entry(k.split('.'), v, "test") }
            val setup = ConfigSetup.setup(Config(ConfigStack(all), listOf(box))).setup
            val inner = square(100.0, 100.0).let { Extrude("Inner wall", it.xy, true, 400, 440, 200) }
            val g = StringWriter().also { KlipperWriter(SliceSettings.of(setup), setup.foreignTemplateVars, "test")
                .write(PrintProgram(listOf(LayerStart(0, 200, 200, 0), LayerStart(1, 400, 200, 1), inner, square(150.0, 100.0)), emptyList()), it) }.toString()
            val ls = g.lines().map { it.substringBefore(';').trim() }.filter { it.startsWith("G1 ") }.map { words(it) }
            // after the inner wall's last line (the last extruding move in the first square, x under 140 mm)
            val lastInner = ls.indexOfLast { (it["E"]?.toDoubleOrNull() ?: 0.0) > 0 && it.containsKey("X") && it["X"]!!.toDouble() < 140 }
            return ls.drop(lastInner + 1).takeWhile { (it["E"]?.toDoubleOrNull() ?: 0.0) < 0 }
        }
        val inward = after(true)
        val along = after(false)
        println("inner wall's end, inward: $inward; along: $along")
        // inward: every retracting move moves (none standing), each point inside the inner square
        inward.all { it.containsKey("X") || it.containsKey("Y") } shouldBe true
        inward.filter { it.containsKey("X") }.all { val px = it["X"]!!.toDouble(); val py = it["Y"]!!.toDouble(); px > 100.3 && px < 109.7 && py > 100.3 && py < 109.7 } shouldBe true
        // control: off, the retract_before share stands still first
        along.first().containsKey("X") shouldBe false
    }
    "the outer wall's wipe inside the square; control: off, along its edge" {
        val inward = wipe(true)
        val along = wipe(false)
        println("wipe inward: $inward; along: $along")
        /** How far (mm) a point lies inside the first square's edges (negative: outside). */
        fun inset(p: Pair<Double, Double>) = minOf(p.first - 100.0, 110.0 - p.first, p.second - 100.0, 110.0 - p.second)
        inward.isNotEmpty() shouldBe true
        inward.all { inset(it) > 0.3 } shouldBe true
        along.isNotEmpty() shouldBe true
        along.all { Math.abs(inset(it)) < 1e-3 } shouldBe true
    }
})
