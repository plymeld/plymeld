package plymeld.core.decide

import plymeld.mesh.LayerPlan
import plymeld.mesh.TopSurface
import plymeld.mesh.TriangleSoup
import java.util.Locale

/**
 * The band depth: a curved top band covers the whole top and
 * starts as HIGH as the tool's thinnest layer allows, so the fewest layers bend. In the band a layer is (t - zs) / (H - zs)
 * times its warped thickness at a column whose top is t (zs the band's start, H the model's top): thinnest where the top
 * is lowest. So the start begins just under the top's lowest point and is lowered in [STEP_MM] steps until every layer
 * above it, with the layer heights the slicer will use (LayerPlan.uniform, evened above the start: evenAbove, as
 * IncrementalSlicer plans a curved object), is
 * at least the tool's thinnest at that lowest top. The same rule the pre-print check applies (CurvedCheck).
 */
object CurvedDepth {
    /** How finely the start is searched (mm). plymeld's choice: a 0.01 mm grid, far finer than a layer. */
    const val STEP_MM = 0.01
    /** How finely the top is sampled for its heights (mm). plymeld's choice: finer than a line. */
    const val SAMPLE_MM = 0.25

    /** The result: the band's start above the object's bottom (mm), and the thinnest layer it leaves (mm). */
    class Depth(val fromMm: Double, val thinnestMm: Double) {
        override fun toString() = "%.2f mm (thinnest layer %.3f mm)".format(Locale.ROOT, fromMm, thinnestMm)
    }

    /**
     * The highest start for [parts] (posed, the object's bottom on its lowest point) with first layer [firstMm], layers
     * [layerMm], the tool's thinnest [minMm]; null when even a start at the first layer's top leaves a layer too thin.
     */
    fun decide(parts: List<TriangleSoup>, firstMm: Double, layerMm: Double, minMm: Double): Depth? {
        val soup = merged(parts)
        val b = soup.bounds()
        val zMin = b[2]; val h = b[5] - zMin
        val top = TopSurface.of(soup, plymeld.core.IncrementalSlicer.UP_NZ)
        // the top's heights over the footprint (above the bottom), sampled
        val tops = ArrayList<Double>()
        var x = b[0]
        while (x <= b[3]) { var y = b[1]; while (y <= b[4]) { val t = top.top(x, y); if (!t.isNaN()) tops += t - zMin; y += SAMPLE_MM }; x += SAMPLE_MM }
        if (tops.isEmpty()) return null
        tops.sort()
        val uniform = LayerPlan.uniform(0.0, h, firstMm, layerMm, minMm)
        // the band starts below the top's lowest point, so the WHOLE top is in it and its layers follow all of it (a start
        // just under the crown passes the thickness rule trivially, bending one layer over the crown alone)
        val lowest = tops.first()
        var zs = lowest - STEP_MM
        while (zs >= firstMm - 1e-9) {
            // the band's layers one thickness above its start, as IncrementalSlicer plans them (LayerPlan.evenAbove)
            val thinnest = thinnest(uniform.evenAbove(maxOf(zs, firstMm), layerMm).tops, zs, h, lowest)
            if (thinnest >= minMm - 1e-9) return Depth(zs, thinnest)
            zs -= STEP_MM
        }
        return null
    }

    /** The thinnest real layer above [zs] at a column whose top is [t] (layer tops [layers], the band's top [hTop]). */
    private fun thinnest(layers: DoubleArray, zs: Double, hTop: Double, t: Double): Double {
        fun real(z: Double) = if (z <= zs) z else zs + (z - zs) * (t - zs) / (hTop - zs)
        var out = Double.POSITIVE_INFINITY
        for (k in layers.indices) {
            if (layers[k] <= zs + 1e-9) continue
            val below = if (k == 0) 0.0 else layers[k - 1]
            out = minOf(out, real(layers[k]) - real(below))
        }
        return out
    }

    /** The [parts] as one soup. */
    internal fun merged(parts: List<TriangleSoup>): TriangleSoup = if (parts.size == 1) parts[0] else {
        val c = FloatArray(parts.sumOf { it.count } * 9); var at = 0
        for (p in parts) { System.arraycopy(p.coords, 0, c, at, p.count * 9); at += p.count * 9 }
        TriangleSoup(c, parts.sumOf { it.count })
    }
}
