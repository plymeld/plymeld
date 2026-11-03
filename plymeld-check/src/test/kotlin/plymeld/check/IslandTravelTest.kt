package plymeld.check

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import plymeld.config.Config
import plymeld.config.ConfigFiles
import plymeld.config.ConfigSetup
import plymeld.config.ConfigStack
import plymeld.config.ModelObject
import plymeld.core.Planner
import plymeld.core.Scarf
import plymeld.gcode.KlipperWriter
import plymeld.geom.Polygon
import plymeld.model.SliceSettings
import plymeld.program.LayerStart
import plymeld.program.PrintProgram
import plymeld.program.Step
import java.io.StringWriter

/**
 * No retraction and no lift for a travel that stays inside its island and crosses no wall (plan.mechanism.motion.no_retract_in_island;
 * without it, a helix island's three runs each retracted, lifted 0.2 mm and restored for 1-2 mm moves inside it).
 * Two 10 mm squares 20 mm apart, 20 layers of 0.2 mm, two wall loops each (walls 0.88 mm thick at 0.44 mm lines), no minimum
 * travel for a retraction, planned and written by plymeld's own planner and writer. The g-code is read on its own terms: per layer, every run of extrusion with
 * whether a retraction (E < 0) and a lift (Z above the run's) came between it and the run before.
 */
