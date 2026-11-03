package plymeld.core.decide

import plymeld.geom.Booleans
import plymeld.geom.Polygon
import plymeld.geom.Polygons
import plymeld.geom.Units
import plymeld.mesh.Slicer
import plymeld.mesh.TriangleSoup

/**
 * The hard check before the weights: a bead with nothing under it that no
 * technique holds up cannot be laid, whatever its score. An overhang region's lowest layer is cut from the posed model
 * and read against the model's cross-section just below it: how far its material over air lies from that section is
 * its REACH. Within one line width a line still touches the layer below (how much of it may hang clean is the quality
 * rule, [plymeld.model.Overhang.stepMm]); beyond it the line lies entirely over air, and only a bridge, anchored on two
 * sides, can lay it. A region reaching past a line width that is anchored on one side only is a CANTILEVER: a candidate
 * leaving one unheld is rejected ([PoseDecision.decideWithTechnique]; support holds it, a locked spot too). Lines over
 * air anchored on two sides (a bridge) pass whatever the span: no bridging limit is known yet (a setting when it is).
 */
object Cantilever {
    /** The step the layer's outline is sampled at, and the anchor search marches in (mm). plymeld's choice: finer than a line. */
    const val SAMPLE_MM = 0.25
    /** Directions the anchor search casts from the farthest point. */
    private const val DIRECTIONS = 16
    /** How far the anchor search follows the layer before giving up (mm): longer than any bed. */
    private const val MAX_SPAN_MM = 1000.0

    /** How far the region's lowest layer reaches over air (mm), where (mm, the posed model's frame), and whether that point is anchored on two sides at least 90 deg apart. */
    class Reach(val reachMm: Double, val x: Double, val y: Double, val bridged: Boolean)

    /**
     * Region [r] (relative to the posed model's corner [b], as the survey gives it) of the posed model [p], its lowest
     * layer [layerMm] thick. Null when the region's bottom lies within the first layer (it stands on the bed) or its
     * lowest layer has nothing over air.
     */
    fun reach(p: TriangleSoup, b: DoubleArray, r: Coordination.Volume.Region, layerMm: Double): Reach? {
        if (layerMm <= 0 || r.fromMm < layerMm - 1e-9) return null
        val cut = Slicer.slice(p, doubleArrayOf(belowZ(b, r, layerMm), layerZ(b, r, layerMm)))      // ascending, as the slicer sweeps
        return reach(Area(cut[1].loops), Area(cut[0].loops), b, r)
    }

    /** The height the region's lowest layer is cut at, and the height just below it (the posed model's frame). */
    fun layerZ(b: DoubleArray, r: Coordination.Volume.Region, layerMm: Double) = b[2] + r.fromMm + layerMm / 2
    fun belowZ(b: DoubleArray, r: Coordination.Volume.Region, layerMm: Double) = b[2] + r.fromMm - layerMm / 2

    /**
     * A cross-section as an area: its loops merged (two shells meeting along an edge would count a point on it twice), its
     * edges on the model check's row-and-cell grid ([plymeld.core.check.ModelCheck.Grid]) for quick point tests: a Benchy
     * section has thousands of edges and a region is sampled thousands of times.
     */
    class Area(loops: List<Polygon>) {
        val loops: List<Polygon> = if (loops.size > 1) Booleans.unionPositive(loops) else loops
        private val s = Units.PER_MM.toDouble()
        private val grid = plymeld.core.check.ModelCheck.Grid(this.loops.flatMap { l -> (0 until l.size).map { i -> val j = (i + 1) % l.size; doubleArrayOf(l.x(i) / s, l.y(i) / s, l.x(j) / s, l.y(j) / s) } })
        val isEmpty: Boolean get() = loops.isEmpty()
        /** Inside the area (holes are loops too): an odd number of edges crossed to the right. */
        fun inside(xMm: Double, yMm: Double): Boolean = !isEmpty && grid.inside(xMm, yMm)
        /** The distance (mm) to the nearest edge, searched outward in doubling radii; infinite with no loops or beyond [MAX_SPAN_MM]. */
        fun distance(xMm: Double, yMm: Double): Double {
            if (isEmpty) return Double.POSITIVE_INFINITY
            var r = 2.0
            while (r <= MAX_SPAN_MM) { val d = grid.nearest(xMm, yMm, r); if (d <= r) return d; r *= 2 }
            return Double.POSITIVE_INFINITY
        }
    }

