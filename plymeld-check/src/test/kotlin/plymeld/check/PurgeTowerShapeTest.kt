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
        // the plain tower's: its wall, its purge, and that rest), reaching to the band's far end
        val paths = { t: PurgeTower -> t.layer(100, 1).purges.single() }
        paths(min).size shouldBe paths(plain).size + 1
        val rest = paths(min).last()
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
})