class IslandTravelTest : StringSpec({
    val machine = listOf(
        "hardware.machine.m.model" to "flashforge-c5","hardware.machine.m.build.x" to "250 mm", "hardware.machine.m.build.y" to "250 mm",
        "hardware.machine.m.build.z" to "250 mm", "hardware.machine.m.motion.travel.speed" to "300 mm/s", "hardware.machine.m.motion.print.speed" to "300 mm/s",
        "hardware.machine.m.motion.print.acceleration" to "5000 mm/s2", "hardware.machine.m.motion.travel.acceleration" to "= machine.motion.print.acceleration",
        "hardware.machine.m.firmware.flavor" to "klipper", "hardware.machine.m.firmware.arcs" to false, "hardware.machine.m.firmware.exclude_object" to false,
        "hardware.machine.m.gcode.start" to "M83\nT{initial_tool}\nM109 S{first_layer_nozzle_temperature} T{initial_tool}", "hardware.machine.m.gcode.end" to "M104 S0",
        "hardware.tool.t1.machine" to "m", "hardware.tool.t1.index" to 1L, "hardware.tool.t1.bore" to "0.4 mm",
        "hardware.tool.t1.layer.height.min" to "= tool.bore / 5", "hardware.tool.t1.layer.height.max" to "= tool.bore * 0.75",
        "hardware.machine.m.loadout.plate" to "pei", "hardware.machine.m.loadout.tool.t1.filament" to "pla-generic",
        "plan.mechanism.adhesion.prime.enabled" to false,
        "plan.layer.walls.thickness" to "0.88 mm", "plan.layer.walls.outer.line.width" to "0.44 mm", "plan.layer.walls.inner.line.width" to "0.44 mm",
        "plan.layer.walls.style" to "fixed_width",
        // every travel retracts unless the planner says otherwise: the hop between the loops (~0.6 mm) is shorter than the
        // bundled filament's hardware.filament.retract.travel.min, so without this the check could not fail (seen: the control passed
        // with the setting off)
        "hardware.filament.pla-generic.retract.travel.min" to "0 mm")
    val box = ModelObject("box", listOf("box"), listOf(1), "box.3mf")
    fun settings(extra: List<Pair<String, Any>>): SliceSettings {
        val all = ConfigFiles.bundled() + seamLockedForTests + (machine + extra).map { (k, v) -> ConfigFiles.Entry(k.split('.'), v, "test") }
        return SliceSettings.of(ConfigSetup.setup(Config(ConfigStack(all), listOf(box))).setup)
    }
    fun square(x0: Long, y0: Long) = Polygon(longArrayOf(x0, y0, x0 + 10_000, y0, x0 + 10_000, y0 + 10_000, x0, y0 + 10_000))
    val n = 20
    val tops = LongArray(n) { 200L * (it + 1) }
    val outlines = List(n) { listOf(square(100_000, 100_000), square(130_000, 100_000)) }

    /** A run of extrusion: its feature, where it starts (mm), whether a retraction and a lift came before it. */
    class Run(val layer: Int, val feature: String, val x: Double, val y: Double, val retracted: Boolean, val lifted: Boolean)
    fun gcode(extra: List<Pair<String, Any>>): String {
        val s = settings(extra)
        val steps = ArrayList<Step>()
        for (l in Planner.plan(s, outlines, tops)) {
            steps += LayerStart(l.index, l.topZUm, l.heightUm)
            for (p in l.paths) steps += Scarf.extrudes(p, l.topZUm, 0)
        }
        return StringWriter().also { KlipperWriter(s, emptyMap(), "test").write(PrintProgram(steps, emptyList()), it) }.toString()
    }
    fun runs(g: String): List<Run> {
        val out = ArrayList<Run>()
        val w = Regex("([XYZE])(-?[0-9.]+)")
        var x = Double.NaN; var y = Double.NaN; var z = Double.NaN; var layer = -1; var feature = ""
        var inRun = false; var retracted = false; var top = Double.NEGATIVE_INFINITY
        for (raw in g.lines()) {
            val line = raw.trim()
            if (line.startsWith(";LAYER_CHANGE") || line.startsWith("; CHANGE_LAYER")) { layer++; continue }
            if (line.startsWith(";TYPE:") || line.startsWith("; FEATURE:")) { feature = line.substringAfter(':').trim(); continue }
            val c = line.substringBefore(';').trim()
            if (!c.startsWith("G1 ") && !c.startsWith("G2 ") && !c.startsWith("G3 ")) continue
            val m = w.findAll(c).associate { it.groupValues[1] to it.groupValues[2].toDouble() }
            val nx = m["X"] ?: x; val ny = m["Y"] ?: y; val nz = m["Z"] ?: z; val e = m["E"]
            val moves = nx != x || ny != y
            when {
                e != null && e < 0 -> retracted = true
                e != null && e > 0 && moves -> {
                    if (!inRun) out += Run(layer, feature, x, y, retracted, top > nz + 1e-6)
                    inRun = true; retracted = false; top = Double.NEGATIVE_INFINITY
                }
                moves -> inRun = false
            }
            if (!inRun && !nz.isNaN()) top = maxOf(top, nz)
            x = nx; y = ny; z = nz
        }
        return out
    }
    val mid = 10
    fun inA(r: Run) = r.x < 120.0

    "a two-loop island: the travel from its outer loop to its inner loop neither retracts nor lifts; control: the travel to the other island does both" {
        val rs = runs(gcode(emptyList())).filter { it.layer == mid }
        val a = rs.filter { inA(it) }; val b = rs.filter { !inA(it) }
        withClue(rs.joinToString("\n") { "${it.feature} (%.2f, %.2f) retract ${it.retracted} lift ${it.lifted}".format(java.util.Locale.ROOT, it.x, it.y) }) {
            // both islands are there, each with its two loops
            (a.count { it.feature == "Outer wall" } == 1 && a.count { it.feature == "Inner wall" } == 1) shouldBe true
            (b.count { it.feature == "Outer wall" } == 1 && b.count { it.feature == "Inner wall" } == 1) shouldBe true
            // walls first, outer then inner (plan.layer.walls.inner_first = false): the inner loop's run follows the outer loop's
            val ia = a.indexOfFirst { it.feature == "Inner wall" }
            a[ia - 1].feature shouldBe "Outer wall"
            a[ia].retracted shouldBe false
            a[ia].lifted shouldBe false
            // control: entering the other island, 20 mm away across the gap
            val first = rs.indexOf(b.first())
            (first > 0 && inA(rs[first - 1])) shouldBe true
            b.first().retracted shouldBe true
            b.first().lifted shouldBe true
        }
    }

    "control: with plan.mechanism.motion.no_retract_in_island off, the same travel between the two loops retracts and lifts" {
        val rs = runs(gcode(listOf("plan.mechanism.motion.no_retract_in_island" to false))).filter { it.layer == mid && inA(it) }
        val ia = rs.indexOfFirst { it.feature == "Inner wall" }
        rs[ia - 1].feature shouldBe "Outer wall"
        rs[ia].retracted shouldBe true
        rs[ia].lifted shouldBe true
    }
})
