package plymeld.check

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import plymeld.check.catalog.Catalog
import plymeld.check.catalog.Models
import plymeld.check.catalog.Shapes
import plymeld.check.catalog.Slicing
import plymeld.core.Walls
import plymeld.geom.Booleans
import plymeld.geom.Polygon
import plymeld.model.SliceSettings

/**
 * An island laid by its beads (a narrow island, DECISIONS 28) hands nothing to infill: what the walls of 26 hand to infill
 * there is taken out by area. The catalog's teeth (Models.TEETH: triangles about two lines across, a side bulging out): the
 * walls' loop fits each only as a scrap shorter than the transition length, a center line in its place, so the walls hand
 * most of the tooth to infill, a piece whose first point (a mitred tip) lies a few hundredths past the tooth's apex. Taken out
 * by that first point alone, the piece stays, and a bridge (the tooth hangs over air) or solid infill is laid across the
 * tooth's own beads: plastic laid twice, a bridge or solid infill over an outer wall.
 *  - each tooth, with the walls of the catalog's walls rig: its loop is laid by its beads, and nothing inside it is handed to
 *    infill; control: taken out by first point (Walls.arachne innerTakenByArea = false), 0.33 and 0.29 mm² of the first
 *    two teeth are (at least 0.2 mm² required);
 *  - the catalog's teeth under a second part, sliced: no spot laid twice (taken out by first point, run by hand: 33 spots,
 *    6.5 mm², bridge over outer wall, on 19 of the teeth's 20 layers).
 */
class BeadIslandInfillTest : StringSpec({
    val s = SliceSettings.of(testSetup(*Slicing.WALLS.values.toTypedArray()))
    fun walls(o: List<Polygon>, byArea: Boolean) = Walls.arachne(o, s.wallLoops, s.outerWallWidthUm, s.innerWallWidthUm, 80, s.minBeadUm, s.minFeatureUm,
        s.minLengthFactor, s.transitionLengthUm, toleranceUm = s.resolutionUm, endOverlap = s.infillWallOverlap, maxBeadUm = s.maxBeadFor(s.innerWallWidthUm),
        innerTakenByArea = byArea)
    /** The area (mm²) of [w]'s infill region inside [o]. */
    fun handedIn(w: plymeld.core.LayerWalls, o: List<Polygon>) = if (w.inner.isEmpty()) 0.0 else Booleans.intersection(w.inner, o).sumOf { it.signedAreaMm2() }

    "each tooth is laid by its beads and hands nothing to infill; control: taken out by first point, a tooth's most is handed to infill" {
        var controlMax = 0.0
        for ((k, t) in Models.TEETH.withIndex()) {
            val o = listOf(Shapes.um(t))
            val w = walls(o, true)
            // the beads' loop: one closed loop with widths of its own (a loop of the set walls carries none)
            (w.walls.firstOrNull()?.size ?: 0) shouldBe 1
            (w.widthsOf(0, 0) != null) shouldBe true
            val handed = handedIn(w, o)
            val old = handedIn(walls(o, false), o)
            println("tooth $k: handed to infill ${"%.4f".format(handed)} mm2; by first point ${"%.4f".format(old)} mm2")
            handed shouldBe 0.0
            controlMax = maxOf(controlMax, old)
        }
        (controlMax >= 0.2) shouldBe true
    }
    "the catalog's teeth under a second part: nothing laid twice" {
        val w = Catalog.write(Catalog.byName("teeth under a second part"))
        val r = Slicing.sliceCheck(w)
        r.problems shouldBe emptyList()
        r.laidTwice.spots shouldBe 0
    }
})
