package plymeld.commands

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import plymeld.commands.TechniqueSamples.SOLID
import plymeld.commands.TechniqueSamples.box
import plymeld.commands.TechniqueSamples.heightField
import plymeld.commands.TechniqueSamples.slice
import plymeld.commands.TechniqueSamples.stl
import plymeld.commands.TechniqueSamples.strip
import plymeld.check.TechniqueRender
import plymeld.check.TechniqueRender.Coloring
import plymeld.check.TechniqueRender.Panel
import plymeld.check.TechniqueRender.View
import plymeld.check.TechniqueRender.Window
import plymeld.check.reviewRender
import java.nio.file.Files
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos

/**
 * Sample toolpaths of the volume techniques that decide the layers,
 * each beside flat layers of one height (the default) on the same model, drawn into docs/renders/technique_*.png. Each
 * test first asserts from the g-code what its picture shows:
 *  - flat layers following the surfaces on a 20 x 20 mm block with a roof rising from 6 to 8 mm (11 deg): layers the tool's
 *    thickest (0.3 mm) below 5.5 mm and at least 0.05 mm thinner in the roof; one height: every layer above the first 0.2 mm;
 *  - the hidden core in thicker layers (`plan.technique.flat.core.layers`, bundled: as many 0.1 mm layers as fit the tool's
 *    0.3 mm thickest) on a 20 x 20 x 4 mm block in 0.1 mm layers: sparse infill on at most half as many layers as with
 *    `core.layers = "1"`, with at least twice the plastic per mm, while the walls keep every 0.1 mm layer;
 *  - curved layers on a pillow (CurvedCheckTest's: 40 x 40 mm, its top 8 + cos(pi x / 40) cos(pi y / 40)): a curved top band
 *    (`depth = "auto"`) whose layers bend only near the top (none below 6 mm), and curved from the first layer
 *    (`from = "0 mm"`), its layers bent from below 1 mm (the bend grows from nothing at the bed); flat layers, none (a layer
 *    counts as bent where its moves, outer walls aside, rise more than 0.05 mm: a scarf seam ramps within its layer);
 *  - layers that follow the underside on a strip arching 0.9 mm over a hollow between two feet on the bed: a layer whose
 *    moves rise 0.8 mm or more along it; flat layers, none;
 *  - curved tops by region on a 40 x 20 mm block, flat-topped at 6 mm on its left half, a cap rising to 7 mm on its right:
 *    with nothing locked the search gives the cap its own curved layers (bent moves, every one over the right half) and
 *    leaves the flat half flat; with `job.faces.sloped_top.look = "concentric_steps"`, no layer bent.
 * The pictures about the layers' shape fill the interior solid ([TechniqueSamples.SOLID]) so each layer reads as one line.
 * The models' sizes are the tests' own (plymeld's choice), except where a line names the test they come from.
 */
