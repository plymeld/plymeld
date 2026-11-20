package plymeld.core

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import plymeld.geom.Polygon

/**
 * A stub at a junction: a short dead end from it that the line would run out along and back, 40 to 150 microns, a zigzag
 * in the outer line where it lies at the surface. Two synthetic areas (µm), each laid without its stub and measured by
 * IslandCheck: no spot bare wider than the floor.
 *  - A cell 0.52 x 0.58 mm between walls, a thin area laid by its center line (Walls): pruning cuts its corner forks,
 *    which would leave less of it than its widest line, so two forks stay. Those were the two longest of the forks cut, a
 *    short trunk among them whose own forks went, and the line ran down the trunk and back; the area's two longest forks
 *    stay instead. Control: the two longest of those cut (trunksGo off) lays the trunk out and back.
 *  - An L of a strip 0.34 mm wide (arms 2 mm) with a stub 0.1 mm long at its corner, laid by its beads (Beads, one bead
 *    across): the stub's piece from the middle line's junction was joined with one arm, and the other arm's end then joined
 *    its dead end. It is left out. Control: joined as any piece (stubsGiveWay off), out and back.
 * Each line laid carries the line as it was joined with its stub (CenterLine.withStubs, what the rule for a dot beside
 * another part reads), which turns back where the stub was.
 */
class JunctionStubTest : StringSpec({
    val minBead = 300L; val minFeature = 150L; val tol = 13L
    val floor = minFeature + 2 * tol
    val flank = 80 * (1 - Math.PI / 4)
    // the model's outline, far from the pieces: they lie between walls (where a bare spot is a hole)
    val farOutline = listOf(Polygon(longArrayOf(-5000, -5000, 10000, -5000, 10000, 10000, -5000, 10000)))
    /** The vertices where a line turns back on itself: its two moves there more than 150 degrees apart. */
    fun backTurns(c: CenterLine): Int {
        var n = 0
        for (k in 1 until c.xy.size / 2 - 1) {
            val ax = (c.xy[2 * k] - c.xy[2 * k - 2]).toDouble(); val ay = (c.xy[2 * k + 1] - c.xy[2 * k - 1]).toDouble()
            val bx = (c.xy[2 * k + 2] - c.xy[2 * k]).toDouble(); val by = (c.xy[2 * k + 3] - c.xy[2 * k + 1]).toDouble()
            val la = Math.hypot(ax, ay); val lb = Math.hypot(bx, by)
            if (la > 0 && lb > 0 && (ax * bx + ay * by) / (la * lb) < Math.cos(Math.toRadians(150.0))) n++
        }
        return n
    }
    fun measure(area: Polygon, ls: List<CenterLine>) = IslandCheck.measure(listOf(area),
        ls.map { IslandCheck.Line(it.xy, DoubleArray(it.widths.size) { k -> it.widths[k].toDouble() }, it.xy[0] == it.xy[it.xy.size - 2] && it.xy[1] == it.xy[it.xy.size - 1]) },
        emptyList(), flank, floor.toDouble(), tol.toDouble())
    fun show(name: String, ls: List<CenterLine>, m: IslandCheck.Measure) {
        println("$name: ${ls.size} line(s), ${ls.sumOf { backTurns(it) }} turning back; $m")
        for (c in ls) println("  line: " + (0 until c.xy.size / 2).joinToString(" ") { "(${c.xy[2 * it]},${c.xy[2 * it + 1]};${c.widths[it]})" })
    }
    // the hole rule's limit, read a cell loose
    fun bare(m: IslandCheck.Measure) = m.widestUm > floor + m.cellUm

    val cell = Polygon(longArrayOf(0, 0, 520, 0, 520, 580, 0, 580))
    fun cellLines(trunksGo: Boolean) = Walls.centerLines(listOf(cell), minBead, minFeature, 1000, 2.0, 300, farOutline, minFeature, floor, 0.25,
        trunksGo = trunksGo)
    "a cell's center line does not run out along the trunk at its junction and back; nothing bare" {
        val ls = cellLines(true)
        val m = measure(cell, ls)
        show("cell", ls, m)
        ls.size shouldBe 1
        ls.sumOf { backTurns(it) } shouldBe 0
        bare(m) shouldBe false
        // the line as it was joined with the trunk, for the dot rule
        val joined = ls[0].withStubs
        joined shouldNotBe null
        (backTurns(joined!!) > 0) shouldBe true
    }
    "control: the cell's two longest forks of those cut keep the trunk, laid out and back" {
        val ls = cellLines(false)
        show("cell control", ls, measure(cell, ls))
        (ls.sumOf { backTurns(it) } > 0) shouldBe true
        ls.all { it.withStubs == null } shouldBe true
    }

    // the L: arms along +x and -y from a corner square 340 wide, a stub 100 up from the corner's top edge
    val ell = Polygon(longArrayOf(-170, -2170, 170, -2170, 170, -170, 2170, -170, 2170, 170, 170, 170, 170, 270, -170, 270, -170, 170, -170, -170))
    val rule = Beads.Rule(440.0, 440.0, 3, flank, minBead.toDouble(), 1000.0)
    fun ellLines(on: Boolean) = Beads.lay(listOf(ell), Beads.Params(rule, minFeature, 300, tol, 75, 0.25, 2.0, stubsGiveWay = on))
    "an L laid by its beads: its middle line does not run out along the stub at its corner and back; nothing bare" {
        val ls = ellLines(true)
        val m = measure(ell, ls)
        show("L", ls, m)
        ls.sumOf { backTurns(it) } shouldBe 0
        bare(m) shouldBe false
        val joined = ls.mapNotNull { it.withStubs }
        joined.size shouldBe 1
        (backTurns(joined[0]) > 0) shouldBe true
    }
    "control: the L's stub joined as any piece is laid out and back" {
        val ls = ellLines(false)
        show("L control", ls, measure(ell, ls))
        (ls.sumOf { backTurns(it) } > 0) shouldBe true
    }
})
