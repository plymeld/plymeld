package plymeld.geom

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.longs.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll

/**
 * Exact predicates are checked against an independent double-precision implementation
 * on coordinates small enough that doubles are exact too (|c| <= 10^4: products <= 10^8),
 * and against BigInteger rationals for the rounded intersection point.
 */
class SegmentsTest : StringSpec({
    val small = Arb.long(-10_000L, 10_000L)
    val pt = arbitrary { IntPoint(small.bind(), small.bind()) }
    // The kernel's stated bound is |c| <= 2^30 (about 1 km at 1 µm): differences then stay
    // below 2^31 and cross products below 2^62. ±2e9 overflowed — see Segments' KDoc.
    val far = Arb.long(-1_073_741_824L, 1_073_741_824L)
    val farPt = arbitrary { IntPoint(far.bind(), far.bind()) }

    "intersects is symmetric in both segments and their endpoints" {
        checkAll(pt, pt, pt, pt) { a, b, c, d ->
            val r = Segments.intersects(a, b, c, d)
            Segments.intersects(c, d, a, b) shouldBe r
            Segments.intersects(b, a, d, c) shouldBe r
        }
    }

    "intersects agrees with an independent double implementation" {
        checkAll(pt, pt, pt, pt) { a, b, c, d ->
            Segments.intersects(a, b, c, d) shouldBe refIntersects(a, b, c, d)
        }
    }

    "properCrossing implies intersects, and excludes shared endpoints" {
        checkAll(pt, pt, pt, pt) { a, b, c, d ->
            if (Segments.properCrossing(a, b, c, d)) {
                Segments.intersects(a, b, c, d) shouldBe true
                (a == c || a == d || b == c || b == d) shouldBe false
            }
        }
    }

    "lineIntersection is within one grid unit of the exact rational point (near and far coordinates)" {
        for (gen in listOf(pt, farPt)) {
            checkAll(200, gen, gen, gen, gen) { a, b, c, d ->
                val denom = (b - a) cross (d - c)
                if (denom != 0L) {
                    val p = Segments.lineIntersection(a, b, c, d)
                    val (ex, ey) = exactIntersection(a, b, c, d)
                    // |p - exact| <= 0.5 in each axis after rounding -> integer distance <= 1
                    Math.abs(p.x - ex.roundHalfAway()) shouldBeLessThanOrEqual 1L
                    Math.abs(p.y - ey.roundHalfAway()) shouldBeLessThanOrEqual 1L
                }
            }
        }
    }

    "control: the double reference disagrees with a deliberately broken predicate" {
        // If the reference were vacuous, this would pass; it must fail for a wrong answer.
        val a = IntPoint(0, 0); val b = IntPoint(10, 0); val c = IntPoint(5, -5); val d = IntPoint(5, 5)
        refIntersects(a, b, c, d) shouldBe true
        refIntersects(a, b, IntPoint(20, -5), IntPoint(20, 5)) shouldBe false
    }
})

/** Independent implementation in doubles (exact for the small coordinate range used). */
private fun refIntersects(a: IntPoint, b: IntPoint, c: IntPoint, d: IntPoint): Boolean {
    fun cross(ox: Double, oy: Double, px: Double, py: Double, qx: Double, qy: Double) = (px - ox) * (qy - oy) - (py - oy) * (qx - ox)
    val ax = a.x.toDouble(); val ay = a.y.toDouble(); val bx = b.x.toDouble(); val by = b.y.toDouble()
    val cx = c.x.toDouble(); val cy = c.y.toDouble(); val dx = d.x.toDouble(); val dy = d.y.toDouble()
    val d1 = cross(ax, ay, bx, by, cx, cy); val d2 = cross(ax, ay, bx, by, dx, dy)
    val d3 = cross(cx, cy, dx, dy, ax, ay); val d4 = cross(cx, cy, dx, dy, bx, by)
    fun inBox(px: Double, py: Double, qx: Double, qy: Double, rx: Double, ry: Double) =
        rx >= minOf(px, qx) && rx <= maxOf(px, qx) && ry >= minOf(py, qy) && ry <= maxOf(py, qy)
    if (((d1 > 0 && d2 < 0) || (d1 < 0 && d2 > 0)) && ((d3 > 0 && d4 < 0) || (d3 < 0 && d4 > 0))) return true
    if (d1 == 0.0 && inBox(ax, ay, bx, by, cx, cy)) return true
    if (d2 == 0.0 && inBox(ax, ay, bx, by, dx, dy)) return true
    if (d3 == 0.0 && inBox(cx, cy, dx, dy, ax, ay)) return true
    if (d4 == 0.0 && inBox(cx, cy, dx, dy, bx, by)) return true
    return false
}

/** Exact rational intersection of the supporting lines, as BigDecimal-free fractions. */
private class Frac(val num: java.math.BigInteger, val den: java.math.BigInteger) {
    fun roundHalfAway(): Long {
        val qr = num.divideAndRemainder(den)
        var q = qr[0]
        if (qr[1].abs().shiftLeft(1) >= den.abs()) q = q.add(java.math.BigInteger.valueOf(if ((num.signum() < 0) != (den.signum() < 0)) -1L else 1L))
        return q.toLong()
    }
}

private fun exactIntersection(a: IntPoint, b: IntPoint, c: IntPoint, d: IntPoint): Pair<Frac, Frac> {
    val big = { v: Long -> java.math.BigInteger.valueOf(v) }
    val rx = big(b.x - a.x); val ry = big(b.y - a.y)
    val sx = big(d.x - c.x); val sy = big(d.y - c.y)
    val denom = rx.multiply(sy).subtract(ry.multiply(sx))
    val qpx = big(c.x - a.x); val qpy = big(c.y - a.y)
    val tNum = qpx.multiply(sy).subtract(qpy.multiply(sx))
    val x = Frac(big(a.x).multiply(denom).add(rx.multiply(tNum)), denom)
    val y = Frac(big(a.y).multiply(denom).add(ry.multiply(tNum)), denom)
    return x to y
}
