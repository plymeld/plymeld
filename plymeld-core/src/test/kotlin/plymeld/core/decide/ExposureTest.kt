package plymeld.core.decide

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import plymeld.core.Seams
import plymeld.geom.Polygon

/**
 * What hides a seam nearby (Exposure). Two blocks 20 x 20 mm, 10 mm tall (50 layers of 0.2 mm),
 * 4 mm apart along x: A's right wall faces B across the gap, A's left wall faces nothing. Loops counter-clockwise in µm
 * (material on the left).
 */
class ExposureTest : StringSpec({
    fun mm(v: Double) = Math.round(v * 1000)
    fun rect(x0: Double, y0: Double, x1: Double, y1: Double) = Polygon(longArrayOf(mm(x0), mm(y0), mm(x1), mm(y0), mm(x1), mm(y1), mm(x0), mm(y1)))
    val a = rect(0.0, 0.0, 20.0, 20.0); val b = rect(24.0, 0.0, 44.0, 20.0)
    val layers = 50
    val tops = LongArray(layers) { (it + 1) * 200L }
    val sample = Exposure.Sample(16, 3, 60.0, 20_000, 250)
    fun exposure(outlines: List<Polygon>, s: Exposure.Sample = sample) = Exposure(List(layers) { outlines }, tops, s)

    "a wall facing a block 4 mm away is partly hidden; the same block's wall facing open air is not (the control)" {
        val e = exposure(listOf(a, b))
        val facingB = e.at(25, mm(20.0), mm(10.0), 1.0, 0.0)
        val open = e.at(25, mm(0.0), mm(10.0), -1.0, 0.0)
        open shouldBe 1.0
        facingB shouldBeLessThan 0.75
        facingB shouldBeGreaterThan 0.0
    }
    "without the neighbor, the wall that faced it is open: the block is what hides it" {
        exposure(listOf(a)).at(25, mm(20.0), mm(10.0), 1.0, 0.0) shouldBe 1.0
    }
    "near the top the rays rising over the neighbor get through: higher on the wall is more exposed than lower" {
        val e = exposure(listOf(a, b))
        e.at(48, mm(20.0), mm(10.0), 1.0, 0.0) shouldBeGreaterThan e.at(5, mm(20.0), mm(10.0), 1.0, 0.0)
    }
    "a reach of 0: nothing hides anything" {
        exposure(listOf(a, b), Exposure.Sample(16, 3, 60.0, 0, 250)).at(25, mm(20.0), mm(10.0), 1.0, 0.0) shouldBe 1.0
    }
    // a seam close behind a thin visible wall shows through it (a part's seam 1.4 mm behind a visible wall printed
    // as two faint lines while the map counted it hidden): A's right wall behind a 0.8 mm shell 0.6 mm away
    "behind a thin shell close in front, a wall is partly seen with show_through; with show_through 0, or behind a thick block, not at all" {
        val shell = rect(20.6, 0.0, 21.4, 20.0); val block = rect(20.6, 0.0, 25.6, 20.0)
        fun seen(front: Polygon, showUm: Long) = exposure(listOf(a, front), Exposure.Sample(16, 3, 60.0, 20_000, 250, showUm)).at(25, mm(20.0), mm(10.0), 1.0, 0.0)
        val thin = seen(shell, 2_000)
        thin shouldBeGreaterThan 0.0
        thin shouldBeLessThan 0.5
        seen(shell, 0) shouldBe 0.0
        seen(block, 2_000) shouldBe 0.0
        // material entered within the show-through depth but left past it hides the seam, never less than nothing (
        // a shell 1.6 to 2.0 mm out, left by the ray's step at 2.125 mm, read 1 - 2.125 / 2 < 0, and the least-visible
        // placement sought such points out: one model's base seam scar summed to -9.2 mm²): the one level ray straight out
        // (4 directions around, the wall faces one) reads 0; control: the thin shell ending within the depth shows through
        val past = rect(21.6, 0.0, 22.0, 20.0)
        fun level(front: Polygon) = exposure(listOf(a, front), Exposure.Sample(4, 1, 0.0, 20_000, 250, 2_000)).at(25, mm(20.0), mm(10.0), 1.0, 0.0)
        level(past) shouldBe 0.0
        level(shell) shouldBeGreaterThan 0.0
        for (z in listOf(5, 25, 45)) for (front in listOf(past, shell, block))
            exposure(listOf(a, front), Exposure.Sample(16, 3, 60.0, 20_000, 250, 2_000)).at(z, mm(20.0), mm(10.0), 1.0, 0.0) shouldBeGreaterThan -1e-12
    }
    // a seam's viewing directions weighed by the quadrant they look from (front 8, left and
    // right 2 each, back 1; with no view set, every side alike)
    val quadrants = Visibility.Quadrants(8.0, 2.0, 2.0, 1.0)
    "a direction counts its quadrant's weight as the viewer at the front sees them; one on a quadrant's edge the mean of the two" {
        val look = Visibility(1.0, 1.0, "test", view = Visibility.viewOf("front"), quadrants = quadrants).look!!
        val e = Math.sqrt(0.5)
        look.weight(0.0, -1.0) shouldBe 8.0          // toward the viewer (the printer's front: small y)
        look.weight(1.0, 0.0) shouldBe 2.0           // the viewer's right (+x)
        look.weight(-1.0, 0.0) shouldBe 2.0          // the viewer's left
        look.weight(0.0, 1.0) shouldBe 1.0           // the back
        look.weight(e, -e) shouldBe 5.0              // front / right edge
        look.weight(-e, e) shouldBe 1.5              // back / left edge
        // from the right, the viewer's front is +x; from above or all around there are no quadrants
        val right = Visibility(1.0, 1.0, "test", view = Visibility.viewOf("right"), quadrants = Visibility.Quadrants(8.0, 3.0, 2.0, 1.0)).look!!
        right.weight(1.0, 0.0) shouldBe 8.0
        right.weight(0.0, -1.0) shouldBe 3.0         // looking along -x, the viewer's left is -y
        right.weight(0.0, 1.0) shouldBe 2.0          // and their right +y
        Visibility(1.0, 1.0, "test", view = Visibility.viewOf("above"), quadrants = quadrants).look shouldBe null
        Visibility(1.0, 1.0, "test", quadrants = quadrants).look shouldBe null
    }
    "seen from the front, an open block's front wall reads 1, its sides and back their weighted facing over the front's; from all around every open wall reads 1 (the control)" {
        val look = Visibility(1.0, 1.0, "test", view = Visibility.viewOf("front"), quadrants = quadrants).look
        val e = Exposure(List(layers) { listOf(a) }, tops, sample, look)
        // by hand: 16 directions around, the ones a wall faces at 0, +-22.5, +-45, +-67.5 deg off its normal (cos 1, c1, c2, c3;
        // 90 deg faces nothing); each height scales every direction alike, so it cancels
        val c1 = Math.cos(Math.toRadians(22.5)); val c2 = Math.cos(Math.toRadians(45.0)); val c3 = Math.cos(Math.toRadians(67.5))
        val front = 8 + 2 * 8 * c1 + 2 * 5 * c2 + 2 * 2 * c3              // front, front, front / side edge, side
        val back = 1 + 2 * 1 * c1 + 2 * 1.5 * c2 + 2 * 2 * c3              // back, back, back / side edge, side
        val side = 2 + 2 * 2 * c1 + (5 + 1.5) * c2 + (8 + 1) * c3          // side, side, the two edges, front and back
        e.at(25, mm(10.0), mm(0.0), 0.0, -1.0) shouldBe (1.0 plusOrMinus 1e-12)
        e.at(25, mm(10.0), mm(20.0), 0.0, 1.0) shouldBe (back / front plusOrMinus 1e-12)
        e.at(25, mm(20.0), mm(10.0), 1.0, 0.0) shouldBe (side / front plusOrMinus 1e-12)
        e.at(25, mm(0.0), mm(10.0), -1.0, 0.0) shouldBe (side / front plusOrMinus 1e-12)
        (back / front) shouldBeLessThan (side / front)
        val around = Exposure(List(layers) { listOf(a) }, tops, sample, null)
        for ((x, y, n) in listOf(Triple(10.0, 0.0, 0.0 to -1.0), Triple(10.0, 20.0, 0.0 to 1.0), Triple(20.0, 10.0, 1.0 to 0.0)))
            around.at(25, mm(x), mm(y), n.first, n.second) shouldBe (1.0 plusOrMinus 1e-12)
    }
    "the least-visible seam on an open block seen from the front goes to its back; from all around, its front and back read alike (the control)" {
        val p = Polygon(longArrayOf(mm(0.0), mm(0.0), mm(10.0), mm(0.0), mm(20.0), mm(0.0), mm(20.0), mm(10.0), mm(20.0), mm(20.0),
            mm(10.0), mm(20.0), mm(0.0), mm(20.0), mm(0.0), mm(10.0)))
        val vis = Visibility(1.0, 1.0, "test", view = Visibility.viewOf("front"), quadrants = quadrants)
        val e = Exposure(List(layers) { listOf(p) }, tops, sample, vis.look)
        val placed = Seams.place(p, 30.0, 880.0, leastVisible = true, vis = vis, cornerShare = 1.0, exposure = e, layer = 25).loop
        placed.y(0) shouldBe mm(20.0)
        val ex = Exposure(List(layers) { listOf(p) }, tops, sample, null).ofLoop(25, p)
        ex[1] shouldBe (ex[5] plusOrMinus 1e-12)     // mid-front and mid-back
    }
    "the least-visible seam goes to the middle of the wall facing the neighbor; without the exposure it does not (the control)" {
        // A with a vertex in the middle of each side, so the placement has a mid-wall point on every side
        val p = Polygon(longArrayOf(mm(0.0), mm(0.0), mm(10.0), mm(0.0), mm(20.0), mm(0.0), mm(20.0), mm(10.0), mm(20.0), mm(20.0),
            mm(10.0), mm(20.0), mm(0.0), mm(20.0), mm(0.0), mm(10.0)))
        val e = exposure(listOf(p, b))
        val vis = Visibility(1.0, 1.0, "test")
        val with = Seams.place(p, 30.0, 880.0, leastVisible = true, vis = vis, cornerShare = 1.0, exposure = e, layer = 25).loop
        (with.x(0) to with.y(0)) shouldBe (mm(20.0) to mm(10.0))
        val without = Seams.place(p, 30.0, 880.0, leastVisible = true, vis = vis, cornerShare = 1.0).loop
        (without.x(0) to without.y(0)) shouldNotBe (mm(20.0) to mm(10.0))
    }
})
