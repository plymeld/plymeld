package plymeld.check

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import plymeld.core.Feature
import plymeld.core.IncrementalSlicer
import plymeld.core.Planner
import plymeld.geom.Polygon
import plymeld.mesh.StlReader
import plymeld.model.SliceSettings

/**
 * The order of the walls.
 * - Contour by contour: in a layer with a hole, each contour's inner walls and outer wall are finished before the next
 *   contour's. With inner walls first (all of them, or all but the one beside the outer wall), two outer walls in a row
 *   means the order went all-inner-then-all-outer, which crosses between contours twice per layer, each time with a
 *   retraction.
 * - Within a contour (plan.layer.walls.order), read from the planned layer as the walls' numbers in the order laid (0 the
 *   outer wall, 1 the wall beside it, ...): a 20 mm square with four walls. outer_first lays 0 first and goes inward;
 *   inner_first lays the innermost first and 0 last; inner_outer_inner lays the walls past 1 first, then 0, then 1.
 *   Controls: with two walls (none past the one beside the outer wall), and where the outer wall steps out past the layer
 *   below by more than the overhang slow-down's start, inner_outer_inner lays inner first.
 */
class WallOrderTest : StringSpec({
    for (order in listOf("inner_first", "inner_outer_inner")) "hole block, $order: walls go contour by contour (never two outer walls in a row)" {
        val layers = IncrementalSlicer(listOf(StlReader.read(testModelsDir().resolve("holeblock.stl"))), "test", "hole").planned(testSetup("plan.layer.walls.order" to order))
        var withHole = 0
        val bad = ArrayList<Int>()
        for (l in layers) {
            val walls = l.paths.filter { it.feature == Feature.OUTER_WALL || it.feature == Feature.INNER_WALL }
            val outers = walls.count { it.feature == Feature.OUTER_WALL }
            if (outers < 2 || walls.none { it.feature == Feature.INNER_WALL }) continue
            withHole++
            if (walls.zipWithNext().any { (a, b) -> a.feature == Feature.OUTER_WALL && b.feature == Feature.OUTER_WALL }) bad.add(l.index)
        }
        println("  hole block, $order: $withHole layers with 2+ contours; back-to-back outer walls on ${bad.size}")
        (withHole > 0) shouldBe true     // control: the check sees layers with a hole at all
        bad shouldBe emptyList()
    }

    fun square(x0: Long, y0: Long, side: Long) = Polygon(longArrayOf(x0, y0, x0 + side, y0, x0 + side, y0 + side, x0, y0 + side))
    /** The walls' numbers on layer [at], in the order laid, for [order] with [walls] lines' thickness; [stepOut]: that layer 2 mm wider all round. */
    fun laid(order: String, walls: Int = 4, at: Int = 5, stepOut: Boolean = false): List<Int> {
        val s = SliceSettings.of(testSetup("plan.layer.walls.order" to order, "plan.layer.walls.style" to "fixed_width",
            "plan.layer.walls.thickness" to "= $walls * plan.layer.walls.inner.line.width"))
        val n = 10
        val tops = LongArray(n) { s.firstLayerHeightUm + it * 200L }
        val layers = Planner.plan(s, List(n) { i -> listOf(if (stepOut && i == at) square(28_000, 28_000, 24_000) else square(30_000, 30_000, 20_000)) }, tops)
        return layers[at].paths.filter { it.feature == Feature.OUTER_WALL || it.feature == Feature.INNER_WALL }.map { it.wall }
    }

    "outer_first: the outer wall, then inward" {
        val w = laid("outer_first")
        println("outer_first: $w")
        w shouldBe w.sorted()
        w.first() shouldBe 0
    }
    "inner_first: the innermost first, the outer wall last" {
        val w = laid("inner_first")
        println("inner_first: $w")
        w.last() shouldBe 0
        w[w.size - 2] shouldBe 1
    }
    "inner_outer_inner: the walls past 1 first, then the outer wall, then 1" {
        val w = laid("inner_outer_inner")
        println("inner_outer_inner: $w")
        (w.size >= 3) shouldBe true
        w.last() shouldBe 1
        w[w.size - 2] shouldBe 0
        w.dropLast(2).all { it >= 2 } shouldBe true
    }
    "inner_outer_inner: the wall beside the outer one starts nearest where the outer wall ended and is stepped onto without a retraction" {
        val s = SliceSettings.of(testSetup("plan.layer.walls.order" to "inner_outer_inner", "plan.layer.walls.style" to "fixed_width",
            "plan.layer.walls.thickness" to "= 4 * plan.layer.walls.inner.line.width", "plan.mechanism.motion.no_retract_in_island" to false,
            // a scarf seam: the outer wall ends 10 mm along from its seam (its ramp's end), away from the wall beside it's own
            // seam, so a start left at that seam is caught
            "plan.technique.scarf_seam.enabled" to true, "plan.technique.scarf_seam.where" to "all", "plan.technique.scarf_seam.length" to "10 mm"))
        val n = 10
        val tops = LongArray(n) { s.firstLayerHeightUm + it * 200L }
        val layer = Planner.plan(s, List(n) { listOf(square(30_000, 30_000, 20_000)) }, tops)[5]
        val walls = layer.paths.filter { it.feature == Feature.OUTER_WALL || it.feature == Feature.INNER_WALL }
        val k = walls.indexOfFirst { it.wall == 0 }
        val outer = walls[k]; val beside = walls[k + 1]
        val end = plymeld.core.Scarf.endOf(outer)
        val step = Math.hypot((beside.xy[0] - end[0]).toDouble(), (beside.xy[1] - end[1]).toDouble()) / 1000.0
        println("from the outer wall's end (${end[0]}, ${end[1]}) to the wall beside it: %.2f mm, retract ${beside.retractBefore}".format(java.util.Locale.ROOT, step))
        beside.wall shouldBe 1
        // its loop lies a line inside the outer wall: started nearest, the step is about a line, under two
        (step < 2 * 0.44) shouldBe true
        beside.retractBefore shouldBe false
    }
    "controls: with two walls, and where the outer wall steps out past the layer below, inner_outer_inner lays inner first" {
        val two = laid("inner_outer_inner", walls = 2)
        val over = laid("inner_outer_inner", stepOut = true)
        println("two walls: $two; stepped out: $over")
        two shouldBe listOf(1, 0)
        over.last() shouldBe 0
        over[over.size - 2] shouldBe 1
    }
})
