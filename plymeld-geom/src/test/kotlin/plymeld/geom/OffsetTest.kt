package plymeld.geom

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.RandomSource
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.double
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.next

class OffsetTest : StringSpec({
    val sq = listOf(square(0, 0, 10_000))
    fun area(ps: List<Polygon>) = ps.sumOf { it.signedAreaMm2() }

    "10 mm square: inset 1 mm -> 8x8, miter outset 1 mm -> 12x12" {
        area(Offset.offset(sq, -1_000)) shouldBe (64.0 plusOrMinus 1e-6)
        area(Offset.offset(sq, 1_000, Offset.Join.MITER)) shouldBe (144.0 plusOrMinus 1e-6)
    }

    "round outset of a square: 100 + 4*10*1 + pi*1^2 (disc chords within tolerance)" {
        area(Offset.offset(sq, 1_000, Offset.Join.ROUND)) shouldBe (100.0 + 40.0 + Math.PI plusOrMinus 0.02)
    }

    "an inset larger than half the width erases the polygon" {
        Offset.offset(sq, -5_001).size shouldBe 0
    }

    "a hole grows when the material shrinks" {
        val ring = Booleans.difference(sq, listOf(square(3_000, 3_000, 4_000)))
        val inset = Offset.offset(ring, -500)
        // outer 9x9 minus hole 5x5
        area(inset) shouldBe (81.0 - 25.0 plusOrMinus 1e-6)
        inset.size shouldBe 2
    }

    "a thin rectangle inset past its half-width leaves nothing (no wrong-way remnants)" {
        val thin = listOf(Polygon(longArrayOf(0, 0, 10_000, 0, 10_000, 1_000, 0, 1_000)))
        Offset.offset(thin, -600).size shouldBe 0
        Offset.offset(thin, -2_000, Offset.Join.ROUND).size shouldBe 0
        area(Offset.offset(thin, -400)) shouldBe (9.2 * 0.2 plusOrMinus 1e-6)
    }

    "raw-ring offset agrees with the independent sleeve implementation (random polygons, both joins)" {
        val rs = RandomSource.seeded(777L)
        val unit = Arb.double(0.0, 1.0)
        val poly = arbitrary {
            val n = Arb.int(4, 14).bind()
            val angles = List(n) { (it + 0.9 * unit.bind()) * 2 * Math.PI / n }
            val xy = LongArray(2 * n)
            for (i in 0 until n) {
                val r = 3_000 + unit.bind() * 25_000
                xy[2 * i] = Math.round(r * Math.cos(angles[i])); xy[2 * i + 1] = Math.round(r * Math.sin(angles[i]))
            }
            Polygon(xy).let { if (it.isCcw()) it else it.reversed() }
        }
        var worst = 0.0
        repeat(40) {
            val p = listOf(poly.next(rs))
            for (d in listOf(-3_000L, -420L, 420L, 3_000L)) for (j in Offset.Join.values()) {
                val fast = area(Offset.offset(p, d, j))
                val slow = area(Offset.offsetBySleeves(p, d, j))
                val err = Math.abs(fast - slow)
                if (err > worst) worst = err
                io.kotest.assertions.withClue("offset $d $j of ${p[0]}: raw=$fast sleeves=$slow") {
                    err shouldBe (0.0 plusOrMinus (0.002 * p[0].perimeterMm() * Math.abs(d / 1000.0) + 0.01))
                }
            }
        }
        println("raw-ring vs sleeves: worst area difference %.5f mm²".format(worst))
    }

    "control: the inset check rejects the outset answer" {
        runCatching { area(Offset.offset(sq, 1_000)) shouldBe (64.0 plusOrMinus 1e-6) }.isFailure shouldBe true
    }

    // both sides approximate arcs with chords: 0.5 % of the perimeter-times-delta band, plus a floor
    fun tol(c: GeomReference.OffsetCase) = 0.005 * c.polygon.perimeterMm() * Math.abs(c.deltaUm / 1000.0) + 0.01

    "round-join insets and outsets of random simple polygons against the frozen independent answers" {
        val cases = GeomReference.offsets()
        cases.size shouldBe 100
        var worst = 0.0
        for (c in cases) {
            val ours = area(Offset.offset(listOf(c.polygon), c.deltaUm, Offset.Join.ROUND))
            val err = Math.abs(ours - c.areaMm2)
            if (err > worst) worst = err
            io.kotest.assertions.withClue("offset ${c.deltaUm} µm of ${c.polygon}: ours=$ours reference=${c.areaMm2} tol=${tol(c)}") {
                err shouldBe (0.0 plusOrMinus tol(c))
            }
        }
        println("offsets against the frozen reference: ${cases.size} cases, worst area error %.5f mm²".format(worst))
    }

    "control: an offset compared with the opposite delta's answer is rejected" {
        val cases = GeomReference.offsets()
        // the file holds -1500, -420, 420, 2000 µm per polygon: compare each -420 with its own +420 answer
        val pairs = cases.chunked(4).map { it[1] to it[2] }
        val rejected = pairs.count { (inset, outset) ->
            Math.abs(area(Offset.offset(listOf(inset.polygon), inset.deltaUm, Offset.Join.ROUND)) - outset.areaMm2) > tol(inset)
        }
        rejected shouldBe pairs.size
    }
})
