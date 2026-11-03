package plymeld.core.decide

import plymeld.mesh.TriangleSoup

/**
 * The infill direction of an object (45 deg is a habit; lines cross the part's long axis): at 45 deg to the long axis
 * of its footprint as posed, so a grid's two line families cross the long axis at the same angle and neither runs along it (rectilinear
 * alternates between the two; cubic starts from it). The long axis is the principal axis of the
 * plan area of the upward-facing faces (the footprint seen from above).
 */
object InfillAngle {
    /** Moments this much smaller than the area's spread count as round: no long axis (a numerical tolerance, not a print value). */
    private const val ROUND = 1e-3

    /** The long axis, degrees from +X in [0, 180); 0 when the footprint has none (round or empty). */
    fun longAxisDeg(parts: List<TriangleSoup>): Double {
        var area = 0.0; var mx = 0.0; var my = 0.0; var ixx = 0.0; var iyy = 0.0; var ixy = 0.0
        for (p in parts) {
            val c = p.coords
            for (t in 0 until p.count) {
                val o = 9 * t
                val x1 = c[o].toDouble(); val y1 = c[o + 1].toDouble()
                val x2 = c[o + 3].toDouble(); val y2 = c[o + 4].toDouble()
                val x3 = c[o + 6].toDouble(); val y3 = c[o + 7].toDouble()
                val a = ((x2 - x1) * (y3 - y1) - (x3 - x1) * (y2 - y1)) / 2      // > 0: facing up (counter-clockwise from above)
                if (a <= 0) continue
                area += a; mx += a * (x1 + x2 + x3) / 3; my += a * (y1 + y2 + y3) / 3
                ixx += a / 6 * (x1 * x1 + x2 * x2 + x3 * x3 + x1 * x2 + x2 * x3 + x3 * x1)
                iyy += a / 6 * (y1 * y1 + y2 * y2 + y3 * y3 + y1 * y2 + y2 * y3 + y3 * y1)
                ixy += a / 12 * (2 * x1 * y1 + 2 * x2 * y2 + 2 * x3 * y3 + x1 * y2 + x2 * y1 + x1 * y3 + x3 * y1 + x2 * y3 + x3 * y2)
            }
        }
        if (area <= 0) return 0.0
        val cx = mx / area; val cy = my / area
        val cxx = ixx - area * cx * cx; val cyy = iyy - area * cy * cy; val cxy = ixy - area * cx * cy
        if (Math.hypot(cxx - cyy, 2 * cxy) <= ROUND * (cxx + cyy)) return 0.0
        return Math.floorMod(Math.round(Math.toDegrees(0.5 * Math.atan2(2 * cxy, cxx - cyy)) * 1000), 180_000L) / 1000.0
    }

    /** The infill direction: 45 deg to the long axis, in [0, 180), to 0.001 deg. */
    fun degrees(parts: List<TriangleSoup>): Double = Math.floorMod(Math.round((longAxisDeg(parts) + 45.0) * 1000), 180_000L) / 1000.0
}
