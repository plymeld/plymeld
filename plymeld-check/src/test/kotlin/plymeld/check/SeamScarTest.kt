package plymeld.check

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import plymeld.core.Feature
import plymeld.core.LayerPlanOut
import plymeld.core.SeamScar
import plymeld.core.ToolPath
import plymeld.core.decide.Visibility

/** The seam measure (SeamScar) on hand-made layers. Lengths in µm; a line 0.4 mm wide, layers 0.2 mm. */
class SeamScarTest : StringSpec({
    val w = 400L; val h = 200L
    /** A closed square outer wall of side [side] mm at ([x0], [y0]) mm, its seam at its first corner, scarfed over [scarfMm]. */
    fun square(x0: Double, y0: Double, side: Double, scarfMm: Double = 0.0): ToolPath {
        fun u(v: Double) = Math.round(v * 1000)
        val xy = longArrayOf(u(x0), u(y0), u(x0 + side), u(y0), u(x0 + side), u(y0 + side), u(x0), u(y0 + side))
        return ToolPath(Feature.OUTER_WALL, xy, true, w, h, scarfUm = Math.round(scarfMm * 1000), scarfStart = if (scarfMm > 0) 0.25 else 0.0)
    }
    fun layer(i: Int, vararg paths: ToolPath) = LayerPlanOut(i, (i + 1) * h, h, paths.toList())
    val map = Visibility(0.3, 0.0, "test")
    // the shares: a scarf 0.25 of a point seam; no corner credit (null corner angle) unless a test says so
    val noCorner = SeamScar.Shares(0.25, 0.25, null)
    fun measure(layers: List<LayerPlanOut>, v: Visibility?, shares: SeamScar.Shares = noCorner) = SeamScar.measure(layers, v, shares)

    // curved islands: an island's own layers are printed among the flat ones, each stack's seams in line with its own layer below
    // (read against the layer printed before, the 3DBenchy with a bow island measured 183 mm2 against 45.6 flat)
    "two stacks printed in turn, each seam in line with its own: no jog read by stack; control: read in print order, each jogs to the other" {
        val stackA = { i: Int -> layer(i, square(0.0, 0.0, 10.0)) }
        val stackB = { i: Int -> layer(i, square(30.0, 0.0, 10.0)) }
        val layers = listOf(stackA(0), stackB(1), stackA(2), stackB(3), stackA(4), stackB(5))
        val byStack = SeamScar.measure(layers, map, noCorner, stackOf = { i -> if (i % 2 == 0) -1 else 0 })   // A the flat layers, B an island
        // B's first layer starts on A's (no layer of its own below yet): 30 mm; nothing else jogs
        byStack.jogMm shouldBe (30.0 plusOrMinus 1e-6)
        val printOrder = SeamScar.measure(layers, map, noCorner)
        printOrder.jogMm shouldBe (5 * 30.0 plusOrMinus 1e-6)
    }

    "two aligned point seams on a wall: one line width squared each, seen in full" {
        val r = measure(listOf(layer(0, square(0.0, 0.0, 10.0)), layer(1, square(0.0, 0.0, 10.0))), map)
        r.seams shouldBe 2; r.pointSeams shouldBe 2; r.scarfSeams shouldBe 0
        r.jogMm shouldBe (0.0 plusOrMinus 1e-9)
        r.scarMm2 shouldBe (2 * 0.4 * 0.4 plusOrMinus 1e-9)
    }

    // a seam on a shallow slope shows from above as a band across the shelf it sits on (a lying pin's top printed
    // its seams as a trail): a 6 mm square set 2 mm in on a 10 mm one, its seam at (2, 2) on a shelf 2 - 0.2 = 1.8 mm wide
    "a point seam on a shelf counts its width across the shelf; a scarf on it its ramp across the shelf; without sampling, as before (the control)" {
        val sampled = Visibility(0.3, 0.0, "test", sample = plymeld.core.decide.Exposure.Sample(16, 3, 60.0, 20_000, 250))
        val jog = Math.hypot(2.0, 2.0) * 0.4
        val point = measure(listOf(layer(0, square(0.0, 0.0, 10.0)), layer(1, square(2.0, 2.0, 6.0))), sampled)
        point.scarMm2 shouldBe (0.16 + 0.4 * 1.8 + jog plusOrMinus 1e-3)
        val scarf = measure(listOf(layer(0, square(0.0, 0.0, 10.0)), layer(1, square(2.0, 2.0, 6.0, scarfMm = 10.0))), sampled)
        scarf.scarMm2 shouldBe (0.16 + 0.16 * 0.25 + 10 * (1.8 - 0.4) + jog plusOrMinus 1e-3)
        measure(listOf(layer(0, square(0.0, 0.0, 10.0)), layer(1, square(2.0, 2.0, 6.0))), map).scarMm2 shouldBe (0.16 + 0.16 + jog plusOrMinus 1e-3)
    }
    "a scarf pays on a wall and not on a shelf wider than a line" {
        SeamScar.scarfPays(0.4, 0.3, 0.25, 10.0) shouldBe true
        SeamScar.scarfPays(0.4, 1.8, 0.25, 10.0) shouldBe false
        SeamScar.scarfPays(0.4, 0.45, 0.0, 10.0) shouldBe false
    }

    "a scarf seam counts the scarf share of a point seam's blemish, whatever its length" {
        val r = measure(listOf(layer(0, square(0.0, 0.0, 10.0)), layer(1, square(0.0, 0.0, 10.0, scarfMm = 10.0))), map)
        r.scarfSeams shouldBe 1
        r.scarMm2 shouldBe (0.16 + 0.16 * 0.25 plusOrMinus 1e-9)
        measure(listOf(layer(0, square(0.0, 0.0, 10.0)), layer(1, square(0.0, 0.0, 10.0, scarfMm = 3.0))), map).scarMm2 shouldBe (r.scarMm2 plusOrMinus 1e-9)
    }

    "a seam that jogs from the one below adds the jog x the line width; a seam on an overhanging edge counts as an underside" {
        // the second layer's square steps out 2 mm in x: its seam corner hangs 2 mm over the layer below (1.8 past the
        // wall's half width), an underside leaning 1.8 / hypot(1.8, 0.2) down, and the seam moved 2 mm from the one below
        val r = measure(listOf(layer(0, square(0.0, 0.0, 10.0)), layer(1, square(-2.0, 0.0, 10.0))), map)
        val out = 2.0 - 0.2
        val v = map.direction(out / Math.hypot(out, 0.2))
        (v < 0.35 && v > 0.3) shouldBe true
        r.jogMm shouldBe (2.0 plusOrMinus 1e-9)
        r.scarMm2 shouldBe (0.16 + (0.16 + 2.0 * 0.4) * v plusOrMinus 1e-9)
        // the control: every seam alike (no map) counts the same blemish in full
        measure(listOf(layer(0, square(0.0, 0.0, 10.0)), layer(1, square(-2.0, 0.0, 10.0))), null).scarMm2 shouldBe (0.16 + 0.16 + 0.8 plusOrMinus 1e-9)
    }

    "a seam the layer below holds up (a step in, a sloped top) is seen in full" {
        val r = measure(listOf(layer(0, square(0.0, 0.0, 10.0)), layer(1, square(1.0, 1.0, 8.0))), map)
        // the jog: the corner moved by hypot(1, 1) = 1.41 mm > w
        r.scarMm2 shouldBe (0.16 + 0.16 + Math.hypot(1.0, 1.0) * 0.4 plusOrMinus 1e-9)
    }

    "a point seam in a corner counts the corner share; the same seam on a smooth wall counts in full" {
        val corners = SeamScar.Shares(0.25, 0.25, 30.0)
        // the square's seam sits at a 90 deg corner
        measure(listOf(layer(0, square(0.0, 0.0, 10.0))), null, corners).scarMm2 shouldBe (0.16 * 0.25 plusOrMinus 1e-9)
        // a 64-gon's seam turns 5.6 deg at its vertex: no corner
        val n = 64; val xy = LongArray(2 * n) { i -> val a = 2 * Math.PI * (i / 2) / n; if (i % 2 == 0) Math.round(10000 + 5000 * Math.cos(a)) else Math.round(10000 + 5000 * Math.sin(a)) }
        val ring = ToolPath(Feature.OUTER_WALL, xy, true, w, h)
        measure(listOf(layer(0, ring)), null, corners).scarMm2 shouldBe (0.16 plusOrMinus 1e-9)
    }

    // a seam's viewing directions weighed by quadrant (front 8, left and right 2, back 1): an open
    // wall facing the viewer reads 1, one facing the back its weighted facing over the front's (worked by hand in ExposureTest:
    // 16 directions around, the facing ones 0, +-22.5, +-45 (a quadrant's edge: the mean), +-67.5 deg off the wall's normal)
    "seen from the front, a seam mid-front counts in full and one mid-back counts the back's quadrant weighting; from all around, both in full (the control)" {
        val sample = plymeld.core.decide.Exposure.Sample(16, 3, 60.0, 20_000, 250)
        val front = Visibility(0.3, 0.0, "test", view = Visibility.viewOf("front"), sample = sample, quadrants = Visibility.Quadrants(8.0, 2.0, 2.0, 1.0))
        val around = Visibility(0.3, 0.0, "test", sample = sample, quadrants = Visibility.Quadrants(8.0, 2.0, 2.0, 1.0))
        fun u(v: Double) = Math.round(v * 1000)
        // the 10 mm square counter-clockwise, started mid-front (5, 0): both edges there run +x, the outward normal -y, toward the viewer
        val atFront = ToolPath(Feature.OUTER_WALL, longArrayOf(u(5.0), u(0.0), u(10.0), u(0.0), u(10.0), u(10.0), u(0.0), u(10.0), u(0.0), u(0.0)), true, w, h)
        // started mid-back (5, 10): both edges run -x, the outward normal +y, the back
        val atBack = ToolPath(Feature.OUTER_WALL, longArrayOf(u(5.0), u(10.0), u(0.0), u(10.0), u(0.0), u(0.0), u(10.0), u(0.0), u(10.0), u(10.0)), true, w, h)
        measure(listOf(layer(0, atFront)), front).scarMm2 shouldBe (0.16 plusOrMinus 1e-9)
        val c1 = Math.cos(Math.toRadians(22.5)); val c2 = Math.cos(Math.toRadians(45.0)); val c3 = Math.cos(Math.toRadians(67.5))
        val frontSum = 8 + 2 * 8 * c1 + 2 * 5 * c2 + 2 * 2 * c3; val backSum = 1 + 2 * 1 * c1 + 2 * 1.5 * c2 + 2 * 2 * c3
        measure(listOf(layer(0, atBack)), front).scarMm2 shouldBe (0.16 * backSum / frontSum plusOrMinus 1e-9)
        measure(listOf(layer(0, atFront)), around).scarMm2 shouldBe (0.16 plusOrMinus 1e-9)
        measure(listOf(layer(0, atBack)), around).scarMm2 shouldBe (0.16 plusOrMinus 1e-9)
    }

    "the step out of a point past the layer below's walls" {
        val below = listOf(plymeld.geom.Polygon(square(0.0, 0.0, 10.0).xy))
        SeamScar.stepOutMm(5000, 5000, below, 0.2) shouldBe (0.0 plusOrMinus 1e-9)          // inside
        SeamScar.stepOutMm(10100, 5000, below, 0.2) shouldBe (0.0 plusOrMinus 1e-9)         // within half a line of the wall
        SeamScar.stepOutMm(12000, 5000, below, 0.2) shouldBe (1.8 plusOrMinus 1e-9)         // 2 mm out
        SeamScar.stepOutMm(12000, 12000, below, 0.2) shouldBe (Math.hypot(2.0, 2.0) - 0.2 plusOrMinus 1e-9)   // past a corner
    }
})
