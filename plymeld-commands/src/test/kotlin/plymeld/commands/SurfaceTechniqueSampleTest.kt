package plymeld.commands

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.shouldBe
import plymeld.commands.TechniqueSamples.SOLID
import plymeld.commands.TechniqueSamples.box
import plymeld.commands.TechniqueSamples.cylinder
import plymeld.commands.TechniqueSamples.heightField
import plymeld.commands.TechniqueSamples.prism
import plymeld.commands.TechniqueSamples.slice
import plymeld.commands.TechniqueSamples.stl
import plymeld.check.TechniqueRender
import plymeld.check.TechniqueRender.Coloring
import plymeld.check.TechniqueRender.Panel
import plymeld.check.TechniqueRender.View
import plymeld.check.TechniqueRender.Window
import plymeld.check.reviewRender
import java.nio.file.Files
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.tan

/**
 * Sample toolpaths of the surface techniques, each beside the surface as laid on the same model, drawn into
 * docs/renders/technique_*.png. Each test first asserts from the g-code that the surface technique laid what its
 * picture shows, and that the as-laid control did not:
 *  - ironing on a 20 x 20 x 5 mm box's top: ironing lines on the top layer only, at least half the top's area over the
 *    bundled spacing (bore / 4 = 0.1 mm) long; none as laid;
 *  - fuzzy skin on the same box: the outer wall of a middle layer leaves the box's sides (as the as-laid wall draws them) by
 *    more than 0.15 mm (the bundled thickness is 0.3 mm, bore x 0.75); as laid, within 0.02 mm;
 *  - Z anti-aliasing on a wedge rising 10 deg along X (ZAntiAliasTest's): lines printed more than 0.03 mm under their layer's
 *    Z; as laid, none;
 *  - wave overhang under a round shelf hanging up to 8 mm off one side of a round post: on the first layer over air, wave
 *    beads and no bridge line; the first bead nearer the post's axis than the last; at least half the beads 3 mm long or
 *    more keep within 0.5 mm of one distance from the axis (arcs that follow the post's round edge, which a straight row
 *    across the crescent cannot); as laid, bridge lines and no wave bead.
 * The sizes of the models are the tests' own (plymeld's choice).
 */