    /**
     * As [reach], with the region's lowest layer [layer] and the section just below it [below] already cut (one sweep for
     * every region of a pose: the caller's). The farthest point over air lies on the layer's OUTLINE (moving away from the
     * section below ends at the layer's edge), so the outline within the region's footprint is sampled every [SAMPLE_MM],
     * not the area: exact at the edge, and a few hundred points for a hull region instead of tens of thousands.
     */
    fun reach(layer: Area, below: Area, b: DoubleArray, r: Coordination.Volume.Region): Reach? {
        if (layer.isEmpty) return null
        val x0 = b[0] + r.x0 - 1e-6; val x1 = b[0] + r.x1 + 1e-6; val y0 = b[1] + r.y0 - 1e-6; val y1 = b[1] + r.y1 + 1e-6
        var far: DoubleArray? = null   // x, y, distance from the section below
        val s = Units.PER_MM.toDouble()
        for (l in layer.loops) for (i in 0 until l.size) {
            val j = (i + 1) % l.size
            val ax = l.x(i) / s; val ay = l.y(i) / s; val bx = l.x(j) / s; val by = l.y(j) / s
            val n = maxOf(1, Math.ceil(Math.hypot(bx - ax, by - ay) / SAMPLE_MM).toInt())
            for (k in 0..n) {
                val x = ax + (bx - ax) * k / n; val y = ay + (by - ay) * k / n
                if (x < x0 || x > x1 || y < y0 || y > y1 || below.inside(x, y)) continue
                val d = below.distance(x, y)
                if (far == null || d > far[2]) far = doubleArrayOf(x, y, d)
            }
        }
        val f = far ?: return null
        if (f[2].isInfinite()) return Reach(f[2], f[0], f[1], false)
        // anchors: from the farthest point, in each direction along the layer until it leaves the layer or reaches the
        // section below; bridged when two anchors lie at least a right angle apart
        val hits = ArrayList<Double>()
        for (k in 0 until DIRECTIONS) {
            val ang = 2 * Math.PI * k / DIRECTIONS
            val dx = Math.cos(ang) * SAMPLE_MM; val dy = Math.sin(ang) * SAMPLE_MM
            var x = f[0]; var y = f[1]; var gone = 0.0
            while (gone < MAX_SPAN_MM) {
                x += dx; y += dy; gone += SAMPLE_MM
                // the first step leaves the outline point in every direction; from the second on, a step outside the layer ends it
                if (gone > SAMPLE_MM + 1e-9 && !layer.inside(x, y)) break
                if (below.inside(x, y)) { hits += ang; break }
            }
        }
        val bridged = hits.any { h1 -> hits.any { h2 -> apart(h1, h2) >= Math.PI / 2 - 1e-9 } }
        return Reach(f[2], f[0], f[1], bridged)
    }

    private fun apart(a: Double, b: Double): Double { var d = Math.abs(a - b) % (2 * Math.PI); if (d > Math.PI) d = 2 * Math.PI - d; return d }

    /** Inside the area [loops] bound (holes are loops too): within an odd number of them. */
    internal fun inside(loops: List<Polygon>, xMm: Double, yMm: Double): Boolean = Area(loops).inside(xMm, yMm)

    /** The distance (mm) from (x, y) to the nearest edge of [loops]; infinite with none. */
    internal fun distance(loops: List<Polygon>, xMm: Double, yMm: Double): Double {
        var best = Double.POSITIVE_INFINITY
        val s = Units.PER_MM.toDouble()
        for (l in loops) {
            val n = l.size
            for (i in 0 until n) {
                val j = (i + 1) % n
                val ax = l.x(i) / s; val ay = l.y(i) / s; val bx = l.x(j) / s; val by = l.y(j) / s
                val vx = bx - ax; val vy = by - ay; val len2 = vx * vx + vy * vy
                val t = if (len2 == 0.0) 0.0 else minOf(1.0, maxOf(0.0, ((xMm - ax) * vx + (yMm - ay) * vy) / len2))
                best = minOf(best, Math.hypot(xMm - (ax + t * vx), yMm - (ay + t * vy)))
            }
        }
        return best
    }
}
