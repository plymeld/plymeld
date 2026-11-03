package plymeld.check

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import plymeld.core.SurfaceFill
import plymeld.geom.Polygon

private fun rect(x0: Double, y0: Double, x1: Double, y1: Double, hole: Boolean = false): Polygon {
    fun u(v: Double) = Math.round(v * 1000)
    val ccw = longArrayOf(u(x0), u(y0), u(x1), u(y0), u(x1), u(y1), u(x0), u(y1))
    return if (!hole) Polygon(ccw) else Polygon(longArrayOf(u(x0), u(y0), u(x0), u(y1), u(x1), u(y1), u(x1), u(y0)))
}

private fun lengthMm(xy: LongArray, closed: Boolean): Double {
    val m = xy.size / 2; val segs = if (closed) m else m - 1
    return (0 until segs).sumOf { k -> val j = (k + 1) % m; Math.hypot((xy[2 * j] - xy[2 * k]).toDouble(), (xy[2 * j + 1] - xy[2 * k + 1]).toDouble()) } / 1000
}

/** Concentric and Archimedean-chords fills (patterns a 3MF project can set per part), checked against plain geometry. */
class SurfaceFillTest : StringSpec({
    "concentric: 10 loops in a 20 x 10 rectangle at 0.5 mm, covering its area" {
        val loops = SurfaceFill.concentric(listOf(rect(0.0, 0.0, 20.0, 10.0)), 500)
        loops.size shouldBe 10                                              // insets 0.25, 0.75 ... 4.75 of a 5 mm half-height
        (loops.sumOf { lengthMm(it.xy, true) } * 0.5) shouldBe (200.0 plusOrMinus 200.0 * 0.05)
        for (lp in loops) for (k in 0 until lp.size) { (lp.x(k) in 0..20_000) shouldBe true; (lp.y(k) in 0..10_000) shouldBe true }
    }

    "archimedean chords: covers a square with a hole, stays inside it, and leaves the hole empty" {
        val region = listOf(rect(0.0, 0.0, 20.0, 20.0), rect(7.0, 7.0, 13.0, 13.0, hole = true))
        val lines = SurfaceFill.archimedeanChords(region, 400, 800)
        (lines.sumOf { lengthMm(it, false) } * 0.4) shouldBe (364.0 plusOrMinus 364.0 * 0.05)
        var inHole = 0; var outside = 0
        for (pl in lines) for (k in 0 until pl.size / 2) {
            val x = pl[2 * k]; val y = pl[2 * k + 1]
            if (x < -1 || x > 20_001 || y < -1 || y > 20_001) outside++
            if (x in 7_001..12_999 && y in 7_001..12_999) inHole++
        }
        outside shouldBe 0
        inHole shouldBe 0
        // control: without the hole the same spiral does go through the middle
        val full = SurfaceFill.archimedeanChords(listOf(rect(0.0, 0.0, 20.0, 20.0)), 400, 800)
        val middle = full.sumOf { pl -> (0 until pl.size / 2).count { k -> pl[2 * k] in 7_001..12_999 && pl[2 * k + 1] in 7_001..12_999 } }
        (middle > 0) shouldBe true
    }

    // concentric rings centered around the center, not the nearest hole
    "concentric, cut: rings follow the OUTER edge only, cut at an off-center hole; control: plain concentric wraps the hole" {
        // the hole off the 0.5 mm grid, so rings wrapped around it cannot land on the outer-edge levels by chance
        val region = listOf(rect(0.0, 0.0, 40.0, 40.0), rect(24.1, 16.1, 30.1, 22.1, hole = true))
        /** How far (mm) a point is off the nearest ring level: rings sit at 0.25, 0.75, ... mm from the square's edge. */
        fun offLevel(x: Long, y: Long): Double {
            val d = minOf(x, 40_000 - x, y, 40_000 - y) / 1000.0
            val k = Math.round((d - 0.25) / 0.5)
            return Math.abs(d - (0.25 + 0.5 * k))
        }
        val pieces = SurfaceFill.concentricCut(region, 500, 800)
        var worst = 0.0; var nearHole = 0
        for (pc in pieces) for (k in 0 until pc.xy.size / 2) {
            val x = pc.xy[2 * k]; val y = pc.xy[2 * k + 1]
            worst = maxOf(worst, offLevel(x, y))
            // outside the hole grown by half a line (round corners: at least 0.25 / sqrt 2 in each axis)
            val dx = maxOf(24_100 - x, x - 30_100, 0); val dy = maxOf(16_100 - y, y - 22_100, 0)
            if (maxOf(dx, dy) < 170) nearHole++
        }
        worst shouldBe (0.0 plusOrMinus 0.015)                             // every point on an outer-edge ring
        nearHole shouldBe 0
        (pieces.count { !it.closed } > 0) shouldBe true                   // some rings are cut
        (pieces.sumOf { lengthMm(it.xy, it.closed) } * 0.5) shouldBe (1564.0 plusOrMinus 1564.0 * 0.06)   // 1600 - 36 mm2
        // control: offsetting the region with its hole bends rings around the hole, off the outer-edge levels
        val wrapped = SurfaceFill.concentric(region, 500)
        val off = wrapped.maxOf { lp -> (0 until lp.size).maxOf { k -> offLevel(lp.x(k), lp.y(k)) } }
        (off > 0.05) shouldBe true
    }

    // circles around a common middle; toward the oval's ends there is no room for full circles, so arcs are laid
    "circles: round rings around the oval's middle, full in the middle and arcs at the ends, none in a hole; control: concentric's loops are not round" {
        // an oval 150 x 75 mm (an ellipse of 360 vertices) around (100, 100), a 10 mm square hole off its middle
        val oval = Polygon(LongArray(720) { k -> val a = 2 * Math.PI * (k / 2) / 360; if (k % 2 == 0) Math.round(100_000 + 75_000 * Math.cos(a)) else Math.round(100_000 + 37_500 * Math.sin(a)) })
        val hole = rect(130.0, 95.0, 140.0, 105.0, hole = true)
        val region = listOf(oval, hole)
        val s = 500L
        val pieces = SurfaceFill.circles(region, s, 800)
        /** How far (mm) a point is off the nearest ring: rings at 0.25, 0.75, ... mm from (100, 100). */
        fun offRing(x: Long, y: Long): Double { val r = Math.hypot(x - 100_000.0, y - 100_000.0) / 1000.0; return Math.abs(r - (0.25 + 0.5 * Math.round((r - 0.25) / 0.5))) }
        var worst = 0.0; var inHole = 0
        for (pl in pieces) for (k in 0 until pl.size / 2) {
            worst = maxOf(worst, offRing(pl[2 * k], pl[2 * k + 1]))
            if (pl[2 * k] in 130_200..139_800 && pl[2 * k + 1] in 95_200..104_800) inHole++
        }
        val full = pieces.count { pl -> pl[0] == pl[pl.size - 2] && pl[1] == pl[pl.size - 1] }
        println("circles: ${pieces.size} pieces, $full full circles, worst off a ring %.4f mm".format(java.util.Locale.ROOT, worst))
        worst shouldBe (0.0 plusOrMinus 0.015)                           // every point on a circle around the middle
        inHole shouldBe 0
        (full > 50) shouldBe true                                        // whole circles where they fit (radius < 37.5 mm)
        (pieces.size > full) shouldBe true                               // and arcs where they do not
        // no arc ends where another starts: a ring cut by the hole is one arc through its start, not two meeting there
        val starts = pieces.map { it[0] to it[1] }.toSet()
        val joins = pieces.count { pl -> pl.size >= 4 && !(pl[0] == pl[pl.size - 2] && pl[1] == pl[pl.size - 1]) && (pl[pl.size - 2] to pl[pl.size - 1]) in starts }
        println("arcs ending where another starts: $joins")
        joins shouldBe 0
        val area = Math.PI * 75 * 37.5 - 100
        (pieces.sumOf { lengthMm(it, false) } * 0.5) shouldBe (area plusOrMinus area * 0.04)
        // control: concentric loops follow the outline, off the circles
        val loops = SurfaceFill.concentric(listOf(oval), s)
        (loops.maxOf { lp -> (0 until lp.size).maxOf { k -> offRing(lp.x(k), lp.y(k)) } } > 0.05) shouldBe true
    }
})
