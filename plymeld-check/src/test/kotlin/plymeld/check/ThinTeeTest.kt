package plymeld.check

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import plymeld.core.Walls
import plymeld.geom.Polygon
import plymeld.model.SliceSettings

/**
 * A thin area shaped like a T gets its center lines: the skeleton splits it into one line per arm at the junction, and
 * the "shorter than twice its width" rule (the void a round bead leaves in a corner) is judged on the connected lines
 * together, not arm by arm. Judged arm by arm, each arm of a small T (what a wall loop leaves inside a QR code's T-shaped
 * stroke: arms about 1 mm long and 0.5 mm wide) was dropped and the whole T was left empty. Control: a lone piece as short
 * as one arm is still dropped as a corner void.
 */
class ThinTeeTest : StringSpec({
    val s = SliceSettings.of(testSetup())
    fun lines(piece: Polygon) = Walls.centerLines(listOf(piece), s.minBeadUm, s.minFeatureUm, 2 * s.innerWallWidthUm, s.minLengthFactor, s.transitionLengthUm)
    fun length(xy: LongArray) = (1 until xy.size / 2).sumOf { k -> Math.hypot((xy[2 * k] - xy[2 * k - 2]).toDouble(), (xy[2 * k + 1] - xy[2 * k - 1]).toDouble()) }

    "a thin T (arms 0.5 mm wide, about 1 mm long) gets center lines along all three arms; control: a lone arm-sized piece gets none" {
        // bar 2.64 x 0.49 mm, stem 0.64 x 1.05 mm up from its middle
        val tee = Polygon(longArrayOf(0, 0, 2_644, 0, 2_644, 494, 1_644, 494, 1_644, 1_544, 1_000, 1_544, 1_000, 494, 0, 494))
        val t = lines(tee)
        val total = t.sumOf { length(it.xy) }
        println("tee: ${t.size} lines, %.0f um".format(total))
        // the arms' center lines: about 1.1 + 1.1 + 1.0 mm
        (total > 2_800) shouldBe true
        // every arm reached: a line near each arm's end
        fun reaches(x: Long, y: Long) = t.any { c -> (0 until c.xy.size / 2).any { k -> Math.hypot((c.xy[2 * k] - x).toDouble(), (c.xy[2 * k + 1] - y).toDouble()) < 400 } }
        reaches(250, 247) shouldBe true
        reaches(2_394, 247) shouldBe true
        reaches(1_322, 1_294) shouldBe true
        // control: one arm alone, 0.49 x 0.9 mm, is a corner void
        lines(Polygon(longArrayOf(0, 0, 900, 0, 900, 494, 0, 494))).size shouldBe 0
    }
})
