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
    val so = Walls.spacing(wo, h); val si = Walls.spacing(wi, h)

    "a 1.5 mm band: one loop, then one center line about 0.68 mm wide down the middle; classic crowds two loops" {
        val band = listOf(rect(0, 0, 20_000, 1_500))
        val a = Walls.arachne(band, 4, wo, wi, h, minBeadUm = 340, minFeatureUm = 100, minLengthFactor = 0.5, transitionUm = 400)
        val c = Walls.generate(band, 4, wo, wi, h)
        // worked: loop 1 needs a band 2 x (wo/2 + (so+si)/2 + si/2) thick
        val need2 = 2 * (wo / 2 + (so + si) / 2 + si / 2)
        println("band 1.5 mm: second loop needs %d um; arachne %d loop(s), classic %d".format(Locale.ROOT, need2, a.walls.size, c.walls.size))
        a.walls.size shouldBe 1
        c.walls.size shouldBe 2                                   // control: classic keeps the crowded second loop
        a.inner.isEmpty() shouldBe true
        // center line(s): along y = 0.75 mm, width = what the outer beads leave: 1.5 - 2 x (wo/2 + so/2)
        val expectW = 1_500 - 2 * (wo / 2 + so / 2)
        var len = 0.0; var worstY = 0L; val mid = ArrayList<Long>()
        for (c in a.center) {
            val xy = c.xy
            for (k in 0 until xy.size / 2) {
                if (xy[2 * k] in 2_000..18_000) worstY = maxOf(worstY, Math.abs(xy[2 * k + 1] - 750))
                if (xy[2 * k] in 5_000..15_000) mid.add(c.widths[k])
            }
            for (k in 1 until xy.size / 2) len += Math.hypot((xy[2 * k] - xy[2 * k - 2]).toDouble(), (xy[2 * k + 1] - xy[2 * k - 1]).toDouble())
        }
        println("center lines: %d path(s), %.1f mm long, middle widths %d..%d (expected ~%d), worst |y - 0.75| %d um".format(
            Locale.ROOT, a.center.size, len / 1000, mid.min(), mid.max(), expectW, worstY))
        a.center.size shouldBe 1                                   // one line: the corner forks pruned, no width splits
        (len > 17_000) shouldBe true
        (worstY <= 10) shouldBe true                              // within 10 um of the middle
        mid.all { Math.abs(it - expectW) <= 20 } shouldBe true
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
