package plymeld.mesh

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.shouldBe

/**
 * The curved band's top surface and warp on a synthetic "pillow": a
 * 40 x 40 x 8 mm block whose top rises 1 mm in the middle, z = 8 + cos(pi x / 40) cos(pi y / 40), so the top
 * meets the vertical sides at 8 everywhere. Expected values worked by hand: the pillow's volume
 * 12800 + (80/pi)^2 = 13448.5 mm3; warped with the band from 0.28 to 9 mm, every column's top lands at 9, so
 * the warped pillow is a 40 x 40 x 9 box, 14400 mm3; layers there are (8 - 0.28) / (9 - 0.28) = 0.8853 times
 * their warped thickness at the edge and 1 at the center.
 */
class CurvedBandTest : StringSpec({
    val n = 40
    fun bump(x: Double, y: Double) = Math.cos(Math.PI * x / 40) * Math.cos(Math.PI * y / 40)
    fun pillow(): TriangleSoup {
        val tris = ArrayList<DoubleArray>()
        fun v(i: Int, j: Int, top: Boolean): DoubleArray { val x = -20.0 + i; val y = -20.0 + j; return doubleArrayOf(x, y, if (top) 8 + bump(x, y) else 0.0) }
        for (j in 0 until n) for (i in 0 until n) {
            tris.add(v(i, j, true) + v(i + 1, j, true) + v(i + 1, j + 1, true))
            tris.add(v(i, j, true) + v(i + 1, j + 1, true) + v(i, j + 1, true))
        }
        // the boundary counter-clockwise seen from above: walls outward, the bottom a fan from its center
        val ring = ArrayList<Pair<Int, Int>>()
        for (i in 0 until n) ring.add(i to 0)
        for (j in 0 until n) ring.add(n to j)
        for (i in n downTo 1) ring.add(i to n)
        for (j in n downTo 1) ring.add(0 to j)
        val center = doubleArrayOf(0.0, 0.0, 0.0)
        for (k in ring.indices) {
            val (pi, pj) = ring[k]; val (qi, qj) = ring[(k + 1) % ring.size]
            tris.add(v(pi, pj, false) + v(qi, qj, false) + v(qi, qj, true))
            tris.add(v(pi, pj, false) + v(qi, qj, true) + v(pi, pj, true))
            tris.add(center + v(qi, qj, false) + v(pi, pj, false))
        }
        val c = FloatArray(tris.size * 9)
        for ((t, tri) in tris.withIndex()) for (k in 0 until 9) c[t * 9 + k] = tri[k].toFloat()
        return TriangleSoup(c, tris.size)
    }
    val soup = pillow()
    val top = TopSurface.of(soup, 0.05)
    val warp = Warp(0.28, 9.0, top::top)

    "the pillow is closed and outward (control for the rest): its volume is 13448.5 mm3 by hand" {
        soup.signedVolume() shouldBe (13448.5 plusOrMinus 2.0)
    }
    "the top: 9 at the center, 8 at the edges and corners, the mesh's own height at a vertex, NaN outside" {
        top.top(0.0, 0.0) shouldBe (9.0 plusOrMinus 1e-6)
        top.top(20.0, 0.0) shouldBe (8.0 plusOrMinus 1e-6)
        top.top(-20.0, -20.0) shouldBe (8.0 plusOrMinus 1e-6)
        top.top(-7.0, 3.0) shouldBe ((8 + bump(-7.0, 3.0)) plusOrMinus 1e-6)
        top.top(20.5, 0.0).isNaN().shouldBeTrue()
        top.top(0.0, -30.0).isNaN().shouldBeTrue()
    }
    "between vertices the top is the mesh's plane, within the tessellation of the analytic bump" {
        top.top(0.5, 0.5) shouldBe ((8 + bump(0.5, 0.5)) plusOrMinus 2e-3)
        top.top(-12.3, 7.7) shouldBe ((8 + bump(-12.3, 7.7)) plusOrMinus 2e-3)
    }
    "the slope: flat at the center, steepest by the edges (atan of the bump's gradient, pi/40 at an edge's middle)" {
        top.slopeDeg(0.2, 0.2) shouldBe (0.0 plusOrMinus 0.3)
        top.slopeDeg(19.5, 0.3) shouldBe (Math.toDegrees(Math.atan(Math.PI / 40)) plusOrMinus 0.3)
    }
    "warp then unwarp gives the point back; below the band and outside the footprint nothing moves" {
        for ((x, y, z) in listOf(Triple(0.0, 0.0, 5.0), Triple(13.0, -4.0, 8.4), Triple(-19.9, 19.9, 0.5), Triple(3.3, 3.3, 9.0))) {
            warp.unwarp(x, y, warp.warp(x, y, z)) shouldBe (z plusOrMinus 1e-9)
        }
        warp.warp(5.0, 5.0, 0.2) shouldBe 0.2
        warp.warp(25.0, 0.0, 6.0) shouldBe 6.0
    }
    "every column's top lands on the band's flat top" {
        for ((x, y) in listOf(0.0 to 0.0, 20.0 to 0.0, -11.0 to 6.5, 17.25 to -3.75)) warp.warp(x, y, top.top(x, y)) shouldBe (9.0 plusOrMinus 1e-6)
    }
    "layer thickness: 1 at the center, 0.8853 at the edge (worked by hand), 1 outside" {
        warp.thicknessFactor(0.0, 0.0) shouldBe (1.0 plusOrMinus 1e-6)
        warp.thicknessFactor(20.0, 0.0) shouldBe ((8 - 0.28) / (9 - 0.28) plusOrMinus 1e-6)
        warp.thicknessFactor(30.0, 0.0) shouldBe 1.0
    }
    "the warped pillow is a 40 x 40 x 9 box: 14400 mm3; control: unwarped it is not (13448.5)" {
        val warped = warp.warpSoup(soup)
        warped.signedVolume() shouldBe (14400.0 plusOrMinus 1.0)
        warped.bounds()[5] shouldBe (9.0 plusOrMinus 1e-5)
        (14400.0 - soup.signedVolume()) shouldBeGreaterThan 900.0
    }
})
