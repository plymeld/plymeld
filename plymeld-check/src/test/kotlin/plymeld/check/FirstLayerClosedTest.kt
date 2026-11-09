package plymeld.check

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import plymeld.core.Planner
import plymeld.core.ToolPath
import plymeld.geom.Polygon
import plymeld.model.SliceSettings
import java.util.Locale

/**
 * The first layer is closed: a region too small for infill, whose walls leave a compact piece in the middle (a small
 * square: its skeleton is a point, so no center line reaches it), gets lines of its own there. On a QR code laid on the
 * bed, every small square's middle was a hole in the visible bottom (283 holes, 60 mm²). Measured by sampling the square:
 * a point counts as laid when a line passes within half its width. Control: the layer above, a hidden layer, keeps its
 * walls only, as before.
 */
class FirstLayerClosedTest : StringSpec({
    val s = SliceSettings.of(testSetup())

    /** The share of the square's sample points (inside it by [inset]) within half a width of a line of [paths]. */
    fun laidShare(paths: List<ToolPath>, side: Long, inset: Long): Double {
        var n = 0; var laid = 0
        var y = inset
        while (y <= side - inset) {
            var x = inset
            while (x <= side - inset) {
                n++
                if (paths.any { p -> near(p, x, y) }) laid++
                x += 50
            }
            y += 50
        }
        return laid.toDouble() / n
    }

    "a 3 mm square's first layer is laid through its middle; control: the same walls without the rule leave it empty" {
        val side = 3_000L
        val sq = Polygon(longArrayOf(0, 0, side, 0, side, side, 0, side))
        val tops = LongArray(3) { s.firstLayerHeightUm + it * 200L }
        val layers = Planner.plan(s, List(3) { listOf(sq) }, tops)
        // inside the first layer's squish compensation (the outline shrunk at the bed) and a little more
        val first = laidShare(layers[0].paths, side, s.elephantFootUm + 100)
        // the first layer's walls as the planner makes them, with and without closing compact pieces
        val shrunk = plymeld.geom.Offset.offset(listOf(sq), -s.elephantFootUm)
        fun wallPaths(close: Boolean): List<ToolPath> {
            val w = plymeld.core.Walls.arachne(shrunk, s.wallLoops, s.firstLayerWidthUm, s.firstLayerWidthUm, s.firstLayerHeightUm,
                s.minBeadUm, s.minFeatureUm, s.minLengthFactor, s.transitionLengthUm, closeCompact = close)
            return w.walls.flatten().map { ToolPath(plymeld.core.Feature.INNER_WALL, it.xy, true, s.firstLayerWidthUm, s.firstLayerHeightUm) } +
                w.center.map { ToolPath(plymeld.core.Feature.INNER_WALL, it.xy, false, it.widths.max(), s.firstLayerHeightUm) }
        }
        val closed = laidShare(wallPaths(true), side, s.elephantFootUm + 100)
        val open = laidShare(wallPaths(false), side, s.elephantFootUm + 100)
        println("laid: first layer %.3f; its walls with the rule %.3f, without %.3f".format(Locale.ROOT, first, closed, open))
        (first >= 0.99) shouldBe true
        (closed >= 0.99) shouldBe true
        (open < 0.95) shouldBe true
    }

    "a 1.3 mm strip's first layer has no slit between its loop's sides; control: the same walls without the rule leave one" {
        // one loop each side leaves a gap thinner than the smallest feature along the middle
        val w = 1_300L; val len = 8_000L
        val strip = Polygon(longArrayOf(0, 0, len, 0, len, w, 0, w))
        val tops = LongArray(3) { s.firstLayerHeightUm + it * 200L }
        val layers = Planner.plan(s, List(3) { listOf(strip) }, tops)
        val inset = s.elephantFootUm + 100
        fun share(paths: List<ToolPath>): Double {
            var n = 0; var laid = 0
            var y = inset
            while (y <= w - inset) { var x = inset
                while (x <= len - inset) { n++; if (paths.any { near(it, x, y) }) laid++; x += 25 }
                y += 25 }
            return laid.toDouble() / n
        }
        val shrunk = plymeld.geom.Offset.offset(listOf(strip), -s.elephantFootUm)
        fun wallPaths(close: Boolean): List<ToolPath> {
            val wl = plymeld.core.Walls.arachne(shrunk, s.wallLoops, s.firstLayerWidthUm, s.firstLayerWidthUm, s.firstLayerHeightUm,
                s.minBeadUm, s.minFeatureUm, s.minLengthFactor, s.transitionLengthUm, closeCompact = close)
            return wl.walls.flatten().map { ToolPath(plymeld.core.Feature.INNER_WALL, it.xy, true, s.firstLayerWidthUm, s.firstLayerHeightUm) } +
                wl.center.map { ToolPath(plymeld.core.Feature.INNER_WALL, it.xy, false, it.widths.max(), s.firstLayerHeightUm) }
        }
        val first = share(layers[0].paths); val closed = share(wallPaths(true)); val open = share(wallPaths(false))
        println("strip laid: first layer %.3f; its walls with the rule %.3f, without %.3f".format(Locale.ROOT, first, closed, open))
        (first >= 0.99) shouldBe true
        (closed >= 0.99) shouldBe true
        (open < 0.95) shouldBe true
    }
})

/** Whether (x, y) lies within half of [p]'s width of one of its segments. */
private fun near(p: ToolPath, x: Long, y: Long): Boolean {
    val m = p.xy.size / 2
    val r = p.widthUm / 2.0
    val segs = if (p.closed) m else m - 1
    for (k in 0 until segs) {
        val j = (k + 1) % m
        val ax = p.xy[2 * k].toDouble(); val ay = p.xy[2 * k + 1].toDouble(); val bx = p.xy[2 * j].toDouble(); val by = p.xy[2 * j + 1].toDouble()
        val dx = bx - ax; val dy = by - ay; val l = dx * dx + dy * dy
        val t = if (l == 0.0) 0.0 else (((x - ax) * dx + (y - ay) * dy) / l).coerceIn(0.0, 1.0)
        if (Math.hypot(x - (ax + t * dx), y - (ay + t * dy)) <= r) return true
    }
    return false
}
