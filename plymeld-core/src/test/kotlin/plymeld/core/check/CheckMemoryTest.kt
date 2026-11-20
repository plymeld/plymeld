package plymeld.core.check

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import plymeld.geom.Polygon
import java.util.Locale

/**
 * What the pre-print check holds while it reads a file (efficiency is tested, not assumed): a 512 MB test JVM checking a
 * 262k-triangle model's file ran its old generation full, with dozens of full collections, until the check's structures
 * were made flat and let go layer by layer.
 *
 * - A grid of segments (ModelCheck.Grid, the check's look-up of a layer's outline, each part's, and its lines) holds its
 *   segments' coordinates and their cells in primitive arrays: under [GRID_BYTES_PER_SEGMENT] bytes a segment retained.
 *   Control: the same segments held as a map of boxed indices and an array a segment (the check's earlier grid) measure
 *   over it, so the measure can fail.
 * - A layer's grids and its cut below are let go once its rules have read it: after the model check, the surface holds
 *   none. Control: the travel rule's look-ups (Surface.at) and the tool rule's part grids made before it are held.
 */
class CheckMemoryTest : StringSpec({
    /** A closed box from (x0, y0, z0) to (x1, y1, z1), mm, as 12 outward triangles. */
    fun box(x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float): plymeld.mesh.TriangleSoup {
        val v = arrayOf(floatArrayOf(x0, y0, z0), floatArrayOf(x1, y0, z0), floatArrayOf(x1, y1, z0), floatArrayOf(x0, y1, z0),
            floatArrayOf(x0, y0, z1), floatArrayOf(x1, y0, z1), floatArrayOf(x1, y1, z1), floatArrayOf(x0, y1, z1))
        val f = intArrayOf(0, 2, 1, 0, 3, 2, 4, 5, 6, 4, 6, 7, 0, 1, 5, 0, 5, 4, 1, 2, 6, 1, 6, 5, 2, 3, 7, 2, 7, 6, 3, 0, 4, 3, 4, 7)
        val out = FloatArray(12 * 9)
        for (t in 0 until 12) for (k in 0 until 3) for (d in 0 until 3) out[9 * t + 3 * k + d] = v[f[3 * t + k]][d]
        return plymeld.mesh.TriangleSoup(out, 12)
    }
    fun f(v: Double) = String.format(Locale.ROOT, "%.3f", v)
    fun retained(make: () -> Any): Long {
        val rt = Runtime.getRuntime()
        System.gc(); System.gc()
        val before = rt.totalMemory() - rt.freeMemory()
        val held = make()
        System.gc(); System.gc()
        val after = rt.totalMemory() - rt.freeMemory()
        java.lang.ref.Reference.reachabilityFence(held)
        return after - before
    }
    /** A polygon of [n] vertices on a circle of radius [r] mm (µm coordinates): [n] segments a few µm to tenths of a mm long. */
    fun circle(n: Int, r: Double) = Polygon(LongArray(2 * n) { i -> val a = 2 * Math.PI * (i / 2) / n; Math.round(1000 * r * (if (i % 2 == 0) Math.cos(a) else Math.sin(a))) })

    "a grid holds its segments in primitive arrays: under the bound a segment retained; control: the boxed form measures over it" {
        val loops = List(4) { k -> listOf(circle(100_000, 20.0 + 10 * k)) }
        val segments = loops.sumOf { l -> l.sumOf { it.size } }.toLong()
        val flat = retained { loops.map { ModelCheck.Grid.ofLoops(it) } }
        println("flat grids: ${flat / segments} bytes a segment retained ($segments segments)")
        flat shouldBeLessThan GRID_BYTES_PER_SEGMENT * segments
        // control: the same segments as a list of arrays and maps of boxed indices by cell and by row
        val boxed = retained {
            loops.map { l ->
                val segs = l.flatMap { p -> (0 until p.size).map { i -> doubleArrayOf(p.x(i) / 1000.0, p.y(i) / 1000.0, p.x((i + 1) % p.size) / 1000.0, p.y((i + 1) % p.size) / 1000.0) } }
                val cells = HashMap<Long, MutableList<Int>>(); val rows = HashMap<Int, MutableList<Int>>()
                for ((i, s) in segs.withIndex()) {
                    val x0 = Math.floor(minOf(s[0], s[2])).toInt(); val x1 = Math.floor(maxOf(s[0], s[2])).toInt()
                    val y0 = Math.floor(minOf(s[1], s[3])).toInt(); val y1 = Math.floor(maxOf(s[1], s[3])).toInt()
                    for (cx in x0..x1) for (cy in y0..y1) cells.getOrPut((cx.toLong() shl 32) or (cy.toLong() and 0xffffffffL)) { ArrayList() } += i
                    for (cy in y0..y1) rows.getOrPut(cy) { ArrayList() } += i
                }
                Triple(segs, cells, rows)
            }
        }
        println("boxed grids: ${boxed / segments} bytes a segment retained")
        boxed shouldBeGreaterThan GRID_BYTES_PER_SEGMENT * segments
    }

    "a grid finds the nearest segment and inside / outside as before: a square's edges and a point in it, one out of it" {
        val sq = listOf(Polygon(longArrayOf(0, 0, 10_000, 0, 10_000, 10_000, 0, 10_000)))
        val g = ModelCheck.Grid.ofLoops(sq)
        g.inside(5.0, 5.0) shouldBe true
        g.inside(15.0, 5.0) shouldBe false
        g.inside(5.0, -0.5) shouldBe false
        (Math.abs(g.nearest(5.0, 1.0, 2.0) - 1.0) < 1e-9) shouldBe true
        (Math.abs(g.nearest(11.5, 5.0, 2.0) - 1.5) < 1e-9) shouldBe true
        // nothing within the radius: none found
        (g.nearest(5.0, 5.0, 1.0) == Double.MAX_VALUE) shouldBe true
        ModelCheck.Grid.ofLoops(emptyList()).let { e -> e.inside(0.0, 0.0) shouldBe false; (e.nearest(0.0, 0.0, 3.0) == Double.MAX_VALUE) shouldBe true }
    }

    "the model check lets each layer's grids and cut below go once its rules have read it; control: held before it" {
        // two boxes side by side, 2 mm tall, on two tools: ten layers of an outer wall loop round each, each by its own tool
        val parts = listOf(box(0f, 0f, 0f, 10f, 10f, 2f), box(10f, 0f, 0f, 20f, 10f, 2f))
        fun loop(x0: Double, y0: Double, x1: Double, y1: Double) = "G1 X${f(x0)} Y${f(y0)} F6000\n" +
            listOf(x1 to y0, x1 to y1, x0 to y1, x0 to y0).joinToString("") { (x, y) -> "G1 X${f(x)} Y${f(y)} E0.40000\n" }
        val text = buildString {
            append("M83\n")
            for (i in 1..10) {
                val z = 0.2 * i
                append("; CHANGE_LAYER\n; Z_HEIGHT: ${f(z)}\n; LAYER_HEIGHT: 0.2\nG1 Z${f(z)} F600\nT0\n; FEATURE: Outer wall\n; LINE_WIDTH: 0.4\n")
                append(loop(0.2, 0.2, 9.8, 9.8))
                append("T1\n; FEATURE: Outer wall\n"); append(loop(10.2, 0.2, 19.8, 9.8))
            }
        }
        val sf = ModelCheck.Surface(parts, 0.0, text)
        sf.layerLines.size shouldBe 10
        // control: the travel rule's look-up on every layer, and one part grid, are held
        for (l in sf.layerLines) sf.at(5.0, 5.0, l.z, l.h)
        sf.partGrid(1, 3)
        sf.held() shouldBeGreaterThan 10
        ModelCheck.checkAll(text, parts, 0.0, surface = sf, partTools = listOf(0, 1))
        sf.held() shouldBe 0
        // ... and made again where asked for after it (the travel rule after a plate's model check)
        sf.at(0.3, 5.0, sf.layerLines[0].z, sf.layerLines[0].h) shouldBe true
        sf.held() shouldBe 1
    }
}) {
    companion object {
        /**
         * The bound on a grid's retained bytes a segment: its four coordinates (32 bytes) and each cell and row it lies in
         * (an int each), with room for the cells' own arrays and the measure's noise. Measured on this test's 400,000 short
         * segments: 49 bytes a segment flat, 94 boxed.
         */
        const val GRID_BYTES_PER_SEGMENT = 64L
    }
}
