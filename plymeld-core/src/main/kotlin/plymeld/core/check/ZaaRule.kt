package plymeld.core.check

import plymeld.mesh.SolidColumns
import plymeld.mesh.TriangleSoup

/**
 * The checks' rule for a line printed below its flat layer (Z anti-aliasing, plan.faces.sloped_top.technique = "z_antialiasing"): made from the MODEL
 * (the parts as placed of the objects that have it on, every part of each, [zBase] the bed's height in their frame: g-code
 * Z = mesh Z - zBase; Expectations.forZaa) and the config (the thinnest lowered line, plymeld.core.ZAntiAlias.minMm's
 * thinnest layer [minMm], the slicing resolution), never from what the slicer planned. At (x, y) on the layer from bottom to
 * top (g-code Z), the line belongs at the model's top when that lies within the layer (the top of the run of solid
 * [PROBE_MM] above the layer's bottom, over the union of the parts, runs closer than [joinMm] joined), no lower than the
 * bottom plus [minMm]; anywhere else at the layer's top, where a lowered line is refused.
 */
class ZaaRule(parts: List<TriangleSoup>, private val zBase: Double, val minMm: Double, resolutionMm: Double, joinMm: Double) {
    private val cols = SolidColumns(parts, joinMm)
    /** How far off that height a line may be (mm): the slicing resolution (the straight move against the top's curve) and the file's µm rounding. */
    val tolMm = resolutionMm + CurvedCheck.ON_SURFACE_MM

    /** Where a line at (x, y) belongs on the layer from [bottom] to [top] (g-code Z, mm). */
    fun expected(x: Double, y: Double, bottom: Double, top: Double): Double {
        val t = cols.topAbove(x, y, zBase + bottom + PROBE_MM) - zBase
        if (t.isNaN() || t >= top) return top
        return minOf(top, maxOf(t, bottom + minMm))
    }

    /**
     * Whether Z [z] at (x, y) is where a line belongs there, within [tolMm]: at the model's top there, or, where the top steps
     * (a top meeting a face rising from it), at its height anywhere within the file's rounding of the point ([ROUND_MM]):
     * the slicer moves straight up at a step, at a position written to the µm.
     */
    fun fits(x: Double, y: Double, z: Double, bottom: Double, top: Double): Boolean {
        if (Math.abs(z - expected(x, y, bottom, top)) <= tolMm) return true
        var lo = Double.POSITIVE_INFINITY; var hi = Double.NEGATIVE_INFINITY
        for ((dx, dy) in AROUND) { val e = expected(x + dx * ROUND_MM, y + dy * ROUND_MM, bottom, top); lo = minOf(lo, e); hi = maxOf(hi, e) }
        return z >= lo - tolMm && z <= hi + tolMm
    }

    /**
     * Whether the straight line (x0, y0, z0) -> (x1, y1, z1) on the layer from [bottom] to [top] lies where it belongs ([fits])
     * at both ends and every [ALONG_MM] between; the first point off it (x, y, z, where it belongs), or null.
     */
    fun off(x0: Double, y0: Double, z0: Double, x1: Double, y1: Double, z1: Double, bottom: Double, top: Double): DoubleArray? {
        val n = maxOf(1, Math.ceil(Math.hypot(x1 - x0, y1 - y0) / ALONG_MM).toInt())
        for (i in 0..n) {
            val t = i.toDouble() / n
            val px = x0 + (x1 - x0) * t; val py = y0 + (y1 - y0) * t; val pz = z0 + (z1 - z0) * t
            if (!fits(px, py, pz, bottom, top)) return doubleArrayOf(px, py, pz, expected(px, py, bottom, top))
        }
        return null
    }

    companion object {
        /** As the slicer's (ZAntiAlias.PROBE_MM): the file's µm above the layer's bottom. */
        const val PROBE_MM = 0.001
        /** The file's rounding of a position (mm): X and Y are written to three decimals. */
        const val ROUND_MM = 0.001
        private val AROUND = listOf(0.0 to 0.0, 1.0 to 0.0, -1.0 to 0.0, 0.0 to 1.0, 0.0 to -1.0, 1.0 to 1.0, 1.0 to -1.0, -1.0 to 1.0, -1.0 to -1.0)
        /** How far apart a lowered line is sampled against the top (mm), as the curved check's lines are. */
        const val ALONG_MM = 0.5
    }
}
