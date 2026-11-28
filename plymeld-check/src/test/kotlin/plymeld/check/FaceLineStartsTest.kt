package plymeld.check

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import plymeld.core.Feature
import plymeld.core.LayerPlanOut
import plymeld.core.Planner
import plymeld.core.ToolPath
import plymeld.geom.Polygon
import plymeld.model.SliceSettings

/**
 * The two experimental rules for open wall lines in a thin face (plan.layer.walls.open_start,
 * plan.layer.walls.thin_skin_after_outer), on a thin face with a slot: two blocks 8 x 10 mm, each with a skin 1.32 mm thick
 * (three 0.44 mm lines: the outer wall's two passes and a center line) running 10 mm from its front toward the other, the
 * skins 4 mm apart (the slot). The front is the small-y side, looked at from the front (plan.view_from, bundled). Each
 * skin's center line has one end at the slot, beside the skin's front corner there (seen) and its back corner (turned
 * away), and the other end where the skin meets its block (no corner).
 * - open_start = hidden_end: no center line with one end within 1 mm of a front slot corner (the reference: the corners as
 *   built, not as the planner finds them) starts there. Control: nearest (unset) starts some there.
 * - thin_skin_after_outer: every skin's center line is laid right after its island's outer wall, stepped onto without a
 *   retraction. Control: off, it is laid before the outer wall, entered by a retracted travel.
 */
