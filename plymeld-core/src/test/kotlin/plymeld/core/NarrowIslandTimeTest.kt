package plymeld.core

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import plymeld.geom.Polygon

/**
 * The time a narrow island's walls take: Walls.arachne on a long strip like a thin blade's cut (55 mm long, 0.3 to 1.5 mm
 * wide along it: one to three lines across, the count changing along it; about 1500 outline samples), as the thread's CPU
 * time per island once warm, each island shifted a micron from the last (the cache by outline would answer a repeat).
 *
 * Read against the same call answered by the cache, which still lays the walls of steps 1 to 3 for the whole outline (what
 * every island costs, narrow or not): the beads and their self-check may take no more than two and a half times that again
 * (the whole call no more than 3.5 times it). A ratio, so a slower machine reads the same. Measured on the development
 * machine: about 31 ms an island against 12.5 ms cached (2.5 times; ten runs read 2.3 to 2.75, the test's 15 islands
 * after 30 to warm up being a noisy reading: 2.1 times over 150 islands after 60); with the construction as one method too
 * large for the virtual machine to compile (it ran interpreted), 51 ms (4.5 times): the control, run and seen to fail.
 * The limit is plymeld's choice between those measurements.
 */
class NarrowIslandTimeTest : StringSpec({
    fun strip(shiftUm: Long): Polygon {
        val n = 750; val len = 55_000.0
        val xy = LongArray(4 * (n + 1))
        for (i in 0..n) {
            val x = len * i / n
            // half-width 0.15 to 0.75 mm: a slow swell with a ripple, so the count rises and falls along it
            val h = 150 + 600 * Math.sin(Math.PI * x / len) * (0.75 + 0.25 * Math.sin(x / 2_500.0))
            xy[2 * i] = Math.round(x) + shiftUm; xy[2 * i + 1] = Math.round(-h)
            xy[4 * (n + 1) - 2 * i - 2] = Math.round(x) + shiftUm; xy[4 * (n + 1) - 2 * i - 1] = Math.round(h)
        }
        return Polygon(xy).let { if (it.isCcw()) it else it.reversed() }
    }
    fun walls(p: Polygon) = Walls.arachne(listOf(p), 3, 440, 440, 100, 300, 150, 2.0, 300, toleranceUm = 10, endOverlap = 0.25)
    val cpu = java.lang.management.ManagementFactory.getThreadMXBean()
    fun timed(f: () -> Unit): Double { val t0 = cpu.currentThreadCpuTime; f(); return (cpu.currentThreadCpuTime - t0) / 1e6 }

    "a long narrow strip's beads and their self-check take no more than 2.5 times its ordinary walls again, once warm" {
        // warm-up: the virtual machine compiles what runs often
        // (60 islands to warm up and 45 read each way: with 30 and 15, one run's ratio swung by 0.3 either side of the
        // same code's steady reading)
        for (i in 0 until 60) walls(strip(1000L + i))
        val laid = (0 until 45).map { i -> timed { val w = walls(strip(2000L + i)); (w.walls.isNotEmpty()) shouldBe true } }.sorted()
        // the same islands again, each answered by the cache (its ordinary walls laid, its beads not)
        val cached = (0 until 45).map { i -> timed { walls(strip(2000L + i)) } }.sorted()
        val m = laid[laid.size / 2]; val c = cached[cached.size / 2]
        println(String.format(java.util.Locale.ROOT, "narrow strip: median %.1f ms an island (%.1f to %.1f); cached %.1f ms; %.1f times", m, laid.first(), laid.last(), c, m / c))
        (c < m) shouldBe true
        (m <= 3.5 * c) shouldBe true
    }
})
