package plymeld.core

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import plymeld.core.check.ModelCheck
import plymeld.mesh.TriangleSoup
import java.util.Locale

/**
 * Parallel work never waits on a lock held by a unit of other parallel work. The pre-print check judges a print's layers
 * in parallel, each reading the model's cut of its layer; the cuts were a synchronized lazy whose own initializer cut
 * every layer in parallel. Taken by one layer's task, that lock was held while its initializer's tasks ran, and a worker
 * helping with them took up another layer's task and waited on the lock: with each index a task of its own (Parallel), the
 * check's whole pool stopped (a check test hung for 26 minutes at no CPU).
 *
 * Read here: the check of a hand-written print of two boxes side by side (two parts: each layer's cut is their union), of
 * 2 to 200 layers (fewer layers than the pool has workers leaves workers free to take up the cuts' tasks), five times
 * each, each finishing within a minute on a thread of its own. Control (run by hand when the rule was made): with the
 * cuts a synchronized lazy again and not made before the layers' tasks, the check of 16 layers stopped on its first round
 * and this test failed by its time limit.
 */
class ParallelLockTest : StringSpec({
    /** A box from (x0, y0, 0) to (x1, y1, z) mm: 12 triangles. */
    fun box(x0: Double, y0: Double, x1: Double, y1: Double, z: Double): TriangleSoup {
        val v = listOf(doubleArrayOf(x0, y0, 0.0), doubleArrayOf(x1, y0, 0.0), doubleArrayOf(x1, y1, 0.0), doubleArrayOf(x0, y1, 0.0),
            doubleArrayOf(x0, y0, z), doubleArrayOf(x1, y0, z), doubleArrayOf(x1, y1, z), doubleArrayOf(x0, y1, z))
        val faces = listOf(intArrayOf(0, 2, 1), intArrayOf(0, 3, 2), intArrayOf(4, 5, 6), intArrayOf(4, 6, 7), intArrayOf(0, 1, 5), intArrayOf(0, 5, 4),
            intArrayOf(1, 2, 6), intArrayOf(1, 6, 5), intArrayOf(2, 3, 7), intArrayOf(2, 7, 6), intArrayOf(3, 0, 4), intArrayOf(3, 4, 7))
        val c = FloatArray(faces.size * 9); var i = 0
        for (f in faces) for (k in f) { c[i++] = v[k][0].toFloat(); c[i++] = v[k][1].toFloat(); c[i++] = v[k][2].toFloat() }
        return TriangleSoup(c, faces.size)
    }
    val h = 0.2
    fun f(v: Double) = String.format(Locale.ROOT, "%.3f", v)
    /** A square loop [inset] in from the two boxes' outline. */
    fun loop(inset: Double): String {
        val a = 100 + inset; val b = 110 - inset
        val pts = listOf(a to a, b to a, b to b, a to b, a to a)
        val sb = StringBuilder("G1 X${f(a)} Y${f(a)} F18000\n; FEATURE: ${if (inset < 0.3) "Outer wall" else "Inner wall"}\n; LINE_WIDTH: 0.44\n")
        for (k in 1 until pts.size) sb.append("G1 X${f(pts[k].first)} Y${f(pts[k].second)} E${f(0.033 * (b - a))}\n")
        return sb.toString()
    }
    /** The print of [layers] layers. */
    fun text(layers: Int) = buildString {
        append("M83\nG28\nM109 S210\nG1 X5 Y5 Z0.3 F6000\nG1 X50 Y5 E5\n")
        for (k in 1..layers) {
            val z = k * h
            append("; CHANGE_LAYER\n; Z_HEIGHT: ${f(z)}\n; LAYER_HEIGHT: $h\nG1 Z${f(z)} F600\n")
            append(loop(0.22)); append(loop(0.62)); append(loop(1.02))
        }
        append("G1 Z${f(layers * h + 2)} F600\nM104 S0\n")
    }

    "the pre-print check of prints of 2 to 200 layers of two parts finishes (its layers' tasks wait on no lock their work holds)" {
        // fewer layers than the pool has workers leaves workers free to take up the cuts' tasks, and more, none
        for (layers in listOf(2, 3, 4, 6, 8, 12, 16, 24, 48, 200)) {
            val parts = listOf(box(100.0, 100.0, 105.0, 110.0, layers * h), box(105.0, 100.0, 110.0, 110.0, layers * h))
            val g = text(layers)
            for (round in 0 until 5) {
                var problems: List<String>? = null
                val t = Thread { problems = ModelCheck.check(g, parts, 0.0, featureMinMm = 0.17) }.apply { isDaemon = true; start() }
                t.join(60_000)
                if (t.isAlive) println("$layers layers, round $round: the check had not finished after 60 s")
                t.isAlive shouldBe false
                (problems != null) shouldBe true
            }
        }
    }
})
