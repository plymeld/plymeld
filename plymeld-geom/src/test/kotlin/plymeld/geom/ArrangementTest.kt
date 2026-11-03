package plymeld.geom

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll

@OptIn(io.kotest.common.ExperimentalKotest::class)
class ArrangementTest : StringSpec({

    "two overlapping squares split into 12 pieces" {
        val a = square(0, 0, 10_000)
        val b = square(5_000, 5_000, 10_000)
        val e = Arrangement.split(Arrangement.edgesOf(listOf(a), listOf(b)))
        e.size shouldBe 12
        noCrossings(e) shouldBe true
    }

    "after splitting, pieces meet only at endpoints (random polygon soups)" {
        val coord = Arb.long(-50_000L, 50_000L)
        val poly = arbitrary {
            val n = Arb.int(3, 8).bind()
            val xy = LongArray(2 * n) { coord.bind() }
            Polygon(xy)
        }
        // Seed locked to the run that first found a counter-example; the
        // iteration count is high enough that it is still a real fuzz, not a single case.
        checkAll(io.kotest.property.PropTestConfig(seed = 919862721652563502L, iterations = 300), poly, poly) { p, q ->
            val e = Arrangement.split(Arrangement.edgesOf(listOf(p), listOf(q)))
            noCrossings(e) shouldBe true
            // every piece is a non-degenerate segment
            for (i in 0 until e.size) (e.p(i) != e.q(i)) shouldBe true
        }
    }

    "control: the crossing check itself sees a crossing in an unsplit soup" {
        val a = square(0, 0, 10_000)
        val b = square(5_000, 5_000, 10_000)
        noCrossings(Arrangement.edgesOf(listOf(a), listOf(b))) shouldBe false
    }
})

internal fun square(x: Long, y: Long, s: Long) = Polygon(longArrayOf(x, y, x + s, y, x + s, y + s, x, y + s))

/** True iff no two pieces properly cross and no piece endpoint lies strictly inside another piece. */
internal fun noCrossings(e: Arrangement.Edges): Boolean {
    for (i in 0 until e.size) for (j in i + 1 until e.size) {
        val a = e.p(i); val b = e.q(i); val c = e.p(j); val d = e.q(j)
        if (Segments.properCrossing(a, b, c, d)) return false
        for (pt in listOf(c, d)) if (pt != a && pt != b && orientation(a, b, pt) == 0 && Segments.onSegment(a, b, pt)) return false
        for (pt in listOf(a, b)) if (pt != c && pt != d && orientation(c, d, pt) == 0 && Segments.onSegment(c, d, pt)) return false
    }
    return true
}
