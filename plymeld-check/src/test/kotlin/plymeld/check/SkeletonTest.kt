package plymeld.check

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import plymeld.geom.Delaunay
import plymeld.geom.Polygon
import plymeld.geom.Skeleton
import java.util.Locale
import kotlin.random.Random

/** The skeleton (Arachne's center lines) and the Delaunay triangulation under it, on shapes with known answers. */
class SkeletonTest : StringSpec({
    "Delaunay: no point inside any triangle's circumcircle (300 random points)" {
        val rnd = Random(7)
        val n = 300
        val xs = LongArray(n) { rnd.nextLong(0, 50_000) }; val ys = LongArray(n) { rnd.nextLong(0, 50_000) }
        val dt = Delaunay(xs, ys)
        var tris = 0; var bad = 0
        fun circle(t: Int): DoubleArray {
            val a = dt.v(t, 0); val b = dt.v(t, 1); val c = dt.v(t, 2)
            val ax = dt.px[a].toDouble(); val ay = dt.py[a].toDouble()
            val bx = dt.px[b] - ax; val by = dt.py[b] - ay; val qx = dt.px[c] - ax; val qy = dt.py[c] - ay
            val d = 2 * (bx * qy - by * qx)
            val ux = (qy * (bx * bx + by * by) - by * (qx * qx + qy * qy)) / d; val uy = (bx * (qx * qx + qy * qy) - qx * (bx * bx + by * by)) / d
            return doubleArrayOf(ax + ux, ay + uy, Math.hypot(ux, uy))
        }
        for (t in 0 until dt.triangleCount) {
            if (!dt.isAlive(t) || dt.isSuper(t)) continue
            tris++
            val c = circle(t)
            for (p in 0 until n) {
                if (p == dt.v(t, 0) || p == dt.v(t, 1) || p == dt.v(t, 2)) continue
                if (Math.hypot(xs[p] - c[0], ys[p] - c[1]) < c[2] * (1 - 1e-9)) bad++
            }
        }
        println("Delaunay: $n points, $tris triangles, $bad circumcircle violations")
        (tris > n) shouldBe true               // about 2n - hull
        bad shouldBe 0
    }

    fun rect(x0: Long, y0: Long, x1: Long, y1: Long) = Polygon(longArrayOf(x0, y0, x1, y0, x1, y1, x0, y1))
    fun circle(r: Double, n: Int, cw: Boolean) = Polygon(LongArray(2 * n) { k ->
        val i = if (cw) n - 1 - k / 2 else k / 2
        val t = 2 * Math.PI * i / n
        Math.round(if (k % 2 == 0) r * Math.cos(t) else r * Math.sin(t))
    })

    "a 20 x 1.5 mm band: center line at y 0.75, radius 0.75, about 18.5 mm long" {
        val sk = Skeleton(listOf(rect(0, 0, 20_000, 1_500)), sampleUm = 100, minFeatureUm = 100)
        var len = 0.0; var worstY = 0.0; var worstR = 0.0
        for (k in 0 until sk.edgeCount) {
            val e = sk.edges.copyOfRange(6 * k, 6 * k + 6)
            val mx = (e[0] + e[3]) / 2
            if (mx < 1_000 || mx > 19_000) continue      // the ends branch to the corners (a true part of the medial axis)
            len += Math.hypot(e[3] - e[0], e[4] - e[1])
            worstY = maxOf(worstY, Math.abs(e[1] - 750), Math.abs(e[4] - 750))
            worstR = maxOf(worstR, Math.abs(e[2] - 750), Math.abs(e[5] - 750))
        }
        println("band: %d skeleton edges; center length (x 1..19 mm) %.2f mm, worst y error %.1f um, worst radius error %.1f um".format(Locale.ROOT, sk.edgeCount, len / 1000, worstY, worstR))
        (Math.abs(len - 18_000) < 200) shouldBe true
        (worstY < 5.0) shouldBe true
        (worstR < 5.0) shouldBe true
    }

    "a ring 1.5 mm wide: center circle of radius 10.75 mm, half-thickness 0.75 mm" {
        val sk = Skeleton(listOf(circle(11_500.0, 720, false), circle(10_000.0, 720, true)), sampleUm = 100, minFeatureUm = 100)
        var len = 0.0; var worstPos = 0.0; var worstR = 0.0
        for (k in 0 until sk.edgeCount) {
            val e = sk.edges.copyOfRange(6 * k, 6 * k + 6)
            len += Math.hypot(e[3] - e[0], e[4] - e[1])
            worstPos = maxOf(worstPos, Math.abs(Math.hypot(e[0], e[1]) - 10_750), Math.abs(Math.hypot(e[3], e[4]) - 10_750))
            worstR = maxOf(worstR, Math.abs(e[2] - 750), Math.abs(e[5] - 750))
        }
        println("ring: %d edges, length %.2f mm (2 pi 10.75 = %.2f), worst position error %.1f um, worst radius error %.1f um".format(
            Locale.ROOT, sk.edgeCount, len / 1000, 2 * Math.PI * 10.75, worstPos, worstR))
        (Math.abs(len / 1000 - 2 * Math.PI * 10.75) < 0.5) shouldBe true
        (worstPos < 15.0) shouldBe true
        (worstR < 15.0) shouldBe true
    }
})
