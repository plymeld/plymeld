package plymeld.core

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import plymeld.geom.Booleans
import plymeld.geom.Polygon

/**
 * A part of another filament buried just behind a face: one filament's area on a layer is a block whose face runs along X,
 * with a hole (where the buried part lies) 1.33 mm behind the face and shorter than it, about three lines of 0.44 mm in
 * front of it. Three walls of 0.44 mm on 0.08 mm layers fit beside the face beyond the hole's ends, not in front of it:
 * there the face's outer wall, the hole's, and a line between them, which meets the face's first inner wall where that
 * turns away from the face to run behind the hole. The catalog's plate behind a leaning face, its section on two layers
 * (one below the plate, one through it), as rectangles (µm).
 *
 * Measured on the walls Walls.arachne lays:
 *  - the face's outer wall: the largest change, from one point to the next along the face, of its center line's distance
 *    from the face and of its width; and between the two layers, where the face does not move, the largest change of
 *    either at the same place along it;
 *  - at each end of the hole, in front of it: the widest spot no line covers (IslandCheck, the measure the walls hold a
 *    narrow island's lines to), against that measure's limit for lines that lay an area well.
 * Controls, each seen to fail: the line's end reaching into a wall only where the wall's nearest point lies ahead of it
 * (the rule before), which leaves a pocket where the line meets the turn; an outer wall sharing a slit beside it as an
 * inner wall does (the rule before), which widens and moves the face's outer wall at that pocket; and, with both, a loop
 * widened at a slit keeping no point before the slit along its edge (the rule before), which widens and slants it along
 * the whole face from the pocket.
 */
