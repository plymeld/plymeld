package plymeld.check

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

import plymeld.check.catalog.CatalogMeasures
import plymeld.check.catalog.Models
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

    /** [paths] as the extruding moves a file would hold, for the catalog's sweep measure (one layer, one tool). */
    fun moves(paths: List<ToolPath>, type: String) = paths.flatMap { p -> (1 until p.xy.size / 2).map { k ->
        TechniqueRender.Move(p.xy[2 * k - 2] / 1000.0, p.xy[2 * k - 1] / 1000.0, 0.0, p.xy[2 * k] / 1000.0, p.xy[2 * k + 1] / 1000.0, 0.0, 1.0,
            type, 0, 0.0, p.widthUm / 1000.0, p.heightUm / 1000.0, 0.0, Double.NaN, null, 0, 0.0) } }
    fun reversed(paths: List<ToolPath>) = CatalogMeasures.sweeps(moves(paths, "Internal solid infill"), listOf("Internal solid infill")).getValue("Internal solid infill")

    /*
     * Internal solid is a monotonic fill too: the slab of the catalog's plates inlaid in a slab's bottom (its pockets empty,
     * one filament) has its internal solid broken into pieces round the pockets on the layers beside them, and each such
     * layer's internal solid is laid in one sweep across its pieces: no line laid against it (CatalogMeasures.sweeps). Control:
     * the same paths ordered nearest-first (internal solid's order before it was a monotonic fill) lay lines against it on
     * those layers. A layer of internal solid over the pockets, its area one piece with no hole, is one sweep too.
     */
    "internal solid broken round holes: each layer one sweep across its pieces (control: nearest-first goes back)" {
        val layers = IncrementalSlicer(listOf(Models.inlaidPlates[0]), "test", "slab").planned(testSetup())
        val broken = layers.filter { l -> l.topZUm - l.heightUm / 2 < Math.round(Models.INLAID_PLATE_MM * 1000) && l.paths.any { it.feature == Feature.SOLID } }
        (broken.isNotEmpty()) shouldBe true
        var nearestAgainst = 0
        for (l in broken) {
            val solid = l.paths.filter { it.feature == Feature.SOLID }
            val r = reversed(solid)
            println("  slab z ${l.topZUm / 1000.0}: internal solid ${solid.size} paths, ${r.lines} lines, ${r.reversed} against the sweep")
            (solid.size > 1) shouldBe true                  // control: the area is broken into pieces
            r.reversed shouldBe 0
            val nearest = Ordering.order(solid, Math.round(Models.CX * 1000), Math.round(Models.CY * 1000))
            nearestAgainst += reversed(nearest).reversed
        }
        println("  nearest-first from the slab's middle: $nearestAgainst lines against the sweep")
        (nearestAgainst > 0) shouldBe true
        // a layer over the pockets: one piece, one sweep too
        val whole = layers.first { l -> l.topZUm - l.heightUm / 2 > Math.round(Models.INLAID_PLATE_MM * 1000) && l.paths.any { it.feature == Feature.SOLID } }
        val w = reversed(whole.paths.filter { it.feature == Feature.SOLID })
        println("  slab z ${whole.topZUm / 1000.0} (over the pockets): internal solid ${whole.paths.count { it.feature == Feature.SOLID }} paths, ${w.lines} lines, ${w.reversed} against the sweep")
        (w.lines > 0) shouldBe true
        w.reversed shouldBe 0
    }
})