class FaceLineStartsTest : StringSpec({
    // µm; the face at y = 30 mm
    val left = Polygon(longArrayOf(30_000, 30_000, 48_000, 30_000, 48_000, 31_320, 38_000, 31_320, 38_000, 40_000, 30_000, 40_000))
    val right = Polygon(longArrayOf(52_000, 30_000, 70_000, 30_000, 70_000, 40_000, 62_000, 40_000, 62_000, 31_320, 52_000, 31_320))
    val seenCorners = listOf(48_000L to 30_000L, 52_000L to 30_000L)
    val n = 12

    fun plan(vararg extra: Pair<String, Any>): List<LayerPlanOut> {
        val s = SliceSettings.of(testSetup("plan.layer.walls.order" to "inner_outer_inner", "plan.layer.walls.style" to "variable_width",
            "plan.layer.walls.thickness" to "0.44 mm", *extra))
        val tops = LongArray(n) { s.firstLayerHeightUm + it * 200L }
        return Planner.plan(s, List(n) { listOf(left, right) }, tops)
    }
    fun d(x: Long, y: Long, c: Pair<Long, Long>) = Math.hypot((x - c.first).toDouble(), (y - c.second).toDouble())
    fun nearSeen(x: Long, y: Long) = seenCorners.any { d(x, y, it) <= 1000.0 }
    /** The skins' center lines: open inner wall lines lying wholly in a skin (between the blocks, y within the skin). */
    fun skinLine(p: ToolPath) = p.feature == Feature.INNER_WALL && !p.closed &&
        (0 until p.xy.size / 2).all { k -> p.xy[2 * k] > 38_200 && p.xy[2 * k] < 61_800 && p.xy[2 * k + 1] in 30_000..31_320 }

    /** Per layer above the first: the center lines with one end within 1 mm of a seen corner, and how many of them start there. */
    fun startsAtSeen(layers: List<LayerPlanOut>): Pair<Int, Int> {
        var lines = 0; var atSeen = 0
        for (l in layers.drop(1)) for (p in l.paths) {
            if (p.feature != Feature.INNER_WALL || p.closed) continue
            val m = p.xy.size
            val a = nearSeen(p.xy[0], p.xy[1]); val b = nearSeen(p.xy[m - 2], p.xy[m - 1])
            if (a == b) continue
            lines++
            if (a) atSeen++
        }
        return lines to atSeen
    }

    "the seen corners: the outline's outside corners not turned away from plan.view_from" {
        val s = SliceSettings.of(testSetup())
        fun seen(view: String) = plymeld.core.WallLineEnds.seenCorners(listOf(left), s.seamCornerDeg, 880.0,
            plymeld.core.decide.Visibility(1.0, 1.0, "test", view = plymeld.core.decide.Visibility.viewOf(view))).map { it.x to it.y }.toSet()
        // the left island's outside corners: front (30, 30) and (48, 30), back (48, 31.32), (38, 40), (30, 40); the inside
        // corner (38, 31.32) is none
        seen("front") shouldBe setOf(30_000L to 30_000L, 48_000L to 30_000L)
        seen("back") shouldBe setOf(48_000L to 31_320L, 38_000L to 40_000L, 30_000L to 40_000L)
        // control: looked at from all around, no corner is turned away
        seen("all_around").size shouldBe 5
    }

    "a thin skin's line: the outer wall within 1.5 widths on both sides all along; a line beside a wider area is none" {
        fun line(vararg xy: Long) = ToolPath(Feature.INNER_WALL, xy, false, 440, 200, wall = 1)
        fun loop(vararg xy: Long) = ToolPath(Feature.OUTER_WALL, xy, true, 440, 200, wall = 0)
        // an outer loop 0.88 mm across (a skin 1.32 mm thick, its outer wall's center line 0.22 mm in) and one 4 mm across
        val thin = loop(30_220, 30_220, 47_780, 30_220, 47_780, 31_100, 30_220, 31_100)
        val wide = loop(30_220, 30_220, 47_780, 30_220, 47_780, 34_220, 30_220, 34_220)
        val center = line(31_000, 30_660, 47_000, 30_660)
        plymeld.core.WallLineEnds.thinSkin(center, thin, 660.0) shouldBe true
        // controls: the far side 3.56 mm away, and a line running out of the skin past the outer wall's end
        plymeld.core.WallLineEnds.thinSkin(center, wide, 660.0) shouldBe false
        plymeld.core.WallLineEnds.thinSkin(line(31_000, 30_660, 49_000, 30_660), thin, 660.0) shouldBe false
    }

    "open_start = hidden_end: a center line with one end at a seen slot corner never starts there; nearest starts some there" {
        val (offLines, offAt) = startsAtSeen(plan())
        val (onLines, onAt) = startsAtSeen(plan("plan.layer.walls.open_start" to "hidden_end"))
        println("nearest: $offAt of $offLines one-seen-end lines start at the seen end; hidden_end: $onAt of $onLines")
        (offLines > 0) shouldBe true          // the case exists
        (offAt > 0) shouldBe true             // control: entered at the nearer end, some start at the seen corner
        (onLines > 0) shouldBe true
        onAt shouldBe 0
    }

    /** Per skin center line on the layers above the first: (what came just before it, its retraction). */
    fun skinSteps(layers: List<LayerPlanOut>): List<Pair<Feature?, Boolean>> = layers.drop(1).flatMap { l ->
        l.paths.withIndex().filter { (_, p) -> skinLine(p) }.map { (k, p) -> l.paths.getOrNull(k - 1)?.feature to p.retractBefore }
    }

    "thin_skin_after_outer: a skin's center line follows its outer wall with no retraction; off, it comes first with one" {
        // seams at corners, inside corners first: each outer wall starts and ends at the inside corner where its skin's back
        // meets its block, beside the skin line's end there (the step onto it is short)
        val off = skinSteps(plan("plan.seam" to "corners"))
        val on = skinSteps(plan("plan.seam" to "corners", "plan.layer.walls.thin_skin_after_outer" to true))
        println("off: $off")
        println("on: $on")
        (off.isNotEmpty() && on.isNotEmpty()) shouldBe true
        // control: off, no skin line follows an outer wall; each is reached by a retracted travel
        off.none { it.first == Feature.OUTER_WALL } shouldBe true
        off.all { it.second } shouldBe true
        on.all { it.first == Feature.OUTER_WALL && !it.second } shouldBe true
    }
})
