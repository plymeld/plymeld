package plymeld.check

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import plymeld.core.Feature
import plymeld.core.Planner
import plymeld.core.ToolPath
import plymeld.geom.Polygon
import plymeld.geom.Polygons
import plymeld.model.SliceSettings
import java.util.Locale

/**
 * A ring band's solid surface is laid as loops along its outer edge, as many as its mean width (2 x area / perimeter)
 * holds. Where the band is deeper than its mean, the loops do not reach: what lies deeper goes to the hatch. Left empty, a
 * QR code's quiet zone (a band whose jagged inner edge makes its mean width small) had a 1.1 x 2.3 mm hole in its bottom,
 * and a 3DBenchy's internal solid had holes inside its bands. Measured on a square band with a deep bump on one side.
 * Control: the same band without the bump is covered by its loops alone (no hatch added).
 */
class RingBandDepthTest : StringSpec({
    val s = SliceSettings.of(testSetup())

    fun near(p: ToolPath, x: Long, y: Long): Boolean {
        val m = p.xy.size / 2; val r = p.widthUm / 2.0; val segs = if (p.closed) m else m - 1
        for (k in 0 until segs) {
            val j = (k + 1) % m
            val ax = p.xy[2 * k].toDouble(); val ay = p.xy[2 * k + 1].toDouble(); val bx = p.xy[2 * j].toDouble(); val by = p.xy[2 * j + 1].toDouble()
            val dx = bx - ax; val dy = by - ay; val l = dx * dx + dy * dy
            val t = if (l == 0.0) 0.0 else (((x - ax) * dx + (y - ay) * dy) / l).coerceIn(0.0, 1.0)
            if (Math.hypot(x - (ax + t * dx), y - (ay + t * dy)) <= r) return true
        }
        return false
    }

    /** The first layer of a 30 mm square band 3.3 mm wide with a bump [bump] deep, 10 mm long, on its right; and the share laid of the bump's stretch. */
    fun slice(bump: Long): Pair<List<ToolPath>, Double> {
        val side = 30_000L; val band = 3_300L
        val outer = Polygon(longArrayOf(0, 0, side, 0, side, 10_000, side + bump, 10_000, side + bump, 20_000, side, 20_000, side, side, 0, side))
        val hole = Polygon(longArrayOf(band, band, band, side - band, side - band, side - band, side - band, band))
        val tops = LongArray(3) { s.firstLayerHeightUm + it * 200L }
        val l0 = Planner.plan(s, List(3) { listOf(outer, hole) }, tops)[0].paths
        val inside = plymeld.geom.Offset.offset(listOf(outer, hole), -(s.elephantFootUm + 100))
        var n = 0; var miss = 0
        var y = 10_000L
        while (y <= 20_000) { var x = side - band
            while (x <= side + bump) { if (inside.count { Polygons.contains(it, x, y) } % 2 == 1) { n++; if (l0.none { near(it, x, y) }) miss++ }; x += 50 }
            y += 50 }
        return l0 to 1.0 - miss.toDouble() / n
    }

    "a ring band deeper than its mean width is laid to its depth; control: a plain band gets its loops only" {
        val (_, bumped) = slice(4_000)
        val (plain, plainShare) = slice(0)
        val plainOpen = plain.count { it.feature == Feature.BOTTOM && !it.closed }
        println("laid: band with a 4 mm bump %.3f, plain band %.3f (open bottom-surface paths %d)".format(Locale.ROOT, bumped, plainShare, plainOpen))
        (bumped >= 0.99) shouldBe true
        (plainShare >= 0.99) shouldBe true
        plainOpen shouldBe 0
    }
})
