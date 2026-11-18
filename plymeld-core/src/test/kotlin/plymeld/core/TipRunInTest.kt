package plymeld.core

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import plymeld.geom.Polygon

/**
 * A narrow island's outer loop runs into a tip (decision 28: its two sides at the narrowest width, then out and back along
 * the axis where the tip is narrower than the narrowest line); where that run's points all lie within the bead of the loop
 * beside it but for an interior line's end overlap, and the loop turns no more than 60 degrees there over a line width
 * either side, the run is left out (Beads, Lay.shortRunIns): it laid the line out and back a few tens of microns, a zigzag
 * in the outer line covering nothing the loop's bead does not. Synthetic islands (µm), the walls 0.44 mm, 0.08 mm layers:
 *  - a band 2 x 0.7 mm whose end tapers over 0.6 mm to a blunt end 0.24 mm wide, narrower than the narrowest line: the
 *    outer loop runs along the taper's sides and across the end, out into the end's corner and back, 30 microns. Without
 *    the run: no zigzag (the catalog's reading: a vertex between moves shorter than a line, turning back more than 150
 *    degrees, the line over a width either side going on within 120 degrees), nothing bare wider than the floor and no
 *    wider bare spot than with it but a cell (IslandCheck). Control: the run laid (shortRunInsGo off): one zigzag; the
 *    test seen to fail with the rule taken out.
 *  - a band 3 x 0.6 mm with square ends (a low bump 0.4 mm wide on one side): the loop turns a right angle at each corner
 *    and runs into it; its runs stay (the same loop with the rule on and off; seen to differ with the turn's bound taken
 *    out).
 */
class TipRunInTest : StringSpec({
    val flank = 80 * (1 - Math.PI / 4)
    val rule = Beads.Rule(440.0, 440.0, 3, flank, 300.0, 880.0)
    fun params(on: Boolean) = Beads.Params(rule, 150, 300, 13, 75, 0.25, 2.0, shortRunInsGo = on)
    val floor = (150 + 2 * 13).toDouble()   // the floor: feature.min and the slicing tolerance a side (Walls.arachne)
    /** The zigzags of [c] as the catalog reads them (CatalogMeasures.jagged, the outer wall's width 0.44 mm). */
    fun zigzags(c: CenterLine): Int {
        val n = c.xy.size / 2; var z = 0; val w = 440.0
        fun x(k: Int) = c.xy[2 * k].toDouble()
        fun y(k: Int) = c.xy[2 * k + 1].toDouble()
        fun along(k: Int, d: Double, back: Boolean): DoubleArray {
            var left = d; var j = k
            while (true) {
                val b = if (back) j - 1 else j + 1
                if (b < 0 || b >= n) return doubleArrayOf(x(j), y(j))
                val l = Math.hypot(x(b) - x(j), y(b) - y(j))
                if (l >= left) return doubleArrayOf(x(j) + (x(b) - x(j)) * left / l, y(j) + (y(b) - y(j)) * left / l)
                left -= l; j = b
            }
        }
        for (k in 1 until n - 1) {
            val l0 = Math.hypot(x(k) - x(k - 1), y(k) - y(k - 1)); val l1 = Math.hypot(x(k + 1) - x(k), y(k + 1) - y(k))
            if (l0 == 0.0 || l1 == 0.0 || minOf(l0, l1) >= w) continue
            if (((x(k) - x(k - 1)) * (x(k + 1) - x(k)) + (y(k) - y(k - 1)) * (y(k + 1) - y(k))) / (l0 * l1) >= Math.cos(Math.toRadians(150.0))) continue
            val b = along(k, w, true); val a = along(k, w, false)
            val bx = x(k) - b[0]; val by = y(k) - b[1]; val ax = a[0] - x(k); val ay = a[1] - y(k)
            if ((bx * ax + by * ay) / (Math.hypot(bx, by) * Math.hypot(ax, ay)) >= Math.cos(Math.toRadians(120.0))) z++
        }
        return z
    }
    fun outer(isl: List<Polygon>, on: Boolean) = Beads.lay(isl, params(on)).map { Walls.simplified(it, 13) }
    fun measure(isl: List<Polygon>, ls: List<CenterLine>) = IslandCheck.measure(isl,
        ls.map { IslandCheck.Line(it.xy, DoubleArray(it.widths.size) { k -> it.widths[k].toDouble() }, false) }, emptyList(), flank, floor, 13.0)

    val taper = listOf(Polygon(longArrayOf(0, 0, 2000, 0, 2600, 230, 2600, 470, 2000, 700, 0, 700)))
    "a loop going on past a short tip's end does not run out to it and back; nothing more is left bare" {
        val on = outer(taper, true); val off = outer(taper, false)
        val mOn = measure(taper, on); val mOff = measure(taper, off)
        println("taper: on ${on.filter { it.wall == 0 }.sumOf { zigzags(it) }} zigzags, $mOn; off ${off.filter { it.wall == 0 }.sumOf { zigzags(it) }}, $mOff")
        on.filter { it.wall == 0 }.sumOf { zigzags(it) } shouldBe 0
        mOn.wideUm2 shouldBe 0.0
        (mOn.widestUm <= mOff.widestUm + mOn.cellUm) shouldBe true
    }
    "control: the run into the tip's end laid, the loop zigzags there" {
        outer(taper, false).filter { it.wall == 0 }.sumOf { zigzags(it) } shouldBe 1
    }
    val bar = listOf(Polygon(longArrayOf(0, 0, 3000, 0, 3000, 600, 1700, 600, 1500, 700, 1300, 600, 0, 600)))
    "a loop turning a square end's corners keeps its runs into them" {
        val on = outer(bar, true).filter { it.wall == 0 }; val off = outer(bar, false).filter { it.wall == 0 }
        on.size shouldBe off.size
        on.indices.all { on[it].xy.contentEquals(off[it].xy) } shouldBe true
    }
})
