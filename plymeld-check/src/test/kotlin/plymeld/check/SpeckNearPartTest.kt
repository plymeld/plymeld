package plymeld.check

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import plymeld.core.Feature
import plymeld.core.MultiFilament
import plymeld.geom.Polygon
import plymeld.model.SliceSettings

/**
 * An outer-wall speck (a wall line kept only because it lines the outline) of one part, where another part of the object
 * lies right behind it: a gray base whose front face passes a white triangle's tip 0.1 mm in front of it, the gray between
 * them a sliver thinner than a line. A speck there is a dot of gray stopped and restarted on the visible face every layer
 * (on a two-color nameplate, 142 such starts made columns of blobs on the lettering's face): it is left out, the white
 * behind filling the outline there within less than a line. Control: the same base with the triangle a hole (one part
 * alone) keeps its specks, as an outline's specks are kept.
 */
class SpeckNearPartTest : StringSpec({
    // the base 40 x 10 mm, its front face at y 0; the triangle's tip 0.1 mm behind the face, its base 5 mm back and 10 mm wide
    val base = Polygon(longArrayOf(0, 0, 40_000, 0, 40_000, 10_000, 0, 10_000))
    val tri = Polygon(longArrayOf(15_000, 5_000, 20_000, 100, 25_000, 5_000))
    val triHole = Polygon(longArrayOf(15_000, 5_000, 25_000, 5_000, 20_000, 100))
    val n = 12
    val tops = LongArray(n) { 200L * (it + 1) }
    val s = SliceSettings.of(testSetup())

    fun specksNearTip(layers: List<plymeld.core.LayerPlanOut>): Int = (2 until n).sumOf { i ->
        layers[i].paths.count { p -> p.feature == Feature.OUTER_WALL && p.speck && (0 until p.xy.size / 2).all { k -> p.xy[2 * k] in 18_000..22_000 && p.xy[2 * k + 1] < 1_500 } }
    }

    "a speck of one part right in front of another part is left out; control: with the other part a hole, it is kept" {
        val two = MultiFilament.plan(s, listOf(List(n) { listOf(base, triHole) }, List(n) { listOf(tri) }), listOf("spool 1", "spool 2"), listOf(0, 1), tops, "spool 1")
        val inFront = specksNearTip(two.filaments.single { it.spool == "spool 1" }.layers)
        val alone = MultiFilament.plan(s, listOf(List(n) { listOf(base, triHole) }, List(n) { emptyList() }), listOf("spool 1", "spool 2"), listOf(0, 1), tops, "spool 1")
        val bare = specksNearTip(alone.filaments.single { it.spool == "spool 1" }.layers)
        val lens = two.filaments.single { it.spool == "spool 1" }.layers.flatMap { l -> l.paths.filter { it.speck }.map { p -> Math.round(plymeld.core.Planner.polylineLength(p.xy)) to p.widthUm } }.distinct().take(6)
        println("gray specks at the triangle's tip: with the white part $inFront, with a hole there $bare; lengths/widths um $lens")
        (bare > 0) shouldBe true
        inFront shouldBe 0
    }
})