class SurfaceTechniqueSampleTest : StringSpec({
    val d = TechniqueSamples.dir()
    Files.writeString(d.resolve("box.stl"), stl(box(-10.0, -10.0, 0.0, 10.0, 10.0, 5.0)))
    fun f(v: Double) = "%.0f".format(Locale.ROOT, v)
    fun middle(p: TechniqueRender.Toolpath): Pair<Double, Double> {
        val ms = p.moves.filter { it.layer >= 1 }
        return (ms.minOf { min(it.x0, it.x1) } + ms.maxOf { maxOf(it.x0, it.x1) }) / 2 to (ms.minOf { min(it.y0, it.y1) } + ms.maxOf { maxOf(it.y0, it.y1) }) / 2
    }

    "ironing: the box's top layer passed over again, beside the top as laid" {
        val laid = slice(d, listOf("box.stl"), "[plan.faces]\nflat_top.technique = \"as_laid\"\n", "iron_laid.gcode")
        val iron = slice(d, listOf("box.stl"), "[plan.faces]\nflat_top.technique = \"ironing\"\n", "iron_on.gcode")
        val top = iron.layerZs.size - 1
        val ironing = iron.moves.filter { it.feature == "Ironing" }
        withClue("ironing only on the top layer") { ironing.all { it.layer == top } shouldBe true }
        ironing.sumOf { it.length } shouldBeGreaterThan 0.5 * 20 * 20 / 0.1
        laid.length("Ironing") shouldBe 0.0
        // a 5 mm corner of the top, so the passes read one by one
        val w = iron.moves.filter { it.feature == "Outer wall" }
        val x0 = w.minOf { min(it.x0, it.x1) }; val y1 = w.maxOf { maxOf(it.y0, it.y1) }
        val win = Window(x0 - 0.4..x0 + 5.0, y1 - 5.0..y1 + 0.4)
        TechniqueRender.render("Ironing: a 5 mm corner of a 20 x 20 x 5 mm box's top layer", View.TOP, listOf(
            Panel(listOf("as laid (default)", "plan.faces.flat_top.technique = \"as_laid\"", "the top surface's lines only"), laid, { it.layer == laid.layerZs.size - 1 }),
            Panel(listOf("ironing", "plan.faces.flat_top.technique = \"ironing\" (bundled: passes 0.1 mm apart, 10 % flow)",
                "${f(ironing.sumOf { it.length })} mm of passes over the top (blue, drawn 0.03 mm wide)"), iron, { it.layer == top }, widths = mapOf("Ironing" to 0.03)),
        ), reviewRender("technique_ironing.png"), window = win)
    }

    "fuzzy skin: a middle layer's outer wall jittered in and out, beside the wall as laid" {
        val laid = slice(d, listOf("box.stl"), "[plan.faces]\nside.technique = \"as_laid\"\n", "fuzzy_laid.gcode")
        val fuzzy = slice(d, listOf("box.stl"), "[plan.faces]\nside.technique = \"fuzzy_skin\"\n", "fuzzy_on.gcode")
        val kl = laid.nearestLayer(2.5); val kf = fuzzy.nearestLayer(2.5)
        // the box's sides as the as-laid outer wall draws them (the reference: the other file, not the fuzzed one)
        val wall = laid.layer(kl).filter { it.feature == "Outer wall" }
        val x0 = wall.minOf { min(it.x0, it.x1) }; val x1 = wall.maxOf { maxOf(it.x0, it.x1) }
        val y0 = wall.minOf { min(it.y0, it.y1) }; val y1 = wall.maxOf { maxOf(it.y0, it.y1) }
        fun off(ms: List<TechniqueRender.Move>) = ms.maxOf { m -> listOf(m.x1 to m.y1, m.x0 to m.y0).maxOf { (x, y) -> minOf(abs(x - x0), abs(x - x1), abs(y - y0), abs(y - y1)) } }
        val fuzzed = fuzzy.layer(kf).filter { it.feature == "Outer wall" }
        off(fuzzed) shouldBeGreaterThan 0.15
        off(wall) shouldBeLessThan 0.02
        TechniqueRender.render("Fuzzy skin: a layer 2.5 mm up a 20 x 20 x 5 mm box", View.TOP, listOf(
            Panel(listOf("as laid (default)", "plan.faces.side.technique = \"as_laid\"", "outer wall %d moves, at most %.2f mm off the sides".format(Locale.ROOT, wall.size, off(wall))), laid, { it.layer == kl }),
            Panel(listOf("fuzzy skin", "plan.faces.side.technique = \"fuzzy_skin\" (plan.technique.fuzzy_skin.* bundled)",
                "outer wall %d moves, up to %.2f mm off the sides".format(Locale.ROOT, fuzzed.size, off(fuzzed))), fuzzy, { it.layer == kf }),
        ), reviewRender("technique_fuzzy_skin.png"))
    }

    "Z anti-aliasing: the lines under a 10 deg sloped top lowered onto it, beside the stair-steps as laid" {
        Files.writeString(d.resolve("wedge.stl"), stl(heightField(0.0, 0.0, 20.0, 10.0, 20, 2) { x, _ -> 1 + x * tan(Math.toRadians(10.0)) }))
        val laid = slice(d, listOf("wedge.stl"), SOLID + "[plan.faces]\nsloped_top.technique = \"as_laid\"\n", "zaa_laid.gcode")
        val zaa = slice(d, listOf("wedge.stl"), SOLID + "[plan.faces]\nsloped_top.technique = \"z_antialiasing\"\n", "zaa_on.gcode")
        fun lowered(p: TechniqueRender.Toolpath) = p.moves.filter { it.layer > 0 && min(it.z0, it.z1) < it.layerZ - 0.03 }
        (lowered(zaa).size > 10) shouldBe true
        lowered(laid).size shouldBe 0
        val (_, cl) = middle(laid); val (_, cz) = middle(zaa)
        val xs = laid.moves.filter { it.layer >= 1 }.minOf { min(it.x0, it.x1) }
        val win = Window(xs + 4.0..xs + 12.0, 1.0..3.3)
        TechniqueRender.render("Z anti-aliasing: part of a wedge whose top rises 10 deg, cut through its middle", View.SIDE, listOf(
            Panel(listOf("as laid (default)", "plan.faces.sloped_top.technique = \"as_laid\"; interior solid", "every line at its layer's height: whole stair-steps"),
                laid, { it.layer >= 0 }, Coloring.LAYER, slab = cl - 0.25..cl + 0.25),
            Panel(listOf("Z anti-aliasing", "plan.faces.sloped_top.technique = \"z_antialiasing\"; interior solid", "${lowered(zaa).size} lines lowered onto the slope with less plastic"),
                zaa, { it.layer >= 0 }, Coloring.LAYER, slab = cz - 0.25..cz + 0.25),
        ), reviewRender("technique_z_antialiasing.png"), zScale = 4.0, window = win)
    }

    "wave overhang: a round shelf's first layer over air grown from the post's edge, beside it bridged as laid" {
        // a post of radius 6 mm, 4 mm high, under a 1.2 mm shelf of radius 10 whose center is 4 mm to +X: a crescent over air,
        // up to 8 mm wide, whose anchor (the post's edge) is round
        Files.writeString(d.resolve("shelf.stl"), stl(cylinder(0.0, 0.0, 6.0, 4.0) +
            prism((0 until 96).map { val a = 2 * Math.PI * it / 96; 4.0 + 10 * cos(a) to 10 * sin(a) }, 4.0, 5.2)))
        val off = "[plan.support]\nenabled = \"false\"\n"
        val laid = slice(d, listOf("shelf.stl"), "$off[plan.faces]\nunder.technique = \"as_laid\"\n", "wave_laid.gcode")
        val wave = slice(d, listOf("shelf.stl"), "$off[plan.faces]\nunder.technique = \"wave_overhang\"\n", "wave_on.gcode")
        val kl = laid.layerZs.indexOfFirst { it > 4.05 }; val kw = wave.layerZs.indexOfFirst { it > 4.05 }
        // the post's axis, from its own outer wall a layer below the shelf (the reference: the post, not the beads)
        fun axis(p: TechniqueRender.Toolpath): Pair<Double, Double> { val w = p.layer(p.nearestLayer(3.0)).filter { it.feature == "Outer wall" }
            return (w.minOf { it.x0 } + w.maxOf { it.x0 }) / 2 to (w.minOf { it.y0 } + w.maxOf { it.y0 }) / 2 }
        val (ax, ay) = axis(wave)
        val beads = ArrayList<MutableList<TechniqueRender.Move>>(); var prev: TechniqueRender.Move? = null
        for (m in wave.layer(kw)) {
            if (m.feature != "Wave overhang") { prev = null; continue }
            if (prev == null || hypot(prev.x1 - m.x0, prev.y1 - m.y0) > 1e-6) beads += arrayListOf(m) else beads.last() += m
            prev = m
        }
        fun r(x: Double, y: Double) = hypot(x - ax, y - ay)
        withClue("wave beads on the first layer over air") { (beads.size >= 10) shouldBe true }
        wave.length("Bridge") { it.layer == kw } shouldBe 0.0
        withClue("grown outward: the first bead nearer the axis than the last") { (r(beads.first()[0].x0, beads.first()[0].y0) < r(beads.last()[0].x0, beads.last()[0].y0) - 4) shouldBe true }
        val long = beads.filter { b -> b.sumOf { it.length } >= 3.0 }
        val arcs = long.count { b -> val rs = b.flatMap { listOf(r(it.x0, it.y0), r(it.x1, it.y1)) }; rs.max() - rs.min() < 0.5 }
        withClue("beads following the round edge: $arcs of ${long.size}") { (2 * arcs >= long.size && long.isNotEmpty()) shouldBe true }
        laid.length("Wave overhang") shouldBe 0.0
        laid.length("Bridge") { it.layer == kl } shouldBeGreaterThan 50.0
        fun speeds(p: TechniqueRender.Toolpath, feature: String, k: Int) = p.layer(k).filter { it.feature == feature }.map { it.speed }.distinct().sorted()
            .joinToString("/") { "%.0f".format(Locale.ROOT, it) }
        val overAir: (TechniqueRender.Move) -> Boolean = { it.feature == "Bridge" || it.feature == "Wave overhang" }
        val win = Window(ax + 2.0..ax + 15.0, ay - 10.6..ay + 10.6)
        TechniqueRender.render("Wave overhang: the first layer over air of a round shelf hanging off a round post, by print order", View.TOP, listOf(
            Panel(listOf("as laid", "plan.faces.under.technique = \"as_laid\", support off",
                "straight bridge lines across the crescent at ${speeds(laid, "Bridge", kl)} mm/s"), laid, { it.layer == kl }, Coloring.ORDER, highlight = overAir),
            Panel(listOf("wave overhang (default)", "plan.faces.under.technique = \"wave_overhang\", support off",
                "${beads.size} beads at ${speeds(wave, "Wave overhang", kw)} mm/s, front by front from the post's edge"),
                wave, { it.layer == kw }, Coloring.ORDER, highlight = overAir),
        ), reviewRender("technique_wave_overhang.png"), window = win, lineShare = 0.4)
    }
})
