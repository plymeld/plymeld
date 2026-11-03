package plymeld.check

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

import plymeld.core.Feature
import plymeld.core.IncrementalSlicer
import plymeld.mesh.StlReader

/**
 * Walls contour by contour: in a layer with a hole, each contour's inner walls and
 * outer wall are finished before the next contour's. With inner walls first, two outer walls in a
 * row means the order went all-inner-then-all-outer, which crosses between contours twice per
 * layer, each time with a retraction.
 */
class WallOrderTest : StringSpec({
    "hole block: walls go contour by contour (inner walls first: never two outer walls in a row)" {
        val layers = IncrementalSlicer(listOf(StlReader.read(testModelsDir().resolve("holeblock.stl"))), "test", "hole").planned(testSetup())
        var withHole = 0
        val bad = ArrayList<Int>()
        for (l in layers) {
            val walls = l.paths.filter { it.feature == Feature.OUTER_WALL || it.feature == Feature.INNER_WALL }
            val outers = walls.count { it.feature == Feature.OUTER_WALL }
            if (outers < 2 || walls.none { it.feature == Feature.INNER_WALL }) continue
            withHole++
            if (walls.zipWithNext().any { (a, b) -> a.feature == Feature.OUTER_WALL && b.feature == Feature.OUTER_WALL }) bad.add(l.index)
        }
        println("  hole block: $withHole layers with 2+ contours; back-to-back outer walls on ${bad.size}")
        (withHole > 0) shouldBe true     // control: the check sees layers with a hole at all
        bad shouldBe emptyList()
    }
})
