package plymeld.check

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import plymeld.config.Config
import plymeld.config.ConfigFiles
import plymeld.config.ConfigSetup
import plymeld.config.ConfigStack
import plymeld.config.ModelObject
import plymeld.core.Feature
import plymeld.core.Scarf
import plymeld.core.ToolPath
import plymeld.gcode.KlipperWriter
import plymeld.model.SliceSettings
import plymeld.program.LayerStart
import plymeld.program.PrintProgram
import java.io.StringWriter

/**
 * A scarf seam's own values (plan.technique.scarf_seam.speed, .step, .flow), read from the g-code of one scarfed loop (a
 * 40 mm square, a 10 mm ramp, the visible lines at 100 mm/s): the ramp and its closing pass at the scarf's speed, the rest
 * of the loop at the wall's; the ramp in steps no longer than .step (twice the steps at half the step); the ramp's and the
 * closing pass's plastic times .flow, the rest's not. Controls: a scarf speed over the wall's leaves the ramp at the wall's;
 * at 100 % the plastic is the plain bead's.
 */
class ScarfSettingsTest : StringSpec({
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
        "plan.mechanism.motion.visible.speed" to "100 mm/s")
    val box = ModelObject("box", listOf("box"), listOf(1), "box.3mf")
    val loopXy = longArrayOf(100_000, 100_000, 140_000, 100_000, 140_000, 140_000, 100_000, 140_000)

    /** Per run (ramp, rest, closing pass): its moves' feeds (mm/s) and filament per mm, with [extra] settings. */
    class Run(val feeds: List<Double>, val ePerMm: List<Double>, val moves: Int, val e: Double, val len: Double)
    fun runs(vararg extra: Pair<String, Any>): List<Run> {
        val all = ConfigFiles.bundled() + (machine + extra).map { (k, v) -> ConfigFiles.Entry(k.split('.'), v, "test") }
        val setup = ConfigSetup.setup(Config(ConfigStack(all), listOf(box))).setup
        val s = SliceSettings.of(setup)
        val p = ToolPath(Feature.OUTER_WALL, loopXy, true, 440, 200, scarfUm = 10_000, scarfStart = 0.25, scarfStepUm = s.scarfStepUm)
        val ex = Scarf.extrudes(p, 400, 0)
        ex.size shouldBe 3
        val g = StringWriter().also { KlipperWriter(s, setup.foreignTemplateVars, "test").write(PrintProgram(listOf(LayerStart(0, 200, 200, 0), LayerStart(1, 400, 200, 1)) + ex, emptyList()), it) }.toString()
        // the runs follow one another with no travel: split them where each one ends (its last vertex, as laid)
        val ends = ex.map { e -> e.xy[e.xy.size - 2] / 1000.0 to e.xy[e.xy.size - 1] / 1000.0 }
        val out = ArrayList<Run>(); var feeds = ArrayList<Double>(); var es = ArrayList<Double>()
        var f = 0.0; var x = Double.NaN; var y = Double.NaN; var r = 0; var eSum = 0.0; var lSum = 0.0
        for (raw in g.lines()) {
            val c = raw.substringBefore(';').trim()
            if (!c.startsWith("G1 ")) continue
            val w = c.split(' ').associate { it.take(1) to it.drop(1) }
            w["F"]?.toDoubleOrNull()?.let { f = it / 60 }
            val nx = w["X"]?.toDouble() ?: x; val ny = w["Y"]?.toDouble() ?: y
            val e = w["E"]?.toDoubleOrNull() ?: 0.0
            val len = Math.hypot(nx - x, ny - y)
            if (e > 0 && len > 0 && r < ex.size) {
                feeds += f; es += e / len; eSum += e; lSum += len
                if (Math.abs(nx - ends[r].first) < 1e-3 && Math.abs(ny - ends[r].second) < 1e-3) {
                    out += Run(feeds, es, feeds.size, eSum, lSum); feeds = ArrayList(); es = ArrayList(); eSum = 0.0; lSum = 0.0; r++
                }
            }
            x = nx; y = ny
        }
        return out
    }

    "the ramp and its closing pass at the scarf's speed, the rest at the wall's; control: a scarf speed over the wall's changes nothing" {
        val half = runs()                                                              // bundled: half the visible speed
        val over = runs("plan.technique.scarf_seam.speed" to "200 mm/s")
        println("feeds, bundled: ${half.map { it.feeds.distinct() }}; at 200 mm/s: ${over.map { it.feeds.distinct() }}")
        half[0].feeds.all { it == 50.0 } shouldBe true
        half[2].feeds.all { it == 50.0 } shouldBe true
        half[1].feeds.all { it == 100.0 } shouldBe true
        over.all { r -> r.feeds.all { it == 100.0 } } shouldBe true
    }
    "the ramp in steps no longer than .step: twice the steps at half the step" {
        val one = runs()[0].moves
        val half = runs("plan.technique.scarf_seam.step" to "0.5 mm")[0].moves
        println("ramp moves at 1 mm: $one; at 0.5 mm: $half")
        one shouldBe 10
        half shouldBe 20
    }
    "the ramp's and the closing pass's plastic times .flow, the rest's not; control: at 100 % the plain bead's" {
        val plain = runs()
        val less = runs("plan.technique.scarf_seam.flow" to "80%")
        fun total(r: Run) = r.ePerMm.sum()
        println("ramp e/mm sums: ${total(plain[0])} -> ${total(less[0])}; rest ${plain[1].ePerMm.first()} -> ${less[1].ePerMm.first()}")
        (Math.abs(total(less[0]) / total(plain[0]) - 0.8) < 0.01) shouldBe true
        (Math.abs(total(less[2]) / total(plain[2]) - 0.8) < 0.01) shouldBe true
        (Math.abs(less[1].ePerMm.first() - plain[1].ePerMm.first()) < 1e-9) shouldBe true
        // the control, absolute: at 100 % the ramp and its closing pass together lay one bead over the ramp's length (the
        // rest's plastic per mm), within the bead model's rounding
        val oneBead = plain[1].e / plain[1].len * plain[0].len
        println("ramp + closing pass %.4f mm of filament against one bead's %.4f".format(java.util.Locale.ROOT, plain[0].e + plain[2].e, oneBead))
        (Math.abs((plain[0].e + plain[2].e) / oneBead - 1) < 0.02) shouldBe true
    }
})
