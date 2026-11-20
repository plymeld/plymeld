package plymeld.check

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import plymeld.core.check.ModelCheck
import java.util.Locale

/**
 * The pre-print check's held-bridge rule (ModelCheck): a bridge line over air with nothing under either end is found and
 * reported (ModelCheck.Result.said), not refused.
 * The model: a bar on the bed (x 100..120, y 100..108, 2 mm tall) and an arm 6 mm wide reaching 12 mm out from its side
 * (x 106..112, y 104..120), its bottom 1 mm over the bed, so the arm's first layer (z 1.0..1.2) hangs off the bar's side.
 * Hand-written bridge lines on that layer, 0.4 mm wide:
 *  - across the arm (along x), every line's ends over air: found, with the place and the length, and not among the
 *    problems;
 *  - control: along the arm (along y), each line starting on the bar: nothing found;
 *  - control: one line along the bar's side, 0.2 mm out from it (its bead on the bar's edge): nothing found;
 *  - control: across the arm with support's interface under it, one layer of gap below: nothing found;
 *  - control: across the arm with support under it but a plate of the model between the support and the arm (the support
 *    holds the plate, not the arm): found;
 *  - a link between two lines, shorter than three line widths, over air at both ends: not judged.
 */
class HeldBridgeCheckTest : StringSpec({
    val bar = testBox(100f, 100f, 0f, 120f, 108f, 2f)
    val arm = testBox(106f, 104f, 1f, 112f, 120f, 2f)
    val plate = testBox(104f, 109f, 0.4f, 114f, 121f, 0.6f)
    fun f(v: Double) = String.format(Locale.ROOT, "%.3f", v)
    /** A run through [pts] after a travel, laid with E per mm for a 0.4 mm line on 0.2 mm. */
    fun path(feature: String, pts: List<DoubleArray>): String {
        val sb = StringBuilder("G1 E-0.8 F2400\nG1 X${f(pts[0][0])} Y${f(pts[0][1])} F18000\nG1 E0.8 F2400\n; FEATURE: $feature\n; LINE_WIDTH: 0.4\nG1 F3000\n")
        for (k in 1 until pts.size) sb.append("G1 X${f(pts[k][0])} Y${f(pts[k][1])} E${f(0.03 * Math.hypot(pts[k][0] - pts[k - 1][0], pts[k][1] - pts[k - 1][1]))}\n")
        return sb.toString()
    }
    fun layer(z: Double, body: String) = "; CHANGE_LAYER\n; Z_HEIGHT: ${f(z)}\n; LAYER_HEIGHT: 0.2\nG1 Z${f(z)} F600\n$body"
    /** Lines across the arm (along x), each on its own, from y 109 to 119. */
    val across = (0..25).joinToString("") { i -> val y = 109.0 + 0.4 * i; path("Bridge", listOf(doubleArrayOf(106.3, y), doubleArrayOf(111.7, y))) }
    /** Lines along the arm (along y), from on the bar (y 107.2) out to the arm's end. */
    val along = (0..13).joinToString("") { i -> val x = 106.4 + 0.4 * i; path("Bridge", listOf(doubleArrayOf(x, 107.2), doubleArrayOf(x, 119.7))) }
    /** Support's interface lines under the arm, at layer [z]. */
    fun support(z: Double) = layer(z, (0..30).joinToString("") { i -> val y = 108.4 + 0.4 * i; path("Support interface", listOf(doubleArrayOf(106.0, y), doubleArrayOf(112.0, y))) })
    fun file(vararg layers: String) = "M83\nG28\nM109 S210\n" + layers.joinToString("") + "G1 E-0.8 F2400\nG1 Z5 F600\nM104 S0\n"
    fun held(text: String, parts: List<plymeld.mesh.TriangleSoup> = listOf(bar, arm)) = ModelCheck.checkAll(text, parts, 0.0).said

    "bridge lines across an arm hanging off one side, nothing under either end: found, saying where, and not refused" {
        val r = ModelCheck.checkAll(file(layer(1.2, across)), listOf(bar, arm), 0.0)
        val p = r.said
        p.size shouldBe 1
        p.single() shouldContain "layer at z 1.200: 26 bridge line(s) over air"
        p.single() shouldContain "rest on nothing at either end: no model and no support under them"
        p.single() shouldContain "a bridge line over air needs printed material under at least one end"
        r.problems.filter { "bridge line" in it }.shouldBeEmpty()
    }
    "control: lines along the arm, each starting on the bar, are held at that end" {
        held(file(layer(1.2, along))).shouldBeEmpty()
    }
    "control: a line along the bar's side, its bead on the bar's edge, is not over air" {
        held(file(layer(1.2, path("Bridge", listOf(doubleArrayOf(106.3, 108.2), doubleArrayOf(111.7, 108.2)))))).shouldBeEmpty()
        // and the same line 1 mm further out is
        held(file(layer(1.2, path("Bridge", listOf(doubleArrayOf(106.3, 109.2), doubleArrayOf(111.7, 109.2)))))).size shouldBe 1
    }
    "control: support's interface under the arm one layer down, one layer of gap: held" {
        held(file(support(0.8), layer(1.0, ""), layer(1.2, across))).shouldBeEmpty()
    }
    "control: support under a plate of the model, the plate between it and the arm: found" {
        held(file(support(0.4), layer(1.2, across)), listOf(bar, arm, plate)).size shouldBe 1
    }
    "a link shorter than three line widths over air at both ends is not judged; the same run as a line is" {
        held(file(layer(1.2, path("Bridge", listOf(doubleArrayOf(108.0, 115.0), doubleArrayOf(109.1, 115.0)))))).shouldBeEmpty()
        held(file(layer(1.2, path("Bridge", listOf(doubleArrayOf(107.0, 115.0), doubleArrayOf(109.0, 115.0), doubleArrayOf(111.0, 115.0)))))).size shouldBe 1
    }
})
