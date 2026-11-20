package plymeld.core

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import plymeld.geom.Polygon

/**
 * Two loops of a level facing each other across a pinch narrower than the loop needs are kept a spacing apart
 * (Walls.apart). The outline (µm): a band 8 mm long and 3 mm wide, pinched in its middle by two V notches 1.2 mm wide at
 * the band's edges whose tips face each other 0.5 mm apart. Walls of 0.44 mm on 0.08 mm layers (spacing 0.423 mm), three
 * loops: the outer loop's fit (the outline shrunk by 0.431 mm) needs 0.86 mm and is cut at the pinch into the band's two
 * halves, whose outer loops turn back at the pinch closer than a line apart. Measured: how near the outer loops of the
 * two halves come (each read every 20 µm of its length), and IslandCheck's coverage (each line a bead of its width): the
 * widest spot no line covers. Control: the loops not cut apart come within 0.2 mm of each other, their beads over each
 * other across most of a line.
 */
class PinchLoopsTest : StringSpec({
    val outline = listOf(Polygon(longArrayOf(0, 0, 3400, 0, 4000, 1250, 4600, 0, 8000, 0, 8000, 3000, 4600, 3000, 4000, 1750, 3400, 3000, 0, 3000))
        .let { if (it.isCcw()) it else it.reversed() })
    val wo = 440L; val wi = 440L; val h = 80L; val tol = 13L
    val so = Walls.spacing(wo, h)
    fun walls(apart: Boolean) = Walls.arachne(outline, 3, wo, wi, h, 300, 150, 2.0, 300, toleranceUm = tol, endOverlap = 0.25, maxBeadUm = 880,
        loopsApart = apart)
    /** The nearest the outer loops of different islands come (µm). */
    fun nearestAcross(r: LayerWalls): Double {
        val islands = plymeld.geom.Polygons.group(r.walls[0])
        val pts = islands.map { (o, hs) -> (listOf(o) + hs).flatMap { p ->
            (0 until p.size).flatMap { e -> val f = (e + 1) % p.size
                val ex = (p.x(f) - p.x(e)).toDouble(); val ey = (p.y(f) - p.y(e)).toDouble(); val n = maxOf(1, Math.ceil(Math.hypot(ex, ey) / 20.0).toInt())
                (0 until n).map { i -> doubleArrayOf(p.x(e) + ex * i / n, p.y(e) + ey * i / n) } } } }
        var best = Double.MAX_VALUE
        for (a in pts.indices) for (b in a + 1 until pts.size) for (p in pts[a]) for (q in pts[b]) best = minOf(best, Math.hypot(p[0] - q[0], p[1] - q[1]))
        return best
    }
    fun beads(r: LayerWalls): List<IslandCheck.Line> = r.walls.flatMapIndexed { k, lv -> lv.mapIndexed { li, p -> val ws = r.widthsOf(k, li)
        IslandCheck.Line(p.xy, DoubleArray(p.size) { (ws?.get(it) ?: (if (k == 0) wo else wi)).toDouble() }, true) } } +
        r.center.map { c -> IslandCheck.Line(c.xy, DoubleArray(c.widths.size) { c.widths[it].toDouble() }, false) }
    fun measure(r: LayerWalls) = IslandCheck.measure(outline, beads(r), r.inner, h * (1 - Math.PI / 4), 150.0 + 2 * tol, tol.toDouble())

    "the outer loops of the band's two halves stay a spacing apart at the pinch, nothing left bare" {
        val r = walls(true)
        val d = nearestAcross(r); val m = measure(r)
        println("pinch: ${plymeld.geom.Polygons.group(r.walls[0]).size} outer loop island(s), nearest across ${"%.0f".format(d)} um; $m")
        plymeld.geom.Polygons.group(r.walls[0]).size shouldBe 2
        (d >= so - tol) shouldBe true
        (m.widestUm <= 150.0 + 2 * tol + m.cellUm) shouldBe true
    }
    "control: not cut apart, the two outer loops turn back over each other" {
        val r = walls(false)
        val d = nearestAcross(r)
        println("pinch control: nearest across ${"%.0f".format(d)} um")
        (d < so - 200) shouldBe true
    }
})
