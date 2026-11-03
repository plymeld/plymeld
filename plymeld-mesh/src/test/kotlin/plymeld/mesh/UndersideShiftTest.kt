package plymeld.mesh

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe

/**
 * Layers that follow the underside on a ring arch: the ring between two
 * circles about one center, radii r and r + 3, the bed cutting it 0.873 mm below the inner circle's top (a 4 deg arc over
 * 50 mm: r = 25 / sin 4 deg = 358.39, the center d = r cos 4 deg = 357.52 below the bed), 5 mm wide. By hand: the underside
 * is the inner circle, sqrt(r^2 - x^2) - d, for |x| < 25 (0.873 at the middle), and the bed (0) out to the outer circle's
 * ends (|x| < 52.77); the shift moves each column down by it, so the arch's top at the middle, 3.873, lands at 3.000.
 */
class UndersideShiftTest : StringSpec({
    val r = 25.0 / Math.sin(Math.toRadians(4.0)); val d = r * Math.cos(Math.toRadians(4.0)); val ro = r + 3.0
    val halfOut = Math.sqrt(ro * ro - d * d)
    fun inner(x: Double) = if (Math.abs(x) >= 25.0) 0.0 else Math.sqrt(r * r - x * x) - d
    fun outer(x: Double) = Math.sqrt(ro * ro - x * x) - d

    /** The arch as triangles: its profile in X-Z (outer arc above, inner arc or bed below) swept 5 mm along Y. */
    fun arch(): TriangleSoup {
        val n = 400
        val xs = DoubleArray(n + 1) { -halfOut + 2 * halfOut * it / n }
        val tris = ArrayList<DoubleArray>()
        fun p(x: Double, y: Double, z: Double) = doubleArrayOf(x, y, z)
        for (i in 0 until n) {
            val a = xs[i]; val b = xs[i + 1]
            val ta = maxOf(outer(a), 0.0); val tb = maxOf(outer(b), 0.0); val ba = inner(a); val bb = inner(b)
            // top (up), bottom (down), and the two side faces at y = -2.5 and +2.5
            tris += p(a, -2.5, ta) + p(b, -2.5, tb) + p(b, 2.5, tb); tris += p(a, -2.5, ta) + p(b, 2.5, tb) + p(a, 2.5, ta)
            tris += p(a, -2.5, ba) + p(b, 2.5, bb) + p(b, -2.5, bb); tris += p(a, -2.5, ba) + p(a, 2.5, ba) + p(b, 2.5, bb)
            tris += p(a, -2.5, ba) + p(b, -2.5, bb) + p(b, -2.5, tb); tris += p(a, -2.5, ba) + p(b, -2.5, tb) + p(a, -2.5, ta)
            tris += p(a, 2.5, ba) + p(b, 2.5, tb) + p(b, 2.5, bb); tris += p(a, 2.5, ba) + p(a, 2.5, ta) + p(b, 2.5, tb)
        }
        val c = FloatArray(tris.size * 9)
        for ((t, tri) in tris.withIndex()) for (k in 0 until 9) c[t * 9 + k] = tri[k].toFloat()
        return TriangleSoup(c, tris.size)
    }
    val soup = arch()
    val under = TopSurface.underside(soup, 1e-3)
    val shift = UndersideShift(0.0, under::top)

    "the underside: the inner arc over the hollow (0.873 at the middle), the bed beyond it, NaN outside the arch" {
        under.top(0.0, 0.0) shouldBe (0.873 plusOrMinus 0.002)
        under.top(12.0, 1.0) shouldBe (inner(12.0) plusOrMinus 0.002)
        under.top(40.0, 0.0) shouldBe (0.0 plusOrMinus 1e-6)
        under.top(60.0, 0.0).isNaN().shouldBeTrue()
    }
    "the shift: the middle's top 3.873 lands at 3.000, a point on the bed stays, the warp round-trips, the thickness is kept" {
        shift.warp(0.0, 0.0, outer(0.0)) shouldBe (3.0 plusOrMinus 0.002)
        shift.warp(40.0, 0.0, 1.5) shouldBe 1.5
        shift.unwarp(7.0, 1.0, shift.warp(7.0, 1.0, 2.2)) shouldBe (2.2 plusOrMinus 1e-12)
        shift.thicknessFactor(0.0, 0.0) shouldBe 1.0
    }
    "control: the top-following warp of the same arch does not keep the thickness (thinner where the top is lower)" {
        val top = TopSurface.of(soup, 1e-3)
        val w = Warp(0.28, outer(0.0), top::top)
        (w.thicknessFactor(20.0, 0.0) < 0.99).shouldBeTrue()
    }
    // two circles 3 mm apart radially are 3 mm apart vertically only at the crown, 3 / cos(4 deg) = 3.0073 at the hollow's
    // edge (x = 25, the inner arc at the bed there): the warped arch is highest there
    "the warped arch: its underside flat on the bed (lowest point 0), at most outer(25) = 3.0073 high, at the hollow's edge" {
        val b = shift.warpSoup(soup).bounds()
        b[2] shouldBe (0.0 plusOrMinus 1e-4)
        b[5] shouldBe (outer(25.0) plusOrMinus 0.001)
        outer(25.0) shouldBe (3.0 / Math.cos(Math.toRadians(4.0)) plusOrMinus 0.001)
    }
})
