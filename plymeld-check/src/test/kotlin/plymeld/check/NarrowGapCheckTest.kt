package plymeld.check

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import plymeld.core.check.ModelCheck
import plymeld.mesh.Slicer
import plymeld.mesh.TriangleSoup

/**
 * A 20 x 10 mm block, 0.4 mm tall, with a V groove across it (along y) from the top: its left side vertical at
 * x 110, its right side sloping 3.3 mm across per mm up (plymeld's choice, close to the Benchy's groove below),
 * its tip at [tipZ]. Built as the XZ profile extruded along y from 100 to 110.
 */
private fun grooveBlock(tipZ: Float): TriangleSoup {
    val top = 0.4f
    // the profile, counter-clockwise in (x, z); Q (110, 0) splits it into two convex pieces for the end caps
    val p = listOf(100f to 0f, 110f to 0f, 120f to 0f, 120f to top, 110f + 3.3f * (top - tipZ) to top, 110f to tipZ, 110f to top, 100f to top)
    val left = listOf(0, 1, 5, 6, 7); val right = listOf(1, 2, 3, 4, 5)
    val y0 = 100f; val y1 = 110f
    val t = ArrayList<FloatArray>()
    fun tri(a: FloatArray, b: FloatArray, c: FloatArray) { t += a + b + c }
    fun v(i: Int, y: Float) = floatArrayOf(p[i].first, y, p[i].second)
    // sides: edge a -> b of the counter-clockwise profile has the material on its left; the normals point out
    for (i in p.indices) { val j = (i + 1) % p.size
        tri(v(i, y0), v(j, y1), v(j, y0)); tri(v(i, y0), v(i, y1), v(j, y1)) }
    // end caps: fans, facing -y at y0 and +y at y1
    for (piece in listOf(left, right)) for (k in 1 until piece.size - 1) {
        tri(v(piece[0], y0), v(piece[k], y0), v(piece[k + 1], y0))
        tri(v(piece[0], y1), v(piece[k + 1], y1), v(piece[k], y1))
    }
    val out = FloatArray(t.size * 9); for ((k, a) in t.withIndex()) System.arraycopy(a, 0, out, k * 9, 9)
    return TriangleSoup(out, t.size)
}

/**
 * ModelCheck's uncovered-outline rule and a gap narrower than its tolerance. The Benchy at 0.08 mm
 * layers (first layer 0.28 mm) was refused at z 14.920: "the model's outline has no wall along 28.7 mm". There
 * the slice plane (z 14.880) passes 1 um above the tip of a V groove (tip at z 14.879, x 138.68 on the bed), so
 * the mesh slicer's cross-section is two pieces 3 um apart; the planner's clean-up closes a gap that narrow and
 * prints the two as one, with no wall along it; the check took both faces of the 3 um gap for outline that
 * needed a wall. The same on a block: the groove's tip 1 um below layer 2's slice plane (z 0.3).
 */
class NarrowGapCheckTest : StringSpec({
    val start = "M83\nG28\nM109 S210"
    /** Two layers printed as ONE region over the whole block (what the planner prints when it closes the gap). */
    fun layer(z: Double): String {
        val a = 100.22; val b = 119.78; val c = 109.78
        val corners = listOf(a to a, b to a, b to c, a to c, a to a)
        val sb = StringBuilder("; CHANGE_LAYER\n; Z_HEIGHT: $z\n; LAYER_HEIGHT: 0.2\n")
        sb.append("G1 E-0.8 F2400\nG1 Z${z + 0.2} F600\nG1 X$a Y$a F18000\nG1 Z$z\nG1 E0.8 F2400\n; FEATURE: Outer wall\n; LINE_WIDTH: 0.44\nG1 F3000\n")
        for (k in 0 until 4) { val (x, y) = corners[k + 1]; sb.append("G1 X$x Y$y E0.3\n") }
        sb.append("; FEATURE: Sparse infill\nG1 X101 Y105 F18000\nG1 X119 Y105 E0.5 F6000\n")
        return sb.toString()
    }
    val gcode = "$start\n" + layer(0.2) + layer(0.4) + "G1 E-0.8 F2400\nG1 Z2 F600\nM104 S0\n"

    /** The gap between the two pieces of the block's cut at z, measured along y 105 (mm), from the mesh slicer. */
    fun gapMm(soup: TriangleSoup, z: Double): Double {
        val loops = Slicer.slice(soup, doubleArrayOf(z))[0].loops.filter { it.signedArea2() > 0 }
        loops.size shouldBe 2   // the cut really is two pieces: without that this test tests nothing
        val (l, r) = loops.sortedBy { it.bounds()[0] }
        return (r.bounds()[0] - l.bounds()[2]) / 1000.0
    }

    "a 3 um gap (a V groove sliced 1 um above its tip) is not outline: the block printed as one region passes" {
        val soup = grooveBlock(0.299f)
        (gapMm(soup, 0.3) in 0.001..0.010) shouldBe true
        val problems = ModelCheck.check(gcode, listOf(soup), 0.0)
        problems.forEach { println("  $it") }
        problems shouldBe emptyList()
    }

    "control: a 0.33 mm gap (the tip 0.1 mm below the plane) printed as one region has no wall along it" {
        val soup = grooveBlock(0.2f)
        (gapMm(soup, 0.3) in 0.30..0.36) shouldBe true
        ModelCheck.check(gcode, listOf(soup), 0.0).any { it.startsWith("layer at z 0.400") && it.contains("no wall") } shouldBe true
    }
})
