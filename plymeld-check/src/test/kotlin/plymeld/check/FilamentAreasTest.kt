package plymeld.check

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import plymeld.core.FilamentAreas
import plymeld.geom.Booleans
import plymeld.geom.Polygon

private const val MM = 1000L
private fun rect(x: Long, y: Long, w: Long, h: Long) = Polygon(longArrayOf(x * MM, y * MM, (x + w) * MM, y * MM, (x + w) * MM, (y + h) * MM, x * MM, (y + h) * MM))
private fun mm2(loops: List<Polygon>?) = (loops?.let { FilamentAreas.area(it) } ?: 0.0) / (MM * MM)

/** References are rectangle arithmetic, not the split's own booleans. */
class FilamentAreasTest : StringSpec({
    val a = listOf(rect(0, 0, 10, 10))      // 100 mm²
    val b = listOf(rect(5, 0, 10, 10))      // 100 mm², 50 mm² of it over a

    // a 10 x 10 body and, along its bottom edge, a 0.3 mm strip of another filament (as seen on a multi-color model)
    fun um(x: Long, y: Long, w: Long, h: Long) = Polygon(longArrayOf(x, y, x + w, y, x + w, y + h, x, y + h))
    val body = listOf(um(0, 300, 10_000, 10_000))
    val strip = listOf(um(0, 0, 10_000, 300))

    "a filament's sliver too thin for a line goes to the neighbor it touches" {
        val s = FilamentAreas.split(listOf(body, strip), listOf("spool 1", "spool 2"), listOf(0, 1), minWidthUm = 440)
        mm2(s.bySpool["spool 2"]) shouldBe (0.0 plusOrMinus 1e-6)
        mm2(s.bySpool["spool 1"]) shouldBe (103.0 plusOrMinus 1e-6)                 // 100 + 10 x 0.3
    }

    "control: without a narrowest line, the sliver stays with its own filament" {
        val s = FilamentAreas.split(listOf(body, strip), listOf("spool 1", "spool 2"), listOf(0, 1))
        mm2(s.bySpool["spool 2"]) shouldBe (3.0 plusOrMinus 1e-6)
    }

    "a sliver with no neighbor stays; one wide enough for a line stays" {
        val apart = listOf(um(0, -2_000, 10_000, 300))                            // 1.7 mm below the body: touches nothing
        FilamentAreas.split(listOf(body, apart), listOf("spool 1", "spool 2"), listOf(0, 1), minWidthUm = 440).bySpool["spool 2"].let { mm2(it) shouldBe (3.0 plusOrMinus 1e-6) }
        val wide = listOf(um(0, -200, 10_000, 500))                               // 0.5 mm: a line fits
        FilamentAreas.split(listOf(body, wide), listOf("spool 1", "spool 2"), listOf(0, 1), minWidthUm = 440).bySpool["spool 2"].let { mm2(it) shouldBe (5.0 plusOrMinus 1e-6) }
    }

    "overlap goes to the part named first in the precedence; the areas are disjoint and cover both" {
        val first = FilamentAreas.split(listOf(a, b), listOf("spool 1", "spool 2"), listOf(0, 1))
        mm2(first.bySpool["spool 1"]) shouldBe (100.0 plusOrMinus 1e-9)
        mm2(first.bySpool["spool 2"]) shouldBe (50.0 plusOrMinus 1e-9)
        first.overlapUm2 / (MM * MM) shouldBe (50.0 plusOrMinus 1e-9)
        mm2(Booleans.intersection(first.bySpool.getValue("spool 1"), first.bySpool.getValue("spool 2"))) shouldBe (0.0 plusOrMinus 1e-9)
        val second = FilamentAreas.split(listOf(a, b), listOf("spool 1", "spool 2"), listOf(1, 0))
        mm2(second.bySpool["spool 1"]) shouldBe (50.0 plusOrMinus 1e-9)
        mm2(second.bySpool["spool 2"]) shouldBe (100.0 plusOrMinus 1e-9)
    }

    "an inlay inside a body: whichever takes precedence, the total is the body's area" {
        val body = listOf(rect(0, 0, 10, 10)); val inlay = listOf(rect(3, 3, 2, 2))   // 4 mm² inside
        val inlayWins = FilamentAreas.split(listOf(body, inlay), listOf("spool 1", "spool 2"), listOf(1, 0))
        mm2(inlayWins.bySpool["spool 1"]) shouldBe (96.0 plusOrMinus 1e-9)
        mm2(inlayWins.bySpool["spool 2"]) shouldBe (4.0 plusOrMinus 1e-9)
        val bodyWins = FilamentAreas.split(listOf(body, inlay), listOf("spool 1", "spool 2"), listOf(0, 1))
        mm2(bodyWins.bySpool["spool 1"]) shouldBe (100.0 plusOrMinus 1e-9)
        bodyWins.bySpool["spool 2"] shouldBe null                               // the inlay vanishes: why the rule matters
    }

    "parts on the same spool merge; parts that only touch have no overlap" {
        val s = FilamentAreas.split(listOf(listOf(rect(0, 0, 10, 10)), listOf(rect(10, 0, 5, 10)), listOf(rect(20, 0, 5, 5))),
            listOf("spool 1", "spool 1", "spool 2"), listOf(0, 1, 2))
        mm2(s.bySpool["spool 1"]) shouldBe (150.0 plusOrMinus 1e-9)
        s.bySpool.getValue("spool 1").count { it.signedArea2() > 0 } shouldBe 1   // one outline, not two
        mm2(s.bySpool["spool 2"]) shouldBe (25.0 plusOrMinus 1e-9)
        s.overlapUm2 shouldBe (0.0 plusOrMinus 1e-9)
    }

    // Reference: sections and overlay computed independently of plymeld (test-models/MANIFEST.md):
    // 6.10 mm³ at 0.2 mm layers, but 4.93 of it on two layers (z 8.9, 18.7) where
    // flat faces of both halves lie within ~0.01 mm of the plane and the reference mis-reads the section;
    // 1.17 mm³ on the layers where it reads under 1 mm². plymeld has ONE such layer, z 8.5, where
    // part B's deck top (491 mm² facing up) lies exactly ON the plane and counts as present
    // while part A starts at 8.498: a 0.002 mm sliver read as a whole layer.
    "two-color Benchy: away from faces lying on a slicing plane, plymeld's overlap matches the independent reference" {
        val parts = benchyDualParts()
        val zs = DoubleArray(240) { 0.1 + 0.2 * it }
        val sliced = parts.map { plymeld.mesh.Slicer.slice(it, zs) }
        val perLayer = zs.indices.map { i -> FilamentAreas.split(sliced.map { it[i].loops }, listOf("spool 1", "spool 2"), listOf(0, 1)).overlapUm2 / 1e6 }
        val big = zs.indices.filter { perLayer[it] >= 1.0 }
        val rest = zs.indices.filter { perLayer[it] < 1.0 }.sumOf { perLayer[it] * 0.2 }
        println("two-color Benchy: overlap %.2f mm³ on layers under 1 mm² (reference 1.17); layers over 1 mm²: %s".format(java.util.Locale.ROOT, rest,
            big.joinToString { "z %.2f %.2f mm²".format(java.util.Locale.ROOT, zs[it], perLayer[it]) }))
        rest shouldBe (1.17 plusOrMinus 0.12)
        big.map { Math.round(zs[it] * 10) } shouldBe listOf(85L)       // z 8.5 only: the deck-top tie
    }

    "overlap rule: the part that started first wins by default; each alternative gives its own order" {
        val slots = listOf(2, 1, 3, 1)       // part 0 on filament 2, parts 1 and 3 on filament 1, part 2 on 3
        FilamentAreas.precedence("started_first", slots, listOf(0, 40, 40, -1)) shouldBe listOf(0, 1, 2, 3)   // lowest first; a tie by filament; never present: last
        FilamentAreas.precedence("lower_filament", slots) shouldBe listOf(1, 3, 0, 2)
        FilamentAreas.precedence("higher_filament", slots) shouldBe listOf(2, 0, 1, 3)
        FilamentAreas.precedence("earlier_part", slots) shouldBe listOf(0, 1, 2, 3)
        FilamentAreas.precedence("later_part", slots) shouldBe listOf(3, 2, 1, 0)
        plymeld.model.Catalog["job.part_overlap"].default shouldBe "started_first"
        // a logo on filament 2 embedded in a base on filament 4 that started below it: the base keeps
        // the shared area (no cut-out); control: lower_filament gives it to the logo
        val base = listOf(rect(0, 0, 10, 10)); val logo = listOf(rect(3, 3, 2, 2))
        val kept = FilamentAreas.split(listOf(base, logo), listOf("spool 4", "spool 2"), FilamentAreas.precedence("started_first", listOf(4, 2), listOf(0, 30)))
        mm2(kept.bySpool["spool 4"]) shouldBe (100.0 plusOrMinus 1e-9)
        kept.bySpool["spool 2"] shouldBe null
        val cut = FilamentAreas.split(listOf(base, logo), listOf("spool 4", "spool 2"), FilamentAreas.precedence("lower_filament", listOf(4, 2)))
        mm2(cut.bySpool["spool 4"]) shouldBe (96.0 plusOrMinus 1e-9)
        // an inlay on filament 2 inside a body on filament 1: by default the body keeps the volume
        val body = listOf(rect(0, 0, 10, 10)); val inlay = listOf(rect(3, 3, 2, 2))
        val d = FilamentAreas.split(listOf(body, inlay), listOf("spool 1", "spool 2"), FilamentAreas.precedence("lower_filament", listOf(1, 2)))
        mm2(d.bySpool["spool 1"]) shouldBe (100.0 plusOrMinus 1e-9)
        shouldThrow<IllegalStateException> { FilamentAreas.precedence("smallest", slots) }
    }

    "control: the precedence must name every part once" {
        shouldThrow<IllegalArgumentException> { FilamentAreas.split(listOf(a, b), listOf("spool 1", "spool 2"), listOf(0)) }
        shouldThrow<IllegalArgumentException> { FilamentAreas.split(listOf(a, b), listOf("spool 1", "spool 2"), listOf(0, 0)) }
    }
})
