package plymeld.commands

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.shouldBe
import plymeld.commands.TechniqueSamples.SOLID
import plymeld.commands.TechniqueSamples.box
import plymeld.commands.TechniqueSamples.slice
import plymeld.commands.TechniqueSamples.strip
import plymeld.commands.TechniqueSamples.threeMf
import plymeld.check.TechniqueRender
import plymeld.check.TechniqueRender.Coloring
import plymeld.check.TechniqueRender.Panel
import plymeld.check.TechniqueRender.View
import plymeld.check.TechniqueRender.Window
import plymeld.check.reviewRender
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Sample toolpaths of the lifted bridge (`band.1.technique = "bridge"`), drawn into
 * docs/renders/technique_lifted_bridge*.png. Each test first asserts from the g-code what its picture shows:
 *  - LiftedBridgeTest's ramp (pillars 6 and 12 mm high, 40 mm apart, a 5 mm wide 1.2 mm strip rising from one to the
 *    other), against flat layers of one height: lifted, the strip's first move over the gap comes after every move of the
 *    tall pillar; flat, before the tall pillar is done;
 *  - the strip's first layer on an arch (LiftedBridgeTest's waved arch: pillars 8 x 10 x 6 mm, 20 mm apart, the strip
 *    arching 2 mm), with `plan.technique.bridge.first_layer` = `lines` (bridge lines, no wave bead) and `wave_overhang`
 *    (wave beads, none running down), and `wave.sides` = `one_then_other` (the side of the middle changes once) and
 *    `alternate` (it changes at more than a quarter of the beads), and `one_support` (grown from one pillar: the beads past
 *    the middle step down towards the far pillar, where the others' step up from it).
 */
class BridgeSampleTest : StringSpec({
    val d = TechniqueSamples.dir()
    fun cx(p: TechniqueRender.Toolpath): Double { val ms = p.moves.filter { it.layer >= 1 }; return (ms.minOf { minOf(it.x0, it.x1) } + ms.maxOf { maxOf(it.x0, it.x1) }) / 2 }
    fun cy(p: TechniqueRender.Toolpath): Double { val ms = p.moves.filter { it.layer >= 1 }; return (ms.minOf { minOf(it.y0, it.y1) } + ms.maxOf { maxOf(it.y0, it.y1) }) / 2 }
    val all: (TechniqueRender.Move) -> Boolean = { it.layer >= 0 }

    "a lifted bridge against flat layers: a strip rising between pillars 6 and 12 mm high, by print order" {
        threeMf(d.resolve("ramp.3mf"), "ramp", listOf(
            "supports" to box(-28.0, -5.0, 0.0, -20.0, 5.0, 6.0) + box(20.0, -5.0, 0.0, 28.0, 5.0, 12.0),
            "strip" to strip(listOf(-28.0 to 6.0, -20.0 to 6.0, 20.0 to 12.0, 28.0 to 12.0))))
        // the head's outline as LiftedBridgeTest has it for this ramp: the nozzle 70 mm from the head's -X side, 20 mm from its
        // +X side, so the head clears the tall pillar while the nozzle is low at the strip's other end
        val head = "[hardware.tool.t1]\nhead.nozzle.x = \"70 mm\"\nhead.nozzle.y = \"40 mm\"\nhead.size.x = \"90 mm\"\nhead.size.y = \"75 mm\"\n"
        val flat = slice(d, listOf("ramp.3mf"), SOLID + head, "ramp_flat.gcode")
        val lifted = slice(d, listOf("ramp.3mf"), SOLID + head + "[plan.object.ramp]\nband.1.technique = \"bridge\"\n", "ramp_lifted.gcode")
        /** The first move over the gap between the pillars, and the last move on the tall pillar (x 20..28 from the middle). */
        fun order(p: TechniqueRender.Toolpath): Pair<Int, Int> {
            val c = cx(p)
            val gap = p.moves.first { it.layer >= 0 && abs((it.x0 + it.x1) / 2 - c) < 15 && it.z0 > 5.5 }.index
            val tall = p.moves.last { it.layer >= 0 && (it.x0 + it.x1) / 2 - c in 20.5..27.5 && it.z0 < 11.9 }.index
            return gap to tall
        }
        val (fg, ft) = order(flat); val (lg, lt) = order(lifted)
        withClue("flat: over the gap at move $fg, the tall pillar's last at $ft") { fg shouldBeLessThan ft }
        withClue("lifted: over the gap at move $lg, the tall pillar's last at $lt") { lg shouldBeGreaterThan lt }
        TechniqueRender.render("Lifted bridge: a strip rising from a 6 mm pillar to a 12 mm one, cut through its middle", View.SIDE, listOf(
            Panel(listOf("flat layers (default)", "one height, 0.2 mm; interior solid", "the strip over the gap is laid before the tall pillar is finished"), flat, all, Coloring.ORDER,
                slab = cy(flat) - 0.25..cy(flat) + 0.25),
            Panel(listOf("lifted bridge", "plan.object.ramp.band.1.technique = \"bridge\"; interior solid", "both pillars first, then the strip along its rising underside"), lifted, all, Coloring.ORDER,
                slab = cy(lifted) - 0.25..cy(lifted) + 0.25),
        ), reviewRender("technique_lifted_bridge.png"))
    }

    "a lifted bridge's first layer over an arch: bridge lines, wave beads one side then the other, wave beads side by side" {
        val arc = (0..40).map { i -> val x = -10.0 + i * 0.5; val r = 26.0; x to 6.0 + sqrt(r * r - x * x) - (r - 2.0) }
        threeMf(d.resolve("arch.3mf"), "arch", listOf(
            "supports" to box(-18.0, -5.0, 0.0, -10.0, 5.0, 6.0) + box(10.0, -5.0, 0.0, 18.0, 5.0, 6.0),
            "strip" to strip(listOf(-18.0 to 6.0) + arc + listOf(18.0 to 6.0))))
        fun lock(first: String, sides: String) = "[plan.object.arch]\nband.1.technique = \"bridge\"\n[plan.technique.bridge]\nfirst_layer = \"$first\"\nwave.sides = \"$sides\"\n"
        val lines = slice(d, listOf("arch.3mf"), lock("lines", "one_then_other"), "arch_lines.gcode")
        val wave = slice(d, listOf("arch.3mf"), lock("wave_overhang", "one_then_other"), "arch_wave.gcode")
        val alt = slice(d, listOf("arch.3mf"), lock("wave_overhang", "alternate"), "arch_alt.gcode")
        val one = slice(d, listOf("arch.3mf"), lock("wave_overhang", "one_support"), "arch_one.gcode")
        /** The strip's first layer: the first with a move over the gap above the pillars' tops. */
        fun first(p: TechniqueRender.Toolpath): Int { val c = cx(p); return p.moves.first { it.layer >= 0 && abs((it.x0 + it.x1) / 2 - c) < 9 && it.z0 > 6.1 }.layer }
        /** The wave beads of layer [k], each a run of contiguous moves. */
        fun beads(p: TechniqueRender.Toolpath, k: Int): List<List<TechniqueRender.Move>> {
            val out = ArrayList<MutableList<TechniqueRender.Move>>(); var prev: TechniqueRender.Move? = null
            for (m in p.layer(k)) {
                if (m.feature != "Wave overhang") { prev = null; continue }
                if (prev == null || prev.index != m.index - 1 || Math.hypot(prev.x1 - m.x0, prev.y1 - m.y0) > 1e-6) out += arrayListOf(m) else out.last() += m
                prev = m
            }
            return out
        }
        fun sideChanges(p: TechniqueRender.Toolpath, k: Int): Int { val c = cx(p); val s = beads(p, k).map { b -> (b[b.size / 2].x0 + b[b.size / 2].x1) / 2 < c }; return s.zipWithNext().count { (a, b) -> a != b } }
        val kl = first(lines); val kw = first(wave); val ka = first(alt); val ko = first(one)
        /** The heights of the beads across the strip (x the same at both ends) past the middle, in the order laid: steps down, steps up. */
        fun farSteps(p: TechniqueRender.Toolpath, k: Int): Pair<Int, Int> {
            val c = cx(p)
            val z = beads(p, k).filter { b -> abs(b.last().x1 - b.first().x0) < 0.05 && b.first().x0 > c + 1.0 }.map { it.first().z0 }
            return z.zipWithNext().count { (a, b) -> b < a - 1e-3 } to z.zipWithNext().count { (a, b) -> b > a + 1e-3 }
        }
        lines.length("Bridge") { it.layer == kl } shouldBeGreaterThan 20.0
        lines.length("Wave overhang") shouldBe 0.0
        for ((p, k) in listOf(wave to kw, alt to ka)) {
            val bs = beads(p, k)
            bs.size shouldBeGreaterThan 10
            withClue("no bead runs down") { bs.count { b -> b.last().z1 < b.first().z0 - 0.01 } shouldBe 0 }
        }
        withClue("one side, then the other") { sideChanges(wave, kw) shouldBe 1 }
        withClue("the sides front by front") { sideChanges(alt, ka) shouldBeGreaterThan beads(alt, ka).size / 4 }
        withClue("from one support: past the middle the beads step down") { farSteps(one, ko).let { (d, u) -> (d > 5 && u == 0) shouldBe true } }
        withClue("control: one side then the other steps up from the far pillar") { farSteps(wave, kw).let { (d, u) -> (u > 5 && d == 0) shouldBe true } }
        val overAir: (TechniqueRender.Move) -> Boolean = { it.feature == "Bridge" || it.feature == "Wave overhang" }
        fun len(p: TechniqueRender.Toolpath, f: String, k: Int) = "%.0f".format(Locale.ROOT, p.length(f) { it.layer == k })
        TechniqueRender.render("Lifted bridge, its first layer over a 2 mm arch between two 6 mm pillars, by print order", View.TOP, listOf(
            Panel(listOf("bridge lines (default)", "plan.technique.bridge.first_layer = \"lines\"", "bridge lines ${len(lines, "Bridge", kl)} mm"),
                lines, { it.layer == kl }, Coloring.ORDER, highlight = overAir),
            Panel(listOf("wave overhang, one side then the other", "first_layer = \"wave_overhang\", wave.sides = \"one_then_other\"",
                "${beads(wave, kw).size} wave beads, each climbing from its pillar"), wave, { it.layer == kw }, Coloring.ORDER, highlight = overAir),
            Panel(listOf("wave overhang, the sides in turn", "first_layer = \"wave_overhang\", wave.sides = \"alternate\"",
                "${beads(alt, ka).size} wave beads, the side changing ${sideChanges(alt, ka)} times"), alt, { it.layer == ka }, Coloring.ORDER, highlight = overAir),
            Panel(listOf("wave overhang, from one support", "first_layer = \"wave_overhang\", wave.sides = \"one_support\"",
                "${beads(one, ko).size} wave beads, up to the peak and down to the far pillar"), one, { it.layer == ko }, Coloring.ORDER, highlight = overAir),
        ), reviewRender("technique_lifted_bridge_first_layer.png"), window = Window(cx(lines) - 11.0..cx(lines) + 11.0, cy(lines) - 3.6..cy(lines) + 3.6), lineShare = 0.4)
    }
})
