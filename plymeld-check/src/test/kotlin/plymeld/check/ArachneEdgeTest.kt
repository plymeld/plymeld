package plymeld.check

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import plymeld.core.Walls
import plymeld.geom.Polygon

/**
 * Variable-width walls at the model's edge (when they became the default): a center line along the outline
 * is the outer wall there ([Walls.alongOutline]), and a short one along it (a wedge tip, a frame's cut end) is kept, not
 * dropped as the void a bead leaves in a corner between loops. Worked shapes; controls that must fail.
 */
class ArachneEdgeTest : StringSpec({
    "a center line down a 0.8 mm strip runs along its outline; control: a line 3 mm inside a solid square does not" {
        val strip = listOf(Polygon(longArrayOf(0, 0, 10_000, 0, 10_000, 800, 0, 800)))
        Walls.alongOutline(longArrayOf(500, 400, 9_500, 400), longArrayOf(800, 800), strip) shouldBe true
        val square = listOf(Polygon(longArrayOf(0, 0, 10_000, 0, 10_000, 10_000, 0, 10_000)))
        Walls.alongOutline(longArrayOf(3_000, 3_000, 7_000, 3_000), longArrayOf(400, 400), square) shouldBe false
    }
    // a wedge tip 1.5 mm long, 0.8 mm across at its base: its center line is about 1.2 mm long and up to about 0.7 mm wide,
    // shorter than twice its widest, which the corner-void rule drops
    val tip = listOf(Polygon(longArrayOf(0, 0, 1_500, 400, 0, 800)))
    "a short center line along the outline (a wedge tip) is kept when the outline is given" {
        val lines = Walls.centerLines(tip, 300, 150, 900, 2.0, 300, outline = tip)
        lines.size shouldBeGreaterThan 0
    }
    "control: the same tip with no outline given (the corner-void rule alone) gets no line" {
        Walls.centerLines(tip, 300, 150, 900, 2.0, 300).size shouldBe 0
    }
})
