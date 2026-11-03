package plymeld.check

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

import plymeld.core.Feature
import plymeld.core.IncrementalSlicer
import plymeld.core.Ordering
import plymeld.core.ToolPath
import plymeld.mesh.StlReader

/**
 * Monotonic surfaces: a top surface that cannot be one monotonic sweep (holes split it) is still
 * printed as close to one as possible: it starts at an END of the sweep, and no block is printed
 * after a block that lies wholly AHEAD of it along the sweep. Blocks side by side (overlapping in
 * sweep position, e.g. the two sides of a hole) may go in either order.
 */
class MonotonicOrderTest : StringSpec({
    fun blocks(paths: List<ToolPath>): List<DoubleArray> {
        // sweep axis: normal to the longest segment
        var best = 0.0; var ux = 1.0; var uy = 0.0
        for (p in paths) for (k in 1 until p.xy.size / 2) {
            val dx = (p.xy[2 * k] - p.xy[2 * k - 2]).toDouble(); val dy = (p.xy[2 * k + 1] - p.xy[2 * k - 1]).toDouble()
            val l = Math.hypot(dx, dy); if (l > best) { best = l; ux = dx / l; uy = dy / l }
        }
        return paths.map { p -> val a = (0 until p.xy.size / 2).map { k -> -uy * p.xy[2 * k] + ux * p.xy[2 * k + 1] }; doubleArrayOf(a.min(), a.max()) }
    }
    /** Pairs (earlier, later) where the later block lies wholly behind the earlier one, in the sweep's own sense. */
    fun backtracks(b: List<DoubleArray>): Int {
        val ascending = b.first()[0] <= b.last()[0]
        var n = 0
        for (i in b.indices) for (j in i + 1 until b.size) {
            val behind = if (ascending) b[j][1] < b[i][0] else b[j][0] > b[i][1]
            if (behind) n++
        }
        return n
    }

    "testplate top at z 6.0: starts at an end of the sweep and never goes back (control: nearest-first does)" {
        val layers = IncrementalSlicer(listOf(StlReader.read(testModelsDir().resolve("testplate.stl"))), "test", "testplate").planned(testSetup())
        val l = layers.single { it.topZUm == 6_000L }
        // the large plate only (x < 127 mm, y > 144 mm): other islands are separate surfaces
        val top = l.paths.filter { p -> p.feature == Feature.TOP && (0 until p.xy.size / 2).all { k -> p.xy[2 * k] < 127_000 && p.xy[2 * k + 1] > 144_000 } }
        val b = blocks(top)
        println("  testplate z 6.0 top: ${top.size} blocks, ${backtracks(b)} backtracks")
        (top.size > 1) shouldBe true                        // control: the surface is split at all
        val lo = b.minOf { it[0] }; val hi = b.maxOf { it[1] }
        (b.first()[0] == lo || b.first()[1] == hi) shouldBe true
        backtracks(b) shouldBe 0
        // control: the same blocks ordered nearest-first from the middle of the plate go back
        val nearest = Ordering.order(top, 107_000, 158_000, improvePass = false)
        (backtracks(blocks(nearest)) > 0) shouldBe true
    }
})
