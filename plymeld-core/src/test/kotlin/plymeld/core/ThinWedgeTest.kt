package plymeld.core

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import plymeld.geom.Booleans
import plymeld.geom.Offset
import plymeld.geom.Polygon

/**
 * A thin wedge is walled along its whole outline: a tube's wall beside a strip's end, cut across, is a four-sided piece
 * 2 mm long, 0.5 to 0.97 mm wide, too narrow anywhere for its three walls: an island laid by its local width (Beads), every
 * point of its outline with a line beside it. Laid by a loop where one fits and a center line along the rest (as such a
 * piece of a wider island still is), the 0.57 mm line is under min_length_factor (2) times its width, and dropped it would
 * leave the part's outline with no wall there; the center lines keep a short line along the outline, and drop it with no
 * outline given (the control).
 */
class ThinWedgeTest : StringSpec({
    val wedge = Polygon(longArrayOf(14728, 2837, 14790, 2500, 13316, 2500, 12757, 2500, 12747, 2550, 12744, 2569, 12736, 2602, 12642, 3007, 13408, 3184,
        13618, 3239, 13816, 3291, 14587, 3470, 14697, 2995, 14704, 2965)).let { if (it.isCcw()) it else it.reversed() }
    "a thin wedge has a line beside every point of its outline, each within the narrowest and widest a wall line may be" {
        val w = Walls.arachne(listOf(wedge), 3, 440, 440, 100, 300, 150, 2.0, 300)
        // its lines: the beads' loops (laid as wall loops) and open lines
        val lines = w.center.map { it.xy to it.widths } + w.walls.flatMapIndexed { k, lv -> lv.mapIndexed { li, p ->
            (p.xy + longArrayOf(p.x(0), p.y(0))) to ((w.widthsOf(k, li) ?: LongArray(p.size) { 440L }).let { it + it[0] }) } }
        (lines.isNotEmpty()) shouldBe true
        for ((_, ws) in lines) (ws.min() >= 300 && ws.max() <= 880) shouldBe true
        // the outline every 20 um, a convex corner's last 0.3 mm left out (a round bead cannot reach into it): a line's
        // bead within its half width and 0.03 mm (the slicing and the skeleton's sampling) of each point
        var bare = 0; var probed = 0
        for (i in 0 until wedge.size) {
            val j = (i + 1) % wedge.size
            val ax = wedge.x(i).toDouble(); val ay = wedge.y(i).toDouble(); val bx = wedge.x(j).toDouble(); val by = wedge.y(j).toDouble()
            val len = Math.hypot(bx - ax, by - ay); val k = maxOf(1, (len / 20).toInt())
            for (s in 0..k) {
                val px = ax + (bx - ax) * s / k; val py = ay + (by - ay) * s / k
                if ((0 until wedge.size).any { v -> Math.hypot(px - wedge.x(v), py - wedge.y(v)) < 300 }) continue
                probed++
                val near = lines.any { (xy, ws) -> (0 until xy.size / 2 - 1).any { m ->
                    val qx = xy[2 * m].toDouble(); val qy = xy[2 * m + 1].toDouble(); val dx = xy[2 * m + 2] - qx; val dy = xy[2 * m + 3] - qy
                    val l2 = dx * dx + dy * dy; val t = if (l2 == 0.0) 0.0 else (((px - qx) * dx + (py - qy) * dy) / l2).coerceIn(0.0, 1.0)
                    Math.hypot(px - qx - t * dx, py - qy - t * dy) <= (ws[m] + ws[m + 1]) / 4.0 + 30 } }
                if (!near) bare++
            }
        }
        println("thin wedge: ${w.center.size} line(s), $bare of $probed outline points with no line beside them")
        (probed > 40) shouldBe true
        bare shouldBe 0
    }
    "the center lines keep a short line along the outline; control: no outline, no line" {
        // the leftover a loop laid where it fits (the outer loop's fit region grown back by half its bead) would leave
        val half = Walls.spacing(440, 100) / 2
        val loop = Offset.offset(Offset.offset(listOf(wedge), -(220 + half), Offset.Join.MITER), half, Offset.Join.MITER)
        loop.isNotEmpty() shouldBe true
        val band = Booleans.intersection(Offset.offset(loop, 220, Offset.Join.MITER), listOf(wedge))
        val thin = Booleans.difference(listOf(wedge), band)
        Walls.centerLines(thin, 300, 150, 880, 2.0, 300, listOf(wedge)).size shouldBe 1
        Walls.centerLines(thin, 300, 150, 880, 2.0, 300, emptyList()).size shouldBe 0
    }
})
