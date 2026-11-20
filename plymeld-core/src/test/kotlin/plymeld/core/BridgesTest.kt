package plymeld.core

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import plymeld.geom.Polygon

class BridgesTest : StringSpec({
    fun rect(x0: Long, y0: Long, x1: Long, y1: Long) = Polygon(longArrayOf(x0, y0, x1, y0, x1, y1, x0, y1))

    "a gap between two supports running along y is bridged along x (0°)" {
        // supports: x in [0,10] and [20,30] mm, y in [0,40]; bridge spans x 8..22 (with anchors)
        val below = listOf(rect(0, 0, 10_000, 40_000), rect(20_000, 0, 30_000, 40_000))
        val bridge = listOf(rect(8_000, 0, 22_000, 40_000))
        Bridges.bestAngle(bridge, below, 377, 400) shouldBe 0.0
    }

    "rotated case: supports above and below in y -> bridge along y (90°)" {
        val below = listOf(rect(0, 0, 40_000, 10_000), rect(0, 20_000, 40_000, 30_000))
        val bridge = listOf(rect(0, 8_000, 40_000, 22_000))
        Bridges.bestAngle(bridge, below, 377, 400) shouldBe 90.0
    }

    // Worked: a 30 mm square anchored all round with an r 8 hole (itself ringed by material below).
    // Every angle anchors every line, so the longest line decides: along x or y it is the full
    // 30 mm width; along a diagonal the lines clear of the hole are at most 30·√2 − 2·8 ≈ 26.4 mm.
    // (The hole block: the anchored LENGTH differed by hatching noise, the pick was -75°.)
    "a square anchored all round, with a round hole: bridged along a diagonal, where the longest line is shortest" {
        fun circle(cx: Long, cy: Long, r: Double, ccw: Boolean) = Polygon(LongArray(128) { k -> val t = (if (ccw) 1 else -1) * 2 * Math.PI * (k / 2) / 64
            if (k % 2 == 0) cx + Math.round(r * Math.cos(t)) else cy + Math.round(r * Math.sin(t)) })
        val bridge = listOf(rect(0, 0, 30_000, 30_000), circle(15_000, 15_000, 8_000.0, false))
        val below = listOf(rect(-5_000, -5_000, 35_000, 35_000), Polygon(longArrayOf(0, 0, 0, 30_000, 30_000, 30_000, 30_000, 0)),
            circle(15_000, 15_000, 8_000.0, true), circle(15_000, 15_000, 6_000.0, false))
        val a = Bridges.bestAngle(bridge, below, 377, 400)
        println("  square with hole: bridge angle $a")
        (a == 45.0 || a == 135.0) shouldBe true
    }

    // internal bridges over sparse infill: the angle whose longest unsupported span is shortest.
    // Worked: a 40 mm square ringed by wall below, over rectilinear lines at 45 deg, 2 mm apart. Across
    // them (135) every strand is held every 2/sin 90 = 2 mm; along them (45) a strand never crosses one
    // and spans up to 40√2 = 56.6 mm; at 0 or 90 the crossings come every 2/sin 45 = 2.83 mm.
    val ring = listOf(rect(-5_000, -5_000, 45_000, 45_000), Polygon(longArrayOf(0, 0, 0, 40_000, 40_000, 40_000, 40_000, 0)))
    "internal bridge over one sparse family: across the lines" {
        Bridges.internalAngle(listOf(rect(0, 0, 40_000, 40_000)), ring, listOf(Bridges.Family(45.0, 2_000)), 400, 400) shouldBe 135.0
        Bridges.internalAngle(listOf(rect(0, 0, 40_000, 40_000)), ring, listOf(Bridges.Family(135.0, 2_000)), 400, 400) shouldBe 45.0
    }

    // a narrow strip: 2.4 mm between walls, over a 45/135 grid 5.66 mm apart. Along
    // the strip the crossings come every 4 mm; across it every strand is held at both walls: 2.4 mm.
    "a narrow strip over a grid is bridged across its width, wall to wall" {
        val below = listOf(rect(-5_000, -5_000, 45_000, 7_400), Polygon(longArrayOf(0, 0, 0, 2_400, 40_000, 2_400, 40_000, 0)))
        val grid = listOf(Bridges.Family(45.0, 5_657), Bridges.Family(135.0, 5_657))
        Bridges.internalAngle(listOf(rect(0, 0, 40_000, 2_400)), below, grid, 400, 400) shouldBe 90.0
    }

    "control: no families, no rule (the external-bridge scorer decides)" {
        Bridges.internalAngle(listOf(rect(0, 0, 40_000, 40_000)), ring, emptyList(), 400, 400) shouldBe null
    }

    // An area hanging off one side: arms 6 mm wide reaching 24 mm out from a bar's side (y = 8 mm, the bar below
    // y 0..8 along x), at 45, 90, and 135 degrees, each region reaching 0.8 mm into the bar as a bridge's anchor does. No
    // line over air lands on the bar at both ends at any angle; each arm's lines run along it, out from the bar, every one
    // anchored on the bar at its inner end. Scored by both ends alone, the lines lying on the anchor strip along the bar's
    // side won: every arm at 0 degrees, its lines across it over air landing on nothing at either end. Asked where undersides
    // are laid as bridges (outward); what wave overhangs leave to bridges keeps the score by both ends.
    "an area hanging off one side is laid outward from its supported edge, each arm by its own direction" {
        val below = listOf(rect(0, 0, 80_000, 8_000))
        fun arm(x: Double, deg: Double): List<Polygon> {
            val r = Math.toRadians(deg); val ux = Math.cos(r); val uy = Math.sin(r); val nx = -uy * 3_000; val ny = ux * 3_000
            val y0 = 4_000.0; val len = 28_000.0
            val q = Polygon(longArrayOf(Math.round(x - nx), Math.round(y0 - ny), Math.round(x + ux * len - nx), Math.round(y0 + uy * len - ny),
                Math.round(x + ux * len + nx), Math.round(y0 + uy * len + ny), Math.round(x + nx), Math.round(y0 + ny)))
            return plymeld.geom.Booleans.intersection(listOf(q), listOf(rect(-100_000, 7_200, 200_000, 100_000)))
        }
        for ((x, deg) in listOf(12_000.0 to 45.0, 40_000.0 to 90.0, 68_000.0 to 135.0)) {
            Bridges.bestAngle(arm(x, deg), below, 377, 400, outward = true) shouldBe deg
            // never a span: the default (waved) path still leaves it to wave overhangs
            Bridges.spanned(arm(x, deg), below, 377, 400) shouldBe false
            // outward off (what wave overhangs leave to bridges): the score by both ends alone, as before
            Bridges.bestAngle(arm(x, deg), below, 377, 400) shouldBe 0.0
        }
        // control: a span between two supports is bridged across it whether or not outward is asked
        val posts = listOf(rect(0, 0, 10_000, 40_000), rect(20_000, 0, 30_000, 40_000))
        Bridges.bestAngle(listOf(rect(8_000, 0, 22_000, 40_000)), posts, 377, 400, outward = true) shouldBe 0.0
        Bridges.bestAngle(listOf(rect(0, 8_000, 40_000, 22_000)), listOf(rect(0, 0, 40_000, 10_000), rect(0, 20_000, 40_000, 30_000)), 377, 400, outward = true) shouldBe 90.0
    }

    "control: with no support at all, no angle anchors anything and the score cannot favor 0°" {
        val bridge = listOf(rect(8_000, 0, 22_000, 40_000))
        // every line is unanchored: the choice falls to the shortest lines, which run across (0°)
        // only because the region is taller than wide — prove the scorer is not simply returning 0
        val wide = listOf(rect(0, 8_000, 40_000, 22_000))
        (Bridges.bestAngle(wide, emptyList(), 377, 400) == Bridges.bestAngle(bridge, emptyList(), 377, 400)) shouldBe false
    }
})
