package plymeld.core

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/** Walls.simplified: vertices go only where both bead edges stay within the tolerance. */
class CenterLineSimplifyTest : StringSpec({
    fun line(n: Int, w: (Int) -> Long, y: (Int) -> Long = { 0L }) =
        CenterLine(LongArray(2 * n) { if (it % 2 == 0) 50L * (it / 2) else y(it / 2) }, LongArray(n) { w(it) })

    "a straight constant-width line sampled every 50 um collapses to its two ends" {
        val s = Walls.simplified(line(101, { 337L }), 12)
        s.xy.size / 2 shouldBe 2
        s.widths.toList() shouldBe listOf(337L, 337L)
    }

    "a width step is kept: the edges would otherwise move 75 um" {
        val c = line(101, { if (it < 50) 300L else 450L })
        val s = Walls.simplified(c, 12)
        (s.xy.size / 2 > 2) shouldBe true
        // every original vertex: its edge within 12 um of the simplified line's edge at the same x
        for (k in 0 until 101) {
            val x = c.xy[2 * k]
            val j = (0 until s.xy.size / 2 - 1).first { s.xy[2 * it] <= x && x <= s.xy[2 * it + 2] }
            val w = (s.widths[j] + s.widths[j + 1]) / 2.0       // one move, one width: the mean of its ends
            // half the tolerance per edge (the outline was already simplified to the full tolerance)
            (Math.abs(w / 2 - c.widths[k] / 2.0) <= 6.0 + 1e-9 || s.xy.indices.step(2).any { s.xy[it] == x }) shouldBe true
        }
    }

    // A move extrudes at one rate: a taper 300 -> 450 um in one move would be printed 375 wide all along,
    // its edges 37 um off at both ends. It must stay in steps.
    "a width taper stays in steps no wider than the tolerance allows" {
        val c = line(101, { 300L + 150L * it / 100 })
        val s = Walls.simplified(c, 12)
        println("taper: ${s.xy.size / 2} vertices of 101")
        (s.xy.size / 2 >= 4) shouldBe true
    }

    /*
     * A narrow island's loop as Beads traces it: a point on each rib of an outline sample, so pairs of points 5 um apart
     * every 75 um, each with its own node's width (60 um apart within a pair), along a taper. The width rule alone keeps
     * every pair: moves of 5 um. Merged first: no move shorter than the tolerance, no point moved as far as the tolerance,
     * the plastic (each move at the mean of its ends' widths) the same within rounding. Control: without the merge, the
     * moves of 5 um stay.
     */
    fun ribPairs(closed: Boolean): CenterLine {
        val xs = ArrayList<Long>(); val ws = ArrayList<Long>()
        for (k in 0 until 40) {
            xs.add(75L * k); xs.add(if (k % 2 == 0) 0L else 4L); ws.add(300L + 3L * k)
            xs.add(75L * k + 5); xs.add(if (k % 2 == 0) 3L else 1L); ws.add(360L + 3L * k)
        }
        if (closed) { // back along a parallel side 400 um off, then to the start
            for (k in 39 downTo 0) { xs.add(75L * k + 5); xs.add(400L); ws.add(330L); xs.add(75L * k); xs.add(403L); ws.add(390L) }
            xs.add(xs[0]); xs.add(xs[1]); ws.add(ws[0])
        }
        return CenterLine(xs.toLongArray(), ws.toLongArray())
    }
    // the shortest move; [inner]: not counting a line's first and last move (an open line's end keeps the vertex beside it)
    fun shortest(c: CenterLine, inner: Boolean = false) = (if (inner) 2 until c.xy.size / 2 - 1 else 1 until c.xy.size / 2).minOf { Math.hypot((c.xy[2 * it] - c.xy[2 * it - 2]).toDouble(), (c.xy[2 * it + 1] - c.xy[2 * it - 1]).toDouble()) }
    fun plastic(c: CenterLine) = (1 until c.xy.size / 2).sumOf { Math.hypot((c.xy[2 * it] - c.xy[2 * it - 2]).toDouble(), (c.xy[2 * it + 1] - c.xy[2 * it - 1]).toDouble()) * (c.widths[it] + c.widths[it - 1]) / 2.0 }
    fun farthest(from: CenterLine, to: CenterLine) = (0 until from.xy.size / 2).maxOf { k ->
        (0 until to.xy.size / 2).minOf { Math.hypot((from.xy[2 * k] - to.xy[2 * it]).toDouble(), (from.xy[2 * k + 1] - to.xy[2 * it + 1]).toDouble()) } }

    "a narrow island's rib pairs: no move shorter than the tolerance, the plastic kept; control: without the merge they stay" {
        for (closed in listOf(false, true)) {
            val c = ribPairs(closed)
            val m = Walls.merged(c, 12)
            val s = Walls.simplified(c, 12)
            println("rib pairs (closed $closed): ${c.xy.size / 2} points, merged ${m.xy.size / 2}, simplified ${s.xy.size / 2}; shortest move ${shortest(c)} -> ${shortest(s)} um; plastic ${plastic(c)} -> ${plastic(m)}")
            (shortest(m, inner = !closed) >= 12.0) shouldBe true
            (shortest(s, inner = !closed) >= 12.0) shouldBe true
            // an open line's last move, beside its end, is left as it was
            if (!closed) shortest(m).toInt() shouldBe 5
            // every point merged lies within the tolerance of the one it went into
            (farthest(c, m) < 12.0) shouldBe true
            // the plastic: within half a micron of width over the line's length (widths and places are whole microns)
            val length = (1 until c.xy.size / 2).sumOf { Math.hypot((c.xy[2 * it] - c.xy[2 * it - 2]).toDouble(), (c.xy[2 * it + 1] - c.xy[2 * it - 1]).toDouble()) }
            (Math.abs(plastic(m) - plastic(c)) <= 0.5 * length) shouldBe true
            // the line starts and ends where it did (a closed one stays closed)
            m.xy[0] shouldBe c.xy[0]; m.xy[1] shouldBe c.xy[1]
            m.xy[m.xy.size - 2] shouldBe c.xy[c.xy.size - 2]; m.xy[m.xy.size - 1] shouldBe c.xy[c.xy.size - 1]
            // control: the width rule alone keeps the moves of 5 um
            (shortest(Walls.simplified(c, 12, merge = false)) < 6.0) shouldBe true
        }
    }

    "a sideways kink bigger than the tolerance is kept; control: tolerance 0 changes nothing" {
        val kinked = line(101, { 337L }, { if (it == 50) 30L else 0L })
        (Walls.simplified(kinked, 12).xy.size / 2 >= 3) shouldBe true
        Walls.simplified(kinked, 0).xy.size shouldBe kinked.xy.size
    }
})
