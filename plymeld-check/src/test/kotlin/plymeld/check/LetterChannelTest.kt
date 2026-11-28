package plymeld.check

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import plymeld.check.catalog.Catalog
import plymeld.check.catalog.Models
import plymeld.check.catalog.Slicing
import plymeld.core.IslandCheck
import plymeld.core.Walls
import plymeld.geom.Booleans
import plymeld.geom.Offset
import plymeld.mesh.Slicer
import plymeld.model.SliceSettings

/**
 * What the walls hand to infill is two inner lines wide between the beads beside it (DECISIONS 73). The catalog's letter
 * strokes (Models.letterStrokes): strokes wide enough for three walls a side with a channel inside them about two lines
 * wide, a short bulge in each, layer by layer a little wider. Where the bulge's stretch of the channel was just two inner
 * lines wide between the bands of the loops beside it, it went to infill: a piece no sparse line crossed, the center line
 * along the rest of the channel kept a line's width away from it, and the beads' flanks into it left the bare ground there
 * narrower than two inner lines, so the pre-print check refused it as a hole between walls.
 *  - each layer's walls (the walls rig's settings): round every piece handed to infill (the piece and a line's width round
 *    it, in the cross-section), the widest ground no bead of the loops or the center lines covers (IslandCheck, each line
 *    at its full width) is at least two inner lines: ground the infill's gaps may leave bare; control: with what the loops
 *    leave handed to infill wherever it is two inner lines wide between their bands (Walls.arachne infillPastFlanks =
 *    false), some is narrower;
 *  - the catalog's letter strokes, sliced: the pre-print check passes and nothing is laid twice (with the control's rule,
 *    run by hand, it refused holes 0.86 to 0.87 mm wide on 9 layers).
 */
class LetterChannelTest : StringSpec({
    val s = SliceSettings.of(testSetup(*Slicing.WALLS.values.toTypedArray()))
    val part = Models.letterStrokes
    // the layers' middles (the layers' tops at 0.2 mm, then every 0.08 mm: Models.letterStrokes)
    val mids = (1..43).map { 0.2 + 0.08 * it - 0.04 }
    val twoLines = 2.0 * s.innerWallWidthUm

    /** The narrowest bare ground round a piece the walls hand to infill, over the layers (µm), and the pieces read. */
    fun narrowest(pastFlanks: Boolean): Pair<Double, Int> {
        var least = Double.MAX_VALUE; var pieces = 0
        for (z in mids) {
            val section = Slicer.slice(part, doubleArrayOf(z))[0].loops
            val w = Walls.arachne(section, s.wallLoops, s.outerWallWidthUm, s.innerWallWidthUm, 80, s.minBeadUm, s.minFeatureUm, s.minLengthFactor,
                s.transitionLengthUm, toleranceUm = s.resolutionUm, endOverlap = s.infillWallOverlap, maxBeadUm = s.maxBeadFor(s.innerWallWidthUm),
                infillPastFlanks = pastFlanks)
            val lines = w.walls.flatMapIndexed { k, level -> level.mapIndexed { li, p ->
                    val width = (if (k == 0) s.outerWallWidthUm else s.innerWallWidthUm).toDouble()
                    val ws = w.widthsOf(k, li)
                    IslandCheck.Line(p.xy, DoubleArray(p.size) { ws?.get(it)?.toDouble() ?: width }, true) } } +
                w.center.map { c -> IslandCheck.Line(c.xy, DoubleArray(c.widths.size) { c.widths[it].toDouble() }, false) }
            for ((o, hs) in plymeld.geom.Polygons.group(w.inner)) {
                val round = Booleans.intersection(Offset.offset(listOf(o) + hs, s.innerWallWidthUm, Offset.Join.ROUND), section)
                least = minOf(least, IslandCheck.measure(round, lines, emptyList(), 0.0, 0.0, 0.0).widestUm); pieces++
            }
        }
        return least to pieces
    }

    "the bare ground round every piece the walls hand to infill is two inner lines wide; control: handed over by the bands, some is narrower" {
        val (fixed, n) = narrowest(true)
        val (control, nc) = narrowest(false)
        println("letter strokes: narrowest bare ground round a piece handed to infill ${"%.0f".format(fixed)} um of $n pieces; handed over by the bands " +
            "${"%.0f".format(control)} um of $nc; two inner lines ${"%.0f".format(twoLines)} um")
        (fixed >= twoLines) shouldBe true
        (control < twoLines) shouldBe true
    }
    "the catalog's letter strokes pass the pre-print check, nothing laid twice" {
        val r = Slicing.sliceCheck(Catalog.write(Catalog.byName("letter strokes")))
        r.problems shouldBe emptyList()
        r.laidTwice.spots shouldBe 0
    }
})
