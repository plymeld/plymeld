package plymeld.core

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import plymeld.geom.Polygon

/**
 * Where the skeleton of a thin area forks, one pair of its branches is joined through the junction (Walls.joinEnds) and the
 * others give way to that line (giveWay). Two synthetic thin areas between walls (µm), measured by IslandCheck's coverage
 * (each line a bead of its width, narrowed by half the flank and the tolerance a side):
 *  - a crossing: two strips 0.6 mm wide crossing, one arm a stub 0.4 mm past the other strip. Both pairs of arms joined
 *    laid two lines over each other across the junction's whole width. With one pair joined the stub must be in it: cut
 *    back to give way to the other strip's line, nothing of it is clear of that line's bead, and the stub's end is left bare.
 *  - a blob running into a neck: a triangle 1.3 mm across its base narrowing to a neck 0.45 mm wide, 0.35 mm long. The
 *    branches into the base's corners run on straighter through the junction than either does with the neck's; joined, the
 *    neck's branch (shorter than the junction is wide) gives way to nothing and is dropped, and the neck is left bare: a
 *    hole. The neck's branch is joined with a corner's instead, the other corner's giving way.
 * Controls: the same areas with a junction's ends joined pair after pair, the straightest first (onePairAtJunction off, as
 * before): the crossing lays the junction twice past the allowance, the blob's neck is left bare.
 */
class JunctionJoinTest : StringSpec({
    val minBead = 300L; val minFeature = 150L; val maxWidth = 880L; val tol = 13L
    val floor = minFeature + 2 * tol
    val flank = 80 * (1 - Math.PI / 4)
    // the model's outline, far from the pieces: they lie between walls (where a bare spot is a hole)
    val farOutline = listOf(Polygon(longArrayOf(-5000, -5000, 10000, -5000, 10000, 10000, -5000, 10000)))
    fun ccw(p: Polygon) = if (p.isCcw()) p else p.reversed()
    fun lines(area: Polygon, onePair: Boolean) = Walls.centerLines(listOf(area), minBead, minFeature, maxWidth, 2.0, 300, farOutline, minFeature,
        floor, 0.25, onePairAtJunction = onePair)
    fun measure(area: Polygon, ls: List<CenterLine>) = IslandCheck.measure(listOf(area),
        ls.map { IslandCheck.Line(it.xy, DoubleArray(it.widths.size) { k -> it.widths[k].toDouble() }, false) }, emptyList(), flank, floor.toDouble(), tol.toDouble())
    fun show(name: String, ls: List<CenterLine>, m: IslandCheck.Measure) {
        println("$name: ${ls.size} line(s); $m")
        for (c in ls) println("  line: " + (0 until c.xy.size / 2).joinToString(" ") { "(${c.xy[2 * it]},${c.xy[2 * it + 1]};${c.widths[it]})" })
    }
    // what a branch's end reaching into another line by the overlap lays twice by design (as an end against a wall does): the
    // cap of its round end that far in, (4/3) x half its chord x its depth, for an end 0.6 mm wide 0.15 mm in, two ends (0.2 mm2)
    val capsUm2 = run { val w = 600.0; val o = 0.25 * w; 2 * 4.0 / 3 * Math.sqrt(w * o - o * o) * o }
    // the hole rule's limit, read a cell loose
    fun bare(m: IslandCheck.Measure) = m.widestUm > floor + m.cellUm

    // the crossing: a strip along x (y -300..300) and one along y (x -300..300) from a stub's end at y = -700 to y = 2000
    val cross = ccw(Polygon(longArrayOf(-2000, -300, -300, -300, -300, -700, 300, -700, 300, -300, 2000, -300, 2000, 300, 300, 300, 300, 2000,
        -300, 2000, -300, 300, -2000, 300)))
    "a crossing joins one pair through it, the stub's: no junction laid twice, no hole at the stub" {
        val ls = lines(cross, true)
        val m = measure(cross, ls)
        show("crossing", ls, m)
        bare(m) shouldBe false
        (m.doubledUm2 <= capsUm2) shouldBe true
        // the stub's end is reached by a line
        ls.any { c -> (0 until c.xy.size / 2).any { k -> c.xy[2 * k + 1] < -350 } } shouldBe true
    }
    "control: the crossing's ends joined pair after pair lay the junction twice" {
        val ls = lines(cross, false)
        val m = measure(cross, ls)
        show("crossing control", ls, m)
        (m.doubledUm2 > capsUm2) shouldBe true
    }

    // the blob: a base 1.3 mm wide, its sides rising to a neck 0.45 mm wide at y = 600, the neck up to y = 950
    val blob = ccw(Polygon(longArrayOf(0, 0, 1300, 0, 875, 600, 875, 950, 425, 950, 425, 600)))
    "a blob running into a short neck: the neck's branch is joined through the junction, the neck is laid" {
        val ls = lines(blob, true)
        val m = measure(blob, ls)
        show("blob", ls, m)
        bare(m) shouldBe false
        (m.doubledUm2 <= capsUm2) shouldBe true
        // a line reaches up into the neck
        ls.any { c -> (0 until c.xy.size / 2).any { k -> c.xy[2 * k + 1] > 700 } } shouldBe true
    }
    "control: the blob's ends joined straightest first leave the neck bare" {
        val ls = lines(blob, false)
        val m = measure(blob, ls)
        show("blob control", ls, m)
        bare(m) shouldBe true
    }
})
