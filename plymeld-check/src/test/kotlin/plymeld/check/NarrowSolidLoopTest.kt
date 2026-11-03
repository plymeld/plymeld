package plymeld.check

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import plymeld.core.Feature
import plymeld.core.Planner
import plymeld.geom.Polygon
import plymeld.model.SliceSettings
import java.util.Locale

/**
 * A narrow solid region without holes gets loops along its outline, widths adjusted to fill it
 *. Crossed by the hatch, such a region is mostly line ends, each a wedge of
 * air or a connector laid on the wall (testplate pillars and slot strips: overfill near the surface
 * 15.7 -> 6.7 mm2 at z 4, a deposition check against the model).
 */
class NarrowSolidLoopTest : StringSpec({
    val s = SliceSettings.of(testSetup())
    val n = 20
    val tops = LongArray(n) { 200L * (it + 1) }
    fun bottomSolid(wUm: Long): Triple<List<plymeld.core.ToolPath>, Double, Long> {
        val outline = List(n) { listOf(Polygon(longArrayOf(0, 0, wUm, 0, wUm, 20_000, 0, 20_000))) }
        val layers = Planner.plan(s, outline, tops)
        val region = Planner.walls(s, outline, tops).walls[1].inner.sumOf { it.signedAreaMm2() }
        val paths = layers[1].paths.filter { it.feature == Feature.BOTTOM || it.feature == Feature.SOLID }
        return Triple(paths, region, 200L)
    }
    fun plasticMm2(ps: List<plymeld.core.ToolPath>, h: Long) = ps.sumOf { p ->
        val m = p.xy.size / 2
        val len = (1 until m).sumOf { Math.hypot((p.xy[2 * it] - p.xy[2 * it - 2]).toDouble(), (p.xy[2 * it + 1] - p.xy[2 * it - 1]).toDouble()) } +
            (if (p.closed) Math.hypot((p.xy[0] - p.xy[2 * m - 2]).toDouble(), (p.xy[1] - p.xy[2 * m - 1]).toDouble()) else 0.0)
        len * (p.widthUm - h * (1 - Math.PI / 4)) / 1e6
    }

    "a 3 mm strip: closed loops only, filling the region between the walls" {
        val (paths, region, h) = bottomSolid(3_000)
        val plastic = plasticMm2(paths, h)
        println("3 mm strip: %d paths, %d closed, widths %s; plastic %.2f mm2 (bead area / h) for a %.2f mm2 region".format(Locale.ROOT,
            paths.size, paths.count { it.closed }, paths.map { it.widthUm }.distinct(), plastic, region))
        paths.isNotEmpty() shouldBe true
        paths.all { it.closed } shouldBe true
        (Math.abs(plastic / region - 1) < 0.1) shouldBe true
    }

    "control: a 20 mm square is hatched (open lines), as before" {
        val (paths, _, _) = bottomSolid(20_000)
        println("20 mm square: %d paths, %d closed".format(Locale.ROOT, paths.size, paths.count { it.closed }))
        paths.none { it.closed } shouldBe true
    }
})
