package plymeld.check

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import plymeld.core.MotionRegions
import plymeld.program.Extrude

/**
 * A region's caps on a path (MotionRegions): a 10 mm square loop whose right half lies in a box gets a vertex where each side
 * crosses the box's left side (x 5), stays one closed loop over the same corners, and its segments in the box carry the caps,
 * the rest none. Controls: a box the loop does not reach, and one holding only other lines (by feature), leave it as it was.
 */
class MotionRegionsTest : StringSpec({
    val square = Extrude("Outer wall", longArrayOf(0, 0, 10000, 0, 10000, 10000, 0, 10000), true, 200, 450, 200)
    fun box(x0: Double, features: Set<String>? = null) = MotionRegions.Box("r", x0, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY,
        Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, features, 20.0, 1000.0)

    "a loop half in a box: a vertex where it crosses, still one closed loop, the half in the box capped" {
        val c = MotionRegions.capped(square, listOf(box(5000.0)))
        c.closed shouldBe true
        c.xy.toList() shouldBe listOf(0L, 0L, 5000L, 0L, 10000L, 0L, 10000L, 10000L, 5000L, 10000L, 0L, 10000L)
        // segments: (0,0)-(5,0) out, (5,0)-(10,0) in, (10,0)-(10,10) in, (10,10)-(5,10) in, (5,10)-(0,10) out, (0,10)-(0,0) out
        c.speedMax!!.map { if (it.isNaN()) null else it } shouldBe listOf(null, 20.0, 20.0, 20.0, null, null)
        c.accelMax!!.map { if (it.isNaN()) null else it } shouldBe listOf(null, 1000.0, 1000.0, 1000.0, null, null)
    }

    "control: a box it does not reach, and one for other lines, leave the path as it was" {
        MotionRegions.capped(square, listOf(box(20000.0))) shouldBeSameInstanceAs square
        MotionRegions.capped(square, listOf(box(5000.0, setOf("Sparse infill")))) shouldBeSameInstanceAs square
    }
})
