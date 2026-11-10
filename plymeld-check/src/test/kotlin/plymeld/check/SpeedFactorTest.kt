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
 * The print-speed factor (plan.mechanism.motion.speed.factor) scales every printing speed and leaves travels as they are:
 * the same program written at 100 % and at 50 %, each printing move's feed halved (an outer wall, an inner wall, sparse
 * infill, on the first layer and above; the machine printing at 120 mm/s, so every speed is under the filament's melt cap,
 * which applies after the factor), each travel's the same. Control: at 100 % the feeds are the plain settings' (the
 * visible lines' 100 mm/s, the hidden lines' 120).
 */
class SpeedFactorTest : StringSpec({
    val machine = listOf(
        "hardware.machine.m.model" to "flashforge-c5", "hardware.machine.m.build.x" to "250 mm", "hardware.machine.m.build.y" to "250 mm",
        "hardware.machine.m.build.z" to "250 mm", "hardware.machine.m.motion.travel.speed" to "300 mm/s", "hardware.machine.m.motion.print.speed" to "120 mm/s",
        "hardware.machine.m.motion.print.acceleration" to "5000 mm/s2", "hardware.machine.m.motion.travel.acceleration" to "= machine.motion.print.acceleration",
        "hardware.machine.m.firmware.flavor" to "klipper", "hardware.machine.m.firmware.arcs" to false, "hardware.machine.m.firmware.exclude_object" to false,
        "hardware.machine.m.gcode.start" to "M83", "hardware.machine.m.gcode.end" to "M104 S0",
        "hardware.tool.t1.machine" to "m", "hardware.tool.t1.index" to 1L, "hardware.tool.t1.bore" to "0.4 mm",
        "hardware.tool.t1.layer.height.min" to "= tool.bore / 5", "hardware.tool.t1.layer.height.max" to "= tool.bore * 0.75",
        "hardware.machine.m.loadout.plate" to "pei", "hardware.machine.m.loadout.tool.t1.filament" to "pla-generic",
        "plan.mechanism.adhesion.prime.enabled" to false, "hardware.filament.pla-generic.cooling.slowdown.enabled" to false,
        "plan.mechanism.motion.visible.speed" to "100 mm/s", "plan.mechanism.motion.first_layer.speed" to "40 mm/s")
    val a = ModelObject("a", listOf("a"), listOf(1), "a.3mf")
    fun line(feature: String, x: Double, y: Double, z: Double) =
        Extrude(feature, longArrayOf(Math.round(x * 1000), Math.round(y * 1000), Math.round(x * 1000) + 20_000, Math.round(y * 1000)), false, Math.round(z * 1000), 440, 200)
    val steps: List<Step> = listOf(ObjectStart("a"), LayerStart(0, 200, 200, 0), line("Outer wall", 100.0, 100.0, 0.2), line("Inner wall", 100.0, 110.0, 0.2),
        LayerStart(1, 400, 200, 1), line("Outer wall", 100.0, 100.0, 0.4), line("Inner wall", 100.0, 110.0, 0.4), line("Sparse infill", 100.0, 120.0, 0.4), ObjectEnd("a"))

    /** The feed (mm/s) in force at each printing move and each travel, in order. */
    fun feeds(factor: String): Pair<List<Double>, List<Double>> {
        val all = ConfigFiles.bundled() + (machine + ("plan.mechanism.motion.speed.factor" to factor)).map { (k, v) -> ConfigFiles.Entry(k.split('.'), v, "test") }
        val setup = ConfigSetup.setup(Config(ConfigStack(all), listOf(a))).setup
        val g = StringWriter().also { KlipperWriter(SliceSettings.of(setup), setup.foreignTemplateVars, "test", mapOf("a" to SliceSettings.of(setup, Context(0)))).write(PrintProgram(steps, emptyList()), it) }.toString()
        val printing = ArrayList<Double>(); val travel = ArrayList<Double>()
        var f = 0.0; var x = Double.NaN; var y = Double.NaN
        for (raw in g.lines()) {
            if (!raw.startsWith("G1 ") && !raw.startsWith("G0 ")) continue
            val w = raw.substringBefore(';').split(' ').associate { it.take(1) to it.drop(1) }
            w["F"]?.toDoubleOrNull()?.let { f = it / 60 }
            val nx = w["X"]?.toDouble() ?: x; val ny = w["Y"]?.toDouble() ?: y
            if (nx != x || ny != y) { if ((w["E"]?.toDouble() ?: 0.0) > 0) printing += f else travel += f }
            x = nx; y = ny
        }
        return printing to travel
    }

    "every printing speed scaled, travels the same; control: at 100 % the settings' own speeds" {
        val (full, fullTravel) = feeds("100%")
        val (half, halfTravel) = feeds("50%")
        println("printing feeds at 100 %: $full; at 50 %: $half; travels $fullTravel / $halfTravel")
        full.size shouldBe 5
        half.size shouldBe full.size
        for (i in full.indices) (Math.abs(half[i] - full[i] / 2) < 0.02) shouldBe true
        halfTravel shouldBe fullTravel
        // the control: the first layer at its 40 mm/s, the visible lines above at 100
        full[0] shouldBe 40.0
        full[2] shouldBe 100.0
        full[3] shouldBe 120.0
    }

    // the factor set for one object, and for one part of it that prints with another tool, through the config as a slice
    // resolves it (plan.object.<key>.…, plan.object.<key>.part.<part>.…) and the writer's settings per object and per part's
    // tool (as Job builds them): object a's lines at its factor, its part p2's at its own, object b's at the plate's.
    // Control: with nothing set per object or part, every line at the plate's
    "per object and per part: each object's lines at its own factor, a part on another tool at its own; control: none set, all the plate's" {
        val twoTools = machine + listOf("hardware.tool.t2.machine" to "m", "hardware.tool.t2.index" to 2L, "hardware.tool.t2.bore" to "0.4 mm",
            "hardware.tool.t2.layer.height.min" to "= tool.bore / 5", "hardware.tool.t2.layer.height.max" to "= tool.bore * 0.75",
            "hardware.machine.m.loadout.tool.t2.filament" to "pla-generic")
        val objA = ModelObject("a", listOf("p1", "p2"), listOf(1, 2), "a.3mf")
        val objB = ModelObject("b", listOf("q"), listOf(1), "b.3mf")
        /** Per object, per tool: the feeds (mm/s) of its printing moves above the first layer. */
        fun feedsBy(vararg own: Pair<String, Any>): Map<Pair<String, Int>, List<Double>> {
            val all = ConfigFiles.bundled() + (twoTools + own).map { (k, v) -> ConfigFiles.Entry(k.split('.'), v, "test") }
            val setup = ConfigSetup.setup(Config(ConfigStack(all), listOf(objA, objB))).setup
            val toolOfP2 = setup.spool(setup.objects[0].parts[1].spool).tool
            val toolOfA = setup.spool(setup.objects[0].spool).tool
            (toolOfP2 != toolOfA) shouldBe true
            val perObject = mapOf("a" to SliceSettings.of(setup, Context(0)), "b" to SliceSettings.of(setup, Context(1)))
            val perTool = plymeld.core.IncrementalSlicer.toolSettings(setup, 0, "a") + plymeld.core.IncrementalSlicer.toolSettings(setup, 1, "b")
            fun l(feature: String, y: Double, tool: Int) = Extrude(feature, longArrayOf(100_000, Math.round(y * 1000), 120_000, Math.round(y * 1000)), false, 400, 440, 200, tool = tool)
            val prog: List<Step> = listOf(LayerStart(0, 200, 200, 0), LayerStart(1, 400, 200, 1),
                ObjectStart("a"), l("Outer wall", 100.0, toolOfA), l("Sparse infill", 105.0, toolOfA),
                plymeld.program.ToolChange(toolOfP2, "spool"), l("Outer wall", 110.0, toolOfP2), l("Sparse infill", 115.0, toolOfP2), ObjectEnd("a"),
                plymeld.program.ToolChange(toolOfA, "spool"), ObjectStart("b"), l("Outer wall", 120.0, toolOfA), l("Sparse infill", 125.0, toolOfA), ObjectEnd("b"))
            val g = StringWriter().also { KlipperWriter(SliceSettings.of(setup), setup.foreignTemplateVars, "test", perObject, perObjectTool = perTool).write(PrintProgram(prog, emptyList()), it) }.toString()
            // each printing move's feed, keyed by the line it lays (its y: which object and tool, by the program above)
            val byY = mapOf(100.0 to ("a" to toolOfA), 105.0 to ("a" to toolOfA), 110.0 to ("a" to toolOfP2), 115.0 to ("a" to toolOfP2), 120.0 to ("b" to toolOfA), 125.0 to ("b" to toolOfA))
            val out = LinkedHashMap<Pair<String, Int>, MutableList<Double>>()
            var f = 0.0; var x = Double.NaN; var y = Double.NaN
            for (raw in g.lines()) {
                if (!raw.startsWith("G1 ") && !raw.startsWith("G0 ")) continue
                val w = raw.substringBefore(';').split(' ').associate { it.take(1) to it.drop(1) }
                w["F"]?.toDoubleOrNull()?.let { f = it / 60 }
                val nx = w["X"]?.toDouble() ?: x; val ny = w["Y"]?.toDouble() ?: y
                if ((nx != x || ny != y) && (w["E"]?.toDouble() ?: 0.0) > 0) byY[ny]?.let { out.getOrPut(it) { ArrayList() } += f }
                x = nx; y = ny
            }
            return out
        }
        val plain = feedsBy("plan.mechanism.motion.speed.factor" to "100%")
        val set = feedsBy("plan.mechanism.motion.speed.factor" to "100%", "plan.object.a.mechanism.motion.speed.factor" to "50%",
            "plan.object.a.part.p2.mechanism.motion.speed.factor" to "25%")
        println("feeds by (object, tool), none set: $plain; set: $set")
        plain.size shouldBe 3
        set.keys shouldBe plain.keys
        val keys = plain.keys.toList()   // (a, its tool), (a, p2's tool), (b, its tool), in program order
        fun ratios(k: Pair<String, Int>) = set[k]!!.indices.map { set[k]!![it] / plain[k]!![it] }
        // the control: none set, each line at its settings' own speed, the outer wall at the visible 100 mm/s
        plain[keys[0]]!![0] shouldBe 100.0
        ratios(keys[0]).all { Math.abs(it - 0.5) < 0.01 } shouldBe true     // object a: its 50 %
        ratios(keys[1]).all { Math.abs(it - 0.25) < 0.01 } shouldBe true    // its part p2, on the other tool: its own 25 %
        ratios(keys[2]).all { Math.abs(it - 1.0) < 0.01 } shouldBe true     // object b: the plate's 100 %
    }
})
