package plymeld.core.decide

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import plymeld.mesh.TriangleSoup

/**
 * [Overhangs.standsOn] finds the highest upward face below a point through a grid of the model's faces by where they lie.
 * It must answer as a scan of every face does: the reference here is that scan, written out on its own, over a field of
 * random faces (up, down and on edge, large and tiny) probed at random points, at the faces' corners and edges, and
 * off the model.
 */
class StandsOnTest : StringSpec({
    /** The highest z below [zTop] of a face facing up whose XY triangle holds (x, y): every face tried. */
    fun scan(p: TriangleSoup, x: Double, y: Double, zTop: Double): Double? {
        val c = p.coords
        var best: Double? = null
        for (i in 0 until p.count) {
            val o = 9 * i
            val ax = c[o].toDouble(); val ay = c[o + 1].toDouble(); val az = c[o + 2].toDouble()
            val bx = c[o + 3].toDouble(); val by = c[o + 4].toDouble(); val bz = c[o + 5].toDouble()
            val cx = c[o + 6].toDouble(); val cy = c[o + 7].toDouble(); val cz = c[o + 8].toDouble()
            if (minOf(az, bz, cz) >= zTop - 1e-9) continue
            val nz = (bx - ax) * (cy - ay) - (by - ay) * (cx - ax)
            if (nz <= 1e-12) continue
            val d1 = (bx - ax) * (y - ay) - (by - ay) * (x - ax)
            val d2 = (cx - bx) * (y - by) - (cy - by) * (x - bx)
            val d3 = (ax - cx) * (y - cy) - (ay - cy) * (x - cx)
            if (d1 < -1e-9 || d2 < -1e-9 || d3 < -1e-9) continue
            val z = d2 / nz * az + d3 / nz * bz + d1 / nz * cz
            if (z < zTop - 1e-6 && (best == null || z > best)) best = z
        }
        return best
    }

    "standsOn answers as a scan of every face does" {
        val rnd = java.util.Random(7)
        val n = 3000
        val c = FloatArray(9 * n)
        for (t in 0 until n) {
            val size = if (t % 10 == 0) 30.0 else if (t % 3 == 0) 0.01 else 2.0   // large, tiny and middling faces
            val x = rnd.nextDouble() * 100 - 20; val y = rnd.nextDouble() * 60 - 10; val z = rnd.nextDouble() * 40
            for (k in 0 until 3) {
                c[9 * t + 3 * k] = (x + rnd.nextDouble() * size).toFloat()
                c[9 * t + 3 * k + 1] = (y + rnd.nextDouble() * size).toFloat()
                c[9 * t + 3 * k + 2] = (z + rnd.nextDouble() * 3).toFloat()
            }
        }
        val p = TriangleSoup(c, n)
        var hits = 0
        var probes = 0
        fun probe(x: Double, y: Double, zTop: Double) {
            val want = scan(p, x, y, zTop)
            Overhangs.standsOn(p, x, y, zTop) shouldBe want
            probes++
            if (want != null) hits++
        }
        repeat(4000) { probe(rnd.nextDouble() * 140 - 30, rnd.nextDouble() * 100 - 30, rnd.nextDouble() * 50) }
        // on the faces' corners and the middles of their edges, where a face's footprint ends
        for (t in 0 until n step 7) for (k in 0 until 3) {
            val o = 9 * t + 3 * k; val o2 = 9 * t + 3 * ((k + 1) % 3)
            probe(c[o].toDouble(), c[o + 1].toDouble(), 50.0)
            probe((c[o] + c[o2]) / 2.0, (c[o + 1] + c[o2 + 1]) / 2.0, 50.0)
        }
        // the control: the probes must hit faces often, and miss off the model
        hits shouldBeGreaterThan probes / 4
        Overhangs.standsOn(p, -1000.0, -1000.0, 50.0) shouldBe null
        Overhangs.standsOn(p, 1000.0, 1000.0, 50.0) shouldBe null
    }

    "a model with no face facing up stands on nothing" {
        val down = TriangleSoup(floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 1f, 0f, 1f), 1)
        Overhangs.standsOn(down, 0.2, 0.2, 5.0) shouldBe null
        val up = TriangleSoup(floatArrayOf(0f, 0f, 1f, 1f, 0f, 1f, 0f, 1f, 1f), 1)
        Overhangs.standsOn(up, 0.2, 0.2, 5.0) shouldBe 1.0
    }
})
