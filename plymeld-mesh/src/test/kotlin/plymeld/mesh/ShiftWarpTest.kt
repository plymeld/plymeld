package plymeld.mesh

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe

/**
 * The shifted warp of a curved island (ShiftWarp). A rounded top: a cap 3 mm tall over a disc of radius 6 mm (top 13
 * at the center, 10 at its rim), the band from 6 to 13 mm, the dip the band's top less the cap's top inside the disc, 0
 * outside. Control: the proportional Warp over the same band squeezes a column
 * whose top barely clears the start to nothing; the shifted one never thins a layer below 1 - the largest dip / the band.
 */
class ShiftWarpTest : StringSpec({
    val zs = 6.0; val zTop = 13.0
    fun top(x: Double, y: Double): Double { val r2 = x * x + y * y; return if (r2 >= 36.0) Double.NaN else 10.0 + 3.0 * (1 - r2 / 36.0) }
    val w = ShiftWarp(zs, zTop) { x, y -> top(x, y).let { if (it.isNaN()) 0.0 else zTop - it } }

    "the cap's top lands on the band's flat top; every point round-trips; off the island nothing moves" {
        for (k in 0..30) {
            val x = -5.5 + k * 0.37; val y = 0.25 * k - 3.0
            val t = top(x, y)
            if (t.isNaN()) continue
            (Math.abs(w.warp(x, y, t) - zTop) < 1e-9) shouldBe true
            for (z in listOf(0.0, 5.9, 6.0, 7.3, 9.9, t)) (Math.abs(w.unwarp(x, y, w.warp(x, y, z)) - z) < 1e-9) shouldBe true
        }
        w.warp(10.0, 0.0, 9.0) shouldBe 9.0
        w.unwarp(10.0, 0.0, 9.0) shouldBe 9.0
        // below the start nothing moves anywhere
        w.warp(0.0, 0.0, 5.0) shouldBe 5.0
    }

    "a layer thins by at most the largest dip over the band, never to nothing; the proportional warp squeezes the rim (the control)" {
        val dMax = zTop - 10.0                         // the dip at the cap's rim
        var thinnest = 1.0
        for (k in 0..60) { val x = -6.0 + k * 0.2; thinnest = minOf(thinnest, w.thicknessFactor(x, 0.0)) }
        (thinnest >= 1 - dMax / (zTop - zs) - 1e-9) shouldBe true              // never below 1 - 3/7: 0.571
        (thinnest < 1 - 2.5 / (zTop - zs)) shouldBe true                      // and it does thin near the rim (dip over 2.5 mm there)
        // the proportional warp, the same band, at a column whose top is 0.05 mm above the start
        val prop = Warp(zs, zTop) { _, _ -> zs + 0.05 }
        (prop.thicknessFactor(0.0, 0.0) < 0.01) shouldBe true                // 0.05 / 7
        (thinnest > 0.5) shouldBe true
    }

    // a warp moves vertices: a triangle longer than the warp's feature took, between its corners, a shift the warp does not
    // have there (the Benchy's bow, its walls short of the hull's outline). Split to the feature, it follows it
    "warpSoup splits what lies over the warp's region to its feature: a face's top edge follows the dip; control: by its corners alone it does not" {
        // a vertical face 10 mm long along x, z 0..12, under a dip of 3 sin(pi x / 10) mm (0 at its ends, 3 at its middle)
        val face = floatArrayOf(0f, 0f, 0f, 10f, 0f, 0f, 10f, 0f, 12f, 0f, 0f, 0f, 10f, 0f, 12f, 0f, 0f, 12f)
        val soup = TriangleSoup(face, 2)
        fun dip(x: Double, @Suppress("UNUSED_PARAMETER") y: Double) = 3 * Math.sin(Math.PI * x.coerceIn(0.0, 10.0) / 10)
        fun topAt5(ws: TriangleSoup): Double {        // the warped face's top edge at x 5: the highest z of a vertex there
            var best = Double.NEGATIVE_INFINITY
            for (t in 0 until ws.count) for (c in 0 until 3) if (Math.abs(ws.x(t, c) - 5.0) < 0.13) best = maxOf(best, ws.z(t, c).toDouble())
            return best
        }
        val split = ShiftWarp(6.0, 13.0, 0.25, doubleArrayOf(-1.0, -1.0, 11.0, 1.0)) { x, y -> dip(x, y) }
        val warped = split.warpSoup(soup)
        // the top edge at x 5 warped as the warp has it there: 12 + 3 x (12 - 6) / 7
        topAt5(warped) shouldBe (split.warp(5.0, 0.0, 12.0) plusOrMinus 0.01)
        (warped.count > 100) shouldBe true
        // the control: the same warp by its corners alone (no feature given), a straight edge from end to end, 12 at x 5
        val corners = ShiftWarp(6.0, 13.0) { x, y -> dip(x, y) }.warpSoup(soup)
        corners.count shouldBe 2
        (split.warp(5.0, 0.0, 12.0) - 12.0 > 2.0) shouldBe true
        // a triangle off the region, or all under the start, is left whole
        ShiftWarp(6.0, 13.0, 0.25, doubleArrayOf(20.0, 20.0, 30.0, 30.0)) { x, y -> dip(x, y) }.warpSoup(soup).count shouldBe 2
        ShiftWarp(13.0, 14.0, 0.25, doubleArrayOf(-1.0, -1.0, 11.0, 1.0)) { x, y -> dip(x, y) }.warpSoup(soup).count shouldBe 2
    }
})
