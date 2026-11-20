package plymeld.core

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import plymeld.geom.Polygon

/**
 * Slits between loops too narrow for a line are shared by the lines beside them (Walls.shareSlits), also in a narrow island
 * whose beads were not taken. The outline: the catalog's two blobs joined by a neck (two ellipses 2.0 by 1.8 mm, a neck 0.5 mm
 * wide), as the planner cuts it on one of its layers (µm). Three walls of 0.44 mm on 0.08 mm layers fit nowhere in it, so it
 * is a narrow island; on this layer its beads lay it no better than its walls (IslandCheck) and it keeps its walls: in the
 * right blob the inner loop is a lens whose two sides leave a slit along its middle.
 *
 * Measured inside the lens (the right blob's inner loop as the walls laid it): the area no bead covers, each line a bead of
 * its width; and IslandCheck's plastic laid twice. Control: the island's walls kept unshared (the rule before) leave the slit.
 * The lens's two sides converge toward its ends, where a side's normal meets the other side at a slant: a side is widened no
 * further than the other side is, measured straight (without that limit, run once as a control: nothing left bare inside
 * the lens, and 6,300 µm² more laid twice, which the last check refuses).
 */
class SlitSharingTest : StringSpec({
    val outline = listOf(Polygon(longArrayOf(98943, 100298, 98817, 100518, 98648, 100685, 98425, 100815, 98171, 100886, 97916, 100897,
        97662, 100847, 97421, 100733, 97232, 100577, 97096, 100383, 97016, 100160, 97004, 99925, 97058, 99696, 97136, 99546, 97232, 99423,
        97421, 99267, 97661, 99153, 97918, 99103, 98083, 99103, 98262, 99132, 98419, 99182, 98577, 99265, 98703, 99361, 98815, 99480,
        98947, 99719, 98993, 99750, 101008, 99750, 101053, 99721, 101132, 99554, 101237, 99418, 101345, 99320, 101494, 99224, 101662, 99153,
        101822, 99114, 101986, 99100, 102178, 99115, 102338, 99153, 102497, 99220, 102638, 99307, 102756, 99411, 102863, 99546, 102934, 99680,
        102996, 99926, 102985, 100153, 102941, 100303, 102864, 100454, 102769, 100576, 102639, 100691, 102436, 100809, 102255, 100869,
        102083, 100897, 101917, 100897, 101738, 100868, 101581, 100818, 101423, 100735, 101297, 100639, 101185, 100520, 101053, 100281,
        101007, 100250, 98995, 100250, 98966, 100259)).let { if (it.isCcw()) it else it.reversed() })
    val wo = 440L; val wi = 440L; val h = 80L; val tol = 13L
    fun walls(shared: Boolean) = Walls.arachne(outline, 3, wo, wi, h, 300, 150, 2.0, 300, toleranceUm = tol, endOverlap = 0.25, maxBeadUm = 880,
        keptWallsShared = shared)
    fun beads(r: LayerWalls): List<IslandCheck.Line> = r.walls.flatMapIndexed { k, lv -> lv.mapIndexed { li, p -> val ws = r.widthsOf(k, li)
        IslandCheck.Line(p.xy, DoubleArray(p.size) { (ws?.get(it) ?: (if (k == 0) wo else wi)).toDouble() }, true) } } +
        r.center.map { c -> IslandCheck.Line(c.xy, DoubleArray(c.widths.size) { c.widths[it].toDouble() }, false) }
    /** The area inside [lens] (the right blob's inner loop as the walls laid it) that no bead covers, on a 5 µm grid (µm²). */
    fun bareInside(r: LayerWalls, lens: Polygon): Double {
        val ls = beads(r)
        fun covered(x: Double, y: Double) = ls.any { l -> val n = l.xy.size / 2
            (0 until (if (l.closed) n else n - 1)).any { e -> val f = (e + 1) % n
                val ax = l.xy[2 * e].toDouble(); val ay = l.xy[2 * e + 1].toDouble(); val bx = l.xy[2 * f].toDouble(); val by = l.xy[2 * f + 1].toDouble()
                val dx = bx - ax; val dy = by - ay; val l2 = dx * dx + dy * dy
                val u = if (l2 == 0.0) 0.0 else (((x - ax) * dx + (y - ay) * dy) / l2).coerceIn(0.0, 1.0)
                Math.hypot(ax + u * dx - x, ay + u * dy - y) <= (l.widths[e] + u * (l.widths[f] - l.widths[e])) / 2 } }
        val b = lens.bounds(); var n = 0
        var y = b[1].toDouble()
        while (y <= b[3]) { var x = b[0].toDouble()
            while (x <= b[2]) { if (plymeld.geom.Polygons.contains(lens, Math.round(x), Math.round(y)) && !covered(x, y)) n++; x += 5.0 }
            y += 5.0 }
        return n * 25.0
    }
    fun doubled(r: LayerWalls) = IslandCheck.measure(outline, beads(r), r.inner, h * (1 - Math.PI / 4), 150.0 + 2 * tol, tol.toDouble()).doubledUm2

    "a narrow island that keeps its walls shares their slits, laying no more twice" {
        val shared = walls(true); val kept = walls(false)
        // the island keeps its walls (the case this test is about): two levels of loops, one a blob each, none with widths of
        // its own (the beads' loops carry their widths)
        (kept.walls.size == 2 && kept.walls.all { it.size == 2 }) shouldBe true
        (kept.walls.indices.all { k -> kept.walls[k].indices.all { kept.widthsOf(k, it) == null } }) shouldBe true
        val lens = kept.walls[1].single { it.x(0) > 100000 }
        val bare = bareInside(shared, lens); val bareKept = bareInside(kept, lens)
        val dbl = doubled(shared); val dblKept = doubled(kept)
        println("kept walls: bare inside the lens ${"%.0f".format(bareKept)} um2, doubled ${"%.0f".format(dblKept)} um2; shared: bare ${"%.0f".format(bare)} um2, doubled ${"%.0f".format(dbl)} um2")
        (bareKept >= 8000.0) shouldBe true                     // control: unshared, the slit is there
        (bare <= bareKept / 2) shouldBe true
        (dbl <= dblKept + 1000.0) shouldBe true
    }
})
