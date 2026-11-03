package plymeld.check

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import plymeld.geom.Offset
import plymeld.geom.Polygon
import java.util.Locale

/**
 * Offsetting an offset must keep its shape.
 * Repeated small inward offsets of a dense ellipse must stay at the summed distance from the
 * original edge, like one offset by the sum. The failure it guards: 20 x 0.55 mm taking the
 * 4000-vertex ellipse to 465 vertices, and 60 steps ending 9.8 mm off (ConcentricDriftTest).
 */
class OffsetRepeatTest : StringSpec({
    val n = 4000; val a = 90_000.0; val b = 50_000.0
    val edge = Polygon(LongArray(2 * n) { k -> val t = 2 * Math.PI * (k / 2) / n; Math.round(if (k % 2 == 0) a * Math.cos(t) else b * Math.sin(t)) })
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
    fun worstError(p: Polygon, d: Double) = (0 until p.size step maxOf(1, p.size / 300)).maxOf { Math.abs(distToEdge(p.x(it), p.y(it)) - d) }

    "60 repeated inward offsets of 0.55 mm stay at the summed distance, and stay fast" {
        val step = 550L
        var ring = listOf(edge)
        val t0 = System.nanoTime()
        var worst = 0.0; var at = 0
        val counts = ArrayList<Int>()
        for (k in 1..60) {
            ring = Offset.offset(ring, -step)
            ring.size shouldBe 1
            if (k % 10 == 0) {
                counts.add(ring[0].size)
                val e = worstError(ring[0], step * k.toDouble())
                if (e > worst) { worst = e; at = k }
            }
        }
        val ms = (System.nanoTime() - t0) / 1_000_000
        println("60 x -0.55 mm on a %d-vertex ellipse: %d ms, vertex counts every 10 steps %s, worst error %.1f um (step %d)".format(Locale.ROOT, n, ms, counts, worst, at))
        // measured after the fix 26.7 um: the 1 um jitter tolerance trims inside a convex curve,
        // under 1 um per offset; before it 9.3 mm
        (worst < 60.0) shouldBe true
        (ms < 5_000) shouldBe true
    }

    "one big offset is exact and fast" {
        val t0 = System.nanoTime()
        val big = Offset.offset(listOf(edge), -30_000)
        val ms = (System.nanoTime() - t0) / 1_000_000
        val e = worstError(big.single(), 30_000.0)
        println("one -30 mm offset: %d ms, %d vertices, worst error %.1f um".format(Locale.ROOT, ms, big.single().size, e))
        (e < 5.0) shouldBe true
        (ms < 1_000) shouldBe true                  // about 4 ms; the slow path it guards against took 24 s
    }
})
