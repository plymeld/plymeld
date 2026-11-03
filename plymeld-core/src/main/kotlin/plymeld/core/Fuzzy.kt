package plymeld.core

import java.util.SplittableRandom

/**
 * Fuzzy skin ("classic" noise, displacement mode): a closed wall loop is re-sampled at random
 * spacing around `pointDistUm`, and each new point is pushed along the loop's normal by a random
 * amount in [-thickness, +thickness]. Behavior measured from QIDIStudio on a test model: offsets
 * spread evenly over ±fuzzy_skin_thickness, point spacing median ≈ the point distance. Which loops
 * each mode fuzzes has no test.
 *
 * Deterministic: the random stream is seeded from the layer and loop, so a re-slice (or an
 * incremental re-run after an unrelated setting change) gives identical g-code.
 */
object Fuzzy {
    /** Spacing is uniform in [LO, HI] × point distance. */
    const val LO = 0.75
    const val HI = 1.25

    /** Result: the fuzzed loop, and for each of its points the index of the source edge it was placed on. */
    class Out(val xy: LongArray, val sourceEdge: IntArray)

    fun loop(xy: LongArray, thicknessUm: Long, pointDistUm: Long, seed: Long): Out {
        val n = xy.size / 2
        val cum = DoubleArray(n + 1)
        for (i in 0 until n) {
            val j = (i + 1) % n
            cum[i + 1] = cum[i] + Math.hypot((xy[2 * j] - xy[2 * i]).toDouble(), (xy[2 * j + 1] - xy[2 * i + 1]).toDouble())
        }
        val perim = cum[n]
        if (perim < 3 * HI * pointDistUm) return Out(xy, IntArray(n) { it })   // too small to fuzz into a sensible loop
        val rnd = SplittableRandom(seed)
        val out = ArrayList<Long>()
        val src = ArrayList<Int>()
        var s = 0.0
        var seg = 0
        while (s < perim - LO * pointDistUm / 2) {
            while (cum[seg + 1] < s) seg++
            val j = (seg + 1) % n
            val dx = (xy[2 * j] - xy[2 * seg]).toDouble(); val dy = (xy[2 * j + 1] - xy[2 * seg + 1]).toDouble()
            val len = cum[seg + 1] - cum[seg]
            val t = if (len > 0) (s - cum[seg]) / len else 0.0
            val px = xy[2 * seg] + t * dx; val py = xy[2 * seg + 1] + t * dy
            // right-hand normal: outward for a CCW contour, into the hole (away from material) for a CW hole
            val off = (rnd.nextDouble() * 2 - 1) * thicknessUm
            val nx = if (len > 0) dy / len else 0.0; val ny = if (len > 0) -dx / len else 0.0
            out.add(Math.round(px + nx * off)); out.add(Math.round(py + ny * off)); src.add(seg)
            s += pointDistUm * (LO + (HI - LO) * rnd.nextDouble())
        }
        return Out(out.toLongArray(), src.toIntArray())
    }
}
