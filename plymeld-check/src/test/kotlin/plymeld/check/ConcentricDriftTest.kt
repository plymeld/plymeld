package plymeld.check

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import plymeld.core.SurfaceFill
import plymeld.geom.Polygon
import java.util.Locale

/**
 * Concentric rings must stay at their intended distance from the edge: ring k at spacing * (k + 1/2).
 * A large oval showed coarse, stepped rings with radial "spokes" where Flash Studio's
 * are smooth ellipses; this measures the drift on a dense ellipse like that oval.
 */
class ConcentricDriftTest : StringSpec({
    "rings on a dense ellipse keep their distance from the edge" {
        val n = 4000; val a = 90_000.0; val b = 50_000.0
        val xy = LongArray(2 * n) { k -> val t = 2 * Math.PI * (k / 2) / n; Math.round(if (k % 2 == 0) a * Math.cos(t) else b * Math.sin(t)) }
        val edge = Polygon(xy)
        val spacing = 550L
        val t0 = System.nanoTime()
        val rings = SurfaceFill.concentric(listOf(edge), spacing)
        val ms = (System.nanoTime() - t0) / 1_000_000
        fun distToEdge(x: Long, y: Long): Double {
            var best = Double.MAX_VALUE
            for (i in 0 until n) {
                val ax = edge.x(i).toDouble(); val ay = edge.y(i).toDouble(); val bx = edge.x((i + 1) % n).toDouble(); val by = edge.y((i + 1) % n).toDouble()
                val dx = bx - ax; val dy = by - ay; val l2 = dx * dx + dy * dy
                val u = (((x - ax) * dx + (y - ay) * dy) / l2).coerceIn(0.0, 1.0)
                best = minOf(best, Math.hypot(x - ax - u * dx, y - ay - u * dy))
            }
            return best
        }
        var worst = 0.0; var worstRing = -1
        for (k in rings.indices step 10) {
            val r = rings[k]
            val want = spacing * (k + 0.5)
            for (v in 0 until r.size step maxOf(1, r.size / 200)) {
                val d = Math.abs(distToEdge(r.x(v), r.y(v)) - want)
                if (d > worst) { worst = d; worstRing = k }
            }
        }
        println("concentric on a %d-vertex ellipse: %d rings in %d ms; ring vertex counts %s; worst distance error %.1f um (ring %d)".format(
            Locale.ROOT, n, rings.size, ms, rings.indices.step(20).map { rings[it].size }, worst, worstRing))
        // measured (same ellipse, worst error / time): ring from ring 9817 um; ring from the
        // simplified outline 8.7 um / 7.1 s; ring from ring with round joins 36.6 um / 37.6 s;
        // anchored every 10 (chosen) 8.7 um / 0.9 s
        // 50 mm half-height / 0.55 mm: rings at 0.275 .. 49.775 mm
        rings.size shouldBe 91
        (worst < 20.0) shouldBe true                           // simplification 10 um + grid rounding
        (ms < 5_000) shouldBe true                             // 0.9 s measured; the unsimplified outline took ~30 s per ring
    }
})
