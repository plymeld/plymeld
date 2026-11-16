package plymeld.geom

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * Polygons.group gives each hole to the outer that holds it, not to a smaller outer it only touches. The set (µm): an
 * outer 10 by 6 mm; a hole inside it, starting at the left end of a sliver 4 mm long and a micron tall that lies in the
 * hole (an island in it, as the leftover between two walls' bands leaves one); the hole touches the sliver only there.
 * The walls of a two-color test model left such a set, and the leftover's line was lost: the hole went to the sliver, the
 * outer was read as having none. Control: the hole's first vertex is on the sliver, the smaller outer, so a rule reading
 * that vertex alone picks the sliver.
 */
class PolygonsGroupTest : StringSpec({
    fun poly(vararg xy: Long) = Polygon(longArrayOf(*xy))
    val outer = poly(0, 0, 10_000, 0, 10_000, 6_000, 0, 6_000)
    // clockwise, from the sliver's left end
    val hole = poly(3_000, 3_000, 2_000, 4_000, 8_000, 4_000, 8_000, 2_000, 2_000, 2_000)
    val sliver = poly(3_000, 3_000, 7_000, 3_000, 7_000, 3_001, 3_000, 3_001)
    val set = listOf(sliver, hole, outer)

    "the set is as described: outers counter-clockwise, the hole clockwise, the sliver inside the hole" {
        outer.isCcw() shouldBe true
        sliver.isCcw() shouldBe true
        hole.isCcw() shouldBe false
        Polygons.contains(hole.reversed(), 5_000, 3_000) shouldBe true
    }
    "a hole touching a smaller outer at its first vertex goes to the outer that holds it" {
        val g = Polygons.group(set).associate { (o, hs) -> o to hs }
        g.getValue(outer) shouldBe listOf(hole)
        g.getValue(sliver) shouldBe emptyList()
    }
    "control: the hole's first vertex alone picks the sliver" {
        val byFirstVertex = listOf(sliver, outer).sortedBy { Math.abs(it.signedArea2()) }.first { Polygons.contains(it, hole.x(0), hole.y(0)) }
        (byFirstVertex === sliver) shouldBe true
    }
    "a hole touching its own outer at its first vertex stays that outer's" {
        val notch = poly(0, 3_000, 1_000, 5_000, 1_000, 1_000)   // clockwise, its first vertex on the outer's left side
        notch.isCcw() shouldBe false
        Polygons.group(listOf(outer, notch)).single().second shouldBe listOf(notch)
    }
})
