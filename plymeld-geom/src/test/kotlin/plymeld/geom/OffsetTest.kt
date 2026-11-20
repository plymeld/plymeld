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
            // each join, and the miter with round arcs past its limit
            for (d in listOf(-3_000L, -420L, 420L, 3_000L)) for ((j, round) in Offset.Join.values().map { it to false } + (Offset.Join.MITER to true)) {
                val fast = area(Offset.offset(p, d, j, roundBeyondLimit = round))
                val slow = area(Offset.offsetBySleeves(p, d, j, roundBeyondLimit = round))
                val err = Math.abs(fast - slow)
                if (err > worst) worst = err
                io.kotest.assertions.withClue("offset $d $j (round past the limit: $round) of ${p[0]}: raw=$fast sleeves=$slow") {
                    err shouldBe (0.0 plusOrMinus (0.002 * p[0].perimeterMm() * Math.abs(d / 1000.0) + 0.01))
                }
            }
        }
        println("raw-ring vs sleeves: worst area difference %.5f mm²".format(worst))
    }

    // A thin triangle (apex 20 degrees, base angles 80) grown by d: its area is A + P d + each corner's join, the join
    // computed from the corner's turn t alone: a miter d^2 tan(t / 2) where it reaches no more than twice d (the base
    // corners, t = 100 degrees: 1.56 d), past that a bevel d^2 sin(t) / 2 or a round arc d^2 t / 2 (the apex, t = 160
    // degrees: the miter would reach 5.8 d)
    val tri = listOf(Polygon(longArrayOf(-3_000, 0, 3_000, 0, 0, 17_014)))
    fun joins(round: Boolean, d: Double): Double {
        val p = tri[0]; var j = 0.0
        for (i in 0 until p.size) {
            val a = (i + p.size - 1) % p.size; val c = (i + 1) % p.size
            val t = Math.abs(Math.atan2(((p.x(i) - p.x(a)) * (p.y(c) - p.y(i)) - (p.y(i) - p.y(a)) * (p.x(c) - p.x(i))).toDouble(),
                ((p.x(i) - p.x(a)) * (p.x(c) - p.x(i)) + (p.y(i) - p.y(a)) * (p.y(c) - p.y(i))).toDouble()))
            j += if (1 / Math.cos(t / 2) <= Offset.MITER_LIMIT) d * d * Math.tan(t / 2) else if (round) d * d * t / 2 else d * d * Math.sin(t) / 2
        }
        return j
    }
    fun grown(round: Boolean) = area(tri) + tri[0].perimeterMm() * 1.0 + joins(round, 1.0)

    "a miter past its limit takes a round arc with roundBeyondLimit: a thin triangle grown by 1 mm, its area in closed form" {
        // within the arc's chords (inside its circle: 0.009 mm² short at the apex) and the points' rounding to the grid
        // (half a micron over a perimeter of 40 mm)
        area(Offset.offset(tri, 1_000, Offset.Join.MITER, roundBeyondLimit = true)) shouldBe (grown(true) plusOrMinus 0.02)
        // without it, the bevel (every other caller's offset, unchanged)
        area(Offset.offset(tri, 1_000, Offset.Join.MITER)) shouldBe (grown(false) plusOrMinus 0.02)
    }

    "control: the bevel's area is rejected as the round arc's (1.2 mm² short)" {
        runCatching { area(Offset.offset(tri, 1_000, Offset.Join.MITER)) shouldBe (grown(true) plusOrMinus 0.02) }.isFailure shouldBe true
    }

    // A 20 mm square with a notch 8 mm deep from its top edge, its tip 30 degrees (the outline turns 150 degrees there:
    // a miter would reach 3.9 d), shrunk by 0.8 mm. Every point of the exact shrink is at least d from the outline: read
    // along the result's edges every 20 µm against the outline's edges (point to segment, not through Offset)
    val notch = listOf(Polygon(longArrayOf(0, 0, 20_000, 0, 20_000, 20_000, 12_144, 20_000, 10_000, 12_000, 7_856, 20_000, 0, 20_000)))
    fun nearestToOutline(r: List<Polygon>): Double {
        val o = notch[0]; var best = Double.MAX_VALUE
        for (q in r) for (e in 0 until q.size) {
            val f = (e + 1) % q.size
            val len = Math.hypot((q.x(f) - q.x(e)).toDouble(), (q.y(f) - q.y(e)).toDouble())
            val steps = maxOf(1, Math.ceil(len / 20.0).toInt())
            for (s in 0..steps) {
                val px = q.x(e) + (q.x(f) - q.x(e)) * s.toDouble() / steps; val py = q.y(e) + (q.y(f) - q.y(e)) * s.toDouble() / steps
                for (k in 0 until o.size) {
                    val l = (k + 1) % o.size
                    val ax = o.x(k).toDouble(); val ay = o.y(k).toDouble(); val dx = o.x(l) - ax; val dy = o.y(l) - ay
                    val u = (((px - ax) * dx + (py - ay) * dy) / (dx * dx + dy * dy)).coerceIn(0.0, 1.0)
                    best = minOf(best, Math.hypot(ax + u * dx - px, ay + u * dy - py))
                }
            }
        }
        return best
    }

    "shrinking at a notch's tip, the round arc keeps the fit at least d from it, and no farther" {
        val r = Offset.offset(notch, -800, Offset.Join.MITER, roundBeyondLimit = true)
        // within the arc's chord sag (5 µm) and the grid's rounding
        (nearestToOutline(r) >= 800.0 - 5 - 2) shouldBe true
        // the ground straight below the tip, just past d from it, is kept (a miter, not cut, would take 3.1 mm more)
        Polygons.contains(r.single(), 10_000, 12_000 - 810) shouldBe true
        // the rest is the mitred shrink: its area is the bevelled one's less, at the tip, the circle's segment between the
        // bevel's chord and the arc, d^2 (t - sin t) / 2 for the turn t (0.68 mm²; the arc's own chords 0.007 mm² in)
        val t = Math.PI - 2 * Math.atan(2_144.0 / 8_000.0)
        val bevel = Offset.offset(notch, -800, Offset.Join.MITER)
        (area(bevel) - area(r)) shouldBe (0.8 * 0.8 * (t - Math.sin(t)) / 2 plusOrMinus 0.01)
    }

    "control: the bevel keeps ground d cos(75 degrees), 0.21 mm, from the notch's tip" {
        val near = nearestToOutline(Offset.offset(notch, -800, Offset.Join.MITER))
        near shouldBe (800 * Math.cos(Math.toRadians(75.0)) plusOrMinus 3.0)
        runCatching { (near >= 800.0 - 5 - 2) shouldBe true }.isFailure shouldBe true
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
