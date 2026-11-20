package plymeld.check

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import plymeld.core.check.ModelCheck
import java.util.Locale

/**
 * The pre-print check's hole rule (ModelCheck, HoleCheck) on a hand-written print of a 10 mm box, two layers, three wall
 * loops and sparse infill lines inside them:
 *  - as written it passes (the infill's gaps, and the corners between the infill and the walls, are no holes);
 *  - control: a 1 mm stretch of the middle loop left out on the second layer leaves a hole closed in by the other two loops,
 *    0.36 mm wide: refused, by name, where it is (9ba9167's lens laid as a loop and a line down its middle left one 0.25 to
 *    0.29 mm wide on its top layers: refused too, read by hand when the rule was made);
 *  - fuzzy skin: the outer loop jittered 0.3 mm in and out (two points out, two in, 0.8 mm apart) opens gaps between it and
 *    the next loop, closed in by the two (read 0.30 mm wide): passed with the fuzzy skin's thickness given (the check's own
 *    allowance for it), refused without it (the control: the gaps are there to see).
 */
class HoleCheckTest : StringSpec({
    val model = listOf(testBox(100f, 100f, 0f, 110f, 110f, 0.4f))
    val start = "M83\nG28\nM109 S210"
    fun f(v: Double) = String.format(Locale.ROOT, "%.3f", v)

    /** A loop [inset] in from the box's edge, its points [pts] (x, y pairs), laid with E per mm. */
    fun path(feature: String, pts: List<DoubleArray>, gapFrom: Double = Double.NaN, gapTo: Double = Double.NaN): String {
        val sb = StringBuilder("G1 X${f(pts[0][0])} Y${f(pts[0][1])} F18000\n; FEATURE: $feature\n; LINE_WIDTH: 0.44\nG1 F3000\n")
        for (k in 1 until pts.size) {
            val (x0, y0) = pts[k - 1][0] to pts[k - 1][1]; val (x1, y1) = pts[k][0] to pts[k][1]
            val skip = !gapFrom.isNaN() && y0 < 101.0 && y1 < 101.0 && minOf(x0, x1) >= gapFrom - 1e-9 && maxOf(x0, x1) <= gapTo + 1e-9
            sb.append(if (skip) "G1 X${f(x1)} Y${f(y1)}\n" else "G1 X${f(x1)} Y${f(y1)} E${f(0.033 * Math.hypot(x1 - x0, y1 - y0))}\n")
        }
        return sb.toString()
    }
    /** A square loop [inset] in from the box's edges, its sides cut every [step] mm; [jitter]: each point moved that far out and in, in turn. */
    fun square(inset: Double, step: Double = 0.5, jitter: Double = 0.0): List<DoubleArray> {
        val a = 100 + inset; val b = 110 - inset
        val corners = listOf(doubleArrayOf(a, a), doubleArrayOf(b, a), doubleArrayOf(b, b), doubleArrayOf(a, b))
        val pts = ArrayList<DoubleArray>()
        for (s in 0 until 4) {
            val p = corners[s]; val q = corners[(s + 1) % 4]
            val n = Math.round(Math.hypot(q[0] - p[0], q[1] - p[1]) / step).toInt()
            // outward: right of the way round (counter-clockwise)
            val ox = (q[1] - p[1]) / Math.hypot(q[0] - p[0], q[1] - p[1]); val oy = -(q[0] - p[0]) / Math.hypot(q[0] - p[0], q[1] - p[1])
            for (j in 0 until n) {
                // two points out, two in, along each side (a stretch of the wall out by the whole thickness)
                val t = j.toDouble() / n; val d = if (j == 0) 0.0 else jitter * (if ((j / 2) % 2 == 0) 1 else -1)
                pts += doubleArrayOf(p[0] + (q[0] - p[0]) * t + ox * d, p[1] + (q[1] - p[1]) * t + oy * d)
            }
        }
        pts += pts[0]
        return pts
    }
    fun layer(z: Double, gap: Boolean, jitter: Double): String {
        val sb = StringBuilder("; CHANGE_LAYER\n; Z_HEIGHT: $z\n; LAYER_HEIGHT: 0.2\nG1 E-0.8 F2400\nG1 Z${f(z + 0.2)} F600\nG1 X100.22 Y100.22 F18000\nG1 Z$z\nG1 E0.8 F2400\n")
        sb.append(path("Outer wall", square(0.22, step = if (jitter > 0) 0.8 else 0.5, jitter = jitter)))
        sb.append(path("Inner wall", square(0.62), gapFrom = if (gap) 104.0 else Double.NaN, gapTo = 105.0))
        sb.append(path("Inner wall", square(1.02)))
        var y = 101.6
        while (y < 108.5) { sb.append(path("Sparse infill", listOf(doubleArrayOf(101.3, y), doubleArrayOf(108.7, y)))); y += 1.2 }
        return sb.toString()
    }
    fun file(gap: Boolean = false, jitter: Double = 0.0) = "$start\nG1 X5 Y5 Z0.3 F6000\nG1 X50 Y5 E5\n" + layer(0.2, false, jitter) + layer(0.4, gap, jitter) +
        "G1 E-0.8 F2400\nG1 Z2 F600\nG1 X150 Y150 F6000\nM104 S0\n"
    fun check(text: String, fuzz: Double = 0.0) = ModelCheck.check(text, model, 0.0, featureMinMm = 0.17, fuzzMm = fuzz)
    fun holes(pr: List<String>) = pr.filter { it.contains("a hole inside the walls") }

    "three loops and sparse infill pass the hole rule" {
        check(file()).shouldBeEmpty()
    }
    "control: a stretch of the middle loop left out is a hole inside the walls, refused where it is" {
        val pr = check(file(gap = true))
        println("planted hole: $pr")
        val h = holes(pr)
        h.size shouldBe 1
        h[0].startsWith("layer at z 0.400: a hole inside the walls") shouldBe true
        // where it is: between x 104 and 105, about 0.6 mm in from the box's side (y 100.4 to 100.8)
        val at = Regex("at ([0-9.]+), ([0-9.]+)\\)").find(h[0])!!.groupValues
        (at[1].toDouble() in 103.8..105.2 && at[2].toDouble() in 100.3..100.9) shouldBe true
    }
    "fuzzy skin's gaps pass with its thickness given; control: refused without it" {
        val text = file(jitter = 0.3)
        val with = check(text, fuzz = 0.3)
        val without = check(text)
        println("fuzzy: with the allowance ${with.size} problem(s) ${with.take(2)}; without ${holes(without).size} hole(s) ${holes(without).take(1)}")
        holes(with).shouldBeEmpty()
        (holes(without).isNotEmpty()) shouldBe true
    }
})
