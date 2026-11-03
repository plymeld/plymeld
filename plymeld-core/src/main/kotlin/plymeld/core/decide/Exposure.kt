package plymeld.core.decide

import plymeld.geom.Polygon
import java.util.BitSet
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicReferenceArray

/**
 * How much of an outer wall's point the eye reaches from around and above the print, 0..1: what places a least-visible
 * seam where nearby geometry hides it, sampled from a few horizontal and vertical angles (kept cheap to compute).
 *
 * Viewing directions are sampled: [Sample.azimuths] around, at [Sample.elevations] heights from level up to
 * [Sample.maxElevationDeg]. A direction counts by how squarely the wall faces it (the wall's outward normal, horizontal,
 * against the direction); it is blocked when a ray from the point toward it, marched [Sample.reachUm] across and rising
 * with it through the layers, meets material on a layer it passes. The exposure is the facing-weighted share of the
 * directions the wall faces that get through: an open wall reads 1, a wall inside a recess or facing another part close
 * by less, a wall with material right outside it 0. The material is each layer's outer-wall loops (even-odd: a hole is
 * air), the layer rasterised once, lazily, at [Sample.cellUm], so a ray step is one bit read; only the geometry within
 * the reach counts. Only the object's own geometry, by design: other objects on a plate are not placed with each other's
 * looks in mind (separate objects are not aligned with that in view). Not the bed.
 *
 * [outlines]: per layer, the outer-wall loops (µm); [topsUm]: each layer's top (µm, rising).
 *
 * [look]: the print looked at from a side (plan.view_from): each direction counts its quadrant's weight
 * ([Visibility.Look.weight]), and the point reads the weighted directions that see it over what the most-seen open wall
 * gets, so a wall facing the viewer's quadrant counts more than one facing the back. Null = every direction alike: the
 * facing-weighted share of the directions the wall faces that get through (an open wall reads 1 whichever way it faces).
 */
class Exposure(private val outlines: List<List<Polygon>>, private val topsUm: LongArray, val sample: Sample, val look: Visibility.Look? = null) {
    /** The sampling (method.quality.visibility.sample.*): directions around and up, the reach of a ray, and the raster's cell. */
    class Sample(val azimuths: Int, val elevations: Int, val maxElevationDeg: Double, val reachUm: Long, val cellUm: Long,
                 /**
                  * How deep behind a visible surface a seam still shows through it (method.quality.visibility.show_through): a ray
                  * that meets material it leaves within this distance of the seam, open beyond, counts 1 - that distance / this
                  * (a bead's bulge behind a thin wall pushes the wall out); 0 = any material hides the seam.
                  */
                 val showThroughUm: Long = 0) {
        init {
            require(azimuths >= 1 && elevations >= 1 && maxElevationDeg in 0.0..80.0 && reachUm >= 0 && cellUm > 0 && showThroughUm >= 0) {
                "visibility sampling: azimuths $azimuths and elevations $elevations at least 1, elevation.max $maxElevationDeg within 0..80 deg, reach ${reachUm / 1000.0} mm at least 0, cell ${cellUm / 1000.0} mm above 0, show_through ${showThroughUm / 1000.0} mm at least 0"
            }
        }
        fun words(): String = "%d directions around at %d heights up to %.0f deg, rays reaching %.1f mm on a %.2f mm grid"
            .format(java.util.Locale.ROOT, azimuths, elevations, maxElevationDeg, reachUm / 1000.0, cellUm / 1000.0)
    }

    init { require(outlines.size == topsUm.size) { "exposure: ${outlines.size} layers of outlines, ${topsUm.size} tops" } }

    private val cell = sample.cellUm
    private val minX: Long; private val minY: Long; private val cols: Int; private val rows: Int
    init {
        var x0 = Long.MAX_VALUE; var y0 = Long.MAX_VALUE; var x1 = Long.MIN_VALUE; var y1 = Long.MIN_VALUE
        for (layer in outlines) for (p in layer) for (i in 0 until p.size) {
            x0 = minOf(x0, p.x(i)); x1 = maxOf(x1, p.x(i)); y0 = minOf(y0, p.y(i)); y1 = maxOf(y1, p.y(i))
        }
        if (x0 > x1) { x0 = 0; y0 = 0; x1 = 0; y1 = 0 }
        minX = x0 - sample.reachUm - cell; minY = y0 - sample.reachUm - cell
        cols = ((x1 + sample.reachUm + cell - minX) / cell + 1).toInt()
        rows = ((y1 + sample.reachUm + cell - minY) / cell + 1).toInt()
    }

