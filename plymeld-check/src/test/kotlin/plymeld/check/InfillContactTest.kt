package plymeld.check

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import plymeld.core.Feature
import plymeld.core.Planner
import plymeld.geom.Polygon
import plymeld.model.SliceSettings
import java.util.Locale

/**
 * Where infill meets a wall: a line is clipped with its CENTER on the
 * region's edge, so the region is pulled in to where the bead merely touches the wall bead, the
 * same center distance as between two neighboring beads (w/2 - h(1 - pi/4)), before
 * infill_wall_overlap adds its bonding overlap. Clipping on the wall's edge itself laid half of
 * every line end on the wall (a deposition check against the model: testplate missing + excess plastic near
 * the surface 10.8 -> 4.7 mm3 on three layers).
 */
class InfillContactTest : StringSpec({
    "solid infill ends where its bead touches the inner wall, plus the bonding overlap" {
        val s = SliceSettings.of(testSetup())
        val n = 20
        val tops = LongArray(n) { 200L * (it + 1) }
        val outline = List(n) { listOf(Polygon(longArrayOf(0, 0, 20_000, 0, 20_000, 20_000, 0, 20_000))) }
        val layers = Planner.plan(s, outline, tops)
        val w = Planner.walls(s, outline, tops)
        // layer 1 (bottom shells): all solid; the region edge = the walls' inner edge, x = xIn
        val xIn = (0 until w.walls[1].inner[0].size).minOf { w.walls[1].inner[0].x(it) }
        val solid = layers[1].paths.filter { it.feature == Feature.BOTTOM || it.feature == Feature.SOLID }
        val minX = solid.minOf { p -> (0 until p.xy.size / 2).minOf { p.xy[2 * it] } }
        val width = solid.first().widthUm
        val expect = Planner.contactInset(width, 200) - Math.round(s.infillWallOverlap * width)
        println("bottom solid on layer 1: region edge x %d, first line center x %d: %d um inside (contact %d - overlap %d = %d)".format(Locale.ROOT,
            xIn, minX, minX - xIn, Planner.contactInset(width, 200), Math.round(s.infillWallOverlap * width), expect))
        // lines are clipped at the pulled-in edge; a diagonal line's end lies on it within a micron or two
        (Math.abs((minX - xIn) - expect) <= 5) shouldBe true
        (minX - xIn > 0) shouldBe true                         // control: the old placement was -overlap (outside the edge)
    }

    // A narrow sparse region's loop runs ALONGSIDE the wall: it sits at bead contact, as a neighboring
    // wall does, with no bonding overlap (that is for line ends). Half a line in left a crack.
    "a narrow sparse loop sits at bead contact with the inner wall" {
        val s = SliceSettings.of(testSetup())
        val n = 20
        val tops = LongArray(n) { 200L * (it + 1) }
        val outline = List(n) { listOf(Polygon(longArrayOf(0, 0, 3_000, 0, 3_000, 20_000, 0, 20_000))) }
        val layers = Planner.plan(s, outline, tops)
        val w = Planner.walls(s, outline, tops)
        val xIn = (0 until w.walls[10].inner[0].size).minOf { w.walls[10].inner[0].x(it) }
        val loops = layers[10].paths.filter { it.feature == Feature.SPARSE }
        println("narrow sparse on layer 10: ${loops.size} paths, closed ${loops.map { it.closed }}")
        loops.size shouldBe 1
        loops[0].closed shouldBe true
        val minX = (0 until loops[0].xy.size / 2).minOf { loops[0].xy[2 * it] }
        val expect = Planner.contactInset(loops[0].widthUm, 200)
        println("loop center %d um inside the region edge (contact %d; half a line was %d)".format(Locale.ROOT, minX - xIn, expect, loops[0].widthUm / 2))
        (Math.abs((minX - xIn) - expect) <= 2) shouldBe true
    }
})
