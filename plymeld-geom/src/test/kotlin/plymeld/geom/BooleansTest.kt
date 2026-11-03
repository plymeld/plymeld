package plymeld.geom

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe

/**
 * Hand cases with known answers, invariants, and random polygons against frozen answers from an independent geometry
 * library ([GeomReference]) for the boolean core.
 */
class BooleansTest : StringSpec({
    val a = listOf(square(0, 0, 10_000))
    val b = listOf(square(5_000, 5_000, 10_000))
    fun area(ps: List<Polygon>) = ps.sumOf { it.signedAreaMm2() }

    "two overlapping squares: union 175, intersection 25, difference 75, xor 150 (2 polygons)" {
        area(Booleans.union(a, b)) shouldBe (175.0 plusOrMinus 1e-9)
        Booleans.union(a, b).size shouldBe 1
        area(Booleans.intersection(a, b)) shouldBe (25.0 plusOrMinus 1e-9)
        area(Booleans.difference(a, b)) shouldBe (75.0 plusOrMinus 1e-9)
        val x = Booleans.xor(a, b)
        area(x) shouldBe (150.0 plusOrMinus 1e-9)
        x.size shouldBe 2
    }

    "hole: square minus a centered square leaves an outer CCW ring and a CW hole" {
        val hole = listOf(square(2_500, 2_500, 5_000))
        val r = Booleans.difference(a, hole)
        r.size shouldBe 2
        area(r) shouldBe (75.0 plusOrMinus 1e-9)
        r.count { it.isCcw() } shouldBe 1
        r.count { !it.isCcw() } shouldBe 1
    }

    "disjoint squares: union keeps both, intersection is empty" {
        val far = listOf(square(20_000, 0, 10_000))
        Booleans.union(a, far).size shouldBe 2
        Booleans.intersection(a, far).size shouldBe 0
    }

    "touching squares (shared edge) union into one rectangle with 4 vertices" {
        val right = listOf(square(10_000, 0, 10_000))
        val u = Booleans.union(a, right)
        u.size shouldBe 1
        u[0].size shouldBe 4
        area(u) shouldBe (200.0 plusOrMinus 1e-9)
    }

    "self-overlapping input is normalized by the nonzero rule" {
        val twice = listOf(square(0, 0, 10_000), square(0, 0, 10_000))
        val n = Booleans.normalize(twice)
        n.size shouldBe 1
        area(n) shouldBe (100.0 plusOrMinus 1e-9)
    }

    "bow-tie input: the nonzero rule keeps BOTH lobes (plymeld's documented choice; GEOS buffer(0) keeps one)" {
        val bowtie = Polygon(longArrayOf(0, 0, 10_000, 10_000, 10_000, 0, 0, 10_000))
        val n = Booleans.normalize(listOf(bowtie))
        n.size shouldBe 2
        n.all { it.isCcw() } shouldBe true
        area(n) shouldBe (50.0 plusOrMinus 1e-9)
    }

    "control: a wrong expected area is rejected by the area check" {
        runCatching { area(Booleans.union(a, b)) shouldBe (100.0 plusOrMinus 1e-9) }.isFailure shouldBe true
    }

    // grid rounding moves vertices by <= 0.5 µm: one grid cell along the whole perimeter plus a floor
    fun tol(c: GeomReference.BooleanCase) = 1e-3 * (c.a.perimeterMm() + c.b.perimeterMm()) + 1e-4

    "random simple polygons, all four ops, against the frozen independent answers" {
        val cases = GeomReference.booleans()
        cases.size shouldBe 240
        var worst = 0.0
        for ((i, c) in cases.withIndex()) {
            val ours = area(Booleans.op(listOf(c.a), listOf(c.b), c.op))
            val err = Math.abs(ours - c.areaMm2)
            if (err > worst) worst = err
            io.kotest.assertions.withClue("${c.op} of case ${i / 4}: ours=$ours reference=${c.areaMm2} (tol ${tol(c)})\n  a=${c.a}\n  b=${c.b}") {
                err shouldBe (0.0 plusOrMinus tol(c))
            }
        }
        println("booleans against the frozen reference: ${cases.size} cases, worst area error %.6f mm²".format(worst))
    }

    "control: each case's answer for another op is rejected (the comparison can fail)" {
        val cases = GeomReference.booleans()
        val unions = cases.filter { it.op == Booleans.Op.UNION }
        val inters = cases.filter { it.op == Booleans.Op.INTERSECTION }
        // a union compared with its own pair's intersection answer: wrong wherever the polygons overlap or are apart
        val rejected = unions.zip(inters).count { (u, x) ->
            Math.abs(area(Booleans.op(listOf(u.a), listOf(u.b), Booleans.Op.UNION)) - x.areaMm2) > tol(u)
        }
        rejected shouldBe unions.size
    }
})
