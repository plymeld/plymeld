package plymeld.core

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import plymeld.geom.Polygon

/**
 * A thin area's fork into a corner is pruned as a corner void only where nothing a line should lay is left in it. The area
 * (a leftover between walls in the repository's Benchy at z 11.6, moved to the origin): a strip 0.4 mm wide ending in a
 * head about 1 mm across, a point of the head reaching up and out. Its skeleton forks in the head's middle, both forks
 * shorter than their width; pruned, they left the strip's line ending in the head's middle and a pocket 0.35 mm wide bare in
 * the point (wider than the narrowest place a line is laid in: a hole the pre-print check refuses). The fork into the point
 * is kept, the short one into the near corner (its area hardly reaches past the line's end) is not. Measured by
 * IslandCheck's coverage (each line a bead of its width): the widest spot no line covers within the hole rule's limit, the
 * lines laying nothing twice; control: the same center lines with the exception off (keepWiderUm unset) leave the pocket.
 */
class JunctionPocketTest : StringSpec({
    // the head at the left, the strip running right (µm)
    val head = Polygon(longArrayOf(3734, 791, 1243, 937, 796, 1153, 608, 1611, 608, 1792, 607, 1790, 607, 1430, 274, 1572, 174, 1570, 54, 1602,
        18, 1617, 189, 1219, 189, 576, 3119, 414, 3583, 182, 3619, 84, 3690, 80)).let { if (it.isCcw()) it else it.reversed() }
    val minBead = 360L; val minFeature = 180L; val maxWidth = 880L; val tol = 13L
    val floor = minFeature + 2 * tol
    // the pre-print check's hole rule: wider than the floor by more than two of its 25 um cells
    val limit = floor + 50.0
    val flank = 80 * (1 - Math.PI / 4)
    // the model's outline, far from the pieces: they lie between walls (where a bare spot is a hole, not the outline's)
    val farOutline = listOf(Polygon(longArrayOf(-5000, -5000, 10000, -5000, 10000, 10000, -5000, 10000)))
    fun lines(keepWider: Long) = Walls.centerLines(listOf(head), minBead, minFeature, maxWidth, 2.0, 360, farOutline, 180, keepWider)
    fun measure(lines: List<CenterLine>) = IslandCheck.measure(listOf(head),
        lines.map { IslandCheck.Line(it.xy, DoubleArray(it.widths.size) { k -> it.widths[k].toDouble() }, false) }, emptyList(), flank, floor.toDouble(), tol.toDouble())

    "a fork into a point of a thin area's head is kept, the short one into a corner is not" {
        val ls = lines(floor)
        val m = measure(ls)
        println("junction pocket: ${ls.size} line(s); widest bare ${"%.0f".format(m.widestUm)} um, doubled ${"%.4f".format(m.doubledUm2 / 1e6)} mm2")
        (m.widestUm <= limit) shouldBe true
        (m.doubledUm2 <= 2000.0) shouldBe true
        // the point is reached; the near corner (189, 576) is given no line
        ls.any { c -> (0 until c.xy.size / 2).any { k -> c.xy[2 * k] < 250 && c.xy[2 * k + 1] > 1350 } } shouldBe true
        ls.none { c -> (0 until c.xy.size / 2).any { k -> c.xy[2 * k] < 350 && c.xy[2 * k + 1] < 800 } } shouldBe true
    }
    "control: with the exception off, the forks are pruned and the pocket is left" {
        val m = measure(lines(Long.MAX_VALUE))
        println("junction pocket control: widest bare ${"%.0f".format(m.widestUm)} um")
        (m.widestUm > limit) shouldBe true
    }

    // A strip running into a triangle 1.2 mm across, the skeleton forking in its middle into two corners: both forks kept,
    // the strip's line runs on into the straighter one, and the third springs from that line, its start cut back to reach
    // into it by the wall overlap (0.25 of its width) instead of laying the junction twice.
    val triangle = Polygon(longArrayOf(0, 0, 2500, 100, 2550, 280, 2150, 650, 1950, 1150, 1800, 1150, 1300, 600, 0, 400))
        .let { if (it.isCcw()) it else it.reversed() }
    // what an end reaching into another line by the overlap lays twice by design (as an end against a wall does): the cap of
    // its round end that far in, (4/3) x half its chord x its depth, for an end 0.41 mm wide 0.1 mm in (0.024 mm2); the
    // junction laid twice is many times that
    val capUm2 = run { val w = 410.0; val o = 0.25 * w; 4.0 / 3 * Math.sqrt(w * o - o * o) * o }
    fun triLines(keepWider: Long, overlap: Double) =
        Walls.centerLines(listOf(triangle), minBead, minFeature, maxWidth, 2.0, 360, farOutline, 180, keepWider, overlap)
    fun triMeasure(ls: List<CenterLine>) = IslandCheck.measure(listOf(triangle),
        ls.map { IslandCheck.Line(it.xy, DoubleArray(it.widths.size) { k -> it.widths[k].toDouble() }, false) }, emptyList(), flank, floor.toDouble(), tol.toDouble())
    "three forks of a triangle: the third gives way to the line the other two make" {
        val ls = triLines(floor, 0.25)
        val m = triMeasure(ls)
        println("triangle: ${ls.size} line(s); widest bare ${"%.0f".format(m.widestUm)} um, doubled ${"%.4f".format(m.doubledUm2 / 1e6)} mm2")
        for (c in ls) println("  line: " + (0 until c.xy.size / 2).joinToString(" ") { "(${c.xy[2 * it]},${c.xy[2 * it + 1]};${c.widths[it]})" })
        (m.widestUm <= limit) shouldBe true
        (m.doubledUm2 <= capUm2) shouldBe true
    }
    "control: the triangle's forks pruned leave a pocket; a line reaching all the way into the other lays plastic twice" {
        val pruned = triMeasure(triLines(Long.MAX_VALUE, 0.25))
        val deep = triMeasure(triLines(floor, 1.0))
        println("triangle control: pruned widest ${"%.0f".format(pruned.widestUm)} um; reaching in doubled ${"%.4f".format(deep.doubledUm2 / 1e6)} mm2")
        (pruned.widestUm > limit) shouldBe true
        (deep.doubledUm2 > capUm2) shouldBe true
    }
})
