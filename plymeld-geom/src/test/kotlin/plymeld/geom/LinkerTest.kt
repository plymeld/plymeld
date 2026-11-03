package plymeld.geom

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe

class LinkerTest : StringSpec({
    val sq = listOf(square(0, 0, 10_000))
    fun len(xy: LongArray): Double { var l = 0.0; for (i in 2 until xy.size step 2) l += Math.hypot((xy[i] - xy[i - 2]).toDouble(), (xy[i + 1] - xy[i - 1]).toDouble()); return l / 1000 }

    "10 lines 1 mm apart in a square link into ONE zig-zag along the edges" {
        val (segs, idx) = Hatch.indexed(sq, 0.0, 1_000)
        val chains = Linker.link(segs, idx, sq, 2_000)
        chains.size shouldBe 1
        // 10 lines of 10 mm + 9 connectors of 1 mm along the boundary
        len(chains[0]) shouldBe (109.0 plusOrMinus 1e-3)
    }

    "connectors never leave the boundary: every chain vertex lies in or on the region" {
        val ring = Booleans.difference(listOf(square(0, 0, 20_000)), listOf(square(6_000, 6_000, 8_000)))
        val (segs, idx) = Hatch.indexed(ring, 45.0, 1_300)
        for (c in Linker.link(segs, idx, ring, 20_000)) for (i in c.indices step 2) {
            val inside = Polygons.group(ring).any { (o, hs) -> Polygons.contains(o, c[i], c[i + 1]) && hs.none { h -> Polygons.contains(h, c[i], c[i + 1]) && !onBoundary(h, c[i], c[i + 1]) } }
            inside shouldBe true
        }
    }

    // grid in a 20 mm square: +45 and -45 lines 4 mm apart
    val big = listOf(square(0, 0, 20_000))
    fun gridSegs(region: List<Polygon>): List<LongArray> = listOf(45.0, 135.0).map { Hatch.indexed(region, it, 4_000).first }
    fun segLen(s: LongArray): Double { var l = 0.0; for (k in 0 until s.size / 4) l += Math.hypot((s[4 * k + 2] - s[4 * k]).toDouble(), (s[4 * k + 3] - s[4 * k + 1]).toDouble()); return l / 1000 }

    "linkAll: a grid joins crossing directions with short steps; linking each direction alone takes the full spacing (control)" {
        val parts = gridSegs(big)
        val lines = parts.sumOf { segLen(it) }
        val all = parts[0] + parts[1]
        val joined = Linker.linkAll(all, big, 20_000).sumOf { len(it) }
        // every line is in a chain (connectors cannot be negative) and connectors fit in one lap
        (joined - lines >= -1e-6) shouldBe true
        (joined - lines <= 80.0) shouldBe true
        // A join between neighboring ends of OPPOSITE directions is at most half the same-direction
        // spacing along the edge; a per-direction join is the whole spacing. So the joined connectors
        // come to less than half the per-direction ones (measured: 12.1 vs 67.9 mm).
        val perDirection = listOf(45.0, 135.0).sumOf { a -> val (s, i) = Hatch.indexed(big, a, 4_000); Linker.link(s, i, big, 20_000).sumOf { len(it) } }
        (joined - lines < (perDirection - lines) / 2) shouldBe true
    }

    "linkAll: connectors stay on the region, around a hole too" {
        val ring = Booleans.difference(listOf(square(0, 0, 20_000)), listOf(square(6_000, 6_000, 8_000)))
        val parts = gridSegs(ring)
        for (c in Linker.linkAll(parts[0] + parts[1], ring, 20_000)) for (i in c.indices step 2) {
            val inside = Polygons.group(ring).any { (o, hs) -> Polygons.contains(o, c[i], c[i + 1]) && hs.none { h -> Polygons.contains(h, c[i], c[i + 1]) && !onBoundary(h, c[i], c[i + 1]) } }
            inside shouldBe true
        }
    }

    "breakAtForks: no chain runs from below a hole on into one of its sides (each side a block of its own); without it one does (control)" {
        val ring = Booleans.difference(listOf(square(0, 0, 20_000)), listOf(square(6_000, 6_000, 8_000)))
        val (segs, idx) = Hatch.indexed(ring, 0.0, 1_000)
        fun crosses(c: LongArray): Boolean {
            val ys = (1 until c.size step 2).map { c[it] }
            return ys.any { it < 5_500 } && ys.any { it in 6_500..13_500 }
        }
        val forks = Linker.link(segs, idx, ring, 3_000, breakAtSplits = true, breakAtForks = true)
        forks.none { crosses(it) } shouldBe true
        forks.size shouldBe 4
        val merges = Linker.link(segs, idx, ring, 3_000, breakAtSplits = true)
        merges.any { crosses(it) } shouldBe true
        merges.size shouldBe 3
    }

    "control: a connector limit below the line spacing links nothing" {
        val (segs, idx) = Hatch.indexed(sq, 0.0, 1_000)
        Linker.link(segs, idx, sq, 500).size shouldBe 10
    }
})

private fun onBoundary(p: Polygon, x: Long, y: Long): Boolean {
    for (i in 0 until p.size) {
        val j = (i + 1) % p.size
        if (orientation(p.point(i), p.point(j), IntPoint(x, y)) == 0 && Segments.onSegment(p.point(i), p.point(j), IntPoint(x, y))) return true
    }
    return false
}
