package plymeld.core

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * Path order vs where the layer starts. Greedy nearest-first commits early, so the
 * start point shapes the whole order. The 2-opt pass after it must (a) never lengthen travel and
 * (b) leave travel less dependent on the start: a smaller spread over start points.
 */
class OrderingTest : StringSpec({
    // 60 open lines, 3-10 mm long, scattered over 50 x 50 mm (fixed seed)
    val rnd = java.util.Random(20260929)
    val paths = List(60) {
        val x = rnd.nextInt(50_000).toLong(); val y = rnd.nextInt(50_000).toLong()
        val a = rnd.nextDouble() * Math.PI; val l = 3_000 + rnd.nextInt(7_000)
        ToolPath(Feature.SPARSE, longArrayOf(x, y, x + Math.round(l * Math.cos(a)), y + Math.round(l * Math.sin(a))), false, 450, 200)
    }
    val starts = listOf(0L to 0L, 50_000L to 0L, 0L to 50_000L, 50_000L to 50_000L, 25_000L to 25_000L, 25_000L to 0L, 0L to 25_000L, 60_000L to 30_000L)
    fun travel(seq: List<ToolPath>, sx: Long, sy: Long): Double {
        var x = sx; var y = sy; var t = 0.0
        for (p in seq) { t += Math.hypot((p.xy[0] - x).toDouble(), (p.xy[1] - y).toDouble()); x = p.xy[p.xy.size - 2]; y = p.xy[p.xy.size - 1] }
        return t / 1000
    }
    fun spread(v: List<Double>) = (v.max() - v.min()) / v.average()

    "2-opt never lengthens travel, and travel depends less on the start point" {
        val greedy = starts.map { (x, y) -> travel(Ordering.order(paths, x, y, improvePass = false), x, y) }
        val improved = starts.map { (x, y) -> travel(Ordering.order(paths, x, y), x, y) }
        println("  greedy travel per start (mm): ${greedy.map { "%.0f".format(java.util.Locale.ROOT, it) }}, spread %.1f %%".format(java.util.Locale.ROOT, 100 * spread(greedy)))
        println("  2-opt  travel per start (mm): ${improved.map { "%.0f".format(java.util.Locale.ROOT, it) }}, spread %.1f %%".format(java.util.Locale.ROOT, 100 * spread(improved)))
        for (k in starts.indices) (improved[k] <= greedy[k] + 1e-6) shouldBe true
        (spread(improved) < spread(greedy)) shouldBe true
        // every path printed exactly once (a reversal must not lose or duplicate one)
        Ordering.order(paths, 0, 0).map { p -> paths.indexOfFirst { q -> q.xy.contentEquals(p.xy) || q.xy.contentEquals(longArrayOf(p.xy[2], p.xy[3], p.xy[0], p.xy[1])) } }.sorted() shouldBe paths.indices.toList()
    }

    // a reversed variable-width line lost its widths and printed at its widest throughout
    "a reversed variable-width path keeps its widths, reversed with it (order, 2-opt, sweep)" {
        val a = ToolPath(Feature.INNER_WALL, longArrayOf(0, 0, 5_000, 0, 10_000, 0), false, 800, 200, widths = longArrayOf(300, 500, 800))
        val b = ToolPath(Feature.INNER_WALL, longArrayOf(10_000, 1_000, 0, 1_000), false, 450, 200, widths = longArrayOf(450, 450))
        // starting at the far end of a: greedy enters a reversed
        for (seq in listOf(Ordering.order(listOf(a, b), 10_500, 0, improvePass = false), Ordering.order(listOf(a, b), 10_500, 0), Ordering.sweep(listOf(a), 0, 0), Ordering.sweep(listOf(a.reversed()), 0, 0))) {
            val ra = seq.first { it.xy.size == 6 }
            val expect = if (ra.xy[0] == 0L) longArrayOf(300, 500, 800) else longArrayOf(800, 500, 300)
            ra.widths!!.toList() shouldBe expect.toList()
        }
        // control: the start at a's far end really did reverse it
        Ordering.order(listOf(a, b), 10_500, 0, improvePass = false).first().xy[0] shouldBe 10_000L
    }
})
