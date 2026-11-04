package plymeld.core.calibrate

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.longs.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import plymeld.core.calibrate.PlateMarks.Edge
import plymeld.geom.Booleans
import plymeld.geom.Polygon

/**
 * The calibration plates' one marking scheme: the origin's chamfer, tally notches (every fifth long), and labels that are the
 * values they name. Each check against geometry computed here by hand; controls: a square base, a notch on the wrong
 * edge, a character the font has not.
 */
class PlateMarksTest : StringSpec({
    fun area(ps: List<Polygon>) = ps.sumOf { it.signedArea() }

    "the base's front-left corner is cut by the chamfer, and only that corner; control: a box without it is square" {
        val b = PlateMarks.base(0, 0, 50_000, 30_000)
        b.isCcw() shouldBe true
        b.size shouldBe 5
        // the full box less a right triangle with legs of the chamfer
        b.signedArea() shouldBe (50_000.0 * 30_000 - PlateMarks.CHAMFER.toDouble() * PlateMarks.CHAMFER / 2 plusOrMinus 1.0)
        (0 until b.size).none { b.x(it) == 0L && b.y(it) == 0L } shouldBe true                 // the corner itself is gone
        PlateMarks.box(0, 0, 50_000, 30_000).size shouldBe 4
    }

    "between: a notch at each boundary but the last, centered on it, every fifth one long" {
        val b = longArrayOf(0, 0, 100_000, 20_000)
        val bounds = (1..7).map { it * 10_000L }                                                   // seven items, 10 mm each
        val ns = PlateMarks.between(b, Edge.FRONT, bounds)
        ns.size shouldBe 6
        ns.map { (it.bounds()[0] + it.bounds()[2]) / 2 } shouldContainExactly bounds.dropLast(1)
        // depths into the box from its front edge (y0 = 0): the fifth long
        ns.map { it.bounds()[3] } shouldContainExactly listOf(1_500L, 1_500L, 1_500L, 1_500L, 3_000L, 1_500L)
        // cut from the box: each notch removes its width x depth inside the box, nothing outside it
        val cut = Booleans.difference(listOf(PlateMarks.box(0, 0, 100_000, 20_000)), ns)
        area(cut) shouldBe (100_000.0 * 20_000 - 1_000.0 * (5 * 1_500 + 3_000) plusOrMinus 1.0)
    }

    "count: n notches together at one place, every fifth long; each edge cuts into its own side; control: a back notch is not at the front" {
        val b = longArrayOf(0, 0, 40_000, 40_000)
        val ns = PlateMarks.count(b, Edge.LEFT, 5_000, 6)
        ns.size shouldBe 6
        ns.map { (it.bounds()[1] + it.bounds()[3]) / 2 } shouldContainExactly (0 until 6).map { 5_000L + it * PlateMarks.TALLY_PITCH }
        ns.map { it.bounds()[2] } shouldContainExactly listOf(1_500L, 1_500L, 1_500L, 1_500L, 3_000L, 1_500L)
        PlateMarks.countLength(6) shouldBe 5 * PlateMarks.TALLY_PITCH + PlateMarks.TALLY_W
        PlateMarks.count(b, Edge.LEFT, 0, 0).size shouldBe 0
        // each edge's notch lies inside the box along that edge
        PlateMarks.notch(b, Edge.BACK, 10_000, 1_500).bounds()[1] shouldBe 38_500L
        PlateMarks.notch(b, Edge.RIGHT, 10_000, 1_500).bounds()[0] shouldBe 38_500L
        PlateMarks.notch(b, Edge.FRONT, 10_000, 1_500).bounds()[3] shouldBe 1_500L
        (PlateMarks.notch(b, Edge.BACK, 10_000, 1_500).bounds()[1] > 20_000L) shouldBe true      // control: not at the front
    }

    "a label is its value in raised glyphs, inside its stated box; control: a character the font has not is refused by name" {
        val text = PlateMarks.whole(214.6)
        text shouldBe "215"
        val ps = PlateMarks.label(text, 10_000, 20_000)
        (ps.isNotEmpty()) shouldBe true
        val xs = ps.flatMap { p -> (0 until p.size).map { p.x(it) } }; val ys = ps.flatMap { p -> (0 until p.size).map { p.y(it) } }
        // exactly [x0, x0 + labelWidth] x [y0, y0 + labelHeight]: touching all four sides of its stated box
        xs.min() shouldBe 10_000L; ys.min() shouldBe 20_000L
        xs.max() shouldBe 10_000L + PlateMarks.labelWidth(text)
        ys.max() shouldBe 20_000L + PlateMarks.labelHeight(text)
        // the glyphs are at least as tall as the font's height (a diagonal's end may reach past it)
        PlateMarks.labelHeight(text) shouldBeLessThanOrEqual PlateMarks.LABEL_H + 2 * PlateMarks.LABEL_STROKE
        (PlateMarks.labelHeight(text) >= PlateMarks.LABEL_H + PlateMarks.LABEL_STROKE) shouldBe true
        shouldThrow<IllegalArgumentException> { PlateMarks.label("FAST", 0, 0) }.message!! shouldContain "no 'F'"
    }

    "lengths read to the hundredth, trailing zeros dropped" {
        listOf(0.18, 0.2, 1.0, 0.255, 12.5).map(PlateMarks::mm) shouldContainExactly listOf("0.18", "0.2", "1", "0.26", "12.5")
    }
})
