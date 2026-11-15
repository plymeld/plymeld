package plymeld.check

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import plymeld.core.Walls
import plymeld.geom.Booleans
import plymeld.geom.Polygon
import java.util.Locale

/** Arachne-style walls (Walls.arachne) on shapes with worked answers; classic walls as the control. */
class ArachneWallsTest : StringSpec({
    fun rect(x0: Long, y0: Long, x1: Long, y1: Long) = Polygon(longArrayOf(x0, y0, x1, y0, x1, y1, x0, y1))
    val h = 80L; val wo = 420L; val wi = 450L

    "a 1.5 mm band (too narrow for its four walls): four lines across it, the outer two at the outer width, the inner two sharing the rest; classic crowds two loops" {
        val band = listOf(rect(0, 0, 20_000, 1_500))
        val a = Walls.arachne(band, 4, wo, wi, h, minBeadUm = 340, minFeatureUm = 100, minLengthFactor = 0.5, transitionUm = 400)
        val c = Walls.generate(band, 4, wo, wi, h)
        c.walls.size shouldBe 2                                   // control: classic keeps the crowded second loop
        // worked: the band is laid by its local width (Beads); beads overlap by the flank f = h (1 - pi/4). Three lines: the
        // middle one 1.5 - 2 (wo - f) = 0.694 mm (0.43 off the set width as a log ratio); four: the inner two each
        // (1.5 - f - 2 (wo - f)) / 2 + f = 0.356 mm (0.235 off), at least the narrowest 0.34; five: under it. So four, as two
        // closed loops: the outer at wo / 2 from the outline, the inner at wo - f + 0.356 / 2
        val f = h * (1 - Math.PI / 4)
        val inner = (1_500 - f - 2 * (wo - f)) / 2 + f
        val expect = listOf(wo / 2.0 to wo.toDouble(), wo - f + inner / 2 to inner)
        // two closed loops, laid as wall loops at their places from the outside, each with its widths where they are not its
        // level's set width
        a.inner.isEmpty() shouldBe true
        a.center.isEmpty() shouldBe true
        a.walls.map { it.size } shouldBe listOf(1, 1)
        for (k in 0 until 2) {
            val p = a.walls[k][0]
            val n = p.size
            val ws = a.widthsOf(k, 0)
            // along the band's long sides, away from its ends: its distance from the nearer side and its width
            val along = (0 until n).filter { p.x(it) in 2_000..18_000 }
            val d = along.map { minOf(p.y(it), 1_500 - p.y(it)).toDouble() }
            val w = along.map { (ws?.get(it) ?: (if (k == 0) wo else wi)).toDouble() }
            val (ed, ew) = expect[k]
            println("band 1.5 mm, loop %d: %d points along the sides, %.0f..%.0f um from the side (expected %.0f), %.0f..%.0f um wide (expected %.0f)".format(
                Locale.ROOT, k, along.size, d.min(), d.max(), ed, w.min(), w.max(), ew))
            (along.size >= 4) shouldBe true
            d.all { Math.abs(it - ed) <= 15 } shouldBe true
            w.all { Math.abs(it - ew) <= 15 } shouldBe true
        }
    }

    // the testplate fins (0.5/0.8/1.2/1.6 x 12 mm). With Arachne on, the
    // 0.5 fin printed nothing and the 0.8 fin a stub loop (testplate z 6.4); classic
    // prints all four. Every fin must be printed along its whole length, by loops or a center line.
    for (t in listOf(500L, 800L, 1_200L, 1_600L)) ("a %.1f mm fin is printed along its length".format(Locale.ROOT, t / 1000.0)) {
        val fin = listOf(rect(0, 0, t, 12_000))
        val a = Walls.arachne(fin, 3, wo, wi, 200, minBeadUm = 340, minFeatureUm = 100, minLengthFactor = 0.0, transitionUm = 400)
        var loopY = 0L
        for (w in a.walls) for (p in w) { val ys = (0 until p.size).map { p.y(it) }; loopY = maxOf(loopY, ys.max() - ys.min()) }
        var lineY = 0L
        for (c in a.center) { val ys = (0 until c.xy.size / 2).map { c.xy[2 * it + 1] }; lineY = maxOf(lineY, ys.max() - ys.min()) }
        println("fin %d um: %d loop(s) spanning %d um in y, %d center line(s) spanning %d um".format(Locale.ROOT, t, a.walls.size, loopY, a.center.size, lineY))
        (maxOf(loopY, lineY) > 10_000) shouldBe true
    }

    "a thick block: the same loops as classic, no center lines" {
        val block = listOf(rect(0, 0, 20_000, 10_000))
        val a = Walls.arachne(block, 2, wo, wi, h, minBeadUm = 340, minFeatureUm = 100, minLengthFactor = 0.5, transitionUm = 400)
        val c = Walls.generate(block, 2, wo, wi, h)
        a.walls.size shouldBe 2
        a.center.isEmpty() shouldBe true
        for (k in 0 until 2) {
            val d = Booleans.xor(a.walls[k], c.walls[k]).sumOf { Math.abs(it.signedAreaMm2()) }
            println("block loop $k: arachne vs classic differ by %.4f mm²".format(Locale.ROOT, d))
            (d < 0.001) shouldBe true
        }
        val di = Booleans.xor(a.inner, c.inner).sumOf { Math.abs(it.signedAreaMm2()) }
        println("block infill region: differ by %.4f mm²".format(Locale.ROOT, di))
        (di < 0.05) shouldBe true
    }
})
