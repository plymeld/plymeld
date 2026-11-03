package plymeld.check

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.shouldBeGreaterThanOrEqual
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.shouldBe
import plymeld.core.PurgeTower
import plymeld.core.ToolPath
import java.util.Locale

private fun len(p: ToolPath): Double { var l = 0.0; val m = p.xy.size / 2; val segs = if (p.closed) m else m - 1
    for (k in 0 until segs) { val j = (k + 1) % m; l += Math.hypot((p.xy[2 * j] - p.xy[2 * k]).toDouble(), (p.xy[2 * j + 1] - p.xy[2 * k + 1]).toDouble()) }; return l / 1000.0 }

/** Multi-color step 4: the purge tower's geometry, with values from the Flash Studio settings of a 4-color print (30 mm³, 35 mm wide). */
class PurgeTowerTest : StringSpec({
    val tops = LongArray(20) { 200L * (it + 1) }                    // 0.2 mm layers
    val changes = IntArray(20) { if (it < 15) (if (it % 3 == 0) 3 else 1) else 0 }.also { it[5] = 0 }
    val t = PurgeTower(165_000, 204_365, 35_000, 30.0, 400, 4_070, changes, tops)

    "each change purges at least its volume: length >= volume / (width x height), and less than one line more than needed" {
        for (i in 0..t.top) {
            val l = t.layer(i, changes[i])
            l.purges.size shouldBe changes[i]
            for (g in l.purges) {
                val mm = g.sumOf(::len)
                mm shouldBeGreaterThanOrEqual 30.0 / (0.4 * 0.2)            // model-free: a bead is at most w x h in section
                val bead = 0.2 * (0.4 - 0.2 * (1 - Math.PI / 4))
                (mm * bead) shouldBeGreaterThanOrEqual 30.0
                (mm * bead - 30.0) shouldBeLessThan (35.0 - 0.8) * bead      // under one more line
            }
        }
        println("  tower: %.1f x %.1f mm, top layer %d; one purge = %d lines".format(Locale.ROOT, 35.0, t.depthUm / 1000.0, t.top, t.purgeLines(1)))
    }

    "every tower path stays inside the footprint, and bands do not overlap" {
        for (i in 0..t.top) {
            val l = t.layer(i, changes[i])
            val all = l.base + l.purges.flatten()
            for (p in all) for (k in 0 until p.xy.size / 2) {
                (p.xy[2 * k] in 165_000L..200_000L) shouldBe true
                (p.xy[2 * k + 1] in 204_365L..(204_365L + t.depthUm)) shouldBe true
            }
            val ranges = (l.purges.flatten() + l.base.filter { !it.closed }).map { p -> (0 until p.xy.size / 2).minOf { p.xy[2 * it + 1] }..(0 until p.xy.size / 2).maxOf { p.xy[2 * it + 1] } }
            for (a in ranges.indices) for (b in a + 1 until ranges.size) (ranges[a].last < ranges[b].first || ranges[b].last < ranges[a].first) shouldBe true
        }
    }

    "a layer without a change keeps the (banded) tower going: wall and lines in every band" {
        val l = t.layer(5, 0)
        l.purges.size shouldBe 0
        l.base.count { !it.closed } shouldBe 3                             // 3 bands (the most changes on a layer)
        len(l.base.single { it.closed }) shouldBeGreaterThanOrEqual 2 * (35.0 - 0.4)
        // and it stops after the last change
        t.top shouldBe 14
        shouldThrow<IllegalArgumentException> { t.layer(15, 0) }
    }

    // ---- compact layout: 0.08 mm layers and a 0.28 mm max gap, as in that Flash Studio settings block ----
    val tops8 = LongArray(120) { 80L * (it + 1) }
    val changes8 = IntArray(120) { if (it % 7 == 0) 2 else if (it % 5 == 0) 1 else 0 }.also { it[119] = 1 }
    val compact = PurgeTower(165_000, 204_365, 35_000, 30.0, 400, 4_070, changes8, tops8, compact = true, maxGapUm = 280)
    val banded = PurgeTower(165_000, 204_365, 35_000, 30.0, 400, 4_070, changes8, tops8)
    fun bead(p: ToolPath) = p.heightUm / 1000.0 * (0.4 - p.heightUm / 1000.0 * (1 - Math.PI / 4))

    "compact: each change still purges its volume, counting the wall it prints first, at the height actually printed" {
        for (i in 0..compact.top) for (g in compact.layer(i, changes8[i]).purges) {
            val mm3 = g.sumOf { len(it) * bead(it) }
            mm3 shouldBeGreaterThanOrEqual 30.0
        }
    }

    "compact: every tower path lands on something printed earlier under it (or the bed), never over a gap above 0.28 mm" {
        class Printed(val y: LongRange, val z: Long)
        val done = ArrayList<Printed>()
        for (i in 0..compact.top) {
            val l = compact.layer(i, changes8[i])
            for (p in l.base + l.purges.flatten()) {
                p.heightUm shouldBeLessThan_ 280
                val bottom = tops8[i] - p.heightUm
                val ys = (0 until p.xy.size / 2).map { p.xy[2 * it + 1] }
                val y = if (p.closed) ys.min()..ys.max() else ys.min()..ys.max()
                // the wall's bottom must meet an earlier wall; a band's bottom an earlier print over the same Y
                val supported = bottom == 0L || done.any { d -> d.z == bottom && d.y.first <= y.last && y.first <= d.y.last }
                if (!supported) error("layer $i: ${p.feature} at y ${y.first / 1e3}..${y.last / 1e3} starts at z ${bottom / 1e3} with nothing printed there")
            }
            for (p in l.base + l.purges.flatten()) { val ys = (0 until p.xy.size / 2).map { p.xy[2 * it + 1] }; done += Printed(ys.min()..ys.max(), tops8[i]) }
        }
    }

    "compact: fewer tower visits and less plastic than banded for the same changes (control: banded visits every layer)" {
        fun stats(t: PurgeTower): Pair<Int, Double> {
            var visits = 0; var mm3 = 0.0
            for (i in 0..t.top) { val l = t.layer(i, changes8[i]); val ps = l.base + l.purges.flatten(); if (ps.isNotEmpty()) visits++; mm3 += ps.sumOf { len(it) * bead(it) } }
            return visits to mm3
        }
        val (vc, mc) = stats(compact); val (vb, mb) = stats(banded)
        println("  tower visits compact %d vs banded %d of %d layers; plastic %.0f vs %.0f mm3".format(Locale.ROOT, vc, vb, compact.top + 1, mc, mb))
        vb shouldBe banded.top + 1
        (vc < vb) shouldBe true
        (mc < mb) shouldBe true
    }

    "square: the smallest width whose depth is no greater; control: the 35 mm setting is far from square" {
        val sq = PurgeTower.square(165_000, 204_365, 30.0, 400, 4_070, changes8, tops8, compact = true, maxGapUm = 280)
        (sq.depthUm <= sq.widthUm) shouldBe true
        val narrower = PurgeTower(165_000, 204_365, sq.widthUm - 1, 30.0, 400, 4_070, changes8, tops8, compact = true, maxGapUm = 280)
        (narrower.depthUm > narrower.widthUm) shouldBe true
        (sq.widthUm - sq.depthUm < 1_000) shouldBe true                 // within about two purge lines of square
        (Math.abs(compact.widthUm - compact.depthUm) > 5_000) shouldBe true
    }
})

private infix fun Long.shouldBeLessThan_(limit: Long) { if (this > limit) error("$this exceeds $limit") }
