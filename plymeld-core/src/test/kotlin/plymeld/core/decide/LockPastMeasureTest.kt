package plymeld.core.decide

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import plymeld.mesh.TriangleSoup

/**
 * A curved island locked with no start where the survey's stair-step measure finds no start that removes any
 * (CurvedRegions.bestIsland's acceptLoss, CurvedIsland.resolve): the lock takes the start that steps least, its measure kept on
 * the island (CurvedIsland.gainMm3, at or under zero) for the plan to say; the unlocked survey still refuses the cap. The case: the
 * roof with a chimney through it of CurvedRegionsTest, measured against flat layers of 0.04 mm (under the tool's thinnest, 0.08,
 * so every own layer of the island is thicker than the flat ones and no start removes stair-step: what makes the measure refuse).
 * Control: the same lock measured against flat layers of 0.08 mm takes the survey's best, which removes stair-step.
 */
class LockPastMeasureTest : StringSpec({
    /** A solid over [x0, x1] x [y0, y1] whose top is [f] (sampled every [step] mm), its bottom at z 0 (the survey reads the top). */
    fun field(x0: Double, x1: Double, y0: Double, y1: Double, step: Double = 0.25, f: (Double, Double) -> Double): TriangleSoup {
        val nx = Math.round((x1 - x0) / step).toInt(); val ny = Math.round((y1 - y0) / step).toInt()
        val tris = ArrayList<Float>()
        fun tri(vararg v: Double) { for (d in v) tris += d.toFloat() }
        for (i in 0 until nx) for (j in 0 until ny) {
            val xa = x0 + i * step; val xb = xa + step; val ya = y0 + j * step; val yb = ya + step
            tri(xa, ya, f(xa, ya), xb, ya, f(xb, ya), xb, yb, f(xb, yb)); tri(xa, ya, f(xa, ya), xb, yb, f(xb, yb), xa, yb, f(xa, yb))
        }
        tri(x0, y0, 0.0, x1, y1, 0.0, x1, y0, 0.0); tri(x0, y0, 0.0, x0, y1, 0.0, x1, y1, 0.0)
        return TriangleSoup(tris.toFloatArray(), tris.size / 9)
    }
    // a roof 0.8 mm high over 20 mm on a block 10 mm tall, a 4 x 4 mm chimney through it to 20 mm
    val roof = field(0.0, 30.0, 0.0, 20.0) { x, y -> if (x in 13.0..17.0 && y in 8.0..12.0) 20.0 else 10.0 + 0.8 * (1 - ((y - 10) / 10).let { it * it }) }
    val lock = CurvedIsland.Lock("roof", 5.0, 5.0)

    "locked with no start where no start removes stair-step: the start that steps least, its loss kept; unlocked, refused" {
        val p = CurvedRegions.Params(45.0, 4.0, 0.04, 0.08)
        val r = CurvedRegions.survey(roof, p, withBands = false)
        val cap = r.caps.first { c -> c.cells.any { a -> a == Math.floor(5.0 / r.cellMm).toInt() * r.ny + Math.floor(5.0 / r.cellMm).toInt() } }
        // the survey (the decision's, and a lock before): refused, saying why
        val refused = CurvedRegions.bestIsland(r, p, cap, 0.04, 0.3)
        refused.shouldBeInstanceOf<String>()
        refused shouldContain "no start removes stair-step from its top"
        val best = Regex("at ([0-9.]+) mm, ").find(refused)!!.groupValues[1].toDouble()
        // locked with no start: that start, the island's own layers within the tool's, its loss kept
        val isl = CurvedIsland.resolve(listOf(roof), listOf(lock), p, 0.04, 0.3).single()
        println("$isl, gain %.3f mm3".format(java.util.Locale.ROOT, isl.gainMm3))
        Math.abs(isl.startMm - best) shouldBeLessThan 0.005 + 1e-9      // the message gives it to 0.01 mm
        (isl.gainMm3 <= 0.0) shouldBe true
        (isl.layerMm >= 0.08 - 1e-9 && isl.layerMm <= 0.3 + 1e-9) shouldBe true
        // the unlocked survey is as it was: the cap has no island
        CurvedRegions.shifted(r, p, 0.04, 0.3).first { it.first.id == cap.id }.second.shouldBeInstanceOf<String>()
    }

    "control: against flat layers of 0.08 mm the same lock takes the survey's best start, which removes stair-step" {
        val p = CurvedRegions.Params(45.0, 4.0, 0.08, 0.08)
        val isl = CurvedIsland.resolve(listOf(roof), listOf(lock), p, 0.08, 0.3).single()
        println("$isl, gain %.3f mm3".format(java.util.Locale.ROOT, isl.gainMm3))
        (isl.gainMm3 > 0.0) shouldBe true
    }
})
