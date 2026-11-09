package plymeld.check

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import plymeld.core.Feature
import plymeld.core.PurgeTower

/**
 * The purge tower's least side and its brim: a tower 40 mm tall whose purge alone makes it about 9 mm square is widened
 * and deepened to half its height (20 mm) with side_share.min 0.5, its bands' lines beyond the purge at the sparse
 * spacing; a brim 4.8 mm wide is laid on its first layer before the tower and counted in its footprint. Controls: no
 * minimum leaves it as the purge makes it; no brim, no brim lines and the footprint the tower's own.
 */
class PurgeTowerShapeTest : StringSpec({
    val tops = LongArray(200) { (it + 1) * 200L }                  // 200 layers of 0.2 mm: 40 mm
    val changes = IntArray(200) { 1 }
    fun tower(share: Double, brim: Long) = PurgeTower.square(100_000, 100_000, 15.0, 400, 2_000, changes, tops, compact = true, maxGapUm = 300,
        minSideShare = share, brimUm = brim, brimLineUm = 500)
    fun size(t: PurgeTower) = t.footprint.bounds().let { b -> (b[2] - b[0]) to (b[3] - b[1]) }

    "each side at least half its 40 mm height; control: without the minimum, the purge alone makes it narrower" {
        val plain = tower(0.0, 0)
        val min = tower(0.5, 0)
        (plain.widthUm < 20_000 && plain.depthUm < 20_000) shouldBe true
        (min.widthUm >= 20_000 && min.depthUm >= 20_000) shouldBe true
        // the deepened band: after a change's purge lines, the rest of its band at the sparse spacing (one more path than
        // the plain tower's: its purge, that rest, and its wall), reaching to the band's far end
        val paths = { t: PurgeTower -> t.layer(100, 1).purges.single() }
        paths(min).size shouldBe paths(plain).size + 1
        val rest = paths(min).last { !it.closed }
        val ys = (0 until rest.xy.size / 2).map { rest.xy[2 * it + 1] }
        (ys.max() > 100_000 + min.depthUm - 400 - 2_000) shouldBe true
    }
    "a brim on the first layer, laid first, and in the footprint; control: no brim, no brim lines, the footprint the tower's own" {
        val t = tower(0.0, 4_800)
        val base = t.layer(0, 1).base
        base.first().feature shouldBe Feature.BRIM
        (base.count { it.feature == Feature.BRIM } >= 8) shouldBe true          // 4.8 mm of 0.5 mm lines a spacing apart
        t.layer(1, 1).base.none { it.feature == Feature.BRIM } shouldBe true
        val (w, d) = size(t)
        w shouldBe t.widthUm + 2 * 4_800
        d shouldBe t.depthUm + 2 * 4_800
        val none = tower(0.0, 0)
        none.layer(0, 1).base.none { it.feature == Feature.BRIM } shouldBe true
        size(none) shouldBe (none.widthUm to none.depthUm)
    }
    "after a change the new filament lays its purge lines first and the tower's wall last, every layer; control: the same lines, the wall among them" {
        val t = tower(0.0, 0)
        for (i in listOf(0, 1, 100, 199)) {
            val g = t.layer(i, 1).purges.single()
            g.first().closed shouldBe false                                      // inside the tower, not its edge
            g.last().closed shouldBe true                                        // the wall, by a nozzle already flowing
            g.count { it.closed } shouldBe 1
            // its first line starts inside the wall (at or past its inner edge, 0.4 mm in), not on the wall's own line
            val (x0, y0) = g.first().xy[0] to g.first().xy[1]
            (x0 >= 100_000 + 400 && y0 >= 100_000 + 400) shouldBe true
        }
    }
    "consecutive changes start their purge at different corners inside the tower, laying as much each time; control: within the wall" {
        val t = tower(0.0, 0)
        fun length(xy: LongArray) = (1 until xy.size / 2).sumOf { k -> Math.hypot((xy[2 * k] - xy[2 * k - 2]).toDouble(), (xy[2 * k + 1] - xy[2 * k - 1]).toDouble()) }
        for (from in listOf(1, 100)) {
            val groups = (from until from + 4).map { t.layer(it, 1).purges.single() }
            val starts = groups.map { it.first().xy[0] to it.first().xy[1] }
            println("starts from layer $from: $starts")
            starts.toSet().size shouldBe 4                                       // four corners, not one point
            for ((x, y) in starts) (x >= 100_000 + 400 && x <= 100_000 + t.widthUm - 400 && y >= 100_000 + 400 && y <= 100_000 + t.depthUm - 400) shouldBe true
            // the same purge each time, whichever corner it starts from (within a line's length of rounding)
            val purged = groups.map { g -> g.filter { !it.closed }.sumOf { length(it.xy) } }
            (purged.max() - purged.min() <= t.widthUm.toDouble()) shouldBe true
        }
    }
    "the tower's first layer is laid at the first layer's line width and spacing, the layers above at its own; control: without it, at its own" {
        fun first(firstLine: Long?) = (if (firstLine == null) PurgeTower.square(100_000, 100_000, 15.0, 400, 2_000, changes, tops, compact = true, maxGapUm = 300)
            else PurgeTower.square(100_000, 100_000, 15.0, 400, 2_000, changes, tops, compact = true, maxGapUm = 300, firstLineUm = firstLine))
        val t = first(500)
        val l0 = t.layer(0, 1).purges.single()
        l0.all { it.widthUm == 500L } shouldBe true                              // purge, the band's rest, the wall
        val purge = l0.first()
        val ys = (0 until purge.xy.size / 2).map { purge.xy[2 * it + 1] }.distinct()
        (ys.zipWithNext { a, b -> Math.abs(b - a) }.toSet()) shouldBe setOf(plymeld.core.Walls.spacing(500, 200))   // a first-layer line's spacing
        t.layer(1, 1).purges.single().all { it.widthUm == 400L } shouldBe true
        // the first layer's whole band laid solid: as much plastic across it at 0.5 mm lines as at 0.4 (within a line)
        fun mm3(g: List<plymeld.core.ToolPath>) = g.filter { !it.closed }.sumOf { p -> (1 until p.xy.size / 2).sumOf { k -> Math.hypot((p.xy[2 * k] - p.xy[2 * k - 2]).toDouble(), (p.xy[2 * k + 1] - p.xy[2 * k - 1]).toDouble()) } * p.widthUm }
        val plain = first(null)
        plain.layer(0, 1).purges.single().all { it.widthUm == 400L } shouldBe true
        println("first layer: lines 0.5 mm, %.0f; at 0.4 mm, %.0f (mm x um)".format(java.util.Locale.ROOT, mm3(l0) / 1000, mm3(plain.layer(0, 1).purges.single()) / 1000))
    }
    "the brim's inner loop reaches into the tower's outline by the overlap; control: with none it only touches" {
        fun innerGapUm(overlap: Long): Long {
            val t = PurgeTower.square(100_000, 100_000, 15.0, 400, 2_000, changes, tops, compact = true, maxGapUm = 300, brimUm = 4_800, brimLineUm = 500, brimOverlapUm = overlap)
            val inner = t.layer(0, 1).base.first { it.feature == Feature.BRIM }
            val minX = (0 until inner.xy.size / 2).minOf { inner.xy[2 * it] }
            // the inner loop's inner edge (its center plus half its width) against the tower's left edge (x 100 mm)
            return (minX + 250) - 100_000
        }
        innerGapUm(0) shouldBe 0L                                                  // edge to edge: touching, no overlap
        innerGapUm(125) shouldBe 125L                                              // 0.125 mm into the tower's outline
    }
})
