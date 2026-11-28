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

    /*
     * A fill forking round a hole, laid by a sweep: a chain C up the trunk (three lines 10 mm long) and on into the left side
     * (three lines 4 mm long), the right side R (three lines beside them), and the merge M above both (two lines). Swept
     * downward from M (the head starts above it), C's start is the nearer once M is laid, and neither C nor R lies wholly
     * behind the other; C first would lay the trunk's top line before R, whose bottom line then goes down beside it: the
     * sweep lays R before C, since R holds a line right behind (above) one of C's. Control: C's start is the nearer from
     * where M ends, so the order by nearness and far edge alone takes C first.
     */
    "a sweep lays a fork's other side before the chain running on into one side, when that chain's line is ahead of it" {
        val sp = 450L
        fun y(k: Int) = k * sp
        val c = ToolPath(Feature.TOP, longArrayOf(0, y(0), 10_000, y(0), 10_000, y(1), 0, y(1), 0, y(2), 10_000, y(2),
            4_000, y(3), 0, y(3), 0, y(4), 4_000, y(4), 4_000, y(5), 0, y(5)), false, 450, 200)
        val r = ToolPath(Feature.TOP, longArrayOf(6_000, y(3), 10_000, y(3), 10_000, y(4), 6_000, y(4), 6_000, y(5), 10_000, y(5)), false, 450, 200)
        val m = ToolPath(Feature.TOP, longArrayOf(0, y(6), 10_000, y(6), 10_000, y(7), 0, y(7)), false, 450, 200)
        val seq = Ordering.sweep(listOf(c, r, m), 0, y(8))
        fun which(p: ToolPath) = listOf(c, r, m).indexOfFirst { q -> q.xy.contentEquals(p.xy) || q.reversed().xy.contentEquals(p.xy) }
        seq.map { which(it) } shouldBe listOf(2, 1, 0)
        // control: from where M ends (laid downward, it ends on its lower line), C's start (its top, turned downward) is nearer
        val mEnd = seq[0].xy.let { longArrayOf(it[it.size - 2], it[it.size - 1]) }
        val cStart = c.reversed().xy; val rStart = seq[1].xy
        (Math.hypot((cStart[0] - mEnd[0]).toDouble(), (cStart[1] - mEnd[1]).toDouble()) < Math.hypot((rStart[0] - mEnd[0]).toDouble(), (rStart[1] - mEnd[1]).toDouble())) shouldBe true
    }

    // a short line beside the end of a long one: greedy took the next long line (nearer) first and reached the short line
    // last, from 20 mm away, by a retracted travel; it belongs between the two, each travel under the retraction's minimum
    "a path whose travel would retract is moved beside a path ending within the free travel" {
        val l = ToolPath(Feature.INNER_WALL, longArrayOf(0, 0, 10_000, 0), false, 450, 200)
        val p = ToolPath(Feature.INNER_WALL, longArrayOf(10_200, 0, 30_000, 0), false, 450, 200)
        val s = ToolPath(Feature.INNER_WALL, longArrayOf(10_000, 500, 10_100, 500), false, 450, 200)
        val retract: (Long, Long, Long, Long) -> Double = { _, _, _, _ -> 26_700.0 }
        fun retracted(seq: List<ToolPath>): Int {
            var x = 0L; var y = 0L; var n = 0
            for (q in seq) { if (Math.hypot((q.xy[0] - x).toDouble(), (q.xy[1] - y).toDouble()) > 1_000) n++; x = q.xy[q.xy.size - 2]; y = q.xy[q.xy.size - 1] }
            return n
        }
        val fixed = Ordering.order(listOf(l, p, s), 0, 0, retract, freeUm = 1_000.0)
        retracted(fixed) shouldBe 0
        fixed.map { q -> listOf(l, p, s).indexOfFirst { it.xy[0] == q.xy[0] || it.xy[0] == q.xy[q.xy.size - 2] } } shouldBe listOf(0, 2, 1)
        // control: without the free travel every move costs a retraction and the short line is left for last
        retracted(Ordering.order(listOf(l, p, s), 0, 0, retract)) shouldBe 1
    }
})
