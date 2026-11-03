package plymeld.commands

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.shouldBe
import plymeld.commands.TechniqueSamples.cylinder
import plymeld.commands.TechniqueSamples.prism
import plymeld.commands.TechniqueSamples.runStarts
import plymeld.commands.TechniqueSamples.slice
import plymeld.commands.TechniqueSamples.stl
import plymeld.check.TechniqueRender
import plymeld.check.TechniqueRender.Mark
import plymeld.check.TechniqueRender.Panel
import plymeld.check.TechniqueRender.View
import plymeld.check.reviewRender
import java.nio.file.Files
import java.util.Locale
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Sample toolpaths of the seam styles (curve techniques), drawn into docs/renders/technique_*.png. Each test first
 * asserts from the g-code what its picture shows:
 *  - corner seams against back seams on an L-shaped prism (20 x 20 mm, an 8 mm wide arm each way, 5 mm high): with
 *    `plan.seam = "corner_seam"` every layer's outer wall starts within 0.6 mm of the L's inside corner; with `"back_seam"`
 *    within 0.3 mm of the back (the largest Y the outer wall reaches);
 *  - scarf seams against a plain seam on a round post (radius 10 mm, 5 mm high, no corner): with the bundled
 *    `plan.seam = "scarf_seam"` each layer above the first has outer wall moves that change Z (the ramp), 7 to 13 mm of them
 *    for the bundled 10 mm scarf length, and its loop's last move feeds under half the layer's median plastic per mm (the
 *    end re-printed thinning); with `"corner_seam"` no outer wall move changes Z, and the plain seams lie in the window
 *    the picture draws round the scarf seams;
 *  - least-visible seams against back seams on a round post (radius 10 mm, 5 mm high, no corner) looked at from its left
 *    (`plan.view_from = "left"`, the model's small-x side): with `plan.seam = "least_visible_seam"` every layer's outer wall
 *    starts on the right, turned away from the viewer (within 45 degrees of +X round the post's axis); with `"back_seam"` at
 *    the back.
 * The models' sizes are the tests' own (plymeld's choice).
 */
class SeamSampleTest : StringSpec({
    val d = TechniqueSamples.dir()

    "corner seams against back seams: where each layer's outer wall starts on an L" {
        Files.writeString(d.resolve("ell.stl"), stl(prism(listOf(0.0 to 0.0, 20.0 to 0.0, 20.0 to 8.0, 8.0 to 8.0, 8.0 to 20.0, 0.0 to 20.0), 0.0, 5.0)))
        val corner = slice(d, listOf("ell.stl"), "[plan]\nseam = \"corner_seam\"\n", "seam_corner.gcode")
        val back = slice(d, listOf("ell.stl"), "[plan]\nseam = \"back_seam\"\n", "seam_back.gcode")
        fun starts(p: TechniqueRender.Toolpath) = runStarts(p, "Outer wall").filter { it.layer >= 1 }
        // the inside corner from the outer wall's own box (its center line lies inset; the inside corner's center line sits at the box's corner + 8 each way)
        fun box(p: TechniqueRender.Toolpath): DoubleArray { val w = p.moves.filter { it.feature == "Outer wall" && it.layer >= 1 }
            return doubleArrayOf(w.minOf { minOf(it.x0, it.x1) }, w.minOf { minOf(it.y0, it.y1) }, w.maxOf { maxOf(it.x0, it.x1) }, w.maxOf { maxOf(it.y0, it.y1) }) }
        val bc = box(corner); val inside = bc[0] + 8 to bc[1] + 8
        val cs = starts(corner); val bs = starts(back)
        (cs.size >= 20) shouldBe true
        withClue("corner seams at the inside corner") { cs.maxOf { hypot(it.x0 - inside.first, it.y0 - inside.second) } shouldBeLessThan 0.6 }
        val bb = box(back)
        withClue("back seams at the back") { bs.maxOf { bb[3] - it.y0 } shouldBeLessThan 0.3 }
        // control: the two styles put the seam in different places
        withClue("back seams not at the inside corner") { bs.minOf { hypot(it.x0 - (bb[0] + 8), it.y0 - (bb[1] + 8)) } shouldBeGreaterThan 2.0 }
        val kc = corner.nearestLayer(2.5); val kb = back.nearestLayer(2.5)
        fun marks(ss: List<TechniqueRender.Move>) = ss.map { Mark(it.x0, it.y0, it.z0) }
        TechniqueRender.render("Seam placement: an L-shaped prism, its layer 2.5 mm up and every layer's seam", View.TOP, listOf(
            Panel(listOf("corner seam", "plan.seam = \"corner_seam\" (also the bundled scarf seams' plain style)",
                "${cs.size} layers' outer walls start at the inside corner (ringed)"), corner, { it.layer == kc }, marks = marks(cs)),
            Panel(listOf("back seam", "plan.seam = \"back_seam\"", "${bs.size} layers' outer walls start at the back (ringed)"), back, { it.layer == kb }, marks = marks(bs)),
        ), reviewRender("technique_corner_seam.png"))
    }

    "scarf seams against a plain seam: a round post's outer wall unrolled" {
        Files.writeString(d.resolve("post.stl"), stl(cylinder(0.0, 0.0, 10.0, 5.0)))
        val plain = slice(d, listOf("post.stl"), "[plan]\nseam = \"corner_seam\"\n", "scarf_plain.gcode")
        val scarf = slice(d, listOf("post.stl"), "[plan]\nseam = \"scarf_seam\"\n", "scarf_on.gcode")
        fun ramps(p: TechniqueRender.Toolpath) = p.moves.filter { it.feature == "Outer wall" && it.layer >= 1 && abs(it.z1 - it.z0) > 1e-4 }
        val perLayer = ramps(scarf).groupBy { it.layer }.mapValues { (_, ms) -> ms.sumOf { it.length } }
        withClue("a ramp on every layer above the first: $perLayer") { perLayer.size shouldBe scarf.layerZs.size - 1 }
        withClue("each ramp 7 to 13 mm long: $perLayer") { perLayer.values.all { it in 7.0..13.0 } shouldBe true }
        ramps(plain).size shouldBe 0
        // the loop's end re-prints the ramp thinning: its last outer wall move feeds under half the layer's median plastic per mm
        for ((k, _) in perLayer) {
            val w = scarf.layer(k).filter { it.feature == "Outer wall" }
            val median = w.map { it.ePerMm }.sorted()[w.size / 2]
            withClue("layer $k: the last outer wall move ${w.last().ePerMm} against the median $median") { (w.last().ePerMm < 0.5 * median) shouldBe true }
        }
        // the post's axis, from the outer wall's own box
        fun axis(p: TechniqueRender.Toolpath): Pair<Double, Double> { val w = p.moves.filter { it.feature == "Outer wall" }
            return (w.minOf { it.x0 } + w.maxOf { it.x0 }) / 2 to (w.minOf { it.y0 } + w.maxOf { it.y0 }) / 2 }
        val ax = axis(scarf)
        withClue("both files place the post alike (one axis for the unrolled view)") { hypot(axis(plain).first - ax.first, axis(plain).second - ax.second) shouldBeLessThan 0.05 }
        // the view faces the seam: the ramps' mean direction from the axis; 70 degrees either side of it (plymeld's choice) drawn
        val facing = ramps(scarf).let { rs -> Math.atan2(rs.sumOf { (it.y0 + it.y1) / 2 - ax.second }, rs.sumOf { (it.x0 + it.x1) / 2 - ax.first }) }
        fun near(m: TechniqueRender.Move): Boolean {
            val a = Math.atan2((m.y0 + m.y1) / 2 - ax.second, (m.x0 + m.x1) / 2 - ax.first) - facing
            return abs(Math.atan2(Math.sin(a), Math.cos(a))) < Math.toRadians(70.0)
        }
        val inBand: (TechniqueRender.Move) -> Boolean = { it.feature == "Outer wall" && it.layerZ in 2.0..2.81 }
        val plainSeams = runStarts(plain, "Outer wall").filter { inBand(it) }
        withClue("the plain seams in the window, where the scarf seams start") { plainSeams.all { near(it) } shouldBe true }
        TechniqueRender.render("Scarf seam: a round post's outer wall near the seam, layers z 2.0 to 2.8, by plastic per mm", View.UNROLLED, listOf(
            Panel(listOf("plain seam", "plan.seam = \"corner_seam\" (no corner: aligned with the layer below)",
                "each loop's ends butted where it starts (ringed); no move changes Z"), plain, { inBand(it) && near(it) }, marks = plainSeams.map { Mark(it.x0, it.y0, it.z0) }, coloring = TechniqueRender.Coloring.FLOW, plasticHeight = true),
            Panel(listOf("scarf seam (bundled)", "plan.seam = \"scarf_seam\" (where = smooth, length 10 mm, start 0.25)",
                "a ramp of " + perLayer.values.take(1).joinToString { "%.1f mm".format(Locale.ROOT, it) } + " a layer from a quarter of the layer up, re-printed thinning at the loop's end"),
                scarf, { inBand(it) && near(it) }, TechniqueRender.Coloring.FLOW, plasticHeight = true),
        ), reviewRender("technique_scarf_seam.png"), zScale = 10.0, center = ax, facing = facing)
    }

    "least-visible seams against back seams: a round post looked at from its left" {
        Files.writeString(d.resolve("post_view.stl"), stl(cylinder(0.0, 0.0, 10.0, 5.0)))
        val view = "[plan]\nview_from = \"left\"\n"
        val back = slice(d, listOf("post_view.stl"), view + "seam = \"back_seam\"\n", "seam_view_back.gcode")
        val least = slice(d, listOf("post_view.stl"), view + "seam = \"least_visible_seam\"\n", "seam_view_least.gcode")
        fun starts(p: TechniqueRender.Toolpath) = runStarts(p, "Outer wall").filter { it.layer >= 1 }
        fun box(p: TechniqueRender.Toolpath): DoubleArray { val w = p.moves.filter { it.feature == "Outer wall" && it.layer >= 1 }
            return doubleArrayOf(w.minOf { minOf(it.x0, it.x1) }, w.minOf { minOf(it.y0, it.y1) }, w.maxOf { maxOf(it.x0, it.x1) }, w.maxOf { maxOf(it.y0, it.y1) }) }
        val ls = starts(least); val bs = starts(back)
        (ls.size >= 20) shouldBe true
        val bl = box(least); val bb = box(back)
        /** Each seam's direction from the post's axis, degrees from +X (the right; the viewer is at -X, the left). */
        fun angles(ss: List<TechniqueRender.Move>, b: DoubleArray) = ss.map { Math.toDegrees(Math.atan2(it.y0 - (b[1] + b[3]) / 2, it.x0 - (b[0] + b[2]) / 2)) }
        val la = angles(ls, bl); val ba = angles(bs, bb)
        withClue("least-visible seams on the right half, turned away from the viewer on the left: ${la.map { "%.0f".format(Locale.ROOT, it) }}") { la.maxOf { abs(it) } shouldBeLessThan 45.0 }
        withClue("back seams at the back") { bs.maxOf { bb[3] - it.y0 } shouldBeLessThan 0.3 }
        // control: the back seams a quarter turn from the right
        withClue("back seams not on the right: ${ba.map { "%.0f".format(Locale.ROOT, it) }}") { ba.minOf { abs(it) } shouldBeGreaterThan 80.0 }
        val kl = least.nearestLayer(2.5); val kb = back.nearestLayer(2.5)
        fun marks(ss: List<TechniqueRender.Move>) = ss.map { Mark(it.x0, it.y0, it.z0) }
        TechniqueRender.render("Least-visible seam: a round post looked at from its left (plan.view_from = \"left\"), its layer 2.5 mm up and every layer's seam", View.TOP, listOf(
            Panel(listOf("back seam", "plan.seam = \"back_seam\"", "${bs.size} layers' outer walls start at the back (ringed)"), back, { it.layer == kb }, marks = marks(bs)),
            Panel(listOf("least-visible seam", "plan.seam = \"least_visible_seam\", plan.view_from = \"left\"",
                "${ls.size} layers' outer walls start on the back right, turned away from the viewer (ringed)"), least, { it.layer == kl }, marks = marks(ls)),
        ), reviewRender("technique_least_visible_seam.png"))
    }
})
