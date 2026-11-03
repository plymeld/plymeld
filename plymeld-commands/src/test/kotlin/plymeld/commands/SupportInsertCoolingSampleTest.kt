package plymeld.commands

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.shouldBe
import plymeld.commands.TechniqueSamples.SOLID
import plymeld.commands.TechniqueSamples.box
import plymeld.commands.TechniqueSamples.slice
import plymeld.commands.TechniqueSamples.stl
import plymeld.check.TechniqueRender
import plymeld.check.TechniqueRender.Coloring
import plymeld.check.TechniqueRender.Mark
import plymeld.check.TechniqueRender.Panel
import plymeld.check.TechniqueRender.View
import plymeld.check.TechniqueRender.Window
import plymeld.check.reviewRender
import java.nio.file.Files
import java.util.Locale

/**
 * Sample toolpaths of support, planned inserts, and bond-first cooling, drawn into docs/renders/technique_*.png, each cut
 * through the model's middle. Each test first asserts from the g-code what its picture shows:
 *  - support under a T (SupportTechniqueTest's: a 10 x 10 x 10 mm post under a 30 x 10 x 4 mm bar): locked off, no support
 *    line; locked on, tree support lines under the bar's two undersides;
 *  - a planned insert (PlannedInsertCliTest's: a 30 x 30 x 8 mm block with a sealed 10 x 10 mm pocket from z 2 to 5,
 *    `plan.insert.ball` naming it with its top at z 5): a pause, the layer after it laying the pocket's roof as bottom
 *    surface, no bridge line in the file; without the insert, no pause and the roof bridged;
 *  - bond-first cooling on a thin pillar (BondCoolingSliceTest's 3 x 3 mm section, 10 mm high): `plan.bond_cooling = "on"`
 *    prints the layers above the second at most 0.6 times the part fan of `"off"` (the bundled value is half) and 10 C
 *    hotter (the bundled filament print temperature + 10 C).
 */
