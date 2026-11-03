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

    "a sideways kink bigger than the tolerance is kept; control: tolerance 0 changes nothing" {
        val kinked = line(101, { 337L }, { if (it == 50) 30L else 0L })
        (Walls.simplified(kinked, 12).xy.size / 2 >= 3) shouldBe true
        Walls.simplified(kinked, 0).xy.size shouldBe kinked.xy.size
    }
})
