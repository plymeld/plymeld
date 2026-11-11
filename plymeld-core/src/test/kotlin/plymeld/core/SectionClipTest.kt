package plymeld.core

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs

/**
 * Planned lines cut to a section left open (SectionClip), on paths made by hand: a 10 mm square loop and lines beside it,
 * cut by a box whose right side runs through the loop's middle (x = 5 mm). Lengths and ends are counted from the paths'
 * own points. Controls: the loop wholly inside a larger box comes back itself, at its whole length; the path after an
 * uncut one keeps its unretracted travel.
 */
class SectionClipTest : StringSpec({
    fun length(p: ToolPath): Double {
        var s = 0.0; val m = p.xy.size / 2
        for (k in 0 until (if (p.closed) m else m - 1)) { val j = (k + 1) % m; s += Math.hypot((p.xy[2 * j] - p.xy[2 * k]).toDouble(), (p.xy[2 * j + 1] - p.xy[2 * k + 1]).toDouble()) }
        return s
    }
    // a square loop from (0, 0), counterclockwise, reached unretracted; a line across it; a line beyond the box
    val loop = ToolPath(Feature.OUTER_WALL, longArrayOf(0, 0, 10_000, 0, 10_000, 10_000, 0, 10_000), true, 400, 200, retractBefore = false, scarfUm = 10_000, scarfStart = 0.25, wall = 0)
    val across = ToolPath(Feature.SPARSE, longArrayOf(1_000, 5_000, 9_000, 5_000), false, 600, 200, retractBefore = false, widths = longArrayOf(400, 600))
    val beyond = ToolPath(Feature.SPARSE, longArrayOf(7_000, 2_000, 9_000, 2_000), false, 400, 200, retractBefore = false)
    val inside = ToolPath(Feature.SPARSE, longArrayOf(1_000, 1_000, 4_000, 1_000), false, 400, 200, retractBefore = false)

    "a loop the box cuts is laid as its pieces inside, open, ending on the box's side; control: wholly inside, it is itself" {
        val pieces = SectionClip.path(loop, -1_000, 5_000, -1_000, 11_000)
        println("pieces: " + pieces.joinToString { "%.1f mm from (%d, %d) to (%d, %d)".format(java.util.Locale.ROOT, length(it) / 1000, it.xy[0], it.xy[1], it.xy[it.xy.size - 2], it.xy[it.xy.size - 1]) })
        // the bottom's half from the start, then the top's half and the left side back to the start: 5 + 15 of the loop's 40 mm
        pieces.size shouldBe 2
        pieces.all { !it.closed && it.scarfUm == 0L && it.wall == 0 && it.feature == Feature.OUTER_WALL } shouldBe true
        pieces.map { Math.round(length(it)) } shouldBe listOf(5_000L, 15_000L)
        (pieces[0].xy[2] == 5_000L && pieces[1].xy[0] == 5_000L) shouldBe true                     // cut on the box's side
        // the first piece starts where the loop did, by the loop's own travel; the second lies across the cut: retracted
        pieces[0].retractBefore shouldBe false
        pieces[1].retractBefore shouldBe true
        // control: a box round the whole loop changes nothing
        val whole = SectionClip.path(loop, -1_000, 11_000, -1_000, 11_000)
        whole.size shouldBe 1
        whole[0] shouldBeSameInstanceAs loop
        Math.round(length(whole[0])) shouldBe 40_000L
    }
    "a cut line's width is the width where it is cut; a line outside is left out" {
        val cut = SectionClip.path(across, -1_000, 5_000, -1_000, 11_000).single()
        cut.widths!!.toList() shouldBe listOf(400L, 500L)
        cut.xy.toList() shouldBe listOf(1_000L, 5_000L, 5_000L, 5_000L)
        SectionClip.path(beyond, -1_000, 5_000, -1_000, 11_000).size shouldBe 0
    }
    "the path after a cut or left-out one is reached retracted; control: after an uncut one it keeps its own travel" {
        val after = SectionClip.paths(listOf(inside, beyond, inside, across, inside, inside), -1_000, 5_000, -1_000, 11_000)
        // inside (itself), [beyond left out], inside retracted, across cut (its own travel: the path before it was whole), inside retracted, inside (itself)
        after.map { it.retractBefore } shouldBe listOf(false, true, false, true, false)
        after[0] shouldBeSameInstanceAs inside
        after[4] shouldBeSameInstanceAs inside
    }
    "layers topping above the box are left out" {
        val layers = (1..5).map { LayerPlanOut(it - 1, it * 200L, 200, listOf(inside)) }
        SectionClip.layers(layers, -1_000, 5_000, -1_000, 11_000, 600).map { it.topZUm } shouldBe listOf(200L, 400L, 600L)
    }
})
