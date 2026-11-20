package plymeld.mesh

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * Slices run side by side (the parallel work of one slice, and several slices in one process: the window's, a plate's,
 * the tests') cut the same and different soups at once: every thread's layers must be exactly the layers each soup gives
 * sliced alone. [SlicerThreads.wrongLayers] slices two faceted cylinders on 8 threads at once, 12 times each, and counts
 * the layers that read otherwise.
 *
 * A slicer that shares a working buffer between threads may still read right once the JIT compiler keeps the buffer's
 * values in registers: the fault shows only in a JVM's first work, and in this JVM, its code compiled, the count below
 * reads 0 with the fault in place. So the threads
 * run in a JVM of their own too, with the slicer's code kept interpreted, as a cold JVM runs it. Control: with the
 * crossing point written to one buffer the slicer object shares, the child JVM counts nearly every one of its 5760
 * layers wrong.
 */
class SlicerThreadsTest : StringSpec({
    "slicing on several threads at once gives every thread the layers each soup gives alone (here, and in a JVM running the slicer interpreted)" {
        SlicerThreads.wrongLayers() shouldBe 0
        val java = ProcessHandle.current().info().command().orElseThrow()
        val p = ProcessBuilder(java, "-XX:CompileCommand=quiet", "-XX:CompileCommand=exclude,plymeld.mesh.Slicer::*",
            "-cp", System.getProperty("java.class.path"), "plymeld.mesh.SlicerThreads").redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor() shouldBe 0
        // the child's answer, its own line (a check that the child ran and counted)
        out.lines().single { it.startsWith("wrong layers: ") } shouldBe "wrong layers: 0"
    }
})

/** The threads' run, for this JVM and a child one ([main]). */
object SlicerThreads {
    /** A cylinder of [n] facets, radius [r] about ([cx], [cy]), from z 0 to 12, its faces outward. */
    private fun cylinder(cx: Double, cy: Double, r: Double, n: Int): TriangleSoup {
        val c = FloatArray(n * 4 * 9)
        var w = 0
        fun put(vararg xyz: Double) { for (v in xyz) c[w++] = v.toFloat() }
        for (i in 0 until n) {
            val a0 = 2 * Math.PI * i / n; val a1 = 2 * Math.PI * (i + 1) / n
            val x0 = cx + r * Math.cos(a0); val y0 = cy + r * Math.sin(a0); val x1 = cx + r * Math.cos(a1); val y1 = cy + r * Math.sin(a1)
            put(x0, y0, 0.0, x1, y1, 0.0, x1, y1, 12.0); put(x0, y0, 0.0, x1, y1, 12.0, x0, y0, 12.0)   // side
            put(cx, cy, 0.0, x1, y1, 0.0, x0, y0, 0.0); put(cx, cy, 12.0, x0, y0, 12.0, x1, y1, 12.0)   // bottom, top
        }
        return TriangleSoup(c, n * 4)
    }

    /**
     * Two cylinders (each plane crossing 720 and 602 side triangles) sliced at 60 heights on 8 threads at once, each thread
     * slicing them in turn 12 times: the layers that differ from each soup's sliced alone. Refused (an exception) where the
     * reference is not a real cut: one loop on every plane, no open chain.
     */
    fun wrongLayers(): Int {
        val soups = listOf(cylinder(0.0, 0.0, 10.0, 360), cylinder(30.0, 5.0, 7.5, 301))
        val zs = DoubleArray(60) { 0.1 + it * 0.2 }
        fun flat(ls: List<Layer>) = ls.map { l -> l.loops.map { it.xy.toList() } to l.openChains.map { it.toList() } }
        val alone = soups.map { flat(Slicer.slice(it, zs)) }
        check(alone.all { a -> a.all { (loops, open) -> loops.size == 1 && open.isEmpty() } }) { "the reference is not one loop a plane" }
        val pool = java.util.concurrent.Executors.newFixedThreadPool(8)
        try {
            val start = java.util.concurrent.CountDownLatch(1)
            val runs = (0 until 8).map { t ->
                pool.submit<Int> {
                    start.await()
                    var wrong = 0
                    repeat(12) { r -> val k = (t + r) % 2; val got = flat(Slicer.slice(soups[k], zs)); wrong += got.indices.count { got[it] != alone[k][it] } }
                    wrong
                }
            }
            start.countDown()
            return runs.sumOf { it.get() }
        } finally { pool.shutdownNow() }
    }

    @JvmStatic fun main(args: Array<String>) { println("wrong layers: ${wrongLayers()}") }
}
