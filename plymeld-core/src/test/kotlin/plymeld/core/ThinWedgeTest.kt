package plymeld.core

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import plymeld.geom.Polygon

/**
 * A thin wedge keeps its center line: the double helix tube's wall beside a strip's end, cut at z 5.33, is a
 * four-sided piece 2 mm long, 0.5 to 0.97 mm wide. A loop fits only its widest end; the rest, 0.58 to 0.71 mm wide, is one
 * center line 0.57 mm long, under min_length_factor (2) times its width, which dropped it although it runs along the part's
 * outline (the pre-print check: "the model's outline has no wall along 4.7 mm"). Control: the same thin piece with no outline
 * given, the line is dropped (it is the outline exception that keeps it).
 */
class ThinWedgeTest : StringSpec({
    val wedge = Polygon(longArrayOf(14728, 2837, 14790, 2500, 13316, 2500, 12757, 2500, 12747, 2550, 12744, 2569, 12736, 2602, 12642, 3007, 13408, 3184,
        13618, 3239, 13816, 3291, 14587, 3470, 14697, 2995, 14704, 2965)).let { if (it.isCcw()) it else it.reversed() }
    "a thin wedge gets its loop where it fits and a center line along the rest; control: no outline, no line" {
        val w = Walls.arachne(listOf(wedge), 3, 440, 440, 100, 300, 150, 2.0, 300)
        w.walls.size shouldBe 1
        w.center.size shouldBe 1
        val c = w.center.single()
        (c.xy.size / 2) shouldBeGreaterThan 2
        (c.widths.min() >= 300 && c.widths.max() <= 880) shouldBe true
        // the control: the thin piece the loop leaves, without the outline, loses its line to min_length_factor
        val loop = w.walls.single()
        val band = plymeld.geom.Booleans.intersection(plymeld.geom.Offset.offset(loop, 220, plymeld.geom.Offset.Join.MITER), listOf(wedge))
        val thin = plymeld.geom.Booleans.difference(listOf(wedge), band)
        Walls.centerLines(thin, 300, 150, 880, 2.0, 300, listOf(wedge)).size shouldBe 1
        Walls.centerLines(thin, 300, 150, 880, 2.0, 300, emptyList()).size shouldBe 0
    }
})
