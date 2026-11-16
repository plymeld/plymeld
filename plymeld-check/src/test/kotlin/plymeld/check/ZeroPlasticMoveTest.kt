package plymeld.check

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import plymeld.core.check.ModelCheck
import plymeld.gcode.KlipperWriter
import plymeld.model.SliceSettings
import plymeld.program.Extrude
import plymeld.program.LayerStart
import plymeld.program.PrintProgram
import java.io.StringWriter

/**
 * A move inside a line whose plastic the file's E (5 decimals) rounds to nothing is merged into the next move where its
 * point lies within the slicing tolerance of the straight move that replaces the two, not written as "E0": a reader takes
 * a move without plastic for a travel, and the line is broken in two there. A scarf seam's closing
 * pass over a narrow loop ends in short segments at a sliver of the line's width; written as "E0" moves they broke the pass,
 * and the pre-print check's laid-twice rule (DoubledCheck) read the pass's last stretch as a line of its own, at the line's
 * full width (a path's widest plastic per mm is its stated width), over the loop's start: on a real three-object plate 40 of
 * its 92 spots, "outer wall laid over outer wall", 0.25 to 0.62 mm wide, where nothing lay twice.
 *
 * The case, built by hand: one layer 0.2 mm thick of a 10 mm box, an outer loop 0.44 mm wide round it and on along its first
 * side for 2 mm, as a scarf's closing pass (one path), narrowing to 0.05 mm by 0.5 mm along it, with two segments of 5 µm there
 * (a 5 µm move at 0.05 mm wide lays 3e-6 mm of filament: "E0" at 5 decimals), and on 1.5 mm at 0.05 mm.
 *  - written: no move inside the line without plastic (the first 5 µm move merged into the second, which then carries
 *    0.00001); the line's plastic is the bead model's over its whole length (the merged move's plastic carried, not
 *    lost); the check finds nothing laid twice;
 *  - control: the same file with the two merged moves put back as "E0" moves (the old writing): the check finds outer wall
 *    over outer wall on that layer.
 */
class ZeroPlasticMoveTest : StringSpec({
    val setup = testSetup()
    val s = SliceSettings.of(setup)
    val model = listOf(testBox(100f, 100f, 0f, 110f, 110f, 0.2f))
    // the loop at bead contact inside the box, then on along its first side (µm)
    val pts = listOf(100_220L to 100_220L, 109_780L to 100_220L, 109_780L to 109_780L, 100_220L to 109_780L, 100_220L to 100_220L,
        100_720L to 100_220L, 100_725L to 100_220L, 100_730L to 100_220L, 101_000L to 100_220L, 101_500L to 100_220L, 102_220L to 100_220L)
    val widths = longArrayOf(440, 440, 440, 440, 440, 50, 50, 50, 50, 50, 50)
    val line = Extrude("Outer wall", pts.flatMap { listOf(it.first, it.second) }.toLongArray(), false, 200, 440, 200, widths = widths, scarf = true)
    val text = StringWriter().also { KlipperWriter(s, setup.foreignTemplateVars, "test").write(PrintProgram(listOf(LayerStart(0, 200, 200), line), emptyList()), it) }.toString()
    // the line's moves: from its first extruding move to its last
    val lines = text.lines()
    val first = lines.indexOfFirst { it.startsWith("G1 X") && it.contains(" E") && !it.contains(" E-") && it.substringAfter(" E").trim().toDouble() > 0 && it.contains("Y100.22") }
    val last = lines.indexOfLast { it.startsWith("G1 X102.22 Y100.22") }
    fun zeroMoves(ls: List<String>) = ls.count { it.startsWith("G1 X") && it.trim().endsWith(" E0") }
    fun check(t: String) = ModelCheck.checkAll(t, model, 0.0, featureMinMm = 0.17, slicingTolMm = 0.01, endOverlap = 0.25)

    "written: no move inside the line without plastic; the line keeps the bead model's plastic; nothing found laid twice" {
        (first in 0 until last) shouldBe true
        zeroMoves(lines.subList(first, last + 1)) shouldBe 0
        // the first 5 µm move is merged into the next, which carries both moves' plastic (6e-6 mm: 0.00001)
        lines.none { it.startsWith("G1 X100.725 ") } shouldBe true
        lines.single { it.startsWith("G1 X100.73 ") } shouldBe "G1 X100.73 Y100.22 E0.00001"
        val area = Math.PI * 1.75 * 1.75 / 4
        val fl = s.flowRatio * s.scarfFlow
        val exact = (1 until pts.size).sumOf { k ->
            val len = Math.hypot((pts[k].first - pts[k - 1].first) / 1000.0, (pts[k].second - pts[k - 1].second) / 1000.0)
            val w = (widths[k] + widths[k - 1]) / 2000.0
            len * 0.2 * (w - 0.2 * (1 - Math.PI / 4)) / area * fl
        }
        val written = lines.subList(first - 1, last + 1).filter { it.startsWith("G1 X") && it.contains(" E") }.sumOf { it.substringAfter(" E").trim().toDouble() }
        written shouldBe (exact plusOrMinus 1e-4)
        val r = check(text)
        r.laidTwice.layers.filter { it.reading.spots.isNotEmpty() }.shouldBeEmpty()
    }
    "control: the merged moves written as E0 moves (the old writing) break the line, and the check reads outer wall over outer wall" {
        // the two 5 µm moves as the old writer wrote them, each rounded on its own (3e-6 mm: E0)
        val k = lines.indexOfFirst { it.startsWith("G1 X100.73 Y100.22") }
        (k > first) shouldBe true
        val old = lines.subList(0, k) + listOf("G1 X100.725 Y100.22 E0", "G1 X100.73 Y100.22 E0") + lines.subList(k + 1, lines.size)
        zeroMoves(old) shouldBe 2
        val spots = check(old.joinToString("\n")).laidTwice.layers.flatMap { it.reading.spots }
        println("old writing: ${spots.map { "${it.lines()} ${it.widthMm} mm ${it.areaMm2} mm2 at ${it.x}, ${it.y}" }}")
        spots.isNotEmpty() shouldBe true
        spots[0].lines() shouldBe "a line of outer wall laid over a line of outer wall"
    }
})
