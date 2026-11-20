package plymeld.geom

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe

class HatchTest : StringSpec({
    val sq = listOf(square(0, 0, 10_000))

    "10 mm square, 1 mm spacing at 0°: 10 lines of 10 mm" {
        val h = Hatch.lines(sq, 0.0, 1_000)
        h.size / 4 shouldBe 10
        Hatch.lengthMm(h) shouldBe (100.0 plusOrMinus 1e-6)
    }

    "total hatch length ≈ area / spacing at any angle" {
        for (ang in listOf(0.0, 17.0, 45.0, 90.0, 133.0)) {
            val h = Hatch.lines(sq, ang, 500)
            // area 100 mm² / 0.5 mm = 200 mm; edge effects bounded by one spacing along the boundary
            Hatch.lengthMm(h) shouldBe (200.0 plusOrMinus 12.0)
        }
    }

    "a hole splits the lines crossing it" {
        val ring = Booleans.difference(sq, listOf(square(3_000, 3_000, 4_000)))
        val h = Hatch.lines(ring, 0.0, 1_000)
        // lines at y 0.25..9.25: those at 3.25,4.25,5.25,6.25 cross the hole -> 2 segments each
        h.size / 4 shouldBe 14
        Hatch.lengthMm(h) shouldBe (100.0 - 4 * 4.0 plusOrMinus 1e-6)
    }

    "every segment's midpoint lies inside the region" {
        val star = Booleans.difference(listOf(square(0, 0, 20_000)), listOf(square(5_000, 5_000, 3_000), square(12_000, 9_000, 4_000)))
        val h = Hatch.lines(star, 31.0, 420)
        val edges = Arrangement.edgesOf(star, emptyList())
        for (i in h.indices step 4) {
            val mx = (h[i] + h[i + 2]) / 2; val my = (h[i + 1] + h[i + 3]) / 2
            // winding at a point = windingBeside on a zero-length probe is not defined; use a tiny horizontal probe
            val w = Booleans.windingBeside(edges, BandIndex(edges), IntPoint(mx, my), IntPoint(mx + 1, my), left = true)
            w shouldBe 1
        }
    }

    "two separate islands share one global grid of lines" {
        val a = listOf(square(0, 0, 5_000)); val b = listOf(square(7_340, 1_111, 5_000))
        val offsetsA = offsetsOf(Hatch.lines(a, 45.0, 450), 45.0)
        val offsetsB = offsetsOf(Hatch.lines(b, 45.0, 450), 45.0)
        // every line offset is (k * 450 + 0.25) in the rotated frame, for both islands
        for (v in offsetsA + offsetsB) (Math.abs((v - 0.25) / 450 - Math.round((v - 0.25) / 450)) < 1e-3) shouldBe true
    }

    // Hatch.throughGrazes: a line 0.45 mm wide; lines at y = k mm + 0.25 um
    fun onLine(segs: LongArray, idx: IntArray, k: Int) = (idx.indices).filter { idx[it] == k }.map { segs[4 * it] to segs[4 * it + 2] }
    "a line grazing a corner of a hole by 5 microns is laid through it; a line crossing the hole is not" {
        // a diamond hole whose lowest corner lies 5.25 um below the line at y 5 mm (the line cut 10.5 um short of it)
        val region = Booleans.difference(sq, listOf(Polygon(longArrayOf(5_000, 4_995, 6_000, 5_995, 5_000, 6_995, 4_000, 5_995))))
        val (segs, idx) = Hatch.indexed(region, 0.0, 1_000)
        // control: clipped to the region the line is two, their ends 10.5 um apart (their round ends over each other)
        val cut = onLine(segs, idx, 5)
        cut.size shouldBe 2
        (cut[1].first - cut[0].second < 450) shouldBe true
        val (js, ji) = Hatch.throughGrazes(segs, idx, region, 450)
        onLine(js, ji, 5) shouldBe listOf(0L to 10_000L)
        onLine(js, ji, 6).size shouldBe 2           // crossing the hole 2 mm wide
        js.size / 4 shouldBe segs.size / 4 - 1
    }

    "two lines' ends 0.4 mm apart are laid through a flat corner (34.7 um off the region) but not across a slot as wide" {
        // a hole whose lowest corner is 10 deg each way from flat, 35.25 um below the line at y 5 mm: cut 400 um short
        val flat = Booleans.difference(sq, listOf(Polygon(longArrayOf(5_000, 4_965, 8_000, 5_494, 8_000, 5_800, 2_000, 5_800, 2_000, 5_494))))
        val (fs, fi) = Hatch.indexed(flat, 0.0, 1_000)
        onLine(fs, fi, 5).let { it.size shouldBe 2; (it[1].first - it[0].second in 398L..402L) shouldBe true }
        val (gs, gi) = Hatch.throughGrazes(fs, fi, flat, 450)
        onLine(gs, gi, 5) shouldBe listOf(0L to 10_000L)
        // a slot 0.4 mm wide: laid through, the line's middle would lie 200 um off the region, more than the ends' 50 um overlap
        val slot = Booleans.difference(sq, listOf(Polygon(longArrayOf(4_800, 3_000, 5_200, 3_000, 5_200, 7_000, 4_800, 7_000))))
        val (ss, si) = Hatch.indexed(slot, 0.0, 1_000)
        val (ts, ti) = Hatch.throughGrazes(ss, si, slot, 450)
        for (k in 4..6) onLine(ts, ti, k).size shouldBe 2
        // the line at y 3 mm runs a quarter micron inside the slot's end: it only grazes it
        onLine(ss, si, 3).size shouldBe 2
        onLine(ts, ti, 3) shouldBe listOf(0L to 10_000L)
    }

    "control: a shifted grid would be detected" {
        val v = 450 * 3 + 225.0
        (Math.abs((v - 0.25) / 450 - Math.round((v - 0.25) / 450)) < 1e-3) shouldBe false
    }
})

private fun offsetsOf(h: LongArray, angleDeg: Double): List<Double> {
    val a = Math.toRadians(angleDeg); val c = Math.cos(a); val s = Math.sin(a)
    return (h.indices step 4).map { -s * h[it] + c * h[it + 1] }
}
