package plymeld.core

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import plymeld.program.Extrude

/**
 * Scarf seams (plan.technique.scarf_seam.where) on a 20 x 20 mm outer wall loop (80 mm round), 0.3 mm layer, 0.44 mm line,
 * a 10 mm ramp starting at a quarter of the layer, the layer's top at 10 mm. Worked by hand: the ramp starts at Z
 * 10 - 0.3 + 0.075 = 9.775 and reaches 10 at 10 mm along; the loop's plastic is the same as without the scarf (the two
 * passes over the ramp lay one layer's bead between them).
 */
class ScarfTest : StringSpec({
    val h = 300L; val w = 440L; val top = 10_000L
    val square = longArrayOf(0, 0, 20_000, 0, 20_000, 20_000, 0, 20_000)
    fun loop(scarf: Long, xy: LongArray = square) = ToolPath(Feature.OUTER_WALL, xy, true, w, h, scarfUm = scarf, scarfStart = 0.25)
    /** The plastic an extrusion lays (mm3), by the writer's bead model: each segment at the mean of its ends' thickness. */
    fun volume(e: Extrude, thick: LongArray? = e.heights): Double {
        val m = e.xy.size / 2; val n = if (e.closed) m else m - 1; val k = 1 - Math.PI / 4
        var v = 0.0
        for (i in 0 until n) {
            val j = (i + 1) % m
            val len = Math.hypot((e.xy[2 * j] - e.xy[2 * i]).toDouble(), (e.xy[2 * j + 1] - e.xy[2 * i + 1]).toDouble()) / 1000
            val t = (if (thick == null) e.heightUm.toDouble() else (thick[i] + thick[j]) / 2.0) / 1000
            v += len * t * (w / 1000.0 - k * t)
        }
        return v
    }

    "without a scarf the loop is one extrusion, unchanged" {
        val x = Scarf.extrudes(loop(0), top, 0)
        x.size shouldBe 1
        x[0].closed shouldBe true
    }
    "a 10 mm scarf: the ramp from Z 9.775 to the top over 10 mm, the rest at the top, the ramp again at the top thinning to nothing" {
        val (up, rest, down) = Scarf.extrudes(loop(10_000), top, 0)
        up.zs!!.first() shouldBe 9_775L
        up.zs!!.last() shouldBe top
        up.xy[up.xy.size - 2] shouldBe 10_000L                    // Q, 10 mm along the first edge
        up.xy.size / 2 shouldBe 11                                   // in 1 mm steps
        rest.zs shouldBe null
        rest.xy.size / 2 shouldBe 5                                  // Q, the three other corners, back to the seam
        down.zs!!.all { it == top } shouldBe true
        down.heights!!.last() shouldBe 0L
        listOf(up.closed, rest.closed, down.closed) shouldBe listOf(false, false, false)
    }
    "the scarfed loop lays the same plastic as the plain one (the two passes make one layer's bead)" {
        val plain = volume(Scarf.extrudes(loop(0), top, 0)[0])
        val scarfed = Scarf.extrudes(loop(10_000), top, 0).sumOf { volume(it) }
        scarfed shouldBe (plain plusOrMinus plain * 0.002)
    }
    // by hand: two beads t and h - t lay 2 (1 - pi/4) t (h - t) more than one of h; t runs evenly from h / 4 to h, where
    // t (h - t) averages 0.1875 h^2: 0.00724 mm2 over a 0.1127 mm2 bead, 6.4 % more along the ramp
    "control: the passes at their literal thicknesses (t and h - t) would overfill the ramp, by 6.4 %" {
        val (up, _, down) = Scarf.extrudes(loop(10_000), top, 0)
        val literalUp = LongArray(up.zs!!.size) { up.zs!![it] - (top - h) }
        val literalDown = LongArray(literalUp.size) { h - literalUp[it] }
        val ours = volume(up) + volume(down)
        val literal = volume(up, literalUp) + volume(down, literalDown)
        (literal / ours) shouldBe (1.064 plusOrMinus 0.003)
    }
    "the ramp is at most half the loop: a 6 x 6 mm loop (24 mm round) gets a 12 mm ramp for a 20 mm one asked" {
        val small = longArrayOf(0, 0, 6_000, 0, 6_000, 6_000, 0, 6_000)
        val (up, _, _) = Scarf.extrudes(loop(20_000, small), top, 0)
        // 12 mm along: the first edge (6) and the second (6): Q is the far corner
        (up.xy[up.xy.size - 2] to up.xy[up.xy.size - 1]) shouldBe (6_000L to 6_000L)
    }
    "a loop too short for a ramp of two line widths is printed as it is" {
        val tiny = longArrayOf(0, 0, 400, 0, 400, 400, 0, 400)    // 1.6 mm round: half is 0.8, under 2 x 0.44
        Scarf.extrudes(loop(10_000, tiny), top, 0).size shouldBe 1
    }
})