class SupportInsertCoolingSampleTest : StringSpec({
    val d = TechniqueSamples.dir()
    fun mid(p: TechniqueRender.Toolpath, x: Boolean): Double { val ms = p.moves.filter { it.layer >= 1 }
        return if (x) (ms.minOf { minOf(it.x0, it.x1) } + ms.maxOf { maxOf(it.x0, it.x1) }) / 2 else (ms.minOf { minOf(it.y0, it.y1) } + ms.maxOf { maxOf(it.y0, it.y1) }) / 2 }
    fun slab(p: TechniqueRender.Toolpath) = mid(p, false).let { it - 0.25..it + 0.25 }
    val all: (TechniqueRender.Move) -> Boolean = { it.layer >= 0 }
    val body: (TechniqueRender.Move) -> Boolean = { it.layer >= 0 && it.feature != "Prime line" && it.feature != "Brim" }
    fun f(v: Double) = "%.0f".format(Locale.ROOT, v)

    "support: none against tree support under a T's bar" {
        Files.writeString(d.resolve("tee.stl"), stl(box(-5.0, -5.0, 0.0, 5.0, 5.0, 10.0) + box(-15.0, -5.0, 10.0, 15.0, 5.0, 14.0)))
        fun sup(on: Boolean) = "[plan.support]\nenabled = \"$on\"\n"
        val off = slice(d, listOf("tee.stl"), SOLID + sup(false), "tee_off.gcode")
        val tree = slice(d, listOf("tee.stl"), SOLID + sup(true), "tee_tree.gcode")
        fun support(p: TechniqueRender.Toolpath) = p.moves.filter { it.feature.startsWith("Support") }.sumOf { it.length }
        /** Support under each half of the bar (left of the post, right of it). */
        fun under(p: TechniqueRender.Toolpath): Pair<Double, Double> { val c = mid(p, true)
            val s = p.moves.filter { it.feature.startsWith("Support") && it.z0 < 10.0 }
            return s.filter { (it.x0 + it.x1) / 2 < c - 5.5 }.sumOf { it.length } to s.filter { (it.x0 + it.x1) / 2 > c + 5.5 }.sumOf { it.length } }
        support(off) shouldBe 0.0
        val (l, r) = under(tree); l shouldBeGreaterThan 10.0; r shouldBeGreaterThan 10.0
        TechniqueRender.render("Support: a T, a 30 x 10 x 4 mm bar on a 10 mm post, cut through its middle", View.SIDE, listOf(
            Panel(listOf("no support", "plan.support.enabled = \"false\"; interior solid", "the undersides as their surface technique lays them (wave overhang)"), off, all, slab = slab(off)),
            Panel(listOf("tree support", "plan.support.enabled = \"true\"", "support lines ${f(support(tree))} mm"), tree, all, slab = slab(tree)),
        ), reviewRender("technique_support.png"))
    }

    "a planned insert: the pocket's roof laid onto the insert after a pause, against the roof bridged" {
        Files.writeString(d.resolve("pocket.stl"), stl(box(-15.0, -15.0, 0.0, 15.0, 15.0, 8.0) + box(-5.0, -5.0, 2.0, 5.0, 5.0, 5.0, inward = true)))
        val plain = slice(d, listOf("pocket.stl"), SOLID, "pocket_plain.gcode")
        val ball = slice(d, listOf("pocket.stl"), SOLID + "[plan.insert.ball]\nmin = \"-5 -5 2 mm\"\nmax = \"5 5 5 mm\"\ntop = \"5 mm\"\n", "pocket_ball.gcode")
        plain.pausedBefore.size shouldBe 0
        plain.length("Bridge") shouldBeGreaterThan 20.0
        ball.pausedBefore.size shouldBe 1
        ball.length("Bridge") shouldBe 0.0
        val after = ball.moves[ball.pausedBefore.single()]
        withClue("the first layer after the pause starts at the insert's top") { (after.layerZ - after.height) shouldBe (5.0 plusOrMinus 0.01) }
        val cx = mid(ball, true); val cy = mid(ball, false)
        val roof = ball.layer(after.layer).filter { it.feature == "Bottom surface" && Math.abs((it.x0 + it.x1) / 2 - cx) < 4.5 && Math.abs((it.y0 + it.y1) / 2 - cy) < 4.5 }
        roof.sumOf { it.length } shouldBeGreaterThan 20.0
        TechniqueRender.render("Planned insert: a 30 x 30 x 8 mm block with a sealed pocket from z 2 to 5, cut through its middle", View.SIDE, listOf(
            Panel(listOf("no insert (default)", "no plan.insert; interior solid", "the pocket's roof bridged: ${f(plain.length("Bridge"))} mm of bridge lines"), plain, all, slab = slab(plain)),
            Panel(listOf("planned insert", "plan.insert.ball: the pocket, top = \"5 mm\"", "a pause before the roof's layer (ringed), the roof laid onto the insert as bottom surface"), ball, all,
                marks = listOf(Mark(cx - 8.0, cy, after.layerZ - after.height / 2, "pause before this layer")), slab = slab(ball)),
        ), reviewRender("technique_planned_insert.png"), zScale = 2.5, window = Window(cx - 9.0..cx + 9.0, 0.6..6.8))
    }

    "bond-first cooling: a thin pillar's part fan and nozzle temperature, against the filament's own" {
        Files.writeString(d.resolve("pillar.stl"), stl(box(-1.5, -1.5, 0.0, 1.5, 1.5, 10.0)))
        val off = slice(d, listOf("pillar.stl"), "", "bond_off.gcode")
        val on = slice(d, listOf("pillar.stl"), "[plan]\nbond_cooling = \"on\"\n", "bond_on.gcode")
        fun median(xs: List<Double>) = xs.sorted()[xs.size / 2]
        fun fan(p: TechniqueRender.Toolpath) = median(p.moves.filter { it.layer >= 2 }.map { it.fan })
        fun temp(p: TechniqueRender.Toolpath) = median(p.moves.filter { it.layer >= 2 }.map { it.temp })
        fan(on) shouldBeLessThan 0.6 * fan(off)
        temp(on) shouldBe (temp(off) + 10.0 plusOrMinus 0.01)
        // the toolpath itself is the same: bond-first cooling changes the conditions, not the lines
        on.moves.size shouldBe off.moves.size
        TechniqueRender.render("Bond-first cooling: a 3 x 3 x 10 mm pillar, by part fan, cut through its middle", View.SIDE, listOf(
            Panel(listOf("the filament's cooling", "plan.bond_cooling = \"off\"", "fan %.0f %%, nozzle %s C from the third layer".format(Locale.ROOT, 100 * fan(off), f(temp(off)))),
                off, body, Coloring.FAN, slab = slab(off)),
            Panel(listOf("bond-first cooling", "plan.bond_cooling = \"on\" (plan.technique.bond_cooling.* bundled)",
                "fan %.0f %%, nozzle %s C from the third layer".format(Locale.ROOT, 100 * fan(on), f(temp(on)))), on, body, Coloring.FAN, slab = slab(on)),
        ), reviewRender("technique_bond_cooling.png"))
    }
})
