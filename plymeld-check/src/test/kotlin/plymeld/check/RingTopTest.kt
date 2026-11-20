package plymeld.check

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import plymeld.check.catalog.Catalog
import plymeld.check.catalog.Models
import plymeld.check.catalog.Slicing
import plymeld.mesh.Slicer
import plymeld.model.SliceSettings

/**
 * A top in round rings (concentric-inside-out) covers its surface up to the walls: where the rings run along a wall (an
 * oval's sides, round its ends), the last arc that fits ended where its center met the region's edge, and between the arc
 * inside it and the wall lay a wedge as wide as nearly a spacing that nothing covered, a dashed slit along the top's edge.
 * Each circle now runs on past its arc there, narrowed to what lies within the walls' reach (SurfaceFill.circles).
 *
 * The catalog's oval (24 by 14 mm) read on a quarter of its top layer (its side and its end) by WallCoverage against the
 * model's own cut, each bead at its width from its plastic: the uncovered ground (all of it beside the walls) and the
 * widest uncovered spot. The control: the same layer with its narrowed moves left out (each circle's run past its arc, not
 * its arc), which the measure must refuse. With the arcs alone (the code before the circles ran on) the quarter read
 * 0.298 mm2 uncovered, the widest spot 0.120 mm; the control 0.293 mm2 and 0.120 mm; the circles running on 0.087 mm2
 * (hairlines where beads meet) and 0.020 mm.
 */
class RingTopTest : StringSpec({
    val e = Catalog.byName("oval top in rings")
    val w = Catalog.write(e)
    val st = SliceSettings.of(w.setup)
    val read = WallCoverage.read(w.g, st.filamentDiameterMm, st.flowRatio)
    val zBase = w.plan.parts.minOf { it.bounds()[2] }
    val top = read.layers.last { l -> l.segs.any { it.feature == "Top surface" } }
    val section = Slicer.slice(w.plan.parts[0], doubleArrayOf(top.z - top.h / 2 + zBase))[0].loops
    // a quarter of the oval (its side and its end, as the rest by symmetry) on 0.01 mm cells: the whole at the walls'
    // 5 microns is 23 million cells, a gigabyte of grids
    val win = WallCoverage.Window(Models.CX - 0.3, Models.CY - 0.3, Models.CX + 12.6, Models.CY + 7.6, 0.01)
    val floor = (st.minFeatureUm + 2 * st.resolutionUm) / 1000.0
    fun measure(layer: WallCoverage.Layer, name: String) = WallCoverage.measure(read, layer, section, win, 0, floor, st.resolutionUm / 1000.0,
        st.infillWallOverlap, map = reviewRender("ring-top-$name.png"))
    /** The top's line width, as set. */
    val topW = st.topWidthUm / 1000.0

    "the oval's top layer: the rings reach the walls, nothing bare wider than a hairline; control: without the narrowed runs, wedges" {
        val now = measure(top, "oval")
        println("oval top in rings, top layer: $now (line ${"%.3f".format(java.util.Locale.ROOT, topW)} mm)")
        // a move read narrower than the line by more than its plastic's rounding in the file
        fun narrow(s: WallCoverage.Seg) = s.feature == "Top surface" && s.w < topW - 0.01
        val narrowed = top.segs.count { narrow(it) }
        println("narrowed moves: $narrowed")
        val without = measure(WallCoverage.Layer(top.z, top.h, top.segs.filter { !narrow(it) }), "oval-control")
        println("control, the narrowed moves left out: $without")
        // the limits: 0.15 mm2 uncovered in the quarter (the hairlines where beads meet read about 0.09), no spot wider than
        // a quarter of the top's line (0.075 mm); the control breaks both
        (now.uncoveredMm2 <= 0.15) shouldBe true
        (now.widestUncoveredMm <= topW / 4) shouldBe true
        (without.uncoveredMm2 > 0.15) shouldBe true
        (without.widestUncoveredMm > topW / 4) shouldBe true
        (narrowed > 0) shouldBe true                                       // the circles do run on, narrowed
        // no more plastic laid twice than without the narrowed runs (there: the steps between the joined rings at the back)
        // but for hairlines a cell or two wide (0.02 mm2)
        (now.doubledMm2 <= without.doubledMm2 + 0.025) shouldBe true
    }

    "the oval's g-code passes the pre-print check, nothing laid twice" {
        val c = Slicing.checked(w)
        c.problems.shouldBeEmpty()
        c.laidTwice.spots shouldBe 0
    }
})