    private val rasters = AtomicReferenceArray<BitSet>(outlines.size)

    /** Layer [j]'s material: a cell is set when its center lies inside an odd number of the layer's loops. */
    private fun raster(j: Int): BitSet = rasters.get(j) ?: rasterise(outlines[j]).also { rasters.compareAndSet(j, null, it) }

    private fun rasterise(loops: List<Polygon>): BitSet {
        // every crossing of a row's center line by an edge, packed as (row, x - minX) and sorted: consecutive pairs per row bound the inside
        var packed = LongArray(256); var n = 0
        for (p in loops) {
            if (p.size < 3) continue
            for (i in 0 until p.size) {
                val ax = p.x(i); val ay = p.y(i); val bx = p.x((i + 1) % p.size); val by = p.y((i + 1) % p.size)
                if (ay == by) continue
                val lo = minOf(ay, by); val hi = maxOf(ay, by)
                // the rows whose center (minY + (r + 0.5) cell) lies in [lo, hi)
                var r = Math.ceil((lo - minY).toDouble() / cell - 0.5).toInt()
                while (r < rows) {
                    val yc = minY + (r + 0.5) * cell
                    if (yc >= hi) break
                    if (r >= 0 && yc >= lo) {
                        val xc = ax + (yc - ay) * (bx - ax).toDouble() / (by - ay)
                        if (n == packed.size) packed = packed.copyOf(n * 2)
                        packed[n++] = (r.toLong() shl 32) or (Math.round(xc - minX).coerceIn(0L, 0xFFFFFFFFL))
                    }
                    r++
                }
            }
        }
        java.util.Arrays.sort(packed, 0, n)
        val bits = BitSet(cols * rows)
        var k = 0
        while (k + 1 < n) {
            val r = (packed[k] ushr 32).toInt()
            if ((packed[k + 1] ushr 32).toInt() != r) { k++; continue }
            val xa = (packed[k] and 0xFFFFFFFFL).toDouble(); val xb = (packed[k + 1] and 0xFFFFFFFFL).toDouble()
            val c0 = maxOf(0, Math.ceil(xa / cell - 0.5).toInt()); val c1 = minOf(cols - 1, Math.floor(xb / cell - 0.5).toInt())
            if (c1 >= c0) bits.set(r * cols + c0, r * cols + c1 + 1)
            k += 2
        }
        return bits
    }

    private fun filled(j: Int, x: Double, y: Double): Boolean {
        val c = Math.floor((x - minX) / cell).toInt(); val r = Math.floor((y - minY) / cell).toInt()
        if (c < 0 || r < 0 || c >= cols || r >= rows) return false
        return raster(j).get(r * cols + c)
    }

    // the sampled directions: (cos azimuth, sin azimuth, cos elevation, tan elevation)
    private val dirs: List<DoubleArray> = (0 until sample.elevations).flatMap { e ->
        val el = Math.toRadians(if (sample.elevations == 1) 0.0 else sample.maxElevationDeg * e / (sample.elevations - 1))
        (0 until sample.azimuths).map { a ->
            val az = 2 * Math.PI * a / sample.azimuths
            doubleArrayOf(Math.cos(az), Math.sin(az), Math.cos(el), Math.tan(el))
        }
    }

    // each direction's weight by its quadrant ([look]; 1 each without one)
    private val weights: DoubleArray = DoubleArray(dirs.size) { look?.weight(dirs[it][0], dirs[it][1]) ?: 1.0 }

    /** The weighted facing of an open wall whose outward normal is ([nx], [ny]): the directions it faces, each by its weight. */
    private fun facing(nx: Double, ny: Double): Double {
        var t = 0.0
        for ((i, d) in dirs.withIndex()) { val f = (nx * d[0] + ny * d[1]) * d[2]; if (f > 1e-9) t += weights[i] * f }
        return t
    }

    /**
     * With a [look], what the most-seen open wall gets: the largest [facing] over normals toward each sampled azimuth and
     * toward the viewer (the bundled weights: the wall facing the viewer squarely), so a point reads at most about 1.
     */
    private val full: Double = look?.let { l ->
        (dirs.map { doubleArrayOf(it[0], it[1]) } + listOf(l.toward)).maxOf { n -> facing(n[0], n[1]) }
    } ?: 0.0

