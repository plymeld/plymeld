package plymeld.check

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import plymeld.core.check.ModelCheck
import plymeld.mesh.TriangleSoup

/** A disc of radius [r] around (110, 110), 0.4 mm tall, as a 128-sided prism. */
private fun disc(r: Double): TriangleSoup {
    val n = 128; val t = ArrayList<FloatArray>()
    fun p(k: Int, z: Double) = floatArrayOf((110 + r * Math.cos(2 * Math.PI * k / n)).toFloat(), (110 + r * Math.sin(2 * Math.PI * k / n)).toFloat(), z.toFloat())
    val c0 = floatArrayOf(110f, 110f, 0f); val c1 = floatArrayOf(110f, 110f, 0.4f)
    for (k in 0 until n) {
        t += p(k, 0.0) + p(k + 1, 0.0) + p(k + 1, 0.4); t += p(k, 0.0) + p(k + 1, 0.4) + p(k, 0.4)
        t += c0 + p(k + 1, 0.0) + p(k, 0.0); t += c1 + p(k, 0.4) + p(k + 1, 0.4)
    }
    val out = FloatArray(t.size * 9); for ((k, a) in t.withIndex()) System.arraycopy(a, 0, out, k * 9, 9)
    return TriangleSoup(out, t.size)
}

/**
 * ModelCheck reads arc moves (G2 / G3 with I / J from the start) as the arcs they are. Before, it read only
 * G0 / G1, so a file with arcs (the Creator 5 with firmware.arcs on) lost its arc walls and drew the next straight line
 * from the position before the arc: the speed Benchy was refused with 30 problems, "no wall along" and "outside the
 * model", none of them in the print. Here a disc whose outer wall is two G3 half circles passes; controls that must
 * fail: the half circles read as straight lines (the old reading), and an arc about the wrong center, bulging out.
 */
class ArcCheckTest : StringSpec({
    val r = 10.0 - 0.22
    fun gcode(wall: (Double, Int) -> String): String {
        val sb = StringBuilder("M83\nG28\nM109 S210\n")
        for ((k, z) in listOf(0.2, 0.4).withIndex()) {
            sb.append("; CHANGE_LAYER\n; Z_HEIGHT: $z\n; LAYER_HEIGHT: 0.2\nG1 E-0.8 F2400\nG1 Z${z + 0.2} F600\nG1 X${110 + r} Y110 F18000\nG1 Z$z\nG1 E0.8 F2400\n")
            sb.append("; FEATURE: Outer wall\n; LINE_WIDTH: 0.44\nG1 F3000\n").append(wall(z, k))
            sb.append("; FEATURE: Sparse infill\nG1 X103 Y110 F18000\nG1 X117 Y110 E0.5 F6000\n")
        }
        return sb.append("G1 E-0.8 F2400\nG1 Z2 F600\nM104 S0\n").toString()
    }
    // the outer wall: counter-clockwise half circles, (110 + r, 110) to (110 - r, 110) and back, about the disc's center
    val arcs = gcode { _, _ -> "G3 X${110 - r} Y110 I${-r} J0 E1.0\nG3 X${110 + r} Y110 I$r J0 E1.0\n" }
    val parts = listOf(disc(10.0))

    "a disc walled with two G3 half circles passes" {
        ModelCheck.check(arcs, parts, 0.0).shouldBeEmpty()
    }
    "control: the same moves read as straight lines (G1, the old reading) leave the outline unwalled" {
        val straight = arcs.lines().joinToString("\n") { if (it.startsWith("G3 ")) it.replace("G3 ", "G1 ").replace(Regex(" [IJ]\\S+"), "") else it }
        ModelCheck.check(straight, parts, 0.0).any { "has no wall along" in it } shouldBe true
    }
    "control: an arc about the wrong center (5 mm off, the long way round) prints outside the model" {
        val wrong = gcode { _, _ -> "G3 X${110 - r} Y110 I${-r} J5 E1.0\nG3 X${110 + r} Y110 I$r J0 E1.0\n" }
        ModelCheck.check(wrong, parts, 0.0).any { "outside the model" in it } shouldBe true
    }
    "arcChords: a clockwise quarter circle from (1, 0) to (0, -1) about the origin, every chord end on the circle" {
        val pts = ArrayList<DoubleArray>()
        ModelCheck.arcChords(1.0, 0.0, 0.0, -1.0, 0.0, 0.0, true) { ax, ay, bx, by -> pts += doubleArrayOf(ax, ay, bx, by) }
        pts.size shouldBe 4                                   // a quarter of a 1 mm circle, 1.57 mm, in chords of at most 0.5
        pts.first()[0] shouldBe 1.0; pts.first()[1] shouldBe 0.0
        pts.last()[2] shouldBe 0.0; pts.last()[3] shouldBe -1.0
        pts.forEach { Math.hypot(it[2], it[3]) shouldBe (1.0 plusOrMinus 1e-12) }
        pts.forEach { (it[3] <= 1e-12) shouldBe true }           // clockwise from (1, 0): through y < 0, not the long way
    }
})