class BuriedPartFaceTest : StringSpec({
    val face0 = 97_353L              // the face on the layer below the hole (µm)
    val face1 = 97_382L              // on the layer through it (the face leans back 29 µm a layer)
    val skin = 1_327L                // the hole's front behind the face
    fun rect(x0: Long, y0: Long, x1: Long, y1: Long) = Polygon(longArrayOf(x0, y0, x1, y0, x1, y1, x0, y1))
    // as the planner cleans a layer's section (closing its gaps narrower than 0.1 mm: the hole's corners cut)
    fun block(face: Long) = Polygon(longArrayOf(94_010, 103_000, 94_003, face, 105_997, face, 105_997, 102_997))
    val below = listOf(block(face0))
    val through = listOf(block(face1), Polygon(longArrayOf(96_050, 98_709, 96_015, 98_724, 96_000, 98_759, 96_000, 99_510, 96_015, 99_545, 96_050, 99_560,
        103_950, 99_560, 103_985, 99_545, 104_000, 99_510, 104_000, 98_759, 103_985, 98_724, 103_950, 98_709)))
    val wo = 440L; val h = 80L; val tol = 13L
    fun walls(o: List<Polygon>, ahead: Boolean = true, anchored: Boolean = true, outerFixed: Boolean = true) =
        Walls.arachne(o, 3, wo, wo, h, 300, 150, 2.0, 300, toleranceUm = tol, endOverlap = 0.25, maxBeadUm = 880, reachAhead = ahead, slitsAnchored = anchored,
            outerWallsFixed = outerFixed)

    /** The face's outer wall as (x, distance from the face, width) at each of its points along the face, by x. */
    fun faceWall(r: LayerWalls, face: Long): List<DoubleArray> {
        val k0 = r.walls[0]
        val li = k0.indices.single { li -> val p = k0[li]; (0 until p.size).any { Math.abs(p.y(it) - face) < 300 } }
        val p = k0[li]; val ws = r.widthsOf(0, li)
        return (0 until p.size).filter { Math.abs(p.y(it) - face) < 300 && p.x(it) in 94_200..105_800 }
            .map { doubleArrayOf(p.x(it).toDouble(), (p.y(it) - face).toDouble(), (ws?.get(it) ?: wo).toDouble()) }.sortedBy { it[0] }
    }
    /** The largest change of distance and of width from one point of [f] to the next (µm). */
    fun steps(f: List<DoubleArray>): Pair<Double, Double> =
        ((1 until f.size).maxOfOrNull { Math.abs(f[it][1] - f[it - 1][1]) } ?: 0.0) to ((1 until f.size).maxOfOrNull { Math.abs(f[it][2] - f[it - 1][2]) } ?: 0.0)
    /** [f]'s distance and width at [x] (µm), along its straight moves. */
    fun at(f: List<DoubleArray>, x: Double): Pair<Double, Double> {
        val i = (1 until f.size).first { f[it][0] >= x }
        val t = (x - f[i - 1][0]) / (f[i][0] - f[i - 1][0])
        return f[i - 1][1] + t * (f[i][1] - f[i - 1][1]) to f[i - 1][2] + t * (f[i][2] - f[i - 1][2])
    }
    /** The largest change of the face's outer wall between the two layers along the face (µm): distance, width. */
    fun layerShift(a: LayerWalls, b: LayerWalls): Pair<Double, Double> {
        val fa = faceWall(a, face0); val fb = faceWall(b, face1)
        val xs = (0..100).map { 94_300.0 + it * 114.0 }
        return xs.maxOf { Math.abs(at(fa, it).first - at(fb, it).first) } to xs.maxOf { Math.abs(at(fa, it).second - at(fb, it).second) }
    }
    /** The widest uncovered spot in front of the hole's end at [x0]..[x1] (µm, IslandCheck): the area clipped to that window. */
    fun pocket(r: LayerWalls, x0: Long, x1: Long): Double {
        val window = listOf(rect(x0, face1, x1, face1 + skin - 200))
        val isl = Booleans.intersection(through, window)
        val lines = r.walls.flatMapIndexed { k, lv -> lv.mapIndexed { li, p -> val ws = r.widthsOf(k, li)
            IslandCheck.Line(p.xy, DoubleArray(p.size) { (ws?.get(it) ?: wo).toDouble() }, true) } } +
            r.center.map { c -> IslandCheck.Line(c.xy, DoubleArray(c.widths.size) { c.widths[it].toDouble() }, false) }
        return IslandCheck.measure(isl, lines, r.inner, h * (1 - Math.PI / 4), 150.0 + 2 * tol, tol.toDouble()).widestUm
    }

    "the face's outer wall runs straight and even through the ends of a part buried behind it, and across its first layer" {
        val a = walls(below); val b = walls(through)
        val (dStep, wStep) = steps(faceWall(b, face1))
        val (dShift, wShift) = layerShift(a, b)
        val bc = walls(through, anchored = false)
        val (dStepC, wStepC) = steps(faceWall(bc, face1)); val (dShiftC, wShiftC) = layerShift(a, bc)
        println("outer wall: step $dStep um, width step $wStep um, shift between layers $dShift um, width $wShift um; control (no anchor before a slit, line ends as before): step $dStepC, $wStepC; shift $dShiftC, $wShiftC")
        (dStep == 0.0 && wStep == 0.0 && dShift == 0.0 && wShift == 0.0) shouldBe true
        // control: the line ends, the outer wall's share and the anchor as before: pockets read as slits, and the outer
        // wall's widening runs along the whole face
        val bcc = walls(through, ahead = false, anchored = false, outerFixed = false)
        val (dShiftCC, wShiftCC) = layerShift(a, bcc)
        println("control, the three rules as before: shift between layers $dShiftCC um, width $wShiftCC um")
        (dShiftCC > tol && wShiftCC > tol) shouldBe true
    }

    "an outer wall keeps its width and place with a slit behind it" {
        // the slit: the pocket the line's end leaves when it reaches into walls only ahead of it (the rule before)
        fun spread(r: LayerWalls): Pair<Double, Double> {
            val f = faceWall(r, face1)
            return (f.maxOf { it[1] } - f.minOf { it[1] }) to (f.maxOf { it[2] } - f.minOf { it[2] })
        }
        val (d, w) = spread(walls(through, ahead = false))
        val (dC, wC) = spread(walls(through, ahead = false, outerFixed = false))
        println("the face's outer wall with a slit behind it: its distance from the face varies by $d um, its width by $w um; control (the outer wall shares the slit): $dC, $wC um")
        (d == 0.0 && w == 0.0) shouldBe true
        (dC > 0.0 && wC > tol) shouldBe true
    }

    "a loop widened at a slit near the end of a long edge keeps its place and width along the rest of the edge" {
        // the slit: the pocket the line's end leaves when it reaches into walls only ahead of it (the rule before), read
        // beside the face's outer wall sharing it (the rule before), a loop beside a slit at the far end of a long edge
        fun middle(r: LayerWalls): Pair<Double, Double> {
            val fa = faceWall(walls(below), face0); val fb = faceWall(r, face1)
            val xs = (0..60).map { 97_000.0 + it * 100.0 }
            return xs.maxOf { Math.abs(at(fa, it).first - at(fb, it).first) } to xs.maxOf { Math.abs(at(fa, it).second - at(fb, it).second) }
        }
        val (d, w) = middle(walls(through, ahead = false, outerFixed = false))
        val (dC, wC) = middle(walls(through, ahead = false, anchored = false, outerFixed = false))
        println("a loop along the face's middle 6 mm beside a slit at its end: moved $d um, width changed $w um; control (no point kept before the slit): $dC, $wC um")
        (d <= tol && w <= tol) shouldBe true
        (wC > tol) shouldBe true
    }

    "the line in front of a buried part meets the inner wall turning away behind it with no pocket" {
        val b = walls(through)
        val left = pocket(b, 95_100, 96_500); val right = pocket(b, 103_500, 104_900)
        val c = walls(through, ahead = false)
        val leftC = pocket(c, 95_100, 96_500); val rightC = pocket(c, 103_500, 104_900)
        println("widest uncovered in front of the hole's ends: $left, $right um; control (reaching in only where the wall's nearest point is ahead): $leftC, $rightC um")
        (maxOf(left, right) <= Walls.NARROW_WIDEST_UM) shouldBe true
        (minOf(leftC, rightC) > Walls.NARROW_WIDEST_UM) shouldBe true
    }
})