    /** The exposure of the wall at ([x], [y]) on layer [layer] whose outward normal is the unit vector ([nx], [ny]). */
    fun at(layer: Int, x: Long, y: Long, nx: Double, ny: Double): Double {
        var total = 0.0; var seen = 0.0
        val bottom = if (layer == 0) 0L else topsUm[layer - 1]
        val z0 = (bottom + topsUm[layer]) / 2.0
        for ((i, d) in dirs.withIndex()) {
            val f = (nx * d[0] + ny * d[1]) * d[2]
            if (f <= 1e-9) continue
            total += weights[i] * f
            seen += weights[i] * f * through(layer, x.toDouble(), y.toDouble(), d[0], d[1], d[3], z0)
        }
        // a point facing no direction (its two edges cancel) reads as seen in full, as before
        if (total == 0.0) return 1.0
        return if (look == null) seen / total else minOf(1.0, seen / full)
    }

    /**
     * How much of the ray from ([x], [y]) at height [z0] along azimuth ([ca], [sa]), rising [tanE] per unit across, gets through:
     * 1 when it leaves the reach meeting no material; 1 - d / show_through when the only material it meets ends d from the seam,
     * within [Sample.showThroughUm] (the seam close behind a thin wall shows through it: a part's seam 1.4 mm behind
     * a visible wall printed as two faint vertical lines, while the map counted it hidden); 0 when material reaches deeper, or
     * comes again after it.
     */
    private fun through(layer: Int, x: Double, y: Double, ca: Double, sa: Double, tanE: Double, z0: Double): Double {
        val top = topsUm[topsUm.size - 1]
        val show = sample.showThroughUm.toDouble()
        var j = layer
        var share = 1.0; var inside = false; var passed = false
        // start just outside the wall: the loop is the wall's center line
        var s = 1.5 * cell
        while (s <= sample.reachUm) {
            val z = z0 + tanE * s
            if (z > top) return if (inside) 0.0 else share
            while (j < topsUm.size - 1 && topsUm[j] < z) j++
            if (filled(j, x + ca * s, y + sa * s)) {
                if (passed || s > show) return 0.0
                inside = true
            } else if (inside) {
                // out of the thin material within the show-through depth: seen through it, less the deeper it lay; material
                // entered within the depth but reaching past it hides the seam (read as 1 - depth / show_through
                // there, below 0, one model's base seam scar summed to -9.2 mm²)
                if (s > show) return 0.0
                share = 1.0 - s / show; inside = false; passed = true
            }
            s += cell
        }
        return if (inside) 0.0 else share
    }

    private val perLoop: MutableMap<Polygon, DoubleArray> = Collections.synchronizedMap(IdentityHashMap())

    /** Every vertex's exposure on loop [p] of layer [layer] (its outward normal as [outward] reads it), kept per loop so the candidates of one decision share it. */
    fun ofLoop(layer: Int, p: Polygon): DoubleArray = perLoop[p] ?: DoubleArray(p.size) { i ->
        val n = outward(p.xy, i); at(layer, p.x(i), p.y(i), n[0], n[1])
    }.also { perLoop[p] = it }

    companion object {
        /**
         * The outward unit normal (x, y) at vertex [i] of a wall loop given as [xy] (µm pairs, closed): the mean of its two
         * edges' right-hand normals (the material is on a wall loop's left), as the seam placement and the seam measure both
         * read a point (one read it from the outgoing edge alone and they disagreed on ties). (0, 0) where the
         * two edges cancel.
         */
        fun outward(xy: LongArray, i: Int): DoubleArray {
            val n = xy.size / 2
            val pi = (i - 1 + n) % n; val ni = (i + 1) % n
            val ax = (xy[2 * i] - xy[2 * pi]).toDouble(); val ay = (xy[2 * i + 1] - xy[2 * pi + 1]).toDouble()
            val bx = (xy[2 * ni] - xy[2 * i]).toDouble(); val by = (xy[2 * ni + 1] - xy[2 * i + 1]).toDouble()
            val la = Math.hypot(ax, ay); val lb = Math.hypot(bx, by)
            var nx = (if (la > 0) ay / la else 0.0) + (if (lb > 0) by / lb else 0.0)
            var ny = -((if (la > 0) ax / la else 0.0) + (if (lb > 0) bx / lb else 0.0))
            val ln = Math.hypot(nx, ny)
            if (ln > 0) { nx /= ln; ny /= ln }
            return doubleArrayOf(nx, ny)
        }
    }
}