class LayerSampleTest : StringSpec({
    val d = TechniqueSamples.dir()
    fun f2(v: Double) = "%.2f".format(Locale.ROOT, v)
    fun middleY(p: TechniqueRender.Toolpath): Double { val ms = p.moves.filter { it.layer >= 1 }; return (ms.minOf { minOf(it.y0, it.y1) } + ms.maxOf { maxOf(it.y0, it.y1) }) / 2 }
    fun slab(p: TechniqueRender.Toolpath) = middleY(p).let { it - 0.25..it + 0.25 }
    fun xRange(p: TechniqueRender.Toolpath): ClosedFloatingPointRange<Double> { val ms = p.moves.filter { it.layer >= 1 }; return ms.minOf { minOf(it.x0, it.x1) } - 0.5..ms.maxOf { maxOf(it.x0, it.x1) } + 0.5 }
    /** Each layer's thickness from the stated Z of consecutive layers (the first: its own Z). */
    fun heights(p: TechniqueRender.Toolpath) = p.layerZs.indices.map { if (it == 0) p.layerZs[0] else p.layerZs[it] - p.layerZs[it - 1] }
    /** The largest rise in Z along one layer's extruding moves, its outer walls left out (a scarf seam's ramp rises within its layer too). */
    fun rise(p: TechniqueRender.Toolpath, k: Int): Double = p.layer(k).filter { it.feature != "Outer wall" }.let { ms -> if (ms.isEmpty()) 0.0 else ms.maxOf { maxOf(it.z0, it.z1) } - ms.minOf { minOf(it.z0, it.z1) } }
    /** A note at the middle of layer [k], its thickness. */
    fun note(p: TechniqueRender.Toolpath, k: Int) = (p.layerZs[k] - heights(p)[k] / 2) to "${f2(heights(p)[k])} mm"
    val all: (TechniqueRender.Move) -> Boolean = { it.layer >= 0 }

    "flat layers: one height against following the surfaces, on a block with a shallow roof" {
        Files.writeString(d.resolve("roof.stl"), stl(heightField(-10.0, -10.0, 10.0, 10.0, 20, 2) { x, _ -> 6 + 2 * (1 - abs(x) / 10) }))
        val one = slice(d, listOf("roof.stl"), SOLID, "flat_one.gcode")
        val follow = slice(d, listOf("roof.stl"), SOLID + "[plan.technique.flat]\nheights = \"follow\"\n", "flat_follow.gcode")
        val ho = heights(one); val hf = heights(follow)
        withClue("one height: $ho") { ho.drop(1).all { abs(it - 0.2) < 1e-3 } shouldBe true }
        val below = follow.layerZs.indices.filter { it > 0 && follow.layerZs[it] < 5.5 }.map { hf[it] }
        val roof = follow.layerZs.indices.filter { follow.layerZs[it] > 6.2 }.map { hf[it] }
        // below the roof the walls are vertical: as thick as the tool allows (0.3 mm, bore x 0.75); on the roof thinner, by the slope's stair-step rule
        withClue("following: below the roof $below, in it $roof") { below.min() shouldBeGreaterThan 0.299; roof.max() shouldBeLessThan below.min() - 0.05 }
        fun notes(p: TechniqueRender.Toolpath) = listOf(4.5, 5.5, 7.0, 7.6).map { note(p, p.nearestLayer(it)) }
        val win = Window(xRange(one), 3.6..8.3)
        TechniqueRender.render("Flat layers: a 20 x 20 mm block with a roof from 6 to 8 mm, cut through its middle, from z 3.6", View.SIDE, listOf(
            Panel(listOf("one height (default)", "plan.technique.flat.heights = \"one\", layer.height = \"0.2 mm\"; interior solid",
                "${one.layerZs.size} layers, all 0.2 mm"), one, all, Coloring.HEIGHT, slab = slab(one), notes = notes(one)),
            Panel(listOf("following the surfaces", "plan.technique.flat.heights = \"follow\" (step.max: the layer height); interior solid",
                "${follow.layerZs.size} layers: ${f2(below.max())} mm up the straight walls, ${f2(roof.max())} mm on the roof"),
                follow, all, Coloring.HEIGHT, slab = slab(follow), notes = notes(follow)),
        ), reviewRender("technique_flat_layers.png"), zScale = 3.0, window = win)
    }

    "the hidden core in thicker layers: sparse infill laid every third 0.1 mm layer, three layers thick" {
        Files.writeString(d.resolve("core.stl"), stl(box(-10.0, -10.0, 0.0, 10.0, 10.0, 4.0)))
        val fine = "[plan]\ntechnique.flat.layer.height = \"0.1 mm\"\n"
        val each = slice(d, listOf("core.stl"), fine + "technique.flat.core.layers = \"1\"\n", "core_off.gcode")
        val core = slice(d, listOf("core.stl"), fine, "core_on.gcode")
        fun sparse(p: TechniqueRender.Toolpath) = p.moves.filter { it.feature == "Sparse infill" }
        fun sparseLayers(p: TechniqueRender.Toolpath) = sparse(p).map { it.layer }.distinct().size
        fun sparseFlow(p: TechniqueRender.Toolpath) = sparse(p).map { it.ePerMm }.sorted().let { it[it.size / 2] }
        fun wallLayers(p: TechniqueRender.Toolpath) = p.moves.filter { it.feature == "Outer wall" }.map { it.layer }.distinct().size
        withClue("layers with sparse infill: ${sparseLayers(core)} against ${sparseLayers(each)}") { (2 * sparseLayers(core) <= sparseLayers(each)) shouldBe true }
        sparseFlow(core) shouldBeGreaterThan 2 * sparseFlow(each)
        // the beads' thickness from their plastic per mm (the file declares the layer's 0.1 mm for both): three layers' worth in the core
        fun bead(p: TechniqueRender.Toolpath) = sparse(p).map { p.beadHeight(it) }.sorted().let { it[it.size / 2] }
        withClue("sparse beads ${bead(core)} mm thick against ${bead(each)}") { bead(core) shouldBeGreaterThan 0.25; bead(each) shouldBeLessThan 0.13 }
        withClue("the walls on every layer either way") { wallLayers(core) shouldBe core.layerZs.size; wallLayers(each) shouldBe each.layerZs.size }
        // from the side, the whole block projected: the sparse lines of a layer run across the interior and project as one
        // line; of the walls only those running across the view (the block's left and right sides), so they do not hide it
        val view: (TechniqueRender.Move) -> Boolean = { it.layer >= 1 && (it.feature == "Sparse infill" ||
            (it.feature.endsWith("wall") && abs(it.y1 - it.y0) > abs(it.x1 - it.x0))) }
        val x0 = core.moves.filter { it.feature == "Outer wall" }.minOf { minOf(it.x0, it.x1) }
        val win = Window(x0 - 0.5..x0 + 5.5, 0.55..2.75)
        fun notes(p: TechniqueRender.Toolpath) = sparse(p).filter { it.layerZ in 1.0..2.6 }.map { it.layer }.distinct().map { k ->
            val h = sparse(p).filter { it.layer == k }.map { p.beadHeight(it) }.sorted().let { it[it.size / 2] }; (p.layerZs[k] - h / 2) to "${f2(h)} mm" }.let { ns -> if (ns.size > 6) ns.filterIndexed { i, _ -> i % 4 == 0 } else ns }
        TechniqueRender.render("Hidden core in thicker layers: the left side of a 20 x 20 x 4 mm block in 0.1 mm layers", View.SIDE, listOf(
            Panel(listOf("every layer its own sparse infill", "plan.technique.flat.core.layers = \"1\"",
                "walls and sparse infill both 0.1 mm thick, on all ${sparseLayers(each)} layers"), each, view, Coloring.HEIGHT, notes = notes(each), plasticHeight = true),
            Panel(listOf("the core in thicker layers (default)", "core.layers bundled: 3 at 0.1 mm under the tool's 0.3 mm",
                "walls still 0.1 mm; sparse infill every third layer, %.1f x the plastic per mm".format(Locale.ROOT, sparseFlow(core) / sparseFlow(each))),
                core, view, Coloring.HEIGHT, notes = notes(core), plasticHeight = true),
        ), reviewRender("technique_core_layers.png"), zScale = 5.0, window = win)
    }

    "curved layers: a top band, and from the first layer, against flat layers, on a pillow" {
        Files.writeString(d.resolve("pillow.stl"), stl(heightField(-20.0, -20.0, 20.0, 20.0, 40, 40) { x, y -> 8 + cos(PI * x / 40) * cos(PI * y / 40) }))
        val obj = "[plan.object.\"pillow.stl\"]\n"
        // flat layers, no curved island (since islands are taken in with a taller part, stage 6, the pillow's top is one the
        // decision weighs and takes; the panel shows flat layers)
        val flat = slice(d, listOf("pillow.stl"), SOLID + "[job]\nfaces.sloped_top.look = \"concentric_steps\"\n", "curved_flat.gcode")
        val top = slice(d, listOf("pillow.stl"), SOLID + obj + "band.1.technique = \"curved\"\nband.1.depth = \"auto\"\n", "curved_top.gcode")
        val whole = slice(d, listOf("pillow.stl"), SOLID + obj + "band.1.technique = \"curved\"\nband.1.from = \"0 mm\"\n", "curved_whole.gcode")
        fun bent(p: TechniqueRender.Toolpath) = p.layerZs.indices.filter { rise(p, it) > 0.05 }
        bent(flat).size shouldBe 0
        val bt = bent(top); val bw = bent(whole)
        withClue("the top band's bent layers $bt") { bt.size shouldBeGreaterThan 2; bt.all { top.layerZs[it] > 6.0 } shouldBe true }
        withClue("curved from the first layer: bent layers $bw") { whole.layerZs[bw.min()] shouldBeLessThan 1.0 }
        fun from(p: TechniqueRender.Toolpath, k: List<Int>) = "%.2f".format(Locale.ROOT, p.layerZs[k.min()] - heights(p)[k.min()])
        val win = Window(xRange(flat), 4.0..9.3)
        TechniqueRender.render("Curved layers: a 40 x 40 mm pillow whose top rises 1 mm, cut through its middle, from z 4", View.SIDE, listOf(
            Panel(listOf("flat layers (default)", "one height, 0.2 mm; interior solid", "no layer bent: the dome stair-steps"), flat, all, Coloring.LAYER, slab = slab(flat)),
            Panel(listOf("curved top band", "band.1.technique = \"curved\", band.1.depth = \"auto\"", "${bt.size} layers bent, from z ${from(top, bt)} up; flat below"),
                top, all, Coloring.LAYER, slab = slab(top)),
            Panel(listOf("curved from the first layer", "band.1.technique = \"curved\", band.1.from = \"0 mm\"", "${bw.size} layers bent, from z ${from(whole, bw)} up, more with height"),
                whole, all, Coloring.LAYER, slab = slab(whole)),
        ), reviewRender("technique_curved.png"), zScale = 3.0, window = win)
    }

    "layers that follow the underside, against flat layers, on a strip arching over a hollow" {
        // the underside: on the bed for 20 <= |x| <= 30, rising to 0.9 mm at x = 0 as a cosine bump; the strip 2 mm thick, 10 mm wide
        val under = (0..120).map { val x = -30.0 + it * 0.5; x to if (abs(x) >= 20) 0.0 else 0.45 * (1 + cos(PI * x / 20)) }
        Files.writeString(d.resolve("arch.stl"), stl(strip(under, 2.0, 10.0)))
        val flat = slice(d, listOf("arch.stl"), SOLID, "under_flat.gcode")
        val follow = slice(d, listOf("arch.stl"), SOLID + "[plan.object.\"arch.stl\"]\nband.1.technique = \"underside\"\n", "under_on.gcode")
        val rf = flat.layerZs.indices.maxOf { rise(flat, it) }; val ru = follow.layerZs.indices.maxOf { rise(follow, it) }
        rf shouldBeLessThan 0.05
        ru shouldBeGreaterThan 0.8
        val body: (TechniqueRender.Move) -> Boolean = { it.layer >= 0 && it.feature != "Prime line" && it.feature != "Brim" }
        TechniqueRender.render("Layers that follow the underside: a strip 2 mm thick arching 0.9 mm over a hollow, cut through its middle", View.SIDE, listOf(
            Panel(listOf("flat layers (default)", "one height, 0.2 mm; interior solid", "largest rise along a layer: ${f2(rf)} mm"), flat, body, Coloring.LAYER, slab = slab(flat)),
            Panel(listOf("following the underside", "band.1.technique = \"underside\"; interior solid", "each layer rises ${f2(ru)} mm along the arch"),
                follow, body, Coloring.LAYER, slab = slab(follow)),
        ), reviewRender("technique_underside.png"), zScale = 6.0)
    }

    "curved tops by region: the search gives a cap its own curved layers and leaves the flat top flat" {
        // flat at 6 mm for x < 0; for x >= 0 a cap rising to 7 mm at (10, 0), meeting the flat top and the sides smoothly
        Files.writeString(d.resolve("capblock.stl"), stl(heightField(-20.0, -10.0, 20.0, 10.0, 40, 20) { x, y ->
            if (x < 0) 6.0 else 6.0 + cos(PI * (x - 10) / 20) * cos(PI * y / 20) }))
        val flat = slice(d, listOf("capblock.stl"), SOLID + "[job]\nfaces.sloped_top.look = \"concentric_steps\"\n", "island_flat.gcode")
        val island = slice(d, listOf("capblock.stl"), SOLID, "island_on.gcode")
        val body: (TechniqueRender.Move) -> Boolean = { it.layer >= 0 && it.feature != "Prime line" && it.feature != "Brim" }
        /** Extruding moves that change Z along their length: a curved layer's (a scarf seam's ramp, on outer walls, left out). */
        fun bent(p: TechniqueRender.Toolpath) = p.moves.filter { body(it) && it.feature != "Outer wall" && abs(it.z1 - it.z0) > 0.01 }
        bent(flat).size shouldBe 0
        val bi = bent(island)
        withClue("the cap's curved layers: ${bi.size} bent moves") { bi.size shouldBeGreaterThan 20 }
        // the block's middle in the bed's frame (x = 0 in the model's): the flat half left of it, the cap right of it
        val mid = island.moves.filter { it.layer >= 1 && it.feature == "Outer wall" }.let { w -> (w.minOf { minOf(it.x0, it.x1) } + w.maxOf { maxOf(it.x0, it.x1) }) / 2 }
        fun top(p: TechniqueRender.Toolpath) = p.moves.filter { body(it) && it.feature == "Top surface" }
        // the flat half's top stays flat at 6 mm, clear of where it meets the cap (lines crossing there ramp a little)
        val flatTop = top(island).filter { maxOf(it.x0, it.x1) < mid - 3.0 }
        withClue("the flat half's top surface: ${flatTop.size} moves, z ${flatTop.minOfOrNull { minOf(it.z0, it.z1) }}..${flatTop.maxOfOrNull { maxOf(it.z0, it.z1) }}") {
            flatTop.size shouldBeGreaterThan 10; flatTop.all { abs(it.z0 - 6.0) < 0.02 && abs(it.z1 - 6.0) < 0.02 } shouldBe true }
        // the cap's top laid in lines that follow it: over the cap, top-surface moves that rise or fall along themselves;
        // flat layers' top over the cap is level line by line (the control)
        fun capTopBent(p: TechniqueRender.Toolpath) = top(p).count { minOf(it.x0, it.x1) > mid + 2.0 && abs(it.z1 - it.z0) > 0.05 }
        withClue("the cap's top in curved lines: ${capTopBent(island)} against flat layers' ${capTopBent(flat)}") { capTopBent(island) shouldBeGreaterThan 10; capTopBent(flat) shouldBe 0 }
        val top = bi.maxOf { maxOf(it.z0, it.z1) }
        val win = Window(xRange(flat), 4.0..7.4)
        TechniqueRender.render("Curved tops by region: a 40 x 20 mm block, flat at 6 mm on the left, a cap to 7 mm on the right, cut through its middle, from z 4", View.SIDE, listOf(
            Panel(listOf("no curved island", "job.faces.sloped_top.look = \"concentric_steps\"; interior solid", "flat layers throughout: the cap stair-steps"),
                flat, body, Coloring.LAYER, slab = slab(flat)),
            Panel(listOf("decided by the search (default)", "nothing locked; interior solid",
                "the cap in curved layers of its own, up to z ${f2(top)}; the flat half flat"), island, body, Coloring.LAYER, slab = slab(island)),
        ), reviewRender("technique_curved_island.png"), zScale = 3.0, window = win)
    }
})
