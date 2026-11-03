package plymeld.core

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import plymeld.mesh.TriangleSoup

/**
 * Footprint.hull on primitive arrays (it ran a 4.7 M-triangle model's plan out of a 3 GB heap with a boxed pair
 * per corner). Checked independently of its construction: every vertex of a random soup lies inside or on the hull, the
 * hull turns left at every vertex, and its vertices are vertices of the soup (to the 1 µm rounding). Control: the hull with
 * one vertex left out leaves a point outside.
 */
class FootprintTest : StringSpec({
    fun inside(h: DoubleArray, x: Double, y: Double): Boolean {
        val m = h.size / 2
        for (i in 0 until m) {
            val ax = h[2 * i]; val ay = h[2 * i + 1]; val bx = h[2 * ((i + 1) % m)]; val by = h[2 * ((i + 1) % m) + 1]
            if ((bx - ax) * (y - ay) - (by - ay) * (x - ax) < -1e-6) return false
        }
        return true
    }

    "a random soup: every vertex inside the hull, the hull convex and made of soup vertices; control: a vertex left out" {
        val rnd = java.util.Random(11)
        val tris = 2000
        val coords = FloatArray(tris * 9) { k -> if (k % 3 == 2) 0f else (rnd.nextGaussian() * 20 + (if (k % 3 == 0) 100 else -40)).toFloat() }
        val soup = TriangleSoup(coords, tris)
        val h = Footprint.hull(soup)
        val m = h.size / 2
        println("hull of ${tris * 3} points: $m vertices")
        (m >= 3) shouldBe true
        val pts = (0 until tris * 3).map { Math.round(coords[3 * it].toDouble() * 1000) / 1000.0 to Math.round(coords[3 * it + 1].toDouble() * 1000) / 1000.0 }
        pts.all { (x, y) -> inside(h, x, y) } shouldBe true
        for (i in 0 until m) {
            val o = i; val a = (i + 1) % m; val b = (i + 2) % m
            ((h[2 * a] - h[2 * o]) * (h[2 * b + 1] - h[2 * o + 1]) - (h[2 * a + 1] - h[2 * o + 1]) * (h[2 * b] - h[2 * o]) > 0) shouldBe true
        }
        val set = pts.toSet()
        (0 until m).all { (h[2 * it] to h[2 * it + 1]) in set } shouldBe true
        // control: drop one hull vertex; that vertex is now outside
        val dropped = h.copyOfRange(2, h.size)
        inside(dropped, h[0], h[1]) shouldBe false
    }
})
